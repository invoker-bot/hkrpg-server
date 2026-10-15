"""设计数据路径与只读导出的边界；实际记录测试依赖指定的本机客户端。"""
import importlib.util
import json
import tempfile
import unittest
from pathlib import Path
from types import SimpleNamespace

ROOT = Path(__file__).resolve().parents[2]
CLIENT = Path(r'C:\Users\InvokerBot\AppData\Local\hoyo\hkrpg\versions\current')
UPDATED_INDEX = '5a5981c06b0d7d1cbff5f89b8450fefb40278c60a5ca10b092b3381094dfcbed'
spec = importlib.util.spec_from_file_location('read_design', ROOT / 'scripts/read-client-design-data.py')
design = importlib.util.module_from_spec(spec)
spec.loader.exec_module(design)

PATHS = {
    'Config/Level/GroupGraph/F10301001/Group_F10301001_G156.json':
        ('c573f72069e3ac91', 401, '545bf4e3ea66d096bca1cdf4ae4d1cb82a45e21cd286f612d8c64079d25ade44'),
    'Config/Level/Props/Chap03/InitLevelGraph_Prop_Chap03_CartoonAmmo_01.json':
        ('a0c6286e22dcb213', 502, '84fdad0f0cfaaaed602accc12756feb9cb6a3300755f8f696235de283dc4f8f8'),
    'Config/Level/Props/Chap03/InitLevelGraph_Prop_Chap03_CartoonAmmo_02.json':
        ('de6c37b0c3cbe5f2', 503, '39b1fdf40c360faca67d0fb5d528cd0e31ac1c9b8ccd69d7863a5201e09c3594'),
}


class Client46DesignPathsTest(unittest.TestCase):
    def test_xxhash_vectors(self):
        self.assertEqual(0xef46db3751d8e999, design.path_hash(b''))
        self.assertEqual(0xd24ec4f1a98c6e5b, design.path_hash(b'a'))

    def test_native_baked_paths_match_exact_index_keys(self):
        for path, expected in PATHS.items():
            baked = 'BakedConfig/' + str(design.source_path(path).with_suffix('.bytes'))
            self.assertEqual(expected[0], design.path_hash(baked.encode()).to_bytes(8, 'big').hex())

    def test_native_stage_game_core_paths_match_the_three_actual_table_records(self):
        for name, expected in [('StageConfig', '4efae292630058fd'),
                               ('StageTestConfig', '02e3900fff97c0cf'),
                               ('StageConfigLD', '1480f80c46f4c751')]:
            path = design.source_path('ExcelOutputGameCore/' + name + '.json')
            baked = 'BakedConfig/' + str(path.with_suffix('.bytes'))
            self.assertEqual(expected, design.path_hash(baked.encode()).to_bytes(8, 'big').hex())

    def test_noncanonical_and_external_paths_are_rejected(self):
        for path in ('../Config/a.json', 'Config/../a.json', 'C:/Config/a.json', '/Config/a.json',
                     'Config\\a.json', 'Config//a.json', 'Config/a.bytes', 'Database/a.json'):
            with self.assertRaises(ValueError):
                design.source_path(path)

    def test_client_directory_is_never_an_output_target(self):
        with tempfile.TemporaryDirectory() as directory:
            client = Path(directory).resolve()
            with self.assertRaises(ValueError):
                design.outside_client(client / 'output/result.json', client)


@unittest.skipUnless((CLIENT / 'GameAssembly.dll').is_file(), '没有固定的本机客户端，跳过实际设计数据读取')
class Client46DesignRecordsTest(unittest.TestCase):
    def arguments(self, directory):
        return SimpleNamespace(client_dir=CLIENT, paths=list(PATHS), paths_file=None,
                               extract_dir=directory / 'raw', output=directory / 'manifest.json',
                               index_sha256=UPDATED_INDEX)

    def test_exact_records_are_read_and_existing_identical_exports_are_reused(self):
        with tempfile.TemporaryDirectory() as directory:
            args = self.arguments(Path(directory))
            first = design.extract(args)
            for row in first['records']:
                expected = PATHS[row['sourcePath']]
                self.assertEqual((expected[0], expected[1], expected[2]),
                                 (row['entryKey'], row['length'], row['sha256']))
                self.assertEqual('read', row['status'])
            second = design.extract(args)
            self.assertEqual(first, second)
            self.assertFalse(second['clientModified'])
            self.assertFalse(second['clientExecutableStarted'])
            self.assertFalse(second['clientAccepted'])
            self.assertFalse(second['jsonInstalled'])
            self.assertEqual(141295, second['indexEntryCount'])
            self.assertEqual(1, len(second['verifiedChunks']))

    def test_different_existing_exports_stop_before_any_other_record_is_written(self):
        with tempfile.TemporaryDirectory() as directory:
            args = self.arguments(Path(directory))
            source = next(iter(PATHS))
            existing = args.extract_dir.joinpath(*design.source_path(source).with_suffix('.bytes').parts)
            existing.parent.mkdir(parents=True)
            existing.write_bytes(b'operator-owned-data')
            with self.assertRaises(FileExistsError):
                design.extract(args)
            self.assertEqual(b'operator-owned-data', existing.read_bytes())
            self.assertEqual([existing], list(args.extract_dir.rglob('*.bytes')))
            self.assertFalse(args.output.exists())


if __name__ == '__main__':
    unittest.main()
