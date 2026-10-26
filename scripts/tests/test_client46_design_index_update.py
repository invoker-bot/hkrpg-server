"""资源热更新后显式选择已核对索引；旧图读取器继续保留原来的来源保护。"""
import hashlib
import importlib.util
import json
import tempfile
import unittest
from pathlib import Path
from types import SimpleNamespace

ROOT = Path(__file__).resolve().parents[2]
CLIENT = Path(r'C:\Users\InvokerBot\AppData\Local\hoyo\hkrpg\versions\current')
UPDATED_INDEX = '5a5981c06b0d7d1cbff5f89b8450fefb40278c60a5ca10b092b3381094dfcbed'
COMPAT_INDEX = 'bcac71ed6783dfd447de127bdcab7c40ae4e21d488c39cba1af6ef6aa801b608'
active_index = CLIENT / 'StarRail_Data/Persistent/DesignData/Windows/DesignV_702d7f1e6dc48d92b85f9ae4ca2b139b.bytes'
if active_index.is_file() and hashlib.sha256(active_index.read_bytes()).hexdigest() == COMPAT_INDEX:
    UPDATED_INDEX = COMPAT_INDEX
spec = importlib.util.spec_from_file_location('updated_design', ROOT / 'scripts/read-client-design-data.py')
design = importlib.util.module_from_spec(spec)
spec.loader.exec_module(design)


@unittest.skipUnless((CLIENT / 'GameAssembly.dll').is_file(), '没有固定本机客户端')
class UpdatedDesignIndexTest(unittest.TestCase):
    def updated_index(self):
        directory = CLIENT / 'StarRail_Data/Persistent/DesignData/Windows'
        return next(path for path in directory.glob('DesignV_*.bytes')
                    if hashlib.sha256(path.read_bytes()).hexdigest() == UPDATED_INDEX)

    def test_explicit_update_reads_same_record_and_reports_actual_index(self):
        with tempfile.TemporaryDirectory() as temp:
            directory = Path(temp)
            args = SimpleNamespace(client_dir=CLIENT, paths=[
                'Config/Level/GroupGraph/F10301001/Group_F10301001_G156.json'],
                paths_file=None, extract_dir=directory / 'raw', output=directory / 'report.json',
                index_sha256=UPDATED_INDEX)
            report = design.extract(args)
            self.assertEqual(UPDATED_INDEX, report['indexSHA256'])
            self.assertEqual(UPDATED_INDEX, hashlib.sha256(Path(report['index']).read_bytes()).hexdigest())
            self.assertEqual(141295, report['indexEntryCount'])
            self.assertTrue(report['indexFullyConsumed'])
            row = report['records'][0]
            self.assertEqual(('read', 401, '545bf4e3ea66d096bca1cdf4ae4d1cb82a45e21cd286f612d8c64079d25ade44'),
                             (row['status'], row['length'], row['sha256']))
            self.assertEqual(row['sha256'], hashlib.sha256(Path(row['extractedFile']).read_bytes()).hexdigest())
            self.assertEqual(report, json.loads(args.output.read_text(encoding='utf-8')))
            self.assertFalse(report['clientModified'])
            self.assertFalse(report['clientAccepted'])

    def test_legacy_default_does_not_implicitly_accept_updated_index(self):
        with tempfile.TemporaryDirectory() as temp:
            directory = Path(temp)
            (directory / 'DesignV_update.bytes').write_bytes(self.updated_index().read_bytes())
            with self.assertRaisesRegex(ValueError, 'SHA256'):
                design.parse_index(directory)

    def test_multiple_exact_candidates_are_rejected(self):
        with tempfile.TemporaryDirectory() as temp:
            directory = Path(temp)
            raw = self.updated_index().read_bytes()
            for name in ('DesignV_a.bytes', 'DesignV_b.bytes'):
                (directory / name).write_bytes(raw)
            with self.assertRaisesRegex(ValueError, 'SHA256'):
                design.parse_index(directory, index_sha256=UPDATED_INDEX)

    def test_unknown_or_modified_index_is_rejected_before_any_export(self):
        original = self.updated_index()
        with tempfile.TemporaryDirectory() as temp:
            directory = Path(temp)
            raw = bytearray(original.read_bytes())
            raw[-1] ^= 1
            (directory / original.name).write_bytes(raw)
            with self.assertRaisesRegex(ValueError, 'SHA256'):
                design.parse_index(directory, index_sha256=UPDATED_INDEX)
            with self.assertRaisesRegex(ValueError, 'SHA256'):
                design.parse_index(directory, index_sha256=hashlib.sha256(raw).hexdigest())


if __name__ == '__main__':
    unittest.main()
