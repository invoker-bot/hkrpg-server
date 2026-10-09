"""原始祝福参数表的完整字节、定点读取器、行身份和解析拒绝边界。"""
import hashlib
import importlib.util
from pathlib import Path
from types import SimpleNamespace
import unittest

import capstone

ROOT = Path(__file__).resolve().parents[2]
CLIENT = Path(r'C:\Users\InvokerBot\AppData\Local\hoyo\hkrpg\versions\current')
spec = importlib.util.spec_from_file_location('maze_buff_tables', ROOT/'scripts/read-rogue-maze-buff-table.py')
tables = importlib.util.module_from_spec(spec)
spec.loader.exec_module(tables)


def uvar(value):
    result = bytearray()
    while value > 127:
        result.append((value & 127) | 128)
        value >>= 7
    result.append(value)
    return bytes(result)


def sint(value):
    return uvar((value << 1) ^ (value >> 63))


class MazeBuffTableTest(unittest.TestCase):
    def instructions(self):
        p = tables.load_profile()
        decoder = capstone.Cs(capstone.CS_ARCH_X86, capstone.CS_MODE_64)
        result = {}
        for method in p['nativeMethods']:
            raw = bytes.fromhex(method['bytesHex'])
            self.assertEqual(method['sha256'], hashlib.sha256(raw).hexdigest())
            self.assertEqual(int(method['endRva'], 16)-int(method['rva'], 16), len(raw))
            result.update({i.address-0x180000000:(i.mnemonic, i.op_str)
                for i in decoder.disasm(raw, 0x180000000+int(method['rva'], 16))})
        return result

    def test_complete_methods_fields_and_param_reader_delegate_match_current_client(self):
        self.assertEqual((4, 4547), tables.verify_native(CLIENT))
        p = tables.load_profile()
        self.assertEqual([94596, 94597, 74462, 74409], [m['index'] for m in p['nativeMethods']])
        fields = {f['name']:f for f in p['rowTypeFields']['fields']}
        self.assertEqual((76, 124, 24), tuple(fields[n]['instanceOffset'] for n in ['ID', 'Lv', 'ParamList']))
        self.assertEqual('FixPoint', fields['ParamList']['nativeType']['elementType']['name'])
        self.assertEqual((74462, '0x9452148'), (p['fixedReaderMethodSlot']['method']['index'], p['fixedReaderMethodSlot']['slot']))

    def test_native_mask_serialization_places_id_level_and_parameters_at_original_fields(self):
        i = self.instructions()
        for address, expected in [
            (0x1d8334a0, ('test', 'bl, 1')),
            (0x1d8334ca, ('mov', 'dword ptr [rdi + 0x4c], eax')),
            (0x1d83352a, ('test', 'bl, 8')),
            (0x1d833554, ('mov', 'dword ptr [rdi + 0x7c], eax')),
            (0x1d83361e, ('test', 'ebx, 0x100')),
            (0x1d833636, ('lea', 'r15, [rdi + 0x18]')),
            (0x1d833657, ('xor', 'r9d, r9d')),
            (0x1d83365a, ('call', '0x196f11c20'))]:
            self.assertEqual(expected, i[address], hex(address))

    def test_fixed_word_reader_keeps_zigzag_signed_raw_without_float_conversion(self):
        i = self.instructions()
        for address, expected in [
            (0x1d1fbc2f, ('call', '0x19d1aed60')),
            (0x1d1fbc37, ('shr', 'rcx, 1')),
            (0x1d1fbc3a, ('and', 'eax, 1')),
            (0x1d1fbc3d, ('neg', 'rax')),
            (0x1d1fbc40, ('xor', 'rax, rcx')),
            (0x1d1fbc43, ('mov', 'qword ptr [rsi], rax'))]:
            self.assertEqual(expected, i[address], hex(address))

    def test_all_original_records_are_consumed_with_exact_row_and_raw_word_counts(self):
        p = tables.load_profile()
        rows, words = [], 0
        for record in p['records']:
            raw = bytes.fromhex(record['rawHex'])
            self.assertEqual(record['record']['sha256'], hashlib.sha256(raw).hexdigest())
            decoded = tables.Reader(raw, p).table()
            self.assertEqual(record['rows'], len(decoded))
            self.assertEqual(len(raw), decoded[-1]['endOffset'])
            rows.extend(decoded)
            words += sum(len(r.get('ParamList') or []) for r in decoded)
        self.assertEqual((4019, 8215), (len(rows), words))

    def test_real_41007_base_and_talent_parameters_keep_source_words(self):
        raw = bytes.fromhex(tables.load_profile()['records'][0]['rawHex'])
        rows = {(r['ID'], r.get('Lv', 0)):r for r in tables.Reader(raw).table()}
        base = rows[612620, 1]
        self.assertEqual('StageAbility_612620', base['InBattleBindingKey'])
        self.assertEqual([2147483650, 25769803776, 42949672960, 687194768,
                          1717986920, 3435973840, 429496730], base['ParamList'])
        self.assertNotEqual((1 << 33)//5, base['ParamList'][4])
        self.assertEqual([8589934592], rows[612625, 1]['ParamList'])
        self.assertEqual([4294967300, 8589934592, 429496730, 1288490190], rows[612626, 1]['ParamList'])
        self.assertEqual([171798691840, 858993460], rows[612627, 1]['ParamList'])

    def test_fresh_all_three_records_match_client_index_chunks_and_native_data(self):
        r = tables.analyze(SimpleNamespace(client_dir=CLIENT, output=ROOT/'build/rogue-maze-buff-table-test-evidence.json'))
        self.assertEqual((693701, 4019, 8215), tuple(r[k] for k in ['sourceTableBytes', 'sourceRows', 'sourceRawParameters']))
        self.assertEqual([1852, 2019, 148], [t['rows'] for t in r['sourceTables']])
        self.assertTrue(all(t['fullyConsumed'] for t in r['sourceTables']))

    def test_reader_preserves_omitted_zero_level_null_empty_and_signed_limits(self):
        self.assertNotIn('Lv', tables.Reader(bytes.fromhex('020101')).table()[0])
        self.assertIsNone(tables.Reader(bytes.fromhex('028902010101')).table()[0]['ParamList'])
        self.assertEqual([], tables.Reader(bytes.fromhex('028902010100')).table()[0]['ParamList'])
        values = [-(1 << 63), -2, 0, 2, (1 << 63)-2]
        raw = bytes.fromhex('0289020101')+sint(len(values))+b''.join(sint(v) for v in values)
        self.assertEqual(values, tables.Reader(raw).table()[0]['ParamList'])

    def test_duplicate_key_fails_and_distinct_levels_stay_distinct(self):
        with self.assertRaises(ValueError):
            tables.Reader(bytes.fromhex('040101010101')).table()
        self.assertEqual(2, len(tables.Reader(bytes.fromhex('04090101090102')).table()))

    def test_project_parameter_bound_and_unmatched_extended_words_are_rejected(self):
        prefix = bytes.fromhex('0289020101')
        self.assertEqual(255, len(tables.Reader(prefix+sint(255)+b'\x04'*255).table()[0]['ParamList']))
        for raw in [prefix+sint(256), prefix+sint(-2), prefix+sint(1)+sint(1)]:
            with self.assertRaises(ValueError):
                tables.Reader(raw).table()

    def test_malformed_shape_utf8_varint_and_remaining_bytes_are_rejected(self):
        for hex_value in ['01', 'c2b518', '020001', '020100', '020201', '02010100', '02810101',
            '0201ffffffffffffffffffff02', '0201ffffffff1f', '02890201010404',
            '02410112', '028180040110', '028180100108', '028180400106',
            '02818080010102', '02210101ff']:
            with self.subTest(raw=hex_value), self.assertRaises((ValueError, UnicodeDecodeError)):
                tables.Reader(bytes.fromhex(hex_value)).table()

    def test_scope_does_not_claim_native_query_fallback_blessing_effects_or_client_gameplay(self):
        p = tables.load_profile()
        self.assertTrue(p['rawSourceParametersMatched'])
        self.assertTrue(p['exactStandardNativeTableReaderImplemented'])
        for key in ['runtimeResourcesInstalled', 'nativeQueryTaskExecuted', 'queryLevelFallbackImplemented',
            'queryIndexConventionMatched', 'heldBlessingsApplied', 'liveBattleContextAttached',
            'abilityExecutionVerified', 'clientGameplayVerified', 'completeResonanceRuntimeImplemented']:
            self.assertFalse(p[key], key)

    def test_output_guard_rejects_client_and_nonbuild_targets_before_reading(self):
        for target in [CLIENT/'maze-buff.json', ROOT/'maze-buff.json']:
            with self.assertRaises(ValueError):
                tables.analyze(SimpleNamespace(client_dir=CLIENT, output=target))


if __name__ == '__main__':
    unittest.main()
