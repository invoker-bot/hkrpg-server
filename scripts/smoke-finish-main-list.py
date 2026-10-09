"""原始支线对话推动原始主任务完成计数；仅接取前置使用隔离夹具。"""
import hashlib
import json
import os
from pathlib import Path
import shutil
import socket
import subprocess
import tempfile
import time
import urllib.error
import urllib.request
from importlib.machinery import SourceFileLoader
from smoke_resource_fixture import mirror_resources

ROOT = Path(__file__).resolve().parents[1]
free_port = SourceFileLoader('finish_main_ports', str(ROOT/'scripts/smoke-mail.py')).load_module().free_port


def main():
    source = ROOT/'resources/4.6'
    jar = ROOT/'build/libs/hkrpg-server.jar'
    java = next((ROOT/'.tools').glob('jdk-21*/bin/java.exe'), None)
    assert java and jar.is_file()
    digest = hashlib.sha256(jar.read_bytes()).hexdigest()
    runs = ROOT/'build/smoke'
    runs.mkdir(exist_ok=True)
    directory = Path(tempfile.mkdtemp(prefix='finish-main-list-', dir=runs))
    assert directory.resolve().is_relative_to(runs.resolve())
    fixture = directory/'resources-fixture'
    missions = [4040290, 4040204, 4040208]
    guarded = ['resource-manifest.json', 'ExcelOutput/MainMission.json', 'ExcelOutput/RewardData.json', 'ExcelOutput/MapEntrance.json', 'ExcelOutput/MazePlane.json']
    for main in missions:
        guarded += [p.relative_to(source).as_posix() for p in (source/f'Config/Level/Mission/{main}').rglob('*') if p.is_file()]
    hashes = {name: hashlib.sha256((source/name).read_bytes()).hexdigest() for name in guarded}
    assert hashes['resource-manifest.json'] == 'ecccb21469df76282e6df5bf0cbe1eff7d9f904c2dd644092124b6904482e735'
    rows = json.loads((source/'ExcelOutput/MainMission.json').read_text('utf-8'))
    for row in rows:
        if row['MainMissionID'] in missions:
            row.update(TakeParam=[{'Type': 'Manual'}], TakeOperation='And', BeginParam=[{'Type': 'Auto'}], BeginOperation='And')
    entrances = json.loads((source/'ExcelOutput/MapEntrance.json').read_text('utf-8'))
    for row in entrances:
        if row['ID'] in [1020101, 1020201]:
            row.update(BeginMainMissionList=[], FinishMainMissionList=[], FinishSubMissionList=[])
    junctions = mirror_resources(source, fixture, {'ExcelOutput/MainMission.json': rows, 'ExcelOutput/MapEntrance.json': entrances})
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
    command = [str(java), *java_flags, '-Xmx1536m', '-jar', str(jar), '--no-handbook']
    base = f'http://127.0.0.1:{http_port}'
    credentials = None
    snapshots = []
    stops = 0

    def request(route, body=None):
        req = urllib.request.Request(base+route, data=None if body is None else json.dumps(body).encode(), headers={'Content-Type': 'application/json'})
        with urllib.request.urlopen(req, timeout=5) as response:
            return json.load(response)

    def probe(phase):
        assert hashlib.sha256(jar.read_bytes()).hexdigest() == digest
        result = subprocess.run([str(java), *java_flags, '-cp', str(jar), 'org.hkrpg.server.protocol.LoopbackFinishMainListProbe', str(game_port)],
            input=json.dumps(dict(credentials, phase=phase)), text=True, encoding='utf-8', capture_output=True, timeout=60, creationflags=flags)
        (directory/f'probe-{len(snapshots)}.log').write_text(result.stdout+result.stderr, 'utf-8')
        assert result.returncode == 0, directory
        value = json.loads(next(line for line in reversed(result.stdout.splitlines()) if line.startswith('{')))
        assert value['passed']
        snapshots.append(value)
        credentials['initialMaterial'] = value['initialMaterial']

    for iteration, phases in enumerate([['first', 'resume'], ['second', 'final'], ['final']]):
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
                        raise RuntimeError(f'Finish-main readiness timeout: {directory}')
                    time.sleep(.2)
                if iteration == 0:
                    login = request('/hkrpg_cn/mdk/shield/api/login', {'account': 'cn-46-finish-main-list', 'password': 'local-test'})
                    assert login['retcode'] == 0
                    account = login['data']['account']
                    combo = request('/hkrpg_cn/combo/granter/login/v2/login', {'data': json.dumps({'uid': account['uid'], 'token': account['token']})})
                    assert combo['retcode'] == 0
                    credentials = {'accountUid': account['uid'], 'comboToken': combo['data']['combo_token']}
                for phase in phases:
                    probe(phase)
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
    assert len(snapshots) == 5 and stops == 3
    assert [r['count'] for r in snapshots] == [1, 1, 2, 2, 2]
    assert len({r['uid'] for r in snapshots}) == 1
    assert snapshots[0]['actualMaterials'] == snapshots[1]['actualMaterials']
    assert snapshots[2]['actualMaterials'] == snapshots[3]['actualMaterials'] == snapshots[4]['actualMaterials']
    assert hashes == {name: hashlib.sha256((source/name).read_bytes()).hexdigest() for name in guarded}
    assert hashlib.sha256(jar.read_bytes()).hexdigest() == digest
    print(json.dumps({'passed': True, 'runtimeDirectory': str(directory), 'snapshots': snapshots,
        'checks': [c for r in snapshots for c in r['checks']], 'normalStopCount': stops, 'normalStopCommandUsed': True,
        'rootJarSHA256': digest, 'sourceSHA256': hashes, 'sourceResourcesModified': False,
        'fixture': {'isolatedAcceptanceMissions': missions, 'isolatedEntranceGates': [1020101, 1020201], 'sourceMainGraphsUnmodified': True, 'mainRewardRowsUnmodified': True, 'finishedMainSeeded': False},
        'nativeThresholdExecutorVerified': False, 'wholeParentMessageChainVerified': False,
        'clientGameplayVerified': False, 'crashAtomicityVerified': False}, ensure_ascii=False))


if __name__ == '__main__':
    main()
