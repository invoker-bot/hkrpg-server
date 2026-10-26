"""固定 4.6 空间站姬子的剧情试用界面分类兼容；默认仅生成可检查的副本。

只改 1011003/0 的 Type，保留数值和装备。安装须退出游戏；保留原数据块，
备份索引后原子更新。未核对的版本、内容或部分安装全部拒绝。
"""
import argparse
from datetime import datetime, timezone
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import struct
import tempfile

ROOT = Path(__file__).resolve().parents[1]
PROFILE = ROOT / 'scripts/data/client46-station-trial-ui.json'
PROFILE_SHA256 = '55f53704e230bae61dbb77cc7b1ec28cba1360e52c8731552e9c10d0ae5cdff9'
DATA_DIR = Path('StarRail_Data/Persistent/DesignData/Windows')
BACKUPS = ROOT / '.local/client-patches'


def module(name, filename):
    spec = importlib.util.spec_from_file_location(name, Path(__file__).with_name(filename))
    result = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(result)
    return result


graphs = module('story_ui_primitives', 'decode-client-level-graphs.py')


def sha(raw):
    return hashlib.sha256(raw).hexdigest()


def profile():
    raw = PROFILE.read_bytes().replace(b'\r\n', b'\n')
    if sha(raw) != PROFILE_SHA256:
        raise ValueError('剧情试用兼容配置 SHA256 不匹配')
    value = json.loads(raw)
    if value['schemaVersion'] != 1 or value['compatibility'] != 'station-himeko-menu-v1':
        raise ValueError('未核对的剧情试用兼容配置')
    return value


def decode_record(raw):
    p = profile()
    if len(raw) != p['recordSize'] or sha(raw) not in (
            p['originalRecordSHA256'], p['patchedRecordSHA256']):
        raise ValueError('试用表的长度或 SHA256 不匹配')
    reader = graphs.Reader(raw, {})
    count = reader.integer(32, True)
    if count != p['rowCount']:
        raise ValueError('试用表行数不匹配')
    rows, targets, identities = [], [], set()
    for _ in range(count):
        reader.nodes = 0
        mask = reader.uvar()
        if mask >> len(p['fields']):
            raise ValueError('试用表有未知字段掩码')
        row = {}
        for bit, field in enumerate(p['fields']):
            if not mask & (1 << bit):
                continue
            begin = reader.pos
            kind = field['type']
            if kind['kind'] == 17:
                value = reader.integer(32, True)
            elif kind['kind'] == 29 and kind['elementType']['kind'] == 18:
                length = reader.integer(32, True)
                if length not in (-1, 0):
                    raise ValueError('试用表有未核对的非空覆盖属性')
                value = None if length < 0 else []
            else:
                value = reader.read(kind)
            row[field['name']] = value
            if row.get('SpecialAvatarID') == p['specialAvatarID'] and field['name'] == 'Type':
                targets.append((len(rows), begin, reader.pos - begin))
        identity = (row.get('SpecialAvatarID'), row.get('WorldLevel', 0))
        if not identity[0] or identity in identities:
            raise ValueError('试用表身份重复或缺失')
        identities.add(identity)
        rows.append(row)
    if reader.pos != len(raw) or len(targets) != 1:
        raise ValueError('试用表未完整读取或目标行不唯一')
    index, offset, size = targets[0]
    target = rows[index]
    for name, expected in [('WorldLevel', 0), ('AvatarID', p['avatarID']),
                           ('PlayerID', p['avatarID']), ('Level', p['level']),
                           ('EquipmentID', p['equipmentID']), ('EquipmentLevel', p['equipmentLevel']),
                           ('EquipmentRank', p['equipmentRank'])]:
        if target.get(name, 0) != expected:
            raise ValueError('目标角色预设变化: ' + name)
    if (offset, size) != (p['typeOffset'], 1) or target['Type'] not in (1, 4):
        raise ValueError('界面分类字段不匹配')
    return rows, index, offset


def patch_record(raw):
    rows, index, offset = decode_record(raw)
    if rows[index]['Type'] == 4:
        return raw
    p = profile()
    if raw[offset] != p['oldByte']:
        raise ValueError('剧情试用原分类编码不匹配')
    changed = raw[:offset] + bytes([p['newByte']]) + raw[offset + 1:]
    if sha(changed) != p['patchedRecordSHA256']:
        raise ValueError('剧情试用修复记录 SHA256 不匹配')
    verified, target, _ = decode_record(changed)
    if target != index or verified[index]['Type'] != 4:
        raise ValueError('剧情试用新分类未生效')
    verified[index]['Type'] = rows[index]['Type']
    if verified != rows:
        raise ValueError('界面分类以外的角色数据发生变化')
    return changed


def inspect_index(raw):
    p = profile()
    current = sha(raw)
    if current not in (p['originalIndexSHA256'], p['patchedIndexSHA256']):
        raise ValueError('设计数据索引 SHA256 不匹配')
    if struct.unpack_from('>IIII', raw) != (255, 4, p['indexChunkCount'], p['indexEntryCount']):
        raise ValueError('设计数据索引头不匹配')
    position, count, found = 16, 0, []
    for chunk in range(p['indexChunkCount']):
        start = position
        base, size, total = struct.unpack_from('>III', raw, start + 24)
        position += 36
        for _ in range(total):
            key = raw[position:position + 8].hex()
            length, offset = struct.unpack_from('>II', raw, position + 8)
            if base + offset + length > size:
                raise ValueError('设计数据记录越界')
            if key == p['recordKey']:
                found.append((chunk, size, base + offset, length, start + 8))
            position += 16
        tag, length = raw[position:position + 2]
        if tag or length >= 16 or raw[position + 2 + length] != 128:
            raise ValueError('设计数据语言标记不匹配')
        position += 3 + length
        count += total
    expected = (p['chunkIndex'], p['chunkSize'], p['recordOffset'],
                p['recordSize'], p['indexMD5Offset'])
    if position != len(raw) or count != p['indexEntryCount'] or found != [expected]:
        raise ValueError('设计数据索引未完整核对')
    digest = p['patchedChunkMD5'] if current == p['patchedIndexSHA256'] else p['originalChunkMD5']
    if raw[p['indexMD5Offset']:p['indexMD5Offset'] + 16].hex() != digest:
        raise ValueError('设计数据块引用不匹配')
    return current


def make_plan(client):
    client = client.resolve()
    p = profile()
    for name, expected in p['clientFiles'].items():
        with (client / name).open('rb') as stream:
            actual = hashlib.file_digest(stream, 'sha256').hexdigest()
        if actual != expected:
            raise ValueError('客户端版本未核对: ' + name)
    directory = (client / DATA_DIR).resolve()
    if not directory.is_relative_to(client):
        raise ValueError('设计数据目录超出指定客户端')
    index_path = directory / p['indexFilename']
    index = index_path.read_bytes()
    current_sha = inspect_index(index)
    source_path = directory / (p['originalChunkMD5'] + '.bytes')
    source = source_path.read_bytes()
    if len(source) != p['chunkSize'] or hashlib.md5(source).hexdigest() != p['originalChunkMD5']:
        raise ValueError('原始数据块内容不匹配')
    begin = p['recordOffset']
    raw = source[begin:begin + p['recordSize']]
    repaired = patch_record(raw)
    if sha(repaired) != p['patchedRecordSHA256']:
        raise ValueError('修复后的试用表 SHA256 不匹配')
    changed = bytearray(source)
    changed[begin:begin + len(raw)] = repaired
    new_chunk = bytes(changed)
    if hashlib.md5(new_chunk).hexdigest() != p['patchedChunkMD5']:
        raise ValueError('修复后的数据块 MD5 不匹配')
    new_index = bytearray(index)
    start = p['indexMD5Offset']
    new_index[start:start + 16] = bytes.fromhex(p['patchedChunkMD5'])
    new_index = bytes(new_index)
    if inspect_index(new_index) != p['patchedIndexSHA256']:
        raise ValueError('修复后的设计数据索引不匹配')
    target = directory / (p['patchedChunkMD5'] + '.bytes')
    installed = current_sha == p['patchedIndexSHA256']
    if target.exists() and target.read_bytes() != new_chunk:
        raise ValueError('目标数据块已存在不同内容')
    if installed and not target.is_file():
        raise ValueError('已有兼容索引但数据块缺失')
    return dict(client=client, directory=directory, indexPath=index_path, originalIndex=index,
                originalIndexSHA256=current_sha, sourcePath=source_path, targetPath=target,
                index=new_index, chunk=new_chunk, installed=installed)


def require_stopped():
    launcher = module('story_ui_launcher', 'start-local-client.py')
    if launcher.client_running():
        raise RuntimeError('客户端正在运行；请正常退出后安装剧情试用界面修复')


def atomic_replace(path, raw, expected):
    if sha(path.read_bytes()) != expected:
        raise ValueError('安装前索引已变化，拒绝覆盖')
    temporary = None
    try:
        with tempfile.NamedTemporaryFile(prefix='.hkrpg-trial-ui-', dir=path.parent, delete=False) as stream:
            temporary = Path(stream.name)
            stream.write(raw)
            stream.flush()
            os.fsync(stream.fileno())
        if sha(path.read_bytes()) != expected:
            raise ValueError('安装期间索引已变化，拒绝覆盖')
        temporary.replace(path)
        temporary = None
    finally:
        if temporary is not None:
            temporary.unlink(missing_ok=True)


def install(client):
    require_stopped()
    plan = make_plan(client)
    if plan['installed']:
        return dict(installed=True, changed=False, clientModified=False)
    BACKUPS.mkdir(parents=True, exist_ok=True)
    backup = Path(tempfile.mkdtemp(prefix='station-trial-ui-', dir=BACKUPS))
    (backup / profile()['indexFilename']).write_bytes(plan['originalIndex'])
    proof = dict(client=str(plan['client']), originalIndexSHA256=plan['originalIndexSHA256'],
                 patchedIndexSHA256=sha(plan['index']), originalChunkPreserved=True,
                 specialAvatarID=profile()['specialAvatarID'], typeBefore=1, typeAfter=4,
                 numericalPresetChanged=False, savedPlayerDataChanged=False,
                 createdAt=datetime.now(timezone.utc).isoformat())
    (backup / 'manifest.json').write_text(json.dumps(proof, indent=2), encoding='utf-8')
    # Keep the original data block. The index is the only replaced file; switching its digest
    # activates a new block whose sole changed byte is the target's UI classification.
    if not plan['targetPath'].exists():
        with plan['targetPath'].open('xb') as stream:
            stream.write(plan['chunk'])
            stream.flush()
            os.fsync(stream.fileno())
    require_stopped()
    if plan['targetPath'].read_bytes() != plan['chunk']:
        raise ValueError('安装期间数据块已变化，索引未激活')
    atomic_replace(plan['indexPath'], plan['index'], plan['originalIndexSHA256'])
    try:
        if not make_plan(client)['installed']:
            raise ValueError('安装后核对失败')
    except Exception:
        # Revert only our known index. A concurrent different index is never overwritten.
        if sha(plan['indexPath'].read_bytes()) == sha(plan['index']):
            atomic_replace(plan['indexPath'], plan['originalIndex'], sha(plan['index']))
        raise
    return dict(installed=True, changed=True, clientModified=True, backup=str(backup), **proof)


def restore(client):
    require_stopped()
    plan = make_plan(client)
    if not plan['installed']:
        return dict(installed=False, changed=False, clientModified=False)
    p = profile()
    original = bytearray(plan['index'])
    original[p['indexMD5Offset']:p['indexMD5Offset'] + 16] = bytes.fromhex(p['originalChunkMD5'])
    original = bytes(original)
    if inspect_index(original) != p['originalIndexSHA256']:
        raise ValueError('原索引恢复核对失败')
    atomic_replace(plan['indexPath'], original, p['patchedIndexSHA256'])
    return dict(installed=False, changed=True, clientModified=True, originalChunkPreserved=True,
                savedPlayerDataChanged=False, unusedPatchedBlockPreserved=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--client-dir', type=Path, required=True)
    parser.add_argument('--output-dir', type=Path, required=True)
    action = parser.add_mutually_exclusive_group()
    action.add_argument('--install', action='store_true')
    action.add_argument('--restore', action='store_true')
    args = parser.parse_args()
    output, client = args.output_dir.resolve(), args.client_dir.resolve()
    if output == client or client in output.parents:
        parser.error('验证产物必须保存到客户端目录外')
    output.mkdir(parents=True, exist_ok=True)
    plan = make_plan(client)
    (output / ('candidate-' + profile()['indexFilename'])).write_bytes(plan['index'])
    (output / plan['targetPath'].name).write_bytes(plan['chunk'])
    result = install(client) if args.install else restore(client) if args.restore else dict(
        installed=plan['installed'], changed=False, clientModified=False, planOnly=True)
    result.update(indexSHA256=sha(plan['indexPath'].read_bytes()) if args.install or args.restore else sha(plan['index']),
                  dataBlockMD5=profile()['originalChunkMD5'] if args.restore else hashlib.md5(plan['chunk']).hexdigest(),
                  clientAccepted=False)
    (output / 'result.json').write_text(json.dumps(result, indent=2), encoding='utf-8')
    print(json.dumps(result, ensure_ascii=False))


if __name__ == '__main__':
    main()
