"""隔离常驻配置：真实 KCP 抽取、可选星琼补票、扣费、保底、H2 保存及同 UID 重启。"""
import argparse
import base64
import hashlib
import http.cookiejar
import importlib.util
import json
import os
from pathlib import Path
import socket
import subprocess
import tempfile
import urllib.request
import uuid

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('gm_smoke', ROOT / 'scripts/smoke-gm-console.py')
gm = importlib.util.module_from_spec(spec)
spec.loader.exec_module(gm)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--ticket-exchange', action='store_true', help='从一张票开始，验证星琼补票、十连、库存和重启保存')
    parser.add_argument('--output', type=Path, default=ROOT / 'build/gacha-rarity-native.json')
    args = parser.parse_args()
    jar = ROOT / 'build/libs/hkrpg-server.jar'
    digest = hashlib.sha256(jar.read_bytes()).hexdigest()
    java = next((ROOT / '.tools').glob('jdk-21*/bin/java.exe'))
    runs = ROOT / 'build/smoke'
    runs.mkdir(exist_ok=True)
    directory = Path(tempfile.mkdtemp(prefix='gacha-rarity-', dir=runs))
    helpers = directory / 'helpers'
    helpers.mkdir()
    flags = subprocess.CREATE_NO_WINDOW if os.name == 'nt' else 0
    sources = [ROOT / 'scripts/fixtures/java/org/hkrpg/server/protocol' / (name + '.java')
               for name in ('NativeGmPlayerProbe', 'GachaRarityFixtureInspector')]
    subprocess.run([str(java.with_name('javac.exe')), '-encoding', 'UTF-8', '-cp', str(jar), '-d', str(helpers),
                    *map(str, sources)], check=True, capture_output=True, creationflags=flags)
    http_port, mongo, game = gm.free_port(), gm.free_port(), gm.free_port(socket.SOCK_DGRAM)
    config = {'protocol': {'wireVersion': '4.6.0'}, 'resourceDir': str(ROOT / 'resources/4.6'),
              'httpServer': {'bindAddress': '127.0.0.1', 'bindPort': http_port},
              'gameServer': {'bindAddress': '127.0.0.1', 'bindPort': game},
              'internalMongoServer': {'address': '127.0.0.1', 'port': mongo, 'filePath': './data/database.mv'}}
    (directory / 'config.json').write_text(json.dumps(config), encoding='utf-8')
    base = f'http://127.0.0.1:{http_port}'
    java_flags = ['-Dfile.encoding=UTF-8', '-Dio.netty.eventLoopThreads=2',
                  '-Djdk.net.unixdomain.tmpdir=' + str(directory / 'tcp-fallback')]
    account_name = 'gacha_rarity_' + uuid.uuid4().hex + '@example.invalid'
    logs, pulls, exchanges = [], [], []
    server = probe = None
    uid = None
    csrf = ''
    opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))

    def request(route, body=None):
        req = urllib.request.Request(base + route, data=None if body is None else json.dumps(body).encode(),
                                     headers={'Content-Type': 'application/json', 'Origin': base, 'X-GM-CSRF': csrf})
        with opener.open(req, timeout=30) as response:
            return json.load(response)

    def stock(snapshot, item=101):
        return next((row['num'] for row in snapshot['bag'].get('material_list', []) if row['tid'] == item), 0)

    def snapshot():
        return json.loads((directory / 'snapshot.json').read_text(encoding='utf-8'))

    def operation(action, **values):
        value = request('/gm/api/operate', {'requestId': str(uuid.uuid4()), 'uid': uid, 'action': action, **values})['data']
        assert value['state'] == 'completed', value

    def stop(strict=True):
        nonlocal probe, server
        results = []
        if probe:
            (directory / 'quit').touch()
            try:
                probe.wait(timeout=40)
            except subprocess.TimeoutExpired:
                probe.terminate()
                probe.wait(timeout=15)
            results.append(probe.returncode == 0)
            probe = None
        if server:
            if server.poll() is None:
                server.stdin.write(b'stop\n')
                server.stdin.flush()
                try:
                    server.wait(timeout=45)
                except subprocess.TimeoutExpired:
                    server.terminate()
                    server.wait(timeout=15)
            results.append(server.returncode == (1000 if os.name == 'nt' else 232))
            server.stdin.close()
            server = None
        if strict:
            assert all(results), 'Owned probe/server did not stop normally'

    def inspect(action):
        result = subprocess.run([str(java), *java_flags, '-cp', str(helpers) + os.pathsep + str(jar),
            'org.hkrpg.server.protocol.GachaRarityFixtureInspector', action, str(directory)],
            cwd=ROOT, capture_output=True, timeout=90, creationflags=flags)
        (directory / f'inspect-{action}.log').write_bytes(result.stdout + result.stderr)
        assert result.returncode == 0, 'Owned H2 inspector failed'
        return json.loads(next(line.removeprefix('RARITY_FIXTURE=') for line in result.stdout.decode('utf-8').splitlines() if line.startswith('RARITY_FIXTURE=')))

    def pull(count):
        request_id = str(uuid.uuid4())
        target = directory / 'gacha-pull-request.json'
        temp = target.with_suffix('.tmp')
        temp.write_text(json.dumps({'requestId': request_id, 'count': count}), encoding='utf-8')
        temp.replace(target)
        result = gm.wait_for(lambda: (lambda data: data if data.get('gachaPull', {}).get('requestId') == request_id else None)(snapshot()), 40)
        target.unlink()
        response = result['gachaPull']['response']
        assert response['gacha_num'] == count and response['gacha_id'] == 1001
        rows = response['gacha_item_list']
        assert len(rows) == count
        won = [row['gacha_item']['item_id'] for row in rows]
        pulls.append({'count': count, 'items': won, 'ticketsAfter': stock(result)})
        return won, result

    def purchase(shop, goods, item, count, success=True):
        # Independent native 1505 wire; the bridge must map optional prop field 15 even when absent.
        def varint(value):
            out = bytearray()
            while value > 127:
                out.append((value & 127) | 128); value >>= 7
            out.append(value)
            return bytes(out)
        payload = b''.join(varint(field * 8) + varint(value) for field, value in
                           ((1, shop), (3, count), (9, goods), (13, item)))
        before = snapshot()
        key = str(uuid.uuid4())
        request_file = directory / 'equipment-request.json'
        temporary = request_file.with_suffix('.tmp')
        packet_id = 73597 + len(exchanges)
        temporary.write_text(json.dumps({'requestId': key, 'command': 'BuyGoodsCsReq', 'success': success,
                                        'packetId': packet_id,
                                        'wire': base64.b64encode(payload).decode()}), encoding='utf-8')
        temporary.replace(request_file)
        result = gm.wait_for(lambda: (lambda s: s if s.get('equipmentReply', {}).get('requestId') == key else None)(snapshot()), 40)
        request_file.unlink()
        reply = result['equipmentReply']['response']
        assert base64.b64decode(result['equipmentReply']['replyHeader']) == varint(8) + varint(packet_id)
        assert (reply.get('retcode', 0) == 0) == success
        assert result['gacha'] == before['gacha'], 'Ticket purchase changed pity before a draw'
        if success:
            assert result['basic']['hcoin'] == before['basic']['hcoin'] - count * 160
            assert stock(result, item) == stock(before, item) + count
            assert (reply['shop_id'], reply['goods_id'], reply['goods_buy_times']) == (shop, goods, count)
            assert sum(r['num'] for r in reply['return_item_list']['item_list'] if r['item_id'] == item) == count
        else:
            assert result['bag'] == before['bag'] and result['basic'] == before['basic']
        exchanges.append({'shop': shop, 'goods': goods, 'item': item, 'count': count, 'success': success,
                          'matchedReplyPacketId': packet_id,
                          'jadeAfter': result['basic']['hcoin'], 'stockAfter': stock(result, item), 'response': reply})
        return result

    rarity = {}
    for name in ('ItemConfigAvatar.json', 'ItemConfigEquipment.json'):
        rows = json.loads((ROOT / 'resources/4.6/ExcelOutput' / name).read_text(encoding='utf-8'))
        rows = rows if isinstance(rows, list) else rows.values()
        rarity.update({row['ID']: {'Rare': 3, 'VeryRare': 4, 'SuperRare': 5}.get(row['Rarity'], 0) for row in rows})
    try:
        seeded = after_draws = restored = None
        for iteration in range(3):
            log = (directory / f'server-{iteration}.log').open('w', encoding='utf-8')
            logs.append(log)
            server = subprocess.Popen([str(java), *java_flags, '-Xmx1536m', '-jar', str(jar), '--no-handbook'],
                cwd=directory, stdin=subprocess.PIPE, stdout=log, stderr=subprocess.STDOUT, creationflags=flags)
            gm.wait_for(lambda: server.poll() is None and request('/health/ready')['ready'])
            csrf = request('/gm/api/session', {})['data']['csrf']
            current = request('/gm/api/banners/current?limit=100')['data']['items']
            assert all(row['pullable'] for row in current), [(row['id'], row['poolIssues']) for row in current if not row['pullable']]
            for row in current:
                assert all(rarity[item['id']] == 5 for item in row['pool5'])
                assert all(rarity[item['id']] == 4 for item in row['pool4'])
            normal = next(row for row in current if row['id'] == 1001)
            assert len(normal['up5']) == 14 and len(normal['up4']) == 34
            account = request('/hkrpg_cn/mdk/shield/api/login', {'account': account_name, 'password': 'disposable-rarity-smoke'})['data']['account']
            combo = request('/hkrpg_cn/combo/granter/login/v2/login', {'data': json.dumps({'uid': account['uid'], 'token': account['token']})})
            credentials = directory / 'credentials.json'
            credentials.write_text(json.dumps({'accountUid': account['uid'], 'comboToken': combo['data']['combo_token']}), encoding='utf-8')
            (directory / 'quit').unlink(missing_ok=True)
            (directory / 'snapshot.json').unlink(missing_ok=True)
            log = (directory / f'probe-{iteration}.log').open('w', encoding='utf-8')
            logs.append(log)
            probe = subprocess.Popen([str(java), *java_flags, '-cp', str(helpers) + os.pathsep + str(jar),
                'org.hkrpg.server.protocol.NativeGmPlayerProbe', str(game), str(credentials), str(directory)],
                cwd=directory, stdout=log, stderr=subprocess.STDOUT, creationflags=flags)
            initial = gm.wait_for(snapshot)
            if uid is None:
                uid = initial['uid']
            assert initial['uid'] == uid
            if iteration == 0:
                original = (directory / 'data/Banners.json').read_bytes()
                tickets = 1 if args.ticket_exchange else 50
                operation('grant', itemId=101, amount=tickets, level=0, rank=0)
                gm.wait_for(lambda: stock(snapshot()) == tickets, 30)
                if args.ticket_exchange:
                    operation('grant', itemId=1, amount=3200, level=0, rank=0)
                    gm.wait_for(lambda: snapshot()['basic'].get('hcoin', 0) == 3200, 30)
                (directory / 'rarity-fixture.json').write_text(json.dumps({'testFixture': True, 'uid': uid}), encoding='utf-8')
                stop()
                seeded = inspect('seed')
                assert (seeded['pity5'], seeded['pity4'], seeded['failedFeatured'], seeded['tickets']) == (89,9,1,tickets)
            elif iteration == 1:
                if args.ticket_exchange:
                    purchase(999, 999001, 101, 9)
                    won5, first = pull(10)
                    assert stock(first) == 0
                    purchase(1000, 1000001, 101, 1)
                    won10, last = pull(1)
                    purchase(999, 999002, 102, 1)
                    last = purchase(1000, 1000002, 102, 1)
                    assert last['basic']['hcoin'] == 1280 and stock(last) == 0 and stock(last, 102) == 2
                    purchase(999, 999001, 101, 9, False)  # Insufficient jade.
                    purchase(999, 999001, 101, 0, False)
                    purchase(999, 999999, 101, 1, False)
                else:
                    won5, first = pull(1)
                    won10, last = pull(10)
                    assert stock(first) == 49 and stock(last) == 39
                assert rarity[won5[0]] == 5, 'Hard five-star pity awarded a lower rarity'
                assert any(rarity[i] == 4 for i in won5 + won10), 'Eleven pulls after four-star pity produced no four-star item'
                stop()
                after_draws = inspect('inspect')
                pity5, pity4 = 89, 9
                for item in won5 + won10:
                    if rarity[item] == 5: pity5, pity4 = 0, pity4 + 1
                    elif rarity[item] == 4: pity5, pity4 = pity5 + 1, 0
                    else: pity5, pity4 = pity5 + 1, pity4 + 1
                assert (after_draws['pity5'],after_draws['pity4']) == (pity5,pity4)
                assert after_draws['failedFeatured'] == 1 and after_draws['ceiling'] == seeded['ceiling'] + 11
            else:
                assert stock(initial) == (0 if args.ticket_exchange else 39)
                if args.ticket_exchange:
                    assert initial['basic']['hcoin'] == 1280 and stock(initial, 102) == 2
                    assert initial['avatars'] == last['avatars']
                    def inventory(s):
                        return {k: sorted(json.dumps({f: v for f, v in x.items() if f != 'unique_id'}, sort_keys=True) for x in rows)
                                for k, rows in s['bag'].items() if isinstance(rows, list)}
                    assert inventory(initial) == inventory(last), 'Rewards/tickets changed after restart'
                restored = initial
                stop()
                final = inspect('inspect')
                assert final == after_draws
            assert (directory / 'data/Banners.json').read_bytes() == original
        assert hashlib.sha256(jar.read_bytes()).hexdigest() == digest
        report = {'passed': True, 'jarSHA256': digest, 'runtimeDirectory': str(directory), 'uid': uid,
                  'normalStops': 3, 'actualPulls': 11, 'pulls': pulls, 'seeded': seeded, 'persisted': after_draws,
                  'actualPlayerReads': 6, 'sameUidAfterTwoRestarts': True, 'ticketCost': 11,
                  'bannerFileUnchanged': True, 'all53PoolsResourceChecked': True,
                  'restoredSnapshot': restored, 'operatorDataModified': False, 'clientGameplayVerified': False}
        report['ticketExchange'] = args.ticket_exchange
        report['exchanges'] = exchanges
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding='utf-8')
        print(json.dumps({k: report[k] for k in ('passed','actualPulls','normalStops','ticketCost','actualPlayerReads')}))
    finally:
        stop(strict=False)
        for log in logs:
            log.close()


if __name__ == '__main__':
    main()
