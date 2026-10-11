"""只读核对四份固定国服4.6休息区脚本；输出报告，不安装或执行脚本。

G34真实分支保留教程状态与强化页；完整解码不等于商品菜单或客户端UI验收。
"""
import argparse
import hashlib
import importlib.util
import json
import struct
import tempfile
from pathlib import Path
from types import SimpleNamespace

PROFILE = Path(__file__).with_name('data') / 'client46-rogue-rest-graphs.json'
PROFILE_SHA256 = '3661c886777d497695bae42cd4386cc33547c80a96337c2f0cf2568da80d22ba'


def module(name, filename):
    spec = importlib.util.spec_from_file_location(name, Path(__file__).with_name(filename))
    result = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(result)
    return result


missions = module('rogue_rest_missions', 'recover-client-mission-graphs.py')


def load_profile():
    raw = PROFILE.read_bytes().replace(b'\r\n', b'\n')
    if hashlib.sha256(raw).hexdigest() != PROFILE_SHA256:
        raise ValueError('休息区固定原生证据已变化，需要重新核对')
    extension = json.loads(raw)
    if extension['schema'] != 1 or extension['missionProfileSHA256'] != missions.PROFILE_SHA256:
        raise ValueError('休息区基础脚本字段证据不符')
    profile = missions.load_profile()
    if extension['clientFiles'] != profile['clientFiles'] or extension['indexSHA256'] != profile['indexSHA256']:
        raise ValueError('休息区固定客户端来源不符')
    for table in ('classes', 'tasks', 'predicates', 'jsonTypeNames'):
        if set(profile[table]) & set(extension[table]):
            raise ValueError('休息区分支与既有固定字段冲突')
        profile[table].update(extension[table])
    profile['binaryEncodings'] = extension['binaryEncodings']
    profile['records'] = extension['records']
    profile['restExtension'] = extension
    return profile


def decode_graph(raw, profile=None):
    return missions.decode_graph(raw, profile or load_profile())


def resource_json(value, profile, description=None):
    if isinstance(value, list):
        element = description.get('elementType') if description else None
        return [resource_json(item, profile, element) for item in value]
    if isinstance(value, dict):
        name = value.get('$type')
        if name == 'JsonEnum':
            if set(value) != {'$type', '$offset', 'EnumIndex', 'Value'}:
                raise ValueError('休息区 JsonEnum 存在未核对字段')
            return {key:value[key] for key in ('EnumIndex', 'Value')}
        fields = {field['name']:field['type'] for field in profile['classes'].get(name, {}).get('fields', [])}
        result = {}
        if 'taskId' in value or '$variantId' in value:
            if name not in profile['jsonTypeNames']:
                raise ValueError('休息区JSON类型名称没有固定来源')
            result['$type'] = profile['jsonTypeNames'][name]
        for key, item in value.items():
            if key.startswith('$') or key == 'taskId':
                continue
            if key not in fields:
                raise ValueError('休息区JSON字段没有固定来源')
            result[key] = resource_json(item, profile, fields[key])
        return result
    if description and description['kind'] == 17:
        enum = profile['enums'].get(str(description.get('typeIndex')))
        if enum is None or str(value) not in enum['values']:
            raise ValueError('休息区枚举值没有固定来源')
        return enum['values'][str(value)]
    return value


def type_description(value):
    result = {key:value[key] for key in ('kind', 'name', 'typeIndex') if key in value}
    if 'elementType' in value:
        result['elementType'] = type_description(value['elementType'])
    return result


def verify_native(client, profile):
    # Recheck the inherited CollectDataConditions / mission-state reader and primitive proof.
    inherited = missions.verify_native(client, missions.load_profile())
    extension = profile['restExtension']
    branches = list(extension['tasks'].values()) + list(extension['predicateBranches'].values())
    native = module('rogue_rest_native', 'analyze-client-46.py')
    reader_rvas = [branch['readerRVA'] for branch in branches if branch['readerRVA'] is not None]
    reader_rvas += ['0x1d7724a0']
    info = native.extract(SimpleNamespace(
        client_dir=client, types=list(extension['nativeFieldLayouts']), fields=True,
        field_offsets=True, inheritance=True, type_contains=[], method_indices=[],
        method_rvas=[int(rva, 16) for rva in sorted(set(reader_rvas))],
        type_slots=[int(branch['typeSlot'], 16) for branch in branches],
        method_slots=[int(branch['methodSlot'], 16) for branch in branches],
        string_slots=[], method_names=[], method_name_contains=[], disassemble=0))
    if info['files'] != extension['clientFiles'] or info['unmatchedTypes']:
        raise ValueError('休息区客户端类型来源不符')
    types = {row['name']:row for row in info['methods']}
    for name, expected in extension['nativeFieldLayouts'].items():
        actual = types[name]
        fields = [{'name':field['name'], 'declaringType':field['declaringType'], 'fieldIndex':field['fieldIndex'],
                   'instanceOffset':field['instanceOffset'], 'type':type_description(field['nativeType'])}
                  for field in actual['instanceFields']]
        if expected != {'typeIndex':actual['typeIndex'], 'fields':fields}:
            raise ValueError('休息区原生字段、继承或实例布局不符')
    type_slots = {row['slot']:row['typeName'] for row in info['typeInfoSlots']}
    method_slots = {row['slot']:row['method'] for row in info['methodInfoSlots']}
    readers = {row['rva']:row for row in info['selectedMethods']}
    for branch in branches:
        factory = method_slots[branch['methodSlot']]
        if (factory['index'] != branch['factoryMethodIndex'] or factory['rva'] != branch['factoryRVA']
                or type_slots[branch['typeSlot']] != branch['name']):
            raise ValueError('休息区原生分支或分配类型不符')
        if branch['readerRVA'] is not None and readers[branch['readerRVA']]['typeName'] != branch['name']:
            raise ValueError('休息区原生读取方法所属类型不符')
    enum_reader = readers['0x1d7724a0']
    if enum_reader['index'] != 75371 or enum_reader['typeName'] != 'JsonEnum' or enum_reader['name'] != 'ParseFromBinary':
        raise ValueError('休息区 JsonEnum 原生读取方法不符')

    import pefile
    pe = pefile.PE(str(client / 'GameAssembly.dll'), fast_load=True)
    try:
        table = pe.OPTIONAL_HEADER.DATA_DIRECTORY[3]
        bounds = {begin:end for begin, end, _ in struct.iter_unpack('<III', pe.get_data(table.VirtualAddress, table.Size))}
        for function in extension['functions'].values():
            begin, end = int(function['beginRVA'], 16), int(function['endRVA'], 16)
            if bounds.get(begin) != end or not begin < end < begin + 200000:
                raise ValueError('休息区完整原生函数边界不符')
            raw = pe.get_data(begin, end - begin)
            if len(raw) != end - begin or hashlib.sha256(raw).hexdigest() != function['sha256']:
                raise ValueError('休息区完整原生函数字节不符')
        for table_name, initializer in [('tasks', 'taskFactoryInitializer'), ('predicateBranches', 'predicateFactoryInitializer')]:
            start = int(extension['functions'][initializer]['beginRVA'], 16)
            finish = int(extension['functions'][initializer]['endRVA'], 16)
            for tag, branch in extension[table_name].items():
                point = int(branch['assignmentRVA'], 16)
                if not start + 7 <= point < finish - 7 or int(branch['arrayOffset'], 16) != int(tag) * 8 + 0x20:
                    raise ValueError('休息区工厂编号或分配点越界')
                store = pe.get_data(point, 7)
                load = pe.get_data(point - 7, 7)
                if (store[:3] != b'\x48\x89\x90' or struct.unpack_from('<i', store, 3)[0] != int(branch['arrayOffset'], 16)
                        or load[:3] != b'\x48\x8b\x15'
                        or point + struct.unpack_from('<i', load, 3)[0] != int(branch['methodSlot'], 16)):
                    raise ValueError('休息区原生工厂精确槽位不符')
    finally:
        pe.close()
    return {'inheritedMissionProof': inherited, 'classes':len(types), 'taskBranches':len(extension['tasks']),
            'predicateBranches':len(extension['predicateBranches']), 'completeFunctions':len(extension['functions']),
            'files':info['files'], 'jsonEnumEncoding':'zigzag32_pair'}


def recover(args):
    profile = load_profile()
    client = args.client_dir.resolve()
    output = missions.design.outside_client(args.output, client)
    paths = args.paths or list(profile['records'])
    if not paths or len(paths) != len(set(paths)) or any(path not in profile['records'] for path in paths):
        raise ValueError('仅接受四份固定休息区原件的唯一规范路径')
    native = verify_native(client, profile)
    with tempfile.TemporaryDirectory(prefix='hkrpg-rogue-rest-graphs-') as directory:
        temp = Path(directory)
        source = missions.design.extract(SimpleNamespace(client_dir=client, paths=paths, paths_file=None,
            extract_dir=temp / 'raw', output=temp / 'index.json'))
        if source['files'] != profile['clientFiles'] or source['indexSHA256'] != profile['indexSHA256']:
            raise ValueError('休息区客户端索引来源不符')
        records = []
        for row in source['records']:
            expected = profile['records'][row['sourcePath']]
            if row['status'] != 'read' or any(row.get(key) != expected[key] for key in ('entryKey', 'length', 'sha256')):
                raise ValueError('休息区完整原件位置、长度或字节不符')
            raw = Path(row['extractedFile']).read_bytes()
            if len(raw) != expected['length'] or hashlib.sha256(raw).hexdigest() != expected['sha256']:
                raise ValueError('休息区完整原件在读取后变化')
            graph = decode_graph(raw, profile)
            resource = resource_json(graph, profile)
            records.append({key:value for key,value in row.items() if key != 'extractedFile'} |
                {'fullyConsumed': True, 'graph':graph, 'resource':resource,
                 'fixedSourceJsonSHA256':expected['sourceJsonSHA256']})
    report = {'clientExecutableStarted':False, 'clientModified':False, 'clientAccepted':False,
              'scriptsExecuted':False, 'jsonInstalled':False, 'completeGameplay':False,
              'clientMenuEntryVerified':False, 'classicMiracleMenuRestored':False,
              'sourceCommit':profile['restExtension']['sourceCommit'], 'profileSHA256':PROFILE_SHA256,
              'clientFiles':source['files'], 'indexSHA256':source['indexSHA256'],
              'nativeFieldAndBranchProof':native, 'verifiedChunks':source['verifiedChunks'], 'records':records}
    missions.groups.write_new_files({output:missions.groups.encode(report)})
    return report


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--client-dir', type=Path, required=True)
    parser.add_argument('--paths', nargs='*', default=[])
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    result = recover(args)
    print(json.dumps({'fullyConsumed':len(result['records']), 'jsonInstalled':False,
                      'clientAccepted':False, 'scriptsExecuted':False}))
