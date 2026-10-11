"""Read-only audit of current-lane raw logs, real stops, old phone reads and added NPC evidence."""
import argparse
import importlib.util
import json
from pathlib import Path

OWN=Path(__file__).resolve().parent
ROOT=next(parent for parent in Path(__file__).resolve().parents if (parent/'scripts/verify-daily-message-origin-native.py').is_file())


def module(name,path):
    spec=importlib.util.spec_from_file_location(name,path)
    result=importlib.util.module_from_spec(spec);spec.loader.exec_module(result);return result


base=module('origin_existing_native_audit',ROOT/'scripts/verify-daily-message-origin-native.py')
effects=module('effects_current_native_audit',OWN/'smoke-daily-origin-effects.py')


def audit(report,jar):
    existing=base.audit(report,jar)
    raw=json.loads(report.read_text('utf-8'));case=raw['cases'][0]
    added=effects.verify_effect_case(case)
    assert case['originalInitialEffectsVerification']==added
    assert case['wireFields']['SceneGroupRefreshScNotify']['command']==1433
    field=case['wireFields']['SceneGroupRefreshScNotify']['fields']['group_refresh_list/refresh_entity/delete_entity']
    assert field['tags']==[7,6,14] and field['type']=='UINT32'
    directory=Path(case['runtimeDirectory']);logs=[]
    for index,value in enumerate(case['snapshots']):
        if value['phase']=='initial':
            log=directory/f'probe-{index}.log'
            actual=base.last_json(log)
            assert actual['originalInitialNpcRemovalWireIdsBeforeAck']==value['originalInitialNpcRemovalWireIdsBeforeAck']
            assert actual['originalInitialEffectsSceneViews']==value['originalInitialEffectsSceneViews']
            logs.append(dict(path=str(log.relative_to(ROOT)),sha256=base.digest(log),dayIndex=value['dayIndex'],
                actualNpcDeletesBeforeAck=value['originalInitialNpcRemovalWireIdsBeforeAck'],sceneViews=value['originalInitialEffectsSceneViews']))
    assert len(logs)==2
    return dict(existingLaneAudit=existing,addedOriginalEffectsAudit=added,
        realEffectsProbeLogs=logs,passed=True,actualSameUidNormalRestarts=existing['sameUIDNormalH2RestartCount'],
        existingIndependentG61PlayerReads=sum(row['independentMorphiaReadCount']for row in case['readOnlyG61H2Inspections']),
        existingIndependentPhonePlayerReads=sum(row['independentMorphiaReadCount']for row in case['readOnlyPhoneH2Inspections']),
        effectsVerifiedInsideExistingPhoneReads=True,additionalIndependentEffectsPlayerReads=0,
        nativeCaseIndices=[0],clientActVerified=False,clientCustomStringVerified=False,clientUIVerified=False)


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--report',type=Path,required=True);parser.add_argument('--jar',type=Path,required=True)
    parser.add_argument('--output',type=Path,required=True);args=parser.parse_args()
    result=audit(args.report.resolve(),args.jar.resolve())
    output=args.output.resolve();assert output.is_relative_to((ROOT/'build').resolve())
    with output.open('x',encoding='utf-8')as stream:json.dump(result,stream,ensure_ascii=False,indent=2)
    print(json.dumps(dict(passed=True,output=str(output),requests=result['existingLaneAudit']['requests'],
        checks=result['existingLaneAudit']['checks'],snapshots=result['existingLaneAudit']['snapshots'],
        normalStops=result['existingLaneAudit']['normalStopCount'],
        sameUidNormalRestarts=result['actualSameUidNormalRestarts'],
        independentG61Reads=result['existingIndependentG61PlayerReads'],
        independentPhoneAndEffectsReads=result['existingIndependentPhonePlayerReads'],
        additionalEffectsReads=result['additionalIndependentEffectsPlayerReads']),ensure_ascii=False))
