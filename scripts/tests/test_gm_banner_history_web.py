"""完整归档与来源约束的回归；损坏输入不能被生成脚本悄悄降级成只读条目。"""
import copy
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location('gm_banner_history', ROOT / 'scripts/generate-gm-banner-history.py')
generator = importlib.util.module_from_spec(spec)
spec.loader.exec_module(generator)


class CompleteBannerHistoryTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.evidence = generator.load(generator.EVIDENCE)
        cls.archive = generator.load(ROOT / 'src/main/resources/gm/banner-history.json')

    def test_all_supported_resources_are_complete_and_have_official_sources(self):
        rows = self.archive['banners']
        self.assertEqual(278, len(rows))
        self.assertEqual(278, len({r['id'] for r in rows}))
        self.assertEqual(31, len({r['version'] for r in rows}))
        self.assertEqual(95, len(self.evidence['notices']))
        for row in rows:
            self.assertTrue(row['replayable'])
            self.assertEqual(3, len(set(row['rateUpItems4'])))
            self.assertRegex(row['sourceUrl'], r'^https://www\.hoyolab\.com/article/[0-9]+$')
            self.assertNotIn('<', self.archive['itemNames'][str(row['rateUpItems5'][0])])

    def test_generator_reproduces_the_entire_bundled_archive(self):
        self.assertEqual(self.archive, generator.generate())

    def test_source_digest_survives_windows_and_git_checkout_line_endings(self):
        with tempfile.TemporaryDirectory() as directory:
            evidence_file = Path(directory) / 'facts.json'
            content = json.dumps(self.evidence, ensure_ascii=False, indent=2) + '\n'
            results = []
            for newline in ('\n', '\r\n'):
                evidence_file.write_bytes(content.replace('\n', newline).encode('utf-8'))
                with patch.object(generator, 'EVIDENCE', evidence_file):
                    results.append(generator.generate())
            self.assertEqual(results[0], results[1])

    def test_new_install_defaults_use_the_verified_historical_up_pools(self):
        rows = {r['id']: r for r in self.archive['banners']}
        for row in generator.load(ROOT / 'src/main/resources/defaults/Banners.json'):
            if row['gachaType'] in ('AvatarUp', 'WeaponUp'):
                self.assertEqual(sorted(rows[row['id']]['rateUpItems4']), sorted(row['rateUpItems4']),
                                 f"default banner {row['id']}")

    def assert_invalid(self, change):
        evidence = copy.deepcopy(self.evidence)
        change(evidence)
        with self.assertRaises(ValueError):
            generator.generate(evidence)

    def test_missing_banner_is_rejected(self):
        self.assert_invalid(lambda e: e['banners'].pop())

    def test_duplicate_banner_is_rejected(self):
        self.assert_invalid(lambda e: e['banners'].append(copy.deepcopy(e['banners'][0])))

    def test_incomplete_up_triplet_is_rejected(self):
        self.assert_invalid(lambda e: e['banners'][0]['rateUpItems4'].pop())

    def test_wrong_rarity_cannot_enter_a_four_star_up_pool(self):
        self.assert_invalid(lambda e: e['banners'][0]['rateUpItems4'].__setitem__(0, 1102))

    def test_missing_official_notice_is_rejected(self):
        self.assert_invalid(lambda e: e['banners'][0].__setitem__('sourceUrl', 'https://example.invalid'))

    def test_three_phase_history_cannot_collapse_into_a_second_half(self):
        self.assert_invalid(lambda e: next(r for r in e['banners'] if r['id'] == 2103).__setitem__('phase', '下半'))

    def test_inherited_errors_and_latest_banners_have_explicit_correct_triplets(self):
        rows = {r['id']: r for r in self.archive['banners']}
        expected = {2002: [1001, 1202, 1206], 3002: [21002, 21003, 21011],
                    3012: [21003, 21005, 21015], 3073: [21013, 21046, 21047],
                    3074: [21013, 21046, 21047], 2099: [1001, 1111, 1301],
                    2139: [1201, 1214, 1312], 3139: [21000, 21011, 21044]}
        for banner_id, up in expected.items():
            self.assertEqual(up, rows[banner_id]['rateUpItems4'])


if __name__ == '__main__':
    unittest.main()
