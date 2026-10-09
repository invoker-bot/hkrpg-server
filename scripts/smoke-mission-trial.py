"""原始100010110角色创建、队伍查询、重连与正常H2恢复；接取/终止为明确隔离夹具。"""
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
    parser.add_argument('--output', type=Path, default=ROOT / 'build/mission-trial-native.json')
    parser.add_argument('--removal', action='store_true', help='保留完整原始任务，验证100010123退场与脚本NPC')
    args = parser.parse_args()
    source = args.resources.resolve(strict=True)
    output = args.output.resolve()
    assert output.is_relative_to(ROOT / 'build'), '报告必须位于本工程 build'
    jar = ROOT / 'build/libs/hkrpg-server.jar'
    classes = ROOT / 'build/classes/java/test'
    java = next((ROOT / '.tools').glob('jdk-21*/bin/java.exe'), None)
    if java is None and os.environ.get('JAVA_HOME'):
        java = Path(os.environ['JAVA_HOME']) / 'bin/java.exe'
    assert java and java.is_file() and jar.is_file()
    assert (classes / 'org/hkrpg/server/gameplay/MissionTrialFixtureInspector.class').is_file()
    artifact_sha = digest(jar)
    fixtures = ROOT / 'src/test/resources/fixtures'
    originals = json.loads((fixtures / 'get-trial-avatar-missions-4.6.json').read_text('utf-8'))
    original = next(c for c in originals['cases'] if c['sub']['ID'] == 100010110)
    skills = json.loads((fixtures / 'mission-trial-skills-4.6.json').read_text('utf-8'))
    for document in [originals, skills]:
        for name, expected in document['sourceFiles'].items():
            assert hashlib.sha256((source / name).read_bytes().replace(b'\r\n', b'\n')).hexdigest() == expected, name
    guarded = set(originals['sourceFiles']) | set(skills['sourceFiles']) | {'resource-manifest.json'}
    guarded.update(p.relative_to(source).as_posix() for p in (source / 'ExcelOutput').glob('*.json'))
    guarded.update(p.relative_to(source).as_posix() for p in (source / 'Config/BattleLineupPage/SkillTreePointPreset').glob('*.json'))
    guarded.update(p.relative_to(source).as_posix() for p in (source / 'Config/LevelOutput/SharedRuntimeGroup/Groups_P20001_F20001001').glob('*.json'))
    guarded.add('Config/LevelOutput/RuntimeFloor/P20001_F20001001.json')
    if args.removal:
        removal = json.loads((fixtures / 'mission-trial-removal-4.6.json').read_text('utf-8'))
        for name, expected in removal['sourceFiles'].items():
            assert hashlib.sha256((source / name).read_bytes().replace(b'\r\n', b'\n')).hexdigest() == expected, name
            guarded.add(name)
        guarded.update(p.relative_to(source).as_posix() for p in (source / 'Config/Level/Mission/1000101').glob('*.json'))
    hashes = {name: digest(source / name) for name in sorted(guarded)}
    runs = ROOT / 'build/smoke'
    runs.mkdir(exist_ok=True)
    directory = Path(tempfile.mkdtemp(prefix='mission-trial-', dir=runs))
    fixture = directory / 'resources-fixture'
    mains = json.loads((source / 'ExcelOutput/MainMission.json').read_text('utf-8'))
    main = next(m for m in mains if m['MainMissionID'] == 1000101)
    if not args.removal:
        main.update(TakeOperation='And', BeginOperation='And', TakeParam=[{'Type': 'Manual'}],
                    BeginParam=[{'Type': 'Auto'}], RewardID=0, SubRewardList=[])
    junctions = mirror_resources(source, fixture, {} if args.removal else {'ExcelOutput/MainMission.json': mains})
    folder = fixture / 'Config/Level/Mission/1000101'
    folder.mkdir(parents=True)
    (folder / 'MissionInfo_1000101.json').write_text(json.dumps({
        'MainMissionID': 1000101, 'StartSubMissionList': [100010110],
        'FinishSubMissionList': [199988999]}), 'utf-8')
    (folder / 'MissionInfo_1000101.partial.json').write_text(json.dumps({
        'SubMissionList': [original['sub']]}), 'utf-8')
    script = original['sub']['MissionJsonPath']
    assert json.loads((source / script).read_text('utf-8')) == original['script']
    (fixture / script).write_bytes((source / script).read_bytes())
    if args.removal:
        for original_file in (source / 'Config/Level/Mission/1000101').glob('*.json'):
            shutil.copyfile(original_file, folder / original_file.name)
    flags = subprocess.CREATE_NO_WINDOW if os.name == 'nt' else 0
    if junctions:
        plan = directory / 'junction-plan.json'
        plan.write_text(json.dumps(junctions), 'utf-8')
        helper = directory / 'prepare-fixture.ps1'
        helper.write_text("param([string]$Plan)\n$ErrorActionPreference='Stop'\nforeach ($binding in (Get-Content -LiteralPath $Plan -Raw | ConvertFrom-Json)) { New-Item -ItemType Junction -Path $binding.target -Value $binding.source | Out-Null }\n", 'utf-8')
        subprocess.run(['powershell', '-NoProfile', '-NonInteractive', '-File', str(helper), '-Plan', str(plan)],
                       check=True, capture_output=True, creationflags=flags)
    (fixture / 'resource-manifest.json').write_text(json.dumps({
        'clientVersion': '4.6.0', 'testFixture': True, 'missionTrialFixture': True,
        'missionTrialRemovalFixture': args.removal,
        'originalMainSubScriptRowsAndFinalListsUnchanged': args.removal,
        'acceptedMissionFixture': 100010110, 'syntheticPendingFinalSubmission': 0 if args.removal else 199988999,
        'originalTaskAndActorResourcesUnchanged': True}), 'utf-8')
    http, mongo, game = free_port(), free_port(), free_port(socket.SOCK_DGRAM)
    (directory / 'config.json').write_text(json.dumps({
        'protocol': {'wireVersion': '4.6.0'}, 'resourceDir': str(fixture),
        'httpServer': {'bindAddress': '127.0.0.1', 'bindPort': http},
        'gameServer': {'bindAddress': '127.0.0.1', 'bindPort': game},
        'internalMongoServer': {'address': '127.0.0.1', 'port': mongo, 'filePath': './data/database.mv'}}), 'utf-8')
    probe_classes = directory / 'probe-classes'
    probe_classes.mkdir()
    compiled = subprocess.run([str(java.with_name('javac.exe')), '-encoding', 'UTF-8', '-cp', str(jar),
        '-d', str(probe_classes), str(ROOT / 'scripts/fixtures/java/org/hkrpg/server/protocol/NativeMissionTrialProbe.java')],
        capture_output=True, creationflags=flags)
    (directory / 'javac.log').write_bytes(compiled.stdout + compiled.stderr)
    assert compiled.returncode == 0, directory / 'javac.log'
    fallback = '-Djdk.net.unixdomain.tmpdir=' + str(directory / 'tcp-fallback')
    encoding = ['-Dfile.encoding=UTF-8', '-Dstdout.encoding=UTF-8', '-Dstderr.encoding=UTF-8']
    command = [str(java), *encoding, '-Xmx1536m', fallback, '-jar', str(jar), '--no-handbook']
    credentials = None
    snapshots, inspections, normal_stops = [], [], 0

    def request(route, body=None):
        req = urllib.request.Request(f'http://127.0.0.1:{http}' + route,
            data=None if body is None else json.dumps(body).encode(), headers={'Content-Type': 'application/json'})
        with urllib.request.urlopen(req, timeout=5) as response:
            return json.load(response)

    def probe(mode):
        assert digest(jar) == artifact_sha
        child = subprocess.run([str(java), *encoding, fallback, '-cp', str(jar) + os.pathsep + str(probe_classes),
            'org.hkrpg.server.protocol.NativeMissionTrialProbe', str(game)], cwd=directory,
            input=json.dumps(dict(credentials, mode=mode)) + '\n', text=True,
            encoding='utf-8', capture_output=True, timeout=90, creationflags=flags)
        log = directory / f'probe-{len(snapshots)}-{mode}.log'
        log.write_text(child.stdout + child.stderr, 'utf-8')
        assert child.returncode == 0, log
        snapshot = json.loads(next(line for line in child.stdout.splitlines() if line.startswith('{"')))
        assert snapshot['passed'] and not snapshot['clientGameplayVerified']
        snapshots.append(snapshot)

    def inspect(mode):
        child = subprocess.run([str(java), *encoding, fallback, '-cp', str(classes) + os.pathsep + str(jar),
            'org.hkrpg.server.gameplay.MissionTrialFixtureInspector', str(directory), str(snapshots[0]['uid']), mode],
            cwd=ROOT, text=True, encoding='utf-8', capture_output=True, timeout=100, creationflags=flags)
        log = directory / f'inspector-{len(inspections)}-{mode}.log'
        log.write_text(child.stdout + child.stderr, 'utf-8')
        assert child.returncode == 0, log
        result = json.loads(next(line for line in reversed(child.stdout.splitlines()) if line.startswith('{"')))
        assert result['passed'] and result['changedPlayers'] == (1 if mode in {'prepare', 'close', 'remove'} else 0)
        inspections.append(result)

    modes = ['pending', 'active', 'resume', 'removed', 'removedResume'] if args.removal else ['pending', 'active', 'resume', 'closed']
    for iteration, mode in enumerate(modes):
        with (directory / f'server-{iteration}.log').open('w', encoding='utf-8') as log:
            process = subprocess.Popen(command, cwd=directory, stdin=subprocess.PIPE,
                                       stdout=log, stderr=subprocess.STDOUT, creationflags=flags)
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
                if credentials is None:
                    login = request('/hkrpg_cn/mdk/shield/api/login', {'account': 'cn-46-mission-trial', 'password': 'local-test'})
                    assert login['retcode'] == 0
                    account = login['data']['account']
                    combo = request('/hkrpg_cn/combo/granter/login/v2/login', {
                        'data': json.dumps({'uid': account['uid'], 'token': account['token']})})
                    assert combo['retcode'] == 0
                    credentials = {'accountUid': account['uid'], 'comboToken': combo['data']['combo_token']}
                probe(mode)
                if mode == 'active' or args.removal and mode == 'removed':
                    time.sleep(.25)
                    probe(mode)
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
        inspect(mode)
        if mode == 'pending': inspect('prepare')
        if mode == 'resume': inspect('remove' if args.removal else 'close')
    assert normal_stops == (5 if args.removal else 4) and len(snapshots) == (7 if args.removal else 5) and len(inspections) == (7 if args.removal else 6)
    assert len({s['uid'] for s in snapshots}) == 1
    assert inspections[2]['party'] == inspections[3]['party']
    assert hashes == {name: digest(source / name) for name in guarded}
    assert digest(jar) == artifact_sha
    report = {'passed': True, 'runtimeDirectory': str(directory), 'artifactSha256': artifact_sha,
              'snapshots': snapshots, 'inspections': inspections, 'normalStops': normal_stops,
              'sameUIDNormalH2ProcessRestarts': 4 if args.removal else 3, 'sourceResourceHashes': hashes,
              'sourceResourcesModified': False, 'sourceCommit': originals['sourceCommit'],
              'fixture': {'acceptedMission': 100010110, 'worldLevel': 0,
                          'syntheticPendingFinalSubmission': 0 if args.removal else 199988999, 'mainTermination': 'none' if args.removal else 'offline cancellation',
                          'mainTakeAndRewards': 'original' if args.removal else 'isolated manual acceptance and no reward',
                          'explicitRemovalDialogueOutcomeFixture': 100010116 if args.removal else 0,
                          'originalMainSubScriptRowsAndFinalListsUnchanged': args.removal,
                          'originalSubTaskActorSkillsEquipmentAndFloorUnchanged': True},
              'clientGameplayVerified': False, 'allSourceRaidTasksVerified': False,
              'fullStoryVerified': False, 'crashAtomicityVerified': False}
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n', 'utf-8')
    print(json.dumps({'passed': True, 'report': str(output), 'runtimeDirectory': str(directory),
                     'nativeRequests': sum(s['requests'] for s in snapshots), 'snapshots': len(snapshots),
                     'normalStops': normal_stops, 'offlineH2Operations': len(inspections),
                     'sourceResourcesUnchanged': len(hashes), 'artifactSha256': artifact_sha}, ensure_ascii=False))


if __name__ == '__main__':
    main()
