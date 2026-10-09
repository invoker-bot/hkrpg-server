"""原始拳馆三条短信的接取、分支、未确认状态、完成及正常H2重启；入口门槛为明确夹具。"""
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
    parser.add_argument('--output', type=Path, default=ROOT / 'build/boxing-message-native.json')
    args = parser.parse_args()
    jar, output = args.jar.resolve(), args.output.resolve()
    assert jar.is_relative_to(ROOT / 'build') and output.is_relative_to(ROOT / 'build') and jar.is_file()
    java = next((ROOT / '.tools').glob('jdk-21*/bin/java.exe'), None)
    assert java
    artifact = sha(jar)
    source = ROOT / 'resources/4.6'
    selected = json.loads((ROOT / 'src/test/resources/fixtures/boxing-club-messages-4.6.json').read_text('utf-8'))
    mains = sorted(row['MainMissionID'] for row in selected['headers'])
    mission_files = sorted(path for main in mains for path in (source / f'Config/Level/Mission/{main}').glob('*.json'))
    guarded = sorted(source.glob('ExcelOutput/*.json')) + [source / 'resource-manifest.json'] + mission_files
    hashes = {path.relative_to(source).as_posix(): sha(path) for path in guarded}
    runs = ROOT / 'build/smoke'
    runs.mkdir(exist_ok=True)
    directory = Path(tempfile.mkdtemp(prefix='boxing-messages-', dir=runs))
    assert directory.resolve().is_relative_to(runs.resolve())
    fixture = directory / 'resources-fixture'
    bindings = mirror_resources(source, fixture, {})
    for main in mains:
        target, origin = fixture / f'Config/Level/Mission/{main}', source / f'Config/Level/Mission/{main}'
        if os.name == 'nt':
            bindings.append({'target': str(target), 'source': str(origin)})
        else:
            target.symlink_to(origin, target_is_directory=True)
    flags = subprocess.CREATE_NO_WINDOW if os.name == 'nt' else 0
    if bindings:
        plan = directory / 'junctions.json'
        plan.write_text(json.dumps(bindings), 'utf-8')
        helper = directory / 'prepare.ps1'
        helper.write_text("param([string]$Plan)\n$ErrorActionPreference='Stop'\nforeach ($binding in (Get-Content -LiteralPath $Plan -Raw | ConvertFrom-Json)) { New-Item -ItemType Junction -Path $binding.target -Value $binding.source | Out-Null }\n", 'utf-8')
        subprocess.run(['powershell', '-NoProfile', '-NonInteractive', '-File', str(helper), '-Plan', str(plan)], check=True, capture_output=True, creationflags=flags)
    marker = fixture / 'resource-manifest.json'
    marker.write_text(json.dumps({'clientVersion': '4.6.0', 'testFixture': True, 'boxingMessagesFixture': True}), 'utf-8')
    assert not os.path.samefile(marker, source / 'resource-manifest.json')
    http, mongo, game = port(), port(), port(socket.SOCK_DGRAM)
    config = directory / 'config.json'
    config.write_text(json.dumps({'protocol': {'wireVersion': '4.6.0'}, 'resourceDir': str(fixture),
        'httpServer': {'bindAddress': '127.0.0.1', 'bindPort': http},
        'gameServer': {'bindAddress': '127.0.0.1', 'bindPort': game},
        'internalMongoServer': {'address': '127.0.0.1', 'port': mongo, 'filePath': './data/database.mv'}}), 'utf-8')
    config_bytes = config.read_bytes()
    data = directory / 'data'
    data.mkdir(exist_ok=True)
    (data / 'Gameplay46.json').write_text(json.dumps({'activities': [{'panelId': 40003, 'moduleId': 4000301, 'beginTime': 1, 'endTime': 4102444800}]}), 'utf-8')
    classes = directory / 'probe-classes'
    classes.mkdir()
    probes = [ROOT / 'scripts/fixtures/java/org/hkrpg/server/protocol/LoopbackBoxingClubMessagesProbe.java',
              ROOT / 'scripts/fixtures/java/org/hkrpg/server/gameplay/BoxingClubMessagesFixtureInspector.java']
    compiled = subprocess.run([str(java.with_name('javac.exe')), '-encoding', 'UTF-8', '-cp', str(jar), '-d', str(classes), *(str(path) for path in probes)], capture_output=True, creationflags=flags)
    (directory / 'compile.log').write_bytes(compiled.stdout + compiled.stderr)
    assert compiled.returncode == 0, directory
    java_flags = ['-Dfile.encoding=UTF-8', '-Dstdout.encoding=UTF-8', '-Dstderr.encoding=UTF-8', '-Djdk.net.unixdomain.tmpdir=' + str(directory / 'tcp-fallback')]
    command = [str(java), *java_flags, '-Xmx1536m', '-jar', str(jar), '--no-handbook']
    classpath = str(classes) + os.pathsep + str(jar)
    base = f'http://127.0.0.1:{http}'
    credentials, snapshots, inspections, normal_stops = None, [], [], 0
    partial = {'1110000': [111000000, 111000001, 111000004, 111000007],
        '1110700': [111070000, 111070002, 111070004, 111070005],
        '1301100': [130110000, 130110001, 130110003, 130110004, 130110005, 130110006, 130110007, 130110008]}
    finished = dict(partial, **{'1110700': [111070000, 111070002, 111070004, 111070005, 111070007, 111070009]})
    control = directory / 'control.json'

    def request(route, body=None):
        req = urllib.request.Request(base + route, data=None if body is None else json.dumps(body).encode(), headers={'Content-Type': 'application/json'})
        with urllib.request.urlopen(req, timeout=5) as response:
            return json.load(response)

    def probe(mode):
        assert sha(jar) == artifact and config.read_bytes() == config_bytes
        child = subprocess.run([str(java), *java_flags, '-cp', classpath, 'org.hkrpg.server.protocol.LoopbackBoxingClubMessagesProbe', str(game)],
            input=json.dumps(dict(credentials, mode=mode)), text=True, encoding='utf-8', capture_output=True, timeout=70, creationflags=flags)
        (directory / f'probe-{len(snapshots)}-{mode}.log').write_text(child.stdout + child.stderr, 'utf-8')
        assert child.returncode == 0, directory
        result = json.loads(next(row for row in reversed(child.stdout.splitlines()) if row.startswith('{')))
        assert result['passed'] and result['uid'] == 10001 and not result['clientGameplayVerified']
        snapshots.append(result)
        return result

    def inspect(phase):
        assert config.read_bytes() == config_bytes
        child = subprocess.run([str(java), *java_flags, '-cp', classpath, 'org.hkrpg.server.gameplay.BoxingClubMessagesFixtureInspector', str(directory), str(control), phase],
            cwd=ROOT, text=True, encoding='utf-8', capture_output=True, timeout=70, creationflags=flags)
        (directory / f'inspector-{len(inspections)}-{phase}.log').write_text(child.stdout + child.stderr, 'utf-8')
        assert child.returncode == 0, directory
        result = json.loads(next(row for row in reversed(child.stdout.splitlines()) if row.startswith('{')))
        assert result['passed'] and result['inspectionWrites'] == 0 and not result['messageChoicesPrewritten'] and not result['messageCompletionsPrewritten']
        inspections.append(result)

    for iteration, mode in enumerate(['bootstrap', 'partial', 'finish', 'resume']):
        with (directory / f'server-{iteration}.log').open('w', encoding='utf-8') as log:
            process = subprocess.Popen(command, cwd=directory, stdin=subprocess.PIPE, stdout=log, stderr=subprocess.STDOUT, creationflags=flags)
            try:
                deadline = time.monotonic() + 100
                while True:
                    assert process.poll() is None, directory
                    try:
                        if request('/health/ready')['ready']:
                            break
                    except (OSError, urllib.error.URLError):
                        pass
                    assert time.monotonic() < deadline, directory
                    time.sleep(.2)
                if iteration == 0:
                    login = request('/hkrpg_cn/mdk/shield/api/login', {'account': 'cn-46-boxing-messages', 'password': 'local-test'})
                    assert login['retcode'] == 0
                    account = login['data']['account']
                    combo = request('/hkrpg_cn/combo/granter/login/v2/login', {'data': json.dumps({'uid': account['uid'], 'token': account['token']})})
                    assert combo['retcode'] == 0
                    credentials = {'accountUid': account['uid'], 'comboToken': combo['data']['combo_token']}
                result = probe(mode)
                if mode == 'bootstrap':
                    control.write_text(json.dumps({'uid': result['uid'], 'source': selected, 'partial': partial, 'finished': finished,
                        'partialOffered': {'1110000': [], '1110700': [111070006, 111070007], '1301100': []},
                        'finishedOffered': {id: [] for id in finished}}), 'utf-8')
                elif mode == 'partial':
                    probe('partial-resume')
                elif mode == 'finish':
                    probe('resume')
                time.sleep(2)
            finally:
                if process.poll() is None:
                    try:
                        process.stdin.write(b'stop\n')
                        process.stdin.flush()
                        process.wait(timeout=35)
                        assert process.returncode == (1000 if os.name == 'nt' else 232), directory
                        normal_stops += 1
                    except BaseException:
                        if process.poll() is None:
                            process.terminate()
                            process.wait(timeout=15)
                        raise
                    finally:
                        process.stdin.close()
        inspect('prepare' if mode == 'bootstrap' else 'partial' if mode == 'partial' else 'finished')
    assert len(snapshots) == 6 and len(inspections) == 4 and normal_stops == 4
    for snapshot in snapshots[1:]:
        done = snapshot['mode'] in ['finish', 'resume']
        assert snapshot['histories'] == (finished if done else partial)
        assert snapshot['finishedSections'] == ([1110000, 1110700, 1301100] if done else [])
        assert snapshot['objectiveProgress'] == {str(id): int(done) for id in [201070100, 201070202, 801520201]}
    assert hashes == {path.relative_to(source).as_posix(): sha(path) for path in guarded}
    assert sha(jar) == artifact and config.read_bytes() == config_bytes
    report = {'passed': True, 'runtimeDirectory': str(directory), 'rootJarSHA256': artifact,
        'snapshots': snapshots, 'inspections': inspections, 'normalStopCount': normal_stops,
        'sourceSHA256': hashes, 'sourceResourcesModified': False, 'operatorDataModified': False,
        'checks': [check for snapshot in snapshots for check in snapshot['checks']],
        'fixturePrerequisiteMainIDs': [1011001, 2010720, 1011503, 1010302], 'fixtureLevel': 21,
        'originalMessageRowsUnmodified': True, 'originalMissionDirectoriesUnmodified': True,
        'messageChoicesPrewritten': False, 'messageCompletionsPrewritten': False,
        'originalTalkCompletionKeyReportedThroughKCP': True,
        'clientGameplayVerified': False, 'fullStoryScriptExecutorInstalled': False,
        'officialEntryMovementVerified': False, 'crashAtomicityVerified': False}
    output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n', 'utf-8')
    print(json.dumps({'passed': True, 'report': str(output), 'runtimeDirectory': str(directory),
        'checks': len(report['checks']), 'normalStops': normal_stops, 'snapshots': len(snapshots),
        'rootJarSHA256': artifact}, ensure_ascii=False))


if __name__ == '__main__':
    main()
