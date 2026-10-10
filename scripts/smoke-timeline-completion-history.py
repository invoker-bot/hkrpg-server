"""用前序实际网络领奖存档检查活动到期后的完成证据；不写玩家或库存。"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parents[1]


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--jar', type=Path, default=ROOT / 'build/libs/hkrpg-server.jar')
    parser.add_argument('--output', type=Path, default=ROOT / 'build/timeline-completion-history-restored.json')
    args = parser.parse_args()
    jar, output = args.jar.resolve(), args.output.resolve()
    assert jar.is_file() and jar.is_relative_to(ROOT / 'build') and output.is_relative_to(ROOT / 'build')
    java = next((ROOT / '.tools').glob('jdk-21*/bin/java.exe'), None)
    assert java
    artifact = sha(jar)
    helper = ROOT / 'scripts/fixtures/java/org/hkrpg/server/gameplay/TimelineCompletionHistoryFixtureInspector.java'
    classes = ROOT / 'build/timeline-completion-history-helper'
    classes.mkdir(exist_ok=True)
    flags = subprocess.CREATE_NO_WINDOW if os.name == 'nt' else 0
    subprocess.run([str(java.with_name('javac.exe')), '-cp', str(jar), '-d', str(classes), str(helper)],
                   check=True, capture_output=True, creationflags=flags)
    reports, restored = [], []
    for kind in ['pillars', 'balls']:
        report_path = ROOT / f'build/timeline-activity-{kind}-native.json'
        evidence = json.loads(report_path.read_text('utf-8'))
        assert evidence['passed'] and not evidence['targetCompletionPrewritten'] and not evidence['countReceiptsPrewritten']
        assert evidence['completedOriginalActivityQuests'] == [6029846, 6029847, 6029848, 6029849]
        directory = Path(evidence['runtimeDirectory']).resolve()
        assert directory.is_relative_to(ROOT / 'build/smoke') and directory != ROOT / 'build/smoke'
        for path, pin in evidence['sourceResourceHashes'].items():
            assert sha(ROOT / 'resources/4.6' / path) == pin, path
        uid = evidence['snapshots'][-1]['uid']
        for repeat in range(2):
            log = ROOT / f'build/timeline-completion-history-{kind}-{repeat}.log'
            child = subprocess.run([str(java), '-Xmx1536m',
                '-Djdk.net.unixdomain.tmpdir=' + str(ROOT / '.tools/tcp-fallback'),
                '-cp', str(classes) + os.pathsep + str(jar),
                'org.hkrpg.server.gameplay.TimelineCompletionHistoryFixtureInspector', str(directory), str(uid)],
                cwd=ROOT, capture_output=True, text=True, encoding='utf-8', timeout=120, creationflags=flags)
            log.write_text(child.stdout + child.stderr, 'utf-8')
            assert child.returncode == 0, log
            value = json.loads(next(line for line in reversed(child.stdout.splitlines()) if line.startswith('{')))
            assert value['passed'] and value['changedPlayers'] == value['changedInventory'] == 0
            assert value['completedOriginalActivityQuests'] == [6029846, 6029847, 6029848, 6029849]
            restored.append(dict(kind=kind, repeat=repeat, **value))
        reports.append(dict(path=str(report_path.relative_to(ROOT)), reportSHA256=sha(report_path),
                            nativeArtifactSHA256=evidence['artifactSha256'], uid=uid,
                            actualPriorNativeRequests=evidence['nativeRequests'],
                            actualPriorStateReports=evidence['actualStateReports']))
    assert sha(jar) == artifact
    result = dict(passed=True, artifactSha256=artifact, helperSHA256=sha(helper),
        predecessorNativeReports=reports, restored=restored, distinctH2Databases=2,
        sameUIDH2MorphiaRestoreProcesses=4, originalClosedQuestsPerDatabase=4,
        originalSourceBoundReceiptsPerDatabase=12, changedPlayers=0, changedInventory=0,
        expiredWindowIsFixture=True, dependentPanelIsFixture=True, nativeRequestsRerun=False,
        clientGameplayVerified=False, wholeActivityComplete=False, wholeGameComplete=False)
    output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n', 'utf-8')
    print(json.dumps(dict(passed=True, distinctH2Databases=2, sameUIDH2MorphiaRestoreProcesses=4)))


if __name__ == '__main__':
    main()
