"""保留原始抗瞬杀放置与本机完整函数；仅核对静态证据，不宣称客户端验收。"""
import hashlib
import json
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[2]


class InstantKillSourcesTest(unittest.TestCase):
    def setUp(self):
        self.proof = json.loads((ROOT / 'src/test/resources/fixtures/instant-kill-preflight-4.6.json').read_text('utf-8'))

    def test_complete_original_minion_placements_retain_resistance(self):
        p = self.proof
        self.assertEqual('724b139d8c9c32d12552eb95745a4fee72bfe48b', p['sourceCommit'])
        self.assertEqual(2, len(p['placements']))
        self.assertEqual({8001010: 'Minion', 2002030: 'MinionLv2'}, {r['ID']: r['Rank'] for r in p['npcRows']})
        for entry in p['placements']:
            row = entry['row']
            self.assertEqual(['ResistInstantKill'], row['TagList'])
            self.assertEqual(200001, row['ID'])
            self.assertIn('AIConfig', row)
            self.assertIn('SpawnConfig', row)
            self.assertEqual({'GroupID': 2, 'ID': 1}, row['BattleArea'])
            self.assertIn(entry['source'], p['files'])
        for file in p['files'].values():
            for key in ['resourceSHA256', 'originalBlobSHA256']:
                self.assertRegex(file[key], '^[0-9a-f]{64}$')

    def test_bounded_native_methods_and_named_mode_tag_constants_are_consistent(self):
        p = self.proof
        methods = {r['name']: r for r in p['nativeMethods']}
        self.assertEqual({'TargetAllowInstantKill', 'get_CurrentGameMode', 'GetEntityTag'}, set(methods))
        self.assertEqual(4, p['GameModeType']['values']['Challenge'])
        self.assertEqual(8, p['GameModeType']['values']['Raid'])
        self.assertEqual(3, p['EntityTag']['values']['ResistInstantKill'])
        for name, length in [('TargetAllowInstantKill', 559), ('get_CurrentGameMode', 7), ('GetEntityTag', 1006)]:
            method = methods[name]
            raw = bytes.fromhex(method['bytesHex'])
            self.assertEqual(length, len(raw))
            self.assertEqual(len(raw), int(method['endRva'], 16) - int(method['rva'], 16))
            self.assertEqual(hashlib.sha256(raw).hexdigest(), method['sha256'])
        self.assertEqual('PE Exception Directory', methods['TargetAllowInstantKill']['bounds'])
        self.assertIn('frameless getter RET', methods['get_CurrentGameMode']['bounds'])

    def test_missing_character_tags_and_full_client_skill_acceptance_remain_unverified(self):
        p = self.proof
        for key in ['additionalCharacterConfigTagsVerified', 'completeOriginalAbilityVerified', 'clientGameplayVerified']:
            self.assertIs(False, p[key])
        self.assertEqual('8cc7380066343b6152069a6d1c90e230322aa78b780b1e738c13185b3692f1d3', p['nativeFiles']['GameAssembly.dll'])
        self.assertEqual('09ce2093b78779d4bbe9dc866bb472438efd96cc6546b7cbc34edf4006736130', p['nativeFiles']['global-metadata.dat'])


if __name__ == '__main__':
    unittest.main()
