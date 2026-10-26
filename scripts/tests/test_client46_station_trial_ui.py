"""真实固定试用表先失败回归；安装测试只操作临时副本，不改运行中的游戏。"""
import base64
import copy
import hashlib
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import zlib

ROOT = Path(__file__).resolve().parents[2]
CLIENT = Path(r'C:\Users\InvokerBot\AppData\Local\hoyo\hkrpg\versions\current')
spec = importlib.util.spec_from_file_location('station_ui', ROOT / 'scripts/patch-client-station-trial-ui.py')
ui = importlib.util.module_from_spec(spec)
spec.loader.exec_module(ui)


class StationTrialRecordTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        f = json.loads((ROOT / 'scripts/tests/fixtures/client46-station-trial-ui.json').read_text('utf-8'))
        cls.raw = zlib.decompress(base64.b64decode(f['recordZlibBase64'], validate=True))
        assert hashlib.sha256(cls.raw).hexdigest() == f['sha256'] == ui.profile()['originalRecordSHA256']

    def test_first_open_skips_plot_trial_instead_of_showing_its_gear_as_a_formal_avatar(self):
        rows, index, _ = ui.decode_record(ui.patch_record(self.raw))
        # Pinned AvatarMainPage.get_init_avatar calls CheckIsPlotTrial; that native function
        # reads the SpecialAvatarType component and compares it with TYPE_PLOT (4).
        self.assertEqual(4, rows[index]['Type'])

    def test_exactly_one_ui_classification_byte_changes(self):
        result = ui.patch_record(self.raw)
        self.assertEqual(len(self.raw), len(result))
        self.assertEqual([18100], [i for i, (a, b) in enumerate(zip(self.raw, result)) if a != b])
        self.assertEqual((2, 8), (self.raw[18100], result[18100]))

    def test_all_rows_stats_skills_and_gear_are_preserved(self):
        before, target, _ = ui.decode_record(self.raw)
        after, new_target, _ = ui.decode_record(ui.patch_record(self.raw))
        self.assertEqual((4925, target), (len(after), new_target))
        after = copy.deepcopy(after)
        after[target]['Type'] = before[target]['Type']
        self.assertEqual(before, after)
        self.assertEqual((1011003, 1003, 8, 20006, 5, 1), tuple(before[target][f] for f in
            ('SpecialAvatarID', 'AvatarID', 'Level', 'EquipmentID', 'EquipmentLevel', 'EquipmentRank')))

    def test_already_repaired_record_is_idempotent(self):
        result = ui.patch_record(self.raw)
        self.assertEqual(result, ui.patch_record(result))

    def test_unknown_changed_or_truncated_table_is_rejected(self):
        changed = bytearray(self.raw)
        changed[18200] ^= 1
        for raw in (b'', self.raw[:-1], bytes(changed), self.raw + b'\0'):
            with self.subTest(length=len(raw)), self.assertRaises(ValueError):
                ui.patch_record(raw)

    def test_changed_patch_profile_is_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            source = Path(directory) / 'profile.json'
            value = json.loads(ui.PROFILE.read_text('utf-8'))
            value['specialAvatarID'] = 3031003
            source.write_text(json.dumps(value), encoding='utf-8')
            with patch.object(ui, 'PROFILE', source), self.assertRaisesRegex(ValueError, 'SHA256'):
                ui.profile()


@unittest.skipUnless((CLIENT / 'GameAssembly.dll').is_file(), '没有固定本机客户端')
class StationTrialInstallTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.plan = ui.make_plan(CLIENT)

    def test_complete_native_index_and_unchanged_original_block_are_verified(self):
        p, plan = ui.profile(), self.plan
        self.assertEqual(p['patchedIndexSHA256'], ui.inspect_index(plan['index']))
        original = plan['sourcePath'].read_bytes()
        self.assertEqual(p['originalChunkMD5'], hashlib.md5(original).hexdigest())
        self.assertEqual(p['patchedChunkMD5'], hashlib.md5(plan['chunk']).hexdigest())
        self.assertEqual([p['byteInChunk']], [i for i, (a, b) in enumerate(zip(original, plan['chunk'])) if a != b])

    def test_changed_index_is_rejected_before_reading_records(self):
        altered = bytearray(self.plan['index'])
        altered[-1] ^= 1
        with self.assertRaisesRegex(ValueError, 'SHA256'):
            ui.inspect_index(bytes(altered))

    def test_repaired_index_is_an_explicit_read_only_source_profile(self):
        design = ui.module('station_ui_design_reader', 'read-client-design-data.py')
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / ui.profile()['indexFilename']).write_bytes(self.plan['index'])
            (root / self.plan['targetPath'].name).write_bytes(self.plan['chunk'])
            path, chunks, entries = design.parse_index(root, self.plan['directory'],
                index_sha256=ui.profile()['patchedIndexSHA256'])
            chunk, length, offset = entries[ui.profile()['recordKey']]
            raw = Path(chunk['file']).read_bytes()[chunk['base'] + offset:chunk['base'] + offset + length]
            self.assertEqual(ui.profile()['patchedRecordSHA256'], ui.sha(raw))
            with self.assertRaisesRegex(ValueError, 'SHA256'):
                design.parse_index(root)

    def fixture(self, directory):
        result = dict(self.plan)
        root = Path(directory)
        result.update(client=root, directory=root, indexPath=root / 'DesignV_fixture.bytes',
                      targetPath=root / 'new-block.bytes', installed=False)
        original_index = bytearray(result['index'])
        p = ui.profile()
        original_index[p['indexMD5Offset']:p['indexMD5Offset'] + 16] = bytes.fromhex(p['originalChunkMD5'])
        result.update(originalIndex=bytes(original_index), originalIndexSHA256=p['originalIndexSHA256'])
        result['indexPath'].write_bytes(result['originalIndex'])
        return result

    def test_running_client_refuses_every_install_before_writes(self):
        launcher = type('Launcher', (), {'client_running': staticmethod(lambda: True)})()
        with patch.object(ui, 'module', return_value=launcher), self.assertRaisesRegex(RuntimeError, '正在运行'):
            ui.install(CLIENT)

    def test_install_is_backed_up_and_idempotent_on_a_disposable_copy(self):
        with tempfile.TemporaryDirectory() as directory:
            plan = self.fixture(directory)
            def state(_):
                return dict(plan, installed=ui.sha(plan['indexPath'].read_bytes()) == ui.profile()['patchedIndexSHA256'])
            with patch.object(ui, 'require_stopped'), patch.object(ui, 'make_plan', side_effect=state), \
                    patch.object(ui, 'BACKUPS', Path(directory) / 'backups'):
                result = ui.install(Path(directory))
                self.assertTrue(result['changed'])
                self.assertEqual(plan['originalIndex'], (Path(result['backup']) / ui.profile()['indexFilename']).read_bytes())
                self.assertEqual(plan['chunk'], plan['targetPath'].read_bytes())
                self.assertFalse(ui.install(Path(directory))['changed'])
                self.assertTrue(ui.restore(Path(directory))['changed'])
                self.assertEqual(plan['originalIndex'], plan['indexPath'].read_bytes())
                self.assertTrue(plan['targetPath'].is_file(), 'Restoration must not delete any client block')

    def test_conflicting_index_and_failed_atomic_replace_preserve_the_original(self):
        with tempfile.TemporaryDirectory() as directory:
            plan = self.fixture(directory)
            with self.assertRaisesRegex(ValueError, '变化'):
                ui.atomic_replace(plan['indexPath'], plan['index'], '0' * 64)
            self.assertEqual(plan['originalIndex'], plan['indexPath'].read_bytes())
            with patch.object(Path, 'replace', side_effect=OSError('fixture write failure')), self.assertRaises(OSError):
                ui.atomic_replace(plan['indexPath'], plan['index'], plan['originalIndexSHA256'])
            self.assertEqual(plan['originalIndex'], plan['indexPath'].read_bytes())
            self.assertFalse(list(Path(directory).glob('.hkrpg-trial-ui-*')))

    def test_block_changed_before_activation_never_activates_the_index(self):
        with tempfile.TemporaryDirectory() as directory:
            plan = self.fixture(directory)
            calls = []
            def stopped():
                calls.append(1)
                if len(calls) == 2:
                    plan['targetPath'].write_bytes(b'conflicting updater block')
            # Supply an uninstalled initial plan, then a passing final snapshot. The installer
            # must independently recheck the written block before it can activate that index.
            with patch.object(ui, 'require_stopped', side_effect=stopped), \
                    patch.object(ui, 'make_plan', side_effect=[plan, dict(plan, installed=True)]), \
                    patch.object(ui, 'BACKUPS', Path(directory) / 'backups'), self.assertRaises(ValueError):
                ui.install(Path(directory))
            self.assertEqual(plan['originalIndex'], plan['indexPath'].read_bytes())

    def test_failed_post_install_verification_restores_only_its_index(self):
        with tempfile.TemporaryDirectory() as directory:
            plan = self.fixture(directory)
            with patch.object(ui, 'require_stopped'), \
                    patch.object(ui, 'make_plan', side_effect=[plan, ValueError('fixture verification failure')]), \
                    patch.object(ui, 'BACKUPS', Path(directory) / 'backups'), self.assertRaises(ValueError):
                ui.install(Path(directory))
            self.assertEqual(plan['originalIndex'], plan['indexPath'].read_bytes())
            self.assertEqual(ui.profile()['originalChunkMD5'], hashlib.md5(plan['sourcePath'].read_bytes()).hexdigest())


if __name__ == '__main__':
    unittest.main()
