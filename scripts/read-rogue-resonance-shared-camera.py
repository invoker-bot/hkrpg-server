"""只读解析固定国服 4.6 毁灭共鸣引用的共享镜头能力。

保留 76 条布局、584 字节能力及 15 个原始动作；不执行、不安装资源。
字段、偏移与 UInt16 类型标记依赖完整 PE 方法和固定原件证据。
"""
import argparse
import hashlib
import json
from pathlib import Path
import struct
import tempfile
from types import SimpleNamespace

ROOT = Path(__file__).resolve().parents[1]
PROFILE = ROOT / 'src/test/resources/fixtures/rogue-resonance-shared-camera-4.6.json'
PROFILE_SHA256 = '0226e0afabb27e9ed58d81cceefae0d45b57ecff71623c776b7d86d95ab1c3b1'

# Share the already frozen finite ability format without widening its original scope.
import importlib.util
spec = importlib.util.spec_from_file_location('resonance_shared_camera_abilities',
    Path(__file__).with_name('read-rogue-resonance-abilities.py'))
abilities = importlib.util.module_from_spec(spec)
spec.loader.exec_module(abilities)


def load_profile():
    raw = PROFILE.read_bytes().replace(b'\r\n', b'\n')
    if hashlib.sha256(raw).hexdigest() != PROFILE_SHA256:
        raise ValueError('共享镜头的固定原生/资源证据已改变，需要重新核对')
    return json.loads(raw)


def verify_native(client, profile=None):
    profile = profile if profile is not None else load_profile()
    counts = abilities.characters.verify_native(client, profile)
    metadata = (client / 'StarRail_Data/il2cpp_data/Metadata/global-metadata.dat').read_bytes()
    for constant in profile['layoutEnumConstants']:
        if constant['nativeKind'] != 7 or struct.unpack_from('<H', metadata,
                constant['metadataOffset'])[0] != constant['value']:
            raise ValueError('布局 UInt16 原始枚举常量不符')
    return counts


class LayoutReader(abilities.graphs.Reader):
    def __init__(self, raw, profile=None):
        super().__init__(raw, {})
        self.camera_profile = profile if profile is not None else load_profile()

    def _read(self, description):
        if description.get('name') != 'ConfigBakeLayoutInfo':
            return super()._read(description)
        start, mask = self.pos, self.uvar()
        if mask >> 4:
            raise ValueError('未知布局字段掩码')
        result = {'$offset': start, '$mask': hex(mask)}
        fields = self.camera_profile['layoutTypes'][0]['fields']
        for ordinal, field in enumerate(fields):
            if mask & (1 << ordinal):
                result[field['name']] = self.integer(16, False) if ordinal == 1 else self.read(field['nativeType'])
        return result

    def layout(self):
        mask = self.uvar()
        if mask != 1:
            raise ValueError('布局根字段缺失或未知')
        result = self.read({'kind': 29, 'elementType': {'kind': 18, 'name': 'ConfigBakeLayoutInfo'}})
        if self.pos != len(self.data):
            raise ValueError('布局记录未完整读取')
        if result is None or not result:
            raise ValueError('布局列表为空')
        return result


def camera_ability_profile(profile=None):
    profile = profile if profile is not None else load_profile()
    result = abilities.load_profile()
    for name, additions in profile['additionalFields'].items():
        result['schemas'][name]['allowedFields'].extend(additions)
    return result


def resolve_camera(raw, layouts, profile=None):
    profile = profile if profile is not None else load_profile()
    expected = profile['sharedAbility']
    if not isinstance(layouts, list) or not 0 < len(layouts) <= abilities.graphs.MAX_ARRAY:
        raise ValueError('布局列表缺失或超过读取边界')
    names, offsets = set(), set()
    types = {constant['value'] for constant in profile['layoutEnumConstants']}
    for layout in layouts:
        if not isinstance(layout, dict) or not {'UniqueName', 'TypeId', 'Offset'} <= layout.keys():
            raise ValueError('布局名称、类型或偏移缺失')
        name, kind, offset = layout['UniqueName'], layout['TypeId'], layout['Offset']
        if not isinstance(name, str) or not name or name in names:
            raise ValueError('布局名称为空或重复')
        if type(kind) is not int or kind not in types:
            raise ValueError('布局类型不是已核对的 UInt16 枚举')
        if type(offset) is not int or not 0 <= offset < len(raw) or offset in offsets:
            raise ValueError('布局偏移非法、越界或重复')
        names.add(name)
        offsets.add(offset)
    row = next((r for r in layouts if r.get('UniqueName') == expected['name']), None)
    if row is None:
        raise ValueError('共享镜头名称不在原始布局中')
    if row['TypeId'] != expected['typeId']:
        raise ValueError('共享镜头布局类型不是 TurnBasedAbilityConfig')
    if 'Dependency' in row:
        raise ValueError('共享镜头出现尚未解析的依赖')
    start = row['Offset']
    end = min((r['Offset'] for r in layouts if r.get('Offset', -1) > start), default=len(raw))
    if (start, end) != (expected['offset'], expected['endOffset']):
        raise ValueError('共享镜头有限布局边界不符')
    reader = abilities.Reader(raw[start:end], camera_ability_profile(profile))
    value = reader.read({'kind': 18, 'name': 'TurnBasedAbilityConfig'})
    sha = hashlib.sha256(raw[start:end]).hexdigest()
    if reader.pos != len(reader.data) or value.get('Name') != expected['name'] or sha != expected['sha256']:
        raise ValueError('共享镜头名称或能力字节边界不符')
    return {'name': expected['name'], 'offset': start, 'endOffset': end,
            'length': reader.pos, 'sha256': sha,
            'decoded': value, 'trace': reader.trace, 'fullyConsumed': True}


def bind_caller(caller, resolved):
    references = []
    for owner in caller.get('AbilityList', []):
        for index, action in enumerate(owner.get('OnStart', [])):
            name = action.get('AbilityName', {})
            if name.get('Value') != resolved['name']:
                continue
            target = action.get('TargetType', {})
            if (action.get('$type'), action.get('taskId'), name.get('$type'), name.get('$mask'),
                    target.get('$type'), target.get('$variantId'), target.get('Alias'),
                    action.get('IsSkillPerform')) != (
                    'TriggerAbility', 3573, 'DynamicString', '0x4', 'TargetAlias', 12, 'Caster', True):
                raise ValueError('调用端的任务、名称模式或目标不符')
            references.append({'callingAbility': owner['Name'], 'callback': 'OnStart',
                'actionOrdinal': index, 'taskId': 3573, 'targetAlias': 'Caster',
                'isSkillPerform': True, 'referencedAbility': resolved['name'],
                'callerObjectOffset': action['$offset'], 'resolvedDefinitionOffset': resolved['offset']})
    if len(references) != 1 or references[0]['callingAbility'] != 'Avatar_RogueBattleevent120_Skill03_Phase02':
        raise ValueError('共享镜头的有限调用关联缺失或重复')
    return references


def analyze(args):
    client = args.client_dir.resolve()
    design = abilities.sibling('resonance_shared_camera_design', 'read-client-design-data.py')
    output = design.outside_client(args.output, client)
    if ROOT / 'build' not in output.parents:
        raise ValueError('只接受工程 build/ 下的只读证据输出')
    profile = load_profile()
    methods, native_bytes = verify_native(client, profile)
    base = abilities.load_profile()
    base_methods, base_bytes = abilities.characters.verify_native(client, base)
    (ROOT / 'build').mkdir(exist_ok=True)
    with tempfile.TemporaryDirectory(prefix='resonance-camera-', dir=ROOT / 'build') as directory:
        work = Path(directory)
        source = design.extract(SimpleNamespace(client_dir=client, paths_file=None,
            paths=[r['sourcePath'] for r in profile['records']], extract_dir=work / 'raw',
            output=work / 'index.json'))
        if source['indexSHA256'] != profile['indexSHA256']:
            raise ValueError('镜头资源索引与原生证据不符')
        raw = {}
        for expected, actual in zip(profile['records'], source['records'], strict=True):
            if actual['status'] != 'read' or any(actual[k] != v for k, v in expected.items()):
                raise ValueError('共享镜头原始记录身份/长度/哈希不符')
            raw[expected['sourcePath']] = Path(actual['extractedFile']).read_bytes()
        source_path = profile['sharedAbility']['sourcePath']
        layouts = LayoutReader(raw[source_path[:-5] + '.layout.json'], profile).layout()
        camera = resolve_camera(raw[source_path], layouts, profile)
        caller_reader = abilities.Reader(raw[profile['callerRecord']['sourcePath']], base)
        caller = caller_reader.ability()
        references = bind_caller(caller, camera)
        if len(layouts) != profile['layoutEntryCount'] or any(camera[k] != profile['sharedAbility'][k]
                for k in ['name', 'offset', 'endOffset', 'length', 'sha256']):
            raise ValueError('共享镜头布局或有限能力身份不符')
    report = {'passed': True, 'recordCount': len(profile['records']),
              'recordBytes': sum(r['length'] for r in profile['records']),
              'parsedBytes': len(raw[source_path[:-5] + '.layout.json']) + caller_reader.pos + camera['length'],
              'parentAbilityRecordFullyDecoded': False,
              'matchedNativeMethods': methods, 'matchedNativeBytes': native_bytes,
              'baseNativeMethods': base_methods, 'baseNativeBytes': base_bytes,
              'layoutEnumConstantsMatched': len(profile['layoutEnumConstants']),
              'layouts': layouts, 'layoutFullyConsumed': True, 'camera': camera,
              'callerFullyConsumed': True, 'callerReferences': references,
              'taskCount': len(camera['decoded'].get('OnStart', [])),
              'sharedCameraDefinitionResolved': True, 'sourceRecords': profile['records'],
              'nativeFiles': profile['nativeFiles'], 'indexSHA256': source['indexSHA256'],
              'defaultsNotMaterialized': True, 'jsonInstalled': False,
              'abilityExecutionVerified': False, 'allResonanceAbilitiesImplemented': False,
              'clientGameplayVerified': False, 'clientExecutableStarted': False, 'clientModified': False}
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(report, ensure_ascii=False, indent=2, allow_nan=False) + '\n', 'utf-8')
    return report


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--client-dir', type=Path, required=True)
    parser.add_argument('--output', type=Path, default=ROOT / 'build/rogue-resonance-shared-camera-evidence.json')
    report = analyze(parser.parse_args())
    print(json.dumps({k: report[k] for k in ['passed', 'recordCount', 'recordBytes',
        'matchedNativeMethods', 'matchedNativeBytes', 'taskCount',
        'sharedCameraDefinitionResolved', 'jsonInstalled', 'clientGameplayVerified']}, ensure_ascii=False))
