"""3011014三条原始支路实际发物/交物、受控两日及正常同UID H2；原Act/临时NPC/UI未验。"""
import argparse
import base64
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import queue
import shutil
import subprocess
import tempfile
import threading
import time
import urllib.error
import zlib

ROOT=Path(__file__).resolve().parents[1]
TOOLS=ROOT/'scripts'
JAVA=TOOLS/'fixtures/java'
FIXTURE=ROOT/'src/test/resources/fixtures/daily-recover-delivery-3011014-4.6.json'
COMMIT='724b139d8c9c32d12552eb95745a4fee72bfe48b'
spec=importlib.util.spec_from_file_location('recovery_selected_support',TOOLS/'smoke-daily-selected-item.py')
shared=importlib.util.module_from_spec(spec);spec.loader.exec_module(shared)
support=shared.support
sha=shared.sha
SCHEDULE=[(1,'grant'),(1,'settled'),(2,'repeat'),(2,'restored')]


def finish_probe(process,primary,log,lines):
    log.write_text(''.join(lines),'utf-8')
    shared.cleanup(process,primary)


def preflight(source,fixture):
    source=Path(source).resolve()
    assert fixture['sourceCommit']==COMMIT and len(fixture['sourceFiles'])==68 and len(fixture['records'])==195
    optional={name for name,row in fixture['sourceFiles'].items()if row['installed']is False}
    assert len(optional)==11 and all(name.startswith('Config/Level/NPCDialogue/')for name in optional)
    blobs,hashes,status={},{},{}
    for relative,row in fixture['sourceFiles'].items():
        assert not Path(relative).is_absolute()and '..'not in Path(relative).parts
        raw=base64.b64decode(row['sourceBlobBase64'],validate=True)if'sourceBlobBase64'in row else zlib.decompress(base64.b64decode(row['sourceBlobDeflateBase64'],validate=True))
        assert hashlib.sha256(raw).hexdigest()==row['sourceBlobSHA256'],relative
        path=source/relative;assert path.resolve().is_relative_to(source),'Original source escaped resource root'
        if not path.exists():
            assert relative in optional and row['installedSHA256']is None,'Required complete source absent: '+relative
            status[relative]='absent';continue
        assert path.is_file()and shared.complete_json(path.read_bytes())==shared.complete_json(raw),'Complete original differs: '+relative
        hashes[relative]=sha(path)
        if relative in optional:status[relative]='present_matching'
        else:blobs[relative]=path.read_bytes()
    assert set(status)==optional
    return blobs,hashes,optional,dict(passed=True,fixedCommit=COMMIT,completeOriginalFiles=68,completeSelectedObjects=195,
        wholeInstalledObjectsEqual=len(hashes),sourceOnlyStatus=status,sourceOnlyInstalledByTool=False,sourceFilesSHA256=hashes)


def prerequisite_sources(source,blobs):
    entries=json.loads(blobs['ExcelOutput/MapEntrance.json']);mains={1010201,1010403,1011501,1010501,1010902,2010501};subs=set()
    for entry in (1010201,2012101,2012301,1010203):
        row=next(r for r in entries if r['ID']==entry);mains.update(row.get('FinishMainMissionList',[]));subs.update(row.get('FinishSubMissionList',[]))
    assert 3011014 not in mains and all(not 301101401<=sid<=301101412 for sid in subs)
    extra={};pending=[]
    for main in sorted(mains):
        for suffix in ('.json','.partial.json','.partial.layout.json'):
            relative=f'Config/Level/Mission/{main}/MissionInfo_{main}{suffix}'
            if(source/relative).is_file():pending.append(relative)
    while pending:
        relative=pending.pop()
        if relative in extra:continue
        path=source/relative;assert path.resolve().is_relative_to(source)and path.is_file(),relative
        original=subprocess.run(['git','-C',str(ROOT/'references/TurnBasedGameData'),'show',COMMIT+':'+relative],check=True,capture_output=True).stdout
        assert shared.complete_json(path.read_bytes())==shared.complete_json(original),'Additional whole prerequisite differs: '+relative
        extra[relative]=path.read_bytes()
        graph=json.loads(extra[relative])
        for goal in graph.get('SubMissionList',[]):
            task=goal.get('MissionJsonPath')
            if task and task not in extra:pending.append(task)
    return extra,dict(main=sorted(mains),sub=sorted(subs),additionalOriginalSourceSHA256={n:hashlib.sha256(b).hexdigest()for n,b in extra.items()},prerequisiteExecutionClaimed=False)


def materialize(source,fixture,blobs,optional):
    source=Path(source).resolve();fixture=Path(fixture).resolve()
    assert not fixture.exists()and fixture!=source and not fixture.is_relative_to(source)
    fixture.mkdir();linked=0
    for origin in source.rglob('*'):
        if not origin.is_file():continue
        assert origin.resolve().is_relative_to(source)
        relative=origin.relative_to(source).as_posix()
        if relative=='resource-manifest.json'or relative in optional or relative.startswith('Config/Level/Mission/'):continue
        target=fixture/relative;target.parent.mkdir(parents=True,exist_ok=True);os.link(origin,target);linked+=1
    for relative,raw in blobs.items():
        if relative.startswith('Config/Level/Mission/'):shared.detached_write(fixture/relative,raw,fixture)
    for name,key,value in [('DailyMissionData','ID',3011014),('DailyActiveQuestPool','QuestID',2100003)]:
        rows=[r for r in json.loads(blobs[f'ExcelOutput/{name}.json'])if r[key]==value];assert len(rows)==1
        shared.detached_write(fixture/f'ExcelOutput/{name}.json',json.dumps(rows,ensure_ascii=False).encode('utf-8'),fixture)
    shared.detached_write(fixture/'resource-manifest.json',json.dumps(dict(clientVersion='4.6.0',testFixture=True,recoverDeliveryFixture=True,originalMission=3011014)).encode(),fixture)
    assert all(not(fixture/name).exists()for name in optional)
    return dict(actualReadOnlyHardlinkCount=linked,directoryJunctionCount=0,sourceOnlyInstalled=False,ordinaryOperatorFilesWritten=False)


def compile_helpers(jar,java,directory):
    names=['org/hkrpg/server/protocol/NativeDailyRecoverDeliveryProbe.java','org/hkrpg/server/gameplay/RecoverDeliveryFixtureInspector.java','emu/lunarcore/command/commands/RecoverDeliveryFixtureCommand.java']
    capture=directory/'captured-tools';capture.mkdir();sources=[]
    for name in names:
        target=capture/Path(name).name;shutil.copyfile(JAVA/name,target);sources.append(target)
    classes=directory/'fixture-classes';classes.mkdir()
    compiled=subprocess.run([str(java.with_name('javac.exe')),'-encoding','UTF-8','-cp',str(jar),'-d',str(classes),*map(str,sources)],capture_output=True,timeout=120)
    (directory/'javac.log').write_bytes(compiled.stdout+compiled.stderr);assert compiled.returncode==0,directory/'javac.log'
    allowed=('NativeDailyRecoverDeliveryProbe','RecoverDeliveryFixtureInspector','RecoverDeliveryFixtureCommand')
    assert all(any(p.name.startswith(prefix)for prefix in allowed)for p in classes.rglob('*.class'))
    classpath=str(jar)+os.pathsep+str(classes)
    result=subprocess.run([str(java),'-cp',classpath,'org.hkrpg.server.protocol.NativeDailyRecoverDeliveryProbe','--wire-fields'],check=True,capture_output=True,text=True,encoding='utf-8',timeout=30)
    wire=json.loads(result.stdout)
    assert wire['FinishTalkMissionCsReq']['command']==1205 and wire['FinishCosumeItemMissionCsReq']['command']==1261
    assert wire['FinishTalkMissionCsReq']['fields']['sub_mission_id']['tags']==[6]
    assert wire['FinishCosumeItemMissionCsReq']['fields']['item_list/item_list/item_id']['tags']==[4,2,15]
    assert wire['FinishCosumeItemMissionCsReq']['fields']['item_list/item_list/num']['tags']==[4,2,6]
    (directory/'wire-fields.json').write_text(json.dumps(wire,indent=2),'utf-8')
    return classpath


def one(index,jar,artifact,java,source,blobs,hashes,optional,proof,prerequisites,attempted):
    runs=ROOT/'build/smoke';runs.mkdir(exist_ok=True)
    directory=Path(tempfile.mkdtemp(prefix=f'daily-recover-delivery-native-{index}-',dir=runs)).resolve();attempted.append(str(directory))
    print(json.dumps(dict(stage='owned-runtime-prepared',caseIndex=index,runtimeDirectory=str(directory))),flush=True)
    input_paths=[Path(__file__),FIXTURE,TOOLS/'smoke-daily-selected-item.py',TOOLS/'smoke-g61-first-talk.py',
        JAVA/'org/hkrpg/server/protocol/NativeDailyRecoverDeliveryProbe.java',JAVA/'org/hkrpg/server/gameplay/RecoverDeliveryFixtureInspector.java',
        JAVA/'emu/lunarcore/command/commands/RecoverDeliveryFixtureCommand.java']
    input_hashes={str(path):sha(path)for path in input_paths}
    (directory/'native-tool-inputs-before.json').write_text(json.dumps(input_hashes,indent=2),'utf-8')
    shutil.copyfile(Path(__file__),directory/'wrapper-executed.py')
    mirror=materialize(source,directory/'resources-fixture',blobs,optional);classpath=compile_helpers(jar,java,directory)
    (directory/'original-source-preflight.json').write_text(json.dumps(proof,indent=2),'utf-8')
    http,mongo,game=support.transport_ports();assert http!=mongo
    config=directory/'config.json';config.write_text(json.dumps(dict(protocol=dict(wireVersion='4.6.0'),resourceDir=str(directory/'resources-fixture'),
        httpServer=dict(bindAddress='127.0.0.1',bindPort=http),gameServer=dict(bindAddress='127.0.0.1',bindPort=game),internalMongoServer=dict(address='127.0.0.1',port=mongo,filePath='./data/database.mv'))),'utf-8');config_sha=sha(config)
    flags=subprocess.CREATE_NO_WINDOW if os.name=='nt'else 0
    java_flags=['-Dfile.encoding=UTF-8','-Dstdout.encoding=UTF-8','-Dstderr.encoding=UTF-8','-Djdk.net.unixdomain.tmpdir='+str(directory/'tcp-fallback'),'-Dio.netty.eventLoopThreads=2']
    command=[str(java),*java_flags,'-Xmx1536m','-cp',classpath,'org.hkrpg.server.HkrpgServer','--no-handbook']
    credentials=None;anchor=None;snapshots=[];views=[];stops=0
    for iteration,(day,phase)in enumerate(SCHEDULE):
        assert sha(jar)==artifact and sha(config)==config_sha
        log_path=directory/f'server-{iteration}.log'
        with log_path.open('x',encoding='utf-8')as log:
            server=subprocess.Popen(command,cwd=directory,stdin=subprocess.PIPE,stdout=log,stderr=subprocess.STDOUT,creationflags=flags)
            primary=None
            try:
                deadline=time.monotonic()+120
                while True:
                    assert server.poll()is None,directory
                    try:
                        if support.local_request(http,'/health/ready')['ready']:break
                    except(OSError,urllib.error.URLError):pass
                    if time.monotonic()>deadline:raise TimeoutError('Readiness timeout: '+str(directory))
                    time.sleep(.2)
                if credentials is None:
                    login=support.local_request(http,'/hkrpg_cn/mdk/shield/api/login',dict(account='cn-46-recovery-'+directory.name,password='local-test'));assert login['retcode']==0
                    account=login['data']['account'];combo=support.local_request(http,'/hkrpg_cn/combo/granter/login/v2/login',dict(data=json.dumps(dict(uid=account['uid'],token=account['token']))));assert combo['retcode']==0
                    credentials=dict(accountUid=account['uid'],comboToken=combo['data']['combo_token'])
                child=subprocess.Popen([str(java),*java_flags,'-cp',classpath,'org.hkrpg.server.protocol.NativeDailyRecoverDeliveryProbe',str(game)],stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,text=True,encoding='utf-8',creationflags=flags)
                lines=[];available=queue.Queue()
                def consume_output():
                    for line in child.stdout:lines.append(line);available.put(line)
                    available.put(None)
                reader=threading.Thread(target=consume_output,daemon=True);reader.start();child_error=None
                try:
                    child.stdin.write(json.dumps(dict(credentials,caseIndex=index,dayIndex=day,phase=phase))+'\n');child.stdin.flush();deadline=time.monotonic()+240
                    while True:
                        line=available.get(timeout=max(.1,deadline-time.monotonic()));assert line is not None,directory
                        if line.startswith('HKRPG_RECOVER_DELIVERY_FIXTURE_READY:'):uid=int(line.strip().rsplit(':',1)[1]);break
                    if anchor is None:anchor=int(time.time())+1
                    target=support.projected_clock(anchor,day);mode='initial'if iteration==0 else'inspect'
                    server.stdin.write(f'recoverdeliveryfixture {uid} {target} {mode} {iteration}\n'.encode());server.stdin.flush()
                    marker=f'HKRPG_RECOVER_DELIVERY_FIXTURE_ACK:{uid}:{mode}:{iteration}'
                    while marker not in log_path.read_text('utf-8',errors='replace'):
                        assert server.poll()is None and child.poll()is None,directory
                        if time.monotonic()>deadline:raise TimeoutError('Console fixture timeout: '+str(directory))
                        time.sleep(.1)
                    view=json.loads([line.split('HKRPG_RECOVER_DELIVERY_FIXTURE_VIEW:',1)[1]for line in log_path.read_text('utf-8').splitlines()if'HKRPG_RECOVER_DELIVERY_FIXTURE_VIEW:'in line][-1])
                    assert view['uid']==uid and view['giftChoicePaymentOrGoalSeeded']is False
                    assert view['prerequisiteMainHistory']==(prerequisites['main']if iteration==0 else[])and view['prerequisiteSubHistory']==(prerequisites['sub']if iteration==0 else[])
                    child.stdin.write('resume\n');child.stdin.flush();child.stdin.close();child.stdin=None;child.wait(timeout=240);reader.join(timeout=5)
                    assert child.returncode==0,directory/f'probe-{iteration}.log'
                except BaseException as failure:
                    child_error=failure;raise
                finally:
                    finish_probe(child,child_error,directory/f'probe-{iteration}.log',lines)
                row=json.loads(next(line for line in reversed(lines)if line.startswith('{')));assert row['passed']and row['uid']==uid
                if snapshots:assert snapshots[0]['uid']==uid
                snapshots.append(row)
            except BaseException as failure:primary=failure;raise
            finally:
                if server.poll()is None:
                    try:
                        server.stdin.write(b'stop\n');server.stdin.flush();server.wait(timeout=60)
                        assert server.returncode==(1000 if os.name=='nt'else 232),directory;stops+=1
                    except BaseException:
                        shared.cleanup(server,primary)
                        if primary is None:raise
                    finally:
                        if server.stdin:server.stdin.close()
        control=directory/f'phase-{iteration}.json';control.write_text(json.dumps(dict(uid=uid,caseIndex=index,dayIndex=day,phase=phase,firstDay=(anchor+14400)//86400)),'utf-8')
        observed=subprocess.run([str(java),*java_flags,'-cp',classpath,'org.hkrpg.server.gameplay.RecoverDeliveryFixtureInspector',str(directory),str(control)],cwd=ROOT,capture_output=True,text=True,encoding='utf-8',timeout=90,creationflags=flags)
        (directory/f'h2-{iteration}.log').write_text(observed.stdout+observed.stderr,'utf-8');assert observed.returncode==0,directory/f'h2-{iteration}.log'
        view=json.loads(next(line for line in reversed(observed.stdout.splitlines())if line.startswith('{')));assert view['passed']and view['databaseFileBytesUnchanged']
        views.append(view);(directory/f'h2-{iteration}.json').write_text(json.dumps(view,indent=2),'utf-8')
        (directory/'completed-phases.json').write_text(json.dumps(dict(snapshots=snapshots,readOnlyH2Inspections=views),indent=2),'utf-8')
        print(json.dumps(dict(caseIndex=index,dayIndex=day,phase=phase,requests=row['requests'],checks=len(row['checks']),normalStops=stops)),flush=True)
    assert stops==4 and all(sha(source/name)==digest for name,digest in hashes.items())and sha(jar)==artifact
    assert all(sha(Path(name))==digest for name,digest in input_hashes.items())
    return dict(passed=True,caseIndex=index,runtimeDirectory=str(directory),snapshots=snapshots,readOnlyH2Inspections=views,requests=sum(row['requests']for row in snapshots),
        checks=sum(len(row['checks'])for row in snapshots),normalStopCount=stops,sameUIDNormalH2RestartCount=3,independentStoppedH2MorphiaReadCount=8,
        originalWholeSourceProof=proof,mirrorLayout=mirror,prerequisites=prerequisites,nativeToolInputSHA256=input_hashes,nativeToolInputsStable=True,
        sourceFilesSHA256=hashes,giftChoicePaymentOrGoalSeeded=False,clientUIVerified=False,clientActOrCustomStringExecuted=False)


def main():
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--jar',type=Path,required=True);parser.add_argument('--output',type=Path,required=True);parser.add_argument('--case-index',type=int,choices=range(3),action='append');args=parser.parse_args()
    jar=args.jar.resolve();output=args.output.resolve();assert jar.is_file()and jar.is_relative_to(ROOT/'build')and output.is_relative_to(ROOT/'build')and not output.exists()
    java=next((ROOT/'.tools').glob('jdk-21*/bin/java.exe'));artifact=sha(jar);source=ROOT/'resources/4.6';attempted=[];cases=[]
    support.reserve_output(jar,output)
    result=dict(passed=False,jarSHA256=artifact,cases=cases,attemptedRuntimeDirectories=attempted,clientUIVerified=False,realWallClockCrossDayVerified=False)
    try:
        blobs,hashes,optional,proof=preflight(source,json.loads(FIXTURE.read_text('utf-8')));extra,prerequisites=prerequisite_sources(source,blobs);blobs.update(extra);hashes.update(prerequisites['additionalOriginalSourceSHA256'])
        for index in args.case_index or range(3):cases.append(one(index,jar,artifact,java,source,blobs,hashes,optional,proof,prerequisites,attempted))
        result.update(passed=True,executedCaseIndexes=[case['caseIndex']for case in cases],requests=sum(case['requests']for case in cases),checks=sum(case['checks']for case in cases),
            normalStopCount=sum(case['normalStopCount']for case in cases),sameUIDNormalH2RestartCount=sum(case['sameUIDNormalH2RestartCount']for case in cases),
            independentStoppedH2MorphiaReadCount=sum(case['independentStoppedH2MorphiaReadCount']for case in cases))
    finally:output.write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n','utf-8')
    print(json.dumps({key:result[key]for key in ('passed','executedCaseIndexes','requests','checks','normalStopCount','sameUIDNormalH2RestartCount','independentStoppedH2MorphiaReadCount')}),flush=True)


if __name__=='__main__':main()
