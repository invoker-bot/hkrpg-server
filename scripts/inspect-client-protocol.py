"""只读核对本机客户端协议证据；不执行客户端、不写客户端文件、不读登录缓存。"""
import argparse
import configparser
import hashlib
import json
import math
import mmap
import struct
from collections import Counter
from pathlib import Path

TARGETS = ('GetRogueInfoCsReq', 'StartRogueCsReq', 'EnterRogueMapRoomCsReq',
           'HandleRogueCommonPendingActionCsReq', 'HandleRogueCommonPendingActionScRsp',
           'MusicRhythmStartLevelCsReq', 'MusicRhythmFinishLevelCsReq', 'MusicRhythmSaveSongConfigDataCsReq',
           'global-metadata.dat', 'il2cpp_init', 'Google.Protobuf')


def inspect(path):
    result = {'path': path.name, 'size': path.stat().st_size}
    with path.open('rb') as stream, mmap.mmap(stream.fileno(), 0, access=mmap.ACCESS_READ) as data:
        result['sha256'] = hashlib.sha256(data).hexdigest()
        result['header'] = data[:16].hex()
        result['namedStringEvidence'] = {
            name: {'asciiOffset': data.find(name.encode()), 'utf16Offset': data.find(name.encode('utf-16le'))}
            for name in TARGETS}
        result['standardMetadataHeader'] = data[:4] == struct.pack('<I', 0xFAB11BAF)
        if result['standardMetadataHeader']:
            result['metadataVersion'] = struct.unpack_from('<I', data, 4)[0]
        if data[:2] == b'MZ':
            pe = struct.unpack_from('<I', data, 0x3c)[0]
            if data[pe:pe+4] != b'PE\0\0': raise ValueError('Invalid PE header')
            count, opt = struct.unpack_from('<H', data, pe+6)[0], struct.unpack_from('<H', data, pe+20)[0]
            sections = []
            for index in range(count):
                pos = pe + 24 + opt + index*40
                name = data[pos:pos+8].rstrip(b'\0').decode('ascii', errors='replace')
                size, rva, raw_size, raw = struct.unpack_from('<4I', data, pos+8)
                sample = data[raw:min(raw+65536, raw+raw_size)]
                entropy = -sum((n/len(sample))*math.log2(n/len(sample)) for n in Counter(sample).values()) if sample else 0
                sections.append({'name': name, 'rva': hex(rva), 'virtualSize': size,
                                 'rawOffset': raw, 'rawSize': raw_size, 'sampleEntropy': round(entropy, 4)})
            result['sections'] = sections
    return result


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--client-dir', type=Path, required=True)
    parser.add_argument('--output', type=Path, default=Path('build/client-protocol-evidence.json'))
    args = parser.parse_args()
    root = args.client_dir.resolve()
    config = configparser.ConfigParser(interpolation=None)
    config.read(root / 'config.ini', encoding='utf-8-sig')
    metadata = root / 'StarRail_Data/il2cpp_data/Metadata'
    report = {'clientVersion': config['General'].get('game_version'), 'gameBiz': config['General'].get('game_biz'),
              'clientExecutableStarted': False, 'clientModified': False, 'clientAccepted': False,
              'resolvedGameplayFields': [], 'files': [inspect(path) for path in [root/'GameAssembly.dll',
                  metadata/'global-metadata.dat', metadata/'startup-metadata.dat'] if path.is_file()]}
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    print(json.dumps(report, ensure_ascii=True, indent=2))
