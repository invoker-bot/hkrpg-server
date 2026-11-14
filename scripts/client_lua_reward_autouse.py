"""为固定 4.6 客户端准备奖励窗口自动使用检查兼容；安装前须正常退出游戏。"""
import argparse
import hashlib
import json
from pathlib import Path
import struct

import client_lua_map_compat as lua

ROOT = Path(__file__).resolve().parents[1]
INDEX_NAME = 'LuaV_7ad73a4baf4869d6a3313536bb94a82e.bytes'
INDEX_SHA = '41bf7aa40f0305600107d34ee163cfc3ff0d71228af9c71bf80fa85e84870660'
UTILITY_KEY = '6a5e279eba054378'
UTILITY_SHA = 'c047571bdeed630b3bb7b89deadcbba89ff371b2144419c7791f29d16c87af9e'
PAGES = {
    'Ui/Common/RewardDialog': ('70c2edb38055cb66', 'a6c6f4c014ede5e3d1f68eb73dbda061c38e47a19c0045dc934bfddfbae7785a', 449),
    'Ui/Shop/RechargeRewardDialog': ('3e68e257ed8d3e85', '486615ef5472162f5d391972e5e9d6b5dc6b91d284415b1201f67e5b92ef9f23', 192),
    'Ui/ActivityMonopoly/MiniGamePage/ActivityMonopolyMiniGameRewardDialog': ('a503a7280b0f9d3f', '3e70cd79683b1d4f7877315c4a5f4793f6bc73b093bede61804ed6d549402d6c', 210),
}


def patch_record(raw, page):
    lua.require(page in PAGES and lua.sha(raw) == PAGES[page][1], '奖励页面不是已核对原件')
    decoded = lua.Bytecode(raw)
    matches = [p for p in decoded.protos if p['name'] == '_trigger_autouse_item']
    lua.require(len(matches) == 1, '自动使用函数不唯一')
    p = matches[0]
    constants = p['constants']
    lua.require(p['header'] == bytes([12, 1, 1, 0, 0])
                and p['line'] == PAGES[page][2] and len(p['code']) == 52
                and len(p['lines']) == 52 and p['debug']
                and constants[:2] == ['pairs', {'import': 1073741824}]
                and constants[3:] == [
                    'ConfigID', 'GetItemDataByConfigID', 'IsAutoUse', 'G', 'InventoryUtils',
                    'check_item_ban', {'import': 3227524104}, 'UseMethod', 'CS', 'RPG',
                    'GameCore', {'import': 3232772109}, 'ItemUseMethod', 'PlayerSelectedReward',
                    'ItemUseDataExcelTable', 'GetData', 'UseDataID', 'UIManager', 'load_and_show',
                    {'import': 3227537429}, 'Ui.Inventory.ItemUse.OptionalGiftPackDetailDialog']
                and p['code'][16] == 0x0009070c and p['code'][17] == 0xc0601c08
                and p['code'][18] == 0x00060806 and p['code'][19] == 0x02020715
                and p['code'][20] == 0x001c071a, '自动使用原始结构变化')

    strings = list(decoded.strings)
    if 'is_item_banned' not in strings:
        strings.append('is_item_banned')

    def encode(values, table):
        result = bytearray()
        for value in values:
            if isinstance(value, str):
                result += b'\x03' + lua.var(table.index(value) + 1)
            elif isinstance(value, dict) and set(value) == {'import'}:
                result += b'\x04' + struct.pack('<I', value['import'])
            else:
                raise ValueError('未知奖励页常量类型')
        return bytes(result)

    lua.require(encode(constants, decoded.strings) == raw[p['constants_start']:p['constants_end']],
                '奖励页常量编码变化')
    changed_constants = list(constants)
    changed_constants[8] = 'is_item_banned'
    code = list(p['code'])
    # 原分支要求“允许时继续”；当前函数返回“被禁用”，故同时反转跳转。
    code[20] = (code[20] & ~255) | 25
    body = raw[p['start']:p['code_count']] + lua.var(len(code))
    body += struct.pack('<' + 'I' * len(code), *code)
    body += lua.var(len(changed_constants)) + encode(changed_constants, strings)
    body += raw[p['constants_end']:p['end']]
    table = b''.join(lua.var(len(s.encode('utf-8'))) + s.encode('utf-8') for s in strings)
    result = raw[:2] + lua.var(len(strings)) + table + raw[decoded.body:p['start']] + body + raw[p['end']:]
    checked = lua.Bytecode(result)
    changed = next(x for x in checked.protos if x['name'] == '_trigger_autouse_item')
    lua.require(checked.main == decoded.main and len(checked.protos) == len(decoded.protos)
                and changed['constants'] == changed_constants and changed['code'] == code
                and changed['lines'] == p['lines'], '奖励页面回读不符')
    return result


def build_files(client):
    directory = client / lua.LUA
    pointer = (directory / 'M_LuaV.bytes').read_bytes()
    digest = lua.pointer_digest(pointer)
    lua.require('LuaV_' + digest + '.bytes' == INDEX_NAME, '客户端 Lua 入口不是已核对来源')
    original_index = (directory / INDEX_NAME).read_bytes()
    lua.require(lua.sha(original_index) == INDEX_SHA and hashlib.md5(original_index).hexdigest() == digest,
                '客户端 Lua 索引已变化')
    index = bytearray(original_index)
    chunks = lua.read_index(original_index)
    by_digest, records = {}, {}
    keys = {value[0] for value in PAGES.values()} | {UTILITY_KEY, '23248f34a56ef961'}
    for chunk in chunks:
        data = (directory / chunk['locale'] / (chunk['digest'] + '.bytes')).read_bytes()
        lua.require(len(data) == chunk['size'] and hashlib.md5(data).hexdigest() == chunk['digest'],
                    'Lua 分块目录与内容不符')
        by_digest[chunk['digest']] = data
        for entry in chunk['entries']:
            if entry['key'] in keys:
                lua.require(entry['key'] not in records, '目标记录不唯一')
                records[entry['key']] = data[entry['offset']:entry['offset'] + entry['length']]
    lua.require(set(records) == keys and lua.sha(records[UTILITY_KEY]) == UTILITY_SHA
                and lua.sha(records['23248f34a56ef961']) == '514e94cbd8db85fcc3456761247873b71f0570135c789989703321977032aaef',
                '禁用检查或既有地图补丁不是已核对原件')
    utility = lua.Bytecode(records[UTILITY_KEY])
    lua.require('is_item_banned' in utility.strings and 'check_item_ban' not in utility.strings,
                '客户端物品禁用检查已变化')

    first = chunks[0]
    manifest_entry = first['entries'][0]
    manifest_raw = by_digest[first['digest']][manifest_entry['offset']:
                                                manifest_entry['offset'] + manifest_entry['length']]
    rows = [json.loads(line) for line in manifest_raw.splitlines()]
    page_rows = {row['Path']: row for row in rows if row['Path'] in PAGES}
    lua.require(len(page_rows) == len(PAGES)
                and sum(row['Path'] in PAGES for row in rows) == len(PAGES), '奖励页目录缺失或重复')
    replacements = {}
    page_hashes = {}
    for page, (key, sha, _) in PAGES.items():
        raw = records[key]
        lua.require(lua.sha(raw) == sha and page_rows[page]['Md5'] == hashlib.md5(raw).hexdigest(),
                    '奖励页目录与原件不符')
        patched = patch_record(raw, page)
        replacements[key] = patched
        old_md5, new_md5 = hashlib.md5(raw).hexdigest(), hashlib.md5(patched).hexdigest()
        lua.require(manifest_raw.count(old_md5.encode()) == 1, '模块摘要引用不唯一')
        manifest_raw = manifest_raw.replace(old_md5.encode(), new_md5.encode())
        page_hashes[page] = {'original': sha, 'patched': lua.sha(patched)}

    # 三处现位于同一个分块；每次从更新后的索引重新取得偏移，避免错位。
    changed_chunks = {}
    for key, patched in replacements.items():
        current = lua.read_index(index)
        matches = [(chunk, entry) for chunk in current for entry in chunk['entries'] if entry['key'] == key]
        lua.require(len(matches) == 1 and matches[0][0]['meta'] != current[0]['meta'], '奖励页分块不符')
        chunk, entry = matches[0]
        filename, packed = lua.changed_chunk(index, chunk, entry, by_digest[chunk['digest']], patched)
        by_digest[filename[:-6]] = packed
        changed_chunks[chunk['meta']] = (filename, packed)

    current = lua.read_index(index)
    first = current[0]
    filename, packed = lua.changed_chunk(index, first, first['entries'][0], by_digest[first['digest']], manifest_raw)
    changed_chunks[first['meta']] = (filename, packed)
    new_digest = hashlib.md5(index).hexdigest()
    updated_pointer = bytearray(pointer)
    updated_pointer[28:44] = b''.join(bytes.fromhex(new_digest)[n:n + 4][::-1] for n in range(0, 16, 4))
    lua.require(lua.pointer_digest(updated_pointer) == new_digest and len(index) == len(original_index),
                'Lua 入口摘要不符')
    files = {filename: data for filename, data in changed_chunks.values()}
    files['LuaV_' + new_digest + '.bytes'] = bytes(index)
    files['M_LuaV.bytes'] = bytes(updated_pointer)
    report = dict(passed=True, clientRoot=str(client.resolve()), originalPointerSHA256=lua.sha(pointer),
                  originalIndexSHA256=lua.sha(original_index), pages=page_hashes,
                  files={lua.LUA + name: lua.sha(data) for name, data in files.items()},
                  clientModified=False, clientExecuted=False)
    return report, files, pointer


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--manifest', type=Path, default=ROOT / '.local/client/patch-manifest.json')
    parser.add_argument('--prepare-dir', type=Path, required=True)
    parser.add_argument('--apply', action='store_true')
    parser.add_argument('--backup-dir', type=Path)
    args = parser.parse_args()
    if not (args.prepare_dir / 'prepared.json').exists():
        client = Path(json.loads(args.manifest.read_text('utf-8-sig'))['clientRoot']).resolve()
        lua.require(not args.prepare_dir.resolve().is_relative_to(client), '准备目录不得位于客户端目录内')
        result = lua.prepare(client, args.prepare_dir, build=build_files)
    else:
        result = json.loads((args.prepare_dir / 'prepared.json').read_text('utf-8'))
    if args.apply:
        lua.require(args.backup_dir is not None, '安装必须明确备份目录')
        result = lua.install(args.prepare_dir, args.manifest, args.backup_dir, build=build_files)
    print(json.dumps(result, ensure_ascii=True))


if __name__ == '__main__':
    main()
