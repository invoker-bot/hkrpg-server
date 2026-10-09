"""原生取得钟70并分阶段消费、转换9、重试及H2重启；仅修改独立临时资源夹具。"""
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
    directory = Path(tempfile.mkdtemp(prefix='rogue-clock-conversion-', dir=ROOT / 'build/smoke'))
    fixture = directory / 'resources-fixture'
    excel = fixture / 'ExcelOutput'
    excel.mkdir(parents=True)
    names = ['ExcelOutput/' + name + '.json' for name in ['ConstValueRogue', 'RogueRoom', 'MapEntrance', 'RogueMiracle', 'RogueMiracleEffect', 'RogueBuff', 'MazeBuff']]
    names += ['Config/LevelOutput/RuntimeFloor/P80201_F80201001.json']
    names += [f'Config/LevelOutput/SharedRuntimeGroup/Groups_P80201_F80201001/LevelGroup_P80201_F80201001_G{group}.json' for group in [35, 101, 117, 118]]
    names += [f'Config/Level/RogueDialogue/RogueDialogueEvent/Act/Rogue130/Act011000{n}.json' for n in [3, 4]]
    hashes = {name: hashlib.sha256((source / name).read_bytes()).hexdigest() for name in names}
    def rows(path):
        value = json.loads(path.read_text('utf-8-sig'))
        return list(value.values()) if isinstance(value, dict) else value
    original_miracles = rows(source / 'ExcelOutput/RogueMiracle.json')
    original_buffs = rows(source / 'ExcelOutput/RogueBuff.json')
    eligible = sorted([r for r in original_buffs if r.get('RogueVersion') == 1 and r.get('MazeBuffLevel') == 1 and r.get('RogueBuffCategory') == 'Legendary' and not r.get('UnlockIDList') and not r.get('ActivityModuleID') and r.get('BattleEventBuffType') == 'BattleEventBuff'], key=lambda r: r['MazeBuffID'])
    assert len(eligible) >= 6
    buff_rows = eligible[:6]
    bases = {r['MazeBuffID']: 250 for r in buff_rows}
    assert len(bases) == 6
    initial = 5000
    catalog = [2, 9, 12, 70]
    expanded = [2, 9, 12, 13, 19, 66, 70]
    original_coins = original_room = revival_base = None
    for p in (source / 'ExcelOutput').glob('*.json'):
        target = excel / p.name
        if p.name in ['ConstValueRogue.json', 'RogueRoom.json', 'RogueMiracle.json', 'RogueBuff.json']:
            value = json.loads(p.read_text('utf-8-sig'))
            values = list(value.values()) if isinstance(value, dict) else value
            if p.name == 'ConstValueRogue.json':
                row = next(r for r in values if r['ConstRogueName'] == 'Start_Rogue_Coin_Base')
                original_coins = row['ConstValue']
                row['ConstValue'] = str(initial)
                cost = next(r['ConstValue'] for r in values if r['ConstRogueName'] == 'Rogue_Recover_ItemCost')
                assert cost.startswith('[31:') and cost.endswith(']')
                revival_base = int(cost[4:-1])
            elif p.name == 'RogueRoom.json':
                room = next(r for r in values if r['RogueRoomID'] == 1000012)
                original_room = dict(room)
                room.update(MapEntrance=8020101, GroupID=1, GroupWithContent={str(g): 0 for g in [1, 35, 101, 117, 118]})
            elif p.name == 'RogueBuff.json':
                value = buff_rows
            else:
                value = [r for r in values if r['MiracleID'] in catalog]
                assert {r['MiracleID'] for r in value} == set(catalog)
            target.write_text(json.dumps(value, ensure_ascii=False), 'utf-8')
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
        assert data['passed'] and not data['clientGameplayVerified']
        assert data['clockConversionNetworkVerified'] == credentials.get('conversion', False)
        return data
    acquisition = []
    conversion = []
    credentials = None
    for iteration in range(3):
        if iteration == 1:
            # Only this private catalog changes between stopped servers; source rows stay intact.
            expanded_rows = [r for r in original_miracles if r['MiracleID'] in expanded]
            assert {r['MiracleID'] for r in expanded_rows} == set(expanded)
            (excel / 'RogueMiracle.json').write_text(json.dumps(expanded_rows, ensure_ascii=False), 'utf-8')
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
                    login = request('/hkrpg_cn/mdk/shield/api/login', dict(account='cn-46-clock-conversion', password='local-test'))
                    assert login['retcode'] == 0
                    account = login['data']['account']
                    combo = request('/hkrpg_cn/combo/granter/login/v2/login', dict(data=json.dumps(dict(uid=account['uid'], token=account['token']))))
                    assert combo['retcode'] == 0
                    credentials = dict(accountUid=account['uid'], comboToken=combo['data']['combo_token'], initialFragments=initial, basePrices=bases, passiveMerchantGroups=[101, 118])
                    first = probe('clock-acquisition.log', credentials)
                    assert first['nativeClockAcquired'] and first['nativeSurchargedBatchPurchased'] and first['currencyNotifications'] == 2
                    assert first['surcharge'] == 186 and first['fragments'] == 3864 and len(first['stock']) == 3
                    acquisition.append(first)
                    credentials.update(resume=True, stock=first['stock'], expectedFragments=first['fragments'])
                    acquisition.append(probe('clock-acquisition-resume.log', credentials))
                    credentials.update(conversion=True, resume=False, priorStock=first['stock'], revivalBase=revival_base)
                else:
                    if iteration == 1:
                        first = probe('clock-conversion.log', credentials)
                        assert first['nativeExpenseTriggeredClockConversion'] and first['replacementNotifications'] == 1 and first['revivalPriceNotifications'] == 1 and first['currencyNotifications'] == 4
                        assert first['fragments'] == 3177 and len(first['stock']) == 3 and len(first['miracleStock']) == 3
                        conversion.append(first)
                        credentials.update(resume=True, stock=first['stock'], miracleStock=first['miracleStock'], expectedFragments=first['fragments'])
                    restored = probe(f'clock-conversion-resume-{iteration}.log', credentials)
                    assert restored['savedConvertedStockWalletAndBlessingsRestored'] and restored['currencyNotifications'] == 0 and restored['replacementNotifications'] == 0
                    assert restored['stock'] == conversion[0]['stock'] and restored['miracleStock'] == conversion[0]['miracleStock'] and restored['fragments'] == 3177
                    conversion.append(restored)
                time.sleep(2)
            finally:
                if process.poll() is None:
                    process.terminate()
                    try:
                        process.wait(timeout=15)
                    except subprocess.TimeoutExpired:
                        process.kill()
                        process.wait(timeout=5)
    assert len(conversion) == 3 and len({s['uid'] for s in acquisition + conversion}) == 1
    assert hashes == {name: hashlib.sha256((source / name).read_bytes()).hexdigest() for name in names}
    checks = ['native-clock-acquisition-and-first-expense', 'saved-clock-counter-restored-before-more-expense', 'original-four-merchant-placements', 'native-three-surcharged-miracle-purchases', 'native-second-surcharged-blessing-batch', 'native-clock-70-to-9-replacement-once', 'native-999-reward-in-exact-wallet', 'native-base-revival-price-after-replacement', 'all-purchase-and-reacquisition-retries-rejected', 'pre-and-post-clock-quotes-remain-frozen', 'converted-stock-wallet-ownership-restored-on-reconnect', 'converted-stock-wallet-ownership-restored-after-h2-process-restart']
    print(json.dumps(dict(passed=True, runtimeDirectory=str(directory), checks=checks, acquisitionSnapshots=acquisition, snapshots=conversion, sourceSha256=hashes, sourceResourcesModified=False, fixture=dict(originalFirstSitePool=original_pool, firstSiteRoom=1000012, originalRoom=original_room, entrance=8020101, groups=[1, 35, 101, 117, 118], initialFragments=initial, originalInitialFragments=original_coins, initialMiracleCatalog=catalog, expandedMiracleCatalog=expanded, blessingCatalog=sorted(bases), revivalBase=revival_base, officialRoomGenerationVerified=False), calculatedExtraExpense=522, oneTimeReward=999, clockConversionNetworkVerified=True, clockRevivalChargeNetworkVerified=False, officialThresholdComparisonVerified=False, clientGameplayVerified=False), ensure_ascii=False))


if __name__ == '__main__':
    main()
