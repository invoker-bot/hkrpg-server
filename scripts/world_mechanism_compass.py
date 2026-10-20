"""任务罗盘的完成控制器、守卫和对话奖励链；只接受原文中的准确引用。"""

BOARD = "Config/Level/Props/Chap02/InitLevelGraph_Puzzle_Chap02_PuzzleCompass_01.json"
SHIP = "Config/Level/Props/Chap02/InitLevelGraph_Prop_Chap02_CargoShip_01_"
GUARDS = "Config/Level/Maze/Chapter02/X201/Maze_Chap02_X201_GroupEvent_PuzzleCompass_SR01.json"


def compass_rule(group, loc, read, tables, pin, used, rows, api, index):
    pair = loc["group"], loc["instance"]
    actors = {p["ID"]: p for p in group.get("PropList", [])}
    for board in actors.values():
        if board.get("InitLevelGraph") != BOARD:
            continue
        shared = api.values(board.get("ValueSource"))
        control = api.address(shared.get("PuzzleCompass_ControllerProp"))
        actor = actors.get(control[1]) if control and control[0] == loc["group"] else None
        if (not actor or actor.get("PropID") not in (100, board["PropID"])
                or actor.get("State", "Closed") != "Closed"):
            continue
        ships = [p for p in actors.values()
                 if p.get("InitLevelGraph") in (SHIP + "TakeMonster.json", SHIP + "TakeNPC_SP01.json")
                 and api.address(api.values(p.get("ValueSource")).get("PuzzleCompass_Prop"))
                 == (loc["group"], board["ID"])
                 and api.address(api.values(p.get("ValueSource")).get("PuzzleCompass_CargoChest01")) == pair]
        if len(ships) != 1 or any(p.get("IsDelete") or p.get("IsClientOnly")
                                 or not p.get("LoadOnInitial", True) for p in (board, actor, ships[0])):
            continue
        finishes = [path for path, n in api.walk(read(BOARD))
                    if n.get("$type") == "RPG.GameCore.PropReqInteract" and "/FinishCallback/" in path
                    and n.get("InteractID") == {"IsDynamic": False, "FixedValue": {"Value": 1010}}
                    and api.target(n, board["ID"], loc["group"], shared) == control]
        if len(finishes) != 1:
            continue
        for mission_path, sub, subs in index.get((loc["floor"], *pair), []):
            if sub.get("ParamInt3") != 13:
                continue
            files = [mission_path, BOARD, ships[0]["InitLevelGraph"], actor.get("InitLevelGraph")]
            base = dict(controller=actor["ID"], controllerProp=actor["PropID"], finish=1010,
                        interactions=[1010], mission=sub["ID"],
                        evidence=[loc["path"] + "#compass-controller+ship-chest",
                                  BOARD + "#" + finishes[0], mission_path + f"#SubMission/{sub['ID']}"])
            monster = api.address(api.values(ships[0].get("ValueSource")).get("PuzzleCompass_MonsterID"))
            if (ships[0]["InitLevelGraph"] == SHIP + "TakeMonster.json"
                    and group.get("LevelGraph") == GUARDS
                    and api.address(api.values(group.get("ValueSource")).get("GroupUnLockChest")) == pair
                    and monster and monster[0] == loc["group"]):
                guard = next((m for m in group.get("MonsterList", []) if m["ID"] == monster[1]), None)
                goals = [s for s in subs.values() if s.get("FinishType") == "KillMonster"
                         and s.get("ParamInt1") == loc["group"] and s.get("ParamInt2") == monster[1]
                         and s.get("TakeParamIntList") == sub.get("TakeParamIntList")]
                if (not guard or not goals or guard.get("IsDelete") or guard.get("IsClientOnly")
                        or not guard.get("LoadOnInitial", True) or guard.get("FarmElementID")):
                    continue
                base.update(kind="GUARDS", guards=[monster[1]])
                files.append(GUARDS)
                rows.add(f"NPCMonsterData:{guard['NPCMonsterID']}")
            elif ships[0]["InitLevelGraph"] == SHIP + "TakeNPC_SP01.json":
                # The reward mission has its own controller cleanup and one reveal action.
                graph = sub.get("MissionJsonPath")
                raw = read(graph) if graph else None
                sequences = [s.get("TaskList", []) for s in (raw or {}).get("OnStartSequece", [])]
                if len(sequences) != 2 or sequences[1] != [{"$type": "RPG.GameCore.ChangePropState"}]:
                    continue
                if (len(sequences[0]) != 1 or sequences[0][0].get("$type") != "RPG.GameCore.PropStateExecute"
                        or sequences[0][0].get("State") != "Open"
                        or api.target(sequences[0][0], None, loc["group"], {}) != control):
                    continue
                cleanup = [n for _, n in api.walk(sequences[0])
                           if n.get("$type") == "RPG.GameCore.PropReqInteract"]
                if (len(cleanup) != 1 or api.target(cleanup[0], None, loc["group"], {}) != control
                        or cleanup[0].get("InteractID") != {"IsDynamic": False, "FixedValue": {"Value": 1120}}):
                    continue
                prior = sub
                chain = []
                for _ in range(3):
                    parents = prior.get("TakeParamIntList", [])
                    prior = subs.get(parents[0]) if len(parents) == 1 else None
                    if not prior or prior.get("FinishType") != "Talk":
                        break
                    chain.append(prior)
                parents = (prior or {}).get("TakeParamIntList", [])
                start = subs.get(parents[0]) if len(chain) == 3 and len(parents) == 1 else None
                if (not start or start.get("FinishType") != "PropState"
                        or start.get("LevelFloorID") != loc["floor"] or start.get("Progress") != 1
                        or (start.get("ParamInt1"), start.get("ParamInt2")) != control
                        or start.get("ParamInt3") != 1):
                    continue
                base.update(kind="MISSION", controllerMission=start["ID"], interactions=[1010,1120],
                            reports=[1120])
                files += [graph, start.get("MissionJsonPath")] + [s.get("MissionJsonPath") for s in chain]
                rows.add("InteractConfig:1120")
                for s in chain:
                    for action in s.get("FinishActionList", []):
                        if action.get("FinishActionType") in ("addMissionItem", "delMissionItem"):
                            rows.update(f"ItemConfig:{i}" for i in action["FinishActionPara"][::2])
            else:
                continue
            if any(not f or read(f) is None for f in files):
                continue
            for f in files:
                pin(f, used)
            rows.update(["MazeProp:100", f"MazeProp:{board['PropID']}", "InteractConfig:1010"])
            return base
    return None
