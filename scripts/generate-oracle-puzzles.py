"""提取原始神谕机关与宝箱的 FCV/FSV 对应，并生成可重复的测试夹具。"""
import argparse
from collections import defaultdict
from pathlib import Path
import re
from source_json import canonical, fingerprint, loads

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / 'resources/4.6'
CATALOG = ROOT / 'src/main/resources/gameplay/oracle-puzzles-4.6.json'
FIXTURE = ROOT / 'src/test/resources/fixtures/oracle-puzzles-4.6.json'
TREASURE_GRAPH = 'Config/Level/Maze/MazeExtra/OraclePuzzle/OraclePuzzle_Treasure_GroupGraph.json'
CONTROL_GRAPH = 'Config/Level/Maze/MazeExtra/OraclePuzzle/OraclePuzzle_GroupGraph.json'
PUZZLE_GRAPH = 'Config/Level/Maze/MazeExtra/OraclePuzzle/OraclePuzzle.json'
SAMPLES = {10403660, 10403661, 20432661, 20432662, 20482660}


def values(group):
    rows = group.get('ValueSource', {}).get('Values', [])
    assert len({row['Key'] for row in rows}) == len(rows)
    return {row['Key']: row.get('Value') for row in rows}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--check', action='store_true')
    args = parser.parse_args()
    world = loads((ROOT / 'src/main/resources/gameplay/world-mechanisms-4.6.json').read_bytes())
    chests = loads((ROOT / 'src/main/resources/gameplay/map-chests-4.6.json').read_bytes())
    paths = {}

    def read(path):
        if path not in paths:
            paths[path] = loads((SOURCE / path).read_bytes())
        return paths[path]

    # The authored finish callback emits this group event; cancellation does not.
    puzzle = read(PUZZLE_GRAPH)['OnStartSequece'][2]['TaskList'][0]
    assert puzzle['$type'] == 'RPG.GameCore.PropPuzzleEventListener'
    def event(nodes):
        return [node for node in nodes if node.get('$type') == 'RPG.GameCore.TriggerGroupEvent'
                and node.get('EventName', {}).get('Value') == 'OraclePuzzleStartLinePerform']
    assert len(event(puzzle['FinishCallback'])) == 1 and not event(puzzle['QuitCallback'])
    trigger = read(CONTROL_GRAPH)['OnStartSequece'][2]['TaskList'][0]
    assert trigger['$type'] == 'RPG.GameCore.WaitGroupEvent'
    assert trigger['EventName']['Value'] == 'OraclePuzzleStartLinePerform'
    set_property = [n for n in trigger['OnEvent']
                    if n.get('$type') == 'RPG.GameCore.RequestSetServerGroupProperty']
    assert len(set_property) == 1 and set_property[0]['DynamicName']['Value'] == 'SGP_PuzzleState'
    assert set_property[0]['Value'] == 1
    treasure = read(TREASURE_GRAPH)
    assert treasure['OnStartSequece'][1]['TaskList'][0]['OnChange'][0]['InteractID']['FixedValue']['Value'] == 120120
    assert treasure['OnStartSequece'][7]['TaskList'][0]['Predicate']['PredicateList'][0]['CompareValue'] == 1

    bindings, pins, by_floor = {}, {}, defaultdict(list)
    pins.update({p: fingerprint(read(p)) for p in [TREASURE_GRAPH, CONTROL_GRAPH, PUZZLE_GRAPH]})
    for ident, rule in world['rules'].items():
        if rule['kind'] != 'CLIENT_REPORT' or not any(e.startswith(TREASURE_GRAPH + '#') for e in rule['evidence']):
            continue
        loc = chests['locations'][ident]
        chest_group = read(loc['path'])
        cv = values(chest_group)
        assert chest_group['LevelGraph'] == TREASURE_GRAPH
        assert cv['Treasure'] == f"{loc['group']},{loc['instance']}"
        assert re.fullmatch(r'FSV_[A-Za-z0-9_]+', cv['FSV'])
        assert re.fullmatch(r'FCV_[A-Za-z0-9_]+', cv['FCV'])
        actual_chest = [p for p in chest_group['PropList'] if p['ID'] == loc['instance']]
        assert len(actual_chest) == 1 and actual_chest[0]['ChestID'] == int(ident)
        assert actual_chest[0]['PropID'] == 60402 and actual_chest[0]['State'] == 'Hidden'
        floor_key = (loc['plane'], loc['floor'])
        if not by_floor[floor_key]:
            folder = SOURCE / f"Config/LevelOutput/SharedRuntimeGroup/Groups_P{loc['plane']}_F{loc['floor']}"
            for file in folder.glob('*.json'):
                number = re.search(r'_G(\d+)\.json$', file.name)
                if not number:
                    continue
                rel = file.relative_to(SOURCE).as_posix()
                data = read(rel)
                if data.get('LevelGraph') == CONTROL_GRAPH:
                    by_floor[floor_key].append((int(number.group(1)), rel, data))
        controls = [(n, p, g) for n, p, g in by_floor[floor_key]
                    if (values(g).get('FSV'), values(g).get('FCV')) == (cv['FSV'], cv['FCV'])]
        assert len(controls) == 1, (ident, controls)
        group_id, control_path, control = controls[0]
        prop = [p for p in control['PropList'] if p['ID'] == 300001]
        assert len(prop) == 1 and prop[0]['PropID'] == 104098
        assert prop[0]['InitLevelGraph'] == PUZZLE_GRAPH and prop[0].get('IsClientOnly')
        props = [p for p in control['GroupPropertyMap'].values() if p['Name'] == 'SGP_PuzzleState']
        assert len(props) == 1 and props[0].get('DefaultValue', 0) == 0 and props[0]['MaxValue'] == 2
        floor_path = f"Config/LevelOutput/RuntimeFloor/P{loc['plane']}_F{loc['floor']}.json"
        floor = read(floor_path)
        dimensions = []
        for dim in floor['DimensionList']:
            did = dim.get('ID', 0)
            if did not in loc['dimensions']:
                continue
            groups = [floor['GroupInstanceList'][index] for index in dim['GroupIndexList']]
            ids = [row['ID'] for row in groups]
            saved = [row for row in dim.get('SavedValues', []) if row.get('Name') == cv['FSV']]
            assert group_id in ids and loc['group'] in ids and len(saved) == 1
            assert [row['GroupPath'] for row in groups if row['ID'] == group_id] == [control_path]
            assert [row['GroupPath'] for row in groups if row['ID'] == loc['group']] == [loc['path']]
            assert saved[0].get('DefaultValue', 0) == 0 and saved[0]['MaxValue'] >= 1
            dimensions.append(did)
        assert sorted(dimensions) == sorted(loc['dimensions']), (ident, dimensions, loc['dimensions'])
        dimensions.sort()
        for path in [loc['path'], control_path, floor_path]:
            pins[path] = fingerprint(read(path))
        assert pins[loc['path']] == world['files'][loc['path']]
        bindings[ident] = dict(plane=loc['plane'], floor=loc['floor'], chestGroup=loc['group'],
            chestInstance=loc['instance'], controlGroup=group_id, floorValue=cv['FSV'],
            customValue=cv['FCV'], controlPath=control_path, chestPath=loc['path'],
            floorPath=floor_path, dimensions=dimensions)
    assert len(bindings) == 30
    catalog = dict(sourceCommit=world['sourceCommit'], bindings=bindings, files=pins)

    fixture = dict(records={}, groups={}, floors={}, locations={}, headers={})
    selected = {'InteractConfig:120120'}
    entrances = read('ExcelOutput/MapEntrance.json')
    planes = read('ExcelOutput/MazePlane.json')
    for ident in sorted(SAMPLES):
        key = str(ident); loc = chests['locations'][key]; rule = world['rules'][key]
        binding = bindings[key]
        for path in [loc['path'], binding['controlPath']]:
            group = read(path); fixture['groups'][path] = group
            selected.update(f"MazeProp:{p['PropID']}" for p in group.get('PropList', []))
        fixture['floors'][binding['floorPath']] = read(binding['floorPath'])
        entry = next(e['ID'] for e in entrances if e['PlaneID'] == loc['plane'] and e['FloorID'] == loc['floor'])
        world_id = next(p['WorldID'] for p in planes if p['PlaneID'] == loc['plane'])
        fixture['locations'][key] = dict(loc, entry=entry, world=world_id)
        selected.update(rule['rows'])
        selected.update([f"MazeChest:{ident}", f"MazePlane:{loc['plane']}", f"MapEntrance:{entry}"])
        for path in [*rule['files'], TREASURE_GRAPH, CONTROL_GRAPH, PUZZLE_GRAPH]:
            if path not in fixture['groups']:
                fixture['headers'][path] = read(path)
    for table in sorted({x.split(':')[0] for x in selected}):
        key = {'MazePlane': 'PlaneID', 'InteractConfig': 'InteractID'}.get(table, 'ID')
        fixture['records'][f'ExcelOutput/{table}.json'] = [row for row in read(f'ExcelOutput/{table}.json')
                                                           if f'{table}:{row[key]}' in selected]
    for path, content in [(CATALOG, canonical(catalog) + '\n'), (FIXTURE, canonical(fixture) + '\n')]:
        if args.check:
            assert path.read_text(encoding='utf8') == content, path
        else:
            path.write_text(content, encoding='utf8')
    print(f'{len(bindings)} original bindings, {len(SAMPLES)} gameplay fixtures')


if __name__ == '__main__':
    main()
