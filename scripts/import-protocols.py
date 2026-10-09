"""导入锁定的协议快照；只读取参考源码，不运行参考仓库的程序。"""
import argparse
import hashlib
import html
import json
import re
import shutil
import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SOURCE_COMMIT = "fe00bdbda3c28ca33d01f171566aa35b5ab95083"


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--protoc", required=True)
    args = parser.parse_args()
    source = ROOT / "references/March7thHoney-OpenSource"
    commit = subprocess.check_output(["git", "-C", str(source), "rev-parse", "HEAD"], text=True).strip()
    if commit != SOURCE_COMMIT:
        raise SystemExit("参考协议提交与锁定版本不同，拒绝覆盖")
    target = ROOT / "protocols/4.6"
    target.mkdir(parents=True, exist_ok=True)
    files = sorted((source / "Proto/4.6").glob("*.proto"))
    for file in files:
        shutil.copyfile(file, target / file.name)
    old_dir = ROOT / "protocols/4.2"
    old_dir.mkdir(parents=True, exist_ok=True)
    definitions = []
    for file in sorted((ROOT / "src/generated/main/emu/lunarcore/proto").glob("*OuterClass.java")):
        text = file.read_text(encoding="utf-8")
        parts = re.split(r"Protobuf (type|enum) \{@code (\w+)\}", text)
        members = []
        for index in range(1, len(parts), 3):
            kind, name, body = parts[index:index + 3]
            fields = list(dict.fromkeys(html.unescape(x) for x in re.findall(r"<code>([^<]+)</code>", body)))
            if kind == "type":
                fields = [x for x in fields if re.fullmatch(r"(?:optional|repeated) [\w.]+ \w+ = \d+;", x)]
                fields = [re.sub(r"(optional|repeated) \.", r"\1 .legacy.", x) for x in fields]
                kind = "message"
            else:
                fields = [x for x in fields if re.fullmatch(r"\w+ = -?\d+;", x)]
            members.append((kind, name, fields))
        kind, name, fields = members[0]
        nested = "".join(f"  {k} {n} {{\n" + "".join(f"    {x}\n" for x in f) + "  }\n" for k, n, f in members[1:])
        definitions.append(f"{kind} {name} {{\n" + "".join(f"  {x}\n" for x in fields) + nested + "}")
    (old_dir / "legacy.proto").write_text('syntax = "proto2";\npackage legacy;\n\n' + "\n\n".join(definitions) + "\n", encoding="utf-8", newline="\n")
    output_root = ROOT / "src/main/resources/protocol"
    for version, directory in [("4.2", old_dir), ("4.6", target)]:
        output = output_root / version
        output.mkdir(parents=True, exist_ok=True)
        proto_files = sorted(directory.glob("*.proto"))
        subprocess.run([args.protoc, f"--proto_path={directory}", f"--descriptor_set_out={output / 'descriptor.pb'}", "--include_imports", *map(str, proto_files)], check=True)
        manifest = {p.name: hashlib.sha256(p.read_bytes()).hexdigest() for p in proto_files}
        manifest["descriptor.pb"] = hashlib.sha256((output / "descriptor.pb").read_bytes()).hexdigest()
        (output / "checksums.json").write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8", newline="\n")
    constants = (source / "Proto/4.6/CmdIds.cs").read_text(encoding="utf-8-sig")
    commands = {name: int(value) for name, value in re.findall(r"public const int (\w+) = (\d+);", constants)
                if name.endswith(("CsReq", "ScRsp", "ScNotify", "Notify", "Rsp")) and int(value) > 0}
    alias_file = source / "Proto/4.6/cmdid-aliases.txt"
    aliases = dict(re.findall(r"^(\w+) = (\w+)$", alias_file.read_text(encoding="utf-8-sig"), re.M))
    # Readable aliases take precedence over alternative spellings for the same command.
    for alias, original in aliases.items():
        if alias in commands and original in commands:
            del commands[original]
    if len(set(commands.values())) != len(commands):
        raise SystemExit("4.6 命令号重复，拒绝导入")
    shutil.copyfile(alias_file, target / alias_file.name)
    (output_root / "4.6/aliases.json").write_text(json.dumps(aliases, indent=2) + "\n", encoding="utf-8", newline="\n")
    (output_root / "4.6/commands.json").write_text(json.dumps(dict(sorted(commands.items())), indent=2) + "\n", encoding="utf-8", newline="\n")
    shutil.copyfile(source / "Proto/4.6/CmdIds.cs", target / "CmdIds.cs")
    checksum_file = output_root / "4.6/checksums.json"
    checksums = json.loads(checksum_file.read_text(encoding="utf-8"))
    for name in ["commands.json", "aliases.json"]:
        checksums[name] = hashlib.sha256((output_root / "4.6" / name).read_bytes()).hexdigest()
    for name in ["CmdIds.cs", "cmdid-aliases.txt"]:
        checksums[name] = hashlib.sha256((target / name).read_bytes()).hexdigest()
    checksum_file.write_text(json.dumps(checksums, indent=2) + "\n", encoding="utf-8", newline="\n")
    print(f"Imported {len(files)} protocol files, {len(commands)} named commands, {len(definitions)} legacy types")


if __name__ == "__main__":
    main()
