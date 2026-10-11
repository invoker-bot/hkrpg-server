"""Independently recount the retained single-lane report, raw probe/H2 logs and normal stops."""
import argparse
import hashlib
import importlib.util
import json
from pathlib import Path
import uuid

OWN = Path(__file__).resolve().parent
ROOT = OWN.parent
spec = importlib.util.spec_from_file_location('origin_native_audit_smoke', OWN / 'smoke-daily-message-origin.py')
smoke = importlib.util.module_from_spec(spec)
spec.loader.exec_module(smoke)


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def last_json(path):
    return json.loads(next(line for line in reversed(path.read_text('utf-8').splitlines()) if line.startswith('{')))


def audit(report, jar):
    source = json.loads(report.read_text('utf-8'))
    assert source['passed'] and source['mainMission'] == 3000601 and source['section'] == 1211400
    assert source['executedCaseIndices'] == [0] and source['actualCompletedCaseCount'] == 1
    assert source['scenario'] == 'settled' and len(source['cases']) == 1
    case = source['cases'][0]
    directory = Path(case['runtimeDirectory']).resolve()
    assert directory.is_relative_to((ROOT / 'build/smoke').resolve()) and directory.name.startswith('daily-message-g61-native-3000601-')
    artifact = digest(jar)
    assert artifact == source['rootJarSHA256'] == case['jarSHA256']
    assert case['branch'] == '102' and case['caseIndex'] == 0
    processes = smoke.schedule('settled')
    snapshots = case['snapshots']
    assert [(value['dayIndex'], value['phase']) for value in snapshots] == [step for process in processes for step in process]
    uid = snapshots[0]['uid']
    assert {value['uid'] for value in snapshots} == {uid}
    control_days = [(value['clockFixtureEpochSecond'] + 14400) // 86400 for value in snapshots]
    assert set(control_days) == {control_days[0], control_days[0] + 1}
    assert control_days[0] == control_days[1], 'Introduction and late prerequisite unlock must remain on the same actual controlled D0'
    assert [value['loginWallet'] for value in snapshots] == [0, 0, 0, 0, 0, 5000, 5000, 5000, 5000, 5000, 10000]
    probes = []
    for index, value in enumerate(snapshots):
        path = directory / ('probe-' + str(index) + '.log')
        actual = last_json(path)
        assert actual == {key: item for key, item in value.items() if key != 'clockFixtureEpochSecond'}
        probes.append(dict(path=str(path.relative_to(ROOT)), sha256=digest(path), requests=actual['requests'], checks=len(actual['checks'])))
    normal_stops, h2_reads, h2_logs = [], 0, []
    for index in range(len(processes)):
        server = directory / ('server-' + str(index) + '.log')
        assert server.read_text('utf-8').count('Stopping server') == 1, 'Each counted process needs its own real stop command'
        normal_stops.append(dict(path=str(server.relative_to(ROOT)), sha256=digest(server)))
        for prefix, field in (('g61-h2-', 'readOnlyG61H2Inspections'), ('phone-h2-', 'readOnlyPhoneH2Inspections')):
            path = directory / (prefix + str(index) + '.log')
            actual = last_json(path)
            assert actual == case[field][index] == json.loads(path.with_suffix('.json').read_text('utf-8'))
            assert actual['passed'] and actual['mvStoreReadOnly'] and actual['databaseFileBytesUnchanged']
            assert actual['independentMorphiaReadCount'] == 2 and actual['changedPlayerDocumentCount'] == 0
            h2_reads += actual['independentMorphiaReadCount']
            h2_logs.append(dict(path=str(path.relative_to(ROOT)), sha256=digest(path), independentMorphiaReadCount=2))
    phone = case['readOnlyPhoneH2Inspections']
    assert phone[0]['introHasNoCommissionOrPhoneRun'] and not phone[0]['persistedPhoneRuns']
    assert 'currentRun' not in phone[1] and phone[1]['initialStage']['id'] == 300060001
    assert phone[1]['initialStage']['phase'] == 'FINISHED' and phone[1]['firstStage']['phase'] == 'DOING'
    assert not phone[1]['persistedPhoneRuns'], 'Initial stage must be persisted before a Phone receipt exists'
    keys = ('mainAcceptance', 'initialAcceptance', 'firstAcceptance', 'goalAcceptance')
    older, current = phone[-1]['archivedRun'], phone[-1]['currentRun']
    for key in keys:
        assert str(uuid.UUID(older[key])) == older[key] and str(uuid.UUID(current[key])) == current[key]
        assert older[key] != current[key]
        for index in (2, 3, 4):
            assert phone[index]['currentRun'][key] == older[key]
        for index in (5, 6, 7):
            assert phone[index]['currentRun'][key] == current[key]
    for run, day in ((older, control_days[0]), (current, control_days[0] + 1)):
        assert run['phoneFinished']
        for stage in ('main', 'initial', 'first', 'goal'):
            value = run[stage]
            assert value['progress'] == 1 and (value['finishTime'] + 14400) // 86400 == value['runDay'] == day
    assert older['history'] == [121140101, 121140111, 121140102, 121140103, 121140108, 121140109, 121140110]
    assert current['history'] == [121140101, 121140111, 121140104, 121140105, 121140108, 121140109, 121140110]
    assert [row['credit'] for row in phone] == [0, 0, 0, 0, 5000, 5000, 5000, 10000]
    assert [row['activePoints'] for row in phone] == [0, 0, 0, 0, 200, 0, 0, 200]
    observations = case['readOnlyG61H2Inspections']
    smoke.verify_observation_stability(observations)
    assert phone[-1]['databaseSHA256'] == observations[-1]['databaseSHA256'] == digest(directory / 'data/database.mv')
    config = json.loads((directory / 'config.json').read_text('utf-8'))
    assert config['httpServer']['bindAddress'] == config['gameServer']['bindAddress'] == config['internalMongoServer']['address'] == '127.0.0.1'
    assert config['httpServer']['bindPort'] != config['internalMongoServer']['port']
    original = case['installedWholeSourcePreflight']
    assert original['passed'] and original['completeOriginalFiles'] == 51 and original['completeSelectedObjects'] == 103
    assert len(original['files']) == 51
    for path, expected in case['sourceSHA256'].items():
        assert digest(ROOT / 'resources/4.6' / path) == expected
    assert source['executedOriginalPaths'] == [smoke.observed_case_paths(case)]
    assert source['requests'] == sum(value['requests'] for value in snapshots)
    assert source['checks'] == sum(len(value['checks']) for value in snapshots)
    assert source['snapshots'] == len(snapshots) and source['normalStopCount'] == len(normal_stops) == case['normalStopCount']
    assert source['sameUIDNormalH2RestartCount'] == len(normal_stops) - 1 == case['sameUIDNormalH2RestartCount']
    assert source['independentStoppedH2MorphiaReadCount'] == h2_reads == case['independentStoppedH2MorphiaReadCount']
    assert all(source[key] is False for key in ('sourceResourcesModified', 'clientGameplayVerified', 'clientMenuUIVerified',
        'clientCustomStringVerified', 'clientActVerified', 'sourceClientScriptExecutorVerified', 'realWallClockCrossDayVerified', 'crashAtomicityVerified'))
    return dict(passed=True, report=str(report.relative_to(ROOT)), reportSHA256=digest(report), jarSHA256=artifact,
        actualExecutedCaseIndices=[0], requests=source['requests'], checks=source['checks'], snapshots=len(snapshots),
        normalStopCount=len(normal_stops), sameUIDNormalH2RestartCount=len(normal_stops)-1,
        independentStoppedH2MorphiaReadCount=h2_reads, retainedRawProbeLogs=probes, retainedNormalStopLogs=normal_stops,
        retainedReadOnlyH2Logs=h2_logs, fourOriginalAcceptanceKeysStableWithinPeriodAndDifferentNextDay=True,
        originalInitialStagePersistedBeforePhoneReceipt=True, sameControlledDayLateUnlockVerified=True,
        actualFirstDayHistory=older['history'], actualSecondDayHistory=current['history'], mainCreditPerPeriod=5000,
        activePointsPerPeriod=200, originalNpcObservationStable=True, completeOriginalFiles=51,
        operatorSourceBytesUnchanged=True, sourceClientScriptExecutorVerified=False, clientCustomStringVerified=False,
        clientActVerified=False, clientMenuUIVerified=False, realWallClockCrossDayVerified=False, crashAtomicityVerified=False)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--report', type=Path, required=True)
    parser.add_argument('--jar', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    report, jar, output = args.report.resolve(), args.jar.resolve(), args.output.resolve()
    assert all(path.is_relative_to((ROOT / 'build').resolve()) for path in (report, jar, output))
    result = audit(report, jar)
    with output.open('x', encoding='utf-8') as file:
        json.dump(result, file, ensure_ascii=False, indent=2)
    print(json.dumps(dict(passed=True, output=str(output), requests=result['requests'], checks=result['checks'],
        snapshots=result['snapshots'], normalStopCount=result['normalStopCount'], independentStoppedH2MorphiaReadCount=result['independentStoppedH2MorphiaReadCount'])))
