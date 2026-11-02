"""隔离 H2/账号验证列车进图、重复进入和重登不下发调试组；不操作正式玩家。"""
import argparse
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
spec = importlib.util.spec_from_file_location('train_debug_transport', ROOT / 'scripts/smoke-login-initial.py')
transport = importlib.util.module_from_spec(spec)
spec.loader.exec_module(transport)


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def port(kind=socket.SOCK_STREAM):
    with socket.socket(type=kind) as sock:
        sock.bind(('127.0.0.1', 0))
        return sock.getsockname()[1]


def run(jar, output):
    jar, output = jar.resolve(strict=True), output.resolve()
    if not jar.is_relative_to(ROOT / 'build') or not output.is_relative_to(ROOT / 'build') or output.exists():
        raise ValueError('使用 build 内冻结 JAR；报告须为 build 内尚不存在的文件')
    resources = ROOT / 'resources/4.6'
    fixture = json.loads((ROOT / 'src/test/resources/fixtures/train-debug-scene-groups-4.6.json').read_text('utf-8'))
    guarded = {name: sha(resources / name) for name in fixture['sourceFiles']}
    if guarded != fixture['sourceFiles']:
        raise ValueError('来源资源与回归夹具不一致')
    initial = json.loads((ROOT / 'src/test/resources/fixtures/train-party-initial-area-4.6.json').read_text('utf-8'))
    guarded[initial['sourceFile']] = sha(resources / initial['sourceFile'])
    if guarded[initial['sourceFile']] != initial['sourceSHA256']:
        raise ValueError('初始房间来源资源与回归夹具不一致')
    runs = ROOT / 'build/smoke'
    runs.mkdir(parents=True, exist_ok=True)
    directory = Path(tempfile.mkdtemp(prefix='train-debug-', dir=runs))
    java = next((ROOT / '.tools').glob('jdk-21*/bin/java.exe'))
    flags = subprocess.CREATE_NO_WINDOW if os.name == 'nt' else 0
    http, mongo, game = port(), port(), port(socket.SOCK_DGRAM)
    while http == mongo:
        mongo = port()
    config = directory / 'config.json'
    config.write_text(json.dumps({'protocol': {'wireVersion': '4.6.0'}, 'resourceDir': str(resources),
        'httpServer': {'bindAddress': '127.0.0.1', 'bindPort': http},
        'gameServer': {'bindAddress': '127.0.0.1', 'bindPort': game},
        'internalMongoServer': {'address': '127.0.0.1', 'port': mongo, 'filePath': './data/database.mv'}}), 'utf-8')
    frozen = {'jar': sha(jar), 'config': sha(config)}
    probe = ROOT / 'scripts/fixtures/java/org/hkrpg/server/protocol/NativeTrainDebugGroupsProbe.java'
    frozen['probe'] = sha(probe)
    classes = directory / 'helpers'
    classes.mkdir()
    options = ['-Dfile.encoding=UTF-8', '-Dio.netty.eventLoopThreads=2',
               '-Djdk.net.unixdomain.tmpdir=' + str(directory / 'tcp-fallback')]
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))

    def request(route, body=None):
        req = urllib.request.Request(f'http://127.0.0.1:{http}' + route,
            data=None if body is None else json.dumps(body).encode(),
            headers={'Content-Type': 'application/json'})
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
        raise RuntimeError('隔离服务状态等待超时：' + str(directory))

    report = dict(passed=False, runtimeSHA256=frozen['jar'], runtimeDirectory=str(directory),
                  snapshots=[], boundaries=[], databaseFiles=[], normalStops=0,
                  clientGameplayVerified=False, credentialsOmitted=True)
    accounts = []
    try:
        compiled = subprocess.run([str(java.with_name('javac.exe')), '-encoding', 'UTF-8',
            '-cp', str(jar), '-d', str(classes), str(probe)], capture_output=True, creationflags=flags)
        (directory / 'javac.log').write_bytes(compiled.stdout + compiled.stderr)
        if compiled.returncode:
            raise RuntimeError('探针编译失败：' + str(directory / 'javac.log'))
        for iteration in range(2):
            with (directory / f'server-{iteration}.log').open('wb') as log:
                server = subprocess.Popen([str(java), *options, '-Xmx1536m', '-jar', str(jar), '--no-handbook'],
                    cwd=directory, stdin=subprocess.PIPE, stdout=log, stderr=subprocess.STDOUT,
                    creationflags=flags)
                try:
                    wait_for(server, lambda: request('/health/ready')['ready'])
                    for mode in (['exercise', 'verify'] if iteration == 0 else ['verify']):
                        index = len(report['snapshots'])
                        login = request('/hkrpg_cn/mdk/shield/api/login',
                            {'account': 'synthetic-train-debug', 'password': 'isolated-' + str(index)})
                        if login.get('retcode') != 0:
                            raise RuntimeError('隔离账号登录失败')
                        account = login['data']['account']
                        accounts.append(account['uid'])
                        combo = request('/hkrpg_cn/combo/granter/login/v2/login',
                            {'data': json.dumps({'uid': account['uid'], 'token': account['token']})})
                        if combo.get('retcode') != 0:
                            raise RuntimeError('隔离 combo 失败')
                        control = {'mode': mode, 'accountUid': account['uid'],
                                   'comboToken': combo['data']['combo_token']}
                        with transport.DatagramBoundary(game, 1200) as boundary:
                            child = subprocess.run([str(java), *options, '-cp',
                                str(classes) + os.pathsep + str(jar),
                                'org.hkrpg.server.protocol.NativeTrainDebugGroupsProbe', str(boundary.port)],
                                cwd=directory, input=json.dumps(control), text=True, encoding='utf-8',
                                capture_output=True, timeout=100, creationflags=flags)
                        report['boundaries'].append(boundary.summary())
                        probe_log = directory / f'probe-{index}.log'
                        probe_log.write_text(child.stdout + child.stderr, encoding='utf-8')
                        if child.returncode:
                            raise RuntimeError('原生列车加载回归失败：' + str(probe_log))
                        if boundary.dropped or boundary.largest_server > 1200 or boundary.largest_client > 1200:
                            raise RuntimeError('传输超出 1200 字节边界')
                        rows = [json.loads(line) for line in child.stdout.splitlines() if line.startswith('{"')]
                        snapshot = rows[-1]
                        if not snapshot.get('passed') or not snapshot.get('normalLogoutRequested'):
                            raise RuntimeError('探针未完成')
                        wait_for(server, lambda: request('/status/server')['status']['playerCount'] == 0)
                        snapshot.update(mode=mode, serverIteration=iteration, normalLogoutConfirmed=True)
                        report['snapshots'].append(snapshot)
                        print(json.dumps({'session': index, 'checkedScenes': snapshot['checkedScenes'],
                                          'nativeRequests': snapshot['nativeRequests']}), flush=True)
                finally:
                    try:
                        if server.poll() is None:
                            server.stdin.write(b'stop\n'); server.stdin.flush(); server.wait(timeout=45)
                            if server.returncode != (1000 if os.name == 'nt' else 232):
                                raise RuntimeError('隔离服务未正常停止')
                            report['normalStops'] += 1
                            database = directory / 'data/database.mv'
                            if not database.is_file() or not database.stat().st_size:
                                raise RuntimeError('隔离 H2 未保存')
                            report['databaseFiles'].append({'bytes': database.stat().st_size,
                                                            'sha256': sha(database)})
                        else:
                            raise RuntimeError('隔离服务在 stop 前退出')
                    finally:
                        if server.poll() is None:
                            server.terminate(); server.wait(timeout=15)
                        server.stdin.close()
        if report['normalStops'] != 2 or len(report['snapshots']) != 3 or len(set(accounts)) != 1:
            raise RuntimeError('重连/重启链路不完整')
        if len({s['playerUid'] for s in report['snapshots']}) != 1:
            raise RuntimeError('隔离玩家身份变化')
        if frozen != {'jar': sha(jar), 'config': sha(config), 'probe': sha(probe)}:
            raise RuntimeError('冻结输入变化')
        if guarded != {name: sha(resources / name) for name in guarded}:
            raise RuntimeError('来源资源变化')
        report.update(passed=True, nativeRequests=sum(s['nativeRequests'] for s in report['snapshots']),
                      checkedScenes=sum(s['checkedScenes'] for s in report['snapshots']),
                      resourceFilesUnchanged=len(guarded))
        return report
    finally:
        output.parent.mkdir(parents=True, exist_ok=True)
        output.write_text(json.dumps(report, indent=2) + '\n', encoding='utf-8')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--jar', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    result = run(args.jar, args.output)
    print(json.dumps({k: result[k] for k in ('passed', 'nativeRequests', 'checkedScenes', 'normalStops')}))
