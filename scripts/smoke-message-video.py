"""原始手机视频消息、整批拒绝、重连与正常H2重启；接取任务为隔离夹具。"""
from pathlib import Path
import hashlib,json,os,socket,subprocess,tempfile,time,urllib.error,urllib.request

from smoke_resource_fixture import mirror_resources

ROOT=Path(__file__).resolve().parents[1]

def free_port(kind=socket.SOCK_STREAM):
    with socket.socket(type=kind) as sock:sock.bind(('127.0.0.1',0));return sock.getsockname()[1]

def main():
    source=ROOT/'resources/4.6';artifact=ROOT/'build/libs/hkrpg-server.jar'
    java=next((ROOT/'.tools').glob('jdk-21*/bin/java.exe'),None);assert java and artifact.is_file()
    classes=ROOT/'build/classes/java/test';assert (classes/'org/hkrpg/server/gameplay/MessageVideoFixtureInspector.class').is_file()
    digest=hashlib.sha256(artifact.read_bytes()).hexdigest();runs=ROOT/'build/smoke';runs.mkdir(exist_ok=True)
    directory=Path(tempfile.mkdtemp(prefix='message-video-',dir=runs));assert directory.resolve().is_relative_to(runs.resolve())
    fixture=directory/'resources-fixture';excel=fixture/'ExcelOutput';excel.mkdir(parents=True)
    names=['MessageItemConfig','MessageSectionConfig','MessageGroupConfig','MessageItemVideo','MainMission']
    guarded=['resource-manifest.json']+['ExcelOutput/'+n+'.json' for n in names]
    hashes={name:hashlib.sha256((source/name).read_bytes()).hexdigest() for name in guarded}
    assert hashes['resource-manifest.json']=='ecccb21469df76282e6df5bf0cbe1eff7d9f904c2dd644092124b6904482e735','Original manifest differs from preserved checksum'
    section_ids=[1219000,1219100,1504201];items={r['ID']:r for r in json.loads((source/'ExcelOutput/MessageItemConfig.json').read_text('utf-8'))}
    section_rows={r['ID']:r for r in json.loads((source/'ExcelOutput/MessageSectionConfig.json').read_text('utf-8'))}
    paths={};reports={};initial={};offered={}
    for section in section_ids:
        path=[];nexts=section_rows[section]['StartMessageItemIDList'];assert section_rows[section]['IsPerformMessage']
        while nexts:
            item=nexts[0];assert item not in path and items[item]['SectionID']==section;path.append(item);nexts=items[item]['NextItemIDList']
        paths[str(section)]=path;reports[str(section)]=[id for id in path if items[id]['Sender']=='Player' or items[id]['ItemType']=='Video']
        stop=next(index for index,id in enumerate(path) if id in reports[str(section)]);initial[str(section)]=path[:stop];offered[str(section)]=items[path[stop-1]]['NextItemIDList'] if stop else section_rows[section]['StartMessageItemIDList']
    assert reports['1219100']==[121910005]
    main=199936;main_rows=json.loads((source/'ExcelOutput/MainMission.json').read_text('utf-8'));assert not any(r['MainMissionID']==main for r in main_rows)
    main_rows.append({'MainMissionID':main,'TakeOperation':'And','TakeParam':[{'Type':'Manual'}],'BeginOperation':'And','BeginParam':[{'Type':'Auto'}]})
    replacements={'ExcelOutput/MainMission.json':main_rows}
    junctions=mirror_resources(source,fixture,replacements);flags=subprocess.CREATE_NO_WINDOW if os.name=='nt' else 0
    if junctions:
        plan=directory/'junction-plan.json';plan.write_text(json.dumps(junctions),'utf-8')
        helper=directory/'prepare-fixture.ps1';helper.write_text("param([string]$Plan)\n$ErrorActionPreference='Stop'\nforeach ($binding in (Get-Content -LiteralPath $Plan -Raw | ConvertFrom-Json)) { New-Item -ItemType Junction -Path $binding.target -Value $binding.source | Out-Null }\n",'utf-8')
        subprocess.run(['powershell','-NoProfile','-NonInteractive','-File',str(helper),'-Plan',str(plan)],check=True,capture_output=True,creationflags=flags)
    subs=[{'ID':id,'MainMissionID':main,'TakeType':'Auto','FinishType':'MessagePerformSectionFinish','ParamType':'Equal','ParamInt1':id,'Progress':1} for id in section_ids]
    mission=fixture/'Config/Level/Mission'/str(main);mission.mkdir(parents=True)
    (mission/f'MissionInfo_{main}.json').write_text(json.dumps({'MainMissionID':main,'StartSubMissionList':section_ids,'FinishSubMissionList':section_ids,'SubMissionList':subs}),'utf-8')
    (fixture/'resource-manifest.json').write_text(json.dumps({'clientVersion':'4.6.0','testFixture':True,'messageVideoFixture':True,'paths':paths,'originalMessageRowsUnmodified':True}),'utf-8')
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
        result=subprocess.run([str(java),*encoding_java,java_option,'-cp',str(artifact),'org.hkrpg.server.protocol.LoopbackMessageVideoProbe',str(game_port)],
            input=json.dumps(dict(credentials,resume=mode=='resume',paths=paths,reports=reports,initial=initial,offered=offered)),text=True,encoding='utf-8',capture_output=True,timeout=45,creationflags=flags)
        (directory/f'probe-{len(snapshots)}-{mode}.log').write_text(result.stdout+result.stderr,'utf-8');assert result.returncode==0,directory
        value=json.loads(next(r for r in reversed(result.stdout.splitlines()) if r.startswith('{')));assert value['passed'];snapshots.append(value);return value
    def editor(mode):
        result=subprocess.run([str(java),*encoding_java,java_option,'-cp',os.pathsep.join([str(classes),str(artifact)]),'org.hkrpg.server.gameplay.MessageVideoFixtureInspector',str(directory),str(snapshots[0]['uid'])],
            cwd=ROOT,text=True,encoding='utf-8',capture_output=True,timeout=45,creationflags=flags)
        (directory/f'editor-{len(inspections)}-{mode}.log').write_text(result.stdout+result.stderr,'utf-8');assert result.returncode==0,directory
        value=json.loads(next(r for r in reversed(result.stdout.splitlines()) if r.startswith('{')));assert value['passed'];inspections.append(value);return value
    for iteration,mode in enumerate(['complete','resume']):
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
                    login=request('/hkrpg_cn/mdk/shield/api/login',{'account':'cn-46-message-video','password':'local-test'});assert login['retcode']==0;account=login['data']['account']
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
        editor('finished')
    assert len(snapshots)==3 and normal_stops==2 and len(inspections)==2
    for value in snapshots:
        assert value['uid']==10001 and value['histories']==paths and value['finishedSections']==section_ids
        assert value['wallet']==0 and value['objectiveProgress']==3 and not value['clientGameplayVerified']
    for inspection in inspections:assert inspection['histories']==paths and inspection['inspectionWrites']==0 and not inspection['operatorDataModified']
    assert hashes=={name:hashlib.sha256((source/name).read_bytes()).hexdigest() for name in guarded}
    assert hashlib.sha256(artifact.read_bytes()).hexdigest()==digest
    print(json.dumps({'passed':True,'runtimeDirectory':str(directory),'snapshots':snapshots,'inspections':inspections,
        'checks':[check for snapshot in snapshots for check in snapshot['checks']], 'normalStopCount':normal_stops,
        'normalStopCommandUsed':True,'rootJarSHA256':digest,'sourceSHA256':hashes,'sourceResourcesModified':False,
        'fixture':{'acceptanceMain':main,'acceptanceSubs':section_ids,'offlineInspectorIsTestClassOnly':True,'originalMessageRowsUnmodified':True},
        'clientGameplayVerified':False,'officialStoryChainVerified':False,'videoPlaybackVerified':False,'crashAtomicityVerified':False},ensure_ascii=False))


if __name__=='__main__':main()
