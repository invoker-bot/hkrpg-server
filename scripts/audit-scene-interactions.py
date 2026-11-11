"""只读盘点场景调查、静态演出引用和缺副本的传送门。

候选表示资源之间的关系；不证明客户端点击可达、服务器已经发布或修复已安装。
不执行脚本，不更改客户端、资源或存档。动态参数和跨脚本调用需另行追踪。
"""
import argparse
from collections import Counter, defaultdict
import hashlib
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
PERFORMANCE_TABLES = {
    'A': ('PerformanceA',), 'C': ('PerformanceC', 'PerformanceCLD'),
    'D': ('PerformanceD', 'PerformanceDLD', 'PerformanceDS', 'PerformanceDSLD'),
    'E': ('PerformanceE',), 'PlayVideo': ('PerformanceVideo', 'PerformanceVideoLD'),
}


def load(path):
    return json.loads(path.read_text(encoding='utf-8-sig'))


def rows(value):
    return list(value.values()) if isinstance(value, dict) else value


def walk(value, path=()):
    if isinstance(value, dict):
        yield path, value
        for key, item in value.items():
            yield from walk(item, (*path, key))
    elif isinstance(value, list):
        for key, item in enumerate(value):
            yield from walk(item, (*path, key))


def disabled_at(value, path):
    for key in (*path, None):
        if isinstance(value, dict) and value.get('TaskEnabled') is False:
            return True
        if key is not None:
            value = value[key]
    return False


def performance_ids(resources):
    result, sizes = {}, {}
    for kind, names in PERFORMANCE_TABLES.items():
        result[kind] = set()
        for name in names:
            table = rows(load(resources / f'ExcelOutput/{name}.json'))
            sizes[name] = len(table)
            result[kind].update(row['PerformanceID'] for row in table)
    return result, sizes


def floor_references(resources):
    result = defaultdict(list)
    paths = sorted((resources / 'Config/LevelOutput/RuntimeFloor').glob('*.json'))
    for path in paths:
        raw = load(path)
        dimensions = raw.get('DimensionList', [])
        for index, entry in enumerate(raw.get('GroupInstanceList', [])):
            dims = [d.get('ID', 0) for d in dimensions if index in d.get('GroupIndexList', [])]
            result[entry.get('GroupPath')].append(dict(floor=raw.get('FloorID'), group=entry.get('ID'),
                path=path.relative_to(resources).as_posix(), index=index, dimensions=dims,
                deleted=entry.get('IsDelete', False),
                activeDefault=not entry.get('IsDelete', False) and 0 in dims))
    return result, len(paths)


def active_placement(row):
    return (row['initial'] and not row['deleted']
            and any(ref['activeDefault'] for ref in row['floorRefs']))


def audit(resources):
    resources = resources.resolve()
    performances, table_sizes = performance_ids(resources)
    raid_ids = {row['RaidID'] for name in ('RaidConfig', 'RaidConfigLD')
                for row in rows(load(resources / f'ExcelOutput/{name}.json'))}
    prop_types = {row['ID']: row.get('PropType')
                  for row in rows(load(resources / 'ExcelOutput/MazeProp.json'))}
    references, floor_count = floor_references(resources)
    graph_users = defaultdict(list)
    raid_candidates, other_namespaces, client_groups = [], [], []
    group_paths = sorted(path for directory in ('SharedRuntimeGroup', 'RuntimeGroup')
        for path in (resources / f'Config/LevelOutput/{directory}').glob('*/*.json'))
    for path in group_paths:
        raw = load(path)
        source = path.relative_to(resources).as_posix()
        refs = references.get(source, [])
        # Match the loader's RuntimeGroup -> SharedRuntimeGroup fallback.
        refs += references.get(source.replace('/SharedRuntimeGroup/', '/RuntimeGroup/'), []) \
            if '/SharedRuntimeGroup/' in source and not (resources / source.replace(
                '/SharedRuntimeGroup/', '/RuntimeGroup/')).is_file() else []
        common = dict(groupPath=source, groupName=raw.get('GroupName'),
            side=raw.get('LoadSide', 'Client'), category=raw.get('Category', 'Normal'),
            conditions={key: raw[key] for key in ('LoadCondition', 'UnloadCondition',
                'AtmosphereCondition', 'OwnerMainMissionID') if key in raw}, floorRefs=refs)
        if common['side'] == 'Client' and raw.get('PropList') and any(r['activeDefault'] for r in refs):
            client_groups.append(dict(**common, props=len(raw['PropList']),
                npcs=len(raw.get('NPCList', [])), monsters=len(raw.get('MonsterList', []))))
        if raw.get('LevelGraph'):
            graph_users[raw['LevelGraph']].append(dict(**common, kind='group', initial=raw.get(
                'LoadOnInitial', False), deleted=raw.get('IsDelete', False)))
        for prop in raw.get('PropList', []):
            placement = dict(**common, kind='prop', instance=prop.get('ID'), prop=prop.get('PropID'),
                initial=raw.get('LoadOnInitial', False) and prop.get('LoadOnInitial', True),
                deleted=raw.get('IsDelete', False) or prop.get('IsDelete', False))
            if prop.get('IsOverrideInitLevelGraph') and prop.get('InitLevelGraph'):
                graph_users[prop['InitLevelGraph']].append(placement)
            raid = prop.get('RaidID', 0)
            if not raid or raid in raid_ids:
                continue
            candidate = dict(**placement, raid=raid, mapping=prop.get('MappingInfoID'))
            if prop_types.get(prop.get('PropID')) == 'PROP_RELIC':
                raid_candidates.append(candidate)
            else:
                other_namespaces.append(candidate)
    missing, dynamic, unknown, raid_graphs, disabled = [], [], [], [], []
    counts = Counter()
    graph_paths = sorted((resources / 'Config/Level').rglob('*.json'))
    for path in graph_paths:
        raw = load(path)
        source = path.relative_to(resources).as_posix()
        for location, node in walk(raw):
            task = node.get('$type', '').removeprefix('RPG.GameCore.')
            if task == 'TriggerPerformance':
                row = dict(graph=source, location=list(location), type=node.get('PerformanceType'),
                    id=node.get('PerformanceID', 0), placements=graph_users.get(source, []),
                    button=any(k in location for k in ('ButtonCallback', 'OnPressedCallback')))
                if disabled_at(raw, location):
                    disabled.append(row)
                elif any(node.get(key) is not None for key in ('PerformanceType_DS', 'PerformanceID_DS')):
                    dynamic.append(row)
                elif row['type'] not in performances:
                    unknown.append(row)
                else:
                    counts['static'] += 1
                    if row['id'] not in performances[row['type']]:
                        missing.append(row)
            raid = node.get('RaidID', 0)
            if (task in ('OpenTreasureChallenge', 'OpenRaid') and type(raid) is int and raid
                    and raid not in raid_ids and not disabled_at(raw, location)):
                raid_graphs.append(dict(graph=source, location=list(location), task=task, raid=raid,
                    placements=graph_users.get(source, [])))
    candidates = [row for row in missing if any(active_placement(p) for p in row['placements'])]
    summary = dict(floors=floor_count, groups=len(group_paths), graphs=len(graph_paths),
        staticPerformances=counts['static'], dynamicPerformances=len(dynamic),
        unknownPerformanceTypes=len(unknown), unresolvedReferences=len(missing),
        disabledPerformanceReferences=len(disabled),
        unresolvedPerformanceIds=len({(row['type'], row['id']) for row in missing}),
        initiallyPlacedUnresolvedReferences=len(candidates),
        initiallyPlacedUnresolvedIds=len({(row['type'],row['id']) for row in candidates}),
        missingRaidPlacements=len(raid_candidates),
        activeDefaultMissingRaidPlacements=sum(active_placement(p) for p in raid_candidates),
        clientPropGroupsNeedingPublicationReview=len(client_groups))
    return dict(readOnly=True, runtimePublicationChecked=False, clientAccepted=False,
        summary=summary, performanceTableSizes=table_sizes, missingPerformances=missing,
        dynamicPerformances=dynamic, unknownPerformanceTypes=unknown,
        disabledPerformanceReferences=disabled,
        missingRaidPlacements=raid_candidates, otherRaidIdNamespaces=other_namespaces,
        missingRaidGraphReferences=raid_graphs, clientPropGroups=client_groups,
        tableSHA256={p.relative_to(resources).as_posix(): hashlib.sha256(p.read_bytes()).hexdigest()
            for p in sorted((resources/'ExcelOutput').glob('*.json'))
            if p.stem in {n for names in PERFORMANCE_TABLES.values() for n in names}
            | {'RaidConfig', 'RaidConfigLD', 'MazeProp'}})


def write_report(resources, output):
    resources, output = resources.resolve(), output.resolve()
    if output == resources or resources in output.parents:
        raise ValueError('不能向资源目录输出诊断文件')
    report = audit(resources)
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    return report


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--resources', type=Path, default=ROOT/'resources/4.6')
    parser.add_argument('--output', type=Path, default=ROOT/'build/scene-interaction-audit.json')
    args = parser.parse_args()
    result = write_report(args.resources, args.output)
    print(json.dumps(result['summary'], ensure_ascii=False))
