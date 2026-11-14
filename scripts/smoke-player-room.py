"""在独立 H2 和合成账号验证展柜原生请求、重登及正常重启；不接触正式玩家。"""
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
spec = importlib.util.spec_from_file_location('room_transport', ROOT / 'scripts/smoke-login-initial.py')
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
        raise ValueError('仅使用 build 内构建产物，报告不得已经存在')
    resources = ROOT / 'resources/4.6'
    fixture = json.loads((ROOT / 'src/test/resources/fixtures/player-room-display-4.6.json').read_text('utf-8'))
    guarded = {f'ExcelOutput/{name}.json': value for name, value in fixture['sourceSha256'].items()}
    if guarded != {name: sha(resources / name) for name in guarded}:
        raise ValueError('原始来源资源与夹具不一致')
    (ROOT / 'build/smoke').mkdir(parents=True, exist_ok=True)
    directory = Path(tempfile.mkdtemp(prefix='player-room-', dir=ROOT / 'build/smoke'))
    java = next((ROOT / '.tools').glob('jdk-21*/bin/java.exe'))
    flags = subprocess.CREATE_NO_WINDOW if os.name == 'nt' else 0
    http, mongo, game = port(), port(), port(socket.SOCK_DGRAM)
    while len({http, mongo, game}) != 3:
        mongo, game = port(), port(socket.SOCK_DGRAM)
    config = directory / 'config.json'
    config.write_text(json.dumps({'protocol': {'wireVersion': '4.6.0'}, 'resourceDir': str(resources),
        'httpServer': {'bindAddress': '127.0.0.1', 'bindPort': http},
        'gameServer': {'bindAddress': '127.0.0.1', 'bindPort': game},
        'internalMongoServer': {'address': '127.0.0.1', 'port': mongo, 'filePath': './data/database.mv'}}), 'utf-8')
    probe = ROOT / 'scripts/fixtures/java/org/hkrpg/server/protocol/NativePlayerRoomProbe.java'
    inspector = ROOT / 'scripts/fixtures/java/org/hkrpg/server/gameplay/PlayerRoomFixtureInspector.java'
    frozen = {str(p): sha(p) for p in (jar, config, probe, inspector)}
    classes = directory / 'helpers'
    classes.mkdir()
    options = ['-Dfile.encoding=UTF-8', '-Dio.netty.eventLoopThreads=2',
               '-Djdk.net.unixdomain.tmpdir=' + str(directory / 'tcp-fallback')]
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
        child = subprocess.run([str(java), *options, '-cp', str(classes) + os.pathsep + str(jar),
            'org.hkrpg.server.gameplay.PlayerRoomFixtureInspector'], cwd=directory,
            input=json.dumps({'directory': str(directory), 'uid': uid, 'mode': mode}),
            text=True, encoding='utf-8', capture_output=True, timeout=25, creationflags=flags)
        (directory / (label + '.log')).write_text(child.stdout + child.stderr, encoding='utf-8')
        if child.returncode:
            raise RuntimeError('隔离存档检查失败：' + label)
        return json.loads(next(line for line in reversed(child.stdout.splitlines()) if line.startswith('{')))

    report = dict(passed=False, runtimeSHA256=sha(jar), runtimeDirectory=str(directory), snapshots=[],
        playerReads=[], boundaries=[], normalStops=0, clientGameplayVerified=False,
        syntheticPreparation='After the locked check, this disposable player alone is set to level 21 and given item 295603.',
        credentialsOmitted=True)
    accounts = []
    try:
        compiled = subprocess.run([str(java.with_name('javac.exe')), '-encoding', 'UTF-8', '-cp', str(jar),
            '-d', str(classes), str(probe), str(inspector)], capture_output=True, creationflags=flags)
        (directory / 'javac.log').write_bytes(compiled.stdout + compiled.stderr)
        if compiled.returncode:
            raise RuntimeError('隔离探针编译失败：' + str(directory / 'javac.log'))
        for iteration in range(2):
            with (directory / f'server-{iteration}.log').open('wb') as log:
                server = subprocess.Popen([str(java), *options, '-Xmx1536m', '-jar', str(jar), '--no-handbook'],
                    cwd=directory, stdin=subprocess.PIPE, stdout=log, stderr=subprocess.STDOUT, creationflags=flags)
                try:
                    wait_for(server, lambda: request('/health/ready')['ready'])
                    for mode in (['locked', 'place', 'verify'] if iteration == 0 else ['restart']):
                        index = len(report['snapshots'])
                        login = request('/hkrpg_cn/mdk/shield/api/login',
                            {'account': 'synthetic-player-room', 'password': 'isolated-' + str(index)})
                        if login.get('retcode') != 0:
                            raise RuntimeError('隔离账号登录失败')
                        account = login['data']['account']
                        accounts.append(account['uid'])
                        combo = request('/hkrpg_cn/combo/granter/login/v2/login',
                            {'data': json.dumps({'uid': account['uid'], 'token': account['token']})})
                        if combo.get('retcode') != 0:
                            raise RuntimeError('隔离 combo 失败')
                        control = {'mode': mode, 'accountUid': account['uid'], 'comboToken': combo['data']['combo_token']}
                        with transport.DatagramBoundary(game, 1200) as boundary:
                            child = subprocess.run([str(java), *options, '-cp', str(classes) + os.pathsep + str(jar),
                                'org.hkrpg.server.protocol.NativePlayerRoomProbe', str(boundary.port)], cwd=directory,
                                input=json.dumps(control), text=True, encoding='utf-8', capture_output=True,
                                timeout=100, creationflags=flags)
                        report['boundaries'].append(boundary.summary())
                        probe_log = directory / f'probe-{index}.log'
                        probe_log.write_text(child.stdout + child.stderr, encoding='utf-8')
                        if child.returncode:
                            raise RuntimeError('原生展柜回归失败：' + str(probe_log))
                        if boundary.dropped or boundary.largest_server > 1200 or boundary.largest_client > 1200:
                            raise RuntimeError('原生数据报越过 1200 字节边界')
                        snapshot = json.loads(next(line for line in reversed(child.stdout.splitlines()) if line.startswith('{')))
                        snapshot['serverIteration'] = iteration
                        report['snapshots'].append(snapshot)
                        wait_for(server, lambda: request('/status/server')['status']['playerCount'] == 0)
                        snapshot['normalLogoutConfirmed'] = True
                        saved = inspect(snapshot['playerUid'], 'prepare' if mode == 'locked' else 'read', 'player-' + str(index))
                        report['playerReads'].append(saved)
                        if saved['level'] != 21 or saved['fixtureItemCount'] != 1:
                            raise RuntimeError('隔离等级或物品准备记录不符')
                        items = (saved.get('cabinet') or {}).get('items') or {}
                        expected = {'1600101': 295603} if mode in ('place', 'verify') else {}
                        if items != expected:
                            raise RuntimeError('真实 Player 文档与展柜回包不一致')
                        pictures = (saved.get('cabinet') or {}).get('pictures') or {}
                        expected_photos = ({'1600301': {'id': snapshot['avatarPhotoId'], 'type': 1}}
                                           if mode in ('place', 'verify') else {})
                        if pictures != expected_photos:
                            raise RuntimeError('真实 Player 文档与照片回包不一致')
                        print(json.dumps({'mode': mode, 'nativeRequests': snapshot['nativeRequests']}), flush=True)
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
        if report['normalStops'] != 2 or len(report['snapshots']) != 4 or len(set(accounts)) != 1:
            raise RuntimeError('隔离重连/重启链路不完整')
        if len({s['playerUid'] for s in report['snapshots']}) != 1:
            raise RuntimeError('隔离玩家身份变化')
        if frozen != {name: sha(Path(name)) for name in frozen}:
            raise RuntimeError('冻结输入变化')
        if guarded != {name: sha(resources / name) for name in guarded}:
            raise RuntimeError('来源资源变化')
        report.update(passed=True, nativeRequests=sum(s['nativeRequests'] for s in report['snapshots']),
            resourceFilesUnchanged=len(guarded), normalLogouts=len(report['snapshots']))
    finally:
        output.parent.mkdir(parents=True, exist_ok=True)
        output.write_text(json.dumps(report, indent=2) + '\n', encoding='utf-8')
    return report


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--jar', type=Path, default=ROOT / 'build/libs/hkrpg-server.jar')
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    result = run(args.jar, args.output)
    print(json.dumps({'passed': result['passed'], 'nativeRequests': result['nativeRequests'],
                      'normalStops': result['normalStops'], 'clientGameplayVerified': False}))
