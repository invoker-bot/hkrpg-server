"""隔离账号验证常驻300次自选：真实4.6 KCP、实际到账和H2重启。"""
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


def varint(value):
    out = bytearray()
    while value > 127:
        out.append((value & 127) | 128)
        value >>= 7
    out.append(value)
    return bytes(out)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--duplicate', action='store_true', help='先为隔离账号发放姬子，验证重复角色的星魂到账')
    parser.add_argument('--output', type=Path, default=ROOT / 'build/gacha-ceiling-native.json')
    args = parser.parse_args()
    jar = ROOT / 'build/libs/hkrpg-server.jar'
    digest = hashlib.sha256(jar.read_bytes()).hexdigest()
    java = next((ROOT / '.tools').glob('jdk-21*/bin/java.exe'))
    runs = ROOT / 'build/smoke'
    runs.mkdir(exist_ok=True)
    directory = Path(tempfile.mkdtemp(prefix='gacha-ceiling-', dir=runs))
    helpers = directory / 'helpers'
    helpers.mkdir()
    flags = subprocess.CREATE_NO_WINDOW if os.name == 'nt' else 0
    sources = [ROOT / 'scripts/fixtures/java/org/hkrpg/server/protocol' / (name + '.java')
               for name in ('NativeGmPlayerProbe', 'GachaCeilingFixtureInspector')]
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
    account_name = 'gacha_ceiling_' + uuid.uuid4().hex + '@example.invalid'
    opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
    logs, operations = [], []
    server = probe = None
    uid = None
    csrf = ''

    def request(route, body=None):
        req = urllib.request.Request(base + route, data=None if body is None else json.dumps(body).encode(),
                                     headers={'Content-Type': 'application/json', 'Origin': base, 'X-GM-CSRF': csrf})
        with opener.open(req, timeout=30) as response:
            return json.load(response)

    def snapshot():
        return json.loads((directory / 'snapshot.json').read_text(encoding='utf-8'))

    def material(snapshot, item):
        return next((r['num'] for r in snapshot['bag'].get('material_list', []) if r['tid'] == item), 0)

    def avatars(snapshot):
        return {row['base_avatar_id'] for row in snapshot['avatars']['avatar_list']}

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
            'org.hkrpg.server.protocol.GachaCeilingFixtureInspector', action, str(directory)],
            cwd=ROOT, capture_output=True, timeout=90, creationflags=flags)
        (directory / f'inspect-{action}.log').write_bytes(result.stdout + result.stderr)
        assert result.returncode == 0, 'Owned H2 inspector failed'
        return json.loads(next(line.removeprefix('CEILING_FIXTURE=') for line in result.stdout.decode('utf-8').splitlines() if line.startswith('CEILING_FIXTURE=')))

    def operation(item, amount=1):
        data = request('/gm/api/operate', {'requestId': str(uuid.uuid4()), 'uid': uid,
                       'action': 'grant', 'itemId': item, 'amount': amount, 'level': 1 if item == 1003 else 0, 'rank': 0})['data']
        assert data['state'] == 'completed', data

    def native(command, fields, success=True):
        payload = b''.join(varint(field * 8) + varint(value) for field, value in fields)
        key, packet_id = str(uuid.uuid4()), 61001 + len(operations)
        target = directory / 'equipment-request.json'
        temporary = target.with_suffix('.tmp')
        temporary.write_text(json.dumps({'requestId': key, 'command': command, 'success': success,
                                        'packetId': packet_id, 'wire': base64.b64encode(payload).decode()}), encoding='utf-8')
        temporary.replace(target)
        result = gm.wait_for(lambda: (lambda s: s if s.get('equipmentReply', {}).get('requestId') == key else None)(snapshot()), 40)
        target.unlink()
        reply = result['equipmentReply']
        assert base64.b64decode(reply['replyHeader']) == varint(8) + varint(packet_id)
        operations.append({'command': command, 'fields': fields, 'success': success,
                           'matchedReplyPacketId': packet_id, 'response': reply['response']})
        return result, reply['response']

    def query(expected, claimed=False):
        result, reply = native('GetGachaCeilingCsReq', [(15, 1)])
        ceiling = reply['gacha_ceiling']
        assert reply['gacha_type'] == 1 and ceiling.get('ceiling_num', 0) == expected
        assert ceiling.get('is_claimed', False) == claimed
        assert {row['avatar_id'] for row in ceiling['avatar_list']} == {1003, 1004, 1101, 1104, 1107, 1209, 1211}
        return result

    def pull():
        key = str(uuid.uuid4())
        target = directory / 'gacha-pull-request.json'
        temporary = target.with_suffix('.tmp')
        temporary.write_text(json.dumps({'requestId': key, 'count': 1}), encoding='utf-8')
        temporary.replace(target)
        result = gm.wait_for(lambda: (lambda s: s if s.get('gachaPull', {}).get('requestId') == key else None)(snapshot()), 40)
        target.unlink()
        assert result['gachaPull']['response']['gacha_num'] == 1 and material(result, 101) == 0
        return result

    try:
        seeded = delivered = final = None
        expected_eidolons = None
        chosen = 1003 if args.duplicate else 1101
        for iteration in range(3):
            log = (directory / f'server-{iteration}.log').open('w', encoding='utf-8')
            logs.append(log)
            server = subprocess.Popen([str(java), *java_flags, '-Xmx1536m', '-jar', str(jar), '--no-handbook'],
                cwd=directory, stdin=subprocess.PIPE, stdout=log, stderr=subprocess.STDOUT, creationflags=flags)
            gm.wait_for(lambda: server.poll() is None and request('/health/ready')['ready'])
            csrf = request('/gm/api/session', {})['data']['csrf']
            account = request('/hkrpg_cn/mdk/shield/api/login', {'account': account_name, 'password': 'disposable-ceiling-smoke'})['data']['account']
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
                query(0)
                native('ExchangeGachaCeilingCsReq', [(1, 1), (6, chosen)], False)
                operation(101)
                gm.wait_for(lambda: material(snapshot(), 101) == 1, 30)
                if args.duplicate:
                    operation(1003)
                    gm.wait_for(lambda: 1003 in avatars(snapshot()), 30)
                else:
                    assert chosen not in avatars(snapshot()), 'New-avatar fixture already owns the target'
                (directory / 'ceiling-fixture.json').write_text(json.dumps({'testFixture': True, 'uid': uid}), encoding='utf-8')
                stop()
                seeded = inspect('seed')
                assert seeded['ceiling'] == 299 and not seeded['claimed'] and seeded['receipt'] == 'none'
            elif iteration == 1:
                query(299)
                native('ExchangeGachaCeilingCsReq', [(1, 1), (6, chosen)], False)
                pull()
                before = query(300)
                native('ExchangeGachaCeilingCsReq', [(1, 1), (6, 1102)], False)
                native('ExchangeGachaCeilingCsReq', [(1, 2), (6, chosen)], False)
                if not args.duplicate:
                    chosen = next(id for id in (1101, 1003, 1004, 1104, 1107, 1209, 1211)
                                  if id not in avatars(before))
                after, reply = native('ExchangeGachaCeilingCsReq', [(1, 1), (6, chosen)])
                assert reply['avatar_id'] == chosen and reply['gacha_type'] == 1
                assert reply['gacha_ceiling']['is_claimed']
                if args.duplicate:
                    expected_eidolons = material(before, 11003) + 1
                    assert material(after, 11003) == expected_eidolons
                else:
                    assert chosen not in avatars(before) and chosen in avatars(after)
                query(300, True)
                native('ExchangeGachaCeilingCsReq', [(1, 1), (6, chosen)], False)
                stop()
                delivered = inspect('inspect')
                assert delivered['ceiling'] == 300 and delivered['claimed'] and delivered['receipt'] == 'granted'
                if args.duplicate:
                    assert delivered['himeko'] == 1 and delivered['himekoEidolons'] == expected_eidolons
            else:
                restored = query(300, True)
                if args.duplicate:
                    assert 1003 in avatars(restored) and material(restored, 11003) == expected_eidolons
                else:
                    assert chosen in avatars(restored)
                native('ExchangeGachaCeilingCsReq', [(1, 1), (6, chosen)], False)
                stop()
                final = inspect('inspect')
                assert final == delivered, 'Saved entitlement or reward changed after normal restart'
        assert hashlib.sha256(jar.read_bytes()).hexdigest() == digest
        report = {'passed': True, 'jarSHA256': digest, 'runtimeDirectory': str(directory), 'uid': uid,
                  'scenario': 'duplicate-eidolon' if args.duplicate else 'new-avatar', 'normalStops': 3,
                  'sameUidAfterTwoRestarts': True, 'actualPlayerReads': 6, 'actualPulls': 1,
                  'seeded': seeded, 'persisted': final, 'operations': operations,
                  'operatorDataModified': False, 'clientGameplayVerified': False}
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding='utf-8')
        print(json.dumps({k: report[k] for k in ('passed', 'scenario', 'normalStops', 'actualPlayerReads', 'actualPulls')}))
    finally:
        stop(strict=False)
        for log in logs:
            log.close()


if __name__ == '__main__':
    main()
