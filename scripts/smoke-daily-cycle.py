"""原始周期关联日常、三个实际资源敌人、领奖防重和两次H2恢复；前置与时钟为隔离夹具。"""
import hashlib
import json
import os
from pathlib import Path
import queue
import socket
import subprocess
import tempfile
import threading
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
    source = ROOT / 'resources/4.6'
    jar = ROOT / 'build/libs/hkrpg-server.jar'
    java = next((ROOT / '.tools').glob('jdk-21*/bin/java.exe'), None)
    assert java and jar.is_file()
    digest = hashlib.sha256(jar.read_bytes()).hexdigest()
    runs = ROOT / 'build/smoke'
    runs.mkdir(exist_ok=True)
    directory = Path(tempfile.mkdtemp(prefix='daily-cycle-', dir=runs))
    assert directory.resolve().is_relative_to(runs.resolve())
    fixture = directory / 'resources-fixture'
    guarded = ['resource-manifest.json'] + ['ExcelOutput/' + name + '.json' for name in [
        'QuestData', 'FinishWay', 'CycleQuest', 'DailyQuest', 'ScheduleDataQuest', 'DailyActiveQuestPool',
        'MainMission', 'RewardData', 'RogueAreaConfig', 'RogueMap', 'RogueRoom', 'RogueMonster',
        'StageConfig', 'ConstValueRogue', 'MapEntrance', 'MazePlane', 'NPCMonsterData']]
    guarded += ['Config/LevelOutput/RuntimeFloor/P81122_F81122001.json']
    guarded += [f'Config/LevelOutput/SharedRuntimeGroup/Groups_P81122_F81122001/LevelGroup_P81122_F81122001_G{group}.json' for group in [70, 71, 72]]
    guarded += [p.relative_to(source).as_posix() for p in (source / 'Config/Level/Mission/8023701').rglob('*') if p.is_file()]
    hashes = {name: hashlib.sha256((source / name).read_bytes()).hexdigest() for name in guarded}
    assert hashes['resource-manifest.json'] == 'ecccb21469df76282e6df5bf0cbe1eff7d9f904c2dd644092124b6904482e735'
    mains = json.loads((source / 'ExcelOutput/MainMission.json').read_text('utf-8'))
    row = next(row for row in mains if row['MainMissionID'] == 8023701)
    row.update(TakeOperation='And', TakeParam=[{'Type': 'Auto'}], BeginOperation='And', BeginParam=[{'Type': 'Auto'}], RewardID=0)
    pool = [row for row in json.loads((source / 'ExcelOutput/DailyActiveQuestPool.json').read_text('utf-8')) if row['QuestID'] == 2100156]
    assert pool == [{'QuestID': 2100156, 'Type': 9}]
    junctions = mirror_resources(source, fixture, {'ExcelOutput/MainMission.json': mains, 'ExcelOutput/DailyActiveQuestPool.json': pool})
    # A no-reward automatic prerequisite graph. No daily/cycle or universe finished states are seeded.
    folder = fixture / 'Config/Level/Mission/8023701'
    folder.mkdir(parents=True, exist_ok=True)
    (folder / 'MissionInfo_8023701.json').write_text(json.dumps({'MainMissionID': 8023701, 'StartSubMissionList': [802370199], 'FinishSubMissionList': [802370199]}), 'utf-8')
    (folder / 'MissionInfo_8023701.partial.json').write_text(json.dumps({'SubMissionList': [{'ID': 802370199, 'MainMissionID': 8023701, 'TakeType': 'Auto', 'FinishType': 'Auto', 'Progress': 1}]}), 'utf-8')
    flags = subprocess.CREATE_NO_WINDOW if os.name == 'nt' else 0
    if junctions:
        plan = directory / 'junction-plan.json'
        plan.write_text(json.dumps(junctions), 'utf-8')
        helper = directory / 'prepare-fixture.ps1'
        helper.write_text("param([string]$Plan)\n$ErrorActionPreference='Stop'\nforeach ($binding in (Get-Content -LiteralPath $Plan -Raw | ConvertFrom-Json)) { New-Item -ItemType Junction -Path $binding.target -Value $binding.source | Out-Null }\n", 'utf-8')
        subprocess.run(['powershell', '-NoProfile', '-NonInteractive', '-File', str(helper), '-Plan', str(plan)], check=True, capture_output=True, creationflags=flags)
    (fixture / 'resource-manifest.json').write_text(json.dumps({'clientVersion': '4.6.0', 'testFixture': True, 'originalDailyQuest': 2100156, 'syntheticNoRewardPrerequisite': 8023701}), 'utf-8')
    assert not os.path.samefile(fixture / 'resource-manifest.json', source / 'resource-manifest.json')
    (directory / 'data').mkdir()
    rooms = json.loads((ROOT / 'src/main/resources/defaults/RogueMapGen.json').read_text('utf-8'))
    assert 122111 in rooms['1']
    rooms['1'] = [122111]
    (directory / 'data/RogueMapGen.json').write_text(json.dumps(rooms), 'utf-8')
    http, mongo, game = free_port(), free_port(), free_port(socket.SOCK_DGRAM)
    (directory / 'config.json').write_text(json.dumps({'protocol': {'wireVersion': '4.6.0'}, 'resourceDir': str(fixture),
        'serverOptions': {'startTrailblazerLevel': 10},
        'httpServer': {'bindAddress': '127.0.0.1', 'bindPort': http}, 'gameServer': {'bindAddress': '127.0.0.1', 'bindPort': game},
        'internalMongoServer': {'address': '127.0.0.1', 'port': mongo, 'filePath': './data/database.mv'}}), 'utf-8')
    helper_source = ROOT / 'scripts/fixtures/java/emu/lunarcore/command/commands/DailyCycleClockFixtureCommand.java'
    classes = directory / 'clock-fixture-classes'
    classes.mkdir()
    subprocess.run([str(java.with_name('javac.exe')), '-encoding', 'UTF-8', '-cp', str(jar), '-d', str(classes), str(helper_source)], check=True, capture_output=True, creationflags=flags)
    java_flags = ['-Dfile.encoding=UTF-8', '-Dstdout.encoding=UTF-8', '-Dstderr.encoding=UTF-8', '-Djdk.net.unixdomain.tmpdir=' + str(directory / 'tcp-fallback')]
    command = [str(java), *java_flags, '-Xmx1536m', '-cp', str(jar) + os.pathsep + str(classes), 'org.hkrpg.server.HkrpgServer', '--no-handbook']
    base = f'http://127.0.0.1:{http}'
    credentials = None
    snapshots, clock_views, clock_targets = [], [], []
    first_clock = next_clock = None
    stops = 0

    def request(route, body=None):
        req = urllib.request.Request(base + route, data=None if body is None else json.dumps(body).encode(), headers={'Content-Type': 'application/json'})
        with urllib.request.urlopen(req, timeout=5) as response:
            return json.load(response)

    def probe(phase, process, server_log):
        nonlocal first_clock, next_clock
        assert hashlib.sha256(jar.read_bytes()).hexdigest() == digest
        child = subprocess.Popen([str(java), *java_flags, '-cp', str(jar), 'org.hkrpg.server.protocol.LoopbackDailyCycleProbe', str(game)],
            stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, encoding='utf-8', creationflags=flags)
        lines, available = [], queue.Queue()

        def read_output():
            for line in child.stdout:
                lines.append(line)
                available.put(line)
            available.put(None)

        reader = threading.Thread(target=read_output, daemon=True)
        reader.start()
        try:
            child.stdin.write(json.dumps(dict(credentials, phase=phase)) + '\n')
            child.stdin.flush()
            deadline = time.monotonic() + 60
            while True:
                line = available.get(timeout=max(.1, deadline - time.monotonic()))
                assert line is not None, directory
                if line.startswith('HKRPG_CYCLE_CLOCK_FIXTURE_READY:'):
                    uid = int(line.strip().rsplit(':', 1)[1])
                    break
            if first_clock is None:
                first_clock = int(time.time()) + 1
                next_clock = ((first_clock + 14400) // 86400 + 1) * 86400 - 14400
                assert next_clock - first_clock > 300, 'Too close to a real daily reset for this fixture'
            target = next_clock if phase == 'nextday' else first_clock
            nonce = len(snapshots)
            marker = f'HKRPG_CYCLE_CLOCK_FIXTURE_ACK:{uid}:{target}:{nonce}'
            process.stdin.write(f'cycleclockfixture {uid} {target} {nonce}\n'.encode())
            process.stdin.flush()
            while marker not in server_log.read_text('utf-8', errors='replace'):
                assert process.poll() is None and child.poll() is None, directory
                if time.monotonic() > deadline:
                    raise TimeoutError(f'Cycle clock acknowledgment timeout: {directory}')
                time.sleep(.05)
            views = [line.split('HKRPG_CYCLE_CLOCK_FIXTURE_VIEW:', 1)[1] for line in server_log.read_text('utf-8', errors='replace').splitlines() if 'HKRPG_CYCLE_CLOCK_FIXTURE_VIEW:' in line]
            view = json.loads(views[-1])
            assert view['uid'] == uid and view['nonce'] == nonce and view['clock'] == target
            clock_views.append(view)
            clock_targets.append(target)
            child.stdin.write('resume\n')
            child.stdin.flush()
            child.stdin.close()
            child.wait(timeout=max(.1, deadline - time.monotonic()))
            reader.join(timeout=3)
            assert child.returncode == 0, directory
            result = json.loads(next(line for line in reversed(lines) if line.startswith('{')))
            assert result['passed'] and not result['clientGameplayVerified']
            result['clockFixtureEpochSecond'] = target
            snapshots.append(result)
        finally:
            if child.poll() is None:
                child.terminate()
                child.wait(timeout=10)
            if child.stdin and not child.stdin.closed:
                child.stdin.close()
            reader.join(timeout=3)
            (directory / f'probe-{len(snapshots)}-{phase}.log').write_text(''.join(lines), 'utf-8')

    for iteration, phases in enumerate([['pending', 'complete', 'unclaimed'], ['unclaimed', 'claim', 'claimed'], ['claimed', 'nextday']]):
        with (directory / f'server-{iteration}.log').open('w', encoding='utf-8') as log:
            process = subprocess.Popen(command, cwd=directory, stdin=subprocess.PIPE, stdout=log, stderr=subprocess.STDOUT, creationflags=flags)
            try:
                deadline = time.monotonic() + 90
                while True:
                    assert process.poll() is None, directory
                    try:
                        if request('/health/ready')['ready']:
                            break
                    except (OSError, urllib.error.URLError):
                        pass
                    if time.monotonic() > deadline:
                        raise TimeoutError(f'Cycle server startup timeout: {directory}')
                    time.sleep(.2)
                if iteration == 0:
                    login = request('/hkrpg_cn/mdk/shield/api/login', {'account': 'cn-46-daily-cycle', 'password': 'local-test'})
                    assert login['retcode'] == 0
                    account = login['data']['account']
                    combo = request('/hkrpg_cn/combo/granter/login/v2/login', {'data': json.dumps({'uid': account['uid'], 'token': account['token']})})
                    assert combo['retcode'] == 0
                    credentials = {'accountUid': account['uid'], 'comboToken': combo['data']['combo_token']}
                for phase in phases:
                    probe(phase, process, directory / f'server-{iteration}.log')
                time.sleep(1)
            finally:
                if process.poll() is None:
                    process.stdin.write(b'stop\n')
                    process.stdin.flush()
                    process.wait(timeout=20)
                    assert process.returncode == (1000 if os.name == 'nt' else 232), directory
                    stops += 1
                if process.stdin:
                    process.stdin.close()
    assert stops == 3 and len(snapshots) == 8
    assert len({s['uid'] for s in snapshots}) == 1
    assert sum(s['actualResourceBattles'] for s in snapshots) == 3
    assert snapshots[1]['completionNotifyBeforeResponse']
    assert clock_targets == [first_clock] * 7 + [next_clock]
    for name, expected in hashes.items():
        assert hashlib.sha256((source / name).read_bytes()).hexdigest() == expected, name
    print(json.dumps({'passed': True, 'runtimeDirectory': str(directory), 'snapshots': snapshots,
        'checks': [check for snapshot in snapshots for check in snapshot['checks']], 'sourceSHA256': hashes,
        'rootJarSHA256': digest, 'clockTargets': clock_targets, 'clockViews': clock_views, 'normalStops': stops,
        'normalH2Restarts': 2, 'realNativeSourceMonsterBattles': 3, 'originalQuestCycleScheduleAndRewardsUnmodified': True,
        'sourceResourcesModified': False, 'clockOnlyFixture': True, 'finishedStateOrRewardSeeded': False,
        'fixture': {'automaticNoRewardPrerequisite': 8023701, 'isolatedPool': 2100156, 'startLevel': 10,
                    'chosenExistingRoom': 122111, 'unmodifiedTerminalSourceMap': 1, 'originalMonsterGroups': [70, 71, 72]},
        'controlledNetworkResetVerified': True, 'realWallClockResetVerified': False, 'officialTutorialUnlockVerified': False,
        'crashAtomicityVerified': False, 'fullUniverseUIVerified': False, 'clientGameplayVerified': False}, ensure_ascii=False))


if __name__ == '__main__':
    main()
