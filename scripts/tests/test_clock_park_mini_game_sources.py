"""核对原始小游戏和四剧本分支，固定本机完整原生证据不代表实际游戏验收。"""
import json
from pathlib import Path
import unittest

ROOT=Path(__file__).resolve().parents[2]

class ClockParkMiniGameSourcesTest(unittest.TestCase):
    def setUp(self):
        p=ROOT/'src/test/resources/fixtures'
        self.files={}
        for name in ['clock-park-catalog-4.6.json','clock-park-dice-4.6.json']:
            d=json.loads((p/name).read_text('utf-8'))
            self.assertEqual('724b139d8c9c32d12552eb95745a4fee72bfe48b',d['sourceCommit'])
            self.files.update(d['files'])
    def table(self,name):
        return json.loads(bytes.fromhex(self.files['ExcelOutput/'+name+'.json']['rawHex']))
    def row(self,name,key,value):
        rows=[r for r in self.table(name) if r.get(key)==value]
        self.assertEqual(1,len(rows));return rows[0]
    def test_original_three_games_follow_all_normal_rounds_and_have_real_checkpoints(self):
        for chapter,round_id,count,point,kind in [(302,30208,4,321,'HanuShooting'),(410,41009,8,411,'MovieGame'),(420,42009,8,421,'MovieGame')]:
            row=self.row('ClockParkChapterConfig','ChapterID',chapter)
            self.assertEqual(list(range(chapter*100+1,chapter*100+count+1)),row['ChapterRoundIDList'])
            self.assertEqual([round_id],row['ChapterGamePlayRoundRandomList'])
            self.assertEqual([point],row['CheckPointList'])
            self.assertEqual(kind,self.row('ClockParkRound','RoundID',round_id)['RoundType'])
    def test_original_game_cards_actions_stage_pairs_and_zero_grade(self):
        for card,action,kind,stage,coefficients,scores in [(3405,341001,'ShootingGameStageID',(0,99),(1,0,0),[0,2,2,2]),(4121,415001,'MovieGameStageID',(50001,0),(1,1,1),[0,1,1,1]),(4225,425001,'MovieGameStageID',(50002,0),(1,1,1),[0,1,1,1])]:
            row=self.row('ClockParkCard','CardID',card);self.assertEqual('Gameplay',row['CardType']);self.assertEqual([action],row['CardActionList']);self.assertEqual(1,row['CardDiceNum'])
            row=self.row('ClockParkCardAction','CardActionID',action);self.assertEqual([1,2,3,4,5,6],row['DiceList']);self.assertEqual([action],row['EffectList']);self.assertEqual([action+10],row['SuccessEffectList'])
            row=self.row('ClockParkEffect','EffectID',action);self.assertEqual(kind,row['EffectType']);self.assertEqual(stage,(row.get('Param1',0),row.get('Param2',0)))
            row=self.row('ClockParkEffect','EffectID',action+10);self.assertEqual('AttributeChangeByGameScore',row['EffectType']);self.assertEqual(coefficients,tuple(row.get('Param'+str(i),0) for i in (1,2,3)));self.assertEqual(scores,row['ParamList']);self.assertEqual(0,row['ParamList'][0])
    def test_fourth_branch_original_faces_and_extra_zero_deltas(self):
        for card,targets in [(4122,[420]*6),(4226,[440,440,430,430,430,430]),(4322,[450]*6),(4422,[460]*6)]:
            row=self.row('ClockParkCard','CardID',card);actual={}
            for action in row['CardActionList']:
                r=self.row('ClockParkCardAction','CardActionID',action);effect=self.row('ClockParkEffect','EffectID',r['EffectList'][0]);self.assertEqual('ToChapter',effect['EffectType'])
                for face in r['DiceList']:self.assertNotIn(face,actual);actual[face]=effect['Param1']
                for extra in r['EffectList'][1:]:
                    effect=self.row('ClockParkEffect','EffectID',extra);self.assertEqual('AttributeChange',effect['EffectType']);self.assertEqual((0,0,0),tuple(effect.get('Param'+str(i),0)for i in (1,2,3)))
            self.assertEqual(targets,[actual[i]for i in range(1,7)])
    def test_original_client_score_count_and_shooting_start_level(self):
        rows={r['ConstValueName']:r['Value'] for r in self.table('ClockParkConst')}
        self.assertEqual(4,rows['ClockPark_GamePlay_Score_Level_Num']['IntValue'])
        self.assertEqual(3,rows['Activity_ClockPark_Shooting_Game_Start_Level']['IntValue'])
    def test_frozen_local_native_methods_are_complete_and_wait_cases_match(self):
        d=json.loads((ROOT/'src/test/resources/fixtures/clock-park-mini-native-4.6.json').read_text('utf-8'))
        self.assertEqual('8cc7380066343b6152069a6d1c90e230322aa78b780b1e738c13185b3692f1d3',d['files']['GameAssembly.dll'])
        self.assertFalse(d['clientExecuted']);self.assertFalse(d['clientModified']);self.assertFalse(d['clientAccepted'])
        self.assertEqual(11,len(d['methods']))
        for m in d['methods']:
            start,end=int(m['rva'],16),int(m['verifiedNativeEndRva'],16)
            self.assertTrue(m['instructions']);self.assertTrue(all(start<=int(i.split(':')[0],16)<end for i in m['instructions']))
        method=next(m for m in d['methods']if m['index']==566373)
        self.assertIn('cb7d547: cmp dword ptr [r15 + 0x24], 0xb',method['instructions'])
        self.assertIn('cb7d5af: cmp dword ptr [r15 + 0x24], 0xd',method['instructions'])

if __name__=='__main__':unittest.main()
