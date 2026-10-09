"""原始 Normal 虚构叙事 20011，两半场原生入战/累计报告与正常 H2 重启历史；门槛/角色为隔离夹具。"""
import hashlib
import json
import os
from pathlib import Path
import re
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
    source = ROOT / 'resources/4.6'
    jar = ROOT / 'build/libs/hkrpg-server.jar'
    java = next((ROOT / '.tools').glob('jdk-21*/bin/java.exe'), None)
    assert java and jar.is_file()
    artifact = sha(jar)
    runs = ROOT / 'build/smoke'
    runs.mkdir(exist_ok=True)
    directory = Path(tempfile.mkdtemp(prefix='story-challenge-', dir=runs))
    assert directory.resolve().is_relative_to(runs.resolve())
    fixture = directory / 'resources-fixture'
    entrances = json.loads((source / 'ExcelOutput/MapEntrance.json').read_text('utf-8'))
    original = [dict(row) for row in entrances if row['ID'] in [3000205, 102020107]]
    entry = next(row for row in entrances if row['ID'] == 3000205)
    plane, floor = entry['PlaneID'], entry['FloorID']
    guarded = sorted(source.glob('ExcelOutput/*.json')) + [source / 'resource-manifest.json']
    guarded += [source / f'Config/LevelOutput/RuntimeFloor/P{plane}_F{floor}.json']
    guarded += [source / f'Config/LevelOutput/SharedRuntimeGroup/Groups_P{plane}_F{floor}/LevelGroup_P{plane}_F{floor}_G{g}.json' for g in [6, 7]]
    hashes = {p.relative_to(source).as_posix(): sha(p) for p in guarded}
    for row in entrances:
        if row['ID'] in [3000205, 102020107]:
            row.update(BeginMainMissionList=[], FinishMainMissionList=[], FinishSubMissionList=[])
    bindings = mirror_resources(source, fixture, {'ExcelOutput/MapEntrance.json': entrances})
    flags = subprocess.CREATE_NO_WINDOW if os.name == 'nt' else 0
    if bindings:
        plan = directory / 'junctions.json'
        plan.write_text(json.dumps(bindings), 'utf-8')
        helper = directory / 'prepare.ps1'
        helper.write_text("param([string]$Plan)\n$ErrorActionPreference='Stop'\nforeach ($binding in (Get-Content -LiteralPath $Plan -Raw | ConvertFrom-Json)) { New-Item -ItemType Junction -Path $binding.target -Value $binding.source | Out-Null }\n", 'utf-8')
        subprocess.run(['powershell', '-NoProfile', '-NonInteractive', '-File', str(helper), '-Plan', str(plan)], check=True, capture_output=True, creationflags=flags)
    (fixture / 'resource-manifest.json').write_text(json.dumps({'clientVersion': '4.6.0', 'testFixture': True, 'storyChallenge': 20011, 'originalEntrances': original}), 'utf-8')
    http, mongo, game = port(), port(), port(socket.SOCK_DGRAM)
    config = directory / 'config.json'
    config.write_text(json.dumps({'protocol': {'wireVersion': '4.6.0'}, 'resourceDir': str(fixture), 'httpServer': {'bindAddress': '127.0.0.1', 'bindPort': http}, 'gameServer': {'bindAddress': '127.0.0.1', 'bindPort': game}, 'internalMongoServer': {'address': '127.0.0.1', 'port': mongo, 'filePath': './data/database.mv'}}), 'utf-8')
    config_bytes = config.read_bytes()
    classes = directory / 'probe-classes'
    classes.mkdir()
    probe = ROOT / 'scripts/fixtures/java/org/hkrpg/server/protocol/NativeStoryChallengeProbe.java'
    child = subprocess.run([str(java.with_name('javac.exe')), '-encoding', 'UTF-8', '-cp', str(jar), '-d', str(classes), str(probe)], capture_output=True, creationflags=flags)
    (directory / 'javac.log').write_bytes(child.stdout + child.stderr)
    assert child.returncode == 0, directory / 'javac.log'
    java_flags = ['-Dfile.encoding=UTF-8', '-Dstdout.encoding=UTF-8', '-Dstderr.encoding=UTF-8', f'-Djdk.net.unixdomain.tmpdir={directory / "tcp-fallback"}']

    def request(route, body=None):
        req = urllib.request.Request(f'http://127.0.0.1:{http}' + route, data=None if body is None else json.dumps(body).encode(), headers={'Content-Type': 'application/json'})
        with urllib.request.urlopen(req, timeout=5) as response:
            return json.load(response)

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
                        raise RuntimeError('Startup timed out: ' + str(directory))
                    time.sleep(.2)
                if not credentials:
                    login = request('/hkrpg_cn/mdk/shield/api/login', {'account': 'story-challenge-native', 'password': 'local-test'})
                    assert login['retcode'] == 0
                    account = login['data']['account']
                    combo = request('/hkrpg_cn/combo/granter/login/v2/login', {'data': json.dumps({'uid': account['uid'], 'token': account['token']})})
                    assert combo['retcode'] == 0
                    credentials.update(accountUid=account['uid'], comboToken=combo['data']['combo_token'])
                assert sha(jar) == artifact
                probe_log = directory / f'probe-{index}.log'
                with probe_log.open('wb') as output:
                    child = subprocess.Popen([str(java), *java_flags, '-cp', str(classes) + os.pathsep + str(jar), 'org.hkrpg.server.protocol.NativeStoryChallengeProbe', str(game)], cwd=directory, stdin=subprocess.PIPE, stdout=output, stderr=subprocess.STDOUT, creationflags=flags)
                    child.stdin.write((json.dumps(dict(credentials, resume=bool(index))) + '\n').encode())
                    child.stdin.close()
                    deadline = time.monotonic() + 150
                    granted = False
                    try:
                        while child.poll() is None:
                            if index == 0 and not granted:
                                match = re.search(r'READY_UID=(\d+)', probe_log.read_text('utf-8'))
                                if match:
                                    uid = int(match.group(1))
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
                assert snapshot['passed'] and snapshot['score'] == 40000 and snapshot['starMask'] == 1 and snapshot['sourceChallenge'] == 20011
                assert snapshot['scoreOne'] == 12000 and snapshot['scoreTwo'] == 28000
                if snapshots:
                    assert snapshot['uid'] == snapshots[0]['uid'] and snapshot['actualHalfVictories'] == 0
                else:
                    assert granted and snapshot['actualHalfVictories'] == 2
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
    assert stops == 2 and config.read_bytes() == config_bytes and all(sha(source / path) == value for path, value in hashes.items())
    report = {'passed': True, 'artifactSha256': artifact, 'runtimeDirectory': str(directory), 'snapshots': snapshots, 'normalStops': stops, 'sameUIDNormalH2ProcessRestarts': 1, 'nativeRequests': sum(s['nativeRequests'] for s in snapshots), 'actualHalfVictories': 2, 'historyOrCompletionPrewritten': False, 'formalAvatar1001IsConsoleFixture': True, 'sourceResourceHashes': hashes, 'sourceResourcesModified': False, 'originalEntrances': original, 'clientGameplayVerified': False, 'completeGameplayVerified': False, 'combatInputReplayVerified': False}
    (directory / 'result.json').write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n', 'utf-8')
    print(json.dumps({k: v for k, v in report.items() if k != 'sourceResourceHashes'}, ensure_ascii=False))
    (ROOT / 'build/story-challenge-native.json').write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n', 'utf-8')


if __name__ == '__main__':
    main()
