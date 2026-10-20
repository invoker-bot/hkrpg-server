"""从固定原文绑定翁法罗斯贼灵 27 的捕捉、对话和奖励组，保留旧收据摘要。"""
import argparse
from pathlib import Path
import json
from source_json import loads, canonical, fingerprint

ROOT = Path(__file__).resolve().parents[1]


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--resources', type=Path, default=ROOT / 'resources/4.6')
    p.add_argument('--check', action='store_true')
    args = p.parse_args()
    read = lambda path: loads((args.resources / path).read_bytes())
    control_path = 'Config/LevelOutput/SharedRuntimeGroup/Groups_P20433_F20433001/LevelGroup_P20433_F20433001_G151.json'
    reward_path = control_path.replace('_G151.', '_G152.')
    control, reward = read(control_path), read(reward_path)
    assert control['GroupStateList'] == [dict(Comment='贼灵抢夺', DstState=1),
        dict(Comment='捕捉贼灵', SrcState=1, DstState=2), dict(Comment='贼灵归还', SrcState=2, DstState=3),
        dict(Comment='宝箱出现', SrcState=3, DstState=4)]
    value = next(v['Value'] for v in control['ValueSource']['Values'] if v['Key'] == 'FSV_QTEGotchard_RewardGet_ID')
    prefix = 'Config/Level/Mission/4015727/MissionInfo_4015727'
    header, partial = read(prefix + '.json'), read(prefix + '.partial.json')
    capture, talk = partial['SubMissionList']
    assert (capture['ID'], capture['FinishType'], capture['ParamInt1'], capture['ParamInt2']) == (401572701, 'GroupState', 151, 2)
    assert (talk['ID'], talk['FinishType'], talk['TakeParamIntList']) == (401572702, 'Talk', [401572701])
    assert talk['FinishActionList'] == [dict(FinishActionType='SetFloorSavedValue', FinishActionPara=[],
        FinishActionParaString=['20433', '20433001', value, '1'])]
    graph = read(reward['LevelGraph'])
    def walk(x):
        if isinstance(x, dict):
            yield x
            for v in x.values(): yield from walk(v)
        elif isinstance(x, list):
            for v in x: yield from walk(v)
    waits = [n for n in walk(graph) if n.get('$type') == 'RPG.GameCore.ByCompareFloorSavedValue']
    assert waits == [{'$type': 'RPG.GameCore.ByCompareFloorSavedValue', 'DynamicName': {'Value': value}, 'CompareValue': 3}]
    assert not reward.get('MonsterList')
    chest = [s for s in reward['PropList'] if s['PropID'] != 100 and s['PropID'] != 211]
    assert len(chest) == 1 and (chest[0]['ID'], chest[0]['PropID'], chest[0]['State']) == (300001, 60402, 'ChestLocked')
    paths = [control_path, reward_path, control['LevelGraph'], reward['LevelGraph'],
        prefix + '.json', prefix + '.partial.json', prefix + '.partial.layout.json']
    records = {}
    for table, key, ids in [('MainMission','MainMissionID',[4015727,1040401]),
        ('MazeChest','ID',[20433651]), ('MazePlane','PlaneID',[20433]), ('MapEntrance','ID',[2043301]),
        ('MazeProp','ID',[60402,211]), ('RewardData','RewardID',[14015727])]:
        records['ExcelOutput/' + table + '.json'] = [r for r in read('ExcelOutput/' + table + '.json') if r[key] in ids]
    result = dict(sourceCommit='724b139d8c9c32d12552eb95745a4fee72bfe48b',
        files={path: fingerprint(read(path)) for path in paths}, rows={})
    for path, rows in records.items():
        table = Path(path).stem
        key = {'MainMission':'MainMissionID','MazePlane':'PlaneID','RewardData':'RewardID'}.get(table,'ID')
        result['rows'].update({f'{table}:{r[key]}': fingerprint(r) for r in rows})
    result['control'] = dict(chest=20433651, plane=20433, floor=20433001, group=151, rewardGroup=152,
        path=control_path, savedValue=value, mission=4015727, capture=401572701, battle=0, talk=401572702)
    result['location'] = dict(plane=20433,floor=20433001,group=152,instance=300001,prop=60402,path=reward_path,dimensions=[0])
    output = ROOT / 'src/main/resources/gameplay/mascot-extension-4.6.json'
    content = json.dumps(result,ensure_ascii=False,indent=2)+'\n'
    fixture_path = ROOT / 'src/test/resources/fixtures/mascot-4.6.json'
    fixture = loads(fixture_path.read_bytes())
    for path, rows in records.items():
        key = {'MainMission':'MainMissionID','MazePlane':'PlaneID','RewardData':'RewardID'}.get(Path(path).stem,'ID')
        old = fixture['records'].setdefault(path,[])
        for row in rows:
            old[:] = [r for r in old if r[key] != row[key]]
            old.append(row)
        old.sort(key=lambda row: row[key])
    for path in paths:
        fixture['groups' if path in (control_path,reward_path) else 'headers'][path] = read(path)
    floor = 'Config/LevelOutput/RuntimeFloor/P20433_F20433001.json'
    fixture['floors'][floor] = read(floor)
    fixture['controls']['20433651'] = result['control']
    fixture['locations']['20433651'] = result['location']
    def pretty(value, depth=0):
        if isinstance(value, dict) and value:
            return '{\n' + ',\n'.join('  '*(depth+1) + canonical(k) + ': ' + pretty(v,depth+1)
                for k,v in value.items()) + '\n' + '  '*depth + '}'
        if isinstance(value, list) and value:
            return '[\n' + ',\n'.join('  '*(depth+1) + pretty(v,depth+1) for v in value) + '\n' + '  '*depth + ']'
        return canonical(value)
    encoded = pretty(fixture)+'\n'
    if args.check:
        assert output.read_text('utf-8') == content
        assert fixture_path.read_text('utf-8') == encoded
    else:
        output.write_text(content,encoding='utf-8')
        fixture_path.write_text(encoded,encoding='utf-8')
    print('Verified QTE group 151 -> mission 4015727 -> reward group 152 (20433651)')


if __name__ == '__main__': main()
