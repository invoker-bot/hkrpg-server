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
    parser.add_argument('--readings', action='store_true', help='校验接待中心两份阅读物拾取、背包、防重和重启恢复；移动为合成请求')
    parser.add_argument('--barriers', action='store_true', help='校验大厅及走廊击杀屏障卸载、失败保留和重连/H2重启；包含剧情门检查')
    parser.add_argument('--ambush', action='store_true', help='回放怪物空命中回调、实际伏击入战和胜败结算；包含屏障检查')
    parser.add_argument('--scripted-unlock', action='store_true', help='校验走廊后续战斗、黑屏阶段控制台解锁、剧情接续及重连/H2恢复')
    parser.add_argument('--stellaron-console', action='store_true', help='继续到星核控制台，校验任务解锁、交互、后续演出接取及重连/H2恢复；对话为合成回执')
    parser.add_argument('--character-choice', action='store_true', help='继续校验真实昵称请求、重连后明确选角及选角后H2重启；不播放客户端演出')
    parser.add_argument('--opening-transfer', action='store_true', help='继续校验选角后按原始脚本切图、后续对白接取及重连/H2保存；不播放客户端演出')
    parser.add_argument('--output', type=Path, default=ROOT / 'build/opening-battle-native.json')
    args = parser.parse_args()
    if args.opening_transfer:
        args.character_choice = True
    if args.character_choice:
        args.stellaron_console = True
    if args.stellaron_console:
        args.scripted_unlock = True
    if args.ambush or args.scripted_unlock:
        args.barriers = True
    if args.barriers:
        args.doors = True
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
                for group in (5, 6, 17, 21, 33, 36, 44, 91, 93)]
    if args.readings:
        guarded += ['ExcelOutput/RewardData.json', 'ExcelOutput/ItemConfig.json', 'ExcelOutput/InteractConfig.json',
                    'Config/Level/Props/Common/InitLevelGraph_Prop_Common_PickupReadings.json']
    guarded += ['ExcelOutput/ItemConfig.json']
    if args.stellaron_console:
        guarded += ['Config/Level/Props/Chap00/InitLevelGraph_Prop_Chap00_Console01_OpenDoor_03.json']
    guarded += [str(path.relative_to(source)) for path in (source / 'Config/Level/Mission/1000101').glob('*.json')]
    if args.opening_transfer:
        guarded += ['ExcelOutput/MapEntrance.json', 'ExcelOutput/MazePlane.json',
                    'Config/LevelOutput/RuntimeFloor/P20002_F20002001.json',
                    'Config/LevelOutput/SharedRuntimeGroup/Groups_P20002_F20002001/LevelGroup_P20002_F20002001_G1.json']
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
    iterations = 5 if args.opening_transfer else 4 if args.barriers else 3 if args.doors else 2
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
                phases = (['first', 'reconnect'] if iteration == 0 else ['restart'] if iteration == 1
                          else ['doors-restart', 'barriers-reconnect'] if iteration == 2 and args.barriers
                          else ['doors-restart'] if iteration == 2
                          else ['barriers-restart', 'transfer-reconnect'] if iteration == 3 and args.opening_transfer
                          else ['barriers-restart'] if iteration == 3 else ['transfer-restart'])
                for phase in phases:
                    credentials = {'accountUid': account['uid'], 'comboToken': combo['data']['combo_token'], 'phase': phase,
                                   'doors': args.doors, 'readings': args.readings, 'barriers': args.barriers,
                                   'ambush': args.ambush, 'scriptedUnlock': args.scripted_unlock,
                                   'stellaronConsole': args.stellaron_console, 'characterChoice': args.character_choice,
                                   'openingTransfer': args.opening_transfer}
                    probe = subprocess.run([str(java), *java_flags, '-cp', str(helpers) + os.pathsep + str(jar),
                                            'org.hkrpg.server.protocol.NativeOpeningStageWinProbe', str(game)],
                                           input=json.dumps(credentials), text=True, capture_output=True,
                                           encoding='utf-8', timeout=120, creationflags=flags)
                    (directory / f'probe-{phase}.log').write_text(probe.stderr, encoding='utf-8')
                    assert probe.returncode == 0, f'Opening probe failed: {directory / ("probe-" + phase + ".log")}'
                    snapshot = json.loads(probe.stdout.strip().splitlines()[-1])
                    if phase.startswith('transfer-'):
                        assert snapshot['passed'] and snapshot['openingTransferVerified']
                        snapshots.append(snapshot)
                        time.sleep(1)
                        continue
                    assert snapshot['passed'] and snapshot['openingBattleFinished'] and snapshot['nextObjectiveAccepted']
                    assert snapshot['openingKafkaVerified'] and snapshot['tutorialProgressVerified']
                    assert snapshot['openingSceneGatesVerified']
                    assert snapshot['recoveryPropsVerified']
                    assert snapshot['capturedPropVerified']
                    assert snapshot['ordinaryBoxRewardsVerified']
                    assert snapshot['openingChestVerified']
                    assert snapshot['storyDoorsVerified'] == args.doors
                    assert snapshot['deskReadingsVerified'] == args.readings
                    assert snapshot['storyBarriersVerified'] == (args.barriers and (phase == 'doors-restart' or phase.startswith('barriers-')))
                    assert snapshot['monsterAmbushVerified'] == (args.ambush and phase == 'doors-restart')
                    assert snapshot['scriptedDoorUnlockVerified'] == (args.scripted_unlock and (phase == 'doors-restart' or phase.startswith('barriers-')))
                    assert snapshot['stellaronConsoleVerified'] == (args.stellaron_console and (phase == 'doors-restart' or phase.startswith('barriers-')))
                    assert snapshot['characterChoiceVerified'] == (args.character_choice and (phase == 'doors-restart' or phase.startswith('barriers-')))
                    assert snapshot['openingTransferVerified'] == (args.opening_transfer and phase == 'barriers-restart')
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
