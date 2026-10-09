"""固定 4.6 文件的完整方法字节和交叉字段链；不执行客户端。"""
import hashlib
import json
from pathlib import Path
import re
import unittest

import capstone

ROOT = Path(__file__).resolve().parents[2]
BASE = 0x180000000


class RogueResonanceWireTest(unittest.TestCase):
    def setUp(self):
        self.p = json.loads((ROOT / 'src/test/resources/fixtures/rogue-resonance-wire-4.6.json').read_text('utf-8'))
        self.methods = {r['index']: r for r in self.p['nativeMethods']}

    def instructions(self, index):
        m = self.methods[index]
        cs = capstone.Cs(capstone.CS_ARCH_X86, capstone.CS_MODE_64)
        return {i.address - BASE: (i.mnemonic, i.op_str)
                for i in cs.disasm(bytes.fromhex(m['bytesHex']), BASE + int(m['rva'], 16))}

    def test_thirteen_complete_pe_methods_keep_original_bytes_and_hashes(self):
        self.assertEqual({541658, 143760, 143726, 141692, 524816, 503648, 534902,
                          524864, 541662, 145945, 145947, 146025, 143735}, set(self.methods))
        self.assertEqual(19078, self.p['nativeBytes'])
        self.assertEqual(19078, sum(len(bytes.fromhex(m['bytesHex'])) for m in self.methods.values()))
        for m in self.methods.values():
            raw = bytes.fromhex(m['bytesHex'])
            self.assertEqual('PE Exception Directory', m['bounds'])
            self.assertEqual(int(m['endRva'], 16) - int(m['rva'], 16), len(raw))
            self.assertEqual(hashlib.sha256(raw).hexdigest(), m['sha256'])
        self.assertEqual(5, len(self.p['nativeMessageIdentities']))
        for digest in self.p['nativeFiles'].values():
            self.assertRegex(digest, '^[0-9a-f]{64}$')

    def test_statistics_producer_and_codec_use_the_same_event_list_at_wire_sixteen(self):
        producer = self.instructions(541662)
        self.assertEqual(('mov', 'rsi, qword ptr [rbx + 0x10]'), producer[0xe6b5ee2])
        self.assertEqual(('call', '0x18e6b5600'), producer[0xe6b5ef0])
        writer = self.instructions(146025)
        self.assertEqual(('mov', 'rbx, qword ptr [rdi + 0x10]'), writer[0x1e8b7eac])
        self.assertEqual(('mov', 'r8, qword ptr [rax + 0x2bcd0]'), writer[0x1e8b7ed4])
        codec = self.instructions(145947)
        self.assertEqual(('mov', 'rdx, qword ptr [rax + 0xcd98]'), codec[0x1e8baf17])
        self.assertEqual(('mov', 'ecx, 0x82'), codec[0x1e8baf25])
        self.assertEqual(('mov', 'qword ptr [rcx + 0x2bcd0], rax'), codec[0x1e8baf36])
        parser = self.instructions(143735)
        self.assertEqual(('mov', 'qword ptr [rax + 0xcd98], rdi'), parser[0x1e991c4f])
        self.assertEqual(16, self.p['statisticsRepeatedEventField'])

    def test_current_maximum_wire_values_feed_the_corresponding_native_property_formula(self):
        bar = self.instructions(141692)
        self.assertEqual(('mov', 'byte ptr [rcx + rax + 0x20], 8'), bar[0x1e861f18])
        self.assertEqual(('mov', 'edx, dword ptr [rdi + 0x18]'), bar[0x1e861f1d])
        self.assertEqual(('mov', 'byte ptr [rcx + rax + 0x20], 0x10'), bar[0x1e861f5c])
        self.assertEqual(('mov', 'edx, dword ptr [rdi + 0x1c]'), bar[0x1e861f61])
        create = self.instructions(534902)
        self.assertEqual(('mov', 'ecx, dword ptr [rcx + 0x18]'), create[0xe64a1e4])
        self.assertEqual(('mov', 'qword ptr [rdi + 0x48], rdx'), create[0xe64a202])
        self.assertEqual(('mov', 'ecx, dword ptr [rax + 0x1c]'), create[0xe64a213])
        self.assertEqual(('mov', 'qword ptr [rdi + 0x50], rax'), create[0xe64a243])
        reset_args = self.instructions(524864)
        self.assertEqual(('lea', 'rdx, [rax + 0x48]'), reset_args[0x1663cb3e])
        self.assertEqual(('lea', 'rbx, [rax + 0x50]'), reset_args[0x1663cb42])
        self.assertEqual(('call', '0x18e5b0bd0'), reset_args[0x1663cb65])
        reset = self.instructions(524816)
        self.assertEqual(('call', '0x19dccb160'), reset[0xe5b0cc7])
        self.assertEqual(('call', '0x19dcc94f0'), reset[0xe5b0cd2])
        self.assertEqual({68474: 'op_Multiply', 68481: 'op_Division', 510460: 'ModifyProperty'},
                         {r['index']: r['name'] for r in self.p['mathAndSetterIdentities']})
        properties = next(r for r in self.p['nativeMessageIdentities'] if r['name'] == 'AbilityProperty')
        enums = {f['name']: f['constant']['value'] for f in properties['fields'] if 'constant' in f}
        self.assertEqual(11, enums['MaxSP'])
        self.assertEqual(12, enums['CurrentSP'])

    def test_existing_protocol_uses_the_matched_fields_without_generated_changes(self):
        text = '\n'.join((ROOT / 'protocols/4.6' / name).read_text('utf-8')
                         for name in ['Common.proto', 'CmdBattleType.proto'])
        for name, field, number in [('BattleStatistics', 'BDCHEJKPOAF', 16),
                                    ('BattleEventBattleInfo', 'battle_event_id', 1),
                                    ('BattleEventBattleInfo', 'status', 2),
                                    ('BattleEventProperty', 'sp_bar', 2),
                                    ('SpBarInfo', 'cur_sp', 1), ('SpBarInfo', 'max_sp', 2)]:
            body = re.search(r'message ' + name + r' \{(.*?)\n\}', text, re.S).group(1)
            self.assertRegex(body, r'\b' + field + r' = ' + str(number) + r';')

    def test_static_fields_do_not_claim_official_energy_thresholds_effects_or_ui(self):
        for field in ['statisticsFieldSixteenProducerMatched', 'currentMaximumConsumerMatched']:
            self.assertIs(True, self.p[field])
        for field in ['officialInitialEnergyVerified', 'officialThresholdsVerified',
                      'allResonanceAbilitiesImplemented', 'clientGameplayVerified']:
            self.assertIs(False, self.p[field])


if __name__ == '__main__':
    unittest.main()
