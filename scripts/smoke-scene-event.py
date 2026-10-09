"""原始事件/怪物波次的合成KCP验证；两次正常H2恢复，不执行官方客户端或剧情脚本。"""
import hashlib
import argparse
import json
import os
from pathlib import Path
import socket
import subprocess
import tempfile
import time
import urllib.error
import urllib.request
from smoke_resource_fixture import mirror_resources

ROOT = Path(__file__).resolve().parents[1]


def free_port(kind=socket.SOCK_STREAM):
    with socket.socket(type=kind) as sock:
        sock.bind(('127.0.0.1', 0))
        return sock.getsockname()[1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--reject-local-legend', action='store_true',
                        help='追加传奇挑战三种难度的通用入口拒绝与当前战斗保留检查')
    parser.add_argument('--check-target-reports', action='store_true',
                        help='追加两场实际战斗中的56次非法目标报告拒绝和存档不变检查')
    args = parser.parse_args()
    source = ROOT/'resources/4.6'
    jar = ROOT/'build/libs/hkrpg-server.jar'
    java = next((ROOT/'.tools').glob('jdk-21*/bin/java.exe'), None)
    assert java and jar.is_file()
    digest = hashlib.sha256(jar.read_bytes()).hexdigest()
    runs = ROOT/'build/smoke'
    runs.mkdir(exist_ok=True)
    directory = Path(tempfile.mkdtemp(prefix='scene-event-', dir=runs))
    assert directory.resolve().is_relative_to(runs.resolve())
    fixture = directory/'resources-fixture'
    guarded = ['resource-manifest.json', 'ExcelOutput/PlaneEvent.json', 'ExcelOutput/StageConfig.json',
               'Config/Level/Mission/3011602/Mission_301160201.json', 'Config/Level/Mission/3011607/Mission_301160701.json']
    hashes = {name: hashlib.sha256((source/name).read_bytes()).hexdigest() for name in guarded}
    junctions = mirror_resources(source, fixture, {})
    flags = subprocess.CREATE_NO_WINDOW if os.name == 'nt' else 0
    if junctions:
        plan = directory/'junction-plan.json'
        plan.write_text(json.dumps(junctions), 'utf-8')
        helper = directory/'prepare-fixture.ps1'
        helper.write_text("param([string]$Plan)\n$ErrorActionPreference='Stop'\nforeach ($binding in (Get-Content -LiteralPath $Plan -Raw | ConvertFrom-Json)) { New-Item -ItemType Junction -Path $binding.target -Value $binding.source | Out-Null }\n", 'utf-8')
        subprocess.run(['powershell', '-NoProfile', '-NonInteractive', '-File', str(helper), '-Plan', str(plan)], check=True, capture_output=True, creationflags=flags)
    (fixture/'resource-manifest.json').write_text(json.dumps({'clientVersion': '4.6.0', 'testFixture': True, 'originalSceneEvents': [20134221, 20134222]}), 'utf-8')
    assert not os.path.samefile(fixture/'resource-manifest.json', source/'resource-manifest.json')
    events = json.loads((source/'ExcelOutput/PlaneEvent.json').read_text('utf-8'))
    stages = {row['StageID']: row for row in json.loads((source/'ExcelOutput/StageConfig.json').read_text('utf-8'))}
    plans = []
    for event in [20134221, 20134222]:
        matches = [row for row in events if row['EventID'] == event and row.get('WorldLevel', 0) == 0]
        assert len(matches) == 1
        stage = matches[0]['StageID']
        waves = [[row[key] for key in ['Monster0', 'Monster1', 'Monster2', 'Monster3', 'Monster4'] if row.get(key, 0) != 0] for row in stages[stage]['MonsterList']]
        plans.append({'event': event, 'stage': stage, 'waves': [wave for wave in waves if wave]})
    legend_events = [426004, 426001, 426005] if args.reject_local_legend else []
    for event in legend_events:
        matches = [row for row in events if row['EventID'] == event and row.get('WorldLevel', 0) == 0]
        assert len(matches) == 1
        assert stages[matches[0]['StageID']]['StageType'] == 'LocalLegend'
    http, mongo, game = free_port(), free_port(), free_port(socket.SOCK_DGRAM)
    (directory/'config.json').write_text(json.dumps({'protocol': {'wireVersion': '4.6.0'}, 'resourceDir': str(fixture),
        'httpServer': {'bindAddress': '127.0.0.1', 'bindPort': http}, 'gameServer': {'bindAddress': '127.0.0.1', 'bindPort': game},
        'internalMongoServer': {'address': '127.0.0.1', 'port': mongo, 'filePath': './data/database.mv'}}), 'utf-8')
    java_flags = ['-Dfile.encoding=UTF-8', '-Dstdout.encoding=UTF-8', '-Dstderr.encoding=UTF-8', '-Djdk.net.unixdomain.tmpdir='+str(directory/'tcp-fallback')]
    command = [str(java), *java_flags, '-Xmx1536m', '-jar', str(jar), '--no-handbook']
    base = f'http://127.0.0.1:{http}'
    credentials = None
    snapshots = []
    stops = 0

    def request(route, body=None):
        req = urllib.request.Request(base+route, data=None if body is None else json.dumps(body).encode(), headers={'Content-Type': 'application/json'})
        with urllib.request.urlopen(req, timeout=5) as response:
            return json.load(response)

    for iteration, phases in enumerate([['battle', 'read'], ['read'], ['read']]):
        with (directory/f'server-{iteration}.log').open('w', encoding='utf-8') as log:
            process = subprocess.Popen(command, cwd=directory, stdin=subprocess.PIPE, stdout=log, stderr=subprocess.STDOUT, creationflags=flags)
            try:
                deadline = time.monotonic()+90
                while True:
                    assert process.poll() is None, directory
                    try:
                        if request('/health/ready')['ready']:
                            break
                    except (OSError, urllib.error.URLError):
                        pass
                    if time.monotonic() > deadline:
                        raise RuntimeError(f'Scene event readiness timeout: {directory}')
                    time.sleep(.2)
                if iteration == 0:
                    login = request('/hkrpg_cn/mdk/shield/api/login', {'account': 'cn-46-scene-event', 'password': 'local-test'})
                    assert login['retcode'] == 0
                    account = login['data']['account']
                    combo = request('/hkrpg_cn/combo/granter/login/v2/login', {'data': json.dumps({'uid': account['uid'], 'token': account['token']})})
                    assert combo['retcode'] == 0
                    credentials = {'accountUid': account['uid'], 'comboToken': combo['data']['combo_token']}
                for phase in phases:
                    assert hashlib.sha256(jar.read_bytes()).hexdigest() == digest
                    result = subprocess.run([str(java), *java_flags, '-cp', str(jar), 'org.hkrpg.server.protocol.LoopbackSceneEventProbe', str(game)],
                        input=json.dumps(dict(credentials, phase=phase, stagePlans=plans,
                                              rejectLocalLegend=args.reject_local_legend,
                                              checkTargetReports=args.check_target_reports)), text=True, encoding='utf-8', capture_output=True, timeout=90, creationflags=flags)
                    (directory/f'probe-{len(snapshots)}.log').write_text(result.stdout+result.stderr, 'utf-8')
                    assert result.returncode == 0, directory
                    value = json.loads(next(line for line in reversed(result.stdout.splitlines()) if line.startswith('{')))
                    assert value['passed']
                    snapshots.append(value)
                time.sleep(2)
            finally:
                if process.poll() is None:
                    try:
                        process.stdin.write(b'stop\n')
                        process.stdin.flush()
                        process.wait(timeout=30)
                        assert process.returncode == (1000 if os.name == 'nt' else 232), directory
                        stops += 1
                    except BaseException:
                        if process.poll() is None:
                            process.terminate()
                            process.wait(timeout=15)
                        raise
                    finally:
                        process.stdin.close()
    assert [row['phase'] for row in snapshots] == ['battle', 'read', 'read', 'read']
    expected_checks = [46, 22, 22, 22] if args.reject_local_legend else [34, 16, 16, 16]
    if args.check_target_reports:
        expected_checks[0] += 60
    assert [len(row['checks']) for row in snapshots] == expected_checks
    assert [row['invalidTargetReportsRejected'] for row in snapshots] == ([56, 0, 0, 0] if args.check_target_reports else [0, 0, 0, 0])
    assert len({row['uid'] for row in snapshots}) == 1 and stops == 3
    assert len(snapshots[0]['actualResourceBattles']) == 2
    assert hashes == {name: hashlib.sha256((source/name).read_bytes()).hexdigest() for name in guarded}
    assert hashlib.sha256(jar.read_bytes()).hexdigest() == digest
    print(json.dumps({'passed': True, 'runtimeDirectory': str(directory), 'snapshots': snapshots,
        'checks': [check for row in snapshots for check in row['checks']], 'normalStopCount': stops,
        'rootJarSHA256': digest, 'sourceSHA256': hashes, 'sourceResourcesModified': False,
        'sameUIDNormalH2ProcessRestarts': 2, 'exactOriginalEventAndWorldZeroStageAndMonsterWavesVerified': True,
        'localLegendGenericEntryRejectedEventIds': legend_events,
        'localLegendDedicatedEntryVerified': False,
        'invalidTargetReportsRejected': sum(row['invalidTargetReportsRejected'] for row in snapshots),
        'fixture': {'originalEventAndStageTablesUnmodified': True, 'missionScriptDirectoryIsolatedAndEmpty': True, 'completedMissionsOrRewardsSeeded': False},
        'fullMissionScriptOrEventAuthorizationVerified': False, 'clientGameplayVerified': False, 'crashAtomicityVerified': False}, ensure_ascii=False))


if __name__ == '__main__':
    main()
