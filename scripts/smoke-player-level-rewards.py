"""隔离账号、真实4.6 KCP与H2：69档等级奖励、到账、防重、重连及正常重启。"""
import argparse
import base64
from collections import Counter
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
    result = bytearray()
    while value > 127:
        result.append((value & 127) | 128)
        value >>= 7
    return bytes(result + bytes([value]))


def claim_wire(level):
    return bytes([8, 13, 56]) + integer(level)  # Prop entity 13 is not a reward/quantity authority.


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--jar', type=Path, default=ROOT / 'build/libs/hkrpg-server.jar')
    parser.add_argument('--output', type=Path, default=ROOT / 'build/player-level-rewards-native.json')
    args = parser.parse_args()
    jar = args.jar.resolve()
    digest = hashlib.sha256(jar.read_bytes()).hexdigest()
    java = next((ROOT / '.tools').glob('jdk-21*/bin/java.exe'))
    runs = ROOT / 'build/smoke'
    runs.mkdir(exist_ok=True)
    directory = Path(tempfile.mkdtemp(prefix='player-level-rewards-', dir=runs))
    helpers = directory / 'helpers'
    helpers.mkdir()
    flags = subprocess.CREATE_NO_WINDOW if os.name == 'nt' else 0
    subprocess.run([str(java.with_name('javac.exe')), '-encoding', 'UTF-8', '-cp', str(jar), '-d', str(helpers),
                    str(ROOT / 'scripts/fixtures/java/org/hkrpg/server/protocol/NativeGmPlayerProbe.java')],
                   check=True, capture_output=True, creationflags=flags)
    http_port, mongo, game = gm.free_port(), gm.free_port(), gm.free_port(socket.SOCK_DGRAM)
    config = directory / 'config.json'
    config.write_text(json.dumps({'protocol': {'wireVersion': '4.6.0'}, 'resourceDir': str(ROOT / 'resources/4.6'),
        'serverOptions': {'startTrailblazerLevel': 70},
        'httpServer': {'bindAddress': '127.0.0.1', 'bindPort': http_port},
        'gameServer': {'bindAddress': '127.0.0.1', 'bindPort': game},
        'internalMongoServer': {'address': '127.0.0.1', 'port': mongo, 'filePath': './data/database.mv'}}), 'utf-8')
    original_config = config.read_bytes()
    resource_report = json.loads((ROOT / 'build/player-level-rewards-resources.json').read_text('utf-8'))
    resource_hashes = resource_report['files']
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}),
                                        urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
    account_name = 'level-reward-' + uuid.uuid4().hex + '@example.invalid'
    jflags = ['-Dfile.encoding=UTF-8', '-Dio.netty.eventLoopThreads=2',
              '-Djdk.net.unixdomain.tmpdir=' + str(directory / 'tcp-fallback')]
    server = probe = None
    logs, results, snapshots = [], [], []
    stops = 0

    def request(route, body=None):
        req = urllib.request.Request(f'http://127.0.0.1:{http_port}' + route,
            data=None if body is None else json.dumps(body).encode(), headers={'Content-Type': 'application/json'})
        with opener.open(req, timeout=5) as response:
            return json.load(response)

    def snapshot():
        return json.loads((directory / 'snapshot.json').read_text('utf-8'))

    def send(steps):
        rid = str(uuid.uuid4())
        target = directory / 'equipment-request.json'
        temporary = target.with_suffix('.tmp')
        commands = [dict(command=name, wire=base64.b64encode(body).decode(), success=success,
                         packetId=7300 + i) for i, (name, body, success) in enumerate(steps)]
        temporary.write_text(json.dumps(dict(requestId=rid, commands=commands)), 'utf-8')
        temporary.replace(target)
        s = gm.wait_for(lambda: (lambda x: x if x.get('equipmentReply', {}).get('requestId') == rid else None)(snapshot()), 90)
        target.unlink()
        replies = s['equipmentReply']['responses']
        assert len(replies) == len(steps)
        results.extend(replies)
        return [r['response'] for r in replies], s

    def stop_probe():
        nonlocal probe
        if probe:
            (directory / 'quit').touch()
            probe.wait(timeout=40)
            assert probe.returncode == 0, directory
            probe = None

    def stop_server():
        nonlocal server, stops
        if server:
            server.stdin.write(b'stop\n')
            server.stdin.flush()
            server.wait(timeout=45)
            assert server.returncode == (1000 if os.name == 'nt' else 232), directory
            server.stdin.close()
            server = None
            stops += 1

    def start_probe():
        nonlocal probe
        account = request('/hkrpg_cn/mdk/shield/api/login', {'account': account_name, 'password': 'isolated-test'})['data']['account']
        combo = request('/hkrpg_cn/combo/granter/login/v2/login',
                        {'data': json.dumps({'uid': account['uid'], 'token': account['token']})})
        credentials = directory / 'credentials.json'
        credentials.write_text(json.dumps({'accountUid': account['uid'], 'comboToken': combo['data']['combo_token']}), 'utf-8')
        (directory / 'quit').unlink(missing_ok=True)
        (directory / 'snapshot.json').unlink(missing_ok=True)
        log = (directory / f'probe-{len(logs)}.log').open('w', encoding='utf-8')
        logs.append(log)
        probe = subprocess.Popen([str(java), *jflags, '-cp', str(helpers) + os.pathsep + str(jar),
            'org.hkrpg.server.protocol.NativeGmPlayerProbe', str(game), str(credentials), str(directory)],
            cwd=directory, stdout=log, stderr=subprocess.STDOUT, creationflags=flags)
        return gm.wait_for(snapshot)

    def payout(row):
        values = Counter({row[f'ItemID_{i}']: row[f'Count_{i}'] for i in range(1, 7) if row.get(f'ItemID_{i}')})
        if row.get('Hcoin'):
            values[1] += row['Hcoin']
        return values

    def reward(response):
        values = Counter()
        for item in response['reward']['item_list']:
            values[item['item_id']] += item['num']
        return values

    def inventory(snapshot):
        values = Counter({r['tid']: r['num'] for r in snapshot['bag'].get('material_list', [])})
        for field in ['equipment_list', 'relic_list']:
            values.update(r['tid'] for r in snapshot['bag'].get(field, []))
        return values

    def bag(snapshot):
        # GetBag repeated rows are unordered maps; preserve every item field, including UID/affixes.
        return {key: sorted(value,key=lambda row: json.dumps(row,sort_keys=True))
                if isinstance(value,list) else value for key,value in snapshot['bag'].items()}

    try:
        for cycle in range(2):
            assert hashlib.sha256(jar.read_bytes()).hexdigest() == digest
            log = (directory / f'server-{cycle}.log').open('w', encoding='utf-8')
            logs.append(log)
            server = subprocess.Popen([str(java), *jflags, '-Xmx1536m', '-jar', str(jar), '--no-handbook'],
                cwd=directory, stdin=subprocess.PIPE, stdout=log, stderr=subprocess.STDOUT, creationflags=flags)
            gm.wait_for(lambda: server.poll() is None and request('/health/ready')['ready'])
            initial = start_probe()
            replies, _ = send([('GetLevelRewardTakenListCsReq', b'', True)])
            if cycle == 0:
                assert not replies[0].get('level_reward_taken_list')
                before = initial
                replies, after3 = send([('GetLevelRewardCsReq', claim_wire(3), True)])
                assert replies[0]['level'] == 3
                assert reward(replies[0]) == {1: 50, 2: 10000, 211: 5}
                assert after3['basic'].get('hcoin', 0) == before['basic'].get('hcoin', 0) + 50
                assert after3['basic'].get('scoin', 0) == before['basic'].get('scoin', 0) + 10000
                assert inventory(after3)[211] == inventory(before)[211] + 5
                replies, duplicate = send([('GetLevelRewardCsReq', claim_wire(3), False),
                    ('GetLevelRewardCsReq', claim_wire(1), False), ('GetLevelRewardCsReq', claim_wire(71), False),
                    ('GetLevelRewardCsReq', bytes([64, 3]), False)])
                assert [r['retcode'] for r in replies] == [2107, 2108, 2108, 5]
                assert duplicate['basic'] == after3['basic'] and duplicate['bag'] == after3['bag']
                levels = json.loads((ROOT / 'resources/4.6/ExcelOutput/PlayerLevelConfig.json').read_text('utf-8'))
                rows = {r['RewardID']: r for r in json.loads((ROOT / 'resources/4.6/ExcelOutput/RewardData.json').read_text('utf-8')) if r}
                remaining = [r for r in levels if r['Level'] not in [1, 3]]
                for start in range(0, len(remaining), 50):
                    batch = remaining[start:start + 50]
                    replies, current = send([('GetLevelRewardCsReq', claim_wire(r['Level']), True) for r in batch])
                    for row, response in zip(batch, replies):
                        assert response['level'] == row['Level'] and reward(response) == payout(rows[row['LevelRewardID']])
                saved = current
                (directory / 'delivered-snapshot.json').write_text(json.dumps(saved,indent=2), 'utf-8')
                total = Counter()
                for row in levels:
                    if row.get('LevelRewardID'):
                        total.update(payout(rows[row['LevelRewardID']]))
                assert saved['basic'].get('hcoin', 0) == before['basic'].get('hcoin', 0) + total.pop(1)
                assert saved['basic'].get('scoin', 0) == before['basic'].get('scoin', 0) + total.pop(2)
                assert inventory(saved) == inventory(before) + total, 'Actual delivered inventory differs from original rewards'
            else:
                assert initial['uid'] == saved['uid']
                assert initial['basic'] == saved['basic'] and bag(initial) == bag(saved)
            replies, current = send([('GetLevelRewardTakenListCsReq', b'', True),
                                    ('GetLevelRewardCsReq', claim_wire(3), False)])
            assert replies[0]['level_reward_taken_list'] == list(range(2, 71))
            assert replies[1]['retcode'] == 2107
            assert current['basic'] == saved['basic'] and bag(current) == bag(saved)
            snapshots.append(dict(uid=current['uid'], taken=replies[0]['level_reward_taken_list']))
            stop_probe()
            if cycle == 0:
                reconnected = start_probe()
                (directory / 'reconnected-snapshot.json').write_text(json.dumps(reconnected,indent=2), 'utf-8')
                assert reconnected['uid'] == saved['uid']
                replies, current = send([('GetLevelRewardTakenListCsReq', b'', True), ('GetLevelRewardCsReq', claim_wire(3), False)])
                assert replies[0]['level_reward_taken_list'] == list(range(2, 71))
                assert current['basic'] == saved['basic'], ('basic changed', saved['basic'], current['basic'])
                assert bag(current) == bag(saved), ('bag changed', directory)
                stop_probe()
            stop_server()
        assert config.read_bytes() == original_config
        for path, expected in resource_hashes.items():
            assert hashlib.sha256((ROOT / 'resources/4.6' / path).read_bytes()).hexdigest() == expected
        result = dict(passed=True, jarSHA256=digest, runtimeDirectory=str(directory), rewardedLevels=69,
                      normalStops=stops, sameUidReconnect=True, sameUidRestart=True, productionModified=False,
                      clientAccepted=False, snapshots=snapshots, nativeRequests=len(results), results=results)
        args.output.write_text(json.dumps(result, ensure_ascii=False, indent=2), 'utf-8')
        print(json.dumps({k: result[k] for k in ['passed', 'rewardedLevels', 'normalStops', 'nativeRequests', 'runtimeDirectory']}))
    finally:
        for process in [probe, server]:
            if process and process.poll() is None:
                process.terminate()
                process.wait(timeout=20)
        for log in logs:
            log.close()


if __name__ == '__main__':
    main()
