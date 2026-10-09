"""只读客户端版本与公开文件元信息，不执行程序、不读取登录缓存。"""
import argparse
import configparser
import hashlib
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def inspect(directory):
    parser = configparser.ConfigParser(interpolation=None)
    parser.read(directory / "config.ini", encoding="utf-8-sig")
    general = parser["General"]
    report = {"directory": str(directory), "gameVersion": general.get("game_version"),
              "gameBiz": general.get("game_biz"), "channel": general.get("channel"), "files": [],
              "clientExecutableStarted": False, "clientModified": False, "clientAcceptance": False}
    paths = ["StarRail.exe", "GameAssembly.dll", "StarRail_Data/il2cpp_data/Metadata/global-metadata.dat",
             "StarRail_Data/StreamingAssets/RawRes/Windows/RawConfig/ConfigDataPoolManifest.dat",
             "StarRail_Data/StreamingAssets/RawRes/Windows/RawConfig/ConfigDataPoolManifest.hash"]
    for name in paths:
        path = directory / name
        record = {"path": name, "present": path.is_file()}
        if path.is_file():
            record["size"] = path.stat().st_size
            if path.stat().st_size < 1024 * 1024:
                record["sha256"] = hashlib.sha256(path.read_bytes()).hexdigest()
        report["files"].append(record)
    return report


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--client-dir", type=Path, required=True)
    parser.add_argument("--output", type=Path, default=ROOT / "build/client-report.json")
    args = parser.parse_args()
    result = inspect(args.client_dir.resolve())
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(result, ensure_ascii=False, indent=2))
