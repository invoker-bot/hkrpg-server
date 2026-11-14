"""核验研究窗口仅释放原生对话管理器自己的输入锁。"""
import sys
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'scripts'))
sys.path.insert(0, str(ROOT / 'scripts/tests'))
import client_monster_research_focus as focus
import client_monster_research_unlock as unlock
import test_client_monster_research_focus as previous


def run_prefix(raw, talk, manager):
    """只执行新加的原生 Luau 指令，保留原 init 给客户端运行。"""
    bc, old = focus.lua.Bytecode(raw), focus.lua.Bytecode(previous.page())
    p = next(v for v in bc.protos if v['name'] == 'init')
    original = next(v for v in old.protos if v['name'] == 'init')
    boundary = len(p['code']) - len(original['code'])
    code, constants = p['code'], p['constants']
    registers = [None] * p['header'][0]
    registers[0], registers[2] = {'_ui_layer': 'Page'}, talk
    globals_ = {'CS': {'RPG': {'Client': {'GlobalVars': {'s_DialogueManager': manager}}}}}
    pc = 0
    while pc < boundary:
        word = code[pc]
        op, a, b = word & 255, (word >> 8) & 255, (word >> 16) & 255
        displacement = word >> 16
        if displacement >= 32768:
            displacement -= 65536
        pc += 1
        if op == 4:
            registers[a] = displacement
        elif op == 12:
            aux = code[pc]; pc += 1
            value = globals_
            for n in range(aux >> 30):
                value = value[constants[(aux >> (20 - n * 10)) & 1023]]
            registers[a] = value
        elif op == 15:
            key = constants[code[pc]]; pc += 1
            registers[a] = registers[b].get(key)
        elif op == 20:
            key = constants[code[pc]]; pc += 1
            receiver = registers[b]
            registers[a], registers[a + 1] = receiver[key], receiver
        elif op == 21:
            assert b == 2 and (word >> 24) == 1
            registers[a](registers[a + 1])
        elif op == 26:
            if registers[a] is None or registers[a] is False:
                pc += displacement
        elif op == 30:
            other = code[pc]
            pc += displacement if registers[a] != registers[other] else 1
        else:
            raise AssertionError('未核对的前置指令 ' + str(op))
    assert pc == boundary
    return registers[0]


class ResearchUnlockTest(unittest.TestCase):
    def test_only_marked_research_page_releases_dialogue_lock(self):
        changed = unlock.patch_page(previous.page())
        calls = []
        manager = {'TryUnlockInput': lambda receiver: calls.append(receiver)}
        talk = {'TextSpeed': focus.MARKER, 'TalkSentenceID': 0}
        self.assertEqual({'_ui_layer': 'Page'}, run_prefix(changed, talk, manager))
        self.assertEqual([manager], calls)
        for value in (None, {}, {'TextSpeed': 0}, {'TextSpeed': focus.MARKER},
                      {'TextSpeed': focus.MARKER, 'TalkSentenceID': 1}):
            calls.clear()
            self.assertEqual({'_ui_layer': 'Page'}, run_prefix(changed, value, manager))
            self.assertEqual([], calls)
        self.assertEqual({'_ui_layer': 'Page'}, run_prefix(changed, talk, None))

    def test_original_init_and_other_functions_are_preserved(self):
        raw, changed = previous.page(), unlock.patch_page(previous.page())
        old, new = focus.lua.Bytecode(raw), focus.lua.Bytecode(changed)
        a = next(p for p in old.protos if p['name'] == 'init')
        b = next(p for p in new.protos if p['name'] == 'init')
        self.assertGreater(len(b['code']), len(a['code']))
        self.assertEqual(a['code'], b['code'][-len(a['code']):])
        self.assertEqual(a['lines'], b['lines'][-len(a['lines']):])
        self.assertEqual(old.main, new.main)
        for oldp, newp in zip(old.protos, new.protos):
            if oldp['name'] != 'init':
                self.assertEqual(raw[oldp['start']:oldp['end']], changed[newp['start']:newp['end']])

    def test_unknown_page_is_rejected(self):
        with self.assertRaises(ValueError):
            unlock.patch_page(previous.page() + b'\0')


if __name__ == '__main__':
    unittest.main()
