"""隔离 KCP/H2 验证试用重登通知、正常重启续局和原队伍返回；不操作游戏客户端。"""
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import socket
import subprocess
import tempfile
import time
import urllib.request
import uuid

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('gm_smoke', ROOT / 'scripts/smoke-gm-console.py')
gm = importlib.util.module_from_spec(spec)
spec.loader.exec_module(gm)


def main():
    jar = ROOT / 'build/libs/hkrpg-server.jar'
    digest = hashlib.sha256(jar.read_bytes()).hexdigest()
    java = next((ROOT / '.tools').glob('jdk-21*/bin/java.exe'))
    # Reuse the existing disposable-database inspector's ownership checks and marker.
    runs = ROOT / 'build/smoke'
    runs.mkdir(exist_ok=True)
    directory = Path(tempfile.mkdtemp(prefix='gacha-rarity-trial-login-', dir=runs))
    helpers = directory / 'helpers'
    helpers.mkdir()
    (directory / 'data').mkdir()
    flags = subprocess.CREATE_NO_WINDOW if os.name == 'nt' else 0
    sources = [ROOT / 'scripts/fixtures/java/org/hkrpg/server/protocol' / (name + '.java')
               for name in ('NativeGmPlayerProbe', 'GachaRarityFixtureInspector')]
    subprocess.run([str(java.with_name('javac.exe')), '-encoding', 'UTF-8', '-cp', str(jar),
                    '-d', str(helpers), *map(str, sources)], check=True, capture_output=True,
                   creationflags=flags)
    http, mongo, game = gm.free_port(), gm.free_port(), gm.free_port(socket.SOCK_DGRAM)
    config = {'protocol': {'wireVersion': '4.6.0'}, 'resourceDir': str(ROOT / 'resources/4.6'),
              'httpServer': {'bindAddress': '127.0.0.1', 'bindPort': http},
              'gameServer': {'bindAddress': '127.0.0.1', 'bindPort': game},
              'internalMongoServer': {'address': '127.0.0.1', 'port': mongo,
                                      'filePath': './data/database.mv'}}
    (directory / 'config.json').write_text(json.dumps(config), encoding='utf-8')
    (directory / 'data/Gameplay46.json').write_text(json.dumps({'activities': [
        {'panelId': 20003, 'moduleId': 2000401, 'beginTime': 0,
         'endTime': int(time.time()) + 86400}]}), encoding='utf-8')
    base = f'http://127.0.0.1:{http}'
    java_flags = [f'-Djdk.net.unixdomain.tmpdir={directory / "tcp-fallback"}',
                  '-Dio.netty.eventLoopThreads=2']
    server = bootstrap = None
    logs = []
    stops = 0

    def request(route, body=None):
        req = urllib.request.Request(base + route,
            data=None if body is None else json.dumps(body).encode(),
            headers={'Content-Type': 'application/json'})
        with urllib.request.urlopen(req, timeout=5) as response:
            return json.load(response)

    def start(index):
        nonlocal server
        log = (directory / f'server-{index}.log').open('w', encoding='utf-8')
        logs.append(log)
        server = subprocess.Popen([str(java), *java_flags, '-Xmx1536m', '-jar', str(jar),
            '--no-handbook'], cwd=directory, stdin=subprocess.PIPE, stdout=log,
            stderr=subprocess.STDOUT, creationflags=flags)
        gm.wait_for(lambda: server.poll() is None and request('/health/ready')['ready'], 90)

    def stop():
        nonlocal server, stops
        server.stdin.write(b'stop\n')
        server.stdin.flush()
        server.wait(timeout=45)
        assert server.returncode == (1000 if os.name == 'nt' else 232), 'Abnormal server shutdown'
        server.stdin.close()
        server = None
        stops += 1

    def probe(index, credentials, **options):
        run = subprocess.run([str(java), *java_flags, '-cp', str(jar),
            'org.hkrpg.server.protocol.LoopbackTrialRecoveryProbe', str(game)],
            input=json.dumps(dict(credentials, **options)).encode(), cwd=directory,
            capture_output=True, timeout=40, creationflags=flags)
        (directory / f'trial-{index}.log').write_bytes(run.stdout + run.stderr)
        assert run.returncode == 0, f'Trial probe failed: {directory / f"trial-{index}.log"}'
        result = json.loads(next(line for line in run.stdout.decode('utf-8').splitlines()
                                 if line.startswith('{"uid":')))
        assert result['passed'] and result['activeTrialSnapshot']
        if options.get('resume'):
            assert result['activeTrialNotification']
        return result

    try:
        start(0)
        account = request('/hkrpg_cn/mdk/shield/api/login',
            {'account': 'trial-login-' + uuid.uuid4().hex, 'password': 'owned-trial-smoke'})['data']['account']
        combo = request('/hkrpg_cn/combo/granter/login/v2/login',
            {'data': json.dumps({'uid': account['uid'], 'token': account['token']})})
        credentials = {'accountUid': account['uid'], 'comboToken': combo['data']['combo_token']}
        credentials_file = directory / 'credentials.json'
        credentials_file.write_text(json.dumps(credentials), encoding='utf-8')
        log = (directory / 'bootstrap.log').open('w', encoding='utf-8')
        logs.append(log)
        bootstrap = subprocess.Popen([str(java), *java_flags, '-cp',
            str(helpers) + os.pathsep + str(jar), 'org.hkrpg.server.protocol.NativeGmPlayerProbe',
            str(game), str(credentials_file), str(directory)], cwd=directory,
            stdout=log, stderr=subprocess.STDOUT, creationflags=flags)
        snapshot_file = directory / 'snapshot.json'
        snapshot = gm.wait_for(lambda: json.loads(snapshot_file.read_text(encoding='utf-8'))
                               if snapshot_file.exists() else None, 40)
        uid = snapshot['uid']
        (directory / 'quit').touch()
        bootstrap.wait(timeout=40)
        assert bootstrap.returncode == 0
        bootstrap = None
        stop()
        (directory / 'rarity-fixture.json').write_text(
            json.dumps({'testFixture': True, 'uid': uid}), encoding='utf-8')
        seeded = subprocess.run([str(java), *java_flags, '-cp', str(helpers) + os.pathsep + str(jar),
            'org.hkrpg.server.protocol.GachaRarityFixtureInspector', 'seedTrial', str(directory)],
            cwd=ROOT, capture_output=True, timeout=60, check=True, creationflags=flags)
        (directory / 'fixture.log').write_bytes(seeded.stdout + seeded.stderr)
        start(1)
        active = probe('start', credentials)
        options = {'resume': True, 'expectedHome': active['home'],
                   'expectedHomeLineup': active['homeLineup']}
        reconnected = probe('reconnect', credentials, **options)
        stop()
        start(2)
        restarted = probe('restart-and-leave', credentials, **options, leave=True)
        stop()
        for result in (active, reconnected, restarted):
            assert result['uid'] == uid
            for key in ('trialScene', 'trialActors', 'trialLineup'):
                assert result[key] == active[key], f'Saved trial changed: {key}'
        assert restarted['originalHomeAndPartyRestored']
        assert hashlib.sha256(jar.read_bytes()).hexdigest() == digest
        report = {'passed': True, 'jarSHA256': digest, 'uid': uid, 'normalStops': stops,
                  'isolatedH2': True, 'fixtureMainMission': 1000510,
                  'fixtureTrialWindow': {'panelId': 20003, 'moduleId': 2000401},
                  'operatorDataModified': False, 'clientGameplayVerified': False,
                  'active': active, 'reconnected': reconnected, 'restarted': restarted,
                  'runtimeDirectory': str(directory)}
        output = ROOT / 'build/trial-ui-recovery-native.json'
        output.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding='utf-8')
        print(json.dumps({'passed': True, 'normalStops': stops, 'sameUid': uid,
                          'activeTrialNotifications': 2, 'originalHomeAndPartyRestored': True,
                          'report': str(output)}, ensure_ascii=False))
    finally:
        if bootstrap and bootstrap.poll() is None:
            bootstrap.terminate()
            bootstrap.wait(timeout=15)
        if server and server.poll() is None:
            server.terminate()
            server.wait(timeout=15)
        for log in logs:
            log.close()


if __name__ == '__main__':
    main()
