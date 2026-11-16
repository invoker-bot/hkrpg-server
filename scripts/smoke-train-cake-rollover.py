"""隔离账号跨周保持连接，验证每日通知、旧尾巴清理和新箱子；不操作正式存档。"""
import argparse
import datetime
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import socket
import subprocess
import tempfile
import time
import urllib.error
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('rollover_transport', ROOT / 'scripts/smoke-login-initial.py')
transport = importlib.util.module_from_spec(spec)
spec.loader.exec_module(transport)


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def port(kind=socket.SOCK_STREAM):
    while True:
        with socket.socket(type=kind) as sock:
            sock.bind(('127.0.0.1', 0))
            value = sock.getsockname()[1]
        if value not in (18080, 23311, 27017, 27079):
            return value


def run(jar, output):
    jar, output = jar.resolve(strict=True), output.resolve()
    if not jar.is_relative_to(ROOT / 'build') or not output.is_relative_to(ROOT / 'build') or output.exists():
        raise ValueError('只允许 build 内的冻结程序和未存在的报告')
    runs = ROOT / 'build/smoke'
    runs.mkdir(parents=True, exist_ok=True)
    directory = Path(tempfile.mkdtemp(prefix='train-cat-box-rollover-', dir=runs))
    classes = directory / 'helpers'
    classes.mkdir()
    java = next((ROOT / '.tools').glob('jdk-21*/bin/java.exe'))
    probe = ROOT / 'scripts/fixtures/java/org/hkrpg/server/protocol/NativeTrainCakeCatchProbe.java'
    inspector = ROOT / 'scripts/fixtures/java/org/hkrpg/server/gameplay/TrainCakeCatchFixtureInspector.java'
    fixture = json.loads((ROOT / 'src/test/resources/fixtures/train-cake-tail-slots-4.6.json').read_text('utf-8'))
    original = ROOT / 'resources/4.6' / fixture['sourceFile']
    if sha(original) != fixture['sourceSHA256']:
        raise ValueError('躲藏点来源资源与夹具不一致')
    frozen = {str(path): sha(path) for path in (jar, probe, inspector, original, Path(__file__).resolve())}
    flags = subprocess.CREATE_NO_WINDOW if os.name == 'nt' else 0
    options = ['-Dfile.encoding=UTF-8', '-Duser.timezone=Asia/Shanghai', '-Dio.netty.eventLoopThreads=2',
               '-Djdk.net.unixdomain.tmpdir=' + str(directory / 'tcp-fallback')]
    compiled = subprocess.run([str(java.with_name('javac.exe')), '-encoding', 'UTF-8', '-cp', str(jar),
                               '-d', str(classes), str(probe), str(inspector)], capture_output=True, creationflags=flags)
    (directory / 'javac.log').write_bytes(compiled.stdout + compiled.stderr)
    if compiled.returncode:
        raise RuntimeError('隔离探针编译失败：' + str(directory / 'javac.log'))
    http, mongo, game = port(), port(), port(socket.SOCK_DGRAM)
    while len({http, mongo, game}) != 3:
        mongo, game = port(), port(socket.SOCK_DGRAM)
    tz = datetime.timezone(datetime.timedelta(hours=8))
    local = datetime.datetime.now(tz)
    date = local.date() + datetime.timedelta(days=(7 - local.weekday()) % 7)
    boundary = datetime.datetime.combine(date, datetime.time(4), tz)
    if boundary <= local:
        boundary += datetime.timedelta(days=7)
    start = boundary - datetime.timedelta(seconds=110)
    next_day = (boundary.date() - datetime.date(1970, 1, 1)).days
    config = directory / 'config.json'
    config.write_text(json.dumps({'protocol': {'wireVersion': '4.6.0'}, 'resourceDir': str(ROOT / 'resources/4.6'),
        'httpServer': {'bindAddress': '127.0.0.1', 'bindPort': http},
        'gameServer': {'bindAddress': '127.0.0.1', 'bindPort': game},
        'internalMongoServer': {'address': '127.0.0.1', 'port': mongo, 'filePath': './data/database.mv'},
        'serverTime': {'spoofTime': True, 'spoofDate': start.strftime('%d-%m-%Y %H:%M:%S')}}), 'utf-8')
    frozen[str(config)] = sha(config)
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))

    def request(route, body=None):
        req = urllib.request.Request(f'http://127.0.0.1:{http}' + route,
            data=None if body is None else json.dumps(body).encode(), headers={'Content-Type': 'application/json'})
        with opener.open(req, timeout=5) as response:
            return json.load(response)

    def wait_for(server, ready):
        deadline = time.monotonic() + 180
        while time.monotonic() < deadline:
            if server.poll() is not None:
                raise RuntimeError('隔离服务提前退出：' + str(directory))
            try:
                if ready():
                    return
            except (OSError, urllib.error.URLError):
                pass
            time.sleep(.2)
        raise RuntimeError('隔离服务状态等待超时')

    def inspect(uid, mode):
        child = subprocess.run([str(java), *options, '-cp', str(classes) + os.pathsep + str(jar),
            'org.hkrpg.server.gameplay.TrainCakeCatchFixtureInspector'], cwd=directory,
            input=json.dumps({'directory': str(directory), 'uid': uid, 'mode': mode}),
            text=True, encoding='utf-8', capture_output=True, timeout=25, creationflags=flags)
        (directory / ('inspect-' + mode + '.log')).write_text(child.stdout + child.stderr, encoding='utf-8')
        if child.returncode:
            raise RuntimeError('隔离存档检查失败：' + mode)
        return json.loads(next(line for line in reversed(child.stdout.splitlines()) if line.startswith('{')))

    report = {'passed': False, 'runtimeSHA256': sha(jar), 'runtimeDirectory': str(directory),
              'clientGameplayVerified': False, 'credentialsOmitted': True,
              'simulatedBoundary': boundary.isoformat(), 'nextDay': next_day, 'normalStops': 0}
    server = None
    try:
        with (directory / 'server.log').open('wb') as log:
            server = subprocess.Popen([str(java), *options, '-Xmx1536m', '-jar', str(jar), '--no-handbook'],
                cwd=directory, stdin=subprocess.PIPE, stdout=log, stderr=subprocess.STDOUT, creationflags=flags)
            try:
                wait_for(server, lambda: request('/health/ready')['ready'])
                results, boundaries, accounts = [], [], []
                for mode in ('locked', 'rollover'):
                    login = request('/hkrpg_cn/mdk/shield/api/login',
                                    {'account': 'synthetic-cake-rollover', 'password': 'isolated'})
                    if login.get('retcode') != 0:
                        raise RuntimeError('隔离账号登录失败')
                    account = login['data']['account']; accounts.append(account['uid'])
                    combo = request('/hkrpg_cn/combo/granter/login/v2/login',
                        {'data': json.dumps({'uid': account['uid'], 'token': account['token']})})
                    if combo.get('retcode') != 0:
                        raise RuntimeError('隔离 combo 失败')
                    control = {'mode': mode, 'accountUid': account['uid'],
                               'comboToken': combo['data']['combo_token'], 'nextDay': next_day}
                    with transport.DatagramBoundary(game, 1200) as wire:
                        child = subprocess.run([str(java), *options, '-cp', str(classes) + os.pathsep + str(jar),
                            'org.hkrpg.server.protocol.NativeTrainCakeCatchProbe', str(wire.port)], cwd=directory,
                            input=json.dumps(control), text=True, encoding='utf-8', capture_output=True,
                            timeout=160, creationflags=flags)
                    boundaries.append(wire.summary())
                    (directory / ('probe-' + mode + '.log')).write_text(child.stdout + child.stderr, encoding='utf-8')
                    if child.returncode or wire.dropped or wire.largest_server > 1200 or wire.largest_client > 1200:
                        raise RuntimeError('隔离跨周流程失败：' + str(directory / ('probe-' + mode + '.log')))
                    result = json.loads(next(line for line in reversed(child.stdout.splitlines()) if line.startswith('{')))
                    results.append(result)
                    wait_for(server, lambda: request('/status/server')['status']['playerCount'] == 0)
                    saved = inspect(result['playerUid'], 'unlock' if mode == 'locked' else 'read')
                    if mode == 'rollover' and ((saved.get('catBox') or {}).get('counts') or {}) != {'10': 1}:
                        raise RuntimeError('跨周后隔离存档数量不一致')
                if len(set(accounts)) != 1 or results[0]['playerUid'] != results[1]['playerUid']:
                    raise RuntimeError('隔离账号重连身份不一致')
                report.update(snapshots=results, boundaries=boundaries,
                              nativeRequests=sum(row['nativeRequests'] for row in results))
            finally:
                if server.poll() is None:
                    server.stdin.write(b'stop\n'); server.stdin.flush(); server.wait(timeout=45)
                    if server.returncode != (1000 if os.name == 'nt' else 232):
                        raise RuntimeError('隔离服务未正常停止')
                    report['normalStops'] += 1
                else:
                    raise RuntimeError('隔离服务在 stop 前退出')
                server.stdin.close()
        if frozen != {name: sha(Path(name)) for name in frozen}:
            raise RuntimeError('冻结输入变化')
        report['passed'] = True
    finally:
        if server is not None and server.poll() is None:
            server.terminate(); server.wait(timeout=15)
        output.parent.mkdir(parents=True, exist_ok=True)
        output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n', 'utf-8')
    return report


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--jar', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    result = run(args.jar, args.output)
    print(json.dumps({'passed': result['passed'], 'nativeRequests': result['nativeRequests'],
                      'normalStops': result['normalStops'], 'clientGameplayVerified': False}))
