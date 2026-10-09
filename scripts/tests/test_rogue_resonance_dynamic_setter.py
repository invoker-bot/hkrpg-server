"""无吸附配置的动态setter与lookup写入/通知来源回归。"""
import hashlib
import importlib.util
from pathlib import Path
from types import SimpleNamespace
import unittest
import capstone

ROOT = Path(__file__).resolve().parents[2]
CLIENT = Path(r'C:\Users\InvokerBot\AppData\Local\hoyo\hkrpg\versions\current')
spec = importlib.util.spec_from_file_location('resonance_dynamic_setter',ROOT/'scripts/read-rogue-resonance-dynamic-setter.py')
setter = importlib.util.module_from_spec(spec)
spec.loader.exec_module(setter)


class ResonanceDynamicSetterTest(unittest.TestCase):
    def instructions(self):
        c = capstone.Cs(capstone.CS_ARCH_X86,capstone.CS_MODE_64)
        result = {}
        for m in setter.load_profile()['nativeMethods']:
            raw = bytes.fromhex(m['bytesHex'])
            self.assertEqual(m['sha256'],hashlib.sha256(raw).hexdigest())
            self.assertEqual('PE Exception Directory',m['bounds'])
            self.assertEqual(len(raw),int(m['endRva'],16)-int(m['rva'],16))
            result.update({i.address-0x180000000:i for i in c.disasm(raw,0x180000000+int(m['rva'],16))})
        return result

    def check(self,rows):
        ins = self.instructions()
        for rva,expected in rows: self.assertEqual(expected,(ins[rva].mnemonic,ins[rva].op_str),hex(rva))

    def target(self,rva): return hex(0x180000000+rva)

    def test_complete_setter_adsorption_and_lookup_methods_match_installed_client(self):
        self.assertEqual((3,2239),setter.verify_native(CLIENT))
        self.assertEqual([74798,74692,74764],[m['index'] for m in setter.load_profile()['nativeMethods']])

    def test_adsorption_config_fields_are_known_but_non_null_values_are_not_enabled(self):
        p = setter.load_profile()
        self.assertEqual({'AdsorptionThresh':16,'FractionalDigit':24},
            {f['name']:f['instanceOffset'] for f in p['adsorptionTypeFields']['fields']})
        self.assertEqual('null only',p['acceptedAdsorptionConfig'])
        self.assertFalse(p['nonNullAdsorptionImplemented'])

    def test_cell_setter_calls_adsorption_helper_before_constructing_fixed_value(self):
        self.check([(0x1cd81549,('mov','qword ptr [rsp + 0x20], rdx')),
            (0x1cd8154e,('mov','rdx, qword ptr [rcx + 0x48]')),
            (0x1cd81552,('lea','rcx, [rsp + 0x20]')),
            (0x1cd81557,('call',self.target(0x1d1103d0))),
            (0x1cd8155c,('mov','rdi, qword ptr [rsp + 0x20]'))])

    def test_null_adsorption_returns_without_reading_or_writing_input_word(self):
        self.check([(0x1d1103d9,('test','rdx, rdx')),(0x1d1103dc,('je',self.target(0x1d110688))),
            (0x1d110688,('add','rsp, 0x28')),(0x1d110691,('ret',''))])
        i = self.instructions()
        tail = [x for a,x in sorted(i.items()) if 0x1d110688<=a<=0x1d110691]
        self.assertTrue(all(x.mnemonic in ['add','pop','ret'] for x in tail))

    def test_fixed_word_is_stored_into_new_dynamic_float_and_overwrites_postfix_holder(self):
        self.check([(0x1cd815a0,('call',self.target(0x3d3a130))),
            (0x1cd815a5,('mov','qword ptr [rax + 0x10], rdi')),
            (0x1cd815a9,('mov','qword ptr [rsi + 0x40], rax')),
            (0x1cd815d6,('mov','rax, qword ptr [rax + 0x21608]')),
            (0x1cd815dd,('jmp',self.target(0x1cd815a9)))])

    def test_lookup_reads_previous_then_minimum_then_maximum_and_clamps_before_setter(self):
        self.check([(0x1cd7eacd,('add','edx, 3')),(0x1cd7eb05,('call','rbp')),
            (0x1cd7eb5d,('add','edx, 5')),(0x1cd7eb95,('call','rbp')),
            (0x1cd7ebed,('add','edx, 7')),(0x1cd7ec25,('call','rsi')),
            (0x1cd7ec30,('call',self.target(0x1dcd1a00))),
            (0x1cd7ec8d,('add','edx, 4')),(0x1cd7ecc9,('call','rbp'))])

    def test_absent_observer_skips_post_setter_getter_and_notification(self):
        self.check([(0x1cd7eccb,('mov','rsi, qword ptr [rbx + 0x10]')),
            (0x1cd7eccf,('test','rsi, rsi')),(0x1cd7ecd2,('je',self.target(0x1cd7ed9f)))])

    def test_present_observer_receives_previous_value_and_second_getter_value(self):
        self.check([(0x1cd7ed2d,('add','edx, 3')),(0x1cd7ed65,('call','rbx')),
            (0x1cd7ed6f,('mov','qword ptr [rsp + 0x30], r14')),
            (0x1cd7ed97,('mov','edx, r15d')),(0x1cd7ed9a,('mov','r9, rax')),
            (0x1cd7ed9d,('call','rbp'))])
        self.assertEqual(['previous getter','minimum','maximum','clamp','setter','observer-present new getter','notification'],
            setter.load_profile()['lookupWriteOrder'])

    def test_static_output_keeps_non_null_configs_aliases_properties_and_real_battle_closed(self):
        r = setter.analyze(SimpleNamespace(client_dir=CLIENT,output=ROOT/'build/dynamic-setter-source-test.json'))
        for k in ['nullAdsorptionDynamicSetterImplemented','dynamicLookupSetterImplemented',
            'setterReplacesPostfixWithFixedValue','observerUsesSecondGetter','cellTemplatesCopiedPerLookup']:
            self.assertTrue(r[k],k)
        for k in ['nonNullAdsorptionImplemented','rangeSettersImplemented','extendedCellsImplemented',
            'propertyCellImplemented','aliasEvaluatorBindingImplemented','actualParameterValuesResolved',
            'liveCasterPropertiesRead','liveBattleContextAttached','originalTaskBranchesExecutedInBattle',
            'abilityExecutionVerified','clientGameplayVerified','allResonanceAbilitiesImplemented',
            'jsonInstalled','clientExecutableStarted','clientModified']:
            self.assertFalse(r[k],k)

    def test_client_output_is_rejected_before_any_source_inspection(self):
        with self.assertRaises(ValueError):
            setter.analyze(SimpleNamespace(client_dir=CLIENT,output=CLIENT/'setter-evidence.json'))


if __name__ == '__main__': unittest.main()
