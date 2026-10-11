"""旧 Phone 原件预检的两份 G61 对话图安装兼容；只在临时目录操作，不启动 JVM。"""
import copy
import hashlib
import importlib.util
import json
from pathlib import Path
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location('daily_message_smoke', ROOT / 'scripts/smoke-daily-message.py')
smoke = importlib.util.module_from_spec(spec)
spec.loader.exec_module(smoke)


class DailyMessageSourceGuardTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.records = json.loads((ROOT / 'src/test/resources/fixtures/daily-message-3000602-4.6.json').read_text('utf-8'))
        cls.original, cls.installed, cls.missing = {}, {}, []
        for record in cls.records:
            relative = Path(record['source']).relative_to('resources/4.6').as_posix()
            if relative in cls.original:
                continue
            blob = subprocess.run(['git', '-C', str(ROOT / 'references/TurnBasedGameData'), 'show',
                                   smoke.COMMIT + ':' + relative], check=True, capture_output=True).stdout
            if hashlib.sha256(blob).hexdigest() != record['sourceBlobSHA256']:
                raise AssertionError('Fixed complete original fixture blob changed: ' + relative)
            cls.original[relative] = blob
            if record['installation'] == 'missing':
                cls.missing.append(relative)
            else:
                cls.installed[relative] = (ROOT / record['source']).read_bytes()
        if len(cls.original) != 39 or len(cls.missing) != 2:
            raise AssertionError('This regression must use all original 39 source files and the exact two historical source-only graphs')

    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.source = (Path(self.temporary.name) / 'resources/4.6').resolve()
        for relative, data in self.installed.items():
            self.write(relative, data)

    def write(self, relative, data):
        target = self.source / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(data)

    def install_pair(self, reformat=False):
        for relative in self.missing:
            blob = self.original[relative]
            if reformat:
                blob = json.dumps(json.loads(blob), sort_keys=True, ensure_ascii=False, indent=3).encode('utf-8')
                self.assertNotEqual(self.original[relative], blob, 'Reformatted fixed complete objects must exercise distinct bytes')
            self.write(relative, blob)

    def inspect(self, records=None, blobs=None):
        return smoke.source_preflight(self.source, records or self.records,
                                      (blobs or self.original).__getitem__)

    def test_original_two_absent_files_still_pass_without_creating_them(self):
        before = {p: p.read_bytes() for p in self.source.rglob('*') if p.is_file()}
        result = self.inspect()
        self.assertEqual(set(self.missing), set(result['source_only']))
        self.assertEqual(37, len(result['guarded']))
        self.assertEqual(before, {p: p.read_bytes() for p in self.source.rglob('*') if p.is_file()})

    def test_complete_fixed_original_pair_is_accepted_and_guarded_after_installation(self):
        self.install_pair()
        before = {p: p.read_bytes() for p in self.source.rglob('*') if p.is_file()}
        result = self.inspect()
        self.assertEqual([], result['source_only'])
        self.assertEqual(39, len(result['guarded']))
        self.assertEqual(before, {p: p.read_bytes() for p in self.source.rglob('*') if p.is_file()})

    def test_complete_decoded_pair_with_distinct_json_format_is_accepted_only_by_whole_object(self):
        self.install_pair(reformat=True)
        result = self.inspect()
        self.assertEqual([], result['source_only'])
        self.assertTrue(all(self.source / name in result['guarded'] for name in self.missing))

    def test_partial_installation_is_rejected(self):
        self.write(self.missing[0], self.original[self.missing[0]])
        with self.assertRaises(AssertionError):
            self.inspect()

    def test_complete_pair_with_changed_nested_graph_field_is_rejected_without_repair(self):
        self.install_pair()
        relative = self.missing[0]
        graph = json.loads(self.original[relative])
        graph['OnStartSequece'][0]['TaskList'][0]['PerformanceIDList'] = [90000104]
        altered = json.dumps(graph).encode('utf-8')
        self.write(relative, altered)
        with self.assertRaises(AssertionError):
            self.inspect()
        self.assertEqual(altered, (self.source / relative).read_bytes())

    def test_complete_pair_with_extra_unsourced_field_is_rejected(self):
        self.install_pair()
        relative = self.missing[1]
        graph = json.loads(self.original[relative])
        graph['UnsourcedExtraTask'] = {'Finish': 300060202}
        self.write(relative, json.dumps(graph).encode('utf-8'))
        with self.assertRaises(AssertionError):
            self.inspect()

    def test_original_reference_whole_blob_sha_cannot_be_replaced_by_matching_current_file(self):
        relative = self.missing[0]
        graph = json.loads(self.original[relative])
        graph['UnsourcedExtraTask'] = True
        changed = json.dumps(graph).encode('utf-8')
        self.write(relative, changed)
        blobs = self.original | {relative: changed}
        with self.assertRaises(AssertionError):
            self.inspect(blobs=blobs)

    def test_existing_installed_file_sha_remains_required_even_if_json_object_is_equal(self):
        relative = next(iter(self.installed))
        formatted = json.dumps(json.loads(self.installed[relative]), sort_keys=True, indent=1).encode('utf-8')
        self.assertNotEqual(self.installed[relative], formatted)
        self.write(relative, formatted)
        with self.assertRaises(AssertionError):
            self.inspect()

    def test_duplicate_file_rows_cannot_disagree_about_the_original_blob(self):
        records = copy.deepcopy(self.records)
        duplicate = copy.deepcopy(records[0])
        duplicate['sourceBlobSHA256'] = '0' * 64
        records.append(duplicate)
        with self.assertRaises(AssertionError):
            self.inspect(records=records)


if __name__ == '__main__':
    unittest.main()
