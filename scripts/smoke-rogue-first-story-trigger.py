"""首次剧情教学宇宙房间、六组敌人与正常 H2 恢复；仅前置使用隔离无奖励夹具。"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import socket
import subprocess
import tempfile
import time
import threading
import queue
import shutil
import urllib.error
import urllib.request

from smoke_resource_fixture import mirror_resources

ROOT = Path(__file__).resolve().parents[1]


def free_port(kind=socket.SOCK_STREAM):
    with socket.socket(type=kind) as sock:
        sock.bind(('127.0.0.1', 0))
        return sock.getsockname()[1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--resources', type=Path, default=ROOT / 'resources/4.6')
    parser.add_argument('--legacy-recovery', action='store_true',
                        help='隔离夹具先模拟旧版错误出生点，再验证同局重登恢复')
    args = parser.parse_args()
    source = args.resources.resolve()
    jar = ROOT / 'build/libs/hkrpg-server.jar'
    java = next((ROOT / '.tools').glob('jdk-21*/bin/java.exe'), None)
    if java is None and os.environ.get('JAVA_HOME'):
        java = Path(os.environ['JAVA_HOME']) / 'bin/java.exe'
    assert java and java.is_file() and jar.is_file()
    digest = hashlib.sha256(jar.read_bytes()).hexdigest()
    runs = ROOT / 'build/smoke'
    runs.mkdir(exist_ok=True)
    directory = Path(tempfile.mkdtemp(prefix='rogue-first-story-trigger-', dir=runs))
    assert directory.resolve().is_relative_to(runs.resolve())
    fixture = directory / 'resources-fixture'
    guarded = ['resource-manifest.json']
    guarded += [p.relative_to(source).as_posix() for p in (source / 'ExcelOutput').glob('*.json')]
    guarded += [p.relative_to(source).as_posix() for p in (source / 'Config/Level/Mission/4030006').glob('*.json')]
    guarded += [p.relative_to(source).as_posix() for p in (source / 'Config/Level/Mission/4030001').rglob('*') if p.is_file()]
    guarded += ['Config/LevelOutput/RuntimeFloor/P80112_F80112001.json',
                'Config/LevelOutput/SharedRuntimeGroup/Groups_P80112_F80112001/LevelGroup_P80112_F80112001_G18.json']
    guarded += ['Config/LevelOutput/RuntimeFloor/P80001_F80001001.json']
    guarded += [f'Config/LevelOutput/SharedRuntimeGroup/Groups_P80001_F80001001/LevelGroup_P80001_F80001001_G{g}.json' for g in [3, 4, 5, 10, 11, 12]]
    hashes = {name: hashlib.sha256((source / name).read_bytes()).hexdigest() for name in guarded}
    mains = json.loads((source / 'ExcelOutput/MainMission.json').read_text('utf-8'))
    prerequisites = [1000201, 4030002, 1010403]
    for row in mains:
        if row['MainMissionID'] in prerequisites:
            row.update(TakeOperation='And', TakeParam=[{'Type': 'Auto'}], BeginOperation='And',
                       BeginParam=[{'Type': 'Auto'}], RewardID=0, SubRewardList=[])
    original_target = next(row for row in mains if row['MainMissionID'] == 4030006)
    assert original_target['RewardID'] == 100 and original_target['TakeParam'] == [{'Type': 'MultiSequence', 'Value': 4030002}]
    junctions = mirror_resources(source, fixture, {'ExcelOutput/MainMission.json': mains})
    for main_id in prerequisites:
        folder = fixture / f'Config/Level/Mission/{main_id}'
        folder.mkdir(parents=True)
        sub = main_id * 100 + 99
        (folder / f'MissionInfo_{main_id}.json').write_text(json.dumps({
            'MainMissionID': main_id, 'StartSubMissionList': [sub], 'FinishSubMissionList': [sub]}), 'utf-8')
        (folder / f'MissionInfo_{main_id}.partial.json').write_text(json.dumps({'SubMissionList': [{
            'ID': sub, 'MainMissionID': main_id, 'TakeType': 'Auto', 'FinishType': 'Auto', 'Progress': 1}]}), 'utf-8')
    # The first story and its entry trigger remain the complete original definitions.
    shutil.copytree(source / 'Config/Level/Mission/4030001', fixture / 'Config/Level/Mission/4030001')
    folder = fixture / 'Config/Level/Mission/4030006'
    folder.mkdir(parents=True)
    for original in (source / 'Config/Level/Mission/4030006').glob('*.json'):
        (folder / original.name).write_bytes(original.read_bytes())
    flags = subprocess.CREATE_NO_WINDOW if os.name == 'nt' else 0
    if junctions:
        plan = directory / 'junction-plan.json'
        plan.write_text(json.dumps(junctions), 'utf-8')
        helper = directory / 'prepare-fixture.ps1'
        helper.write_text("param([string]$Plan)\n$ErrorActionPreference='Stop'\nforeach ($binding in (Get-Content -LiteralPath $Plan -Raw | ConvertFrom-Json)) { New-Item -ItemType Junction -Path $binding.target -Value $binding.source | Out-Null }\n", 'utf-8')
        subprocess.run(['powershell', '-NoProfile', '-NonInteractive', '-File', str(helper), '-Plan', str(plan)],
                       check=True, capture_output=True, creationflags=flags)
    (fixture / 'resource-manifest.json').write_text(json.dumps({
        'clientVersion': '4.6.0', 'testFixture': True, 'rogueFirstStoryTriggerFixture': True,
        'syntheticNoRewardPrerequisites': prerequisites}), 'utf-8')
    data = directory / 'data'
    data.mkdir()
    # Leave both pool files absent: production DefaultData must install the actual defaults.
    assert not (data / 'RogueMapGen.json').exists()
    assert not (data / 'RogueMapGenByMap.json').exists()
    mapped_defaults = (ROOT / 'src/main/resources/defaults/RogueMapGenByMap.json').read_bytes()
    rooms = json.loads((source / 'ExcelOutput/RogueRoom.json').read_text('utf-8'))
    area = next(r for r in json.loads((source / 'ExcelOutput/RogueAreaConfig.json').read_text('utf-8')) if r['RogueAreaID'] == 100)
    source_map = [r for r in json.loads((source / 'ExcelOutput/RogueMap.json').read_text('utf-8')) if r['RogueMapID'] == 1]
    assert area.get('AreaProgress', 0) * 100 + area['Difficulty'] == 1 and area.get('UnlockID', 0) == 0
    assert {r['SiteID'] for r in source_map} == {1}
    groups = json.loads((source / 'ExcelOutput/RogueMonsterGroup.json').read_text('utf-8'))
    monsters = json.loads((source / 'ExcelOutput/RogueMonster.json').read_text('utf-8'))
    stages = json.loads((source / 'ExcelOutput/StageConfig.json').read_text('utf-8'))
    first = next(r for r in rooms if r['RogueRoomID'] == 100)
    assert first['MapEntrance'] == 8000101 and first['RogueRoomType'] == 1
    actors = {}
    for group, content in first['GroupWithContent'].items():
        # 4.6 omits 1006; the level-seven Trampler battle is present as 1101.
        if group == '10':
            assert content == 1006 and not any(r['RogueMonsterGroupID'] == 1006 for r in groups)
            content = 1101
        authored = json.loads((source / f'Config/LevelOutput/SharedRuntimeGroup/Groups_P80001_F80001001/LevelGroup_P80001_F80001001_G{group}.json').read_text('utf-8'))
        assert len(authored['MonsterList']) == 1
        weights = next(r['RogueMonsterListAndWeight'] for r in groups if r['RogueMonsterGroupID'] == content)
        choices = []
        for monster_id, weight in weights.items():
            assert weight > 0
            monster = next(r for r in monsters if r['RogueMonsterID'] == int(monster_id))
            stage = next(r for r in stages if r['StageID'] == monster['EventID'])
            choices.append({'monster': int(monster_id), 'npc': monster['NpcMonsterID'], 'stage': monster['EventID'],
                            'waves': [list(wave.values()) for wave in stage['MonsterList']]})
        placement = authored['MonsterList'][0]
        actors[str(group)] = {'content': content, 'inst': placement['ID'], 'weights': weights,
                              'pos': {axis: int(placement.get('Pos' + axis.upper(), 0) * 1000)
                                      for axis in ('x', 'y', 'z')}, 'choices': choices}
    assert set(actors) == {'3', '4', '5', '10', '11', '12'}
    (directory / 'actors.json').write_text(json.dumps(actors), 'utf-8')
    http, mongo, game = free_port(), free_port(), free_port(socket.SOCK_DGRAM)
    (directory / 'config.json').write_text(json.dumps({
        'protocol': {'wireVersion': '4.6.0'}, 'resourceDir': str(fixture),
        'httpServer': {'bindAddress': '127.0.0.1', 'bindPort': http},
        'gameServer': {'bindAddress': '127.0.0.1', 'bindPort': game},
        'internalMongoServer': {'address': '127.0.0.1', 'port': mongo, 'filePath': './data/database.mv'}}), 'utf-8')
    probe_classes = directory / 'probe-classes'
    probe_classes.mkdir()
    probe_source = ROOT / 'scripts/fixtures/java/org/hkrpg/server/protocol/NativeRogueFirstStoryTriggerProbe.java'
    fixture_source = ROOT / 'scripts/fixtures/java/emu/lunarcore/command/commands/RogueFirstStoryFixtureCommand.java'
    inspector_source = ROOT / 'src/test/java/org/hkrpg/server/gameplay/RogueFirstStoryEntryFixtureInspector.java'
    compilation = subprocess.run([str(java.with_name('javac.exe')), '-encoding', 'UTF-8', '-cp', str(jar),
                                  '-d', str(probe_classes), str(probe_source), str(fixture_source), str(inspector_source)], capture_output=True, creationflags=flags)
    (directory / 'javac.log').write_bytes(compilation.stdout + compilation.stderr)
    assert compilation.returncode == 0, directory / 'javac.log'
    fallback = '-Djdk.net.unixdomain.tmpdir=' + str(directory / 'tcp-fallback')
    encoding = ['-Dfile.encoding=UTF-8', '-Dstdout.encoding=UTF-8', '-Dstderr.encoding=UTF-8']
    command = [str(java), *encoding, '-Xmx1536m', fallback, '-cp', str(jar) + os.pathsep + str(probe_classes), 'org.hkrpg.server.HkrpgServer', '--no-handbook']

    def request(route, body=None):
        req = urllib.request.Request(f'http://127.0.0.1:{http}' + route,
            data=None if body is None else json.dumps(body).encode(), headers={'Content-Type': 'application/json'})
        with urllib.request.urlopen(req, timeout=5) as response:
            return json.load(response)

    snapshots, inspections, credentials, stops = [], [], None, 0
    def probe(mode, server, server_log):
        assert hashlib.sha256(jar.read_bytes()).hexdigest() == digest
        child = subprocess.Popen([str(java), *encoding, fallback, '-cp', str(jar) + os.pathsep + str(probe_classes),
            'org.hkrpg.server.protocol.NativeRogueFirstStoryTriggerProbe', str(game)], cwd=directory,
            stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
            text=True, encoding='utf-8', errors='replace', creationflags=flags)
        lines, available = [], queue.Queue()
        def collect():
            for line in child.stdout:
                lines.append(line)
                available.put(line)
            available.put(None)
        reader = threading.Thread(target=collect, daemon=True)
        reader.start()
        try:
            child.stdin.write(json.dumps(dict(credentials, mode=mode,
                                              simulateLegacy=args.legacy_recovery and mode == 'trigger')) + '\n')
            child.stdin.flush()
            deadline = time.monotonic() + 75
            if mode == 'trigger':
                while True:
                    line = available.get(timeout=max(.1, deadline - time.monotonic()))
                    assert line is not None, directory
                    if line.startswith('HKRPG_ROGUE_FIRST_STORY_FIXTURE_READY:'):
                        uid = int(line.strip().rsplit(':', 1)[1]); break
                nonce = 1781
                marker = f'HKRPG_ROGUE_FIRST_STORY_FIXTURE_ACK:{uid}:{nonce}'
                server.stdin.write(f'roguefirststoryfixture {uid} {nonce}\n'.encode())
                server.stdin.flush()
                while marker not in server_log.read_text('utf-8', errors='replace'):
                    assert server.poll() is None and child.poll() is None, directory
                    if time.monotonic() > deadline: raise TimeoutError('Isolated story setup did not acknowledge')
                    time.sleep(.1)
                child.stdin.write('resume\n'); child.stdin.flush()
                if args.legacy_recovery:
                    while True:
                        line = available.get(timeout=max(.1, deadline - time.monotonic()))
                        assert line is not None, directory
                        if line.startswith('HKRPG_ROGUE_FIRST_STORY_LEGACY_READY:'):
                            break
                    legacy_marker = f'HKRPG_ROGUE_FIRST_STORY_LEGACY_ACK:{uid}:{nonce}'
                    server.stdin.write(f'roguefirststoryfixture {uid} {nonce} legacy\n'.encode())
                    server.stdin.flush()
                    while legacy_marker not in server_log.read_text('utf-8', errors='replace'):
                        assert server.poll() is None and child.poll() is None, directory
                        if time.monotonic() > deadline: raise TimeoutError('Legacy fixture was not acknowledged')
                        time.sleep(.1)
                    child.stdin.write('legacy-ready\n'); child.stdin.flush()
            child.stdin.close()
            child.wait(timeout=max(.1, deadline - time.monotonic()))
            reader.join(timeout=3)
            (directory / f'probe-{len(snapshots)}-{mode}.log').write_text(''.join(lines), 'utf-8')
            assert child.returncode == 0, directory / f'probe-{len(snapshots)}-{mode}.log'
            snapshot = json.loads(next(line for line in lines if line.startswith('{"uid":')))
            assert snapshot['passed'] and not snapshot['clientGameplayVerified']
            if mode == 'trigger':
                assert snapshot['serverTransferNotified'] and not snapshot['startRequestSent']
            snapshots.append(snapshot)
        finally:
            if child.poll() is None: child.terminate(); child.wait(timeout=10)
            if child.stdin and not child.stdin.closed: child.stdin.close()
            reader.join(timeout=3)

    for iteration, mode in enumerate(['trigger', 'resume', 'resume']):
        with (directory / f'server-{iteration}.log').open('w', encoding='utf-8') as log:
            process = subprocess.Popen(command, cwd=directory, stdin=subprocess.PIPE,
                stdout=log, stderr=subprocess.STDOUT, creationflags=flags)
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
                        raise RuntimeError('Startup timed out: ' + str(directory))
                    time.sleep(.2)
                if iteration == 0:
                    login = request('/hkrpg_cn/mdk/shield/api/login', {'account': 'cn-46-first-world-entry', 'password': 'local-test'})
                    assert login['retcode'] == 0
                    account = login['data']['account']
                    combo = request('/hkrpg_cn/combo/granter/login/v2/login', {
                        'data': json.dumps({'uid': account['uid'], 'token': account['token']})})
                    assert combo['retcode'] == 0
                    credentials = {'accountUid': account['uid'], 'comboToken': combo['data']['combo_token'], 'actors': actors}
                assert (data / 'RogueMapGenByMap.json').read_bytes() == mapped_defaults
                probe(mode, process, directory / f'server-{iteration}.log')
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
        child = subprocess.run([str(java), *encoding, fallback, '-cp', str(probe_classes) + os.pathsep + str(jar),
            'org.hkrpg.server.gameplay.RogueFirstStoryEntryFixtureInspector', str(directory),
            str(snapshots[0]['uid']), 'legacy' if args.legacy_recovery and iteration == 0 else 'active'], cwd=ROOT,
            text=True, encoding='utf-8', capture_output=True, timeout=45, creationflags=flags)
        log = directory / f'inspector-{iteration}.log'
        log.write_text(child.stdout + child.stderr, 'utf-8')
        assert child.returncode == 0, log
        inspection = json.loads(next(line for line in reversed(child.stdout.splitlines()) if line.startswith('{')))
        assert inspection['passed'] and inspection['changedPlayers'] == 0
        assert inspection['enterMission'] == 'FINISHED'
        inspections.append(inspection)
    assert stops == 3 and len(snapshots) == 3 and len(inspections) == 3
    assert all(s['uid'] == snapshots[0]['uid'] for s in snapshots)
    assert snapshots[0]['assignments'] == snapshots[1]['assignments'] == snapshots[2]['assignments']
    assert snapshots[0]['actors'] == snapshots[1]['actors'] == snapshots[2]['actors']
    assert inspections[0]['runId'] == inspections[1]['runId'] == inspections[2]['runId']
    assert inspections[0]['sourceChoices'] == inspections[1]['sourceChoices'] == inspections[2]['sourceChoices']
    assert (data / 'RogueMapGenByMap.json').read_bytes() == mapped_defaults
    assert hashes == {name: hashlib.sha256((source / name).read_bytes()).hexdigest() for name in guarded}
    print(json.dumps({'passed': True, 'runtimeDirectory': str(directory), 'rootJarSHA256': digest,
        'snapshots': snapshots, 'inspections': inspections, 'normalStops': stops,
        'sameUIDNormalH2ProcessRestarts': 2, 'sourceResourceHashes': hashes,
        'sourceResourcesModified': False, 'fixture': {'syntheticNoRewardPrerequisites': prerequisites,
            'firstRoomAssigned': 100, 'area': 100, 'sourceMap': 1,
            'mapScopedDefaultsInstalledByProduction': True, 'operatorPoolOverrides': False,
            'originalAreaRoomMapAndWeightedActorsUnchanged': True,
            'legacySpawnSimulatedInIsolatedFixture': args.legacy_recovery,
            'missingBossGroupCompatibility': {'1006': 1101},
            'officialRoomGenerationVerified': False},
        'clientGameplayVerified': False, 'storyTriggerNetworkVerified': True,
        'crashAtomicityVerified': False}, ensure_ascii=False))


if __name__ == '__main__':
    main()
