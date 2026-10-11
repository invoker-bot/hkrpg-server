"""原始两层 Chess 教程限定路线 KCP/H2 工具；--preflight-only 不启动游戏服务。"""
import argparse
import base64
import hashlib
import json
import os
from pathlib import Path
import re
import socket
import subprocess
import sys
import tempfile
import time
import traceback
import urllib.error
import urllib.request
import zipfile

from smoke_resource_fixture import mirror_resources

ROOT = Path(__file__).resolve().parents[1]
COMMIT = '724b139d8c9c32d12552eb95745a4fee72bfe48b'
PROOF = ROOT / 'src/test/resources/fixtures/chess-rogue-merchant-entry-4.6.json'
POLICY = ROOT / 'src/main/resources/defaults/ChessRogueGuideRoutes.json'
JAVA_SOURCES = [
    ROOT / 'scripts/fixtures/java/org/hkrpg/server/protocol/NativeChessRogueMerchantProbe.java',
    ROOT / 'scripts/fixtures/java/emu/lunarcore/command/commands/ChessRogueFixtureCommand.java',
    ROOT / 'scripts/fixtures/java/org/hkrpg/server/gameplay/ChessRogueFixtureInspector.java']


class FixtureConsoleBridge:
    """Console acknowledgements gate the real native sequence; this never invents a reply."""
    def __init__(self, nonce):
        self.nonce = nonce
        self.phases = ['unlock', 'start', 'win201', 'win202', 'win203', 'win204']
        self.acknowledged = []
        self.pending = None
        self.battles = set()

    def next_command(self, probe_text):
        if self.pending is not None or self.complete:
            return None
        phase = self.phases[len(self.acknowledged)]
        if phase == 'unlock':
            marker = re.search(r'CHESS_FIXTURE_READY_UID=(\d+)', probe_text)
            if marker:
                if int(marker.group(1)) != 10001:
                    raise RuntimeError('Only one owned new Player receives eligibility history')
                self.pending = ('unlock', 0)
                return f'chessroguefixture 10001 unlock {self.nonce}\n'
        else:
            marker = re.search(rf'CHESS_OBSERVE_READY_UID=(\d+):{phase}:(\d+)', probe_text)
            if marker:
                uid, battle = map(int, marker.groups())
                if uid != 10001 or (phase == 'start') != (battle == 0) or battle in self.battles:
                    raise RuntimeError('Read-only observation must match the current owned phase and unique actual battle')
                self.pending = (phase, battle)
                return f'chessroguefixture 10001 observe-{phase} {battle} {self.nonce}\n'
        return None

    def next_acknowledgement(self, server_text):
        if self.pending is not None:
            phase, battle = self.pending
            tag = 'unlock' if phase == 'unlock' else f'observe-{phase}:{battle}'
            if f'HKRPG_CHESS_FIXTURE_ACK:10001:{tag}:{self.nonce}' in server_text:
                self.acknowledged.append(phase)
                if battle:
                    self.battles.add(battle)
                self.pending = None
                return 'PREREQUISITES_APPLIED\n' if phase == 'unlock' else f'CHESS_OBSERVATION_RECORDED:{phase}:{battle}\n'
        return None

    @property
    def complete(self):
        return self.acknowledged == self.phases


def sha(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def digest_bytes(raw):
    return hashlib.sha256(raw).hexdigest()


def port(kind=socket.SOCK_STREAM):
    with socket.socket(type=kind) as transport:
        transport.bind(('127.0.0.1', 0))
        return transport.getsockname()[1]


def transport_ports():
    http = port()
    for _ in range(50):
        mongo = port()
        if mongo != http:
            return http, mongo, port(socket.SOCK_DGRAM)
    raise RuntimeError('Could not obtain distinct HTTP and Mongo TCP ports')


def local_request(http, route, body=None):
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    request = urllib.request.Request(f'http://127.0.0.1:{http}' + route,
        data=None if body is None else json.dumps(body).encode(), headers={'Content-Type': 'application/json'})
    with opener.open(request, timeout=5) as response:
        return json.load(response)


def cleanup_failure(failed, directory, primary, label):
    with (directory / 'cleanup-failures.log').open('a', encoding='utf-8') as log:
        log.write(label + ': ' + repr(failed) + '\n')
    if primary is not None:
        primary.add_note(label + ': ' + repr(failed))
    else:
        raise failed


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
        cleanup_failure(failed, directory, primary, 'Owned Chess probe cleanup also failed')
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
        cleanup_failure(failed, directory, primary, 'Owned Chess server stop also failed')
        return 0


def reserve_output(jar, output):
    if output.exists():
        raise FileExistsError('Previous evidence is preserved; choose a new output: ' + str(output))
    output.parent.mkdir(parents=True, exist_ok=True)
    lease = output.with_name(output.name + '.lease.json')
    with lease.open('x', encoding='utf-8') as reservation:
        json.dump(dict(output=str(output), pid=os.getpid(), jarSHA256=sha(jar), startedEpoch=time.time()), reservation)
    return lease


def inside(source, relative):
    if '\\' in relative:
        raise ValueError('Source bindings must be portable relative paths')
    path = source / relative
    if not path.resolve().is_relative_to(source.resolve()):
        raise ValueError('Original source binding escaped the resource root')
    return path


def enabled_policy(default, proof):
    if default.get('enabled') is not False:
        raise ValueError('Production Chess routing must remain disabled by default')
    candidate = dict(default, enabled=True)
    if candidate != proof['route']:
        raise ValueError('Only enabled may differ from the complete pinned route fixture')
    if (candidate['initialCoins'], candidate['coinsPerMonsterVictory'], candidate['miracleBasePrice'],
            candidate['miracleIds'], candidate['monsterLevel']) != (0, 100, 200, [4], 53):
        raise ValueError('Bounded declared server policy changed')
    return candidate


def fixture_inputs(source, proof, default):
    if proof['source']['commit'] != COMMIT or len(proof['files']) != 88 or len(proof['rows']) != 23:
        raise ValueError('Wrong complete pinned 88-blob/23-table Chess fixture')
    policy = enabled_policy(default, proof)
    objects, pins, blob_hashes, table_hashes = {}, {}, {}, {}
    for record in proof['files']:
        path = inside(source, record['path'])
        original = base64.b64decode(record['rawBase64'], validate=True)
        normalized = original.replace(b'\r\n', b'\n')
        if digest_bytes(original) != record['sourceSHA256'] or digest_bytes(normalized) != record['normalizedSHA256']:
            raise ValueError('Pinned complete original blob SHA mismatch: ' + record['path'])
        actual = path.read_bytes()
        if actual.replace(b'\r\n', b'\n') != normalized or json.loads(actual) != json.loads(original):
            raise ValueError('Deployed complete original blob changed: ' + record['path'])
        pins[record['path']], blob_hashes[record['path']] = sha(path), record['sourceSHA256']
        objects[record['path']] = json.loads(original)
    rows = {}
    for record in proof['rows']:
        path = inside(source, record['path'])
        raw = path.read_bytes()
        if digest_bytes(raw.replace(b'\r\n', b'\n')) != record['normalizedSHA256']:
            raise ValueError('Complete original table bytes changed: ' + record['table'])
        actual = json.loads(raw)
        for row in record['rows']:
            if sum(candidate == row for candidate in actual) != 1:
                raise ValueError('Selected whole original table row changed: ' + record['table'])
        rows[record['table']] = record['rows']
        pins[record['path']], table_hashes[record['path']] = sha(path), record['sourceSHA256']
    # The complete floor stays byte-for-byte original; do not reduce it to three rooms.
    group_files = [name for name in objects if '/Groups_P80201_F80201001/' in name]
    if len(group_files) != 82:
        raise ValueError('Complete original floor must retain all 82 source group files')
    boards = []
    # These block IDs are the fixed source enum already used by the verified route, not new command IDs.
    block_ids = dict(MonsterNormal=3, MonsterBoss=11, Respite=2, Event=8, MonsterElite=5, Adventure=6)
    room_ids = {room['block']: room['room'] for room in policy['rooms']}
    for layer in policy['layers']:
        row = next(row for row in rows['RogueDLCChessBoard'] if row['ChessBoardID'] == layer['board'])
        board = objects[row['ChessBoardConfiguration']]
        cells = {key: dict(x=cell['PosX'], y=cell['PosY'], block=block_ids[cell['BlockTypeList'][0]],
            room=room_ids.get(cell['BlockTypeList'][0], 0)) for key, cell in board['RogueChestGridItemMap'].items()}
        boards.append(dict(board=layer['board'], layer=layer['layer'], width=board['Width'], height=board['Height'],
            start=board['StartGridItemID'], end=board['EndGridItemID'], cells=cells, path=layer['path']))
    act_name = 'Config/Level/RogueDialogue/RogueDialogueEvent/Act/Rogue130/Act0110003.json'
    act = objects[act_name]
    tasks = [task for sequence in act['OnStartSequece'] for task in sequence['TaskList']]
    choices = [task for task in tasks if task.get('$type') == 'RPG.GameCore.PlayRogueOptionTalk']
    pages = [task for task in tasks if task.get('$type') == 'RPG.GameCore.RoguePropOpenPage']
    if len(choices) != 1 or not any(row['TriggerCustomString'] == 'Go_Miracle' for row in choices[0]['OptionList']) \
            or len(pages) != 1 or pages[0]['Type'] != 'MiracleShop' or pages[0]['WaitForExit'] is not True:
        raise ValueError('Original complete local merchant Act changed')
    group = objects['Config/LevelOutput/SharedRuntimeGroup/Groups_P80201_F80201001/LevelGroup_P80201_F80201001_G35.json']
    npc = [record for record in group['NPCList'] if record['ID'] == 400001]
    if len(npc) != 1 or npc[0]['NPCID'] != 1013 or npc[0]['Dialog']['LevelGraph'] != act_name:
        raise ValueError('Original G35 actor-to-Act source binding changed')
    actors = {}
    for key, block, number in [('normal', 'MonsterNormal', 111), ('boss', 'MonsterBoss', 139011)]:
        room = next(row for row in policy['rooms'] if row['block'] == block)
        placements = []
        for group_id in room['groups']:
            path = f'Config/LevelOutput/SharedRuntimeGroup/Groups_P80201_F80201001/LevelGroup_P80201_F80201001_G{group_id}.json'
            placements.extend((group_id, record) for record in objects[path].get('MonsterList', []))
        if len(placements) != 1:
            raise ValueError('Declared route must have one actual original monster placement: ' + block)
        group_id, placement = placements[0]
        monster = next(row for row in rows['RogueMonster'] if row['RogueMonsterID'] == number)
        stage = next(row for row in rows['StageConfig'] if row['StageID'] == monster['EventID'])
        actors[key] = dict(rogueMonster=number, group=group_id, instance=placement['ID'], originalPlacementNPC=placement['NPCMonsterID'],
            npc=monster['NpcMonsterID'], stage=monster['EventID'], waves=[list(wave.values()) for wave in stage['MonsterList']])
    main_path = source / 'ExcelOutput/MainMission.json'
    mains = json.loads(main_path.read_text('utf-8'))
    qualification = [row for row in mains if row['MainMissionID'] == 8013104]
    if len(qualification) != 1 or qualification[0]['RewardID'] != 18013104:
        raise ValueError('Original eligibility mission row is missing')
    original = subprocess.run(['git', '-C', str(ROOT / 'references/TurnBasedGameData'), 'show',
        COMMIT + ':ExcelOutput/MainMission.json'], capture_output=True, check=True).stdout
    if main_path.read_bytes().replace(b'\r\n', b'\n') != original.replace(b'\r\n', b'\n'):
        raise ValueError('Qualification table differs from the fixed source commit')
    reward_path = source / 'ExcelOutput/RewardData.json'
    rewards = json.loads(reward_path.read_text('utf-8'))
    qualification_reward = [row for row in rewards if row['RewardID'] == 18013104]
    if qualification_reward != [dict(RewardID=18013104, ItemID_1=2, Count_1=5000)]:
        raise ValueError('Complete original qualification reward row changed')
    original_reward = subprocess.run(['git', '-C', str(ROOT / 'references/TurnBasedGameData'), 'show',
        COMMIT + ':ExcelOutput/RewardData.json'], capture_output=True, check=True).stdout
    if reward_path.read_bytes().replace(b'\r\n', b'\n') != original_reward.replace(b'\r\n', b'\n'):
        raise ValueError('Qualification reward table differs from the fixed source commit')
    guarded = set(source.glob('ExcelOutput/*.json')) | {source / name for name in pins} | {source / 'resource-manifest.json'}
    return dict(sourceResourceHashes={path.relative_to(source).as_posix(): sha(path) for path in sorted(guarded)},
        sourceBlobHashes=blob_hashes, sourceTableHashes=table_hashes, sourceFilesCount=88, sourceTableCount=23,
        completeGroupFiles=82, selectedWholeRows=sum(len(table) for table in rows.values()),
        qualification=qualification[0], qualificationReward=qualification_reward[0],
        qualificationRewardTableSHA256=digest_bytes(original_reward), policy=policy, actors=actors, boards=boards,
        localActSource=dict(path=act_name, sourceSHA256=blob_hashes[act_name], taskTag=2304,
            taskTagIsWireCommand=False, clientActExecuted=False, originalNpc=1013, group=35, instance=400001))


def prepare(jar, directory, preflight_only):
    artifact = sha(jar)
    java = next((ROOT / '.tools').glob('jdk-21*/bin/java.exe'), None)
    if not java or not java.is_file():
        raise FileNotFoundError('Owned Java21 is missing')
    source = ROOT / 'resources/4.6'
    proof = json.loads(PROOF.read_text('utf-8'))
    default = json.loads(POLICY.read_text('utf-8'))
    prepared = fixture_inputs(source, proof, default)
    fixture = directory / 'resources-fixture'
    flags = subprocess.CREATE_NO_WINDOW if os.name == 'nt' else 0
    junctions = mirror_resources(source, fixture, {})
    if junctions:
        plan = directory / 'junctions.json'
        plan.write_text(json.dumps(junctions), 'utf-8')
        helper = directory / 'prepare.ps1'
        helper.write_text("param([string]$Plan)\n$ErrorActionPreference='Stop'\nforeach ($binding in (Get-Content -LiteralPath $Plan -Raw | ConvertFrom-Json)) { New-Item -ItemType Junction -Path $binding.target -Value $binding.source | Out-Null }\n", 'utf-8')
        subprocess.run(['powershell', '-NoProfile', '-NonInteractive', '-File', str(helper), '-Plan', str(plan)],
            check=True, capture_output=True, creationflags=flags)
    (fixture / 'resource-manifest.json').write_text(json.dumps(dict(clientVersion='4.6.0', testFixture=True,
        chessRogueFixture=True, sourceCommit=COMMIT, frozenJarSHA256=artifact,
        originalFloorGroupsBoardsActAndTablesUnchanged=True, originalQualificationHistorySeeded=True,
        chessRunWalletVictoryRewardOrEntrySeeded=False)), 'utf-8')
    data = directory / 'data'
    data.mkdir()
    policy_file = data / 'ChessRogueGuideRoutes.json'
    policy_file.write_text(json.dumps(prepared['policy'], ensure_ascii=False, indent=2) + '\n', 'utf-8')
    with zipfile.ZipFile(jar) as archive:
        includes_feature = 'org/hkrpg/server/gameplay/ChessRogueState.class' in archive.namelist()
        if not preflight_only:
            if not includes_feature or json.loads(archive.read('defaults/ChessRogueGuideRoutes.json')) != default:
                raise ValueError('Native execution requires the new frozen jar with the exact disabled-by-default Chess policy')
    http, mongo, game = transport_ports()
    if http == mongo:
        raise ValueError('HTTP/Mongo cannot share a TCP port')
    config = directory / 'config.json'
    config.write_text(json.dumps({'protocol': {'wireVersion': '4.6.0'}, 'resourceDir': str(fixture),
        'httpServer': {'bindAddress': '127.0.0.1', 'bindPort': http},
        'gameServer': {'bindAddress': '127.0.0.1', 'bindPort': game},
        'internalMongoServer': {'address': '127.0.0.1', 'port': mongo, 'filePath': './data/database.mv'}}), 'utf-8')
    classes = directory / 'probe-classes'
    classes.mkdir()
    compile_result = subprocess.run([str(java.with_name('javac.exe')), '-encoding', 'UTF-8', '-cp', str(jar),
        '-d', str(classes), *map(str, JAVA_SOURCES)], capture_output=True, creationflags=flags, timeout=90)
    (directory / 'javac.log').write_bytes(compile_result.stdout + compile_result.stderr)
    if compile_result.returncode:
        raise RuntimeError('Owned Chess probe javac failed: ' + str(directory / 'javac.log'))
    options = ['-Dfile.encoding=UTF-8', '-Dstdout.encoding=UTF-8', '-Dstderr.encoding=UTF-8',
        f'-Djdk.net.unixdomain.tmpdir={directory / "tcp-fallback"}', '-Dio.netty.eventLoopThreads=2']
    classpath = str(classes) + os.pathsep + str(jar)
    result = subprocess.run([str(java), *options, '-cp', classpath,
        'org.hkrpg.server.protocol.NativeChessRogueMerchantProbe', '--verify-descriptors'],
        capture_output=True, text=True, encoding='utf-8', creationflags=flags, timeout=60)
    (directory / 'descriptors.log').write_text(result.stdout + result.stderr, 'utf-8')
    if result.returncode:
        raise RuntimeError('Owned Chess descriptor preflight failed: ' + str(directory / 'descriptors.log'))
    descriptors = json.loads(next(line for line in reversed(result.stdout.splitlines()) if line.startswith('{')))
    if not descriptors['passed']:
        raise RuntimeError('Descriptor preflight did not pass')
    storage = subprocess.run([str(java), *options, '-cp', classpath,
        'org.hkrpg.server.gameplay.ChessRogueFixtureInspector', '--verify-storage-codecs'],
        capture_output=True, text=True, encoding='utf-8', creationflags=flags, timeout=60)
    (directory / 'storage-codecs.log').write_text(storage.stdout + storage.stderr, 'utf-8')
    if storage.returncode:
        raise RuntimeError('Actual stored PlayerLineup codec preflight failed: ' + str(directory / 'storage-codecs.log'))
    storage_checks = json.loads(next(line for line in reversed(storage.stdout.splitlines()) if line.startswith('{')))
    if not storage_checks['passed'] or storage_checks['nativeExecuted']:
        raise RuntimeError('Pure storage codec check must pass without gameplay Native')
    prepared.update(artifactSHA256=artifact, fixtureSHA256=sha(PROOF), disabledDefaultPolicySHA256=sha(POLICY),
        isolatedPolicySHA256=sha(policy_file), isolatedConfigSHA256=sha(config), descriptorSchemas=descriptors['schemas'],
        descriptorChecks=descriptors['checks'], frozenJarHasChessFeature=includes_feature,
        storageCodecChecks=storage_checks,
        sourcesSHA256={str(path.relative_to(ROOT)): sha(path) for path in JAVA_SOURCES})
    (directory / 'prepared-inputs.json').write_text(json.dumps(prepared, ensure_ascii=False, indent=2), 'utf-8')
    return prepared, java, options, classpath, (http, mongo, game)


def unchanged(jar, directory, prepared):
    if sha(jar) != prepared['artifactSHA256'] or sha(directory / 'config.json') != prepared['isolatedConfigSHA256'] \
            or sha(directory / 'data/ChessRogueGuideRoutes.json') != prepared['isolatedPolicySHA256'] \
            or sha(POLICY) != prepared['disabledDefaultPolicySHA256'] or sha(PROOF) != prepared['fixtureSHA256']:
        raise ValueError('Frozen jar, fixture, original default policy or isolated inputs changed')
    if prepared['sourcesSHA256'] != {str(path.relative_to(ROOT)): sha(path) for path in JAVA_SOURCES}:
        raise ValueError('Frozen owned native probe source changed')
    if prepared['sourceResourceHashes'] != {name: sha(ROOT / 'resources/4.6' / name) for name in prepared['sourceResourceHashes']}:
        raise ValueError('Original resources were changed')


def run(jar, output, preflight_only):
    runs = ROOT / 'build/smoke'
    runs.mkdir(parents=True, exist_ok=True)
    directory = Path(tempfile.mkdtemp(prefix='chess-rogue-native-', dir=runs))
    snapshots, inspections, credentials, stops = [], [], {}, 0
    artifact = sha(jar)
    try:
        prepared, java, options, classpath, (http, mongo, game) = prepare(jar, directory, preflight_only)
        unchanged(jar, directory, prepared)
        if preflight_only:
            result = dict(passed=True, prepared=True, nativeExecuted=False, runtimeDirectory=str(directory), **prepared)
        else:
            flags = subprocess.CREATE_NO_WINDOW if os.name == 'nt' else 0
            for index, mode in enumerate(['sequence', 'resume', 'restored']):
                unchanged(jar, directory, prepared)
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
                            login = local_request(http, '/hkrpg_cn/mdk/shield/api/login', dict(account='chess-rogue-native', password='local-test'))
                            if login['retcode'] != 0:
                                raise RuntimeError('Owned account login failed')
                            account = login['data']['account']
                            combo = local_request(http, '/hkrpg_cn/combo/granter/login/v2/login',
                                dict(data=json.dumps(dict(uid=account['uid'], token=account['token']))))
                            if combo['retcode'] != 0:
                                raise RuntimeError('Owned combo login failed')
                            credentials.update(accountUid=account['uid'], comboToken=combo['data']['combo_token'])
                        probe_log = directory / f'probe-{index}.log'
                        with probe_log.open('wb') as child_log:
                            child = subprocess.Popen([str(java), *options, '-cp', classpath,
                                'org.hkrpg.server.protocol.NativeChessRogueMerchantProbe', str(game)], cwd=directory,
                                stdin=subprocess.PIPE, stdout=child_log, stderr=subprocess.STDOUT, creationflags=flags)
                            child.stdin.write((json.dumps(dict(credentials, mode=mode, actors=prepared['actors'], boards=prepared['boards'])) + '\n').encode())
                            child.stdin.flush()
                            if mode != 'sequence':
                                child.stdin.close()
                            deadline = time.monotonic() + 240
                            bridge = FixtureConsoleBridge(int(time.time()))
                            try:
                                while child.poll() is None:
                                    if mode == 'sequence':
                                        command = bridge.next_command(probe_log.read_text('utf-8'))
                                        if command:
                                            process.stdin.write(command.encode())
                                            process.stdin.flush()
                                        acknowledgement = bridge.next_acknowledgement(server_log.read_text('utf-8'))
                                        if acknowledgement:
                                            child.stdin.write(acknowledgement.encode())
                                            child.stdin.flush()
                                            if bridge.complete:
                                                child.stdin.close()
                                    if time.monotonic() > deadline:
                                        raise TimeoutError(probe_log)
                                    time.sleep(.2)
                            finally:
                                stop_owned_probe(child, directory)
                        if child.returncode or mode == 'sequence' and not bridge.complete:
                            raise RuntimeError('Actual native Chess phase failed: ' + str(probe_log))
                        snapshot = json.loads(next(line for line in reversed(probe_log.read_text('utf-8').splitlines()) if line.startswith('{')))
                        if not snapshot['passed'] or snapshot['uid'] != 10001:
                            raise RuntimeError('Actual native Chess snapshot did not pass')
                        snapshot['artifactSHA256'] = artifact
                        snapshot['isolatedPolicySHA256'] = prepared['isolatedPolicySHA256']
                        snapshots.append(snapshot)
                        credentials.setdefault('uid', snapshot['uid'])
                        credentials.setdefault('gameInfoHash', snapshot['gameInfoHash'])
                        credentials.setdefault('classicHash', snapshot['classicHash'])
                        unchanged(jar, directory, prepared)
                    finally:
                        stops += stop_owned_server(process, directory, ready)
                control = directory / f'inspect-{mode}.json'
                with control.open('x', encoding='utf-8') as writer:
                    json.dump(dict(snapshot, expectedChessDocument=inspections[0]['chessDocument']) if inspections else snapshot, writer)
                inspected = subprocess.run([str(java), *options, '-cp', classpath,
                    'org.hkrpg.server.gameplay.ChessRogueFixtureInspector', str(directory), str(control)],
                    cwd=ROOT, capture_output=True, text=True, encoding='utf-8', timeout=120, creationflags=flags)
                inspection_log = directory / f'inspector-{index}.log'
                inspection_log.write_text(inspected.stdout + inspected.stderr, 'utf-8')
                if inspected.returncode:
                    raise RuntimeError('Normally stopped read-only H2 inspection failed: ' + str(inspection_log))
                inspection = json.loads(next(line for line in reversed(inspected.stdout.splitlines()) if line.startswith('{')))
                if not inspection['passed'] or inspection['uid'] != credentials['uid']:
                    raise RuntimeError('Wrong restored Chess Player')
                inspections.append(inspection)
                print(json.dumps(dict(phase=mode, normalStops=stops, checks=len(snapshot['checks']))), flush=True)
            unchanged(jar, directory, prepared)
            if stops != 3:
                raise ValueError('Expected three actual normal stops and two same-UID H2 process restarts')
            observations = [json.loads((directory / f'fixture-observed-{phase}.json').read_text('utf-8'))
                for phase in ['start', 'win201', 'win202', 'win203', 'win204']]
            result = dict(passed=True, prepared=True, nativeExecuted=True, artifactSHA256=artifact,
                runtimeDirectory=str(directory), normalStops=stops, sameUIDNormalH2ProcessRestarts=2,
                nativeRequests=sum(row['requests'] for row in snapshots), nativeChecks=sum(len(row['checks']) for row in snapshots),
                snapshots=snapshots, inspections=inspections, sourceEvidence=prepared,
                readOnlyAfterStartAndPerBattleObservations=observations,
                originalResourcesModified=False, operatorDataModified=False,
                seededOriginalEligibilityHistory=8013104, chessRunCoinsVictoryRewardOrEntrySeeded=False,
                gameplayExitVerified=False, clientMenuActExecuted=False, localTaskTag2304SentAsWireCommand=False,
                clientGameplayVerified=False, officialPhysicalRouteOrNumericalPolicyRecovered=False,
                blessingShopVerified=False, miracle4FutureEffectVerified=False, crashAtomicityVerified=False,
                wholeChessRogueGameComplete=False, wholeGameComplete=False)
        with output.open('x', encoding='utf-8') as report:
            report.write(json.dumps(result, ensure_ascii=False, indent=2) + '\n')
        print(json.dumps(dict(passed=result['passed'], nativeExecuted=result['nativeExecuted'],
            artifactSHA256=artifact, runtimeDirectory=str(directory)), ensure_ascii=False))
    except BaseException as error:
        with (directory / 'failed-run.json').open('x', encoding='utf-8') as report:
            json.dump(dict(passed=False, error=repr(error), traceback=''.join(traceback.format_exception(error)),
                artifactSHA256=artifact, runtimeDirectory=str(directory), normalStops=stops,
                completedSnapshots=snapshots, inspections=inspections), report, ensure_ascii=False, indent=2)
        raise


def main():
    if hasattr(sys.stdout, 'reconfigure'):
        sys.stdout.reconfigure(encoding='utf-8')
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--jar', type=Path, required=True, help='本地冻结jar；真实运行须新Chess实现')
    parser.add_argument('--output', type=Path, default=ROOT / 'build' / ('chess-rogue-native-' + str(time.time_ns()) + '.json'))
    parser.add_argument('--preflight-only', action='store_true', help='仅来源、javac与descriptor；不启动游戏JVM/KCP')
    args = parser.parse_args()
    jar, output = args.jar.resolve(), args.output.resolve()
    if not jar.is_file() or not jar.is_relative_to(ROOT / 'build') or not output.is_relative_to(ROOT / 'build'):
        raise ValueError('Only a frozen local build jar and fresh build report are accepted')
    reserve_output(jar, output)
    run(jar, output, args.preflight_only)


if __name__ == '__main__':
    main()
