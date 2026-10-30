"""只读核对本机 4.6 试用下载回包及可用角色池；不启动或修改客户端。"""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import sys
import capstone
import pefile


def audit(client, output):
    raw = output.parent / 'download-native-metadata.json'
    output.parent.mkdir(parents=True, exist_ok=True)
    subprocess.run([sys.executable, str(Path(__file__).with_name('analyze-client-46.py')),
        '--client-dir', str(client), '--types', 'BHLEHMNFHPJ', 'ENOBKLGCHOD',
        '--fields', '--field-offsets', '--method-indices', '617619', '619191', '616727',
        '616730', '616731', '616728', '616729', '--output', str(raw)], check=True,
        stdout=subprocess.DEVNULL)
    metadata = json.loads(raw.read_text('utf-8'))
    pe = pefile.PE(str(client / 'GameAssembly.dll'), fast_load=True)
    pe.parse_data_directories([pefile.DIRECTORY_ENTRY['IMAGE_DIRECTORY_ENTRY_EXCEPTION']])
    bounds = {r.struct.BeginAddress: r.struct.EndAddress for r in pe.DIRECTORY_ENTRY_EXCEPTION}
    dis = capstone.Cs(capstone.CS_ARCH_X86, capstone.CS_MODE_64)
    methods = []
    for selected in metadata['selectedMethods']:
        start = int(selected['rva'], 16)
        end = bounds[start]
        body = pe.get_data(start, end - start)
        instructions = list(dis.disasm(body, pe.OPTIONAL_HEADER.ImageBase + start))
        if sum(i.size for i in instructions) != len(body):
            raise ValueError('Incomplete unwind body decode')
        methods.append(dict(selected, endRVA=hex(end), sha256=hashlib.sha256(body).hexdigest(),
            instructions=[f'{i.address - pe.OPTIONAL_HEADER.ImageBase:x}: {i.mnemonic} {i.op_str}' for i in instructions]))
    by_name = {m['name']: m for m in methods}
    response = by_name['_OnCmdPickRogueAvatarScRsp']['instructions']
    sync = by_name['Sync']['instructions']
    required = [
        ('formal response list', response, 'mov rsi, qword ptr [rdi + 0x18]'),
        ('formal response consumer', response, 'call 0x18df492d0'),
        ('trial response list', response, 'mov rsi, qword ptr [rax + 0x10]'),
        ('trial response consumer', response, 'call 0x18df49440'),
        ('formal pool', sync, 'mov rdx, qword ptr [rsi + 0x20]'),
        ('formal pool consumer', sync, 'call 0x18df5c7a0'),
        ('trial pool', sync, 'mov rdx, qword ptr [rsi + 0x28]'),
        ('trial pool consumer', sync, 'jmp 0x18df5c8b0'),
    ]
    for label, instructions, value in required:
        if not any(line.endswith(value) for line in instructions):
            raise ValueError('Client mismatch: ' + label)
    report = dict(clientModified=False, clientExecutableStarted=False, clientAccepted=False,
        files=metadata['files'], methods=methods,
        fields=[dict(name=t['name'], fields=t['fields']) for t in metadata['methods']],
        checks=[label for label, _, _ in required],
        request=dict(command=1864, entity=6, formal=14, trial=11),
        response=dict(command=1821, retcode=13, formal=15, trial=6),
        pool=dict(type='ENOBKLGCHOD', formal=1, trial=4),
        codecProvenance='docs/client46-rogue-download-evidence.json; native metadata offsets and response/pool consumers checked here')
    output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    print(json.dumps(dict(output=str(output), checks=len(required), methods=len(methods), passed=True)))


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--client-dir', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    audit(args.client_dir.resolve(), args.output.resolve())
