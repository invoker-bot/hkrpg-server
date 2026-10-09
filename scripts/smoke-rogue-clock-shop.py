"""先原生购买公司咕咕钟，再向原始祝福商人批量消费；隔离房间/钱包，核对重连和H2重启。"""
from pathlib import Path
import hashlib
import json
import os
import socket
import subprocess
import tempfile
import time
import urllib.error
import urllib.request

ROOT = Path(__file__).resolve().parents[1]


def main():
    source = ROOT / 'resources/4.6'
    artifact = ROOT / 'build/libs/hkrpg-server.jar'
    java = next((ROOT / '.tools').glob('jdk-21*/bin/java.exe'), Path('java'))
    flags = subprocess.CREATE_NO_WINDOW if os.name == 'nt' else 0
    (ROOT / 'build/smoke').mkdir(parents=True, exist_ok=True)
    directory = Path(tempfile.mkdtemp(prefix='rogue-clock-expenditure-', dir=ROOT / 'build/smoke'))
    fixture = directory / 'resources-fixture'
    excel = fixture / 'ExcelOutput'
    excel.mkdir(parents=True)
    names = ['ExcelOutput/' + name + '.json' for name in ['ConstValueRogue', 'RogueRoom', 'MapEntrance', 'RogueMiracle', 'RogueMiracleEffect', 'RogueBuff', 'MazeBuff']]
    names += ['Config/LevelOutput/RuntimeFloor/P80201_F80201001.json']
    names += [f'Config/LevelOutput/SharedRuntimeGroup/Groups_P80201_F80201001/LevelGroup_P80201_F80201001_G{group}.json' for group in [35, 117]]
    names += [f'Config/Level/RogueDialogue/RogueDialogueEvent/Act/Rogue130/Act011000{n}.json' for n in [3, 4]]
    hashes = {name: hashlib.sha256((source / name).read_bytes()).hexdigest() for name in names}
    original_coins = original_room = None
    initial = 1500
    catalog = [2, 12, 70]
    for p in (source / 'ExcelOutput').glob('*.json'):
        target = excel / p.name
        if p.name in ['ConstValueRogue.json', 'RogueRoom.json', 'RogueMiracle.json']:
            rows = json.loads(p.read_text('utf-8-sig'))
            values = list(rows.values()) if isinstance(rows, dict) else rows
            if p.name == 'ConstValueRogue.json':
                row = next(r for r in values if r['ConstRogueName'] == 'Start_Rogue_Coin_Base')
                original_coins = row['ConstValue']
                row['ConstValue'] = str(initial)
            elif p.name == 'RogueRoom.json':
                room = next(r for r in values if r['RogueRoomID'] == 1000012)
                original_room = dict(room)
                room.update(MapEntrance=8020101, GroupID=1, GroupWithContent={'1': 0, '35': 0, '117': 0})
            else:
                rows = [r for r in values if r['MiracleID'] in catalog]
                assert {r['MiracleID'] for r in rows} == set(catalog)
            target.write_text(json.dumps(rows, ensure_ascii=False), 'utf-8')
        else:
            os.link(p, target)
    def literal(p):
        return "'" + str(p).replace("'", "''") + "'"
    if os.name == 'nt':
        subprocess.run(['powershell', '-NoProfile', '-NonInteractive', '-Command', 'New-Item -ItemType Junction -Path ' + literal(fixture / 'Config') + ' -Value ' + literal(source / 'Config') + ' | Out-Null'], check=True, capture_output=True, creationflags=flags)
    else:
        (fixture / 'Config').symlink_to(source / 'Config', target_is_directory=True)
    (fixture / 'resource-manifest.json').write_text(json.dumps(dict(clientVersion='4.6.0', testFixture=True)), 'utf-8')
    (directory / 'data').mkdir()
    pool = json.loads((ROOT / 'src/main/resources/defaults/RogueMapGen.json').read_text('utf-8-sig'))
    original_pool = pool['1']
    pool['1'] = [1000012]
    (directory / 'data/RogueMapGen.json').write_text(json.dumps(pool), 'utf-8')
    categories = {'Common': 100, 'Rare': 150, 'Legendary': 250}
    rows = json.loads((source / 'ExcelOutput/RogueBuff.json').read_text('utf-8-sig'))
    values = list(rows.values()) if isinstance(rows, dict) else rows
    bases = {r['MazeBuffID']: categories[r['RogueBuffCategory']] for r in values if r.get('MazeBuffLevel') == 1 and r.get('RogueVersion') == 1 and r.get('RogueBuffCategory') in categories}
    def free(kind=socket.SOCK_STREAM):
        with socket.socket(type=kind) as s:
            s.bind(('127.0.0.1', 0))
            return s.getsockname()[1]
    http, db, game = free(), free(), free(socket.SOCK_DGRAM)
    config = dict(protocol=dict(wireVersion='4.6.0'), resourceDir=str(fixture), httpServer=dict(bindAddress='127.0.0.1', bindPort=http), gameServer=dict(bindAddress='127.0.0.1', bindPort=game), internalMongoServer=dict(address='127.0.0.1', port=db, filePath='./data/database.mv'))
    (directory / 'config.json').write_text(json.dumps(config), 'utf-8')
    command = [str(java), '-Xmx1536m', '-Djdk.net.unixdomain.tmpdir=' + str(directory / 'tcp-fallback'), '-jar', str(artifact), '--no-console', '--no-handbook']
    def request(route, body=None):
        req = urllib.request.Request('http://127.0.0.1:' + str(http) + route, data=None if body is None else json.dumps(body).encode(), headers={'Content-Type': 'application/json'})
        with urllib.request.urlopen(req, timeout=5) as response:
            return json.load(response)
    def probe(name, credentials):
        r = subprocess.run([str(java), command[2], '-cp', str(artifact), 'org.hkrpg.server.protocol.LoopbackRogueClockShopProbe', str(game)], input=json.dumps(credentials), cwd=directory, capture_output=True, text=True, encoding='utf-8', errors='replace', timeout=35, creationflags=flags)
        (directory / name).write_text(r.stdout + r.stderr, 'utf-8')
        assert r.returncode == 0, str(directory / name)
        data = json.loads(next(line for line in r.stdout.splitlines() if line.startswith('{"uid":')))
        assert data['passed'] and not data['clientGameplayVerified'] and not data['clockConversionNetworkVerified']
        return data
    snapshots = []
    credentials = None
    for iteration in range(2):
        with (directory / f'server-{iteration}.log').open('w', encoding='utf-8') as log:
            process = subprocess.Popen(command, cwd=directory, stdin=subprocess.DEVNULL, stdout=log, stderr=subprocess.STDOUT, creationflags=flags)
            try:
                deadline = time.monotonic() + 90
                while True:
                    if process.poll() is not None:
                        raise RuntimeError('Server failed ' + str(directory))
                    try:
                        if request('/health/ready')['ready']:
                            break
                    except (OSError, urllib.error.URLError):
                        pass
                    if time.monotonic() > deadline:
                        raise RuntimeError('Server readiness timeout ' + str(directory))
                    time.sleep(.2)
                if iteration == 0:
                    login = request('/hkrpg_cn/mdk/shield/api/login', dict(account='cn-46-clock-expenditure', password='local-test'))
                    assert login['retcode'] == 0
                    account = login['data']['account']
                    combo = request('/hkrpg_cn/combo/granter/login/v2/login', dict(data=json.dumps(dict(uid=account['uid'], token=account['token']))))
                    assert combo['retcode'] == 0
                    credentials = dict(accountUid=account['uid'], comboToken=combo['data']['combo_token'], initialFragments=initial, basePrices=bases)
                    first = probe('clock-purchase-and-expenditure.log', credentials)
                    assert first['nativeClockAcquired'] and first['nativeSurchargedBatchPurchased'] and first['currencyNotifications'] == 2
                    assert first['surcharge'] == sum(bases[int(id)] // 4 for id in first['stock'])
                    assert first['fragments'] == initial - 200 - sum(first['stock'].values())
                    snapshots.append(first)
                    credentials.update(resume=True, stock=first['stock'], expectedFragments=first['fragments'])
                restored = probe(f'clock-resume-{iteration}.log', credentials)
                assert restored['savedClockStockWalletAndBuffsRestored'] and restored['uid'] == snapshots[0]['uid'] and restored['fragments'] == snapshots[0]['fragments'] and restored['stock'] == snapshots[0]['stock']
                snapshots.append(restored)
                time.sleep(2)
            finally:
                if process.poll() is None:
                    process.terminate()
                    try:
                        process.wait(timeout=15)
                    except subprocess.TimeoutExpired:
                        process.kill()
                        process.wait(timeout=5)
    assert hashes == {name: hashlib.sha256((source / name).read_bytes()).hexdigest() for name in names}
    checks = ['original-resource-merchants-present', 'native-clock-purchase-before-buff-quote', 'clock-surcharged-category-prices', 'native-surcharged-batch-purchase', 'clock-and-blessing-and-wallet-notifications', 'clock-and-batch-retries-rejected', 'pre-clock-catalog-price-keeps-frozen', 'clock-stock-wallet-blessings-restored-on-reconnect', 'clock-stock-wallet-blessings-restored-after-h2-process-restart']
    print(json.dumps(dict(passed=True, runtimeDirectory=str(directory), checks=checks, snapshots=snapshots, sourceSha256=hashes, sourceResourcesModified=False, fixture=dict(originalFirstSitePool=original_pool, firstSiteRoom=1000012, originalRoom=original_room, entrance=8020101, groups=[1, 35, 117], initialFragments=initial, originalInitialFragments=original_coins, miracleCatalog=catalog, officialRoomGenerationVerified=False), clockConversionNetworkVerified=False, clientGameplayVerified=False), ensure_ascii=False))


if __name__ == '__main__':
    main()
