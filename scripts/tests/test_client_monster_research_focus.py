"""回归研究任务材料页的隔离标记与客户端界面层级。"""
import base64
import json
import struct
import sys
import tempfile
import unittest
import zlib
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'scripts'))
sys.path.insert(0, str(ROOT / 'scripts/tests'))
import client_monster_research_compat as compat
import client_monster_research_retry as retry
import client_monster_research_focus as focus
import test_client_monster_research_retry as graph_test


def page():
    fixture = json.loads((ROOT / 'scripts/fixtures/monster-research-submit-ui-4.6.json').read_text('utf8'))
    raw = zlib.decompress(base64.b64decode(fixture['data'], validate=True))
    assert compat.sha(raw) == fixture['sha256']
    return raw


def submission_nodes(raw, sub):
    marker = compat.var(1099)
    nodes = []
    for pos in range(len(raw)):
        if not raw.startswith(marker, pos):
            continue
        reader = graph_test.graphs.Reader(raw, graph_test.PROFILE)
        reader.pos = pos
        try:
            node = reader.read({'kind': 18, 'name': 'TaskConfig'})
        except (ValueError, IndexError, KeyError):
            continue
        if node.get('$type') == 'ConsumeMissionItemPerformance' and node.get('SubMissionID') == sub:
            nodes.append(node)
    return nodes


def branch_layer(raw, talk):
    """执行 init 前置判断，原 init 指令留给游戏处理。"""
    bc = focus.lua.Bytecode(raw)
    p = next(x for x in bc.protos if x['name'] == 'init')
    old = focus.lua.Bytecode(page())
    old_init = next(x for x in old.protos if x['name'] == 'init')
    boundary = len(p['code']) - len(old_init['code'])
    if boundary <= 0:
        return 'Page'
    code, constants = p['code'], p['constants']
    regs = [None] * p['header'][0]
    regs[0], regs[2] = {'_ui_layer': 'Page'}, talk
    globals_ = {'G': {'ImportCsToLua': {'CS_RPG_Client_UILayer_Dialog': 'Dialog'}}}
    pc = 0
    while pc < boundary:
        word = code[pc]; op = word & 255; a = (word >> 8) & 255; b = (word >> 16) & 255
        displacement = word >> 16
        if displacement >= 32768: displacement -= 65536
        pc += 1
        if op == 4:
            regs[a] = displacement
        elif op == 12:
            aux = code[pc]; pc += 1
            value = globals_
            for n in range(aux >> 30):
                value = value[constants[(aux >> (20 - n * 10)) & 1023]]
            regs[a] = value
        elif op == 15:
            key = constants[code[pc]]; pc += 1
            regs[a] = regs[b].get(key)
        elif op == 16:
            key = constants[code[pc]]; pc += 1
            regs[b][key] = regs[a]
        elif op == 26:
            if regs[a] is None or regs[a] is False: pc += displacement
        elif op == 30:
            other = code[pc]
            pc += displacement if regs[a] != regs[other] else 1
        else:
            raise AssertionError('未核对的前置指令 ' + str(op))
    assert pc == boundary
    return regs[0]['_ui_layer']


class ResearchFocusTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        _, cls.first = compat.fixture()
        _, cls.retry = retry.fixture()
        cls.original_page = page()

    def test_research_pages_are_marked_without_changing_callbacks(self):
        for stage, (main, ident, offset) in enumerate(zip(compat.MAINS, retry.RETRIES, compat.OFFSETS), 1):
            sub = main * 100 + (5 if stage == 7 else 4)
            initial_path = f'Config/Level/Mission/{main}/Act/Act{main * 100 + 1}.json'
            retry_path = f'Config/Level/NPCDialogue/P10001/F10001001_G10/DialogueItem{ident}_F10001001_G10_N400001.json'
            for kind, before in (('initial', compat.patch_initial(self.first[initial_path], main, stage, offset)),
                                 ('retry', retry.patch_retry_graph(self.retry[retry_path], main, stage))):
                with self.subTest(stage=stage, kind=kind):
                    after = focus.mark_graph(before, sub)
                    old_nodes, new_nodes = submission_nodes(before, sub), submission_nodes(after, sub)
                    self.assertEqual(3 if stage == 7 else 1, len(old_nodes))
                    self.assertEqual(len(old_nodes), len(new_nodes))
                    recovered = after
                    for new in reversed(new_nodes):
                        reader = graph_test.graphs.Reader(recovered, graph_test.PROFILE)
                        start = new['$offset'] - len(compat.var(1099))
                        reader.pos = start
                        reader.read({'kind': 18, 'name': 'TaskConfig'})
                        end = reader.pos
                        marker = compat.var(1) + struct.pack('<f', focus.MARKER)
                        self.assertEqual(marker, recovered[end - len(marker):end])
                        prefix = compat.var(1099) + compat.var(58)
                        self.assertTrue(recovered.startswith(prefix, start))
                        recovered = (recovered[:start] + compat.var(1099) + compat.var(26)
                                     + recovered[start + len(prefix):end - len(marker)] + recovered[end:])
                    self.assertEqual(before, recovered)
                    for old, new in zip(old_nodes, new_nodes):
                        self.assertNotIn('SimpleTalk', old)
                        self.assertIn('SimpleTalk', new)
                        self.assertEqual(focus.MARKER, new['SimpleTalk']['TextSpeed'])
                        self.assertNotIn('TalkSentenceID', new['SimpleTalk'])
                        self.assertEqual(graph_test.clean(old['OnSubmitConfirm']), graph_test.clean(new['OnSubmitConfirm']))
                        self.assertEqual(graph_test.clean(old['OnSubmitCancel']), graph_test.clean(new['OnSubmitCancel']))

    def test_only_marked_research_page_moves_above_the_dialogue_page(self):
        changed = focus.patch_page(self.original_page)
        self.assertEqual('Dialog', branch_layer(changed, {'TextSpeed': focus.MARKER, 'TalkSentenceID': 0}))
        for normal in (None, {}, {'TextSpeed': 0}, {'TextSpeed': 1.0},
                       {'TextSpeed': focus.MARKER, 'TalkSentenceID': 1}):
            with self.subTest(normal=normal):
                self.assertEqual('Page', branch_layer(changed, normal))

    def test_page_preserves_existing_init_and_all_other_functions(self):
        changed = focus.patch_page(self.original_page)
        old, new = focus.lua.Bytecode(self.original_page), focus.lua.Bytecode(changed)
        a = next(p for p in old.protos if p['name'] == 'init')
        b = next(p for p in new.protos if p['name'] == 'init')
        self.assertGreater(len(b['code']), len(a['code']))
        self.assertEqual(a['code'], b['code'][-len(a['code']):])
        self.assertEqual(a['lines'], b['lines'][-len(a['lines']):])
        self.assertEqual(old.main, new.main)
        self.assertEqual(old.strings, new.strings[:len(old.strings)])
        for oldp, newp in zip(old.protos, new.protos):
            if oldp['name'] != 'init':
                self.assertEqual(self.original_page[oldp['start']:oldp['end']], changed[newp['start']:newp['end']])

    def test_wrong_graph_or_page_is_rejected(self):
        with self.assertRaises(ValueError): focus.patch_page(self.original_page + b'\x00')
        with self.assertRaises(ValueError): focus.mark_graph(b'not a graph', 800017004)

    def test_second_step_failure_restores_design_only_if_client_stays_stopped(self):
        workspace_build = (ROOT / 'build').resolve()
        workspace_build.mkdir(exist_ok=True)
        with tempfile.TemporaryDirectory(dir=workspace_build, prefix='research-focus-test-') as directory:
            root = Path(directory).resolve()
            self.assertTrue(root.is_relative_to(workspace_build))
            client, prepared, backup = root / 'client', root / 'prepared', root / 'backup'
            client.mkdir()
            index = client / compat.CLIENT_DIR / 'DesignV_1234567890abcdef1234567890abcdef.bytes'
            index.parent.mkdir(parents=True)
            original, modified = b'original design index', b'changed design index'
            manifest = root / 'manifest.json'
            manifest.write_text(json.dumps({'clientRoot': str(client)}), encoding='utf8')
            design, lua_prepared = prepared / 'design', prepared / 'lua'
            design.mkdir(parents=True); lua_prepared.mkdir()
            (design / 'prepared.json').write_text(json.dumps({'indexName': index.name,
                'newIndexSHA256': compat.sha(modified)}), encoding='utf8')
            (lua_prepared / 'prepared.json').write_text('{}', encoding='utf8')
            def fake_design_install(source, dest, manifest_path):
                dest.mkdir(parents=True)
                (dest / index.name).write_bytes(original)
                index.write_bytes(modified)
                return {'installed': True}
            for running in (False, True):
                with self.subTest(game_started=running):
                    index.write_bytes(original)
                    this_backup = backup.with_name('backup-' + str(running))
                    launcher = SimpleNamespace(require_stopped=lambda: (_ for _ in ()).throw(RuntimeError('running'))
                                               if running else None)
                    argv = ['focus', '--manifest', str(manifest), '--prepare-dir', str(prepared),
                            '--apply', '--backup-dir', str(this_backup)]
                    with patch.object(sys, 'argv', argv), \
                         patch.object(compat, 'install', side_effect=fake_design_install), \
                         patch.object(compat, 'module', return_value=launcher), \
                         patch.object(focus.lua, 'install', side_effect=ValueError('injected failure')):
                        with self.assertRaises(RuntimeError if running else ValueError): focus.main()
                    self.assertEqual(modified if running else original, index.read_bytes())


if __name__ == '__main__': unittest.main()
