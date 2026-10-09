"""九个世界的原始 Boss 入战、正式结果与正常 H2 恢复；首房和解锁为明确隔离夹具。"""
import argparse
import hashlib
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
    parser.add_argument('--resources', type=Path, default=ROOT / 'resources/4.6')
    parser.add_argument('--all-classic-areas', action='store_true', help='验证全部 32 个经典区域的 Boss 固定等级，不代表完整通关或官方难度算法')
    args = parser.parse_args()
    source = args.resources.resolve()
    jar = ROOT / 'build/libs/hkrpg-server.jar'
    java = next((ROOT / '.tools').glob('jdk-21*/bin/java.exe'), None)
    if java is None and os.environ.get('JAVA_HOME'):
        java = Path(os.environ['JAVA_HOME']) / 'bin/java.exe'
    classes = ROOT / 'build/classes/java/test'
    assert java and java.is_file() and jar.is_file()
    assert (classes / 'org/hkrpg/server/gameplay/RogueWorldBossFixtureInspector.class').is_file()
    digest = hashlib.sha256(jar.read_bytes()).hexdigest()
    runs = ROOT / 'build/smoke'
    runs.mkdir(exist_ok=True)
    directory = Path(tempfile.mkdtemp(prefix='rogue-world-boss-', dir=runs))
    assert directory.resolve().is_relative_to(runs.resolve())
    fixture = directory / 'resources-fixture'
    guarded = ['resource-manifest.json']
    guarded += [p.relative_to(source).as_posix() for p in (source / 'ExcelOutput').glob('*.json')]
    guarded += ['Config/LevelOutput/RuntimeFloor/P81222_F81222001.json']
    guarded += [f'Config/LevelOutput/SharedRuntimeGroup/Groups_P81222_F81222001/LevelGroup_P81222_F81222001_G{g}.json' for g in [37, 38, 39]]
    hashes = {name: hashlib.sha256((source / name).read_bytes()).hexdigest() for name in guarded}
    originals = json.loads((source / 'ExcelOutput/RogueAreaConfig.json').read_text('utf-8'))
    selected = ([r for r in originals if 1 <= r.get('AreaProgress', 0) <= 9 and r.get('Difficulty', 0) > 0]
                if args.all_classic_areas else [r for r in originals if r['RogueAreaID'] in [110, 120, 130, 140, 150, 160, 170, 180, 190]])
    count = 32 if args.all_classic_areas else 9
    assert len(selected) == count
    isolated = [dict(r, UnlockID=0) if r['RogueAreaID'] in {a['RogueAreaID'] for a in selected} else r for r in originals]
    junctions = mirror_resources(source, fixture, {'ExcelOutput/RogueAreaConfig.json': isolated})
    flags = subprocess.CREATE_NO_WINDOW if os.name == 'nt' else 0
    if junctions:
        plan = directory / 'junction-plan.json'
        plan.write_text(json.dumps(junctions), 'utf-8')
        helper = directory / 'prepare-fixture.ps1'
        helper.write_text("param([string]$Plan)\n$ErrorActionPreference='Stop'\nforeach ($binding in (Get-Content -LiteralPath $Plan -Raw | ConvertFrom-Json)) { New-Item -ItemType Junction -Path $binding.target -Value $binding.source | Out-Null }\n", 'utf-8')
        subprocess.run(['powershell', '-NoProfile', '-NonInteractive', '-File', str(helper), '-Plan', str(plan)],
                       check=True, capture_output=True, creationflags=flags)
    (fixture / 'resource-manifest.json').write_text(json.dumps({
        'clientVersion': '4.6.0', 'testFixture': True, 'rogueWorldBossFixture': True,
        'syntheticUnlockDisabledForAreas': [r['RogueAreaID'] for r in selected]}), 'utf-8')
    data = directory / 'data'; data.mkdir()
    defaults = (ROOT / 'src/main/resources/defaults/RogueBossGen.json').read_bytes()
    pools = json.loads((ROOT / 'src/main/resources/defaults/RogueMapGenByMap.json').read_text('utf-8'))
    monsters = json.loads((source / 'ExcelOutput/RogueMonster.json').read_text('utf-8'))
    groups = json.loads((source / 'ExcelOutput/RogueMonsterGroup.json').read_text('utf-8'))
    stages = json.loads((source / 'ExcelOutput/StageConfig.json').read_text('utf-8'))
    cases = []
    for area in selected:
        map_id = area['AreaProgress'] * 100 + area['Difficulty']
        content = 10901 + area['AreaProgress'] * 1000
        group = next(r for r in groups if r['RogueMonsterGroupID'] == content)
        weights = group['RogueMonsterListAndWeight']; assert len(weights) == 1
        monster = next(r for r in monsters if r['RogueMonsterID'] == int(next(iter(weights))))
        stage = next(r for r in stages if r['StageID'] == monster['EventID'])
        levels = area['DisplayMonsterMap']
        assert levels and len(set(levels.values())) == 1
        level = next(iter(levels.values()))
        assert 1 <= level <= 100
        cases.append({'area': area['RogueAreaID'], 'map': map_id, 'content': content,
                      'score': area['ScoreMap']['1'], 'expectedCredits': 0, 'levels': levels,
                      'actors': {'39': {'npc': monster['NpcMonsterID'], 'stage': monster['EventID'],
                                       'level': level,
                                       'waves': [list(wave.values()) for wave in stage['MonsterList']]}}})
        pools['maps'].setdefault(str(map_id), {})['1'] = [222713]
    (data / 'RogueMapGenByMap.json').write_text(json.dumps(pools), 'utf-8')
    assert not (data / 'RogueBossGen.json').exists()
    http, mongo, game = free_port(), free_port(), free_port(socket.SOCK_DGRAM)
    (directory / 'config.json').write_text(json.dumps({
        'protocol': {'wireVersion': '4.6.0'}, 'resourceDir': str(fixture),
        'httpServer': {'bindAddress': '127.0.0.1', 'bindPort': http},
        'gameServer': {'bindAddress': '127.0.0.1', 'bindPort': game},
        'internalMongoServer': {'address': '127.0.0.1', 'port': mongo, 'filePath': './data/database.mv'}}), 'utf-8')
    probe_classes = directory / 'probe-classes'
    probe_classes.mkdir()
    probe_source = ROOT / 'scripts/fixtures/java/org/hkrpg/server/protocol/NativeRogueWorldBossProbe.java'
    compilation = subprocess.run([str(java.with_name('javac.exe')), '-encoding', 'UTF-8', '-cp', str(jar),
                                  '-d', str(probe_classes), str(probe_source)], capture_output=True, creationflags=flags)
    (directory / 'javac.log').write_bytes(compilation.stdout + compilation.stderr)
    assert compilation.returncode == 0, directory / 'javac.log'
    fallback = '-Djdk.net.unixdomain.tmpdir=' + str(directory / 'tcp-fallback')
    encoding = ['-Dfile.encoding=UTF-8', '-Dstdout.encoding=UTF-8', '-Dstderr.encoding=UTF-8']
    command = [str(java), *encoding, '-Xmx1536m', fallback, '-jar', str(jar), '--no-handbook']

    def request(route, body=None):
        req = urllib.request.Request(f'http://127.0.0.1:{http}' + route,
            data=None if body is None else json.dumps(body).encode(), headers={'Content-Type': 'application/json'})
        with urllib.request.urlopen(req, timeout=5) as response:
            return json.load(response)

    snapshots, inspections, stops = [], [], 0
    def probe(case, mode):
        assert hashlib.sha256(jar.read_bytes()).hexdigest() == digest
        child = subprocess.run([str(java), *encoding, fallback, '-cp', str(jar) + os.pathsep + str(probe_classes),
            'org.hkrpg.server.protocol.NativeRogueWorldBossProbe', str(game)], cwd=directory,
            input=json.dumps(dict(case, mode=mode)) + '\n', capture_output=True,
            text=True, encoding='utf-8', errors='replace', timeout=90, creationflags=flags)
        log = directory / f'probe-{len(snapshots)}-{case["area"]}-{mode}.log'
        log.write_text(child.stdout + child.stderr, 'utf-8')
        assert child.returncode == 0, log
        snapshot = json.loads(next(line for line in child.stdout.splitlines() if line.startswith('{"uid":')))
        assert snapshot['passed'] and not snapshot['clientGameplayVerified']
        snapshots.append(snapshot)
        case['uid'] = snapshot['uid']
        if mode == 'settle':
            assert snapshot['credits'] == snapshot['battleDroppedCredits']
            case['expectedCredits'] = snapshot['credits']

    for iteration, mode in enumerate(['start', 'settle', 'finished']):
        with (directory / f'server-{iteration}.log').open('w', encoding='utf-8') as log:
            process = subprocess.Popen(command, cwd=directory, stdin=subprocess.PIPE,
                stdout=log, stderr=subprocess.STDOUT, creationflags=flags)
            try:
                deadline = time.monotonic() + 90
                while True:
                    assert process.poll() is None, directory
                    try:
                        if request('/health/ready')['ready']:
                            break
                    except (OSError, urllib.error.URLError):
                        pass
                    if time.monotonic() > deadline:
                        raise RuntimeError('Startup timed out: ' + str(directory))
                    time.sleep(.2)
                if iteration == 0:
                    for case in cases:
                        login = request('/hkrpg_cn/mdk/shield/api/login', {'account': f'cn-46-world-boss-{case["area"]}', 'password': 'local-test'})
                        assert login['retcode'] == 0
                        account = login['data']['account']
                        combo = request('/hkrpg_cn/combo/granter/login/v2/login', {
                            'data': json.dumps({'uid': account['uid'], 'token': account['token']})})
                        assert combo['retcode'] == 0
                        case.update(accountUid=account['uid'], comboToken=combo['data']['combo_token'])
                assert (data / 'RogueBossGen.json').read_bytes() == defaults
                for case in cases:
                    probe(case, mode)
                    if mode == 'start': probe(case, 'resume')
                expected = [{'uid': c['uid'], 'area': c['area'], 'map': c['map'], 'content': c['content'],
                             'npc': c['actors']['39']['npc'], 'stage': c['actors']['39']['stage'],
                             'level': c['actors']['39']['level'], 'levels': c['levels'],
                             'score': c['score'], 'credits': c['expectedCredits']} for c in cases]
                (directory / 'expected-bosses.json').write_text(json.dumps(expected), 'utf-8')
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
        child = subprocess.run([str(java), *encoding, fallback, '-cp', str(classes) + os.pathsep + str(jar),
            'org.hkrpg.server.gameplay.RogueWorldBossFixtureInspector', str(directory),
            'active' if iteration == 0 else 'finished'], cwd=ROOT,
            text=True, encoding='utf-8', capture_output=True, timeout=45, creationflags=flags)
        log = directory / f'inspector-{iteration}.log'
        log.write_text(child.stdout + child.stderr, 'utf-8')
        assert child.returncode == 0, log
        inspection = json.loads(next(line for line in reversed(child.stdout.splitlines()) if line.startswith('{')))
        assert inspection['passed'] and inspection['changedPlayers'] == 0
        inspections.append(inspection)
    assert stops == 3 and len(snapshots) == count * 4 and len(inspections) == 3
    for case in cases:
        rows = [s for s in snapshots if s['area'] == case['area']]
        assert len(rows) == 4 and {s['uid'] for s in rows} == {case['uid']}
        assert [s['mode'] for s in rows] == ['start', 'resume', 'settle', 'finished']
        assert [s['score'] for s in rows] == [0, 0, case['score'], case['score']]
        assert rows[2]['credits'] == rows[3]['credits'] == case['expectedCredits']
        assert all(s['bossLevel'] == case['actors']['39']['level'] for s in rows)
        assert rows[2]['encounters'][0]['level'] == case['actors']['39']['level']
    assert sum(s['battles'] for s in snapshots) == sum(s['removedSourceActors'] for s in snapshots) == count
    assert sum(s['staleBattleResultsRejected'] for s in snapshots) == sum(s['scoreNotifications'] for s in snapshots) == count
    assert inspections[1]['players'] == inspections[2]['players']
    assert (data / 'RogueBossGen.json').read_bytes() == defaults
    assert hashes == {name: hashlib.sha256((source / name).read_bytes()).hexdigest() for name in guarded}
    print(json.dumps({'passed': True, 'runtimeDirectory': str(directory), 'rootJarSHA256': digest,
        'snapshots': snapshots, 'inspections': inspections, 'normalStops': stops,
        'sameUIDNormalH2ProcessRestarts': 2, 'sourceResourceHashes': hashes,
        'classicAreasChecked': count, 'bossWaveLevelsVerified': True, 'officialDifficultyScalingVerified': False,
        'sourceResourcesModified': False, 'fixture': {'firstRoomAssigned': 222713,
            'unlockDisabledForAreas': [r['RogueAreaID'] for r in selected],
            'bossDefaultsInstalledByProduction': True, 'sourceMapAndBossRowsUnchanged': True,
            'officialRoomGenerationVerified': False},
        'clientGameplayVerified': False, 'allWorldsFullyPlayed': False,
        'combatInputReplayVerified': False, 'crashAtomicityVerified': False}, ensure_ascii=False))


if __name__ == '__main__':
    main()
