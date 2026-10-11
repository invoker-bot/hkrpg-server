"""原始楼层移动委托的实际 KCP、受控两日奖励及正常 H2 进程恢复；前置和入口门为隔离夹具。"""
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


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def port(kind=socket.SOCK_STREAM):
    with socket.socket(type=kind) as sock:
        sock.bind(('127.0.0.1', 0))
        return sock.getsockname()[1]


def one(main_id, jar, java, records):
    source = ROOT / 'resources/4.6'
    artifact = sha(jar)
    daily = [record['row'] for record in records if record['kind'] == 'daily' and record['row']['ID'] == main_id]
    assert len(daily) == 1
    prerequisites = [daily[0]['UnlockMainMission'], 1010403]
    original_entries = [1010201, 1010203] if main_id == 3012001 else [1020201, 1020204]
    mains = json.loads((source / 'ExcelOutput/MainMission.json').read_text('utf-8'))
    for row in mains:
        if row['MainMissionID'] in prerequisites:
            row.update(TakeParam=[{'Type': 'Auto'}], TakeOperation='And', BeginParam=[{'Type': 'Auto'}], BeginOperation='And', RewardID=0)
    entries = json.loads((source / 'ExcelOutput/MapEntrance.json').read_text('utf-8'))
    for row in entries:
        if row['ID'] in original_entries:
            row.update(BeginMainMissionList=[], FinishMainMissionList=[], FinishSubMissionList=[])
    pool = [record['row'] for record in records if record['kind'] == 'pool']
    replacements = {'ExcelOutput/MainMission.json': mains, 'ExcelOutput/MapEntrance.json': entries,
                    'ExcelOutput/DailyMissionData.json': daily, 'ExcelOutput/DailyActiveQuestPool.json': pool}
    guarded = {source / 'resource-manifest.json'} | {ROOT / record['source'] for record in records}
    guarded |= {path for path in (source / f'Config/Level/Mission/{main_id}').rglob('*') if path.is_file()}
    hashes = {path.relative_to(source).as_posix(): sha(path) for path in sorted(guarded)}
    runs = ROOT / 'build/smoke'
    runs.mkdir(exist_ok=True)
    directory = Path(tempfile.mkdtemp(prefix=f'daily-travel-{main_id}-', dir=runs))
    assert directory.resolve().is_relative_to(runs.resolve())
    fixture = directory / 'resources-fixture'
    bindings = mirror_resources(source, fixture, replacements)
    flags = subprocess.CREATE_NO_WINDOW if os.name == 'nt' else 0
    if bindings:
        plan = directory / 'junctions.json'
        plan.write_text(json.dumps(bindings), 'utf-8')
        helper = directory / 'prepare.ps1'
        helper.write_text("param([string]$Plan)\n$ErrorActionPreference='Stop'\nforeach ($binding in (Get-Content -LiteralPath $Plan -Raw | ConvertFrom-Json)) { New-Item -ItemType Junction -Path $binding.target -Value $binding.source | Out-Null }\n", 'utf-8')
        subprocess.run(['powershell', '-NoProfile', '-NonInteractive', '-File', str(helper), '-Plan', str(plan)], check=True, capture_output=True, creationflags=flags)
    for path in (source / f'Config/Level/Mission/{main_id}').rglob('*'):
        if not path.is_file():
            continue
        target = fixture / path.relative_to(source)
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(path, target)
        assert sha(path) == sha(target)
    for main in prerequisites:
        folder = fixture / f'Config/Level/Mission/{main}'
        folder.mkdir(parents=True, exist_ok=True)
        child = main * 100 + 99
        (folder / f'MissionInfo_{main}.json').write_text(json.dumps(dict(MainMissionID=main, StartSubMissionList=[child], FinishSubMissionList=[child])), 'utf-8')
        (folder / f'MissionInfo_{main}.partial.json').write_text(json.dumps(dict(SubMissionList=[dict(ID=child, MainMissionID=main, TakeType='Auto', FinishType='Auto', Progress=1)])), 'utf-8')
    (fixture / 'resource-manifest.json').write_text(json.dumps(dict(clientVersion='4.6.0', testFixture=True, dailyTravelFixture=True, originalMission=main_id)), 'utf-8')
    http, mongo, game = port(), port(), port(socket.SOCK_DGRAM)
    config = directory / 'config.json'
    config.write_text(json.dumps(dict(protocol=dict(wireVersion='4.6.0'), resourceDir=str(fixture),
        httpServer=dict(bindAddress='127.0.0.1', bindPort=http), gameServer=dict(bindAddress='127.0.0.1', bindPort=game),
        internalMongoServer=dict(address='127.0.0.1', port=mongo, filePath='./data/database.mv'))), 'utf-8')
    config_hash = sha(config)
    classes = directory / 'fixture-classes'
    classes.mkdir()
    clock_source = ROOT / 'scripts/fixtures/java/emu/lunarcore/command/commands/DailyTravelClockFixtureCommand.java'
    probe_source = ROOT / 'scripts/fixtures/java/org/hkrpg/server/protocol/NativeDailyTravelProbe.java'
    subprocess.run([str(java.with_name('javac.exe')), '-encoding', 'UTF-8', '-cp', str(jar), '-d', str(classes),
                    str(clock_source), str(probe_source)], check=True, capture_output=True, creationflags=flags)
    java_flags = ['-Dfile.encoding=UTF-8', '-Dstdout.encoding=UTF-8', '-Dstderr.encoding=UTF-8',
                  '-Djdk.net.unixdomain.tmpdir=' + str(directory / 'tcp-fallback'), '-Dio.netty.eventLoopThreads=2']
    classpath = str(jar) + os.pathsep + str(classes)
    command = [str(java), *java_flags, '-Xmx1536m', '-cp', classpath, 'org.hkrpg.server.HkrpgServer', '--no-handbook']
    base = f'http://127.0.0.1:{http}'
    local_http = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    credentials = None
    snapshots, views = [], []
    first_clock = None
    stops = 0

    def request(route, body=None):
        req = urllib.request.Request(base + route, data=None if body is None else json.dumps(body).encode(), headers={'Content-Type': 'application/json'})
        with local_http.open(req, timeout=5) as response:
            return json.load(response)

    def probe(day, phase, server, log):
        nonlocal first_clock
        assert sha(jar) == artifact and sha(config) == config_hash
        child = subprocess.Popen([str(java), *java_flags, '-cp', classpath, 'org.hkrpg.server.protocol.NativeDailyTravelProbe', str(game)],
            stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, encoding='utf-8', creationflags=flags)
        lines, available = [], queue.Queue()
        def read_output():
            for line in child.stdout:
                lines.append(line)
                available.put(line)
            available.put(None)
        reader = threading.Thread(target=read_output, daemon=True)
        reader.start()
        index = len(snapshots)
        try:
            child.stdin.write(json.dumps(dict(credentials, mainMission=main_id, dayIndex=day, phase=phase)) + '\n')
            child.stdin.flush()
            deadline = time.monotonic() + 60
            while True:
                line = available.get(timeout=max(.1, deadline - time.monotonic()))
                assert line is not None, directory
                if line.startswith('HKRPG_DAILY_CLOCK_FIXTURE_READY:'):
                    uid = int(line.strip().rsplit(':', 1)[1])
                    break
            if first_clock is None:
                first_clock = int(time.time()) + 1
                assert (first_clock + 14400) % 86400 < 86400 - 600, 'Too close to actual reset for the controlled fixture'
            target = first_clock + (day - 1) * 86400
            marker = f'HKRPG_DAILY_TRAVEL_CLOCK_ACK:{uid}:{target}:{index}'
            server.stdin.write(f'dailytravelclockfixture {uid} {target} {index}\n'.encode())
            server.stdin.flush()
            while marker not in log.read_text('utf-8', errors='replace'):
                assert server.poll() is None and child.poll() is None, directory
                if time.monotonic() > deadline:
                    raise TimeoutError(f'Clock fixture timeout: {directory}')
                time.sleep(.05)
            rows = [line.split('HKRPG_DAILY_TRAVEL_CLOCK_VIEW:', 1)[1] for line in log.read_text('utf-8', errors='replace').splitlines() if 'HKRPG_DAILY_TRAVEL_CLOCK_VIEW:' in line]
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
        finally:
            if child.poll() is None:
                child.terminate()
                child.wait(timeout=10)
            if child.stdin and not child.stdin.closed:
                child.stdin.close()
            reader.join(timeout=3)
            (directory / f'probe-{index}.log').write_text(''.join(lines), 'utf-8')

    for iteration, phases in enumerate([[(1, 'first'), (1, 'finish'), (1, 'unclaimed')],
                                       [(1, 'claimed'), (2, 'first'), (2, 'finish'), (2, 'unclaimed')], [(2, 'claimed')]]):
        server_log = directory / f'server-{iteration}.log'
        with server_log.open('w', encoding='utf-8') as log:
            process = subprocess.Popen(command, cwd=directory, stdin=subprocess.PIPE, stdout=log, stderr=subprocess.STDOUT, creationflags=flags)
            try:
                deadline = time.monotonic() + 90
                while True:
                    assert process.poll() is None, directory
                    try:
                        if request('/health/ready')['ready']:
                            break
                    except (OSError, urllib.error.URLError):
                        pass
                    if time.monotonic() > deadline:
                        raise TimeoutError(f'Daily travel readiness timeout: {directory}')
                    time.sleep(.2)
                if iteration == 0:
                    login = request('/hkrpg_cn/mdk/shield/api/login', {'account': f'cn-46-daily-travel-{main_id}', 'password': 'local-test'})
                    assert login['retcode'] == 0
                    account = login['data']['account']
                    combo = request('/hkrpg_cn/combo/granter/login/v2/login', {'data': json.dumps({'uid': account['uid'], 'token': account['token']})})
                    assert combo['retcode'] == 0
                    credentials = {'accountUid': account['uid'], 'comboToken': combo['data']['combo_token']}
                for day, phase in phases:
                    probe(day, phase, process, server_log)
                time.sleep(1)
            finally:
                if process.poll() is None:
                    try:
                        process.stdin.write(b'stop\n')
                        process.stdin.flush()
                        process.wait(timeout=30)
                        assert process.returncode == (1000 if os.name == 'nt' else 232), directory
                        stops += 1
                    except BaseException:
                        if process.poll() is None:
                            process.terminate()
                            process.wait(timeout=15)
                        raise
                    finally:
                        process.stdin.close()
    assert len(snapshots) == 8 and stops == 3 and len({row['uid'] for row in snapshots}) == 1
    assert [row['loginWallet'] for row in snapshots] == [0, 0, 5000, 5000, 5000, 5000, 10000, 10000]
    assert len(views[-1]['archives']) == 1 and not views[-1]['expired']
    old_day = (first_clock + 14400) // 86400
    archive = views[-1]['archives'][0]
    assert (archive['mainId'], archive['day'], archive['mainPhase'], archive['mainProgress']) == (main_id, old_day, 'CLOSED', 1)
    assert archive['rewardKeys'] == [f'mission:{main_id}']
    assert views[-1]['runDays'] == {str(main_id): old_day + 1}
    assert sha(jar) == artifact and sha(config) == config_hash
    assert hashes == {name: sha(source / name) for name in hashes}
    return dict(passed=True, mainMission=main_id, runtimeDirectory=str(directory), snapshots=snapshots,
                requests=sum(row['requests'] for row in snapshots), checks=sum(len(row['checks']) for row in snapshots),
                clockViews=views, sourceSHA256=hashes, normalStopCount=stops, sameUIDNormalH2RestartCount=2,
                actualOriginalMainCreditPerPeriod=5000, actualOriginalActivePointsPerPeriod=200,
                originalSourceMainAndTravelGraphsUnmodified=True, sourceResourcesModified=False,
                fixture=dict(isolatedAutoUnlockMissions=prerequisites, isolatedEntranceGates=original_entries,
                             singleOriginalCatalog=[main_id], clocksOnly=True, finishedStateSeeded=False),
                controlledCrossDayVerified=True, realWallClockCrossDayVerified=False,
                nativeSourceClientScriptExecutorVerified=False, clientGameplayVerified=False, crashAtomicityVerified=False)


def main():
    if hasattr(sys.stdout, 'reconfigure'):
        sys.stdout.reconfigure(encoding='utf-8')
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--jar', type=Path, default=ROOT / 'build/libs/hkrpg-server.jar')
    parser.add_argument('--output', type=Path, default=ROOT / 'build/daily-travel-native.json')
    args = parser.parse_args()
    jar, output = args.jar.resolve(), args.output.resolve()
    assert jar.is_file() and jar.is_relative_to(ROOT / 'build') and output.is_relative_to(ROOT / 'build')
    java = next((ROOT / '.tools').glob('jdk-21*/bin/java.exe'), None)
    assert java
    records = json.loads((ROOT / 'src/test/resources/fixtures/daily-travel-4.6.json').read_text('utf-8'))
    results = [one(main_id, jar, java, records) for main_id in [3012001, 3020204]]
    result = dict(passed=True, rootJarSHA256=sha(jar), cases=results,
                  snapshots=sum(len(case['snapshots']) for case in results),
                  requests=sum(case['requests'] for case in results), checks=sum(case['checks'] for case in results),
                  sameUIDNormalH2RestartCount=sum(case['sameUIDNormalH2RestartCount'] for case in results),
                  sourceResourcesModified=False, clientGameplayVerified=False, crashAtomicityVerified=False)
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n', 'utf-8')
    print(json.dumps(dict(passed=True, report=str(output), rootJarSHA256=result['rootJarSHA256'],
                         requests=result['requests'], checks=result['checks'], snapshots=result['snapshots']), ensure_ascii=False))


if __name__ == '__main__':
    main()
