"""隔离 4.6 KCP 与 H2 检查「闭嘴」回执，不连接正式存档。"""
from pathlib import Path
import errno
import hashlib
import json
import os
import shutil
import socket
import subprocess
import sys
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
    source = Path(sys.argv[1]).resolve()
    artifact = Path(sys.argv[2]).resolve() if len(sys.argv) > 2 else ROOT / 'build/libs/hkrpg-server.jar'
    java = Path(os.environ['JAVA_HOME']) / 'bin/java.exe'
    classes = ROOT / 'build/classes/java/test'
    assert java.is_file() and artifact.is_file() and source.is_dir()
    assert (classes / 'org/hkrpg/server/protocol/MissionTalkCustomNativeProbe.class').is_file()
    digest = hashlib.sha256(artifact.read_bytes()).hexdigest()
    runs = ROOT / 'build/smoke'
    runs.mkdir(parents=True, exist_ok=True)
    directory = Path(tempfile.mkdtemp(prefix='talk-custom-', dir=runs)).resolve()
    assert directory.is_relative_to(runs.resolve())
    fixture_classes = directory / 'fixture-classes'
    for package, prefix in [('org/hkrpg/server/protocol', 'MissionTalkCustomNativeProbe'),
                            ('org/hkrpg/server/gameplay', 'MissionTalkCustomFixtureInspector')]:
        target = fixture_classes / package
        target.mkdir(parents=True, exist_ok=True)
        found = list((classes / package).glob(prefix + '*.class'))
        assert found
        for file in found:
            shutil.copyfile(file, target / file.name)
    fixture = directory / 'resources-fixture'
    fixture.mkdir()
    guarded = ['resource-manifest.json', 'ExcelOutput/RewardData.json', 'ExcelOutput/ItemConfig.json',
               'ExcelOutput/MapEntrance.json',
               'Config/Level/Mission/8027111/MissionInfo_8027111.json',
               'Config/Level/Mission/8027111/MissionInfo_8027111.partial.json']
    hashes = {name: hashlib.sha256((source / name).read_bytes()).hexdigest() for name in guarded}
    # A worktree can be on a different volume from the operator's read-only resources.
    # The shared fixture helper hard-links individual files; copy only when that is impossible.
    original_link = os.link
    def link_or_copy(src, dst):
        try:
            original_link(src, dst)
        except OSError as error:
            if error.errno != errno.EXDEV and getattr(error, 'winerror', None) != 17:
                raise
            shutil.copyfile(src, dst)
    try:
        os.link = link_or_copy
        junctions = mirror_resources(source, fixture, {})
    finally:
        os.link = original_link
    flags = subprocess.CREATE_NO_WINDOW if os.name == 'nt' else 0
    if junctions:
        plan = directory / 'junction-plan.json'
        plan.write_text(json.dumps(junctions), encoding='utf-8')
        helper = directory / 'prepare-fixture.ps1'
        helper.write_text("param([string]$Plan)\n$ErrorActionPreference='Stop'\n"
                          "foreach ($binding in (Get-Content -LiteralPath $Plan -Raw | ConvertFrom-Json)) "
                          "{ New-Item -ItemType Junction -Path $binding.target -Value $binding.source | Out-Null }\n",
                          encoding='utf-8')
        subprocess.run(['powershell', '-NoProfile', '-NonInteractive', '-File', str(helper), '-Plan', str(plan)],
                       check=True, capture_output=True, creationflags=flags)
    for name in guarded[-2:]:
        target = fixture / name
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(source / name, target)
    (fixture / 'resource-manifest.json').write_text(json.dumps(
        {'clientVersion': '4.6.0', 'testFixture': True, 'talkCustomFixture': True}), encoding='utf-8')
    http_port, mongo_port, game_port = free_port(), free_port(), free_port(socket.SOCK_DGRAM)
    (directory / 'config.json').write_text(json.dumps({
        'protocol': {'wireVersion': '4.6.0'}, 'resourceDir': str(fixture),
        'httpServer': {'bindAddress': '127.0.0.1', 'bindPort': http_port},
        'gameServer': {'bindAddress': '127.0.0.1', 'bindPort': game_port},
        'internalMongoServer': {'address': '127.0.0.1', 'port': mongo_port, 'filePath': './data/database.mv'}}),
        encoding='utf-8')
    encoding = ['-Dfile.encoding=UTF-8', '-Dstdout.encoding=UTF-8', '-Dstderr.encoding=UTF-8']
    java_option = '-Djdk.net.unixdomain.tmpdir=' + str(directory / 'tcp-fallback')
    command = [str(java), *encoding, '-Xmx1536m', java_option, '-jar', str(artifact), '--no-handbook']
    base = f'http://127.0.0.1:{http_port}'
    credentials, snapshots, inspections, stops = None, [], [], 0

    def request(route, body=None):
        req = urllib.request.Request(base + route, data=None if body is None else json.dumps(body).encode(),
                                     headers={'Content-Type': 'application/json'})
        with urllib.request.urlopen(req, timeout=5) as response:
            return json.load(response)

    def probe(mode):
        assert hashlib.sha256(artifact.read_bytes()).hexdigest() == digest
        result = subprocess.run([str(java), *encoding, java_option, '-cp', os.pathsep.join([str(fixture_classes), str(artifact)]),
            'org.hkrpg.server.protocol.MissionTalkCustomNativeProbe', str(game_port)],
            input=json.dumps(dict(credentials, mode=mode)), text=True, encoding='utf-8', capture_output=True,
            timeout=60, creationflags=flags)
        (directory / f'probe-{mode}.log').write_text(result.stdout + result.stderr, encoding='utf-8')
        assert result.returncode == 0, directory
        value = json.loads(next(row for row in reversed(result.stdout.splitlines()) if row.startswith('{')))
        assert value['passed'] and not value['clientGameplayVerified']
        snapshots.append(value)

    def inspect(mode):
        result = subprocess.run([str(java), *encoding, java_option, '-cp', os.pathsep.join([str(fixture_classes), str(artifact)]),
            'org.hkrpg.server.gameplay.MissionTalkCustomFixtureInspector', str(directory), str(snapshots[0]['uid']), mode],
            cwd=ROOT, text=True, encoding='utf-8', capture_output=True, timeout=60, creationflags=flags)
        (directory / f'inspect-{len(inspections)}-{mode}.log').write_text(result.stdout + result.stderr, encoding='utf-8')
        assert result.returncode == 0, directory
        value = json.loads(next(row for row in reversed(result.stdout.splitlines()) if row.startswith('{')))
        assert value['passed'] and not value['operatorDataModified']
        inspections.append(value)

    for iteration, mode in enumerate(['bootstrap', 'complete', 'resume']):
        with (directory / f'server-{iteration}.log').open('w', encoding='utf-8') as log:
            process = subprocess.Popen(command, cwd=directory, stdin=subprocess.PIPE, stdout=log,
                                       stderr=subprocess.STDOUT, creationflags=flags)
            try:
                deadline = time.monotonic() + 120
                while True:
                    assert process.poll() is None, directory
                    try:
                        if request('/health/ready')['ready']:
                            break
                    except (OSError, urllib.error.URLError):
                        pass
                    if time.monotonic() > deadline:
                        raise RuntimeError(f'Fixture readiness timeout: {directory}')
                    time.sleep(.2)
                if iteration == 0:
                    login = request('/hkrpg_cn/mdk/shield/api/login',
                                    {'account': 'cn-46-talk-custom-fixture', 'password': 'local-test'})
                    assert login['retcode'] == 0
                    account = login['data']['account']
                    combo = request('/hkrpg_cn/combo/granter/login/v2/login',
                                    {'data': json.dumps({'uid': account['uid'], 'token': account['token']})})
                    assert combo['retcode'] == 0
                    credentials = {'accountUid': account['uid'], 'comboToken': combo['data']['combo_token']}
                probe(mode)
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
        inspect('seed' if iteration == 0 else 'inspect')
    assert stops == 3 and len(snapshots) == 3 and len(inspections) == 3
    assert all(value['uid'] == snapshots[0]['uid'] for value in snapshots)
    assert inspections[0]['changedPlayers'] == 1 and all(v['changedPlayers'] == 0 for v in inspections[1:])
    assert hashes == {name: hashlib.sha256((source / name).read_bytes()).hexdigest() for name in guarded}
    assert hashlib.sha256(artifact.read_bytes()).hexdigest() == digest
    print(json.dumps({'passed': True, 'runtimeDirectory': str(directory), 'jarSHA256': digest,
        'snapshots': snapshots, 'inspections': inspections, 'normalStops': stops,
        'fixture': {'seededMainAndTwoPriorObjectives': True, 'targetAndChoiceSeeded': False,
                    'originalMissionFilesUnchanged': True},
        'sourceResourcesModified': False, 'clientGameplayVerified': False}, ensure_ascii=False))


if __name__ == '__main__':
    main()
