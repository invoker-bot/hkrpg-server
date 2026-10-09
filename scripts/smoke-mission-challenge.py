"""真实忘却之庭1/15关卡与剧情目标的原生入场、战斗、重连/H2；接取门槛为隔离夹具。"""
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


def main():
    java = next((ROOT / '.tools').glob('jdk-21*/bin/java.exe'), None)
    java = str(java) if java else 'java'
    artifact = ROOT / 'build/libs/hkrpg-server.jar'
    assert artifact.is_file(), '先运行 scripts/build.ps1'
    source = ROOT / 'resources/4.6'
    runs = ROOT / 'build/smoke'
    runs.mkdir(parents=True, exist_ok=True)
    directory = Path(tempfile.mkdtemp(prefix='mission-challenge-', dir=runs))
    fixture = directory / 'resources-fixture'
    guarded = ['resource-manifest.json', 'ExcelOutput/MainMission.json', 'ExcelOutput/PlaneEvent.json',
               'ExcelOutput/StageConfig.json', 'ExcelOutput/MonsterConfig.json', 'ExcelOutput/MapEntrance.json',
               'ExcelOutput/ChallengeMazeConfig.json', 'ExcelOutput/ChallengeTargetConfig.json',
               'Config/Level/Mission/4010105/MissionInfo_4010105.partial.json',
               'Config/Level/Mission/4010106/MissionInfo_4010106.partial.json',
               'Config/Level/Mission/4010105/Mission_401010507.json']
    before = {name: hashlib.sha256((source / name).read_bytes()).hexdigest() for name in guarded}
    replacements = {}
    rows = json.loads((source / 'ExcelOutput/MainMission.json').read_text(encoding='utf-8'))
    for main in rows:
        if main['MainMissionID'] in [4010105, 4010106]:
            main.update(TakeOperation='And', BeginOperation='And', TakeParam=[{'Type': 'Manual'}],
                        BeginParam=[{'Type': 'Auto'}], RewardID=0, SubRewardList=[])
    replacements['ExcelOutput/MainMission.json'] = rows
    rows = json.loads((source / 'ExcelOutput/MapEntrance.json').read_text(encoding='utf-8'))
    for entrance in rows:
        if entrance['ID'] in [3000101, 100000103]:
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
    for main_id, sub_id in [(4010105, 401010507), (4010106, 401010601)]:
        name = f'Config/Level/Mission/{main_id}/MissionInfo_{main_id}.partial.json'
        sub = next(row for row in json.loads((source / name).read_text(encoding='utf-8'))['SubMissionList'] if row['ID'] == sub_id)
        sub.update(TakeType='Auto', TakeParamIntList=[], SubRewardID=0)
        mission = fixture / f'Config/Level/Mission/{main_id}'
        mission.mkdir(parents=True)
        (mission / f'MissionInfo_{main_id}.json').write_text(json.dumps({'MainMissionID': main_id,
            'StartSubMissionList': [sub_id], 'FinishSubMissionList': [sub_id], 'SubMissionList': [sub]}), encoding='utf-8')
        if sub.get('MissionJsonPath'):
            connect(fixture / sub['MissionJsonPath'], source / sub['MissionJsonPath'])
    (fixture / 'resource-manifest.json').write_text(json.dumps({'clientVersion': '4.6.0', 'testFixture': True,
        'onlyMissionFixtures': [4010105, 4010106], 'realChallenges': [1, 15]}), encoding='utf-8')
    http_port, mongo_port, game_port = free_port(), free_port(), free_port(socket.SOCK_DGRAM)
    (directory / 'config.json').write_text(json.dumps({'protocol': {'wireVersion': '4.6.0'}, 'resourceDir': str(fixture),
        'httpServer': {'bindAddress': '127.0.0.1', 'bindPort': http_port},
        'gameServer': {'bindAddress': '127.0.0.1', 'bindPort': game_port},
        'internalMongoServer': {'address': '127.0.0.1', 'port': mongo_port, 'filePath': './data/database.mv'}}), encoding='utf-8')
    command = [java, '-Xmx1536m', f'-Djdk.net.unixdomain.tmpdir={directory / "tcp-fallback"}', '-jar', str(artifact), '--no-console', '--no-handbook']
    base = f'http://127.0.0.1:{http_port}'

    def probe(name, credentials):
        execution = subprocess.run([java, command[2], '-cp', str(artifact),
            'org.hkrpg.server.protocol.LoopbackMissionChallengeProbe', str(game_port)], input=json.dumps(credentials),
            cwd=directory, capture_output=True, text=True, encoding='utf-8', errors='replace', timeout=35,
            creationflags=subprocess.CREATE_NO_WINDOW if os.name == 'nt' else 0)
        (directory / name).write_text(execution.stdout + execution.stderr, encoding='utf-8')
        if execution.returncode: raise RuntimeError(f'Memory challenge probe failed; inspect {directory / name}')
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
                    if process.poll() is not None: raise RuntimeError(f'Memory challenge server failed; inspect {directory}')
                    try:
                        if request(base, '/health/ready')['ready']: break
                    except (OSError, urllib.error.URLError): pass
                    if time.monotonic() > deadline: raise RuntimeError(f'Memory challenge server timeout; inspect {directory}')
                    time.sleep(.2)
                if iteration == 0:
                    login = request(base, '/hkrpg_cn/mdk/shield/api/login', {'account': 'cn-46-memory-challenge', 'password': 'local-test'})
                    assert login['retcode'] == 0
                    account = login['data']['account']
                    combo = request(base, '/hkrpg_cn/combo/granter/login/v2/login', {'data': json.dumps({'uid': account['uid'], 'token': account['token']})})
                    assert combo['retcode'] == 0
                    credentials = {'accountUid': account['uid'], 'comboToken': combo['data']['combo_token']}
                    first = probe('mission-challenge-first.log', credentials)
                    for key in ['packedLineupStarted', 'formalIdentifierLineupsStarted', 'wrongIdentityRejected',
                                'rejectedReportPreservedBattle', 'duplicateReportRejected', 'actualCountCompletedOnce',
                                'firstHalfStayedPending', 'actualSpecifiedTwoHalfCompleted', 'nativeSettleNotification', 'actualLeaveCompleted',
                                'nativeCurrentChallengeQuery', 'nativeSecondHalfQuery', 'lastBattleRoundsBeforeStars',
                                'bothHalfRoundsBeforeStars', 'nativeHistoryStars', 'repeatedLeaveRejected',
                                'overflowRoundsRejected', 'overflowRoundsPreservedBattle']:
                        assert first[key], key
                    snapshots.append(first)
                    credentials.update(resume=True)
                restored = probe(f'mission-challenge-resume-{iteration}.log', credentials)
                assert restored['savedObjectivesRestored']
                assert restored['savedStarsRestored'] and restored['nativeHistoryStars']
                assert restored['firstWinStars'] == restored['specifiedWinStars'] == 5
                assert restored['uid'] == first['uid'] and restored['countProgress'] == restored['specifiedProgress'] == 1
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
        'checks': ['actual-packed-memory-lineup-entry', 'actual-formal-identifiers-two-half-entry',
                   'wrong-battle-identity-rejected', 'rejected-report-preserves-battle', 'repeated-settlement-rejected',
                   'actual-count-objective-completes-once', 'first-half-stays-pending', 'specified-challenge-two-half-completes',
                   'native-win-settlement-notification', 'actual-challenge-leave', 'reconnect-restores-progress',
                   'h2-process-restart-restores-progress', 'native-current-challenge-query', 'native-second-half-query',
                   'last-battle-rounds-before-stars', 'both-half-rounds-before-stars', 'native-history-stars',
                   'repeated-leave-rejected', 'h2-process-restart-restores-stars', 'overflow-rounds-rejected',
                   'overflow-rounds-preserve-active-battle'],
        'fixture': {'onlyMissions': [4010105, 4010106], 'subMissions': [401010507, 401010601],
                    'challenges': [1, 15], 'stages': [30001011, 30001151, 30001152], 'entrance': 3000101,
                    'acceptanceAndEntranceGatesRelaxed': True, 'parentFinishesAtSelectedObjective': True,
                    'reportedRoundsPerBattle': 8, 'expectedStarMasks': {'1': 5, '15': 5},
                    'sourceSha256': before, 'sourceResourcesModified': False, 'officialStoryMissionVerified': False}, 'clientGameplayVerified': False}, ensure_ascii=False))


if __name__ == '__main__':
    main()
