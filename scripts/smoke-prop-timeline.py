"""七条原始世界时间轴目标：原生上报、正常关服、同UID重启、只读H2/Morphia核验；接取/场景/加载门槛是隔离夹具。"""
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
    parser.add_argument('--output', type=Path, default=ROOT / 'build/prop-timeline-native.json')
    args = parser.parse_args()
    jar, output = args.jar.resolve(), args.output.resolve()
    assert jar.is_file() and jar.is_relative_to(ROOT / 'build') and output.is_relative_to(ROOT / 'build')
    java = next((ROOT / '.tools').glob('jdk-21*/bin/java.exe'), None)
    assert java
    artifact = sha(jar)
    source = ROOT / 'resources/4.6'
    golden = json.loads((ROOT / 'src/test/resources/fixtures/prop-timeline-4.6.json').read_text('utf-8'))
    subs = [row for row in golden['subs'] if row['LevelPlaneID'] < 40000]
    assert len(subs) == 7
    goals, stages, replacements = [], {}, {}
    guarded = set(source.glob('ExcelOutput/*.json')) | {source / 'resource-manifest.json'}
    guarded |= {source / path for path in golden['sourceFiles']}
    for row in subs:
        plane, floor, group, instance = row['LevelPlaneID'], row['LevelFloorID'], row['ParamInt1'], row['ParamInt2']
        group_path = f'Config/LevelOutput/SharedRuntimeGroup/Groups_P{plane}_F{floor}/LevelGroup_P{plane}_F{floor}_G{group}.json'
        authored = json.loads((source / group_path).read_text('utf-8'))
        prop = next(p for p in authored['PropList'] if p['ID'] == instance)
        goal = dict(id=row['ID'], main=row['MainMissionID'], plane=plane, floor=floor, group=group, instance=instance,
                    prop=prop['PropID'], state=row['ParamStr1'], propDefinition=prop, actions=row['FinishActionList'])
        goals.append(goal)
        entries = [r for r in golden['entryRows'] if r['PlaneID'] == plane and r['FloorID'] == floor]
        entry = min(entries, key=lambda r: r['ID'])
        world = next(p for p in golden['planeRows'] if p['PlaneID'] == plane)['WorldID']
        stage = stages.setdefault(floor, dict(plane=plane, floor=floor, entry=entry['ID'], world=world,
                                             pos={k.lower(): int(prop.get('Pos' + k.upper(), 0) * 1000) for k in ['x', 'y', 'z']}, goals=[]))
        stage['goals'].append(goal)
        authored.update(LoadSide='Server', LoadOnInitial=True)
        authored.pop('LoadCondition', None)
        authored.pop('UnloadCondition', None)
        replacements[group_path] = authored
    for stage in stages.values():
        path = f"Config/LevelOutput/RuntimeFloor/P{stage['plane']}_F{stage['floor']}.json"
        guarded.add(source / path)
        floor = json.loads((source / path).read_text('utf-8'))
        dim = next(d for d in floor['DimensionList'] if d.get('ID', 0) == 0)
        initial = floor['GroupInstanceList'][dim.get('StartGroupIndex', floor.get('StartGroupIndex', 0))]
        selected = [initial] + [floor['GroupInstanceList'][i] for i in dim['GroupIndexList']
                               if floor['GroupInstanceList'][i]['ID'] in {g['group'] for g in stage['goals']}
                               and floor['GroupInstanceList'][i]['ID'] != initial['ID']]
        assert len(selected) == len(stage['goals']) + (initial['ID'] not in {g['group'] for g in stage['goals']})
        assert {g['group'] for g in stage['goals']}.issubset({floor['GroupInstanceList'][i]['ID'] for i in dim['GroupIndexList']})
        floor['GroupInstanceList'] = selected
        floor['DimensionList'] = [dict(dim, GroupIndexList=list(range(len(selected))), StartGroupIndex=0)]
        floor['StartGroupIndex'] = 0
        replacements[path] = floor
    rows = json.loads((source / 'ExcelOutput/MainMission.json').read_text('utf-8'))
    for row in rows:
        if row['MainMissionID'] in {g['main'] for g in goals}:
            row.update(TakeOperation='And', TakeParam=[{'Type': 'Manual'}], BeginOperation='And', BeginParam=[{'Type': 'Auto'}])
    replacements['ExcelOutput/MainMission.json'] = rows
    entrances = json.loads((source / 'ExcelOutput/MapEntrance.json').read_text('utf-8'))
    for row in entrances:
        if row['ID'] in {s['entry'] for s in stages.values()}:
            row.update(BeginMainMissionList=[], FinishMainMissionList=[], FinishSubMissionList=[])
    replacements['ExcelOutput/MapEntrance.json'] = entrances
    hashes = {str(p.relative_to(source)).replace('\\', '/'): sha(p) for p in sorted(guarded)}
    runs = ROOT / 'build/smoke'
    runs.mkdir(exist_ok=True)
    directory = Path(tempfile.mkdtemp(prefix='prop-timeline-', dir=runs))
    fixture = directory / 'resources-fixture'
    bindings = mirror_resources(source, fixture, replacements)
    flags = subprocess.CREATE_NO_WINDOW if os.name == 'nt' else 0
    if bindings:
        plan = directory / 'junctions.json'
        plan.write_text(json.dumps(bindings), 'utf-8')
        helper = directory / 'prepare.ps1'
        helper.write_text("param([string]$Plan)\n$ErrorActionPreference='Stop'\nforeach ($binding in (Get-Content -LiteralPath $Plan -Raw | ConvertFrom-Json)) { New-Item -ItemType Junction -Path $binding.target -Value $binding.source | Out-Null }\n", 'utf-8')
        subprocess.run(['powershell', '-NoProfile', '-NonInteractive', '-File', str(helper), '-Plan', str(plan)], check=True, capture_output=True, creationflags=flags)
    for main in sorted({g['main'] for g in goals}):
        folder = fixture / f'Config/Level/Mission/{main}'
        folder.mkdir(parents=True, exist_ok=True)
        header = next(h for h in golden['headers'] if h['MainMissionID'] == main)
        selected = [r for r in [*subs, *golden['linkedSubs']] if r['MainMissionID'] == main]
        (folder / f'MissionInfo_{main}.json').write_text(json.dumps(header), 'utf-8')
        (folder / f'MissionInfo_{main}.partial.json').write_text(json.dumps(dict(SubMissionList=selected)), 'utf-8')
        for row in selected:
            if row.get('MissionJsonPath'):
                (fixture / row['MissionJsonPath']).write_bytes((source / row['MissionJsonPath']).read_bytes())
    (fixture / 'resource-manifest.json').write_text(json.dumps(dict(clientVersion='4.6.0', testFixture=True, propTimelineFixture=True)), 'utf-8')
    http, mongo, game = port(), port(), port(socket.SOCK_DGRAM)
    config = directory / 'config.json'
    config.write_text(json.dumps(dict(protocol=dict(wireVersion='4.6.0'), resourceDir=str(fixture),
        httpServer=dict(bindAddress='127.0.0.1', bindPort=http), gameServer=dict(bindAddress='127.0.0.1', bindPort=game),
        internalMongoServer=dict(address='127.0.0.1', port=mongo, filePath='./data/database.mv'))), 'utf-8')
    config_bytes = config.read_bytes()
    classes = directory / 'probe-classes'
    classes.mkdir()
    sources = [ROOT / 'scripts/fixtures/java/org/hkrpg/server/protocol/NativePropTimelineProbe.java',
               ROOT / 'scripts/fixtures/java/org/hkrpg/server/gameplay/PropTimelineFixtureInspector.java']
    built = subprocess.run([str(java.with_name('javac.exe')), '-encoding', 'UTF-8', '-cp', str(jar), '-d', str(classes), *map(str, sources)], capture_output=True, creationflags=flags)
    (directory / 'javac.log').write_bytes(built.stdout + built.stderr)
    assert built.returncode == 0, directory / 'javac.log'
    options = ['-Dfile.encoding=UTF-8', '-Dstdout.encoding=UTF-8', '-Dstderr.encoding=UTF-8', f'-Djdk.net.unixdomain.tmpdir={directory / "tcp-fallback"}']
    classpath = str(classes) + os.pathsep + str(jar)
    credentials, snapshots, inspections, completed = {}, [], [], []
    stops = 0
    stage_list = list(stages.values())

    def request(route, body=None):
        req = urllib.request.Request(f'http://127.0.0.1:{http}' + route, data=None if body is None else json.dumps(body).encode(), headers={'Content-Type': 'application/json'})
        with urllib.request.urlopen(req, timeout=5) as response:
            return json.load(response)

    def inspect(mode, stage):
        control = directory / f'inspector-{len(inspections)}.json'
        control.write_text(json.dumps(dict(uid=credentials['uid'], allGoals=goals, linkedSubs=[r['ID'] for r in golden['linkedSubs']], completed=completed, stage=stage)), 'utf-8')
        child = subprocess.run([str(java), *options, '-cp', classpath, 'org.hkrpg.server.gameplay.PropTimelineFixtureInspector', str(directory), str(control), mode], cwd=ROOT, capture_output=True, text=True, encoding='utf-8', timeout=100, creationflags=flags)
        (directory / f'inspector-{len(inspections)}.log').write_text(child.stdout + child.stderr, 'utf-8')
        assert child.returncode == 0, directory / f'inspector-{len(inspections)}.log'
        value = json.loads(next(line for line in reversed(child.stdout.splitlines()) if line.startswith('{')))
        assert value['passed'] and value['completed'] == len(completed)
        inspections.append(value)

    for index in range(6):
        stage = stage_list[min(max(index - 1, 0), len(stage_list) - 1)]
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
                    login = request('/hkrpg_cn/mdk/shield/api/login', dict(account='prop-timeline-native', password='local-test'))
                    assert login['retcode'] == 0
                    account = login['data']['account']
                    combo = request('/hkrpg_cn/combo/granter/login/v2/login', dict(data=json.dumps(dict(uid=account['uid'], token=account['token']))))
                    assert combo['retcode'] == 0
                    credentials.update(accountUid=account['uid'], comboToken=combo['data']['combo_token'])
                probe_log = directory / f'probe-{index}.log'
                with probe_log.open('wb') as log_probe:
                    child = subprocess.Popen([str(java), *options, '-cp', classpath, 'org.hkrpg.server.protocol.NativePropTimelineProbe', str(game)], cwd=directory, stdin=subprocess.PIPE, stdout=log_probe, stderr=subprocess.STDOUT, creationflags=flags)
                    child.stdin.write((json.dumps(dict(credentials, **stage, seed=index == 0, resume=index == 5)) + '\n').encode())
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
                if index in range(1, 5):
                    assert value['actualStateReports'] == len(stage['goals'])
                    completed.extend(g['id'] for g in stage['goals'])
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
        if index == 0:
            inspect('seed', stage_list[0])
        else:
            inspect('inspect', stage)
            if index < 4:
                inspect('move', stage_list[index])
        print(json.dumps(dict(phase=index, completed=len(completed), normalStops=stops)), flush=True)
    assert len(completed) == 7 and len(set(completed)) == 7
    assert sha(jar) == artifact and config.read_bytes() == config_bytes
    for path, expected in hashes.items():
        assert sha(source / path) == expected, path
    report = dict(passed=True, artifactSha256=artifact, runtimeDirectory=str(directory), snapshots=snapshots, inspections=inspections,
        nativeRequests=sum(s['nativeRequests'] for s in snapshots), actualStateReports=7,
        oldContextRejections=sum(s.get('oldContextRejections', 0) for s in snapshots), normalStops=stops,
        sameUIDNormalH2ProcessRestarts=5, completedOriginalWorldGoals=completed,
        acceptanceAndSceneAreFixtures=True, groupLoadGatesRelaxed=True, originalPropDefinitionsUnmodified=True,
        targetCompletionPrewritten=False, timelineProofPrewritten=False, sourceResourceHashes=hashes,
        sourceResourcesModified=False, operatorDataModified=False, clientGameplayVerified=False,
        animationStateMachineVerified=False, wholeGameComplete=False)
    output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n', 'utf-8')
    print(json.dumps(dict(passed=True, nativeRequests=report['nativeRequests'], actualStateReports=7, normalStops=stops, runtimeDirectory=str(directory)), ensure_ascii=False))


if __name__ == '__main__':
    main()
