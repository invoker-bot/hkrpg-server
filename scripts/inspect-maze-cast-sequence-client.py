"""只读核对4.6施放UseID、投射物快照、组合标签与1408扣点请求来源。

不加载/执行GameAssembly.dll，不启动官方客户端，不改变资源或描述符。
所有方法同时核对元数据身份、PE函数边界、函数字节SHA和关键寄存器指令。
静态请求构造证据不等于官方客户端实际发包顺序、UI或完整投射物调度验收。
"""
import argparse
import hashlib
import importlib.util
import json
import mmap
from pathlib import Path
import struct
import subprocess
from types import SimpleNamespace

import capstone
from google.protobuf import descriptor_pb2

ROOT = next(parent for parent in Path(__file__).resolve().parents
            if (parent / 'scripts/analyze-client-46.py').is_file())


def sha(data):
    return hashlib.sha256(data).hexdigest()


def pe_layout(data):
    pe = struct.unpack_from('<I', data, 0x3c)[0]
    if data[pe:pe + 4] != b'PE\0\0':
        raise ValueError('不是PE原件')
    optional = pe + 24
    if struct.unpack_from('<H', data, optional)[0] != 0x20b:
        raise ValueError('不是固定PE32+架构')
    section_table = optional + struct.unpack_from('<H', data, pe + 20)[0]
    count = struct.unpack_from('<H', data, pe + 6)[0]
    sections = [struct.unpack_from('<IIII', data, section_table + j * 40 + 8) for j in range(count)]

    def offset(rva, length):
        for _, virtual, raw_size, raw in sections:
            if virtual <= rva and rva + length <= virtual + raw_size:
                result = raw + rva - virtual
                if result + length <= len(data):
                    return result
        raise ValueError('RVA没有完整原文件字节')

    exception_rva, exception_size = struct.unpack_from('<II', data, optional + 112 + 24)
    if exception_size % 12:
        raise ValueError('函数边界表大小不匹配')
    begin = offset(exception_rva, exception_size)
    image_base = struct.unpack_from('<Q', data, optional + 24)[0]
    return offset, begin, exception_size, image_base


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--client-dir', type=Path, required=True)
    parser.add_argument('--resources', type=Path, default=ROOT / 'resources/4.6')
    parser.add_argument('--references', type=Path, default=ROOT / 'references/TurnBasedGameData')
    parser.add_argument('--output-dir', type=Path, default=ROOT / 'build/maze-cast-sequence-client')
    args = parser.parse_args()
    output = args.output_dir.resolve()
    if not output.is_relative_to((ROOT / 'build').resolve()) or output == (ROOT / 'build').resolve():
        raise ValueError('输出仅限本项目build子目录')
    # Reserve a unique report path before any write; never overwrite an earlier run.
    output.mkdir(parents=True, exist_ok=False)
    profile_path = ROOT / 'scripts/fixtures/maze-cast-sequence-native-4.6.json'
    fixture_path = ROOT / 'src/test/resources/fixtures/maze-cast-sequence-4.6.json'
    profile = json.loads(profile_path.read_text('utf-8'))
    fixture = json.loads(fixture_path.read_text('utf-8'))
    descriptor = ROOT / 'src/main/resources/protocol/4.6/descriptor.pb'
    if sha(descriptor.read_bytes()) != profile['descriptorSHA256']:
        raise ValueError('冻结4.6描述符变化，禁止沿用字段结论')
    spec = importlib.util.spec_from_file_location('cast_sequence_native', ROOT / 'scripts/analyze-client-46.py')
    native = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(native)
    report = native.extract(SimpleNamespace(
        client_dir=args.client_dir, types=list(profile['types']), fields=True, field_offsets=True,
        inheritance=True, type_contains=[], method_indices=[value['index'] for value in profile['methods']],
        method_rvas=[], type_slots=[int(value['slot'], 0) for value in profile['typeSlots']],
        method_slots=[], string_slots=[], method_names=[], method_name_contains=[], disassemble=0))
    if report['files'] != profile['clientFiles'] or report['unmatchedTypes']:
        raise ValueError('客户端指纹或类型未匹配')
    checks = []

    def check(ok, name):
        checks.append(dict(name=name, passed=bool(ok)))
        if not ok:
            (output / 'failed-checks.json').write_text(json.dumps(checks, ensure_ascii=False, indent=2) + '\n', 'utf-8')
            raise ValueError('来源/原生证据变化: ' + name)

    types = {value['name']: value for value in report['methods']}
    for name, expected in profile['types'].items():
        actual = types[name]
        check(actual['typeIndex'] == expected['typeIndex'], 'native-type:' + name)
        fields = {field['name']: field for field in actual['fields']}
        for key, constant in expected['constants'].items():
            check(fields[key].get('constant', {}).get('value') == constant, 'native-constant:' + name + '.' + key)
        for key, layout in expected['fields'].items():
            field = fields[key]
            check(not field.get('isStatic') and field.get('instanceOffset') == layout['offset']
                  and field['nativeType']['kind'] == layout['kind']
                  and ('typeIndex' not in layout or field['nativeType'].get('typeIndex') == layout['typeIndex']),
                  'native-layout:' + name + '.' + key)
    slots = {value['slot']: value['typeIndex'] for value in report['typeInfoSlots']}
    check(slots == {value['slot']: value['typeIndex'] for value in profile['typeSlots']}, 'native-type-slots')
    methods = {value['index']: value for value in report['selectedMethods']}
    dll_path = args.client_dir / 'GameAssembly.dll'
    with dll_path.open('rb') as stream, mmap.mmap(stream.fileno(), 0, access=mmap.ACCESS_READ) as dll:
        offset, exception_begin, exception_size, image_base = pe_layout(dll)
        starts = {int(value['rva'], 0) for value in profile['methods']}
        ends = {}
        for pos in range(exception_begin, exception_begin + exception_size, 12):
            start, end, _ = struct.unpack_from('<III', dll, pos)
            if start in starts:
                if start in ends and ends[start] != end:
                    raise ValueError('PE函数边界冲突')
                ends[start] = end
        decoder = capstone.Cs(capstone.CS_ARCH_X86, capstone.CS_MODE_64)
        for expected in profile['methods']:
            actual = methods[expected['index']]
            check(all(actual[key] == expected[key] for key in ['name', 'rva', 'typeIndex']),
                  'native-method:' + str(expected['index']))
            rva, end = int(expected['rva'], 0), int(expected['endRVA'], 0)
            check(ends.get(rva) == end and 0 < end - rva < 20000, 'PE-method-boundary:' + str(expected['index']))
            raw = offset(rva, end - rva)
            body = dll[raw:raw + end - rva]
            check(sha(body) == expected['bodySHA256'], 'native-method-bytes:' + str(expected['index']))
            instructions = [f'{ins.address - image_base:x}: {ins.mnemonic} {ins.op_str}'
                            for ins in decoder.disasm(body, image_base + rva)]
            actual['endRVA'] = hex(end)
            actual['bodySHA256'] = sha(body)
            actual['instructions'] = instructions
            for line in expected['instructions']:
                check(int(line.split(':', 1)[0], 16) < end and line in instructions,
                      'native-instruction:' + line)

    descriptors = descriptor_pb2.FileDescriptorSet.FromString(descriptor.read_bytes())
    messages = {message.name: message for file in descriptors.file for message in file.message_type}
    commands = json.loads((ROOT / 'src/main/resources/protocol/4.6/commands.json').read_text('utf-8'))
    for name, fields in profile['fields'].items():
        check(commands[name] == fields['command'], 'native-command:' + name)
        actual = {field.name: field for field in messages[name].field}
        for field_name, number in fields.items():
            if field_name != 'command':
                check(actual[field_name].number == number, 'wire-field:' + name + '.' + field_name)
        if 'cast_entity_id' in actual:
            check(actual['cast_entity_id'].type == descriptor_pb2.FieldDescriptorProto.TYPE_UINT32,
                  'wire-uint-use-id:' + name)
    tags = next(field for field in messages['SceneCastSkillCsReq'].field if field.name == 'skill_extra_tags')
    check(tags.label == descriptor_pb2.FieldDescriptorProto.LABEL_REPEATED
          and tags.type == descriptor_pb2.FieldDescriptorProto.TYPE_ENUM, 'wire-repeated-event-tags')

    check(fixture['sourceCommit'] == profile['sourceCommit'], 'source-commit')
    for relative, hashes in fixture['sourceFiles'].items():
        data = (args.resources / relative).read_bytes()
        check(sha(data) == hashes['installedSHA256'], 'installed-original:' + relative)
        blob = subprocess.run(['git', '-C', str(args.references), 'show', profile['sourceCommit'] + ':' + relative],
                              check=True, capture_output=True).stdout
        check(sha(blob) == hashes['blobSHA256'], 'fixed-git-blob:' + relative)
        check(data.replace(b'\r\n', b'\n') == blob.replace(b'\r\n', b'\n'), 'source-checkout-line-endings:' + relative)
    for record in fixture['records']:
        source_avatar = json.loads((args.resources / record['avatarFile']).read_text('utf-8'))
        source_maze = json.loads((args.resources / record['mazeFile']).read_text('utf-8'))
        source_ability = json.loads((args.resources / record['abilityFile']).read_text('utf-8'))['AbilityList']
        check(record['avatar'] in source_avatar, 'source-avatar:' + str(record['avatarID']))
        for row in record['mazeRows']:
            check(row in source_maze, 'source-maze-cost:' + str(row['MazeSkillId']))
        for ability in record['abilities']:
            check(ability in source_ability, 'source-ability:' + ability['Name'])
    result = dict(passed=True, checks=len(checks), nativeTypes=len(types), nativeMethods=len(methods),
                  sourceCommit=profile['sourceCommit'], clientFiles=profile['clientFiles'],
                  descriptorSHA256=profile['descriptorSHA256'],
                  profileSHA256=sha(profile_path.read_bytes()), fixtureSHA256=sha(fixture_path.read_bytes()),
                  directTagValues=profile['directTagValues'],
                  costRequestContainsUseID=True, projectilePreservesOriginalUseID=True,
                  clientExecutableStarted=False, clientModified=False, clientGameplayVerified=False,
                  officialRequestSequenceObserved=False, multiPacketTechniqueMPVerified=False,
                  descriptorModified=False, limitations=profile['limitations'])
    (output / 'native.json').write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n', 'utf-8')
    (output / 'checks.json').write_text(json.dumps(checks, ensure_ascii=False, indent=2) + '\n', 'utf-8')
    (output / 'verification.json').write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n', 'utf-8')
    print(json.dumps(result, ensure_ascii=False))


if __name__ == '__main__':
    main()
