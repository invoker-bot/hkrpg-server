"""核对原始日常、首波弱点与完整普通攻击文件；不把资源关联当成官方 Excellent 判定。"""
import hashlib
import json
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[2]


class DailyWeaknessEntrySourcesTest(unittest.TestCase):
    def setUp(self):
        self.fixture = json.loads((ROOT / 'src/test/resources/fixtures/daily-weakness-entry-4.6.json').read_text('utf-8'))
        self.assertEqual('724b139d8c9c32d12552eb95745a4fee72bfe48b', self.fixture['sourceCommit'])

    def rows(self, kind):
        return [r['row'] for r in self.fixture['records'] if r['kind'] == kind]

    def test_original_daily_goals_pool_links_and_virtual_reward(self):
        quests = {r['QuestID']: r for r in self.rows('quest')}
        rules = {r['ID']: r for r in self.rows('rule')}
        pools = {r['QuestID']: r['Type'] for r in self.rows('pool')}
        self.assertEqual({2100123: 3, 2100142: 11}, pools)
        for quest, goal in [(2100123, 3), (2100142, 1)]:
            row = quests[quest]
            self.assertEqual((5, 'AutoUnlock', [], 103002, quest),
                             (row['QuestType'], row['UnlockType'], row['UnlockParamList'], row['RewardID'], row['FinishWayID']))
            self.assertEqual({'ID': quest, 'FinishType': 'ExcellentEnterBattleAndWin', 'ParamType': 'NoPara',
                              'ParamStr1': '', 'ParamIntList': [], 'ParamItemList': [], 'Progress': goal}, rules[quest])
        self.assertEqual([{'RewardID': 103002, 'ItemID_1': 23, 'Count_1': 100}], self.rows('reward'))
        item = self.rows('item')[0]
        self.assertEqual((23, 'Virtual', 'Virtual'), (item['ID'], item['ItemMainType'], item['ItemSubType']))

    def test_original_stage_first_wave_weakness_and_actual_elemental_buff(self):
        monsters = {r['MonsterID']: r for r in self.rows('monster')}
        stages = {r['StageID']: r for r in self.rows('stage')}
        for stage, ice in [(201342210, True), (201011330, False)]:
            wave = [v for v in stages[stage]['MonsterList'][0].values() if v]
            self.assertTrue(wave)
            self.assertTrue(all(m in monsters for m in wave))
            self.assertEqual(ice, any('Ice' in monsters[m]['StanceWeakList'] for m in wave))
        for buff, element in zip(self.rows('buff'), ['Ice', 'Fire']):
            self.assertEqual((1, 1, 'CharacterAbility', []),
                             (buff['Lv'], buff['LvMax'], buff['InBattleBindingType'], buff['ParamList']))
            self.assertEqual('StageAbility_MazeCommon_EnterBattle_' + element, buff['InBattleBindingKey'])
            self.assertEqual('ADV_' + buff['InBattleBindingKey'], buff['ModifierName'])
        self.assertEqual(['Ice', 'Fire'], [r['DamageType'] for r in self.rows('avatar')])

    def test_complete_original_ability_has_canonical_basic_attack_and_separate_technique(self):
        evidence = self.fixture['abilityFile']
        raw = bytes.fromhex(evidence['rawHex'])
        self.assertEqual(evidence['resourceSHA256'], hashlib.sha256(raw).hexdigest())
        self.assertEqual('Config/ConfigAdventureAbility/LocalPlayer/LocalPlayer_Mar_7th_00_Ability.json', evidence['path'])
        abilities = json.loads(raw)['AbilityList']
        self.assertEqual(['LocalPlayer_Mar_7th_NormalAtk01', 'LocalPlayer_Mar_7th_MazeSkill'],
                         [r['Name'] for r in abilities])
        self.assertTrue(all(r['OnStart'] for r in abilities))


if __name__ == '__main__':
    unittest.main()
