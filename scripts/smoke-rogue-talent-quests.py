"""原始能力树实际激活/两项成就领奖、同 UID 重连和两次正常 H2 重启；前置与初始预算为隔离夹具。"""
import hashlib
import json
import os
import queue
import threading
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
    source = ROOT / 'resources/4.6'
    jar = ROOT / 'build/libs/hkrpg-server.jar'
    java = next((ROOT / '.tools').glob('jdk-21*/bin/java.exe'), None)
    assert java and jar.is_file()
    digest = hashlib.sha256(jar.read_bytes()).hexdigest()
    runs = ROOT / 'build/smoke'
    runs.mkdir(exist_ok=True)
    directory = Path(tempfile.mkdtemp(prefix='talent-quests-', dir=runs))
    assert directory.resolve().is_relative_to(runs.resolve())
    fixture = directory / 'resources-fixture'
    guarded = ['resource-manifest.json'] + ['ExcelOutput/' + name + '.json' for name in [
        'MainMission', 'QuestData', 'FinishWay', 'AchievementData', 'RewardData', 'ItemConfig',
        'RogueTalent', 'RogueUnlockConfig', 'FinishWayRogue', 'ConstValueRogue']]
    for id in [1000201, 4030007]:
        folder = source / f'Config/Level/Mission/{id}'
        assert folder.is_dir()
        guarded.extend(p.relative_to(source).as_posix() for p in folder.rglob('*') if p.is_file())
    hashes = {name: hashlib.sha256((source / name).read_bytes()).hexdigest() for name in guarded}
    assert hashes['resource-manifest.json'] == 'ecccb21469df76282e6df5bf0cbe1eff7d9f904c2dd644092124b6904482e735'
    mains = json.loads((source / 'ExcelOutput/MainMission.json').read_text('utf-8'))
    for id in [1000201, 4030007]:
        row = next(r for r in mains if r['MainMissionID'] == id)
        row.update(TakeOperation='And', TakeParam=[{'Type': 'Auto'}], BeginOperation='And', BeginParam=[{'Type': 'Auto'}], RewardID=0, SubRewardList=[])
    junctions = mirror_resources(source, fixture, {'ExcelOutput/MainMission.json': mains})
    # Only prerequisite graphs are substituted. Achievement goals, states and payments are never seeded.
    for id in [1000201, 4030007]:
        folder = fixture / f'Config/Level/Mission/{id}'
        folder.mkdir(parents=True, exist_ok=False)
        sub = id * 100 + 99
        (folder / f'MissionInfo_{id}.json').write_text(json.dumps({'MainMissionID': id, 'StartSubMissionList': [sub], 'FinishSubMissionList': [sub]}), 'utf-8')
        (folder / f'MissionInfo_{id}.partial.json').write_text(json.dumps({'SubMissionList': [{'ID': sub, 'MainMissionID': id, 'TakeType': 'Auto', 'FinishType': 'Auto', 'Progress': 1}]}), 'utf-8')
    flags = subprocess.CREATE_NO_WINDOW if os.name == 'nt' else 0
    if junctions:
        plan = directory / 'junction-plan.json'
        plan.write_text(json.dumps(junctions), 'utf-8')
        helper = directory / 'prepare-fixture.ps1'
        helper.write_text("param([string]$Plan)\n$ErrorActionPreference='Stop'\nforeach ($binding in (Get-Content -LiteralPath $Plan -Raw | ConvertFrom-Json)) { New-Item -ItemType Junction -Path $binding.target -Value $binding.source | Out-Null }\n", 'utf-8')
        subprocess.run(['powershell', '-NoProfile', '-NonInteractive', '-File', str(helper), '-Plan', str(plan)], check=True, capture_output=True, creationflags=flags)
    (fixture / 'resource-manifest.json').write_text(json.dumps({'clientVersion': '4.6.0', 'testFixture': True, 'syntheticNoRewardPrerequisites': [1000201, 4030007]}), 'utf-8')
    http, mongo, game = free_port(), free_port(), free_port(socket.SOCK_DGRAM)
    (directory / 'config.json').write_text(json.dumps({
        'protocol': {'wireVersion': '4.6.0'}, 'resourceDir': str(fixture),
        'httpServer': {'bindAddress': '127.0.0.1', 'bindPort': http}, 'gameServer': {'bindAddress': '127.0.0.1', 'bindPort': game},
        'internalMongoServer': {'address': '127.0.0.1', 'port': mongo, 'filePath': './data/database.mv'}}), 'utf-8')
    classes = directory / 'probe-classes'
    classes.mkdir()
    probe_source = ROOT / 'scripts/fixtures/java/org/hkrpg/server/protocol/NativeTalentQuestProbe.java'
    subprocess.run([str(java.with_name('javac.exe')), '-encoding', 'UTF-8', '-cp', str(jar), '-d', str(classes), str(probe_source)], check=True, capture_output=True, creationflags=flags)
    command = [str(java), '-Xmx1536m', '-Djdk.net.unixdomain.tmpdir=' + str(directory / 'tcp-fallback'), '-jar', str(jar), '--no-handbook']
    costs = {str(r['TalentID']): sum(c['ItemNum'] for c in r['Cost']) for r in json.loads((source / 'ExcelOutput/RogueTalent.json').read_text('utf-8')) if 1 <= r['TalentID'] <= 26}
    expected_points = 100000 - sum(costs.values())
    assert len(costs) == 26 and expected_points >= 0

    def request(route, body=None):
        req = urllib.request.Request(f'http://127.0.0.1:{http}' + route, data=None if body is None else json.dumps(body).encode(), headers={'Content-Type': 'application/json'})
        with urllib.request.urlopen(req, timeout=5) as response:
            return json.load(response)

    def probe(name, credentials, process):
        assert hashlib.sha256(jar.read_bytes()).hexdigest() == digest
        child = subprocess.Popen([str(java), command[2], '-cp', str(jar) + os.pathsep + str(classes), 'org.hkrpg.server.protocol.NativeTalentQuestProbe', str(game)],
            cwd=directory, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, encoding='utf-8', errors='replace', creationflags=flags)
        lines, available = [], queue.Queue()
        def capture():
            for line in child.stdout:
                lines.append(line);available.put(line)
            available.put(None)
        reader=threading.Thread(target=capture,daemon=True);reader.start()
        try:
            child.stdin.write(json.dumps(credentials)+'\n');child.stdin.flush()
            deadline=time.monotonic()+60
            if not credentials.get('resume'):
                while True:
                    line=available.get(timeout=max(.1,deadline-time.monotonic()))
                    assert line is not None, directory/name
                    if line.startswith('HKRPG_TALENT_BUDGET_READY:'):
                        uid=int(line.strip().rsplit(':',1)[1]);break
                # Existing console/inventory route grants only the isolated starting currency.
                process.stdin.write(f'give 32 x100000 @{uid}\n'.encode());process.stdin.flush()
                child.stdin.write('resume\n');child.stdin.flush()
            child.stdin.close();child.wait(timeout=max(.1,deadline-time.monotonic()));reader.join(timeout=3)
            assert child.returncode==0, str(directory/name)
            snapshot=json.loads(next(line for line in lines if line.startswith('{"uid":')))
            assert snapshot['passed'] and not snapshot['clientGameplayVerified'] and snapshot['jades']==10 and snapshot['talentPoints']==expected_points
            return snapshot
        finally:
            if child.poll() is None:child.terminate();child.wait(timeout=10)
            if child.stdin and not child.stdin.closed:child.stdin.close()
            reader.join(timeout=3)
            (directory/name).write_text(''.join(lines),'utf-8')

    snapshots, credentials, stops = [], None, 0
    for iteration in range(3):
        with (directory / f'server-{iteration}.log').open('w', encoding='utf-8') as log:
            process = subprocess.Popen(command, cwd=directory, stdin=subprocess.PIPE, stdout=log, stderr=subprocess.STDOUT, creationflags=flags)
            try:
                deadline = time.monotonic() + 90
                while True:
                    if process.poll() is not None:
                        raise RuntimeError('Server exited: ' + str(directory))
                    try:
                        if request('/health/ready')['ready']:
                            break
                    except (OSError, urllib.error.URLError):
                        pass
                    if time.monotonic() > deadline:
                        raise RuntimeError('Startup timed out: ' + str(directory))
                    time.sleep(.2)
                if iteration == 0:
                    login = request('/hkrpg_cn/mdk/shield/api/login', {'account': 'cn-talent-achievements', 'password': 'local-test'})
                    assert login['retcode'] == 0
                    account = login['data']['account']
                    combo = request('/hkrpg_cn/combo/granter/login/v2/login', {'data': json.dumps({'uid': account['uid'], 'token': account['token']})})
                    assert combo['retcode'] == 0
                    credentials = {'accountUid': account['uid'], 'comboToken': combo['data']['combo_token'], 'costs': costs}
                    first = probe('first.log', credentials, process)
                    assert first['actualActivations'] == first['currencyNotifications'] == first['questNotifications'] == 26 and first['jadeNotifications'] == 2 and first['questQueries'] == 4
                    snapshots.append(first)
                    credentials['resume'] = True
                resumed = probe(f'resume-{iteration}.log', credentials, process)
                assert resumed['actualActivations'] == resumed['questNotifications'] == resumed['currencyNotifications'] == resumed['jadeNotifications'] == 0 and resumed['questQueries'] == 2
                assert resumed['uid'] == snapshots[0]['uid']
                snapshots.append(resumed)
                time.sleep(2)
            finally:
                if process.poll() is None:
                    try:
                        process.stdin.write(b'stop\n'); process.stdin.flush(); process.wait(timeout=30)
                        assert process.returncode == (1000 if os.name == 'nt' else 232), directory
                        stops += 1
                    except BaseException:
                        if process.poll() is None:
                            process.terminate(); process.wait(timeout=15)
                        raise
                    finally:
                        process.stdin.close()
    assert stops == 3 and len(snapshots) == 4
    assert hashes == {name: hashlib.sha256((source / name).read_bytes()).hexdigest() for name in guarded}
    print(json.dumps({'passed': True, 'runtimeDirectory': str(directory), 'rootJarSHA256': digest, 'snapshots': snapshots,
        'normalStops': stops, 'sameUIDNormalH2ProcessRestarts': 2, 'originalTalentPrices': costs,
        'sourceResourceHashes': hashes, 'sourceResourcesModified': False,
        'fixture': {'syntheticNoRewardPrerequisites': [1000201, 4030007], 'actualItem32ViaIsolatedGiveCommand': 100000, 'originalAchievementsAndTalentRulesUnchanged': True, 'officialTutorialVerified': False},
        'clientGameplayVerified': False, 'crashAtomicityVerified': False}, ensure_ascii=False))


if __name__ == '__main__':
    main()
