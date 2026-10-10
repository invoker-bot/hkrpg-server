"""40431 原生试用队伍入口、实际前置战斗/换层、时间轴上报与H2恢复；目标接取和组加载是隔离夹具。"""
import argparse
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
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--jar', type=Path, default=ROOT / 'build/libs/hkrpg-server.jar')
    parser.add_argument('--output', type=Path, default=ROOT / 'build/default-hybrid-native.json')
    args = parser.parse_args()
    jar, output = args.jar.resolve(), args.output.resolve()
    assert jar.is_file() and jar.is_relative_to(ROOT / 'build') and output.is_relative_to(ROOT / 'build')
    java = next((ROOT / '.tools').glob('jdk-21*/bin/java.exe'), None)
    assert java
    artifact = sha(jar)
    source = ROOT / 'resources/4.6'
    golden = json.loads((ROOT / 'src/test/resources/fixtures/default-hybrid-mission-raid-4.6.json').read_text('utf-8'))
    records = golden['records']
    partial = records['Config/Level/Mission/1040408/MissionInfo_1040408.partial.json'][0]
    row = next(r for r in partial['SubMissionList'] if r['ID'] == 104040816)
    group_path = 'Config/LevelOutput/SharedRuntimeGroup/Groups_P40431_F40431002/LevelGroup_P40431_F40431002_G38.json'
    authored = json.loads((source / group_path).read_text('utf-8'))
    prop = next(p for p in authored['PropList'] if p['ID'] == 300001)
    goal = dict(id=104040816,main=1040408,plane=40431,floor=40431002,group=38,instance=300001,
                prop=prop['PropID'],state=row['ParamStr1'],propDefinition=prop,actions=row['FinishActionList'])
    goals = [goal]
    stage = dict(plane=40431,floor=40431002,entry=40431002,world=501,goals=goals)
    home = dict(plane=20431,floor=20431001,entry=2043101,world=501,pos=dict(x=0,y=0,z=0))
    guarded = set(source.glob('ExcelOutput/*.json')) | {source / 'resource-manifest.json'}
    guarded |= {source / path for path in golden['sourceFiles']}
    authored.update(LoadSide='Server',LoadOnInitial=True)
    authored.pop('LoadCondition',None)
    authored.pop('UnloadCondition',None)
    replacements = {group_path:authored}
    for floor_id in [40431001,40431002]:
        path = f'Config/LevelOutput/RuntimeFloor/P40431_F{floor_id}.json'
        floor = json.loads((source / path).read_text('utf-8'))
        dim = next(d for d in floor['DimensionList'] if d.get('ID',0) == 0)
        initial = floor['GroupInstanceList'][dim.get('StartGroupIndex',floor.get('StartGroupIndex',0))]
        selected = [initial] + [floor['GroupInstanceList'][i] for i in dim['GroupIndexList']
                               if floor_id == 40431002 and floor['GroupInstanceList'][i]['ID'] == 38 and initial['ID'] != 38]
        assert floor_id == 40431001 or len({g['ID'] for g in selected}) == 2
        floor['GroupInstanceList'] = selected
        floor['DimensionList'] = [dict(dim,GroupIndexList=list(range(len(selected))),StartGroupIndex=0)]
        floor['StartGroupIndex'] = 0
        replacements[path] = floor
    hashes = {str(p.relative_to(source)).replace('\\', '/'): sha(p) for p in sorted(guarded)}
    runs = ROOT / 'build/smoke'
    runs.mkdir(exist_ok=True)
    directory = Path(tempfile.mkdtemp(prefix='default-hybrid-', dir=runs))
    fixture = directory / 'resources-fixture'
    bindings = mirror_resources(source, fixture, replacements)
    flags = subprocess.CREATE_NO_WINDOW if os.name == 'nt' else 0
    if bindings:
        plan = directory / 'junctions.json'
        plan.write_text(json.dumps(bindings), 'utf-8')
        helper = directory / 'prepare.ps1'
        helper.write_text("param([string]$Plan)\n$ErrorActionPreference='Stop'\nforeach ($binding in (Get-Content -LiteralPath $Plan -Raw | ConvertFrom-Json)) { New-Item -ItemType Junction -Path $binding.target -Value $binding.source | Out-Null }\n", 'utf-8')
        subprocess.run(['powershell', '-NoProfile', '-NonInteractive', '-File', str(helper), '-Plan', str(plan)], check=True, capture_output=True, creationflags=flags)
    folder = fixture / 'Config/Level/Mission/1040408'
    folder.mkdir(parents=True,exist_ok=True)
    for path, values in records.items():
        if path.startswith('Config/Level/Mission/1040408/'):
            target = fixture / path
            target.write_text(json.dumps(values[0]),'utf-8')
    (fixture / 'resource-manifest.json').write_text(json.dumps(dict(clientVersion='4.6.0', testFixture=True, defaultHybridMissionRaidFixture=True)), 'utf-8')
    http, mongo, game = port(), port(), port(socket.SOCK_DGRAM)
    config = directory / 'config.json'
    config.write_text(json.dumps(dict(protocol=dict(wireVersion='4.6.0'), resourceDir=str(fixture),
        httpServer=dict(bindAddress='127.0.0.1', bindPort=http), gameServer=dict(bindAddress='127.0.0.1', bindPort=game),
        internalMongoServer=dict(address='127.0.0.1', port=mongo, filePath='./data/database.mv'))), 'utf-8')
    config_bytes = config.read_bytes()
    classes = directory / 'probe-classes'
    classes.mkdir()
    sources = [ROOT / 'scripts/fixtures/java/org/hkrpg/server/protocol/NativeDefaultHybridMissionRaidProbe.java',
               ROOT / 'scripts/fixtures/java/org/hkrpg/server/gameplay/DefaultHybridMissionRaidFixtureInspector.java']
    built = subprocess.run([str(java.with_name('javac.exe')), '-encoding', 'UTF-8', '-cp', str(jar), '-d', str(classes), *map(str, sources)], capture_output=True, creationflags=flags)
    (directory / 'javac.log').write_bytes(built.stdout + built.stderr)
    assert built.returncode == 0, directory / 'javac.log'
    options = ['-Dfile.encoding=UTF-8', '-Dstdout.encoding=UTF-8', '-Dstderr.encoding=UTF-8', f'-Djdk.net.unixdomain.tmpdir={directory / "tcp-fallback"}']
    classpath = str(classes) + os.pathsep + str(jar)
    credentials, snapshots, inspections, completed = {}, [], [], []
    stops = 0
    modes = ["seed","enter","resume","pause","return","resume"]

    def request(route, body=None):
        req = urllib.request.Request(f'http://127.0.0.1:{http}' + route, data=None if body is None else json.dumps(body).encode(), headers={'Content-Type': 'application/json'})
        with urllib.request.urlopen(req, timeout=5) as response:
            return json.load(response)

    def inspect(operation, snapshot_mode):
        control = directory / f'inspector-{len(inspections)}.json'
        control.write_text(json.dumps(dict(uid=credentials['uid'], goal=goal, home=home, mode=snapshot_mode)), 'utf-8')
        child = subprocess.run([str(java), *options, '-cp', classpath, 'org.hkrpg.server.gameplay.DefaultHybridMissionRaidFixtureInspector', str(directory), str(control), operation], cwd=ROOT, capture_output=True, text=True, encoding='utf-8', timeout=100, creationflags=flags)
        (directory / f'inspector-{len(inspections)}.log').write_text(child.stdout + child.stderr, 'utf-8')
        assert child.returncode == 0, directory / f'inspector-{len(inspections)}.log'
        value = json.loads(next(line for line in reversed(child.stdout.splitlines()) if line.startswith('{')))
        assert value['passed']
        inspections.append(value)

    for index in range(6):
        mode = modes[index]
        with (directory / f'server-{index}.log').open('wb') as log:
            process = subprocess.Popen([str(java), *options, '-Xmx1536m', '-jar', str(jar), '--no-handbook'], cwd=directory, stdin=subprocess.PIPE, stdout=log, stderr=subprocess.STDOUT, creationflags=flags)
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
                        raise TimeoutError(directory)
                    time.sleep(.2)
                if not credentials:
                    login = request('/hkrpg_cn/mdk/shield/api/login', dict(account='default-hybrid-native', password='local-test'))
                    assert login['retcode'] == 0
                    account = login['data']['account']
                    combo = request('/hkrpg_cn/combo/granter/login/v2/login', dict(data=json.dumps(dict(uid=account['uid'], token=account['token']))))
                    assert combo['retcode'] == 0
                    credentials.update(accountUid=account['uid'], comboToken=combo['data']['combo_token'])
                probe_log = directory / f'probe-{index}.log'
                with probe_log.open('wb') as log_probe:
                    child = subprocess.Popen([str(java), *options, '-cp', classpath, 'org.hkrpg.server.protocol.NativeDefaultHybridMissionRaidProbe', str(game)], cwd=directory, stdin=subprocess.PIPE, stdout=log_probe, stderr=subprocess.STDOUT, creationflags=flags)
                    child.stdin.write((json.dumps(dict(credentials, **stage, seed=index == 0, resume=index == 5, mode=mode, homeEntry=home['entry'])) + '\n').encode())
                    child.stdin.close()
                    deadline, granted = time.monotonic() + 150, False
                    try:
                        while child.poll() is None:
                            if index == 0 and not granted:
                                match = re.search(r'READY_UID=(\d+)', probe_log.read_text('utf-8'))
                                if match:
                                    process.stdin.write(f'give 1001 @{int(match.group(1))}\n'.encode())
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
                value = json.loads(next(line for line in reversed(probe_log.read_text('utf-8').splitlines()) if line.startswith('{')))
                assert value['passed']
                credentials.setdefault('uid', value['uid'])
                assert credentials['uid'] == value['uid']
                snapshots.append(value)
                if index == 1:
                    assert value['actualStateReports'] == 1 and value['actualPrecedingBattleVictories'] == 1
                    completed.append(104040816)
            finally:
                if process.poll() is None:
                    try:
                        process.stdin.write(b'stop\n')
                        process.stdin.flush()
                        process.wait(timeout=45)
                        assert process.returncode == (1000 if os.name == 'nt' else 232), directory
                        stops += 1
                    finally:
                        if process.poll() is None:
                            process.terminate()
                            process.wait(timeout=15)
                        process.stdin.close()
        inspect('seed' if index == 0 else 'inspect',mode)
        print(json.dumps(dict(phase=index,mode=mode,normalStops=stops)),flush=True)
    assert completed == [104040816]
    assert sha(jar) == artifact and config.read_bytes() == config_bytes
    for path, expected in hashes.items():
        assert sha(source / path) == expected, path
    report = dict(passed=True, artifactSha256=artifact, runtimeDirectory=str(directory), snapshots=snapshots, inspections=inspections,
        nativeRequests=sum(s['nativeRequests'] for s in snapshots), actualStateReports=1, actualPrecedingBattleVictories=1,
        oldContextRejections=sum(s.get('oldContextRejections', 0) for s in snapshots), normalStops=stops,
        sameUIDNormalH2ProcessRestarts=5, completedOriginalRaidGoalIds=completed,
        parentAndTargetAcceptanceAreFixtures=True, homeIsFixture=True, groupLoadGatesRelaxed=True, originalPropDefinitionsUnmodified=True,
        targetCompletionPrewritten=False, timelineProofPrewritten=False, sourceResourceHashes=hashes,
        sourceResourcesModified=False, operatorDataModified=False, clientGameplayVerified=False,
        animationStateMachineVerified=False, wholeGameComplete=False)
    output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n', 'utf-8')
    print(json.dumps(dict(passed=True, nativeRequests=report['nativeRequests'], actualStateReports=1, actualPrecedingBattleVictories=1, normalStops=stops, runtimeDirectory=str(directory)), ensure_ascii=False))


if __name__ == '__main__':
    main()
