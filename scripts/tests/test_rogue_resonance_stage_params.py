"""参数原始格式、数组位置/list追加、定点词直拷与核验边界。"""
import hashlib
import importlib.util
from pathlib import Path
from types import SimpleNamespace
import unittest

import capstone

ROOT = Path(__file__).resolve().parents[2]
CLIENT = Path(r'C:\Users\InvokerBot\AppData\Local\hoyo\hkrpg\versions\current')
spec = importlib.util.spec_from_file_location('resonance_stageparams',ROOT/'scripts/read-rogue-resonance-stage-params.py')
params = importlib.util.module_from_spec(spec)
spec.loader.exec_module(params)


class ResonanceStageParamsTest(unittest.TestCase):
    def instructions(self):
        decoder = capstone.Cs(capstone.CS_ARCH_X86,capstone.CS_MODE_64)
        result = {}
        for m in params.load_profile()['nativeMethods']:
            raw = bytes.fromhex(m['bytesHex'])
            self.assertEqual(m['sha256'],hashlib.sha256(raw).hexdigest())
            self.assertEqual(int(m['endRva'],16)-int(m['rva'],16),len(raw))
            result.update({i.address-0x180000000:(i.mnemonic,i.op_str)
                for i in decoder.disasm(raw,0x180000000+int(m['rva'],16))})
        return result

    def test_four_complete_methods_and_native_fields_match_fixed_client(self):
        self.assertEqual((4,2038),params.verify_native(CLIENT))
        p = params.load_profile()
        self.assertEqual([508228,508229,508230,508231],[m['index'] for m in p['nativeMethods']])
        self.assertEqual(('Key',16,14),tuple(p['entryTypeFields']['fields'][0][k] if k!='kind'
            else p['entryTypeFields']['fields'][0]['nativeType']['kind'] for k in ['name','instanceOffset','kind']))
        self.assertEqual(('Value',24,9939),(p['entryTypeFields']['fields'][1]['name'],
            p['entryTypeFields']['fields'][1]['instanceOffset'],p['entryTypeFields']['fields'][1]['nativeType']['typeIndex']))

    def test_wrapper_returns_null_for_missing_or_empty_source_and_builds_from_zero(self):
        i = self.instructions()
        for address,expected in [
            (0xe4b1602,('test','rsi, rsi')),(0xe4b1605,('je','0x18e4b1641')),
            (0xe4b160b,('test','rax, rax')),(0xe4b160e,('je','0x18e4b1641')),
            (0xe4b1617,('mov','edx, eax')),(0xe4b1637,('xor','r8d, r8d')),
            (0xe4b163a,('call','0x18e4b1680')),(0xe4b1641,('xor','edi, edi'))]:
            self.assertEqual(expected,i[address],hex(address))

    def test_array_assignment_uses_source_index_plus_one_and_same_destination_slot(self):
        i = self.instructions()
        for address,expected in [
            (0xe4b1695,('mov','edi, r8d')),(0xe4b16c2,('cmp','eax, edi')),
            (0xe4b16c4,('jle','0x18e4b17d1')),(0xe4b16d3,('movsxd','rbx, edi')),
            (0xe4b16d6,('add','rbx, 4')),(0xe4b16ff,('lea','ebp, [rdi + 1]')),
            (0xe4b175e,('mov','rax, qword ptr [r12 + rbx*8]')),
            (0xe4b1762,('mov','qword ptr [rsi + 0x18], rax')),
            (0xe4b17b1,('mov','qword ptr [r13 + rbx*8], rsi'))]:
            self.assertEqual(expected,i[address],hex(address))

    def test_list_builder_uses_source_index_but_appends_to_list_count(self):
        i = self.instructions()
        for address,expected in [
            (0xe4b18c5,('mov','r13d, r8d')),(0xe4b1930,('lea','ebp, [r13 + 1]')),
            (0xe4b1992,('mov','rax, qword ptr [r12 + rbx*8]')),
            (0xe4b1996,('mov','qword ptr [rsi + 0x18], rax')),
            (0xe4b19b6,('movsxd','rdx, dword ptr [r14 + 0x18]')),
            (0xe4b19c2,('mov','dword ptr [r14 + 0x18], ecx')),
            (0xe4b19c6,('mov','qword ptr [rax + rdx*8 + 0x20], rsi'))]:
            self.assertEqual(expected,i[address],hex(address))

    def test_interface_list_builder_starts_at_zero_and_copies_each_raw_word(self):
        i = self.instructions()
        for address,expected in [
            (0xe4b1b84,('xor','r13d, r13d')),(0xe4b1be2,('inc','r13')),
            (0xe4b1be5,('mov','dword ptr [rsp + 0x2c], r13d')),
            (0xe4b1c40,('mov','rax, qword ptr [rsi + rbp*8 + 0x20]')),
            (0xe4b1c45,('mov','qword ptr [rbx + 0x18], rax'))]:
            self.assertEqual(expected,i[address],hex(address))

    def test_name_literal_rip_targets_keep_leading_prefix_for_all_builders(self):
        p = params.load_profile()
        self.assertEqual('#ADF_{0}',p['formatLiteral']['value'])
        for address,displacement in [(0xe4b1715,0x4d9e58c),(0xe4b1949,0x4d9e7c0),(0xe4b1bf9,0x4d9ea70)]:
            self.assertEqual(0x9713190,address+7-displacement)
            self.assertEqual(('mov',f'rdx, qword ptr [rip - {hex(displacement)}]'),self.instructions()[address])
        self.assertEqual((15143,'StageParamEntry'),(p['entryTypeSlot']['typeIndex'],p['entryTypeSlot']['typeName']))

    def test_evidence_keeps_actual_params_alias_mapping_and_gameplay_closed(self):
        r = params.analyze(SimpleNamespace(client_dir=CLIENT,output=ROOT/'build/rogue-resonance-stage-params-test-evidence.json'))
        self.assertTrue(r['passed'])
        for key in ['explicitStandardStageParameterBuilderImplemented','sourcePositionNamesPreserved',
            'arrayAssignmentPreservesEarlierSlots','listAppendsPreserveExistingEntries']:
            self.assertIs(True,r[key],key)
        for key in ['convertsThroughFloat','unprefixedExpressionAliasMappingImplemented','actualParameterValuesResolved',
            'liveBattleContextAttached','liveCasterPropertiesRead','originalTaskBranchesExecutedInBattle',
            'abilityExecutionVerified','clientGameplayVerified','allResonanceAbilitiesImplemented','extendedCellsImplemented',
            'jsonInstalled','clientExecutableStarted','clientModified']:
            self.assertIs(False,r[key],key)

    def test_client_and_nonbuild_outputs_rejected_before_native_read(self):
        for output in [CLIENT/'params-evidence.json',ROOT/'docs/params-evidence.json']:
            with self.subTest(output=output),self.assertRaises(ValueError):
                params.analyze(SimpleNamespace(client_dir=CLIENT,output=output))


if __name__ == '__main__': unittest.main()
