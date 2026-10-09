"""隔离真实列车/城镇入口经KCP切换、重连和H2重启；两条入口门槛临时清空，不启动客户端。"""
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

def free_port(kind=socket.SOCK_STREAM):
    with socket.socket(type=kind) as sock:
        sock.bind(('127.0.0.1', 0))
        return sock.getsockname()[1]

def request(base, route, body=None):
    req = urllib.request.Request(base + route, data=None if body is None else json.dumps(body).encode(),
        headers={'Content-Type': 'application/json'})
    with urllib.request.urlopen(req, timeout=5) as response:
        return json.load(response)

def main():
    java = next((ROOT / '.tools').glob('jdk-21*/bin/java.exe'), None)
    java = str(java) if java else 'java'
    artifact = ROOT / 'build/libs/hkrpg-server.jar'
    runs = ROOT / 'build/smoke'
    runs.mkdir(parents=True, exist_ok=True)
    directory = Path(tempfile.mkdtemp(prefix='train-world-', dir=runs))
    original = ROOT / 'resources/4.6'
    fixture = directory / 'resources-fixture'
    excel = fixture / 'ExcelOutput'
    excel.mkdir(parents=True)
    source_entries = original / 'ExcelOutput/MapEntrance.json'
    source_sha = hashlib.sha256(source_entries.read_bytes()).hexdigest()
    changed = {}
    for source in (original / 'ExcelOutput').glob('*.json'):
        target = excel / source.name
        if source.name == 'MapEntrance.json':
            data = json.loads(source.read_text(encoding='utf-8'))
            rows = list(data.values()) if isinstance(data, dict) else data
            for row in rows:
                if row['ID'] in [1000001, 2010101]:
                    changed[row['ID']] = {key: row.get(key, []) for key in ['FinishMainMissionList', 'FinishSubMissionList']}
                    for key in changed[row['ID']]: row[key] = []
            assert set(changed) == {1000001, 2010101}
            target.write_text(json.dumps(data, ensure_ascii=False), encoding='utf-8')
        else:
            os.link(source, target)
    if os.name == 'nt':
        literal = lambda p: "'" + str(p).replace("'", "''") + "'"
        subprocess.run(['powershell', '-NoProfile', '-NonInteractive', '-Command',
            'New-Item -ItemType Junction -Path ' + literal(fixture / 'Config') + ' -Value ' + literal(original / 'Config') + ' | Out-Null'],
            check=True, capture_output=True, text=True)
    else:
        (fixture / 'Config').symlink_to(original / 'Config', target_is_directory=True)
    (fixture / 'resource-manifest.json').write_text(json.dumps({'clientVersion': '4.6.0', 'testFixture': True,
        'isolatedEntranceGates': changed}), encoding='utf-8')
    http_port, mongo_port, game_port = free_port(), free_port(), free_port(socket.SOCK_DGRAM)
    (directory / 'config.json').write_text(json.dumps({'protocol': {'wireVersion': '4.6.0'}, 'resourceDir': str(fixture),
        'httpServer': {'bindAddress': '127.0.0.1', 'bindPort': http_port},
        'gameServer': {'bindAddress': '127.0.0.1', 'bindPort': game_port},
        'internalMongoServer': {'address': '127.0.0.1', 'port': mongo_port, 'filePath': './data/database.mv'}}), encoding='utf-8')
    command = [java, '-Xmx1536m', f'-Djdk.net.unixdomain.tmpdir={directory / "tcp-fallback"}',
        '-jar', str(artifact), '--no-console', '--no-handbook']
    base = f'http://127.0.0.1:{http_port}'
    hidden = subprocess.CREATE_NO_WINDOW if os.name == 'nt' else 0
    def probe(name, credentials):
        execution = subprocess.run([java, command[2], '-cp', str(artifact),
            'org.hkrpg.server.protocol.LoopbackTrainWorldProbe', str(game_port)], input=json.dumps(credentials),
            cwd=directory, capture_output=True, text=True, encoding='utf-8', errors='replace', timeout=30, creationflags=hidden)
        (directory / name).write_text(execution.stdout + execution.stderr, encoding='utf-8')
        if execution.returncode: raise RuntimeError(f'Train world probe failed; inspect {directory / name}')
        result = json.loads(next(line for line in execution.stdout.splitlines() if line.startswith('{"uid":')))
        assert result['passed'] and not result['clientGameplayVerified']
        return result
    snapshots = []
    credentials = None
    for iteration in range(2):
        with (directory / f'server-{iteration}.log').open('w', encoding='utf-8') as log:
            process = subprocess.Popen(command, cwd=directory, stdin=subprocess.DEVNULL, stdout=log,
                stderr=subprocess.STDOUT, creationflags=hidden)
            try:
                deadline = time.monotonic() + 90
                while True:
                    if process.poll() is not None: raise RuntimeError(f'Train server exited; inspect {directory}')
                    try:
                        if request(base, '/health/ready')['ready']: break
                    except (OSError, urllib.error.URLError): pass
                    if time.monotonic() > deadline: raise RuntimeError(f'Train server timeout; inspect {directory}')
                    time.sleep(.2)
                if iteration == 0:
                    login = request(base, '/hkrpg_cn/mdk/shield/api/login', {'account': 'cn-46-train', 'password': 'local-test'})
                    assert login['retcode'] == 0
                    account = login['data']['account']
                    combo = request(base, '/hkrpg_cn/combo/granter/login/v2/login', {'data': json.dumps({'uid': account['uid'], 'token': account['token']})})
                    assert combo['retcode'] == 0
                    credentials = {'accountUid': account['uid'], 'comboToken': combo['data']['combo_token']}
                    first = probe('train-first.log', credentials)
                    assert first['nativeWorldSelection'] and first['nativeWorldNotification'] and first['fullWidthContextRejected'] and first['unvisitedWorldRejected']
                    snapshots.append(first)
                    credentials.update(resume=True, previousContext=first['context'])
                restored = probe(f'train-resume-{iteration}.log', credentials)
                assert restored['savedTrainWorldRestored'] and restored['previousContextRejected'] and restored['visitedWorldsRestored']
                assert restored['uid'] == first['uid'] and restored['world'] == 101
                snapshots.append(restored)
                credentials['previousContext'] = restored['context']
                time.sleep(2)
            finally:
                if process.poll() is None:
                    process.terminate()
                    try: process.wait(timeout=15)
                    except subprocess.TimeoutExpired:
                        process.kill()
                        process.wait(timeout=5)
    assert hashlib.sha256(source_entries.read_bytes()).hexdigest() == source_sha
    print(json.dumps({'passed': True, 'runtimeDirectory': str(directory), 'checks': ['actual-resource-native-train-selection',
        'current-scene-uint64-context', 'full-width-context-rejection', 'unvisited-world-rejection',
        'reconnect-selection-and-context', 'h2-restart-visited-history'], 'snapshots': snapshots,
        'fixtureEntranceGates': changed, 'sourceEntriesUnchanged': True, 'clientAccepted': False}, ensure_ascii=False))

if __name__ == '__main__':
    main()
