"""核验打包协议描述符和来源定义，构建时无需下载或运行参考仓库。"""
import hashlib
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
for version in ["4.2", "4.6"]:
    output = ROOT / "src/main/resources/protocol" / version
    entries = json.loads((output / "checksums.json").read_text(encoding="utf-8"))
    for name, expected in entries.items():
        path = output / name if name.endswith((".pb", ".json")) else ROOT / "protocols" / version / name
        actual = hashlib.sha256(path.read_bytes()).hexdigest()
        if actual != expected: raise SystemExit(f"协议快照校验不符: {path}")
print("Verified frozen 4.2 and 4.6 protocol definitions/descriptors")
