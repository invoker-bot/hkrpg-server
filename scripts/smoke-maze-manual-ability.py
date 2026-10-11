"""隔离1510原Input真实KCP/H2验证；预检只编译/读descriptor，拒绝覆盖旧证据。"""
import argparse
import base64
import copy
import importlib.util
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile
import time
import traceback
import urllib.error

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'scripts'))


def module(name, file):
    spec = importlib.util.spec_from_file_location(name, ROOT / 'scripts' / file)
    value = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(value)
    return value


common = module('maze_cast_common', 'smoke-maze-cast-sequence.py')
source_proof = module('maze_manual_source', 'freeze-maze-manual-ability-source.py')
sha = common.sha
reserve_output = common.reserve_output
transport_ports = common.transport_ports
stop_owned_process = common.stop_owned_process
SOURCE_FILES = tuple(dict.fromkeys((*common.QUOTE_TABLES, source_proof.ABILITY)))


def verify_original_closure(source, fixture, records):
    common.verify_quote_closure(source, fixture, records)
    for relative, pin in source_proof.PINS.items():
        data = (fixture / relative).read_bytes().replace(b'\r\n', b'\n')
        if source_proof.digest(data) != pin:
            raise ValueError('Complete original 1510 source pin differs: ' + relative)


def verify_snapshot_counts(snapshot):
    if snapshot['requests'] != len(snapshot['observedReplies']):
        raise ValueError('Actual observed native replies and request count differ')


def fixture_inputs(source):
    manual_file = ROOT / 'src/test/resources/fixtures/maze-manual-ability-4.6.json'
    declared = json.loads(manual_file.read_text('utf-8'))
    actual = source_proof.source_fixture(ROOT / 'references/TurnBasedGameData', source)
    if declared != actual:
        raise ValueError('Fixed three-blob manual source fixture differs from installed originals')
    old = json.loads((ROOT / 'src/test/resources/fixtures/maze-cast-sequence-4.6.json').read_text('utf-8'))
    replacements, hashes, stage = common.fixture_inputs(source, old)
    asta = next(row['abilityFile'] for row in old['records'] if row['avatarID'] == 1009)
    replacements.pop(asta)
    npc_path = 'Config/LevelOutput/SharedRuntimeGroup/Groups_P20001_F20001001/LevelGroup_P20001_F20001001_G91.json'
    group = replacements[npc_path]
    group['GroupName'] = 'MazeManualAbilityFixture'
    for row in group['MonsterList']:
        if row['ID'] in (200109, 200110):
            row['NPCMonsterID'] = 1003010  # Whole original Elite row; source rank forbids instant-kill.
    npcs = json.loads((source / 'ExcelOutput/NPCMonsterData.json').read_text('utf-8'))
    ranks = {row['ID']: row['Rank'] for row in npcs if row['ID'] in (1002020, 1003010)}
    if ranks != {1002020: 'MinionLv2', 1003010: 'Elite'}:
        raise ValueError('Original eligible/elite source rows changed')
    original_box = group['PropList'][0]
    group['PropList'] = []
    for instance in range(300001, 300009):
        box = copy.deepcopy(original_box)
        box['ID'] = instance
        group['PropList'].append(box)
    # Materialize before mirror links are created, then overwrite with exact full original bytes.
    for relative in SOURCE_FILES:
        replacements[relative] = json.loads((source / relative).read_text('utf-8'))
        hashes[relative] = sha(source / relative)
    return replacements, hashes, stage, manual_file, actual


def prepare(jar, directory):
    source = ROOT / 'resources/4.6'
    replacements, hashes, stage, proof_file, proof = fixture_inputs(source)
    fixture = directory / 'resources-fixture'
    flags = subprocess.CREATE_NO_WINDOW if os.name == 'nt' else 0
    junctions = common.mirror_resources(source, fixture, replacements)
    for relative, value in replacements.items():
        target = fixture / relative
        if not target.resolve().is_relative_to(fixture.resolve()):
            raise ValueError('Owned fixture replacement escaped its root: ' + relative)
        target.write_text(json.dumps(value, ensure_ascii=False), 'utf-8')
    records = {}
    for relative in SOURCE_FILES:
        target = fixture / relative
        if not target.resolve().is_relative_to(fixture.resolve()) or os.path.samefile(target, source / relative):
            raise ValueError('Original manual source was not physically materialized: ' + relative)
        original = (source / relative).read_bytes()
        target.write_bytes(original)
        records[relative] = dict(originalSHA256=sha(source / relative), fullBytes=len(original))
    verify_original_closure(source, fixture, records)
    if junctions:
        plan = directory / 'junctions.json'
        plan.write_text(json.dumps(junctions), 'utf-8')
        helper = directory / 'prepare.ps1'
        helper.write_text("param([string]$Plan)\n$ErrorActionPreference='Stop'\nforeach ($binding in (Get-Content -LiteralPath $Plan -Raw | ConvertFrom-Json)) { New-Item -ItemType Junction -Path $binding.target -Value $binding.source | Out-Null }\n", 'utf-8')
        subprocess.run(['powershell', '-NoProfile', '-NonInteractive', '-File', str(helper), '-Plan', str(plan)],
                       check=True, capture_output=True, creationflags=flags)
    marker = dict(clientVersion='4.6.0', testFixture=True, mazeManualAbilityFixture=True,
                  sourceCommit=source_proof.COMMIT, completeOriginal1510Ability=True,
                  controlledNPCStageAndRecoveryPlacement=True, originalAstaUnmodified=True,
                  useIDOrPaymentPrewritten=False)
    (fixture / 'resource-manifest.json').write_text(json.dumps(marker), 'utf-8')
    (directory / 'data').mkdir()
    http, mongo, game = transport_ports()
    if http == mongo:
        raise ValueError('Owned HTTP and Mongo ports must differ before server startup')
    config = directory / 'config.json'
    config.write_text(json.dumps({'protocol': {'wireVersion': '4.6.0'}, 'resourceDir': str(fixture),
        'httpServer': {'bindAddress': '127.0.0.1', 'bindPort': http},
        'gameServer': {'bindAddress': '127.0.0.1', 'bindPort': game},
        'internalMongoServer': {'address': '127.0.0.1', 'port': mongo, 'filePath': './data/database.mv'}}), 'utf-8')
    java = next((ROOT / '.tools').glob('jdk-21*/bin/java.exe'), None)
    if not java or not java.is_file():
        raise FileNotFoundError('The owned Java 21 toolchain is missing')
    classes = directory / 'probe-classes'
    classes.mkdir()
    probes = [ROOT / 'scripts/fixtures/java/org/hkrpg/server/protocol/NativeMazeManualAbilityProbe.java',
              ROOT / 'scripts/fixtures/java/org/hkrpg/server/gameplay/MazeManualAbilityFixtureInspector.java']
    compiled = subprocess.run([str(java.with_name('javac.exe')), '--release', '21', '-encoding', 'UTF-8',
        '-cp', str(jar), '-d', str(classes), *map(str, probes)], capture_output=True, creationflags=flags)
    (directory / 'javac.log').write_bytes(compiled.stdout + compiled.stderr)
    if compiled.returncode != 0:
        raise RuntimeError('Native helper javac failed: ' + str(directory / 'javac.log'))
    options = ['-Dfile.encoding=UTF-8', '-Dstdout.encoding=UTF-8', '-Dstderr.encoding=UTF-8',
               '-Dio.netty.eventLoopThreads=2', f'-Djdk.net.unixdomain.tmpdir={directory / "tcp-fallback"}']
    classpath = str(classes) + os.pathsep + str(jar)
    descriptors = subprocess.run([str(java), *options, '-cp', classpath,
        'org.hkrpg.server.protocol.NativeMazeManualAbilityProbe', '--verify-descriptors'], capture_output=True,
        creationflags=flags, timeout=60)
    (directory / 'descriptor-preflight.log').write_bytes(descriptors.stdout + descriptors.stderr)
    if descriptors.returncode != 0:
        raise RuntimeError('Native descriptor preflight failed: ' + str(directory / 'descriptor-preflight.log'))
    descriptor = json.loads(next(row for row in reversed(descriptors.stdout.decode('utf-8').splitlines()) if row.startswith('{')))
    if not descriptor['passed']:
        raise ValueError('Native descriptor preflight reported failure')
    return dict(source=source, fixture=fixture, hashes=hashes, stage=stage, proofFile=proof_file, proof=proof,
                physicalOriginals=records, java=java, options=options, classpath=classpath, flags=flags,
                config=config, configBytes=config.read_bytes(), http=http, game=game, descriptor=descriptor,
                probeSourceSHA256={path.relative_to(ROOT).as_posix(): sha(path) for path in probes})


def run(jar, output, preflight_only=False):
    artifact = sha(jar)
    runs = ROOT / 'build/smoke'
    runs.mkdir(parents=True, exist_ok=True)
    directory = Path(tempfile.mkdtemp(prefix='maze-manual-ability-', dir=runs))
    snapshots, inspections, credentials, stops = [], [], {}, 0
    server_started, native_executed = False, False
    try:
        ready = prepare(jar, directory)
        if preflight_only:
            result = dict(preflightPassed=True, serverStarted=False, nativeExecuted=False,
                artifactSHA256=artifact, runtimeDirectory=str(directory), descriptor=ready['descriptor'],
                originalSourcesPhysicallyCopied=ready['physicalOriginals'], probeSourceSHA256=ready['probeSourceSHA256'],
                fixtureSHA256=sha(ready['proofFile']), clientGameplayVerified=False)
        else:
            for index, mode in enumerate(('sequence', 'new-session', 'restored')):
                if sha(jar) != artifact:
                    raise ValueError('Frozen runtime artifact changed before process startup')
                verify_original_closure(ready['source'], ready['fixture'], ready['physicalOriginals'])
                with (directory / f'server-{index}.log').open('wb') as log:
                    process = subprocess.Popen([str(ready['java']), *ready['options'], '-Xmx1536m', '-jar', str(jar), '--no-handbook'],
                        cwd=directory, stdin=subprocess.PIPE, stdout=log, stderr=subprocess.STDOUT, creationflags=ready['flags'])
                    server_started = True
                    server_ready = False
                    try:
                        deadline = time.monotonic() + 120
                        while True:
                            if process.poll() is not None:
                                raise RuntimeError('Owned server exited before readiness: ' + str(directory / f'server-{index}.log'))
                            try:
                                if common.local_request(ready['http'], '/health/ready')['ready']:
                                    server_ready = True
                                    break
                            except (OSError, urllib.error.URLError):
                                pass
                            if time.monotonic() > deadline:
                                raise TimeoutError(directory / f'server-{index}.log')
                            time.sleep(.2)
                        if not credentials:
                            login = common.local_request(ready['http'], '/hkrpg_cn/mdk/shield/api/login', dict(account='maze-manual-native', password='local-test'))
                            if login['retcode'] != 0:
                                raise RuntimeError('Owned local authentication failed')
                            account = login['data']['account']
                            combo = common.local_request(ready['http'], '/hkrpg_cn/combo/granter/login/v2/login',
                                dict(data=json.dumps(dict(uid=account['uid'], token=account['token']))))
                            if combo['retcode'] != 0:
                                raise RuntimeError('Owned local combo authentication failed')
                            credentials.update(accountUid=account['uid'], comboToken=combo['data']['combo_token'])
                        probe_log = directory / f'probe-{index}.log'
                        with probe_log.open('wb') as log_probe:
                            child = subprocess.Popen([str(ready['java']), *ready['options'], '-cp', ready['classpath'],
                                'org.hkrpg.server.protocol.NativeMazeManualAbilityProbe', str(ready['game'])], cwd=directory,
                                stdin=subprocess.PIPE, stdout=log_probe, stderr=subprocess.STDOUT, creationflags=ready['flags'])
                            native_executed = True
                            child.stdin.write((json.dumps(dict(credentials, mode=mode, stage=ready['stage']['StageID'],
                                waves=[list(wave.values()) for wave in ready['stage']['MonsterList']])) + '\n').encode())
                            child.stdin.close()
                            deadline, granted = time.monotonic() + 300, False
                            try:
                                while child.poll() is None:
                                    if mode == 'sequence' and not granted:
                                        marker = re.search(r'READY_UID=(\d+)', probe_log.read_text('utf-8'))
                                        if marker:
                                            uid = int(marker.group(1))
                                            if uid != 10001:
                                                raise ValueError('Only the single isolated actor grant is authorized')
                                            process.stdin.write(f'give 1510 @{uid}\n'.encode())
                                            process.stdin.flush()
                                            granted = True
                                    if time.monotonic() > deadline:
                                        raise TimeoutError(probe_log)
                                    time.sleep(.2)
                            finally:
                                if child.poll() is None:
                                    child.terminate()
                                    child.wait(timeout=15)
                        if child.returncode != 0 or (mode == 'sequence' and not granted):
                            raise RuntimeError('Actual manual probe failed: ' + str(probe_log))
                        snapshot = json.loads(next(row for row in reversed(probe_log.read_text('utf-8').splitlines()) if row.startswith('{')))
                        if not snapshot['passed']:
                            raise ValueError('Actual manual probe reported failure')
                        verify_snapshot_counts(snapshot)
                        credentials.setdefault('uid', snapshot['uid'])
                        if credentials['uid'] != snapshot['uid']:
                            raise ValueError('Owned Player UID changed across process restart')
                        snapshots.append(snapshot)
                    finally:
                        stops += stop_owned_process(process, directory, server_ready)
                inspected = subprocess.run([str(ready['java']), *ready['options'], '-cp', ready['classpath'],
                    'org.hkrpg.server.gameplay.MazeManualAbilityFixtureInspector', str(directory), mode,
                    str(credentials['uid']), str(snapshot['mp'])], cwd=ROOT, capture_output=True, timeout=120,
                    creationflags=ready['flags'])
                inspector_log = directory / f'inspector-{index}.log'
                inspector_log.write_bytes(inspected.stdout + inspected.stderr)
                if inspected.returncode != 0:
                    raise RuntimeError('Read-only normal H2 inspection failed: ' + str(inspector_log))
                inspection = json.loads(next(row for row in reversed(inspected.stdout.decode('utf-8').splitlines()) if row.startswith('{')))
                if not inspection['passed'] or inspection['uid'] != credentials['uid']:
                    raise ValueError('Read-only H2 inspection differs from actual network Player')
                inspections.append(inspection)
                print(json.dumps(dict(phase=mode, normalStops=stops, checks=len(snapshot['checks']))), flush=True)
            if stops != 3:
                raise ValueError('Three actual normal server stops were not verified')
            result = dict(passed=True, artifactSHA256=artifact, runtimeDirectory=str(directory), snapshots=snapshots,
                inspections=inspections, nativeRequests=sum(row['requests'] for row in snapshots),
                nativeChecks=sum(len(row['checks']) for row in snapshots), normalStops=stops,
                sameUIDNormalH2ProcessRestarts=2, originalSourcesPhysicallyCopied=ready['physicalOriginals'],
                fixtureSHA256=sha(ready['proofFile']), probeSourceSHA256=ready['probeSourceSHA256'],
                sourceResourceHashes=ready['hashes'], descriptorPreflight=ready['descriptor'],
                fixture=dict(controlledSceneEntry=2000101, controlledGroup=91, consoleGrantedFormalActors=[1510],
                    original1510AbilityWholeAndUnchanged=True, originalAvatarAndMazeTablesWholeAndUnchanged=True,
                    controlledRecoveryBoxInstances=list(range(300001, 300009)), originalRecoveryBoxTownPlacement=False,
                    sourceEligibleNPC=1002020, sourceEliteNPC=1003010, sourceStage=80030231,
                    missingStageEvent=199996001, useIDOrPaymentPrewritten=False, originalAstaUnmodified=True),
                sourceResourcesModified=False, operatorDataModified=False, boundedOriginal1510InputNetworkVerified=True,
                basePrepaymentBorrowedByInput=False, clientGameplayVerified=False, officialRequestSequenceObserved=False,
                otherManualAbilitiesVerified=False, extraTeamMpModifiersVerified=False, fullSummonLifecycleVerified=False,
                crashAtomicityVerified=False, wholeBattleComplete=False, wholeGameComplete=False)
        if sha(jar) != artifact or ready['config'].read_bytes() != ready['configBytes']:
            raise ValueError('Frozen artifact or owned config changed during verification')
        if ready['hashes'] != {name: sha(ready['source'] / name) for name in ready['hashes']}:
            raise ValueError('Source resources changed during verification')
        verify_original_closure(ready['source'], ready['fixture'], ready['physicalOriginals'])
        with output.open('x', encoding='utf-8') as report:
            json.dump(result, report, ensure_ascii=False, indent=2)
        print(json.dumps({key: result[key] for key in ('artifactSHA256', 'runtimeDirectory')} | dict(
            preflightOnly=preflight_only, passed=result.get('passed'), preflightPassed=result.get('preflightPassed'))), flush=True)
    except BaseException as error:
        with (directory / 'failed-run.json').open('x', encoding='utf-8') as report:
            json.dump(dict(passed=False, serverStarted=server_started, nativeExecuted=native_executed, error=repr(error),
                traceback=''.join(traceback.format_exception(error)), artifactSHA256=artifact,
                runtimeDirectory=str(directory), normalStops=stops, completedSnapshots=snapshots,
                inspections=inspections), report, ensure_ascii=False, indent=2)
        raise


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--jar', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--preflight-only', action='store_true')
    args = parser.parse_args()
    jar, output = args.jar.resolve(), args.output.resolve()
    if not jar.is_file() or not jar.is_relative_to(ROOT / 'build') or not output.is_relative_to(ROOT / 'build'):
        raise ValueError('Frozen artifact and unique evidence output must be under build/')
    reserve_output(jar, output)
    run(jar, output, args.preflight_only)


if __name__ == '__main__':
    main()
