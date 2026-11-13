"""盘点原始 SimpleDoor 控制台及其同维度目标；静态结果不代表客户端已验收。"""

import argparse
from collections import Counter
import json
from pathlib import Path
import re
import shutil
import subprocess

ROOT = Path(__file__).resolve().parents[1]
RESOURCES = ROOT / "resources/4.6"
GROUPS = RESOURCES / "Config/LevelOutput/SharedRuntimeGroup"
FLOORS = RESOURCES / "Config/LevelOutput/RuntimeFloor"
GRAPH = "InitLevelGraph_Prop_Chap01_BaseConsole_01A_Control_SimpleDoor.json"
GATE_GRAPH = "InitLevelGraph_Prop_Chap01_IronGate_06.json"
OWNER_KEY = "Maze_Prop_Chap01_BaseControllSimpleDoor"
GATE_KEY = "Maze_Prop_Chap01_IronGate06Controller_ConfigID"


def read(path):
    return json.loads(path.read_text(encoding="utf-8"))


def source_files():
    rg = shutil.which("rg")
    if rg:
        result = subprocess.run([rg, "-l", "-F", GRAPH, str(GROUPS)], capture_output=True,
                                text=True, encoding="utf-8", check=True)
        return sorted(Path(line) for line in result.stdout.splitlines())
    return sorted(path for path in GROUPS.rglob("*.json") if GRAPH in path.read_text(encoding="utf-8"))


def binding(prop, key):
    source = prop.get("ValueSource") or {}
    values = [v for v in source.get("Values", []) if v.get("Key") == key]
    return values[0].get("Value") if len(values) == 1 else None


def scan():
    files = source_files()
    candidates = {path.relative_to(RESOURCES).as_posix(): read(path) for path in files}
    cache = dict(candidates)
    planes = {row["PlaneID"]: row for row in read(RESOURCES / "ExcelOutput/MazePlane.json")}
    rows = []
    for floor_path in sorted(FLOORS.glob("P*_F*.json")):
        match = re.fullmatch(r"P(\d+)_F(\d+)\.json", floor_path.name)
        if not match:
            continue
        plane, floor = map(int, match.groups())
        floor_data = read(floor_path)
        instances = floor_data.get("GroupInstanceList") or []
        for dimension in floor_data.get("DimensionList") or []:
            groups = {}
            for index in dimension.get("GroupIndexList") or []:
                if not isinstance(index, int) or index < 0 or index >= len(instances):
                    raise ValueError("Invalid floor group index: " + str(floor_path))
                entry = instances[index]
                path, group_id = entry.get("GroupPath"), entry.get("ID")
                if path and group_id:
                    groups.setdefault(group_id, set()).add(path)
            for group_id, paths in sorted(groups.items()):
                for path in sorted(paths):
                    if path not in candidates:
                        continue
                    group = candidates[path]
                    for owner in group.get("PropList") or []:
                        if not owner.get("InitLevelGraph", "").endswith(GRAPH):
                            continue
                        value = binding(owner, OWNER_KEY)
                        row = dict(plane=plane, floor=floor, planeType=planes.get(plane, {}).get("PlaneType"),
                                   dimension=dimension.get("ID", 0), group=group_id,
                                   controller=owner.get("ID"), controllerProp=owner.get("PropID"),
                                   controllerState=owner.get("State", "Closed"),
                                   controllerInitial=owner.get("LoadOnInitial", True),
                                   controllerDeleted=owner.get("IsDelete", False),
                                   controllerClientOnly=owner.get("IsClientOnly", False),
                                   groupInitial=group.get("LoadOnInitial", True),
                                   groupSide=group.get("LoadSide", "Server"),
                                   groupPath=path, target=value)
                        parts = value.split(",") if isinstance(value, str) else []
                        if len(parts) != 2 or any(not part.isdecimal() or int(part) <= 0 for part in parts):
                            row["shape"] = "missing-or-malformed-binding"
                            rows.append(row)
                            continue
                        target_group, target_id = map(int, parts)
                        matches = []
                        for target_path in sorted(groups.get(target_group, set())):
                            target_file = RESOURCES / target_path
                            if not target_file.is_file():
                                continue
                            if target_path not in cache:
                                cache[target_path] = read(target_file)
                            matches.extend((target_path, prop) for prop in cache[target_path].get("PropList") or []
                                           if prop.get("ID") == target_id)
                        if len(matches) != 1:
                            row.update(shape="target-missing-or-ambiguous", targetMatches=len(matches))
                            rows.append(row)
                            continue
                        target_path, target = matches[0]
                        reverse = binding(target, GATE_KEY)
                        graph = target.get("InitLevelGraph", "")
                        paired = (owner.get("PropID") == 101001 and target.get("PropID") == 101004
                                  and graph.endswith(GATE_GRAPH)
                                  and reverse == f"{group_id},{owner.get('ID')}")
                        row.update(targetProp=target.get("PropID"), targetState=target.get("State", "Closed"),
                                   targetGraph=graph, targetGroupPath=target_path, backref=reverse,
                                   targetInitial=target.get("LoadOnInitial", True),
                                   targetDeleted=target.get("IsDelete", False),
                                   targetClientOnly=target.get("IsClientOnly", False),
                                   targetGroupInitial=cache[target_path].get("LoadOnInitial", True),
                                   shape="standard-pair" if paired else "other-model-or-binding")
                        rows.append(row)
    counts = Counter((r["planeType"], r["shape"]) for r in rows)
    return dict(sourceFiles=len(files), placements=len(rows),
                counts=[dict(planeType=plane, shape=shape, count=count)
                        for (plane, shape), count in sorted(counts.items(), key=lambda item: str(item[0]))],
                rows=rows)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    result = scan()
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({key: result[key] for key in ("sourceFiles", "placements", "counts")}, ensure_ascii=False))


if __name__ == "__main__":
    main()
