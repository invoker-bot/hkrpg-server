"""上下文查找前段、作用域跳表、逐键调用和隔离来源的回归。"""
from copy import deepcopy
import hashlib
import importlib.util
from pathlib import Path
import struct
from types import SimpleNamespace
import unittest

import capstone

ROOT = Path(__file__).resolve().parents[2]
CLIENT = Path(r'C:\Users\InvokerBot\AppData\Local\hoyo\hkrpg\versions\current')
spec = importlib.util.spec_from_file_location('resonance_context', ROOT / 'scripts/read-rogue-resonance-context.py')
context = importlib.util.module_from_spec(spec)
spec.loader.exec_module(context)


class ResonanceContextTest(unittest.TestCase):
    def fixture_record(self):
        profile = context.load_profile()
        ability = {'Name': profile['sourceAbilityName'], 'DynamicValues': deepcopy(profile['sourceDynamicValues']),
            'OnStart': [{'SuccessTaskList': [{}, {}, {}]}, {'SuccessTaskList': [{}, {}, {}, {}]}]}
        for row in context.defaults.write.load_profile()['sourceTasks']:
            ability['OnStart'][row['path'][3]]['SuccessTaskList'][row['path'][5]] = deepcopy(row['task'])
        return {**profile['sourceRecord'], 'decoded': {'AbilityList': [{}, {}, ability]}}

    def instructions(self):
        decoder = capstone.Cs(capstone.CS_ARCH_X86, capstone.CS_MODE_64)
        decoder.detail = True
        result = {}
        for method in context.load_profile()['nativeMethods'] + context.load_profile()['nativeFragments']:
            raw = bytes.fromhex(method['bytesHex'])
            self.assertEqual(method['sha256'], hashlib.sha256(raw).hexdigest())
            result.update({i.address - 0x180000000: i for i in decoder.disasm(raw, 0x180000000 + int(method['rva'], 16))})
        return result

    def test_full_context_vm_value_reader_and_provider_fragment_match_fixed_client(self):
        self.assertEqual((3, 9257), context.verify_native(CLIENT))
        profile = context.load_profile()
        self.assertEqual([508324, 70469, 508325], [m['index'] for m in profile['nativeMethods']])
        self.assertEqual((509824, 103, False), tuple(profile['nativeFragments'][0][k]
            for k in ['index', 'length', 'completeNativeMethod']))

    def test_context_prefix_order_and_explicit_scope_gates_follow_native_branches(self):
        ins = self.instructions()
        for address, expected in [
                (0xe4c35f4, ('cmp', 'esi, 9')), (0xe4c35f7, ('je', '0x18e4c3601')),
                (0xe4c35f9, ('test', 'esi, esi')), (0xe4c35fb, ('jne', '0x18e4c36dd')),
                (0xe4c36c5, ('cmp', 'esi, 9')), (0xe4c36c8, ('jne', '0x18e4c36dd')),
                (0xe4c3714, ('mov', 'rdi, qword ptr [rbx + 0x50]')),
                (0xe4c3732, ('mov', 'rdi, qword ptr [rdi + 0x18]')),
                (0xe4c3743, ('mov', 'rax, qword ptr [rdi + 0x10]')),
                (0xe4c3763, ('jns', '0x18e4c3777')),
                (0xe4c3784, ('mov', 'rdi, qword ptr [rbx + 0x60]')),
                (0xe4c38c1, ('mov', 'rax, qword ptr [rbx + 0x88]')),
                (0xe4c38f5, ('js', '0x18e4c39d6')),
                (0xe4c39d6, ('test', 'esi, esi')), (0xe4c39d8, ('jne', '0x18e4c4221'))]:
            self.assertEqual(expected, (ins[address].mnemonic, ins[address].op_str), hex(address))

    def test_scope_jump_tables_are_inside_full_method_and_rip_targets_match(self):
        profile = context.load_profile()
        ins = self.instructions()
        method = profile['nativeMethods'][0]
        raw = bytes.fromhex(method['bytesHex'])
        for table in profile['scopeTables']:
            address = int(table['tableRva'], 16)
            i = ins[int(table['instructionRva'], 16)]
            operand = next(o for o in i.operands if o.type == capstone.x86.X86_OP_MEM)
            self.assertEqual(capstone.x86.X86_REG_RIP, operand.mem.base)
            self.assertEqual(address, i.address + i.size + operand.mem.disp - 0x180000000)
            data = bytes.fromhex(table['bytesHex'])
            start = address - int(method['rva'], 16)
            self.assertEqual(data, raw[start:start + len(data)])
            targets = {str(index + table['firstScope']): hex(address + relative)
                for index, relative in enumerate(struct.unpack('<' + 'i' * (len(data) // 4), data))}
            self.assertEqual(table['targets'], targets)
        self.assertEqual([0, 1, 2, 9], profile['supportedScopes'])

    def test_vm_reads_numeric_reference_then_calls_provider_with_context_scope_zero(self):
        ins = self.instructions()
        for address, expected in [
                (0x1d23e5f4, ('mov', 'ebp, dword ptr [rdx + rax*4 + 1]')),
                (0x1d23e633, ('cmp', 'dword ptr [rsi + 0x18], ebp')),
                (0x1d23e66a, ('mov', 'rbx, qword ptr [r12 + 0x18]')),
                (0x1d23f28a, ('mov', 'edx, ebp')), (0x1d23f28c, ('call', 'rdi')),
                (0xe779e39, ('xor', 'r8d, r8d')), (0xe779e42, ('jmp', '0x18e4c4ca0')),
                (0xe4c4cea, ('call', '0x18e4c35d0')),
                (0xe4c4cff, ('mov', 'qword ptr [rax + 0x20], rdi')),
                (0xe4c4d32, ('mov', 'qword ptr [rdx + 0x20], rcx'))]:
            self.assertEqual(expected, (ins[address].mnemonic, ins[address].op_str), hex(address))
        provider = context.load_profile()['numericProviderInterface']
        self.assertEqual((10805, 'PFMDDEGLECM', 'Evaluate'), (provider['typeIndex'], provider['name'], provider['methods'][0]['name']))

    def test_original_declarations_and_six_source_tasks_remain_explicit_inputs(self):
        inputs = context.verify_source_inputs(self.fixture_record())
        self.assertEqual(4, len(inputs['fixedDeclarations']))
        self.assertEqual([547, 571, 606, 719, 743, 805], [r['offset'] for r in inputs['writeTasks']])

    def test_changed_source_identity_declarations_and_task_content_are_rejected(self):
        for kind in ['identity', 'declaration', 'task']:
            record = self.fixture_record()
            if kind == 'identity': record['sha256'] = '0' * 64
            elif kind == 'declaration': record['decoded']['AbilityList'][2]['DynamicValues']['floats'][0]['rawReadType'] = 1
            else: record['decoded']['AbilityList'][2]['OnStart'][0]['SuccessTaskList'][0]['Value'] = 13
            with self.subTest(kind=kind), self.assertRaises(ValueError): context.verify_source_inputs(record)

    def test_live_static_evidence_keeps_tail_provider_actual_bindings_and_gameplay_closed(self):
        report = context.analyze(SimpleNamespace(client_dir=CLIENT, output=ROOT / 'build/rogue-resonance-context-test-evidence.json'))
        self.assertTrue(report['passed'] and report['explicitPrefixContextImplemented'] and report['numericResolverImplemented'])
        self.assertEqual((3, 9257, 103, 8), tuple(report[k] for k in ['matchedNativeMethods', 'matchedNativeBytes',
            'providerInstructionBytes', 'matchedNativeFieldFacts']))
        for key in ['providerFragmentCompleteNativeMethod', 'nativeContextTailImplemented', 'nativeNumericProviderImplemented',
                    'dynamicCellProvidersImplemented', 'actualSourceDeclarationsBoundToTemplate', 'contextLookupSelectionFullyImplemented',
                    'actualParameterValuesResolved', 'liveCasterPropertiesRead', 'liveBattleContextAttached',
                    'originalTaskBranchesExecutedInBattle', 'abilityExecutionVerified', 'jsonInstalled',
                    'clientExecutableStarted', 'clientModified', 'clientGameplayVerified', 'allResonanceAbilitiesImplemented']:
            self.assertIs(False, report[key], key)

    def test_invalid_output_locations_are_rejected_before_native_reads(self):
        for output in [ROOT / 'context-evidence.json', CLIENT / 'context-evidence.json']:
            with self.subTest(output=output), self.assertRaises(ValueError):
                context.analyze(SimpleNamespace(client_dir=CLIENT, output=output))


if __name__ == '__main__':
    unittest.main()
