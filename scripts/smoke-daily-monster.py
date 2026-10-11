"""三条原始击怪委托的实际 KCP 原怪/战斗/尾对话、受控两日奖励和正常 H2 恢复；胜利为构造的有效报告。"""
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


def one(main_id, jar, java, records, scenario, artifact):
    source = ROOT / 'resources/4.6'
    assert sha(jar) == artifact, 'Frozen jar changed between source cases'
    daily = [record['row'] for record in records if record['kind'] == 'daily' and record['row']['ID'] == main_id]
    assert len(daily) == 1
    prerequisites = [daily[0]['UnlockMainMission'], 1010403]
    original_entries = [2000201, 2000301]
    binding = next(record['row'] for record in records if record['kind'] == 'group' and record['row']['OwnerMainMissionID'] == main_id)
    monster = binding['MonsterList'][0]
    drop_path = source / 'ExcelOutput/MonsterDrop.json'
    drop_bytes = drop_path.read_bytes()
    drop_blob = subprocess.run(['git', '-C', str(ROOT / 'references/TurnBasedGameData'), 'show',
        '724b139d8c9c32d12552eb95745a4fee72bfe48b:ExcelOutput/MonsterDrop.json'], check=True, capture_output=True).stdout
    assert json.loads(drop_bytes) == json.loads(drop_blob), 'Original monster drop table differs from the fixed source'
    drops = [row for row in json.loads(drop_bytes) if row['MonsterTemplateID'] == monster['NPCMonsterID']
             and row.get('WorldLevel', 0) == 0]
    assert len(drops) == 1
    coins = [row for row in drops[0]['DisplayItemList'] if row['ItemID'] == 2]
    assert len(coins) == 1 and not coins[0].get('ItemNum', coins[0].get('ItemCount', 0))
    # Existing MonsterDropExcel uses AvatarExpReward for this original fixed credit drop.
    battle_credit = drops[0]['AvatarExpReward']
    assert battle_credit == 36
    drop_proof = dict(sourceCommit='724b139d8c9c32d12552eb95745a4fee72bfe48b',
        installedSHA256=sha(drop_path), sourceBlobSHA256=hashlib.sha256(drop_blob).hexdigest(),
        row=drops[0], worldLevel=0, existingCoreCreditPerAcceptedWin=battle_credit,
        coreConversion=dict(javaSource='emu/lunarcore/data/excel/MonsterDropExcel.java',
            method='onLoad', sourceField='AvatarExpReward', displayedCoinItem=2,
            displayedCoinCount=0, rule='Existing Java core uses AvatarExpReward as credit when the displayed coin count is zero',
            officialDropRateVerified=False))
    stages = {}
    for event in [record['row'] for record in records if record['kind'] == 'event' and record['row']['EventID'] == monster['EventID']]:
        stage = next(record['row'] for record in records if record['kind'] == 'stage' and record['row']['StageID'] == event['StageID'])
        stages[str(event.get('WorldLevel', 0))] = dict(stage=stage['StageID'], waves=[[value for value in wave.values() if value > 0] for wave in stage['MonsterList']])
    assert set(stages) == set(map(str, range(7)))
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
    guarded = {source / 'resource-manifest.json', drop_path} | {ROOT / record['source'] for record in records}
    guarded |= {path for path in (source / f'Config/Level/Mission/{main_id}').rglob('*') if path.is_file()}
    hashes = {path.relative_to(source).as_posix(): sha(path) for path in sorted(guarded)}
    runs = ROOT / 'build/smoke'
    runs.mkdir(exist_ok=True)
    directory = Path(tempfile.mkdtemp(prefix=f'daily-monster-{main_id}-', dir=runs))
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
    # The original KillMonster scripts belong to 3000201, not their current main's folder.
    for record in records:
        if record['kind'] != 'script':
            continue
        origin = (ROOT / record['source']).resolve()
        relative = origin.relative_to(source.resolve())
        assert relative.as_posix().startswith('Config/Level/Mission/')
        target = fixture / relative
        assert target.resolve().is_relative_to(fixture.resolve())
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(origin, target)
        assert sha(origin) == sha(target) == record['sha256']
    for main in prerequisites:
        folder = fixture / f'Config/Level/Mission/{main}'
        folder.mkdir(parents=True, exist_ok=True)
        child = main * 100 + 99
        (folder / f'MissionInfo_{main}.json').write_text(json.dumps(dict(MainMissionID=main, StartSubMissionList=[child], FinishSubMissionList=[child])), 'utf-8')
        (folder / f'MissionInfo_{main}.partial.json').write_text(json.dumps(dict(SubMissionList=[dict(ID=child, MainMissionID=main, TakeType='Auto', FinishType='Auto', Progress=1)])), 'utf-8')
    (fixture / 'resource-manifest.json').write_text(json.dumps(dict(clientVersion='4.6.0', testFixture=True, dailyMonsterFixture=True, originalMission=main_id)), 'utf-8')
    http, mongo, game = port(), port(), port(socket.SOCK_DGRAM)
    config = directory / 'config.json'
    config.write_text(json.dumps(dict(protocol=dict(wireVersion='4.6.0'), resourceDir=str(fixture),
        httpServer=dict(bindAddress='127.0.0.1', bindPort=http), gameServer=dict(bindAddress='127.0.0.1', bindPort=game),
        internalMongoServer=dict(address='127.0.0.1', port=mongo, filePath='./data/database.mv'))), 'utf-8')
    config_hash = sha(config)
    classes = directory / 'fixture-classes'
    classes.mkdir()
    clock_source = ROOT / 'scripts/fixtures/java/emu/lunarcore/command/commands/DailyTravelClockFixtureCommand.java'
    probe_source = ROOT / 'scripts/fixtures/java/org/hkrpg/server/protocol/NativeDailyMonsterProbe.java'
    inspector_source = ROOT / 'scripts/fixtures/java/org/hkrpg/server/gameplay/DailyMonsterFixtureInspector.java'
    subprocess.run([str(java.with_name('javac.exe')), '-encoding', 'UTF-8', '-cp', str(jar), '-d', str(classes),
                    str(clock_source), str(probe_source), str(inspector_source)], check=True, capture_output=True, creationflags=flags)
    java_flags = ['-Dfile.encoding=UTF-8', '-Dstdout.encoding=UTF-8', '-Dstderr.encoding=UTF-8',
                  '-Djdk.net.unixdomain.tmpdir=' + str(directory / 'tcp-fallback'), '-Dio.netty.eventLoopThreads=2']
    classpath = str(jar) + os.pathsep + str(classes)
    wire = subprocess.run([str(java), *java_flags, '-cp', classpath,
        'org.hkrpg.server.protocol.NativeDailyMonsterProbe', '--wire-fields'], cwd=ROOT, check=True,
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

    def request(route, body=None):
        req = urllib.request.Request(base + route, data=None if body is None else json.dumps(body).encode(), headers={'Content-Type': 'application/json'})
        with local_http.open(req, timeout=5) as response:
            return json.load(response)

    def probe(day, phase, server, log):
        nonlocal first_clock
        assert sha(jar) == artifact and sha(config) == config_hash
        child = subprocess.Popen([str(java), *java_flags, '-cp', classpath, 'org.hkrpg.server.protocol.NativeDailyMonsterProbe', str(game)],
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
            child.stdin.write(json.dumps(dict(credentials, mainMission=main_id, dayIndex=day, phase=phase,
                unpaidPriorRun=scenario == 'expired-unpaid', npcMonster=monster['NPCMonsterID'], event=monster['EventID'],
                existingCoreBattleCredit=battle_credit, stages=stages)) + '\n')
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

    schedule = [[(1, 'first'), (1, 'kill')],
                [(1, 'tail'), (1, 'unclaimed'), (1, 'claimed'), (2, 'first'), (2, 'kill')],
                [(2, 'tail'), (2, 'unclaimed'), (2, 'claimed')]]
    if scenario == 'expired-unpaid':
        schedule[1] = [(2, 'first'), (2, 'kill')]
    for iteration, phases in enumerate(schedule):
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
                    login = request('/hkrpg_cn/mdk/shield/api/login', {'account': f'cn-46-daily-monster-{main_id}', 'password': 'local-test'})
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
        # The stopped MVStore is opened read-only; no gameplay refresh or state seeding occurs.
        control = directory / f'h2-control-{iteration}.json'
        control.write_text(json.dumps(dict(uid=snapshots[-1]['uid'], mainMission=main_id,
            firstDay=(first_clock + 14400) // 86400, runDay=(first_clock + 14400) // 86400 + (iteration > 0),
            completed=iteration == 2, scenario=scenario, existingCoreBattleCredit=battle_credit)), 'utf-8')
        inspected = subprocess.run([str(java), *java_flags, '-cp', classpath,
            'org.hkrpg.server.gameplay.DailyMonsterFixtureInspector', str(directory), str(control)],
            cwd=ROOT, capture_output=True, text=True, encoding='utf-8', creationflags=flags, timeout=45)
        (directory / f'h2-inspector-{iteration}.log').write_text(inspected.stdout + inspected.stderr, 'utf-8')
        assert inspected.returncode == 0, directory
        h2 = json.loads(next(line for line in reversed(inspected.stdout.splitlines()) if line.startswith('{')))
        assert h2['passed'] and h2['mvStoreReadOnly'] and h2['databaseFileBytesUnchanged']
        h2_views.append(h2)
        (directory / f'h2-check-{iteration}.json').write_text(json.dumps(h2, indent=2), 'utf-8')
    expected_wallets = [0, 0, 36, 5036, 5036, 5036, 5036, 5072, 10072, 10072]
    if scenario == 'expired-unpaid':
        expected_wallets = [0, 0, 36, 36, 72, 5072, 5072]
    assert len(snapshots) == len(expected_wallets) and stops == 3 and len({row['uid'] for row in snapshots}) == 1
    assert [row['loginWallet'] for row in snapshots] == expected_wallets
    old_day = (first_clock + 14400) // 86400
    if scenario == 'expired-unpaid':
        assert not views[-1]['archives'] and len(views[-1]['expired']) == 1
        expired = views[-1]['expired'][0]
        assert (expired['mainId'], expired['day'], expired['mainPhase'], expired['mainProgress']) == (main_id, old_day, 'DOING', 0)
    else:
        assert len(views[-1]['archives']) == 1 and not views[-1]['expired']
        archive = views[-1]['archives'][0]
        assert (archive['mainId'], archive['day'], archive['mainPhase'], archive['mainProgress']) == (main_id, old_day, 'CLOSED', 1)
        assert archive['rewardKeys'] == [f'mission:{main_id}']
    assert views[-1]['runDays'] == {str(main_id): old_day + 1}
    assert sha(jar) == artifact and sha(config) == config_hash
    assert hashes == {name: sha(source / name) for name in hashes}
    return dict(passed=True, mainMission=main_id, scenario=scenario, jarSHA256=artifact,
                runtimeDirectory=str(directory), snapshots=snapshots,
                requests=sum(row['requests'] for row in snapshots), checks=sum(len(row['checks']) for row in snapshots),
                clockViews=views, sourceSHA256=hashes, normalStopCount=stops, sameUIDNormalH2RestartCount=2,
                wireFields=wire_fields, readOnlyH2Inspections=h2_views,
                independentStoppedH2MorphiaReadCount=sum(row['independentMorphiaReadCount'] for row in h2_views),
                originalMonsterDropProof=drop_proof, existingCoreBattleCreditPerAcceptedWin=battle_credit,
                actualOriginalMainCreditPerCompletedPeriod=5000, actualOriginalActivePointsPerCompletedPeriod=200,
                completedPeriodCount=1 if scenario == 'expired-unpaid' else 2,
                originalSourceMainAndKillGraphsUnmodified=True, sourceResourcesModified=False,
                fixture=dict(isolatedAutoUnlockMissions=prerequisites, isolatedEntranceGates=original_entries,
                             singleOriginalCatalog=[main_id], clocksOnly=True, finishedStateSeeded=False),
                controlledCrossDayVerified=True, realWallClockCrossDayVerified=False,
                nativeSourceClientScriptExecutorVerified=False, constructedAcceptedBattleWinReports=True, actualClientBattleSimulationVerified=False, clientGameplayVerified=False, crashAtomicityVerified=False)


def main():
    if hasattr(sys.stdout, 'reconfigure'):
        sys.stdout.reconfigure(encoding='utf-8')
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--jar', type=Path, default=ROOT / 'build/libs/hkrpg-server.jar')
    parser.add_argument('--output', type=Path, default=ROOT / 'build' / ('daily-killmonster-native-' + str(time.time_ns()) + '.json'))
    parser.add_argument('--scenario', choices=['settled', 'expired-unpaid'], default='settled')
    parser.add_argument('--main-mission', type=int, choices=[3000211, 3000212, 3000213], action='append',
                        help='只验指定来源委托；可重复，默认三条')
    args = parser.parse_args()
    jar, output = args.jar.resolve(), args.output.resolve()
    assert jar.is_file() and jar.is_relative_to(ROOT / 'build') and output.is_relative_to(ROOT / 'build')
    java = next((ROOT / '.tools').glob('jdk-21*/bin/java.exe'), None)
    assert java
    records = json.loads((ROOT / 'src/test/resources/fixtures/daily-killmonster-4.6.json').read_text('utf-8'))
    output.parent.mkdir(parents=True, exist_ok=True)
    assert not output.exists(), 'Choose an unused output path; existing native evidence is preserved'
    lease = output.with_name(output.name + '.lease.json')
    artifact = sha(jar)
    with lease.open('x', encoding='utf-8') as reservation:
        json.dump(dict(output=str(output), pid=os.getpid(), jarSHA256=artifact, startedEpoch=time.time()), reservation)
    missions = args.main_mission or [3000211, 3000212, 3000213]
    assert len(missions) == len(set(missions)), 'Duplicate source mission is not additional coverage'
    results = [one(main_id, jar, java, records, args.scenario, artifact) for main_id in missions]
    assert sha(jar) == artifact
    result = dict(passed=True, scenario=args.scenario, rootJarSHA256=artifact, cases=results,
                  snapshots=sum(len(case['snapshots']) for case in results),
                  requests=sum(case['requests'] for case in results), checks=sum(case['checks'] for case in results),
                  sameUIDNormalH2RestartCount=sum(case['sameUIDNormalH2RestartCount'] for case in results),
                  sourceResourcesModified=False, clientGameplayVerified=False, crashAtomicityVerified=False)
    output.parent.mkdir(parents=True, exist_ok=True)
    with output.open('x', encoding='utf-8') as report:
        report.write(json.dumps(result, ensure_ascii=False, indent=2) + '\n')
    print(json.dumps(dict(passed=True, report=str(output), rootJarSHA256=result['rootJarSHA256'],
                         requests=result['requests'], checks=result['checks'], snapshots=result['snapshots']), ensure_ascii=False))


if __name__ == '__main__':
    main()
