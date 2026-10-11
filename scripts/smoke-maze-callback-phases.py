"""隔离KCP验证实际HIT/END、成功与失败OnBattle；不启动官方客户端。"""
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
import urllib.error
import urllib.request

ROOT = next(parent for parent in Path(__file__).resolve().parents
            if (parent / 'scripts/smoke_resource_fixture.py').is_file())
sys.path.insert(0, str(ROOT / 'scripts'))
from smoke_resource_fixture import mirror_resources


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def free_port(kind=socket.SOCK_STREAM):
    with socket.socket(type=kind) as sock:
        sock.bind(('127.0.0.1', 0))
        return sock.getsockname()[1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--jar', type=Path, default=ROOT / 'build/libs/hkrpg-server.jar')
    parser.add_argument('--output', type=Path, default=ROOT / 'build/maze-callback-phases-native.json')
    args = parser.parse_args()
    jar, output = args.jar.resolve(), args.output.resolve()
    assert jar.is_file() and jar.is_relative_to(ROOT / 'build')
    assert output.is_relative_to(ROOT / 'build')
    java = next((ROOT / '.tools').glob('jdk-21*/bin/java.exe'), None)
    if java is None and os.environ.get('JAVA_HOME'):
        java = Path(os.environ['JAVA_HOME']) / 'bin/java.exe'
    assert java and java.is_file()
    digest = sha(jar)
    source = ROOT / 'resources/4.6'
    proof = json.loads((ROOT / 'src/test/resources/fixtures/maze-callback-phases-4.6.json').read_text('utf-8'))
    assert proof['sourceCommit'] == '724b139d8c9c32d12552eb95745a4fee72bfe48b'
    for record in proof['records']:
        for name in ('avatar', 'ability'):
            assert sha(source / record[name + 'File']) == record[name + 'FileSHA256']
    floor_path = 'Config/LevelOutput/RuntimeFloor/P20001_F20001001.json'
    born_path = 'Config/LevelOutput/SharedRuntimeGroup/Groups_P20001_F20001001/LevelGroup_P20001_F20001001_G2.json'
    npc_path = 'Config/LevelOutput/SharedRuntimeGroup/Groups_P20001_F20001001/LevelGroup_P20001_F20001001_G91.json'
    template_path = 'Config/LevelOutput/SharedRuntimeGroup/Groups_P80001_F80001003/LevelGroup_P80001_F80001003_G18.json'
    floor = json.loads((source / floor_path).read_text('utf-8'))
    born = json.loads((source / born_path).read_text('utf-8'))
    template = json.loads((source / template_path).read_text('utf-8'))['MonsterList'][0]
    stage = next(row for row in json.loads((source / 'ExcelOutput/StageConfig.json').read_text('utf-8'))
                 if row['StageID'] == 80030231)
    events = json.loads((source / 'ExcelOutput/PlaneEvent.json').read_text('utf-8'))
    assert any(row['EventID'] == stage['StageID'] and row['StageID'] == stage['StageID'] for row in events)
    assert not any(row['EventID'] == 199996001 for row in events)
    assert not any(row['StageID'] == 1999960010 for row in json.loads((source / 'ExcelOutput/StageConfig.json').read_text('utf-8')))
    npcs = json.loads((source / 'ExcelOutput/NPCMonsterData.json').read_text('utf-8'))
    assert any(row['ID'] == 1002020 for row in npcs)
    npc_group = {'$type': 'RPG.GameCore.RtLevelGroupInfo', 'GroupName': 'MazeCallbackFixture',
                 'LoadSide': 'Server', 'LoadOnInitial': True, 'MonsterList': [], 'PropList': [], 'NPCList': []}
    for inst in range(200101, 200113):
        monster = copy.deepcopy(template)
        monster.update(ID=inst, NPCMonsterID=1002020, EventID=199996001 if inst == 200109 else stage['StageID'],
                       PosX=.1, PosY=.062052, PosZ=-4.8, IsClientOnly=False, LoadOnInitial=True)
        npc_group['MonsterList'].append(monster)
    floor['GroupInstanceList'] = [floor['GroupInstanceList'][0], {'GroupPath': npc_path, 'Name': 'MazeCallbackFixture', 'ID': 91}]
    dimension = copy.deepcopy(floor['DimensionList'][0])
    dimension['GroupIndexList'] = [0, 1]
    dimension['ExclusiveSavedGroupIDList'] = []
    floor['DimensionList'] = [dimension]
    floor['GroupInstanceCommonMap'] = {'2': floor['GroupInstanceCommonMap']['2'],
        '91': {'SaveType': 'Permanent', 'LoadSide': 'Server', 'LoadOnInitial': True}}
    asta_record = next(record for record in proof['records'] if record['avatarID'] == 1009)
    asta_path = asta_record['abilityFile']
    asta = json.loads((source / asta_path).read_text('utf-8'))
    ability = next(row for row in asta['AbilityList'] if row['Name'] == asta_record['ability']['Name'])
    projectile = copy.deepcopy(ability['OnStart'][1]['SuccessTaskList'][7])
    assert projectile['$type'] == 'RPG.GameCore.AdventureFireProjectile'
    assert projectile['Projectile']['Behavior'] == 'Static' and '$type' not in projectile['Projectile']

    def add(buff):
        return {'$type': 'RPG.GameCore.AddMazeBuff', 'ID': buff,
                'TargetType': {'$type': 'RPG.GameCore.TargetAlias', 'Alias': 'Caster'},
                'LifeTime': {'FixedValue': {'Value': 3600}}}

    def remove(buff):
        return {'$type': 'RPG.GameCore.RemoveMazeBuff', 'ID': buff,
                'TargetType': {'$type': 'RPG.GameCore.TargetAlias', 'Alias': 'Caster'}}

    projectile['OnProjectileHit'] = [remove(199998102), add(199998101)]
    projectile['OnProjectileLifetimeFinish'] = [remove(199998101), add(199998102)]
    ability['OnStart'] = [projectile]
    replacements = {floor_path: floor, born_path: born, npc_path: npc_group, asta_path: asta}
    guarded = set(source.glob('ExcelOutput/*.json')) | {source / 'resource-manifest.json',
        source / floor_path, source / born_path, source / template_path}
    guarded |= {source / record['abilityFile'] for record in proof['records']}
    hashes = {path.relative_to(source).as_posix(): sha(path) for path in sorted(guarded)}
    runs = ROOT / 'build/smoke'
    runs.mkdir(exist_ok=True)
    directory = Path(tempfile.mkdtemp(prefix='maze-callback-phases-', dir=runs))
    fixture = directory / 'resources-fixture'
    junctions = mirror_resources(source, fixture, replacements)
    # A new controlled group is local even when it has no original file to mirror.
    for relative, value in replacements.items():
        target = fixture / relative
        assert target.resolve().is_relative_to(fixture.resolve())
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(json.dumps(value, ensure_ascii=False), 'utf-8')
    flags = subprocess.CREATE_NO_WINDOW if os.name == 'nt' else 0
    if junctions:
        plan = directory / 'junctions.json'
        plan.write_text(json.dumps(junctions), 'utf-8')
        helper = directory / 'prepare.ps1'
        helper.write_text("param([string]$Plan)\n$ErrorActionPreference='Stop'\nforeach ($binding in (Get-Content -LiteralPath $Plan -Raw | ConvertFrom-Json)) { New-Item -ItemType Junction -Path $binding.target -Value $binding.source | Out-Null }\n", 'utf-8')
        subprocess.run(['powershell', '-NoProfile', '-NonInteractive', '-File', str(helper), '-Plan', str(plan)],
                       check=True, capture_output=True, creationflags=flags)
    (fixture / 'resource-manifest.json').write_text(json.dumps({'clientVersion': '4.6.0', 'testFixture': True,
        'mazeCallbackFixture': True, 'sourceCommit': proof['sourceCommit'],
        'controlledSceneTargetsAndAstaMarkerLeaves': True}), 'utf-8')
    (directory / 'data').mkdir()
    http, mongo, game = free_port(), free_port(), free_port(socket.SOCK_DGRAM)
    (directory / 'config.json').write_text(json.dumps({'protocol': {'wireVersion': '4.6.0'}, 'resourceDir': str(fixture),
        'httpServer': {'bindAddress': '127.0.0.1', 'bindPort': http},
        'gameServer': {'bindAddress': '127.0.0.1', 'bindPort': game},
        'internalMongoServer': {'address': '127.0.0.1', 'port': mongo, 'filePath': './data/database.mv'}}), 'utf-8')
    classes = directory / 'probe-classes'
    classes.mkdir()
    probe_source = Path(__file__).resolve().parent / 'fixtures/java/org/hkrpg/server/protocol/NativeMazeCallbackPhasesProbe.java'
    compiled = subprocess.run([str(java.with_name('javac.exe')), '-encoding', 'UTF-8', '-cp', str(jar),
        '-d', str(classes), str(probe_source)], capture_output=True, creationflags=flags)
    (directory / 'javac.log').write_bytes(compiled.stdout + compiled.stderr)
    assert compiled.returncode == 0, directory / 'javac.log'
    encoding = ['-Dfile.encoding=UTF-8', '-Dstdout.encoding=UTF-8', '-Dstderr.encoding=UTF-8']
    fallback = '-Djdk.net.unixdomain.tmpdir=' + str(directory / 'tcp-fallback')

    def request(route, body=None):
        value = urllib.request.Request(f'http://127.0.0.1:{http}' + route,
            data=None if body is None else json.dumps(body).encode(), headers={'Content-Type': 'application/json'})
        with urllib.request.urlopen(value, timeout=5) as response:
            return json.load(response)

    stops = 0
    with (directory / 'server.log').open('wb') as log:
        process = subprocess.Popen([str(java), *encoding, '-Xmx1536m', fallback, '-jar', str(jar), '--no-handbook'],
            cwd=directory, stdin=subprocess.PIPE, stdout=log, stderr=subprocess.STDOUT, creationflags=flags)
        try:
            deadline = time.monotonic() + 90
            while True:
                assert process.poll() is None, directory / 'server.log'
                try:
                    if request('/health/ready')['ready']:
                        break
                except (OSError, urllib.error.URLError):
                    pass
                if time.monotonic() > deadline:
                    raise TimeoutError(directory / 'server.log')
                time.sleep(.2)
            login = request('/hkrpg_cn/mdk/shield/api/login', {'account': 'maze-phase-native', 'password': 'local-test'})
            assert login['retcode'] == 0
            account = login['data']['account']
            combo = request('/hkrpg_cn/combo/granter/login/v2/login', {'data': json.dumps({'uid': account['uid'], 'token': account['token']})})
            assert combo['retcode'] == 0
            credentials = dict(accountUid=account['uid'], comboToken=combo['data']['combo_token'],
                stage=stage['StageID'], waves=[list(wave.values()) for wave in stage['MonsterList']])
            assert sha(jar) == digest
            probe_log = directory / 'probe.log'
            with probe_log.open('wb') as log_probe:
                child = subprocess.Popen([str(java), *encoding, fallback, '-cp', str(jar) + os.pathsep + str(classes),
                    'org.hkrpg.server.protocol.NativeMazeCallbackPhasesProbe', str(game)], cwd=directory,
                    stdin=subprocess.PIPE, stdout=log_probe, stderr=subprocess.STDOUT, creationflags=flags)
                child.stdin.write((json.dumps(credentials) + '\n').encode())
                child.stdin.close()
                granted = False
                deadline = time.monotonic() + 180
                try:
                    while child.poll() is None:
                        if not granted:
                            ready = re.search(r'READY_UID=(\d+)', probe_log.read_text('utf-8'))
                            if ready:
                                uid = int(ready.group(1))
                                assert uid == 10001, 'Only the new isolated account may receive fixture avatars'
                                process.stdin.write(f'give 1310 1009 @{uid}\n'.encode())
                                process.stdin.flush()
                                granted = True
                        if time.monotonic() > deadline:
                            raise TimeoutError(probe_log)
                        time.sleep(.2)
                finally:
                    if child.poll() is None:
                        child.terminate()
                        child.wait(timeout=15)
            assert child.returncode == 0 and granted, probe_log
            snapshot = json.loads(next(line for line in reversed(probe_log.read_text('utf-8').splitlines()) if line.startswith('{')))
            assert snapshot['passed'] and len(snapshot['battles']) == 11
        finally:
            if process.poll() is None:
                try:
                    process.stdin.write(b'stop\n')
                    process.stdin.flush()
                    process.wait(timeout=30)
                    assert process.returncode == (1000 if os.name == 'nt' else 232), directory
                    stops += 1
                finally:
                    if process.poll() is None:
                        process.terminate()
                        process.wait(timeout=15)
                    process.stdin.close()
    assert stops == 1 and sha(jar) == digest
    assert hashes == {name: sha(source / name) for name in hashes}
    result = dict(passed=True, runtimeDirectory=str(directory), rootJarSHA256=digest,
        snapshot=snapshot, normalStops=stops, sourceResourceHashes=hashes, sourceResourcesModified=False,
        fixture=dict(controlledSceneEntry=2000101, controlledGroup=91, consoleGrantedAvatars=[1310, 1009],
            sourcePlayerBoy30AndFireflyAbilitiesUnchanged=True, controlledAstaMarkerLeaves=True,
            sourceNPCAndStageRowsUnchanged=True, missingStageEvent=199996001,
            unitTestAlsoChecksNPCFailureTempBuffs=True), clientGameplayVerified=False,
        multiPacketTechniqueMPVerified=False, activeBattleRecoveryVerified=False, crashAtomicityVerified=False)
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n', 'utf-8')
    print(json.dumps({key: result[key] for key in ['passed', 'runtimeDirectory', 'rootJarSHA256', 'normalStops']} |
        dict(requests=snapshot['requests'], checks=len(snapshot['checks']), battles=len(snapshot['battles']),
             clientGameplayVerified=False), ensure_ascii=False))


if __name__ == '__main__':
    main()
