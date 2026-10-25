"""隔离 H2 / KCP：按已核对的客户端字段号验证全部历史卡池展示，零抽取、零运营写入。"""
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


def wire_fields(data):
    """Independent protobuf framing: do not derive display semantics from descriptor names."""
    index = 0
    def varint():
        nonlocal index
        value = 0
        for shift in range(0, 70, 7):
            assert index < len(data), 'Truncated varint'
            byte = data[index]
            index += 1
            value |= (byte & 127) << shift
            if not byte & 128:
                return value
        raise AssertionError('Invalid varint')
    fields = {}
    while index < len(data):
        tag = varint()
        number, kind = tag >> 3, tag & 7
        assert number > 0
        if kind == 0:
            value = varint()
        elif kind in (1, 2, 5):
            size = varint() if kind == 2 else (8 if kind == 1 else 4)
            assert index + size <= len(data), 'Truncated field'
            value = data[index:index + size]
            index += size
        else:
            raise AssertionError(f'Unexpected wire type {kind}')
        fields.setdefault(number, []).append(value)
    return fields


def ids(fields, number):
    result = []
    for value in fields.get(number, []):
        if isinstance(value, int):
            result.append(value)
        else:
            # Decode packed uint32s with a synthetic field-1 varint tag.
            index = 0
            while index < len(value):
                end = index
                while end < len(value) and value[end] & 128:
                    end += 1
                assert end < len(value)
                result.extend(wire_fields(b'\x08' + value[index:end + 1])[1])
                index = end + 1
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, default=ROOT / 'build/gacha-banner-display-native.json')
    args = parser.parse_args()
    jar = ROOT / 'build/libs/hkrpg-server.jar'
    digest = hashlib.sha256(jar.read_bytes()).hexdigest()
    history = json.loads((ROOT / 'src/main/resources/gm/banner-history.json').read_text('utf-8'))['banners']
    banners = [{k: row[k] for k in ('id', 'gachaType', 'rateUpItems5', 'rateUpItems4')}
               | {'beginTime': 1, 'endTime': 2147483647} for row in history if row['replayable']]
    assert len(banners) == len({row['id'] for row in banners}) == 278
    normal = next(row for row in json.loads((ROOT / 'src/main/resources/defaults/Banners.json').read_text('utf-8'))
                  if row['id'] == 1001)
    banners.extend([normal, dict(normal, id=4001, gachaType='Newbie')])
    expected = {row['id']: (row['rateUpItems5'], row['rateUpItems4']) for row in banners if row['id'] not in (1001, 4001)}
    for number in (1001, 4001):
        expected[number] = (normal['rateUpItems4'], normal['rateUpItems4'])
    # None of these should reach the client, even with plausible-looking preview values.
    banners.extend([dict(banners[0], id=999991, disabled=True),
                    dict(banners[0], id=999992, endTime=2),
                    dict(banners[0], id=999993, beginTime=2147483646),
                    dict(banners[0], id=999994, rateUpItems5=[1105])])
    runs = ROOT / 'build/smoke'
    runs.mkdir(exist_ok=True)
    directory = Path(tempfile.mkdtemp(prefix='gacha-display-', dir=runs))
    (directory / 'data').mkdir()
    banner_file = directory / 'data/Banners.json'
    banner_file.write_text(json.dumps(banners), encoding='utf-8')
    original = banner_file.read_bytes()
    helpers = directory / 'helpers'
    helpers.mkdir()
    java = next((ROOT / '.tools').glob('jdk-21*/bin/java.exe'))
    flags = subprocess.CREATE_NO_WINDOW if os.name == 'nt' else 0
    subprocess.run([str(java.with_name('javac.exe')), '-encoding', 'UTF-8', '-cp', str(jar), '-d', str(helpers),
                    str(ROOT / 'scripts/fixtures/java/org/hkrpg/server/protocol/NativeGmPlayerProbe.java')],
                   check=True, capture_output=True, creationflags=flags)
    http, mongo, game = gm.free_port(), gm.free_port(), gm.free_port(socket.SOCK_DGRAM)
    config = {'protocol': {'wireVersion': '4.6.0'}, 'resourceDir': str(ROOT / 'resources/4.6'),
              'httpServer': {'bindAddress': '127.0.0.1', 'bindPort': http},
              'gameServer': {'bindAddress': '127.0.0.1', 'bindPort': game},
              'internalMongoServer': {'address': '127.0.0.1', 'port': mongo, 'filePath': './data/database.mv'}}
    (directory / 'config.json').write_text(json.dumps(config), encoding='utf-8')
    base = f'http://127.0.0.1:{http}'
    java_flags = ['-Dfile.encoding=UTF-8', '-Dio.netty.eventLoopThreads=2',
                  '-Djdk.net.unixdomain.tmpdir=' + str(directory / 'tcp-fallback')]
    account_name = 'display_' + uuid.uuid4().hex + '@example.invalid'
    server = probe = None
    logs, sessions = [], []

    def request(route, body=None):
        req = urllib.request.Request(base + route, data=None if body is None else json.dumps(body).encode(),
                                     headers={'Content-Type': 'application/json'})
        with urllib.request.urlopen(req, timeout=30) as response:
            return json.load(response)

    def stop(strict=True):
        nonlocal probe, server
        exits = []
        if probe:
            (directory / 'quit').touch()
            try:
                probe.wait(timeout=40)
            except subprocess.TimeoutExpired:
                probe.terminate()
                probe.wait(timeout=15)
            exits.append(probe.returncode == 0)
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
            exits.append(server.returncode == (1000 if os.name == 'nt' else 232))
            server.stdin.close()
            server = None
        if strict:
            assert all(exits), 'Owned processes did not stop normally'

    try:
        for iteration in range(2):
            log = (directory / f'server-{iteration}.log').open('w', encoding='utf-8')
            logs.append(log)
            server = subprocess.Popen([str(java), *java_flags, '-Xmx1536m', '-jar', str(jar), '--no-handbook'],
                                      cwd=directory, stdin=subprocess.PIPE, stdout=log, stderr=subprocess.STDOUT, creationflags=flags)
            gm.wait_for(lambda: server.poll() is None and request('/health/ready')['ready'])
            account = request('/hkrpg_cn/mdk/shield/api/login', {'account': account_name, 'password': 'disposable-display'})['data']['account']
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
            snapshot = gm.wait_for(lambda: json.loads((directory / 'snapshot.json').read_text('utf-8')))
            response = wire_fields(base64.b64decode(snapshot['gachaWire']))
            assert response.get(2, [0]) == [0]
            rows = {wire_fields(row)[4][0]: wire_fields(row) for row in response[13]}
            assert len(response[13]) == len(rows) and rows.keys() == expected.keys()
            for number, (headline, cards) in expected.items():
                assert ids(rows[number], 5) == headline, f'{number}: wrong headline'
                assert ids(rows[number], 10) == cards, f'{number}: wrong card preview'
                assert not ids(rows[number], 7), f'{number}: fabricated selection data'
            assert not snapshot.get('gachaPull')
            if sessions:
                assert snapshot['uid'] == sessions[0]['uid']
            sessions.append({'uid': snapshot['uid'], 'verifiedBanners': len(rows),
                             'seele': {'headline': ids(rows[2001], 5), 'cards': ids(rows[2001], 10)},
                             'lightCone': {'headline': ids(rows[3001], 5), 'cards': ids(rows[3001], 10)}})
            stop()
            assert banner_file.read_bytes() == original
        assert hashlib.sha256(jar.read_bytes()).hexdigest() == digest
        report = {'passed': True, 'jarSHA256': digest, 'runtimeDirectory': str(directory),
                  'sessions': sessions, 'normalStops': 2, 'h2Restarts': 1,
                  'actualPulls': 0, 'operatorDataModified': False, 'clientUiVerified': False,
                  'wireFields': {'headline': 5, 'cards': 10, 'selectionMetadata': 7}}
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(report, indent=2), encoding='utf-8')
        print(json.dumps(report))
    finally:
        stop(strict=False)
        for log in logs:
            log.close()


if __name__ == '__main__':
    main()
