"""空拍照报告、两项原始日常各100活跃度、重连/正常H2重启；不验证照片或游戏UI。"""
from pathlib import Path
import hashlib, json, os, socket, subprocess, tempfile, time, urllib.error, urllib.request

ROOT = Path(__file__).resolve().parents[1]


def free_port(kind=socket.SOCK_STREAM):
    with socket.socket(type=kind) as sock:
        sock.bind(('127.0.0.1', 0))
        return sock.getsockname()[1]


def main():
    source = ROOT / 'resources/4.6'
    artifact = ROOT / 'build/libs/hkrpg-server.jar'
    java = next((ROOT / '.tools').glob('jdk-21*/bin/java.exe'), None)
    assert java is not None and artifact.is_file()
    jar_sha = hashlib.sha256(artifact.read_bytes()).hexdigest()
    runs = ROOT / 'build/smoke'
    runs.mkdir(parents=True, exist_ok=True)
    directory = Path(tempfile.mkdtemp(prefix='picture-', dir=runs))
    assert directory.resolve().is_relative_to(runs.resolve())
    fixture = directory / 'resources-fixture'
    excel = fixture / 'ExcelOutput'
    excel.mkdir(parents=True)
    guarded = ['resource-manifest.json', 'ExcelOutput/DailyActiveQuestPool.json', 'ExcelOutput/QuestData.json',
               'ExcelOutput/FinishWay.json', 'ExcelOutput/RewardData.json', 'ExcelOutput/ItemConfig.json',
               'ExcelOutput/MapEntrance.json', 'ExcelOutput/MazePlane.json']
    hashes = {name: hashlib.sha256((source / name).read_bytes()).hexdigest() for name in guarded}
    for origin in (source / 'ExcelOutput').iterdir():
        target = excel / origin.name
        if origin.name == 'DailyActiveQuestPool.json':
            rows = [row for row in json.loads(origin.read_text('utf-8')) if row['QuestID'] in {2100129, 2100149}]
            assert rows == [{'QuestID': 2100129, 'Type': 3}, {'QuestID': 2100149, 'Type': 13}]
            target.write_text(json.dumps(rows), 'utf-8')
        else:
            os.link(origin, target)
    flags = subprocess.CREATE_NO_WINDOW if os.name == 'nt' else 0
    config = fixture / 'Config'
    if os.name == 'nt':
        assert config.resolve().is_relative_to(fixture.resolve())
        plan = directory / 'junction-plan.json'
        plan.write_text(json.dumps({'target': str(config), 'source': str(source / 'Config')}), 'utf-8')
        helper = directory / 'prepare-fixture.ps1'
        helper.write_text("param([string]$Plan)\n$ErrorActionPreference='Stop'\n"
                          "$binding=Get-Content -LiteralPath $Plan -Raw | ConvertFrom-Json\n"
                          "New-Item -ItemType Junction -Path $binding.target -Value $binding.source | Out-Null\n", 'utf-8')
        subprocess.run(['powershell', '-NoProfile', '-NonInteractive', '-File', str(helper), '-Plan', str(plan)],
                       check=True, capture_output=True, creationflags=flags)
    else:
        config.symlink_to(source / 'Config', target_is_directory=True)
    (fixture / 'resource-manifest.json').write_text(json.dumps({'clientVersion': '4.6.0', 'testFixture': True,
        'dailyPoolIsolated': True, 'dailyQuestAndRewardUnmodified': True, 'dailyUnlockGateUnmodified': True}), 'utf-8')
    http_port, mongo_port, game_port = free_port(), free_port(), free_port(socket.SOCK_DGRAM)
    (directory / 'config.json').write_text(json.dumps({'protocol': {'wireVersion': '4.6.0'}, 'resourceDir': str(fixture),
        'httpServer': {'bindAddress': '127.0.0.1', 'bindPort': http_port},
        'gameServer': {'bindAddress': '127.0.0.1', 'bindPort': game_port},
        'internalMongoServer': {'address': '127.0.0.1', 'port': mongo_port, 'filePath': './data/database.mv'}}), 'utf-8')
    command = [str(java), '-Xmx1536m', '-Djdk.net.unixdomain.tmpdir=' + str(directory / 'tcp-fallback'),
               '-jar', str(artifact), '--no-console', '--no-handbook']
    base = f'http://127.0.0.1:{http_port}'

    def request(route, body=None):
        req = urllib.request.Request(base + route, data=None if body is None else json.dumps(body).encode(),
                                     headers={'Content-Type': 'application/json'})
        with urllib.request.urlopen(req, timeout=5) as response:
            return json.load(response)

    def probe(name, credentials):
        assert hashlib.sha256(artifact.read_bytes()).hexdigest() == jar_sha
        result = subprocess.run([str(java), command[2], '-cp', str(artifact),
            'org.hkrpg.server.protocol.LoopbackPictureProbe', str(game_port)], input=json.dumps(credentials),
            text=True, encoding='utf-8', capture_output=True, timeout=40, creationflags=flags)
        (directory / name).write_text(result.stdout + result.stderr, 'utf-8')
        assert result.returncode == 0, str(directory / name)
        parsed = json.loads(next(line for line in reversed(result.stdout.splitlines()) if line.startswith('{')))
        assert parsed['passed']
        return parsed

    snapshots = []
    credentials = None
    first = None
    for iteration in range(2):
        assert hashlib.sha256(artifact.read_bytes()).hexdigest() == jar_sha
        with (directory / f'server-{iteration}.log').open('w', encoding='utf-8') as log:
            process = subprocess.Popen(command, cwd=directory, stdin=subprocess.DEVNULL, stdout=log,
                                       stderr=subprocess.STDOUT, creationflags=flags)
            try:
                deadline = time.monotonic() + 90
                while True:
                    if process.poll() is not None:
                        raise RuntimeError(f'Photo server exited; inspect {directory}')
                    try:
                        if request('/health/ready')['ready']:
                            break
                    except (OSError, urllib.error.URLError):
                        pass
                    if time.monotonic() > deadline:
                        raise RuntimeError(f'Photo readiness timeout; inspect {directory}')
                    time.sleep(.2)
                if iteration == 0:
                    login = request('/hkrpg_cn/mdk/shield/api/login', {'account': 'cn-46-photo', 'password': 'local-test'})
                    assert login['retcode'] == 0
                    account = login['data']['account']
                    combo = request('/hkrpg_cn/combo/granter/login/v2/login',
                        {'data': json.dumps({'uid': account['uid'], 'token': account['token']})})
                    assert combo['retcode'] == 0
                    credentials = {'accountUid': account['uid'], 'comboToken': combo['data']['combo_token']}
                    first = probe('photo-first.log', credentials)
                    assert all(first[key] for key in ['actualSceneAndActorVerified', 'nativeOriginalPhotoDailiesSelected',
                        'nativeNonemptyPhotoRejectedWithoutProgress', 'nativeAcceptedPhotoCompletesBothOriginalDailies',
                        'nativeRepeatedPhotoDoesNotDuplicateProgress', 'nativePhotoDoesNotAutomaticallyGrantPoints',
                        'nativeFirstOriginalHundredPoints', 'nativeSecondOriginalHundredPoints',
                        'nativeFirstDuplicatePhotoClaimRejected', 'nativeSecondDuplicatePhotoClaimRejected'])
                    snapshots.append(first)
                    credentials.update(resume=True)
                restored = probe(f'photo-resume-{iteration}.log', credentials)
                assert restored['savedPhotoDailiesRestored'] and restored['uid'] == first['uid']
                assert restored['nativeSavedPhotoReportDoesNotReopenClaims'] and restored['nativeSavedDuplicatePhotoClaimsRejected']
                snapshots.append(restored)
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
        assert snapshot['passed'] and snapshot['dailyPoints'] == 200
        assert snapshot['quest2100129Status'] == snapshot['quest2100149Status'] == 3
        assert snapshot['quest2100129Progress'] == snapshot['quest2100149Progress'] == 1
    assert hashes == {name: hashlib.sha256((source / name).read_bytes()).hexdigest() for name in guarded}
    assert hashlib.sha256(artifact.read_bytes()).hexdigest() == jar_sha
    print(json.dumps({'passed': True, 'runtimeDirectory': str(directory), 'snapshots': snapshots,
        'checks': ['actual-scene-and-actor', 'two-original-photo-objectives', 'invalid-report-no-progress',
            'accepted-report-finishes-objectives', 'repeated-report-no-extra-progress', 'report-no-auto-reward',
            'first-original-100-points', 'first-duplicate-claim-rejected', 'second-original-100-points',
            'second-duplicate-claim-rejected', 'same-uid-reconnect', 'normal-h2-restart',
            'saved-report-cannot-reopen-claims', 'saved-duplicate-claims-rejected'],
        'rootJarSHA256': jar_sha, 'sourceSha256': hashes, 'sourceResourcesModified': False,
        'fixture': {'dailyPoolIsolated': True, 'dailyQuestAndRewardUnmodified': True, 'dailyUnlockGateUnmodified': True},
        'picturePixelsVerified': False, 'clientGameplayVerified': False, 'networkDayRolloverVerified': False,
        'allScenesVerified': False, 'crashAtomicityVerified': False}, ensure_ascii=False))


if __name__ == '__main__':
    main()
