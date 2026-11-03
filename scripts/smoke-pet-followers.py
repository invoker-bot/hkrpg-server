"""隔离账号和 H2：正式 GM 发放宠物，原生召唤/切换/召回、请求关联、重连及两次正常重启。"""
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
    parser.add_argument('--jar', type=Path, default=ROOT / 'build/libs/hkrpg-server.jar')
    args = parser.parse_args()
    jar = args.jar.resolve(strict=True)
    artifact = hashlib.sha256(jar.read_bytes()).hexdigest()
    java = next((ROOT / '.tools').glob('jdk-21*/bin/java.exe'))
    runs = ROOT / 'build/smoke'
    runs.mkdir(parents=True, exist_ok=True)
    directory = Path(tempfile.mkdtemp(prefix='pet-followers-', dir=runs))
    classes = directory / 'classes'
    classes.mkdir()
    flags = subprocess.CREATE_NO_WINDOW if os.name == 'nt' else 0
    helper = ROOT / 'scripts/fixtures/java/org/hkrpg/server/protocol/NativeGmPlayerProbe.java'
    compiled = subprocess.run([str(java.with_name('javac.exe')), '-encoding', 'UTF-8',
        '-cp', str(jar), '-d', str(classes), str(helper)], capture_output=True, creationflags=flags)
    (directory / 'javac.log').write_bytes(compiled.stdout + compiled.stderr)
    assert compiled.returncode == 0, directory / 'javac.log'
    http_port, mongo, game = gm.free_port(), gm.free_port(), gm.free_port(socket.SOCK_DGRAM)
    config = directory / 'config.json'
    config.write_text(json.dumps({'protocol': {'wireVersion': '4.6.0'},
        'resourceDir': str(ROOT / 'resources/4.6'),
        'httpServer': {'bindAddress': '127.0.0.1', 'bindPort': http_port},
        'gameServer': {'bindAddress': '127.0.0.1', 'bindPort': game},
        'internalMongoServer': {'address': '127.0.0.1', 'port': mongo,
                                'filePath': './data/database.mv'}}), encoding='utf-8')
    original_config = config.read_bytes()
    options = ['-Dfile.encoding=UTF-8', '-Dio.netty.eventLoopThreads=2',
               f'-Djdk.net.unixdomain.tmpdir={directory / "tcp-fallback"}']
    base = f'http://127.0.0.1:{http_port}'
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}),
        urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
    csrf = ''
    server = probe = None
    logs, steps, sessions = [], [], []
    uid = None
    stops = 0

    def request(route, body=None):
        req = urllib.request.Request(base + route,
            data=None if body is None else json.dumps(body).encode(),
            headers={'Content-Type': 'application/json', 'Origin': base, 'X-GM-CSRF': csrf})
        with opener.open(req, timeout=10) as response:
            return json.load(response)

    def snapshot():
        return json.loads((directory / 'snapshot.json').read_text('utf-8'))

    def start_probe(label):
        nonlocal probe, uid
        for name in ('quit', 'snapshot.json', 'equipment-request.json'):
            (directory / name).unlink(missing_ok=True)
        log = (directory / f'probe-{label}.log').open('wb')
        logs.append(log)
        probe = subprocess.Popen([str(java), *options, '-cp', str(classes) + os.pathsep + str(jar),
            'org.hkrpg.server.protocol.NativeGmPlayerProbe', str(game),
            str(directory / 'credentials.json'), str(directory)], cwd=directory,
            stdout=log, stderr=subprocess.STDOUT, creationflags=flags)
        first = gm.wait_for(snapshot, 45)
        if uid is None:
            uid = first['uid']
        assert first['uid'] == uid

    def stop_probe(strict=True):
        nonlocal probe
        if probe is None:
            return
        if (directory / 'snapshot.json').exists():
            sessions.append({'uid': snapshot()['uid'], 'requests': snapshot()['requests']})
        (directory / 'quit').touch()
        try:
            probe.wait(timeout=30)
            if strict:
                assert probe.returncode == 0, 'Owned KCP probe failed'
        finally:
            if probe.poll() is None:
                probe.terminate()
                probe.wait(timeout=15)
            probe = None

    def stop_server(strict=True):
        nonlocal server, stops
        stop_probe(strict)
        if server is None:
            return
        try:
            if server.poll() is None:
                server.stdin.write(b'stop\n')
                server.stdin.flush()
                server.wait(timeout=45)
            if strict:
                assert server.returncode == (1000 if os.name == 'nt' else 232), 'Owned server did not stop normally'
                stops += 1
        finally:
            if server.poll() is None:
                server.terminate()
                server.wait(timeout=15)
            server.stdin.close()
            server = None

    def call(command, wire=b'', success=True, selected=None, notifications=0):
        key = uuid.uuid4().hex
        packet_id = 81 + len(steps)
        control = directory / 'equipment-request.json'
        temporary = control.with_suffix('.tmp')
        temporary.write_text(json.dumps({'requestId': key, 'command': command,
            'wire': base64.b64encode(wire).decode(), 'packetId': packet_id, 'success': success}), 'utf-8')
        temporary.replace(control)
        captured = gm.wait_for(lambda: (lambda s: s if s.get('equipmentReply', {}).get('requestId') == key else None)(snapshot()), 40)
        control.unlink()
        result = captured['equipmentReply']
        assert base64.b64decode(result['replyHeader']) == bytes([8, packet_id])
        assert len(result['petChanges']) == notifications, result
        response = result['response']
        assert (response.get('retcode', 0) == 0) == success, response
        if selected is not None:
            state = response.get('PLKBLNMOOPH', {})
            assert state.get('ODNGMIJLKKA', 0) == selected, response
            if selected:
                assert state.get('LLGIIPALELD') == 'EOGNOEJHAGN_OJHOIBAFDPB', response
            if notifications:
                change = result['petChanges'][0]['DGNGMJMPFGK'][0]
                assert 'GJALNEHICDE' in change, change
                assert change['GJALNEHICDE'].get('ODNGMIJLKKA', 0) == selected
        steps.append({'command': command, 'success': success, 'selected': selected,
            'matchedPacketId': packet_id, 'reply': result})
        return response

    def query(expected, owned):
        response = call('GetPetDataCsReq', selected=expected)
        assert sorted(response.get('unlocked_pet_id', [])) == owned, response

    def summon(id, **kwargs):
        # Pinned native request 1/13; normal pet IDs here are exactly two varint bytes.
        wire = bytes([8, (id & 127) | 128, id >> 7, 104, 1])
        return call('SummonPetCsReq', wire, **kwargs)

    try:
        for iteration in range(3):
            log = (directory / f'server-{iteration}.log').open('wb')
            logs.append(log)
            server = subprocess.Popen([str(java), *options, '-Xmx1536m', '-jar', str(jar),
                '--no-handbook'], cwd=directory, stdin=subprocess.PIPE, stdout=log,
                stderr=subprocess.STDOUT, creationflags=flags)
            gm.wait_for(lambda: server.poll() is None and request('/health/ready')['ready'], 120)
            csrf = request('/gm/api/session', {})['data']['csrf']
            account = request('/hkrpg_cn/mdk/shield/api/login',
                {'account': 'isolated-pet-followers', 'password': 'local-test'})['data']['account']
            combo = request('/hkrpg_cn/combo/granter/login/v2/login',
                {'data': json.dumps({'uid': account['uid'], 'token': account['token']})})
            (directory / 'credentials.json').write_text(json.dumps({'accountUid': account['uid'],
                'comboToken': combo['data']['combo_token']}), 'utf-8')
            start_probe(str(iteration))
            if iteration == 0:
                query(0, [])
                summon(1001, success=False, selected=0)
                for item in (251001,251002):
                    operation = request('/gm/api/operate', {'requestId': str(uuid.uuid4()),
                        'uid': uid, 'action': 'grant', 'itemId': item, 'amount': 1, 'level': 0, 'rank': 0})['data']
                    assert operation['state'] == 'completed', operation
                query(0, [1001,1002])
                summon(1001, selected=1001, notifications=1)
                summon(1001, selected=1001)
                summon(1002, selected=1002, notifications=1)
                summon(1003, success=False, selected=1002)
                call('SummonPetCsReq', bytes.fromhex('08e9076802'), success=False, selected=1002)
                query(1002, [1001,1002])
                stop_probe()
                start_probe('reconnect')
                query(1002, [1001,1002])
            elif iteration == 1:
                query(1002, [1001,1002])
                call('RecallPetCsReq', selected=0, notifications=1)
                call('RecallPetCsReq', selected=0)
                query(0, [1001,1002])
            else:
                query(0, [1001,1002])
                summon(1001, selected=1001, notifications=1)
                call('RecallPetCsReq', selected=0, notifications=1)
                query(0, [1001,1002])
            stop_server()
        assert stops == 3 and len(sessions) == 4
        assert hashlib.sha256(jar.read_bytes()).hexdigest() == artifact
        assert config.read_bytes() == original_config
        report = dict(passed=True, jarSHA256=artifact, runtimeDirectory=str(directory), uid=uid,
            normalStops=stops, sameUIDH2ProcessRestarts=2, sessions=sessions, steps=steps,
            petRequests=len(steps), nativeRequests=sum(s['requests'] for s in sessions),
            requestCorrelationVerified=True, notificationHeadersEmpty=True,
            ownershipGrantedByActualInventory=True, operatorDataModified=False, clientGameplayVerified=False)
        (directory / 'result.json').write_text(json.dumps(report, ensure_ascii=False, indent=2)+'\n', 'utf-8')
        (ROOT / 'build/pet-followers-network.json').write_text(json.dumps(report, ensure_ascii=False, indent=2)+'\n', 'utf-8')
        print(json.dumps({k:v for k,v in report.items() if k not in ('steps','sessions')}, ensure_ascii=False))
    finally:
        stop_server(False)
        for log in logs:
            log.close()


if __name__ == '__main__':
    main()
