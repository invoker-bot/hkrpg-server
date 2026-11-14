"""商品表和无赠送头像详情的回归；只读取客户端，不启动或修改游戏。"""
import hashlib
import importlib.util
import json
from pathlib import Path
import shutil
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
                     CurrencyList=[3], CurrencyCostList=[1350]),
                dict(template, GoodsID=109002, ItemID=227013, ShopID=109,
                     CurrencyList=[3], CurrencyCostList=[680]),
                dict(template, GoodsID=108012, ItemID=252004, ShopID=108,
                     CurrencyList=[263], CurrencyCostList=[1200], LimitType1=0,
                     LimitValue1List=[0])]
        return b'\0' + data.signed(len(rows)) + b''.join(data.encode_row(row) for row in rows)

    def test_unlisted_outfits_and_chef_pam_receive_known_shop_entries(self):
        rows = {row['GoodsID']: row for row, _ in data.decode_table(data.append_rows(self.make_table()))}
        for goods, item in zip(range(109006, 109014),
                               [227001, 227002, 227003, 227005, 227007, 227008, 227015, 227017]):
            self.assertIn(goods, rows)
            row = rows[goods]
            self.assertEqual((109, item, [3], [680], 1),
                             (row['ShopID'], row['ItemID'], row['CurrencyList'],
                              row['CurrencyCostList'], row['LimitTimes']))
            self.assertNotIn('ScheduleDataID', row)
        self.assertIn(108022, rows)
        chef = rows[108022]
        self.assertEqual((108, 252001, [263], [1200], 1),
                         (chef['ShopID'], chef['ItemID'], chef['CurrencyList'],
                          chef['CurrencyCostList'], chef['LimitTimes']))
        self.assertNotIn('ScheduleDataID', chef)

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
    def original_detail_page(self):
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
        return matches[0]

    def test_stellar_skin_has_its_own_background_preview(self):
        patched = ui.lua.Bytecode(ui.patch_record(self.original_detail_page()))
        background = next(p for p in patched.protos if p['name'] == '_load_skin_bg_obj')
        # This is the original special-skin artwork, not the default character portrait.
        self.assertIn('SpriteOutput/StellarCustomizationMaxFigure/1141501.png', background['constants'])
        excel = ROOT / 'resources/4.6/ExcelOutput'
        skins = json.loads((excel / 'AvatarSkin.json').read_text(encoding='utf-8-sig'))
        skin = next(row for row in skins if row['ID'] == 1141501)
        self.assertEqual('', skin['ShopBgPath'])
        self.assertEqual('Stellar', skin['Type'])
        constants = json.loads((excel / 'ConstValueClient.json').read_text(encoding='utf-8-sig'))
        figure = next(row for row in constants
                      if row['ConstValueName'] == 'AvatarCutinFrontImgPath_StellarCustomization_1141501')
        self.assertEqual(ui.STELLAR_FIGURE, figure['Value']['StringValue'])
        with self.assertRaises(ValueError):
            ui.patch_background_record(b'unknown page')

    def test_installed_profile_can_be_prepared_backed_up_and_installed_on_a_copy(self):
        source = CLIENT / ui.lua.LUA
        index_name = 'LuaV_c6ba410adafd0d22ad63cc8c503021d0.bytes'
        index = (source / index_name).read_bytes()
        self.assertEqual(ui.INSTALLED_INDEX_SHA, ui.lua.sha(index))
        chunks = ui.lua.read_index(index)
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            client = root / 'client'
            directory = client / ui.lua.LUA
            directory.mkdir(parents=True)
            (directory / index_name).write_bytes(index)
            pointer = bytearray((source / 'M_LuaV.bytes').read_bytes())
            digest = bytes.fromhex('c6ba410adafd0d22ad63cc8c503021d0')
            pointer[28:44] = b''.join(digest[i:i + 4][::-1] for i in range(0, 16, 4))
            (directory / 'M_LuaV.bytes').write_bytes(pointer)
            for chunk in chunks:
                name = Path(chunk['locale']) / (chunk['digest'] + '.bytes')
                (directory / name).parent.mkdir(parents=True, exist_ok=True)
                shutil.copyfile(source / name, directory / name)
            launcher = ui.lua.launcher_module()
            files = {}
            for name in launcher.REQUIRED_FILES:
                path = client / name
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_bytes(name.encode('utf-8'))
                files[name] = ui.lua.sha(path.read_bytes())
            old_names = ['29331ada2e56dfe03d4aac7ec4ca0897.bytes',
                         'dfc26c8f5d4fb3d4db89e28fd51c0199.bytes', index_name, 'M_LuaV.bytes']
            lua_files = {ui.lua.LUA + name: ui.lua.sha((directory / name).read_bytes()) for name in old_names}
            document = {'schema': 2, 'clientRoot': str(client), 'clientFiles': files,
                        'clientLuaFiles': lua_files,
                        'debugConsole': {'mode': 'external', 'sha256': '0' * 64, 'backupPath': 'console.dll'},
                        'service': {'readyUrl': 'http://127.0.0.1:18080/health/ready', 'startCommand': 'fixture'}}
            manifest = root / 'manifest.json'
            manifest.write_text(json.dumps(document), encoding='utf-8')
            old_manifest = manifest.read_bytes()
            prepared = root / 'prepared'
            report = ui.lua.prepare(client, prepared, build=ui.build_files)
            self.assertFalse(report['clientModified'])
            self.assertEqual(ui.INSTALLED_PAGE_SHA, report['originalPageSHA256'])
            self.assertNotEqual(report['originalPageSHA256'], report['patchedPageSHA256'])
            with patch.object(launcher, 'client_running', return_value=False), \
                 patch.object(ui.lua, 'launcher_module', return_value=launcher):
                backup = root / 'backup'
                result = ui.lua.install(prepared, manifest, backup, build=ui.build_files)
                self.assertTrue(result['passed'])
                self.assertEqual(bytes(pointer), (backup / 'M_LuaV.bytes').read_bytes())
                self.assertEqual(old_manifest, (backup / 'patch-manifest.json').read_bytes())
                self.assertEqual(report['files'], json.loads(manifest.read_text('utf-8'))['clientLuaFiles'])
                self.assertTrue(ui.lua.install(prepared, manifest, root / 'again', build=ui.build_files)['alreadyInstalled'])

            new_pointer = (directory / 'M_LuaV.bytes').read_bytes()
            new_index = (directory / ('LuaV_' + ui.lua.pointer_digest(new_pointer) + '.bytes')).read_bytes()
            new_chunks = ui.lua.read_index(new_index)
            # Only the module manifest and the selected background page may change.
            changed = []
            self.assertEqual(len(chunks), len(new_chunks))
            for old_chunk, new_chunk in zip(chunks, new_chunks):
                before = (source / old_chunk['locale'] / (old_chunk['digest'] + '.bytes')).read_bytes()
                after = (directory / new_chunk['locale'] / (new_chunk['digest'] + '.bytes')).read_bytes()
                self.assertEqual(len(old_chunk['entries']), len(new_chunk['entries']))
                for old, new in zip(old_chunk['entries'], new_chunk['entries']):
                    self.assertEqual(old['key'], new['key'])
                    a = before[old['offset']:old['offset'] + old['length']]
                    b = after[new['offset']:new['offset'] + new['length']]
                    if a != b: changed.append((ui.lua.sha(a), ui.lua.sha(b)))
            self.assertEqual(2, len(changed))
            self.assertIn((ui.INSTALLED_PAGE_SHA, report['patchedPageSHA256']), changed)

    def test_first_preview_upgrade_requires_and_preserves_the_verified_predecessor(self):
        source = CLIENT / ui.lua.LUA
        first_name = 'LuaV_d8cc9ff8f252439fd2901bd5ccab08fa.bytes'
        first_index = (source / first_name).read_bytes()
        baseline_index = (source / ui.INSTALLED_INDEX_NAME).read_bytes()
        self.assertEqual(ui.FIRST_PREVIEW_INDEX_SHA, ui.lua.sha(first_index))
        with tempfile.TemporaryDirectory() as temp:
            client = Path(temp) / 'client'
            directory = client / ui.lua.LUA
            directory.mkdir(parents=True)
            for name, index in [(first_name, first_index), (ui.INSTALLED_INDEX_NAME, baseline_index)]:
                (directory / name).write_bytes(index)
                for chunk in ui.lua.read_index(index):
                    relative = Path(chunk['locale']) / (chunk['digest'] + '.bytes')
                    target = directory / relative
                    target.parent.mkdir(parents=True, exist_ok=True)
                    if not target.exists(): shutil.copyfile(source / relative, target)
            pointer = bytearray((source / 'M_LuaV.bytes').read_bytes())
            digest = bytes.fromhex('d8cc9ff8f252439fd2901bd5ccab08fa')
            pointer[28:44] = b''.join(digest[i:i + 4][::-1] for i in range(0, 16, 4))
            (directory / 'M_LuaV.bytes').write_bytes(pointer)
            report, _, _ = ui.build_files(client)
            self.assertEqual(ui.FIRST_PREVIEW_PAGE_SHA, report['originalPageSHA256'])
            self.assertEqual(ui.lua.sha(ui.patch_record(self.original_detail_page())), report['patchedPageSHA256'])
            (directory / ui.INSTALLED_INDEX_NAME).write_bytes(b'changed predecessor')
            with self.assertRaisesRegex(ValueError, '既有时装索引不符'):
                ui.build_files(client)
            (directory / ui.INSTALLED_INDEX_NAME).write_bytes(baseline_index)
            prepared = Path(temp) / 'prepared'
            ui.lua.prepare(client, prepared, build=ui.build_files)
            launcher = ui.lua.launcher_module()
            files = {}
            for name in launcher.REQUIRED_FILES:
                path = client / name
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_bytes(name.encode('utf-8'))
                files[name] = ui.lua.sha(path.read_bytes())
            old_names = ['e3c095f39b5566cddae2cb43006ffa74.bytes',
                         'c02b019f4e4c52af964e4873e0c0f848.bytes', first_name, 'M_LuaV.bytes']
            lua_files = {ui.lua.LUA + name: ui.lua.sha((directory / name).read_bytes()) for name in old_names}
            document = {'schema': 2, 'clientRoot': str(client), 'clientFiles': files, 'clientLuaFiles': lua_files,
                        'debugConsole': {'mode': 'external', 'sha256': '0' * 64, 'backupPath': 'console.dll'},
                        'service': {'readyUrl': 'http://127.0.0.1:18080/health/ready', 'startCommand': 'fixture'}}
            manifest = Path(temp) / 'manifest.json'
            manifest.write_text(json.dumps(document), encoding='utf-8')
            with patch.object(launcher, 'client_running', return_value=False), \
                 patch.object(ui.lua, 'launcher_module', return_value=launcher):
                backup = Path(temp) / 'backup'
                self.assertTrue(ui.lua.install(prepared, manifest, backup, build=ui.build_files)['passed'])
                self.assertEqual(bytes(pointer), (backup / 'M_LuaV.bytes').read_bytes())
                self.assertTrue(ui.lua.install(prepared, manifest, Path(temp) / 'again',
                                              build=ui.build_files)['alreadyInstalled'])

    def test_preview_is_created_once_and_hidden_when_switching_goods(self):
        original = ui.lua.Bytecode(self.original_detail_page())
        patched = ui.lua.Bytecode(ui.patch_record(self.original_detail_page()))
        old = next(p for p in original.protos if p['name'] == '_load_skin_bg_obj')
        background = next(p for p in patched.protos if p['name'] == '_load_skin_bg_obj')
        self.assertEqual(old['code'], background['code'][-len(old['code']):])
        code = background['code'][:-len(old['code'])]
        constants = background['constants']

        class Rect:
            def __init__(self, game_object=None):
                self.gameObject = game_object
                self.children = {}
            def Find(self, name): return self.children.get(name)
            def SetParent(self, parent, world_position_stays):
                self.parent, self.world_position_stays = parent, world_position_stays
                parent.children[self.gameObject.name] = self

        class Image:
            pass

        rect_type, image_type = object(), object()

        class GameObject:
            created = []
            def __init__(self, name):
                self.name, self.active, self.destroyed = name, True, False
                self.transform = Rect(self)
                self.image = None
                self.created.append(self)
            def AddComponent(self, kind):
                if kind is rect_type: return self.transform
                if kind is image_type:
                    self.image = Image()
                    return self.image
                raise AssertionError(f'unexpected component {kind!r}')
            def SetActive(self, active):
                if self.destroyed: raise RuntimeError('destroyed Unity object')
                self.active = active

        class Panel:
            def __init__(self):
                self._binder = type('Binder', (), {'node_bg': Rect()})()
                self.loads = []
            def _async_load_sprite_to(self, image, path): self.loads.append((image, path))

        panel = Panel()
        imports = {'CS': {'UnityEngine': {'GameObject': GameObject, 'RectTransform': Rect,
                   'Vector2': {'zero': (0, 0), 'one': (1, 1)}, 'UI': {'Image': Image}}},
                   'typeof': lambda value: {Rect: rect_type, Image: image_type}[value]}

        def execute(skin_id):
            panel._skin_id = skin_id
            registers = [None] * 7
            registers[0] = panel
            pc, steps = 0, 0
            def prop(value, name):
                return value[name] if isinstance(value, dict) else getattr(value, name)
            while pc < len(code):
                steps += 1
                self.assertLess(steps, 150)
                word = code[pc]
                op, a, b, c = word & 255, word >> 8 & 255, word >> 16 & 255, word >> 24 & 255
                delta = word >> 16
                if delta >= 0x8000: delta -= 0x10000
                if op == 3:
                    registers[a] = bool(b); pc += 1
                elif op == 5:
                    registers[a] = constants[delta]; pc += 1
                elif op == 6:
                    registers[a] = registers[b]; pc += 1
                elif op == 12:
                    encoded = code[pc + 1]
                    value = imports
                    for shift in (20, 10, 0)[:encoded >> 30]:
                        value = prop(value, constants[(encoded >> shift) & 1023])
                    registers[a] = value; pc += 2
                elif op == 15:
                    registers[a] = prop(registers[b], constants[code[pc + 1]]); pc += 2
                elif op == 16:
                    setattr(registers[b], constants[code[pc + 1]], registers[a]); pc += 2
                elif op == 20:
                    registers[a], registers[a + 1] = (registers[b], constants[code[pc + 1]]), registers[b]
                    pc += 2
                elif op == 21:
                    target, args = registers[a], registers[a + 1:a + b]
                    if isinstance(target, tuple):
                        owner, name = target
                        self.assertIs(args[0], owner)
                        result = getattr(owner, name)(*args[1:])
                    else:
                        result = target(*args)
                    if c == 2: registers[a] = result
                    else: self.assertEqual(1, c)
                    pc += 1
                elif op == 23:
                    pc += 1 + delta
                elif op == 26:
                    pc = pc + 1 + delta if registers[a] is None or registers[a] is False else pc + 1
                elif op == 30:
                    pc = pc + 1 + delta if registers[a] != registers[code[pc + 1]] else pc + 2
                else:
                    self.fail(f'unexpected opcode {op} at {pc}')
            self.assertEqual(len(code), pc)

        execute(1131001)
        self.assertEqual([], GameObject.created)
        execute(1141501)
        preview = panel._binder.node_bg.children['StellarSkinShopPreview'].gameObject
        self.assertEqual(1, len(GameObject.created))
        self.assertTrue(preview.active)
        self.assertIs(panel._binder.node_bg, preview.transform.parent)
        self.assertFalse(preview.transform.world_position_stays)
        self.assertEqual(((0, 0), (1, 1), (0, 0), (0, 0)),
                         (preview.transform.anchorMin, preview.transform.anchorMax,
                          preview.transform.offsetMin, preview.transform.offsetMax))
        self.assertTrue(preview.image.preserveAspect)
        self.assertFalse(preview.image.raycastTarget)
        self.assertEqual([(preview.image, ui.STELLAR_FIGURE)], panel.loads)
        execute(1150101)
        self.assertFalse(preview.active)
        execute(1141501)
        self.assertTrue(preview.active)
        self.assertEqual(1, len(GameObject.created))
        self.assertEqual(1, len(panel.loads))

        # A panel instance may be reused after its old Unity hierarchy was destroyed.
        preview.destroyed = True
        panel._binder = type('Binder', (), {'node_bg': Rect()})()
        execute(1141501)
        replacement = panel._binder.node_bg.children['StellarSkinShopPreview'].gameObject
        self.assertIsNot(preview, replacement)
        self.assertIs(panel._binder.node_bg, replacement.transform.parent)
        self.assertEqual(2, len(panel.loads))

    def test_all_900_original_rows_remain_identical_after_twelve_additions(self):
        chunk = (CLIENT / data.CLIENT_DIR / (data.SOURCE_CHUNK_MD5 + '.bytes')).read_bytes()
        self.assertEqual(data.SOURCE_CHUNK_MD5, hashlib.md5(chunk).hexdigest())
        raw = chunk[16547888:16547888 + 26495]
        self.assertEqual(data.RECORD_SHA, data.sha(raw))
        before = data.decode_table(raw)
        after = data.decode_table(data.patch_record(raw))
        self.assertEqual(900, len(before))
        self.assertEqual(912, len(after))
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

        goods_chunk = (CLIENT / data.CLIENT_DIR / (data.SOURCE_CHUNK_MD5 + '.bytes')).read_bytes()
        goods = data.decode_table(data.patch_record(goods_chunk[16547888:16547888 + 26495]))
        sold_skins = {row['ItemID'] for row, _ in goods if row.get('ShopID') == 107}
        self.assertEqual(set(client), sold_skins)

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
            if left['name'] == '_load_skin_bg_obj':
                self.assertEqual(left['code'], right['code'][-len(left['code']):])
                self.assertEqual(left['constants'], right['constants'][:len(left['constants'])])
            elif left['name'] != 'setup_view':
                self.assertEqual(left['code'], right['code'])
                self.assertEqual(left['constants'], right['constants'])
            else:
                extra = ui.guard((35, 36, 37))
                self.assertEqual(left['code'][:67], right['code'][:67])
                self.assertEqual(left['code'][67:], right['code'][67 + len(extra):])


if __name__ == '__main__':
    unittest.main()
