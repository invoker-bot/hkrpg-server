"""Five original nonperiodic activity kill goals: original NPC encounters, native reported deaths, rewards and same-UID restarts."""
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
    parser.add_argument('--output', type=Path, default=ROOT / 'build/activity-kill-quests-native.json')
    args = parser.parse_args()
    jar, output = args.jar.resolve(), args.output.resolve()
    assert jar.is_file() and jar.is_relative_to(ROOT / 'build') and output.is_relative_to(ROOT / 'build')
    java = next((ROOT / '.tools').glob('jdk-21*/bin/java.exe'), None)
    assert java
    artifact = sha(jar)
    source = ROOT / 'resources/4.6'
    golden = json.loads((ROOT / 'src/test/resources/fixtures/activity-kill-quests-4.6.json').read_text('utf-8'))
    tables = {Path(p).stem: rows for p,rows in golden['records'].items()}
    goals, stages, replacements = [], {}, {}
    progress={str(i):0 for i in range(6029841,6029846)}
    for quest in tables['QuestData']:
        reward=next(r for r in tables['RewardData'] if r['RewardID']==quest['RewardID'])
        recipes={1:reward['Hcoin']} if reward.get('Hcoin') else {}
        for key,item in reward.items():
            if key.startswith('ItemID_') and item: recipes[item]=recipes.get(item,0)+reward['Count_'+key.split('_')[1]]
        goal=next(r['Progress'] for r in tables['FinishWay'] if r['ID']==quest['FinishWayID'])
        template=next(r['ParamInt1'] for r in tables['FinishWay'] if r['ID']==quest['FinishWayID'])
        goals.append(dict(id=quest['QuestID'],goal=goal,template=template,rewards=recipes))
    guarded=set(source.glob('ExcelOutput/*.json'))|{source/'resource-manifest.json'}|{source/p for p in golden['sourceFiles']}
    for path in golden['records']: replacements[path]=json.loads((source/path).read_text('utf-8'))
    replacements.update(golden['groups'])
    placements=json.loads((ROOT/'build/activity-kill-original-encounters.json').read_text('utf-8'))
    specifications=[(20411,102,200005,204110050),(20411,102,200001,204110010),(20411,102,200003,204110030),(20411,235,200001,204112310),
                    (20412,139,200001,204122310),(20412,174,200001,204121010),(20412,174,200004,204121020)]
    fullstages={r['StageID']:r for r in tables['StageConfig']}
    monstertemplates={r['MonsterID']:r.get('MonsterTemplateID',0) for r in tables['MonsterConfig']}
    groupsbyplane={20411:[102,235],20412:[139,174]}
    for path,definition in golden['floors'].items():
        floor=int(Path(path).stem.split('_F')[1]);plane=int(Path(path).stem.split('_F')[0][1:])
        original=json.loads((source/path).read_text('utf-8'))
        dim=next(d for d in original['DimensionList'] if d.get('ID',0)==0)
        initial=original['GroupInstanceList'][dim.get('StartGroupIndex',original.get('StartGroupIndex',0))]
        refs=[initial]+[original['GroupInstanceList'][i] for i in dim['GroupIndexList'] if original['GroupInstanceList'][i]['ID'] in groupsbyplane[plane] and original['GroupInstanceList'][i]['ID']!=initial['ID']]
        assert all(sum(r['ID']==g for r in refs)==1 for g in groupsbyplane[plane])
        original['GroupInstanceList']=refs;original['DimensionList']=[dict(dim,GroupIndexList=list(range(len(refs))),StartGroupIndex=0)];original['StartGroupIndex']=0
        replacements[path]=original
        entry=next(r for r in tables['MapEntrance'] if r['PlaneID']==plane and r['FloorID']==floor)
        stages[plane]=dict(plane=plane,floor=floor,entry=entry['ID'],world=501,pos=dict(x=0,y=0,z=0),encounters=[])
    for plane,group,instance,stage in specifications:
        row=next(r for r in placements if r['stage']==stage and r['instance']==instance and r['path'].endswith('_G'+str(group)+'.json'))
        raw=fullstages[stage];waves=[[r['Monster'+str(i)] for i in range(5) if r.get('Monster'+str(i),0)] for r in raw['MonsterList']]
        for wave in waves:
            for monster in wave:
                for goal in goals:
                    if monstertemplates[monster]==goal['template']:progress[str(goal['id'])]=min(goal['goal'],progress[str(goal['id'])]+1)
        stages[plane]['encounters'].append(dict(group=group,instance=instance,npc=row['npc'],stage=stage,waves=waves,after=dict(progress)))
    assert list(progress.values())==[3,3,3,3,1]
    mainheaders={}
    for main in [1000510,1040105,1040106]:
        path=f'Config/Level/Mission/{main}/MissionInfo_{main}.json'
        assert (source/path).is_file()
        mainheaders[path]=json.loads((source/path).read_text('utf-8'));guarded.add(source/path)
    hashes = {str(p.relative_to(source)).replace('\\', '/'): sha(p) for p in sorted(guarded)}
    runs = ROOT / 'build/smoke'
    runs.mkdir(exist_ok=True)
    directory = Path(tempfile.mkdtemp(prefix='activity-kill-quests-' , dir=runs))
    fixture = directory / 'resources-fixture'
    bindings = mirror_resources(source, fixture, replacements)
    flags = subprocess.CREATE_NO_WINDOW if os.name == 'nt' else 0
    if bindings:
        plan = directory / 'junctions.json'
        plan.write_text(json.dumps(bindings), 'utf-8')
        helper = directory / 'prepare.ps1'
        helper.write_text("param([string]$Plan)\n$ErrorActionPreference='Stop'\nforeach ($binding in (Get-Content -LiteralPath $Plan -Raw | ConvertFrom-Json)) { New-Item -ItemType Junction -Path $binding.target -Value $binding.source | Out-Null }\n", 'utf-8')
        subprocess.run(['powershell', '-NoProfile', '-NonInteractive', '-File', str(helper), '-Plan', str(plan)], check=True, capture_output=True, creationflags=flags)
    for path,definition in mainheaders.items():
        target=fixture/path;target.parent.mkdir(parents=True,exist_ok=True);target.write_bytes((source/path).read_bytes())
    (fixture / 'resource-manifest.json').write_text(json.dumps(dict(clientVersion='4.6.0', testFixture=True, activityKillFixture=True)), 'utf-8')
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
    sources = [ROOT / 'scripts/fixtures/java/org/hkrpg/server/protocol/NativeActivityKillProbe.java',
               ROOT / 'scripts/fixtures/java/org/hkrpg/server/gameplay/ActivityKillFixtureInspector.java']
    built = subprocess.run([str(java.with_name('javac.exe')), '-encoding', 'UTF-8', '-cp', str(jar), '-d', str(classes), *map(str, sources)], capture_output=True, creationflags=flags)
    (directory / 'javac.log').write_bytes(built.stdout + built.stderr)
    assert built.returncode == 0, directory / 'javac.log'
    options = ['-Dfile.encoding=UTF-8', '-Dstdout.encoding=UTF-8', '-Dstderr.encoding=UTF-8', f'-Djdk.net.unixdomain.tmpdir={directory / "tcp-fallback"}']
    classpath = str(classes) + os.pathsep + str(jar)
    credentials, snapshots, inspections, completed = {}, [], [], []
    stops = 0
    stage_list = list(stages.values())
    current_progress={str(g['id']):0 for g in goals}
    claimed=False
    battle_drops={}

    def request(route, body=None):
        req = urllib.request.Request(f'http://127.0.0.1:{http}' + route, data=None if body is None else json.dumps(body).encode(), headers={'Content-Type': 'application/json'})
        with urllib.request.urlopen(req, timeout=5) as response:
            return json.load(response)

    def inspect(mode, stage):
        control = directory / f'inspector-{len(inspections)}.json'
        control.write_text(json.dumps(dict(uid=credentials['uid'], allGoals=goals, progress=current_progress, claimed=claimed, battleDrops=battle_drops, stage=stage)), 'utf-8')
        child = subprocess.run([str(java), *options, '-cp', classpath, 'org.hkrpg.server.gameplay.ActivityKillFixtureInspector', str(directory), str(control), mode], cwd=ROOT, capture_output=True, text=True, encoding='utf-8', timeout=100, creationflags=flags)
        (directory / f'inspector-{len(inspections)}.log').write_text(child.stdout + child.stderr, 'utf-8')
        assert child.returncode == 0, directory / f'inspector-{len(inspections)}.log'
        value = json.loads(next(line for line in reversed(child.stdout.splitlines()) if line.startswith('{')))
        assert value['passed'] and value['receipts']==sum(current_progress.values())
        inspections.append(value)

    for index in range(4):
        stage = stage_list[min(max(index - 1, 0), len(stage_list) - 1)]
        after = current_progress if index in [0,3] else stage['encounters'][-1]['after']
        phase_goals=[dict(g,before=current_progress[str(g['id'])],after=after[str(g['id'])]) for g in goals]
        encounters=[] if index in [0,3] else stage['encounters']
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
                    child = subprocess.Popen([str(java), *options, '-cp', classpath, 'org.hkrpg.server.protocol.NativeActivityKillProbe', str(game)], cwd=directory, stdin=subprocess.PIPE, stdout=log_probe, stderr=subprocess.STDOUT, creationflags=flags)
                    child.stdin.write((json.dumps(dict(credentials,**dict(stage,encounters=encounters),goals=phase_goals,seed=index==0,resume=index==3,claim=index==2)) + '\n').encode())
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
                if index in [1,2]:
                    assert len(value['battles'])==len(encounters)
                    for battle in value['battles']:
                        for item,count in battle['drops'].items():battle_drops[item]=battle_drops.get(item,0)+count
                    current_progress=dict(after)
                    if index==2:
                        assert value['claimedOriginalGoals']==5
                        claimed=True
                completed=[g['id'] for g in goals if current_progress[str(g['id'])]==g['goal']]
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
            if index == 1:
                inspect('move', stage_list[1])
        print(json.dumps(dict(phase=index, completed=len(completed), normalStops=stops)), flush=True)
    inspect('expired',stage_list[-1])
    assert len(completed)==5 and len(set(completed))==5 and claimed
    assert sha(jar)==artifact and config.read_bytes()==config_bytes
    for path,expected in hashes.items():assert sha(source/path)==expected,path
    payout={}
    for g in goals:
        for item,count in g['rewards'].items():payout[item]=payout.get(item,0)+count
    report=dict(passed=True,artifactSha256=artifact,runtimeDirectory=str(directory),snapshots=snapshots,inspections=inspections,
        nativeRequests=sum(s['nativeRequests'] for s in snapshots),actualReportedDeaths=sum(s.get('actualReportedDeaths',0) for s in snapshots),
        actualSourceNPCEncounters=sum(len(s.get('battles',[])) for s in snapshots),staleResultRejections=sum(s.get('staleResultRejections',0) for s in snapshots),
        normalStops=stops,sameUIDNormalH2ProcessRestarts=3,completedOriginalActivityQuests=completed,sourceBoundDeathReceipts=13,
        storyPrerequisitesAndSceneAreFixtures=True,operatorWindowIsFixture=True,sceneGroupReferencesReduced=True,originalWholeGroupDefinitionsUnmodified=True,
        actualQuestRewards=payout,actualBattleDrops=battle_drops,targetCompletionPrewritten=False,countReceiptsPrewritten=False,sourceResourceHashes=hashes,
        sourceResourcesModified=False,operatorDataModified=False,clientGameplayVerified=False,combatReplayVerified=False,
        wholeActivityComplete=False,wholeGameComplete=False)
    output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n', 'utf-8')
    print(json.dumps(dict(passed=True, nativeRequests=report['nativeRequests'], actualReportedDeaths=report['actualReportedDeaths'], normalStops=stops, runtimeDirectory=str(directory)), ensure_ascii=False))


if __name__ == '__main__':
    main()
