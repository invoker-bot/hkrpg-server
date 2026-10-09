"""原始40301/40414任务试用队伍的KCP暂离/返回/正常H2恢复；前置及首步为隔离夹具。"""
import argparse
import hashlib
import json
import os
from pathlib import Path
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
    parser.add_argument('--output', type=Path, default=ROOT / 'build/raid-scripted-trials-native.json')
    parser.add_argument('--frozen', action='store_true', help='同时核对原始40412禁止普通换队副本的脚本替换')
    parser.add_argument('--trial-only', action='store_true', help='同时核对原始4010106试用队伍、固定领队和剧情开拓者')
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
    assert (classes / 'org/hkrpg/server/gameplay/RaidScriptedTrialFixtureInspector.class').is_file()
    artifact_sha = digest(jar)
    fixtures = ROOT / 'src/test/resources/fixtures'
    mission = json.loads((fixtures / 'get-trial-avatar-missions-4.6.json').read_text('utf-8'))
    raids = json.loads((fixtures / 'mission-raid-scripted-trials-4.6.json').read_text('utf-8'))
    skills = json.loads((fixtures / 'mission-trial-skills-4.6.json').read_text('utf-8'))
    guarded = {'resource-manifest.json'}
    documents = [mission, raids, skills]
    if args.frozen:
        documents.append(json.loads((fixtures / 'raid-forbidden-scripted-trial-4.6.json').read_text('utf-8')))
    if args.trial_only:
        lists = json.loads((fixtures / 'get-trial-avatar-list-missions-4.6.json').read_text('utf-8'))
        documents.extend([lists, json.loads((fixtures / 'trial-only-scripted-mission-4.6.json').read_text('utf-8'))])
        mission['cases'].extend(lists['cases'])
    for document in documents:
        for name, expected in document['sourceFiles'].items():
            assert hashlib.sha256((source / name).read_bytes().replace(b'\r\n', b'\n')).hexdigest() == expected, name
            guarded.add(name)
    guarded.update(p.relative_to(source).as_posix() for p in (source / 'ExcelOutput').glob('*.json'))
    guarded.update(p.relative_to(source).as_posix() for p in (source / 'Config/BattleLineupPage/SkillTreePointPreset').glob('*.json'))
    floors = [(20001, 20001001), (40301, 40301002), (40414, 40414001)]
    if args.frozen: floors.append((40412, 40412001))
    if args.trial_only: floors.append((40101, 40101006))
    for plane, floor in floors:
        guarded.add(f'Config/LevelOutput/RuntimeFloor/P{plane}_F{floor}.json')
        guarded.update(p.relative_to(source).as_posix() for p in
            (source / f'Config/LevelOutput/SharedRuntimeGroup/Groups_P{plane}_F{floor}').glob('*.json'))
    hashes = {n: digest(source / n) for n in sorted(guarded)}
    scenarios = [dict(raid=40301, main=1036004, parent=1036003, sub=103600414,
                      trial=1021225, entry=40301002, floor=40301002, virtual=False, predecessor=103600405),
                 dict(raid=40414, main=1040212, parent=1040211, sub=104021212,
                      trial=1021404, entry=40414001, floor=40414001, virtual=True)]
    if args.frozen:
        scenarios.append(dict(raid=40412, main=1040207, parent=1040208, sub=104020708,
            trial=1021403, entry=40412001, floor=40412001, virtual=True, frozenBase=True))
    if args.trial_only:
        scenarios.append(dict(raid=4010106, main=2011412, parent=2011411, sub=201141225,
            trial=1108001, plane=40101, entry=4010106, floor=40101006, virtual=False,
            predecessor=201141216, trialOnly=True, baseRoster=[1101110,1101106],
            baseActorIds=[1110,1106]))
    runs = ROOT / 'build/smoke'
    runs.mkdir(exist_ok=True)
    directory = Path(tempfile.mkdtemp(prefix='raid-scripted-trials-', dir=runs))
    fixture = directory / 'resources-fixture'
    mains = json.loads((source / 'ExcelOutput/MainMission.json').read_text('utf-8'))
    for m in mains:
        if m['MainMissionID'] in {s['parent'] for s in scenarios}:
            m.update(TakeOperation='And', BeginOperation='And', TakeParam=[{'Type': 'Manual'}],
                     BeginParam=[{'Type': 'Auto'}], RewardID=0, SubRewardList=[])
    junctions = mirror_resources(source, fixture, {'ExcelOutput/MainMission.json': mains})
    for s in scenarios:
        c = next(c for c in mission['cases'] if c['sub']['ID'] == s['sub'])
        role = next((r for r in c.get('roles', []) if r['special']['SpecialAvatarID'] == s['trial']), c)
        s['actor'] = role['special']['AvatarID']
        folder = fixture / 'Config/Level/Mission' / str(s['main'])
        folder.mkdir(parents=True)
        info = json.loads((source / f'Config/Level/Mission/{s["main"]}/MissionInfo_{s["main"]}.json').read_text('utf-8'))
        info.update(StartSubMissionList=[s['sub']], FinishSubMissionList=[199988999])
        (folder / f'MissionInfo_{s["main"]}.json').write_text(json.dumps(info), 'utf-8')
        selected = [c['sub']]
        if 'predecessor' in s:
            original = json.loads((source / f'Config/Level/Mission/{s["main"]}/MissionInfo_{s["main"]}.partial.json').read_text('utf-8'))
            selected.append(next(x for x in original['SubMissionList'] if x['ID'] == s['predecessor']))
        (folder / f'MissionInfo_{s["main"]}.partial.json').write_text(json.dumps({'SubMissionList': selected}), 'utf-8')
        script = c['sub']['MissionJsonPath']
        assert json.loads((source / script).read_text('utf-8')) == c['script']
        (fixture / script).write_bytes((source / script).read_bytes())
    flags = subprocess.CREATE_NO_WINDOW if os.name == 'nt' else 0
    if junctions:
        plan = directory / 'junction-plan.json'
        plan.write_text(json.dumps(junctions), 'utf-8')
        helper = directory / 'prepare-fixture.ps1'
        helper.write_text("param([string]$Plan)\n$ErrorActionPreference='Stop'\nforeach ($binding in (Get-Content -LiteralPath $Plan -Raw | ConvertFrom-Json)) { New-Item -ItemType Junction -Path $binding.target -Value $binding.source | Out-Null }\n", 'utf-8')
        subprocess.run(['powershell', '-NoProfile', '-NonInteractive', '-File', str(helper), '-Plan', str(plan)],
                       check=True, capture_output=True, creationflags=flags)
    (fixture / 'resource-manifest.json').write_text(json.dumps({'clientVersion': '4.6.0',
        'testFixture': True, 'raidScriptedTrialsFixture': True, 'raids': [s['raid'] for s in scenarios],
        'syntheticPendingFinalSubmission': 199988999,
        'parentAndPredecessorAcceptanceAndFirstStepAreIsolatedFixtures': True}), 'utf-8')
    http, mongo, game = free_port(), free_port(), free_port(socket.SOCK_DGRAM)
    (directory / 'config.json').write_text(json.dumps({'protocol': {'wireVersion': '4.6.0'},
        'resourceDir': str(fixture), 'httpServer': {'bindAddress': '127.0.0.1', 'bindPort': http},
        'gameServer': {'bindAddress': '127.0.0.1', 'bindPort': game},
        'internalMongoServer': {'address': '127.0.0.1', 'port': mongo, 'filePath': './data/database.mv'}}), 'utf-8')
    probes = directory / 'probe-classes'
    probes.mkdir()
    compiled = subprocess.run([str(java.with_name('javac.exe')), '-encoding', 'UTF-8', '-cp', str(jar), '-d', str(probes),
        str(ROOT / 'scripts/fixtures/java/org/hkrpg/server/protocol/NativeRaidScriptedTrialProbe.java')],
        capture_output=True, creationflags=flags)
    (directory / 'javac.log').write_bytes(compiled.stdout + compiled.stderr)
    assert compiled.returncode == 0, directory / 'javac.log'
    fallback = '-Djdk.net.unixdomain.tmpdir=' + str(directory / 'tcp-fallback')
    encoding = ['-Dfile.encoding=UTF-8', '-Dstdout.encoding=UTF-8', '-Dstderr.encoding=UTF-8']
    snapshots, inspections, normal_stops = [], [], 0

    def request(route, body=None):
        req = urllib.request.Request(f'http://127.0.0.1:{http}' + route,
            data=None if body is None else json.dumps(body).encode(), headers={'Content-Type': 'application/json'})
        with urllib.request.urlopen(req, timeout=5) as response:
            return json.load(response)

    def probe(s, mode):
        assert digest(jar) == artifact_sha
        child = subprocess.run([str(java), *encoding, fallback, '-cp', str(jar) + os.pathsep + str(probes),
            'org.hkrpg.server.protocol.NativeRaidScriptedTrialProbe', str(game)], cwd=directory,
            input=json.dumps(dict(s, mode=mode)) + '\n', text=True, encoding='utf-8',
            capture_output=True, timeout=90, creationflags=flags)
        log = directory / f'probe-{len(snapshots)}-{s["raid"]}-{mode}.log'
        log.write_text(child.stdout + child.stderr, 'utf-8')
        assert child.returncode == 0, log
        snapshot = json.loads(next(line for line in child.stdout.splitlines() if line.startswith('{"')))
        assert snapshot['passed'] and not snapshot['clientGameplayVerified']
        if 'uid' not in s: s['uid'] = snapshot['uid']
        assert s['uid'] == snapshot['uid']
        snapshots.append(snapshot)

    def inspect(mode):
        credentials = directory / 'player-fixtures.json'
        credentials.write_text(json.dumps(scenarios), 'utf-8')
        child = subprocess.run([str(java), *encoding, fallback, '-cp', str(classes) + os.pathsep + str(jar),
            'org.hkrpg.server.gameplay.RaidScriptedTrialFixtureInspector', str(directory), str(credentials), mode],
            cwd=ROOT, text=True, encoding='utf-8', capture_output=True, timeout=100, creationflags=flags)
        log = directory / f'inspector-{len(inspections)}-{mode}.log'
        log.write_text(child.stdout + child.stderr, 'utf-8')
        assert child.returncode == 0, log
        result = json.loads(next(line for line in reversed(child.stdout.splitlines()) if line.startswith('{"')))
        assert result['passed'] and result['changedPlayers'] == (len(scenarios) if mode in {'prepare', 'close'} else 0)
        inspections.append(result)

    for iteration, mode in enumerate(['pending', 'first', 'resume', 'closed']):
        with (directory / f'server-{iteration}.log').open('w', encoding='utf-8') as log:
            process = subprocess.Popen([str(java), *encoding, '-Xmx1536m', fallback, '-jar', str(jar), '--no-handbook'],
                cwd=directory, stdin=subprocess.PIPE, stdout=log, stderr=subprocess.STDOUT, creationflags=flags)
            try:
                deadline = time.monotonic() + 100
                while True:
                    assert process.poll() is None, directory / f'server-{iteration}.log'
                    try:
                        if request('/health/ready')['ready']: break
                    except (OSError, urllib.error.URLError): pass
                    if time.monotonic() > deadline: raise RuntimeError('Startup timed out: ' + str(directory))
                    time.sleep(.2)
                for s in scenarios:
                    if 'comboToken' not in s:
                        login = request('/hkrpg_cn/mdk/shield/api/login', {'account': 'raid-source-' + str(s['raid']), 'password': 'local-test'})
                        assert login['retcode'] == 0
                        account = login['data']['account']
                        combo = request('/hkrpg_cn/combo/granter/login/v2/login', {'data': json.dumps({'uid': account['uid'], 'token': account['token']})})
                        assert combo['retcode'] == 0
                        s.update(accountUid=account['uid'], comboToken=combo['data']['combo_token'])
                    probe(s, mode)
                    if mode == 'first':
                        for extra in ['active', 'pause', 'return']:
                            time.sleep(.25)
                            probe(s, extra)
            finally:
                if process.poll() is None:
                    try:
                        process.stdin.write(b'stop\n');process.stdin.flush();process.wait(timeout=45)
                        assert process.returncode == (1000 if os.name == 'nt' else 232), directory
                        normal_stops += 1
                    except BaseException:
                        if process.poll() is None: process.terminate();process.wait(timeout=15)
                        raise
                    finally: process.stdin.close()
        inspect('active' if mode == 'first' else mode)
        if mode == 'pending': inspect('prepare')
        if mode == 'resume': inspect('close')
    assert normal_stops == 4 and len(snapshots) == len(scenarios) * 7 and len(inspections) == 6
    assert len({s['uid'] for s in snapshots}) == len(scenarios)
    assert inspections[2]['players'] == inspections[3]['players']
    assert hashes == {n: digest(source / n) for n in guarded}
    assert digest(jar) == artifact_sha
    report = {'passed': True, 'runtimeDirectory': str(directory), 'artifactSha256': artifact_sha,
        'snapshots': snapshots, 'inspections': inspections, 'normalStops': normal_stops,
        'sameUIDNormalH2ProcessRestarts': 3, 'sourceResourceHashes': hashes,
        'sourceResourcesModified': False, 'sourceCommit': mission['sourceCommit'],
        'isolatedParentAndPredecessorAcceptance': True, 'isolatedFirstAndFinalSubMissionLists': True,
        'originalSubTaskActorSkillsEquipmentRaidRowAndFloorUnchanged': True,
        'forbiddenPlayerRaidIncluded': args.frozen, 'trialOnlyCaptainRaidIncluded': args.trial_only,
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
