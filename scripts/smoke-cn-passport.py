"""隔离HTTP/H2验证国服原生账号SDK链路；不启动或代填游戏登录界面。"""
import argparse
import base64
import hashlib
import json
import os
import socket
import subprocess
import tempfile
import time
import urllib.error
import urllib.request
from pathlib import Path

from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric import padding, rsa

ROOT = Path(__file__).resolve().parents[1]


def checksum(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def port():
    with socket.socket() as stream:
        stream.bind(('127.0.0.1', 0))
        return stream.getsockname()[1]


def run(jar, output):
    jar, output = jar.resolve(strict=True), output.resolve()
    if not output.is_relative_to(ROOT / 'build') or output.exists():
        raise ValueError('报告必须是build内尚不存在的文件')
    expected = checksum(jar)
    (ROOT / 'build/smoke').mkdir(parents=True, exist_ok=True)
    directory = Path(tempfile.mkdtemp(prefix='cn-passport-', dir=ROOT / 'build/smoke'))
    key = rsa.generate_private_key(public_exponent=65537, key_size=1024)
    private = directory / 'passport-private.der'
    private.write_bytes(key.private_bytes(serialization.Encoding.DER, serialization.PrivateFormat.PKCS8, serialization.NoEncryption()))
    http, mongo = port(), port()
    while mongo == http:
        mongo = port()
    config = directory / 'config.json'
    config.write_text(json.dumps({'protocol': {'wireVersion': '4.6.0'},
        'httpServer': {'bindAddress': '127.0.0.1', 'bindPort': http},
        'internalMongoServer': {'address': '127.0.0.1', 'port': mongo, 'filePath': './data/database.mv'},
        'loginOptions': {'cnPassportPrivateKeyPath': str(private)}}), encoding='utf-8')
    config_sha = checksum(config)
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    base = f'http://127.0.0.1:{http}'

    def request(path, body=None):
        data = json.dumps(body).encode() if body is not None else None
        message = urllib.request.Request(base + path, data, {'Content-Type': 'application/json'})
        with opener.open(message, timeout=5) as response:
            assert response.status == 200
            return json.load(response)

    def encrypted(text):
        return base64.b64encode(key.public_key().encrypt(text.encode(), padding.PKCS1v15())).decode()

    java = next((ROOT / '.tools').glob('jdk-21*/bin/java.exe'))
    accounts, mdk_accounts, checks, stops = [], [], [], 0
    for iteration in range(2):
        assert checksum(jar) == expected and checksum(config) == config_sha
        with (directory / f'server-{iteration}.log').open('x', encoding='utf-8') as log:
            process = subprocess.Popen([str(java), '-Dfile.encoding=UTF-8',
                '-Dstdout.encoding=UTF-8', '-Dstderr.encoding=UTF-8',
                '-Djdk.net.unixdomain.tmpdir=' + str(directory / 'tcp-fallback'),
                '-Dio.netty.eventLoopThreads=2', '-jar', str(jar), '--mode=dispatch'],
                cwd=directory, stdin=subprocess.PIPE, stdout=log, stderr=subprocess.STDOUT,
                creationflags=subprocess.CREATE_NO_WINDOW if os.name == 'nt' else 0)
            try:
                deadline = time.monotonic() + 60
                while True:
                    assert process.poll() is None, '测试服启动提前退出: ' + str(directory)
                    try:
                        if request('/health/ready')['ready']:
                            break
                    except (OSError, urllib.error.URLError):
                        pass
                    if time.monotonic() > deadline:
                        raise TimeoutError('HTTP readiness: ' + str(directory))
                    time.sleep(.2)
                checks.append(f'{iteration}:ready')
                if not accounts:
                    for name in ('cn-smoke-one@example.com', 'cn-smoke-two@example.com'):
                        result = request('/account/ma-cn-passport/app/loginByPassword', {
                            'account': encrypted(name), 'password': encrypted('local-test')})
                        assert result['retcode'] == 0
                        accounts.append(result['data'])
                        assert accounts[-1]['user_info']['account_name'] == name
                        checks.append(f'{iteration}:rsa-account-{len(accounts)}')
                first, second = accounts
                uid = first['user_info']['mid']
                assert uid != second['user_info']['mid']
                checks.append(f'{iteration}:distinct-identities')
                for account in accounts:
                    result = request('/account/ma-cn-session/app/verify', {
                        'mid': account['user_info']['mid'], 'token': account['token']})
                    assert result['retcode'] == 0
                    assert result['data']['user_info'] == account['user_info']
                checks.append(f'{iteration}:original-tokens-and-identities-restored')
                wrong = request('/account/ma-cn-session/app/verify', {
                    'mid': second['user_info']['mid'], 'token': first['token']})
                assert wrong['retcode'] == -201
                checks.append(f'{iteration}:cross-account-rejected')
                exchanged = request('/account/ma-cn-session/app/exchange', {
                    'mid': uid, 'src_token': first['token'], 'dst_token_type': 4})
                assert exchanged['retcode'] == 0 and exchanged['data']['token']['token_type'] == 4
                checks.append(f'{iteration}:token-exchange')
                combo = request('/hkrpg_cn/combo/granter/login/v2/login', {'data': json.dumps({
                    'uid': uid, 'token': exchanged['data']['token']['token']})})
                assert combo['retcode'] == 0 and combo['data']['combo_token']
                checks.append(f'{iteration}:combo-token')
                if not mdk_accounts:
                    for name in ('mdk-smoke-one', 'mdk-smoke-two'):
                        result = request('/hkrpg_cn/mdk/shield/api/login', {'account': name, 'password': 'first-password'})
                        assert result['retcode'] == 0
                        first_login = result['data']['account']
                        changed = request('/hkrpg_cn/mdk/shield/api/login', {'account': name, 'password': 'different-password'})
                        assert changed['retcode'] == 0
                        changed = changed['data']['account']
                        assert first_login['uid'] == changed['uid']
                        mdk_accounts.append(changed)
                        checks.append(f'{iteration}:mdk-create-and-ignore-password-{len(mdk_accounts)}')
                assert mdk_accounts[0]['uid'] != mdk_accounts[1]['uid']
                checks.append(f'{iteration}:mdk-distinct-identities')
                for account in mdk_accounts:
                    verified = request('/hkrpg_cn/mdk/shield/api/verify', {'uid': account['uid'], 'token': account['token']})
                    assert verified['retcode'] == 0
                    assert verified['data']['account']['uid'] == account['uid']
                checks.append(f'{iteration}:mdk-original-identities-and-tokens-restored')
                rejected = request('/hkrpg_cn/mdk/shield/api/verify', {
                    'uid': mdk_accounts[1]['uid'], 'token': mdk_accounts[0]['token']})
                assert rejected['retcode'] != 0
                checks.append(f'{iteration}:mdk-cross-account-rejected')
                combo = request('/hkrpg_cn/combo/granter/login/v2/login', {'data': json.dumps({
                    'uid': mdk_accounts[0]['uid'], 'token': mdk_accounts[0]['token']})})
                assert combo['retcode'] == 0 and combo['data']['combo_token']
                checks.append(f'{iteration}:mdk-combo')
            finally:
                if process.poll() is None:
                    try:
                        process.stdin.write(b'stop\n')
                        process.stdin.flush()
                        process.wait(timeout=30)
                        assert process.returncode == (1000 if os.name == 'nt' else 1000 % 256)
                        assert 'Stopping server' in (directory / f'server-{iteration}.log').read_text(encoding='utf-8')
                        stops += 1
                    finally:
                        if process.poll() is None:
                            process.terminate()
                            process.wait(timeout=10)
                        process.stdin.close()
    assert stops == 2 and checksum(jar) == expected and checksum(config) == config_sha
    report = {'passed': True, 'runtimeSHA256': expected, 'runtimeDirectory': str(directory),
              'configSHA256': config_sha, 'checks': checks, 'normalStops': stops,
              'sameAccountH2Restart': True, 'originalTokensVerifiedAfterRestart': True,
              'defaultAutoCreationAndIgnoredPasswords': True,
              'accountNamesAndTokensOmitted': True, 'clientLoginVerified': False}
    output.parent.mkdir(parents=True, exist_ok=True)
    with output.open('x', encoding='utf-8') as stream:
        json.dump(report, stream, ensure_ascii=False, indent=2)
        stream.write('\n')
    return report


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--jar', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    proof = run(args.jar, args.output)
    print(json.dumps({'passed': proof['passed'], 'checks': len(proof['checks']),
                      'normalStops': proof['normalStops'], 'clientLoginVerified': False}))
