"""只读核对4.6原1510手动Input、UseID快照、字段15和显式命中费用链。

完整核对固定metadata身份、PE函数边界与字节；不执行官方DLL/客户端。
静态来源不等于实际官方发包、动画、本地Out或完整秘技生命周期验收。
"""

import argparse
import hashlib
import importlib.util
import json
import mmap
from pathlib import Path
import struct
from types import SimpleNamespace

import capstone
from google.protobuf import descriptor_pb2

ROOT = Path(__file__).resolve().parents[1]


def sha(data):
    return hashlib.sha256(data).hexdigest()


def module_at(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def native_options(client_dir, profile):
    return SimpleNamespace(
        client_dir=client_dir, types=profile["types"], fields=True, field_offsets=True,
        inheritance=False, type_contains=[], method_indices=[row["index"] for row in profile["functions"]],
        method_rvas=[], type_slots=[], method_slots=[], string_slots=[], method_names=[],
        method_name_contains=[], disassemble=0,
    )


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--client-dir", type=Path, required=True)
    parser.add_argument("--resources", type=Path, default=ROOT / "resources/4.6")
    parser.add_argument("--references", type=Path, default=ROOT / "references/TurnBasedGameData")
    parser.add_argument("--output-dir", type=Path, required=True)
    args = parser.parse_args()
    output = args.output_dir.resolve()
    if not output.is_relative_to((ROOT / "build").resolve()) or output == (ROOT / "build").resolve():
        raise ValueError("输出仅限本项目build子目录")
    output.mkdir(parents=True, exist_ok=False)
    profile_path = ROOT / "scripts/fixtures/maze-manual-ability-native-4.6.json"
    profile = json.loads(profile_path.read_text("utf-8"))
    descriptor = ROOT / "src/main/resources/protocol/4.6/descriptor.pb"
    dll = args.client_dir / "GameAssembly.dll"
    metadata = args.client_dir / "StarRail_Data/il2cpp_data/Metadata/global-metadata.dat"
    checks = []

    def check(name, condition):
        checks.append({"name": name, "passed": bool(condition)})
        if not condition:
            raise ValueError("来源核对失败: " + name)

    report = None
    completed = False
    try:
        check("original GameAssembly SHA256", sha(dll.read_bytes()) == profile["gameAssemblySHA256"])
        check("original metadata SHA256", sha(metadata.read_bytes()) == profile["metadataSHA256"])
        check("frozen native descriptor SHA256", sha(descriptor.read_bytes()) == profile["descriptorSHA256"])
        source = module_at("manual_source_freeze", ROOT / "scripts/freeze-maze-manual-ability-source.py")
        fixture = source.source_fixture(args.references.resolve(), args.resources.resolve())
        check("fixed source commit", fixture["sourceCommit"] == profile["sourceCommit"])
        for name, pin in profile["sourceFiles"].items():
            check("whole source blob " + name, fixture["sourceFiles"][name]["blobSHA256"] == pin)
        native = module_at("manual_native_identity", ROOT / "scripts/analyze-client-46.py")
        report = native.extract(native_options(args.client_dir, profile))
        types = {row["name"]: row for row in report["methods"]}
        check("all explicit native config types found", not report["unmatchedTypes"])
        for name, wanted in profile["fields"].items():
            fields = {row["name"]: row for row in types[name]["fields"]}
            for field, offset in wanted.items():
                check(name + "." + field + " native offset", fields[field].get("instanceOffset") == offset)
        for name, wanted in profile["enums"].items():
            fields = {row["name"]: row for row in types[name]["fields"]}
            for field, value in wanted.items():
                check(name + "." + field + " original enum", fields[field].get("constant", {}).get("value") == value)
        methods = {row["index"]: row for row in report["selectedMethods"]}
        layout = module_at("manual_pe_layout", ROOT / "scripts/inspect-maze-cast-sequence-client.py")
        with dll.open("rb") as stream, mmap.mmap(stream.fileno(), 0, access=mmap.ACCESS_READ) as data:
            offset, begin, size, image_base = layout.pe_layout(data)
            ranges = {start: end for start, end, _ in struct.iter_unpack("<III", data[begin:begin + size])}
            engine = capstone.Cs(capstone.CS_ARCH_X86, capstone.CS_MODE_64)
            for pin in profile["functions"]:
                method = methods[pin["index"]]
                check(str(pin["index"]) + " original method identity",
                      method["name"] == pin["name"] and method["typeName"] == pin["typeName"]
                      and method["rva"] == pin["rva"])
                start, end = int(pin["rva"], 16), int(pin["endRVA"], 16)
                check(str(pin["index"]) + " full PE exception boundary", ranges.get(start) == end)
                raw = offset(start, end - start)
                body = data[raw:raw + end - start]
                check(str(pin["index"]) + " whole function SHA256", sha(body) == pin["bodySHA256"])
                decoded = list(engine.disasm(body, image_base + start))
                check(str(pin["index"]) + " all bytes disassembled", sum(row.size for row in decoded) == len(body))
                instructions = [f"{row.address - image_base:x}: {row.mnemonic} {row.op_str}" for row in decoded]
                method.update(endRVA=pin["endRVA"], bodySHA256=sha(body), instructions=instructions)
                for anchor in pin["anchors"]:
                    check(str(pin["index"]) + " " + anchor, anchor in instructions)
        # This negative claim covers the complete direct function, never an excerpt.
        manual = methods[524586]["instructions"]
        check("ManualTriggerAbility has no direct _CostMazeSkillMp call",
              not any("call 0x18e575700" in row for row in manual))
        check("ManualTriggerAbility starts source ability through actual UseAbility",
              "e57674a: call 0x18e5369d0" in manual)
        schema = descriptor_pb2.FileDescriptorSet.FromString(descriptor.read_bytes())
        messages = {message.name: message for file in schema.file for message in file.message_type}
        req = {field.name: field for field in messages["SceneCastSkillCsReq"].field}
        for name, number, kind in [("maze_ability_str", 15, 9), ("cast_entity_id", 13, 13),
                                   ("skill_index", 4, 13), ("skill_extra_tags", 11, 14)]:
            check("1460 " + name + " field/type", req[name].number == number and req[name].type == kind)
        check("unknown field8 is preserved without a cost alias",
              any(field.number == 8 and field.name != "cost_mp" for field in req.values()))
        with (output / "native.json").open("x", encoding="utf-8") as stream:
            json.dump(report, stream, ensure_ascii=False, indent=2)
            stream.write("\n")
        completed = True
    finally:
        result = {
            "checks": checks, "passed": completed and len(checks) > 0 and all(row["passed"] for row in checks),
            "profileSHA256": sha(profile_path.read_bytes()), "sourceCommit": profile["sourceCommit"],
            "fullFunctionCount": len(profile["functions"]),
            "clientExecutableStarted": False, "clientModified": False,
            "clientGameplayVerified": False, "networkExchangePerformed": False,
            "boundaries": profile["boundaries"],
        }
        with (output / "verification.json").open("x", encoding="utf-8") as stream:
            json.dump(result, stream, ensure_ascii=False, indent=2)
            stream.write("\n")
    print(json.dumps({"output": str(output), "checks": len(checks), "passed": result["passed"],
                      "fullFunctions": len(profile["functions"]), "nativeExecuted": False}, ensure_ascii=False))


if __name__ == "__main__":
    main()
