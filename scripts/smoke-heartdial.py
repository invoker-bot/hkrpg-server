"""实际钟表NPC/对话的KCP、重连与正常H2重启；隔离任务/加载门槛，不代表官方剧情或UI。"""
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


def free_port(kind=socket.SOCK_STREAM):
    with socket.socket(type=kind) as stream:
        stream.bind(('127.0.0.1', 0))
        return stream.getsockname()[1]


def request(base, route, body=None):
    query = urllib.request.Request(base + route, data=None if body is None else json.dumps(body).encode(),
                                   headers={'Content-Type': 'application/json'})
    with urllib.request.urlopen(query, timeout=5) as response:
        return json.load(response)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--missing', action='store_true', help='验证实际20004缺失情绪物品及步骤任务')
    parser.add_argument('--full', action='store_true', help='验证实际13113缺失、充盈、指定任务及普通步骤')
    parser.add_argument('--floor', action='store_true', help='与--full一起验证原始楼层选择变量和290/400组NPC切换')
    options = parser.parse_args()
    full = options.full
    floor = options.floor
    if floor and not full:
        parser.error('--floor 必须与 --full 一起使用')
    missing = options.missing or full
    script_id = 13113 if full else 20004 if missing else 20002
    npc_id = 13113 if full else 13129 if missing else 13151
    item_id = 140406 if full else 140469
    java = next((ROOT / '.tools').glob('jdk-21*/bin/java.exe'), None)
    java = str(java) if java else 'java'
    jar = ROOT / 'build/libs/hkrpg-server.jar'
    runs = ROOT / 'build/smoke'
    runs.mkdir(parents=True, exist_ok=True)
    directory = Path(tempfile.mkdtemp(prefix='heartdial-', dir=runs))
    source = ROOT / 'resources/4.6'
    fixture = directory / 'resources-fixture'
    group_path = ('Config/LevelOutput/SharedRuntimeGroup/Groups_P10301_F10301001/LevelGroup_P10301_F10301001_G131.json' if full else
                  'Config/LevelOutput/SharedRuntimeGroup/Groups_P20313_F20313001/LevelGroup_P20313_F20313001_G94.json' if missing
                  else 'Config/LevelOutput/SharedRuntimeGroup/Groups_P10301_F10301001/LevelGroup_P10301_F10301001_G47.json')
    guarded = ['ExcelOutput/' + name + '.json' for name in
               ['MainMission', 'HeartDialScript', 'HeartDialDialogue', 'HeartDialNpc', 'HeartDialConstValue', 'MapEntrance']]
    guarded += [group_path, ('Config/LevelOutput/RuntimeFloor/P20313_F20313001.json' if missing and not full
                             else 'Config/LevelOutput/RuntimeFloor/P10301_F10301001.json'), 'resource-manifest.json']
    if missing:
        guarded += ['ExcelOutput/HeartDialScriptCondition.json', 'ExcelOutput/HeartDialCondition.json',
                    'ExcelOutput/ItemConfig.json', ('Config/LevelOutput/SharedRuntimeGroup/Groups_P10301_F10301001/LevelGroup_P10301_F10301001_G162.json' if full else
                     'Config/LevelOutput/SharedRuntimeGroup/Groups_P20313_F20313001/LevelGroup_P20313_F20313001_G73.json')]
    if floor:
        guarded += ['Config/LevelOutput/SharedRuntimeGroup/Groups_P10301_F10301001/LevelGroup_P10301_F10301001_G' + str(group) + '.json' for group in [290, 400]]
    original = {name: hashlib.sha256((source / name).read_bytes()).hexdigest() for name in guarded}
    main_id = 19997101
    main_rows = json.loads((source / 'ExcelOutput/MainMission.json').read_text(encoding='utf-8'))
    assert not any(row['MainMissionID'] == main_id for row in main_rows)
    main_rows.append({'MainMissionID': main_id, 'TakeOperation': 'And', 'TakeParam': [{'Type': 'Manual'}],
                      'BeginOperation': 'And', 'BeginParam': [{'Type': 'Auto'}]})
    if full:
        assert sum(row['MainMissionID'] == 4240301 for row in main_rows) == 1
        main_rows = [row for row in main_rows if row['MainMissionID'] != 4240301]
        main_rows.append({'MainMissionID': 4240301, 'TakeOperation': 'And', 'TakeParam': [{'Type': 'Manual'}],
                          'BeginOperation': 'And', 'BeginParam': [{'Type': 'Auto'}]})
    group = json.loads((source / group_path).read_text(encoding='utf-8'))
    assert group['OwnerMainMissionID'] == (4240301 if full else 1030402 if missing else 1030401)
    assert any(row['ID'] == (400001 if missing else 400005) and row['NPCID'] == npc_id for row in group['NPCList'])
    if not full:
        group['OwnerMainMissionID'] = main_id
        group['LoadOnInitial'] = True
        group.pop('LoadCondition', None)
        group.pop('UnloadCondition', None)
    replacements = {'ExcelOutput/MainMission.json': main_rows,
                    'resource-manifest.json': {'clientVersion': '4.6.0', 'testFixture': True,
                                               'onlyMissionFixture': [main_id, 4240301] if full else [main_id], 'realHeartDialScript': script_id}}
    if not full:
        replacements[group_path] = group

    junctions = []

    def connect(target, origin):
        target.parent.mkdir(parents=True, exist_ok=True)
        if origin.is_file():
            os.link(origin, target)
        elif os.name == 'nt':
            assert target.resolve().is_relative_to(fixture.resolve())
            junctions.append({'target': str(target), 'source': str(origin)})
        else:
            target.symlink_to(origin, target_is_directory=True)

    def mirror(origin, relative=''):
        target = fixture / relative
        if relative == 'Config/Level/Mission':
            target.mkdir(parents=True)
        elif relative in replacements:
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_text(json.dumps(replacements[relative], ensure_ascii=False), encoding='utf-8')
        elif any(name.startswith(relative + '/') or not relative for name in
                 [*replacements, 'Config/Level/Mission']):
            target.mkdir(parents=True, exist_ok=True)
            for child in origin.iterdir():
                mirror(child, (relative + '/' if relative else '') + child.name)
        else:
            connect(target, origin)

    mirror(source)
    if junctions:
        plan = directory / 'junction-plan.json'
        plan.write_text(json.dumps(junctions), encoding='utf-8')
        helper = directory / 'prepare-fixture.ps1'
        helper.write_text("param([string]$Plan)\n$ErrorActionPreference = 'Stop'\n"
                          "foreach ($binding in (Get-Content -LiteralPath $Plan -Raw | ConvertFrom-Json)) {\n"
                          "  New-Item -ItemType Junction -Path $binding.target -Value $binding.source | Out-Null\n}\n",
                          encoding='utf-8')
        subprocess.run(['powershell', '-NoProfile', '-NonInteractive', '-File', str(helper), '-Plan', str(plan)],
                       check=True, capture_output=True, text=True, creationflags=subprocess.CREATE_NO_WINDOW)
    subs = [{'ID': identifier, 'MainMissionID': main_id, 'TakeType': 'Auto', 'FinishType': 'Auto', 'Progress': 1}
            for identifier in [103040103, 103040104, 199971010]]
    subs[-1]['FinishActionList'] = [{'FinishActionType': 'MoveToAnchor',
                                   'FinishActionPara': [2031302, 73, 4] if missing and not full else [1030101, 1, 1]}]
    if missing:
        subs[-1]['FinishActionList'].append({'FinishActionType': 'addMissionItem', 'FinishActionPara': [item_id, 3]})
    subs += [{'ID': 199971011, 'MainMissionID': main_id, 'TakeType': 'Auto',
              'FinishType': 'HeartDialDialoguePerformanceFinish', 'ParamType': 'ListContain',
              'ParamIntList': [script_id * 100 + 3], 'Progress': 1},
             {'ID': 199971012, 'MainMissionID': main_id, 'TakeType': 'MultiSequence',
              'TakeParamIntList': [199971011], 'FinishType': 'HeartDialDialoguePerformanceFinish',
              'ParamType': 'ListContain', 'ParamIntList': [script_id * 100 + 3], 'Progress': 1}]
    if missing:
        subs.append({'ID': 199971013, 'MainMissionID': main_id, 'TakeType': 'Auto',
                     'FinishType': 'HeartDialScriptListStep', 'ParamType': 'ListContain',
                     'ParamInt1': 4, 'ParamIntList': [script_id], 'Progress': 1})
    mission = fixture / 'Config/Level/Mission/19997101'
    mission.mkdir(parents=True)
    (mission / 'MissionInfo_19997101.json').write_text(json.dumps({'MainMissionID': main_id,
        'FinishSubMissionList': [199971012], 'SubMissionList': subs}), encoding='utf-8')
    if full:
        target = fixture / 'Config/Level/Mission/4240301'
        target.mkdir(parents=True)
        full_mission = {'MainMissionID': 4240301,
            'FinishSubMissionList': [424030103], 'SubMissionList': [
                {'ID': 424030101, 'MainMissionID': 4240301, 'TakeType': 'Auto',
                 'FinishType': 'HeartDialScriptListStep', 'ParamType': 'ListContain',
                 'ParamInt1': 1, 'ParamIntList': [13113], 'Progress': 1},
                {'ID': 424030103, 'MainMissionID': 4240301, 'TakeType': 'MultiSequence',
                 'TakeParamIntList': [424030101],
                 'FinishType': 'HeartDialDialoguePerformanceFinish', 'ParamType': 'ListContain',
                 'ParamIntList': [1311306], 'Progress': 1}]}
        if floor:
            for sub, value in zip(full_mission['SubMissionList'], [2, 1]):
                sub['FinishActionList'] = [{'FinishActionType': 'SetFloorSavedValue',
                    'FinishActionParaString': ['10301', '10301001', 'FSV_KekenaChoose', str(value)]}]
        (target / 'MissionInfo_4240301.json').write_text(json.dumps(full_mission), encoding='utf-8')
    http, mongo, game = free_port(), free_port(), free_port(socket.SOCK_DGRAM)
    (directory / 'config.json').write_text(json.dumps({'protocol': {'wireVersion': '4.6.0'}, 'resourceDir': str(fixture),
        'httpServer': {'bindAddress': '127.0.0.1', 'bindPort': http},
        'gameServer': {'bindAddress': '127.0.0.1', 'bindPort': game},
        'internalMongoServer': {'address': '127.0.0.1', 'port': mongo, 'filePath': './data/database.mv'}}), encoding='utf-8')
    command = [java, '-Xmx1536m', f"-Djdk.net.unixdomain.tmpdir={directory / 'tcp-fallback'}",
               '-jar', str(jar), '--no-console', '--no-handbook']
    base = f'http://127.0.0.1:{http}'

    def probe(name, credentials):
        execution = subprocess.run([java, command[2], '-cp', str(jar),
            'org.hkrpg.server.protocol.LoopbackHeartDialProbe', str(game)], input=json.dumps(credentials),
            cwd=directory, capture_output=True, text=True, encoding='utf-8', errors='replace', timeout=30,
            creationflags=subprocess.CREATE_NO_WINDOW if os.name == 'nt' else 0)
        log = directory / name
        log.write_text(execution.stdout + execution.stderr, encoding='utf-8')
        if execution.returncode:
            raise RuntimeError(f'Clockwork probe failed; inspect {log}')
        result = json.loads(next(line for line in execution.stdout.splitlines() if line.startswith('{"uid":')))
        assert result['passed'] and result['emotion'] == 3 and result['dialoguePerformed']
        assert result['missionProgress'] == {'first': 1, 'second': 0}
        assert not result['clientGameplayVerified']
        assert result['unlockNotifications'] == [] if credentials.get('resume') else result['unlockNotifications'][-1] == 2
        if missing:
            assert result['emotionItemCount'] == 2 and result['scriptStep'] == 4
            assert result['stepMissionProgress'] == 1 and result['missingDialoguePerformed']
            assert result['repairNotifications'] == (0 if credentials.get('resume') else 2 if full else 1)
            if full:
                assert result['notifiedSteps'] == ([] if credentials.get('resume') else [1, 4])
                assert result['fullTargetMissionCompleted'] and result['fullDialoguePerformed']
                assert result['replacementNpcGateVerified']
                assert result['npc']['group'] == 162 and result['npc']['id'] == 13114
                if not credentials.get('resume'):
                    assert result['initialNpc']['group'] == 131 and result['initialNpc']['id'] == 13113
                if floor:
                    assert result['floorNpcBranch'] == {'400': 13208}
                    if not credentials.get('resume'):
                        assert result['defaultFloorBranchEmpty']
                        assert result['intermediateFloorBranch'] == {'290': 13117}
        return result

    snapshots = []
    credentials = None
    for iteration in range(2):
        with (directory / f'server-{iteration}.log').open('w', encoding='utf-8') as log:
            process = subprocess.Popen(command, cwd=directory, stdin=subprocess.DEVNULL, stdout=log,
                stderr=subprocess.STDOUT, creationflags=subprocess.CREATE_NO_WINDOW if os.name == 'nt' else 0)
            try:
                deadline = time.monotonic() + 90
                while True:
                    if process.poll() is not None:
                        raise RuntimeError(f'Clockwork server failed; inspect {directory}')
                    try:
                        if request(base, '/health/ready')['ready']:
                            break
                    except (OSError, urllib.error.URLError):
                        pass
                    if time.monotonic() > deadline:
                        raise RuntimeError(f'Clockwork server timeout; inspect {directory}')
                    time.sleep(.2)
                if iteration == 0:
                    login = request(base, '/hkrpg_cn/mdk/shield/api/login', {'account': 'cn-46-heartdial', 'password': 'local-test'})
                    assert login['retcode'] == 0
                    account = login['data']['account']
                    combo = request(base, '/hkrpg_cn/combo/granter/login/v2/login', {'data': json.dumps({'uid': account['uid'], 'token': account['token']})})
                    assert combo['retcode'] == 0
                    credentials = {'accountUid': account['uid'], 'comboToken': combo['data']['combo_token']}
                    if missing:
                        credentials['missing'] = True
                    if full:
                        credentials['full'] = True
                    if floor:
                        credentials['floor'] = True
                    first = probe('clockwork-first.log', credentials)
                    snapshots.append(first)
                    credentials['resume'] = True
                restored = probe(f'clockwork-resume-{iteration}.log', credentials)
                assert restored['restored'] and restored['uid'] == first['uid']
                snapshots.append(restored)
                time.sleep(2)
            finally:
                if process.poll() is None:
                    process.terminate()
                    try:
                        process.wait(timeout=15)
                    except subprocess.TimeoutExpired:
                        process.kill()
                        process.wait(timeout=5)
    for name, digest in original.items():
        assert hashlib.sha256((source / name).read_bytes()).hexdigest() == digest
    print(json.dumps({'passed': True, 'runtimeDirectory': str(directory), 'snapshots': snapshots,
        'checks': ['actual-13113-to-13114-npcs-and-13113-script' if full else 'actual-13129-npc-and-20004-script' if missing else 'actual-13151-npc-and-20002-script', 'wrong-entity-and-wrong-emotion-dialogue-rejected',
                   'native-emotion-change-and-query', 'one-dialogue-does-not-advance-new-objective',
                   'repeated-performance-rejected', 'saved-state-restored-on-reconnect',
                   'saved-state-restored-after-normal-h2-process-restart',
                   'unlock-notification-not-republished-on-reconnect-or-restart'] +
                  (['missing-dialogue-does-not-repair-script', 'exact-one-real-emotion-item-and-normal-step-notification',
                    'step-objective-repeated-debit-rejected-stock-restored'] if missing else []) +
                  (['full-gate-rejects-normal-emotion-and-dialogue', 'authored-target-4240301-completes-before-normal',
                    'full-and-normal-notifications-history-restored',
                    'original-mission-owner-and-atmosphere-replacement-gates'] if full else []) +
                  (['original-floor-default-hides-both-choice-npcs',
                    'persisted-floor-value-switches-original-290-and-400-npcs',
                    'floor-choice-branch-restored-on-reconnect-and-normal-h2-restart'] if floor else []),
        'fixture': {'onlyMissions': [main_id, 4240301] if full else [main_id], 'sourceSha256': original,
                    'npcGroupLoadingOwnerOverridden': not full, 'officialUnlockMissionsVerified': False,
                    'targetMissionRulesOverridden': full, 'alternateNpcLoadingIsolated': False,
                    'floorChoiceActionsFixture': floor,
                    'sourceResourcesModified': False}, 'clientGameplayVerified': False}, ensure_ascii=False))


if __name__ == '__main__':
    main()
