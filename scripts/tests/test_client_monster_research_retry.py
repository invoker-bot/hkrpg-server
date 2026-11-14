import json
import sys
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'scripts'))
import client_monster_research_compat as compat
import client_monster_research_retry as retry

graphs, PROFILE, ARRAY = compat.graph_reader()
PROFILE['tasks']['1099'] = {'name': 'ConsumeMissionItemPerformance', 'noReaderPayload': False}
PROFILE['classes']['ConsumeMissionItemPerformance'] = {'fields': [
    {'name': 'TaskEnabled', 'type': {'kind': 2}},
    {'name': 'SubMissionID', 'type': {'kind': 9}},
    {'name': 'Desc', 'type': {'kind': 17, 'name': 'TextID'}},
    {'name': 'OnSubmitConfirm', 'type': ARRAY},
    {'name': 'OnSubmitCancel', 'type': ARRAY},
    {'name': 'SimpleTalk', 'type': {'kind': 18, 'name': 'SimpleTalkInfo'}},
]}
PROFILE['tasks'].update({
    '936': {'name': 'CaptureNPCToCharacter', 'noReaderPayload': False},
    '972': {'name': 'CharacterDisableLookAt', 'noReaderPayload': False},
    '1264': {'name': 'EndDialogueEntityInteract', 'noReaderPayload': False},
    '3357': {'name': 'StartDialogueEntityInteract', 'noReaderPayload': False},
})
PROFILE['classes']['CaptureNPCToCharacter'] = {'fields': [
    {'name': 'TaskEnabled', 'type': {'kind': 2}},
    {'name': 'GroupID', 'type': {'kind': 18, 'name': 'DynamicFloat'}},
    {'name': 'GroupNpcID', 'type': {'kind': 18, 'name': 'DynamicFloat'}},
    {'name': 'CharacterUniqueName', 'type': {'kind': 18, 'name': 'DynamicString'}},
    {'name': 'ReleaseIfPerformanceEnd', 'type': {'kind': 2}},
    {'name': 'TargetType', 'type': {'kind': 18, 'name': 'TargetEvaluator'}},
]}
PROFILE['classes']['CharacterDisableLookAt'] = {'fields': [
    {'name': 'TaskEnabled', 'type': {'kind': 2}}, {'name': 'TargetIsOwner', 'type': {'kind': 2}},
    {'name': 'CharacterUniqueName', 'type': {'kind': 14}},
    {'name': 'TargetType', 'type': {'kind': 18, 'name': 'TargetEvaluator'}},
    {'name': 'Disable', 'type': {'kind': 2}},
]}
PROFILE['classes']['EndDialogueEntityInteract'] = {'fields': [
    {'name': 'TaskEnabled', 'type': {'kind': 2}},
    {'name': 'OverrideTasks', 'type': {'kind': 18, 'name': 'DialogueEntityInteractOverride'}},
]}
PROFILE['classes']['StartDialogueEntityInteract'] = {'fields': [
    {'name': 'TaskEnabled', 'type': {'kind': 2}},
    {'name': 'TargetType', 'type': {'kind': 18, 'name': 'TargetEvaluator'}},
    {'name': 'LevelGraphPath', 'type': {'kind': 14}}, {'name': 'UseOverrideData', 'type': {'kind': 2}},
    {'name': 'ValueSource', 'type': {'kind': 18, 'name': 'LevelGraphValueSource'}},
    {'name': 'OverrideTasks', 'type': {'kind': 18, 'name': 'DialogueEntityInteractOverride'}},
]}
PROFILE['predicates'].update({'53': 'ByAnd', '196': 'ByCompareMainMissionState', '475': 'ByNot'})
PROFILE['classes']['ByAnd'] = {'fields': [
    {'name': 'TaskEnabled', 'type': {'kind': 2}}, {'name': 'Inverse', 'type': {'kind': 2}},
    {'name': 'PredicateList', 'type': {'kind': 29, 'elementType': {'kind': 18, 'name': 'PredicateConfig'}}},
]}
PROFILE['classes']['ByNot'] = {'fields': [
    {'name': 'TaskEnabled', 'type': {'kind': 2}}, {'name': 'Inverse', 'type': {'kind': 2}},
    {'name': 'Predicate', 'type': {'kind': 18, 'name': 'PredicateConfig'}},
]}
PROFILE['classes']['ByCompareMainMissionState'] = {'fields': [
    {'name': 'TaskEnabled', 'type': {'kind': 2}}, {'name': 'Inverse', 'type': {'kind': 2}},
    {'name': 'MainMissionID', 'type': {'kind': 9}},
    {'name': 'MainMissionState', 'type': {'kind': 17, 'name': 'MainMissionState', 'typeIndex': 24497}},
    {'name': 'AllStoryLine', 'type': {'kind': 2}},
]}
PROFILE['enums']['24497'] = {'name': 'MainMissionState', 'values': {'0': 'Unknow', '1': 'Started', '2': 'Finish'}}

def events(items):
    return [(x['$type'], x.get('CustomString', {}).get('Value')) for x in items]

def clean(value):
    if isinstance(value, list): return [clean(x) for x in value]
    if isinstance(value, dict): return {k: clean(v) for k, v in value.items() if k not in ('$offset', '$mask', '$variantId')}
    return value

def node_start(data, stage):
    marker = compat.var(1789) + compat.var(62 if stage == 7 else 38) + compat.var(stage)
    assert data.count(marker) == 1
    return data.index(marker)

def original_node(data, stage):
    reader = graphs.Reader(data, PROFILE)
    reader.pos = node_start(data, stage)
    node = reader.read({'kind': 18, 'name': 'TaskConfig'})
    return node, reader.pos

def performance_rows(data, expected):
    reader = graphs.Reader(data, PROFILE)
    count = reader.integer(32, True)
    assert count == expected
    result = {}
    for _ in range(count):
        start, mask = reader.pos, reader.uvar()
        row = {}
        for bit, (name, kind) in enumerate(compat.PERFORMANCE_FIELDS):
            if mask & (1 << bit):
                row[name] = reader.sint() if kind == 17 else reader.read({'kind': kind})
        result[row['id']] = (row, data[start:reader.pos])
    assert reader.pos == len(data)
    return result

class ResearchRetryTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.evidence, cls.records = retry.fixture()
        _, cls.first = compat.fixture()

    def retry_record(self, identity):
        return self.records[f'Config/Level/NPCDialogue/P10001/F10001001_G10/DialogueItem{identity}_F10001001_G10_N400001.json']

    def test_original_activity_pack_routes_each_ongoing_retry_to_its_item(self):
        pack = graphs.decode_graph(self.records[retry.PACK], PROFILE)
        tasks = pack['OnStartSequece'][0]['TaskList']
        entries = {t['Predicate']['SubMissionID']: t['SuccessTaskList'][0]['DialoguePath']
                   for t in tasks if t.get('Predicate', {}).get('$type') == 'ByCompareSubMissionState'
                   and t['Predicate'].get('SubMissionState') == 1}
        for identity in retry.RETRIES:
            self.assertTrue(entries[identity].endswith(f'DialogueItem{identity}_F10001001_G10_N400001.json'))

    def test_six_retry_windows_use_normal_submission_and_original_callbacks(self):
        for stage, (main, identity) in enumerate(zip(compat.MAINS[:6], retry.RETRIES[:6]), 1):
            with self.subTest(stage=stage):
                old = self.retry_record(identity); original, end = original_node(old, stage)
                offset = node_start(old, stage); patched = retry.patch_retry_graph(old, main, stage)
                reader = graphs.Reader(patched, PROFILE); reader.pos = offset
                node = reader.read({'kind': 18, 'name': 'TaskConfig'})
                self.assertEqual('ConsumeMissionItemPerformance', node['$type'])
                self.assertEqual(main * 100 + 4, node['SubMissionID'])
                self.assertEqual(events(original['OnQuestion1']), events(node['OnSubmitConfirm']))
                self.assertEqual(events(original['OnCancel']), events(node['OnSubmitCancel']))
                self.assertEqual(old[:offset], patched[:offset]); self.assertEqual(old[end:], patched[reader.pos:])
                self.assertNotIn(('TriggerCustomString', f'Talk_{identity}'), events(node['OnSubmitCancel']))

    def test_all_patched_retry_graphs_decode_completely(self):
        for stage, (main, identity) in enumerate(zip(compat.MAINS, retry.RETRIES), 1):
            with self.subTest(stage=stage):
                data = retry.patch_retry_graph(self.retry_record(identity), main, stage)
                graph = graphs.decode_graph(data, PROFILE)
                self.assertEqual(5 if stage == 7 else 3, len(graph['OnStartSequece']))

    def test_cancelling_the_retry_preserves_the_active_retry_objective(self):
        for stage, (main, identity) in enumerate(zip(compat.MAINS, retry.RETRIES), 1):
            with self.subTest(stage=stage):
                data = retry.patch_retry_graph(self.retry_record(identity), main, stage)
                graph = graphs.decode_graph(data, PROFILE)
                branches = graph['OnStartSequece']
                cancel = [seq['TaskList'] for seq in branches
                          if seq['TaskList'] and seq['TaskList'][0]['$type'] == 'WaitCustomString'
                          and seq['TaskList'][0]['CustomString']['Value'] == f'Cancel_{identity}']
                self.assertEqual(1, len(cancel))
                self.assertFalse(any(task['$type'] in ('TriggerCustomString', 'FinishPerformanceMission')
                                     for task in cancel[0][1:]))

    def test_changed_retry_and_dialogue_sources_are_refused(self):
        with self.assertRaises(ValueError):
            retry.patch_retry_graph(self.retry_record(retry.RETRIES[0]) + b'\x00', 8000170, 1)
        with self.assertRaises(ValueError):
            retry.patch_npc_main(self.records[retry.MAIN] + b'\x00')

    def test_seventh_retry_retains_three_choices_and_original_submit_events(self):
        old = self.retry_record(retry.RETRIES[-1]); original, end = original_node(old, 7)
        offset = node_start(old, 7); patched = retry.patch_retry_graph(old, compat.MAINS[-1], 7)
        reader = graphs.Reader(patched, PROFILE); reader.pos = offset
        selection = reader.read({'kind': 18, 'name': 'TaskConfig'})
        self.assertEqual('PlayOptionTalk', selection['$type'])
        self.assertEqual(list(compat.TOPIC_HASHES), [x['OptionTextmapID']['hash'] for x in selection['OptionList']])
        self.assertEqual([f'ResearchRetryChoice_8000177_{i}' for i in (1,2,3)],
                         [x['TriggerCustomString'] for x in selection['OptionList']])
        suffix = old[end:]; self.assertEqual(suffix, patched[reader.pos:reader.pos + len(suffix)])
        reader.pos += len(suffix)
        for i in (1,2,3):
            wait, submit = reader.obj('LevelStartSequeceConfig')['TaskList']
            self.assertEqual(f'ResearchRetryChoice_8000177_{i}', wait['CustomString']['Value'])
            self.assertEqual('ConsumeMissionItemPerformance', submit['$type'])
            self.assertEqual(800017705, submit['SubMissionID'])
            expected = events(original[f'OnQuestion{i}'])
            expected.insert(3, ('TriggerCustomString', f'ResearchFinish_8000177_{i}'))
            self.assertEqual(expected, events(submit['OnSubmitConfirm']))
            self.assertEqual(events(original['OnCancel']), events(submit['OnSubmitCancel']))
        self.assertEqual(len(patched), reader.pos)

    def test_retry_menu_is_reachable_without_consuming_the_normal_intro(self):
        raw = self.records[retry.MAIN]
        before = graphs.decode_graph(raw, PROFILE)['OnStartSequece'][0]['TaskList']
        after = graphs.decode_graph(retry.patch_npc_main(raw), PROFILE)['OnStartSequece'][0]['TaskList']
        self.assertEqual(3, len(after))
        self.assertEqual(list(retry.RETRIES), after[0]['TaskIDList'])
        old_collect, new_collect = clean(before[0]), clean(after[0])
        old_collect.pop('TaskIDList'); new_collect.pop('TaskIDList')
        self.assertEqual(old_collect, new_collect)
        condition = after[1]['Predicate']
        self.assertEqual('ByAny', condition['$type'])
        self.assertEqual([(i, 1) for i in retry.RETRIES],
                         [(p['SubMissionID'], p['SubMissionState']) for p in condition['PredicateList']])
        self.assertEqual([], after[1]['SuccessTaskList'])
        self.assertEqual(clean(before[1]), clean(after[1]['FailedTaskList'][0]))
        self.assertEqual(clean(before[2]), clean(after[2]))

    def test_retry_success_keeps_original_followup_performances(self):
        raw = self.first['ExcelOutput/PerformanceE.json']; installed = compat.patch_performances(raw)
        old, before = performance_rows(raw, 12585), performance_rows(installed, 12590)
        after = performance_rows(retry.restore_performances(installed, raw), 12590)
        for identity in (800017003, 800017102, 800017702):
            self.assertEqual(old[identity][0], after[identity][0])
        for identity in set(before) - {800017003, 800017102, 800017702}:
            self.assertEqual(before[identity][1], after[identity][1])

if __name__ == '__main__': unittest.main()
