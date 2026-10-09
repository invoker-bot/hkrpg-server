"""真实资源商人经合成KCP购买/重连/H2重启；隔离配置首房和300碎片，不是官方房间生成验收。"""
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
from smoke_resource_fixture import mirror_resources

ROOT = Path(__file__).resolve().parents[1]


def free_port(kind=socket.SOCK_STREAM):
    with socket.socket(type=kind) as sock:
        sock.bind(("127.0.0.1", 0))
        return sock.getsockname()[1]


def request(base, route, body=None):
    req = urllib.request.Request(base + route, data=None if body is None else json.dumps(body).encode(),
                                 headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=5) as response:
        return json.load(response)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--verify-handbook', action='store_true',
                        help='核对真实收集通知/查询、重连、正常关服/H2重启及结束探索后图鉴保留')
    parser.add_argument('--verify-handbook-reward', action='store_true',
                        help='按实际收集记录领取原始30星琼，核对整批拒绝、重复、通知、图鉴已领及重启钱包')
    parser.add_argument('--verify-handbook-blessing-quests', action='store_true',
                        help='核对实际祝福购买推进原始53图鉴任务、通知、结束探索及正常H2重启；不固定完成或发奖')
    parser.add_argument('--verify-handbook-blessing-tier', type=int, choices=[3000101,3000130],
                        help='实际多次探索/商人购买凑齐原始5或24不同祝福并领取80/30星琼；候选资格和30000碎片为隔离夹具')
    parser.add_argument('--verify-handbook-miracle-tier', type=int, choices=[4092318,4092319],
                        help='实际不同奇物商人购买推进原始3/40目标并领取5/10星琼；入口/目录/前置为隔离夹具')
    parser.add_argument('--miracle', type=int, choices=[1, 2, 3, 5, 7, 8, 10, 12, 14, 19, 21, 22, 23, 24, 25, 26, 27, 28, 45, 46, 47, 48, 49, 50, 51, 52, 53, 54, 55, 56, 58, 59, 60, 61, 62, 65, 66, 67, 68, 69, 70, 71],
                        help='隔离真实奇物商人场景，购买指定的新支持奇物')
    parser.add_argument('--verify-rest-exit', action='store_true',
                        help='在祝福商人重启恢复后核对真实休整出口和相邻战斗房进入')
    parser.add_argument('--verify-laurel-battle', action='store_true',
                        help='取得换境桂冠后进入实际相邻战斗房，验证失败转胜利、满生命及重启防重')
    parser.add_argument('--verify-battle-miracle', action='store_true',
                        help='取得已支持的战斗奇物后进入实际战斗，核对资源效果绑定、冻结参数、查询及重连恢复')
    parser.add_argument('--verify-room-miracle', action='store_true',
                        help='永动咕咕钟取得后核对相邻房间五个百分点扣费/通知、防重及战后重连恢复')
    parser.add_argument('--verify-code-repair', action='store_true',
                        help='隔离房间组合四个真实资源敌人；核对三胜修复和第四场修复后绑定及重启恢复')
    args = parser.parse_args()
    if args.verify_handbook_miracle_tier:
        if args.miracle not in [None,7] or args.verify_handbook_reward or args.verify_handbook_blessing_quests or args.verify_handbook_blessing_tier:
            parser.error('奇物收集成就检查独立使用实际奇物商人')
        args.miracle=7
        args.verify_handbook=True
    if args.verify_handbook_blessing_tier:
        args.verify_handbook_blessing_quests = True
    if args.verify_handbook_blessing_quests:
        if args.miracle is not None or args.verify_handbook_reward: parser.error('祝福图鉴任务检查使用祝福商人')
        args.verify_handbook = True
    if args.verify_handbook_reward:
        if args.miracle not in [7,21]: parser.error('图鉴奖励验证使用原始奇物7或骰子21')
        args.verify_handbook = True
    if args.verify_handbook and (args.verify_rest_exit or args.verify_laurel_battle or args.verify_battle_miracle or args.verify_room_miracle or args.verify_code_repair):
        parser.error('图鉴验证独立于战斗及多房间验证')
    if args.verify_code_repair:
        if args.miracle not in [45, 47, 49, 51, 53, 55]:
            parser.error('代码修复检查只使用六项损坏代码奇物')
        args.verify_battle_miracle = True
    if args.verify_rest_exit and args.miracle is not None:
        parser.error('休整出口检查使用含真实门的祝福商人房间')
    if args.verify_laurel_battle and args.miracle != 61:
        parser.error('失败转胜利检查仅使用奇物61')
    if args.verify_battle_miracle and not args.verify_code_repair and args.miracle not in [8, 10, 14, 19, 46, 48, 50, 52, 54, 56, 58, 59, 62, 66, 68, 71]:
        parser.error('资源战斗绑定检查仅使用已核对奇物8/10/14/19/46/48/50/52/54/56/58/59/62/66/68/71')
    if args.verify_room_miracle and (args.miracle != 67 or args.verify_battle_miracle):
        parser.error('相邻房间扣费检查仅使用奇物67')
    combat_miracle = args.verify_battle_miracle or args.verify_room_miracle
    multi_room = args.verify_rest_exit or args.verify_laurel_battle or combat_miracle
    miracle = args.miracle is not None
    wax_paths = {22: 120, 23: 126, 24: 124, 25: 125, 26: 121, 27: 122, 28: 123}
    immediate = args.miracle == 5 or args.miracle in wax_paths
    miracle_catalog = [2, args.miracle, 12] if args.miracle in [1, 3, 5, 7, 8, 10, 14, 19, 46, 48, 50, 52, 54, 56, 58, 59, 60, 61, 62, 65, 66, 67, 68, 69, 70, 71] or args.miracle in wax_paths else [2, 12]
    if args.miracle == 21:
        miracle_catalog = [4, 6, 21]
    if args.verify_code_repair:
        miracle_catalog = [2, args.miracle, args.miracle + 1]
    if args.verify_handbook_miracle_tier:
        miracle_catalog = [1,2,3,4,5,6,7,8,12,13,14,15,17,19,20,22,23,24,25,26,27,28,45,47,49,51,53,55,58,59,60,61,62,64,65,66,67,68,69,70]
    java = next((ROOT / ".tools").glob("jdk-21*/bin/java.exe"), None)
    java = str(java) if java else "java"
    artifact = ROOT / "build/libs/hkrpg-server.jar"
    root_jar_sha = hashlib.sha256(artifact.read_bytes()).hexdigest()
    runs = ROOT / "build/smoke"
    runs.mkdir(parents=True, exist_ok=True)
    directory = Path(tempfile.mkdtemp(prefix=f"rogue-miracle-{args.miracle}-" if miracle else "rogue-shop-", dir=runs))
    source_resources = ROOT / "resources/4.6"
    eligible_path_ids = None
    if args.miracle in wax_paths:
        rows = json.loads((source_resources / 'ExcelOutput/RogueBuff.json').read_text(encoding='utf-8'))
        values = list(rows.values()) if isinstance(rows, dict) else rows
        eligible_path_ids = sorted({r['MazeBuffID'] for r in values if r.get('MazeBuffLevel') == 1
            and r.get('RogueVersion') == 1 and r.get('RogueBuffType') == wax_paths[args.miracle]
            and r.get('RogueBuffCategory') in ['Common', 'Rare', 'Legendary']})
        assert eligible_path_ids
    fixture = directory / "resources-fixture"
    excel = fixture / "ExcelOutput"
    excel.mkdir(parents=True)
    original_const = source_resources / "ExcelOutput/ConstValueRogue.json"
    original_sha = hashlib.sha256(original_const.read_bytes()).hexdigest()
    hash_files = ['ConstValueRogue.json', 'RogueRoom.json', 'RogueMiracle.json']
    if args.miracle == 21:
        hash_files.extend(['RogueMiracleEffect.json', 'RogueMiracleGroup.json', 'MapEntrance.json', 'NPCData.json'])
    handbook_unlocks = {}
    blessing_quest_goals, blessing_quest_paths, blessing_handbook_paths = {}, {}, {}
    tier_goal, tier_amount, tier_allowed = None, None, []
    miracle_path_buffs, handbook_eligible_buffs, miracle_prerequisite_hashes = {}, [], {}
    if args.verify_handbook:
        hash_files.extend(['RogueHandbookMiracle.json', 'RogueBuff.json', 'MazeBuff.json'])
        if args.verify_handbook_reward: hash_files.extend(['RewardData.json','ItemConfig.json'])
        rows = json.loads((source_resources / 'ExcelOutput/RogueMiracle.json').read_text('utf-8'))
        handbook_unlocks = {row['MiracleID']: row.get('UnlockHandbookMiracleID', 0) for row in rows}
    if args.verify_handbook_miracle_tier:
        hash_files.extend(['QuestData.json','FinishWay.json','RewardData.json','ItemConfig.json','MainMission.json','RogueMiracleEffect.json','RogueMazeBuff.json'])
        def source_rows(name):
            rows=json.loads((source_resources/'ExcelOutput'/name).read_text('utf-8'))
            return list(rows.values()) if isinstance(rows,dict) else rows
        quests={r['QuestID']:r for r in source_rows('QuestData.json')}
        rules={r['ID']:r for r in source_rows('FinishWay.json')}
        rewards={r['RewardID']:r for r in source_rows('RewardData.json')}
        for id,goal,amount in [(4092318,3,5),(4092319,40,10)]:
            q=quests[id];r=rules[q['FinishWayID']]
            assert q['QuestType']==1 and q['UnlockType']=='FinishQuest' and q['UnlockParamList']==[4000001]
            assert r['FinishType']=='RogueMiracleUnlock' and r['ParamType']=='NoPara' and r['Progress']==goal
            assert rewards[q['RewardID']]['Hcoin']==amount
        tier_goal=rules[quests[args.verify_handbook_miracle_tier]['FinishWayID']]['Progress']
        tier_amount=rewards[quests[args.verify_handbook_miracle_tier]['RewardID']]['Hcoin']
        assert len(set(handbook_unlocks[id] for id in miracle_catalog))==40 and all(handbook_unlocks[id]>0 for id in miracle_catalog)
        buffs=source_rows('RogueBuff.json')
        handbook_eligible_buffs=sorted({r['MazeBuffID'] for r in buffs if r.get('IsShow') and r.get('RogueVersion')==1 and r.get('MazeBuffLevel')==1})
        miracle_path_buffs={id:sorted({r['MazeBuffID'] for r in buffs if r.get('RogueVersion')==1 and r.get('MazeBuffLevel')==1 and r.get('RogueBuffType')==path}) for id,path in wax_paths.items()}
        guarded=[source_resources/'resource-manifest.json',*(source_resources/'Config/Level/Mission/1000201').rglob('*')]
        miracle_prerequisite_hashes={p.relative_to(source_resources).as_posix():hashlib.sha256(p.read_bytes()).hexdigest() for p in guarded if p.is_file()}
    if args.verify_handbook_blessing_quests:
        hash_files.extend(['RogueBuffType.json','QuestData.json','FinishWay.json','RogueMazeBuff.json'])
        def source_rows(name):
            rows=json.loads((source_resources/'ExcelOutput'/name).read_text('utf-8'))
            return list(rows.values()) if isinstance(rows,dict) else rows
        ways={r['ID']:r for r in source_rows('FinishWay.json')}
        quests={r['QuestID']:r for r in source_rows('QuestData.json')}
        for row in source_rows('RogueBuffType.json'):
            for id in row['RugueBuffTypeRewardQuestList']:
                way=ways[quests[id]['FinishWayID']]
                assert way['FinishType']=='RogueUnlockBuff' and way['ParamInt1']==row['RogueBuffType']
                blessing_quest_goals[id]=way['Progress'];blessing_quest_paths[id]=row['RogueBuffType']
        assert len(blessing_quest_goals)==53
        blessing_handbook_paths={r['MazeBuffID']:r['RogueBuffType'] for r in source_rows('RogueBuff.json')
            if r.get('IsShow') and r.get('RogueVersion')==1 and r.get('MazeBuffLevel')==1}
        if args.verify_handbook_blessing_tier:
            tier_goal=blessing_quest_goals[args.verify_handbook_blessing_tier]
            reward=next(r for r in source_rows('RewardData.json') if r['RewardID']==quests[args.verify_handbook_blessing_tier]['RewardID'])
            tier_amount=reward['Hcoin'];assert (tier_goal,tier_amount) in [(5,80),(24,30)]
            tier_allowed=sorted(r['MazeBuffID'] for r in source_rows('RogueBuff.json') if r.get('RogueBuffType')==120
                and r.get('MazeBuffLevel')==1 and r.get('IsShow') and r.get('RogueVersion')==1
                and r.get('RogueBuffCategory') in (['Common'] if tier_goal==5 else ['Common','Rare','Legendary']))[:tier_goal]
            assert len(tier_allowed)==tier_goal
            hash_files.extend(['RewardData.json','ItemConfig.json'])
    if multi_room:
        hash_files.append('RogueAreaConfig.json')
    if combat_miracle:
        hash_files.extend(['MazeBuff.json', 'RogueMiracleEffect.json', 'RogueMap.json', 'RogueMonster.json'])
    original_hashes = {name: hashlib.sha256((source_resources / 'ExcelOutput' / name).read_bytes()).hexdigest()
                       for name in hash_files}
    destruction_sources = [source_resources / name for name in [
        'ExcelOutput/MazeProp.json',
        'Config/LevelOutput/RuntimeFloor/P81122_F81122001.json',
        'Config/LevelOutput/SharedRuntimeGroup/Groups_P81122_F81122001/LevelGroup_P81122_F81122001_G73.json']] if args.miracle == 58 else []
    destruction_hashes = {str(path.relative_to(source_resources)).replace('\\', '/'): hashlib.sha256(path.read_bytes()).hexdigest()
                          for path in destruction_sources}
    code_source_names = ['ExcelOutput/NPCMonsterData.json', 'ExcelOutput/StageConfig.json',
        'Config/LevelOutput/RuntimeFloor/P81122_F81122001.json'] + [
        f'Config/LevelOutput/SharedRuntimeGroup/Groups_P81122_F81122001/LevelGroup_P81122_F81122001_G{group}.json'
        for group in [62, 63, 64, 73]] if args.verify_code_repair else []
    code_source_hashes = {name: hashlib.sha256((source_resources / name).read_bytes()).hexdigest()
                          for name in code_source_names}
    original_code_room = None
    original_room = None
    original_progress = fixture_progress = fixture_map = None
    for source in (source_resources / "ExcelOutput").glob("*.json"):
        target = excel / source.name
        if source.name == "ConstValueRogue.json":
            rows = json.loads(source.read_text(encoding="utf-8"))
            values = list(rows.values()) if isinstance(rows, dict) else rows
            initial = next(row for row in values if row["ConstRogueName"] == "Start_Rogue_Coin_Base")
            original_coins = initial["ConstValue"]
            initial["ConstValue"] = "30000" if args.verify_handbook_blessing_tier else "300"
            target.write_text(json.dumps(rows, ensure_ascii=False), encoding="utf-8")
        elif args.verify_handbook_blessing_tier and source.name == 'RogueBuff.json':
            rows=json.loads(source.read_text('utf-8'))
            values=list(rows.values()) if isinstance(rows,dict) else rows
            for row in values:
                row['UnlockIDList']=[] if row['MazeBuffID'] in tier_allowed else [1999999999]
            target.write_text(json.dumps(rows,ensure_ascii=False),encoding='utf-8')
        elif miracle and source.name == "RogueRoom.json":
            rows = json.loads(source.read_text(encoding="utf-8"))
            values = list(rows.values()) if isinstance(rows, dict) else rows
            room = next(row for row in values if row['RogueRoomID'] == 1000012)
            original_room = dict(room)
            # 80201/35 is the actual resource NPC1013/400001 and MiracleShop graph.
            # Only the isolated room assignment changes; source floor/groups stay untouched.
            room.update(MapEntrance=8020101, GroupID=1, GroupWithContent={'1': 0, '35': 0})
            if args.verify_code_repair:
                code_room = next(row for row in values if row['RogueRoomID'] == 122231)
                original_code_room = dict(code_room)
                # Existing source groups contain one distinct NPC each; no group/NPC/stage file changes.
                code_room['GroupWithContent'] = {'62': 4006, '63': 4003, '64': 4005, '73': 4005}
            target.write_text(json.dumps(rows, ensure_ascii=False), encoding="utf-8")
        elif miracle and not args.verify_handbook_miracle_tier and source.name == "RogueMiracle.json":
            rows = json.loads(source.read_text(encoding="utf-8"))
            values = list(rows.values()) if isinstance(rows, dict) else rows
            selected_rows = [row for row in values if row['MiracleID'] in miracle_catalog]
            assert {row['MiracleID'] for row in selected_rows} == set(miracle_catalog)
            target.write_text(json.dumps(selected_rows, ensure_ascii=False), encoding="utf-8")
        elif args.verify_handbook_miracle_tier and source.name=='MainMission.json':
            rows=json.loads(source.read_text('utf-8'))
            values=list(rows.values()) if isinstance(rows,dict) else rows
            row=next(r for r in values if r['MainMissionID']==1000201)
            row.update(TakeOperation='And',TakeParam=[{'Type':'Auto'}],BeginOperation='And',BeginParam=[{'Type':'Auto'}],RewardID=0,SubRewardList=[])
            target.write_text(json.dumps(rows,ensure_ascii=False),'utf-8')
        elif multi_room and source.name == 'RogueAreaConfig.json':
            rows = json.loads(source.read_text(encoding='utf-8'))
            values = list(rows.values()) if isinstance(rows, dict) else rows
            area = next(row for row in values if row['RogueAreaID'] == 100)
            source_area = next(row for row in values if row['RogueAreaID'] == 120)
            original_progress = area.get('AreaProgress', 0)
            fixture_progress = source_area['AreaProgress']
            area['AreaProgress'] = fixture_progress
            fixture_map = fixture_progress * 100 + area['Difficulty']
            # The normal merchant fixture uses tutorial map 1 with one terminal room.
            # Only the isolated area now references the actual multi-room map for exit checks.
            target.write_text(json.dumps(rows, ensure_ascii=False), encoding='utf-8')
        else:
            os.link(source, target)
    config_source=source_resources/'Config'
    if args.verify_handbook_miracle_tier:
        mission_fixture=directory/'mission-fixture'
        junctions=mirror_resources(source_resources,mission_fixture,{})
        folder=mission_fixture/'Config/Level/Mission/1000201'
        folder.mkdir(parents=True)
        sub=100020199
        (folder/'MissionInfo_1000201.json').write_text(json.dumps({'MainMissionID':1000201,'StartSubMissionList':[sub],'FinishSubMissionList':[sub]}),'utf-8')
        (folder/'MissionInfo_1000201.partial.json').write_text(json.dumps({'SubMissionList':[{'ID':sub,'MainMissionID':1000201,'TakeType':'Auto','FinishType':'Auto','Progress':1}]}),'utf-8')
        if junctions:
            plan=directory/'mission-junctions.json';plan.write_text(json.dumps(junctions),'utf-8')
            helper=directory/'prepare-mission.ps1'
            helper.write_text("param([string]$Plan)\n$ErrorActionPreference='Stop'\nforeach ($binding in (Get-Content -LiteralPath $Plan -Raw | ConvertFrom-Json)) { New-Item -ItemType Junction -Path $binding.target -Value $binding.source | Out-Null }\n",'utf-8')
            subprocess.run(['powershell','-NoProfile','-NonInteractive','-File',str(helper),'-Plan',str(plan)],check=True,capture_output=True,creationflags=subprocess.CREATE_NO_WINDOW)
        config_source=mission_fixture/'Config'
    if os.name == "nt":
        def literal(path): return "'" + str(path).replace("'", "''") + "'"
        subprocess.run(["powershell", "-NoProfile", "-NonInteractive", "-Command",
                        "New-Item -ItemType Junction -Path " + literal(fixture / "Config") +
                        " -Value " + literal(config_source) + " | Out-Null"],
                       check=True, capture_output=True, text=True, creationflags=subprocess.CREATE_NO_WINDOW)
    else:
        (fixture / "Config").symlink_to(config_source, target_is_directory=True)
    (fixture / "resource-manifest.json").write_text(json.dumps({"clientVersion": "4.6.0", "testFixture": True,
        "modifiedConst": "Start_Rogue_Coin_Base", "originalValue": original_coins}), encoding="utf-8")
    (directory / "data").mkdir()
    pool = json.loads((ROOT / "src/main/resources/defaults/RogueMapGen.json").read_text(encoding="utf-8"))
    original_pool = pool["1"]
    pool["1"] = [1000012]
    original_destruction_pool = None
    if args.miracle == 58:
        original_destruction_pool = pool['21']
        assert 122221 in original_destruction_pool
        pool['21'] = [122221]
    original_code_pool = None
    if args.verify_code_repair:
        original_code_pool = pool['21']
        assert 122231 in original_code_pool
        pool['21'] = [122231]
    if multi_room:
        maps = json.loads((excel / 'RogueMap.json').read_text(encoding='utf-8'))
        values = list(maps.values()) if isinstance(maps, dict) else maps
        sites = [row for row in values if row['RogueMapID'] == fixture_map]
        assert sites and all(str(row['SiteID']) in pool for row in sites)
        starts = [row for row in sites if row.get('IsStart')]
        assert len(starts) == 1 and starts[0]['SiteID'] == 1 and starts[0]['NextSiteIDList']
    (directory / "data/RogueMapGen.json").write_text(json.dumps(pool), encoding="utf-8")
    http_port, mongo_port, game_port = free_port(), free_port(), free_port(socket.SOCK_DGRAM)
    (directory / "config.json").write_text(json.dumps({"protocol": {"wireVersion": "4.6.0"}, "resourceDir": str(fixture),
        "httpServer": {"bindAddress": "127.0.0.1", "bindPort": http_port},
        "gameServer": {"bindAddress": "127.0.0.1", "bindPort": game_port},
        "internalMongoServer": {"address": "127.0.0.1", "port": mongo_port, "filePath": "./data/database.mv"}}), encoding="utf-8")
    command = [java, "-Xmx1536m", f"-Djdk.net.unixdomain.tmpdir={directory / 'tcp-fallback'}",
               "-jar", str(artifact), "--no-console", "--no-handbook"]
    if args.verify_handbook:
        command.remove('--no-console')
    base = f"http://127.0.0.1:{http_port}"

    def probe(log_name, credentials, combat=False):
        execution = subprocess.run([java, command[2], "-cp", str(artifact),
            "org.hkrpg.server.protocol.LoopbackRogueCombatProbe" if combat else
            "org.hkrpg.server.protocol.LoopbackRogueShopProbe", str(game_port)], input=json.dumps(credentials),
            cwd=directory, capture_output=True, text=True, encoding="utf-8", errors="replace", timeout=330 if args.verify_handbook_blessing_tier or args.verify_handbook_miracle_tier else 30,
            creationflags=subprocess.CREATE_NO_WINDOW if os.name == "nt" else 0)
        (directory / log_name).write_text(execution.stdout + execution.stderr, encoding="utf-8")
        if execution.returncode:
            raise RuntimeError(f"Resource merchant failed; inspect {directory / log_name}")
        result = json.loads(next(line for line in execution.stdout.splitlines() if line.startswith('{"uid":')))
        assert result["passed"] and not result["clientGameplayVerified"]
        return result

    snapshots = []
    laurel_snapshots = []
    battle_snapshots = []
    credentials = None
    normal_stops = 0
    for iteration in range(4 if args.verify_handbook else 3 if args.verify_laurel_battle or combat_miracle else 2):
        with (directory / f"server-{iteration}.log").open("w", encoding="utf-8") as log:
            process = subprocess.Popen(command, cwd=directory, stdin=subprocess.PIPE if args.verify_handbook else subprocess.DEVNULL, stdout=log, stderr=subprocess.STDOUT,
                                       creationflags=subprocess.CREATE_NO_WINDOW if os.name == "nt" else 0)
            try:
                deadline = time.monotonic() + 90
                while True:
                    if process.poll() is not None: raise RuntimeError(f"Merchant server failed; inspect {directory}")
                    try:
                        if request(base, "/health/ready")["ready"]: break
                    except (OSError, urllib.error.URLError): pass
                    if time.monotonic() > deadline: raise RuntimeError(f"Merchant server timeout; inspect {directory}")
                    time.sleep(.2)
                if args.verify_handbook and iteration >= 2:
                    restored = probe(f'handbook-outside-{iteration}.log', dict(credentials, handbookOnly=True, clearRun=iteration == 2))
                    assert restored['handbookBuffs'] == first['handbookBuffs'] and restored['handbookMiracles'] == first['handbookMiracles']
                    assert restored['handbookQueries'] == (2 if iteration == 2 else 1) and restored['handbookNotifications'] == 0
                    if args.verify_handbook_reward:
                        assert restored['handbookClaimed'] == first['handbookMiracles']
                        assert restored['loginJades'] == restored['confirmedJades'] == first['confirmedJades']
                        assert restored['rewardJadeNotifications'] == 0
                    assert restored['handbookRunCleared'] == (iteration == 2) and restored['uid'] == first['uid']
                    snapshots.append(restored)
                    time.sleep(2)
                    continue
                if iteration == 2:
                    if combat_miracle:
                        restored = probe('battle-miracle-after-restart.log', credentials, combat=True)
                        assert restored['savedCombatWalletRestored'] and restored['battleMiracleUnconsumed']
                        assert restored['battleTechniquePointsUnchanged'] and restored['fragments'] == battle_snapshots[0]['fragments']
                        battle_snapshots.append(restored)
                        time.sleep(2)
                        continue
                    restored = probe('laurel-battle-after-restart.log', credentials, combat=True)
                    assert restored['savedCombatWalletRestored'] and restored['laurelUseCount'] == 1
                    assert restored['laurelFullHealth'] and restored['fragments'] == laurel_snapshots[0]['fragments']
                    laurel_snapshots.append(restored)
                    time.sleep(2)
                    continue
                if iteration == 0:
                    login = request(base, "/hkrpg_cn/mdk/shield/api/login", {"account": "cn-46-merchant", "password": "local-test"})
                    assert login["retcode"] == 0
                    account = login["data"]["account"]
                    combo = request(base, "/hkrpg_cn/combo/granter/login/v2/login", {"data": json.dumps({"uid": account["uid"], "token": account["token"]})})
                    assert combo["retcode"] == 0
                    credentials = {"accountUid": account["uid"], "comboToken": combo["data"]["combo_token"]}
                    if args.verify_handbook:
                        credentials.update(verifyHandbook=True, handbookUnlocks=handbook_unlocks)
                    if args.verify_handbook_blessing_quests:
                        credentials.update(verifyBlessingQuests=True,blessingQuestGoals=blessing_quest_goals,
                            blessingQuestPaths=blessing_quest_paths,blessingHandbookPaths=blessing_handbook_paths)
                    if args.verify_handbook_blessing_tier:
                        credentials.update(blessingTier=args.verify_handbook_blessing_tier,tierAmount=tier_amount,initialFragments=30000)
                    if args.verify_handbook_miracle_tier:
                        credentials.update(miracleTier=args.verify_handbook_miracle_tier,tierAmount=tier_amount,handbookEligibleBuffs=handbook_eligible_buffs,miraclePathBuffs=miracle_path_buffs,allowedMiracles=miracle_catalog)
                    if args.verify_handbook_reward: credentials.update(verifyHandbookReward=True)
                    if miracle:
                        credentials.update(miracle=True, selected=args.miracle)
                    if eligible_path_ids:
                        credentials.update(expectedImmediateBuffs=eligible_path_ids)
                    first = probe("shop-purchase.log", credentials)
                    assert first["nativeMerchantPurchase"] and first["nativeMerchantRetryRejected"]
                    assert first["merchantCurrencyNotification"]
                    assert first['merchantMiracleNotification' if miracle else 'merchantBlessingNotification']
                    if miracle and not args.verify_handbook_miracle_tier:
                        assert first['selected'] == args.miracle and first['stock'] == {str(id): 200 for id in miracle_catalog}
                        assert first['fragments'] == 100
                    if args.verify_handbook_miracle_tier:
                        assert first['fragments']==100 and first['selected'] in miracle_catalog
                    snapshots.append(first)
                    if args.verify_handbook:
                        assert first['handbookQueries'] == (1+2*tier_goal if args.verify_handbook_blessing_tier or args.verify_handbook_miracle_tier else 5 if args.verify_handbook_reward else 3)
                        assert first['handbookNotifications'] > 0 or (not first['handbookBuffs'] and not first['handbookMiracles'])
                        credentials.update(expectedHandbookBuffs=first['handbookBuffs'], expectedHandbookMiracles=first['handbookMiracles'])
                        if args.verify_handbook_blessing_tier:
                            assert set(first['handbookBuffs'])==set(tier_allowed) and first['tierActualPurchases']==tier_goal
                            assert first['tierClaimed'] and first['tierRetryRejected'] and first['confirmedJades']==tier_amount
                            credentials.update(tierClaimed=True)
                        if args.verify_handbook_miracle_tier:
                            assert len(first['handbookMiracles'])==tier_goal and set(first['handbookMiracles'])<=set(handbook_unlocks[id] for id in miracle_catalog) and first['tierActualPurchases']==tier_goal
                            assert first['tierClaimed'] and first['tierRetryRejected'] and first['confirmedJades']==tier_amount
                            credentials.update(tierClaimed=True)
                        if args.verify_handbook_reward:
                            assert first['handbookOriginalRewardActuallyPaid'] and first['handbookInvalidBatchNoPayment'] and first['handbookClaimRetryRejected']
                            assert first['handbookClaimed'] == first['handbookMiracles']
                            assert first['confirmedJades'] == first['loginJades'] + 30 * len(first['handbookMiracles'])
                            credentials.update(claimedHandbookMiracles=first['handbookClaimed'])
                    credentials.update(resume=True, expectedFragments=first["fragments"], selected=first["selected"], stock=first["stock"])
                    if args.verify_handbook_miracle_tier and 'grantedBlessings' in first:
                        credentials.update(grantedBlessings=first['grantedBlessings'],expectedImmediateBuffs=miracle_path_buffs.get(first['selected'],[]))
                    if args.miracle == 21:
                        assert first['diceSelfReplacementVerified'] and first['diceReplacementNotifications'] == 1
                        assert first['diceReplacementId'] in [4, 6]
                        credentials.update(expectedDiceReplacementId=first['diceReplacementId'])
                    if immediate:
                        assert 1 <= len(first['grantedBlessings']) <= (2 if args.miracle == 5 else 1)
                        assert set(first['grantedBlessings'].values()) == {1}
                        if eligible_path_ids:
                            assert set(map(int, first['grantedBlessings'])) <= set(eligible_path_ids)
                        credentials.update(grantedBlessings=first['grantedBlessings'])
                restored = probe(f"shop-resume-{iteration}.log", credentials)
                assert restored["savedShopStockAndOwnershipRestored"] and restored["nativeMerchantRetryRejected"]
                assert restored["uid"] == first["uid"] and restored["fragments"] == first["fragments"]
                if args.miracle == 21:
                    assert restored['diceSelfReplacementVerified'] and restored['diceReplacementNotifications'] == 0
                    assert restored['diceReplacementId'] == first['diceReplacementId']
                if immediate:
                    assert restored['grantedBlessings'] == first['grantedBlessings']
                snapshots.append(restored)
                if args.verify_handbook:
                    assert restored['handbookBuffs'] == first['handbookBuffs'] and restored['handbookMiracles'] == first['handbookMiracles']
                    assert restored['handbookQueries'] == 3 and restored['handbookNotifications'] == 0
                    if args.verify_handbook_reward:
                        assert restored['handbookClaimed'] == first['handbookMiracles']
                        assert restored['loginJades'] == restored['confirmedJades'] == first['confirmedJades']
                        assert restored['rewardJadeNotifications'] == 0
                if iteration == 1 and args.verify_rest_exit:
                    exit_snapshot = probe('rest-exit.log', dict(credentials, verifyRestExit=True))
                    assert exit_snapshot['actualRestPortalOpen'] and exit_snapshot['actualRestExitEntered']
                    assert exit_snapshot['restExitWrongDestinationRejected'] and exit_snapshot['restExitRetryRejected']
                    assert exit_snapshot['restExitNoVictoryRewards']
                    snapshots.append(exit_snapshot)
                if iteration == 1 and args.verify_laurel_battle:
                    credentials.update(laurel=True, fightLaurel=True)
                    won = probe('laurel-battle.log', credentials, combat=True)
                    assert won['actualLaurelLossConvertedToVictory'] and won['actualRogueVictoryAndRetry']
                    assert won['laurelDamageNotification'] and won['laurelFullHealth']
                    assert won['laurelUseCount'] == won['laurelTotalCount'] == 1
                    assert 120 <= won['fragments'] <= 140
                    laurel_snapshots.append(won)
                    credentials.update(fightLaurel=False, expectedFragments=won['fragments'])
                    restored = probe('laurel-battle-reconnect.log', credentials, combat=True)
                    assert restored['savedCombatWalletRestored'] and restored['laurelUseCount'] == 1
                    assert restored['laurelFullHealth'] and restored['fragments'] == won['fragments']
                    laurel_snapshots.append(restored)
                if iteration == 1 and combat_miracle:
                    credentials.update(battleMiracle=args.miracle, fightMiracle=True, codeRepair=args.verify_code_repair)
                    won = probe('battle-miracle.log', credentials, combat=True)
                    if args.verify_room_miracle:
                        assert won['roomFragmentCurrencyNotification'] and won['roomFragmentBalance'] == 95
                        assert won['roomClockRepeatEntryRejected'] and won['roomClockHasNoCurioBattleBuff'] and won['roomClockBattleQueryStable']
                    else:
                        assert won['battleBindingVerified'] and won['battleBindingQueryStable']
                        if args.miracle == 14:
                            assert won['battleDynamicValues'] == {'ItemNumber': 100, '#ADF_1': 100, '#ADF_2': .16}
                        if args.miracle == 58:
                            assert won['actualDestructibleAttacked'] and won['destructibleRetryKeepsOneCount']
                            assert won['actualDestructibleRemoved'] and won['destructibleRetryRejected']
                            assert won['battleDynamicValues'] == {'RoguePropCounter_0_7394D591': 1, '#ADF_1': .03}
                    assert won['battleMiracleUnconsumed'] and won['battleTechniquePointsUnchanged']
                    assert won['actualRogueVictoryAndRetry'] and (180 if args.verify_code_repair else 115 if args.verify_room_miracle else 120) <= won['fragments'] <= (260 if args.verify_code_repair else 135 if args.verify_room_miracle else 140)
                    if args.verify_code_repair:
                        assert won['codeThreeWinsRepaired'] and won['codeFourthBattleUsesRepairedBinding']
                        assert won['codeProgressNotifications'] == [1, 2] and won['codeReplacementNotification']
                        assert [row['beforeWins'] for row in won['codeBattleSnapshots']] == [0, 1, 2, 3]
                        assert [row['buff'] for row in won['codeBattleSnapshots']] == [630968 + args.miracle] * 3 + [630969 + args.miracle]
                        assert len({row['battleId'] for row in won['codeBattleSnapshots']}) == 4
                    battle_snapshots.append(won)
                    credentials.update(fightMiracle=False, expectedFragments=won['fragments'], expectedMp=won['techniquePoints'])
                    restored = probe('battle-miracle-reconnect.log', credentials, combat=True)
                    assert restored['savedCombatWalletRestored'] and restored['battleMiracleUnconsumed']
                    assert restored['battleTechniquePointsUnchanged'] and restored['fragments'] == won['fragments']
                    battle_snapshots.append(restored)
                time.sleep(2)  # Allow H2's delayed disk commit before the process restart.
            finally:
                if process.poll() is None:
                    if args.verify_handbook:
                        try:
                            process.stdin.write(b'stop\n')
                            process.stdin.flush()
                            process.wait(timeout=30)
                            assert process.returncode == (1000 if os.name == 'nt' else 232), directory
                            normal_stops += 1
                        except BaseException:
                            if process.poll() is None:
                                process.terminate()
                                process.wait(timeout=15)
                            raise
                        finally:
                            process.stdin.close()
                    else:
                        process.terminate()
                        try: process.wait(timeout=15)
                        except subprocess.TimeoutExpired:
                            process.kill()
                            process.wait(timeout=5)
    assert hashlib.sha256(original_const.read_bytes()).hexdigest() == original_sha
    if args.verify_handbook:
        assert normal_stops == 4 and len(snapshots) == 5 and len({row['uid'] for row in snapshots}) == 1
    if args.verify_handbook_miracle_tier:
        assert all(s['miracleQuestProgress']=={str(id):min(tier_goal,goal) for id,goal in [(4092318,3),(4092319,40)]} for s in snapshots)
        assert all(s['tierClaimed'] and s['confirmedJades']==tier_amount for s in snapshots)
        assert all(s['miracleQuestQueries']==2 and s['miracleQuestLoginSnapshotVerified'] for s in snapshots)
        assert snapshots[0]['miracleQuestNotifications']>=tier_goal and snapshots[0]['tierWalletNotifications']==1
        assert all(s['miracleQuestNotifications']==s['tierWalletNotifications']==0 for s in snapshots[1:])
    for name, expected in original_hashes.items():
        assert hashlib.sha256((source_resources / 'ExcelOutput' / name).read_bytes()).hexdigest() == expected
    for name, expected in destruction_hashes.items():
        assert hashlib.sha256((source_resources / name).read_bytes()).hexdigest() == expected
    for name, expected in code_source_hashes.items():
        assert hashlib.sha256((source_resources / name).read_bytes()).hexdigest() == expected
    for name, expected in miracle_prerequisite_hashes.items():
        assert hashlib.sha256((source_resources / name).read_bytes()).hexdigest()==expected
    print(json.dumps({"passed": True, "runtimeDirectory": str(directory), "checks": ["actual-resource-merchant-native-purchase",
        "actual-purchase-miracle-and-wallet-notifications" if miracle else "actual-purchase-blessing-and-wallet-notifications", "duplicate-merchant-purchase-rejected",
        "stock-price-ownership-wallet-restored-on-reconnect", "stock-price-ownership-wallet-restored-after-h2-process-restart"]
        + (["immediate-distinct-blessings-notified-and-restored-without-reroll"] if immediate else [])
        + (["actual-rest-portal-and-map-unlocked-after-restart", "bound-adjacent-rest-exit-forged-and-repeat-rejection",
            "rest-exit-keeps-wallet-and-blessings-and-combat-room-unfinished"] if args.verify_rest_exit else []),
        "snapshots": snapshots, "laurelBattleSnapshots": laurel_snapshots,
        "battleMiracleSnapshots": battle_snapshots if args.verify_battle_miracle else [],
        "roomMiracleSnapshots": battle_snapshots if args.verify_room_miracle else [],
        "roomMiracleChecks": (["acquired-before-real-adjacent-room", "exact-room-debit-and-currency-notification",
            "repeated-room-entry-rejected-and-wallet-queried", "no-fabricated-curio-battle-buff",
            "current-battle-query-keeps-binding-absence", "wrong-and-repeated-combat-report-rejected",
            "technique-points-unchanged", "wallet-restored-after-reconnect", "wallet-restored-after-h2-restart"] if args.verify_room_miracle else []),
        "battleMiracleChecks": (["acquired-before-actual-resource-combat", "exact-resource-battle-binding",
            "current-battle-query-retains-binding", "wrong-and-repeated-report-rejected",
            "battle-boost-points-do-not-spend-technique-points", "permanent-curio-wallet-restored-after-reconnect",
            "permanent-curio-wallet-restored-after-h2-restart"] if args.verify_battle_miracle else [])
            + (["actual-battle-fragment-snapshot-and-acquired-ratio"] if args.verify_battle_miracle and args.miracle == 14 else [])
            + (["actual-destructible-removed-once-and-repeated-attack-rejected",
                "actual-destruction-count-and-acquired-ratio-in-native-battle"] if args.verify_battle_miracle and args.miracle == 58 else []),
        "codeRepairChecks": (["three-distinct-resource-bound-wins", "two-saved-progress-notifications",
            "native-1957-replacement-notification", "fourth-battle-uses-repaired-resource-binding",
            "repaired-ownership-wallet-and-technique-points-restored-after-reconnect-and-h2-restart"] if args.verify_code_repair else []),
        "laurelBattleChecks": (["acquired-laurel-before-actual-resource-combat",
            "accepted-loss-converted-to-native-victory", "wrong-and-repeated-report-rejected",
            "one-use-damage-notification-and-full-roster-health", "health-curio-wallet-restored-after-reconnect",
            "health-curio-wallet-restored-after-h2-restart"] if args.verify_laurel_battle else []),
        "fixture": {"sourceResourcesModified": False, "firstSiteRoom": 1000012,
        "originalFirstSitePool": original_pool, "initialFragments": 30000 if args.verify_handbook_blessing_tier else 300, "originalInitialFragments": original_coins,
        "tierFixtureAllowedBlessings":tier_allowed,"tierFixtureQualificationOverride":"UnlockIDList only" if args.verify_handbook_blessing_tier else None,
        "npc": 1013 if miracle else 1007, "group": 35 if miracle else 77,
        "instance": 400001 if miracle else 400002, 'selectedMiracle': args.miracle,
        'fixtureMapEntrance': 8020101 if miracle else 8030301, 'originalRoom': original_room,
        'fixtureMiracleCatalog': miracle_catalog if miracle else None, 'sourceResourceHashes': original_hashes,
        'syntheticNoRewardPrerequisites':[1000201] if args.verify_handbook_miracle_tier else [],
        'originalPrerequisiteSourceHashes':miracle_prerequisite_hashes,
        'officialTutorialVerified':False,
        'destructionSourceHashes': destruction_hashes, 'originalDestructionSite21Pool': original_destruction_pool,
        'codeRepairVerified': args.verify_code_repair, 'originalCodeSite21Pool': original_code_pool,
        'originalCodeRoom': original_code_room, 'codeSourceHashes': code_source_hashes,
        'codeFixtureGroupContents': {'62': 4006, '63': 4003, '64': 4005, '73': 4005} if args.verify_code_repair else None,
        'immediateBlessingPath': wax_paths.get(args.miracle), 'eligiblePathBlessings': eligible_path_ids,
        'restExitVerified': args.verify_rest_exit,
        'laurelBattleVerified': args.verify_laurel_battle,
        'battleMiracleBindingsVerified': args.verify_battle_miracle,
        'roomMiracleLossVerified': args.verify_room_miracle,
        'battleAbilityExecutionClientVerified': False,
        'originalAreaProgress': original_progress, 'fixtureAreaProgress': fixture_progress,
        'fixtureRogueMap': fixture_map,
        "officialRoomGenerationVerified": False},
        "diceCurioChecks": (["native-add-21-before-self-replacement", "actual-unique-source-curio-instead-of-21",
            "frozen-200-fragments-debit-once", "same-uid-reconnect-keeps-replacement-and-purchased-stock",
            "normal-h2-restart-keeps-replacement-and-purchased-stock"] if args.miracle == 21 else []),
        "rootJarSHA256": root_jar_sha,
        "handbookChecks": (["actual-empty-initial-query", "actual-source-merchant-acquisition", "explicit-source-unlock-only",
            "native-canonical-collection-notification" if not miracle or args.miracle == 21 or handbook_unlocks.get(args.miracle, 0) > 0 else "no-collection-notification-for-unlinked-curio", "outside-query-keeps-collected-history", "repeated-query-does-not-collect",
            "same-uid-reconnect-keeps-history", "same-uid-normal-h2-restart-keeps-history",
            "actual-quit-removes-run-without-removing-history", "second-normal-h2-restart-keeps-ended-run-history",
            "query-and-reconnect-never-emit-collection-notifications"] if args.verify_handbook else []),
        "normalStopCount": normal_stops,
        "blessingTierChecks":(["actual-distinct-source-purchases", "source-exact-five-or-twenty-four-threshold", "native-original-eighty-or-thirty-jades",
            "actual-jade-notification-before-claim-success", "duplicate-native-claim-no-payment", "source-query-closed-only-after-payment",
            "same-uid-normal-restarts-keep-wallet-and-closed-state"] if args.verify_handbook_blessing_tier else []),
        "miracleQuestChecks":(["actual-distinct-source-curio-purchases","both-original-three-and-forty-goals","native-progress-before-purchase-success","exact-five-or-ten-jades-notified-before-claim-success","duplicate-native-claim-no-payment","same-uid-source-progress-and-wallet-after-normal-restart","ended-run-collection-and-closed-state-retained"] if args.verify_handbook_miracle_tier else []),
        "blessingQuestChecks": (["all-original-fifty-three-source-tiers-present", "actual-purchase-distinct-progress-only",
            "native-quest-progress-notification", "source-payment-only-after-actual-count" if args.verify_handbook_blessing_tier else "no-fixed-completion-or-payment", "explicit-native-query",
            "same-uid-reconnect-source-progress", "normal-h2-restart-source-progress", "ended-run-progress-survives-second-restart"]
            if args.verify_handbook_blessing_quests else []),
        "sameUIDNormalH2ProcessRestarts": 3 if args.verify_handbook else None,
        "handbookRewardChecks": (["invalid-whole-batch-no-payment-or-taken-flags", "original-thirty-jades-per-actual-collected-identity",
            "actual-wallet-notification-before-success", "actually-paid-identities-only", "packed-claim-and-unpacked-retry",
            "duplicate-claim-no-second-payment", "native-query-taken-flags", "same-uid-reconnect-wallet-and-flags",
            "normal-h2-restart-wallet-and-flags", "ended-run-wallet-and-claims-survive-next-restart"] if args.verify_handbook_reward else []),
        "diceOfficialReplacementPoolVerified": False,
        "clientGameplayVerified": False}, ensure_ascii=False))


if __name__ == "__main__":
    main()
