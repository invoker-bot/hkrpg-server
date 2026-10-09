"""临时别名绑定的完整原生方法、正常/异常恢复与输出边界。"""
import hashlib
import importlib.util
from pathlib import Path
from types import SimpleNamespace
import unittest

import capstone

ROOT = Path(__file__).resolve().parents[2]
CLIENT = Path(r'C:\Users\InvokerBot\AppData\Local\hoyo\hkrpg\versions\current')
spec = importlib.util.spec_from_file_location('resonance_aliascontext',ROOT/'scripts/read-rogue-resonance-alias-context.py')
binding = importlib.util.module_from_spec(spec)
spec.loader.exec_module(binding)


class ResonanceAliasContextTest(unittest.TestCase):
    def instructions(self):
        decoder = capstone.Cs(capstone.CS_ARCH_X86,capstone.CS_MODE_64)
        result = {}
        for m in binding.load_profile()['nativeMethods']:
            raw = bytes.fromhex(m['bytesHex'])
            self.assertEqual(m['sha256'],hashlib.sha256(raw).hexdigest())
            self.assertEqual(int(m['endRva'],16)-int(m['rva'],16),len(raw))
            result.update({i.address-0x180000000:(i.mnemonic,i.op_str)
                for i in decoder.disasm(raw,0x180000000+int(m['rva'],16))})
        return result

    def test_three_complete_native_methods_match_fixed_client(self):
        self.assertEqual((3,811),binding.verify_native(CLIENT))
        self.assertEqual([74820,508325,509825],[m['index'] for m in binding.load_profile()['nativeMethods']])

    def test_save_then_bind_before_value_getter_and_restore_on_return(self):
        i = self.instructions()
        for address,expected in [
            (0xe4c4cea,('call','0x18e4c35d0')),
            (0xe4c4cf7,('mov','rdx, qword ptr [rax + 0x20]')),
            (0xe4c4cfb,('mov','qword ptr [rbp - 0x20], rdx')),
            (0xe4c4cff,('mov','qword ptr [rax + 0x20], rdi')),
            (0xe4c4d24,('call','rdi')),
            (0xe4c4d2a,('mov','rcx, qword ptr [rbp - 0x20]')),
            (0xe4c4d32,('mov','qword ptr [rdx + 0x20], rcx')),
            (0xe4c4d3e,('ret',''))]:
            self.assertEqual(expected,i[address],hex(address))

    def test_exception_cleanup_restores_same_old_evaluator_before_rethrow(self):
        i = self.instructions()
        for address,expected in [
            (0xe4c4dcb,('mov','rax, qword ptr [rbp - 0x10]')),
            (0xe4c4dcf,('mov','rcx, qword ptr [rbp - 0x20]')),
            (0xe4c4dd3,('mov','qword ptr [rax + 0x20], rcx')),
            (0xe4c4de5,('mov','rcx, qword ptr [rbp - 0x18]')),
            (0xe4c4deb,('call','0x183cc8040'))]:
            self.assertEqual(expected,i[address],hex(address))

    def test_numeric_dispatch_uses_same_key_virtual_slot_seven(self):
        i = self.instructions()
        for address,expected in [
            (0xe4c4cb8,('mov','esi, edx')),
            (0xe4c4d03,('mov','rdi, qword ptr [rcx + 0xf8]')),
            (0xe4c4d1f,('mov','edx, esi')),
            (0xe4c4d7f,('mov','edx, 7')),
            (0xe4c4d97,('add','ecx, 7'))]:
            self.assertEqual(expected,i[address],hex(address))

    def test_task_context_alias_adapter_preserves_config_context_and_output_key(self):
        i = self.instructions()
        for address,expected in [
            (0xe779e87,('mov','esi, r8d')),(0xe779e8a,('mov','rdi, rdx')),
            (0xe779e8d,('mov','rbx, rcx')),(0xe779ea9,('mov','rcx, rdi')),
            (0xe779eac,('mov','rdx, rbx')),(0xe779eaf,('mov','r8d, esi')),
            (0xe779eb9,('jmp','0x18b223af0'))]:
            self.assertEqual(expected,i[address],hex(address))

    def test_alias_binding_field_is_distinct_interface_not_numeric_provider(self):
        p = binding.load_profile()
        f = next(f for f in p['lookupTypeFields']['fields'] if f['instanceOffset']==32)
        self.assertEqual(('<AliasEvaluator>k__BackingField',10849,'GGMPKKNCLHF'),
            (f['name'],f['nativeType']['typeIndex'],f['nativeType']['name']))
        self.assertNotEqual(10805,f['nativeType']['typeIndex'])

    def test_static_evidence_opens_only_explicit_context_binding(self):
        r = binding.analyze(SimpleNamespace(client_dir=CLIENT,output=ROOT/'build/rogue-resonance-alias-context-test-evidence.json'))
        self.assertTrue(r['passed'])
        for key in ['explicitContextAliasBindingImplemented','restoresPreviousBindingOnReturn',
            'restoresPreviousBindingOnFailure','sameLookupConcurrentContextsSerialized']:
            self.assertIs(True,r[key],key)
        for key in ['actualTaskConfigurationDecoded','nativeAliasTaskSequenceImplemented','liveBattleContextAttached',
            'actualParameterValuesResolved','liveCasterPropertiesRead','originalTaskBranchesExecutedInBattle',
            'abilityExecutionVerified','clientGameplayVerified','allResonanceAbilitiesImplemented','extendedCellsImplemented',
            'jsonInstalled','clientExecutableStarted','clientModified']:
            self.assertIs(False,r[key],key)

    def test_client_and_nonbuild_outputs_rejected_before_native_read(self):
        for output in [CLIENT/'alias-evidence.json',ROOT/'docs/alias-evidence.json']:
            with self.subTest(output=output),self.assertRaises(ValueError):
                binding.analyze(SimpleNamespace(client_dir=CLIENT,output=output))


if __name__ == '__main__': unittest.main()
