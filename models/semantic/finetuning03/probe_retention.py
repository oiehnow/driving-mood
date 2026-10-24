import hashlib,json,sys,time
from pathlib import Path
ROOT=Path(__file__).resolve().parent
sys.path.insert(0,str(ROOT))
import train_pilot as driver
import torch
from ultralytics import YOLO
class ProbeDone(Exception):pass
class ProbeTrainer(driver.AuditedTrainer):
    def optimizer_step(self):
        # Plain supervised probe, intentionally not using RetentionLoss.
        step=self.actual_optimizer_steps+1
        params=[(n,p) for n,p in self.model.named_parameters() if p.requires_grad]
        N=sum(p.numel() for _,p in params)
        if step==1:
            assert all(torch.equal(p.detach().cpu(),ANCHOR[n]) for n,p in params)
        if step==5:
            sup=sum(float(p.grad.detach().double().square().sum()) for _,p in params if p.grad is not None)**0.5
            reg=sum(float((2*(p.detach().cpu().double()-ANCHOR[n].double())/N*2).square().sum()) for n,p in params)**0.5
            assert sup>0 and reg>0
            driver.save(ROOT/'coefficient.json',dict(lambda_=0.1*sup/reg,**{'lambda':0.1*sup/reg},
                targetGradientRatio=.1,supervisedGradientNormBeforeClip=sup,unitCoefficientRegularizerGradientNorm=reg,
                measuredBeforeOptimizerStep=5,trainableElements=N,batchSize=2,amp=False,scalerScale=float(self.scaler.get_scale()),
                coefficientSelectionUsedOnlyTraining=True,developmentValidationUsed=False,heldoutUsed=False))
        super(driver.AuditedTrainer,self).optimizer_step()
        self.actual_optimizer_steps+=1
        driver.log('probe_optimizer_step',step=step,images=[Path(p).name for p in self.current_batch_images],
            losses={k:float(v.detach()) for k,v in self.loss_items.items()})
        if step==5:raise ProbeDone()
def setup(trainer):
    global ANCHOR
    ANCHOR={n:p.detach().cpu().clone() for n,p in trainer.model.named_parameters() if p.requires_grad}
    driver.save(ROOT/'probe-initial-tensor-hashes.json',driver.tensor_records(trainer.model))
    assert len(trainer.test_loader.dataset)==len(trainer.train_loader.dataset)>0
driver.START=time.perf_counter();driver.AUDIT=ROOT/'probe-audit.jsonl'
assert not driver.AUDIT.exists() and not (ROOT/'coefficient.json').exists()
config=json.loads((ROOT/'pilot_config.json').read_text());config.update(val=False,plots=False,save=False)
torch.set_num_threads(4)
assert driver.sha(driver.INITIAL)==driver.INITIAL_SHA
model=YOLO(str(driver.INITIAL),task='semantic');model.add_callback('on_pretrain_routine_end',setup)
try:
    model.train(trainer=ProbeTrainer,data=str(ROOT/'staged-data/probe-train-only.yaml'),project=str(ROOT/'probe-runs'),name='discarded-five-step',**config)
    raise RuntimeError('Probe must stop at exactly five optimizer updates')
except ProbeDone:
    driver.save(ROOT/'probe-completion.json',dict(status='completed-deliberate-five-step-stop',optimizerSteps=5,
        elapsedSeconds=time.perf_counter()-driver.START,checkpointUsedForMain=False,initialPenaltyExactlyZero=True,
        coefficientSha256=driver.sha(ROOT/'coefficient.json')))
    print((ROOT/'coefficient.json').read_text())
