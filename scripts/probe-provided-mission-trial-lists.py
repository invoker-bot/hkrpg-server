"""只读实际角色、技能和装备构建检查；接取和发布队伍为明确隔离夹具。"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--resources', type=Path, default=ROOT / 'resources/4.6')
    parser.add_argument('--output', type=Path, default=ROOT / 'build/provided-mission-trial-lists-probe.json')
    args = parser.parse_args()
    resources = args.resources.resolve(strict=True)
    output = args.output.resolve()
    if not output.is_relative_to(ROOT / 'build'):
        raise SystemExit('检查报告只能写入本工程 build 目录')
    fixture = json.loads((ROOT / 'src/test/resources/fixtures/get-trial-avatar-list-missions-4.6.json').read_text('utf-8'))
    for name, expected in fixture['sourceFiles'].items():
        raw = (resources / name).read_bytes().replace(b'\r\n', b'\n')
        if hashlib.sha256(raw).hexdigest() != expected:
            raise SystemExit('原始资源内容变化: ' + name)
    files = set(fixture['sourceFiles'])
    files.update(path.relative_to(resources).as_posix() for path in (resources / 'ExcelOutput').glob('*.json'))
    files.update(path.relative_to(resources).as_posix() for path in
                 (resources / 'Config/BattleLineupPage/SkillTreePointPreset').glob('*.json'))
    before = {name: digest(resources / name) for name in sorted(files)}
    artifact = ROOT / 'build/libs/hkrpg-server.jar'
    classfile = ROOT / 'build/classes/java/test/org/hkrpg/server/gameplay/ProvidedMissionTrialResourceProbe.class'
    if not artifact.is_file() or not classfile.is_file():
        raise SystemExit('先运行 scripts/build.ps1，生成本工程运行包和检查类')
    artifact_sha, class_sha = digest(artifact), digest(classfile)
    local_java = next((ROOT / '.tools').glob('jdk-21*/bin/java.exe'), None)
    java = str(local_java or (Path(os.environ['JAVA_HOME']) / 'bin/java.exe'
                             if os.environ.get('JAVA_HOME') else 'java'))
    runs = ROOT / 'build/smoke'
    runs.mkdir(parents=True, exist_ok=True)
    directory = Path(tempfile.mkdtemp(prefix='provided-mission-trial-lists-', dir=runs))
    classpath = os.pathsep.join(str(ROOT / name) for name in
                               ['build/classes/java/test', 'build/resources/test', 'build/libs/hkrpg-server.jar'])
    environment = os.environ.copy()
    if os.name == 'nt':
        environment['JAVA_TOOL_OPTIONS'] = (environment.get('JAVA_TOOL_OPTIONS', '') +
            ' -Djdk.net.unixdomain.tmpdir=' + str(ROOT / '.tools/tcp-fallback')).strip()
    execution = subprocess.run([java, '-cp', classpath,
        'org.hkrpg.server.gameplay.ProvidedMissionTrialResourceProbe', str(resources), 'list'],
        cwd=directory, env=environment, capture_output=True, text=True, encoding='utf-8', timeout=100)
    (directory / 'probe.log').write_text(execution.stdout + execution.stderr, 'utf-8')
    after = {name: digest(resources / name) for name in before}
    if after != before or digest(artifact) != artifact_sha or digest(classfile) != class_sha:
        raise SystemExit('检查期间输入资源或运行包发生变化')
    if execution.returncode:
        raise SystemExit('实际角色检查失败，见 ' + str(directory / 'probe.log') + '\n' +
                         (execution.stdout + execution.stderr)[-2200:])
    report = next(json.loads(line) for line in reversed(execution.stdout.splitlines())
                  if line.startswith('{"') and '"passed"' in line)
    assert report['passed'] and report['completedConditions'] == 5 and len(report['cases']) == 5
    assert len({r['subMissionId'] for r in report['cases']}) == 4
    report.update(runtimeDirectory=str(directory), artifactSha256=artifact_sha,
                  probeClassSha256=class_sha, sourceCommit=fixture['sourceCommit'],
                  originalSourceFiles=len(fixture['sourceFiles']),
                  sourceFilesUnchanged=len(after), sourceFiles=after)
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n', 'utf-8')
    print(json.dumps({key: value for key, value in report.items()
                      if key not in {'cases', 'sourceFiles'}}, ensure_ascii=False))


if __name__ == '__main__':
    main()
