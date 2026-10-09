"""原生虚方法槽、角色分支、实体身份比较及固定投影来源的回归。"""
from copy import deepcopy
import hashlib
import importlib.util
from pathlib import Path
from types import SimpleNamespace
import unittest

import capstone

ROOT = Path(__file__).resolve().parents[2]
CLIENT = Path(r'C:\Users\InvokerBot\AppData\Local\hoyo\hkrpg\versions\current')
spec = importlib.util.spec_from_file_location('resonance_scoped', ROOT / 'scripts/read-rogue-resonance-scoped-lookup.py')
scoped = importlib.util.module_from_spec(spec)
spec.loader.exec_module(scoped)


class ResonanceScopedLookupTest(unittest.TestCase):
    def instructions(self):
        decoder = capstone.Cs(capstone.CS_ARCH_X86, capstone.CS_MODE_64)
        decoder.detail = True
        profile = scoped.load_profile()
        result = {}
        for method in profile['nativeMethods'] + profile['nativeRuntimeFunctions'] + profile['nativeFragments']:
            raw = bytes.fromhex(method['bytesHex'])
            self.assertEqual(method['sha256'], hashlib.sha256(raw).hexdigest())
            if 'endRva' in method:
                self.assertEqual('PE Exception Directory', method['bounds'])
                self.assertEqual(len(raw), int(method['endRva'], 16) - int(method['rva'], 16))
            else: self.assertFalse(method['completeNativeMethod'])
            result.update({i.address - 0x180000000: i for i in decoder.disasm(raw, 0x180000000 + int(method['rva'], 16))})
        return result

    def test_managed_methods_cpp_setup_and_getter_spans_match_fixed_client(self):
        self.assertEqual((5, 10204), scoped.verify_native(CLIENT))
        profile = scoped.load_profile()
        self.assertEqual((1, 1333, 116), (len(profile['nativeRuntimeFunctions']),
            profile['nativeRuntimeFunctionBytes'], profile['fragmentBytes']))
        self.assertEqual([508324, 70469, 508325, 74755, 509828], [m['index'] for m in profile['nativeMethods']])

    def test_slot_decoding_matches_owner_getters_lookup_reader_and_tostring(self):
        slots = scoped.read_native_slots(CLIENT)
        self.assertEqual([(509791, 10, '0x110'), (509799, 12, '0x120'), (74755, 7, '0xf8'), (509808, 3, None)],
            [(r['methodIndex'], r['slot'], r['dispatchClassOffset']) for r in slots])
        ins = self.instructions()
        for address, expected in [(0x3d24139, ('movd', 'xmm1, dword ptr [r12 + 0xc]')),
                (0x3d24145, ('pshuflw', 'xmm2, xmm2, 0')), (0x3d2414a, ('pshuflw', 'xmm1, xmm1, 0xe1')),
                (0x3d24153, ('pxor', 'xmm1, xmm0')), (0x3d24157, ('movd', 'dword ptr [rbx + 0x2a], xmm1'))]:
            self.assertEqual(expected, (ins[address].mnemonic, ins[address].op_str))
        i = ins[0x3d23f8a]
        operand = next(o for o in i.operands if o.type == capstone.x86.X86_OP_MEM)
        self.assertEqual(0x55ddc30, i.address + i.size + operand.mem.disp - 0x180000000)
        self.assertEqual(0x9f7, scoped.load_profile()['slotXorConstant']['xorWord'])

    def test_dispatch_class_offsets_and_getter_fields_agree_with_decoded_slots(self):
        ins = self.instructions()
        for address, expected in [(0xe4c3be1, ('mov', 'rbp, qword ptr [rcx + 0x110]')),
                (0xe4c39e1, ('mov', 'rdi, qword ptr [rcx + 0x120]')),
                (0xe4c4d03, ('mov', 'rdi, qword ptr [rcx + 0xf8]')),
                (0xe776f20, ('mov', 'rax, qword ptr [rcx + 0x90]')),
                (0xe777300, ('mov', 'rax, qword ptr [rcx + 0x28]')),
                (0x1cd7dd47, ('mov', 'r9d, 3')), (0x1cd7dd9a, ('call', 'rsi'))]:
            self.assertEqual(expected, (ins[address].mnemonic, ins[address].op_str), hex(address))

    def test_default_ability_presence_controls_primary_owner_and_caster_branches(self):
        ins = self.instructions()
        for address, expected in [(0xe4c39ff, ('je', '0x18e4c3a98')),
                (0xe4c3a0c, ('je', '0x18e4c3aeb')), (0xe4c3a15, ('jne', '0x18e4c3c77')),
                (0xe4c3a42, ('mov', 'rax, qword ptr [rax + 0x60]')),
                (0xe4c3a66, ('js', '0x18e4c3ae3')), (0xe4c3a9a, ('jne', '0x18e4c4221')),
                (0xe4c3aa3, ('call', '0x18e7783d0')), (0xe4c3b0e, ('mov', 'rax, qword ptr [rax + 0x68]'))]:
            self.assertEqual(expected, (ins[address].mnemonic, ins[address].op_str), hex(address))

    def test_summoner_owner_reference_identity_and_global_gate_follow_native_control_flow(self):
        ins = self.instructions()
        for address, expected in [(0xe4c3b71, ('call', '0x18b10c6a0')),
                (0xe4c3bd3, ('js', '0x18e4c3bde')), (0xe4c3bfc, ('cmp', 'rax, rdi')),
                (0xe4c3bff, ('je', '0x18e4c3c77')), (0xe4c3c77, ('test', 'esi, esi')),
                (0xe4c3c79, ('jne', '0x18e4c420c')), (0xe4c3cb9, ('mov', 'rdi, qword ptr [rax + 0x410]'))]:
            self.assertEqual(expected, (ins[address].mnemonic, ins[address].op_str), hex(address))
        self.assertEqual([0, 1, 2, 3, 4, 5, 8, 9], scoped.load_profile()['supportedScopes'])

    def test_unchecked_aitag_container_and_target_scope_remain_unopened(self):
        ins = self.instructions()
        self.assertEqual(('mov', 'rdi, qword ptr [rax + 0x18]'), (ins[0xe4c3978].mnemonic, ins[0xe4c3978].op_str))
        self.assertEqual(('jne', '0x18e4c3d25'), (ins[0xe4c397f].mnemonic, ins[0xe4c397f].op_str))
        self.assertEqual([6, 7], scoped.load_profile()['unopenedScopes'])

    def fixture_record(self):
        profile = scoped.load_profile()
        ability = {'Name': profile['sourceAbilityName'], 'DynamicValues': deepcopy(profile['sourceDynamicValues']),
            'OnStart': [{'SuccessTaskList': [{}, {}, {}]}, {'SuccessTaskList': [{}, {}, {}, {}]}]}
        for row in scoped.context.defaults.write.load_profile()['sourceTasks']:
            ability['OnStart'][row['path'][3]]['SuccessTaskList'][row['path'][5]] = deepcopy(row['task'])
        return {**profile['sourceRecord'], 'decoded': {'AbilityList': [{}, {}, ability]}}

    def test_original_declarations_and_writes_match_but_changed_source_is_closed(self):
        record = self.fixture_record()
        inputs = scoped.context.verify_source_inputs(record)
        self.assertEqual((4, 6), (len(inputs['fixedDeclarations']), len(inputs['writeTasks'])))
        for kind in ['identity', 'declaration', 'task']:
            changed = deepcopy(record)
            if kind == 'identity': changed['entryKey'] = '0' * 16
            elif kind == 'declaration': changed['decoded']['AbilityList'][2]['DynamicValues']['floats'][0]['fixedSignedRaw'] = 1
            else: changed['decoded']['AbilityList'][2]['OnStart'][0]['SuccessTaskList'][0]['Value'] = 13
            with self.subTest(kind=kind), self.assertRaises(ValueError): scoped.context.verify_source_inputs(changed)

    def test_live_static_report_does_not_claim_live_projections_generic_types_provider_or_gameplay(self):
        report = scoped.analyze(SimpleNamespace(client_dir=CLIENT, output=ROOT / 'build/rogue-resonance-scoped-lookup-test-evidence.json'))
        self.assertTrue(report['passed'] and report['fixedProjectedContextTailImplemented'])
        self.assertEqual((5, 10204, 1, 1333, 116), tuple(report[k] for k in ['matchedNativeMethods', 'matchedNativeBytes',
            'matchedNativeRuntimeFunctions', 'matchedNativeRuntimeFunctionBytes', 'getterAndProviderInstructionBytes']))
        for key in ['gettersCompleteNativeMethods', 'explicitSummonerScopeAllowsGlobalAliases', 'liveSourceProjectionsResolved',
                    'genericEntityComponentTypeArgumentsResolved', 'aitagMissingValueRepresentationResolved',
                    'contextLookupSelectionFullyImplemented', 'actualParameterValuesResolved', 'nativeNumericProviderImplemented',
                    'dynamicCellProvidersImplemented', 'liveCasterPropertiesRead', 'liveBattleContextAttached',
                    'originalTaskBranchesExecutedInBattle', 'abilityExecutionVerified', 'jsonInstalled',
                    'clientExecutableStarted', 'clientModified', 'clientGameplayVerified', 'allResonanceAbilitiesImplemented']:
            self.assertIs(False, report[key], key)

    def test_invalid_output_locations_are_rejected_before_native_reads(self):
        for output in [ROOT / 'scoped-evidence.json', CLIENT / 'scoped-evidence.json']:
            with self.subTest(output=output), self.assertRaises(ValueError):
                scoped.analyze(SimpleNamespace(client_dir=CLIENT, output=output))


if __name__ == '__main__':
    unittest.main()
