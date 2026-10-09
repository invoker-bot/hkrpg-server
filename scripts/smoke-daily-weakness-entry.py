"""原始两项日常、真实普攻弱点入战、三次获胜/领取和正常 H2 恢复；前置与日常池隔离。"""
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
    assert (classes / 'org/hkrpg/server/gameplay/DailyWeaknessEntryFixtureInspector.class').is_file()
    digest = hashlib.sha256(jar.read_bytes()).hexdigest()
    runs = ROOT / 'build/smoke'
    runs.mkdir(exist_ok=True)
    directory = Path(tempfile.mkdtemp(prefix='daily-weakness-entry-', dir=runs))
    assert directory.resolve().is_relative_to(runs.resolve())
    fixture = directory / 'resources-fixture'
    guarded = ['resource-manifest.json', 'Config/ConfigAdventureAbility/LocalPlayer/LocalPlayer_Mar_7th_00_Ability.json']
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
    pools = json.loads((source / 'ExcelOutput/DailyActiveQuestPool.json').read_text('utf-8'))
    selected = [r for r in pools if r['QuestID'] in [2100123, 2100142]]
    assert sorted(selected, key=lambda r: r['QuestID']) == [{'QuestID':2100123,'Type':3},{'QuestID':2100142,'Type':11}]
    proof = json.loads((ROOT / 'src/test/resources/fixtures/daily-weakness-entry-4.6.json').read_text('utf-8'))
    kind_tables = {'pool':'DailyActiveQuestPool.json','quest':'QuestData.json','rule':'FinishWay.json',
        'reward':'RewardData.json','item':'ItemConfig.json','avatar':'AvatarConfig.json','npc':'NPCMonsterData.json',
        'stage':'StageConfig.json','nonMatchingPrimaryStage':'StageConfig.json','monster':'MonsterConfig.json','buff':'MazeBuff.json'}
    originals = {}
    for name, evidence in proof['files'].items():
        raw = (source / 'ExcelOutput' / name).read_bytes()
        assert hashlib.sha256(raw).hexdigest() == evidence['resourceSHA256'], name
        originals[name] = json.loads(raw)
    ability = proof['abilityFile']
    assert (source / ability['path']).read_bytes() == bytes.fromhex(ability['rawHex'])
    for record in proof['records']:
        assert record['row'] in originals[kind_tables[record['kind']]], record['kind']
    junctions = mirror_resources(source, fixture, {'ExcelOutput/MainMission.json': mains,
        'ExcelOutput/DailyActiveQuestPool.json': selected})
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
        'clientVersion': '4.6.0', 'testFixture': True, 'dailyWeaknessEntryFixture': True,
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
    primary_stages = {str(c['stage']): c for c in actors['18']['choices']}
    monster_rows = json.loads((source / 'ExcelOutput/MonsterConfig.json').read_text('utf-8'))
    physical_weak = [r['MonsterID'] for r in monster_rows if 'Physical' in r['StanceWeakList']]
    assert all(any(m in physical_weak for m in c['waves'][0]) for c in primary_stages.values())
    physical_buff = next(r for r in json.loads((source / 'ExcelOutput/MazeBuff.json').read_text('utf-8'))
        if r['ID'] == 1000111 and r['Lv'] == 1)
    assert physical_buff['InBattleBindingKey'] == 'StageAbility_MazeCommon_EnterBattle_Physical'
    reset = ((int(time.time()) + 14400)//86400 + 1)*86400 - 14400
    assert reset - int(time.time()) > 1200, 'Too close to real daily reset'
    http, mongo, game = free_port(), free_port(), free_port(socket.SOCK_DGRAM)
    (directory / 'config.json').write_text(json.dumps({
        'protocol': {'wireVersion': '4.6.0'}, 'resourceDir': str(fixture),
        'httpServer': {'bindAddress': '127.0.0.1', 'bindPort': http},
        'gameServer': {'bindAddress': '127.0.0.1', 'bindPort': game},
        'internalMongoServer': {'address': '127.0.0.1', 'port': mongo, 'filePath': './data/database.mv'}}), 'utf-8')
    probe_classes = directory / 'probe-classes'
    probe_classes.mkdir()
    probe_source = ROOT / 'scripts/fixtures/java/org/hkrpg/server/protocol/NativeDailyWeaknessEntryProbe.java'
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
            'org.hkrpg.server.protocol.NativeDailyWeaknessEntryProbe', str(game)], cwd=directory,
            input=json.dumps(dict(credentials, mode=mode)) + '\n', capture_output=True,
            text=True, encoding='utf-8', errors='replace', timeout=90, creationflags=flags)
        log = directory / f'probe-{len(snapshots)}-{mode}.log'
        log.write_text(child.stdout + child.stderr, 'utf-8')
        assert child.returncode == 0, log
        snapshot = json.loads(next(line for line in child.stdout.splitlines() if line.startswith('{"uid":')))
        assert snapshot['passed'] and not snapshot['clientGameplayVerified']
        snapshots.append(snapshot)

    for iteration, modes in enumerate([['initial','win1','one'],['win2','win3','ready'],['claim','claimed'],['claimed']]):
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
                    login = request('/hkrpg_cn/mdk/shield/api/login', {'account': 'cn-46-weakness-entry', 'password': 'local-test'})
                    assert login['retcode'] == 0
                    account = login['data']['account']
                    combo = request('/hkrpg_cn/combo/granter/login/v2/login', {
                        'data': json.dumps({'uid': account['uid'], 'token': account['token']})})
                    assert combo['retcode'] == 0
                    credentials = {'accountUid': account['uid'], 'comboToken': combo['data']['combo_token'], 'primaryStages': primary_stages, 'physicalWeak':physical_weak}
                assert (data / 'RogueMapGenByMap.json').read_bytes() == mapped_defaults
                for mode in modes:
                    probe(mode)
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
            'org.hkrpg.server.gameplay.DailyWeaknessEntryFixtureInspector', str(directory),
            str(snapshots[0]['uid']), ['one','ready','claimed','claimed'][iteration]], cwd=ROOT,
            text=True, encoding='utf-8', capture_output=True, timeout=45, creationflags=flags)
        log = directory / f'inspector-{iteration}.log'
        log.write_text(child.stdout + child.stderr, 'utf-8')
        assert child.returncode == 0, log
        inspection = json.loads(next(line for line in reversed(child.stdout.splitlines()) if line.startswith('{')))
        assert inspection['passed'] and inspection['changedPlayers'] == 0
        inspections.append(inspection)
    assert stops == 4 and len(snapshots) == 9 and len(inspections) == 4
    assert all(s['uid'] == snapshots[0]['uid'] for s in snapshots)
    assert [s['mode'] for s in snapshots] == ['initial','win1','one','win2','win3','ready','claim','claimed','claimed']
    assert inspections[2]['goals'] == inspections[3]['goals'] and inspections[2]['points'] == inspections[3]['points'] == 200
    assert reset - int(time.time()) > 0
    assert (data / 'RogueMapGenByMap.json').read_bytes() == mapped_defaults
    assert hashes == {name: hashlib.sha256((source / name).read_bytes()).hexdigest() for name in guarded}
    print(json.dumps({'passed': True, 'runtimeDirectory': str(directory), 'rootJarSHA256': digest,
        'snapshots': snapshots, 'inspections': inspections, 'normalStops': stops,
        'sameUIDNormalH2ProcessRestarts': 3, 'sourceResourceHashes': hashes,
        'sourceResourcesModified': False, 'sourceRecordChecks':len(proof['records']),
        'fixture': {'syntheticNoRewardPrerequisites': prerequisites, 'isolatedOriginalDailyRows':selected,
            'area':110, 'sourceMap':101, 'primaryGroup':18, 'physicalFormalAvatar':8001,
            'originalStageWavesWeaknessBuffQuestAndRewardUnchanged':True,
            'goalsPointsOrInventorySeeded':False},
        'checks':[check for snapshot in snapshots for check in snapshot['checks']],
        'officialExcellentPredicateVerified':False, 'clientGameplayVerified':False,
        'activeBattleRecoveryVerified':False, 'crashAtomicityVerified':False}, ensure_ascii=False))



if __name__ == '__main__':
    main()
