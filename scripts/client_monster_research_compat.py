"""为固定 4.6 客户端准备旧研究任务兼容数据；不得在游戏运行时安装。"""
import argparse
import base64
import hashlib
import importlib.util
import json
import os
import re
import struct
import tempfile
import zlib
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
FIXTURE = ROOT / 'scripts/fixtures/monster-research-client-4.6.json'
FIXTURE_SHA256 = '7c48b27c164591a1af36cba92e09f394671d323c3236a08ca9c5154a640e9599'
CLIENT_DIR = Path('StarRail_Data/Persistent/DesignData/Windows')
MAINS = (8000170, 8000171, 8000172, 8000173, 8000174, 8000175, 8000177)
OFFSETS = (601, 344, 511, 350, 583, 517, 523)
COSTS = (
    ((111011, 1), (181002, 1), (180005, 1)), ((111011, 1), (181003, 1), (180001, 1)),
    ((111001, 1), (181005, 1), (180004, 1)), ((111001, 1), (181008, 1), (180005, 1)),
    ((112011, 1), (181010, 1), (180005, 1)), ((112011, 1), (181001, 1), (180002, 1)),
    ((181002, 1), (181009, 1), (181003, 1)),
)
TOPIC_HASHES = (4674647842233983734, 5007463842034624602, 1834554150998599499)
REPORT_PATH = b'Ui.Activity.ActivityMonsterResearch.ActivityMonsterResearchEnterPage'


def require(value, reason):
    if not value:
        raise ValueError(reason)


def var(value):
    require(type(value) is int and 0 <= value < 2**64, '变长整数越界')
    out = bytearray()
    while value >= 128:
        out.append((value & 127) | 128)
        value >>= 7
    out.append(value)
    return bytes(out)


def string(value):
    raw = value.encode('utf-8')
    return var(len(raw)) + raw


def tasks(*encoded):
    return var(len(encoded) * 2) + b''.join(encoded)


def event(tag, value):
    return var(tag) + var(2) + var(4) + string(value)


def consume(sub, confirm, cancel):
    return var(1099) + var(26) + var(sub) + confirm + cancel


def sequence(*encoded):
    return var(4) + tasks(*encoded)


def module(name, filename):
    spec = importlib.util.spec_from_file_location(name, ROOT / 'scripts' / filename)
    result = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(result)
    return result


def graph_reader():
    graphs = module('research_native_graphs', 'recover-client-g61-npc-graphs.py')
    profile = graphs.load_profile()
    array = {'kind': 29, 'elementType': {'kind': 18, 'name': 'TaskConfig'}}
    profile['tasks']['1789'] = {'name': 'MonsterResearchSubmit', 'noReaderPayload': False}
    profile['classes']['MonsterResearchSubmit'] = {'fields': [
        {'name': 'TaskEnabled', 'type': {'kind': 2}},
        {'name': 'ActivityMonsterResearchID', 'type': {'kind': 9}},
        *[{'name': name, 'type': array} for name in ('OnQuestion1', 'OnQuestion2', 'OnQuestion3', 'OnCancel')],
    ]}
    return graphs, profile, array


def original_submit(raw, offset, main, stage):
    graphs, profile, array = graph_reader()
    reader = graphs.Reader(raw, profile)
    reader.pos = offset
    require((reader.uvar(), reader.uvar(), reader.uvar()) == (1789, 62, stage), '旧研究提交节点不符')
    sections = []
    for _ in range(4):
        start = reader.pos
        value = reader.read(array)
        sections.append((raw[start:reader.pos], value))
    identity = lambda seq: [(item['$type'], item['CustomString']['Value']) for item in seq]
    submit = f'Submit_{main * 100 + 1}'
    finish = f'MainFinish_{main}'
    expected = ([('TriggerCustomStringOnDialogEnd', finish), ('TriggerCustomString', submit)]
                if stage == 7 else [('TriggerCustomString', submit), ('TriggerCustomString', finish)])
    require(identity(sections[0][1]) == expected, '原始确认回调变化')
    require(identity(sections[3][1]) == [('TriggerCustomString', f'Cancel_{main * 100 + 1}'),
                                         ('TriggerCustomString', f'FirstTimeFail_{main * 100 + 1}')],
            '原始取消回调变化')
    require(all(identity(value) == expected for _, value in sections[1:3]) if stage == 7
            else all(not value for _, value in sections[1:3]), '研究课题分支变化')
    return reader.pos, sections


def replace_report(raw, start, end):
    require(raw[start:end].count(REPORT_PATH) == 1, '旧报告界面节点变化')
    # 客户端不再携带这个旧报告页面；不自动代填报告，也不推进任务。
    return raw[:start] + event(3598, 'ResearchReportUnavailable') + raw[end:]


def patch_initial(raw, main, stage, offset):
    end, sections = original_submit(raw, offset, main, stage)
    if main == 8000170:
        raw = replace_report(raw, 817, 902)
    if stage != 7:
        replacement = consume(main * 100 + 4, sections[0][0], sections[3][0])
        return raw[:offset] + replacement + raw[end:]
    # 原任务的三个课题会产生不同的报告；让玩家显式选择并将选择传回服务端。
    options = []
    for i, title in enumerate(TOPIC_HASHES, 1):
        options.append(var(0) + var(14) + var(0) + var(title) + var(40) + var(4)
                       + string(f'ResearchChoice_8000177_{i}'))
    replacement = var(1954) + var(2) + tasks(*options)
    require(raw[:3] == b'\x03\x00\x0c', '第七阶段图序列头变化')
    result = raw[:2] + var(18) + raw[3:offset] + replacement + raw[end:]
    for i in (1, 2, 3):
        confirm = tasks(event(3600, f'ResearchFinish_8000177_{i}'),
                        event(3598, 'Submit_800017701'))
        result += sequence(event(3795, f'ResearchChoice_8000177_{i}'),
                           consume(800017705, confirm, sections[3][0]))
    return result


def patch_partial(raw, main, costs):
    graphs, profile, _ = graph_reader()
    reader = graphs.Reader(raw, profile)
    offset = 420 if main == 8000177 else 330
    reader.pos = offset
    mask, second = reader.uvar(), reader.uvar()
    data_start = reader.pos
    sub = main * 100 + (5 if main == 8000177 else 4)
    require(second == 0 and not mask & (1 << 27) and (reader.uvar(), reader.uvar()) == (sub, main),
            '任务条件原件变化')
    require(reader.read({'kind': 14}) == f'Config/Level/Mission/{main}/Mission_{sub}.json', '任务路径变化')
    require((reader.uvar(), reader.uvar(), reader.sint()) == (10001, 10001001, 1), '任务地点变化')
    require(reader.read({'kind': 29, 'elementType': {'kind': 9}}) == [main], '任务接取条件变化')
    require((reader.sint(), reader.sint(), reader.read({'kind': 14})) == (2, 2, f'Mission_{sub}'),
            '原始完成条件变化')
    insert = reader.pos
    require(reader.uvar() == 1 and reader.read({'kind': 2}) is False, '原始任务进度或显示标记变化')
    if main != 8000177:
        require(reader.uvar() == 1, '原始任务结束效果变化')
    require(reader.pos == len(raw), '任务记录尾部变化')
    payload = var(len(costs) * 2) + b''.join(var(3) + var(item) + var(amount) for item, amount in costs)
    return raw[:offset] + var(mask | (1 << 27)) + var(second) + raw[data_start:insert] + payload + raw[insert:]


PERFORMANCE_FIELDS = (('id', 9), ('path', 14), ('skip', 17), ('change', 17), ('character', 14),
                      ('start', 17), ('end', 17), ('crack', 2), ('plane', 9), ('floor', 9), ('intro', 2))


def performance_rows(raw):
    graphs, profile, _ = graph_reader()
    reader = graphs.Reader(raw, profile)
    count = reader.integer(32, True)
    require(count == 12585, '演出表行数变化')
    result = {}
    for _ in range(count):
        start, mask = reader.pos, reader.uvar()
        require(mask >> len(PERFORMANCE_FIELDS) == 0, '演出表未知字段')
        row = {}
        for bit, (name, kind) in enumerate(PERFORMANCE_FIELDS):
            if mask & (1 << bit):
                row[name] = reader.sint() if kind == 17 else reader.read({'kind': kind})
        require(row.get('id', 0) > 0 and row['id'] not in result, '演出编号缺失或重复')
        result[row['id']] = (row, raw[start:reader.pos])
    require(reader.pos == len(raw), '演出表尾部变化')
    return result


def encode_performance(row):
    require(not set(row) - {name for name, _ in PERFORMANCE_FIELDS}, '演出字段未知')
    result = bytearray(var(sum(1 << bit for bit, (name, _) in enumerate(PERFORMANCE_FIELDS) if name in row)))
    for name, kind in PERFORMANCE_FIELDS:
        if name not in row:
            continue
        value = row[name]
        if kind == 14:
            result += string(value)
        elif kind == 2:
            require(type(value) is bool, '演出布尔字段非法')
            result.append(int(value))
        elif kind == 17:
            require(-(2**31) <= value < 2**31, '演出枚举越界')
            result += var((value << 1) ^ (value >> 31))
        else:
            result += var(value)
    return bytes(result)


def patch_performances(raw):
    rows = performance_rows(raw)
    changed = {800017003: 800017001, 800017102: 800017101, 800017702: 800017701}
    added = {800017202: 800017201, 800017302: 800017301, 800017402: 800017401,
             800017502: 800017501, 800017706: 800017702}
    require(not set(added) & set(rows), '研究重试演出编号已存在')
    for target, source in (changed | added).items():
        require(source in rows and (target in rows if target in changed else True), '研究演出原件缺失')
        for identity in (source, target) if target in changed else (source,):
            row = rows[identity][0]
            require(row.get('change') == 1 and row.get('character') == ''
                    and row.get('plane') == 10001 and row.get('floor') == 10001001,
                    '研究演出的地点或模式变化')
    output = bytearray(var((len(rows) + len(added)) * 2))
    for ident, (row, original) in rows.items():
        output += encode_performance(dict(row, path=rows[changed[ident]][0]['path'])) if ident in changed else original
    for ident, source in added.items():
        output += encode_performance(dict(rows[source][0], id=ident))
    return bytes(output)


def patch_final_mission(raw):
    graphs, profile, _ = graph_reader()
    original = graphs.decode_graph(raw, profile)
    require(original['Type'] == 1 and len(original['OnStartSequece']) == 1
            and [item['$type'] for item in original['OnStartSequece'][0]['TaskList']]
                == ['WaitCustomString', 'FinishPerformanceMission'], '最终任务图变化')
    require(original['OnStartSequece'][0]['TaskList'][0]['CustomString']['Value'] == 'MainFinish_8000177'
            and original['OnStartSequece'][0]['TaskList'][1]['Key'] == 'Mission_800017705', '最终任务事件变化')
    choices = [sequence(event(3795, f'ResearchFinish_8000177_{i}'),
                        var(1367) + var(2) + string(f'Research{chr(64 + i)}_800017705')) for i in (1, 2, 3)]
    result = var(11) + var(0) + tasks(*choices) + var(2)
    require(len(graphs.decode_graph(result, profile)['OnStartSequece']) == 3, '最终任务图编码失败')
    return result


def sha(raw):
    return hashlib.sha256(raw).hexdigest()


def fixture():
    raw = FIXTURE.read_bytes().replace(b'\r\n', b'\n')
    if sha(raw) != FIXTURE_SHA256:
        raise ValueError('研究任务客户端固定证据变化')
    value = json.loads(raw)
    if value['schema'] != 1 or value['sourceCommit'] != '724b139d8c9c32d12552eb95745a4fee72bfe48b':
        raise ValueError('研究任务来源不符')
    records = {}
    for path, row in value['records'].items():
        data = base64.b64decode(row['data'], validate=True)
        if row['encoding'] == 'zlib-base64':
            data = zlib.decompress(data)
        elif row['encoding'] != 'base64':
            raise ValueError('未知记录编码')
        if len(data) != row['bytes'] or sha(data) != row['sha256']:
            raise ValueError('研究任务原记录损坏')
        records[path] = data
    return value, records


def patch_record(path, raw):
    _, records = fixture()
    if path not in records or records[path] != raw:
        raise ValueError('拒绝非固定原始记录')
    for stage, (main, offset, costs) in enumerate(zip(MAINS, OFFSETS, COSTS), 1):
        if path == f'Config/Level/Mission/{main}/Act/Act{main * 100 + 1}.json':
            return patch_initial(raw, main, stage, offset)
        if path == f'Config/Level/Mission/{main}/MissionInfo_{main}.partial.json':
            return patch_partial(raw, main, costs)
    if path == 'ExcelOutput/PerformanceE.json':
        return patch_performances(raw)
    if path == 'Config/Level/Mission/8000177/Mission_800017705.json':
        return patch_final_mission(raw)
    if path == 'Config/Level/Mission/8000177/Mission_800017703.json':
        old, new = var(800017702), var(800017706)
        require(raw.count(old) == 1, '最终报告演出编号不唯一')
        return raw.replace(old, new)
    if path == 'Config/Level/Mission/8000170/Act/Act800017003.json':
        return replace_report(raw, 101, 186)
    if path == 'Config/Level/Mission/8000177/Act/Act800017702.json':
        return replace_report(raw, 258, 333)
    return raw


def patch_chunk(index, chunk, raw, replacements):
    require(len(raw) == chunk['size'] and hashlib.md5(raw).hexdigest() == chunk['digest'], '分块原件不符')
    by_key = {entry['key']: entry for entry in chunk['entries']}
    require(len(by_key) == len(chunk['entries']) and replacements and set(replacements) <= set(by_key),
            '分块替换记录缺失或重复')
    selected = sorted((by_key[key] for key in replacements), key=lambda row: row['offset'])
    prior_end = 0
    for row in selected:
        start, end = row['offset'], row['offset'] + row['length']
        require(prior_end <= start <= end <= len(raw) and isinstance(replacements[row['key']], bytes),
                '替换记录越界或相互重叠')
        prior_end = end
    offsets = []
    for row in chunk['entries']:
        start, end = row['offset'], row['offset'] + row['length']
        require(chunk['base'] <= start <= end <= len(raw) and 0 <= row['meta'] <= len(index) - 16,
                '分块索引记录越界')
        for target in selected:
            if row is not target:
                require(end <= target['offset'] or start >= target['offset'] + target['length'], '替换覆盖其他记录')
        shift = sum(len(replacements[target['key']]) - target['length'] for target in selected
                    if target['offset'] + target['length'] <= start)
        length = len(replacements[row['key']]) if row['key'] in replacements else row['length']
        offsets.append((row['meta'], length, start + shift - chunk['base']))
    require(0 <= chunk['meta'] <= len(index) - 36, '分块索引头越界')
    pieces, end = [], 0
    for row in selected:
        pieces.extend((raw[end:row['offset']], replacements[row['key']]))
        end = row['offset'] + row['length']
    pieces.append(raw[end:])
    changed = b''.join(pieces)
    require(len(changed) < 2**32 and all(0 <= pos < 2**32 and 0 <= size < 2**32
                                        for _, size, pos in offsets), '新分块长度越界')
    digest = hashlib.md5(changed).digest()
    index[chunk['meta'] + 8:chunk['meta'] + 24] = digest
    struct.pack_into('>I', index, chunk['meta'] + 28, len(changed))
    for meta, length, offset in offsets:
        struct.pack_into('>II', index, meta + 8, length, offset)
    return digest.hex() + '.bytes', changed


def read_index(raw, expected):
    require(sha(raw) == expected and len(raw) >= 16, '设计数据索引摘要不符')
    require(struct.unpack_from('>IIII', raw) == (255, 4, 33, 141295), '设计数据索引头变化')
    pos, chunks, keys = 16, [], set()
    for _ in range(33):
        require(pos + 36 <= len(raw), '设计数据分块头截断')
        chunk = {'meta': pos, 'digest': raw[pos + 8:pos + 24].hex()}
        chunk['base'], chunk['size'], count = struct.unpack_from('>III', raw, pos + 24)
        pos += 36
        require(count <= 141295 and pos + count * 16 + 3 <= len(raw), '设计数据记录数量越界')
        chunk['entries'] = []
        for _ in range(count):
            key = raw[pos:pos + 8].hex()
            length, relative = struct.unpack_from('>II', raw, pos + 8)
            require(key not in keys and chunk['base'] + relative + length <= chunk['size'], '设计数据记录重复或越界')
            keys.add(key)
            chunk['entries'].append({'meta': pos, 'key': key, 'length': length,
                                     'offset': chunk['base'] + relative})
            pos += 16
        tag, length = raw[pos:pos + 2]
        pos += 2
        require(tag == 0 and length < 16 and pos + length < len(raw) and raw[pos + length] == 128,
                '设计数据语言标记变化')
        chunk['locale'] = raw[pos:pos + length].decode('utf-8')
        require(not chunk['locale'] or chunk['locale'].islower() and chunk['locale'].isascii(), '语言目录非法')
        pos += length + 1
        chunks.append(chunk)
    require(pos == len(raw) and len(keys) == 141295, '设计数据索引未完整消费')
    return chunks


def outside_client(path, client):
    path, client = path.resolve(), client.resolve()
    require(path != client and not path.is_relative_to(client), '准备和备份目录必须在客户端外')
    return path


def make_plan(client, manifest):
    launcher = module('research_client_launcher', 'start-local-client.py')
    document, bound, _ = launcher.load_manifest(manifest)
    client = client.resolve()
    require(client == bound, '客户端目录与清单不符')
    for relative, expected in document['clientFiles'].items():
        require(launcher.sha(launcher.checked_path(client, relative)) == expected, '客户端程序文件变化')
    evidence, sources = fixture()
    require(document['clientFiles'].get('GameAssembly.dll') == evidence['clientFiles']['GameAssembly.dll'],
            '客户端程序不属于已核对版本')
    metadata = 'StarRail_Data/il2cpp_data/Metadata/global-metadata.dat'
    require(document['clientFiles'].get(metadata) == evidence['clientFiles']['global-metadata.dat'],
            '客户端元数据不属于已核对版本')
    directory = (client / CLIENT_DIR).resolve()
    require(directory.is_relative_to(client), '设计数据目录超出客户端')
    lua = module('research_client_pointer', 'client_lua_map_compat.py')
    pointer = (directory / 'M_DesignV.bytes').read_bytes()
    index_name = 'DesignV_' + lua.pointer_digest(pointer) + '.bytes'
    index_path = directory / index_name
    original_index = index_path.read_bytes()
    chunks = read_index(original_index, evidence['indexSHA256'])
    by_key = {entry['key']: (chunk, entry) for chunk in chunks for entry in chunk['entries']}
    wanted = {}
    for path, row in evidence['records'].items():
        key = row['entryKey']
        require(key in by_key and key not in wanted, '研究记录索引缺失或重复')
        wanted[key] = (path, sources[path])
    groups = {}
    for key, (path, original) in wanted.items():
        chunk, entry = by_key[key]
        require(not chunk['locale'], '研究记录不应位于语言分块')
        group = groups.setdefault(chunk['digest'], {'chunk': chunk, 'paths': {}, 'original': None})
        require(group['chunk'] is chunk, '研究记录分块摘要重复')
        group['paths'][key] = (path, original, entry)
    updated_index, packed, record_hashes = bytearray(original_index), {}, {}
    for group in groups.values():
        chunk = group['chunk']
        source_path = directory / (chunk['digest'] + '.bytes')
        raw = source_path.read_bytes()
        require(len(raw) == chunk['size'] and hashlib.md5(raw).hexdigest() == chunk['digest'], '设计数据分块摘要变化')
        group['original'] = raw
        replacements = {}
        for key, (path, original, entry) in group['paths'].items():
            require(raw[entry['offset']:entry['offset'] + entry['length']] == original, '研究记录内容变化')
            updated = patch_record(path, original)
            if updated != original:
                replacements[key] = updated
                record_hashes[path] = sha(updated)
        if replacements:
            name, output = patch_chunk(updated_index, chunk, raw, replacements)
            destination = directory / name
            require(not destination.exists() or destination.read_bytes() == output, '目标分块已有不同内容')
            packed[name] = output
    require(len(record_hashes) == 19 and len(packed) == 2, '研究兼容记录或分块数量变化')
    updated = bytes(updated_index)
    result_chunks = read_index(updated, sha(updated))
    updated_entries = {entry['key']: (chunk, entry) for chunk in result_chunks for entry in chunk['entries']}
    checked_unrelated = 0
    for group in groups.values():
        original = group['original']
        for entry in group['chunk']['entries']:
            if entry['key'] in wanted:
                continue
            chunk, after = updated_entries[entry['key']]
            body = packed.get(chunk['digest'] + '.bytes')
            require(body is not None
                    and original[entry['offset']:entry['offset'] + entry['length']]
                        == body[after['offset']:after['offset'] + after['length']],
                    '生成后的无关设计记录发生变化')
            checked_unrelated += 1
    for key, (path, original) in wanted.items():
        chunk, entry = updated_entries[key]
        expected = patch_record(path, original)
        body = packed.get(chunk['digest'] + '.bytes')
        require(body is not None and body[entry['offset']:entry['offset'] + entry['length']] == expected,
                '生成后的研究记录与索引不一致')
    require((directory / 'M_DesignV.bytes').read_bytes() == pointer and index_path.read_bytes() == original_index,
            '准备期间客户端索引变化')
    return {'client': client, 'directory': directory, 'indexPath': index_path, 'pointer': pointer,
            'oldIndex': original_index, 'index': updated, 'chunks': packed,
            'recordSHA256': record_hashes, 'unrelatedRecordsChecked': checked_unrelated}


def prepare(client, output, manifest):
    output = outside_client(output, client)
    require(not output.exists(), '准备目录已存在')
    plan = make_plan(client, manifest)
    output.mkdir(parents=True, exist_ok=False)
    for name, data in plan['chunks'].items():
        (output / name).write_bytes(data)
    (output / plan['indexPath'].name).write_bytes(plan['index'])
    report = {'schema': 1, 'client': str(plan['client']), 'indexName': plan['indexPath'].name,
              'oldIndexSHA256': sha(plan['oldIndex']), 'newIndexSHA256': sha(plan['index']),
              'pointerSHA256': sha(plan['pointer']),
              'chunks': {name: sha(data) for name, data in plan['chunks'].items()},
              'recordSHA256': plan['recordSHA256'], 'unrelatedRecordsChecked': plan['unrelatedRecordsChecked'],
              'clientModified': False,
              'clientAccepted': False, 'legacyReportPageRestored': False}
    (output / 'prepared.json').write_text(json.dumps(report, indent=2, ensure_ascii=True) + '\n', encoding='utf-8')
    return report


def atomic_replace(path, data, expected):
    require(sha(path.read_bytes()) == expected, '设计数据索引已变化，拒绝覆盖')
    temporary = None
    try:
        with tempfile.NamedTemporaryFile(prefix='.hkrpg-research-', dir=path.parent, delete=False) as stream:
            temporary = Path(stream.name)
            stream.write(data)
            stream.flush()
            os.fsync(stream.fileno())
        require(sha(path.read_bytes()) == expected, '安装期间设计数据索引变化')
        temporary.replace(path)
        temporary = None
    finally:
        if temporary is not None:
            temporary.unlink(missing_ok=True)


def install(prepared, backup, manifest):
    launcher = module('research_install_launcher', 'start-local-client.py')
    launcher.require_stopped()
    report = json.loads((prepared / 'prepared.json').read_text(encoding='utf-8'))
    require(report.get('schema') == 1 and re.fullmatch(r'DesignV_[0-9a-f]{32}\.bytes', report.get('indexName', '')),
            '准备清单格式错误')
    client = Path(report['client']).resolve()
    prepared, backup = outside_client(prepared, client), outside_client(backup, client)
    require(not backup.exists(), '备份目录已存在')
    plan = make_plan(client, manifest)
    require(sha(plan['oldIndex']) == report['oldIndexSHA256'] and sha(plan['index']) == report['newIndexSHA256']
            and sha(plan['pointer']) == report['pointerSHA256'] and plan['indexPath'].name == report['indexName']
            and {name: sha(raw) for name, raw in plan['chunks'].items()} == report['chunks']
            and plan['recordSHA256'] == report['recordSHA256']
            and plan['unrelatedRecordsChecked'] == report['unrelatedRecordsChecked'], '准备清单与当前原件不一致')
    require((prepared / report['indexName']).read_bytes() == plan['index'], '待安装索引内容变化')
    for name, raw in plan['chunks'].items():
        require(re.fullmatch(r'[0-9a-f]{32}\.bytes', name) and (prepared / name).read_bytes() == raw,
                '待安装分块内容变化')
    backup.mkdir(parents=True, exist_ok=False)
    (backup / report['indexName']).write_bytes(plan['oldIndex'])
    (backup / 'M_DesignV.bytes').write_bytes(plan['pointer'])
    (backup / 'manifest.json').write_text(json.dumps(report, indent=2) + '\n', encoding='utf-8')
    for name, raw in plan['chunks'].items():
        target = plan['directory'] / name
        if not target.exists():
            with target.open('xb') as stream:
                stream.write(raw)
                stream.flush()
                os.fsync(stream.fileno())
        require(target.read_bytes() == raw, '安装期间目标分块变化')
    launcher.require_stopped()
    require((plan['directory'] / 'M_DesignV.bytes').read_bytes() == plan['pointer'], '安装期间索引入口变化')
    atomic_replace(plan['indexPath'], plan['index'], report['oldIndexSHA256'])
    try:
        require(sha(plan['indexPath'].read_bytes()) == report['newIndexSHA256'], '安装后的索引校验失败')
        for name, raw in plan['chunks'].items():
            require((plan['directory'] / name).read_bytes() == raw, '安装后的分块校验失败')
    except Exception:
        if sha(plan['indexPath'].read_bytes()) == report['newIndexSHA256']:
            atomic_replace(plan['indexPath'], plan['oldIndex'], report['newIndexSHA256'])
        raise
    return dict(report, clientModified=True, backup=str(backup))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--manifest', type=Path, required=True)
    parser.add_argument('--prepare-dir', type=Path, required=True)
    parser.add_argument('--apply', action='store_true')
    parser.add_argument('--backup-dir', type=Path)
    args = parser.parse_args()
    client = Path(json.loads(args.manifest.read_text(encoding='utf-8-sig'))['clientRoot'])
    report = (json.loads((args.prepare_dir / 'prepared.json').read_text(encoding='utf-8'))
              if (args.prepare_dir / 'prepared.json').exists() else prepare(client, args.prepare_dir, args.manifest))
    if args.apply:
        require(args.backup_dir is not None, '安装必须指定备份目录')
        report = install(args.prepare_dir, args.backup_dir, args.manifest)
    print(json.dumps(report, ensure_ascii=True))


if __name__ == '__main__':
    main()
