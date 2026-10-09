"""组件MethodSpec类型、AITag继承/虚方法及最后一项读取的来源回归。"""
from copy import deepcopy
import hashlib
import importlib.util
from pathlib import Path
import struct
from types import SimpleNamespace
import unittest
from unittest.mock import patch

import capstone
import pefile

ROOT = Path(__file__).resolve().parents[2]
CLIENT = Path(r'C:\Users\InvokerBot\AppData\Local\hoyo\hkrpg\versions\current')
spec = importlib.util.spec_from_file_location('resonance_components', ROOT / 'scripts/read-rogue-resonance-components.py')
components = importlib.util.module_from_spec(spec)
spec.loader.exec_module(components)


class ResonanceComponentsTest(unittest.TestCase):
    def instructions(self):
        decoder = capstone.Cs(capstone.CS_ARCH_X86, capstone.CS_MODE_64)
        decoder.detail = True
        profile = components.load_profile()
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

    def check(self, rows):
        ins = self.instructions()
        for address, expected in rows:
            self.assertEqual(expected, (ins[address].mnemonic, ins[address].op_str), hex(address))

    def rip(self, instruction):
        operand = next(o for o in instruction.operands if o.type == capstone.x86.X86_OP_MEM)
        return instruction.address + instruction.size + operand.mem.disp - 0x180000000

    def test_eight_complete_managed_methods_and_three_cpp_functions_match_installed_client(self):
        self.assertEqual((8, 11362), components.verify_native(CLIENT))
        profile = components.load_profile()
        self.assertEqual((3, 3574, 225), (len(profile['nativeRuntimeFunctions']), profile['nativeRuntimeFunctionBytes'], profile['fragmentBytes']))
        self.assertEqual(524424, profile['nativeMethods'][-1]['index'])

    def test_native_methodspec_table_uses_header_word_and_three_twelve_byte_fields(self):
        self.check([(0x3d33290, ('mov', 'eax, dword ptr [rdx + 0x38]')),
                    (0x3d33293, ('mov', 'ecx, 0xf778f1ab')),
                    (0x3d3329f, ('lea', 'rax, [rdi + rdi*2]')),
                    (0x3d332a3, ('movsxd', 'rdx, dword ptr [r14 + rax*4 + 8]')),
                    (0x3d332c1, ('movsxd', 'r8, dword ptr [r14 + rax*4 + 4]')),
                    (0x3d335b8, ('mov', 'ecx, dword ptr [r14 + rax*4]')),
                    (0x3d335bc, ('call', hex(0x180000000 + 0x3d33710)))])

    def test_usage_kind_six_resolves_two_component_arguments_despite_other_table_destinations(self):
        rows = components.read_generic_slots(CLIENT)
        self.assertEqual([('0x944fdb8', 21531, 37866, 57562), ('0x945a398', 24359, 39085, 57764)],
            [(r['slot'], r['payload'], r['methodSpec']['argumentIndexAt4'], r['instRecords'][0]['argumentTypeIndex']) for r in rows])
        self.assertTrue(all(r['usageKind'] == 6 and r['methodSpec']['argumentIndexAt8'] == -1 for r in rows))
        self.assertTrue(all(r['methodSpec']['methodDefinition'] == 502972 for r in rows))
        definition = components.load_profile()['getComponentDefinition']
        self.assertEqual(('GetComponent', 'GameEntity', 53551), (definition['name'], definition['typeName'], definition['typeIndex']))

    def test_registration_installation_and_native_generic_array_are_actual_file_data(self):
        ins = self.instructions()
        self.assertEqual(0x4931658, self.rip(ins[0x3adc82e]))
        self.assertEqual(0x9aeab60, self.rip(ins[0x3adc835]))
        self.check([(0x3d332b9, ('shl', 'rdx, 4')), (0x3d332bd, ('add', 'rdx, qword ptr [rcx + 0x38]'))])
        pe = pefile.PE(str(CLIENT / 'GameAssembly.dll'), fast_load=True)
        pointer = struct.unpack('<Q', components.read_rva(pe, 0x4931690, 8))[0]
        self.assertEqual(0x4457ce0, pointer - pe.OPTIONAL_HEADER.ImageBase)

    def test_component_fields_base_lookup_inheritance_and_read_override_slot_agree(self):
        profile = components.load_profile()
        custom, ai, bag = profile['componentTypes']
        self.assertEqual((57562, 88, 'ENEIHJJPNPI'), (custom['typeIndex'], custom['field']['instanceOffset'], custom['field']['nativeType']['name']))
        self.assertEqual((57764, 24, 'BAGIHIEADIL'), (ai['typeIndex'], ai['field']['instanceOffset'], ai['field']['nativeType']['name']))
        self.assertEqual('ENEIHJJPNPI', bag['baseTypeChain'][0]['parentType']['name'])
        self.assertEqual((524424, 7, '0xf8'), tuple(profile['virtualMethodSlots'][-1][k] for k in ['index', 'decodedSlot', 'dispatchClassOffset']))

    def test_aitag_reads_last_list_value_before_tail_calling_fixed_lookup(self):
        self.check([(0x15bd2aae, ('mov', 'rsi, qword ptr [rbx + 0x28]')),
                    (0x15bd2ac7, ('js', hex(0x180000000 + 0x15bd2b1c))),
                    (0x15bd2aeb, ('mov', 'eax, dword ptr [rcx + 0x18]')),
                    (0x15bd2af0, ('jle', hex(0x180000000 + 0x15bd2b1c))),
                    (0x15bd2afb, ('dec', 'eax')), (0x15bd2b04, ('shl', 'rax, 4')),
                    (0x15bd2b08, ('mov', 'rax, qword ptr [rcx + rax + 0x28]')),
                    (0x15bd2b0d, ('mov', 'qword ptr [r14], rax')), (0x15bd2b10, ('mov', 'al, 1')),
                    (0x15bd2b2d, ('jmp', hex(0x180000000 + 0x1cd7dcb0)))])

    def test_original_four_declarations_and_six_tasks_remain_matched(self):
        profile = components.load_profile()
        ability = {'Name': profile['sourceAbilityName'], 'DynamicValues': deepcopy(profile['sourceDynamicValues']),
            'OnStart': [{'SuccessTaskList': [{}, {}, {}]}, {'SuccessTaskList': [{}, {}, {}, {}]}]}
        for row in components.entity.scoped.context.defaults.write.load_profile()['sourceTasks']:
            ability['OnStart'][row['path'][3]]['SuccessTaskList'][row['path'][5]] = deepcopy(row['task'])
        record = {**profile['sourceRecord'], 'decoded': {'AbilityList': [{}, {}, ability]}}
        inputs = components.entity.scoped.context.verify_source_inputs(record)
        self.assertEqual((4, 6), (len(inputs['fixedDeclarations']), len(inputs['writeTasks'])))
        changed = deepcopy(record)
        changed['entryKey'] = '0' * 16
        with self.assertRaises(ValueError): components.entity.scoped.context.verify_source_inputs(changed)

    def test_report_keeps_live_objects_shared_body_dynamic_provider_and_effects_unverified(self):
        report = components.analyze(SimpleNamespace(client_dir=CLIENT, output=ROOT / 'build/rogue-resonance-components-test-evidence.json'))
        for key in ['passed', 'fixedAiTagStackProjectionImplemented', 'aitagTopValueBeforeBaseFallback', 'genericEntityComponentTypeArgumentsResolved']:
            self.assertIs(True, report[key], key)
        self.assertEqual((8, 11362, 3, 3574, 225), tuple(report[k] for k in ['matchedNativeMethods', 'matchedNativeBytes',
            'matchedNativeRuntimeFunctions', 'matchedNativeRuntimeFunctionBytes', 'getterProviderAndRegistrationInstructionBytes']))
        for key in ['aitagWriteReplacesStackTop', 'genericSharedBodyIdentityResolved', 'sourceTagStacksRead', 'tagPushPopImplemented',
                    'gettersCompleteNativeMethods', 'liveSourceProjectionsResolved', 'contextLookupSelectionFullyImplemented',
                    'actualParameterValuesResolved', 'nativeNumericProviderImplemented', 'dynamicCellProvidersImplemented',
                    'liveCasterPropertiesRead', 'liveBattleContextAttached', 'originalTaskBranchesExecutedInBattle',
                    'abilityExecutionVerified', 'jsonInstalled', 'clientExecutableStarted', 'clientModified',
                    'clientGameplayVerified', 'allResonanceAbilitiesImplemented']:
            self.assertIs(False, report[key], key)

    def test_unmatched_generic_shapes_and_virtual_zero_fill_are_rejected(self):
        pe = pefile.PE(str(CLIENT / 'GameAssembly.dll'), fast_load=True)
        with self.assertRaises(ValueError): components.read_rva(pe, 0x9343d30, 8)
        with self.assertRaises(ValueError): components.read_inst(pe, -1, 0x4457ce0)
        with self.assertRaises(ValueError): components.read_inst(pe, 10000000, 0x4457ce0)
        for count, reserved, pointer in [(0, 0, 0), (2, 0, 0), (1, 1, 0), (1, 0, 1)]:
            with self.subTest(count=count, reserved=reserved, pointer=pointer), patch.object(components, 'read_rva',
                    return_value=struct.pack('<IIQ', count, reserved, pointer)), self.assertRaises(ValueError):
                components.read_inst(pe, 0, 0x4457ce0)

    def test_output_paths_are_rejected_before_any_native_read(self):
        for output in [ROOT / 'components-evidence.json', CLIENT / 'components-evidence.json']:
            with self.subTest(output=output), self.assertRaises(ValueError):
                components.analyze(SimpleNamespace(client_dir=CLIENT, output=output))


if __name__ == '__main__':
    unittest.main()
