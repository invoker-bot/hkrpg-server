"""源关卡1001的任务授权胜利上报、实际奖励、重连和H2重启；不执行客户端本地战斗。"""
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
    directory = Path(tempfile.mkdtemp(prefix='dice-pve-', dir=runs))
    fixture = directory / 'resources-fixture'
    guarded = ['resource-manifest.json', 'ExcelOutput/ActivityDiceStageConfig.json',
        'ExcelOutput/RewardData.json', 'ExcelOutput/MainMission.json', 'ExcelOutput/ItemConfig.json',
        'ExcelOutput/ActivityDiceAvatarConfig.json', 'ExcelOutput/ActivityDiceConfig.json',
        'Config/Level/Mission/1054003/MissionInfo_1054003.partial.json']
    hashes = {name: hashlib.sha256((source / name).read_bytes()).hexdigest() for name in guarded}
    rows = json.loads((source / 'ExcelOutput/MainMission.json').read_text('utf-8'))
    main_row = next(row for row in rows if row['MainMissionID'] == 1054003)
    original_gates = {key: main_row.get(key) for key in ['TakeOperation', 'TakeParam', 'BeginOperation', 'BeginParam']}
    main_row.update(TakeOperation='And', BeginOperation='And', TakeParam=[{'Type': 'Auto'}],
                    BeginParam=[{'Type': 'Auto'}], RewardID=0, SubRewardList=[])
    excel = fixture / 'ExcelOutput'
    excel.mkdir(parents=True)
    for path in (source / 'ExcelOutput').iterdir():
        if path.name == 'MainMission.json':
            (excel / path.name).write_text(json.dumps(rows, ensure_ascii=False), 'utf-8')
        else:
            os.link(path, excel / path.name)
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

    for path in (source / 'Config').iterdir():
        if path.name != 'Level':
            connect(fixture / 'Config' / path.name, path)
    for path in (source / 'Config/Level').iterdir():
        if path.name != 'Mission':
            connect(fixture / 'Config/Level' / path.name, path)
    if junctions:
        plan = directory / 'junction-plan.json'
        plan.write_text(json.dumps(junctions), 'utf-8')
        helper = directory / 'prepare-fixture.ps1'
        helper.write_text("param([string]$Plan)\n$ErrorActionPreference = 'Stop'\n"
                          "foreach ($binding in (Get-Content -LiteralPath $Plan -Raw | ConvertFrom-Json)) {\n"
                          "  New-Item -ItemType Junction -Path $binding.target -Value $binding.source | Out-Null\n}\n", 'utf-8')
        subprocess.run(['powershell', '-NoProfile', '-NonInteractive', '-File', str(helper), '-Plan', str(plan)],
                       check=True, capture_output=True, text=True, creationflags=subprocess.CREATE_NO_WINDOW)
    original_sub = next(row for row in json.loads((source / guarded[-1]).read_text('utf-8'))['SubMissionList']
                        if row['ID'] == 105400302)
    sub = dict(original_sub)
    sub.update(TakeType='Auto', TakeParamIntList=[], LevelPlaneID=20001, LevelFloorID=20001001, GroupIDList=[])
    mission = fixture / 'Config/Level/Mission/1054003'
    mission.mkdir(parents=True)
    (mission / 'MissionInfo_1054003.json').write_text(json.dumps({'MainMissionID': 1054003,
        'StartSubMissionList': [105400302], 'FinishSubMissionList': [], 'SubMissionList': [sub]}, ensure_ascii=False), 'utf-8')
    for key in ['FinishType', 'ParamType', 'ParamInt1', 'ParamIntList', 'Progress']:
        assert sub[key] == original_sub[key]
    (fixture / 'resource-manifest.json').write_text(json.dumps({'clientVersion': '4.6.0', 'testFixture': True,
        'onlyMissionFixtures': [1054003], 'actualPveStage': 1001}), 'utf-8')
    data_dir = directory / 'data'
    data_dir.mkdir()
    (data_dir / 'Gameplay46.json').write_text(json.dumps({'activities': []}), 'utf-8')
    http_port, mongo_port, game_port = free_port(), free_port(), free_port(socket.SOCK_DGRAM)
    (directory / 'config.json').write_text(json.dumps({'protocol': {'wireVersion': '4.6.0'}, 'resourceDir': str(fixture),
        'httpServer': {'bindAddress': '127.0.0.1', 'bindPort': http_port},
        'gameServer': {'bindAddress': '127.0.0.1', 'bindPort': game_port},
        'internalMongoServer': {'address': '127.0.0.1', 'port': mongo_port, 'filePath': './data/database.mv'}}), 'utf-8')
    command = [java, '-Xmx1536m', f'-Djdk.net.unixdomain.tmpdir={directory / "tcp-fallback"}', '-jar', str(artifact),
               '--no-console', '--no-handbook']
    base = f'http://127.0.0.1:{http_port}'

    def probe(name, credentials):
        execution = subprocess.run([java, command[2], '-cp', str(artifact),
            'org.hkrpg.server.protocol.LoopbackDicePveProbe', str(game_port)], input=json.dumps(credentials),
            cwd=directory, capture_output=True, text=True, encoding='utf-8', errors='replace', timeout=35,
            creationflags=subprocess.CREATE_NO_WINDOW if os.name == 'nt' else 0)
        (directory / name).write_text(execution.stdout + execution.stderr, 'utf-8')
        if execution.returncode:
            raise RuntimeError(f'Dice PVE probe failed; inspect {directory / name}')
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
                    if process.poll() is not None:
                        raise RuntimeError(f'Dice PVE server exited; inspect {directory}')
                    try:
                        if request(base, '/health/ready')['ready']:
                            break
                    except (OSError, urllib.error.URLError):
                        pass
                    if time.monotonic() > deadline:
                        raise RuntimeError(f'Dice PVE startup timeout; inspect {directory}')
                    time.sleep(.2)
                if iteration == 0:
                    login = request(base, '/hkrpg_cn/mdk/shield/api/login', {'account': 'cn-46-dice-pve', 'password': 'local-test'})
                    assert login['retcode'] == 0
                    account = login['data']['account']
                    combo = request(base, '/hkrpg_cn/combo/granter/login/v2/login',
                                    {'data': json.dumps({'uid': account['uid'], 'token': account['token']})})
                    assert combo['retcode'] == 0
                    credentials = {'accountUid': account['uid'], 'comboToken': combo['data']['combo_token'], 'mode': 'fresh'}
                    first = probe('pve-fresh.log', credentials)
                    snapshots.append(first)
                    credentials['mode'] = 'resume'
                    snapshots.append(probe('pve-resume.log', credentials))
                else:
                    credentials['mode'] = 'restart'
                    snapshots.append(probe('pve-restart.log', credentials))
                time.sleep(2)
            finally:
                if process.poll() is None:
                    process.terminate()
                    try:
                        process.wait(timeout=15)
                    except subprocess.TimeoutExpired:
                        process.kill()
                        process.wait(timeout=5)
    for snapshot in snapshots:
        for key in ['uid', 'coins', 'dust', 'completedStages', 'missionProgress']:
            assert snapshot[key] == first[key], f'Persisted PVE state changed: {key}'
    assert all(hashlib.sha256((source / name).read_bytes()).hexdigest() == sha for name, sha in hashes.items())
    print(json.dumps({'passed': True, 'runtimeDirectory': str(directory), 'snapshots': snapshots,
        'checks': ['empty-query-does-not-invent-wins', 'unknown-stage-rejected', 'loss-not-treated-as-win',
            'unmatched-native-flag-rejected', 'wide-scalar-rejected', 'source-first-reward-actually-granted',
            'accepted-objective-completed-once', 'real-completed-stage-query-without-card-grants',
            'repeat-has-empty-reward', 'same-uid-reconnect-preserves-ledger-wallet-mission',
            'same-uid-normal-H2-restart-preserves-ledger-wallet-mission'],
        'fixture': {'stage': 1001, 'reward': 8014100, 'objective': 105400302,
            'mainAcceptanceAndObjectivePredecessorGatesRelaxed': True, 'objectiveSceneRelocated': True,
            'originalMainGates': original_gates, 'originalObjective': original_sub,
            'finishConditionUnchanged': True, 'activityWindows': [], 'sourceResourceHashes': hashes,
            'sourceResourcesModified': False, 'officialStoryMissionVerified': False,
            'clientBattleReplayVerified': False, 'crashRecoveryVerified': False},
        'clientGameplayVerified': False}, ensure_ascii=False))


if __name__ == '__main__':
    main()
