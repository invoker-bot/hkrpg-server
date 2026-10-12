"""Meaningful scope/source guards for the new single original sequel lane; no server startup."""
import base64
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
import zlib

OUT=Path(__file__).resolve().parent
spec=importlib.util.spec_from_file_location('followup_native_guard',OUT.parent/'smoke-daily-followup.py');tool=importlib.util.module_from_spec(spec);spec.loader.exec_module(tool)
MISSING='Config/Level/NPCDialogue/P20002/F20002001_G85/DialogueMain_F20002001_G85_N400001.json'

class FollowupNativeGuards(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.fixture=tool.source_path();cls.data=json.loads(cls.fixture.read_text('utf-8'));cls.raw={}
        for relative,metadata in cls.data['sourceFiles'].items():
            cls.raw[relative]=base64.b64decode(metadata['sourceBlobBase64'])if 'sourceBlobBase64'in metadata else zlib.decompress(base64.b64decode(metadata['sourceBlobDeflateBase64']))
    def copied(self,directory):
        root=Path(directory)
        for relative,raw in self.raw.items():
            if relative==MISSING:continue
            target=root/relative;target.parent.mkdir(parents=True,exist_ok=True);target.write_bytes(raw)
        return root
    def test_declared_lane_has_seven_processes_eight_snapshots_and_only_one_actual_day(self):
        steps=tool.schedule('settled');self.assertEqual(7,len(steps));self.assertEqual(8,sum(map(len,steps)))
        self.assertEqual({1},{day for group in steps for day,phase in group})
        self.assertEqual([0,0,0,1,1,2,3,3],[tool.followup_stage(phase)for group in steps for day,phase in group])
    def test_unknown_phase_cannot_be_mislabeled_as_no_accepted_sequel(self):
        with self.assertRaises(AssertionError):tool.followup_stage('sequel-finished-typo')
    def test_same_day_sequel_keeps_real_completed_parent_phone_history(self):
        for phase in ('sequel-pending','sequel-entered','sequel-complete','sequel-restored'):
            self.assertEqual(13,tool.snapshot_phone_size(dict(phase=phase,dayIndex=1),'settled','oneReply'))
    def test_native_path_labels_do_not_count_ten_npc_messages_as_player_replies(self):
        history=[121140301,121140316,121140317,121140302,121140304,121140305,121140306,121140307,121140310,121140311,121140313,121140314,121140315]
        case=dict(caseIndex=0,branch='302-306-311',readOnlyPhoneH2Inspections=[dict(currentRun=dict(history=history),followup=dict(stage=3))])
        result=tool.observed_case_paths(case)
        self.assertEqual([121140302,121140306,121140311],result['actualParentReplies'])
        self.assertEqual(history,result['actualParentHistory'])
    def test_bad_primary_fixture_is_not_silently_replaced_by_valid_fallback(self):
        with tempfile.TemporaryDirectory(dir=tool.ROOT/'build')as directory:
            primary=Path(directory)/'bad.json';primary.write_text('{}','utf-8')
            self.assertEqual(primary,tool.source_path(primary,self.fixture))
            with self.assertRaises((KeyError,AssertionError)):tool.source_preflight(Path(directory),tool.source_path(primary,self.fixture))
    def test_complete_original_fixture_and_absent_source_only_graph_are_read_only(self):
        with tempfile.TemporaryDirectory(dir=tool.ROOT/'build')as directory:
            root=self.copied(directory);before={p.relative_to(root).as_posix():tool.sha(p)for p in root.rglob('*')if p.is_file()}
            _,hashes,proof=tool.source_preflight(root,self.fixture)
            self.assertEqual(22,len(hashes));self.assertEqual({MISSING:'absent'},proof['sourceOnlyStatus']);self.assertFalse(proof['sourceOnlyInstalledByTool'])
            self.assertEqual(before,{p.relative_to(root).as_posix():tool.sha(p)for p in root.rglob('*')if p.is_file()})
    def test_source_only_complete_present_matching_stays_excluded_from_install_copy(self):
        with tempfile.TemporaryDirectory(dir=tool.ROOT/'build')as directory:
            root=self.copied(directory);target=root/MISSING;target.parent.mkdir(parents=True,exist_ok=True);target.write_bytes(self.raw[MISSING])
            blobs,hashes,proof=tool.source_preflight(root,self.fixture)
            self.assertEqual({MISSING:'present_matching'},proof['sourceOnlyStatus']);self.assertNotIn(MISSING,blobs);self.assertNotIn(MISSING,hashes)
            self.assertFalse(proof['sourceOnlyInstalledByTool'])
    def test_whole_original_act_change_cannot_borrow_its_unchanged_talk_key(self):
        with tempfile.TemporaryDirectory(dir=tool.ROOT/'build')as directory:
            root=self.copied(directory);path=root/'Config/Level/Mission/3000604/Act/Act300060401.json'
            value=json.loads(path.read_bytes());value['UnknownDifferentWholeOriginal']=True;path.write_text(json.dumps(value),'utf-8')
            with self.assertRaisesRegex(AssertionError,'Complete original source differs'):tool.source_preflight(root,self.fixture)

if __name__=='__main__':unittest.main()
