"""为固定 4.6 研究材料窗口准备局部对话解锁兼容候选。"""
import struct

import client_monster_research_focus as focus


def patch_page(raw):
    lua = focus.lua
    lua.require(lua.sha(raw) == focus.PAGE_SHA, '材料页不是已核对原件')
    decoded = lua.Bytecode(raw)
    matches = [p for p in decoded.protos if p['name'] == 'init']
    lua.require(len(matches) == 1, '材料页 init 不唯一')
    p = matches[0]
    lua.require(p['header'] == bytes([7, 5, 0, 0, 0]) and p['line'] == 29
                and p['debug'] and len(p['code']) == len(p['lines']) == 11
                and p['constants'] == ['_required_items', '_hint_desc', 'G', 'RogueTalkUtils',
                                       '_construct_one_simple_talk', {'import': 3223325700}, '_talk_info'],
                '材料页初始化结构变化')
    strings, constants = list(decoded.strings), bytearray()
    def text_constant(value):
        if value not in strings:
            strings.append(value)
        return b'\x03' + lua.var(strings.index(value) + 1)
    first = len(p['constants'])
    for value in ('TextSpeed', 'TalkSentenceID', 'CS', 'RPG', 'Client', 'GlobalVars',
                  's_DialogueManager', 'TryUnlockInput'):
        constants += text_constant(value)
    imported = 0xc0000000 | ((first + 2) << 20) | ((first + 3) << 10) | (first + 4)
    constants += b'\x04' + struct.pack('<I', imported)
    # 只接受研究节点标记和零对话句子；其他任务与实际对话不触碰任何锁。
    # 调用序列沿用本机 DrinkMakerCheersEntrancePage.init 的 GETIMPORT、
    # GETTABLEKS、NAMECALL 和 CALL；空管理器时跳过。
    branch = [26 | (2 << 8) | (20 << 16),
              15 | (5 << 8) | (2 << 16), first,
              4 | (6 << 8) | (int(focus.MARKER) << 16),
              30 | (5 << 8) | (16 << 16), 6,
              15 | (5 << 8) | (2 << 16), first + 1,
              4 | (6 << 8),
              30 | (5 << 8) | (11 << 16), 6,
              12 | (6 << 8) | ((first + 8) << 16), imported,
              15 | (5 << 8) | (6 << 16) | (0xac << 24), first + 5,
              15 | (5 << 8) | (5 << 16) | (0xf2 << 24), first + 6,
              26 | (5 << 8) | (3 << 16),
              20 | (5 << 8) | (5 << 16) | (0x56 << 24), first + 7,
              21 | (5 << 8) | (2 << 16) | (1 << 24)]
    code = branch + p['code']
    body = raw[p['start']:p['code_count']] + lua.var(len(code))
    body += struct.pack('<' + 'I' * len(code), *code)
    body += lua.var(first + 9) + raw[p['constants_start']:p['constants_end']] + constants
    body += raw[p['constants_end']:p['line_start']]
    previous = 0
    body += b'\x01\x00' + bytes(len(code))
    for line in [p['line']] * len(branch) + p['lines']:
        body += struct.pack('<i', line - previous)
        previous = line
    body += b'\x01' + lua.var(len(p['locals']))
    for name, start, end, register in p['locals']:
        body += lua.var(name) + lua.var(start + len(branch) if start else 0)
        body += lua.var(end + len(branch)) + bytes([register])
    body += lua.var(len(p['upnames'])) + b''.join(lua.var(n) for n in p['upnames'])
    table = b''.join(lua.var(len(s.encode('utf8'))) + s.encode('utf8') for s in strings)
    result = raw[:2] + lua.var(len(strings)) + table + raw[decoded.body:p['start']] + body + raw[p['end']:]
    checked = lua.Bytecode(result)
    lua.require(len(checked.protos) == len(decoded.protos) and checked.main == decoded.main,
                '材料页原型图变化')
    return result


def main():
    original = focus.patch_page
    try:
        focus.patch_page = patch_page
        focus.main()
    finally:
        focus.patch_page = original


if __name__ == '__main__':
    main()
