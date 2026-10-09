"""核对固定 4.6 共鸣后缀程序并恢复有限表达式树；不求值、不执行、不安装。"""
import argparse
from functools import lru_cache
import hashlib
import importlib.util
import json
from pathlib import Path
from types import SimpleNamespace
import tempfile

ROOT = Path(__file__).resolve().parents[1]
PROFILE = ROOT / 'src/test/resources/fixtures/rogue-resonance-postfix-4.6.json'
PROFILE_SHA256 = 'e18232d7dad32917737b6e475fb38a4f788de14849b3125ffb6aa5f3de409baf'


def sibling(name, filename):
    spec = importlib.util.spec_from_file_location(name, Path(__file__).with_name(filename))
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


@lru_cache(maxsize=1)
def load_profile():
    raw = PROFILE.read_bytes().replace(b'\r\n', b'\n')
    if hashlib.sha256(raw).hexdigest() != PROFILE_SHA256:
        raise ValueError('表达式的固定原生证据已改变，需要重新核对')
    return json.loads(raw)


def decode(program, constants, variable_keys):
    """Only the nine observed opcodes; unresolved globals and calls remain explicit."""
    if not isinstance(program, bytes) or not 1 <= len(program) <= 255:
        raise ValueError('表达式程序长度或类型超出边界')
    for values, bits in [(constants, 64), (variable_keys, 32)]:
        if not isinstance(values, (list, tuple)) or len(values) > 255 or any(
                type(value) is not int or not -(1 << (bits - 1)) <= value < 1 << (bits - 1)
                for value in values):
            raise ValueError('表达式常量或变量键类型/位宽超出边界')
    profile = load_profile()
    names = {item['value']: item['name'] for item in profile['opCodeConstants']}
    allowed = frozenset(profile['acceptedOpCodes'])
    stack, cursor, count, maximum = [], 0, 0, 0

    def pop():
        if not stack:
            raise ValueError('表达式栈下溢')
        return stack.pop()

    def operand():
        nonlocal cursor
        if cursor >= len(program):
            raise ValueError('表达式操作数截断')
        value = program[cursor]
        cursor += 1
        return value

    while cursor < len(program):
        operation = program[cursor]
        cursor += 1
        count += 1
        if operation not in allowed:
            raise ValueError('表达式操作码尚未匹配: ' + str(operation))
        name = names[operation]
        depth = 1
        if operation in (0, 1):
            index = operand()
            values = constants if operation == 0 else variable_keys
            if index >= len(values):
                raise ValueError('表达式常量或变量索引越界')
            key = 'fixedSignedRaw' if operation == 0 else 'variableKey'
            node = {'op': name, 'tableIndex': index, key: values[index]}
        elif operation in (2, 3, 4, 5):
            right, right_depth = pop()
            left, left_depth = pop()
            node = {'op': name, 'left': left, 'right': right}
            depth = max(left_depth, right_depth) + 1
        elif operation == 14:
            value, child_depth = pop()
            node, depth = {'op': name, 'value': value}, child_depth + 1
        elif operation == 16:
            arguments = operand()
            if arguments not in profile['acceptedCallArgumentCounts']:
                raise ValueError('表达式调用参数数量尚未匹配')
            argument, argument_depth = pop()
            callee, callee_depth = pop()
            if callee['op'] != profile['acceptedCallCallee']:
                raise ValueError('表达式调用目标尚未匹配')
            node = {'op': name, 'callee': callee, 'arguments': [argument], 'argumentCount': arguments}
            depth = max(argument_depth, callee_depth) + 1
        else:  # Return
            if cursor != len(program) or len(stack) != 1:
                raise ValueError('表达式返回位置或结果栈不唯一')
            return {'tree': stack[0][0], 'fullyConsumed': True, 'programBytes': len(program),
                    'instructionCount': count, 'maxStackDepth': maximum, 'expressionEvaluated': False}
        if depth > 32 or len(stack) >= 64:
            raise ValueError('表达式树深度或栈大小超出边界')
        stack.append((node, depth))
        maximum = max(maximum, len(stack))
    raise ValueError('表达式缺少终止返回')


def verify_native(client):
    profile = load_profile()
    characters = sibling('postfix_character_verification', 'read-rogue-resonance-character.py')
    methods, size = characters.verify_native(client, profile)
    file = client / 'StarRail_Data/il2cpp_data/Metadata/global-metadata.dat'
    with file.open('rb') as stream:
        for value in profile['opCodeConstants']:
            if value['nativeKind'] != 5:
                raise ValueError('表达式操作码原生类型改变')
            stream.seek(value['metadataOffset'])
            if stream.read(1) != bytes([value['value']]):
                raise ValueError('表达式操作码原始常量改变')
    return methods, size


def expression_rows(records):
    results = []

    def visit(value, path):
        if isinstance(value, dict):
            if value.get('mode') == 1 and 'rawPostfixBytes' in value:
                plan = decode(bytes.fromhex(value['rawPostfixBytes']),
                              value['fixedSignedConstants'], value['variableKeys'])
                results.append({'path': path, **value, 'plan': plan})
            for key, child in value.items():
                visit(child, path + '.' + key)
        elif isinstance(value, list):
            for index, child in enumerate(value):
                visit(child, path + '[' + str(index) + ']')

    for record in records:
        visit(record['decoded'], record['sourcePath'])
    return results


def analyze(args):
    client = args.client_dir.resolve()
    design = sibling('postfix_design_output', 'read-client-design-data.py')
    output = design.outside_client(args.output, client)
    if ROOT / 'build' not in output.parents:
        raise ValueError('只接受工程 build/ 下的只读表达式证据输出')
    methods, size = verify_native(client)
    profile = load_profile()
    abilities = sibling('postfix_abilities', 'read-rogue-resonance-abilities.py')
    (ROOT / 'build').mkdir(exist_ok=True)
    with tempfile.TemporaryDirectory(prefix='resonance-postfix-', dir=ROOT / 'build') as temporary:
        source = abilities.analyze(SimpleNamespace(client_dir=client, output=Path(temporary) / 'abilities.json'))
        rows = expression_rows(source['records'])
    programs = sorted({row['rawPostfixBytes'] for row in rows})
    if len(rows) != profile['originalExpressionCount'] or programs != profile['originalProgramBytes']:
        raise ValueError('原始表达式集合与已匹配范围不符')
    report = {'passed': True, 'recordCount': source['recordCount'], 'recordBytes': source['recordBytes'],
              'expressionCount': len(rows), 'programCount': len(programs),
              'matchedNativeMethods': methods, 'matchedNativeBytes': size,
              'opCodeConstantsMatched': len(profile['opCodeConstants']),
              'acceptedOpCodes': profile['acceptedOpCodes'], 'expressions': rows,
              'nativeFiles': profile['nativeFiles'], 'indexSHA256': source['indexSHA256'],
              'expressionTopologyVerified': True, 'expressionEvaluationVerified': False,
              'variableContextResolved': False, 'callImplementationsResolved': False,
              'jsonInstalled': False, 'clientExecutableStarted': False, 'clientModified': False,
              'allResonanceAbilitiesImplemented': False, 'clientGameplayVerified': False}
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n', 'utf-8')
    return report


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--client-dir', type=Path, required=True)
    parser.add_argument('--output', type=Path, default=ROOT / 'build/rogue-resonance-postfix-evidence.json')
    report = analyze(parser.parse_args())
    print(json.dumps({k: report[k] for k in ['passed', 'expressionCount', 'programCount',
        'matchedNativeMethods', 'matchedNativeBytes', 'expressionTopologyVerified',
        'expressionEvaluationVerified', 'jsonInstalled', 'clientGameplayVerified']}))
