"""自有4.6 KCP命途请求、实际Player与正常H2恢复；隔离首房池与2/5/9/13个初始普通祝福及2500能量，不代表官方客户端UI。"""
import argparse
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

def free_port(kind=socket.SOCK_STREAM):
    with socket.socket(type=kind) as sock:
        sock.bind(('127.0.0.1', 0))
        return sock.getsockname()[1]

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--resources', type=Path, default=ROOT / 'resources/4.6')
    parser.add_argument('--recover-missing-menus', action='store_true',
                        help='明确种入已达阈值且无待选菜单的存档，检验重入恢复')
    args = parser.parse_args()
    source = args.resources.resolve()
    jar = ROOT / 'build/libs/hkrpg-server.jar'
    java = next((ROOT / '.tools').glob('jdk-21*/bin/java.exe'), None)
    if java is None and os.environ.get('JAVA_HOME'):
        java = Path(os.environ['JAVA_HOME']) / 'bin/java.exe'
    classes = ROOT / 'build/classes/java/test'
    assert java and java.is_file() and jar.is_file()
    assert (classes / 'org/hkrpg/server/gameplay/RogueResonanceFixtureInspector.class').is_file()
    digest = hashlib.sha256(jar.read_bytes()).hexdigest()
    runs = ROOT / 'build/smoke'
    runs.mkdir(exist_ok=True)
    directory = Path(tempfile.mkdtemp(prefix='rogue-resonance-', dir=runs))
    assert directory.resolve().is_relative_to(runs.resolve())
    fixture = directory / 'resources-fixture'
    guarded = ['resource-manifest.json'] + [p.relative_to(source).as_posix() for p in (source / 'ExcelOutput').glob('*.json')]
    hashes = {name: hashlib.sha256((source / name).read_bytes()).hexdigest() for name in guarded}
    aeons = json.loads((source / 'ExcelOutput/RogueAeon.json').read_text('utf-8'))
    assert [row['AeonID'] for row in aeons] == list(range(1,10))
    assert next(row for row in aeons if row['AeonID'] == 7).get('UnlockID',0) == 0
    flags = subprocess.CREATE_NO_WINDOW if os.name == 'nt' else 0
    links = mirror_resources(source, fixture, {})
    if links:
        plan = directory / 'junction-plan.json'
        plan.write_text(json.dumps(links),'utf-8')
        helper = directory / 'prepare-fixture.ps1'
        helper.write_text("param([string]$Plan)\n$ErrorActionPreference='Stop'\nforeach ($binding in (Get-Content -LiteralPath $Plan -Raw | ConvertFrom-Json)) { New-Item -ItemType Junction -Path $binding.target -Value $binding.source | Out-Null }\n",'utf-8')
        subprocess.run(['powershell','-NoProfile','-NonInteractive','-File',str(helper),'-Plan',str(plan)],check=True,capture_output=True,creationflags=flags)
    (fixture / 'resource-manifest.json').write_text(json.dumps({'clientVersion':'4.6.0','testFixture':True,'rogueResonanceFixture':True}),'utf-8')
    data = directory / 'data'
    data.mkdir()
    pools = json.loads((ROOT / 'src/main/resources/defaults/RogueMapGen.json').read_text('utf-8'))
    original_pool = pools['1']
    pools['1'] = [306]
    (data / 'RogueMapGen.json').write_text(json.dumps(pools),'utf-8')
    http, mongo, game = free_port(), free_port(), free_port(socket.SOCK_DGRAM)
    (directory / 'config.json').write_text(json.dumps({
        'protocol':{'wireVersion':'4.6.0'},'resourceDir':str(fixture),
        'httpServer':{'bindAddress':'127.0.0.1','bindPort':http},
        'gameServer':{'bindAddress':'127.0.0.1','bindPort':game},
        'internalMongoServer':{'address':'127.0.0.1','port':mongo,'filePath':'./data/database.mv'}}),'utf-8')
    probe_classes = directory / 'probe-classes'
    probe_classes.mkdir()
    probe_source = ROOT / 'scripts/fixtures/java/org/hkrpg/server/protocol/NativeRogueResonanceProbe.java'
    compilation = subprocess.run([str(java.with_name('javac.exe')),'-encoding','UTF-8','-cp',str(jar),'-d',str(probe_classes),str(probe_source)],capture_output=True,creationflags=flags)
    (directory / 'javac.log').write_bytes(compilation.stdout + compilation.stderr)
    assert compilation.returncode == 0, directory / 'javac.log'
    fallback = '-Djdk.net.unixdomain.tmpdir=' + str(directory / 'tcp-fallback')
    encoding = ['-Dfile.encoding=UTF-8','-Dstdout.encoding=UTF-8','-Dstderr.encoding=UTF-8']
    command = [str(java),*encoding,'-Xmx1536m',fallback,'-jar',str(jar),'--no-handbook']
    def request(route, body=None):
        req = urllib.request.Request(f'http://127.0.0.1:{http}' + route,
            data=None if body is None else json.dumps(body).encode(),headers={'Content-Type':'application/json'})
        with urllib.request.urlopen(req,timeout=5) as response:
            return json.load(response)
    snapshots, inspections, credentials, stops = [], [], None, 0
    def probe(mode, acquired):
        assert hashlib.sha256(jar.read_bytes()).hexdigest() == digest
        child = subprocess.run([str(java),*encoding,fallback,'-cp',str(jar) + os.pathsep + str(probe_classes),
            'org.hkrpg.server.protocol.NativeRogueResonanceProbe',str(game)],cwd=directory,
            input=json.dumps(dict(credentials,mode=mode,acquired=acquired)) + '\n',capture_output=True,
            text=True,encoding='utf-8',errors='replace',timeout=90,creationflags=flags)
        log = directory / f'probe-{len(snapshots)}-{mode}.log'
        log.write_text(child.stdout + child.stderr,'utf-8')
        assert child.returncode == 0, log
        snapshot = next(json.loads(line) for line in child.stdout.splitlines()
            if line.startswith('{') and json.loads(line).get('passed') is True)
        assert snapshot['passed'] and not snapshot['clientGameplayVerified']
        snapshots.append(snapshot)
    earned_mode = 'resume-earned' if args.recover_missing_menus else 'earn'
    for iteration, modes in enumerate([['setup'],[earned_mode],[earned_mode],[earned_mode],[earned_mode],['recover']]):
        with (directory / f'server-{iteration}.log').open('w',encoding='utf-8') as log:
            process = subprocess.Popen(command,cwd=directory,stdin=subprocess.PIPE,stdout=log,stderr=subprocess.STDOUT,creationflags=flags)
            try:
                deadline = time.monotonic() + 90
                while True:
                    assert process.poll() is None, directory
                    try:
                        if request('/health/ready')['ready']: break
                    except (OSError,urllib.error.URLError): pass
                    if time.monotonic() > deadline: raise RuntimeError('Startup timed out: ' + str(directory))
                    time.sleep(.2)
                if iteration == 0:
                    login = request('/hkrpg_cn/mdk/shield/api/login',{'account':'cn-46-earned-resonance','password':'local-test'})
                    assert login['retcode'] == 0
                    account = login['data']['account']
                    combo = request('/hkrpg_cn/combo/granter/login/v2/login',{'data':json.dumps({'uid':account['uid'],'token':account['token']})})
                    assert combo['retcode'] == 0
                    credentials = {'accountUid':account['uid'],'comboToken':combo['data']['combo_token']}
                for mode in modes: probe(mode,max(0,iteration-1))
                time.sleep(2)
            finally:
                if process.poll() is None:
                    try:
                        process.stdin.write(b'stop\n'); process.stdin.flush(); process.wait(timeout=30)
                        assert process.returncode == (1000 if os.name == 'nt' else 232), directory
                        stops += 1
                    except BaseException:
                        if process.poll() is None: process.terminate(); process.wait(timeout=15)
                        raise
                    finally: process.stdin.close()
        acquired = min(iteration,4)
        seed = [3,6,10,14,0,0][iteration]
        child = subprocess.run([str(java),*encoding,fallback,'-cp',str(classes) + os.pathsep + str(jar),
            'org.hkrpg.server.gameplay.RogueResonanceFixtureInspector',str(directory),str(snapshots[0]['uid']),str(acquired),str(seed),
            *(['missing-menu'] if args.recover_missing_menus else [])],
            cwd=ROOT,text=True,encoding='utf-8',capture_output=True,timeout=45,creationflags=flags)
        log = directory / f'inspector-{iteration}.log'
        log.write_text(child.stdout + child.stderr,'utf-8')
        assert child.returncode == 0, log
        inspection = json.loads(next(line for line in reversed(child.stdout.splitlines()) if line.startswith('{')))
        assert inspection['passed'] and inspection['changedRecords'] == (1 if seed else 0)
        inspections.append(inspection)
    assert stops == 6 and len(snapshots) == 6 and len(inspections) == 6
    assert len({r['uid'] for r in snapshots}) == 1
    assert len({r['runId'] for r in inspections}) == 1
    assert hashes == {name:hashlib.sha256((source / name).read_bytes()).hexdigest() for name in guarded}
    print(json.dumps({'passed':True,'directory':str(directory),'artifactSHA256':digest,'snapshots':snapshots,'inspections':inspections,
        'requests':sum(s['requests'] for s in snapshots),'checks':sum(len(s['checks']) for s in snapshots),
        'normalStops':stops,'sameUidH2Restarts':5,'actualPlayerReads':sum(r['actualPlayerReads'] for r in inspections),
        'sourceSha256':hashes,'sourceResourcesModified':False,'fixture':{'firstRoom':306,'originalFirstRoomPool':original_pool,'normalBlessingThresholdSeeds':[3,6,10,14],'reservedEnergySeed':2500,'missingEarnedMenuSeeds':args.recover_missing_menus},
        'completeResonance':False,'clientGameplayVerified':False}))

if __name__ == '__main__': main()
