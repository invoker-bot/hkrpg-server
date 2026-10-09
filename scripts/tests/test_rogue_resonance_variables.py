"""变量写入顺序、原生任务工厂、完整原件及验收边界回归。"""
from copy import deepcopy
import hashlib
import importlib.util
import json
from pathlib import Path
import random
import tempfile
from types import SimpleNamespace
import unittest

import capstone

ROOT = Path(__file__).resolve().parents[2]
CLIENT = Path(r'C:\Users\InvokerBot\AppData\Local\hoyo\hkrpg\versions\current')
spec = importlib.util.spec_from_file_location('resonance_variables', ROOT / 'scripts/read-rogue-resonance-variables.py')
variables = importlib.util.module_from_spec(spec)
spec.loader.exec_module(variables)


class ResonanceVariablesTest(unittest.TestCase):
    def test_standard_clamp_preserves_lower_first_branch_and_signed_words(self):
        one = 2**33
        for raw, lower, upper, expected in [(-3*one, -one, one, -one), (3*one, -one, one, one),
                (0, -one, one, 0), (-one, -one, one, -one), (one, -one, one, one),
                (0, 2*one, -2*one, 2*one), (3*one, 2*one, -2*one, -2*one),
                (-2**63, -2**63, 2**63-2, -2**63)]:
            self.assertEqual(expected, variables.clamp(raw, lower, upper))

    def test_standard_clamp_random_signed_words_agree_with_independent_comparisons(self):
        rand = random.Random(46068532)
        for _ in range(4096):
            raw, lower, upper = (rand.randrange(-2**63, 2**63) & ~1 for _ in range(3))
            expected = lower if raw < lower else upper if raw > upper else raw
            self.assertEqual(expected, variables.clamp(raw, lower, upper))

    def test_extended_malformed_clamp_arguments_are_closed(self):
        for raw in [1, -1, True, False, 0.0, None, '0', -2**63-2, 2**63]:
            for index in range(3):
                args = [0, 0, 0]
                args[index] = raw
                with self.subTest(args=args), self.assertRaises(ValueError):
                    variables.clamp(*args)

    def test_bounded_native_order_clamp_observer_and_hexadecimal_task_states(self):
        profile = variables.load_profile()
        self.assertEqual((6, 91929, 72), (len(profile['nativeMethods']), profile['nativeBytes'], profile['fragmentBytes']))
        decoder = capstone.Cs(capstone.CS_ARCH_X86, capstone.CS_MODE_64)
        instructions = {}
        for method in profile['nativeMethods']:
            raw = bytes.fromhex(method['bytesHex'])
            self.assertEqual(method['sha256'], hashlib.sha256(raw).hexdigest())
            self.assertEqual('PE Exception Directory', method['bounds'])
            self.assertEqual(len(raw), int(method['endRva'],16) - int(method['rva'],16))
            instructions.update({i.address - 0x180000000: i for i in decoder.disasm(raw, 0x180000000 + int(method['rva'],16))})
        for fragment in profile['nativeFragments']:
            raw = bytes.fromhex(fragment['bytesHex'])
            self.assertFalse(fragment['completeNativeMethod'])
            self.assertEqual(fragment['sha256'], hashlib.sha256(raw).hexdigest())
            instructions.update({i.address - 0x180000000: i for i in decoder.disasm(raw, 0x180000000 + int(fragment['rva'],16))})
        for address, expected in [
                (0x153e9b3e, ('mov', 'rdi, qword ptr [rip - 0xc057b8d]')),
                (0x153e9bdd, ('call', '0x19b174dc0')),
                (0x1540134e, ('call', '0x192cfeb90')),
                (0x12cfeb9e, ('mov', 'dword ptr [rcx + 0x10], 0x7777')),
                (0x12cfec23, ('imul', 'edx, esi, 0x5d588b65')),
                (0x12cfecb5, ('call', '0x18e4c35d0')),
                (0x12cfedf8, ('sar', 'r8, cl')),
                (0x12cfedfb, ('movsxd', 'rax, r8d')),
                (0x12cfee27, ('call', '0x19cd7ea20')),
                (0x12cfee2c, ('mov', 'dword ptr [rsi + 0x10], 0x9999')),
                (0x1cd7eab7, ('mov', 'r9d, 3')), (0x1cd7eb47, ('mov', 'r9d, 5')),
                (0x1cd7ebd7, ('mov', 'r9d, 7')), (0x1cd7ec30, ('call', '0x19dcd1a00')),
                (0x1cd7ec77, ('mov', 'r9d, 4')), (0x1cd7ecc9, ('call', 'rbp')),
                (0x1cd7ed17, ('mov', 'r9d, 3')), (0x1cd7ed9d, ('call', 'rbp')),
                (0x1dcd1a0c, ('cmp', 'rcx, rax')), (0x1dcd1a0f, ('jge', '0x19dcd1a52')),
                (0x1dcd1a11, ('ret', '')), (0x1dcd1ab5, ('cmp', 'rcx, r8')),
                (0x1dcd1b01, ('cmovg', 'rcx, r8')),
                (0x1cd80fc0, ('mov', 'rax, qword ptr [rcx + 0x30]')),
                (0x1cd80fd0, ('mov', 'qword ptr [rcx + 0x30], rdx')),
                (0x1cd80fe0, ('mov', 'rax, qword ptr [rcx + 0x38]')),
                (0x1cd81000, ('mov', 'rax, qword ptr [rcx + 0x40]'))]:
            self.assertEqual(expected, (instructions[address].mnemonic, instructions[address].op_str), hex(address))
        # Interface ordinal 3/4/5/7 is not inferred from obfuscated method names.
        self.assertEqual(['get_Val','set_Val','get_MinValue','get_MaxValue'],
            [profile['variableInterface']['methods'][i]['name'] for i in [3,4,5,7]])

    def test_exact_native_factory_slots_interface_and_scope_constants_match_client(self):
        self.assertEqual((6, 91929), variables.verify_native(CLIENT))
        profile = variables.load_profile()
        self.assertEqual((1221, '0x9391fb8', '0x94ab780'),
            tuple(profile['runtimeRegistration'][k] for k in ['configVariant','configTypeSlotRva','methodInfoSlotRva']))
        self.assertEqual('CallbackTemporary', next(f['name'] for f in profile['scopeConstants']
            if f.get('constant',{}).get('value') == 9))

    def test_original_source_binding_and_explicit_lookup_are_distinct_from_live_battle(self):
        with tempfile.TemporaryDirectory(dir=ROOT / 'build') as temporary:
            report = variables.analyze(SimpleNamespace(client_dir=CLIENT, output=Path(temporary)/'report.json'))
        self.assertTrue(report['passed'])
        self.assertEqual((1221, 'Extra_AttackTime', 785),
            tuple(report['sourceTask'][k] for k in ['taskId','DynamicKey','$offset']))
        self.assertEqual(1555818884, report['dynamicKeyHash'])
        for key in ['originalAbilityRecordFullyRead', 'sourceTaskVariantMatchedToRuntimeFactory',
                'explicitLookupStandardCellsImplemented', 'explicitLookupRoundTaskImplemented', 'observerAfterClampedWrite']:
            self.assertTrue(report[key], key)
        for key in ['standardClampCompleteNativeMethod', 'missingCellAutomaticallyCreated',
                'contextLookupSelectionImplemented', 'defaultCellBoundsResolved', 'originalRoundTaskExecutedInBattle',
                'liveBattleContextAttached', 'abilityExecutionVerified', 'jsonInstalled',
                'clientExecutableStarted', 'clientModified', 'clientGameplayVerified', 'allResonanceAbilitiesImplemented']:
            self.assertFalse(report[key], key)

    def test_wrong_original_task_path_key_identity_or_variable_declaration_is_rejected(self):
        profile = variables.load_profile()
        # A finite source fragment is enough for this validation; no operator or client resources are written.
        ability = {'Name': profile['sourceAbilityName'], 'DynamicValues': profile['sourceDynamicValues'],
            'OnStart': [{}, {'SuccessTaskList': [{}, {}, profile['sourceTask']]}]}
        record = {**profile['sourceRecord'], 'decoded': {'AbilityList': [{}, {}, ability]}}
        self.assertEqual(profile['sourceTask'], variables.verify_source_task(record))
        for change in ['key', 'offset', 'variant', 'variable', 'name', 'record', 'path']:
            altered = deepcopy(record)
            node = altered['decoded']['AbilityList'][2]
            task = node['OnStart'][1]['SuccessTaskList'][2]
            if change == 'key': task['DynamicKey'] = '_CurrentSP'
            elif change == 'offset': task['$offset'] = 786
            elif change == 'variant': task['taskId'] = 2791
            elif change == 'variable': node['DynamicValues']['floats'][1]['key'] = 0
            elif change == 'name': node['Name'] = 'different'
            elif change == 'record': altered['sha256'] = '0'*64
            else: del node['OnStart']
            with self.subTest(change=change), self.assertRaises(ValueError):
                variables.verify_source_task(altered)

    def test_client_operator_and_resource_output_paths_are_rejected(self):
        for output in [CLIENT/'result.json', ROOT/'resources/4.6/result.json', ROOT/'config.json']:
            with self.subTest(output=str(output)), self.assertRaises(ValueError):
                variables.analyze(SimpleNamespace(client_dir=CLIENT, output=output))


if __name__ == '__main__':
    unittest.main()
