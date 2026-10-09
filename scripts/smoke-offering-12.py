"""真实骰子供奉12的25级成本/全部奖励、等级商店、角色取得与KCP/H2正常恢复；积分来自隔离音律夹具。"""
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

ROOT = Path(__file__).resolve().parents[1]


def free_port(kind=socket.SOCK_STREAM):
    with socket.socket(type=kind) as stream:
        stream.bind(('127.0.0.1', 0))
        return stream.getsockname()[1]


def request(base, route, body=None):
    req = urllib.request.Request(base + route, data=None if body is None else json.dumps(body).encode(),
                                 headers={'Content-Type': 'application/json'})
    with urllib.request.urlopen(req, timeout=5) as response:
        return json.load(response)


def main():
    java = next((ROOT / '.tools').glob('jdk-21*/bin/java.exe'), None)
    java = str(java) if java else 'java'
    artifact = ROOT / 'build/libs/hkrpg-server.jar'
    assert artifact.is_file(), '先运行 scripts/build.ps1'
    source = ROOT / 'resources/4.6'
    runs = ROOT / 'build/smoke'
    runs.mkdir(parents=True, exist_ok=True)
    directory = Path(tempfile.mkdtemp(prefix='offering-12-', dir=runs))
    fixture = directory / 'resources-fixture'
    excel = fixture / 'ExcelOutput'
    excel.mkdir(parents=True)
    modified = {'ActivityPanel.json', 'MusicRhythmGroup.json', 'RewardData.json'}
    guarded = modified | {'ActivityConfig.json', 'ActivityDiceConstValueCommo.json', 'ActivityDiceAvatarConfig.json',
        'ActivityDiceConfig.json', 'ActivityDiceShopConfig.json', 'ActivityDiceShopGoodsConfig.json', 'ItemConfig.json', 'OfferingTypeConfig.json', 'OfferingLevelConfig.json', 'PlayerOutfitBase.json', 'PlayerOutfitSlot.json'}
    hashes = {name: hashlib.sha256((source / 'ExcelOutput' / name).read_bytes()).hexdigest() for name in sorted(guarded)}
    original = {}
    for path in (source / 'ExcelOutput').glob('*.json'):
        target = excel / path.name
        if path.name not in modified:
            os.link(path, target)
            continue
        data = json.loads(path.read_text(encoding='utf-8'))
        rows = list(data.values()) if isinstance(data, dict) else data
        if path.name == 'ActivityPanel.json':
            for row in rows:
                if row['PanelID'] in [50073, 50022]:
                    original[str(row['PanelID'])] = row.get('UnlockConditions', '')
                    row['UnlockConditions'] = ''
        elif path.name == 'MusicRhythmGroup.json':
            row = next(r for r in rows if r['ID'] == 1)
            original['musicGroupGate'] = row['UnlockSubMissionID']
            row['UnlockSubMissionID'] = 0
        elif path.name == 'RewardData.json':
            row = next(r for r in rows if r['RewardID'] == 261001)
            original['fundingReward'] = dict(row)
            row.clear()
            row.update(RewardID=261001, ItemID_1=264996, Count_1=10, ItemID_2=264999, Count_2=2050)
        target.write_text(json.dumps(data, ensure_ascii=False), encoding='utf-8')
    if os.name == 'nt':
        def literal(path):
            return "'" + str(path).replace("'", "''") + "'"
        subprocess.run(['powershell', '-NoProfile', '-NonInteractive', '-Command',
            'New-Item -ItemType Junction -Path ' + literal(fixture / 'Config') + ' -Value ' + literal(source / 'Config') + ' | Out-Null'],
            check=True, capture_output=True, text=True, creationflags=subprocess.CREATE_NO_WINDOW)
    else:
        (fixture / 'Config').symlink_to(source / 'Config', target_is_directory=True)
    (fixture / 'resource-manifest.json').write_text(json.dumps({'clientVersion': '4.6.0', 'testFixture': True,
        'openedPanels': [50073, 50022], 'fixtureFundingReward': 261001, 'originalGates': original}), encoding='utf-8')
    data_dir = directory / 'data'
    data_dir.mkdir()
    windows = {'activities': [{'panelId': 50073, 'moduleId': 5007301, 'beginTime': 0, 'endTime': int(time.time()) + 86400},
        {'panelId': 50022, 'moduleId': 0, 'beginTime': 0, 'endTime': int(time.time()) + 86400}]}
    (data_dir / 'Gameplay46.json').write_text(json.dumps(windows), encoding='utf-8')
    http_port, mongo_port, game_port = free_port(), free_port(), free_port(socket.SOCK_DGRAM)
    (directory / 'config.json').write_text(json.dumps({'protocol': {'wireVersion': '4.6.0'}, 'resourceDir': str(fixture),
        'httpServer': {'bindAddress': '127.0.0.1', 'bindPort': http_port}, 'gameServer': {'bindAddress': '127.0.0.1', 'bindPort': game_port},
        'internalMongoServer': {'address': '127.0.0.1', 'port': mongo_port, 'filePath': './data/database.mv'}}), encoding='utf-8')
    command = [java, '-Xmx1536m', f'-Djdk.net.unixdomain.tmpdir={directory / "tcp-fallback"}', '-jar', str(artifact), '--no-console', '--no-handbook']
    base = f'http://127.0.0.1:{http_port}'

    def probe(name, credentials):
        run = subprocess.run([java, command[2], '-cp', str(artifact), 'org.hkrpg.server.protocol.LoopbackOfferingProbe', str(game_port)],
            input=json.dumps(credentials), cwd=directory, capture_output=True, text=True, encoding='utf-8', errors='replace', timeout=35,
            creationflags=subprocess.CREATE_NO_WINDOW if os.name == 'nt' else 0)
        (directory / name).write_text(run.stdout + run.stderr, encoding='utf-8')
        if run.returncode:
            raise RuntimeError(f'Offering probe failed; inspect {directory / name}')
        result = json.loads(next(line for line in run.stdout.splitlines() if line.startswith('{"uid":')))
        assert result['passed'] and not result['clientGameplayVerified']
        return result

    snapshots, credentials = [], None
    for iteration in range(3):
        if iteration == 2:
            (data_dir / 'Gameplay46.json').write_text(json.dumps({'activities': []}), encoding='utf-8')
        with (directory / f'server-{iteration}.log').open('w', encoding='utf-8') as log:
            process = subprocess.Popen(command, cwd=directory, stdin=subprocess.DEVNULL, stdout=log, stderr=subprocess.STDOUT,
                creationflags=subprocess.CREATE_NO_WINDOW if os.name == 'nt' else 0)
            try:
                deadline = time.monotonic() + 180
                while True:
                    if process.poll() is not None:
                        raise RuntimeError(f'Offering server exited; inspect {directory}')
                    try:
                        if request(base, '/health/ready')['ready']:
                            break
                    except (OSError, urllib.error.URLError):
                        pass
                    if time.monotonic() > deadline:
                        raise RuntimeError(f'Offering startup timeout; inspect {directory}')
                    time.sleep(.2)
                if iteration == 0:
                    login = request(base, '/hkrpg_cn/mdk/shield/api/login', {'account': 'cn-46-offering-12', 'password': 'local-test'})
                    assert login['retcode'] == 0
                    account = login['data']['account']
                    combo = request(base, '/hkrpg_cn/combo/granter/login/v2/login', {'data': json.dumps({'uid': account['uid'], 'token': account['token']})})
                    assert combo['retcode'] == 0
                    credentials = {'accountUid': account['uid'], 'comboToken': combo['data']['combo_token'], 'mode': 'fresh'}
                    first = probe('offering-fresh.log', credentials)
                    snapshots.append(first)
                    credentials.update(mode='resume', unlockTimes=first['unlockTimes'], initialCredits=first['initialCredits'], initialJade=first['initialJade'])
                    snapshots.append(probe('offering-resume.log', credentials))
                else:
                    credentials['mode'] = 'restart' if iteration == 1 else 'closed'
                    snapshots.append(probe('offering-' + credentials['mode'] + '.log', credentials))
                time.sleep(2)
            finally:
                if process.poll() is None:
                    process.terminate()
                    try:
                        process.wait(timeout=15)
                    except subprocess.TimeoutExpired:
                        process.kill()
                        process.wait(timeout=5)
    for snapshot in snapshots:
        for key in ['uid', 'offeringLevel', 'totalExp', 'claimedLevels', 'material', 'credits', 'jade', 'playerLevel', 'displayExp', 'unlockTimes', 'avatarDice', 'equippedOutfits']:
            assert snapshot[key] == first[key], f'Persisted Offering state changed: {key}'
    assert all(hashlib.sha256((source / 'ExcelOutput' / name).read_bytes()).hexdigest() == sha for name, sha in hashes.items())
    print(json.dumps({'passed': True, 'runtimeDirectory': str(directory), 'snapshots': snapshots,
        'checks': ['query-zero-without-fabricated-maximum', 'Offering-level-gate-rejected-before-progress',
            'real-2000-point-debit-to-level25-leaves50', 'unearned-level26-rejected', 'all25-authored-reward-vectors-granted-once',
            'outfit-item-stored-without-auto-equip', 'reward-avatar-source-base-dice-and-time', 'Offering-level25-unlocks-real-shop-good1201',
            'one-actual-price5-debit-and-native-die-acquisition', 'same-uid-reconnect-and-normal-H2-restart',
            'unowned-outfit1006-rejected-without-sync', 'earned-outfit1005-equip-clear-and-reequip-with-avatar-query',
            'outfit-sync-and-same-uid-normal-H2-restart-with-closed-activity',
            'closed-activity-retains-progress-ownership-and-rejects-debit-rewards-purchase'],
        'fixture': {'panels': [50073, 50022], 'fundingRewardOverride': {'reward': 261001, 'items': {'264996': 10, '264999': 2050}},
            'actualOffering': 12, 'actualMaximum': 25, 'actualLevelCosts': [50] * 10 + [100] * 15, 'actualGood': 1201, 'actualPrice': 5,
            'originalGates': original, 'sourceResourceHashes': hashes, 'sourceResourcesModified': False,
            'officialStoryUnlockVerified': False, 'crashRecoveryVerified': False}, 'clientGameplayVerified': False}, ensure_ascii=False))


if __name__ == '__main__':
    main()
