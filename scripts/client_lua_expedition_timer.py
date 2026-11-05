"""为固定国服 4.6 委托页修复领奖后的小时刷新；只准备显示补丁，不启动游戏。"""
import argparse
import importlib.util
import json
from pathlib import Path
import struct

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('expedition_timer_lua', ROOT / 'scripts/client_lua_map_compat.py')
lua = importlib.util.module_from_spec(spec)
spec.loader.exec_module(lua)

PAGE = 'Ui/Expedition/ExpeditionMainPage'
PAGE_SHA = 'b77ba92c58a3e37b0a4af77cf386f8e5a85c93196f851efd900fc87451850693'
RECORD_KEY = 'd7592d6010bb721c'
PROFILES = {
    lua.INDEX_SHA: lua.PAGE_SHA,
    '86f04b42ab920713c37cb60eaed073b972a3a7e5fdb29b464d4d3b82d4803bbc':
        '514e94cbd8db85fcc3456761247873b71f0570135c789989703321977032aaef',
    '580310a459ddbce2d06e1338a7e5f023841541dd966b7ff731043acb4198d89c':
        '514e94cbd8db85fcc3456761247873b71f0570135c789989703321977032aaef',
}


def patch_record(raw):
    lua.require(lua.sha(raw) == PAGE_SHA, '委托页不是已核对原件')
    decoded = lua.Bytecode(raw)
    matches = [p for p in decoded.protos if p['name'] == '_on_reward_got']
    lua.require(len(matches) == 1, '委托领奖回调不唯一')
    p = matches[0]
    lua.require(p['header'] == bytes([5, 2, 0, 0, 0]) and p['line'] == 521
                and len(p['code']) == len(p['lines']) == 9 and p['debug']
                and not p['children'] and p['constants'] == [
                    'G', 'RewardUtils', 'show_reward_dialog', {'import': 3221226498}, '_refresh'],
                '委托领奖回调结构变化')
    # 原函数先显示本次奖励，再 _refresh(true)。沿用已有的取消/重建定时器方法，
    # 恢复达到30小时停表后的新一轮小时刷新；不发送请求，也不修改服务端计时。
    name = '_try_add_refresh_timer'
    lua.require(name in decoded.strings, '既有小时刷新方法缺失')
    constant = len(p['constants'])
    extra = [20 | (2 << 8), constant, 21 | (2 << 8) | (2 << 16) | (1 << 24)]
    insertion = len(p['code']) - 1
    code = p['code'][:insertion] + extra + p['code'][insertion:]
    body = raw[p['start']:p['code_count']] + lua.var(len(code))
    body += struct.pack('<' + 'I' * len(code), *code)
    body += lua.var(constant + 1) + raw[p['constants_start']:p['constants_end']]
    body += b'\x03' + lua.var(decoded.strings.index(name) + 1)
    body += raw[p['constants_end']:p['line_start']]
    lines = p['lines'][:insertion] + [523] * len(extra) + p['lines'][insertion:]
    body += b'\x01\x00' + bytes(len(code))
    previous = 0
    for line in lines:
        body += struct.pack('<i', line - previous)
        previous = line
    body += b'\x01' + lua.var(len(p['locals']))
    for name, start, end, register in p['locals']:
        body += lua.var(name) + lua.var(start + len(extra) if start >= insertion else start)
        body += lua.var(end + len(extra) if end >= insertion else end) + bytes([register])
    body += lua.var(len(p['upnames'])) + b''.join(lua.var(n) for n in p['upnames'])
    result = raw[:p['start']] + body + raw[p['end']:]
    checked = lua.Bytecode(result)
    lua.require(len(checked.protos) == len(decoded.protos) and checked.main == decoded.main,
                '委托页原型图变化')
    return result


def build_files(client):
    directory = client / lua.LUA
    digest = lua.pointer_digest((directory / 'M_LuaV.bytes').read_bytes())
    index_sha = lua.sha((directory / ('LuaV_' + digest + '.bytes')).read_bytes())
    lua.require(index_sha in PROFILES, '客户端 Lua 索引不是已核对的委托补丁来源')
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
