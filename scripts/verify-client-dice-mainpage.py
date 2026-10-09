"""只读核对固定国服4.6骰子首页空请求、命令号、冷却键与响应字段；不执行客户端。"""
import argparse
import hashlib
import importlib.util
import json
from pathlib import Path
from types import SimpleNamespace

ROOT = Path(__file__).resolve().parent
FUNCTIONS = {
    'DiceCombatUtil.GetMainPageRollDiceCoolDownMilliSecond': (0xcc35590, {
        0xcc355c2: 'mov rcx, qword ptr [rip - 0x350c419]',
        0xcc355c9: 'call 0x19d189930',
        0xcc355d7: 'mov rcx, qword ptr [rax + 0x18]',
        0xcc355e4: 'movzx eax, byte ptr [rcx + 0x30]',
        0xcc355f2: 'test eax, eax',
        0xcc355f6: 'mov eax, dword ptr [rcx + 0x28]'}),
    'DiceCombatUtil.SendDiceCombatMainPageRollDiceCsReq': (0xcc36b90, {
        0xcc36bda: 'mov rcx, qword ptr [rip - 0x38ac2c1]',
        0xcc36be1: 'call 0x183d3a130',
        0xcc36be9: 'mov dx, 0x242e',
        0xcc36bed: 'mov r8, rax',
        0xcc36bf0: 'xor r9d, r9d',
        0xcc36bf8: 'jmp 0x18d9912e0'}),
    'BPNGPLHJHBE.WriteTo': (0x1e021ce0, {
        0x1e021cec: 'cmp dword ptr [rcx + 0x18], 0',
        0x1e021d20: 'mov byte ptr [rcx + rax + 0x20], 0x40',
        0x1e021d25: 'mov edx, dword ptr [rdi + 0x18]',
        0x1e021d2b: 'call 0x19b831f30'}),
}


def load(name, filename):
    spec = importlib.util.spec_from_file_location(name, ROOT / filename)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def verify(client):
    native = load('dice_native', 'analyze-client-46.py')
    reader = load('dice_points', 'verify-client-local-legend-target-init.py')
    metadata = native.extract(SimpleNamespace(
        client_dir=client, types=['CNFMEHAEIJI', 'BPNGPLHJHBE'], fields=True,
        field_offsets=True, inheritance=False, type_contains=[],
        method_indices=[690664, 690678, 169194], method_rvas=[],
        type_slots=[0x938a920], string_slots=[0x97291b0], method_slots=[],
        method_names=[], method_name_contains=[], disassemble=0))
    methods = [(m['index'], m['name'], m['typeName'], m['rva'])
               for m in metadata['selectedMethods']]
    if methods != [
            (690664, 'GetMainPageRollDiceCoolDownMilliSecond', 'DiceCombatUtil', '0xcc35590'),
            (690678, 'SendDiceCombatMainPageRollDiceCsReq', 'DiceCombatUtil', '0xcc36b90'),
            (169194, 'WriteTo', 'BPNGPLHJHBE', '0x1e021ce0')]:
        raise ValueError('骰子首页方法身份不符')
    types = {t['name']: t for t in metadata['methods']}
    if metadata['unmatchedTypes'] or set(types) != {'CNFMEHAEIJI', 'BPNGPLHJHBE'}:
        raise ValueError('骰子首页原生类型缺失')
    request = [f for f in types['CNFMEHAEIJI']['fields'] if not f['isStatic']]
    response = {f['name']: f for f in types['BPNGPLHJHBE']['fields']}
    if (len(request) != 1 or request[0]['name'] != 'DHHIJOGAOFI'
            or request[0].get('instanceOffset') != 16
            or request[0]['nativeType'].get('name') != 'UnknownFieldSet'
            or response['PLDKENJLKME'].get('constant', {}).get('value') != 8
            or response['NAEACGPKNNH'].get('instanceOffset') != 24
            or response['NAEACGPKNNH']['nativeType']['kind'] != 9
            or len([f for f in response.values() if not f['isStatic']]) != 2):
        raise ValueError('骰子首页请求载荷或响应字段布局不符')
    if (metadata['typeInfoSlots'][0].get('typeName') != 'CNFMEHAEIJI'
            or metadata['stringLiteralSlots'][0].get('value')
            != 'Dice_Combat_Main_Page_Dice_Cool_Down_Time'):
        raise ValueError('骰子首页分配类型或资源键不符')
    constants = ROOT.parent / 'src/test/resources/gameplay/activity-dice-const-4.6.json'
    raw = constants.read_bytes()
    if hashlib.sha256(raw).hexdigest() != 'cea8b6ffe42464dbec2162be883350db072baa5fbc8663f69a32e0c562ffaa95':
        raise ValueError('骰子原始常量文件不符')
    rows = json.loads(raw)
    cooldown = [r for r in rows if r.get('ConstValueName') ==
                'Dice_Combat_Main_Page_Dice_Cool_Down_Time']
    if len(cooldown) != 1 or cooldown[0]['Value'] != {'IntValue': 1500}:
        raise ValueError('骰子原始冷却值不符')
    functions = reader.read_function_points(client, FUNCTIONS)
    return dict(verified=True, clientExecutableStarted=False, clientModified=False,
                clientAccepted=False, requestCommand=9262, emptyRequest=True,
                responseField=8, responseWireType=0, responseType='UInt32',
                cooldownKey=cooldown[0]['ConstValueName'], cooldownMillis=1500,
                constantsSHA256=hashlib.sha256(raw).hexdigest(), functions=functions,
                scope='仅核对首页投掷报告和原始冷却；响应命令号使用冻结参考映射，未验证骰子对战演算或游戏UI')


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--client-dir', required=True, type=Path)
    parser.add_argument('--output', required=True, type=Path)
    args = parser.parse_args()
    client, output = args.client_dir.resolve(), args.output.resolve()
    if output == client or client in output.parents:
        parser.error('拒绝向客户端目录写入报告')
    report = verify(client)
    payload = json.dumps(report, ensure_ascii=False, indent=2) + '\n'
    if output.exists() and output.read_text('utf-8') != payload:
        raise ValueError('已有不同证据不覆盖')
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(payload, 'utf-8')
    print(json.dumps({'verified': True, 'requestCommand': 9262, 'cooldownMillis': 1500,
                      'clientAccepted': False}))
