"""原始小小哈努关卡 3 的实际 KCP 存档、重连和正常 H2 重启；没有完整小游戏/UI验收。"""
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


def free_port(kind=socket.SOCK_STREAM):
    with socket.socket(type=kind) as sock:
        sock.bind(('127.0.0.1', 0))
        return sock.getsockname()[1]


def main():
    source = ROOT / 'resources/4.6'
    jar = ROOT / 'build/libs/hkrpg-server.jar'
    java = next((ROOT / '.tools').glob('jdk-21*/bin/java.exe'), None)
    assert java and jar.is_file()
    digest = hashlib.sha256(jar.read_bytes()).hexdigest()
    runs = ROOT / 'build/smoke'
    runs.mkdir(exist_ok=True)
    directory = Path(tempfile.mkdtemp(prefix='wolfbro-checkpoints-', dir=runs))
    assert directory.resolve().is_relative_to(runs.resolve())
    rows = json.loads((ROOT / 'src/test/resources/fixtures/wolfbro-lifecycle-4.6.json').read_text('utf-8'))
    guarded = ['resource-manifest.json'] + sorted({r['source'].removeprefix('resources/4.6/') for r in rows})
    hashes = {name: hashlib.sha256((source / name).read_bytes()).hexdigest() for name in guarded}
    fixture = directory / 'resources-fixture'
    junctions = mirror_resources(source, fixture, {})
    flags = subprocess.CREATE_NO_WINDOW if os.name == 'nt' else 0
    if junctions:
        plan = directory / 'junction-plan.json'
        plan.write_text(json.dumps(junctions), 'utf-8')
        helper = directory / 'prepare-fixture.ps1'
        helper.write_text("param([string]$Plan)\n$ErrorActionPreference='Stop'\nforeach ($binding in (Get-Content -LiteralPath $Plan -Raw | ConvertFrom-Json)) { New-Item -ItemType Junction -Path $binding.target -Value $binding.source | Out-Null }\n", 'utf-8')
        subprocess.run(['powershell', '-NoProfile', '-NonInteractive', '-File', str(helper), '-Plan', str(plan)],
                       check=True, capture_output=True, creationflags=flags)
    (fixture / 'resource-manifest.json').write_text(json.dumps({
        'clientVersion': '4.6.0', 'testFixture': True, 'noPrerequisitesOrGameplayStateSeeded': True}), 'utf-8')
    http, mongo, game = free_port(), free_port(), free_port(socket.SOCK_DGRAM)
    (directory / 'config.json').write_text(json.dumps({
        'protocol': {'wireVersion': '4.6.0'}, 'resourceDir': str(fixture),
        'httpServer': {'bindAddress': '127.0.0.1', 'bindPort': http},
        'gameServer': {'bindAddress': '127.0.0.1', 'bindPort': game},
        'internalMongoServer': {'address': '127.0.0.1', 'port': mongo, 'filePath': './data/database.mv'}}), 'utf-8')
    classes = directory / 'probe-classes'
    classes.mkdir()
    compilation = subprocess.run([str(java.with_name('javac.exe')), '-encoding', 'UTF-8', '-cp', str(jar),
        '-d', str(classes), str(ROOT / 'scripts/fixtures/java/org/hkrpg/server/protocol/NativeWolfBroLifecycleProbe.java')],
        capture_output=True, creationflags=flags)
    (directory / 'javac.log').write_bytes(compilation.stdout + compilation.stderr)
    assert compilation.returncode == 0, directory / 'javac.log'
    fallback = '-Djdk.net.unixdomain.tmpdir=' + str(directory / 'tcp-fallback')
    command = [str(java), '-Xmx1536m', fallback, '-jar', str(jar), '--no-handbook']

    def request(route, body=None):
        req = urllib.request.Request(f'http://127.0.0.1:{http}' + route,
            data=None if body is None else json.dumps(body).encode(), headers={'Content-Type': 'application/json'})
        with urllib.request.urlopen(req, timeout=5) as response:
            return json.load(response)

    def probe(name, credentials):
        assert hashlib.sha256(jar.read_bytes()).hexdigest() == digest
        child = subprocess.run([str(java), fallback, '-cp', str(jar) + os.pathsep + str(classes),
            'org.hkrpg.server.protocol.NativeWolfBroLifecycleProbe', str(game)], cwd=directory,
            input=json.dumps(credentials) + '\n', stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
            text=True, encoding='utf-8', errors='replace', timeout=120, creationflags=flags)
        (directory / name).write_text(child.stdout, 'utf-8')
        assert child.returncode == 0, directory / name
        snapshot = json.loads(next(line for line in child.stdout.splitlines() if line.startswith('{"uid":')))
        assert snapshot['passed'] and not snapshot['clientGameplayVerified'] and snapshot['jades'] == 0
        return snapshot

    snapshots, credentials, stops = [], None, 0
    for iteration in range(3):
        with (directory / f'server-{iteration}.log').open('w', encoding='utf-8') as log:
            process = subprocess.Popen(command, cwd=directory, stdin=subprocess.PIPE,
                stdout=log, stderr=subprocess.STDOUT, creationflags=flags)
            try:
                deadline = time.monotonic() + 90
                while True:
                    if process.poll() is not None:
                        raise RuntimeError('Server exited: ' + str(directory))
                    try:
                        if request('/health/ready')['ready']:
                            break
                    except (OSError, urllib.error.URLError):
                        pass
                    if time.monotonic() > deadline:
                        raise RuntimeError('Startup timed out: ' + str(directory))
                    time.sleep(.2)
                if iteration == 0:
                    login = request('/hkrpg_cn/mdk/shield/api/login', {'account': 'cn-wolfbro-checkpoints', 'password': 'local-test'})
                    assert login['retcode'] == 0
                    account = login['data']['account']
                    combo = request('/hkrpg_cn/combo/granter/login/v2/login', {'data': json.dumps({'uid': account['uid'], 'token': account['token']})})
                    assert combo['retcode'] == 0
                    credentials = {'accountUid': account['uid'], 'comboToken': combo['data']['combo_token'], 'mode': 'checkpoint'}
                    first = probe('checkpoint.log', credentials)
                    snapshots.append(first)
                    credentials.update(mode='resume', activeDataHash=first['dataHash'])
                    snapshots.append(probe('reconnect.log', credentials))
                    credentials['restoredCoordinatesExpected'] = True
                elif iteration == 1:
                    snapshots.append(probe('resume-active.log', credentials))
                    credentials['mode'] = 'quit'
                    closed = probe('quit.log', credentials)
                    snapshots.append(closed)
                    credentials.update(mode='closed', closedDataHash=closed['dataHash'])
                    credentials['restoredCoordinatesExpected'] = False
                else:
                    snapshots.append(probe('resume-closed.log', credentials))
                assert len({s['uid'] for s in snapshots}) == 1
                time.sleep(2)
            finally:
                if process.poll() is None:
                    try:
                        process.stdin.write(b'stop\n')
                        process.stdin.flush()
                        process.wait(timeout=30)
                        assert process.returncode == (1000 if os.name == 'nt' else 232), directory
                        stops += 1
                    except BaseException:
                        if process.poll() is None:
                            process.terminate()
                            process.wait(timeout=15)
                        raise
                    finally:
                        process.stdin.close()
    assert stops == 3 and len(snapshots) == 5
    assert [s['mode'] for s in snapshots] == ['checkpoint', 'resume', 'resume', 'quit', 'closed']
    assert [s['notifications'] for s in snapshots] == [2, 0, 0, 4, 0]
    assert [s['actualSceneMotionQueries'] for s in snapshots] == [0, 1, 2, 1, 0]
    assert [s['actualControlQueries'] for s in snapshots] == [3, 2, 3, 4, 1]
    assert [s['actualControlRequests'] for s in snapshots] == [2, 1, 1, 0, 0]
    assert [s['controlNotificationStates'] for s in snapshots] == [[1, 2, 1], [2], [], [0, 1, 0], []]
    assert all('restore-actually-updates-signed-player-position-and-rotation' in s['checks'] for s in snapshots[1:3])
    assert all('saved-player-position-and-rotation-retained-before-original-re-entry' in s['checks'] for s in snapshots[2:4])
    assert sum(s['actualStarts'] for s in snapshots) == sum(s['actualArchives'] for s in snapshots) == sum(s['actualRestores'] for s in snapshots) == sum(s['actualQuits'] for s in snapshots) == 2
    assert hashes == {name: hashlib.sha256((source / name).read_bytes()).hexdigest() for name in guarded}
    print(json.dumps({'passed': True, 'runtimeDirectory': str(directory), 'rootJarSHA256': digest,
        'snapshots': snapshots, 'normalStops': stops, 'sameUIDNormalH2ProcessRestarts': 2,
        'sameUIDSessions': 5, 'sourceResourceHashes': hashes, 'sourceResourcesModified': False,
        'actualSceneMotionQueries': 4, 'playerPositionAndRotationPersistedAfterNormalH2': True,
        'actualControlQueries': 13, 'actualControlRequests': 4, 'actualControlNotifications': 7,
        'sourceControlStateRestoredAndPersistedAfterNormalH2': True,
        'gameSceneObjectsRestored': False,
        'fixture': {'originalLevelFloorGroupsAndEntranceUnchanged': True, 'prerequisitesAndGameplayStateSeeded': False,
                    'movementAndGroupStateRestored': False, 'bulletsAndFullScriptsVerified': False},
        'clientGameplayVerified': False, 'crashAtomicityVerified': False}, ensure_ascii=False))


if __name__ == '__main__':
    main()
