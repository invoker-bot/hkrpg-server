"""原始source2事件、默认/简单偏好、实际KCP入战与正常H2恢复；正式世界1为明确夹具。"""
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


def free_port(kind=socket.SOCK_STREAM):
    with socket.socket(type=kind) as channel:
        channel.bind(('127.0.0.1', 0))
        return channel.getsockname()[1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--resources', type=Path, default=ROOT / 'resources/4.6')
    parser.add_argument('--output', type=Path, default=ROOT / 'build/stage-event-difficulty-native.json')
    sets = parser.add_mutually_exclusive_group()
    sets.add_argument('--single-events', action='store_true', help='验证全部28个原始单事件和完整偏好H2恢复')
    sets.add_argument('--all-events', action='store_true', help='验证全部42条目/57个实际事件及分组内独立偏好H2恢复')
    args = parser.parse_args()
    matrix = args.single_events or args.all_events
    source = args.resources.resolve(strict=True)
    output = args.output.resolve()
    assert output.is_relative_to(ROOT / 'build')
    java = next((ROOT / '.tools').glob('jdk-21*/bin/java.exe'), None)
    if java is None and os.environ.get('JAVA_HOME'):
        java = Path(os.environ['JAVA_HOME']) / 'bin/java.exe'
    jar = ROOT / 'build/libs/hkrpg-server.jar'
    classes = ROOT / 'build/classes/java/test'
    assert java and java.is_file() and jar.is_file()
    assert (classes / 'org/hkrpg/server/gameplay/StageEventDifficultyFixtureInspector.class').is_file()
    artifact = sha(jar)
    original = json.loads((ROOT / 'src/test/resources/fixtures' /
        ('all-stage-events-4.6.json' if args.all_events else 'single-stage-events-4.6.json' if args.single_events else 'stage-event-difficulty-4.6.json')).read_text('utf-8'))
    events = original['eligibleEventIds' if args.all_events else 'eligibleSingleEventIds'] if matrix else [20003011]
    assert len(set(events)) == len(events) == (57 if args.all_events else 28 if args.single_events else 1)
    assert original['sourceCommit'] == '724b139d8c9c32d12552eb95745a4fee72bfe48b'
    for name, proof in original['files'].items():
        assert hashlib.sha256((source / name).read_bytes().replace(b'\r\n', b'\n')).hexdigest() == proof['sourceSha256'], name
    guarded = ['resource-manifest.json', *[p.relative_to(source).as_posix() for p in (source / 'ExcelOutput').glob('*.json')]]
    hashes = {name: sha(source / name) for name in guarded}
    runs = ROOT / 'build/smoke'
    runs.mkdir(parents=True, exist_ok=True)
    directory = Path(tempfile.mkdtemp(prefix='stage-event-difficulty-', dir=runs))
    fixture = directory / 'resources-fixture'
    bindings = mirror_resources(source, fixture, {})
    flags = subprocess.CREATE_NO_WINDOW if os.name == 'nt' else 0
    if bindings:
        plan = directory / 'junction-plan.json'
        plan.write_text(json.dumps(bindings), 'utf-8')
        helper = directory / 'prepare-fixture.ps1'
        helper.write_text("param([string]$Plan)\n$ErrorActionPreference='Stop'\nforeach ($binding in (Get-Content -LiteralPath $Plan -Raw | ConvertFrom-Json)) { New-Item -ItemType Junction -Path $binding.target -Value $binding.source | Out-Null }\n", 'utf-8')
        subprocess.run(['powershell', '-NoProfile', '-NonInteractive', '-File', str(helper), '-Plan', str(plan)], check=True, capture_output=True, creationflags=flags)
    (fixture / 'resource-manifest.json').write_text(json.dumps({'clientVersion': '4.6.0', 'testFixture': True,
        'stageEventDifficultyFixture': True, 'actualWorldOneIsExplicitFixture': True,
        'originalEventAndStageAndDifficultyTablesUnchanged': True}), 'utf-8')
    http, mongo, game = free_port(), free_port(), free_port(socket.SOCK_DGRAM)
    config = directory / 'config.json'
    config.write_text(json.dumps({'protocol': {'wireVersion': '4.6.0'}, 'resourceDir': str(fixture),
        'httpServer': {'bindAddress': '127.0.0.1', 'bindPort': http},
        'gameServer': {'bindAddress': '127.0.0.1', 'bindPort': game},
        'internalMongoServer': {'address': '127.0.0.1', 'port': mongo, 'filePath': './data/database.mv'}}), 'utf-8')
    config_bytes = config.read_bytes()
    probes = directory / 'probe-classes'
    probes.mkdir()
    compiled = subprocess.run([str(java.with_name('javac.exe')), '-encoding', 'UTF-8', '-cp', str(jar), '-d', str(probes),
        str(ROOT / 'scripts/fixtures/java/org/hkrpg/server/protocol/NativeStageEventDifficultyProbe.java')], capture_output=True, creationflags=flags)
    (directory / 'javac.log').write_bytes(compiled.stdout + compiled.stderr)
    assert compiled.returncode == 0, directory / 'javac.log'
    java_flags = ['-Dfile.encoding=UTF-8', '-Dstdout.encoding=UTF-8', '-Dstderr.encoding=UTF-8',
                  f'-Djdk.net.unixdomain.tmpdir={directory / "tcp-fallback"}']
    credentials, snapshots, inspections = {}, [], []
    stops = 0

    def request(route, body=None):
        req = urllib.request.Request(f'http://127.0.0.1:{http}' + route,
            data=None if body is None else json.dumps(body).encode(), headers={'Content-Type': 'application/json'})
        with urllib.request.urlopen(req, timeout=5) as response:
            return json.load(response)

    def inspect(mode):
        data = directory / 'credentials.json'
        data.write_text(json.dumps(credentials), 'utf-8')
        child = subprocess.run([str(java), *java_flags, '-cp', str(classes) + os.pathsep + str(jar),
            'org.hkrpg.server.gameplay.StageEventDifficultyFixtureInspector', str(directory), str(data), mode],
            cwd=ROOT, capture_output=True, text=True, encoding='utf-8', timeout=100, creationflags=flags)
        log = directory / f'inspector-{len(inspections)}-{mode}.log'
        log.write_text(child.stdout + child.stderr, 'utf-8')
        assert child.returncode == 0, log
        row = json.loads(next(line for line in reversed(child.stdout.splitlines()) if line.startswith('{"')))
        assert row['passed'] and row['changedPlayers'] == (1 if mode == 'prepare' else 0) and row['changedLineups'] == 0
        inspections.append(row)

    modes = ['pending', 'singleMatrix', 'singleRestart'] if matrix else ['pending', 'unselected', 'easyRestart', 'defaultRestart']
    for index, mode in enumerate(modes):
        with (directory / f'server-{index}.log').open('wb') as log:
            process = subprocess.Popen([str(java), *java_flags, '-Xmx1536m', '-jar', str(jar), '--no-handbook'],
                cwd=directory, stdin=subprocess.PIPE, stdout=log, stderr=subprocess.STDOUT, creationflags=flags)
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
                        raise RuntimeError('Startup timed out: ' + str(directory))
                    time.sleep(.2)
                if not credentials:
                    login = request('/hkrpg_cn/mdk/shield/api/login', {'account': 'native-source2-difficulty', 'password': 'local-test'})
                    assert login['retcode'] == 0
                    account = login['data']['account']
                    combo = request('/hkrpg_cn/combo/granter/login/v2/login', {'data': json.dumps({'uid': account['uid'], 'token': account['token']})})
                    assert combo['retcode'] == 0
                    credentials.update(accountUid=account['uid'], comboToken=combo['data']['combo_token'])
                assert sha(jar) == artifact
                control = dict(credentials, mode=mode)
                if matrix:
                    control.update(singleEvents=events, allOriginalEvents=args.all_events,
                        stages=original['records']['ExcelOutput/StageConfig.json'])
                child = subprocess.run([str(java), *java_flags, '-cp', str(jar) + os.pathsep + str(probes),
                    'org.hkrpg.server.protocol.NativeStageEventDifficultyProbe', str(game)], cwd=directory,
                    input=json.dumps(control) + '\n', capture_output=True, text=True,
                    encoding='utf-8', timeout=300 if matrix else 90, creationflags=flags)
                probe_log = directory / f'probe-{index}-{mode}.log'
                probe_log.write_text(child.stdout + child.stderr, 'utf-8')
                assert child.returncode == 0, probe_log
                row = json.loads(next(line for line in reversed(child.stdout.splitlines()) if line.startswith('{"')))
                assert row['passed'] and not row['clientGameplayVerified']
                credentials.setdefault('uid', row['uid'])
                assert row['uid'] == credentials['uid']
                snapshots.append(row)
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
        if mode == 'pending':
            inspect('pending')
            inspect('prepare')
        else:
            inspect('allGroupedEasy' if args.all_events else 'allEasy' if args.single_events else 'easy' if mode == 'unselected' else 'default')
    if matrix:
        assert [r['requests'] for r in snapshots] == [4, len(events) * 37 + 4, len(events) * 11 + 4]
        assert [r['actualEventBattles'] for r in snapshots] == [0, len(events) * 3, len(events)]
        assert [r['stages'] for r in snapshots] == [[],
            [event * 10 + world for event in events for world in [1, 1, 0]],
            [event * 10 for event in events]]
    else:
        assert [r['requests'] for r in snapshots] == [4, 28, 28, 15]
        assert [r['actualEventBattles'] for r in snapshots] == [0, 2, 2, 1]
        assert [r['stages'] for r in snapshots] == [[], [200030111, 200030110], [200030110, 200030111], [200030111]]
    assert len({r['uid'] for r in snapshots}) == 1 and stops == len(modes)
    assert len(inspections) == len(modes) + 1 and len({r['formalLineup'] for r in inspections}) == 1
    assert config.read_bytes() == config_bytes and sha(jar) == artifact
    assert hashes == {name: sha(source / name) for name in guarded}
    result = {'passed': True, 'artifactSha256': artifact, 'snapshots': snapshots, 'inspections': inspections,
        'nativeRequests': sum(r['requests'] for r in snapshots),
        'actualEventBattles': sum(r['actualEventBattles'] for r in snapshots),
        'normalStops': stops, 'sameUIDNormalH2ProcessRestarts': len(modes) - 1,
        'verifiedSingleEventCount': 28 if matrix else 1,
        'verifiedStageEventCount': len(events), 'verifiedGroupedOwnerCount': 14 if args.all_events else 0,
        'sourceResourceHashes': hashes, 'sourceResourcesModified': False, 'actualWorldOneIsExplicitFixture': True,
        'missionDirectoryIsIsolatedAndEmpty': True, 'runtimeDirectory': str(directory),
        'clientGameplayVerified': False, 'fullStoryVerified': False, 'crashAtomicityVerified': False}
    output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n', 'utf-8')
    print(json.dumps({'passed': True, 'report': str(output), 'nativeRequests': result['nativeRequests'],
        'actualEventBattles': result['actualEventBattles'], 'normalStops': stops,
        'sourceResourcesUnchanged': len(hashes), 'artifactSha256': artifact}), flush=True)


if __name__ == '__main__':
    main()
