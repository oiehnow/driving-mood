"""Apply the pre-frozen development screening policy; never promote an app model."""
from pathlib import Path
import json
import compare as c

def screen(report, policy, name):
    checks=[]; unsupported=[]; macro_deltas=[]; focus_deltas=[]
    def check(scope, metric, baseline, candidate, lower=None, upper=None):
        if baseline is None or candidate is None:
            unsupported.append({'scope':scope,'metric':metric,'baseline':baseline,'candidate':candidate})
            return
        delta=candidate-baseline
        passed=(lower is None or delta>=lower-1e-12) and (upper is None or delta<=upper+1e-12)
        checks.append({'scope':scope,'metric':metric,'baseline':baseline,'candidate':candidate,
                       'deltaPercentagePoints':delta*100,'minimumDelta':lower,'maximumDelta':upper,'passed':passed})
    for scope in policy['scopes']:
        values=report['scopes'][scope]['overall'];base=values['baseline'];candidate=values[name]
        key='macroIoUSupportedGT'
        check(scope,key,base[key],candidate[key],lower=policy['minimumMacroIoUDeltaEachScope'])
        macro_deltas.append(candidate[key]-base[key])
        for class_id in policy['focusClassIds']:
            b=base['classes'][str(class_id)];a=candidate['classes'][str(class_id)]
            for metric in ('iou','recall'):
                check(scope,f'class{class_id}_{metric}',b[metric],a[metric],lower=-policy['maximumFocusIoUOrRecallRegression'])
            if b['iou'] is not None and a['iou'] is not None: focus_deltas.append(a['iou']-b['iou'])
        for metric in ('iou','recall'):
            check(scope,'vehicle_group_'+metric,base['vehicleGroup20_80_83'][metric],candidate['vehicleGroup20_80_83'][metric],
                  lower=-policy['maximumVehicleGroupIoUOrRecallRegression'])
        check(scope,'sign_precision',base['classes']['43']['precision'],candidate['classes']['43']['precision'],
              lower=-policy['maximumSignPrecisionRegression'])
        key='signFalsePositiveOnNonSignLabeledRate'
        check(scope,key,base[key],candidate[key],upper=policy['maximumSignFPNonSignLabeledRateIncrease'])
        key='eligibleSurfaceConfusionRate'
        check(scope,'vehicle_'+key,base['vehicleGroup20_80_83'][key],candidate['vehicleGroup20_80_83'][key],
              upper=policy['maximumVehicleEligibleSurfaceConfusionRateIncrease'])
    mean=sum(macro_deltas)/len(macro_deltas)
    check('mean_of_development_scopes','mean_macro_iou_delta',0,mean,lower=policy['minimumMeanMacroIoUDelta'])
    return {'name':name,'passed':all(row['passed'] for row in checks),'failedChecks':[row for row in checks if not row['passed']],
            'checks':checks,'unsupportedChecks':unsupported,'worstScopeMacroDelta':min(macro_deltas),
            'meanMacroDelta':mean,'meanFocusIoUDelta':sum(focus_deltas)/len(focus_deltas) if focus_deltas else None}

def main():
    freeze=c.read(c.ROOT/'protocol-freeze.json');policy=c.read(c.ROOT/'protocol.json')
    c.require(c.sha(c.ROOT/'protocol.json')==freeze['protocolSha256'],'Frozen policy changed')
    report=c.read(c.ROOT/'results-v1/comparison.json')
    results={name:screen(report,policy,name) for name in ('pilot02','A','B')}
    eligible=[result for name,result in results.items() if name in ('A','B') and result['passed']]
    ordered=sorted(eligible,key=lambda result:(result['worstScopeMacroDelta'],result['meanFocusIoUDelta'],result['name']=='A'),reverse=True)
    decision={'role':'development_screening_only','independentTest':False,'automaticAppPromotion':False,
              'protocolSha256':freeze['protocolSha256'],'comparisonSha256':c.sha(c.ROOT/'results-v1/comparison.json'),
              'preferredCandidate':ordered[0]['name'] if ordered else None,
              'defaultModel':'official baseline until separate adoption approval and untouched evaluation', 'candidates':results}
    c.save(c.ROOT/'development-decision.json',decision)
    print(json.dumps({'preferredCandidate':decision['preferredCandidate'],'candidates':{k:{'passed':v['passed'],'failedChecks':v['failedChecks']} for k,v in results.items()}},ensure_ascii=False,indent=2))

if __name__=='__main__':main()
