"""原始唯一物品分解/返还/100活跃度，重连与正常H2重启；库存和前置为隔离夹具。"""
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


def main():
    source = ROOT / 'resources/4.6'
    artifact = ROOT / 'build/libs/hkrpg-server.jar'
    java = next((ROOT / '.tools').glob('jdk-21*/bin/java.exe'), None)
    java = str(java) if java else 'java'
    assert artifact.is_file(), '先运行 scripts/build.ps1'
    jar_sha = hashlib.sha256(artifact.read_bytes()).hexdigest()
    runs = ROOT / 'build/smoke'
    runs.mkdir(parents=True, exist_ok=True)
    directory = Path(tempfile.mkdtemp(prefix='item-sale-', dir=runs))
    fixture = directory / 'resources-fixture'
    excel = fixture / 'ExcelOutput'
    excel.mkdir(parents=True)
    guarded = ['resource-manifest.json', 'ExcelOutput/DailyActiveQuestPool.json', 'ExcelOutput/QuestData.json',
               'ExcelOutput/FinishWay.json', 'ExcelOutput/RewardData.json', 'ExcelOutput/ItemConfig.json',
               'ExcelOutput/ItemConfigRelic.json', 'ExcelOutput/ItemConfigEquipment.json',
               'ExcelOutput/RelicConfig.json', 'ExcelOutput/EquipmentConfig.json', 'ExcelOutput/ConstValueCommon.json']
    hashes = {name: hashlib.sha256((source / name).read_bytes()).hexdigest() for name in guarded}
    for origin in (source / 'ExcelOutput').iterdir():
        target = excel / origin.name
        if origin.name == 'DailyActiveQuestPool.json':
            rows = [r for r in json.loads(origin.read_text('utf-8')) if r['QuestID'] == 2100109]
            assert len(rows) == 1
            target.write_text(json.dumps(rows), 'utf-8')
        elif origin.name == 'QuestData.json':
            rows = json.loads(origin.read_text('utf-8'))
            row = next(r for r in rows if r['QuestID'] == 2100109)
            assert row['UnlockType'] == 'FinishMission' and row['UnlockParamList'] == [4010125]
            row.update(UnlockType='AutoUnlock', UnlockParamList=[])
            target.write_text(json.dumps(rows, ensure_ascii=False), 'utf-8')
        else:
            os.link(origin, target)
    config = fixture / 'Config'
    flags = subprocess.CREATE_NO_WINDOW if os.name == 'nt' else 0
    if os.name == 'nt':
        assert config.resolve().is_relative_to(fixture.resolve())
        plan = directory / 'junction-plan.json'
        plan.write_text(json.dumps({'target': str(config), 'source': str(source / 'Config')}), 'utf-8')
        helper = directory / 'prepare-fixture.ps1'
        helper.write_text("param([string]$Plan)\n$ErrorActionPreference = 'Stop'\n"
            "$binding = Get-Content -LiteralPath $Plan -Raw | ConvertFrom-Json\n"
            "New-Item -ItemType Junction -Path $binding.target -Value $binding.source | Out-Null\n", 'utf-8')
        subprocess.run(['powershell', '-NoProfile', '-NonInteractive', '-File', str(helper), '-Plan', str(plan)],
                       check=True, capture_output=True, creationflags=flags)
    else:
        config.symlink_to(source / 'Config', target_is_directory=True)
    (fixture / 'resource-manifest.json').write_text(json.dumps({'clientVersion': '4.6.0', 'testFixture': True,
        'dailyQuest': 2100109, 'dailyGateRelaxed': 4010125}), 'utf-8')
    http_port, mongo_port, game_port = free_port(), free_port(), free_port(socket.SOCK_DGRAM)
    (directory / 'config.json').write_text(json.dumps({'protocol': {'wireVersion': '4.6.0'}, 'resourceDir': str(fixture),
        'serverOptions': {'welcomeMail': {'title': 'Decomposition stock fixture', 'sender': 'Isolated test',
            'content': 'Original unique item stock fixture', 'attachments': [
                {'id': 31011, 'count': 1}, {'id': 61011, 'count': 2},
                {'id': 20000, 'count': 1}]}},
        'httpServer': {'bindAddress': '127.0.0.1', 'bindPort': http_port},
        'gameServer': {'bindAddress': '127.0.0.1', 'bindPort': game_port},
        'internalMongoServer': {'address': '127.0.0.1', 'port': mongo_port, 'filePath': './data/database.mv'}}), 'utf-8')
    command = [java, '-Xmx1536m', f'-Djdk.net.unixdomain.tmpdir={directory / "tcp-fallback"}',
               '-jar', str(artifact), '--no-console', '--no-handbook']
    base = f'http://127.0.0.1:{http_port}'

    def request(route, body=None):
        req = urllib.request.Request(base + route, data=None if body is None else json.dumps(body).encode(),
                                     headers={'Content-Type': 'application/json'})
        with urllib.request.urlopen(req, timeout=5) as response:
            return json.load(response)

    def probe(name, credentials):
        assert hashlib.sha256(artifact.read_bytes()).hexdigest() == jar_sha, '检查期间运行包发生变化'
        execution = subprocess.run([java, command[2], '-cp', str(artifact),
            'org.hkrpg.server.protocol.LoopbackItemSaleProbe', str(game_port)], input=json.dumps(credentials),
            cwd=directory, capture_output=True, text=True, encoding='utf-8', errors='replace', timeout=35,
            creationflags=flags)
        (directory / name).write_text(execution.stdout + execution.stderr, 'utf-8')
        if execution.returncode:
            raise RuntimeError(f'Item sale probe failed; inspect {directory / name}')
        result = json.loads(next(line for line in execution.stdout.splitlines() if line.startswith('{"uid":')))
        assert result['passed'] and not result['clientGameplayVerified']
        return result

    snapshots = []
    credentials = None
    for iteration in range(2):
        assert hashlib.sha256(artifact.read_bytes()).hexdigest() == jar_sha, '检查期间运行包发生变化'
        with (directory / f'server-{iteration}.log').open('w', encoding='utf-8') as log:
            process = subprocess.Popen(command, cwd=directory, stdin=subprocess.DEVNULL,
                stdout=log, stderr=subprocess.STDOUT, creationflags=flags)
            try:
                deadline = time.monotonic() + 90
                while True:
                    if process.poll() is not None:
                        raise RuntimeError(f'Item sale server failed; inspect {directory}')
                    try:
                        if request('/health/ready')['ready']:
                            break
                    except (OSError, urllib.error.URLError):
                        pass
                    if time.monotonic() > deadline:
                        raise RuntimeError(f'Item sale readiness timeout; inspect {directory}')
                    time.sleep(.2)
                if iteration == 0:
                    login = request('/hkrpg_cn/mdk/shield/api/login',
                        {'account': 'cn-46-item-sale', 'password': 'local-test'})
                    assert login['retcode'] == 0
                    account = login['data']['account']
                    combo = request('/hkrpg_cn/combo/granter/login/v2/login',
                        {'data': json.dumps({'uid': account['uid'], 'token': account['token']})})
                    assert combo['retcode'] == 0
                    credentials = {'accountUid': account['uid'], 'comboToken': combo['data']['combo_token']}
                    first = probe('sale-first.log', credentials)
                    assert all(first[k] for k in ['nativeOriginalDailySelected', 'nativeActualUniqueStock',
                        'nativeDuplicateUniqueIdNoDebit', 'nativeWrongOneofNoDebit',
                        'nativeActualLightConeDebitAndReturn', 'nativeLightConeDoesNotCompleteRelicDaily',
                        'nativeActualRemainsDebitAndReturn', 'nativeActualMaterialDebitAndReturn',
                        'nativeActualRelicCompletesDaily', 'nativeRepeatedSaleNoExtraReturn',
                        'nativeHundredVirtualPointsOnce', 'nativeDuplicateClaimRejected'])
                    snapshots.append(first)
                    credentials.update(resume=True)
                restored = probe(f'sale-resume-{iteration}.log', credentials)
                assert restored['savedStateRestored'] and restored['nativeSavedWrongOneofNoDebit']
                assert restored['nativeSavedDuplicateClaimRejected'] and restored['uid'] == first['uid']
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
        assert snapshot['quest'] == 2100109 and snapshot['dailyProgress'] == 1
        assert snapshot['dailyQuestStatus'] == 3 and snapshot['dailyPoints'] == 100
        assert snapshot['remainingRelics'] == 1 and snapshot['remainingLightCones'] == 0
        assert snapshot['remains'] == 10 and snapshot['relicMaterial'] == 3 and snapshot['coneMaterial'] == 1
    assert hashes == {name: hashlib.sha256((source / name).read_bytes()).hexdigest() for name in guarded}
    assert hashlib.sha256(artifact.read_bytes()).hexdigest() == jar_sha
    print(json.dumps({'passed': True, 'runtimeDirectory': str(directory), 'snapshots': snapshots,
        'checks': ['original-relic-daily-selected', 'actual-unique-stock', 'duplicate-uid-no-debit',
            'wrong-oneof-no-debit', 'light-cone-actual-debit-return', 'light-cone-does-not-credit-relic-daily',
            'five-star-relic-actual-remains-return', 'five-star-relic-actual-material-return',
            'actual-relic-credits-daily', 'repeated-sale-no-extra-return', 'original-100-virtual-points-once',
            'duplicate-daily-claim-rejected', 'reconnect-restores-debit-return-daily',
            'h2-restart-restores-debit-return-daily', 'saved-wrong-oneof-no-debit', 'saved-duplicate-claim-rejected'],
        'rootJarSHA256': jar_sha, 'sourceSha256': hashes, 'sourceResourcesModified': False,
        'fixture': {'dailyPoolIsolated': True, 'dailyGateRelaxed': 4010125, 'initialUniqueStockViaWelcomeMail': True,
            'originalItemRuleAndRewardUnmodified': True}, 'officialStoryUnlockVerified': False,
        'leveledItemReturnVerified': False, 'clientGameplayVerified': False, 'crashAtomicityVerified': False}, ensure_ascii=False))


if __name__ == '__main__':
    main()
