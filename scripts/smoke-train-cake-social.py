"""隔离 H2 和三个合成账号验证猫猫糕联机；不启动或操作游戏客户端。"""
import argparse
from contextlib import ExitStack
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
spec = importlib.util.spec_from_file_location('social_transport', ROOT / 'scripts/smoke-login-initial.py')
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
    directory = Path(tempfile.mkdtemp(prefix='train-cat-social-', dir=runs))
    helpers = directory / 'helpers'
    helpers.mkdir()
    java = next((ROOT / '.tools').glob('jdk-21*/bin/java.exe'))
    probe = ROOT / 'scripts/fixtures/java/org/hkrpg/server/protocol/NativeTrainCakeSocialProbe.java'
    inspector = ROOT / 'scripts/fixtures/java/org/hkrpg/server/gameplay/TrainCakeSocialFixtureInspector.java'
    frozen = {str(path): sha(path) for path in (jar, probe, inspector, Path(__file__).resolve())}
    flags = subprocess.CREATE_NO_WINDOW if os.name == 'nt' else 0
    options = ['-Dfile.encoding=UTF-8', '-Dio.netty.eventLoopThreads=2',
               '-Djdk.net.unixdomain.tmpdir=' + str(directory / 'tcp-fallback')]
    subprocess.run([str(java.with_name('javac.exe')), '-encoding', 'UTF-8', '-cp', str(jar),
                    '-d', str(helpers), str(probe), str(inspector)], check=True, capture_output=True)
    http, mongo, game = port(), port(), port(socket.SOCK_DGRAM)
    while len({http, mongo, game}) != 3:
        mongo, game = port(), port(socket.SOCK_DGRAM)
    config = directory / 'config.json'
    config.write_text(json.dumps({'protocol': {'wireVersion': '4.6.0'},
        'resourceDir': str(ROOT / 'resources/4.6'),
        'httpServer': {'bindAddress': '127.0.0.1', 'bindPort': http},
        'gameServer': {'bindAddress': '127.0.0.1', 'bindPort': game},
        'internalMongoServer': {'address': '127.0.0.1', 'port': mongo, 'filePath': './data/database.mv'}}), 'utf-8')
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

    def inspect(uid, mode, label):
        child = subprocess.run([str(java), *options, '-cp', str(helpers) + os.pathsep + str(jar),
            'org.hkrpg.server.gameplay.TrainCakeSocialFixtureInspector'], cwd=directory,
            input=json.dumps({'directory': str(directory), 'uid': uid, 'mode': mode}),
            text=True, encoding='utf-8', capture_output=True, timeout=30, creationflags=flags)
        (directory / (label + '.log')).write_text(child.stdout + child.stderr, encoding='utf-8')
        if child.returncode:
            raise RuntimeError('隔离存档检查失败：' + label)
        return json.loads(next(line for line in reversed(child.stdout.splitlines()) if line.startswith('{')))

    report = {'passed': False, 'runtimeSHA256': sha(jar), 'runtimeDirectory': str(directory),
              'snapshots': [], 'independentPlayerReads': [], 'boundaries': [], 'normalStops': 0,
              'clientGameplayVerified': False, 'credentialsOmitted': True,
              'syntheticGateFixture': '仅隔离账号预先准备 4020800 和 402080005 完成状态'}
    accounts = []
    player_uids = None

    def run_probe(server, mode):
        nonlocal player_uids
        credentials = []
        for name in ('owner', 'visitor-a', 'visitor-b'):
            login = request('/hkrpg_cn/mdk/shield/api/login',
                            {'account': 'synthetic-train-social-' + name, 'password': 'isolated-' + name})
            if login.get('retcode') != 0:
                raise RuntimeError('隔离账号登录失败')
            account = login['data']['account']
            combo = request('/hkrpg_cn/combo/granter/login/v2/login',
                            {'data': json.dumps({'uid': account['uid'], 'token': account['token']})})
            if combo.get('retcode') != 0:
                raise RuntimeError('隔离 combo 登录失败')
            credentials.append({'accountUid': account['uid'], 'comboToken': combo['data']['combo_token']})
        control = {'mode': mode, 'accounts': credentials}
        if player_uids:
            control['ownerUid'] = player_uids[0]
        with ExitStack() as stack:
            boundaries = [stack.enter_context(transport.DatagramBoundary(game, 1200)) for _ in range(3)]
            child = subprocess.run([str(java), *options, '-cp', str(helpers) + os.pathsep + str(jar),
                'org.hkrpg.server.protocol.NativeTrainCakeSocialProbe', *[str(b.port) for b in boundaries]],
                cwd=directory, input=json.dumps(control), text=True, encoding='utf-8', capture_output=True,
                timeout=150, creationflags=flags)
        report['boundaries'].extend(b.summary() for b in boundaries)
        log = directory / ('probe-' + mode + '.log')
        log.write_text(child.stdout + child.stderr, encoding='utf-8')
        if child.returncode:
            raise RuntimeError('原生联机探针失败：' + str(log))
        if any(b.dropped or b.largest_server > 1200 or b.largest_client > 1200 for b in boundaries):
            raise RuntimeError('原生联机数据报越过 1200 字节边界')
        result = json.loads(next(line for line in reversed(child.stdout.splitlines()) if line.startswith('{')))
        if mode == 'bootstrap':
            player_uids = result['playerUids']
            if len(set(player_uids)) != 3 or min(player_uids) <= 0:
                raise RuntimeError('合成账号没有产生三个独立玩家')
        if mode in ('exercise', 'verify') and result['playerUids'] != player_uids:
            raise RuntimeError('重连后的玩家身份发生变化')
        report['snapshots'].append(result)
        wait_for(server, lambda: request('/status/server')['status']['playerCount'] == 0)
        print(json.dumps({'mode': mode, 'nativeRequests': result['nativeRequests']}), flush=True)

    try:
        for iteration in range(2):
            with (directory / f'server-{iteration}.log').open('wb') as log:
                server = subprocess.Popen([str(java), *options, '-Xmx1536m', '-jar', str(jar), '--no-handbook'],
                    cwd=directory, stdin=subprocess.PIPE, stdout=log, stderr=subprocess.STDOUT, creationflags=flags)
                try:
                    wait_for(server, lambda: request('/health/ready')['ready'])
                    if iteration == 0:
                        run_probe(server, 'bootstrap')
                        for uid in player_uids:
                            inspect(uid, 'unlock', 'unlock-' + str(uid))
                        run_probe(server, 'configure')
                        run_probe(server, 'exercise')
                        for uid in player_uids:
                            report['independentPlayerReads'].append(inspect(uid, 'read', 'before-restart-' + str(uid)))
                    else:
                        run_probe(server, 'verify')
                        for uid in player_uids:
                            saved = inspect(uid, 'read', 'after-restart-' + str(uid))
                            report['independentPlayerReads'].append(saved)
                            if saved['returnBookmark'] is not None or saved['entryId'] != 100000302:
                                raise RuntimeError('重连后未恢复原来的车厢位置')
                finally:
                    try:
                        if server.poll() is None:
                            server.stdin.write(b'stop\n'); server.stdin.flush(); server.wait(timeout=45)
                            if server.returncode != (1000 if os.name == 'nt' else 232):
                                raise RuntimeError('隔离服务未正常停止')
                            report['normalStops'] += 1
                        else:
                            raise RuntimeError('隔离服务在 stop 前退出')
                    finally:
                        if server.poll() is None:
                            server.terminate(); server.wait(timeout=15)
                        server.stdin.close()
        if frozen != {name: sha(Path(name)) for name in frozen}:
            raise RuntimeError('冻结输入发生变化')
        if report['normalStops'] != 2 or len(report['snapshots']) != 4:
            raise RuntimeError('隔离重启链路不完整')
        report['nativeRequests'] = sum(row['nativeRequests'] for row in report['snapshots'])
        report['passed'] = True
    finally:
        output.parent.mkdir(parents=True, exist_ok=True)
        output.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding='utf-8')
    return report


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--jar', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    print(json.dumps(run(args.jar, args.output), ensure_ascii=False))
