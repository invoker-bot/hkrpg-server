"""原始可选对话汇总与单对象监视、重连与正常H2重启；接取前置由隔离离线夹具设置。"""
from pathlib import Path
import hashlib,json,os,socket,subprocess,tempfile,time,urllib.error,urllib.request

from smoke_resource_fixture import mirror_resources

ROOT=Path(__file__).resolve().parents[1]

def free_port(kind=socket.SOCK_STREAM):
    with socket.socket(type=kind) as sock:sock.bind(('127.0.0.1',0));return sock.getsockname()[1]

def main():
    source=ROOT/'resources/4.6';artifact=ROOT/'build/libs/hkrpg-server.jar'
    java=next((ROOT/'.tools').glob('jdk-21*/bin/java.exe'),None);assert java and artifact.is_file()
    classes=ROOT/'build/classes/java/test';assert (classes/'org/hkrpg/server/gameplay/MissionRelationsFixtureInspector.class').is_file()
    digest=hashlib.sha256(artifact.read_bytes()).hexdigest();runs=ROOT/'build/smoke';runs.mkdir(exist_ok=True)
    directory=Path(tempfile.mkdtemp(prefix='submission-monitor-',dir=runs));assert directory.resolve().is_relative_to(runs.resolve())
    fixture=directory/'resources-fixture';excel=fixture/'ExcelOutput';excel.mkdir(parents=True)
    main=1054502;main_path='Config/Level/Mission/1054502/MissionInfo_1054502.json';partial_path=main_path.replace('.json','.partial.json')
    guarded=['resource-manifest.json','ExcelOutput/MainMission.json','ExcelOutput/MapEntrance.json',main_path,partial_path,'Config/LevelOutput/RuntimeFloor/P10551_F10551001.json']
    hashes={name:hashlib.sha256((source/name).read_bytes()).hexdigest() for name in guarded}
    assert hashes['resource-manifest.json']=='ecccb21469df76282e6df5bf0cbe1eff7d9f904c2dd644092124b6904482e735','Original manifest differs from preserved checksum'
    full=json.loads((source/main_path).read_text('utf-8'));assert full['SubMissionRelationList']==[{'MonitorSubMissionID':105450235,'MonitoredSubMissionIDList':[105450232]}]
    partial=json.loads((source/partial_path).read_text('utf-8'));ids={105450227,105450230,105450231,105450232,105450234,105450235,105450212}
    selected=[row for row in partial['SubMissionList'] if row['ID'] in ids];assert len(selected)==7
    rows=json.loads((source/'ExcelOutput/MainMission.json').read_text('utf-8'));target=next(row for row in rows if row['MainMissionID']==main)
    target.update(TakeOperation='And',TakeParam=[{'Type':'Manual'}],BeginOperation='And',BeginParam=[{'Type':'Auto'}])
    replacements={'ExcelOutput/MainMission.json':rows}
    junctions=mirror_resources(source,fixture,replacements);flags=subprocess.CREATE_NO_WINDOW if os.name=='nt' else 0
    if junctions:
        plan=directory/'junction-plan.json';plan.write_text(json.dumps(junctions),'utf-8')
        helper=directory/'prepare-fixture.ps1';helper.write_text("param([string]$Plan)\n$ErrorActionPreference='Stop'\nforeach ($binding in (Get-Content -LiteralPath $Plan -Raw | ConvertFrom-Json)) { New-Item -ItemType Junction -Path $binding.target -Value $binding.source | Out-Null }\n",'utf-8')
        subprocess.run(['powershell','-NoProfile','-NonInteractive','-File',str(helper),'-Plan',str(plan)],check=True,capture_output=True,creationflags=flags)
    (fixture/main_path).parent.mkdir(parents=True)
    (fixture/main_path).write_bytes((source/main_path).read_bytes())
    (fixture/partial_path).write_text(json.dumps({'SubMissionList':selected}),'utf-8')
    (fixture/'resource-manifest.json').write_text(json.dumps({'clientVersion':'4.6.0','testFixture':True,'missionRelationFixture':True,'originalMonitoredDefinitionAndRelationUnmodified':True}),'utf-8')
    assert not os.path.samefile(fixture/'resource-manifest.json',source/'resource-manifest.json')
    http_port,mongo_port,game_port=free_port(),free_port(),free_port(socket.SOCK_DGRAM)
    (directory/'config.json').write_text(json.dumps({'protocol':{'wireVersion':'4.6.0'},'resourceDir':str(fixture),
        'httpServer':{'bindAddress':'127.0.0.1','bindPort':http_port},'gameServer':{'bindAddress':'127.0.0.1','bindPort':game_port},
        'internalMongoServer':{'address':'127.0.0.1','port':mongo_port,'filePath':'./data/database.mv'}}),'utf-8')
    encoding_java=['-Dfile.encoding=UTF-8','-Dstdout.encoding=UTF-8','-Dstderr.encoding=UTF-8']
    java_option='-Djdk.net.unixdomain.tmpdir='+str(directory/'tcp-fallback');command=[str(java),*encoding_java,'-Xmx1536m',java_option,'-jar',str(artifact),'--no-handbook']
    base=f'http://127.0.0.1:{http_port}';credentials=None;snapshots=[];inspections=[];normal_stops=0
    def request(route,body=None):
        req=urllib.request.Request(base+route,data=None if body is None else json.dumps(body).encode(),headers={'Content-Type':'application/json'})
        with urllib.request.urlopen(req,timeout=5) as response:return json.load(response)
    def probe(mode):
        assert hashlib.sha256(artifact.read_bytes()).hexdigest()==digest
        result=subprocess.run([str(java),*encoding_java,java_option,'-cp',str(artifact),'org.hkrpg.server.protocol.LoopbackMissionRelationsProbe',str(game_port)],
            input=json.dumps(dict(credentials,resume=mode=='resume',bootstrap=mode=='bootstrap')),text=True,encoding='utf-8',capture_output=True,timeout=45,creationflags=flags)
        (directory/f'probe-{len(snapshots)}-{mode}.log').write_text(result.stdout+result.stderr,'utf-8');assert result.returncode==0,directory
        value=json.loads(next(r for r in reversed(result.stdout.splitlines()) if r.startswith('{')));assert value['passed'];snapshots.append(value);return value
    def editor(mode):
        result=subprocess.run([str(java),*encoding_java,java_option,'-cp',os.pathsep.join([str(classes),str(artifact)]),'org.hkrpg.server.gameplay.MissionRelationsFixtureInspector',str(directory),str(snapshots[0]['uid']),mode],
            cwd=ROOT,text=True,encoding='utf-8',capture_output=True,timeout=45,creationflags=flags)
        (directory/f'editor-{len(inspections)}-{mode}.log').write_text(result.stdout+result.stderr,'utf-8');assert result.returncode==0,directory
        value=json.loads(next(r for r in reversed(result.stdout.splitlines()) if r.startswith('{')));assert value['passed'];inspections.append(value);return value
    for iteration,mode in enumerate(['bootstrap','complete','resume']):
        with (directory/f'server-{iteration}.log').open('w',encoding='utf-8') as log:
            process=subprocess.Popen(command,cwd=directory,stdin=subprocess.PIPE,stdout=log,stderr=subprocess.STDOUT,creationflags=flags)
            try:
                deadline=time.monotonic()+90
                while True:
                    assert process.poll() is None,directory
                    try:
                        if request('/health/ready')['ready']:break
                    except (OSError,urllib.error.URLError):pass
                    if time.monotonic()>deadline:raise RuntimeError(f'Revival readiness timeout: {directory}')
                    time.sleep(.2)
                if iteration==0:
                    login=request('/hkrpg_cn/mdk/shield/api/login',{'account':'cn-46-submission-monitor','password':'local-test'});assert login['retcode']==0;account=login['data']['account']
                    combo=request('/hkrpg_cn/combo/granter/login/v2/login',{'data':json.dumps({'uid':account['uid'],'token':account['token']})});assert combo['retcode']==0
                    credentials={'accountUid':account['uid'],'comboToken':combo['data']['combo_token']}
                value=probe(mode)
                if mode=='complete':probe('resume')
                time.sleep(2)
            finally:
                if process.poll() is None:
                    try:
                        process.stdin.write(b'stop\n');process.stdin.flush();process.wait(timeout=30)
                        assert process.returncode==(1000 if os.name=='nt' else 232),directory
                        normal_stops+=1
                    except BaseException:
                        if process.poll() is None:process.terminate();process.wait(timeout=15)
                        raise
                    finally:process.stdin.close()
        if iteration==0:
            editor('seed');editor('seeded')
        else:editor('finished')
    assert len(snapshots)==4 and normal_stops==3 and len(inspections)==4
    for value in snapshots:assert value['uid']==10001 and value['entry']==1055101 and value['wallet']==0 and not value['clientGameplayVerified']
    for value in snapshots[1:]:assert value['watchedProgress']==2 and value['monitorProgress']==1
    assert inspections[0]['changedPlayers']==1 and inspections[1]['changedPlayers']==0
    assert inspections[0]['subMissions']==inspections[1]['subMissions']
    for inspection in inspections[2:]:assert inspection['changedPlayers']==0 and inspection['subMissions']['105450235']['progress']==1 and inspection['storyActions']['105450235']=={'next':1,'pending':False}
    assert hashes=={name:hashlib.sha256((source/name).read_bytes()).hexdigest() for name in guarded}
    assert hashlib.sha256(artifact.read_bytes()).hexdigest()==digest
    print(json.dumps({'passed':True,'runtimeDirectory':str(directory),'snapshots':snapshots,'inspections':inspections,
        'checks':[check for snapshot in snapshots for check in snapshot['checks']], 'normalStopCount':normal_stops,
        'normalStopCommandUsed':True,'rootJarSHA256':digest,'sourceSHA256':hashes,'sourceResourcesModified':False,
        'fixture':{'acceptanceMainSeeded':main,'finishedPrerequisiteSeeded':105450227,'offlineEditorIsTestClassOnly':True,'originalMonitoredDefinitionAndRelationUnmodified':True,'missionSubsetIDs':sorted(ids)},
        'clientGameplayVerified':False,'officialStoryChainVerified':False,'nativeConditionExecutorVerified':False,'crashAtomicityVerified':False},ensure_ascii=False))


if __name__=='__main__':main()
