"""Frozen pilot02 development comparison. No training, app changes, or automatic promotion."""
from pathlib import Path
import argparse, csv, hashlib, importlib.util, json, os, re, shutil, sys, textwrap, time
import numpy as np
from PIL import Image, ImageDraw, ImageOps

ROOT = Path(__file__).resolve().parent
OLD_SHA = 'f796d1a50074e06a2f5a1aaefaa0ed354699edc8e33a41b46fbfe97ac2aa8299'
BASE_SHA = '3af7911808fd81e95982b3541fe4c1151016fc6e596b872d0b62f3874d575dad'
SCOPES = {'data02_development_val': 'val', 'pilot01_fixed_regression': 'test'}
FOCUS = {0:'wall',1:'building',2:'sky',6:'road',8:'window',11:'sidewalk',12:'person',20:'car',43:'signboard',80:'bus',83:'truck'}
LIMIT = 'AI partial interior annotations only. Ignore255 outside false positives and full boundaries are unmeasured; no emotion ground truth, no new independent test.'

def sha(path): return hashlib.sha256(Path(path).read_bytes()).hexdigest()
def read(path): return json.loads(Path(path).read_text(encoding='utf-8-sig'))
def save(path, value): Path(path).write_text(json.dumps(value,ensure_ascii=False,indent=2,allow_nan=False),encoding='utf-8')
def require(condition, message):
    if not condition: raise ValueError(message)

def load_legacy():
    source = ROOT/'provenance/evaluate_heldout.py'
    require(sha(source)==OLD_SHA, 'Legacy source changed')
    spec=importlib.util.spec_from_file_location('frozen_pilot01_evaluator',source)
    module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module)
    text=source.read_text(encoding='utf-8')
    start=text.index('        rgb=np.asarray',text.index('    for s in samples:'))
    end=text.index('        results={};preds={}',start)
    prep=compile(textwrap.dedent(text[start:end]),str(source)+':preprocess','exec')
    line=next(line.strip() for line in text.splitlines() if line.strip().startswith('pred=cv2.resize(raw[0,'))
    return module,prep,compile(line,str(source)+':alignment','exec')

LEGACY, PREP, ALIGN = load_legacy()

def preprocess(root, sample):
    import cv2
    env={'ROOT':root,'s':sample,'np':np,'Image':Image,'cv2':cv2}
    exec(PREP,env)
    rgb,gt=env['rgb'],env['gt']
    require(gt.ndim==2 and gt.dtype==np.uint8 and rgb.shape[:2]==gt.shape,'Invalid RGB/label grid')
    require(np.all((gt<150)|(gt==255)), 'Labels must be ADE0..149 or ignore255')
    return env

def aligned(raw, env):
    require(raw.dtype==np.uint8 and raw.shape==(1,320,320) and np.all(raw<150),'Invalid model output')
    env=dict(env,raw=raw);exec(ALIGN,env);return env['pred']

def confusion(gt,pred):
    require(gt.shape==pred.shape and gt.ndim==2,'Confusion shape mismatch')
    require(np.all(((gt>=0)&(gt<150))|(gt==255)) and np.all((pred>=0)&(pred<150)),'Invalid class IDs')
    return LEGACY.confusion(gt,pred)

def metrics(cm):
    result=LEGACY.metrics(cm)
    support=cm.sum(1);pred=cm.sum(0);tp=np.diag(cm);n=int(cm.sum())
    rows={}
    for c in range(150):
        union=int(support[c]+pred[c]-tp[c])
        rows[str(c)]={'name':FOCUS.get(c,f'ADE-{c}'),'gtPixels':int(support[c]),'predictedOnLabeledPixels':int(pred[c]),
            'truePositivePixels':int(tp[c]),'unionPixels':union,'iou':float(tp[c]/union) if support[c] else None,
            'recall':float(tp[c]/support[c]) if support[c] else None,'precision':float(tp[c]/pred[c]) if pred[c] else None}
    result['classes']=rows
    result['frequencyWeightedPixelAccuracy']=result['pixelAccuracy']
    result['supportWeightedIoU']=float(sum(support[c]*rows[str(c)]['iou'] for c in range(150) if support[c])/n) if n else None
    vehicles=[20,80,83];group_tp=int(cm[np.ix_(vehicles,vehicles)].sum())
    group_gt=int(cm[vehicles,:].sum());group_pred=int(cm[:,vehicles].sum())
    result['vehicleGroup20_80_83']={'gtPixels':group_gt,'predictedOnLabeledPixels':group_pred,'truePositivePixels':group_tp,
        'iou':group_tp/(group_gt+group_pred-group_tp) if group_gt else None,
        'predictedAsBuildingWindowSign':int(cm[np.ix_(vehicles,[1,8,43])].sum())}
    return result

def checked_file(item):
    require(isinstance(item.get('path'),str) and item['path'], 'Missing final path')
    require(isinstance(item.get('sha256'),str) and re.fullmatch('[0-9a-f]{64}',item['sha256']), 'Missing final SHA')
    path=Path(item['path']).resolve();require(path.is_file() and sha(path)==item['sha256'],f'File/hash mismatch: {path}')
    return path

def ready_proof(item):
    path=checked_file(item);data=read(path)
    require(item.get('requiredFields'), 'No final ready fields specified')
    for field,expected in item['requiredFields'].items():
        value=data
        for part in field.split('.'): value=value[part]
        require(value==expected and expected is not None,f'Ready mismatch: {field}')
    return path

def verify_frozen(snapshot):
    doc=read(snapshot/'frozen.json')
    for path,value in doc['files'].items():
        target=(snapshot/path).resolve();require(target.is_relative_to(snapshot.resolve()),'Snapshot path escaped')
        require(sha(target)==value,f'Frozen file changed: {path}')
    require(doc['files']['code/evaluate.py']==sha(__file__),'Run the exact frozen evaluation code')
    return doc

def freeze(request_path, snapshot):
    request=read(request_path)
    require(request.get('parentReceivedFinalReadyHandoff') is True,'Final DATA02/model ready handoff has not been received')
    require(request.get('trial')=='pilot02' and request.get('scope') in SCOPES,'Invalid trial/scope')
    scope=request['scope'];split=SCOPES[scope]
    proofs={name:ready_proof(request[name]) for name in ('dataReady','modelReady','exportReady')}
    manifest_path=checked_file(request['datasetManifest']);manifest=read(manifest_path)
    selected=[s for s in manifest['samples'] if s['split']==split and s.get('selected',True)]
    samples=request['samples']
    require(len(samples)==8 and len(selected)==8 and len({s['sampleId'] for s in samples})==8,'Expected fixed8 samples')
    require({s['sampleId'] for s in samples}=={s['sampleId'] for s in selected},'Do not select subsets by outcomes')
    lookup={s['sampleId']:s for s in selected}
    models={name:checked_file(request[name]) for name in ('baseline','candidate')}
    require(request['baseline']['sha256']==BASE_SHA and request['candidate']['sha256']!=BASE_SHA,'Wrong baseline/candidate')
    export=read(proofs['exportReady']);ready=read(proofs['modelReady'])
    require(export['trainedCheckpointSha256']==ready['checkpointSha256'],'Export checkpoint mismatch')
    require(any(a['sha256']==request['candidate']['sha256'] and Path(a['path']).resolve()==models['candidate'] for a in export['artifacts']),'Candidate not in final export manifest')
    if scope=='pilot01_fixed_regression':
        old=ROOT.parent/'C-finetune-data-20261024'
        old_ready=read(old/'dataset-ready.json')
        require(manifest_path==(old/'split-manifest.json').resolve(),'Regression must use original fixed manifest')
        require(sha(manifest_path)==old_ready['fileHashes']['split-manifest.json'],'Old regression manifest changed')
    inputs=[]
    for s in samples:
        require(re.fullmatch('[A-Za-z0-9_-]+',s['sampleId']) is not None and s['split']==split,'Unsafe ID or wrong split')
        original=lookup[s['sampleId']]
        for kind in ('image','mask'):
            path=checked_file({'path':s[kind],'sha256':s[kind+'Sha256']})
            require(path==(manifest_path.parent/original[kind]).resolve(),'Input not bound to final manifest')
            expected=original.get(kind+'Sha256')
            if expected is None and scope=='pilot01_fixed_regression':expected=old_ready['fileHashes'][original[kind]]
            require(expected==s[kind+'Sha256'],'Input hash differs from final manifest')
            inputs.append((path,s[kind+'Sha256']))
        rgb=Image.open(s['image']);mask=np.asarray(Image.open(s['mask']))
        require(mask.ndim==2 and mask.dtype==np.uint8 and rgb.size==(mask.shape[1],mask.shape[0]),'RGB/mask shape mismatch')
        require(np.all((mask<150)|(mask==255)) and np.any(mask!=255),'Invalid/empty partial annotation')
    snapshot.mkdir(parents=True,exist_ok=False)
    def copy(source,relative):
        target=snapshot/relative;target.parent.mkdir(parents=True,exist_ok=True);shutil.copyfile(source,target)
    copy(request_path,'request.json');copy(__file__,'code/evaluate.py');copy(ROOT/'provenance/evaluate_heldout.py','code/provenance/evaluate_heldout.py')
    for name,path in proofs.items():copy(path,'proofs/'+name+'.json')
    copy(manifest_path,'proofs/dataset-manifest.json')
    for name,path in models.items():copy(path,f'models/{name}.onnx')
    frozen_samples=[]
    for s in samples:
        frozen=dict(s)
        for kind in ('image','mask'):
            relative=f'inputs/{kind}/{s["sampleId"]}.png';copy(s[kind],relative);frozen[kind]=relative
        frozen_samples.append(frozen)
    for path,h in inputs:require(sha(path)==h,'Source changed during freeze')
    for name,path in models.items():require(sha(path)==request[name]['sha256'],'Model changed during freeze')
    for name,path in proofs.items():require(sha(path)==request[name]['sha256'],'Ready proof changed during freeze')
    files={str(p.relative_to(snapshot)).replace('\\','/'):sha(p) for p in snapshot.rglob('*') if p.is_file()}
    fingerprint=hashlib.sha256(json.dumps(files,sort_keys=True).encode()).hexdigest()
    save(snapshot/'frozen.json',{'status':'frozen','trial':'pilot02','scope':scope,'split':split,'fingerprint':fingerprint,
        'legacyEvaluatorSha256':OLD_SHA,'files':files,'samples':frozen_samples,'limitations':LIMIT})
    print(f'FROZEN {scope} samples=8 fingerprint={fingerprint}')

def claim_once(scope,snapshot,fingerprint,claim_root=None):
    claims=claim_root or ROOT/'claims';claims.mkdir(parents=True,exist_ok=True)
    path=claims/f'pilot02-{scope}.json'
    with path.open('x',encoding='utf-8') as f:
        json.dump({'scope':scope,'snapshot':str(snapshot),'fingerprint':fingerprint,'state':'claimed_once'},f)
    return path

def pct(value):return 'N/A' if value is None else f'{value*100:.4f}%'

def run(snapshot):
    import cv2,onnxruntime as ort
    doc=verify_frozen(snapshot)
    claim_once(doc['scope'],snapshot,doc['fingerprint'])
    out=snapshot/'results';out.mkdir(exist_ok=False)
    save(out/'started.json',{'scope':doc['scope'],'fingerprint':doc['fingerprint'],'python':sys.version,
        'opencv':cv2.__version__,'onnxruntime':ort.__version__,'numpy':np.__version__,'legacySha256':OLD_SHA})
    os.environ['ORT_DISABLE_TELEMETRY']='1'
    options=ort.SessionOptions();options.intra_op_num_threads=2;options.inter_op_num_threads=1
    sessions={k:ort.InferenceSession(str(snapshot/f'models/{k}.onnx'),sess_options=options,providers=['CPUExecutionProvider']) for k in ('baseline','candidate')}
    for session in sessions.values():
        require(session.get_inputs()[0].shape==[1,3,320,320] and session.get_inputs()[0].type=='tensor(float)','Input contract mismatch')
        require(session.get_outputs()[0].shape==[1,320,320] and session.get_outputs()[0].type=='tensor(uint8)','Output contract mismatch')
    total={k:np.zeros((150,150),np.int64) for k in sessions};groups={};records=[]
    for s in doc['samples']:
        env=preprocess(snapshot,s);rgb=env['rgb'];gt=env['gt'];predictions={};results={}
        group=groups.setdefault(s['sourceGroup'],{k:np.zeros((150,150),np.int64) for k in sessions})
        for name,session in sessions.items():
            start=time.perf_counter();raw=session.run(None,{session.get_inputs()[0].name:env['tensor']})[0]
            duration=time.perf_counter()-start;pred=aligned(raw,env);cm=confusion(gt,pred)
            total[name]+=cm;group[name]+=cm;predictions[name]=pred
            dest=out/'predictions'/name;dest.mkdir(parents=True,exist_ok=True)
            Image.fromarray(raw[0]).save(dest/f'{s["sampleId"]}-320.png')
            Image.fromarray(pred).save(dest/f'{s["sampleId"]}-aligned.png')
            results[name]={'metrics':metrics(cm),'risks':LEGACY.risks(cm),'inferenceMs':duration*1000,
                'alignedSha256':sha(dest/f'{s["sampleId"]}-aligned.png')}
        panels=[Image.fromarray(rgb)]
        for name in sessions:
            overlay=rgb.copy();keep=gt!=255;correct=keep&(predictions[name]==gt);wrong=keep&~correct
            overlay[correct]=(rgb[correct]*.5+np.array([30,220,70])*.5).astype(np.uint8)
            overlay[wrong]=(rgb[wrong]*.5+np.array([250,40,40])*.5).astype(np.uint8)
            panels.append(Image.fromarray(overlay))
        canvas=Image.new('RGB',(1920,400),'#151515');draw=ImageDraw.Draw(canvas)
        for i,(panel,label) in enumerate(zip(panels,['RGB / ignored255 unscored','BASELINE: annotated pixels only','PILOT02: same fixed partial labels'])):
            thumb=ImageOps.contain(panel,(640,360));canvas.paste(thumb,(i*640+(640-thumb.width)//2,35))
            draw.text((i*640+8,8),label,fill='white')
        canvas.save(out/f'{s["sampleId"]}-comparison.jpg',quality=95)
        records.append({'sampleId':s['sampleId'],'sourceGroup':s['sourceGroup'],'annotatedPixels':int((gt!=255).sum()),
            'ignoredPixels':int((gt==255).sum()),'inputTensorSha256':hashlib.sha256(env['tensor'].tobytes()).hexdigest(),
            'geometry':{k:env[k] for k in ('w','h','rw','rh','left','top')},'results':results})
    overall={name:metrics(cm) for name,cm in total.items()}
    comparison=[]
    for c in range(150):
        b=overall['baseline']['classes'][str(c)];a=overall['candidate']['classes'][str(c)]
        comparison.append({'classId':c,'className':b['name'],'gtPixels':b['gtPixels'],'baselineIoU':b['iou'],'candidateIoU':a['iou'],
            'deltaPercentagePoints':None if b['iou'] is None else (a['iou']-b['iou'])*100,
            'baselinePredictedOnLabels':b['predictedOnLabeledPixels'],'candidatePredictedOnLabels':a['predictedOnLabeledPixels']})
    with (out/'class-comparison.csv').open('w',newline='',encoding='utf-8-sig') as f:
        writer=csv.DictWriter(f,fieldnames=list(comparison[0]));writer.writeheader()
        writer.writerows({k:('N/A' if v is None else v) for k,v in row.items()} for row in comparison)
    verify_frozen(snapshot)
    report={'status':'completed','scope':doc['scope'],'independentTest':False,'limitations':LIMIT,'datasetFingerprint':doc['fingerprint'],
        'models':{name:doc['files'][f'models/{name}.onnx'] for name in sessions},'overall':overall,'classComparison':comparison,
        'sourceGroups':{g:{k:metrics(cm) for k,cm in values.items()} for g,values in groups.items()},'samples':records,
        'confusionMatrices':{k:cm.tolist() for k,cm in total.items()},'inputsUnchanged':True,'usedForTrainingOrCandidateSelection':False,
        'validationPreviouslyUsedByTrainer':doc['scope']=='data02_development_val','androidInferenceTest':False}
    save(out/'evaluation.json',report)
    lines=[f'# {doc["scope"]}: fixed partial-label comparison','',LIMIT,'',
        '|Metric|Official base|Pilot02|','|---|---:|---:|']
    for key in ('macroIoUSupportedGT','frequencyWeightedPixelAccuracy','supportWeightedIoU'):
        lines.append(f'|{key}|{pct(overall["baseline"][key])}|{pct(overall["candidate"][key])}|')
    lines+=['','|Class|Labeled pixels|Base IoU|Pilot02 IoU|Change pp|','|---|---:|---:|---:|---:|']
    for row in comparison:
        if row['gtPixels'] or row['classId'] in FOCUS:
            delta='N/A' if row['deltaPercentagePoints'] is None else f'{row["deltaPercentagePoints"]:+.4f}'
            lines.append(f'|{row["classId"]} {row["className"]}|{row["gtPixels"]}|{pct(row["baselineIoU"])}|{pct(row["candidateIoU"])}|{delta}|')
    lines+=['','Every fixed sample has a comparison image; no favorable-case selection.','No automatic model adoption. Parent decides after regressions and class-specific review.']
    (out/'SUMMARY.md').write_text('\n'.join(lines)+'\n',encoding='utf-8')
    save(out/'completion.json',{'status':'completed','evaluationSha256':sha(out/'evaluation.json'),
        'files':{str(p.relative_to(out)):sha(p) for p in out.rglob('*') if p.is_file()}})
    print(json.dumps({'scope':doc['scope'],'overall':{k:{m:v[m] for m in ('annotatedPixels','macroIoUSupportedGT','pixelAccuracy')} for k,v in overall.items()}},ensure_ascii=False))

if __name__=='__main__':
    parser=argparse.ArgumentParser();sub=parser.add_subparsers(dest='action',required=True)
    frozen=sub.add_parser('freeze');frozen.add_argument('--request',required=True,type=Path);frozen.add_argument('--snapshot',required=True,type=Path)
    execute=sub.add_parser('run');execute.add_argument('--snapshot',required=True,type=Path)
    args=parser.parse_args()
    if args.action=='freeze':freeze(args.request,args.snapshot)
    else:run(args.snapshot)
