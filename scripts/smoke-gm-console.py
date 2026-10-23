"""隔离 H2、原始资源、真实 KCP 玩家：GM 发放/邮件/回显/去重/重启，支持浏览器验收暂停。"""
import argparse
import hashlib
import http.cookiejar
import json
import os
from pathlib import Path
import socket
import subprocess
import tempfile
import time
import urllib.error
import urllib.request
import uuid

ROOT = Path(__file__).resolve().parents[1]


def free_port(kind=socket.SOCK_STREAM):
    with socket.socket(type=kind) as sock:
        sock.bind(('127.0.0.1', 0))
        return sock.getsockname()[1]


def wait_for(check, seconds=180):
    deadline = time.monotonic() + seconds
    while time.monotonic() < deadline:
        try:
            value = check()
            if value:
                return value
        except (OSError, ValueError, urllib.error.URLError):
            pass
        time.sleep(.25)
    raise RuntimeError('GM smoke timed out')


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--browser-pause', action='store_true')
    args = parser.parse_args()
    jar = ROOT / 'build/libs/hkrpg-server.jar'
    digest = hashlib.sha256(jar.read_bytes()).hexdigest()
    java = next((ROOT / '.tools').glob('jdk-21*/bin/java.exe'))
    runs = ROOT / 'build/smoke'
    runs.mkdir(exist_ok=True)
    directory = Path(tempfile.mkdtemp(prefix='gm-console-', dir=runs))
    flags = subprocess.CREATE_NO_WINDOW if os.name == 'nt' else 0
    helpers = directory / 'helpers'
    helpers.mkdir()
    subprocess.run([str(java.with_name('javac.exe')), '-encoding', 'UTF-8', '-cp', str(jar), '-d', str(helpers),
                    str(ROOT / 'scripts/fixtures/java/org/hkrpg/server/protocol/NativeGmPlayerProbe.java')],
                   check=True, capture_output=True, creationflags=flags)
    http_port, mongo, game = free_port(), free_port(), free_port(socket.SOCK_DGRAM)
    config = {'protocol': {'wireVersion': '4.6.0'}, 'resourceDir': str(ROOT / 'resources/4.6'),
              'httpServer': {'bindAddress': '127.0.0.1', 'bindPort': http_port},
              'gameServer': {'bindAddress': '127.0.0.1', 'bindPort': game},
              'internalMongoServer': {'address': '127.0.0.1', 'port': mongo, 'filePath': './data/database.mv'}}
    (directory / 'config.json').write_text(json.dumps(config), encoding='utf-8')
    base = f'http://127.0.0.1:{http_port}'
    java_flags = ['-Dfile.encoding=UTF-8', '-Dio.netty.eventLoopThreads=2',
                  '-Djdk.net.unixdomain.tmpdir=' + str(directory / 'tcp-fallback')]
    cookies = http.cookiejar.CookieJar()
    opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(cookies))
    csrf = ''

    def request(route, body=None):
        req = urllib.request.Request(base + route, data=None if body is None else json.dumps(body, ensure_ascii=False).encode(),
                                     headers={'Content-Type': 'application/json', 'Origin': base, 'X-GM-CSRF': csrf})
        with opener.open(req, timeout=30) as response:
            return json.load(response)

    def login():
        nonlocal csrf
        key = (directory / 'data/gm-console.key').read_text(encoding='utf-8').strip()
        csrf = request('/gm/api/login', {'key': key})['data']['csrf']

    def operation(action, **values):
        body = {'requestId': str(uuid.uuid4()), 'action': action, 'uid': uid, **values}
        result = request('/gm/api/operate', body)['data']
        assert result['state'] in ('completed', 'returned'), result
        operations.append(body)
        return body

    server = probe = None
    logs = []
    operations, snapshots = [], []
    normal_stops = 0
    account_name = 'gm_smoke_' + uuid.uuid4().hex + '@example.invalid'
    uid = None
    banner_window = None
    credentials_file = directory / 'credentials.json'
    try:
        for iteration in range(2):
            log = (directory / f'server-{iteration}.log').open('w', encoding='utf-8')
            logs.append(log)
            server = subprocess.Popen([str(java), *java_flags, '-Xmx1536m', '-jar', str(jar), '--no-handbook'],
                                      cwd=directory, stdin=subprocess.PIPE, stdout=log, stderr=subprocess.STDOUT, creationflags=flags)
            wait_for(lambda: server.poll() is None and request('/health/ready')['ready'])
            login()
            if iteration == 0:
                assert request('/gm/api/players')['data'] == []
                history = request('/gm/api/banners/history?version=1.0&type=avatarup')['data']
                assert history['total'] == 2 and len(history['items']) == 2
                assert all(row['version'] == '1.0' and row['type'] == 'AvatarUp' for row in history['items'])
                operation('banner-rerun', uid=0, bannerKey='1.0-2001', durationDays=7)
                saved = json.loads((directory / 'data/Banners.json').read_text(encoding='utf-8'))
                row = next(row for row in saved if row['id'] == 2001)
                banner_window = (row['beginTime'], row['endTime'])
                assert banner_window[1] - banner_window[0] == 7 * 86400
            account = request('/hkrpg_cn/mdk/shield/api/login', {'account': account_name, 'password': 'isolated-gm-test'})['data']['account']
            combo = request('/hkrpg_cn/combo/granter/login/v2/login', {'data': json.dumps({'uid': account['uid'], 'token': account['token']})})
            credentials_file.write_text(json.dumps({'accountUid': account['uid'], 'comboToken': combo['data']['combo_token']}), encoding='utf-8')
            (directory / 'quit').unlink(missing_ok=True)
            (directory / 'snapshot.json').unlink(missing_ok=True)
            probe_log = (directory / f'probe-{iteration}.log').open('w', encoding='utf-8')
            logs.append(probe_log)
            probe = subprocess.Popen([str(java), *java_flags, '-cp', str(helpers) + os.pathsep + str(jar),
                                      'org.hkrpg.server.protocol.NativeGmPlayerProbe', str(game), str(credentials_file), str(directory)],
                                     stdout=probe_log, stderr=subprocess.STDOUT, creationflags=flags)
            snapshot = wait_for(lambda: json.loads((directory / 'snapshot.json').read_text(encoding='utf-8')))
            uid = snapshot['uid']
            native_banner = next(row for row in snapshot['gacha']['gacha_info_list'] if row['gacha_id'] == 2001)
            assert (native_banner['begin_time'], native_banner['end_time']) == banner_window
            assert native_banner['prize_item_list'] == [1102]
            assert native_banner['ONIHOMCELJK'] == [1105, 1106, 1109]
            assert len(request('/gm/api/players')['data']) == 1
            if iteration == 0:
                operation('grant', itemId=1, amount=37, level=0, rank=0)
                operation('grant', itemId=101, amount=37, level=0, rank=0)
                operation('grant', itemId=211, amount=7, level=0, rank=0)
                operation('grant', itemId=1001, amount=1, level=20, rank=2)
                operation('mail', title='GM 回归邮件', sender='GM', content='附件持久化验证', attachments=[{'itemId': 101, 'amount': 11}])
                operation('command', command='status -nogc')
                for op in operations:
                    assert request('/gm/api/operate', op)['data']['requestId'] == op['requestId']
                ready = {'base': base, 'runtimeDirectory': str(directory), 'uid': uid, 'jarSHA256': digest}
                (ROOT / 'build/gm-browser-ready.json').write_text(json.dumps(ready, indent=2), encoding='utf-8')
                print(json.dumps({'browserReady': True, **ready}), flush=True)
                if args.browser_pause:
                    wait_for(lambda: (directory / 'continue').exists(), 2400)
            else:
                for op in operations:
                    assert request('/gm/api/operate', op)['data']['requestId'] == op['requestId']
            snapshot = wait_for(lambda: (lambda data: data if data.get('basic', {}).get('hcoin') == 37 and any(m.get('title') == 'GM 回归邮件' for m in data.get('mail', {}).get('mail_list', [])) else None)(json.loads((directory / 'snapshot.json').read_text(encoding='utf-8'))), 30)
            assert any(row.get('base_avatar_id') == 1001 or row.get('avatar_id') == 1001 for row in snapshot['avatars'].get('avatar_list', [])), snapshot['avatars']
            assert next(row['num'] for row in snapshot['bag']['material_list'] if row['tid'] == 101) == 37
            assert next(row['num'] for row in snapshot['bag']['material_list'] if row['tid'] == 211) >= 7
            avatar = next(row for row in snapshot['avatars']['avatar_list'] if row.get('base_avatar_id') == 1001 or row.get('avatar_id') == 1001)
            path = next((row for row in snapshot['avatars'].get('avatar_path_data_info_list', []) if row.get('avatar_id') == 1001), {})
            assert avatar['level'] == 20 and path.get('rank', 0) == 2, {'avatar': avatar, 'path': path}
            snapshots.append(snapshot)
            (directory / 'quit').touch()
            probe.wait(timeout=40)
            assert probe.returncode == 0
            probe = None
            server.stdin.write(b'stop\n')
            server.stdin.flush()
            server.wait(timeout=45)
            assert server.returncode == (1000 if os.name == 'nt' else 232)
            server.stdin.close()
            server = None
            normal_stops += 1
        assert snapshots[0]['uid'] == snapshots[1]['uid']
        assert hashlib.sha256(jar.read_bytes()).hexdigest() == digest
        report = {'passed': True, 'jarSHA256': digest, 'runtimeDirectory': str(directory), 'normalStops': normal_stops,
                  'operations': len(operations), 'replayChecks': len(operations)*2, 'snapshots': snapshots,
                  'operatorDataModified': False, 'clientGameplayVerified': False}
        report['bannerWindow'] = banner_window
        report['bannerRerunWithoutOnlinePlayer'] = True
        report['bannerNativeQueryVerified'] = True
        (ROOT / 'build/gm-native.json').write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding='utf-8')
        print(json.dumps({'passed': True, 'normalStops': normal_stops, 'report': str(ROOT / 'build/gm-native.json')}))
    finally:
        if probe and probe.poll() is None:
            (directory / 'quit').touch()
            try:
                probe.wait(timeout=35)
            except subprocess.TimeoutExpired:
                probe.terminate()
                probe.wait(timeout=15)
        if server and server.poll() is None:
            server.stdin.write(b'stop\n')
            server.stdin.flush()
            try:
                server.wait(timeout=45)
            except subprocess.TimeoutExpired:
                server.terminate()
                server.wait(timeout=15)
            server.stdin.close()
        for log in logs:
            log.close()
        credentials_file.unlink(missing_ok=True)


if __name__ == '__main__':
    main()
