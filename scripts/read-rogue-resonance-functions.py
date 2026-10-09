"""只读匹配固定 4.6 共鸣函数注册及两处 floor 调用，保留未求值变量。

名字和回调来自完整注册方法及元数据槽；不安装脚本、不执行客户端。
"""
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
PROFILE = ROOT / 'src/test/resources/fixtures/rogue-resonance-functions-4.6.json'
PROFILE_SHA256 = '4aee3cef1047c04acd8b7daf5b3e61a3232b3cc796566e9556b958fa5454ad6d'


def sibling(name, filename):
    spec = importlib.util.spec_from_file_location(name, Path(__file__).with_name(filename))
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


postfix = sibling('resonance_function_postfix', 'read-rogue-resonance-postfix.py')


@lru_cache(maxsize=1)
def load_profile():
    raw = PROFILE.read_bytes().replace(b'\r\n', b'\n')
    if hashlib.sha256(raw).hexdigest() != PROFILE_SHA256:
        raise ValueError('函数注册的固定原生证据已改变，需要重新核对')
    return json.loads(raw)


def native_name_key(name):
    """The observed ASCII names use alternating 32-bit accumulators."""
    if (not isinstance(name, str) or not 1 <= len(name) <= 64
            or any(not 1 <= ord(c) < 128 for c in name)):
        raise ValueError('仅接受已核对的非空 ASCII 函数名')
    h1 = h2 = 0x1505
    for index, char in enumerate(name):
        if index % 2 == 0:
            h1 = ((h1 * 33) ^ ord(char)) & 0xffffffff
        else:
            h2 = ((h2 * 33) ^ ord(char)) & 0xffffffff
    value = (h1 + h2 * 0x5d588b65) & 0xffffffff
    return value - (1 << 32) if value & (1 << 31) else value


def floor_raw(raw):
    """Integer translation of callback 74380, including extended normalization."""
    if type(raw) is not int or not -(1 << 63) <= raw < 1 << 63:
        raise ValueError('定点数必须是有符号 64 位整数')
    if raw & 1 == 0:
        return raw & -(1 << 33)
    integral_raw = raw & -(1 << 26)
    constants = load_profile()['floorConstants']
    if integral_raw <= -6710886399932891137:
        result = constants['minimumInitializer']['raw']
    elif integral_raw >= 6710886399932891137:
        result = constants['maximumInitializer']['raw']
    else:
        result = integral_raw + 1
    if result & 1 and abs(result >> 26) <= 0x3fffffff:
        return (result & ~1) << 7
    return result


def decode(program, constants, variable_keys):
    plan = postfix.decode(program, constants, variable_keys)
    profile = load_profile()
    registered = {item['hashKey']: item for item in profile['registrations']}

    def bind(node):
        if isinstance(node, dict):
            if node.get('op') == 'Call':
                key = node['callee']['variableKey']
                function = registered.get(key)
                if (function is None or function['name'] not in profile['acceptedCallNames']
                        or node['argumentCount'] not in profile['acceptedCallArgumentCounts']):
                    raise ValueError('表达式函数名称或参数尚未匹配')
                method = function['callbackMethod']
                node['function'] = {'name': function['name'], 'hashKey': key,
                                    'methodIndex': method['index'], 'methodRva': method['rva']}
            for child in node.values():
                bind(child)
        elif isinstance(node, list):
            for child in node:
                bind(child)

    bind(plan['tree'])
    plan['functionNamesResolved'] = True
    return plan


def verify_native(client):
    profile = load_profile()
    characters = sibling('resonance_function_characters', 'read-rogue-resonance-character.py')
    counts = characters.verify_native(client, profile)
    analyzer = sibling('resonance_function_metadata', 'analyze-client-46.py')
    source = analyzer.extract(SimpleNamespace(client_dir=client,
        types=[], type_contains=[], method_indices=[], method_rvas=[], method_name_contains=[],
        method_slots=[int(r['callbackMethodSlotRva'], 16) for r in profile['registrations']],
        string_slots=[int(r['literalSlotRva'], 16) for r in profile['registrations']], type_slots=[],
        fields=False, inheritance=False, field_offsets=False, disassemble=0, method_names=[]))
    literals = {row['slot']: row for row in source['stringLiteralSlots']}
    methods = {row['slot']: row['method'] for row in source['methodInfoSlots']}
    names, keys = set(), set()
    for item in profile['registrations']:
        if (literals.get(item['literalSlotRva']) != item['literal']
                or methods.get(item['callbackMethodSlotRva']) != item['callbackMethod']
                or item['name'] != item['literal']['value'] or native_name_key(item['name']) != item['hashKey']
                or item['name'] in names or item['hashKey'] in keys):
            raise ValueError('函数字面量、回调元数据、原生哈希或注册唯一性不符')
        names.add(item['name'])
        keys.add(item['hashKey'])
    constant = profile['floorConstants']['minimumInitializer']
    pe = pefile.PE(str(client / 'GameAssembly.dll'), fast_load=True)
    address = int(constant['sourceSlotRva'], 16)
    section = pe.get_section_by_rva(address)
    if section is None or address - section.VirtualAddress + constant['sourceLength'] > section.SizeOfRawData:
        raise ValueError('floor 饱和下限没有实际原生数据范围')
    with (client / 'GameAssembly.dll').open('rb') as stream:
        stream.seek(pe.get_offset_from_rva(address))
        raw = stream.read(constant['sourceLength'])
    if raw.hex() != constant['sourceBytesHex'] or struct.unpack_from('<q', raw)[0] != constant['raw']:
        raise ValueError('floor 饱和下限的原生初始化数据改变')
    return counts


def analyze(args):
    client = args.client_dir.resolve()
    design = sibling('resonance_function_design', 'read-client-design-data.py')
    output = design.outside_client(args.output, client)
    if ROOT / 'build' not in output.parents:
        raise ValueError('只接受工程 build/ 下的只读函数证据输出')
    methods, size = verify_native(client)
    profile = load_profile()
    abilities = sibling('resonance_function_abilities', 'read-rogue-resonance-abilities.py')
    parent = sibling('resonance_function_parent', 'read-rogue-resonance-shared-parent.py')
    (ROOT / 'build').mkdir(exist_ok=True)
    with tempfile.TemporaryDirectory(prefix='resonance-functions-', dir=ROOT / 'build') as temporary:
        work = Path(temporary)
        base = abilities.analyze(SimpleNamespace(client_dir=client, output=work / 'abilities.json'))
        shared = parent.analyze(SimpleNamespace(client_dir=client, output=work / 'parent.json'))
        records = base['records'] + [{'sourcePath': shared['sourceRecords'][0]['sourcePath'], 'decoded': shared['decoded']}]
        rows = postfix.expression_rows(records)
        for row in rows:
            row['plan'] = decode(bytes.fromhex(row['rawPostfixBytes']), row['fixedSignedConstants'], row['variableKeys'])
    calls = []
    for row in rows:
        def visit(node):
            if isinstance(node, dict):
                if node.get('op') == 'Call':
                    calls.append({'path': row['path'], 'function': node.get('function'), 'argumentCount': node['argumentCount']})
                for child in node.values():
                    visit(child)
            elif isinstance(node, list):
                for child in node:
                    visit(child)
        visit(row['plan']['tree'])
    if len(rows) != profile['sourceExpressionCount'] or len(calls) != profile['sourceCallCount']:
        raise ValueError('原始函数调用集合超出已匹配范围')
    report = {'passed': True, 'recordCount': len(records), 'abilityRecordBytes': base['recordBytes'] + shared['sourceRecords'][0]['length'],
        'expressionCount': len(rows), 'callCount': len(calls), 'registrations': profile['registrations'],
        'matchedNativeMethods': methods, 'matchedNativeBytes': size, 'calls': calls, 'expressions': rows,
        'floorStandaloneArithmeticImplemented': True, 'floorNumericContextAttached': False,
        'functionNamesResolved': True, 'variableContextResolved': False, 'expressionEvaluationVerified': False,
        'callImplementationsExecuted': False, 'jsonInstalled': False, 'clientExecutableStarted': False,
        'clientModified': False, 'clientGameplayVerified': False, 'allResonanceAbilitiesImplemented': False}
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n', 'utf-8')
    return report


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--client-dir', type=Path, required=True)
    parser.add_argument('--output', type=Path, default=ROOT / 'build/rogue-resonance-functions-evidence.json')
    report = analyze(parser.parse_args())
    print(json.dumps({key: report[key] for key in ['passed', 'recordCount', 'abilityRecordBytes',
        'expressionCount', 'callCount', 'matchedNativeMethods', 'matchedNativeBytes',
        'functionNamesResolved', 'expressionEvaluationVerified', 'jsonInstalled', 'clientGameplayVerified']}))
