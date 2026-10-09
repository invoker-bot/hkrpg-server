"""五个活动进度任务的完整固定来源，不宣称全剧情或客户端验收。"""
import hashlib,json,unittest
from pathlib import Path
ROOT=Path(__file__).resolve().parents[2]
FIXTURE=ROOT/'src/test/resources/fixtures/clock-park-mission-progress-4.6.json'
DATA=json.loads(FIXTURE.read_text('utf-8'))

class ClockParkMissionSourceTest(unittest.TestCase):
 def test_all_four_original_inputs_match_the_recorded_whole_file_hash(self):
  for name,expected in DATA['sourceFiles'].items():
   raw=(ROOT/'resources/4.6'/name).read_bytes().replace(b'\r\n',b'\n')
   self.assertEqual(expected,hashlib.sha256(raw).hexdigest(),name)
 def test_five_source_targets_have_the_exact_authored_progress_and_backtracking(self):
  self.assertEqual({802210103:2000,802210201:4000,802210205:6000,802210207:8000,802210401:10000},
   {row['ID']:row['ParamInt1'] for row in DATA['rows']})
  for row in DATA['rows']:
   self.assertEqual(('ClockParkProgress','GreaterEqual',1,True,20321,20321001),
    tuple(row[k] for k in ['FinishType','ParamType','Progress','IsBackTrack','LevelPlaneID','LevelFloorID']))
 def test_fixture_preserves_each_complete_original_row_and_original_screen_task(self):
  actual=[]
  for name in DATA['sourceFiles']:
   raw=json.loads((ROOT/'resources/4.6'/name).read_text('utf-8'))
   if 'SubMissionList' in raw:actual.extend(s for s in raw['SubMissionList'] if s.get('FinishType')=='ClockParkProgress')
   else:self.assertEqual(DATA['script'],raw)
  self.assertEqual(DATA['rows'],actual)
  task=DATA['script']['OnStartSequece'][0]['TaskList'][0]
  self.assertEqual({'$type':'RPG.GameCore.SetFloorCustomString','Name':{'Value':'ScreenContent'},'Value':{'Value':'Idle'}},task)
 def test_provenance_and_completion_boundaries_remain_explicit(self):
  self.assertEqual('724b139d8c9c32d12552eb95745a4fee72bfe48b',DATA['sourceCommit'])
  self.assertFalse(DATA['clientGameplayVerified']);self.assertFalse(DATA['fullStoryScriptExecutorInstalled'])
  self.assertEqual(4,len(DATA['sourceFiles']));self.assertEqual(5,len(DATA['rows']))

if __name__=='__main__':unittest.main()
