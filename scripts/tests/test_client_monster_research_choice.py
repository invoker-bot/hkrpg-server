"""检查首轮和重试均先打开对话界面，再提供原有三个课题。"""
import sys
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'scripts'))
sys.path.insert(0, str(ROOT / 'scripts/tests'))
import client_monster_research_choice as choice
import client_monster_research_compat as compat
import client_monster_research_focus as focus
import test_client_monster_research_retry as retry_test


def graphs():
    _, raw = focus.known_graphs(800017705)
    return [focus.mark_graph(item, 800017705) for item in raw]


def selection(graph):
    found = []
    for seq in graph['OnStartSequece']:
        for index, task in enumerate(seq['TaskList']):
            if task['$type'] == 'PlayOptionTalk' and any(
                item.get('TriggerCustomString', '').startswith('Research') for item in task['OptionList']
            ):
                found.append((seq, index))
    if len(found) != 1:
        raise AssertionError('研究选项应当唯一')
    return found[0]


class ResearchChoiceTest(unittest.TestCase):
    def test_first_and_retry_open_talk_before_research_options(self):
        for raw in graphs():
            with self.subTest(original_sha=focus.lua.sha(raw)):
                before = retry_test.graphs.decode_graph(raw, retry_test.PROFILE)
                after = retry_test.graphs.decode_graph(choice.patch_graph(raw), retry_test.PROFILE)
                seq, index = selection(after)
                self.assertEqual('ShowTalkUI', seq['TaskList'][index - 1]['$type'])
                self.assertTrue(seq['TaskList'][index - 1]['Show'])
                self.assertEqual('StartDialogueEntityInteract', seq['TaskList'][index - 2]['$type'])
                del seq['TaskList'][index - 1]
                self.assertEqual(retry_test.clean(before), retry_test.clean(after))

    def test_unknown_or_tampered_graph_is_rejected(self):
        with self.assertRaises(ValueError):
            choice.patch_graph(graphs()[0] + b'\0')
        with self.assertRaises(ValueError):
            choice.patch_graph(b'not a graph')

    def test_installed_choice_index_remains_readable_by_design_data_tools(self):
        reader = compat.module('choice_design_reader', 'read-client-design-data.py')
        self.assertEqual((255, 4, 33, 141295), reader.INDEX_PROFILES[choice.RESULT_SHA])


if __name__ == '__main__':
    unittest.main()
