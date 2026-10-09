"""原始活动解锁门的KCP查询与正常H2恢复；前置完成状态及运营窗口明确隔离。"""
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
    parser.add_argument('--output', type=Path, default=ROOT / 'build/activity-panel-unlock-native.json')
    args = parser.parse_args()
    source = args.resources.resolve(strict=True)
    output = args.output.resolve()
    assert output.is_relative_to(ROOT / 'build')
    java = next((ROOT / '.tools').glob('jdk-21*/bin/java.exe'), None)
    if java is None and os.environ.get('JAVA_HOME'):
        java = Path(os.environ['JAVA_HOME']) / 'bin/java.exe'
    jar = ROOT / 'build/libs/hkrpg-server.jar'
    classes = ROOT / 'build/classes/java/test'
    assert java and java.is_file() and jar.is_file()
    assert (classes / 'org/hkrpg/server/gameplay/ActivityPanelUnlockFixtureInspector.class').is_file()
    artifact = sha(jar)
    original = json.loads((ROOT / 'src/test/resources/fixtures/activity-panel-unlock-4.6.json').read_text('utf-8'))
    assert original['sourceCommit'] == '724b139d8c9c32d12552eb95745a4fee72bfe48b'
    for name, proof in original['files'].items():
        assert hashlib.sha256((source / name).read_bytes().replace(b'\r\n', b'\n')).hexdigest() == proof['sourceSha256'], name
    guarded = sorted(set(['resource-manifest.json', *original['files'], *[p.relative_to(source).as_posix() for p in (source / 'ExcelOutput').glob('*.json')]]))
    hashes = {name: sha(source / name) for name in guarded}
    runs = ROOT / 'build/smoke'
    runs.mkdir(parents=True, exist_ok=True)
    directory = Path(tempfile.mkdtemp(prefix='activity-panel-unlock-', dir=runs))
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
        'activityPanelUnlockFixture': True, 'completionStatesAreExplicitFixtures': True,
        'originalPanelMissionAndQuestTablesUnchanged': True}), 'utf-8')
    import shutil
    mission = 'Config/Level/Mission/8017101'
    shutil.copytree(source / mission, fixture / mission)
    data_dir = directory / 'data'
    data_dir.mkdir()
    (data_dir / 'Gameplay46.json').write_text(json.dumps({'activities': [
        {'panelId': 10061, 'moduleId': 199984, 'beginTime': 0, 'endTime': 9223372036854775807},
        {'panelId': 10025, 'moduleId': 199985, 'beginTime': 0, 'endTime': 9223372036854775807}]}), 'utf-8')
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
        str(ROOT / 'scripts/fixtures/java/org/hkrpg/server/protocol/NativeActivityPanelUnlockProbe.java')], capture_output=True, creationflags=flags)
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
            'org.hkrpg.server.gameplay.ActivityPanelUnlockFixtureInspector', str(directory), str(data), mode],
            cwd=ROOT, capture_output=True, text=True, encoding='utf-8', timeout=100, creationflags=flags)
        log = directory / f'inspector-{len(inspections)}-{mode}.log'
        log.write_text(child.stdout + child.stderr, 'utf-8')
        assert child.returncode == 0, log
        row = json.loads(next(line for line in reversed(child.stdout.splitlines()) if line.startswith('{"')))
        assert row['passed'] and row['changedPlayers'] == (1 if mode.startswith('prepare') else 0) and row['changedLineups'] == 0
        inspections.append(row)

    modes = ['pending', 'main', 'sub', 'closed']
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
                control['expectedPanels'] = [] if mode == 'pending' else [10025, 10061] if mode == 'closed' else [10061]
                child = subprocess.run([str(java), *java_flags, '-cp', str(jar) + os.pathsep + str(probes),
                    'org.hkrpg.server.protocol.NativeActivityPanelUnlockProbe', str(game)], cwd=directory,
                    input=json.dumps(control) + '\n', capture_output=True, text=True,
                    encoding='utf-8', timeout=90, creationflags=flags)
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
        inspect(mode)
        if mode != 'closed':
            inspect({'pending': 'prepareMain', 'main': 'prepareSub', 'sub': 'prepareClosed'}[mode])
    assert [r['requests'] for r in snapshots] == [5, 5, 5, 5]
    assert [r['panels'] for r in snapshots] == [[], [10061], [10061], [10025, 10061]]
    assert len({r['uid'] for r in snapshots}) == 1 and stops == 4
    assert len(inspections) == 7 and len({r['formalLineup'] for r in inspections}) == 1
    assert config.read_bytes() == config_bytes and sha(jar) == artifact
    assert hashes == {name: sha(source / name) for name in guarded}
    result = {'passed': True, 'artifactSha256': artifact, 'snapshots': snapshots, 'inspections': inspections,
        'nativeRequests': 20, 'normalStops': stops, 'sameUIDNormalH2ProcessRestarts': 3,
        'sourceResourceHashes': hashes, 'sourceResourcesModified': False,
        'completionStatesAreExplicitFixtures': True, 'operatorWindowsAreExplicitFixtures': True,
        'missionDirectoryIsIsolatedToOneOriginalParent': True, 'runtimeDirectory': str(directory),
        'clientGameplayVerified': False, 'fullActivityGameplayVerified': False, 'crashAtomicityVerified': False}
    output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n', 'utf-8')
    print(json.dumps({'passed': True, 'report': str(output), 'nativeRequests': 20,
        'normalStops': 4, 'sourceResourcesUnchanged': len(hashes), 'artifactSha256': artifact}), flush=True)


if __name__ == '__main__':
    main()
