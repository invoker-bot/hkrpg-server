"""G61 原 NPC 初遇、原条件菜单、Talk/Phone 两日与同 UID 正常 H2 恢复；客户端演出/UI 未验。"""
import argparse
import base64
from decimal import Decimal
import hashlib
import importlib.util
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

_spec = importlib.util.spec_from_file_location('g61_daily_message_support', ROOT / 'scripts/smoke-daily-message.py')
_support = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(_support)
COMMIT = _support.COMMIT
PHASES = [[(1, 'intro')], [(1, 'open')], [(1, 'finish')], [(1, 'claimed'), (2, 'open')],
          [(2, 'finish'), (2, 'claimed')]]
INTRO = 'Config/Level/NPC/W0/NPC_DefaultStory_500001301.json'
MAIN_GRAPH = 'Config/Level/NPCDialogue/P20002/F20002001_G61/DialogueMain_F20002001_G61_N400001.json'
PACK = 'Config/Level/NPCDialogue/P20002/F20002001_G61/DialoguePack_Transferred_F20002001_G61_N400001.json'
GROUP = 'Config/LevelOutput/SharedRuntimeGroup/Groups_P20002_F20002001/LevelGroup_P20002_F20002001_G61.json'
sha = _support.sha
cleanup = _support.cleanup


class NumberLiteral(str):
    """Gson JsonElement canonicalization retains each original JSON number's lexical spelling."""


def canonical(value):
    if isinstance(value, NumberLiteral):
        return str(value)
    if isinstance(value, dict):
        return '{' + ','.join(json.dumps(key, ensure_ascii=False) + ':' + canonical(value[key])
                              for key in sorted(value)) + '}'
    if isinstance(value, list):
        return '[' + ','.join(canonical(child) for child in value) + ']'
    return json.dumps(value, ensure_ascii=False, separators=(',', ':')).replace('\u2028', '\\u2028').replace('\u2029', '\\u2029')


def fingerprint(value):
    return hashlib.sha256(canonical(value).encode('utf-8')).hexdigest()


def original_source_identity(blobs):
    parsed = {name: json.loads(blobs[name], parse_int=NumberLiteral, parse_float=NumberLiteral)
              for name in (INTRO, MAIN_GRAPH, PACK, GROUP)}
    pins = {name: fingerprint(value) for name, value in parsed.items()}
    return dict(source='4.6:G61:N10206:90000103:' + ':'.join(pins[name] for name in (INTRO, MAIN_GRAPH, PACK, GROUP)),
                npcSource=fingerprint(parsed[GROUP]['NPCList'][0]), groupSource=pins[GROUP], scriptSource=pins[INTRO])


def source_preflight(source, fixture):
    """All fifty complete originals are checked, including newly decoded data with distinct formatting."""
    assert fixture['sourceCommit'] == COMMIT and len(fixture['records']) == 240 and len(fixture['sourceFiles']) == 50
    source = source.resolve()
    blobs, hashes, proof = {}, {}, []
    for relative, record in fixture['sourceFiles'].items():
        origin = source / relative
        assert origin.resolve().is_relative_to(source) and origin.is_file(), 'G61 original is unavailable: ' + relative
        blob = subprocess.run(['git', '-C', str(ROOT / 'references/TurnBasedGameData'), 'show',
                               COMMIT + ':' + relative], check=True, capture_output=True).stdout
        original_sha = hashlib.sha256(blob).hexdigest()
        assert original_sha == record['sourceBlobSHA256'], 'Complete fixed original changed: ' + relative
        if 'sourceBlobBase64' in record:
            assert base64.b64decode(record['sourceBlobBase64'], validate=True) == blob
        current = origin.read_bytes()
        number = lambda value: ('json-number', Decimal(value))
        assert json.loads(current, parse_int=number, parse_float=number) == json.loads(blob, parse_int=number, parse_float=number), 'Installed entire source object differs: ' + relative
        if record['installation'] == 'present':
            assert sha(origin) == record['sha256'], 'Previously installed source bytes changed: ' + relative
        else:
            assert record['installation'] == 'missing' and record['sha256'] is None
        blobs[relative] = blob
        hashes[relative] = hashlib.sha256(current).hexdigest()
        proof.append(dict(path=relative, fixedOriginalSHA256=original_sha, installedSHA256=hashes[relative],
                          originalWholeObjectEqual=True, historicalInstallation=record['installation'],
                          currentInstallation='present_matching'))
    return blobs, hashes, proof


def reserve_output(jar, output):
    if output.exists() or output.with_name(output.name + '.failed.json').exists():
        raise FileExistsError('Choose an unused report path; preserve earlier native evidence')
    output.parent.mkdir(parents=True, exist_ok=True)
    lease = output.with_name(output.name + '.lease.json')
    with lease.open('x', encoding='utf-8') as reservation:
        json.dump(dict(output=str(output), pid=os.getpid(), jarSHA256=sha(jar), startedEpoch=time.time()), reservation)
    return lease


def transport_ports():
    http = _support.port()
    for _ in range(16):
        mongo = _support.port()
        if mongo != http:
            return http, mongo, _support.port(socket.SOCK_DGRAM)
    raise RuntimeError('Could not allocate distinct loopback HTTP and Mongo TCP ports')


def projected_clock(anchor, day):
    assert day in (1, 2), 'Unknown finite fixture period'
    current = max(anchor, int(time.time()) + 1)
    assert (current + 14400) // 86400 == (anchor + 14400) // 86400, 'Controlled fixture crossed wall-clock reset'
    return current + (day - 1) * 86400


def local_request(http, route, body=None):
    req = urllib.request.Request(f'http://127.0.0.1:{http}' + route,
                                 data=None if body is None else json.dumps(body).encode('utf-8'),
                                 headers={'Content-Type': 'application/json'})
    with urllib.request.build_opener(urllib.request.ProxyHandler({})).open(req, timeout=5) as response:
        return json.load(response)


def one(jar, java, artifact):
    assert sha(jar) == artifact, 'Frozen G61 jar changed'
    source = (ROOT / 'resources/4.6').resolve()
    fixture_data = json.loads((ROOT / 'src/test/resources/fixtures/g61-npc-first-talk-4.6.json').read_text('utf-8'))
    blobs, hashes, source_proof = source_preflight(source, fixture_data)
    identity = original_source_identity(blobs)
    prerequisites = [1000401, 1010201, 1010403]
    daily = [record['row'] for record in fixture_data['records']
             if record['kind'] == 'daily' and record['row']['ID'] == 3000602]
    pool = [record['row'] for record in fixture_data['records'] if record['kind'] == 'pool']
    assert len(daily) == 1 and pool
    # Each required original is an actual local fixture file. NPC rule path checks reject source junctions.
    replacements = {name: None for name in fixture_data['sourceFiles']}
    replacements.update({'ExcelOutput/DailyMissionData.json': daily, 'ExcelOutput/DailyActiveQuestPool.json': pool})
    for main in prerequisites:
        original_folder = source / 'Config/Level/Mission' / str(main)
        assert original_folder.is_dir(), 'Original prerequisite mission folder missing: ' + str(main)
        for origin in original_folder.rglob('*'):
            if origin.is_file():
                assert origin.resolve().is_relative_to(source)
                hashes[origin.relative_to(source).as_posix()] = sha(origin)
    hashes['resource-manifest.json'] = sha(source / 'resource-manifest.json')
    runs = (ROOT / 'build/smoke').resolve()
    runs.mkdir(parents=True, exist_ok=True)
    directory = Path(tempfile.mkdtemp(prefix='daily-message-g61-native-', dir=runs)).resolve()
    assert directory.is_relative_to(runs)
    print(json.dumps(dict(runtimeDirectory=str(directory), stage='prepared-owned-G61-runtime'), ensure_ascii=False), flush=True)
    (directory / 'original-source-preflight.json').write_text(json.dumps(source_proof, ensure_ascii=False, indent=2), 'utf-8')
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
    for relative in hashes:
        if relative == 'resource-manifest.json' or relative in ('ExcelOutput/DailyMissionData.json', 'ExcelOutput/DailyActiveQuestPool.json'):
            continue
        target = fixture / relative
        assert target.resolve().is_relative_to(fixture.resolve())
        target.parent.mkdir(parents=True, exist_ok=True)
        target.unlink(missing_ok=True)  # Detach any fixture hard link before writing the fixture copy.
        shutil.copyfile(source / relative, target)
        assert sha(target) == hashes[relative]
    (fixture / 'resource-manifest.json').write_text(json.dumps(dict(clientVersion='4.6.0', testFixture=True,
        g61NpcFirstTalkFixture=True, dailyMessageFixture=True, originalMission=3000602)), 'utf-8')
    http, mongo, game = transport_ports()
    config = directory / 'config.json'
    config.write_text(json.dumps(dict(protocol=dict(wireVersion='4.6.0'), resourceDir=str(fixture),
        httpServer=dict(bindAddress='127.0.0.1', bindPort=http), gameServer=dict(bindAddress='127.0.0.1', bindPort=game),
        internalMongoServer=dict(address='127.0.0.1', port=mongo, filePath='./data/database.mv'))), 'utf-8')
    config_hash = sha(config)
    classes = directory / 'fixture-classes'
    classes.mkdir()
    sources = [ROOT / 'scripts/fixtures/java' / path for path in [
        'emu/lunarcore/command/commands/DailyTravelClockFixtureCommand.java',
        'emu/lunarcore/command/commands/NpcFirstTalkFixtureCommand.java',
        'org/hkrpg/server/protocol/NativeG61FirstTalkProbe.java',
        'org/hkrpg/server/gameplay/G61FirstTalkFixtureInspector.java',
        'org/hkrpg/server/gameplay/DailyMessageFixtureInspector.java']]
    subprocess.run([str(java.with_name('javac.exe')), '-encoding', 'UTF-8', '-cp', str(jar), '-d', str(classes),
                    *map(str, sources)], check=True, capture_output=True, creationflags=flags)
    java_flags = ['-Dfile.encoding=UTF-8', '-Dstdout.encoding=UTF-8', '-Dstderr.encoding=UTF-8',
                  '-Djdk.net.unixdomain.tmpdir=' + str(directory / 'tcp-fallback'), '-Dio.netty.eventLoopThreads=2']
    classpath = str(jar) + os.pathsep + str(classes)
    wire = subprocess.run([str(java), *java_flags, '-cp', classpath,
        'org.hkrpg.server.protocol.NativeG61FirstTalkProbe', '--wire-fields'], cwd=ROOT, check=True,
        capture_output=True, text=True, encoding='utf-8', creationflags=flags)
    wire_fields = json.loads(wire.stdout)
    assert len(wire_fields) == 35 and wire_fields['MissionTimestampDeclared'] is False
    (directory / 'wire-fields.json').write_text(json.dumps(wire_fields, indent=2), 'utf-8')
    actual_source = subprocess.run([str(java), *java_flags, '-cp', classpath,
        'org.hkrpg.server.gameplay.G61FirstTalkFixtureInspector', '--source-identity', str(fixture)],
        cwd=ROOT, check=True, capture_output=True, text=True, encoding='utf-8', creationflags=flags)
    assert json.loads(actual_source.stdout) == identity, 'Actual Java/Gson source identity differs from the fixed originals'
    (directory / 'original-source-identity.json').write_text(json.dumps(identity, indent=2), 'utf-8')
    command = [str(java), *java_flags, '-Xmx1536m', '-cp', classpath, 'org.hkrpg.server.HkrpgServer', '--no-handbook']
    credentials, first_clock, observation = None, None, None
    snapshots, clock_views, fixture_views, h2_views, phone_h2_views = [], [], [], [], []
    stops = 0

    def wait_marker(server, log, marker, child, deadline):
        while marker not in log.read_text('utf-8', errors='replace'):
            assert server.poll() is None and child.poll() is None, directory
            if time.monotonic() > deadline:
                raise TimeoutError('G61 console fixture timed out: ' + str(directory))
            time.sleep(.05)

    def probe(day, phase, server, server_log):
        nonlocal first_clock
        assert sha(jar) == artifact and sha(config) == config_hash
        first, second = (121140202, 121140205) if day == 1 else (121140203, 121140207)
        child = subprocess.Popen([str(java), *java_flags, '-cp', classpath,
            'org.hkrpg.server.protocol.NativeG61FirstTalkProbe', str(game)], stdin=subprocess.PIPE,
            stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, encoding='utf-8', creationflags=flags)
        lines, available = [], queue.Queue()
        def read_output():
            for line in child.stdout:
                lines.append(line)
                available.put(line)
            available.put(None)
        reader = threading.Thread(target=read_output, daemon=True)
        reader.start()
        index, primary = len(snapshots), None
        try:
            child.stdin.write(json.dumps(dict(credentials, dayIndex=day, phase=phase, firstChoice=first,
                secondChoice=second, originalMenuPack=str(fixture / PACK))) + '\n')
            child.stdin.flush()
            deadline = time.monotonic() + 120
            while True:
                line = available.get(timeout=max(.1, deadline - time.monotonic()))
                assert line is not None, directory
                if line.startswith('HKRPG_G61_FIXTURE_READY:'):
                    uid = int(line.strip().rsplit(':', 1)[1])
                    break
            mode = 'intro' if phase == 'intro' else 'commission' if day == 1 and phase == 'open' else 'inspect'
            server.stdin.write(f'npcfirsttalkfixture {uid} {mode} {index}\n'.encode('utf-8'))
            server.stdin.flush()
            wait_marker(server, server_log, f'HKRPG_G61_FIXTURE_ACK:{uid}:{mode}:{index}', child, deadline)
            rows = [line.split('HKRPG_G61_FIXTURE_VIEW:', 1)[1]
                    for line in server_log.read_text('utf-8', errors='replace').splitlines() if 'HKRPG_G61_FIXTURE_VIEW:' in line]
            view = json.loads(rows[-1])
            assert view['uid'] == uid and view['mode'] == mode and view['nonce'] == index and view['sourceRowsModified'] is False
            assert view['seededPrerequisiteHistory'] == ([1000401] if mode == 'intro' else [1010201, 1010403] if mode == 'commission' else [])
            fixture_views.append(view)
            if first_clock is None:
                first_clock = int(time.time()) + 1
                assert (first_clock + 14400) % 86400 < 86400 - 900, 'Controlled fixture is too close to wall-clock reset'
            target = projected_clock(first_clock, day)
            server.stdin.write(f'dailytravelclockfixture {uid} {target} {index}\n'.encode('utf-8'))
            server.stdin.flush()
            wait_marker(server, server_log, f'HKRPG_DAILY_TRAVEL_CLOCK_ACK:{uid}:{target}:{index}', child, deadline)
            rows = [line.split('HKRPG_DAILY_TRAVEL_CLOCK_VIEW:', 1)[1]
                    for line in server_log.read_text('utf-8', errors='replace').splitlines() if 'HKRPG_DAILY_TRAVEL_CLOCK_VIEW:' in line]
            clock_view = json.loads(rows[-1])
            assert clock_view['uid'] == uid and clock_view['clock'] == target and clock_view['nonce'] == index
            clock_views.append(clock_view)
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
            finally:
                try:
                    reader.join(timeout=3)
                    (directory / f'probe-{index}.log').write_text(''.join(lines), 'utf-8')
                except BaseException as log_error:
                    if primary is None:
                        raise
                    primary.add_note('Owned G61 probe log failed: ' + repr(log_error))

    def inspect(class_name, control, name):
        path = directory / (name + '-control.json')
        path.write_text(json.dumps(control), 'utf-8')
        result = subprocess.run([str(java), *java_flags, '-cp', classpath, class_name, str(directory), str(path)],
            cwd=ROOT, capture_output=True, text=True, encoding='utf-8', creationflags=flags, timeout=45)
        (directory / (name + '.log')).write_text(result.stdout + result.stderr, 'utf-8')
        assert result.returncode == 0, directory
        data = json.loads(next(line for line in reversed(result.stdout.splitlines()) if line.startswith('{')))
        assert data['passed'] and data['mvStoreReadOnly'] and data['databaseFileBytesUnchanged']
        (directory / (name + '.json')).write_text(json.dumps(data, indent=2), 'utf-8')
        return data

    for iteration, phases in enumerate(PHASES):
        server_log = directory / f'server-{iteration}.log'
        with server_log.open('w', encoding='utf-8') as log:
            process = subprocess.Popen(command, cwd=directory, stdin=subprocess.PIPE,
                                       stdout=log, stderr=subprocess.STDOUT, creationflags=flags)
            primary, ready = None, False
            try:
                deadline = time.monotonic() + 90
                while True:
                    assert process.poll() is None, directory
                    try:
                        ready = local_request(http, '/health/ready')['ready'] is True
                        if ready:
                            break
                    except (OSError, urllib.error.URLError):
                        pass
                    if time.monotonic() > deadline:
                        raise TimeoutError('G61 readiness timed out: ' + str(directory))
                    time.sleep(.2)
                if iteration == 0:
                    login = local_request(http, '/hkrpg_cn/mdk/shield/api/login',
                        {'account': 'cn-46-g61-native-' + directory.name, 'password': 'local-test'})
                    assert login['retcode'] == 0
                    account = login['data']['account']
                    combo = local_request(http, '/hkrpg_cn/combo/granter/login/v2/login',
                        {'data': json.dumps({'uid': account['uid'], 'token': account['token']})})
                    assert combo['retcode'] == 0
                    credentials = {'accountUid': account['uid'], 'comboToken': combo['data']['combo_token']}
                for day, phase in phases:
                    probe(day, phase, process, server_log)
                time.sleep(1)
            except BaseException as error:
                primary = error
                raise
            finally:
                if cleanup(process, primary, normal_stop=ready):
                    stops += 1
        last = snapshots[-1]
        credit = ((last['dayIndex'] - 1) + (last['phase'] in ('finish', 'claimed'))) * 5000
        control = dict(uid=last['uid'], credit=credit, introOnly=iteration == 0, sourceIdentity=identity)
        if observation is not None:
            control['expectedObservation'] = observation
        else:
            control['actorWireIdentity'] = snapshots[0]['actorWireIdentity']
        h2 = inspect('org.hkrpg.server.gameplay.G61FirstTalkFixtureInspector', control, f'g61-h2-{iteration}')
        observation = h2['observation']
        h2_views.append(h2)
        if iteration > 0:
            day = (first_clock + 14400) // 86400
            control = dict(uid=last['uid'], firstDay=day, runDay=day + last['dayIndex'] - 1,
                scenario='settled', expiredAt='oneReply', phoneSize=5 if last['phase'] == 'open' else 9,
                completed=last['phase'] in ('finish', 'claimed'), firstChoice=last['firstChoice'],
                secondChoice=last['secondChoice'], oldFirstChoice=121140202, oldSecondChoice=121140205)
            phone_h2_views.append(inspect('org.hkrpg.server.gameplay.DailyMessageFixtureInspector', control,
                                          f'phone-h2-{iteration}'))
        print(json.dumps(dict(stage='normal-stop-and-read-only-H2-verified', process=iteration,
            normalStopCount=stops, snapshots=len(snapshots), runtimeDirectory=str(directory)), ensure_ascii=False), flush=True)
    assert len(snapshots) == 7 and stops == 5 and len({snapshot['uid'] for snapshot in snapshots}) == 1
    assert [snapshot['loginWallet'] for snapshot in snapshots] == [0, 0, 0, 5000, 5000, 5000, 10000]
    assert len(h2_views) == 5 and len(phone_h2_views) == 4
    assert len({canonical(view['observation']) for view in h2_views}) == 1, 'Original global initial meeting identity was changed'
    assert sha(jar) == artifact and sha(config) == config_hash
    assert hashes == {relative: sha(source / relative) for relative in hashes}, 'Operator source resources changed'
    return dict(passed=True, mainMission=3000602, performance=90000103, sourceCommit=COMMIT,
        jarSHA256=artifact, runtimeDirectory=str(directory), snapshots=snapshots, requests=sum(s['requests'] for s in snapshots),
        checks=sum(len(s['checks']) for s in snapshots), clockViews=clock_views, prerequisiteFixtureViews=fixture_views,
        wireFields=wire_fields, readOnlyG61H2Inspections=h2_views, readOnlyPhoneH2Inspections=phone_h2_views,
        normalStopCount=stops, sameUIDNormalH2RestartCount=stops - 1,
        independentStoppedH2MorphiaReadCount=sum(v['independentMorphiaReadCount'] for v in h2_views + phone_h2_views),
        originalObservationStableAcrossAllNormalRestartsAndBothPeriods=True, sourceSHA256=hashes,
        installedWholeSourcePreflight=source_proof, sourceResourcesModified=False,
        actualOriginalMainCreditPerCompletedPeriod=5000, actualOriginalActivePointsPerCompletedPeriod=200,
        completedPeriodCount=2, sourceMenuAgainstActualNativeStartedMissionVerified=True,
        fixture=dict(singleOriginalCatalog=[3000602], explicitPrerequisiteHistoryOnly=prerequisites,
                     originalSourceRowsAndGraphsUnmodified=True, controlledClock=True,
                     initialMeetingOrDailyGoalSeeded=False),
        realWallClockCrossDayVerified=False, sourceClientScriptExecutorVerified=False,
        clientMenuUIVerified=False, clientGameplayVerified=False, crashAtomicityVerified=False)


def main():
    if hasattr(sys.stdout, 'reconfigure'):
        sys.stdout.reconfigure(encoding='utf-8')
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--jar', type=Path, default=ROOT / 'build/libs/hkrpg-server.jar')
    parser.add_argument('--output', type=Path, default=ROOT / 'build' / ('g61-first-talk-native-' + str(time.time_ns()) + '.json'))
    args = parser.parse_args()
    jar, output = args.jar.resolve(), args.output.resolve()
    assert jar.is_file() and jar.is_relative_to((ROOT / 'build').resolve()) and output.is_relative_to((ROOT / 'build').resolve())
    java = next((ROOT / '.tools').glob('jdk-21*/bin/java.exe'), None)
    assert java
    reserve_output(jar, output)
    artifact = sha(jar)
    try:
        case = one(jar, java, artifact)
        with output.open('x', encoding='utf-8') as report:
            report.write(json.dumps(case, ensure_ascii=False, indent=2) + '\n')
    except BaseException as error:
        failure = output.with_name(output.name + '.failed.json')
        try:
            with failure.open('x', encoding='utf-8') as report:
                json.dump(dict(passed=False, jarSHA256=artifact, output=str(output), failure=repr(error)), report, ensure_ascii=False, indent=2)
        except BaseException as report_error:
            error.add_note('Owned failed G61 report could not be written: ' + repr(report_error))
        raise
    print(json.dumps(dict(passed=True, report=str(output), jarSHA256=artifact, requests=case['requests'],
        checks=case['checks'], snapshots=len(case['snapshots']), normalStopCount=case['normalStopCount']), ensure_ascii=False))


if __name__ == '__main__':
    main()
