"""原始银河大乐透商人、实际资源物件三种抽奖、通知与正常 H2 恢复；房间、目录和起始碎片隔离。"""
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
    args = parser.parse_args()
    source = args.resources.resolve()
    jar = ROOT / 'build/libs/hkrpg-server.jar'
    java = next((ROOT / '.tools').glob('jdk-21*/bin/java.exe'), None)
    if java is None and os.environ.get('JAVA_HOME'):
        java = Path(os.environ['JAVA_HOME']) / 'bin/java.exe'
    classes = ROOT / 'build/classes/java/test'
    assert java and java.is_file() and jar.is_file()
    assert (classes / 'org/hkrpg/server/gameplay/RogueLotteryFixtureInspector.class').is_file()
    digest = hashlib.sha256(jar.read_bytes()).hexdigest()
    runs = ROOT / 'build/smoke'
    runs.mkdir(exist_ok=True)
    directory = Path(tempfile.mkdtemp(prefix='rogue-galactic-lottery-', dir=runs))
    assert directory.resolve().is_relative_to(runs.resolve())
    fixture = directory / 'resources-fixture'
    guarded = ['resource-manifest.json']
    guarded += [p.relative_to(source).as_posix() for p in (source / 'ExcelOutput').glob('*.json')]
    floor = 'Config/LevelOutput/RuntimeFloor/P80201_F80201001.json'
    guarded.append(floor)
    groups = source / 'Config/LevelOutput/SharedRuntimeGroup/Groups_P80201_F80201001'
    props_excel = {r['ID']:r for r in json.loads((source/'ExcelOutput/MazeProp.json').read_text('utf-8'))}
    selected_groups, props = {}, {}
    for p in groups.glob('*.json'):
        r=json.loads(p.read_text('utf-8'))
        if r.get('MonsterList') or r.get('NPCList'): continue
        chosen=[v for v in r.get('PropList',[]) if props_excel.get(v['PropID'],{}).get('PropType') in ['PROP_DESTRUCT','PROP_NO_REWARD_DESTRUCT']
            and v.get('LoadOnInitial',True) and not v.get('IsDelete') and not v.get('IsClientOnly')
            and 'HPRecover' not in props_excel[v['PropID']].get('JsonPath','') and 'MPRecover' not in props_excel[v['PropID']].get('JsonPath','')]
        if chosen:
            group=int(p.stem.rsplit('_G',1)[1]);selected_groups[str(group)]=0
            for v in chosen:props[f"{group}:{v['ID']}"]=v['PropID']
            guarded.append(p.relative_to(source).as_posix())
    assert len(props)>=70, 'Need bounded real source-prop choice coverage'
    for group in [1,35]:guarded.append(f'Config/LevelOutput/SharedRuntimeGroup/Groups_P80201_F80201001/LevelGroup_P80201_F80201001_G{group}.json')
    hashes={name:hashlib.sha256((source/name).read_bytes()).hexdigest() for name in guarded}
    proof=json.loads((ROOT/'src/test/resources/fixtures/rogue-galactic-lottery-4.6.json').read_text('utf-8'))
    originals={}
    for name,evidence in proof['files'].items():
        raw=(source/name).read_bytes()
        assert hashlib.sha256(raw).hexdigest()==evidence['resourceSHA256'],name
        originals[Path(name).name]=json.loads(raw)
    kinds={'miracle':'RogueMiracle.json','effect':'RogueMiracleEffect.json',
        'display':'RogueMiracleDisplay.json','pool':'RogueMiracleGroup.json'}
    for record in proof['records']:
        assert record['row'] in originals[kinds[record['kind']]],record['kind']
    rooms=json.loads((source/'ExcelOutput/RogueRoom.json').read_text('utf-8'))
    first=next(r for r in rooms if r['RogueRoomID']==1000012)
    original_room=dict(first)
    first.update(MapEntrance=8020101,GroupID=1,GroupWithContent={'1':0,'35':0,**selected_groups})
    const=json.loads((source/'ExcelOutput/ConstValueRogue.json').read_text('utf-8'))
    start=next(r for r in const if r['ConstRogueName']=='Start_Rogue_Coin_Base');original_coins=start['ConstValue'];start['ConstValue']='300'
    miracles=json.loads((source/'ExcelOutput/RogueMiracle.json').read_text('utf-8'))
    catalogue=[r for r in miracles if r['MiracleID'] in [2,12,63]]
    assert {r['MiracleID'] for r in catalogue}=={2,12,63}
    overrides={'ExcelOutput/RogueRoom.json':rooms,
        'ExcelOutput/ConstValueRogue.json':const,
        'ExcelOutput/RogueMiracle.json':catalogue}
    flags=subprocess.CREATE_NO_WINDOW if os.name=='nt' else 0
    links=mirror_resources(source,fixture,overrides)
    (fixture/'resource-manifest.json').write_text(json.dumps({'testFixture':True,'galacticLotteryFixture':True}), 'utf-8')
    if links:
        plan=directory/'junction-plan.json';plan.write_text(json.dumps(links),'utf-8')
        helper=directory/'prepare-fixture.ps1'
        helper.write_text("param([string]$Plan)\n$ErrorActionPreference='Stop'\nforeach ($binding in (Get-Content -LiteralPath $Plan -Raw | ConvertFrom-Json)) { New-Item -ItemType Junction -Path $binding.target -Value $binding.source | Out-Null }\n",'utf-8')
        subprocess.run(['powershell','-NoProfile','-NonInteractive','-File',str(helper),'-Plan',str(plan)],check=True,capture_output=True,creationflags=flags)
    (fixture/'resource-manifest.json').write_text(json.dumps({'clientVersion':'4.6.0','testFixture':True,'galacticLotteryFixture':True}),'utf-8')
    data=directory/'data';data.mkdir()
    pool=json.loads((ROOT/'src/main/resources/defaults/RogueMapGen.json').read_text('utf-8'))
    original_start_pool=list(pool['1']);pool['1']=[1000012]
    (data/'RogueMapGen.json').write_text(json.dumps(pool),'utf-8')
    http, mongo, game = free_port(), free_port(), free_port(socket.SOCK_DGRAM)
    (directory / 'config.json').write_text(json.dumps({
        'protocol': {'wireVersion': '4.6.0'}, 'resourceDir': str(fixture),
        'httpServer': {'bindAddress': '127.0.0.1', 'bindPort': http},
        'gameServer': {'bindAddress': '127.0.0.1', 'bindPort': game},
        'internalMongoServer': {'address': '127.0.0.1', 'port': mongo, 'filePath': './data/database.mv'}}), 'utf-8')
    probe_classes = directory / 'probe-classes'
    probe_classes.mkdir()
    probe_source = ROOT / 'scripts/fixtures/java/org/hkrpg/server/protocol/NativeRogueLotteryProbe.java'
    compilation = subprocess.run([str(java.with_name('javac.exe')), '-encoding', 'UTF-8', '-cp', str(jar),
                                  '-d', str(probe_classes), str(probe_source)], capture_output=True, creationflags=flags)
    (directory / 'javac.log').write_bytes(compilation.stdout + compilation.stderr)
    assert compilation.returncode == 0, directory / 'javac.log'
    fallback = '-Djdk.net.unixdomain.tmpdir=' + str(directory / 'tcp-fallback')
    encoding = ['-Dfile.encoding=UTF-8', '-Dstdout.encoding=UTF-8', '-Dstderr.encoding=UTF-8']
    command = [str(java), *encoding, '-Xmx1536m', fallback, '-jar', str(jar), '--no-handbook']

    def request(route, body=None):
        req = urllib.request.Request(f'http://127.0.0.1:{http}' + route,
            data=None if body is None else json.dumps(body).encode(), headers={'Content-Type': 'application/json'})
        with urllib.request.urlopen(req, timeout=5) as response:
            return json.load(response)

    snapshots, inspections, credentials, stops = [], [], None, 0
    def probe(mode):
        assert hashlib.sha256(jar.read_bytes()).hexdigest() == digest
        child = subprocess.run([str(java), *encoding, fallback, '-cp', str(jar) + os.pathsep + str(probe_classes),
            'org.hkrpg.server.protocol.NativeRogueLotteryProbe', str(game)], cwd=directory,
            input=json.dumps(dict(credentials, mode=mode)) + '\n', capture_output=True,
            text=True, encoding='utf-8', errors='replace', timeout=90, creationflags=flags)
        log = directory / f'probe-{len(snapshots)}-{mode}.log'
        log.write_text(child.stdout + child.stderr, 'utf-8')
        assert child.returncode == 0, log
        snapshot = json.loads(next(line for line in child.stdout.splitlines() if line.startswith('{"passed":')))
        assert snapshot['passed'] and not snapshot['clientGameplayVerified']
        snapshots.append(snapshot)

    for iteration, modes in enumerate([['buy'],['roll'],['resume']]):
        with (directory / f'server-{iteration}.log').open('w', encoding='utf-8') as log:
            process = subprocess.Popen(command, cwd=directory, stdin=subprocess.PIPE,
                stdout=log, stderr=subprocess.STDOUT, creationflags=flags)
            try:
                deadline = time.monotonic() + 90
                while True:
                    assert process.poll() is None, directory
                    try:
                        if request('/health/ready')['ready']:
                            break
                    except (OSError, urllib.error.URLError):
                        pass
                    if time.monotonic() > deadline:
                        raise RuntimeError('Startup timed out: ' + str(directory))
                    time.sleep(.2)
                if iteration == 0:
                    login = request('/hkrpg_cn/mdk/shield/api/login', {'account': 'cn-46-weakness-entry', 'password': 'local-test'})
                    assert login['retcode'] == 0
                    account = login['data']['account']
                    combo = request('/hkrpg_cn/combo/granter/login/v2/login', {
                        'data': json.dumps({'uid': account['uid'], 'token': account['token']})})
                    assert combo['retcode'] == 0
                    credentials = {'accountUid': account['uid'], 'comboToken': combo['data']['combo_token'], 'props':props}
                for mode in modes:
                    probe(mode)
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
        child = subprocess.run([str(java), *encoding, fallback, '-cp', str(classes) + os.pathsep + str(jar),
            'org.hkrpg.server.gameplay.RogueLotteryFixtureInspector', str(directory),
            str(snapshots[0]['uid']), ['buy','roll','resume'][iteration]], cwd=ROOT,
            text=True, encoding='utf-8', capture_output=True, timeout=45, creationflags=flags)
        log = directory / f'inspector-{iteration}.log'
        log.write_text(child.stdout + child.stderr, 'utf-8')
        assert child.returncode == 0, log
        inspection = json.loads(next(line for line in reversed(child.stdout.splitlines()) if line.startswith('{')))
        assert inspection['passed'] and inspection['changedPlayers'] == 0
        inspections.append(inspection)
        if iteration==0:
            run=inspection['run'];item=run['miracles']['63'];acquisition=item['acquisitionId']
            assert acquisition==item['lottery']['acquisition']
            rule=item['lottery']['rule'];chosen=[]
            for outcome in ['NONE','GAIN','BREAK']:
                for prop_key in sorted(props):
                    key=f"{run['siteId']}:{run['roomId']}:80201:80201001:{prop_key}"
                    ticket=(int.from_bytes(hashlib.sha256(f'{rule}:{acquisition}:{key}'.encode()).digest()[:4],'big')&0x7fffffff)%100
                    branch='GAIN' if ticket<5 else 'BREAK' if ticket<10 else 'NONE'
                    if branch==outcome:
                        chosen.append({'key':prop_key,'receiptKey':key,'outcome':outcome});break
                else: raise AssertionError('Earned random UUID did not supply all branches in bounded original prop set; no outcomes were seeded')
            credentials['chosen']=chosen

    assert stops==3 and len(snapshots)==3 and len(inspections)==3
    assert sum(r['requests'] for r in snapshots)==54 and sum(len(r['checks']) for r in snapshots)==30
    assert len({r['uid'] for r in snapshots})==1
    assert hashes=={name:hashlib.sha256((source/name).read_bytes()).hexdigest() for name in guarded}
    assert inspections[1]['run']==inspections[2]['run']
    report={'passed':True,'runtimeDirectory':str(directory),'rootJarSHA256':digest,
        'snapshots':snapshots,'inspections':inspections,'normalStops':stops,'sameUIDNormalH2ProcessRestarts':2,
        'sourceResourceHashes':hashes,'sourceResourcesModified':False,
        'completeOriginalRecordChecks':len(proof['records']),
        'fixture':{'originalRoom':original_room,'isolatedRoom':first,'originalStartRoomPool':original_start_pool,'startRoomPool':[1000012],'originalStartCoins':original_coins,
            'startCoins':300,'originalRecordCatalogue':[2,12,63],'authoredProps':len(props),
            'originalPoolEffectParametersFloorAndGroupFilesUnchanged':True,
            'acquisitionOutcomeReceiptOrHpSeeded':False},
        'checks':[c for r in snapshots for c in r['checks']],
        'officialOddsAndPoolVerified':False,'clientGameplayVerified':False,'crashAtomicityVerified':False}
    (directory/'report.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),'utf-8')
    print(json.dumps(report,ensure_ascii=False))


if __name__=='__main__': main()
