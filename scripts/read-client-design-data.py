"""按本机固定 4.6 客户端的实际路径转换，只读查找或导出设计数据。

导出的是原始 .bytes 记录，不是可安装的 JSON，也不代表玩法或客户端验收。
不启动客户端、不覆盖已有不同内容的文件、不向客户端目录写入。
"""
import argparse
import hashlib
import importlib.util
import json
import mmap
import struct
from pathlib import Path, PurePosixPath
from types import SimpleNamespace

MASK64 = (1 << 64) - 1
P1, P2, P3 = 0x9e3779b185ebca87, 0xc2b2ae3d27d4eb4f, 0x165667b19e3779f9
P4, P5 = 0x85ebca77c2b2ae63, 0x27d4eb2f165667c5
INDEX_SHA256 = '2836fe8dba9a28fd48a27eae0220179e7804e31167222ea3fabf3608e1cf6e89'
# Keep the legacy default: graph readers also pin their own record/schema evidence.
# The second index was checked after the installed client's D16728363 update.
INDEX_PROFILES = {
    INDEX_SHA256: (255, 4, 33, 141295),
    '5a5981c06b0d7d1cbff5f89b8450fefb40278c60a5ca10b092b3381094dfcbed': (255, 4, 33, 141295),
}


def rotate(value, bits):
    return ((value << bits) | (value >> (64 - bits))) & MASK64


def round_value(value, word):
    return rotate((value + word * P2) & MASK64, 31) * P1 & MASK64


def path_hash(data):
    """原生 1dd3ab30 的 XXH64，种子为 0；输入保留原始大小写和斜杠。"""
    size = len(data)
    position = 0
    if size >= 32:
        values = [(P1 + P2) & MASK64, P2, 0, -P1 & MASK64]
        while position <= size - 32:
            for index in range(4):
                values[index] = round_value(values[index], struct.unpack_from('<Q', data, position + index * 8)[0])
            position += 32
        result = sum(rotate(value, shift) for value, shift in zip(values, (1, 7, 12, 18))) & MASK64
        for value in values:
            result = ((result ^ round_value(0, value)) * P1 + P4) & MASK64
    else:
        result = P5
    result = (result + size) & MASK64
    while position <= size - 8:
        result = (rotate(result ^ round_value(0, struct.unpack_from('<Q', data, position)[0]), 27) * P1 + P4) & MASK64
        position += 8
    if position <= size - 4:
        result = (rotate(result ^ (struct.unpack_from('<I', data, position)[0] * P1 & MASK64), 23) * P2 + P3) & MASK64
        position += 4
    while position < size:
        result = rotate(result ^ (data[position] * P5 & MASK64), 11) * P1 & MASK64
        position += 1
    result ^= result >> 33
    result = result * P2 & MASK64
    result ^= result >> 29
    result = result * P3 & MASK64
    return result ^ (result >> 32)


def source_path(value):
    path = PurePosixPath(value)
    if ('\\' in value or ':' in value or path.is_absolute() or '..' in path.parts
            or str(path) != value or path.suffix != '.json'
            or path.parts[0] not in ('Config', 'ExcelOutput', 'ExcelOutputGameCore')):
        raise ValueError(f'不是规范的相对设计数据 JSON 路径: {value}')
    return path


def outside_client(path, client):
    resolved = path.resolve()
    if resolved == client or client in resolved.parents:
        raise ValueError('拒绝向客户端目录输出文件')
    return resolved


def parse_index(directory, bundled_directory=None, *, index_sha256=INDEX_SHA256):
    if index_sha256 not in INDEX_PROFILES:
        raise ValueError('未核对的设计数据索引 SHA256')
    candidates = list(directory.glob('DesignV_*.bytes'))
    verified = []
    for candidate in candidates:
        raw = candidate.read_bytes()
        if hashlib.sha256(raw).hexdigest() == index_sha256:
            verified.append((candidate, raw))
    if len(verified) != 1:
        raise ValueError('未找到唯一匹配固定 SHA256 的设计数据索引，拒绝沿用解析常量')
    path, data = verified[0]
    header = INDEX_PROFILES[index_sha256]
    if len(data) < 16 or struct.unpack_from('>IIII', data) != header:
        raise ValueError('设计数据索引头不匹配')
    position = 16
    chunks = []
    entries = {}
    count = 0
    for index in range(header[2]):
        if position + 36 > len(data):
            raise ValueError('设计数据块头截断')
        md5 = data[position + 8:position + 24].hex()
        base, size, total = struct.unpack_from('>III', data, position + 24)
        position += 36
        if total > 200000 or position + 16 * total + 3 > len(data):
            raise ValueError('设计数据记录数量或范围非法')
        file = directory / (md5 + '.bytes')
        if not file.exists() and bundled_directory is not None:
            candidate = bundled_directory / file.name
            if candidate.is_file():
                file = candidate
        chunk = {'index': index, 'file': str(file),
                 'declaredMD5': md5, 'base': base, 'size': size, 'count': total}
        for _ in range(total):
            key = data[position:position + 8].hex()
            length, offset = struct.unpack_from('>II', data, position + 8)
            position += 16
            if key in entries or base + offset + length > size:
                raise ValueError('设计数据记录重复或超出数据块范围')
            entries[key] = (chunk, length, offset)
        tag, length = data[position:position + 2]
        position += 2
        if tag != 0 or length >= 16 or position + length + 1 > len(data):
            raise ValueError('设计数据语言标记非法')
        chunk['locale'] = data[position:position + length].decode('utf-8')
        position += length
        if data[position] != 128:
            raise ValueError('设计数据块结束标记非法')
        position += 1
        chunks.append(chunk)
        count += total
    if position != len(data) or count != header[3]:
        raise ValueError('设计数据索引未完整读取或记录总数不匹配')
    return path, chunks, entries


def extract(args):
    client = args.client_dir.resolve()
    output = outside_client(args.output, client)
    target = outside_client(args.extract_dir, client) if args.extract_dir else None
    paths = list(args.paths)
    if args.paths_file:
        extra = json.loads(args.paths_file.read_text(encoding='utf-8-sig'))
        if not isinstance(extra, list) or not all(isinstance(value, str) for value in extra):
            raise ValueError('路径文件必须是 JSON 字符串列表')
        paths.extend(extra)
    if not 1 <= len(paths) <= 256 or len(set(paths)) != len(paths):
        raise ValueError('每次只接受 1..256 个不重复的规范路径')
    paths = [source_path(value) for value in paths]
    spec = importlib.util.spec_from_file_location('client46_metadata', Path(__file__).with_name('analyze-client-46.py'))
    metadata = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(metadata)
    evidence = metadata.extract(SimpleNamespace(
        client_dir=client, types=[], fields=False, inheritance=False, type_contains=[],
        method_indices=[], method_rvas=[], type_slots=[], method_slots=[],
        string_slots=[0x96f5968, 0x9729e18], method_names=[],
        method_name_contains=[], disassemble=0))
    literals = {row['slot']: row['value'] for row in evidence['stringLiteralSlots']}
    prefix, suffix = literals['0x9729e18'], literals['0x96f5968']
    if (prefix, suffix) != ('BakedConfig/', '.bytes'):
        raise ValueError('原生 JSON 路径转换交叉核对失败')
    directory = client / 'StarRail_Data/Persistent/DesignData/Windows'
    bundled_directory = client / 'StarRail_Data/StreamingAssets/DesignData/Windows'
    index_sha256 = getattr(args, 'index_sha256', INDEX_SHA256)
    index_path, chunks, index = parse_index(directory, bundled_directory, index_sha256=index_sha256)
    results, writes, verified_chunks = [], [], {}
    for path in paths:
        baked = prefix + str(path.with_suffix('')) + suffix
        key = path_hash(baked.encode('utf-8')).to_bytes(8, 'big').hex()
        row = {'sourcePath': str(path), 'bakedPath': baked, 'entryKey': key}
        results.append(row)
        if key not in index:
            row['status'] = 'index_entry_missing'
            continue
        chunk, length, offset = index[key]
        row.update(chunk=chunk['index'], locale=chunk['locale'], sourceFile=chunk['file'],
                   offset=chunk['base'] + offset, length=length)
        file = Path(chunk['file'])
        if not file.is_file():
            row['status'] = 'chunk_file_missing'
            continue
        state = file.stat()
        if state.st_size != chunk['size']:
            raise ValueError('设计数据块文件长度与已核对索引不匹配')
        with file.open('rb') as stream, mmap.mmap(stream.fileno(), 0, access=mmap.ACCESS_READ) as data:
            signature = (state.st_size, state.st_mtime_ns)
            cached = verified_chunks.get(str(file))
            if not cached or cached['signature'] != signature:
                digest = hashlib.md5(data).hexdigest()
                if digest != chunk['declaredMD5']:
                    raise ValueError('设计数据块 MD5 与已核对索引不匹配')
                verified_chunks[str(file)] = {'signature': signature, 'md5': digest,
                                              'sha256': hashlib.sha256(data).hexdigest()}
            raw = bytes(data[row['offset']:row['offset'] + length])
        if len(raw) != length:
            raise ValueError('设计数据记录读取截断')
        row.update(status='read', sha256=hashlib.sha256(raw).hexdigest())
        if target:
            destination = target.joinpath(*path.with_suffix('.bytes').parts)
            destination = outside_client(destination, client)
            if target not in destination.parents:
                raise ValueError('导出目标超出指定目录')
            if destination.exists() and (not destination.is_file()
                    or hashlib.sha256(destination.read_bytes()).hexdigest() != row['sha256']):
                raise FileExistsError('已有不同内容的导出文件，拒绝覆盖')
            writes.append((destination, raw))
            row['extractedFile'] = str(destination)
    # All pre-existing targets are checked before the first write.
    for destination, raw in writes:
        destination.parent.mkdir(parents=True, exist_ok=True)
        if not destination.exists():
            with destination.open('xb') as stream:
                stream.write(raw)
    report = {'clientExecutableStarted': False, 'clientModified': False, 'clientAccepted': False,
              'jsonInstalled': False, 'files': evidence['files'], 'index': str(index_path),
              'indexSHA256': index_sha256, 'indexFullyConsumed': True, 'indexEntryCount': len(index),
              'verifiedChunks': {file: {key: value for key, value in row.items() if key != 'signature'}
                                 for file, row in verified_chunks.items()},
              'missingChunks': [row['index'] for row in chunks if not Path(row['file']).is_file()],
              'bundledChunks': [row['index'] for row in chunks if Path(row['file']).parent == bundled_directory],
              'missingCoreChunks': [row['index'] for row in chunks if not row['locale'] and not Path(row['file']).is_file()],
              'missingLocaleChunks': [row['index'] for row in chunks if row['locale'] and not Path(row['file']).is_file()],
              'pathLiteralEvidence': evidence['stringLiteralSlots'], 'records': results}
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    return report


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--client-dir', type=Path, required=True)
    parser.add_argument('--paths', nargs='*', default=[])
    parser.add_argument('--paths-file', type=Path)
    parser.add_argument('--index-sha256', choices=INDEX_PROFILES, default=INDEX_SHA256,
                        help='显式选择已核对索引；默认继续锁定旧版，不能自动升级图读取证据')
    parser.add_argument('--extract-dir', type=Path)
    parser.add_argument('--output', type=Path, default=Path('build/client46-design-records.json'))
    arguments = parser.parse_args()
    report = extract(arguments)
    print(json.dumps({'output': str(arguments.output), 'requested': len(report['records']),
                      'read': sum(row['status'] == 'read' for row in report['records']),
                      'clientAccepted': False, 'jsonInstalled': False}, ensure_ascii=True))
