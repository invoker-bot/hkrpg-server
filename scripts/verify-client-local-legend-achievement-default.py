"""只读核对固定4.6成就默认Add/1及目标默认Add；不表示运行客户端或完整舞台脚本。"""
import argparse
import hashlib
import importlib.util
import json
import mmap
from pathlib import Path
from types import SimpleNamespace

ROOT = Path(__file__).resolve().parent
CTOR = {
    0x1da68ac5: 'mov rsi, rcx',
    0x1da68ac8: 'mov dword ptr [rcx + 0x1c], 2',
    0x1da68acf: 'mov rcx, qword ptr [rip - 0x1470b24e]',
    0x1da68adf: 'call 0x183d3a130',
    0x1da68ae4: 'movabs rcx, 0x200000000',
    0x1da68aee: 'mov qword ptr [rax + 0x10], rcx',
    0x1da68af2: 'mov qword ptr [rsi + 0x20], rax',
    0x1da68aff: 'ret ',
    0x1da68b21: 'jmp 0x19da68adf',
}
CONVERSION = [
    'mov rax, qword ptr [rcx]', 'test al, 1', 'mov edx, 0x21',
    'mov ecx, 0x1a', 'cmove ecx, edx', 'sar rax, cl', 'ret',
]


def load(name, filename):
    spec = importlib.util.spec_from_file_location(name, ROOT / filename)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def verify(client_dir):
    native = load('achievement_native', 'analyze-client-46.py')
    reader = load('achievement_points', 'verify-client-local-legend-target-init.py')
    meta = native.extract(SimpleNamespace(
        client_dir=client_dir, types=['SetBattleAchievement', 'SetBattleTargetProgress', 'DynamicFloat', 'PropertyModifyFunction'],
        fields=True, field_offsets=True, inheritance=False, type_contains=[],
        method_indices=[128063, 68425, 68426, 128066, 128068], method_rvas=[], type_slots=[0x935d888],
        method_slots=[], string_slots=[], method_names=[], method_name_contains=[], disassemble=0))
    if meta['unmatchedTypes']:
        raise ValueError('成就默认值必需类型缺失')
    methods = [(m['index'], m['name'], m['typeName'], m['rva']) for m in meta['selectedMethods']]
    if methods != [(128063, '.ctor', 'SetBattleAchievement', '0x1da68ac0'),
                   (68425, 'ToInt32', 'FixPoint', '0x1dccc150'),
                   (68426, 'ToInt64', 'FixPoint', '0x1dccc170'),
                   (128066, '.ctor', 'SetBattleTargetProgress', '0x1da6a4a0'),
                   (128068, 'MGMEGEDLMAK', 'SetBattleTargetProgress', '0x1da6a4b0')]:
        raise ValueError('成就构造/定点转换方法身份不符')
    types = {t['name']: {f['name']: f for f in t['fields']} for t in meta['methods']}
    if (types['SetBattleAchievement']['ModifyFunction'].get('instanceOffset') != 0x1c
            or types['SetBattleAchievement']['ModifyValue'].get('instanceOffset') != 0x20
            or types['SetBattleTargetProgress']['ModifyFunction'].get('instanceOffset') != 0x28
            or types['DynamicFloat']['fixedValue'].get('instanceOffset') != 0x10
            or types['DynamicFloat']['fixedValue']['nativeType'].get('name') != 'FixPoint'
            or types['PropertyModifyFunction']['Add'].get('constant', {}).get('value') != 2
            or meta['typeInfoSlots'][0].get('typeName') != 'DynamicFloat'):
        raise ValueError('成就默认成员布局、Add枚举或分配类型不符')
    functions = reader.read_function_points(client_dir, {'SetBattleAchievement..ctor': (0x1da68ac0, CTOR)})
    # Leaf conversion has no required unwind record. Its span is bounded by verified
    # consecutive declared method starts, and its return is checked separately.
    import capstone
    import pefile
    path = client_dir / 'GameAssembly.dll'
    pe = pefile.PE(str(path), fast_load=True)
    try:
        with path.open('rb') as stream, mmap.mmap(stream.fileno(), 0, access=mmap.ACCESS_READ) as data:
            offset = pe.get_offset_from_rva(0x1dccc150)
            body = data[offset:offset + 0x20]
            decoder = capstone.Cs(capstone.CS_ARCH_X86, capstone.CS_MODE_64)
            instructions = list(decoder.disasm(body, pe.OPTIONAL_HEADER.ImageBase + 0x1dccc150))
            actual = [f'{i.mnemonic} {i.op_str}'.strip() for i in instructions[:7]]
            if actual != CONVERSION or instructions[6].address - pe.OPTIONAL_HEADER.ImageBase != 0x1dccc165:
                raise ValueError('定点默认1的转换指令/返回位置不符')
            target_offset = pe.get_offset_from_rva(0x1da6a4a0)
            target_body = data[target_offset:target_offset + 0x10]
            target_instructions = list(decoder.disasm(
                target_body, pe.OPTIONAL_HEADER.ImageBase + 0x1da6a4a0))
            target_actual = [f'{i.mnemonic} {i.op_str}'.strip() for i in target_instructions[:3]]
            if target_actual != ['mov dword ptr [rcx + 0x28], 2',
                                 'mov byte ptr [rcx + 0x10], 1', 'ret'] or (
                    target_instructions[2].address - pe.OPTIONAL_HEADER.ImageBase != 0x1da6a4ab):
                raise ValueError('战斗目标任务默认Add的构造指令/返回位置不符')
    finally:
        pe.close()
    raw = 0x200000000
    shift = 0x21 if raw & 1 == 0 else 0x1a
    if raw >> shift != 1:
        raise ValueError('默认定点数不是1')
    return {'verified': True, 'clientExecutableStarted': False, 'clientModified': False,
            'clientAccepted': False, 'files': meta['files'], 'modifyFunction': 'Add',
            'modifyFunctionValue': 2, 'defaultModifyValue': 1, 'encodedDefault': hex(raw),
            'functions': functions, 'conversion': {'beginRVA': '0x1dccc150',
                'declaredSpanEndRVA': '0x1dccc170', 'returnRVA': '0x1dccc165',
                'boundaryEvidence': 'next verified declared method; not a PE unwind boundary',
                'spanSHA256': hashlib.sha256(body).hexdigest(), 'instructions': actual},
            'targetModifyFunction': 'Add', 'targetModifyFunctionValue': 2,
            'targetConstructor': {'beginRVA': '0x1da6a4a0', 'declaredSpanEndRVA': '0x1da6a4b0',
                'returnRVA': '0x1da6a4ab',
                'boundaryEvidence': 'next verified declared method; not a PE unwind boundary',
                'spanSHA256': hashlib.sha256(target_body).hexdigest(), 'instructions': target_actual},
            'scope': '固定客户端成就默认Add/1、目标默认Add；完整原始胜利条件与关卡归属另行核对，未运行客户端'}


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--client-dir', required=True, type=Path)
    parser.add_argument('--output', type=Path, default=Path('build/client46-achievement-default.json'))
    args = parser.parse_args()
    client = args.client_dir.resolve()
    output = args.output.resolve()
    if output == client or client in output.parents:
        parser.error('拒绝向客户端目录写入报告')
    report = verify(client)
    payload = json.dumps(report, ensure_ascii=False, indent=2) + '\n'
    if output.exists() and output.read_text('utf-8') != payload:
        raise ValueError('已有不同证据不覆盖')
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(payload, 'utf-8')
    print(json.dumps({'verified': True, 'modifyFunction': 'Add', 'defaultModifyValue': 1,
                      'clientAccepted': False}))
