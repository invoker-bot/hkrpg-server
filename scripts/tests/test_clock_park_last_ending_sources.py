"""核对完整固定来源中的第三/第四剧本四结局、两件奖励和九个普通结局目录。"""
import base64,gzip,json
from pathlib import Path
import unittest
ROOT=Path(__file__).resolve().parents[2]
class ClockParkLastEndingSourcesTest(unittest.TestCase):
    def setUp(self):
        fixture=ROOT/'src/test/resources/fixtures'
        self.catalog=json.loads((fixture/'clock-park-catalog-4.6.json').read_text('utf-8'))
        self.rewards=json.loads((fixture/'clock-park-ending-reward-4.6.json').read_text('utf-8'))
    def table(self,name):
        proof=self.rewards['files']['ExcelOutput/'+name+'.json']
        return json.loads(gzip.decompress(base64.b64decode(proof['gzipBase64'],validate=True)))
    def test_original_four_endings_keep_story_reward_and_progress(self):
        rows=json.loads(bytes.fromhex(self.catalog['files']['ExcelOutput/ClockParkChapterConfig.json']['rawHex']))
        for ending,kind,reward,progress,story in [(305,'TrueEnding',8008003,2000,30501),(306,'FalseEnding',100,0,30601),(450,'TrueEnding',8008005,2000,40501),(460,'FalseEnding',100,0,40601)]:
            row=next(r for r in rows if r['ChapterID']==ending)
            self.assertEqual((kind,reward,progress,[story]),(row['ChapterType'],row['RewardID'],row.get('RewardProgress',0),row['ChapterStoryIDList']))
    def test_third_and_fourth_reward_have_exact_complete_unique_items(self):
        rows=self.table('RewardData')
        items=lambda r:[(v,r.get('Count_'+k.split('_')[1],0))for k,v in r.items()if k.startswith('ItemID_')and v]
        for reward,expected in [(8008003,[(140486,1),(140487,1)]),(8008005,[(140488,1)]),(100,[])]:
            self.assertEqual(expected,items(next(r for r in rows if r['RewardID']==reward)))
        self.assertEqual([(140487,1)],items(next(r for r in rows if r['RewardID']==8008004)))
    def test_all_three_reward_materials_are_unique_original_mission_items(self):
        rows=self.table('ItemConfig')
        for id in (140486,140487,140488):
            row=next(r for r in rows if r['ID']==id)
            self.assertEqual(('Mission','Mission',1),(row['ItemMainType'],row['ItemSubType'],row['PileLimit']))
    def test_original_authored_catalog_has_exactly_nine_ending_chapters(self):
        rows=json.loads(bytes.fromhex(self.catalog['files']['ExcelOutput/ClockParkChapterConfig.json']['rawHex']))
        self.assertEqual([103,205,206,305,306,450,460,550,560],sorted(r['ChapterID']for r in rows if r.get('ChapterType')in('TrueEnding','FalseEnding')))
if __name__=='__main__':unittest.main()
