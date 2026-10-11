"""Build-only guards use the actual Inspector and complete fixed source; no server starts."""
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
OWN = ROOT / 'scripts/fixtures'
EVIDENCE = ROOT / 'build/daily-origin-native-guard'
spec = importlib.util.spec_from_file_location('origin_native_smoke', ROOT / 'scripts/smoke-daily-message-origin.py')
smoke = importlib.util.module_from_spec(spec)
spec.loader.exec_module(smoke)
JAR = ROOT / 'build/initial-query-cyrene-daily-origin-clock-runtime.jar'
SHA = '258b6cdb5567db32e2a7a81a7907ba8e5fb71f3aa9bbfb47cd24bceeab508ff1'
JAVA = next((ROOT / '.tools').glob('jdk-21*/bin/java.exe'))
CLASSES = None
_NATIVE_DIRECTORY = None
FLAGS = subprocess.CREATE_NO_WINDOW if os.name == 'nt' else 0


def setUpModule():
    global CLASSES, _NATIVE_DIRECTORY
    assert smoke.sha(JAR) == SHA
    EVIDENCE.mkdir(parents=True, exist_ok=True)
    _NATIVE_DIRECTORY = tempfile.TemporaryDirectory(prefix='daily-origin-guard-classes-', dir=ROOT / 'build')
    CLASSES = Path(_NATIVE_DIRECTORY.name) / 'classes'
    CLASSES.mkdir()
    paths = ['emu/lunarcore/command/commands/DailyTravelClockFixtureCommand.java',
             'emu/lunarcore/command/commands/NpcFirstTalkFixtureCommand.java',
             'org/hkrpg/server/gameplay/G61FirstTalkFixtureInspector.java',
             'org/hkrpg/server/protocol/NativeDailyMessageOriginProbe.java',
             'org/hkrpg/server/gameplay/DailyMessageOriginFixtureInspector.java']
    result = subprocess.run([str(JAVA.with_name('javac.exe')), '-encoding', 'UTF-8', '-cp', str(JAR),
        '-d', str(CLASSES), *[str(OWN / 'java' / path) for path in paths]], capture_output=True,
        text=True, encoding='utf-8', timeout=60, creationflags=FLAGS)
    (EVIDENCE / 'tracked-javac.log').write_text(result.stdout + result.stderr, 'utf-8')
    assert result.returncode == 0, result.stdout + result.stderr


def tearDownModule():
    if _NATIVE_DIRECTORY is not None:
        _NATIVE_DIRECTORY.cleanup()


def original_prefix(replies):
    fixture = json.loads((ROOT / 'src/test/resources/fixtures/daily-message-3000601-4.6.json').read_text('utf-8'))
    rows = {record['row']['ID']: record['row'] for record in fixture['records']
            if record['kind'] == 'MessageItemConfig' and record['row']['SectionID'] == 1211400}
    assert len(rows) == 11
    remaining, cursor, history, offered = list(replies), 121140101, [], []
    while cursor:
        row = rows[cursor]
        assert row['ItemType'] == 'Text'
        if row['Sender'] == 'Player':
            assert row.get('ContactsID', 0) == 0
            assert remaining and remaining.pop(0) == cursor
        else:
            assert row['Sender'] == 'NPC' and row['ContactsID'] == 48
        history.append(cursor)
        following = row['NextItemIDList']
        if following and all(rows[value]['Sender'] == 'Player' for value in following):
            offered = following
            if not remaining:
                break
            assert remaining[0] in offered
            cursor, offered = remaining[0], []
        else:
            assert len(following) <= 1
            cursor = following[0] if following else 0
    assert not remaining
    return dict(history=history, offered=offered)


class OriginInspectorGuard(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        assert smoke.sha(JAR) == SHA and CLASSES.is_dir()
        cls.temp = tempfile.TemporaryDirectory(prefix='origin-native-controls-', dir=ROOT / 'build')
        cls.directory = Path(cls.temp.name)

    @classmethod
    def tearDownClass(cls):
        cls.temp.cleanup()

    @staticmethod
    def control(**overrides):
        result = dict(uid=10001, firstDay=20737, runDay=20737, scenario='settled', expiredAt='noReplies',
                      firstChoice=121140102, oldFirstChoice=121140102, phoneSize=2,
                      completed=False, introOnly=False, phase='open')
        result.update(overrides)
        return result

    def verify(self, values):
        path = self.directory / 'controls.json'
        path.write_text(json.dumps(values), 'utf-8')
        return subprocess.run([str(JAVA), '-Dfile.encoding=UTF-8', '-Dstdout.encoding=UTF-8', '-Dstderr.encoding=UTF-8',
            '-cp', str(JAR) + os.pathsep + str(CLASSES), 'org.hkrpg.server.gameplay.DailyMessageOriginFixtureInspector',
            '--verify-controls', str(path)], cwd=ROOT, capture_output=True, text=True, encoding='utf-8', timeout=30, creationflags=FLAGS)

    def reject(self, **changes):
        result = self.verify([self.control(**changes)])
        self.assertNotEqual(0, result.returncode, 'Impossible controls must fail before opening a stopped database')

    def test_three_original_full_paths_match_the_actual_inspector(self):
        values, expected = [], []
        for reply in (121140102, 121140104, 121140106):
            for size, phase in ((2, 'open'), (7, 'middle')):
                values.append(self.control(firstChoice=reply, phoneSize=size, phase=phase))
                expected.append(original_prefix([] if size == 2 else [reply]))
        result = self.verify(values)
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertEqual(expected, json.loads(result.stdout))

    def test_intro_initial_and_completed_controls_preserve_three_real_stages(self):
        values = [self.control(phase='intro', introOnly=True, phoneSize=0),
                  self.control(phase='initial', phoneSize=0),
                  self.control(phase='finish', completed=True, phoneSize=7),
                  self.control(phase='claimed', completed=True, phoneSize=7, runDay=20738)]
        result = self.verify(values)
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertEqual([dict(history=[], offered=[])] * 2 + [original_prefix([121140102])] * 2,
                         json.loads(result.stdout))

    def test_two_original_unpaid_prefixes_are_distinct(self):
        for at, size in (('noReplies', 2), ('allReplies', 7)):
            with self.subTest(at=at):
                result = self.verify([self.control(scenario='expired-unpaid', expiredAt=at, phoneSize=size),
                                      self.control(scenario='expired-unpaid', expiredAt=at, phoneSize=size,
                                                   phase='boundary-reject')])
                self.assertEqual(0, result.returncode, result.stdout + result.stderr)
                self.assertEqual([original_prefix([] if size == 2 else [121140102])] * 2, json.loads(result.stdout))

    def test_sibling_or_impossible_initial_reply_is_rejected(self):
        for reply in (121140202, 121140302, 121140103, 0):
            with self.subTest(reply=reply):
                self.reject(firstChoice=reply)

    def test_nonterminal_stages_cannot_claim_completed(self):
        for phase, size in (('initial', 0), ('open', 2), ('middle', 7)):
            with self.subTest(phase=phase):
                self.reject(phase=phase, phoneSize=size, completed=True)

    def test_finish_and_claimed_require_terminal_history_and_completion(self):
        for phase in ('finish', 'claimed'):
            self.reject(phase=phase, phoneSize=7)
            self.reject(phase=phase, phoneSize=2, completed=True)

    def test_initial_and_intro_cannot_invent_phone_opening(self):
        self.reject(phase='initial', phoneSize=2)
        self.reject(phase='intro', phoneSize=2, introOnly=True)
        self.reject(phase='intro', phoneSize=0, introOnly=True, runDay=20738)

    def test_middle_requires_one_real_original_reply(self):
        self.reject(phase='middle', phoneSize=2)

    def test_settled_open_cannot_be_mislabelled_with_terminal_history(self):
        self.reject(phoneSize=7)

    def test_unpaid_open_must_match_its_actual_first_day_prefix(self):
        self.reject(scenario='expired-unpaid', expiredAt='noReplies', phoneSize=7)
        self.reject(scenario='expired-unpaid', expiredAt='allReplies', phoneSize=2)
        self.reject(scenario='expired-unpaid', expiredAt='allReplies', phoneSize=7, runDay=20738)

    def test_boundary_cannot_be_a_settled_or_changed_old_prefix(self):
        self.reject(phase='boundary-reject')
        self.reject(phase='boundary-reject', scenario='expired-unpaid', expiredAt='allReplies', phoneSize=2)
        self.reject(phase='boundary-reject', scenario='expired-unpaid', expiredAt='noReplies', phoneSize=7)

    def test_impossible_uid_clock_phase_and_scenario_are_rejected(self):
        for changes in (dict(uid=0), dict(firstDay=0), dict(runDay=20736), dict(runDay=20739),
                        dict(phase='fixed-finished'), dict(scenario='fixed-finished'), dict(expiredAt='bothReplies')):
            with self.subTest(changes=changes):
                self.reject(**changes)


class OriginLifecycleAndSourceGuard(unittest.TestCase):
    def test_select_single_lane_keeps_real_indices(self):
        self.assertEqual([dict(caseIndex=0, branch='102', expiredAt='noReplies')], smoke.select_cases('settled', [0]))
        self.assertEqual([0, 1, 2], [case['caseIndex'] for case in smoke.select_cases('settled')])
        self.assertEqual([0, 1], [case['caseIndex'] for case in smoke.select_cases('expired-unpaid')])

    def test_invalid_repeated_or_ambiguous_indices_are_rejected(self):
        for scenario, indices in (('settled', [3]), ('expired-unpaid', [2]), ('settled', [-1]),
                                  ('settled', [0, 0]), ('settled', [True]), ('settled', [])):
            with self.assertRaises(AssertionError):
                smoke.select_cases(scenario, indices)
        with self.assertRaises(AssertionError):
            smoke.select_cases('settled', [0], branches=['102'])

    def test_both_days_restore_initial_talk_before_opening_the_original_phone(self):
        phases = smoke.schedule('settled')
        self.assertEqual(8, len(phases))
        for day in (1, 2):
            actual = [phase for process in phases for current, phase in process if current == day]
            self.assertEqual((['intro'] if day == 1 else []) + ['initial', 'open', 'middle', 'finish', 'claimed'], actual)
        self.assertEqual(11, sum(map(len, phases)))
        self.assertEqual(121140102, smoke.choices_for('102', 1, 'open'))
        self.assertEqual(121140104, smoke.choices_for('102', 2, 'open'))
        self.assertEqual(121140102, smoke.choices_for('102', 2, 'boundary-reject'))

    def test_report_paths_are_observed_in_h2_not_taken_from_the_plan(self):
        for at, old in (('noReplies', original_prefix([])), ('allReplies', original_prefix([121140102]))):
            case = dict(caseIndex=0, branch='102', scenario='expired-unpaid', expiredAt=at,
                        readOnlyPhoneH2Inspections=[dict(expiredRun=old, currentRun=original_prefix([121140104]))])
            actual = smoke.observed_case_paths(case)
            self.assertEqual(old['history'], actual['actualFirstDayHistory'])
            self.assertEqual([] if at == 'noReplies' else [121140102], actual['actualFirstDayReplies'])
            self.assertEqual([121140104], actual['actualSecondDayReplies'])

    def test_output_reservation_preserves_the_first_actual_failure(self):
        with tempfile.TemporaryDirectory(dir=ROOT / 'build') as name:
            path = Path(name) / 'first.json'
            lease = smoke.reserve_output(JAR, path)
            self.assertTrue(lease.is_file())
            self.assertFalse(path.exists())
            with self.assertRaises(FileExistsError):
                smoke.reserve_output(JAR, path)
            failed = Path(name) / 'second.json'
            failed.with_name(failed.name + '.failed.json').write_text('first actual failure', 'utf-8')
            with self.assertRaises(FileExistsError):
                smoke.reserve_output(JAR, failed)

    def test_all_51_fixed_original_files_and_103_complete_selected_records_match_installation(self):
        fixture = json.loads((ROOT / 'src/test/resources/fixtures/g61-npc-first-talk-4.6.json').read_text('utf-8'))
        blobs, hashes, proof = smoke.source_preflight((ROOT / 'resources/4.6').resolve(), fixture)
        self.assertEqual(51, len(proof['files']))
        self.assertEqual(103, proof['completeSelectedObjects'])
        self.assertTrue(proof['passed'])
        self.assertFalse(proof['clientActExecuted'])
        self.assertIn('Config/Level/Mission/3000600/Act/Act300060001.json', blobs)
        path = EVIDENCE / 'preflight-original-source.json'
        path.write_text(json.dumps(proof, ensure_ascii=False, indent=2), 'utf-8')
        identity = smoke._g61.original_source_identity(blobs)
        result = subprocess.run([str(JAVA), '-Dfile.encoding=UTF-8', '-cp', str(JAR) + os.pathsep + str(CLASSES),
            'org.hkrpg.server.gameplay.G61FirstTalkFixtureInspector', '--source-identity', str(ROOT / 'resources/4.6')],
            cwd=ROOT, capture_output=True, text=True, encoding='utf-8', timeout=30, creationflags=FLAGS)
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertEqual(identity, json.loads(result.stdout))
        self.assertEqual(SHA, smoke.sha(JAR))

    def test_wire_shape_is_the_actual_frozen_4_6_command_and_field_numbers(self):
        result = subprocess.run([str(JAVA), '-Dfile.encoding=UTF-8', '-cp', str(JAR) + os.pathsep + str(CLASSES),
            'org.hkrpg.server.protocol.NativeDailyMessageOriginProbe', '--wire-fields'], cwd=ROOT,
            capture_output=True, text=True, encoding='utf-8', timeout=30, creationflags=FLAGS)
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        proof = json.loads(result.stdout)
        self.assertEqual(35, len(proof))
        self.assertFalse(proof['MissionTimestampDeclared'])
        self.assertEqual(1205, proof['FinishTalkMissionCsReq']['command'])
        self.assertEqual([6], proof['FinishTalkMissionCsReq']['fields']['sub_mission_id']['tags'])
        self.assertEqual([2], proof['FinishTalkMissionCsReq']['fields']['talk_str']['tags'])
        self.assertIn('scene/entity_group_list/entity_list/prop/prop_id', proof['GetCurSceneInfoScRsp']['fields'])
        (EVIDENCE / 'preflight-wire-fields.json').write_text(json.dumps(proof, indent=2), 'utf-8')


if __name__ == '__main__':
    unittest.main()
