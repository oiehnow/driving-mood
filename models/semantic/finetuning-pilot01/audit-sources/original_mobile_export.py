"""Export a frozen trained checkpoint to separate ONNX/ARM ORT artifacts; no app edits."""
import ast,copy,hashlib,json,os,shutil,subprocess,sys
from pathlib import Path
import configure_offline
os.environ['ORT_DISABLE_TELEMETRY']='1'
import cv2,numpy as np,onnx,onnxruntime as ort,torch
from ultralytics import YOLO
ROOT=Path(__file__).resolve().parent;OUT=ROOT/'exports';OUT.mkdir(exist_ok=False)
def sha(path):return hashlib.sha256(Path(path).read_bytes()).hexdigest()
def save(path,obj):Path(path).write_text(json.dumps(obj,ensure_ascii=False,indent=2),encoding='utf-8')
trained=json.loads((ROOT/'training-result.json').read_text());assert trained['status']=='completed'
best=Path(trained['weights']['best']['path']);assert sha(best)==trained['weights']['best']['sha256']
target=OUT/'yolo26n-sem-driving-mood-pilot01.pt';shutil.copy2(best,target)
torch.set_num_threads(4)
model=YOLO(str(target),task='semantic')
original_names=model.names.copy();assert len(original_names)==150
exported=Path(model.export(format='onnx',imgsz=320,batch=1,opset=17,dynamic=False,simplify=False,device='cpu'))
original=onnx.load(exported);sanitized=copy.deepcopy(original)
metadata_before={e.key:e.value for e in original.metadata_props}
for e in sanitized.metadata_props:
    if e.key=='description':e.value='Ultralytics YOLO26n semantic model; Driving Mood partial-label pilot 01; 150 ADE20K classes'
onnx.checker.check_model(sanitized)
raw=OUT/'raw-export.onnx';shutil.copy2(exported,raw)
onnx.save(sanitized,exported)
assert ast.literal_eval(next(e.value for e in sanitized.metadata_props if e.key=='names'))==original_names
def tensors(m):return {x.name:hashlib.sha256(onnx.numpy_helper.to_array(x).tobytes()).hexdigest() for x in m.graph.initializer}
assert tensors(original)==tensors(sanitized)
with (OUT/'ort-conversion.log').open('w',encoding='utf-8') as log:
    subprocess.run([sys.executable,'-m','onnxruntime.tools.convert_onnx_models_to_ort',str(exported),
        '--output_dir',str(OUT/'ort-320'),'--optimization_style','Fixed','--target_platform','arm',
        '--enable_type_reduction','--save_optimized_onnx_model'],stdout=log,stderr=subprocess.STDOUT,check=True)
ort_path=OUT/'ort-320'/exported.with_suffix('.ort').name
options=ort.SessionOptions();options.intra_op_num_threads=2
sessions={kind:ort.InferenceSession(str(path),sess_options=options,providers=['CPUExecutionProvider']) for kind,path in [('onnx',exported),('ort',ort_path)]}
for session in sessions.values():
    assert session.get_inputs()[0].shape==[1,3,320,320]
    assert session.get_outputs()[0].shape==[1,320,320] and session.get_outputs()[0].type=='tensor(uint8)'
model=YOLO(str(best),task='semantic').model.float().cpu().eval()
freeze=json.loads((ROOT/'data-freeze.json').read_text());comparisons=[]
with torch.inference_mode():
    for sample in freeze['samples']:
        rgb=cv2.cvtColor(cv2.imread(sample['image']),cv2.COLOR_BGR2RGB)
        tensor=np.ascontiguousarray(rgb.transpose(2,0,1)[None].astype(np.float32)/255)
        logits=model(torch.from_numpy(tensor));logits=logits[0] if isinstance(logits,tuple) else logits
        pt=torch.nn.functional.interpolate(logits,size=(320,320),mode='bilinear',align_corners=False).argmax(1).numpy().astype(np.uint8)
        outputs={kind:session.run(None,{session.get_inputs()[0].name:tensor})[0] for kind,session in sessions.items()}
        record=dict(sampleId=sample['sampleId'],split=sample['split'],pixels=pt.size,
            pytorchVsOnnxDifferentPixels=int((pt!=outputs['onnx']).sum()),onnxVsOrtDifferentPixels=int((outputs['onnx']!=outputs['ort']).sum()),
            onnxPredictionSha256=hashlib.sha256(outputs['onnx'].tobytes()).hexdigest(),ortPredictionSha256=hashlib.sha256(outputs['ort'].tobytes()).hexdigest())
        comparisons.append(record)
        assert outputs['ort'].dtype==np.uint8 and outputs['ort'].max()<150
        for kind,value in outputs.items():
            dest=OUT/'parity-masks'/kind;dest.mkdir(parents=True,exist_ok=True);cv2.imwrite(str(dest/f"{sample['sampleId']}.png"),value[0])
shutil.copytree(ROOT.parent/'B-mobile-semantic-runtime-2026-10-24/licenses',OUT/'licenses')
report=dict(status='exported',trainingResultSha256=sha(ROOT/'training-result.json'),trainedCheckpointSha256=trained['weights']['best']['sha256'],
    originalPretrainedWeightSha256='68ac71bef2868c987fff7cd7c49cb656922f6d8447ad21af3893305577435000',
    outputContract=dict(inputShape=[1,3,320,320],inputType='float32 RGB/255 NCHW centered114padding',outputShape=[1,320,320],outputType='uint8',classes=150),
    artifacts=[dict(path=str(p),bytes=p.stat().st_size,sha256=sha(p)) for p in [best,target,raw,exported,ort_path]],
    conversion=dict(runtime=ort.__version__,target='arm',optimization='Fixed',quantized=False),
    metadata=dict(descriptionChangedOnly=True,tensorsUnchanged=True,originalDescriptionSha256=hashlib.sha256(metadata_before['description'].encode()).hexdigest(),
        licensePreserved=metadata_before.get('license')==next((e.value for e in sanitized.metadata_props if e.key=='license'),None)),
    validation= dict(actualTrainValFrames=len(comparisons),testFramesUsed=0,comparisons=comparisons,
        totalPyTorchOnnxDifferentPixels=sum(r['pytorchVsOnnxDifferentPixels'] for r in comparisons),
        totalOnnxOrtDifferentPixels=sum(r['onnxVsOrtDifferentPixels'] for r in comparisons)),
    appDefaultChanged=False,androidExecutionVerified=False,accuracyImprovementClaimed=False)
save(OUT/'export-manifest.json',report)
assert sha(best)==trained['weights']['best']['sha256']
print(json.dumps({k:v for k,v in report.items() if k not in ['validation','metadata']},ensure_ascii=False,indent=2))
