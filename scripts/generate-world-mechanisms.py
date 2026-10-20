"""从固定 4.6 原文提取守卫与客户端完成回执；不猜测被剥离的服务端脚本字段。"""
import argparse
from collections import Counter
import json
from pathlib import Path

from source_json import loads, fingerprint

ROOT = Path(__file__).resolve().parents[1]
OUTPUT = ROOT / "src/main/resources/gameplay/world-mechanisms-4.6.json"
FIXTURE = ROOT / "src/test/resources/fixtures/world-mechanisms-4.6.json"
GUARD_GRAPH = "Config/Level/Maze/MazeGroup/Maze_Group_OpenTreasure_WhenMonsterDie.json"
LINKED_GRAPHS = {
    "Config/Level/Props/Chap01/InitLevelGraph_Prop_Chap01_BoxMan_01.json": "ChestID",
    "Config/Level/Props/Chap02/InitLevelGraph_Puzzle_Chap02_RubikCube_Config.json": "TargetChest",
    "Config/Level/Props/Chap02/InitLevelGraph_Puzzle_Chap02_RubikCube_Advanced_Config.json": "TargetChest",
    "Config/Level/Props/Chap02/InitLevelGraph_Prop_Chap02_BlockProjection_01.json": "TargetChest",
    "Config/Level/Maze/MazeExtra/TramcarPuzzle/Maze_Puzzle_Tramcar_Controller.json": "UnlockChest",
}


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
                                ("NPCMonsterData", "ID")]}
    files, rows, rules, pending = {}, {}, {}, {}
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
            pending[key] = dict(state=chest["State"], graph=group.get("LevelGraph"),
                                reason="No complete supported server predicate or exact client unlock binding")
            continue
        rule["files"] = sorted(used)
        rule["rows"] = sorted(row_keys)
        for name in row_keys:
            table, id_ = name.split(":")
            rows[name] = fingerprint(tables[table][int(id_)])
        rules[key] = rule
    return dict(sourceCommit=source["sourceCommit"], rules=rules, files=dict(sorted(files.items())),
                rows=dict(sorted(rows.items())), pending=pending)


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
    fixture = dict(records={}, groups={}, floors={}, locations={}, headers={})
    locations = loads((ROOT / "src/main/resources/gameplay/map-chests-4.6.json").read_bytes())["locations"]
    selected_rows = set()
    for id_ in (20001632, 20111656, 20211656, 10301656, 10401651, 10403660, 20241651):
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
