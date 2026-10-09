"""完整回响原件和明确项目规则；不把隔离KCP或能量种子当作官方战斗验收。"""
import json
from pathlib import Path
import unittest

ROOT=Path(__file__).resolve().parents[2]

class RogueResonanceSourcesTest(unittest.TestCase):
    def setUp(self):
        self.p=json.loads((ROOT/'src/test/resources/fixtures/rogue-resonance-4.6.json').read_text('utf-8'))
    def test_original_nine_paths_and_all_thirty_six_resonance_rows_are_retained(self):
        p=self.p
        self.assertEqual('724b139d8c9c32d12552eb95745a4fee72bfe48b',p['sourceCommit'])
        self.assertEqual(9,len(p['aeons'])); self.assertEqual(18,len(p['groups'])); self.assertEqual(36,len(p['buffs']))
        self.assertEqual({r['RogueBuffTag'] for r in p['buffs']},{t for r in p['groups'] for t in r['HECJCAMDGNO']})
        self.assertEqual({r['MazeBuffID'] for r in p['buffs']},{r['ID'] for r in p['mazeBuffs']})
        self.assertTrue(all(r['MazeBuffLevel']==1 and r['RogueVersion']==1 for r in p['buffs']))
    def test_exact_classic_event_and_active_passive_costs_are_retained(self):
        p=self.p; skills={r['SkillID']:r for r in p['skills']}
        self.assertEqual(list(range(41001,41010)),[r['BattleEventID'] for r in p['events']])
        self.assertEqual(18,len(skills)); self.assertEqual(9,len(p['eventConfigs']))
        for row in p['events']:
            event=row['BattleEventID']; self.assertTrue(row['IsSPReserved'])
            self.assertEqual([event*100+1,event*100+2],row['SkillIDList'])
            self.assertEqual(100,skills[event*100+1]['SPNeed']['Value'])
            self.assertEqual('Skill03',skills[event*100+1]['SkillTriggerKey'])
            self.assertEqual('SkillP01',skills[event*100+2]['SkillTriggerKey'])
            self.assertNotIn('SPNeed',skills[event*100+2])
    def test_same_config_rows_are_explicitly_distinct_nonclassic_events(self):
        p=self.p; self.assertEqual([96121,96123],[r['BattleEventID'] for r in p['duplicateConfigEventRowsNotClassic']])
        classic=next(r for r in p['events'] if r['BattleEventID']==41001)
        self.assertTrue(all(r['Config']==classic['Config'] for r in p['duplicateConfigEventRowsNotClassic']))
    def test_thresholds_are_pinned_project_policy_with_seven_resource_sources(self):
        rule=self.p['projectThresholdPolicy']
        self.assertEqual([3,6,10,14],rule['thresholds']); self.assertFalse(rule['official46ThresholdsVerified'])
        self.assertEqual('ec315bb1cf5cf86ae0f6ee52824e07f1fef9a5c4',rule['referenceCommit'])
        self.assertEqual('AGPL-3.0',rule['referenceLicense']); self.assertIn('pathBuffs >= 14',rule['referenceCode'])
        self.assertEqual(7,len(self.p['files']))
        for row in self.p['files'].values():
            for key in ['resourceSHA256','originalBlobSHA256']: self.assertRegex(row[key],'^[0-9a-f]{64}$')
        self.assertFalse(self.p['clientGameplayVerified']); self.assertFalse(self.p['completeResonanceRuntimeImplemented'])

if __name__=='__main__': unittest.main()
