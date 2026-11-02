"""隔离账号/KCP/H2验证音游原门槛、真实徽章与演出报酬；不操作正式玩家。"""
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
spec = importlib.util.spec_from_file_location('music_transport', ROOT / 'scripts/smoke-login-initial.py')
transport = importlib.util.module_from_spec(spec)
spec.loader.exec_module(transport)


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def free_port(kind=socket.SOCK_STREAM):
    with socket.socket(type=kind) as sock:
        sock.bind(('127.0.0.1', 0))
        return sock.getsockname()[1]


def run(jar, output):
    jar, output = jar.resolve(strict=True), output.resolve()
    if not jar.is_relative_to(ROOT / 'build') or not output.is_relative_to(ROOT / 'build') or output.exists():
        raise ValueError('只使用 build 内冻结产物和新的报告路径')
    resources = ROOT / 'resources/4.6'
    guarded_names = ['ExcelOutput/' + name + '.json' for name in ['MusicRhythmGroup', 'MusicRhythmLevel',
        'OfferingTypeConfig', 'OfferingLevelConfig', 'RewardData', 'ActivityPanel', 'FuncUnlockData']]
    guarded_names.append('Config/LevelOutput/SharedRuntimeGroup/Groups_P20101_F20101001/LevelGroup_P20101_F20101001_G102.json')
    guarded = {name: sha(resources / name) for name in guarded_names}
    (ROOT / 'build/smoke').mkdir(parents=True, exist_ok=True)
    directory = Path(tempfile.mkdtemp(prefix='music-performance-', dir=ROOT / 'build/smoke'))
    java = next((ROOT / '.tools').glob('jdk-21*/bin/java.exe'))
    javac = java.with_name('javac.exe')
    flags = subprocess.CREATE_NO_WINDOW if os.name == 'nt' else 0
    ports = set()
    while len(ports) < 3:
        value = free_port()
        if value not in (18080, 23311, 27017, 27079):
            ports.add(value)
    http, mongo = list(ports)[:2]
    game = free_port(socket.SOCK_DGRAM)
    while game in (http, mongo, 18080, 23311, 27017, 27079):
        game = free_port(socket.SOCK_DGRAM)
    config = directory / 'config.json'
    config.write_text(json.dumps({'protocol': {'wireVersion': '4.6.0'}, 'resourceDir': str(resources),
        'httpServer': {'bindAddress': '127.0.0.1', 'bindPort': http},
        'gameServer': {'bindAddress': '127.0.0.1', 'bindPort': game},
        'internalMongoServer': {'address': '127.0.0.1', 'port': mongo, 'filePath': './data/database.mv'}}), 'utf-8')
    (directory / 'data').mkdir()
    (directory / 'data/Gameplay46.json').write_text(json.dumps({'activities': [
        {'panelId': 50022, 'moduleId': 5002202, 'beginTime': 1, 'endTime': 4102444800}]}), 'utf-8')
    helper_root = ROOT / 'scripts/fixtures/java/org/hkrpg/server'
    helpers = [helper_root / 'protocol/NativeMusicRewardsProbe.java', helper_root / 'gameplay/MusicRewardsFixtureInspector.java']
    classes = directory / 'helpers'
    classes.mkdir()
    frozen = {str(p): sha(p) for p in [jar, config, *helpers]}
    options = ['-Dfile.encoding=UTF-8', '-Dio.netty.eventLoopThreads=2',
               '-Djdk.net.unixdomain.tmpdir=' + str(directory / 'tcp-fallback')]
    subprocess.run([str(javac), '-encoding', 'UTF-8', '-cp', str(jar), '-d', str(classes), *map(str, helpers)],
                   check=True, capture_output=True, text=True, creationflags=flags)
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
        raise RuntimeError('隔离服务状态超时')

    def inspect(uid, mode, label):
        child = subprocess.run([str(java), *options, '-cp', str(classes) + os.pathsep + str(jar),
            'org.hkrpg.server.gameplay.MusicRewardsFixtureInspector'], cwd=directory,
            input=json.dumps({'directory': str(directory), 'uid': uid, 'mode': mode}),
            text=True, encoding='utf-8', capture_output=True, timeout=30, creationflags=flags)
        (directory / (label + '.log')).write_text(child.stdout + child.stderr, 'utf-8')
        if child.returncode:
            raise RuntimeError('隔离存档检查失败：' + label)
        result = json.loads(next(line for line in reversed(child.stdout.splitlines()) if line.startswith('{')))
        report['playerReads'].append(result)
        return result

    report = dict(passed=False, jarSHA256=sha(jar), runtimeDirectory=str(directory), snapshots=[], playerReads=[],
        normalStops=0, boundaries=[], sourceResourcesModified=False, syntheticPrerequisitePreparation=True,
        officialStoryProgressionVerified=False, clientGameplayVerified=False)
    credentials = None
    try:
        for iteration, modes in enumerate([['bootstrap', 'locked', 'earn'], ['redeem'], ['verify']]):
            with (directory / f'server-{iteration}.log').open('wb') as log:
                server = subprocess.Popen([str(java), *options, '-Xmx1536m', '-jar', str(jar), '--no-handbook'],
                    cwd=directory, stdin=subprocess.PIPE, stdout=log, stderr=subprocess.STDOUT, creationflags=flags)
                try:
                    wait_for(server, lambda: request('/health/ready')['ready'])
                    for mode in modes:
                        login = request('/hkrpg_cn/mdk/shield/api/login', {'account': 'cn-46-music-performance', 'password': 'isolated-test'})
                        if login.get('retcode') != 0:
                            raise RuntimeError('隔离账号登录失败')
                        account = login['data']['account']
                        combo = request('/hkrpg_cn/combo/granter/login/v2/login',
                            {'data': json.dumps({'uid': account['uid'], 'token': account['token']})})
                        credentials = {'mode': mode, 'accountUid': account['uid'], 'comboToken': combo['data']['combo_token']}
                        with transport.DatagramBoundary(game, 1200) as boundary:
                            child = subprocess.run([str(java), *options, '-cp', str(classes) + os.pathsep + str(jar),
                                'org.hkrpg.server.protocol.NativeMusicRewardsProbe', str(boundary.port)], cwd=directory,
                                input=json.dumps(credentials), text=True, encoding='utf-8', capture_output=True,
                                timeout=100, creationflags=flags)
                        probe_log = directory / (mode + '.log')
                        probe_log.write_text(child.stdout + child.stderr, 'utf-8')
                        report['boundaries'].append(boundary.summary())
                        if child.returncode:
                            raise RuntimeError('原生音乐回归失败：' + str(probe_log))
                        if boundary.dropped or boundary.largest_server > 1200 or boundary.largest_client > 1200:
                            raise RuntimeError('数据报越过1200字节边界')
                        snapshot = json.loads(next(line for line in reversed(child.stdout.splitlines()) if line.startswith('{')))
                        report['snapshots'].append(snapshot)
                        wait_for(server, lambda: request('/status/server')['status']['playerCount'] == 0)
                        uid = snapshot['uid']
                        (directory / 'music-fixture.json').write_text(json.dumps({'testFixture': True, 'uid': uid}), 'utf-8')
                        saved = inspect(uid, 'read', mode + '-read')
                        if mode in ('bootstrap', 'locked'):
                            inspect(uid, 'prepare-locked' if mode == 'bootstrap' else 'prepare-qualified', mode + '-prerequisites')
                        elif mode == 'earn':
                            assert saved['item270003'] == 3 and saved['rhythm']['stars']['601'] == 3
                            report['walletBefore'] = {key: saved[key] for key in ('hcoin', 'scoin', 'exp')}
                        else:
                            assert saved['item270003'] == 0 and saved['item223'] == 4
                            assert saved['offering']['level'] == 2 and saved['offering']['totalExp'] == 6
                            assert saved['hcoin'] - report['walletBefore']['hcoin'] == 60
                            assert saved['scoin'] - report['walletBefore']['scoin'] == 20000
                            assert saved['exp'] - report['walletBefore']['exp'] == 200
                        print(json.dumps({'mode': mode, 'nativeRequests': snapshot['nativeRequests']}), flush=True)
                finally:
                    try:
                        if server.poll() is not None:
                            raise RuntimeError('隔离服务在stop前退出')
                        server.stdin.write(b'stop\n'); server.stdin.flush(); server.wait(timeout=45)
                        if server.returncode != (1000 if os.name == 'nt' else 232):
                            raise RuntimeError('隔离服务未正常停止')
                        report['normalStops'] += 1
                    finally:
                        if server.poll() is None:
                            server.terminate(); server.wait(timeout=15)
                        server.stdin.close()
        assert len({s['uid'] for s in report['snapshots']}) == 1 and report['normalStops'] == 3
        assert frozen == {name: sha(Path(name)) for name in frozen}
        assert guarded == {name: sha(resources / name) for name in guarded}
        report.update(passed=True, nativeRequests=sum(s['nativeRequests'] for s in report['snapshots']),
            normalLogouts=len(report['snapshots']), sourceFilesUnchanged=len(guarded))
    finally:
        output.parent.mkdir(parents=True, exist_ok=True)
        output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n', 'utf-8')
    return report


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--jar', type=Path, default=ROOT / 'build/libs/hkrpg-server.jar')
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    result = run(args.jar, args.output)
    print(json.dumps({key: result[key] for key in ['passed', 'nativeRequests', 'normalStops', 'normalLogouts', 'runtimeDirectory']}))
