"""真实骰面自然进入三种原始小游戏回合，零评级退出/检查点、正常H2保存恢复和正评级拒绝；不代表游戏UI验收。"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import socket
import secrets
import subprocess
import tempfile
import time
import urllib.error
import urllib.request
from smoke_resource_fixture import mirror_resources

ROOT = Path(__file__).resolve().parents[1]
FIXTURE_PORTS = set()


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def port(kind=socket.SOCK_STREAM):
    # Keep stopped fixture listeners outside this host's verified 49152..65535 dynamic range.
    # Inspector connections otherwise can reuse a listener as an ephemeral source port.
    for candidate in secrets.SystemRandom().sample(range(20000, 30000), 800):
        if candidate in FIXTURE_PORTS:
            continue
        with socket.socket(type=kind) as channel:
            try:
                channel.bind(('127.0.0.1', candidate))
            except OSError:
                continue
            FIXTURE_PORTS.add(candidate)
            return candidate
    raise OSError('No available isolated fixture port')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--resources', type=Path, default=ROOT / 'resources/4.6')
    parser.add_argument('--output', type=Path, default=ROOT / 'build/clock-park-mini-games-native.json')
    args = parser.parse_args()
    source = args.resources.resolve(strict=True)
    output = args.output.resolve()
    assert output.is_relative_to(ROOT / 'build')
    java = next((ROOT / '.tools').glob('jdk-21*/bin/java.exe'), None)
    if java is None and os.environ.get('JAVA_HOME'):
        java = Path(os.environ['JAVA_HOME']) / 'bin/java.exe'
    jar = ROOT / 'build/libs/hkrpg-server.jar'
    classes = ROOT / 'build/classes/java/test'
    assert java and java.is_file() and jar.is_file()
    artifact = sha(jar)
    original = json.loads((ROOT / 'src/test/resources/fixtures/clock-park-catalog-4.6.json').read_text('utf-8'))
    opening = json.loads((ROOT / 'src/test/resources/fixtures/clock-park-opening-4.6.json').read_text('utf-8'))
    original['files'].update(opening['files'])
    gates = json.loads((ROOT / 'src/test/resources/fixtures/clock-park-dice-4.6.json').read_text('utf-8'))
    original['files'].update(gates['files'])
    for name, proof in original['files'].items():
        assert sha(source / name) == proof['sha256'], name
    checkpoints = json.loads((ROOT / 'src/test/resources/fixtures/clock-park-checkpoints-4.6.json').read_text('utf-8'))
    original['files'].update(checkpoints['files'])
    for name,proof in checkpoints['files'].items(): assert sha(source / name)==proof['sha256']
    guarded = sorted({'resource-manifest.json', *original['files'],
        *[p.relative_to(source).as_posix() for p in (source / 'ExcelOutput').glob('*.json')],
        *[p.relative_to(source).as_posix()
          for main_id in (8022101,8022108,8022120,8022124,8022103)
          for p in (source / f'Config/Level/Mission/{main_id}').rglob('*') if p.is_file()]})
    hashes = {name: sha(source / name) for name in guarded}
    runs = ROOT / 'build/smoke'
    runs.mkdir(parents=True, exist_ok=True)
    directory = Path(tempfile.mkdtemp(prefix='clock-park-mini-games-', dir=runs))
    fixture = directory / 'resources-fixture'
    name = 'ServerData/ClockParkStories.json'
    bindings = mirror_resources(source, fixture, {name: {}})
    flags = subprocess.CREATE_NO_WINDOW if os.name == 'nt' else 0
    if bindings:
        plan = directory / 'junction-plan.json'
        plan.write_text(json.dumps(bindings), 'utf-8')
        helper = directory / 'prepare-fixture.ps1'
        helper.write_text("param([string]$Plan)\n$ErrorActionPreference='Stop'\nforeach ($binding in (Get-Content -LiteralPath $Plan -Raw | ConvertFrom-Json)) { New-Item -ItemType Junction -Path $binding.target -Value $binding.source | Out-Null }\n", 'utf-8')
        subprocess.run(['powershell', '-NoProfile', '-NonInteractive', '-File', str(helper), '-Plan', str(plan)],
                       check=True, capture_output=True, creationflags=flags)
    owned_resource = (fixture / name).resolve()
    assert owned_resource.is_relative_to(fixture.resolve())
    resource_bytes = (source / name).read_bytes()
    owned_resource.write_bytes(resource_bytes)
    (fixture / 'resource-manifest.json').write_text(json.dumps({'clientVersion': '4.6.0',
        'testFixture': True, 'clockParkMiniGamesFixture': True, 'scriptsExecuted': False}), 'utf-8')
    import shutil
    for main_id in (8022101,8022108,8022120,8022124,8022103):
        mission = f'Config/Level/Mission/{main_id}'
        shutil.copytree(source / mission, fixture / mission)
    (directory / 'data').mkdir()
    (directory / 'data/Gameplay46.json').write_text(json.dumps({'activities': [
        {'panelId':50012,'moduleId':5001202,'beginTime':0,'endTime':9223372036854775807}]}),'utf-8')
    http, mongo, game = port(), port(), port(socket.SOCK_DGRAM)
    config = directory / 'config.json'
    config.write_text(json.dumps({'protocol': {'wireVersion': '4.6.0'}, 'resourceDir': str(fixture),
        'httpServer': {'bindAddress': '127.0.0.1', 'bindPort': http},
        'gameServer': {'bindAddress': '127.0.0.1', 'bindPort': game},
        'internalMongoServer': {'address': '127.0.0.1', 'port': mongo, 'filePath': './data/database.mv'}}), 'utf-8')
    config_bytes = config.read_bytes()
    probes = directory / 'probe-classes'
    probes.mkdir()
    result = subprocess.run([str(java.with_name('javac.exe')), '-encoding', 'UTF-8', '-cp', str(jar), '-d', str(probes),
        str(ROOT / 'scripts/fixtures/java/org/hkrpg/server/protocol/NativeClockParkMiniGamesProbe.java'),
        str(ROOT / 'scripts/fixtures/java/org/hkrpg/server/gameplay/ClockParkMiniGamesFixtureInspector.java')],
        capture_output=True, creationflags=flags)
    (directory / 'javac.log').write_bytes(result.stdout + result.stderr)
    assert result.returncode == 0, directory / 'javac.log'
    java_flags = ['-Dfile.encoding=UTF-8', '-Dstdout.encoding=UTF-8', '-Dstderr.encoding=UTF-8',
                  f'-Djdk.net.unixdomain.tmpdir={directory / "tcp-fallback"}']
    credentials, snapshots, inspections = {}, [], []
    stops = 0

    def request(route, body=None):
        req = urllib.request.Request(f'http://127.0.0.1:{http}' + route,
            data=None if body is None else json.dumps(body).encode(), headers={'Content-Type': 'application/json'})
        with urllib.request.urlopen(req, timeout=5) as response:
            return json.load(response)

    active, transaction, ongoing_sha, last, starts = 0, '', '', {}, 0
    stages=[('locked',0),('reachHanu',3),('reload',3),('finishHanu',3),('reload',3),('quit',3),('reachMovie',4),('reload',4),('finishMovie',4),('reachMovieSecond',4),('reload',4),('finishMovieSecond',4),('reload',4),('quit',4),('reload',0)]
    failure_completed, failure_transaction = False, ''
    for index,(action,script) in enumerate(stages):
        with (directory / f'server-{index}.log').open('wb') as log:
            process = subprocess.Popen([str(java), *java_flags, '-Xmx1536m', '-jar', str(jar), '--no-handbook'],
                cwd=directory, stdin=subprocess.PIPE, stdout=log, stderr=subprocess.STDOUT, creationflags=flags)
            try:
                deadline=time.monotonic()+100
                while True:
                    assert process.poll() is None, directory / f'server-{index}.log'
                    try:
                        if request('/health/ready')['ready']:break
                    except (OSError,urllib.error.URLError):pass
                    if time.monotonic()>deadline:raise RuntimeError('Startup timed out: '+str(directory))
                    time.sleep(.2)
                if not credentials:
                    login=request('/hkrpg_cn/mdk/shield/api/login',{'account':'clock-park-mini-games','password':'local-test'})
                    assert login['retcode']==0;account=login['data']['account']
                    combo=request('/hkrpg_cn/combo/granter/login/v2/login',{'data':json.dumps({'uid':account['uid'],'token':account['token']})})
                    assert combo['retcode']==0
                    credentials.update(accountUid=account['uid'],comboToken=combo['data']['combo_token'])
                assert sha(jar)==artifact
                control=dict(credentials,previousScript=active,script=script,action=action,runsStarted=starts,
                    transaction=transaction,ongoingSha256=ongoing_sha,failureCompleted=failure_completed,failureTransaction=failure_transaction)
                child=subprocess.run([str(java),*java_flags,'-cp',str(jar)+os.pathsep+str(probes),
                    'org.hkrpg.server.protocol.NativeClockParkMiniGamesProbe',str(game)],cwd=directory,
                    input=json.dumps(control)+'\n',capture_output=True,text=True,encoding='utf-8',timeout=900,creationflags=flags)
                probe_log=directory / f'probe-{index}.log';probe_log.write_text(child.stdout+child.stderr,'utf-8')
                assert child.returncode==0,probe_log
                row=json.loads(next(line for line in reversed(child.stdout.splitlines())if line.startswith('{"')))
                assert row['passed'] and not row['scriptsExecuted'] and row['placementUsesDiceFaces']
                credentials.setdefault('uid',row['uid']);assert row['uid']==credentials['uid']
                active,transaction,ongoing_sha,last,starts=row['activeScript'],row['transaction'],row['ongoingSha256'],row,row['runsStarted']
                snapshots.append(row)
                failure_completed,failure_transaction=row['failureCompleted'],row['failureTransaction']
            finally:
                if process.poll() is None:
                    try:
                        process.stdin.write(b'stop\n');process.stdin.flush();process.wait(timeout=45)
                        assert process.returncode==(1000 if os.name=='nt' else 232),directory
                        stops+=1
                    except BaseException:
                        if process.poll() is None:process.terminate();process.wait(timeout=15)
                        raise
                    finally:process.stdin.close()
        credentials_path=directory/'credentials.json';credentials_path.write_text(json.dumps(dict(credentials,**last)),'utf-8')
        child=subprocess.run([str(java),*java_flags,'-cp',str(probes)+os.pathsep+str(jar),
            'org.hkrpg.server.gameplay.ClockParkMiniGamesFixtureInspector',str(directory),str(credentials_path),
            'prepare' if index==0 else 'inspect'],cwd=ROOT,capture_output=True,text=True,encoding='utf-8',timeout=100,creationflags=flags)
        log=directory/f'inspector-{index}.log';log.write_text(child.stdout+child.stderr,'utf-8');assert child.returncode==0,log
        row=json.loads(next(line for line in reversed(child.stdout.splitlines())if line.startswith('{"')))
        assert row['passed']and row['changedPlayers']==(1 if index==0 else 0)and row['changedLineups']==0
        inspections.append(row)
        print(json.dumps({'phase':index,'wait':last['phase'],'activeScript':active,'actualStarts':starts,'normalStops':stops}),flush=True)
    assert stops==15 and len(snapshots)==15 and active==0
    assert [r['phase']for r in snapshots]==['IDLE','HANU','HANU','CHECKPOINT','CHECKPOINT','IDLE','MOVIE','MOVIE','CHECKPOINT','MOVIE','MOVIE','CHECKPOINT','CHECKPOINT','IDLE','IDLE']
    assert [r['chapter']for r in snapshots]==[0,302,302,302,302,0,410,410,410,420,420,420,420,0,0]
    assert all(r['progress']==0 for r in snapshots)
    assert config.read_bytes()==config_bytes and sha(jar)==artifact
    assert hashes=={name:sha(source/name)for name in guarded}
    result={'passed':True,'artifactSha256':artifact,'snapshots':snapshots,'inspections':inspections,
        'nativeRequests':sum(r['requests']for r in snapshots),'normalStops':stops,'sameUIDNormalH2ProcessRestarts':14,
        'threeOriginalGameWaitsAndZeroGradeResultsRestored':True,'naturallyPassedOriginal311And411ToEnterGames':True,
        'placementsApplied':sum(r['placementsApplied']for r in snapshots),
        'attributesDiceAndPassWereNotWrittenByFixture':True,'outsideGradeReportsRejectedWithoutConsumingGame':True,
        'sourceResourceHashes':hashes,'sourceResourcesModified':False,'runtimeDirectory':str(directory),
        'prerequisitePlayerLevelAndCompletionsAreExplicitFixtures':True,'operatorWindowIsExplicitFixture':True,
        'initialAttributesAreProjectRuleZero':True,'noEndingItemRewardOrProgressCredited':True,
        'positiveGameScoreFormulaVerified':False,'officialRoundCardPoolVerified':False,'scriptsExecuted':False,
        'clientGameplayVerified':False,'fullClockParkGameplayVerified':False,'crashAtomicityVerified':False}
    output.write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n','utf-8')
    print(json.dumps({'passed':True,'nativeRequests':result['nativeRequests'],'normalStops':stops,'report':str(output),
        'sourceResourcesUnchanged':len(hashes),'artifactSha256':artifact}),flush=True)


if __name__ == '__main__':
    main()
