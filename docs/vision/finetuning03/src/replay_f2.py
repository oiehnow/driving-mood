"""Replay the fixed F2 rule using authorized local inputs matching published hashes.

Usage: python src/replay_f2.py --root /path/to/research-root --scope data02_development_val --out /new/output
The root contains research/ as described by input-hash-manifest.json. Images/weights are not included here.
"""
from pathlib import Path
import argparse, importlib.util
import numpy as np
import onnxruntime as ort
from PIL import Image
import compare as c
from compare_additional import prep,align

PACKAGE=Path(__file__).resolve().parent.parent

def replay(root,scope,out):
    c.require(not out.exists(),'Choose a new output directory')
    manifest=c.read(PACKAGE/'input-hash-manifest.json');models=c.read(PACKAGE/'model-hash-manifest.json')
    samples=[s for s in manifest['items'] if s['scope']==scope]
    c.require(bool(samples),'Unknown scope')
    spec=importlib.util.spec_from_file_location('fixed_fusion',PACKAGE/'src/fusion/fusion.py')
    f=importlib.util.module_from_spec(spec);spec.loader.exec_module(f)
    final=c.read(PACKAGE/'protocols/final-F2-auxiliary-freeze.json')
    c.require(c.sha(PACKAGE/'src/fusion/fusion.py')==final['implementationSha256'],'F2 implementation changed')
    c.require(c.sha(PACKAGE/'src/fusion/fusion_rules.json')==final['rulesSha256'],'F2 rules changed')
    sessions={}
    for n in ('n320','n640','C'):
        path=(root/models[n]['path']).resolve()
        c.require(path.is_relative_to(root.resolve()) and c.sha(path)==models[n]['sha256'],'Model path/hash mismatch')
        options=ort.SessionOptions();options.intra_op_num_threads=2;options.inter_op_num_threads=1
        sessions[n]=ort.InferenceSession(str(path),sess_options=options,providers=['CPUExecutionProvider'])
    out.mkdir(parents=True);totals={n:np.zeros((150,150),np.int64) for n in ('baseline','F2')}
    for s in samples:
        paths={key:(root/s['root']/s[key]).resolve() for key in ('image','mask')}
        for key,path in paths.items():c.require(path.is_relative_to(root.resolve()) and c.sha(path)==s[key+'Sha256'],'Input path/hash mismatch')
        rgb=np.asarray(Image.open(paths['image']).convert('RGB'));gt=np.asarray(Image.open(paths['mask']));pred={}
        for n,session in sessions.items():
            size=session.get_inputs()[0].shape[-1];tensor,box=prep(rgb,size)
            pred[n]=align(session.run(None,{session.get_inputs()[0].name:tensor})[0],box,size)
        result,sky,_=f.merge_maps(pred['n320'],pred['n640'],pred['C'])
        for n,p in (('baseline',pred['n320']),('F2',result)):
            totals[n]+=c.e.confusion(gt,p);Image.fromarray(p).save(out/(s['sampleId']+'-'+n+'.png'))
    c.save(out/'metrics.json',{'scope':scope,'humanGT':False,'rulesChanged':False,'overall':{n:c.extended_metrics(cm) for n,cm in totals.items()}})

if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('--root',type=Path,required=True);p.add_argument('--scope',required=True);p.add_argument('--out',type=Path,required=True)
    args=p.parse_args();replay(args.root,args.scope,args.out)
