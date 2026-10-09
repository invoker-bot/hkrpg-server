"""只读核对4.6虚方法槽和固定容器投影的查找后段；不建立真实对象/参数绑定。"""
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
PROFILE = ROOT / 'src/test/resources/fixtures/rogue-resonance-scoped-lookup-4.6.json'
PROFILE_SHA256 = '15b717de045ad20e743c04dcc625e113ab2d0ccab6fee5252a55cbbb357e445e'
spec = importlib.util.spec_from_file_location('scoped_context', Path(__file__).with_name('read-rogue-resonance-context.py'))
context = importlib.util.module_from_spec(spec)
spec.loader.exec_module(context)
functions = context.functions


@lru_cache(maxsize=1)
def load_profile():
    raw = PROFILE.read_bytes().replace(b'\r\n', b'\n')
    if hashlib.sha256(raw).hexdigest() != PROFILE_SHA256:
        raise ValueError('作用域查找后段的固定原生证据改变')
    return json.loads(raw)


def read_native_slots(client):
    profile = load_profile()
    analyzer = functions.sibling('scoped_slot_metadata', 'analyze-client-46.py')
    pe = pefile.PE(str(client / 'GameAssembly.dll'), fast_load=True)
    header = pe.get_data(0x48b19b0, 0x208)
    start = ((analyzer.word(header, 0x14c) + 0xf3a04294) & 0xffffffff) + 0x208
    constant = profile['slotXorConstant']
    raw = pe.get_data(int(constant['rva'], 16), 16)
    if raw.hex() != constant['bytesHex'] or hashlib.sha256(raw).hexdigest() != constant['sha256']:
        raise ValueError('虚方法槽解码常量改变')
    word = struct.unpack_from('<H', raw, 2)[0]
    if word != constant['xorWord']:
        raise ValueError('虚方法槽与SetupMethods的SSE换序常量不符')
    path = client / 'StarRail_Data/il2cpp_data/Metadata/global-metadata.dat'
    rows = []
    with path.open('rb') as stream, mmap.mmap(stream.fileno(), 0, access=mmap.ACCESS_READ) as data:
        for fact in profile['virtualMethodSlots']:
            offset = start + fact['index'] * 26 + int(constant['methodDefinitionWordOffset'], 16)
            if offset < 0 or offset + 2 > len(data):
                raise ValueError('虚方法槽定义超出元数据范围')
            encoded = struct.unpack_from('<H', data, offset)[0]
            slot = (encoded ^ analyzer.method_key(fact['index']) ^ word) & 0xffff
            if encoded != fact['encodedWord'] or slot != fact['decodedSlot']:
                raise ValueError('OwnerEntity、OwnerAbility或固定值读取的虚方法槽不符')
            rows.append({'methodIndex': fact['index'], 'slot': slot,
                'dispatchClassOffset': hex(0xc0 + slot * 8) if fact['dispatchInstructionRva'] else None})
    return rows


def verify_native(client):
    profile = load_profile()
    context.verify_native(client)
    characters = functions.sibling('scoped_native_characters', 'read-rogue-resonance-character.py')
    counts = characters.verify_native(client, profile)
    analyzer = functions.sibling('scoped_native_metadata', 'analyze-client-46.py')
    identities = profile['nativeMethods'] + profile['nativeFragments']
    indices = list(dict.fromkeys([m['index'] for m in identities] + [m['index'] for m in profile['virtualMethodSlots']]))
    actual = analyzer.extract(SimpleNamespace(client_dir=client, types=[], type_contains=[], method_indices=indices,
        method_rvas=[], method_name_contains=[], method_slots=[], string_slots=[], type_slots=[],
        fields=False, inheritance=False, field_offsets=False, disassemble=0, method_names=[]))
    expected = [{k: m[k] for k in ['index', 'name', 'rva', 'typeIndex', 'typeName']} for m in identities]
    seen = {m['index'] for m in identities}
    expected += [{k: m[k] for k in ['index', 'name', 'rva', 'typeIndex', 'typeName']}
                 for m in profile['virtualMethodSlots'] if m['index'] not in seen]
    if actual['selectedMethods'] != expected:
        raise ValueError('固定值读取、Owner自定义数据或虚方法getter的身份不符')
    pe = pefile.PE(str(client / 'GameAssembly.dll'), fast_load=True)
    directory = pe.OPTIONAL_HEADER.DATA_DIRECTORY[pefile.DIRECTORY_ENTRY['IMAGE_DIRECTORY_ENTRY_EXCEPTION']]
    bounds = {start: (end, unwind) for start, end, unwind in struct.iter_unpack('<III', pe.get_data(directory.VirtualAddress, directory.Size))}
    for method in profile['nativeRuntimeFunctions']:
        if bounds.get(int(method['rva'], 16)) != (int(method['endRva'], 16), int(method['unwindRva'], 16)):
            raise ValueError('原生SetupMethods没有匹配的完整PE范围')
    with (client / 'GameAssembly.dll').open('rb') as stream:
        for method in profile['nativeFragments'] + profile['nativeRuntimeFunctions']:
            address = int(method['rva'], 16)
            size = method.get('length', len(bytes.fromhex(method['bytesHex'])))
            section = pe.get_section_by_rva(address)
            if section is None or address - section.VirtualAddress + size > section.SizeOfRawData:
                raise ValueError('作用域getter或SetupMethods没有实际原生文件范围')
            stream.seek(pe.get_offset_from_rva(address))
            raw = stream.read(size)
            if raw.hex() != method['bytesHex'] or hashlib.sha256(raw).hexdigest() != method['sha256']:
                raise ValueError('作用域getter或SetupMethods原生字节不符')
    read_native_slots(client)
    return counts


def analyze(args):
    client = args.client_dir.resolve()
    design = functions.sibling('scoped_output_design', 'read-client-design-data.py')
    output = design.outside_client(args.output, client)
    if ROOT / 'build' not in output.parents:
        raise ValueError('只接受工程build目录下的作用域静态证据')
    methods, size = verify_native(client)
    profile = load_profile()
    abilities = functions.sibling('scoped_source_abilities', 'read-rogue-resonance-abilities.py')
    with tempfile.TemporaryDirectory(prefix='resonance-scoped-', dir=ROOT / 'build') as temporary:
        source = abilities.analyze(SimpleNamespace(client_dir=client, output=Path(temporary) / 'abilities.json'))
        record = next(r for r in source['records'] if r['sourcePath'] == profile['sourceRecord']['sourcePath'])
        inputs = context.verify_source_inputs(record)
    report = {'passed': True, 'matchedNativeMethods': methods, 'matchedNativeBytes': size,
        'matchedNativeRuntimeFunctions': len(profile['nativeRuntimeFunctions']),
        'matchedNativeRuntimeFunctionBytes': profile['nativeRuntimeFunctionBytes'],
        'getterAndProviderInstructionBytes': profile['fragmentBytes'], 'gettersCompleteNativeMethods': False,
        'virtualMethodSlots': read_native_slots(client), 'sourceRecord': profile['sourceRecord'], **inputs,
        'supportedProjectedScopes': profile['supportedScopes'], 'fixedProjectedContextTailImplemented': True,
        'abilityPresenceChangesDefaultLookup': True, 'sameSummonerOwnerIdentitySkipsFallback': True,
        'explicitSummonerScopeAllowsGlobalAliases': False, 'unopenedScopes': profile['unopenedScopes'],
        'liveSourceProjectionsResolved': False, 'genericEntityComponentTypeArgumentsResolved': False,
        'aitagMissingValueRepresentationResolved': False, 'contextLookupSelectionFullyImplemented': False,
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
    parser.add_argument('--output', type=Path, default=ROOT / 'build/rogue-resonance-scoped-lookup-evidence.json')
    report = analyze(parser.parse_args())
    print(json.dumps({k: report[k] for k in ['passed', 'matchedNativeMethods', 'matchedNativeBytes',
        'fixedProjectedContextTailImplemented', 'liveSourceProjectionsResolved', 'clientGameplayVerified']}, ensure_ascii=False))
