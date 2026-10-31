"""隔离 4.6 KCP/H2：新手池折扣、五十次上限、保底及两次正常重启。"""
import hashlib
import argparse
import http.cookiejar
import importlib.util
import json
import os
from pathlib import Path
import socket
import subprocess
import tempfile
import urllib.request
import uuid

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('gm_smoke', ROOT / 'scripts/smoke-gm-console.py')
gm = importlib.util.module_from_spec(spec)
spec.loader.exec_module(gm)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--browser-qa', action='store_true', help='在隔离 HTTP 服务上验证详情页')
    args = parser.parse_args()
    jar = ROOT / 'build/libs/hkrpg-server.jar'
    digest = hashlib.sha256(jar.read_bytes()).hexdigest()
    java = next((ROOT / '.tools').glob('jdk-21*/bin/java.exe'))
    runs = ROOT / 'build/smoke'
    runs.mkdir(exist_ok=True)
    directory = Path(tempfile.mkdtemp(prefix='gacha-rarity-', dir=runs))
    (directory / 'data').mkdir()
    helpers = directory / 'helpers'
    helpers.mkdir()
    flags = subprocess.CREATE_NO_WINDOW if os.name == 'nt' else 0
    sources = [ROOT / 'scripts/fixtures/java/org/hkrpg/server/protocol' / (n + '.java')
               for n in ('NativeGmPlayerProbe', 'GachaRarityFixtureInspector')]
    subprocess.run([str(java.with_name('javac.exe')), '-encoding', 'UTF-8', '-cp', str(jar),
                    '-d', str(helpers), *map(str, sources)], check=True, capture_output=True, creationflags=flags)
    banners = json.loads((ROOT / 'src/main/resources/defaults/Banners.json').read_text(encoding='utf-8'))
    next(row for row in banners if row['id'] == 4001)['disabled'] = False
    banner_path = directory / 'data/Banners.json'
    banner_path.write_text(json.dumps(banners, ensure_ascii=False), encoding='utf-8')
    original_banners = banner_path.read_bytes()
    http_port, mongo, game = gm.free_port(), gm.free_port(), gm.free_port(socket.SOCK_DGRAM)
    config = {'protocol': {'wireVersion': '4.6.0'}, 'resourceDir': str(ROOT / 'resources/4.6'),
              'httpServer': {'bindAddress': '127.0.0.1', 'bindPort': http_port},
              'gameServer': {'bindAddress': '127.0.0.1', 'bindPort': game},
              'internalMongoServer': {'address': '127.0.0.1', 'port': mongo, 'filePath': './data/database.mv'}}
    (directory / 'config.json').write_text(json.dumps(config), encoding='utf-8')
    java_flags = ['-Dfile.encoding=UTF-8', '-Dio.netty.eventLoopThreads=2',
                  '-Djdk.net.unixdomain.tmpdir=' + str(directory / 'tcp-fallback')]
    opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
    base = f'http://127.0.0.1:{http_port}'
    csrf = ''
    server = probe = None
    logs = []
    uid = None
    won = []
    account_name = 'beginner_' + uuid.uuid4().hex + '@example.invalid'

    def request(route, body=None):
        req = urllib.request.Request(base + route, data=None if body is None else json.dumps(body).encode(),
                                     headers={'Content-Type': 'application/json', 'Origin': base, 'X-GM-CSRF': csrf})
        with opener.open(req, timeout=30) as response:
            return json.load(response)

    def snapshot():
        return json.loads((directory / 'snapshot.json').read_text(encoding='utf-8'))

    def stock(s):
        return next((r['num'] for r in s['bag'].get('material_list', []) if r['tid'] == 101), 0)

    def count(s):
        row = next(r for r in s['gacha']['gacha_info_list'] if r['gacha_id'] == 4001)
        return row.get('NHBBHEMPJJM', 0)

    def stop():
        nonlocal probe, server
        if probe:
            (directory / 'quit').touch()
            probe.wait(timeout=40)
            assert probe.returncode == 0, 'Isolated KCP probe did not stop normally'
            probe = None
        if server:
            server.stdin.write(b'stop\n'); server.stdin.flush()
            server.wait(timeout=45)
            assert server.returncode == (1000 if os.name == 'nt' else 232), 'Isolated server did not stop normally'
            server.stdin.close(); server = None

    def inspect(action='inspect'):
        result = subprocess.run([str(java), *java_flags, '-cp', str(helpers) + os.pathsep + str(jar),
            'org.hkrpg.server.protocol.GachaRarityFixtureInspector', action, str(directory)],
            cwd=ROOT, capture_output=True, timeout=90, creationflags=flags, check=True)
        return json.loads(next(line.removeprefix('RARITY_FIXTURE=') for line in result.stdout.decode('utf-8').splitlines()
                               if line.startswith('RARITY_FIXTURE=')))

    def pull(expected_count, success=True):
        key = str(uuid.uuid4())
        target = directory / 'gacha-pull-request.json'
        temporary = target.with_suffix('.tmp')
        temporary.write_text(json.dumps({'requestId': key, 'count': 10, 'gachaId': 4001, 'success': success}), encoding='utf-8')
        temporary.replace(target)
        result = gm.wait_for(lambda: (lambda s: s if s.get('gachaPull', {}).get('requestId') == key else None)(snapshot()), 40)
        target.unlink()
        response = result['gachaPull']['response']
        if success:
            assert response['gacha_id'] == 4001 and response['gacha_num'] == 10
            assert response.get('NHBBHEMPJJM', 0) == expected_count
            rows = response['gacha_item_list']; assert len(rows) == 10
            won.extend(row['gacha_item']['item_id'] for row in rows)
        else:
            assert response.get('retcode') == 3006
        assert count(result) == expected_count
        return result

    def trial_request(command, stage=None):
        key = str(uuid.uuid4())
        target = directory / 'equipment-request.json'
        temporary = target.with_suffix('.tmp')
        data = {'requestId': key, 'command': command, 'success': True, 'wire': ''}
        if stage is not None: data['stageId'] = stage
        temporary.write_text(json.dumps(data), encoding='utf-8'); temporary.replace(target)
        result = gm.wait_for(lambda: (lambda s: s if s.get('equipmentReply', {}).get('requestId') == key else None)(snapshot()), 40)
        target.unlink()
        return result['equipmentReply']['response']

    try:
        for iteration in range(3):
            log = (directory / f'server-{iteration}.log').open('w', encoding='utf-8'); logs.append(log)
            server = subprocess.Popen([str(java), *java_flags, '-Xmx1536m', '-jar', str(jar), '--no-handbook'],
                cwd=directory, stdin=subprocess.PIPE, stdout=log, stderr=subprocess.STDOUT, creationflags=flags)
            gm.wait_for(lambda: server.poll() is None and request('/health/ready')['ready'])
            if iteration == 0 and args.browser_qa:
                from playwright.sync_api import sync_playwright
                with sync_playwright() as playwright:
                    browser = playwright.chromium.launch(headless=True, channel='chrome')
                    for width, height in ((1280, 900), (390, 844)):
                        page = browser.new_page(viewport={'width': width, 'height': height})
                        errors = []
                        page.on('pageerror', lambda error: errors.append(str(error)))
                        response = page.goto(base + '/gacha/details?gacha_id=2001&authkey=ignored-test', timeout=60000)
                        assert response.status == 200
                        assert page.get_by_role('heading', name='跃迁详情').count() == 1
                        assert page.get_by_text('希儿', exact=True).count() >= 1
                        assert page.get_by_role('heading', name='跃迁规则').count() == 1
                        page.get_by_role('link', name='对象清单').click()
                        assert page.evaluate('location.hash') == '#objects'
                        assert page.evaluate('document.documentElement.scrollWidth <= innerWidth')
                        assert 'ignored-test' not in page.locator('body').inner_text()
                        assert not errors, errors
                        page.screenshot(path=str(Path(tempfile.gettempdir()) / f'hkrpg-gacha-detail-{width}.png'), full_page=True)
                        page.close()
                    browser.close()
            csrf = request('/gm/api/session', {})['data']['csrf']
            account = request('/hkrpg_cn/mdk/shield/api/login', {'account': account_name, 'password': 'owned-beginner-smoke'})['data']['account']
            combo = request('/hkrpg_cn/combo/granter/login/v2/login', {'data': json.dumps({'uid': account['uid'], 'token': account['token']})})
            credentials = directory / 'credentials.json'
            credentials.write_text(json.dumps({'accountUid': account['uid'], 'comboToken': combo['data']['combo_token']}), encoding='utf-8')
            (directory / 'quit').unlink(missing_ok=True); (directory / 'snapshot.json').unlink(missing_ok=True)
            log = (directory / f'probe-{iteration}.log').open('w', encoding='utf-8'); logs.append(log)
            probe = subprocess.Popen([str(java), *java_flags, '-cp', str(helpers) + os.pathsep + str(jar),
                'org.hkrpg.server.protocol.NativeGmPlayerProbe', str(game), str(credentials), str(directory)],
                cwd=directory, stdout=log, stderr=subprocess.STDOUT, creationflags=flags)
            initial = gm.wait_for(snapshot)
            if uid is None: uid = initial['uid']
            assert initial['uid'] == uid
            (directory / 'rarity-fixture.json').write_text(json.dumps({'testFixture': True, 'uid': uid}), encoding='utf-8')
            if iteration == 0:
                operation = request('/gm/api/operate', {'requestId': str(uuid.uuid4()), 'uid': uid, 'action': 'grant',
                    'itemId': 101, 'amount': 40, 'level': 0, 'rank': 0})['data']
                assert operation['state'] == 'completed'
                gm.wait_for(lambda: stock(snapshot()) == 40, 30)
                assert stock(pull(10)) == 32
                assert stock(pull(20)) == 24
            elif iteration == 1:
                assert count(initial) == 20 and stock(initial) == 24
                schedules = trial_request('GetActivityScheduleConfigCsReq')['schedule_data']
                # The bundled 2002 lineup matches the other resource-backed rerun (20004).
                for panel, module in ((20001, 2000101), (20003, 2000401),
                                      (20005, 2000501), (20020, 2002002)):
                    assert any(row['panel_id'] == panel and row['activity_id'] == module for row in schedules)
                trial_request('GetTrialActivityDataCsReq')
                for stage in (310030, 311020, 312040, 310060, 313020):
                    assert trial_request('StartTrialActivityCsReq', stage).get('stage_id') == stage
                    assert trial_request('LeaveTrialActivityCsReq', stage).get('stage_id') == stage
                for n in (30, 40, 50):
                    assert stock(pull(n)) == 40 - n * 8 // 10
                after = pull(50, False)
                assert stock(after) == 0
            else:
                assert count(initial) == 50 and stock(initial) == 0
                assert stock(pull(50, False)) == 0
            stop()
            stored = inspect()
            assert stored['newbieDraws'] == (20 if iteration == 0 else 50)
            assert stored['ceiling'] == 0 and stored['tickets'] == (24 if iteration == 0 else 0)
            if iteration: assert stored['newbieFive']
            if iteration == 0: inspect('seedTrial')
            assert banner_path.read_bytes() == original_banners
        rarity = {}
        for name in ('ItemConfigAvatar.json', 'ItemConfigEquipment.json'):
            rows = json.loads((ROOT / 'resources/4.6/ExcelOutput' / name).read_text(encoding='utf-8'))
            rows = rows if isinstance(rows, list) else rows.values()
            rarity.update({row['ID']: row['Rarity'] for row in rows})
        assert len(won) == 50 and any(rarity[item] == 'SuperRare' and item < 20000 for item in won)
        assert hashlib.sha256(jar.read_bytes()).hexdigest() == digest
        report = {'passed': True, 'jarSHA256': digest, 'uid': uid, 'draws': len(won), 'ticketsSpent': 40,
                  'normalStops': 3, 'sameUidAfterRestarts': True, 'limitRetcode': 3006,
                  'trialSchedules': [2000101, 2000401, 2000501, 2002002],
                  'trialStagesStartedAndLeft': [310030, 311020, 312040, 310060, 313020],
                  'isolatedH2': True, 'operatorDataModified': False, 'clientGameplayVerified': False}
        output = ROOT / 'build/gacha-beginner-native.json'
        output.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding='utf-8')
        print(json.dumps(report, ensure_ascii=False))
    finally:
        if probe and probe.poll() is None: probe.terminate(); probe.wait(timeout=15)
        if server and server.poll() is None: server.terminate(); server.wait(timeout=15)
        for log in logs: log.close()


if __name__ == '__main__':
    main()
