"""准备 4.6 时装橱窗界面兼容；安装前须正常退出游戏。"""
import argparse
import hashlib
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
INSTALLED_INDEX_SHA = '67172adea9072fa565a8573558d8ad809358ff58863b7d8668e20d4c9975aaa2'
INSTALLED_PAGE_SHA = 'f1b656111ed27eb610012842736e681467a47aecb064c91a8f57affe5ed2346d'
FIRST_PREVIEW_INDEX_SHA = '4a9064ffe77645be9718408737eec3295bcf77d6d85610e91604f0127f08458a'
FIRST_PREVIEW_PAGE_SHA = 'c2c7c115e46d9a6b782c510802dc1e141ac7dce9da0406092c5f1e8a663b8e15'
INSTALLED_INDEX_NAME = 'LuaV_c6ba410adafd0d22ad63cc8c503021d0.bytes'
STELLAR_SKIN = 1141501
STELLAR_FIGURE = 'SpriteOutput/StellarCustomizationMaxFigure/1141501.png'


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
    return patch_background_record(result)


def patch_background_record(raw):
    """Add a reusable image only for the special skin; keep the native prefab path intact."""
    lua.require(lua.sha(raw) == INSTALLED_PAGE_SHA, '时装详情页不是已核对的无头像补丁')
    decoded = lua.Bytecode(raw)
    matches = [p for p in decoded.protos if p['name'] == '_load_skin_bg_obj']
    lua.require(len(matches) == 1, '时装背景函数不唯一')
    p = matches[0]
    lua.require(p['header'] == bytes([7, 1, 0, 0, 0]) and p['line'] == 32
                and len(p['code']) == 26 and len(p['lines']) == 26 and p['debug']
                and p['constants'] == ['_avatar_skin_data', 'ShopBgPath', '_bg_panel_game_object',
                    'destroy_object', 'G', 'StrExt', 'IsNullOrEmpty', {'import': 3225424902},
                    '_binder', 'node_bg', '_sync_load_prefab'], '时装背景函数结构变化')
    strings = list(decoded.strings)
    extra, values = bytearray(), list(p['constants'])

    def string(value):
        if value in values:
            return values.index(value)
        if value not in strings:
            strings.append(value)
        index = len(values)
        values.append(value)
        extra.extend(b'\x03' + lua.var(strings.index(value) + 1))
        return index

    def number(value):
        index = len(values)
        values.append(float(value))
        extra.extend(b'\x02' + struct.pack('<d', value))
        return index

    def imported(*parts):
        indices = [string(part) for part in parts]
        lua.require(1 <= len(indices) <= 3 and all(i < 1024 for i in indices), '导入常量越界')
        encoded = len(indices) << 30
        for index, shift in zip(indices, (20, 10, 0)):
            encoded |= index << shift
        index = len(values)
        values.append({'import': encoded})
        extra.extend(b'\x04' + struct.pack('<I', encoded))
        return index, encoded

    code, labels, pending = [], {}, []

    def emit(*words): code.extend(words)
    def get(dst, src, name): emit(15 | dst << 8 | src << 16, string(name))
    def put(src, dst, name): emit(16 | src << 8 | dst << 16, string(name))
    def method(dst, src, name): emit(20 | dst << 8 | src << 16, string(name))
    def call(dst, nargs, nresults=0): emit(21 | dst << 8 | (nargs + 1) << 16 | (nresults + 1) << 24)
    def load_bool(dst, value): emit(3 | dst << 8 | int(value) << 16)
    def load_const(dst, index): emit(5 | dst << 8 | index << 16)
    def load_import(dst, *parts):
        index, encoded = imported(*parts)
        emit(12 | dst << 8 | index << 16, encoded)
    def label(name): labels[name] = len(code)
    def jump(op, reg, target, aux=None):
        pending.append((len(code), target))
        emit(op | reg << 8)
        if aux is not None: emit(aux)

    # Find the image under the current hierarchy, rather than caching a Unity
    # object that can become stale when this panel is closed and re-created.
    get(3, 0, '_binder'); get(3, 3, 'node_bg')
    method(1, 3, 'Find'); load_const(3, string('StellarSkinShopPreview')); call(1, 2, 1)
    jump(26, 1, 'hidden')
    get(1, 1, 'gameObject')
    method(2, 1, 'SetActive'); load_bool(4, False); call(2, 2)
    label('hidden')
    get(2, 0, '_skin_id'); load_const(3, number(STELLAR_SKIN))
    jump(30, 2, 'original', 3)
    jump(26, 1, 'create')
    method(2, 1, 'SetActive'); load_bool(4, True); call(2, 2)
    jump(23, 0, 'original')
    label('create')
    load_import(1, 'CS', 'UnityEngine', 'GameObject')
    load_const(2, string('StellarSkinShopPreview')); call(1, 1, 1)
    load_import(4, 'CS', 'UnityEngine', 'RectTransform')
    load_import(3, 'typeof'); call(3, 1, 1)
    emit(6 | 4 << 8 | 3 << 16)
    method(2, 1, 'AddComponent'); call(2, 2)
    get(2, 1, 'transform')
    get(5, 0, '_binder'); get(5, 5, 'node_bg')
    load_bool(6, False); method(3, 2, 'SetParent'); call(3, 3)
    load_import(3, 'CS', 'UnityEngine', 'Vector2')
    get(4, 3, 'zero'); put(4, 2, 'anchorMin')
    get(4, 3, 'one'); put(4, 2, 'anchorMax')
    get(4, 3, 'zero'); put(4, 2, 'offsetMin'); put(4, 2, 'offsetMax')
    load_import(5, 'CS', 'UnityEngine', 'UI'); get(4, 5, 'Image')
    load_import(3, 'typeof'); call(3, 1, 1)
    emit(6 | 4 << 8 | 3 << 16)
    method(2, 1, 'AddComponent'); call(2, 2, 1)
    load_bool(3, True); put(3, 2, 'preserveAspect')
    load_bool(3, False); put(3, 2, 'raycastTarget')
    method(3, 0, '_async_load_sprite_to')
    emit(6 | 5 << 8 | 2 << 16)
    load_const(6, string(STELLAR_FIGURE)); call(3, 3)
    label('original')
    for pc, target in pending:
        displacement = labels[target] - pc - 1
        lua.require(-32768 <= displacement < 32768, '背景跳转越界')
        code[pc] |= (displacement & 0xffff) << 16

    prefix = code
    code = prefix + p['code']
    body = raw[p['start']:p['code_count']] + lua.var(len(code))
    body += struct.pack('<' + 'I' * len(code), *code)
    body += lua.var(len(values)) + raw[p['constants_start']:p['constants_end']] + extra
    body += raw[p['constants_end']:p['line_start']]
    lines = [p['line']] * len(prefix) + p['lines']
    previous = 0
    body += b'\x01\x00' + bytes(len(code))
    for line in lines:
        body += struct.pack('<i', line - previous)
        previous = line
    body += b'\x01' + lua.var(len(p['locals']))
    for name, start, end, register in p['locals']:
        body += lua.var(name) + lua.var(start + len(prefix) if start else 0)
        body += lua.var(end + len(prefix)) + bytes([register])
    body += lua.var(len(p['upnames'])) + b''.join(lua.var(name) for name in p['upnames'])
    table = b''.join(lua.var(len(s.encode('utf-8'))) + s.encode('utf-8') for s in strings)
    result = raw[:2] + lua.var(len(strings)) + table + raw[decoded.body:p['start']] + body + raw[p['end']:]
    checked = lua.Bytecode(result)
    lua.require(len(checked.protos) == len(decoded.protos) and checked.main == decoded.main, '背景补丁改变原型图')
    changed = next(q for q in checked.protos if q['name'] == '_load_skin_bg_obj')
    lua.require(changed['code'][:len(prefix)] == prefix and changed['code'][len(prefix):] == p['code']
                and changed['constants'] == values, '背景补丁复核失败')
    return result


def installed_baseline_page(client):
    """Read the exact predecessor retained by the first installation."""
    directory = client / lua.LUA
    index = (directory / INSTALLED_INDEX_NAME).read_bytes()
    lua.require(lua.sha(index) == INSTALLED_INDEX_SHA
                and hashlib.md5(index).hexdigest() == INSTALLED_INDEX_NAME[5:-6], '既有时装索引不符')
    chunks = lua.read_index(index)
    first = chunks[0]
    payload = (directory / (first['digest'] + '.bytes')).read_bytes()
    lua.require(len(payload) == first['size'] and hashlib.md5(payload).hexdigest() == first['digest'], '既有模块目录不符')
    entry = first['entries'][0]
    rows = [json.loads(line) for line in payload[entry['offset']:entry['offset'] + entry['length']].splitlines()]
    wanted = [row['Md5'] for row in rows if row['Path'] == PAGE]
    lua.require(len(wanted) == 1, '既有时装页目录不唯一')
    matches = []
    for chunk in chunks:
        payload = (directory / chunk['locale'] / (chunk['digest'] + '.bytes')).read_bytes()
        lua.require(len(payload) == chunk['size'] and hashlib.md5(payload).hexdigest() == chunk['digest'], '既有时装分块不符')
        for entry in chunk['entries']:
            raw = payload[entry['offset']:entry['offset'] + entry['length']]
            if hashlib.md5(raw).hexdigest() == wanted[0]: matches.append(raw)
    lua.require(len(matches) == 1 and lua.sha(matches[0]) == INSTALLED_PAGE_SHA, '既有时装页内容不符')
    return matches[0]


def build_files(client):
    directory = client / lua.LUA
    digest = lua.pointer_digest((directory / 'M_LuaV.bytes').read_bytes())
    index_sha = lua.sha((directory / ('LuaV_' + digest + '.bytes')).read_bytes())
    patcher = {INDEX_SHA: patch_record, INSTALLED_INDEX_SHA: patch_background_record}.get(index_sha)
    if index_sha == FIRST_PREVIEW_INDEX_SHA:
        baseline = installed_baseline_page(client)
        def patcher(raw):
            lua.require(lua.sha(raw) == FIRST_PREVIEW_PAGE_SHA, '第一次预览补丁页面不符')
            return patch_background_record(baseline)
    lua.require(patcher is not None, '客户端 Lua 索引不是已核对的时装补丁来源')
    return lua.build_profile_files(client, index_sha=index_sha, page=PAGE,
                                   companion=lua.PAGE, companion_sha=COMPANION_SHA, patcher=patcher)


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
