"""只读恢复固定客户端的时钟乐园原始对象，不执行剧情。"""
import importlib.util
import argparse
import hashlib
import json
import struct
import tempfile
from pathlib import Path
from types import SimpleNamespace

PROFILE = Path(__file__).with_name('data') / 'client46-clock-park.json'
PROFILE_SHA256 = 'e36df5a33f023f0bea6d08dac333420b8176cb77640611af0ef6f47b65037356'


def module(name, filename):
    spec = importlib.util.spec_from_file_location(name, Path(__file__).with_name(filename))
    value = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(value)
    return value


missions = module('clock_park_missions', 'recover-client-mission-graphs.py')
graphs = missions.graphs
design = missions.design
files = missions.groups


def load_profile():
    raw = PROFILE.read_bytes().replace(b'\r\n', b'\n')
    if hashlib.sha256(raw).hexdigest() != PROFILE_SHA256:
        raise ValueError('时钟乐园字段证据已变化，拒绝沿用')
    extension = json.loads(raw)
    if extension['missionProfileSHA256'] != missions.PROFILE_SHA256:
        raise ValueError('时钟乐园基础剧情证据不匹配')
    profile = missions.load_profile()
    for table in ('classes', 'tasks'):
        if set(profile[table]) & set(extension[table]):
            raise ValueError('时钟乐园与基础字段证据冲突')
        profile[table].update(extension[table])
    profile['clockPark'] = extension
    return profile


def decode(data, kind, profile=None):
    if kind not in ('ClockParkStoryConfig', 'LevelGraphConfig'):
        raise ValueError('未知时钟乐园对象')
    reader = graphs.Reader(data, profile or load_profile())
    value = reader.read({'kind': 18, 'name': kind})
    if reader.pos != len(data):
        raise ValueError('时钟乐园记录未完整读取')
    return value


def verify_native(client, profile):
    extension = profile['clockPark']
    base_proof = missions.verify_native(client, profile)
    native = module('clock_park_native', 'analyze-client-46.py')
    branches = list(extension['tasks'].values())
    actual = native.extract(SimpleNamespace(
        client_dir=client, types=list(extension['classes']), fields=True,
        field_offsets=True, inheritance=True, type_contains=[],
        method_indices=[m['index'] for m in extension['readers'].values()], method_rvas=[],
        type_slots=[int(b['typeSlot'], 16) for b in branches],
        method_slots=[int(b['methodSlot'], 16) for b in branches], string_slots=[],
        method_names=[], method_name_contains=[], disassemble=0))
    if actual['unmatchedTypes'] or actual['files'] != extension['clientFiles']:
        raise ValueError('时钟乐园客户端身份或类型不匹配')

    def compact(t):
        result = {k: v for k, v in t.items() if k in ('kind', 'name', 'typeIndex')}
        if 'elementType' in t:
            result['elementType'] = compact(t['elementType'])
        return result

    for row in actual['methods']:
        fields = [{'name': f['name'], 'fieldIndex': f['fieldIndex'],
                   'declaringType': f['declaringType'], 'type': compact(f['nativeType'])}
                  for f in row['instanceFields']]
        if extension['classes'][row['name']] != {'typeIndex': row['typeIndex'], 'fields': fields}:
            raise ValueError('时钟乐园继承字段或类型不匹配')
    readers = {m['index']: m for m in actual['selectedMethods']}
    for expected in extension['readers'].values():
        if any(readers[expected['index']].get(k) != v for k, v in expected.items()):
            raise ValueError('时钟乐园对象读取方法不匹配')
    types = {row['slot']: row['typeName'] for row in actual['typeInfoSlots']}
    methods = {row['slot']: row['method'] for row in actual['methodInfoSlots']}
    for branch in branches:
        if (types[branch['typeSlot']] != branch['name']
                or methods[branch['methodSlot']] != branch['factoryMethod']):
            raise ValueError('时钟乐园任务分配类型或工厂槽不匹配')
    import pefile
    import capstone
    pe = pefile.PE(str(client / 'GameAssembly.dll'), fast_load=True)
    try:
        directory = pe.OPTIONAL_HEADER.DATA_DIRECTORY[3]
        table = pe.get_data(directory.VirtualAddress, directory.Size)
        if len(table) != directory.Size or len(table) % 12:
            raise ValueError('原生函数边界表不完整')
        bounds = {s: e for s, e, u in struct.iter_unpack('<III', table)}
        decoder = capstone.Cs(capstone.CS_ARCH_X86, capstone.CS_MODE_64)
        for function in extension['functions'].values():
            begin, end = int(function['beginRVA'], 16), int(function['endRVA'], 16)
            if bounds.get(begin) != end or not begin < end < begin + 200000:
                raise ValueError('时钟乐园原生函数边界不符')
            section = pe.get_section_by_rva(begin)
            if section is None or begin - section.VirtualAddress + end - begin > section.SizeOfRawData:
                raise ValueError('时钟乐园原生函数超出文件字节')
            raw = pe.get_data(begin, end - begin)
            if hashlib.sha256(raw).hexdigest() != function['sha256']:
                raise ValueError('时钟乐园原生函数完整字节不符')
            for point, expected in function['points'].items():
                rva = int(point, 16)
                if not begin <= rva < end:
                    raise ValueError('时钟乐园任务分配点超出函数')
                ins = next(decoder.disasm(raw[rva - begin:rva - begin + 15], rva), None)
                if ins is None or ins.mnemonic + ' ' + ins.op_str != expected:
                    raise ValueError('时钟乐园任务编号或分配槽不符')
    finally:
        pe.close()
    return {'classes': 14, 'taskBranches': 10, 'readers': 14, 'functions': 25,
            'registrationPoints': 20, 'baseProof': base_proof, 'files': actual['files']}


def recover(args):
    profile = load_profile()
    extension = profile['clockPark']
    client = args.client_dir.resolve()
    output = design.outside_client(args.output, client)
    destination = design.outside_client(args.destination, client) if args.destination else None
    proof = verify_native(client, profile)
    records = []
    with tempfile.TemporaryDirectory(prefix='hkrpg-clock-park-') as directory:
        root = Path(directory)
        source = design.extract(SimpleNamespace(client_dir=client, paths=list(extension['records']),
                    paths_file=None, extract_dir=root / 'raw', output=root / 'index.json'))
        if source['files'] != extension['clientFiles'] or source['indexSHA256'] != extension['indexSHA256']:
            raise ValueError('时钟乐园客户端或资源索引不符')
        if [r['sourcePath'] for r in source['records']] != list(extension['records']):
            raise ValueError('时钟乐园原始记录集合不完整或顺序改变')
        for row in source['records']:
            expected = extension['records'][row['sourcePath']]
            if row['status'] != 'read' or any(row.get(k) != v for k, v in expected.items() if k != 'kind'):
                raise ValueError('时钟乐园记录索引、长度或哈希不符')
            raw = Path(row['extractedFile']).read_bytes()
            if len(raw) != expected['length'] or hashlib.sha256(raw).hexdigest() != expected['sha256']:
                raise ValueError('时钟乐园原始记录在读取后发生变化')
            value = decode(raw, expected['kind'], profile)
            records.append({k: v for k, v in row.items() if k not in ('extractedFile', 'sourceFile', 'status')}
                           | {'kind': expected['kind'], 'fullyConsumed': True, 'value': value})
    graph_paths = {r['sourcePath'] for r in records if r['kind'] == 'LevelGraphConfig'}
    links = [r['value']['LevelGraphConfigPath'] for r in records if r['kind'] == 'ClockParkStoryConfig']
    if len(links) != 27 or len(set(links)) != 27 or set(links) != graph_paths:
        raise ValueError('时钟乐园剧情引用不是完整一一对应')
    resource = {'schema': 'hkrpg.clock-park.original-objects.v1', 'profileSHA256': PROFILE_SHA256,
                'clientFiles': extension['clientFiles'], 'indexSHA256': extension['indexSHA256'],
                'records': records}
    content = files.encode(resource)
    checksum = hashlib.sha256(content).hexdigest()
    report = {'clientExecutableStarted': False, 'clientModified': False, 'clientAccepted': False,
              'scriptsExecuted': False, 'completeGameplay': False, 'unityJsonRecreated': False,
              'jsonInstalled': bool(destination), 'profileSHA256': PROFILE_SHA256,
              'clientFiles': source['files'], 'indexSHA256': source['indexSHA256'],
              'verifiedChunks': source['verifiedChunks'], 'nativeProof': proof,
              'fullyConsumedRecords': len(records), 'configGraphPairs': 27,
              'resourceSHA256': checksum, 'resource': resource}
    writes = {}
    if destination:
        target = (destination / 'ServerData/ClockParkStories.json').resolve()
        if not target.is_relative_to(destination) or target == output:
            raise ValueError('时钟乐园资源与报告路径冲突')
        writes[target] = content
        supplement = {'clientVersion': '4.6.0', 'source': 'operator-local-client', 'sourceRegion': 'CN',
            'clientFiles': source['files'], 'indexSHA256': source['indexSHA256'],
            'profileSHA256': PROFILE_SHA256, 'clientAcceptance': False, 'scriptsExecuted': False,
            'completeGameplay': False, 'files': {'ServerData/ClockParkStories.json': checksum}}
        manifest = files.encode(supplement)
        target = (destination / ('resource-supplement-client46-clockpark-' + hashlib.sha256(manifest).hexdigest()[:12] + '.json')).resolve()
        if not target.is_relative_to(destination) or target == output:
            raise ValueError('时钟乐园补充清单与报告路径冲突')
        writes[target] = manifest
        report['supplementFile'] = str(target)
    writes[output] = files.encode(report)
    files.write_new_files(writes)
    return report


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--client-dir', type=Path, required=True)
    parser.add_argument('--destination', type=Path, help='只新建本服原始对象包和补充清单，不重建Unity JSON')
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    result = recover(args)
    print(json.dumps({k: result[k] for k in ('fullyConsumedRecords', 'configGraphPairs', 'jsonInstalled',
                                          'unityJsonRecreated', 'scriptsExecuted', 'clientAccepted')}))
