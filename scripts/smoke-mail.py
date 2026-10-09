"""邮件读取、真实附件领取、防重、删除与stop关服/H2恢复；仅使用隔离夹具。"""
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


def main():
    source = ROOT / 'resources/4.6'
    artifact = ROOT / 'build/libs/hkrpg-server.jar'
    classes = ROOT / 'build/classes/java/test'
    java = next((ROOT / '.tools').glob('jdk-21*/bin/java.exe'), None)
    assert java and artifact.is_file() and (classes / 'org/hkrpg/server/gameplay/MailAttachmentFixtureInspector.class').is_file()
    digest = hashlib.sha256(artifact.read_bytes()).hexdigest()
    runs = ROOT / 'build/smoke'
    runs.mkdir(parents=True, exist_ok=True)
    directory = Path(tempfile.mkdtemp(prefix='mail-attachments-', dir=runs))
    assert directory.resolve().is_relative_to(runs.resolve())
    fixture = directory / 'resources-fixture'
    excel = fixture / 'ExcelOutput'
    excel.mkdir(parents=True)
    guarded = ['resource-manifest.json', 'ExcelOutput/ItemConfig.json', 'ExcelOutput/ItemConfigEquipment.json',
               'ExcelOutput/EquipmentConfig.json', 'ExcelOutput/ConstValueCommon.json']
    hashes = {name: hashlib.sha256((source / name).read_bytes()).hexdigest() for name in guarded}
    for origin in (source / 'ExcelOutput').iterdir():
        os.link(origin, excel / origin.name)
    flags = subprocess.CREATE_NO_WINDOW if os.name == 'nt' else 0
    config = fixture / 'Config'
    if os.name == 'nt':
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
        'mailAttachmentFixture': True, 'originalExcelAndConfigUnmodified': True}), 'utf-8')
    http_port, mongo_port, game_port = free_port(), free_port(), free_port(socket.SOCK_DGRAM)
    (directory / 'config.json').write_text(json.dumps({'protocol': {'wireVersion': '4.6.0'}, 'resourceDir': str(fixture),
        'serverOptions': {'welcomeMail': {'title': '领取状态网络夹具', 'sender': '隔离验证', 'content': '原始物品与唯一领取',
            'attachments': [{'id': 2, 'count': 1000}, {'id': 235, 'count': 3}, {'id': 20000, 'count': 2}]}},
        'httpServer': {'bindAddress': '127.0.0.1', 'bindPort': http_port},
        'gameServer': {'bindAddress': '127.0.0.1', 'bindPort': game_port},
        'internalMongoServer': {'address': '127.0.0.1', 'port': mongo_port, 'filePath': './data/database.mv'}}), 'utf-8')
    option = '-Djdk.net.unixdomain.tmpdir=' + str(directory / 'tcp-fallback')
    command = [str(java), '-Xmx1536m', option, '-jar', str(artifact), '--no-handbook']
    base = f'http://127.0.0.1:{http_port}'
    snapshots, inspections = [], []
    credentials = None

    def request(route, body=None):
        req = urllib.request.Request(base + route, data=None if body is None else json.dumps(body).encode(),
                                     headers={'Content-Type': 'application/json'})
        with urllib.request.urlopen(req, timeout=5) as response:
            return json.load(response)

    def probe(mode):
        assert hashlib.sha256(artifact.read_bytes()).hexdigest() == digest
        result = subprocess.run([str(java), option, '-cp', str(artifact),
            'org.hkrpg.server.protocol.LoopbackMailProbe', str(game_port)], input=json.dumps(dict(credentials, mode=mode)),
            text=True, encoding='utf-8', capture_output=True, timeout=45, creationflags=flags)
        (directory / f'probe-{len(snapshots)}-{mode}.log').write_text(result.stdout + result.stderr, 'utf-8')
        assert result.returncode == 0, directory
        parsed = json.loads(next(row for row in reversed(result.stdout.splitlines()) if row.startswith('{')))
        assert parsed['passed']
        snapshots.append(parsed)
        return parsed

    def inspect(mode):
        result = subprocess.run([str(java), option, '-cp', os.pathsep.join([str(classes), str(artifact)]),
            'org.hkrpg.server.gameplay.MailAttachmentFixtureInspector', str(directory), str(snapshots[0]['uid']), mode,
            str(credentials['expectedWallet'])], cwd=ROOT, text=True, encoding='utf-8', capture_output=True, timeout=45,
            creationflags=flags)
        (directory / f'inspection-{len(inspections)}-{mode}.log').write_text(result.stdout + result.stderr, 'utf-8')
        assert result.returncode == 0, directory
        value = json.loads(next(row for row in reversed(result.stdout.splitlines()) if row.startswith('{')))
        assert value['passed'] and value['inspectionWrites'] == 0
        inspections.append(value)

    for iteration, mode in enumerate(['first', 'resume', 'delete']):
        with (directory / f'server-{iteration}.log').open('w', encoding='utf-8') as log:
            process = subprocess.Popen(command, cwd=directory, stdin=subprocess.PIPE, stdout=log,
                                       stderr=subprocess.STDOUT, creationflags=flags)
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
                        raise RuntimeError(f'Mail readiness timeout: {directory}')
                    time.sleep(.2)
                if credentials is None:
                    login = request('/hkrpg_cn/mdk/shield/api/login', {'account': 'cn-46-mail-claim', 'password': 'local-test'})
                    assert login['retcode'] == 0
                    account = login['data']['account']
                    combo = request('/hkrpg_cn/combo/granter/login/v2/login', {'data': json.dumps({'uid': account['uid'], 'token': account['token']})})
                    assert combo['retcode'] == 0
                    credentials = {'accountUid': account['uid'], 'comboToken': combo['data']['combo_token']}
                value = probe(mode)
                if mode == 'first':
                    credentials.update(expectedWallet=value['wallet'], expectedGear=value['equipment20000Uids'])
                    probe('resume')
            finally:
                if process.poll() is None:
                    try:
                        process.stdin.write(b'stop\n')
                        process.stdin.flush()
                        process.wait(timeout=30)
                        assert process.returncode == (1000 if os.name == 'nt' else 232), directory
                    except BaseException:
                        if process.poll() is None:
                            process.terminate()
                            process.wait(timeout=15)
                        raise
                    finally:
                        process.stdin.close()
        inspect('deleted' if mode == 'delete' else 'claimed')
    assert len(snapshots) == 4 and len(inspections) == 3
    for snapshot in snapshots:
        assert snapshot['uid'] == snapshots[0]['uid'] and snapshot['material235'] == 3
        assert snapshot['equipment20000Uids'] == credentials['expectedGear'] and snapshot['wallet'] == credentials['expectedWallet']
    assert inspections[0]['mailObjectId'] == inspections[1]['mailObjectId']
    assert inspections[0]['expected'] == inspections[1]['received'] == {'2': 1000, '235': 3, '20000': 2}
    for name, expected in hashes.items():
        assert hashlib.sha256((source / name).read_bytes()).hexdigest() == expected, name
    print(json.dumps({'passed': True, 'runtime': str(directory), 'checks': [check for snapshot in snapshots for check in snapshot['checks']],
        'snapshots': snapshots, 'inspections': inspections, 'normalStopCommandUsed': True, 'sourceResourcesModified': False,
        'sourceSHA256': hashes, 'jarSHA256': digest, 'offlineInspectorIsTestClassOnly': True, 'operatorDataModified': False,
        'fullClientUIVerified': False, 'partialDeliveryNetworkVerified': False, 'legacyReadMigrationVerified': False,
        'crashAtomicityVerified': False}, ensure_ascii=False))


if __name__ == '__main__':
    main()
