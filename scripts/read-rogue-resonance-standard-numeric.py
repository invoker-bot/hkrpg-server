"""核对全部 592 条共鸣程序及九种操作的有限标准定点数求值。

保留真实变量缺口；完整编译不等于实际战斗能力执行。
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
PROFILE = ROOT / 'src/test/resources/fixtures/rogue-resonance-standard-numeric-4.6.json'
PROFILE_SHA256 = 'aa08cdd14224d8bb7d427d4794393a127c3da4317df45cacd8266f6da2b0759d'
spec = importlib.util.spec_from_file_location('standard_numeric_floor', Path(__file__).with_name('read-rogue-resonance-floor-programs.py'))
numeric = importlib.util.module_from_spec(spec)
spec.loader.exec_module(numeric)


@lru_cache(maxsize=1)
def load_profile():
    raw = PROFILE.read_bytes().replace(b'\r\n', b'\n')
    if hashlib.sha256(raw).hexdigest() != PROFILE_SHA256:
        raise ValueError('标准数值运算与程序集合的固定证据已改变')
    return json.loads(raw)


def compile_program(program, constants, variable_keys):
    profile = load_profile()
    if profile['acceptedNumericOpCodes'] != numeric.functions.postfix.load_profile()['acceptedOpCodes']:
        raise ValueError('标准数值范围与原生有限操作码范围不符')
    return numeric.functions.decode(program, constants, variable_keys)


def evaluate(program, constants, variable_keys, inputs):
    plan = compile_program(program, constants, variable_keys)
    if (not isinstance(inputs, dict) or len(inputs) > 255 or any(type(key) is not int
            or not -(1 << 31) <= key < 1 << 31 for key in inputs)):
        raise ValueError('标准数值上下文类型、数量或变量键位宽不符')
    values = {key: numeric.standard(value) for key, value in inputs.items()}

    def value(node):
        op = node['op']
        if op == 'Constant':
            return numeric.standard(node['fixedSignedRaw'])
        if op == 'GetGlobal':
            key = node['variableKey']
            if key == 1776456860 or key not in values:
                raise ValueError('标准数值变量上下文未解析')
            return values[key]
        if op == 'Negative':
            return numeric.numeric_result(-value(node['value']))
        if op in ['Add', 'Subtract', 'Multiply', 'Divide']:
            a, b = value(node['left']), value(node['right'])
            if op == 'Add':
                return numeric.numeric_result(a + b)
            if op == 'Subtract':
                return numeric.numeric_result(a - b)
            if op == 'Multiply':
                return numeric.numeric_result(((a >> 1) * (b >> 1) >> 32) << 1)
            return numeric.evaluate(bytes.fromhex('000000010511'), [a, b], [], {})
        return numeric.functions.floor_raw(value(node['arguments'][0]))

    return value(plan['tree'])


def verify_native(client):
    profile = load_profile()
    characters = numeric.functions.sibling('standard_numeric_characters', 'read-rogue-resonance-character.py')
    counts = characters.verify_native(client, profile)
    numeric.verify_native(client)
    analyzer = numeric.functions.sibling('standard_numeric_metadata', 'analyze-client-46.py')
    source = analyzer.extract(SimpleNamespace(client_dir=client, types=[], type_contains=[],
        method_indices=[m['index'] for m in profile['standardBranchMethods']], method_rvas=[], method_name_contains=[],
        method_slots=[], string_slots=[], type_slots=[], fields=False, inheritance=False,
        field_offsets=False, disassemble=0, method_names=[]))
    if source['selectedMethods'] != profile['standardBranchMethods']:
        raise ValueError('标准减法/取负的原始方法身份不符')
    pe = pefile.PE(str(client / 'GameAssembly.dll'), fast_load=True)
    with (client / 'GameAssembly.dll').open('rb') as stream:
        for fragment in profile['standardBranchFragments']:
            start, length = int(fragment['rva'], 16), fragment['length']
            section = pe.get_section_by_rva(start)
            if section is None or start - section.VirtualAddress + length > section.SizeOfRawData:
                raise ValueError('标准数值分支片段无实际文件范围')
            stream.seek(pe.get_offset_from_rva(start))
            raw = stream.read(length)
            if raw.hex() != fragment['bytesHex'] or hashlib.sha256(raw).hexdigest() != fragment['sha256']:
                raise ValueError('标准数值分支固定字节改变')
    return counts


def analyze(args):
    client = args.client_dir.resolve()
    design = numeric.functions.sibling('standard_numeric_design', 'read-client-design-data.py')
    output = design.outside_client(args.output, client)
    if ROOT / 'build' not in output.parents:
        raise ValueError('只接受工程 build/ 下的只读标准数值证据输出')
    profile = load_profile()
    methods, size = verify_native(client)
    with tempfile.TemporaryDirectory(prefix='resonance-standard-', dir=ROOT / 'build') as directory:
        source = numeric.functions.analyze(SimpleNamespace(client_dir=client, output=Path(directory) / 'functions.json'))
    rows = [{k: v for k, v in row.items() if k not in ['plan', 'expressionEvaluated']} for row in source['expressions']]
    if rows != profile['expressions'] or len(rows) != profile['expressionCount']:
        raise ValueError('原始标准数值程序集合的身份或内容改变')
    plans = [compile_program(bytes.fromhex(row['rawPostfixBytes']), row['fixedSignedConstants'], row['variableKeys']) for row in rows]
    report = {'passed': True, 'originalExpressionCount': len(rows), 'originalProgramCount': len({r['rawPostfixBytes'] for r in rows}),
        'originalProgramsCompiled': len(plans), 'acceptedNumericOpCodes': profile['acceptedNumericOpCodes'],
        'matchedSupplementalNativeMethods': methods, 'matchedSupplementalNativeBytes': size,
        'standardBranchFragmentBytes': profile['standardBranchFragmentBytes'], 'standardBranchesCompleteNativeMethods': False,
        'expressions': rows, 'standaloneStandardNumericEvaluatorImplemented': True, 'standardArithmeticOnly': True,
        'originalVariableContextResolved': False, 'originalExpressionsEvaluated': False,
        'abilityExecutionVerified': False, 'jsonInstalled': False, 'clientExecutableStarted': False,
        'clientModified': False, 'clientGameplayVerified': False, 'allResonanceAbilitiesImplemented': False}
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n', 'utf-8')
    return report


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--client-dir', type=Path, required=True)
    parser.add_argument('--output', type=Path, default=ROOT / 'build/rogue-resonance-standard-numeric-evidence.json')
    report = analyze(parser.parse_args())
    print(json.dumps({key: report[key] for key in ['passed', 'originalExpressionCount', 'originalProgramCount',
        'originalProgramsCompiled', 'matchedSupplementalNativeMethods', 'matchedSupplementalNativeBytes',
        'standardBranchFragmentBytes', 'standaloneStandardNumericEvaluatorImplemented',
        'originalExpressionsEvaluated', 'jsonInstalled', 'clientGameplayVerified']}))
