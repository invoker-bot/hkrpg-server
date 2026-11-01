"""Open original world chests, claim source activities and restore actual H2 inventory."""
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
from final_world_mechanism_probe_cases import IDS as FINAL_IDS, PREREQUISITES as FINAL_PREREQUISITES, actions as final_actions
from world_mechanism_probe_cases import (LEGACY_IDS, DYNAMIC_IDS, REMAINING_IDS,
                                        REMAINING_PREREQUISITES, dynamic_actions, remaining_actions)

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
    parser.add_argument('--output', type=Path, default=ROOT / 'build/world-chest-native.json')
    parser.add_argument('--base-zone', action='store_true', help='Verify the six Base Zone exploration chests')
    parser.add_argument('--map-chests', action='store_true', help='Verify original chests across seven worlds')
    parser.add_argument('--mechanisms', action='store_true', help='Verify guard/puzzle unlock, loot and H2 restart')
    parser.add_argument('--dynamic', action='store_true', help='Verify staged boards, tramcar replacements and spear variants')
    parser.add_argument('--remaining', action='store_true', help='Verify cross-group, mission and time-lock completion receipts')
    parser.add_argument('--remaining-case', type=int, choices=REMAINING_IDS, help='Verify one --remaining chest in isolation')
    parser.add_argument('--final-bindings', action='store_true', help='Verify compass guards, cargo NPC and QTE reward with H2 restarts')
    parser.add_argument('--final-case', type=int, choices=FINAL_IDS, help='Diagnose one --final-bindings chest in isolation')
    args = parser.parse_args()
    assert args.final_case is None or args.final_bindings, '--final-case requires --final-bindings'
    assert args.remaining_case is None or args.remaining, '--remaining-case requires --remaining'
    assert sum([args.base_zone, args.map_chests, args.mechanisms, args.dynamic, args.remaining, args.final_bindings]) <= 1, 'Choose one chest fixture'
    if args.dynamic or args.remaining or args.final_bindings:
        args.mechanisms = True
    original_resources = args.base_zone or args.map_chests or args.mechanisms
    jar, output = args.jar.resolve(), args.output.resolve()
    assert jar.is_file() and jar.is_relative_to(ROOT / 'build')
    assert output.is_relative_to(ROOT / 'build')
    java = next((ROOT / '.tools').glob('jdk-21*/bin/java.exe'), None)
    assert java
    artifact = sha(jar)
    source = ROOT / 'resources/4.6'
    fixture_name = ('world-mechanisms-4.6.json' if args.mechanisms else 'map-chests-4.6.json' if args.map_chests
                    else 'base-zone-chests-4.6.json' if args.base_zone else 'world-chest-4.6.json')
    golden = json.loads((ROOT / 'src/test/resources/fixtures' / fixture_name).read_text('utf-8'))
    final_mission_rewards = []
    if args.final_bindings:
        extra = json.loads((ROOT / 'src/main/resources/gameplay/mascot-extension-4.6.json').read_text('utf-8'))
        golden['locations']['20433651'] = dict(extra['location'],entry=2043301,world=501)
        mascot = json.loads((ROOT / 'src/test/resources/fixtures/mascot-4.6.json').read_text('utf-8'))
        for path in ('ExcelOutput/MapEntrance.json', 'ExcelOutput/MazePlane.json'):
            key = 'ID' if 'MapEntrance' in path else 'PlaneID'
            merged = {row[key]: row for row in golden['records'][path]}
            merged.update({row[key]: row for row in mascot['records'][path]})
            golden['records'][path] = list(merged.values())
        mission = next(row for row in mascot['records']['ExcelOutput/MainMission.json']
                       if row['MainMissionID'] == extra['control']['mission'])
        reward = next(row for row in mascot['records']['ExcelOutput/RewardData.json']
                      if row['RewardID'] == mission['RewardID'])
        recipe = {1: reward['Hcoin']} if reward.get('Hcoin') else {}
        for key, item in reward.items():
            if key.startswith('ItemID_') and item:
                recipe[item] = recipe.get(item, 0) + reward['Count_' + key.split('_')[1]]
        final_mission_rewards = [dict(mission=mission['MainMissionID'], rewards=recipe)]
    tables = {Path(path).stem: rows for path, rows in golden['records'].items()}
    goals = []
    for quest in sorted(tables.get('QuestData', []), key=lambda row: row['QuestID']):
        finish = next(row for row in tables['FinishWay'] if row['ID'] == quest['FinishWayID'])
        reward = next(row for row in tables['RewardData'] if row['RewardID'] == quest['RewardID'])
        recipes = {1: reward['Hcoin']} if reward.get('Hcoin') else {}
        for key, item in reward.items():
            if key.startswith('ItemID_') and item:
                recipes[item] = recipes.get(item, 0) + reward['Count_' + key.split('_')[1]]
        goals.append(dict(id=quest['QuestID'], goal=finish['Progress'], rewards=recipes))
    assert [(goal['id'], goal['goal']) for goal in goals] == (
        [] if original_resources else [(6029850, 5), (6029851, 10), (6029852, 15)])
    chests = [dict(location, id=int(key)) for key, location in golden['locations'].items()]
    if args.map_chests:
        chests = [row for row in chests if row['id'] != 20001632]  # Locked guard challenge is a rejection fixture.
    chests.sort(key=lambda row: row['id'])
    if args.mechanisms:
        selected = FINAL_IDS if args.final_bindings else REMAINING_IDS if args.remaining else DYNAMIC_IDS if args.dynamic else LEGACY_IDS
        if args.final_case is not None:
            selected = (args.final_case,)
        if args.remaining_case is not None:
            selected = (args.remaining_case,)
        chests = sorted((row for row in chests if row['id'] in selected), key=lambda row: selected.index(row['id']))
    expected_chests = len(FINAL_IDS) if args.final_bindings else len(REMAINING_IDS) if args.remaining else len(DYNAMIC_IDS) if args.dynamic else 7 if args.map_chests or args.mechanisms else 6 if args.base_zone else 24
    if args.final_case is not None or args.remaining_case is not None:
        expected_chests = 1
    assert len(chests) == expected_chests and len({row['id'] for row in chests}) == expected_chests
    plane, floor = chests[0]['plane'], chests[0]['floor']
    assert args.map_chests or args.mechanisms or all(row['plane'] == plane and row['floor'] == floor for row in chests)
    entry = next(row for row in tables['MapEntrance'] if row['PlaneID'] == plane and row['FloorID'] == floor)
    world = next(row['WorldID'] for row in tables['MazePlane'] if row['PlaneID'] == plane)
    stage = dict(plane=plane, floor=floor, entry=entry['ID'], world=world, pos=dict(x=0, y=0, z=0))
    replacements = {path: json.loads((source / path).read_text('utf-8')) for path in golden['records']}
    replacements.update(golden['groups'])
    replacements.update(golden.get('headers', {}))
    floor_path = f'Config/LevelOutput/RuntimeFloor/P{plane}_F{floor}.json'
    original = json.loads((source / floor_path).read_text('utf-8'))
    dim = next(row for row in original['DimensionList'] if row.get('ID', 0) == 0)
    initial = original['GroupInstanceList'][dim.get('StartGroupIndex', original.get('StartGroupIndex', 0))]
    groups = {row['group'] for row in chests if row['plane'] == plane and row['floor'] == floor}
    refs = [initial] + [original['GroupInstanceList'][i] for i in dim['GroupIndexList']
                       if original['GroupInstanceList'][i]['ID'] in groups
                       and original['GroupInstanceList'][i]['ID'] != initial['ID']]
    assert all(sum(row['ID'] == group for row in refs) == 1 for group in groups)
    original['GroupInstanceList'] = refs
    original['DimensionList'] = [dict(dim, GroupIndexList=list(range(len(refs))), StartGroupIndex=0)]
    original['StartGroupIndex'] = 0
    replacements[floor_path] = original
    guarded = set(source.glob('ExcelOutput/*.json')) | {source / 'resource-manifest.json'}
    guarded |= {source / path for path in golden.get('sourceFiles', [])}
    if args.mechanisms:
        guarded |= {source / path for section in ('groups', 'floors', 'headers') for path in golden[section]}
    hashes = {str(path.relative_to(source)).replace('\\', '/'): sha(path) for path in sorted(guarded)}
    runs = ROOT / 'build/smoke'
    runs.mkdir(exist_ok=True)
    directory = Path(tempfile.mkdtemp(prefix='world-mechanisms-' if args.mechanisms else 'map-chests-' if args.map_chests
                                     else 'base-zone-chests-' if args.base_zone else 'world-chests-', dir=runs))
    fixture = directory / 'resources-fixture'
    if original_resources:
        # Exercise the operator's original resources read-only, including unmodified floor indexes.
        fixture.mkdir()
        bindings = []
    else:
        bindings = mirror_resources(source, fixture, replacements)
    for path in golden.get('headers', {}):
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
        clientVersion='4.6.0', testFixture=True, worldChestFixture=True,
        baseZoneChestFixture=args.base_zone, mapChestFixture=args.map_chests or args.mechanisms,
        mechanismFixture=args.mechanisms)), 'utf-8')
    http, mongo, game = port(), port(), port(socket.SOCK_DGRAM)
    config = directory / 'config.json'
    config.write_text(json.dumps(dict(protocol=dict(wireVersion='4.6.0'), resourceDir=str(source if original_resources else fixture),
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
    sources = [ROOT / 'scripts/fixtures/java/org/hkrpg/server/protocol/NativeWorldChestProbe.java',
               ROOT / 'scripts/fixtures/java/org/hkrpg/server/gameplay/WorldChestFixtureInspector.java']
    built = subprocess.run([str(java.with_name('javac.exe')), '-encoding', 'UTF-8', '-cp', str(jar),
                            '-d', str(classes), *map(str, sources)], capture_output=True, creationflags=flags)
    (directory / 'javac.log').write_bytes(built.stdout + built.stderr)
    assert built.returncode == 0, directory / 'javac.log'
    options = ['-Dfile.encoding=UTF-8', '-Dstdout.encoding=UTF-8', '-Dstderr.encoding=UTF-8',
               f'-Djdk.net.unixdomain.tmpdir={directory / "tcp-fallback"}']
    classpath = str(classes) + os.pathsep + str(jar)
    credentials, snapshots, inspections, known, claimed = {}, [], [], [], []
    mechanism_progress = {}
    prop_progress = {}
    value_progress = {}
    mission_progress = {}
    prerequisite = None
    mechanism_spec = None
    mascot_progress = {}
    mission_item = 0
    combat_payout = {}
    stops = 0

    def request(route, body=None):
        req = urllib.request.Request(f'http://127.0.0.1:{http}' + route,
            data=None if body is None else json.dumps(body).encode(),
            headers={'Content-Type': 'application/json'})
        with urllib.request.urlopen(req, timeout=5) as response:
            return json.load(response)

    def inspect(mode):
        control = directory / f'inspector-{len(inspections)}.json'
        control.write_text(json.dumps(dict(uid=credentials['uid'], known=known, claimed=claimed, baseZone=args.base_zone,
                                          mapChests=args.map_chests or args.mechanisms,
                                          mechanismProgress=mechanism_progress if args.mechanisms else None,
                                          propProgress=prop_progress if args.dynamic or args.remaining or args.final_bindings else None,
                                          valueProgress=value_progress if args.remaining or args.final_bindings else None,
                                          missionProgress=mission_progress if args.remaining or args.final_bindings else None,
                                          remainingFixture=args.remaining or args.final_bindings, prerequisite=prerequisite,
                                          finalBindings=args.final_bindings, mascotProgress=mascot_progress, missionItem=mission_item,
                                          combatPayout=combat_payout,
                                          missionRewards=final_mission_rewards if mascot_progress.get('20433651', 0) >= 3 else [],
                                          dynamicFixture=args.dynamic,
                                          battleBalancePhase=not args.final_bindings and (bool(mechanism_spec and mechanism_spec['op'] == 'guard')
                                                             or any(not p['complete'] for p in mechanism_progress.values())),
                                          captureBattleBalances=bool(not args.final_bindings and mode == 'inspect' and mechanism_spec
                                                                     and mechanism_spec['op'] == 'guard'
                                                                     and not mechanism_spec['remaining']),
                                          goals=goals, stage=stage)), 'utf-8')
        child = subprocess.run([str(java), *options, '-cp', classpath,
            'org.hkrpg.server.gameplay.WorldChestFixtureInspector', str(directory), str(control), mode],
            cwd=ROOT, capture_output=True, text=True, encoding='utf-8', timeout=100, creationflags=flags)
        log = directory / f'inspector-{len(inspections)}.log'
        log.write_text(child.stdout + child.stderr, 'utf-8')
        assert child.returncode == 0, log
        value = json.loads(next(line for line in reversed(child.stdout.splitlines()) if line.startswith('{')))
        assert value['passed'] and value['openedChests'] == len(known)
        inspections.append(value)

    actions = None
    if args.mechanisms:
        catalog = json.loads((ROOT / 'src/main/resources/gameplay/world-mechanisms-4.6.json').read_text('utf-8'))
        mechanisms = catalog['rules']
        by_id = {row['id']: row for row in chests}
        chests = [by_id[id_] for id_ in selected]
        actions = [None]
        for chest in ([] if args.final_bindings else chests):
            rule = mechanisms[str(chest['id'])]
            common = dict(chest, controller=rule['controller'], finish=rule['finish'] or
                          (rule['reports'][0] if rule['reports'] else 0), defeated=[], remaining=[])
            initial_state = 11 if rule['initial'] == 'ChestLocked' else 20
            if rule['kind'] == 'GUARDS':
                original_group = golden['groups'][chest['path']]
                for index, guard in enumerate(rule['guards']):
                    row = next(m for m in original_group['MonsterList'] if m['ID'] == guard)
                    actions.append(dict(common, op='guard', guard=guard, npc=row['NPCMonsterID'],
                                        event=row['EventID'], before=11,
                                        after=12 if index + 1 == len(rule['guards']) else 11,
                                        defeated=rule['guards'][:index + 1], remaining=rule['guards'][index + 1:]))
                common.update(defeated=rule['guards'])
            else:
                actions.append(dict(common, op='finish', before=initial_state, after=12))
            actions.append(dict(common, op='claim', before=12, after=13))
            actions.append(dict(common, op='verify', before=13, after=13))
        if args.final_bindings:
            actions = final_actions(chests, catalog, golden['groups'])
            prerequisite = FINAL_PREREQUISITES.get(chests[0]['id'])
        if args.dynamic:
            actions = dynamic_actions(chests, catalog)
        if args.remaining:
            actions = remaining_actions(chests, catalog)
            prerequisite = REMAINING_PREREQUISITES.get(chests[0]['id'])
        batches = [[spec] if spec and spec['op'] == 'claim' else [] for spec in actions]
        first = chests[0]
        stage = dict(plane=first['plane'], floor=first['floor'], entry=first['entry'],
                     world=first['world'], pos=dict(x=0, y=0, z=0))
    else:
        batches = ([[], *[[row] for row in chests], []] if args.map_chests else [[], chests, []]
               if args.base_zone else [[], chests[:5], chests[5:10], chests[10:15], chests[15:], []])
    for index, batch in enumerate(batches):
        mechanism_spec = actions[index] if actions else None
        target_claim = goals[index - 1]['id'] if goals and index in range(1, 4) else 0
        current_chests = [row for row in chests if row['plane'] == stage['plane'] and row['floor'] == stage['floor']]
        control = dict(credentials, **stage, allChests=current_chests, chests=batch, goals=goals,
                       known=known, claimed=claimed, seed=index == 0, claim=target_claim)
        if mechanism_spec:
            control['mechanism'] = mechanism_spec
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
                        'org.hkrpg.server.protocol.NativeWorldChestProbe', str(game)],
                        cwd=directory, stdin=subprocess.PIPE, stdout=out, stderr=subprocess.STDOUT, creationflags=flags)
                    child.stdin.write((json.dumps(control) + '\n').encode())
                    child.stdin.close()
                    deadline, granted = time.monotonic() + 180, False
                    try:
                        while child.poll() is None:
                            if index == 0 and not granted:
                                match = re.search(r'READY_UID=(\d+)', probe_log.read_text('utf-8'))
                                if match:
                                    process.stdin.write(f'give 1001 @{int(match.group(1))}\n'.encode())
                                    process.stdin.flush()
                                    granted = True
                            if time.monotonic() > deadline:
                                raise TimeoutError(probe_log)
                            time.sleep(.2)
                    finally:
                        if child.poll() is None:
                            child.terminate()
                            child.wait(timeout=15)
                if child.returncode != 0:
                    # Preserve the live server's stacks before shutdown when a wire probe fails.
                    trace = subprocess.run([str(java.with_name('jcmd.exe')), str(process.pid), 'Thread.print'],
                        capture_output=True, timeout=15, creationflags=flags)
                    (directory / f'server-{index}-threads.log').write_bytes(trace.stdout + trace.stderr)
                assert child.returncode == 0, probe_log
                value = json.loads(next(line for line in reversed(probe_log.read_text('utf-8').splitlines()) if line.startswith('{')))
                assert value['passed']
                credentials.setdefault('uid', value['uid'])
                assert credentials['uid'] == value['uid']
                snapshots.append(value)
                if args.final_bindings:
                    for item, count in value.get('combatPayout', {}).items():
                        combat_payout[item] = combat_payout.get(item, 0) + count
                if index:
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
        if mechanism_spec and not mechanism_spec.get('mascot') and mechanism_spec['op'] in ('guard', 'finish', 'destroy', 'talk', 'property') and (
                mechanism_spec['after'] == 12 or mechanism_spec['op'] == 'guard'):
            mechanism_progress[str(mechanism_spec['id'])] = dict(
                complete=mechanism_spec['after'] == 12, defeated=mechanism_spec['defeated'],
                late=bool(mechanism_spec.get('late')))
        if (args.dynamic or args.remaining or args.final_bindings) and mechanism_spec:
            prefix = f"{stage['plane']}:{stage['floor']}:0:0:{mechanism_spec.get('actorGroup', mechanism_spec['group'])}:"
            for instance, state in mechanism_spec.get('persistedStates', mechanism_spec.get('states', {})).items():
                prop_progress[prefix + instance] = state
            if args.remaining and mechanism_spec['id'] == 20003658 and mechanism_spec['op'] == 'finish':
                # The original pad-puzzle completion also opens this cross-group bridge.
                prop_progress['20003:20003001:0:0:1:300025'] = 1
            if mechanism_spec['op'] == 'property':
                value_progress[f"floor:{stage['floor']}:{mechanism_spec['floorValue']}"] = 1
                value_progress[f"group:{stage['floor']}:{mechanism_spec['actorGroup']}:{mechanism_spec['property']}"] = 1
            mission_progress.update(mechanism_spec.get('missions', {}))
            if mechanism_spec.get('mascotState'):
                mascot_progress[str(mechanism_spec['id'])] = mechanism_spec['mascotState']
            value_progress.update(mechanism_spec.get('floorValues', {}))
            mission_item = mechanism_spec.get('missionItem', mission_item)
        inspect('seed' if index == 0 else 'inspect')
        if actions and index + 1 < len(actions):
            upcoming = actions[index + 1]
            destination = upcoming.get('travel', upcoming)
            next_stage = dict(plane=destination['plane'], floor=destination['floor'], entry=destination['entry'],
                              world=destination['world'], pos=dict(x=0, y=0, z=0))
            # Placement only; all defeat, unlock and reward progress must come through KCP.
            next_case = (args.remaining or args.final_bindings) and (mechanism_spec is None or upcoming['id'] != mechanism_spec['id'])
            if next_stage != stage or next_case:
                stage = next_stage
                prerequisite = (FINAL_PREREQUISITES if args.final_bindings else REMAINING_PREREQUISITES).get(upcoming['id']) if next_case else None
                mechanism_spec = None
                inspect('move')
        if args.map_chests and index + 1 < len(batches) and batches[index + 1]:
            next_chest = batches[index + 1][0]
            stage = dict(plane=next_chest['plane'], floor=next_chest['floor'],
                         entry=next_chest['entry'], world=next_chest['world'], pos=dict(x=0, y=0, z=0))
            # Only move the owned smoke character; never prewrite claims or inventory.
            inspect('move')
        print(json.dumps(dict(phase=index, openedChests=len(known), normalStops=stops)), flush=True)
    if not original_resources:
        inspect('expired')
    assert len(known) == expected_chests and len(set(known)) == expected_chests
    assert claimed == [goal['id'] for goal in goals] and stops == len(batches)
    assert sum(len(snapshot.get('chestIdentityNotifications', [])) for snapshot in snapshots) == expected_chests
    assert sha(jar) == artifact and config.read_bytes() == config_bytes
    for path, expected in hashes.items():
        assert sha(source / path) == expected, path
    report = dict(passed=True, artifactSha256=artifact, runtimeDirectory=str(directory),
        snapshots=snapshots, inspections=inspections, nativeRequests=sum(row['nativeRequests'] for row in snapshots),
        openedOriginalChests=known, sourceBoundActivityCredits=0 if original_resources else 30,
        chestIdentityNotifications=expected_chests, baseZone=args.base_zone, mapChests=args.map_chests,
        mechanisms=args.mechanisms, mechanismProgress=mechanism_progress,
        dynamicMechanisms=args.dynamic, propProgress=prop_progress,
        finalBindings=args.final_bindings, selectedFinalCase=args.final_case,
        mascotProgress=mascot_progress, combatPayout=combat_payout,
        sourceMissionRewards=final_mission_rewards,
        remainingMechanisms=args.remaining, selectedRemainingCase=args.remaining_case,
        valueProgress=value_progress, missionProgress=mission_progress,
        guardBattleLootBalancesRebased=args.mechanisms and not args.dynamic and not args.remaining and not args.final_bindings,
        rejectedRepeatedOpenings=sum(row.get('rejectedRepeatedOpenings', 0) for row in snapshots),
        normalStops=stops, sameUIDNormalH2ProcessRestarts=stops - 1, completedOriginalActivityQuests=claimed,
        actualQuestRewards={goal['id']: goal['rewards'] for goal in goals}, sourceResourceHashes=hashes,
        chestLootIsCustomServerPolicy=True, storyPrerequisitesAndSceneAreFixtures=True,
        operatorWindowIsFixture=True, sceneGroupReferencesReduced=not original_resources,
        originalWholeGroupDefinitionsUnmodified=True, targetCompletionPrewritten=False,
        countReceiptsPrewritten=False, sourceResourcesModified=False, operatorDataModified=False,
        clientGameplayVerified=False, wholeActivityComplete=False, wholeGameComplete=False)
    output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n', 'utf-8')
    print(json.dumps(dict(passed=True, nativeRequests=report['nativeRequests'], openedChests=expected_chests,
                         normalStops=stops, runtimeDirectory=str(directory))))


if __name__ == '__main__':
    main()
