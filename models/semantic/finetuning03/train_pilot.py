"""Run official Ultralytics semantic trainer with local audit hooks.
The trainer fixes BN statistics and adds the declared L2-SP retention term. Class IDs and supervised loss are preserved.
"""
import hashlib,json,sys,time,traceback,os
from pathlib import Path
import configure_offline
import numpy as np,torch
from retention_loss import RetentionLoss
from ultralytics import YOLO,settings
from ultralytics.models.yolo.semantic import SemanticSegmentationTrainer
ROOT=Path(__file__).resolve().parent
INITIAL=Path(os.environ.get('DM_INITIAL_WEIGHTS',str(ROOT/'weights/yolo26n-sem-ade20k.pt')))
INITIAL_SHA='68ac71bef2868c987fff7cd7c49cb656922f6d8447ad21af3893305577435000'
RUN=ROOT/'runs/pilot03c-l2sp'
AUDIT=ROOT/'pilot03c-l2sp-audit.jsonl'
TRAINER_INITIAL={}

def sha(path):
    path=Path(path);path=path if path.is_absolute() else ROOT/path
    return hashlib.sha256(path.read_bytes()).hexdigest()
def save(path,obj):Path(path).write_text(json.dumps(obj,ensure_ascii=False,indent=2,allow_nan=False),encoding='utf-8')
def log(event,**values):
    with AUDIT.open('a',encoding='utf-8') as f:f.write(json.dumps(dict(event=event,elapsedSeconds=time.perf_counter()-START,**values),ensure_ascii=False,allow_nan=False)+'\n')
def tensor_records(model):
    return {name:dict(shape=list(t.shape),dtype=str(t.dtype),sha256=hashlib.sha256(t.detach().cpu().contiguous().numpy().tobytes()).hexdigest()) for name,t in model.state_dict().items()}

class AuditedTrainer(SemanticSegmentationTrainer):
    def __init__(self,*args,**kwargs):
        self.actual_optimizer_steps=0
        super().__init__(*args,**kwargs)
    def _model_train(self):
        super()._model_train()
        # Freeze running mean/variance only. Affine gradients retain original policy.
        for module in self.model.modules():
            if isinstance(module, torch.nn.modules.batchnorm._BatchNorm):
                module.eval()
    def preprocess_batch(self,batch):
        batch=super().preprocess_batch(batch)
        self.current_batch_images=list(batch['im_file'])
        mask=batch['semantic_mask'];self.current_valid_pixels=int((mask!=255).sum().item())
        self.current_ignore_pixels=int((mask==255).sum().item())
        return batch
    def optimizer_step(self):
        if self.actual_optimizer_steps in (0,4,11,119,239,359):
            log('retention',step=self.actual_optimizer_steps+1,**self.model.criterion.last_record)
        super().optimizer_step();self.actual_optimizer_steps+=1
        log('optimizer_step',epoch=int(self.epoch)+1,step=self.actual_optimizer_steps,
            validPixels=self.current_valid_pixels,ignorePixels=self.current_ignore_pixels,
            images=[Path(p).name for p in self.current_batch_images],
            losses={k:float(v.detach()) for k,v in self.loss_items.items()})

def setup_done(trainer):
    global TRAINER_INITIAL
    TRAINER_INITIAL={k:v.detach().cpu().clone() for k,v in trainer.model.state_dict().items()}
    save(ROOT/'trainable-parameters.json',dict(trainable=[n for n,p in trainer.model.named_parameters() if p.requires_grad],
        frozen=[n for n,p in trainer.model.named_parameters() if not p.requires_grad],
        trainableCount=sum(p.numel() for p in trainer.model.parameters() if p.requires_grad),
        frozenCount=sum(p.numel() for p in trainer.model.parameters() if not p.requires_grad)))
    save(ROOT/'trainer-initial-tensor-hashes.json',tensor_records(trainer.model))
    log('trainer_ready',trainImages=len(trainer.train_loader.dataset),valImages=len(trainer.test_loader.dataset),
        classCount=trainer.data['nc'],device=str(trainer.device))
    assert trainer.data['nc']==150 and trainer.data.get('label_mapping') in (None,{})
    coefficient=json.loads((ROOT/'coefficient.json').read_text())['lambda']
    trainer.model.criterion=RetentionLoss(trainer.model.init_criterion(),trainer.model,coefficient)

def epoch_done(trainer):
    snapshot=ROOT/'fp32-ema';snapshot.mkdir(exist_ok=True)
    torch.save({k:v.detach().cpu().clone() for k,v in trainer.ema.ema.state_dict().items()},snapshot/f'epoch-{int(trainer.epoch)+1:03d}.pt')
    log('epoch_complete',epoch=int(trainer.epoch)+1,optimizerSteps=trainer.actual_optimizer_steps,
        losses={k:float(v.detach()) for k,v in trainer.tloss.items()},
        metrics={k:float(v) for k,v in trainer.metrics.items()},fitness=float(trainer.fitness),bestFitness=float(trainer.best_fitness))

def main():
    global START
    START=time.perf_counter()
    assert not RUN.exists() and not AUDIT.exists(),'Preserve runs: choose a new run name for another experiment.'
    assert sha(INITIAL)==INITIAL_SHA
    freeze=json.loads((ROOT/'data-freeze.json').read_text(encoding='utf-8'))
    assert freeze['status']=='ready' and freeze['ignoreIndex']==255 and freeze['numClasses']==150
    for p,h in freeze['stagedFileHashes'].items():assert sha(p)==h
    config=json.loads((ROOT/'pilot_config.json').read_text())
    assert (ROOT/'coefficient.json').exists()
    assert torch.cuda.is_available(),'GPU configuration requires a verified GPU.'
    torch.set_num_threads(4)
    settings.update({key:False for key in ['sync','clearml','comet','dvc','mlflow','raytune','tensorboard','wandb']})
    save(ROOT/'execution.json',dict(status='running',argv=sys.argv,initialSha256=INITIAL_SHA,
        configSha256=sha(ROOT/'pilot_config.json'),dataFreezeSha256=sha(ROOT/'data-freeze.json'),config=config,
        testUsedForTrainingOrSelection=False))
    # One frozen baseline on the exact staged validation pixels, prior to the first optimizer step.
    model=YOLO(str(INITIAL),task='semantic')
    baseline=model.val(data=str(ROOT/'staged-data/dataset.yaml'),split='val',imgsz=320,batch=2,device='0',workers=0,
        rect=False,amp=False,plots=False,save_json=True,project=str(ROOT/'runs'),name='baseline-val',exist_ok=False)
    save(ROOT/'baseline-val.json',dict(metrics={k:float(v) for k,v in baseline.results_dict.items()},
        note='AI-assisted partial-label validation pixels only, same staged 320 grid; no heldout test.'))
    model=YOLO(str(INITIAL),task='semantic')
    model.add_callback('on_pretrain_routine_end',setup_done)
    model.add_callback('on_fit_epoch_end',epoch_done)
    log('training_start',config=config)
    model.train(trainer=AuditedTrainer,data=str(ROOT/'staged-data/dataset.yaml'),project=str(ROOT/'runs'),name='pilot03c-l2sp',**config)
    trainer=model.trainer
    actual_changes=[]
    parameter_names=dict(trainer.model.named_parameters())
    for key,tensor in trainer.model.state_dict().items():
        current=tensor.detach().cpu();before=TRAINER_INITIAL[key]
        if not torch.equal(before,current):
            delta=current.double()-before.double()
            actual_changes.append(dict(name=key,parameter=key in parameter_names,
                requiresGrad=bool(parameter_names[key].requires_grad) if key in parameter_names else False,
                changedElements=int(torch.count_nonzero(delta)),maxAbs=float(delta.abs().max())))
    save(ROOT/'actual-trainer-weight-changes.json',dict(comparison='Raw trainer states before/after optimization; excludes checkpoint FP16 serialization rounding',
        changes=actual_changes,changedTensorCount=len(actual_changes),
        changedTrainableParameterTensorCount=sum(x['parameter'] and x['requiresGrad'] for x in actual_changes)))
    assert any(x['parameter'] and x['requiresGrad'] for x in actual_changes)
    state={}
    initial_model=YOLO(str(INITIAL),task='semantic').model.float()
    base=initial_model.state_dict()
    for label in ['best','last']:
        path=RUN/f'weights/{label}.pt';trained=YOLO(str(path),task='semantic').model.float()
        assert trained.names==initial_model.names and trained.model[-1].nc==150
        changes=[];unchanged=[]
        for key,tensor in trained.state_dict().items():
            old=base[key];assert old.shape==tensor.shape
            if torch.equal(old,tensor):unchanged.append(key)
            else:
                delta=tensor.double()-old.double()
                changes.append(dict(name=key,elements=int(torch.count_nonzero(delta)),maxAbs=float(delta.abs().max()),sumSquared=float((delta*delta).sum())))
        state[label]=dict(path=str(path),bytes=path.stat().st_size,sha256=sha(path),changedTensors=len(changes),unchangedTensors=len(unchanged),changes=changes)
        save(ROOT/f'{label}-tensor-hashes.json',tensor_records(trained))
        assert changes,'Training must change learned weights.'
    for p,h in freeze['stagedFileHashes'].items():assert sha(p)==h
    assert sha(INITIAL)==INITIAL_SHA
    save(ROOT/'training-result.json',dict(status='completed',epochs=int(trainer.epoch)+1,optimizerSteps=trainer.actual_optimizer_steps,
        elapsedSeconds=time.perf_counter()-START,weights=state,baselineVal=json.loads((ROOT/'baseline-val.json').read_text()),
        finalTrainerMetrics={k:float(v) for k,v in trainer.metrics.items()},bestFitness=float(trainer.best_fitness),
        originalWeightsUnchanged=True,stagedInputsUnchanged=True,testUsed=False,
        annotationLimit='AI-assisted partial polygons; not human-reviewed full ground truth',
        peakGpuAllocatedBytes=torch.cuda.max_memory_allocated(),peakGpuReservedBytes=torch.cuda.max_memory_reserved()))
    execution=json.loads((ROOT/'execution.json').read_text());execution.update(status='completed',exitCode=0);save(ROOT/'execution.json',execution)
    print(json.dumps(dict(status='completed',epochs=int(trainer.epoch)+1,optimizerSteps=trainer.actual_optimizer_steps,
        weights={k:{a:b for a,b in v.items() if a!='changes'} for k,v in state.items()}),indent=2))

if __name__=='__main__':
    try:main()
    except Exception as e:
        save(ROOT/'training-failure.json',dict(error=repr(e),traceback=traceback.format_exc(),elapsedSeconds=time.perf_counter()-START))
        raise
