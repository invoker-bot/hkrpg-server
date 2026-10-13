"""两条有限原物品选择日常的实际1261/1205、两日与正常同UID H2；Act/UI未验。"""
import argparse
import base64
from decimal import Decimal
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import queue
import shutil
import subprocess
import sys
import tempfile
import threading
import time
import urllib.error
import zlib

ROOT=next(p for p in Path(__file__).resolve().parents if(p/'AGENTS.md').is_file()and(p/'scripts/smoke-server.py').is_file())
TOOLS=Path(__file__).resolve().parent
JAVA=TOOLS/"fixtures/java"
spec=importlib.util.spec_from_file_location('selected_item_native_support',ROOT/'scripts/smoke-g61-first-talk.py')
support=importlib.util.module_from_spec(spec);spec.loader.exec_module(support)
sha=support.sha;cleanup=support.cleanup
COMMIT='724b139d8c9c32d12552eb95745a4fee72bfe48b'
SOURCE_ONLY={
 'Config/Level/NPCDialogue/P20122/F20122001_G102/DialogueMain_F20122001_G102_N400001.json',
 'Config/Level/NPCDialogue/P20132/F20132001_G74/DialogueMain_F20132001_G74_N400001.json'}
CASES={3011605:dict(mainMission=3011605,plane=20122,floor=20122001,group=102,npc=11103,entry=2012201,firstItem=401004,secondItem=401012),
       3011608:dict(mainMission=3011608,plane=20132,floor=20132001,group=74,npc=2108,entry=2013201,firstItem=401007,secondItem=401009)}
SCHEDULE=[(1,'open'),(1,'paid'),(1,'settled'),(2,'repeat'),(2,'restored')]

def prerequisite_history(main,blobs):
    rows=json.loads(blobs['ExcelOutput/MapEntrance.json'])
    entrance=next(row for row in rows if row['ID']==CASES[main]['entry'])
    mains=list(dict.fromkeys([1010201,1010403,1011501,*entrance['FinishMainMissionList']]))
    subs=entrance['FinishSubMissionList']
    assert set(mains).isdisjoint(CASES) and set(subs).isdisjoint({mid*100+i for mid in CASES for i in(1,2)})
    return dict(main=mains,sub=subs)

def prerequisite_blobs(main,source):
    if main==3011605:return {},dict(additionalNativePrerequisiteOriginalFiles=0,sourceSHA256={})
    assert main==3011608
    source=Path(source).resolve();files=[
      'Config/Level/Mission/1011202/MissionInfo_1011202.json',
      'Config/Level/Mission/1011202/MissionInfo_1011202.partial.json',
      'Config/Level/Mission/1011202/MissionInfo_1011202.partial.layout.json',
      'Config/Level/Mission/1011201/Mission_101120201.json']
    blobs={};proof={}
    for relative in files:
        origin=source/relative;assert origin.is_file()and origin.resolve().is_relative_to(source)
        original=subprocess.run(['git','-C',str(ROOT/'references/TurnBasedGameData'),'show',COMMIT+':'+relative],check=True,capture_output=True).stdout
        assert complete_json(origin.read_bytes())==complete_json(original),'Additional complete original entrance prerequisite differs: '+relative
        blobs[relative]=origin.read_bytes();proof[relative]=dict(fixedOriginalSHA256=hashlib.sha256(original).hexdigest(),installedSHA256=sha(origin),completeObjectEqual=True)
    graph=json.loads(blobs[files[0]]);partial=json.loads(blobs[files[1]])
    assert graph['MainMissionID']==1011202 and len(partial['SubMissionList'])==1
    sub=partial['SubMissionList'][0];assert sub['ID']==101120201 and sub['MainMissionID']==1011202 and sub['MissionJsonPath']==files[3]
    return blobs,dict(additionalNativePrerequisiteOriginalFiles=4,fixedCommit=COMMIT,fullParentAndSubIdentityChecked=True,
        sourceSHA256={path:row['installedSHA256']for path,row in proof.items()},completeOriginalFileProof=proof,
        originalPrerequisiteExecutionClaimed=False,operatorFilesWritten=False)

def source_path():
    formal=ROOT/'src/test/resources/fixtures/daily-selected-item-3011605-8-4.6.json'
    return formal if formal.is_file()else TOOLS/'fixtures/daily-selected-item-3011605-8-4.6.json'

def complete_json(raw):
    number=lambda value:('json-number',Decimal(value))
    return json.loads(raw,parse_int=number,parse_float=number)

def preflight(source,fixture):
    source=Path(source).resolve()
    assert fixture['sourceCommit']==COMMIT and set(fixture['mains'])==set(CASES)
    assert len(fixture['sourceFiles'])==36 and len(fixture['records'])==131
    hashes,blobs,status={}, {},{}
    for relative,entry in fixture['sourceFiles'].items():
        assert not Path(relative).is_absolute() and '..'not in Path(relative).parts
        raw=base64.b64decode(entry['sourceBlobBase64'],validate=True)if'sourceBlobBase64'in entry else zlib.decompress(base64.b64decode(entry['sourceBlobDeflateBase64'],validate=True))
        assert hashlib.sha256(raw).hexdigest()==entry['sourceBlobSHA256'],relative
        file=source/relative
        assert file.resolve().is_relative_to(source),'Original source path escaped resource root'
        if not file.exists():
            assert relative in SOURCE_ONLY and entry['installed']is False and entry['installedSHA256']is None,'Required complete source absent: '+relative
            status[relative]='absent';continue
        assert file.is_file() and complete_json(file.read_bytes())==complete_json(raw),'Complete original differs: '+relative
        hashes[relative]=sha(file)
        if relative in SOURCE_ONLY:status[relative]='present_matching'
        else:blobs[relative]=file.read_bytes()
    assert set(status)==SOURCE_ONLY
    return blobs,hashes,dict(passed=True,fixedCommit=COMMIT,completeOriginalFiles=36,completeSelectedObjects=131,
        wholeInstalledObjectsEqual=len(hashes),sourceOnlyStatus=status,sourceOnlyInstalledByTool=False,
        sourceFilesSHA256=hashes,clientActExecuted=False,clientUIVerified=False)

def detached_write(target,raw,fixture):
    target=Path(target);fixture=Path(fixture).resolve()
    assert target.resolve().is_relative_to(fixture) and target!=fixture,'Fixture write escaped its owned root'
    target.parent.mkdir(parents=True,exist_ok=True)
    if target.exists():
        assert target.is_file() and not target.is_symlink()
        target.unlink()  # Never overwrite the content of a read-only source hardlink.
    with target.open('xb')as output:output.write(raw)

def materialize(source,fixture,blobs,main):
    source=Path(source).resolve();fixture=Path(fixture).resolve()
    assert not fixture.exists() and fixture!=source and not fixture.is_relative_to(source)
    fixture.mkdir();linked=0
    for origin in source.rglob('*'):
        if not origin.is_file():continue
        assert origin.resolve().is_relative_to(source),'Operator source escaped its root'
        relative=origin.relative_to(source).as_posix()
        if relative=='resource-manifest.json' or relative in SOURCE_ONLY or relative.startswith('Config/Level/Mission/'):continue
        target=fixture/relative;target.parent.mkdir(parents=True,exist_ok=True);os.link(origin,target);linked+=1
    for relative,raw in blobs.items():
        if relative.startswith('Config/Level/Mission/'):
            detached_write(fixture/relative,raw,fixture)
    data=json.loads(blobs['ExcelOutput/DailyMissionData.json'])
    selected=[row for row in data if row['ID']==main];assert len(selected)==1
    pool=[row for row in json.loads(blobs['ExcelOutput/DailyActiveQuestPool.json'])if row['QuestID']==2100003];assert len(pool)==1
    for relative,value in {'ExcelOutput/DailyMissionData.json':selected,'ExcelOutput/DailyActiveQuestPool.json':pool,
       'resource-manifest.json':dict(clientVersion='4.6.0',testFixture=True,selectedItemFixture=True,originalMission=main)}.items():
        detached_write(fixture/relative,json.dumps(value,ensure_ascii=False).encode('utf-8'),fixture)
    for relative in blobs:
        file=fixture/relative;assert file.is_file() and file.resolve().is_relative_to(fixture),'Source-checked runtime path escaped fixture: '+relative
    assert all(not(fixture/p).exists()for p in SOURCE_ONLY)
    return dict(actualReadOnlyHardlinkCount=linked,directoryJunctionCount=0,sourceOnlyInstalled=False,
        ordinarySourceFilesNeverWritten=True,originalSourceRowsModified=False,
        filteredOriginalDailyCatalog=[main],filteredOriginalActivePool=[2100003])

def compile_helpers(jar,java,classes):
    sources=[JAVA/path for path in ('org/hkrpg/server/protocol/NativeDailySelectedItemProbe.java', 'emu/lunarcore/command/commands/SelectedItemFixtureCommand.java', 'org/hkrpg/server/gameplay/SelectedItemFixtureInspector.java')]
    sources+=[ROOT/'scripts/fixtures/java/emu/lunarcore/command/commands/DailyTravelClockFixtureCommand.java']
    capture=classes.parent/'captured-tools';capture.mkdir()
    for source in sources:shutil.copyfile(source,capture/source.name)
    sources=[capture/source.name for source in sources]
    classes.mkdir();subprocess.run([str(java.with_name('javac.exe')),'-encoding','UTF-8','-cp',str(jar),'-d',str(classes),*map(str,sources)],check=True,capture_output=True,timeout=120)
    classpath=str(jar)+os.pathsep+str(classes)
    result=subprocess.run([str(java),'-Dfile.encoding=UTF-8','-cp',classpath,'org.hkrpg.server.protocol.NativeDailySelectedItemProbe','--wire-fields'],check=True,capture_output=True,text=True,encoding='utf-8',timeout=30)
    wire=json.loads(result.stdout)
    assert wire['FinishCosumeItemMissionCsReq']['command']==1261 and wire['FinishCosumeItemMissionScRsp']['command']==1208 and wire['FinishTalkMissionCsReq']['command']==1205
    assert wire['FinishCosumeItemMissionCsReq']['fields']['sub_mission_id']['tags']==[2]
    assert wire['FinishCosumeItemMissionCsReq']['fields']['item_list/item_list/item_id']['tags']==[4,2,15]
    assert wire['FinishCosumeItemMissionCsReq']['fields']['item_list/item_list/num']['tags']==[4,2,6]
    return classpath,wire

def one(main,jar,artifact,java,attempted):
    assert sha(jar)==artifact;source=ROOT/'resources/4.6'
    blobs,hashes,proof=preflight(source,json.loads(source_path().read_text('utf-8')))
    prerequisites=prerequisite_history(main,blobs)
    extra,extra_proof=prerequisite_blobs(main,source);runtime_blobs=dict(blobs,**extra)
    runs=(ROOT/'build/smoke').resolve();runs.mkdir(parents=True,exist_ok=True)
    directory=Path(tempfile.mkdtemp(prefix=f'daily-selected-item-native-{main}-',dir=runs)).resolve();assert directory.is_relative_to(runs)
    attempted.append(str(directory));print(json.dumps(dict(stage='owned-runtime-prepared',mainMission=main,runtimeDirectory=str(directory))),flush=True)
    tool_inputs={str(path):sha(path)for path in [TOOLS/'smoke-daily-selected-item.py',JAVA/'org/hkrpg/server/protocol/NativeDailySelectedItemProbe.java',JAVA/'emu/lunarcore/command/commands/SelectedItemFixtureCommand.java',JAVA/'org/hkrpg/server/gameplay/SelectedItemFixtureInspector.java',
        ROOT/'scripts/fixtures/java/emu/lunarcore/command/commands/DailyTravelClockFixtureCommand.java',ROOT/'scripts/smoke-g61-first-talk.py',ROOT/'scripts/smoke-daily-message.py',source_path()]}
    (directory/'native-tool-inputs-before.json').write_text(json.dumps(tool_inputs,indent=2)+'\n','utf-8')
    shutil.copyfile(TOOLS/'smoke-daily-selected-item.py',directory/'wrapper-executed-snapshot.py')
    mirror=materialize(source,directory/'resources-fixture',runtime_blobs,main)
    (directory/'original-source-preflight.json').write_text(json.dumps(proof,indent=2)+'\n','utf-8')
    (directory/'additional-native-prerequisite-source-proof.json').write_text(json.dumps(extra_proof,indent=2)+'\n','utf-8')
    http,mongo,game=support.transport_ports();assert http!=mongo
    config=directory/'config.json';config.write_text(json.dumps(dict(protocol=dict(wireVersion='4.6.0'),resourceDir=str(directory/'resources-fixture'),
        httpServer=dict(bindAddress='127.0.0.1',bindPort=http),gameServer=dict(bindAddress='127.0.0.1',bindPort=game),
        internalMongoServer=dict(address='127.0.0.1',port=mongo,filePath='./data/database.mv'))),'utf-8');config_sha=sha(config)
    classpath,wire=compile_helpers(jar,java,directory/'fixture-classes');(directory/'wire-fields.json').write_text(json.dumps(wire,indent=2),'utf-8')
    flags=subprocess.CREATE_NO_WINDOW if os.name=='nt'else 0
    java_flags=['-Dfile.encoding=UTF-8','-Dstdout.encoding=UTF-8','-Dstderr.encoding=UTF-8','-Djdk.net.unixdomain.tmpdir='+str(directory/'tcp-fallback'),'-Dio.netty.eventLoopThreads=2']
    command=[str(java),*java_flags,'-Xmx1536m','-cp',classpath,'org.hkrpg.server.HkrpgServer','--no-handbook']
    credentials=None;anchor=None;snapshots=[];views=[];clock_views=[];h2=[];stops=0
    for iteration,(day,phase)in enumerate(SCHEDULE):
        assert sha(jar)==artifact and sha(config)==config_sha
        log_path=directory/f'server-{iteration}.log'
        with log_path.open('x',encoding='utf-8')as log:
            server=subprocess.Popen(command,cwd=directory,stdin=subprocess.PIPE,stdout=log,stderr=subprocess.STDOUT,creationflags=flags)
            primary=None;ready=False
            try:
                deadline=time.monotonic()+100
                while not ready:
                    assert server.poll()is None,directory
                    try:ready=support.local_request(http,'/health/ready')['ready']is True
                    except(OSError,urllib.error.URLError):pass
                    if time.monotonic()>deadline:raise TimeoutError('Selected-item readiness timed out: '+str(directory))
                    if not ready:time.sleep(.2)
                if iteration==0:
                    login=support.local_request(http,'/hkrpg_cn/mdk/shield/api/login',dict(account='cn-46-selected-'+directory.name,password='local-test'))
                    assert login['retcode']==0;account=login['data']['account']
                    combo=support.local_request(http,'/hkrpg_cn/combo/granter/login/v2/login',{'data':json.dumps({'uid':account['uid'],'token':account['token']})})
                    assert combo['retcode']==0;credentials=dict(accountUid=account['uid'],comboToken=combo['data']['combo_token'])
                child=subprocess.Popen([str(java),*java_flags,'-cp',classpath,'org.hkrpg.server.protocol.NativeDailySelectedItemProbe',str(game)],stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,text=True,encoding='utf-8',creationflags=flags)
                lines=[];available=queue.Queue()
                def read_output():
                    for line in child.stdout:lines.append(line);available.put(line)
                    available.put(None)
                reader=threading.Thread(target=read_output,daemon=True);reader.start();probe_primary=None
                try:
                    child.stdin.write(json.dumps(dict(credentials,**CASES[main],dayIndex=day,phase=phase))+'\n');child.stdin.flush();deadline=time.monotonic()+150
                    while True:
                        line=available.get(timeout=max(.1,deadline-time.monotonic()));assert line is not None,directory
                        if line.startswith('HKRPG_SELECTED_ITEM_FIXTURE_READY:'):uid=int(line.strip().rsplit(':',1)[1]);break
                    if anchor is None:
                        anchor=int(time.time())+1;assert(anchor+14400)%86400<86400-1200,'Controlled fixture too close to wall-clock reset'
                    target=support.projected_clock(anchor,day)
                    mode='initial'if phase=='open'else'stock'if phase=='repeat'else'inspect'
                    item=CASES[main]['firstItem']if day==1 else CASES[main]['secondItem']
                    server.stdin.write(f'selecteditemfixture {uid} {main} {item} {target} {mode} {iteration}\n'.encode('utf-8'));server.stdin.flush()
                    marker=f'HKRPG_SELECTED_ITEM_FIXTURE_ACK:{uid}:{mode}:{iteration}'
                    while marker not in log_path.read_text('utf-8',errors='replace'):
                        assert server.poll()is None and child.poll()is None,directory
                        if time.monotonic()>deadline:raise TimeoutError('Selected-item console fixture timed out: '+str(directory))
                        time.sleep(.05)
                    rows=[line.split('HKRPG_SELECTED_ITEM_FIXTURE_VIEW:',1)[1]for line in log_path.read_text('utf-8',errors='replace').splitlines()if'HKRPG_SELECTED_ITEM_FIXTURE_VIEW:'in line]
                    view=json.loads(rows[-1]);assert view['uid']==uid and view['nonce']==iteration and view['mode']==mode and view['targetOrPaymentSeeded']is False
                    assert view['seededPrerequisiteHistory']==(prerequisites['main']if phase=='open'else[])
                    assert view['seededSubPrerequisiteHistory']==(prerequisites['sub']if phase=='open'else[])
                    views.append(view)
                    server.stdin.write(f'dailytravelclockfixture {uid} {target} {iteration}\n'.encode('utf-8'));server.stdin.flush()
                    marker=f'HKRPG_DAILY_TRAVEL_CLOCK_ACK:{uid}:{target}:{iteration}'
                    while marker not in log_path.read_text('utf-8',errors='replace'):
                        assert server.poll()is None and child.poll()is None,directory
                        if time.monotonic()>deadline:raise TimeoutError('Selected-item controlled clock timed out: '+str(directory))
                        time.sleep(.05)
                    rows=[line.split('HKRPG_DAILY_TRAVEL_CLOCK_VIEW:',1)[1]for line in log_path.read_text('utf-8',errors='replace').splitlines()if'HKRPG_DAILY_TRAVEL_CLOCK_VIEW:'in line]
                    clock=json.loads(rows[-1]);assert clock['uid']==uid and clock['clock']==target and clock['nonce']==iteration;clock_views.append(clock)
                    child.stdin.write('resume\n');child.stdin.flush();child.stdin.close();child.wait(timeout=max(.1,deadline-time.monotonic()));reader.join(timeout=3)
                    assert child.returncode==0,directory
                    snapshot=json.loads(next(line for line in reversed(lines)if line.startswith('{')))
                    assert snapshot['passed']and snapshot['mainMission']==main and snapshot['dayIndex']==day and snapshot['phase']==phase
                    snapshot['clockFixtureEpochSecond']=target;snapshots.append(snapshot)
                except BaseException as error:probe_primary=error;raise
                finally:
                    try:cleanup(child,probe_primary)
                    finally:
                        reader.join(timeout=3)
                        try:(directory/f'probe-{iteration}.log').write_text(''.join(lines),'utf-8')
                        except BaseException as error:
                            if probe_primary is None:raise
                            probe_primary.add_note('Probe log preservation failed: '+repr(error))
                time.sleep(1)
            except BaseException as error:primary=error;raise
            finally:
                if cleanup(server,primary,normal_stop=ready):stops+=1
        assert sha(jar)==artifact and sha(config)==config_sha
        control=dict(uid=snapshots[-1]['uid'],**CASES[main],dayIndex=day,phase=phase,firstDay=(anchor+14400)//86400)
        path=directory/f'h2-{iteration}-control.json';path.write_text(json.dumps(control),'utf-8')
        observed=subprocess.run([str(java),*java_flags,'-cp',classpath,'org.hkrpg.server.gameplay.SelectedItemFixtureInspector',str(directory),str(path)],cwd=ROOT,capture_output=True,text=True,encoding='utf-8',timeout=60,creationflags=flags)
        (directory/f'h2-{iteration}.log').write_text(observed.stdout+observed.stderr,'utf-8');assert observed.returncode==0,directory
        data=json.loads(next(line for line in reversed(observed.stdout.splitlines())if line.startswith('{')))
        assert data['passed']and data['databaseFileBytesUnchanged']and data['mvStoreReadOnly'];h2.append(data)
        (directory/f'h2-{iteration}.json').write_text(json.dumps(data,indent=2),'utf-8')
        print(json.dumps(dict(stage='normal-stop-read-only-H2-verified',mainMission=main,phase=phase,normalStops=stops,runtimeDirectory=str(directory))),flush=True)
    assert len(snapshots)==len(h2)==stops==5 and len({s['uid']for s in snapshots})==1
    assert hashes=={relative:sha(source/relative)for relative in hashes}and sha(jar)==artifact
    assert extra_proof['sourceSHA256']=={relative:sha(source/relative)for relative in extra_proof['sourceSHA256']}
    assert all(sha(Path(path))==digest for path,digest in tool_inputs.items()),'Scoped native tool input changed'
    return dict(passed=True,mainMission=main,scenario='settled',executedChoiceCaseIndex=0,actualD0Item=CASES[main]['firstItem'],actualD1Item=CASES[main]['secondItem'],
        jarSHA256=artifact,runtimeDirectory=str(directory),snapshots=snapshots,requests=sum(s['requests']for s in snapshots),checks=sum(len(s['checks'])for s in snapshots),
        normalStopCount=stops,sameUIDNormalH2RestartCount=stops-1,independentStoppedH2MorphiaReadCount=sum(v['independentMorphiaReadCount']for v in h2),
        readOnlyH2Inspections=h2,prerequisiteAndStockFixtureViews=views,clockViews=clock_views,wireFields=wire,originalWholeSourceProof=proof,additionalNativePrerequisiteSourceProof=extra_proof,mirrorLayout=mirror,
        nativeToolInputSHA256=tool_inputs,nativeToolInputsStable=True,capturedHelperSources={str(path):sha(path)for path in (directory/'captured-tools').glob('*.java')},
        sourceResourcesModified=False,controlledTwoDaysVerified=True,originalCreditPerCompletedPeriod=5000,originalActivePointsPerCompletedPeriod=200,actualCompletedPeriods=2,
        explicitPrerequisiteHistory=prerequisites['main'],explicitEntranceSubPrerequisiteHistory=prerequisites['sub'],explicitInitialAndNewDayStock=True,paymentOrGoalSeeded=False,
        clientUIVerified=False,clientActOrCustomStringExecuted=False,realWallClockCrossDayVerified=False,inventoryCrashAtomicityVerified=False)

def main():
    if hasattr(sys.stdout,'reconfigure'):sys.stdout.reconfigure(encoding='utf-8')
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--jar',type=Path,required=True);parser.add_argument('--output',type=Path,required=True);parser.add_argument('--main-mission',type=int,choices=CASES,action='append');parser.add_argument('--preflight-only',action='store_true');args=parser.parse_args()
    jar=args.jar.resolve();output=args.output.resolve();build=(ROOT/'build').resolve()
    assert jar.is_file()and jar.is_relative_to(build)and output.is_relative_to(build)
    chosen=args.main_mission or list(CASES);assert chosen and len(chosen)==len(set(chosen))
    java=next((ROOT/'.tools').glob('jdk-21*/bin/java.exe'));artifact=sha(jar);support.reserve_output(jar,output)
    cases=[];attempted=[]
    try:
        if args.preflight_only:
            blobs,hashes,proof=preflight(ROOT/'resources/4.6',json.loads(source_path().read_text('utf-8')))
            directory=TOOLS/('preflight-'+str(time.time_ns()));directory.mkdir();classpath,wire=compile_helpers(jar,java,directory/'classes')
            result=dict(passed=True,preflightOnly=True,nativeExecuted=False,jarSHA256=artifact,source=proof,wireFields=wire,selectedMainMissions=chosen)
        else:
            for mid in chosen:cases.append(one(mid,jar,artifact,java,attempted))
            result=dict(passed=True,jarSHA256=artifact,cases=cases,executedMainMissions=[c['mainMission']for c in cases],actualCompletedCaseCount=len(cases),
                requests=sum(c['requests']for c in cases),checks=sum(c['checks']for c in cases),snapshots=sum(len(c['snapshots'])for c in cases),
                normalStopCount=sum(c['normalStopCount']for c in cases),sameUIDNormalH2RestartCount=sum(c['sameUIDNormalH2RestartCount']for c in cases),
                independentStoppedH2MorphiaReadCount=sum(c['independentStoppedH2MorphiaReadCount']for c in cases),sourceResourcesModified=False,
                clientUIVerified=False,clientActOrCustomStringExecuted=False,realWallClockCrossDayVerified=False,inventoryCrashAtomicityVerified=False)
        assert sha(jar)==artifact
        with output.open('x',encoding='utf-8')as report:report.write(json.dumps(result,ensure_ascii=False,indent=2)+'\n')
    except BaseException as error:
        try:
            with output.with_name(output.name+'.failed.json').open('x',encoding='utf-8')as report:
                json.dump(dict(passed=False,jarSHA256=artifact,failure=repr(error),completedCases=cases,attemptedRuntimeDirectories=attempted,selectedMainMissions=chosen),report,ensure_ascii=False,indent=2)
        except BaseException as secondary:error.add_note('First-failure report preservation failed: '+repr(secondary))
        raise
    print(json.dumps({key:result[key]for key in ('passed','jarSHA256','requests','checks','normalStopCount','independentStoppedH2MorphiaReadCount')if key in result},ensure_ascii=False))

if __name__=='__main__':main()
