"""Verify original world offering 10 and its separate source-bound H2 journal."""
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
from smoke_resource_fixture import mirror_resources

ROOT = Path(__file__).resolve().parents[1]


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def port(kind=socket.SOCK_STREAM):
    with socket.socket(type=kind) as sock:
        sock.bind(('127.0.0.1', 0))
        return sock.getsockname()[1]


def main():
    jar = ROOT / 'build/libs/hkrpg-server.jar'
    java = next((ROOT / '.tools').glob('jdk-21*/bin/java.exe'))
    previous = json.loads((ROOT / 'build/krater-offering-native.json').read_text('utf-8'))
    original = Path(previous['runtimeDirectory']).resolve()
    assert previous['passed'] and previous['paidMaterials'] == 18800
    assert original.is_relative_to(ROOT / 'build/smoke')
    manifest = json.loads((original / 'resources-fixture/resource-manifest.json').read_text('utf-8'))
    assert manifest['kraterOfferingFixture'] and manifest['testFixture']
    source_hashes = {str(path): sha(path) for path in (original / 'data').rglob('*') if path.is_file()}
    golden = json.loads((ROOT / 'src/test/resources/fixtures/world-shop-offering-4.6.json').read_text('utf-8'))
    source_hashes.update({str(ROOT / 'resources/4.6' / path): sha(ROOT / 'resources/4.6' / path)
                          for path in golden['sourceFiles']})
    directory = Path(tempfile.mkdtemp(prefix='world-shop-offering-', dir=ROOT / 'build/smoke'))
    assert directory.resolve().is_relative_to(ROOT / 'build/smoke')
    shutil.copytree(original / 'data', directory / 'data')
    resources = ROOT / 'resources/4.6'
    krater = json.loads((ROOT / 'src/test/resources/fixtures/krater-offering-4.6.json').read_text('utf-8'))
    query = json.loads((ROOT / 'src/test/resources/fixtures/offering-query-4.6.json').read_text('utf-8'))
    paths = set(krater['sourceFiles']) | set(golden['sourceFiles']) | set(query['sourceFiles'])
    replacements = {path: json.loads((resources / path).read_text('utf-8')) for path in paths}
    floor = 'Config/LevelOutput/RuntimeFloor/P10402_F10402001.json'
    replacements[floor] = json.loads((resources / floor).read_text('utf-8'))
    fixture = directory / 'resources-fixture'
    bindings = mirror_resources(resources, fixture, replacements)
    flags = subprocess.CREATE_NO_WINDOW if os.name == 'nt' else 0
    if bindings:
        plan = directory / 'junctions.json'
        plan.write_text(json.dumps(bindings), 'utf-8')
        helper = directory / 'prepare.ps1'
        helper.write_text("param([string]$Plan)\n$ErrorActionPreference='Stop'\nforeach ($binding in (Get-Content -LiteralPath $Plan -Raw | ConvertFrom-Json)) { New-Item -ItemType Junction -Path $binding.target -Value $binding.source | Out-Null }\n", 'utf-8')
        subprocess.run(['powershell', '-NoProfile', '-NonInteractive', '-File', str(helper), '-Plan', str(plan)],
                       check=True, capture_output=True, creationflags=flags)
    # Mission folders are deliberately empty in mirror_resources; materialize only original required headers.
    for path in paths:
        if path.startswith('Config/Level/Mission/'):
            target = fixture / path
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes((resources / path).read_bytes())
    manifest['worldShopOfferingFixture'] = True
    (fixture / 'resource-manifest.json').write_text(json.dumps(manifest), 'utf-8')
    config = json.loads((original / 'config.json').read_text('utf-8'))
    http, mongo, game = port(), port(), port(socket.SOCK_DGRAM)
    config['httpServer'].update(bindPort=http)
    config['internalMongoServer'].update(port=mongo, filePath='./data/database.mv')
    config['gameServer'].update(bindPort=game)
    config['resourceDir'] = str(fixture)
    (directory / 'config.json').write_text(json.dumps(config), 'utf-8')
    classes = directory / 'probe-classes'
    classes.mkdir()
    helpers = [ROOT / 'scripts/fixtures/java/org/hkrpg/server/protocol/NativeWorldShopOfferingProbe.java',
               ROOT / 'scripts/fixtures/java/org/hkrpg/server/gameplay/WorldShopOfferingFixtureInspector.java']
    child = subprocess.run([str(java.with_name('javac.exe')), '-encoding', 'UTF-8', '-cp', str(jar),
                            '-d', str(classes), *map(str, helpers)], capture_output=True, creationflags=flags)
    (directory / 'javac.log').write_bytes(child.stdout + child.stderr)
    assert child.returncode == 0, directory / 'javac.log'
    options = ['-Dfile.encoding=UTF-8', f'-Djdk.net.unixdomain.tmpdir={directory / "tcp-fallback"}']
    cp = str(classes) + os.pathsep + str(jar)
    snapshots, stops = [], 0
    artifact = sha(jar)

    def request(route, body=None):
        req = urllib.request.Request(f'http://127.0.0.1:{http}' + route,
            data=None if body is None else json.dumps(body).encode(), headers={'Content-Type': 'application/json'})
        with urllib.request.urlopen(req, timeout=5) as response:
            return json.load(response)


    tables={Path(path).stem:rows for path,rows in golden['records'].items()}
    levels={row['Level']:row for row in tables['OfferingLevelConfig']}
    rewards={row['RewardID']:row for row in tables['RewardData']}
    assert sum(row['ItemCost'] for level,row in levels.items() if level<=50)==13500
    def progress(total,tails,taken):
        remaining,level=total,0
        while level<50 and remaining>=levels[level+1]['ItemCost']:
            level+=1;remaining-=levels[level]['ItemCost']
        return dict(total=total,level=level,exp=remaining-tails*100,taken=sorted(taken))
    def recipe(ids,tails):
        out={}
        for level in ids:
            row=rewards[levels[level]['RewardID']];quantity=tails if level==51 else 1
            if row.get('Hcoin'):out[1]=out.get(1,0)+row['Hcoin']*quantity
            for key,item in row.items():
                if key.startswith('ItemID_'):out[item]=out.get(item,0)+row['Count_'+key[7:]]*quantity
        return out
    uid=previous['snapshots'][-1]['uid']
    paid=tails=payments=claims=0
    taken=set();payout={};inspections=[]
    def inspect(mode):
        control=directory/f'inspector-{len(inspections)}.json'
        control.write_text(json.dumps(dict(uid=uid,progress=progress(paid,tails,taken),payments=payments,
            claims=claims,tails=tails,payout=payout,suppliedMaterials=paid)),'utf-8')
        child=subprocess.run([str(java),*options,'-cp',cp,'org.hkrpg.server.gameplay.WorldShopOfferingFixtureInspector',
            str(directory),str(control),mode],cwd=ROOT,capture_output=True,text=True,encoding='utf-8',timeout=100,creationflags=flags)
        path=directory/f'inspector-{len(inspections)}.log';path.write_text(child.stdout+child.stderr,'utf-8')
        assert child.returncode==0,path
        value=json.loads(next(line for line in reversed(child.stdout.splitlines()) if line.startswith('{')))
        assert value['passed'];inspections.append(value)
    inspect('seed')
    batches=[(0,[],0),(199,[],0),(1,[1],0),(13300,list(range(2,51)),0),(200,[51],2),(100,[51],1),(0,[],0)]
    for index,(give,claim_levels,tail_quantity) in enumerate(batches):
        after_taken=taken|{level for level in claim_levels if level<=50}
        control=dict(uid=uid,seed=False,give=give,levels=claim_levels,before=progress(paid,tails,taken),
            submitted=progress(paid+give,tails,taken),after=progress(paid+give,tails+tail_quantity,after_taken),
            rewards=recipe(claim_levels,tail_quantity))
        with (directory/f'server-{index}.log').open('wb') as log:
            server=subprocess.Popen([str(java),*options,'-Xmx1536m','-jar',str(jar),'--no-handbook'],
                cwd=directory,stdin=subprocess.PIPE,stdout=log,stderr=subprocess.STDOUT,creationflags=flags)
            try:
                deadline=time.monotonic()+100
                while True:
                    assert server.poll() is None,directory/f'server-{index}.log'
                    try:
                        if request('/health/ready')['ready']:break
                    except (OSError,urllib.error.URLError):pass
                    assert time.monotonic()<deadline,directory
                    time.sleep(.2)
                login=request('/hkrpg_cn/mdk/shield/api/login',dict(account='krater-offering-native',password='local-test'))
                assert login['retcode']==0;account=login['data']['account']
                combo=request('/hkrpg_cn/combo/granter/login/v2/login',dict(data=json.dumps(dict(uid=account['uid'],token=account['token']))))
                assert combo['retcode']==0
                control.update(accountUid=account['uid'],comboToken=combo['data']['combo_token'])
                path=directory/f'probe-{index}.log'
                with path.open('wb') as output:
                    child=subprocess.Popen([str(java),*options,'-cp',cp,'org.hkrpg.server.protocol.NativeWorldShopOfferingProbe',str(game)],
                        cwd=directory,stdin=subprocess.PIPE,stdout=output,stderr=subprocess.STDOUT,creationflags=flags)
                    child.stdin.write((json.dumps(control)+'\n').encode());child.stdin.close()
                    deadline,given=time.monotonic()+150,False
                    try:
                        while child.poll() is None:
                            text=path.read_text('utf-8')
                            if give and not given and f'READY_MATERIAL={uid}' in text:
                                server.stdin.write(f'give 120005 x{give} @{uid}\n'.encode());server.stdin.flush();given=True
                            assert time.monotonic()<deadline,path
                            time.sleep(.2)
                    finally:
                        if child.poll() is None:child.terminate();child.wait(timeout=15)
                assert child.returncode==0,path
                value=json.loads(next(line for line in reversed(path.read_text('utf-8').splitlines()) if line.startswith('{')))
                assert value['passed'] and value['uid']==uid and value['sourceOffering']==10
                snapshots.append(value)
                paid+=give;tails+=tail_quantity;taken=after_taken;payments+=bool(give);claims+=bool(claim_levels)
                for item,count in recipe(claim_levels,tail_quantity).items():payout[item]=payout.get(item,0)+count
            finally:
                if server.poll() is None:
                    try:
                        server.stdin.write(b'stop\n');server.stdin.flush();server.wait(timeout=45)
                        assert server.returncode==(1000 if os.name=='nt' else 232),directory
                        stops+=1
                    finally:
                        if server.poll() is None:server.terminate();server.wait(timeout=15)
                        server.stdin.close()
        inspect('inspect')
        print(json.dumps(dict(phase=index,paidMaterials=paid,level=progress(paid,tails,taken)['level'],
                              claimedSourceLevels=len(taken),tailQuantity=tails,normalStops=stops)),flush=True)
    assert paid==13800 and tails==3 and taken==set(range(1,51)) and payments==5 and claims==4 and stops==7
    assert sha(jar)==artifact
    for path,value in source_hashes.items():assert sha(Path(path))==value,path
    result=dict(passed=True,artifactSha256=artifact,runtimeDirectory=str(directory),previousRuntimeDirectory=str(original),
        snapshots=snapshots,inspections=inspections,nativeRequests=sum(v['nativeRequests'] for v in snapshots),
        rejectedRequests=sum(v['rejectedRequests'] for v in snapshots),paidMaterials=paid,normalCost=13500,
        claimedOriginalLevelRewards=sorted(taken),sourceLongTailQuantity=tails,paymentReceipts=payments,rewardReceipts=claims,
        actualPayout=payout,normalStops=stops,sameUIDNormalH2ProcessRestarts=6,retainedOfferingSixPaid=18800,
        retainedOfferingSixClaimedLevels=50,sourceResourcesModified=False,originalFixtureDatabaseModified=False,
        fullOriginalWorldShopFloorUsed=True,sourceMainPrerequisiteIsFixture=True,targetProgressPrewritten=False,
        operatorDataModified=False,clientGameplayVerified=False,wholeGameComplete=False)
    (ROOT/'build/world-shop-offering-native.json').write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n','utf-8')
    print(json.dumps({k:v for k,v in result.items() if k not in ['snapshots','inspections']}),flush=True)


if __name__=='__main__':main()
