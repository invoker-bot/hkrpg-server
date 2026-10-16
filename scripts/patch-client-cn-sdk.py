"""仅对 build 内独立国服4.6副本安装可恢复的本地账号SDK数据补丁。"""
import argparse
import base64
import hashlib
import json
import os
import re
import tempfile
from pathlib import Path
from urllib.parse import urlsplit

ROOT = Path(__file__).resolve().parents[1]
SDK_PATH = Path('StarRail_Data/Plugins/x86_64/AccountPlatNative.dll')
SDK_SHA256 = '469ac7d38d15d076c71352d728354b5970a49537200ca3c2fdaa1d5b318a7844'
KEY_OFFSET, KEY_LENGTH = 0x6c9480, 268
KEY_SHA256 = '42a8cfbff43e26979202d5bc16d17602c627ac40d54dc43b48b1815e75d5daac'
HOSTS = ('passport-api.mihoyo.com', 'pre-passport-api.mihoyo.com', 'ue-account.mihoyo.com',
         'devapi-takumi.mihoyo.com', 'sandbox-sdk.mihoyo.com', 'ptsapi-takumi.mihoyo.com')


def checksum(raw):
    return hashlib.sha256(raw).hexdigest()


def server_origin(value):
    url = urlsplit(value)
    if (url.scheme != 'http' or url.hostname != '127.0.0.1' or not url.port
            or url.username is not None or url.password is not None
            or url.path not in ('', '/') or url.query or url.fragment):
        raise ValueError('只接受显式端口的本机 HTTP 入口')
    return f'http://127.0.0.1:{url.port}'


def public_literal(raw):
    from cryptography.hazmat.primitives import serialization
    from cryptography.hazmat.primitives.asymmetric import rsa
    key = serialization.load_pem_public_key(raw)
    if not isinstance(key, rsa.RSAPublicKey) or key.key_size != 1024:
        raise ValueError('此固定SDK只接受对应1024位RSA公钥')
    der = key.public_bytes(serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo)
    result = b'-----BEGIN PUBLIC KEY-----\n' + base64.b64encode(der) + b'\n-----END PUBLIC KEY-----'
    if len(result) != KEY_LENGTH:
        raise ValueError('公钥固定槽长度不符')
    return result


def patch_bytes(raw, origin, public_key):
    if checksum(raw) != SDK_SHA256:
        raise ValueError('SDK SHA256不匹配，拒绝猜测其他版本')
    origin = server_origin(origin)
    key = public_literal(public_key)
    if checksum(raw[KEY_OFFSET:KEY_OFFSET + KEY_LENGTH]) != KEY_SHA256:
        raise ValueError('SDK原登录公钥槽不符')
    patched = bytearray(raw)
    changes = []
    counts = dict.fromkeys(HOSTS, 0)
    # Native constructors pass each original URL length explicitly (e.g. RVA
    # 106477 -> 137f20), so padding with NUL would enter the std::string value.
    # Fill the authority with an inert username; preserve path, query and length.
    for match in re.finditer(rb'https?://[!-~]+\x00', raw):
        old = match.group()[:-1]
        url = urlsplit(old.decode('ascii'))
        if url.netloc not in counts:
            continue
        suffix = url.path + ('?' + url.query if url.query else '')
        new = (origin + suffix).encode('ascii')
        padding = len(old) - len(new)
        if padding < 0 or padding == 1:
            raise ValueError('本地URL长于固定SDK字符串槽')
        if padding:
            new = (origin.replace('http://', 'http://' + 'p' * (padding - 1) + '@', 1) + suffix).encode('ascii')
        replacement = new + b'\0'
        patched[match.start():match.end()] = replacement
        counts[url.netloc] += 1
        changes.append({'offset': match.start(), 'length': len(match.group()),
                        'originalHost': url.netloc, 'path': url.path,
                        'beforeSHA256': checksum(match.group()), 'afterSHA256': checksum(replacement)})
    if any(count != 20 for count in counts.values()):
        raise ValueError('SDK固定六组账号URL数量不符')
    patched[KEY_OFFSET:KEY_OFFSET + KEY_LENGTH] = key
    if len(patched) != len(raw):
        raise ValueError('SDK文件长度变化')
    import pefile
    pe = pefile.PE(data=raw, fast_load=True)
    try:
        for offset, length in [(row['offset'], row['length']) for row in changes] + [(KEY_OFFSET, KEY_LENGTH)]:
            section = next((section for section in pe.sections
                            if section.PointerToRawData <= offset
                            and offset + length <= section.PointerToRawData + section.SizeOfRawData), None)
            if section is None or section.Characteristics & 0x20000000:
                raise ValueError('补丁范围不是已核对的非执行数据段')
    finally:
        pe.close()
    return bytes(patched), changes


def install(client, origin, public_key, backup_directory, output):
    client = client.resolve(strict=True)
    build = (ROOT / 'build').resolve(strict=True)
    backup_directory = backup_directory.resolve()
    output = output.resolve()
    if (not client.is_relative_to(build) or client == build
            or not backup_directory.is_relative_to(build) or backup_directory.is_relative_to(client)
            or not output.is_relative_to(build) or output.is_relative_to(client)):
        raise ValueError('只能修改build内独立副本，备份和报告必须放在副本外')
    if output.exists():
        raise FileExistsError('拒绝覆盖补丁证据')
    target = client / SDK_PATH
    if not target.resolve(strict=True).is_relative_to(client):
        raise ValueError('SDK路径指向独立副本以外')
    current = target.read_bytes()
    backup = backup_directory / 'AccountPlatNative.original.dll'
    original = backup.read_bytes() if backup.is_file() else current
    expected, changes = patch_bytes(original, origin, public_key.read_bytes())
    # Recognize only the exact predecessor produced with this same key/origin.
    # This lets an installed NUL-padded patch be corrected without accepting
    # arbitrary local edits or overwriting an operator's different key.
    predecessor = bytearray(expected)
    for row in changes:
        old = original[row['offset']:row['offset'] + row['length'] - 1]
        url = urlsplit(old.decode('ascii'))
        value = (server_origin(origin) + url.path + ('?' + url.query if url.query else '')).encode('ascii')
        predecessor[row['offset']:row['offset'] + row['length']] = value + bytes(row['length'] - len(value))
    if current not in (original, expected, bytes(predecessor)):
        raise ValueError('独立副本含有未知SDK改动，拒绝覆盖')
    backup_directory.mkdir(parents=True, exist_ok=True)
    if not backup.exists():
        with backup.open('xb') as stream:
            stream.write(original)
    changed = current != expected
    if changed:
        descriptor, temporary = tempfile.mkstemp(prefix='AccountPlatNative-', suffix='.tmp', dir=target.parent)
        try:
            with os.fdopen(descriptor, 'wb') as stream:
                stream.write(expected)
            # A loaded Windows DLL cannot be replaced; the original stays intact on failure.
            os.replace(temporary, target)
        finally:
            Path(temporary).unlink(missing_ok=True)
    if checksum(target.read_bytes()) != checksum(expected):
        raise ValueError('安装后SDK摘要不符')
    report = {'client': str(client), 'sdk': str(target), 'backup': str(backup),
              'originalSHA256': SDK_SHA256, 'patchedSHA256': checksum(expected),
              'publicKeySHA256': checksum(public_literal(public_key.read_bytes())),
              'origin': server_origin(origin), 'changed': changed,
              'upgradedFromNulPadding': current == bytes(predecessor) and current != expected,
              'urlReplacements': changes, 'loginPublicKeyOffset': KEY_OFFSET,
              'fileLengthPreserved': True, 'executableCodeModified': False,
              'nativeStringLengthsPreserved': True,
              'officialClientModified': False, 'clientLoginVerified': False}
    output.parent.mkdir(parents=True, exist_ok=True)
    with output.open('x', encoding='utf-8') as stream:
        json.dump(report, stream, ensure_ascii=False, indent=2)
        stream.write('\n')
    return report


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--client-dir', type=Path, required=True)
    parser.add_argument('--server', default='http://127.0.0.1:18080')
    parser.add_argument('--public-key', type=Path, required=True)
    parser.add_argument('--backup-dir', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    report = install(args.client_dir, args.server, args.public_key, args.backup_dir, args.output)
    print(json.dumps({'changed': report['changed'], 'urls': len(report['urlReplacements']),
                      'patchedSHA256': report['patchedSHA256'], 'clientLoginVerified': False}))
