"""37 项兼容流程的独立线协议操作；只允许初始任务接受与旅行作为前置。"""
import json
from pathlib import Path

ROOT=Path(__file__).resolve().parents[1]


def phases():
    raw=json.loads((ROOT/'src/test/resources/fixtures/compatible-mechanisms-4.6.json').read_text('utf-8'))
    states={}
    out=[]
    def action(chest,op,after=None,**kw):
        c=dict(raw['locations'][str(chest)],id=chest,op=op,defeated=[],remaining=[])
        before=states.get(chest,20)
        c.update(before=before,after=before if after is None else after,**kw)
        states[chest]=c['after']
        return c
    def claim(chest): return action(chest,'claim',13)
    def add(flow,actions,pre=None):
        assert actions
        first=actions[0]
        world=next(r['WorldID'] for r in raw['records']['ExcelOutput/MazePlane.json'] if r['PlaneID']==first['plane'])
        out.append(dict(flow=flow,actions=actions,prerequisite=pre,stage=dict(plane=first['plane'],floor=first['floor'],entry=first['entry'],world=world,pos=dict(x=0,y=0,z=0))))
    def verify(flow,ids):add(flow,[action(c,'verify') for c in ids])

    ids=list(range(20451691,20451700));steps=[]
    for i,c in enumerate(ids,1):
        steps.append(action(c,'property',12,actorGroup=53,property=f'CaseState_{i}',events=[f'case:{i}']))
        if i==4:add('gravity-dawn',steps);steps=[]
    add('gravity-dawn',steps);add('gravity-dawn',[claim(c) for c in ids]);verify('gravity-dawn',ids)

    chest=20311684
    add('jigsaw-20311684',[action(chest,'finish',actorGroup=455,controller=300001,finish=1210,events=['open:455:300001']),action(chest,'finish',actorGroup=379,controller=300002,finish=11000,events=['done:379:300002'])],dict(values={'floor:20311001:ChallengeGravityRoom':2}))
    add('jigsaw-20311684',[action(chest,'finish',actorGroup=455,controller=300002,finish=11000,events=['done:455:300002']),action(chest,'finish',12,actorGroup=455,controller=300001,finish=11000,events=['done:455:300001'])])
    add('jigsaw-20311684',[claim(chest)]);verify('jigsaw-20311684',[chest])
    chest=20322662
    add('jigsaw-20322662',[action(chest,'finish',actorGroup=340,controller=300001,finish=1210,events=['open:340:300001']),action(chest,'finish',actorGroup=183,controller=300001,finish=11000,events=['done:183:300001'])])
    add('jigsaw-20322662',[action(chest,'finish',12,actorGroup=340,controller=300001,finish=11000,events=['done:340:300001'])]);add('jigsaw-20322662',[claim(chest)]);verify('jigsaw-20322662',[chest])
    chest=20332662
    add('jigsaw-20332662',[action(chest,'finish',12,actorGroup=211,controller=300001,finish=11000,events=['done:211:300001'])],dict(main=1032902,sub=103290250))
    add('jigsaw-20332662',[claim(chest)]);verify('jigsaw-20332662',[chest])

    chest=20331665
    add('clocks',[action(chest,'finish',actorGroup=199,controller=300001,finish=11000,events=['done:199:300001'])])
    add('clocks',[action(chest,'finish',12,actorGroup=200,controller=300001,finish=11000,events=['done:200:300001'])]);add('clocks',[claim(chest)]);verify('clocks',[chest])
    chest=20331626
    add('waves',[action(chest,'group',actorGroup=39,targetState=2,events=['started'])])
    for g in [98,147,150]:
        path=f'Config/LevelOutput/SharedRuntimeGroup/Groups_P20331_F20331001/LevelGroup_P20331_F20331001_G{g}.json'
        steps=[];monsters=raw['groups'][path]['MonsterList']
        for i,m in enumerate(monsters):steps.append(action(chest,'guard',12 if g==150 and i==len(monsters)-1 else 20,guardGroup=g,guard=m['ID'],npc=m['NPCMonsterID'],event=m['EventID'],events=[f'kill:{g}:{m["ID"]}']))
        add('waves',steps)
    add('waves',[claim(chest)]);verify('waves',[chest])

    chest=10401680;steps=[]
    for g in range(208,228):
        instance=300003 if g in (214,215) else 300005 if g==226 else 300001
        path=f'Config/LevelOutput/SharedRuntimeGroup/Groups_P10401_F10401001/LevelGroup_P10401_F10401001_G{g}.json'
        p=next(p for p in raw['groups'][path]['PropList'] if p['ID']==instance)
        config={'Closed':10191,'Open':11191,'EventClose':118191}[p.get('State','Closed')]
        steps.append(action(chest,'finish',12 if g==227 else 20,actorGroup=g,controller=instance,finish=config,events=[f'done:{g}:{instance}']))
        if g in (217,227):add('bugs',steps);steps=[]
    add('bugs',[claim(chest)]);verify('bugs',[chest])

    chest=20481657
    add('transfer',[action(chest,'talk',12,talk=401590401,events=['mission:401590401'],completedMain=4015904)],dict(main=4015904,sub=401590401))
    add('transfer',[claim(chest)]);verify('transfer',[chest])

    # Actual talk transitions, waits and milestones, without seeding completed objectives.
    ids=list(range(20422691,20422700));chest=ids[0]
    steps=[action(chest,'talk',talk=401600101)]
    talks={1:[103],2:[105],3:[107],4:[109,110,111],5:[113],6:[115,116,117],7:[119],8:[121],9:[123,124]}
    for i,c in enumerate(ids,1):
        steps.append(action(c,'property',12,actorGroup=229,property='MissionState',value=i,oldValue=i-1,events=[f'case:{i}']))
        for talk in talks[i]:
            milestone={110:20422686,116:20422687,124:20422688}.get(talk)
            steps.append(action(milestone or c,'talk',12 if milestone else None,talk=401600000+talk,events=[f'mission:{401600000+talk}'] if milestone else []))
        if i in (4,6,9):
            add('gravity-river',steps,dict(main=4016001,sub=401600101,values={'floor:20422001:SceneStateByMainMission':1}) if i==4 else None);steps=[]
    ids+=list(range(20422686,20422689));add('gravity-river',[claim(c) for c in ids]);verify('gravity-river',ids)

    for i,(g,sub,talk) in enumerate([(164,401610106,401610107),(30,401610109,401610111),(167,401610115,401610116)],1):
        ids=list(range(20423680+i*3,20423683+i*3));steps=[]
        if i>1:steps.append(action(ids[0],'talk',talk=401610118 if i==2 else 401610114))
        steps.append(action(ids[0],'property',12,actorGroup=g,property='State',events=[f'room:{i}']))
        for c in ids[1:]:states[c]=12
        steps.append(action(ids[0],'talk',talk=talk))
        add('region',steps,dict(main=4016101,sub=401610106,values={'floor:20423001:FSV_Region08State':0}) if i==1 else None)
        steps=[claim(c) for c in ids]
        # The last claim advances the authored room objective and unloads this reward group.
        steps[-1].update(after=-1 if i<3 else 13,events=[f'claimed:{i}'])
        add('region',steps)
    verify('region',list(range(20423689,20423692)))
    return out
