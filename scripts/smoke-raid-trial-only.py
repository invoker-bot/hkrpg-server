"""真实仅试用副本入口、暂停/重入和正常H2恢复；父任务及普通入口明确隔离。"""
import hashlib
import json
import os
from pathlib import Path
import shutil
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


def main(formal_captain=False, team_thresholds=False, player_frozen=False, multi_path=False, role_identity=False, trial_candidates=False, trial_role_uniqueness=False):
    assert sum([formal_captain, team_thresholds, player_frozen, multi_path, role_identity, trial_candidates, trial_role_uniqueness]) <= 1
    party_formal = formal_captain or player_frozen
    java = next((ROOT / '.tools').glob('jdk-21*/bin/java.exe'), None)
    java = str(java) if java else 'java'
    source = ROOT / 'resources/4.6'
    artifact = ROOT / 'build/libs/hkrpg-server.jar'
    assert artifact.is_file(), '先运行 scripts/build.ps1'
    runs = ROOT / 'build/smoke'; runs.mkdir(parents=True, exist_ok=True)
    directory = Path(tempfile.mkdtemp(prefix='raid-trial-only-', dir=runs))
    jar = directory / 'hkrpg-server.jar'
    shutil.copyfile(artifact, jar)
    jar_sha = hashlib.sha256(jar.read_bytes()).hexdigest()
    fixture = directory / 'resources-fixture'
    guard = ['resource-manifest.json'] + ['ExcelOutput/' + name + '.json' for name in
        ['RaidConfig', 'TeamLimitCondition', 'MainMission', 'MapEntrance', 'MazePlane', 'SpecialAvatar',
         'AvatarConfig', 'AvatarPromotionConfig', 'AvatarSkillTreeConfig', 'MultiplePathAvatarConfig',
         'ItemConfigEquipment', 'EquipmentConfig', 'EquipmentPromotionConfig']]
    guard += ['Config/BattleLineupPage/SkillTreePointPreset/SkillTreePointPreset_' + str(n) + '.json' for n in [1, 7]]
    def table(name): return json.loads((source / 'ExcelOutput' / (name + '.json')).read_text('utf-8'))
    raids, entrances, mains = table('RaidConfig'), table('MapEntrance'), table('MainMission')
    scenarios = []
    originals = {}
    isolated_team_original = None
    candidate_catalog_source = None
    if formal_captain:
        guard += ['ExcelOutput/' + name + '.json' for name in ['MusicRhythmGroup', 'ActivityPanel', 'RewardData', 'ItemConfig']]
    for rid in ([40452] if role_identity or trial_candidates or trial_role_uniqueness else [41212] if multi_path else [40412] if player_frozen else [4012301] if formal_captain else [40222001] if team_thresholds else [40222001, 40531, 40452]):
        raid = next(row for row in raids if row['RaidID'] == rid and not row.get('WorldLevel', 0))
        if team_thresholds:
            isolated_team_original = json.loads(json.dumps(raid))
            raid['TrialAvatarList'] = raid['TrialAvatarList'] + [1011209]
            raid['TeamLimitIDList'] = raid['TeamLimitIDList'] + [2, 5]
        if role_identity:
            isolated_team_original = json.loads(json.dumps(raid))
            raid['TeamLimitIDList'] = raid['TeamLimitIDList'] + [1]
        if trial_candidates or trial_role_uniqueness:
            isolated_team_original = json.loads(json.dumps(raid))
            catalog_raid = 40546 if trial_role_uniqueness else 40475
            candidate_catalog_source = json.loads(json.dumps(next(row for row in raids if row['RaidID'] == catalog_raid and not row.get('WorldLevel', 0))))
            assert candidate_catalog_source['SkipJoinLineup'] and len(candidate_catalog_source['TrialAvatarList']) == (7 if trial_role_uniqueness else 13)
            assert not raid.get('SkipJoinLineup', False)
            for key in ['TeamType', 'TrialAvatarList', 'TeamLimitIDList']:
                raid[key] = json.loads(json.dumps(candidate_catalog_source[key]))
        mid, parent = raid['MainMissionIDList'][0], raid['MainMissionIDBefore']
        assert raid['TeamType'] == ('Player' if player_frozen else 'TrialOrPlayer' if formal_captain or multi_path or trial_candidates or trial_role_uniqueness else 'TrialOnly') and raid['SkipRewardOnFinish'] and raid['RecoverType'] == ['Unknown']
        folder = source / 'Config/Level/Mission' / str(mid)
        info = json.loads((folder / f'MissionInfo_{mid}.json').read_text('utf-8'))
        partial = folder / f'MissionInfo_{mid}.partial.json'
        subs = json.loads(partial.read_text('utf-8'))['SubMissionList'] if partial.exists() else info['SubMissionList']
        first = next(row for row in subs if row['ID'] == info['StartSubMissionList'][0])
        entries = [row for row in entrances if row['PlaneID'] == first['LevelPlaneID'] and row['FloorID'] == first['LevelFloorID']]
        assert len(entries) == 1 and first['FinishType'] == 'Talk'
        selected = [1031510, 1048007] if trial_role_uniqueness else [1011415, 1021402, 1021405, 1031408] if trial_candidates else [1021224] if multi_path else [8001] if player_frozen else [8001, 1111] if formal_captain else [x for x in raid['TrialAvatarList'] if x != 1028008]
        scenarios.append(dict(raid=rid, main=mid, parent=parent, first=first['ID'], selected=selected,
                              locked=raid.get('LockCaptain', False), leader=1 if formal_captain else 0, formalMode=formal_captain, playerMode=player_frozen, pathMode=multi_path, candidateMode=trial_candidates or trial_role_uniqueness, duplicateRoleMode=trial_role_uniqueness,
                              plane=first['LevelPlaneID'], floor=first['LevelFloorID'], entry=entries[0]['ID']))
        main = next(row for row in mains if row['MainMissionID'] == mid)
        assert main['IsInRaid'] and not main.get('RewardID', 0) and not main.get('SubRewardList', [])
        p = next(row for row in mains if row['MainMissionID'] == parent)
        originals[str(parent)] = dict(p)
        p.update(TakeOperation='And', BeginOperation='And', TakeParam=[{'Type': 'Manual'}],
                 BeginParam=[{'Type': 'Auto'}], RewardID=0, SubRewardList=[])
        guard += [path.relative_to(source).as_posix() for path in folder.iterdir() if path.is_file()]
        parent_dir = source / 'Config/Level/Mission' / str(parent)
        guard += [path.relative_to(source).as_posix() for path in parent_dir.iterdir() if path.is_file()]
    gate = next(row for row in entrances if row['ID'] == 1010101)
    original_entry = dict(gate)
    gate.update(BeginMainMissionList=[], FinishMainMissionList=[], FinishSubMissionList=[])
    before = {name: hashlib.sha256((source / name).read_bytes()).hexdigest() for name in sorted(set(guard))}
    replacements = {'ExcelOutput/MainMission.json': mains, 'ExcelOutput/MapEntrance.json': entrances}
    if team_thresholds or role_identity or trial_candidates or trial_role_uniqueness:
        replacements['ExcelOutput/RaidConfig.json'] = raids
    if formal_captain:
        funding_original = {}
        for name in ['MusicRhythmGroup', 'ActivityPanel', 'RewardData']:
            rows = table(name)
            if name == 'MusicRhythmGroup':
                row = next(row for row in rows if row['ID'] == 1)
                funding_original['musicGroupGate'] = row.get('UnlockSubMissionID', 0); row['UnlockSubMissionID'] = 0
            elif name == 'ActivityPanel':
                row = next(row for row in rows if row['PanelID'] == 50022)
                funding_original['panelGate'] = row.get('UnlockConditions', ''); row['UnlockConditions'] = ''
            else:
                row = next(row for row in rows if row['RewardID'] == 261001)
                funding_original['reward'] = dict(row); row.clear(); row.update(RewardID=261001, ItemID_1=1111, Count_1=1)
            replacements['ExcelOutput/' + name + '.json'] = rows
        data = directory / 'data'; data.mkdir()
        (data / 'Gameplay46.json').write_text(json.dumps({'activities': [{'panelId': 50022, 'moduleId': 0,
            'beginTime': 0, 'endTime': int(time.time()) + 86400}]}), 'utf-8')
    junctions = []
    def connect(target, origin):
        target.parent.mkdir(parents=True, exist_ok=True)
        if origin.is_file(): os.link(origin, target)
        elif os.name == 'nt':
            assert target.resolve().is_relative_to(fixture.resolve())
            junctions.append({'target': str(target), 'source': str(origin)})
        else: target.symlink_to(origin, target_is_directory=True)
    for path in (source / 'ExcelOutput').iterdir():
        name = 'ExcelOutput/' + path.name
        if name in replacements:
            target = fixture / name; target.parent.mkdir(parents=True, exist_ok=True)
            target.write_text(json.dumps(replacements[name], ensure_ascii=False), 'utf-8')
        else: connect(fixture / name, path)
    for path in (source / 'Config').iterdir():
        if path.name != 'Level': connect(fixture / 'Config' / path.name, path)
    for path in (source / 'Config/Level').iterdir():
        if path.name != 'Mission': connect(fixture / 'Config/Level' / path.name, path)
    for item in scenarios:
        mid, parent = item['main'], item['parent']
        original = source / 'Config/Level/Mission' / str(mid)
        target = fixture / 'Config/Level/Mission' / str(mid); target.mkdir(parents=True)
        info = json.loads((original / f'MissionInfo_{mid}.json').read_text('utf-8'))
        partial = original / f'MissionInfo_{mid}.partial.json'
        if partial.exists(): info['SubMissionList'] = json.loads(partial.read_text('utf-8'))['SubMissionList']
        (target / f'MissionInfo_{mid}.json').write_text(json.dumps(info, ensure_ascii=False), 'utf-8')
        for path in original.iterdir():
            if path.is_file() and not path.name.startswith('MissionInfo_'): connect(target / path.name, path)
        target = fixture / 'Config/Level/Mission' / str(parent); target.mkdir(parents=True)
        sub = parent * 100 + 1
        (target / f'MissionInfo_{parent}.json').write_text(json.dumps({'MainMissionID': parent,
            'StartSubMissionList': [sub], 'FinishSubMissionList': [sub], 'MissionCustomValueList': [],
            'SubMissionList': [{'ID': sub, 'MainMissionID': parent, 'TakeType': 'Auto', 'FinishType': 'Talk',
                               'ParamType': 'Equal', 'ParamStr1': 'IsolatedParentKeepDoing', 'Progress': 1,
                               'ParamIntList': [], 'ParamItemList': [], 'FinishActionList': []}]}), 'utf-8')
    if junctions:
        plan = directory / 'junction-plan.json'; plan.write_text(json.dumps(junctions), 'utf-8')
        helper = directory / 'prepare-fixture.ps1'
        helper.write_text("param([string]$Plan)\n$ErrorActionPreference = 'Stop'\n"
                          "foreach ($binding in (Get-Content -LiteralPath $Plan -Raw | ConvertFrom-Json)) {\n"
                          "  New-Item -ItemType Junction -Path $binding.target -Value $binding.source | Out-Null\n}\n", 'utf-8')
        subprocess.run(['powershell', '-NoProfile', '-NonInteractive', '-File', str(helper), '-Plan', str(plan)],
                       check=True, capture_output=True, text=True, creationflags=subprocess.CREATE_NO_WINDOW)
    (fixture / 'resource-manifest.json').write_text(json.dumps({'clientVersion': '4.6.0', 'testFixture': True}), 'utf-8')
    http_port, mongo_port, game_port = free_port(), free_port(), free_port(socket.SOCK_DGRAM)
    config = directory / 'config.json'
    config.write_text(json.dumps({'protocol': {'wireVersion': '4.6.0'}, 'resourceDir': str(fixture),
        'httpServer': {'bindAddress': '127.0.0.1', 'bindPort': http_port},
        'gameServer': {'bindAddress': '127.0.0.1', 'bindPort': game_port},
        'internalMongoServer': {'address': '127.0.0.1', 'port': mongo_port, 'filePath': './data/database.mv'}}), 'utf-8')
    config_bytes = config.read_bytes(); config_sha = hashlib.sha256(config_bytes).hexdigest()
    base = f'http://127.0.0.1:{http_port}'
    java_option = f'-Djdk.net.unixdomain.tmpdir={directory / "tcp-fallback"}'
    credentials, snapshots, uids = {}, [], {}
    def preserved():
        assert config.read_bytes() == config_bytes, '已有测试配置被改写'
        assert hashlib.sha256(jar.read_bytes()).hexdigest() == jar_sha, '验证产物被替换'
    def probe(item, mode, **flags):
        preserved()
        cred = dict(credentials[item['raid']], **flags, mode=mode)
        run = subprocess.run([java, java_option, '-cp', str(jar), 'org.hkrpg.server.protocol.LoopbackRaidTrialOnlyProbe', str(game_port)],
            input=json.dumps(cred), cwd=directory, capture_output=True, text=True, encoding='utf-8', errors='replace', timeout=35,
            creationflags=subprocess.CREATE_NO_WINDOW if os.name == 'nt' else 0)
        log = directory / f"{item['raid']}-{mode}.log"; log.write_text(run.stdout + run.stderr, 'utf-8')
        if run.returncode: raise RuntimeError(f'仅试用探针失败，见 {log}')
        report = json.loads(next(line for line in run.stdout.splitlines() if line.startswith('{"uid":')))
        assert report['passed'] and report['ownedStorageMatchesPreparedRoster' if party_formal else 'formalStorageUnchanged'] and not report['clientGameplayVerified']
        assert report['frozenFormalRoster' if party_formal else 'automaticRoster'] == item['selected'] and report['sourceRaidScene'] and report['sourceFirstObjectiveUncompleted']
        if item['locked'] and len(item['selected']) > 1: assert report['captainChangeRejected']
        if flags.get('leave'): assert report['ordinaryRosterRestored'] and report['ordinaryReturnScene']
        if mode == 'fresh': assert report['unownedFormalRejected' if player_frozen else 'missingRequiredRoleRejected' if formal_captain else 'formalSubstitutionRejected']; uids[item['raid']] = report['uid']
        assert report['uid'] == uids[item['raid']]
        snapshots.append(report); preserved()
    for iteration in range(3):
        preserved()
        with (directory / f'server-{iteration}.log').open('w', encoding='utf-8') as log:
            process = subprocess.Popen([java, '-Xmx1536m', java_option, '-jar', str(jar), '--no-console', '--no-handbook'],
                cwd=directory, stdin=subprocess.DEVNULL, stdout=log, stderr=subprocess.STDOUT,
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
                for item in scenarios:
                    if iteration == 0:
                        account = request(base, '/hkrpg_cn/mdk/shield/api/login', {'account': f"trial-only-{item['raid']}", 'password': 'local-test'})['data']['account']
                        combo = request(base, '/hkrpg_cn/combo/granter/login/v2/login', {'data': json.dumps({'uid': account['uid'], 'token': account['token']})})
                        credentials[item['raid']] = dict(item, accountUid=account['uid'], comboToken=combo['data']['combo_token'])
                        probe(item, 'fresh'); probe(item, 'resume', resume=True)
                    elif iteration == 1:
                        probe(item, 'restart', resume=True)
                        probe(item, 'pause', resume=True, leave=True)
                        probe(item, 'reenter', reenter=True)
                    else:
                        probe(item, 'reentered-restart', resume=True)
                        probe(item, 'final-pause', resume=True, leave=True)
                time.sleep(2)
            finally:
                if process.poll() is None:
                    process.terminate()
                    try: process.wait(timeout=15)
                    except subprocess.TimeoutExpired: process.kill(); process.wait(timeout=5)
    preserved()
    for name, sha in before.items(): assert hashlib.sha256((source / name).read_bytes()).hexdigest() == sha
    assert len(set(uids.values())) == len(scenarios) and len(snapshots) == 7 * len(scenarios)
    checks = ['three-authored-trial-only-entries', 'empty-request-selects-source-trials', 'formal-substitution-rejected',
              'male-protagonist-selects-only-declared-male-path', 'source-captain-retained-and-multi-member-change-rejected',
              'formal-owned-storage-unchanged', 'actual-first-objectives-remain-uncompleted', 'three-uids-reconnect-restores-parties',
              'three-uids-normal-H2-restart-restores-parties', 'pause-restores-ordinary-party-and-home',
              'paused-source-party-reenters', 'reentered-parties-survive-normal-H2-restart',
              'operator-config-original-bytes-preserved', 'fixed-test-artifact-preserved', 'original-source-resources-preserved']
    if formal_captain:
        checks = ['actual-4012301-frozen-formal-captain-entry', 'owned-role-selections-outside-ordinary-roster',
                  'missing-required-role-rejected', 'source-captain-resolved-to-second-formal-slot',
                  'captain-change-rejected', 'formal-storage-matches-only-prepared-roles',
                  'actual-first-objective-remains-uncompleted', 'frozen-formal-reconnect-restores-party',
                  'frozen-formal-normal-H2-restart-restores-party', 'pause-restores-original-ordinary-party-and-home',
                  'empty-request-reenters-saved-formal-party', 'reentered-formal-party-survives-normal-H2-restart',
                  'operator-config-original-bytes-preserved', 'fixed-test-artifact-preserved', 'original-source-resources-preserved']
        originals['isolatedFunding'] = funding_original
    if team_thresholds:
        checks = ['isolated-four-trial-party-with-source-level-and-count-rules', 'empty-request-selects-four-declared-trials',
                  'formal-substitution-rejected', 'all-four-trials-satisfy-minimum-level-30',
                  'source-captain-retained-and-change-rejected', 'formal-owned-storage-unchanged',
                  'actual-first-objective-remains-uncompleted', 'threshold-party-reconnect-restored',
                  'threshold-party-normal-H2-restart-restored', 'pause-restores-ordinary-party-and-home',
                  'paused-threshold-party-reenters', 'reentered-threshold-party-survives-normal-H2-restart',
                  'operator-config-original-bytes-preserved', 'fixed-test-artifact-preserved', 'original-source-resources-preserved']
    if player_frozen:
        checks = ['actual-40412-player-forbid-replace-entry', 'empty-request-freezes-the-owned-ordinary-party',
                  'unowned-formal-selection-rejected', 'formal-semantic-type-in-extra-raid-party',
                  'ordinary-party-does-not-become-a-trial', 'formal-storage-remains-only-8001',
                  'actual-first-objective-remains-uncompleted', 'player-frozen-reconnect-restored',
                  'player-frozen-normal-H2-restart-restored', 'pause-restores-ordinary-party-and-home',
                  'paused-player-frozen-party-reenters', 'reentered-player-frozen-party-survives-normal-H2-restart',
                  'operator-config-original-bytes-preserved', 'fixed-test-artifact-preserved', 'original-source-resources-preserved']
    if multi_path:
        checks = ['actual-41212-source-multipath-entry', 'explicit-source-trial-1021224-selection',
                  'unrelated-owned-hero-path-rejected', 'trial-actor-1224-satisfies-source-path-rule-15',
                  'source-captain-1001-resolved-through-explicit-base-path-relationship', 'formal-storage-remains-only-8001',
                  'actual-first-objective-remains-uncompleted', 'multipath-trial-reconnect-restored',
                  'multipath-trial-normal-H2-restart-restored', 'pause-restores-ordinary-party-and-home',
                  'paused-multipath-trial-reenters', 'reentered-multipath-trial-survives-normal-H2-restart',
                  'operator-config-original-bytes-preserved', 'fixed-test-artifact-preserved', 'original-source-resources-preserved']
    if role_identity:
        checks = ['isolated-40452-source-hero-trials-with-source-include-role-rule-1', 'empty-request-selects-declared-male-trial-1028007',
                  'formal-substitution-rejected', 'source-actor-path-8007-satisfies-base-role-8001',
                  'trial-semantic-type-and-source-roster-preserved', 'formal-storage-remains-only-8001',
                  'actual-first-objective-remains-uncompleted', 'role-identity-party-reconnect-restored',
                  'role-identity-party-normal-H2-restart-restored', 'pause-restores-ordinary-party-and-home',
                  'paused-role-identity-party-reenters', 'reentered-role-identity-party-survives-normal-H2-restart',
                  'operator-config-original-bytes-preserved', 'fixed-test-artifact-preserved', 'original-source-resources-preserved']
    if trial_candidates:
        checks = ['isolated-40452-with-source-40475-thirteen-candidate-fields', 'explicit-selection-of-four-source-trials-including-last-candidate',
                  'unrelated-owned-role-without-required-1415-rejected', 'four-trials-published-in-selected-order',
                  'trial-semantic-type-and-source-first-objective-preserved', 'formal-storage-remains-only-8001',
                  'actual-first-objective-remains-uncompleted', 'four-selected-trials-reconnect-restored',
                  'four-selected-trials-normal-H2-restart-restored', 'pause-restores-ordinary-party-and-home',
                  'paused-selected-party-reenters', 'reentered-selected-party-survives-normal-H2-restart',
                  'operator-config-original-bytes-preserved', 'fixed-test-artifact-preserved', 'original-source-resources-preserved']
    if trial_role_uniqueness:
        checks = ['isolated-40452-with-source-40546-seven-candidate-fields', 'explicit-selection-of-required-1510-and-trial-hero-8007',
                  'owned-base-8001-plus-trial-8007-with-required-1510-rejected', 'distinct-base-roles-published-in-selected-order',
                  'trial-semantic-type-and-source-first-objective-preserved', 'formal-storage-remains-only-8001',
                  'actual-first-objective-remains-uncompleted', 'distinct-selected-trials-reconnect-restored',
                  'distinct-selected-trials-normal-H2-restart-restored', 'pause-restores-ordinary-party-and-home',
                  'paused-distinct-party-reenters', 'reentered-distinct-party-survives-normal-H2-restart',
                  'operator-config-original-bytes-preserved', 'fixed-test-artifact-preserved', 'original-source-resources-preserved']
    print(json.dumps(dict(passed=True, checks=checks, snapshots=snapshots, runtimeDirectory=str(directory),
        artifactSHA256=jar_sha, operatorConfig={'sha256': config_sha, 'bytesUnchangedAcrossProbesAndRestarts': True},
        fixture={'scenarios': scenarios, 'isolatedParentsOriginal': originals, 'ordinaryEntranceOriginal': original_entry,
                 'sourceSha256': before, 'sourceResourcesModified': False, 'raidAndTeamRulesUnchanged': not (team_thresholds or role_identity or trial_candidates or trial_role_uniqueness),
                 'isolatedTeamThresholdsApplied': team_thresholds, 'isolatedRoleIdentityApplied': role_identity, 'isolatedTeamOriginal': isolated_team_original,
                 'isolatedTrialCandidatesApplied': trial_candidates, 'candidateCatalogSource': candidate_catalog_source,
                 'isolatedTrialRoleUniquenessApplied': trial_role_uniqueness,
                 'sourceObjectivesScriptsAndLocationsUnchanged': True, 'parentStoryAndOrdinaryEntryGatesIsolated': True},
        clientGameplayVerified=False, officialStoryMissionVerified=False, battleInputReplayVerified=False, crashRecoveryVerified=False), ensure_ascii=False))


if __name__ == '__main__':
    import argparse
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--formal-captain', action='store_true', help='保留真实4012301与队长规则；隔离父任务、普通入口与1111角色获取')
    parser.add_argument('--team-thresholds', action='store_true', help='隔离四人试用声明及原始等级30/人数4规则；不代表原始副本1或43211已接通')
    parser.add_argument('--player-frozen', action='store_true', help='原始40412 Player队伍和ForbidReplace34；父任务及普通入口明确隔离')
    parser.add_argument('--multi-path', action='store_true', help='原始41212、指定命途1224和队长1001；父任务及普通入口明确隔离')
    parser.add_argument('--role-identity', action='store_true', help='隔离40452增加原始IncludeAvatar1；不能当作原副本队伍规则验收')
    parser.add_argument('--trial-candidates', action='store_true', help='隔离40452仅套用40475的13个候选、队伍类型及条件；不代表原40475或SkipJoinLineup已接通')
    parser.add_argument('--trial-role-uniqueness', action='store_true', help='隔离40452仅套用40546的7个候选、队伍类型及条件；验证正式主角与试用主角不能重复占槽')
    args = parser.parse_args()
    main(args.formal_captain, args.team_thresholds, args.player_frozen, args.multi_path, args.role_identity, args.trial_candidates, args.trial_role_uniqueness)
