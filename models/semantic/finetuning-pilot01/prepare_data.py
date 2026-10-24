import argparse,hashlib,json
from pathlib import Path
import cv2,numpy as np
p=argparse.ArgumentParser();p.add_argument('--source',required=True);p.add_argument('--out',required=True);a=p.parse_args()
src=Path(a.source).resolve();out=Path(a.out).resolve();sha=lambda p:hashlib.sha256(p.read_bytes()).hexdigest()
ready=json.loads((src/'dataset-ready.json').read_text(encoding='utf-8-sig'))
assert ready['status']=='ready' and ready['datasetFingerprint']=='5ce3b399089bec9c478f3f3418322a9bd4457af7b61eb707f0354f8bf82639a5'
manifest_path=src/'split-manifest.json';assert sha(manifest_path)=='27e7cb5b1b352e217c6836896b574d6dd275bb81c164f5e6c608f1adf47b6986'
manifest=json.loads(manifest_path.read_text(encoding='utf-8-sig'));assert manifest['numClasses']==150 and manifest['ignoreIndex']==255
names=json.loads((src/'classes.json').read_text());assert names['0']=='wall' and len(names)==150
sources={s:{r['sourceVideoSha256'] for r in manifest['samples'] if r['split']==s} for s in ['train','val','test']}
assert not(sources['train']&sources['val'] or sources['train']&sources['test'] or sources['val']&sources['test'])
out.mkdir(parents=True,exist_ok=False)
for row in manifest['samples']:
    if row['split'] not in ['train','val']:continue
    image=(src/row['image']).resolve();mask=(src/row['mask']).resolve();assert image.is_relative_to(src) and mask.is_relative_to(src)
    assert sha(image)==row['sourceRgbSha256'] and sha(mask)==row['maskSha256']
    rgb=cv2.imread(str(image));labels=cv2.imread(str(mask),cv2.IMREAD_UNCHANGED);h,w=labels.shape
    assert labels.dtype==np.uint8 and np.all((labels<150)|(labels==255))
    factor=min(320/w,320/h);rw,rh=round(w*factor),round(h*factor);left=round((320-rw)/2-.1);top=round((320-rh)/2-.1)
    im=np.full((320,320,3),114,np.uint8);gt=np.full((320,320),255,np.uint8)
    im[top:top+rh,left:left+rw]=cv2.resize(rgb,(rw,rh),interpolation=cv2.INTER_LINEAR)
    gt[top:top+rh,left:left+rw]=cv2.resize(labels,(rw,rh),interpolation=cv2.INTER_NEAREST_EXACT)
    for folder,value in [('images',im),('masks',gt)]:
        dest=out/folder/row['split']/f"{row['sampleId']}.png";dest.parent.mkdir(parents=True,exist_ok=True);cv2.imwrite(str(dest),value)
(out/'dataset.yaml').write_text(json.dumps(dict(path=out.as_posix(),train='images/train',val='images/val',masks_dir='masks',names=names,nc=150)),encoding='utf-8')
