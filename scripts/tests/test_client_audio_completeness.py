"""回归：基础语音包齐全不等于现用 AudioV 的章节包齐全。"""
import hashlib
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
CLIENT = Path(r'C:\Users\InvokerBot\AppData\Local\hoyo\hkrpg\versions\current')
spec = importlib.util.spec_from_file_location('audio', ROOT / 'scripts/check-client-audio.py')
audio = importlib.util.module_from_spec(spec)
spec.loader.exec_module(audio)


class AudioCompletenessTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.directory = Path(self.temp.name) / 'audio'
        self.source = Path(self.temp.name) / 'source'
        (self.directory / 'Chinese(PRC)').mkdir(parents=True)
        self.source.mkdir()
        self.data = b'base voice'
        self.row = {'Path': 'Chinese(PRC)/External0.pck', 'Md5': hashlib.md5(self.data).hexdigest(),
                    'Size': len(self.data), 'SubPackId': 0}
        self.chapter = {'Path': 'Chinese(PRC)/External_del_4.6_chapter5_0.pck',
                        'Md5': hashlib.md5(b'chapter').hexdigest(), 'Size': 7, 'SubPackId': 2002}

    def index(self, rows):
        path = self.directory / audio.INDEX
        path.write_text('\n'.join(json.dumps(row) for row in rows), encoding='utf-8')
        return audio.read_index(self.directory, 'cn', expected=audio.digest(path))

    def install_base(self):
        path = self.directory / self.row['Path']
        path.write_bytes(self.data)
        audio.marker_path(path, self.row).write_bytes(b' ')
        return path

    def test_complete_base_pack_does_not_hide_missing_chapter_subpack(self):
        self.install_base()
        rows = self.index([self.row, self.chapter])
        result = audio.audit(self.directory, rows)
        self.assertFalse(result['complete'])
        self.assertEqual(2, result['requiredPackages'])
        self.assertEqual([{'path': self.chapter['Path'], 'reason': 'missing'}], result['issues'])

    def test_same_size_corrupt_audio_is_not_complete_and_cannot_be_overwritten(self):
        path = self.install_base()
        path.write_bytes(b'x' * len(self.data))
        self.assertEqual('content-mismatch', audio.audit(self.directory, [self.row])['issues'][0]['reason'])
        with self.assertRaises(ValueError):
            audio.plan_additions(self.directory, [self.row], self.source)
        self.assertEqual(b'x' * len(self.data), path.read_bytes())

    def test_missing_marker_is_rebuilt_only_for_verified_content(self):
        path = self.install_base()
        audio.marker_path(path, self.row).unlink()
        self.assertEqual('missing-marker', audio.audit(self.directory, [self.row])['issues'][0]['reason'])
        plan = audio.plan_additions(self.directory, [self.row], self.source)
        self.assertEqual(1, len(plan))
        audio.install_additions(plan)
        self.assertTrue(audio.audit(self.directory, [self.row])['complete'])

    def test_chapter_install_preserves_base_and_is_idempotent(self):
        path = self.install_base()
        old = path.stat().st_mtime_ns
        staged = self.source / Path(self.chapter['Path']).name
        staged.write_bytes(b'chapter')
        rows = [self.row, self.chapter]
        audio.install_additions(audio.plan_additions(self.directory, rows, self.source))
        self.assertTrue(audio.audit(self.directory, rows)['complete'])
        self.assertEqual(old, path.stat().st_mtime_ns)
        self.assertEqual([], audio.plan_additions(self.directory, rows, self.source))
        self.assertEqual(1, (self.directory / self.chapter['Path']).stat().st_nlink)
        staged.write_bytes(b'changed')
        self.assertEqual(b'chapter', (self.directory / self.chapter['Path']).read_bytes())

    def test_unverified_source_cannot_create_completion_markers(self):
        (self.source / Path(self.chapter['Path']).name).write_bytes(b'INVALID')
        with self.assertRaises(ValueError):
            audio.plan_additions(self.directory, [self.chapter], self.source)
        self.assertEqual([], list((self.directory / 'Chinese(PRC)').iterdir()))

    def test_copy_failure_rolls_back_only_our_new_files(self):
        self.install_base()
        (self.source / Path(self.chapter['Path']).name).write_bytes(b'chapter')
        plan = audio.plan_additions(self.directory, [self.row, self.chapter], self.source)
        link = audio.os.link
        def interrupted(source, target):
            if target.suffix == '.hash':
                raise OSError('injected publish failure')
            link(source, target)
        with patch.object(audio.os, 'link', side_effect=interrupted), self.assertRaises(OSError):
            audio.install_additions(plan)
        self.assertTrue(audio.audit(self.directory, [self.row])['complete'])
        self.assertFalse((self.directory / self.chapter['Path']).exists())
        self.assertFalse(list(self.directory.rglob('*.partial')))

    def test_concurrent_target_is_not_replaced(self):
        (self.source / Path(self.chapter['Path']).name).write_bytes(b'chapter')
        plan = audio.plan_additions(self.directory, [self.chapter], self.source)
        target = self.directory / self.chapter['Path']
        target.write_bytes(b'operator file')
        with self.assertRaises(FileExistsError):
            audio.install_additions(plan)
        self.assertEqual(b'operator file', target.read_bytes())

    def test_selection_uses_wwise_name_and_preserves_other_record_fields(self):
        path = self.directory / 'AudioLangRedord.txt'
        before = b'{"AudioLang":"English","operator":7}'
        path.write_bytes(before)
        record, old, new = audio.select_audio(self.directory, 'cn')
        self.assertEqual(path, record)
        self.assertEqual(before, old)
        self.assertEqual(before, path.read_bytes())
        self.assertEqual({'AudioLang': 'Chinese(PRC)', 'operator': 7}, json.loads(new))

    def test_invalid_index_version_or_duplicate_path_fails_closed(self):
        self.index([self.row])
        with self.assertRaises(ValueError):
            audio.read_index(self.directory, 'cn', expected='0' * 64)
        with self.assertRaises(ValueError):
            self.index([self.row, self.row])

    def test_noncanonical_paths_are_rejected(self):
        for path in ('Chinese(PRC)/../outside.pck', '/outside.pck', 'C:/outside.pck',
                     'Chinese(PRC)\\outside.pck', 'Chinese(PRC)//outside.pck'):
            with self.subTest(path=path), self.assertRaises(ValueError):
                self.index([dict(self.row, Path=path)])


@unittest.skipUnless((CLIENT / audio.AUDIO / audio.INDEX).exists(), '没有固定的本机音频索引')
class InstalledChineseVoiceRegression(unittest.TestCase):
    def test_current_index_has_every_chinese_package_and_matching_marker(self):
        directory = CLIENT / audio.AUDIO
        rows = audio.read_index(directory, 'cn')
        self.assertEqual(175, len(rows))
        report = audio.audit(directory, rows)
        self.assertTrue(report['complete'], report['issues'])


if __name__ == '__main__':
    unittest.main()
