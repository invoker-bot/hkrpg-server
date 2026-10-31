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
    parser.add_argument('--awakening-door', action='store_true', help='继续校验醒来后首个控制台、成对开门、后续战斗目标及重连/H2保存；对白为合成回执')
    parser.add_argument('--awakening-battle', action='store_true', help='继续校验女主真实出战编号结算、失败重试、防重复奖励、后续两场战斗与对白接取、生命能量及H2恢复；战报为合成数据')
    parser.add_argument('--avatar-progression', action='store_true', help='继续校验两场战斗经验、三本实际材料升级、扣费与角色同步及H2重启保存')
    parser.add_argument('--space-anchors', action='store_true', help='继续校验收容舱段锚点靠近激活、地图查询、实际传送和H2重启；移动为合成请求')
    parser.add_argument('--base-anchor-box', action='store_true', help='校验基座舱段原始盒形锚点移动激活、地图状态及重连/H2重启；移动为合成请求')
    parser.add_argument('--story-party', action='store_true', help='继续校验三月七获得、丹恒奖励入队、原始三人编队、手动编辑和H2重启；对白为合成回执')
    parser.add_argument('--preset-lineup', action='store_true', help='继续校验备用队伍编辑、切换及重连/H2重启；包含剧情队伍测试')
    parser.add_argument('--key-console', action='store_true', help='继续到使用密钥目标，校验前置战斗与对白门槛、控制台和绑定门、后续对白、同进程重连和H2重启')
    parser.add_argument('--story-mechanisms', action='store_true', help='继续校验两座剧情桥梁的前置对白、精确联动、重连/H2保存，以及持钥门和升降机的无钥匙拒绝；不播放真实客户端演出')
    parser.add_argument('--main-control-transfer', action='store_true', help='继续到电梯前战斗及演出完成，校验未持通行证仍可按原任务切往主控舱段、断线恢复及H2重启；战报和演出回执为合成请求')
    parser.add_argument('--station-story', action='store_true', help='继续校验姬子兼容试用、科员对话、支援舱段双色桥、末日兽战报及醒来指定落点；合成输入，不等同实机演出')
    parser.add_argument('--live-station-transfer', action='store_true', help='完成末日兽后在同一会话等待切图恢复，检查服务端场景通知、迟到请求与重启保存；对白为合成回执')
    parser.add_argument('--interrupt-opening-transfer', choices=('reconnect', 'restart'),
                        help='完成选角演出后不发送切图请求，验证同进程重连或H2重启能续接原任务')
    parser.add_argument('--output', type=Path, default=ROOT / 'build/opening-battle-native.json')
    args = parser.parse_args()
    if args.preset_lineup:
        args.story_party = True
    if args.live_station_transfer:
        args.station_story = True
    if args.station_story:
        args.main_control_transfer = True
    if args.main_control_transfer:
        args.story_mechanisms = True
    if args.story_mechanisms:
        args.key_console = True
    if args.key_console:
        args.story_party = True
        if args.avatar_progression or args.space_anchors:
            parser.error('--key-console 的后续战斗另有经验和生命结果，请与升级/锚点快照分开运行')
    if args.avatar_progression or args.space_anchors or args.story_party:
        args.awakening_battle = True
    if args.awakening_battle:
        args.awakening_door = True
    if args.interrupt_opening_transfer or args.awakening_door:
        args.opening_transfer = True
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
    if args.awakening_door:
        guarded += [str(path.relative_to(source)) for path in (source / 'Config/Level/Mission/1000201').glob('*.json')]
        guarded += [f'Config/LevelOutput/SharedRuntimeGroup/Groups_P20002_F20002001/LevelGroup_P20002_F20002001_G{group}.json' for group in (3, 4, 5, 16)]
    if args.awakening_battle:
        guarded += ['Config/Level/Mission/1000201/Act/Act100020107.json']
        guarded += ['Config/LevelOutput/SharedRuntimeGroup/Groups_P20002_F20002001/LevelGroup_P20002_F20002001_G12.json']
    if args.avatar_progression:
        for name in ('AvatarConfig', 'AvatarPromotionConfig', 'ExpType', 'AvatarExpItemConfig', 'NPCMonsterData', 'MonsterDrop'):
            guarded.append(f'ExcelOutput/{name}.json')
    if args.space_anchors:
        guarded += ['Config/Level/Props/Common/InitLevelGraph_Prop_Common_CheckPoint_ActiveHint.json',
                    'Config/Props/Common/Prop_Common_RecoveryAnchor_01_Config.json']
    if args.base_anchor_box:
        fixture = json.loads((ROOT / 'src/test/resources/fixtures/base-zone-anchor-box-4.6.json').read_text(encoding='utf-8'))
        original = ROOT / fixture['sourcePath']
        assert hashlib.sha256(original.read_bytes()).hexdigest() == fixture['sha256']
        guarded.append(str(original.relative_to(source)))
    if args.story_party:
        guarded += ['ExcelOutput/AvatarConfig.json', 'ExcelOutput/ItemConfigAvatar.json', 'ExcelOutput/RewardData.json']
        guarded += [str(path.relative_to(source)) for path in (source / 'Config/Level/Mission/1000204').glob('*.json')]
    if args.key_console:
        guarded += [str(path.relative_to(source)) for path in (source / 'Config/Level/Mission/1000202').glob('*.json')]
    if args.story_mechanisms:
        fixture = json.loads((ROOT / 'src/test/resources/fixtures/story-mechanisms-4.6.json').read_text(encoding='utf-8'))
        guarded += list(fixture['sourceSHA256'])
        for name, source_digest in fixture['sourceSHA256'].items():
            assert hashlib.sha256((source / name).read_bytes()).hexdigest() == source_digest, name
    if args.main_control_transfer:
        fixture = json.loads((ROOT / 'src/test/resources/fixtures/main-control-transfer-4.6.json').read_text(encoding='utf-8'))
        guarded += list(fixture['sourceSHA256'])
        for name, source_digest in fixture['sourceSHA256'].items():
            assert hashlib.sha256((source / name).read_bytes()).hexdigest() == source_digest, name
    if args.station_story:
        for name in ('station-trial-4.6.json','station-bridges-4.6.json','story-transfers-4.6.json','station-boss-event-4.6.json'):
            fixture=json.loads((ROOT/'src/test/resources/fixtures'/name).read_text('utf-8'))
            guarded += list(fixture['sourceSHA256'])
            for path,source_digest in fixture['sourceSHA256'].items():
                assert hashlib.sha256((source/path).read_bytes()).hexdigest()==source_digest,path
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
    iterations = 11 if args.station_story else 7 if args.main_control_transfer else 6 if args.awakening_door else 5 if args.opening_transfer else 4 if args.barriers else 3 if args.doors else 2
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
                          else ['barriers-restart', 'transfer-reconnect'] if iteration == 3 and args.opening_transfer and args.interrupt_opening_transfer != 'restart'
                          else ['barriers-restart'] if iteration == 3
                          else ['transfer-restart', 'awakening-reconnect'] if iteration == 4 and args.awakening_door
                          else ['transfer-restart'] if iteration == 4 else ['awakening-restart'])
                if args.key_console and iteration == 4:
                    phases.append('key-console-reconnect')
                if args.story_mechanisms:
                    if iteration == 4:
                        phases += ['mechanisms-first', 'mechanisms-reconnect']
                    elif iteration == 5:
                        phases = ['mechanisms-restart']
                if args.main_control_transfer:
                    if iteration == 5:
                        phases += ['main-control-prepare', 'main-control-reconnect']
                    elif iteration == 6:
                        phases = ['main-control-restart']
                if args.station_story:
                    if iteration==6: phases.append('station-prepare')
                    elif iteration==7: phases=['station-blue','station-reconnect']
                    elif iteration==8: phases=['station-orange']
                    elif iteration==9: phases=['station-wakeup','station-wakeup-reconnect']
                    elif iteration==10: phases=['station-wakeup-restart']
                for phase in phases:
                    credentials = {'accountUid': account['uid'], 'comboToken': combo['data']['combo_token'], 'phase': phase,
                                   'doors': args.doors, 'readings': args.readings, 'barriers': args.barriers,
                                   'ambush': args.ambush, 'scriptedUnlock': args.scripted_unlock,
                                   'stellaronConsole': args.stellaron_console, 'characterChoice': args.character_choice,
                                   'openingTransfer': args.opening_transfer,
                                   'awakeningDoor': args.awakening_door,
                                   'awakeningBattle': args.awakening_battle,
                                   'avatarProgression': args.avatar_progression,
                                   'spaceAnchors': args.space_anchors,
                                   'baseAnchorBox': args.base_anchor_box,
                                   'storyParty': args.story_party,
                                   'presetLineup': args.preset_lineup,
                                   'keyConsole': args.key_console,
                                   'storyMechanisms': args.story_mechanisms,
                                   'mainControlTransfer': args.main_control_transfer,
                                   'stationStory': args.station_story,
                                   'liveStationTransfer': args.live_station_transfer,
                                   'interruptedTransfer': bool(args.interrupt_opening_transfer)}
                    probe = subprocess.run([str(java), *java_flags, '-cp', str(helpers) + os.pathsep + str(jar),
                                            'org.hkrpg.server.protocol.NativeOpeningStageWinProbe', str(game)],
                                           input=json.dumps(credentials), text=True, capture_output=True,
                                           encoding='utf-8', timeout=120, creationflags=flags)
                    (directory / f'probe-{phase}.log').write_text(probe.stderr, encoding='utf-8')
                    assert probe.returncode == 0, f'Opening probe failed: {directory / ("probe-" + phase + ".log")}'
                    snapshot = json.loads(probe.stdout.strip().splitlines()[-1])
                    if phase.startswith('station-'):
                        assert snapshot['passed'] and snapshot['stationPartyVerified']
                        assert snapshot['postBattleUnbindVerified'] == (phase == 'station-orange' or phase.startswith('station-wakeup'))
                        if phase == 'station-orange' and args.live_station_transfer:
                            assert snapshot['liveTransferVerified'] and snapshot['serverSceneNotifications'] == 1
                            assert snapshot['lateRequestKeptAnchor']
                        if phase in ('station-wakeup-reconnect','station-wakeup-restart'):
                            previous=next(row for row in snapshots if row['phase']=='station-wakeup')
                            assert snapshot['credits']==previous['credits'],'Reconnect duplicated/lost waking rewards'
                        snapshots.append(snapshot)
                        time.sleep(1)
                        continue
                    if phase.startswith('main-control-'):
                        assert snapshot['passed'] and snapshot['certificateAbsent']
                        assert snapshot['transferPending'] == (phase == 'main-control-prepare')
                        assert snapshot['mainControlArrival'] == (phase != 'main-control-prepare')
                        if phase == 'main-control-restart':
                            previous = next(row for row in snapshots if row['phase'] == 'main-control-reconnect')
                            assert snapshot['credits'] == previous['credits'], 'Restart duplicated/lost arrival rewards'
                        snapshots.append(snapshot)
                        time.sleep(1)
                        continue
                    if phase.startswith('mechanisms-'):
                        mechanics = snapshot['storyMechanisms']
                        assert snapshot['passed'] and mechanics['restored'] == (phase != 'mechanisms-first')
                        assert mechanics['bridgesOpen'] == ['3:300012', '6:300001']
                        assert all(mechanics[key] for key in ('unrelatedBridgeClosed', 'keyDoorPreserved', 'keyOwnershipRequired', 'nextBattlePending'))
                        if phase != 'mechanisms-first':
                            previous = next(row for row in snapshots if row['phase'] == 'mechanisms-first')
                            assert mechanics['credits'] == previous['storyMechanisms']['credits'], 'Reconnect duplicated/lost mission rewards'
                        snapshots.append(snapshot)
                        time.sleep(1)
                        continue
                    if phase == 'key-console-reconnect':
                        assert snapshot['passed'] and snapshot['keyConsole']['restored']
                        assert snapshot['keyConsole']['doorOpen'] and snapshot['keyConsole']['nextPerformancePending']
                        snapshots.append(snapshot)
                        time.sleep(1)
                        continue
                    if phase.startswith('awakening-'):
                        assert snapshot['passed'] and snapshot['awakeningDoorVerified']
                        assert snapshot['nextBattlePending'] == (not args.awakening_battle)
                        assert snapshot['awakeningBattleVerified'] == args.awakening_battle
                        assert snapshot['inclinationAcknowledgements'] == (4 if args.awakening_battle else 0)
                        assert snapshot['stationDestructiblesVerified'] == args.awakening_battle
                        if args.awakening_battle and phase == 'awakening-restart':
                            previous = next(row for row in snapshots if row['phase'] == 'awakening-reconnect')
                            assert snapshot['credits'] == previous['credits'], 'Restart duplicated/lost battle rewards'
                            if args.avatar_progression:
                                for key in ('level', 'exp', 'books'):
                                    assert snapshot['avatarProgression'][key] == previous['avatarProgression'][key]
                        if args.avatar_progression:
                            assert snapshot['avatarProgression']['level'] == 10
                            assert snapshot['avatarProgression']['exp'] == 1512
                        if args.space_anchors:
                            anchor = snapshot['spaceAnchor']
                            assert anchor['activationPersisted'] and anchor['mapConsistent'] and anchor['teleportVerified']
                            assert anchor['mapping'] == 2000204 and anchor['restored'] == (phase == 'awakening-restart')
                        if args.story_party:
                            party = snapshot['storyParty']
                            assert party['owned'] == [1001, 1002, 8001]
                            assert party['roster'] == [1002, 8001] and party['leader'] == 0
                            assert party['marchJoined'] and party['danHengJoined'] and party['authoredLineupCompleted']
                            assert party['restored'] == (phase == 'awakening-restart')
                            assert party['presetTeamsVerified'] == args.preset_lineup
                        if args.key_console:
                            console = snapshot['keyConsole']
                            assert console['doorOpen'] and console['nextPerformancePending']
                            assert console['restored'] == (phase == 'awakening-restart')
                        snapshots.append(snapshot)
                        time.sleep(1)
                        continue
                    if phase.startswith('transfer-'):
                        assert snapshot['passed'] and snapshot['openingTransferVerified']
                        assert snapshot['enterSceneRequests'] == 0
                        assert snapshot['interruptedTransferRecovery'] == bool(args.interrupt_opening_transfer)
                        assert snapshot['awakeningDoorVerified'] == (args.awakening_door and phase == 'transfer-restart')
                        snapshots.append(snapshot)
                        time.sleep(1)
                        continue
                    assert snapshot['passed'] and snapshot['openingBattleFinished'] and snapshot['nextObjectiveAccepted']
                    if args.base_anchor_box:
                        assert snapshot['baseAnchorBox'] == {'mapping': 2000102, 'restored': phase != 'first', 'activated': True}
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
                    assert snapshot['openingTransferVerified'] == (args.opening_transfer and phase == 'barriers-restart' and not args.interrupt_opening_transfer)
                    assert snapshot['openingTransferPending'] == (bool(args.interrupt_opening_transfer) and phase == 'barriers-restart')
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
              'normalStops': stops, 'interruptedTransfer': args.interrupt_opening_transfer,
              'liveStationTransfer': args.live_station_transfer,
              'sourceResourcesModified': False, 'clientGameplayVerified': False}
    output = args.output
    output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    print(json.dumps({'passed': True, 'requests': sum(row['requests'] for row in snapshots),
                      'normalStops': stops, 'report': str(output), 'clientGameplayVerified': False}))


if __name__ == '__main__':
    main()
