"""执行实际地图 init 的有限参数路径；不加载游戏 DLL，不启动或操作客户端。"""
import importlib.util
import json
from pathlib import Path
import shutil
import tempfile
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location('client_lua_map_compat', ROOT / 'scripts/client_lua_map_compat.py')
compat = importlib.util.module_from_spec(spec)
spec.loader.exec_module(compat)
CLIENT = Path('C:/Users/InvokerBot/AppData/Local/hoyo/hkrpg/versions/current')
SOURCE = CLIENT / compat.LUA / 'e0c070e248e602899e95ee9d618cfb29.bytes'


def original_tree(target):
    """只复制原始资源到测试目录；已安装补丁也不影响回归输入。"""
    source = CLIENT / compat.LUA
    index_name = 'LuaV_ae52631809b84671e2acc54e537ba182.bytes'
    index = (source / index_name).read_bytes()
    if compat.sha(index) != compat.INDEX_SHA:
        raise AssertionError('Original index changed')
    target.mkdir(parents=True)
    pointer = bytearray((source / 'M_LuaV.bytes').read_bytes())
    digest = bytes.fromhex('ae52631809b84671e2acc54e537ba182')
    pointer[28:44] = b''.join(digest[n:n + 4][::-1] for n in range(0, 16, 4))
    (target / 'M_LuaV.bytes').write_bytes(pointer)
    (target / index_name).write_bytes(index)
    for chunk in compat.read_index(index):
        name = Path(chunk['locale']) / (chunk['digest'] + '.bytes')
        (target / name).parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(source / name, target / name)


class ParameterMachine:
    """仅解释 init 参数路径中明确使用的原始指令；地图数据和 UI 方法用观察桩。"""
    def __init__(self, bytecode):
        self.bytecode, self.allocations, self.seen = bytecode, [], []
        self.defaults = dict(story_line_id=0, content_id=0, default_early_access=False,
                             need_switch_online_status=False)
        self.globals = {'G': {'new': self.create}}
        self.actor = {'_context': {'init': lambda this, story, content: self.seen.append(('context', story, content))},
                      '_init_data': self.init_data,
                      '_init_map_entry_info': lambda this, *values: self.seen.append(('details', *values))}
        self.upvalues = [{'GetPlayerLocatedSubTab': lambda this: 'original-sub-tab'}]

    def create(self, name):
        if name != 'Ui.Map.SwitchAreaPageInitParam':
            raise AssertionError('Unexpected constructor')
        result = dict(self.defaults)
        self.allocations.append(result)
        return result

    def init_data(self, actor, parameter):
        # 原始 _init_data 的前九个字：包括出错的两个 GETTABLEKS 和 context:init。
        proto = next(p for p in self.bytecode.protos if p['name'] == '_init_data')
        self.execute(proto, [actor, parameter], limit=9)

    def execute(self, proto, arguments, limit=None):
        registers = [None] * proto['header'][0]
        registers[:len(arguments)] = arguments
        code, constants, pc = proto['code'], proto['constants'], 0
        while pc < (len(code) if limit is None else limit):
            word = code[pc]
            op, a, b, c = word & 255, (word >> 8) & 255, (word >> 16) & 255, word >> 24
            pc += 1
            if op == 3: registers[a] = bool(b)
            elif op == 5: registers[a] = constants[word >> 16]
            elif op == 6: registers[a] = registers[b]
            elif op == 9: registers[a] = self.upvalues[b]
            elif op == 12:
                aux = code[pc]; pc += 1
                count = aux >> 30
                value = self.globals
                for n in range(count):
                    value = value[constants[(aux >> (20 - n * 10)) & 1023]]
                registers[a] = value
            elif op in (15, 16, 20):
                key = constants[code[pc]]; pc += 1
                if op == 15:
                    if registers[b] is None:
                        raise TypeError("nil parameter: " + key)
                    registers[a] = registers[b].get(key)
                elif op == 16: registers[b][key] = registers[a]
                else: registers[a], registers[a + 1] = registers[b][key], registers[b]
            elif op == 21:
                value = registers[a](*registers[a + 1:a + b])
                if c == 2: registers[a] = value
                elif c != 1: raise AssertionError('Unexpected return arity')
            elif op == 22: return
            elif op == 25:
                displacement = word >> 16
                if displacement >= 32768: displacement -= 65536
                if registers[a] is not None and registers[a] is not False:
                    pc += displacement
            else: raise AssertionError('Unverified parameter-path opcode: ' + str(op))

    def run(self, parameter):
        proto = next(p for p in self.bytecode.protos if p['name'] == 'init')
        self.execute(proto, [self.actor, parameter])


@unittest.skipUnless(SOURCE.is_file(), '没有已核对的本机 4.6 Lua 原件')
class ClientMapInitCompatibilityTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        with SOURCE.open('rb') as stream:
            stream.seek(29923110)
            cls.original = stream.read(23990)
        if compat.sha(cls.original) != compat.PAGE_SHA:
            raise AssertionError('本机 Lua 原件改变，需要重新核对')

    def test_original_rift_call_reproduces_the_actual_nil_story_line_failure(self):
        machine = ParameterMachine(compat.Bytecode(self.original))
        with self.assertRaisesRegex(TypeError, 'nil parameter: story_line_id'):
            machine.run(None)

    def test_native_no_argument_entry_gets_one_real_default_parameter(self):
        machine = ParameterMachine(compat.Bytecode(compat.patch_record(self.original)))
        machine.run(None)
        self.assertEqual(1, len(machine.allocations))
        self.assertIn(('context', 0, 0), machine.seen)
        self.assertIn(('details', None, None, None, None), machine.seen)

    def test_explicit_story_and_content_parameters_are_preserved_without_allocation(self):
        machine = ParameterMachine(compat.Bytecode(compat.patch_record(self.original)))
        parameter = dict(story_line_id=2022304, content_id=17, mapping_info_id=11,
                         sub_mission_id=19, entity_pair=object(), sub_map_id=23)
        before = dict(parameter)
        machine.run(parameter)
        self.assertEqual([], machine.allocations)
        self.assertEqual(before, parameter)
        self.assertIn(('context', 2022304, 17), machine.seen)
        self.assertIn(('details', 11, 19, parameter['entity_pair'], 23), machine.seen)

    def test_only_init_changes_and_existing_prototypes_remain_byte_identical(self):
        original = compat.Bytecode(self.original)
        raw = compat.patch_record(self.original)
        patched = compat.Bytecode(raw)
        self.assertEqual(original.main, patched.main)
        self.assertEqual(len(original.protos), len(patched.protos))
        self.assertEqual(original.strings, patched.strings[:len(original.strings)])
        changed = []
        for a, b in zip(original.protos, patched.protos):
            if self.original[a['start']:a['end']] != raw[b['start']:b['end']]:
                changed.append(a['name'])
        self.assertEqual(['init'], changed)
        init = next(p for p in patched.protos if p['name'] == 'init')
        old = next(p for p in original.protos if p['name'] == 'init')
        self.assertEqual(old['code'], init['code'][6:])
        self.assertEqual(len(init['code']), len(init['lines']))

    def test_changed_or_truncated_page_is_rejected(self):
        for raw in (self.original[:-1], self.original[:10] + b'x' + self.original[11:]):
            with self.assertRaises(ValueError):
                compat.patch_record(raw)

    def test_packed_index_manifest_and_both_records_agree(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / 'prepared'
            client = Path(directory) / 'client'
            original_tree(client / compat.LUA)
            report = compat.prepare(client, output)
            self.assertFalse(report['clientModified'])
            self.assertFalse(report['clientExecuted'])
            self.assertNotEqual(compat.PAGE_SHA, report['patchedPageSHA256'])
            pointer = (output / 'M_LuaV.bytes').read_bytes()
            digest = compat.pointer_digest(pointer)
            index = (output / ('LuaV_' + digest + '.bytes')).read_bytes()
            self.assertEqual(digest, compat.hashlib.md5(index).hexdigest())
            chunks = compat.read_index(index)
            modules, page_raw, parameter = None, None, None
            for i, chunk in enumerate(chunks):
                name = chunk['digest'] + '.bytes'
                path = output / name
                raw = path.read_bytes() if path.exists() else (client / compat.LUA / chunk['locale'] / name).read_bytes()
                self.assertEqual(chunk['digest'], compat.hashlib.md5(raw).hexdigest())
                self.assertEqual(chunk['size'], len(raw))
                for n, entry in enumerate(chunk['entries']):
                    value = raw[entry['offset']:entry['offset'] + entry['length']]
                    if i == n == 0: modules = [json.loads(s) for s in value.splitlines()]
                    if entry['key'] == '23248f34a56ef961': page_raw = value
                    if entry['key'] == '56b1302a26f789a2': parameter = value
            self.assertEqual(compat.patch_record(self.original), page_raw)
            self.assertEqual(compat.PARAM_SHA, compat.sha(parameter))
            row = next(x for x in modules if x['Path'] == compat.PAGE)
            self.assertEqual(compat.hashlib.md5(page_raw).hexdigest(), row['Md5'])


@unittest.skipUnless(SOURCE.is_file(), '没有已核对的本机 4.6 Lua 原件')
class ClientMapInstallationTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.client = self.root / 'isolated-client'
        original_tree(self.client / compat.LUA)
        self.prepared = self.root / 'prepared'
        self.report = compat.prepare(self.client, self.prepared)
        self.launcher = compat.launcher_module()
        files = {}
        for name in self.launcher.REQUIRED_FILES:
            path = self.client / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(name.encode('utf-8'))
            files[name] = compat.sha(path.read_bytes())
        self.manifest = self.root / 'patch-manifest.json'
        data = dict(schema=2, clientRoot=str(self.client), clientFiles=files,
                    debugConsole=dict(mode='external', sha256='0' * 64, backupPath='console.dll'),
                    service=dict(readyUrl='http://127.0.0.1:18080/health/ready', startCommand='owned-fixture'))
        self.manifest.write_text(json.dumps(data), 'utf-8')
        self.original_manifest = self.manifest.read_bytes()
        self.pointer = self.client / compat.LUA / 'M_LuaV.bytes'
        self.original_pointer = self.pointer.read_bytes()
        self.backup = self.root / 'backup'
        self.module_patch = patch.object(compat, 'launcher_module', return_value=self.launcher)
        self.module_patch.start()
        self.addCleanup(self.module_patch.stop)
        self.stopped = patch.object(self.launcher, 'client_running', return_value=False)
        self.stopped.start()
        self.addCleanup(self.stopped.stop)

    def install(self):
        return compat.install(self.prepared, self.manifest, self.backup)

    def assert_unchanged(self):
        self.assertEqual(self.original_pointer, self.pointer.read_bytes())
        self.assertEqual(self.original_manifest, self.manifest.read_bytes())

    def test_install_preserves_backups_and_validates_on_the_existing_launcher(self):
        result = self.install()
        self.assertTrue(result['passed'])
        self.assertFalse(result['clientExecuted'])
        self.assertEqual(self.original_pointer, (self.backup / 'M_LuaV.bytes').read_bytes())
        self.assertEqual(self.original_manifest, (self.backup / 'patch-manifest.json').read_bytes())
        data = json.loads(self.manifest.read_text('utf-8'))
        self.assertEqual(self.report['files'], data['clientLuaFiles'])
        for name, expected in self.report['files'].items():
            self.assertEqual(expected, self.launcher.sha(self.client / name))
        with patch.object(self.launcher, 'require_ready'), patch.object(self.launcher, 'launch_client') as launch:
            result = self.launcher.start(self.manifest, dry_run=True)
        self.assertEqual(4, result['clientLuaFilesVerified'])
        launch.assert_not_called()

    def test_a_second_install_is_idempotent(self):
        self.install()
        before = self.manifest.read_bytes()
        result = self.install()
        self.assertTrue(result['alreadyInstalled'])
        self.assertEqual(before, self.manifest.read_bytes())

    def test_running_game_rejects_before_any_backup_or_mutation(self):
        with patch.object(self.launcher, 'client_running', return_value=True):
            with self.assertRaisesRegex(RuntimeError, '客户端正在运行'):
                self.install()
        self.assert_unchanged()
        self.assertFalse(self.backup.exists())

    def test_changed_prepared_bytes_and_forged_digest_are_recomputed_from_originals(self):
        relative = next(name for name in self.report['files'] if not name.endswith('M_LuaV.bytes'))
        path = self.prepared / Path(relative).name
        path.write_bytes(path.read_bytes() + b'changed')
        self.report['files'][relative] = compat.sha(path.read_bytes())
        (self.prepared / 'prepared.json').write_text(json.dumps(self.report), 'utf-8')
        with self.assertRaisesRegex(ValueError, '重新计算'):
            self.install()
        self.assert_unchanged()
        self.assertFalse(self.backup.exists())

    def test_unknown_destination_is_not_overwritten(self):
        relative = next(name for name in self.report['files'] if not name.endswith('M_LuaV.bytes'))
        target = self.client / relative
        target.write_bytes(b'unknown')
        with self.assertRaisesRegex(ValueError, '同名文件'):
            self.install()
        self.assert_unchanged()
        self.assertEqual(b'unknown', target.read_bytes())

    def test_manifest_save_failure_restores_original_pointer(self):
        atomic = compat.atomic
        def fail_manifest(path, raw):
            if path == self.manifest:
                raise OSError('isolated manifest save failure')
            atomic(path, raw)
        with patch.object(compat, 'atomic', side_effect=fail_manifest):
            with self.assertRaisesRegex(OSError, 'manifest save failure'):
                self.install()
        self.assert_unchanged()

    def test_launcher_refuses_changed_lua_instead_of_launching_with_partial_patch(self):
        self.install()
        self.pointer.write_bytes(b'changed')
        with patch.object(self.launcher, 'require_ready'), patch.object(self.launcher, 'launch_client') as launch:
            with self.assertRaisesRegex(ValueError, '地图兼容资源已变化'):
                self.launcher.start(self.manifest)
        launch.assert_not_called()

    def test_launcher_rejects_outside_lua_paths(self):
        self.install()
        data = json.loads(self.manifest.read_text('utf-8'))
        key = next(k for k in data['clientLuaFiles'] if k.endswith('M_LuaV.bytes'))
        data['clientLuaFiles']['../outside'] = data['clientLuaFiles'].pop(key)
        self.manifest.write_text(json.dumps(data), 'utf-8')
        with self.assertRaisesRegex(ValueError, '地图兼容资源'):
            self.launcher.load_manifest(self.manifest)


if __name__ == '__main__':
    unittest.main()
