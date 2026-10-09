"""只读恢复固定 4.6 客户端的已核对关卡组；仅新建输出，拒绝覆盖不同内容。

读取完成只证明二进制和资源形状已核对，不证明剧情脚本执行或客户端玩法验收。
"""
import argparse
import hashlib
import importlib.util
import json
import tempfile
from pathlib import Path
from types import SimpleNamespace

PROFILE = Path(__file__).with_name('data') / 'client46-runtime-groups.json'
PROFILE_SHA256 = 'adf628ddc67b5f4f5f57e7c9a00ac25b4ba38dc225a365f572208387db9f952d'
TABLES = {'SharedValue': 'sharedValues',
          'LevelGroupAtmosphereLoadCondition': 'atmosphereConditions',
          'LevelAnchorParameter': 'anchorParameters',
          'LittleGameLevelVarBindingExtraInfo': 'littleGameExtras',
          'LevelNPCNearbyConfig': 'nearbyConfigs',
          'DynamicJsonConfig': 'dynamicJsonConfigs'}


def module(name, filename):
    spec = importlib.util.spec_from_file_location(name, Path(__file__).with_name(filename))
    result = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(result)
    return result


graphs = module('client_group_primitives', 'decode-client-level-graphs.py')
design = module('client_group_design', 'read-client-design-data.py')


def load_profile():
    raw = PROFILE.read_bytes().replace(b'\r\n', b'\n')
    if hashlib.sha256(raw).hexdigest() != PROFILE_SHA256:
        raise ValueError('关卡组的静态字段证据已变化，需要重新核对')
    return json.loads(raw)


class Reader(graphs.Reader):
    def __init__(self, data, profile=None):
        super().__init__(data, profile if profile is not None else load_profile())

    def _read(self, description):
        kind, name = description['kind'], description.get('name')
        if kind == 5:
            return self.take(1)[0]
        if name == 'MVector2':
            return {axis: self.read({'kind': 12}) for axis in ('x', 'y')}
        if name == 'JsonEnum':
            return {'EnumIndex': self.integer(32, True), 'Value': self.integer(32, True)}
        if kind == 21:
            if not description.get('dictionary') and not description.get('emptyDictionaryOnly'):
                raise ValueError('未核对的泛型容器')
            count = self.integer(32, True)
            if not 0 <= count <= graphs.MAX_ARRAY:
                raise ValueError('字典长度超出读取边界')
            if count and description.get('emptyDictionaryOnly'):
                raise ValueError('尚未核对非空效果字典的元素编码')
            entries, keys = [], set()
            for _ in range(count):
                key = self.read(description['dictionary'][0])
                if not isinstance(key, (str, int)) or isinstance(key, bool) or key in keys:
                    raise ValueError('字典键类型错误或重复')
                keys.add(key)
                entries.append([key, self.read(description['dictionary'][1])])
            return {'$entries': entries}
        if name in TABLES:
            tag = self.uvar()
            subtype = self.profile[TABLES[name]].get(str(tag))
            if not subtype:
                raise ValueError(f'未知 {name} 分支 {tag}')
            result = self.obj(subtype)
            result['$variantId'] = tag
            return result
        if kind in (17, 18) and name in self.profile['classes']:
            definition = self.profile['classes'][name]
            if 'typeIndex' in description and definition['typeIndex'] != description['typeIndex']:
                raise ValueError('同名类型的元数据索引不匹配')
        return super()._read(description)

    def obj(self, name):
        definition = self.profile['classes'].get(name)
        if not definition:
            raise ValueError(f'未核对的对象 {name}')
        fields, offset = definition['fields'], self.pos
        count = definition['maskWords']
        if count not in (1, 2) or len(fields) > count * 64:
            raise ValueError('对象字段掩码数量未核对')
        mask = sum(self.uvar() << (64 * word) for word in range(count))
        if mask >> len(fields):
            raise ValueError(f'{name} 存在未知字段掩码')
        result = {'$type': name, '$mask': hex(mask), '$offset': offset}
        for ordinal, field in enumerate(fields):
            if mask & (1 << ordinal):
                result[field['name']] = self.read(field['type'])
        return result


def decode_group(data, profile=None):
    reader = Reader(data, profile)
    # RtLevelGroupInfoBase.OJNNBEJLDIJ distinguishes 0/base, 1/editor and 2/runtime.
    if reader.uvar() != 2:
        raise ValueError('仅接受已核对的运行时关卡组分支 2')
    group = reader.read({'kind': 18, 'name': 'RtLevelGroupInfo'})
    if reader.pos != len(data):
        raise ValueError(f'关卡组仍有 {len(data) - reader.pos} 字节未读取')
    return group


def resource_json(value, profile, description=None, root=False):
    if isinstance(value, list):
        element = description.get('elementType') if description else None
        return [resource_json(item, profile, element) for item in value]
    if isinstance(value, dict):
        if description and description.get('name') in ('MVector2', 'MVector3'):
            axes = ('x', 'y') if description['name'] == 'MVector2' else ('x', 'y', 'z')
            if set(value) != set(axes):
                raise ValueError('向量形状与原始读取证据不符')
            return {axis.upper(): value[axis] for axis in axes}
        if description and description.get('name') == 'TextID':
            if set(value) != {'id', 'hash'}:
                raise ValueError('文本标识形状与原始读取证据不符')
            return {'Hash': value['hash']}
        if '$entries' in value:
            if not description or description['kind'] != 21 or set(value) != {'$entries'}:
                raise ValueError('字典没有匹配的字段证据')
            child = description.get('dictionary', [None, None])[1]
            result = {}
            for key, item in value['$entries']:
                name = str(key)
                if name in result:
                    raise ValueError('字典键转为 JSON 后重复')
                result[name] = resource_json(item, profile, child)
            return result
        name = value.get('$type')
        fields = {field['name']: field['type']
                  for field in profile['classes'].get(name, {}).get('fields', [])}
        result = {}
        if root or '$variantId' in value:
            if name not in profile['jsonTypeNames']:
                raise ValueError(f'尚未核对 {name} 的 JSON 类型名')
            result['$type'] = profile['jsonTypeNames'][name]
        for key, item in value.items():
            if key.startswith('$'):
                continue
            result[key] = resource_json(item, profile, fields.get(key))
        return result
    if description and description['kind'] == 17:
        enum = profile['enums'].get(str(description.get('typeIndex')))
        if enum:
            if str(value) not in enum['values']:
                raise ValueError(f'未知 {enum["name"]} 数值 {value}')
            return enum['values'][str(value)]
    return value


def encode(value):
    return (json.dumps(value, ensure_ascii=False, indent=2, allow_nan=False) + '\n').encode('utf-8')


def write_new_files(files):
    # Check the entire batch first, including the supplement and report.
    for path, content in files.items():
        if path.exists() and (not path.is_file() or path.read_bytes() != content):
            raise FileExistsError(f'拒绝覆盖已有不同内容的文件: {path}')
    created = 0
    for path, content in files.items():
        if path.exists():
            continue
        path.parent.mkdir(parents=True, exist_ok=True)
        with path.open('xb') as stream:
            stream.write(content)
        created += 1
    return created


def recover(args):
    profile = load_profile()
    paths = args.paths or profile['missingPaths']
    if not paths or len(set(paths)) != len(paths) or any(path not in profile['records'] for path in paths):
        raise ValueError('仅接受字段证据清单中的唯一关卡组路径')
    client = args.client_dir.resolve()
    output = design.outside_client(args.output, client)
    destination = design.outside_client(args.destination, client) if args.destination else None
    decoded, files, checksums = [], {}, {}
    with tempfile.TemporaryDirectory(prefix='hkrpg-runtime-groups-') as directory:
        temp = Path(directory)
        source = design.extract(SimpleNamespace(client_dir=client, paths=paths, paths_file=None,
                                               extract_dir=temp / 'raw', output=temp / 'index.json'))
        if source['files'] != profile['clientFiles'] or source['indexSHA256'] != profile['indexSHA256']:
            raise ValueError('客户端或资源索引与关卡组字段证据不匹配')
        for row in source['records']:
            expected = profile['records'][row['sourcePath']]
            if row['status'] != 'read' or any(row.get(key) != value for key, value in expected.items()):
                raise ValueError('关卡组记录的索引键、长度或哈希不匹配')
            raw = Path(row['extractedFile']).read_bytes()
            if len(raw) != expected['length'] or hashlib.sha256(raw).hexdigest() != expected['sha256']:
                raise ValueError('关卡组记录在读取后发生变化')
            group = decode_group(raw, profile)
            resource = resource_json(group, profile, root=True)
            content = encode(resource)
            checksum = hashlib.sha256(content).hexdigest()
            checksums[row['sourcePath']] = checksum
            if destination:
                target = (destination / row['sourcePath']).resolve()
                if target == destination or destination not in target.parents or target == output:
                    raise ValueError('关卡组输出路径越界或与报告冲突')
                files[target] = content
            decoded.append({key: value for key, value in row.items() if key != 'extractedFile'} |
                           {'fullyConsumed': True, 'group': group, 'resource': resource,
                            'resourceSHA256': checksum})
    report = {'clientExecutableStarted': False, 'clientModified': False, 'clientAccepted': False,
              'scriptsExecuted': False, 'completeGameplay': False, 'jsonInstalled': bool(destination),
              'profileSHA256': PROFILE_SHA256, 'clientFiles': source['files'],
              'indexSHA256': source['indexSHA256'], 'verifiedChunks': source['verifiedChunks'],
              'records': decoded}
    if destination:
        supplement = {'clientVersion': '4.6.0', 'source': 'operator-local-client', 'sourceRegion': 'CN',
                      'clientFiles': source['files'], 'indexSHA256': source['indexSHA256'],
                      'profileSHA256': PROFILE_SHA256, 'clientAcceptance': False,
                      'scriptsExecuted': False, 'completeGameplay': False, 'files': checksums}
        content = encode(supplement)
        filename = 'resource-supplement-client46-rtgroups-' + hashlib.sha256(content).hexdigest()[:12] + '.json'
        target = (destination / filename).resolve()
        if target == output:
            raise ValueError('补充清单与报告路径冲突')
        files[target] = content
        report['supplementFile'] = str(target)
    files[output] = encode(report)
    write_new_files(files)
    return report


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--client-dir', type=Path, required=True)
    parser.add_argument('--paths', nargs='*', default=[])
    parser.add_argument('--destination', type=Path, help='显式指定新建 JSON 的目录；省略时仅输出解析报告')
    parser.add_argument('--output', type=Path, default=Path('build/client46-runtime-groups.json'))
    args = parser.parse_args()
    result = recover(args)
    print(json.dumps({'fullyConsumed': len(result['records']), 'jsonInstalled': result['jsonInstalled'],
                      'clientAccepted': False, 'output': str(args.output)}))
