"""只读核对4.6 AITag缺失值初始化及实体查找重载；不获取真实战斗对象。"""
import argparse
from functools import lru_cache
import hashlib
import importlib.util
import json
from pathlib import Path
import struct
import tempfile
from types import SimpleNamespace

import pefile

ROOT = Path(__file__).resolve().parents[1]
PROFILE = ROOT / 'src/test/resources/fixtures/rogue-resonance-entity-lookup-4.6.json'
PROFILE_SHA256 = '2432944eb868aa85082f201f537bb0fe83a57699068ffe90f4faa2e61e29ee61'
spec = importlib.util.spec_from_file_location('entity_scoped', Path(__file__).with_name('read-rogue-resonance-scoped-lookup.py'))
scoped = importlib.util.module_from_spec(spec)
spec.loader.exec_module(scoped)
functions = scoped.functions


@lru_cache(maxsize=1)
def load_profile():
    raw = PROFILE.read_bytes().replace(b'\r\n', b'\n')
    if hashlib.sha256(raw).hexdigest() != PROFILE_SHA256:
        raise ValueError('实体查找固定原生证据改变')
    return json.loads(raw)


def read_missing_raw(client):
    fact = load_profile()['missingReadInitializer']
    pe = pefile.PE(str(client / 'GameAssembly.dll'), fast_load=True)
    address = int(fact['rva'], 16)
    section = pe.get_section_by_rva(address)
    if section is None or address - section.VirtualAddress + 16 > section.SizeOfRawData:
        raise ValueError('缺失值初始化器没有实际文件数据；不能使用虚拟零填充作为证据')
    raw = pe.get_data(address, 16)
    if raw.hex() != fact['bytesHex'] or hashlib.sha256(raw).hexdigest() != fact['sha256']:
        raise ValueError('缺失值初始化器的数据改变')
    word = struct.unpack_from('<q', raw, fact['missingWordOffset'])[0]
    if word != fact['missingRaw']:
        raise ValueError('缺失值的原生FixPoint字不符')
    return word


def verify_native(client):
    profile = load_profile()
    scoped.verify_native(client)
    characters = functions.sibling('entity_native_characters', 'read-rogue-resonance-character.py')
    counts = characters.verify_native(client, profile)
    analyzer = functions.sibling('entity_native_metadata', 'analyze-client-46.py')
    identities = profile['nativeMethods']
    actual = analyzer.extract(SimpleNamespace(client_dir=client, types=[], type_contains=[],
        method_indices=[m['index'] for m in identities], method_rvas=[], method_name_contains=[],
        method_slots=[], string_slots=[], type_slots=[], fields=False, inheritance=False,
        field_offsets=False, disassemble=0, method_names=[]))
    expected = [{k: m[k] for k in ['index', 'name', 'rva', 'typeIndex', 'typeName']} for m in identities]
    if actual['selectedMethods'] != expected:
        raise ValueError('FixPoint初始化器或实体查找重载的元数据身份不符')
    read_missing_raw(client)
    return counts


def analyze(args):
    client = args.client_dir.resolve()
    design = functions.sibling('entity_output_design', 'read-client-design-data.py')
    output = design.outside_client(args.output, client)
    if ROOT / 'build' not in output.parents:
        raise ValueError('只接受工程build目录下的实体静态证据')
    methods, size = verify_native(client)
    profile = load_profile()
    abilities = functions.sibling('entity_source_abilities', 'read-rogue-resonance-abilities.py')
    with tempfile.TemporaryDirectory(prefix='resonance-entity-', dir=ROOT / 'build') as temporary:
        source = abilities.analyze(SimpleNamespace(client_dir=client, output=Path(temporary) / 'abilities.json'))
        record = next(r for r in source['records'] if r['sourcePath'] == profile['sourceRecord']['sourcePath'])
        inputs = scoped.context.verify_source_inputs(record)
    report = {'passed': True, 'matchedNativeMethods': methods, 'matchedNativeBytes': size,
        'matchedNativeRuntimeFunctions': len(profile['nativeRuntimeFunctions']),
        'matchedNativeRuntimeFunctionBytes': profile['nativeRuntimeFunctionBytes'],
        'getterAndProviderInstructionBytes': profile['fragmentBytes'], 'gettersCompleteNativeMethods': False,
        'missingReadInitializerBytes': 16, 'missingReadRaw': read_missing_raw(client),
        'virtualMethodSlots': scoped.read_native_slots(client), 'sourceRecord': profile['sourceRecord'], **inputs,
        'supportedProjectedScopes': profile['supportedScopes'], 'supportedProjectedEntityScopes': profile['supportedEntityScopes'],
        'fixedProjectedContextTailImplemented': True, 'fixedEntityLookupImplemented': True,
        'aitagMissingValueRepresentationResolved': True, 'entitySelectorChecksKeyPresence': False,
        'expressionEvaluatedBeforeEntitySelection': True, 'previousWritesRetainedAfterSelectionFailure': True,
        'contextTargetScopeSupported': False, 'liveSourceProjectionsResolved': False,
        'genericEntityComponentTypeArgumentsResolved': False, 'contextLookupSelectionFullyImplemented': False,
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
    parser.add_argument('--output', type=Path, default=ROOT / 'build/rogue-resonance-entity-lookup-evidence.json')
    report = analyze(parser.parse_args())
    print(json.dumps({k: report[k] for k in ['passed', 'matchedNativeMethods', 'matchedNativeBytes',
        'missingReadRaw', 'fixedEntityLookupImplemented', 'liveSourceProjectionsResolved', 'clientGameplayVerified']}, ensure_ascii=False))
