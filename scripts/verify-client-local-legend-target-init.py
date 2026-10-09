"""固定本机 4.6 初始目标调用链的只读证据；不执行 DLL，不代替客户端玩法验收。"""
import argparse
import hashlib
import importlib.util
import json
import mmap
import struct
from pathlib import Path
from types import SimpleNamespace

import capstone
import pefile


ROOT = Path(__file__).resolve().parent
# Each point is checked within its PE exception-table function, never a window
# spanning the following method. Branch conditions are retained in the report.
FUNCTIONS = {
    'SetupFields': (0x3d220d0, {
        0x3d224a7: 'mov edx, 0xbc7ec9d7',
        0x3d224ac: 'add edx, dword ptr [rax + 0x9c]',
        0x3d224bf: 'mov esi, 0xe4dbe763',
        0x3d224c4: 'add esi, dword ptr [rax + 0x180]',
        0x3d224e7: 'imul edx, ebx, 0x8af8af8b',
        0x3d225ec: 'movsxd rbx, dword ptr [rax + 0x148]',
        0x3d225f3: 'xor rbx, 0x329e1172'}),
    'SceneBattleInfo.MapCodec': (0x1eb58dc0, {
        0x1eb58edc: 'mov dword ptr [rax + 0x20], 0x3ea2',
        0x1eb58eea: 'mov qword ptr [rcx + 0x3148], rax'}),
    'SceneBattleInfo.WriteTo': (0x1eb577c0, {
        0x1eb57c4b: 'mov rbx, qword ptr [rdi + 0x10]',
        0x1eb57c73: 'mov r8, qword ptr [rax + 0x3148]',
        0x1eb57c81: 'mov rcx, rbx',
        0x1eb57c84: 'mov rdx, rsi',
        0x1eb57c87: 'call 0x1982fe2b0'}),
    'BattleTarget.WriteTo': (0x1e108c20, {
        0x1e108c6c: 'mov byte ptr [rcx + rax + 0x20], 8',
        0x1e108c71: 'mov edx, dword ptr [rdi + 0x1c]',
        0x1e108cb8: 'mov byte ptr [rcx + rax + 0x20], 0x10',
        0x1e108cbd: 'mov edx, dword ptr [rdi + 0x20]',
        0x1e108cfc: 'mov byte ptr [rcx + rax + 0x20], 0x18',
        0x1e108d01: 'mov edx, dword ptr [rdi + 0x18]'}),
    'FKOEECADKFF.BMLFJNDCCJO': (0x1580d0b0, {
        0x1580d0fe: 'mov edx, 5',
        0x1580d10a: 'js 0x19580d22a',
        0x1580d11a: 'mov edx, 5',
        0x1580d11f: 'call 0x1982fb770',
        0x1580d12d: 'mov rcx, qword ptr [rax + 0x10]',
        0x1580d138: 'call 0x1971849f0',
        0x1580d1b3: 'movq xmm0, qword ptr [rax + 0x1c]',
        0x1580d1b8: 'pshufd xmm0, xmm0, 0xe1',
        0x1580d1bd: 'movq qword ptr [rbx + 0x10], xmm0'}),
    'AdventureModule.CreateBattleServerCheckResult': (0xc5d5350, {
        0xc5d544c: 'mov rcx, qword ptr [r13 + 0x10]',
        0xc5d5450: 'call 0x19580d0b0',
        0xc5d5455: 'mov qword ptr [rbp + 0x28], rax'}),
    'AdventurePhaseEnterBattle._OnCheckTriggerBattle': (0xc614ab0, {
        0xc614cd6: 'mov rax, qword ptr [rsi + 0x28]',
        0xc614cda: 'mov qword ptr [rdi + 0x100], rax'}),
    'AdventurePhaseEnterBattle._FillBattleInitParams': (0xc617d60, {
        0xc61880e: 'mov rax, qword ptr [r13 + 0x100]',
        0xc61882b: 'mov rcx, qword ptr [rcx + 0x18]',
        0xc618838: 'mov rcx, qword ptr [rcx + 0x40]',
        0xc618845: 'mov qword ptr [rcx + 0xa0], rax'}),
    'LevelDataComponent.InitComponent': (0xe6dbf10, {
        0xe6dc24e: 'mov r15, qword ptr [r13 + 0xa0]',
        0xe6dc2ed: 'mov edi, dword ptr [rax + 0x14]',
        0xe6dc306: 'call 0x19d43b780',
        0xe6dc314: 'cmp byte ptr [rax + 0x66], 0',
        0xe6dc31c: 'je 0x18e6dc33d',
        0xe6dc335: 'mov edi, dword ptr [rdx + 0x10]',
        0xe6dc338: 'cmp edi, dword ptr [rax + 0x44]',
        0xe6dc33b: 'jae 0x18e6dc370',
        0xe6dc354: 'mov rcx, qword ptr [r12 + 0x60]',
        0xe6dc370: 'mov rcx, qword ptr [r12 + 0x70]'}),
}
LAYOUT = {
    'NNPGLCGNICI': {'IFPIHNGNPGF': 0x10},
    'COMGNGLLCFN': {'GEMFJILOPLL': 0x1c, 'NDFIDCNNGMJ': 0x20,
                    'CEHGEDLBFEI': 0x18},
    'PBNONKAIAMK': {'KKCKMCDGCGK': 0x10, 'MBCACNKDMFG': 0x14},
    'SceneMonsterBattleServerCheckResult': {'battleTargetList': 0x28},
    'AdventurePhaseEnterBattle': {'_OverrideTargetData': 0x100},
    'BattleLineupData': {'Context': 0x40},
    'LineUpContext': {'BattleTargetList': 0xa0},
    'LevelDataComponent': {'BattleTargetList': 0x60, 'BattleTargetList_FinishOnEnterBattle': 0x70},
    'BattleTargetConfigRow': {'ID': 0x58, 'TargetParam': 0x44,
                            'SkipWhenSuccessOnEnterBattle': 0x66},
}


def read_function_points(client_dir, definitions):
    """调用方须先通过固定 SHA 校验；只读函数字节和异常表边界。"""
    dll = client_dir / 'GameAssembly.dll'
    pe = pefile.PE(str(dll), fast_load=True)
    base = pe.OPTIONAL_HEADER.ImageBase
    decoder = capstone.Cs(capstone.CS_ARCH_X86, capstone.CS_MODE_64)
    functions = []
    with dll.open('rb') as stream, mmap.mmap(stream.fileno(), 0, access=mmap.ACCESS_READ) as image:
        def read(rva, length):
            section = pe.get_section_by_rva(rva)
            if section is None or rva - section.VirtualAddress + length > section.SizeOfRawData:
                raise ValueError('PE 地址没有完整文件字节')
            pos = pe.get_offset_from_rva(rva)
            return image[pos:pos + length]
        table = pe.OPTIONAL_HEADER.DATA_DIRECTORY[3]
        if table.Size % 12:
            raise ValueError('异常表不是完整的 12 字节记录')
        bounds = {begin: end for begin, end, _ in
                  struct.iter_unpack('<III', read(table.VirtualAddress, table.Size))}
        for name, (start, points) in definitions.items():
            end = bounds.get(start)
            if end is None or not start < end <= start + 10000:
                raise ValueError(f'函数异常表边界无法核对: {name}')
            raw = read(start, end - start)
            # Hash complete functions; decode only points, not embedded jump tables.
            confirmed = []
            for rva, expected in points.items():
                if not start <= rva < end:
                    raise ValueError(f'证据点跨出函数: {name}/{rva:x}')
                instruction = next(decoder.disasm(read(rva, min(15, end - rva)), base + rva), None)
                actual = None if instruction is None else f'{instruction.mnemonic} {instruction.op_str}'
                if actual != expected:
                    raise ValueError(f'静态指令不符: {name}/{rva:x}: {actual}')
                confirmed.append({'rva': hex(rva), 'instruction': actual})
            functions.append({'name': name, 'beginRVA': hex(start), 'endRVA': hex(end),
                              'functionBytesSHA256': hashlib.sha256(raw).hexdigest(),
                              'points': confirmed})
    return functions


def verify(client_dir):
    spec = importlib.util.spec_from_file_location('native_metadata', ROOT / 'analyze-client-46.py')
    analyzer = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(analyzer)
    metadata = analyzer.extract(SimpleNamespace(
        client_dir=client_dir, types=list(LAYOUT), fields=True, field_offsets=True,
        inheritance=False, type_contains=[], method_indices=[], method_rvas=[],
        type_slots=[], method_slots=[], string_slots=[], method_names=[],
        method_name_contains=[], disassemble=0))
    if metadata['unmatchedTypes']:
        raise ValueError('必需的本机目标类型缺失')
    layouts = {}
    for row in metadata['methods']:
        fields = {field['name']: field for field in row['fields']}
        layouts[row['name']] = {}
        for name, expected in LAYOUT[row['name']].items():
            actual = fields[name].get('instanceOffset')
            if actual != expected:
                raise ValueError(f'本机实例字段布局不符: {row["name"]}.{name}: {actual}')
            layouts[row['name']][name] = actual
    functions = read_function_points(client_dir, FUNCTIONS)
    return {'clientExecutableStarted': False, 'clientModified': False, 'clientAccepted': False,
            'files': metadata['files'], 'verified': True, 'initialTargetCategory': 5,
            'sceneBattleInfoField': 2004, 'wireTargetFields': {'id': 1, 'progress': 2, 'total_progress': 3},
            'instanceLayouts': layouts, 'functions': functions,
            'boundaries': ['PE 异常表函数边界与指定指令点已逐项核对，未执行客户端',
                           '零进度和原始阈值为本项目新战斗入场规则，历史星级独立保存',
                           '目标 Ability/舞台事件 20037、真实回合、UI 和客户端验收仍未完成']}


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--client-dir', required=True, type=Path)
    parser.add_argument('--output', type=Path, default=Path('build/client46-local-legend-initial-targets.json'))
    args = parser.parse_args()
    report = verify(args.client_dir.resolve())
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    print(json.dumps({'output': str(args.output), 'verified': True, 'functions': len(report['functions']),
                      'instructionPoints': sum(len(row['points']) for row in report['functions']),
                      'clientAccepted': False}))
