"""只读核对固定 4.6 客户端的六种结算目标类别，不推定成就条件或奖励完成。"""
import argparse
import importlib.util
import json
from pathlib import Path
from types import SimpleNamespace


ROOT = Path(__file__).resolve().parent
CATEGORIES = {
    'battleScoringList': (0x38, 1),
    'battleAchievementList': (0x20, 2),
    'battleClientAchievementList': (0xd8, 6),
    'battleRaidTargetList': (0x110, 3),
    'battleChallengeScoreList': (0x28, 4),
    'battleTargetList': (0x70, 5),
}
POINTS = {
    # Register saves/restores distinguish the internal statistics from the proto.
    0xe6b5e06: 'mov r14, rcx',
    0xe6b5e35: 'mov rbx, rax',
    0xe6b6020: 'mov qword ptr [rbp - 0x58], r14',
    0xe6b6024: 'mov qword ptr [rbp], rbx',
    0xe6b618f: 'mov rbx, qword ptr [rbp - 0x58]',
    0xe6b6199: 'mov r14, qword ptr [rbp]',
    0xe6b61f6: 'mov rdx, qword ptr [rbx + 0x38]',
    0xe6b6209: 'mov rcx, qword ptr [r14 + 0xe0]',
    0xe6b6220: 'mov edx, 1',
    0xe6b6228: 'call 0x1982fb240',
    0xe6b622d: 'mov rax, qword ptr [rbx + 0x20]',
    0xe6b623e: 'jle 0x18e6b62b1',
    0xe6b627a: 'mov rdx, qword ptr [rbx + 0x20]',
    0xe6b628d: 'mov rcx, qword ptr [r14 + 0xe0]',
    0xe6b62a4: 'mov edx, 2',
    0xe6b62ac: 'call 0x1982fb240',
    0xe6b62b1: 'mov rax, qword ptr [rbx + 0xd8]',
    0xe6b62c5: 'jle 0x18e6b633b',
    0xe6b6301: 'mov rdx, qword ptr [rbx + 0xd8]',
    0xe6b6317: 'mov rcx, qword ptr [r14 + 0xe0]',
    0xe6b632e: 'mov edx, 6',
    0xe6b6336: 'call 0x1982fb240',
    0xe6b638b: 'mov rdx, qword ptr [rbx + 0x110]',
    0xe6b63a1: 'mov rcx, qword ptr [r14 + 0xe0]',
    0xe6b63b8: 'mov edx, 3',
    0xe6b63c0: 'call 0x1982fb240',
    0xe6b6412: 'mov rdx, qword ptr [rbx + 0x28]',
    0xe6b6425: 'mov rcx, qword ptr [r14 + 0xe0]',
    0xe6b643c: 'mov edx, 4',
    0xe6b6444: 'call 0x1982fb240',
    0xe6b6496: 'mov rdx, qword ptr [rbx + 0x70]',
    0xe6b64a9: 'mov rcx, qword ptr [r14 + 0xe0]',
    0xe6b64c0: 'mov edx, 5',
    0xe6b64c8: 'call 0x1982fb240',
}


def load(name, path):
    spec = importlib.util.spec_from_file_location(name, ROOT / path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def verify(client_dir):
    native = load('native_metadata', 'analyze-client-46.py')
    reader = load('native_points', 'verify-client-local-legend-target-init.py')
    report = native.extract(SimpleNamespace(
        client_dir=client_dir, types=['PVEGameStatistics', 'LCCJKNEADPL'], fields=True,
        field_offsets=True, inheritance=False, type_contains=[], method_indices=[541662],
        method_rvas=[], type_slots=[], method_slots=[], string_slots=[], method_names=[],
        method_name_contains=[], disassemble=0))
    if report['unmatchedTypes']:
        raise ValueError('必需的本机结算类型缺失')
    method = report['selectedMethods'][0]
    if (method['name'], method['typeName'], method['rva']) != (
            'CreateBattleStatsProto', 'GamePlayStatic', '0xe6b5de0'):
        raise ValueError('本机结算转换方法身份不符')
    types = {row['name']: {field['name']: field for field in row['fields']}
             for row in report['methods']}
    for name, (offset, _) in CATEGORIES.items():
        if types['PVEGameStatistics'][name].get('instanceOffset') != offset:
            raise ValueError(f'结算集合偏移不符: {name}')
    if types['LCCJKNEADPL']['IFPIHNGNPGF'].get('instanceOffset') != 0xe0:
        raise ValueError('结算协议目标字典偏移不符')
    functions = reader.read_function_points(client_dir, {
        'GamePlayStatic.CreateBattleStatsProto': (0xe6b5de0, POINTS)})
    return {'verified': True, 'clientExecutableStarted': False, 'clientModified': False,
            'clientAccepted': False, 'files': report['files'], 'wireFieldNumber': 28,
            'categories': {name: key for name, (_, key) in CATEGORIES.items()},
            'functions': functions,
            'scope': '固定客户端原始集合至六种字典键的静态链；不是成就条件、奖励归属或客户端玩法验收'}


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--client-dir', required=True, type=Path)
    parser.add_argument('--output', type=Path, default=Path('build/client46-battle-target-categories.json'))
    args = parser.parse_args()
    report = verify(args.client_dir.resolve())
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    print(json.dumps({'output': str(args.output), 'verified': True, 'categories': report['categories'],
                      'instructionPoints': len(POINTS), 'clientAccepted': False}))
