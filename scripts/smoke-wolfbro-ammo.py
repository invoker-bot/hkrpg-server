"""原 Level11 枪/NPC的6525→6514→6508 KCP与正常H2恢复；数量是本服规则，未验收官方UI。

仅新建 build/smoke 隔离目录。完整原1031501章节和103150120完成历史是明确前置夹具；
枪、运行UUID、弹药、NPC击败及掉落不预写。已有输出/lease一律保留，--jar必须是冻结本地产物。
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import socket
import struct
import subprocess
import sys
import tempfile
import time
import traceback
import urllib.error
import urllib.request

ROOT = next(parent for parent in Path(__file__).resolve().parents
            if (parent / 'scripts/smoke_resource_fixture.py').is_file())
sys.path.insert(0, str(ROOT / 'scripts'))
from smoke_resource_fixture import mirror_resources
import source_json

COMMIT = '724b139d8c9c32d12552eb95745a4fee72bfe48b'
CHAPTER = 'Config/Level/Mission/1031501'


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def fingerprint(value):
    return source_json.fingerprint(value)


def port(kind=socket.SOCK_STREAM):
    with socket.socket(type=kind) as connection:
        connection.bind(('127.0.0.1', 0))
        return connection.getsockname()[1]


def transport_ports():
    http = port()
    for _ in range(32):
        mongo = port()
        if mongo != http:
            return http, mongo, port(socket.SOCK_DGRAM)
    raise RuntimeError('Cannot select distinct owned HTTP and Mongo TCP ports')


def local_request(http, route, body=None):
    request = urllib.request.Request(f'http://127.0.0.1:{http}' + route,
        data=None if body is None else json.dumps(body).encode(), headers={'Content-Type': 'application/json'})
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    with opener.open(request, timeout=5) as response:
        return json.load(response)


def cleanup_failure(error, directory, primary, description):
    evidence = directory / 'cleanup-failures.log'
    with evidence.open('a', encoding='utf-8') as log:
        log.write(description + '\n' + ''.join(traceback.format_exception(error)))
    if primary is not None:
        primary.add_note(f'{description}: {error}; preserved in {evidence}')
        return
    raise error


def stop_owned_probe(process, directory):
    primary = sys.exception()
    try:
        if process.poll() is None:
            process.terminate()
            try:
                process.wait(timeout=15)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait(timeout=10)
    except Exception as failed:
        cleanup_failure(failed, directory, primary, 'Owned probe cleanup also failed')
    finally:
        if not process.stdin.closed:
            process.stdin.close()


def stop_owned_server(process, directory, ready):
    primary = sys.exception()
    try:
        if process.poll() is None:
            try:
                process.stdin.write(b'stop\n')
                process.stdin.flush()
                process.wait(timeout=45)
                expected = 1000 if os.name == 'nt' else 232
                if process.returncode != expected:
                    raise RuntimeError(f'Unexpected owned server stop exit {process.returncode}; {directory}')
                return int(ready)
            finally:
                if process.poll() is None:
                    process.terminate()
                    process.wait(timeout=15)
                process.stdin.close()
        return 0
    except Exception as failed:
        cleanup_failure(failed, directory, primary, 'Owned server stop also failed')
        return 0


def reserve_output(jar, output):
    if output.exists():
        raise FileExistsError('Previous native evidence is preserved; choose a new output: ' + str(output))
    output.parent.mkdir(parents=True, exist_ok=True)
    lease = output.with_name(output.name + '.lease.json')
    with lease.open('x', encoding='utf-8') as reservation:
        json.dump(dict(output=str(output), pid=os.getpid(), jarSHA256=sha(jar), startedEpoch=time.time()), reservation)
    return lease


def scene_coordinate(value):
    # ObjectInfo stores float then casts float*1000 to int; do not round authored coordinates.
    f32 = lambda number: struct.unpack('<f', struct.pack('<f', number))[0]
    return int(f32(f32(value) * 1000.0))


def source_point(record):
    return {axis.lower(): scene_coordinate(record.get('Pos' + axis, 0)) for axis in 'XYZ'}


def fixture_inputs(source, proof):
    if proof['sourceCommit'] != COMMIT or len(proof['sourceFiles']) != 6 or len(proof['sourceRows']) != 30:
        raise ValueError('Wrong complete pinned Level11 fixture')
    files, pins, tables = {}, {}, {}
    for record in proof['sourceFiles']:
        name = record['path']
        path = source / name
        if sha(path) != record['fileSHA256']:
            raise ValueError('Original complete placement bytes changed: ' + name)
        actual = source_json.loads(path.read_text('utf-8'))
        if (source_json.canonical(actual) != source_json.canonical(record['data'])
                or fingerprint(actual) != record['canonicalSHA256']):
            raise ValueError('Original complete placement object changed: ' + name)
        files[name], pins[name] = actual, record['fileSHA256']
    for record in proof['sourceRows']:
        path = record['sourcePath']
        if path not in tables:
            tables[path] = source_json.loads((source / path).read_text('utf-8'))
            pins[path] = sha(source / path)
        if pins[path] != record['fileSHA256']:
            raise ValueError('Original full table bytes changed: ' + path)
        matches = [row for row in tables[path] if row.get(record['keyField']) == record['key']]
        if (len(matches) != 1
                or source_json.canonical(matches[0]) != source_json.canonical(record['data'])
                or fingerprint(matches[0]) != record['canonicalSHA256']):
            raise ValueError('Original complete table row changed: ' + path + ':' + str(record['key']))
    level = next(row['data'] for row in proof['sourceRows'] if row['table'] == 'MazePuzzleWolfBro' and row['key'] == 11)
    if (level['PlaneID'], level['FloorID'], level['ControlGroupID']) != (20321, 20321001, 21):
        raise ValueError('Original Level11 source identity changed')
    group20 = next(value for name, value in files.items() if name.endswith('_G20.json'))
    group22 = next(value for name, value in files.items() if name.endswith('_G22.json'))
    gun = next(row for row in group20['PropList'] if row['ID'] == 300009)
    npc = next(row for row in group22['MonsterList'] if row['ID'] == 200001)
    if gun['PropID'] != 103068 or npc['NPCMonsterID'] != 3002041 or npc['EventID'] != 10301299:
        raise ValueError('Original loaded gun or NPC differs')
    tables_to_check = [('MainMission', 'MainMissionID', 1031501), ('SubMission', 'SubMissionID', 103150120),
                       ('MapEntrance', 'ID', 2032101)]
    prerequisite_rows = {}
    for table, key, value in tables_to_check:
        name = 'ExcelOutput/' + table + '.json'
        rows = json.loads((source / name).read_text('utf-8'))
        matches = [row for row in rows if row.get(key) == value]
        if len(matches) != 1:
            raise ValueError('Original source prerequisite row is missing: ' + table)
        prerequisite_rows[table] = matches[0]
        pins[name] = sha(source / name)
    entry = prerequisite_rows['MapEntrance']
    if (entry['PlaneID'], entry['FloorID'], entry['FinishMainMissionList'], entry['FinishSubMissionList']) != (20321, 20321001, [1031501], [103150120]):
        raise ValueError('Original entry prerequisites changed')
    chapter_paths = sorted(path for path in (source / CHAPTER).rglob('*.json') if path.is_file())
    if not chapter_paths:
        raise ValueError('Complete original prerequisite chapter is missing')
    chapter_blobs = {}
    for path in chapter_paths:
        if not path.resolve().is_relative_to(source.resolve()):
            raise ValueError('Chapter path escaped source resources')
        relative = path.relative_to(source).as_posix()
        original = subprocess.run(['git', '-C', str(ROOT / 'references/TurnBasedGameData'), 'show', COMMIT + ':' + relative],
            capture_output=True, check=True).stdout
        if path.read_bytes().replace(b'\r\n', b'\n') != original.replace(b'\r\n', b'\n'):
            raise ValueError('Prerequisite chapter differs from its fixed source commit: ' + relative)
        pins[relative] = sha(path)
        chapter_blobs[relative] = hashlib.sha256(original).hexdigest()
    full = json.loads((source / CHAPTER / 'MissionInfo_1031501.json').read_text('utf-8'))
    partial = json.loads((source / CHAPTER / 'MissionInfo_1031501.partial.json').read_text('utf-8'))
    sub = next(row for row in partial['SubMissionList'] if row['ID'] == 103150120)
    if full['MainMissionID'] != 1031501 or sub['MainMissionID'] != 1031501 or sub['Progress'] != 1:
        raise ValueError('Full/partial original mission ownership is inconsistent')
    guarded = set(source.glob('ExcelOutput/*.json')) | {source / path for path in pins} | {source / 'resource-manifest.json'}
    pins = {path.relative_to(source).as_posix(): sha(path) for path in sorted(guarded)}
    originals = sorted({record['path'] for record in proof['sourceFiles']}
                       | {record['sourcePath'] for record in proof['sourceRows']})
    return dict(sourceResourceHashes=pins, sourceChapterBlobHashes=chapter_blobs, copiedChapterFiles=list(chapter_blobs),
        copiedRuntimeOriginalFiles=originals,
        prerequisiteRows=prerequisite_rows, sourceFilesCount=6, sourceRowsCount=30,
        gunPosition=source_point(gun), checkpointMotion=dict(pos=source_point(npc), rot=dict(x=0, y=scene_coordinate(npc.get('RotY', 0)), z=0)))


def explosion_inputs(source, prepared):
    proof_path = ROOT / 'src/test/resources/fixtures/wolfbro-explosion-4.6.json'
    proof = source_json.loads(proof_path.read_bytes())
    if proof['sourceCommit'] != COMMIT or len(proof['sourceFiles']) != 11 or len(proof['sourceRows']) != 1:
        raise ValueError('Incomplete original blast source fixture')
    abilities = [row for row in proof['sourceFiles'] if row['path'].startswith('Config/ConfigAdventureAbility/NpcMonster/')]
    required = {
        'Config/ConfigAdventureAbility/NpcMonster/NPCMonster_W3_TV_00_Ability.json',
        'Config/ConfigAdventureAbility/NpcMonster/NPCMonster_W3_Clock_00_Ability.json',
        'Config/ConfigAdventureAbility/NpcMonster/NPCMonster_W3_Dinosaur_00_Ability.json',
        'Config/ConfigAdventureAbility/NpcMonster/NPCMonster_XP_Minion01_04_Ability.json',
    }
    if len(abilities) != 4 or {row['path'] for row in abilities} != required:
        raise ValueError('Four complete original blast abilities are required')
    pins = {}
    for row in abilities:
        name = row['path']
        raw = (source / name).read_bytes()
        if (hashlib.sha256(raw.replace(b'\r\n', b'\n')).hexdigest() != row['sourceBlobSHA256']
                or fingerprint(source_json.loads(raw)) != row['canonicalSHA256']
                or source_json.canonical(source_json.loads(raw)) != source_json.canonical(row['data'])):
            raise ValueError('Complete original blast ability changed: ' + name)
        pins[name] = sha(source / name)
    row = proof['sourceRows'][0]
    if row['table'] != 'MazeProp' or row['key'] != 103055:
        raise ValueError('Wrong original explosive prop')
    matches = [value for value in source_json.loads((source / row['sourcePath']).read_bytes())
               if value.get(row['keyField']) == row['key']]
    if (len(matches) != 1 or fingerprint(matches[0]) != row['canonicalSHA256']
            or source_json.canonical(matches[0]) != source_json.canonical(row['data'])):
        raise ValueError('Whole original explosive prop row changed')
    prepared['sourceResourceHashes'].update(pins)
    prepared['copiedRuntimeOriginalFiles'] = sorted(set(prepared['copiedRuntimeOriginalFiles']) | required)
    return dict(fixtureSHA256=sha(proof_path), completeAbilityFiles=4, completeExplosivePropRows=1,
                serverPhysicsImplemented=False, clientBlastPhysicsVerified=False)


def run(jar, output, *, explosion=False):
    artifact = sha(jar)
    java = next((ROOT / '.tools').glob('jdk-21*/bin/java.exe'), None)
    if not java or not java.is_file():
        raise FileNotFoundError('Owned Java21 runtime is missing')
    source = ROOT / 'resources/4.6'
    proof_file = ROOT / 'src/test/resources/fixtures/wolfbro-ammo-4.6.json'
    proof = source_json.loads(proof_file.read_text('utf-8'))
    prepared = fixture_inputs(source, proof)
    blast_sources = explosion_inputs(source, prepared) if explosion else None
    runs = ROOT / 'build/smoke'
    runs.mkdir(parents=True, exist_ok=True)
    directory = Path(tempfile.mkdtemp(prefix='wolfbro-ammo-native-', dir=runs))
    fixture = directory / 'resources-fixture'
    flags = subprocess.CREATE_NO_WINDOW if os.name == 'nt' else 0
    snapshots, inspections, credentials = [], [], {}
    stops = 0
    try:
        physical = sorted(set(prepared['copiedChapterFiles']) | set(prepared['copiedRuntimeOriginalFiles']))
        # Runtime source guards require the real files to remain under the configured resource root.
        junctions = mirror_resources(source, fixture, {name: None for name in physical})
        for name in physical:
            target = fixture / name
            target.parent.mkdir(parents=True, exist_ok=True)
            if not target.resolve().is_relative_to(fixture.resolve()):
                raise ValueError('Original copy target escaped the owned fixture')
            shutil.copy2(source / name, target)
            if sha(target) != prepared['sourceResourceHashes'][name]:
                raise ValueError('Original physical copy changed bytes: ' + name)
        if junctions:
            plan = directory / 'junctions.json'
            plan.write_text(json.dumps(junctions), 'utf-8')
            helper = directory / 'prepare.ps1'
            helper.write_text("param([string]$Plan)\n$ErrorActionPreference='Stop'\nforeach ($binding in (Get-Content -LiteralPath $Plan -Raw | ConvertFrom-Json)) { New-Item -ItemType Junction -Path $binding.target -Value $binding.source | Out-Null }\n", 'utf-8')
            subprocess.run(['powershell', '-NoProfile', '-NonInteractive', '-File', str(helper), '-Plan', str(plan)],
                capture_output=True, check=True, creationflags=flags)
        (fixture / 'resource-manifest.json').write_text(json.dumps(dict(clientVersion='4.6.0',
            testFixture=True, wolfBroAmmoFixture=True, sourceCommit=COMMIT, frozenJarSHA256=artifact,
            originalLevel11FloorGroupsRowsUnchanged=True, originalChapterHistorySeeded=True,
            requiredAmmoOriginalsPhysicallyCopied=True,
            wolfBroExplosionFixture=explosion,
            ammoRunOrDropSeeded=False)), 'utf-8')
        (directory / 'source-fixture.json').write_text(json.dumps(prepared, ensure_ascii=False, indent=2), 'utf-8')
        (directory / 'data').mkdir()
        http, mongo, game = transport_ports()
        if http == mongo:
            raise ValueError('HTTP and Mongo cannot share the same TCP port')
        config = directory / 'config.json'
        config.write_text(json.dumps({'protocol': {'wireVersion': '4.6.0'}, 'resourceDir': str(fixture),
            'httpServer': {'bindAddress': '127.0.0.1', 'bindPort': http},
            'gameServer': {'bindAddress': '127.0.0.1', 'bindPort': game},
            'internalMongoServer': {'address': '127.0.0.1', 'port': mongo, 'filePath': './data/database.mv'}}), 'utf-8')
        config_bytes = config.read_bytes()
        classes = directory / 'probe-classes'
        classes.mkdir()
        sources = [ROOT / 'scripts/fixtures/java' / name for name in [
            'org/hkrpg/server/protocol/NativeWolfBroAmmoProbe.java',
            'org/hkrpg/server/gameplay/WolfBroAmmoFixtureInspector.java',
            'emu/lunarcore/command/commands/WolfBroAmmoFixtureCommand.java']]
        compiled = subprocess.run([str(java.with_name('javac.exe')), '-encoding', 'UTF-8', '-cp', str(jar),
            '-d', str(classes), *map(str, sources)], capture_output=True, creationflags=flags)
        (directory / 'javac.log').write_bytes(compiled.stdout + compiled.stderr)
        if compiled.returncode != 0:
            raise RuntimeError('Own ammo probes did not compile: ' + str(directory / 'javac.log'))
        options = ['-Dfile.encoding=UTF-8', '-Dstdout.encoding=UTF-8', '-Dstderr.encoding=UTF-8',
            f'-Djdk.net.unixdomain.tmpdir={directory / "tcp-fallback"}', '-Dio.netty.eventLoopThreads=2']
        classpath = str(classes) + os.pathsep + str(jar)
        descriptor_run = subprocess.run([str(java), *options, '-cp', classpath,
            'org.hkrpg.server.protocol.NativeWolfBroAmmoProbe', '--verify-descriptors'],
            capture_output=True, text=True, encoding='utf-8', creationflags=flags, timeout=60)
        (directory / 'descriptors.log').write_text(descriptor_run.stdout + descriptor_run.stderr, 'utf-8')
        if descriptor_run.returncode != 0:
            raise RuntimeError('Frozen ammo descriptor preflight failed: ' + str(directory / 'descriptors.log'))
        descriptors = json.loads(next(row for row in reversed(descriptor_run.stdout.splitlines()) if row.startswith('{')))
        if not descriptors['passed']:
            raise RuntimeError('Frozen descriptor preflight did not pass')
        for index, mode in enumerate(['sequence', 'resume', 'restored']):
            if sha(jar) != artifact:
                raise ValueError('Frozen jar changed before native phase ' + mode)
            server_log = directory / f'server-{index}.log'
            with server_log.open('wb') as log:
                process = subprocess.Popen([str(java), *options, '-Xmx1536m', '-cp', classpath,
                    'org.hkrpg.server.HkrpgServer', '--no-handbook'], cwd=directory,
                    stdin=subprocess.PIPE, stdout=log, stderr=subprocess.STDOUT, creationflags=flags)
                ready = False
                try:
                    deadline = time.monotonic() + 100
                    while True:
                        if process.poll() is not None:
                            raise RuntimeError('Owned server exited during startup: ' + str(server_log))
                        try:
                            if local_request(http, '/health/ready')['ready']:
                                ready = True
                                break
                        except (OSError, urllib.error.URLError):
                            pass
                        if time.monotonic() > deadline:
                            raise TimeoutError(server_log)
                        time.sleep(.2)
                    if not credentials:
                        login = local_request(http, '/hkrpg_cn/mdk/shield/api/login', dict(account='wolfbro-ammo-native', password='local-test'))
                        if login['retcode'] != 0:
                            raise RuntimeError('Owned account login failed')
                        account = login['data']['account']
                        combo = local_request(http, '/hkrpg_cn/combo/granter/login/v2/login',
                            dict(data=json.dumps(dict(uid=account['uid'], token=account['token']))))
                        if combo['retcode'] != 0:
                            raise RuntimeError('Owned combo login failed')
                        credentials.update(accountUid=account['uid'], comboToken=combo['data']['combo_token'])
                    probe_log = directory / f'probe-{index}.log'
                    with probe_log.open('wb') as log_probe:
                        child = subprocess.Popen([str(java), *options, '-cp', classpath,
                            'org.hkrpg.server.protocol.NativeWolfBroAmmoProbe', str(game)], cwd=directory,
                            stdin=subprocess.PIPE, stdout=log_probe, stderr=subprocess.STDOUT, creationflags=flags)
                        child.stdin.write((json.dumps(dict(credentials, mode=mode, explosionScenario=explosion, gunPosition=prepared['gunPosition'],
                            checkpointMotion=prepared['checkpointMotion'])) + '\n').encode())
                        child.stdin.flush()
                        if mode != 'sequence':
                            child.stdin.close()
                        deadline, fixture_sent, fixture_ack = time.monotonic() + 240, False, False
                        nonce = int(time.time())
                        try:
                            while child.poll() is None:
                                if mode == 'sequence':
                                    text = probe_log.read_text('utf-8')
                                    marker = re.search(r'AMMO_FIXTURE_READY_UID=(\d+)', text)
                                    if marker and not fixture_sent:
                                        if int(marker.group(1)) != 10001:
                                            raise RuntimeError('Only the new single owned player can receive prerequisite history')
                                        process.stdin.write(f'wolfbroammofixture 10001 unlock {nonce}\n'.encode())
                                        process.stdin.flush()
                                        fixture_sent = True
                                    if fixture_sent and not fixture_ack and f'HKRPG_WOLFBRO_AMMO_FIXTURE_ACK:10001:unlock:{nonce}' in server_log.read_text('utf-8'):
                                        child.stdin.write(b'PREREQUISITES_APPLIED\n')
                                        child.stdin.flush()
                                        child.stdin.close()
                                        fixture_ack = True
                                if time.monotonic() > deadline:
                                    raise TimeoutError(probe_log)
                                time.sleep(.2)
                        finally:
                            stop_owned_probe(child, directory)
                    if child.returncode != 0 or mode == 'sequence' and not fixture_ack:
                        raise RuntimeError('Actual native ammo phase failed: ' + str(probe_log))
                    snapshot = json.loads(next(row for row in reversed(probe_log.read_text('utf-8').splitlines()) if row.startswith('{')))
                    if not snapshot['passed'] or snapshot['uid'] != 10001:
                        raise RuntimeError('Actual native ammo snapshot did not pass')
                    credentials.setdefault('uid', snapshot['uid'])
                    credentials.setdefault('runUuid', snapshot['game']['uuid'])
                    credentials.setdefault('gameDataHash', snapshot['gameDataHash'])
                    if snapshot['game']['uuid'] != credentials['runUuid'] or snapshot['gameDataHash'] != credentials['gameDataHash']:
                        raise RuntimeError('Real run identity changed across normally restarted sessions')
                    snapshots.append(snapshot)
                finally:
                    stops += stop_owned_server(process, directory, ready)
            inspect_control = directory / ('inspect-' + mode + '.json')
            with inspect_control.open('x', encoding='utf-8') as control:
                json.dump(dict(snapshot, ammoSourceSignature=inspections[0]['ammoSourceSignature']) if inspections else snapshot, control)
            inspected = subprocess.run([str(java), *options, '-cp', classpath,
                'org.hkrpg.server.gameplay.WolfBroAmmoFixtureInspector', str(directory), str(inspect_control)],
                cwd=ROOT, capture_output=True, text=True, encoding='utf-8', timeout=120, creationflags=flags)
            inspection_log = directory / f'inspector-{index}.log'
            inspection_log.write_text(inspected.stdout + inspected.stderr, 'utf-8')
            if inspected.returncode != 0:
                raise RuntimeError('Real stopped H2 inspection failed: ' + str(inspection_log))
            inspection = json.loads(next(row for row in reversed(inspected.stdout.splitlines()) if row.startswith('{')))
            if not inspection['passed'] or inspection['uid'] != credentials['uid']:
                raise RuntimeError('Wrong restored ammo Player')
            inspections.append(inspection)
            print(json.dumps(dict(phase=mode, normalStops=stops, checks=len(snapshot['checks']))), flush=True)
        if stops != 3 or sha(jar) != artifact or config.read_bytes() != config_bytes:
            raise ValueError('Normal stops or frozen inputs differ')
        if prepared['sourceResourceHashes'] != {name: sha(source / name) for name in prepared['sourceResourceHashes']}:
            raise ValueError('Original source resources were changed')
        result = dict(passed=True, artifactSHA256=artifact, runtimeDirectory=str(directory), snapshots=snapshots,
            inspections=inspections, normalStops=stops, sameUIDNormalH2ProcessRestarts=2,
            nativeRequests=sum(row['requests'] for row in snapshots), nativeChecks=sum(len(row['checks']) for row in snapshots),
            descriptorSchemas=descriptors['schemas'], sourceResourceHashes=prepared['sourceResourceHashes'],
            sourceChapterBlobHashes=prepared['sourceChapterBlobHashes'], fixtureSHA256=sha(proof_file),
            copiedRuntimeOriginalFiles=prepared['copiedRuntimeOriginalFiles'],
            fixture=dict(level=11, plane=20321, floor=20321001, entry=2032101, sourceFiles=6, completeRows=30,
                originalFloorGroupsGunNPCRowsUnchanged=True, seededOriginalMainMission=1031501,
                seededOriginalSubMission=103150120, sourceChapterComplete=True, gunRunAmmoDropOrDefeatSeeded=False),
            serverPolicy=proof['serverPolicy'], sourceResourcesModified=False, operatorDataModified=False,
            nativeAmmoSequenceVerified=True, officialNumericalRulesRecovered=False, clientGameplayVerified=False,
            nativeExplosionReportSequenceVerified=explosion, explosionSource=blast_sources,
            explosionGameplayVerified=False, clientMovementPhysicsVerified=False, crashAtomicityVerified=False,
            wholeWolfBroGameComplete=False, wholeGameComplete=False)
        with output.open('x', encoding='utf-8') as report:
            report.write(json.dumps(result, ensure_ascii=False, indent=2) + '\n')
        print(json.dumps({key: result[key] for key in ['passed', 'artifactSHA256', 'nativeRequests',
            'nativeChecks', 'normalStops', 'runtimeDirectory']}, ensure_ascii=False))
    except BaseException as error:
        with (directory / 'failed-run.json').open('x', encoding='utf-8') as report:
            json.dump(dict(passed=False, error=repr(error), traceback=''.join(traceback.format_exception(error)),
                artifactSHA256=artifact, runtimeDirectory=str(directory), normalStops=stops,
                completedSnapshots=snapshots, inspections=inspections), report, ensure_ascii=False, indent=2)
        raise


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--jar', type=Path, default=ROOT / 'build/libs/hkrpg-server.jar')
    parser.add_argument('--output', type=Path, default=ROOT / 'build' / ('wolfbro-ammo-' + str(time.time_ns()) + '.json'))
    parser.add_argument('--explosion', action='store_true', help='验证真实6502爆炸上报，仍使用原弹药账本与H2检查')
    args = parser.parse_args()
    jar, output = args.jar.resolve(), args.output.resolve()
    if not jar.is_file() or not jar.is_relative_to(ROOT / 'build') or not output.is_relative_to(ROOT / 'build'):
        raise ValueError('Only a local frozen build jar and fresh build report are accepted')
    reserve_output(jar, output)
    run(jar, output, explosion=args.explosion)


if __name__ == '__main__':
    main()
