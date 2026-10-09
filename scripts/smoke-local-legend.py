"""地方传奇专用入口、原始世界等级关卡、目标奖励和两次正常H2恢复；结果报告与主线前置使用明确夹具。"""
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
    source = ROOT/'resources/4.6'
    jar = ROOT/'build/libs/hkrpg-server.jar'
    java = next((ROOT/'.tools').glob('jdk-21*/bin/java.exe'), None)
    classes = ROOT/'build/classes/java/test'
    assert java and jar.is_file()
    assert (classes/'org/hkrpg/server/gameplay/LocalLegendFixtureEditor.class').is_file()
    digest = hashlib.sha256(jar.read_bytes()).hexdigest()
    runs = ROOT/'build/smoke'
    runs.mkdir(exist_ok=True)
    directory = Path(tempfile.mkdtemp(prefix='local-legend-', dir=runs))
    assert directory.resolve().is_relative_to(runs.resolve())
    fixture = directory/'resources-fixture'
    guarded = ['resource-manifest.json'] + ['ExcelOutput/'+name+'.json' for name in [
        'LocalLegendStageConfig', 'ActivityLocalLegendGroup', 'ActivityLocalLegendReward', 'BattleTargetConfig',
        'PlaneEvent', 'StageConfig', 'ActivityConfig', 'ActivityPanel', 'ActivityPanelCondition',
        'AvatarConfig', 'SpecialAvatar', 'QuestData', 'FinishWay', 'RewardData', 'ItemConfig', 'BattleAchievement']]
    hashes = {name: hashlib.sha256((source/name).read_bytes()).hexdigest() for name in guarded}
    junctions = mirror_resources(source, fixture, {})
    flags = subprocess.CREATE_NO_WINDOW if os.name == 'nt' else 0
    if junctions:
        plan = directory/'junction-plan.json'
        plan.write_text(json.dumps(junctions), 'utf-8')
        helper = directory/'prepare-fixture.ps1'
        helper.write_text("param([string]$Plan)\n$ErrorActionPreference='Stop'\nforeach ($binding in (Get-Content -LiteralPath $Plan -Raw | ConvertFrom-Json)) { New-Item -ItemType Junction -Path $binding.target -Value $binding.source | Out-Null }\n", 'utf-8')
        subprocess.run(['powershell', '-NoProfile', '-NonInteractive', '-File', str(helper), '-Plan', str(plan)], check=True, capture_output=True, creationflags=flags)
    (fixture/'resource-manifest.json').write_text(json.dumps({'clientVersion': '4.6.0', 'testFixture': True, 'localLegendFixture': True}), 'utf-8')
    assert not os.path.samefile(fixture/'resource-manifest.json', source/'resource-manifest.json')
    tables = {name: json.loads((source/'ExcelOutput'/f'{name}.json').read_text('utf-8')) for name in ['LocalLegendStageConfig', 'PlaneEvent', 'StageConfig']}
    stages = {row['StageID']: row for row in tables['StageConfig']}
    plans = {}
    for level in tables['LocalLegendStageConfig']:
        if level['GroupID'] != 1:
            continue
        matches = [row for row in tables['PlaneEvent'] if row['EventID'] == level['EventID'] and row.get('WorldLevel', 0) == 1]
        assert len(matches) == 1
        stage = stages[matches[0]['StageID']]
        assert stage['StageType'] == 'LocalLegend'
        plans[str(stage['StageID'])] = [[wave[key] for key in ['Monster0', 'Monster1', 'Monster2', 'Monster3', 'Monster4'] if wave.get(key, 0)] for wave in stage['MonsterList']]
    assert set(plans) == {'4260041', '4260011', '426005'}
    targets = {row['ID']: row for row in json.loads((source/'ExcelOutput/BattleTargetConfig.json').read_text('utf-8'))}
    difficulties = {'Easy': '1', 'Hard': '2', 'Fearless': '3'}
    target_plans = {difficulties[row['DifficultyLevel']]: [
        {'id': ident, 'threshold': targets[ident]['TargetParam']} for ident in row['BattleTargetList']]
        for row in json.loads((source/'ExcelOutput/LocalLegendStageConfig.json').read_text('utf-8'))
        if row['GroupID'] == 1}
    assert set(target_plans) == {'1', '2', '3'}
    data = directory/'data'
    data.mkdir()
    (data/'Gameplay46.json').write_text(json.dumps({'activities': [{'panelId': 50070, 'moduleId': 5007003, 'beginTime': 1, 'endTime': 4102444800}]}), 'utf-8')
    http, mongo, game = free_port(), free_port(), free_port(socket.SOCK_DGRAM)
    (directory/'config.json').write_text(json.dumps({'protocol': {'wireVersion': '4.6.0'}, 'resourceDir': str(fixture),
        'httpServer': {'bindAddress': '127.0.0.1', 'bindPort': http}, 'gameServer': {'bindAddress': '127.0.0.1', 'bindPort': game},
        'internalMongoServer': {'address': '127.0.0.1', 'port': mongo, 'filePath': './data/database.mv'}}), 'utf-8')
    java_flags = ['-Dfile.encoding=UTF-8', '-Dstdout.encoding=UTF-8', '-Dstderr.encoding=UTF-8', '-Djdk.net.unixdomain.tmpdir='+str(directory/'tcp-fallback')]
    command = [str(java), *java_flags, '-Xmx1536m', '-jar', str(jar), '--no-handbook']
    base = f'http://127.0.0.1:{http}'
    credentials = None
    snapshots, edits = [], []
    stops = 0

    def request(route, body=None):
        req = urllib.request.Request(base+route, data=None if body is None else json.dumps(body).encode(), headers={'Content-Type': 'application/json'})
        with urllib.request.urlopen(req, timeout=5) as response:
            return json.load(response)

    def probe(mode):
        assert hashlib.sha256(jar.read_bytes()).hexdigest() == digest
        result = subprocess.run([str(java), *java_flags, '-cp', str(jar), 'org.hkrpg.server.protocol.LoopbackLocalLegendProbe', str(game)],
            input=json.dumps(dict(credentials, mode=mode, stagePlans=plans, targetPlans=target_plans)), text=True, encoding='utf-8', capture_output=True, timeout=90, creationflags=flags)
        (directory/f'probe-{len(snapshots)}-{mode}.log').write_text(result.stdout+result.stderr, 'utf-8')
        assert result.returncode == 0, directory
        value = json.loads(next(line for line in reversed(result.stdout.splitlines()) if line.startswith('{')))
        assert value['passed']
        snapshots.append(value)
        return value

    def editor(mode):
        result = subprocess.run([str(java), *java_flags, '-cp', os.pathsep.join([str(classes), str(jar)]), 'org.hkrpg.server.gameplay.LocalLegendFixtureEditor', str(directory), str(snapshots[0]['uid']), mode],
            cwd=ROOT, text=True, encoding='utf-8', capture_output=True, timeout=45, creationflags=flags)
        (directory/f'editor-{len(edits)}-{mode}.log').write_text(result.stdout+result.stderr, 'utf-8')
        assert result.returncode == 0, directory
        value = json.loads(next(line for line in reversed(result.stdout.splitlines()) if line.startswith('{')))
        assert value['passed'] and value['matchingPlayerCount'] == 1 and not value['operatorDataModified']
        edits.append(value)
        return value

    for iteration, modes in enumerate([['bootstrap'], ['battle', 'read'], ['read'], ['read']]):
        with (directory/f'server-{iteration}.log').open('w', encoding='utf-8') as log:
            process = subprocess.Popen(command, cwd=directory, stdin=subprocess.PIPE, stdout=log, stderr=subprocess.STDOUT, creationflags=flags)
            try:
                deadline = time.monotonic()+90
                while True:
                    assert process.poll() is None, directory
                    try:
                        if request('/health/ready')['ready']:
                            break
                    except (OSError, urllib.error.URLError):
                        pass
                    if time.monotonic() > deadline:
                        raise RuntimeError(f'Local Legend readiness timeout: {directory}')
                    time.sleep(.2)
                if iteration == 0:
                    login = request('/hkrpg_cn/mdk/shield/api/login', {'account': 'cn-46-local-legend', 'password': 'local-test'})
                    assert login['retcode'] == 0
                    account = login['data']['account']
                    combo = request('/hkrpg_cn/combo/granter/login/v2/login', {'data': json.dumps({'uid': account['uid'], 'token': account['token']})})
                    assert combo['retcode'] == 0
                    credentials = {'accountUid': account['uid'], 'comboToken': combo['data']['combo_token']}
                for mode in modes:
                    value = probe(mode)
                    if mode == 'bootstrap':
                        credentials['expectedHcoin'] = value['initialHcoin']+120
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
        saved = editor('seed' if iteration == 0 else 'inspect')
        assert saved['level'] == 21 and saved['worldLevel'] == 1 and saved['restoredMainMissionGate']
        if iteration == 0:
            assert all(mask == 0 for mask in saved['restoredMasks'].values())
            assert saved['restoredAchievementReceipts'] == {}
            assert len(saved['restoredAchievementQuestPhases']) == 10
            assert set(saved['restoredAchievementQuestPhases'].values()) == {'DOING'}
            credentials['initialScoin'] = saved['scoin']
        else:
            assert saved['restoredMasks'] == dict(snapshots[-1]['masks'])
            assert saved['actualStoredMaterials'] == {'213': 6, '232': 5, '110222': 6}
            assert saved['hcoin'] == credentials['expectedHcoin'] and saved['scoin'] == credentials['initialScoin']+60000
            assert saved['restoredAchievementReceipts'] == {'6072147': 30213, '6072148': 30214}
            assert saved['restoredAchievementQuestPhases'] == {
                str(q): 'CLOSED' if q in [6072147, 6072148] else 'DOING'
                for q in [6072147, 6072148, 6072150, 6072151, 6072153, 6072154, 6072156, 6072157, 6072159, 6072160]}
            assert [saved['targetQuests'][str(id)]['phase'] for id in [6072101, 6072102, 6072113, 6072114]] == ['FINISHED', 'FINISHED', 'CLOSED', 'CLOSED']
    assert [value['mode'] for value in snapshots] == ['bootstrap', 'battle', 'read', 'read', 'read']
    assert len({value['uid'] for value in snapshots}) == 1 and stops == 4
    assert hashes == {name: hashlib.sha256((source/name).read_bytes()).hexdigest() for name in guarded}
    assert hashlib.sha256(jar.read_bytes()).hexdigest() == digest
    print(json.dumps({'passed': True, 'runtimeDirectory': str(directory), 'snapshots': snapshots, 'offlineReads': edits,
        'checks': [check for value in snapshots for check in value['checks']], 'normalStopCount': stops, 'sameUIDNormalH2ProcessRestartsAfterSettlement': 2,
        'rootJarSHA256': digest, 'sourceSHA256': hashes, 'sourceResourcesModified': False,
        'exactOriginalWorldOneStagesAndMonsterWavesVerified': True, 'formalAndDeclaredTrialPartyIsolationVerified': True,
        'initialNativeTargetMapZeroProgressAndOriginalThresholdsVerified': True,
        'actualStoredSourceTargetRewardsVerified': True, 'targetBitFourVerified': True,
        'actualStoredSourceAchievementRewardsAndBoundReceiptsVerified': True,
        'sourceWinnerLeafWithoutAchievementStatisticsVerified':
            'source-winner-leaf-without-achievement-statistics-win-2' in snapshots[1]['checks'],
        'sourceWinnerMainTargetWithoutStatisticsVerified':
            'source-winner-main-target-without-statistics-win-1' in snapshots[1]['checks'],
        'fixture': {'finishedMainMissionSeeded': 1040416, 'playerLevelSeeded': 21, 'worldLevelSeeded': 1, 'operatorActivityWindowExplicit': True,
                    'fixtureEditorIsTestClassOnly': True, 'missionScriptDirectoryIsolatedAndEmpty': True, 'originalGameplayTablesUnmodified': True},
        'constructedResultReports': True, 'winOnlyProgressAndFullHealthHalfEnergyAreProjectPolicy': True,
        'clientGameplayVerified': False, 'officialMainMissionChainVerified': False, 'initialClientTargetCreationVerified': False,
        'battleEvent20037ExecutionVerified': False, 'crashAtomicityVerified': False}, ensure_ascii=False))


if __name__ == '__main__':
    main()
