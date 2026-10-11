"""只读核对 G61 固定十份4.6图的完整本机 bytes，准备六图加法安装 bundle。

完整解码与固定原件等价不等于执行剧情或游戏 UI 验收；本脚本不写运营资源。
"""
import argparse
import base64
import hashlib
import importlib.util
import json
import struct
import subprocess
import tempfile
from pathlib import Path
from types import SimpleNamespace

ROOT = Path(__file__).resolve().parents[1]
PROFILE = Path(__file__).with_name('data') / 'client46-g61-npc-graphs.json'
PROFILE_SHA256 = '24f4e9f0bedead6ec6feedc6f9984779d2b0919ebbf017a22cde5e8e36137baf'
FIXTURE = Path(__file__).with_name('fixtures') / 'g61-npc-graphs-4.6.json'
FIXTURE_SHA256 = '0ea838df7f4cb0e9acdeb1e6b308c7bca3d695dd843865c760d9d384c63d4c6d'
SOURCE_COMMIT = '724b139d8c9c32d12552eb95745a4fee72bfe48b'


def module(name, filename):
    spec = importlib.util.spec_from_file_location(name, Path(__file__).with_name(filename))
    result = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(result)
    return result


rest = module('g61_graph_rest', 'recover-client-rogue-rest-graphs.py')


def checksum(data):
    return hashlib.sha256(data).hexdigest()


def pinned_json(path, expected):
    raw = path.read_bytes().replace(b'\r\n', b'\n')
    if checksum(raw) != expected:
        raise ValueError('G61 固定证据已变化: ' + path.name)
    return json.loads(raw)


def load_profile():
    extension = pinned_json(PROFILE, PROFILE_SHA256)
    profile = rest.load_profile()
    if extension['schema'] != 1 or extension['restProfileSHA256'] != rest.PROFILE_SHA256:
        raise ValueError('G61 基础脚本读取证据不符')
    if (extension['clientFiles'] != profile['clientFiles'] or
            extension['indexSHA256'] != profile['indexSHA256']):
        raise ValueError('G61 固定客户端来源不符')
    for table in ('classes', 'enums', 'tasks', 'predicates', 'jsonTypeNames'):
        if set(profile[table]) & set(extension[table]):
            raise ValueError('G61 新读取定义与已有证据冲突: ' + table)
        profile[table].update(extension[table])
    # Existing primitive tasks already have independently pinned native factories;
    # the fixed source graph retains their original RPG.GameCore JSON names.
    for task in profile['tasks'].values():
        profile['jsonTypeNames'].setdefault(task['name'], 'RPG.GameCore.' + task['name'])
    profile['records'] = extension['records']
    profile['g61Extension'] = extension
    return profile


class Reader(rest.missions.graphs.Reader):
    def _read(self, description):
        if description.get('name') == 'OptionTalkInfo':
            tag = self.uvar()
            if tag != self.profile['g61Extension']['optionTalkInfoFactory']['tag']:
                raise ValueError('未核对的 OptionTalkInfo 分支: ' + str(tag))
            value = self.obj('OptionTalkInfo')
            value['$variantId'] = tag
            return value
        return super()._read(description)


def decode_graph(raw, profile=None):
    reader = Reader(raw, profile or load_profile())
    graph = reader.read({'kind':18, 'name':'LevelGraphConfig'})
    if reader.pos != len(raw):
        raise ValueError('G61 图仍有未消费字节: ' + str(len(raw) - reader.pos))
    return graph


def float32_json(value):
    encoded = struct.pack('<f', value)
    for digits in range(1, 10):
        candidate = float(format(value, f'.{digits}g'))
        if struct.pack('<f', candidate) == encoded:
            return candidate
    raise ValueError('G61 单精度数不能保持原二进制值')


def resource_json(value, profile, description=None):
    if isinstance(value, list):
        element = description.get('elementType') if description else None
        return [resource_json(item, profile, element) for item in value]
    if isinstance(value, dict):
        name = value.get('$type')
        if description and description.get('name') == 'MVector3':
            if set(value) != {'x', 'y', 'z'}:
                raise ValueError('G61 向量包含未知字段')
            return {axis.upper():float32_json(value[axis]) for axis in ('x', 'y', 'z')}
        if name == 'JsonEnum':
            if set(value) != {'$type', '$offset', 'EnumIndex', 'Value'}:
                raise ValueError('G61 JsonEnum 包含未知字段')
            return {key:value[key] for key in ('EnumIndex', 'Value')}
        if name == 'DynamicFloat':
            unit = profile['g61Extension']['fixedPointUnit']
            if (set(value) != {'$type', '$mode', 'fixedRawSigned'} or value['$mode'] != 0
                    or value['fixedRawSigned'] % unit):
                raise ValueError('G61 只接受本批原件中的整数字面定点值')
            return {'IsDynamic':False, 'FixedValue':{'Value':value['fixedRawSigned'] // unit}}
        fields = {field['name']:field['type'] for field in profile['classes'].get(name, {}).get('fields', [])}
        result = {}
        if ('taskId' in value or '$variantId' in value
                or name in profile['g61Extension']['ordinaryJSONTypeNames']):
            if name not in profile['jsonTypeNames']:
                raise ValueError('G61 JSON 类型名称没有固定证据: ' + str(name))
            result['$type'] = profile['jsonTypeNames'][name]
        for key, item in value.items():
            if key.startswith('$') or key == 'taskId':
                continue
            if key not in fields:
                raise ValueError('G61 JSON 字段没有固定证据: ' + str(name) + '.' + key)
            result[key] = resource_json(item, profile, fields[key])
        return result
    if description and description['kind'] == 17:
        enum = profile['enums'].get(str(description.get('typeIndex')))
        if enum is None or str(value) not in enum['values']:
            raise ValueError('G61 枚举值没有固定证据')
        return enum['values'][str(value)]
    if description and description['kind'] == 12:
        return float32_json(value)
    return value


def type_description(value):
    result = {key:value[key] for key in ('kind', 'name', 'typeIndex') if key in value}
    if 'elementType' in value:
        result['elementType'] = type_description(value['elementType'])
    return result


def verify_native(client, profile):
    inherited = rest.verify_native(client, rest.load_profile())
    extension = profile['g61Extension']
    branches = extension['branches']
    native = module('g61_graph_native', 'analyze-client-46.py')
    info = native.extract(SimpleNamespace(client_dir=client,
        types=list(extension['nativeFieldLayouts']) + [enum['name'] for enum in extension['enums'].values()],
        fields=True, field_offsets=True, inheritance=True, type_contains=[],
        method_indices=[branch['readerMethod']['index'] for branch in branches if branch['readerMethod'] is not None],
        method_rvas=[], type_slots=[int(branch['typeSlot'],16) for branch in branches]
            + [int(extension['optionTalkInfoFactory']['typeSlot'],16)],
        method_slots=[int(branch['methodSlot'],16) for branch in branches], string_slots=[],
        method_names=[], method_name_contains=[], disassemble=0))
    if info['files'] != extension['clientFiles'] or info['unmatchedTypes']:
        raise ValueError('G61 原生客户端或类型布局不符')
    types = {row['name']:row for row in info['methods']}
    for name, expected in extension['nativeFieldLayouts'].items():
        actual = types[name]
        fields = [{key:field[key] for key in ('name','declaringType','fieldIndex','instanceOffset')} |
                  {'type':type_description(field['nativeType'])} for field in actual['instanceFields']]
        if expected != {'typeIndex':actual['typeIndex'], 'fields':fields}:
            raise ValueError('G61 原生继承字段或实例布局不符: ' + name)
    for index, enum in extension['enums'].items():
        actual = types[enum['name']]
        values = {str(field['constant']['value']):field['name']
                  for field in actual['fields'] if 'value' in field.get('constant', {})}
        if actual['typeIndex'] != int(index) or values != enum['values']:
            raise ValueError('G61 原生枚举不符')
    slots = {row['slot']:row['typeName'] for row in info['typeInfoSlots']}
    methods = {row['slot']:row['method'] for row in info['methodInfoSlots']}
    readers = {row['index']:row for row in info['selectedMethods']}
    for branch in branches:
        if methods[branch['methodSlot']] != branch['factoryMethod'] or slots[branch['typeSlot']] != branch['name']:
            raise ValueError('G61 工厂登记或分配类型不符')
        if branch['readerMethod'] is not None and readers[branch['readerMethod']['index']] != branch['readerMethod']:
            raise ValueError('G61 任务读取方法不符')
    if slots[extension['optionTalkInfoFactory']['typeSlot']] != 'OptionTalkInfo':
        raise ValueError('G61 选项标签0的分配类型不符')

    import pefile
    pe = pefile.PE(str(client / 'GameAssembly.dll'), fast_load=True)
    try:
        table = pe.OPTIONAL_HEADER.DATA_DIRECTORY[3]
        bounds = {begin:end for begin,end,_ in struct.iter_unpack('<III',pe.get_data(table.VirtualAddress,table.Size))}
        for function in extension['functions'].values():
            begin, end = int(function['beginRVA'],16), int(function['endRVA'],16)
            if bounds.get(begin) != end or not begin < end < begin + 200000:
                raise ValueError('G61 完整读取函数边界不符')
            raw = pe.get_data(begin, end - begin)
            if len(raw) != end - begin or checksum(raw) != function['sha256']:
                raise ValueError('G61 完整读取函数原bytes不符')
            for point in function.get('points', []):
                rva, data = int(point['rva'],16), bytes.fromhex(point['bytes'])
                if not begin <= rva <= end - len(data) or pe.get_data(rva,len(data)) != data:
                    raise ValueError('G61 选项读取字段与分支证明不符')
        for branch in branches:
            function = extension['functions']['taskFactoryInitializer' if branch['factory'] == 'tasks'
                                               else 'predicateFactoryInitializer']
            begin, end, point = int(function['beginRVA'],16), int(function['endRVA'],16), int(branch['assignmentRVA'],16)
            if not begin + 7 <= point < end - 7 or int(branch['arrayOffset'],16) != branch['tag'] * 8 + 0x20:
                raise ValueError('G61 工厂登记编号越界')
            store, load = pe.get_data(point,7), pe.get_data(point - 7,7)
            if (store[:3] != b'\x48\x89\x90' or struct.unpack_from('<i',store,3)[0] != int(branch['arrayOffset'],16)
                    or load[:3] != b'\x48\x8b\x15'
                    or point + struct.unpack_from('<i',load,3)[0] != int(branch['methodSlot'],16)):
                raise ValueError('G61 工厂实际精确槽位不符')
        option = extension['optionTalkInfoFactory']
        jump = int(option['jumpTableRVA'],16)
        if jump + struct.unpack('<i',pe.get_data(jump,4))[0] != int(option['branchRVA'],16):
            raise ValueError('G61 OptionTalkInfo 的真实标签0跳转不符')
        reader_jump = int(option['readerJumpRVA'],16)
        encoded = pe.get_data(reader_jump,5)
        if encoded[:1] != b'\xe9' or reader_jump + 5 + struct.unpack_from('<i',encoded,1)[0] != int(option['readerRVA'],16):
            raise ValueError('G61 选项标签0未转到原读取器')
    finally:
        pe.close()
    return {'inheritedRestProof':inherited, 'classes':len(extension['nativeFieldLayouts']),
            'taskBranches':sum(branch['factory']=='tasks' for branch in branches),
            'predicateBranches':sum(branch['factory']=='predicates' for branch in branches),
            'completeFunctions':len(extension['functions']), 'optionTalkInfoTag':0,
            'optionRuntimePrefixFieldsExcluded':4, 'files':info['files']}


def fixture_records():
    value = pinned_json(FIXTURE, FIXTURE_SHA256)
    if value['schema'] != 1 or value['sourceCommit'] != SOURCE_COMMIT:
        raise ValueError('G61 固定源提交不符')
    records = {}
    for record in value['records']:
        path = record['sourcePath']
        if path in records:
            raise ValueError('G61 原件路径重复')
        for key, size, sha in [('rawBase64','length','sha256'), ('sourceBlobBase64','sourceBytes','sourceBlobSHA256')]:
            raw = base64.b64decode(record[key],validate=True)
            if len(raw) != record[size] or checksum(raw) != record[sha]:
                raise ValueError('G61 完整原件大小或哈希不符')
        records[path] = record
    return value, records


def fixed_source(repository, record):
    result = subprocess.run(['git','-C',str(repository),'show',SOURCE_COMMIT + ':' + record['sourcePath']],
                            check=True, capture_output=True)
    expected = base64.b64decode(record['sourceBlobBase64'],validate=True)
    if result.stdout != expected:
        raise ValueError('G61 本地固定 commit 整原件不符')
    return json.loads(expected)


def encode(value):
    return (json.dumps(value,ensure_ascii=False,indent=2,allow_nan=False) + '\n').encode('utf-8')


def write_new(path, raw):
    if path.exists():
        if not path.is_file() or path.read_bytes() != raw:
            raise FileExistsError('已有不同内容的 G61 输出，拒绝覆盖: ' + str(path))
        return
    path.parent.mkdir(parents=True,exist_ok=True)
    with path.open('xb') as stream:
        stream.write(raw)


def installed_status(directory, path, value):
    root = directory.resolve()
    target = (root / path).resolve()
    if not target.is_relative_to(root):
        raise ValueError('G61 资源路径越界')
    if not target.exists():
        return {'status':'missing','sha256':None,'path':str(target)}
    if not target.is_file():
        raise ValueError('G61 运营资源目标不是普通文件')
    raw = target.read_bytes()
    if json.loads(raw) != value:
        raise ValueError('已有 G61 运营图与完整固定原件不符，拒绝覆盖: ' + str(target))
    return {'status':'present_matching','sha256':checksum(raw),'path':str(target)}


def recover(args):
    profile = load_profile()
    fixture, fixed = fixture_records()
    client = args.client_dir.resolve()
    output = rest.missions.design.outside_client(args.output,client)
    paths = args.paths or list(profile['records'])
    if not paths or len(paths) != len(set(paths)) or any(path not in fixed for path in paths):
        raise ValueError('G61 只接受固定十份原件中的唯一规范路径')
    if args.bundle_dir and set(paths) != set(fixed):
        raise ValueError('准备六图 bundle 必须完整核对十图')
    bundle = args.bundle_dir.resolve() if args.bundle_dir else None
    if bundle and not bundle.is_relative_to((ROOT / 'build').resolve()):
        raise ValueError('G61 bundle 只能写项目 build，运营资源由 root 单独安装')
    native = verify_native(client,profile)
    with tempfile.TemporaryDirectory(prefix='hkrpg-g61-graphs-') as directory:
        temp = Path(directory)
        source = rest.missions.design.extract(SimpleNamespace(client_dir=client,paths=paths,paths_file=None,
            extract_dir=temp / 'raw',output=temp / 'index.json'))
        if source['files'] != fixture['clientFiles'] or source['indexSHA256'] != fixture['indexSHA256']:
            raise ValueError('G61 本机完整索引或客户端来源不符')
        records, pending = [], []
        for row in source['records']:
            expected = fixed[row['sourcePath']]
            if row['status'] != 'read' or any(row.get(key) != expected[key] for key in ('entryKey','length','sha256','offset')):
                raise ValueError('G61 本机完整原件索引、长度或原bytes不符')
            raw = Path(row['extractedFile']).read_bytes()
            if raw != base64.b64decode(expected['rawBase64'],validate=True):
                raise ValueError('G61 本机记录与固定夹具的整原bytes不符')
            graph = decode_graph(raw,profile)
            value = resource_json(graph,profile)
            original = fixed_source(args.source_repository,expected)
            if value != original:
                raise ValueError('G61 完整解码与固定源整对象不相等: ' + row['sourcePath'])
            install = installed_status(args.resources_dir,row['sourcePath'],value)
            content = encode(value)
            record = {key:value for key,value in row.items() if key != 'extractedFile'} | {
                'fullyConsumed':True,'fullFixedSourceEqual':True,'sourceBlobSHA256':expected['sourceBlobSHA256'],
                'sourceBytes':expected['sourceBytes'],'decodedResourceSHA256':checksum(content),
                'decodedBytesEqualOriginal':content==base64.b64decode(expected['sourceBlobBase64'],validate=True),
                'installationBefore':install,'resource':value}
            records.append(record)
            if row['sourcePath'] in profile['g61Extension']['requiredInstallPaths']:
                pending.append((record,content))
    report = {'clientExecutableStarted':False,'clientModified':False,'clientAccepted':False,
        'jsonInstalled':False,'scriptsExecuted':False,'operatorResourcesModified':False,
        'sourceCommit':fixture['sourceCommit'],'sourceTitle':fixture['sourceTitle'],
        'referenceLicenseEvidence':fixture['referenceLicenseEvidence'], 'profileSHA256':PROFILE_SHA256,
        'fixtureSHA256':FIXTURE_SHA256,'clientFiles':source['files'],'indexSHA256':source['indexSHA256'],
        'verifiedChunks':source['verifiedChunks'],'nativeProof':native,'records':records}
    if bundle:
        if len(pending) != 6:
            raise ValueError('G61 六份加法安装范围缺失')
        files = []
        for record, content in pending:
            path = record['sourcePath']
            if record['installationBefore']['status'] == 'missing':
                write_new(bundle / path,content)
            files.append({key:record[key] for key in ('sourcePath','sourceBlobSHA256','decodedResourceSHA256',
                'sourceBytes','decodedBytesEqualOriginal','fullyConsumed','fullFixedSourceEqual','installationBefore')} |
                {'bundlePath':str(bundle / path),'action':'add_only' if record['installationBefore']['status']=='missing'
                 else 'already_present_matching'})
        manifest = {key:report[key] for key in ('sourceCommit','sourceTitle','profileSHA256','fixtureSHA256',
                                              'clientFiles','indexSHA256')} | {
            'schema':1,'addOnly':True,'operatorResourcesModified':False,'resourcesDirectory':str(args.resources_dir.resolve()),
            'bundleDirectory':str(bundle),'requiredFiles':6,'files':files,
            'completeLocalGraphsVerified':len(records),'nativeProof':native}
        write_new(bundle / 'manifest.json',encode(manifest))
        report['pendingInstallManifest'] = str(bundle / 'manifest.json')
    write_new(output,encode(report))
    return report


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--client-dir',type=Path,required=True)
    parser.add_argument('--source-repository',type=Path,default=ROOT / 'references/TurnBasedGameData')
    parser.add_argument('--resources-dir',type=Path,default=ROOT / 'resources/4.6')
    parser.add_argument('--paths',nargs='*',default=[])
    parser.add_argument('--bundle-dir',type=Path)
    parser.add_argument('--output',type=Path,default=ROOT / 'build/g61-client-graphs-proof.json')
    args = parser.parse_args()
    report = recover(args)
    print(json.dumps({'graphs':len(report['records']),'fullyConsumed':sum(row['fullyConsumed'] for row in report['records']),
        'fullFixedSourceEqual':sum(row['fullFixedSourceEqual'] for row in report['records']),
        'pendingInstallManifest':report.get('pendingInstallManifest'),'output':str(args.output),
        'operatorResourcesModified':False,'clientAccepted':False},ensure_ascii=False))
