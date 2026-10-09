"""只读恢复两份固定4.6剧情脚本；解析完成不代表脚本执行或客户端验收。"""
import importlib.util
import argparse
import hashlib
import json
import mmap
import struct
import tempfile
from pathlib import Path
from types import SimpleNamespace

PROFILE = Path(__file__).with_name('data') / 'client46-mission-graphs.json'
PROFILE_SHA256 = '21032416c120e295a6bb1fc22d826661aeb35fd21792dae66cb46e02470e6200'


def module(name, filename):
    spec = importlib.util.spec_from_file_location(name, Path(__file__).with_name(filename))
    result = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(result)
    return result


graphs = module('mission_graph_primitives', 'decode-client-level-graphs.py')
design = module('mission_graph_design', 'read-client-design-data.py')
groups = module('mission_graph_files', 'recover-client-runtime-groups.py')


def load_profile():
    raw = PROFILE.read_bytes().replace(b'\r\n', b'\n')
    if hashlib.sha256(raw).hexdigest() != PROFILE_SHA256:
        raise ValueError('剧情脚本字段证据已变化，拒绝沿用')
    extension = json.loads(raw)
    profile = graphs.load_profile()
    if extension['baseProfileSHA256'] != graphs.PROFILE_SHA256:
        raise ValueError('剧情脚本基础字段证据不符')
    for table in ['classes', 'enums', 'tasks', 'predicates']:
        if set(profile[table]) & set(extension[table]):
            raise ValueError('剧情脚本扩展与既有字段冲突')
        profile[table].update(extension[table])
    for table in ['records', 'jsonTypeNames', 'functions', 'predicateFactory']:
        profile[table] = extension[table]
    profile['extension'] = extension
    return profile


def decode_graph(data, profile=None):
    return graphs.decode_graph(data, profile or load_profile())


def resource_json(value, profile, description=None):
    if isinstance(value, list):
        element = description.get('elementType') if description else None
        return [resource_json(item, profile, element) for item in value]
    if isinstance(value, dict):
        name = value.get('$type')
        if name == 'DynamicFloat':
            # This pair of records has only fixed one. The previously verified
            # native FixPoint conversion reads 0x200000000 as exactly one.
            if value != {'$type':'DynamicFloat','$mode':0,'fixedRawSigned':8589934592}:
                raise ValueError('未核对的剧情定点表达式，拒绝代入数值')
            return {'IsDynamic':False,'FixedValue':{'Value':1}}
        fields = {f['name']:f['type'] for f in profile['classes'].get(name,{}).get('fields',[])}
        result = {}
        if 'taskId' in value or '$variantId' in value:
            if name not in profile['jsonTypeNames']:
                raise ValueError('未核对的剧情JSON类型名')
            result['$type'] = profile['jsonTypeNames'][name]
        for key, item in value.items():
            if key.startswith('$') or key == 'taskId':
                continue
            if key not in fields:
                raise ValueError('剧情字段没有来源类型定义')
            result[key] = resource_json(item, profile, fields[key])
        return result
    if description and description['kind'] == 17:
        enum = profile['enums'].get(str(description.get('typeIndex')))
        if not enum or str(value) not in enum['values']:
            raise ValueError('未知剧情枚举，拒绝猜测名称')
        return enum['values'][str(value)]
    return value


def verify_native(client, profile):
    native = module('mission_graph_native', 'analyze-client-46.py')
    extension = profile['extension']
    branches = list(extension['tasks'].values()) + [extension['predicateFactory']]
    info = native.extract(SimpleNamespace(
        client_dir=client, types=list(extension['classes']) + ['SubMissionState'], fields=True,
        field_offsets=True, inheritance=True, type_contains=[],
        method_indices=[b['readerMethod']['index'] for b in branches], method_rvas=[],
        type_slots=[int(b['typeSlot'],16) for b in branches],
        method_slots=[int(b['methodSlot'],16) for b in branches], string_slots=[],
        method_names=[], method_name_contains=[], disassemble=0))
    if info['unmatchedTypes'] or info['files'] != extension['clientFiles']:
        raise ValueError('剧情脚本固定客户端或类型缺失')
    types = {t['name']:t for t in info['methods']}

    def copy_type(t):
        result = {k:v for k,v in t.items() if k in ['kind','name','typeIndex']}
        if 'elementType' in t:
            result['elementType'] = copy_type(t['elementType'])
        return result

    for name, definition in extension['classes'].items():
        actual = types[name]
        fields = [{'name':f['name'],'declaringType':f['declaringType'],
                   'fieldIndex':f['fieldIndex'],'type':copy_type(f['nativeType'])}
                  for f in actual['instanceFields']]
        if actual['typeIndex'] != definition['typeIndex'] or fields != definition['fields']:
            raise ValueError('剧情原生继承字段顺序或类型不符')
    values = {str(f['constant']['value']):f['name'] for f in types['SubMissionState']['fields']
              if 'value' in f.get('constant',{})}
    if values != extension['enums']['24496']['values']:
        raise ValueError('子任务状态枚举不符')
    type_slots = {s['slot']:s['typeName'] for s in info['typeInfoSlots']}
    method_slots = {s['slot']:s['method'] for s in info['methodInfoSlots']}
    readers = {m['index']:m for m in info['selectedMethods']}
    for branch in branches:
        expected = branch['readerMethod']
        if (type_slots[branch['typeSlot']] != expected['typeName']
                or method_slots[branch['methodSlot']] != branch['factoryMethod']
                or any(readers[expected['index']].get(k) != v for k,v in expected.items())):
            raise ValueError('剧情分支分配类型、读取方法或注册方法不符')
    # Each registration point and preceding method slot is checked within a
    # complete PE exception-table function, including the larger factory initializer.
    import pefile
    import capstone
    path = client / 'GameAssembly.dll'
    pe = pefile.PE(str(path), fast_load=True)
    try:
        decoder = capstone.Cs(capstone.CS_ARCH_X86, capstone.CS_MODE_64)
        with path.open('rb') as stream, mmap.mmap(stream.fileno(),0,access=mmap.ACCESS_READ) as data:
            def read(rva,size):
                section = pe.get_section_by_rva(rva)
                if section is None or rva-section.VirtualAddress+size > section.SizeOfRawData:
                    raise ValueError('剧情原生证据越过文件范围')
                offset = pe.get_offset_from_rva(rva)
                return data[offset:offset+size]
            table = pe.OPTIONAL_HEADER.DATA_DIRECTORY[3]
            bounds = {s:e for s,e,u in struct.iter_unpack('<III',read(table.VirtualAddress,table.Size))}
            for function in extension['functions'].values():
                begin,end = int(function['beginRVA'],16),int(function['endRVA'],16)
                if bounds.get(begin) != end or not begin < end < begin+200000:
                    raise ValueError('剧情分配函数边界不符')
                if hashlib.sha256(read(begin,end-begin)).hexdigest() != function['sha256']:
                    raise ValueError('剧情分配函数完整字节不符')
                for point, expected in function['points'].items():
                    rva = int(point,16)
                    if not begin <= rva < end:
                        raise ValueError('剧情分配点跨出函数')
                    ins = next(decoder.disasm(read(rva,min(15,end-rva)),pe.OPTIONAL_HEADER.ImageBase+rva),None)
                    if ins is None or ins.mnemonic+' '+ins.op_str != expected:
                        raise ValueError('剧情分配槽或分支编号不符')
    finally:
        pe.close()
    return {'classes':8,'taskBranches':5,'predicateBranches':1,'registrationPoints':12,
            'functions':extension['functions'],'files':info['files']}


def recover(args):
    profile = load_profile()
    paths = args.paths or list(profile['records'])
    if not paths or len(paths) != len(set(paths)) or any(p not in profile['records'] for p in paths):
        raise ValueError('仅接受两份固定剧情脚本的唯一规范路径')
    client = args.client_dir.resolve()
    output = design.outside_client(args.output,client)
    destination = design.outside_client(args.destination,client) if args.destination else None
    native = verify_native(client,profile)
    files, records, checksums = {}, [], {}
    with tempfile.TemporaryDirectory(prefix='hkrpg-mission-graphs-') as directory:
        temp = Path(directory)
        source = design.extract(SimpleNamespace(client_dir=client,paths=paths,paths_file=None,
                    extract_dir=temp/'raw',output=temp/'index.json'))
        if source['files'] != profile['clientFiles'] or source['indexSHA256'] != profile['indexSHA256']:
            raise ValueError('剧情脚本索引或客户端身份不符')
        for row in source['records']:
            expected = profile['records'][row['sourcePath']]
            if row['status'] != 'read' or any(row.get(k) != v for k,v in expected.items()):
                raise ValueError('剧情记录的索引键、位置、长度或哈希不符')
            raw = Path(row['extractedFile']).read_bytes()
            if len(raw) != expected['length'] or hashlib.sha256(raw).hexdigest() != expected['sha256']:
                raise ValueError('剧情记录在读取后发生变化')
            graph = decode_graph(raw,profile)
            resource = resource_json(graph,profile)
            content = groups.encode(resource)
            checksums[row['sourcePath']] = hashlib.sha256(content).hexdigest()
            if destination:
                target = (destination / row['sourcePath']).resolve()
                if not target.is_relative_to(destination) or target == output:
                    raise ValueError('剧情JSON输出越界或与报告冲突')
                files[target] = content
            records.append({k:v for k,v in row.items() if k != 'extractedFile'} |
                {'fullyConsumed':True,'graph':graph,'resource':resource,'resourceSHA256':checksums[row['sourcePath']]})
    report = {'clientExecutableStarted':False,'clientModified':False,'clientAccepted':False,
              'scriptsExecuted':False,'completeGameplay':False,'jsonInstalled':bool(destination),
              'profileSHA256':PROFILE_SHA256,'clientFiles':source['files'],
              'indexSHA256':source['indexSHA256'],'verifiedChunks':source['verifiedChunks'],
              'nativeFieldAndBranchProof':native,'records':records}
    if destination:
        supplement = {'clientVersion':'4.6.0','source':'operator-local-client','sourceRegion':'CN',
                      'clientFiles':source['files'],'indexSHA256':source['indexSHA256'],
                      'profileSHA256':PROFILE_SHA256,'clientAcceptance':False,
                      'scriptsExecuted':False,'completeGameplay':False,'files':checksums}
        content = groups.encode(supplement)
        target = destination / ('resource-supplement-client46-missions-'+hashlib.sha256(content).hexdigest()[:12]+'.json')
        if target == output:
            raise ValueError('剧情补充清单与报告冲突')
        files[target] = content
        report['supplementFile'] = str(target)
    files[output] = groups.encode(report)
    groups.write_new_files(files)
    return report


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--client-dir',type=Path,required=True)
    parser.add_argument('--paths',nargs='*',default=[])
    parser.add_argument('--destination',type=Path,help='只新建已核对的JSON及补充清单，不覆盖不同内容')
    parser.add_argument('--output',type=Path,required=True)
    args = parser.parse_args()
    result = recover(args)
    print(json.dumps({'fullyConsumed':len(result['records']),'jsonInstalled':result['jsonInstalled'],
                      'clientAccepted':False,'scriptsExecuted':False}))
