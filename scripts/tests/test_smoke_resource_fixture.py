"""用真实独立文件复现说明文件硬链接误写，禁止覆盖原始资源。"""
import hashlib
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest

ROOT=Path(__file__).resolve().parents[2]
SPEC=importlib.util.spec_from_file_location('smoke_resource_fixture',ROOT/'scripts/smoke_resource_fixture.py')
MODULE=importlib.util.module_from_spec(SPEC);SPEC.loader.exec_module(MODULE)

class SmokeResourceFixtureTests(unittest.TestCase):
    def setUp(self):
        base=ROOT/'build';base.mkdir(exist_ok=True)
        self.temporary=tempfile.TemporaryDirectory(prefix='smoke-fixture-tests-',dir=base)
        self.directory=Path(self.temporary.name).resolve();self.assertTrue(self.directory.is_relative_to(base.resolve()))
        self.source=self.directory/'original';self.source.mkdir();self.fixture=self.directory/'isolated'
        self.manifest=self.source/'resource-manifest.json';self.original=b'{"clientVersion":"4.6.0","operatorMetadata":"preserve"}\n';self.manifest.write_bytes(self.original)
        (self.source/'ExcelOutput').mkdir();(self.source/'ExcelOutput/MainMission.json').write_text('[{"ID":1}]','utf-8')
        mission=self.source/'Config/Level/Mission/1';mission.mkdir(parents=True);(mission/'MissionInfo_1.json').write_text('{"MainMissionID":1}','utf-8')
        self.junctions=MODULE.mirror_resources(self.source,self.fixture,{'ExcelOutput/MainMission.json':[{'ID':2}]})
    def tearDown(self):self.temporary.cleanup()
    def test_writing_fixture_manifest_preserves_original_bytes_and_file_identity(self):
        target=self.fixture/'resource-manifest.json';target.write_text('{"testFixture":true}','utf-8')
        self.assertEqual(self.original,self.manifest.read_bytes())
        self.assertFalse(target.samefile(self.manifest))
        self.assertEqual(hashlib.sha256(self.original).hexdigest(),hashlib.sha256(self.manifest.read_bytes()).hexdigest())
    def test_replacing_mission_table_never_writes_the_source_table(self):
        self.assertEqual([{'ID':1}],json.loads((self.source/'ExcelOutput/MainMission.json').read_text('utf-8')))
        self.assertEqual([{'ID':2}],json.loads((self.fixture/'ExcelOutput/MainMission.json').read_text('utf-8')))
        self.assertFalse((self.fixture/'ExcelOutput/MainMission.json').samefile(self.source/'ExcelOutput/MainMission.json'))
    def test_isolated_mission_directory_starts_empty_and_keeps_operator_mission(self):
        self.assertEqual([],list((self.fixture/'Config/Level/Mission').iterdir()))
        self.assertTrue((self.source/'Config/Level/Mission/1/MissionInfo_1.json').is_file())

if __name__=='__main__':unittest.main()
