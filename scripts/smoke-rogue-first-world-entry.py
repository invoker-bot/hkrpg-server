"""第一世界默认地图、原始加权敌人与正常 H2 恢复；仅前置使用隔离无奖励夹具。"""
import argparse
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
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--resources', type=Path, default=ROOT / 'resources/4.6')
    args = parser.parse_args()
    source = args.resources.resolve()
    jar = ROOT / 'build/libs/hkrpg-server.jar'
    java = next((ROOT / '.tools').glob('jdk-21*/bin/java.exe'), None)
    if java is None and os.environ.get('JAVA_HOME'):
        java = Path(os.environ['JAVA_HOME']) / 'bin/java.exe'
    classes = ROOT / 'build/classes/java/test'
    assert java and java.is_file() and jar.is_file()
    assert (classes / 'org/hkrpg/server/gameplay/RogueFirstWorldEntryFixtureInspector.class').is_file()
    digest = hashlib.sha256(jar.read_bytes()).hexdigest()
    runs = ROOT / 'build/smoke'
    runs.mkdir(exist_ok=True)
    directory = Path(tempfile.mkdtemp(prefix='rogue-first-world-entry-', dir=runs))
    assert directory.resolve().is_relative_to(runs.resolve())
    fixture = directory / 'resources-fixture'
    guarded = ['resource-manifest.json']
    guarded += [p.relative_to(source).as_posix() for p in (source / 'ExcelOutput').glob('*.json')]
    guarded += [p.relative_to(source).as_posix() for p in (source / 'Config/Level/Mission/4030006').glob('*.json')]
    guarded += ['Config/LevelOutput/RuntimeFloor/P80001_F80001003.json']
    guarded += [f'Config/LevelOutput/SharedRuntimeGroup/Groups_P80001_F80001003/LevelGroup_P80001_F80001003_G{g}.json' for g in [18, 19, 20, 21, 22, 23]]
    hashes = {name: hashlib.sha256((source / name).read_bytes()).hexdigest() for name in guarded}
    mains = json.loads((source / 'ExcelOutput/MainMission.json').read_text('utf-8'))
    prerequisites = [1000201, 4030001, 4030002, 1010403]
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
        'clientVersion': '4.6.0', 'testFixture': True, 'rogueFirstWorldEntryFixture': True,
        'syntheticNoRewardPrerequisites': prerequisites}), 'utf-8')
    data = directory / 'data'
    data.mkdir()
    # Leave both pool files absent: production DefaultData must install the actual defaults.
    assert not (data / 'RogueMapGen.json').exists()
    assert not (data / 'RogueMapGenByMap.json').exists()
    mapped_defaults = (ROOT / 'src/main/resources/defaults/RogueMapGenByMap.json').read_bytes()
    rooms = json.loads((source / 'ExcelOutput/RogueRoom.json').read_text('utf-8'))
    area = next(r for r in json.loads((source / 'ExcelOutput/RogueAreaConfig.json').read_text('utf-8')) if r['RogueAreaID'] == 110)
    source_map = [r for r in json.loads((source / 'ExcelOutput/RogueMap.json').read_text('utf-8')) if r['RogueMapID'] == 101]
    assert area['AreaProgress'] * 100 + area['Difficulty'] == 101 and area['UnlockID'] == 12001
    assert {r['SiteID'] for r in source_map} == set(range(1, 8))
    groups = json.loads((source / 'ExcelOutput/RogueMonsterGroup.json').read_text('utf-8'))
    monsters = json.loads((source / 'ExcelOutput/RogueMonster.json').read_text('utf-8'))
    stages = json.loads((source / 'ExcelOutput/StageConfig.json').read_text('utf-8'))
    first = next(r for r in rooms if r['RogueRoomID'] == 304)
    assert first['MapEntrance'] == 8000103 and first['RogueRoomType'] == 1
    actors = {}
    for group, content in first['GroupWithContent'].items():
        authored = json.loads((source / f'Config/LevelOutput/SharedRuntimeGroup/Groups_P80001_F80001003/LevelGroup_P80001_F80001003_G{group}.json').read_text('utf-8'))
        assert len(authored['MonsterList']) == 1
        weights = next(r['RogueMonsterListAndWeight'] for r in groups if r['RogueMonsterGroupID'] == content)
        choices = []
        for monster_id, weight in weights.items():
            assert weight > 0
            monster = next(r for r in monsters if r['RogueMonsterID'] == int(monster_id))
            stage = next(r for r in stages if r['StageID'] == monster['EventID'])
            choices.append({'monster': int(monster_id), 'npc': monster['NpcMonsterID'], 'stage': monster['EventID'],
                            'waves': [list(wave.values()) for wave in stage['MonsterList']]})
        actors[str(group)] = {'content': content, 'inst': authored['MonsterList'][0]['ID'], 'weights': weights, 'choices': choices}
    assert set(actors) == {'18', '19', '20'}
    (directory / 'actors.json').write_text(json.dumps(actors), 'utf-8')
    # The authored boss must agree with the first-world preview, rather than another world's boss.
    boss = next(r for r in rooms if r['RogueRoomID'] == 307)
    boss_weights = next(r['RogueMonsterListAndWeight'] for r in groups if r['RogueMonsterGroupID'] == boss['GroupWithContent']['23'])
    boss_rosters = []
    for monster_id in boss_weights:
        row = next(r for r in monsters if r['RogueMonsterID'] == int(monster_id))
        stage = next(r for r in stages if r['StageID'] == row['EventID'])
        boss_rosters += [int(n) for wave in stage['MonsterList'] for n in wave.values()]
    assert set(boss_rosters) == {int(n) for n in area['DisplayMonsterMap']}
    http, mongo, game = free_port(), free_port(), free_port(socket.SOCK_DGRAM)
    (directory / 'config.json').write_text(json.dumps({
        'protocol': {'wireVersion': '4.6.0'}, 'resourceDir': str(fixture),
        'httpServer': {'bindAddress': '127.0.0.1', 'bindPort': http},
        'gameServer': {'bindAddress': '127.0.0.1', 'bindPort': game},
        'internalMongoServer': {'address': '127.0.0.1', 'port': mongo, 'filePath': './data/database.mv'}}), 'utf-8')
    probe_classes = directory / 'probe-classes'
    probe_classes.mkdir()
    probe_source = ROOT / 'scripts/fixtures/java/org/hkrpg/server/protocol/NativeRogueFirstWorldEntryProbe.java'
    compilation = subprocess.run([str(java.with_name('javac.exe')), '-encoding', 'UTF-8', '-cp', str(jar),
                                  '-d', str(probe_classes), str(probe_source)], capture_output=True, creationflags=flags)
    (directory / 'javac.log').write_bytes(compilation.stdout + compilation.stderr)
    assert compilation.returncode == 0, directory / 'javac.log'
    fallback = '-Djdk.net.unixdomain.tmpdir=' + str(directory / 'tcp-fallback')
    encoding = ['-Dfile.encoding=UTF-8', '-Dstdout.encoding=UTF-8', '-Dstderr.encoding=UTF-8']
    command = [str(java), *encoding, '-Xmx1536m', fallback, '-jar', str(jar), '--no-handbook']

    def request(route, body=None):
        req = urllib.request.Request(f'http://127.0.0.1:{http}' + route,
            data=None if body is None else json.dumps(body).encode(), headers={'Content-Type': 'application/json'})
        with urllib.request.urlopen(req, timeout=5) as response:
            return json.load(response)

    snapshots, inspections, credentials, stops = [], [], None, 0
    def probe(mode):
        assert hashlib.sha256(jar.read_bytes()).hexdigest() == digest
        child = subprocess.run([str(java), *encoding, fallback, '-cp', str(jar) + os.pathsep + str(probe_classes),
            'org.hkrpg.server.protocol.NativeRogueFirstWorldEntryProbe', str(game)], cwd=directory,
            input=json.dumps(dict(credentials, mode=mode)) + '\n', capture_output=True,
            text=True, encoding='utf-8', errors='replace', timeout=90, creationflags=flags)
        log = directory / f'probe-{len(snapshots)}-{mode}.log'
        log.write_text(child.stdout + child.stderr, 'utf-8')
        assert child.returncode == 0, log
        snapshot = json.loads(next(line for line in child.stdout.splitlines() if line.startswith('{"uid":')))
        assert snapshot['passed'] and not snapshot['clientGameplayVerified']
        snapshots.append(snapshot)

    for iteration, mode in enumerate(['pending', 'start', 'resume']):
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
                probe(mode)
                if mode == 'start':
                    probe('resume')
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
        child = subprocess.run([str(java), *encoding, fallback, '-cp', str(classes) + os.pathsep + str(jar),
            'org.hkrpg.server.gameplay.RogueFirstWorldEntryFixtureInspector', str(directory),
            str(snapshots[0]['uid']), 'pending' if iteration == 0 else 'active'], cwd=ROOT,
            text=True, encoding='utf-8', capture_output=True, timeout=45, creationflags=flags)
        log = directory / f'inspector-{iteration}.log'
        log.write_text(child.stdout + child.stderr, 'utf-8')
        assert child.returncode == 0, log
        inspection = json.loads(next(line for line in reversed(child.stdout.splitlines()) if line.startswith('{')))
        assert inspection['passed'] and inspection['changedPlayers'] == 0
        inspections.append(inspection)
    assert stops == 3 and len(snapshots) == 4 and len(inspections) == 3
    assert all(s['uid'] == snapshots[0]['uid'] for s in snapshots)
    assert snapshots[1]['assignments'] == snapshots[2]['assignments'] == snapshots[3]['assignments']
    assert snapshots[1]['actors'] == snapshots[2]['actors'] == snapshots[3]['actors']
    assert inspections[1]['rooms'] == inspections[2]['rooms']
    assert inspections[1]['sourceChoices'] == inspections[2]['sourceChoices']
    assert inspections[1]['runId'] == inspections[2]['runId']
    assert (data / 'RogueMapGenByMap.json').read_bytes() == mapped_defaults
    assert hashes == {name: hashlib.sha256((source / name).read_bytes()).hexdigest() for name in guarded}
    print(json.dumps({'passed': True, 'runtimeDirectory': str(directory), 'rootJarSHA256': digest,
        'snapshots': snapshots, 'inspections': inspections, 'normalStops': stops,
        'sameUIDNormalH2ProcessRestarts': 2, 'sourceResourceHashes': hashes,
        'sourceResourcesModified': False, 'fixture': {'syntheticNoRewardPrerequisites': prerequisites,
            'firstRoomAssigned': 304, 'area': 110, 'sourceMap': 101,
            'mapScopedDefaultsInstalledByProduction': True, 'operatorPoolOverrides': False,
            'originalAreaRoomMapAndWeightedActorsUnchanged': True,
            'bossRosterMatchesOriginalWorldPreview': boss_rosters,
            'officialRoomGenerationVerified': False},
        'clientGameplayVerified': False, 'allSevenRoomsPlayed': False,
        'crashAtomicityVerified': False}, ensure_ascii=False))


if __name__ == '__main__':
    main()
