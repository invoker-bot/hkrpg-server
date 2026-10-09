"""只读核对4.6上下文查找前段与逐键求值；原生provider和查找后段未实现。"""
import argparse
from functools import lru_cache
import hashlib
import importlib.util
import json
from pathlib import Path
import tempfile
from types import SimpleNamespace

import pefile

ROOT = Path(__file__).resolve().parents[1]
PROFILE = ROOT / 'src/test/resources/fixtures/rogue-resonance-context-4.6.json'
PROFILE_SHA256 = '74cab0f7ae53f4ccf116471d39272466cfd70cb083672d7968e8567cb0e1045c'
spec = importlib.util.spec_from_file_location('context_defaults', Path(__file__).with_name('read-rogue-resonance-defaults.py'))
defaults = importlib.util.module_from_spec(spec)
spec.loader.exec_module(defaults)
functions = defaults.functions


@lru_cache(maxsize=1)
def load_profile():
    raw = PROFILE.read_bytes().replace(b'\r\n', b'\n')
    if hashlib.sha256(raw).hexdigest() != PROFILE_SHA256:
        raise ValueError('上下文前段的固定原生证据改变')
    return json.loads(raw)


def verify_native(client):
    profile = load_profile()
    defaults.verify_native(client)
    characters = functions.sibling('context_native_characters', 'read-rogue-resonance-character.py')
    counts = characters.verify_native(client, profile)
    analyzer = functions.sibling('context_native_metadata', 'analyze-client-46.py')
    actual = analyzer.extract(SimpleNamespace(client_dir=client,
        types=['TaskContext', 'GAGIJDJFKAB', 'GameWorld', 'AELMAJAHPCP', 'DynamicValueContextScope'],
        type_contains=[], method_indices=[m['index'] for m in profile['nativeMethods']] + [509824],
        method_rvas=[], method_name_contains=[], method_slots=[], string_slots=[],
        type_slots=[int(t['slot'], 16) for t in profile['typeInfoSlots']],
        fields=True, inheritance=True, field_offsets=True, disassemble=0, method_names=[]))
    expected = [{k: m[k] for k in ['index', 'name', 'rva', 'typeIndex', 'typeName']}
                for m in profile['nativeMethods'] + profile['nativeFragments']]
    if (actual['selectedMethods'] != expected
            or actual['typeInfoSlots'] != sorted(profile['typeInfoSlots'], key=lambda t: t['slot'])):
        raise ValueError('上下文查找、逐键provider或原生接口身份不符')
    for fact in profile['nativeFieldFacts']:
        owner = next(t for t in actual['methods'] if t['name'] == fact['typeName'])
        if next(f for f in owner['fields'] if f['name'] == fact['field']['name']) != fact['field']:
            raise ValueError('参数栈、模板、Modifier或回调参数字段不符')
    for name in ['templateInterface', 'numericProviderInterface']:
        expected_type = profile[name]
        current = next(t for t in actual['methods'] if t['typeIndex'] == expected_type['typeIndex'])
        if any(current[k] != expected_type[k] for k in ['typeIndex', 'name', 'methods']):
            raise ValueError('模板或逐键provider接口方法顺序不符')
    scope = next(t for t in actual['methods'] if t['name'] == 'DynamicValueContextScope')
    if scope['fields'] != profile['scopeConstants']:
        raise ValueError('上下文原始作用域常量不符')
    pe = pefile.PE(str(client / 'GameAssembly.dll'), fast_load=True)
    with (client / 'GameAssembly.dll').open('rb') as stream:
        for fragment in profile['nativeFragments']:
            address, size = int(fragment['rva'], 16), fragment['length']
            section = pe.get_section_by_rva(address)
            if section is None or address - section.VirtualAddress + size > section.SizeOfRawData:
                raise ValueError('逐键provider指令没有实际原生文件范围')
            stream.seek(pe.get_offset_from_rva(address))
            raw = stream.read(size)
            if raw.hex() != fragment['bytesHex'] or hashlib.sha256(raw).hexdigest() != fragment['sha256']:
                raise ValueError('逐键provider指令片段不符')
    return counts


def verify_source_inputs(record):
    profile = load_profile()
    if any(record.get(k) != v for k, v in profile['sourceRecord'].items()):
        raise ValueError('上下文隔离示例的原始记录身份不符')
    return {'fixedDeclarations': defaults.verify_source_declarations(record),
            'writeTasks': defaults.write.verify_source_tasks(record)}


def analyze(args):
    client = args.client_dir.resolve()
    design = functions.sibling('context_output_design', 'read-client-design-data.py')
    output = design.outside_client(args.output, client)
    if ROOT / 'build' not in output.parents:
        raise ValueError('只接受工程build目录下的上下文静态证据')
    methods, size = verify_native(client)
    profile = load_profile()
    abilities = functions.sibling('context_source_abilities', 'read-rogue-resonance-abilities.py')
    with tempfile.TemporaryDirectory(prefix='resonance-context-', dir=ROOT / 'build') as temporary:
        source = abilities.analyze(SimpleNamespace(client_dir=client, output=Path(temporary) / 'abilities.json'))
        record = next(r for r in source['records'] if r['sourcePath'] == profile['sourceRecord']['sourcePath'])
        inputs = verify_source_inputs(record)
    report = {'passed': True, 'matchedNativeMethods': methods, 'matchedNativeBytes': size,
        'providerInstructionBytes': sum(f['length'] for f in profile['nativeFragments']),
        'providerFragmentCompleteNativeMethod': False, 'matchedNativeFieldFacts': len(profile['nativeFieldFacts']),
        'sourceRecord': profile['sourceRecord'], **inputs, 'supportedPrefixScopes': profile['supportedScopes'],
        'matchedDefaultPrefixOrder': profile['matchedDefaultPrefixOrder'],
        'explicitPrefixContextImplemented': True, 'numericResolverImplemented': True,
        'nativeContextTailImplemented': False, 'nativeNumericProviderImplemented': False,
        'dynamicCellProvidersImplemented': False, 'actualSourceDeclarationsBoundToTemplate': False,
        'contextLookupSelectionFullyImplemented': False, 'actualParameterValuesResolved': False,
        'liveCasterPropertiesRead': False, 'liveBattleContextAttached': False,
        'originalTaskBranchesExecutedInBattle': False, 'abilityExecutionVerified': False,
        'jsonInstalled': False, 'clientExecutableStarted': False, 'clientModified': False,
        'clientGameplayVerified': False, 'allResonanceAbilitiesImplemented': False}
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n', 'utf-8')
    return report


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--client-dir', type=Path, required=True)
    parser.add_argument('--output', type=Path, default=ROOT / 'build/rogue-resonance-context-evidence.json')
    report = analyze(parser.parse_args())
    print(json.dumps({k: report[k] for k in ['passed', 'matchedNativeMethods', 'matchedNativeBytes',
        'explicitPrefixContextImplemented', 'nativeContextTailImplemented', 'clientGameplayVerified']}, ensure_ascii=False))
