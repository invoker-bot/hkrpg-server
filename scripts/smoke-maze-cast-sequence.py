"""隔离 KCP 验证1408/1460同UseID、逐请求回调、MP与正常H2恢复；不启动官方客户端。"""
import argparse
import copy
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

ROOT = next(parent for parent in Path(__file__).resolve().parents
            if (parent / 'scripts/smoke_resource_fixture.py').is_file())
sys.path.insert(0, str(ROOT / 'scripts'))
from smoke_resource_fixture import mirror_resources

COMMIT = '724b139d8c9c32d12552eb95745a4fee72bfe48b'
QUOTE_TABLES = tuple('ExcelOutput/' + name for name in
    ('MazeSkill.json', 'AvatarConfig.json', 'MultiplePathAvatarConfig.json', 'AvatarSkillConfig.json'))


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def verify_quote_closure(source, fixture, records):
    for relative, record in records.items():
        path = fixture / relative
        if not path.is_file() or not path.resolve().is_relative_to(fixture.resolve()) \
                or os.path.samefile(path, source / relative) or sha(path) != record['originalSHA256'] \
                or sha(source / relative) != record['originalSHA256']:
            raise ValueError('Original quote table must be a complete physical owned copy: ' + relative)


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


def stop_owned_process(process, directory, ready):
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
    except Exception as cleanup:
        evidence = directory / 'cleanup-failures.log'
        with evidence.open('a', encoding='utf-8') as log:
            log.write(''.join(traceback.format_exception(cleanup)))
        if primary is not None:
            primary.add_note(f'Owned stop also failed: {cleanup}; preserved in {evidence}')
            return 0
        raise


def reserve_output(jar, output):
    if output.exists():
        raise FileExistsError('Previous native evidence is preserved; choose a new output: ' + str(output))
    output.parent.mkdir(parents=True, exist_ok=True)
    lease = output.with_name(output.name + '.lease.json')
    with lease.open('x', encoding='utf-8') as reservation:
        json.dump(dict(output=str(output), pid=os.getpid(), jarSHA256=sha(jar), startedEpoch=time.time()), reservation)
    return lease


def fixture_inputs(source, proof):
    assert proof['sourceCommit'] == COMMIT
    for name, expected in proof['sourceFiles'].items():
        assert sha(source / name) == expected['installedSHA256'], name
    floor_path = 'Config/LevelOutput/RuntimeFloor/P20001_F20001001.json'
    born_path = 'Config/LevelOutput/SharedRuntimeGroup/Groups_P20001_F20001001/LevelGroup_P20001_F20001001_G2.json'
    npc_path = 'Config/LevelOutput/SharedRuntimeGroup/Groups_P20001_F20001001/LevelGroup_P20001_F20001001_G91.json'
    template_path = 'Config/LevelOutput/SharedRuntimeGroup/Groups_P80001_F80001003/LevelGroup_P80001_F80001003_G18.json'
    prop_path = 'Config/LevelOutput/SharedRuntimeGroup/Groups_P80001_F80001003/LevelGroup_P80001_F80001003_G22.json'
    ordinary_path = 'Config/ConfigAdventureAbility/LocalPlayer/LocalPlayer_PlayerBoy_00_Ability.json'
    floor = json.loads((source / floor_path).read_text('utf-8'))
    template = json.loads((source / template_path).read_text('utf-8'))['MonsterList'][0]
    box = next(row for row in json.loads((source / prop_path).read_text('utf-8'))['PropList'] if row['PropID'] == 100025)
    prop = next(row for row in json.loads((source / 'ExcelOutput/MazeProp.json').read_text('utf-8')) if row['ID'] == 100025)
    assert prop['PropType'] == 'PROP_DESTRUCT'
    stages = json.loads((source / 'ExcelOutput/StageConfig.json').read_text('utf-8'))
    stage = next(row for row in stages if row['StageID'] == 80030231)
    events = json.loads((source / 'ExcelOutput/PlaneEvent.json').read_text('utf-8'))
    assert any(row['EventID'] == stage['StageID'] and row['StageID'] == stage['StageID'] for row in events)
    assert not any(row['EventID'] == 199996001 for row in events)
    assert not any(row['StageID'] == 1999960010 for row in stages)
    group = {'$type': 'RPG.GameCore.RtLevelGroupInfo', 'GroupName': 'MazeCastSequenceFixture',
        'LoadSide': 'Server', 'LoadOnInitial': True, 'MonsterList': [], 'PropList': [], 'NPCList': []}
    for instance in range(200101, 200114):
        monster = copy.deepcopy(template)
        monster.update(ID=instance, NPCMonsterID=1002020,
            EventID=199996001 if instance == 200109 else stage['StageID'], PosX=.1, PosY=.062052,
            PosZ=-4.8, IsClientOnly=False, LoadOnInitial=True)
        group['MonsterList'].append(monster)
    for instance in [300001, 300002]:
        recovery = copy.deepcopy(box)
        recovery.update(ID=instance, PosX=.1, PosY=.062052, PosZ=-4.8, LoadOnInitial=True)
        group['PropList'].append(recovery)
    floor['GroupInstanceList'] = [floor['GroupInstanceList'][0], {'GroupPath': npc_path, 'Name': group['GroupName'], 'ID': 91}]
    dimension = copy.deepcopy(floor['DimensionList'][0])
    dimension.update(GroupIndexList=[0, 1], ExclusiveSavedGroupIDList=[])
    floor['DimensionList'] = [dimension]
    floor['GroupInstanceCommonMap'] = {'2': floor['GroupInstanceCommonMap']['2'],
        '91': {'SaveType': 'Permanent', 'LoadSide': 'Server', 'LoadOnInitial': True}}
    record = next(row for row in proof['records'] if row['avatarID'] == 1009)
    ability_path = record['abilityFile']
    asta = json.loads((source / ability_path).read_text('utf-8'))
    original = record['abilities'][1]
    projectile = copy.deepcopy(original['OnStart'][0]['SuccessTaskList'][8])
    assert projectile['$type'] == 'RPG.GameCore.AdventureFireProjectile'
    assert projectile['Projectile']['Behavior'] == 'Static' and '$type' not in projectile['Projectile']

    def add(buff):
        return {'$type': 'RPG.GameCore.AddMazeBuff', 'ID': buff,
            'TargetType': {'$type': 'RPG.GameCore.TargetAlias', 'Alias': 'Caster'},
            'LifeTime': {'FixedValue': {'Value': 3600}}}

    def remove(buff):
        return {'$type': 'RPG.GameCore.RemoveMazeBuff', 'ID': buff,
            'TargetType': {'$type': 'RPG.GameCore.TargetAlias', 'Alias': 'Caster'}}

    def trigger(battle):
        return {'$type': 'RPG.GameCore.AdventureTriggerAttack', 'TriggerBattle': battle}

    # Removing then replenishing a private marker yields one real notification per invocation.
    # Those leaves count dispatch only; they do not replace claims about the original effects.
    projectile['OnProjectileHit'] = [remove(199998112), add(199998112), trigger(False)]
    projectile['OnProjectileLifetimeFinish'] = [remove(199998113), add(199998113), trigger(True)]
    technique = next(row for row in asta['AbilityList'] if row['Name'] == original['Name'])
    technique['OnStart'] = [add(199998111), remove(199998111), add(199998112), add(199998113), trigger(False), projectile]
    replacements = {floor_path: floor, born_path: json.loads((source / born_path).read_text('utf-8')),
        npc_path: group, ability_path: asta}
    for relative in QUOTE_TABLES:
        replacements[relative] = json.loads((source / relative).read_text('utf-8'))
    guarded = set(source.glob('ExcelOutput/*.json')) | {source / name for name in proof['sourceFiles']}
    guarded |= {source / name for name in [floor_path, born_path, template_path, prop_path, ordinary_path, 'resource-manifest.json']}
    if (source / npc_path).exists():
        guarded.add(source / npc_path)
    hashes = {path.relative_to(source).as_posix(): sha(path) for path in sorted(guarded)}
    return replacements, hashes, stage


def run(jar, output):
    artifact = sha(jar)
    java = next((ROOT / '.tools').glob('jdk-21*/bin/java.exe'), None)
    assert java and java.is_file()
    source = ROOT / 'resources/4.6'
    proof_file = ROOT / 'src/test/resources/fixtures/maze-cast-sequence-4.6.json'
    proof = json.loads(proof_file.read_text('utf-8'))
    replacements, hashes, stage = fixture_inputs(source, proof)
    runs = ROOT / 'build/smoke'
    runs.mkdir(parents=True, exist_ok=True)
    directory = Path(tempfile.mkdtemp(prefix='maze-cast-sequence-', dir=runs))
    fixture = directory / 'resources-fixture'
    flags = subprocess.CREATE_NO_WINDOW if os.name == 'nt' else 0
    snapshots, inspections, credentials = [], [], {}
    stops = 0
    try:
        junctions = mirror_resources(source, fixture, replacements)
        for name, value in replacements.items():
            target = fixture / name
            assert target.resolve().is_relative_to(fixture.resolve())
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_text(json.dumps(value, ensure_ascii=False), 'utf-8')
        quote_copies = {}
        for relative in QUOTE_TABLES:
            target = fixture / relative
            if not target.resolve().is_relative_to(fixture.resolve()) or os.path.samefile(target, source / relative):
                raise ValueError('Quote materialization escaped its owned closed resource root: ' + relative)
            original = (source / relative).read_bytes()
            target.write_bytes(original)
            quote_copies[relative] = dict(originalSHA256=sha(source / relative), fullRows=len(json.loads(original)))
        verify_quote_closure(source, fixture, quote_copies)
        if junctions:
            plan = directory / 'junctions.json'
            plan.write_text(json.dumps(junctions), 'utf-8')
            helper = directory / 'prepare.ps1'
            helper.write_text("param([string]$Plan)\n$ErrorActionPreference='Stop'\nforeach ($binding in (Get-Content -LiteralPath $Plan -Raw | ConvertFrom-Json)) { New-Item -ItemType Junction -Path $binding.target -Value $binding.source | Out-Null }\n", 'utf-8')
            subprocess.run(['powershell', '-NoProfile', '-NonInteractive', '-File', str(helper), '-Plan', str(plan)],
                check=True, capture_output=True, creationflags=flags)
        (fixture / 'resource-manifest.json').write_text(json.dumps(dict(clientVersion='4.6.0',
            testFixture=True, mazeCastSequenceFixture=True, sourceCommit=COMMIT,
            controlledSceneAndAstaMarkerLeaves=True, useIDOrPaymentPrewritten=False)), 'utf-8')
        (directory / 'data').mkdir()
        http, mongo, game = transport_ports()
        assert http != mongo
        config = directory / 'config.json'
        config.write_text(json.dumps({'protocol': {'wireVersion': '4.6.0'}, 'resourceDir': str(fixture),
            'httpServer': {'bindAddress': '127.0.0.1', 'bindPort': http},
            'gameServer': {'bindAddress': '127.0.0.1', 'bindPort': game},
            'internalMongoServer': {'address': '127.0.0.1', 'port': mongo, 'filePath': './data/database.mv'}}), 'utf-8')
        config_bytes = config.read_bytes()
        classes = directory / 'probe-classes'
        classes.mkdir()
        probes = [ROOT / 'scripts/fixtures/java/org/hkrpg/server/protocol/NativeMazeCastSequenceProbe.java',
            ROOT / 'scripts/fixtures/java/org/hkrpg/server/gameplay/MazeCastSequenceFixtureInspector.java']
        compiled = subprocess.run([str(java.with_name('javac.exe')), '-encoding', 'UTF-8', '-cp', str(jar),
            '-d', str(classes), *map(str, probes)], capture_output=True, creationflags=flags)
        (directory / 'javac.log').write_bytes(compiled.stdout + compiled.stderr)
        assert compiled.returncode == 0, directory / 'javac.log'
        options = ['-Dfile.encoding=UTF-8', '-Dstdout.encoding=UTF-8', '-Dstderr.encoding=UTF-8',
            f'-Djdk.net.unixdomain.tmpdir={directory / "tcp-fallback"}']
        classpath = str(classes) + os.pathsep + str(jar)
        for index, mode in enumerate(['sequence', 'new-session', 'restored']):
            assert sha(jar) == artifact
            verify_quote_closure(source, fixture, quote_copies)
            with (directory / f'server-{index}.log').open('wb') as log:
                process = subprocess.Popen([str(java), *options, '-Xmx1536m', '-jar', str(jar), '--no-handbook'],
                    cwd=directory, stdin=subprocess.PIPE, stdout=log, stderr=subprocess.STDOUT, creationflags=flags)
                ready = False
                try:
                    deadline = time.monotonic() + 100
                    while True:
                        assert process.poll() is None, directory / f'server-{index}.log'
                        try:
                            if local_request(http, '/health/ready')['ready']:
                                ready = True
                                break
                        except (OSError, urllib.error.URLError):
                            pass
                        if time.monotonic() > deadline:
                            raise TimeoutError(directory / f'server-{index}.log')
                        time.sleep(.2)
                    if not credentials:
                        login = local_request(http, '/hkrpg_cn/mdk/shield/api/login', dict(account='maze-cast-native', password='local-test'))
                        assert login['retcode'] == 0
                        account = login['data']['account']
                        combo = local_request(http, '/hkrpg_cn/combo/granter/login/v2/login',
                            dict(data=json.dumps(dict(uid=account['uid'], token=account['token']))))
                        assert combo['retcode'] == 0
                        credentials.update(accountUid=account['uid'], comboToken=combo['data']['combo_token'])
                    probe_log = directory / f'probe-{index}.log'
                    with probe_log.open('wb') as log_probe:
                        child = subprocess.Popen([str(java), *options, '-cp', classpath,
                            'org.hkrpg.server.protocol.NativeMazeCastSequenceProbe', str(game)], cwd=directory,
                            stdin=subprocess.PIPE, stdout=log_probe, stderr=subprocess.STDOUT, creationflags=flags)
                        child.stdin.write((json.dumps(dict(credentials, mode=mode, stage=stage['StageID'],
                            waves=[list(wave.values()) for wave in stage['MonsterList']])) + '\n').encode())
                        child.stdin.close()
                        deadline, granted = time.monotonic() + 240, False
                        try:
                            while child.poll() is None:
                                if mode == 'sequence' and not granted:
                                    marker = re.search(r'READY_UID=(\d+)', probe_log.read_text('utf-8'))
                                    if marker:
                                        uid = int(marker.group(1))
                                        assert uid == 10001, 'Only the single new isolated player may receive declared actors'
                                        process.stdin.write(f'give 1009 1510 @{uid}\n'.encode())
                                        process.stdin.flush()
                                        granted = True
                                if time.monotonic() > deadline:
                                    raise TimeoutError(probe_log)
                                time.sleep(.2)
                        finally:
                            if child.poll() is None:
                                child.terminate()
                                child.wait(timeout=15)
                    assert child.returncode == 0 and (mode != 'sequence' or granted), probe_log
                    snapshot = json.loads(next(row for row in reversed(probe_log.read_text('utf-8').splitlines()) if row.startswith('{')))
                    assert snapshot['passed']
                    credentials.setdefault('uid', snapshot['uid'])
                    assert credentials['uid'] == snapshot['uid']
                    snapshots.append(snapshot)
                finally:
                    stops += stop_owned_process(process, directory, ready)
            inspected = subprocess.run([str(java), *options, '-cp', classpath,
                'org.hkrpg.server.gameplay.MazeCastSequenceFixtureInspector', str(directory), mode,
                str(credentials['uid']), str(snapshot['mp'])], cwd=ROOT, capture_output=True,
                text=True, encoding='utf-8', timeout=120, creationflags=flags)
            inspection_log = directory / f'inspector-{index}.log'
            inspection_log.write_text(inspected.stdout + inspected.stderr, 'utf-8')
            assert inspected.returncode == 0, inspection_log
            inspection = json.loads(next(row for row in reversed(inspected.stdout.splitlines()) if row.startswith('{')))
            assert inspection['passed'] and inspection['uid'] == credentials['uid']
            inspections.append(inspection)
            print(json.dumps(dict(phase=mode, normalStops=stops, checks=len(snapshot['checks']))), flush=True)
        assert stops == 3 and sha(jar) == artifact and config.read_bytes() == config_bytes
        assert hashes == {name: sha(source / name) for name in hashes}
        verify_quote_closure(source, fixture, quote_copies)
        result = dict(passed=True, runtimeDirectory=str(directory), artifactSHA256=artifact,
            snapshots=snapshots, inspections=inspections, nativeRequests=sum(row['requests'] for row in snapshots),
            nativeChecks=sum(len(row['checks']) for row in snapshots), normalStops=stops,
            sameUIDNormalH2ProcessRestarts=2, sourceResourceHashes=hashes, fixtureSHA256=sha(proof_file),
            originalQuoteTablesPhysicallyCopied=quote_copies,
            fixture=dict(controlledSceneEntry=2000101, controlledGroup=91, consoleGrantedActors=[1009, 1510],
                originalAstaProjectileDeclaration=True, controlledReleaseHitEndMarkerLeaves=True,
                sourceNPCAndStageRowsUnchanged=True, sourceMPRecoveryBoxCopiedToTown=True,
                controlledRecoveryBoxInstances=[300001, 300002],
                originalRecoveryBoxTownPlacement=False, sourcePlayerBoyNormalAbilityUnchanged=True,
                missingStageEvent=199996001, useIDOrPaymentPrewritten=False),
            sourceResourcesModified=False, operatorDataModified=False, nativePrepaidUseIDSequenceVerified=True,
            clientGameplayVerified=False, officialRequestSequenceObserved=False,
            completeUnpaidHitPaymentSemanticsVerified=False, crashAtomicityVerified=False,
            wholeBattleComplete=False, wholeGameComplete=False)
        with output.open('x', encoding='utf-8') as report:
            report.write(json.dumps(result, ensure_ascii=False, indent=2) + '\n')
        print(json.dumps({key: result[key] for key in ['passed', 'nativeRequests', 'nativeChecks',
            'normalStops', 'artifactSHA256', 'runtimeDirectory']}, ensure_ascii=False))
    except BaseException as error:
        with (directory / 'failed-run.json').open('x', encoding='utf-8') as report:
            json.dump(dict(passed=False, error=repr(error), traceback=''.join(traceback.format_exception(error)),
                artifactSHA256=artifact, runtimeDirectory=str(directory), normalStops=stops,
                completedSnapshots=snapshots, inspections=inspections), report, ensure_ascii=False, indent=2)
        raise


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--jar', type=Path, default=ROOT / 'build/libs/hkrpg-server.jar')
    parser.add_argument('--output', type=Path, default=ROOT / 'build' / ('maze-cast-sequence-' + str(time.time_ns()) + '.json'))
    args = parser.parse_args()
    jar, output = args.jar.resolve(), args.output.resolve()
    assert jar.is_file() and jar.is_relative_to(ROOT / 'build') and output.is_relative_to(ROOT / 'build')
    reserve_output(jar, output)
    run(jar, output)


if __name__ == '__main__':
    main()
