"""原始4340104命名选择、两次离开/返回、书籍和短信尾奖；前置教程及入口为隔离夹具。"""
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
from importlib.machinery import SourceFileLoader
from smoke_resource_fixture import mirror_resources

ROOT = Path(__file__).resolve().parents[1]
free_port = SourceFileLoader('switch_floor_ports', str(ROOT/'scripts/smoke-mail.py')).load_module().free_port


def main():
    source = ROOT/'resources/4.6'
    jar = ROOT/'build/libs/hkrpg-server.jar'
    java = next((ROOT/'.tools').glob('jdk-21*/bin/java.exe'), None)
    assert java and jar.is_file()
    digest = hashlib.sha256(jar.read_bytes()).hexdigest()
    runs = ROOT/'build/smoke'
    runs.mkdir(exist_ok=True)
    directory = Path(tempfile.mkdtemp(prefix='switch-floor-', dir=runs))
    assert directory.resolve().is_relative_to(runs.resolve())
    fixture = directory/'resources-fixture'
    missions = [4340104]
    guarded = ['resource-manifest.json', 'ExcelOutput/MainMission.json', 'ExcelOutput/RewardData.json', 'ExcelOutput/MapEntrance.json', 'ExcelOutput/MazePlane.json', 'ExcelOutput/QuestData.json', 'ExcelOutput/FinishWay.json', 'ExcelOutput/ItemConfig.json', 'ExcelOutput/ItemConfigBook.json', 'ExcelOutput/MessageSectionConfig.json', 'ExcelOutput/MessageGroupConfig.json', 'ExcelOutput/MessageItemConfig.json', 'Config/LevelOutput/RuntimeFloor/P20311_F20311001.json', 'Config/LevelOutput/RuntimeFloor/P20312_F20312001.json']
    for main in missions:
        guarded += [p.relative_to(source).as_posix() for p in (source/f'Config/Level/Mission/{main}').rglob('*') if p.is_file()]
    hashes = {name: hashlib.sha256((source/name).read_bytes()).hexdigest() for name in guarded}
    assert hashes['resource-manifest.json'] == 'ecccb21469df76282e6df5bf0cbe1eff7d9f904c2dd644092124b6904482e735'
    rows = json.loads((source/'ExcelOutput/MainMission.json').read_text('utf-8'))
    for row in rows:
        if row['MainMissionID'] == 1030801:
            row.update(TakeParam=[{'Type': 'Auto'}], TakeOperation='And', BeginParam=[{'Type': 'Auto'}], BeginOperation='And', RewardID=0)
    entrances = json.loads((source/'ExcelOutput/MapEntrance.json').read_text('utf-8'))
    for row in entrances:
        if row['ID'] in [2031101, 2031201]:
            row.update(BeginMainMissionList=[], FinishMainMissionList=[], FinishSubMissionList=[])
    junctions = mirror_resources(source, fixture, {'ExcelOutput/MainMission.json': rows, 'ExcelOutput/MapEntrance.json': entrances})
    # Synthetic automatic prerequisite has no seeded completion and grants no rewards.
    folder = fixture/'Config/Level/Mission/1030801'
    folder.mkdir(parents=True, exist_ok=True)
    (folder/'MissionInfo_1030801.json').write_text(json.dumps({'MainMissionID': 1030801, 'StartSubMissionList': [103080199], 'FinishSubMissionList': [103080199]}), 'utf-8')
    (folder/'MissionInfo_1030801.partial.json').write_text(json.dumps({'SubMissionList': [{'ID': 103080199, 'MainMissionID': 1030801, 'TakeType': 'Auto', 'FinishType': 'Auto', 'Progress': 1}]}), 'utf-8')
    flags = subprocess.CREATE_NO_WINDOW if os.name == 'nt' else 0
    if junctions:
        plan = directory/'junction-plan.json'
        plan.write_text(json.dumps(junctions), 'utf-8')
        helper = directory/'prepare-fixture.ps1'
        helper.write_text("param([string]$Plan)\n$ErrorActionPreference='Stop'\nforeach ($binding in (Get-Content -LiteralPath $Plan -Raw | ConvertFrom-Json)) { New-Item -ItemType Junction -Path $binding.target -Value $binding.source | Out-Null }\n", 'utf-8')
        subprocess.run(['powershell', '-NoProfile', '-NonInteractive', '-File', str(helper), '-Plan', str(plan)], check=True, capture_output=True, creationflags=flags)
    for name in guarded:
        if name.startswith('Config/Level/Mission/'):
            target = fixture/name
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(source/name, target)
            assert hashlib.sha256(target.read_bytes()).hexdigest() == hashes[name]
    (fixture/'resource-manifest.json').write_text(json.dumps({'clientVersion': '4.6.0', 'testFixture': True, 'originalMissions': missions}), 'utf-8')
    assert not os.path.samefile(fixture/'resource-manifest.json', source/'resource-manifest.json')
    http_port, mongo_port, game_port = free_port(), free_port(), free_port(socket.SOCK_DGRAM)
    (directory/'config.json').write_text(json.dumps({'protocol': {'wireVersion': '4.6.0'}, 'resourceDir': str(fixture),
        'httpServer': {'bindAddress': '127.0.0.1', 'bindPort': http_port}, 'gameServer': {'bindAddress': '127.0.0.1', 'bindPort': game_port},
        'internalMongoServer': {'address': '127.0.0.1', 'port': mongo_port, 'filePath': './data/database.mv'}}), 'utf-8')
    java_flags = ['-Dfile.encoding=UTF-8', '-Dstdout.encoding=UTF-8', '-Dstderr.encoding=UTF-8', '-Djdk.net.unixdomain.tmpdir='+str(directory/'tcp-fallback')]
    command = [str(java), *java_flags, '-Xmx1536m', '-jar', str(jar), '--no-handbook']
    base = f'http://127.0.0.1:{http_port}'
    credentials = None
    snapshots = []
    observer_snapshots = []
    stops = 0
    sections = json.loads((source/'ExcelOutput/MessageSectionConfig.json').read_text('utf-8'))
    items = {row['ID']: row for row in json.loads((source/'ExcelOutput/MessageItemConfig.json').read_text('utf-8'))}
    choices = {}
    for section in sections:
        if section['ID'] not in [1303200, 1303300]: continue
        chain = []; offered = section['StartMessageItemIDList']
        while offered:
            at = offered[0]; assert at not in chain
            chain.append(at); offered = items[at]['NextItemIDList']
        choices[str(section['ID'])] = chain
    assert len(choices) == 2

    def request(route, body=None):
        req = urllib.request.Request(base+route, data=None if body is None else json.dumps(body).encode(), headers={'Content-Type': 'application/json'})
        with urllib.request.urlopen(req, timeout=5) as response:
            return json.load(response)

    def probe(phase):
        assert hashlib.sha256(jar.read_bytes()).hexdigest() == digest
        result = subprocess.run([str(java), *java_flags, '-cp', str(jar), 'org.hkrpg.server.protocol.LoopbackSwitchFloorProbe', str(game_port)],
            input=json.dumps(dict(credentials, phase=phase, messageChoices=choices)), text=True, encoding='utf-8', capture_output=True, timeout=60, creationflags=flags)
        (directory/f'probe-{len(snapshots)}.log').write_text(result.stdout+result.stderr, 'utf-8')
        assert result.returncode == 0, directory
        value = json.loads(next(line for line in reversed(result.stdout.splitlines()) if line.startswith('{')))
        assert value['passed']
        snapshots.append(value)
        credentials['initial212'] = value['initial212']

    for iteration, phases in enumerate([['first'], ['return', 'tail'], ['final', 'final']]):
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
                        raise RuntimeError(f'Source floor readiness timeout: {directory}')
                    time.sleep(.2)
                if iteration == 0:
                    login = request('/hkrpg_cn/mdk/shield/api/login', {'account': 'cn-46-switch-floor', 'password': 'local-test'})
                    assert login['retcode'] == 0
                    account = login['data']['account']
                    combo = request('/hkrpg_cn/combo/granter/login/v2/login', {'data': json.dumps({'uid': account['uid'], 'token': account['token']})})
                    assert combo['retcode'] == 0
                    credentials = {'accountUid': account['uid'], 'comboToken': combo['data']['combo_token']}
                if iteration == 2:
                    # The original account has not logged in to this new server process yet.
                    login = request('/hkrpg_cn/mdk/shield/api/login', {'account': 'cn-46-book-observer', 'password': 'local-test'})
                    assert login['retcode'] == 0
                    account = login['data']['account']
                    combo = request('/hkrpg_cn/combo/granter/login/v2/login', {'data': json.dumps({'uid': account['uid'], 'token': account['token']})})
                    assert combo['retcode'] == 0
                    result = subprocess.run([str(java), *java_flags, '-cp', str(jar), 'org.hkrpg.server.protocol.LoopbackSwitchFloorProbe', str(game_port)],
                        input=json.dumps({'accountUid': account['uid'], 'comboToken': combo['data']['combo_token'], 'phase': 'observer', 'targetUid': snapshots[0]['uid']}), text=True, encoding='utf-8', capture_output=True, timeout=60, creationflags=flags)
                    (directory/'observer-probe.log').write_text(result.stdout+result.stderr, 'utf-8')
                    assert result.returncode == 0, directory
                    observer = json.loads(next(line for line in reversed(result.stdout.splitlines()) if line.startswith('{')))
                    assert observer['passed'] and observer['offlineOriginalBeforeOwnerLogin'] and len(observer['checks']) == 4
                    assert observer['uid'] != snapshots[0]['uid'] and observer['targetUid'] == snapshots[0]['uid'] and observer['targetBookCount'] == 1
                    observer_snapshots.append(observer)
                for phase in phases:
                    probe(phase)
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
    assert len(snapshots) == 5 and stops == 3
    assert [row['phase'] for row in snapshots] == ['first', 'return', 'tail', 'final', 'final']
    assert [len(row['checks']) for row in snapshots] == [16, 12, 10, 9, 9]
    assert [row['loginWallet'] for row in snapshots] == [0, 0, 0, 3000, 3000]
    assert len({row['uid'] for row in snapshots}) == 1
    assert snapshots[0]['bookRewardNotifyBeforeResponse'] and snapshots[2]['tailRewardNotifyBeforeResponse']
    assert all(row['book'] == 1 for row in snapshots)
    assert all(row['profileBookCount'] == 1 for row in snapshots) and len(observer_snapshots) == 1
    assert [row['material212'] - row['initial212'] for row in snapshots] == [0, 0, 3, 3, 3]
    assert hashes == {name: hashlib.sha256((source/name).read_bytes()).hexdigest() for name in guarded}
    assert hashlib.sha256(jar.read_bytes()).hexdigest() == digest
    print(json.dumps({'passed': True, 'runtimeDirectory': str(directory), 'snapshots': snapshots,
        'checks': [c for r in snapshots + observer_snapshots for c in r['checks']], 'normalStopCount': stops, 'normalStopCommandUsed': True,
        'rootJarSHA256': digest, 'sourceSHA256': hashes, 'sourceResourcesModified': False,
        'fixture': {'isolatedAutomaticPrerequisite': 1030801, 'isolatedEntranceGates': [2031101, 2031201], 'sourceMainGraphAndTakeGatesUnmodified': True, 'sourceBookAndRewardRowsUnmodified': True, 'sourceMessageGraphsUnmodified': True, 'finishedMainSeeded': False, 'finishedQuestSeeded': False},
        'actualSourceBookAnd3000CreditAndThreeMaterial212Verified': True, 'sourceRewardNotificationsBeforeResponsesVerified': True,
        'actualNativeBookCountBeforeAndAfterGrantVerified': True, 'observerSnapshots': observer_snapshots,
        'offlineOwnerBookProfileAfterNormalH2RestartVerified': True, 'offlineProfileRepeatedReads': 12,
        'sameUIDNormalH2ProcessRestarts': 2, 'wholeTutorialUnlockChainVerified': False, 'fullClientScriptExecutionVerified': False,
        'clientGameplayVerified': False, 'crashAtomicityVerified': False}, ensure_ascii=False))


if __name__ == '__main__':
    main()
