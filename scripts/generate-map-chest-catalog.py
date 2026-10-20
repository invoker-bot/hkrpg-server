"""从完整 4.6 资源生成宝箱来源目录；不修改运营资源。"""
import argparse
from collections import defaultdict
import json
from pathlib import Path
import re

from source_json import loads, fingerprint

ROOT = Path(__file__).resolve().parents[1]
SOURCE_COMMIT = "724b139d8c9c32d12552eb95745a4fee72bfe48b"
OUTPUT = ROOT / "src/main/resources/gameplay/map-chests-4.6.json"


def catalog(root):
    def table(name, key):
        rows = loads((root / f"ExcelOutput/{name}.json").read_bytes())
        result = {row[key]: row for row in rows}
        assert len(result) == len(rows), f"Duplicate {name} identity"
        return result

    chests = table("MazeChest", "ID")
    props = table("MazeProp", "ID")
    planes = table("MazePlane", "PlaneID")
    entrances = table("MapEntrance", "ID")
    items = table("ItemConfig", "ID")
    bindings = defaultdict(set)
    for path in sorted((root / "Config/LevelOutput/RuntimeFloor").glob("*.json")):
        plane, floor = map(int, re.fullmatch(r"P(\d+)_F(\d+)", path.stem).groups())
        raw = loads(path.read_bytes())
        refs = raw.get("GroupInstanceList", [])
        for dimension in raw.get("DimensionList", []):
            for index in dimension.get("GroupIndexList", []):
                assert 0 <= index < len(refs), path
                ref = refs[index]
                relative = ref.get("GroupPath")
                if relative:
                    bindings[relative].add((plane, floor, ref["ID"], dimension.get("ID", 0)))
    locations, rows, files = {}, {}, {}
    used_planes, used_floors, used_props = set(), set(), set()
    for relative, origins in sorted(bindings.items()):
        path = (root / relative).resolve()
        assert path.is_relative_to(root.resolve()), relative
        if not path.is_file():
            continue  # Only resources actually present can be registered.
        raw = loads(path.read_bytes())
        if raw.get("LoadSide", "Server") != "Server":
            continue
        for prop in raw.get("PropList", []):
            chest = prop.get("ChestID", 0)
            excel = props.get(prop.get("PropID"), {})
            if (chest not in chests or excel.get("PropType") != "PROP_TREASURE_CHEST"
                    or prop.get("IsDelete") or prop.get("IsClientOnly")):
                continue
            scopes = {(p, f, g) for p, f, g, _ in origins}
            assert len(scopes) == 1, f"Ambiguous chest scope: {chest}"
            plane, floor, group = next(iter(scopes))
            if planes[plane].get("PlaneType") not in ("Town", "Maze"):
                continue  # Instance activities own their separate loot and progression.
            assert chest not in locations, f"Duplicate chest placement: {chest}"
            assert planes[plane]["WorldID"] == chests[chest]["WorldID"], chest
            locations[chest] = dict(
                plane=plane, floor=floor, group=group, instance=prop["ID"],
                prop=prop["PropID"], path=relative,
                dimensions=sorted(d for p, f, g, d in origins),
            )
            rows[f"MazeChest:{chest}"] = fingerprint(chests[chest])
            files[relative] = fingerprint(raw)
            used_planes.add(plane)
            used_floors.add((plane, floor))
            used_props.add(prop["PropID"])
    for name, values, selected in (
        ("MazeProp", props, used_props),
        ("MazePlane", planes, used_planes),
        ("MapEntrance", entrances, {key for key, row in entrances.items()
                                   if (row["PlaneID"], row["FloorID"]) in used_floors}),
        ("ItemConfig", items, {1, 2, 22, 212, 232, *range(120000, 120007)}),
    ):
        for key in selected:
            rows[f"{name}:{key}"] = fingerprint(values[key])
    return dict(sourceCommit=SOURCE_COMMIT, locations=dict(sorted(locations.items())),
                rows=dict(sorted(rows.items())), files=dict(sorted(files.items())))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--resources", type=Path, default=ROOT / "resources/4.6")
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()
    result = catalog(args.resources.resolve())
    content = json.dumps(result, ensure_ascii=False, indent=2) + "\n"
    if args.check:
        assert OUTPUT.read_text("utf-8") == content, "Chest catalog differs from source"
    else:
        OUTPUT.parent.mkdir(parents=True, exist_ok=True)
        OUTPUT.write_text(content, encoding="utf-8")
    print(json.dumps(dict(chests=len(result["locations"]), groups=len(result["files"]),
                          sourceRows=len(result["rows"]), checked=args.check)))


if __name__ == "__main__":
    main()
