import importlib.util
import hashlib
import json
import struct
import tempfile
import unittest
from pathlib import Path
from types import SimpleNamespace

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location('client_runtime_groups', ROOT / 'scripts/recover-client-runtime-groups.py')
groups = importlib.util.module_from_spec(spec)
spec.loader.exec_module(groups)
CLIENT = Path(r'C:\Users\InvokerBot\AppData\Local\hoyo\hkrpg\versions\current')


def uvar(value):
    result = bytearray()
    while value >= 128:
        result.append((value & 127) | 128)
        value >>= 7
    result.append(value)
    return bytes(result)


def sint(value):
    return uvar(value * 2 if value >= 0 else -value * 2 - 1)


class RuntimeGroupReaderTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.profile = groups.load_profile()

    def test_vector2_json_uses_existing_resource_coordinate_names(self):
        actual = groups.resource_json({'x': -12.43689, 'y': 15.12104}, self.profile,
                                      {'kind': 17, 'name': 'MVector2'})
        self.assertEqual(actual, {'X': -12.43689, 'Y': 15.12104})

    def test_vector3_json_uses_existing_resource_coordinate_names(self):
        actual = groups.resource_json({'x': 1.0, 'y': 2.0, 'z': 3.0}, self.profile,
                                      {'kind': 17, 'name': 'MVector3'})
        self.assertEqual(actual, {'X': 1.0, 'Y': 2.0, 'Z': 3.0})

    def test_second_prop_mask_precedes_all_field_payloads(self):
        data = uvar(1) + uvar(1 << 12) + uvar(400001) + b'\x01'
        reader = groups.Reader(data, self.profile)
        prop = reader.read({'kind': 18, 'name': 'LevelPropInfo', 'typeIndex': 17261})
        self.assertEqual(prop['ID'], 400001)
        self.assertTrue(prop['IsTrackRealtimePositionInMap'])
        self.assertEqual(reader.pos, len(data))
        self.assertNotIn('State', prop)

    def test_unknown_second_mask_bit_is_rejected(self):
        with self.assertRaises(ValueError):
            groups.Reader(uvar(0) + uvar(1 << 13), self.profile).obj('LevelPropInfo')

    def test_only_runtime_root_and_no_trailing_bytes_are_accepted(self):
        self.assertEqual(groups.decode_group(b'\x02\x00', self.profile)['$mask'], '0x0')
        for data in (b'\x00\x00', b'\x01\x00', b'\x03\x00', b'\x02\x00\x00'):
            with self.subTest(data=data), self.assertRaises(ValueError):
                groups.decode_group(data, self.profile)

    def test_control_byte_is_fixed_width_even_when_top_bit_is_set(self):
        reader = groups.Reader(b'\xff\x07', self.profile)
        self.assertEqual(reader.read({'kind': 5}), 255)
        self.assertEqual(reader.uvar(), 7)

    def test_json_enum_reads_two_signed_int32_values(self):
        reader = groups.Reader(sint(20) + sint(-1), self.profile)
        self.assertEqual(reader.read({'kind': 18, 'name': 'JsonEnum'}), {'EnumIndex': 20, 'Value': -1})
        with self.assertRaises(ValueError):
            groups.Reader(sint(1 << 31) + sint(0), self.profile).read({'kind': 18, 'name': 'JsonEnum'})

    def test_vector2_is_raw_float32_and_rejects_truncation_or_nan(self):
        reader = groups.Reader(struct.pack('<2f', 1.5, -3.25), self.profile)
        self.assertEqual(reader.read({'kind': 17, 'name': 'MVector2'}), {'x': 1.5, 'y': -3.25})
        for data in (b'\x00', struct.pack('<2f', float('nan'), 0.0)):
            with self.subTest(data=data), self.assertRaises(ValueError):
                groups.Reader(data, self.profile).read({'kind': 17, 'name': 'MVector2'})

    def test_empty_anchor_base_still_consumes_its_mask(self):
        reader = groups.Reader(b'\x00\x00\xff', self.profile)
        anchor = reader.read({'kind': 18, 'name': 'LevelAnchorParameter'})
        self.assertEqual(anchor['$variantId'], 0)
        self.assertEqual(reader.pos, 2)
        self.assertEqual(reader.read({'kind': 5}), 255)

    def test_npc_nearby_event_uses_factory_tag_before_field_mask(self):
        reader = groups.Reader(b'\x02\x00\x7b', self.profile)
        nearby = reader.read({'kind': 18, 'name': 'LevelNPCNearbyConfig'})
        self.assertEqual(nearby['$type'], 'LevelNPCNearbyEventConfig')
        self.assertEqual(nearby['$variantId'], 2)
        self.assertEqual(reader.uvar(), 123)

    def test_shared_signed_integer_preserves_key_and_negative_value(self):
        reader = groups.Reader(b'\x03\x03\x03key' + sint(-7), self.profile)
        value = reader.read({'kind': 18, 'name': 'SharedValue'})
        self.assertEqual((value['$type'], value['Key'], value['Value']), ('SharedInt', 'key', -7))

    def test_unknown_factory_tags_fail_for_every_supported_family(self):
        for name in groups.TABLES:
            with self.subTest(name=name), self.assertRaises(ValueError):
                groups.Reader(b'\x7f', self.profile).read({'kind': 18, 'name': name})

    def test_dictionary_duplicate_keys_are_rejected(self):
        description = {'kind': 21, 'dictionary': [{'kind': 9}, {'kind': 5}]}
        with self.assertRaises(ValueError):
            groups.Reader(sint(2) + b'\x01\x04\x01\x05', self.profile).read(description)

    def test_unidentified_generic_container_is_rejected_even_when_empty(self):
        with self.assertRaises(ValueError):
            groups.Reader(b'\x00', self.profile).read({'kind': 21})

    def test_effect_dictionary_accepts_only_the_verified_empty_encoding(self):
        description = {'kind': 21, 'emptyDictionaryOnly': True}
        self.assertEqual(groups.Reader(b'\x00', self.profile).read(description), {'$entries': []})
        for value in (-1, 1, 4097):
            with self.subTest(value=value), self.assertRaises(ValueError):
                groups.Reader(sint(value), self.profile).read(description)

    def test_dictionary_uint32_keys_and_signed_capacity_bounds(self):
        description = {'kind': 21, 'dictionary': [{'kind': 9}, {'kind': 5}]}
        value = groups.Reader(sint(1) + uvar((1 << 32) - 1) + b'\xff', self.profile).read(description)
        self.assertEqual(value, {'$entries': [[(1 << 32) - 1, 255]]})
        for data in (sint(4097), sint(-1), sint(1) + uvar(1 << 32)):
            with self.subTest(data=data), self.assertRaises(ValueError):
                groups.Reader(data, self.profile).read(description)

    def test_same_name_with_wrong_native_type_index_is_rejected(self):
        with self.assertRaises(ValueError):
            groups.Reader(b'\x00', self.profile).read({'kind': 18, 'name': 'LevelPropInfo', 'typeIndex': 1})

    def test_json_conversion_refuses_unknown_enum_and_unverified_type_name(self):
        with self.assertRaises(ValueError):
            groups.resource_json(999, self.profile, {'kind': 17, 'typeIndex': 10527})
        with self.assertRaises(ValueError):
            groups.resource_json({'$type': 'AudienceGroupMemberInfo', '$variantId': 2}, self.profile)

    def test_text_id_json_retains_the_existing_unsigned_hash_field(self):
        value = groups.resource_json({'id': -1, 'hash': (1 << 64) - 1}, self.profile,
                                     {'kind': 17, 'name': 'TextID'})
        self.assertEqual(value, {'Hash': (1 << 64) - 1})

    def test_late_output_conflict_prevents_every_new_file(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            late = root / 'resource-supplement.json'
            late.write_bytes(b'operator data')
            with self.assertRaises(FileExistsError):
                groups.write_new_files({root / 'new.json': b'new', late: b'replacement'})
            self.assertFalse((root / 'new.json').exists())
            self.assertEqual(late.read_bytes(), b'operator data')

    def test_identical_outputs_are_reused_without_rewriting(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'new.json'
            self.assertEqual(groups.write_new_files({path: b'new'}), 1)
            timestamp = path.stat().st_mtime_ns
            self.assertEqual(groups.write_new_files({path: b'new'}), 0)
            self.assertEqual(path.stat().st_mtime_ns, timestamp)

    def test_client_outputs_and_unknown_source_paths_are_refused_early(self):
        with tempfile.TemporaryDirectory() as directory:
            client = Path(directory) / 'client'
            args = SimpleNamespace(client_dir=client, paths=[], destination=client / 'output',
                                   output=Path(directory) / 'report.json')
            with self.assertRaises(ValueError):
                groups.recover(args)
            args.destination = None
            args.paths = ['../outside.json']
            with self.assertRaises(ValueError):
                groups.recover(args)
            self.assertFalse(args.output.exists())


@unittest.skipUnless((CLIENT / 'GameAssembly.dll').is_file(), '固定本地客户端不可用')
class RuntimeGroupNativeTests(unittest.TestCase):
    def test_all_169_original_records_and_additive_install_are_repeatable(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            destination = root / 'resources'
            destination.mkdir()
            manifest = destination / 'resource-manifest.json'
            manifest.write_bytes(b'operator manifest')
            args = SimpleNamespace(client_dir=CLIENT, paths=[], destination=destination,
                                   output=root / 'report.json')
            report = groups.recover(args)
            self.assertEqual(len(report['records']), 169)
            self.assertTrue(all(row['fullyConsumed'] for row in report['records']))
            self.assertFalse(report['clientAccepted'])
            self.assertFalse(report['clientModified'])
            self.assertFalse(report['completeGameplay'])
            self.assertFalse(report['scriptsExecuted'])
            before = {path: path.stat().st_mtime_ns for path in destination.rglob('*.json')}
            self.assertEqual(report, groups.recover(args))
            self.assertEqual(before, {path: path.stat().st_mtime_ns for path in before})
            self.assertEqual(manifest.read_bytes(), b'operator manifest')

    def test_original_control_resources_match_all_fields_types_and_float32_values(self):
        profile = groups.load_profile()
        with tempfile.TemporaryDirectory() as directory:
            report = groups.recover(SimpleNamespace(client_dir=CLIENT, destination=None,
                                    paths=list(profile['controlSourceSHA256']),
                                    output=Path(directory) / 'report.json'))

        def compare(actual, expected):
            if isinstance(actual, dict):
                self.assertIsInstance(expected, dict)
                self.assertEqual(set(actual), set(expected))
                for key in actual:
                    compare(actual[key], expected[key])
            elif isinstance(actual, list):
                self.assertIsInstance(expected, list)
                self.assertEqual(len(actual), len(expected))
                for left, right in zip(actual, expected):
                    compare(left, right)
            elif isinstance(actual, float):
                self.assertEqual(struct.pack('<f', actual), struct.pack('<f', expected))
            else:
                self.assertEqual(type(actual), type(expected))
                self.assertEqual(actual, expected)

        for row in report['records']:
            def normalize(value):
                if isinstance(value, dict):
                    return {key: normalize(item) for key, item in value.items()}
                if isinstance(value, list):
                    return [normalize(item) for item in value]
                if isinstance(value, (int, float)) and not isinstance(value, bool):
                    text = format(struct.unpack('<f', struct.pack('<f', value))[0], '.9g') \
                        if isinstance(value, float) else str(value)
                    return {'$numeric': text}
                return value

            canonical = json.dumps(normalize(row['resource']), ensure_ascii=False,
                                   sort_keys=True, separators=(',', ':')).encode('utf-8')
            self.assertEqual(hashlib.sha256(canonical).hexdigest(),
                             profile['controlSemanticSHA256'][row['sourcePath']])
            path = ROOT / 'resources/4.6' / row['sourcePath']
            if path.is_file():
                self.assertEqual(hashlib.sha256(path.read_bytes()).hexdigest(),
                                 profile['controlSourceSHA256'][row['sourcePath']])
                with self.subTest(path=row['sourcePath']):
                    compare(row['resource'], json.loads(path.read_text('utf-8')))


if __name__ == '__main__':
    unittest.main()
