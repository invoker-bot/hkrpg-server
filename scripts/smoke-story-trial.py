"""实际2022304剧情场景/试用角色/替换任务的KCP与重连/H2；前置门槛为隔离夹具。"""
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

ROOT = Path(__file__).resolve().parents[1]


def free_port(kind=socket.SOCK_STREAM):
    with socket.socket(type=kind) as stream:
        stream.bind(('127.0.0.1', 0))
        return stream.getsockname()[1]


def request(base, route, body=None):
    req = urllib.request.Request(base + route, data=None if body is None else json.dumps(body).encode(),
                                 headers={'Content-Type': 'application/json'})
    with urllib.request.urlopen(req, timeout=5) as response:
        return json.load(response)


def main(leader_checks=False, caster_checks=False, ability_name_checks=False):
    assert not caster_checks or leader_checks, '施法者检查需要双角色剧情队伍'
    assert not ability_name_checks or caster_checks, '动作名称检查需要施法者夹具'
    java = next((ROOT / '.tools').glob('jdk-21*/bin/java.exe'), None)
    java = str(java) if java else 'java'
    artifact = ROOT / 'build/libs/hkrpg-server.jar'
    assert artifact.is_file(), '先运行 scripts/build.ps1'
    source = ROOT / 'resources/4.6'
    runs = ROOT / 'build/smoke'
    runs.mkdir(parents=True, exist_ok=True)
    directory = Path(tempfile.mkdtemp(prefix='lineup-leader-' if leader_checks else 'story-trial-', dir=runs))
    fixture = directory / 'resources-fixture'
    guarded = ['resource-manifest.json', 'ExcelOutput/MainMission.json', 'ExcelOutput/MapEntrance.json',
               'ExcelOutput/StoryLine.json', 'ExcelOutput/StoryLineFloorData.json',
               'ExcelOutput/StroyLineTrialAvatarData.json', 'ExcelOutput/SpecialAvatar.json',
               'ExcelOutput/AvatarConfig.json', 'ExcelOutput/AvatarSkillTreeConfig.json',
               'Config/Level/Mission/2022303/MissionInfo_2022303.partial.json',
               'Config/Level/Mission/2022304/MissionInfo_2022304.partial.json',
               'Config/Level/Mission/2022305/MissionInfo_2022305.partial.json',
               'Config/Level/Mission/2022305/Mission_202230507.json',
               'Config/LevelOutput/RuntimeFloor/P20242_F20242001.json',
               'Config/LevelOutput/SharedRuntimeGroup/Groups_P20242_F20242001/LevelGroup_P20242_F20242001_G25.json']
    guarded += [str(p.relative_to(source)).replace('\\', '/')
                for p in (source / 'Config/BattleLineupPage/SkillTreePointPreset').glob('*.json')]
    attack_source = 'Config/ConfigAdventureAbility/LocalPlayer/LocalPlayer_Mar_7th_10_Ability.json'
    source_attack_name = ''
    if ability_name_checks:
        guarded.append(attack_source)
        abilities = json.loads((source / attack_source).read_text(encoding='utf-8'))['AbilityList']
        names = [a['Name'] for a in abilities if a['Name'].endswith('NormalAtk01')]
        assert len(names) == 1
        source_attack_name = names[0]
    before = {name: hashlib.sha256((source / name).read_bytes()).hexdigest() for name in guarded}
    replacements = {}
    trial_lock_fixture = []
    if caster_checks:
        rows = json.loads((source / 'ExcelOutput/SpecialAvatar.json').read_text(encoding='utf-8'))
        for row in rows:
            if row['SpecialAvatarID'] in [1031224, 1041209]:
                trial_lock_fixture.append({'specialAvatarId': row['SpecialAvatarID'],
                    'worldLevel': row.get('WorldLevel', 0), 'sourceLockMazeSkill': row.get('LockMazeSkill', False)})
                row['LockMazeSkill'] = row['SpecialAvatarID'] == 1031224
        assert trial_lock_fixture and {row['specialAvatarId'] for row in trial_lock_fixture} == {1031224, 1041209}
        replacements['ExcelOutput/SpecialAvatar.json'] = rows
    rows = json.loads((source / 'ExcelOutput/MainMission.json').read_text(encoding='utf-8'))
    for main in rows:
        if main['MainMissionID'] in [2022303, 2022304, 2022305]:
            main.update(TakeOperation='And', BeginOperation='And', TakeParam=[{'Type': 'Manual'}],
                        BeginParam=[{'Type': 'Auto'}], RewardID=0, SubRewardList=[])
    replacements['ExcelOutput/MainMission.json'] = rows
    rows = json.loads((source / 'ExcelOutput/MapEntrance.json').read_text(encoding='utf-8'))
    for entrance in rows:
        if entrance['ID'] in [2024201, 100000103]:
            entrance.update(BeginMainMissionList=[], FinishMainMissionList=[], FinishSubMissionList=[])
    replacements['ExcelOutput/MapEntrance.json'] = rows
    junctions = []

    def connect(target, origin):
        target.parent.mkdir(parents=True, exist_ok=True)
        if origin.is_file():
            os.link(origin, target)
        elif os.name == 'nt':
            assert target.resolve().is_relative_to(fixture.resolve())
            junctions.append({'target': str(target), 'source': str(origin)})
        else:
            target.symlink_to(origin, target_is_directory=True)

    for path in (source / 'ExcelOutput').iterdir():
        name = 'ExcelOutput/' + path.name
        if name in replacements:
            target = fixture / name
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_text(json.dumps(replacements[name], ensure_ascii=False), encoding='utf-8')
        else:
            connect(fixture / name, path)
    for path in (source / 'Config').iterdir():
        if path.name != 'Level': connect(fixture / 'Config' / path.name, path)
    for path in (source / 'Config/Level').iterdir():
        if path.name != 'Mission': connect(fixture / 'Config/Level' / path.name, path)
    if junctions:
        plan = directory / 'junction-plan.json'
        plan.write_text(json.dumps(junctions), encoding='utf-8')
        helper = directory / 'prepare-fixture.ps1'
        helper.write_text("param([string]$Plan)\n$ErrorActionPreference = 'Stop'\n"
                          "foreach ($binding in (Get-Content -LiteralPath $Plan -Raw | ConvertFrom-Json)) {\n"
                          "  New-Item -ItemType Junction -Path $binding.target -Value $binding.source | Out-Null\n}\n", encoding='utf-8')
        subprocess.run(['powershell', '-NoProfile', '-NonInteractive', '-File', str(helper), '-Plan', str(plan)],
                       check=True, capture_output=True, text=True, creationflags=subprocess.CREATE_NO_WINDOW)
    selections = {2022303: [202230316], 2022304: [202230401], 2022305: [202230501, 202230507]}
    for main_id, sub_ids in selections.items():
        name = f'Config/Level/Mission/{main_id}/MissionInfo_{main_id}.partial.json'
        rows = json.loads((source / name).read_text(encoding='utf-8'))['SubMissionList']
        subs = [row for row in rows if row['ID'] in sub_ids]
        assert len(subs) == len(sub_ids)
        for sub in subs:
            if sub['ID'] == 202230316:
                # The real predecessor is in a different branch/map. Only its server finish action is retained.
                sub.update(TakeType='Auto', TakeParamIntList=[], LevelPlaneID=20242, LevelFloorID=20242001,
                           GroupIDList=[], StoryLineIDList=[], StoryLineID=0)
        mission = fixture / f'Config/Level/Mission/{main_id}'
        mission.mkdir(parents=True)
        (mission / f'MissionInfo_{main_id}.json').write_text(json.dumps({'MainMissionID': main_id,
            'StartSubMissionList': sub_ids, 'FinishSubMissionList': [202230501] if main_id == 2022305 else [],
            'SubMissionList': subs}), encoding='utf-8')
        for sub in subs:
            if sub.get('MissionJsonPath'):
                connect(fixture / sub['MissionJsonPath'], source / sub['MissionJsonPath'])
    (fixture / 'resource-manifest.json').write_text(json.dumps({'clientVersion': '4.6.0', 'testFixture': True,
        'onlyMissionFixtures': list(selections), 'realStory': 2022304}), encoding='utf-8')
    http_port, mongo_port, game_port = free_port(), free_port(), free_port(socket.SOCK_DGRAM)
    (directory / 'config.json').write_text(json.dumps({'protocol': {'wireVersion': '4.6.0'}, 'resourceDir': str(fixture),
        'httpServer': {'bindAddress': '127.0.0.1', 'bindPort': http_port},
        'gameServer': {'bindAddress': '127.0.0.1', 'bindPort': game_port},
        'internalMongoServer': {'address': '127.0.0.1', 'port': mongo_port, 'filePath': './data/database.mv'}}), encoding='utf-8')
    command = [java, '-Xmx1536m', f'-Djdk.net.unixdomain.tmpdir={directory / "tcp-fallback"}', '-jar', str(artifact), '--no-console', '--no-handbook']
    base = f'http://127.0.0.1:{http_port}'

    def probe(name, credentials):
        execution = subprocess.run([java, command[2], '-cp', str(artifact),
            'org.hkrpg.server.protocol.LoopbackStoryTrialProbe', str(game_port)], input=json.dumps(credentials),
            cwd=directory, capture_output=True, text=True, encoding='utf-8', errors='replace', timeout=35,
            creationflags=subprocess.CREATE_NO_WINDOW if os.name == 'nt' else 0)
        (directory / name).write_text(execution.stdout + execution.stderr, encoding='utf-8')
        if execution.returncode: raise RuntimeError(f'Story trial probe failed; inspect {directory / name}')
        result = json.loads(next(line for line in execution.stdout.splitlines() if line.startswith('{"uid":')))
        assert result['passed'] and not result['clientGameplayVerified']
        return result

    snapshots, credentials = [], None
    for iteration in range(2):
        with (directory / f'server-{iteration}.log').open('w', encoding='utf-8') as log:
            process = subprocess.Popen(command, cwd=directory, stdin=subprocess.DEVNULL, stdout=log, stderr=subprocess.STDOUT,
                                       creationflags=subprocess.CREATE_NO_WINDOW if os.name == 'nt' else 0)
            try:
                deadline = time.monotonic() + 90
                while True:
                    if process.poll() is not None: raise RuntimeError(f'Story trial server failed; inspect {directory}')
                    try:
                        if request(base, '/health/ready')['ready']: break
                    except (OSError, urllib.error.URLError): pass
                    if time.monotonic() > deadline: raise RuntimeError(f'Story trial server timeout; inspect {directory}')
                    time.sleep(.2)
                if iteration == 0:
                    login = request(base, '/hkrpg_cn/mdk/shield/api/login', {'account': 'cn-46-story-trial', 'password': 'local-test'})
                    assert login['retcode'] == 0
                    account = login['data']['account']
                    combo = request(base, '/hkrpg_cn/combo/granter/login/v2/login', {'data': json.dumps({'uid': account['uid'], 'token': account['token']})})
                    assert combo['retcode'] == 0
                    credentials = {'accountUid': account['uid'], 'comboToken': combo['data']['combo_token'],
                                   'leaderChecks': leader_checks, 'casterChecks': caster_checks,
                                   'abilityNameChecks': ability_name_checks, 'sourceAttackName': source_attack_name}
                    first = probe('story-trial-first.log', credentials)
                    for key in ['outsideStoryStayedPending', 'earlyTrialReplacementRejected', 'nativeAvailableActors',
                                'nativeInitialAndReplacementNotifications', 'actualSourceTaskCompletedOnce',
                                'nativeReplacedParty', 'nativeSceneActor', 'nativeDimension', 'formalStorageUnchanged',
                                'repeatedTalkDidNotReset', 'oldActorRemainsAvailable']:
                        assert first[key], key
                    assert first['replacementProgress'] == 1
                    if leader_checks:
                        for key in ['nativeLeaderChangeSucceeded', 'nativeLeaderRetrySucceeded',
                                    'nativeInvalidLeaderSlotRejected', 'nativeWideLeaderSlotRejected',
                                    'nativeDuplicateLeaderSlotRejected']:
                            assert first[key], key
                    snapshots.append(first)
                    credentials.update(resume=True)
                restored = probe(f'story-trial-resume-{iteration}.log', credentials)
                for key in ['savedGoalRestored', 'savedStoryRestored', 'savedPartyRestored',
                            'nativeSceneActor', 'nativeDimension', 'nativeAvailableActors', 'formalStorageUnchanged']:
                    assert restored[key], key
                assert restored['uid'] == first['uid'] and restored['replacementProgress'] == 1
                if caster_checks:
                    for snapshot in [first, restored]:
                        for key in ['nativeLockedNonLeaderTechniqueRejected', 'nativeLockedNonLeaderOrdinaryAttackAccepted',
                                    'nativeNonLeaderCastsKeptPointsAndLeader']:
                            assert snapshot[key], key
                        assert snapshot['nativeCasterSpecial'] == 1031224 and snapshot['nativeCasterActor'] == 1224
                        assert snapshot['nativeCasterPoints'] == first['nativeCasterPoints']
                        if ability_name_checks:
                            assert snapshot['nativeExactSourceAttackNameAccepted']
                            assert snapshot['nativeUnknownAttackNameRejected']
                            assert snapshot['nativeSourceAttackName'] == source_attack_name
                if leader_checks:
                    assert restored['nativeLeaderSlot'] == first['nativeLeaderSlot'] == 1
                    assert restored['nativeLeaderSceneActor'] == first['nativeLeaderSceneActor'] == 1209
                    assert restored['nativeLeaderRoster'] == first['nativeLeaderRoster'] == [1031224, 1041209]
                snapshots.append(restored)
                time.sleep(2)
            finally:
                if process.poll() is None:
                    process.terminate()
                    try: process.wait(timeout=15)
                    except subprocess.TimeoutExpired:
                        process.kill()
                        process.wait(timeout=5)
    for name, digest in before.items():
        assert hashlib.sha256((source / name).read_bytes()).hexdigest() == digest
    print(json.dumps({'passed': True, 'runtimeDirectory': str(directory), 'snapshots': snapshots,
        'checks': ['outside-story-task-pending', 'future-actor-client-replacement-rejected', 'actual-story-action-entry',
                   'actual-source-trial-replacement', 'native-initial-and-replacement-notifications',
                   'native-story-id-and-trial-availability', 'native-story-party-identity', 'native-scene-actor-cache', 'native-story-dimension',
                   'formal-storage-unchanged', 'repeated-talk-rejected', 'old-actor-remains-selectable',
                   'reconnect-restores-story-and-goal', 'h2-process-restart-restores-story-and-goal']
                  + (['native-two-trial-leader-change', 'invalid-slot-rejected', 'same-leader-idempotent',
                      'wide-slot-rejected', 'duplicate-slot-rejected', 'native-scene-leader-matches',
                      'same-uid-leader-restored-after-reconnect-and-h2-restart'] if leader_checks else [])
                  + (['native-locked-non-leader-technique-rejected', 'native-locked-non-leader-ordinary-attack-accepted',
                      'native-non-leader-casts-keep-points-and-leader',
                      'native-non-leader-cast-policy-restored-after-reconnect-and-h2-restart'] if caster_checks else []),
        'abilityNameChecks': (['native-exact-source-primary-attack-name-accepted',
                              'native-unknown-attack-name-rejected-before-cost',
                              'native-attack-name-policy-restored-after-reconnect-and-h2-restart']
                             if ability_name_checks else []),
        'fixture': {'onlyMissions': list(selections), 'story': 2022304, 'entrance': 2024201, 'dimension': 2,
                    'replacementSub': 202230507, 'initialActor': 1031224, 'replacementActor': 1041209,
                    'acceptanceAndEntranceGatesRelaxed': True, 'predecessorSceneAndBranchRelocated': True,
                    'sourceReplacementTaskUnchanged': True, 'sourceSha256': before,
                    'sourceResourcesModified': False, 'officialStoryMissionVerified': False},
        'casterFixture': {'enabled': caster_checks, 'isolatedLockDeclarations': trial_lock_fixture,
                          'sourceLockRulesExercisedUnmodified': False, 'targetlessOrdinaryAttackOnly': True,
                          'completeBattleVerified': False},
        'abilityNameFixture': {'enabled': ability_name_checks, 'source': attack_source,
                               'name': source_attack_name, 'sourceAbilityChainModified': False,
                               'targetlessOrdinaryAttackOnly': True, 'completeAbilityChainVerified': False},
        'clientGameplayVerified': False}, ensure_ascii=False))


if __name__ == '__main__':
    main()
