"""只读核对3011014三支路的原生探针、正常停服、实际Player读取及来源SHA；不启动服务器。"""
import argparse
import hashlib
import json
from pathlib import Path
import re

ROOT=Path(__file__).resolve().parents[1]
sha=lambda path:hashlib.sha256(path.read_bytes()).hexdigest()


def audit(path,jar):
    path=Path(path).resolve();jar=Path(jar).resolve()
    assert path.is_relative_to(ROOT/'build')and jar.is_relative_to(ROOT/'build')
    report=json.loads(path.read_text('utf-8'));assert report['passed']is True and report['executedCaseIndexes']==[0,1,2]
    assert report['jarSHA256']==sha(jar)
    counts=dict(requests=0,checks=0,normalStopCount=0,sameUIDNormalH2RestartCount=0,independentStoppedH2MorphiaReadCount=0)
    raw=[]
    for case in report['cases']:
        assert case['passed']and case['nativeToolInputsStable']and case['giftChoicePaymentOrGoalSeeded']is False
        directory=Path(case['runtimeDirectory']).resolve();assert directory.is_relative_to(ROOT/'build/smoke')
        snapshots=case['snapshots'];h2=case['readOnlyH2Inspections']
        assert len(snapshots)==len(h2)==4 and [row['phase']for row in snapshots]==['grant','settled','repeat','restored']
        assert [row['dayIndex']for row in snapshots]==[1,1,2,2]and len({row['uid']for row in snapshots})==1
        assert [row['loginWallet']for row in snapshots]==[0,0,5000,10000]
        source_hashes={}
        for index,(snapshot,view)in enumerate(zip(snapshots,h2)):
            server=directory/f'server-{index}.log';probe=directory/f'probe-{index}.log';stored=directory/f'h2-{index}.json';inspector=directory/f'h2-{index}.log'
            plain=re.sub(r'\x1b\[[0-9;]*m','',server.read_text('utf-8',errors='replace'))
            assert plain.count('[INFO] Stopping server')==1 and 'Client connected from 127.0.0.1'in plain
            observed=json.loads(next(line for line in reversed(probe.read_text('utf-8').splitlines())if line.startswith('{')))
            assert observed==snapshot and snapshot['passed']
            inspected=json.loads(next(line for line in reversed(inspector.read_text('utf-8').splitlines())if line.startswith('{')))
            assert inspected==view==json.loads(stored.read_text('utf-8'))and view['passed']and view['mvStoreReadOnly']and view['databaseFileBytesUnchanged']
            assert view['uid']==snapshot['uid']and view['independentMorphiaReadCount']==2
            assert view['credit']==[0,5000,10000,10000][index]and view['activePoints']==[0,200,200,200][index]
            for file in (server,probe,stored,inspector):source_hashes[file.name]=sha(file)
        assert sha(directory/'data/database.mv')==h2[-1]['databaseSHA256']
        assert case['normalStopCount']==4 and case['sameUIDNormalH2RestartCount']==3 and case['independentStoppedH2MorphiaReadCount']==8
        assert case['requests']==sum(row['requests']for row in snapshots)and case['checks']==sum(len(row['checks'])for row in snapshots)
        proof=case['originalWholeSourceProof'];assert proof['completeOriginalFiles']==68 and proof['completeSelectedObjects']==195 and len(proof['sourceOnlyStatus'])==11
        assert proof['sourceOnlyInstalledByTool']is False and case['mirrorLayout']['directoryJunctionCount']==0
        for relative,digest in case['sourceFilesSHA256'].items():assert sha(ROOT/'resources/4.6'/relative)==digest
        for name,digest in case['nativeToolInputSHA256'].items():assert sha(Path(name))==digest
        captured=directory/'captured-tools'
        for filename in ('NativeDailyRecoverDeliveryProbe.java','RecoverDeliveryFixtureInspector.java','RecoverDeliveryFixtureCommand.java'):
            original=next(Path(name)for name in case['nativeToolInputSHA256']if Path(name).name==filename)
            assert sha(captured/filename)==sha(original)
        for name in counts:counts[name]+=case[name]
        raw.append(dict(caseIndex=case['caseIndex'],runtimeDirectory=str(directory),rawEvidenceSHA256=source_hashes))
    assert all(report[name]==value for name,value in counts.items())
    return dict(passed=True,sourceReport=str(path),sourceReportSHA256=sha(path),artifactSHA256=sha(jar),**counts,
        auditedCases=raw,serverStartedByAudit=False,clientUIVerified=False,realWallClockCrossDayVerified=False,inventoryFailureInjectedIntoRuntime=False)


def main():
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('report',type=Path);parser.add_argument('--jar',type=Path,required=True);parser.add_argument('--output',type=Path,required=True);args=parser.parse_args()
    output=args.output.resolve();assert output.is_relative_to(ROOT/'build')and not output.exists()
    value=audit(args.report,args.jar)
    with output.open('x',encoding='utf-8')as stream:json.dump(value,stream,ensure_ascii=False,indent=2)
    print(json.dumps({key:value[key]for key in ['passed','requests','checks','normalStopCount','sameUIDNormalH2RestartCount','independentStoppedH2MorphiaReadCount']}))


if __name__=='__main__':main()
