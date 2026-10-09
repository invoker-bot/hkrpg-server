"""安装固定提交的 JSON 资源。已有不同内容的文件一律拒绝覆盖。"""
import argparse
import hashlib
import json
import re
import shutil
import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
EXPECTED_COMMIT = "724b139d8c9c32d12552eb95745a4fee72bfe48b"


def digest(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def install(source, destination):
    commit = subprocess.check_output(["git", "-C", str(source), "rev-parse", "HEAD"], text=True).strip()
    if commit != EXPECTED_COMMIT:
        raise RuntimeError("资源提交不符合锁定的 4.6.0 快照")
    label = subprocess.check_output(["git", "-C", str(source), "show", "-s", "--format=%s", "HEAD"], text=True).strip()
    if not re.match(r"OSPRODWin4\.6\.0_", label):
        raise RuntimeError("资源快照未声明 4.6.0 正式版")
    folders = ["ExcelOutput", "Config/LevelOutput/RuntimeFloor", "Config/LevelOutput/SharedRuntimeGroup", "Config/LevelOutput/RuntimeGroup",
               "Config/ConfigAdventureAbility", "Config/ConfigAdventureModifier",
               "Config/ConfigSummonUnit", "Config/ConfigMazeBuff", "Config/ConfigEntity/SummonUnit",
               "Config/Level/Mission",
               "Config/Level/Rogue", "Config/Level/RogueDialogue", "Config/Level/LittleGame",
               "Config/Activity", "Config/BattleLineupPage/SkillTreePointPreset", "TextMap"]
    files = sorted(path for folder in folders for path in (source / folder).rglob("*.json"))
    if not files: raise RuntimeError("尚未下载资源文件")
    entries = {path.relative_to(source).as_posix(): digest(path) for path in files}
    manifest = {"clientVersion": "4.6.0", "source": "https://github.com/DimbreathBot/TurnBasedGameData",
                "sourceCommit": commit, "resourceLabel": label, "sourceRegion": "OS", "localClientRegion": "CN",
                "clientAcceptance": False, "files": entries}
    encoded = json.dumps(manifest, ensure_ascii=False, indent=2) + "\n"
    # Complete the conflict audit before copying any files.
    for relative, expected in entries.items():
        target = destination / relative
        if target.exists() and (not target.is_file() or digest(target) != expected):
            raise RuntimeError(f"拒绝覆盖已有资源: {target}")
    manifest_path = destination / "resource-manifest.json"
    if manifest_path.exists() and manifest_path.read_text(encoding="utf-8") != encoded:
        existing = json.loads(manifest_path.read_text(encoding="utf-8"))
        if existing.get("sourceCommit") != commit or existing.get("clientVersion") != "4.6.0" or any(
                entries.get(name) != checksum for name, checksum in existing.get("files", {}).items()):
            raise RuntimeError(f"拒绝覆盖已有清单: {manifest_path}")
        # An additive install gets its own manifest; the original remains byte-for-byte intact.
        manifest_path = destination / ("resource-supplement-" + hashlib.sha256(encoded.encode()).hexdigest()[:12] + ".json")
        if manifest_path.exists() and manifest_path.read_text(encoding="utf-8") != encoded:
            raise RuntimeError(f"拒绝覆盖已有补充清单: {manifest_path}")
    installed = 0
    for path in files:
        target = destination / path.relative_to(source)
        if target.exists(): continue
        target.parent.mkdir(parents=True, exist_ok=True)
        with path.open("rb") as incoming, target.open("xb") as outgoing:
            shutil.copyfileobj(incoming, outgoing)
        installed += 1
    if not manifest_path.exists():
        with manifest_path.open("x", encoding="utf-8", newline="\n") as output:
            output.write(encoded)
    return {"installed": installed, "verifiedFiles": len(entries), "destination": str(destination),
            "sourceCommit": commit, "clientAcceptance": False}


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--source", type=Path, default=ROOT / "references/TurnBasedGameData")
    parser.add_argument("--destination", type=Path, default=ROOT / "resources/4.6")
    args = parser.parse_args()
    print(json.dumps(install(args.source.resolve(), args.destination.resolve()), ensure_ascii=False, indent=2))
