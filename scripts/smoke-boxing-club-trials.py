"""隔离账号通过4.6原生KCP进行首季试用/正式混队四轮、重启续局及奖励去重；等级/主线门槛为明确夹具。"""
import argparse
import hashlib
import json
import os
import re
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
    parser.add_argument('--output', type=Path, default=ROOT / 'build/boxing-trial-native.json')
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
    directory = Path(tempfile.mkdtemp(prefix='boxing-trial-', dir=runs))
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
    (fixture / 'resource-manifest.json').write_text(json.dumps({'clientVersion': '4.6.0', 'testFixture': True, 'boxingTrialsFixture': True}), 'utf-8')
    http, mongo, game = port(), port(), port(socket.SOCK_DGRAM)
    config = directory / 'config.json'
    config.write_text(json.dumps({'protocol': {'wireVersion': '4.6.0'}, 'resourceDir': str(fixture), 'httpServer': {'bindAddress': '127.0.0.1', 'bindPort': http}, 'gameServer': {'bindAddress': '127.0.0.1', 'bindPort': game}, 'internalMongoServer': {'address': '127.0.0.1', 'port': mongo, 'filePath': './data/database.mv'}}), 'utf-8')
    config_bytes = config.read_bytes()
    data = directory / 'data'
    data.mkdir(exist_ok=True)
    (data / 'Gameplay46.json').write_text(json.dumps({'activities': [{'panelId': 50000, 'moduleId': 5000002, 'beginTime': 1, 'endTime': 4102444800}, {'panelId': 50000, 'moduleId': 5000001, 'beginTime': 1, 'endTime': 4102444800}]}), 'utf-8')
    groups = json.loads((source / 'ExcelOutput/BoxingClubStageGroup.json').read_text('utf-8'))
    events = next(row['EventIDList'] for row in groups if row['StageGroupID'] == 10)
    event_rows = json.loads((source / 'ExcelOutput/PlaneEvent.json').read_text('utf-8'))
    stages = [next(row['StageID'] for row in event_rows if row['EventID'] == event and row.get('WorldLevel', 0) == 0) for event in events]
    definitions = json.loads((source / 'ExcelOutput/BoxingClubStage.json').read_text('utf-8'))
    gains = [next(row.get('BuffID', 0) for row in definitions if row['EventID'] == event) for event in events]

    classes = directory / 'probe-classes'
    classes.mkdir()
    probe = ROOT / 'scripts/fixtures/java/org/hkrpg/server/protocol/NativeBoxingClubTrialsProbe.java'
    child = subprocess.run([str(java.with_name('javac.exe')), '-encoding', 'UTF-8', '-cp', str(jar), '-d', str(classes), str(probe), str(ROOT / 'scripts/fixtures/java/org/hkrpg/server/gameplay/BoxingClubTrialsFixtureInspector.java')], capture_output=True, creationflags=flags)
    (directory / 'javac.log').write_bytes(child.stdout + child.stderr)
    assert child.returncode == 0, directory / 'javac.log'
    java_flags = ['-Dfile.encoding=UTF-8', '-Dstdout.encoding=UTF-8', '-Dstderr.encoding=UTF-8', f'-Djdk.net.unixdomain.tmpdir={directory / "tcp-fallback"}']

    def request(route, body=None):
        req = urllib.request.Request(f'http://127.0.0.1:{http}' + route, data=None if body is None else json.dumps(body).encode(), headers={'Content-Type': 'application/json'})
        with urllib.request.urlopen(req, timeout=5) as reply:
            return json.load(reply)

    trial = next(row for row in json.loads((source / 'ExcelOutput/SpecialAvatar.json').read_text('utf-8')) if row.get('SpecialAvatarID') == 3051204 and row.get('WorldLevel', 0) == 0)
    credentials, snapshots, inspections = {}, [], []
    stops = 0
    for index in range(4):
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
                    login = request('/hkrpg_cn/mdk/shield/api/login', {'account': 'boxing-trial-native', 'password': 'local-test'})
                    assert login['retcode'] == 0
                    account = login['data']['account']
                    combo = request('/hkrpg_cn/combo/granter/login/v2/login', {'data': json.dumps({'uid': account['uid'], 'token': account['token']})})
                    assert combo['retcode'] == 0
                    credentials.update(accountUid=account['uid'], comboToken=combo['data']['combo_token'])
                assert sha(jar) == artifact
                probe_log = directory / f'probe-{index}.log'
                with probe_log.open('wb') as log_probe:
                    child = subprocess.Popen([str(java), *java_flags, '-cp', str(classes) + os.pathsep + str(jar), 'org.hkrpg.server.protocol.NativeBoxingClubTrialsProbe', str(game)], cwd=directory, stdin=subprocess.PIPE, stdout=log_probe, stderr=subprocess.STDOUT, creationflags=flags)
                    child.stdin.write((json.dumps(dict(credentials, phase=index, stages=stages, gainBuffs=gains, trial=trial)) + '\n').encode())
                    child.stdin.close()
                    deadline = time.monotonic() + 250
                    granted = False
                    try:
                        while child.poll() is None:
                            if index == 0 and not granted:
                                ready = re.search(r'READY_UID=(\d+)', probe_log.read_text('utf-8'))
                                if ready:
                                    uid = int(ready.group(1))
                                    assert uid == 10001
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
                snapshot = json.loads(next(line for line in reversed(probe_log.read_text('utf-8').splitlines()) if line.startswith('{"')))
                assert snapshot['passed'] and snapshot['phase'] == index and not snapshot['completionPrewritten']
                assert snapshot['acceptedRoundWins'] == [0, 2, 6, 0][index]
                credentials.setdefault('uid', snapshot['uid'])
                assert snapshot['uid'] == credentials['uid']
                if index == 0:
                    assert granted
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
        control = directory / 'inspection-control.json'
        control.write_text(json.dumps(credentials), 'utf-8')
        phase = ['prepare', 'active', 'settled', 'restart'][index]
        checked = subprocess.run([str(java), *java_flags, '-cp', str(classes) + os.pathsep + str(jar), 'org.hkrpg.server.gameplay.BoxingClubTrialsFixtureInspector', str(directory), str(control), phase], cwd=ROOT, capture_output=True, text=True, encoding='utf-8', timeout=100, creationflags=flags)
        (directory / f'inspector-{index}.log').write_text(checked.stdout + checked.stderr, 'utf-8')
        assert checked.returncode == 0, directory / f'inspector-{index}.log'
        proof = json.loads(next(line for line in reversed(checked.stdout.splitlines()) if line.startswith('{"')))
        assert proof['passed'] and proof['phase'] == phase and not proof['boxCompletionPrewritten']
        inspections.append(proof)
        print(json.dumps(dict(phase=index, uid=snapshot['uid'], acceptedRoundWins=snapshot['acceptedRoundWins'], normalStops=stops)), flush=True)
    assert stops == 4 and config.read_bytes() == config_bytes
    assert all(sha(source / path) == value for path, value in hashes.items()) and sha(jar) == artifact
    report = dict(passed=True, artifactSha256=artifact, runtimeDirectory=str(directory), snapshots=snapshots, inspections=inspections, normalStops=stops, sameUIDNormalH2ProcessRestarts=3, nativeRequests=sum(s['nativeRequests'] for s in snapshots), acceptedRoundWins=8, sourceResourceHashes=hashes, sourceResourcesModified=False, battleOrCompletionPrewritten=False, levelAndMainMissionArePrerequisiteFixtures=True, formalAvatar1001IsConsoleFixture=True, clientGameplayVerified=False, combatInputReplayVerified=False, completeGameplayVerified=False)
    (directory / 'result.json').write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n', 'utf-8')
    output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n', 'utf-8')
    print(json.dumps(dict(passed=True, artifactSha256=artifact, runtimeDirectory=str(directory), nativeRequests=report['nativeRequests'], normalStops=stops, sameUIDNormalH2ProcessRestarts=3, sourceResourcesGuarded=len(hashes))))


if __name__ == '__main__':
    main()
