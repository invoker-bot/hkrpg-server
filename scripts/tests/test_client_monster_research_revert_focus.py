"""撤回界面候选时保护客户端入口和失败回退。"""
import json
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'scripts'))
import client_monster_research_revert_focus as rollback


class FocusRevertTest(unittest.TestCase):
    def setUp(self):
        base = (ROOT / 'build').resolve()
        base.mkdir(exist_ok=True)
        self.temp = tempfile.TemporaryDirectory(dir=base, prefix='research-rollback-test-')
        self.addCleanup(self.temp.cleanup)
        root = Path(self.temp.name).resolve()
        self.assertTrue(root.is_relative_to(base))
        self.client = root / 'client'; self.client.mkdir()
        self.index = self.client / 'index'; self.index.write_bytes(b'candidate design')
        self.pointer = self.client / 'pointer'; self.pointer.write_bytes(b'candidate lua')
        self.manifest = root / 'manifest.json'; self.manifest.write_bytes(b'candidate manifest')
        self.backup = root / 'backup'
        self.plan = {'client': self.client, 'indexPath': self.index,
                     'beforeIndex': b'candidate design', 'afterIndex': b'prior design',
                     'pointerPath': self.pointer, 'beforePointer': b'candidate lua', 'afterPointer': b'prior lua',
                     'manifestPath': self.manifest, 'beforeManifest': b'candidate manifest',
                     'afterManifest': b'prior manifest'}

    def test_reverts_both_entries_and_preserves_candidate_backup(self):
        rollback.perform(self.plan, self.backup, lambda: None)
        self.assertEqual(b'prior design', self.index.read_bytes())
        self.assertEqual(b'prior lua', self.pointer.read_bytes())
        self.assertEqual(b'prior manifest', self.manifest.read_bytes())
        self.assertEqual(b'candidate design', (self.backup / 'design-index.bytes').read_bytes())
        self.assertEqual(b'candidate lua', (self.backup / 'M_LuaV.bytes').read_bytes())
        self.assertEqual(b'candidate manifest', (self.backup / 'patch-manifest.json').read_bytes())

    def test_live_or_changed_client_is_not_modified(self):
        with self.assertRaises(RuntimeError):
            rollback.perform(self.plan, self.backup, lambda: (_ for _ in ()).throw(RuntimeError('running')))
        self.assertFalse(self.backup.exists())
        self.index.write_bytes(b'changed by another task')
        with self.assertRaises(ValueError): rollback.perform(self.plan, self.backup, lambda: None)
        self.assertFalse(self.backup.exists())
        self.assertEqual(b'candidate lua', self.pointer.read_bytes())

    def test_failure_after_pointer_switch_restores_candidate(self):
        original_atomic = rollback.atomic
        def fail_manifest(path, data, expected):
            if path == self.manifest and data == b'prior manifest': raise OSError('injected failure')
            return original_atomic(path, data, expected)
        with patch.object(rollback, 'atomic', side_effect=fail_manifest):
            with self.assertRaises(OSError): rollback.perform(self.plan, self.backup, lambda: None)
        self.assertEqual(b'candidate design', self.index.read_bytes())
        self.assertEqual(b'candidate lua', self.pointer.read_bytes())
        self.assertEqual(b'candidate manifest', self.manifest.read_bytes())

    def test_game_starting_mid_revert_does_not_switch_another_entry(self):
        calls = 0
        def stop_check():
            nonlocal calls
            calls += 1
            if calls >= 3: raise RuntimeError('running')
        with self.assertRaises(RuntimeError): rollback.perform(self.plan, self.backup, stop_check)
        self.assertEqual(b'prior design', self.index.read_bytes())
        self.assertEqual(b'candidate lua', self.pointer.read_bytes())
        self.assertEqual(b'candidate manifest', self.manifest.read_bytes())
        self.assertEqual(b'candidate design', (self.backup / 'design-index.bytes').read_bytes())


if __name__ == '__main__': unittest.main()
