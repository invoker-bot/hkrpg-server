"""只读完整解析固定 4.6 共享镜头所在的 76 条能力表。

限定原生字段、动作注册和参数查询格式，保留缺省值和后缀程序。
不求值、不执行、不安装；其他客户端或未知格式必须停止。
"""
import argparse
import hashlib
import importlib.util
import json
from pathlib import Path
import struct
import tempfile
from types import SimpleNamespace

ROOT = Path(__file__).resolve().parents[1]
PROFILE = ROOT / 'src/test/resources/fixtures/rogue-resonance-shared-parent-4.6.json'
PROFILE_SHA256 = '214e77f050d52052916ba453a0aeafa5bf8560b6a595e5c01e2b1cada9d10c9d'
spec = importlib.util.spec_from_file_location('resonance_shared_parent_camera',
    Path(__file__).with_name('read-rogue-resonance-shared-camera.py'))
camera = importlib.util.module_from_spec(spec)
spec.loader.exec_module(camera)


def load_profile():
    raw = PROFILE.read_bytes().replace(b'\r\n', b'\n')
    if hashlib.sha256(raw).hexdigest() != PROFILE_SHA256:
        raise ValueError('共享能力表的固定原生/资源证据已改变，需要重新核对')
    return json.loads(raw)


def ability_profile(profile=None):
    profile = profile if profile is not None else load_profile()
    result = camera.camera_ability_profile()
    result['schemas'].update(profile['additionalSchemas'])
    result['bindings']['task'].update(profile['additionalTaskBindings'])
    for name, fields in profile['additionalFields'].items():
        result['schemas'][name]['allowedFields'].extend(fields)
    for key, enum in profile['additionalEnums'].items():
        if key in result['enums']:
            result['enums'][key]['allowedRawValues'] = sorted(set(result['enums'][key]['allowedRawValues']) | set(enum['allowedRawValues']))
        else:
            result['enums'][key] = enum
    return result


def verify_native(client, profile=None):
    profile = profile if profile is not None else load_profile()
    counts = camera.abilities.characters.verify_native(client, profile)
    metadata = (client / 'StarRail_Data/il2cpp_data/Metadata/global-metadata.dat').read_bytes()
    for constant in profile['enumConstants']:
        if constant['nativeKind'] != 8 or struct.unpack_from('<i', metadata,
                constant['metadataOffset'])[0] != constant['value']:
            raise ValueError('共享能力表原始 Int32 枚举常量不符')
    return counts


class Reader(camera.abilities.Reader):
    def __init__(self, raw, profile=None, ability_schema=None):
        self.parent_profile = profile if profile is not None else load_profile()
        super().__init__(raw, ability_schema if ability_schema is not None else ability_profile(self.parent_profile))

    def _read(self, description):
        dictionary = self.parent_profile['queryDictionary']
        context = '.'.join(self.field_stack[-1]) if self.field_stack else ''
        if description['kind'] == 21 and context == dictionary['context']:
            return self.dictionary(dictionary['valueType'])
        return super()._read(description)


def verify_layout(value, layouts, length):
    entries = value.get('AbilityList')
    if not isinstance(entries, list) or not entries or len(entries) != len(layouts):
        raise ValueError('父能力表与布局对象数量不符')
    by_name = {}
    for row in layouts:
        name = row.get('UniqueName')
        if (not isinstance(name, str) or not name or name in by_name
                or type(row.get('TypeId')) is not int or row['TypeId'] != 1):
            raise ValueError('父能力表布局名称或类型不符')
        by_name[name] = row
    records, names, previous = [], set(), -1
    for index, entry in enumerate(entries):
        name, offset = entry.get('Name'), entry.get('$offset')
        row = by_name.get(name)
        if (row is None or name in names or type(offset) is not int
                or not previous < offset < length or row.get('Offset') != offset):
            raise ValueError('父能力表原始名称与字节偏移不符')
        end = entries[index + 1].get('$offset') if index + 1 < len(entries) else length
        if type(end) is not int or not offset < end <= length:
            raise ValueError('父能力对象字节范围不符')
        names.add(name)
        records.append({'name': name, 'offset': offset, 'endOffset': end, 'length': end - offset})
        previous = offset
    return records


def analyze(args):
    client = args.client_dir.resolve()
    design = camera.abilities.sibling('resonance_shared_parent_design', 'read-client-design-data.py')
    output = design.outside_client(args.output, client)
    if ROOT / 'build' not in output.parents:
        raise ValueError('只接受工程 build/ 下的只读证据输出')
    profile = load_profile()
    methods, native_bytes = verify_native(client, profile)
    camera_methods, camera_bytes = camera.verify_native(client)
    base_methods, base_bytes = camera.abilities.characters.verify_native(client, camera.abilities.load_profile())
    postfix_methods, postfix_bytes = camera.abilities.postfix.verify_native(client)
    camera_profile = camera.load_profile()
    expected_layout = next(r for r in camera_profile['records'] if '.layout.' in r['sourcePath'])
    expected = [profile['parentRecord'], expected_layout]
    (ROOT / 'build').mkdir(exist_ok=True)
    with tempfile.TemporaryDirectory(prefix='resonance-parent-', dir=ROOT / 'build') as directory:
        work = Path(directory)
        source = design.extract(SimpleNamespace(client_dir=client, paths_file=None,
            paths=[r['sourcePath'] for r in expected], extract_dir=work / 'raw', output=work / 'index.json'))
        if source['indexSHA256'] != profile['indexSHA256']:
            raise ValueError('共享能力表资源索引与原生证据不符')
        raw = {}
        for record, actual in zip(expected, source['records'], strict=True):
            if actual['status'] != 'read' or any(actual[k] != v for k, v in record.items()):
                raise ValueError('共享能力表原始记录身份/长度/哈希不符')
            raw[record['sourcePath']] = Path(actual['extractedFile']).read_bytes()
        reader = Reader(raw[expected[0]['sourcePath']], profile)
        value = reader.ability()
        layouts = camera.LayoutReader(raw[expected_layout['sourcePath']], camera_profile).layout()
        offsets = verify_layout(value, layouts, reader.pos)
        if len(offsets) != profile['abilityCount'] or reader.dynamic_expressions != profile['expressionCount']:
            raise ValueError('共享能力表有限格式范围不符')
        resolved_camera = next(r for r in offsets if r['name'] == camera_profile['sharedAbility']['name'])
        if (resolved_camera['offset'], resolved_camera['endOffset']) != (1617, 2201):
            raise ValueError('整表镜头偏移与独立读取证据不符')
    schema_profile = reader.profile
    report = {'passed': True, 'recordCount': 2, 'recordBytes': sum(r['length'] for r in expected),
        'parsedBytes': sum(r['length'] for r in expected), 'parentAbilityRecordFullyDecoded': True,
        'abilityCount': len(offsets), 'expressionCount': reader.dynamic_expressions,
        'matchedSupplementalNativeMethods': methods, 'matchedSupplementalNativeBytes': native_bytes,
        'cameraNativeMethods': camera_methods, 'cameraNativeBytes': camera_bytes,
        'baseNativeMethods': base_methods, 'baseNativeBytes': base_bytes,
        'postfixNativeMethods': postfix_methods, 'postfixNativeBytes': postfix_bytes,
        'enumConstantsMatched': len(profile['enumConstants']), 'schemas': len(schema_profile['schemas']),
        'allowedFields': sum(len(s['allowedFields']) for s in schema_profile['schemas'].values()),
        'enumTypes': len(schema_profile['enums']), 'taskBindings': len(schema_profile['bindings']['task']),
        'parentFullyConsumed': True, 'layoutFullyConsumed': True, 'offsets': offsets,
        'decoded': value, 'trace': reader.trace, 'defaultsNotMaterialized': True,
        'sourceRecords': expected, 'nativeFiles': profile['nativeFiles'], 'indexSHA256': source['indexSHA256'],
        'queryParametersEvaluated': False, 'expressionEvaluationVerified': False,
        'abilityExecutionVerified': False, 'jsonInstalled': False,
        'allResonanceAbilitiesImplemented': False, 'clientGameplayVerified': False,
        'clientExecutableStarted': False, 'clientModified': False}
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(report, ensure_ascii=False, indent=2, allow_nan=False) + '\n', 'utf-8')
    return report


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--client-dir', type=Path, required=True)
    parser.add_argument('--output', type=Path, default=ROOT / 'build/rogue-resonance-shared-parent-evidence.json')
    report = analyze(parser.parse_args())
    print(json.dumps({k: report[k] for k in ['passed', 'recordCount', 'recordBytes',
        'abilityCount', 'expressionCount', 'matchedSupplementalNativeMethods',
        'matchedSupplementalNativeBytes', 'jsonInstalled', 'clientGameplayVerified']}, ensure_ascii=False))
