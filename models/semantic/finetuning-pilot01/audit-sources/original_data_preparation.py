"""Freeze C-reviewed train/val data as mobile-sized letterboxes. Never read test pixels."""
import hashlib,json,shutil
from pathlib import Path
import cv2,numpy as np
ROOT=Path(__file__).resolve().parent;SOURCE=ROOT.parent/'C-finetune-data-20261024';OUT=ROOT/'staged-data'
def sha(p):return hashlib.sha256(Path(p).read_bytes()).hexdigest()
def save(p,obj):Path(p).write_text(json.dumps(obj,ensure_ascii=False,indent=2),encoding='utf-8')
ready=json.loads((SOURCE/'dataset-ready.json').read_text(encoding='utf-8-sig'))
assert ready['status']=='ready','Wait for independent annotation review.'
manifest_path=SOURCE/'split-manifest.json';manifest=json.loads(manifest_path.read_text(encoding='utf-8-sig'))
assert manifest['ignoreIndex']==255 and manifest['numClasses']==150
# Require the ready record to pin this exact frozen manifest, whatever its descriptive field name.
assert sha(manifest_path) in json.dumps(ready),'Ready record must pin the reviewed manifest SHA256.'
split_sources={split:{r['sourceVideoSha256'] for r in manifest['samples'] if r['split']==split} for split in ['train','val','test']}
assert not (split_sources['train']&split_sources['val'] or split_sources['train']&split_sources['test'] or split_sources['val']&split_sources['test'])
names=json.loads((SOURCE/'classes.json').read_text(encoding='utf-8-sig'))
assert set(names)==set(map(str,range(150))) and names['0']=='wall'
expected=json.loads((ROOT/'environment-gpu.json').read_text())['names'];assert names==expected
OUT.mkdir(exist_ok=False)
samples=[];source_hashes={};staged_hashes={};counts={split:np.zeros(256,dtype=np.int64) for split in ['train','val']}
for r in manifest['samples']:
    if r['split'] not in ['train','val']:continue
    image_path=(SOURCE/r['image']).resolve();mask_path=(SOURCE/r['mask']).resolve()
    assert image_path.is_relative_to(SOURCE.resolve()) and mask_path.is_relative_to(SOURCE.resolve())
    assert sha(image_path)==r['sourceRgbSha256'] and sha(mask_path)==r['maskSha256']
    for p in [image_path,mask_path]:source_hashes[str(p)]=sha(p)
    rgb=cv2.cvtColor(cv2.imread(str(image_path)),cv2.COLOR_BGR2RGB);mask=cv2.imread(str(mask_path),cv2.IMREAD_UNCHANGED)
    h,w=mask.shape;assert rgb.shape==(h,w,3) and mask.dtype==np.uint8
    assert np.all((mask<150)|(mask==255)) and int((mask!=255).sum())==r['annotatedPixels']
    size=320;scale=min(size/h,size/w);rw,rh=round(w*scale),round(h*scale)
    left=round((size-rw)/2-.1);top=round((size-rh)/2-.1)
    out_rgb=np.full((size,size,3),114,np.uint8);out_mask=np.full((size,size),255,np.uint8)
    out_rgb[top:top+rh,left:left+rw]=cv2.resize(rgb,(rw,rh),interpolation=cv2.INTER_LINEAR)
    out_mask[top:top+rh,left:left+rw]=cv2.resize(mask,(rw,rh),interpolation=cv2.INTER_NEAREST_EXACT)
    assert (out_mask!=255).any()
    image_dest=OUT/'images'/r['split']/f"{r['sampleId']}.png";mask_dest=OUT/'masks'/r['split']/f"{r['sampleId']}.png"
    image_dest.parent.mkdir(parents=True,exist_ok=True);mask_dest.parent.mkdir(parents=True,exist_ok=True)
    cv2.imwrite(str(image_dest),cv2.cvtColor(out_rgb,cv2.COLOR_RGB2BGR));cv2.imwrite(str(mask_dest),out_mask)
    for p in [image_dest,mask_dest]:staged_hashes[str(p)]=sha(p)
    histogram=np.bincount(out_mask.ravel(),minlength=256);counts[r['split']]+=histogram
    samples.append(dict(sampleId=r['sampleId'],split=r['split'],sourceVideoSha256=r['sourceVideoSha256'],sourcePtsMs=r['sourcePtsMs'],
        originalImageSha256=r['sourceRgbSha256'],originalMaskSha256=r['maskSha256'],originalSize=[w,h],resizedSize=[rw,rh],
        padding=[left,top,size-rw-left,size-rh-top],image=str(image_dest),mask=str(mask_dest),
        annotatedPixels=int((out_mask!=255).sum()),ignorePixels=int(histogram[255]),
        classCounts={str(k):int(histogram[k]) for k in range(150) if histogram[k]}))
dataset=dict(path=OUT.as_posix(),train='images/train',val='images/val',masks_dir='masks',names=names,nc=150)
save(OUT/'dataset.yaml',dataset);staged_hashes[str(OUT/'dataset.yaml')]=sha(OUT/'dataset.yaml')
assert all(sha(p)==s for p,s in source_hashes.items())
record=dict(status='ready',ignoreIndex=255,numClasses=150,CReadySha256=sha(SOURCE/'dataset-ready.json'),
    CManifestSha256=sha(manifest_path),sourceVideoSplitDisjoint=True,sourceVideoSplits={k:sorted(v) for k,v in split_sources.items()},
    trainingAndValidationOnly=True,testPixelsRead=False,testStaged=False,sourceFileHashes=source_hashes,stagedFileHashes=staged_hashes,
    samples=samples,statistics={k:dict(images=sum(r['split']==k for r in samples),annotatedPixels=int(v[:150].sum()),ignorePixels=int(v[255]),
        classCounts={str(i):int(v[i]) for i in range(150) if v[i]}) for k,v in counts.items()},
    interpolation=dict(rgb='OpenCV INTER_LINEAR',mask='INTER_NEAREST_EXACT'),
    annotationPolicy='AI-assisted partial polygons, not human-verified dense ground truth')
save(ROOT/'data-freeze.json',record)
shutil.copy2(SOURCE/'dataset-ready.json',ROOT/'C-dataset-ready-snapshot.json')
print(json.dumps(record['statistics'],ensure_ascii=False,indent=2))
