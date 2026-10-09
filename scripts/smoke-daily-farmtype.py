"""原始金/赤日常与实际花萼波次、体力、200活跃度；前置/池选择为隔离夹具。"""
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


def run_case(java, artifact, source, runs, quest):
    directory = Path(tempfile.mkdtemp(prefix=f'daily-farmtype-{quest}-', dir=runs))
    fixture = directory / 'resources-fixture'
    excel = fixture / 'ExcelOutput'
    excel.mkdir(parents=True)
    gate = 4010121 if quest == 2100157 else 4010123
    guarded = ['resource-manifest.json', 'ExcelOutput/DailyActiveQuestPool.json', 'ExcelOutput/QuestData.json',
               'ExcelOutput/FinishWay.json', 'ExcelOutput/RewardData.json', 'ExcelOutput/ItemConfig.json',
               'ExcelOutput/CocoonConfig.json', 'ExcelOutput/StageConfig.json']
    before = {name: hashlib.sha256((source / name).read_bytes()).hexdigest() for name in guarded}
    for origin in (source / 'ExcelOutput').iterdir():
        target = excel / origin.name
        if origin.name == 'DailyActiveQuestPool.json':
            rows = [r for r in json.loads(origin.read_text('utf-8')) if r['QuestID'] == quest]
            assert rows == [{'QuestID': quest, 'Type': 10}]
            target.write_text(json.dumps(rows), 'utf-8')
        elif origin.name == 'QuestData.json':
            rows = json.loads(origin.read_text('utf-8'))
            row = next(r for r in rows if r['QuestID'] == quest)
            assert row['UnlockType'] == 'FinishMission' and row['UnlockParamList'] == [gate]
            row.update(UnlockType='AutoUnlock', UnlockParamList=[])
            target.write_text(json.dumps(rows, ensure_ascii=False), 'utf-8')
        else:
            os.link(origin, target)
    config = fixture / 'Config'
    if os.name == 'nt':
        assert config.resolve().is_relative_to(fixture.resolve())
        plan = directory / 'junction-plan.json'
        plan.write_text(json.dumps({'target': str(config), 'source': str(source / 'Config')}), 'utf-8')
        helper = directory / 'prepare-fixture.ps1'
        helper.write_text("param([string]$Plan)\n$ErrorActionPreference = 'Stop'\n"
            "$binding = Get-Content -LiteralPath $Plan -Raw | ConvertFrom-Json\n"
            "New-Item -ItemType Junction -Path $binding.target -Value $binding.source | Out-Null\n", 'utf-8')
        subprocess.run(['powershell', '-NoProfile', '-NonInteractive', '-File', str(helper), '-Plan', str(plan)],
                       check=True, capture_output=True, creationflags=subprocess.CREATE_NO_WINDOW)
    else:
        config.symlink_to(source / 'Config', target_is_directory=True)
    (fixture / 'resource-manifest.json').write_text(json.dumps({'clientVersion': '4.6.0', 'testFixture': True,
        'dailyQuest': quest, 'dailyGateRelaxed': gate, 'originalCocoons': [1001, 1004]}), 'utf-8')
    http_port, mongo_port, game_port = free_port(), free_port(), free_port(socket.SOCK_DGRAM)
    (directory / 'config.json').write_text(json.dumps({'protocol': {'wireVersion': '4.6.0'}, 'resourceDir': str(fixture),
        'httpServer': {'bindAddress': '127.0.0.1', 'bindPort': http_port},
        'gameServer': {'bindAddress': '127.0.0.1', 'bindPort': game_port},
        'internalMongoServer': {'address': '127.0.0.1', 'port': mongo_port, 'filePath': './data/database.mv'}}), 'utf-8')
    command = [java, '-Xmx1536m', f'-Djdk.net.unixdomain.tmpdir={directory / "tcp-fallback"}',
               '-jar', str(artifact), '--no-console', '--no-handbook']
    base = f'http://127.0.0.1:{http_port}'
    flags = subprocess.CREATE_NO_WINDOW if os.name == 'nt' else 0

    def probe(name, credentials):
        execution = subprocess.run([java, command[2], '-cp', str(artifact),
            'org.hkrpg.server.protocol.LoopbackDailyFarmTypeProbe', str(game_port)], input=json.dumps(credentials),
            cwd=directory, capture_output=True, text=True, encoding='utf-8', errors='replace', timeout=35,
            creationflags=flags)
        (directory / name).write_text(execution.stdout + execution.stderr, 'utf-8')
        if execution.returncode:
            raise RuntimeError(f'Farm daily probe failed; inspect {directory / name}')
        result = json.loads(next(line for line in execution.stdout.splitlines() if line.startswith('{"uid":')))
        assert result['passed'] and not result['clientGameplayVerified']
        return result

    snapshots, credentials = [], None
    for iteration in range(2):
        with (directory / f'server-{iteration}.log').open('w', encoding='utf-8') as log:
            process = subprocess.Popen(command, cwd=directory, stdin=subprocess.DEVNULL,
                stdout=log, stderr=subprocess.STDOUT, creationflags=flags)
            try:
                deadline = time.monotonic() + 90
                while True:
                    if process.poll() is not None:
                        raise RuntimeError(f'Farm daily server failed; inspect {directory}')
                    try:
                        if request(base, '/health/ready')['ready']:
                            break
                    except (OSError, urllib.error.URLError):
                        pass
                    if time.monotonic() > deadline:
                        raise RuntimeError(f'Farm daily server timeout; inspect {directory}')
                    time.sleep(.2)
                if iteration == 0:
                    login = request(base, '/hkrpg_cn/mdk/shield/api/login',
                        {'account': f'cn-46-daily-farmtype-{quest}', 'password': 'local-test'})
                    assert login['retcode'] == 0
                    account = login['data']['account']
                    combo = request(base, '/hkrpg_cn/combo/granter/login/v2/login',
                        {'data': json.dumps({'uid': account['uid'], 'token': account['token']})})
                    assert combo['retcode'] == 0
                    credentials = {'accountUid': account['uid'], 'comboToken': combo['data']['combo_token'], 'quest': quest}
                    first = probe('farm-daily-first.log', credentials)
                    assert all(first[k] for k in ['nativeOriginalFarmDailySelected', 'nativeActualOtherColorStarted',
                        'nativeOtherColorStayedPending', 'nativeActualTwoWaveTargetStarted', 'nativeWrongIdentityRejected',
                        'nativeRepeatedReportRejected', 'nativeActualFarmVictoryCompletedDaily',
                        'nativeOriginalTwoHundredPoints', 'nativeVirtualPointsWithoutInventoryItem',
                        'nativeDuplicateRewardRejected', 'nativeActualThirtyStaminaDebit'])
                    snapshots.append(first)
                    credentials.update(resume=True, initialStamina=first['initialStamina'])
                restored = probe(f'farm-daily-resume-{iteration}.log', credentials)
                assert restored['savedFarmDailyRestored'] and restored['savedActualStaminaRestored']
                assert restored['nativeSavedDuplicateRewardRejected']
                assert restored['uid'] == first['uid']
                snapshots.append(restored)
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
        assert snapshot['quest'] == quest and snapshot['cocoon'] == (1001 if quest == 2100157 else 1004)
        assert snapshot['dailyPoints'] == 200 and snapshot['dailyProgress'] == 1 and snapshot['dailyQuestStatus'] == 3
        assert snapshot['remainingStamina'] == snapshot['initialStamina'] - 30
    for name, digest in before.items():
        assert hashlib.sha256((source / name).read_bytes()).hexdigest() == digest
    return {'passed': True, 'quest': quest, 'runtimeDirectory': str(directory), 'snapshots': snapshots,
        'sourceSha256': before, 'fixture': {'dailyPoolIsolated': True, 'dailyGateRelaxed': gate,
            'cocoons': [1001, 1004], 'worldLevel': 0, 'wavesPerBattle': [1, 2], 'actualStaminaCost': 30,
            'originalSourceWavesUnmodified': True, 'originalQuestRuleRewardUnmodified': True},
        'sourceResourcesModified': False, 'officialStoryUnlockVerified': False, 'clientGameplayVerified': False}


def main():
    java = next((ROOT / '.tools').glob('jdk-21*/bin/java.exe'), None)
    java = str(java) if java else 'java'
    artifact = ROOT / 'build/libs/hkrpg-server.jar'
    assert artifact.is_file(), '先运行 scripts/build.ps1'
    jar_sha = hashlib.sha256(artifact.read_bytes()).hexdigest()
    runs = ROOT / 'build/smoke'
    runs.mkdir(parents=True, exist_ok=True)
    cases = [run_case(java, artifact, ROOT / 'resources/4.6', runs, quest) for quest in [2100157, 2100158]]
    assert hashlib.sha256(artifact.read_bytes()).hexdigest() == jar_sha, '检查期间运行包发生变化'
    print(json.dumps({'passed': True, 'cases': cases, 'checks': [
        'original-golden-daily-selected', 'original-crimson-daily-selected', 'actual-other-color-starts',
        'other-color-does-not-credit', 'actual-two-source-waves-start', 'wrong-battle-identity-rejected',
        'repeated-battle-report-rejected', 'actual-win-completes-original-daily', 'original-200-points-once',
        'virtual-points-stay-out-of-inventory', 'duplicate-reward-rejected', 'actual-30-stamina-debited',
        'reconnect-restores-daily-and-stamina', 'h2-process-restart-restores-daily-and-stamina'],
        'rootJarSHA256': jar_sha, 'sourceResourcesModified': False, 'clientGameplayVerified': False,
        'officialStoryUnlockVerified': False, 'networkDayRolloverVerified': False, 'crashAtomicityVerified': False},
        ensure_ascii=False))


if __name__ == '__main__':
    main()
