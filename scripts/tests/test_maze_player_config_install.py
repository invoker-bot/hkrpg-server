"""Sparse locked-resource installation must include the two complete maze configs."""
import importlib.util
import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

SPEC = importlib.util.spec_from_file_location(
    'maze_config_install', Path(__file__).parents[1] / 'install-resources.py')
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)
FIXTURES = Path(__file__).parents[1] / 'fixtures/maze-local-player'
NAMES = ['LocalPlayer_Feixiao_00_Config.json', 'LocalPlayer_Castorice_00_Config.json']
PATHS = ['Config/ConfigCharacter/LocalPlayer/' + name for name in NAMES]
PAYLOADS = {relative: (FIXTURES / name).read_bytes() for relative, name in zip(PATHS, NAMES)}


class MazePlayerConfigInstallTest(unittest.TestCase):
    def make_source(self, root):
        source = root / 'source'
        (source / '.git').mkdir(parents=True)
        (source / 'ExcelOutput').mkdir()
        (source / 'ExcelOutput/seed.json').write_text('{"seed":true}', encoding='utf-8')
        return source, root / 'target'

    def git(self, command, **kwargs):
        if 'rev-parse' in command:
            return MODULE.EXPECTED_COMMIT + '\n'
        if 'show' in command:
            return 'OSPRODWin4.6.0_fixture\n'
        if 'cat-file' in command:
            commit, relative = command[-1].split(':', 1)
            self.assertEqual(MODULE.EXPECTED_COMMIT, commit)
            return PAYLOADS[relative]
        raise AssertionError('Unexpected Git read')

    def test_sparse_git_whole_configs_install_and_repeat_preserve_files(self):
        with tempfile.TemporaryDirectory() as temporary:
            source, target = self.make_source(Path(temporary))
            with patch.object(MODULE.subprocess, 'check_output', side_effect=self.git):
                first = MODULE.install(source, target)
                for relative, raw in PAYLOADS.items():
                    self.assertTrue((target / relative).is_file(), 'Required whole LocalPlayer config absent')
                    self.assertEqual(raw, (target / relative).read_bytes())
                prior = {p.relative_to(target): (p.read_bytes(), p.stat().st_mtime_ns)
                         for p in target.rglob('*') if p.is_file()}
                second = MODULE.install(source, target)
                self.assertEqual(3, first['installed'])
                self.assertEqual(0, second['installed'])
                self.assertEqual(prior, {p.relative_to(target): (p.read_bytes(), p.stat().st_mtime_ns)
                                        for p in target.rglob('*') if p.is_file()})
                manifest = json.loads((target / 'resource-manifest.json').read_text('utf-8'))
                for relative in PATHS:
                    self.assertIn(relative, manifest['files'])

    def test_conflict_in_second_config_rejects_entire_batch_before_first_write(self):
        with tempfile.TemporaryDirectory() as temporary:
            source, target = self.make_source(Path(temporary))
            conflict = target / PATHS[1]
            conflict.parent.mkdir(parents=True)
            conflict.write_bytes(b'operator-original')
            prior = (conflict.read_bytes(), conflict.stat().st_mtime_ns)
            with patch.object(MODULE.subprocess, 'check_output', side_effect=self.git):
                with self.assertRaises(RuntimeError):
                    MODULE.install(source, target)
            self.assertFalse((target / PATHS[0]).exists())
            self.assertFalse((target / 'ExcelOutput').exists())
            self.assertFalse((target / 'resource-manifest.json').exists())
            self.assertEqual(prior, (conflict.read_bytes(), conflict.stat().st_mtime_ns))

    def test_tampered_pinned_git_blob_rejects_before_destination_creation(self):
        with tempfile.TemporaryDirectory() as temporary:
            source, target = self.make_source(Path(temporary))
            def wrong(command, **kwargs):
                result = self.git(command, **kwargs)
                return result + b' ' if 'cat-file' in command and command[-1].endswith(PATHS[1]) else result
            with patch.object(MODULE.subprocess, 'check_output', side_effect=wrong):
                with self.assertRaises(RuntimeError):
                    MODULE.install(source, target)
            self.assertFalse(target.exists())

    def test_other_local_player_configs_are_not_implicitly_installed(self):
        with tempfile.TemporaryDirectory() as temporary:
            source, target = self.make_source(Path(temporary))
            extra = source / 'Config/ConfigCharacter/LocalPlayer/LocalPlayer_Other_Config.json'
            extra.parent.mkdir(parents=True)
            extra.write_text('{"notSelected":true}', encoding='utf-8')
            with patch.object(MODULE.subprocess, 'check_output', side_effect=self.git):
                MODULE.install(source, target)
            self.assertFalse((target / extra.relative_to(source)).exists())


if __name__ == '__main__':
    unittest.main()
