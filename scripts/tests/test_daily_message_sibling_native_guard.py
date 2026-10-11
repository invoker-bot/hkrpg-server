"""Run actual new Inspector controls against the complete original Phone graph; no game server."""
import itertools
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
SOURCE = ROOT / 'scripts/fixtures/java/org/hkrpg/server/gameplay/DailyMessageSiblingFixtureInspector.java'
spec = importlib.util.spec_from_file_location('sibling_native_smoke', ROOT / 'scripts/smoke-daily-message-sibling.py')
smoke = importlib.util.module_from_spec(spec)
spec.loader.exec_module(smoke)


def original_prefix(choices):
    fixture = json.loads((ROOT / 'src/test/resources/fixtures/g61-npc-first-talk-4.6.json').read_text('utf-8'))
    rows = {r['row']['ID']: r['row'] for r in fixture['records']
            if r['kind'] == 'messageItem' and r['row']['SectionID'] == 1211402}
    assert len(rows) == 17
    cursor, history, offered, remaining = 121140301, [], [], list(choices)
    while cursor:
        row = rows[cursor]
        assert row['ItemType'] == 'Text'
        if row['Sender'] == 'Player':
            if not remaining:
                break
            assert remaining.pop(0) == cursor
        else:
            assert row['Sender'] == 'NPC' and row['ContactsID'] == 48
        history.append(cursor)
        following = row['NextItemIDList']
        if following and all(rows[value]['Sender'] == 'Player' for value in following):
            offered = following
            if not remaining:
                break
            assert remaining[0] in offered
            cursor = remaining[0]
            offered = []
        else:
            assert len(following) <= 1
            cursor = following[0] if following else 0
    assert not remaining
    return dict(history=history, offered=offered)


class SiblingNativeInspectorControlTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.java = next((ROOT / '.tools').glob('jdk-21*/bin/java.exe'))
        cls.jar = Path(os.environ.get('HKRPG_NATIVE_TEST_JAR', ROOT / 'build/gameplay-cast-g61-chess-ammo-fixed-runtime.jar')).resolve()
        assert cls.jar.is_file() and cls.jar.is_relative_to((ROOT / 'build').resolve())
        cls.temporary = tempfile.TemporaryDirectory(prefix='daily-message-sibling-inspector-guard-', dir=ROOT / 'build')
        cls.directory = Path(cls.temporary.name)
        cls.classes = cls.directory / 'classes'
        cls.classes.mkdir()
        cls.flags = subprocess.CREATE_NO_WINDOW if os.name == 'nt' else 0
        compiled = subprocess.run([str(cls.java.with_name('javac.exe')), '-encoding', 'UTF-8', '-cp', str(cls.jar),
                                  '-d', str(cls.classes), str(SOURCE)], capture_output=True, text=True, encoding='utf-8',
                                  creationflags=cls.flags)
        assert compiled.returncode == 0, compiled.stdout + compiled.stderr

    @classmethod
    def tearDownClass(cls):
        cls.temporary.cleanup()

    def verify(self, values):
        input_path = self.directory / 'controls.json'
        input_path.write_text(json.dumps(values), 'utf-8')
        return subprocess.run([str(self.java), '-Dfile.encoding=UTF-8', '-Dstdout.encoding=UTF-8',
                               '-Dstderr.encoding=UTF-8', '-cp', str(self.jar) + os.pathsep + str(self.classes),
                               'org.hkrpg.server.gameplay.DailyMessageSiblingFixtureInspector', '--verify-controls',
                               str(input_path)], cwd=ROOT, capture_output=True, text=True, encoding='utf-8',
                              timeout=30, creationflags=self.flags)

    @staticmethod
    def control(first=121140302, second=121140306, third=121140311, size=6, **overrides):
        result = dict(uid=10001, firstDay=20737, runDay=20737, scenario='expired-unpaid', expiredAt='oneReply',
                      firstChoice=first, secondChoice=second, thirdChoice=third, phoneSize=size,
                      completed=False, introOnly=False)
        result.update(overrides)
        return result

    def test_all_eight_original_paths_and_four_unpaid_prefixes_match_real_inspector(self):
        values, expected = [], []
        for choices in itertools.product((121140302, 121140303), (121140306, 121140308), (121140311, 121140312)):
            for count, at in enumerate(('noReplies', 'oneReply', 'twoReplies', 'allReplies')):
                prefix = original_prefix(choices[:count])
                values.append(self.control(*choices, size=len(prefix['history']), expiredAt=at))
                expected.append(prefix)
        result = self.verify(values)
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertEqual(expected, json.loads(result.stdout), 'Controls must describe the original full graph, not a copied two-choice model')

    def test_initial_meeting_and_actual_completed_terminal_controls_are_distinct(self):
        intro = self.control(size=0, introOnly=True)
        completed = self.control(size=13, completed=True, scenario='settled')
        result = self.verify([intro, completed])
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertEqual([dict(history=[], offered=[]), original_prefix((121140302, 121140306, 121140311))], json.loads(result.stdout))

    def test_foreign_old_section_and_impossible_prefix_controls_are_rejected(self):
        invalid = [self.control(first=121140202), self.control(second=121140205), self.control(third=121140999),
                   self.control(size=9, completed=True), self.control(size=0), self.control(size=13, introOnly=True),
                   self.control(runDay=20739), self.control(scenario='auto-completed'), self.control(expiredAt='bothReplies')]
        for value in invalid:
            with self.subTest(control=value):
                result = self.verify([value])
                self.assertNotEqual(0, result.returncode, 'Invalid controls must fail before any stopped database is opened')


class SiblingNativeLifecycleGuardTest(unittest.TestCase):
    def test_unpaid_report_reads_actual_stored_prefix_instead_of_counting_the_configured_three_replies(self):
        for count, at in enumerate(smoke.EXPIRY_POINTS):
            actual_old = original_prefix((121140302, 121140306, 121140311)[:count])
            actual_new = original_prefix((121140303, 121140308, 121140312))
            case = dict(caseIndex=count, branch='302-306-311', expiredAt=at, scenario='expired-unpaid',
                        configuredFirstDayChoices=[121140302, 121140306, 121140311],
                        readOnlyPhoneH2Inspections=[dict(expiredRun=actual_old, currentRun=actual_new)])
            report = smoke.observed_case_paths(case)
            self.assertEqual(actual_old['history'], report['actualFirstDayHistory'])
            self.assertEqual([121140302, 121140306, 121140311][:count], report['actualFirstDayReplies'])
            self.assertEqual([121140303, 121140308, 121140312], report['actualSecondDayReplies'])
            self.assertEqual(count, len(report['actualFirstDayReplies']))

    def test_selected_native_cases_keep_stable_source_indices_without_counting_unexecuted_paths(self):
        settled, expired = smoke.select_cases('settled'), smoke.select_cases('expired-unpaid')
        self.assertEqual(list(range(8)), [case['caseIndex'] for case in settled])
        self.assertEqual(list(range(4)), [case['caseIndex'] for case in expired])
        for index in range(8):
            selected = smoke.select_cases('settled', [index])
            self.assertEqual([settled[index]], selected)
            self.assertEqual(1, len(selected), 'A single native index must not run or count the default eight paths')
        for index, at in enumerate(('noReplies', 'oneReply', 'twoReplies', 'allReplies')):
            self.assertEqual([dict(caseIndex=index, branch='302-306-311', expiredAt=at)], smoke.select_cases('expired-unpaid', [index]))
        self.assertEqual([settled[7], settled[0]], smoke.select_cases('settled', [7, 0]))

    def test_invalid_repeated_or_ambiguous_indices_are_rejected_before_any_runtime_is_prepared(self):
        for scenario, indices in [('settled', [-1]), ('settled', [8]), ('expired-unpaid', [4]),
                                  ('settled', [0, 0]), ('settled', [True]), ('settled', [])]:
            with self.subTest(scenario=scenario, indices=indices), self.assertRaises(AssertionError):
                smoke.select_cases(scenario, indices)
        with self.assertRaisesRegex(AssertionError, 'combined'):
            smoke.select_cases('settled', [0], branches=['302-306-311'])
        with self.assertRaisesRegex(AssertionError, 'combined'):
            smoke.select_cases('expired-unpaid', [0], points=['noReplies'])
        self.assertEqual([dict(caseIndex=3, branch='302-306-311', expiredAt='allReplies')],
                         smoke.select_cases('expired-unpaid', points=['allReplies']))

    def test_stable_source_npc_observation_survives_field_order_but_rejects_an_event_replacement(self):
        first = dict(uid=10001, performance=90000103, runtime=5868350669391634234,
                     source='fixed-complete-original', event='44090630-33e7-4e54-bc8a-b8388083fca6')
        reordered = dict(reversed(list(first.items())))
        smoke.verify_observation_stability([dict(observation=first), dict(observation=reordered)])
        replaced = dict(first, event='1e289ba8-7f07-4c83-b8f8-1776f75ee332')
        with self.assertRaisesRegex(AssertionError, 'identity'):
            smoke.verify_observation_stability([dict(observation=first), dict(observation=replaced)])

    def test_settled_process_schedule_reloads_each_original_pending_offer_on_both_days(self):
        processes = smoke.schedule('settled')
        self.assertEqual(7, len(processes))
        for branch, choices in smoke.BRANCHES.items():
            for day in (1, 2):
                phases = [phase for group in processes for index, phase in group if index == day]
                self.assertEqual(['intro', 'open', 'middle', 'finish', 'claimed'] if day == 1 else ['open', 'middle', 'finish', 'claimed'], phases)
                selected = smoke.choices_for(branch, day, 'open')
                self.assertEqual(choices if day == 1 else tuple(next(value for value in pair if value != old)
                    for old, pair in zip(choices, ((121140302, 121140303), (121140306, 121140308), (121140311, 121140312)))), selected)
                for phase, count in (('open', 1), ('middle', 2), ('finish', 3), ('claimed', 3)):
                    prefix = original_prefix(selected[:count])
                    self.assertEqual(len(prefix['history']), smoke.snapshot_phone_size(dict(dayIndex=day, phase=phase), 'settled', 'oneReply'))
            with self.subTest(branch=branch):
                self.assertEqual(choices, smoke.choices_for(branch, 2, 'boundary-reject'), 'The boundary request must retain the expired day choices')

    def test_all_four_unpaid_original_prefixes_are_rejected_before_any_second_day_open(self):
        processes = smoke.schedule('expired-unpaid')
        self.assertEqual([[(1, 'intro')], [(1, 'open')], [(2, 'boundary-reject')]], processes[:3])
        self.assertEqual(6, len(processes))
        for count, at in enumerate(smoke.EXPIRY_POINTS):
            expected = len(original_prefix((121140302, 121140306, 121140311)[:count])['history'])
            self.assertEqual(expected, smoke.snapshot_phone_size(dict(dayIndex=1, phase='open'), 'expired-unpaid', at))
            self.assertEqual(expected, smoke.snapshot_phone_size(dict(dayIndex=2, phase='boundary-reject'), 'expired-unpaid', at))
            self.assertEqual(6, smoke.snapshot_phone_size(dict(dayIndex=2, phase='open'), 'expired-unpaid', at))

    def test_unused_output_reservation_cannot_become_a_passed_report_or_overwrite_previous_failure(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            jar, output = root / 'inert.jar', root / 'report.json'
            jar.write_bytes(b'not-an-executable-jar')
            lease = smoke.reserve_output(jar, output)
            self.assertTrue(lease.is_file())
            self.assertFalse(output.exists())
            before = lease.read_bytes()
            with self.assertRaises(FileExistsError):
                smoke.reserve_output(jar, output)
            self.assertEqual(before, lease.read_bytes())
            failure = root / 'failed.json'
            failure_sidecar = failure.with_name(failure.name + '.failed.json')
            failure_sidecar.write_bytes(b'actual first failure')
            with self.assertRaises(FileExistsError):
                smoke.reserve_output(jar, failure)
            self.assertEqual(b'actual first failure', failure_sidecar.read_bytes())


if __name__ == '__main__':
    unittest.main()
