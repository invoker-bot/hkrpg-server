"""从固定 4.6 原文提取守卫与客户端完成回执；不猜测被剥离的服务端脚本字段。"""
import argparse
from collections import Counter
import json
from pathlib import Path
import sys

from source_json import loads, fingerprint
from world_mechanism_bindings import extend, mission_index
from world_mechanism_compass import compass_rule

ROOT = Path(__file__).resolve().parents[1]
OUTPUT = ROOT / "src/main/resources/gameplay/world-mechanisms-4.6.json"
FIXTURE = ROOT / "src/test/resources/fixtures/world-mechanisms-4.6.json"
GUARD_GRAPH = "Config/Level/Maze/MazeGroup/Maze_Group_OpenTreasure_WhenMonsterDie.json"
LINKED_GRAPHS = {
    "Config/Level/Props/Chap01/InitLevelGraph_Prop_Chap01_BoxMan_01.json": "ChestID",
    "Config/Level/Props/Chap01/InitLevelGraph_Prop_Chap01_CablePuzzle_05.json": "Chest",
    "Config/Level/Props/Chap02/InitLevelGraph_Puzzle_Chap02_RubikCube_Config.json": "TargetChest",
    "Config/Level/Props/Chap02/InitLevelGraph_Puzzle_Chap02_RubikCube_Advanced_Config.json": "TargetChest",
    "Config/Level/Props/Chap02/InitLevelGraph_Prop_Chap02_BlockProjection_01.json": "TargetChest",
    "Config/Level/Maze/MazeExtra/TramcarPuzzle/Maze_Puzzle_Tramcar_Controller.json": "UnlockChest",
}
JIGSAW = "Config/Level/Props/Chap03/InitLevelGraph_Prop_Chap03_JigsawPuzzle_"
ROBOT = "Config/Level/Maze/MazeExtra/CommandRobotPuzzle/"


def staged_rule(group, loc, read, tables, pin, used, rows):
    """Only compile explicit local actor bindings; stripped server targets are not guessed."""
    actors = {p["ID"]: p for p in group.get("PropList", [])}

    def graph(p):
        path = p.get("InitLevelGraph")
        if not path:
            config = tables["MazeProp"].get(p["PropID"], {}).get("JsonPath")
            raw = read(config) if config else None
            if raw:
                pin(config, used)
                path = raw.get("DefaultLevelGraphPath")
        return path

    def active(p):
        return p.get("LoadOnInitial", True) and not p.get("IsDelete") and not p.get("IsClientOnly")

    def bind(p, path):
        pin(path, used)
        rows.add(f"MazeProp:{p['PropID']}")

    def request(p, path, code, finish=False):
        raw = read(path)
        shared = values((raw or {}).get("ValueSource")) | values(p.get("ValueSource"))
        return [where for where, n in walk(raw)
                if n.get("$type") == "RPG.GameCore.PropReqInteract"
                and n.get("InteractID", {}).get("FixedValue", {}).get("Value") == code
                and not n.get("InteractID", {}).get("IsDynamic")
                and (not finish or "/FinishCallback/" in where)
                and target(n, p["ID"], loc["group"], shared) == (loc["group"], p["ID"])]

    group_path = group.get("LevelGraph")
    group_graph = read(group_path) if group_path else None
    for helper in actors.values():
        path = graph(helper)
        shared = values(helper.get("ValueSource"))
        if path == JIGSAW + "TreasureAppearSFXTrigger.json":
            if address(shared.get("TargetProp")) != (loc["group"], loc["instance"]):
                continue
            pair = address(shared.get("GameplayProp"))
            controller = actors.get(pair[1]) if pair and pair[0] == loc["group"] else None
            if not controller or not active(controller) or controller.get("State") == "Hidden":
                continue
            control_graph = graph(controller)
            if control_graph not in (JIGSAW + "JigsawSir.json", JIGSAW + "JigsawSir_02.json"):
                continue
            # The original conjunction identifies the pieces of THIS board. Initial Closed
            # pieces are authored as already present; TriggerDisable pieces must be collected.
            sets = set()
            for _, n in walk(group_graph):
                if n.get("$type") != "RPG.GameCore.ByAnd":
                    continue
                predicates = n.get("PredicateList", [])
                if not predicates or any(p.get("$type") != "RPG.GameCore.ByComparePropState"
                                         or p.get("Inverse") for p in predicates):
                    continue
                checks = {(target(p, None, loc["group"], {}), p.get("State", "Closed"))
                          for p in predicates}
                if ((loc["group"], controller["ID"]), "Open") not in checks:
                    continue
                pieces = {pair[1] for pair, state in checks if pair and pair[0] == loc["group"]
                          and pair[1] != controller["ID"] and state == "Closed"}
                if len(pieces) + 1 == len(checks):
                    sets.add(tuple(sorted(pieces)))
            if not sets and controller.get("State") == "TriggerEnable":
                # These boards start ready and contain only authored, already collected pieces.
                ready = [p for p in actors.values() if graph(p) == JIGSAW + "CollectablePiece.json"]
                if ready and all(active(p) and p.get("State", "Closed") == "Closed" for p in ready):
                    sets.add(tuple(sorted(p["ID"] for p in ready)))
            if len(sets) != 1:
                continue
            pieces = sets.pop()
            if not pieces or any(i not in actors or not active(actors[i])
                                 or graph(actors[i]) != JIGSAW + "CollectablePiece.json"
                                 or actors[i].get("State", "Closed") not in ("Closed", "TriggerDisable")
                                 or values(actors[i].get("ValueSource")).get("ControlByMission")
                                 or not request(actors[i], graph(actors[i]), 11000) for i in pieces):
                continue
            finish = 11090 if control_graph.endswith("JigsawSir_02.json") else 11000
            if finish == 11090 and any(actors[i].get("State", "Closed") != "Closed" for i in pieces):
                continue
            if not request(controller, control_graph, finish, True):
                continue
            interactions = [finish] if finish == 11090 else [1210, 1920, 19100, 11000]
            if any(not request(controller, control_graph, i) for i in interactions):
                continue
            bind(helper, path)
            bind(controller, control_graph)
            for i in pieces:
                bind(actors[i], graph(actors[i]))
            pin(group_path, used)
            rows.update(f"InteractConfig:{i}" for i in interactions)
            return dict(kind="JIGSAW", controller=controller["ID"],
                        controllerProp=controller["PropID"], finish=finish,
                        interactions=interactions, pieces=list(pieces),
                        evidence=[path + "#TargetProp+GameplayProp",
                                  group_path + "#ByAnd", control_graph + "#FinishCallback"])
        if path != ROBOT + "CommandRobotPuzzle_HideChestWaypointControl.json":
            continue
        if address(shared.get("Chest")) != (loc["group"], loc["instance"]):
            continue
        controllers = [p for p in actors.values() if active(p)
                       and graph(p) == ROBOT + "CommandRobotPuzzleUpgrade_Controller.json"]
        if len(controllers) != 1:
            continue
        controller = controllers[0]
        control_graph = graph(controller)
        if not request(controller, control_graph, 1010, True):
            continue
        # Require the exact nested server predicates (controller Open, this chest Hidden).
        bound = False
        for _, n in walk(group_graph):
            if n.get("$type") != "RPG.GameCore.PredicateTaskList":
                continue
            predicate = n.get("Predicate", {})
            if (predicate.get("$type") != "RPG.GameCore.ByComparePropState"
                    or predicate.get("Inverse") or predicate.get("State") != "Open"
                    or target(predicate, None, loc["group"], {}) != (loc["group"], controller["ID"])):
                continue
            success = n.get("SuccessTaskList", [])
            if len(success) != 1:
                continue
            child = success[0]
            predicate = child.get("Predicate", {})
            if (child.get("$type") == "RPG.GameCore.PredicateTaskList"
                    and predicate.get("$type") == "RPG.GameCore.ByComparePropState"
                    and not predicate.get("Inverse") and predicate.get("State") == "Hidden"
                    and target(predicate, None, loc["group"], {}) == (loc["group"], loc["instance"])
                    and child.get("SuccessTaskList") == [{"$type": "RPG.GameCore.ChangePropState"}]):
                bound = True
        if bound:
            bind(helper, path)
            bind(controller, control_graph)
            pin(group_path, used)
            rows.add("InteractConfig:1010")
            return dict(kind="CONTROLLER", controller=controller["ID"],
                        controllerProp=controller["PropID"], finish=1010,
                        interactions=[1010],
                        evidence=[group_path + "#controller-Open+chest-Hidden",
                                  path + "#Chest", control_graph + "#FinishCallback"])
    return None


def spear_rule(group, loc, read, tables, pin, used, rows):
    if group.get("GroupName") != "DestructableGoldenSpear":
        return None
    shields = [p for p in group.get("PropList", []) if p["PropID"] in (104010, 104011)]
    spears = [p for p in group.get("PropList", []) if p["PropID"] == 104023]
    chests = [p for p in group.get("PropList", []) if p.get("ChestID")]
    if len(shields) != 1 or len(spears) != 1 or len(chests) != 1 or chests[0]["ID"] != loc["instance"]:
        return None
    shield, spear = shields[0], spears[0]
    if any(p.get("IsDelete") or p.get("IsClientOnly") or not p.get("LoadOnInitial", True)
           or p.get("State", "Closed") != "Closed" for p in [shield, spear]):
        return None
    graph = spear.get("InitLevelGraph")
    if graph not in {f"Config/Level/Props/Chap04/InitLevelGraph_Prop_Chap04_GoldenSpear_{i:02}.json"
                     for i in range(1, 5)}:
        return None
    if address(values(spear.get("ValueSource")).get("AttachTargetRef")) != (loc["group"], shield["ID"]):
        return None
    group_graph = group.get("LevelGraph")
    raw_group = read(group_graph) if group_graph else None
    if not any(n.get("$type") == "RPG.GameCore.TriggerEntityEvent"
               and n.get("EventName", {}).get("Value") == "Clt_TriggerTryAttach"
               and n.get("InstanceID", {}).get("FixedValue", {}).get("Value") == spear["ID"]
               for _, n in walk(raw_group)):
        return None
    raw = read(graph)
    requests = [n for _, n in walk(raw) if n.get("$type") == "RPG.GameCore.PropReqInteract"]
    sequence, state = [], "Closed"
    for n in requests:
        config = n.get("InteractID", {}).get("FixedValue", {}).get("Value")
        row = tables["InteractConfig"].get(config, {})
        if (n.get("InteractID", {}).get("IsDynamic")
                or target(n, spear["ID"], loc["group"], {}) != (loc["group"], spear["ID"])
                or row.get("SrcState", "Closed") != state):
            return None
        state = row.get("TargetState", "Closed")
        sequence.append(config)
    if state != "Locked" or len(sequence) not in (4, 5, 6, 7):
        return None
    shield_graph = shield.get("InitLevelGraph")
    if not shield_graph:
        config = tables["MazeProp"][shield["PropID"]].get("JsonPath")
        shield_graph = (read(config) or {}).get("DefaultLevelGraphPath") if config else None
        if config:
            pin(config, used)
    for path in (graph, group_graph, shield_graph):
        if not path or read(path) is None:
            return None
        pin(path, used)
    rows.update([f"MazeProp:{shield['PropID']}", "MazeProp:104023"])
    rows.update(f"InteractConfig:{i}" for i in sequence)
    return dict(kind="SPEAR", controller=spear["ID"], controllerProp=spear["PropID"],
                shield=shield["ID"], shieldProp=shield["PropID"], finish=0, interactions=sequence,
                evidence=[group_graph + "#Clt_TriggerTryAttach+single-ChestID",
                          loc["path"] + "#AttachTargetRef", graph + "#PropReqInteract"])


def walk(value, path=""):
    if isinstance(value, dict):
        yield path, value
        for key, child in value.items():
            yield from walk(child, path + "/" + key)
    elif isinstance(value, list):
        for i, child in enumerate(value):
            yield from walk(child, path + "/" + str(i))


def values(value):
    return {v["Key"]: v.get("Value") for v in (value or {}).get("Values", [])}


def address(value):
    if isinstance(value, str):
        parts = value.split(",")
        if len(parts) == 2 and all(p.isdecimal() for p in parts):
            return tuple(map(int, parts))
    return None


def target(node, owner, group, shared):
    obj = node.get("TargetType", {})
    kind = obj.get("$type", "").split(".")[-1]
    if kind == "GroupFetchLocalTarget" and obj.get("TargetType") == "Prop":
        ids = obj.get("Targets", [])
        return (group, ids[0]) if len(ids) == 1 else None
    if kind != "TargetFetchAdvPropEx":
        return None
    fetch = obj.get("FetchType")
    if fetch is None:
        return (group, owner) if owner else None
    if fetch == "SinglePropByPropKey":
        key = obj.get("SinglePropKey", {})
        return address(shared.get(key.get("Key")) if key.get("Custom") else key.get("Value"))
    if fetch == "SinglePropByOwnerGroupAndID":
        ref = obj.get("PropIDInOwnerGroup", {})
        value = ref.get("FixedValue", {}).get("Value")
        return (group, value) if not ref.get("IsDynamic") and isinstance(value, int) and value > 0 else None
    if fetch == "SinglePropByPropID":
        pair = obj.get("SinglePropID", {})
        if any(pair.get(k, {}).get("IsDynamic") for k in ("GroupID", "ID")):
            return None
        return tuple(pair.get(k, {}).get("FixedValue", {}).get("Value", 0) for k in ("GroupID", "ID"))
    return None


def catalog(root):
    source = loads((ROOT / "src/main/resources/gameplay/map-chests-4.6.json").read_bytes())
    tables = {name: {r[key]: r for r in loads((root / f"ExcelOutput/{name}.json").read_bytes())}
              for name, key in [("MazeProp", "ID"), ("InteractConfig", "InteractID"),
                                ("NPCMonsterData", "ID"), ("ItemConfig", "ID")]}
    files, rows, rules, pending, aliases = {}, {}, {}, {}, {}
    cache = {}

    def read(path):
        if path not in cache:
            file = root / path
            cache[path] = loads(file.read_bytes()) if path and file.is_file() else None
        return cache[path]

    def pin(path, used):
        if read(path) is not None:
            files[path] = fingerprint(read(path))
            used.add(path)

    missions = mission_index(root, read)
    for key, loc in source["locations"].items():
        group = read(loc["path"])
        chest = next(p for p in group["PropList"] if p["ID"] == loc["instance"])
        if chest.get("State") not in ("Hidden", "ChestLocked"):
            continue
        used = {loc["path"]}
        files[loc["path"]] = source["files"][loc["path"]]
        row_keys = {f"MazeProp:{loc['prop']}"}
        rule = dict(kind="", initial=chest["State"], guards=[], controller=0,
                    controllerProp=0, finish=0, interactions=[], reports=[], evidence=[])
        if group.get("LevelGraph") == GUARD_GRAPH:
            monsters = [m for m in group.get("MonsterList", []) if not m.get("IsDelete")
                        and not m.get("IsClientOnly") and m.get("LoadOnInitial", True)]
            if monsters and all(not m.get("FarmElementID") for m in monsters):
                rule.update(kind="GUARDS", guards=sorted(m["ID"] for m in monsters))
                row_keys.update(f"NPCMonsterData:{m['NPCMonsterID']}" for m in monsters)
                pin(GUARD_GRAPH, used)
        # Completion of these boards reports a state transition on a controller.
        # The exact chest link is supplied by that authored controller, not every prop in its group.
        if not rule["kind"]:
            for prop in group.get("PropList", []):
                graph = prop.get("InitLevelGraph")
                config_path = tables["MazeProp"].get(prop["PropID"], {}).get("JsonPath")
                config = read(config_path) if config_path else None
                if not graph and config:
                    graph = config.get("DefaultLevelGraphPath")
                link = LINKED_GRAPHS.get(graph)
                if not link or address(values(prop.get("ValueSource")).get(link)) != (
                        loc["group"], loc["instance"]):
                    continue
                raw = read(graph)
                if raw is None:
                    continue
                shared = values(raw.get("ValueSource")) | values(prop.get("ValueSource"))
                calls = [(path, node) for path, node in walk(raw)
                         if node.get("$type") == "RPG.GameCore.PropReqInteract"
                         and target(node, prop["ID"], loc["group"], shared) == (loc["group"], prop["ID"])]
                finish = [(path, n) for path, n in calls if "/FinishCallback/" in path]
                # Tramcar's authored finish sequence waits for PuzzleEnd, then reports 1100.
                if graph.endswith("Maze_Puzzle_Tramcar_Controller.json"):
                    finish = [(path, n) for path, n in calls
                              if n.get("InteractID", {}).get("FixedValue", {}).get("Value") == 1100]
                ids = {n.get("InteractID", {}).get("FixedValue", {}).get("Value") for _, n in finish}
                if len(ids) != 1 or None in ids:
                    continue
                interactions = sorted({n["InteractID"]["FixedValue"]["Value"] for _, n in calls
                                       if not n.get("InteractID", {}).get("IsDynamic")
                                       and "FixedValue" in n.get("InteractID", {})})
                rule.update(kind="CONTROLLER", controller=prop["ID"], controllerProp=prop["PropID"],
                            finish=ids.pop(), interactions=interactions,
                            evidence=[graph + "#" + path for path, _ in finish])
                pin(graph, used)
                if not prop.get("InitLevelGraph") and config_path:
                    pin(config_path, used)
                row_keys.add(f"MazeProp:{prop['PropID']}")
                row_keys.update(f"InteractConfig:{i}" for i in interactions)
                # Some tramcar groups retain a disabled old controller followed by an
                # initially loaded replacement with the same exact chest/script binding.
                # Keep the old rule identity so existing completion receipts remain valid.
                replacements = [p["ID"] for p in group.get("PropList", [])
                                if p["ID"] != prop["ID"] and p.get("LoadOnInitial", True)
                                and not p.get("IsDelete") and not p.get("IsClientOnly")
                                and p["PropID"] == prop["PropID"]
                                and p.get("InitLevelGraph") == graph
                                and address(values(p.get("ValueSource")).get(link)) == (
                                    loc["group"], loc["instance"])]
                if replacements and not prop.get("LoadOnInitial", True):
                    aliases[key] = sorted(replacements)
                break
        # Explicit client unlock requests in the chest/prop/group scripts. This is a
        # completion report, not server-side replay of a local puzzle's move sequence.
        if not rule["kind"]:
            scripts = []
            graph = group.get("LevelGraph")
            if graph:
                scripts.append((graph, None, values(group.get("ValueSource"))))
            for prop in group.get("PropList", []):
                graph = prop.get("InitLevelGraph")
                definition = tables["MazeProp"].get(prop["PropID"], {})
                config_path = definition.get("JsonPath")
                config = read(config_path) if config_path else None
                if not graph and config:
                    graph = config.get("DefaultLevelGraphPath")
                if graph:
                    scripts.append((graph, prop["ID"], values(prop.get("ValueSource"))))
                    if config_path and config:
                        pin(config_path, used)
            for graph, owner, shared in scripts:
                raw = read(graph)
                if raw is None:
                    continue
                shared = values(raw.get("ValueSource")) | shared
                for path, node in walk(raw):
                    if node.get("$type") != "RPG.GameCore.PropReqInteract":
                        continue
                    interaction = node.get("InteractID", {})
                    code = interaction.get("FixedValue", {}).get("Value")
                    if interaction.get("IsDynamic") or code not in (111120, 120120):
                        continue
                    if target(node, owner, loc["group"], shared) != (loc["group"], loc["instance"]):
                        continue
                    expected = tables["InteractConfig"].get(code, {})
                    if expected.get("SrcState") != chest["State"] or expected.get("TargetState") != "ChestClosed":
                        continue
                    rule["reports"].append(code)
                    rule["evidence"].append(graph + "#" + path)
                    pin(graph, used)
            if rule["reports"]:
                rule.update(kind="CLIENT_REPORT", reports=sorted(set(rule["reports"])))
                row_keys.update(f"InteractConfig:{i}" for i in rule["reports"])
        if not rule["kind"]:
            staged = staged_rule(group, loc, read, tables, pin, used, row_keys)
            if staged:
                rule.update(staged)
        if not rule["kind"]:
            spear = spear_rule(group, loc, read, tables, pin, used, row_keys)
            if spear:
                rule.update(spear)
        if not rule["kind"]:
            extra = extend(group, loc, root, read, tables, pin, used, row_keys,
                           sys.modules[__name__], missions)
            if extra:
                rule.update(extra)
        if not rule["kind"]:
            extra = compass_rule(group, loc, read, tables, pin, used, row_keys,
                                 sys.modules[__name__], missions)
            if extra:
                rule.update(extra)
        if not rule["kind"]:
            pending[key] = dict(state=chest["State"], graph=group.get("LevelGraph"),
                                reason="No complete supported server predicate or exact client unlock binding")
            continue
        rule["files"] = sorted(used)
        rule["rows"] = sorted(row_keys)
        for name in row_keys:
            table, id_ = name.split(":")
            rows[name] = fingerprint(tables[table][int(id_)])
        rules[key] = rule
    extra = loads((ROOT / 'src/main/resources/gameplay/mascot-extension-4.6.json').read_bytes())
    key = str(extra['control']['chest'])
    assert all(fingerprint(read(path)) == digest for path, digest in extra['files'].items())
    assert key in pending and source['locations'][key] == extra['location']
    del pending[key]
    owners={key: 'mascot-extension-4.6.json'}
    compatible=loads((ROOT/'src/main/resources/gameplay/compatible-mechanisms-4.6.json').read_bytes())
    assert compatible['sourceCommit']==source['sourceCommit'] and compatible['policyVersion']==1
    assert all(fingerprint(read(path))==digest for path,digest in compatible['files'].items())
    compatibility_ids={chest for flow in compatible['flows'].values() for chest in flow['rewards']}
    assert len(compatibility_ids)==37 and compatibility_ids==set(pending), 'Compatibility scope must cover exactly the last 37 gaps'
    for chest in sorted(compatibility_ids):
        owners[chest]='compatible-mechanisms-4.6.json'
        del pending[chest]
    return dict(sourceCommit=source["sourceCommit"], rules=rules, files=dict(sorted(files.items())),
                rows=dict(sorted(rows.items())), pending=pending, controllerAliases=aliases,
                independentOwners=owners)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--resources", type=Path, default=ROOT / "resources/4.6")
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()
    result = catalog(args.resources.resolve())
    content = json.dumps(result, ensure_ascii=False, indent=2) + "\n"
    if args.check:
        assert OUTPUT.read_text("utf-8") == content, "Mechanism catalog differs from fixed source"
    else:
        OUTPUT.write_text(content, encoding="utf-8")
    fixture = dict(records={}, groups={}, floors={}, locations={}, headers={}, missions={})
    for mission in (2010401, 2010402, 2010405, 2000302, 2000303, 2000304, 2011409, 4016201,
                    8015163, 4015101, 4015102, 4015103, 4040223, 4015202, 4015203, 4015204,
                    2010503, 4015301, 4015302, 4015303):
        path = f"Config/Level/Mission/{mission}/MissionInfo_{mission}.partial.json"
        fixture["missions"][str(mission)] = loads((args.resources / path).read_bytes())
    locations = loads((ROOT / "src/main/resources/gameplay/map-chests-4.6.json").read_bytes())["locations"]
    selected_rows = set()
    sample_ids = {20001632, 20111656, 20211656, 10301656, 10401651, 10403660, 20241651}
    sample_ids.update(int(k) for k, r in result["rules"].items()
                      if r["kind"] in ("JIGSAW", "SPEAR")
                      or r["kind"] == "CONTROLLER"
                      and any("CommandRobotPuzzle" in p for p in r["files"]))
    sample_ids.update(map(int, result["controllerAliases"]))
    sample_ids.update([20123655, 20223651, 20223652, 20223653, 20241665])
    sample_ids.update(int(k) for k, r in result["rules"].items() if "controllerPath" in r)
    sample_ids.update(int(k) for k, r in result["rules"].items() if "mission" in r)
    for id_ in sorted(sample_ids):
        key = str(id_)
        loc = locations[key]
        rule = result["rules"][key]
        group = loads((args.resources / loc["path"]).read_bytes())
        fixture["groups"][loc["path"]] = group
        floor = f"Config/LevelOutput/RuntimeFloor/P{loc['plane']}_F{loc['floor']}.json"
        fixture["floors"][floor] = loads((args.resources / floor).read_bytes())
        entrances = loads((args.resources / "ExcelOutput/MapEntrance.json").read_bytes())
        entry = next(e["ID"] for e in entrances if e["PlaneID"] == loc["plane"] and e["FloorID"] == loc["floor"])
        planes = loads((args.resources / "ExcelOutput/MazePlane.json").read_bytes())
        world = next(p["WorldID"] for p in planes if p["PlaneID"] == loc["plane"])
        fixture["locations"][key] = dict(loc, entry=entry, world=world)
        selected_rows.update(rule["rows"])
        selected_rows.update([f"MazeChest:{id_}", f"MazePlane:{loc['plane']}", f"MapEntrance:{entry}"])
        selected_rows.update(f"MazeProp:{p['PropID']}" for p in group.get("PropList", []))
        for path in rule["files"]:
            if path != loc["path"]:
                fixture["headers"][path] = loads((args.resources / path).read_bytes())
        if rule.get("controllerPath"):
            path = rule["controllerPath"]
            other = loads((args.resources / path).read_bytes())
            fixture["groups"][path] = other
            selected_rows.update(f"MazeProp:{p['PropID']}" for p in other.get("PropList", []))
    for table in sorted({r.split(":")[0] for r in selected_rows}):
        id_key = {"MazePlane": "PlaneID", "InteractConfig": "InteractID"}.get(table, "ID")
        original = loads((args.resources / f"ExcelOutput/{table}.json").read_bytes())
        fixture["records"][f"ExcelOutput/{table}.json"] = [
            r for r in original if f"{table}:{r[id_key]}" in selected_rows]
    from source_json import canonical
    encoded_fixture = canonical(fixture) + "\n"
    if args.check:
        assert FIXTURE.read_text("utf-8") == encoded_fixture, "Mechanism fixture differs from source"
    else:
        FIXTURE.write_text(encoded_fixture, encoding="utf-8")
    print(json.dumps(dict(kinds=Counter(r["kind"] for r in result["rules"].values()),
                         pending=len(result["pending"]), files=len(result["files"]))))


if __name__ == "__main__":
    main()
