"""Offline same-frame specialist evidence merger. No inference, training or GT access."""
from __future__ import annotations
import argparse,hashlib,json,math,re
from pathlib import Path
import cv2
import numpy as np
ROOT=Path(__file__).resolve().parent
RULES=json.loads((ROOT/'fusion_rules.json').read_text())
SHA=re.compile(r'[0-9a-f]{64}')

def sha(path):return hashlib.sha256(Path(path).read_bytes()).hexdigest()
def validate_array(array,shape=None):
    if array.dtype!=np.uint8 or array.ndim!=2 or not array.size or int(array.max())>=150:
        raise ValueError('Expected nonempty uint8 ADE150 raster, no unknown/background remapping')
    if shape is not None and array.shape!=shape:raise ValueError('Grid mismatch; align before fusion')

def merge_maps(base,sky,structure):
    """Pure deterministic merger: three predictions only, never a ground-truth argument."""
    validate_array(base);validate_array(sky,base.shape);validate_array(structure,base.shape)
    protected=np.isin(base,RULES['protectedBaseAdeIds'])
    structural=np.isin(structure,RULES['structureOverrideAdeIds']) & ~protected
    sky_selected=(sky==RULES['skyAdeId']) & ~protected
    merged=base.copy();owner=np.zeros(base.shape,np.uint8)
    merged[structural]=structure[structural];owner[structural]=2
    merged[sky_selected]=2;owner[sky_selected]=3
    owner[protected]=1
    assert np.array_equal(merged[protected],base[protected])
    return merged,merged==2,dict(protected=protected,structureSelected=structural & ~sky_selected,
        skySelected=sky_selected,baseFallback=owner==0,owner=owner)

def validate_provenance(frame,source):
    if source['frameId']!=frame['id'] or source['rgbSha256']!=frame['rgbSha256']:
        raise ValueError('Cross-frame or RGB provenance mismatch')
    if source['uprightSize']!=frame['uprightSize']:raise ValueError('Source geometry mismatch')
    if not SHA.fullmatch(source['modelSha256']):raise ValueError('Missing exact model SHA')
    if source['coordinateSpace']!='upright-full-frame':raise ValueError('Unsupported coordinate space')
    if source['status'] not in ('ok','unavailable'):raise ValueError('Unknown role status')

def validate_boxes(boxes):
    valid=[]
    for box in boxes:
        values=[box[k] for k in ('left','top','right','bottom','score')]
        if not all(isinstance(v,(int,float)) and math.isfinite(v) for v in values):raise ValueError('Invalid box number')
        l,t,r,b,score=values
        if not (0<=l<r<=1 and 0<=t<b<=1 and 0<=score<=1):raise ValueError('Box must be normalized upright XYXY')
        if not isinstance(box['label'],str):raise ValueError('Missing box class')
        valid.append(dict(box))
    return valid

def box_union_area(boxes):
    xs=sorted(set([float(b[k]) for b in boxes for k in ['left','right']]))
    area=0.
    for l,r in zip(xs,xs[1:]):
        intervals=sorted((b['top'],b['bottom']) for b in boxes if b['left']<r and b['right']>l)
        end=0.;height=0.
        for top,bottom in intervals:
            height+=max(0.,bottom-max(top,end));end=max(end,bottom)
        area+=(r-l)*height
    return area

def load_request(path):
    request=json.loads(Path(path).read_text(encoding='utf-8-sig'))
    frame=request['frame']
    if not SHA.fullmatch(frame['rgbSha256']) or sha(frame['rgbPath'])!=frame['rgbSha256']:
        raise ValueError('RGB file hash mismatch')
    rgb=cv2.cvtColor(cv2.imread(frame['rgbPath']),cv2.COLOR_BGR2RGB)
    if [rgb.shape[1],rgb.shape[0]]!=frame['uprightSize']:raise ValueError('RGB size mismatch')
    arrays={};provenance={}
    for role in ['base','sky','structure','objects']:
        source=request['sources'][role];validate_provenance(frame,source);provenance[role]=source
        if source['status']!='ok':arrays[role]=None;continue
        if role=='objects':arrays[role]=validate_boxes(source['boxes']);continue
        if sha(source['path'])!=source['sha256']:raise ValueError(f'{role} prediction file hash mismatch')
        arrays[role]=cv2.imread(source['path'],cv2.IMREAD_UNCHANGED)
        validate_array(arrays[role],rgb.shape[:2])
    return request,rgb,arrays,provenance

def run(request_path,outdir):
    from kernels import observe,evidence,groups
    request,rgb,arrays,provenance=load_request(request_path)
    out=Path(outdir);out.mkdir(exist_ok=False)
    frame=request['frame'];h,w=rgb.shape[:2]
    merged=None;sky_mask=None;ownership=None
    if all(arrays[k] is not None for k in ['base','sky','structure']):
        merged,sky_mask,ownership=merge_maps(arrays['base'],arrays['sky'],arrays['structure'])
    # Unknown roles stay unknown, never become fabricated zero observations.
    boxes=arrays['objects'];persons=None
    if boxes is not None:
        people=[b for b in boxes if b['label']=='person' and b['score']>=.5]
        persons=dict(detectionCount=len(people),areaRatio=box_union_area(people),
            threshold=.5,capacityReached=provenance['objects'].get('capacityReached'),uniquePersonCount=None)
    light=None;sky_stats=None
    if merged is not None:
        measurement,masks,small,sem=observe(rgb,merged,boxes or [],max(w,h),sky_mask)
        union=masks['union'];by_kind=[]
        kinds={1:'building_surface',8:'window_surface',43:'sign_surface',87:'road_lighting'}
        for ade,kind in kinds.items():
            selected=union & (sem==ade)
            by_kind.append(dict(kind=kind,adeId=ade,**evidence(selected,small,sem)))
        assert sum(x['pixels'] for x in by_kind)==int(union.sum())
        assert not np.any(union & sky_mask) and not np.any(union & masks['forbidden'])
        cv2.imwrite(str(out/'light-union.png'),union.astype(np.uint8)*255)
        cv2.imwrite(str(out/'light-forbidden.png'),masks['forbidden'].astype(np.uint8)*255)
        light=dict(status='observed' if boxes is not None else 'partial-object-veto-unavailable',
            groups=by_kind,summary=evidence(union,small,sem),
            vehicleVetoSourceAvailable=boxes is not None,confirmedEmissionType=None,shops=None,
            activityContribution=None,quality=None,
            measurementVersion='specialist-semantic-contrast-v1',
            referenceValueP95=measurement['referenceValueP95'])
        p=rgb[sky_mask].astype(np.float32)/255
        sky_stats=dict(pixelCount=int(sky_mask.sum()),areaRatio=float(sky_mask.mean()),
            meanRgb=p.mean(0).tolist() if len(p) else None,
            meanEncodedLuma=float((p@np.array([.2126,.7152,.0722])).mean()) if len(p) else None,
            physicalLux=None,maskSource=provenance['sky']['modelSha256'])
        cv2.imwrite(str(out/'semantic-fused.png'),merged)
        cv2.imwrite(str(out/'sky-selected.png'),sky_mask.astype(np.uint8)*255)
        cv2.imwrite(str(out/'sky-n640-comparison-only.png'),(arrays['sky']==2).astype(np.uint8)*255)
        cv2.imwrite(str(out/'ownership.png'),ownership['owner'])
    result=dict(version=RULES['version'],frame=frame,provenance=provenance,
        rulesSha256=sha(ROOT/'fusion_rules.json'),implementationSha256=sha(Path(__file__)),
        requestSha256=sha(request_path),persons=persons,sky=sky_stats,light=light,
        status='complete' if merged is not None and boxes is not None else 'partial',
        missingRoles=[k for k,v in arrays.items() if v is None],
        groundTruthUsed=False,newModelInferenceExecuted=False,accuracyMeasured=False,
        note='Fixed development fusion prototype; source classifications and lighting quality remain unverified.')
    (out/'result.json').write_text(json.dumps(result,ensure_ascii=False,indent=2,allow_nan=False),encoding='utf-8')
    return result

if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('request');parser.add_argument('outdir')
    args=parser.parse_args();result=run(args.request,args.outdir)
    print(json.dumps(dict(status=result['status'],missingRoles=result['missingRoles']),ensure_ascii=False))
