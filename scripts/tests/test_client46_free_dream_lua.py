"""在固定客户端字节码上验证零价例外不影响其他商品。"""
import hashlib
import importlib.util
import json
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[2]
CLIENT = Path.home() / 'AppData/Local/hoyo/hkrpg/versions/current'
spec = importlib.util.spec_from_file_location('free_dream_shop_lua', ROOT / 'scripts/client_lua_free_dream_shop.py')
shop = importlib.util.module_from_spec(spec)
spec.loader.exec_module(shop)
INDEX = CLIENT / shop.lua.LUA / 'LuaV_fa719cbca0319ebded02bfda866d136c.bytes'


@unittest.skipUnless(INDEX.is_file(), '固定客户端 Lua 索引不可用')
class FreeDreamGuardTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        raw = INDEX.read_bytes()
        assert shop.lua.sha(raw) == shop.INDEX_SHA
        chunks = shop.lua.read_index(raw)
        directory = INDEX.parent
        first = chunks[0]
        payload = (directory / (first['digest'] + '.bytes')).read_bytes()
        entry = first['entries'][0]
        rows = [json.loads(line) for line in payload[entry['offset']:entry['offset'] + entry['length']].splitlines()]
        digest, = [row['Md5'] for row in rows if row['Path'] == shop.PAGE]
        matches = []
        for chunk in chunks:
            payload = (directory / chunk['locale'] / (chunk['digest'] + '.bytes')).read_bytes()
            assert hashlib.md5(payload).hexdigest() == chunk['digest']
            for entry in chunk['entries']:
                record = payload[entry['offset']:entry['offset'] + entry['length']]
                if hashlib.md5(record).hexdigest() == digest:
                    matches.append(record)
        cls.original, = matches
        assert shop.lua.sha(cls.original) == shop.PAGE_SHA
        cls.patched = shop.patch_record(cls.original)
        cls.before = shop.lua.Bytecode(cls.original)
        cls.after = shop.lua.Bytecode(cls.patched)
        cls.before_buy, = [p for p in cls.before.protos if p['name'] == '_on_btn_buy']
        cls.after_buy, = [p for p in cls.after.protos if p['name'] == '_on_btn_buy']

    def execute_guard(self, product):
        old, new = self.before_buy['code'], self.after_buy['code']
        extra = len(new) - len(old)
        self.assertEqual(old[:3], new[:3])
        self.assertEqual(old[3:], new[3 + extra:])
        registers = [None] * self.after_buy['header'][0]
        registers[0] = {'_product': None if product is None else {'ProductID': product}}
        sent = []
        manager = {'GCBGBBMFIJN': lambda target, values: sent.append(list(values))}
        imports = {'CS.System.Collections': {'Generic': {'List': lambda typ: lambda: []}},
                   'CS.System.String': str,
                   'CS.RPG.Client': {'GlobalVars': {'s_NetworkManager': manager}}}
        pc, end, steps = 3, 3 + extra, 0
        while pc < end:
            steps += 1
            self.assertLess(steps, 100, '保护分支不得死循环')
            word = new[pc]
            op, a, b = word & 255, word >> 8 & 255, word >> 16 & 255
            d = word >> 16
            d = d - 65536 if d >= 32768 else d
            if op == 15:
                registers[a] = registers[b][self.after_buy['constants'][new[pc + 1]]]
                pc += 2
            elif op == 26:
                pc = pc + 1 + d if not registers[a] else pc + 1
            elif op == 27:
                pc = pc + 1 + d if registers[a] == registers[new[pc + 1]] else pc + 2
            elif op == 23:
                pc = pc + 1 + d
            elif op == 5:
                registers[a] = self.after_buy['constants'][word >> 16]
                pc += 1
            elif op == 3:
                registers[a] = bool(b)
                pc += 1
            elif op == 6:
                registers[a] = registers[b]
                pc += 1
            elif op == 12:
                encoded = new[pc + 1]
                count = encoded >> 30
                path = '.'.join(self.after_buy['constants'][(encoded >> shift) & 1023]
                                for shift in (20, 10, 0)[:count])
                registers[a] = imports[path]
                pc += 2
            elif op == 20:
                target = registers[b]
                name = self.after_buy['constants'][new[pc + 1]]
                registers[a + 1] = target
                registers[a] = ((lambda obj, item: obj.append(item)) if name == 'Add' else target[name])
                pc += 2
            elif op == 21:
                c = word >> 24
                result = registers[a](*registers[a + 1:a + b])
                if c == 2:
                    registers[a] = result
                else:
                    self.assertEqual(1, c)
                pc += 1
            elif op == 22:
                self.assertEqual(1, word >> 16)
                return True, sent
            else:
                self.fail(f'意外指令 {op}')
        self.assertEqual(end, pc)
        return False, sent

    def test_only_six_local_free_products_send_a_session_bound_claim(self):
        for product in shop.PRODUCTS:
            with self.subTest(product=product):
                self.assertEqual((True, [[product]]), self.execute_guard(product))
        for product in (None, '', 'rpgchncoin60tier2', 'rpgchnmonthcard', 'unrelated_paid_gift'):
            with self.subTest(product=product):
                self.assertEqual((False, []), self.execute_guard(product))

    def test_original_functions_and_original_buy_logic_remain_unchanged(self):
        self.assertEqual(self.before.main, self.after.main)
        self.assertEqual(len(self.before.protos), len(self.after.protos))
        for old, new in zip(self.before.protos, self.after.protos):
            if old['name'] != '_on_btn_buy':
                self.assertEqual(self.original[old['start']:old['end']], self.patched[new['start']:new['end']])

    def test_unknown_source_is_rejected(self):
        with self.assertRaises(ValueError):
            shop.patch_record(self.original + b'changed')


if __name__ == '__main__':
    unittest.main()
