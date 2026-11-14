"""隔离 H2/合成账号验证猫猫糕开盒、领取、防重及正常重启；不操作正式玩家。"""
import argparse
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import shutil
import socket
import subprocess
import tempfile
import time
import urllib.error
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('cat_box_transport', ROOT / 'scripts/smoke-login-initial.py')
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


def run(jar, output, legacy_database=None):
    jar, output = jar.resolve(strict=True), output.resolve()
    if not jar.is_relative_to(ROOT / 'build') or not output.is_relative_to(ROOT / 'build') or output.exists():
        raise ValueError('仅使用 build 内冻结产物；报告不得已经存在')
    resources = ROOT / 'resources/4.6'
    fixture = json.loads((ROOT / 'src/test/resources/fixtures/train-cake-box-4.6.json').read_text('utf-8'))
    guarded = dict(fixture['sourceFileSHA256'])
    initial = json.loads((ROOT / 'src/test/resources/fixtures/train-debug-scene-groups-4.6.json').read_text('utf-8'))
    guarded.update(initial['sourceFiles'])
    if guarded != {name: sha(resources / name) for name in guarded}:
        raise ValueError('原始来源资源与夹具不一致')
    (ROOT / 'build/smoke').mkdir(parents=True, exist_ok=True)
    directory = Path(tempfile.mkdtemp(prefix='train-cat-box-', dir=ROOT / 'build/smoke'))
    legacy_seed = None
    if legacy_database is not None:
        legacy_database = legacy_database.resolve(strict=True)
        if (not legacy_database.is_relative_to(ROOT / 'build/smoke')
            or legacy_database.name != 'database.mv' or legacy_database.parent.name != 'data'
            or not legacy_database.parent.parent.name.startswith('train-cat-box-')):
            raise ValueError('旧存档只能来自已正常停止的隔离开盒目录')
        legacy_seed = {'sourceSHA256': sha(legacy_database), 'sourcePreserved': False}
        (directory / 'data').mkdir()
        shutil.copy2(legacy_database, directory / 'data/database.mv')
    java = next((ROOT / '.tools').glob('jdk-21*/bin/java.exe'))
    flags = subprocess.CREATE_NO_WINDOW if os.name == 'nt' else 0
    http, mongo, game = port(), port(), port(socket.SOCK_DGRAM)
    while len({http, mongo, game}) != 3:
        mongo = port()
        game = port(socket.SOCK_DGRAM)
    config = directory / 'config.json'
    config.write_text(json.dumps({'protocol': {'wireVersion': '4.6.0'}, 'resourceDir': str(resources),
        'httpServer': {'bindAddress': '127.0.0.1', 'bindPort': http},
        'gameServer': {'bindAddress': '127.0.0.1', 'bindPort': game},
        'internalMongoServer': {'address': '127.0.0.1', 'port': mongo, 'filePath': './data/database.mv'}}), 'utf-8')
    probe = ROOT / 'scripts/fixtures/java/org/hkrpg/server/protocol/NativeTrainCakeCatchProbe.java'
    inspector = ROOT / 'scripts/fixtures/java/org/hkrpg/server/gameplay/TrainCakeCatchFixtureInspector.java'
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
            'org.hkrpg.server.gameplay.TrainCakeCatchFixtureInspector'], cwd=directory,
            input=json.dumps({'directory': str(directory), 'uid': uid, 'mode': mode}),
            text=True, encoding='utf-8', capture_output=True, timeout=25, creationflags=flags)
        (directory / (label + '.log')).write_text(child.stdout + child.stderr, encoding='utf-8')
        if child.returncode:
            raise RuntimeError('隔离存档检查失败：' + label)
        return json.loads(next(line for line in reversed(child.stdout.splitlines()) if line.startswith('{')))

    report = dict(passed=False, runtimeSHA256=sha(jar), runtimeDirectory=str(directory), snapshots=[],
        independentPlayerReads=[], boundaries=[], normalStops=0, databaseFiles=[],
        clientGameplayVerified=False, credentialsOmitted=True, officialGachaWeightsVerified=False,
        syntheticGateFixture='Only the isolated player is prepared at level 21 with original 402080005 finished and 402080008 active')
    if legacy_seed is not None:
        report['legacyDatabaseSeed'] = legacy_seed
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
                    for mode in (['locked', 'open', 'verify'] if iteration == 0 else ['restart']):
                        index = len(report['snapshots'])
                        login = request('/hkrpg_cn/mdk/shield/api/login',
                            {'account': 'synthetic-train-cat-box', 'password': 'isolated-' + str(index)})
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
                                'org.hkrpg.server.protocol.NativeTrainCakeCatchProbe', str(boundary.port)], cwd=directory,
                                input=json.dumps(control), text=True, encoding='utf-8', capture_output=True,
                                timeout=100, creationflags=flags)
                        report['boundaries'].append(boundary.summary())
                        probe_log = directory / f'probe-{index}.log'
                        probe_log.write_text(child.stdout + child.stderr, encoding='utf-8')
                        if child.returncode:
                            raise RuntimeError('原生开盒回归失败：' + str(probe_log))
                        if boundary.dropped or boundary.largest_server > 1200 or boundary.largest_client > 1200:
                            raise RuntimeError('原生数据报越过 1200 字节边界')
                        snapshot = json.loads(next(line for line in reversed(child.stdout.splitlines()) if line.startswith('{')))
                        snapshot.update(serverIteration=iteration)
                        report['snapshots'].append(snapshot)
                        wait_for(server, lambda: request('/status/server')['status']['playerCount'] == 0)
                        snapshot['normalLogoutConfirmed'] = True
                        mode_inspect = 'unlock' if mode == 'locked' else 'read'
                        saved = inspect(snapshot['playerUid'], mode_inspect, 'player-' + str(index))
                        report['independentPlayerReads'].append(saved)
                        if saved['level'] != 21 or saved['introGatePhase'] != 'FINISHED':
                            raise RuntimeError('隔离门槛记录未保留')
                        if saved['boxGoalPhase'] != ('DOING' if mode == 'locked' else 'FINISHED'):
                            raise RuntimeError('开盒目标未按原生操作推进或提前完成')
                        if mode != 'locked' and saved['nextDialoguePhase'] != 'DOING':
                            raise RuntimeError('后续原始对话没有保持进行中')
                        expected = {str(cat): 1 for cat in snapshot['capturedCats']}
                        if ((saved.get('catBox') or {}).get('counts') or {}) != expected:
                            raise RuntimeError('真实 Player 文档的领取数与原生回包不同')
                        print(json.dumps({'mode': mode, 'nativeRequests': snapshot['nativeRequests'],
                                          'capturedCats': snapshot['capturedCats']}), flush=True)
                finally:
                    try:
                        if server.poll() is None:
                            server.stdin.write(b'stop\n'); server.stdin.flush(); server.wait(timeout=45)
                            if server.returncode != (1000 if os.name == 'nt' else 232):
                                raise RuntimeError('隔离服务未正常停止')
                            report['normalStops'] += 1
                            database = directory / 'data/database.mv'
                            report['databaseFiles'].append({'bytes': database.stat().st_size, 'sha256': sha(database)})
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
        if legacy_seed is not None:
            if sha(legacy_database) != legacy_seed['sourceSHA256']:
                raise RuntimeError('旧隔离存档被修改')
            legacy_seed['sourcePreserved'] = True
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
    parser.add_argument('--legacy-database', type=Path,
                        help='复制正常停止的旧隔离 H2，验证已有玩家缺少新状态字段时的升级')
    args = parser.parse_args()
    result = run(args.jar, args.output, args.legacy_database)
    print(json.dumps({'passed': result['passed'], 'nativeRequests': result['nativeRequests'],
                      'normalStops': result['normalStops'], 'clientGameplayVerified': False}))
