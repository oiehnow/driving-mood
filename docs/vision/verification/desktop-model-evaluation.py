"""Windows CPU LiteRT smoke + licensed-still evaluation. Not Android/FPS/accuracy proof.
Creates RGB/box fixtures for the SAME Kotlin LightMeasurer; no Python scoring clone.
"""
import hashlib
import json
import pathlib
import sys
import zipfile
import numpy as np
from PIL import Image, ImageOps
from ai_edge_litert.interpreter import Interpreter

HERE=pathlib.Path(__file__).resolve().parent
APP=HERE.parents[2]
MODEL=APP/'app/src/main/assets/vision/efficientdet-lite0-metadata-v1.tflite'
OUT=HERE/'build/offline'
OUT.mkdir(parents=True,exist_ok=True)
assert hashlib.sha256(MODEL.read_bytes()).hexdigest()=='2e04c53bfeac0ac2a30c057c7e2a777594ce39baaac35a92f74fb1e8c4fc4e0b'
with zipfile.ZipFile(MODEL) as z:
    labels=z.read('labelmap.txt').decode().splitlines()
interpreter=Interpreter(model_path=str(MODEL),num_threads=2)
interpreter.allocate_tensors()
input_info=interpreter.get_input_details()[0]
output_info=interpreter.get_output_details()
assert input_info['dtype']==np.uint8 and input_info['shape'].tolist()==[1,320,320,3]
report={'runtime':'ai-edge-litert 2.3.0 Windows x64 CPU, Python '+sys.version.split()[0],
        'scope':'Still samples; Pillow stretch to 320x320 for model. Android MediaPipe preprocessing/native execution not validated.',
        'modelSha256':hashlib.sha256(MODEL.read_bytes()).hexdigest(),
        'input':{'shape':input_info['shape'].tolist(),'dtype':str(input_info['dtype'])},
        'outputs':[{'name':o['name'],'shape':o['shape'].tolist()} for o in output_info], 'samples':[]}
sources_path=HERE.parent/'evaluation/sources.json'
sources=json.loads(sources_path.read_text(encoding='utf-8')) if sources_path.exists() else []
inputs=[('synthetic-black',Image.new('RGB',(320,240),(0,0,0)),None)]
for source in sources:
    original=HERE.parent/'evaluation'/(source['id']+'.jpg')
    assert hashlib.sha256(original.read_bytes()).hexdigest()==source['sha256']
    inputs.append((source['id'],ImageOps.exif_transpose(Image.open(original)).convert('RGB'),source))
index=[]
for name,original,source in inputs:
    small=original.copy(); small.thumbnail((320,320),Image.Resampling.BILINEAR)
    interpreter.set_tensor(input_info['index'],np.asarray(original.resize((320,320),Image.Resampling.BILINEAR),dtype=np.uint8)[None,...])
    interpreter.invoke()
    outputs=[interpreter.get_tensor(o['index']) for o in output_info]
    assert all(np.isfinite(v).all() for v in outputs)
    # This pinned model has standard TFLite_Detection_PostProcess output order.
    boxes,classes,scores,count=outputs
    assert boxes.shape==(1,25,4) and classes.shape==(1,25) and scores.shape==(1,25)
    found=[]
    for j in range(min(25,int(count[0]))):
        if scores[0,j]<.25: continue
        category=int(classes[0,j]); y0,x0,y1,x1=map(float,boxes[0,j])
        found.append({'label':labels[category],'confidence':float(scores[0,j]),'left':x0,'top':y0,'right':x1,'bottom':y1})
    # Big-endian signed ARGB matches DataInputStream.readInt without image decoder differences.
    rgb=np.asarray(small,dtype=np.uint32)
    argb=(np.uint32(0xff000000)|(rgb[:,:,0]<<16)|(rgb[:,:,1]<<8)|rgb[:,:,2]).astype('>u4')
    (OUT/(name+'.argb')).write_bytes(argb.tobytes())
    (OUT/(name+'.boxes.tsv')).write_text('\n'.join('\t'.join(str(b[k]) for k in ['left','top','right','bottom','label','confidence']) for b in found),encoding='utf-8')
    index.append(f'{name}\t{small.width}\t{small.height}\t{len(found)>=25}')
    report['samples'].append({'id':name,'synthetic':source is None,'boxes':found,'size':[small.width,small.height]})
    print(name,'detections',len(found),[(b['label'],round(b['confidence'],3)) for b in found])
(OUT/'index.tsv').write_text('\n'.join(index),encoding='utf-8')
(HERE.parent/'evaluation/model-results.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf-8')
