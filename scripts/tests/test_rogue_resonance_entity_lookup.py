"""实体选择分支、原生缺失值初始化和写入顺序的来源回归。"""
from copy import deepcopy
import hashlib
import importlib.util
from pathlib import Path
from types import SimpleNamespace
import unittest

import capstone

ROOT = Path(__file__).resolve().parents[2]
CLIENT = Path(r'C:\Users\InvokerBot\AppData\Local\hoyo\hkrpg\versions\current')
spec = importlib.util.spec_from_file_location('resonance_entity', ROOT / 'scripts/read-rogue-resonance-entity-lookup.py')
entity = importlib.util.module_from_spec(spec)
spec.loader.exec_module(entity)


class ResonanceEntityLookupTest(unittest.TestCase):
    def instructions(self):
        decoder = capstone.Cs(capstone.CS_ARCH_X86, capstone.CS_MODE_64)
        decoder.detail = True
        profile = entity.load_profile()
        write = entity.scoped.context.defaults.write.load_profile()
        result = {}
        for method in profile['nativeMethods'] + write['nativeMethods']:
            raw = bytes.fromhex(method['bytesHex'])
            self.assertEqual(method['sha256'], hashlib.sha256(raw).hexdigest())
            self.assertEqual('PE Exception Directory', method['bounds'])
            self.assertEqual(len(raw), int(method['endRva'], 16) - int(method['rva'], 16))
            result.update({i.address - 0x180000000: i for i in decoder.disasm(raw, 0x180000000 + int(method['rva'], 16))})
        return result

    def check(self, rows):
        ins = self.instructions()
        for address, expected in rows:
            self.assertEqual(expected, (ins[address].mnemonic, ins[address].op_str), hex(address))

    def rip(self, instruction):
        operand = next(o for o in instruction.operands if o.type == capstone.x86.X86_OP_MEM)
        return instruction.address + instruction.size + operand.mem.disp - 0x180000000

    def test_seven_complete_methods_and_readonly_constant_match_installed_client(self):
        self.assertEqual((7, 11128), entity.verify_native(CLIENT))
        self.assertEqual(0, entity.read_missing_raw(CLIENT))
        profile = entity.load_profile()
        self.assertEqual([508324, 70469, 508325, 74755, 509828, 68417, 508322], [m['index'] for m in profile['nativeMethods']])
        self.assertEqual((1, 1333, 116), (len(profile['nativeRuntimeFunctions']), profile['nativeRuntimeFunctionBytes'], profile['fragmentBytes']))

    def test_missing_dictionary_branch_writes_initializer_slot_and_returns_false(self):
        self.check([(0x1cd7dcd5, ('js', '0x19cd7dd59')),
                    (0x1cd7dd9c, ('test', 'edi, edi')),
                    (0x1cd7dda1, ('mov', 'qword ptr [r14], rax'))])
        ins = self.instructions()
        self.assertEqual(0x9343d30, self.rip(ins[0x1cd7dd59]))
        self.assertEqual('setns', ins[0x1cd7dd9e].mnemonic)

    def test_zero_has_actual_initializer_source_bytes_and_correct_second_word_destination(self):
        fact = entity.load_profile()['missingReadInitializer']
        ins = self.instructions()
        self.assertEqual(('movaps', 'xmm0, xmmword ptr [rip - 0x18765f3a]'), (ins[0x1dcd3aa3].mnemonic, ins[0x1dcd3aa3].op_str))
        self.assertEqual(0x556db70, self.rip(ins[0x1dcd3aa3]))
        self.assertEqual(0x9343d28, self.rip(ins[0x1dcd3aaa]))
        self.assertEqual(0x9343d30, int(fact['pairDestinationRva'], 16) + fact['missingWordOffset'])
        self.assertEqual(bytes(8), bytes.fromhex(fact['bytesHex'])[8:])
        self.assertEqual('movups', ins[0x1dcd3aaa].mnemonic)

    def test_owner_aitag_context_selects_container_before_any_key_check(self):
        self.check([(0xe4c3978, ('mov', 'rdi, qword ptr [rax + 0x18]')),
                    (0xe4c397f, ('jne', '0x18e4c3d25'))])
        self.assertEqual([0, 1, 2, 3, 4, 5, 7, 8, 9], entity.load_profile()['supportedScopes'])

    def test_entity_role_reference_matches_delegate_except_target_scope_six(self):
        self.check([(0xe4c33df, ('call', '0x18b10c6a0')), (0xe4c33e4, ('cmp', 'rax, rdi')),
                    (0xe4c33e7, ('je', '0x18e4c343d')), (0xe4c343d, ('cmp', 'ebp, 6')),
                    (0xe4c3440, ('jne', '0x18e4c3465')), (0xe4c3407, ('cmp', 'ebp, 6')),
                    (0xe4c340a, ('je', '0x18e4c3411')), (0xe4c340c, ('cmp', 'rax, rdi')),
                    (0xe4c340f, ('je', '0x18e4c3465')), (0xe4c348c, ('jmp', '0x18e4c35d0'))])

    def test_foreign_entity_aitag_callback_and_custom_container_branches_are_distinct(self):
        self.check([(0xe4c3411, ('cmp', 'ebp, 7')), (0xe4c3414, ('je', '0x18e4c3491')),
                    (0xe4c3416, ('cmp', 'ebp, 9')), (0xe4c3419, ('jne', '0x18e4c3442')),
                    (0xe4c3435, ('mov', 'r8d, 9')), (0xe4c3455, ('call', '0x18b10cc50')),
                    (0xe4c345f, ('mov', 'rax, qword ptr [rax + 0x58]')),
                    (0xe4c34c1, ('call', '0x18b10cc50')), (0xe4c34cb, ('mov', 'rax, qword ptr [rax + 0x18]'))])
        self.assertEqual(list(range(10)), entity.load_profile()['supportedEntityScopes'])

    def test_expression_evaluation_precedes_target_iteration_then_each_selection_precedes_setter(self):
        self.check([(0x15931973, ('call', '0x18e777c90')), (0x159319d7, ('call', '0x18e7796f0')),
                    (0x15931a5d, ('call', '0x18e4c33b0')), (0x15931a74, ('call', '0x19cd7ea20'))])

    def fixture_record(self):
        profile = entity.load_profile()
        ability = {'Name': profile['sourceAbilityName'], 'DynamicValues': deepcopy(profile['sourceDynamicValues']),
            'OnStart': [{'SuccessTaskList': [{}, {}, {}]}, {'SuccessTaskList': [{}, {}, {}, {}]}]}
        for row in entity.scoped.context.defaults.write.load_profile()['sourceTasks']:
            ability['OnStart'][row['path'][3]]['SuccessTaskList'][row['path'][5]] = deepcopy(row['task'])
        return {**profile['sourceRecord'], 'decoded': {'AbilityList': [{}, {}, ability]}}

    def test_original_source_inputs_are_matched_and_changed_inputs_rejected(self):
        record = self.fixture_record()
        inputs = entity.scoped.context.verify_source_inputs(record)
        self.assertEqual((4, 6), (len(inputs['fixedDeclarations']), len(inputs['writeTasks'])))
        changed = deepcopy(record)
        changed['decoded']['AbilityList'][2]['DynamicValues']['floats'][0]['fixedSignedRaw'] = 1
        with self.assertRaises(ValueError): entity.scoped.context.verify_source_inputs(changed)

    def test_live_source_report_retains_unresolved_binding_provider_and_gameplay_flags(self):
        report = entity.analyze(SimpleNamespace(client_dir=CLIENT, output=ROOT / 'build/rogue-resonance-entity-lookup-test-evidence.json'))
        for key in ['passed', 'fixedEntityLookupImplemented', 'aitagMissingValueRepresentationResolved',
                    'expressionEvaluatedBeforeEntitySelection', 'previousWritesRetainedAfterSelectionFailure']:
            self.assertIs(True, report[key], key)
        self.assertEqual((7, 11128, 16, 0), tuple(report[k] for k in ['matchedNativeMethods', 'matchedNativeBytes', 'missingReadInitializerBytes', 'missingReadRaw']))
        for key in ['gettersCompleteNativeMethods', 'entitySelectorChecksKeyPresence', 'contextTargetScopeSupported',
                    'liveSourceProjectionsResolved', 'genericEntityComponentTypeArgumentsResolved',
                    'contextLookupSelectionFullyImplemented', 'actualParameterValuesResolved', 'nativeNumericProviderImplemented',
                    'dynamicCellProvidersImplemented', 'liveCasterPropertiesRead', 'liveBattleContextAttached',
                    'originalTaskBranchesExecutedInBattle', 'abilityExecutionVerified', 'jsonInstalled',
                    'clientExecutableStarted', 'clientModified', 'clientGameplayVerified', 'allResonanceAbilitiesImplemented']:
            self.assertIs(False, report[key], key)

    def test_outputs_outside_build_are_rejected_before_native_read(self):
        for output in [ROOT / 'entity-evidence.json', CLIENT / 'entity-evidence.json']:
            with self.subTest(output=output), self.assertRaises(ValueError):
                entity.analyze(SimpleNamespace(client_dir=CLIENT, output=output))


if __name__ == '__main__':
    unittest.main()
