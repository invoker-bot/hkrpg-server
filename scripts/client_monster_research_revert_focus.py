"""撤回本机已证实无效的研究窗口层级候选，保留前一版重试修复。"""
import argparse
import json
import os
from pathlib import Path

import client_monster_research_compat as compat
import client_monster_research_focus as focus

DESIGN_CANDIDATE = 'c61c44d3c63612efdb858f8c2922dd345c532acc961ae85aabc8bc937830920d'
LUA_CANDIDATE = 'fc61fc130818cd8b2c4ad788df9eb3c7a0c10d119af847423c96cfd0f6cb9ab1'
LUA_ORIGINAL = '53a272ab934100e15bfe9c60edad9b268803c6fc758028f1895cb8261a31f982'
PAGE_CANDIDATE = 'c65fccc244ac3d6ab496b76cd3664f9cba9493fe021bc3675de39895c40a2650'


def atomic(path, data, expected):
    compat.atomic_replace(path, data, expected)


def make_plan(manifest, source_backup, lua_report_path):
    launcher = compat.module('research_revert_launcher', 'start-local-client.py')
    document, client, _ = launcher.load_manifest(manifest)
    source_backup = compat.outside_client(source_backup, client)
    compat.require(source_backup.is_dir(), '候选原备份不存在')
    for relative, expected in document['clientFiles'].items():
        compat.require(launcher.sha(launcher.checked_path(client, relative)) == expected, '客户端程序文件变化')
    saved = json.loads((source_backup / 'lua/patch-manifest.json').read_text(encoding='utf-8-sig'))
    report = json.loads(lua_report_path.read_text(encoding='utf8'))
    design_report = json.loads((source_backup / 'design/manifest.json').read_text(encoding='utf8'))
    compat.require(saved['clientRoot'] == document['clientRoot']
                   and saved['clientFiles'] == document['clientFiles'], '原备份不属于当前客户端')
    compat.require(report['originalIndexSHA256'] == focus.LUA_SHA
                   and report['originalPointerSHA256'] == LUA_ORIGINAL
                   and report['originalPageSHA256'] == focus.PAGE_SHA
                   and report['patchedPageSHA256'] == PAGE_CANDIDATE
                   and report['files'].get(focus.lua.LUA + 'M_LuaV.bytes') == LUA_CANDIDATE
                   and document.get('clientLuaFiles') == report['files'], 'Lua 候选清单变化')
    compat.require(design_report['oldIndexSHA256'] == focus.DESIGN_SHA
                   and design_report['newIndexSHA256'] == DESIGN_CANDIDATE, '设计数据备份不符')
    for relative, expected in report['files'].items():
        compat.require(launcher.sha(launcher.checked_path(client, relative)) == expected, '已安装 Lua 候选变化')
    old_lua = (source_backup / 'lua/M_LuaV.bytes').read_bytes()
    compat.require(compat.sha(old_lua) == LUA_ORIGINAL
                   and saved['clientLuaFiles'][focus.lua.LUA + 'M_LuaV.bytes'] == LUA_ORIGINAL,
                   '原 Lua 入口备份变化')
    for relative, expected in saved['clientLuaFiles'].items():
        if relative != focus.lua.LUA + 'M_LuaV.bytes':
            compat.require(launcher.sha(launcher.checked_path(client, relative)) == expected, '原 Lua 文件不完整')
    directory = client / compat.CLIENT_DIR
    design_pointer = (directory / 'M_DesignV.bytes').read_bytes()
    compat.require(compat.sha(design_pointer) == design_report['pointerSHA256']
                   and (source_backup / 'design/M_DesignV.bytes').read_bytes() == design_pointer,
                   '设计数据入口变化')
    index_path = directory / ('DesignV_' + focus.lua.pointer_digest(design_pointer) + '.bytes')
    compat.require(index_path.name == design_report['indexName'], '设计索引名称变化')
    new_index = index_path.read_bytes()
    old_index = (source_backup / 'design' / index_path.name).read_bytes()
    compat.require(compat.sha(new_index) == DESIGN_CANDIDATE and compat.sha(old_index) == focus.DESIGN_SHA,
                   '设计索引或备份变化')
    # 只恢复旧 Lua 文件映射，保留其他并发维护的启动清单字段。
    updated = dict(document, clientLuaFiles=saved['clientLuaFiles'])
    manifest_bytes = manifest.read_bytes()
    return {'client': client, 'indexPath': index_path, 'beforeIndex': new_index, 'afterIndex': old_index,
            'pointerPath': client / focus.lua.LUA / 'M_LuaV.bytes',
            'beforePointer': (client / focus.lua.LUA / 'M_LuaV.bytes').read_bytes(), 'afterPointer': old_lua,
            'manifestPath': manifest, 'beforeManifest': manifest_bytes,
            'afterManifest': (json.dumps(updated, ensure_ascii=False, indent=2) + '\n').encode('utf8')}


def perform(plan, backup, require_stopped):
    require_stopped()
    backup = compat.outside_client(backup, plan['client'])
    compat.require(not backup.exists(), '撤回备份目录已存在')
    pairs = (('indexPath', 'beforeIndex'), ('pointerPath', 'beforePointer'), ('manifestPath', 'beforeManifest'))
    for path, content in pairs:
        compat.require(plan[path].read_bytes() == plan[content], '撤回前入口或清单变化')
    backup.mkdir(parents=True, exist_ok=False)
    for name, data in (('design-index.bytes', plan['beforeIndex']), ('M_LuaV.bytes', plan['beforePointer']),
                       ('patch-manifest.json', plan['beforeManifest'])):
        with (backup / name).open('xb') as stream:
            stream.write(data)
            stream.flush()
            os.fsync(stream.fileno())
        compat.require((backup / name).read_bytes() == data, '撤回备份校验失败')
    try:
        require_stopped()
        atomic(plan['indexPath'], plan['afterIndex'], compat.sha(plan['beforeIndex']))
        require_stopped()
        atomic(plan['pointerPath'], plan['afterPointer'], compat.sha(plan['beforePointer']))
        atomic(plan['manifestPath'], plan['afterManifest'], compat.sha(plan['beforeManifest']))
        require_stopped()
        for path, content in (('indexPath', 'afterIndex'), ('pointerPath', 'afterPointer'),
                              ('manifestPath', 'afterManifest')):
            compat.require(plan[path].read_bytes() == plan[content], '撤回后入口或清单校验失败')
    except Exception:
        # 如果游戏已启动，不在运行期间切换任何入口；保留备份以便后续处理。
        require_stopped()
        for path, before, after in (('manifestPath', 'beforeManifest', 'afterManifest'),
                                    ('pointerPath', 'beforePointer', 'afterPointer'),
                                    ('indexPath', 'beforeIndex', 'afterIndex')):
            current = plan[path].read_bytes()
            if current == plan[after]:
                atomic(plan[path], plan[before], compat.sha(plan[after]))
            else:
                compat.require(current == plan[before], '撤回失败后入口被其他任务修改')
        raise
    return {'passed': True, 'backup': str(backup), 'clientExecuted': False, 'playerDataModified': False}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--manifest', type=Path, required=True)
    parser.add_argument('--source-backup', type=Path, required=True)
    parser.add_argument('--lua-report', type=Path, required=True)
    parser.add_argument('--apply', action='store_true')
    parser.add_argument('--backup-dir', type=Path)
    args = parser.parse_args()
    plan = make_plan(args.manifest.resolve(), args.source_backup, args.lua_report)
    if args.apply:
        compat.require(args.backup_dir is not None, '撤回必须指定新备份目录')
        launcher = compat.module('research_revert_apply_launcher', 'start-local-client.py')
        result = perform(plan, args.backup_dir, launcher.require_stopped)
    else:
        result = {'passed': True, 'clientModified': False, 'clientExecuted': False}
    print(json.dumps(result, ensure_ascii=True))


if __name__ == '__main__': main()
