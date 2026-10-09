"""只读核对固定本机 4.6 战斗事件完整表；查询缺失编号，不代入相邻事件。"""
import argparse
import hashlib
import importlib.util
import json
import tempfile
from pathlib import Path
from types import SimpleNamespace

SOURCE = 'ExcelOutput/PlaneEvent.json'
RECORD = {'entryKey': '04e1e47c07cb2349', 'length': 1353762,
          'sha256': 'fd3bdba0459bab5136d665f9c96253ae4cab279d65c0bb5cc60e4e2c3add4c76'}
TABLE_SHA256 = '75cee5e400d5ee59b5dd86fa228124a197c6e552f1806246d366d81da602b3f5'
MAX_ROWS = 200000
# PlaneEventRow.FromBinary 94806 / 1d8f1bd0: bit order differs from metadata field order.
FIELDS = [('EventID', {'kind': 9}), ('WorldLevel', {'kind': 9}),
          ('StageID', {'kind': 9}), ('DropList', {'kind': 29, 'elementType': {'kind': 9}}),
          ('Reward', {'kind': 9}), ('IsUseMonsterDrop', {'kind': 2}),
          ('AvatarExpReward', {'kind': 9})]


def module(name, filename):
    spec = importlib.util.spec_from_file_location(name, Path(__file__).with_name(filename))
    result = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(result)
    return result


graphs = module('plane_event_primitives', 'decode-client-level-graphs.py')
design = module('plane_event_design', 'read-client-design-data.py')


def decode_table(data):
    reader = graphs.Reader(data, {})
    count = reader.integer(32, True)
    if not 0 <= count <= MAX_ROWS:
        raise ValueError('战斗事件数量超出读取边界')
    rows, identities = [], set()
    for _ in range(count):
        mask = reader.uvar()
        if mask >> 8:
            raise ValueError('战斗事件有未知字段掩码')
        # Bound each row independently; total rows and input bytes are also bounded.
        reader.nodes = 0
        row = {name: reader.read(kind) for bit, (name, kind) in enumerate(FIELDS)
               if mask & (1 << bit)}
        if mask & 128:
            # Every display-item list in this fixed original table is explicitly empty.
            if reader.integer(32, True) != 0:
                raise ValueError('尚未核对非空或 null 展示道具数组')
            row['DisplayItemList'] = []
        if row.get('EventID', 0) <= 0:
            raise ValueError('战斗事件缺少正整数身份')
        identity = (row['EventID'], row.get('WorldLevel', 0))
        if identity in identities:
            raise ValueError('战斗事件和世界等级重复')
        identities.add(identity)
        rows.append(row)
    if reader.pos != len(data):
        raise ValueError('战斗事件表有未读取的剩余字节')
    return rows


def canonical(rows):
    return json.dumps(rows, sort_keys=True, separators=(',', ':'), ensure_ascii=False).encode('utf-8')


def validate_events(events):
    if (not 1 <= len(events) <= 256 or len(set(events)) != len(events)
            or any(type(event) is not int or not 1 <= event <= 0xffffffff for event in events)):
        raise ValueError('仅接受 1..256 个唯一、正整数 uint32 战斗编号')


def inspect(args):
    validate_events(args.events)
    client = args.client_dir.resolve()
    output = design.outside_client(args.output, client)
    with tempfile.TemporaryDirectory(prefix='hkrpg-plane-events-') as directory:
        temp = Path(directory)
        source = design.extract(SimpleNamespace(client_dir=client, paths=[SOURCE], paths_file=None,
                                               extract_dir=temp / 'raw', output=temp / 'index.json'))
        record = source['records'][0]
        if record['status'] != 'read' or any(record.get(key) != value for key, value in RECORD.items()):
            raise ValueError('战斗事件表的记录键、长度或哈希与证据不匹配')
        raw = Path(record['extractedFile']).read_bytes()
        if len(raw) != RECORD['length'] or hashlib.sha256(raw).hexdigest() != RECORD['sha256']:
            raise ValueError('战斗事件表在读取后发生变化')
        rows = decode_table(raw)
        digest = hashlib.sha256(canonical(rows)).hexdigest()
        if len(rows) != 79030 or digest != TABLE_SHA256:
            raise ValueError('完整战斗事件表与已核对的原始形状不符')
    selected = [row for row in rows if row['EventID'] in args.events]
    found = {row['EventID'] for row in selected}
    report = {'clientExecutableStarted': False, 'clientModified': False, 'clientAccepted': False,
              'jsonInstalled': False, 'scriptsExecuted': False, 'completeGameplay': False,
              'clientFiles': source['files'], 'indexSHA256': source['indexSHA256'],
              'verifiedChunks': source['verifiedChunks'],
              'record': {key: value for key, value in record.items() if key != 'extractedFile'},
              'tableFullyConsumed': True, 'rowCount': len(rows), 'tableSemanticSHA256': digest,
              'requestedEventIDs': args.events, 'missingEventIDs': [e for e in args.events if e not in found],
              'events': selected}
    content = (json.dumps(report, ensure_ascii=False, indent=2) + '\n').encode('utf-8')
    if output.exists():
        if not output.is_file() or output.read_bytes() != content:
            raise FileExistsError(f'拒绝覆盖已有不同内容的报告: {output}')
    else:
        output.parent.mkdir(parents=True, exist_ok=True)
        with output.open('xb') as stream:
            stream.write(content)
    return report


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--client-dir', type=Path, required=True)
    parser.add_argument('--events', nargs='+', type=int, default=[20134220, 20134221, 20134222])
    parser.add_argument('--output', type=Path, default=Path('build/client46-plane-events.json'))
    args = parser.parse_args()
    result = inspect(args)
    print(json.dumps({'rows': result['rowCount'], 'missingEventIDs': result['missingEventIDs'],
                      'clientAccepted': False, 'jsonInstalled': False}))
