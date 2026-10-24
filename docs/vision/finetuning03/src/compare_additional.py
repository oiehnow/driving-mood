"""Additional frozen candidates, original annotation grid; no training/holdout/fusion."""
from pathlib import Path
import ast, datetime, hashlib, json, time
import cv2, numpy as np, onnxruntime as ort
from PIL import Image
import compare as c
from decide import screen

ROOT=c.ROOT
MODELS=('n320','n640','s','C')

def prep(rgb,size):
    h,w=rgb.shape[:2];scale=min(size/h,size/w)
    rw,rh=round(w*scale),round(h*scale)
    left=round((size-rw)/2-.1);top=round((size-rh)/2-.1)
    canvas=np.full((size,size,3),114,np.uint8)
    canvas[top:top+rh,left:left+rw]=cv2.resize(rgb,(rw,rh),interpolation=cv2.INTER_LINEAR)
    return np.ascontiguousarray(canvas.transpose(2,0,1)[None].astype(np.float32)/255),(top,left,rh,rw,h,w)

def align(raw,box,size):
    c.require(raw.shape==(1,size,size) and raw.dtype==np.uint8 and np.all(raw<150),'Wrong ADE150 output')
    top,left,rh,rw,h,w=box
    return cv2.resize(raw[0,top:top+rh,left:left+rw],(w,h),interpolation=cv2.INTER_NEAREST_EXACT)

def freeze():
    target=ROOT/'additional-request.json'
    c.require(not target.exists(),'Do not overwrite additional freeze')
    meta=c.read(ROOT/'additional-model-metadata.json')
    base_names=ast.literal_eval(meta['n320']['metadata']['names'])
    for name,m in meta.items():
        c.checked(m)
        c.require(ast.literal_eval(m['metadata']['names'])==base_names,'Class IDs differ')
        c.require(m['inputs'][0]['shape'] in ([1,3,320,320],[1,3,640,640]),'Unapproved size')
    export=ROOT.parent/'B-finetune03-20261024/pilot03c-l2sp/exports/export-manifest.json'
    ed=c.read(export)
    c.require(any(a['sha256']==meta['C']['sha256'] for a in ed['artifacts']),'C not selected checkpoint export')
    scope={name:{'frozenSha256':c.sha(path/'frozen.json'),'fingerprint':c.verify_scope(path)[0]['fingerprint']}
           for name,path in c.SCOPES.items()}
    c.save(target,{'frozenAtUtc':datetime.datetime.now(datetime.timezone.utc).isoformat(),'models':meta,'scopes':scope,
       'codeSha256':c.sha(__file__),'protocolSha256':c.sha(ROOT/'protocol.json'),
       'CExportManifestSha256':c.sha(export),'CSelectedCheckpointSha256':ed['trainedCheckpointSha256'],
       'fixedRoles':{'personVehicle':[12,20,80,83],'sky':[2],'lightContext':[8,43]},
       'roleMeaning':'Class-specific reporting only. No fused output, no GT oracle, no luminous-object accuracy claim.',
       'costProtocol':'Windows CPU ORT 2 intra/1 inter threads; each model separately; 3 warmup first tensor, 3 timed runs per frame; inference only. Not phone latency.',
       'scopeMeaning':'Same two development scopes. User-video regression primary presentation; mixed development and source-only subgroups secondary. No selection-rule change.',
       'selectionMeaning':'Original C03 policy applied separately to C/n640/s as development screen. No automatic selection among different role specialists.'})
    print('Frozen',c.sha(target),flush=True)

def run():
    request=c.read(ROOT/'additional-request.json');out=ROOT/'results-additional-v1'
    c.require(not out.exists(),'Never overwrite result')
    c.require(c.sha(__file__)==request['codeSha256'],'Code changed after freeze')
    c.require(c.sha(ROOT/'protocol.json')==request['protocolSha256'],'Policy changed')
    for scope,path in c.SCOPES.items():
        c.require(c.sha(path/'frozen.json')==request['scopes'][scope]['frozenSha256'],'Dataset changed')
        c.verify_scope(path)
    out.mkdir();c.save(out/'request.snapshot.json',request)
    report={'independentTest':False,'scopeRole':'development_selection','fusionPerformed':False,'scopes':{},'cost':{},'models':request['models'],
        'limitations':'AI-assisted partial annotations, not independent human ground truth. Ignore255 pixels not evaluated. Window/sign classes describe surface context, not light source or shop-open status.'}
    docs={scope:c.read(path/'frozen.json') for scope,path in c.SCOPES.items()}
    totals={s:{n:np.zeros((150,150),np.int64) for n in MODELS} for s in docs}
    subgroups={s:{} for s in docs};records={s:[] for s in docs}
    for scope,doc in docs.items():
        records[scope]=[{**sample,'results':{}} for sample in doc['samples']]
    for name in MODELS:
        m=request['models'][name];path=c.checked(m);size=m['inputs'][0]['shape'][-1]
        options=ort.SessionOptions();options.intra_op_num_threads=2;options.inter_op_num_threads=1
        begin=time.perf_counter();session=ort.InferenceSession(str(path),sess_options=options,providers=['CPUExecutionProvider'])
        init_ms=(time.perf_counter()-begin)*1000;durations=[];warmed=False
        for scope,snapshot in c.SCOPES.items():
            for record in records[scope]:
                env=c.e.preprocess(snapshot,record);rgb=env['rgb'];gt=env['gt']
                tensor,box=prep(rgb,size)
                if size==320:c.require(np.array_equal(tensor,env['tensor']),'320 preprocessing drift')
                if not warmed:
                    for _ in range(3):session.run(None,{session.get_inputs()[0].name:tensor})
                    warmed=True
                sample_ms=[];reference=None
                for _ in range(3):
                    begin=time.perf_counter();raw=session.run(None,{session.get_inputs()[0].name:tensor})[0]
                    sample_ms.append((time.perf_counter()-begin)*1000)
                    if reference is not None:c.require(np.array_equal(raw,reference),'Nondeterministic CPU output')
                    reference=raw.copy()
                durations+=sample_ms;pred=align(raw,box,size)
                if size==320:c.require(np.array_equal(pred,c.e.aligned(raw,env)),'320 alignment drift')
                cm=c.e.confusion(gt,pred);totals[scope][name]+=cm
                group=record['sourceGroup'];g=subgroups[scope].setdefault(group,{n:np.zeros((150,150),np.int64) for n in MODELS});g[name]+=cm
                dest=out/scope/name;dest.mkdir(parents=True,exist_ok=True)
                rawp=dest/(record['sampleId']+f'-{size}.png');alignedp=dest/(record['sampleId']+'-aligned.png')
                Image.fromarray(raw[0]).save(rawp);Image.fromarray(pred).save(alignedp)
                if name=='n320':
                    old=np.asarray(Image.open(snapshot/'results/predictions/baseline'/(record['sampleId']+'-aligned.png')))
                    c.require(np.array_equal(old,pred),'Baseline changed from old prediction')
                record['results'][name]={'metrics':c.extended_metrics(cm),'inputTensorSha256':hashlib.sha256(tensor.tobytes()).hexdigest(),
                    'rawSha256':c.sha(rawp),'alignedSha256':c.sha(alignedp),'inferenceMs':sample_ms,'size':size}
        report['cost'][name]={'inferenceMedianMs':float(np.median(durations)),'inferenceP95Ms':float(np.percentile(durations,95)),
           'sessionInitMs':init_ms,'onnxBytes':path.stat().st_size,'runs':len(durations),'device':'Windows CPU; not Android','intraThreads':2,'interThreads':1}
        del session
        print(name,'finished',report['cost'][name]['inferenceMedianMs'],flush=True)
    old=c.read(ROOT/'results-v1/comparison.json')
    for scope in docs:
        c.require(totals[scope]['n320'].tolist()==old['scopes'][scope]['confusionMatrices']['baseline'],'Baseline total drift')
        overall={n:c.extended_metrics(cm) for n,cm in totals[scope].items()}
        overall['baseline']=overall['n320']
        report['scopes'][scope]={'overall':overall,'samples':records[scope],
            'sourceGroups':{g:{n:c.extended_metrics(cm) for n,cm in models.items()} for g,models in subgroups[scope].items()},
            'confusionMatrices':{n:cm.tolist() for n,cm in totals[scope].items()}}
    c.save(out/'comparison.json',report)
    policy=c.read(ROOT/'protocol.json')
    c.save(out/'screening.json',{name:screen(report,policy,name) for name in ('C','n640','s')})
    for m in request['models'].values():c.checked(m)
    c.save(out/'completion.json',{'status':'completed','files':{str(p.relative_to(out)):c.sha(p) for p in out.rglob('*') if p.is_file()}})
    print(json.dumps({s:{n:v['overall'][n]['macroIoUSupportedGT'] for n in MODELS} for s,v in report['scopes'].items()}))

if __name__=='__main__':
    import sys
    if sys.argv[1:] == ['freeze']:freeze()
    elif sys.argv[1:] == ['run']:run()
    else:raise ValueError('Choose freeze then run')
