"""Audit the actual candidate report, raw probes, normal stops and independent stopped H2 reads."""
import argparse
import hashlib
import json
from pathlib import Path

ROOT=next(parent for parent in Path(__file__).resolve().parents if (parent/'AGENTS.md').is_file()and(parent/'scripts/smoke-server.py').is_file())
sha=lambda path:hashlib.sha256(path.read_bytes()).hexdigest()
parser=argparse.ArgumentParser();parser.add_argument('--report',type=Path,required=True);parser.add_argument('--output',type=Path,required=True);parser.add_argument('--jar',type=Path,required=True);args=parser.parse_args()
path=args.report.resolve();output=args.output.resolve();assert path.is_relative_to(ROOT/'build')and output.is_relative_to(ROOT/'build')and not output.exists()
data=json.loads(path.read_text('utf-8'));expected='39601d345bc6d9726034ca0cd0173eab658572b71344468db7350dc06b23c5ab'
assert data['passed']and data['rootJarSHA256']==expected and data['executedCaseIndices']==[0]and data['actualCompletedCaseCount']==1
assert len(data['cases'])==1
case=data['cases'][0];directory=Path(case['runtimeDirectory']).resolve();assert directory.is_relative_to(ROOT/'build/smoke')and directory.name.startswith('daily-message-g61-native-3000604-followup-')
assert case['nonPoolFollowup']==3000604 and case['nonPoolFollowupCompleted']and case['completedPeriodCount']==1
snapshots=case['snapshots'];assert len(snapshots)==8 and{row['dayIndex']for row in snapshots}=={1}and len({row['uid']for row in snapshots})==1
assert [row['phase']for row in snapshots]==['intro','open','middle','finish','sequel-pending','sequel-entered','sequel-complete','sequel-restored']
assert [row['loginWallet']for row in snapshots]==[0,0,0,0,5000,5000,5000,10000]
assert all(row['clientGameplayVerified']is False and row['sourceClientScriptExecutorVerified']is False for row in snapshots)
raw={};raw_requests=raw_checks=0
for index,row in enumerate(snapshots):
    file=directory/f'probe-{index}.log';values=[json.loads(line)for line in file.read_text('utf-8').splitlines()if line.startswith('{')]
    assert len(values)==1 and values[0]['passed']and values[0]['uid']==row['uid']and values[0]['phase']==row['phase']
    assert values[0]['requests']==row['requests']and values[0]['checks']==row['checks']
    raw_requests+=values[0]['requests'];raw_checks+=len(values[0]['checks']);raw[file.name]=sha(file)
assert data['requests']==case['requests']==raw_requests and data['checks']==case['checks']==raw_checks
stops=0;stop_proof=[]
for index in range(7):
    file=directory/f'server-{index}.log';text=file.read_text('utf-8',errors='replace')
    assert 'Stopping server' in text and 'Client disconnected from 127.0.0.1' in text
    stops+=1;stop_proof.append(dict(log=file.name,sha256=sha(file),normalConsoleStopMarker=True,
        clientDisconnectMarker=True,processExitCheckedByCleanup=True))
assert data['normalStopCount']==case['normalStopCount']==stops
assert case['sameUIDNormalH2RestartCount']==data['sameUIDNormalH2RestartCount']==stops-1
reads=0;h2=[]
for kind,views,prefix in [('G61',case['readOnlyG61H2Inspections'],'g61-h2'),('PhoneAndFollowup',case['readOnlyPhoneH2Inspections'],'phone-h2')]:
    assert len(views)==stops
    for index,view in enumerate(views):
        file=directory/f'{prefix}-{index}.json';actual=json.loads(file.read_text('utf-8'));assert actual==view
        assert actual['passed']and actual['mvStoreReadOnly']and actual['databaseFileBytesUnchanged']and actual['independentMorphiaReadCount']==2
        reads+=2;h2.append(dict(kind=kind,index=index,uid=actual['uid'],reads=2,fileSHA256=sha(file),databaseSHA256=actual['databaseSHA256']))
assert data['independentStoppedH2MorphiaReadCount']==case['independentStoppedH2MorphiaReadCount']==reads
assert [view['followup']['stage']for view in case['readOnlyPhoneH2Inspections']]==[0,0,0,1,2,3,3]
assert case['followupWholeSourcePreflight']['sourceOnlyStatus']=={'Config/Level/NPCDialogue/P20002/F20002001_G85/DialogueMain_F20002001_G85_N400001.json':'absent'}
assert case['followupWholeSourcePreflight']['sourceOnlyInstalledByTool']is False
assert case['controlledCrossDayVerified']is False and case['realWallClockCrossDayVerified']is False
assert case['clientMenuUIVerified']is False and case['sourceClientScriptExecutorVerified']is False
jar=args.jar.resolve();assert jar.is_relative_to(ROOT/'build')and sha(jar)==expected
result=dict(passed=True,reportSHA256=sha(path),jarSHA256=expected,runtimeDirectory=str(directory),
    actualKcpRequests=raw_requests,actualChecks=raw_checks,actualSnapshots=len(snapshots),actualNormalStops=stops,
    actualSameUidH2Restarts=stops-1,actualIndependentPlayerReads=reads,readBreakdown=dict(G61=stops*2,PhoneAndFollowup=stops*2),
    extraFollowupPlayerReads=0,rawProbeLogs=raw,normalStopEvidence=stop_proof,readOnlyH2Evidence=h2,
    sourceOnlyG85DialogueMainInstalled=False,clientActVerified=False,clientMenuUiVerified=False,realWallClockCrossDayVerified=False,
    coreJarFinalByteEqualityStillRequired=True)
output.write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n','utf-8');print(json.dumps({key:value for key,value in result.items()if key not in('rawProbeLogs','normalStopEvidence','readOnlyH2Evidence')},ensure_ascii=False))
