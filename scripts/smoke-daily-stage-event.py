"""原始事件/怪物波次的合成KCP验证；完整原始日常文件、战斗推进、尾对话、奖励及两次正常H2恢复；不执行官方客户端。"""
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

ROOT = Path(__file__).resolve().parents[1]


def free_port(kind=socket.SOCK_STREAM):
    with socket.socket(type=kind) as sock:
        sock.bind(('127.0.0.1', 0))
        return sock.getsockname()[1]


def main():
    source = ROOT/'resources/4.6'
    jar = ROOT/'build/libs/hkrpg-server.jar'
    java = next((ROOT/'.tools').glob('jdk-21*/bin/java.exe'), None)
    assert java and jar.is_file()
    digest = hashlib.sha256(jar.read_bytes()).hexdigest()
    runs = ROOT/'build/smoke'
    runs.mkdir(exist_ok=True)
    directory = Path(tempfile.mkdtemp(prefix='daily-stage-event-', dir=runs))
    assert directory.resolve().is_relative_to(runs.resolve())
    fixture = directory/'resources-fixture'
    guarded = ['resource-manifest.json','ExcelOutput/PlaneEvent.json','ExcelOutput/StageConfig.json',
               'ExcelOutput/MainMission.json','ExcelOutput/RewardData.json','ExcelOutput/QuestData.json',
               'ExcelOutput/FinishWay.json','ExcelOutput/DailyMissionData.json','ExcelOutput/DailyMissionCount.json',
               'ExcelOutput/DailyActiveQuestPool.json','ExcelOutput/MapEntrance.json','ExcelOutput/MazePlane.json','ExcelOutput/ItemConfig.json']
    folder=source/'Config/Level/Mission/3011607'
    files=sorted(p for p in folder.iterdir() if p.is_file())
    assert len(files)==5
    guarded += [p.relative_to(source).as_posix() for p in files]
    hashes={name:hashlib.sha256((source/name).read_bytes()).hexdigest() for name in guarded}
    mains=json.loads((source/'ExcelOutput/MainMission.json').read_text('utf-8'))
    for row in mains:
        if row['MainMissionID'] in [1010403,1011501]:
            row.update(TakeParam=[{'Type':'Auto'}],TakeOperation='And',BeginParam=[{'Type':'Auto'}],BeginOperation='And',RewardID=0)
    entrances=json.loads((source/'ExcelOutput/MapEntrance.json').read_text('utf-8'))
    for row in entrances:
        if row['ID']==2012201:
            row.update(BeginMainMissionList=[],FinishMainMissionList=[],FinishSubMissionList=[])
    daily=[row for row in json.loads((source/'ExcelOutput/DailyMissionData.json').read_text('utf-8')) if row['ID']==3011607]
    pool=[row for row in json.loads((source/'ExcelOutput/DailyActiveQuestPool.json').read_text('utf-8')) if row['QuestID']==2100003]
    assert len(daily)==len(pool)==1
    junctions=mirror_resources(source,fixture,{'ExcelOutput/MainMission.json':mains,'ExcelOutput/MapEntrance.json':entrances,'ExcelOutput/DailyMissionData.json':daily,'ExcelOutput/DailyActiveQuestPool.json':pool})
    output=fixture/'Config/Level/Mission/3011607'
    output.mkdir(parents=True)
    for file in files:
        shutil.copy2(file,output/file.name)
        assert not (output/file.name).samefile(file)
        assert hashlib.sha256((output/file.name).read_bytes()).hexdigest()==hashes[file.relative_to(source).as_posix()]
    # Explicit automatic prerequisite graphs, with no seeded closed mission states or rewards.
    for prerequisite in [1010403,1011501]:
        sub=prerequisite*100+99
        output=fixture/f'Config/Level/Mission/{prerequisite}'
        output.mkdir(parents=True)
        (output/f'MissionInfo_{prerequisite}.json').write_text(json.dumps({'MainMissionID':prerequisite,'StartSubMissionList':[sub],'FinishSubMissionList':[sub]}),'utf-8')
        (output/f'MissionInfo_{prerequisite}.partial.json').write_text(json.dumps({'SubMissionList':[{'ID':sub,'MainMissionID':prerequisite,'TakeType':'Auto','FinishType':'Auto','Progress':1}]}),'utf-8')
    flags = subprocess.CREATE_NO_WINDOW if os.name == 'nt' else 0
    if junctions:
        plan = directory/'junction-plan.json'
        plan.write_text(json.dumps(junctions), 'utf-8')
        helper = directory/'prepare-fixture.ps1'
        helper.write_text("param([string]$Plan)\n$ErrorActionPreference='Stop'\nforeach ($binding in (Get-Content -LiteralPath $Plan -Raw | ConvertFrom-Json)) { New-Item -ItemType Junction -Path $binding.target -Value $binding.source | Out-Null }\n", 'utf-8')
        subprocess.run(['powershell', '-NoProfile', '-NonInteractive', '-File', str(helper), '-Plan', str(plan)], check=True, capture_output=True, creationflags=flags)
    (fixture/'resource-manifest.json').write_text(json.dumps({'clientVersion': '4.6.0', 'testFixture': True, 'originalDailyStageMission':3011607}), 'utf-8')
    assert not os.path.samefile(fixture/'resource-manifest.json', source/'resource-manifest.json')
    events = json.loads((source/'ExcelOutput/PlaneEvent.json').read_text('utf-8'))
    stages = {row['StageID']: row for row in json.loads((source/'ExcelOutput/StageConfig.json').read_text('utf-8'))}
    matches=[row for row in events if row['EventID']==20134222 and row.get('WorldLevel',0)==0]
    assert len(matches)==1 and matches[0]['StageID']==201342220
    waves=[[row[key] for key in ['Monster0','Monster1','Monster2','Monster3','Monster4'] if row.get(key,0)!=0] for row in stages[201342220]['MonsterList']]
    waves=[wave for wave in waves if wave]
    assert waves
    reset=((int(time.time())+14400)//86400+1)*86400-14400
    assert reset-int(time.time())>600, 'Too close to real daily reset for this same-day proof'
    http, mongo, game = free_port(), free_port(), free_port(socket.SOCK_DGRAM)
    (directory/'config.json').write_text(json.dumps({'protocol': {'wireVersion': '4.6.0'}, 'resourceDir': str(fixture),
        'httpServer': {'bindAddress': '127.0.0.1', 'bindPort': http}, 'gameServer': {'bindAddress': '127.0.0.1', 'bindPort': game},
        'internalMongoServer': {'address': '127.0.0.1', 'port': mongo, 'filePath': './data/database.mv'}}), 'utf-8')
    java_flags = ['-Dfile.encoding=UTF-8', '-Dstdout.encoding=UTF-8', '-Dstderr.encoding=UTF-8', '-Djdk.net.unixdomain.tmpdir='+str(directory/'tcp-fallback')]
    command = [str(java), *java_flags, '-Xmx1536m', '-jar', str(jar), '--no-handbook']
    base = f'http://127.0.0.1:{http}'
    credentials = None
    snapshots = []
    stops = 0

    def request(route, body=None):
        req = urllib.request.Request(base+route, data=None if body is None else json.dumps(body).encode(), headers={'Content-Type': 'application/json'})
        with urllib.request.urlopen(req, timeout=5) as response:
            return json.load(response)

    for iteration, phases in enumerate([['first', 'unclaimed'], ['claim', 'claimed'], ['claimed']]):
        with (directory/f'server-{iteration}.log').open('w', encoding='utf-8') as log:
            process = subprocess.Popen(command, cwd=directory, stdin=subprocess.PIPE, stdout=log, stderr=subprocess.STDOUT, creationflags=flags)
            try:
                deadline = time.monotonic()+90
                while True:
                    assert process.poll() is None, directory
                    try:
                        if request('/health/ready')['ready']:
                            break
                    except (OSError, urllib.error.URLError):
                        pass
                    if time.monotonic() > deadline:
                        raise RuntimeError(f'Scene event readiness timeout: {directory}')
                    time.sleep(.2)
                if iteration == 0:
                    login = request('/hkrpg_cn/mdk/shield/api/login', {'account': 'cn-46-daily-stage-event', 'password': 'local-test'})
                    assert login['retcode'] == 0
                    account = login['data']['account']
                    combo = request('/hkrpg_cn/combo/granter/login/v2/login', {'data': json.dumps({'uid': account['uid'], 'token': account['token']})})
                    assert combo['retcode'] == 0
                    credentials = {'accountUid': account['uid'], 'comboToken': combo['data']['combo_token']}
                for phase in phases:
                    assert hashlib.sha256(jar.read_bytes()).hexdigest() == digest
                    result = subprocess.run([str(java), *java_flags, '-cp', str(jar), 'org.hkrpg.server.protocol.LoopbackDailyStageEventProbe', str(game)],
                        input=json.dumps(dict(credentials, phase=phase, waves=waves)), text=True, encoding='utf-8', capture_output=True, timeout=90, creationflags=flags)
                    (directory/f'probe-{len(snapshots)}.log').write_text(result.stdout+result.stderr, 'utf-8')
                    assert result.returncode == 0, directory
                    value = json.loads(next(line for line in reversed(result.stdout.splitlines()) if line.startswith('{')))
                    assert value['passed']
                    snapshots.append(value)
                time.sleep(2)
            finally:
                if process.poll() is None:
                    try:
                        process.stdin.write(b'stop\n')
                        process.stdin.flush()
                        process.wait(timeout=30)
                        assert process.returncode == (1000 if os.name == 'nt' else 232), directory
                        stops += 1
                    except BaseException:
                        if process.poll() is None:
                            process.terminate()
                            process.wait(timeout=15)
                        raise
                    finally:
                        process.stdin.close()
    assert [row['phase'] for row in snapshots]==['first','unclaimed','claim','claimed','claimed']
    assert [row['loginWallet'] for row in snapshots]==[0,5000,5000,5000,5000]
    assert snapshots[0]['immediateQuestCompletionNotifyVerified'] and snapshots[0]['mainRewardNotifyBeforeResponseVerified']
    assert len({row['uid'] for row in snapshots})==1 and stops==3
    assert reset-int(time.time())>0
    assert hashes == {name: hashlib.sha256((source/name).read_bytes()).hexdigest() for name in guarded}
    assert hashlib.sha256(jar.read_bytes()).hexdigest() == digest
    print(json.dumps({'passed': True, 'runtimeDirectory': str(directory), 'snapshots': snapshots,
        'checks': [check for row in snapshots for check in row['checks']], 'normalStopCount': stops,
        'rootJarSHA256': digest, 'sourceSHA256': hashes, 'sourceResourcesModified': False,
        'sameUIDNormalH2ProcessRestarts': 2, 'exactOriginalEventAndWorldZeroStageAndMonsterWavesVerified': True, 'originalSourceDailyStageMission':3011607, 'originalMissionFolderFileCount':5,
        'fixture': {'originalEventAndStageTablesUnmodified': True, 'completeOriginalMissionFolderCopiedWithoutModification':True,'isolatedAutoUnlockMissions':[1010403,1011501],'isolatedEntranceGates':[2012201],'dailyMissionPoolIsolatedToOneOriginalRow':True,'completedMissionsOrRewardsSeeded':False},
        'fullMissionScriptOrEventAuthorizationVerified': False, 'clientGameplayVerified': False, 'crashAtomicityVerified': False}, ensure_ascii=False))


if __name__ == '__main__':
    main()
