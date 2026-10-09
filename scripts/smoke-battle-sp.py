"""原始305001事件的构造能量报告，经4.6 KCP结算、两UID隔离、重连和H2重启核验。"""
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

ROOT = Path(__file__).resolve().parents[1]


def sha(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


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
    parser = argparse.ArgumentParser()
    parser.add_argument('--resources', type=Path, default=ROOT / 'resources/4.6')
    args = parser.parse_args()
    source = args.resources.resolve(strict=True)
    java = next((ROOT / '.tools').glob('jdk-21*/bin/java.exe'), None)
    java = str(java) if java else 'java'
    artifact = ROOT / 'build/libs/hkrpg-server.jar'
    assert artifact.is_file(), '先运行 scripts/build.ps1'
    guarded = ['resource-manifest.json', 'ExcelOutput/PlaneEvent.json', 'ExcelOutput/StageConfig.json',
               'ExcelOutput/MonsterConfig.json', 'ExcelOutput/AvatarConfig.json']
    before = {name: sha(source / name) for name in guarded}
    original_config = sha(ROOT / 'config.json') if (ROOT / 'config.json').is_file() else None
    actor = next(row for row in json.loads((source / guarded[4]).read_text('utf-8')) if row['AvatarID'] == 8001)
    assert actor['SPNeed']['Value'] == 120
    runs = ROOT / 'build/smoke'
    runs.mkdir(parents=True, exist_ok=True)
    directory = Path(tempfile.mkdtemp(prefix='battle-sp-', dir=runs))
    assert directory.resolve().is_relative_to(runs.resolve())
    http_port, mongo_port, game_port = free_port(), free_port(), free_port(socket.SOCK_DGRAM)
    (directory / 'config.json').write_text(json.dumps({'protocol': {'wireVersion': '4.6.0'}, 'resourceDir': str(source),
        'httpServer': {'bindAddress': '127.0.0.1', 'bindPort': http_port},
        'gameServer': {'bindAddress': '127.0.0.1', 'bindPort': game_port},
        'internalMongoServer': {'address': '127.0.0.1', 'port': mongo_port, 'filePath': './data/database.mv'}}), encoding='utf-8')
    fallback = f'-Djdk.net.unixdomain.tmpdir={directory / "tcp-fallback"}'
    command = [java, '-Xmx1536m', fallback, '-jar', str(artifact), '--no-console', '--no-handbook']
    base = f'http://127.0.0.1:{http_port}'
    snapshots, credentials = [], []
    hidden = subprocess.CREATE_NO_WINDOW if os.name == 'nt' else 0

    def probe(name, identity):
        execution = subprocess.run([java, fallback, '-cp', str(artifact),
            'org.hkrpg.server.protocol.LoopbackBattleSpProbe', str(game_port)], input=json.dumps(identity),
            cwd=directory, capture_output=True, text=True, encoding='utf-8', errors='replace', timeout=35,
            creationflags=hidden)
        (directory / name).write_text(execution.stdout + execution.stderr, encoding='utf-8')
        if execution.returncode:
            raise RuntimeError(f'能量验证失败，查看 {directory / name}')
        result = json.loads(next(line for line in execution.stdout.splitlines() if line.startswith('{"uid":')))
        assert result['passed'] and result['constructedResultReports'] and not result['clientGameplayVerified']
        snapshots.append(result)
        return result

    for iteration in range(2):
        with (directory / f'server-{iteration}.log').open('w', encoding='utf-8') as log:
            process = subprocess.Popen(command, cwd=directory, stdin=subprocess.DEVNULL, stdout=log,
                                       stderr=subprocess.STDOUT, creationflags=hidden)
            try:
                deadline = time.monotonic() + 90
                while True:
                    if process.poll() is not None:
                        raise RuntimeError(f'能量验证服务器提前退出，查看 {directory}')
                    try:
                        if request(base, '/health/ready')['ready']:
                            break
                    except (OSError, urllib.error.URLError):
                        pass
                    if time.monotonic() > deadline:
                        raise RuntimeError(f'能量验证服务器超时，查看 {directory}')
                    time.sleep(.2)
                if iteration == 0:
                    for account_name in ['cn-46-battle-sp', 'cn-46-battle-sp-control']:
                        login = request(base, '/hkrpg_cn/mdk/shield/api/login', {'account': account_name, 'password': 'local-test'})
                        assert login['retcode'] == 0
                        account = login['data']['account']
                        combo = request(base, '/hkrpg_cn/combo/granter/login/v2/login',
                                        {'data': json.dumps({'uid': account['uid'], 'token': account['token']})})
                        assert combo['retcode'] == 0
                        credentials.append({'accountUid': account['uid'], 'comboToken': combo['data']['combo_token']})
                    assert credentials[0]['accountUid'] != credentials[1]['accountUid']
                    control = probe('control-initial.log', {**credentials[1], 'observeOnly': True})
                    first = probe('settlement.log', credentials[0])
                    assert first['overCapacityRejected'] and first['invalidReportPreservedBattle']
                    assert [first[f'outcome{i}Sp'] for i in range(4)] == [4208, 2500, 2500, 4208]
                    assert all(first[f'outcome{i}ReplayRejected'] for i in range(4)) and first['notificationCount'] == 3
                    assert first['uid'] != control['uid']
                    credentials[0].update(observeOnly=True, expectedSp=4208)
                    credentials[1].update(observeOnly=True, expectedSp=control['storedSp'])
                for index, identity in enumerate(credentials):
                    restored = probe(f'resume-{iteration}-{index}.log', identity)
                    assert restored['storedSp'] == identity['expectedSp']
                    assert restored['uid'] == (first if index == 0 else control)['uid']
                time.sleep(2)
            finally:
                if process.poll() is None:
                    process.terminate()
                    try:
                        process.wait(timeout=15)
                    except subprocess.TimeoutExpired:
                        process.kill()
                        process.wait(timeout=5)
    assert before == {name: sha(source / name) for name in guarded}
    assert original_config == (sha(ROOT / 'config.json') if (ROOT / 'config.json').is_file() else None)
    report = {'passed': True, 'runtimeDirectory': str(directory), 'snapshots': snapshots,
        'checks': ['original-event-stage-roster', 'fractional-sp-normalized', 'over-capacity-rejected',
                   'invalid-report-preserves-battle', 'win-loss-quit-isolation', 'three-native-sp-notifications',
                   'four-replayed-settlements-rejected', 'two-uid-isolation', 'reconnect-restores-sp',
                   'h2-process-restart-restores-sp'],
        'sourceSha256': before, 'jarSha256': sha(artifact), 'originalAvatar': 8001, 'originalAvatarSpNeed': 120,
        'constructedResultReports': True, 'sourceResourcesModified': False, 'operatorConfigModified': False,
        'clientExecutableStarted': False, 'clientGameplayVerified': False, 'completeGameplay': False}
    (directory / 'verification.json').write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    print(json.dumps(report, ensure_ascii=False))


if __name__ == '__main__':
    main()
