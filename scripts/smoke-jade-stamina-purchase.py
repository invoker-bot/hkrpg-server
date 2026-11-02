"""隔离账号/H2验证八档星琼兑换、实际扣费到账、回执重发及每日次数恢复；不操作正式玩家。"""
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
    return hashlib.sha256(path.read_bytes()).hexdigest()


def port(kind=socket.SOCK_STREAM):
    with socket.socket(type=kind) as sock:
        sock.bind(('127.0.0.1', 0))
        return sock.getsockname()[1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--jar', type=Path, default=ROOT / 'build/libs/hkrpg-server.jar')
    parser.add_argument('--java', type=Path)
    parser.add_argument('--resources', type=Path, default=ROOT / 'resources/4.6')
    args = parser.parse_args()
    jar = args.jar.resolve(strict=True)
    java = (args.java or next((ROOT / '.tools').glob('jdk-21*/bin/java.exe'))).resolve(strict=True)
    resources = args.resources.resolve(strict=True)
    helpers = [ROOT / 'scripts/fixtures/java/org/hkrpg/server/protocol' / name for name in ('NativeJadeStaminaPurchaseProbe.java', 'JadeStaminaPurchaseFixture.java')]
    artifact = sha(jar)
    source_hashes = {str(p.relative_to(ROOT)): sha(p) for p in [Path(__file__), *helpers]}
    runs = ROOT / 'build/smoke'
    runs.mkdir(parents=True, exist_ok=True)
    directory = Path(tempfile.mkdtemp(prefix='jade-stamina-', dir=runs))
    http, mongo, game = port(), port(), port(socket.SOCK_DGRAM)
    while mongo == http:
        mongo = port()
    config = directory / 'config.json'
    config.write_text(json.dumps({'protocol': {'wireVersion': '4.6.0'},
        'resourceDir': str(resources),
        'httpServer': {'bindAddress': '127.0.0.1', 'bindPort': http},
        'gameServer': {'bindAddress': '127.0.0.1', 'bindPort': game},
        'internalMongoServer': {'address': '127.0.0.1', 'port': mongo,
                                'filePath': './data/database.mv'}}), 'utf-8')
    config_bytes = config.read_bytes()
    classes = directory / 'probe-classes'
    classes.mkdir()
    flags = subprocess.CREATE_NO_WINDOW if os.name == 'nt' else 0
    compiled = subprocess.run([str(java.with_name('javac.exe')), '-encoding', 'UTF-8',
        '-cp', str(jar), '-d', str(classes), *map(str, helpers)], capture_output=True, creationflags=flags)
    (directory / 'javac.log').write_bytes(compiled.stdout + compiled.stderr)
    assert compiled.returncode == 0, directory / 'javac.log'
    options = ['-Dfile.encoding=UTF-8', '-Dio.netty.eventLoopThreads=2',
               f'-Djdk.net.unixdomain.tmpdir={directory / "tcp-fallback"}']
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))

    def request(route, body=None):
        req = urllib.request.Request(f'http://127.0.0.1:{http}' + route,
            data=None if body is None else json.dumps(body).encode(),
            headers={'Content-Type': 'application/json'})
        with opener.open(req, timeout=5) as response:
            return json.load(response)

    credentials, snapshots, stops = {}, [], 0
    fixture_reads = []
    def fixture(mode, uid):
        result = subprocess.run([str(java), *options, '-cp', str(classes) + os.pathsep + str(jar),
            'org.hkrpg.server.protocol.JadeStaminaPurchaseFixture', str(directory), mode, str(uid)],
            cwd=directory, capture_output=True, timeout=30, creationflags=flags)
        (directory / ('fixture-' + mode + '.log')).write_bytes(result.stdout + result.stderr)
        assert result.returncode == 0, directory / ('fixture-' + mode + '.log')
        row = json.loads(next(line for line in reversed(result.stdout.decode('utf-8').splitlines()) if line.startswith('{')))
        fixture_reads.append(dict(mode=mode, row=row))

    for iteration in range(3):
        with (directory / f'server-{iteration}.log').open('wb') as log:
            server = subprocess.Popen([str(java), *options, '-Xmx1536m', '-jar', str(jar),
                '--no-handbook'], cwd=directory, stdin=subprocess.PIPE, stdout=log,
                stderr=subprocess.STDOUT, creationflags=flags)
            try:
                deadline = time.monotonic() + 100
                while True:
                    assert server.poll() is None, directory / f'server-{iteration}.log'
                    try:
                        if request('/health/ready')['ready']:
                            break
                    except (OSError, urllib.error.URLError):
                        pass
                    if time.monotonic() > deadline:
                        raise TimeoutError(directory)
                    time.sleep(.2)
                if not credentials:
                    login = request('/hkrpg_cn/mdk/shield/api/login',
                                    {'account': 'jade-stamina-native', 'password': 'local-test'})
                    assert login['retcode'] == 0
                    account = login['data']['account']
                    combo = request('/hkrpg_cn/combo/granter/login/v2/login',
                        {'data': json.dumps({'uid': account['uid'], 'token': account['token']})})
                    assert combo['retcode'] == 0
                    credentials.update(accountUid=account['uid'], comboToken=combo['data']['combo_token'])
                if iteration == 1:
                    fixture('seed', snapshots[0]['uid'])
                for mode in [['initialize', 'purchases', 'restart'][iteration]]:
                    assert sha(jar) == artifact
                    tested = subprocess.run([str(java), *options, '-cp',
                        str(classes) + os.pathsep + str(jar),
                        'org.hkrpg.server.protocol.NativeJadeStaminaPurchaseProbe', str(game)], cwd=directory,
                        input=(json.dumps(dict(credentials, mode=mode)) + '\n').encode(),
                        capture_output=True, timeout=100, creationflags=flags)
                    (directory / f'probe-{mode}.log').write_bytes(tested.stdout + tested.stderr)
                    assert tested.returncode == 0, directory / f'probe-{mode}.log'
                    snapshot = json.loads(next(line for line in reversed(tested.stdout.decode('utf-8').splitlines())
                                              if line.startswith('{"')))
                    assert snapshot['passed'] and snapshot['correlationHeadersVerified']
                    if snapshots:
                        assert snapshot['uid'] == snapshots[0]['uid']
                    snapshots.append(snapshot)
                if iteration == 2:
                    fixture('verify', snapshots[0]['uid'])
            finally:
                if server.poll() is None:
                    try:
                        server.stdin.write(b'stop\n')
                        server.stdin.flush()
                        server.wait(timeout=45)
                        assert server.returncode == (1000 if os.name == 'nt' else 232), directory
                        stops += 1
                    finally:
                        if server.poll() is None:
                            server.terminate()
                            server.wait(timeout=15)
                        server.stdin.close()
    assert stops == 3 and config.read_bytes() == config_bytes
    assert all(sha(ROOT / path) == digest for path, digest in source_hashes.items())
    for iteration in range(3):
        # JVM startup diagnostics may use the Windows native encoding before Java starts.
        log = (directory / f'server-{iteration}.log').read_bytes()
        assert b'Unhandled 4.6.0 inbound command: 45' not in log
    result = dict(passed=True, artifactSha256=artifact, runtimeDirectory=str(directory),
        snapshots=snapshots, normalStops=stops, sameUIDNormalH2ProcessRestarts=2,
        nativeRequests=sum(s['nativeRequests'] for s in snapshots),
        successfulPurchases=sum(s['successfulPurchases'] for s in snapshots),
        jadeSpent=sum(s['jadeSpent'] for s in snapshots), powerAdded=sum(s['powerAdded'] for s in snapshots),
        isolatedFixtureReads=fixture_reads, sourceInputHashes=source_hashes, clientUIVerified=False)
    (directory / 'result.json').write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n', 'utf-8')
    (ROOT / 'build/jade-stamina-purchase-network.json').write_text(
        json.dumps(result, ensure_ascii=False, indent=2) + '\n', 'utf-8')
    print(json.dumps(result, ensure_ascii=False))


if __name__ == '__main__':
    main()
