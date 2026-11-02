"""固定音律复刻的玩法与奖励参数；不包含本地化正文，不修改原始资源。"""
import argparse
import hashlib
import json
from pathlib import Path
import re

ROOT=Path(__file__).resolve().parents[1]
COMMIT='724b139d8c9c32d12552eb95745a4fee72bfe48b'
FIELDS={
 'ActivityConfig':'ActivityID ActivityPanelID ResidentModuleList ActivityModuleIDList',
 'ActivityPanel':'PanelID UIPrefab UnlockConditions FinishConditions',
 'QuestData':'QuestID QuestType RewardID FinishWayID UnlockType UnlockParamList',
 'FinishWay':'ID FinishType ParamType ParamInt1 ParamInt2 ParamInt3 ParamStr1 ParamIntList ParamItemList Progress IsBackTrack',
 'MusicRhythmGroup':'ID Phase UnlockSubMissionID RewardTrackIDList',
 'MusicRhythmLevel':'ID Group Difficulty StarScoreList StarRewardIDList',
 'MusicRhythmPhase':'Phase SongID TrackIDList FinishMissionID',
 'MusicRhythmSong':'ID UnlockType UnlockTypeParam GridNumList TrackIDList PresetIDList SoundEffectUnlockSubMission SoundEffectIDList',
 'MusicRhythmTrack':'ID UnlockSubMissionID EmptyGridList',
 'MusicRhythmPresetSong':'ID PresetGridConfig',
 'MusicRhythmConstValueCommon':'ConstValueName Value',
}


def generate():
 sources={};tables={}
 def read(name):
  path=ROOT/'resources/4.6/ExcelOutput'/f'{name}.json';raw=path.read_bytes();value=json.loads(raw)
  sources[path.relative_to(ROOT/'resources/4.6').as_posix()]={'sha256':hashlib.sha256(raw).hexdigest(),'rowCount':len(value)}
  return value
 def select(name,predicate):return [r for r in read(name) if predicate(r)]
 for name in FIELDS:
  rows=read(name)
  if name=='ActivityConfig':rows=[r for r in rows if r['ActivityID']==50022]
  if name=='ActivityPanel':rows=[r for r in rows if r['PanelID']==50022]
  tables[name]=rows
 ids={int(s) for s in re.findall(r'\[QuestClose:(\d+)\]',tables['ActivityPanel'][0]['FinishConditions'])}
 assert len(ids)==20
 tables['QuestData']=[r for r in tables['QuestData'] if r['QuestID'] in ids]
 tables['FinishWay']=[r for r in tables['FinishWay'] if r['ID'] in ids]
 tables['OfferingTypeConfig']=select('OfferingTypeConfig',lambda r:r['ID']==5)
 tables['OfferingLevelConfig']=select('OfferingLevelConfig',lambda r:r['TypeID']==5)
 reward_ids={r['RewardID'] for r in tables['QuestData']+tables['OfferingLevelConfig']}
 reward_ids.update(i for r in tables['MusicRhythmLevel'] for i in r['StarRewardIDList'] if i)
 tables['RewardData']=select('RewardData',lambda r:r['RewardID'] in reward_ids)
 item_ids={270003}
 for r in tables['RewardData']:
  for key,value in r.items():
   if key.startswith('ItemID_') and value:item_ids.add(value)
  for key,item in [('Hcoin',1),('Exp',22),('Scoin',2),('Mcoin',3)]:
   if r.get(key):item_ids.add(item)
 tables['ItemConfig']=select('ItemConfig',lambda r:r['ID'] in item_ids)
 main_ids={8026101,8026102,8026103,8026104,8026120}
 tables['MainMission']=select('MainMission',lambda r:r['MainMissionID'] in main_ids)
 for name,fields in FIELDS.items():
  allowed=set(fields.split());tables[name]=[{k:v for k,v in r.items() if k in allowed} for r in tables[name]]
 return {'schemaVersion':1,'sourceRepository':'DimbreathBot/TurnBasedGameData','sourceCommit':COMMIT,
  'scope':'原始27关、9组、3阶段、20个限时目标、演出报酬5的16档及其原始费用/奖励；剧情和地图前置不改写。',
  'sources':sources,'records':tables}


if __name__=='__main__':
 parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--check',action='store_true');args=parser.parse_args()
 result=generate();out=ROOT/'src/main/resources/gameplay/music-replay-4.6.json'
 if args.check:assert json.loads(out.read_text('utf8'))==result,'音律复刻固定来源已变化'
 else:
  out.parent.mkdir(parents=True,exist_ok=True);out.write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n','utf8')
 print(json.dumps({k:len(v) for k,v in result['records'].items()}))
