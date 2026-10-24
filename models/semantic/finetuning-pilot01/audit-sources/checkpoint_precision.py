"""Two-epoch deterministic replay for serialization diagnosis only.
Uses the already frozen 30-epoch schedule and train/val data. No test, no selection change.
"""
import copy,hashlib,json,time
from pathlib import Path
import configure_offline
import torch
from ultralytics import YOLO
from ultralytics.models.yolo.semantic import SemanticSegmentationTrainer
from ultralytics.utils.metrics import SemanticMetrics
ROOT=Path(__file__).resolve().parent;OUT=ROOT/'precision-diagnostic';OUT.mkdir(exist_ok=False)
torch.set_num_threads(4)
def save(path,obj):Path(path).write_text(json.dumps(obj,indent=2),encoding='utf-8')
def sha(path):return hashlib.sha256(Path(path).read_bytes()).hexdigest()
official_best=ROOT/'runs/pilot-01/weights/best.pt';before=sha(official_best)

class PrecisionTrainer(SemanticSegmentationTrainer):
    def final_eval(self):
        # The in-memory epoch-two model is compared directly in the callback; no model re-selection.
        pass

def compare(trainer):
    if trainer.epoch!=1:return
    source=trainer.ema.ema
    variants={'in_memory_fp32':copy.deepcopy(source).float().eval(),
        'rounded_half_then_fp32':copy.deepcopy(source).half().float().eval()}
    variants['rounded_half_fused_fp32']=copy.deepcopy(variants['rounded_half_then_fp32']).fuse(verbose=False).eval()
    variants['frozen_official_best']=YOLO(str(official_best),task='semantic').model.float().cuda().fuse(verbose=False).eval()
    predictions={k:[] for k in variants};metrics={k:SemanticMetrics(names=source.names) for k in variants};all_targets=[]
    with torch.inference_mode():
        for batch in trainer.test_loader:
            x=batch['img'].to(trainer.device).float()/255;target=batch['semantic_mask'].to(trainer.device,dtype=torch.int32)
            all_targets.append(target.cpu())
            for name,model in variants.items():
                logits=model(x);logits=logits[0] if isinstance(logits,tuple) else logits
                pred=torch.nn.functional.interpolate(logits,size=target.shape[-2:],mode='bilinear',align_corners=False).argmax(1).to(torch.int32)
                metrics[name].update_stats(pred,target);predictions[name].append(pred.cpu())
    target=torch.cat(all_targets);valid=target!=255;predictions={k:torch.cat(v) for k,v in predictions.items()}
    results={}
    for name,metric in metrics.items():
        metric.process();cm=metric.matrix.cpu();rows=cm.sum(1);cols=cm.sum(0);diag=cm.diag();classes={}
        for c in torch.nonzero(rows>0).flatten().tolist():
            classes[str(c)]=dict(name=source.names[c],groundTruthPixels=int(rows[c]),iou=float(diag[c]/(rows[c]+cols[c]-diag[c])))
        results[name]=dict(metrics={k:float(v) for k,v in metric.results_dict.items()},classes=classes,
            differsFromInMemoryAll=int((predictions[name]!=predictions['in_memory_fp32']).sum()),
            differsFromInMemoryLabeled=int(((predictions[name]!=predictions['in_memory_fp32'])&valid).sum()))
    replay_state=variants['rounded_half_then_fp32'].state_dict()
    selected=YOLO(str(official_best),task='semantic').model.float().state_dict()
    differing=[key for key,value in selected.items() if not torch.equal(value.cpu(),replay_state[key].cpu())]
    save(OUT/'precision-comparison.json',dict(diagnosticOnly=True,testUsed=False,selectedCheckpointUnchanged=True,
        replayEpoch=2,sameFrozen30EpochSchedule=True,trainingValidatorMetrics={k:float(v) for k,v in trainer.metrics.items()},
        roundedReplayVsFrozenBestDifferentTensorNames=differing,variants=results,
        originalBestSha256=before,originalBestUnchanged=sha(official_best)==before))
    print(json.dumps(results,indent=2))
    trainer.stop=True

config=json.loads((ROOT/'pilot_config.json').read_text());config['plots']=False
model=YOLO(str(ROOT.parent/'B-modern-models-2026-10-24/yolo26n-sem-ade20k.pt'),task='semantic')
model.add_callback('on_fit_epoch_end',compare)
model.train(trainer=PrecisionTrainer,data=str(ROOT/'staged-data/dataset.yaml'),project=str(OUT),name='replay',**config)
assert sha(official_best)==before
