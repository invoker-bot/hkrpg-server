"""原3000603三次回复八支路、四种未交付到期、G61原菜单与同UID正常H2重启；UI/演出未验。"""
import argparse
import importlib.util
import json
import os
from pathlib import Path
import queue
import shutil
import subprocess
import sys
import tempfile
import threading
import time
import urllib.error

ROOT = next(parent for parent in Path(__file__).resolve().parents if (parent / 'AGENTS.md').is_file() and (parent / 'scripts/smoke-server.py').is_file())
DRAFT = Path(__file__).resolve().parent
JAVA = ROOT / 'scripts/fixtures/java'

sys.path.insert(0, str(ROOT / 'scripts'))
from smoke_resource_fixture import mirror_resources
_spec = importlib.util.spec_from_file_location('sibling_g61_support', ROOT / 'scripts/smoke-g61-first-talk.py')
_g61 = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(_g61)
COMMIT, PACK = _g61.COMMIT, _g61.PACK
sha, cleanup = _g61.sha, _g61.cleanup
reserve_output, transport_ports = _g61.reserve_output, _g61.transport_ports
projected_clock, local_request = _g61.projected_clock, _g61.local_request
canonical = _g61.canonical
BRANCHES = {f'{first % 1000}-{second % 1000}-{third % 1000}': (first, second, third)
            for first in (121140302, 121140303) for second in (121140306, 121140308)
            for third in (121140311, 121140312)}
EXPIRY_POINTS = ['noReplies', 'oneReply', 'twoReplies', 'allReplies']
PREFIX_SIZES = dict(zip(EXPIRY_POINTS, (3, 6, 9, 13)))


def select_cases(scenario, indices=None, branches=None, points=None):
    assert scenario in ('settled', 'expired-unpaid'), 'Unknown finite original Phone scenario'
    catalog = [dict(caseIndex=index, branch=branch, expiredAt='oneReply')
               for index, branch in enumerate(BRANCHES)] if scenario == 'settled' else [
                   dict(caseIndex=index, branch='302-306-311', expiredAt=at)
                   for index, at in enumerate(EXPIRY_POINTS)]
    if indices is not None:
        assert not branches and not points, 'Case indices cannot be combined with branch or expiry filters'
        assert indices and len(indices) == len(set(indices)), 'Repeated case indices are not additional coverage'
        assert all(type(index) is int and 0 <= index < len(catalog) for index in indices), 'Case index is outside the original finite catalog'
        return [catalog[index] for index in indices]
    assert not branches or len(branches) == len(set(branches)), 'Repeated branches are not additional coverage'
    assert not points or len(points) == len(set(points)), 'Repeated expiry prefixes are not additional coverage'
    assert not branches or set(branches) <= {case['branch'] for case in catalog}, 'Branch is outside this scenario catalog'
    assert not points or scenario == 'expired-unpaid' and set(points) <= set(EXPIRY_POINTS), 'Expiry prefixes belong to the unpaid catalog'
    return [case for case in catalog if (not branches or case['branch'] in branches) and (not points or case['expiredAt'] in points)]


def schedule(scenario):
    if scenario == 'settled':
        return [[(1, 'intro')], [(1, 'open')], [(1, 'middle')], [(1, 'finish')],
                [(1, 'sequel-pending'), (1, 'sequel-entered')], [(1, 'sequel-complete')], [(1, 'sequel-restored')]]
    if scenario == 'expired-unpaid':
        return [[(1, 'intro')], [(1, 'open')], [(2, 'boundary-reject')], [(2, 'open')],
                [(2, 'middle')], [(2, 'finish'), (2, 'claimed')]]
    raise ValueError('Unknown finite original Phone scenario')


def choices_for(branch, day, phase):
    assert branch in BRANCHES and day in (1, 2)
    first, second, third = BRANCHES[branch]
    if day == 1 or phase == 'boundary-reject':
        return first, second, third
    return (121140303 if first == 121140302 else 121140302,
            121140308 if second == 121140306 else 121140306,
            121140312 if third == 121140311 else 121140311)


def snapshot_phone_size(snapshot, scenario, at):
    phase, day = snapshot['phase'], snapshot['dayIndex']
    if phase.startswith('sequel-'):
        return 13
    assert scenario in ('settled', 'expired-unpaid') and at in PREFIX_SIZES and day in (1, 2)
    if phase == 'intro':
        assert day == 1
        return 0
    if phase in ('finish', 'claimed'):
        return 13
    if phase == 'middle':
        return 9
    if phase == 'boundary-reject':
        assert day == 2 and scenario == 'expired-unpaid'
        return PREFIX_SIZES[at]
    assert phase == 'open'
    return PREFIX_SIZES[at] if day == 1 and scenario == 'expired-unpaid' else 6


def verify_observation_stability(views):
    assert views and len({canonical(view['observation']) for view in views}) == 1, 'Original global initial meeting identity was changed'


def observed_case_paths(case):
    final = case['readOnlyPhoneH2Inspections'][-1]
    player_items = {value for branch in BRANCHES.values() for value in branch}
    return dict(caseIndex=case['caseIndex'], branch=case['branch'], actualParentHistory=final['currentRun']['history'],
                actualParentReplies=[value for value in final['currentRun']['history'] if value in player_items],
                nonPoolFollowup=final['followup'])
    old = final['archivedRun' if case['scenario'] == 'settled' else 'expiredRun']
    player_items = {value for branch in BRANCHES.values() for value in branch}
    return dict(caseIndex=case['caseIndex'], branch=case['branch'], expiredAt=case['expiredAt'],
                actualFirstDayHistory=old['history'],
                actualFirstDayReplies=[value for value in old['history'] if value in player_items],
                actualSecondDayReplies=[value for value in final['currentRun']['history'] if value in player_items])



def source_path(primary=None, fallback=None):
    primary = Path(primary) if primary is not None else ROOT / 'src/test/resources/fixtures/daily-followup-3000604-4.6.json'
    if primary.exists():
        return primary
    return Path(fallback) if fallback is not None else ROOT / 'scripts/fixtures/daily-followup-3000604-4.6.json'


def source_preflight(source, path):
    import base64
    from decimal import Decimal
    import hashlib
    import zlib
    fixture = json.loads(Path(path).read_text('utf-8'))
    assert fixture['sourceCommit'] == COMMIT and len(fixture['sourceFiles']) == 23
    number = lambda value: ('number', Decimal(value))
    parse = lambda raw: json.loads(raw, parse_float=number, parse_int=number)
    blobs, hashes, missing, source_only_status = {}, {}, [], {}
    for relative, metadata in fixture['sourceFiles'].items():
        raw = base64.b64decode(metadata['sourceBlobBase64']) if 'sourceBlobBase64' in metadata else zlib.decompress(base64.b64decode(metadata['sourceBlobDeflateBase64']))
        assert hashlib.sha256(raw).hexdigest() == metadata['sourceBlobSHA256'], relative
        file = source / relative
        if not file.exists():
            assert metadata.get('sourceOnly') is True and metadata['installed'] is False, 'Required full original absent: ' + relative
            missing.append(relative)
            source_only_status[relative] = 'absent'
            continue
        assert file.resolve().is_relative_to(source.resolve()), 'Read-only source escaped resource root'
        actual = file.read_bytes()
        assert hashlib.sha256(raw).digest() == hashlib.sha256(actual).digest() or parse(raw) == parse(actual), 'Complete original source differs: ' + relative
        if metadata.get('sourceOnly'):
            # Its appearance is read-only evidence, never an authorization to install or execute it.
            source_only_status[relative] = 'present_matching'
            continue
        hashes[relative] = sha(file)
        blobs[relative] = raw
    assert list(source_only_status) == ['Config/Level/NPCDialogue/P20002/F20002001_G85/DialogueMain_F20002001_G85_N400001.json'], 'Unexpected source-only original identity'
    return blobs, hashes, dict(passed=True, sourceFiles=list(fixture['sourceFiles']), completeFixedOriginalBytes=23,
        wholeInstalledObjectsEqual=22 + sum(status == 'present_matching' for status in source_only_status.values()),
        sourceOnlyMissing=missing, sourceOnlyStatus=source_only_status, sourceOnlyInstalledByTool=False, sourceRowsModified=False,
        clientActExecuted=False, clientUIVerified=False)


def followup_stage(phase):
    assert phase in {'intro', 'open', 'middle', 'finish', 'sequel-pending', 'sequel-entered', 'sequel-complete', 'sequel-restored'}, 'Unknown original followup phase'
    return 1 if phase in ('finish', 'sequel-pending') else 2 if phase == 'sequel-entered' else 3 if phase in ('sequel-complete', 'sequel-restored') else 0


def one(branch, at, jar, java, artifact, scenario, attempted):
    assert sha(jar) == artifact, 'Frozen G61 jar changed'
    source = (ROOT / 'resources/4.6').resolve()
    followup_blobs, followup_hashes, followup_proof = source_preflight(source, source_path())
    g61_path = ROOT / 'src/test/resources/fixtures/g61-npc-first-talk-4.6.json'
    if not g61_path.exists():
        g61_path = ROOT / 'scripts/fixtures/g61-npc-first-talk-4.6.json'
    fixture_data = json.loads(g61_path.read_text('utf-8'))
    blobs, hashes, source_proof = _g61.source_preflight(source, fixture_data)
    identity = _g61.original_source_identity(blobs)
    hashes.update(followup_hashes)
    prerequisites = [1000401, 1010201, 1010403]
    daily = [record['row'] for record in fixture_data['records']
             if record['kind'] == 'daily' and record['row']['ID'] == 3000603]
    pool = [record['row'] for record in fixture_data['records'] if record['kind'] == 'pool']
    assert len(daily) == 1 and pool
    # Each required original is an actual local fixture file. NPC rule path checks reject source junctions.
    replacements = {name: None for name in fixture_data['sourceFiles']}
    replacements.update({name: None for name in followup_proof['sourceFiles']})
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
    directory = Path(tempfile.mkdtemp(prefix=f'daily-message-g61-native-3000604-followup-{scenario}-{branch}-{at}-', dir=runs)).resolve()
    assert directory.is_relative_to(runs)
    attempted.append(str(directory))
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
        g61NpcFirstTalkFixture=True, dailyMessageFixture=True, originalMission=3000603)), 'utf-8')
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
        'org/hkrpg/server/protocol/NativeDailyFollowupProbe.java',
        'org/hkrpg/server/gameplay/G61FirstTalkFixtureInspector.java',
        'org/hkrpg/server/gameplay/DailyFollowupFixtureInspector.java']]
    sources = [path for path in sources if path.name not in ('NativeDailyFollowupProbe.java', 'DailyFollowupFixtureInspector.java')]
    sources += [JAVA / 'org/hkrpg/server/protocol/NativeDailyFollowupProbe.java', JAVA / 'org/hkrpg/server/gameplay/DailyFollowupFixtureInspector.java']
    subprocess.run([str(java.with_name('javac.exe')), '-encoding', 'UTF-8', '-cp', str(jar), '-d', str(classes),
                    *map(str, sources)], check=True, capture_output=True, creationflags=flags)
    java_flags = ['-Dfile.encoding=UTF-8', '-Dstdout.encoding=UTF-8', '-Dstderr.encoding=UTF-8',
                  '-Djdk.net.unixdomain.tmpdir=' + str(directory / 'tcp-fallback'), '-Dio.netty.eventLoopThreads=2']
    classpath = str(jar) + os.pathsep + str(classes)
    wire = subprocess.run([str(java), *java_flags, '-cp', classpath,
        'org.hkrpg.server.protocol.NativeDailyFollowupProbe', '--wire-fields'], cwd=ROOT, check=True,
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
        first, second, third = choices_for(branch, day, phase)
        child = subprocess.Popen([str(java), *java_flags, '-cp', classpath,
            'org.hkrpg.server.protocol.NativeDailyFollowupProbe', str(game)], stdin=subprocess.PIPE,
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
                secondChoice=second, thirdChoice=third, scenario=scenario, expiredAt=at, originalMenuPack=str(fixture / PACK))) + '\n')
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

    phases_by_process = schedule(scenario)
    for iteration, phases in enumerate(phases_by_process):
        assert sha(jar) == artifact and sha(config) == config_hash
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
        stage = followup_stage(last['phase'])
        credit = 10000 if stage == 3 else 5000 if stage else 0
        control = dict(uid=last['uid'], credit=credit, introOnly=iteration == 0, sourceIdentity=identity)
        if observation is not None:
            control['expectedObservation'] = observation
        else:
            control['actorWireIdentity'] = snapshots[0]['actorWireIdentity']
        h2 = inspect('org.hkrpg.server.gameplay.G61FirstTalkFixtureInspector', control, f'g61-h2-{iteration}')
        observation = h2['observation']
        h2_views.append(h2)
        day = (first_clock + 14400) // 86400
        data_day = 1 if last['phase'] == 'boundary-reject' else last['dayIndex']
        old_first, old_second, old_third = BRANCHES[branch]
        control = dict(uid=last['uid'], firstDay=day, runDay=day + data_day - 1,
            scenario=scenario, expiredAt=at, phoneSize=snapshot_phone_size(last, scenario, at),
            completed=last['phase'] in ('finish', 'claimed') or last['phase'].startswith('sequel-'), introOnly=last['phase'] == 'intro', followupStage=stage,
            firstChoice=last['firstChoice'], secondChoice=last['secondChoice'], thirdChoice=last['thirdChoice'],
            oldFirstChoice=old_first, oldSecondChoice=old_second, oldThirdChoice=old_third)
        phone_h2_views.append(inspect('org.hkrpg.server.gameplay.DailyFollowupFixtureInspector', control,
                                      f'phone-h2-{iteration}'))
        if last['phase'] == 'boundary-reject':
            previous, current = phone_h2_views[-2:]
            assert previous['persistedPhoneRuns'] == current['persistedPhoneRuns']
            assert previous['currentRun'] == current['currentRun'] and current['runDays'] == {}
            assert current['credit'] == 0 and current['activePoints'] == 0, 'The first old boundary request mutated or paid its run'
        print(json.dumps(dict(stage='normal-stop-and-read-only-H2-verified', process=iteration,
            normalStopCount=stops, snapshots=len(snapshots), runtimeDirectory=str(directory)), ensure_ascii=False), flush=True)
    expected_wallets = [0, 0, 0, 0, 5000, 5000, 5000, 10000]
    assert len(snapshots) == len(expected_wallets) and stops == len(phases_by_process) and len({snapshot['uid'] for snapshot in snapshots}) == 1
    assert [snapshot['loginWallet'] for snapshot in snapshots] == expected_wallets
    assert len(h2_views) == stops and len(phone_h2_views) == stops
    verify_observation_stability(h2_views)
    assert sha(jar) == artifact and sha(config) == config_hash
    assert hashes == {relative: sha(source / relative) for relative in hashes}, 'Operator source resources changed'
    return dict(passed=True, mainMission=3000603, section=1211402, scenario=scenario, branch=branch, expiredAt=at, performance=90000103, sourceCommit=COMMIT,
        jarSHA256=artifact, runtimeDirectory=str(directory), snapshots=snapshots, requests=sum(s['requests'] for s in snapshots),
        checks=sum(len(s['checks']) for s in snapshots), clockViews=clock_views, prerequisiteFixtureViews=fixture_views,
        wireFields=wire_fields, readOnlyG61H2Inspections=h2_views, readOnlyPhoneH2Inspections=phone_h2_views,
        normalStopCount=stops, sameUIDNormalH2RestartCount=stops - 1,
        independentStoppedH2MorphiaReadCount=sum(v['independentMorphiaReadCount'] for v in h2_views + phone_h2_views),
        originalObservationStableAcrossAllNormalRestarts=True, originalObservationAcrossBothPeriodsVerified=False, sourceSHA256=hashes,
        installedWholeSourcePreflight=source_proof, sourceResourcesModified=False,
        actualOriginalMainCreditPerCompletedPeriod=5000, actualOriginalActivePointsPerCompletedPeriod=200,
        completedPeriodCount=1, nonPoolFollowup=3000604, nonPoolFollowupCompleted=True, sourceMenuAgainstActualNativeStartedMissionVerified=True,
        followupWholeSourcePreflight=followup_proof, followupStageViews=[v['followup'] for v in phone_h2_views],
        actualOriginalNonPoolMainCredit=5000, nonPoolDailyPeriodCreated=False,
        fixture=dict(singleOriginalCatalog=[3000603], explicitPrerequisiteHistoryOnly=prerequisites,
                     originalSourceRowsAndGraphsUnmodified=True, controlledClock=True,
                     initialMeetingOrDailyGoalSeeded=False),
        controlledCrossDayVerified=False, firstDayLateUnlockOnSameActualDayVerified=True,
        realWallClockCrossDayVerified=False, sourceClientScriptExecutorVerified=False,
        clientMenuUIVerified=False, clientGameplayVerified=False, crashAtomicityVerified=False)


def main():
    if hasattr(sys.stdout, 'reconfigure'):
        sys.stdout.reconfigure(encoding='utf-8')
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--jar', type=Path, required=True)
    parser.add_argument('--output', type=Path, default=ROOT / 'build' / ('daily-message-3000603-native-' + str(time.time_ns()) + '.json'))
    parser.add_argument('--scenario', choices=['settled', 'expired-unpaid'], default='settled')
    parser.add_argument('--case-index', type=int, action='append', help='稳定原case编号：settled 0..7；expired-unpaid 0..3；省略执行整个有限目录')
    parser.add_argument('--branch', choices=BRANCHES, action='append')
    parser.add_argument('--expired-at', choices=EXPIRY_POINTS, action='append')
    args = parser.parse_args()
    jar, output = args.jar.resolve(), args.output.resolve()
    assert jar.is_file() and jar.is_relative_to((ROOT / 'build').resolve()) and output.is_relative_to((ROOT / 'build').resolve())
    java = next((ROOT / '.tools').glob('jdk-21*/bin/java.exe'), None)
    assert java
    assert args.scenario == 'settled' and args.case_index == [0] and not args.branch and not args.expired_at, 'Only the declared single original settled parent branch0 is prepared'
    selected = select_cases(args.scenario, args.case_index, args.branch, args.expired_at)
    reserve_output(jar, output)
    artifact, cases, attempted, attempted_indices = sha(jar), [], [], []
    try:
        for selected_case in selected:
            attempted_indices.append(selected_case['caseIndex'])
            case = one(selected_case['branch'], selected_case['expiredAt'], jar, java, artifact, args.scenario, attempted)
            case['caseIndex'] = selected_case['caseIndex']
            case['configuredFirstDayChoices'] = list(BRANCHES[case['branch']])
            cases.append(case)
        assert sha(jar) == artifact
        result = dict(passed=True, mainMission=3000603, nonPoolFollowup=3000604, section=1211402, scenario=args.scenario, rootJarSHA256=artifact, cases=cases,
            executedCaseIndices=[case['caseIndex'] for case in cases], actualCompletedCaseCount=len(cases),
            executedOriginalPaths=[observed_case_paths(case) for case in cases],
            snapshots=sum(len(case['snapshots']) for case in cases), requests=sum(case['requests'] for case in cases),
            checks=sum(case['checks'] for case in cases), normalStopCount=sum(case['normalStopCount'] for case in cases),
            sameUIDNormalH2RestartCount=sum(case['sameUIDNormalH2RestartCount'] for case in cases),
            independentStoppedH2MorphiaReadCount=sum(case['independentStoppedH2MorphiaReadCount'] for case in cases),
            sourceResourcesModified=False, clientGameplayVerified=False, clientMenuUIVerified=False,
            sourceClientScriptExecutorVerified=False, realWallClockCrossDayVerified=False, crashAtomicityVerified=False)
        with output.open('x', encoding='utf-8') as report:
            report.write(json.dumps(result, ensure_ascii=False, indent=2) + '\n')
    except BaseException as error:
        failure = output.with_name(output.name + '.failed.json')
        try:
            with failure.open('x', encoding='utf-8') as report:
                json.dump(dict(passed=False, jarSHA256=artifact, output=str(output), failure=repr(error),
                    completedCases=cases, completedCaseIndices=[case['caseIndex'] for case in cases],
                    attemptedCaseIndices=attempted_indices, selectedCaseIndices=[case['caseIndex'] for case in selected],
                    attemptedRuntimeDirectories=attempted), report, ensure_ascii=False, indent=2)
        except BaseException as report_error:
            error.add_note('Owned failed 3000603 report could not be preserved: ' + repr(report_error))
        raise
    print(json.dumps(dict(passed=True, report=str(output), rootJarSHA256=artifact, requests=result['requests'],
        executedCaseIndices=result['executedCaseIndices'], actualCompletedCaseCount=result['actualCompletedCaseCount'],
        checks=result['checks'], snapshots=result['snapshots'], normalStopCount=result['normalStopCount'],
        independentStoppedH2MorphiaReadCount=result['independentStoppedH2MorphiaReadCount']), ensure_ascii=False))


if __name__ == '__main__':
    main()
