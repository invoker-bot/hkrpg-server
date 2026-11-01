"""隔离 KCP/H2：逐池开放原缺失的 28 个角色池，核对全部 UP 试用的进入和退出。"""
import base64
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
    fixture = json.loads((ROOT / 'src/test/resources/fixtures/banner-trial-mappings-4.6.json')
                        .read_text(encoding='utf-8'))
    cases = sorted((r for r in fixture['banners'] if r['wasGap']), key=lambda r: r['id'])
    assert len(cases) == 28 and all(len(r['stages']) == 4 for r in cases)
    excel = ROOT / 'resources/4.6/ExcelOutput'
    source_hashes = dict(fixture['sourceSHA256'])
    for name in ('SpecialAvatar.json', 'SpecialAvatarLD.json', 'MultiplePathAvatarConfig.json'):
        source_hashes[name] = hashlib.sha256((excel / name).read_bytes()).hexdigest()

    def rows(name):
        value = json.loads((excel / name).read_text(encoding='utf-8-sig'))
        return list(value.values()) if isinstance(value, dict) else value

    for name, expected in source_hashes.items():
        assert hashlib.sha256((excel / name).read_bytes()).hexdigest() == expected, name
    demos = {r['StageID']: r for r in rows('AvatarDemoConfig.json')}
    specials = {(r['SpecialAvatarID'], r.get('WorldLevel', 0)): r
                for name in ('SpecialAvatar.json', 'SpecialAvatarLD.json') for r in rows(name)}
    genders = {r['AvatarID']: r.get('Gender', 'GENDER_NONE')
               for r in rows('MultiplePathAvatarConfig.json')}
    java = next((ROOT / '.tools').glob('jdk-21*/bin/java.exe'))
    runs = ROOT / 'build/smoke'
    runs.mkdir(exist_ok=True)
    directory = Path(tempfile.mkdtemp(prefix='gacha-rarity-trial-mappings-', dir=runs))
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
    (directory / 'data/Gameplay46.json').write_text('{"activities":[]}', encoding='utf-8')
    base = f'http://127.0.0.1:{http}'
    java_flags = ['-Dfile.encoding=UTF-8', '-Dio.netty.eventLoopThreads=2',
                  f'-Djdk.net.unixdomain.tmpdir={directory / "tcp-fallback"}']
    server = probe = None
    logs, checks, results = [], [], []
    stops = packet_id = 0
    account_name = 'banner-trials-' + uuid.uuid4().hex

    def request(route, body=None):
        req = urllib.request.Request(base + route,
            data=None if body is None else json.dumps(body).encode(),
            headers={'Content-Type': 'application/json'})
        with urllib.request.urlopen(req, timeout=5) as response:
            return json.load(response)

    def snapshot():
        path = directory / 'snapshot.json'
        return json.loads(path.read_text(encoding='utf-8')) if path.exists() else None

    def activate(case):
        row = {key: case[key] for key in ('id', 'gachaType', 'rateUpItems5', 'rateUpItems4')}
        row.update(beginTime=int(time.time()) - 60, endTime=int(time.time()) + 86400)
        target = directory / 'data/Banners.json'
        temporary = target.with_suffix('.tmp')
        temporary.write_text(json.dumps([row]), encoding='utf-8')
        temporary.replace(target)
        return row

    def start(index):
        nonlocal server, probe
        log = (directory / f'server-{index}.log').open('w', encoding='utf-8')
        logs.append(log)
        server = subprocess.Popen([str(java), *java_flags, '-Xmx1536m', '-jar', str(jar),
            '--no-handbook'], cwd=directory, stdin=subprocess.PIPE, stdout=log,
            stderr=subprocess.STDOUT, creationflags=flags)
        gm.wait_for(lambda: server.poll() is None and request('/health/ready')['ready'], 90)
        account = request('/hkrpg_cn/mdk/shield/api/login',
            {'account': account_name, 'password': 'owned-banner-trials'})['data']['account']
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

    def inspect(action):
        run = subprocess.run([str(java), *java_flags, '-cp', str(helpers) + os.pathsep + str(jar),
            'org.hkrpg.server.protocol.GachaRarityFixtureInspector', action, str(directory)],
            cwd=ROOT, capture_output=True, timeout=60, check=True, creationflags=flags)
        (directory / f'fixture-{action}.log').write_bytes(run.stdout + run.stderr)
        return json.loads(next(line for line in run.stdout.decode().splitlines()
            if line.startswith('RARITY_FIXTURE=')).split('=', 1)[1])

    def batch(commands):
        nonlocal packet_id
        encoded = []
        for name, fields, success in commands:
            packet_id += 1
            message = GetMessageClass(pool.FindMessageTypeByName(name))()
            ParseDict(fields or {}, message)
            encoded.append({'command': name, 'success': success, 'packetId': packet_id,
                            'wire': base64.b64encode(message.SerializeToString()).decode()})
        key = uuid.uuid4().hex
        target = directory / 'equipment-request.json'
        temporary = target.with_suffix('.tmp')
        temporary.write_text(json.dumps({'requestId': key, 'commands': encoded}), encoding='utf-8')
        temporary.replace(target)
        value = gm.wait_for(lambda: (lambda s: s if s and
            s.get('equipmentReply', {}).get('requestId') == key else None)(snapshot()), 60)
        target.unlink()
        replies = value['equipmentReply']['responses']
        assert len(replies) == len(commands)
        for (name, _, _), reply in zip(commands, replies):
            assert reply['command'] == name
            checks.append({'command': name, 'retcode': reply['response'].get('retcode', 0)})
        return [reply['response'] for reply in replies]

    def call(name, fields=None, success=True):
        return batch([(name, fields, success)])[0]

    def entities(scene):
        return [*scene.get('entity_list', []),
                *(e for group in scene.get('entity_group_list', []) for e in group.get('entity_list', []))]

    def home_view(scene):
        leader = next(e for e in entities(scene) if e['entity_id'] == scene['leader_entity_id'])
        return {key: scene.get(key, 0) for key in ('entry_id', 'plane_id', 'floor_id', 'dimension_id')} | {
            'motion': leader.get('motion', {}),
            'actors': sorted((e['actor']['base_avatar_id'], e['actor']['avatar_type'])
                             for e in entities(scene) if 'actor' in e)}

    def inventory(value):
        return {'basic': value['basic'], 'bag': value['bag'], 'avatars': value['avatars']}

    try:
        activate(cases[0])
        initial = start(0)
        uid = initial['uid']
        stop()
        (directory / 'rarity-fixture.json').write_text(
            json.dumps({'testFixture': True, 'uid': uid}), encoding='utf-8')
        seeded = inspect('seedTrial')
        assert not seeded['missionTrialInside'] and not seeded['trialInside']
        assert seeded['worldLevel'] == 0
        current = start(1)
        assert current['uid'] == uid
        baseline = inventory(current)
        home, home_lineup, empty = batch([('GetCurSceneInfoCsReq', {}, True),
            ('GetCurLineupDataCsReq', {}, True), ('GetTrialActivityDataCsReq', {}, True)])
        home = home_view(home['scene'])
        home_lineup = home_lineup['lineup']
        assert not empty.get('trial_activity_info_list') and not empty.get('activity_stage_id')
        for case in cases:
            configured = activate(case)
            gm.wait_for(lambda: (lambda s: s and
                [r['gacha_id'] for r in s['gacha'].get('gacha_info_list', [])] == [case['id']])(snapshot()), 20)
            schedule = call('GetActivityScheduleConfigCsReq')['schedule_data']
            allowed = {(a['panelId'], module) for a in case['activities'] for module in a['moduleIds']}
            assert schedule and all((r['panel_id'], r['activity_id']) in allowed for r in schedule)
            assert all(r['begin_time'] == configured['beginTime'] and r['end_time'] == configured['endTime']
                       for r in schedule)
            commands = []
            for stage in case['stages']:
                commands.extend([('StartTrialActivityCsReq', {'stage_id': stage}, True),
                    ('GetCurSceneInfoCsReq', {}, True), ('GetCurLineupDataCsReq', {}, True),
                    ('LeaveTrialActivityCsReq', {'stage_id': stage}, True),
                    ('GetCurSceneInfoCsReq', {}, True), ('GetCurLineupDataCsReq', {}, True)])
            replies = batch(commands)
            stage_results = []
            for i, stage in enumerate(case['stages']):
                entered, scene, lineup, left, restored, party = replies[i * 6:i * 6 + 6]
                assert entered['stage_id'] == left['stage_id'] == stage
                source, scene, lineup = demos[stage], scene['scene'], lineup['lineup']
                assert scene['entry_id'] == source['MapEntranceID']
                assert lineup['extra_lineup_type'] == 'ExtraLineupType_LineupStageTrial'
                roster = [actor for actor in source['TrialAvatarList']
                    if genders.get(specials[(actor, 0)]['AvatarID'], 'GENDER_NONE')
                       in ('GENDER_NONE', seeded['gender'])]
                assert len(roster) <= 4
                assert {r['id'] for r in lineup['avatar_list']} == set(roster)
                assert all(r['avatar_type'] == 'AvatarType_AvatarTrialType' and r.get('hp', 0) > 0
                           for r in lineup['avatar_list'])
                actor_ids = sorted(specials[(actor, 0)]['AvatarID'] for actor in roster)
                assert sorted(e['actor']['base_avatar_id'] for e in entities(scene) if 'actor' in e) == actor_ids
                assert all(e['actor']['avatar_type'] == 'AvatarType_AvatarTrialType'
                           for e in entities(scene) if 'actor' in e)
                monsters = [e for e in entities(scene) if 'npc_monster' in e]
                assert {e['inst_id'] for e in monsters} == set(source['ConfigList1'])
                assert all(e['group_id'] == source['MazeGroupID1'] for e in monsters)
                assert home_view(restored['scene']) == home
                assert party['lineup'] == home_lineup
                stage_results.append({'stageId': stage, 'entryId': scene['entry_id'],
                    'roster': roster, 'actors': actor_ids, 'monsterCount': len(monsters),
                    'originalHomeAndPartyRestored': True})
            assert inventory(snapshot()) == baseline
            assert not snapshot()['trialNotifications'] and not snapshot()['trialStateChanges']
            results.append({'bannerId': case['id'], 'schedule': schedule, 'stages': stage_results})
            (directory / 'progress.json').write_text(json.dumps({'banners': results,
                'completed': len(results), 'total': len(cases)}), encoding='utf-8')
            print(json.dumps({'banner': case['id'], 'completed': len(results), 'total': len(cases),
                              'enteredAndLeft': len(stage_results)}), flush=True)
        history = call('GetTrialActivityDataCsReq')
        assert not history.get('trial_activity_info_list') and not history.get('activity_stage_id')
        stop()
        persisted = inspect('inspect')
        assert not persisted['trialInside'] and not persisted['trialCompletions'] and not persisted['trialReceipts']
        restarted = start(2)
        assert restarted['uid'] == uid and inventory(restarted) == baseline
        restored, party = batch([('GetCurSceneInfoCsReq', {}, True), ('GetCurLineupDataCsReq', {}, True)])
        assert home_view(restored['scene']) == home and party['lineup'] == home_lineup
        stop()
        assert hashlib.sha256(jar.read_bytes()).hexdigest() == digest
        for name, expected in source_hashes.items():
            assert hashlib.sha256((excel / name).read_bytes()).hexdigest() == expected, name
        report = {'passed': True, 'jarSHA256': digest, 'uid': uid, 'bannerCount': len(results),
            'entryExitPairs': sum(len(r['stages']) for r in results),
            'distinctStages': sorted({r['stageId'] for b in results for r in b['stages']}),
            'normalStops': stops, 'sameUidAfterRestart': True, 'originalHomeAndPartyRestored': True,
            'walletInventoryAndFormalAvatarsPreserved': True, 'noVictoryOrRewardCreated': True,
            'requestChecks': checks, 'isolatedH2': True, 'fixtureMainMission': 1000510,
            'fixtureWorldLevel': seeded['worldLevel'], 'fixtureGender': seeded['gender'],
            'explicitTrialWindows': [], 'operatorDataModified': False, 'clientGameplayVerified': False,
            'sourceSHA256': source_hashes, 'banners': results, 'runtimeDirectory': str(directory)}
        output = ROOT / 'build/banner-trial-mappings-native.json'
        output.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding='utf-8')
        print(json.dumps({'passed': True, 'banners': len(results), 'entryExitPairs': report['entryExitPairs'],
            'normalStops': stops, 'requestChecks': len(checks), 'report': str(output)}), flush=True)
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
