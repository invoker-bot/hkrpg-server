"""只读扫描任务中的重复 Talk 回执；静态重合不等于任务可同时接取。"""
import argparse
from collections import defaultdict
import json
from pathlib import Path
import re


def contains_performance_report(node, key):
    if isinstance(node, dict):
        if node.get('$type') == 'RPG.GameCore.FinishPerformanceMission' and node.get('Key') == key:
            return True
        return any(contains_performance_report(value, key) for value in node.values())
    return isinstance(node, list) and any(contains_performance_report(value, key) for value in node)


def audit(root):
    missions = root / 'Config/Level/Mission'
    grouped = defaultdict(list)
    count = 0
    for path in sorted(missions.rglob('MissionInfo_*.json')):
        if not re.fullmatch(r'MissionInfo_\d+\.json', path.name):
            continue
        full = json.loads(path.read_text(encoding='utf-8'))
        partial = path.with_name(path.stem + '.partial.json')
        if partial.is_file():
            for key, value in json.loads(partial.read_text(encoding='utf-8')).items():
                if key in full and full[key] != value:
                    raise ValueError(f'Conflicting mission source: {path.name} {key}')
                full[key] = value
        for row in full.get('SubMissionList', []):
            if row.get('FinishType') != 'Talk':
                continue
            count += 1
            key = row.get('ParamStr1', '')
            script = row.get('MissionJsonPath', '')
            sends = False
            if script:
                script_path = (root / script).resolve()
                if not script_path.is_relative_to(missions.resolve()):
                    raise ValueError('Mission script outside resource directory')
                if script_path.is_file():
                    sends = contains_performance_report(json.loads(script_path.read_text(encoding='utf-8')), key)
            grouped[key].append(dict(id=row['ID'], main=row['MainMissionID'],
                plane=row.get('LevelPlaneID', 0), floor=row.get('LevelFloorID', 0),
                take=row.get('TakeType', ''), prerequisites=row.get('TakeParamIntList', []),
                script=script, sendsSameReport=sends))
    duplicates = {k: sorted(v, key=lambda item: item['id']) for k, v in sorted(grouped.items())
        if k and len(v) > 1}
    same_scene = {k: v for k, v in duplicates.items()
        if len({(row['plane'], row['floor']) for row in v}) == 1}
    return dict(talkObjectives=count, duplicateKeys=len(duplicates),
        duplicateObjectives=sum(len(rows) for rows in duplicates.values()),
        sameDeclaredSceneKeys=len(same_scene), sameDeclaredScene=same_scene,
        otherDuplicateKeys=[key for key in duplicates if key not in same_scene])


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('resource_root', type=Path, nargs='?',
        default=Path(__file__).resolve().parents[1] / 'resources/4.6')
    args = parser.parse_args()
    print(json.dumps(audit(args.resource_root.resolve()), ensure_ascii=False, indent=2))


if __name__ == '__main__':
    main()
