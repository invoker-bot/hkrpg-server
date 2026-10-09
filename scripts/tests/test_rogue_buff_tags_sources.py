"""原始祝福Tag与分组完整性；不是完整回响玩法验收。"""
import json
from pathlib import Path
import unittest
ROOT = Path(__file__).resolve().parents[2]

class RogueBuffTagSourcesTest(unittest.TestCase):
    def setUp(self):
        self.proof = json.loads((ROOT / 'src/test/resources/fixtures/rogue-buff-tags-4.6.json').read_text('utf-8'))
    def test_full_original_rows_and_nested_groups_are_retained(self):
        p = self.proof
        self.assertEqual('724b139d8c9c32d12552eb95745a4fee72bfe48b',p['sourceCommit'])
        self.assertEqual(25,len(p['groups']))
        self.assertEqual(143,len(p['buffs']))
        self.assertEqual(143,len({r['RogueBuffTag'] for r in p['buffs']}))
        groups = {r['GMLOGNJAIGI'] for r in p['groups']}
        tags = {r['RogueBuffTag'] for r in p['buffs']}
        self.assertFalse(groups & tags)
        self.assertTrue(all(c in groups | tags for r in p['groups'] for c in r['HECJCAMDGNO']))
        self.assertEqual(612020,next(r['MazeBuffID'] for r in p['buffs'] if r['RogueBuffTag'] == 1202001))
        self.assertTrue(p['groupEntriesAreRogueBuffTagsOrNestedGroups'])
    def test_installed_metadata_retains_the_distinct_tag_and_aeon_fields(self):
        row = self.proof['nativeRogueBuffRow']
        self.assertEqual(14731,row['typeIndex'])
        self.assertEqual('RogueBuffRow',row['name'])
        fields = {r['name']:r['nativeType'] for r in row['fields']}
        for key in ['MazeBuffID','RogueBuffTag','AeonID']:
            self.assertEqual(9,fields[key]['kind'])
        self.assertEqual('RogueBuffAeonType',fields['BattleEventBuffType']['name'])
    def test_partial_resonance_and_game_ui_are_not_reported_as_complete(self):
        self.assertFalse(self.proof['completeResonanceRuntimeImplemented'])
        self.assertFalse(self.proof['clientGameplayVerified'])
        for row in self.proof['files'].values():
            for key in ['resourceSHA256','originalBlobSHA256']:
                self.assertRegex(row[key],'^[0-9a-f]{64}$')

if __name__ == '__main__': unittest.main()
