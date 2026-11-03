"""为已核对的国服 4.6 地图页补缺省参数；先准备，停游戏后备份安装，不启动客户端。"""
import argparse
import hashlib
import importlib.util
import json
import os
import re
import struct
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
LUA = 'StarRail_Data/Persistent/Lua/Windows/'
PAGE = 'Ui/Map/SwitchAreaPage'
PARAM = 'Ui/Map/SwitchAreaPageInitParam'
INDEX_SHA = '619db3bdd4730d92ada44132b7badcdde7d11159df4d2fea10fae36bd8e50346'
PAGE_SHA = '896539349948041287f389acb119a21abade22c70f0255c95a8f5e5a6637c184'
PARAM_SHA = '4301b2a398c9307ae2a287705173861483e3d9ce6d139f19d5477101aff6c6db'


def require(value, message):
    if not value:
        raise ValueError(message)


def sha(raw):
    return hashlib.sha256(raw).hexdigest()


def var(value):
    require(0 <= value < 2**32, '无效变长整数')
    out = bytearray()
    while value >= 128:
        out.append((value & 127) | 128)
        value >>= 7
    return bytes(out + bytes([value]))


class Bytecode:
    """只接受本机原件使用的 Luau 4/1 序列化；保留所有未修改原型的原始字节。"""
    def __init__(self, raw):
        self.raw, self.pos = raw, 0
        require(len(raw) <= 1024 * 1024 and self.take(2) == b'\x04\x01', '非已核对字节码版本')
        self.strings = [self.take(self.uv()).decode('utf-8') for _ in range(self.uv())]
        self.body = self.pos
        self.protos = []
        count = self.uv()
        require(0 < count < 4096, '原型数量越界')
        for _ in range(count):
            p = {'start': self.pos, 'header': self.take(5)}
            self.take(self.uv())  # 原始类型资料完整保留。
            p['code_count'] = self.pos
            p['code'] = [struct.unpack('<I', self.take(4))[0] for _ in range(self.uv())]
            p['constants_count'] = self.pos
            constants_count = self.uv()
            p['constants_start'] = self.pos
            constants = []
            for _ in range(constants_count):
                kind = self.take(1)[0]
                if kind == 0: value = None
                elif kind == 1: value = bool(self.take(1)[0])
                elif kind == 2: value = struct.unpack('<d', self.take(8))[0]
                elif kind == 3: value = self.string()
                elif kind == 4: value = {'import': struct.unpack('<I', self.take(4))[0]}
                elif kind == 5: value = {'table': [self.uv() for _ in range(self.uv())]}
                elif kind == 6: value = {'closure': self.uv()}
                elif kind == 7: value = {'vector': self.take(16).hex()}
                else: raise ValueError('未知常量类型')
                constants.append(value)
            p['constants'], p['constants_end'] = constants, self.pos
            p['children'] = [self.uv() for _ in range(self.uv())]
            p['line'], p['name'] = self.uv(), self.string()
            p['line_start'] = self.pos
            lines = []
            if self.take(1)[0]:
                gap = self.take(1)[0]
                require(gap < 32, '行号间隔越界')
                delta = self.take(len(p['code']))
                base = [struct.unpack('<i', self.take(4))[0]
                        for _ in range(((len(p['code']) - 1) >> gap) + 1)]
                absolute, accumulated = [], 0
                for value in base:
                    accumulated += value
                    absolute.append(accumulated)
                offset = 0
                for pc, value in enumerate(delta):
                    offset = (offset + value) & 255
                    lines.append(absolute[pc >> gap] + offset)
            p['lines'], p['locals'], p['upnames'] = lines, [], []
            p['debug'] = bool(self.take(1)[0])
            if p['debug']:
                p['locals'] = [(self.uv(), self.uv(), self.uv(), self.take(1)[0]) for _ in range(self.uv())]
                p['upnames'] = [self.uv() for _ in range(self.uv())]
                require(len(p['upnames']) == p['header'][2], 'upvalue 数量不符')
            p['end'] = self.pos
            self.protos.append(p)
        self.main_start, self.main = self.pos, self.uv()
        require(self.main < count and self.pos == len(raw), '字节码存在未解析尾部')

    def take(self, size):
        require(0 <= size <= len(self.raw) - self.pos, '字节码截断')
        result = self.raw[self.pos:self.pos + size]
        self.pos += size
        return result

    def uv(self):
        value = 0
        for n in range(5):
            b = self.take(1)[0]
            value |= (b & 127) << (7 * n)
            if b < 128:
                require(value < 2**32, '整数溢出')
                return value
        raise ValueError('过长变长整数')

    def string(self):
        index = self.uv()
        require(index <= len(self.strings), '字符串索引越界')
        return self.strings[index - 1] if index else None


def patch_record(raw):
    require(sha(raw) == PAGE_SHA, '地图页不是已核对原件')
    decoded = Bytecode(raw)
    matches = [p for p in decoded.protos if p['name'] == 'init']
    require(len(matches) == 1, 'init 原型不唯一')
    p = matches[0]
    require(p['header'] == bytes([8, 2, 1, 0, 0])
            and p['line'] == 54 and len(p['code']) == 25
            and p['constants'] == ['GetPlayerLocatedSubTab', '_player_located_sub_tab', '_init_data',
                                   '_symbol_detail_active', 'mapping_info_id', 'sub_mission_id',
                                   'entity_pair', 'sub_map_id', '_init_map_entry_info'], 'init 原型结构变化')
    strings = list(decoded.strings)

    def text_constant(text):
        if text not in strings:
            strings.append(text)
        return b'\x03' + var(strings.index(text) + 1)

    first = len(p['constants'])
    # Luau 4/1: JUMPIF, GETIMPORT(+AUX), LOADK, CALL, MOVE。
    # 只在 nil/false 时调用原生 G.new 构造器，显式参数直接跳过六个字。
    imported = 0x80000000 | (first << 20) | ((first + 1) << 10)
    constants = text_constant('G') + text_constant('new') + b'\x04' + struct.pack('<I', imported)
    constants += text_constant('Ui.Map.SwitchAreaPageInitParam')
    prefix = [25 | (1 << 8) | (5 << 16),
              12 | (2 << 8) | ((first + 2) << 16), imported,
              5 | (3 << 8) | ((first + 3) << 16),
              21 | (2 << 8) | (2 << 16) | (2 << 24), 6 | (1 << 8) | (2 << 16)]
    code = prefix + p['code']
    body = raw[p['start']:p['code_count']] + var(len(code)) + struct.pack('<' + 'I' * len(code), *code)
    body += var(len(p['constants']) + 4) + raw[p['constants_start']:p['constants_end']] + constants
    body += raw[p['constants_end']:p['line_start']]
    require(len(p['lines']) == len(p['code']) and p['debug'], '缺少完整原始行号资料')
    lines = [p['line']] * len(prefix) + p['lines']
    # gap=0，每个字独立记录绝对行增量；不改变原代码的行号。
    previous = 0
    body += b'\x01\x00' + bytes(len(code))
    for line in lines:
        body += struct.pack('<i', line - previous)
        previous = line
    body += b'\x01' + var(len(p['locals']))
    for name, start, end, register in p['locals']:
        body += var(name) + var(start + len(prefix) if start else 0) + var(end + len(prefix)) + bytes([register])
    body += var(len(p['upnames'])) + b''.join(var(name) for name in p['upnames'])
    table = b''.join(var(len(text.encode('utf-8'))) + text.encode('utf-8') for text in strings)
    result = raw[:2] + var(len(strings)) + table + raw[decoded.body:p['start']] + body + raw[p['end']:]
    checked = Bytecode(result)
    require(len(checked.protos) == len(decoded.protos) and checked.main == decoded.main, '修复改变了原型图')
    return result


def read_index(raw):
    require(len(raw) >= 16 and raw[:8] == bytes.fromhex('000000ff00000004'), 'Lua 索引版本不符')
    count, total = struct.unpack_from('>II', raw, 8)
    require(count == 14 and total == 19644, 'Lua 索引目录不符')
    pos, chunks = 16, []
    for _ in range(count):
        require(pos + 36 <= len(raw), '索引截断')
        chunk = {'meta': pos, 'digest': raw[pos + 8:pos + 24].hex()}
        chunk['base'], chunk['size'], n = struct.unpack_from('>III', raw, pos + 24)
        pos += 36
        require(n <= total and pos + n * 16 <= len(raw), '记录数量越界')
        chunk['entries'] = []
        for _ in range(n):
            length, offset = struct.unpack_from('>II', raw, pos + 8)
            require(chunk['base'] + offset + length <= chunk['size'], 'Lua 记录越界')
            chunk['entries'].append(dict(meta=pos, key=raw[pos:pos + 8].hex(), length=length,
                                         offset=chunk['base'] + offset))
            pos += 16
        require(pos + 2 <= len(raw), '地区资料截断')
        tag, length = raw[pos:pos + 2]
        pos += 2
        require(tag == 0 and length < 16 and pos + length < len(raw), '未知地区资料')
        chunk['locale'] = raw[pos:pos + length].decode('utf-8')
        pos += length
        require(raw[pos] == 128, '地区尾标记不符')
        pos += 1
        chunks.append(chunk)
    require(pos == len(raw) and sum(len(c['entries']) for c in chunks) == total, '索引尾部或总数不符')
    return chunks


def pointer_digest(raw):
    require(len(raw) == 66 and raw[:8] == b'SRMI\0\3\0\1'
            and struct.unpack_from('<I', raw, 8)[0] == 66, 'M_LuaV 版本或长度不符')
    return b''.join(raw[n:n + 4][::-1] for n in range(28, 44, 4)).hex()


def changed_chunk(index, chunk, entry, original, replacement):
    require(len(original) == chunk['size'] and hashlib.md5(original).hexdigest() == chunk['digest'], 'Lua 分块摘要不符')
    start, end = entry['offset'], entry['offset'] + entry['length']
    change = len(replacement) - entry['length']
    result = original[:start] + replacement + original[end:]
    digest = hashlib.md5(result).hexdigest()
    index[chunk['meta'] + 8:chunk['meta'] + 24] = bytes.fromhex(digest)
    struct.pack_into('>I', index, chunk['meta'] + 28, len(result))
    for row in chunk['entries']:
        require(row is entry or row['offset'] + row['length'] <= start or row['offset'] >= end, '记录重叠')
        length = len(replacement) if row is entry else row['length']
        offset = row['offset'] + (change if row['offset'] >= end else 0) - chunk['base']
        struct.pack_into('>II', index, row['meta'] + 8, length, offset)
    return digest + '.bytes', result


def build_profile_files(client, *, index_sha, page, companion, companion_sha, patcher):
    """在已核对的现有索引上替换一个页面，并保留同包的既有兼容修复。"""
    directory = client / LUA
    pointer = (directory / 'M_LuaV.bytes').read_bytes()
    digest = pointer_digest(pointer)
    raw_index = (directory / ('LuaV_' + digest + '.bytes')).read_bytes()
    require(sha(raw_index) == index_sha and hashlib.md5(raw_index).hexdigest() == digest, '客户端 Lua 索引已变化')
    chunks = read_index(raw_index)
    first = chunks[0]
    manifest_chunk = (directory / (first['digest'] + '.bytes')).read_bytes()
    record = first['entries'][0]
    manifest = manifest_chunk[record['offset']:record['offset'] + record['length']]
    rows = [json.loads(line) for line in manifest.splitlines()]
    wanted = {row['Md5']: row['Path'] for row in rows if row['Path'] in (page, companion)}
    require(len(wanted) == 2 and set(wanted.values()) == {page, companion}, '页面目录缺失或重复')
    found = {}
    for chunk in chunks:
        data = (directory / chunk['locale'] / (chunk['digest'] + '.bytes')).read_bytes()
        require(len(data) == chunk['size'] and hashlib.md5(data).hexdigest() == chunk['digest'], '分块目录与内容不符')
        for entry in chunk['entries']:
            raw = data[entry['offset']:entry['offset'] + entry['length']]
            key = hashlib.md5(raw).hexdigest()
            if key in wanted:
                require(wanted[key] not in found, '原件记录重复')
                found[wanted[key]] = (chunk, entry, data, raw)
    require(set(found) == {page, companion} and sha(found[companion][3]) == companion_sha, '既有兼容页面不符')
    chunk, entry, data, original = found[page]
    require(chunk is not first, '页面不得覆盖模块目录所在分块')
    patched = patcher(original)
    require(patched != original, '缺少页面修复')
    index = bytearray(raw_index)
    filename, packed = changed_chunk(index, chunk, entry, data, patched)
    old_md5, new_md5 = hashlib.md5(original).hexdigest(), hashlib.md5(patched).hexdigest()
    require(manifest.count(old_md5.encode()) == 1, '模块摘要引用不唯一')
    new_manifest = manifest.replace(old_md5.encode(), new_md5.encode())
    manifest_filename, manifest_data = changed_chunk(index, first, record, manifest_chunk, new_manifest)
    new_digest = hashlib.md5(index).hexdigest()
    updated_pointer = bytearray(pointer)
    updated_pointer[28:44] = b''.join(bytes.fromhex(new_digest)[n:n + 4][::-1] for n in range(0, 16, 4))
    require(pointer_digest(updated_pointer) == new_digest and len(index) == len(raw_index), '入口摘要编码不符')
    files = {filename: packed, manifest_filename: manifest_data,
             'LuaV_' + new_digest + '.bytes': bytes(index), 'M_LuaV.bytes': bytes(updated_pointer)}
    report = dict(passed=True, clientRoot=str(client.resolve()), originalPointerSHA256=sha(pointer),
                  originalIndexSHA256=sha(raw_index), originalPageSHA256=sha(original),
                  patchedPageSHA256=sha(patched), files={LUA + name: sha(raw) for name, raw in files.items()},
                  clientModified=False, clientExecuted=False)
    return report, files, pointer


def build_files(client):
    return build_profile_files(client, index_sha=INDEX_SHA, page=PAGE, companion=PARAM,
                               companion_sha=PARAM_SHA, patcher=patch_record)


def prepare(client, output, *, build=None):
    report, files, pointer = (build or build_files)(client)
    output.mkdir(parents=True, exist_ok=False)
    for name, content in files.items():
        (output / name).write_bytes(content)
    (output / 'original-M_LuaV.bytes').write_bytes(pointer)
    (output / 'prepared.json').write_text(json.dumps(report, indent=2) + '\n', 'utf-8')
    return report


def launcher_module():
    spec = importlib.util.spec_from_file_location('map_client_launcher', ROOT / 'scripts/start-local-client.py')
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def atomic(path, raw):
    temp = path.with_name(path.name + '.map-compat.tmp')
    with temp.open('xb') as stream:
        stream.write(raw)
        stream.flush()
        os.fsync(stream.fileno())
    os.replace(temp, path)


def install(prepared, manifest_path, backup, *, build=None):
    report = json.loads((prepared / 'prepared.json').read_text('utf-8'))
    launcher = launcher_module()
    launcher.require_stopped()
    document, client, _ = launcher.load_manifest(manifest_path)
    require(client == Path(report['clientRoot']).resolve(), '客户端根目录不符')
    for relative, expected in document['clientFiles'].items():
        require(launcher.sha(launcher.checked_path(client, relative)) == expected, '既有客户端文件变化')
    for relative, expected in document.get('clientLuaFiles', {}).items():
        require(launcher.sha(launcher.checked_path(client, relative)) == expected, '既有 Lua 兼容资源变化')
    files = {}
    for relative, expected in report['files'].items():
        require(relative.startswith(LUA) and re.fullmatch(r'(M_LuaV|LuaV_[a-f0-9]{32}|[a-f0-9]{32})\.bytes', relative[len(LUA):]), '未知补丁路径')
        content = (prepared / Path(relative).name).read_bytes()
        require(sha(content) == expected, '待安装文件变化')
        files[relative] = content
    pointer_path = launcher.checked_path(client, LUA + 'M_LuaV.bytes')
    before = pointer_path.read_bytes()
    if sha(before) == report['files'][LUA + 'M_LuaV.bytes']:
        require(all(launcher.sha(launcher.checked_path(client, name)) == report['files'][name] for name in files), '已安装补丁不完整')
        require(document.get('clientLuaFiles') == report['files'], '已安装入口与启动清单不一致')
        return dict(passed=True, alreadyInstalled=True, clientExecuted=False)
    require(sha(before) == report['originalPointerSHA256'], '客户端 Lua 入口已变化')
    expected, generated, _ = (build or build_files)(client)
    require(expected == report and all(content == generated[Path(relative).name]
                                      for relative, content in files.items()), '准备目录与原件重新计算的补丁不一致')
    original_manifest = manifest_path.read_bytes()
    require(not backup.resolve().is_relative_to(client), '备份不得写入客户端资源目录')
    backup.mkdir(parents=True, exist_ok=False)
    (backup / 'M_LuaV.bytes').write_bytes(before)
    (backup / 'patch-manifest.json').write_bytes(original_manifest)
    require(sha((backup / 'M_LuaV.bytes').read_bytes()) == sha(before), '备份校验失败')
    for relative, content in files.items():
        if relative == LUA + 'M_LuaV.bytes':
            continue
        target = launcher.checked_path(client, relative)
        if target.exists():
            require(target.read_bytes() == content, '同名文件存在且内容不同')
        else:
            with target.open('xb') as stream:
                stream.write(content)
                stream.flush()
                os.fsync(stream.fileno())
    launcher.require_stopped()
    require(pointer_path.read_bytes() == before and manifest_path.read_bytes() == original_manifest, '安装期间入口或清单变化')
    try:
        atomic(pointer_path, files[LUA + 'M_LuaV.bytes'])
        document['clientLuaFiles'] = report['files']
        atomic(manifest_path, (json.dumps(document, ensure_ascii=False, indent=2) + '\n').encode('utf-8'))
        require(all(launcher.sha(launcher.checked_path(client, relative)) == expected
                    for relative, expected in report['files'].items()), '安装后文件校验失败')
    except Exception:
        if pointer_path.read_bytes() == files[LUA + 'M_LuaV.bytes']:
            atomic(pointer_path, before)
        if json.loads(manifest_path.read_text('utf-8-sig')).get('clientLuaFiles') == report['files']:
            atomic(manifest_path, original_manifest)
        raise
    result = dict(passed=True, backup=str(backup.resolve()), filesVerified=len(files),
                  clientModified=True, clientExecuted=False, clientGameplayVerified=False)
    (backup / 'installation.json').write_text(json.dumps(result, indent=2) + '\n', 'utf-8')
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--manifest', type=Path, default=ROOT / '.local/client/patch-manifest.json')
    parser.add_argument('--prepare-dir', type=Path, required=True)
    parser.add_argument('--apply', action='store_true')
    parser.add_argument('--backup-dir', type=Path)
    args = parser.parse_args()
    if not (args.prepare_dir / 'prepared.json').exists():
        client = Path(json.loads(args.manifest.read_text('utf-8-sig'))['clientRoot'])
        result = prepare(client, args.prepare_dir)
    else:
        result = json.loads((args.prepare_dir / 'prepared.json').read_text('utf-8'))
    if args.apply:
        require(args.backup_dir is not None, '安装必须明确备份目录')
        result = install(args.prepare_dir, args.manifest, args.backup_dir)
    print(json.dumps(result, ensure_ascii=True))


if __name__ == '__main__':
    main()
