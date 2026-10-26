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
    parser.add_argument('--gates', action='store_true', help='验证突破、行迹和锁定批次；仍使用独立 H2/账号')
    parser.add_argument('--milestones', action='store_true', help='加验星魂扣料、晋阶奖励、重复请求和 H2 重登；包含 gates')
    parser.add_argument('--selection', action='store_true', help='独立验证光锥推荐查询、装备、跨角色交换、卸下及 H2 重登')
    args = parser.parse_args()
    if args.selection and (args.gates or args.milestones):
        parser.error('--selection 与养成门槛测试分别运行')
    args.gates |= args.milestones
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

    def grant(tid, amount, level=None):
        result = request('/gm/api/operate', {'requestId': str(uuid.uuid4()), 'uid': uid, 'action': 'grant',
                                          'itemId': tid, 'amount': amount,
                                          'level': level if level is not None else (1 if tid in (20000, 20006, 24000) else 0),
                                          'rank': 1 if tid in (20000, 20006, 24000) else 0})['data']
        assert result['state'] == 'completed', result

    def operate(command, target, costs, success=True):
        return wire(command, selection(command, target, costs), success)

    def wire(command, payload, success=True):
        key = str(uuid.uuid4())
        data = {'requestId': key, 'command': command, 'success': success,
                'wire': base64.b64encode(payload).decode()}
        temporary = directory / 'equipment-request.tmp'
        temporary.write_text(json.dumps(data), encoding='utf-8')
        temporary.replace(directory / 'equipment-request.json')
        result = gm.wait_for(lambda: matching_snapshot(lambda s: s.get('equipmentReply', {}).get('requestId') == key), 45)
        observations.append({'command': command, 'success': success, 'reply': result['equipmentReply']})
        reply = result['equipmentReply']['response']
        assert (reply.get('retcode', reply.get('PLDKENJLKME', 0)) == 0) == success
        return result

    def avatar(snap, tid):
        return next(r for r in snap['avatars']['avatar_list'] if r['base_avatar_id'] == tid)

    def trace(snap, tid, anchor):
        path = next(r for r in snap['avatars']['avatar_path_data_info_list'] if r['avatar_id'] == tid)
        return next((r['level'] for r in path['avatar_path_skill_tree'] if r['point_id'] == anchor), 0)

    def reject(command, payload):
        before = snapshot()
        after = wire(command, payload, False)
        assert after['bag'] == before['bag'] and after['avatars'] == before['avatars']
        assert after['basic']['scoin'] == before['basic']['scoin']
        return after

    def gates_first():
        result = request('/gm/api/operate', {'requestId': str(uuid.uuid4()), 'uid': uid,
                          'action': 'command', 'command': 'setlevel 15'})['data']
        assert result['state'] == 'returned', result
        grant(1202, 1, 20)
        grant(20000, 2, 20)
        for tid, amount in [(2, 100000), (31011, 1), (113001, 10), (111011, 10), (110161, 10)]:
            grant(tid, amount)
        ready = gm.wait_for(lambda: matching_snapshot(lambda s: material(s, 110161) == 10 and
                            any(a['base_avatar_id'] == 1202 for a in s['avatars']['avatar_list'])), 40)
        credits = ready['basic']['scoin']
        cones = sorted(items(ready, 'equipment', 20000), key=lambda r: r['unique_id'])
        target, donor = (r['unique_id'] for r in cones)
        relic = items(ready, 'relic', 31011)[0]['unique_id']
        assert ready['basic']['level'] == 15
        assert avatar(ready, 1202)['level'] == 20 and not avatar(ready, 1202).get('promotion', 0)
        assert trace(ready, 1202, 2) == 1
        upgrade_trace = number(11, 1202002) + number(5, 2)
        reject('UnlockSkillTreeCsReq', upgrade_trace)  # Needs promotion 1.
        promoted = wire('PromoteAvatarCsReq', number(5, 1202))
        assert avatar(promoted, 1202)['promotion'] == 1
        assert promoted['basic']['scoin'] == credits - 3200 and material(promoted, 113001) == 6
        reject('PromoteAvatarCsReq', number(5, 1202))
        upgraded = wire('UnlockSkillTreeCsReq', upgrade_trace)
        assert trace(upgraded, 1202, 2) == 2 and material(upgraded, 113001) == 4
        assert upgraded['basic']['scoin'] == credits - 5200
        assert upgraded['equipmentReply']['response'] == {'point_id': 1202002, 'level': 2}
        reject('UnlockSkillTreeCsReq', upgrade_trace)
        reject('UnlockSkillTreeCsReq', number(11, 1202002) + number(5, 3))
        promoted = wire('PromoteEquipmentCsReq', number(10, target))
        assert row(promoted, 'equipment', target)['promotion'] == 1
        assert promoted['basic']['scoin'] == credits - 8200 and material(promoted, 111011) == 6
        reject('PromoteEquipmentCsReq', number(10, target))
        locked = wire('LockEquipmentCsReq', number(7, donor) + number(6, 1))
        assert row(locked, 'equipment', donor)['is_protected']
        reject('RankUpEquipmentCsReq', selection('RankUpEquipmentCsReq', target, [number(2, donor)]))
        reject('LockEquipmentCsReq', number(7, target) + number(7, 999999) + number(6, 1))
        reject('LockEquipmentCsReq', number(7, relic) + number(6, 1))
        reject('LockRelicCsReq', number(5, target) + number(12, 1))
        locked = wire('LockRelicCsReq', number(5, relic) + number(12, 1))
        assert row(locked, 'relic', relic)['is_protected']
        unlocked = wire('LockRelicCsReq', number(5, relic))
        assert not row(unlocked, 'relic', relic).get('is_protected', False)
        locked = wire('LockRelicCsReq', number(5, relic) + number(12, 1))
        return locked

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

    def rank_request(rank=1, item=11202, count=1):
        # Pinned CmdAvatarType: rank=6, avatar_id=7, cost_data=14.
        return number(6, rank) + number(7, 1202) + message(14, message(2, pile(item, count)))

    def milestone_rank(snap):
        path = next(r for r in snap['avatars']['avatar_path_data_info_list'] if r['avatar_id'] == 1202)
        return path.get('rank', 0)

    def milestones_first():
        grant(11202, 1)
        ready = gm.wait_for(lambda: matching_snapshot(lambda s: material(s, 11202) == 1), 40)
        before_tickets = material(ready, 101)
        assert milestone_rank(ready) == 0
        reject('RankUpAvatarCsReq', rank_request(item=101))
        reject('RankUpAvatarCsReq', rank_request(count=2))
        reject('RankUpAvatarCsReq', number(7, 1202))
        ranked = wire('RankUpAvatarCsReq', rank_request())
        assert milestone_rank(ranked) == 1 and material(ranked, 11202) == 0
        assert any(r.get('avatar_sync') for r in ranked['equipmentReply']['syncs'])
        reject('RankUpAvatarCsReq', rank_request())  # The single material was already spent.
        reject('TakePromotionRewardCsReq', number(5, 1202) + number(2, 3))
        claimed = wire('TakePromotionRewardCsReq', number(5, 1202) + number(2, 1))
        assert material(claimed, 101) == before_tickets + 1
        assert claimed['equipmentReply']['response'].get('reward_list')
        assert any(r.get('avatar_sync') for r in claimed['equipmentReply']['syncs'])
        return reject('TakePromotionRewardCsReq', number(5, 1202) + number(2, 1))

    def milestones_after_restart():
        assert milestone_rank(snapshot()) == 1
        reject('TakePromotionRewardCsReq', number(5, 1202) + number(2, 1))
        reject('RankUpAvatarCsReq', rank_request())
        grant(11202, 1)
        gm.wait_for(lambda: matching_snapshot(lambda s: material(s, 11202) == 1), 40)
        ranked = wire('RankUpAvatarCsReq', rank_request(rank=2))
        assert milestone_rank(ranked) == 2 and material(ranked, 11202) == 0

    def recommendation(snap, avatar_id, kind=1):
        # Pinned CN4.6: request 2416 fields 10/12; reply 2411 fields 9/12.
        result = wire('GetBigDataRecommendCsReq', number(10, avatar_id) + number(12, kind))
        reply = result['equipmentReply']['response']
        assert reply == {'equip_avatar': avatar_id, 'big_data_recommend_type':
                         'BIG_DATA_RECOMMEND_TYPE_' + ('EQUIPMENT' if kind == 1 else 'RELIC_SUIT')}, reply
        assert result['bag'] == snap['bag'] and result['avatars'] == snap['avatars']
        assert result['basic'] == snap['basic'], 'Read-only recommendation changed player state'
        return result

    def equipped(snap, owner):
        matches = [r for r in items(snap, 'equipment', 20006) if r.get('dress_avatar_id') == owner]
        assert len(matches) == 1, (owner, matches)
        unique = matches[0]['unique_id']
        assert avatar(snap, owner)['base_avatar_id'] == owner
        path = next(r for r in snap['avatars']['avatar_path_data_info_list'] if r['avatar_id'] == owner)
        assert path.get('path_equipment_id') == unique
        return unique

    def dress(avatar_id, unique):
        # Native CmdAvatarType: avatar 2 / unique item 4; never persistent DB object IDs.
        result = wire('DressAvatarCsReq', number(2, avatar_id) + number(4, unique))
        assert equipped(result, avatar_id) == unique
        assert any(s.get('equipment_list') and s.get('avatar_sync')
                   for s in result['equipmentReply']['syncs']), 'Missing equipment/avatar notification'
        return result

    def selection_first():
        # Only this disposable server/account receives fixtures. Keep production equipment intact.
        grant(1003, 1, 1)
        grant(1202, 1, 1)
        grant(20006, 2)
        ready = gm.wait_for(lambda: matching_snapshot(lambda s: len(items(s, 'equipment', 20006)) == 2
                            and {1003, 1202}.issubset({r['base_avatar_id'] for r in s['avatars']['avatar_list']})), 40)
        for avatar_id, kind in ((1003, 1), (1202, 1), (1003, 2)):
            ready = recommendation(ready, avatar_id, kind)
        first, second = sorted(r['unique_id'] for r in items(ready, 'equipment', 20006))
        dress(1003, first)
        dress(1202, second)
        swapped = dress(1003, second)
        assert equipped(swapped, 1202) == first, 'Cross-avatar replacement must swap the old cone'
        removed = wire('TakeOffEquipmentCsReq', number(9, 1003))
        assert not row(removed, 'equipment', second).get('dress_avatar_id')
        path = next(r for r in removed['avatars']['avatar_path_data_info_list'] if r['avatar_id'] == 1003)
        assert not path.get('path_equipment_id')
        assert equipped(removed, 1202) == first
        final = dress(1003, second)
        return recommendation(final, 1003)

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
            if iteration == 0 and args.selection:
                final = selection_first()
                (directory / 'before-restart.json').write_text(json.dumps(final, indent=2), encoding='utf-8')
            elif iteration == 0 and args.gates:
                final = gates_first()
                if args.milestones:
                    final = milestones_first()
                (directory / 'before-restart.json').write_text(json.dumps(final, indent=2), encoding='utf-8')
            elif iteration == 0:
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
                assert initial['basic'].get('scoin', 0) == final['basic'].get('scoin', 0), 'Credit debit did not survive restart'
                if args.selection:
                    # Session wire IDs can change; both persisted ownership and each fresh avatar
                    # reference must agree with the new bag, without relying on old unique IDs.
                    assert equipped(initial, 1003) != equipped(initial, 1202)
                    restored = recommendation(initial, 1003)
                    recommendation(restored, 1202)
                elif args.gates:
                    assert initial['avatars'] == final['avatars'], 'Promotion/traces did not survive H2 restart'
                    target = next(r['unique_id'] for r in items(initial, 'equipment', 20000) if r.get('promotion') == 1)
                    donor = next(r['unique_id'] for r in items(initial, 'equipment', 20000) if r.get('is_protected'))
                    reject('RankUpEquipmentCsReq', selection('RankUpEquipmentCsReq', target, [number(2, donor)]))
                    wire('LockEquipmentCsReq', number(7, donor))
                    ranked = operate('RankUpEquipmentCsReq', target, [number(2, donor)])
                    assert row(ranked, 'equipment', target)['rank'] == 2
                    reject('UnlockSkillTreeCsReq', number(11, 1202002) + number(5, 2))
                    if args.milestones:
                        milestones_after_restart()
                else:
                    restored = next(r for r in items(initial, 'equipment', 20000) if r['rank'] == 2)
                    rejected = operate('ExpUpEquipmentCsReq', restored['unique_id'], [pile(221, 100)], False)
                    assert rejected['bag'] == initial['bag'], 'Rejected request consumed materials after restart'
            sessions.append(snapshot())
            stop()
        assert hashlib.sha256(jar.read_bytes()).hexdigest() == digest
        result = {'passed': True, 'clientAccepted': False, 'isolated': True, 'jarSha256': digest,
                  'suite': 'equipment-selection' if args.selection else ('avatar-milestones' if args.milestones else ('progression-gates' if args.gates else 'equipment-progression')),
                  'uid': uid, 'sessions': len(sessions), 'normalStops': normal_stops,
                  'requests': sum(s['requests'] for s in sessions), 'operations': observations,
                  'finalBag': final['bag'], 'credits': final['basic'].get('scoin', 0), 'directory': str(directory)}
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
        print(json.dumps({k: v for k, v in result.items() if k not in ('operations', 'finalBag')}, ensure_ascii=False))
    finally:
        stop(False)
        for log in logs: log.close()


if __name__ == '__main__':
    main()
