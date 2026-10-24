"""Rebuild the packaged semantic model. SPDX-License-Identifier: AGPL-3.0-only"""
import argparse, hashlib, importlib.metadata, json, os, subprocess, sys
from pathlib import Path
ROOT = Path(__file__).resolve().parents[2]
SOURCE_SHA = '3af7911808fd81e95982b3541fe4c1151016fc6e596b872d0b62f3874d575dad'
ORT_SHA = '6ce74261203e17eeefda81e41dd8e7e764b0dc98368dc9662501a8de12054dfc'
OPTIMIZED_SHA = '11bb65829da40c79ad333f5843089af34309e3035b1c994144f9c170adc7e5bc'
def sha(path): return hashlib.sha256(path.read_bytes()).hexdigest()
def main():
    p=argparse.ArgumentParser();p.add_argument('--output',type=Path,default=ROOT/'build/semantic-model');a=p.parse_args()
    if importlib.metadata.version('onnxruntime')!='1.31.0': raise SystemExit('Use onnxruntime==1.31.0.')
    source=ROOT/'models/semantic/source/yolo26n-sem-ade20k-320.onnx'
    if sha(source)!=SOURCE_SHA: raise SystemExit('Source model checksum mismatch.')
    a.output.mkdir(parents=True,exist_ok=False)
    with (a.output/'conversion.log').open('w',encoding='utf-8') as log:
        subprocess.run([sys.executable,'-m','onnxruntime.tools.convert_onnx_models_to_ort',str(source),
            '--output_dir',str(a.output),'--optimization_style','Fixed','--target_platform','arm',
            '--enable_type_reduction','--save_optimized_onnx_model'],env=dict(os.environ,ORT_DISABLE_TELEMETRY='1'),
            stdout=log,stderr=subprocess.STDOUT,check=True)
    import onnx
    import numpy as np
    os.environ['ORT_DISABLE_TELEMETRY']='1'
    import onnxruntime as ort
    model=a.output/'yolo26n-sem-ade20k-320.ort';actual=sha(model)
    graph=onnx.load(a.output/'yolo26n-sem-ade20k-320.optimized.onnx')
    graph_sha=hashlib.sha256(graph.SerializeToString(deterministic=True)).hexdigest()
    if graph_sha!=OPTIMIZED_SHA:raise SystemExit('Optimized graph differs from the verified source graph.')
    reference=ROOT/'app/src/main/assets/vision/yolo26n-sem-ade20k-320.ort'
    if sha(reference)!=ORT_SHA:raise SystemExit('Packaged reference model checksum mismatch.')
    options=ort.SessionOptions();options.intra_op_num_threads=2;options.inter_op_num_threads=1
    rebuilt=ort.InferenceSession(str(model),sess_options=options,providers=['CPUExecutionProvider'])
    packaged=ort.InferenceSession(str(reference),sess_options=options,providers=['CPUExecutionProvider'])
    probes=[np.zeros((1,3,320,320),dtype=np.float32),np.full((1,3,320,320),114/255,dtype=np.float32),
            np.linspace(0,1,3*320*320,dtype=np.float32).reshape(1,3,320,320)]
    differences=[int(np.count_nonzero(rebuilt.run(None,{'images':x})[0]!=packaged.run(None,{'images':x})[0])) for x in probes]
    result=dict(sourceSha256=SOURCE_SHA,ortSha256=actual,packagedOrtSha256=ORT_SHA,bytes=model.stat().st_size,
        exactFileMatch=actual==ORT_SHA,optimizedGraphSha256=graph_sha,probeMismatchPixels=differences,passed=not any(differences))
    (a.output/'verification.json').write_text(json.dumps(result,indent=2),encoding='utf-8');print(json.dumps(result))
    if not result['passed']: raise SystemExit('Rebuilt output differs from the packaged model.')
if __name__=='__main__': main()
