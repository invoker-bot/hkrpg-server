"""Audit actual Native snapshots, stopped H2 output and logs without starting any server."""
import argparse
import hashlib
import json
from pathlib import Path
import re

ROOT=next(p for p in Path(__file__).resolve().parents if(p/'AGENTS.md').is_file())
sha=lambda path:hashlib.sha256(Path(path).read_bytes()).hexdigest()
PHASES=['open','paid','settled','repeat','restored']

def normal_stops(raw):
    plain=re.sub(r'\x1b\[[0-9;]*m','',raw)
    return len(re.findall(r'\[INFO\] Stopping server',plain))

def audit(report_path):
    path=Path(report_path).resolve();assert path.is_relative_to((ROOT/'build').resolve())
    report=json.loads(path.read_text('utf-8'));assert report['passed']is True and report['actualCompletedCaseCount']==2
    assert report['executedMainMissions']==[3011605,3011608]
    jar=ROOT/'build/feixiao-selected-item-blessing-console-candidate-runtime.jar'
    assert sha(jar)==report['jarSHA256']=='3eeeab41ec750f60b92c2ab3a536760afab14eb53c71581d34e18bafb02fa05c'
    actual=[];requests=checks=stops=reads=restarts=0
    for case in report['cases']:
        directory=Path(case['runtimeDirectory']).resolve();assert directory.is_relative_to((ROOT/'build/smoke').resolve())
        assert case['passed'] and case['scenario']=='settled' and case['executedChoiceCaseIndex']==0
        mid=case['mainMission'];snapshots=case['snapshots'];h2=case['readOnlyH2Inspections']
        assert len(snapshots)==len(h2)==5 and [s['phase']for s in snapshots]==PHASES and [s['dayIndex']for s in snapshots]==[1,1,1,2,2]
        assert len({s['uid']for s in snapshots})==1 and [s['loginWallet']for s in snapshots]==[0,0,0,5000,10000]
        req=sum(s['requests']for s in snapshots);checked=sum(len(s['checks'])for s in snapshots);actual_stop=actual_read=0
        assert case['requests']==req and case['checks']==checked
        hashes={}
        for index,(snapshot,view)in enumerate(zip(snapshots,h2)):
            server=directory/f'server-{index}.log';probe=directory/f'probe-{index}.log';stored=directory/f'h2-{index}.json'
            assert server.is_file()and probe.is_file()and stored.is_file()
            lines=server.read_text('utf-8',errors='replace');normal=normal_stops(lines)
            assert normal==1 and 'Client connected from 127.0.0.1'in lines
            actual_stop+=normal
            wire=json.loads(next(line for line in reversed(probe.read_text('utf-8').splitlines())if line.startswith('{')))
            assert wire['requests']==snapshot['requests']and wire['checks']==snapshot['checks']and wire['uid']==snapshot['uid']
            assert view==json.loads(stored.read_text('utf-8'))and view['passed']and view['mvStoreReadOnly']and view['databaseFileBytesUnchanged']
            assert view['uid']==snapshot['uid']and view['mainMission']==mid and view['phase']==snapshot['phase']and view['independentMorphiaReadCount']==2
            actual_read+=view['independentMorphiaReadCount']
            assert view['credit']==[0,0,5000,10000,10000][index]and view['activePoints']==[0,0,200,200,200][index]
            selected=view['currentRun']['select'];tail=view['currentRun']['talk'];main=view['currentRun']['main']
            assert selected['progress']==(0 if index==0 else 1) and tail['progress']==(0 if index<2 else 1)
            assert main['phase']==('DOING'if index<2 else'CLOSED')
            if index:
                receipt=view['currentRun']['receipt'];assert receipt['uid']==snapshot['uid']and receipt['main']==mid and receipt['retiredAt']==0
                assert receipt['item']==(case['actualD0Item']if index<3 else case['actualD1Item'])
                assert receipt['mainAcceptance']==main['acceptance']and receipt['selectAcceptance']==selected['acceptance']and receipt['talkAcceptance']==tail['acceptance']
            if index>=3:
                old=view['archivedRun']['receipt'];new=view['currentRun']['receipt'];assert old['retiredAt']>0 and old['item']==case['actualD0Item']
                assert all(old[key]!=new[key]for key in ('event','mainAcceptance','selectAcceptance','talkAcceptance'))
            for file in(server,probe,stored,directory/f'h2-{index}.log'):hashes[file.relative_to(directory).as_posix()]=sha(file)
        assert actual_stop==case['normalStopCount']==5 and actual_read==case['independentStoppedH2MorphiaReadCount']==10
        assert case['sameUIDNormalH2RestartCount']==actual_stop-1
        proof=case['originalWholeSourceProof'];assert proof['completeOriginalFiles']==36 and proof['completeSelectedObjects']==131 and proof['sourceOnlyInstalledByTool']is False
        for relative,digest in proof['sourceFilesSHA256'].items():assert sha(ROOT/'resources/4.6'/relative)==digest
        assert case['mirrorLayout']['directoryJunctionCount']==0 and case['paymentOrGoalSeeded']is False and case['nativeToolInputsStable']is True
        for name,digest in case['capturedHelperSources'].items():assert sha(name)==digest
        before=json.loads((directory/'native-tool-inputs-before.json').read_text('utf-8'));assert before==case['nativeToolInputSHA256']
        actual.append(dict(mainMission=mid,runtimeDirectory=str(directory),requests=req,checks=checked,normalStops=actual_stop,actualPlayerReads=actual_read,
            sameUIDRestarts=actual_stop-1,rawEvidenceSHA256=hashes,sourceOnlyStatus=proof['sourceOnlyStatus']))
        requests+=req;checks+=checked;stops+=actual_stop;reads+=actual_read;restarts+=actual_stop-1
    assert (report['requests'],report['checks'],report['normalStopCount'],report['independentStoppedH2MorphiaReadCount'],report['sameUIDNormalH2RestartCount'])==(requests,checks,stops,reads,restarts)
    return dict(passed=True,sourceReport=str(path),sourceReportSHA256=sha(path),runtimeJarSHA256=sha(jar),actualCases=actual,requests=requests,checks=checks,
        normalStopCount=stops,independentActualPlayerReadCount=reads,sameUIDNormalH2RestartCount=restarts,serverStartedByAudit=False,
        clientUIVerified=False,clientActOrCustomStringExecuted=False,realWallClockCrossDayVerified=False)

if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('report',type=Path);parser.add_argument('--output',type=Path,required=True);args=parser.parse_args()
    value=audit(args.report);assert args.output.resolve().is_relative_to((ROOT/'build').resolve())
    with args.output.open('x',encoding='utf-8')as output:json.dump(value,output,ensure_ascii=False,indent=2)
    print(json.dumps({key:value[key]for key in('passed','requests','checks','normalStopCount','independentActualPlayerReadCount','sameUIDNormalH2RestartCount')}))
