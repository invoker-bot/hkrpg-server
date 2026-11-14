"""为研究任务第七阶段的课题选择准备原生对话界面前置步骤。"""
import argparse
import functools
import hashlib
import json
from pathlib import Path

import client_monster_research_compat as compat
import client_monster_research_focus as focus
import client_monster_research_retry as retry

DESIGN_SHA = 'c61c44d3c63612efdb858f8c2922dd345c532acc961ae85aabc8bc937830920d'
RESULT_SHA = '15fb9682f950182c3df789d218ac61c1aa2382aa39517d1628b34421f39953d6'


@functools.lru_cache(maxsize=1)
def known():
    stage, originals = focus.known_graphs(800017705)
    compat.require(stage == 7, '研究阶段变化')
    return tuple(focus.mark_graph(raw, 800017705) for raw in originals)


def options(retry):
    prefix = 'ResearchRetryChoice' if retry else 'ResearchChoice'
    rows = []
    for i, title in enumerate(compat.TOPIC_HASHES, 1):
        rows.append(compat.var(0) + compat.var(14) + compat.var(0) + compat.var(title)
                    + compat.var(40) + compat.var(4)
                    + compat.string(f'{prefix}_8000177_{i}'))
    return compat.var(1954) + compat.var(2) + compat.tasks(*rows)


def patch_graph(raw):
    sources = known()
    compat.require(raw in sources, '第七阶段提交图不是已核对的兼容原件')
    retry = raw == sources[1]
    # 来源图固定且整份比较；只在原 StartDialogueEntityInteract 之后插入原生任务。
    sequence_offset, count = (3, 4) if retry else (176, 6)
    marker = options(retry)
    compat.require(raw.count(marker) == 1 and raw[sequence_offset:sequence_offset + 2]
                   == bytes((4, count * 2)), '第七阶段选项或任务序列变化')
    position = raw.index(marker)
    compat.require(position > sequence_offset + 2, '研究选项不在预期序列中')
    show_talk = compat.var(3297) + compat.var(2) + b'\x01'  # ShowTalkUI.Show=true
    return (raw[:sequence_offset + 1] + compat.var((count + 1) * 2)
            + raw[sequence_offset + 2:position] + show_talk + raw[position:])


def make_plan(client, manifest):
    launcher = compat.module('research_choice_launcher', 'start-local-client.py')
    document, bound, _ = launcher.load_manifest(manifest)
    client = client.resolve()
    compat.require(client == bound, '客户端目录与清单不符')
    for group in ('clientFiles', 'clientLuaFiles'):
        for relative, expected in document.get(group, {}).items():
            compat.require(launcher.sha(launcher.checked_path(client, relative)) == expected,
                           '客户端程序或 Lua 入口变化')
    initial, _ = compat.fixture()
    again, _ = retry.fixture()
    metadata = 'StarRail_Data/il2cpp_data/Metadata/global-metadata.dat'
    compat.require(document['clientFiles'].get('GameAssembly.dll') == initial['clientFiles']['GameAssembly.dll']
                   and document['clientFiles'].get(metadata) == initial['clientFiles']['global-metadata.dat'],
                   '客户端程序或元数据不属于固定来源')
    directory = (client / compat.CLIENT_DIR).resolve()
    compat.require(directory.is_relative_to(client), '设计数据目录超出客户端')
    pointer = (directory / 'M_DesignV.bytes').read_bytes()
    index_path = directory / ('DesignV_' + focus.lua.pointer_digest(pointer) + '.bytes')
    old_index = index_path.read_bytes()
    chunks = compat.read_index(old_index, DESIGN_SHA)
    entries = {entry['key']: (chunk, entry) for chunk in chunks for entry in chunk['entries']}
    paths = ('Config/Level/Mission/8000177/Act/Act800017701.json',
             'Config/Level/NPCDialogue/P10001/F10001001_G10/DialogueItem800017702_F10001001_G10_N400001.json')
    wanted = {}
    for path, evidence, expected in zip(paths, (initial, again), known()):
        key = evidence['records'][path]['entryKey']
        compat.require(key in entries and key not in wanted, '第七阶段图索引缺失或重复')
        wanted[key] = (path, expected)
    groups = {}
    for key in wanted:
        chunk, entry = entries[key]
        compat.require(not chunk['locale'], '研究图不应位于语言分块')
        group = groups.setdefault(chunk['digest'], {'chunk': chunk, 'entries': {}, 'original': None})
        compat.require(group['chunk'] is chunk, '研究图分块摘要重复')
        group['entries'][key] = entry
    updated_index, packed, record_hashes = bytearray(old_index), {}, {}
    for group in groups.values():
        chunk = group['chunk']
        body = (directory / (chunk['digest'] + '.bytes')).read_bytes()
        compat.require(len(body) == chunk['size'] and hashlib.md5(body).hexdigest() == chunk['digest'],
                       '设计分块摘要变化')
        group['original'] = body
        replacements = {}
        for key, entry in group['entries'].items():
            path, expected = wanted[key]
            compat.require(body[entry['offset']:entry['offset'] + entry['length']] == expected,
                           '第七阶段图内容变化')
            replacements[key] = patch_graph(expected)
            record_hashes[path] = compat.sha(replacements[key])
        name, output = compat.patch_chunk(updated_index, chunk, body, replacements)
        target = directory / name
        compat.require(not target.exists() or target.read_bytes() == output, '目标分块已有不同内容')
        packed[name] = output
    updated = bytes(updated_index)
    compat.require(compat.sha(updated) == RESULT_SHA, '第七阶段设计索引生成结果不符')
    after = {entry['key']: (chunk, entry) for chunk in compat.read_index(updated, compat.sha(updated))
             for entry in chunk['entries']}
    compat.require(set(after) == set(entries) and len(record_hashes) == 2, '研究图变更集合不符')
    checked = 0
    for group in groups.values():
        for entry in group['chunk']['entries']:
            chunk, new_entry = after[entry['key']]
            body = packed.get(chunk['digest'] + '.bytes')
            compat.require(body is not None, '设计分块丢失')
            content = body[new_entry['offset']:new_entry['offset'] + new_entry['length']]
            if entry['key'] in wanted:
                compat.require(compat.sha(content) == record_hashes[wanted[entry['key']][0]], '研究图生成后不符')
            else:
                compat.require(content == group['original'][entry['offset']:entry['offset'] + entry['length']],
                               '无关设计记录变化')
                checked += 1
    compat.require((directory / 'M_DesignV.bytes').read_bytes() == pointer and index_path.read_bytes() == old_index,
                   '准备期间设计索引变化')
    return {'client': client, 'directory': directory, 'indexPath': index_path, 'pointer': pointer,
            'oldIndex': old_index, 'index': updated, 'chunks': packed,
            'recordSHA256': record_hashes, 'unrelatedRecordsChecked': checked}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--manifest', type=Path, required=True)
    parser.add_argument('--prepare-dir', type=Path, required=True)
    parser.add_argument('--apply', action='store_true')
    parser.add_argument('--backup-dir', type=Path)
    args = parser.parse_args()
    client = Path(json.loads(args.manifest.read_text(encoding='utf-8-sig'))['clientRoot']).resolve()
    compat.outside_client(args.prepare_dir, client)
    original = compat.make_plan
    try:
        compat.make_plan = make_plan
        report = (json.loads((args.prepare_dir / 'prepared.json').read_text(encoding='utf8'))
                  if args.prepare_dir.exists() else compat.prepare(client, args.prepare_dir, args.manifest))
        if args.apply:
            compat.require(args.backup_dir is not None, '安装必须明确备份目录')
            report = compat.install(args.prepare_dir, args.backup_dir, args.manifest)
        print(json.dumps(report, ensure_ascii=True))
    finally:
        compat.make_plan = original


if __name__ == '__main__':
    main()
