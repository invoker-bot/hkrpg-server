"""原七站 G22 本服商店验收；200 碎片/最多三件为可关闭配置，客户端菜单入口未验。"""
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
import zipfile

from smoke_resource_fixture import mirror_resources

ROOT = Path(__file__).resolve().parents[1]
PIN = '724b139d8c9c32d12552eb95745a4fee72bfe48b'
SOURCE_BINDINGS = {
    'Config/Level/Rogue/RogueNPC/RogueNPC40398.json': 'fa21d43c722cfcb21030f761ed97c61a8b287c006d9e727c193e722d388fbb2a',
    'Config/Level/Rogue/RogueDialogue/Event40398/Opt40398.json': 'f3201fa896d601aa5f009ebc4df7f46228073023211179fb4ab02c58bb65a02c',
    'Config/Level/Rogue/RogueDialogue/Event40398/Act40398.json': '65ab43311653fcaaa795554e2341d4fd5377b9cf928c70f42a58c221c738f9de',
    'Config/LevelOutput/SharedRuntimeGroup/Groups_P80001_F80001003/LevelGroup_P80001_F80001003_G22.json':
        '50aeab6d84c933dacdbac2ce2dac505448199170045cc43e72f66ce21ea4de59',
    'Config/Level/RogueDialogue/RogueDialogueEvent/Act/Act403810002.json':
        'fe0ecd2145033bfbc886a0d40cc0c21d6b1677deae00f0b59d22942eb6e21361'}


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


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
    source, jar = args.resources.resolve(), args.jar.resolve()
    java = next((ROOT / '.tools').glob('jdk-21*/bin/java.exe'), None)
    if java is None and os.environ.get('JAVA_HOME'):
        java = Path(os.environ['JAVA_HOME']) / 'bin/java.exe'
    assert java and java.is_file() and jar.is_file()
    jar_digest = digest(jar)
    runs = ROOT / 'build/smoke'
    runs.mkdir(exist_ok=True)
    directory = Path(tempfile.mkdtemp(prefix='rogue-rest-merchants-', dir=runs))
    assert directory.resolve().is_relative_to(runs.resolve())
    fixture = directory / 'resources-fixture'
    guarded = ['resource-manifest.json', *SOURCE_BINDINGS]
    guarded += [p.relative_to(source).as_posix() for p in (source / 'ExcelOutput').glob('*.json')]
    guarded += [p.relative_to(source).as_posix() for p in (source / 'Config/Level/Mission/4030006').glob('*.json')]
    for floor, groups in {'80001002': [34, 35], '80001003': [18, 19, 20, 21, 22, 23]}.items():
        guarded += [f'Config/LevelOutput/RuntimeFloor/P80001_F{floor}.json']
        guarded += [f'Config/LevelOutput/SharedRuntimeGroup/Groups_P80001_F{floor}/LevelGroup_P80001_F{floor}_G{g}.json' for g in groups]
    for name in ['Config/Level/NPCDialogue/P80001/F80001002_G34/DialogueMain_F80001002_G34_N400001.json',
                 'Config/Level/NPCDialogue/P80001/F80001002_G34/DialoguePack_Transferred_F80001002_G34_N400001.json',
                 'Config/Level/RogueDialogue/RogueDialogueEvent/Act/Act403810003.json']:
        if (source / name).is_file():
            guarded.append(name)
    guarded = sorted(set(guarded))
    hashes = {name: digest(source / name) for name in guarded}
    for name, sha in SOURCE_BINDINGS.items():
        assert hashlib.sha256((source / name).read_bytes().replace(b'\r\n', b'\n')).hexdigest() == sha, name
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
        'clientVersion': '4.6.0', 'testFixture': True, 'rogueRestMerchantFixture': True,
        'sourceCommit': PIN, 'syntheticNoRewardPrerequisites': prerequisites}), 'utf-8')
    # Only prerequisite mission files and this owned manifest differ from the source resources.
    for name in guarded:
        if name not in {'resource-manifest.json', 'ExcelOutput/MainMission.json'}:
            assert (fixture / name).read_bytes() == (source / name).read_bytes(), name
    data = directory / 'data'
    data.mkdir()
    with zipfile.ZipFile(jar) as archive:
        defaults = {name: archive.read('defaults/' + name)
                    for name in ['RogueMapGen.json', 'RogueMapGenByMap.json', 'RogueDialogueRewards.json', 'RogueRestMerchants.json']}
    merchant = json.loads(defaults.pop('RogueRestMerchants.json'))
    assert merchant['enabled'] is False and merchant['fragmentBasePrice'] == 200 and merchant['stockLimit'] == 3
    assert merchant['binding']['sourceCommit'] == PIN and merchant['binding']['site'] == 6 and merchant['binding']['room'] == 306
    merchant['enabled'] = True
    recipe = data / 'RogueRestMerchants.json'
    recipe.write_text(json.dumps(merchant, ensure_ascii=False, indent=2) + '\n', 'utf-8')
    recipe_digest = digest(recipe)
    assert all(not (data / name).exists() for name in defaults)
    rooms = json.loads((source / 'ExcelOutput/RogueRoom.json').read_text('utf-8'))
    entries = {r['ID']: r for r in json.loads((source / 'ExcelOutput/MapEntrance.json').read_text('utf-8'))}
    assert next(r for r in rooms if r['RogueRoomID'] == 306)['GroupWithContent'] == {'22': 0}
    assert next(r for r in rooms if r['RogueRoomID'] == 303)['MapEntrance'] == 8000102
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
    handbook = {r['MiracleHandbookID']: r for r in json.loads((source / 'ExcelOutput/RogueHandbookMiracle.json').read_text('utf-8'))}
    unlocks = {}
    for row in json.loads((source / 'ExcelOutput/RogueMiracle.json').read_text('utf-8')):
        link = row.get('UnlockHandbookMiracleID', 0)
        valid = link in handbook and handbook[link]['MiracleDisplayID'] == row['MiracleDisplayID'] and handbook[link]['MiracleEffectDisplayID'] == row['MiracleEffectDisplayID']
        unlocks[str(row['MiracleID'])] = link if valid else 0
    (directory / 'handbook-unlocks.json').write_text(json.dumps(unlocks), 'utf-8')
    http, mongo, game = free_port(), free_port(), free_port(socket.SOCK_DGRAM)
    while mongo == http:
        mongo = free_port()
    configuration = directory / 'config.json'
    configuration.write_text(json.dumps({
        'protocol': {'wireVersion': '4.6.0'}, 'resourceDir': str(fixture),
        'httpServer': {'bindAddress': '127.0.0.1', 'bindPort': http},
        'gameServer': {'bindAddress': '127.0.0.1', 'bindPort': game},
        'internalMongoServer': {'address': '127.0.0.1', 'port': mongo, 'filePath': './data/database.mv'}}), 'utf-8')
    config_digest = digest(configuration)
    (directory / 'runtime-inputs.json').write_text(json.dumps({
        'rootJarSHA256': jar_digest, 'isolatedConfigSHA256': config_digest,
        'isolatedMerchantRecipeSHA256': recipe_digest, 'sourceResourceHashes': hashes,
        'sourceCommit': PIN}, ensure_ascii=False, indent=2) + '\n', 'utf-8')

    def unchanged_runtime_inputs():
        assert digest(jar) == jar_digest, 'Frozen jar changed'
        assert digest(configuration) == config_digest, 'Isolated config changed'
        assert digest(recipe) == recipe_digest, 'Isolated opt-in merchant recipe changed'

    unchanged_runtime_inputs()
    probe_classes = directory / 'probe-classes'
    probe_classes.mkdir()
    sources = [ROOT / 'scripts/fixtures/java/org/hkrpg/server/protocol/NativeRogueRestMerchantProbe.java',
               ROOT / 'scripts/fixtures/java/org/hkrpg/server/gameplay/RogueRestMerchantFixtureInspector.java']
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
            'org.hkrpg.server.protocol.NativeRogueRestMerchantProbe', str(game)], cwd=directory,
            input=json.dumps(dict(credentials, mode=mode)) + '\n', capture_output=True,
            text=True, encoding='utf-8', errors='replace', timeout=180, creationflags=flags)
        log = directory / f'probe-{len(snapshots)}-{mode}.log'
        log.write_text(child.stdout + child.stderr, 'utf-8')
        assert child.returncode == 0, log
        snapshot = json.loads(next(line for line in child.stdout.splitlines() if line.startswith('{"uid":')))
        assert snapshot['passed'] and not snapshot['clientGameplayVerified'] and not snapshot['clientMenuEntryVerified']
        unchanged_runtime_inputs()
        snapshot.update(runtimeJarSHA256=jar_digest, isolatedConfigSHA256=config_digest, isolatedMerchantRecipeSHA256=recipe_digest)
        snapshots.append(snapshot)
        credentials.update({key: snapshot[key] for key in ['unique', 'batch', 'buff', 'selected', 'stock', 'beforePurchaseCoins']})

    for iteration, mode in enumerate(['start', 'purchase', 'finish', 'finished']):
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
                    login = request('/hkrpg_cn/mdk/shield/api/login', {'account': 'cn-46-rest-merchant', 'password': 'local-test'})
                    assert login['retcode'] == 0
                    account = login['data']['account']
                    combo = request('/hkrpg_cn/combo/granter/login/v2/login', {
                        'data': json.dumps({'uid': account['uid'], 'token': account['token']})})
                    assert combo['retcode'] == 0
                    credentials = {'accountUid': account['uid'], 'comboToken': combo['data']['combo_token'],
                                   'actors': actors, 'handbookUnlocks': unlocks}
                assert all((data / name).read_bytes() == raw for name, raw in defaults.items())
                probe(mode)
                if mode == 'start':
                    probe('stock')
                elif mode == 'purchase':
                    probe('bought')
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
                else:
                    raise RuntimeError('Server exited before normal stop: ' + str(directory))
                unchanged_runtime_inputs()
                (directory / f'normal-stop-{iteration}-input-check.json').write_text(json.dumps({
                    'rootJarSHA256': jar_digest, 'isolatedConfigSHA256': config_digest,
                    'isolatedMerchantRecipeSHA256': recipe_digest, 'jarConfigAndRecipeSHA256Unchanged': True,
                    'normalStop': True, 'serverExitCode': process.returncode}, indent=2) + '\n', 'utf-8')
        inspection_mode = 'stock' if iteration == 0 else 'bought' if iteration == 1 else 'finished'
        child = subprocess.run([str(java), *encoding, fallback, '-cp', str(jar) + os.pathsep + str(probe_classes),
            'org.hkrpg.server.gameplay.RogueRestMerchantFixtureInspector', str(directory),
            str(snapshots[0]['uid']), inspection_mode, str(credentials['selected']), str(credentials['beforePurchaseCoins'])], cwd=ROOT,
            text=True, encoding='utf-8', capture_output=True, timeout=45, creationflags=flags)
        log = directory / f'inspector-{iteration}.log'
        log.write_text(child.stdout + child.stderr, 'utf-8')
        assert child.returncode == 0, log
        inspection = json.loads(next(line for line in reversed(child.stdout.splitlines()) if line.startswith('{')))
        assert inspection['passed'] and inspection['changedPlayers'] == 0 and inspection['independentPlayerReads'] == 2
        assert inspection['stock'] == credentials['stock']
        unchanged_runtime_inputs()
        inspections.append(inspection)
    assert stops == 4 and len(snapshots) == 6 and len(inspections) == 4
    assert all(all(s[key] == snapshots[0][key] for key in ['uid', 'unique', 'batch', 'buff', 'selected', 'stock', 'beforePurchaseCoins']) for s in snapshots)
    assert snapshots[0]['miracles'] == snapshots[1]['miracles'] == []
    assert all(s['miracles'] == [snapshots[0]['selected']] for s in snapshots[2:])
    assert snapshots[0]['currentCoins'] == snapshots[1]['currentCoins'] == snapshots[0]['beforePurchaseCoins']
    assert snapshots[2]['currentCoins'] == snapshots[3]['currentCoins'] == snapshots[0]['beforePurchaseCoins'] - 200
    assert snapshots[4]['currentCoins'] == snapshots[5]['currentCoins'] and snapshots[4]['credits'] == snapshots[5]['credits']
    assert snapshots[4]['blessings'] == snapshots[5]['blessings'] and snapshots[4]['handbook'] == snapshots[5]['handbook']
    assert len(snapshots[0]['encounters']) == 7 and len(snapshots[4]['encounters']) == 1
    assert all(i['runId'] == inspections[0]['runId'] and i['eventReward'] == inspections[0]['eventReward'] for i in inspections)
    assert inspections[0]['acquisitionUUID'] == '' and all(i['acquisitionUUID'] == inspections[1]['acquisitionUUID'] for i in inspections[1:])
    assert inspections[1]['merchantLease'] == inspections[2]['merchantLease'] == inspections[3]['merchantLease']
    assert inspections[2]['sourceChoices'] == inspections[3]['sourceChoices']
    assert all((data / name).read_bytes() == raw for name, raw in defaults.items())
    assert hashes == {name: digest(source / name) for name in guarded}
    unchanged_runtime_inputs()
    report = {'passed': True, 'runtimeDirectory': str(directory), 'rootJarSHA256': jar_digest,
        'isolatedConfigSHA256': config_digest, 'isolatedMerchantRecipeSHA256': recipe_digest,
        'jarConfigAndRecipeSHA256Unchanged': True, 'snapshots': snapshots, 'inspections': inspections,
        'normalStops': stops, 'sameUIDNormalH2ProcessRestarts': 3, 'sourceResourceHashes': hashes,
        'sourceResourcesModified': False, 'sourceCommit': PIN,
        'fixture': {'syntheticNoRewardPrerequisites': prerequisites, 'area': 110, 'sourceMap': 101,
            'productionMapAndEventRecipeDefaultsInstalled': True,
            'operatorOverrides': {'RogueRestMerchants.json': {'enabled': True}},
            'bundledMerchantEnabledByDefault': False, 'fragmentBasePrice': 200, 'stockLimit': 3,
            'originalAreaRoomMapWeightedActorsNPCAndDialogueUnchanged': True,
            'serverRecipePricing': True, 'officialPricingVerified': False,
            'officialRewardEffectsVerified': False, 'officialRoomGenerationVerified': False},
        'allSevenRoomsPlayed': True, 'originalG22SourceActorVerified': True, 'nativeBattleResultsAreClientReports': True,
        'clientMenuEntryVerified': False, 'clientGameplayVerified': False, 'crashAtomicityVerified': False}
    (directory / 'report.json').write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n', 'utf-8')
    if args.output:
        output = args.output.resolve()
        assert output != jar and not output.is_relative_to(source) and not output.is_relative_to(data)
        output.parent.mkdir(parents=True, exist_ok=True)
        output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n', 'utf-8')
    unchanged_runtime_inputs()
    print(json.dumps({'passed': True, 'runtimeDirectory': str(directory), 'rootJarSHA256': jar_digest,
                     'snapshots': len(snapshots), 'requests': sum(s['requests'] for s in snapshots),
                     'rejectedDuplicateOrBlockedActions': sum(s['rejectedDuplicateOrBlockedActions'] for s in snapshots),
                     'normalStops': stops, 'inspections': len(inspections), 'clientMenuEntryVerified': False}, ensure_ascii=False))


if __name__ == '__main__':
    main()
