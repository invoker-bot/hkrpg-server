"""升级已安装的 4.6 时装商品表；仅在游戏退出后备份并安装。"""
import argparse
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('fashion_shop_data', ROOT / 'scripts/client_skin_shop_data.py')
data = importlib.util.module_from_spec(spec)
spec.loader.exec_module(data)

INDEX_NAME = 'DesignV_702d7f1e6dc48d92b85f9ae4ca2b139b.bytes'
INDEX_SHA = '15fb9682f950182c3df789d218ac61c1aa2382aa39517d1628b34421f39953d6'
CHUNK_MD5 = '116a2fb5c6f13adac0335af08dd7b041'
RECORD_SHA = '501dff4ad952ca11a91274e7adb2444a28995cf172c665d327f8a97c21cbb797'


def launcher():
    return data.module('fashion_client_launcher', 'start-local-client.py')


def validate_client(client, manifest, launch):
    document, bound, _ = launch.load_manifest(manifest)
    if client.resolve() != bound:
        raise ValueError('客户端根目录不符')
    for relative, expected in {**document['clientFiles'], **document.get('clientLuaFiles', {})}.items():
        if launch.sha(launch.checked_path(bound, relative)) != expected:
            raise ValueError('既有客户端或 Lua 资源变化')


def make_plan(client, manifest, launch):
    client = client.resolve()
    validate_client(client, manifest, launch)
    directory = (client / data.CLIENT_DIR).resolve()
    if not directory.is_relative_to(client):
        raise ValueError('设计数据目录超出客户端')
    index_path = directory / INDEX_NAME
    original_index = index_path.read_bytes()
    chunks = data.read_index(original_index, index_sha=INDEX_SHA)
    matches = [(chunk, entry) for chunk in chunks for entry in chunk['entries']
               if entry['key'] == data.RECORD_KEY]
    if len(matches) != 1:
        raise ValueError('商品表记录不唯一')
    chunk, entry = matches[0]
    if chunk['digest'] != CHUNK_MD5 or chunk['locale']:
        raise ValueError('现有时装商品分块来源变化')
    source = (directory / (CHUNK_MD5 + '.bytes')).read_bytes()
    if len(source) != chunk['size'] or hashlib.md5(source).hexdigest() != CHUNK_MD5:
        raise ValueError('现有商品分块校验失败')
    original = source[entry['offset']:entry['offset'] + entry['length']]
    if data.sha(original) != RECORD_SHA or len(data.decode_table(original)) != 903:
        raise ValueError('已安装商品表不是已核对版本')
    rows = {row['GoodsID']: row for row, _ in data.decode_table(original)}
    for goods, item, currency, price, _ in data.ENTRIES:
        row = rows.get(goods, {})
        if (row.get('ShopID'), row.get('ItemID'), row.get('CurrencyList'), row.get('CurrencyCostList')) != (
                107, item, [currency], [price]):
            raise ValueError('既有角色时装商品变化')
    updated = data.append_fashion_rows(original)
    index = bytearray(original_index)
    filename, packed = data.lua.changed_chunk(index, chunk, entry, source, updated)
    target = directory / filename
    if target.exists() and target.read_bytes() != packed:
        raise ValueError('目标分块已存在不同内容')
    return {'client': client, 'indexPath': index_path, 'oldIndex': original_index,
            'index': bytes(index), 'targetPath': target, 'chunk': packed,
            'recordSHA256': data.sha(updated)}


def prepare(client, output, manifest=ROOT / '.local/client/patch-manifest.json'):
    if output.resolve().is_relative_to(client.resolve()):
        raise ValueError('准备目录不得位于客户端内')
    plan = make_plan(client, manifest, launcher())
    output.mkdir(parents=True, exist_ok=False)
    (output / plan['targetPath'].name).write_bytes(plan['chunk'])
    (output / INDEX_NAME).write_bytes(plan['index'])
    report = {'client': str(plan['client']), 'oldIndexSHA256': INDEX_SHA, 'indexName': INDEX_NAME,
              'newIndexSHA256': data.sha(plan['index']), 'chunkName': plan['targetPath'].name,
              'chunkSHA256': data.sha(plan['chunk']), 'recordSHA256': plan['recordSHA256'],
              'clientModified': False}
    (output / 'prepared.json').write_text(json.dumps(report, indent=2) + '\n', encoding='utf-8')
    return report


def install(prepared, backup, manifest=ROOT / '.local/client/patch-manifest.json'):
    launch = launcher()
    launch.require_stopped()
    report = json.loads((prepared / 'prepared.json').read_text(encoding='utf-8'))
    client = Path(report['client']).resolve()
    validate_client(client, manifest, launch)
    if (report.get('oldIndexSHA256') != INDEX_SHA or report.get('indexName') != INDEX_NAME
            or not re.fullmatch(r'[0-9a-f]{32}\.bytes', report.get('chunkName', ''))):
        raise ValueError('待安装清单路径或来源不符')
    directory = (client / data.CLIENT_DIR).resolve()
    if not directory.is_relative_to(client) or backup.resolve().is_relative_to(client):
        raise ValueError('安装或备份路径不符')
    index_path, target = directory / INDEX_NAME, directory / report['chunkName']
    new_index = (prepared / INDEX_NAME).read_bytes()
    new_chunk = (prepared / report['chunkName']).read_bytes()
    if (data.sha(new_index) != report.get('newIndexSHA256')
            or data.sha(new_chunk) != report.get('chunkSHA256')
            or hashlib.md5(new_chunk).hexdigest() + '.bytes' != target.name):
        raise ValueError('待安装内容摘要不符')
    if data.sha(index_path.read_bytes()) == report['newIndexSHA256']:
        if not target.is_file() or target.read_bytes() != new_chunk:
            raise ValueError('已安装的索引和分块不一致')
        return dict(report, clientModified=False, alreadyInstalled=True)
    plan = make_plan(client, manifest, launch)
    if (new_index != plan['index'] or new_chunk != plan['chunk'] or target != plan['targetPath']
            or report.get('recordSHA256') != plan['recordSHA256']):
        raise ValueError('待安装内容与重新核对结果不符')
    backup.mkdir(parents=True, exist_ok=False)
    (backup / INDEX_NAME).write_bytes(plan['oldIndex'])
    (backup / 'manifest.json').write_text(json.dumps(report, indent=2) + '\n', encoding='utf-8')
    if not target.exists():
        with target.open('xb') as stream:
            stream.write(new_chunk)
            stream.flush()
            os.fsync(stream.fileno())
    launch.require_stopped()
    validate_client(client, manifest, launch)
    if target.read_bytes() != new_chunk:
        raise ValueError('安装期间分块已变化')
    data.atomic_replace(index_path, new_index, INDEX_SHA)
    try:
        if data.sha(index_path.read_bytes()) != report['newIndexSHA256'] or target.read_bytes() != new_chunk:
            raise RuntimeError('安装后的索引或分块校验失败')
    except Exception:
        if data.sha(index_path.read_bytes()) == report['newIndexSHA256']:
            data.atomic_replace(index_path, plan['oldIndex'], report['newIndexSHA256'])
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
        result = prepare(client, args.prepare_dir, args.manifest)
    else:
        result = json.loads((args.prepare_dir / 'prepared.json').read_text(encoding='utf-8'))
    if args.apply:
        if args.backup_dir is None:
            raise ValueError('安装必须明确备份目录')
        result = install(args.prepare_dir, args.backup_dir, args.manifest)
    print(json.dumps(result, ensure_ascii=True))


if __name__ == '__main__':
    main()
