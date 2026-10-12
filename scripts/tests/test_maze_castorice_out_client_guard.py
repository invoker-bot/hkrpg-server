"""Protect the actual typed task mapping and exact full original Out fixture, no runtime."""
import copy
import hashlib
import importlib.util
import json
from pathlib import Path
import unittest

HERE = Path(__file__).resolve().parent
ROOT = next(parent for parent in HERE.parents if (parent / 'scripts/analyze-client-46.py').is_file())
reader_path = ROOT / 'scripts/inspect-maze-castorice-out-client.py'
if not reader_path.is_file(): reader_path = HERE.parent / 'inspect-maze-castorice-out-client.py'
spec = importlib.util.spec_from_file_location('out_complete_binding', reader_path)
reader = importlib.util.module_from_spec(spec)
spec.loader.exec_module(reader)
fixture = json.loads((ROOT / 'src/test/resources/fixtures/maze-castorice-out-4.6.json').read_text('utf-8'))
proof = fixture['outNativeFullChain']

class OutSourceBindingGuard(unittest.TestCase):
    def test_actual_immediate_task_generic_argument_is_the_submit_template(self):
        parent = proof['submitTaskParentBinding']
        self.assertTrue(reader.verify_submit_parent(parent))
        self.assertEqual('ed60040000001500', parent['nativeParentRaw'])
        self.assertEqual('fb4e000000001200', parent['argumentBytesHex'])
        self.assertEqual('78d090a64d8375574db2e36721e38994265c23ca8ac398ea97061523f2166034', proof['startupSHA256'])

    def test_same_owner_name_with_another_template_is_not_submit(self):
        parent = copy.deepcopy(proof['submitTaskParentBinding'])
        parent['args'][0]['definition'] = 20207
        parent['args'][0]['name'] = 'AdventureTriggerAttack'
        with self.assertRaisesRegex(ValueError, 'original AdventureSubmitSkill'):
            reader.verify_submit_parent(parent)

    def test_unknown_generic_definition_or_nonclass_argument_cannot_use_the_mapping(self):
        for key, value in [('genericDefinition', 54789), ('nativeKind', 18)]:
            parent = copy.deepcopy(proof['submitTaskParentBinding'])
            parent[key] = value
            with self.assertRaises(ValueError): reader.verify_submit_parent(parent)
        parent = copy.deepcopy(proof['submitTaskParentBinding'])
        parent['args'][0]['kind'] = 21
        with self.assertRaises(ValueError): reader.verify_submit_parent(parent)

    def test_writer_defaults_match_fields_eleven_thirteen_and_fifteen(self):
        f = {m['index']: m for m in proof['selectedMethods']}
        self.assertEqual(8, len(f))
        self.assertIn('e84e1c5: mov qword ptr [rcx + 0x63f38], rax', f[635592]['instructions'])
        self.assertIn('1e69189c: mov ecx, 0x5a', f[232553]['instructions'])
        self.assertIn('1e690c97: mov byte ptr [rcx + rax + 0x20], 0x68', f[232578]['instructions'])
        self.assertIn('1e690cf0: mov byte ptr [rcx + rax + 0x20], 0x7a', f[232578]['instructions'])
        self.assertEqual([1, 2], proof['wirePattern']['tagsField11'])

    def test_new_fixture_keeps_all_twelve_complete_old_blobs_and_no_client_execution_claim(self):
        old = ROOT / 'src/test/resources/fixtures/maze-castorice-manual-ability-4.6.json'
        new = ROOT / 'src/test/resources/fixtures/maze-castorice-out-4.6.json'
        self.assertEqual('2a77f5ded624778f828a33febd8908a876f817cfa82cda518d0f8955a7706f93', hashlib.sha256(old.read_bytes()).hexdigest())
        self.assertEqual('f1f264a700ade7055fdda5d4647c3e43b1b443b43159887c7429e805d7624ee3', hashlib.sha256(new.read_bytes()).hexdigest())
        original, fixture = json.loads(old.read_text('utf-8')), json.loads(new.read_text('utf-8'))
        self.assertEqual(original['sourceFiles'], fixture['sourceFiles'])
        self.assertEqual(12, len(fixture['sourceFiles']))
        self.assertEqual(proof, fixture['outNativeFullChain'])
        self.assertFalse(proof['officialClientRequestCaptured'])
        self.assertFalse(proof['clientGameplayVerified'])

    def test_exact_original_out_is_submit_then_caster_removal_without_a_client_execution_claim(self):
        task = proof['exactOriginalOut']['OnStart'][2]
        self.assertEqual(['RPG.GameCore.AdventureSubmitSkill','RPG.GameCore.RemoveMazeBuff'],
            [row['$type'] for row in task['SuccessTaskList']])
        self.assertEqual(140704, task['SuccessTaskList'][1]['ID'])
        self.assertEqual('Caster', task['SuccessTaskList'][1]['TargetType']['Alias'])
        self.assertFalse(proof['clientGameplayVerified']); self.assertFalse(proof['officialClientRequestCaptured'])

if __name__ == '__main__': unittest.main()
