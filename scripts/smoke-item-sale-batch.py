"""隔离 500 件光锥批量分解，验证原生 KCP 回复、真实库存及 H2 重启。"""
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


def free_port(kind=socket.SOCK_STREAM):
    with socket.socket(type=kind) as stream:
        stream.bind(('127.0.0.1', 0))
        return stream.getsockname()[1]


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--server-jar', type=Path, default=ROOT / 'build/libs/hkrpg-server.jar')
    artifact = parser.parse_args().server_jar.resolve()
    probe_jar = ROOT / 'build/libs/hkrpg-server.jar'
    source = ROOT / 'resources/4.6'
    java = next((ROOT / '.tools').glob('jdk-21*/bin/java.exe'), None)
    java = str(java) if java else 'java'
    assert artifact.is_file() and probe_jar.is_file(), '先运行 scripts/build.ps1'
    jar_sha = hashlib.sha256(artifact.read_bytes()).hexdigest()
    guarded = ['ExcelOutput/ItemConfigEquipment.json', 'ExcelOutput/EquipmentConfig.json',
               'ExcelOutput/ItemConfig.json', 'ExcelOutput/ConstValueCommon.json']
    hashes = {name: hashlib.sha256((source / name).read_bytes()).hexdigest() for name in guarded}
    runs = ROOT / 'build/smoke'
    runs.mkdir(parents=True, exist_ok=True)
    directory = Path(tempfile.mkdtemp(prefix='item-sale-batch-', dir=runs))
    ports = set()
    while len(ports) < 2:
        ports.add(free_port())
    http_port, mongo_port = tuple(ports)
    game_port = free_port(socket.SOCK_DGRAM)
    (directory / 'config.json').write_text(json.dumps({
        'protocol': {'wireVersion': '4.6.0'}, 'resourceDir': str(source),
        'serverOptions': {'welcomeMail': {'title': 'Batch decomposition stock fixture',
            'sender': 'Isolated test', 'content': '500 unique light cones',
            'attachments': [{'id': 20000, 'count': 500}]}},
        'httpServer': {'bindAddress': '127.0.0.1', 'bindPort': http_port},
        'gameServer': {'bindAddress': '127.0.0.1', 'bindPort': game_port},
        'internalMongoServer': {'address': '127.0.0.1', 'port': mongo_port,
                                'filePath': './data/database.mv'}}), encoding='utf-8')
    fallback = f'-Djdk.net.unixdomain.tmpdir={directory / "tcp-fallback"}'
    command = [java, '-Xmx1536m', fallback, '-jar', str(artifact), '--no-console', '--no-handbook']
    flags = subprocess.CREATE_NO_WINDOW if os.name == 'nt' else 0
    base = f'http://127.0.0.1:{http_port}'

    def request(route, body=None):
        req = urllib.request.Request(base + route, data=None if body is None else json.dumps(body).encode(),
                                     headers={'Content-Type': 'application/json'})
        with urllib.request.urlopen(req, timeout=5) as response:
            return json.load(response)

    def probe(name, credentials):
        assert hashlib.sha256(artifact.read_bytes()).hexdigest() == jar_sha, '运行包发生变化'
        classpath = os.pathsep.join((str(artifact), str(probe_jar)))
        run = subprocess.run([java, fallback, '-cp', classpath,
            'org.hkrpg.server.protocol.LoopbackItemSaleBatchProbe', str(game_port)],
            input=json.dumps(credentials), cwd=directory, capture_output=True, text=True,
            encoding='utf-8', errors='replace', timeout=75, creationflags=flags)
        (directory / name).write_text(run.stdout + run.stderr, encoding='utf-8')
        if run.returncode:
            raise RuntimeError(f'批量分解网络验证失败：{directory / name}')
        result = json.loads(next(line for line in run.stdout.splitlines() if line.startswith('{"uid":')))
        assert result['passed'] and not result['clientGameplayVerified']
        return result

    credentials = None
    snapshots = []
    for iteration in range(2):
        with (directory / f'server-{iteration}.log').open('w', encoding='utf-8') as log:
            process = subprocess.Popen(command, cwd=directory, stdin=subprocess.DEVNULL,
                stdout=log, stderr=subprocess.STDOUT, creationflags=flags)
            try:
                deadline = time.monotonic() + 100
                while True:
                    if process.poll() is not None:
                        raise RuntimeError(f'隔离服务器异常退出：{directory}')
                    try:
                        if request('/health/ready')['ready']:
                            break
                    except (OSError, urllib.error.URLError):
                        pass
                    if time.monotonic() > deadline:
                        raise RuntimeError(f'隔离服务器启动超时：{directory}')
                    time.sleep(.2)
                if iteration == 0:
                    login = request('/hkrpg_cn/mdk/shield/api/login',
                                    {'account': 'item-sale-batch', 'password': 'isolated-test'})
                    assert login['retcode'] == 0
                    account = login['data']['account']
                    combo = request('/hkrpg_cn/combo/granter/login/v2/login',
                        {'data': json.dumps({'uid': account['uid'], 'token': account['token']})})
                    assert combo['retcode'] == 0
                    credentials = {'accountUid': account['uid'], 'comboToken': combo['data']['combo_token']}
                    first = probe('sale-first.log', credentials)
                    assert first['deleted'] == 500 and first['deletionUpdates'] == 1 and first['correlatedReply']
                    snapshots.append(first)
                    credentials.update(resume=True)
                restored = probe(f'sale-resume-{iteration}.log', credentials)
                assert restored['uid'] == first['uid'] and restored['restored']
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
    assert all(s['remainingLightCones'] == 0 and s['materials'] == 500 for s in snapshots)
    assert hashes == {name: hashlib.sha256((source / name).read_bytes()).hexdigest() for name in guarded}
    assert hashlib.sha256(artifact.read_bytes()).hexdigest() == jar_sha
    print(json.dumps({'passed': True, 'runtimeDirectory': str(directory), 'snapshots': snapshots,
        'rootJarSHA256': jar_sha, 'sourceSha256': hashes, 'productionAccountUsed': False,
        'sourceResourcesModified': False, 'clientGameplayVerified': False, 'crashAtomicityVerified': False},
        ensure_ascii=False))


if __name__ == '__main__':
    main()
