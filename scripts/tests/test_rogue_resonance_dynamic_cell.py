"""普通动态单元的字段、创建、读取和上下限来源回归。"""
import hashlib
import importlib.util
from pathlib import Path
from types import SimpleNamespace
import unittest
import capstone

ROOT = Path(__file__).resolve().parents[2]
CLIENT = Path(r'C:\Users\InvokerBot\AppData\Local\hoyo\hkrpg\versions\current')
spec = importlib.util.spec_from_file_location('resonance_dynamic_cell',ROOT/'scripts/read-rogue-resonance-dynamic-cell.py')
dynamic = importlib.util.module_from_spec(spec)
spec.loader.exec_module(dynamic)


class ResonanceDynamicCellTest(unittest.TestCase):
    def instructions(self):
        c = capstone.Cs(capstone.CS_ARCH_X86,capstone.CS_MODE_64)
        c.detail = True
        result = {}
        p = dynamic.load_profile()
        for m in p['nativeMethods']+p['nativeFragments']:
            raw = bytes.fromhex(m['bytesHex'])
            self.assertEqual(m['sha256'],hashlib.sha256(raw).hexdigest())
            if 'endRva' in m:
                self.assertEqual('PE Exception Directory',m['bounds'])
                self.assertEqual(len(raw),int(m['endRva'],16)-int(m['rva'],16))
            else: self.assertFalse(m['completeNativeMethod'])
            result.update({i.address-0x180000000:i for i in c.disasm(raw,0x180000000+int(m['rva'],16))})
        return result

    def check(self,rows):
        ins = self.instructions()
        for rva,expected in rows: self.assertEqual(expected,(ins[rva].mnemonic,ins[rva].op_str),hex(rva))

    def target(self,rva): return hex(0x180000000+rva)

    def rip(self,rva):
        i = self.instructions()[rva]
        o = next(o for o in i.operands if o.type==capstone.x86.X86_OP_MEM and o.mem.base==capstone.x86.X86_REG_RIP)
        return i.address+i.size+o.mem.disp-0x180000000

    def test_complete_native_methods_match_installed_client(self):
        self.assertEqual((10,7650),dynamic.verify_native(CLIENT))
        self.assertEqual([74760,74796,74797,74799,74801,74754,74755,74756,70469,68417],
            [m['index'] for m in dynamic.load_profile()['nativeMethods']])

    def test_dynamic_float_and_cell_fields_match_metadata(self):
        p = dynamic.load_profile()
        fields = {t['name']:{f['name']:f['instanceOffset'] for f in t['fields']} for t in p['typeFieldFacts']}
        self.assertEqual({'fixedValue':16,'postfixExpr':24},fields['DynamicFloat'])
        self.assertEqual(64,fields['FJNIFLCNGIK']['MGIHBPBIHFC'])
        self.assertEqual(56,fields['FJNIFLCNGIK']['<Provider>k__BackingField'])
        self.assertEqual(32,fields['ENEIHJJPNPI']['<AliasEvaluator>k__BackingField'])

    def test_factory_creates_fjn_cell_with_provider_pointing_to_owning_lookup(self):
        p = dynamic.load_profile()
        self.assertEqual('FJNIFLCNGIK',p['factoryTypeSlot']['typeName'])
        self.assertEqual(0x9361558,self.rip(0x1cd7e77d))
        self.check([(0x1cd7e77a,('mov','rbx, rcx')),
            (0x1cd7e799,('mov','qword ptr [rsi + 0x40], rax')),
            (0x1cd7e7a1,('mov','qword ptr [rsi + 0x18], rax')),
            (0x1cd7e7a9,('mov','qword ptr [rsi + 0x10], rax')),
            (0x1cd7e7ea,('mov','qword ptr [rsi + 0x38], rbx'))])

    def test_value_read_null_fixed_or_postfix_selection_matches_source(self):
        self.check([(0x1cd8122d,('mov','rax, qword ptr [rcx + 0x40]')),
            (0x1cd81234,('je',self.target(0x1cd812c0))),
            (0x1cd8123a,('cmp','qword ptr [rax + 0x18], 0')),
            (0x1cd8123f,('je',self.target(0x1cd812c9))),
            (0x1cd812c9,('mov','rdi, qword ptr [rax + 0x10]'))])

    def test_value_then_minimum_then_maximum_are_all_evaluated_before_clamp(self):
        self.check([(0x1cd812b3,('mov','r8, rdi')),(0x1cd812b6,('call',self.target(0x1d23e480))),
            (0x1cd812d0,('call',self.target(0x1cd81340))),
            (0x1cd812db,('call',self.target(0x1cd81440))),
            (0x1cd812e0,('mov','rcx, rdi')),(0x1cd812e3,('mov','rdx, rbx')),
            (0x1cd812e6,('mov','r8, rax')),(0x1cd812e9,('call',self.target(0x1dcd1a00)))])
        self.assertEqual(['value','minimum','maximum'],dynamic.load_profile()['getValInputOrder'])

    def test_minimum_uses_offset_18_maximum_uses_offset_10(self):
        self.check([(0x1cd81348,('mov','rax, qword ptr [rcx + 0x18]')),
            (0x1cd81448,('mov','rax, qword ptr [rcx + 0x10]')),
            (0x1cd81364,('mov','rsi, qword ptr [rcx + 0x38]')),
            (0x1cd81464,('mov','rsi, qword ptr [rcx + 0x38]')),
            (0x1cd813c9,('call',self.target(0x1d23e480))),
            (0x1cd814c9,('call',self.target(0x1d23e480)))])

    def test_null_bounds_load_known_extended_initializers_and_value_loads_zero(self):
        self.assertEqual(0x9343cb8,self.rip(0x1cd813d8))
        self.assertEqual(0x9343ca0,self.rip(0x1cd814d8))
        self.assertEqual(0x9343d30,self.rip(0x1cd812c0))
        p = dynamic.load_profile()
        self.assertEqual(p['nullMinimumRaw'],p['minimumInitializer']['raw'])
        self.assertEqual(p['nullMaximumRaw'],p['maximumInitializer']['raw'])
        self.assertEqual(0,p['zeroInitializerPair']['constantRaw'])

    def test_generic_scalar_read_has_same_null_fixed_and_provider_vm_paths(self):
        self.check([(0x1cd81128,('mov','rax, qword ptr [rdx]')),
            (0x1cd81134,('cmp','qword ptr [rax + 0x18], 0')),
            (0x1cd81143,('mov','rsi, qword ptr [rcx + 0x38]')),
            (0x1cd811a5,('mov','r8, rsi')),(0x1cd811a8,('call',self.target(0x1d23e480))),
            (0x1cd811c7,('mov','rax, qword ptr [rax + 0x10]'))])
        self.assertEqual(0x9343d30,self.rip(0x1cd811b7))

    def test_lookup_read_retains_native_zero_for_missing_local_key_and_virtual_cell_value(self):
        self.check([(0x1cd7dcd5,('js',self.target(0x1cd7dd59))),
            (0x1cd7dd66,('add','edx, 3')),(0x1cd7dd9a,('call','rsi')),
            (0x1cd7de28,('mov','rbx, qword ptr [rcx + 0xf8]')),
            (0x1cd7de45,('call','rbx'))])
        self.assertEqual(0x9343d30,self.rip(0x1cd7dd59))

    def test_is_dynamic_fragment_is_only_nine_bytes_and_checks_postfix_pointer(self):
        p = dynamic.load_profile()
        self.assertEqual(9,p['fragmentBytes'])
        self.assertEqual(9,p['nativeFragments'][0]['length'])
        self.check([(0x1d5f9e80,('cmp','qword ptr [rcx + 0x18], 0')),
            (0x1d5f9e85,('setne','al')),(0x1d5f9e88,('ret',''))])

    def test_static_report_keeps_setters_aliases_properties_and_live_objects_unverified(self):
        r = dynamic.analyze(SimpleNamespace(client_dir=CLIENT,output=ROOT/'build/dynamic-cell-source-test.json'))
        self.assertEqual((10,7650),(r['matchedNativeMethods'],r['matchedNativeBytes']))
        for k in ['dynamicCellReadProjectionImplemented','localLookupNumericReadsImplemented','aiTagProviderProjectionImplemented']:
            self.assertTrue(r[k],k)
        for k in ['nativeNumericProviderImplemented','aliasEvaluatorBindingImplemented','dynamicCellSetterImplemented',
            'adsorptionImplemented','propertyCellImplemented','actualParameterValuesResolved','liveCasterPropertiesRead',
            'liveBattleContextAttached','originalTaskBranchesExecutedInBattle','abilityExecutionVerified',
            'clientGameplayVerified','allResonanceAbilitiesImplemented','newExtendedInputsSupported',
            'jsonInstalled','clientExecutableStarted','clientModified']:
            self.assertFalse(r[k],k)

    def test_output_inside_client_is_rejected_before_inspection(self):
        with self.assertRaises(ValueError):
            dynamic.analyze(SimpleNamespace(client_dir=CLIENT,output=CLIENT/'dynamic-evidence.json'))


if __name__ == '__main__': unittest.main()
