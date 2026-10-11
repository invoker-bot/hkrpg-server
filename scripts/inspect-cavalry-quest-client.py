"""只读核对4.6骑兽活动的原生915发送与请求字段，不执行游戏二进制。"""
import argparse
import json
from pathlib import Path
import re
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--client-dir', type=Path, required=True)
    parser.add_argument('--output-dir', type=Path, default=ROOT / 'build/cavalry-client-verified')
    args = parser.parse_args()
    output = args.output_dir.resolve()
    assert output.is_relative_to(ROOT / 'build')
    output.mkdir(parents=True, exist_ok=True)
    raw = output / 'native-methods.json'
    subprocess.run([sys.executable, '-s', '-X', 'utf8', str(ROOT / 'scripts/analyze-client-46.py'),
                    '--client-dir', str(args.client_dir), '--types', 'GameEntity', 'AliveState', 'NotifyType',
                    '--type-slots', '0x938d0c0', '--fields', '--field-offsets',
                    '--method-indices', '216045', '554129', '554126', '498193', '635327', '613598',
                    '--disassemble', '1300', '--output', str(raw)], check=True, capture_output=True, text=True)
    data = json.loads(raw.read_text(encoding='utf-8'))
    checks = []

    def check(value, label):
        if not value:
            raise AssertionError(label)
        checks.append(label)

    methods = {row['index']: row for row in data['selectedMethods']}
    types = {row['typeIndex']: row for row in data['methods']}
    native = lambda index: methods[index]['instructions']
    contains = lambda index, text: any(line.endswith(': ' + text) for line in native(index))
    check(methods[554129]['typeIndex'] == 60280 and methods[554129]['rva'] == '0xbf7cf20', 'actual-ride-handler-method')
    check(methods[613598]['typeName'] == 'QuestModule' and methods[613598]['name'] == 'IsQuestStatus', 'actual-quest-status-callee')
    check(methods[635327]['typeName'] == 'NetworkManager' and methods[635327]['rva'] == '0xd9912e0', 'actual-network-callee')
    for instruction in ('mov edx, 0x5c0224', 'mov r8d, 1', 'call 0x18de063b0',
                        'mov ebp, dword ptr [rdi + 0xd0]', 'mov edi, dword ptr [rdi + 0x10c]',
                        'mov dword ptr [rax + 0x18], edi', 'mov dword ptr [rax + 0x1c], ebp',
                        'mov dword ptr [rax + 0x20], 0x5c0224', 'mov dx, 0x393',
                        'mov r8, rax', 'call 0x18d9912e0'):
        check(contains(554129, instruction), 'ride-sender:' + instruction)
    entity = next(row for row in types.values() if row['name'] == 'GameEntity')
    offsets = {field['name']: field.get('instanceOffset') for field in entity['fields']}
    check(offsets['_GroupID'] == 268 and offsets['_GroupEntityID'] == 208, 'source-group-and-instance-layout')
    check(offsets['_AliveState'] == 224 and offsets['_EntityType'] == 256, 'alive-state-not-entity-type-guard')
    request = types[32358]
    check(request['name'] == 'KLKBKPCLHEE', 'actual-request-allocation-type-slot')
    fields = {field['name']: field.get('instanceOffset') for field in request['fields']}
    check(fields['FLBKCMIIFFP'] == 24 and fields['KNKNLKGEGFF'] == 28 and fields['DJIJMBMGAPB'] == 32, 'actual-request-instance-layout')
    check(methods[216045]['typeIndex'] == 32358 and methods[216045]['name'] == 'WriteTo', 'actual-request-writer')
    for tag, offset in ((0x38, 0x20), (0x40, 0x1c), (0x58, 0x18)):
        lines = native(216045)
        selected = [index for index, line in enumerate(lines)
                    if line.endswith(f'mov byte ptr [rcx + rax + 0x20], {tag:#x}')]
        check(len(selected) == 1 and lines[selected[0] + 1].endswith(f'mov edx, dword ptr [rdi + {offset:#x}]'),
              f'writer-field-{tag >> 3}-offset-{offset}')
    notify = types[59289]
    constants = {field['name']: field.get('constant', {}).get('value') for field in notify['fields']}
    for name, value in (('UIPlayerRideGameSkipRide', 371), ('UIPlayerRideGameExit', 372),
                        ('UIPlayerRideGameSwitchRun', 373), ('UIPlayerRideGameSwitchWalk', 374)):
        check(constants[name] == value, 'ride-local-notify:' + name)
        check(contains(554126, f'mov edx, {value:#x}'), 'handler-registers:' + name)
    check(methods[498193]['name'] == 'OnTaskBegin' and contains(498193, 'jmp 0x18bf7cf20'), 'actual-task-begin-calls-ride-handler')
    proto = (ROOT / 'protocols/4.6/CmdQuestType.proto').read_text(encoding='utf-8')
    check('CmdQuestType_FinishQuestCsReq = 915;' in proto, 'frozen-command-915')
    message = re.search(r'message FinishQuestCsReq\s*\{([^}]+)\}', proto).group(1)
    for name, tag in (('quest_id', 7), ('prop_id', 8), ('group_id', 11)):
        check(re.search(r'uint32\s+' + name + r'\s*=\s*' + str(tag) + ';', message), 'frozen-wire:' + name)
    report = {'passed': True, 'checks': checks, 'checkCount': len(checks), 'files': data['files'],
              'questId': 6029860, 'commandId': 915, 'fields': {'quest': 7, 'instance': 8, 'group': 11},
              'clientModified': False, 'clientExecutableStarted': False, 'clientAccepted': False,
              'physicalRideVerified': False, 'requestCommentsAreMemoryLayoutEvidence': False}
    (output / 'verification.json').write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n', encoding='utf-8', newline='\n')
    print(json.dumps({'passed': True, 'checks': len(checks), 'output': str(output / 'verification.json')}, ensure_ascii=False))


if __name__ == '__main__':
    main()
