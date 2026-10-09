"""Record pinned source provenance and a name-based handler comparison (no runtime claims)."""
import collections
import json
import pathlib
import re
import subprocess

ROOT = pathlib.Path(__file__).resolve().parents[1]
PROJECTS = [
    ("March7thHoney-OpenSource", "https://github.com/Mar7thLover/March7thHoney-OpenSource", "C#", "4.6"),
    ("pearl-sr", "https://git.xeondev.com/HonkaiSlopRail/pearl-sr", "Zig", "4.6.51 / 4.7 beta"),
    ("echium-server", "https://github.com/yuvlian/echium-server", "Python", "4.5.52"),
    ("LunarCore", "https://github.com/Melledy/LunarCore", "Java", "4.2.0 (lite)"),
    ("HyacineDH-Core", "https://github.com/Mar7thLover/HyacineDH-Core", "C#", "4.0"),
    ("hkrpg-go", "https://github.com/gucooing/hkrpg-go", "Go", "3.0.0"),
    ("himeko-nova-sr", "https://git.xeondev.com/HonkaiSlopRail/himeko-nova-sr", "Zig", "4.4.53 / 4.5 beta"),
    ("evanescia-sr", "https://git.xeondev.com/HonkaiSlopRail/evanescia-sr", "Zig", "4.2.54 / 4.3 beta"),
    ("LunarCore-development", "https://github.com/Melledy/LunarCore/tree/development", "Java", "2.5.0"),
]

def git(directory, *args):
    return subprocess.check_output(["git", "-C", str(directory), *args], text=True, encoding="utf-8").strip()

def sources(directory):
    return [directory / path for path in git(directory, "ls-files").splitlines()]

def handler_names(directory, files):
    handlers = {}
    go_definitions = collections.defaultdict(list)
    go_routes = []
    for path in files:
        if path.suffix not in {".java", ".cs", ".py", ".zig", ".go"} or not path.is_file():
            continue
        relative = path.relative_to(directory).as_posix()
        if any(part.lower() in {"generated", "proto", "protocol"} for part in path.relative_to(directory).parts):
            continue
        text = path.read_text(encoding="utf-8-sig", errors="replace")
        names = []
        if path.suffix in {".java", ".cs"}:
            names = re.findall(r"@Opcodes\(CmdId\.(\w+)|\[Opcode\(CmdIds\.(\w+)", text)
            names = [a or b for a, b in names]
        elif path.suffix == ".zig":
            names = [n + "CsReq" for n in re.findall(r"pub fn on(\w+)\(", text)]
        elif path.suffix == ".py":
            names = ["".join(p.capitalize() for p in n.split("_")) + "CsReq" for n in
                     re.findall(r"@handler\s+async def on_(\w+)\(", text)]
        elif path.suffix == ".go":
            # Request-sending robot methods are not server handlers. Resolve the explicit
            # command map to its implementation, including free functions and Handle prefixes.
            if "robot" in {part.lower() for part in path.relative_to(directory).parts}:
                continue
            code = re.sub(r'`[^`]*`|"(?:\\.|[^"\\])*"|//[^\r\n]*|/\*[\s\S]*?\*/',
                          lambda m: "\n" * m.group().count("\n"), text)
            for function in re.findall(r"(?m)^\s*func\s+(?:\([^\r\n)]*\)\s*)?(\w+)\s*\(", code):
                go_definitions[function].append(relative)
            go_routes.extend((command, function, relative) for command, function in
                re.findall(r"(?m)^\s*cmd\.(\w+CsReq)\s*:\s*(\w+)\s*,", code))
        for name in names:
            handlers.setdefault(name, []).append(relative)
    for command, function, relative in go_routes:
        paths = handlers.setdefault(command, [])
        for source in [relative, *go_definitions.get(function, [])]:
            if source not in paths:
                paths.append(source)
    return handlers

def main():
    rows = []
    for name, url, language, version in PROJECTS:
        directory = ROOT / "references" / name
        files = sources(directory)
        licenses = [p for p in files if p.name.upper().startswith("LICENSE") and p.is_file()]
        license_text = licenses[0].read_text(encoding="utf-8", errors="replace") if licenses else ""
        license_id = "AGPL-3.0" if "AFFERO" in license_text else "Unlicense" if "unencumbered" in license_text else "unspecified"
        handlers = handler_names(directory, files)
        rows.append({
            "name": name, "url": url, "commit": git(directory, "rev-parse", "HEAD"),
            "branch": ("development" if name == "LunarCore-development" else git(directory, "symbolic-ref", "--short", "refs/remotes/origin/HEAD").removeprefix("origin/")),
            "commitDate": git(directory, "show", "-s", "--format=%cs", "HEAD"),
            "language": language, "declaredClientVersion": version, "license": license_id,
            "trackedFiles": len(files), "sourceExtensions": dict(collections.Counter(p.suffix or "(none)" for p in files)),
            "handlers": handlers,
        })
    analysis_path = ROOT / "references/HNA-MORAX"
    analysis_commit = git(analysis_path, "rev-parse", "HEAD")
    if analysis_commit != "5b169a9ec74dd8e369ff20933dbb9052b1b75b30" or not (
            analysis_path / "LICENSE").read_text(encoding="utf-8").startswith("MIT License"):
        raise RuntimeError("客户端分析辅助参考的固定提交或许可证不匹配")
    analysis = [{"name": "HNA-MORAX", "url": "https://github.com/NeonTeam58/HNA-MORAX",
        "commit": analysis_commit, "license": "MIT", "runtimeDependency": False,
        "binariesExecuted": False, "codeImported": False,
        "purpose": "只读分析参考；测试版常量不适用于本机，解析脚本由本机反汇编证据独立实现"}]
    output = {"snapshotDate": "2026-09-30", "note": "Source declarations only; handler names and counts do not prove gameplay or client compatibility.", "projects": rows, "analysisReferences": analysis}
    (ROOT / "references.lock.json").write_text(json.dumps(output, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    baseline = rows[3]["handlers"]
    lines = ["# 参考源码快照", "", "快照日期：2026-09-30。以下数字来自固定提交的源码扫描，不代表可玩程度。Go 按服务端命令路由表记录注册入口并关联函数定义，排除机器人客户端、注释和生成协议；其他语言保留声明扫描口径，数字不能直接横向排名。", "", "| 项目 | 语言 | 声明版本 | 分支 | 提交 | 许可证 | 识别的处理器名称 |", "|---|---|---|---|---|---|---|"]
    for row in rows:
        lines.append(f"| [{row['name']}]({row['url']}) | {row['language']} | {row['declaredClientVersion']} | {row['branch']} | `{row['commit'][:12]}` | {row['license']} | {len(row['handlers'])} |")
    lines += ["", "## 客户端分析辅助参考", "",
        f"[NeonTeam58/HNA-MORAX](https://github.com/NeonTeam58/HNA-MORAX)，MIT，固定提交 `{analysis_commit}`。仅阅读源码，没有执行仓库程序、导入代码或加入运行依赖。其测试版常量不适用于本机 4.6；解析脚本使用本机反汇编独立核对的常量，见 [客户端静态证据](client-protocol-evidence.md)。不计入上述玩法处理器统计。", "",
        "## Java 主工程与其他源码的接口差异", "", "按消息名比较，未做字段号、命令号或玩法语义等价验证。同名消息也可能无法跨版本复用。", ""]
    for row in rows:
        if row["name"] == "LunarCore": continue
        missing = sorted(set(row["handlers"]) - set(baseline))
        lines += [f"### {row['name']}：主工程未识别到的同名处理器 ({len(missing)})", ""]
        lines += [f"- `{name}` — `{row['handlers'][name][0]}`" for name in missing]
        lines.append("")
    (ROOT / "docs/reference-inventory.md").write_text("\n".join(lines).rstrip() + "\n", encoding="utf-8")
    print(json.dumps({row["name"]: {"handlers": len(row["handlers"]), "commit": row["commit"][:12]} for row in rows}, indent=2))

if __name__ == "__main__":
    main()
