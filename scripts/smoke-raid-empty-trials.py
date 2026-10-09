"""原始40403空试用目录的正式队伍入口；前置主任务为隔离夹具。"""
import hashlib
import json
import os
from pathlib import Path
import shutil
import socket
import subprocess
import tempfile
import time
import urllib.error
import urllib.request
from smoke_resource_fixture import mirror_resources
from importlib.machinery import SourceFileLoader

ROOT=Path(__file__).resolve().parents[1]
free_port=SourceFileLoader('empty_raid_ports',str(ROOT/'scripts/smoke-mail.py')).load_module().free_port


def main():
    source=ROOT/'resources/4.6';jar=ROOT/'build/libs/hkrpg-server.jar';java=next((ROOT/'.tools').glob('jdk-21*/bin/java.exe'),None)
    assert java and jar.is_file()
    digest=hashlib.sha256(jar.read_bytes()).hexdigest();runs=ROOT/'build/smoke';runs.mkdir(exist_ok=True)
    directory=Path(tempfile.mkdtemp(prefix='raid-empty-trials-',dir=runs));assert directory.resolve().is_relative_to(runs.resolve())
    fixture=directory/'resources-fixture';mission='Config/Level/Mission/1040115'
    guarded=['resource-manifest.json','ExcelOutput/MainMission.json','ExcelOutput/RaidConfig.json','ExcelOutput/MapEntrance.json','ExcelOutput/MazePlane.json',
             'Config/LevelOutput/RuntimeFloor/P40403_F40403001.json']
    guarded += [p.relative_to(source).as_posix() for p in (source/mission).rglob('*') if p.is_file()]
    guarded += [p.relative_to(source).as_posix() for p in (source/'Config/LevelOutput/SharedRuntimeGroup/Groups_P40403_F40403001').glob('*.json')]
    hashes={name:hashlib.sha256((source/name).read_bytes()).hexdigest() for name in guarded}
    assert hashes['resource-manifest.json']=='ecccb21469df76282e6df5bf0cbe1eff7d9f904c2dd644092124b6904482e735'
    rows=json.loads((source/'ExcelOutput/MainMission.json').read_text('utf-8'))
    parent=next(r for r in rows if r['MainMissionID']==1040113)
    parent.update(TakeParam=[{'Type':'Manual'}],TakeOperation='And',BeginParam=[{'Type':'Auto'}],BeginOperation='And',RewardID=0,SubRewardList=[])
    junctions=mirror_resources(source,fixture,{'ExcelOutput/MainMission.json':rows});flags=subprocess.CREATE_NO_WINDOW if os.name=='nt' else 0
    if junctions:
        plan=directory/'junction-plan.json';plan.write_text(json.dumps(junctions),'utf-8')
        helper=directory/'prepare-fixture.ps1';helper.write_text("param([string]$Plan)\n$ErrorActionPreference='Stop'\nforeach ($binding in (Get-Content -LiteralPath $Plan -Raw | ConvertFrom-Json)) { New-Item -ItemType Junction -Path $binding.target -Value $binding.source | Out-Null }\n",'utf-8')
        subprocess.run(['powershell','-NoProfile','-NonInteractive','-File',str(helper),'-Plan',str(plan)],check=True,capture_output=True,creationflags=flags)
    for name in guarded:
        if name.startswith(mission+'/'):
            target=fixture/name;target.parent.mkdir(parents=True,exist_ok=True);shutil.copyfile(source/name,target)
            assert hashlib.sha256(target.read_bytes()).hexdigest()==hashes[name]
    hold=19997991;parent_info=fixture/'Config/Level/Mission/1040113/MissionInfo_1040113.json';parent_info.parent.mkdir(parents=True)
    parent_info.write_text(json.dumps({'MainMissionID':1040113,'FinishSubMissionList':[hold],'SubMissionList':[{'ID':hold,'MainMissionID':1040113,'TakeType':'Auto','FinishType':'Talk','ParamStr1':'fixture_parent_hold','Progress':1}]}),'utf-8')
    (fixture/'resource-manifest.json').write_text(json.dumps({'clientVersion':'4.6.0','testFixture':True,'originalRaid':40403,'fixtureParentMission':1040113}),'utf-8')
    assert not os.path.samefile(fixture/'resource-manifest.json',source/'resource-manifest.json')
    http_port,mongo_port,game_port=free_port(),free_port(),free_port(socket.SOCK_DGRAM)
    (directory/'config.json').write_text(json.dumps({'protocol':{'wireVersion':'4.6.0'},'resourceDir':str(fixture),
        'httpServer':{'bindAddress':'127.0.0.1','bindPort':http_port},'gameServer':{'bindAddress':'127.0.0.1','bindPort':game_port},
        'internalMongoServer':{'address':'127.0.0.1','port':mongo_port,'filePath':'./data/database.mv'}}),'utf-8')
    java_flags=['-Dfile.encoding=UTF-8','-Dstdout.encoding=UTF-8','-Dstderr.encoding=UTF-8','-Djdk.net.unixdomain.tmpdir='+str(directory/'tcp-fallback')]
    command=[str(java),*java_flags,'-Xmx1536m','-jar',str(jar),'--no-handbook'];base=f'http://127.0.0.1:{http_port}'
    credentials=None;snapshots=[];stops=0
    def request(route,body=None):
        req=urllib.request.Request(base+route,data=None if body is None else json.dumps(body).encode(),headers={'Content-Type':'application/json'})
        with urllib.request.urlopen(req,timeout=5) as response:return json.load(response)
    def probe(resume):
        assert hashlib.sha256(jar.read_bytes()).hexdigest()==digest
        result=subprocess.run([str(java),*java_flags,'-cp',str(jar),'org.hkrpg.server.protocol.LoopbackEmptyRaidProbe',str(game_port)],
            input=json.dumps(dict(credentials,resume=resume)),text=True,encoding='utf-8',capture_output=True,timeout=60,creationflags=flags)
        (directory/f'probe-{len(snapshots)}.log').write_text(result.stdout+result.stderr,'utf-8');assert result.returncode==0,directory
        value=json.loads(next(line for line in reversed(result.stdout.splitlines()) if line.startswith('{')));assert value['passed'];snapshots.append(value)
        credentials.update(home=value['home'],roster=value['roster']);return value
    for iteration in range(2):
        with (directory/f'server-{iteration}.log').open('w',encoding='utf-8') as log:
            process=subprocess.Popen(command,cwd=directory,stdin=subprocess.PIPE,stdout=log,stderr=subprocess.STDOUT,creationflags=flags)
            try:
                deadline=time.monotonic()+90
                while True:
                    assert process.poll() is None,directory
                    try:
                        if request('/health/ready')['ready']:break
                    except (OSError,urllib.error.URLError):pass
                    if time.monotonic()>deadline:raise RuntimeError(f'Empty raid readiness timeout: {directory}')
                    time.sleep(.2)
                if iteration==0:
                    login=request('/hkrpg_cn/mdk/shield/api/login',{'account':'cn-46-raid-empty-trials','password':'local-test'});assert login['retcode']==0
                    account=login['data']['account'];combo=request('/hkrpg_cn/combo/granter/login/v2/login',{'data':json.dumps({'uid':account['uid'],'token':account['token']})});assert combo['retcode']==0
                    credentials={'accountUid':account['uid'],'comboToken':combo['data']['combo_token']}
                probe(iteration!=0)
                if iteration==0:probe(True)
                time.sleep(2)
            finally:
                if process.poll() is None:
                    try:
                        process.stdin.write(b'stop\n');process.stdin.flush();process.wait(timeout=30)
                        assert process.returncode==(1000 if os.name=='nt' else 232),directory;stops+=1
                    except BaseException:
                        if process.poll() is None:process.terminate();process.wait(timeout=15)
                        raise
                    finally:process.stdin.close()
    assert len(snapshots)==3 and stops==2
    assert len({r['uid'] for r in snapshots})==1
    assert all(r['home']==snapshots[0]['home'] and r['roster']==snapshots[0]['roster'] and r['entry']==40403001 for r in snapshots)
    assert hashes=={name:hashlib.sha256((source/name).read_bytes()).hexdigest() for name in guarded}
    assert hashlib.sha256(jar.read_bytes()).hexdigest()==digest
    print(json.dumps({'passed':True,'runtimeDirectory':str(directory),'snapshots':snapshots,'checks':[c for r in snapshots for c in r['checks']],
        'normalStopCount':stops,'normalStopCommandUsed':True,'rootJarSHA256':digest,'sourceSHA256':hashes,'sourceResourcesModified':False,
        'fixture':{'parentMission':1040113,'parentGateAndDefinitionIsolated':True,'originalRaidAndFullMissionUnmodified':True,'officialMissionGraphVerified':False},
        'clientGameplayVerified':False,'crashAtomicityVerified':False},ensure_ascii=False))


if __name__=='__main__':main()
