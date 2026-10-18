"""核验常驻 Compose；默认只读，--lifecycle 才创建独立测试账号并停启/重建服务。"""
import argparse
import base64
import datetime
import hashlib
import importlib.util
import json
import os
from pathlib import Path, PurePosixPath, PureWindowsPath
import re
import subprocess
import sys
import time
import urllib.request
import uuid

ROOT = Path(__file__).resolve().parents[1]


def require(condition, message):
    if not condition:
        raise ValueError(message)


def sha(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def inside(path, root, label, exists=True):
    value = Path(path).resolve(strict=exists)
    require(value.is_relative_to(root.resolve()) and value != root.resolve(), label + '必须位于本仓库内')
    return value


def read_env(path):
    result = {}
    for line in Path(path).read_text('utf-8-sig').splitlines():
        line = line.strip()
        if not line or line.startswith('#'):
            continue
        key, separator, value = line.partition('=')
        require(separator and re.fullmatch(r'[A-Za-z_][A-Za-z_0-9]*', key), '环境文件格式不受支持')
        require(key not in result, '环境文件含重复键')
        value = value.strip()
        if len(value) >= 2 and value[0] == value[-1] and value[0] in "\"'":
            value = value[1:-1]
        require('$' not in value and '\n' not in value, '环境值必须是明确的字面值')
        result[key] = value
    return result


def validate_inputs(compose, env_file, output, project='hkrpg-local', root=ROOT):
    root = root.resolve()
    compose = inside(compose, root, 'Compose文件')
    env_file = inside(env_file, root, '环境文件')
    output = inside(output, root / 'build', '报告', exists=False)
    require(compose.is_file() and compose.suffix in ('.yml', '.yaml'), 'Compose文件无效')
    require(env_file.is_file() and not output.exists(), '环境文件不存在或报告已存在')
    env = read_env(env_file)
    require(project == 'hkrpg-local', '此验收工具仅允许hkrpg-local项目')
    require(env.get('HKRPG_PROJECT_NAME') == project, '环境文件项目名与预期项目不一致')
    require(Path(env.get('HKRPG_REPO_DIR', '')).resolve() == root, '环境文件不属于本仓库')
    runtime = inside(env['HKRPG_RUNTIME_DIR'], root, '运行目录')
    resources = inside(env['HKRPG_RESOURCES_DIR'], root, '资源目录')
    require(runtime.is_dir() and resources.is_dir(), '运行/资源目录不存在')
    require((runtime / 'migration.json').is_file(), '缺少本次迁移清单')
    ports = [int(env[key]) for key in ('HKRPG_HTTP_PORT', 'HKRPG_GAME_PORT')]
    require(all(0 < port < 65536 for port in ports), '端口无效')
    return dict(root=root, compose=compose, envFile=env_file, output=output, project=project,
                runtime=runtime, resources=resources, httpPort=ports[0], gamePort=ports[1],
                composeSHA256=sha(compose), envSHA256=sha(env_file))


def require_image_resources(destinations):
    resources = PurePosixPath('/resources')
    for destination in destinations:
        mount = PurePosixPath(destination)
        require(not (mount == resources or mount in resources.parents or resources in mount.parents),
                '挂载不能覆盖镜像资源目录或其子目录')


def bind_source_matches(source, expected):
    # Docker Desktop reports its WSL host-drive mount, not the Windows source path.
    desktop = re.fullmatch(r'/run/desktop/mnt/host/([a-zA-Z])/(.+)', source)
    if desktop:
        parts = desktop.group(2).split('/')
        if any(part in ('', '.', '..') or chr(92) in part for part in parts):
            return False
        mapped = PureWindowsPath(desktop.group(1) + ':/', *parts)
        return mapped == PureWindowsPath(expected)
    return Path(source).resolve() == Path(expected).resolve()


def validate_compose(config, target):
    require(config.get('name') == target['project'], 'Compose解析到了其他项目')
    service = config.get('services', {}).get('server', {})
    labels = service.get('labels', {})
    require(labels.get('io.hkrpg.managed') == 'true'
            and labels.get('io.hkrpg.role') == 'local-server', '服务缺少本地HKRPG所有权标记')
    require(Path(labels.get('io.hkrpg.runtime-directory', '')).resolve() == target['runtime'],
            '服务标记指向其他运行目录')
    mounts = service.get('volumes', [])
    work = PurePosixPath('/work')
    work_mounts = [item for item in mounts if PurePosixPath(item['target']) == work
                   or work in PurePosixPath(item['target']).parents]
    require(len(work_mounts) == 1 and work_mounts[0]['target'] == '/work'
            and work_mounts[0].get('type') == 'bind' and work_mounts[0].get('read_only') is not True
            and Path(work_mounts[0].get('source', '')).resolve() == target['runtime'] / 'work',
            '持久化目录必须唯一、可写且匹配运行目录，不能有子挂载')
    contexts = service.get('build', {}).get('additional_contexts', {})
    require(Path(contexts.get('hkrpg_resources', '')).resolve() == target['resources'],
            '镜像资源构建目录不匹配')
    require_image_resources(item['target'] for item in mounts)
    jar = inside(target['runtime'] / 'artifact/server.jar', target['root'], '服务产物')
    require(jar.is_file(), '服务产物不存在')
    published = [(item.get('host_ip'), item.get('protocol', 'tcp'), int(item['published']), int(item['target']))
                 for item in service.get('ports', [])]
    expected_ports = {('127.0.0.1', 'tcp', target['httpPort'], target['httpPort']),
                      ('127.0.0.1', 'udp', target['gamePort'], target['gamePort'])}
    require(len(published) == 2 and set(published) == expected_ports,
            '发布端口必须恰好为预期的两个本机HTTP/UDP映射')
    require(service.get('restart') == 'unless-stopped', '服务未配置持久重启策略')
    require(service.get('init') is True, '服务必须由容器init转发终止信号')
    require(service.get('read_only') is True
            and any(item.split(':', 1)[0] == '/tmp' for item in service.get('tmpfs', [])),
            '服务必须使用只读镜像与/tmp临时目录')
    manifest = json.loads((target['runtime'] / 'migration.json').read_text('utf-8-sig'))
    require(sha(jar) == manifest.get('runtimeSHA256'), '服务产物与迁移清单不一致')
    require(sha(target['runtime'] / 'work/config.json') == manifest.get('configSHA256'),
            '运行配置与迁移清单不一致')
    return jar


def validate_container(container, target, running=True):
    labels = container.get('Config', {}).get('Labels', {})
    require(labels.get('com.docker.compose.project') == target['project']
            and labels.get('com.docker.compose.service') == 'server'
            and labels.get('io.hkrpg.managed') == 'true'
            and labels.get('io.hkrpg.role') == 'local-server', '容器不属于目标HKRPG Compose服务')
    require(Path(labels.get('io.hkrpg.runtime-directory', '')).resolve() == target['runtime'],
            '容器运行目录标记不匹配')
    files = labels.get('com.docker.compose.project.config_files', '').split(',')
    require(len(files) == 1 and Path(files[0]).resolve() == target['compose'],
            '容器由其他Compose文件创建')
    require(container.get('State', {}).get('Running') is running, '容器运行状态与预期不同')
    require(container.get('HostConfig', {}).get('RestartPolicy', {}).get('Name') == 'unless-stopped',
            '实际容器重启策略不匹配')
    require(re.fullmatch(r'[0-9a-f]{64}', container.get('Id', '')), '容器身份无效')
    require(container['HostConfig'].get('ReadonlyRootfs') is True
            and '/tmp' in container['HostConfig'].get('Tmpfs', {}), '实际容器缺少只读镜像与/tmp临时目录')
    require('Mounts' in container, '容器挂载检查结果缺失')
    require_image_resources(item['Destination'] for item in container['Mounts'])
    work = PurePosixPath('/work')
    work_mounts = [item for item in container['Mounts']
                   if PurePosixPath(item['Destination']) == work
                   or work in PurePosixPath(item['Destination']).parents]
    require(len(work_mounts) == 1 and work_mounts[0]['Destination'] == '/work'
            and work_mounts[0].get('Type') == 'bind' and work_mounts[0].get('RW') is True
            and bind_source_matches(work_mounts[0].get('Source', ''), target['runtime'] / 'work'),
            '实际持久化目录必须唯一、可写且匹配运行目录，不能有子挂载')
    expected_ports = {
        str(target['httpPort']) + '/tcp': [{'HostIp': '127.0.0.1', 'HostPort': str(target['httpPort'])}],
        str(target['gamePort']) + '/udp': [{'HostIp': '127.0.0.1', 'HostPort': str(target['gamePort'])}]}
    require(container['HostConfig'].get('PortBindings') == expected_ports,
            '实际发布端口必须恰好为预期的两个本机HTTP/UDP映射')


def execute(args, *, cwd=ROOT, timeout=60, input_text=None):
    try:
        result = subprocess.run([str(value) for value in args], cwd=cwd, input=input_text,
                                capture_output=True, text=True, encoding='utf-8', errors='replace',
                                timeout=timeout,
                                creationflags=subprocess.CREATE_NO_WINDOW if os.name == 'nt' else 0)
    except subprocess.TimeoutExpired as error:
        raise RuntimeError('外部检查超时；未输出命令正文或凭据') from error
    require(result.returncode == 0, '外部检查失败；退出码=' + str(result.returncode))
    return result.stdout


class Compose:
    def __init__(self, target):
        self.target = target
        self.prefix = ['docker', 'compose', '--project-name', target['project'], '--file',
                       target['compose'], '--env-file', target['envFile']]

    def command(self, *args, timeout=60):
        return execute(self.prefix + list(args), cwd=self.target['root'], timeout=timeout)

    def config(self):
        return json.loads(self.command('config', '--format', 'json'))

    def inspect(self, running=True):
        ids = self.command('ps', '--all', '--quiet', 'server').split()
        require(len(ids) == 1 and re.fullmatch(r'[0-9a-f]{12,64}', ids[0]), '目标server容器必须恰好一个')
        objects = json.loads(execute(['docker', 'inspect', ids[0]]))
        require(len(objects) == 1, '容器检查结果不唯一')
        validate_container(objects[0], self.target, running)
        return objects[0]

    def snapshot(self):
        container = self.inspect()
        digest = execute(['docker', 'exec', container['Id'], 'sha256sum', '/opt/hkrpg/server.jar']).split()[0]
        require(re.fullmatch(r'[0-9a-f]{64}', digest), '容器产物摘要无效')
        return dict(containerId=container['Id'], imageId=container['Image'],
                    image=container['Config']['Image'], runtimeSHA256=digest,
                    startedAt=container['State']['StartedAt'], restartPolicy='unless-stopped')


class Http:
    def __init__(self, port):
        self.base = 'http://127.0.0.1:' + str(port)
        self.opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))

    def get(self, route, body=None, raw=False):
        data = None if body is None else json.dumps(body).encode()
        request = urllib.request.Request(self.base + route, data, {'Content-Type': 'application/json'})
        with self.opener.open(request, timeout=5) as response:
            content = response.read()
        return content if raw else json.loads(content)


def dispatch(http, game_port):
    sys.path.insert(0, str(ROOT / '.tools/client-analysis-libs'))
    from google.protobuf.descriptor_pb2 import FileDescriptorSet
    from google.protobuf.descriptor_pool import DescriptorPool
    from google.protobuf.message_factory import GetMessageClass
    pool = DescriptorPool()
    schema = FileDescriptorSet.FromString((ROOT / 'src/main/resources/protocol/4.6/descriptor.pb').read_bytes())
    for definition in schema.file:
        pool.Add(definition)
    ready = http.get('/health/ready')
    require(ready.get('ready') is True and ready.get('mode') == 'BOTH', '服务未完成游戏与登录联合启动')
    checks = []
    for version, environment in [('CNPRODWin4.6.0', '0'), ('OSPRODWin4.6.0', '2')]:
        regions = GetMessageClass(pool.FindMessageTypeByName('Dispatch'))()
        regions.ParseFromString(base64.b64decode(http.get('/query_dispatch?version=' + version, raw=True)))
        require(regions.retcode == 0 and len(regions.region_list) == 1
                and regions.region_list[0].env_type == environment, 'SDK分区环境不匹配')
        gate = GetMessageClass(pool.FindMessageTypeByName('GateServer'))()
        gate.ParseFromString(base64.b64decode(http.get('/query_gateway?version=' + version, raw=True)))
        require(gate.retcode == 0 and gate.ip == '127.0.0.1' and gate.port == game_port,
                '客户端网关不是预期的本机UDP端口')
        require(not gate.ios_exam, '客户端网关误启用审核模式，会禁止切换已安装配音')
        checks.append(dict(version=version, sdkEnvironment=environment, gatewayHost=gate.ip,
                           gatewayPort=gate.port, iosReviewMode=gate.ios_exam))
    return checks


def no_players(http, wait_seconds=0):
    deadline = time.monotonic() + wait_seconds
    while True:
        if http.get('/status/server')['status']['playerCount'] == 0:
            return
        require(time.monotonic() < deadline, '仍有在线玩家；拒绝停服或重建')
        time.sleep(.5)


def java_tools():
    suffix = '.exe' if os.name == 'nt' else ''
    homes = sorted((ROOT / '.tools').glob('jdk-21*'))
    java = next((home / 'bin' / ('java' + suffix) for home in homes
                 if (home / 'bin' / ('java' + suffix)).is_file()), None)
    require(java is not None, '缺少本地Java21工具链')
    return java, java.with_name('javac' + suffix)


def run_probe(java, jar, port, report_dir, label, helpers=None, credentials=None):
    entry = 'NativeLoginInitialProbe' if credentials else 'LoopbackHeartbeatProbe'
    classpath = str(jar) if helpers is None else str(helpers) + os.pathsep + str(jar)
    args = [java, '-Dio.netty.eventLoopThreads=2', '-Djdk.net.unixdomain.tmpdir=' + str(ROOT / '.tools/tcp-fallback'),
            '-cp', classpath, 'org.hkrpg.server.protocol.' + entry, str(port)]
    result = subprocess.run([str(x) for x in args], cwd=report_dir,
                            input=None if credentials is None else json.dumps(credentials),
                            capture_output=True, text=True, encoding='utf-8', timeout=40,
                            creationflags=subprocess.CREATE_NO_WINDOW if os.name == 'nt' else 0)
    # Repository-owned probes print only contract labels, command IDs and public counters.
    (report_dir / (label + '.log')).write_text(result.stdout + result.stderr, encoding='utf-8')
    require(result.returncode == 0, '合成探针失败，见报告目录内' + label + '.log')
    summary = json.loads(next(line for line in result.stdout.splitlines() if line.startswith('{')))
    require(summary.get('passed') is True, '合成探针未通过')
    if credentials:
        require(summary.get('initialRequests') == 143 and summary.get('initialBurstDeadlineMillis') == 4000,
                '初始化探针请求集或时限发生变化')
        require(summary.get('b51InactiveInitialProfileVerified') is True,
                '缺少赛车未激活资料回包核对；不能漏掉实际阻塞客户端的9996')
    return summary


def login(http, username, password):
    response = http.get('/hkrpg_cn/mdk/shield/api/login', {'account': username, 'password': password})
    require(response.get('retcode') == 0, '独立测试账号登录失败')
    account = response['data']['account']
    combo = http.get('/hkrpg_cn/combo/granter/login/v2/login', {'data': json.dumps({
        'uid': account['uid'], 'token': account['token']})})
    require(combo.get('retcode') == 0, '独立测试账号令牌交换失败')
    return {'accountUid': account['uid'], 'comboToken': combo['data']['combo_token']}


def restart(compose, http, previous, recreate):
    target = compose.target
    require(sha(target['compose']) == target['composeSHA256'] and sha(target['envFile']) == target['envSHA256'],
            '验证过程中Compose或环境文件改变；拒绝生命周期操作')
    validate_compose(compose.config(), target)
    require(compose.inspect()['Id'] == previous['containerId'], '容器已被其他操作替换；拒绝停服')
    no_players(http, 45)
    start = datetime.datetime.now(datetime.timezone.utc).isoformat()
    began = time.monotonic()
    try:
        compose.command('stop', '--timeout', '60', 'server', timeout=75)
        stopped = compose.inspect(running=False)
        state = stopped['State']
        elapsed = time.monotonic() - began
        logs = execute(['docker', 'logs', '--since', start, stopped['Id']])
        evidence = dict(exitCode=state['ExitCode'], oomKilled=state['OOMKilled'], elapsedSeconds=round(elapsed, 3),
                        logSHA256=hashlib.sha256(logs.encode()).hexdigest(),
                        dedicatedStopCommandMarker='Stopping server' in logs,
                        shutdownLogErrorLines=sum('ERROR' in line or 'Exception' in line for line in logs.splitlines()),
                        shutdownViaSigterm=True)
        require(state['ExitCode'] in (0, 143) and not state['OOMKilled'] and elapsed < 60,
                '容器未在宽限内通过SIGTERM退出')
    finally:
        args = ['up', '-d', '--no-deps', '--wait', '--wait-timeout', '120']
        if recreate:
            args.append('--force-recreate')
        compose.command(*args, 'server', timeout=150)
    current = compose.snapshot()
    require((current['containerId'] != previous['containerId']) is recreate, '容器重建身份不符合预期')
    require(current['imageId'] == previous['imageId'] and current['runtimeSHA256'] == previous['runtimeSHA256'],
            '生命周期操作改变了待验证产物')
    evidence['containerRecreated'] = recreate
    return current, evidence


def run(args):
    target = validate_inputs(args.compose, args.env_file, args.output)
    report_dir = target['output'].with_suffix('')
    require(not report_dir.exists(), '证据目录已存在')
    report_dir.mkdir(parents=True)
    report = dict(passed=False, lifecycleRequested=args.lifecycle, project=target['project'],
                  credentialsOmitted=True, clientGameplayVerified=False, evidenceDirectory=str(report_dir))
    try:
        compose = Compose(target)
        jar = validate_compose(compose.config(), target)
        snapshot = compose.snapshot()
        require(snapshot['runtimeSHA256'] == sha(jar), '实际容器JAR与宿主已验证产物不同')
        report['containers'] = [snapshot]
        report['runtimeSHA256'] = sha(jar)
        http = Http(target['httpPort'])
        report['dispatch'] = dispatch(http, target['gamePort'])
        java, javac = java_tools()
        report['heartbeat'] = run_probe(java, jar, target['gamePort'], report_dir, 'heartbeat')
        if args.lifecycle:
            no_players(http)
            helpers = report_dir / 'helpers'
            helpers.mkdir()
            execute([javac, '-encoding', 'UTF-8', '-cp', jar, '-d', helpers,
                     ROOT / 'scripts/fixtures/java/org/hkrpg/server/protocol/NativeLoginInitialProbe.java'])
            spec = importlib.util.spec_from_file_location('compose_datagram_boundary', ROOT / 'scripts/smoke-login-initial.py')
            smoke = importlib.util.module_from_spec(spec)
            spec.loader.exec_module(smoke)
            username = 'compose_acceptance_' + uuid.uuid4().hex + '@example.invalid'
            identities, player_ids = [], []
            report['snapshots'], report['normalStops'] = [], []
            for iteration in range(3):
                if iteration:
                    snapshot, stopped = restart(compose, http, snapshot, recreate=iteration == 2)
                    report['containers'].append(snapshot)
                    report['normalStops'].append(stopped)
                    dispatch(http, target['gamePort'])
                credentials = login(http, username, 'compose-test-' + str(iteration))
                identities.append(credentials['accountUid'])
                with smoke.DatagramBoundary(target['gamePort'], 1200) as boundary:
                    summary = run_probe(java, jar, boundary.port, report_dir, 'login-' + str(iteration), helpers, credentials)
                require(boundary.dropped == 0 and boundary.largest_server <= 1200 and boundary.largest_client <= 1200,
                        '合成会话的数据报超过1200字节边界')
                player_ids.append(summary.pop('playerUid'))
                summary['transport'] = boundary.summary()
                report['snapshots'].append(summary)
            require(len(set(identities)) == 1 and len(set(player_ids)) == 1, '重启或重建丢失测试账号/玩家身份')
            no_players(http, 45)
            report.update(sameAccountDifferentPasswords=True, samePlayerAfterRestartAndRecreate=True,
                          accountUidSHA256=hashlib.sha256(str(identities[0]).encode()).hexdigest(),
                          playerUid=player_ids[0], independentSyntheticAccount=True,
                          shutdownEvidence='SIGTERM exit code, bounded stop and persisted identity; dedicated shutdown log is optional')
        report['passed'] = True
    except Exception as error:
        report['errorType'] = type(error).__name__
        report['error'] = str(error) if isinstance(error, ValueError) else '验证异常，未记录可能含敏感数据的外部输出'
        raise
    finally:
        target['output'].write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    return report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--compose', type=Path, default=ROOT / 'deploy/compose.local.yaml')
    parser.add_argument('--env-file', type=Path, default=ROOT / '.local/compose/compose.env')
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--lifecycle', action='store_true', help='创建独立测试账号并停止、启动和重建本项目server')
    args = parser.parse_args()
    try:
        report = run(args)
    except Exception as error:
        print('Compose验证失败：' + type(error).__name__ + '；若报告已创建请查看报告。', file=sys.stderr)
        return 1
    print(json.dumps(dict(passed=report['passed'], lifecycleRequested=report['lifecycleRequested'],
                          report=str(args.output), clientGameplayVerified=False)))
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
