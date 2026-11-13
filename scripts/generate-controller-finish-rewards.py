"""提取原始挑战完成回调与隐藏宝箱的绑定。"""
import argparse
from pathlib import Path
from source_json import canonical, fingerprint, loads

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / 'resources/4.6'
CATALOG = ROOT / 'src/main/resources/gameplay/controller-finish-rewards-4.6.json'
FIXTURE = ROOT / 'src/test/resources/fixtures/controller-finish-rewards-4.6.json'
PREFIX = 'Config/Level/Maze/MazeExtra/CommandRobotPuzzle/'
HELPER = PREFIX + 'CommandRobotPuzzle_HideChestControl.json'
CONTROLLER = PREFIX + 'CommandRobotPuzzle_Controller.json'
HIT = 'Config/Level/Props/Chap03/InitLevelGraph_Prop_Chap03_HitCount_01.json'
EXIT = 'Config/Level/Props/Chap03/InitLevelGraph_Prop_Chap03_C2Finish_01.json'
MOVIE = 'Config/Level/Maze/MazeExtra/MovieGame/MovieGame_InCinema_Secret.json'


def values(prop):
    rows = prop.get('ValueSource', {}).get('Values', [])
    assert len({row['Key'] for row in rows}) == len(rows)
    return {row['Key']: row.get('Value') for row in rows}


def walk(obj):
    if isinstance(obj, dict):
        yield obj
        for value in obj.values():
            yield from walk(value)
    elif isinstance(obj, list):
        for value in obj:
            yield from walk(value)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--check', action='store_true')
    args = parser.parse_args()
    world = loads((ROOT / 'src/main/resources/gameplay/world-mechanisms-4.6.json').read_bytes())
    chests = loads((ROOT / 'src/main/resources/gameplay/map-chests-4.6.json').read_bytes())
    cache = {}

    def read(path):
        if path not in cache:
            cache[path] = loads((SOURCE / path).read_bytes())
        return cache[path]

    listener = [n for n in walk(read(CONTROLLER))
                if n.get('$type') == 'RPG.GameCore.PropPuzzleEventListener']
    assert len(listener) == 1
    def calls(nodes):
        return [n for n in walk(nodes) if n.get('$type') == 'RPG.GameCore.PropReqInteract'
                and n.get('InteractID', {}).get('FixedValue', {}).get('Value') == 1010]
    assert len(calls(listener[0]['FinishCallback'])) == 2
    assert not calls(listener[0].get('QuitCallback', [])) and not calls(listener[0].get('FailCallback', []))
    helper = read(HELPER)
    first = helper['OnStartSequece'][0]['TaskList'][0]
    assert first['State'] == 'Open' and first['TargetType']['SinglePropKey']['Key'] == 'Puzzle'
    nested = first['Execute'][0]
    assert nested['State'] == 'Hidden' and nested['TargetType']['SinglePropKey']['Key'] == 'Chest'
    request = nested['Execute'][0]
    assert request['InteractID']['FixedValue']['Value'] == 120120
    assert request['TargetType']['SinglePropKey']['Key'] == 'Chest'
    pins = {p: fingerprint(read(p)) for p in [HELPER, CONTROLLER]}
    bindings = {}
    for ident, rule in world['rules'].items():
        if rule['kind'] != 'CLIENT_REPORT' or HELPER not in rule['files']:
            continue
        loc = chests['locations'][ident]
        group = read(loc['path'])
        matches = [p for p in group['PropList'] if p.get('InitLevelGraph') == HELPER
                   and values(p).get('Chest') == f"{loc['group']},{loc['instance']}"]
        assert len(matches) == 1
        pair = values(matches[0])['Puzzle'].split(',')
        assert len(pair) == 2 and int(pair[0]) == loc['group']
        target = [p for p in group['PropList'] if p['ID'] == int(pair[1])]
        assert len(target) == 1 and target[0]['PropID'] == 100
        assert target[0]['InitLevelGraph'] == CONTROLLER and target[0].get('State', 'Closed') == 'Closed'
        assert not target[0].get('IsDelete') and not target[0].get('IsClientOnly') and target[0].get('LoadOnInitial', True)
        assert fingerprint(group) == world['files'][loc['path']]
        pins[loc['path']] = fingerprint(group)
        bindings[ident] = dict(plane=loc['plane'], floor=loc['floor'], group=loc['group'],
            chest=loc['instance'], chestProp=loc['prop'], controller=int(pair[1]),
            helper=matches[0]['ID'], graph=CONTROLLER, prerequisite=0,
            path=loc['path'], dimensions=loc['dimensions'])
    assert len(bindings) == 9

    # 匹诺康尼两处控制器在满足原始挑战条件后先报告 1010，随后才报告宝箱。
    hit = read(HIT)['OnStartSequece'][0]['TaskList'][0]
    predicate = hit['OnChange'][0]
    assert hit['Name']['Value'] == 'EasterC1_AllHit'
    assert predicate['Predicate']['CompareValue']['FixedValue']['Value'] == 10
    assert len(calls(predicate['SuccessTaskList'])) == 1 and not calls(predicate['FailedTaskList'])
    exit_tasks = read(EXIT)['OnStartSequece'][4]['TaskList']
    assert exit_tasks[0]['CustomString']['Value'] == 'ExitRunningGame'
    assert len(calls(exit_tasks[1:])) == 1
    movie = read(MOVIE)['OnStartSequece'][0]['TaskList'][0]
    assert any(n.get('InteractID', {}).get('FixedValue', {}).get('Value') == 1200
               for n in walk(movie['FinishCallback']))
    assert any(n.get('$type') == 'RPG.GameCore.ByComparePropState' and 'State' not in n
               for n in walk(movie['QuitCallback']))
    assert any(n.get('CustomString', {}).get('Value') == 'ExitRunningGame'
               for n in walk(movie['QuitCallback']))
    for ident, graph, controller, prerequisite in [
            ('20331671', HIT, 300001, 0), ('20331672', EXIT, 300002, 300001)]:
        loc, rule = chests['locations'][ident], world['rules'][ident]
        group = read(loc['path'])
        actor = next(p for p in group['PropList'] if p['ID'] == controller)
        assert actor['PropID'] == 100 and actor['InitLevelGraph'] == graph
        assert actor.get('State', 'Closed') == 'Closed'
        assert values(actor)['ShowChest'] == f"{loc['group']},{loc['instance']}"
        listener = next(n for n in walk(read(graph))
                        if n.get('$type') == 'RPG.GameCore.PropStateChangeListenerConfig'
                        and n.get('ToState') == 'Open' and any(
                            x.get('InteractID', {}).get('FixedValue', {}).get('Value') == 120120
                            and x.get('TargetType', {}).get('SinglePropKey', {}).get('Key') == 'ShowChest'
                            for x in walk(n.get('OnChange', []))))
        assert listener and graph in rule['files']
        if prerequisite:
            actor = next(p for p in group['PropList'] if p['ID'] == prerequisite)
            assert actor['PropID'] == 100 and actor['State'] == 'Locked' and actor['InitLevelGraph'] == MOVIE
        assert fingerprint(group) == world['files'][loc['path']]
        for path in [loc['path'], graph] + ([MOVIE] if prerequisite else []):
            pins[path] = fingerprint(read(path))
        bindings[ident] = dict(plane=loc['plane'], floor=loc['floor'], group=loc['group'],
            chest=loc['instance'], chestProp=loc['prop'], controller=controller,
            helper=0, graph=graph, prerequisite=prerequisite,
            path=loc['path'], dimensions=loc['dimensions'])
    assert len(bindings) == 11
    rows = {}
    for table, key, ident in [('InteractConfig', 'InteractID', 1010),
                              ('InteractConfig', 'InteractID', 1200), ('MazeProp', 'ID', 100)]:
        matches = [row for row in read(f'ExcelOutput/{table}.json') if row[key] == ident]
        assert len(matches) == 1
        rows[f'{table}:{ident}'] = fingerprint(matches[0])
    catalog = dict(sourceCommit=world['sourceCommit'], bindings=bindings, files=pins, rows=rows)

    fixture = dict(records={}, groups={}, floors={}, locations={}, headers={})
    selected = {'InteractConfig:1010', 'InteractConfig:1200', 'InteractConfig:120120'}
    entrances = read('ExcelOutput/MapEntrance.json')
    planes = read('ExcelOutput/MazePlane.json')
    for ident in sorted(bindings, key=int):
        loc = chests['locations'][ident]
        rule = world['rules'][ident]
        group = read(loc['path'])
        fixture['groups'][loc['path']] = group
        floor = f"Config/LevelOutput/RuntimeFloor/P{loc['plane']}_F{loc['floor']}.json"
        fixture['floors'][floor] = read(floor)
        entry = next(e['ID'] for e in entrances if e['PlaneID'] == loc['plane'] and e['FloorID'] == loc['floor'])
        world_id = next(p['WorldID'] for p in planes if p['PlaneID'] == loc['plane'])
        fixture['locations'][ident] = dict(loc, entry=entry, world=world_id)
        selected.update(rule['rows'])
        selected.update([f"MazeChest:{ident}", f"MazePlane:{loc['plane']}", f"MapEntrance:{entry}"])
        selected.update(f"MazeProp:{p['PropID']}" for p in group['PropList'])
        for path in [*rule['files'], CONTROLLER, bindings[ident]['graph']] + ([MOVIE] if bindings[ident]['prerequisite'] else []):
            if path != loc['path']:
                fixture['headers'][path] = read(path)
    for table in sorted({row.split(':')[0] for row in selected}):
        key = {'MazePlane': 'PlaneID', 'InteractConfig': 'InteractID'}.get(table, 'ID')
        fixture['records'][f'ExcelOutput/{table}.json'] = [row for row in read(f'ExcelOutput/{table}.json')
                                                           if f'{table}:{row[key]}' in selected]
    for path, content in [(CATALOG, canonical(catalog) + '\n'), (FIXTURE, canonical(fixture) + '\n')]:
        if args.check:
            assert path.read_text(encoding='utf8') == content, path
        else:
            path.write_text(content, encoding='utf8')
    print(f'{len(bindings)} original controller-finish bindings')


if __name__ == '__main__':
    main()
