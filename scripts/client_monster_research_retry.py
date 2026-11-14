"""只为已安装的 4.6 研究兼容层准备可重复提交补丁。"""
import base64
import hashlib
import json
from pathlib import Path
import argparse

import client_monster_research_compat as compat

ROOT = Path(__file__).resolve().parents[1]
FIXTURE = ROOT / 'scripts/fixtures/monster-research-retry-client-4.6.json'
FIXTURE_SHA256 = '7fcc61e3a211f3807f278235f3ffa6e0146220700a0a2702c38f250869ab07f6'
RETRIES = (800017003, 800017102, 800017202, 800017302, 800017402, 800017502, 800017702)
MAIN = 'Config/Level/NPCDialogue/P10001/F10001001_G10/DialogueMain_F10001001_G10_N400001.json'
PACK = 'Config/Level/NPCDialogue/P10001/F10001001_G10/DialoguePack_Activity_F10001001_G10_N400001.json'

def fixture():
    raw = FIXTURE.read_bytes().replace(b'\r\n', b'\n')
    compat.require(hashlib.sha256(raw).hexdigest() == FIXTURE_SHA256, '重试来源夹具变化')
    value = json.loads(raw)
    records = {}
    for name, row in value['records'].items():
        data = base64.b64decode(row['data'], validate=True)
        compat.require(len(data) == row['bytes'] and compat.sha(data) == row['sha256'], '重试图原件变化')
        records[name] = data
    return value, records

def profile():
    graphs, result, array = compat.graph_reader()
    result['tasks'].update({
        '972': {'name': 'CharacterDisableLookAt', 'noReaderPayload': False},
        '1264': {'name': 'EndDialogueEntityInteract', 'noReaderPayload': False},
    })
    result['classes']['EndDialogueEntityInteract'] = {'fields': [
        {'name': 'TaskEnabled', 'type': {'kind': 2}},
        {'name': 'OverrideTasks', 'type': {'kind': 18, 'name': 'DialogueEntityInteractOverride'}},
    ]}
    result['classes']['CharacterDisableLookAt'] = {'fields': [
        {'name': 'TaskEnabled', 'type': {'kind': 2}},
        {'name': 'TargetIsOwner', 'type': {'kind': 2}},
        {'name': 'CharacterUniqueName', 'type': {'kind': 14}},
        {'name': 'TargetType', 'type': {'kind': 18, 'name': 'TargetEvaluator'}},
        {'name': 'Disable', 'type': {'kind': 2}},
    ]}
    return graphs, result, array

def retry_source(raw, main, stage):
    compat.require(1 <= stage <= 7 and compat.MAINS[stage - 1] == main, '研究阶段不符')
    identity = RETRIES[stage - 1]
    marker = compat.var(1789) + compat.var(62 if stage == 7 else 38) + compat.var(stage)
    compat.require(raw.count(marker) == 1, '重试提交节点不唯一')
    start = raw.index(marker)
    graphs, definitions, array = profile()
    reader = graphs.Reader(raw, definitions); reader.pos = start
    compat.require((reader.uvar(), reader.uvar(), reader.uvar())
                   == (1789, 62 if stage == 7 else 38, stage), '重试提交节点变化')
    sections = []
    mask = 62 if stage == 7 else 38
    for index in range(4):
        if not mask & (1 << (index + 2)):
            sections.append((compat.tasks(), [], []))
            continue
        begin = reader.pos
        count = reader.integer(32, True)
        items, encodings = [], []
        for _ in range(count):
            item_start = reader.pos
            items.append(reader.read({'kind': 18, 'name': 'TaskConfig'}))
            encodings.append(raw[item_start:reader.pos])
        sections.append((raw[begin:reader.pos], items, encodings))
    describe = lambda items: [(x['$type'], x.get('CustomString', {}).get('Value')) for x in items]
    confirmation = [('TriggerCustomString', f'Talk_{identity}'),
                    ('TriggerCustomString', f'Submit_{identity}'),
                    ('TriggerCustomString', f'MainFinish_{main}'),
                    ('EndDialogueEntityInteract', None), ('CharacterDisableLookAt', None)]
    if stage == 7: confirmation.append(('FinishLevelGraph', None))
    cancel = [('TriggerCustomString', f'Cancel_{identity}'),
              ('EndDialogueEntityInteract', None), ('CharacterDisableLookAt', None)]
    compat.require(describe(sections[0][1]) == confirmation
                   and describe(sections[3][1]) == cancel, '原重试确认或取消事件变化')
    compat.require(all(describe(x[1]) == confirmation for x in sections[1:3]) if stage == 7
                   else all(not x[1] for x in sections[1:3]), '原重试课题分支变化')
    return start, reader.pos, sections

def patch_retry_graph(raw, main, stage):
    _, known = fixture()
    identity = RETRIES[stage - 1]
    path = f'Config/Level/NPCDialogue/P10001/F10001001_G10/DialogueItem{identity}_F10001001_G10_N400001.json'
    compat.require(known.get(path) == raw, '重试对话不是已核对的原件')
    start, end, sections = retry_source(raw, main, stage)
    if stage != 7:
        node = compat.consume(main * 100 + 4, sections[0][0], sections[3][0])
        return raw[:start] + node + raw[end:]
    options = []
    for i, title in enumerate(compat.TOPIC_HASHES, 1):
        options.append(compat.var(0) + compat.var(14) + compat.var(0) + compat.var(title)
                       + compat.var(40) + compat.var(4) + compat.string(f'ResearchRetryChoice_8000177_{i}'))
    selection = compat.var(1954) + compat.var(2) + compat.tasks(*options)
    compat.require(raw[:3] == b'\x03\x00\x04', '末阶段重试序列头变化')
    output = raw[:2] + compat.var(10) + raw[3:start] + selection + raw[end:]
    for i in (1, 2, 3):
        original = sections[i - 1][2]
        choice = compat.event(3598, f'ResearchFinish_8000177_{i}')
        confirmation = compat.tasks(*original[:3], choice, *original[3:])
        output += compat.sequence(compat.event(3795, f'ResearchRetryChoice_8000177_{i}'),
                                  compat.consume(800017705, confirmation, sections[3][0]))
    return output

def patch_npc_main(raw):
    _, known = fixture()
    compat.require(raw == known[MAIN] and len(raw) == 100, '温世玲对话入口原件变化')
    # The old main graph always starts a default introduction before ShowMenu. While an
    # original research retry is active, defer that introduction without recording it as seen.
    compat.require(raw[:5] == b'\x03\x00\x02\x04\x06'
                   and raw[5:7] == compat.var(1082) and raw[22:24] == compat.var(1993)
                   and raw[7:9] == b'\x0e\x00'
                   and raw[97:99] == compat.var(3259), '温世玲对话入口结构变化')
    # Extend the original collector instead of replacing or repeating it, so its original
    # main-mission and first-talk dependencies remain part of the same query.
    collect = raw[5:8] + compat.var(len(RETRIES) * 2)
    collect += b''.join(compat.var(identity) for identity in RETRIES) + raw[9:22]
    predicates = [compat.var(268) + compat.var(12) + compat.var(identity) + compat.var(2)
                  for identity in RETRIES]
    any_retry = compat.var(57) + compat.var(4) + compat.var(len(predicates) * 2) + b''.join(predicates)
    # Empty success branch defers the intro; the untouched original branch runs otherwise.
    guarded_intro = compat.var(1993) + compat.var(14) + any_retry + compat.tasks() + compat.tasks(raw[22:97])
    return raw[:5] + collect + guarded_intro + raw[97:]

def performance_rows(raw, expected):
    graphs, definitions, _ = profile()
    reader = graphs.Reader(raw, definitions)
    compat.require(reader.integer(32, True) == expected, '演出表数量变化')
    rows = {}
    for _ in range(expected):
        start, mask = reader.pos, reader.uvar()
        compat.require(mask >> len(compat.PERFORMANCE_FIELDS) == 0, '演出表未知字段')
        row = {}
        for bit, (name, kind) in enumerate(compat.PERFORMANCE_FIELDS):
            if mask & (1 << bit):
                row[name] = reader.sint() if kind == 17 else reader.read({'kind': kind})
        compat.require(row.get('id', 0) > 0 and row['id'] not in rows, '演出编号缺失或重复')
        rows[row['id']] = (row, raw[start:reader.pos])
    compat.require(reader.pos == len(raw), '演出表尾部变化')
    return rows

def restore_performances(raw, original):
    compat.require(raw == compat.patch_performances(original), '已安装的演出表与固定兼容版本不符')
    before, source = performance_rows(raw, 12590), compat.performance_rows(original)
    restored = {800017003, 800017102, 800017702}
    output = bytearray(compat.var(len(before) * 2))
    for identity, (row, encoded) in before.items():
        output += compat.encode_performance(source[identity][0]) if identity in restored else encoded
    return bytes(output)

def make_plan(client, manifest):
    launcher = compat.module('research_retry_launcher', 'start-local-client.py')
    document, bound, _ = launcher.load_manifest(manifest)
    client = client.resolve()
    compat.require(client == bound, '客户端目录与清单不符')
    for relative, expected in document['clientFiles'].items():
        compat.require(launcher.sha(launcher.checked_path(client, relative)) == expected, '客户端程序文件变化')
    evidence, originals = fixture()
    first_evidence, first_originals = compat.fixture()
    metadata = 'StarRail_Data/il2cpp_data/Metadata/global-metadata.dat'
    compat.require(document['clientFiles'].get('GameAssembly.dll') == evidence['clientFiles']['GameAssembly.dll']
                   and document['clientFiles'].get(metadata) == evidence['clientFiles']['global-metadata.dat'],
                   '客户端程序或元数据不属于固定来源')
    directory = (client / compat.CLIENT_DIR).resolve()
    compat.require(directory.is_relative_to(client), '设计数据目录超出客户端')
    lua = compat.module('research_retry_pointer', 'client_lua_map_compat.py')
    pointer = (directory / 'M_DesignV.bytes').read_bytes()
    index_path = directory / ('DesignV_' + lua.pointer_digest(pointer) + '.bytes')
    original_index = index_path.read_bytes()
    chunks = compat.read_index(original_index, evidence['indexSHA256'])
    by_key = {entry['key']: (chunk, entry) for chunk in chunks for entry in chunk['entries']}
    wanted = {row['entryKey']: (path, originals[path]) for path, row in evidence['records'].items()}
    performance = 'ExcelOutput/PerformanceE.json'
    perf_key = first_evidence['records'][performance]['entryKey']
    compat.require(perf_key not in wanted, '演出表与重试来源索引冲突')
    wanted[perf_key] = (performance, compat.patch_performances(first_originals[performance]))
    compat.require(len(wanted) == 10 and all(key in by_key for key in wanted), '重试记录索引缺失')
    groups = {}
    for key, (path, raw) in wanted.items():
        chunk, entry = by_key[key]
        compat.require(not chunk['locale'], '重试记录不应位于语言分块')
        group = groups.setdefault(chunk['digest'], {'chunk': chunk, 'paths': {}, 'original': None})
        compat.require(group['chunk'] is chunk, '重试分块摘要重复')
        group['paths'][key] = (path, raw, entry)
    updated_index, packed, hashes, changed_keys = bytearray(original_index), {}, {}, set()
    for group in groups.values():
        chunk = group['chunk']
        raw = (directory / (chunk['digest'] + '.bytes')).read_bytes()
        compat.require(len(raw) == chunk['size'] and hashlib.md5(raw).hexdigest() == chunk['digest'], '设计分块摘要变化')
        group['original'] = raw
        replacements = {}
        for key, (path, expected, entry) in group['paths'].items():
            compat.require(raw[entry['offset']:entry['offset'] + entry['length']] == expected, '重试图来源变化')
            if path == MAIN:
                new = patch_npc_main(expected)
            elif path == PACK:
                new = expected
            elif path == performance:
                new = restore_performances(expected, first_originals[performance])
            else:
                identity = int(Path(path).name.split('_')[0].removeprefix('DialogueItem'))
                compat.require(identity in RETRIES, '未知重试编号')
                stage = RETRIES.index(identity) + 1
                new = patch_retry_graph(expected, compat.MAINS[stage - 1], stage)
            if new != expected:
                replacements[key] = new
                hashes[path] = compat.sha(new)
                changed_keys.add(key)
        if replacements:
            name, output = compat.patch_chunk(updated_index, chunk, raw, replacements)
            target = directory / name
            compat.require(not target.exists() or target.read_bytes() == output, '目标分块已有不同内容')
            packed[name] = output
    compat.require(len(hashes) == 9 and packed, '重试兼容变更数量不符')
    updated = bytes(updated_index)
    result_chunks = compat.read_index(updated, compat.sha(updated))
    after = {entry['key']: (chunk, entry) for chunk in result_chunks for entry in chunk['entries']}
    compat.require(set(after) == set(by_key), '设计数据记录集合变化')
    checked = 0
    for group in groups.values():
        old_chunk, original = group['chunk'], group['original']
        for entry in old_chunk['entries']:
            if entry['key'] in changed_keys:
                continue
            chunk, record = after[entry['key']]
            body = packed.get(chunk['digest'] + '.bytes', original)
            compat.require(original[entry['offset']:entry['offset'] + entry['length']]
                           == body[record['offset']:record['offset'] + record['length']], '无关设计记录变化')
            checked += 1
    for key in changed_keys:
        path, _ = wanted[key]
        chunk, entry = after[key]
        body = packed.get(chunk['digest'] + '.bytes')
        compat.require(body is not None and compat.sha(body[entry['offset']:entry['offset'] + entry['length']])
                       == hashes[path], '生成后的研究记录与索引不一致')
    compat.require((directory / 'M_DesignV.bytes').read_bytes() == pointer and index_path.read_bytes() == original_index,
                   '准备期间客户端索引变化')
    return {'client': client, 'directory': directory, 'indexPath': index_path, 'pointer': pointer,
            'oldIndex': original_index, 'index': updated, 'chunks': packed,
            'recordSHA256': hashes, 'unrelatedRecordsChecked': checked}

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--manifest', type=Path, required=True)
    parser.add_argument('--prepare-dir', type=Path, required=True)
    parser.add_argument('--apply', action='store_true')
    parser.add_argument('--backup-dir', type=Path)
    args = parser.parse_args()
    client = Path(json.loads(args.manifest.read_text(encoding='utf-8-sig'))['clientRoot'])
    previous = compat.make_plan
    try:
        compat.make_plan = make_plan
        report = (json.loads((args.prepare_dir / 'prepared.json').read_text(encoding='utf-8'))
                  if (args.prepare_dir / 'prepared.json').exists()
                  else compat.prepare(client, args.prepare_dir, args.manifest))
        if args.apply:
            compat.require(args.backup_dir is not None, '安装必须指定备份目录')
            report = compat.install(args.prepare_dir, args.backup_dir, args.manifest)
    finally:
        compat.make_plan = previous
    print(json.dumps(report, ensure_ascii=True))

if __name__ == '__main__':
    main()
