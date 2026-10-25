"""只读核对空间站章节任务及嵌套脚本动作；源码核对通过不等于客户端全流程验收。"""
import argparse
import hashlib
import json
from collections import Counter
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
EFFECTS = {'EnterMap', 'CreateTrialPlayer', 'DestroyTrialPlayer',
           'SetVirtualLineupBindPlane', 'RemoveVirtualLineupBindPlane',
           'ChangePropState', 'PropReqInteract', 'TriggerBattle'}


def actions(value, path='$'):
    if isinstance(value, dict):
        kind = value.get('$type', '').removeprefix('RPG.GameCore.')
        if kind in EFFECTS:
            yield dict(path=path, type=kind, parameters={k:v for k,v in value.items() if k!='$type'})
        for key, child in value.items():
            yield from actions(child, path+'.'+key)
    elif isinstance(value, list):
        for index, child in enumerate(value):
            yield from actions(child, path+f'[{index}]')


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--resources',type=Path,default=ROOT/'resources/4.6')
    parser.add_argument('--output',type=Path,default=ROOT/'build/opening-story-audit.json')
    args=parser.parse_args()
    source=args.resources.resolve(strict=True);output=args.output.resolve()
    if not output.is_relative_to(ROOT/'build'):raise SystemExit('报告只能写入工程 build 目录')
    hashes={}
    for name in ('station-trial-4.6.json','station-bridges-4.6.json','story-transfers-4.6.json','station-boss-event-4.6.json'):
        fixture=json.loads((ROOT/'src/test/resources/fixtures'/name).read_text('utf-8'))
        for file,digest in fixture['sourceSHA256'].items():
            assert hashlib.sha256((source/file).read_bytes()).hexdigest()==digest,'原始来源变化，必须重新验证: '+file
            hashes[file]=digest
    subs={}
    for path in sorted((source/'Config/Level/Mission').rglob('MissionInfo_*.json')):
        for sub in json.loads(path.read_bytes()).get('SubMissionList',[]):
            subs.setdefault(sub['ID'],{}).update(sub)
    chapter={id:sub for id,sub in subs.items() if 1000000<=sub.get('MainMissionID',0)<1001000}
    types=Counter(s['FinishType'] for s in chapter.values())
    assert len(chapter)==189 and len({s['MainMissionID'] for s in chapter.values()})==26,'章节目标变化，必须扩充验收清单'
    assert set(types)=={'Talk','StageWin','PropState','GetTrialAvatar','SubMissionFinishCnt',
                        'DelTrialAvatar','EnterFloor','KillMonster','CreateCharacter','MessagePerformSectionFinish','FinishMission'}
    rows=[]
    for id,sub in sorted(chapter.items()):
        file=sub.get('MissionJsonPath')
        effects=[]
        if file:
            assert (source/file).is_file(),'缺少剧情脚本: '+file
            raw=(source/file).read_bytes();hashes[file]=hashlib.sha256(raw).hexdigest()
            effects=list(actions(json.loads(raw)))
        rows.append(dict(main=sub['MainMissionID'],sub=id,finish=sub['FinishType'],
                         predecessors=sub.get('TakeParamIntList',[]),script=file,effects=effects))
    transfers=[dict(sub=r['sub'],**action['parameters']) for r in rows for action in r['effects'] if action['type']=='EnterMap']
    assert {r['sub'] for r in transfers}=={100010121,100020118,100030204,100040113,100050102}
    hidden=[dict(sub=r['sub'],finish=r['finish'],effect=a) for r in rows if r['finish']=='Talk'
            for a in r['effects'] if a['type'] in {'CreateTrialPlayer','DestroyTrialPlayer','SetVirtualLineupBindPlane','RemoveVirtualLineupBindPlane'}]
    assert {r['sub'] for r in hidden}=={100030100,100040112,100040125},'新增隐藏试用动作，必须补充生命周期验证'
    battles=[dict(sub=r['sub'],winEvent=chapter[r['sub']].get('ParamInt1'),
                  scriptEvent=a['parameters'].get('EventID',{}).get('FixedValue',{}).get('Value'))
             for r in rows if r['finish']=='StageWin' for a in r['effects'] if a['type']=='TriggerBattle']
    assert {(b['sub'],b['winEvent'],b['scriptEvent']) for b in battles}=={
        (100010103,20001001,20001000),(100010113,20001006,20001006),
        (100020116,20002009,20002008),(100040110,20003011,20003012)},'剧情战斗编号变化，必须复核事件表与任务条件'
    report=dict(passed=True,mains=26,objectives=len(chapter),finishTypes=types,
                scriptedTransfers=transfers,talkTrialActions=hidden,scriptedBattleEvents=battles,
                compatibility=dict(himekoTrial=1011003,level=8,creation=100030100,unbind=100040112,removal=100040125,
                                   originalRoleAndPlaneParametersAvailable=False,operatorApproved=True),
                sourceSHA256=hashes,objectivesAndNestedActions=rows,
                completeGameplayVerified=False,clientGameplayVerified=False)
    output.parent.mkdir(parents=True,exist_ok=True)
    output.write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n','utf-8')
    print(json.dumps(dict(passed=True,mains=26,objectives=len(chapter),scriptedTransfers=len(transfers),
                         talkTrialActions=len(hidden),clientGameplayVerified=False,output=str(output))))


if __name__=='__main__':main()
