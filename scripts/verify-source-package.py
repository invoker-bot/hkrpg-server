"""核验源码交付集合与检出后的协议字节；可导出独立构建目录。"""
import argparse
import hashlib
import json
import subprocess
import uuid
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
REQUIRED = [
    "src/main/resources/logback.xml",
    "src/main/resources/features.json",
    "src/main/resources/feature-backlog.json",
    "src/main/resources/defaults/Banners.json",
    "src/main/resources/defaults/ActivityScheduling.json",
    "src/main/resources/defaults/RogueMapGen.json",
    "src/main/resources/defaults/RogueMapGenByMap.json",
    "src/main/resources/defaults/RogueBossGen.json",
    "src/main/resources/defaults/Gameplay46.json",
    "src/main/java/org/hkrpg/server/gameplay/GameplayState.java",
    "src/main/resources/protocol/4.2/descriptor.pb",
    "src/main/resources/protocol/4.6/descriptor.pb",
    "src/main/java/emu/lunarcore/data/GameData.java",
    "src/main/java/emu/lunarcore/data/ResourceLoader.java",
]


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--export", action="store_true", help="导出仅包含 Git 交付文件的独立目录")
    args = parser.parse_args()
    listed = subprocess.check_output(
        ["git", "ls-files", "--cached", "--others", "--exclude-standard", "-z"], cwd=ROOT)
    removed = subprocess.check_output(["git", "ls-files", "--deleted", "-z"], cwd=ROOT)
    deleted = set(removed.decode("utf-8").split("\0"))
    files = sorted(set(name for name in listed.decode("utf-8").split("\0")
                       if name and name not in deleted))
    paths = set(files)
    missing = [name for name in REQUIRED if name not in paths or not (ROOT / name).is_file()]
    for file in (ROOT / "src").rglob("*.java"):
        if file.relative_to(ROOT).as_posix() not in paths:
            missing.append(file.relative_to(ROOT).as_posix())
    for file in (ROOT / "src/main/resources").rglob("*"):
        if file.is_file() and file.relative_to(ROOT).as_posix() not in paths:
            missing.append(file.relative_to(ROOT).as_posix())
    if missing:
        raise SystemExit("工程源码被忽略或缺失: " + ", ".join(sorted(set(missing))))
    attributes = subprocess.check_output(["git", "check-attr", "-z", "--stdin", "text", "eol"],
        input=("\0".join(files) + "\0").encode("utf-8"), cwd=ROOT).decode("utf-8").split("\0")
    rules = {}
    for index in range(0, len(attributes) - 1, 3):
        name, attribute, value = attributes[index:index + 3]
        rules.setdefault(name, {})[attribute] = value

    def checkout_bytes(name):
        raw = (ROOT / name).read_bytes()
        rule = rules.get(name, {})
        if rule.get("text") == "unset" or b"\0" in raw:
            return raw
        normalized = raw.replace(b"\r\n", b"\n")
        return normalized.replace(b"\n", b"\r\n") if rule.get("eol") == "crlf" else normalized

    verified = 0
    for version in ["4.2", "4.6"]:
        base = f"src/main/resources/protocol/{version}"
        manifest = json.loads((ROOT / base / "checksums.json").read_text(encoding="utf-8"))
        for name, expected in manifest.items():
            source = f"{base}/{name}" if name.endswith((".pb", ".json")) else f"protocols/{version}/{name}"
            if source not in paths or hashlib.sha256(checkout_bytes(source)).hexdigest() != expected:
                raise SystemExit(f"Git 检出后协议快照丢失或字节变化: {source}")
            verified += 1

    report = {"passed": True, "sourceFiles": len(files), "protocolFilesVerifiedAfterCheckout": verified}
    if args.export:
        output = ROOT / "build" / f"source-export-{uuid.uuid4().hex[:8]}"
        output.mkdir(parents=True, exist_ok=False)
        for name in files:
            target = output / name
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(checkout_bytes(name))
        report["exportDirectory"] = str(output)
    (ROOT / "build").mkdir(exist_ok=True)
    (ROOT / "build/source-package-report.json").write_text(
        json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8", newline="\n")
    if args.export:
        (ROOT / "build/source-package-export-report.json").write_text(
            json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8", newline="\n")
    print(json.dumps(report, ensure_ascii=False))


if __name__ == "__main__":
    main()
