"""原始复活装置、双人复活/咕咕钟转换、重连与正常H2重启；倒地与奇物历史为隔离夹具。"""
from pathlib import Path
import hashlib,json,os,socket,subprocess,tempfile,time,urllib.error,urllib.request

ROOT=Path(__file__).resolve().parents[1]

def free_port(kind=socket.SOCK_STREAM):
    with socket.socket(type=kind) as sock:sock.bind(('127.0.0.1',0));return sock.getsockname()[1]

def main():
    source=ROOT/'resources/4.6';artifact=ROOT/'build/libs/hkrpg-server.jar'
    java=next((ROOT/'.tools').glob('jdk-21*/bin/java.exe'),None);assert java and artifact.is_file()
    classes=ROOT/'build/classes/java/test';assert (classes/'org/hkrpg/server/gameplay/RogueRevivalFixtureEditor.class').is_file()
    digest=hashlib.sha256(artifact.read_bytes()).hexdigest();runs=ROOT/'build/smoke';runs.mkdir(exist_ok=True)
    directory=Path(tempfile.mkdtemp(prefix='rogue-revival-',dir=runs));assert directory.resolve().is_relative_to(runs.resolve())
    fixture=directory/'resources-fixture';excel=fixture/'ExcelOutput';excel.mkdir(parents=True)
    guarded=['resource-manifest.json']+['ExcelOutput/'+n+'.json' for n in ['RogueRoom','MapEntrance','MazeProp','ConstValueRogue','RogueMiracle','RogueMiracleEffect','RogueMiracleDisplay']]+[
        'Config/LevelOutput/RuntimeFloor/P80001_F80001003.json',
        'Config/LevelOutput/SharedRuntimeGroup/Groups_P80001_F80001003/LevelGroup_P80001_F80001003_G22.json']
    hashes={name:hashlib.sha256((source/name).read_bytes()).hexdigest() for name in guarded}
    for origin in (source/'ExcelOutput').iterdir():
        target=excel/origin.name
        if origin.name=='ConstValueRogue.json':
            rows=json.loads(origin.read_text('utf-8'))
            entry=next(r for r in rows if r['ConstRogueName']=='Start_Rogue_Coin_Base');original_wallet=entry['ConstValue'];assert original_wallet=='50'
            entry['ConstValue']='200';target.write_text(json.dumps(rows),'utf-8')
        else:os.link(origin,target)
    flags=subprocess.CREATE_NO_WINDOW if os.name=='nt' else 0
    config=fixture/'Config'
    if os.name=='nt':
        plan=directory/'junction-plan.json';plan.write_text(json.dumps({'target':str(config),'source':str(source/'Config')}),'utf-8')
        helper=directory/'prepare-fixture.ps1';helper.write_text("param([string]$Plan)\n$ErrorActionPreference='Stop'\n$binding=Get-Content -LiteralPath $Plan -Raw | ConvertFrom-Json\nNew-Item -ItemType Junction -Path $binding.target -Value $binding.source | Out-Null\n",'utf-8')
        subprocess.run(['powershell','-NoProfile','-NonInteractive','-File',str(helper),'-Plan',str(plan)],check=True,capture_output=True,creationflags=flags)
    else:config.symlink_to(source/'Config',target_is_directory=True)
    (fixture/'resource-manifest.json').write_text(json.dumps({'clientVersion':'4.6.0','testFixture':True,'revivalFixture':True,'sourceRoomAndConsoleUnmodified':True}),'utf-8')
    (directory/'data').mkdir();pool=json.loads((ROOT/'src/main/resources/defaults/RogueMapGen.json').read_text('utf-8-sig'));original_pool=pool['1'];pool['1']=[306]
    (directory/'data/RogueMapGen.json').write_text(json.dumps(pool),'utf-8')
    http_port,mongo_port,game_port=free_port(),free_port(),free_port(socket.SOCK_DGRAM)
    (directory/'config.json').write_text(json.dumps({'protocol':{'wireVersion':'4.6.0'},'resourceDir':str(fixture),
        'serverOptions':{'welcomeMail':{'title':'Rogue revival avatar fixture','sender':'Isolated test','content':'Formal revival avatar','attachments':[{'id':1202,'count':1}]}},
        'httpServer':{'bindAddress':'127.0.0.1','bindPort':http_port},'gameServer':{'bindAddress':'127.0.0.1','bindPort':game_port},
        'internalMongoServer':{'address':'127.0.0.1','port':mongo_port,'filePath':'./data/database.mv'}}),'utf-8')
    java_option='-Djdk.net.unixdomain.tmpdir='+str(directory/'tcp-fallback');command=[str(java),'-Xmx1536m',java_option,'-jar',str(artifact),'--no-handbook']
    base=f'http://127.0.0.1:{http_port}';credentials=None;snapshots=[];inspections=[];normal_stops=0
    def request(route,body=None):
        req=urllib.request.Request(base+route,data=None if body is None else json.dumps(body).encode(),headers={'Content-Type':'application/json'})
        with urllib.request.urlopen(req,timeout=5) as response:return json.load(response)
    def probe(mode):
        assert hashlib.sha256(artifact.read_bytes()).hexdigest()==digest
        result=subprocess.run([str(java),java_option,'-cp',str(artifact),'org.hkrpg.server.protocol.LoopbackRogueRevivalProbe',str(game_port)],
            input=json.dumps(dict(credentials,mode=mode)),text=True,encoding='utf-8',capture_output=True,timeout=45,creationflags=flags)
        (directory/f'probe-{len(snapshots)}-{mode}.log').write_text(result.stdout+result.stderr,'utf-8');assert result.returncode==0,directory
        value=json.loads(next(r for r in reversed(result.stdout.splitlines()) if r.startswith('{')));assert value['passed'];snapshots.append(value);return value
    def editor(mode):
        result=subprocess.run([str(java),java_option,'-cp',os.pathsep.join([str(classes),str(artifact)]),'org.hkrpg.server.gameplay.RogueRevivalFixtureEditor',str(directory),str(snapshots[0]['uid']),mode],
            cwd=ROOT,text=True,encoding='utf-8',capture_output=True,timeout=45,creationflags=flags)
        (directory/f'editor-{len(inspections)}-{mode}.log').write_text(result.stdout+result.stderr,'utf-8');assert result.returncode==0,directory
        value=json.loads(next(r for r in reversed(result.stdout.splitlines()) if r.startswith('{')));assert value['passed'];inspections.append(value);return value
    for iteration,mode in enumerate(['bootstrap','revive','resume']):
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
                    login=request('/hkrpg_cn/mdk/shield/api/login',{'account':'cn-46-revival','password':'local-test'});assert login['retcode']==0;account=login['data']['account']
                    combo=request('/hkrpg_cn/combo/granter/login/v2/login',{'data':json.dumps({'uid':account['uid'],'token':account['token']})});assert combo['retcode']==0
                    credentials={'accountUid':account['uid'],'comboToken':combo['data']['combo_token']}
                value=probe(mode)
                if mode=='bootstrap':credentials['normalLineups']=value['normalLineups']
                elif mode=='revive':probe('resume')
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
            seeded=editor('seed');reopened=editor('dead')
            assert seeded['restoredRun']==reopened['restoredRun'] and seeded['changedPlayers']==1 and reopened['changedPlayers']==0
        else:editor('revived')
    assert len(snapshots)==4 and normal_stops==3
    for value in snapshots:
        assert value['uid']==snapshots[0]['uid'] and value['normalLineups']==snapshots[0]['normalLineups'] and value['originalReviveConsolePresent']
        assert value['members']==[8001,1202] and value['hp']==10000 and value['sp']==5000
    for value in snapshots[1:]:assert value['coins']==999 and value['curios']==[9] and value['unitPrice']==80
    assert snapshots[1]['priceChangeNotifies']==1 and snapshots[2]['priceChangeNotifies']==snapshots[3]['priceChangeNotifies']==0
    for inspection in inspections:assert not inspection['operatorDataModified']
    first=inspections[0]['restoredRun'];second=inspections[2]['restoredRun'];third=inspections[3]['restoredRun']
    assert second['miracles']['9']['acquisitionId']==third['miracles']['9']['acquisitionId']
    key='clock:70:'+first['miracles']['70']['acquisitionId']
    assert set(second['miracleFragmentGrants'])==set(third['miracleFragmentGrants'])=={key}
    assert second['miracleFragmentGrants']==third['miracleFragmentGrants']
    assert hashes=={name:hashlib.sha256((source/name).read_bytes()).hexdigest() for name in guarded}
    assert hashlib.sha256(artifact.read_bytes()).hexdigest()==digest
    print(json.dumps({'passed':True,'runtimeDirectory':str(directory),'snapshots':snapshots,'inspections':inspections,
        'checks':[check for snapshot in snapshots for check in snapshot['checks']], 'normalStopCount':normal_stops,
        'normalStopCommandUsed':True,'rootJarSHA256':digest,'sourceSHA256':hashes,'sourceResourcesModified':False,
        'fixture':{'firstRoomOnly':True,'originalFirstRoomPool':original_pool,'initialWallet':200,'originalInitialWallet':original_wallet,
            'originalRoomAndConsoleUnmodified':True,'ownedAvatarViaMail':1202,'deadVitalsSeeded':[8001,1202],
            'clockAcquisitionAnd490ExpenseSeeded':True,'offlineEditorIsTestClassOnly':True},
        'clientGameplayVerified':False,'officialDeathAndClockAcquisitionVerified':False,'crashAtomicityVerified':False},ensure_ascii=False))

if __name__=='__main__':main()
