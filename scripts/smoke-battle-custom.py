"""真实20241054事件和两条自定义值分支；场景/接取隔离，未执行官方演出或战斗输入。"""
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


def main(formal_raid=False, trial_raid=False, mixed_raid=False):
    assert not trial_raid or formal_raid
    assert not mixed_raid or trial_raid
    java = next((ROOT / '.tools').glob('jdk-21*/bin/java.exe'), None)
    java = str(java) if java else 'java'
    artifact = ROOT / 'build/libs/hkrpg-server.jar'
    assert artifact.is_file(), '先运行 scripts/build.ps1'
    source = ROOT / 'resources/4.6'
    runs = ROOT / 'build/smoke'
    runs.mkdir(parents=True, exist_ok=True)
    directory = Path(tempfile.mkdtemp(prefix='raid-formal-' if formal_raid else 'battle-custom-', dir=runs))
    fixture = directory / 'resources-fixture'
    guarded = ['resource-manifest.json', 'ExcelOutput/MainMission.json', 'ExcelOutput/PlaneEvent.json',
               'ExcelOutput/StageConfig.json', 'ExcelOutput/MonsterConfig.json', 'ExcelOutput/MapEntrance.json',
               'Config/Level/Mission/2022005/MissionInfo_2022005.json',
               'Config/Level/Mission/2022005/MissionInfo_2022005.partial.json']
    guarded.extend(f'Config/Level/Mission/2022005/Mission_20220050{i}.json' for i in range(1, 8))
    scripts = list(guarded[8:])
    if formal_raid:
        guarded.extend('ExcelOutput/' + name + '.json' for name in ['RaidConfig', 'TeamLimitCondition', 'MazePlane',
            'MusicRhythmGroup', 'ActivityPanel', 'RewardData', 'ItemConfig'])
        guarded.extend(['Config/Level/Mission/2022004/MissionInfo_2022004.json',
                        'Config/Level/Mission/2022004/MissionInfo_2022004.partial.json'])
    if trial_raid:
        guarded.extend('ExcelOutput/' + name + '.json' for name in ['SpecialAvatar', 'AvatarConfig',
            'AvatarSkillTreeConfig', 'ItemConfigEquipment', 'EquipmentConfig', 'AvatarPromotionConfig',
            'EquipmentPromotionConfig'])
        guarded.append('Config/BattleLineupPage/SkillTreePointPreset/SkillTreePointPreset_1.json')
    before = {name: hashlib.sha256((source / name).read_bytes()).hexdigest() for name in guarded}
    rows = json.loads((source / guarded[1]).read_text('utf-8'))
    main = next(row for row in rows if row['MainMissionID'] == 2022005)
    original_gates = {key: main.get(key) for key in ['TakeOperation', 'TakeParam', 'BeginOperation', 'BeginParam', 'IsInRaid']}
    if formal_raid:
        assert main['IsInRaid'] and main.get('RewardID', 0) == 0 and not main['SubRewardList']
        parent = next(row for row in rows if row['MainMissionID'] == 2022004)
        original_parent = dict(parent)
        parent.update(TakeOperation='And', BeginOperation='And', TakeParam=[{'Type': 'Manual'}],
                      BeginParam=[{'Type': 'Auto'}], RewardID=0, SubRewardList=[])
    else:
        main.update(TakeOperation='And', BeginOperation='And', TakeParam=[{'Type': 'Manual'}],
                    BeginParam=[{'Type': 'Auto'}], RewardID=0, SubRewardList=[], IsInRaid=False)
    replacements = {'ExcelOutput/MainMission.json': rows}
    rows = json.loads((source / guarded[5]).read_text('utf-8'))
    entrance = next(row for row in rows if row['ID'] == 1010101)
    assert entrance['PlaneID'] == 10101 and entrance['FloorID'] == 10101001
    entrance.update(BeginMainMissionList=[], FinishMainMissionList=[], FinishSubMissionList=[])
    replacements['ExcelOutput/MapEntrance.json'] = rows
    if formal_raid and not trial_raid:
        funding_original = {}
        for name in ['MusicRhythmGroup', 'ActivityPanel', 'RewardData']:
            rows = json.loads((source / f'ExcelOutput/{name}.json').read_text('utf-8'))
            if name == 'MusicRhythmGroup':
                row = next(row for row in rows if row['ID'] == 1)
                funding_original['musicGroupGate'] = row.get('UnlockSubMissionID', 0); row['UnlockSubMissionID'] = 0
            elif name == 'ActivityPanel':
                row = next(row for row in rows if row['PanelID'] == 50022)
                funding_original['panelGate'] = row.get('UnlockConditions', ''); row['UnlockConditions'] = ''
            else:
                row = next(row for row in rows if row['RewardID'] == 261001)
                funding_original['reward'] = dict(row); row.clear(); row.update(RewardID=261001, ItemID_1=1209, Count_1=1)
            replacements[f'ExcelOutput/{name}.json'] = rows
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
    mission = fixture / 'Config/Level/Mission/2022005'
    mission.mkdir(parents=True)
    info = json.loads((source / guarded[6]).read_text('utf-8'))
    subs = json.loads((source / guarded[7]).read_text('utf-8'))['SubMissionList']
    original_locations = {str(row['ID']): [row['LevelPlaneID'], row['LevelFloorID']] for row in subs}
    if not formal_raid:
        for row in subs: row.update(LevelPlaneID=10101, LevelFloorID=10101001)
    info['SubMissionList'] = subs
    assert info['FinishSubMissionList'] == [202200505]
    (mission / 'MissionInfo_2022005.json').write_text(json.dumps(info, ensure_ascii=False), encoding='utf-8')
    for name in scripts: connect(mission / Path(name).name, source / name)
    if formal_raid:
        parent_dir = fixture / 'Config/Level/Mission/2022004'; parent_dir.mkdir(parents=True)
        (parent_dir / 'MissionInfo_2022004.json').write_text(json.dumps({'MainMissionID': 2022004,
            'StartSubMissionList': [202200401], 'FinishSubMissionList': [202200401], 'MissionCustomValueList': [],
            'SubMissionList': [{'ID': 202200401, 'MainMissionID': 2022004, 'TakeType': 'Auto',
                'FinishType': 'Talk', 'ParamType': 'Equal', 'ParamStr1': 'IsolatedParentKeepDoing', 'Progress': 1,
                'ParamIntList': [], 'ParamItemList': [], 'FinishActionList': []}]}), encoding='utf-8')
        if not trial_raid:
            data_dir = directory / 'data'; data_dir.mkdir()
            (data_dir / 'Gameplay46.json').write_text(json.dumps({'activities': [{'panelId': 50022, 'moduleId': 0,
                'beginTime': 0, 'endTime': int(time.time()) + 86400}]}), encoding='utf-8')
    (fixture / 'resource-manifest.json').write_text(json.dumps({'clientVersion': '4.6.0', 'testFixture': True,
        'onlyMissionFixture': 2022005, 'realEvent': 20241054}), encoding='utf-8')
    http_port, mongo_port, game_port = free_port(), free_port(), free_port(socket.SOCK_DGRAM)
    (directory / 'config.json').write_text(json.dumps({'protocol': {'wireVersion': '4.6.0'}, 'resourceDir': str(fixture),
        'httpServer': {'bindAddress': '127.0.0.1', 'bindPort': http_port},
        'gameServer': {'bindAddress': '127.0.0.1', 'bindPort': game_port},
        'internalMongoServer': {'address': '127.0.0.1', 'port': mongo_port, 'filePath': './data/database.mv'}}), encoding='utf-8')
    config_bytes = (directory / 'config.json').read_bytes()
    config_sha = hashlib.sha256(config_bytes).hexdigest()

    def preserved_config():
        assert (directory / 'config.json').read_bytes() == config_bytes, f'已有配置被改写，见 {directory}'

    command = [java, '-Xmx1536m', f'-Djdk.net.unixdomain.tmpdir={directory / "tcp-fallback"}', '-jar', str(artifact), '--no-console', '--no-handbook']
    base = f'http://127.0.0.1:{http_port}'

    def probe(name, credentials):
        execution = subprocess.run([java, command[2], '-cp', str(artifact),
            'org.hkrpg.server.protocol.LoopbackBattleCustomProbe', str(game_port)], input=json.dumps(credentials),
            cwd=directory, capture_output=True, text=True, encoding='utf-8', errors='replace', timeout=35,
            creationflags=subprocess.CREATE_NO_WINDOW if os.name == 'nt' else 0)
        (directory / name).write_text(execution.stdout + execution.stderr, encoding='utf-8')
        preserved_config()
        if execution.returncode: raise RuntimeError(f'自定义值探针失败，见 {directory / name}')
        result = json.loads(next(line for line in execution.stdout.splitlines() if line.startswith('{"uid":')))
        assert result['passed'] and not result['clientGameplayVerified']
        chosen = credentials['chosenValue']
        assert result['202200502'] == 1 and result['202200506'] == chosen and result['202200507'] == 1 - chosen
        complete = credentials.get('finishRaid') or credentials.get('completeSaved')
        assert result['parentStillDoing'] == (not complete) and result['stageId'] == 202410540 and result['noActiveBattle']
        if formal_raid:
            assert result['trialRequiredActor' if trial_raid else 'formalRequiredRole'] == 1209
            if trial_raid: assert result['selectedTrial'] == 1011209 and result['formalStorageUnchanged']
            if mixed_raid: assert result['mixedFormalMember'] == 8001 and result['selectedRoster'] == [8001, 1011209]
            if complete:
                assert result['sourceMainAndTailCompleted'] and result['actualFinishedReturnRestored'] and result['actualSourceRaidCompletionRecorded']
                if trial_raid: assert result['ordinaryRosterRestored']
            else: assert result['sourceRaidScene'] and result['raidAndTrialTeamRestored' if trial_raid else 'raidAndFormalTeamRestored']
        return result

    snapshots, credentials, first_uids = [], {}, {}
    for iteration in range(3 if formal_raid else 2):
        preserved_config()
        with (directory / f'server-{iteration}.log').open('w', encoding='utf-8') as log:
            process = subprocess.Popen(command, cwd=directory, stdin=subprocess.DEVNULL, stdout=log, stderr=subprocess.STDOUT,
                                       creationflags=subprocess.CREATE_NO_WINDOW if os.name == 'nt' else 0)
            try:
                deadline = time.monotonic() + 90
                while True:
                    if process.poll() is not None: raise RuntimeError(f'服务启动失败，见 {directory}')
                    try:
                        if request(base, '/health/ready')['ready']: break
                    except (OSError, urllib.error.URLError): pass
                    if time.monotonic() > deadline: raise RuntimeError(f'服务启动超时，见 {directory}')
                    time.sleep(.2)
                preserved_config()
                for chosen in [0, 1]:
                    if iteration == 2:
                        completed = probe(f'custom-{chosen}-completed-restart.log', credentials[chosen])
                        assert completed['uid'] == first_uids[chosen]
                        completed['mode'] = 'completed-restart'; snapshots.append(completed); continue
                    if iteration == 0:
                        login = request(base, '/hkrpg_cn/mdk/shield/api/login', {'account': f'cn-46-custom-{chosen}', 'password': 'local-test'})
                        assert login['retcode'] == 0
                        account = login['data']['account']
                        combo = request(base, '/hkrpg_cn/combo/granter/login/v2/login', {'data': json.dumps({'uid': account['uid'], 'token': account['token']})})
                        assert combo['retcode'] == 0
                        cred = {'accountUid': account['uid'], 'comboToken': combo['data']['combo_token'], 'chosenValue': chosen,
                                'formalRaid': formal_raid, 'trialRaid': trial_raid, 'mixedRaid': mixed_raid}
                        first = probe(f'custom-{chosen}-first.log', cred)
                        for key in ['sourceStageAndRoster', 'wrongIdentityRejected', 'duplicateKeyRejected', 'nonFiniteRejected',
                                    'invalidReportsPreservedBattle', 'repeatedResultRejected']: assert first[key], key
                        if formal_raid: assert first['normalAcceptanceRejected'] and first['missingRoleRejected']
                        if trial_raid: assert first['sourceTrialBattleActorEquipmentAndSkills']
                        if mixed_raid: assert first['sourceMixedBattleRoster']
                        snapshots.append(first)
                        first_uids[chosen] = first['uid']
                        cred.update(resume=True, battleId=first['battleId'], stageId=first['stageId'])
                        credentials[chosen] = cred
                    restored = probe(f'custom-{chosen}-resume-{iteration}.log', credentials[chosen])
                    assert restored['uid'] == first_uids[chosen] and restored['savedBranchesRestored'] and restored['savedBattleRetryRejected']
                    if iteration == 1: restored['mode'] = 'restart'
                    snapshots.append(restored)
                    if formal_raid and iteration == 1:
                        completing = probe(f'custom-{chosen}-finish-story.log', dict(credentials[chosen], finishRaid=True))
                        assert completing['uid'] == first_uids[chosen]
                        completing['mode'] = 'completed'; snapshots.append(completing)
                        credentials[chosen]['completeSaved'] = True
                        completed = probe(f'custom-{chosen}-completed-resume.log', credentials[chosen])
                        assert completed['uid'] == first_uids[chosen]
                        completed['mode'] = 'completed-resume'; snapshots.append(completed)
                time.sleep(2)
            finally:
                if process.poll() is None:
                    process.terminate()
                    try: process.wait(timeout=15)
                    except subprocess.TimeoutExpired:
                        process.kill()
                        process.wait(timeout=5)
        preserved_config()
    assert len(set(first_uids.values())) == 2
    for name, digest in before.items(): assert hashlib.sha256((source / name).read_bytes()).hexdigest() == digest
    checks = ['source-event-stage-monster', 'actual-preceding-talk-unlocks-source-objectives', 'wrong-battle-id-rejected',
                   'duplicate-custom-key-rejected', 'nonfinite-custom-value-rejected', 'invalid-report-keeps-active-battle',
                   'zero-report-selects-only-202200507', 'one-report-selects-only-202200506', 'mapped-event-stage-goal-completes-once',
                   'next-source-talk-accepted-with-parent-still-doing', 'repeated-settlement-rejected',
                   'both-uids-reconnect-restores-branches', 'both-uids-normal-H2-restart-restores-branches']
    if formal_raid: checks.extend(['normal-IsInRaid-acceptance-rejected', 'missing-required-role-rejected',
        'owned-formal-role-accepted-without-trial', 'actual-source-raid-entry', 'both-uids-reconnect-retains-raid-and-team',
        'both-uids-normal-H2-restart-retains-raid-and-team', 'both-source-tails-complete-after-exact-talk-reports',
        'actual-source-finish-return-and-completion-history', 'both-completed-uids-reconnect-preserves-history',
        'both-completed-uids-normal-H2-restart-preserves-history'])
    if trial_raid:
        checks = [s.replace('owned-formal-role-accepted-without-trial', 'declared-trial-role-accepted-without-formal-grant')
                    .replace('raid-and-team', 'raid-and-trial-team') for s in checks]
        checks.extend(['source-trial-battle-level-equipment-and-skills', 'formal-storage-unchanged-across-all-snapshots',
                       'ordinary-roster-restored-after-completion'])
    if mixed_raid: checks.append('owned-formal-and-declared-trial-mixed-roster-and-battle')
    report = {'passed': True, 'runtimeDirectory': str(directory), 'snapshots': snapshots, 'checks': checks,
        'operatorConfig': {'sha256': config_sha, 'bytesUnchangedAcrossProbesAndRestarts': True},
        'fixture': {'onlyMission': 2022005, 'event': 20241054, 'stage': 202410540, 'monster': 2002010,
                    'entrance': 40235001 if formal_raid else 1010101,
                    'acceptanceAndEntranceGatesRelaxed': not formal_raid,
                    'raidGateRelaxedForIsolatedNormalScene': not formal_raid, 'allObjectiveLocationsRelocated': not formal_raid,
                    'originalMainGates': original_gates, 'originalObjectiveLocations': original_locations,
                    'sourceFinishConditionsAndScriptsUnchanged': True, 'officialMainFinishObjectivePreserved': 202200505,
                    'sourceSha256': before, 'sourceResourcesModified': False, 'officialStoryMissionVerified': False,
                    'battleInputReplayVerified': False, 'crashRecoveryVerified': False}, 'clientGameplayVerified': False}
    if formal_raid:
        report['fixture'].update(raid=40235, teamLimit=3, trialDeclaration=[1011209], isolatedParent=2022004,
            isolatedParentOriginal=original_parent, ordinaryEntryGatesRelaxed=True,
            sourceRaidConfigTeamRulesMainAndAllLocationsUnchanged=True, fullOfficialParentStoryVerified=False)
        if trial_raid:
            report['fixture'].update(trialSelected=1011209, formalRoleFundingUsed=False, trialRelicCalculationVerified=False)
            if mixed_raid: report['fixture'].update(mixedFormalSelected=[8001], selectedRoster=[8001, 1011209])
        else:
            report['fixture'].update(isolatedFundingOriginal=funding_original,
                isolatedFundingReward={'rewardId': 261001, 'itemId': 1209, 'count': 1})
    print(json.dumps(report, ensure_ascii=False))


if __name__ == '__main__':
    import argparse
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--formal-raid', action='store_true', help='保留真实40235副本、正式角色和剧情条件；隔离父任务和角色获取')
    parser.add_argument('--trial-raid', action='store_true', help='保留真实40235副本和1011209试用配置；隔离父任务，核对正式角色未被授予')
    parser.add_argument('--mixed-raid', action='store_true', help='真实40235副本使用已有8001正式角色与声明1011209试用角色混编')
    args = parser.parse_args()
    main(args.formal_raid or args.trial_raid or args.mixed_raid, args.trial_raid or args.mixed_raid, args.mixed_raid)
