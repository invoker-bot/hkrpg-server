"""安装固定国服4.6本地账号数据补丁；默认限build副本，原安装需显式选择。"""
import argparse
import base64
import hashlib
import json
import os
import re
import struct
import tempfile
from pathlib import Path
from urllib.parse import urlsplit

ROOT = Path(__file__).resolve().parents[1]
SDK_PATH = Path('StarRail_Data/Plugins/x86_64/AccountPlatNative.dll')
SDK_SHA256 = '469ac7d38d15d076c71352d728354b5970a49537200ca3c2fdaa1d5b318a7844'
BUNDLE_PATH = Path('StarRail_Data/StreamingAssets/MiHoYoSDKRes/PC/mihoyo_sdk_res')
BUNDLE_SHA256 = '9b34bb0ce1f91f7665e72f90b358252989c094fe7183b48e563bd1a5ec6c47b9'
CONFIG_SHA256 = 'a58e7d005fe90a23759072974e8a424ff0393f702ba415d3b5257e770866b8fe'
DISPATCH_PATH = Path('StarRail_Data/StreamingAssets/ClientConfig.bytes')
DISPATCH_SHA256 = 'd1722d5a8a4a58ec46aed0532dc559bbae0441d6314637721807b4082d4737ad'
INSTALLED_CLIENT = Path(os.environ.get('LOCALAPPDATA', Path.home() / 'AppData/Local')) / 'hoyo/hkrpg/versions/current'
KEY_OFFSET, KEY_LENGTH = 0x6c9480, 268
KEY_SHA256 = '42a8cfbff43e26979202d5bc16d17602c627ac40d54dc43b48b1815e75d5daac'
HOSTS = ('passport-api.mihoyo.com', 'pre-passport-api.mihoyo.com', 'ue-account.mihoyo.com',
         'devapi-takumi.mihoyo.com', 'sandbox-sdk.mihoyo.com', 'ptsapi-takumi.mihoyo.com')
CONFIG_FIELDS = ('loginUrl', 'tokenVerify', 'comboLogin', 'loadConfig', 'comboConfig',
                 'comboConfigCenter', 'getAgreementInfos', 'comboProtocol', 'swConfigPC',
                 'swConfig', 'checkMmt')


def checksum(raw):
    return hashlib.sha256(raw).hexdigest()


def read_bundle_config(raw):
    """Read the fixed SDK UnityFS6 bundle and its complete server_pc TextAsset.

    Independently implemented binary layout, cross-checked with UnityPy's
    BundleFile.read_fs; no UnityPy code or runtime dependency is imported.
    """
    import lz4.block
    if raw[:30] != b'UnityFS\0\0\0\0\x065.x.x\x002017.4.18f1\x00':
        raise ValueError('不是已核对的SDK UnityFS6头')
    size, compressed, expanded, flags = struct.unpack_from('>QIII', raw, 30)
    if size != len(raw) or flags != 0x43 or expanded != 4169 or compressed > 10000:
        raise ValueError('SDK包大小或固定块目录不符')
    info = lz4.block.decompress(raw[50:50 + compressed], uncompressed_size=expanded)
    if info[:16] != bytes(16) or struct.unpack_from('>I', info, 16)[0] != 396:
        raise ValueError('SDK包块表不符')
    at, position = 20, 50 + compressed
    data, blocks = bytearray(), []
    for index in range(396):
        expanded_size, compressed_size, block_flags = struct.unpack_from('>IIH', info, at)
        at += 10
        if not 0 < expanded_size <= 131072 or not 0 < compressed_size <= 200000:
            raise ValueError('SDK块长度越界')
        encoded = raw[position:position + compressed_size]
        position += compressed_size
        if block_flags & 63 in (2, 3):
            decoded = lz4.block.decompress(encoded, uncompressed_size=expanded_size)
        elif block_flags & 63 == 0:
            decoded = encoded
        else:
            raise ValueError('未核对的SDK块压缩')
        if len(decoded) != expanded_size:
            raise ValueError('SDK块截断')
        blocks.append((len(data), expanded_size, block_flags, encoded))
        data.extend(decoded)
    if position != len(raw) or len(data) != 51806652:
        raise ValueError('SDK包没有完整消费')
    count = struct.unpack_from('>I', info, at)[0]
    at += 4
    nodes = []
    for index in range(count):
        offset, length, node_flags = struct.unpack_from('>qqI', info, at)
        at += 20
        end = info.index(0, at)
        name = info[at:end].decode('ascii')
        at = end + 1
        if offset < 0 or length < 1 or offset + length > len(data):
            raise ValueError('SDK包对象越界')
        nodes.append((offset, length, node_flags, name))
    if at != len(info) or count != 3 or nodes[0][:3] != (0, 38439212, 4):
        raise ValueError('SDK包对象目录不符')
    marker = b'\x09\0\0\0server_pc\0\0\0'
    matches = list(re.finditer(re.escape(marker), data[:38439212]))
    if len(matches) != 1:
        raise ValueError('SDK server_pc对象不唯一')
    start = matches[0].start() + 20
    length = struct.unpack_from('<I', data, start - 4)[0]
    if start != 5784604 or length != 473014:
        raise ValueError('SDK server_pc对象位置不符')
    config = json.loads(data[start:start + length])
    if not isinstance(config, list) or len(config) != 16:
        raise ValueError('SDK环境目录不符')
    return {'header': raw[:50], 'info': info, 'blocks': blocks, 'nodes': nodes,
            'data': bytes(data), 'configOffset': start, 'configLength': length, 'config': config}


def patch_bundle_bytes(raw, origin):
    import lz4.block
    if checksum(raw) != BUNDLE_SHA256:
        raise ValueError('SDK资源包SHA256不匹配')
    origin = server_origin(origin)
    bundle = read_bundle_config(raw)
    start, length = bundle['configOffset'], bundle['configLength']
    script = bundle['data'][start:start + length]
    if checksum(script) != CONFIG_SHA256 or bundle['config'][0]['env'] != 0:
        raise ValueError('SDK server_pc原件不符')
    text = script.decode('utf-8')
    first = text.index('{')
    env, end = json.JSONDecoder().raw_decode(text, first)
    first, end = len(text[:first].encode('utf-8')), len(text[:end].encode('utf-8'))
    selected = bytearray(script[first:end])
    changes = []
    for field in CONFIG_FIELDS:
        old = env['url'][field]
        url = urlsplit(old)
        if url.hostname not in ('hkrpg-sdk.mihoyo.com', 'hkrpg-sdk-s.mihoyo.com',
                                'sdk-static.mihoyo.com', 'gameapi-account.mihoyo.com'):
            raise ValueError('SDK配置字段不是已核对的国服账号入口')
        new = origin + url.path + ('?' + url.query if url.query else '')
        if url.fragment or len(new) > len(old):
            raise ValueError('SDK本地配置字段无法等长保存')
        matches = list(re.finditer(rb'"' + field.encode('ascii') + rb'"\s*:\s*"([^"\\]*)"', selected))
        if len(matches) != 1 or matches[0].group(1).decode('ascii') != old:
            raise ValueError('SDK配置字段不唯一或含转义')
        match = matches[0]
        # Pad JSON whitespace AFTER the closing quote; never put padding inside
        # the URL. The serialized TextAsset size and following objects stay put.
        value = new.encode('ascii') + b'"' + b' ' * (len(old) - len(new))
        before = bytes(selected[match.start(1):match.end()])
        selected[match.start(1):match.end()] = value
        changes.append({'field': field, 'offset': start + first + match.start(1),
                        'length': len(before), 'path': url.path,
                        'beforeSHA256': checksum(before), 'afterSHA256': checksum(value)})
    expected_config = json.loads(script)
    for field in CONFIG_FIELDS:
        old = urlsplit(env['url'][field])
        expected_config[0]['url'][field] = origin + old.path + ('?' + old.query if old.query else '')
    new_script = script[:first] + selected + script[end:]
    if len(new_script) != length or json.loads(new_script) != expected_config:
        raise ValueError('SDK配置出现白名单以外的变化')
    data = bytearray(bundle['data'])
    data[start:start + length] = new_script
    info = bytearray(bundle['info'])
    encoded_blocks, changed_blocks = [], []
    for index, (offset, count, flags, original) in enumerate(bundle['blocks']):
        block = bytes(data[offset:offset + count])
        encoded = original
        if block != bundle['data'][offset:offset + count]:
            if flags & 63 not in (2, 3):
                raise ValueError('修改目标不是已核对的LZ4块')
            encoded = lz4.block.compress(block, mode='high_compression', store_size=False)
            struct.pack_into('>I', info, 20 + index * 10 + 4, len(encoded))
            changed_blocks.append(index)
        encoded_blocks.append(encoded)
    encoded_info = lz4.block.compress(bytes(info), mode='high_compression', store_size=False)
    header = bytearray(bundle['header'])
    struct.pack_into('>QI', header, 30, 50 + len(encoded_info) + sum(map(len, encoded_blocks)), len(encoded_info))
    patched = bytes(header) + encoded_info + b''.join(encoded_blocks)
    verified = read_bundle_config(patched)
    if verified['data'] != bytes(data) or verified['nodes'] != bundle['nodes']:
        raise ValueError('SDK资源包重读不符')
    return patched, {'originalSHA256': BUNDLE_SHA256, 'patchedSHA256': checksum(patched),
                     'environment': 0, 'configOffset': start, 'configLength': length,
                     'changes': changes, 'changedBlocks': changed_blocks,
                     'serializedObjectLengthsPreserved': True, 'otherExpandedBytesPreserved': True}


def patch_dispatch_bytes(raw, origin):
    if checksum(raw) != DISPATCH_SHA256:
        raise ValueError('ClientConfig原件SHA256不匹配')
    origin = server_origin(origin)
    patched = raw
    for number in (1, 2):
        old = f'https://globaldp-prod-cn0{number}.bhsr.com/query_dispatch'.encode('ascii')
        new = (origin + '/query_dispatch').encode('ascii')
        before = struct.pack('>H', len(old)) + old
        if patched.count(before) != 1:
            raise ValueError('ClientConfig固定入口字段不唯一')
        patched = patched.replace(before, struct.pack('>H', len(new)) + new, 1)
    return patched


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


def atomic_write(target, content):
    descriptor, temporary = tempfile.mkstemp(prefix='cn-sdk-', suffix='.tmp', dir=target.parent)
    try:
        with os.fdopen(descriptor, 'wb') as stream:
            stream.write(content)
        os.replace(temporary, target)
    finally:
        Path(temporary).unlink(missing_ok=True)


def install(client, origin, public_key, backup_directory, output, allow_installed=False):
    client = client.resolve(strict=True)
    build = (ROOT / 'build').resolve(strict=True)
    backup_directory = backup_directory.resolve()
    output = output.resolve()
    installed = allow_installed and client == INSTALLED_CLIENT.resolve()
    if ((not installed and (not client.is_relative_to(build) or client == build))
            or not backup_directory.is_relative_to(build) or backup_directory.is_relative_to(client)
            or not output.is_relative_to(build) or output.is_relative_to(client)):
        raise ValueError('只能修改build内独立副本；已核对的原安装需显式--installed-client，备份和报告必须放在build内且客户端外')
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
    files = [(target, current, expected, backup, original)]
    bundle_report = None
    bundle_target = client / BUNDLE_PATH
    if bundle_target.exists():
        if not bundle_target.resolve(strict=True).is_relative_to(client):
            raise ValueError('SDK资源路径指向独立副本以外')
        bundle_current = bundle_target.read_bytes()
        bundle_backup = backup_directory / 'mihoyo_sdk_res.original.bundle'
        bundle_original = bundle_backup.read_bytes() if bundle_backup.is_file() else bundle_current
        bundle_expected, bundle_report = patch_bundle_bytes(bundle_original, origin)
        if bundle_current not in (bundle_original, bundle_expected):
            raise ValueError('独立副本含有未知SDK资源改动，拒绝覆盖')
        files.append((bundle_target, bundle_current, bundle_expected, bundle_backup, bundle_original))
        bundle_report.update(path=str(bundle_target), backup=str(bundle_backup))
    dispatch_report = None
    dispatch_target = client / DISPATCH_PATH
    if dispatch_target.exists():
        if not dispatch_target.resolve(strict=True).is_relative_to(client):
            raise ValueError('ClientConfig路径指向客户端以外')
        dispatch_current = dispatch_target.read_bytes()
        dispatch_backup = backup_directory / 'ClientConfig.original.bytes'
        dispatch_original = dispatch_backup.read_bytes() if dispatch_backup.is_file() else dispatch_current
        if checksum(dispatch_original) != DISPATCH_SHA256 and not installed:
            # Recognize the separately backed-up Dispatch-only acceptance copy.
            earlier = client.parent / 'ClientConfig.original.bytes'
            if earlier.is_file():
                dispatch_original = earlier.read_bytes()
        dispatch_expected = patch_dispatch_bytes(dispatch_original, origin)
        if dispatch_current not in (dispatch_original, dispatch_expected):
            raise ValueError('含有未知ClientConfig改动，拒绝覆盖')
        files.append((dispatch_target, dispatch_current, dispatch_expected, dispatch_backup, dispatch_original))
        dispatch_report = {'path': str(dispatch_target), 'backup': str(dispatch_backup),
                           'originalSHA256': DISPATCH_SHA256, 'patchedSHA256': checksum(dispatch_expected)}
    backup_directory.mkdir(parents=True, exist_ok=True)
    for path, before, after, saved, pristine in files:
        if not saved.exists():
            with saved.open('xb') as stream:
                stream.write(pristine)
    changed_files = []
    try:
        for path, before, after, saved, pristine in files:
            if before != after:
                atomic_write(path, after)
                changed_files.append((path, before))
            if path.read_bytes() != after:
                raise ValueError('安装后SDK或资源摘要不符')
    except Exception:
        for path, before in reversed(changed_files):
            atomic_write(path, before)
        raise
    report = {'client': str(client), 'sdk': str(target), 'backup': str(backup),
              'originalSHA256': SDK_SHA256, 'patchedSHA256': checksum(expected),
              'publicKeySHA256': checksum(public_literal(public_key.read_bytes())),
              'origin': server_origin(origin), 'changed': bool(changed_files),
              'upgradedFromNulPadding': current == bytes(predecessor) and current != expected,
              'urlReplacements': changes, 'loginPublicKeyOffset': KEY_OFFSET,
              'fileLengthPreserved': True, 'executableCodeModified': False,
              'nativeStringLengthsPreserved': True,
              'managedConfig': bundle_report,
              'dispatchConfig': dispatch_report,
              'installedClientTarget': installed,
              'officialClientModified': installed and bool(changed_files), 'clientLoginVerified': False}
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
    parser.add_argument('--installed-client', action='store_true',
                        help='按用户要求修改已核对的原安装；默认仅允许build内副本')
    args = parser.parse_args()
    report = install(args.client_dir, args.server, args.public_key, args.backup_dir, args.output, args.installed_client)
    print(json.dumps({'changed': report['changed'], 'urls': len(report['urlReplacements']),
                      'patchedSHA256': report['patchedSHA256'],
                      'managedConfigPatched': report['managedConfig'] is not None,
                      'clientLoginVerified': False}))
