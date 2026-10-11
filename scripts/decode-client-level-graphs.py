"""只读复核固定 4.6 客户端的七份小小哈努关卡脚本。

输出保留原始字段掩码和定点整数，不补默认值，不执行脚本，不安装运行时资源。
未知任务、类型、表达式或剩余字节均停止；完整读取不等于客户端玩法验收。
"""
import argparse
import hashlib
import importlib.util
import json
import math
import struct
import tempfile
from pathlib import Path
from types import SimpleNamespace

PROFILE = Path(__file__).with_name('data') / 'client46-level-graphs.json'
PROFILE_SHA256 = 'd8c56646e3567ebc0e1d688ec8b0845e0ed46f6556d1382effa70df9a7f8079b'
MAX_DEPTH, MAX_ARRAY, MAX_NODES = 32, 4096, 100000


def load_profile():
    raw = PROFILE.read_bytes().replace(b'\r\n', b'\n')
    if hashlib.sha256(raw).hexdigest() != PROFILE_SHA256:
        raise ValueError('关卡脚本的固定静态字段证据已变化，需要重新核对')
    return json.loads(raw)


class Reader:
    def __init__(self, data, profile=None):
        if not 0 < len(data) <= 4 * 1024 * 1024:
            raise ValueError('关卡记录大小超出读取边界')
        self.data, self.pos = data, 0
        self.profile = profile if profile is not None else load_profile()
        self.depth, self.nodes = 0, 0

    def take(self, size):
        if size < 0 or size > len(self.data) - self.pos:
            raise ValueError(f'记录在 {self.pos} 截断，需要 {size} 字节')
        value = self.data[self.pos:self.pos + size]
        self.pos += size
        return value

    def uvar(self):
        result = 0
        for ordinal in range(10):
            value = self.take(1)[0]
            if ordinal == 9 and value > 1:
                raise ValueError('无符号 64 位变长整数溢出')
            result |= (value & 127) << (ordinal * 7)
            if value < 128:
                if ordinal and value == 0:
                    raise ValueError('非规范变长整数编码')
                return result
        raise ValueError('变长整数过长')

    def sint(self):
        value = self.uvar()
        return (value >> 1) ^ -(value & 1)

    def integer(self, bits, signed):
        value = self.sint() if signed else self.uvar()
        minimum = -(1 << (bits - 1)) if signed else 0
        maximum = (1 << (bits - int(signed))) - 1
        if not minimum <= value <= maximum:
            raise ValueError(f'{bits} 位整数溢出')
        return value

    def read(self, description):
        self.nodes += 1
        self.depth += 1
        try:
            if self.depth > MAX_DEPTH or self.nodes > MAX_NODES:
                raise ValueError('嵌套层数或字段数量超出读取边界')
            return self._read(description)
        finally:
            self.depth -= 1

    def _read(self, description):
        kind, name = description['kind'], description.get('name')
        if kind == 2:
            value = self.take(1)[0]
            if value > 1:
                raise ValueError('非布尔字节')
            return bool(value)
        if kind in (6, 8, 10):
            return self.integer({6: 16, 8: 32, 10: 64}[kind], True)
        if kind in (7, 9, 11):
            return self.integer({7: 16, 9: 32, 11: 64}[kind], False)
        if kind == 12:
            value = struct.unpack('<f', self.take(4))[0]
            if not math.isfinite(value):
                raise ValueError('非有限浮点数')
            return value
        if kind == 14:
            length = self.uvar()
            if length > 65536:
                raise ValueError('字符串长度超出读取边界')
            return self.take(length).decode('utf-8', errors='strict')
        if kind == 29:
            length = self.integer(32, True)
            if length == -1:
                return None
            if not 0 <= length <= MAX_ARRAY:
                raise ValueError('数组长度超出读取边界')
            if 'elementType' not in description:
                raise ValueError('数组没有已核对的元素类型')
            return [self.read(description['elementType']) for _ in range(length)]
        if name == 'TextID':
            return {'id': self.integer(32, True), 'hash': self.uvar()}
        if name == 'JsonEnum' and self.profile.get('binaryEncodings', {}).get(name) == 'zigzag32_pair':
            # The pinned ParseFromBinary reader has two varuint/zigzag32 reads, no object mask.
            offset = self.pos
            return {'$type': name, '$offset': offset,
                    'EnumIndex': self.integer(32, True), 'Value': self.integer(32, True)}
        if name == 'MVector3':
            return {axis: self.read({'kind': 12}) for axis in ('x', 'y', 'z')}
        if name == 'DynamicFloat':
            mode = self.take(1)[0]
            if mode != 0:
                raise ValueError('尚未核对 DynamicFloat 表达式，拒绝代入固定数值')
            return {'$type': name, '$mode': mode, 'fixedRawSigned': self.integer(64, True)}
        if name == 'TaskConfig':
            offset, tag = self.pos, self.uvar()
            task = self.profile['tasks'].get(str(tag))
            if not task:
                raise ValueError(f'未知任务 {tag}，位置 {offset}')
            if task['noReaderPayload']:
                return {'$type': task['name'], 'taskId': tag,
                        '$offset': offset, '$noReaderPayload': True}
            result = self.obj(task['name'])
            result['taskId'] = tag
            return result
        tables = {'PredicateConfig': 'predicates', 'TargetEvaluator': 'targets',
                  'SwitchRefBase': 'switchRefs', 'CaseContainerBase': 'caseContainers',
                  'SharedValue': 'sharedValues'}
        if name in tables:
            tag = self.uvar()
            subtype = self.profile[tables[name]].get(str(tag))
            if not subtype:
                raise ValueError(f'未知 {name} 分支 {tag}')
            result = self.obj(subtype)
            result['$variantId'] = tag
            return result
        if kind == 17 and str(description.get('typeIndex')) in self.profile['enums']:
            return self.integer(32, True)
        if kind in (17, 18) and name in self.profile['classes']:
            return self.obj(name)
        raise ValueError(f'未核对的字段类型 {kind}/{name}')

    def obj(self, name):
        definition = self.profile['classes'].get(name)
        if not definition:
            raise ValueError(f'未核对的对象 {name}')
        offset, mask = self.pos, self.uvar()
        fields = definition['fields']
        if mask >> len(fields):
            raise ValueError(f'{name} 存在未知字段掩码 {mask:x}')
        result = {'$type': name, '$mask': hex(mask), '$offset': offset}
        for ordinal, field in enumerate(fields):
            if mask & (1 << ordinal):
                result[field['name']] = self.read(field['type'])
        return result


def decode_graph(data, profile=None):
    reader = Reader(data, profile)
    graph = reader.read({'kind': 18, 'name': 'LevelGraphConfig'})
    if reader.pos != len(data):
        raise ValueError(f'关卡记录仍有 {len(data) - reader.pos} 字节未读取')
    return graph


def recover(args):
    profile = load_profile()
    paths = args.paths or list(profile['records'])
    if not paths or len(set(paths)) != len(paths) or any(path not in profile['records'] for path in paths):
        raise ValueError('只接受七份已核对脚本中的唯一规范路径')
    spec = importlib.util.spec_from_file_location('read_design', Path(__file__).with_name('read-client-design-data.py'))
    design = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(design)
    client = args.client_dir.resolve()
    output = design.outside_client(args.output, client)
    with tempfile.TemporaryDirectory(prefix='hkrpg-level-graphs-') as directory:
        temp = Path(directory)
        source = design.extract(SimpleNamespace(client_dir=client, paths=paths, paths_file=None,
                                               extract_dir=temp / 'raw', output=temp / 'index.json'))
        if source['files'] != profile['clientFiles'] or source['indexSHA256'] != profile['indexSHA256']:
            raise ValueError('客户端或资源索引与字段证据不匹配')
        decoded = []
        for row in source['records']:
            expected = profile['records'][row['sourcePath']]
            if row['status'] != 'read' or any(row.get(key) != value for key, value in expected.items()):
                raise ValueError('脚本记录与已核对的索引键、长度、哈希不匹配')
            raw = Path(row['extractedFile']).read_bytes()
            if len(raw) != expected['length'] or hashlib.sha256(raw).hexdigest() != expected['sha256']:
                raise ValueError('脚本记录在读取后发生变化')
            graph = decode_graph(raw, profile)
            decoded.append({key: value for key, value in row.items() if key != 'extractedFile'} |
                           {'fullyConsumed': True, 'graph': graph})
    report = {'clientExecutableStarted': False, 'clientModified': False, 'clientAccepted': False,
              'jsonInstalled': False, 'scriptsExecuted': False, 'completeGameplay': False,
              'profileSHA256': PROFILE_SHA256, 'clientFiles': source['files'],
              'indexSHA256': source['indexSHA256'], 'verifiedChunks': source['verifiedChunks'],
              'records': decoded}
    text = json.dumps(report, ensure_ascii=False, indent=2) + '\n'
    if output.exists():
        if not output.is_file() or output.read_text(encoding='utf-8') != text:
            raise FileExistsError('已有不同内容的输出文件，拒绝覆盖')
    else:
        output.parent.mkdir(parents=True, exist_ok=True)
        with output.open('x', encoding='utf-8', newline='\n') as stream:
            stream.write(text)
    return report


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--client-dir', type=Path, required=True)
    parser.add_argument('--paths', nargs='*', default=[])
    parser.add_argument('--output', type=Path, default=Path('build/client46-level-graphs.json'))
    arguments = parser.parse_args()
    report = recover(arguments)
    print(json.dumps({'fullyConsumed': len(report['records']), 'output': str(arguments.output),
                      'jsonInstalled': False, 'clientAccepted': False}))
