"""原始宇宙遭遇、已接受死亡报告和成就领奖；部分房间中途正常 H2 恢复。"""
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
    parser.add_argument('--target', type=int, choices=[15, 1000], required=True)
    parser.add_argument('--verify-story-blessing', action='store_true',
                        help='验证首战祝福通知、重启保存、选择防重及原始机关状态')
    parser.add_argument('--selected-before-restart', action='store_true',
                        help='在首次停服之前选择祝福，验证已选结果和机关跨进程恢复')
    parser.add_argument('--legacy-checkpoint-jar', type=Path,
                        help='在隔离旧版服务上先选择祝福，再用当前构建验证同一存档升级恢复')
    parser.add_argument('--checkpoint-inside', action='store_true',
                        help='停服时保留在探索场景，验证登录时场景直接恢复')
    args = parser.parse_args()
    if args.selected_before_restart and not args.verify_story_blessing:
        parser.error('--selected-before-restart requires --verify-story-blessing')
    if args.legacy_checkpoint_jar and not (args.verify_story_blessing and args.selected_before_restart):
        parser.error('--legacy-checkpoint-jar requires selected-before-restart and verify-story-blessing')
    if args.checkpoint_inside and not args.legacy_checkpoint_jar:
        parser.error('--checkpoint-inside requires legacy-checkpoint-jar')
    source = ROOT / 'resources/4.6'
    jar = ROOT / 'build/libs/hkrpg-server.jar'
    java = next((ROOT / '.tools').glob('jdk-21*/bin/java.exe'), None)
    assert java and jar.is_file()
    digest = hashlib.sha256(jar.read_bytes()).hexdigest()
    legacy_jar = args.legacy_checkpoint_jar.resolve() if args.legacy_checkpoint_jar else None
    if legacy_jar:
        assert legacy_jar.is_file() and hashlib.sha256(legacy_jar.read_bytes()).hexdigest() != digest
    legacy_digest = hashlib.sha256(legacy_jar.read_bytes()).hexdigest() if legacy_jar else None
    runs = ROOT / 'build/smoke'
    runs.mkdir(exist_ok=True)
    directory = Path(tempfile.mkdtemp(prefix=f'battle-kill-quests-{args.target}-', dir=runs))
    assert directory.resolve().is_relative_to(runs.resolve())
    fixture = directory / 'resources-fixture'
    guarded = ['resource-manifest.json'] + ['ExcelOutput/' + name + '.json' for name in [
        'MainMission','QuestData','FinishWay','AchievementData','RewardData','ItemConfig',
        'RogueRoom','MapEntrance','MazePlane','RogueMonster','NPCMonsterData','StageConfig','MonsterConfig',
        'AvatarConfig','AvatarSkillConfig','ConstValueRogue']]
    guarded += ['Config/LevelOutput/RuntimeFloor/P80001_F80001001.json']
    guarded += [f'Config/LevelOutput/SharedRuntimeGroup/Groups_P80001_F80001001/LevelGroup_P80001_F80001001_G{g}.json'
                for g in [3,4,5,10,11,12]]
    folder = source / 'Config/Level/Mission/1000201'
    assert folder.is_dir()
    guarded.extend(p.relative_to(source).as_posix() for p in folder.rglob('*') if p.is_file())
    hashes = {name: hashlib.sha256((source / name).read_bytes()).hexdigest() for name in guarded}
    assert hashes['resource-manifest.json'] == 'ecccb21469df76282e6df5bf0cbe1eff7d9f904c2dd644092124b6904482e735'
    mains = json.loads((source / 'ExcelOutput/MainMission.json').read_text('utf-8'))
    row = next(r for r in mains if r['MainMissionID'] == 1000201)
    row.update(TakeOperation='And', TakeParam=[{'Type': 'Auto'}], BeginOperation='And',
               BeginParam=[{'Type': 'Auto'}], RewardID=0, SubRewardList=[])
    replacements = {'ExcelOutput/MainMission.json': mains}
    junctions = mirror_resources(source, fixture, replacements)
    # Only the no-reward prerequisite graph is substituted; all source actors and combat rosters remain original.
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
    pools['1'] = [100]
    (data / 'RogueMapGen.json').write_text(json.dumps(pools), 'utf-8')
    http, mongo, game = free_port(), free_port(), free_port(socket.SOCK_DGRAM)
    (directory / 'config.json').write_text(json.dumps({
        'protocol': {'wireVersion': '4.6.0'}, 'resourceDir': str(fixture),
        'httpServer': {'bindAddress': '127.0.0.1', 'bindPort': http},
        'gameServer': {'bindAddress': '127.0.0.1', 'bindPort': game},
        'internalMongoServer': {'address': '127.0.0.1', 'port': mongo, 'filePath': './data/database.mv'}}), 'utf-8')
    classes = directory / 'probe-classes'
    classes.mkdir()
    probe_source = ROOT / 'scripts/fixtures/java/org/hkrpg/server/protocol/NativeBattleKillQuestProbe.java'
    compilation = subprocess.run([str(java.with_name('javac.exe')), '-encoding', 'UTF-8', '-cp', str(jar),
                                  '-d', str(classes), str(probe_source)], capture_output=True, creationflags=flags)
    (directory / 'javac.log').write_bytes(compilation.stdout + compilation.stderr)
    assert compilation.returncode == 0, directory / 'javac.log'
    fallback = '-Djdk.net.unixdomain.tmpdir=' + str(directory / 'tcp-fallback')
    def server_command(runtime_jar):
        return [str(java), '-Xmx1536m', fallback, '-jar', str(runtime_jar), '--no-handbook']

    def request(route, body=None):
        req = urllib.request.Request(f'http://127.0.0.1:{http}' + route,
            data=None if body is None else json.dumps(body).encode(), headers={'Content-Type': 'application/json'})
        with urllib.request.urlopen(req, timeout=5) as response:
            return json.load(response)

    def probe(name, credentials):
        assert hashlib.sha256(jar.read_bytes()).hexdigest() == digest
        child = subprocess.run([str(java), fallback, '-cp', str(jar) + os.pathsep + str(classes),
            'org.hkrpg.server.protocol.NativeBattleKillQuestProbe', str(game)], cwd=directory,
            input=json.dumps(credentials) + '\n', stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
            text=True, encoding='utf-8', errors='replace', timeout=360, creationflags=flags)
        (directory / name).write_text(child.stdout, 'utf-8')
        assert child.returncode == 0, directory / name
        snapshot = json.loads(next(line for line in child.stdout.splitlines() if line.startswith('{"uid":')))
        assert snapshot['passed'] and not snapshot['clientGameplayVerified']
        assert snapshot['target'] == args.target
        assert snapshot['jades'] == (0 if credentials['mode'] == 'checkpoint' else 5 if args.target == 15 else 15)
        return snapshot

    snapshots, credentials, stops = [], None, 0
    for iteration in range(3):
        runtime_jar = legacy_jar if iteration == 0 and legacy_jar else jar
        if legacy_jar: assert hashlib.sha256(legacy_jar.read_bytes()).hexdigest() == legacy_digest
        with (directory / f'server-{iteration}.log').open('w', encoding='utf-8') as log:
            process = subprocess.Popen(server_command(runtime_jar), cwd=directory, stdin=subprocess.PIPE,
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
                                   'target': args.target, 'mode': 'checkpoint',
                                   'verifyStoryBlessing': args.verify_story_blessing,
                                   'selectedBeforeRestart': args.selected_before_restart,
                                   'legacyCheckpoint': bool(legacy_jar),
                                   'checkpointInside': args.checkpoint_inside}
                    checkpoint = probe('checkpoint.log', credentials)
                    assert checkpoint['count'] == checkpoint['actualReportedDeaths'] == 2
                    assert checkpoint['actualEncounters'] == checkpoint['actualExplorations'] == 1
                    assert checkpoint['questQueries'] == 2 and checkpoint['jadeNotifications'] == 0
                    if args.verify_story_blessing:
                        assert checkpoint['blessingNotifications'] == 1
                        assert checkpoint['blessingSelections'] == (1 if args.selected_before_restart else 0)
                        prefix = 'selected' if args.selected_before_restart else 'pending'
                        credentials['expectedBatch'] = checkpoint[prefix + 'BlessingBatch']
                        credentials['expectedBuff'] = checkpoint[prefix + 'BlessingBuff']
                        assert credentials['expectedBatch'] > 0 and credentials['expectedBuff'] > 0
                    snapshots.append(checkpoint)
                elif iteration == 1:
                    credentials['mode'] = 'complete'
                    credentials['legacyCheckpoint'] = False
                    completed = probe('completed.log', credentials)
                    assert completed['count'] == args.target and completed['actualReportedDeaths'] == args.target - 2
                    assert completed['actualExplorations'] == completed['actualEncounters'] == (args.target + 1)//2 - 1
                    assert completed['jadeNotifications'] == (1 if args.target == 15 else 2)
                    assert completed['questQueries'] == 3
                    if args.verify_story_blessing:
                        assert completed['blessingSelections'] == completed['actualEncounters'] + (0 if args.selected_before_restart else 1)
                        assert completed['blessingNotifications'] == completed['actualEncounters']
                    snapshots.append(completed)
                if iteration > 0:
                    credentials['mode'] = 'resume'
                    resumed = probe(f'resume-{iteration}.log', credentials)
                    assert resumed['actualReportedDeaths'] == resumed['questNotifications'] == resumed['removalNotifications'] == resumed['jadeNotifications'] == 0
                    assert resumed['count'] == args.target and resumed['questQueries'] == 2
                    snapshots.append(resumed)
                assert all(s['uid'] == snapshots[0]['uid'] for s in snapshots)
                assert all(s['actualEncounters'] == s['questNotifications'] == s['removalNotifications'] == s['staleResultsRejected'] for s in snapshots)
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
    assert sum(s['actualReportedDeaths'] for s in snapshots) == args.target
    assert sum(s['actualExplorations'] for s in snapshots) == (args.target + 1)//2
    print(json.dumps({'passed': True, 'runtimeDirectory': str(directory), 'rootJarSHA256': digest,
        'legacyCheckpointJarSHA256': legacy_digest,
        'checkpointInside': args.checkpoint_inside,
        'target': args.target, 'snapshots': snapshots, 'normalStops': stops,
        'sameUIDNormalH2ProcessRestarts': 2, 'sourceResourceHashes': hashes, 'sourceResourcesModified': False,
        'fixture': {'syntheticNoRewardPrerequisites': [1000201], 'initialFragments': 50,
            'firstRoomAssigned': 100, 'originalRoomGroupActorsRostersAndAchievementRulesUnchanged': True,
            'deathCounterDefeatReceiptsCompletionAndJadesSeeded': False,
            'officialTutorialVerified': False, 'officialRoomGenerationVerified': False},
        'clientGameplayVerified': False, 'combatInputReplayVerified': False, 'crashAtomicityVerified': False}, ensure_ascii=False))



if __name__ == '__main__':
    main()
