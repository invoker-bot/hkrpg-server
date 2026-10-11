"""Verify all-type entry queries against an isolated copy of real offering-six H2 history."""
import hashlib
import json
import os
from pathlib import Path
import shutil
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
    jar = ROOT / 'build/libs/hkrpg-server.jar'
    java = next((ROOT / '.tools').glob('jdk-21*/bin/java.exe'))
    previous = json.loads((ROOT / 'build/krater-offering-native.json').read_text('utf-8'))
    original = Path(previous['runtimeDirectory']).resolve()
    assert previous['passed'] and previous['paidMaterials'] == 18800
    assert original.is_relative_to(ROOT / 'build/smoke')
    manifest = json.loads((original / 'resources-fixture/resource-manifest.json').read_text('utf-8'))
    assert manifest['kraterOfferingFixture'] and manifest['testFixture']
    source_hashes = {str(path): sha(path) for path in (original / 'data').rglob('*') if path.is_file()}
    golden = json.loads((ROOT / 'src/test/resources/fixtures/offering-query-4.6.json').read_text('utf-8'))
    source_hashes.update({str(ROOT / 'resources/4.6' / path): sha(ROOT / 'resources/4.6' / path)
                          for path in golden['sourceFiles']})
    directory = Path(tempfile.mkdtemp(prefix='offering-query-', dir=ROOT / 'build/smoke'))
    assert directory.resolve().is_relative_to(ROOT / 'build/smoke')
    shutil.copytree(original / 'data', directory / 'data')
    resources = ROOT / 'resources/4.6'
    krater = json.loads((ROOT / 'src/test/resources/fixtures/krater-offering-4.6.json').read_text('utf-8'))
    paths = set(krater['sourceFiles']) | set(golden['sourceFiles'])
    replacements = {path: json.loads((resources / path).read_text('utf-8')) for path in paths}
    floor = 'Config/LevelOutput/RuntimeFloor/P10402_F10402001.json'
    replacements[floor] = json.loads((original / 'resources-fixture' / floor).read_text('utf-8'))
    fixture = directory / 'resources-fixture'
    bindings = mirror_resources(resources, fixture, replacements)
    flags = subprocess.CREATE_NO_WINDOW if os.name == 'nt' else 0
    if bindings:
        plan = directory / 'junctions.json'
        plan.write_text(json.dumps(bindings), 'utf-8')
        helper = directory / 'prepare.ps1'
        helper.write_text("param([string]$Plan)\n$ErrorActionPreference='Stop'\nforeach ($binding in (Get-Content -LiteralPath $Plan -Raw | ConvertFrom-Json)) { New-Item -ItemType Junction -Path $binding.target -Value $binding.source | Out-Null }\n", 'utf-8')
        subprocess.run(['powershell', '-NoProfile', '-NonInteractive', '-File', str(helper), '-Plan', str(plan)],
                       check=True, capture_output=True, creationflags=flags)
    # Mission folders are deliberately empty in mirror_resources; materialize only original required headers.
    for path in paths:
        if path.startswith('Config/Level/Mission/'):
            target = fixture / path
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes((resources / path).read_bytes())
    (fixture / 'resource-manifest.json').write_text(json.dumps(manifest), 'utf-8')
    config = json.loads((original / 'config.json').read_text('utf-8'))
    http, mongo, game = port(), port(), port(socket.SOCK_DGRAM)
    config['httpServer'].update(bindPort=http)
    config['internalMongoServer'].update(port=mongo, filePath='./data/database.mv')
    config['gameServer'].update(bindPort=game)
    config['resourceDir'] = str(fixture)
    (directory / 'config.json').write_text(json.dumps(config), 'utf-8')
    classes = directory / 'probe-classes'
    classes.mkdir()
    source = ROOT / 'scripts/fixtures/java/org/hkrpg/server/protocol/NativeOfferingQueryProbe.java'
    child = subprocess.run([str(java.with_name('javac.exe')), '-encoding', 'UTF-8', '-cp', str(jar),
                            '-d', str(classes), str(source)], capture_output=True, creationflags=flags)
    (directory / 'javac.log').write_bytes(child.stdout + child.stderr)
    assert child.returncode == 0, directory / 'javac.log'
    options = ['-Dfile.encoding=UTF-8', f'-Djdk.net.unixdomain.tmpdir={directory / "tcp-fallback"}']
    cp = str(classes) + os.pathsep + str(jar)
    snapshots, stops = [], 0
    artifact = sha(jar)

    def request(route, body=None):
        req = urllib.request.Request(f'http://127.0.0.1:{http}' + route,
            data=None if body is None else json.dumps(body).encode(), headers={'Content-Type': 'application/json'})
        with urllib.request.urlopen(req, timeout=5) as response:
            return json.load(response)

    for restart in range(2):
        with (directory / f'server-{restart}.log').open('wb') as log:
            server = subprocess.Popen([str(java), *options, '-Xmx1536m', '-jar', str(jar), '--no-handbook'],
                cwd=directory, stdin=subprocess.PIPE, stdout=log, stderr=subprocess.STDOUT, creationflags=flags)
            try:
                deadline = time.monotonic() + 100
                while True:
                    assert server.poll() is None, directory / f'server-{restart}.log'
                    try:
                        if request('/health/ready')['ready']:
                            break
                    except (OSError, urllib.error.URLError):
                        pass
                    assert time.monotonic() < deadline, directory
                    time.sleep(.2)
                login = request('/hkrpg_cn/mdk/shield/api/login', dict(account='krater-offering-native', password='local-test'))
                assert login['retcode'] == 0
                account = login['data']['account']
                combo = request('/hkrpg_cn/combo/granter/login/v2/login', dict(data=json.dumps(
                    dict(uid=account['uid'], token=account['token']))))
                assert combo['retcode'] == 0
                control = dict(accountUid=account['uid'], comboToken=combo['data']['combo_token'],
                    uid=previous['snapshots'][-1]['uid'])
                for reconnect in range(2 if restart == 0 else 1):
                    probe = subprocess.run([str(java), *options, '-cp', cp,
                        'org.hkrpg.server.protocol.NativeOfferingQueryProbe', str(game)],
                        cwd=directory, input=json.dumps(control), capture_output=True, text=True,
                        encoding='utf-8', timeout=100, creationflags=flags)
                    path = directory / f'probe-{len(snapshots)}.log'
                    path.write_text(probe.stdout + probe.stderr, 'utf-8')
                    assert probe.returncode == 0, path
                    value = json.loads(next(line for line in reversed(probe.stdout.splitlines()) if line.startswith('{')))
                    assert value['passed']
                    snapshots.append(value)
                    print(json.dumps(dict(phase=len(snapshots), nativeRequests=value['nativeRequests'],
                                          retainedPaidMaterials=value['retainedPaidMaterials'])), flush=True)
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
    assert stops == 2 and len(snapshots) == 3 and sha(jar) == artifact
    for path, before in source_hashes.items():
        assert sha(Path(path)) == before, path
    result = dict(passed=True, artifactSha256=artifact, runtimeDirectory=str(directory),
        previousRuntimeDirectory=str(original), snapshots=snapshots, normalStops=stops,
        sameUIDReconnects=1, sameUIDNormalH2ProcessRestarts=1,
        nativeRequests=sum(row['nativeRequests'] for row in snapshots),
        rejectedRequests=sum(row['rejectedRequests'] for row in snapshots),
        queriedOriginalTypes=15, retainedActualPaidMaterials=18800, retainedActualClaimedLevels=50,
        originalFixtureDatabaseAndSourceFilesVerifiedUnchanged=len(source_hashes),
        previousDatabaseCopiedBeforeLogin=True, newProgressPrewritten=False,
        requiredOriginalHeadersMaterialized=True,
        operatorDataModified=False, clientGameplayVerified=False, wholeGameComplete=False)
    (ROOT / 'build/offering-query-native.json').write_text(json.dumps(result, ensure_ascii=False, indent=2)+'\n','utf-8')
    print(json.dumps({k:v for k,v in result.items() if k not in ['snapshots']}), flush=True)


if __name__ == '__main__':
    main()
