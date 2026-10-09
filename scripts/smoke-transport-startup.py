"""验证打包服务器在真实 UDP 绑定失败时不发布就绪，全部配置/数据库隔离。"""
import argparse
import hashlib
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


def free_port():
    with socket.socket() as channel:
        channel.bind(('127.0.0.1', 0))
        return channel.getsockname()[1]


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--java')
    parser.add_argument('--resources', type=Path, default=ROOT / 'resources/4.6')
    parser.add_argument('--output', type=Path)
    args = parser.parse_args()
    local_java = next((ROOT / '.tools').glob('jdk-21*/bin/java.exe'), None)
    java = args.java or (str(local_java) if local_java else str(Path(os.environ['JAVA_HOME']) / 'bin/java.exe'))
    jar = ROOT / 'build/libs/hkrpg-server.jar'
    artifact = hashlib.sha256(jar.read_bytes()).hexdigest()
    source = args.resources.resolve(strict=True)
    # 此探针不进入场景、不创建玩家，也不改写原始资源。
    originals = {str(p.relative_to(source)): hashlib.sha256(p.read_bytes()).hexdigest()
                 for p in [source / 'resource-manifest.json', source / 'ExcelOutput/PlaneEvent.json',
                           source / 'ExcelOutput/StageConfig.json']}
    runs = ROOT / 'build/smoke'
    runs.mkdir(parents=True, exist_ok=True)
    directory = Path(tempfile.mkdtemp(prefix='transport-startup-', dir=runs))
    flags = subprocess.CREATE_NO_WINDOW if os.name == 'nt' else 0
    with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as occupied:
        if os.name == 'nt':
            occupied.setsockopt(socket.SOL_SOCKET, socket.SO_EXCLUSIVEADDRUSE, 1)
        occupied.bind(('127.0.0.1', 0))
        http, mongo, game = free_port(), free_port(), occupied.getsockname()[1]
        config = directory / 'config.json'
        config.write_text(json.dumps({'protocol': {'wireVersion': '4.6.0'}, 'resourceDir': str(source),
            'httpServer': {'bindAddress': '127.0.0.1', 'bindPort': http},
            'gameServer': {'bindAddress': '127.0.0.1', 'bindPort': game},
            'internalMongoServer': {'address': '127.0.0.1', 'port': mongo, 'filePath': './data/database.mv'}}), 'utf-8')
        before = config.read_bytes()
        ready_observed = False
        log_path = directory / 'server.log'
        with log_path.open('wb') as log:
            process = subprocess.Popen([java, '-Xmx1536m',
                f'-Djdk.net.unixdomain.tmpdir={directory / "tcp-fallback"}', '-Dfile.encoding=UTF-8',
                '-jar', str(jar), '--no-console', '--no-handbook'], cwd=directory,
                stdin=subprocess.DEVNULL, stdout=log, stderr=subprocess.STDOUT, creationflags=flags)
            try:
                deadline = time.monotonic() + 100
                while process.poll() is None:
                    try:
                        with urllib.request.urlopen(f'http://127.0.0.1:{http}/health/ready', timeout=2) as response:
                            ready_observed = ready_observed or bool(json.load(response)['ready'])
                    except (OSError, urllib.error.URLError):
                        pass
                    if ready_observed:
                        raise AssertionError(f'UDP port is occupied but ready=true: {log_path}')
                    if time.monotonic() > deadline:
                        raise AssertionError(f'Failed UDP startup did not exit: {log_path}')
                    time.sleep(.05)
                assert process.returncode == 1, (process.returncode, log_path)
            finally:
                if process.poll() is None:
                    process.terminate()
                    try:
                        process.wait(timeout=20)
                    except subprocess.TimeoutExpired:
                        process.kill()
                        process.wait(timeout=5)
        log_text = log_path.read_text('utf-8', errors='replace')
        assert 'Game Server started on' not in log_text, log_path
        assert 'Game startup failed' in log_text, log_path
        assert not ready_observed
        assert config.read_bytes() == before
    assert hashlib.sha256(jar.read_bytes()).hexdigest() == artifact
    for name, digest in originals.items():
        assert hashlib.sha256((source / name).read_bytes()).hexdigest() == digest
    summary = {'passed': True, 'artifactSha256': artifact, 'occupiedUdpBindRejected': True,
        'readyWasNeverObserved': True, 'gameStartedWasNeverLogged': True,
        'serverExitCode': 1, 'operatorConfigurationPreserved': True,
        'resourceHashesChecked': len(originals), 'runtimeDirectory': str(directory),
        'clientGameplayVerified': False}
    target = args.output or ROOT / 'build/transport-startup-smoke.json'
    target.write_text(json.dumps(summary, ensure_ascii=False, indent=2) + '\n', 'utf-8')
    print(json.dumps(summary, ensure_ascii=False), flush=True)


if __name__ == '__main__':
    main()
