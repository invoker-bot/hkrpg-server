"""默认七站、原始40398祝福、本服配方与同UID正常H2恢复；前置仅用隔离无奖励夹具。"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import socket
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.request

from smoke_resource_fixture import mirror_resources

ROOT = Path(__file__).resolve().parents[1]
PIN = '724b139d8c9c32d12552eb95745a4fee72bfe48b'
EVENT = {'Config/Level/Rogue/RogueNPC/RogueNPC40398.json': 'fa21d43c722cfcb21030f761ed97c61a8b287c006d9e727c193e722d388fbb2a',
         'Config/Level/Rogue/RogueDialogue/Event40398/Opt40398.json': 'f3201fa896d601aa5f009ebc4df7f46228073023211179fb4ab02c58bb65a02c',
         'Config/Level/Rogue/RogueDialogue/Event40398/Act40398.json': '65ab43311653fcaaa795554e2341d4fd5377b9cf928c70f42a58c221c738f9de'}


def free_port(kind=socket.SOCK_STREAM):
    with socket.socket(type=kind) as sock:
        sock.bind(('127.0.0.1', 0))
        return sock.getsockname()[1]


def main():
    if hasattr(sys.stdout, 'reconfigure'):
        sys.stdout.reconfigure(encoding='utf-8')
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--resources', type=Path, default=ROOT / 'resources/4.6')
    parser.add_argument('--jar', type=Path, default=ROOT / 'build/libs/hkrpg-server.jar')
    parser.add_argument('--output', type=Path, help='另存 UTF-8 JSON 验证报告')
    args = parser.parse_args()
    source = args.resources.resolve()
    jar = args.jar.resolve()
    java = next((ROOT / '.tools').glob('jdk-21*/bin/java.exe'), None)
    if java is None and os.environ.get('JAVA_HOME'):
        java = Path(os.environ['JAVA_HOME']) / 'bin/java.exe'
    assert java and java.is_file() and jar.is_file()
    digest = hashlib.sha256(jar.read_bytes()).hexdigest()
    runs = ROOT / 'build/smoke'
    runs.mkdir(exist_ok=True)
    directory = Path(tempfile.mkdtemp(prefix='rogue-first-world-closure-', dir=runs))
    assert directory.resolve().is_relative_to(runs.resolve())
    fixture = directory / 'resources-fixture'
    guarded = ['resource-manifest.json', *EVENT]
    guarded += [p.relative_to(source).as_posix() for p in (source / 'ExcelOutput').glob('*.json')]
    guarded += [p.relative_to(source).as_posix() for p in (source / 'Config/Level/Mission/4030006').glob('*.json')]
    for floor, groups in {'80001002': [34, 35], '80001003': [18, 19, 20, 21, 22, 23]}.items():
        guarded += [f'Config/LevelOutput/RuntimeFloor/P80001_F{floor}.json']
        guarded += [f'Config/LevelOutput/SharedRuntimeGroup/Groups_P80001_F{floor}/LevelGroup_P80001_F{floor}_G{g}.json' for g in groups]
    hashes = {name: hashlib.sha256((source / name).read_bytes()).hexdigest() for name in guarded}
    for name, sha in EVENT.items():
        assert hashlib.sha256((source / name).read_bytes().replace(b'\r\n', b'\n')).hexdigest() == sha
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
        'clientVersion': '4.6.0', 'testFixture': True, 'rogueFirstWorldClosureFixture': True,
        'sourceCommit': PIN, 'syntheticNoRewardPrerequisites': prerequisites}), 'utf-8')
    data = directory / 'data'
    data.mkdir()
    defaults = {name: (ROOT / f'src/main/resources/defaults/{name}').read_bytes()
                for name in ['RogueMapGen.json', 'RogueMapGenByMap.json', 'RogueDialogueRewards.json']}
    assert all(not (data / name).exists() for name in defaults)
    rooms = json.loads((source / 'ExcelOutput/RogueRoom.json').read_text('utf-8'))
    entries = {r['ID']: r for r in json.loads((source / 'ExcelOutput/MapEntrance.json').read_text('utf-8'))}
    area = next(r for r in json.loads((source / 'ExcelOutput/RogueAreaConfig.json').read_text('utf-8')) if r['RogueAreaID'] == 110)
    source_map = [r for r in json.loads((source / 'ExcelOutput/RogueMap.json').read_text('utf-8')) if r['RogueMapID'] == 101]
    assert area['AreaProgress'] * 100 + area['Difficulty'] == 101 and area['UnlockID'] == 12001
    assert {r['SiteID'] for r in source_map} == set(range(1, 8))
    groups = json.loads((source / 'ExcelOutput/RogueMonsterGroup.json').read_text('utf-8'))
    monsters = json.loads((source / 'ExcelOutput/RogueMonster.json').read_text('utf-8'))
    stages = json.loads((source / 'ExcelOutput/StageConfig.json').read_text('utf-8'))
    actors = {}
    for room_id in [302, 304, 307]:
        room = next(r for r in rooms if r['RogueRoomID'] == room_id)
        floor = entries[room['MapEntrance']]['FloorID']
        actors[str(room_id)] = {}
        for group, content in room['GroupWithContent'].items():
            placement = json.loads((source / f'Config/LevelOutput/SharedRuntimeGroup/Groups_P80001_F{floor}/LevelGroup_P80001_F{floor}_G{group}.json').read_text('utf-8'))
            assert len(placement['MonsterList']) == 1
            weights = next(r['RogueMonsterListAndWeight'] for r in groups if r['RogueMonsterGroupID'] == content)
            choices = []
            for monster_id, weight in weights.items():
                assert weight > 0
                monster = next(r for r in monsters if r['RogueMonsterID'] == int(monster_id))
                stage = next(r for r in stages if r['StageID'] == monster['EventID'])
                choices.append({'monster': int(monster_id), 'npc': monster['NpcMonsterID'], 'stage': monster['EventID'],
                                'waves': [list(wave.values()) for wave in stage['MonsterList']]})
            actors[str(room_id)][str(group)] = {'content': content, 'inst': placement['MonsterList'][0]['ID'],
                                               'weights': weights, 'choices': choices}
    boss_roster = {n for actor in actors['307'].values() for choice in actor['choices'] for wave in choice['waves'] for n in wave}
    assert boss_roster == {int(n) for n in area['DisplayMonsterMap']}
    (directory / 'actors.json').write_text(json.dumps(actors), 'utf-8')
    http, mongo, game = free_port(), free_port(), free_port(socket.SOCK_DGRAM)
    configuration = directory / 'config.json'
    configuration.write_text(json.dumps({
        'protocol': {'wireVersion': '4.6.0'}, 'resourceDir': str(fixture),
        'httpServer': {'bindAddress': '127.0.0.1', 'bindPort': http},
        'gameServer': {'bindAddress': '127.0.0.1', 'bindPort': game},
        'internalMongoServer': {'address': '127.0.0.1', 'port': mongo, 'filePath': './data/database.mv'}}), 'utf-8')
    config_digest = hashlib.sha256(configuration.read_bytes()).hexdigest()

    def unchanged_runtime_inputs():
        assert hashlib.sha256(jar.read_bytes()).hexdigest() == digest, 'Frozen jar changed'
        assert hashlib.sha256(configuration.read_bytes()).hexdigest() == config_digest, 'Isolated config changed'

    unchanged_runtime_inputs()
    probe_classes = directory / 'probe-classes'
    probe_classes.mkdir()
    sources = [ROOT / 'scripts/fixtures/java/org/hkrpg/server/protocol/NativeRogueFirstWorldClosureProbe.java',
               ROOT / 'scripts/fixtures/java/org/hkrpg/server/gameplay/RogueFirstWorldClosureFixtureInspector.java']
    compilation = subprocess.run([str(java.with_name('javac.exe')), '-encoding', 'UTF-8', '-cp', str(jar),
                                  '-d', str(probe_classes), *map(str, sources)], capture_output=True, creationflags=flags)
    (directory / 'javac.log').write_bytes(compilation.stdout + compilation.stderr)
    assert compilation.returncode == 0, directory / 'javac.log'
    fallback = '-Djdk.net.unixdomain.tmpdir=' + str(directory / 'tcp-fallback')
    encoding = ['-Dfile.encoding=UTF-8', '-Dstdout.encoding=UTF-8', '-Dstderr.encoding=UTF-8']
    command = [str(java), *encoding, '-Xmx1536m', fallback, '-jar', str(jar), '--no-handbook']
    snapshots, inspections, credentials, stops = [], [], None, 0
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))

    def request(route, body=None):
        req = urllib.request.Request(f'http://127.0.0.1:{http}' + route,
            data=None if body is None else json.dumps(body).encode(), headers={'Content-Type': 'application/json'})
        with opener.open(req, timeout=5) as response:
            return json.load(response)

    def probe(mode):
        unchanged_runtime_inputs()
        child = subprocess.run([str(java), *encoding, fallback, '-cp', str(jar) + os.pathsep + str(probe_classes),
            'org.hkrpg.server.protocol.NativeRogueFirstWorldClosureProbe', str(game)], cwd=directory,
            input=json.dumps(dict(credentials, mode=mode)) + '\n', capture_output=True,
            text=True, encoding='utf-8', errors='replace', timeout=150, creationflags=flags)
        log = directory / f'probe-{len(snapshots)}-{mode}.log'
        log.write_text(child.stdout + child.stderr, 'utf-8')
        assert child.returncode == 0, log
        snapshot = json.loads(next(line for line in child.stdout.splitlines() if line.startswith('{"uid":')))
        assert snapshot['passed'] and not snapshot['clientGameplayVerified']
        unchanged_runtime_inputs()
        snapshot.update(runtimeJarSHA256=digest, isolatedConfigSHA256=config_digest)
        snapshots.append(snapshot)
        credentials.update({key: snapshot[key] for key in ['unique', 'batch', 'buff', 'coins']})

    for iteration, mode in enumerate(['start', 'resume', 'finished']):
        unchanged_runtime_inputs()
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
                    login = request('/hkrpg_cn/mdk/shield/api/login', {'account': 'cn-46-seven-sites', 'password': 'local-test'})
                    assert login['retcode'] == 0
                    account = login['data']['account']
                    combo = request('/hkrpg_cn/combo/granter/login/v2/login', {
                        'data': json.dumps({'uid': account['uid'], 'token': account['token']})})
                    assert combo['retcode'] == 0
                    credentials = {'accountUid': account['uid'], 'comboToken': combo['data']['combo_token'], 'actors': actors}
                assert all((data / name).read_bytes() == raw for name, raw in defaults.items())
                probe(mode)
                if mode == 'start':
                    probe('pending')
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
        child = subprocess.run([str(java), *encoding, fallback, '-cp', str(jar) + os.pathsep + str(probe_classes),
            'org.hkrpg.server.gameplay.RogueFirstWorldClosureFixtureInspector', str(directory),
            str(snapshots[0]['uid']), 'pending' if iteration == 0 else 'finished'], cwd=ROOT,
            text=True, encoding='utf-8', capture_output=True, timeout=45, creationflags=flags)
        log = directory / f'inspector-{iteration}.log'
        log.write_text(child.stdout + child.stderr, 'utf-8')
        assert child.returncode == 0, log
        inspection = json.loads(next(line for line in reversed(child.stdout.splitlines()) if line.startswith('{')))
        assert inspection['passed'] and inspection['changedPlayers'] == 0
        unchanged_runtime_inputs()
        inspections.append(inspection)
    assert stops == 3 and len(snapshots) == 4 and len(inspections) == 3
    assert all(s['uid'] == snapshots[0]['uid'] and s['unique'] == snapshots[0]['unique']
               and s['batch'] == snapshots[0]['batch'] and s['buff'] == snapshots[0]['buff'] for s in snapshots)
    assert snapshots[0]['blessings'] == snapshots[1]['blessings']
    assert set(snapshots[2]['blessings']) == set(snapshots[0]['blessings']) | {snapshots[0]['buff']}
    assert snapshots[2]['blessings'] == snapshots[3]['blessings']
    assert snapshots[2]['currentCoins'] == snapshots[3]['currentCoins']
    assert snapshots[2]['credits'] == snapshots[3]['credits']
    assert len(snapshots[0]['encounters']) == 7 and len(snapshots[2]['encounters']) == 1
    assert inspections[0]['runId'] == inspections[1]['runId'] == inspections[2]['runId']
    assert inspections[1]['eventReward'] == inspections[2]['eventReward']
    assert inspections[1]['sourceChoices'] == inspections[2]['sourceChoices']
    assert all((data / name).read_bytes() == raw for name, raw in defaults.items())
    assert hashes == {name: hashlib.sha256((source / name).read_bytes()).hexdigest() for name in guarded}
    unchanged_runtime_inputs()
    report = {'passed': True, 'runtimeDirectory': str(directory), 'rootJarSHA256': digest,
        'isolatedConfigSHA256': config_digest, 'jarAndConfigSHA256Unchanged': True,
        'snapshots': snapshots, 'inspections': inspections, 'normalStops': stops,
        'sameUIDNormalH2ProcessRestarts': 2, 'sourceResourceHashes': hashes,
        'sourceResourcesModified': False, 'sourceCommit': PIN,
        'fixture': {'syntheticNoRewardPrerequisites': prerequisites, 'area': 110, 'sourceMap': 101,
            'productionMapAndRecipeDefaultsInstalled': True, 'operatorOverrides': False,
            'originalAreaRoomMapWeightedActorsAndEventUnchanged': True,
            'eventRewardRecipe': 'hkrpg server schema 1; one uniformly drawn ordinary blessing',
            'officialRewardEffectsVerified': False, 'officialRoomGenerationVerified': False},
        'allSevenRoomsPlayed': True, 'nativeBattleResultsAreClientReports': True,
        'clientGameplayVerified': False, 'crashAtomicityVerified': False}
    (directory / 'report.json').write_text(json.dumps(report, ensure_ascii=False, indent=2), 'utf-8')
    if args.output:
        output = args.output.resolve()
        assert output not in {jar, configuration}
        output.parent.mkdir(parents=True, exist_ok=True)
        output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n', 'utf-8')
    unchanged_runtime_inputs()
    print(json.dumps(report, ensure_ascii=False))


if __name__ == '__main__':
    main()
