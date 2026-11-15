"""隔离账号验证零价商品查询、本地发放、防重及正常重启；不连接正式服务。"""
import argparse
import base64
import hashlib
from http.cookiejar import CookieJar
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
    parser.add_argument('--jar', type=Path, default=ROOT / 'build/libs/hkrpg-server.jar')
    parser.add_argument('--output', type=Path, default=ROOT / 'build/free-dream-shop-native.json')
    args = parser.parse_args()
    jar = args.jar.resolve()
    digest = hashlib.sha256(jar.read_bytes()).hexdigest()
    java = next((ROOT / '.tools').glob('jdk-21*/bin/java.exe'))
    runs = ROOT / 'build/smoke'
    runs.mkdir(parents=True, exist_ok=True)
    directory = Path(tempfile.mkdtemp(prefix='free-dream-shop-', dir=runs))
    helpers = directory / 'helpers'
    helpers.mkdir()
    flags = subprocess.CREATE_NO_WINDOW if os.name == 'nt' else 0
    subprocess.run([str(java.with_name('javac.exe')), '-encoding', 'UTF-8', '-cp', str(jar),
                    '-d', str(helpers), str(ROOT / 'scripts/fixtures/java/org/hkrpg/server/protocol/NativeGmPlayerProbe.java')],
                   check=True, capture_output=True, creationflags=flags)
    ports = [gm.free_port(), gm.free_port(), gm.free_port(socket.SOCK_DGRAM)]
    config = {'protocol': {'wireVersion': '4.6.0'}, 'resourceDir': str(ROOT / 'resources/4.6'),
              'httpServer': {'bindAddress': '127.0.0.1', 'bindPort': ports[0]},
              'gameServer': {'bindAddress': '127.0.0.1', 'bindPort': ports[2]},
              'internalMongoServer': {'address': '127.0.0.1', 'port': ports[1], 'filePath': './data/database.mv'}}
    (directory / 'config.json').write_text(json.dumps(config), encoding='utf-8')
    base = f'http://127.0.0.1:{ports[0]}'
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}),
                                          urllib.request.HTTPCookieProcessor(CookieJar()))
    account_name = 'free_shop_' + uuid.uuid4().hex + '@example.invalid'
    jflags = ['-Dfile.encoding=UTF-8', '-Dio.netty.eventLoopThreads=2',
              '-Djdk.net.unixdomain.tmpdir=' + str(directory / 'tcp-fallback')]
    logs, results = [], []
    server = probe = None
    uid = None

    def http(path, body=None):
        request = urllib.request.Request(base + path, data=None if body is None else json.dumps(body).encode(),
                                         headers={'Content-Type': 'application/json', 'Origin': base})
        with opener.open(request, timeout=30) as response:
            return json.load(response)

    def snapshot():
        return json.loads((directory / 'snapshot.json').read_text(encoding='utf-8'))

    def send(name, body=b'', success=True):
        rid = uuid.uuid4().hex
        target = directory / 'equipment-request.json'
        tmp = target.with_suffix('.tmp')
        tmp.write_text(json.dumps({'requestId': rid, 'command': name,
                                   'wire': base64.b64encode(body).decode(), 'success': success}), encoding='utf-8')
        tmp.replace(target)
        found = gm.wait_for(lambda: (lambda state: state if state.get('equipmentReply', {}).get('requestId') == rid
                                    else None)(snapshot()), 40)
        target.unlink()
        response = found['equipmentReply']['response']
        assert (response.get('retcode', 0) == 0) == success, (name, response)
        results.append(name)
        return response

    def stop(strict=True):
        nonlocal server, probe
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

    try:
        for cycle in range(2):
            output = (directory / f'server-{cycle}.log').open('w', encoding='utf-8')
            logs.append(output)
            server = subprocess.Popen([str(java), *jflags, '-Xmx1536m', '-jar', str(jar), '--no-handbook'],
                                      cwd=directory, stdin=subprocess.PIPE, stdout=output, stderr=subprocess.STDOUT,
                                      creationflags=flags)
            gm.wait_for(lambda: server.poll() is None and http('/health/ready')['ready'])
            account = http('/hkrpg_cn/mdk/shield/api/login',
                           {'account': account_name, 'password': 'isolated-free-shop'})['data']['account']
            combo = http('/hkrpg_cn/combo/granter/login/v2/login',
                         {'data': json.dumps({'uid': account['uid'], 'token': account['token']})})['data']['combo_token']
            credential = directory / 'credentials.json'
            credential.write_text(json.dumps({'accountUid': account['uid'], 'comboToken': combo}), encoding='utf-8')
            (directory / 'quit').unlink(missing_ok=True)
            (directory / 'snapshot.json').unlink(missing_ok=True)
            output = (directory / f'probe-{cycle}.log').open('w', encoding='utf-8')
            logs.append(output)
            probe = subprocess.Popen([str(java), *jflags, '-cp', str(helpers) + os.pathsep + str(jar),
                                      'org.hkrpg.server.protocol.NativeGmPlayerProbe', str(ports[2]),
                                      str(credential), str(directory)], cwd=directory, stdout=output,
                                     stderr=subprocess.STDOUT, creationflags=flags)
            initial = gm.wait_for(snapshot)
            uid = initial['uid'] if uid is None else uid
            assert uid == initial['uid']
            response = send('QueryProductInfoCsReq')
            products = response['product_list']
            assert len(products) == 6 and all(p['price_tier'] == 'Local_Free' for p in products), products
            gift_status = send('GetRechargeGiftInfoCsReq')
            assert gift_status.get('retcode', 0) == 0, gift_status
            price = http('/hkrpg_cn/mdk/shopwindow/shopwindow/listPriceTierV2')['data']['tiers']
            prices = {row['tier_id']: row['t_price'][0]['price'] for row in price}
            assert prices['Local_Free'] == '0' and prices['Tier_5'] == '3000' and prices['Tier_15'] == '9800'
            order = {'open_id': account['uid'], 'combo_token': combo, 'role_id': str(uid),
                     'request_id': 'isolated-order-one', 'product_id': 'rpgchncoin300tier5'}
            path = '/hkrpg_cn/mdk/luckycat/luckycat/createOrder'
            if cycle == 0:
                assert initial['basic'].get('mcoin', 0) == 0
                # The native Lua button sends the selected fixed product over
                # the authenticated KCP session, without invoking the SDK pay flow.
                product = b'rpgchncoin60tier1'
                native_claim = b'\x0a' + bytes([len(product)]) + product + b'\x10\x02'
                assert send('CancelAutoConversionCacheCsReq', native_claim).get('retcode', 0) == 0
                gm.wait_for(lambda: snapshot()['basic'].get('mcoin', 0) == 60, 30)
                assert send('CancelAutoConversionCacheCsReq', native_claim).get('retcode', 0) == 0
                assert snapshot()['basic'].get('mcoin', 0) == 60
                assert send('CancelAutoConversionCacheCsReq', b'\x0a\x0aLocal_Free\x10\x02').get('retcode', 0) == 0
                assert send('CancelAutoConversionCacheCsReq', native_claim).get('retcode', 0) == 0
                gm.wait_for(lambda: snapshot()['basic'].get('mcoin', 0) == 120, 30)
                assert send('CancelAutoConversionCacheCsReq', b'\x0a\x0aLocal_Free\x10\x02').get('retcode', 0) == 0
                for fields in ({'combo_token': 'invalid'}, {'role_id': '999999'},
                               {'product_id': 'rpgchnmonthcardtier5'}, {'request_id': 'bad id'}):
                    assert http(path, {**order, **fields})['retcode'] != 0
                assert http(path, order)['retcode'] == 0
                gm.wait_for(lambda: snapshot()['basic'].get('mcoin', 0) == 450, 30)
                assert http(path, order)['retcode'] == 0
                assert snapshot()['basic'].get('mcoin', 0) == 450
                send('CancelAutoConversionCacheCsReq', b'\x0a\x0aLocal_Free\x10\x02')
                without_id = {key: value for key, value in order.items() if key != 'request_id'}
                without_id['product_id'] = 'rpgchncoin60tier1'
                assert http(path, without_id)['retcode'] == 0
                gm.wait_for(lambda: snapshot()['basic'].get('mcoin', 0) == 510, 30)
                assert http(path, without_id)['retcode'] == 0
                assert snapshot()['basic'].get('mcoin', 0) == 510
                send('CancelAutoConversionCacheCsReq', b'\x0a\x0aLocal_Free\x10\x02')
            else:
                assert initial['basic'].get('mcoin', 0) == 510
                assert http(path, order)['retcode'] == 0
                assert snapshot()['basic'].get('mcoin', 0) == 510
                assert http(path, {**order, 'request_id': 'isolated-order-two',
                                   'product_id': 'rpgchncoin60tier1'})['retcode'] == 0
                gm.wait_for(lambda: snapshot()['basic'].get('mcoin', 0) == 570, 30)
                send('CancelAutoConversionCacheCsReq', b'\x0a\x0aLocal_Free\x10\x02')
            stop()
        assert hashlib.sha256(jar.read_bytes()).hexdigest() == digest
        args.output.parent.mkdir(parents=True, exist_ok=True)
        summary = {'passed': True, 'jarSHA256': digest, 'normalStops': 2,
                   'sameUidAfterRestart': True, 'clientGameplayVerified': False,
                   'nativeRequests': len(results), 'productionModified': False}
        args.output.write_text(json.dumps(summary, indent=2) + '\n', encoding='utf-8')
        print(json.dumps(summary))
    finally:
        stop(False)
        for log in logs:
            log.close()
        (directory / 'credentials.json').unlink(missing_ok=True)


if __name__ == '__main__':
    main()
