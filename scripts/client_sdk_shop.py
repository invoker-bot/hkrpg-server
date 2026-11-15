"""准备固定国服客户端的本地商品与支付入口；安装前要求退出游戏。"""
import argparse
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import struct
import tempfile
from urllib.parse import urlsplit

import lz4.block

ROOT = Path(__file__).resolve().parents[1]


def module(name, path):
    spec = importlib.util.spec_from_file_location(name, ROOT / path)
    result = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(result)
    return result


sdk = module('cn_sdk_shop_source', 'scripts/patch-client-cn-sdk.py')
launcher = module('cn_sdk_shop_launcher', 'scripts/start-local-client.py')
SOURCE_SHA = '8e8213ece03f10349202c20bf7011375bcd46a3b6dc06bd92ccc52c55a86e225'
ORIGIN = 'http://127.0.0.1:18080'
SHOP_FIELDS = (
    'adyenDirectPayUrl', 'ageGatePreOrderValidation', 'avaliablePayWays',
    'avaliablePayWaysPC', 'checkPayPS', 'comboCreateOrder', 'createOrder',
    'detectPay', 'firstPayment', 'getCurrencyAndCountryByIP', 'googleQueryGoods',
    'listAppPayPlat', 'listGoods', 'listGoodsCDN', 'listPayPlatV2', 'listPriceTier',
    'listPriceTierSteam', 'listPriceTierV2', 'loadAllGoodsCDN', 'payCheckOrder',
    'payPlatH5URL', 'payPlatQRCodeShortURL', 'payPlatQRCodeURL',
)
HOSTS = frozenset(('sdk.mihoyo.com', 'hkrpg-sdk.mihoyo.com', 'sdk-static.mihoyo.com',
                   'goods-api.mihoyo.com', 'goods-static.mihoyo.com',
                   'api-payment.mihoyo.com', 'webstatic.mihoyo.com', 'pay.mihoyo.com'))


def sha(data):
    return hashlib.sha256(data).hexdigest()


def patch(raw):
    if sha(raw) != SOURCE_SHA:
        raise ValueError('客户端 SDK 不是已核对的本地版本')
    original = sdk.read_bundle_config(raw)
    offset, length = original['configOffset'], original['configLength']
    text = original['data'][offset:offset + length]
    first = text.index(b'{')
    decoded = text.decode('utf-8')
    _, end_chars = json.JSONDecoder().raw_decode(decoded, len(text[:first].decode('utf-8')))
    end = len(decoded[:end_chars].encode('utf-8'))
    selected = bytearray(text[first:end])
    env = original['config'][0]
    if env.get('env') != 0:
        raise ValueError('SDK 环境不符')
    for field in sdk.CONFIG_FIELDS:
        if urlsplit(env['url'][field]).netloc != '127.0.0.1:18080':
            raise ValueError('原本地登录配置未通过核对')
    changes = []
    for field in SHOP_FIELDS:
        old = env['url'][field]
        value = urlsplit(old)
        new = ORIGIN + value.path + (('?' + value.query) if value.query else '')
        if value.scheme != 'https' or value.hostname not in HOSTS or value.fragment or len(new) > len(old):
            raise ValueError('SDK 商店入口不符合已核对的地址')
        matches = list(re.finditer(rb'"' + field.encode('ascii') + rb'"\s*:\s*"([^"\\]*)"', selected))
        if len(matches) != 1 or matches[0].group(1).decode('ascii') != old:
            raise ValueError('SDK 商店入口不唯一')
        match = matches[0]
        replacement = new.encode('ascii') + b'"' + b' ' * (len(old) - len(new))
        selected[match.start(1):match.end()] = replacement
        changes.append({'field': field, 'path': value.path})
    result_text = text[:first] + selected + text[end:]
    expected = json.loads(text)
    for change in changes:
        old = urlsplit(expected[0]['url'][change['field']])
        expected[0]['url'][change['field']] = ORIGIN + old.path + (('?' + old.query) if old.query else '')
    if len(result_text) != length or json.loads(result_text) != expected:
        raise ValueError('SDK 配置出现白名单以外变化')
    data = bytearray(original['data'])
    data[offset:offset + length] = result_text
    info = bytearray(original['info'])
    packed, changed = [], []
    for index, (start, size, flags, old) in enumerate(original['blocks']):
        chunk = bytes(data[start:start + size])
        if chunk != original['data'][start:start + size]:
            if flags & 63 not in (2, 3):
                raise ValueError('SDK 目标块不支持原样更新')
            encoded = lz4.block.compress(chunk, mode='high_compression', store_size=False)
            struct.pack_into('>I', info, 20 + index * 10 + 4, len(encoded))
            changed.append(index)
        else:
            encoded = old
        packed.append(encoded)
    packed_info = lz4.block.compress(bytes(info), mode='high_compression', store_size=False)
    header = bytearray(original['header'])
    struct.pack_into('>QI', header, 30, 50 + len(packed_info) + sum(map(len, packed)), len(packed_info))
    result = bytes(header) + packed_info + b''.join(packed)
    verified = sdk.read_bundle_config(result)
    if verified['data'] != bytes(data) or verified['nodes'] != original['nodes']:
        raise ValueError('SDK 分块重读校验失败')
    return result, {'sourceSHA256': SOURCE_SHA, 'resultSHA256': sha(result),
                    'changes': changes, 'changedBlocks': changed}


def atomic(path, data, expected):
    if sha(path.read_bytes()) != expected:
        raise ValueError('安装目标在操作期间变化')
    descriptor, name = tempfile.mkstemp(prefix=path.name + '.pending-', dir=path.parent)
    try:
        with os.fdopen(descriptor, 'wb') as stream:
            stream.write(data)
            stream.flush()
            os.fsync(stream.fileno())
        if sha(path.read_bytes()) != expected:
            raise ValueError('安装目标在替换前变化')
        os.replace(name, path)
    finally:
        if os.path.exists(name):
            os.unlink(name)


def validate(manifest):
    data, client, _ = launcher.load_manifest(manifest)
    launcher.require_stopped()
    for relative, expected in {**data['clientFiles'], **data.get('clientLuaFiles', {})}.items():
        if launcher.sha(launcher.checked_path(client, relative)) != expected:
            raise ValueError('客户端文件或已有补丁已变化')
    return data, client


def prepare(manifest, output):
    data, client = validate(manifest)
    bundle = launcher.checked_path(client, sdk.BUNDLE_PATH.as_posix())
    updated, report = patch(bundle.read_bytes())
    output.mkdir(parents=True, exist_ok=False)
    (output / 'sdk-shop.bundle').write_bytes(updated)
    (output / 'prepared.json').write_text(json.dumps(report, indent=2) + '\n', encoding='utf-8')
    return report


def install(manifest, prepared, backup):
    data, client = validate(manifest)
    bundle = launcher.checked_path(client, sdk.BUNDLE_PATH.as_posix())
    manifest_before = manifest.read_bytes()
    report = json.loads((prepared / 'prepared.json').read_text(encoding='utf-8'))
    prepared_bundle = (prepared / 'sdk-shop.bundle').read_bytes()
    if sha(prepared_bundle) != report.get('resultSHA256') or report.get('sourceSHA256') != SOURCE_SHA:
        raise ValueError('待安装 SDK 摘要不符')
    current = bundle.read_bytes()
    if current == prepared_bundle:
        return dict(report, clientModified=False, alreadyInstalled=True)
    candidate, expected = patch(current)
    if report != expected or prepared_bundle != candidate:
        raise ValueError('待安装 SDK 与重新核对结果不符')
    if backup.resolve().is_relative_to(client.resolve()):
        raise ValueError('备份目录不得位于客户端内')
    backup.mkdir(parents=True, exist_ok=False)
    (backup / 'sdk.bundle').write_bytes(bundle.read_bytes())
    (backup / 'patch-manifest.json').write_bytes(manifest_before)
    updated = json.loads(manifest_before.decode('utf-8-sig'))
    updated['clientFiles'][sdk.BUNDLE_PATH.as_posix()] = sha(candidate)
    after = (json.dumps(updated, ensure_ascii=False, indent=2) + '\n').encode('utf-8')
    launcher.require_stopped()
    validate(manifest)
    atomic(bundle, candidate, SOURCE_SHA)
    try:
        launcher.require_stopped()
        atomic(manifest, after, sha(manifest_before))
        validate(manifest)
    except Exception:
        if sha(bundle.read_bytes()) == sha(candidate):
            atomic(bundle, (backup / 'sdk.bundle').read_bytes(), sha(candidate))
        if sha(manifest.read_bytes()) == sha(after):
            atomic(manifest, manifest_before, sha(after))
        raise
    return dict(report, clientModified=True, backup=str(backup.resolve()))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--manifest', type=Path, default=ROOT / '.local/client/patch-manifest.json')
    parser.add_argument('--prepare-dir', type=Path, required=True)
    parser.add_argument('--apply', action='store_true')
    parser.add_argument('--backup-dir', type=Path)
    args = parser.parse_args()
    manifest = args.manifest.resolve()
    report = prepare(manifest, args.prepare_dir) if not args.prepare_dir.exists() else json.loads(
        (args.prepare_dir / 'prepared.json').read_text(encoding='utf-8'))
    if args.apply:
        if args.backup_dir is None:
            raise ValueError('安装前必须指定新备份目录')
        report = install(manifest, args.prepare_dir, args.backup_dir)
    print(json.dumps(report, ensure_ascii=True))


if __name__ == '__main__':
    main()
