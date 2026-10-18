"""隔离配置/H2下重建客户端初始化命令顺序；正文为合成值，不启动或操作游戏客户端。"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import socket
import subprocess
import tempfile
import threading
import time
import urllib.error
import urllib.request

ROOT = Path(__file__).resolve().parents[1]


class DatagramBoundary:
    """只在隔离合成连接中转发UDP；丢弃超界数据报，仅报告尺寸与计数。"""
    def __init__(self, server_port, limit):
        self.server = ('127.0.0.1', server_port)
        self.limit = limit
        self.socket = socket.socket(type=socket.SOCK_DGRAM)
        self.socket.bind(('127.0.0.1', 0))
        self.socket.settimeout(.2)
        self.port = self.socket.getsockname()[1]
        self.stopped = threading.Event()
        self.largest_server = self.largest_client = self.dropped = 0
        self.error = None
        self.thread = threading.Thread(target=self.forward, daemon=True)

    def forward(self):
        client = None
        while not self.stopped.is_set():
            try:
                data, peer = self.socket.recvfrom(65535)
                if peer == self.server:
                    self.largest_server = max(self.largest_server, len(data))
                    if len(data) > self.limit:
                        self.dropped += 1
                        continue
                    if client is not None:
                        self.socket.sendto(data, client)
                else:
                    client = peer
                    self.largest_client = max(self.largest_client, len(data))
                    self.socket.sendto(data, self.server)
            except socket.timeout:
                continue
            except OSError as error:
                if not self.stopped.is_set():
                    self.error = type(error).__name__
                break

    def __enter__(self):
        self.thread.start()
        return self

    def __exit__(self, *_):
        self.stopped.set()
        self.socket.close()
        self.thread.join(2)
        if self.thread.is_alive() or self.error:
            raise RuntimeError('隔离UDP转发未正常退出: ' + str(self.error))

    def summary(self):
        return dict(receiveDatagramLimit=self.limit, largestServerDatagram=self.largest_server,
                    largestClientDatagram=self.largest_client, oversizedServerDatagrams=self.dropped,
                    payloadsRecorded=False)


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def free_port(kind=socket.SOCK_STREAM):
    with socket.socket(type=kind) as stream:
        stream.bind(('127.0.0.1', 0))
        return stream.getsockname()[1]


def run(jar, output):
    jar, output = jar.resolve(strict=True), output.resolve()
    if not output.is_relative_to(ROOT / 'build') or output.exists():
        raise ValueError('报告必须是build内尚不存在的文件')
    digest = sha(jar)
    runs = ROOT / 'build/smoke'
    runs.mkdir(parents=True, exist_ok=True)
    directory = Path(tempfile.mkdtemp(prefix='login-initial-', dir=runs))
    java = next((ROOT / '.tools').glob('jdk-21*/bin/java.exe'))
    javac = java.with_name('javac.exe')
    helper = ROOT / 'scripts/fixtures/java/org/hkrpg/server/protocol/NativeLoginInitialProbe.java'
    input_paths = [Path(__file__).resolve(), helper]
    source_hashes = {str(path.relative_to(ROOT)): sha(path) for path in input_paths}
    helpers = directory / 'helpers'
    helpers.mkdir()
    subprocess.run([str(javac), '-encoding', 'UTF-8', '-cp', str(jar), '-d', str(helpers), str(helper)],
                   check=True, capture_output=True, text=True)
    http, mongo, game = free_port(), free_port(), free_port(socket.SOCK_DGRAM)
    while mongo == http:
        mongo = free_port()
    config = directory / 'config.json'
    config.write_text(json.dumps({'protocol': {'wireVersion': '4.6.0'},
        'resourceDir': str(ROOT / 'resources/4.6'),
        'httpServer': {'bindAddress': '127.0.0.1', 'bindPort': http},
        'gameServer': {'bindAddress': '127.0.0.1', 'bindPort': game},
        'internalMongoServer': {'address': '127.0.0.1', 'port': mongo, 'filePath': './data/database.mv'}}),
        encoding='utf-8')
    config_sha = sha(config)
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    base = f'http://127.0.0.1:{http}'

    def request(path, body=None):
        data = json.dumps(body).encode() if body is not None else None
        message = urllib.request.Request(base + path, data, {'Content-Type': 'application/json'})
        with opener.open(message, timeout=5) as response:
            return json.load(response)

    flags = subprocess.CREATE_NO_WINDOW if os.name == 'nt' else 0
    summaries, identities, normal_stops, boundaries = [], [], 0, []
    policy_path = directory / 'data/ChessRogueGuideRoutes.json'
    policy_snapshots = []

    def check_policy(stage):
        content = policy_path.read_bytes()
        policy = json.loads(content)
        snapshot = dict(stage=stage, enabled=policy.get('enabled'),
                        sha256=hashlib.sha256(content).hexdigest())
        policy_snapshots.append(snapshot)
        if snapshot['enabled'] is not False:
            raise RuntimeError('隔离 ChessRogueGuideRoutes 实际配置必须保持 enabled=false')
        if snapshot['sha256'] != policy_snapshots[0]['sha256']:
            raise RuntimeError('隔离 ChessRogueGuideRoutes 在验收期间被修改')

    def check_inputs():
        current = {str(path.relative_to(ROOT)): sha(path) for path in input_paths}
        if current != source_hashes:
            raise RuntimeError('隔离初始化探针源码在验收期间被修改')
        return current

    try:
        for iteration in range(2):
            check_inputs()
            with (directory / f'server-{iteration}.log').open('w', encoding='utf-8') as log:
                process = subprocess.Popen([str(java), '-Xmx1536m', '-Dfile.encoding=UTF-8',
                    '-Dio.netty.eventLoopThreads=2', '-Djdk.net.unixdomain.tmpdir=' + str(directory / 'tcp-fallback'),
                    '-jar', str(jar), '--no-handbook'], cwd=directory, stdin=subprocess.PIPE,
                    stdout=log, stderr=subprocess.STDOUT, creationflags=flags)
                try:
                    deadline = time.monotonic() + 90
                    while True:
                        if process.poll() is not None:
                            raise RuntimeError('隔离服务提前退出: ' + str(directory))
                        try:
                            if request('/health/ready')['ready']:
                                break
                        except (OSError, urllib.error.URLError):
                            pass
                        if time.monotonic() >= deadline:
                            raise RuntimeError('隔离服务启动超时: ' + str(directory))
                        time.sleep(.2)
                    check_policy(f'ready-{iteration}')
                    login = request('/hkrpg_cn/mdk/shield/api/login', {
                        'account': 'synthetic-initial-query', 'password': 'first' if iteration == 0 else 'different'})
                    assert login['retcode'] == 0
                    account = login['data']['account']
                    identities.append(account['uid'])
                    combo = request('/hkrpg_cn/combo/granter/login/v2/login', {'data': json.dumps({
                        'uid': account['uid'], 'token': account['token']})})
                    assert combo['retcode'] == 0
                    with DatagramBoundary(game, 1200) as boundary:
                        probe = subprocess.run([str(java), '-Dio.netty.eventLoopThreads=2',
                            '-Djdk.net.unixdomain.tmpdir=' + str(directory / 'tcp-fallback'),
                            '-cp', str(helpers) + os.pathsep + str(jar),
                            'org.hkrpg.server.protocol.NativeLoginInitialProbe', str(boundary.port)],
                            input=json.dumps({'accountUid': account['uid'], 'comboToken': combo['data']['combo_token']}),
                            cwd=directory, capture_output=True, text=True, encoding='utf-8', timeout=40,
                            creationflags=flags)
                        # Preserve the exact rejected response even if the UDP relay sees the
                        # early-exiting probe's socket reset while closing its context.
                        (directory / f'probe-{iteration}.log').write_text(probe.stdout + probe.stderr, encoding='utf-8')
                    boundaries.append(boundary.summary())
                    if probe.returncode:
                        raise RuntimeError('初始化连接检查失败，见 ' + str(directory / f'probe-{iteration}.log'))
                    assert boundary.dropped == 0 and boundary.largest_server <= 1200
                    assert boundary.largest_client <= 1200
                    summaries.append(json.loads(next(line for line in probe.stdout.splitlines()
                                                     if line.startswith('{"'))))
                    assert summaries[-1]['passed']
                    assert summaries[-1]['rogueInfoRetcodeZero'] is True
                    assert summaries[-1]['chessRogueInitialProfile']['verified'] is True
                finally:
                    if process.poll() is None:
                        try:
                            process.stdin.write(b'stop\n')
                            process.stdin.flush()
                            process.wait(timeout=30)
                            assert process.returncode == (1000 if os.name == 'nt' else 1000 % 256)
                            normal_stops += 1
                            check_policy(f'stopped-{iteration}')
                        finally:
                            if process.poll() is None:
                                process.terminate()
                                process.wait(timeout=10)
                            process.stdin.close()
        assert normal_stops == 2 and identities[0] == identities[1]
        assert summaries[0].pop('playerUid') == summaries[1].pop('playerUid')
        assert sha(jar) == digest and sha(config) == config_sha
        final_source_hashes = check_inputs()
        report = dict(passed=True, runtimeSHA256=digest, runtimeDirectory=str(directory),
                      configSHA256=config_sha, normalStops=normal_stops,
                      sameAccountWithDifferentPasswordAfterH2Restart=True, snapshots=summaries,
                      samePlayerUidAfterH2Restart=True,
                      transportBoundaries=boundaries,
                      sourceInputsBefore=source_hashes, sourceInputsAfter=final_source_hashes,
                      sourceInputsUnchanged=True,
                      chessRoguePolicy=dict(path=str(policy_path), enabled=False,
                          snapshots=policy_snapshots, unchanged=True),
                      chessRogueFreshProfileVerifiedBeforeAndAfterH2Restart=True,
                      credentialsOmitted=True, clientLoginVerified=False)
    except Exception as error:
        report = dict(passed=False, runtimeSHA256=digest, runtimeDirectory=str(directory),
                      normalStops=normal_stops, transportBoundaries=boundaries,
                      sourceInputsBefore=source_hashes,
                      chessRoguePolicy=dict(path=str(policy_path), snapshots=policy_snapshots),
                      error=str(error), clientLoginVerified=False)
        raise
    finally:
        output.parent.mkdir(parents=True, exist_ok=True)
        output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    return report


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--jar', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    result = run(args.jar, args.output)
    print(json.dumps(dict(passed=result['passed'], normalStops=result['normalStops'],
                          runtimeDirectory=result['runtimeDirectory'], clientLoginVerified=False)))
