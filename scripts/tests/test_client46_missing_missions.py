"""两份原始客户端记录的完整读取；不启动游戏客户端或执行剧情。"""
import hashlib
import importlib.util
import json
from pathlib import Path
import unittest
import copy
import tempfile
from types import SimpleNamespace
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location('mission_graphs', ROOT / 'scripts/recover-client-mission-graphs.py')
missions = importlib.util.module_from_spec(spec)
spec.loader.exec_module(missions)
FIXTURE = json.loads((ROOT / 'src/test/resources/fixtures/client46-missing-mission-records.json').read_text('utf-8'))


class MissingMissionGraphsTest(unittest.TestCase):
    def decode(self, ordinal):
        record = FIXTURE['records'][ordinal]
        raw = bytes.fromhex(record['rawHex'])
        self.assertEqual(record['length'], len(raw))
        self.assertEqual(record['sha256'], hashlib.sha256(raw).hexdigest())
        try:
            return missions.decode_graph(raw)
        except ValueError as error:
            self.fail(str(error))

    def test_studio_script_keeps_both_conditional_paths_and_original_sentences(self):
        graph = self.decode(0)
        self.assertEqual(2, len(graph['OnStartSequece']))
        wait = graph['OnStartSequece'][0]['TaskList'][0]
        self.assertEqual('WaitFloorCustomValueChangeV2', wait['$type'])
        self.assertEqual('FCV_StudioState_Entity', wait['Name']['Value'])
        self.assertEqual([155032600,155032601], [s['TalkSentenceID'] for s in wait['OnChange'][0]['SimpleTalkList']])
        self.assertEqual(105450340, wait['OnChange'][1]['SubmissionID'])
        self.assertEqual(8589934592, wait['Condition']['CompareValue']['fixedRawSigned'])

    def test_commission_script_keeps_three_separate_finish_conditions(self):
        graph = self.decode(1)
        sequences = graph['OnStartSequece']
        self.assertEqual(3, len(sequences))
        self.assertEqual([434011111,434011112,434011113], [s['TaskList'][1]['SubMissionID'] for s in sequences])
        self.assertTrue(all(s['TaskList'][1]['SubMissionState']==2 for s in sequences))
        self.assertTrue(all(s['TaskList'][2]['SubmissionID']==434011114 for s in sequences))

    def test_every_truncated_original_record_is_rejected(self):
        for record in FIXTURE['records']:
            raw=bytes.fromhex(record['rawHex'])
            for end in range(len(raw)):
                with self.subTest(path=record['sourcePath'],end=end):
                    with self.assertRaises(ValueError):missions.decode_graph(raw[:end])

    def test_original_records_reject_trailing_bytes(self):
        for record in FIXTURE['records']:
            with self.assertRaises(ValueError):missions.decode_graph(bytes.fromhex(record['rawHex'])+b'\0')

    def test_unverified_task_number_is_not_treated_as_a_known_shape(self):
        raw=bytearray.fromhex(FIXTURE['records'][0]['rawHex'])
        self.assertEqual(bytes.fromhex('e81d'),raw[5:7])
        raw[5:7]=bytes.fromhex('e71d')
        with self.assertRaises(ValueError):missions.decode_graph(bytes(raw))

    def test_changed_profile_is_rejected_before_reading_any_script(self):
        with tempfile.TemporaryDirectory() as directory:
            p=Path(directory)/'profile.json';p.write_text('{}','utf-8')
            with patch.object(missions,'PROFILE',p):
                with self.assertRaises(ValueError):missions.load_profile()

    def test_canonical_studio_resource_keeps_original_predicate_and_talks(self):
        profile=missions.load_profile();resource=missions.resource_json(self.decode(0),profile)
        self.assertEqual('Mission',resource['Type'])
        wait=resource['OnStartSequece'][0]['TaskList'][0]
        self.assertEqual('RPG.GameCore.WaitFloorCustomValueChangeV2',wait['$type'])
        self.assertEqual({'Value':'FCV_StudioState_Entity'},wait['Name'])
        self.assertEqual({'IsDynamic':False,'FixedValue':{'Value':1}},wait['Condition']['CompareValue'])
        self.assertNotIn('CompareType',wait['Condition'])
        self.assertEqual([{'TalkSentenceID':155032600,'ProtectTime':2.0},
                          {'TalkSentenceID':155032601,'ProtectTime':2.0}],wait['OnChange'][0]['SimpleTalkList'])
        self.assertEqual(105450340,wait['OnChange'][1]['SubmissionID'])

    def test_canonical_commission_keeps_three_alternative_finished_states(self):
        resource=missions.resource_json(self.decode(1),missions.load_profile())
        self.assertEqual('Mission',resource['Type'])
        for sequence,ident in zip(resource['OnStartSequece'],[434011111,434011112,434011113]):
            tasks=sequence['TaskList']
            self.assertEqual([434011111,434011112,434011113],tasks[0]['TaskIDList'])
            self.assertEqual({'$type':'RPG.GameCore.ByCompareSubMissionState',
                              'SubMissionID':ident,'SubMissionState':'Finish'},tasks[1])
            self.assertEqual({'$type':'RPG.GameCore.ClientFinishMission','SubmissionID':434011114},tasks[2])

    def test_unverified_fixed_expression_is_not_rounded_into_one(self):
        graph=self.decode(0);graph['OnStartSequece'][0]['TaskList'][0]['Condition']['CompareValue']['fixedRawSigned']+=1
        with self.assertRaises(ValueError):missions.resource_json(graph,missions.load_profile())

    def test_unverified_enum_is_not_guessed(self):
        graph=self.decode(1);graph['Type']=100
        with self.assertRaises(ValueError):missions.resource_json(graph,missions.load_profile())

    def fake_extract(self,args):
        profile=missions.load_profile();records=[]
        for fixture in FIXTURE['records']:
            if fixture['sourcePath'] not in args.paths:continue
            target=args.extract_dir/fixture['sourcePath'];target.parent.mkdir(parents=True,exist_ok=True)
            target.write_bytes(bytes.fromhex(fixture['rawHex']))
            records.append(dict(profile['records'][fixture['sourcePath']],sourcePath=fixture['sourcePath'],
                                status='read',extractedFile=str(target)))
        return {'files':profile['clientFiles'],'indexSHA256':profile['indexSHA256'],
                'verifiedChunks':{},'records':records}

    def args(self,directory,paths=None):
        return SimpleNamespace(client_dir=directory/'client',destination=directory/'resources',
                               output=directory/'proof.json',paths=paths or [])

    def recover_fixture(self,args,extract=None):
        with patch.object(missions,'verify_native',return_value={'fixtureOnly':True}),\
             patch.object(missions.design,'extract',side_effect=extract or self.fake_extract):
            return missions.recover(args)

    def test_two_files_are_created_and_identical_rerun_does_not_overwrite(self):
        with tempfile.TemporaryDirectory() as directory:
            args=self.args(Path(directory));result=self.recover_fixture(args)
            self.assertTrue(result['jsonInstalled']);self.assertFalse(result['scriptsExecuted'])
            files={p:p.read_bytes() for p in Path(directory).rglob('*') if p.is_file()}
            self.assertEqual(4,len(files))
            self.assertEqual(result,self.recover_fixture(args))
            self.assertTrue(all(p.read_bytes()==data for p,data in files.items()))

    def test_conflicting_operator_script_leaves_every_other_output_absent(self):
        with tempfile.TemporaryDirectory() as directory:
            args=self.args(Path(directory));path=args.destination/FIXTURE['records'][1]['sourcePath']
            path.parent.mkdir(parents=True);path.write_bytes(b'operator script')
            with self.assertRaises(FileExistsError):self.recover_fixture(args)
            self.assertEqual(b'operator script',path.read_bytes());self.assertFalse(args.output.exists())
            self.assertFalse((args.destination/FIXTURE['records'][0]['sourcePath']).exists())

    def test_conflicting_report_prevents_installing_any_resource(self):
        with tempfile.TemporaryDirectory() as directory:
            args=self.args(Path(directory));args.output.write_bytes(b'operator report')
            with self.assertRaises(FileExistsError):self.recover_fixture(args)
            self.assertFalse(args.destination.exists());self.assertEqual(b'operator report',args.output.read_bytes())

    def test_changed_record_hash_cannot_create_json_or_a_report(self):
        with tempfile.TemporaryDirectory() as directory:
            args=self.args(Path(directory))
            def corrupt(a):
                source=self.fake_extract(a);source['records'][0]['sha256']='0'*64;return source
            with self.assertRaises(ValueError):self.recover_fixture(args,corrupt)
            self.assertFalse(args.destination.exists());self.assertFalse(args.output.exists())

    def test_changed_raw_record_after_extraction_is_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            args=self.args(Path(directory))
            def corrupt(a):
                source=self.fake_extract(a);Path(source['records'][0]['extractedFile']).write_bytes(b'changed');return source
            with self.assertRaises(ValueError):self.recover_fixture(args,corrupt)
            self.assertFalse(args.destination.exists());self.assertFalse(args.output.exists())

    def test_unknown_duplicate_and_parent_paths_are_rejected(self):
        for paths in [['../unknown.json'],[FIXTURE['records'][0]['sourcePath']]*2,['unknown']]:
            with tempfile.TemporaryDirectory() as directory:
                args=self.args(Path(directory),paths)
                with self.assertRaises(ValueError):self.recover_fixture(args)
                self.assertFalse(args.destination.exists())

    def test_client_directory_cannot_receive_resources_or_a_report(self):
        with tempfile.TemporaryDirectory() as directory:
            for target in ['destination','output']:
                args=self.args(Path(directory));setattr(args,target,args.client_dir/'forbidden')
                with self.assertRaises(ValueError):self.recover_fixture(args)
                self.assertFalse(args.client_dir.exists())

    @unittest.skipUnless((Path(r'C:\Users\InvokerBot\AppData\Local\hoyo\hkrpg\versions\current')/'GameAssembly.dll').is_file(),
                         '没有固定本机客户端，跳过只读核对')
    def test_actual_local_client_fully_reads_two_source_records_into_an_isolated_directory(self):
        with tempfile.TemporaryDirectory() as directory:
            args=self.args(Path(directory));args.client_dir=Path(r'C:\Users\InvokerBot\AppData\Local\hoyo\hkrpg\versions\current')
            result=missions.recover(args)
            self.assertEqual([164,130],[r['length'] for r in result['records']])
            self.assertEqual(12,result['nativeFieldAndBranchProof']['registrationPoints'])
            self.assertTrue(all(r['fullyConsumed'] for r in result['records']))
            self.assertFalse(result['clientExecutableStarted']);self.assertFalse(result['clientAccepted'])
            self.assertFalse(result['scriptsExecuted']);self.assertFalse(result['completeGameplay'])


if __name__ == '__main__':
    unittest.main()
