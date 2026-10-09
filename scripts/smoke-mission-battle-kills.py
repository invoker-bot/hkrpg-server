"""真实305001事件/800204001名单的原生结算、重连与正常H2重启；接取门槛为隔离夹具。"""
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
    directory = Path(tempfile.mkdtemp(prefix='mission-battle-kills-', dir=runs))
    fixture = directory / 'resources-fixture'
    guarded = ['resource-manifest.json', 'ExcelOutput/MainMission.json', 'ExcelOutput/PlaneEvent.json',
               'ExcelOutput/StageConfig.json', 'ExcelOutput/MonsterConfig.json', 'ExcelOutput/MapEntrance.json',
               'Config/Level/Mission/8000181/MissionInfo_8000181.json',
               'Config/Level/Mission/8000181/MissionInfo_8000181.partial.json',
               'Config/Level/Mission/8000181/Mission_800018102.json']
    before = {name: hashlib.sha256((source / name).read_bytes()).hexdigest() for name in guarded}
    replacements = {}
    rows = json.loads((source / 'ExcelOutput/MainMission.json').read_text(encoding='utf-8'))
    main = next(row for row in rows if row['MainMissionID'] == 8000181)
    main.update(TakeOperation='And', BeginOperation='And', TakeParam=[{'Type': 'Manual'}],
                BeginParam=[{'Type': 'Auto'}], RewardID=0, SubRewardList=[])
    replacements['ExcelOutput/MainMission.json'] = rows
    rows = json.loads((source / 'ExcelOutput/MapEntrance.json').read_text(encoding='utf-8'))
    entrance = next(row for row in rows if row['ID'] == 1010101)
    assert entrance['PlaneID'] == 10101 and entrance['FloorID'] == 10101001
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
    sub = next(row for row in json.loads((source / guarded[7]).read_text(encoding='utf-8'))['SubMissionList'] if row['ID'] == 800018102)
    sub.update(TakeType='Auto', TakeParamIntList=[], SubRewardID=0)
    mission = fixture / 'Config/Level/Mission/8000181'
    mission.mkdir(parents=True)
    (mission / 'MissionInfo_8000181.json').write_text(json.dumps({'MainMissionID': 8000181,
        'StartSubMissionList': [800018102], 'FinishSubMissionList': [800018102], 'SubMissionList': [sub]}), encoding='utf-8')
    connect(mission / 'Mission_800018102.json', source / guarded[8])
    (fixture / 'resource-manifest.json').write_text(json.dumps({'clientVersion': '4.6.0', 'testFixture': True,
        'onlyMissionFixture': 8000181, 'realEvent': 305001}), encoding='utf-8')
    http_port, mongo_port, game_port = free_port(), free_port(), free_port(socket.SOCK_DGRAM)
    (directory / 'config.json').write_text(json.dumps({'protocol': {'wireVersion': '4.6.0'}, 'resourceDir': str(fixture),
        'httpServer': {'bindAddress': '127.0.0.1', 'bindPort': http_port},
        'gameServer': {'bindAddress': '127.0.0.1', 'bindPort': game_port},
        'internalMongoServer': {'address': '127.0.0.1', 'port': mongo_port, 'filePath': './data/database.mv'}}), encoding='utf-8')
    command = [java, '-Xmx1536m', f'-Djdk.net.unixdomain.tmpdir={directory / "tcp-fallback"}', '-jar', str(artifact), '--no-console', '--no-handbook']
    base = f'http://127.0.0.1:{http_port}'

    def probe(name, credentials):
        execution = subprocess.run([java, command[2], '-cp', str(artifact),
            'org.hkrpg.server.protocol.LoopbackMissionBattleKillsProbe', str(game_port)], input=json.dumps(credentials),
            cwd=directory, capture_output=True, text=True, encoding='utf-8', errors='replace', timeout=35,
            creationflags=subprocess.CREATE_NO_WINDOW if os.name == 'nt' else 0)
        (directory / name).write_text(execution.stdout + execution.stderr, encoding='utf-8')
        if execution.returncode: raise RuntimeError(f'Battle kill probe failed; inspect {directory / name}')
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
                    if process.poll() is not None: raise RuntimeError(f'Battle kill server failed; inspect {directory}')
                    try:
                        if request(base, '/health/ready')['ready']: break
                    except (OSError, urllib.error.URLError): pass
                    if time.monotonic() > deadline: raise RuntimeError(f'Battle kill server timeout; inspect {directory}')
                    time.sleep(.2)
                if iteration == 0:
                    login = request(base, '/hkrpg_cn/mdk/shield/api/login', {'account': 'cn-46-battle-kills', 'password': 'local-test'})
                    assert login['retcode'] == 0
                    account = login['data']['account']
                    combo = request(base, '/hkrpg_cn/combo/granter/login/v2/login', {'data': json.dumps({'uid': account['uid'], 'token': account['token']})})
                    assert combo['retcode'] == 0
                    credentials = {'accountUid': account['uid'], 'comboToken': combo['data']['combo_token']}
                    first = probe('battle-kills-first.log', credentials)
                    for key in ['actualSceneEntered', 'actualEventStageAndRoster', 'wrongBattleIdentityRejected',
                                'unknownDeathRejected', 'invalidReportPreservedBattle', 'escapeStayedPending',
                                'wrongMonsterStageStayedPending', 'duplicateMonsterStayedPending',
                                'actualEventKillCompleted', 'repeatedBattleResultRejected']:
                        assert first[key], key
                    snapshots.append(first)
                    credentials.update(resume=True)
                restored = probe(f'battle-kills-resume-{iteration}.log', credentials)
                assert restored['savedMissionRestored'] and restored['finishedMissionRetryRejected']
                assert restored['uid'] == first['uid'] and restored['objectiveProgress'] == 1
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
        'checks': ['actual-scene-event-stage-roster', 'wrong-battle-identity-rejected', 'unknown-death-enum-rejected',
                   'invalid-report-preserves-active-battle', 'escape-stays-pending', 'wrong-monster-stage-stays-pending',
                   'duplicate-monsters-stay-pending', 'actual-kill-completes-once', 'reconnect-restores-progress',
                   'h2-process-restart-restores-progress'],
        'fixture': {'onlyMission': 8000181, 'subMission': 800018102, 'event': 305001, 'stage': 3050010,
                    'monster': 800204001, 'entrance': 1010101, 'acceptanceAndEntranceGatesRelaxed': True,
                    'parentFinishesAtSelectedObjective': True, 'sourceSha256': before, 'sourceResourcesModified': False,
                    'officialStoryMissionVerified': False}, 'clientGameplayVerified': False}, ensure_ascii=False))


if __name__ == '__main__':
    main()
