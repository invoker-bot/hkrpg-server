"""准备 4.6 时装橱窗无赠送头像的界面兼容；安装前须正常退出游戏。"""
import argparse
import importlib.util
import json
from pathlib import Path
import struct

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('skin_shop_lua', ROOT / 'scripts/client_lua_map_compat.py')
lua = importlib.util.module_from_spec(spec)
spec.loader.exec_module(lua)

PAGE = 'Ui/Shop/AvatarSkin/AvatarSkinShopDetailPanel'
PAGE_SHA = 'e964f8f48a282c5edefd7fe8a5b270daa1dfe342b87c4befb35eb270453b79b8'
INDEX_SHA = 'bd9839fcd86cd4aa97742259c67c064505907fbf024b4997faee1e559935688b'
COMPANION_SHA = '514e94cbd8db85fcc3456761247873b71f0570135c789989703321977032aaef'


def guard(constants):
    """Use existing 0..8 registers; hide all three portrait widgets when there is no gift."""
    field_btn, field_object, set_active = constants
    get = lambda dst, src: 15 | (dst << 8) | (src << 16)
    code = [get(2, 0), 9, get(2, 2), 27, 4 | (3 << 8),
            3 | (8 << 8) | (1 << 16), 30 | (2 << 8) | (2 << 16), 3,
            3 | (8 << 8)]
    for field in (28, 30, field_btn):
        code += [get(4, 0), 11, get(4, 4), field, get(4, 4), field_object,
                 6 | (6 << 8) | (8 << 16), 20 | (4 << 8) | (4 << 16), set_active,
                 21 | (4 << 8) | (3 << 16) | (1 << 24)]
    # The verified original portrait block has 35 instruction words.
    code += [26 | (8 << 8) | (35 << 16)]
    return code


def patch_record(raw):
    lua.require(lua.sha(raw) == PAGE_SHA, '时装橱窗详情页不是已核对原件')
    decoded = lua.Bytecode(raw)
    matches = [p for p in decoded.protos if p['name'] == 'setup_view']
    lua.require(len(matches) == 1, '时装详情函数不唯一')
    p = matches[0]
    lua.require(p['header'] == bytes([9, 2, 0, 0, 0]) and p['line'] == 14
                and len(p['code']) == 109 and len(p['constants']) == 35
                and len(p['lines']) == 109 and p['debug']
                and p['constants'][9] == '_avatar_skin_data'
                and p['constants'][11] == '_binder' and p['constants'][27] == 'PlayerCardID'
                and p['constants'][28] == 'avatar_head_name_txt'
                and p['constants'][30] == 'avatar_head_img'
                and p['constants'][32] == '_async_load_sprite_to'
                and p['constants'][33] == '_load_skin_bg_obj', '详情函数结构变化')
    strings = list(decoded.strings)
    added = bytearray()
    for value in ('avatar_head_btn', 'gameObject', 'SetActive'):
        if value not in strings:
            strings.append(value)
        added += b'\x03' + lua.var(strings.index(value) + 1)
    extra = guard((35, 36, 37))
    insertion = 67
    code = p['code'][:insertion] + extra + p['code'][insertion:]
    body = raw[p['start']:p['code_count']] + lua.var(len(code))
    body += struct.pack('<' + 'I' * len(code), *code)
    body += lua.var(38) + raw[p['constants_start']:p['constants_end']] + added
    body += raw[p['constants_end']:p['line_start']]
    lines = p['lines'][:insertion] + [p['lines'][insertion]] * len(extra) + p['lines'][insertion:]
    previous = 0
    body += b'\x01\x00' + bytes(len(code))
    for line in lines:
        body += struct.pack('<i', line - previous)
        previous = line
    body += b'\x01' + lua.var(len(p['locals']))
    for name, start, end, register in p['locals']:
        body += lua.var(name) + lua.var(start + len(extra) if start >= insertion else start)
        body += lua.var(end + len(extra) if end >= insertion else end) + bytes([register])
    body += lua.var(len(p['upnames'])) + b''.join(lua.var(name) for name in p['upnames'])
    table = b''.join(lua.var(len(text.encode('utf-8'))) + text.encode('utf-8') for text in strings)
    result = raw[:2] + lua.var(len(strings)) + table + raw[decoded.body:p['start']] + body + raw[p['end']:]
    checked = lua.Bytecode(result)
    lua.require(len(checked.protos) == len(decoded.protos) and checked.main == decoded.main, '原型图变化')
    new = next(p for p in checked.protos if p['name'] == 'setup_view')
    lua.require(new['code'][:insertion] == p['code'][:insertion]
                and new['code'][insertion:insertion + len(extra)] == extra
                and new['code'][insertion + len(extra):] == p['code'][insertion:], '原代码发生变化')
    return result


def build_files(client):
    return lua.build_profile_files(client, index_sha=INDEX_SHA, page=PAGE,
                                   companion=lua.PAGE, companion_sha=COMPANION_SHA, patcher=patch_record)


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
