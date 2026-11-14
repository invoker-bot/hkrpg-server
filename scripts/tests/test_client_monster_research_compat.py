import base64
import hashlib
import importlib.util
import json
import struct
import sys
import tempfile
import types
import unittest
import zlib
from pathlib import Path
from unittest import mock

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'scripts'))
import client_monster_research_compat as compat

spec = importlib.util.spec_from_file_location('research_graph_reader', ROOT / 'scripts/recover-client-g61-npc-graphs.py')
graphs = importlib.util.module_from_spec(spec)
spec.loader.exec_module(graphs)
PROFILE = graphs.load_profile()
PROFILE['tasks']['1099'] = {'name': 'ConsumeMissionItemPerformance', 'noReaderPayload': False}
PROFILE['classes']['ConsumeMissionItemPerformance'] = {'fields': [
    {'name': 'TaskEnabled', 'type': {'kind': 2}},
    {'name': 'SubMissionID', 'type': {'kind': 9}},
    {'name': 'Desc', 'type': {'kind': 17, 'name': 'TextID'}},
    {'name': 'OnSubmitConfirm', 'type': {'kind': 29, 'elementType': {'kind': 18, 'name': 'TaskConfig'}}},
    {'name': 'OnSubmitCancel', 'type': {'kind': 29, 'elementType': {'kind': 18, 'name': 'TaskConfig'}}},
    {'name': 'SimpleTalk', 'type': {'kind': 18, 'name': 'SimpleTalkInfo'}},
]}
MAINS = (8000170, 8000171, 8000172, 8000173, 8000174, 8000175, 8000177)
OFFSETS = (601, 344, 511, 350, 583, 517, 523)
COSTS = (
    [(111011, 1), (181002, 1), (180005, 1)],
    [(111011, 1), (181003, 1), (180001, 1)],
    [(111001, 1), (181005, 1), (180004, 1)],
    [(111001, 1), (181008, 1), (180005, 1)],
    [(112011, 1), (181010, 1), (180005, 1)],
    [(112011, 1), (181001, 1), (180002, 1)],
    [(181002, 1), (181009, 1), (181003, 1)],
)


def events(tasks):
    return [(task['$type'], task['CustomString']['Value']) for task in tasks]


def read_partial(raw, main):
    reader = graphs.Reader(raw, PROFILE)
    reader.pos = 420 if main == 8000177 else 330
    mask, second = reader.uvar(), reader.uvar()
    sub = main * 100 + (5 if main == 8000177 else 4)
    assert (reader.uvar(), reader.uvar()) == (sub, main)
    assert reader.read({'kind': 14}) == f'Config/Level/Mission/{main}/Mission_{sub}.json'
    assert (reader.uvar(), reader.uvar(), reader.sint()) == (10001, 10001001, 1)
    assert reader.read({'kind': 29, 'elementType': {'kind': 9}}) == [main]
    assert (reader.sint(), reader.sint(), reader.read({'kind': 14})) == (2, 2, f'Mission_{sub}')
    costs = []
    if mask & (1 << 27):
        for _ in range(reader.integer(32, True)):
            assert reader.uvar() == 3
            costs.append((reader.uvar(), reader.uvar()))
    assert reader.uvar() == 1 and reader.read({'kind': 2}) is False
    if main != 8000177:
        assert reader.uvar() == 1
    assert reader.pos == len(raw) and second == 0
    return costs


def read_performances(raw):
    fields = [('id', 9), ('path', 14), ('skip', 17), ('change', 17), ('character', 14),
              ('start', 17), ('end', 17), ('crack', 2), ('plane', 9), ('floor', 9), ('intro', 2)]
    reader = graphs.Reader(raw, PROFILE)
    count = reader.integer(32, True)
    result = {}
    for _ in range(count):
        start, mask = reader.pos, reader.uvar()
        assert mask >> len(fields) == 0
        row = {}
        for bit, (name, kind) in enumerate(fields):
            if mask & (1 << bit):
                row[name] = reader.sint() if kind == 17 else reader.read({'kind': kind})
        assert row['id'] not in result
        result[row['id']] = (row, raw[start:reader.pos])
    assert reader.pos == len(raw)
    return result


class ClientMonsterResearchCompatibilityTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        _, cls.original = compat.fixture()

    def patched(self, path):
        return compat.patch_record(path, self.original[path])

    def test_six_submissions_use_native_ui_and_preserve_both_callbacks(self):
        for main, offset in zip(MAINS[:6], OFFSETS[:6]):
            path = f'Config/Level/Mission/{main}/Act/Act{main * 100 + 1}.json'
            raw = self.patched(path)
            reader = graphs.Reader(raw, PROFILE); reader.pos = offset
            node = reader.read({'kind': 18, 'name': 'TaskConfig'})
            self.assertEqual(1099, node['taskId'])
            self.assertEqual(main * 100 + 4, node['SubMissionID'])
            self.assertEqual([('TriggerCustomString', f'Submit_{main * 100 + 1}'),
                              ('TriggerCustomString', f'MainFinish_{main}')], events(node['OnSubmitConfirm']))
            self.assertEqual([('TriggerCustomString', f'Cancel_{main * 100 + 1}'),
                              ('TriggerCustomString', f'FirstTimeFail_{main * 100 + 1}')], events(node['OnSubmitCancel']))

    def test_each_mission_displays_its_original_materials(self):
        source = (ROOT / 'src/test/resources/fixtures/material-submit-activity-4.6.json').read_bytes()
        self.assertEqual('455304ad8bd49e783b91337bd1824d1b6bf843a368f97e1fa40a40577f290d64',
                         hashlib.sha256(source).hexdigest())
        material = json.loads(source)['files']['ExcelOutput/MaterialSubmitter.json']
        data = zlib.decompress(base64.b64decode(material['rawBase64']))
        self.assertEqual(material['sha256'], hashlib.sha256(data).hexdigest())
        rows = {row['ID']: row for row in json.loads(data)}
        for ident, expected in enumerate(COSTS, 1):
            self.assertEqual(expected, [(item['ItemID'], item['ItemNum']) for item in rows[ident]['MaterialList']])
        for main, costs in zip(MAINS, COSTS):
            path = f'Config/Level/Mission/{main}/MissionInfo_{main}.partial.json'
            self.assertEqual(costs, read_partial(self.patched(path), main))

    def test_retry_entries_and_final_report_continuation_are_separate(self):
        path = 'ExcelOutput/PerformanceE.json'
        before, after = read_performances(self.original[path]), read_performances(self.patched(path))
        changed = {800017003: 800017001, 800017102: 800017101, 800017702: 800017701}
        added = {800017202: 800017201, 800017302: 800017301, 800017402: 800017401,
                 800017502: 800017501, 800017706: 800017702}
        self.assertEqual(set(before) | set(added), set(after))
        for target, source in (changed | added).items():
            self.assertEqual(before[source][0]['path'], after[target][0]['path'])
        for key in set(before) - set(changed):
            self.assertEqual(before[key][1], after[key][1])
        tail = self.patched('Config/Level/Mission/8000177/Mission_800017703.json')
        tail_graph = graphs.decode_graph(tail, PROFILE)
        self.assertEqual(800017706, tail_graph['OnStartSequece'][0]['TaskList'][0]['PerformanceID'])

    def test_final_stage_presents_all_three_choices_and_preserves_them(self):
        path = 'Config/Level/Mission/8000177/Act/Act800017701.json'
        raw = self.patched(path)
        reader = graphs.Reader(raw, PROFILE); reader.pos = OFFSETS[-1]
        node = reader.read({'kind': 18, 'name': 'TaskConfig'})
        self.assertEqual('PlayOptionTalk', node['$type'])
        self.assertEqual([4674647842233983734, 5007463842034624602, 1834554150998599499],
                         [option['OptionTextmapID']['hash'] for option in node['OptionList']])
        self.assertEqual([f'ResearchChoice_8000177_{i}' for i in (1, 2, 3)],
                         [option['TriggerCustomString'] for option in node['OptionList']])
        # Skip the unchanged original suffix to independently read the appended choice branches.
        reader.pos += len(self.original[path]) - 712
        for i in (1, 2, 3):
            branch = reader.obj('LevelStartSequeceConfig')['TaskList']
            self.assertEqual(f'ResearchChoice_8000177_{i}', branch[0]['CustomString']['Value'])
            submit = branch[1]
            self.assertEqual(1099, submit['taskId'])
            self.assertEqual(800017705, submit['SubMissionID'])
            self.assertEqual([('TriggerCustomStringOnDialogEnd', f'ResearchFinish_8000177_{i}'),
                              ('TriggerCustomString', 'Submit_800017701')], events(submit['OnSubmitConfirm']))
            self.assertEqual([('TriggerCustomString', 'Cancel_800017701'),
                              ('TriggerCustomString', 'FirstTimeFail_800017701')], events(submit['OnSubmitCancel']))
        self.assertEqual(len(raw), reader.pos)
        finish = self.patched('Config/Level/Mission/8000177/Mission_800017705.json')
        branches = graphs.decode_graph(finish, PROFILE)['OnStartSequece']
        self.assertEqual(3, len(branches))
        for i, branch in enumerate(branches, 1):
            wait, complete = branch['TaskList']
            self.assertEqual(f'ResearchFinish_8000177_{i}', wait['CustomString']['Value'])
            # The fixed client's ParseMissionIDFromFinishKey retains every digit in the key.
            self.assertEqual('800017705', ''.join(c for c in complete['Key'] if c in '0123456789'))
            self.assertEqual(f'Research{chr(64 + i)}_800017705', complete['Key'])

    def test_missing_legacy_report_page_cannot_reopen_after_submission(self):
        for path in ('Config/Level/Mission/8000170/Act/Act800017001.json',
                     'Config/Level/Mission/8000170/Act/Act800017003.json',
                     'Config/Level/Mission/8000177/Act/Act800017702.json'):
            self.assertNotIn(b'Ui.Activity.ActivityMonsterResearch.ActivityMonsterResearchEnterPage',
                             self.patched(path))

    def test_changed_source_is_rejected(self):
        path = 'Config/Level/Mission/8000170/Act/Act800017001.json'
        with self.assertRaises(ValueError):
            compat.patch_record(path, self.original[path] + b'\0')

    def test_shared_chunk_keeps_unrelated_bytes_and_updates_all_offsets(self):
        original = b'HEADaaaBBBBcccccTAIL'
        index = bytearray(128)
        entries = [{'meta': 40, 'key': 'a', 'offset': 4, 'length': 3},
                   {'meta': 56, 'key': 'b', 'offset': 7, 'length': 4},
                   {'meta': 72, 'key': 'c', 'offset': 11, 'length': 5}]
        chunk = {'meta': 0, 'base': 4, 'size': len(original), 'digest': hashlib.md5(original).hexdigest(),
                 'entries': entries}
        name, changed = compat.patch_chunk(index, chunk, original, {'a': b'LONGER', 'c': b'Z'})
        self.assertEqual(b'HEADLONGERBBBBZTAIL', changed)
        self.assertEqual(hashlib.md5(changed).hexdigest() + '.bytes', name)
        self.assertEqual(bytes.fromhex(name[:-6]), index[8:24])
        self.assertEqual(len(changed), struct.unpack_from('>I', index, 28)[0])
        self.assertEqual((6, 0), struct.unpack_from('>II', index, 48))
        self.assertEqual((4, 6), struct.unpack_from('>II', index, 64))
        self.assertEqual((1, 10), struct.unpack_from('>II', index, 80))
        with self.assertRaises(ValueError):
            compat.patch_chunk(bytearray(128), chunk, original + b'!', {'a': b'LONGER'})

    def test_install_refuses_a_running_client_before_creating_a_backup(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp); backup = root / 'backup'
            launcher = types.SimpleNamespace(require_stopped=mock.Mock(side_effect=RuntimeError('client running')))
            with mock.patch.object(compat, 'module', return_value=launcher):
                with self.assertRaisesRegex(RuntimeError, 'client running'):
                    compat.install(root / 'prepared', backup, root / 'manifest')
            self.assertFalse(backup.exists())

    def test_install_backups_index_and_never_replaces_unrelated_files(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp); client = root / 'client'; directory = client / compat.CLIENT_DIR
            prepared = root / 'prepared'; backup = root / 'backup'
            directory.mkdir(parents=True); prepared.mkdir()
            index_name = 'DesignV_' + 'a' * 32 + '.bytes'
            chunk_name = 'b' * 32 + '.bytes'
            old, new, pointer, chunk = b'old index', b'new index', b'pointer', b'new chunk'
            (directory / index_name).write_bytes(old); (directory / 'M_DesignV.bytes').write_bytes(pointer)
            (directory / 'unrelated.bytes').write_bytes(b'original')
            (prepared / index_name).write_bytes(new); (prepared / chunk_name).write_bytes(chunk)
            plan = {'client': client.resolve(), 'directory': directory.resolve(), 'indexPath': directory / index_name,
                    'pointer': pointer, 'oldIndex': old, 'index': new, 'chunks': {chunk_name: chunk},
                    'recordSHA256': {}, 'unrelatedRecordsChecked': 0}
            report = {'schema': 1, 'client': str(client), 'indexName': index_name,
                      'oldIndexSHA256': compat.sha(old), 'newIndexSHA256': compat.sha(new),
                      'pointerSHA256': compat.sha(pointer), 'chunks': {chunk_name: compat.sha(chunk)},
                      'recordSHA256': {}, 'unrelatedRecordsChecked': 0}
            (prepared / 'prepared.json').write_text(json.dumps(report), encoding='utf-8')
            launcher = types.SimpleNamespace(require_stopped=mock.Mock())
            with mock.patch.object(compat, 'module', return_value=launcher), mock.patch.object(compat, 'make_plan', return_value=plan):
                result = compat.install(prepared, backup, root / 'manifest')
            self.assertTrue(result['clientModified']); self.assertEqual(2, launcher.require_stopped.call_count)
            self.assertEqual(old, (backup / index_name).read_bytes())
            self.assertEqual(pointer, (backup / 'M_DesignV.bytes').read_bytes())
            self.assertEqual(new, (directory / index_name).read_bytes())
            self.assertEqual(chunk, (directory / chunk_name).read_bytes())
            self.assertEqual(b'original', (directory / 'unrelated.bytes').read_bytes())


if __name__ == '__main__':
    unittest.main()
