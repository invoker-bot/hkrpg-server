"""四处最终绑定的原生推进步骤；任务接受和旅行位置是测试前置。"""
IDS = (20211674,20211681,20211682,20433651)
PREREQUISITES = {
    20211674: dict(main=4015101,doing=[401510104]),
    20211681: dict(main=4015102,doing=[401510203]),
    20211682: dict(main=4015102,doing=[401510207]),
    20433651: dict(main=4015727,doing=[401572701]),
}


def actions(chests, catalog, groups):
    out=[None]
    for chest in chests:
        id_=chest['id']
        common=dict(chest,defeated=[],remaining=[],actorGroup=chest['group'])
        if id_ == 20433651:
            common.update(actorGroup=151,controller=0,finish=0,mascot=True)
            for state in (1,2):
                out.append(dict(common,op='mascot',targetState=state,before=-1,after=-1,mascotState=state))
            out.append(dict(common,op='talk',talk=401572702,before=-1,after=-1,mascotState=3,
                missions={'401572701':'FINISHED','401572702':'FINISHED'},floorValues={'floor:20433001:FSV_QTEGotchard_RewardGet_27':1}))
            out.append(dict(common,op='mascot',targetState=4,before=-1,after=12,mascotState=4,
                floorValues={'floor:20433001:FSV_QTEGotchard_RewardGet_27':3}))
        else:
            rule=catalog['rules'][str(id_)]
            common.update(controller=rule['controller'],finish=1010)
            out.append(dict(common,op='finish',before=11,after=11,
                beforeStates={str(rule['controller']):0},states={str(rule['controller']):1},
                absentBefore=rule['guards'],remaining=rule['guards']))
            if rule['kind']=='GUARDS':
                guard=next(m for m in groups[chest['path']]['MonsterList'] if m['ID']==rule['guards'][0])
                out.append(dict(common,op='guard',guard=guard['ID'],npc=guard['NPCMonsterID'],event=guard['EventID'],
                    before=11,after=12,defeated=rule['guards']))
                common['defeated']=rule['guards']
            else:
                out[-1]['missions']={'401510207':'FINISHED'}
                for talk in (401510208,401510210,401510211):
                    away=talk==401510210
                    out.append(dict(common,op='talk',talk=talk,before=-1 if away else 11,
                        after=-1 if away else 12 if talk==401510211 else 11,
                        missions={str(talk):'FINISHED'},missionItem=1 if away else 0))
                    if away: out[-1]['travel']=dict(plane=10201,floor=10201001,entry=1020101,world=301)
                out.append(dict(common,op='finish',finish=1120,before=12,after=12,
                    beforeStates={str(rule['controller']):1},states={str(rule['controller']):2}))
        out.extend([dict(common,op='claim',before=12,after=13),dict(common,op='verify',before=13,after=13)])
        if id_!=20433651: out[-2]['missions']={str(catalog['rules'][str(id_)]['mission']):'FINISHED'}
    return out
