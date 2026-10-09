"""原始宇宙房间五场战斗、积分目标及正常 H2 恢复；前置由隔离无奖励夹具开放。"""
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
    assert (classes / 'org/hkrpg/server/gameplay/RogueExploreScoreFixtureInspector.class').is_file()
    digest = hashlib.sha256(jar.read_bytes()).hexdigest()
    runs = ROOT / 'build/smoke'
    runs.mkdir(exist_ok=True)
    directory = Path(tempfile.mkdtemp(prefix='rogue-explore-score-', dir=runs))
    assert directory.resolve().is_relative_to(runs.resolve())
    fixture = directory / 'resources-fixture'
    guarded = ['resource-manifest.json']
    guarded += [p.relative_to(source).as_posix() for p in (source / 'ExcelOutput').glob('*.json')]
    guarded += [p.relative_to(source).as_posix() for p in (source / 'Config/Level/Mission/4030006').glob('*.json')]
    guarded += ['Config/LevelOutput/RuntimeFloor/P80001_F80001001.json']
    guarded += [f'Config/LevelOutput/SharedRuntimeGroup/Groups_P80001_F80001001/LevelGroup_P80001_F80001001_G{g}.json' for g in [3, 4, 5, 10, 11, 12]]
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
        'clientVersion': '4.6.0', 'testFixture': True, 'rogueExploreScoreFixture': True,
        'syntheticNoRewardPrerequisites': prerequisites}), 'utf-8')
    data = directory / 'data'
    data.mkdir()
    pools = json.loads((ROOT / 'src/main/resources/defaults/RogueMapGen.json').read_text('utf-8'))
    pools['1'] = [100]
    # The shipped operator pool has branch-site keys; this source map is linear.
    # Supply its missing sites only in this owned fixture. No official generation is claimed.
    for linear, branch in [('2', '21'), ('3', '31'), ('7', '71')]:
        assert linear not in pools and pools[branch]
        pools[linear] = list(pools[branch])
    (data / 'RogueMapGen.json').write_text(json.dumps(pools), 'utf-8')
    # This historical score probe deliberately uses tutorial room 100 in world 1.
    # Explicitly override only this owned fixture's first site after map-scoped defaults exist.
    (data / 'RogueMapGenByMap.json').write_text(json.dumps({'maps': {'101': {'1': [100]}}}), 'utf-8')
    # This historical route has an elite as its last assigned room, not a source boss room.
    (data / 'RogueBossGen.json').write_text(json.dumps({'areas': {}}), 'utf-8')
    groups = json.loads((source / 'ExcelOutput/RogueMonsterGroup.json').read_text('utf-8'))
    monsters = json.loads((source / 'ExcelOutput/RogueMonster.json').read_text('utf-8'))
    stages = json.loads((source / 'ExcelOutput/StageConfig.json').read_text('utf-8'))
    actors = {}
    for group, content in [(3, 1001), (4, 1002), (5, 1003), (11, 1004), (12, 1005)]:
        weights = next(r['RogueMonsterListAndWeight'] for r in groups if r['RogueMonsterGroupID'] == content)
        assert len(weights) == 1 and next(iter(weights.values())) == 1
        monster = next(r for r in monsters if r['RogueMonsterID'] == int(next(iter(weights))))
        stage = next(r for r in stages if r['StageID'] == monster['EventID'])
        actors[str(group)] = {'npc': monster['NpcMonsterID'], 'stage': monster['EventID'],
                             'waves': [list(wave.values()) for wave in stage['MonsterList']]}
    http, mongo, game = free_port(), free_port(), free_port(socket.SOCK_DGRAM)
    (directory / 'config.json').write_text(json.dumps({
        'protocol': {'wireVersion': '4.6.0'}, 'resourceDir': str(fixture),
        'httpServer': {'bindAddress': '127.0.0.1', 'bindPort': http},
        'gameServer': {'bindAddress': '127.0.0.1', 'bindPort': game},
        'internalMongoServer': {'address': '127.0.0.1', 'port': mongo, 'filePath': './data/database.mv'}}), 'utf-8')
    probe_classes = directory / 'probe-classes'
    probe_classes.mkdir()
    probe_source = ROOT / 'scripts/fixtures/java/org/hkrpg/server/protocol/NativeRogueExploreScoreProbe.java'
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
            'org.hkrpg.server.protocol.NativeRogueExploreScoreProbe', str(game)], cwd=directory,
            input=json.dumps(dict(credentials, mode=mode)) + '\n', capture_output=True,
            text=True, encoding='utf-8', errors='replace', timeout=90, creationflags=flags)
        log = directory / f'probe-{len(snapshots)}-{mode}.log'
        log.write_text(child.stdout + child.stderr, 'utf-8')
        assert child.returncode == 0, log
        snapshot = json.loads(next(line for line in child.stdout.splitlines() if line.startswith('{"uid":')))
        assert snapshot['passed'] and not snapshot['clientGameplayVerified']
        snapshots.append(snapshot)
        if mode == 'complete':
            assert snapshot['credits'] == snapshot['battleDroppedCredits']
            credentials['expectedCredits'] = snapshot['credits']

    for iteration, mode in enumerate(['pending', 'complete', 'resume']):
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
                    login = request('/hkrpg_cn/mdk/shield/api/login', {'account': 'cn-46-explore-score', 'password': 'local-test'})
                    assert login['retcode'] == 0
                    account = login['data']['account']
                    combo = request('/hkrpg_cn/combo/granter/login/v2/login', {
                        'data': json.dumps({'uid': account['uid'], 'token': account['token']})})
                    assert combo['retcode'] == 0
                    credentials = {'accountUid': account['uid'], 'comboToken': combo['data']['combo_token'], 'actors': actors}
                probe(mode)
                if mode == 'complete':
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
            'org.hkrpg.server.gameplay.RogueExploreScoreFixtureInspector', str(directory),
            str(snapshots[0]['uid']), 'pending' if iteration == 0 else 'finished',
            str(credentials.get('expectedCredits', 0))], cwd=ROOT,
            text=True, encoding='utf-8', capture_output=True, timeout=45, creationflags=flags)
        log = directory / f'inspector-{iteration}.log'
        log.write_text(child.stdout + child.stderr, 'utf-8')
        assert child.returncode == 0, log
        inspection = json.loads(next(line for line in reversed(child.stdout.splitlines()) if line.startswith('{')))
        assert inspection['passed'] and inspection['changedPlayers'] == 0
        inspections.append(inspection)
    assert stops == 3 and len(snapshots) == 4 and len(inspections) == 3
    assert all(s['uid'] == snapshots[0]['uid'] for s in snapshots)
    assert sum(s['battles'] for s in snapshots) == sum(s['removedSourceActors'] for s in snapshots) == 5
    assert sum(s['scoreNotifications'] for s in snapshots) == 1
    assert inspections[1]['rogueResults'] == inspections[2]['rogueResults']
    assert inspections[1]['cycleWeeks'] == inspections[2]['cycleWeeks']
    assert hashes == {name: hashlib.sha256((source / name).read_bytes()).hexdigest() for name in guarded}
    print(json.dumps({'passed': True, 'runtimeDirectory': str(directory), 'rootJarSHA256': digest,
        'snapshots': snapshots, 'inspections': inspections, 'normalStops': stops,
        'sameUIDNormalH2ProcessRestarts': 2, 'sourceResourceHashes': hashes,
        'sourceResourcesModified': False, 'fixture': {'syntheticNoRewardPrerequisites': prerequisites,
            'firstRoomAssigned': 100, 'area': 110, 'originalTargetActorsRostersScoreAndRewardUnchanged': True,
            'operatorPoolLinearAliases': {'2': '21', '3': '31', '7': '71'},
            'fixtureMapScopedFirstSiteOverride': {'101': {'1': [100]}},
            'fixtureBossBindingDisabledForHistoricalRoute': True,
            'missionProgressOrScoreSeeded': False, 'officialRoomGenerationVerified': False},
        'clientGameplayVerified': False, 'combatInputReplayVerified': False, 'crashAtomicityVerified': False}, ensure_ascii=False))


if __name__ == '__main__':
    main()
