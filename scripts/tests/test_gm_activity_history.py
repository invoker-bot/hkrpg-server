import importlib.util
import json
from pathlib import Path
import unittest

ROOT=Path(__file__).resolve().parents[2]
spec=importlib.util.spec_from_file_location('gm_activity_history',ROOT/'scripts/generate-gm-activity-history.py')
module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module)


class HistoricalActivities(unittest.TestCase):
    def test_all_31_versions_are_retained_and_external_future_events_are_separate(self):
        archive=module.generate()
        self.assertEqual(31,len(archive['versions']))
        self.assertEqual(208,len(archive['events']))
        self.assertEqual(208,len({e['key'] for e in archive['events']}))
        self.assertTrue(all('网页活动' not in e['categories'] and e['version']!='4.7' for e in archive['events']))
        self.assertEqual({'1.0','4.6'},{archive['versions'][0],archive['versions'][-1]})

    def test_original_editions_and_source_windows_are_not_overwritten_by_rerun_times(self):
        archive=module.generate();events=archive['events']
        first=next(e for e in events if e['name']=='「巡星之礼」')
        last=next(e for e in events if e['name']=='「巡星之礼」第二十七期')
        self.assertEqual(('1.1',10018,'login'),(first['version'],first['activityId'],first['family']))
        self.assertEqual(('4.6',10186,'login'),(last['version'],last['activityId'],last['family']))
        self.assertNotEqual(first['historicalStart'],last['historicalStart'])
        self.assertTrue(all(e['sourceUrl'].startswith('https://wiki.biligame.com/sr/') for e in events))

    def test_explicit_material_group_and_ambiguous_matches_cannot_be_guessed(self):
        archive=module.generate();events=archive['events']
        material=next(e for e in events if '圣城鉴宝' in e['name'])
        self.assertEqual(30014,material['activityId']);self.assertEqual('material-submit',material['family'])
        self.assertEqual(list(range(3001401,3001408)),material['modules'])
        ambiguous=next(e for e in events if e['name']=='「位面分裂」')
        self.assertEqual(0,ambiguous['activityId'])

    def test_bundled_archive_is_exactly_regenerable(self):
        self.assertEqual(module.generate(),json.loads((ROOT/'src/main/resources/gm/activity-history.json').read_text('utf8')))

    def test_removed_material_panels_are_excluded_without_excluding_preserved_engines(self):
        events=module.generate()['events']
        for name,activity in [('实验助手请就位',30005),('匹诺康尼美食庆典',30009)]:
            entry=next(e for e in events if name in e['name'])
            self.assertEqual(activity,entry['activityId'])
            self.assertEqual('excluded',entry['family'])
            self.assertEqual('missing-panel',entry['resourceStatus'])
            self.assertEqual(0,entry['panelId'])
        museum=next(e for e in events if '冬城博物珍奇簿' in e['name'])
        self.assertEqual('available',museum['resourceStatus'])
        self.assertNotEqual('excluded',museum['family'])


if __name__=='__main__':unittest.main()
