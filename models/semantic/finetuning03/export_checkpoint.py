"""Export a local trained checkpoint. Model operators/weights are never edited."""
import argparse,copy,json,hashlib
from pathlib import Path
import onnx
from ultralytics import YOLO
def sha(p):return hashlib.sha256(p.read_bytes()).hexdigest()
def main():
    parser=argparse.ArgumentParser();parser.add_argument('checkpoint',type=Path)
    args=parser.parse_args();checkpoint=args.checkpoint.resolve()
    model=YOLO(str(checkpoint),task='semantic')
    output=Path(model.export(format='onnx',imgsz=320,batch=1,opset=17,dynamic=False,simplify=False,device='cpu'))
    graph=onnx.load(output);before=[(t.name,t.SerializeToString()) for t in graph.graph.initializer]
    for item in graph.metadata_props:
        if item.key=='description':item.value='Driving Mood pilot03 C semantic model; ADE20K class contract'
    onnx.checker.check_model(graph)
    assert before==[(t.name,t.SerializeToString()) for t in graph.graph.initializer]
    onnx.save(graph,output)
    output.with_suffix('.provenance.json').write_text(json.dumps(dict(checkpointSha256=sha(checkpoint),onnxSha256=sha(output),metadataDescriptionRedacted=True,weightsUnchanged=True),indent=2))
    print(output.name)
if __name__=='__main__':main()
