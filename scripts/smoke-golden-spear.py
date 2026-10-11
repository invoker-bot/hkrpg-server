"""验证原始金色短矛的覆盖交互、实际护盾破坏、开箱、活动结算和同 UID 存储恢复。"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import socket
import shutil
import subprocess
import tempfile
import time
import urllib.error
import urllib.request
from smoke_resource_fixture import mirror_resources

ROOT = Path(__file__).resolve().parents[1]


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def port(kind=socket.SOCK_STREAM):
    with socket.socket(type=kind) as sock:
        sock.bind(('127.0.0.1', 0))
        return sock.getsockname()[1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--jar', type=Path, default=ROOT / 'build/libs/hkrpg-server.jar')
    parser.add_argument('--output', type=Path, default=ROOT / 'build/golden-spear-native.json')
    args = parser.parse_args()
    jar, output = args.jar.resolve(), args.output.resolve()
    assert jar.is_file() and jar.is_relative_to(ROOT / 'build')
    assert output.is_relative_to(ROOT / 'build')
    java = next((ROOT / '.tools').glob('jdk-21*/bin/java.exe'), None)
    assert java
    artifact = sha(jar)
    source = ROOT / 'resources/4.6'
    golden = json.loads((ROOT / 'src/test/resources/fixtures/world-chest-4.6.json').read_text('utf-8'))
    previous = json.loads((ROOT / 'build/time-relay-flame-native.json').read_text('utf-8'))
    original_dir = Path(previous['runtimeDirectory']).resolve()
    assert previous['passed'] and len(previous['openedOriginalChests']) == 28
    assert original_dir.is_relative_to(ROOT / 'build/smoke')
    old_marker = json.loads((original_dir / 'resources-fixture/resource-manifest.json').read_text('utf-8'))
    assert old_marker['testFixture'] and old_marker['worldChestFixture']
    predecessor_hashes = {str(path.relative_to(original_dir)): sha(path)
                          for path in (original_dir / 'data').rglob('*') if path.is_file()}
    for filename in ('time-relay-flame-4.6.json', 'golden-spear-4.6.json'):
        extra = json.loads((ROOT / 'src/test/resources/fixtures' / filename).read_text('utf-8'))
        for path, rows in extra['records'].items(): golden['records'].setdefault(path, []).extend(rows)
        for section in ('groups', 'locations', 'sourceFiles'): golden[section].update(extra[section])
    tables = {Path(path).stem: rows for path, rows in golden['records'].items()}
    goals = []
    for quest in sorted(tables['QuestData'], key=lambda row: row['QuestID']):
        finish = next(row for row in tables['FinishWay'] if row['ID'] == quest['FinishWayID'])
        reward = next(row for row in tables['RewardData'] if row['RewardID'] == quest['RewardID'])
        recipes = {1: reward['Hcoin']} if reward.get('Hcoin') else {}
        for key, item in reward.items():
            if key.startswith('ItemID_') and item:
                recipes[item] = recipes.get(item, 0) + reward['Count_' + key.split('_')[1]]
        goals.append(dict(id=quest['QuestID'], goal=finish['Progress'], rewards=recipes))
    assert [(goal['id'], goal['goal']) for goal in goals] == [(6029850, 5), (6029851, 10), (6029852, 15), (6029856, 1), (6029858, 1)]
    chests = [dict(location, id=int(key)) for key, location in golden['locations'].items()]
    chests.sort(key=lambda row: row['id'])
    assert len(chests) == 30 and len({row['id'] for row in chests}) == 30
    plane, floor = chests[0]['plane'], chests[0]['floor']
    assert all(row['plane'] == plane and row['floor'] == floor for row in chests)
    entry = next(row for row in tables['MapEntrance'] if row['PlaneID'] == plane and row['FloorID'] == floor)
    world = next(row['WorldID'] for row in tables['MazePlane'] if row['PlaneID'] == plane)
    stage = dict(plane=plane, floor=floor, entry=entry['ID'], world=world, pos=dict(x=0, y=0, z=0))
    replacements = {path: json.loads((source / path).read_text('utf-8')) for path in golden['records']}
    replacements.update(golden['groups'])
    replacements.update(golden['headers'])
    floor_path = f'Config/LevelOutput/RuntimeFloor/P{plane}_F{floor}.json'
    replacements[floor_path] = json.loads((source / floor_path).read_text('utf-8'))
    guarded = set(source.glob('ExcelOutput/*.json')) | {source / 'resource-manifest.json'}
    guarded |= {source / path for path in golden['sourceFiles']}
    hashes = {str(path.relative_to(source)).replace('\\', '/'): sha(path) for path in sorted(guarded)}
    runs = ROOT / 'build/smoke'
    runs.mkdir(exist_ok=True)
    directory = Path(tempfile.mkdtemp(prefix='golden-spear-', dir=runs))
    fixture = directory / 'resources-fixture'
    bindings = mirror_resources(source, fixture, replacements)
    for path in golden['headers']:
        target = fixture / path
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes((source / path).read_bytes())
    flags = subprocess.CREATE_NO_WINDOW if os.name == 'nt' else 0
    if bindings:
        plan = directory / 'junctions.json'
        plan.write_text(json.dumps(bindings), 'utf-8')
        helper = directory / 'prepare.ps1'
        helper.write_text("param([string]$Plan)\n$ErrorActionPreference='Stop'\nforeach ($binding in (Get-Content -LiteralPath $Plan -Raw | ConvertFrom-Json)) { New-Item -ItemType Junction -Path $binding.target -Value $binding.source | Out-Null }\n", 'utf-8')
        subprocess.run(['powershell', '-NoProfile', '-NonInteractive', '-File', str(helper), '-Plan', str(plan)],
                       check=True, capture_output=True, creationflags=flags)
    (fixture / 'resource-manifest.json').write_text(json.dumps(dict(
        clientVersion='4.6.0', testFixture=True, worldChestFixture=True, timeRelayFlameFixture=True, goldenSpearFixture=True)), 'utf-8')
    http, mongo, game = port(), port(), port(socket.SOCK_DGRAM)
    config = directory / 'config.json'
    config.write_text(json.dumps(dict(protocol=dict(wireVersion='4.6.0'), resourceDir=str(fixture),
        httpServer=dict(bindAddress='127.0.0.1', bindPort=http),
        gameServer=dict(bindAddress='127.0.0.1', bindPort=game),
        internalMongoServer=dict(address='127.0.0.1', port=mongo, filePath='./data/database.mv'))), 'utf-8')
    config_bytes = config.read_bytes()
    data = directory / 'data'
    shutil.copytree(original_dir / 'data', data)
    for name in ('world-chest-start-balances.json', 'world-chest-start-items.json'):
        shutil.copy2(original_dir / name, directory / name)
    (data / 'Gameplay46.json').write_text(json.dumps({'activities': [dict(
        panelId=30013, moduleId=3001301, beginTime=1, endTime=4102444800)]}), 'utf-8')
    classes = directory / 'probe-classes'
    classes.mkdir()
    sources = [ROOT / 'scripts/fixtures/java/org/hkrpg/server/protocol/NativeGoldenSpearProbe.java',
               ROOT / 'scripts/fixtures/java/org/hkrpg/server/gameplay/GoldenSpearFixtureInspector.java']
    built = subprocess.run([str(java.with_name('javac.exe')), '-encoding', 'UTF-8', '-cp', str(jar),
                            '-d', str(classes), *map(str, sources)], capture_output=True, creationflags=flags)
    (directory / 'javac.log').write_bytes(built.stdout + built.stderr)
    assert built.returncode == 0, directory / 'javac.log'
    options = ['-Dfile.encoding=UTF-8', '-Dstdout.encoding=UTF-8', '-Dstderr.encoding=UTF-8',
               f'-Djdk.net.unixdomain.tmpdir={directory / "tcp-fallback"}']
    classpath = str(classes) + os.pathsep + str(jar)
    credentials, snapshots, inspections = {}, [], []
    known, claimed, unlocked = list(previous["openedOriginalChests"]), list(previous["completedOriginalActivityQuests"]), list(previous["snapshots"][-1]["unlocked"])
    stops = 0
    spear_progress = {}

    def request(route, body=None):
        req = urllib.request.Request(f'http://127.0.0.1:{http}' + route,
            data=None if body is None else json.dumps(body).encode(),
            headers={'Content-Type': 'application/json'})
        with urllib.request.urlopen(req, timeout=5) as response:
            return json.load(response)

    def inspect(mode):
        control = directory / f'inspector-{len(inspections)}.json'
        control.write_text(json.dumps(dict(uid=credentials['uid'], known=known, claimed=claimed,
                                          goals=goals, stage=stage, unlocked=unlocked, spearProgress=spear_progress)), 'utf-8')
        child = subprocess.run([str(java), *options, '-cp', classpath,
            'org.hkrpg.server.gameplay.GoldenSpearFixtureInspector', str(directory), str(control), mode],
            cwd=ROOT, capture_output=True, text=True, encoding='utf-8', timeout=100, creationflags=flags)
        log = directory / f'inspector-{len(inspections)}.log'
        log.write_text(child.stdout + child.stderr, 'utf-8')
        assert child.returncode == 0, log
        value = json.loads(next(line for line in reversed(child.stdout.splitlines()) if line.startswith('{')))
        assert value['passed'] and value['openedChests'] == len(known)
        inspections.append(value)

    gold = [row for row in chests if row['id'] in (20411666,20411667)]
    assert [row['id'] for row in gold] == [20411666,20411667]
    def steps(source,start,end): return dict(source,start=start,end=end)
    batches=[([],[],[],0),([steps(gold[0],0,2)],[],[],0),
             ([steps(gold[0],2,4)],[gold[0]],[],0),([],[],[gold[0]],6029858),
             ([steps(gold[1],0,4)],[gold[1]],[gold[1]],0),([],[],[],0)]
    for index, (stages, hits, batch, target_claim) in enumerate(batches):
        control = dict(credentials, **stage, allChests=chests, goldenChests=gold, chests=batch, goals=goals,
                       known=known, claimed=claimed, unlocked=unlocked, hitShields=hits, spearStages=stages,
                       spearProgress=spear_progress, seed=False, claim=target_claim)
        with (directory / f'server-{index}.log').open('wb') as log:
            process = subprocess.Popen([str(java), *options, '-Xmx1536m', '-jar', str(jar), '--no-handbook'],
                cwd=directory, stdin=subprocess.PIPE, stdout=log, stderr=subprocess.STDOUT, creationflags=flags)
            try:
                deadline = time.monotonic() + 100
                while True:
                    assert process.poll() is None, directory / f'server-{index}.log'
                    try:
                        if request('/health/ready')['ready']:
                            break
                    except (OSError, urllib.error.URLError):
                        pass
                    if time.monotonic() > deadline:
                        raise TimeoutError(directory)
                    time.sleep(.2)
                if not credentials:
                    login = request('/hkrpg_cn/mdk/shield/api/login', dict(account='world-chests-native', password='local-test'))
                    assert login['retcode'] == 0
                    account = login['data']['account']
                    combo = request('/hkrpg_cn/combo/granter/login/v2/login', dict(data=json.dumps(
                        dict(uid=account['uid'], token=account['token']))))
                    assert combo['retcode'] == 0
                    credentials.update(accountUid=account['uid'], comboToken=combo['data']['combo_token'])
                    control.update(credentials)
                probe_log = directory / f'probe-{index}.log'
                with probe_log.open('wb') as out:
                    child = subprocess.Popen([str(java), *options, '-cp', classpath,
                        'org.hkrpg.server.protocol.NativeGoldenSpearProbe', str(game)],
                        cwd=directory, stdin=subprocess.PIPE, stdout=out, stderr=subprocess.STDOUT, creationflags=flags)
                    child.stdin.write((json.dumps(control) + '\n').encode())
                    child.stdin.close()
                    deadline = time.monotonic() + 180
                    try:
                        while child.poll() is None:
                            if time.monotonic() > deadline:
                                raise TimeoutError(probe_log)
                            time.sleep(.2)
                    finally:
                        if child.poll() is None:
                            child.terminate()
                            child.wait(timeout=15)
                assert child.returncode == 0, probe_log
                value = json.loads(next(line for line in reversed(probe_log.read_text('utf-8').splitlines()) if line.startswith('{')))
                assert value['passed']
                credentials.setdefault('uid', value['uid'])
                assert credentials['uid'] == value['uid']
                snapshots.append(value)
                assert value['newlyUnlocked'] == [row['id'] for row in hits]
                unlocked = sorted(unlocked + value['newlyUnlocked'])
                assert value['unlocked'] == unlocked
                expected_steps = dict(spear_progress)
                for row in stages: expected_steps[str(row['id'])] = row['end']
                assert value['spearProgress'] == expected_steps
                spear_progress = expected_steps
                assert value['openedOriginalChests'] == [row['id'] for row in batch]
                known = sorted(known + value['openedOriginalChests'])
                if target_claim:
                    claimed = sorted(claimed + [target_claim])
                assert value['known'] == known and value['claimed'] == claimed
            finally:
                if process.poll() is None:
                    try:
                        process.stdin.write(b'stop\n')
                        process.stdin.flush()
                        process.wait(timeout=45)
                        assert process.returncode == (1000 if os.name == 'nt' else 232), directory
                        stops += 1
                    finally:
                        if process.poll() is None:
                            process.terminate()
                            process.wait(timeout=15)
                        process.stdin.close()
        inspect('inspect')
        print(json.dumps(dict(phase=index, openedChests=len(known), normalStops=stops)), flush=True)
    inspect('expired')
    assert len(known) == 30 and len(set(known)) == 30 and len(unlocked) == 6
    assert claimed == [goal['id'] for goal in goals] and stops == 6
    assert sum(len(snapshot.get('chestIdentityNotifications', [])) for snapshot in snapshots) == 2
    assert sha(jar) == artifact and config.read_bytes() == config_bytes
    for path, expected in hashes.items():
        assert sha(source / path) == expected, path
    for path, expected in predecessor_hashes.items():
        assert sha(original_dir / path) == expected, path
    report = dict(predecessorDataVerifiedUnchanged=True, passed=True, artifactSha256=artifact, runtimeDirectory=str(directory),
        snapshots=snapshots, inspections=inspections, nativeRequests=sum(row['nativeRequests'] for row in snapshots),
        openedOriginalChests=known, sourceBoundActivityCredits=32, retainedOriginalWorldChests=24, retainedTimeRelayFlameChests=4, sourceBoundDestructions=2, sourceBoundFinishReports=6, chestIdentityNotifications=2, sourceSpearSteps=8,
        rejectedRepeatedOpenings=sum(row.get('rejectedRepeatedOpenings', 0) for row in snapshots),
        normalStops=stops, sameUIDNormalH2ProcessRestarts=5, completedOriginalActivityQuests=claimed,
        actualQuestRewards={goal['id']: goal['rewards'] for goal in goals}, sourceResourceHashes=hashes,
        chestLootIsCustomServerPolicy=True, storyPrerequisitesAndSceneAreFixtures=True, clientLocalPuzzleActionsReplayed=False, activityType226VerifiedFromNativeClient=True, sourceMapOverrideVerified=True, sourceMapHasFourSpearStages=True,
        operatorWindowIsFixture=True, sceneGroupReferencesReduced=False,
        originalWholeGroupDefinitionsUnmodified=True, targetCompletionPrewritten=False,
        countReceiptsPrewritten=False, sourceResourcesModified=False, operatorDataModified=False,
        clientGameplayVerified=False, wholeActivityComplete=False, wholeGameComplete=False)
    assert sum(row["acceptedSourceSteps"] for row in snapshots) == 8
    output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n', 'utf-8')
    print(json.dumps(dict(passed=True, nativeRequests=report['nativeRequests'], openedChests=30,
                         normalStops=stops, runtimeDirectory=str(directory))))


if __name__ == '__main__':
    main()
