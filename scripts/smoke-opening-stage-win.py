"""隔离新号的卡芙卡开场、首战、紫色恢复物及 H2 重启检查；不操作官方客户端。"""
import hashlib
import argparse
import json
import os
from pathlib import Path
import socket
import subprocess
import tempfile
import time
import urllib.error
import urllib.request
import uuid

ROOT = Path(__file__).resolve().parents[1]


def free_port(kind=socket.SOCK_STREAM):
    with socket.socket(type=kind) as connection:
        connection.bind(('127.0.0.1', 0))
        return connection.getsockname()[1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--doors', action='store_true', help='校验控制台、两扇剧情门及两次同 UID H2 重启')
    parser.add_argument('--output', type=Path, default=ROOT / 'build/opening-battle-native.json')
    args = parser.parse_args()
    source = ROOT / 'resources/4.6'
    jar = ROOT / 'build/libs/hkrpg-server.jar'
    java = next((ROOT / '.tools').glob('jdk-21*/bin/java.exe'))
    digest = hashlib.sha256(jar.read_bytes()).hexdigest()
    guarded = ['ExcelOutput/MainMission.json', 'ExcelOutput/PlaneEvent.json', 'ExcelOutput/StageConfig.json']
    guarded += ['ExcelOutput/SpecialAvatar.json', 'ExcelOutput/AvatarConfigTrial.json',
                'ExcelOutput/TutorialData.json', 'ExcelOutput/TutorialGuideGroup.json',
                'ExcelOutput/MazeProp.json',
                'Config/ConfigAdventureAbility/LocalPlayer/LocalPlayer_Kafka_00_Ability.json']
    guarded += [f'Config/LevelOutput/SharedRuntimeGroup/Groups_P20001_F20001001/LevelGroup_P20001_F20001001_G{group}.json'
                for group in (5, 6, 36, 91, 93)]
    guarded += [str(path.relative_to(source)) for path in (source / 'Config/Level/Mission/1000101').glob('*.json')]
    before = {name: hashlib.sha256((source / name).read_bytes()).hexdigest() for name in guarded}
    runs = ROOT / 'build/smoke'
    runs.mkdir(exist_ok=True)
    directory = Path(tempfile.mkdtemp(prefix='opening-stage-win-', dir=runs))
    assert directory.resolve().is_relative_to(runs.resolve())
    helpers = directory / 'helpers'
    helpers.mkdir()
    flags = subprocess.CREATE_NO_WINDOW if os.name == 'nt' else 0
    subprocess.run([str(java.with_name('javac.exe')), '-encoding', 'UTF-8', '-cp', str(jar), '-d', str(helpers),
                    str(ROOT / 'scripts/fixtures/java/org/hkrpg/server/protocol/NativeOpeningStageWinProbe.java')],
                   check=True, capture_output=True, creationflags=flags)
    http, mongo, game = free_port(), free_port(), free_port(socket.SOCK_DGRAM)
    config = {'protocol': {'wireVersion': '4.6.0'}, 'resourceDir': str(source),
              'httpServer': {'bindAddress': '127.0.0.1', 'bindPort': http},
              'gameServer': {'bindAddress': '127.0.0.1', 'bindPort': game},
              'internalMongoServer': {'address': '127.0.0.1', 'port': mongo, 'filePath': './data/database.mv'}}
    (directory / 'config.json').write_text(json.dumps(config), encoding='utf-8')
    java_flags = ['-Dfile.encoding=UTF-8', '-Dio.netty.eventLoopThreads=2',
                  '-Djdk.net.unixdomain.tmpdir=' + str(directory / 'tcp-fallback')]
    base = f'http://127.0.0.1:{http}'

    def request(route, body=None):
        req = urllib.request.Request(base + route, data=None if body is None else json.dumps(body).encode(),
                                     headers={'Content-Type': 'application/json'})
        with urllib.request.urlopen(req, timeout=10) as response:
            return json.load(response)

    account_name = 'opening_stage_' + uuid.uuid4().hex + '@example.invalid'
    snapshots, stops = [], 0
    iterations = 3 if args.doors else 2
    for iteration in range(iterations):
        with (directory / f'server-{iteration}.log').open('w', encoding='utf-8') as log:
            process = subprocess.Popen([str(java), *java_flags, '-Xmx1536m', '-jar', str(jar), '--no-handbook'],
                                       cwd=directory, stdin=subprocess.PIPE, stdout=log, stderr=subprocess.STDOUT,
                                       creationflags=flags)
            try:
                deadline = time.monotonic() + 180
                while True:
                    assert process.poll() is None, f'Isolated server exited: {directory}'
                    try:
                        if request('/health/ready')['ready']:
                            break
                    except (OSError, urllib.error.URLError):
                        pass
                    if time.monotonic() > deadline:
                        raise RuntimeError(f'Isolated readiness timeout: {directory}')
                    time.sleep(.2)
                login = request('/hkrpg_cn/mdk/shield/api/login', {'account': account_name, 'password': 'isolated-opening-test'})
                assert login['retcode'] == 0
                account = login['data']['account']
                combo = request('/hkrpg_cn/combo/granter/login/v2/login',
                                {'data': json.dumps({'uid': account['uid'], 'token': account['token']})})
                assert combo['retcode'] == 0
                for phase in (['first', 'reconnect'] if iteration == 0 else ['restart' if iteration == 1 else 'doors-restart']):
                    credentials = {'accountUid': account['uid'], 'comboToken': combo['data']['combo_token'], 'phase': phase, 'doors': args.doors}
                    probe = subprocess.run([str(java), *java_flags, '-cp', str(helpers) + os.pathsep + str(jar),
                                            'org.hkrpg.server.protocol.NativeOpeningStageWinProbe', str(game)],
                                           input=json.dumps(credentials), text=True, capture_output=True,
                                           encoding='utf-8', timeout=120, creationflags=flags)
                    (directory / f'probe-{phase}.log').write_text(probe.stderr, encoding='utf-8')
                    assert probe.returncode == 0, f'Opening probe failed: {directory / ("probe-" + phase + ".log")}'
                    snapshot = json.loads(probe.stdout.strip().splitlines()[-1])
                    assert snapshot['passed'] and snapshot['openingBattleFinished'] and snapshot['nextObjectiveAccepted']
                    assert snapshot['openingKafkaVerified'] and snapshot['tutorialProgressVerified']
                    assert snapshot['openingSceneGatesVerified']
                    assert snapshot['recoveryPropsVerified']
                    assert snapshot['capturedPropVerified']
                    assert snapshot['openingChestVerified']
                    assert snapshot['storyDoorsVerified'] == args.doors
                    snapshots.append(snapshot)
                    time.sleep(1)
            finally:
                if process.poll() is None:
                    try:
                        process.stdin.write(b'stop\n')
                        process.stdin.flush()
                        process.wait(timeout=40)
                        assert process.returncode == (1000 if os.name == 'nt' else 232), directory
                        stops += 1
                    finally:
                        if process.poll() is None:
                            process.terminate()
                            process.wait(timeout=15)
                        process.stdin.close()
    assert len({snapshot['uid'] for snapshot in snapshots}) == 1 and stops == iterations
    assert before == {name: hashlib.sha256((source / name).read_bytes()).hexdigest() for name in guarded}
    assert hashlib.sha256(jar.read_bytes()).hexdigest() == digest
    report = {'passed': True, 'jarSHA256': digest, 'runtimeDirectory': str(directory), 'snapshots': snapshots,
              'normalStops': stops, 'sourceResourcesModified': False, 'clientGameplayVerified': False}
    output = args.output
    output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    print(json.dumps({'passed': True, 'requests': sum(row['requests'] for row in snapshots),
                      'normalStops': stops, 'report': str(output), 'clientGameplayVerified': False}))


if __name__ == '__main__':
    main()
