"""隔离账号的光锥升级、遗器强化、叠影与 H2 重启；材料由测试服 GM 显式发放。"""
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


def integer(value):
    out = bytearray()
    while value > 127:
        out.append((value & 127) | 128)
        value >>= 7
    return bytes(out + bytes([value]))


def number(field, value):
    return integer(field * 8) + integer(value)


def message(field, value):
    return integer(field * 8 + 2) + integer(len(value)) + value


def selection(command, target, costs):
    # Pinned CN4.6 CmdItemType: independent wire, not the bridge's name mapping.
    target_field, cost_field = {'ExpUpEquipmentCsReq': (2, 5), 'ExpUpRelicCsReq': (1, 13),
                              'RankUpEquipmentCsReq': (8, 11)}[command]
    return number(target_field, target) + message(cost_field, b''.join(message(2, c) for c in costs))


def pile(item, count):
    return message(10, number(14, item) + number(1, count))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, default=ROOT / 'build/equipment-progression-native.json')
    args = parser.parse_args()
    jar = ROOT / 'build/libs/hkrpg-server.jar'
    digest = hashlib.sha256(jar.read_bytes()).hexdigest()
    java = next((ROOT / '.tools').glob('jdk-21*/bin/java.exe'))
    runs = ROOT / 'build/smoke'
    runs.mkdir(exist_ok=True)
    directory = Path(tempfile.mkdtemp(prefix='equipment-progression-', dir=runs))
    helpers = directory / 'helpers'
    helpers.mkdir()
    flags = subprocess.CREATE_NO_WINDOW if os.name == 'nt' else 0
    source = ROOT / 'scripts/fixtures/java/org/hkrpg/server/protocol/NativeGmPlayerProbe.java'
    subprocess.run([str(java.with_name('javac.exe')), '-encoding', 'UTF-8', '-cp', str(jar), '-d', str(helpers), str(source)],
                   check=True, capture_output=True, creationflags=flags)
    http_port, mongo, game = gm.free_port(), gm.free_port(), gm.free_port(socket.SOCK_DGRAM)
    config = {'protocol': {'wireVersion': '4.6.0'}, 'resourceDir': str(ROOT / 'resources/4.6'),
              'httpServer': {'bindAddress': '127.0.0.1', 'bindPort': http_port},
              'gameServer': {'bindAddress': '127.0.0.1', 'bindPort': game},
              'internalMongoServer': {'address': '127.0.0.1', 'port': mongo, 'filePath': './data/database.mv'}}
    (directory / 'config.json').write_text(json.dumps(config), encoding='utf-8')
    base = f'http://127.0.0.1:{http_port}'
    java_flags = ['-Dfile.encoding=UTF-8', '-Dio.netty.eventLoopThreads=2',
                  '-Djdk.net.unixdomain.tmpdir=' + str(directory / 'tcp-fallback')]
    account_name = 'equipment_' + uuid.uuid4().hex + '@example.invalid'
    logs, observations, sessions = [], [], []
    server = probe = None
    uid = None
    csrf = ''
    normal_stops = 0
    opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))

    def request(route, body=None):
        req = urllib.request.Request(base + route, data=None if body is None else json.dumps(body).encode(),
                                     headers={'Content-Type': 'application/json', 'Origin': base, 'X-GM-CSRF': csrf})
        with opener.open(req, timeout=30) as response:
            return json.load(response)

    def snapshot():
        return json.loads((directory / 'snapshot.json').read_text(encoding='utf-8'))

    def matching_snapshot(predicate):
        current = snapshot()
        return current if predicate(current) else None

    def material(snap, tid):
        return next((r['num'] for r in snap['bag'].get('material_list', []) if r['tid'] == tid), 0)

    def items(snap, kind, tid):
        return [r for r in snap['bag'].get(kind + '_list', []) if r['tid'] == tid]

    def row(snap, kind, unique):
        return next(r for r in snap['bag'][kind + '_list'] if r['unique_id'] == unique)

    def persisted_bag(snap):
        # GameItem.internalUid is transient: a new KCP session receives fresh wire IDs.
        # Compare the complete item multiset (including duplicates), excluding only that ID.
        result = {}
        for key, value in snap['bag'].items():
            if isinstance(value, list):
                result[key] = sorted(json.dumps({k: v for k, v in r.items() if k != 'unique_id'},
                                               sort_keys=True) for r in value)
            else:
                result[key] = value
        return result

    def grant(tid, amount):
        result = request('/gm/api/operate', {'requestId': str(uuid.uuid4()), 'uid': uid, 'action': 'grant',
                                          'itemId': tid, 'amount': amount,
                                          'level': 1 if tid in (20000, 24000) else 0,
                                          'rank': 1 if tid in (20000, 24000) else 0})['data']
        assert result['state'] == 'completed', result

    def operate(command, target, costs, success=True):
        key = str(uuid.uuid4())
        data = {'requestId': key, 'command': command, 'success': success,
                'wire': base64.b64encode(selection(command, target, costs)).decode()}
        temporary = directory / 'equipment-request.tmp'
        temporary.write_text(json.dumps(data), encoding='utf-8')
        temporary.replace(directory / 'equipment-request.json')
        result = gm.wait_for(lambda: matching_snapshot(lambda s: s.get('equipmentReply', {}).get('requestId') == key), 45)
        observations.append({'command': command, 'success': success, 'reply': result['equipmentReply']})
        assert (result['equipmentReply']['response'].get('retcode', 0) == 0) == success
        return result

    def stop(strict=True):
        nonlocal probe, server, normal_stops
        results = []
        if probe:
            (directory / 'quit').touch()
            try:
                probe.wait(timeout=40)
            except subprocess.TimeoutExpired:
                probe.terminate(); probe.wait(timeout=15)
            results.append(probe.returncode == 0)
            probe = None
        if server:
            if server.poll() is None:
                server.stdin.write(b'stop\n'); server.stdin.flush()
                try:
                    server.wait(timeout=45)
                except subprocess.TimeoutExpired:
                    server.terminate(); server.wait(timeout=15)
            results.append(server.returncode == (1000 if os.name == 'nt' else 232))
            server.stdin.close(); server = None
        if strict:
            assert results and all(results), 'Owned probe/server did not stop normally'
            normal_stops += 1

    try:
        for iteration in range(2):
            log = (directory / f'server-{iteration}.log').open('wb'); logs.append(log)
            server = subprocess.Popen([str(java), *java_flags, '-Xmx1536m', '-jar', str(jar), '--no-handbook'],
                cwd=directory, stdin=subprocess.PIPE, stdout=log, stderr=subprocess.STDOUT, creationflags=flags)
            gm.wait_for(lambda: server.poll() is None and request('/health/ready')['ready'])
            csrf = request('/gm/api/session', {})['data']['csrf']
            account = request('/hkrpg_cn/mdk/shield/api/login', {'account': account_name, 'password': 'disposable-equipment-smoke'})['data']['account']
            combo = request('/hkrpg_cn/combo/granter/login/v2/login', {'data': json.dumps({'uid': account['uid'], 'token': account['token']})})
            credentials = directory / 'credentials.json'
            credentials.write_text(json.dumps({'accountUid': account['uid'], 'comboToken': combo['data']['combo_token']}), encoding='utf-8')
            for name in ('quit', 'snapshot.json', 'equipment-request.json'):
                (directory / name).unlink(missing_ok=True)
            log = (directory / f'probe-{iteration}.log').open('wb'); logs.append(log)
            probe = subprocess.Popen([str(java), *java_flags, '-cp', str(helpers) + os.pathsep + str(jar),
                'org.hkrpg.server.protocol.NativeGmPlayerProbe', str(game), str(credentials), str(directory)],
                cwd=directory, stdout=log, stderr=subprocess.STDOUT, creationflags=flags)
            initial = gm.wait_for(snapshot)
            if uid is None: uid = initial['uid']
            assert initial['uid'] == uid
            if iteration == 0:
                for tid, amount in [(2, 100000), (20000, 4), (24000, 1), (31011, 1), (221, 10), (232, 10), (271, 4)]:
                    grant(tid, amount)
                ready = gm.wait_for(lambda: matching_snapshot(lambda s: material(s, 271) == 4 and len(items(s, 'equipment', 20000)) == 4), 40)
                cones = sorted(items(ready, 'equipment', 20000), key=lambda r: r['unique_id'])
                target, donor = cones[0]['unique_id'], cones[1]['unique_id']
                special = items(ready, 'equipment', 24000)[0]['unique_id']
                relic = items(ready, 'relic', 31011)[0]['unique_id']
                credits = ready['basic']['scoin']
                upgraded = operate('ExpUpEquipmentCsReq', target, [pile(221, 1)])
                assert (row(upgraded, 'equipment', target)['level'], row(upgraded, 'equipment', target)['exp']) == (5, 140)
                assert material(upgraded, 221) == 9 and upgraded['basic']['scoin'] == credits - 250
                assert any(r.get('equipment_list') for r in upgraded['equipmentReply']['syncs'])
                upgraded = operate('ExpUpRelicCsReq', relic, [pile(232, 1)])
                assert (row(upgraded, 'relic', relic)['level'], row(upgraded, 'relic', relic)['exp']) == (2, 90)
                assert material(upgraded, 232) == 9 and upgraded['basic']['scoin'] == credits - 1000
                assert any(r.get('relic_list') for r in upgraded['equipmentReply']['syncs'])
                for command, object_id, costs in [
                    ('RankUpEquipmentCsReq', target, [number(2, target)]),
                    ('RankUpEquipmentCsReq', target, [number(2, donor), number(2, donor)]),
                    ('RankUpEquipmentCsReq', target, [number(4, donor)]),
                    ('ExpUpEquipmentCsReq', target, [pile(221, 100)]),
                    ('ExpUpRelicCsReq', relic, [pile(232, 100)])]:
                    before = snapshot()
                    rejected = operate(command, object_id, costs, False)
                    assert rejected['bag'] == before['bag'] and rejected['basic']['scoin'] == before['basic']['scoin']
                ranked = operate('RankUpEquipmentCsReq', target, [number(2, donor)])
                assert row(ranked, 'equipment', target)['rank'] == 2
                assert len(items(ranked, 'equipment', 20000)) == 3
                again = operate('RankUpEquipmentCsReq', target, [number(2, donor)], False)
                assert again['bag'] == ranked['bag']
                ranked = operate('RankUpEquipmentCsReq', special, [pile(271, 3)])
                assert row(ranked, 'equipment', special)['rank'] == 4 and material(ranked, 271) == 1
                ranked = operate('RankUpEquipmentCsReq', special, [pile(271, 1)])
                assert row(ranked, 'equipment', special)['rank'] == 5 and material(ranked, 271) == 0
                final = ranked
                (directory / 'before-restart.json').write_text(json.dumps(final, indent=2), encoding='utf-8')
            else:
                (directory / 'after-restart.json').write_text(json.dumps(initial, indent=2), encoding='utf-8')
                assert persisted_bag(initial) == persisted_bag(final), 'Inventory/progression did not survive H2 restart'
                assert initial['basic']['scoin'] == final['basic']['scoin'], 'Credit debit did not survive restart'
                restored = next(r for r in items(initial, 'equipment', 20000) if r['rank'] == 2)
                rejected = operate('ExpUpEquipmentCsReq', restored['unique_id'], [pile(221, 100)], False)
                assert rejected['bag'] == initial['bag'], 'Rejected request consumed materials after restart'
            sessions.append(snapshot())
            stop()
        assert hashlib.sha256(jar.read_bytes()).hexdigest() == digest
        result = {'passed': True, 'clientAccepted': False, 'isolated': True, 'jarSha256': digest,
                  'uid': uid, 'sessions': len(sessions), 'normalStops': normal_stops,
                  'requests': sum(s['requests'] for s in sessions), 'operations': observations,
                  'finalBag': final['bag'], 'credits': final['basic']['scoin'], 'directory': str(directory)}
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
        print(json.dumps({k: v for k, v in result.items() if k not in ('operations', 'finalBag')}, ensure_ascii=False))
    finally:
        stop(False)
        for log in logs: log.close()


if __name__ == '__main__':
    main()
