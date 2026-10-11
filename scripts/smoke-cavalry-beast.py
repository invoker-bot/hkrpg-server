"""隔离 NativeKCP 验证原骑兽 NPC 的915报告、领奖与同UID重启；不启动官方客户端。"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import socket
import subprocess
import sys
import tempfile
import time
import traceback
import urllib.error
import urllib.request

ROOT = next(parent for parent in Path(__file__).resolve().parents
            if (parent / 'scripts/smoke_resource_fixture.py').is_file())
sys.path.insert(0, str(ROOT / 'scripts'))
from smoke_resource_fixture import mirror_resources


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def port(kind=socket.SOCK_STREAM):
    with socket.socket(type=kind) as sock:
        sock.bind(('127.0.0.1', 0))
        return sock.getsockname()[1]


def isolated_ports():
    http = port()
    for _ in range(32):
        mongo = port()
        if mongo != http:
            return http, mongo, port(socket.SOCK_DGRAM)
    raise RuntimeError('Could not select two distinct owned TCP ports')


def stop_owned_process(process, directory, ready):
    primary = sys.exception()
    try:
        if process.poll() is None:
            try:
                process.stdin.write(b'stop\n')
                process.stdin.flush()
                process.wait(timeout=45)
                expected = 1000 if os.name == 'nt' else 232
                if process.returncode != expected:
                    raise RuntimeError(f'Unexpected server stop exit: {process.returncode}; {directory}')
                return int(ready)
            finally:
                if process.poll() is None:
                    process.terminate()
                    process.wait(timeout=15)
                process.stdin.close()
        return 0
    except Exception as cleanup:
        log = directory / 'cleanup-failures.log'
        with log.open('a', encoding='utf-8') as evidence:
            evidence.write(''.join(traceback.format_exception(cleanup)))
        if primary is not None:
            primary.add_note(f'Owned stop also failed: {cleanup}; preserved in {log}')
            return 0
        raise


def local_request(http, route, body=None):
    req = urllib.request.Request(f'http://127.0.0.1:{http}' + route,
        data=None if body is None else json.dumps(body).encode(), headers={'Content-Type': 'application/json'})
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    with opener.open(req, timeout=5) as response:
        return json.load(response)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--jar', type=Path, default=ROOT / 'build/libs/hkrpg-server.jar')
    parser.add_argument('--output', type=Path, default=ROOT / 'build' / ('cavalry-beast-native-' + str(time.time_ns()) + '.json'))
    args = parser.parse_args()
    jar, output = args.jar.resolve(), args.output.resolve()
    assert jar.is_file() and jar.is_relative_to(ROOT / 'build') and output.is_relative_to(ROOT / 'build')
    if output.exists():
        raise FileExistsError('Choose an unused output path; previous native evidence is preserved: ' + str(output))
    java = next((ROOT / '.tools').glob('jdk-21*/bin/java.exe'), None)
    assert java
    artifact = sha(jar)
    output.parent.mkdir(parents=True, exist_ok=True)
    lease = output.with_name(output.name + '.lease.json')
    # Preserve the lease after success or failure so the same evidence path cannot be reused.
    with lease.open('x', encoding='utf-8') as reservation:
        json.dump(dict(output=str(output), pid=os.getpid(), jarSHA256=artifact, startedEpoch=time.time()), reservation)
    source = ROOT / 'resources/4.6'
    proof = json.loads((ROOT / 'src/test/resources/fixtures/cavalry-beast-4.6.json').read_text('utf-8'))
    assert proof['sourceCommit'] == '724b139d8c9c32d12552eb95745a4fee72bfe48b'
    for path, expected in proof['fileSHA256'].items():
        assert sha(source / path) == expected, path
    # Every pinned file is materialized unchanged beneath the fixture root. The full floor,
    # all its dimension references and original NPC definitions are preserved.
    replacements = {path: json.loads((source / path).read_text('utf-8')) for path in proof['fileSHA256']}
    binding = next(row for row in proof['bindings'] if row['plane'] == 10401 and row['group'] == 176)
    actor = next(row for row in proof['groups'][binding['path']]['NPCList'] if row['ID'] == binding['instance'])
    entrance = next(row for row in proof['records']['ExcelOutput/MapEntrance.json']
                    if row['PlaneID'] == 10401 and row['FloorID'] == 10401001)
    stage = dict(plane=10401, floor=10401001, entry=entrance['ID'], world=501,
                 pos={axis: int(actor.get('Pos' + axis.upper(), 0) * 1000) for axis in 'xyz'})
    guarded = set(source.glob('ExcelOutput/*.json')) | {source / 'resource-manifest.json'}
    guarded |= {source / path for path in proof['fileSHA256']}
    hashes = {path.relative_to(source).as_posix(): sha(path) for path in sorted(guarded)}
    runs = ROOT / 'build/smoke'
    runs.mkdir(exist_ok=True)
    directory = Path(tempfile.mkdtemp(prefix='cavalry-beast-', dir=runs))
    fixture = directory / 'resources-fixture'
    junctions = mirror_resources(source, fixture, replacements)
    for path in replacements:
        target = fixture / path
        assert target.resolve().is_relative_to(fixture.resolve())
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes((source / path).read_bytes())
    flags = subprocess.CREATE_NO_WINDOW if os.name == 'nt' else 0
    if junctions:
        plan = directory / 'junctions.json'
        plan.write_text(json.dumps(junctions), 'utf-8')
        helper = directory / 'prepare.ps1'
        helper.write_text("param([string]$Plan)\n$ErrorActionPreference='Stop'\nforeach ($binding in (Get-Content -LiteralPath $Plan -Raw | ConvertFrom-Json)) { New-Item -ItemType Junction -Path $binding.target -Value $binding.source | Out-Null }\n", 'utf-8')
        subprocess.run(['powershell', '-NoProfile', '-NonInteractive', '-File', str(helper), '-Plan', str(plan)],
                       check=True, capture_output=True, creationflags=flags)
    (fixture / 'resource-manifest.json').write_text(json.dumps(dict(clientVersion='4.6.0', testFixture=True,
        cavalryBeastFixture=True, sourceCommit=proof['sourceCommit'], originalFloorAndNpcSourcesUnmodified=True)), 'utf-8')
    data = directory / 'data'
    data.mkdir()
    (data / 'Gameplay46.json').write_text(json.dumps({'activities': [dict(panelId=30013, moduleId=3001301,
        beginTime=1, endTime=4102444800)]}), 'utf-8')
    http, mongo, game = isolated_ports()
    config = directory / 'config.json'
    config.write_text(json.dumps(dict(protocol=dict(wireVersion='4.6.0'), resourceDir=str(fixture),
        httpServer=dict(bindAddress='127.0.0.1', bindPort=http), gameServer=dict(bindAddress='127.0.0.1', bindPort=game),
        internalMongoServer=dict(address='127.0.0.1', port=mongo, filePath='./data/database.mv'))), 'utf-8')
    config_bytes = config.read_bytes()
    classes = directory / 'probe-classes'
    classes.mkdir()
    # Development drafts and the final checked-in script use the same owned helpers.
    helpers = Path(__file__).resolve().parent
    if (helpers / 'NativeCavalryActivityProbe.java').is_file():
        sources = [helpers / 'NativeCavalryActivityProbe.java', helpers / 'CavalryFixtureInspector.java']
    else:
        sources = [ROOT / 'scripts/fixtures/java/org/hkrpg/server/protocol/NativeCavalryActivityProbe.java',
                   ROOT / 'scripts/fixtures/java/org/hkrpg/server/gameplay/CavalryFixtureInspector.java']
    compiled = subprocess.run([str(java.with_name('javac.exe')), '-encoding', 'UTF-8', '-cp', str(jar),
        '-d', str(classes), *map(str, sources)], capture_output=True, creationflags=flags)
    (directory / 'javac.log').write_bytes(compiled.stdout + compiled.stderr)
    assert compiled.returncode == 0, directory / 'javac.log'
    options = ['-Dfile.encoding=UTF-8', '-Dstdout.encoding=UTF-8', '-Dstderr.encoding=UTF-8',
               f'-Djdk.net.unixdomain.tmpdir={directory / "tcp-fallback"}']
    classpath = str(classes) + os.pathsep + str(jar)
    credentials, snapshots, inspections = {}, [], []
    stops = 0

    def request(route, body=None):
        return local_request(http, route, body)

    def inspect(mode):
        control = directory / f'inspector-{len(inspections)}.json'
        control.write_text(json.dumps(dict(uid=credentials['uid'], stage=stage)), 'utf-8')
        child = subprocess.run([str(java), *options, '-cp', classpath,
            'org.hkrpg.server.gameplay.CavalryFixtureInspector', str(directory), str(control), mode], cwd=ROOT,
            capture_output=True, text=True, encoding='utf-8', timeout=120, creationflags=flags)
        log = directory / f'inspector-{len(inspections)}.log'
        log.write_text(child.stdout + child.stderr, 'utf-8')
        assert child.returncode == 0, log
        result = json.loads(next(line for line in reversed(child.stdout.splitlines()) if line.startswith('{')))
        assert result['passed'] and result['uid'] == credentials['uid']
        inspections.append(result)

    for index, mode in enumerate(['bootstrap', 'report', 'resume']):
        assert sha(jar) == artifact
        with (directory / f'server-{index}.log').open('wb') as log:
            process = subprocess.Popen([str(java), *options, '-Xmx1536m', '-jar', str(jar), '--no-handbook'],
                cwd=directory, stdin=subprocess.PIPE, stdout=log, stderr=subprocess.STDOUT, creationflags=flags)
            ready = False
            try:
                deadline = time.monotonic() + 100
                while True:
                    assert process.poll() is None, directory / f'server-{index}.log'
                    try:
                        if request('/health/ready')['ready']:
                            ready = True
                            break
                    except (OSError, urllib.error.URLError):
                        pass
                    if time.monotonic() > deadline:
                        raise TimeoutError(directory / f'server-{index}.log')
                    time.sleep(.2)
                if not credentials:
                    login = request('/hkrpg_cn/mdk/shield/api/login', dict(account='cavalry-native', password='local-test'))
                    assert login['retcode'] == 0
                    account = login['data']['account']
                    combo = request('/hkrpg_cn/combo/granter/login/v2/login',
                        dict(data=json.dumps(dict(uid=account['uid'], token=account['token']))))
                    assert combo['retcode'] == 0
                    credentials.update(accountUid=account['uid'], comboToken=combo['data']['combo_token'])
                probe_log = directory / f'probe-{index}.log'
                with probe_log.open('wb') as out:
                    child = subprocess.Popen([str(java), *options, '-cp', classpath,
                        'org.hkrpg.server.protocol.NativeCavalryActivityProbe', str(game)], cwd=directory,
                        stdin=subprocess.PIPE, stdout=out, stderr=subprocess.STDOUT, creationflags=flags)
                    child.stdin.write((json.dumps(dict(credentials, **stage, mode=mode)) + '\n').encode())
                    child.stdin.close()
                    deadline, granted = time.monotonic() + 180, False
                    try:
                        while child.poll() is None:
                            if mode == 'bootstrap' and not granted:
                                uid_marker = re.search(r'READY_UID=(\d+)', probe_log.read_text('utf-8'))
                                if uid_marker:
                                    uid = int(uid_marker.group(1))
                                    assert uid == 10001, 'Only the one newly created fixture player may be seeded'
                                    process.stdin.write(f'give 1001 @{uid}\n'.encode())
                                    process.stdin.flush()
                                    granted = True
                            if time.monotonic() > deadline:
                                raise TimeoutError(probe_log)
                            time.sleep(.2)
                    finally:
                        if child.poll() is None:
                            child.terminate()
                            child.wait(timeout=15)
                assert child.returncode == 0, probe_log
                snapshot = json.loads(next(line for line in reversed(probe_log.read_text('utf-8').splitlines()) if line.startswith('{')))
                assert snapshot['passed']
                credentials.setdefault('uid', snapshot['uid'])
                assert credentials['uid'] == snapshot['uid']
                snapshots.append(snapshot)
            finally:
                stops += stop_owned_process(process, directory, ready)
        inspect('seed' if mode == 'bootstrap' else 'inspect')
        print(json.dumps(dict(phase=mode, normalStops=stops, checks=len(snapshot['checks']))), flush=True)
    inspect('expired')
    assert stops == 3 and sha(jar) == artifact and config.read_bytes() == config_bytes
    for path, expected in hashes.items():
        assert sha(source / path) == expected, path
    result = dict(passed=True, runtimeDirectory=str(directory), artifactSha256=artifact,
        snapshots=snapshots, inspections=inspections, nativeRequests=sum(row['requests'] for row in snapshots),
        nativeChecks=sum(len(row['checks']) for row in snapshots), normalStops=stops,
        sameUIDNormalH2ProcessRestarts=2, actualReward={1: 20, 213: 4}, originalRideQuest=6029860,
        sourceResourceHashes=hashes, originalFloorAndNpcSourcesUnmodified=True,
        sourceResourcesModified=False, operatorDataModified=False, prerequisitesAndPositionAreFixtures=True,
        operatorWindowIsFixture=True, targetCompletionPrewritten=False, receiptPrewritten=False,
        physicalRideVerified=False, clientGameplayVerified=False, crashAtomicityVerified=False,
        wholeActivityComplete=False, wholeGameComplete=False)
    output.parent.mkdir(parents=True, exist_ok=True)
    with output.open('x', encoding='utf-8') as report:
        report.write(json.dumps(result, ensure_ascii=False, indent=2) + '\n')
    print(json.dumps({key: result[key] for key in ['passed', 'nativeRequests', 'nativeChecks', 'normalStops',
        'artifactSha256', 'runtimeDirectory']}, ensure_ascii=False))


if __name__ == '__main__':
    main()
