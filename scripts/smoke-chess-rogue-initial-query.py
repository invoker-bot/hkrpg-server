"""首次5547限定原生验收：未解锁/原资格生产结算/重复查询/同UID正常H2恢复；预检不启动服务。"""
import argparse
import base64
import importlib.util
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile
import time
import traceback
import urllib.error
import zipfile

HERE = Path(__file__).resolve().parent
ROOT = next(p for p in HERE.parents if (p / 'src/main/resources/protocol/4.6').is_dir())
sys.path.insert(0, str(ROOT / 'scripts'))
BASE_PATH = ROOT / 'scripts/smoke-chess-rogue-merchant.py'
spec = importlib.util.spec_from_file_location('initial_query_merchant_base', BASE_PATH)
base = importlib.util.module_from_spec(spec)
spec.loader.exec_module(base)
PROOF = ROOT / 'src/test/resources/fixtures/chess-rogue-initial-query-4.6.json'
PROOF_SHA = 'c8499dd6035cdb7dfc4eb63d763b31556fd780c1fb40d580d38e16e33b15a703'
def source_path(local, relative):
    candidate = HERE / local
    return candidate if candidate.is_file() else ROOT / relative


QUALIFICATION_PROOF = source_path('qualification-source-proof.json', 'scripts/fixtures/chess-rogue-initial-query-qualification-4.6.json')
JAVA_SOURCES = [source_path('NativeChessRogueInitialQueryProbe.java', 'scripts/fixtures/java/org/hkrpg/server/protocol/NativeChessRogueInitialQueryProbe.java'),
    source_path('ChessRogueInitialQueryFixtureCommand.java', 'scripts/fixtures/java/emu/lunarcore/command/commands/ChessRogueInitialQueryFixtureCommand.java'),
    source_path('ChessRogueInitialQueryInspector.java', 'scripts/fixtures/java/org/hkrpg/server/gameplay/ChessRogueInitialQueryInspector.java')]
PHASES = ['unqualified', 'unqualified-restart', 'qualified', 'qualified-restart']


def initial_source_proof():
    if base.sha(PROOF) != PROOF_SHA:
        raise ValueError('Complete fixed initial Query source fixture changed')
    proof = json.loads(PROOF.read_text('utf-8'))
    if (proof['sourceCommit'] != base.COMMIT or proof['sourceOriginalBlobCount'] != 88
            or proof['sourceOriginalTableCount'] != 23 or proof['clientGameplayVerified'] is not False
            or proof['staticConsumptionVerified'] is not True
            or proof['commands'] != dict(ChessRogueQueryCsReq=5547, ChessRogueQueryScRsp=5435)
            or proof['descriptorSHA256'] != base.sha(ROOT / 'src/main/resources/protocol/4.6/descriptor.pb')
            or proof['optionalRun']['field'] != 8 or proof['optionalRun']['type'] != 'DJEKEGMEHLK'
            or proof['mandatoryProfile']['field'] != 10 or proof['mandatoryProfile']['type'] != 'HIMJKAMHLMJ'
            or proof['mandatoryProfile']['requiredChildren'] != [dict(field=9, type='MOOKMKEKMKC'), dict(field=12, type='DDAFEPBGOHK')]):
        raise ValueError('Initial Query commands, optional run or complete mandatory profile source changed')
    if len(proof['wholeNativeFunctions']) != 15 or len(proof['completeSelectedSourceRows']) != 5:
        raise ValueError('All fifteen whole native functions and five complete original rows are required')
    for row in proof['wholeNativeFunctions']:
        raw = base64.b64decode(row['rawBase64'], validate=True)
        if len(raw) != row['byteCount'] or base.digest_bytes(raw) != row['sha256']:
            raise ValueError('Whole native initial Query function bytes changed')
    return dict(sourceCommit=base.COMMIT, proofSHA256=PROOF_SHA, wholeNativeFunctions=15,
                completeOriginalQualificationAndNousRows=5, clientGameplayVerified=False)


def qualification_source_proof(source):
    proof = json.loads(QUALIFICATION_PROOF.read_text('utf-8'))
    if (proof['sourceCommit'] != base.COMMIT or proof['qualificationHistorySeeded'] != 8013104
            or proof['productionRefreshSettlesReward'] != 18013104
            or proof['originalFirstReward109011Paid'] is not False or proof['clientGameplayVerified'] is not False
            or len(proof['completeOriginalRows']) != 2):
        raise ValueError('Original qualification history and production reward attribution changed')
    expected_paths = ['ExcelOutput/MainMission.json', 'ExcelOutput/RewardData.json']
    for row, name in zip(proof['completeOriginalRows'], expected_paths):
        if row['path'] != name:
            raise ValueError('Complete original qualification source paths changed')
        original = subprocess.run(['git', '-C', str(ROOT / 'references/TurnBasedGameData'), 'show',
            base.COMMIT + ':' + name], capture_output=True, check=True).stdout
        disk = base.inside(source, name).read_bytes()
        if base.digest_bytes(original) != row['sourceFileSHA256'] or disk.replace(b'\r\n', b'\n') != original.replace(b'\r\n', b'\n'):
            raise ValueError('Whole original qualification table differs from the fixed commit')
        key, identifier = ('MainMissionID', 8013104) if name.endswith('MainMission.json') else ('RewardID', 18013104)
        if [item for item in json.loads(disk) if item.get(key) == identifier] != [row['row']]:
            raise ValueError('Complete original qualification row was changed or duplicated')
    reward = proof['completeOriginalRows'][1]['row']
    if reward != dict(RewardID=18013104, ItemID_1=2, Count_1=5000):
        raise ValueError('Qualification18013104 must contain exactly the original item2/5000 reward')
    return proof


def runtime_capabilities(jar, preflight_only):
    with zipfile.ZipFile(jar) as archive:
        name = 'org/hkrpg/server/gameplay/ChessRogueProfile.class'
        ready = name in archive.namelist() and b'initialSource' in archive.read(name)
    if not preflight_only and not ready:
        raise ValueError('Real initial Query requires the new frozen jar; immutable6525 is preflight only')
    return ready


class InitialQueryConsoleBridge:
    """Only the actual ordered console ACK opens each read-only observation barrier."""
    def __init__(self, mode, nonce):
        if mode not in PHASES or nonce <= 0:
            raise ValueError('Unknown owned Query phase or nonce')
        self.nonce = nonce
        self.operations = (['qualify'] if mode == 'qualified' else []) + ['before-' + mode, 'after-' + mode]
        self.acknowledged, self.seen, self.pending = [], set(), None

    def next_command(self, probe_text):
        for uid, operation in re.findall(r'CHESS_INITIAL_QUERY_READY_UID=(\d+):([a-z-]+)', probe_text):
            if int(uid) != 10001 or operation not in self.operations:
                raise ValueError('Wrong owned UID or initial Query observation operation')
            if operation in self.seen:
                continue
            if self.pending is not None or operation != self.operations[len(self.acknowledged)]:
                raise ValueError('Out-of-order or overlapping initial Query observation')
            self.seen.add(operation)
            self.pending = operation
            return f'chessqueryfixture 10001 {operation} {self.nonce}\n'
        return None

    def next_acknowledgement(self, server_text):
        if self.pending is None:
            return None
        for uid, operation, nonce in re.findall(r'HKRPG_CHESS_INITIAL_QUERY_ACK:(\d+):([a-z-]+):(\d+)', server_text):
            if int(nonce) != self.nonce:
                continue
            if int(uid) != 10001 or operation not in self.operations:
                raise ValueError('Wrong owned UID or console Query ACK')
            if operation in self.acknowledged:
                continue
            if operation != self.pending:
                raise ValueError('Out-of-order initial Query console ACK')
            self.acknowledged.append(operation)
            self.pending = None
            return f'CHESS_INITIAL_QUERY_OBSERVED:{operation}\n'
        return None

    @property
    def complete(self):
        return self.acknowledged == self.operations


def snapshot(path):
    return json.loads(next(line for line in reversed(path.read_text('utf-8').splitlines()) if line.startswith('{')))


def login_refresh_observation(previous, before_query, mode, qualification):
    baseline = before_query
    if mode == 'qualified':
        if qualification is None or qualification['productionSettled']['gameplay'] != before_query['gameplay']:
            raise ValueError('Qualification settlement and actual before-Query gameplay must match')
        baseline = qualification['before']
    return dict(mode=mode, beforeQuery=before_query,
        loginRefreshBaseline='after-normal-login-before-explicit-qualification' if mode == 'qualified' else 'after-normal-login-before-query',
        loginRefreshComparedToPreviousStop=None if previous is None else dict(
            previousGameplay=previous['gameplay'], currentGameplay=baseline['gameplay'],
            gameplayChanged=previous['gameplay'] != baseline['gameplay']))


def prepare(jar, directory, preflight_only):
    source_proof = initial_source_proof()
    qualification = qualification_source_proof(ROOT / 'resources/4.6')
    ready = runtime_capabilities(jar, preflight_only)
    prepared, java, options, classpath, ports = base.prepare(jar, directory, preflight_only)
    flags = subprocess.CREATE_NO_WINDOW if os.name == 'nt' else 0
    compiled = subprocess.run([str(java.with_name('javac.exe')), '-encoding', 'UTF-8', '-cp', classpath,
        '-d', str(directory / 'probe-classes'), *map(str, JAVA_SOURCES)], capture_output=True, creationflags=flags, timeout=90)
    (directory / 'initial-query-javac.log').write_bytes(compiled.stdout + compiled.stderr)
    if compiled.returncode:
        raise RuntimeError('Initial Query javac failed; no native behavior was executed')
    for name, type_name, argument in [('descriptors', 'org.hkrpg.server.protocol.NativeChessRogueInitialQueryProbe', '--verify-descriptors'),
            ('storage-codecs', 'org.hkrpg.server.gameplay.ChessRogueInitialQueryInspector', '--verify-storage-codecs')]:
        checked = subprocess.run([str(java), *options, '-cp', classpath, type_name, argument], capture_output=True,
            text=True, encoding='utf-8', creationflags=flags, timeout=60)
        path = directory / ('initial-query-' + name + '.log')
        path.write_text(checked.stdout + checked.stderr, 'utf-8')
        if checked.returncode:
            raise RuntimeError('Pure initial Query ' + name + ' preflight failed: ' + str(path))
        result = snapshot(path)
        if not result['passed'] or result.get('nativeExecuted', False):
            raise ValueError('Preflight cannot count as actual KCP/client gameplay execution')
        prepared['initialQuery' + name.title().replace('-', '')] = result
    marker_file = directory / 'resources-fixture/resource-manifest.json'
    marker = json.loads(marker_file.read_text('utf-8'))
    marker.update(initialQueryFixture=True, initialQueryCommands=[5547, 5435],
        originalQualificationHistorySeeded=False, qualificationHistorySeededOnlyInExplicitPhase=True)
    marker_file.write_text(json.dumps(marker, ensure_ascii=False, indent=2) + '\n', 'utf-8')
    qualification_file = directory / 'qualification-source-proof.json'
    qualification_file.write_bytes(QUALIFICATION_PROOF.read_bytes())
    prepared.update(initialQuerySourceEvidence=source_proof, qualificationSourceEvidence=qualification,
        frozenJarHasInitialQuery=ready, initialQueryMarkerSHA256=base.sha(marker_file),
        qualificationFixtureSHA256=base.sha(qualification_file),
        initialQueryToolInputs={str(p.relative_to(ROOT)): base.sha(p) for p in [Path(__file__), BASE_PATH, PROOF, QUALIFICATION_PROOF, *JAVA_SOURCES]})
    (directory / 'initial-query-prepared-inputs.json').write_text(json.dumps(prepared, ensure_ascii=False, indent=2) + '\n', 'utf-8')
    return prepared, java, options, classpath, ports


def unchanged(jar, directory, prepared):
    base.unchanged(jar, directory, prepared)
    if prepared['initialQueryToolInputs'] != {name: base.sha(ROOT / name) for name in prepared['initialQueryToolInputs']}:
        raise ValueError('First Query native tool or complete fixed source fixture changed')
    if (base.sha(directory / 'resources-fixture/resource-manifest.json') != prepared['initialQueryMarkerSHA256']
            or base.sha(directory / 'qualification-source-proof.json') != prepared['qualificationFixtureSHA256']):
        raise ValueError('Owned first Query fixture identity changed')


def child_probe(java, options, classpath, directory, server, server_log, game, input_data):
    flags = subprocess.CREATE_NO_WINDOW if os.name == 'nt' else 0
    mode = input_data['mode']
    path = directory / ('initial-query-' + mode + '.log')
    bridge = InitialQueryConsoleBridge(mode, int(time.time()) + PHASES.index(mode))
    with path.open('xb') as writer:
        child = subprocess.Popen([str(java), *options, '-cp', classpath,
            'org.hkrpg.server.protocol.NativeChessRogueInitialQueryProbe', str(game)], cwd=directory,
            stdin=subprocess.PIPE, stdout=writer, stderr=subprocess.STDOUT, creationflags=flags)
        child.stdin.write((json.dumps(input_data) + '\n').encode('utf-8'))
        child.stdin.flush()
        deadline = time.monotonic() + 240
        try:
            while child.poll() is None:
                command = bridge.next_command(path.read_text('utf-8'))
                if command:
                    server.stdin.write(command.encode('utf-8'))
                    server.stdin.flush()
                acknowledgement = bridge.next_acknowledgement(server_log.read_text('utf-8'))
                if acknowledgement:
                    child.stdin.write(acknowledgement.encode('utf-8'))
                    child.stdin.flush()
                    if bridge.complete:
                        child.stdin.close()
                if time.monotonic() > deadline:
                    raise TimeoutError(path)
                time.sleep(.2)
        finally:
            base.stop_owned_probe(child, directory)
    if child.returncode or not bridge.complete:
        raise RuntimeError('Actual first Query native phase failed: ' + str(path))
    result = snapshot(path)
    if not result['passed'] or result['uid'] != 10001 or result['mode'] != mode:
        raise ValueError('Wrong owned first Query snapshot')
    return result


def run(jar, output, preflight_only):
    runs = ROOT / 'build/smoke'
    runs.mkdir(parents=True, exist_ok=True)
    directory = Path(tempfile.mkdtemp(prefix='chess-rogue-native-initial-query-', dir=runs))
    snapshots, inspections, credentials, login_observations, stops = [], [], {}, [], 0
    artifact = base.sha(jar)
    try:
        prepared, java, options, classpath, (http, mongo, game) = prepare(jar, directory, preflight_only)
        unchanged(jar, directory, prepared)
        if preflight_only:
            result = dict(passed=True, prepared=True, nativeExecuted=False, gameServerStarted=False,
                scenario='initial-query-no-run', runtimeDirectory=str(directory), **prepared)
        else:
            flags = subprocess.CREATE_NO_WINDOW if os.name == 'nt' else 0
            for index, mode in enumerate(PHASES):
                unchanged(jar, directory, prepared)
                server_log = directory / f'initial-query-server-{index}.log'
                with server_log.open('xb') as writer:
                    server = subprocess.Popen([str(java), *options, '-Xmx1536m', '-cp', classpath,
                        'org.hkrpg.server.HkrpgServer', '--no-handbook'], cwd=directory,
                        stdin=subprocess.PIPE, stdout=writer, stderr=subprocess.STDOUT, creationflags=flags)
                    ready = False
                    try:
                        deadline = time.monotonic() + 100
                        while not ready:
                            if server.poll() is not None:
                                raise RuntimeError('Owned first Query server startup failed: ' + str(server_log))
                            try:
                                ready = base.local_request(http, '/health/ready')['ready']
                            except (OSError, urllib.error.URLError):
                                pass
                            if time.monotonic() > deadline:
                                raise TimeoutError(server_log)
                            time.sleep(.2)
                        if not credentials:
                            login = base.local_request(http, '/hkrpg_cn/mdk/shield/api/login',
                                dict(account='chess-initial-query-native', password='local-test'))
                            if login['retcode'] != 0:
                                raise ValueError('Owned first Query account login failed')
                            account = login['data']['account']
                            combo = base.local_request(http, '/hkrpg_cn/combo/granter/login/v2/login',
                                dict(data=json.dumps(dict(uid=account['uid'], token=account['token']))))
                            if combo['retcode'] != 0:
                                raise ValueError('Owned first Query combo login failed')
                            credentials.update(accountUid=account['uid'], comboToken=combo['data']['combo_token'])
                        observed = child_probe(java, options, classpath, directory, server, server_log, game,
                            dict(credentials, mode=mode))
                        observed.update(artifactSHA256=artifact, isolatedPolicySHA256=prepared['isolatedPolicySHA256'])
                        snapshots.append(observed)
                        unchanged(jar, directory, prepared)
                    finally:
                        stops += base.stop_owned_server(server, directory, ready)
                before = json.loads((directory / ('initial-query-before-' + mode + '.json')).read_text('utf-8'))
                after = json.loads((directory / ('initial-query-after-' + mode + '.json')).read_text('utf-8'))
                if before != after:
                    raise ValueError('Actual Query wrote live/stored progress, wallet, items, team or a run')
                qualification = json.loads((directory / 'initial-query-qualification.json').read_text('utf-8')) if mode == 'qualified' else None
                login_observations.append(login_refresh_observation(None if not inspections else inspections[-1], before, mode, qualification))
                control = directory / ('initial-query-inspect-' + mode + '.json')
                with control.open('x', encoding='utf-8') as writer:
                    json.dump(dict(uid=10001, mode=mode), writer)
                inspected = subprocess.run([str(java), *options, '-cp', classpath,
                    'org.hkrpg.server.gameplay.ChessRogueInitialQueryInspector', str(directory), str(control)],
                    cwd=ROOT, capture_output=True, text=True, encoding='utf-8', creationflags=flags, timeout=120)
                path = directory / f'initial-query-inspector-{index}.log'
                path.write_text(inspected.stdout + inspected.stderr, 'utf-8')
                if inspected.returncode:
                    raise RuntimeError('Read-only first Query H2 inspection failed: ' + str(path))
                inspection = snapshot(path)
                if not inspection['passed'] or inspection['uid'] != 10001 or inspection['mode'] != mode:
                    raise ValueError('Wrong owned same-UID first Query H2 record')
                inspections.append(inspection)
                unchanged(jar, directory, prepared)
                print(json.dumps(dict(phase=mode, normalStops=stops, checks=len(observed['checks']))), flush=True)
            if stops != 4 or len(snapshots) != 4 or len(inspections) != 4:
                raise ValueError('Four actual first Query snapshots/normal stops/read-only H2 inspections required')
            qualification = json.loads((directory / 'initial-query-qualification.json').read_text('utf-8'))
            if qualification['productionQualificationReward'] != 18013104 or qualification['creditDelta'] != 5000:
                raise ValueError('Qualification refresh reward was not separately source-attributed')
            result = dict(passed=True, prepared=True, nativeExecuted=True, scenario='initial-query-no-run',
                artifactSHA256=artifact, runtimeDirectory=str(directory), normalStops=stops,
                sameUIDNormalH2ProcessRestarts=3, nativeRequests=sum(r['requests'] for r in snapshots),
                nativeChecks=sum(len(r['checks']) for r in snapshots), snapshots=snapshots, inspections=inspections,
                sourceEvidence=prepared, qualificationSettlement=qualification, loginRefreshObservations=login_observations,
                originalResourcesModified=False, operatorDataModified=False, seededOriginalEligibilityHistory=8013104,
                qualificationProductionReward18013104Credits=5000, queryWalletOrItemIncrement=0,
                queryCreatedOrSavedRun=False, nativeQueryPlayerSaveCallsInstrumented=False,
                completeInitialQueryProfileNetworkVerified=True, originalFirstReward109011Paid=False,
                clientGameplayVerified=False, qualificationStoryVerified=False, nousTalentsOrCurrencyVerified=False,
                originalQuitFinishVerified=False, crashAtomicityVerified=False, wholeChessRogueGameComplete=False)
        unchanged(jar, directory, prepared)
        with output.open('x', encoding='utf-8') as report:
            report.write(json.dumps(result, ensure_ascii=False, indent=2) + '\n')
        print(json.dumps(dict(passed=result['passed'], nativeExecuted=result['nativeExecuted'],
            artifactSHA256=artifact, runtimeDirectory=str(directory)), ensure_ascii=False))
    except BaseException as error:
        with (directory / 'failed-initial-query-run.json').open('x', encoding='utf-8') as report:
            json.dump(dict(passed=False, error=repr(error), traceback=''.join(traceback.format_exception(error)),
                artifactSHA256=artifact, runtimeDirectory=str(directory), normalStops=stops,
                completedSnapshots=snapshots, inspections=inspections), report, ensure_ascii=False, indent=2)
        raise


def main():
    if hasattr(sys.stdout, 'reconfigure'):
        sys.stdout.reconfigure(encoding='utf-8')
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--jar', type=Path, required=True, help='统一冻结的新初次Query jar；旧6525只可预检')
    parser.add_argument('--output', type=Path, default=ROOT / 'build' / ('chess-initial-query-native-' + str(time.time_ns()) + '.json'))
    parser.add_argument('--preflight-only', action='store_true', help='完整来源+javac+schema+storage codec，不启动服务/KCP')
    args = parser.parse_args()
    jar, output = args.jar.resolve(), args.output.resolve()
    if not jar.is_file() or not jar.is_relative_to(ROOT / 'build') or not output.is_relative_to(ROOT / 'build'):
        raise ValueError('Only frozen local build jar and fresh build report accepted')
    base.reserve_output(jar, output)
    run(jar, output, args.preflight_only)


if __name__ == '__main__':
    main()
