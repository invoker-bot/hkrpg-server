"""固定原始摆放目标及完整输入文件，保留无回溯与 NoPara 的原值。"""
import hashlib,json,unittest
from pathlib import Path
ROOT=Path(__file__).resolve().parents[2]
DATA=json.loads((ROOT/'src/test/resources/fixtures/match-three-bird-position-4.6.json').read_text('utf-8'))

class MatchThreeBirdPlacementSourceTest(unittest.TestCase):
 def test_whole_source_inputs_match_recorded_bytes_and_sha256(self):
  self.assertEqual(8,len(DATA['files']))
  for name,row in DATA['files'].items():
   raw=(ROOT/'resources/4.6'/name).read_bytes().replace(b'\r\n',b'\n')
   self.assertEqual(raw,bytes.fromhex(row['rawHex']),name)
   self.assertEqual(row['sha256'],hashlib.sha256(raw).hexdigest(),name)
 def test_each_fixture_target_is_the_complete_original_row(self):
  rows=[]
  for name in DATA['files']:
   if name.startswith('Config/'):
    rows.extend(s for s in json.loads(bytes.fromhex(DATA['files'][name]['rawHex']))['SubMissionList']if s.get('FinishType')=='MatchThreeBirdPos')
  self.assertEqual(DATA['rows'],rows)
  self.assertEqual({802310205:(2,2,300),802310206:(2,1,301),802310305:(2,3,302),802310405:(2,4,303),802310506:(2,6,305)},
   {r['ID']:tuple(r[k]for k in ['ParamInt1','ParamInt2','ParamInt3'])for r in rows})
 def test_original_no_backtrack_no_para_and_floor_actions_are_retained(self):
  by_id={r['ID']:r for r in DATA['rows']}
  self.assertEqual('NoPara',by_id[802310305]['ParamType'])
  for row in by_id.values():
   self.assertFalse(row.get('IsBackTrack',False))
   self.assertEqual((1,10341,10341001),tuple(row[k]for k in ['Progress','LevelPlaneID','LevelFloorID']))
  for id,value in [(802310206,'2'),(802310305,'3')]:
   self.assertEqual([dict(FinishActionType='SetFloorSavedValue',FinishActionPara=[],FinishActionParaString=['10341','10341001','Activity230DIYEntrance',value])],by_id[id]['FinishActionList'])
 def test_provenance_and_boundaries(self):
  self.assertEqual('724b139d8c9c32d12552eb95745a4fee72bfe48b',DATA['sourceCommit'])
  self.assertFalse(DATA['clientGameplayVerified']);self.assertFalse(DATA['fullStoryScriptExecutorInstalled'])

if __name__=='__main__':unittest.main()
