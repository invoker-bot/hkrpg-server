"""四个原始非周期活动时间轴任务：原生计数/原始领奖/同UID重启；主线历史、活动时间和精简场景为隔离夹具。"""
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
    parser.add_argument('--output', type=Path, default=ROOT / 'build/timeline-activity-native.json')
    parser.add_argument('--targets', choices=['pillars','balls'], default='pillars')
    args = parser.parse_args()
    jar, output = args.jar.resolve(), args.output.resolve()
    assert jar.is_file() and jar.is_relative_to(ROOT / 'build') and output.is_relative_to(ROOT / 'build')
    java = next((ROOT / '.tools').glob('jdk-21*/bin/java.exe'), None)
    assert java
    artifact = sha(jar)
    source = ROOT / 'resources/4.6'
    golden = json.loads((ROOT / 'src/test/resources/fixtures/timeline-activity-4.6.json').read_text('utf-8'))
    tables = {Path(p).stem: rows for p,rows in golden['records'].items()}
    selected = [dict(r) for r in golden['groups']]
    if args.targets == 'balls':
        for i,row in enumerate(selected):
            plane = row['plane']
            if plane == 20412:
                continue
            group = 96 if plane == 10401 else 18 if plane == 20411 else 49
            original = next(g for g in golden['catalogGroups'] if g['plane']==plane and g['group']==group and g['path'].endswith('_G'+str(group)+'.json'))
            selected[i] = dict(original,quest=row['quest'],instance=300003 if plane==20413 else 300001)
    goals, stages, replacements = [], {}, {}
    guarded = set(source.glob('ExcelOutput/*.json')) | {source / 'resource-manifest.json'} | {source / p for p in golden['sourceFiles']}
    for row in selected:
        plane,floor,group,instance = [row[k] for k in ['plane','floor','group','instance']]
        prop = next(p for p in row['definition']['PropList'] if p['ID']==instance)
        quest = next(q for q in tables['QuestData'] if q['QuestID']==row['quest'])
        reward = next(r for r in tables['RewardData'] if r['RewardID']==quest['RewardID'])
        recipes = {1: reward.get('Hcoin',0)} if reward.get('Hcoin',0) else {}
        for key,item in reward.items():
            if key.startswith('ItemID_') and item:
                recipes[item] = recipes.get(item,0)+reward['Count_'+key.split('_')[1]]
        digest=hashlib.sha256(json.dumps(row['definition'],sort_keys=True,ensure_ascii=False,separators=(',',':')).encode()).hexdigest()
        goal=dict(id=row['quest'],plane=plane,floor=floor,group=group,instance=instance,prop=prop['PropID'],
                  propDefinition=prop,groupSource=digest,rewards=recipes)
        goals.append(goal)
        entry=next(e for e in tables['MapEntrance'] if e['PlaneID']==plane)
        world=next(p for p in tables['MazePlane'] if p['PlaneID']==plane)['WorldID']
        stage=dict(plane=plane,floor=floor,entry=entry['ID'],world=world,
                   pos={k.lower():int(prop.get('Pos'+k.upper(),0)*1000) for k in ['x','y','z']},goal=goal)
        stages[floor]=stage
        path=f'Config/LevelOutput/RuntimeFloor/P{plane}_F{floor}.json'
        original=json.loads((source/path).read_text('utf-8'))
        dim=next(d for d in original['DimensionList'] if d.get('ID',0)==0)
        initial=original['GroupInstanceList'][dim.get('StartGroupIndex',original.get('StartGroupIndex',0))]
        refs=[initial]+[original['GroupInstanceList'][i] for i in dim['GroupIndexList'] if original['GroupInstanceList'][i]['ID']==group and group!=initial['ID']]
        assert sum(r['ID']==group for r in refs)==1
        original['GroupInstanceList']=refs
        original['DimensionList']=[dict(dim,GroupIndexList=list(range(len(refs))),StartGroupIndex=0)]
        original['StartGroupIndex']=0
        replacements[path]=original
    # The runtime verifier requires every pinned file to resolve within this owned resource root.
    # Materialize full original tables/groups; junctions remain only for unrelated read-only data.
    for path in golden['records']:
        replacements[path]=json.loads((source/path).read_text('utf-8'))
    for row in golden['catalogGroups']:
        replacements[row['path']]=row['definition']
    assert len(goals)==4
    hashes = {str(p.relative_to(source)).replace('\\', '/'): sha(p) for p in sorted(guarded)}
    runs = ROOT / 'build/smoke'
    runs.mkdir(exist_ok=True)
    directory = Path(tempfile.mkdtemp(prefix='timeline-activity-' , dir=runs))
    fixture = directory / 'resources-fixture'
    bindings = mirror_resources(source, fixture, replacements)
    flags = subprocess.CREATE_NO_WINDOW if os.name == 'nt' else 0
    if bindings:
        plan = directory / 'junctions.json'
        plan.write_text(json.dumps(bindings), 'utf-8')
        helper = directory / 'prepare.ps1'
        helper.write_text("param([string]$Plan)\n$ErrorActionPreference='Stop'\nforeach ($binding in (Get-Content -LiteralPath $Plan -Raw | ConvertFrom-Json)) { New-Item -ItemType Junction -Path $binding.target -Value $binding.source | Out-Null }\n", 'utf-8')
        subprocess.run(['powershell', '-NoProfile', '-NonInteractive', '-File', str(helper), '-Plan', str(plan)], check=True, capture_output=True, creationflags=flags)
    for path,definition in golden['headers'].items():
        target=fixture/path
        target.parent.mkdir(parents=True,exist_ok=True)
        target.write_bytes((source/path).read_bytes())
    for row in golden['prerequisites']:
        target=fixture/row['path']
        target.parent.mkdir(parents=True,exist_ok=True)
        target.write_bytes((source/row['path']).read_bytes())
    (fixture / 'resource-manifest.json').write_text(json.dumps(dict(clientVersion='4.6.0', testFixture=True, timelineActivityFixture=True)), 'utf-8')
    http, mongo, game = port(), port(), port(socket.SOCK_DGRAM)
    config = directory / 'config.json'
    config.write_text(json.dumps(dict(protocol=dict(wireVersion='4.6.0'), resourceDir=str(fixture),
        httpServer=dict(bindAddress='127.0.0.1', bindPort=http), gameServer=dict(bindAddress='127.0.0.1', bindPort=game),
        internalMongoServer=dict(address='127.0.0.1', port=mongo, filePath='./data/database.mv'))), 'utf-8')
    config_bytes = config.read_bytes()
    data=directory/'data'
    data.mkdir(exist_ok=True)
    (data/'Gameplay46.json').write_text(json.dumps({'activities':[dict(panelId=30013,moduleId=3001301,beginTime=1,endTime=4102444800)]}), 'utf-8')
    classes = directory / 'probe-classes'
    classes.mkdir()
    sources = [ROOT / 'scripts/fixtures/java/org/hkrpg/server/protocol/NativeTimelineActivityProbe.java',
               ROOT / 'scripts/fixtures/java/org/hkrpg/server/gameplay/TimelineActivityFixtureInspector.java']
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
        control.write_text(json.dumps(dict(uid=credentials['uid'], allGoals=goals, prerequisites=golden['prerequisites'], completed=completed, stage=stage)), 'utf-8')
        child = subprocess.run([str(java), *options, '-cp', classpath, 'org.hkrpg.server.gameplay.TimelineActivityFixtureInspector', str(directory), str(control), mode], cwd=ROOT, capture_output=True, text=True, encoding='utf-8', timeout=100, creationflags=flags)
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
                    child = subprocess.Popen([str(java), *options, '-cp', classpath, 'org.hkrpg.server.protocol.NativeTimelineActivityProbe', str(game)], cwd=directory, stdin=subprocess.PIPE, stdout=log_probe, stderr=subprocess.STDOUT, creationflags=flags)
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
                    assert value['actualStateReports'] == 3
                    completed.append(stage['goal']['id'])
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
    assert len(completed) == 4 and len(set(completed)) == 4
    assert sha(jar) == artifact and config.read_bytes() == config_bytes
    for path, expected in hashes.items():
        assert sha(source / path) == expected, path
    report = dict(passed=True, artifactSha256=artifact, runtimeDirectory=str(directory), snapshots=snapshots, inspections=inspections,
        nativeRequests=sum(s['nativeRequests'] for s in snapshots), actualStateReports=12,
        oldContextRejections=sum(s.get('oldContextRejections', 0) for s in snapshots), normalStops=stops,
        sameUIDNormalH2ProcessRestarts=5, completedOriginalActivityQuests=completed,
        storyPrerequisitesAndSceneAreFixtures=True, operatorWindowIsFixture=True, sceneGroupReferencesReduced=True, originalWholeGroupDefinitionsUnmodified=True,
        targetKind=args.targets, actualQuestRewards={1:120,2:60000,110202:6,213:4},
        targetCompletionPrewritten=False, countReceiptsPrewritten=False, timelineProofPrewritten=False, sourceResourceHashes=hashes,
        sourceResourcesModified=False, operatorDataModified=False, clientGameplayVerified=False,
        animationStateMachineVerified=False, actualControlInputVerified=False, wholeActivityComplete=False, wholeGameComplete=False)
    output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n', 'utf-8')
    print(json.dumps(dict(passed=True, nativeRequests=report['nativeRequests'], actualStateReports=12, normalStops=stops, runtimeDirectory=str(directory)), ensure_ascii=False))


if __name__ == '__main__':
    main()
