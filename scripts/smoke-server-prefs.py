"""独立账号与临时 H2：4.6 已读偏好保存、重复请求、空值清除及两次正常重启。"""
import argparse
import base64
import hashlib
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


def varint(value):
    data = bytearray()
    while value > 127:
        data.append((value & 127) | 128)
        value >>= 7
    data.append(value)
    return bytes(data)


def update_wire(key, data):
    # Independent pinned native fields: ServerPrefs (id=3, bytes=13), update message=15.
    nested = b'\x18' + varint(key) + b'\x6a' + varint(len(data)) + data
    return b'\x7a' + varint(len(nested)) + nested


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, default=ROOT / 'build/server-prefs-native.json')
    args = parser.parse_args()
    jar = ROOT / 'build/libs/hkrpg-server.jar'
    digest = hashlib.sha256(jar.read_bytes()).hexdigest()
    java = next((ROOT / '.tools').glob('jdk-21*/bin/java.exe'))
    (ROOT / 'build/smoke').mkdir(exist_ok=True)
    directory = Path(tempfile.mkdtemp(prefix='server-prefs-', dir=ROOT / 'build/smoke'))
    helpers = directory / 'helpers'
    helpers.mkdir()
    flags = subprocess.CREATE_NO_WINDOW if os.name == 'nt' else 0
    source = ROOT / 'scripts/fixtures/java/org/hkrpg/server/protocol/NativeGmPlayerProbe.java'
    subprocess.run([str(java.with_name('javac.exe')), '-encoding', 'UTF-8', '-cp', str(jar),
                    '-d', str(helpers), str(source)], check=True, capture_output=True, creationflags=flags)
    http, mongo, game = gm.free_port(), gm.free_port(), gm.free_port(socket.SOCK_DGRAM)
    config = {'protocol': {'wireVersion': '4.6.0'}, 'resourceDir': str(ROOT / 'resources/4.6'),
              'httpServer': {'bindAddress': '127.0.0.1', 'bindPort': http},
              'gameServer': {'bindAddress': '127.0.0.1', 'bindPort': game},
              'internalMongoServer': {'address': '127.0.0.1', 'port': mongo, 'filePath': './data/database.mv'}}
    (directory / 'config.json').write_text(json.dumps(config), encoding='utf-8')
    java_flags = ['-Dfile.encoding=UTF-8', '-Dio.netty.eventLoopThreads=2',
                  '-Djdk.net.unixdomain.tmpdir=' + str(directory / 'tcp-fallback')]
    account_name = 'prefs_' + uuid.uuid4().hex + '@example.invalid'
    logs, results = [], []
    server = probe = None
    uid = None
    expected = {}
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))

    def request(route, body=None):
        req = urllib.request.Request(f'http://127.0.0.1:{http}' + route,
            data=None if body is None else json.dumps(body).encode(), headers={'Content-Type': 'application/json'})
        with opener.open(req, timeout=30) as response:
            return json.load(response)

    def snapshot():
        return json.loads((directory / 'snapshot.json').read_text(encoding='utf-8'))

    def call(command, wire=b'', success=True):
        before = snapshot()
        key = str(uuid.uuid4())
        path = directory / 'equipment-request.json'
        temporary = path.with_suffix('.tmp')
        temporary.write_text(json.dumps({'requestId': key, 'command': command, 'success': success,
            'wire': base64.b64encode(wire).decode()}), encoding='utf-8')
        temporary.replace(path)
        def reply():
            if probe.poll() is not None:
                raise ValueError('Owned probe exited; inspect ' + str(directory))
            value = snapshot()
            return value if value.get('equipmentReply', {}).get('requestId') == key else None
        after = gm.wait_for(reply, 40)
        path.unlink()
        response = after['equipmentReply']['response']
        assert (response.get('retcode', 0) == 0) == success
        for field in ('bag', 'gacha', 'avatars', 'lineup'):
            if field in before:
                assert before[field] == after[field], 'Preference request changed ' + field
        results.append({'command': command, 'success': success})
        return response

    def check_all():
        rows = call('GetAllServerPrefsDataCsReq').get('server_prefs_list', [])
        actual = {r['server_prefs_id']: base64.b64decode(r.get('data', '')) for r in rows}
        assert len(actual) == len(rows) and actual == expected

    def stop(strict=True):
        nonlocal probe, server
        stopped = []
        if probe:
            (directory / 'quit').touch()
            try:
                probe.wait(timeout=40)
            except subprocess.TimeoutExpired:
                probe.terminate()
                probe.wait(timeout=15)
            stopped.append(probe.returncode == 0)
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
            stopped.append(server.returncode == (1000 if os.name == 'nt' else 232))
            server.stdin.close()
            server = None
        if strict:
            assert all(stopped), 'Owned test processes must stop normally'

    try:
        for iteration in range(3):
            log = (directory / f'server-{iteration}.log').open('w', encoding='utf-8')
            logs.append(log)
            server = subprocess.Popen([str(java), *java_flags, '-Xmx1536m', '-jar', str(jar), '--no-handbook'],
                cwd=directory, stdin=subprocess.PIPE, stdout=log, stderr=subprocess.STDOUT, creationflags=flags)
            gm.wait_for(lambda: server.poll() is None and request('/health/ready')['ready'])
            account = request('/hkrpg_cn/mdk/shield/api/login', {'account': account_name, 'password': 'disposable-prefs-smoke'})['data']['account']
            combo = request('/hkrpg_cn/combo/granter/login/v2/login', {'data': json.dumps({'uid': account['uid'], 'token': account['token']})})
            credentials = directory / 'credentials.json'
            credentials.write_text(json.dumps({'accountUid': account['uid'], 'comboToken': combo['data']['combo_token']}), encoding='utf-8')
            for name in ('quit', 'snapshot.json'):
                (directory / name).unlink(missing_ok=True)
            log = (directory / f'probe-{iteration}.log').open('w', encoding='utf-8')
            logs.append(log)
            probe = subprocess.Popen([str(java), *java_flags, '-cp', str(helpers) + os.pathsep + str(jar),
                'org.hkrpg.server.protocol.NativeGmPlayerProbe', str(game), str(credentials), str(directory)],
                cwd=directory, stdout=log, stderr=subprocess.STDOUT, creationflags=flags)
            initial = gm.wait_for(snapshot)
            if uid is None:
                uid = initial['uid']
            assert initial['uid'] == uid
            check_all()
            if iteration == 0:
                for key, data in ((2, b'\0\xff\x03\0\x80'), (3202, b'[]')):
                    for retry in range(2):
                        assert call('UpdateServerPrefsCsReq', update_wire(key, data))['server_prefs_id'] == key
                    expected[key] = data
                check_all()
                call('UpdateServerPrefsCsReq', b'\x7a\x01\xff', False)
                check_all()
            elif iteration == 1:
                row = call('GetServerPrefsDataCsReq', b'\x10' + varint(2))['server_prefs']
                assert row['server_prefs_id'] == 2 and base64.b64decode(row['data']) == expected[2]
                assert call('UpdateServerPrefsCsReq', update_wire(2, b''))['server_prefs_id'] == 2
                expected[2] = b''
                check_all()
            else:
                row = call('GetServerPrefsDataCsReq', b'\x10' + varint(2))['server_prefs']
                assert row['server_prefs_id'] == 2 and not row.get('data')
            stop()
        assert hashlib.sha256(jar.read_bytes()).hexdigest() == digest
        args.output.parent.mkdir(parents=True, exist_ok=True)
        proof = {'passed': True, 'jarSHA256': digest, 'directory': str(directory), 'uid': uid,
                 'sessions': 3, 'normalStops': 3, 'h2Restarts': 2, 'preferenceRequests': results,
                 'clientUiVerified': False, 'productionAccountTouched': False}
        args.output.write_text(json.dumps(proof, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
        print(json.dumps(proof, ensure_ascii=True))
    finally:
        stop(False)
        for log in logs:
            log.close()


if __name__ == '__main__':
    main()
