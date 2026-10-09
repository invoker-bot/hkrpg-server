"""骰子首页20次真实KCP空请求、原始5星琼奖励与正常H2恢复；前置教程采用明确隔离夹具。"""
import hashlib
import json
import os
from pathlib import Path
import socket
import subprocess
import tempfile
import time
import urllib.error
import urllib.request
from smoke_resource_fixture import mirror_resources

ROOT = Path(__file__).resolve().parents[1]


def free_port(kind=socket.SOCK_STREAM):
    with socket.socket(type=kind) as sock:
        sock.bind(('127.0.0.1', 0))
        return sock.getsockname()[1]


def main():
    source = ROOT/'resources/4.6'
    jar = ROOT/'build/libs/hkrpg-server.jar'
    java = next((ROOT/'.tools').glob('jdk-21*/bin/java.exe'), None)
    classes = ROOT/'build/classes/java/test'
    assert java and jar.is_file() and (classes/'org/hkrpg/server/gameplay/DiceMainPageFixtureEditor.class').is_file()
    digest = hashlib.sha256(jar.read_bytes()).hexdigest()
    runs = ROOT/'build/smoke'
    runs.mkdir(exist_ok=True)
    directory = Path(tempfile.mkdtemp(prefix='dice-mainpage-', dir=runs))
    assert directory.resolve().is_relative_to(runs.resolve())
    fixture = directory/'resources-fixture'
    guarded = ['resource-manifest.json'] + ['ExcelOutput/'+name+'.json' for name in [
        'ActivityDiceConstValueCommo','ActivityConfig','ActivityPanel','QuestData','FinishWay','RewardData','ItemConfig']]
    hashes = {name: hashlib.sha256((source/name).read_bytes()).hexdigest() for name in guarded}
    panels = json.loads((source/'ExcelOutput/ActivityPanel.json').read_text('utf-8'))
    rows = list(panels.values()) if isinstance(panels,dict) else panels
    row = next(r for r in rows if r['PanelID']==50073)
    original_gate=row.get('UnlockConditions','');row['UnlockConditions']=''
    junctions = mirror_resources(source,fixture,{'ExcelOutput/ActivityPanel.json':panels})
    flags = subprocess.CREATE_NO_WINDOW if os.name == 'nt' else 0
    if junctions:
        plan=directory/'junction-plan.json';plan.write_text(json.dumps(junctions),'utf-8')
        helper=directory/'prepare-fixture.ps1'
        helper.write_text("param([string]$Plan)\n$ErrorActionPreference='Stop'\nforeach ($binding in (Get-Content -LiteralPath $Plan -Raw | ConvertFrom-Json)) { New-Item -ItemType Junction -Path $binding.target -Value $binding.source | Out-Null }\n",'utf-8')
        subprocess.run(['powershell','-NoProfile','-NonInteractive','-File',str(helper),'-Plan',str(plan)],check=True,capture_output=True,creationflags=flags)
    (fixture/'resource-manifest.json').write_text(json.dumps({'clientVersion':'4.6.0','testFixture':True,'diceMainPageFixture':True}),'utf-8')
    assert not os.path.samefile(fixture/'resource-manifest.json',source/'resource-manifest.json')
    data=directory/'data';data.mkdir()
    windows={'activities':[{'panelId':50073,'moduleId':5007301,'beginTime':1,'endTime':4102444800}]}
    (data/'Gameplay46.json').write_text(json.dumps(windows),'utf-8')
    http,mongo,game=free_port(),free_port(),free_port(socket.SOCK_DGRAM)
    (directory/'config.json').write_text(json.dumps({'protocol':{'wireVersion':'4.6.0'},'resourceDir':str(fixture),
        'httpServer':{'bindAddress':'127.0.0.1','bindPort':http},'gameServer':{'bindAddress':'127.0.0.1','bindPort':game},
        'internalMongoServer':{'address':'127.0.0.1','port':mongo,'filePath':'./data/database.mv'}}),'utf-8')
    java_flags=['-Dfile.encoding=UTF-8','-Dstdout.encoding=UTF-8','-Dstderr.encoding=UTF-8','-Djdk.net.unixdomain.tmpdir='+str(directory/'tcp-fallback')]
    command=[str(java),*java_flags,'-Xmx1536m','-jar',str(jar),'--no-handbook']
    base=f'http://127.0.0.1:{http}'
    credentials=None;snapshots=[];edits=[];stops=0

    def request(route,body=None):
        req=urllib.request.Request(base+route,data=None if body is None else json.dumps(body).encode(),headers={'Content-Type':'application/json'})
        with urllib.request.urlopen(req,timeout=5) as response:return json.load(response)

    def probe(mode):
        assert hashlib.sha256(jar.read_bytes()).hexdigest()==digest
        result=subprocess.run([str(java),*java_flags,'-cp',str(jar),'org.hkrpg.server.protocol.LoopbackDiceMainPageProbe',str(game)],
            input=json.dumps(dict(credentials,mode=mode)),text=True,encoding='utf-8',capture_output=True,timeout=75,creationflags=flags)
        (directory/f'probe-{len(snapshots)}-{mode}.log').write_text(result.stdout+result.stderr,'utf-8')
        assert result.returncode==0,directory
        value=json.loads(next(line for line in reversed(result.stdout.splitlines()) if line.startswith('{')))
        assert value['passed'] and not value['clientGameplayVerified'];snapshots.append(value);return value

    def editor(mode):
        result=subprocess.run([str(java),*java_flags,'-cp',os.pathsep.join([str(classes),str(jar)]),
            'org.hkrpg.server.gameplay.DiceMainPageFixtureEditor',str(directory),str(snapshots[0]['uid']),mode],
            cwd=ROOT,text=True,encoding='utf-8',capture_output=True,timeout=45,creationflags=flags)
        (directory/f'editor-{len(edits)}-{mode}.log').write_text(result.stdout+result.stderr,'utf-8')
        assert result.returncode==0,directory
        value=json.loads(next(line for line in reversed(result.stdout.splitlines()) if line.startswith('{')))
        assert value['passed'] and value['matchingPlayerCount']==1 and not value['operatorDataModified'] and not value['rollCounterEdited']
        edits.append(value);return value

    for iteration,modes in enumerate([['bootstrap'],['first','resume'],['finish'],['closed']]):
        if iteration==3:(data/'Gameplay46.json').write_text(json.dumps({'activities':[]}),'utf-8')
        with (directory/f'server-{iteration}.log').open('w',encoding='utf-8') as log:
            process=subprocess.Popen(command,cwd=directory,stdin=subprocess.PIPE,stdout=log,stderr=subprocess.STDOUT,creationflags=flags)
            try:
                deadline=time.monotonic()+90
                while True:
                    assert process.poll() is None,directory
                    try:
                        if request('/health/ready')['ready']:break
                    except (OSError,urllib.error.URLError):pass
                    if time.monotonic()>deadline:raise RuntimeError(f'Dice readiness timeout: {directory}')
                    time.sleep(.2)
                if iteration==0:
                    login=request('/hkrpg_cn/mdk/shield/api/login',{'account':'cn-46-dice-mainpage','password':'local-test'})
                    assert login['retcode']==0
                    account=login['data']['account']
                    combo=request('/hkrpg_cn/combo/granter/login/v2/login',{'data':json.dumps({'uid':account['uid'],'token':account['token']})})
                    assert combo['retcode']==0
                    credentials={'accountUid':account['uid'],'comboToken':combo['data']['combo_token']}
                for mode in modes:
                    value=probe(mode)
                    if mode=='bootstrap':credentials['initialHcoin']=value['initialHcoin']
                time.sleep(2)
            finally:
                if process.poll() is None:
                    try:
                        process.stdin.write(b'stop\n');process.stdin.flush();process.wait(timeout=30)
                        assert process.returncode==(1000 if os.name=='nt' else 232),directory
                        stops+=1
                    except BaseException:
                        if process.poll() is None:process.terminate();process.wait(timeout=15)
                        raise
                    finally:process.stdin.close()
        saved=editor('seed' if iteration==0 else 'inspect')
        expected_count=[0,10,20,20][iteration]
        assert saved['count']==expected_count and saved['questProgress']==expected_count,directory
        assert saved['hcoin']==credentials['initialHcoin']+(5 if iteration>=2 else 0)
        assert saved['questPhase']==(['absent','DOING','CLOSED','CLOSED'][iteration])
        assert saved['changedPlayers']==(1 if iteration==0 else 0)
    assert len({s['uid'] for s in snapshots})==1 and stops==4
    assert edits[2]['lastMillis']==edits[3]['lastMillis']
    assert all(hashlib.sha256((source/name).read_bytes()).hexdigest()==sha for name,sha in hashes.items())
    print(json.dumps({'passed':True,'rootJarSHA256':digest,'runtimeDirectory':str(directory),'snapshots':snapshots,'offlineReads':edits,
        'checks':[check for s in snapshots for check in s['checks']],'normalStopCount':stops,'sameUIDNormalH2HistoryRestarts':2,
        'twentyActualNativeEmptyRequestsAccepted':True,'sourceFiveJadePersistedAndDuplicateClaimRejected':True,
        'sourceResourcesModified':False,'sourceSHA256':hashes,'tutorialCompletionFixture':4000001,
        'panelGateOverride':{'panel':50073,'originalGate':original_gate},'operatorDataModified':False,'officialStoryUnlockVerified':False,
        'diceCombatSimulationVerified':False,'clientGameplayVerified':False,'crashRecoveryVerified':False},ensure_ascii=False))


if __name__=='__main__':main()
