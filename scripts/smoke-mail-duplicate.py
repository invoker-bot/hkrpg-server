"""隔离账号/H2复现欢迎邮件重复角色，核对实际奖励、领取响应及正常重启防重。"""
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
    helpers = [ROOT / 'scripts/fixtures/java/org/hkrpg/server/protocol' / name
               for name in ('NativeMailDuplicateProbe.java', 'MailDuplicateFixture.java')]
    artifact = sha(jar)
    source_hashes = {str(p.relative_to(ROOT)): sha(p) for p in [Path(__file__), *helpers]}
    guarded = [resources / name for name in ('resource-manifest.json', 'ExcelOutput/ItemConfig.json',
        'ExcelOutput/ItemConfigAvatar.json', 'ExcelOutput/ItemConfigAvatarRank.json', 'ExcelOutput/AvatarConfig.json')]
    resource_hashes = {str(p.relative_to(resources)): sha(p) for p in guarded}
    runs = ROOT / 'build/smoke'
    runs.mkdir(parents=True, exist_ok=True)
    directory = Path(tempfile.mkdtemp(prefix='mail-duplicate-', dir=runs))
    http, mongo, game = port(), port(), port(socket.SOCK_DGRAM)
    while mongo == http or mongo == 27079:
        mongo = port()
    config = directory / 'config.json'
    config.write_text(json.dumps({'protocol': {'wireVersion': '4.6.0'},
        'resourceDir': str(resources),
        'httpServer': {'bindAddress': '127.0.0.1', 'bindPort': http},
        'gameServer': {'bindAddress': '127.0.0.1', 'bindPort': game},
        'internalMongoServer': {'address': '127.0.0.1', 'port': mongo,
                                'filePath': './data/database.mv'}}), 'utf-8')
    config_bytes = config.read_bytes()
    (directory / 'mail-duplicate.marker').write_text('isolated fixture\n', 'utf-8')
    classes = directory / 'probe-classes'
    classes.mkdir()
    flags = subprocess.CREATE_NO_WINDOW if os.name == 'nt' else 0
    compiled = subprocess.run([str(java.with_name('javac.exe' if os.name == 'nt' else 'javac')),
        '-encoding', 'UTF-8', '-cp', str(jar), '-d', str(classes), *map(str, helpers)],
        capture_output=True, creationflags=flags)
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

    credentials, snapshots, stops, inspections = {}, [], 0, []

    def inspect(mode, uid):
        result = subprocess.run([str(java), *options, '-cp', str(classes) + os.pathsep + str(jar),
            'org.hkrpg.server.protocol.MailDuplicateFixture', str(directory), mode, str(uid)],
            cwd=directory, capture_output=True, timeout=45, creationflags=flags)
        (directory / f'fixture-{len(inspections)}-{mode}.log').write_bytes(result.stdout + result.stderr)
        assert result.returncode == 0, directory
        row = json.loads(next(line for line in reversed(result.stdout.decode('utf-8').splitlines())
                              if line.startswith('{"')))
        assert row['passed']
        if inspections:
            assert row['mailObjectId'] == inspections[0]['mailObjectId']
            assert row['avatarObjectIds'] == inspections[0]['avatarObjectIds']
        inspections.append(row)

    for iteration, mode in enumerate(('initialize', 'claim', 'restart')):
        marker = directory / 'server-running.marker'
        marker.write_text('running\n', 'utf-8')
        with (directory / f'server-{iteration}.log').open('wb') as log:
            server = subprocess.Popen([str(java), *options, '-Xmx1536m', '-jar', str(jar), '--no-handbook'],
                cwd=directory, stdin=subprocess.PIPE, stdout=log, stderr=subprocess.STDOUT, creationflags=flags)
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
                                    {'account': 'mail-duplicate-native', 'password': 'local-test'})
                    assert login['retcode'] == 0
                    account = login['data']['account']
                    combo = request('/hkrpg_cn/combo/granter/login/v2/login',
                        {'data': json.dumps({'uid': account['uid'], 'token': account['token']})})
                    assert combo['retcode'] == 0
                    credentials.update(accountUid=account['uid'], comboToken=combo['data']['combo_token'])
                assert sha(jar) == artifact
                tested = subprocess.run([str(java), *options, '-cp', str(classes) + os.pathsep + str(jar),
                    'org.hkrpg.server.protocol.NativeMailDuplicateProbe', str(game)], cwd=directory,
                    input=(json.dumps(dict(credentials, mode=mode)) + '\n').encode(),
                    capture_output=True, timeout=70, creationflags=flags)
                (directory / f'probe-{mode}.log').write_bytes(tested.stdout + tested.stderr)
                assert tested.returncode == 0, directory / f'probe-{mode}.log'
                snapshot = json.loads(next(line for line in reversed(tested.stdout.decode('utf-8').splitlines())
                                          if line.startswith('{"')))
                assert snapshot['passed']
                if snapshots:
                    assert snapshot['uid'] == snapshots[0]['uid']
                snapshots.append(snapshot)
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
                if server.poll() is not None:
                    marker.unlink()
        inspect('seed' if iteration == 0 else 'verify', snapshots[0]['uid'])
    assert stops == 3 and config.read_bytes() == config_bytes
    assert all(sha(ROOT / path) == digest for path, digest in source_hashes.items())
    assert all(sha(resources / path) == digest for path, digest in resource_hashes.items())
    result = dict(passed=True, artifactSha256=artifact, runtimeDirectory=str(directory), snapshots=snapshots,
        normalStops=stops, sameUIDNormalH2ProcessRestarts=2,
        nativeRequests=sum(s['nativeRequests'] for s in snapshots), inspections=inspections,
        sourceInputHashes=source_hashes, resourceHashes=resource_hashes, clientUIVerified=False)
    (directory / 'result.json').write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n', 'utf-8')
    (ROOT / 'build/mail-duplicate-network.json').write_text(
        json.dumps(result, ensure_ascii=False, indent=2) + '\n', 'utf-8')
    print(json.dumps(result, ensure_ascii=False))


if __name__ == '__main__':
    main()
