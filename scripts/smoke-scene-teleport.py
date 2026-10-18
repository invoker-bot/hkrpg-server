"""隔离账号/H2的4.6场景传送验收；真实UDP/KCP，不启动游戏客户端或修改正式存档。"""
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
spec = importlib.util.spec_from_file_location('scene_teleport_transport', ROOT / 'scripts/smoke-login-initial.py')
transport = importlib.util.module_from_spec(spec)
spec.loader.exec_module(transport)


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def port(kind=socket.SOCK_STREAM):
    with socket.socket(type=kind) as stream:
        stream.bind(('127.0.0.1', 0))
        return stream.getsockname()[1]


def run(jar, output):
    jar, output = jar.resolve(strict=True), output.resolve()
    if not jar.is_relative_to(ROOT / 'build') or not output.is_relative_to(ROOT / 'build') or output.exists():
        raise ValueError('使用build内冻结JAR，报告必须是build内尚不存在的文件')
    digest = sha(jar)
    source = ROOT / 'resources/4.6'
    floor_file = source / 'Config/LevelOutput/RuntimeFloor/P20001_F20001001.json'
    floor_resource = json.loads(floor_file.read_text(encoding='utf-8'))
    masks = floor_resource.get('LayerToAreaMask')
    if floor_resource.get('FloorID') != 20001001 or not isinstance(masks, list) or not masks:
        raise ValueError('默认入口楼层资源缺少已验证的LayerToAreaMask')
    floor_contract = dict(floorId=floor_resource['FloorID'], validMapLayers=list(range(1, len(masks) + 1)))
    guarded = sorted((source / 'ExcelOutput').glob('*.json')) + [source / 'resource-manifest.json', floor_file]
    hashes = {str(path.relative_to(source)): sha(path) for path in guarded}
    runs = ROOT / 'build/smoke'
    runs.mkdir(parents=True, exist_ok=True)
    directory = Path(tempfile.mkdtemp(prefix='scene-teleport-', dir=runs)).resolve()
    if not directory.is_relative_to(runs.resolve()):
        raise ValueError('隔离目录越界')
    java = next((ROOT / '.tools').glob('jdk-21*/bin/java.exe'))
    helper = ROOT / 'scripts/fixtures/java/org/hkrpg/server/protocol/NativeSceneTeleportProbe.java'
    helper_hash = sha(helper)
    classes = directory / 'helpers'
    classes.mkdir()
    flags = subprocess.CREATE_NO_WINDOW if os.name == 'nt' else 0
    http, mongo, game = port(), port(), port(socket.SOCK_DGRAM)
    while mongo == http:
        mongo = port()
    config = directory / 'config.json'
    config.write_text(json.dumps({
        'protocol': {'wireVersion': '4.6.0'}, 'resourceDir': str(source),
        'httpServer': {'bindAddress': '127.0.0.1', 'bindPort': http},
        'gameServer': {'bindAddress': '127.0.0.1', 'bindPort': game},
        'internalMongoServer': {'address': '127.0.0.1', 'port': mongo, 'filePath': './data/database.mv'}
    }), encoding='utf-8')
    config_hash = sha(config)
    java_flags = ['-Dfile.encoding=UTF-8', '-Dstdout.encoding=UTF-8', '-Dstderr.encoding=UTF-8',
                  '-Dio.netty.eventLoopThreads=2', '-Djava.net.preferIPv4Stack=true',
                  '-Djdk.net.unixdomain.tmpdir=' + str(directory / 'tcp-fallback')]
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))

    def request(route, body=None):
        data = None if body is None else json.dumps(body).encode('utf-8')
        req = urllib.request.Request(f'http://127.0.0.1:{http}' + route, data,
                                     {'Content-Type': 'application/json'})
        with opener.open(req, timeout=5) as response:
            return json.load(response)

    def wait_ready(process):
        deadline = time.monotonic() + 180
        while True:
            if process.poll() is not None:
                raise RuntimeError('隔离服务提前退出，见 ' + str(directory))
            try:
                if request('/health/ready')['ready']:
                    return
            except (OSError, urllib.error.URLError):
                pass
            if time.monotonic() >= deadline:
                raise RuntimeError('隔离服务启动超时，见 ' + str(directory))
            time.sleep(.2)

    def wait_logged_out():
        deadline = time.monotonic() + 15
        while True:
            if request('/status/server')['status']['playerCount'] == 0:
                return
            if time.monotonic() >= deadline:
                raise RuntimeError('客户端注销后隔离玩家仍在线')
            time.sleep(.1)

    database = directory / 'data/database.mv'

    def database_snapshot():
        if not database.is_file() or database.stat().st_size == 0:
            raise RuntimeError('正常停服后隔离H2文件不存在或为空')
        return dict(path=str(database.relative_to(directory)), bytes=database.stat().st_size,
                    sha256=sha(database))

    snapshots, boundaries, account_ids, player_ids, database_files = [], [], [], [], []
    stops = 0
    logout_confirmations = 0
    expected = None
    report = dict(passed=False, runtimeSHA256=digest, runtimeDirectory=str(directory),
                  clientGameplayVerified=False, credentialsOmitted=True)
    try:
        compiled = subprocess.run([str(java.with_name('javac.exe')), '-encoding', 'UTF-8',
            '-cp', str(jar), '-d', str(classes), str(helper)], cwd=directory,
            capture_output=True, creationflags=flags)
        (directory / 'javac.log').write_bytes(compiled.stdout + compiled.stderr)
        if compiled.returncode:
            raise RuntimeError('隔离探针编译失败，见 ' + str(directory / 'javac.log'))
        for iteration in range(2):
            if iteration and database_snapshot() != database_files[-1]:
                raise RuntimeError('正常停服到重启之间隔离H2文件发生变化')
            if sha(jar) != digest:
                raise RuntimeError('候选JAR在验收中发生变化')
            with (directory / f'server-{iteration}.log').open('wb') as log:
                process = subprocess.Popen([str(java), *java_flags, '-Xmx1536m', '-jar', str(jar),
                    '--no-handbook'], cwd=directory, stdin=subprocess.PIPE,
                    stdout=log, stderr=subprocess.STDOUT, creationflags=flags)
                try:
                    wait_ready(process)
                    for mode in (['exercise', 'verify'] if iteration == 0 else ['verify']):
                        index = len(snapshots)
                        login = request('/hkrpg_cn/mdk/shield/api/login', {
                            'account': 'synthetic-scene-teleport', 'password': 'different-' + str(index)})
                        if login.get('retcode') != 0:
                            raise RuntimeError('隔离自动建号/换密码登录失败')
                        account = login['data']['account']
                        account_ids.append(account['uid'])
                        combo = request('/hkrpg_cn/combo/granter/login/v2/login', {'data': json.dumps({
                            'uid': account['uid'], 'token': account['token']})})
                        if combo.get('retcode') != 0:
                            raise RuntimeError('隔离combo认证失败')
                        control = {'accountUid': account['uid'], 'comboToken': combo['data']['combo_token'],
                                   'mode': mode, 'floorResource': floor_contract}
                        if expected is not None:
                            control['expected'] = expected
                        with transport.DatagramBoundary(game, 1200) as boundary:
                            child = subprocess.run([str(java), *java_flags, '-cp',
                                str(classes) + os.pathsep + str(jar),
                                'org.hkrpg.server.protocol.NativeSceneTeleportProbe', str(boundary.port)],
                                cwd=directory, input=json.dumps(control), capture_output=True,
                                text=True, encoding='utf-8', timeout=100, creationflags=flags)
                        boundaries.append(boundary.summary())
                        probe_log = directory / f'probe-{index}-{mode}.log'
                        probe_log.write_text(child.stdout + child.stderr, encoding='utf-8')
                        if child.returncode:
                            raise RuntimeError('场景传送网络验收失败，见 ' + str(probe_log))
                        if boundary.dropped or boundary.largest_server > 1200 or boundary.largest_client > 1200:
                            raise RuntimeError('隔离传输超过1200字节数据报边界')
                        rows = [json.loads(line) for line in child.stdout.splitlines() if line.startswith('{"')]
                        snapshot = rows[-1]
                        if not snapshot.get('passed') or not snapshot.get('normalLogoutRequested'):
                            raise RuntimeError('探针未完成正常注销请求')
                        wait_logged_out()
                        logout_confirmations += 1
                        player_ids.append(snapshot['playerUid'])
                        if expected is None:
                            expected = {key: snapshot[key] for key in
                                        ('playerUid', 'entry', 'floor', 'position', 'rotation', 'mapLayer')}
                        snapshot['mode'] = mode
                        snapshot['serverIteration'] = iteration
                        snapshots.append(snapshot)
                        print(json.dumps({'phase': index, 'mode': mode, 'checks': len(snapshot['checks']),
                                          'normalLogoutConfirmed': True}), flush=True)
                finally:
                    try:
                        if process.poll() is None:
                            process.stdin.write(b'stop\n')
                            process.stdin.flush()
                            process.wait(timeout=45)
                            if process.returncode != (1000 if os.name == 'nt' else 232):
                                raise RuntimeError('隔离服务未正常停服')
                            stops += 1
                            database_files.append(database_snapshot())
                        else:
                            raise RuntimeError('隔离服务在正常stop之前退出')
                    finally:
                        if process.poll() is None:
                            process.terminate()
                            process.wait(timeout=15)
                        process.stdin.close()
        if stops != 2 or logout_confirmations != 3 or len(set(account_ids)) != 1 or len(set(player_ids)) != 1:
            raise RuntimeError('同账号/玩家、三次注销与两次正常停服链路不完整')
        if sha(jar) != digest or sha(config) != config_hash or sha(helper) != helper_hash:
            raise RuntimeError('冻结JAR、探针源码或隔离配置在验收中变化')
        if any(sha(source / name) != digest for name, digest in hashes.items()):
            raise RuntimeError('源资源在验收中发生变化')
        for snapshot in snapshots:
            snapshot.pop('playerUid', None)
        report.update(passed=True, snapshots=snapshots, transportBoundaries=boundaries,
            normalStops=stops, normalClientLogouts=logout_confirmations,
            samePlayerAndAccountAcrossReconnectAndRestart=True,
            sameAccountWithDifferentPasswords=True, sameProcessReconnects=1, normalH2Restarts=1,
            fullAxisPositionAndRotationPersisted=True, forcedMovementNotifications=0,
            stoppedH2Files=database_files, sameH2FileAtColdRestart=True,
            floorResource=dict(path=str(floor_file.relative_to(source)), sha256=sha(floor_file),
                               **floor_contract), networkCrossLayerTransitionVerified=False,
            nativeRequests=sum(s['nativeRequests'] for s in snapshots), sourceResourcesModified=False,
            sourceResourceHashes=hashes, sourceResourceCount=len(hashes), configSHA256=config_hash,
            probeSourceSHA256=helper_hash, realClientStarted=False, completeGameplayVerified=False)
    except BaseException as error:
        report.update(error=str(error), errorType=type(error).__name__, normalStops=stops,
                      normalClientLogouts=logout_confirmations, transportBoundaries=boundaries,
                      stoppedH2Files=database_files)
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
    print(json.dumps({'passed': result['passed'], 'normalStops': result['normalStops'],
                      'normalClientLogouts': result['normalClientLogouts'],
                      'runtimeDirectory': result['runtimeDirectory'], 'clientGameplayVerified': False}))
