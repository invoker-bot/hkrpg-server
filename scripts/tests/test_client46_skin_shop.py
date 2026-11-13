"""商品表和无赠送头像详情的回归；只读取客户端，不启动或修改游戏。"""
import hashlib
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
CLIENT = Path(r'C:\Users\InvokerBot\AppData\Local\hoyo\hkrpg\versions\current')


def load(name, filename):
    spec = importlib.util.spec_from_file_location(name, ROOT / 'scripts' / filename)
    value = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(value)
    return value


data = load('skin_shop_data_test', 'client_skin_shop_data.py')
ui = load('skin_shop_ui_test', 'client_lua_skin_shop.py')


class GoodsTableTest(unittest.TestCase):
    def make_table(self):
        template = {'GoodsID': 107002, 'ItemID': 1131001, 'ItemCount': 1,
                    'CurrencyList': [3], 'CurrencyCostList': [1680], 'GoodsSortID': 98,
                    'LimitType1': 9, 'LimitValue1List': [1], 'LimitValue2List': [],
                    'OnShelfValue1List': [], 'OnShelfValue2List': [], 'LimitTimes': 1,
                    'ShopID': 107, 'ScheduleDataID': 10107002, 'IsNew': True}
        rows = [template, dict(template, GoodsID=107012, ItemID=1150501, ShopID=996,
                              CurrencyList=[1], CurrencyCostList=[2680]),
                dict(template, GoodsID=107014, ItemID=1140901, ShopID=996,
                     CurrencyList=[3], CurrencyCostList=[1350])]
        return b'\0' + data.signed(len(rows)) + b''.join(data.encode_row(row) for row in rows)

    def test_existing_rows_are_unchanged_and_three_distinct_prices_match_server(self):
        raw = self.make_table()
        before = data.decode_table(raw)
        after = data.decode_table(data.append_rows(raw))
        self.assertEqual(before, after[:len(before)])
        for (goods, item, currency, price, _), (row, _) in zip(data.ENTRIES, after[len(before):]):
            self.assertEqual((goods, item, [currency], [price], 107, 1),
                             (row['GoodsID'], row['ItemID'], row['CurrencyList'], row['CurrencyCostList'],
                              row['ShopID'], row['LimitTimes']))

    def test_conflicting_id_or_changed_price_rejects_the_whole_table(self):
        rows = [r for r, _ in data.decode_table(self.make_table())]
        for changed in (rows + [dict(rows[0], GoodsID=107013)],
                        [rows[0], dict(rows[1], CurrencyCostList=[1]), rows[2]]):
            raw = b'\0' + data.signed(len(changed)) + b''.join(data.encode_row(r) for r in changed)
            with self.assertRaises(ValueError):
                data.append_rows(raw)
        with self.assertRaises(ValueError):
            data.patch_record(self.make_table())

    def test_install_refuses_to_touch_a_running_client(self):
        class Launcher:
            def require_stopped(self):
                raise RuntimeError('client running')
        with patch.object(data, 'module', return_value=Launcher()):
            with self.assertRaisesRegex(RuntimeError, 'client running'):
                data.install(Path('unused'), Path('unused-backup'))

    def test_install_backup_and_idempotence_on_a_disposable_client(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            client = root / 'client'
            directory = client / data.CLIENT_DIR
            directory.mkdir(parents=True)
            prepared = root / 'prepared'
            prepared.mkdir()
            index_name = 'DesignV_' + 'a' * 32 + '.bytes'
            chunk_name = 'b' * 32 + '.bytes'
            old, new, chunk = b'old-index', b'new-index', b'new-chunk'
            (directory / index_name).write_bytes(old)
            (prepared / index_name).write_bytes(new)
            (prepared / chunk_name).write_bytes(chunk)
            report = {'client': str(client.resolve()), 'oldIndexSHA256': data.sha(old),
                      'indexName': index_name, 'newIndexSHA256': data.sha(new),
                      'chunkName': chunk_name, 'chunkSHA256': data.sha(chunk)}
            (prepared / 'prepared.json').write_text(json.dumps(report), encoding='utf-8')
            plan = {'client': client.resolve(), 'indexPath': directory / index_name,
                    'targetPath': directory / chunk_name, 'oldIndex': old, 'index': new, 'chunk': chunk}
            class Launcher:
                def require_stopped(self): pass
                def load_manifest(self, _): return {'clientFiles': {}}, client.resolve(), None
            with patch.object(data, 'module', return_value=Launcher()), \
                 patch.object(data, 'make_plan', return_value=plan), \
                 patch.object(data, 'INDEX_SHA', data.sha(old)):
                backup = root / 'backup'
                result = data.install(prepared, backup)
                self.assertTrue(result['clientModified'])
                self.assertEqual(old, (backup / index_name).read_bytes())
                self.assertEqual(new, (directory / index_name).read_bytes())
                self.assertEqual(chunk, (directory / chunk_name).read_bytes())
                again = data.install(prepared, root / 'second-backup')
                self.assertTrue(again['alreadyInstalled'])
                self.assertFalse((root / 'second-backup').exists())

    def test_atomic_index_replacement_refuses_an_unexpected_old_value(self):
        with tempfile.TemporaryDirectory() as temp:
            index = Path(temp) / 'index.bytes'
            index.write_bytes(b'other')
            with self.assertRaises(ValueError):
                data.atomic_replace(index, b'new', data.sha(b'expected'))
            self.assertEqual(b'other', index.read_bytes())


class DetailGuardTest(unittest.TestCase):
    def execute_guard(self, card_id):
        code = ui.guard((35, 36, 37))
        widgets = {name: {'gameObject': {'active': None}} for name in ('text', 'image', 'button')}
        constants = {9: 'skin', 27: 'card', 11: 'binder', 28: 'text', 30: 'image',
                     35: 'button', 36: 'gameObject', 37: 'SetActive'}
        registers = [None] * 9
        registers[0] = {'skin': {'card': card_id}, 'binder': widgets}
        pc = 0
        while pc < len(code):
            word = code[pc]
            op, a, b, c = word & 255, word >> 8 & 255, word >> 16 & 255, word >> 24 & 255
            displacement = (word >> 16) & 0xffff
            if displacement >= 0x8000:
                displacement -= 0x10000
            if op == 15:
                registers[a] = registers[b][constants[code[pc + 1]]]
                pc += 2
            elif op == 4:
                registers[a] = displacement
                pc += 1
            elif op == 3:
                registers[a] = bool(b)
                pc += 1
            elif op == 30:
                pc = pc + 1 + displacement if registers[a] != registers[code[pc + 1]] else pc + 2
            elif op == 6:
                registers[a] = registers[b]
                pc += 1
            elif op == 20:
                self.assertEqual('SetActive', constants[code[pc + 1]])
                registers[a + 1], registers[a] = registers[b], 'SetActive'
                pc += 2
            elif op == 21:
                self.assertEqual((3, 1), (b, c))
                registers[a + 1]['active'] = registers[a + 2]
                pc += 1
            elif op == 26:
                pc = pc + 1 + displacement if not registers[a] else pc + 1
            else:
                self.fail(f'unexpected opcode {op}')
        return [widgets[k]['gameObject']['active'] for k in ('text', 'image', 'button')], pc - len(code)

    def test_stellar_skin_without_portrait_hides_widgets_and_skips_the_portrait_block(self):
        self.assertEqual(([False, False, False], 35), self.execute_guard(0))

    def test_other_skins_restore_widgets_and_keep_the_existing_portrait_block(self):
        self.assertEqual(([True, True, True], 0), self.execute_guard(202062))


@unittest.skipUnless((CLIENT / data.CLIENT_DIR / (data.SOURCE_CHUNK_MD5 + '.bytes')).is_file(), '没有固定客户端原始分块')
class LocalClientRecordTest(unittest.TestCase):
    def test_all_900_original_rows_remain_identical_after_three_additions(self):
        chunk = (CLIENT / data.CLIENT_DIR / (data.SOURCE_CHUNK_MD5 + '.bytes')).read_bytes()
        self.assertEqual(data.SOURCE_CHUNK_MD5, hashlib.md5(chunk).hexdigest())
        raw = chunk[16547888:16547888 + 26495]
        self.assertEqual(data.RECORD_SHA, data.sha(raw))
        before = data.decode_table(raw)
        after = data.decode_table(data.patch_record(raw))
        self.assertEqual(900, len(before))
        self.assertEqual(903, len(after))
        self.assertEqual(before, after[:900])

    def test_unrecognized_record_never_receives_the_patch(self):
        with self.assertRaises(ValueError):
            data.patch_record(b'\0\0')

    def test_native_client_and_server_have_the_same_eight_skin_identities(self):
        source = CLIENT / data.CLIENT_DIR / 'e8155d8f201f88801abacc4b3e32694f.bytes'
        if not source.is_file():
            self.skipTest('固定角色时装表不可用')
        chunk = source.read_bytes()
        self.assertEqual('e8155d8f201f88801abacc4b3e32694f', hashlib.md5(chunk).hexdigest())
        raw = chunk[3391731:3391731 + 8509]
        self.assertEqual('45fb5f48f0cce23f6081309f09db59fb1a9139b5c066844698b014e684c82bb6', data.sha(raw))
        fields = [('ID','u'),('AvatarID','u'),('Type','e'),('PlayerCardID','u'),('ActivitySkinName','t'),
                  ('ActivityIntroDataID','u'),('AvatarSkinSynopsis','t'),('FreeStyleCharacterID','s'),
                  ('AvatarCutinFrontImgPath','s'),('AssistOffset','f'),('PlayerPrefabPath','s'),
                  ('DefaultAvatarModelPath','s'),('UIAvatarModelPath','s'),('UltraSkillCutInPrefabPath','s'),
                  ('DefaultAvatarHeadIconPath','s'),('AdventureDefaultAvatarHeadIconPath','s'),
                  ('WaitingAvatarHeadIconPath','s'),('ActionAvatarHeadIconPath','s'),('SideAvatarHeadIconPath','s'),
                  ('AvatarSideIconPath','s'),('AvatarCutinImgPath','s'),('AvatarCutinBgImgPath','s'),
                  ('AvatarMiniIconPath','s'),('AvatarDropOffset','f'),('AvatarSelfShowOffset','f'),
                  ('ShowType','e'),('ShowParam','u'),('PanelID','u'),('IntroDataID','u'),('VideoID','u'),
                  ('ShopBgPath','s'),('GachaResultImgPath','s'),('SkinConfigPath','s'),
                  ('AdventureCharacterConfigOverrideJsonPath','s'),('AudioEventTag','s'),('DressIconPath','s')]
        reader = data.graphs.Reader(raw, {})
        self.assertEqual((0, 8), (reader.uvar(), reader.integer(32, True)))
        client = {}
        for _ in range(8):
            mask, row = reader.uvar(), {}
            self.assertEqual(0, mask >> len(fields))
            for bit, (name, kind) in enumerate(fields):
                if not mask & (1 << bit):
                    continue
                if kind == 'u': value = reader.integer(32, False)
                elif kind == 'e': value = reader.integer(32, True)
                elif kind == 's': value = reader.read({'kind': 14})
                elif kind == 't': value = (reader.integer(32, True), reader.uvar())
                else: value = reader.read({'kind': 29, 'elementType': {'kind': 12}})
                row[name] = value
            client[row['ID']] = (row['AvatarID'], row.get('PlayerCardID', 0))
        self.assertEqual(len(raw), reader.pos)
        path = ROOT / 'resources/4.6/ExcelOutput/AvatarSkin.json'
        server = {row['ID']: (row['AvatarID'], row.get('PlayerCardID', 0))
                  for row in json.loads(path.read_text(encoding='utf-8-sig'))}
        self.assertEqual(server, client)

    def test_pinned_detail_patch_preserves_all_other_prototypes(self):
        directory = CLIENT / ui.lua.LUA
        indexes = [p for p in directory.glob('LuaV_*.bytes') if ui.lua.sha(p.read_bytes()) == ui.INDEX_SHA]
        if len(indexes) != 1:
            self.skipTest('固定原始 Lua 索引不可用')
        chunks = ui.lua.read_index(indexes[0].read_bytes())
        first = chunks[0]
        payload = (directory / (first['digest'] + '.bytes')).read_bytes()
        entry = first['entries'][0]
        rows = [json.loads(line) for line in payload[entry['offset']:entry['offset'] + entry['length']].splitlines()]
        expected = next(row['Md5'] for row in rows if row['Path'] == ui.PAGE)
        matches = []
        for chunk in chunks:
            payload = (directory / chunk['locale'] / (chunk['digest'] + '.bytes')).read_bytes()
            self.assertEqual(chunk['digest'], hashlib.md5(payload).hexdigest())
            for entry in chunk['entries']:
                raw = payload[entry['offset']:entry['offset'] + entry['length']]
                if hashlib.md5(raw).hexdigest() == expected:
                    matches.append(raw)
        self.assertEqual(1, len(matches))
        original = ui.lua.Bytecode(matches[0])
        changed = ui.lua.Bytecode(ui.patch_record(matches[0]))
        self.assertEqual(original.main, changed.main)
        for left, right in zip(original.protos, changed.protos):
            if left['name'] != 'setup_view':
                self.assertEqual(left['code'], right['code'])
                self.assertEqual(left['constants'], right['constants'])
            else:
                extra = ui.guard((35, 36, 37))
                self.assertEqual(left['code'][:67], right['code'][:67])
                self.assertEqual(left['code'][67:], right['code'][67 + len(extra):])


if __name__ == '__main__':
    unittest.main()
