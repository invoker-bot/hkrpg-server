"""隔离 H2 验证原自动衔接、旧局恢复、原生强化及首次教学选图；不操作真实游戏。"""
import argparse
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import shutil
import socket
import subprocess
import tempfile
import time
import urllib.request

from smoke_resource_fixture import mirror_resources

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('teaching_ports', ROOT / 'scripts/smoke-rogue-first-world-entry.py')
ports = importlib.util.module_from_spec(spec)
spec.loader.exec_module(ports)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--jar', type=Path, required=True)
    parser.add_argument('--fixture', choices=['legacy', 'fresh'], required=True)
    args = parser.parse_args()
    jar = args.jar.resolve()
    java = next((ROOT / '.tools').glob('jdk-21*/bin/java.exe'))
    sha = lambda p: hashlib.sha256(p.read_bytes()).hexdigest()
    digest = sha(jar)
    source = ROOT / 'resources/4.6'
    directory = Path(tempfile.mkdtemp(prefix='rogue-teaching-flow-', dir=ROOT / 'build/smoke'))
    assert directory.resolve().is_relative_to((ROOT / 'build/smoke').resolve())
    fixture = directory / 'resources-fixture'
    guarded = list((source / 'ExcelOutput').glob('*.json'))
    for mission in (4030002, 4010116, 4030003):
        guarded += list((source / f'Config/Level/Mission/{mission}').rglob('*.json'))
    guarded += [source / 'Config/LevelOutput/SharedRuntimeGroup/Groups_P80001_F80001002/LevelGroup_P80001_F80001002_G34.json']
    hashes = {str(p.relative_to(source)): sha(p) for p in guarded}
    junctions = mirror_resources(source, fixture, {})
    for mission in (4030002, 4010116, 4030003):
        shutil.copytree(source / f'Config/Level/Mission/{mission}', fixture / f'Config/Level/Mission/{mission}')
    flags = subprocess.CREATE_NO_WINDOW if os.name == 'nt' else 0
    if junctions:
        plan = directory / 'junctions.json'
        plan.write_text(json.dumps(junctions), 'utf-8')
        helper = directory / 'junctions.ps1'
        helper.write_text("param([string]$Plan)\n$ErrorActionPreference='Stop'\nforeach ($b in (Get-Content -LiteralPath $Plan -Raw | ConvertFrom-Json)) { New-Item -ItemType Junction -Path $b.target -Value $b.source | Out-Null }\n", 'utf-8')
        subprocess.run(['powershell', '-NoProfile', '-NonInteractive', '-File', str(helper), '-Plan', str(plan)],
                       check=True, capture_output=True, creationflags=flags)
    (fixture / 'resource-manifest.json').write_text(json.dumps({
        'clientVersion': '4.6.0', 'testFixture': True, 'rogueTeachingFixture': True,
        'originalTaskDefinitionsUnchanged': True}), 'utf-8')
    classes = directory / 'probe-classes'
    classes.mkdir()
    sources = [ROOT / 'scripts/fixtures/java/org/hkrpg/server/protocol/NativeRogueTeachingFlowProbe.java',
               ROOT / 'scripts/fixtures/java/org/hkrpg/server/gameplay/RogueTeachingFixtureInspector.java']
    result = subprocess.run([str(java.with_name('javac.exe')), '-encoding', 'UTF-8', '-cp', str(jar),
                             '-d', str(classes), *map(str, sources)], capture_output=True, creationflags=flags)
    (directory / 'javac.log').write_bytes(result.stdout + result.stderr)
    assert result.returncode == 0, directory / 'javac.log'
    options = ['-Dfile.encoding=UTF-8', '-Dstdout.encoding=UTF-8', '-Dstderr.encoding=UTF-8',
               '-Djdk.net.unixdomain.tmpdir=' + str(directory / 'tcp-fallback')]
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    credentials = None
    snapshots, inspections = [], []
    stops = 0

    def child(main, arguments, payload=None, cwd=ROOT):
        assert sha(jar) == digest
        result = subprocess.run([str(java), *options, '-cp', str(jar) + os.pathsep + str(classes), main, *arguments],
                                cwd=cwd, input=None if payload is None else json.dumps(payload) + '\n',
                                text=True, encoding='utf-8', capture_output=True, timeout=90, creationflags=flags)
        log = directory / f'child-{len(list(directory.glob("child-*.log")))}.log'
        log.write_text(result.stdout + result.stderr, 'utf-8')
        assert result.returncode == 0, log
        value = json.loads(next(line for line in reversed(result.stdout.splitlines()) if line.startswith('{')))
        assert value['passed'] and not value['clientGameplayVerified']
        return value

    modes = ['create', 'exercise' if args.fixture == 'legacy' else 'fresh']
    if args.fixture == 'legacy':
        modes.append('restore')
    for iteration, mode in enumerate(modes):
        http, mongo, game = ports.free_port(), ports.free_port(), ports.free_port(socket.SOCK_DGRAM)
        config = {'protocol': {'wireVersion': '4.6.0'}, 'resourceDir': str(fixture),
                  'httpServer': {'bindAddress': '127.0.0.1', 'bindPort': http},
                  'gameServer': {'bindAddress': '127.0.0.1', 'bindPort': game},
                  'internalMongoServer': {'address': '127.0.0.1', 'port': mongo, 'filePath': './data/database.mv'}}
        config_path = directory / 'config.json'
        config_path.write_text(json.dumps(config), 'utf-8')
        config_sha = sha(config_path)

        def request(route, data=None):
            req = urllib.request.Request(f'http://127.0.0.1:{http}' + route,
                data=None if data is None else json.dumps(data).encode(), headers={'Content-Type': 'application/json'})
            with opener.open(req, timeout=5) as response:
                return json.load(response)

        with (directory / f'server-{iteration}.log').open('w', encoding='utf-8') as log:
            process = subprocess.Popen([str(java), *options, '-Xmx1536m', '-jar', str(jar), '--no-handbook'],
                cwd=directory, stdin=subprocess.PIPE, stdout=log, stderr=subprocess.STDOUT, creationflags=flags)
            try:
                deadline = time.monotonic() + 90
                while True:
                    assert process.poll() is None, directory / f'server-{iteration}.log'
                    try:
                        if request('/health/ready')['ready']:
                            break
                    except (OSError, ValueError):
                        pass
                    assert time.monotonic() < deadline, directory
                    time.sleep(.2)
                if credentials is None:
                    login = request('/hkrpg_cn/mdk/shield/api/login', {'account': 'teaching-flow-' + args.fixture, 'password': 'local-test'})
                    account = login['data']['account']
                    combo = request('/hkrpg_cn/combo/granter/login/v2/login', {'data': json.dumps({'uid': account['uid'], 'token': account['token']})})
                    credentials = {'accountUid': account['uid'], 'comboToken': combo['data']['combo_token']}
                snapshots.append(child('org.hkrpg.server.protocol.NativeRogueTeachingFlowProbe', [str(game)],
                                       dict(credentials, mode=mode), directory))
                if mode == 'exercise':
                    snapshots.append(child('org.hkrpg.server.protocol.NativeRogueTeachingFlowProbe', [str(game)],
                                           dict(credentials, mode='restore'), directory))
                time.sleep(2)
            finally:
                if process.poll() is None:
                    try:
                        process.stdin.write(b'stop\n'); process.stdin.flush(); process.wait(timeout=30)
                        assert process.returncode == (1000 if os.name == 'nt' else 232), directory
                        stops += 1
                    except BaseException:
                        if process.poll() is None:
                            process.terminate(); process.wait(timeout=15)
                        raise
                    finally:
                        process.stdin.close()
        assert sha(config_path) == config_sha and sha(jar) == digest
        inspect_mode = 'seed-' + args.fixture if mode == 'create' else 'inspect-' + args.fixture
        inspections.append(child('org.hkrpg.server.gameplay.RogueTeachingFixtureInspector',
                                 [str(directory), str(snapshots[0]['uid']), inspect_mode]))
    assert len({s['uid'] for s in snapshots}) == 1
    assert all(sha(source / name) == value for name, value in hashes.items())
    if args.fixture == 'legacy':
        assert len({i['runId'] for i in inspections}) == 1
        assert all(i['rooms'] == inspections[0]['rooms'] for i in inspections)
    print(json.dumps({'passed': True, 'runtimeDirectory': str(directory), 'jarSHA256': digest,
        'fixture': args.fixture, 'snapshots': snapshots, 'inspections': inspections, 'normalStops': stops,
        'sourceHashes': hashes, 'sourceResourcesModified': False, 'originalTaskDefinitionsUnchanged': True,
        'fixturePrerequisiteHistorySeeded': [4030001, 4030002, 1010403, 403000222],
        'legacyArrivalWalletAndBlessingAreExplicitFixtures': args.fixture == 'legacy',
        'targetCompletionPrewritten': False, 'clientGameplayVerified': False, 'allRoomsPlayed': False,
        'crashAtomicityVerified': False}, ensure_ascii=False))


if __name__ == '__main__':
    main()
