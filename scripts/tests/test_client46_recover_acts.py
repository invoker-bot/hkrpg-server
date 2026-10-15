"""完整原始演出图与拒绝边界；不以此代替客户端执行。"""
import base64
import importlib.util
import json
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location('verify_recover_acts', ROOT / 'scripts/verify-client-recover-acts.py')
acts = importlib.util.module_from_spec(spec)
spec.loader.exec_module(acts)


class RecoverActsTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.profile = acts.load_profile()
        cls.originals = acts.originals()
        fixture = json.loads((ROOT / 'scripts/tests/fixtures/client46-recover-acts.json').read_text())
        cls.records = {row['path']: base64.b64decode(row['binaryBase64'], validate=True)
                       for row in fixture['records']}

    def test_eight_complete_binary_records_equal_original_whole_objects(self):
        self.assertEqual(set(self.originals), set(self.records))
        self.assertEqual(8, len(self.records))
        self.assertEqual(4176, sum(map(len, self.records.values())))
        for path, raw in self.records.items():
            with self.subTest(path=path):
                pinned = self.profile['records'][path]
                self.assertEqual((pinned['length'], pinned['sha256']), (len(raw), acts.checksum(raw)))
                value = acts.g61.resource_json(acts.g61.decode_graph(raw, self.profile), self.profile)
                self.assertEqual(json.loads(self.originals[path]), value)

    def test_all_eight_truncated_and_extended_records_are_rejected(self):
        for path, raw in self.records.items():
            for damaged in (raw[:-1], raw + b'\0'):
                with self.subTest(path=path, length=len(damaged)), self.assertRaises(ValueError):
                    acts.g61.decode_graph(damaged, self.profile)

    def test_unknown_task_and_unknown_field_mask_are_rejected(self):
        reader = acts.g61.Reader(b'\x80\x40', self.profile)
        with self.assertRaisesRegex(ValueError, '未知任务'):
            reader.read({'kind': 18, 'name': 'TaskConfig'})
        reader = acts.g61.Reader(b'\x40', self.profile)
        with self.assertRaisesRegex(ValueError, '未知字段掩码'):
            reader.obj('CaptureNPCToCharacter')

    def test_only_two_exact_original_noninteger_literals_are_allowed(self):
        for raw, expected in [(4294967300, 0.5), (6012954220, 0.7)]:
            value = {'$type': 'DynamicFloat', '$mode': 0, 'fixedRawSigned': raw}
            self.assertEqual({'IsDynamic': False, 'FixedValue': {'Value': expected}},
                             acts.g61.resource_json(value, self.profile))
            with self.assertRaises(ValueError):
                acts.g61.resource_json(value, acts.g61.load_profile())
            with self.assertRaises(ValueError):
                acts.g61.resource_json(value | {'fixedRawSigned': raw + 1}, self.profile)
            with self.assertRaises(ValueError):
                acts.g61.resource_json(value | {'$mode': 1}, self.profile)

    def test_existing_report_and_client_output_are_rejected(self):
        with tempfile.TemporaryDirectory() as temp:
            directory = Path(temp)
            client = directory / 'client'
            client.mkdir()
            output = directory / 'report.json'
            output.write_bytes(b'previous evidence')
            with self.assertRaises(FileExistsError):
                acts.verify(client, directory, output)
            self.assertEqual(b'previous evidence', output.read_bytes())
            with self.assertRaisesRegex(ValueError, '客户端目录'):
                acts.verify(client, directory, client / 'report.json')


if __name__ == '__main__':
    unittest.main()
