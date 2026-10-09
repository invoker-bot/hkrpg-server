"""受控时钟的实际KCP未完成换日、新轮原始对话/奖励及两次H2恢复；非真实墙钟跨日。"""
import hashlib
import json
import os
from pathlib import Path
import shutil
import socket
import subprocess
import tempfile
import queue
import threading
import time
import urllib.error
import urllib.request
from importlib.machinery import SourceFileLoader
from smoke_resource_fixture import mirror_resources

ROOT = Path(__file__).resolve().parents[1]
free_port = SourceFileLoader('daily_mission_ports', str(ROOT/'scripts/smoke-mail.py')).load_module().free_port


def main():
    source = ROOT/'resources/4.6'
    jar = ROOT/'build/libs/hkrpg-server.jar'
    java = next((ROOT/'.tools').glob('jdk-21*/bin/java.exe'), None)
    assert java and jar.is_file()
    digest = hashlib.sha256(jar.read_bytes()).hexdigest()
    runs = ROOT/'build/smoke'
    runs.mkdir(exist_ok=True)
    directory = Path(tempfile.mkdtemp(prefix='daily-mission-expiry-', dir=runs))
    assert directory.resolve().is_relative_to(runs.resolve())
    fixture = directory/'resources-fixture'
    missions = [3000201]
    guarded = ['resource-manifest.json', 'ExcelOutput/MainMission.json', 'ExcelOutput/RewardData.json', 'ExcelOutput/MapEntrance.json', 'ExcelOutput/MazePlane.json', 'ExcelOutput/QuestData.json', 'ExcelOutput/FinishWay.json', 'ExcelOutput/DailyMissionData.json', 'ExcelOutput/DailyMissionCount.json', 'ExcelOutput/DailyActiveQuestPool.json', 'ExcelOutput/ItemConfig.json']
    for main in missions:
        guarded += [p.relative_to(source).as_posix() for p in (source/f'Config/Level/Mission/{main}').rglob('*') if p.is_file()]
    hashes = {name: hashlib.sha256((source/name).read_bytes()).hexdigest() for name in guarded}
    assert hashes['resource-manifest.json'] == 'ecccb21469df76282e6df5bf0cbe1eff7d9f904c2dd644092124b6904482e735'
    rows = json.loads((source/'ExcelOutput/MainMission.json').read_text('utf-8'))
    for row in rows:
        if row['MainMissionID'] in [2000116, 1010403]:
            row.update(TakeParam=[{'Type': 'Auto'}], TakeOperation='And', BeginParam=[{'Type': 'Auto'}], BeginOperation='And', RewardID=0)
    entrances = json.loads((source/'ExcelOutput/MapEntrance.json').read_text('utf-8'))
    for row in entrances:
        if row['ID'] == 2000301:
            row.update(BeginMainMissionList=[], FinishMainMissionList=[], FinishSubMissionList=[])
    daily = [row for row in json.loads((source/'ExcelOutput/DailyMissionData.json').read_text('utf-8')) if row['ID'] == 3000201]
    pool = [row for row in json.loads((source/'ExcelOutput/DailyActiveQuestPool.json').read_text('utf-8')) if row['QuestID'] == 2100003]
    assert len(daily) == len(pool) == 1
    junctions = mirror_resources(source, fixture, {'ExcelOutput/MainMission.json': rows, 'ExcelOutput/MapEntrance.json': entrances, 'ExcelOutput/DailyMissionData.json': daily, 'ExcelOutput/DailyActiveQuestPool.json': pool})
    # Explicit synthetic automatic unlock graphs; no finished states or rewards are seeded.
    for prerequisite in [2000116, 1010403]:
        sub = prerequisite*100+99
        folder = fixture/f'Config/Level/Mission/{prerequisite}'
        folder.mkdir(parents=True, exist_ok=True)
        (folder/f'MissionInfo_{prerequisite}.json').write_text(json.dumps({'MainMissionID': prerequisite, 'StartSubMissionList': [sub], 'FinishSubMissionList': [sub]}), 'utf-8')
        (folder/f'MissionInfo_{prerequisite}.partial.json').write_text(json.dumps({'SubMissionList': [{'ID': sub, 'MainMissionID': prerequisite, 'TakeType': 'Auto', 'FinishType': 'Auto', 'Progress': 1}]}), 'utf-8')
    flags = subprocess.CREATE_NO_WINDOW if os.name == 'nt' else 0
    if junctions:
        plan = directory/'junction-plan.json'
        plan.write_text(json.dumps(junctions), 'utf-8')
        helper = directory/'prepare-fixture.ps1'
        helper.write_text("param([string]$Plan)\n$ErrorActionPreference='Stop'\nforeach ($binding in (Get-Content -LiteralPath $Plan -Raw | ConvertFrom-Json)) { New-Item -ItemType Junction -Path $binding.target -Value $binding.source | Out-Null }\n", 'utf-8')
        subprocess.run(['powershell', '-NoProfile', '-NonInteractive', '-File', str(helper), '-Plan', str(plan)], check=True, capture_output=True, creationflags=flags)
    for name in guarded:
        if name.startswith('Config/Level/Mission/'):
            target = fixture/name
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(source/name, target)
            assert hashlib.sha256(target.read_bytes()).hexdigest() == hashes[name]
    (fixture/'resource-manifest.json').write_text(json.dumps({'clientVersion': '4.6.0', 'testFixture': True, 'originalMissions': missions}), 'utf-8')
    assert not os.path.samefile(fixture/'resource-manifest.json', source/'resource-manifest.json')
    http_port, mongo_port, game_port = free_port(), free_port(), free_port(socket.SOCK_DGRAM)
    (directory/'config.json').write_text(json.dumps({'protocol': {'wireVersion': '4.6.0'}, 'resourceDir': str(fixture),
        'httpServer': {'bindAddress': '127.0.0.1', 'bindPort': http_port}, 'gameServer': {'bindAddress': '127.0.0.1', 'bindPort': game_port},
        'internalMongoServer': {'address': '127.0.0.1', 'port': mongo_port, 'filePath': './data/database.mv'}}), 'utf-8')
    java_flags = ['-Dfile.encoding=UTF-8', '-Dstdout.encoding=UTF-8', '-Dstderr.encoding=UTF-8', '-Djdk.net.unixdomain.tmpdir='+str(directory/'tcp-fallback')]
    helper_source = ROOT/'scripts/fixtures/java/emu/lunarcore/command/commands/DailyMissionClockFixtureCommand.java'
    helper_classes = directory/'clock-fixture-classes'
    helper_classes.mkdir()
    subprocess.run([str(java.with_name('javac.exe')), '-encoding', 'UTF-8', '-cp', str(jar), '-d', str(helper_classes), str(helper_source)], check=True, capture_output=True, creationflags=flags)
    command = [str(java), *java_flags, '-Xmx1536m', '-cp', str(jar)+os.pathsep+str(helper_classes), 'org.hkrpg.server.HkrpgServer', '--no-handbook']
    clock_targets = []
    clock_views = []
    first_clock = None
    next_clock = None
    base = f'http://127.0.0.1:{http_port}'
    credentials = None
    snapshots = []
    stops = 0

    def request(route, body=None):
        req = urllib.request.Request(base+route, data=None if body is None else json.dumps(body).encode(), headers={'Content-Type': 'application/json'})
        with urllib.request.urlopen(req, timeout=5) as response:
            return json.load(response)

    def probe(phase, server, server_log):
        nonlocal first_clock, next_clock
        assert hashlib.sha256(jar.read_bytes()).hexdigest() == digest
        child = subprocess.Popen([str(java), *java_flags, '-cp', str(jar), 'org.hkrpg.server.protocol.LoopbackDailyMissionProbe', str(game_port)],
            stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, encoding='utf-8', creationflags=flags)
        lines = []
        available = queue.Queue()
        def read_output():
            for line in child.stdout:
                lines.append(line)
                available.put(line)
            available.put(None)
        reader = threading.Thread(target=read_output, daemon=True)
        reader.start()
        try:
            child.stdin.write(json.dumps(dict(credentials, phase=phase, clockFixture=True))+'\n')
            child.stdin.flush()
            deadline = time.monotonic()+60
            while True:
                line = available.get(timeout=max(.1,deadline-time.monotonic()))
                assert line is not None, directory
                if line.startswith('HKRPG_DAILY_CLOCK_FIXTURE_READY:'):
                    uid = int(line.strip().rsplit(':',1)[1]);break
            if first_clock is None:
                first_clock = int(time.time())+1
                next_clock = ((first_clock+14400)//86400+1)*86400-14400
                assert next_clock-first_clock>300, 'Too close to real reset for this controlled fixture'
            target = first_clock if not snapshots else next_clock
            nonce = len(snapshots)
            marker = f'HKRPG_DAILY_CLOCK_FIXTURE_ACK:{uid}:{target}:{nonce}'
            server.stdin.write(f'dailyclockfixture {uid} {target} {nonce}\n'.encode())
            server.stdin.flush()
            while marker not in server_log.read_text('utf-8',errors='replace'):
                assert server.poll() is None and child.poll() is None, directory
                if time.monotonic()>deadline:raise TimeoutError(f'Clock fixture ack timeout: {directory}')
                time.sleep(.05)
            clock_targets.append(target)
            view_lines = [line.split('HKRPG_DAILY_CLOCK_FIXTURE_VIEW:',1)[1] for line in server_log.read_text('utf-8',errors='replace').splitlines() if 'HKRPG_DAILY_CLOCK_FIXTURE_VIEW:' in line]
            view = json.loads(view_lines[-1])
            assert view['uid'] == uid and view['nonce'] == nonce and view['clock'] == target
            clock_views.append(view)
            child.stdin.write('resume\n');child.stdin.flush();child.stdin.close()
            child.wait(timeout=max(.1,deadline-time.monotonic()));reader.join(timeout=3)
            assert child.returncode==0, directory
            value=json.loads(next(line for line in reversed(lines) if line.startswith('{')))
            assert value['passed']
            value['clockFixtureEpochSecond']=target
            snapshots.append(value)
        finally:
            if child.poll() is None:child.terminate();child.wait(timeout=10)
            if child.stdin and not child.stdin.closed:child.stdin.close()
            reader.join(timeout=3)
            (directory/f'probe-{len(clock_targets)-1}.log').write_text(''.join(lines),'utf-8')

    for iteration, phases in enumerate([['first', 'first', 'finish'], ['unclaimed', 'claimed'], ['claimed']]):
        with (directory/f'server-{iteration}.log').open('w', encoding='utf-8') as log:
            process = subprocess.Popen(command, cwd=directory, stdin=subprocess.PIPE, stdout=log, stderr=subprocess.STDOUT, creationflags=flags)
            try:
                deadline = time.monotonic()+90
                while True:
                    assert process.poll() is None, directory
                    try:
                        if request('/health/ready')['ready']:
                            break
                    except (OSError, urllib.error.URLError):
                        pass
                    if time.monotonic() > deadline:
                        raise RuntimeError(f'Daily mission readiness timeout: {directory}')
                    time.sleep(.2)
                if iteration == 0:
                    login = request('/hkrpg_cn/mdk/shield/api/login', {'account': 'cn-46-daily-mission', 'password': 'local-test'})
                    assert login['retcode'] == 0
                    account = login['data']['account']
                    combo = request('/hkrpg_cn/combo/granter/login/v2/login', {'data': json.dumps({'uid': account['uid'], 'token': account['token']})})
                    assert combo['retcode'] == 0
                    credentials = {'accountUid': account['uid'], 'comboToken': combo['data']['combo_token']}
                for phase in phases:
                    probe(phase, process, directory/f'server-{iteration}.log')
                time.sleep(2)
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
    assert len(snapshots) == 6 and stops == 3
    assert [row['phase'] for row in snapshots] == ['first', 'first', 'finish', 'unclaimed', 'claimed', 'claimed']
    assert snapshots[2]['immediateQuestCompletionNotifyVerified'] and snapshots[2]['mainRewardNotifyBeforeResponseVerified']
    assert [row['loginWallet'] for row in snapshots] == [0, 0, 0, 5000, 5000, 5000]
    assert len(clock_targets)==6 and clock_targets[0]<clock_targets[1] and len(set(clock_targets[1:]))==1
    assert [len(view['expired']) for view in clock_views] == [0,0,1,1,1,1]
    first_day = (clock_targets[0]+14400)//86400
    next_day = (clock_targets[1]+14400)//86400
    for view in clock_views[2:]:
        assert view['expired'] == [{'mainId':3000201,'day':first_day,'expiredAt':clock_targets[1],
            'mainPhase':'DOING','mainProgress':0,'subCount':6,'aggregateProgress':3,'aggregateGoal':3}]
        assert view['runDays']['3000201'] == next_day
    assert len({row['uid'] for row in snapshots}) == 1
    assert hashes == {name: hashlib.sha256((source/name).read_bytes()).hexdigest() for name in guarded}
    assert hashlib.sha256(jar.read_bytes()).hexdigest() == digest
    print(json.dumps({'passed': True, 'runtimeDirectory': str(directory), 'snapshots': snapshots,
        'checks': [c for r in snapshots for c in r['checks']], 'normalStopCount': stops, 'normalStopCommandUsed': True,
        'clockFixtureViews': clock_views, 'normalH2ExpiredArchiveReadVerified': True,
        'rootJarSHA256': digest, 'sourceSHA256': hashes, 'sourceResourcesModified': False,
        'fixture': {'originalDailyMission': 3000201, 'isolatedAutoUnlockMissions': [2000116,1010403], 'isolatedEntranceGates': [2000301],
            'sourceDailyMainGraphUnmodified': True, 'originalDailyMainTakeGatesAndRewardUnmodified': True, 'dailyActiveQuestAndFinishWayUnmodified': True,
            'finishedMainSeeded': False, 'finishedQuestSeeded': False, 'isolatedOriginalDailyPool': [2100003], 'isolatedOriginalDailyCatalog': [3000201]},
        'immediateQuestCompletionNotifyBeforeResponseVerified': True, 'actualOriginalMainCreditNotifyVerified': True,
        'actualOriginal5000CreditAnd200PointsVerified': True, 'automaticAssignmentWithoutAcceptRequestVerified': True,
        'controlledClockKCPExpiryVerified': True, 'realWallClockCrossDayVerified': False, 'clockFixtureOnlyChangesClocks': True, 'fixtureClockTargets': clock_targets, 'clockFixtureSourceSHA256': hashlib.sha256(helper_source.read_bytes()).hexdigest(), 'sameUIDNormalH2RestartCount': 2, 'wholeTutorialUnlockChainVerified': False, 'nativeSourceClientScriptExecutorVerified': False,
        'realClientCrossDayVerified': False, 'clientGameplayVerified': False, 'crashAtomicityVerified': False}, ensure_ascii=False))



if __name__ == '__main__':
    main()
