"""Verify original offering 6, source rewards, real inventory and same-UID H2 restarts."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import socket
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
    parser.add_argument('--output', type=Path, default=ROOT / 'build/krater-offering-native.json')
    args = parser.parse_args()
    jar, output = args.jar.resolve(), args.output.resolve()
    assert jar.is_file() and jar.is_relative_to(ROOT / 'build')
    assert output.is_relative_to(ROOT / 'build')
    java = next((ROOT / '.tools').glob('jdk-21*/bin/java.exe'), None)
    assert java
    artifact = sha(jar)
    source = ROOT / 'resources/4.6'
    golden = json.loads((ROOT / 'src/test/resources/fixtures/krater-offering-4.6.json').read_text('utf-8'))
    tables = {Path(path).stem: rows for path, rows in golden['records'].items()}
    levels = {row['Level']: row for row in tables['OfferingLevelConfig']}
    rewards = {row['RewardID']: row for row in tables['RewardData']}

    def recipe(ids, tail=0):
        value = {}
        for reward_id, quantity in ids:
            row = rewards[reward_id]
            if row.get('Hcoin'):
                value[1] = value.get(1, 0) + row['Hcoin'] * quantity
            for key, item in row.items():
                if key.startswith('ItemID_'):
                    value[item] = value.get(item, 0) + row['Count_' + key.split('_')[1]] * quantity
        return value

    def level_recipe(ids, tail=0):
        return recipe([(levels[level]['RewardID'], tail if level == 51 else 1) for level in ids])

    def progress(total, tails, taken):
        remaining, level = total, 0
        while level < 50 and remaining >= levels[level + 1]['ItemCost']:
            level += 1
            remaining -= levels[level]['ItemCost']
        remaining -= tails * levels[51]['ItemCost']
        return dict(total=total, level=level, exp=remaining, taken=sorted(taken))

    assert sum(levels[level]['ItemCost'] for level in range(1, 51)) == 18500
    assert levels[51]['ItemCost'] == 100
    goal_rewards = recipe([(3129017, 1)])
    assert goal_rewards == {1: 20, 213: 4}
    replacements = {path: json.loads((source / path).read_text('utf-8')) for path in golden['records']}
    replacements.update(golden['groups'])
    replacements.update(golden['headers'])
    floor_path = 'Config/LevelOutput/RuntimeFloor/P10402_F10402001.json'
    floor = json.loads((source / floor_path).read_text('utf-8'))
    dim = next(row for row in floor['DimensionList'] if row.get('ID', 0) == 0)
    initial = floor['GroupInstanceList'][dim.get('StartGroupIndex', floor.get('StartGroupIndex', 0))]
    refs = [initial] + [floor['GroupInstanceList'][i] for i in dim['GroupIndexList']
                       if floor['GroupInstanceList'][i]['ID'] == 24 and initial['ID'] != 24]
    assert sum(row['ID'] == 24 for row in refs) == 1
    floor['GroupInstanceList'] = refs
    floor['DimensionList'] = [dict(dim, GroupIndexList=list(range(len(refs))), StartGroupIndex=0)]
    floor['StartGroupIndex'] = 0
    replacements[floor_path] = floor
    guarded = set(source.glob('ExcelOutput/*.json')) | {source / 'resource-manifest.json'}
    guarded |= {source / path for path in golden['sourceFiles']}
    hashes = {str(path.relative_to(source)).replace('\\', '/'): sha(path) for path in sorted(guarded)}
    runs = ROOT / 'build/smoke'
    runs.mkdir(exist_ok=True)
    directory = Path(tempfile.mkdtemp(prefix='krater-offering-', dir=runs))
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
        clientVersion='4.6.0', testFixture=True, kraterOfferingFixture=True)), 'utf-8')
    http, mongo, game = port(), port(), port(socket.SOCK_DGRAM)
    config = directory / 'config.json'
    config.write_text(json.dumps(dict(protocol=dict(wireVersion='4.6.0'), resourceDir=str(fixture),
        httpServer=dict(bindAddress='127.0.0.1', bindPort=http),
        gameServer=dict(bindAddress='127.0.0.1', bindPort=game),
        internalMongoServer=dict(address='127.0.0.1', port=mongo, filePath='./data/database.mv'))), 'utf-8')
    config_bytes = config.read_bytes()
    data = directory / 'data'
    data.mkdir(exist_ok=True)
    (data / 'Gameplay46.json').write_text(json.dumps({'activities': [dict(
        panelId=30013, moduleId=3001301, beginTime=1, endTime=4102444800)]}), 'utf-8')
    classes = directory / 'probe-classes'
    classes.mkdir()
    helper_sources = [ROOT / 'scripts/fixtures/java/org/hkrpg/server/protocol/NativeKraterOfferingProbe.java',
                      ROOT / 'scripts/fixtures/java/org/hkrpg/server/gameplay/KraterOfferingFixtureInspector.java']
    built = subprocess.run([str(java.with_name('javac.exe')), '-encoding', 'UTF-8', '-cp', str(jar),
                            '-d', str(classes), *map(str, helper_sources)], capture_output=True, creationflags=flags)
    (directory / 'javac.log').write_bytes(built.stdout + built.stderr)
    assert built.returncode == 0, directory / 'javac.log'
    options = ['-Dfile.encoding=UTF-8', '-Dstdout.encoding=UTF-8', '-Dstderr.encoding=UTF-8',
               f'-Djdk.net.unixdomain.tmpdir={directory / "tcp-fallback"}']
    classpath = str(classes) + os.pathsep + str(jar)
    credentials, snapshots, inspections = {}, [], []
    stops = paid = tails = payment_count = claim_count = 0
    claimed_goal = False
    taken, payout = set(), {}

    def request(route, body=None):
        req = urllib.request.Request(f'http://127.0.0.1:{http}' + route,
            data=None if body is None else json.dumps(body).encode(), headers={'Content-Type': 'application/json'})
        with urllib.request.urlopen(req, timeout=5) as response:
            return json.load(response)

    def inspect(mode):
        control = directory / f'inspector-{len(inspections)}.json'
        control.write_text(json.dumps(dict(uid=credentials['uid'], progress=progress(paid, tails, taken),
            payments=payment_count, claims=claim_count, tails=tails, suppliedMaterials=paid,
            payout=payout, completed=paid >= 1300, claimedGoal=claimed_goal)), 'utf-8')
        child = subprocess.run([str(java), *options, '-cp', classpath,
            'org.hkrpg.server.gameplay.KraterOfferingFixtureInspector', str(directory), str(control), mode],
            cwd=ROOT, capture_output=True, text=True, encoding='utf-8', timeout=100, creationflags=flags)
        log = directory / f'inspector-{len(inspections)}.log'
        log.write_text(child.stdout + child.stderr, 'utf-8')
        assert child.returncode == 0, log
        value = json.loads(next(line for line in reversed(child.stdout.splitlines()) if line.startswith('{')))
        assert value['passed'] and value['paidMaterials'] == paid
        inspections.append(value)

    batches = [(0, [], 0), (199, [], 0), (1, [1], 0), (1100, [6], 0),
               (17200, [level for level in range(2, 51) if level != 6], 0),
               (200, [51], 2), (100, [51], 1), (0, [], 0)]
    for index, (give, claim_levels, tail_quantity) in enumerate(batches):
        after_taken = taken | {level for level in claim_levels if level <= 50}
        claim_goal = index == 3
        control = dict(credentials, seed=index == 0, give=give, levels=claim_levels,
            before=progress(paid, tails, taken), submitted=progress(paid + give, tails, taken),
            after=progress(paid + give, tails + tail_quantity, after_taken),
            rewards=level_recipe(claim_levels, tail_quantity), goalRewards=goal_rewards,
            claimGoal=claim_goal, questStatus=3 if claimed_goal else 2 if paid + give >= 1300 else 1)
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
                    login = request('/hkrpg_cn/mdk/shield/api/login', dict(account='krater-offering-native', password='local-test'))
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
                        'org.hkrpg.server.protocol.NativeKraterOfferingProbe', str(game)],
                        cwd=directory, stdin=subprocess.PIPE, stdout=out, stderr=subprocess.STDOUT, creationflags=flags)
                    child.stdin.write((json.dumps(control) + '\n').encode())
                    child.stdin.close()
                    deadline, roster_given, material_given = time.monotonic() + 180, False, False
                    try:
                        while child.poll() is None:
                            text = probe_log.read_text('utf-8')
                            if index == 0 and not roster_given:
                                match = re.search(r'READY_UID=(\d+)', text)
                                if match:
                                    process.stdin.write(f'give 1001 @{int(match.group(1))}\n'.encode())
                                    process.stdin.flush()
                                    roster_given = True
                            if give > 0 and not material_given:
                                match = re.search(r'READY_MATERIAL=(\d+)', text)
                                if match:
                                    process.stdin.write(f'give 120004 x{give} @{int(match.group(1))}\n'.encode())
                                    process.stdin.flush()
                                    material_given = True
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
                if index:
                    assert value['submittedMaterial'] == give and value['after'] == control['after']
                    paid += give
                    payment_count += bool(give)
                    claim_count += bool(claim_levels)
                    tails += tail_quantity
                    taken = after_taken
                    actual_reward = level_recipe(claim_levels, tail_quantity)
                    if claim_goal:
                        claimed_goal = True
                        for item, count in goal_rewards.items():
                            actual_reward[item] = actual_reward.get(item, 0) + count
                    for item, count in actual_reward.items():
                        payout[item] = payout.get(item, 0) + count
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
        inspect('seed' if index == 0 else 'inspect')
        print(json.dumps(dict(phase=index, paidMaterials=paid, level=progress(paid, tails, taken)['level'],
                              sourceLevelRewards=len(taken), tailQuantity=tails, normalStops=stops)), flush=True)
    inspect('expired')
    assert paid == 18800 and taken == set(range(1, 51)) and tails == 3
    assert payment_count == 6 and claim_count == 5 and claimed_goal and stops == 8
    assert sha(jar) == artifact and config.read_bytes() == config_bytes
    for path, expected in hashes.items():
        assert sha(source / path) == expected, path
    report = dict(passed=True, artifactSha256=artifact, runtimeDirectory=str(directory),
        snapshots=snapshots, inspections=inspections, nativeRequests=sum(row['nativeRequests'] for row in snapshots),
        rejectedRequests=sum(row.get('rejectedRequests', 0) for row in snapshots), paidMaterials=paid,
        actualPayout=payout, claimedOriginalLevelRewards=sorted(taken), sourceLongTailQuantity=tails,
        paidReceipts=payment_count, rewardReceipts=claim_count, completedOriginalGoal=6029857,
        actualGoalRewards=goal_rewards, normalStops=stops, sameUIDNormalH2ProcessRestarts=7,
        sourceResourceHashes=hashes, storyPrerequisitesAndSceneAreFixtures=True,
        materialsAreExplicitConsoleFixtures=True, operatorWindowIsFixture=True,
        sceneGroupReferencesReduced=True, originalWholeGroupDefinitionsUnmodified=True,
        targetCompletionPrewritten=False, paidReceiptsPrewritten=False, sourceResourcesModified=False,
        operatorDataModified=False, clientGameplayVerified=False, chestFinderUseVerified=False,
        wholeActivityComplete=False, wholeGameComplete=False)
    output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n', 'utf-8')
    print(json.dumps(dict(passed=True, nativeRequests=report['nativeRequests'], paidMaterials=paid,
                         normalStops=stops, runtimeDirectory=str(directory))))


if __name__ == '__main__':
    main()
