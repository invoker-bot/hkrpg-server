"""补齐固定国服 4.6 的启动资源；完整读取不等于玩法或客户端验收。

只接受锁定客户端/记录，安装前检查全部冲突，仅新建资源及补充清单。
"""
import argparse
import base64
import hashlib
import importlib.util
import json
from pathlib import Path, PurePosixPath
import subprocess
import tempfile
from types import SimpleNamespace
import zlib

ROOT = Path(__file__).resolve().parents[1]
PROFILE = ROOT / 'scripts/data/client46-startup-resources.json'
PROFILE_SHA256 = 'e0d6acbabfce13a2acabbb1c1a62780e532152cc10c67a8b94aaae0d5225bf60'
FIXTURE = ROOT / 'scripts/tests/fixtures/client46-startup-records.json'
FIXTURE_SHA256 = '471bbc4fa3ff076d1d58a1ef3f97c902c38dabac96aafbd4553a0631d2316ff5'
COMMIT = '724b139d8c9c32d12552eb95745a4fee72bfe48b'
TEXTMAPS = {
    'TextMap/TextMapEN.json': 'a9cc30a02acb4438fd8abd23f4ff50eb08bf6846fc60ab8611b5b27e896dd620',
    'TextMap/TextMapCHS.json': 'b442c7a30bbd5c21e5f4393781bfd833e939e5feca6f6612b01b107e94b2ea9c',
}


def module(name, filename):
    spec = importlib.util.spec_from_file_location(name, Path(__file__).with_name(filename))
    result = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(result)
    return result


groups = module('startup_resource_groups', 'recover-client-runtime-groups.py')
g61 = module('startup_resource_g61', 'recover-client-g61-npc-graphs.py')


def digest(raw):
    return hashlib.sha256(raw).hexdigest()


def pinned(path, expected):
    raw = path.read_bytes().replace(b'\r\n', b'\n')
    if digest(raw) != expected:
        raise ValueError('固定启动资源证据不匹配: ' + path.name)
    return json.loads(raw)


def load_profile():
    extension = pinned(PROFILE, PROFILE_SHA256)
    profile = groups.load_profile()
    graph_profile = g61.load_profile()
    for table in ('classes', 'enums', 'tasks', 'predicates', 'targets', 'switchRefs',
                  'caseContainers', 'jsonTypeNames'):
        for name, value in graph_profile[table].items():
            profile[table].setdefault(name, value)
    for table in ('classes', 'enums', 'tasks', 'predicates', 'jsonTypeNames'):
        profile[table].update(extension[table])
    for definition in profile['classes'].values():
        definition.setdefault('maskWords', 1)
    profile['startup'] = extension
    return profile


class Reader(groups.Reader):
    def _read(self, description):
        name = description.get('name')
        if name == 'FormatString':
            return self.read({'kind': 14})
        factories = {'FloorCustomValueConfig': 'floorCustomValues', 'SOTaskConfig': 'soTasks',
                     'LevelPedestrianV2IntersectionInfo': 'intersections',
                     'AdventureAttackDetectShapeConfig': 'shapes'}
        if name in factories:
            tag = self.uvar()
            concrete = self.profile['startup'][factories[name]].get(str(tag))
            if concrete is None:
                raise ValueError(f'未知启动资源分支 {name}/{tag}')
            value = self.obj(concrete)
            value['$variantId'] = tag
            return value
        return super()._read(description)


def resource_json(value, profile, description=None, root=False):
    if isinstance(value, list):
        element = description.get('elementType') if description else None
        return [resource_json(item, profile, element) for item in value]
    if isinstance(value, dict):
        if description and description.get('name') in ('MVector2', 'MVector3', 'TextID'):
            return groups.resource_json(value, profile, description)
        if '$entries' in value:
            if not description or description['kind'] != 21:
                raise ValueError('字典缺少对应字段证据')
            child = description.get('dictionary', [None, None])[1]
            return {str(key): resource_json(item, profile, child) for key, item in value['$entries']}
        name = value.get('$type')
        if name == 'DynamicFloat':
            if set(value) != {'$type', '$mode', 'fixedRawSigned'} or value['$mode'] != 0:
                raise ValueError('只接受固定资源实际使用的字面定点值')
            # Exact binary rational; do not guess a shorter source decimal.
            return {'IsDynamic': False, 'FixedValue': {
                'Value': value['fixedRawSigned'] / profile['startup']['fixedPointUnit']}}
        if name is None and set(value) == {'EnumIndex', 'Value'}:
            return value
        fields = {field['name']: field['type'] for field in profile['classes'].get(name, {}).get('fields', [])}
        result = {}
        if root or 'taskId' in value or '$variantId' in value:
            result['$type'] = profile['jsonTypeNames'][name]
        for key, item in value.items():
            if key.startswith('$') or key == 'taskId':
                continue
            if key not in fields:
                raise ValueError(f'未核对的资源字段 {name}.{key}')
            result[key] = resource_json(item, profile, fields[key])
        return result
    if description and description.get('name') == 'FormatString':
        return value
    if description and description['kind'] == 17:
        enum = profile['enums'][str(description['typeIndex'])]
        if str(value) not in enum['values']:
            raise ValueError('未核对的枚举值: ' + enum['name'])
        return enum['values'][str(value)]
    return value


def decode_record(path, raw, profile=None):
    profile = profile or load_profile()
    expected = profile['startup']['records'].get(path)
    if not expected or len(raw) != expected['length'] or digest(raw) != expected['sha256']:
        raise ValueError('原始记录长度或 SHA256 不匹配: ' + path)
    reader = Reader(raw, profile)
    if '/RuntimeFloor/' in path:
        value, group = reader.obj('RtLevelFloorInfo'), False
    elif '/SharedRuntimeGroup/' in path or '/RuntimeGroup/' in path:
        if reader.uvar() != 2:
            raise ValueError('地图组不属于已核对的运行时分支 2')
        value, group = reader.obj('RtLevelGroupInfo'), True
    elif '/ConfigAdventureAbility/LocalPlayer/' in path:
        value, group = reader.obj('AdventureAbilityConfigList'), False
    else:
        raise ValueError('资源路径未列入启动补充范围')
    if reader.pos != len(raw):
        raise ValueError('原始记录仍有未读取字节')
    resource = resource_json(value, profile, root=group)
    encoded = groups.encode(resource)
    if digest(encoded) != expected['resourceSHA256']:
        raise ValueError('JSON 结果与已核对的原始记录不匹配')
    return encoded


def fixture_records():
    fixture = pinned(FIXTURE, FIXTURE_SHA256)
    return {path: zlib.decompress(base64.b64decode(raw, validate=True))
            for path, raw in fixture['records'].items()}


def fixture_resources():
    profile = load_profile()
    return {path: decode_record(path, raw, profile) for path, raw in fixture_records().items()}


def install_files(files, destination):
    destination = destination.resolve()
    checked = {}
    for relative, raw in files.items():
        path = PurePosixPath(relative)
        if path.is_absolute() or '..' in path.parts or '\\' in relative or ':' in relative:
            raise ValueError('资源路径越界: ' + relative)
        target = (destination / relative).resolve()
        if not target.is_relative_to(destination) or target == destination:
            raise ValueError('资源目标越界: ' + relative)
        if target.exists() and (not target.is_file() or target.read_bytes() != raw):
            raise FileExistsError('拒绝覆盖已有不同资源: ' + relative)
        checked[target] = raw
    count = 0
    for target, raw in checked.items():
        if target.exists():
            continue
        target.parent.mkdir(parents=True, exist_ok=True)
        with target.open('xb') as stream:
            stream.write(raw)
        count += 1
    return count


def recover(args):
    profile = load_profile()
    client = args.client_dir.resolve()
    output = groups.design.outside_client(args.output, client)
    destination = groups.design.outside_client(args.destination, client) if args.destination else None
    files, records, sources = {}, [], []
    paths = list(profile['startup']['records'])
    with tempfile.TemporaryDirectory(prefix='hkrpg-startup-resources-') as folder:
        temp = Path(folder)
        for offset in range(0, len(paths), 250):
            source = groups.design.extract(SimpleNamespace(client_dir=client, paths=paths[offset:offset + 250],
                paths_file=None, extract_dir=temp / 'raw', output=temp / 'source.json',
                index_sha256=profile['startup']['indexSHA256']))
            sources.append({key: source[key] for key in ('files', 'indexSHA256', 'verifiedChunks')})
            for row in source['records']:
                if row['status'] != 'read':
                    raise ValueError('启动资源原始记录不可用: ' + row['sourcePath'])
                raw = Path(row['extractedFile']).read_bytes()
                files[row['sourcePath']] = decode_record(row['sourcePath'], raw, profile)
                records.append({key: value for key, value in row.items() if key != 'extractedFile'})
    if args.include_textmap:
        for relative, expected in TEXTMAPS.items():
            raw = subprocess.run(['git', '-C', str(args.reference_dir), 'cat-file', 'blob',
                COMMIT + ':' + relative], check=True, capture_output=True, timeout=120).stdout
            if digest(raw) != expected or not isinstance(json.loads(raw), dict):
                raise ValueError('TextMap 的固定提交或内容不符')
            files[relative] = raw
    supplement = {'clientVersion': '4.6.0', 'source': 'operator-local-client', 'sourceRegion': 'CN',
        'clientFiles': profile['startup']['clientFiles'], 'indexSHA256': profile['startup']['indexSHA256'],
        'textMapSourceCommit': COMMIT if args.include_textmap else None,
        'profileSHA256': PROFILE_SHA256, 'clientAcceptance': False, 'completeGameplay': False,
        'files': {name: digest(raw) for name, raw in files.items()}}
    encoded = groups.encode(supplement)
    manifest = 'resource-supplement-client46-startup-' + digest(encoded)[:12] + '.json'
    report = {'verifiedResources': len(files), 'recordsFullyConsumed': len(records), 'sources': sources,
        'supplement': manifest, 'clientAccepted': False, 'completeGameplay': False, 'installed': 0}
    if destination:
        report['installed'] = install_files(files | {manifest: encoded}, destination)
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_bytes(groups.encode(report))
    return report


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--client-dir', type=Path, required=True)
    parser.add_argument('--destination', type=Path)
    parser.add_argument('--include-textmap', action='store_true')
    parser.add_argument('--reference-dir', type=Path, default=ROOT / 'references/TurnBasedGameData')
    parser.add_argument('--output', type=Path, default=ROOT / 'build/client46-startup-resources-report.json')
    print(json.dumps(recover(parser.parse_args()), ensure_ascii=True))
