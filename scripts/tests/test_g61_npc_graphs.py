"""固定 G61 原件完整读取、原调用链与加法安装边界；不执行客户端或写运营资源。"""
import base64
import copy
import importlib.util
import json
import struct
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location('g61_recovery', ROOT / 'scripts/recover-client-g61-npc-graphs.py')
recovery = importlib.util.module_from_spec(spec)
spec.loader.exec_module(recovery)


def descendants(value):
    if isinstance(value, dict):
        yield value
        for child in value.values():
            yield from descendants(child)
    elif isinstance(value, list):
        for child in value:
            yield from descendants(child)


def uvar(value):
    result = bytearray()
    while value >= 128:
        result.append((value & 127) | 128)
        value >>= 7
    result.append(value)
    return bytes(result)


class G61NpcGraphsTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.profile = recovery.load_profile()
        cls.fixture, cls.records = recovery.fixture_records()

    def resource(self, path):
        raw = base64.b64decode(self.records[path]['rawBase64'], validate=True)
        return recovery.resource_json(recovery.decode_graph(raw,self.profile), self.profile)

    def test_ten_whole_local_records_equal_entire_fixed_source_graphs(self):
        self.assertEqual(10, len(self.records))
        self.assertEqual('724b139d8c9c32d12552eb95745a4fee72bfe48b', self.fixture['sourceCommit'])
        for path, record in self.records.items():
            with self.subTest(path=path):
                original = json.loads(base64.b64decode(record['sourceBlobBase64'],validate=True))
                self.assertEqual(original, self.resource(path))

    def test_every_original_rejects_truncation_and_trailing_bytes(self):
        for path, record in self.records.items():
            raw = base64.b64decode(record['rawBase64'],validate=True)
            with self.subTest(path=path,case='truncated'), self.assertRaises(ValueError):
                recovery.decode_graph(raw[:-1],self.profile)
            with self.subTest(path=path,case='trailing'), self.assertRaises(ValueError):
                recovery.decode_graph(raw + b'\x00',self.profile)

    def test_unknown_task_and_predicate_are_not_guessed(self):
        for name in ('TaskConfig','PredicateConfig'):
            with self.subTest(name=name), self.assertRaises(ValueError):
                recovery.Reader(uvar(999999),self.profile).read({'kind':18,'name':name})

    def test_option_factory_keeps_only_original_tag_zero_and_runtime_prefix_exclusion(self):
        reader = recovery.Reader(b'\x00\x01' + uvar(500011305),self.profile)
        option = reader.read({'kind':18,'name':'OptionTalkInfo'})
        self.assertEqual(500011305, option['TalkSentenceID'])
        self.assertEqual(0, option['$variantId'])
        self.assertEqual(len(reader.data), reader.pos)
        self.assertNotIn('OptionIconPath',option)
        with self.assertRaises(ValueError):
            recovery.Reader(b'\x01\x00',self.profile).read({'kind':18,'name':'OptionTalkInfo'})
        with self.assertRaises(ValueError):
            recovery.Reader(b'\x00' + uvar(1 << 11),self.profile).read({'kind':18,'name':'OptionTalkInfo'})

    def test_float32_text_retains_exact_original_bits(self):
        raw = struct.pack('<f',0.3)
        value = struct.unpack('<f',raw)[0]
        self.assertEqual(0.3, recovery.float32_json(value))
        self.assertEqual(raw, struct.pack('<f',recovery.float32_json(value)))

    def test_integer_literal_fixpoint_group_is_61_and_dynamic_expression_is_rejected(self):
        value = {'$type':'DynamicFloat','$mode':0,'fixedRawSigned':61 * 8589934592}
        self.assertEqual({'IsDynamic':False,'FixedValue':{'Value':61}},recovery.resource_json(value,self.profile))
        for changed in [value | {'$mode':1}, value | {'fixedRawSigned':value['fixedRawSigned'] + 1}]:
            with self.subTest(changed=changed), self.assertRaises(ValueError):
                recovery.resource_json(changed,self.profile)

    def test_original_intro_graph_selects_real_intro_then_original_menu(self):
        path = 'Config/Level/NPCDialogue/P20002/F20002001_G61/DialogueMain_F20002001_G61_N400001.json'
        tasks = self.resource(path)['OnStartSequece'][0]['TaskList']
        self.assertEqual([],tasks[0]['TaskIDList'])
        self.assertEqual([90000103],tasks[0]['PerformanceIDList'])
        self.assertEqual({'$type':'RPG.GameCore.ByComparePerformance','PerformanceID':90000103},tasks[1]['Predicate'])
        self.assertEqual(90000103,tasks[1]['SuccessTaskList'][0]['PerformanceID'])
        self.assertEqual(90000104,tasks[1]['FailedTaskList'][0]['PerformanceID'])
        self.assertEqual('RPG.GameCore.ShowMenu',tasks[2]['$type'])

    def test_original_commission_menu_is_started_gated_and_does_not_complete_any_goal(self):
        path = 'Config/Level/NPCDialogue/P20002/F20002001_G61/DialoguePack_Transferred_F20002001_G61_N400001.json'
        tasks = self.resource(path)['OnStartSequece'][0]['TaskList']
        self.assertEqual([300060101,300060201,300060301],tasks[0]['TaskIDList'])
        for task in tasks[1:4]:
            goal = task['Predicate']['SubMissionID']
            self.assertEqual('Started',task['Predicate']['SubMissionState'])
            menu = task['SuccessTaskList'][0]
            self.assertEqual(goal,menu['MenuItemID'])
            self.assertEqual(goal,menu['MissionID'])
            self.assertEqual('RPG.GameCore.AddMenuItem',menu['$type'])
            self.assertEqual(f'Config/Level/Mission/{goal // 100}/Talk/Talk_{goal}.json',menu['DialoguePath'])

    def test_real_first_script_waits_on_original_dialogue_finish_and_reaches_original_mission_key(self):
        first = self.resource('Config/Level/Mission/3000602/Mission_300060201.json')
        task_types = list(descendants(first))
        created = next(task for task in task_types if task.get('$type')=='RPG.GameCore.CreateNPC')
        self.assertEqual({'IsDynamic':False,'FixedValue':{'Value':61}},created['GroupID'])
        self.assertEqual({'IsDynamic':False,'FixedValue':{'Value':400001}},created['GroupNpcID'])
        self.assertTrue(any(task.get('$type')=='RPG.GameCore.WaitCustomString'
                            and task.get('CustomString',{}).get('Value')=='300060201_Finish' for task in task_types))
        self.assertTrue(any(task.get('$type')=='RPG.GameCore.FinishPerformanceMission'
                            and task.get('Key')=='Mission_300060201' for task in task_types))
        dialogue = self.resource('Config/Level/Mission/3000602/Act/Act300060201.json')
        final = [task for task in descendants(dialogue) if task.get('$type')=='RPG.GameCore.TriggerCustomStringOnDialogEnd']
        self.assertTrue(final)
        self.assertTrue(all(task['CustomString']['Value']=='300060201_Finish' for task in final))
        cancelled = next(sequence for sequence in dialogue['OnStartSequece']
            if any(task.get('$type')=='RPG.GameCore.WaitCustomString'
                   and task.get('CustomString',{}).get('Value')=='Wait_Option_300060201_18'
                   for task in sequence['TaskList']))
        self.assertFalse(any(task.get('$type')=='RPG.GameCore.TriggerCustomStringOnDialogEnd'
                             for task in cancelled['TaskList']))
        self.assertEqual('RPG.GameCore.EndPerformance',cancelled['TaskList'][-1]['$type'])

    def test_installed_preflight_reads_missing_and_matching_without_writing(self):
        path = next(iter(self.records))
        value = self.resource(path)
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self.assertEqual('missing',recovery.installed_status(root,path,value)['status'])
            target = root / path
            self.assertFalse(target.exists())
            target.parent.mkdir(parents=True)
            content = recovery.encode(value)
            target.write_bytes(content)
            self.assertEqual('present_matching',recovery.installed_status(root,path,value)['status'])
            self.assertEqual(content,target.read_bytes())

    def test_changed_operator_graph_and_path_escape_are_rejected(self):
        path = 'Config/Level/NPCDialogue/P20002/F20002001_G61/DialoguePack_Transferred_F20002001_G61_N400001.json'
        original = self.resource(path)
        changed = copy.deepcopy(original)
        changed['OnStartSequece'][0]['TaskList'][2]['Predicate']['SubMissionState']='Finished'
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            target = root / path
            target.parent.mkdir(parents=True)
            target.write_bytes(recovery.encode(changed))
            before = target.read_bytes()
            with self.assertRaises(ValueError):
                recovery.installed_status(root,path,original)
            self.assertEqual(before,target.read_bytes())
            with self.assertRaises(ValueError):
                recovery.installed_status(root,'../foreign.json',original)

    def test_add_only_output_never_replaces_different_bytes(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'original.json'
            recovery.write_new(path,b'original')
            recovery.write_new(path,b'original')
            with self.assertRaises(FileExistsError):
                recovery.write_new(path,b'changed')
            self.assertEqual(b'original',path.read_bytes())


if __name__ == '__main__':
    unittest.main()
