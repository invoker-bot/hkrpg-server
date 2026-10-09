"""原始奇物、修复与奖池关联；项目抽奖政策不得冒充已验证的原版规则。"""
import json
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[2]


class RogueGalacticLotterySourcesTest(unittest.TestCase):
    def setUp(self):
        self.proof = json.loads((ROOT / 'src/test/resources/fixtures/rogue-galactic-lottery-4.6.json').read_text('utf-8'))
        self.assertEqual('724b139d8c9c32d12552eb95745a4fee72bfe48b', self.proof['sourceCommit'])

    def rows(self, kind):
        return [r['row'] for r in self.proof['records'] if r['kind'] == kind]

    def test_original_lottery_and_repair_link_to_exact_effect_parameters(self):
        miracles = {r['MiracleID']: r for r in self.rows('miracle')}
        effects = {r['MiracleEffectID']: r for r in self.rows('effect')}
        self.assertEqual({'MiracleID':63, 'MiracleDisplayID':44, 'MiracleEffectDisplayID':63,
                          'UnlockHandbookMiracleID':63}, miracles[63])
        self.assertEqual({'MiracleID':17, 'MiracleDisplayID':15, 'MiracleEffectDisplayID':17,
                          'UnlockHandbookMiracleID':17}, miracles[17])
        self.assertEqual([{'Value':.99}], effects[63]['ParamList'])
        self.assertEqual([{'Value':2}], effects[17]['ParamList'])
        self.assertEqual(44, self.rows('display')[0]['MiracleDisplayID'])

    def test_original_pool_retains_unsupported_and_self_entries_for_runtime_filtering(self):
        pool = self.rows('pool')[0]
        self.assertEqual(10004, pool['RogueMiracleGroupID'])
        weights = {int(k):v for k,v in pool['MiracleWeight'].items()}
        self.assertEqual(68, len(weights))
        self.assertTrue({11,17,21,57,63,102,901,902}.issubset(weights))
        self.assertTrue(all(k > 0 and isinstance(v,int) and v > 0 for k,v in weights.items()))

    def test_project_odds_and_reward_pool_are_explicitly_unverified(self):
        self.assertIs(False, self.proof['officialOddsAndPoolVerified'])
        self.assertEqual({'gainTickets':5,'breakTickets':5,'totalTickets':100,'rewardPool':10004,
                          'excludedAcquisitionIds':[21,63]}, self.proof['projectPolicy'])
        self.assertEqual(4,len(self.proof['files']))
        for proof in self.proof['files'].values():
            self.assertRegex(proof['resourceSHA256'], r'^[0-9a-f]{64}$')
            self.assertRegex(proof['originalBlobSHA256'], r'^[0-9a-f]{64}$')
            self.assertRegex(proof['gitBlobSHA1'], r'^[0-9a-f]{40}$')


if __name__ == '__main__':
    unittest.main()
