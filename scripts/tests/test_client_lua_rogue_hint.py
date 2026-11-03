"""执行原始锁定提示函数的有限路径；不运行游戏或加载游戏 DLL。"""
import copy
import importlib.util
import json
from pathlib import Path
import shutil
import tempfile
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location('client_lua_rogue_hint', ROOT / 'scripts/client_lua_rogue_hint.py')
hint = importlib.util.module_from_spec(spec)
spec.loader.exec_module(hint)
CLIENT = Path('C:/Users/InvokerBot/AppData/Local/hoyo/hkrpg/versions/current')
SOURCE = CLIENT / hint.lua.LUA / 'e0c070e248e602899e95ee9d618cfb29.bytes'


def original_record():
    index = (CLIENT / hint.lua.LUA / 'LuaV_ae52631809b84671e2acc54e537ba182.bytes').read_bytes()
    for chunk in hint.lua.read_index(index):
        for entry in chunk['entries']:
            if entry['key'] == hint.RECORD_KEY:
                path = CLIENT / hint.lua.LUA / chunk['locale'] / (chunk['digest'] + '.bytes')
                with path.open('rb') as stream:
                    stream.seek(entry['offset'])
                    raw = stream.read(entry['length'])
                if hint.lua.sha(raw) != hint.PAGE_SHA:
                    raise AssertionError('本机选世界详情页原件变化')
                return raw
    raise AssertionError('原始选世界详情页缺失')


class HintMachine:
    """只解释提示函数的取值、调用和分支；UI 接收器记录实际参数。"""
    def __init__(self, raw, unlock=12001, row=None):
        self.decoded = hint.lua.Bytecode(raw)
        self.calls = []
        self.row = {'RogueUnlockID': unlock} if row is None else row
        self.text = {'SafeSetTextID': self.text_id, 'SafeSetText': self.set_text}
        self.actor = {'_rogue_area_data': {'RogueAreaRow': {'UnlockID': unlock}},
                      '_binder': {'node_lock_hint': {'SafeSetActive': self.active},
                                  'text_lock_tip': self.text}}
        self.globals = {'CS': {'RPG': {'GameCore': {'RogueUnlockConfigExcelTable': {'GetData': self.lookup}}}}}

    def lookup(self, identity):
        self.calls.append(('lookup', identity))
        return self.row

    def active(self, actor, value):
        self.calls.append(('active', value))

    def text_id(self, actor, value):
        self.calls.append(('text_id', value))

    def set_text(self, actor, value):
        self.calls.append(('text', value))

    def run(self):
        p = next(p for p in self.decoded.protos if p['name'] == '_setup_locked_btns')
        regs, constants, code = [None] * p['header'][0], p['constants'], p['code']
        regs[0] = self.actor
        pc = 0
        while pc < len(code):
            word = code[pc]
            op, a, b, c = word & 255, (word >> 8) & 255, (word >> 16) & 255, word >> 24
            displacement = word >> 16
            if displacement >= 32768:
                displacement -= 65536
            pc += 1
            if op == 3:
                regs[a] = bool(b)
            elif op == 4:
                regs[a] = displacement
            elif op == 5:
                regs[a] = constants[word >> 16]
            elif op == 12:
                aux = code[pc]; pc += 1
                value = self.globals
                for n in range(aux >> 30):
                    value = value[constants[(aux >> (20 - n * 10)) & 1023]]
                regs[a] = value
            elif op in (15, 20):
                key = constants[code[pc]]; pc += 1
                if op == 15:
                    regs[a] = regs[b].get(key)
                else:
                    regs[a], regs[a + 1] = regs[b][key], regs[b]
            elif op == 21:
                value = regs[a](*regs[a + 1:a + b])
                if c == 2:
                    regs[a] = value
                elif c != 1:
                    raise AssertionError('未知调用返回数量')
            elif op == 22:
                return
            elif op == 30:
                target = code[pc]
                pc += displacement if regs[a] != regs[target] else 1
            elif op == 81:
                aux = code[pc]
                take = (regs[a] is None) != bool(aux & 0x80000000)
                pc += displacement if take else 1
            else:
                raise AssertionError('未核对的提示指令: ' + str(op))
        raise AssertionError('提示函数没有返回')


@unittest.skipUnless(SOURCE.is_file(), '没有固定本机 4.6 Lua 原件')
class FirstWorldUnlockHintTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.original = original_record()

    def test_original_missing_description_reproduces_red_background_with_empty_text(self):
        machine = HintMachine(self.original)
        machine.run()
        self.assertEqual([('active', True), ('lookup', 12001), ('text_id', None)], machine.calls)

    def test_first_world_displays_all_three_prerequisite_names(self):
        machine = HintMachine(hint.patch_record(self.original))
        before = copy.deepcopy(machine.actor['_rogue_area_data'])
        machine.run()
        self.assertIn(('text', '需完成「始发测试」「漫游测试」「她等待刀尖已经太久」'), machine.calls)
        self.assertEqual(before, machine.actor['_rogue_area_data'])
        self.assertEqual([('active', True), ('text', hint.HINT)], machine.calls)

    def test_second_world_keeps_its_original_localized_description(self):
        text_id = {'Hash': 17652991673663792738}
        row = {'RogueUnlockID': 12002, 'RogueUnlockDetail': text_id}
        machine = HintMachine(hint.patch_record(self.original), 12002, row)
        machine.run()
        self.assertEqual([('active', True), ('lookup', 12002), ('text_id', text_id)], machine.calls)

    def test_other_unlock_ids_keep_the_original_path_even_when_description_is_missing(self):
        for identity in (0, 11, 12000, 12002, 12099):
            with self.subTest(identity=identity):
                machine = HintMachine(hint.patch_record(self.original), identity)
                machine.run()
                self.assertEqual([('active', True), ('lookup', identity), ('text_id', None)], machine.calls)

    def test_hint_names_match_the_original_three_required_missions(self):
        excel = ROOT / 'resources/4.6/ExcelOutput'
        unlocks = {r['RogueUnlockID']: r for r in json.loads((excel / 'RogueUnlockConfig.json').read_text('utf-8'))}
        rules = {r['ID']: r for r in json.loads((excel / 'FinishWayRogue.json').read_text('utf-8'))}
        first_world = rules[unlocks[12001]['UnlockFinishWay']]
        self.assertEqual('RogueFinishUnlock', first_world['FinishType'])
        self.assertEqual([11, 11007], first_world['ParamIntList'])
        self.assertEqual(2, first_world['Progress'])
        required = []
        for identity in first_world['ParamIntList']:
            condition = rules[unlocks[identity]['UnlockFinishWay']]
            self.assertEqual('FinishMission', condition['FinishType'])
            self.assertEqual(len(condition['ParamIntList']), condition['Progress'])
            required += condition['ParamIntList']
        self.assertEqual([4030001, 4030002, 1010403], required)
        self.assertNotIn('RogueUnlockDetail', unlocks[12001])
        missions = {r['MainMissionID']: r for r in json.loads((excel / 'MainMission.json').read_text('utf-8'))}
        text = json.loads((ROOT / 'resources/4.6/TextMap/TextMapCHS.json').read_text('utf-8'))
        for identity in required:
            name = text[str(missions[identity]['Name']['Hash'])].split('•')[-1]
            self.assertIn('「' + name + '」', hint.HINT)

    def test_only_locked_text_function_changes_and_unlock_button_logic_is_untouched(self):
        raw = hint.patch_record(self.original)
        before, after = hint.lua.Bytecode(self.original), hint.lua.Bytecode(raw)
        changed = [a['name'] for a, b in zip(before.protos, after.protos)
                   if self.original[a['start']:a['end']] != raw[b['start']:b['end']]]
        self.assertEqual(['_setup_locked_btns'], changed)
        self.assertEqual(before.main, after.main)
        self.assertEqual(len(before.protos), len(after.protos))
        self.assertEqual(before.strings, after.strings[:len(before.strings)])
        old = next(p for p in before.protos if p['name'] == '_setup_locked_btns')
        new = next(p for p in after.protos if p['name'] == '_setup_locked_btns')
        self.assertEqual(old['code'][:8], new['code'][:8])
        self.assertEqual(old['code'][8:], new['code'][26:])
        self.assertEqual(old['lines'][8:], new['lines'][26:])
        self.assertEqual(len(new['code']), len(new['lines']))

    def test_changed_original_is_rejected(self):
        for raw in (self.original[:-1], self.original[:10] + b'x' + self.original[11:]):
            with self.assertRaisesRegex(ValueError, '不是已核对原件'):
                hint.patch_record(raw)


def isolated_client(target):
    source = CLIENT / hint.lua.LUA
    index_name = 'LuaV_ae52631809b84671e2acc54e537ba182.bytes'
    index = (source / index_name).read_bytes()
    if hint.lua.sha(index) != hint.lua.INDEX_SHA:
        raise AssertionError('原始 Lua 索引变化')
    target.mkdir(parents=True)
    pointer = bytearray((source / 'M_LuaV.bytes').read_bytes())
    digest = bytes.fromhex('ae52631809b84671e2acc54e537ba182')
    pointer[28:44] = b''.join(digest[n:n + 4][::-1] for n in range(0, 16, 4))
    (target / 'M_LuaV.bytes').write_bytes(pointer)
    (target / index_name).write_bytes(index)
    for chunk in hint.lua.read_index(index):
        name = Path(chunk['locale']) / (chunk['digest'] + '.bytes')
        (target / name).parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(source / name, target / name)


def records(client, files=None):
    files = files or {}
    def read(name):
        return files[name] if name in files else (client / hint.lua.LUA / name).read_bytes()
    digest = hint.lua.pointer_digest(read('M_LuaV.bytes'))
    index = read('LuaV_' + digest + '.bytes')
    result = {}
    for chunk in hint.lua.read_index(index):
        data = read(str(Path(chunk['locale']) / (chunk['digest'] + '.bytes')))
        if len(data) != chunk['size'] or hint.lua.hashlib.md5(data).hexdigest() != chunk['digest']:
            raise AssertionError('打包分块与索引不一致')
        for entry in chunk['entries']:
            raw = data[entry['offset']:entry['offset'] + entry['length']]
            result[entry['key']] = raw
    return result


@unittest.skipUnless(SOURCE.is_file(), '没有固定本机 4.6 Lua 原件')
class FirstWorldHintPackageTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.client = self.root / 'client'
        isolated_client(self.client / hint.lua.LUA)

    def apply_files(self, files):
        for name, raw in files.items():
            (self.client / hint.lua.LUA / name).write_bytes(raw)

    def test_both_source_profiles_preserve_all_other_lua_records_and_existing_map_fix(self):
        for map_fixed in (False, True):
            with self.subTest(map_fixed=map_fixed):
                if map_fixed:
                    _, files, _ = hint.lua.build_files(self.client)
                    self.apply_files(files)
                before = records(self.client)
                pointer = (self.client / hint.lua.LUA / 'M_LuaV.bytes').read_bytes()
                report, files, _ = hint.build_files(self.client)
                after = records(self.client, files)
                self.assertEqual(before.keys(), after.keys())
                self.assertEqual(19644, len(after))
                changed = {key for key in before if before[key] != after[key]}
                manifest_key = next(key for key, raw in before.items() if b'"Path":' in raw and b'"Md5":' in raw)
                self.assertEqual({manifest_key, hint.RECORD_KEY}, changed)
                self.assertEqual(hint.patch_record(before[hint.RECORD_KEY]), after[hint.RECORD_KEY])
                manifest = [json.loads(line) for line in after[manifest_key].splitlines()]
                entry = next(row for row in manifest if row['Path'] == hint.PAGE)
                self.assertEqual(hint.lua.hashlib.md5(after[hint.RECORD_KEY]).hexdigest(), entry['Md5'])
                self.assertEqual(pointer, (self.client / hint.lua.LUA / 'M_LuaV.bytes').read_bytes())
                self.assertEqual(4, len(report['files']))
                self.assertFalse(report['clientModified'])

    def installation_fixture(self):
        map_report, map_files, _ = hint.lua.build_files(self.client)
        self.apply_files(map_files)
        launcher = hint.lua.launcher_module()
        client_files = {}
        for name in launcher.REQUIRED_FILES:
            path = self.client / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(name.encode('utf-8'))
            client_files[name] = launcher.sha(path)
        document = dict(schema=2, clientRoot=str(self.client), clientFiles=client_files,
                        clientLuaFiles=map_report['files'],
                        debugConsole=dict(mode='external', sha256='0' * 64, backupPath='console.dll'),
                        service=dict(readyUrl='http://127.0.0.1:18080/health/ready', startCommand='fixture'))
        manifest = self.root / 'patch-manifest.json'
        manifest.write_text(json.dumps(document), 'utf-8')
        prepared = self.root / 'prepared'
        report = hint.lua.prepare(self.client, prepared, build=hint.build_files)
        return launcher, manifest, prepared, report

    def test_install_composes_with_map_patch_and_existing_launcher_then_is_idempotent(self):
        launcher, manifest, prepared, report = self.installation_fixture()
        old_manifest = manifest.read_bytes()
        pointer = self.client / hint.lua.LUA / 'M_LuaV.bytes'
        old_pointer = pointer.read_bytes()
        backup = self.root / 'backup'
        with patch.object(hint.lua, 'launcher_module', return_value=launcher), \
                patch.object(launcher, 'client_running', return_value=False):
            result = hint.lua.install(prepared, manifest, backup, build=hint.build_files)
            self.assertTrue(result['passed'])
            self.assertEqual(old_pointer, (backup / 'M_LuaV.bytes').read_bytes())
            self.assertEqual(old_manifest, (backup / 'patch-manifest.json').read_bytes())
            self.assertTrue(hint.lua.install(prepared, manifest, backup, build=hint.build_files)['alreadyInstalled'])
            with patch.object(launcher, 'require_ready'), patch.object(launcher, 'launch_client') as launch:
                self.assertEqual(4, launcher.start(manifest, dry_run=True)['clientLuaFilesVerified'])
                launch.assert_not_called()
        self.assertEqual(report['files'], json.loads(manifest.read_text('utf-8'))['clientLuaFiles'])

    def test_install_failure_restores_map_pointer_and_manifest_and_running_client_is_rejected(self):
        launcher, manifest, prepared, _ = self.installation_fixture()
        pointer = self.client / hint.lua.LUA / 'M_LuaV.bytes'
        old_pointer, old_manifest = pointer.read_bytes(), manifest.read_bytes()
        backup = self.root / 'backup'
        with patch.object(hint.lua, 'launcher_module', return_value=launcher):
            with patch.object(launcher, 'client_running', return_value=True):
                with self.assertRaisesRegex(RuntimeError, '客户端正在运行'):
                    hint.lua.install(prepared, manifest, backup, build=hint.build_files)
            self.assertFalse(backup.exists())
            atomic = hint.lua.atomic
            def fail_manifest(path, raw):
                if path == manifest:
                    raise OSError('isolated hint manifest failure')
                atomic(path, raw)
            with patch.object(launcher, 'client_running', return_value=False), \
                    patch.object(hint.lua, 'atomic', side_effect=fail_manifest):
                with self.assertRaisesRegex(OSError, 'isolated hint manifest failure'):
                    hint.lua.install(prepared, manifest, backup, build=hint.build_files)
        self.assertEqual(old_pointer, pointer.read_bytes())
        self.assertEqual(old_manifest, manifest.read_bytes())


if __name__ == '__main__':
    unittest.main()
