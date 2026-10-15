"""只读验收本机热更新后的3011014八份完整Act；不启动游戏或安装资源。"""
import argparse
import base64
import hashlib
import importlib.util
import json
import struct
import tempfile
import zlib
from pathlib import Path
from types import SimpleNamespace

ROOT = Path(__file__).resolve().parents[1]
PROFILE = ROOT / 'scripts/data/client46-recover-acts.json'
PROFILE_SHA256 = 'ac342911b1c46dfcffe73170c67d467f9402eb99524d22aada0451186cffce90'
COMMIT = '724b139d8c9c32d12552eb95745a4fee72bfe48b'
FIXTURE = ROOT / 'src/test/resources/fixtures/daily-recover-delivery-3011014-4.6.json'
ACTS = (301101401, 301101402, *range(301101406, 301101412))


def module(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    result = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(result)
    return result


g61 = module('recover_acts_g61', ROOT / 'scripts/recover-client-g61-npc-graphs.py')
design = g61.rest.missions.design


def checksum(raw):
    return hashlib.sha256(raw).hexdigest()


def load_profile():
    extension = g61.pinned_json(PROFILE, PROFILE_SHA256)
    profile = g61.load_profile()
    if (extension['schema'] != 1 or extension['g61ProfileSHA256'] != g61.PROFILE_SHA256
            or extension['clientFiles'] != profile['clientFiles']):
        raise ValueError('回收物Act基础原生证据不符')
    for name, definition in extension['nativeFieldLayouts'].items():
        parsed = {
            'typeIndex': definition['typeIndex'],
            'fields': [{k: field[k] for k in ('name', 'declaringType', 'fieldIndex', 'type')}
                       for field in definition['fields']]}
        if name in profile['classes'] and profile['classes'][name] != parsed:
            raise ValueError('回收物Act类型与基础定义冲突')
        profile['classes'][name] = parsed
    for branch in extension['branches']:
        name, tag = branch['name'], str(branch['tag'])
        if tag in profile['tasks']:
            raise ValueError('回收物Act任务编号与基础定义冲突')
        profile['tasks'][tag] = {'name': name, 'typeIndex': profile['classes'][name]['typeIndex'],
                                'noReaderPayload': False}
        profile['jsonTypeNames'][name] = 'RPG.GameCore.' + name
    profile['indexSHA256'] = extension['indexSHA256']
    profile['records'] = extension['records']
    profile['fixedPointJSONLiterals'] = extension['fixedPointJSONLiterals']
    profile['recoverActExtension'] = extension
    return profile


def originals():
    fixture = json.loads(FIXTURE.read_text(encoding='utf-8'))
    if fixture['sourceCommit'] != COMMIT:
        raise ValueError('回收物Act原件提交不符')
    result = {}
    for performance in ACTS:
        path = f'Config/Level/Mission/3011014/Act/Act{performance}.json'
        entry = fixture['sourceFiles'][path]
        raw = (base64.b64decode(entry['sourceBlobBase64'], validate=True)
               if 'sourceBlobBase64' in entry else
               zlib.decompress(base64.b64decode(entry['sourceBlobDeflateBase64'], validate=True)))
        if checksum(raw) != entry['sourceBlobSHA256']:
            raise ValueError('回收物Act完整原件摘要不符: ' + path)
        result[path] = raw
    return result


def verify_native(client, profile):
    # The inherited parser is checked against its original profile, not upgraded implicitly.
    base = g61.verify_native(client, g61.load_profile())
    extension = profile['recoverActExtension']
    native = module('recover_acts_native', ROOT / 'scripts/analyze-client-46.py')
    info = native.extract(SimpleNamespace(client_dir=client,
        types=list(extension['nativeFieldLayouts']), fields=True, field_offsets=True,
        inheritance=True, type_contains=[], method_indices=[m['index'] for m in extension['methods']],
        method_rvas=[], type_slots=[int(b['typeSlot'], 16) for b in extension['branches']],
        method_slots=[int(b['methodSlot'], 16) for b in extension['branches']],
        string_slots=[], method_names=[], method_name_contains=[], disassemble=0))
    if (info['files'] != profile['clientFiles'] or info['unmatchedTypes']
            or info['selectedMethods'] != extension['methods']):
        raise ValueError('回收物Act本机文件或读取方法不符')
    for actual in info['methods']:
        fields = [{k: field[k] for k in ('name', 'declaringType', 'fieldIndex', 'instanceOffset')}
                  | {'type': g61.type_description(field['nativeType'])}
                  for field in actual['instanceFields']]
        if extension['nativeFieldLayouts'][actual['name']] != {
                'typeIndex': actual['typeIndex'], 'fields': fields}:
            raise ValueError('回收物Act原生字段布局不符')
    methods = {row['index']: row for row in info['selectedMethods']}
    slots = {row['slot']: row['method'] for row in info['methodInfoSlots']}
    types = {row['slot']: row['typeName'] for row in info['typeInfoSlots']}
    import pefile
    pe = pefile.PE(str(client / 'GameAssembly.dll'), fast_load=True)
    try:
        table = pe.OPTIONAL_HEADER.DATA_DIRECTORY[3]
        bounds = {b: e for b, e, _ in struct.iter_unpack('<III', pe.get_data(table.VirtualAddress, table.Size))}
        for function in extension['functions'].values():
            begin, end = int(function['beginRVA'], 16), int(function['endRVA'], 16)
            if (bounds.get(begin) != end or
                    checksum(pe.get_data(begin, end - begin)) != function['sha256']):
                raise ValueError('回收物Act完整原生函数边界或字节不符')
        for branch in extension['branches']:
            if (slots[branch['methodSlot']] != methods[branch['factoryIndex']]
                    or types[branch['typeSlot']] != branch['name']):
                raise ValueError('回收物Act工厂或分配类型槽不符')
            at = int(branch['assignmentRVA'], 16) - 7
            raw = pe.get_data(at, 14)
            if (raw[:3] != bytes.fromhex('488b15') or raw[7:10] != bytes.fromhex('488990')
                    or at + 7 + struct.unpack_from('<i', raw, 3)[0] != int(branch['methodSlot'], 16)
                    or struct.unpack_from('<i', raw, 10)[0] != 0x20 + branch['tag'] * 8):
                raise ValueError('回收物Act实际任务工厂表赋值不符')
    finally:
        pe.close()
    return {'inherited': base, 'clientFiles': info['files'], 'branches': extension['branches'],
            'completeFunctions': extension['functions'], 'nativeFieldLayoutsVerified': True}


def verify(client, resources, output):
    client = client.resolve(strict=True)
    output = design.outside_client(output, client)
    resources = resources.resolve(strict=True)
    if output.exists():
        raise FileExistsError('拒绝覆盖已有验收报告')
    profile = load_profile()
    sources = originals()
    if set(sources) != set(profile['records']):
        raise ValueError('回收物Act记录范围不符')
    native = verify_native(client, profile)
    records = []
    before = {}
    with tempfile.TemporaryDirectory(prefix='hkrpg-recover-acts-') as temp:
        directory = Path(temp)
        source = design.extract(SimpleNamespace(client_dir=client, paths=list(sources),
            paths_file=None, extract_dir=directory / 'raw', output=directory / 'index.json',
            index_sha256=profile['indexSHA256']))
        if source['files'] != profile['clientFiles'] or source['indexSHA256'] != profile['indexSHA256']:
            raise ValueError('回收物Act客户端或索引不符')
        for row in source['records']:
            path = row['sourcePath']
            if (row['status'] != 'read' or
                    {k: row[k] for k in ('entryKey', 'length', 'sha256')} != profile['records'][path]):
                raise ValueError('回收物Act完整记录字节不符: ' + path)
            raw = Path(row['extractedFile']).read_bytes()
            value = g61.resource_json(g61.decode_graph(raw, profile), profile)
            original = json.loads(sources[path])
            target = resources / path
            installed = target.read_bytes()
            before[path] = checksum(installed)
            if value != original or json.loads(installed) != original:
                raise ValueError('回收物Act完整对象与原件或运营资源不符: ' + path)
            records.append({k: v for k, v in row.items() if k != 'extractedFile'} | {
                'fullyConsumed': True, 'wholeObjectEqualFixedSource': True,
                'wholeObjectEqualInstalled': True, 'fixedSourceSHA256': checksum(sources[path]),
                'installedSHA256': before[path]})
    if before != {p: checksum((resources / p).read_bytes()) for p in before}:
        raise ValueError('验收期间运营资源变化')
    report = {'passed': True, 'sourceCommit': COMMIT, 'profileSHA256': PROFILE_SHA256,
              'clientFiles': source['files'], 'indexSHA256': source['indexSHA256'],
              'indexFullyConsumed': source['indexFullyConsumed'], 'indexEntryCount': source['indexEntryCount'],
              'verifiedChunks': source['verifiedChunks'], 'records': records, 'native': native,
              'clientModified': False, 'jsonInstalled': False, 'clientActExecuted': False,
              'clientUIVerified': False}
    output.parent.mkdir(parents=True, exist_ok=True)
    with output.open('x', encoding='utf-8') as stream:
        json.dump(report, stream, ensure_ascii=False, indent=2)
        stream.write('\n')
    return report


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--client-dir', type=Path, required=True)
    parser.add_argument('--resources', type=Path, default=ROOT / 'resources/4.6')
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    result = verify(args.client_dir, args.resources, args.output)
    print(json.dumps({'passed': result['passed'], 'completeActs': len(result['records']),
                      'bytes': sum(row['length'] for row in result['records']), 'clientUIVerified': False}))
