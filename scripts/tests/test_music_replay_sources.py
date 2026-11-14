"""Guard the frozen activity contract independently of its generator's field selection."""
import json
from pathlib import Path
import re
import unittest

ROOT = Path(__file__).resolve().parents[2]


class MusicReplaySources(unittest.TestCase):
    def setUp(self):
        self.records = json.loads((ROOT / 'src/main/resources/gameplay/music-replay-4.6.json').read_text('utf8'))['records']

    def test_frozen_music_rows_retain_every_declared_runtime_parameter(self):
        source = (ROOT / 'src/main/java/org/hkrpg/server/gameplay/GameplayDefinitions.java').read_text('utf8')
        tables = {'MusicLevel': 'MusicRhythmLevel', 'MusicGroup': 'MusicRhythmGroup',
                  'MusicPhase': 'MusicRhythmPhase', 'MusicSong': 'MusicRhythmSong',
                  'MusicPreset': 'MusicRhythmPresetSong', 'MusicTrack': 'MusicRhythmTrack'}
        for record, table in tables.items():
            declaration = re.search(r'public record ' + record + r'\((.*?)\)\s*\{', source, re.S).group(1)
            fields = set(re.findall(r'(?:int|List<Integer>)\s+(\w+)', declaration))
            self.assertGreater(len(fields), 1, record)
            for row in self.records[table]:
                self.assertEqual(fields, set(row), (record, row))

    def test_goal_set_and_reward_contract_are_the_original_panel_contract(self):
        r = self.records
        panel_ids = set(map(int, re.findall(r'\[QuestClose:(\d+)\]', r['ActivityPanel'][0]['FinishConditions'])))
        self.assertEqual(20, len(panel_ids))
        self.assertEqual(panel_ids, {q['QuestID'] for q in r['QuestData']})
        self.assertNotIn(6029238, panel_ids)
        self.assertEqual(27, len(r['MusicRhythmLevel']))
        self.assertEqual(54, sum(sum(bool(v) for v in level['StarRewardIDList']) for level in r['MusicRhythmLevel']))
        offering = sorted(r['OfferingLevelConfig'], key=lambda level: level['Level'])
        self.assertEqual(list(range(1, 17)), [v['Level'] for v in offering])
        self.assertEqual([3] * 16, [v['ItemCost'] for v in offering])
        self.assertEqual(list(range(260001, 260017)), [v['RewardID'] for v in offering])


if __name__ == '__main__':
    unittest.main()
