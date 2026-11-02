"""只读盘点原始主线的单步切图、未覆盖原因和同类未启用气氛组。

覆盖表示已有夹具并由 StorySceneTransfer46Test 执行，不代表实机通过。
可选核对固定客户端索引中的 InitLevelGraph 键；不启动或修改客户端。
"""
import argparse
from collections import Counter
import hashlib
import importlib.util
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def load(path):
    return json.loads(path.read_text(encoding='utf-8-sig'))


def rows(value):
    return value.values() if isinstance(value, dict) else value


def audit(resources, fixture, client=None):
    mains = {row['MainMissionID']: row for row in rows(load(resources / 'ExcelOutput/MainMission.json'))}
    entrances = {row['ID']: row for row in rows(load(resources / 'ExcelOutput/MapEntrance.json'))}
    covered = {row['sub'] for row in fixture['cases']}
    candidates, excluded, sources = [], [], {}
    graphs = 0
    for path in sorted((resources / 'Config/Level/Mission').glob('*/MissionInfo_*.partial.json')):
        main = int(path.parent.name)
        if mains.get(main, {}).get('Type') != 'Main':
            continue
        graphs += 1
        definitions = {row['ID']: row for row in load(path).get('SubMissionList', [])}
        for sub in definitions.values():
            if sub.get('FinishType') != 'EnterFloor':
                continue
            source = sub.get('MissionJsonPath', '')
            script = resources / source
            reason = None
            if not source or not script.is_file():
                reason = 'missing-script'
            else:
                raw = load(script)
                sources[source] = hashlib.sha256(script.read_bytes()).hexdigest()
                init = raw.get('OnInitSequece', [])
                start = raw.get('OnStartSequece', [])
                sequences = init + start
                if (set(raw) != {'Type', 'OnInitSequece', 'OnStartSequece'} or raw['Type'] != 'Mission'
                        or len(sequences) != 1):
                    reason = 'complex-root-or-sequences'
                else:
                    sequence = sequences[0]
                    tasks = sequence.get('TaskList', [])
                    if (set(sequence) - {'Order', 'TaskList'} or sequence.get('Order', 1) != 1
                            or len(tasks) != 1):
                        reason = 'loop-or-complex-sequence'
                    else:
                        task = tasks[0]
                        if (task.get('$type') != 'RPG.GameCore.EnterMap' or set(task) not in (
                                {'$type', 'EntranceID'}, {'$type', 'EntranceID', 'GroupID', 'AnchorID'})):
                            reason = 'nonliteral-or-complex-task'
                        else:
                            entry = entrances.get(task.get('EntranceID'))
                            if (not entry or entry.get('PlaneID') != sub.get('ParamInt1')
                                    or entry.get('FloorID') != sub.get('ParamInt2')):
                                reason = 'destination-mismatch'
            if reason:
                excluded.append({'sub': sub['ID'], 'main': main, 'reason': reason, 'script': source})
                continue
            previous = sub.get('TakeParamIntList', [])
            take = sub.get('TakeType')
            starts = load(resources / f'Config/Level/Mission/{main}/MissionInfo_{main}.json').get('StartSubMissionList', [])
            eligible = ((take == 'Auto' and (not previous or previous == [main] and starts == [sub['ID']]))
                        or take in ('AnySequence', 'MultiSequence') and bool(previous)
                        and len(set(previous)) == len(previous) and sub['ID'] not in previous
                        and all(id in definitions for id in previous))
            candidates.append(dict(sub=sub['ID'], main=main, plane=sub.get('LevelPlaneID'),
                floor=sub.get('LevelFloorID'), entry=entry['ID'], targetFloor=entry['FloorID'],
                group=task.get('GroupID', 0), anchor=task.get('AnchorID', 0),
                previous=previous, eligible=eligible, covered=sub['ID'] in covered,
                sequence='init' if init else 'start', takeType=take,
                reason=None if eligible else 'unsupported-predecessors', script=source))
    dormant, controlled = [], 0
    for path in sorted((resources / 'Config/LevelOutput/SharedRuntimeGroup').glob('*/*.json')):
        group = load(path)
        if (group.get('$type') != 'RPG.GameCore.RtLevelGroupInfo' or group.get('Category') != 'Atmosphere'
                or 'LoadSide' in group or group.get('LoadOnInitial', False) is not False):
            continue
        if any(key in group for key in ('LoadCondition', 'UnloadCondition', 'AtmosphereCondition')):
            controlled += 1
            continue
        source = path.relative_to(resources).as_posix()
        dormant.append(dict(path=source, props=len(group.get('PropList', [])), npcs=len(group.get('NPCList', [])),
            graphPaths=sorted({row['InitLevelGraph'] for row in group.get('PropList', [])
                               if row.get('IsOverrideInitLevelGraph') and row.get('InitLevelGraph')})))
        sources[source] = hashlib.sha256(path.read_bytes()).hexdigest()
    fixture_matches = all((resources / path).is_file()
        and hashlib.sha256((resources / path).read_bytes()).hexdigest() == digest
        for path, digest in fixture['sourceSHA256'].items())
    report = dict(readOnly=True, clientModified=False, clientAccepted=False, mainGraphs=graphs,
        literalTransfers=candidates, deferred=excluded, deferredCounts=dict(Counter(row['reason'] for row in excluded)),
        dormantAtmosphere=dormant, conditionControlledAtmosphere=controlled,
        sourceSHA256=sources, fixtureSourcesMatch=fixture_matches,
        uncoveredEligible=[row['sub'] for row in candidates if row['eligible'] and not row['covered']])
    if client:
        spec = importlib.util.spec_from_file_location('design_index', ROOT / 'scripts/read-client-design-data.py')
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        index_sha = 'bcac71ed6783dfd447de127bdcab7c40ae4e21d488c39cba1af6ef6aa801b608'
        _, _, index = module.parse_index(client / 'StarRail_Data/Persistent/DesignData/Windows',
            client / 'StarRail_Data/StreamingAssets/DesignData/Windows', index_sha256=index_sha)
        paths = {path for group in dormant for path in group['graphPaths']}
        def indexed_paths(paths):
            return [dict(path=path, present=module.path_hash(
                ('BakedConfig/' + path.removesuffix('.json') + '.bytes').encode()).to_bytes(8, 'big').hex() in index)
                for path in sorted(paths)]
        report['clientIndex'] = dict(sha256=index_sha, entries=len(index),
            graphKeys=indexed_paths(paths),
            transferScriptKeys=indexed_paths({row['script'] for row in candidates}))
    return report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--resources', type=Path, default=ROOT / 'resources/4.6')
    parser.add_argument('--fixture', type=Path, default=ROOT / 'src/test/resources/fixtures/story-transfers-4.6.json')
    parser.add_argument('--client-dir', type=Path)
    parser.add_argument('--output', type=Path, default=ROOT / 'build/story-scene-loading-audit.json')
    args = parser.parse_args()
    output = args.output.resolve()
    protected = [args.resources.resolve()]
    if args.client_dir:
        protected.append(args.client_dir.resolve())
    if any(output == path or path in output.parents for path in protected):
        raise ValueError('不能向资源或客户端目录输出诊断文件')
    report = audit(args.resources, load(args.fixture), args.client_dir)
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    print(json.dumps(dict(mainGraphs=report['mainGraphs'], literalTransfers=len(report['literalTransfers']),
        deferredCounts=report['deferredCounts'], dormantAtmosphere=len(report['dormantAtmosphere']),
        conditionControlledAtmosphere=report['conditionControlledAtmosphere'],
        fixtureSourcesMatch=report['fixtureSourcesMatch'], uncoveredEligible=report['uncoveredEligible'])))
    return 0 if report['fixtureSourcesMatch'] and not report['uncoveredEligible'] else 1


if __name__ == '__main__':
    raise SystemExit(main())
