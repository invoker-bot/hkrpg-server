"""Meaningful source and actual hardlink isolation guards; no server is started."""
import base64
import copy
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
import subprocess
import shutil
import zlib

TOOLS=Path(__file__).resolve().parents[1]
spec=importlib.util.spec_from_file_location('selected_native_under_test',TOOLS/'smoke-daily-selected-item.py')
tool=importlib.util.module_from_spec(spec);spec.loader.exec_module(tool)

class GuardTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.fixture=json.loads(tool.source_path().read_text('utf-8'))
        cls.raw={path:base64.b64decode(entry['sourceBlobBase64'])if'sourceBlobBase64'in entry else zlib.decompress(base64.b64decode(entry['sourceBlobDeflateBase64']))for path,entry in cls.fixture['sourceFiles'].items()}
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory(prefix='selected-native-guard-');self.addCleanup(self.temp.cleanup)
        self.root=Path(self.temp.name);self.source=self.root/'source';self.source.mkdir()
        for path,raw in self.raw.items():
            if path in tool.SOURCE_ONLY:continue
            target=self.source/path;target.parent.mkdir(parents=True,exist_ok=True);target.write_bytes(raw)
    def test_complete_source_and_two_missing_dialogue_graphs_are_valid(self):
        blobs,hashes,proof=tool.preflight(self.source,self.fixture)
        self.assertEqual(34,len(blobs));self.assertEqual(34,len(hashes));self.assertEqual({path:'absent'for path in tool.SOURCE_ONLY},proof['sourceOnlyStatus'])
    def test_later_present_complete_equal_source_only_graph_is_read_only_evidence(self):
        path=sorted(tool.SOURCE_ONLY)[0];target=self.source/path;target.parent.mkdir(parents=True);target.write_bytes(self.raw[path])
        blobs,hashes,proof=tool.preflight(self.source,self.fixture)
        self.assertNotIn(path,blobs);self.assertIn(path,hashes);self.assertEqual('present_matching',proof['sourceOnlyStatus'][path]);self.assertFalse(proof['sourceOnlyInstalledByTool'])
    def test_altered_source_only_whole_graph_is_rejected(self):
        path=sorted(tool.SOURCE_ONLY)[0];target=self.source/path;target.parent.mkdir(parents=True);target.write_text('{}','utf-8')
        with self.assertRaisesRegex(AssertionError,'Complete original differs'):tool.preflight(self.source,self.fixture)
    def test_real_whole_floor_change_outside_the_chosen_npc_is_rejected(self):
        path='Config/LevelOutput/RuntimeFloor/P20122_F20122001.json';value=json.loads(self.raw[path]);value['OutsideChosenNPCRegressionMarker']=1
        (self.source/path).write_text(json.dumps(value),'utf-8')
        with self.assertRaisesRegex(AssertionError,'Complete original differs'):tool.preflight(self.source,self.fixture)
    def test_booleans_cannot_match_original_numeric_fields(self):
        path='ExcelOutput/DailyMissionCount.json';value=json.loads(self.raw[path]);value[0]['ID']=True
        (self.source/path).write_text(json.dumps(value),'utf-8')
        with self.assertRaisesRegex(AssertionError,'Complete original differs'):tool.preflight(self.source,self.fixture)
    def test_fixed_blob_integrity_must_precede_installed_source_acceptance(self):
        fixture=copy.deepcopy(self.fixture);fixture['sourceFiles']['ExcelOutput/DailyMissionData.json']['sourceBlobSHA256']='0'*64
        with self.assertRaises(AssertionError):tool.preflight(self.source,fixture)
    def test_missing_required_partial_cannot_be_treated_as_optional_dialogue(self):
        (self.source/'Config/Level/Mission/3011605/MissionInfo_3011605.partial.json').unlink()
        with self.assertRaisesRegex(AssertionError,'Required complete source absent'):tool.preflight(self.source,self.fixture)
    def test_detached_fixture_write_preserves_actual_operator_hardlink_bytes(self):
        fixture=self.root/'fixture';fixture.mkdir();target=fixture/'ExcelOutput/DailyMissionData.json';target.parent.mkdir()
        original=self.source/'ExcelOutput/DailyMissionData.json';tool.os.link(original,target);before=original.read_bytes()
        self.assertTrue(tool.os.path.samefile(original,target));tool.detached_write(target,b'[]',fixture)
        self.assertFalse(tool.os.path.samefile(original,target));self.assertEqual(before,original.read_bytes());self.assertEqual(b'[]',target.read_bytes())
        with self.assertRaisesRegex(AssertionError,'escaped'):tool.detached_write(self.root/'outside.json',b'[]',fixture)
    def test_materialized_protected_paths_stay_in_fixture_without_junctions(self):
        source=self.root/'small-source';source.mkdir();(source/'Other').mkdir();(source/'Other/owned-read-only.json').write_text('{}','utf-8')
        files={'ExcelOutput/DailyMissionData.json':json.dumps([{'ID':3011605},{'ID':3011608}]).encode(),
          'ExcelOutput/DailyActiveQuestPool.json':json.dumps([{'QuestID':2100003}]).encode(),
          'Config/Level/Mission/3011605/MissionInfo_3011605.partial.json':b'{}'}
        for path,raw in files.items():target=source/path;target.parent.mkdir(parents=True,exist_ok=True);target.write_bytes(raw)
        before={path:(source/path).read_bytes()for path in files};fixture=self.root/'small-fixture'
        view=tool.materialize(source,fixture,files,3011605)
        self.assertEqual(0,view['directoryJunctionCount']);self.assertFalse(view['sourceOnlyInstalled'])
        self.assertEqual([{'ID':3011605}],json.loads((fixture/'ExcelOutput/DailyMissionData.json').read_text('utf-8')))
        self.assertTrue(all((fixture/path).resolve().is_relative_to(fixture.resolve())for path in files))
        self.assertTrue(all(before[path]==(source/path).read_bytes()for path in files))

class RealEntrancePrerequisiteGuard(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temp=tempfile.TemporaryDirectory(prefix='selected-entrance-guard-');cls.addClassCleanup(cls.temp.cleanup)
        java=next((tool.ROOT/'.tools').glob('jdk-21*/bin/java.exe'))
        jar=tool.ROOT/'build/feixiao-selected-item-blessing-console-candidate-runtime.jar'
        classes=Path(cls.temp.name)/'classes';classes.mkdir()
        subprocess.run([str(java.with_name('javac.exe')),'-encoding','UTF-8','-cp',str(jar),'-d',str(classes),str(tool.JAVA/'emu/lunarcore/command/commands/SelectedItemFixtureCommand.java')],check=True,capture_output=True)
        result=subprocess.run([str(java),'-cp',str(jar)+tool.os.pathsep+str(classes),'emu.lunarcore.command.commands.SelectedItemFixtureCommand','--prerequisite-plan',str(tool.ROOT/'resources/4.6')],check=True,capture_output=True,text=True,encoding='utf-8')
        cls.actual=json.loads(result.stdout)
    def test_original_2012201_entrance_does_not_lack_1010700_history(self):
        self.assertEqual([1010201,1010403,1011501,1010700],self.actual['3011605']['main'])
        self.assertEqual([],self.actual['3011605']['sub'])
    def test_original_2013201_entrance_does_not_lack_1011202_and_101120201_history(self):
        self.assertEqual([1010201,1010403,1011501,1011202],self.actual['3011608']['main'])
        self.assertEqual([101120201],self.actual['3011608']['sub'])

class ActualStoredRewardKeyGuard(unittest.TestCase):
    def test_actual_first_day_stored_original_reward_is_not_rejected_as_absent(self):
        with tempfile.TemporaryDirectory(prefix='selected-reward-shape-guard-',dir=tool.ROOT/'build')as directory:
            java=next((tool.ROOT/'.tools').glob('jdk-21*/bin/java.exe'));jar=tool.ROOT/'build/feixiao-selected-item-blessing-console-candidate-runtime.jar';classes=Path(directory)/'classes';classes.mkdir()
            subprocess.run([str(java.with_name('javac.exe')),'-encoding','UTF-8','-cp',str(jar),'-d',str(classes),str(tool.JAVA/'org/hkrpg/server/gameplay/SelectedItemFixtureInspector.java')],check=True,capture_output=True)
            source=Path(directory)/'actual-stored-shape.json';shutil.copyfile(TOOLS/'fixtures/daily-selected-item-stored-reward-shape-4.6.json',source)
            result=subprocess.run([str(java),'-cp',str(jar)+tool.os.pathsep+str(classes),'org.hkrpg.server.gameplay.SelectedItemFixtureInspector','--verify-stored-reward',str(source)],capture_output=True,text=True,encoding='utf-8')
            self.assertEqual(0,result.returncode,'The actual stored 231/{2:5000} reward must match its existing first-period key: '+result.stderr)

class OriginalEntranceDefinitionMirrorGuard(unittest.TestCase):
    def test_original_101120201_history_has_its_real_full_partial_and_cross_folder_script(self):
        extra,proof=tool.prerequisite_blobs(3011608,tool.ROOT/'resources/4.6')
        required={'Config/Level/Mission/1011202/MissionInfo_1011202.json',
          'Config/Level/Mission/1011202/MissionInfo_1011202.partial.json',
          'Config/Level/Mission/1011202/MissionInfo_1011202.partial.layout.json',
          'Config/Level/Mission/1011201/Mission_101120201.json'}
        self.assertEqual(required,set(extra),'Original entrance completion history cannot be left as an unknown sub in the real query')
        self.assertEqual(4,proof['additionalNativePrerequisiteOriginalFiles'])

class ActualNormalStopLogGuard(unittest.TestCase):
    def test_actual_color_formatted_info_marker_still_proves_one_normal_stop(self):
        spec=importlib.util.spec_from_file_location('actual_native_audit',TOOLS/'verify-daily-selected-item-native.py');audit=importlib.util.module_from_spec(spec);spec.loader.exec_module(audit)
        actual_stored_line='> [\x1b[36m10:04:49\x1b[0;39m] [\x1b[34mINFO\x1b[0;39m] Stopping server'
        self.assertEqual(1,audit.normal_stops(actual_stored_line))

if __name__=='__main__':unittest.main(verbosity=2)
