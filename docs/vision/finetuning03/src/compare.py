"""Pilot03 development-only comparison; no training or independent-test entry point."""
from pathlib import Path
import argparse, csv, hashlib, importlib.util, json, os, sys, time
import numpy as np
from PIL import Image, ImageDraw, ImageOps

ROOT = Path(__file__).resolve().parent
OLD = ROOT.parent / 'C-finetune02-evaluation-20261024'
SCOPES = {'data02_development_val': OLD/'snapshot-data02-val',
          'pilot01_development_regression': OLD/'snapshot-pilot01-regression'}
EXPECTED_CODE = 'f7fe2e7e7a675a21a5b15cd1e713d9a70443b032e62f2d7be385b825ade1ac48'
NAMES = ('baseline', 'pilot02', 'A', 'B')

def sha(path): return hashlib.sha256(Path(path).read_bytes()).hexdigest()
def read(path): return json.loads(Path(path).read_text(encoding='utf-8-sig'))
def save(path, value): Path(path).write_text(json.dumps(value, ensure_ascii=False, indent=2, allow_nan=False), encoding='utf-8')
def require(value, message):
    if not value: raise ValueError(message)

source = ROOT/'provenance/c02/evaluate.py'
require(sha(source) == EXPECTED_CODE, 'C02 evaluator copy changed')
spec = importlib.util.spec_from_file_location('frozen_c02', source)
e = importlib.util.module_from_spec(spec)
spec.loader.exec_module(e)

def checked(item):
    path = Path(item['path']).resolve()
    require(path.is_file() and sha(path) == item['sha256'], 'Artifact SHA mismatch: '+str(path))
    return path

def verify_scope(snapshot):
    doc = read(snapshot/'frozen.json')
    for relative, expected in doc['files'].items():
        path = (snapshot/relative).resolve()
        require(path.is_relative_to(snapshot.resolve()) and sha(path) == expected, 'C02 frozen file changed: '+relative)
    completion = read(snapshot/'results/completion.json')
    require(sha(snapshot/'results/evaluation.json') == completion['evaluationSha256'], 'C02 evaluation changed')
    return doc, read(snapshot/'results/evaluation.json'), completion

def extended_metrics(cm):
    metrics = e.metrics(cm)
    support, predicted, tp = cm.sum(1), cm.sum(0), np.diag(cm)
    for c in range(150):
        row = metrics['classes'][str(c)]
        row['falsePositivePixels'] = int(predicted[c]-tp[c])
        row['falseNegativePixels'] = int(support[c]-tp[c])
    vehicles = [20,80,83]
    group = metrics['vehicleGroup20_80_83']
    group['recall'] = group['truePositivePixels']/group['gtPixels'] if group['gtPixels'] else None
    group['precision'] = group['truePositivePixels']/group['predictedOnLabeledPixels'] if group['predictedOnLabeledPixels'] else None
    group['falsePositivePixels'] = group['predictedOnLabeledPixels']-group['truePositivePixels']
    group['falseNegativePixels'] = group['gtPixels']-group['truePositivePixels']
    group['eligibleSurfaceConfusionRate'] = group['predictedAsBuildingWindowSign']/group['gtPixels'] if group['gtPixels'] else None
    denominator = int(cm.sum()-support[43])
    metrics['signFalsePositiveOnNonSignLabeledRate'] = int(predicted[43]-tp[43])/denominator if denominator else None
    metrics['semanticRisksOnLabeledPixelsOnly'] = e.LEGACY.risks(cm)
    return metrics

def load_cached_prediction(snapshot, name, sample, env, old_report, completion):
    old_name = 'baseline' if name == 'baseline' else 'candidate'
    folder = snapshot/'results/predictions'/old_name
    raw_path = folder/f'{sample["sampleId"]}-320.png'
    aligned_path = folder/f'{sample["sampleId"]}-aligned.png'
    for path in (raw_path, aligned_path):
        relative = path.relative_to(snapshot/'results').as_posix()
        # Historical completion uses Windows separators on Windows.
        expected = completion['files'].get(relative, completion['files'].get(relative.replace('/', '\\')))
        require(expected is not None and sha(path) == expected, 'Cached prediction changed')
    previous = next(row for row in old_report['samples'] if row['sampleId'] == sample['sampleId'])
    require(hashlib.sha256(env['tensor'].tobytes()).hexdigest() == previous['inputTensorSha256'], 'Input tensor changed')
    raw = np.asarray(Image.open(raw_path))
    pred = e.aligned(raw[None], env)
    require(np.array_equal(pred, np.asarray(Image.open(aligned_path))), 'Old aligned output differs from exact C02 alignment')
    return raw, pred, {'inferenceReused': True, 'rawSha256': sha(raw_path), 'alignedSha256': sha(aligned_path)}

def run(request_path, output):
    import cv2, onnxruntime as ort
    freeze = read(ROOT/'protocol-freeze.json')
    require(sha(ROOT/'protocol.json')==freeze['protocolSha256'] and
            sha(ROOT/'EVALUATION-CONTRACT.md')==freeze['contractSha256'], 'Frozen selection policy changed')
    request = read(request_path)
    require(request['status'] == 'FROZEN_FOR_DEVELOPMENT_EVALUATION', 'Both candidates must be frozen first')
    require(set(request['candidates']) == {'A','B'}, 'Only frozen A/B comparison supported')
    require(output.resolve().is_relative_to(ROOT.resolve()) and not output.exists(), 'Use a new C03 output directory')
    artifacts = {}
    for name, entry in request['candidates'].items():
        require(entry['selectionScope'] == 'data02_development_val', 'Checkpoint selected outside frozen dev')
        require(isinstance(entry['bestEpoch'], int) and 1 <= entry['bestEpoch'] <= 30, 'Invalid selected epoch')
        artifacts[name] = {'onnx': checked(entry['onnx']), 'checkpoint': checked(entry['checkpoint'])}
        export=read(checked(entry['exportManifest']))
        require(export['trainedCheckpointSha256']==entry['checkpoint']['sha256'], 'Export not linked to selected checkpoint')
        require(any(item['sha256']==entry['onnx']['sha256'] and Path(item['path']).resolve()==artifacts[name]['onnx']
                    for item in export['artifacts']), 'ONNX not in selected checkpoint export manifest')
    trainer_contract = checked(request['trainerContract'])
    scope_data = {name: verify_scope(snapshot) for name,snapshot in SCOPES.items()}
    output.mkdir(parents=True)
    save(output/'request.snapshot.json', request)
    save(output/'started.json', {'independentTest': False, 'usedForCandidateSelection': True,
        'evaluatorSha256': sha(__file__), 'c02EvaluatorSha256': sha(source),
        'contractSha256': sha(ROOT/'EVALUATION-CONTRACT.md'), 'trainerContractSha256': sha(trainer_contract),
        'python':sys.version, 'numpy':np.__version__, 'opencv':cv2.__version__, 'onnxruntime':ort.__version__})
    os.environ['ORT_DISABLE_TELEMETRY'] = '1'
    options = ort.SessionOptions(); options.intra_op_num_threads=2; options.inter_op_num_threads=1
    sessions = {name:ort.InferenceSession(str(item['onnx']), sess_options=options, providers=['CPUExecutionProvider']) for name,item in artifacts.items()}
    for session in sessions.values():
        require(session.get_inputs()[0].shape==[1,3,320,320] and session.get_inputs()[0].type=='tensor(float)', 'Input contract mismatch')
        require(session.get_outputs()[0].shape==[1,320,320] and session.get_outputs()[0].type=='tensor(uint8)', 'Output contract mismatch')
    report = {'independentTest':False, 'usedForCandidateSelection':True,
        'limitations':'Previously exposed development data; fixed AI-assisted partial labels; ignore255 outside errors and full boundaries unmeasured. No emotion or real light-leakage accuracy claim.',
        'candidates':request['candidates'], 'scopes':{}}
    for scope,snapshot in SCOPES.items():
        doc, old_report, completion = scope_data[scope]
        dest = output/scope; dest.mkdir()
        totals = {name:np.zeros((150,150),np.int64) for name in NAMES}
        groups = {}; records=[]; support_groups={str(c):set() for c in range(150)}
        for sample in doc['samples']:
            env = e.preprocess(snapshot, sample); gt=env['gt']; rgb=env['rgb']
            group = groups.setdefault(sample['sourceGroup'], {name:np.zeros((150,150),np.int64) for name in NAMES})
            for c in np.unique(gt):
                if c != 255: support_groups[str(c)].add(sample['sourceGroup'])
            result={}; predictions={}
            for name in NAMES:
                if name in ('baseline','pilot02'):
                    raw,pred,provenance=load_cached_prediction(snapshot,name,sample,env,old_report,completion)
                else:
                    session=sessions[name]; start=time.perf_counter()
                    raw_output=session.run(None,{session.get_inputs()[0].name:env['tensor']})[0]
                    duration=time.perf_counter()-start; pred=e.aligned(raw_output,env); raw=raw_output[0]
                    folder=dest/'predictions'/name;folder.mkdir(parents=True,exist_ok=True)
                    Image.fromarray(raw).save(folder/f'{sample["sampleId"]}-320.png')
                    Image.fromarray(pred).save(folder/f'{sample["sampleId"]}-aligned.png')
                    provenance={'inferenceReused':False,'cpuInferenceMs':duration*1000,
                        'rawSha256':sha(folder/f'{sample["sampleId"]}-320.png'),
                        'alignedSha256':sha(folder/f'{sample["sampleId"]}-aligned.png')}
                cm=e.confusion(gt,pred);totals[name]+=cm;group[name]+=cm;predictions[name]=pred
                result[name]={'metrics':extended_metrics(cm),**provenance}
            records.append({'sampleId':sample['sampleId'],'sourceGroup':sample['sourceGroup'],
                'imageSha256':sample['imageSha256'],'maskSha256':sample['maskSha256'],
                'annotatedPixels':int((gt!=255).sum()),'ignoredPixels':int((gt==255).sum()),
                'inputTensorSha256':hashlib.sha256(env['tensor'].tobytes()).hexdigest(),'results':result})
            canvas=Image.new('RGB',(1600,215),'#151515');draw=ImageDraw.Draw(canvas)
            panels=[Image.fromarray(rgb)]
            for name in NAMES:
                overlay=rgb.copy();keep=gt!=255;correct=keep&(predictions[name]==gt);wrong=keep&~correct
                overlay[correct]=(rgb[correct]*.5+np.array([30,220,70])*.5).astype(np.uint8)
                overlay[wrong]=(rgb[wrong]*.5+np.array([250,40,40])*.5).astype(np.uint8)
                panels.append(Image.fromarray(overlay))
            for i,(panel,label) in enumerate(zip(panels,('RGB',)+NAMES)):
                thumb=ImageOps.contain(panel,(320,180));canvas.paste(thumb,(i*320+(320-thumb.width)//2,30));draw.text((i*320+8,8),label,fill='white')
            canvas.save(dest/f'{sample["sampleId"]}-comparison.jpg',quality=95)
        for name,old_name in (('baseline','baseline'),('pilot02','candidate')):
            require(np.array_equal(totals[name],np.asarray(old_report['confusionMatrices'][old_name])), 'Cached model confusion changed')
        overall={name:extended_metrics(cm) for name,cm in totals.items()}
        rows=[]
        for c in range(150):
            row={'classId':c,'className':overall['baseline']['classes'][str(c)]['name'],
                'sourceGroupSupport':len(support_groups[str(c)]),'sourceGroups':','.join(sorted(support_groups[str(c)]))}
            for name in NAMES:
                row.update({name+'_'+key:value for key,value in overall[name]['classes'][str(c)].items() if key!='name'})
            rows.append(row)
        with (dest/'class-comparison.csv').open('w',newline='',encoding='utf-8-sig') as f:
            writer=csv.DictWriter(f,fieldnames=list(rows[0]));writer.writeheader()
            writer.writerows({key:'N/A' if value is None else value for key,value in row.items()} for row in rows)
        scope_report={'datasetFingerprint':doc['fingerprint'],'previousScope':doc['scope'],
            'scopeRole':'development_selection','independentTest':False,'overall':overall,
            'sourceGroups':{g:{name:extended_metrics(cm) for name,cm in data.items()} for g,data in groups.items()},
            'classSupportSourceGroups':{c:sorted(value) for c,value in support_groups.items()},
            'samples':records,'confusionMatrices':{name:cm.tolist() for name,cm in totals.items()}}
        save(dest/'evaluation.json',scope_report);report['scopes'][scope]=scope_report
    for snapshot in SCOPES.values():verify_scope(snapshot)
    for entry in request['candidates'].values():checked(entry['onnx']);checked(entry['checkpoint'])
    checked(request['trainerContract'])
    require(sha(ROOT/'protocol.json')==freeze['protocolSha256'] and
            sha(ROOT/'EVALUATION-CONTRACT.md')==freeze['contractSha256'], 'Selection policy changed during evaluation')
    save(output/'comparison.json',report)
    save(output/'completion.json',{'status':'completed','files':{str(p.relative_to(output)):sha(p) for p in output.rglob('*') if p.is_file()}})
    print(json.dumps({scope:{name:values['overall'][name]['macroIoUSupportedGT'] for name in NAMES} for scope,values in report['scopes'].items()}))

if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('--request',type=Path,required=True);parser.add_argument('--output',type=Path,required=True)
    args=parser.parse_args();run(args.request,args.output)
