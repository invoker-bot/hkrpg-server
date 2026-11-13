"""隔离 H2/KCP 验证 GM 商品上下架、拒绝旧购买请求、请求重放与正常重启；支持浏览器暂停。"""
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
spec = importlib.util.spec_from_file_location('gm', ROOT / 'scripts/smoke-gm-console.py')
gm = importlib.util.module_from_spec(spec)
spec.loader.exec_module(gm)


def varint(value):
    result = bytearray()
    while value > 127:
        result.append((value & 127) | 128)
        value >>= 7
    result.append(value)
    return bytes(result)


def wire(fields):
    return b''.join(varint(field * 8) + varint(value) for field, value in fields)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--browser-pause', action='store_true')
    parser.add_argument('--output', type=Path, default=ROOT / 'build/gm-shops-native.json')
    parser.add_argument('--jar', type=Path, default=ROOT / 'build/libs/hkrpg-server.jar')
    args = parser.parse_args()
    jar = args.jar.resolve()
    digest = hashlib.sha256(jar.read_bytes()).hexdigest()
    resource = ROOT / 'resources/4.6/ExcelOutput/ShopGoodsConfig.json'
    resource_digest = hashlib.sha256(resource.read_bytes()).hexdigest()
    java = next((ROOT / '.tools').glob('jdk-21*/bin/java.exe'))
    runs = ROOT / 'build/smoke'
    runs.mkdir(parents=True, exist_ok=True)
    directory = Path(tempfile.mkdtemp(prefix='gm-shops-', dir=runs))
    helpers = directory / 'helpers'
    helpers.mkdir()
    flags = subprocess.CREATE_NO_WINDOW if os.name == 'nt' else 0
    subprocess.run([str(java.with_name('javac.exe')), '-encoding', 'UTF-8', '-cp', str(jar), '-d', str(helpers),
                    str(ROOT / 'scripts/fixtures/java/org/hkrpg/server/protocol/NativeGmPlayerProbe.java')],
                   check=True, capture_output=True, creationflags=flags)
    http_port, mongo, game = gm.free_port(), gm.free_port(), gm.free_port(socket.SOCK_DGRAM)
    config = {'protocol': {'wireVersion': '4.6.0'}, 'resourceDir': str(ROOT / 'resources/4.6'),
              'httpServer': {'bindAddress': '127.0.0.1', 'bindPort': http_port},
              'gameServer': {'bindAddress': '127.0.0.1', 'bindPort': game},
              'internalMongoServer': {'address': '127.0.0.1', 'port': mongo, 'filePath': './data/database.mv'}}
    (directory / 'config.json').write_text(json.dumps(config), encoding='utf-8')
    base = f'http://127.0.0.1:{http_port}'
    opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
    csrf, uid, saved, old_unlist = '', None, None, None
    server = probe = None
    logs, operations, requests = [], [], []
    normal_stops = 0
    jflags = ['-Dfile.encoding=UTF-8', '-Dio.netty.eventLoopThreads=2',
              '-Djdk.net.unixdomain.tmpdir=' + str(directory / 'tcp-fallback')]
    account_name = 'gm_shops_' + uuid.uuid4().hex + '@example.invalid'
    targets = [{'shopId': 101, 'goodsId': 101001}, {'shopId': 102, 'goodsId': 102001}]

    def request(route, body=None):
        req = urllib.request.Request(base + route, data=None if body is None else json.dumps(body).encode(),
                                     headers={'Content-Type': 'application/json', 'Origin': base, 'X-GM-CSRF': csrf})
        with opener.open(req, timeout=30) as response:
            return json.load(response)

    def operation(action, **values):
        body = {'requestId': str(uuid.uuid4()), 'action': action, **values}
        result = request('/gm/api/operate', body)['data']
        assert result['state'] == 'completed', result
        operations.append(body)
        return body

    def snapshot():
        return json.loads((directory / 'snapshot.json').read_text('utf-8'))

    def send(name, fields, success=True):
        rid = str(uuid.uuid4())
        path = directory / 'equipment-request.json'
        temp = path.with_suffix('.tmp')
        temp.write_text(json.dumps({'requestId': rid, 'command': name,
                                    'wire': base64.b64encode(wire(fields)).decode(), 'success': success}), encoding='utf-8')
        temp.replace(path)
        data = gm.wait_for(lambda: (lambda s: s if s.get('equipmentReply', {}).get('requestId') == rid else None)(snapshot()), 40)
        path.unlink()
        response = data['equipmentReply']['response']
        assert (response.get('retcode', 0) == 0) == success, response
        requests.append({'command': name, 'success': success, 'response': response})
        return response, data

    def shop_rows():
        response, _ = send('GetShopListCsReq', [(12, 1)])
        return {g['goods_id']: g for shop in response.get('shop_list', []) for g in shop.get('goods_list', [])}

    def purchase(success):
        before = snapshot()
        response, after = send('BuyGoodsCsReq', [(1, 101), (3, 1), (9, 101001), (13, 101)], success)
        if not success:
            assert before['basic'] == after['basic'] and before['bag'] == after['bag'], 'Unlisted purchase changed balances'
        return response

    def stock(data, item_id):
        return next((row['num'] for row in data['bag'].get('material_list', []) if row['tid'] == item_id), 0)

    def stop(strict=True):
        nonlocal server, probe, normal_stops
        codes = []
        if probe:
            (directory / 'quit').touch()
            try:
                probe.wait(timeout=40)
            except subprocess.TimeoutExpired:
                probe.terminate()
                probe.wait(timeout=15)
            codes.append(probe.returncode == 0)
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
            codes.append(server.returncode == (1000 if os.name == 'nt' else 232))
            server.stdin.close()
            server = None
        if strict:
            assert all(codes), codes
            normal_stops += 1

    try:
        for cycle in range(2):
            log = (directory / f'server-{cycle}.log').open('w', encoding='utf-8')
            logs.append(log)
            server = subprocess.Popen([str(java), *jflags, '-Xmx1536m', '-jar', str(jar), '--no-handbook'],
                                      cwd=directory, stdin=subprocess.PIPE, stdout=log, stderr=subprocess.STDOUT, creationflags=flags)
            gm.wait_for(lambda: server.poll() is None and request('/health/ready')['ready'])
            csrf = request('/gm/api/session', {})['data']['csrf']
            assert request('/gm/api/players')['data'] == []
            if cycle == 0:
                catalog = request('/gm/api/shops?limit=100')['data']
                assert catalog['total'] == 903 and len(catalog['shops']) == 102, catalog['total']
                for goods_id, skin_id in [(107013, 1141501), (107015, 1150501), (107016, 1140901)]:
                    rows = request(f'/gm/api/shops?shopId=107&q={goods_id}')['data']['items']
                    assert len(rows) == 1 and rows[0]['itemId'] == skin_id and rows[0]['listed'], rows
                first = request('/gm/api/shops?shopId=101&q=101001')['data']['items'][0]
                assert first['name'] == '星轨通票' and first['costs'][0]['amount'] == 20, first
                old_unlist = operation('shop-unlist', shopGoods=targets)
            assert request('/gm/api/shops?state=unlisted')['data']['total'] == 2
            account = request('/hkrpg_cn/mdk/shield/api/login', {'account': account_name, 'password': 'disposable-gm-shops'})['data']['account']
            combo = request('/hkrpg_cn/combo/granter/login/v2/login', {'data': json.dumps({'uid': account['uid'], 'token': account['token']})})
            credentials = directory / 'credentials.json'
            credentials.write_text(json.dumps({'accountUid': account['uid'], 'comboToken': combo['data']['combo_token']}), encoding='utf-8')
            (directory / 'quit').unlink(missing_ok=True)
            (directory / 'snapshot.json').unlink(missing_ok=True)
            log = (directory / f'probe-{cycle}.log').open('w', encoding='utf-8')
            logs.append(log)
            probe = subprocess.Popen([str(java), *jflags, '-cp', str(helpers) + os.pathsep + str(jar),
                                      'org.hkrpg.server.protocol.NativeGmPlayerProbe', str(game), str(credentials), str(directory)],
                                     cwd=directory, stdout=log, stderr=subprocess.STDOUT, creationflags=flags)
            initial = gm.wait_for(snapshot)
            if uid is None:
                uid = initial['uid']
            assert uid == initial['uid']
            if saved:
                assert initial['basic'] == saved['basic'] and initial['bag'] == saved['bag']
            assert not {101001, 102001}.intersection(shop_rows())
            purchase(False)
            if cycle == 0:
                operation('grant', uid=uid, itemId=252, amount=40, level=0, rank=0)
                gm.wait_for(lambda: stock(snapshot(), 252) == 40)
                purchase(False)
            operation('shop-list', shopGoods=[targets[0]])
            assert request('/gm/api/operate', old_unlist)['data']['state'] == 'completed'
            assert 101001 in shop_rows(), 'Replaying an older unlist reversed the newer listing'
            response = purchase(True)
            assert response['goods_buy_times'] == cycle + 1
            gm.wait_for(lambda: stock(snapshot(), 101) == cycle + 1 and stock(snapshot(), 252) == 20 * (1 - cycle))
            assert shop_rows()[101001]['buy_times'] == cycle + 1
            if cycle == 0:
                old_unlist = operation('shop-unlist', shopGoods=[targets[0]])
            else:
                operation('shop-list', shopGoods=targets)
                before = (directory / 'data/ShopAvailability.json').read_bytes()
                invalid = request('/gm/api/operate', {'requestId': str(uuid.uuid4()), 'action': 'shop-unlist',
                    'shopGoods': [targets[0], {'shopId': 101, 'goodsId': 2147483647}]})['data']
                assert invalid['state'] == 'failed' and before == (directory / 'data/ShopAvailability.json').read_bytes()
                if args.browser_pause:
                    ready = {'base': base, 'runtimeDirectory': str(directory), 'uid': uid, 'jarSHA256': digest}
                    (ROOT / 'build/gm-shops-browser-ready.json').write_text(json.dumps(ready, indent=2), encoding='utf-8')
                    print(json.dumps({'browserReady': True, **ready}), flush=True)
                    gm.wait_for(lambda: (directory / 'continue').exists(), 2400)
                assert {101001, 102001}.issubset(shop_rows())
            saved = snapshot()
            stop()
        assert hashlib.sha256(jar.read_bytes()).hexdigest() == digest
        assert hashlib.sha256(resource.read_bytes()).hexdigest() == resource_digest
        args.output.parent.mkdir(exist_ok=True, parents=True)
        args.output.write_text(json.dumps({'passed': True, 'jarSHA256': digest, 'runtimeDirectory': str(directory),
            'uid': uid, 'normalStops': normal_stops, 'operations': len(operations), 'nativeRequests': len(requests),
            'unlistedPurchasePreservesBalances': True, 'sameUidRestart': True, 'oldUnlistReplayDoesNotReverseNewListing': True,
            'originalResourceUnchanged': True, 'productionModified': False, 'clientAccepted': False, 'requests': requests},
            ensure_ascii=False, indent=2), encoding='utf-8')
        print(json.dumps({'passed': True, 'nativeRequests': len(requests), 'normalStops': normal_stops, 'report': str(args.output)}))
    finally:
        stop(False)
        for log in logs:
            log.close()
        (directory / 'credentials.json').unlink(missing_ok=True)


if __name__ == '__main__':
    main()
