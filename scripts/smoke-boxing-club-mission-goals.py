"""隔离账号通过4.6原生KCP进行原始拳馆场景中两季通关后七个剧情目标与50项非周期任务、原始奖励、重启恢复及领奖防重；等级/主线门槛为明确夹具。"""
import argparse
import hashlib
import json
import os
import re
from pathlib import Path
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
    parser.add_argument('--output', type=Path, default=ROOT / 'build/boxing-story-native.json')
    args = parser.parse_args()
    jar, output = args.jar.resolve(), args.output.resolve()
    assert jar.is_relative_to(ROOT / 'build') and output.is_relative_to(ROOT / 'build')
    java = next((ROOT / '.tools').glob('jdk-21*/bin/java.exe'), None)
    assert java and jar.is_file()
    artifact = sha(jar)
    source = ROOT / 'resources/4.6'
    story_source = json.loads((ROOT / 'src/test/resources/fixtures/boxing-club-mission-goals-4.6.json').read_text('utf-8'))
    mission_ids = sorted({row['MainMissionID'] for row in story_source['rows']})
    mission_paths = sorted(p for id in mission_ids for p in (source / f'Config/Level/Mission/{id}').glob('*.json'))
    guarded = sorted(source.glob('ExcelOutput/*.json')) + [source / 'resource-manifest.json'] + mission_paths
    hashes = {p.relative_to(source).as_posix(): sha(p) for p in guarded}
    runs = ROOT / 'build/smoke'
    runs.mkdir(exist_ok=True)
    directory = Path(tempfile.mkdtemp(prefix='boxing-story-', dir=runs))
    assert directory.resolve().is_relative_to(runs.resolve())
    fixture = directory / 'resources-fixture'
    bindings = mirror_resources(source, fixture, {})
    # The generic fixture intentionally leaves Mission empty; bind only these seven original directories.
    for id in mission_ids:
        target = fixture / f'Config/Level/Mission/{id}'
        origin = source / f'Config/Level/Mission/{id}'
        if os.name == 'nt':
            bindings.append({'target': str(target), 'source': str(origin)})
        else:
            target.symlink_to(origin, target_is_directory=True)
    flags = subprocess.CREATE_NO_WINDOW if os.name == 'nt' else 0
    if bindings:
        plan = directory / 'junctions.json'
        plan.write_text(json.dumps(bindings), 'utf-8')
        helper = directory / 'prepare.ps1'
        helper.write_text("param([string]$Plan)\n$ErrorActionPreference='Stop'\nforeach ($binding in (Get-Content -LiteralPath $Plan -Raw | ConvertFrom-Json)) { New-Item -ItemType Junction -Path $binding.target -Value $binding.source | Out-Null }\n", 'utf-8')
        subprocess.run(['powershell', '-NoProfile', '-NonInteractive', '-File', str(helper), '-Plan', str(plan)], check=True, capture_output=True, creationflags=flags)
    (fixture / 'resource-manifest.json').write_text(json.dumps({'clientVersion': '4.6.0', 'testFixture': True, 'boxingResonanceFixture': True, 'boxingActivityQuestFixture': True, 'boxingMissionGoalsFixture': True}), 'utf-8')
    http, mongo, game = port(), port(), port(socket.SOCK_DGRAM)
    config = directory / 'config.json'
    config.write_text(json.dumps({'protocol': {'wireVersion': '4.6.0'}, 'resourceDir': str(fixture), 'httpServer': {'bindAddress': '127.0.0.1', 'bindPort': http}, 'gameServer': {'bindAddress': '127.0.0.1', 'bindPort': game}, 'internalMongoServer': {'address': '127.0.0.1', 'port': mongo, 'filePath': './data/database.mv'}}), 'utf-8')
    config_bytes = config.read_bytes()
    data = directory / 'data'
    data.mkdir(exist_ok=True)
    (data / 'Gameplay46.json').write_text(json.dumps({'activities': [{'panelId': 40003, 'moduleId': module, 'beginTime': 1, 'endTime': 4102444800} for module in range(4000301, 4000308)] + [{"panelId":50000,"moduleId":module,"beginTime":1,"endTime":4102444800} for module in [5000001,5000002,5000003,5000004,5000005,5000006]] + [{"panelId":40003,"moduleId":4000308,"beginTime":1,"endTime":4102444800}]}), 'utf-8')
    groups = {row['StageGroupID']: row for row in json.loads((source / 'ExcelOutput/BoxingClubStageGroup.json').read_text('utf-8'))}
    events = {(row['EventID'], row.get('WorldLevel', 0)): row['StageID'] for row in json.loads((source / 'ExcelOutput/PlaneEvent.json').read_text('utf-8')) if row.get('StageID', 0) > 0}
    stages = {row['EventID']: row for row in json.loads((source / 'ExcelOutput/BoxingClubStage.json').read_text('utf-8'))}
    specs = {}
    for challenge in json.loads((source / 'ExcelOutput/BoxingClubChallenge.json').read_text('utf-8')):
        if challenge['ChallengeID'] < 6:
            continue
        resonance, group = next(iter(challenge['StageBuffAndGroupMap'].items()))
        event_ids = groups[group]['EventIDList']
        specs[str(challenge['ChallengeID'])] = dict(group=group, resonance=int(resonance), events=event_ids, stages=[events[(event, 0)] for event in event_ids], optionalLists=[stages[event]['BuffOptionalList'] for event in event_ids])
    resident_source = json.loads((ROOT / 'src/test/resources/fixtures/boxing-club-activity-quests-4.6.json').read_text('utf-8'))
    resident_tables = {Path(e['path']).stem: e['rows'] for e in resident_source['inputs']}
    resident_quests = resident_tables['QuestData']
    resident_finishes = {str(row['ID']): row for row in resident_tables['FinishWay']}

    activity_windows = json.loads((data / 'Gameplay46.json').read_text('utf-8'))['activities']
    raw_rewards = {row['RewardID']:row for row in json.loads((source / 'ExcelOutput/RewardData.json').read_text('utf-8'))}
    def recipe(row):
        result = {}
        for key,id in [('Hcoin',1),('Scoin',2),('Mcoin',3)]:
            if row.get(key,0): result[id] = result.get(id,0) + row[key]
        for key,id in row.items():
            if key.startswith('ItemID_') and id: result[id] = result.get(id,0) + row['Count_'+key.split('_')[1]]
        return result
    quest_recipes = {str(q['QuestID']):recipe(raw_rewards[q['RewardID']]) for q in resident_quests}
    first_specs = {}
    for challenge in json.loads((source / 'ExcelOutput/BoxingClubChallenge.json').read_text('utf-8')):
        if challenge['ChallengeID'] > 5: continue
        linked = {}
        for group in challenge['StageGroupList']:
            event_ids = groups[group]['EventIDList']
            linked[str(group)] = dict(stages=[events[(e,0)] for e in event_ids],buffs=[stages[e].get('BuffID',0) for e in event_ids])
        first_specs[str(challenge['ChallengeID'])] = dict(groups=linked,buff=challenge['ChallengeBuff'],firstPassItems=recipe(raw_rewards[challenge['FirstPassRewardID']]))

    classes = directory / 'probe-classes'
    classes.mkdir()
    probe = ROOT / 'scripts/fixtures/java/org/hkrpg/server/protocol/NativeBoxingClubMissionGoalsProbe.java'
    child = subprocess.run([str(java.with_name('javac.exe')), '-encoding', 'UTF-8', '-cp', str(jar), '-d', str(classes), str(probe), str(ROOT / 'scripts/fixtures/java/org/hkrpg/server/gameplay/BoxingClubMissionGoalsFixtureInspector.java')], capture_output=True, creationflags=flags)
    (directory / 'javac.log').write_bytes(child.stdout + child.stderr)
    assert child.returncode == 0, directory / 'javac.log'
    java_flags = ['-Dfile.encoding=UTF-8', '-Dstdout.encoding=UTF-8', '-Dstderr.encoding=UTF-8', f'-Djdk.net.unixdomain.tmpdir={directory / "tcp-fallback"}']

    def request(route, body=None):
        req = urllib.request.Request(f'http://127.0.0.1:{http}' + route, data=None if body is None else json.dumps(body).encode(), headers={'Content-Type': 'application/json'})
        with urllib.request.urlopen(req, timeout=5) as reply:
            return json.load(reply)

    credentials, snapshots, inspections = {}, [], []
    stops = 0
    for index in range(6):
        with (directory / f'server-{index}.log').open('wb') as log:
            process = subprocess.Popen([str(java), *java_flags, '-Xmx1536m', '-jar', str(jar), '--no-handbook'], cwd=directory, stdin=subprocess.PIPE, stdout=log, stderr=subprocess.STDOUT, creationflags=flags)
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
                        raise TimeoutError(directory / f'server-{index}.log')
                    time.sleep(.2)
                if not credentials:
                    login = request('/hkrpg_cn/mdk/shield/api/login', {'account': 'boxing-activity-native', 'password': 'local-test'})
                    assert login['retcode'] == 0
                    account = login['data']['account']
                    combo = request('/hkrpg_cn/combo/granter/login/v2/login', {'data': json.dumps({'uid': account['uid'], 'token': account['token']})})
                    assert combo['retcode'] == 0
                    credentials.update(accountUid=account['uid'], comboToken=combo['data']['combo_token'])
                assert sha(jar) == artifact
                probe_log = directory / f'probe-{index}.log'
                with probe_log.open('wb') as log_probe:
                    child = subprocess.Popen([str(java), *java_flags, '-cp', str(classes) + os.pathsep + str(jar), 'org.hkrpg.server.protocol.NativeBoxingClubMissionGoalsProbe', str(game)], cwd=directory, stdin=subprocess.PIPE, stdout=log_probe, stderr=subprocess.STDOUT, creationflags=flags)
                    child.stdin.write((json.dumps(dict(credentials, phase=index, boxSpecs=specs, firstSpecs=first_specs, questRecipes=quest_recipes)) + '\n').encode())
                    child.stdin.close()
                    deadline = time.monotonic() + 250
                    granted = False
                    try:
                        while child.poll() is None:
                            if index == 0 and not granted:
                                ready = re.search(r'READY_UID=(\d+)', probe_log.read_text('utf-8'))
                                if ready:
                                    uid = int(ready.group(1))
                                    assert uid == 10001
                                    process.stdin.write(f'give 1001 @{uid}\n'.encode())
                                    process.stdin.flush()
                                    granted = True
                            if time.monotonic() > deadline:
                                raise TimeoutError(probe_log)
                            time.sleep(.2)
                    finally:
                        if child.poll() is None:
                            child.terminate()
                            child.wait(timeout=15)
                assert child.returncode == 0, probe_log
                snapshot = json.loads(next(line for line in reversed(probe_log.read_text('utf-8').splitlines()) if line.startswith('{"')))
                assert snapshot['passed'] and snapshot['phase'] == index and not snapshot['completionPrewritten']
                assert snapshot['acceptedRoundWins'] == [0, 20, 3, 21, 0, 0][index]
                credentials.setdefault('uid', snapshot['uid'])
                assert snapshot['uid'] == credentials['uid']
                if index == 0:
                    assert granted
                assert snapshot['claimedActivityRewards'] == [0,21,0,29,0,0][index]
                assert snapshot['storyGoalStatusVerified'] == (index > 0)
                snapshots.append(snapshot)
            finally:
                if process.poll() is None:
                    try:
                        process.stdin.write(b'stop\n')
                        process.stdin.flush()
                        process.wait(timeout=45)
                        assert process.returncode == (1000 if os.name == 'nt' else 232), directory
                        stops += 1
                    except BaseException:
                        if process.poll() is None:
                            process.terminate()
                            process.wait(timeout=15)
                        raise
                    finally:
                        process.stdin.close()
        control = directory / 'inspection-control.json'
        control.write_text(json.dumps(dict(credentials, boxSpecs=specs, firstSpecs=first_specs, questRecipes=quest_recipes, activityQuests=resident_quests, activityFinishes=resident_finishes, missionSource=story_source)), 'utf-8')
        phase = ['prepare','first','slow','settled','expired','reopened'][index]
        checked = subprocess.run([str(java), *java_flags, '-cp', str(classes) + os.pathsep + str(jar), 'org.hkrpg.server.gameplay.BoxingClubMissionGoalsFixtureInspector', str(directory), str(control), phase], cwd=ROOT, capture_output=True, text=True, encoding='utf-8', timeout=100, creationflags=flags)
        (directory / f'inspector-{index}.log').write_text(checked.stdout + checked.stderr, 'utf-8')
        assert checked.returncode == 0, directory / f'inspector-{index}.log'
        proof = json.loads(next(line for line in reversed(checked.stdout.splitlines()) if line.startswith('{"')))
        assert proof['passed'] and proof['phase'] == phase and not proof['boxCompletionPrewritten']
        inspections.append(proof)
        print(json.dumps(dict(phase=index, uid=snapshot['uid'], acceptedRoundWins=snapshot['acceptedRoundWins'], normalStops=stops)), flush=True)
        if index == 3:
            (data / 'Gameplay46.json').write_text(json.dumps({'activities': []}), 'utf-8')
        if index == 4:
            now = int(time.time())
            reopened = [dict(w,beginTime=now-1,endTime=now+3600) if w['moduleId'] in [5000001,4000308] else w for w in activity_windows]
            (data / 'Gameplay46.json').write_text(json.dumps({'activities':reopened}), 'utf-8')
    assert stops == 6 and config.read_bytes() == config_bytes
    assert all(sha(source / path) == value for path, value in hashes.items()) and sha(jar) == artifact
    totals = {}
    for values in quest_recipes.values():
        for id,amount in values.items(): totals[id] = totals.get(id,0)+amount
    report = dict(passed=True, artifactSha256=artifact, runtimeDirectory=str(directory), snapshots=snapshots, inspections=inspections,
        normalStops=stops, sameUIDNormalH2ProcessRestarts=5, nativeRequests=sum(s['nativeRequests'] for s in snapshots), acceptedRoundWins=44,
        originalStoryGoalIDs=7, originalStoryGoalStatusVerified=True, savedStoryAcceptanceBindingsVerified=True, boxingFloorActionVerified=True, originalScopeEntryIsFixture=True,
        claimedActivityRewards=50, activityQuestIDs=50, originalActivityRewardTotals=totals, activityClosedAndExplicitlyReopened=True,
        nonperiodicClaimsNotReset=True, unlinkedHistoricalQuest6000399NotPublished=True, activityProgressOrRewardPrewritten=False,
        sourceResourceHashes=hashes, sourceResourcesModified=False, battleOrCompletionPrewritten=False,
        levelAndMainMissionArePrerequisiteFixtures=True, prerequisiteMainMissionIDs=[1011001,2010720,1011503,8015202,1010302],
        prerequisiteMissionRewardClosedIsFixture=True, formalAvatar1001IsConsoleFixture=True,
        clientGameplayVerified=False, combatInputReplayVerified=False, completeGameplayVerified=False)
    (directory / 'result.json').write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n', 'utf-8')
    output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n', 'utf-8')
    print(json.dumps(dict(passed=True, artifactSha256=artifact, runtimeDirectory=str(directory), nativeRequests=report['nativeRequests'], normalStops=stops, sameUIDNormalH2ProcessRestarts=5, sourceResourcesGuarded=len(hashes))))


if __name__ == '__main__':
    main()
