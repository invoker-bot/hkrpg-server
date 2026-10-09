"""原始4000301正式/试用角色入口、可选创建、实际三项奖励与正常H2重启；前置及剧情/战斗结果为明确夹具。"""
import argparse
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


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def free_port(kind=socket.SOCK_STREAM):
    with socket.socket(type=kind) as sock:
        sock.bind(('127.0.0.1', 0))
        return sock.getsockname()[1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--resources', type=Path, default=ROOT / 'resources/4.6')
    parser.add_argument('--output', type=Path, default=ROOT / 'build/paid-mission-raid-native.json')
    args = parser.parse_args()
    source = args.resources.resolve(strict=True)
    output = args.output.resolve()
    assert output.is_relative_to(ROOT / 'build')
    jar = ROOT / 'build/libs/hkrpg-server.jar'
    java = next((ROOT / '.tools').glob('jdk-21*/bin/java.exe'), None)
    if java is None and os.environ.get('JAVA_HOME'):
        java = Path(os.environ['JAVA_HOME']) / 'bin/java.exe'
    assert java and java.is_file() and jar.is_file()
    classes = ROOT / 'build/classes/java/test'
    assert (classes / 'org/hkrpg/server/gameplay/PaidMissionRaidFixtureInspector.class').is_file()
    artifact_sha = digest(jar)
    document = json.loads((ROOT / 'src/test/resources/fixtures/paid-mission-raid-4.6.json').read_text('utf-8'))
    guarded = {'resource-manifest.json'}
    for name, expected in document['sourceFiles'].items():
        assert hashlib.sha256((source / name).read_bytes().replace(b'\r\n', b'\n')).hexdigest() == expected, name
        guarded.add(name)
    guarded.update(p.relative_to(source).as_posix() for p in (source / 'ExcelOutput').glob('*.json'))
    guarded.update(p.relative_to(source).as_posix() for p in (source / 'Config/BattleLineupPage/SkillTreePointPreset').glob('*.json'))
    for main_id in [2000703]:
        guarded.update(p.relative_to(source).as_posix() for p in (source / f'Config/Level/Mission/{main_id}').glob('*.json'))
    for plane, floor in [(20001, 20001001), (40003, 40003001)]:
        guarded.add(f'Config/LevelOutput/RuntimeFloor/P{plane}_F{floor}.json')
        guarded.update(p.relative_to(source).as_posix() for p in
            (source / f'Config/LevelOutput/SharedRuntimeGroup/Groups_P{plane}_F{floor}').glob('*.json'))
    hashes = {name: digest(source / name) for name in sorted(guarded)}
    runs = ROOT / 'build/smoke'
    runs.mkdir(exist_ok=True)
    directory = Path(tempfile.mkdtemp(prefix='paid-mission-raid-', dir=runs))
    fixture = directory / 'resources-fixture'
    junctions = mirror_resources(source, fixture, {})
    for main_id in [2000703]:
        target = fixture / f'Config/Level/Mission/{main_id}'
        target.mkdir()
        for original in (source / f'Config/Level/Mission/{main_id}').glob('*.json'):
            shutil.copyfile(original, target / original.name)
    flags = subprocess.CREATE_NO_WINDOW if os.name == 'nt' else 0
    if junctions:
        plan = directory / 'junction-plan.json'
        plan.write_text(json.dumps(junctions), 'utf-8')
        helper = directory / 'prepare-fixture.ps1'
        helper.write_text("param([string]$Plan)\n$ErrorActionPreference='Stop'\nforeach ($binding in (Get-Content -LiteralPath $Plan -Raw | ConvertFrom-Json)) { New-Item -ItemType Junction -Path $binding.target -Value $binding.source | Out-Null }\n", 'utf-8')
        subprocess.run(['powershell', '-NoProfile', '-NonInteractive', '-File', str(helper), '-Plan', str(plan)], check=True, capture_output=True, creationflags=flags)
    (fixture / 'resource-manifest.json').write_text(json.dumps({'clientVersion': '4.6.0',
        'testFixture': True, 'paidMissionRaidFixture': True,
        'originalMainSubScriptRaidRowsAndFinalListsUnchanged': True,
        'parentAcceptanceAndDialogueBattleOutcomesAreIsolatedFixtures': True}), 'utf-8')
    http, mongo, game = free_port(), free_port(), free_port(socket.SOCK_DGRAM)
    (directory / 'config.json').write_text(json.dumps({'protocol': {'wireVersion': '4.6.0'},
        'resourceDir': str(fixture), 'httpServer': {'bindAddress': '127.0.0.1', 'bindPort': http},
        'gameServer': {'bindAddress': '127.0.0.1', 'bindPort': game},
        'internalMongoServer': {'address': '127.0.0.1', 'port': mongo, 'filePath': './data/database.mv'}}), 'utf-8')
    probes = directory / 'probe-classes'
    probes.mkdir()
    compiled = subprocess.run([str(java.with_name('javac.exe')), '-encoding', 'UTF-8', '-cp', str(jar), '-d', str(probes),
        str(ROOT / 'scripts/fixtures/java/org/hkrpg/server/protocol/NativePaidMissionRaidProbe.java')], capture_output=True, creationflags=flags)
    (directory / 'javac.log').write_bytes(compiled.stdout + compiled.stderr)
    assert compiled.returncode == 0, directory / 'javac.log'
    fallback = '-Djdk.net.unixdomain.tmpdir=' + str(directory / 'tcp-fallback')
    encoding = ['-Dfile.encoding=UTF-8', '-Dstdout.encoding=UTF-8', '-Dstderr.encoding=UTF-8']
    snapshots, inspections, normal_stops = [], [], 0
    accounts = [{"formal": False}, {"formal": True}]
    credentials = None

    def request(route, body=None):
        req = urllib.request.Request(f'http://127.0.0.1:{http}' + route,
            data=None if body is None else json.dumps(body).encode(), headers={'Content-Type': 'application/json'})
        with urllib.request.urlopen(req, timeout=5) as response:
            return json.load(response)

    def probe(mode):
        assert digest(jar) == artifact_sha
        child = subprocess.run([str(java), *encoding, fallback, '-cp', str(jar) + os.pathsep + str(probes),
            'org.hkrpg.server.protocol.NativePaidMissionRaidProbe', str(game)], cwd=directory,
            input=json.dumps(dict(credentials, mode=mode)) + '\n', text=True, encoding='utf-8',
            capture_output=True, timeout=90, creationflags=flags)
        log = directory / f'probe-{len(snapshots)}-{mode}.log'
        log.write_text(child.stdout + child.stderr, 'utf-8')
        assert child.returncode == 0, log
        snapshot = json.loads(next(line for line in child.stdout.splitlines() if line.startswith('{"')))
        assert snapshot['passed'] and not snapshot['clientGameplayVerified']
        if 'uid' not in credentials:
            credentials['uid'] = snapshot['uid']
        assert credentials['uid'] == snapshot['uid']
        snapshots.append(snapshot)

    def inspect(mode):
        file = directory / 'player-fixture.json'
        file.write_text(json.dumps(credentials), 'utf-8')
        child = subprocess.run([str(java), *encoding, fallback, '-cp', str(classes) + os.pathsep + str(jar),
            'org.hkrpg.server.gameplay.PaidMissionRaidFixtureInspector', str(directory), str(file), mode],
            cwd=ROOT, text=True, encoding='utf-8', capture_output=True, timeout=100, creationflags=flags)
        log = directory / f'inspector-{len(inspections)}-{mode}.log'
        log.write_text(child.stdout + child.stderr, 'utf-8')
        assert child.returncode == 0, log
        result = json.loads(next(line for line in reversed(child.stdout.splitlines()) if line.startswith('{"')))
        assert result['passed'] and result['changedPlayers'] == (1 if mode in {'prepare', 'acceptSource', 'completeFinal'} else 0)
        inspections.append(result)

    for iteration, mode in enumerate(['pending', 'entry', 'source', 'resume', 'finished', 'final']):
        with (directory / f'server-{iteration}.log').open('w', encoding='utf-8') as log:
            process = subprocess.Popen([str(java), *encoding, '-Xmx1536m', fallback, '-jar', str(jar), '--no-handbook'],
                cwd=directory, stdin=subprocess.PIPE, stdout=log, stderr=subprocess.STDOUT, creationflags=flags)
            try:
                deadline = time.monotonic() + 100
                while True:
                    assert process.poll() is None, directory / f'server-{iteration}.log'
                    try:
                        if request('/health/ready')['ready']:
                            break
                    except (OSError, urllib.error.URLError):
                        pass
                    if time.monotonic() > deadline:
                        raise RuntimeError('Startup timed out: ' + str(directory))
                    time.sleep(.2)
                for credentials in accounts:
                    if 'comboToken' not in credentials:
                        name = 'paid-mission-raid-4000301-' + ('formal' if credentials['formal'] else 'trial')
                        login = request('/hkrpg_cn/mdk/shield/api/login', {'account': name, 'password': 'local-test'})
                        assert login['retcode'] == 0
                        account = login['data']['account']
                        combo = request('/hkrpg_cn/combo/granter/login/v2/login', {'data': json.dumps({'uid': account['uid'], 'token': account['token']})})
                        assert combo['retcode'] == 0
                        credentials.update(accountUid=account['uid'], comboToken=combo['data']['combo_token'])
                    probe(mode)
                    if mode == 'source':
                        for extra in ['active', 'pause', 'return']:
                            time.sleep(.25)
                            probe(extra)
                    if mode == 'finished':
                        time.sleep(.25)
                        probe('leaveFinished')
            finally:
                if process.poll() is None:
                    try:
                        process.stdin.write(b'stop\n')
                        process.stdin.flush()
                        process.wait(timeout=45)
                        assert process.returncode == (1000 if os.name == 'nt' else 232), directory
                        normal_stops += 1
                    except BaseException:
                        if process.poll() is None:
                            process.terminate()
                            process.wait(timeout=15)
                        raise
                    finally:
                        process.stdin.close()
        for credentials in accounts:
            inspect('final' if mode == 'finished' else 'finalResume' if mode == 'final' else mode)
            if mode == 'pending': inspect('prepare')
            if mode == 'entry': inspect('acceptSource')
            if mode == 'resume': inspect('completeFinal')
    assert normal_stops == 6 and len(snapshots) == 20 and len(inspections) == 18
    assert len({s['uid'] for s in snapshots}) == 2
    for formal in [False, True]:
        saved = [s for s in inspections if s['formal'] == formal]
        assert next(s['run'] for s in saved if s['mode'] == 'source') == next(s['run'] for s in saved if s['mode'] == 'resume')
    assert hashes == {name: digest(source / name) for name in guarded}
    assert digest(jar) == artifact_sha
    report = {'passed': True, 'runtimeDirectory': str(directory), 'artifactSha256': artifact_sha,
        'snapshots': snapshots, 'inspections': inspections, 'normalStops': normal_stops,
        'sameUIDNormalH2ProcessRestarts': 5, 'distinctUIDs': 2, 'bothOriginalFormalAndTrialAlternativesVerified': True, 'sourceResourceHashes': hashes,
        'sourceResourcesModified': False, 'sourceCommit': document['sourceCommit'],
        'isolatedParentAcceptanceAndDialogueBattleOutcomes': True,
        'originalMainSubScriptRaidRowsAndFinalListsUnchanged': True,
        'clientGameplayVerified': False, 'allSourceRaidTasksVerified': False,
        'fullStoryVerified': False, 'combatInputReplayVerified': False, 'crashAtomicityVerified': False}
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n', 'utf-8')
    print(json.dumps({'passed': True, 'report': str(output), 'runtimeDirectory': str(directory),
        'nativeRequests': sum(s['requests'] for s in snapshots), 'snapshots': len(snapshots),
        'normalStops': normal_stops, 'offlineH2Operations': len(inspections),
        'sourceResourcesUnchanged': len(hashes), 'artifactSha256': artifact_sha}))


if __name__ == '__main__':
    main()
