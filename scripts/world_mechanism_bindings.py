"""跨组奖励引用及带有明确完成回执的时锁绑定；所有路径均来自固定资源。"""
from pathlib import PurePosixPath


def mission_index(root, read):
    result = {}
    for path in sorted((root / "Config/Level/Mission").glob("*/MissionInfo_*.partial.json")):
        relative = path.relative_to(root).as_posix()
        info = read(relative)
        subs = {s["ID"]: s for s in (info or {}).get("SubMissionList", [])}
        for sub in subs.values():
            if (sub.get("FinishType") != "PropState" or sub.get("ParamInt3") not in (12, 13)
                    or sub.get("ParamType", "Equal") != "Equal" or sub.get("Progress") != 1):
                continue
            key = (sub.get("LevelFloorID"), sub.get("ParamInt1"), sub.get("ParamInt2"))
            result.setdefault(key, []).append((relative, sub, subs))
    return result


def mission_rule(loc, group, read, tables, pin, used, rows, api, index):
    for mission_path, sub, subs in index.get((loc["floor"], loc["group"], loc["instance"]), []):
        graph = sub.get("MissionJsonPath")
        raw = read(graph) if graph else None
        if raw is None:
            continue
        reports = []
        for path, node in api.walk(raw):
            if (node.get("$type") == "RPG.GameCore.PropReqInteract"
                    and api.target(node, None, loc["group"], {}) == (loc["group"], loc["instance"])
                    and not node.get("InteractID", {}).get("IsDynamic")):
                code = node.get("InteractID", {}).get("FixedValue", {}).get("Value")
                initial = next(p["State"] for p in group["PropList"] if p["ID"] == loc["instance"])
                row = tables["InteractConfig"].get(code, {})
                if code in (111120, 120120) and row.get("SrcState") == initial and row.get("TargetState") == "ChestClosed":
                    reports.append((path, code))
        if reports:
            pin(mission_path, used)
            pin(graph, used)
            codes = sorted({n for _, n in reports})
            rows.update(f"InteractConfig:{n}" for n in codes)
            return dict(kind="CLIENT_REPORT", reports=codes, mission=sub["ID"],
                        evidence=[graph + "#" + p for p, _ in reports])
        sequences = [s.get("TaskList", []) for s in raw.get("OnStartSequece", [])]
        if (sub["ID"] == 201050302 and sub.get("ParamInt3") == 13
                and sequences == [
                    [{"$type": "RPG.GameCore.TriggerCustomString",
                      "CustomString": {"Value": "chest_201050302"}}],
                    [{"$type": "RPG.GameCore.WaitCustomString",
                      "CustomString": {"Value": "chest_201050302"}},
                     {"$type": "RPG.GameCore.ChangePropState"}]]
                and sub.get("TakeParamIntList") == [201050301]):
            pin(mission_path, used)
            pin(graph, used)
            return dict(kind="MISSION", mission=sub["ID"], controllerMission=201050301,
                        evidence=[mission_path + "#SubMission/201050301->201050302",
                                  graph + "#automatic-custom-string+single-chest-objective"])
        # A reward objective starts after a board-completion objective AND its dialogue.
        # The original goal supplies the exact chest; its one startup action reveals it.
        tasks = [n for seq in raw.get("OnStartSequece", []) for n in seq.get("TaskList", [])]
        previous = sub.get("TakeParamIntList", [])
        predecessor = subs.get(previous[0]) if len(previous) == 1 else None
        deploy = bool(predecessor and predecessor.get("FinishType") == "PropState"
                      and predecessor.get("ParamInt3") == 1)
        talk = predecessor if not deploy else None
        parents = (talk or {}).get("TakeParamIntList", [])
        board = predecessor if deploy else subs.get(parents[0]) if len(parents) == 1 else None
        if ((not deploy and (not talk or talk.get("FinishType") != "Talk"
                            or tasks != [{"$type": "RPG.GameCore.ChangePropState"}])) or not board
                or sub.get("ParamInt3") != 13
                or board.get("FinishType") != "PropState" or board.get("ParamInt3") != 1):
            continue
        gid, instance = board.get("ParamInt1"), board.get("ParamInt2")
        control_path = str(PurePosixPath(loc["path"]).parent /
                           f"LevelGroup_P{loc['plane']}_F{loc['floor']}_G{gid}.json")
        control = read(control_path)
        actor = next((p for p in (control or {}).get("PropList", []) if p["ID"] == instance), None)
        expected = ("Config/Level/Maze/MazeExtra/DeployPuzzle/DeployPuzzle_Board.json" if deploy else
                    "Config/Level/Props/Chap02/InitLevelGraph_Prop_Chap02_DestroyrootPuzzle_02.json")
        if not actor or actor.get("InitLevelGraph") != expected:
            continue
        if deploy:
            name = api.values(actor.get("ValueSource")).get("CustomString_PerformanceFinish")
            if not name or not any(s == [{"$type": "RPG.GameCore.ChangePropState"}]
                                   for s in sequences):
                continue
            if not any(s and s[0] == {"$type": "RPG.GameCore.WaitCustomString",
                                     "CustomString": {"Value": name}} for s in sequences):
                continue
            completion = [n.get("FinishCallback", []) for _, n in api.walk(read(expected))
                          if n.get("$type") == "RPG.GameCore.PropPuzzleEventListener"]
        else:
            completion = [s for s in (read(expected) or {}).get("OnStartSequece", [])
                          if s.get("TaskList", [{}])[0] == {
                              "$type": "RPG.GameCore.WaitCustomString",
                              "CustomString": {"Value": "FinishedTriggerVCam"}, "WaitOwnerOnly": True}]
        calls = [p for p, n in api.walk(completion)
                 if n.get("$type") == "RPG.GameCore.PropReqInteract"
                 and api.target(n, instance, gid, {}) == (gid, instance)
                 and n.get("InteractID", {}).get("FixedValue", {}).get("Value") == 1010
                 and not n.get("InteractID", {}).get("IsDynamic")]
        if len(calls) != 1:
            continue
        for path in (mission_path, graph, expected, control_path):
            pin(path, used)
        rows.update([f"MazeProp:{actor['PropID']}", "InteractConfig:1010"])
        return dict(kind="MISSION", mission=sub["ID"], controllerMission=board["ID"],
                    controllerGroup=gid, controllerPath=control_path,
                    controller=instance, controllerProp=actor["PropID"], finish=1010,
                    interactions=[1010], evidence=[mission_path + f"#SubMission/{board['ID']}->{sub['ID']}",
                                                  graph + "#OnStartSequece", expected + "#" + calls[0]])
    return None


def extend(group, loc, root, read, tables, pin, used, rows, api, missions):
    result = mission_rule(loc, group, read, tables, pin, used, rows, api, missions)
    if result:
        return result
    folder = PurePosixPath(loc["path"]).parent
    siblings = [(p.relative_to(root).as_posix(), read(p.relative_to(root).as_posix()))
                for p in sorted((root / folder).glob("*.json"))]
    address, values, walk, target = api.address, api.values, api.walk, api.target
    pair = (loc["group"], loc["instance"])
    pad = "Config/Level/Maze/MazeExtra/PadsPuzzle/Padpuzzle_HideChestControl.json"
    common = "Config/Level/Maze/MazeExtra/PadsPuzzle/Padpuzzle_Common.json"
    matches = []
    for path, candidate in siblings:
        gid = int(path.rsplit("_G", 1)[1].split(".")[0].split("_")[0])
        for actor in candidate.get("PropList", []):
            graph = actor.get("InitLevelGraph")
            linked = api.LINKED_GRAPHS.get(graph)
            link = values(actor.get("ValueSource")).get(linked)
            if candidate.get("LevelGraph") == pad and graph == common:
                link = values(candidate.get("ValueSource")).get("Chest")
            if (address(link) != pair or actor.get("IsDelete") or actor.get("IsClientOnly")
                    or not actor.get("LoadOnInitial", True)):
                continue
            raw = read(graph)
            shared = values((raw or {}).get("ValueSource")) | values(actor.get("ValueSource"))
            calls = [(p, n) for p, n in walk(raw)
                     if n.get("$type") == "RPG.GameCore.PropReqInteract"
                     and target(n, actor["ID"], gid, shared) == (gid, actor["ID"])
                     and not n.get("InteractID", {}).get("IsDynamic")]
            finish = [(p, n) for p, n in calls if "/FinishCallback/" in p]
            codes = {n.get("InteractID", {}).get("FixedValue", {}).get("Value") for _, n in finish}
            if len(codes) != 1 or None in codes:
                continue
            matches.append((path, candidate, gid, actor, graph, calls, finish, codes.pop()))
    if len(matches) == 1:
        path, candidate, gid, actor, graph, calls, finish, code = matches[0]
        pin(path, used)
        pin(graph, used)
        if candidate.get("LevelGraph"):
            pin(candidate["LevelGraph"], used)
        interactions = sorted({n["InteractID"]["FixedValue"]["Value"] for _, n in calls})
        rows.add(f"MazeProp:{actor['PropID']}")
        rows.update(f"InteractConfig:{i}" for i in interactions)
        return dict(kind="CONTROLLER", controller=actor["ID"], controllerProp=actor["PropID"],
                    controllerGroup=gid, controllerPath=path, finish=code,
                    interactions=interactions,
                    evidence=[path + "#exact-chest-reference", graph + "#" + finish[0][0]])

    treasure = "Config/Level/Maze/MazeExtra/TimeLock/TimeLock_Treasure_GroupGraph.json"
    if group.get("LevelGraph") != treasure:
        return None
    shared = values(group.get("ValueSource"))
    fsv = shared.get("FSV")
    if address(shared.get("Treasure")) != pair or not fsv:
        return None
    matches = [(p, c) for p, c in siblings
               if c.get("LevelGraph", "").startswith("Config/Level/Maze/MazeExtra/TimeLock/TimeLock_")
               and values(c.get("ValueSource")).get("FSV_PuzzleState") == fsv]
    if len(matches) != 1:
        return None
    path, control = matches[0]
    gid = int(path.rsplit("_G", 1)[1].split(".")[0].split("_")[0])
    graph = control["LevelGraph"]
    calls = [(p, n) for p, n in walk(read(graph))
             if n.get("$type") == "RPG.GameCore.RequestSetServerGroupProperty"
             and n.get("DynamicName") == {"Value": "Srv_PuzzleState"}
             and n.get("Value") == 1 and n.get("GroupID", gid) == gid]
    if len(calls) != 1:
        return None
    # The mission explicitly waits for this same saved value to become 1.
    mission = "Config/Level/Mission/4016201/MissionInfo_4016201.partial.json"
    late = values(control.get("ValueSource")).get("FSV_PuzzleState_Late", "")
    if late and not any(n.get("$type") == "RPG.GameCore.RequestSetServerGroupPropertyV2"
                        and n.get("DynamicName") == {"Value": "Srv_KindlingFall"}
                        and n.get("Value") == 1 for _, n in walk(read(graph))):
        return None
    objectives = [s for s in (read(mission) or {}).get("SubMissionList", [])
                  if s.get("FinishType") == "FloorSavedValue" and s.get("ParamStr1") == (late or fsv)
                  and s.get("ParamInt1") == 1 and s.get("ParamType", "Equal") == "Equal"]
    if len(objectives) != 1:
        return None
    for item in (path, graph, treasure, mission,
                 f"Config/LevelOutput/RuntimeFloor/P{loc['plane']}_F{loc['floor']}.json"):
        pin(item, used)
    result = dict(kind="GROUP_PROPERTY", controllerGroup=gid, controllerPath=path,
                property="Srv_PuzzleState", propertyValue=1, floorValue=fsv, finish=1,
                evidence=[loc["path"] + "#FSV+Treasure", path + "#FSV_PuzzleState",
                          graph + "#" + calls[0][0], mission + f"#SubMission/{objectives[0]['ID']}"])
    if late:
        result["lateFloorValue"] = late
    return result
