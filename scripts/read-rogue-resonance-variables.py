"""只读匹配4.6变量setter、1221运行工厂和原始动作；真实战斗容器选择仍未接入。"""
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
PROFILE = ROOT / 'src/test/resources/fixtures/rogue-resonance-variables-4.6.json'
PROFILE_SHA256 = '6f1407a24211a781ed034de761a55599d9c7428ca62bbb7071a055d874611878'
spec = importlib.util.spec_from_file_location('variable_round', Path(__file__).with_name('read-rogue-resonance-round.py'))
rounding = importlib.util.module_from_spec(spec)
spec.loader.exec_module(rounding)


@lru_cache(maxsize=1)
def load_profile():
    raw = PROFILE.read_bytes().replace(b'\r\n', b'\n')
    if hashlib.sha256(raw).hexdigest() != PROFILE_SHA256:
        raise ValueError('变量动作的固定原生证据改变')
    return json.loads(raw)


def clamp(raw, lower, upper):
    for value in [raw, lower, upper]:
        if type(value) is not int or not -2**63 <= value < 2**63 or value & 1:
            raise ValueError('变量裁剪只接受标准有符号64位定点数')
    if raw < lower:
        return lower
    return upper if raw > upper else raw


def verify_native(client):
    profile = load_profile()
    rounding.verify_native(client)
    functions = rounding.functions
    characters = functions.sibling('variable_native_characters', 'read-rogue-resonance-character.py')
    counts = characters.verify_native(client, profile)
    analyzer = functions.sibling('variable_native_metadata', 'analyze-client-46.py')
    leaf_indices = list(dict.fromkeys(f['methodIndex'] for f in profile['nativeFragments']))
    identities = analyzer.extract(SimpleNamespace(client_dir=client,
        types=['FFKBNBIMMHE', 'DynamicValueContextScope'], type_contains=[],
        method_indices=[m['index'] for m in profile['nativeMethods']] + leaf_indices,
        method_rvas=[], method_name_contains=[],
        method_slots=[int(m['slot'], 16) for m in profile['methodInfoSlots']],
        string_slots=[], type_slots=[int(t['slot'], 16) for t in profile['typeInfoSlots']],
        fields=True, inheritance=False, field_offsets=True, disassemble=0, method_names=[]))
    expected = [{k: m[k] for k in ['index', 'name', 'rva', 'typeIndex', 'typeName']}
                for m in profile['nativeMethods']]
    for index in leaf_indices:
        fragment = next(f for f in profile['nativeFragments'] if f['methodIndex'] == index)
        expected.append({'index': index, 'name': fragment['methodName'], 'rva': fragment['rva'],
            'typeIndex': 9939 if index == 68532 else 10852,
            'typeName': 'FixPoint' if index == 68532 else 'CBIEMGIAPCC'})
    if (identities['selectedMethods'] != expected
            or identities['typeInfoSlots'] != sorted(profile['typeInfoSlots'], key=lambda t: int(t['slot'], 16))
            or identities['methodInfoSlots'] != profile['methodInfoSlots']):
        raise ValueError('变量任务/数值setter的原生身份或注册槽不符')
    interface = next(t for t in identities['methods'] if t['name'] == 'FFKBNBIMMHE')
    scope = next(t for t in identities['methods'] if t['name'] == 'DynamicValueContextScope')
    if interface != profile['variableInterface'] or scope['fields'] != profile['scopeConstants']:
        raise ValueError('变量接口方法顺序或上下文枚举不符')
    pe = pefile.PE(str(client / 'GameAssembly.dll'), fast_load=True)
    with (client / 'GameAssembly.dll').open('rb') as stream:
        for fragment in profile['nativeFragments']:
            address, size = int(fragment['rva'], 16), fragment['length']
            section = pe.get_section_by_rva(address)
            if section is None or address - section.VirtualAddress + size > section.SizeOfRawData:
                raise ValueError('变量裁剪片段没有原生文件范围')
            stream.seek(pe.get_offset_from_rva(address))
            raw = stream.read(size)
            if raw.hex() != fragment['bytesHex'] or hashlib.sha256(raw).hexdigest() != fragment['sha256']:
                raise ValueError('变量标准裁剪或原始cell访问片段不符')
    return counts


def verify_source_task(record):
    profile = load_profile()
    for key, expected in profile['sourceRecord'].items():
        if record.get(key) != expected:
            raise ValueError('原始舍入动作的能力记录身份不符')
    value = record['decoded']
    try:
        for step in profile['sourceTaskPath']:
            value = value[step]
        ability = record['decoded']['AbilityList'][2]
    except (KeyError, IndexError, TypeError) as error:
        raise ValueError('原始舍入动作路径不符') from error
    if (value != profile['sourceTask'] or ability['Name'] != profile['sourceAbilityName']
            or ability['DynamicValues'] != profile['sourceDynamicValues']
            or rounding.functions.native_name_key(value['DynamicKey']) != profile['dynamicKeyHash']):
        raise ValueError('原始1221动作、变量声明或名称哈希不符')
    config = rounding.functions.sibling('variable_source_abilities', 'read-rogue-resonance-abilities.py').load_profile()
    if config['bindings']['task'][str(value['taskId'])] != profile['configBinding']:
        raise ValueError('舍入动作与原生配置注册不符')
    return value.copy()


def analyze(args):
    client = args.client_dir.resolve()
    design = rounding.functions.sibling('variable_output_design', 'read-client-design-data.py')
    output = design.outside_client(args.output, client)
    if ROOT / 'build' not in output.parents:
        raise ValueError('只接受工程build目录下的变量静态证据')
    methods, size = verify_native(client)
    profile = load_profile()
    abilities = rounding.functions.sibling('variable_source_abilities', 'read-rogue-resonance-abilities.py')
    with tempfile.TemporaryDirectory(prefix='resonance-variable-', dir=ROOT / 'build') as temporary:
        base = abilities.analyze(SimpleNamespace(client_dir=client, output=Path(temporary) / 'abilities.json'))
        record = next(r for r in base['records'] if r['sourcePath'] == profile['sourceRecord']['sourcePath'])
        task = verify_source_task(record)
    report = {'passed': True, 'matchedNativeMethods': methods, 'matchedNativeBytes': size,
        'standardBranchAndCellInstructionBytes': profile['fragmentBytes'],
        'standardClampCompleteNativeMethod': False, 'sourceRecord': profile['sourceRecord'],
        'sourceTask': task, 'dynamicKeyHash': profile['dynamicKeyHash'],
        'originalAbilityRecordFullyRead': True, 'sourceTaskVariantMatchedToRuntimeFactory': True,
        'runtimeRegistration': profile['runtimeRegistration'],
        'explicitLookupStandardCellsImplemented': True, 'explicitLookupRoundTaskImplemented': True,
        'observerAfterClampedWrite': True, 'missingCellAutomaticallyCreated': False,
        'contextLookupSelectionImplemented': False, 'defaultCellBoundsResolved': False,
        'originalRoundTaskExecutedInBattle': False, 'liveBattleContextAttached': False,
        'abilityExecutionVerified': False, 'jsonInstalled': False,
        'clientExecutableStarted': False, 'clientModified': False,
        'clientGameplayVerified': False, 'allResonanceAbilitiesImplemented': False}
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n', 'utf-8')
    return report


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--client-dir', type=Path, required=True)
    parser.add_argument('--output', type=Path, default=ROOT / 'build/rogue-resonance-variables-evidence.json')
    report = analyze(parser.parse_args())
    print(json.dumps({k: report[k] for k in ['passed', 'matchedNativeMethods', 'matchedNativeBytes',
        'sourceTaskVariantMatchedToRuntimeFactory', 'contextLookupSelectionImplemented',
        'originalRoundTaskExecutedInBattle', 'clientGameplayVerified']}, ensure_ascii=False))
