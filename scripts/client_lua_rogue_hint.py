"""补充固定国服 4.6 第一世界的锁定提示；只生成客户端显示补丁。"""
import argparse
import importlib.util
import json
from pathlib import Path
import struct

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('rogue_hint_lua', ROOT / 'scripts/client_lua_map_compat.py')
lua = importlib.util.module_from_spec(spec)
spec.loader.exec_module(lua)

PAGE = 'Ui/Rogue/MainPage/RogueSelectAreaDetailDialog'
PAGE_SHA = 'c28676ec3b2258917062d154d587c17d6f6a79eceac7c7d6c021d693d657f8d7'
RECORD_KEY = '6db5e3cb2ee8fbf7'
HINT = '需完成「始发测试」「漫游测试」「她等待刀尖已经太久」'
# 只接受已核对的原始客户端，或当前已安装地图缺省参数修复的客户端。
PROFILES = {
    lua.INDEX_SHA: lua.PAGE_SHA,
    '86f04b42ab920713c37cb60eaed073b972a3a7e5fdb29b464d4d3b82d4803bbc':
        '514e94cbd8db85fcc3456761247873b71f0570135c789989703321977032aaef',
}


def patch_record(raw):
    lua.require(lua.sha(raw) == PAGE_SHA, '选世界详情页不是已核对原件')
    decoded = lua.Bytecode(raw)
    matches = [p for p in decoded.protos if p['name'] == '_setup_locked_btns']
    lua.require(len(matches) == 1, '锁定提示函数不唯一')
    p = matches[0]
    lua.require(p['header'] == bytes([5, 1, 0, 0, 0]) and p['line'] == 452
                and len(p['code']) == 34 and p['debug']
                and len(p['lines']) == 34 and p['constants'] == [
                    '_binder', 'node_lock_hint', 'SafeSetActive', 'CS', 'RPG', 'GameCore',
                    {'import': 3224375301}, 'RogueUnlockConfigExcelTable', 'GetData',
                    '_rogue_area_data', 'RogueAreaRow', 'UnlockID', 'text_lock_tip',
                    'RogueUnlockDetail', 'SafeSetTextID'], '锁定提示函数结构变化')
    strings = list(decoded.strings)
    constants = bytearray()
    for text in ('SafeSetText', HINT):
        if text not in strings:
            strings.append(text)
        constants += b'\x03' + lua.var(strings.index(text) + 1)
    first = len(p['constants'])
    # 原函数已由 _setup_btns 的锁定分支调用。保留原始显示背景的八个字，
    # 仅在 UnlockID=12001 时写入三项原始前置；其他区域继续执行原始文案路径。
    # Luau 4/1: GETTABLEKS、LOADN、JUMPIFNOTEQ(+寄存器 AUX)、LOADK、NAMECALL、CALL、RETURN。
    branch = [15 | (1 << 8), 9,
              15 | (1 << 8) | (1 << 16), 10,
              15 | (1 << 8) | (1 << 16), 11,
              4 | (2 << 8) | (12001 << 16),
              30 | (1 << 8) | (10 << 16), 2,
              15 | (2 << 8), 0,
              15 | (2 << 8) | (2 << 16), 12,
              5 | (4 << 8) | ((first + 1) << 16),
              20 | (2 << 8) | (2 << 16), first,
              21 | (2 << 8) | (3 << 16) | (1 << 24),
              22 | (1 << 16)]
    insertion = 8
    code = p['code'][:insertion] + branch + p['code'][insertion:]
    body = raw[p['start']:p['code_count']] + lua.var(len(code))
    body += struct.pack('<' + 'I' * len(code), *code)
    body += lua.var(first + 2) + raw[p['constants_start']:p['constants_end']] + constants
    body += raw[p['constants_end']:p['line_start']]
    lines = p['lines'][:insertion] + [457] * len(branch) + p['lines'][insertion:]
    previous = 0
    body += b'\x01\x00' + bytes(len(code))
    for line in lines:
        body += struct.pack('<i', line - previous)
        previous = line
    body += b'\x01' + lua.var(len(p['locals']))
    for name, start, end, register in p['locals']:
        body += lua.var(name) + lua.var(start + len(branch) if start >= insertion else start)
        body += lua.var(end + len(branch) if end >= insertion else end) + bytes([register])
    body += lua.var(len(p['upnames'])) + b''.join(lua.var(n) for n in p['upnames'])
    table = b''.join(lua.var(len(s.encode('utf-8'))) + s.encode('utf-8') for s in strings)
    result = raw[:2] + lua.var(len(strings)) + table
    result += raw[decoded.body:p['start']] + body + raw[p['end']:]
    checked = lua.Bytecode(result)
    lua.require(len(checked.protos) == len(decoded.protos) and checked.main == decoded.main, '原型图变化')
    return result


def build_files(client):
    directory = client / lua.LUA
    digest = lua.pointer_digest((directory / 'M_LuaV.bytes').read_bytes())
    index_sha = lua.sha((directory / ('LuaV_' + digest + '.bytes')).read_bytes())
    lua.require(index_sha in PROFILES, '客户端 Lua 索引不是已核对的提示补丁来源')
    return lua.build_profile_files(client, index_sha=index_sha, page=PAGE, companion=lua.PAGE,
                                   companion_sha=PROFILES[index_sha], patcher=patch_record)


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
