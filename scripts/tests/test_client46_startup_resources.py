import importlib.util
import json
from pathlib import Path
import tempfile
import unittest

SCRIPT = Path(__file__).resolve().parents[1] / 'recover-client-startup-resources.py'


class StartupResourcesTest(unittest.TestCase):
    def setUp(self):
        spec = importlib.util.spec_from_file_location('startup_recovery', SCRIPT)
        self.recovery = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(self.recovery)

    def test_fixed_native_records_are_complete_and_not_empty_floors(self):
        resources = self.recovery.fixture_resources()
        self.assertEqual(279, len(resources))
        counts = {20561: 231, 40561: 27, 30146: 17}
        for plane, count in counts.items():
            path = f'Config/LevelOutput/RuntimeFloor/P{plane}_F{plane}001.json'
            floor = json.loads(resources[path])
            self.assertEqual(plane * 1000 + 1, floor['FloorID'])
            self.assertEqual(count, len(floor['GroupInstanceList']))
            self.assertTrue(floor['DimensionList'])
            for group in floor['GroupInstanceList']:
                self.assertIn(group['GroupPath'], resources)
        pearl = json.loads(resources['Config/ConfigAdventureAbility/LocalPlayer/LocalPlayer_Pearl_00_Ability.json'])
        self.assertTrue(pearl['AbilityList'])
        self.assertTrue(any('Pearl' in ability['Name'] for ability in pearl['AbilityList']))

    def test_conflict_is_checked_before_any_resource_is_written(self):
        with tempfile.TemporaryDirectory() as folder:
            target = Path(folder)
            (target / 'old.json').write_text('operator data', encoding='utf8')
            with self.assertRaises(FileExistsError):
                self.recovery.install_files({'new.json': b'{}', 'old.json': b'[]'}, target)
            self.assertFalse((target / 'new.json').exists())
            self.assertEqual('operator data', (target / 'old.json').read_text(encoding='utf8'))

    def test_additive_install_is_idempotent(self):
        with tempfile.TemporaryDirectory() as folder:
            target = Path(folder)
            files = {'Config/example.json': b'{"ID":1}\n'}
            self.assertEqual(1, self.recovery.install_files(files, target))
            self.assertEqual(0, self.recovery.install_files(files, target))

    def test_truncated_unknown_and_extra_bytes_are_rejected(self):
        path, raw = next(iter(self.recovery.fixture_records().items()))
        for invalid in (raw[:-1], raw + b'\x00', b'\xff' * 12):
            with self.subTest(length=len(invalid)), self.assertRaises(ValueError):
                self.recovery.decode_record(path, invalid)

    def test_output_cannot_escape_resource_root(self):
        with tempfile.TemporaryDirectory() as folder:
            with self.assertRaises(ValueError):
                self.recovery.install_files({'../outside.json': b'{}'}, Path(folder))


if __name__ == '__main__':
    unittest.main()
