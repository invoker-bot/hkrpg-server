"""只读复核骑兽6029860的完整原任务、任务归属、NPC标签与真实Floor维度来源。"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parents[1]
COMMIT = '724b139d8c9c32d12552eb95745a4fee72bfe48b'
KEYS = {'QuestData': 'QuestID', 'FinishWay': 'ID', 'CycleQuest': 'CycleID',
        'QuestTimeLimitConfig': 'QuestID', 'FuncUnlockData': 'UnlockID', 'ActivityPanel': 'PanelID',
        'ActivityConfig': 'ActivityID', 'RewardData': 'RewardID', 'ItemConfig': 'ID',
        'MainMission': 'MainMissionID', 'NPCData': 'ID', 'MazePlane': 'PlaneID', 'MapEntrance': 'ID'}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source-dir', type=Path, default=ROOT / 'resources/4.6')
    parser.add_argument('--reference-dir', type=Path, default=ROOT / 'references/TurnBasedGameData')
    parser.add_argument('--jar', type=Path, help='额外使用实际Java canonicalizer复核所有原始指纹')
    parser.add_argument('--output-dir', type=Path, default=ROOT / 'build/cavalry-source-verified')
    args = parser.parse_args()
    source, reference, output = args.source_dir.resolve(), args.reference_dir.resolve(), args.output_dir.resolve()
    assert output.is_relative_to(ROOT / 'build')
    output.mkdir(parents=True, exist_ok=True)
    fixture_path = ROOT / 'src/test/resources/fixtures/cavalry-beast-4.6.json'
    fixture = json.loads(fixture_path.read_text('utf-8'))
    checks = []

    def check(valid, label):
        if not valid:
            raise AssertionError(label)
        checks.append(label)

    check(fixture['sourceCommit'] == COMMIT, 'fixed-source-commit')
    head = subprocess.check_output(['git', '-C', str(reference), 'rev-parse', 'HEAD'], text=True).strip()
    check(head == COMMIT, 'reference-current-source-commit')
    for path, expected in fixture['fileSHA256'].items():
        raw = (source / path).read_bytes()
        check(hashlib.sha256(raw).hexdigest() == expected and raw == (reference / path).read_bytes(),
              'whole-original-byte-source:' + path)
    for path, records in fixture['records'].items():
        field = KEYS[Path(path).stem]
        original = json.loads((source / path).read_text('utf-8'))
        for record in records:
            found = [row for row in original if row.get(field) == record[field]]
            check(found == [record], f'whole-original-row:{path}:{record[field]}')
    for section in ['groups', 'floors', 'headers']:
        for path, definition in fixture[section].items():
            check(definition == json.loads((source / path).read_text('utf-8')), 'whole-original-definition:' + path)
    merged = {}
    for main in [1040101, 1040103]:
        base = f'Config/Level/Mission/{main}/MissionInfo_{main}'
        full, partial = fixture['headers'][base + '.json'], fixture['headers'][base + '.partial.json']
        combined = dict(full)
        for field, value in partial.items():
            check(field not in combined or combined[field] == value, f'unambiguous-original-mission-merge:{main}:{field}')
            combined[field] = value
        check(combined['MainMissionID'] == main
              and all(row['MainMissionID'] == main for row in combined['SubMissionList']), 'real-sub-mission-owner:' + str(main))
        merged[main] = combined
    check(any(row['ID'] == 104010102 for row in merged[1040101]['SubMissionList'])
          and any(row['ID'] == 104010320 for row in merged[1040103]['SubMissionList']), 'both-real-prerequisite-owners')
    town_dimensions = 0
    for binding in fixture['bindings']:
        group = fixture['groups'][binding['path']]
        actors = [row for row in group['NPCList'] if row['ID'] == binding['instance']]
        check(len(actors) == 1 and actors[0]['NPCID'] == binding['npc'], 'unique-original-actor:' + binding['path'])
        tags = actors[0]['TagOverrideConfig']['OverrideTags']['List']
        check(sum(row.get('Tag') == 'NPC.CavalryBeast' and row.get('TagHash') == 2136885204 for row in tags) == 1,
              'original-cavalry-tag:' + binding['path'])
        floor = fixture['floors'][f"Config/LevelOutput/RuntimeFloor/P{binding['plane']}_F{binding['floor']}.json"]
        indices = [index for index, row in enumerate(floor['GroupInstanceList']) if row.get('GroupPath') == binding['path']]
        dimensions = sorted(row.get('ID', 0) for row in floor['DimensionList']
                            if any(index in row.get('GroupIndexList', []) for index in indices))
        check(dimensions == binding['dimensions'], 'actual-original-floor-dimensions:' + binding['path'])
        if binding['plane'] == 10401:
            town_dimensions += len(dimensions)
    check(town_dimensions == 55, 'fifty-five-original-town-dimension-placements')
    check(len(fixture['fileSHA256']) == 40 and len(fixture['recordFingerprints']) == 43
          and len(fixture['fileFingerprints']) == 27, 'complete-forty-files-forty-three-rows-twenty-seven-blobs')
    canonical = None
    canonical_artifact = None
    if args.jar:
        jar = args.jar.resolve()
        check(jar.is_file() and jar.is_relative_to(ROOT / 'build'), 'owned-frozen-runtime-jar')
        artifact_sha = hashlib.sha256(jar.read_bytes()).hexdigest()
        canonical_artifact = dict(path=str(jar), sha256=artifact_sha)
        java = next((ROOT / '.tools').glob('jdk-21*/bin/java.exe'), None)
        check(java is not None, 'local-java21-runtime')
        classes = output / 'source-inspector-classes'
        classes.mkdir(exist_ok=True)
        helper = ROOT / 'scripts/fixtures/java/org/hkrpg/server/gameplay/CavalrySourceInspector.java'
        flags = subprocess.CREATE_NO_WINDOW if os.name == 'nt' else 0
        built = subprocess.run([str(java.with_name('javac.exe')), '-encoding', 'UTF-8', '-cp', str(jar),
            '-d', str(classes), str(helper)], capture_output=True, creationflags=flags)
        (output / 'javac.log').write_bytes(built.stdout + built.stderr)
        check(built.returncode == 0, 'own-source-inspector-compiled')
        canonical_file = output / 'runtime-canonical-fingerprints.json'
        inspected = subprocess.run([str(java), '-Dfile.encoding=UTF-8', '-cp', str(classes) + os.pathsep + str(jar),
            'org.hkrpg.server.gameplay.CavalrySourceInspector', str(fixture_path), str(canonical_file)],
            capture_output=True, creationflags=flags)
        (output / 'java-source-inspector.log').write_bytes(inspected.stdout + inspected.stderr)
        check(inspected.returncode == 0, 'actual-runtime-canonical-source-inspector')
        canonical = json.loads(canonical_file.read_text('utf-8'))
        check(canonical['passed'] and canonical['checks'] == 70 and not canonical['pythonHashDifferences'],
              'seventy-original-runtime-canonical-fingerprints')
        assert hashlib.sha256(jar.read_bytes()).hexdigest() == artifact_sha, 'Canonical inspector changed the frozen jar'
    result = dict(passed=True, sourceCommit=COMMIT, checks=checks, checkCount=len(checks),
        wholeSourceFiles=40, wholeOriginalRows=43, wholeOriginalBlobs=27, originalTownDimensionPlacements=55,
        fixtureSetupUsesRealMergedMainOwners=True, runtimeCanonicalVerification=canonical,
        runtimeCanonicalArtifact=canonical_artifact,
        sourceResourceFilesModified=False, referenceExecutableStarted=False, clientGameplayVerified=False)
    (output / 'verification.json').write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n', 'utf-8')
    print(json.dumps({key: result[key] for key in ['passed', 'checkCount', 'wholeSourceFiles', 'wholeOriginalRows',
        'wholeOriginalBlobs', 'originalTownDimensionPlacements']}, ensure_ascii=False))


if __name__ == '__main__':
    main()
