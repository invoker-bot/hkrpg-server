"""匹配两条原始 floor(Add/Divide) 程序及有限标准定点数求值范围。

调用者显式提供数值；不推定变量来源、不安装能力或执行客户端。
"""
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
PROFILE = ROOT / 'src/test/resources/fixtures/rogue-resonance-floor-programs-4.6.json'
PROFILE_SHA256 = 'b0044406cb7d58fb18f0f292acd1a4a1e73b15e2b48937aa8696878736170f06'
spec = importlib.util.spec_from_file_location('floor_program_functions', Path(__file__).with_name('read-rogue-resonance-functions.py'))
functions = importlib.util.module_from_spec(spec)
spec.loader.exec_module(functions)


@lru_cache(maxsize=1)
def load_profile():
    raw = PROFILE.read_bytes().replace(b'\r\n', b'\n')
    if hashlib.sha256(raw).hexdigest() != PROFILE_SHA256:
        raise ValueError('floor 运算和原始程序的固定证据已改变')
    return json.loads(raw)


def compile_program(program, constants, variable_keys):
    plan = functions.decode(program, constants, variable_keys)

    def validate(node):
        if node['op'] not in ['Constant', 'GetGlobal', 'Add', 'Divide', 'Call']:
            raise ValueError('数值操作码尚未匹配')
        if node['op'] in ['Add', 'Divide']:
            validate(node['left'])
            validate(node['right'])
        elif node['op'] == 'Call':
            validate(node['arguments'][0])

    validate(plan['tree'])
    return plan


def standard(raw):
    if type(raw) is not int or not -(1 << 63) <= raw < 1 << 63 or raw & 1:
        raise ValueError('尚未匹配的定点数类型、位宽或扩展数值输入')
    return raw


def numeric_result(raw):
    if not -(1 << 63) <= raw < 1 << 63 or abs(raw >> 33) >= 0x40000000:
        raise ValueError('数值运算的扩展结果尚未匹配')
    return standard(raw)


def evaluate(program, constants, variable_keys, inputs):
    plan = compile_program(program, constants, variable_keys)
    if (not isinstance(inputs, dict) or len(inputs) > 255 or any(type(key) is not int
            or not -(1 << 31) <= key < 1 << 31 for key in inputs)):
        raise ValueError('数值上下文类型、数量或变量键位宽不符')
    values = {key: standard(value) for key, value in inputs.items()}

    def value(node):
        if node['op'] == 'Constant':
            return standard(node['fixedSignedRaw'])
        if node['op'] == 'GetGlobal':
            key = node['variableKey']
            if key == 1776456860 or key not in values:
                raise ValueError('数值变量上下文未解析')
            return values[key]
        if node['op'] in ['Add', 'Divide']:
            a, b = value(node['left']), value(node['right'])
            if node['op'] == 'Add':
                return numeric_result(a + b)
            if b == 0:
                raise ValueError('定点数除数不能为零')
            quotient, remainder = divmod(abs(a >> 1) << 32, abs(b >> 1))
            if remainder * 2 >= abs(b >> 1):
                quotient += 1
            return numeric_result((quotient if (a < 0) == (b < 0) else -quotient) << 1)
        return functions.floor_raw(value(node['arguments'][0]))

    return value(plan['tree'])


def verify_native(client):
    profile = load_profile()
    characters = functions.sibling('floor_numeric_character', 'read-rogue-resonance-character.py')
    counts = characters.verify_native(client, profile)
    analyzer = functions.sibling('floor_numeric_metadata', 'analyze-client-46.py')
    source = analyzer.extract(SimpleNamespace(client_dir=client, types=[], type_contains=[],
        method_indices=[profile['standardAdditionMethodIndex']], method_rvas=[], method_name_contains=[],
        method_slots=[], string_slots=[], type_slots=[], fields=False, inheritance=False,
        field_offsets=False, disassemble=0, method_names=[]))
    expected = {'index': 68467, 'name': 'op_Addition', 'rva': '0x1dcc86e0', 'typeIndex': 9939, 'typeName': 'FixPoint'}
    if source['selectedMethods'] != [expected]:
        raise ValueError('标准加法原始方法身份不符')
    pe = pefile.PE(str(client / 'GameAssembly.dll'), fast_load=True)
    with (client / 'GameAssembly.dll').open('rb') as stream:
        for fragment in profile['standardAdditionFragments']:
            start, length = int(fragment['rva'], 16), fragment['length']
            section = pe.get_section_by_rva(start)
            if section is None or start - section.VirtualAddress + length > section.SizeOfRawData:
                raise ValueError('标准加法片段无实际文件范围')
            stream.seek(pe.get_offset_from_rva(start))
            raw = stream.read(length)
            if raw.hex() != fragment['bytesHex'] or hashlib.sha256(raw).hexdigest() != fragment['sha256']:
                raise ValueError('标准加法片段的固定字节改变')
    return counts


def analyze(args):
    client = args.client_dir.resolve()
    design = functions.sibling('floor_numeric_design', 'read-client-design-data.py')
    output = design.outside_client(args.output, client)
    if ROOT / 'build' not in output.parents:
        raise ValueError('只接受工程 build/ 下的只读 floor 程序证据输出')
    profile = load_profile()
    methods, size = verify_native(client)
    with tempfile.TemporaryDirectory(prefix='floor-numeric-', dir=ROOT / 'build') as directory:
        source = functions.analyze(SimpleNamespace(client_dir=client, output=Path(directory) / 'functions.json'))
    calls = [{k: v for k, v in row.items() if k not in ['plan', 'expressionEvaluated']}
        for row in source['expressions'] if row['plan']['tree']['op'] == 'Call']
    if calls != profile['expressions']:
        raise ValueError('两处原始 floor 数值程序的身份或内容改变')
    for row in calls:
        compile_program(bytes.fromhex(row['rawPostfixBytes']), row['fixedSignedConstants'], row['variableKeys'])
    report = {'passed': True, 'sourceExpressionCount': source['expressionCount'], 'originalCallProgramCount': len(calls),
        'callPrograms': calls, 'matchedSupplementalNativeMethods': methods, 'matchedSupplementalNativeBytes': size,
        'standardAdditionFragmentBytes': profile['standardAdditionFragmentBytes'], 'standardAdditionCompleteNativeMethod': False,
        'acceptedNumericOpCodes': profile['acceptedNumericOpCodes'], 'standaloneNumericEvaluatorImplemented': True,
        'standardArithmeticOnly': True, 'variableContextResolved': False, 'originalExpressionsEvaluated': False,
        'abilityExecutionVerified': False, 'jsonInstalled': False, 'clientExecutableStarted': False,
        'clientModified': False, 'clientGameplayVerified': False, 'allResonanceAbilitiesImplemented': False}
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n', 'utf-8')
    return report


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--client-dir', type=Path, required=True)
    parser.add_argument('--output', type=Path, default=ROOT / 'build/rogue-resonance-floor-programs-evidence.json')
    report = analyze(parser.parse_args())
    print(json.dumps({key: report[key] for key in ['passed', 'sourceExpressionCount', 'originalCallProgramCount',
        'matchedSupplementalNativeMethods', 'matchedSupplementalNativeBytes', 'standardAdditionFragmentBytes',
        'standaloneNumericEvaluatorImplemented', 'originalExpressionsEvaluated', 'jsonInstalled', 'clientGameplayVerified']}))
