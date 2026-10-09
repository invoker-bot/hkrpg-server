"""真实43305双楼层的合成KCP与正常H2重启；父任务及剧情尾部为明确隔离夹具。"""
import hashlib
import json
import os
from pathlib import Path
import socket
import subprocess
import sys
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
    req = urllib.request.Request(base + route, data=None if body is None else json.dumps(body).encode(),
                                 headers={'Content-Type': 'application/json'})
    with urllib.request.urlopen(req, timeout=5) as response:
        return json.load(response)


def main():
    clock = '--clock' in sys.argv
    java = next((ROOT / '.tools').glob('jdk-21*/bin/java.exe'), None)
    java = str(java) if java else 'java'
    jar = ROOT / 'build/libs/hkrpg-server.jar'
    assert jar.is_file(), '先运行 scripts/build.ps1'
    runs = ROOT / 'build/smoke'
    runs.mkdir(parents=True, exist_ok=True)
    directory = Path(tempfile.mkdtemp(prefix='mission-raid-', dir=runs))
    source = ROOT / 'resources/4.6'
    fixture = directory / 'resources-fixture'
    mids = [2030001, 2030002]
    subs = [203000201, 203000202, 203000233, 203000234]
    guarded = ['resource-manifest.json', 'ExcelOutput/MainMission.json',
               'ExcelOutput/RaidConfig.json', 'ExcelOutput/MapEntrance.json',
               'Config/Level/Mission/2030002/MissionInfo_2030002.json',
               'Config/Level/Mission/2030002/MissionInfo_2030002.partial.json']
    guarded += [f'Config/Level/Mission/2030002/Mission_{sub}.json' for sub in subs]
    if clock:
        guarded += ['ExcelOutput/HeartDialScript.json', 'ExcelOutput/HeartDialScriptCondition.json',
                    'ExcelOutput/HeartDialCondition.json', 'ExcelOutput/HeartDialNpc.json',
                    'ExcelOutput/HeartDialDialogue.json', 'ExcelOutput/ItemConfig.json',
                    'Config/LevelOutput/RuntimeFloor/P10301_F10301001.json']
        guarded += [f'Config/LevelOutput/SharedRuntimeGroup/Groups_P10301_F10301001/'
                    f'LevelGroup_P10301_F10301001_G{group}.json' for group in [61, 67]]
    for floor in [43305001, 43305002]:
        guarded.append(f'Config/LevelOutput/RuntimeFloor/P43305_F{floor}.json')
        guarded += [str(path.relative_to(source)).replace('\\', '/') for path in
                    (source / f'Config/LevelOutput/SharedRuntimeGroup/Groups_P43305_F{floor}').glob('*.json')]
    before = {name: hashlib.sha256((source / name).read_bytes()).hexdigest() for name in guarded}
    main_rows = json.loads((source / 'ExcelOutput/MainMission.json').read_text(encoding='utf-8'))
    for row in main_rows:
        if row['MainMissionID'] == 2030001:
            row.update(TakeOperation='And', BeginOperation='And', TakeParam=[{'Type': 'Manual'}],
                       BeginParam=[{'Type': 'Auto'}], RewardID=0, SubRewardList=[])
    replacements = {'ExcelOutput/MainMission.json': main_rows,
                    'resource-manifest.json': {'clientVersion': '4.6.0', 'testFixture': True,
                                               'onlyMissionFixture': mids, 'realRaid': 43305}}
    if clock:
        name = ('Config/LevelOutput/SharedRuntimeGroup/Groups_P10301_F10301001/'
                'LevelGroup_P10301_F10301001_G67.json')
        group = json.loads((source / name).read_text(encoding='utf-8'))
        group['LoadOnInitial'] = False
        replacements[name] = group

    junctions = []

    def connect(target, origin):
        target.parent.mkdir(parents=True, exist_ok=True)
        if origin.is_file():
            os.link(origin, target)
        elif os.name == 'nt':
            assert target.resolve().is_relative_to(fixture.resolve())
            # 批量创建资源目录链接；不移动、删除或编辑来源目录。
            junctions.append({'target': str(target), 'source': str(origin)})
        else:
            target.symlink_to(origin, target_is_directory=True)

    def mirror(origin, name):
        target = fixture / name
        if name in replacements:
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_text(json.dumps(replacements[name], ensure_ascii=False), encoding='utf-8')
        elif any(key.startswith(name + '/') for key in replacements):
            target.mkdir(parents=True, exist_ok=True)
            for child in origin.iterdir(): mirror(child, name + '/' + child.name)
        else:
            connect(target, origin)

    for path in (source / 'ExcelOutput').iterdir():
        name = 'ExcelOutput/' + path.name
        if name in replacements:
            target = fixture / name
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_text(json.dumps(replacements[name], ensure_ascii=False), encoding='utf-8')
        else:
            connect(fixture / name, path)
    for path in (source / 'Config').iterdir():
        if path.name != 'Level': mirror(path, 'Config/' + path.name)
    for path in (source / 'Config/Level').iterdir():
        if path.name != 'Mission': connect(fixture / 'Config/Level' / path.name, path)
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
    parent = fixture / 'Config/Level/Mission/2030001'
    parent.mkdir(parents=True)
    parent_subs = [{'ID': 19997991,
        'MainMissionID': 2030001, 'TakeType': 'Auto', 'FinishType': 'Talk', 'ParamType': 'Equal',
        'ParamStr1': 'fixture_parent_hold', 'Progress': 1}]
    if clock:
        parent_subs += [{'ID': sub, 'MainMissionID': 2030001, 'TakeType': 'Auto',
                        'FinishType': 'Auto', 'Progress': 1} for sub in [103040103, 103040104]]
        parent_subs.append({'ID': 19997992, 'MainMissionID': 2030001, 'TakeType': 'Auto',
            'FinishType': 'Auto', 'Progress': 1, 'FinishActionList': [
                {'FinishActionType': 'MoveToAnchor', 'FinishActionPara': [1030101, 1, 1]},
                {'FinishActionType': 'addMissionItem', 'FinishActionPara': [140408, 3]},
                {'FinishActionType': 'addMissionItem', 'FinishActionPara': [140410, 1]}]})
        parent_subs += [
            {'ID': 19997993, 'MainMissionID': 2030001, 'TakeType': 'Auto',
             'FinishType': 'RaidFinishCnt', 'ParamType': 'ListContain', 'ParamIntList': [43305],
             'Progress': 1, 'LevelPlaneID': 10301, 'LevelFloorID': 10301001},
            {'ID': 19997994, 'MainMissionID': 2030001, 'TakeType': 'Auto',
             'FinishType': 'RaidFinishedAndTransfer', 'ParamType': 'Equal', 'ParamInt1': 43305,
             'Progress': 1, 'LevelPlaneID': 10301, 'LevelFloorID': 10301001}]
    (parent / 'MissionInfo_2030001.json').write_text(json.dumps({'MainMissionID': 2030001,
        'FinishSubMissionList': [19997991], 'SubMissionList': parent_subs}), encoding='utf-8')
    mission = fixture / 'Config/Level/Mission/2030002'
    mission.mkdir(parents=True)
    info = json.loads((source / 'Config/Level/Mission/2030002/MissionInfo_2030002.json').read_text(encoding='utf-8'))
    rows = json.loads((source / 'Config/Level/Mission/2030002/MissionInfo_2030002.partial.json').read_text(encoding='utf-8'))['SubMissionList']
    selected = [row for row in rows if row['ID'] in subs]
    assert len(selected) == 4
    tail = next(row for row in selected if row['ID'] == 203000234)
    tail.update(TakeParamIntList=[203000233], FinishActionList=[])
    info.update(FinishSubMissionList=[203000234], SubMissionList=selected)
    (mission / 'MissionInfo_2030002.json').write_text(json.dumps(info, ensure_ascii=False), encoding='utf-8')
    for sub in subs:
        name = f'Mission_{sub}.json'
        connect(mission / name, source / 'Config/Level/Mission/2030002' / name)
    (fixture / 'resource-manifest.json').write_text(json.dumps(replacements['resource-manifest.json']), encoding='utf-8')
    http, mongo, game = free_port(), free_port(), free_port(socket.SOCK_DGRAM)
    (directory / 'config.json').write_text(json.dumps({'protocol': {'wireVersion': '4.6.0'},
        'resourceDir': str(fixture), 'httpServer': {'bindAddress': '127.0.0.1', 'bindPort': http},
        'gameServer': {'bindAddress': '127.0.0.1', 'bindPort': game},
        'internalMongoServer': {'address': '127.0.0.1', 'port': mongo, 'filePath': './data/database.mv'}}), encoding='utf-8')
    flags = [f'-Djdk.net.unixdomain.tmpdir={directory / "tcp-fallback"}', '-Dio.netty.eventLoopThreads=2']
    command = [java, '-Xmx1536m', *flags, '-jar', str(jar), '--no-console', '--no-handbook']
    base = f'http://127.0.0.1:{http}'

    def probe(name, credentials):
        execution = subprocess.run([java, *flags, '-cp', str(jar),
            'org.hkrpg.server.protocol.LoopbackMissionRaidProbe', str(game)],
            input=json.dumps(credentials), cwd=directory, capture_output=True, text=True,
            encoding='utf-8', errors='replace', timeout=40,
            creationflags=subprocess.CREATE_NO_WINDOW if os.name == 'nt' else 0)
        log = directory / name
        log.write_text(execution.stdout + execution.stderr, encoding='utf-8')
        if execution.returncode: raise RuntimeError(f'Mission Raid probe failed; inspect {log}')
        result = json.loads(next(line for line in execution.stdout.splitlines() if line.startswith('{"uid":')))
        assert result['passed'] and not result['clientGameplayVerified']
        assert result['phase'] == credentials['phase']
        return result

    snapshots = []
    credentials = None
    for iteration in range(3):
        with (directory / f'server-{iteration}.log').open('w', encoding='utf-8') as log:
            process = subprocess.Popen(command, cwd=directory, stdin=subprocess.DEVNULL,
                stdout=log, stderr=subprocess.STDOUT,
                creationflags=subprocess.CREATE_NO_WINDOW if os.name == 'nt' else 0)
            try:
                deadline = time.monotonic() + 90
                while True:
                    if process.poll() is not None: raise RuntimeError(f'Mission Raid server failed; inspect {directory}')
                    try:
                        if request(base, '/health/ready')['ready']: break
                    except (OSError, urllib.error.URLError): pass
                    if time.monotonic() > deadline: raise RuntimeError(f'Mission Raid server timeout; inspect {directory}')
                    time.sleep(.2)
                if iteration == 0:
                    login = request(base, '/hkrpg_cn/mdk/shield/api/login', {'account': 'cn-46-mission-raid', 'password': 'local-test'})
                    assert login['retcode'] == 0
                    account = login['data']['account']
                    combo = request(base, '/hkrpg_cn/combo/granter/login/v2/login', {'data': json.dumps({'uid': account['uid'], 'token': account['token']})})
                    assert combo['retcode'] == 0
                    credentials = {'accountUid': account['uid'], 'comboToken': combo['data']['combo_token'], 'phase': 'start'}
                    if clock: credentials['clock'] = True
                    first = probe('raid-start.log', credentials)
                    snapshots.append(first)
                    if clock:
                        assert first['clockStep'] == 2
                        credentials.update(home=first['home'], phase='clock-lock')
                        second = probe('clock-lock-after-reconnect.log', credentials)
                        assert second['sceneTransfers'] > 0 and second['ordinaryTeleportRejected']
                        snapshots.append(second)
                        credentials['checkpoint'] = second['checkpoint']
                    else:
                        assert first['sceneTransfers'] > 0
                        credentials.update(home=first['home'], checkpoint=first['checkpoint'])
                    credentials['phase'] = 'inside'
                    snapshots.append(probe('raid-inside-reconnect.log', credentials))
                elif iteration == 1:
                    credentials['phase'] = 'complete'
                    completed = probe('raid-active-after-h2-restart.log', credentials)
                    assert completed['finishNotifications'] > 0
                    snapshots.append(completed)
                    credentials['phase'] = 'finished'
                    snapshots.append(probe('raid-finished-reconnect.log', credentials))
                else:
                    snapshots.append(probe('raid-finished-after-h2-restart.log', credentials))
                assert all(row['uid'] == first['uid'] for row in snapshots)
                time.sleep(2)
            finally:
                if process.poll() is None:
                    process.terminate()
                    try: process.wait(timeout=15)
                    except subprocess.TimeoutExpired:
                        process.kill(); process.wait(timeout=5)
    for name, digest in before.items():
        assert hashlib.sha256((source / name).read_bytes()).hexdigest() == digest
    checks = ['ordinary-world-raid-mission-acceptance-rejected', 'explicit-parent-fixture-accepted',
            'actual-43305-raid-and-first-floor-entry', 'formal-lineup-preserved',
            'wrong-raid-saved-exit-and-repeat-entry-rejected', 'wrong-floor-tail-rejected',
            'one-real-talk-does-not-complete-next-objective', 'actual-second-talk-and-authored-floor-transfer',
            'actual-transfer-scene-notification', 'transfer-objective-after-real-move',
            'unfinished-return-to-exact-home-position-and-rotation', 'existing-task-acceptance-outside-raid-rejected',
            'reentry-keeps-second-floor-and-transfer-history', 'active-raid-restored-on-reconnect',
            'active-raid-restored-after-normal-h2-restart', 'real-tail-completion-and-native-finish-time-notification',
            'native-finished-raid-query-and-finished-reentry-rejection', 'no-invented-saved-raid-data',
            'finished-raid-home-and-history-restored-on-reconnect-and-normal-h2-restart']
    if clock:
        checks += ['real-10108-heartdial-graph-and-authored-npc-identity',
                   'missing-dialogue-and-wrong-phase-item-rejection',
                   'missing-item-debit-and-native-lock-stage-notification',
                   'lock-stage-and-dialogue-history-restored-on-reconnect',
                   'separate-lock-item-debit-and-native-unlock-stage-notification',
                   'unlock-stage-and-item-counts-preserved-during-real-raid',
                   'ordinary-map-teleport-rejected-inside-raid',
                   'only-real-raid-main-completion-releases-normal-stage',
                   'normal-emotion-dialogue-and-all-earlier-phase-history',
                   'parent-raid-count-objective-only-after-real-completion-and-authored-floor',
                   'parent-raid-transfer-objective-only-after-actual-successful-return',
                   'both-emotion-items-debited-once-after-reconnect-and-normal-h2-restart']
    print(json.dumps({'passed': True, 'runtimeDirectory': str(directory), 'snapshots': snapshots,
        'checks': checks,
        'fixture': {'onlyMissions': mids, 'raid': 43305, 'sourceSha256': before,
            'sourceResourcesModified': False, 'raidAndSceneResourcesOverridden': clock,
            'raidResourcesOverridden': False, 'raidSceneResourcesOverridden': False,
            'parentMissionGatesOverridden': True, 'raidMissionTailOverridden': True,
            'clockwork10108': clock, 'npcLoadingGateOverridden': clock,
            'npcDefinitionsOverridden': False, 'heartdialResourcesOverridden': False,
            'officialMissionGraphVerified': False}, 'clientGameplayVerified': False}, ensure_ascii=False))


if __name__ == '__main__':
    main()
