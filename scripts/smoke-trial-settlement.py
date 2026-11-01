"""隔离景元试用 KCP/H2：神君报告、一次胜利、实际领奖、防重和正常重启。"""
import base64
import copy
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import socket
import subprocess
import sys
import tempfile
import time
import urllib.request
import uuid

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('gm_smoke', ROOT / 'scripts/smoke-gm-console.py')
gm = importlib.util.module_from_spec(spec)
spec.loader.exec_module(gm)
sys.path.insert(0, str(ROOT / '.tools/client-analysis-libs'))
from google.protobuf.descriptor_pb2 import FileDescriptorSet
from google.protobuf.descriptor_pool import DescriptorPool
from google.protobuf.json_format import ParseDict
from google.protobuf.message_factory import GetMessageClass


def main():
    jar = ROOT / 'build/libs/hkrpg-server.jar'
    digest = hashlib.sha256(jar.read_bytes()).hexdigest()
    source_names = ['BattleEventConfig', 'BattleEventData', 'AvatarConfig', 'SpecialAvatar',
                    'AvatarDemoConfig', 'RewardData']
    source_hashes = {f'ExcelOutput/{name}.json': hashlib.sha256(
        (ROOT / 'resources/4.6/ExcelOutput' / (name + '.json')).read_bytes()).hexdigest()
        for name in source_names}
    java = next((ROOT / '.tools').glob('jdk-21*/bin/java.exe'))
    runs = ROOT / 'build/smoke'
    runs.mkdir(exist_ok=True)
    directory = Path(tempfile.mkdtemp(prefix='gacha-rarity-trial-settlement-', dir=runs))
    helpers = directory / 'helpers'
    helpers.mkdir()
    (directory / 'data').mkdir()
    flags = subprocess.CREATE_NO_WINDOW if os.name == 'nt' else 0
    sources = [ROOT / 'scripts/fixtures/java/org/hkrpg/server/protocol' / (name + '.java')
               for name in ('NativeGmPlayerProbe', 'GachaRarityFixtureInspector')]
    subprocess.run([str(java.with_name('javac.exe')), '-encoding', 'UTF-8', '-cp', str(jar),
                    '-d', str(helpers), *map(str, sources)], check=True, capture_output=True,
                   creationflags=flags)
    pool = DescriptorPool()
    for definition in FileDescriptorSet.FromString(
            (ROOT / 'src/main/resources/protocol/4.6/descriptor.pb').read_bytes()).file:
        pool.Add(definition)
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
    java_flags = ['-Dfile.encoding=UTF-8', '-Dio.netty.eventLoopThreads=2',
                  f'-Djdk.net.unixdomain.tmpdir={directory / "tcp-fallback"}']
    server = probe = None
    logs, commands = [], []
    stops = 0
    stage = 312040

    def request(route, body=None):
        req = urllib.request.Request(base + route,
            data=None if body is None else json.dumps(body).encode(),
            headers={'Content-Type': 'application/json'})
        with urllib.request.urlopen(req, timeout=5) as response:
            return json.load(response)

    def start(index):
        nonlocal server, probe
        log = (directory / f'server-{index}.log').open('w', encoding='utf-8')
        logs.append(log)
        server = subprocess.Popen([str(java), *java_flags, '-Xmx1536m', '-jar', str(jar),
            '--no-handbook'], cwd=directory, stdin=subprocess.PIPE, stdout=log,
            stderr=subprocess.STDOUT, creationflags=flags)
        gm.wait_for(lambda: server.poll() is None and request('/health/ready')['ready'], 90)
        account = request('/hkrpg_cn/mdk/shield/api/login',
            {'account': account_name, 'password': 'owned-trial-settlement'})['data']['account']
        combo = request('/hkrpg_cn/combo/granter/login/v2/login',
            {'data': json.dumps({'uid': account['uid'], 'token': account['token']})})
        credentials = directory / 'credentials.json'
        credentials.write_text(json.dumps({'accountUid': account['uid'],
            'comboToken': combo['data']['combo_token']}), encoding='utf-8')
        for name in ('quit', 'snapshot.json', 'equipment-request.json'):
            (directory / name).unlink(missing_ok=True)
        log = (directory / f'probe-{index}.log').open('w', encoding='utf-8')
        logs.append(log)
        probe = subprocess.Popen([str(java), *java_flags, '-cp',
            str(helpers) + os.pathsep + str(jar), 'org.hkrpg.server.protocol.NativeGmPlayerProbe',
            str(game), str(credentials), str(directory)], cwd=directory,
            stdout=log, stderr=subprocess.STDOUT, creationflags=flags)
        return gm.wait_for(snapshot, 45)

    def snapshot():
        p = directory / 'snapshot.json'
        return json.loads(p.read_text(encoding='utf-8')) if p.exists() else None

    def stop():
        nonlocal server, probe, stops
        (directory / 'quit').touch()
        probe.wait(timeout=40)
        assert probe.returncode == 0, 'KCP probe did not close normally'
        probe = None
        server.stdin.write(b'stop\n')
        server.stdin.flush()
        server.wait(timeout=45)
        assert server.returncode == (1000 if os.name == 'nt' else 232), 'Abnormal server shutdown'
        server.stdin.close()
        server = None
        stops += 1

    def call(name, fields=None, success=True):
        message = GetMessageClass(pool.FindMessageTypeByName(name))()
        ParseDict(fields or {}, message)
        key = uuid.uuid4().hex
        control = {'requestId': key, 'command': name, 'success': success,
                   'wire': base64.b64encode(message.SerializeToString()).decode()}
        if name == 'TakeTrialActivityRewardCsReq' and success:
            control['packetId'] = 41
        target = directory / 'equipment-request.json'
        temporary = target.with_suffix('.tmp')
        temporary.write_text(json.dumps(control), encoding='utf-8')
        temporary.replace(target)
        value = gm.wait_for(lambda: (lambda s: s if s and
            s.get('equipmentReply', {}).get('requestId') == key else None)(snapshot()), 45)
        target.unlink()
        response = value['equipmentReply']['response']
        commands.append({'command': name, 'retcode': response.get('retcode', 0)})
        return response

    def completion(taken):
        response = call('GetTrialActivityDataCsReq')
        rows = response.get('trial_activity_info_list', [])
        assert len(rows) == 1 and rows[0]['stage_id'] == stage
        assert rows[0].get('taken_reward', False) == taken
        assert response.get('activity_stage_id', 0) == 0
        return response

    def wallet(s):
        return {'hcoin': s['basic'].get('hcoin', 0),
                'materials': {str(r['tid']): r.get('num', 0)
                              for r in s['bag'].get('material_list', [])}}

    account_name = 'trial-settlement-' + uuid.uuid4().hex
    try:
        initial = start(0)
        uid = initial['uid']
        stop()
        (directory / 'rarity-fixture.json').write_text(
            json.dumps({'testFixture': True, 'uid': uid}), encoding='utf-8')
        seeded = subprocess.run([str(java), *java_flags, '-cp', str(helpers) + os.pathsep + str(jar),
            'org.hkrpg.server.protocol.GachaRarityFixtureInspector', 'seedTrial', str(directory)],
            cwd=ROOT, capture_output=True, timeout=60, check=True, creationflags=flags)
        (directory / 'fixture.log').write_bytes(seeded.stdout + seeded.stderr)
        current = start(1)
        assert current['uid'] == uid
        home = call('GetCurSceneInfoCsReq')['scene']
        home_lineup = call('GetCurLineupDataCsReq')['lineup']
        call('StartTrialActivityCsReq', {'stage_id': stage})
        scene = call('GetCurSceneInfoCsReq')['scene']
        assert scene['entry_id'] == 30524001
        enemies = [e for g in scene['entity_group_list'] for e in g.get('entity_list', []) if 'npc_monster' in e]
        assert len(enemies) == 1 and enemies[0]['group_id'] == 8 and enemies[0]['inst_id'] == 200001
        response = call('SceneCastSkillCsReq', {'cast_entity_id': 1,
            'attacked_by_entity_id': scene['leader_entity_id'],
            'hit_target_entity_id_list': [enemies[0]['entity_id']]})
        battle = response['battle_info']
        assert battle['stage_id'] == stage and battle['monster_wave_list']
        actors = battle['battle_avatar_list']
        assert {r['id'] for r in actors} == {2000091, 2000092}
        stats = {'round_cnt': 1, 'battle_avatar_list': [
            {'id': r['id'], 'avatar_type': r['avatar_type'], 'avatar_status':
             {'max_hp': 1000, 'left_hp': 1000, 'max_sp': 130, 'left_sp': 0}} for r in actors],
            'BDCHEJKPOAF': [{'battle_event_id': 11203, 'status':
                            {'sp_bar': {'cur_sp': 0, 'max_sp': 1000}}}]}
        report = {'battle_id': battle['battle_id'], 'stage_id': stage,
                  'end_status': 1, 'stt': stats}
        for bad in ('foreign-event', 'duplicate-event', 'over-max'):
            malformed = copy.deepcopy(report)
            event = malformed['stt']['BDCHEJKPOAF'][0]
            if bad == 'foreign-event':
                event['battle_event_id'] = 11204
            elif bad == 'duplicate-event':
                malformed['stt']['BDCHEJKPOAF'].append(copy.deepcopy(event))
            else:
                event['status']['sp_bar']['cur_sp'] = 1001
            call('PVEBattleResultCsReq', malformed, False)
            assert call('GetCurBattleInfoCsReq')['battle_info']['battle_id'] == battle['battle_id']
            state = call('GetTrialActivityDataCsReq')
            assert state['activity_stage_id'] == stage and not state.get('trial_activity_info_list')
        call('TakeTrialActivityRewardCsReq', {'stage_id': stage}, False)
        won = call('PVEBattleResultCsReq', report)
        assert won['battle_id'] == battle['battle_id']
        changes = snapshot()['trialStateChanges']
        assert len(changes) == 1 and changes[0]['trial_activity_info']['stage_id'] == stage
        assert not changes[0]['trial_activity_info'].get('taken_reward', False)
        # The native passed-stage cache can now enable claiming without another history query.
        call('PVEBattleResultCsReq', report, False)
        restored = call('GetCurSceneInfoCsReq')['scene']
        assert all(restored[k] == home[k] for k in ('entry_id', 'floor_id', 'plane_id'))
        assert call('GetCurLineupDataCsReq')['lineup'] == home_lineup
        notifications = snapshot()['trialNotifications']
        assert len(notifications) == 1 and notifications[0]['activity_stage_id'] == stage
        status = pool.FindMessageTypeByName('CurTrialActivityScNotify').fields_by_name['status']
        assert notifications[0]['status'] == status.enum_type.values_by_number[1].name
        before_reward = wallet(snapshot())
        reward = call('TakeTrialActivityRewardCsReq', {'stage_id': stage})
        changes = snapshot()['trialStateChanges']
        assert len(changes) == 2 and changes[1]['trial_activity_info']['stage_id'] == stage
        assert changes[1]['trial_activity_info']['taken_reward']
        items = {r['item_id']: r['num'] for r in reward['reward']['item_list']}
        assert items == {1: 20, 212: 3, 113001: 5}
        completion(True)
        claimed = wallet(snapshot())
        assert claimed['hcoin'] == before_reward['hcoin'] + 20
        for item, amount in (('212', 3), ('113001', 5)):
            assert claimed['materials'].get(item, 0) == before_reward['materials'].get(item, 0) + amount
        call('TakeTrialActivityRewardCsReq', {'stage_id': stage}, False)
        assert wallet(snapshot()) == claimed
        assert len(snapshot()['trialStateChanges']) == 2
        stop()
        restarted = start(2)
        assert restarted['uid'] == uid and wallet(restarted) == claimed
        completion(True)
        call('TakeTrialActivityRewardCsReq', {'stage_id': stage}, False)
        assert wallet(snapshot()) == claimed and not snapshot()['trialNotifications']
        assert not snapshot()['trialStateChanges']
        stop()
        assert hashlib.sha256(jar.read_bytes()).hexdigest() == digest
        for name, expected in source_hashes.items():
            assert hashlib.sha256((ROOT / 'resources/4.6' / name).read_bytes()).hexdigest() == expected
        result = {'passed': True, 'jarSHA256': digest, 'stageId': stage, 'eventId': 11203,
                  'uid': uid, 'normalStops': stops, 'sameUidAfterRestart': True,
                  'finishNotifications': 1, 'rewardItems': items, 'rewardsPersisted': True,
                  'completionAvailableWithoutRefresh': True, 'stateChangeNotifications': 2,
                  'claimPacketIdPreserved': True,
                  'duplicateVictoryRejected': True, 'duplicateRewardsRejected': True,
                  'requestChecks': commands, 'isolatedH2': True,
                  'fixtureMainMission': 1000510, 'fixtureTrialWindow': [20003, 2000401],
                  'operatorDataModified': False, 'clientGameplayVerified': False,
                  'sourceSHA256': source_hashes, 'runtimeDirectory': str(directory)}
        output = ROOT / 'build/trial-reward-notify-native.json'
        output.write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding='utf-8')
        print(json.dumps({'passed': True, 'normalStops': stops, 'requestChecks': len(commands),
                          'stageId': stage, 'rewardItems': items, 'report': str(output)}))
    finally:
        if probe and probe.poll() is None:
            probe.terminate()
            probe.wait(timeout=15)
        if server and server.poll() is None:
            server.terminate()
            server.wait(timeout=15)
        for log in logs:
            log.close()


if __name__ == '__main__':
    main()
