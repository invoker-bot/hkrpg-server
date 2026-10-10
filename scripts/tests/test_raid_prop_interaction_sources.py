"""40431 原始机关、无费用交互、楼层索引及完整剧情前置来源。"""
import hashlib
import json
from pathlib import Path
import subprocess
import unittest

ROOT = Path(__file__).resolve().parents[2]
PIN = '724b139d8c9c32d12552eb95745a4fee72bfe48b'

class RaidPropInteractionSources(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.fixture=json.loads((ROOT/'src/test/resources/fixtures/raid-prop-interaction-4.6.json').read_text('utf-8'))
        cls.records=cls.fixture['records']

    def test_all_seven_whole_source_blobs_and_checkout_hashes(self):
        self.assertEqual(PIN,self.fixture['sourceCommit'])
        self.assertEqual(7,len(self.fixture['sourceFiles']))
        names=list(self.fixture['sourceFiles'])
        raw=subprocess.run(['git','-C',str(ROOT/'references/TurnBasedGameData'),'cat-file','--batch'],input=''.join(PIN+':'+p+'\n' for p in names).encode(),capture_output=True,check=True).stdout
        cursor=0
        for name in names:
            end=raw.index(b'\n',cursor);_,kind,size=raw[cursor:end].split();self.assertEqual(b'blob',kind)
            cursor=end+1;blob=raw[cursor:cursor+int(size)];cursor+=int(size)+1
            local=(ROOT/'resources/4.6'/name).read_bytes();pins=self.fixture['sourceFiles'][name]
            self.assertEqual(pins['gitBlobSHA256'],hashlib.sha256(blob).hexdigest())
            self.assertEqual(pins['checkoutSHA256'],hashlib.sha256(local).hexdigest())
            self.assertEqual(json.loads(blob),json.loads(local))
            data=json.loads(blob)
            selected=self.records[name]
            if name.endswith('MazeProp.json'):data=[r for r in data if r['ID'] in {100,104022,104031,104191}]
            elif name.endswith('InteractConfig.json'):data=[r for r in data if r['InteractID'] in {1010,1011}]
            self.assertEqual(data,selected)
        self.assertEqual(len(raw),cursor)

    def test_original_orb_and_console_keep_their_authored_properties(self):
        group=self.records['Config/LevelOutput/SharedRuntimeGroup/Groups_P40431_F40431002/LevelGroup_P40431_F40431002_G46.json']
        self.assertEqual('Server',group['LoadSide'])
        props={p['ID']:p for p in group['PropList']}
        self.assertEqual(104022,props[300002]['PropID'])
        self.assertNotIn('State',props[300002])
        self.assertEqual(['46,300001'],next(v['Value'] for v in props[300002]['ValueSource']['Values'] if v['Key']=='FlipperPointList'))
        self.assertEqual(104031,props[300001]['PropID'])
        self.assertEqual('Open',props[300001]['State'])
        excel=next(r for r in self.records['ExcelOutput/MazeProp.json'] if r['ID']==104022)
        self.assertEqual('PROP_ORDINARY',excel['PropType'])
        self.assertEqual(['Closed','Open','Locked'],excel['PropStateList'])

    def test_free_source_interaction_and_default_floor_references(self):
        rows=self.records['ExcelOutput/InteractConfig.json'];self.assertEqual({1010,1011},{r['InteractID'] for r in rows})
        for r in rows:self.assertNotIn('SrcState',r);self.assertEqual('Open',r['TargetState']);self.assertEqual([],r['ItemCostList'])
        floor=self.records['Config/LevelOutput/RuntimeFloor/P40431_F40431002.json']
        dim=next(d for d in floor['DimensionList'] if d.get('ID',0)==0)
        refs=[floor['GroupInstanceList'][i] for i in dim['GroupIndexList']]
        for group in [11,33,41,46]:
            selected=[r for r in refs if r['ID']==group];self.assertEqual(1,len(selected))
            self.assertTrue(selected[0]['GroupPath'].endswith('_G'+str(group)+'.json'))

    def test_whole_original_34_objectives_declare_backtracking_in_the_same_raid(self):
        full=json.loads((ROOT/'src/test/resources/fixtures/default-hybrid-mission-raid-4.6.json').read_text('utf-8'))
        self.assertEqual(PIN,full['sourceCommit'])
        rows=full['records']['Config/Level/Mission/1040408/MissionInfo_1040408.partial.json'][0]['SubMissionList']
        self.assertEqual(34,len(rows));goal=next(r for r in rows if r['ID']==104040807)
        self.assertEqual(1040408,goal['MainMissionID']);self.assertEqual('PropState',goal['FinishType']);self.assertEqual('Equal',goal['ParamType']);self.assertTrue(goal['IsBackTrack'])
        self.assertEqual((40431,40431002,46,300002,1),tuple(goal[k] for k in ['LevelPlaneID','LevelFloorID','ParamInt1','ParamInt2','ParamInt3']))
        self.assertEqual([104040806],goal['TakeParamIntList']);self.assertEqual(1,goal['Progress'])
        self.assertFalse(self.fixture['clientGameplayVerified'])

if __name__=='__main__':unittest.main()
