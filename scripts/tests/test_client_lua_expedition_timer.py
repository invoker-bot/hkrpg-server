"""执行原委托领奖回调的有限指令，核对满额领奖后自然小时的页面刷新。"""
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location('client_lua_expedition_timer', ROOT / 'scripts/client_lua_expedition_timer.py')
timer = importlib.util.module_from_spec(spec)
spec.loader.exec_module(timer)
CLIENT = Path('C:/Users/InvokerBot/AppData/Local/hoyo/hkrpg/versions/current')
INDEX = CLIENT / timer.lua.LUA / 'LuaV_ae52631809b84671e2acc54e537ba182.bytes'
fixture_spec = importlib.util.spec_from_file_location('expedition_package_fixtures', ROOT / 'scripts/tests/test_client_lua_rogue_hint.py')
fixtures = importlib.util.module_from_spec(fixture_spec)
fixture_spec.loader.exec_module(fixtures)


def original_record():
    index = INDEX.read_bytes()
    if timer.lua.sha(index) != timer.lua.INDEX_SHA:
        raise AssertionError('原始 Lua 索引变化')
    for chunk in timer.lua.read_index(index):
        for entry in chunk['entries']:
            if entry['key'] != timer.RECORD_KEY:
                continue
            path = CLIENT / timer.lua.LUA / chunk['locale'] / (chunk['digest'] + '.bytes')
            with path.open('rb') as stream:
                stream.seek(entry['offset'])
                raw = stream.read(entry['length'])
            if timer.lua.sha(raw) != timer.PAGE_SHA:
                raise AssertionError('原始委托页变化')
            return raw
    raise AssertionError('原始委托页缺失')


class RewardPage:
    """仅执行原始领奖回调；既有刷新/定时器接口由确定时钟观察桩提供。"""
    def __init__(self, raw):
        self.decoded = timer.lua.Bytecode(raw)
        self.now, self.anchor = 30 * 3600 + 1277, 0
        self.timer_at = None
        self.visible_hours = 30
        self.dialogs = []
        self.calls = []
        self.disposed = False
        self.actor = {'_refresh': self.refresh, '_try_add_refresh_timer': self.schedule}
        self.globals = {'G': {'RewardUtils': {'show_reward_dialog': self.dialog}}}

    def dialog(self, rewards):
        self.dialogs.append(rewards)
        self.calls.append('dialog')

    def refresh(self, actor, *arguments):
        self.visible_hours = min(30, max(0, (self.now - self.anchor) // 3600))
        self.calls.append('refresh')

    def schedule(self, actor):
        self.timer_at = None
        if not self.disposed and self.visible_hours < 30:
            self.timer_at = self.anchor + (self.visible_hours + 1) * 3600
        self.calls.append('schedule')

    def advance(self, seconds):
        target = self.now + seconds
        while self.timer_at is not None and self.timer_at <= target:
            self.now, self.timer_at = self.timer_at, None
            self.refresh(self.actor)
            self.schedule(self.actor)
        self.now = target

    def claim(self, rewards):
        self.anchor = self.now // 3600 * 3600
        p = next(p for p in self.decoded.protos if p['name'] == '_on_reward_got')
        registers = [None] * p['header'][0]
        registers[:2] = [self.actor, rewards]
        code, constants, pc = p['code'], p['constants'], 0
        while pc < len(code):
            word = code[pc]
            op, a, b, c = word & 255, (word >> 8) & 255, (word >> 16) & 255, word >> 24
            pc += 1
            if op == 6:
                registers[a] = registers[b]
            elif op == 3:
                registers[a] = bool(b)
            elif op == 12:
                aux = code[pc]
                pc += 1
                value = self.globals
                for n in range(aux >> 30):
                    value = value[constants[(aux >> (20 - n * 10)) & 1023]]
                registers[a] = value
            elif op == 20:
                key = constants[code[pc]]
                pc += 1
                registers[a], registers[a + 1] = registers[b][key], registers[b]
            elif op == 21:
                result = registers[a](*registers[a + 1:a + b])
                if c == 2:
                    registers[a] = result
                elif c != 1:
                    raise AssertionError('未知调用返回数量')
            elif op == 22:
                return
            else:
                raise AssertionError('未核对的领奖指令：' + str(op))
        raise AssertionError('领奖回调未返回')


@unittest.skipUnless(INDEX.is_file(), '没有固定本机 4.6 Lua 原件')
class ExpeditionTimerTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.original = original_record()

    def test_full_claim_page_advances_at_next_hour_without_reopening(self):
        page = RewardPage(timer.patch_record(self.original))
        rewards = [{'item_id': 111001, 'num': 60}, {'item_id': 111011, 'num': 60}]
        page.claim(rewards)
        self.assertEqual(0, page.visible_hours)
        self.assertEqual([rewards], page.dialogs)
        page.advance(3600 - 1277 - 1)
        self.assertEqual(0, page.visible_hours)
        page.advance(1)
        self.assertEqual(1, page.visible_hours, '满额领奖后页面没有恢复下一小时刷新')
        self.assertEqual([rewards], page.dialogs, '小时刷新不能重复显示领奖')

    def test_second_full_claim_restarts_the_timer_after_reaching_the_cap_again(self):
        page = RewardPage(timer.patch_record(self.original))
        page.claim(['first'])
        page.advance(30 * 3600)
        self.assertEqual(30, page.visible_hours)
        self.assertIsNone(page.timer_at)
        page.claim(['second'])
        page.advance(3600)
        self.assertEqual(1, page.visible_hours)
        self.assertEqual([['first'], ['second']], page.dialogs)

    def test_rearming_an_existing_timer_keeps_one_refresh_and_original_reward_order(self):
        page = RewardPage(timer.patch_record(self.original))
        page.now = 2 * 3600 + 1277
        page.refresh(page.actor)
        page.schedule(page.actor)
        page.calls.clear()
        page.claim(['materials'])
        self.assertEqual(['dialog', 'refresh', 'schedule'], page.calls)
        page.calls.clear()
        page.advance(3600)
        self.assertEqual(1, page.visible_hours)
        self.assertEqual(['refresh', 'schedule'], page.calls)

    def test_original_full_claim_reproduces_stuck_zero_even_after_two_hours(self):
        page = RewardPage(self.original)
        page.claim(['materials'])
        page.advance(7200)
        self.assertEqual(0, page.visible_hours)
        self.assertIsNone(page.timer_at)

    def test_only_reward_callback_changes_all_other_page_functions_remain_identical(self):
        raw = timer.patch_record(self.original)
        before, after = timer.lua.Bytecode(self.original), timer.lua.Bytecode(raw)
        changed = [a['name'] for a, b in zip(before.protos, after.protos)
                   if self.original[a['start']:a['end']] != raw[b['start']:b['end']]]
        self.assertEqual(['_on_reward_got'], changed)
        self.assertEqual(before.main, after.main)
        self.assertEqual(len(before.protos), len(after.protos))
        original = next(p for p in before.protos if p['name'] == '_on_reward_got')
        patched = next(p for p in after.protos if p['name'] == '_on_reward_got')
        self.assertEqual(original['code'][:-1], patched['code'][:len(original['code']) - 1])
        self.assertEqual(original['code'][-1], patched['code'][-1])
        self.assertEqual(len(patched['code']), len(patched['lines']))
        self.assertTrue(all(0 <= start <= end <= len(patched['code']) for _, start, end, _ in patched['locals']))

    def test_unknown_client_record_is_rejected_before_modification(self):
        for raw in (self.original[:-1], self.original[:10] + b'x' + self.original[11:]):
            with self.assertRaisesRegex(ValueError, '不是已核对原件'):
                timer.patch_record(raw)


@unittest.skipUnless(INDEX.is_file(), '没有固定本机 4.6 Lua 原件')
class ExpeditionTimerPackageTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.client = self.root / 'client'
        fixtures.isolated_client(self.client / timer.lua.LUA)

    def apply_files(self, files):
        for name, raw in files.items():
            (self.client / timer.lua.LUA / name).write_bytes(raw)

    def test_all_pinned_source_profiles_preserve_the_other_19642_records(self):
        for profile in ('original', 'map', 'map-and-hint'):
            with self.subTest(profile=profile):
                if profile == 'map':
                    self.apply_files(timer.lua.build_files(self.client)[1])
                elif profile == 'map-and-hint':
                    self.apply_files(fixtures.hint.build_files(self.client)[1])
                before = fixtures.records(self.client)
                pointer = (self.client / timer.lua.LUA / 'M_LuaV.bytes').read_bytes()
                report, files, _ = timer.build_files(self.client)
                after = fixtures.records(self.client, files)
                self.assertEqual(19644, len(after))
                self.assertEqual(before.keys(), after.keys())
                manifest_key = next(key for key, raw in before.items() if b'"Path":' in raw and b'"Md5":' in raw)
                changed = {key for key in before if before[key] != after[key]}
                self.assertEqual({manifest_key, timer.RECORD_KEY}, changed)
                self.assertEqual(timer.patch_record(before[timer.RECORD_KEY]), after[timer.RECORD_KEY])
                manifest = [json.loads(line) for line in after[manifest_key].splitlines()]
                row = next(row for row in manifest if row['Path'] == timer.PAGE)
                self.assertEqual(timer.lua.hashlib.md5(after[timer.RECORD_KEY]).hexdigest(), row['Md5'])
                self.assertEqual(pointer, (self.client / timer.lua.LUA / 'M_LuaV.bytes').read_bytes())
                self.assertFalse(report['clientModified'])
                self.assertEqual(4, len(report['files']))

    def installation_fixture(self):
        map_report, map_files, _ = timer.lua.build_files(self.client)
        self.apply_files(map_files)
        launcher = timer.lua.launcher_module()
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
        report = timer.lua.prepare(self.client, prepared, build=timer.build_files)
        return launcher, manifest, prepared, report

    def test_install_backs_up_the_existing_patch_and_is_idempotent(self):
        launcher, manifest, prepared, report = self.installation_fixture()
        old_manifest = manifest.read_bytes()
        pointer = self.client / timer.lua.LUA / 'M_LuaV.bytes'
        before = pointer.read_bytes()
        backup = self.root / 'backup'
        with patch.object(timer.lua, 'launcher_module', return_value=launcher), \
                patch.object(launcher, 'client_running', return_value=False):
            result = timer.lua.install(prepared, manifest, backup, build=timer.build_files)
            self.assertTrue(result['passed'])
            self.assertEqual(before, (backup / 'M_LuaV.bytes').read_bytes())
            self.assertEqual(old_manifest, (backup / 'patch-manifest.json').read_bytes())
            self.assertTrue(timer.lua.install(prepared, manifest, backup, build=timer.build_files)['alreadyInstalled'])
            with patch.object(launcher, 'require_ready'), patch.object(launcher, 'launch_client') as launch:
                self.assertEqual(4, launcher.start(manifest, dry_run=True)['clientLuaFilesVerified'])
                launch.assert_not_called()
        self.assertEqual(report['files'], json.loads(manifest.read_text('utf-8'))['clientLuaFiles'])

    def test_running_game_and_changed_prepared_files_are_rejected(self):
        launcher, manifest, prepared, report = self.installation_fixture()
        pointer = self.client / timer.lua.LUA / 'M_LuaV.bytes'
        before, old_manifest = pointer.read_bytes(), manifest.read_bytes()
        with patch.object(timer.lua, 'launcher_module', return_value=launcher):
            with patch.object(launcher, 'client_running', return_value=True):
                with self.assertRaisesRegex(RuntimeError, '客户端正在运行'):
                    timer.lua.install(prepared, manifest, self.root / 'backup', build=timer.build_files)
            name = next(name for name in report['files'] if 'LuaV_' in name)
            (prepared / Path(name).name).write_bytes(b'changed fixture')
            with patch.object(launcher, 'client_running', return_value=False):
                with self.assertRaisesRegex(ValueError, '待安装文件变化'):
                    timer.lua.install(prepared, manifest, self.root / 'backup', build=timer.build_files)
        self.assertEqual(before, pointer.read_bytes())
        self.assertEqual(old_manifest, manifest.read_bytes())
        self.assertFalse((self.root / 'backup').exists())

    def test_failed_manifest_write_restores_pointer_and_launcher_manifest(self):
        launcher, manifest, prepared, _ = self.installation_fixture()
        pointer = self.client / timer.lua.LUA / 'M_LuaV.bytes'
        before, old_manifest = pointer.read_bytes(), manifest.read_bytes()
        atomic = timer.lua.atomic
        def fail_manifest(path, raw):
            if path == manifest:
                raise OSError('isolated timer manifest failure')
            atomic(path, raw)
        with patch.object(timer.lua, 'launcher_module', return_value=launcher), \
                patch.object(launcher, 'client_running', return_value=False), \
                patch.object(timer.lua, 'atomic', side_effect=fail_manifest):
            with self.assertRaisesRegex(OSError, 'isolated timer manifest failure'):
                timer.lua.install(prepared, manifest, self.root / 'backup', build=timer.build_files)
        self.assertEqual(before, pointer.read_bytes())
        self.assertEqual(old_manifest, manifest.read_bytes())


if __name__ == '__main__':
    unittest.main()
