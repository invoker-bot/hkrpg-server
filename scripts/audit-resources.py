"""检查本地资源的文件校验、地图引用与缺口，不宣称客户端兼容。"""
import argparse
import hashlib
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def audit(directory):
    manifest_files = [directory / "resource-manifest.json", *directory.glob("resource-supplement-*.json")]
    entries = {}
    versions = set()
    for path in manifest_files:
        manifest = json.loads(path.read_text(encoding="utf-8"))
        versions.add(manifest["clientVersion"])
        for name, value in manifest["files"].items():
            if name in entries and entries[name] != value: raise RuntimeError(f"清单冲突: {name}")
            entries[name] = value
    failures = []
    for name, expected in entries.items():
        path = directory / name
        if not path.is_file(): failures.append(name + ":missing")
        else:
            with path.open("rb") as stream:
                if hashlib.file_digest(stream, "sha256").hexdigest() != expected: failures.append(name + ":checksum")
    floors = list((directory / "Config/LevelOutput/RuntimeFloor").glob("*.json"))
    missing = set()
    for floor in floors:
        data = json.loads(floor.read_text(encoding="utf-8"))
        for group in data.get("GroupInstanceList", []):
            if group.get("IsDelete"): continue
            name = group["GroupPath"]
            path = directory / name
            fallback = directory / name.replace("/RuntimeGroup/", "/SharedRuntimeGroup/")
            if not path.is_file() and not fallback.is_file(): missing.add(name)
    return {"versions": sorted(versions), "verifiedFiles": len(entries), "checksumFailures": failures,
            "runtimeFloors": len(floors), "missingReferencedGroups": sorted(missing), "clientAccepted": False}


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--resources", type=Path, default=ROOT / "resources/4.6")
    parser.add_argument("--output", type=Path, default=ROOT / "build/resource-audit.json")
    args = parser.parse_args()
    report = audit(args.resources.resolve())
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({key: len(value) if key == "missingReferencedGroups" else value for key, value in report.items()}, indent=2))
    if report["checksumFailures"]: raise SystemExit(1)
