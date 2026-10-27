"""在临时 H2 与一次性账号上验证 4.6 常用队伍及推荐查询，不使用真实存档。"""
import argparse
import hashlib
import http.cookiejar
import importlib.util
import json
import os
from pathlib import Path
import socket
import subprocess
import tempfile
import time
import urllib.request
import uuid

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('gm_smoke', ROOT / 'scripts/smoke-gm-console.py')
gm = importlib.util.module_from_spec(spec)
spec.loader.exec_module(gm)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, default=ROOT / 'build/preset-lineup-native.json')
    args = parser.parse_args()
    jar = ROOT / 'build/libs/hkrpg-server.jar'
    digest = hashlib.sha256(jar.read_bytes()).hexdigest()
    java = next((ROOT / '.tools').glob('jdk-21*/bin/java.exe'))
    runs = ROOT / 'build/smoke'
    runs.mkdir(parents=True, exist_ok=True)
    directory = Path(tempfile.mkdtemp(prefix='preset-lineup-', dir=runs))
    helpers = directory / 'helpers'
    helpers.mkdir()
    flags = subprocess.CREATE_NO_WINDOW if os.name == 'nt' else 0
    source = ROOT / 'scripts/fixtures/java/org/hkrpg/server/protocol/NativePresetLineupProbe.java'
    compiled = subprocess.run([str(java.with_name('javac.exe')), '-encoding', 'UTF-8', '-cp', str(jar),
                               '-d', str(helpers), str(source)], capture_output=True, text=True, encoding='utf-8',
                              errors='replace', creationflags=flags)
    if compiled.returncode:
        raise RuntimeError('Probe compilation failed: ' + compiled.stderr)
    http_port, mongo, game = gm.free_port(), gm.free_port(), gm.free_port(socket.SOCK_DGRAM)
    (directory / 'config.json').write_text(json.dumps({
        'protocol': {'wireVersion': '4.6.0'}, 'resourceDir': str(ROOT / 'resources/4.6'),
        'httpServer': {'bindAddress': '127.0.0.1', 'bindPort': http_port},
        'gameServer': {'bindAddress': '127.0.0.1', 'bindPort': game},
        'internalMongoServer': {'address': '127.0.0.1', 'port': mongo, 'filePath': './data/database.mv'},
    }), encoding='utf-8')
    base = f'http://127.0.0.1:{http_port}'
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}),
                                         urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
    java_flags = ['-Dfile.encoding=UTF-8', '-Dio.netty.eventLoopThreads=2',
                  '-Djdk.net.unixdomain.tmpdir=' + str(directory / 'tcp-fallback')]
    account_name = 'preset_' + uuid.uuid4().hex + '@example.invalid'
    phases, server, logs, normal_stops = [], None, [], 0

    def request(route, body=None):
        req = urllib.request.Request(base + route, data=None if body is None else json.dumps(body).encode(),
                                     headers={'Content-Type': 'application/json', 'Origin': base})
        with opener.open(req, timeout=30) as response:
            return json.load(response)

    def stop(strict=True):
        nonlocal server, normal_stops
        if server is None:
            return
        if server.poll() is None:
            server.stdin.write(b'stop\n')
            server.stdin.flush()
            try:
                server.wait(timeout=45)
            except subprocess.TimeoutExpired:
                server.terminate()
                server.wait(timeout=15)
        expected = 1000 if os.name == 'nt' else 232
        code = server.returncode
        server.stdin.close()
        server = None
        if strict:
            assert code == expected, f'Server did not stop normally: {code}'
            normal_stops += 1

    try:
        for iteration, modes in enumerate((('initial', 'reconnect'), ('after-restart',), ('final',))):
            log = (directory / f'server-{iteration}.log').open('wb')
            logs.append(log)
            server = subprocess.Popen([str(java), *java_flags, '-Xmx1536m', '-jar', str(jar), '--no-handbook'],
                                      cwd=directory, stdin=subprocess.PIPE, stdout=log, stderr=subprocess.STDOUT,
                                      creationflags=flags)
            gm.wait_for(lambda: server.poll() is None and request('/health/ready')['ready'])
            for mode in modes:
                account = request('/hkrpg_cn/mdk/shield/api/login',
                                  {'account': account_name, 'password': 'disposable-preset-smoke'})['data']['account']
                combo = request('/hkrpg_cn/combo/granter/login/v2/login',
                                {'data': json.dumps({'uid': account['uid'], 'token': account['token']})})
                credentials = {'accountUid': account['uid'], 'comboToken': combo['data']['combo_token'], 'mode': mode}
                probe = subprocess.run([str(java), *java_flags, '-cp', str(helpers) + os.pathsep + str(jar),
                                        'org.hkrpg.server.protocol.NativePresetLineupProbe', str(game)],
                                       input=json.dumps(credentials), cwd=directory, capture_output=True, text=True,
                                       encoding='utf-8', errors='replace', timeout=90, creationflags=flags)
                if probe.returncode:
                    raise RuntimeError(f'KCP probe {mode} failed: {probe.stderr}')
                row = json.loads(next(line for line in probe.stdout.splitlines() if line.startswith('{')))
                phases.append(row)
                time.sleep(1)  # Let the owned session finish its normal logout before reconnecting.
            stop()
        assert len({row['uid'] for row in phases}) == 1
        assert all(row['unchangedTeamSnapshots'] == phases[0]['unchangedTeamSnapshots'] for row in phases)
        assert hashlib.sha256(jar.read_bytes()).hexdigest() == digest
        result = {'passed': True, 'clientAccepted': False, 'isolated': True, 'jarSha256': digest,
                  'sessions': len(phases), 'normalStops': normal_stops,
                  'requests': sum(row['requests'] for row in phases), 'phases': phases, 'directory': str(directory)}
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
        print(json.dumps({k: v for k, v in result.items() if k != 'phases'}, ensure_ascii=False))
    finally:
        stop(False)
        for log in logs:
            log.close()


if __name__ == '__main__':
    main()
