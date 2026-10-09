"""真实购买、付费强化及原始成就领奖；同 UID 重连、两次正常 H2 重启。"""
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
    parser.add_argument('--target', type=int, choices=[10, 200], required=True)
    parser.add_argument('--automatic', type=int, choices=[20, 21])
    args = parser.parse_args()
    source = ROOT / 'resources/4.6'
    jar = ROOT / 'build/libs/hkrpg-server.jar'
    java = next((ROOT / '.tools').glob('jdk-21*/bin/java.exe'), None)
    assert java and jar.is_file()
    digest = hashlib.sha256(jar.read_bytes()).hexdigest()
    runs = ROOT / 'build/smoke'
    runs.mkdir(exist_ok=True)
    directory = Path(tempfile.mkdtemp(prefix=f'enhancement-quests-{args.target}-', dir=runs))
    assert directory.resolve().is_relative_to(runs.resolve())
    fixture = directory / 'resources-fixture'
    guarded = ['resource-manifest.json'] + ['ExcelOutput/' + name + '.json' for name in [
        'MainMission', 'QuestData', 'FinishWay', 'AchievementData', 'RewardData', 'ItemConfig',
        'RogueBuff', 'RogueMazeBuff', 'MazeBuff', 'RogueRoom', 'MapEntrance', 'NPCData', 'ConstValueRogue']]
    guarded += ['Config/LevelOutput/RuntimeFloor/P80303_F80303001.json']
    guarded += [f'Config/LevelOutput/SharedRuntimeGroup/Groups_P80303_F80303001/LevelGroup_P80303_F80303001_G{group}.json'
                for group in [44, 45, 46, 77]]
    if args.automatic:
        guarded += ['ExcelOutput/' + name + '.json' for name in ['RogueMiracle','RogueMiracleEffect','RogueMiracleGroup','RogueHandbookMiracle']]
        guarded += ['Config/LevelOutput/RuntimeFloor/P80201_F80201001.json']
        guarded += [f'Config/LevelOutput/SharedRuntimeGroup/Groups_P80201_F80201001/LevelGroup_P80201_F80201001_G{group}.json' for group in [35,117]]
        guarded += [f'Config/Level/RogueDialogue/RogueDialogueEvent/Act/Rogue130/Act011000{page}.json' for page in [3,4]]
    folder = source / 'Config/Level/Mission/1000201'
    assert folder.is_dir()
    guarded.extend(p.relative_to(source).as_posix() for p in folder.rglob('*') if p.is_file())
    hashes = {name: hashlib.sha256((source / name).read_bytes()).hexdigest() for name in guarded}
    assert hashes['resource-manifest.json'] == 'ecccb21469df76282e6df5bf0cbe1eff7d9f904c2dd644092124b6904482e735'
    mains = json.loads((source / 'ExcelOutput/MainMission.json').read_text('utf-8'))
    row = next(r for r in mains if r['MainMissionID'] == 1000201)
    row.update(TakeOperation='And', TakeParam=[{'Type': 'Auto'}], BeginOperation='And',
               BeginParam=[{'Type': 'Auto'}], RewardID=0, SubRewardList=[])
    constants = json.loads((source / 'ExcelOutput/ConstValueRogue.json').read_text('utf-8'))
    start = next(r for r in constants if r['ConstRogueName'] == 'Start_Rogue_Coin_Base')
    original_coins = start['ConstValue']
    assert original_coins == '50'
    start['ConstValue'] = '1000'
    buffs = json.loads((source / 'ExcelOutput/RogueBuff.json').read_text('utf-8'))
    maze = json.loads((source / 'ExcelOutput/MazeBuff.json').read_text('utf-8'))
    maze += json.loads((source / 'ExcelOutput/RogueMazeBuff.json').read_text('utf-8'))
    levels = {(r.get('ID'), r.get('Lv', 1)) for r in maze}
    second = {r['MazeBuffID'] for r in buffs if r.get('MazeBuffLevel') == 2 and r.get('RogueVersion') == 1}
    enhanceable = sorted({r['MazeBuffID'] for r in buffs if r.get('MazeBuffLevel') == 1
        and r.get('RogueVersion') == 1 and r['MazeBuffID'] in second
        and all((r['MazeBuffID'], lv) in levels for lv in [1, 2])})
    assert len(enhanceable) == 171
    replacements = {'ExcelOutput/MainMission.json': mains, 'ExcelOutput/ConstValueRogue.json': constants}
    if args.automatic:
        rooms = json.loads((source / 'ExcelOutput/RogueRoom.json').read_text('utf-8'))
        room = next(r for r in rooms if r['RogueRoomID'] == 1000012)
        room.update(MapEntrance=8020101, GroupID=35, GroupWithContent={'35':0,'117':0})
        replacements['ExcelOutput/RogueRoom.json'] = rooms
        if args.automatic == 21:
            groups = json.loads((source / 'ExcelOutput/RogueMiracleGroup.json').read_text('utf-8'))
            pool = next(r for r in groups if r['RogueMiracleGroupID'] == 10004)
            assert pool['MiracleWeight']['20'] == 1
            pool['MiracleWeight'] = {'20':1}
            replacements['ExcelOutput/RogueMiracleGroup.json'] = groups
    junctions = mirror_resources(source, fixture, replacements)
    # Only the no-reward prerequisite graph and initial budget are substituted.
    folder = fixture / 'Config/Level/Mission/1000201'
    folder.mkdir(parents=True, exist_ok=False)
    sub = 100020199
    (folder / 'MissionInfo_1000201.json').write_text(json.dumps({
        'MainMissionID': 1000201, 'StartSubMissionList': [sub], 'FinishSubMissionList': [sub]}), 'utf-8')
    (folder / 'MissionInfo_1000201.partial.json').write_text(json.dumps({'SubMissionList': [{
        'ID': sub, 'MainMissionID': 1000201, 'TakeType': 'Auto', 'FinishType': 'Auto', 'Progress': 1}]}), 'utf-8')
    flags = subprocess.CREATE_NO_WINDOW if os.name == 'nt' else 0
    if junctions:
        plan = directory / 'junction-plan.json'
        plan.write_text(json.dumps(junctions), 'utf-8')
        helper = directory / 'prepare-fixture.ps1'
        helper.write_text("param([string]$Plan)\n$ErrorActionPreference='Stop'\nforeach ($binding in (Get-Content -LiteralPath $Plan -Raw | ConvertFrom-Json)) { New-Item -ItemType Junction -Path $binding.target -Value $binding.source | Out-Null }\n", 'utf-8')
        subprocess.run(['powershell', '-NoProfile', '-NonInteractive', '-File', str(helper), '-Plan', str(plan)],
                       check=True, capture_output=True, creationflags=flags)
    (fixture / 'resource-manifest.json').write_text(json.dumps({
        'clientVersion': '4.6.0', 'testFixture': True, 'syntheticNoRewardPrerequisites': [1000201]}), 'utf-8')
    data = directory / 'data'
    data.mkdir()
    pools = json.loads((ROOT / 'src/main/resources/defaults/RogueMapGen.json').read_text('utf-8'))
    pools['1'] = [1000012]
    (data / 'RogueMapGen.json').write_text(json.dumps(pools), 'utf-8')
    http, mongo, game = free_port(), free_port(), free_port(socket.SOCK_DGRAM)
    (directory / 'config.json').write_text(json.dumps({
        'protocol': {'wireVersion': '4.6.0'}, 'resourceDir': str(fixture),
        'httpServer': {'bindAddress': '127.0.0.1', 'bindPort': http},
        'gameServer': {'bindAddress': '127.0.0.1', 'bindPort': game},
        'internalMongoServer': {'address': '127.0.0.1', 'port': mongo, 'filePath': './data/database.mv'}}), 'utf-8')
    classes = directory / 'probe-classes'
    classes.mkdir()
    probe_source = ROOT / 'scripts/fixtures/java/org/hkrpg/server/protocol/NativeEnhancementQuestProbe.java'
    compilation = subprocess.run([str(java.with_name('javac.exe')), '-encoding', 'UTF-8', '-cp', str(jar),
                                  '-d', str(classes), str(probe_source)], capture_output=True, creationflags=flags)
    (directory / 'javac.log').write_bytes(compilation.stdout + compilation.stderr)
    assert compilation.returncode == 0, directory / 'javac.log'
    fallback = '-Djdk.net.unixdomain.tmpdir=' + str(directory / 'tcp-fallback')
    command = [str(java), '-Xmx1536m', fallback, '-jar', str(jar), '--no-handbook']

    def request(route, body=None):
        req = urllib.request.Request(f'http://127.0.0.1:{http}' + route,
            data=None if body is None else json.dumps(body).encode(), headers={'Content-Type': 'application/json'})
        with urllib.request.urlopen(req, timeout=5) as response:
            return json.load(response)

    def probe(name, credentials):
        assert hashlib.sha256(jar.read_bytes()).hexdigest() == digest
        child = subprocess.run([str(java), fallback, '-cp', str(jar) + os.pathsep + str(classes),
            'org.hkrpg.server.protocol.NativeEnhancementQuestProbe', str(game)], cwd=directory,
            input=json.dumps(credentials) + '\n', stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
            text=True, encoding='utf-8', errors='replace', timeout=360, creationflags=flags)
        (directory / name).write_text(child.stdout, 'utf-8')
        assert child.returncode == 0, directory / name
        snapshot = json.loads(next(line for line in child.stdout.splitlines() if line.startswith('{"uid":')))
        assert snapshot['passed'] and not snapshot['clientGameplayVerified']
        assert snapshot['target'] == args.target and snapshot['jades'] == (5 if args.target == 10 else 15)
        return snapshot

    snapshots, credentials, stops = [], None, 0
    for iteration in range(3):
        with (directory / f'server-{iteration}.log').open('w', encoding='utf-8') as log:
            process = subprocess.Popen(command, cwd=directory, stdin=subprocess.PIPE,
                stdout=log, stderr=subprocess.STDOUT, creationflags=flags)
            try:
                deadline = time.monotonic() + 90
                while True:
                    if process.poll() is not None:
                        raise RuntimeError('Server exited: ' + str(directory))
                    try:
                        if request('/health/ready')['ready']:
                            break
                    except (OSError, urllib.error.URLError):
                        pass
                    if time.monotonic() > deadline:
                        raise RuntimeError('Startup timed out: ' + str(directory))
                    time.sleep(.2)
                if iteration == 0:
                    login = request('/hkrpg_cn/mdk/shield/api/login', {
                        'account': 'cn-enhancement-achievements', 'password': 'local-test'})
                    assert login['retcode'] == 0
                    account = login['data']['account']
                    combo = request('/hkrpg_cn/combo/granter/login/v2/login', {
                        'data': json.dumps({'uid': account['uid'], 'token': account['token']})})
                    assert combo['retcode'] == 0
                    credentials = {'accountUid': account['uid'], 'comboToken': combo['data']['combo_token'],
                                   'target': args.target, 'enhanceable': enhanceable, 'automatic': args.automatic or 0}
                    first = probe('first.log', credentials)
                    assert first['actualPurchases'] == first['actualEnhancements'] == first['buffNotifications'] == args.target
                    expected_operations = first['actualCurioPurchases'] if args.automatic else args.target
                    assert first['currencyNotifications'] == first['questNotifications'] == expected_operations
                    if args.automatic: assert (args.target + 1)//2 <= expected_operations <= args.target
                    assert first['jadeNotifications'] == (1 if args.target == 10 else 2) and first['questQueries'] == 3
                    snapshots.append(first)
                    credentials['resume'] = True
                resumed = probe(f'resume-{iteration}.log', credentials)
                assert resumed['actualPurchases'] == resumed['actualEnhancements'] == resumed['questNotifications'] == resumed['currencyNotifications'] == resumed['buffNotifications'] == resumed['jadeNotifications'] == 0
                assert resumed['actualCurioPurchases'] == 0
                assert resumed['questQueries'] == 2 and resumed['uid'] == snapshots[0]['uid']
                snapshots.append(resumed)
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
    assert stops == 3 and len(snapshots) == 4
    assert hashes == {name: hashlib.sha256((source / name).read_bytes()).hexdigest() for name in guarded}
    print(json.dumps({'passed': True, 'runtimeDirectory': str(directory), 'rootJarSHA256': digest,
        'target': args.target, 'automaticCurio': args.automatic or 0, 'snapshots': snapshots, 'normalStops': stops, 'sameUIDNormalH2ProcessRestarts': 2,
        'sourceResourceHashes': hashes, 'sourceResourcesModified': False,
        'fixture': {'syntheticNoRewardPrerequisites': [1000201], 'initialFragments': 1000,
            'connectorOriginalEnhanceableIDs': enhanceable,
            'originalInitialFragments': original_coins, 'originalRoomMerchantHertaAndAchievementRulesUnchanged': not bool(args.automatic),
            'roomAssignmentOverriddenForOriginalMerchants': bool(args.automatic),
            'dicePoolRestrictedToOriginalCurio20': args.automatic == 21,
            'originalBuffMiracleDefinitionsAndAchievementRulesUnchanged': True,
            'officialTutorialVerified': False, 'officialRoomGenerationVerified': False},
        'clientGameplayVerified': False, 'crashAtomicityVerified': False}, ensure_ascii=False))


if __name__ == '__main__':
    main()
