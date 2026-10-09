"""只读核对4.6实体组件的MethodSpec参数类型及AITag栈顶读取，不获取活对象。"""
import argparse
from functools import lru_cache
import hashlib
import importlib.util
import json
import mmap
from pathlib import Path
import struct
import tempfile
from types import SimpleNamespace

import pefile

ROOT = Path(__file__).resolve().parents[1]
PROFILE = ROOT / 'src/test/resources/fixtures/rogue-resonance-components-4.6.json'
PROFILE_SHA256 = '7d591da13874971266dc21fc6d8e1ad356b1cbc05457911e96bf95f04468de7b'
spec = importlib.util.spec_from_file_location('component_entity', Path(__file__).with_name('read-rogue-resonance-entity-lookup.py'))
entity = importlib.util.module_from_spec(spec)
spec.loader.exec_module(entity)
functions = entity.functions


@lru_cache(maxsize=1)
def load_profile():
    raw = PROFILE.read_bytes().replace(b'\r\n', b'\n')
    if hashlib.sha256(raw).hexdigest() != PROFILE_SHA256:
        raise ValueError('组件与AITag原生证据改变')
    return json.loads(raw)


def read_rva(pe, address, size):
    section = pe.get_section_by_rva(address)
    if address < 0 or size < 0 or section is None or address - section.VirtualAddress + size > section.SizeOfRawData:
        raise ValueError('组件参数没有实际文件范围，拒绝虚拟零填充')
    raw = pe.get_data(address, size)
    if len(raw) != size:
        raise ValueError('组件参数文件字节截断')
    return raw


def read_inst(pe, index, array):
    if index < 0:
        raise ValueError('不读取缺省MethodSpec参数索引')
    address = array + index * 16
    raw = read_rva(pe, address, 16)
    count, reserved, pointer = struct.unpack('<IIQ', raw)
    if count != 1 or reserved != 0 or pointer < pe.OPTIONAL_HEADER.ImageBase:
        raise ValueError('不是已匹配的单参数泛型实例化')
    argv = pointer - pe.OPTIONAL_HEADER.ImageBase
    native_pointer = struct.unpack('<Q', read_rva(pe, argv, 8))[0]
    if native_pointer < pe.OPTIONAL_HEADER.ImageBase:
        raise ValueError('组件类型指针越界')
    type_rva = native_pointer - pe.OPTIONAL_HEADER.ImageBase
    native_type = read_rva(pe, type_rva, 8)
    definition = struct.unpack_from('<I', native_type)[0]
    if native_type[6] != 18 or definition >= 82342:
        raise ValueError('不是已匹配的组件类参数')
    return {'index': index, 'rva': hex(address), 'raw': raw.hex(), 'argCount': count,
        'unused': reserved, 'argv': hex(pointer), 'argumentIndexWordOffset': 4,
        'argvRva': hex(argv), 'argumentTypeRva': hex(type_rva),
        'argumentTypeBytesHex': native_type.hex(), 'argumentTypeIndex': definition, 'argumentTypeKind': 18}


def read_generic_slots(client, profile=None):
    profile = load_profile() if profile is None else profile
    pe = pefile.PE(str(client / 'GameAssembly.dll'), fast_load=True)
    registration = profile['genericInstantiationRegistration']
    array = struct.unpack('<Q', read_rva(pe, int(registration['pointerWordRva'], 16), 8))[0] - pe.OPTIONAL_HEADER.ImageBase
    if array != int(registration['arrayRva'], 16):
        raise ValueError('泛型实例化注册数组不符')
    header = read_rva(pe, 0x48b19b0, 0x208)
    word = lambda offset: struct.unpack_from('<I', header, offset)[0]
    pairs = ((word(0x190) + 0xed791073) & 0xffffffff) + 0x208
    literals = (word(0x1f0) ^ 0x56c7d20d) + 0x208
    specs = ((word(0x38) + 0xf778f1ab) & 0xffffffff) + 0x208
    wanted = {r['destination']: r for r in profile['genericMethodSlots']}
    found = {}
    with (client / 'StarRail_Data/il2cpp_data/Metadata/global-metadata.dat').open('rb') as stream, mmap.mmap(stream.fileno(), 0, access=mmap.ACCESS_READ) as data:
        if not 0 <= pairs < literals <= len(data):
            raise ValueError('方法使用表范围越界')
        for index in range((literals - pairs) // 8):
            first, second = struct.unpack_from('<II', data, pairs + index * 8)
            key = (((index * 0x87c3) ^ 0x5fa3fad3) * 0x334fb2ba + 0x454d10d89e6a02a) & ((1 << 64) - 1)
            key = (((key >> 14) * 0x102533d7 + 0x58972d9863a807) & ((1 << 64) - 1)) >> 23
            destination = ((first ^ 0x6907ab9a) - key) & 0xffffffff
            source = (second - key + 0xab3a6cb5) & 0xffffffff
            if destination not in wanted or source >> 29 != 6:
                continue  # Other usage kinds address independent type/string tables.
            payload = source & 0x1fffffff
            address = specs + payload * 12
            if address < 0 or address + 12 > len(data):
                raise ValueError('MethodSpec记录越界')
            definition, at4, at8 = struct.unpack_from('<iii', data, address)
            if definition != 502972 or at4 < 0 or at8 != -1:
                raise ValueError('不是已匹配的GetComponent参数形式')
            inst = read_inst(pe, at4, array)
            fact = {**{k: wanted[destination][k] for k in ['instructionRva', 'slot', 'destination']},
                'pairIndex': index, 'usageKind': 6, 'payload': payload,
                'methodSpec': {'methodDefinition': definition, 'argumentIndexAt4': at4, 'argumentIndexAt8': at8},
                'instRecords': [inst]}
            if destination in found and found[destination] != fact:
                raise ValueError('组件方法槽出现冲突初始化')
            if fact != wanted[destination]:
                raise ValueError('组件方法槽或参数类型改变')
            found[destination] = fact
    if set(found) != set(wanted):
        raise ValueError('组件方法槽未完全匹配')
    return [found[r['destination']] for r in profile['genericMethodSlots']]


def verify_native(client):
    profile = load_profile()
    entity.verify_native(client)
    characters = functions.sibling('component_native_characters', 'read-rogue-resonance-character.py')
    counts = characters.verify_native(client, profile)
    analyzer = functions.sibling('component_native_metadata', 'analyze-client-46.py')
    expected = [{k: m[k] for k in ['index', 'name', 'rva', 'typeIndex', 'typeName']} for m in profile['nativeMethods']]
    definition = profile['getComponentDefinition']
    actual = analyzer.extract(SimpleNamespace(client_dir=client,
        types=[t['name'] for t in profile['componentTypes']], type_contains=[],
        method_indices=[m['index'] for m in expected] + [definition['index']], method_rvas=[],
        method_name_contains=[], method_slots=[], string_slots=[], type_slots=[], fields=True,
        inheritance=True, field_offsets=True, disassemble=0, method_names=[]))
    if actual['selectedMethods'] != expected + [definition]:
        raise ValueError('组件方法定义或AITag读取实现的身份不符')
    types = {t['typeIndex']: t for t in actual['methods']}
    for fact in profile['componentTypes']:
        native = types.get(fact['typeIndex'])
        if native is None or native['name'] != fact['name'] or native['baseTypeChain'] != fact['baseTypeChain']:
            raise ValueError('组件或AITag容器继承关系不符')
        if fact['field'] not in native['instanceFields']:
            raise ValueError('组件容器字段偏移或原生类型不符')
    pe = pefile.PE(str(client / 'GameAssembly.dll'), fast_load=True)
    directory = pe.OPTIONAL_HEADER.DATA_DIRECTORY[pefile.DIRECTORY_ENTRY['IMAGE_DIRECTORY_ENTRY_EXCEPTION']]
    bounds = {a: (b, c) for a, b, c in struct.iter_unpack('<III', pe.get_data(directory.VirtualAddress, directory.Size))}
    for method in profile['nativeRuntimeFunctions']:
        if bounds.get(int(method['rva'], 16)) != (int(method['endRva'], 16), int(method['unwindRva'], 16)):
            raise ValueError('组件元数据初始化函数没有完整PE范围')
    for method in profile['nativeRuntimeFunctions'] + profile['nativeFragments']:
        raw = read_rva(pe, int(method['rva'], 16), len(bytes.fromhex(method['bytesHex'])))
        if raw.hex() != method['bytesHex'] or hashlib.sha256(raw).hexdigest() != method['sha256']:
            raise ValueError('组件元数据初始化原生字节不符')
    header = read_rva(pe, 0x48b19b0, 0x208)
    start = ((analyzer.word(header, 0x14c) + 0xf3a04294) & 0xffffffff) + 0x208
    with (client / 'StarRail_Data/il2cpp_data/Metadata/global-metadata.dat').open('rb') as stream, mmap.mmap(stream.fileno(), 0, access=mmap.ACCESS_READ) as data:
        for fact in profile['virtualMethodSlots']:
            encoded = struct.unpack_from('<H', data, start + fact['index'] * 26 + 12)[0]
            slot = (encoded ^ analyzer.method_key(fact['index']) ^ 0x9f7) & 0xffff
            if encoded != fact['encodedWord'] or slot != fact['decodedSlot']:
                raise ValueError('AITag覆写的读取虚方法槽不符')
    read_generic_slots(client)
    return counts


def analyze(args):
    client = args.client_dir.resolve()
    design = functions.sibling('component_output_design', 'read-client-design-data.py')
    output = design.outside_client(args.output, client)
    if ROOT / 'build' not in output.parents:
        raise ValueError('只接受工程build目录下的组件静态证据')
    methods, size = verify_native(client)
    profile = load_profile()
    abilities = functions.sibling('component_source_abilities', 'read-rogue-resonance-abilities.py')
    with tempfile.TemporaryDirectory(prefix='resonance-component-', dir=ROOT / 'build') as temporary:
        source = abilities.analyze(SimpleNamespace(client_dir=client, output=Path(temporary) / 'abilities.json'))
        record = next(r for r in source['records'] if r['sourcePath'] == profile['sourceRecord']['sourcePath'])
        inputs = entity.scoped.context.verify_source_inputs(record)
    report = {'passed': True, 'matchedNativeMethods': methods, 'matchedNativeBytes': size,
        'matchedNativeRuntimeFunctions': len(profile['nativeRuntimeFunctions']),
        'matchedNativeRuntimeFunctionBytes': profile['nativeRuntimeFunctionBytes'],
        'getterProviderAndRegistrationInstructionBytes': profile['fragmentBytes'],
        'missingReadInitializerBytes': 16, 'missingReadRaw': entity.read_missing_raw(client),
        'genericComponentSlots': read_generic_slots(client), 'componentTypes': profile['componentTypes'],
        'sourceRecord': profile['sourceRecord'], **inputs,
        'fixedAiTagStackProjectionImplemented': True, 'aitagTopValueBeforeBaseFallback': True,
        'aitagWriteReplacesStackTop': False, 'genericEntityComponentTypeArgumentsResolved': True,
        'genericSharedBodyIdentityResolved': False, 'sourceTagStacksRead': False,
        'tagPushPopImplemented': False, 'gettersCompleteNativeMethods': False,
        'liveSourceProjectionsResolved': False, 'contextLookupSelectionFullyImplemented': False,
        'actualParameterValuesResolved': False, 'nativeNumericProviderImplemented': False,
        'dynamicCellProvidersImplemented': False, 'liveCasterPropertiesRead': False,
        'liveBattleContextAttached': False, 'originalTaskBranchesExecutedInBattle': False,
        'abilityExecutionVerified': False, 'jsonInstalled': False, 'clientExecutableStarted': False,
        'clientModified': False, 'clientGameplayVerified': False, 'allResonanceAbilitiesImplemented': False}
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n', 'utf-8')
    return report


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--client-dir', type=Path, required=True)
    parser.add_argument('--output', type=Path, default=ROOT / 'build/rogue-resonance-components-evidence.json')
    report = analyze(parser.parse_args())
    print(json.dumps({k: report[k] for k in ['passed', 'matchedNativeMethods', 'matchedNativeBytes',
        'genericEntityComponentTypeArgumentsResolved', 'fixedAiTagStackProjectionImplemented',
        'liveBattleContextAttached', 'clientGameplayVerified']}, ensure_ascii=False))
