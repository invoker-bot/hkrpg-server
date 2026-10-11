"""3000602 原 Phone 四分支、两日真实付款/未交付到期与正常 H2 恢复；原演出和游戏 UI 未验。"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import queue
import shutil
import socket
import subprocess
import sys
import tempfile
import threading
import time
import urllib.error
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'scripts'))
from smoke_resource_fixture import mirror_resources

COMMIT = '724b139d8c9c32d12552eb95745a4fee72bfe48b'
BRANCHES = {'202-205': (121140202, 121140205), '202-207': (121140202, 121140207),
            '203-205': (121140203, 121140205), '203-207': (121140203, 121140207)}
EXPIRY_POINTS = ['noReplies', 'oneReply', 'bothReplies']


def sha(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def port(kind=socket.SOCK_STREAM):
    with socket.socket(type=kind) as sock:
        sock.bind(('127.0.0.1', 0))
        return sock.getsockname()[1]


def transport_ports():
    http, mongo = port(), port()
    while mongo == http:
        mongo = port()
    return http, mongo, port(socket.SOCK_DGRAM)


def schedule(scenario):
    if scenario == 'settled':
        return [[(1, 'open')], [(1, 'finish'), (2, 'open')], [(2, 'finish'), (2, 'claimed')]]
    if scenario == 'expired-unpaid':
        return [[(1, 'open')], [(2, 'boundary-reject')], [(2, 'open'), (2, 'finish')], [(2, 'claimed')]]
    raise ValueError('Unknown finite Phone scenario')


def cleanup(process, primary, normal_stop=False):
    """Preserve the first failure while attempting the owned process's normal shutdown."""
    current_failure = primary
    try:
        if process.poll() is None:
            if normal_stop:
                process.stdin.write(b'stop\n')
                process.stdin.flush()
                process.wait(timeout=30)
                assert process.returncode == (1000 if os.name == 'nt' else 232), 'Normal fixture stop failed'
                return True
            process.terminate()
            process.wait(timeout=10)
        return False
    except BaseException as cleanup_error:
        current_failure = primary or cleanup_error
        try:
            if process.poll() is None:
                process.terminate()
                process.wait(timeout=15)
        except BaseException as fallback_error:
            cleanup_error.add_note('Fallback termination failed: ' + repr(fallback_error))
        if primary is not None:
            primary.add_note('Owned process cleanup failed: ' + repr(cleanup_error))
            return False
        raise
    finally:
        if process.stdin and not process.stdin.closed:
            try:
                process.stdin.close()
            except BaseException as close_error:
                if current_failure is None:
                    raise
                current_failure.add_note('Owned stdin close failed: ' + repr(close_error))


def one(branch, at, jar, java, records, scenario, artifact):
    source = (ROOT / 'resources/4.6').resolve()
    assert sha(jar) == artifact, 'Frozen jar changed between Phone cases'
    daily = [r['row'] for r in records if r['kind'] == 'daily' and r['row']['ID'] == 3000602]
    assert len(daily) == 1
    prerequisites = [1000401, 1010201, 1010403]
    mains = json.loads((source / 'ExcelOutput/MainMission.json').read_text('utf-8'))
    for row in mains:
        if row['MainMissionID'] in prerequisites:
            row.update(TakeParam=[{'Type': 'Auto'}], TakeOperation='And', BeginParam=[{'Type': 'Auto'}],
                       BeginOperation='And', RewardID=0)
    pool = [r['row'] for r in records if r['kind'] == 'pool']
    replacements = {'ExcelOutput/MainMission.json': mains, 'ExcelOutput/DailyMissionData.json': daily,
                    'ExcelOutput/DailyActiveQuestPool.json': pool}
    guarded = {source / 'resource-manifest.json'}
    source_only = []
    files = {}
    for record in records:
        relative = Path(record['source']).relative_to('resources/4.6').as_posix()
        if relative in files:
            assert files[relative] == (record['installation'], record['sha256'], record['sourceBlobSHA256'])
            continue
        files[relative] = (record['installation'], record['sha256'], record['sourceBlobSHA256'])
        origin = source / relative
        blob = subprocess.run(['git', '-C', str(ROOT / 'references/TurnBasedGameData'), 'show',
                               COMMIT + ':' + relative], check=True, capture_output=True).stdout
        assert hashlib.sha256(blob).hexdigest() == record['sourceBlobSHA256']
        if record['installation'] == 'missing':
            assert not origin.exists() and record['sha256'] is None
            source_only.append(relative)
        else:
            assert origin.is_file() and sha(origin) == record['sha256']
            assert json.loads(origin.read_bytes()) == json.loads(blob)
            guarded.add(origin)
    assert len(files) == 39 and len(source_only) == 2
    original_folder = source / 'Config/Level/Mission/3000602'
    guarded |= {path for path in original_folder.rglob('*') if path.is_file()}
    hashes = {path.relative_to(source).as_posix(): sha(path) for path in sorted(guarded)}
    runs = ROOT / 'build/smoke'
    runs.mkdir(parents=True, exist_ok=True)
    directory = Path(tempfile.mkdtemp(prefix=f'daily-message-{scenario}-{branch}-{at}-', dir=runs)).resolve()
    assert directory.is_relative_to(runs.resolve())
    fixture = directory / 'resources-fixture'
    bindings = mirror_resources(source, fixture, replacements)
    flags = subprocess.CREATE_NO_WINDOW if os.name == 'nt' else 0
    if bindings:
        plan = directory / 'junctions.json'
        plan.write_text(json.dumps(bindings), 'utf-8')
        helper = directory / 'prepare.ps1'
        helper.write_text("param([string]$Plan)\n$ErrorActionPreference='Stop'\nforeach ($binding in (Get-Content -LiteralPath $Plan -Raw | ConvertFrom-Json)) { New-Item -ItemType Junction -Path $binding.target -Value $binding.source | Out-Null }\n", 'utf-8')
        subprocess.run(['powershell', '-NoProfile', '-NonInteractive', '-File', str(helper), '-Plan', str(plan)],
                       check=True, capture_output=True, creationflags=flags)
    mission_parent = fixture / 'Config/Level/Mission'
    mission_parent.mkdir(parents=True, exist_ok=True)
    for origin in original_folder.rglob('*'):
        if origin.is_file():
            target = fixture / origin.relative_to(source)
            assert target.resolve().is_relative_to(fixture.resolve())
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(origin, target)
            assert sha(origin) == sha(target)
    for main_id in prerequisites:
        folder = mission_parent / str(main_id)
        folder.mkdir(parents=True, exist_ok=True)
        child = main_id * 100 + 99
        (folder / f'MissionInfo_{main_id}.json').write_text(json.dumps(dict(MainMissionID=main_id,
            StartSubMissionList=[child], FinishSubMissionList=[child])), 'utf-8')
        (folder / f'MissionInfo_{main_id}.partial.json').write_text(json.dumps(dict(SubMissionList=[dict(
            ID=child, MainMissionID=main_id, TakeType='Auto', FinishType='Auto', Progress=1)])), 'utf-8')
    (fixture / 'resource-manifest.json').write_text(json.dumps(dict(clientVersion='4.6.0', testFixture=True,
        dailyMessageFixture=True, originalMission=3000602)), 'utf-8')
    # No entrance replacement: source 200020102 and its Finish1000401 gate remain original.
    http, mongo, game = transport_ports()
    assert http != mongo
    config = directory / 'config.json'
    config.write_text(json.dumps(dict(protocol=dict(wireVersion='4.6.0'), resourceDir=str(fixture),
        httpServer=dict(bindAddress='127.0.0.1', bindPort=http), gameServer=dict(bindAddress='127.0.0.1', bindPort=game),
        internalMongoServer=dict(address='127.0.0.1', port=mongo, filePath='./data/database.mv'))), 'utf-8')
    config_hash = sha(config)
    classes = directory / 'fixture-classes'
    classes.mkdir()
    sources = [ROOT / 'scripts/fixtures/java' / path for path in [
        'emu/lunarcore/command/commands/DailyTravelClockFixtureCommand.java',
        'org/hkrpg/server/protocol/NativeDailyMessageProbe.java',
        'org/hkrpg/server/gameplay/DailyMessageFixtureInspector.java']]
    subprocess.run([str(java.with_name('javac.exe')), '-encoding', 'UTF-8', '-cp', str(jar), '-d', str(classes),
                    *map(str, sources)], check=True, capture_output=True, creationflags=flags)
    java_flags = ['-Dfile.encoding=UTF-8', '-Dstdout.encoding=UTF-8', '-Dstderr.encoding=UTF-8',
                  '-Djdk.net.unixdomain.tmpdir=' + str(directory / 'tcp-fallback'), '-Dio.netty.eventLoopThreads=2']
    classpath = str(jar) + os.pathsep + str(classes)
    wire = subprocess.run([str(java), *java_flags, '-cp', classpath,
        'org.hkrpg.server.protocol.NativeDailyMessageProbe', '--wire-fields'], cwd=ROOT, check=True,
        capture_output=True, text=True, encoding='utf-8', creationflags=flags)
    wire_fields = json.loads(wire.stdout)
    assert wire_fields['MissionTimestampDeclared'] is False and len(wire_fields) == 31
    (directory / 'wire-fields.json').write_text(json.dumps(wire_fields, indent=2), 'utf-8')
    command = [str(java), *java_flags, '-Xmx1536m', '-cp', classpath, 'org.hkrpg.server.HkrpgServer', '--no-handbook']
    base = f'http://127.0.0.1:{http}'
    local_http = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    credentials = None
    snapshots, views, h2_views = [], [], []
    first_clock = None
    stops = 0
    old_first, old_second = BRANCHES[branch]

    def request(route, body=None):
        req = urllib.request.Request(base + route, data=None if body is None else json.dumps(body).encode(),
                                     headers={'Content-Type': 'application/json'})
        with local_http.open(req, timeout=5) as response:
            return json.load(response)

    def probe(day, phase, server, server_log):
        nonlocal first_clock
        assert sha(jar) == artifact and sha(config) == config_hash
        first, second = (old_first, old_second) if day == 1 or phase == 'boundary-reject' else (
            121140203 if old_first == 121140202 else 121140202,
            121140207 if old_second == 121140205 else 121140205)
        child = subprocess.Popen([str(java), *java_flags, '-cp', classpath,
            'org.hkrpg.server.protocol.NativeDailyMessageProbe', str(game)], stdin=subprocess.PIPE,
            stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, encoding='utf-8', creationflags=flags)
        lines, available = [], queue.Queue()
        def read_output():
            for line in child.stdout:
                lines.append(line)
                available.put(line)
            available.put(None)
        reader = threading.Thread(target=read_output, daemon=True)
        reader.start()
        index = len(snapshots)
        primary = None
        try:
            child.stdin.write(json.dumps(dict(credentials, dayIndex=day, phase=phase, scenario=scenario,
                expiredAt=at, firstChoice=first, secondChoice=second)) + '\n')
            child.stdin.flush()
            deadline = time.monotonic() + 90
            while True:
                line = available.get(timeout=max(.1, deadline - time.monotonic()))
                assert line is not None, directory
                if line.startswith('HKRPG_DAILY_CLOCK_FIXTURE_READY:'):
                    uid = int(line.strip().rsplit(':', 1)[1])
                    break
            if first_clock is None:
                first_clock = int(time.time()) + 1
                assert (first_clock + 14400) % 86400 < 86400 - 900, 'Controlled fixture is too close to wall-clock reset'
            target = first_clock + (day - 1) * 86400
            marker = f'HKRPG_DAILY_TRAVEL_CLOCK_ACK:{uid}:{target}:{index}'
            server.stdin.write(f'dailytravelclockfixture {uid} {target} {index}\n'.encode())
            server.stdin.flush()
            while marker not in server_log.read_text('utf-8', errors='replace'):
                assert server.poll() is None and child.poll() is None, directory
                if time.monotonic() > deadline:
                    raise TimeoutError(f'Phone clock fixture timed out: {directory}')
                time.sleep(.05)
            rows = [line.split('HKRPG_DAILY_TRAVEL_CLOCK_VIEW:', 1)[1] for line in server_log.read_text('utf-8', errors='replace').splitlines()
                    if 'HKRPG_DAILY_TRAVEL_CLOCK_VIEW:' in line]
            view = json.loads(rows[-1])
            assert view['uid'] == uid and view['nonce'] == index and view['clock'] == target
            views.append(view)
            child.stdin.write('resume\n')
            child.stdin.flush()
            child.stdin.close()
            child.wait(timeout=max(.1, deadline - time.monotonic()))
            reader.join(timeout=3)
            assert child.returncode == 0, directory
            result = json.loads(next(line for line in reversed(lines) if line.startswith('{')))
            assert result['passed'] and result['dayIndex'] == day and result['phase'] == phase
            result['clockFixtureEpochSecond'] = target
            snapshots.append(result)
        except BaseException as error:
            primary = error
            raise
        finally:
            try:
                cleanup(child, primary)
            except BaseException as cleanup_error:
                if primary is None:
                    primary = cleanup_error
                raise
            finally:
                try:
                    reader.join(timeout=3)
                    (directory / f'probe-{index}.log').write_text(''.join(lines), 'utf-8')
                except BaseException as log_error:
                    if primary is None:
                        raise
                    primary.add_note('Owned probe log failed: ' + repr(log_error))

    phases_by_process = schedule(scenario)
    for iteration, phases in enumerate(phases_by_process):
        server_log = directory / f'server-{iteration}.log'
        with server_log.open('w', encoding='utf-8') as log:
            process = subprocess.Popen(command, cwd=directory, stdin=subprocess.PIPE,
                                       stdout=log, stderr=subprocess.STDOUT, creationflags=flags)
            primary = None
            try:
                deadline = time.monotonic() + 90
                while True:
                    assert process.poll() is None, directory
                    try:
                        ready = request('/health/ready')['ready']
                        if ready:
                            break
                    except (OSError, urllib.error.URLError):
                        pass
                    if time.monotonic() > deadline:
                        raise TimeoutError(f'Phone server readiness timed out: {directory}')
                    time.sleep(.2)
                if iteration == 0:
                    login = request('/hkrpg_cn/mdk/shield/api/login', {'account': f'cn-46-daily-message-{scenario}-{branch}-{at}', 'password': 'local-test'})
                    assert login['retcode'] == 0
                    account = login['data']['account']
                    combo = request('/hkrpg_cn/combo/granter/login/v2/login', {'data': json.dumps({'uid': account['uid'], 'token': account['token']})})
                    assert combo['retcode'] == 0
                    credentials = {'accountUid': account['uid'], 'comboToken': combo['data']['combo_token']}
                for day, phase in phases:
                    probe(day, phase, process, server_log)
                time.sleep(1)
            except BaseException as error:
                primary = error
                raise
            finally:
                if cleanup(process, primary, normal_stop=True):
                    stops += 1
        last = snapshots[-1]
        data_day = 1 if last['phase'] == 'boundary-reject' else last['dayIndex']
        current_size = 9 if last['phase'] in ('finish', 'claimed') else (
            3 if scenario == 'expired-unpaid' and data_day == 1 and at == 'noReplies'
            else 9 if scenario == 'expired-unpaid' and data_day == 1 and at == 'bothReplies' else 5)
        control = directory / f'h2-control-{iteration}.json'
        control.write_text(json.dumps(dict(uid=last['uid'], scenario=scenario, expiredAt=at,
            firstDay=(first_clock + 14400) // 86400, runDay=(first_clock + 14400) // 86400 + data_day - 1,
            completed=last['phase'] in ('finish', 'claimed'), phoneSize=current_size,
            firstChoice=last['firstChoice'], secondChoice=last['secondChoice'],
            oldFirstChoice=old_first, oldSecondChoice=old_second)), 'utf-8')
        inspected = subprocess.run([str(java), *java_flags, '-cp', classpath,
            'org.hkrpg.server.gameplay.DailyMessageFixtureInspector', str(directory), str(control)],
            cwd=ROOT, capture_output=True, text=True, encoding='utf-8', creationflags=flags, timeout=45)
        (directory / f'h2-inspector-{iteration}.log').write_text(inspected.stdout + inspected.stderr, 'utf-8')
        assert inspected.returncode == 0, directory
        h2 = json.loads(next(line for line in reversed(inspected.stdout.splitlines()) if line.startswith('{')))
        assert h2['passed'] and h2['mvStoreReadOnly'] and h2['databaseFileBytesUnchanged']
        h2_views.append(h2)
        (directory / f'h2-check-{iteration}.json').write_text(json.dumps(h2, indent=2), 'utf-8')
        if scenario == 'expired-unpaid' and iteration == 1:
            assert h2_views[0]['persistedPhoneRuns'] == h2['persistedPhoneRuns']
            assert h2_views[0]['currentRun'] == h2['currentRun']
            assert h2['credit'] == 0 and h2['activePoints'] == 0 and not h2['runDays'], 'Old boundary request mutated its accepted run'
    expected_wallets = [0, 0, 5000, 5000, 10000] if scenario == 'settled' else [0, 0, 0, 0, 5000]
    assert len(snapshots) == 5 and stops == len(phases_by_process) and len({s['uid'] for s in snapshots}) == 1
    assert [s['loginWallet'] for s in snapshots] == expected_wallets
    assert sha(jar) == artifact and sha(config) == config_hash
    assert hashes == {name: sha(source / name) for name in hashes}
    assert all(not (source / name).exists() for name in source_only)
    return dict(passed=True, mainMission=3000602, scenario=scenario, branch=branch, expiredAt=at,
        jarSHA256=artifact, runtimeDirectory=str(directory), snapshots=snapshots, clockViews=views,
        readOnlyH2Inspections=h2_views, requests=sum(s['requests'] for s in snapshots),
        checks=sum(len(s['checks']) for s in snapshots), normalStopCount=stops,
        sameUIDNormalH2RestartCount=stops - 1, independentStoppedH2MorphiaReadCount=2 * len(h2_views),
        wireFields=wire_fields, sourceCommit=COMMIT, sourceSHA256=hashes, sourceOnlyMissingDialogueGraphs=source_only,
        actualOriginalMainCreditPerCompletedPeriod=5000, actualOriginalActivePointsPerCompletedPeriod=200,
        completedPeriodCount=2 if scenario == 'settled' else 1, sourceResourcesModified=False,
        fixture=dict(isolatedAutoUnlockMissions=prerequisites, singleOriginalCatalog=[3000602],
                     clocksOnly=True, finishedStateSeeded=False, originalMissionEntranceAndGroupGatesUnmodified=True),
        controlledCrossDayVerified=True, realWallClockCrossDayVerified=False,
        sourceClientScriptExecutorVerified=False, clientGameplayVerified=False, crashAtomicityVerified=False)


def main():
    if hasattr(sys.stdout, 'reconfigure'):
        sys.stdout.reconfigure(encoding='utf-8')
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--jar', type=Path, default=ROOT / 'build/libs/hkrpg-server.jar')
    parser.add_argument('--output', type=Path, default=ROOT / 'build' / ('daily-message-native-' + str(time.time_ns()) + '.json'))
    parser.add_argument('--scenario', choices=['settled', 'expired-unpaid'], default='settled')
    parser.add_argument('--branch', choices=BRANCHES, action='append')
    parser.add_argument('--expired-at', choices=EXPIRY_POINTS, action='append')
    args = parser.parse_args()
    jar, output = args.jar.resolve(), args.output.resolve()
    assert jar.is_file() and jar.is_relative_to((ROOT / 'build').resolve()) and output.is_relative_to((ROOT / 'build').resolve())
    java = next((ROOT / '.tools').glob('jdk-21*/bin/java.exe'), None)
    assert java
    records = json.loads((ROOT / 'src/test/resources/fixtures/daily-message-3000602-4.6.json').read_text('utf-8'))
    assert len(records) == 124 and all(r['sourceCommit'] == COMMIT for r in records)
    branches = args.branch or (list(BRANCHES) if args.scenario == 'settled' else ['202-205'])
    expiry_points = args.expired_at or (EXPIRY_POINTS if args.scenario == 'expired-unpaid' else ['oneReply'])
    assert len(branches) == len(set(branches)) and len(expiry_points) == len(set(expiry_points)), 'Duplicate finite cases are not new coverage'
    assert args.scenario != 'settled' or not args.expired_at, 'Expiry phases belong to the unpaid scenario'
    output.parent.mkdir(parents=True, exist_ok=True)
    assert not output.exists(), 'Choose an unused report path; previous native evidence is preserved'
    artifact = sha(jar)
    lease = output.with_name(output.name + '.lease.json')
    with lease.open('x', encoding='utf-8') as reservation:
        json.dump(dict(output=str(output), pid=os.getpid(), jarSHA256=artifact, startedEpoch=time.time()), reservation)
    results = [one(branch, at, jar, java, records, args.scenario, artifact) for branch in branches for at in expiry_points]
    assert sha(jar) == artifact
    result = dict(passed=True, scenario=args.scenario, rootJarSHA256=artifact, cases=results,
        snapshots=sum(len(c['snapshots']) for c in results), requests=sum(c['requests'] for c in results),
        checks=sum(c['checks'] for c in results), normalStopCount=sum(c['normalStopCount'] for c in results),
        sameUIDNormalH2RestartCount=sum(c['sameUIDNormalH2RestartCount'] for c in results),
        sourceResourcesModified=False, clientGameplayVerified=False, crashAtomicityVerified=False)
    with output.open('x', encoding='utf-8') as report:
        report.write(json.dumps(result, ensure_ascii=False, indent=2) + '\n')
    print(json.dumps(dict(passed=True, report=str(output), rootJarSHA256=artifact,
        requests=result['requests'], checks=result['checks'], snapshots=result['snapshots']), ensure_ascii=False))


if __name__ == '__main__':
    main()
