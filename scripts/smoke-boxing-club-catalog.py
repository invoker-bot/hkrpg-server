"""隔离账号通过4.6原生KCP读取第一季拳击目录；没有匹配、通关或奖励预写。"""
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
from smoke_resource_fixture import mirror_resources

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
    parser.add_argument('--output', type=Path, default=ROOT / 'build/boxing-catalog-native.json')
    args = parser.parse_args()
    jar, output = args.jar.resolve(), args.output.resolve()
    assert jar.is_relative_to(ROOT / 'build') and output.is_relative_to(ROOT / 'build')
    java = next((ROOT / '.tools').glob('jdk-21*/bin/java.exe'), None)
    assert java and jar.is_file()
    artifact = sha(jar)
    source = ROOT / 'resources/4.6'
    guarded = sorted(source.glob('ExcelOutput/*.json')) + [source / 'resource-manifest.json']
    hashes = {p.relative_to(source).as_posix(): sha(p) for p in guarded}
    runs = ROOT / 'build/smoke'
    runs.mkdir(exist_ok=True)
    directory = Path(tempfile.mkdtemp(prefix='boxing-catalog-', dir=runs))
    assert directory.resolve().is_relative_to(runs.resolve())
    fixture = directory / 'resources-fixture'
    bindings = mirror_resources(source, fixture, {})
    flags = subprocess.CREATE_NO_WINDOW if os.name == 'nt' else 0
    if bindings:
        plan = directory / 'junctions.json'
        plan.write_text(json.dumps(bindings), 'utf-8')
        helper = directory / 'prepare.ps1'
        helper.write_text("param([string]$Plan)\n$ErrorActionPreference='Stop'\nforeach ($binding in (Get-Content -LiteralPath $Plan -Raw | ConvertFrom-Json)) { New-Item -ItemType Junction -Path $binding.target -Value $binding.source | Out-Null }\n", 'utf-8')
        subprocess.run(['powershell', '-NoProfile', '-NonInteractive', '-File', str(helper), '-Plan', str(plan)], check=True, capture_output=True, creationflags=flags)
    (fixture / 'resource-manifest.json').write_text(json.dumps({'clientVersion': '4.6.0', 'testFixture': True}), 'utf-8')
    http, mongo, game = port(), port(), port(socket.SOCK_DGRAM)
    config = directory / 'config.json'
    config.write_text(json.dumps({'protocol': {'wireVersion': '4.6.0'}, 'resourceDir': str(fixture), 'httpServer': {'bindAddress': '127.0.0.1', 'bindPort': http}, 'gameServer': {'bindAddress': '127.0.0.1', 'bindPort': game}, 'internalMongoServer': {'address': '127.0.0.1', 'port': mongo, 'filePath': './data/database.mv'}}), 'utf-8')
    config_bytes = config.read_bytes()
    classes = directory / 'probe-classes'
    classes.mkdir()
    probe = ROOT / 'scripts/fixtures/java/org/hkrpg/server/protocol/NativeBoxingClubCatalogProbe.java'
    child = subprocess.run([str(java.with_name('javac.exe')), '-encoding', 'UTF-8', '-cp', str(jar), '-d', str(classes), str(probe)], capture_output=True, creationflags=flags)
    (directory / 'javac.log').write_bytes(child.stdout + child.stderr)
    assert child.returncode == 0, directory / 'javac.log'
    java_flags = ['-Dfile.encoding=UTF-8', '-Dstdout.encoding=UTF-8', '-Dstderr.encoding=UTF-8', f'-Djdk.net.unixdomain.tmpdir={directory / "tcp-fallback"}']

    def request(route, body=None):
        req = urllib.request.Request(f'http://127.0.0.1:{http}' + route, data=None if body is None else json.dumps(body).encode(), headers={'Content-Type': 'application/json'})
        with urllib.request.urlopen(req, timeout=5) as reply:
            return json.load(reply)

    credentials, snapshots = {}, []
    stops = 0
    for index in range(2):
        with (directory / f'server-{index}.log').open('wb') as log:
            process = subprocess.Popen([str(java), *java_flags, '-Xmx1536m', '-jar', str(jar), '--no-handbook'], cwd=directory, stdin=subprocess.PIPE, stdout=log, stderr=subprocess.STDOUT, creationflags=flags)
            try:
                deadline = time.monotonic() + 100
                while True:
                    assert process.poll() is None, directory / f'server-{index}.log'
                    try:
                        if request('/health/ready')['ready']:
                            break
                    except (OSError, urllib.error.URLError):
                        pass
                    if time.monotonic() > deadline:
                        raise TimeoutError(directory / f'server-{index}.log')
                    time.sleep(.2)
                if not credentials:
                    login = request('/hkrpg_cn/mdk/shield/api/login', {'account': 'boxing-catalog-native', 'password': 'local-test'})
                    assert login['retcode'] == 0
                    account = login['data']['account']
                    combo = request('/hkrpg_cn/combo/granter/login/v2/login', {'data': json.dumps({'uid': account['uid'], 'token': account['token']})})
                    assert combo['retcode'] == 0
                    credentials.update(accountUid=account['uid'], comboToken=combo['data']['combo_token'])
                assert sha(jar) == artifact
                probe_log = directory / f'probe-{index}.log'
                child = subprocess.run([str(java), *java_flags, '-cp', str(classes) + os.pathsep + str(jar), 'org.hkrpg.server.protocol.NativeBoxingClubCatalogProbe', str(game)], cwd=directory, input=(json.dumps(credentials) + '\n').encode(), capture_output=True, timeout=100, creationflags=flags)
                probe_log.write_bytes(child.stdout + child.stderr)
                assert child.returncode == 0, probe_log
                snapshot = json.loads(next(line for line in reversed(probe_log.read_text('utf-8').splitlines()) if line.startswith('{"')))
                assert snapshot['passed'] and snapshot['originalFirstSeasonLevels'] == 5 and snapshot['nativeRequests'] == 7
                assert not snapshot['battleOrCompletionPrewritten'] and not snapshot['battleGameplayVerified']
                if snapshots:
                    assert snapshot['uid'] == snapshots[0]['uid']
                snapshots.append(snapshot)
            finally:
                if process.poll() is None:
                    try:
                        process.stdin.write(b'stop\n')
                        process.stdin.flush()
                        process.wait(timeout=45)
                        assert process.returncode == (1000 if os.name == 'nt' else 232), directory
                        stops += 1
                    except BaseException:
                        if process.poll() is None:
                            process.terminate()
                            process.wait(timeout=15)
                        raise
                    finally:
                        process.stdin.close()
    assert stops == 2 and config.read_bytes() == config_bytes
    assert all(sha(source / path) == value for path, value in hashes.items()) and sha(jar) == artifact
    report = dict(passed=True, artifactSha256=artifact, runtimeDirectory=str(directory), snapshots=snapshots, normalStops=stops, sameUIDNormalH2ProcessRestarts=1, nativeRequests=14, sourceResourceHashes=hashes, sourceResourcesModified=False, battleOrCompletionPrewritten=False, clientGameplayVerified=False, completeGameplayVerified=False)
    (directory / 'result.json').write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n', 'utf-8')
    output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n', 'utf-8')
    print(json.dumps(dict(passed=True, artifactSha256=artifact, runtimeDirectory=str(directory), nativeRequests=14, normalStops=stops, sameUIDNormalH2ProcessRestarts=1, sourceResourcesGuarded=len(hashes))))


if __name__ == '__main__':
    main()
