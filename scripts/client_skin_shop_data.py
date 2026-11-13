"""为固定 4.6 客户端准备三条橱窗商品数据；安装前必须正常退出游戏。"""
import argparse
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import struct
import tempfile

ROOT = Path(__file__).resolve().parents[1]
CLIENT_DIR = Path('StarRail_Data/Persistent/DesignData/Windows')
INDEX_SHA = 'bcac71ed6783dfd447de127bdcab7c40ae4e21d488c39cba1af6ef6aa801b608'
RECORD_KEY = 'c0c76481e9fd5adc'
RECORD_SHA = 'b26ca7cffba9477bbc46b3fba4f47458d7bf55b67f99ab151e6d3926853be2d6'
SOURCE_CHUNK_MD5 = '1efaad7a4e7c92a8a12c66cffbe3b50a'
ENTRIES = [(107013, 1141501, 3, 1680, 94), (107015, 1150501, 1, 2680, 93),
           (107016, 1140901, 3, 1350, 92)]
# Pinned ShopGoodsConfigRow.FromBinary 101473 / 1daa6090, mask bits and field offsets.
FIELDS = ['GoodsID', 'ItemID', 'ItemGroupID', 'ItemCount', 'Level', 'Rank', 'CurrencyList',
          'CurrencyCostList', 'GoodsSortID', 'LimitType1', 'LimitValue1List', 'LimitType2',
          'LimitValue2List', 'OnShelfType1', 'OnShelfValue1List', 'OnShelfType2',
          'OnShelfValue2List', 'LimitTimes', 'RefreshType', 'CycleDays', 'RefreshOffset',
          'IsLimitedTimePurchase', 'ShopID', 'ScheduleDataID', 'IsOnSale', 'TagType',
          'TagParam', 'ActivityModuleID', 'IsNew']
ARRAYS = {'CurrencyList', 'CurrencyCostList', 'LimitValue1List', 'LimitValue2List',
          'OnShelfValue1List', 'OnShelfValue2List'}
ENUMS = {'LimitType1', 'LimitType2', 'OnShelfType1', 'OnShelfType2', 'RefreshType', 'TagType'}
BOOLS = {'IsLimitedTimePurchase', 'IsOnSale', 'IsNew'}


def module(name, filename):
    spec = importlib.util.spec_from_file_location(name, ROOT / 'scripts' / filename)
    result = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(result)
    return result


lua = module('skin_shop_index', 'client_lua_map_compat.py')
graphs = module('skin_shop_reader', 'decode-client-level-graphs.py')


def sha(data):
    return hashlib.sha256(data).hexdigest()


def signed(value):
    if not -(2**31) <= value < 2**31:
        raise ValueError('有符号整数越界')
    return lua.var((value << 1) ^ (value >> 31))


def decode_table(raw):
    reader = graphs.Reader(raw, {})
    if reader.uvar() != 0:
        raise ValueError('商品表格式变化')
    count = reader.integer(32, True)
    if not 0 <= count <= 10000:
        raise ValueError('商品数量越界')
    rows, identities = [], set()
    for _ in range(count):
        start = reader.pos
        mask = reader.uvar()
        if mask >> len(FIELDS):
            raise ValueError('商品字段掩码包含未知位')
        row = {}
        for bit, name in enumerate(FIELDS):
            if not mask & (1 << bit):
                continue
            if name in ARRAYS:
                size = reader.integer(32, True)
                if not -1 <= size <= 4096:
                    raise ValueError('商品数组长度越界')
                row[name] = None if size == -1 else [reader.integer(32, False) for _ in range(size)]
            elif name in BOOLS:
                row[name] = reader.read({'kind': 2})
            else:
                row[name] = reader.integer(32, name in ENUMS or name == 'RefreshOffset')
        if row.get('GoodsID', 0) <= 0 or row['GoodsID'] in identities:
            raise ValueError('商品编号缺失或重复')
        identities.add(row['GoodsID'])
        rows.append((row, raw[start:reader.pos]))
    if reader.pos != len(raw):
        raise ValueError('商品表没有完整消费')
    return rows


def encode_row(row):
    if set(row) - set(FIELDS):
        raise ValueError('未知商品字段')
    result = bytearray(lua.var(sum(1 << bit for bit, name in enumerate(FIELDS) if name in row)))
    for name in FIELDS:
        if name not in row:
            continue
        value = row[name]
        if name in ARRAYS:
            result += signed(-1 if value is None else len(value))
            if value is not None:
                for item in value:
                    result += lua.var(item)
        elif name in BOOLS:
            if type(value) is not bool:
                raise ValueError('商品布尔字段不合法')
            result += bytes([int(value)])
        elif name in ENUMS or name == 'RefreshOffset':
            result += signed(value)
        else:
            result += lua.var(value)
    return bytes(result)


def append_rows(raw):
    before = decode_table(raw)
    by_id = {row['GoodsID']: row for row, _ in before}
    for source, item, currency, price in [(107012, 1150501, 1, 2680), (107014, 1140901, 3, 1350)]:
        row = by_id.get(source, {})
        if (row.get('ItemID'), row.get('CurrencyList'), row.get('CurrencyCostList'), row.get('ShopID')) != (item, [currency], [price], 996):
            raise ValueError('原推荐商品与定价来源不符')
    template = by_id.get(107002, {})
    if (template.get('ItemID'), template.get('LimitType1'), template.get('LimitTimes'), template.get('ShopID')) != (1131001, 9, 1, 107):
        raise ValueError('常规时装商品模板不符')
    additions = []
    for goods, item, currency, price, order in ENTRIES:
        if goods in by_id:
            raise ValueError('新增商品编号冲突')
        row = dict(template, GoodsID=goods, ItemID=item, CurrencyList=[currency],
                   CurrencyCostList=[price], GoodsSortID=order, ShopID=107)
        additions.append((row, encode_row(row)))
    result = b'\x00' + signed(len(before) + len(additions))
    result += b''.join(original for _, original in before) + b''.join(encoded for _, encoded in additions)
    after = decode_table(result)
    if after[:len(before)] != before or after[len(before):] != additions:
        raise ValueError('商品表补丁未保持原有记录')
    return result


def patch_record(raw):
    if len(raw) != 26495 or sha(raw) != RECORD_SHA:
        raise ValueError('商品表不是已核对原件')
    if len(decode_table(raw)) != 900:
        raise ValueError('商品表行数不符')
    return append_rows(raw)


def read_index(raw):
    if sha(raw) != INDEX_SHA or len(raw) < 16 or struct.unpack_from('>IIII', raw) != (255, 4, 33, 141295):
        raise ValueError('设计数据索引不是已核对版本')
    pos, chunks = 16, []
    for _ in range(33):
        if pos + 36 > len(raw):
            raise ValueError('设计数据块截断')
        chunk = {'meta': pos, 'digest': raw[pos + 8:pos + 24].hex()}
        chunk['base'], chunk['size'], count = struct.unpack_from('>III', raw, pos + 24)
        pos += 36
        chunk['entries'] = []
        for _ in range(count):
            if pos + 16 > len(raw):
                raise ValueError('设计数据记录截断')
            length, offset = struct.unpack_from('>II', raw, pos + 8)
            if chunk['base'] + offset + length > chunk['size']:
                raise ValueError('设计数据记录越界')
            chunk['entries'].append({'meta': pos, 'key': raw[pos:pos + 8].hex(),
                                     'length': length, 'offset': chunk['base'] + offset})
            pos += 16
        if pos + 2 > len(raw):
            raise ValueError('设计数据语言标记截断')
        tag, length = raw[pos:pos + 2]
        pos += 2
        if tag != 0 or length >= 16 or pos + length >= len(raw) or raw[pos + length] != 128:
            raise ValueError('设计数据语言标记变化')
        chunk['locale'] = raw[pos:pos + length].decode('utf-8')
        pos += length + 1
        chunks.append(chunk)
    if pos != len(raw) or sum(len(c['entries']) for c in chunks) != 141295:
        raise ValueError('设计数据索引没有完整消费')
    return chunks


def make_plan(client, manifest=ROOT / '.local/client/patch-manifest.json'):
    client = client.resolve()
    launcher = module('skin_shop_launcher', 'start-local-client.py')
    document, bound, _ = launcher.load_manifest(manifest)
    if client != bound:
        raise ValueError('客户端根目录不符')
    for relative, expected in document['clientFiles'].items():
        if launcher.sha(launcher.checked_path(client, relative)) != expected:
            raise ValueError('客户端程序文件变化')
    directory = (client / CLIENT_DIR).resolve()
    if not directory.is_relative_to(client):
        raise ValueError('设计数据目录超出客户端')
    matches = [p for p in directory.glob('DesignV_*.bytes') if sha(p.read_bytes()) == INDEX_SHA]
    if len(matches) != 1:
        raise ValueError('未找到唯一匹配的设计数据索引')
    index_path = matches[0]
    original_index = index_path.read_bytes()
    chunks = read_index(original_index)
    matches = [(c, e) for c in chunks for e in c['entries'] if e['key'] == RECORD_KEY]
    if len(matches) != 1:
        raise ValueError('商品表记录不唯一')
    chunk, entry = matches[0]
    if chunk['digest'] != SOURCE_CHUNK_MD5 or chunk['locale']:
        raise ValueError('商品表分块来源变化')
    source = (directory / (chunk['digest'] + '.bytes')).read_bytes()
    if len(source) != chunk['size'] or hashlib.md5(source).hexdigest() != chunk['digest']:
        raise ValueError('商品表分块校验失败')
    original = source[entry['offset']:entry['offset'] + entry['length']]
    updated = patch_record(original)
    index = bytearray(original_index)
    filename, packed = lua.changed_chunk(index, chunk, entry, source, updated)
    target = directory / filename
    if target.exists() and target.read_bytes() != packed:
        raise ValueError('目标分块已存在不同内容')
    return {'client': client, 'directory': directory, 'indexPath': index_path,
            'oldIndex': original_index, 'index': bytes(index), 'targetPath': target,
            'chunk': packed, 'recordSHA256': sha(updated)}


def prepare(client, output, manifest=ROOT / '.local/client/patch-manifest.json'):
    output = output.resolve()
    if output.is_relative_to(client.resolve()):
        raise ValueError('准备目录不得位于客户端内')
    plan = make_plan(client, manifest)
    output.mkdir(parents=True, exist_ok=False)
    (output / plan['targetPath'].name).write_bytes(plan['chunk'])
    (output / plan['indexPath'].name).write_bytes(plan['index'])
    report = {'client': str(plan['client']), 'oldIndexSHA256': INDEX_SHA,
              'indexName': plan['indexPath'].name, 'newIndexSHA256': sha(plan['index']),
              'chunkName': plan['targetPath'].name, 'chunkSHA256': sha(plan['chunk']),
              'recordSHA256': plan['recordSHA256'], 'clientModified': False}
    (output / 'prepared.json').write_text(json.dumps(report, indent=2) + '\n', encoding='utf-8')
    return report


def atomic_replace(path, raw, expected):
    if sha(path.read_bytes()) != expected:
        raise ValueError('索引已变化，拒绝覆盖')
    temporary = None
    try:
        with tempfile.NamedTemporaryFile(prefix='.hkrpg-skin-shop-', dir=path.parent, delete=False) as stream:
            temporary = Path(stream.name)
            stream.write(raw)
            stream.flush()
            os.fsync(stream.fileno())
        if sha(path.read_bytes()) != expected:
            raise ValueError('安装期间索引已变化')
        temporary.replace(path)
        temporary = None
    finally:
        if temporary is not None:
            temporary.unlink(missing_ok=True)


def install(prepared, backup, manifest=ROOT / '.local/client/patch-manifest.json'):
    launcher = module('skin_shop_install_launcher', 'start-local-client.py')
    launcher.require_stopped()
    report = json.loads((prepared / 'prepared.json').read_text(encoding='utf-8'))
    client = Path(report['client']).resolve()
    document, bound, _ = launcher.load_manifest(manifest)
    if bound != client:
        raise ValueError('客户端根目录不符')
    if (report.get('oldIndexSHA256') != INDEX_SHA
            or not re.fullmatch(r'DesignV_[0-9a-f]{32}\.bytes', report.get('indexName', ''))
            or not re.fullmatch(r'[0-9a-f]{32}\.bytes', report.get('chunkName', ''))):
        raise ValueError('待安装清单路径或来源不符')
    for relative, expected in document['clientFiles'].items():
        if launcher.sha(launcher.checked_path(client, relative)) != expected:
            raise ValueError('客户端程序文件变化')
    new_index = (prepared / report['indexName']).read_bytes()
    new_chunk = (prepared / report['chunkName']).read_bytes()
    if sha(new_index) != report['newIndexSHA256'] or sha(new_chunk) != report['chunkSHA256']:
        raise ValueError('待安装内容摘要不符')
    directory = (client / CLIENT_DIR).resolve()
    index_path, target_path = directory / report['indexName'], directory / report['chunkName']
    if sha(index_path.read_bytes()) == report['newIndexSHA256']:
        if not target_path.is_file() or target_path.read_bytes() != new_chunk:
            raise ValueError('已安装的索引和分块不一致')
        return dict(report, clientModified=False, alreadyInstalled=True)
    plan = make_plan(client, manifest)
    if new_index != plan['index'] or new_chunk != plan['chunk']:
        raise ValueError('待安装内容与重新核对结果不符')
    backup.mkdir(parents=True, exist_ok=False)
    (backup / plan['indexPath'].name).write_bytes(plan['oldIndex'])
    (backup / 'manifest.json').write_text(json.dumps(report, indent=2) + '\n', encoding='utf-8')
    if not plan['targetPath'].exists():
        with plan['targetPath'].open('xb') as stream:
            stream.write(new_chunk)
            stream.flush()
            os.fsync(stream.fileno())
    launcher.require_stopped()
    if plan['targetPath'].read_bytes() != new_chunk:
        raise ValueError('安装期间分块已变化')
    atomic_replace(plan['indexPath'], new_index, INDEX_SHA)
    try:
        if sha(plan['indexPath'].read_bytes()) != report['newIndexSHA256'] or plan['targetPath'].read_bytes() != new_chunk:
            raise RuntimeError('安装后的索引或分块校验失败')
    except Exception:
        if sha(plan['indexPath'].read_bytes()) == report['newIndexSHA256']:
            atomic_replace(plan['indexPath'], plan['oldIndex'], report['newIndexSHA256'])
        raise
    return dict(report, clientModified=True, backup=str(backup.resolve()))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--prepare-dir', type=Path, required=True)
    parser.add_argument('--manifest', type=Path, default=ROOT / '.local/client/patch-manifest.json')
    parser.add_argument('--apply', action='store_true')
    parser.add_argument('--backup-dir', type=Path)
    args = parser.parse_args()
    if not (args.prepare_dir / 'prepared.json').exists():
        client = Path(json.loads(args.manifest.read_text('utf-8-sig'))['clientRoot'])
        report = prepare(client, args.prepare_dir, args.manifest)
    else:
        report = json.loads((args.prepare_dir / 'prepared.json').read_text(encoding='utf-8'))
    if args.apply:
        if args.backup_dir is None:
            raise ValueError('安装必须明确备份目录')
        report = install(args.prepare_dir, args.backup_dir, args.manifest)
    print(json.dumps(report, ensure_ascii=True))


if __name__ == '__main__':
    main()
