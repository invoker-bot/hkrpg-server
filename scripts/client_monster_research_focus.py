"""为固定 4.6 研究任务准备隔离的材料页面层级兼容候选。"""
import functools
import argparse
import hashlib
import json
import struct
from pathlib import Path

import client_monster_research_compat as compat
import client_monster_research_retry as retry

lua = compat.module('research_focus_lua', 'client_lua_map_compat.py')
MARKER = 1729.0
PAGE_SHA = '0aa8a59c1b30132b967cd65d479a060c2dbec160c3709e5003518508679009b7'
DESIGN_SHA = '7b1fe9daae5364e0fe7d2241314b49ed7b080068e75dbfa6a21e730be73a0a8f'
LUA_SHA = '59a53c5c4c567235b130be6cd28ab0ea09d8f302fd0d8b091f9b3f5117479129'
MAP_SHA = '514e94cbd8db85fcc3456761247873b71f0570135c789989703321977032aaef'


@functools.lru_cache(maxsize=7)
def known_graphs(sub_mission):
    for stage, main in enumerate(compat.MAINS, 1):
        sub = main * 100 + (5 if stage == 7 else 4)
        if sub == sub_mission:
            _, first = compat.fixture()
            _, again = retry.fixture()
            initial = f'Config/Level/Mission/{main}/Act/Act{main * 100 + 1}.json'
            ident = retry.RETRIES[stage - 1]
            reentry = f'Config/Level/NPCDialogue/P10001/F10001001_G10/DialogueItem{ident}_F10001001_G10_N400001.json'
            return stage, (compat.patch_initial(first[initial], main, stage, compat.OFFSETS[stage - 1]),
                           retry.patch_retry_graph(again[reentry], main, stage))
    raise ValueError('非已核对的研究子任务')


def submit_profile():
    graphs, profile, array = retry.profile()
    profile['tasks']['1099'] = {'name': 'ConsumeMissionItemPerformance', 'noReaderPayload': False}
    profile['classes']['ConsumeMissionItemPerformance'] = {'fields': [
        {'name': 'TaskEnabled', 'type': {'kind': 2}},
        {'name': 'SubMissionID', 'type': {'kind': 9}},
        {'name': 'Desc', 'type': {'kind': 17, 'name': 'TextID'}},
        {'name': 'OnSubmitConfirm', 'type': array},
        {'name': 'OnSubmitCancel', 'type': array},
        {'name': 'SimpleTalk', 'type': {'kind': 18, 'name': 'SimpleTalkInfo'}},
    ]}
    return graphs, profile


def mark_graph(raw, sub_mission):
    stage, known = known_graphs(sub_mission)
    compat.require(raw in known, '研究提交图不是已核对的兼容原件')
    graphs, profile = submit_profile()
    prefix = compat.var(1099) + compat.var(26) + compat.var(sub_mission)
    offsets, start = [], 0
    while (pos := raw.find(prefix, start)) >= 0:
        reader = graphs.Reader(raw, profile)
        reader.pos = pos
        node = reader.read({'kind': 18, 'name': 'TaskConfig'})
        compat.require(node['$type'] == 'ConsumeMissionItemPerformance'
                       and node['SubMissionID'] == sub_mission and 'SimpleTalk' not in node,
                       '研究提交节点结构变化')
        offsets.append((pos, reader.pos))
        start = reader.pos
    compat.require(len(offsets) == (3 if stage == 7 else 1), '研究提交节点数量变化')
    output = raw
    # SimpleTalk.TalkSentenceID 默认为 0，原生界面会直接跳过对话文本；仅 TextSpeed
    # 被用作本兼容层的标记。确认/取消事件和材料数组保持原值。
    marker = compat.var(1) + struct.pack('<f', MARKER)
    for pos, end in reversed(offsets):
        output = (output[:pos] + compat.var(1099) + compat.var(58)
                  + output[pos + len(compat.var(1099)) + len(compat.var(26)):end]
                  + marker + output[end:])
    return output


def patch_page(raw):
    lua.require(lua.sha(raw) == PAGE_SHA, '材料页不是已核对原件')
    decoded = lua.Bytecode(raw)
    matches = [p for p in decoded.protos if p['name'] == 'init']
    lua.require(len(matches) == 1, '材料页 init 不唯一')
    p = matches[0]
    lua.require(p['header'] == bytes([7, 5, 0, 0, 0]) and p['line'] == 29
                and p['debug'] and len(p['code']) == len(p['lines']) == 11
                and p['constants'] == ['_required_items', '_hint_desc', 'G', 'RogueTalkUtils',
                                       '_construct_one_simple_talk', {'import': 3223325700}, '_talk_info'],
                '材料页初始化结构变化')
    strings, constants = list(decoded.strings), bytearray()
    def text_constant(value):
        if value not in strings:
            strings.append(value)
        return b'\x03' + lua.var(strings.index(value) + 1)
    first = len(p['constants'])
    for value in ('TextSpeed', 'G', 'ImportCsToLua', 'CS_RPG_Client_UILayer_Dialog', '_ui_layer', 'TalkSentenceID'):
        constants += text_constant(value)
    imported = 0xc0000000 | ((first + 1) << 20) | ((first + 2) << 10) | (first + 3)
    constants += b'\x04' + struct.pack('<I', imported)
    # Luau 4/1: JUMPIFNOT、GETTABLEKS、LOADN、JUMPIFNOTEQ、GETIMPORT、SETTABLEKS。
    # 未标记的原生页面继续原始 Page 层；标记页放在 Dialog 层。
    branch = [26 | (2 << 8) | (14 << 16),
              15 | (5 << 8) | (2 << 16), first,
              4 | (6 << 8) | (int(MARKER) << 16),
              30 | (5 << 8) | (10 << 16), 6,
              15 | (5 << 8) | (2 << 16), first + 5,
              4 | (6 << 8),
              30 | (5 << 8) | (5 << 16), 6,
              12 | (5 << 8) | ((first + 6) << 16), imported,
              16 | (5 << 8), first + 4]
    code = branch + p['code']
    body = raw[p['start']:p['code_count']] + lua.var(len(code))
    body += struct.pack('<' + 'I' * len(code), *code)
    body += lua.var(first + 7) + raw[p['constants_start']:p['constants_end']] + constants
    body += raw[p['constants_end']:p['line_start']]
    previous = 0
    body += b'\x01\x00' + bytes(len(code))
    for line in [p['line']] * len(branch) + p['lines']:
        body += struct.pack('<i', line - previous)
        previous = line
    body += b'\x01' + lua.var(len(p['locals']))
    for name, start, end, register in p['locals']:
        body += lua.var(name) + lua.var(start + len(branch) if start else 0)
        body += lua.var(end + len(branch)) + bytes([register])
    body += lua.var(len(p['upnames'])) + b''.join(lua.var(n) for n in p['upnames'])
    table = b''.join(lua.var(len(s.encode('utf8'))) + s.encode('utf8') for s in strings)
    result = raw[:2] + lua.var(len(strings)) + table + raw[decoded.body:p['start']] + body + raw[p['end']:]
    checked = lua.Bytecode(result)
    lua.require(len(checked.protos) == len(decoded.protos) and checked.main == decoded.main,
                '材料页原型图变化')
    return result


def make_plan(client, manifest):
    launcher = compat.module('research_focus_launcher', 'start-local-client.py')
    document, bound, _ = launcher.load_manifest(manifest)
    client = client.resolve()
    compat.require(client == bound, '客户端目录与清单不符')
    for relative, expected in document['clientFiles'].items():
        compat.require(launcher.sha(launcher.checked_path(client, relative)) == expected, '客户端程序文件变化')
    first_evidence, _ = compat.fixture()
    retry_evidence, _ = retry.fixture()
    metadata = 'StarRail_Data/il2cpp_data/Metadata/global-metadata.dat'
    compat.require(document['clientFiles'].get('GameAssembly.dll') == first_evidence['clientFiles']['GameAssembly.dll']
                   and document['clientFiles'].get(metadata) == first_evidence['clientFiles']['global-metadata.dat'],
                   '客户端程序或元数据不属于固定来源')
    directory = (client / compat.CLIENT_DIR).resolve()
    compat.require(directory.is_relative_to(client), '设计数据目录超出客户端')
    pointer = (directory / 'M_DesignV.bytes').read_bytes()
    index_path = directory / ('DesignV_' + lua.pointer_digest(pointer) + '.bytes')
    original_index = index_path.read_bytes()
    chunks = compat.read_index(original_index, DESIGN_SHA)
    by_key = {entry['key']: (chunk, entry) for chunk in chunks for entry in chunk['entries']}
    wanted = {}
    for stage, main in enumerate(compat.MAINS, 1):
        sub = main * 100 + (5 if stage == 7 else 4)
        _, expected = known_graphs(sub)
        initial = f'Config/Level/Mission/{main}/Act/Act{main * 100 + 1}.json'
        ident = retry.RETRIES[stage - 1]
        again = f'Config/Level/NPCDialogue/P10001/F10001001_G10/DialogueItem{ident}_F10001001_G10_N400001.json'
        for path, evidence, body in ((initial, first_evidence, expected[0]), (again, retry_evidence, expected[1])):
            key = evidence['records'][path]['entryKey']
            compat.require(key in by_key and key not in wanted, '研究图索引缺失或重复')
            wanted[key] = (path, body, sub)
    groups = {}
    for key, (path, expected, sub) in wanted.items():
        chunk, entry = by_key[key]
        compat.require(not chunk['locale'], '研究图不应位于语言分块')
        group = groups.setdefault(chunk['digest'], {'chunk': chunk, 'records': {}, 'original': None})
        compat.require(group['chunk'] is chunk, '研究图分块摘要重复')
        group['records'][key] = (path, expected, sub, entry)
    updated_index, packed, record_hashes = bytearray(original_index), {}, {}
    for group in groups.values():
        chunk = group['chunk']
        raw = (directory / (chunk['digest'] + '.bytes')).read_bytes()
        compat.require(len(raw) == chunk['size'] and hashlib.md5(raw).hexdigest() == chunk['digest'], '设计分块摘要变化')
        group['original'] = raw
        replacements = {}
        for key, (path, expected, sub, entry) in group['records'].items():
            compat.require(raw[entry['offset']:entry['offset'] + entry['length']] == expected, '研究图原件变化')
            changed = mark_graph(expected, sub)
            replacements[key] = changed
            record_hashes[path] = compat.sha(changed)
        name, output = compat.patch_chunk(updated_index, chunk, raw, replacements)
        target = directory / name
        compat.require(not target.exists() or target.read_bytes() == output, '目标分块已有不同内容')
        packed[name] = output
    compat.require(len(record_hashes) == 14 and packed, '研究图变更数量不符')
    updated = bytes(updated_index)
    after_chunks = compat.read_index(updated, compat.sha(updated))
    after = {entry['key']: (chunk, entry) for chunk in after_chunks for entry in chunk['entries']}
    compat.require(set(after) == set(by_key), '设计记录集合变化')
    checked = 0
    for group in groups.values():
        for entry in group['chunk']['entries']:
            chunk, new_entry = after[entry['key']]
            body = packed.get(chunk['digest'] + '.bytes')
            compat.require(body is not None, '设计分块丢失')
            content = body[new_entry['offset']:new_entry['offset'] + new_entry['length']]
            if entry['key'] in wanted:
                path, _, _ = wanted[entry['key']]
                compat.require(compat.sha(content) == record_hashes[path], '研究记录生成后不符')
            else:
                compat.require(content == group['original'][entry['offset']:entry['offset'] + entry['length']],
                               '无关设计记录变化')
                checked += 1
    compat.require((directory / 'M_DesignV.bytes').read_bytes() == pointer and index_path.read_bytes() == original_index,
                   '准备期间设计索引变化')
    return {'client': client, 'directory': directory, 'indexPath': index_path, 'pointer': pointer,
            'oldIndex': original_index, 'index': updated, 'chunks': packed,
            'recordSHA256': record_hashes, 'unrelatedRecordsChecked': checked}


def build_lua(client):
    return lua.build_profile_files(client, index_sha=LUA_SHA, page='Ui/Mission/PassiveSubmitMissionItemPage',
                                   companion='Ui/Map/SwitchAreaPage', companion_sha=MAP_SHA,
                                   patcher=patch_page)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--manifest', type=Path, required=True)
    parser.add_argument('--prepare-dir', type=Path, required=True)
    parser.add_argument('--apply', action='store_true')
    parser.add_argument('--backup-dir', type=Path)
    args = parser.parse_args()
    client = Path(json.loads(args.manifest.read_text(encoding='utf-8-sig'))['clientRoot']).resolve()
    compat.outside_client(args.prepare_dir, client)
    design, pages = args.prepare_dir / 'design', args.prepare_dir / 'lua'
    old_make_plan = compat.make_plan
    try:
        compat.make_plan = make_plan
        if not args.prepare_dir.exists():
            args.prepare_dir.mkdir(parents=True, exist_ok=False)
            design_report = compat.prepare(client, design, args.manifest)
            page_report = lua.prepare(client, pages, build=build_lua)
        else:
            design_report = json.loads((design / 'prepared.json').read_text('utf8'))
            page_report = json.loads((pages / 'prepared.json').read_text('utf8'))
        if args.apply:
            compat.require(args.backup_dir is not None, '安装必须明确备份目录')
            backup = compat.outside_client(args.backup_dir, client)
            compat.require(not backup.exists(), '备份目录已存在')
            backup.mkdir(parents=True, exist_ok=False)
            result = compat.install(design, backup / 'design', args.manifest)
            try:
                page_result = lua.install(pages, args.manifest, backup / 'lua', build=build_lua)
            except Exception as error:
                launcher = compat.module('research_focus_rollback_launcher', 'start-local-client.py')
                try:
                    launcher.require_stopped()
                except Exception as state_error:
                    raise RuntimeError('游戏已启动或运行状态不可核对；保留可回滚的研究标记，不切换运行中的索引') from state_error
                current = client / compat.CLIENT_DIR / design_report['indexName']
                old = (backup / 'design' / design_report['indexName']).read_bytes()
                if compat.sha(current.read_bytes()) == design_report['newIndexSHA256']:
                    compat.atomic_replace(current, old, design_report['newIndexSHA256'])
                raise
            print(json.dumps({'design': result, 'lua': page_result}, ensure_ascii=True))
        else:
            print(json.dumps({'design': design_report, 'lua': page_report}, ensure_ascii=True))
    finally:
        compat.make_plan = old_make_plan


if __name__ == '__main__':
    main()
