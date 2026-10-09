"""原生战斗取得待选菜单；正常关服后移除隔离资源、拒绝领取、恢复再领取及 H2 重启防重。"""
import hashlib
import json
import os
from pathlib import Path
import socket
import subprocess
import tempfile
import time
import urllib.error
import urllib.request
from smoke_resource_fixture import mirror_resources

ROOT = Path(__file__).resolve().parents[1]


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def port(kind=socket.SOCK_STREAM):
    with socket.socket(type=kind) as sock:
        sock.bind(('127.0.0.1', 0))
        return sock.getsockname()[1]


def main():
    source = ROOT / 'resources/4.6'
    jar = ROOT / 'build/libs/hkrpg-server.jar'
    java = next((ROOT / '.tools').glob('jdk-21*/bin/java.exe'), None)
    assert java and jar.is_file()
    artifact = sha(jar)
    runs = ROOT / 'build/smoke'
    runs.mkdir(exist_ok=True)
    directory = Path(tempfile.mkdtemp(prefix='blessing-selection-', dir=runs))
    assert directory.resolve().is_relative_to(runs.resolve())
    fixture = directory / 'resources-fixture'
    guarded = sorted(source.glob('ExcelOutput/*.json')) + [source / 'resource-manifest.json']
    guarded += [source / 'Config/LevelOutput/RuntimeFloor/P80001_F80001001.json']
    guarded += [source / f'Config/LevelOutput/SharedRuntimeGroup/Groups_P80001_F80001001/LevelGroup_P80001_F80001001_G{g}.json' for g in [3,4,5,10,11,12]]
    hashes = {p.relative_to(source).as_posix(): sha(p) for p in guarded}
    areas = json.loads((source / 'ExcelOutput/RogueAreaConfig.json').read_text('utf-8'))
    area = next(row for row in areas if row['RogueAreaID'] == 100)
    original = dict(area)
    # Select the original seven-site tutorial map 3; this is an explicit fixture mapping.
    assert area.get('AreaProgress', 0) == 0 and area['Difficulty'] == 1
    area['Difficulty'] = 3
    buffs = json.loads((source / 'ExcelOutput/RogueBuff.json').read_text('utf-8'))
    for row in buffs:
        row['UnlockIDList'] = [] if row['MazeBuffID'] == 612050 else [1999999999]
    maze = json.loads((source / 'ExcelOutput/RogueMazeBuff.json').read_text('utf-8'))
    missing = [row for row in maze if not (row['ID'] == 612050 and row['Lv'] == 1)]
    assert len(missing) == len(maze) - 1
    bindings = mirror_resources(source, fixture, {
        'ExcelOutput/RogueAreaConfig.json': areas,
        'ExcelOutput/RogueBuff.json': buffs,
        'ExcelOutput/RogueMazeBuff.json': maze})
    flags = subprocess.CREATE_NO_WINDOW if os.name == 'nt' else 0
    if bindings:
        plan = directory / 'junctions.json'
        plan.write_text(json.dumps(bindings), 'utf-8')
        helper = directory / 'prepare.ps1'
        helper.write_text("param([string]$Plan)\n$ErrorActionPreference='Stop'\nforeach ($binding in (Get-Content -LiteralPath $Plan -Raw | ConvertFrom-Json)) { New-Item -ItemType Junction -Path $binding.target -Value $binding.source | Out-Null }\n", 'utf-8')
        subprocess.run(['powershell','-NoProfile','-NonInteractive','-File',str(helper),'-Plan',str(plan)], check=True, capture_output=True, creationflags=flags)
    (fixture / 'resource-manifest.json').write_text(json.dumps({'clientVersion':'4.6.0','testFixture':True,'blessingSelectionFixture':True,'originalArea':original}), 'utf-8')
    data = directory / 'data'
    data.mkdir()
    sites = [row['SiteID'] for row in json.loads((source / 'ExcelOutput/RogueMap.json').read_text('utf-8')) if row['RogueMapID'] == 3]
    assert sites == list(range(1,8))
    (data / 'RogueMapGenByMap.json').write_text(json.dumps({'maps':{'3':{str(site):[100] for site in sites}}}), 'utf-8')
    http, mongo, game = port(), port(), port(socket.SOCK_DGRAM)
    config = directory / 'config.json'
    config.write_text(json.dumps({'protocol':{'wireVersion':'4.6.0'},'resourceDir':str(fixture),'httpServer':{'bindAddress':'127.0.0.1','bindPort':http},'gameServer':{'bindAddress':'127.0.0.1','bindPort':game},'internalMongoServer':{'address':'127.0.0.1','port':mongo,'filePath':'./data/database.mv'}}), 'utf-8')
    config_bytes = config.read_bytes()
    classes = directory / 'probe-classes'
    classes.mkdir()
    probe = ROOT / 'scripts/fixtures/java/org/hkrpg/server/protocol/NativeBlessingSelectionProbe.java'
    child = subprocess.run([str(java.with_name('javac.exe')),'-encoding','UTF-8','-cp',str(jar),'-d',str(classes),str(probe)], capture_output=True, creationflags=flags)
    (directory / 'javac.log').write_bytes(child.stdout + child.stderr)
    assert child.returncode == 0, directory / 'javac.log'
    java_flags = ['-Dfile.encoding=UTF-8','-Dstdout.encoding=UTF-8','-Dstderr.encoding=UTF-8',f'-Djdk.net.unixdomain.tmpdir={directory / "tcp-fallback"}']
    def request(route, body=None):
        req = urllib.request.Request(f'http://127.0.0.1:{http}' + route, data=None if body is None else json.dumps(body).encode(), headers={'Content-Type':'application/json'})
        with urllib.request.urlopen(req, timeout=5) as response:
            return json.load(response)
    credentials, snapshots = {}, []
    stops = 0
    for index, mode in enumerate(['earn','missing','restore','repeat']):
        # This replacement is inside the fresh, owned fixture and occurs only while stopped.
        (fixture / 'ExcelOutput/RogueMazeBuff.json').write_text(json.dumps(missing if mode == 'missing' else maze, ensure_ascii=False), 'utf-8')
        with (directory / f'server-{index}.log').open('wb') as log:
            process = subprocess.Popen([str(java),*java_flags,'-Xmx1536m','-jar',str(jar),'--no-handbook'], cwd=directory, stdin=subprocess.PIPE, stdout=log, stderr=subprocess.STDOUT, creationflags=flags)
            try:
                deadline = time.monotonic() + 100
                while True:
                    assert process.poll() is None, directory / f'server-{index}.log'
                    try:
                        if request('/health/ready')['ready']:
                            break
                    except (OSError, urllib.error.URLError):
                        pass
                    if time.monotonic() > deadline:
                        raise RuntimeError('Startup timed out: ' + str(directory))
                    time.sleep(.2)
                if not credentials:
                    login = request('/hkrpg_cn/mdk/shield/api/login', {'account':'blessing-selection-story','password':'local-test'})
                    assert login['retcode'] == 0
                    account = login['data']['account']
                    combo = request('/hkrpg_cn/combo/granter/login/v2/login', {'data':json.dumps({'uid':account['uid'],'token':account['token']})})
                    assert combo['retcode'] == 0
                    credentials.update(accountUid=account['uid'],comboToken=combo['data']['combo_token'])
                assert sha(jar) == artifact
                child = subprocess.run([str(java),*java_flags,'-cp',str(classes)+os.pathsep+str(jar),'org.hkrpg.server.protocol.NativeBlessingSelectionProbe',str(game)], cwd=directory, input=json.dumps(dict(credentials,mode=mode))+'\n', capture_output=True, text=True, encoding='utf-8', timeout=150, creationflags=flags)
                probe_log = directory / f'probe-{index}.log'
                probe_log.write_text(child.stdout + child.stderr, 'utf-8')
                assert child.returncode == 0, probe_log
                snapshot = json.loads(next(line for line in reversed(child.stdout.splitlines()) if line.startswith('{"')))
                assert snapshot['passed']
                if not snapshots:
                    credentials.update(uid=snapshot['uid'],batch=snapshot['batch'])
                    assert snapshot['actualVictories'] > 0 and snapshot['owned'] == {}
                else:
                    assert snapshot['actualVictories'] == 0 and snapshot['fragments'] == snapshots[0]['fragments']
                    assert snapshot['owned'] == ({} if mode == 'missing' else {'612050':1})
                assert snapshot['uid'] == credentials['uid'] and snapshot['batch'] == credentials['batch']
                snapshots.append(snapshot)
            finally:
                if process.poll() is None:
                    try:
                        process.stdin.write(b'stop\n')
                        process.stdin.flush()
                        process.wait(timeout=45)
                        assert process.returncode == (1000 if os.name == 'nt' else 232), directory
                        stops += 1
                    except BaseException:
                        if process.poll() is None:
                            process.terminate()
                            process.wait(timeout=15)
                        raise
                    finally:
                        process.stdin.close()
    assert stops == 4 and config.read_bytes() == config_bytes and all(sha(source / path) == value for path, value in hashes.items())
    report = {'passed':True,'artifactSha256':artifact,'runtimeDirectory':str(directory),'snapshots':snapshots,'normalStops':stops,'sameUIDNormalH2ProcessRestarts':3,'nativeRequests':sum(s['nativeRequests'] for s in snapshots),'actualVictories':snapshots[0]['actualVictories'],'targetMenuPrewritten':False,'blessingOwnershipPrewritten':False,'sourceResourceHashes':hashes,'sourceResourcesModified':False,'resourceRemovalInStoppedOwnedFixture':True,'originalArea':original,'clientGameplayVerified':False,'completeGameplayVerified':False,'combatInputReplayVerified':False}
    (directory / 'result.json').write_text(json.dumps(report, ensure_ascii=False, indent=2)+'\n', 'utf-8')
    print(json.dumps(report, ensure_ascii=False))


if __name__ == '__main__':
    main()
