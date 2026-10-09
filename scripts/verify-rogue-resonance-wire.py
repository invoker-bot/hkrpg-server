"""只读核对回响字段链的本机哈希、PE 范围与完整字节，不加载/执行 DLL。"""
import argparse
import hashlib
import json
import mmap
from pathlib import Path
import struct

import pefile

ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--client-dir', type=Path, required=True)
    args = parser.parse_args()
    proof = json.loads((ROOT / 'src/test/resources/fixtures/rogue-resonance-wire-4.6.json').read_text('utf-8'))
    client = args.client_dir.resolve()
    for name, relative in [('GameAssembly.dll', 'GameAssembly.dll'),
                           ('global-metadata.dat', 'StarRail_Data/il2cpp_data/Metadata/global-metadata.dat')]:
        with (client / relative).open('rb') as stream:
            if hashlib.file_digest(stream, 'sha256').hexdigest() != proof['nativeFiles'][name]:
                raise ValueError(name + ': 文件哈希变化，拒绝沿用此版本的静态结论')
    pe = pefile.PE(str(client / 'GameAssembly.dll'), fast_load=True)
    directory = pe.OPTIONAL_HEADER.DATA_DIRECTORY[pefile.DIRECTORY_ENTRY['IMAGE_DIRECTORY_ENTRY_EXCEPTION']]
    if directory.Size % 12:
        raise ValueError('异常的 PE 范围表')
    bounds = {begin: (end, unwind) for begin, end, unwind in struct.iter_unpack(
        '<III', pe.get_data(directory.VirtualAddress, directory.Size)) if begin}
    with (client / 'GameAssembly.dll').open('rb') as stream, mmap.mmap(
            stream.fileno(), 0, access=mmap.ACCESS_READ) as data:
        for method in proof['nativeMethods']:
            begin, end = int(method['rva'], 16), int(method['endRva'], 16)
            if bounds[begin] != (end, int(method['unwindRva'], 16)):
                raise ValueError('方法 PE 范围不符: ' + str(method['index']))
            section = pe.get_section_by_rva(begin)
            if not section or begin - section.VirtualAddress + end - begin > section.SizeOfRawData:
                raise ValueError('无完整文件范围')
            offset = pe.get_offset_from_rva(begin)
            actual = data[offset:offset + end - begin]
            if actual != bytes.fromhex(method['bytesHex']) or hashlib.sha256(actual).hexdigest() != method['sha256']:
                raise ValueError('方法完整字节不符: ' + str(method['index']))
    print(json.dumps({'passed': True, 'matchedMethods': len(proof['nativeMethods']),
                      'matchedBytes': proof['nativeBytes'], 'nativeFiles': proof['nativeFiles'],
                      'clientExecutableStarted': False, 'clientModified': False,
                      'clientGameplayVerified': False}))


if __name__ == '__main__':
    main()
