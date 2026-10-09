"""只读核对4.6固定变量的未序列化范围默认值；不打开扩展变量或真实战斗上下文。"""
import argparse
from functools import lru_cache
import hashlib
import importlib.util
import json
from pathlib import Path
import tempfile
from types import SimpleNamespace

ROOT = Path(__file__).resolve().parents[1]
PROFILE = ROOT / 'src/test/resources/fixtures/rogue-resonance-defaults-4.6.json'
PROFILE_SHA256 = 'e151c29b6faf31fb1bb8e17fadf8f8f8e03d859b2e5905345d150ba8691243eb'
spec = importlib.util.spec_from_file_location('default_write', Path(__file__).with_name('read-rogue-resonance-write.py'))
write = importlib.util.module_from_spec(spec)
spec.loader.exec_module(write)
functions = write.functions


@lru_cache(maxsize=1)
def load_profile():
    raw = PROFILE.read_bytes().replace(b'\r\n', b'\n')
    if hashlib.sha256(raw).hexdigest() != PROFILE_SHA256:
        raise ValueError('默认变量范围的固定原生证据改变')
    return json.loads(raw)


def verify_native(client):
    profile = load_profile()
    write.verify_native(client)
    characters = functions.sibling('default_native_characters', 'read-rogue-resonance-character.py')
    counts = characters.verify_native(client, profile)
    analyzer = functions.sibling('default_native_metadata', 'analyze-client-46.py')
    identities = analyzer.extract(SimpleNamespace(client_dir=client, types=[], type_contains=[],
        method_indices=[m['index'] for m in profile['nativeMethods']], method_rvas=[],
        method_name_contains=[], method_slots=[], string_slots=[], type_slots=[],
        fields=False, inheritance=False, field_offsets=False, disassemble=0, method_names=[]))
    expected = [{k: m[k] for k in ['index', 'name', 'rva', 'typeIndex', 'typeName']}
                for m in profile['nativeMethods']]
    if identities['selectedMethods'] != expected:
        raise ValueError('默认变量二进制读取器、cell创建器或静态常量的原生身份不符')
    if profile['minimumInitializer'] != functions.load_profile()['floorConstants']['minimumInitializer']:
        raise ValueError('默认范围下限与已核对的原生静态初始化不符')
    return counts


def verify_source_declarations(record):
    profile = load_profile()
    if any(record.get(k) != v for k, v in profile['sourceRecord'].items()):
        raise ValueError('固定变量声明的原始记录身份不符')
    try:
        ability = record['decoded']['AbilityList'][2]
        declarations = ability['DynamicValues']
    except (KeyError, IndexError, TypeError) as error:
        raise ValueError('固定变量声明的原始路径不符') from error
    if ability.get('Name') != profile['sourceAbilityName'] or declarations != profile['sourceDynamicValues']:
        raise ValueError('原始固定变量声明、读取模式或范围改变')
    result = {}
    for row in declarations['floats']:
        key, raw = row['key'], row['fixedSignedRaw']
        if (type(key) is not int or not -2**31 <= key < 2**31 or key in result
                or type(raw) is not int or not -2**63 <= raw < 2**63 or raw & 1
                or row['rawReadType'] != 0 or set(row) != {'key', 'fixedSignedRaw', 'rawReadType'}):
            raise ValueError('只接受已核对的标准固定变量和未序列化范围')
        result[key] = raw
    return result


def analyze(args):
    client = args.client_dir.resolve()
    design = functions.sibling('default_output_design', 'read-client-design-data.py')
    output = design.outside_client(args.output, client)
    if ROOT / 'build' not in output.parents:
        raise ValueError('只接受工程build目录下的默认变量静态证据')
    methods, size = verify_native(client)
    profile = load_profile()
    abilities = functions.sibling('default_source_abilities', 'read-rogue-resonance-abilities.py')
    with tempfile.TemporaryDirectory(prefix='resonance-default-', dir=ROOT / 'build') as temporary:
        source = abilities.analyze(SimpleNamespace(client_dir=client, output=Path(temporary) / 'abilities.json'))
        record = next(r for r in source['records'] if r['sourcePath'] == profile['sourceRecord']['sourcePath'])
        declarations = verify_source_declarations(record)
    report = {'passed': True, 'matchedNativeMethods': methods, 'matchedNativeBytes': size,
        'sourceRecord': profile['sourceRecord'], 'sourceFixedDeclarations': declarations,
        'nativeDefaultMinimumRaw': profile['minimumInitializer']['raw'],
        'nativeDefaultMaximumRaw': profile['maximumInitializer']['raw'],
        'defaultSourceStandardCellsImplemented': True, 'standardValuesStrictlyInsideNativeBounds': True,
        'mixedClampImplemented': False, 'extendedCellsImplemented': False,
        'serializedCustomRangesAcceptedBySourceFactory': False,
        'contextLookupSelectionImplemented': False, 'actualParameterValuesResolved': False,
        'liveBattleContextAttached': False, 'abilityExecutionVerified': False,
        'jsonInstalled': False, 'clientExecutableStarted': False, 'clientModified': False,
        'clientGameplayVerified': False, 'allResonanceAbilitiesImplemented': False}
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n', 'utf-8')
    return report


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--client-dir', type=Path, required=True)
    parser.add_argument('--output', type=Path, default=ROOT / 'build/rogue-resonance-defaults-evidence.json')
    report = analyze(parser.parse_args())
    print(json.dumps({k: report[k] for k in ['passed', 'matchedNativeMethods', 'matchedNativeBytes',
        'defaultSourceStandardCellsImplemented', 'clientGameplayVerified']}, ensure_ascii=False))
