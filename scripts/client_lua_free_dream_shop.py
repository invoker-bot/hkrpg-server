"""固定客户端中将六个本地零价商品送往当前游戏会话，其他商品不改。"""
import argparse
import importlib.util
import json
import struct
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('free_dream_lua_base', ROOT / 'scripts/client_lua_map_compat.py')
lua = importlib.util.module_from_spec(spec)
spec.loader.exec_module(lua)

INDEX_SHA = '870ee620bbb47c60963b25ba5761be1cc55c5c46cb1a1b607755f8dbb5f8b67e'
PAGE_SHA = 'cdbfbdf1d076b98fd9099e78937d02473bbc77a40295e246cc5f0ef127085880'
PAGE = 'Ui/Shop/Product/ProductItemPanel'
COMPANION = 'Ui/Shop/AvatarSkin/AvatarSkinShopDetailPanel'
COMPANION_SHA = '230a886d1ac66b7b8220b4cdf68d03e08ffae03815e91353e26fae901f9e4bd8'
PRODUCTS = ('rpgchncoin60tier1', 'rpgchncoin300tier5', 'rpgchncoin980tier15',
            'rpgchncoin1980tier30', 'rpgchncoin3280tier50', 'rpgchncoin6480tier60')


def patch_record(raw):
    lua.require(lua.sha(raw) == PAGE_SHA, '充值商品页不是已核对原件')
    decoded = lua.Bytecode(raw)
    matches = [p for p in decoded.protos if p['name'] == '_on_btn_buy']
    lua.require(len(matches) == 1, '购买按钮原型不唯一')
    p, = matches
    lua.require(p['header'] == bytes([5, 1, 1, 0, 0]) and p['line'] == 87
                and len(p['code']) == len(p['lines']) == 66 and p['debug']
                and p['constants'][:5] == ['_report_item_click', 'G', 'ShopUtils',
                                           'is_forbid_recharge', {'import': 3222276099}]
                and p['code'][:7] == [0xd3000114, 0, 0x01020115, 0x0004010c,
                                      0xc0100803, 0x02010115, 0x0009011a],
                '购买按钮结构变化')
    strings, values, extra = list(decoded.strings), list(p['constants']), bytearray()

    def string(value):
        if value in values:
            return values.index(value)
        if value not in strings:
            strings.append(value)
        index = len(values)
        values.append(value)
        extra.extend(b'\x03' + lua.var(strings.index(value) + 1))
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

    def jump(op, register, label, aux=None):
        pending.append((len(code), label))
        code.append(op | register << 8)
        if aux is not None:
            code.append(aux)

    def get(dst, src, name): code.extend([15 | dst << 8 | src << 16, string(name)])
    def method(dst, src, name): code.extend([20 | dst << 8 | src << 16, string(name)])
    def call(dst, nargs, results=0): code.append(21 | dst << 8 | (nargs + 1) << 16 | (results + 1) << 24)
    def load_import(dst, *parts):
        index, encoded = imported(*parts)
        code.extend([12 | dst << 8 | index << 16, encoded])

    # The native SDK payment path rejects zero price and checks the recharge
    # ban a second time. Send only these six IDs over the authenticated game
    # session, using the native recharge-confirmation sender and no SDK pay call.
    get(1, 0, '_product')
    jump(26, 1, 'original')
    get(1, 1, 'ProductID')
    for product in PRODUCTS:
        code.append(5 | 2 << 8 | string(product) << 16)
        jump(27, 1, 'local_free', 2)
    jump(23, 0, 'original')
    labels['local_free'] = len(code)
    # Construct List<String> exactly as the client's own list call sites do.
    load_import(4, 'CS', 'System', 'Collections')
    get(3, 4, 'Generic')
    get(2, 3, 'List')
    load_import(3, 'CS', 'System', 'String')
    call(2, 1, 1)
    call(2, 0, 1)
    method(3, 2, 'Add')
    code.append(6 | 5 << 8 | 1 << 16)  # MOVE R5, R1
    call(3, 2)
    code.append(6 | 5 << 8 | 2 << 16)  # Keep list while looking up the network manager.
    load_import(4, 'CS', 'RPG', 'Client')
    get(3, 4, 'GlobalVars')
    get(2, 3, 's_NetworkManager')
    method(2, 2, 'GCBGBBMFIJN')
    code.append(6 | 4 << 8 | 5 << 16)
    call(2, 2)
    code.append(22 | 1 << 16)  # RETURN, never enter the native SDK payment path.
    labels['original'] = len(code)
    for pc, target in pending:
        displacement = labels[target] - pc - 1
        lua.require(-32768 <= displacement < 32768, '商品保护跳转越界')
        code[pc] |= (displacement & 0xffff) << 16

    insertion = code
    code = p['code'][:3] + insertion + p['code'][3:]
    body = bytes([6]) + raw[p['start'] + 1:p['code_count']] + lua.var(len(code))
    body += struct.pack('<' + 'I' * len(code), *code)
    body += lua.var(len(values)) + raw[p['constants_start']:p['constants_end']] + extra
    body += raw[p['constants_end']:p['line_start']]
    lines = p['lines'][:3] + [p['lines'][3]] * len(insertion) + p['lines'][3:]
    body += b'\x01\x00' + bytes(len(code))
    previous = 0
    for line in lines:
        body += struct.pack('<i', line - previous)
        previous = line
    body += b'\x01' + lua.var(len(p['locals']))
    for name, start, end, register in p['locals']:
        body += lua.var(name) + lua.var(start + len(insertion) if start >= 3 else start)
        body += lua.var(end + len(insertion) if end >= 3 else end) + bytes([register])
    body += lua.var(len(p['upnames'])) + b''.join(lua.var(name) for name in p['upnames'])
    table = b''.join(lua.var(len(s.encode('utf-8'))) + s.encode('utf-8') for s in strings)
    result = raw[:2] + lua.var(len(strings)) + table + raw[decoded.body:p['start']] + body + raw[p['end']:]
    checked = lua.Bytecode(result)
    lua.require(len(checked.protos) == len(decoded.protos) and checked.main == decoded.main, '修复改变原型图')
    changed, = [q for q in checked.protos if q['name'] == '_on_btn_buy']
    lua.require(changed['code'] == code and changed['constants'] == values, '商品保护补丁复核失败')
    return result


def build_files(client):
    return lua.build_profile_files(client, index_sha=INDEX_SHA, page=PAGE, companion=COMPANION,
                                   companion_sha=COMPANION_SHA, patcher=patch_record)


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
