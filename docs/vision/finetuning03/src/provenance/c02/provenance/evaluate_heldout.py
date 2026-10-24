from pathlib import Path
import argparse, json, hashlib, time, os
import numpy as np
from PIL import Image, ImageDraw
ROOT=Path(__file__).resolve().parent
def sha(p):return hashlib.sha256(Path(p).read_bytes()).hexdigest()
def save(p,value):Path(p).write_text(json.dumps(value,ensure_ascii=False,indent=2,allow_nan=False),encoding='utf-8')
def confusion(gt,pred,n=150):
    assert gt.shape==pred.shape and np.all((gt<n)|(gt==255)) and np.all(pred<n)
    keep=gt!=255
    return np.bincount((gt[keep].astype(np.int64)*n+pred[keep]),minlength=n*n).reshape(n,n)
def metrics(cm):
    support=cm.sum(axis=1);predicted=cm.sum(axis=0);tp=np.diag(cm);union=support+predicted-tp
    present=support>0;all_present=union>0
    iou=np.divide(tp,union,out=np.zeros(len(tp),dtype=float),where=union>0)
    return {'annotatedPixels':int(cm.sum()),'pixelAccuracy':float(tp.sum()/cm.sum()) if cm.sum() else None,
      'macroIoUSupportedGT':float(iou[present].mean()) if present.any() else None,
      'macroIoUUnionSupported':float(iou[all_present].mean()) if all_present.any() else None,
      'classes':{str(c):{'gtPixels':int(support[c]),'predictedOnLabeledPixels':int(predicted[c]),'truePositivePixels':int(tp[c]),
         'iou':float(iou[c]) if support[c] else None,'recall':float(tp[c]/support[c]) if support[c] else None,
         'precision':float(tp[c]/predicted[c]) if predicted[c] else None} for c in range(len(tp)) if union[c]},
      'falsePositiveOnlyClasses':{str(c):int(predicted[c]) for c in range(len(tp)) if not support[c] and predicted[c]}}
def risks(cm):
    eligible=[1,8,43]
    return {'carLabeledPixels':int(cm[20].sum()),'carPredictedAsBuildingWindowSign':int(cm[20,eligible].sum()),
       'roadSidewalkLabeledPixels':int(cm[[6,11],:].sum()),
       'roadSidewalkPredictedAsBuildingWindowSign':int(cm[np.ix_([6,11],eligible)].sum()),
       'signboardLabeledPixels':int(cm[43].sum()),'signboardPredictedOutsideEligibleClasses':int(cm[43].sum()-cm[43,eligible].sum()),
       'personFalsePositiveOnOtherLabeledClasses':int(cm[:,12].sum()-cm[12,12]),
       'note':'Semantic confusion on labeled pixels only; not actual light-mask leakage/recall or object detection accuracy'}
def run(args):
    import cv2,onnxruntime as ort
    os.environ['ORT_DISABLE_TELEMETRY']='1'
    out=Path(args.output);out.mkdir(parents=True,exist_ok=False)
    ready=json.loads((ROOT/'dataset-ready.json').read_text(encoding='utf-8-sig'))
    for p,h in ready['fileHashes'].items():assert sha(ROOT/p)==h
    manifest=json.loads((ROOT/'split-manifest.json').read_text(encoding='utf-8'))
    samples=[s for s in manifest['samples'] if s['split']=='test'];assert len(samples)==8
    paths={'baseline':Path(args.baseline),'finetuned':Path(args.candidate)}
    hashes={k:sha(p) for k,p in paths.items()}
    config=json.loads((ROOT/'evaluation-protocol.json').read_text())
    save(out/'run-start.json',{'models':{k:{'path':str(p),'sha256':hashes[k]} for k,p in paths.items()},
        'datasetFingerprint':ready['datasetFingerprint'],'protocolSha256':sha(ROOT/'evaluation-protocol.json'),
        'scope':'independent8-image heldout; same CPU preprocessing; frozen AI-assisted partial labels'})
    options=ort.SessionOptions();options.intra_op_num_threads=2;options.inter_op_num_threads=1
    sessions={k:ort.InferenceSession(str(p),sess_options=options,providers=['CPUExecutionProvider']) for k,p in paths.items()}
    for session in sessions.values():
        assert session.get_inputs()[0].shape==[1,3,320,320]
        assert session.get_outputs()[0].shape==[1,320,320] and session.get_outputs()[0].type=='tensor(uint8)'
    total={k:np.zeros((150,150),dtype=np.int64) for k in paths};per_video={};records=[]
    for s in samples:
        rgb=np.asarray(Image.open(ROOT/s['image']).convert('RGB'));gt=np.asarray(Image.open(ROOT/s['mask']))
        h,w=gt.shape;scale=min(320/h,320/w);rw,rh=round(w*scale),round(h*scale)
        left=round((320-rw)/2-.1);top=round((320-rh)/2-.1)
        letterbox=np.full((320,320,3),114,np.uint8)
        letterbox[top:top+rh,left:left+rw]=cv2.resize(rgb,(rw,rh),interpolation=cv2.INTER_LINEAR)
        tensor=np.ascontiguousarray(letterbox.transpose(2,0,1)[None].astype(np.float32)/255)
        results={};preds={}
        for name,session in sessions.items():
            start=time.perf_counter();raw=session.run(None,{session.get_inputs()[0].name:tensor})[0]
            duration=time.perf_counter()-start
            assert raw.dtype==np.uint8 and raw.shape==(1,320,320) and raw.max()<150
            pred=cv2.resize(raw[0,top:top+rh,left:left+rw],(w,h),interpolation=cv2.INTER_NEAREST_EXACT)
            cm=confusion(gt,pred);total[name]+=cm
            per_video.setdefault(s['clipId'],{k:np.zeros((150,150),dtype=np.int64) for k in paths})[name]+=cm
            dest=out/'predictions'/name;dest.mkdir(parents=True,exist_ok=True)
            Image.fromarray(raw[0]).save(dest/f"{s['sampleId']}-320.png")
            Image.fromarray(pred).save(dest/f"{s['sampleId']}-640.png")
            results[name]={'metrics':metrics(cm),'risks':risks(cm),'inferenceMs':duration*1000,
                           'prediction640Sha256':sha(dest/f"{s['sampleId']}-640.png")};preds[name]=pred
        # Green/red only inside annotated regions: ignored pixels remain original RGB.
        panels=[Image.fromarray(rgb)]
        for name in ('baseline','finetuned'):
            overlay=rgb.copy();keep=gt!=255;correct=keep&(preds[name]==gt);wrong=keep&~correct
            overlay[correct]=(rgb[correct]*.5+np.array([30,220,70])*.5).astype(np.uint8)
            overlay[wrong]=(rgb[wrong]*.5+np.array([250,40,40])*.5).astype(np.uint8)
            panels.append(Image.fromarray(overlay))
        figure=Image.new('RGB',(1920,390),'#151515');draw=ImageDraw.Draw(figure)
        for i,(panel,label) in enumerate(zip(panels,['RGB','BASELINE: labeled pixels only green correct / red mismatch','FINETUNED: identical fixed partial labels'])):
            figure.paste(panel,(i*640,30));draw.text((i*640+8,8),label,fill='white')
        figure.save(out/f"{s['sampleId']}-comparison.jpg",quality=95)
        records.append({'sampleId':s['sampleId'],'clipId':s['clipId'],'sourcePtsMs':s['sourcePtsMs'],
            'sourceRgbSha256':s['sourceRgbSha256'],'maskSha256':s['maskSha256'],'results':results})
    report={'status':'completed','protocol':config,'datasetFingerprint':ready['datasetFingerprint'],
      'models':{k:{'path':str(p),'sha256':hashes[k]} for k,p in paths.items()},
      'overall':{k:{'metrics':metrics(v),'risks':risks(v)} for k,v in total.items()},
      'perVideo':{clip:{k:{'metrics':metrics(v),'risks':risks(v)} for k,v in data.items()} for clip,data in per_video.items()},
      'samples':records,'confusionMatrices':{k:v.tolist() for k,v in total.items()},
      'inputAndLabelsUnchanged':True,'testUsedForTrainingOrSelection':False,'androidInferenceTest':False}
    for p,h in ready['fileHashes'].items():assert sha(ROOT/p)==h
    for k,p in paths.items():assert sha(p)==hashes[k]
    save(out/'evaluation.json',report)
    print(json.dumps({k:v['metrics'] for k,v in report['overall'].items()},ensure_ascii=False))
if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('--baseline',required=True);p.add_argument('--candidate',required=True);p.add_argument('--output',required=True)
    run(p.parse_args())
