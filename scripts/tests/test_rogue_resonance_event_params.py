"""事件参数转交的完整原件、源上下文求值顺序和七条实际配置。"""
import hashlib
import importlib.util
from pathlib import Path
from types import SimpleNamespace
import unittest

import capstone

ROOT = Path(__file__).resolve().parents[2]
CLIENT = Path(r'C:\Users\InvokerBot\AppData\Local\hoyo\hkrpg\versions\current')
spec = importlib.util.spec_from_file_location('resonance_eventparams', ROOT/'scripts/read-rogue-resonance-event-params.py')
params = importlib.util.module_from_spec(spec)
spec.loader.exec_module(params)


class ResonanceEventParamsTest(unittest.TestCase):
    def instructions(self):
        decoder = capstone.Cs(capstone.CS_ARCH_X86, capstone.CS_MODE_64)
        result = {}
        for m in params.load_profile()['nativeMethods']:
            raw = bytes.fromhex(m['bytesHex'])
            self.assertEqual(m['sha256'], hashlib.sha256(raw).hexdigest())
            self.assertEqual(int(m['endRva'], 16)-int(m['rva'], 16), len(raw))
            result.update({i.address-0x180000000:(i.mnemonic, i.op_str)
                for i in decoder.disasm(raw, 0x180000000+int(m['rva'], 16))})
        return result

    def test_complete_factory_and_executor_match_native_method_fields_and_type_slots(self):
        self.assertEqual((2, 1567), params.verify_native(CLIENT))
        p = params.load_profile()
        self.assertEqual([516225, 536334], [m['index'] for m in p['nativeMethods']])
        fields = {f['name']:f['instanceOffset'] for f in p['configTypeFields']['fields']}
        self.assertEqual((24, 48, 56, 60), tuple(fields[n] for n in ['EventID', 'DynamicValues', 'Team', 'TotalDamageTeam']))
        self.assertEqual((58043, 'NAPIKMAGDDI'), (p['runtimeTypeSlot']['typeIndex'], p['runtimeTypeSlot']['typeName']))

    def test_original_registration_uses_create_event_config_and_factory_delegate(self):
        p = params.load_profile()
        registry = next(m for m in params.variables.load_profile()['nativeMethods'] if m['index']==515972)
        decoder = capstone.Cs(capstone.CS_ARCH_X86, capstone.CS_MODE_64)
        instructions = list(decoder.disasm(bytes.fromhex(registry['bytesHex']), 0x180000000+int(registry['rva'], 16)))
        matched = next(i for i in instructions if i.address-0x180000000==0x153ea1a6)
        self.assertEqual(('mov', 'rdi, qword ptr [rip - 0xc0635f5]'), (matched.mnemonic, matched.op_str))
        self.assertEqual(0x9386bb8, 0x153ea1a6+matched.size-0xc0635f5)
        self.assertEqual(516225, p['factoryMethodSlot']['method']['index'])
        self.assertEqual('0x94ab7d0', p['factoryMethodSlot']['slot'])

    def test_executor_reads_config_dictionary_and_evaluates_each_scalar_on_original_context(self):
        i = self.instructions()
        for address, expected in [
            (0xbab21ee, ('mov', 'rax, qword ptr [rcx + 0x30]')),
            (0xbab21f2, ('test', 'rax, rax')),
            (0xbab21f5, ('je', '0x18bab2405')),
            (0xbab225a, ('call', '0x19a7c7d10')),
            (0xbab225f, ('test', 'al, al')),
            (0xbab22e2, ('mov', 'rcx, qword ptr [r13 + 0x20]')),
            (0xbab22eb, ('mov', 'rdx, qword ptr [rbp + 0x48]')),
            (0xbab22ef, ('call', '0x18e777c90')),
            (0xbab2307, ('mov', 'r8, rax')),
            (0xbab2312, ('jmp', '0x18bab2250'))]:
            self.assertEqual(expected, i[address], hex(address))

    def test_name_hash_reads_original_utf16_first_character_without_removing_prefix(self):
        i = self.instructions()
        for address, expected in [
            (0xbab2274, ('movzx', 'ecx, word ptr [rax + 0x14]')),
            (0xbab227d, ('add', 'rax, 0x18')),
            (0xbab229a, ('xor', 'esi, ecx')),
            (0xbab229c, ('movzx', 'ecx, word ptr [rax - 2]')),
            (0xbab22fd, ('imul', 'edx, ebx, 0x5d588b65')),
            (0xbab2303, ('add', 'esi, edx')),
            (0xbab2305, ('mov', 'edx, esi'))]:
            self.assertEqual(expected, i[address], hex(address))

    def test_parameter_dictionary_is_filled_before_event_creation_and_released_afterwards(self):
        i = self.instructions()
        self.assertEqual(('call', '0x19aec11b0'), i[0xbab230d])
        self.assertEqual(('mov', 'r9b, 1'), i[0xbab230a])
        self.assertEqual(('call', '0x18e649380'), i[0xbab24ce])
        self.assertEqual(('call', '0x18b10ce60'), i[0xbab24e0])
        self.assertLess(0xbab230d, 0xbab24ce)

    def test_seven_original_classic_transfers_match_full_resource_record(self):
        sources = params.source_bindings(CLIENT)
        self.assertEqual([41001, 41006, 41002, 41003, 41004, 41005, 41007], [s['eventId'] for s in sources])
        self.assertEqual(127, sum(len(s['bindings']) for s in sources))
        s = sources[-1]
        self.assertEqual(('StageAbility_612620', 20340, 20494, 1122),
            tuple(s[k] for k in ['abilityName', 'abilityOffset', 'taskOffset', 'taskId']))
        b = next(pair for pair in s['bindings'] if pair[0]=='ADF_5')
        self.assertEqual(('010011', [-1862642832]), (b[1]['rawPostfixBytes'], b[1]['variableKeys']))
        self.assertEqual(18, len(s['bindings']))

    def test_parameter_projection_does_not_claim_actual_queries_event_creation_or_gameplay(self):
        r = params.analyze(SimpleNamespace(client_dir=CLIENT, output=ROOT/'build/rogue-resonance-event-params-test-evidence.json'))
        for key in ['explicitEventParameterTransferImplemented', 'originalClassicTransferConfigsMatched',
                    'originalPrefixedParameterReadMatched', 'evaluationUsesSourceContext']:
            self.assertTrue(r[key], key)
        for key in ['implicitPrefixRemovalImplemented', 'actualParameterValuesResolved', 'battleEventCreated',
                    'callbackExecutionImplemented', 'liveBattleContextAttached', 'liveCasterPropertiesRead',
                    'originalTaskBranchesExecutedInBattle', 'abilityExecutionVerified', 'clientGameplayVerified',
                    'allResonanceAbilitiesImplemented', 'extendedCellsImplemented', 'jsonInstalled',
                    'clientExecutableStarted', 'clientModified']:
            self.assertFalse(r[key], key)

    def test_output_guard_rejects_client_and_nonbuild_targets_before_any_read(self):
        for target in [CLIENT/'event-params.json', ROOT/'event-params.json']:
            with self.assertRaises(ValueError):
                params.analyze(SimpleNamespace(client_dir=CLIENT, output=target))


if __name__ == '__main__':
    unittest.main()
