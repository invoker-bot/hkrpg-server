"""Actual full CN 4.6 rest-room records, not synthetic successful byte streams."""
import base64
import copy
import hashlib
import importlib.util
import json
from pathlib import Path
import unittest
import tempfile
from types import SimpleNamespace
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
FIXTURE = Path(__file__).with_name('fixtures') / 'client46-rogue-rest-graphs.json'


def module(name, filename):
    spec = importlib.util.spec_from_file_location(name, ROOT / 'scripts' / filename)
    result = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(result)
    return result


class RogueRestClientGraphs(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.fixture = json.loads(FIXTURE.read_text('utf-8'))
        cls.missions = module('rest_client_test_missions', 'recover-client-mission-graphs.py')
        # The RED baseline really decodes with the current public fixed reader.
        # After extension, use its public loader and the same original records.
        extension = ROOT / 'scripts/recover-client-rogue-rest-graphs.py'
        cls.decoder = module('rest_client_test_extension', extension.name) if extension.is_file() else cls.missions

    def record(self, suffix):
        return next(row for row in self.fixture['records'] if row['sourcePath'].endswith(suffix))

    def decode_original(self, row):
        raw = base64.b64decode(row['rawBase64'], validate=True)
        self.assertEqual(len(raw), row['length'])
        self.assertEqual(hashlib.sha256(raw).hexdigest(), row['rawSHA256'])
        try:
            return self.decoder.decode_graph(raw, self.decoder.load_profile())
        except ValueError as error:
            self.fail(f"Complete original {row['sourcePath']} was rejected: {error}")

    def test_full_transferred_preserves_both_authored_mission_menu_branches(self):
        row = self.record('DialoguePack_Transferred_F80001002_G34_N400001.json')
        graph = self.decode_original(row)
        tasks = graph['OnStartSequece'][0]['TaskList']
        self.assertEqual([task['$type'] for task in tasks], ['CollectDataConditions', 'PredicateTaskList', 'PredicateTaskList'])
        self.assertEqual(tasks[0]['TaskIDList'], [403000304])
        for task, state, menu, path in zip(tasks[1:], [1, 2], [403000304, 403000399], [
                'Config/Level/Mission/4030003/Talk/Talk_403000304.json',
                'Config/Level/Mission/4030003/Act/Act403000399.json']):
            self.assertEqual(task['Predicate']['$type'], 'ByCompareSubMissionState')
            self.assertEqual(task['Predicate']['SubMissionID'], 403000304)
            self.assertEqual(task['Predicate']['SubMissionState'], state)
            option, = task['SuccessTaskList']
            self.assertEqual(option['$type'], 'AddMenuItem')
            self.assertEqual(option['MenuItemID'], menu)
            self.assertEqual(option['MissionID'], 403000304)
            self.assertEqual(option['DialoguePath'], path)
            self.assertEqual(option['MenuIconType']['EnumIndex'], 20)
            self.assertEqual(option['MenuIconType']['Value'], 10)
            self.assertEqual(option['ExtraInfo'], 2)

    def test_full_g34_main_is_the_actual_single_show_menu(self):
        graph = self.decode_original(self.record('DialogueMain_F80001002_G34_N400001.json'))
        self.assertEqual(len(graph['OnStartSequece']), 1)
        task, = graph['OnStartSequece'][0]['TaskList']
        self.assertEqual(task['$type'], 'ShowMenu')
        self.assertEqual(task['taskId'], 3259)
        self.assertNotIn('DisplaySingleMenuItem', task)

    def test_full_thirteen_byte_tutorial_has_all_three_native_tasks(self):
        self.assert_rest_script('Maze_Rogue_Tutorial_RestRoom_S1.json')

    def test_full_thirteen_byte_regular_rest_has_all_three_native_tasks(self):
        self.assert_rest_script('Maze_Rogue_Group_RestRoom.json')

    def assert_rest_script(self, suffix):
        row = self.record(suffix)
        self.assertEqual(row['length'], 13)
        graph = self.decode_original(row)
        tasks = graph['OnStartSequece'][0]['TaskList']
        self.assertEqual([task['$type'] for task in tasks], ['WaitPredicateSucc', 'SetAllRogueDoorState', 'SetRogueRoomFinish'])
        self.assertEqual([task['taskId'] for task in tasks], [3875, 2697, 3067])
        self.assertTrue(all(task['$noReaderPayload'] for task in tasks))

    def test_full_fixed_source_blobs_are_self_consistent_and_keep_ui_boundary(self):
        self.assertEqual(self.fixture['sourceCommit'], '724b139d8c9c32d12552eb95745a4fee72bfe48b')
        for row in self.fixture['records']:
            raw = base64.b64decode(row['sourceJsonBase64'], validate=True)
            self.assertEqual(hashlib.sha256(raw).hexdigest(), row['sourceJsonSHA256'])
            json.loads(raw)
        for row in self.fixture['followingGraphs'].values():
            raw = base64.b64decode(row['sourceJsonBase64'], validate=True)
            self.assertEqual(hashlib.sha256(raw).hexdigest(), row['sha256'])
            pages = [task['Name'] for seq in json.loads(raw)['OnStartSequece'] for task in seq['TaskList']
                     if task.get('$type') == 'RPG.GameCore.ShowUI']
            self.assertEqual(pages, ['Ui.Rogue.Buff.RogueEnhanceBuffPage'])
        self.assertFalse(any(self.fixture['boundaries'].values()))

    def test_fixture_and_profile_bind_the_same_entire_source_records(self):
        profile = self.decoder.load_profile()
        self.assertEqual(self.fixture['sourceCommit'], profile['restExtension']['sourceCommit'])
        self.assertEqual(self.fixture['clientFiles'], profile['clientFiles'])
        self.assertEqual(self.fixture['indexSHA256'], profile['indexSHA256'])
        self.assertEqual({row['sourcePath'] for row in self.fixture['records']}, set(profile['records']))
        for row in self.fixture['records']:
            expected = profile['records'][row['sourcePath']]
            self.assertEqual(expected, {'entryKey':row['entryKey'], 'length':row['length'],
                                       'sha256':row['rawSHA256'], 'sourceJsonSHA256':row['sourceJsonSHA256']})

    def test_complete_decoded_json_equals_each_entire_fixed_git_blob(self):
        for row in self.fixture['records']:
            with self.subTest(path=row['sourcePath']):
                graph = self.decode_original(row)
                result = self.decoder.resource_json(graph, self.decoder.load_profile())
                self.assertEqual(result, json.loads(base64.b64decode(row['sourceJsonBase64'], validate=True)))

    def test_every_truncated_original_prefix_is_rejected(self):
        profile = self.decoder.load_profile()
        for row in self.fixture['records']:
            raw = base64.b64decode(row['rawBase64'], validate=True)
            for cut in range(len(raw)):
                with self.subTest(path=row['sourcePath'], length=cut), self.assertRaises(ValueError):
                    self.decoder.decode_graph(raw[:cut], profile)

    def test_trailing_bytes_after_each_complete_original_are_rejected(self):
        profile = self.decoder.load_profile()
        for row in self.fixture['records']:
            with self.subTest(path=row['sourcePath']), self.assertRaisesRegex(ValueError, '未读取'):
                self.decoder.decode_graph(base64.b64decode(row['rawBase64'], validate=True) + b'\0', profile)

    def test_unverified_predicate_tag_in_the_actual_transferred_branch_is_rejected(self):
        row = self.record('DialoguePack_Transferred_F80001002_G34_N400001.json')
        raw = base64.b64decode(row['rawBase64'], validate=True)
        graph = self.decode_original(row)
        point = graph['OnStartSequece'][0]['TaskList'][1]['Predicate']['$offset']
        self.assertEqual(raw[point - 2:point], b'\x8c\x02')
        bad = raw[:point - 2] + b'\x8d\x02' + raw[point:]
        with self.assertRaisesRegex(ValueError, 'PredicateConfig'):
            self.decoder.decode_graph(bad, self.decoder.load_profile())

    def test_unverified_task_tag_in_the_actual_rest_original_is_rejected(self):
        row = self.record('Maze_Rogue_Group_RestRoom.json')
        raw = base64.b64decode(row['rawBase64'], validate=True)
        graph = self.decode_original(row)
        point = graph['OnStartSequece'][0]['TaskList'][0]['$offset']
        self.assertEqual(raw[point:point + 2], b'\xa3\x1e')
        bad = raw[:point] + b'\xa2\x1e' + raw[point + 2:]
        self.assertNotIn('3874', self.decoder.load_profile()['tasks'])
        with self.assertRaisesRegex(ValueError, '未知任务'):
            self.decoder.decode_graph(bad, self.decoder.load_profile())

    def test_unknown_add_menu_mask_bit_in_the_actual_original_is_rejected(self):
        row = self.record('DialoguePack_Transferred_F80001002_G34_N400001.json')
        raw = base64.b64decode(row['rawBase64'], validate=True)
        graph = self.decode_original(row)
        item = graph['OnStartSequece'][0]['TaskList'][1]['SuccessTaskList'][0]
        point = item['$offset']
        self.assertEqual(raw[point:point + 2], b'\x96\x06')
        bad = raw[:point] + b'\x96\x16' + raw[point + 2:]
        with self.assertRaisesRegex(ValueError, '未知字段掩码'):
            self.decoder.decode_graph(bad, self.decoder.load_profile())

    def test_native_json_enum_has_no_guessed_mask_and_requires_explicit_encoding(self):
        row = self.record('DialoguePack_Transferred_F80001002_G34_N400001.json')
        raw = base64.b64decode(row['rawBase64'], validate=True)
        graph = self.decode_original(row)
        icon = graph['OnStartSequece'][0]['TaskList'][1]['SuccessTaskList'][0]['MenuIconType']
        self.assertEqual(raw[icon['$offset']:icon['$offset'] + 2], b'\x28\x14')
        self.assertNotIn('$mask', icon)
        profile = copy.deepcopy(self.decoder.load_profile())
        profile.pop('binaryEncodings')
        with self.assertRaisesRegex(ValueError, 'JsonEnum'):
            self.decoder.decode_graph(raw, profile)

    def test_native_json_enum_pair_rejects_overflow_and_noncanonical_varints(self):
        profile = self.decoder.load_profile()
        overflow = b'\x80\x80\x80\x80\x10'
        for raw in (overflow + b'\0', b'\0' + overflow, b'\x80\0\0', b'\0\x80\0'):
            with self.subTest(raw=raw), self.assertRaises(ValueError):
                self.missions.graphs.Reader(raw, profile).read({'kind':18, 'name':'JsonEnum'})

    def test_tampered_native_profile_is_rejected_before_using_its_fields(self):
        original = self.decoder.PROFILE.read_bytes()
        changed = original.replace(b'zigzag32_pair', b'guessed_mask')
        self.assertNotEqual(original, changed)
        with tempfile.TemporaryDirectory() as directory:
            bad = Path(directory) / 'changed-profile.json'
            bad.write_bytes(changed)
            with patch.object(self.decoder, 'PROFILE', bad), self.assertRaisesRegex(ValueError, '原生证据已变化'):
                self.decoder.load_profile()

    def test_unknown_and_duplicate_record_paths_are_rejected_before_client_reads(self):
        known = self.fixture['records'][0]['sourcePath']
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            for paths in (['Config/Level/unknown.json'], [known, known], ['../' + known]):
                args = SimpleNamespace(client_dir=root / 'client', output=root / 'report.json', paths=paths)
                with self.subTest(paths=paths), patch.object(self.decoder, 'verify_native', side_effect=AssertionError('No client read expected')):
                    with self.assertRaisesRegex(ValueError, '四份固定休息区'):
                        self.decoder.recover(args)
                self.assertFalse(args.output.exists())

    def fake_extract(self, args):
        profile = self.decoder.load_profile()
        records = []
        for row in self.fixture['records']:
            if row['sourcePath'] not in args.paths:
                continue
            target = args.extract_dir / row['sourcePath']
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(base64.b64decode(row['rawBase64'], validate=True))
            records.append(dict(profile['records'][row['sourcePath']], sourcePath=row['sourcePath'],
                                status='read', extractedFile=str(target)))
        return {'files':profile['clientFiles'], 'indexSHA256':profile['indexSHA256'],
                'verifiedChunks':{}, 'records':records}

    def recover_fixture(self, directory, extract=None, output=None):
        args = SimpleNamespace(client_dir=directory / 'client', paths=[], output=output or directory / 'report.json')
        with patch.object(self.decoder, 'verify_native', return_value={'fixtureOnly':True}), \
             patch.object(self.decoder.missions.design, 'extract', side_effect=extract or self.fake_extract):
            return self.decoder.recover(args)

    def test_report_is_the_only_output_and_identical_rerun_preserves_bytes(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            first = self.recover_fixture(root)
            before = (root / 'report.json').read_bytes()
            self.assertEqual(first, self.recover_fixture(root))
            self.assertEqual(before, (root / 'report.json').read_bytes())
            self.assertEqual([root / 'report.json'], [path for path in root.rglob('*') if path.is_file()])
            self.assertTrue(all(row['fullyConsumed'] for row in first['records']))
            for flag in ('clientExecutableStarted', 'clientModified', 'clientAccepted', 'scriptsExecuted',
                         'jsonInstalled', 'completeGameplay', 'clientMenuEntryVerified', 'classicMiracleMenuRestored'):
                self.assertFalse(first[flag])

    def test_conflicting_operator_report_is_preserved(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            target = root / 'report.json'
            target.write_bytes(b'operator-owned-report')
            with self.assertRaises(FileExistsError):
                self.recover_fixture(root)
            self.assertEqual(b'operator-owned-report', target.read_bytes())
            self.assertEqual([target], [path for path in root.rglob('*') if path.is_file()])

    def test_client_directory_cannot_receive_a_report(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            with patch.object(self.decoder, 'verify_native', side_effect=AssertionError('No client read expected')):
                with self.assertRaisesRegex(ValueError, '客户端目录'):
                    self.decoder.recover(SimpleNamespace(client_dir=root / 'client', paths=[],
                                                         output=root / 'client' / 'report.json'))
            self.assertFalse((root / 'client').exists())

    def test_changed_client_or_index_digest_cannot_create_a_report(self):
        for field in ('files', 'indexSHA256'):
            with self.subTest(field=field), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                def changed(args):
                    source = self.fake_extract(args)
                    source[field] = {} if field == 'files' else '0' * 64
                    return source
                with self.assertRaisesRegex(ValueError, '索引来源'):
                    self.recover_fixture(root, changed)
                self.assertFalse((root / 'report.json').exists())
                self.assertFalse((root / 'client').exists())

    def test_changed_record_identity_or_digest_cannot_create_a_report(self):
        for field in ('status', 'entryKey', 'length', 'sha256'):
            with self.subTest(field=field), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                def changed(args):
                    source = self.fake_extract(args)
                    row = source['records'][1]
                    row[field] = row[field] + 1 if field == 'length' else 'changed'
                    return source
                with self.assertRaisesRegex(ValueError, '原件位置、长度或字节'):
                    self.recover_fixture(root, changed)
                self.assertFalse((root / 'report.json').exists())

    def test_changed_entire_raw_file_after_extraction_cannot_create_a_report(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            def changed(args):
                source = self.fake_extract(args)
                target = Path(source['records'][1]['extractedFile'])
                original = target.read_bytes()
                target.write_bytes(original[:-1] + bytes([original[-1] ^ 1]))
                return source
            with self.assertRaisesRegex(ValueError, '原件在读取后变化'):
                self.recover_fixture(root, changed)
            self.assertFalse((root / 'report.json').exists())


if __name__ == '__main__':
    unittest.main()
