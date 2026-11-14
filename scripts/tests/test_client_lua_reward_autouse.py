"""核对奖励窗口原始自动使用检查和物品禁用分支；不启动或操作客户端。"""
import json
import hashlib
from pathlib import Path
import shutil
import sys
import tempfile
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'scripts'))
import client_lua_reward_autouse as reward

MANIFEST = ROOT / '.local/client/patch-manifest.json'


def source_records():
    client = Path(json.loads(MANIFEST.read_text('utf-8-sig'))['clientRoot'])
    directory = client / reward.lua.LUA
    index = (directory / reward.INDEX_NAME).read_bytes()
    if reward.lua.sha(index) != reward.INDEX_SHA:
        raise AssertionError('固定来源索引发生变化')
    wanted = {value[0] for value in reward.PAGES.values()} | {reward.UTILITY_KEY}
    found = {}
    for chunk in reward.lua.read_index(index):
        matching = [entry for entry in chunk['entries'] if entry['key'] in wanted]
        if matching:
            data = (directory / chunk['locale'] / (chunk['digest'] + '.bytes')).read_bytes()
            if len(data) != chunk['size'] or hashlib.md5(data).hexdigest() != chunk['digest']:
                raise AssertionError('原始分块摘要不符')
            for entry in matching:
                found[entry['key']] = data[entry['offset']:entry['offset'] + entry['length']]
    if set(found) != wanted:
        raise AssertionError('原始测试资料缺失')
    return found


def auto_use_path(raw, item, banned):
    """解释一次原生循环的取物、自动使用和禁用检查，停在后续礼盒判断之前。"""
    bytecode = reward.lua.Bytecode(raw)
    proto = next(p for p in bytecode.protos if p['name'] == '_trigger_autouse_item')
    code, constants, pc = proto['code'], proto['constants'], 6
    registers = [None] * proto['header'][0]
    registers[5] = {'ConfigID': 190001}
    calls = []
    inventory = {'GetItemDataByConfigID': lambda _this, _id: item}
    utilities = {'is_item_banned': lambda actual: calls.append(actual) or banned}
    globals_ = {'G': {'InventoryUtils': utilities}}
    while pc < len(code):
        if pc == 21:
            return 'eligible', calls
        if pc == 49:
            return 'skipped', calls
        word = code[pc]
        op, a, b, c = word & 255, (word >> 8) & 255, (word >> 16) & 255, word >> 24
        displacement = word >> 16
        if displacement >= 32768:
            displacement -= 65536
        pc += 1
        if op == 6:
            registers[a] = registers[b]
        elif op == 9:
            registers[a] = inventory
        elif op == 12:
            aux = code[pc]; pc += 1
            value = globals_
            for n in range(aux >> 30):
                value = value.get(constants[(aux >> (20 - n * 10)) & 1023])
                if value is None:
                    break
            registers[a] = value
        elif op == 15:
            key = constants[code[pc]]; pc += 1
            registers[a] = registers[b].get(key)
        elif op == 20:
            key = constants[code[pc]]; pc += 1
            registers[a], registers[a + 1] = registers[b][key], registers[b]
        elif op == 21:
            if not callable(registers[a]):
                raise TypeError('attempt to call a nil value')
            value = registers[a](*registers[a + 1:a + b])
            if c == 2:
                registers[a] = value
            elif c != 1:
                raise AssertionError('未知返回数量')
        elif op in (25, 26):
            truth = registers[a] is not None and registers[a] is not False
            if truth == (op == 25):
                pc += displacement
        else:
            raise AssertionError('未核对的原生指令 ' + str(op))
    raise AssertionError('未抵达原有分支')


@unittest.skipUnless(MANIFEST.is_file(), '没有本机客户端原始资源')
class RewardAutouseTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.records = source_records()

    def test_original_auto_use_reproduces_nil_call_on_all_three_windows(self):
        for page, (key, _, _) in reward.PAGES.items():
            with self.subTest(page=page), self.assertRaisesRegex(TypeError, 'nil value'):
                auto_use_path(self.records[key], {'IsAutoUse': True}, False)

    def test_existing_utility_returns_banned_and_is_checked_by_ordinary_use(self):
        utility = reward.lua.Bytecode(self.records[reward.UTILITY_KEY])
        self.assertEqual(reward.UTILITY_SHA, reward.lua.sha(self.records[reward.UTILITY_KEY]))
        banned = next(p for p in utility.protos if p['name'] == 'is_item_banned')
        self.assertEqual(1, banned['header'][1])
        self.assertIn('IsFeaturePartialClosed', banned['constants'])
        main = utility.protos[utility.main]
        name_index = main['constants'].index('is_item_banned')
        exports = [i for i in range(len(main['code']) - 1)
                   if main['code'][i] & 255 == 16 and main['code'][i + 1] == name_index]
        self.assertEqual(1, len(exports))
        closure_word = main['code'][exports[0] - 1]
        self.assertEqual(68, closure_word & 255)
        closure = main['constants'][closure_word >> 16]['closure']
        self.assertIs(banned, utility.protos[main['children'][closure]])
        check = next(p for p in utility.protos if p['name'] == 'check_item_use')
        self.assertIn('is_item_banned', check['constants'])
        self.assertEqual(26, check['code'][18] & 255)  # false skips the refusal block
        self.assertEqual(3, check['code'][33] & 255)   # true returns false
        self.assertNotIn('check_item_ban', utility.strings)

    def test_auto_use_continues_only_when_item_is_not_banned(self):
        for page, (key, _, _) in reward.PAGES.items():
            patched = reward.patch_record(self.records[key], page)
            item = {'IsAutoUse': True}
            with self.subTest(page=page, banned=False):
                self.assertEqual(('eligible', [item]), auto_use_path(patched, item, False))
            with self.subTest(page=page, banned=True):
                self.assertEqual(('skipped', [item]), auto_use_path(patched, item, True))

    def test_non_auto_or_unknown_items_do_not_call_ban_check(self):
        for page, (key, _, _) in reward.PAGES.items():
            patched = reward.patch_record(self.records[key], page)
            for item in (None, {'IsAutoUse': False}):
                with self.subTest(page=page, item=item):
                    self.assertEqual(('skipped', []), auto_use_path(patched, item, False))

    def test_only_named_check_and_branch_change_in_each_page(self):
        for page, (key, _, _) in reward.PAGES.items():
            raw = self.records[key]
            patched = reward.patch_record(raw, page)
            before, after = reward.lua.Bytecode(raw), reward.lua.Bytecode(patched)
            self.assertEqual(before.main, after.main)
            self.assertEqual(len(before.protos), len(after.protos))
            for a, b in zip(before.protos, after.protos):
                if a['name'] != '_trigger_autouse_item':
                    self.assertEqual(raw[a['start']:a['end']], patched[b['start']:b['end']])
                    continue
                self.assertEqual('check_item_ban', a['constants'][8])
                self.assertEqual('is_item_banned', b['constants'][8])
                self.assertEqual(a['code'][:20], b['code'][:20])
                self.assertEqual(26, a['code'][20] & 255)
                self.assertEqual(25, b['code'][20] & 255)
                self.assertEqual(a['code'][21:], b['code'][21:])
                self.assertEqual(a['lines'], b['lines'])

    def test_rejects_unknown_or_changed_original(self):
        for page, (key, _, _) in reward.PAGES.items():
            with self.subTest(page=page), self.assertRaises(ValueError):
                reward.patch_record(self.records[key] + b'\0', page)
        with self.assertRaises(ValueError):
            reward.patch_record(next(iter(self.records.values())), 'unknown')


def isolated_client(target):
    client = Path(json.loads(MANIFEST.read_text('utf-8-sig'))['clientRoot'])
    source = client / reward.lua.LUA
    index = (source / reward.INDEX_NAME).read_bytes()
    if reward.lua.sha(index) != reward.INDEX_SHA:
        raise AssertionError('固定来源索引变化')
    target.mkdir(parents=True)
    pointer = bytearray((source / 'M_LuaV.bytes').read_bytes())
    digest = bytes.fromhex(reward.INDEX_NAME[5:-6])
    pointer[28:44] = b''.join(digest[n:n + 4][::-1] for n in range(0, 16, 4))
    (target / 'M_LuaV.bytes').write_bytes(pointer)
    (target / reward.INDEX_NAME).write_bytes(index)
    for chunk in reward.lua.read_index(index):
        name = Path(chunk['locale']) / (chunk['digest'] + '.bytes')
        (target / name).parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(source / name, target / name)


def records(client, files=None):
    files = files or {}
    directory = client / reward.lua.LUA
    def read(name):
        return files[name] if name in files else (directory / name).read_bytes()
    digest = reward.lua.pointer_digest(read('M_LuaV.bytes'))
    index = read('LuaV_' + digest + '.bytes')
    if hashlib.md5(index).hexdigest() != digest:
        raise AssertionError('索引摘要不符')
    result = {}
    for chunk in reward.lua.read_index(index):
        data = read(str(Path(chunk['locale']) / (chunk['digest'] + '.bytes')))
        if len(data) != chunk['size'] or hashlib.md5(data).hexdigest() != chunk['digest']:
            raise AssertionError('分块摘要不符')
        for entry in chunk['entries']:
            if entry['key'] in result:
                raise AssertionError('记录键重复')
            result[entry['key']] = data[entry['offset']:entry['offset'] + entry['length']]
    return result


@unittest.skipUnless(MANIFEST.is_file(), '没有本机客户端原始资源')
class RewardAutousePackageTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.client = self.root / 'client'
        isolated_client(self.client / reward.lua.LUA)

    def test_package_preserves_every_other_record_and_existing_compatibility(self):
        before = records(self.client)
        pointer = (self.client / reward.lua.LUA / 'M_LuaV.bytes').read_bytes()
        report, files, _ = reward.build_files(self.client)
        after = records(self.client, files)
        self.assertEqual(before.keys(), after.keys())
        self.assertEqual(19644, len(after))
        manifest_key = reward.lua.read_index((self.client / reward.lua.LUA / reward.INDEX_NAME).read_bytes())[0]['entries'][0]['key']
        old_manifest = [json.loads(row) for row in before[manifest_key].splitlines()]
        names = {row['Md5']: row['Path'] for row in old_manifest}
        callers = set()
        for value in before.values():
            if b'check_item_ban' in value:
                decoded = reward.lua.Bytecode(value)
                if any('check_item_ban' in p['constants'] for p in decoded.protos):
                    callers.add(names[hashlib.md5(value).hexdigest()])
        self.assertEqual(set(reward.PAGES), callers)
        changed = {key for key in before if before[key] != after[key]}
        self.assertEqual({manifest_key} | {p[0] for p in reward.PAGES.values()}, changed)
        manifest = [json.loads(row) for row in after[manifest_key].splitlines()]
        for page, (key, _, _) in reward.PAGES.items():
            self.assertEqual(reward.patch_record(before[key], page), after[key])
            entry = next(row for row in manifest if row['Path'] == page)
            self.assertEqual(hashlib.md5(after[key]).hexdigest(), entry['Md5'])
        self.assertEqual(pointer, (self.client / reward.lua.LUA / 'M_LuaV.bytes').read_bytes())
        self.assertEqual(4, len(report['files']))
        self.assertFalse(report['clientModified'])

    def test_install_requires_stopped_client_and_preserves_existing_files(self):
        launcher = reward.lua.launcher_module()
        client_files = {}
        for name in launcher.REQUIRED_FILES:
            path = self.client / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(name.encode('utf-8'))
            client_files[name] = launcher.sha(path)
        document = dict(schema=2, clientRoot=str(self.client), clientFiles=client_files,
                        debugConsole=dict(mode='external', sha256='0' * 64, backupPath='console.dll'),
                        service=dict(readyUrl='http://127.0.0.1:18080/health/ready', startCommand='fixture'))
        manifest = self.root / 'patch-manifest.json'
        manifest.write_text(json.dumps(document), 'utf-8')
        old_manifest = manifest.read_bytes()
        pointer = self.client / reward.lua.LUA / 'M_LuaV.bytes'
        old_pointer = pointer.read_bytes()
        prepared = self.root / 'prepared'
        report = reward.lua.prepare(self.client, prepared, build=reward.build_files)
        backup = self.root / 'backup'
        with patch.object(reward.lua, 'launcher_module', return_value=launcher):
            with patch.object(launcher, 'client_running', return_value=True):
                with self.assertRaisesRegex(RuntimeError, '客户端正在运行'):
                    reward.lua.install(prepared, manifest, backup, build=reward.build_files)
            self.assertEqual(old_pointer, pointer.read_bytes())
            self.assertEqual(old_manifest, manifest.read_bytes())
            self.assertFalse(backup.exists())

            relative = next(name for name in report['files'] if not name.endswith('M_LuaV.bytes'))
            candidate = prepared / Path(relative).name
            original_candidate = candidate.read_bytes()
            candidate.write_bytes(original_candidate + b'forged')
            forged = json.loads((prepared / 'prepared.json').read_text('utf-8'))
            forged['files'][relative] = reward.lua.sha(candidate.read_bytes())
            (prepared / 'prepared.json').write_text(json.dumps(forged), 'utf-8')
            with patch.object(launcher, 'client_running', return_value=False):
                with self.assertRaisesRegex(ValueError, '重新计算'):
                    reward.lua.install(prepared, manifest, backup, build=reward.build_files)
                self.assertEqual(old_pointer, pointer.read_bytes())
                self.assertEqual(old_manifest, manifest.read_bytes())
                candidate.write_bytes(original_candidate)
                (prepared / 'prepared.json').write_text(json.dumps(report), 'utf-8')
                original_atomic = reward.lua.atomic
                def fail_manifest(path, raw):
                    if path == manifest:
                        raise OSError('isolated manifest write failure')
                    original_atomic(path, raw)
                with patch.object(reward.lua, 'atomic', side_effect=fail_manifest):
                    with self.assertRaisesRegex(OSError, 'manifest write failure'):
                        reward.lua.install(prepared, manifest, self.root / 'failed-backup', build=reward.build_files)
                self.assertEqual(old_pointer, pointer.read_bytes())
                self.assertEqual(old_manifest, manifest.read_bytes())
                result = reward.lua.install(prepared, manifest, backup, build=reward.build_files)
                self.assertTrue(result['passed'])
                self.assertEqual(old_pointer, (backup / 'M_LuaV.bytes').read_bytes())
                self.assertEqual(old_manifest, (backup / 'patch-manifest.json').read_bytes())
                self.assertEqual(report['files'], json.loads(manifest.read_text('utf-8'))['clientLuaFiles'])
                self.assertTrue(reward.lua.install(prepared, manifest, backup, build=reward.build_files)['alreadyInstalled'])


if __name__ == '__main__':
    unittest.main()
