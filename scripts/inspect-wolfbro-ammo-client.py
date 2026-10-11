"""只读核对4.6小小哈努枪械、弹药请求、同步字段和两个完整道具配置。

不执行客户端，不改描述符，不安装或覆盖资源。原生静态发送器不等于UI验收；
初填、每枪扣弹与掉落数量是本服规则。6502发送器有证明，爆炸玩法尚未实现。
"""
import argparse
import base64
import hashlib
import importlib.util
import json
import mmap
from pathlib import Path
import re
import struct
import subprocess
import sys
import tempfile
from types import SimpleNamespace

import capstone
from google.protobuf import descriptor_pb2

ROOT = next(p for p in Path(__file__).resolve().parents if (p / 'scripts/analyze-client-46.py').is_file())
sys.path.insert(0, str(ROOT / 'scripts'))
import source_json
PROFILE_PATH = ROOT / 'scripts/fixtures/wolfbro-ammo-native-4.6.json'


def module(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    result = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(result)
    return result


def sha(data):
    return hashlib.sha256(data).hexdigest()


def fingerprint(value):
    return source_json.fingerprint(value)


def decode_config(raw, profile=None):
    profile = profile or json.loads(PROFILE_PATH.read_text('utf-8'))
    graphs = module('wolf_ammo_graph_reader', ROOT / 'scripts/decode-client-level-graphs.py')
    layout = graphs.load_profile()
    layout['classes'].update(profile['classes'])

    class Reader(graphs.Reader):
        def obj(self, name):
            start = self.pos
            mask = self.uvar()
            self.pos = start
            if name not in profile['allowedMasks'] or mask & ~int(profile['allowedMasks'][name], 0):
                raise ValueError('未核对的弹药配置分支: ' + name)
            return super().obj(name)

    reader = Reader(raw, layout)
    value = reader.obj('PropConfig')
    if reader.pos != len(raw):
        raise ValueError('弹药道具配置存在未读取尾部')

    def resource(value):
        if isinstance(value, list):
            return [resource(x) for x in value]
        if isinstance(value, dict):
            name = value.get('$type')
            if name not in profile['allowedMasks']:
                raise ValueError('未核对的弹药配置对象')
            fields = layout['classes'][name]['fields']
            allowed = {field['name'] for field in fields} | {'$type', '$mask', '$offset'}
            if set(value) - allowed:
                raise ValueError('弹药配置对象含未核对字段')
            return {k: resource(v) for k, v in value.items() if k not in {'$type', '$mask', '$offset'}}
        return value

    return resource(value)


def inspect(args):
    profile = json.loads(PROFILE_PATH.read_text('utf-8'))
    output = args.output_dir.resolve()
    if not output.is_relative_to((ROOT / 'build').resolve()) or output == (ROOT / 'build').resolve():
        raise ValueError('输出必须在本项目build子目录')
    output.mkdir(parents=True, exist_ok=False)
    checks = []

    def check(ok, name):
        checks.append({'name': name, 'passed': bool(ok)})
        if not ok:
            (output / 'failed-checks.json').write_text(json.dumps(checks, ensure_ascii=False, indent=2), 'utf-8')
            raise ValueError('弹药来源或原生证据变化: ' + name)

    descriptor = ROOT / 'src/main/resources/protocol/4.6/descriptor.pb'
    check(sha(descriptor.read_bytes()) == profile['descriptorSHA256'], 'frozen-descriptor')
    analyzer = module('wolf_ammo_native', ROOT / 'scripts/analyze-client-46.py')
    native = analyzer.extract(SimpleNamespace(client_dir=args.client_dir, types=list(profile['types']),
        fields=True, field_offsets=True, inheritance=True, type_contains=[],
        method_indices=[m['index'] for m in profile['methods']], method_rvas=[],
        type_slots=[int(s['slot'], 0) for s in profile['typeSlots']], method_slots=[],
        string_slots=[], method_names=[], method_name_contains=[], disassemble=0))
    check(native['files'] == profile['clientFiles'] and not native['unmatchedTypes'], 'native-client-identity')
    actual_types = {t['name']: t for t in native['methods']}
    for name, expected in profile['types'].items():
        actual = actual_types[name]
        check(actual['typeIndex'] == expected['typeIndex'], 'native-type:' + name)
        fields = {f['name']: f for f in actual['fields']}
        for key, number in expected['constants'].items():
            check(fields[key].get('constant', {}).get('value') == number, 'native-constant:' + name + '.' + key)
        for key, layout in expected['fields'].items():
            check(fields[key].get('instanceOffset') == layout['offset']
                  and fields[key]['nativeType']['kind'] == layout['kind'], 'native-layout:' + name + '.' + key)
    check({s['slot']: s['typeIndex'] for s in native['typeInfoSlots']}
          == {s['slot']: s['typeIndex'] for s in profile['typeSlots']}, 'native-request-type-slots')
    pe = module('wolf_ammo_pe', ROOT / 'scripts/inspect-maze-cast-sequence-client.py')
    methods = {m['index']: m for m in native['selectedMethods']}
    with (args.client_dir / 'GameAssembly.dll').open('rb') as f, mmap.mmap(f.fileno(), 0, access=mmap.ACCESS_READ) as dll:
        offset, begin, size, _ = pe.pe_layout(dll)
        ends = {b: e for b, e, _ in struct.iter_unpack('<III', dll[begin:begin + size])}
        decoder = capstone.Cs(capstone.CS_ARCH_X86, capstone.CS_MODE_64)
        for expected in profile['methods']:
            actual = methods[expected['index']]
            check(all(actual[key] == expected[key] for key in ('index', 'name', 'typeIndex', 'typeName', 'rva')),
                  'native-method:' + str(expected['index']))
            start, end = int(expected['rva'], 0), int(expected['endRVA'], 0)
            check(ends.get(start) == end and 0 < end - start < 20000, 'PE-boundary:' + str(expected['index']))
            position = offset(start, end - start)
            body = dll[position:position + end - start]
            check(sha(body) == expected['bodySHA256'], 'native-whole-function:' + str(expected['index']))
            instructions = [f'{i.address:x}: {i.mnemonic} {i.op_str}' for i in decoder.disasm(body, start)]
            for line in expected['instructions']:
                check(int(line.split(':', 1)[0], 16) < end and line in instructions, 'native-instruction:' + line)
            actual.update(endRVA=hex(end), bodySHA256=sha(body), instructions=instructions)
    descriptors = descriptor_pb2.FileDescriptorSet.FromString(descriptor.read_bytes())
    messages = {m.name: m for file in descriptors.file for m in file.message_type}
    enums = {e.name: {v.name: v.number for v in e.value} for file in descriptors.file for e in file.enum_type}
    commands = json.loads((ROOT / 'src/main/resources/protocol/4.6/commands.json').read_text('utf-8'))
    for name, fields in profile['wire'].items():
        if 'command' in fields:
            actual_command = (enums[fields['commandEnum']][fields['commandName']]
                              if 'commandEnum' in fields else commands[name])
            check(actual_command == fields['command'], 'native-command:' + name)
        actual = {field.name: field for field in messages[name].field}
        for name_field, number in fields.items():
            if name_field not in ('command', 'commandName', 'commandEnum'):
                check(actual[name_field].number == number, 'native-wire:' + name + '.' + name_field)

    config_fixture = json.loads((ROOT / 'src/test/resources/fixtures/wolfbro-ammo-client-configs-4.6.json').read_text('utf-8'))
    design = module('wolf_ammo_design', ROOT / 'scripts/read-client-design-data.py')
    with tempfile.TemporaryDirectory(prefix='hkrpg-wolf-ammo-') as directory:
        temp = Path(directory)
        records = design.extract(SimpleNamespace(client_dir=args.client_dir,
            paths=[r['path'] for r in config_fixture['records']], paths_file=None,
            extract_dir=temp / 'raw', output=temp / 'records.json'))
        for expected in config_fixture['records']:
            record = next(r for r in records['records'] if r['sourcePath'] == expected['path'])
            check(record['status'] == 'read', 'local-prop-record:' + expected['path'])
            raw = Path(record['extractedFile']).read_bytes()
            check(len(raw) == expected['length'] and sha(raw) == expected['sha256']
                  and raw == base64.b64decode(expected['rawBase64'], validate=True), 'local-prop-bytes:' + expected['path'])
            value = decode_config(raw, profile)
            blob = subprocess.run(['git', '-C', str(args.references), 'show', profile['sourceCommit'] + ':' + expected['path']],
                                  check=True, capture_output=True).stdout
            check(value == expected['resource'] == json.loads(blob), 'whole-prop-object:' + expected['path'])

    fixture = source_json.loads((ROOT / 'src/test/resources/fixtures/wolfbro-ammo-4.6.json').read_text('utf-8'))
    check(fixture['sourceCommit'] == profile['sourceCommit'], 'fixed-source-commit')
    files = {}
    for item in fixture['sourceFiles'] + fixture['sourceRows']:
        path = item.get('path', item.get('sourcePath'))
        if path not in files:
            blob = subprocess.run(['git', '-C', str(args.references), 'show', profile['sourceCommit'] + ':' + path],
                                  check=True, capture_output=True).stdout
            actual = (args.resources / path).read_bytes()
            check(sha(blob) == item['sourceBlobSHA256'] and sha(actual) == item['fileSHA256'], 'whole-source-file:' + path)
            check(source_json.canonical(source_json.loads(actual)) == source_json.canonical(source_json.loads(blob)),
                  'whole-source-content:' + path)
            files[path] = source_json.loads(blob)
        if 'table' in item:
            found = [r for r in files[path] if r[item['keyField']] == item['key']]
            check(len(found) == 1 and source_json.canonical(found[0]) == source_json.canonical(item['data']),
                  'whole-source-row:' + item['table'] + ':' + str(item['key']))
        else:
            check(source_json.canonical(files[path]) == source_json.canonical(item['data']), 'whole-source-placement:' + path)

    sources = (ROOT / 'src/main/java/org/hkrpg/server/gameplay/WolfBroAmmoSources.java').read_text('utf-8')
    pins = dict(re.findall(r'Map\.entry\("([^"]+)", "([0-9a-f]{64})"\)', sources))
    for path, pin in pins.items():
        if path.startswith('Config/'):
            check(fingerprint(source_json.loads((args.resources / path).read_bytes())) == pin, 'runtime-whole-placement:' + path)
        else:
            table, key = path.split(':', 1)
            key_field = {'MazePuzzleWolfBro': 'WolfBroID', 'MazePlane': 'PlaneID', 'MazeProp': 'ID',
                         'NPCMonsterData': 'ID', 'ConstValueCommon': 'ConstValueName'}[table]
            content = files.setdefault('ExcelOutput/' + table + '.json', source_json.loads((args.resources / ('ExcelOutput/' + table + '.json')).read_bytes()))
            rows = [r for r in content if str(r[key_field]) == key]
            check(len(rows) == 1 and fingerprint(rows[0]) == pin, 'runtime-whole-row:' + path)
    result = {'passed': True, 'checks': len(checks), 'nativeTypes': len(actual_types), 'nativeCompleteFunctions': len(methods),
        'completeLocalPropConfigs': len(config_fixture['records']), 'completeLevel11Files': len(fixture['sourceFiles']),
        'completeLevel11Rows': len(fixture['sourceRows']), 'runtimeSourcePins': len(pins), 'sourceCommit': profile['sourceCommit'],
        'clientFiles': profile['clientFiles'], 'profileSHA256': sha(PROFILE_PATH.read_bytes()),
        'descriptorSHA256': profile['descriptorSHA256'], 'serverPolicy': fixture['serverPolicy'],
        'clientStarted': False, 'clientModified': False, 'clientGameplayVerified': False,
        'officialNumericalRulesRecovered': False, 'explodeMonsterImplemented': False,
        'limitations': profile['limitations']}
    (output / 'native.json').write_text(json.dumps(native, ensure_ascii=False, indent=2), 'utf-8')
    (output / 'checks.json').write_text(json.dumps(checks, ensure_ascii=False, indent=2), 'utf-8')
    (output / 'verification.json').write_text(json.dumps(result, ensure_ascii=False, indent=2), 'utf-8')
    return result


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--client-dir', type=Path, required=True)
    parser.add_argument('--resources', type=Path, default=ROOT / 'resources/4.6')
    parser.add_argument('--references', type=Path, default=ROOT / 'references/TurnBasedGameData')
    parser.add_argument('--output-dir', type=Path, default=ROOT / 'build/wolfbro-ammo-client')
    print(json.dumps(inspect(parser.parse_args()), ensure_ascii=False))
