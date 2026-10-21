"""固定 37 项兼容规则及原始证据；奖励对应关系由本项目定义，不冒充原版脚本。"""
import argparse
import json
from pathlib import Path
from source_json import loads, fingerprint, canonical

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / 'src/main/resources/gameplay/compatible-mechanisms-4.6.json'
FIXTURE = ROOT / 'src/test/resources/fixtures/compatible-mechanisms-4.6.json'


def generate(root):
    def read(p):
        return loads((root / p).read_bytes())

    def group_path(f, g):
        return f'Config/LevelOutput/SharedRuntimeGroup/Groups_P{f//1000}_F{f}/LevelGroup_P{f//1000}_F{f}_G{g}.json'

    locations = loads((ROOT / 'src/main/resources/gameplay/map-chests-4.6.json').read_bytes())
    tables = {t: read(f'ExcelOutput/{t}.json') for t in ['MazeProp', 'InteractConfig', 'MazePlane', 'MapEntrance', 'NPCMonsterData', 'PlaneEvent', 'StageConfig', 'MainMission', 'MazePuzzleOrigami', 'FinishWay']}
    props = {r['ID']: r for r in tables['MazeProp']}
    flows = {}
    files = {}
    fixture = dict(records={}, groups={}, floors={}, locations={}, headers={}, missions={})
    selected = {}

    def rows(t, ids):
        selected.setdefault(t, set()).update(ids)

    def flow(name, floor, chests, groups, mission=0):
        x = dict(floor=floor, plane=floor//1000, groups=sorted(set(groups)), actors={}, reports=[],
                 rewards={}, missions={}, files=[], rows={}, policy='HKRPG compatibility v1; original server reward bindings unavailable')
        used = set()
        def pin(path):
            if not path or not (root/path).is_file():
                return
            raw = read(path)
            files[path] = fingerprint(raw)
            used.add(path)
            fixture['headers'][path] = raw
        x['_pin'] = pin
        x['_used'] = used
        for chest in chests:
            loc = locations['locations'][str(chest)]
            x['rewards'][str(chest)] = []
            x['groups'].append(loc['group'])
            entry = next(r['ID'] for r in tables['MapEntrance'] if r['PlaneID']==loc['plane'] and r['FloorID']==floor)
            fixture['locations'][str(chest)] = dict(loc, entry=entry)
            rows('MazeChest', [chest]); rows('MapEntrance', [entry]); rows('MazePlane', [loc['plane']]); rows('MazeProp', [loc['prop']])
        x['groups'] = sorted(set(x['groups']))
        floorpath = f'Config/LevelOutput/RuntimeFloor/P{floor//1000}_F{floor}.json'
        pin(floorpath)
        fixture['floors'][floorpath] = read(floorpath)
        for g in x['groups']:
            path = group_path(floor, g); pin(path)
            raw = read(path); fixture['groups'][path] = raw
            pin(raw.get('LevelGraph')); pin(raw.get('GroupTemplateGraph'))
            for p in raw.get('PropList', []):
                rows('MazeProp', [p['PropID']])
                pin(p.get('InitLevelGraph'))
                cfg = props.get(p['PropID'], {}).get('JsonPath')
                pin(cfg)
                if cfg and (root/cfg).is_file(): pin(read(cfg).get('DefaultLevelGraphPath'))
            for m in raw.get('MonsterList', []):
                rows('NPCMonsterData', [m['NPCMonsterID']]); rows('PlaneEvent', [m.get('EventID',0)])
        if mission:
            path=f'Config/Level/Mission/{mission}/MissionInfo_{mission}.partial.json'
            pin(path); fixture['missions'][str(mission)]=read(path)
            rows('MainMission',[mission])
            for sub in read(path)['SubMissionList']: pin(sub.get('MissionJsonPath'))
        flows[name] = x
        return x

    def actor(x, g, i, role, interactions, **extra):
        raw = read(group_path(x['floor'],g))
        p = next(a for a in raw['PropList'] if a['ID']==i)
        assert not p.get('IsDelete') and not p.get('IsClientOnly') and p.get('LoadOnInitial',True)
        x['actors'][f'{g}:{i}'] = dict(group=g, instance=i, prop=p['PropID'], initial=p.get('State','Closed'), role=role, interactions=interactions, **extra)
        rows('InteractConfig', interactions)

    def report(x,g,name,value,key,requires=(),mission=0,effects=None,group_effects=None):
        x['reports'].append(dict(group=g,name=name,value=value,key=key,requires=list(requires),mission=mission,effects=effects or {},groupEffects=group_effects or {}))

    x=flow('gravity-river',20422001,list(range(20422686,20422689))+list(range(20422691,20422700)),[229],4016001)
    waits=[401600102,401600104,401600106,401600108,401600112,401600114,401600118,401600120,401600122]
    puzzles=[29,12,20,25,21,27,26,19,22]
    for i in range(1,10):
        key=f'case:{i}'
        report(x,229,'MissionState',i,key, [f'case:{i-1}'] if i>1 else [], waits[i-1], {'FSV_GravityBallCaseState':i}, {'CurrentPuzzleID':puzzles[min(i,8)]})
        x['rewards'][str(20422690+i)]=[key]
    for i,sub in enumerate([401600110,401600116,401600124],1):
        x['missions'][str(sub)]=dict(key=f'mission:{sub}',requires=[f'case:{[4,6,9][i-1]}'])
        x['rewards'][str(20422685+i)]=[f'mission:{sub}']
    # Original hard-case callback remains usable, without duplicating the story rewards.
    for i in range(1,4): report(x,229,'HardCaseState',i,f'hard:{i}',[f'case:{[4,6,9][i-1]}'],effects={f'FSV_GravityBallHardCase_{i}':1})

    x=flow('gravity-dawn',20451001,range(20451691,20451700),[53])
    puzzles=[44,47,40,46,48,42,45,41,43]
    for i in range(1,10):
        report(x,53,f'CaseState_{i}',1,f'case:{i}',[f'case:{i-1}'] if i>1 else [],group_effects={'CurrentPuzzleID':puzzles[min(i,8)]})
        x['rewards'][str(20451690+i)]=[f'case:{i}']

    x=flow('region',20423001,range(20423683,20423692),[164,30,167,265],4016101)
    for stage,(g,sub) in enumerate([(164,401610106),(30,401610109),(167,401610115)],1):
        report(x,g,'State',1,f'room:{stage}', [f'claimed:{stage-1}'] if stage>1 else [],sub,{'FSV_SubGameplayClear':stage})
        for chest in range(20423680+stage*3,20423683+stage*3): x['rewards'][str(chest)]=[f'room:{stage}']
    # Authored region controller toggles presentation only; never award a room for this callback.
    x['presentationGroup']=265

    for chest,floor,board,pieces,base,mission in [(20311684,20311001,455,[(455,300002),(379,300002)],1,0),(20322662,20322001,340,[(183,300001)],2,0),(20332662,20332001,211,[],3,103290250)]:
        x=flow(f'jigsaw-{chest}',floor,[chest],[board]+[p[0] for p in pieces],1032902 if mission else 0)
        x.update(board=f'{board}:300001',basePieces=base,mission=mission)
        actor(x,board,300001,'board',[1210,1920,19100,11000])
        for g,i in pieces: actor(x,g,i,'piece',[11000])
        x['rewards'][str(chest)]=[f'done:{board}:300001']
        if chest==20311684:x.update(pieceValue='JG_NM_05_PieceCount',boardValue='JG_NM_05_BoardState')
        if chest==20322662:x.update(pieceValue='JG_NM_01_PieceCount',boardValue='JG_NM_01_BoardState')

    x=flow('clocks',20331001,[20331665],[199,200])
    actor(x,199,300001,'clock',[11000]);actor(x,200,300001,'clock',[11000],requires=['done:199:300001'])
    x['rewards']['20331665']=['done:199:300001','done:200:300001']

    x=flow('waves',20331001,[20331626],[39,98,147,150])
    x['waves']=[]
    for g in [98,147,150]:
        monsters=read(group_path(x['floor'],g))['MonsterList']
        x['waves'].append([f'kill:{g}:{m["ID"]}' for m in monsters if not m.get('IsDelete') and not m.get('IsClientOnly')])
    x['rewards']['20331626']=sum(x['waves'],[])

    colony=[r for r in tables['MazePuzzleOrigami'] if r['FloorID']==10401001 and r['ColonyID']==12]
    assert len(colony)==20
    x=flow('bugs',10401001,[10401680],[r['GroupID'] for r in colony])
    for r in colony:
        actor(x,r['GroupID'],r['MainPropID'],'bug',[12180,11800,118191,10191,11191])
    x['rewards']['10401680']=[f'done:{a}' for a in x['actors']]
    x['collectionRows']=colony
    rows('FinishWay',[2200602])
    x['_pin']('ExcelOutput/MazePuzzleOrigami.json')
    x['_pin']('ExcelOutput/MazePuzzleOrigamiColony.json')

    x=flow('transfer',20481001,[20481657],[],4015904)
    x['missions']['401590401']=dict(key='mission:401590401',requires=[])
    x['rewards']['20481657']=['mission:401590401']

    rows('InteractConfig',[112131])
    for x in flows.values():
        x['files']=sorted(x.pop('_used'));x.pop('_pin')
        # Binding rows are checked against both disk and the live Excel maps.
        for t in ['MazeProp','InteractConfig','NPCMonsterData']:
            key='InteractID' if t=='InteractConfig' else 'ID'
            ids={a['prop'] for a in x['actors'].values()} if t=='MazeProp' else set(sum([a['interactions'] for a in x['actors'].values()],[])) if t=='InteractConfig' else set()
            if t=='NPCMonsterData' and 'waves' in x:
                ids={m['NPCMonsterID'] for g in [98,147,150] for m in read(group_path(x['floor'],g))['MonsterList']}
            for r in tables[t]:
                if r[key] in ids:x['rows'][f'{t}:{r[key]}']=fingerprint(r)
    # Battle fixtures need complete stage/plane-event definitions for the twelve real guards.
    events=[r for r in tables['PlaneEvent'] if r['EventID'] in selected.get('PlaneEvent',set())]
    rows('StageConfig',[r['StageID'] for r in events if r.get('StageID')])
    for t,ids in selected.items():
        key={'MazePlane':'PlaneID','InteractConfig':'InteractID','PlaneEvent':'EventID','StageConfig':'StageID','MainMission':'MainMissionID'}.get(t,'ID')
        data=tables.get(t) or read(f'ExcelOutput/{t}.json')
        fixture['records'][f'ExcelOutput/{t}.json']=[r for r in data if r[key] in ids]
    return dict(sourceCommit=locations['sourceCommit'],policyVersion=1,flows=flows,files=dict(sorted(files.items()))),fixture


def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--resources',type=Path,default=ROOT/'resources/4.6');p.add_argument('--check',action='store_true');a=p.parse_args()
    result,fixture=generate(a.resources)
    for path,body in [(OUT,json.dumps(result,ensure_ascii=False,indent=2)+'\n'),(FIXTURE,canonical(fixture)+'\n')]:
        if a.check:assert path.read_text('utf-8')==body,str(path)
        else:path.write_text(body,encoding='utf-8')
    print(json.dumps(dict(flows=len(result['flows']),chests=sum(len(f['rewards']) for f in result['flows'].values()),files=len(result['files']))))


if __name__=='__main__': main()
