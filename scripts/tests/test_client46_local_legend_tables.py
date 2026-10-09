import importlib.util
import json
import tempfile
import unittest
from pathlib import Path
from types import SimpleNamespace

ROOT = Path(__file__).resolve().parents[2]
CLIENT = Path(r'C:\Users\InvokerBot\AppData\Local\hoyo\hkrpg\versions\current')
spec = importlib.util.spec_from_file_location('legend_tables', ROOT / 'scripts/inspect-client-local-legend-tables.py')
legend = importlib.util.module_from_spec(spec)
spec.loader.exec_module(legend)


def uvar(value):
    out = bytearray()
    while value >= 128:
        out.append((value & 127) | 128)
        value >>= 7
    out.append(value)
    return bytes(out)


def sint(value):
    return uvar(value * 2 if value >= 0 else -value * 2 - 1)


def text(value):
    data = value.encode('utf-8')
    return uvar(len(data)) + data


def one(mask, body):
    return b'\x00' + sint(1) + uvar(mask) + body


class LocalLegendTableReaderTests(unittest.TestCase):
    def test_stage_native_bit_order_retains_all_ten_fields(self):
        raw = one(1023, uvar(4) + sint(3) + uvar(426011) + uvar(6072108)
                  + sint(3) + b''.join(uvar(i) for i in [5001820, 5001832, 5001833])
                  + sint(-7) + uvar(0xffffffffffffffff) + uvar(2042104)
                  + sint(2) + uvar(3238007) + uvar(3238008) + text('Prefab') + text('Config'))
        self.assertEqual([{'GroupID': 4, 'DifficultyLevel': 'Fearless', 'EventID': 426011,
            'UnlockQuest': 6072108, 'BattleTargetList': [5001820, 5001832, 5001833],
            'FearlessStageMechanism': {'Hash': 0xffffffffffffffff}, 'BattleAreaID': 2042104,
            'TrialAvatarList': [3238007, 3238008], 'ManikinPrefabPath': 'Prefab',
            'ManikinConfigPath': 'Config'}], legend.decode_table('LocalLegendStageConfig', raw))

    def test_group_native_bit_order_keeps_every_text_hash(self):
        tid = lambda h: sint(0) + uvar(h)
        raw = one(511, uvar(3) + tid(1001) + uvar(5007002) + text('Image') + uvar(77)
                  + tid(1002) + tid(1003) + tid(1004) + tid(1005))
        self.assertEqual([{'GroupID': 3, 'GroupTitle': {'Hash': 1001}, 'ActivityModuleID': 5007002,
            'GroupPicPath': 'Image', 'TutorialGuideID': 77, 'StageMechanismTitle': {'Hash': 1002},
            'StageMechanism': {'Hash': 1003}, 'ChallengeStrategy': {'Hash': 1004},
            'TeamBuildTip': {'Hash': 1005}}], legend.decode_table('ActivityLocalLegendGroup', raw))

    def test_reward_sort_is_signed_and_task_values_are_explicit(self):
        for value, name in [(1, 'EasyHard'), (2, 'Fearless')]:
            self.assertEqual([{'ID': 6072147, 'TaskType': name, 'Sort': -2}],
                legend.decode_table('ActivityLocalLegendReward', one(7, uvar(6072147) + sint(value) + sint(-2))))

    def test_target_native_field_order_preserves_conditions_icons_and_flags(self):
        raw = one(65535, uvar(5001802) + sint(1) + text('BattleTarget_LocalLegend_S1_2')
                  + sint(4) + uvar(6) + sint(2) + uvar(3) + uvar(6) + uvar(1) + b'\x01'
                  + sint(-4) + uvar(1234) + sint(0) + uvar(5678)
                  + sint(1) + uvar(5001801) + sint(2) + sint(0) + sint(4)
                  + b'\x00' + sint(3) + uvar(2) + b'\x01')
        self.assertEqual([{'ID': 5001802, 'Type': 'ChallengeTarget',
            'AbilityName': 'BattleTarget_LocalLegend_S1_2', 'ParamType': 'GreaterEqual',
            'TargetParam': 6, 'HintStep': [3, 6], 'IsShowProgress': 1, 'IsFixableHeight': True,
            'TargetName': {'Hash': 1234}, 'TargetNameSimple': {'Hash': 5678},
            'MultiTarget': [5001801], 'MultiTargetIconType': ['Star', 'TierceSpecialStar'],
            'ShowInScoreCounter': False, 'IconType': 'PeakDifficultyBossStar', 'IconNum': 2,
            'SkipWhenSuccessOnEnterBattle': True}], legend.decode_table('BattleTargetConfig', raw))

    def test_target_bool_icon_and_condition_are_not_coerced(self):
        for mask, body in [(1 | (1 << 7), uvar(1) + b'\x02'),
                           (1 | (1 << 3), uvar(1) + sint(8)),
                           (1 | (1 << 13), uvar(1) + sint(5)),
                           (1 | (1 << 11), uvar(1) + sint(1) + sint(-1))]:
            with self.subTest(mask=mask), self.assertRaises(ValueError):
                legend.decode_table('BattleTargetConfig', one(mask, body))

    def test_target_record_uses_the_verified_game_core_path(self):
        self.assertEqual('ExcelOutputGameCore/BattleTargetConfig.json', legend.source_path('BattleTargetConfig'))
        self.assertEqual('ExcelOutput/LocalLegendStageConfig.json', legend.source_path('LocalLegendStageConfig'))

    def test_minimal_records_do_not_invent_other_fields(self):
        self.assertEqual([{'GroupID': 1, 'DifficultyLevel': 'Easy'}],
            legend.decode_table('LocalLegendStageConfig', one(3, uvar(1) + sint(1))))
        self.assertEqual([{'GroupID': 1}], legend.decode_table('ActivityLocalLegendGroup', one(1, uvar(1))))
        self.assertEqual([{'ID': 1}], legend.decode_table('ActivityLocalLegendReward', one(1, uvar(1))))
        for name in legend.TABLES:
            self.assertEqual([], legend.decode_table(name, b'\x00\x00'))

    def test_null_and_empty_arrays_stay_distinct(self):
        for count, expected in [(-1, None), (0, [])]:
            self.assertEqual(expected, legend.decode_table('LocalLegendStageConfig',
                one(3 | 16, uvar(1) + sint(1) + sint(count)))[0]['BattleTargetList'])

    def test_same_group_different_difficulty_is_unique_but_same_pair_is_rejected(self):
        for name, body in [('LocalLegendStageConfig', uvar(3) + uvar(1) + sint(1)),
                           ('ActivityLocalLegendGroup', uvar(1) + uvar(1)),
                           ('ActivityLocalLegendReward', uvar(1) + uvar(1))]:
            with self.subTest(name=name), self.assertRaises(ValueError):
                legend.decode_table(name, b'\x00' + sint(2) + body + body)
        raw = b'\x00' + sint(2) + uvar(3) + uvar(1) + sint(1) + uvar(3) + uvar(1) + sint(2)
        self.assertEqual(2, len(legend.decode_table('LocalLegendStageConfig', raw)))

    def test_enum_invalid_values_never_become_a_guessed_difficulty(self):
        for value in [-1, 0, 4, 2147483647]:
            with self.subTest(value=value), self.assertRaises(ValueError):
                legend.decode_table('LocalLegendStageConfig', one(3, uvar(1) + sint(value)))
        with self.assertRaises(ValueError):
            legend.decode_table('ActivityLocalLegendReward', one(3, uvar(1) + sint(3)))

    def test_record_count_size_identity_prefix_and_masks_are_bounded(self):
        for raw in [b'', b'\x01\x00', b'\x00' + sint(-1), b'\x00' + sint(65),
                    one(0, b''), one(1, uvar(0)), one(1, uvar(1 << 32)),
                    one(512, b''), bytes(legend.MAX_RECORD + 1)]:
            with self.subTest(raw=raw[:20]), self.assertRaises(ValueError):
                legend.decode_table('ActivityLocalLegendGroup', raw)
        with self.assertRaises(ValueError):
            legend.decode_table('unverified', b'\x00\x00')

    def test_array_counts_stop_at_the_local_bound(self):
        for count in [-2, 65, 2147483648]:
            with self.subTest(count=count), self.assertRaises(ValueError):
                legend.decode_table('LocalLegendStageConfig', one(19, uvar(1) + sint(1) + sint(count)))

    def test_strings_noncanonical_varints_and_trailing_bytes_are_rejected(self):
        for raw in [one(9, uvar(1) + uvar(1) + b'\xff'), one(9, uvar(1) + uvar(65537)),
                    one(1, b'\x81\x00'), one(1, uvar(1)) + b'\x00', b'\x00\x80']:
            with self.subTest(raw=raw[:20]), self.assertRaises(ValueError):
                legend.decode_table('ActivityLocalLegendGroup', raw)

    def test_output_inside_client_is_refused_before_read_or_write(self):
        with tempfile.TemporaryDirectory() as directory:
            client = Path(directory)
            args = SimpleNamespace(client_dir=client, output=client / 'new.json')
            with self.assertRaises(ValueError):
                legend.inspect(args)
            self.assertFalse(args.output.exists())


@unittest.skipUnless((CLIENT / 'GameAssembly.dll').is_file(), '没有固定本机客户端，跳过原始传奇挑战表核对')
class LocalLegendClientTests(unittest.TestCase):
    def test_all_thirty_original_rows_full_consumption_and_protected_report(self):
        with tempfile.TemporaryDirectory() as directory:
            args = SimpleNamespace(client_dir=CLIENT, output=Path(directory) / 'report.json')
            result = legend.inspect(args)
            self.assertEqual([15, 5, 10, 143], [t['rowCount'] for t in result['tables']])
            self.assertTrue(all(t['tableFullyConsumed'] for t in result['tables']))
            stage = result['tables'][0]['rows']
            self.assertEqual({(g, d) for g in range(1, 6) for d in ['Easy', 'Hard', 'Fearless']},
                {(r['GroupID'], r['DifficultyLevel']) for r in stage})
            self.assertEqual([5001805, 5001826, 5001827], stage[2]['BattleTargetList'])
            self.assertEqual([], stage[2]['TrialAvatarList'])
            self.assertEqual(6072102, stage[2]['UnlockQuest'])
            self.assertEqual({1: 5007003, 2: 5007001, 3: 5007002, 4: 5007004, 5: 5007005},
                {r['GroupID']: r['ActivityModuleID'] for r in result['tables'][1]['rows']})
            ids = {i for r in stage for i in r['BattleTargetList']}
            targets = {r['ID']: r for r in result['tables'][3]['rows'] if r['ID'] in ids}
            self.assertEqual(35, len(targets))
            self.assertTrue(all(r['ParamType'] == 'GreaterEqual' for r in targets.values()))
            self.assertEqual(6, targets[5001802]['TargetParam'])
            self.assertEqual(18000, targets[5001830]['TargetParam'])
            for key in ['clientExecutableStarted', 'clientModified', 'clientAccepted',
                        'jsonInstalled', 'scriptsExecuted', 'completeGameplay']:
                self.assertFalse(result[key])
            stamp = args.output.stat().st_mtime_ns
            self.assertEqual(result, legend.inspect(args))
            self.assertEqual(stamp, args.output.stat().st_mtime_ns)
            args.output.write_bytes(b'operator report')
            with self.assertRaises(FileExistsError):
                legend.inspect(args)
            self.assertEqual(b'operator report', args.output.read_bytes())


if __name__ == '__main__':
    unittest.main()
