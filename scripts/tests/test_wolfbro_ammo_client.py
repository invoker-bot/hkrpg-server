import base64
import importlib.util
import json
from pathlib import Path
import re
import unittest

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location('wolfbro_ammo', ROOT / 'scripts/inspect-wolfbro-ammo-client.py')
ammo = importlib.util.module_from_spec(spec)
spec.loader.exec_module(ammo)


class WolfBroAmmoClientTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.fixture = json.loads((ROOT / 'src/test/resources/fixtures/wolfbro-ammo-client-configs-4.6.json').read_text('utf-8'))

    def test_both_complete_original_objects_are_preserved(self):
        for record in self.fixture['records']:
            raw = base64.b64decode(record['rawBase64'])
            self.assertEqual(record['length'], len(raw))
            self.assertEqual(record['sha256'], ammo.sha(raw))
            self.assertEqual(record['resource'], ammo.decode_config(raw))

    def test_original_gun_selects_the_actual_new_graph_and_ammo_selects_its_original_graph(self):
        decoded = [ammo.decode_config(base64.b64decode(r['rawBase64'])) for r in self.fixture['records']]
        self.assertTrue(decoded[0]['DefaultLevelGraphPath'].endswith('CartoonGun_02.json'))
        self.assertTrue(decoded[1]['DefaultLevelGraphPath'].endswith('CartoonAmmo_01.json'))
        self.assertEqual(['Interact', 'InteractByTeam'], [b['ID'] for b in decoded[1]['PropButtonConfigs']])

    def test_every_truncation_is_rejected(self):
        for record in self.fixture['records']:
            raw = base64.b64decode(record['rawBase64'])
            for length in range(len(raw)):
                with self.assertRaises(ValueError):
                    ammo.decode_config(raw[:length])

    def test_unconsumed_bytes_are_rejected(self):
        for record in self.fixture['records']:
            with self.assertRaises(ValueError):
                ammo.decode_config(base64.b64decode(record['rawBase64']) + b'\0')

    def test_unknown_config_masks_cannot_be_silently_dropped(self):
        raw = bytearray(base64.b64decode(self.fixture['records'][0]['rawBase64']))
        raw[0] |= 1
        with self.assertRaises(ValueError):
            ammo.decode_config(bytes(raw))

    def test_unknown_button_branch_is_rejected(self):
        raw = bytearray(base64.b64decode(self.fixture['records'][0]['rawBase64']))
        self.assertEqual(0xb, raw[93])
        raw[93] |= 4
        with self.assertRaises(ValueError):
            ammo.decode_config(bytes(raw))

    def test_dynamic_expression_does_not_become_a_literal_value(self):
        raw = bytearray(base64.b64decode(self.fixture['records'][0]['rawBase64']))
        self.assertEqual(4, raw[103])
        raw[103] = 5
        with self.assertRaises(ValueError):
            ammo.decode_config(bytes(raw))

    def test_counts_and_strings_are_bounded(self):
        # Known PropConfig mask followed by a noncanonical array count or overflowing string.
        mask = base64.b64decode(self.fixture['records'][0]['rawBase64'])[:4]
        for tail in (b'\x82\x00', b'\xff' * 10, b'\x02\xff' * 10):
            with self.assertRaises(ValueError):
                ammo.decode_config(mask + tail)

    def test_complete_fixed_g21_retains_its_actual_gson_number_tokens(self):
        raw = (ROOT / 'src/test/resources/fixtures/wolfbro-g21-original-4.6.json').read_bytes()
        self.assertEqual('9ee1588bb762832b9f787a410348ee83f41a3fcd026a3f09d91adf8ab2ef1d8f', ammo.sha(raw))
        self.assertEqual('3d0649fd63d95e5a2681b9927b512a2c7099d571891f653a5b58701b38c53cac',
                         ammo.fingerprint(ammo.source_json.loads(raw)))
        self.assertNotEqual(ammo.fingerprint(json.loads(raw)), ammo.fingerprint(ammo.source_json.loads(raw)))

    def test_all_86_complete_original_files_and_41_original_rows_match_runtime_pins(self):
        source = (ROOT / 'src/main/java/org/hkrpg/server/gameplay/WolfBroAmmoSources.java').read_text('utf-8')
        pins = dict(re.findall(r'Map\.entry\("([^"]+)", "([0-9a-f]{64})"\)', source))
        keys = {'ConstValueCommon': 'ConstValueName', 'NPCMonsterData': 'ID', 'MazeProp': 'ID',
                'MazePlane': 'PlaneID', 'MazePuzzleWolfBro': 'WolfBroID'}
        tables, placements, rows = {}, 0, 0
        for name, pin in pins.items():
            if name.startswith('Config/'):
                value = ammo.source_json.loads((ROOT / 'resources/4.6' / name).read_bytes())
                placements += 1
            else:
                table, key = name.split(':', 1)
                if table not in tables:
                    tables[table] = ammo.source_json.loads((ROOT / 'resources/4.6/ExcelOutput' / (table + '.json')).read_bytes())
                matches = [row for row in tables[table] if str(row[keys[table]]) == key]
                self.assertEqual(1, len(matches), name)
                value = matches[0]
                rows += 1
            self.assertEqual(pin, ammo.fingerprint(value), name)
        self.assertEqual((86, 41), (placements, rows))

    def test_a_changed_complete_original_decimal_is_rejected_even_when_float_values_equal(self):
        raw = (ROOT / 'src/test/resources/fixtures/wolfbro-g21-original-4.6.json').read_text('utf-8')
        candidates = []
        for match in re.finditer(r'-?\d+\.\d+', raw):
            token = match.group()
            altered = token + '0' * 22 + '1'
            if float(token) == float(altered):
                candidates.append((match, altered))
        self.assertTrue(candidates, 'A tiny mutation of an original decimal must exercise float precision loss')
        match, altered = candidates[0]
        changed = raw[:match.start()] + altered + raw[match.end():]
        self.assertEqual(json.loads(raw), json.loads(changed))
        self.assertNotEqual(ammo.fingerprint(ammo.source_json.loads(raw)),
                            ammo.fingerprint(ammo.source_json.loads(changed)))

    def test_numeric_source_tokens_do_not_become_booleans_or_rounded_literals(self):
        value = ammo.source_json.loads('{"z":1.23000000000000000001,"a":true,"b":1,"c":1e+04}')
        self.assertEqual('{"a":true,"b":1,"c":1e+04,"z":1.23000000000000000001}',
                         ammo.source_json.canonical(value))
        self.assertNotEqual(ammo.fingerprint(ammo.source_json.loads('{"a":true}')),
                            ammo.fingerprint(ammo.source_json.loads('{"a":1}')))
        for token in ('NaN', 'Infinity', '-Infinity'):
            with self.assertRaises(ValueError):
                ammo.source_json.loads(token)


if __name__ == '__main__':
    unittest.main()
