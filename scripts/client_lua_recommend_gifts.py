"""为固定国服客户端的四类推荐礼包接入本服领取，保留既有 Lua 修复。"""
import argparse
import hashlib
import importlib.util
import json
import struct
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('recommend_lua_base', ROOT / 'scripts/client_lua_map_compat.py')
lua = importlib.util.module_from_spec(spec)
spec.loader.exec_module(lua)
INDEX_SHA = 'c48daf8cf0ea70c1f91ee393489b52ae3da01f04073292e88ba9ec4fa411e4dd'
TARGETS = {
    'Ui/Shop/Recommend/DiscountedSingleGiftTabItem': ('rpgchnyear3tier1', 15, 138, '5d44f10873ff569029fe6b52557d485a1e713c99ce213f78cab0b8d10d790f2e'),
    'Ui/Shop/Recommend/DiscountedPackedGiftTabItem': ('rpgchnyear3tier5', 16, 157, 'a772cbf9d5dfbe7e6082776217731c9bb8c2d9632be3be0b7c9855fa306fc613'),
    'Ui/Shop/Recommend/DiscountedDozenPassesGiftTabItem': ('rpgchnticket3tier15', 22, 133, '8d0683c3bc454bc7e5fe12e0e6770825c85212ff3a9df7f9d4258e21a44955ef'),
    'Ui/Shop/Recommend/GiftExtraValueTicketOneTabItem': ('rpgchnyear4tiercny100', 25, 140, '8c11f10697b708b7e64f0d451450d88d63fd5a13d7543e200c6944872b073d46'),
}
COMPANIONS = {
    'Ui/Shop/Product/ProductItemPanel': '0a1beeb2f7b91838f96d2a5806b077c79e801037e3425a29d79de5c5bb83ba33',
    'Ui/Shop/AvatarSkin/AvatarSkinShopDetailPanel': '230a886d1ac66b7b8220b4cdf68d03e08ffae03815e91353e26fae901f9e4bd8',
}
ORIGINAL_CODE = [0xbb00020f,0,0xec02010f,1,0x309,0xa803020f,2,0x2011b,2,0x10016,
                 0x6040c,0xc0301005,0xac04030f,7,0xae03020f,8,0xcf02010f,9,0x10409,
                 0x9b04030f,10,0x7d010114,11,0x2030115,0x413,0x4a,0xb1010214,12,0x1030215,0x10016]


def patch_record(raw, profile):
    product, gift_type, line, expected_sha = profile
    lua.require(lua.sha(raw) == expected_sha, '礼包页不是已核对原件')
    decoded = lua.Bytecode(raw)
    matches = [p for p in decoded.protos if p['name'] == '_on_btn_buy_click']
    lua.require(len(matches) == 1, '购买按钮不唯一')
    p, = matches
    lua.require(p['header'] == bytes([5, 1, 2, 0, 0]) and p['line'] == line
                and p['code'] == ORIGINAL_CODE and p['debug'] and len(p['lines']) == len(p['code'])
                and p['constants'][:3] == ['_gift_data', 'Status', 'Selling'], '礼包按钮结构变化')
    strings, values, extra = list(decoded.strings), list(p['constants']), bytearray()
    def string(value):
        if value in values: return values.index(value)
        if value not in strings: strings.append(value)
        index = len(values); values.append(value)
        extra.extend(b'\x03' + lua.var(strings.index(value) + 1))
        return index
    def number(value):
        index = len(values); values.append(float(value))
        extra.extend(b'\x02' + struct.pack('<d', value))
        return index
    def imported(*parts):
        indices = [string(part) for part in parts]
        lua.require(1 <= len(indices) <= 3 and all(i < 1024 for i in indices), '导入常量越界')
        encoded = len(indices) << 30
        for index, shift in zip(indices, (20, 10, 0)): encoded |= index << shift
        index = len(values); values.append({'import': encoded})
        extra.extend(b'\x04' + struct.pack('<I', encoded))
        return index, encoded
    code, labels, pending = [], {}, []
    def jump(op, register, label, aux=None):
        pending.append((len(code), label)); code.append(op | register << 8)
        if aux is not None: code.append(aux)
    def get(dst, src, name): code.extend([15 | dst << 8 | src << 16, string(name)])
    def method(dst, src, name): code.extend([20 | dst << 8 | src << 16, string(name)])
    def call(dst, nargs, results=0): code.append(21 | dst << 8 | (nargs+1) << 16 | (results+1) << 24)
    def load_import(dst, *parts):
        index, encoded = imported(*parts); code.extend([12 | dst << 8 | index << 16, encoded])
    # 原始按钮先检查 Selling；仅对应的原始礼包类型走本服，不触发 SDK 支付。
    get(1, 0, '_gift_data'); get(1, 1, 'GiftType')
    code.append(5 | 2 << 8 | number(gift_type) << 16)
    jump(27, 1, 'local', 2); jump(23, 0, 'original')
    labels['local'] = len(code)
    code.append(5 | 1 << 8 | string(product) << 16)
    load_import(4, 'CS', 'System', 'Collections'); get(3, 4, 'Generic'); get(2, 3, 'List')
    load_import(3, 'CS', 'System', 'String'); call(2, 1, 1); call(2, 0, 1)
    method(3, 2, 'Add'); code.append(6 | 5 << 8 | 1 << 16); call(3, 2)
    code.append(6 | 5 << 8 | 2 << 16)
    load_import(4, 'CS', 'RPG', 'Client'); get(3, 4, 'GlobalVars'); get(2, 3, 's_NetworkManager')
    method(2, 2, 'GCBGBBMFIJN'); code.append(6 | 4 << 8 | 5 << 16); call(2, 2)
    # KCP preserves request order: refresh both product counts and gift status after the claim.
    load_import(4, 'CS', 'RPG', 'Client'); get(3, 4, 'GlobalVars'); get(2, 3, 's_ModuleManager')
    get(2, 2, 'RechargeShopModule'); method(2, 2, 'RequestData'); call(2, 1)
    code.append(22 | 1 << 16)
    labels['original'] = len(code)
    for pc, target in pending:
        displacement = labels[target] - pc - 1
        lua.require(-32768 <= displacement < 32768, '礼包保护跳转越界')
        code[pc] |= (displacement & 0xffff) << 16
    insertion = code
    code = p['code'][:10] + insertion + p['code'][10:]
    body = bytes([6]) + raw[p['start']+1:p['code_count']] + lua.var(len(code))
    body += struct.pack('<'+'I'*len(code), *code)
    body += lua.var(len(values)) + raw[p['constants_start']:p['constants_end']] + extra
    body += raw[p['constants_end']:p['line_start']]
    lines = p['lines'][:10] + [p['lines'][10]]*len(insertion) + p['lines'][10:]
    body += b'\x01\x00' + bytes(len(code))
    previous = 0
    for lineno in lines:
        body += struct.pack('<i', lineno-previous); previous = lineno
    body += b'\x01' + lua.var(len(p['locals']))
    for name, start, end, register in p['locals']:
        body += lua.var(name) + lua.var(start+len(insertion) if start>=10 else start)
        body += lua.var(end+len(insertion) if end>=10 else end) + bytes([register])
    body += lua.var(len(p['upnames'])) + b''.join(lua.var(name) for name in p['upnames'])
    table = b''.join(lua.var(len(s.encode('utf-8'))) + s.encode('utf-8') for s in strings)
    result = raw[:2] + lua.var(len(strings)) + table + raw[decoded.body:p['start']] + body + raw[p['end']:]
    check = lua.Bytecode(result)
    changed, = [q for q in check.protos if q['name'] == '_on_btn_buy_click']
    lua.require(check.main == decoded.main and len(check.protos) == len(decoded.protos)
                and changed['code'] == code and changed['constants'] == values, '礼包补丁复核失败')
    return result


def build_files(client):
    directory = client / lua.LUA
    pointer = (directory/'M_LuaV.bytes').read_bytes()
    digest = lua.pointer_digest(pointer)
    raw_index = (directory/('LuaV_'+digest+'.bytes')).read_bytes()
    lua.require(lua.sha(raw_index)==INDEX_SHA and hashlib.md5(raw_index).hexdigest()==digest, '客户端 Lua 索引已变化')
    chunks = lua.read_index(raw_index); first = chunks[0]
    manifest_chunk = (directory/(first['digest']+'.bytes')).read_bytes()
    record = first['entries'][0]
    manifest = manifest_chunk[record['offset']:record['offset']+record['length']]
    rows = [json.loads(line) for line in manifest.splitlines()]
    wanted_paths = set(TARGETS) | set(COMPANIONS)
    wanted = {row['Md5']:row['Path'] for row in rows if row['Path'] in wanted_paths}
    lua.require(len(wanted)==len(wanted_paths) and set(wanted.values())==wanted_paths, '礼包页面目录缺失或重复')
    found = {}
    for chunk in chunks:
        data = (directory/chunk['locale']/(chunk['digest']+'.bytes')).read_bytes()
        lua.require(len(data)==chunk['size'] and hashlib.md5(data).hexdigest()==chunk['digest'], 'Lua 分块不符')
        for entry in chunk['entries']:
            raw = data[entry['offset']:entry['offset']+entry['length']]
            key = hashlib.md5(raw).hexdigest()
            if key in wanted:
                path = wanted[key]
                lua.require(path not in found, '礼包页面重复')
                found[path] = (chunk,entry,data,raw)
    lua.require(set(found)==wanted_paths, '礼包或既有修复页面缺失')
    for path, sha in COMPANIONS.items(): lua.require(lua.sha(found[path][3])==sha, '既有商店修复页面不符')
    lua.require(len({found[path][0]['digest'] for path in TARGETS})==1, '礼包页面跨分块')
    chunk_id = found[next(iter(TARGETS))][0]['digest']
    lua.require(chunk_id!=first['digest'], '礼包覆盖目录分块')
    packed = found[next(iter(TARGETS))][2]
    index = bytearray(raw_index); new_manifest = manifest
    before, after = {}, {}
    for path, profile in TARGETS.items():
        original = found[path][3]; patched = patch_record(original,profile)
        old_md5,new_md5 = hashlib.md5(original).hexdigest(),hashlib.md5(patched).hexdigest()
        lua.require(new_manifest.count(old_md5.encode())==1, '模块摘要引用不唯一')
        new_manifest = new_manifest.replace(old_md5.encode(),new_md5.encode())
        updated_chunks = lua.read_index(index)
        chunk = next(c for c in updated_chunks if c['digest']==hashlib.md5(packed).hexdigest())
        entry = next(e for e in chunk['entries'] if e['key']==found[path][1]['key'])
        filename, packed = lua.changed_chunk(index,chunk,entry,packed,patched)
        before[path],after[path] = lua.sha(original),lua.sha(patched)
    newfirst=lua.read_index(index)[0]
    manifest_filename,manifest_data=lua.changed_chunk(index,newfirst,newfirst['entries'][0],manifest_chunk,new_manifest)
    new_digest=hashlib.md5(index).hexdigest(); updated_pointer=bytearray(pointer)
    updated_pointer[28:44]=b''.join(bytes.fromhex(new_digest)[n:n+4][::-1] for n in range(0,16,4))
    lua.require(lua.pointer_digest(updated_pointer)==new_digest and len(index)==len(raw_index), '入口摘要不符')
    files={filename:packed,manifest_filename:manifest_data,'LuaV_'+new_digest+'.bytes':bytes(index),'M_LuaV.bytes':bytes(updated_pointer)}
    report=dict(passed=True,clientRoot=str(client.resolve()),originalPointerSHA256=lua.sha(pointer),
                originalIndexSHA256=lua.sha(raw_index),originalPages=before,patchedPages=after,
                files={lua.LUA+name:lua.sha(raw) for name,raw in files.items()},clientModified=False,clientExecuted=False)
    return report,files,pointer


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--manifest',type=Path,default=ROOT/'.local/client/patch-manifest.json')
    parser.add_argument('--prepare-dir',type=Path,required=True)
    parser.add_argument('--apply',action='store_true'); parser.add_argument('--backup-dir',type=Path)
    args=parser.parse_args()
    if not (args.prepare_dir/'prepared.json').exists():
        client=Path(json.loads(args.manifest.read_text('utf-8-sig'))['clientRoot']).resolve()
        lua.require(not args.prepare_dir.resolve().is_relative_to(client), '准备目录不得位于客户端内')
        result=lua.prepare(client,args.prepare_dir,build=build_files)
    else: result=json.loads((args.prepare_dir/'prepared.json').read_text('utf-8'))
    if args.apply:
        lua.require(args.backup_dir is not None,'安装必须明确备份目录')
        result=lua.install(args.prepare_dir,args.manifest,args.backup_dir,build=build_files)
    print(json.dumps(result,ensure_ascii=True))

if __name__=='__main__': main()
