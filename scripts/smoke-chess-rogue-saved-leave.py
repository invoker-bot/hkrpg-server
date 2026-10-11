"""限定 Chess 保存记录：原商店链→完整 Query→5462→正常 H2 重启→5437；预检不运行游戏服务。"""
import argparse
import base64
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import time
import traceback
import urllib.error
import zipfile

ROOT = Path(__file__).resolve().parents[1]
BASE_PATH = ROOT / 'scripts/smoke-chess-rogue-merchant.py'
spec = importlib.util.spec_from_file_location('chess_merchant_base', BASE_PATH)
base = importlib.util.module_from_spec(spec)
spec.loader.exec_module(base)
PROOF = ROOT / 'src/test/resources/fixtures/chess-rogue-leave-and-save-4.6.json'
PROOF_SHA = '71b468fc3e530464150f1dda02a5fc1502802c2f77b121e60e0f7f821c42c1b8'
JAVA_SOURCES = [ROOT / 'scripts/fixtures/java/org/hkrpg/server/protocol/NativeChessRogueSavedLeaveProbe.java',
                ROOT / 'scripts/fixtures/java/org/hkrpg/server/gameplay/ChessRogueSavedLeaveInspector.java']
PHASES = ['saved-leave', 'paused-resume', 'resaved-leave']


def leave_source_proof():
    if base.sha(PROOF) != PROOF_SHA:
        raise ValueError('Complete fixed Leave source fixture changed')
    proof = json.loads(PROOF.read_text('utf-8'))
    if (proof['sourceCommit'] != base.COMMIT or proof['sourceOriginalBlobCount'] != 88
            or proof['sourceOriginalTableCount'] != 23 or proof['clientGameplayVerified'] is not False
            or proof['commands']['ChessRogueLeaveCsReq'] != 5462
            or proof['commands']['ChessRogueLeaveScRsp'] != 5419 or proof['nativeRecordStatus'] != 2):
        raise ValueError('Native whole Leave source, commands or record status changed')
    if len(proof['wholeNativeFunctions']) != 11:
        raise ValueError('Complete eleven native functions/leaf originals required')
    for row in proof['wholeNativeFunctions']:
        raw = base64.b64decode(row['rawBase64'], validate=True)
        if len(raw) != row['byteCount'] or base.digest_bytes(raw) != row['sha256']:
            raise ValueError('Whole native Leave function bytes changed')
    lua = proof['wholeLua']
    raw = base64.b64decode(lua['rawBase64'], validate=True)
    if len(raw) != 13145 or lua['fullyConsumedPrototypeCount'] != 30 or base.digest_bytes(raw) != lua['sha256']:
        raise ValueError('Whole original Lua page changed')
    return dict(sourceCommit=base.COMMIT, proofSHA256=PROOF_SHA, wholeNativeFunctions=11,
                wholeLuaBytes=13145, fullyConsumedLuaPrototypes=30,
                clientGameplayVerified=False, officialReturnMappingRecovered=False)


def runtime_capabilities(jar, preflight_only):
    with zipfile.ZipFile(jar) as archive:
        profile = 'org/hkrpg/server/gameplay/ChessRogueProfile.class' in archive.namelist()
        name = 'org/hkrpg/server/gameplay/ChessRogueState$ReturnLocation.class'
        lease = name in archive.namelist() and b'getSourceSignature' in archive.read(name)
    if not preflight_only and not (profile and lease):
        raise ValueError('Real execution requires the new profile/return-lease frozen jar; F2 is preflight only')
    return profile, lease


def prepare(jar, directory, preflight_only):
    proof = leave_source_proof()
    prepared, java, options, classpath, ports = base.prepare(jar, directory, preflight_only)
    flags = subprocess.CREATE_NO_WINDOW if os.name == 'nt' else 0
    classes = directory / 'probe-classes'
    compiled = subprocess.run([str(java.with_name('javac.exe')), '-encoding', 'UTF-8', '-cp', classpath,
        '-d', str(classes), *map(str, JAVA_SOURCES)], capture_output=True, creationflags=flags, timeout=90)
    (directory / 'saved-leave-javac.log').write_bytes(compiled.stdout + compiled.stderr)
    if compiled.returncode:
        raise RuntimeError('Saved-record javac failed; not a native behavior result')
    checked = subprocess.run([str(java), *options, '-cp', classpath,
        'org.hkrpg.server.protocol.NativeChessRogueSavedLeaveProbe', '--verify-descriptors'],
        capture_output=True, text=True, encoding='utf-8', creationflags=flags, timeout=60)
    (directory / 'saved-leave-descriptors.log').write_text(checked.stdout + checked.stderr, 'utf-8')
    if checked.returncode:
        raise RuntimeError('Saved-record descriptor preflight failed')
    descriptors = json.loads(next(line for line in reversed(checked.stdout.splitlines()) if line.startswith('{')))
    if not descriptors['passed']:
        raise ValueError('Saved-record descriptors did not pass')
    profile, lease = runtime_capabilities(jar, preflight_only)
    marker_file = directory / 'resources-fixture/resource-manifest.json'
    marker = json.loads(marker_file.read_text('utf-8'))
    marker['savedLeaveFixture'] = True
    marker['savedLeaveCommands'] = [5462, 5419, 5547, 5437, 5483]
    marker_file.write_text(json.dumps(marker, ensure_ascii=False, indent=2) + '\n', 'utf-8')
    prepared.update(leaveSourceEvidence=proof, savedLeaveDescriptorSchemas=descriptors['schemas'],
        savedLeaveDescriptorChecks=descriptors['checks'], frozenJarHasProfile=profile,
        frozenJarHasReturnLease=lease, savedLeaveMarkerSHA256=base.sha(marker_file),
        savedLeaveToolInputs={str(p.relative_to(ROOT)): base.sha(p) for p in [Path(__file__), BASE_PATH, PROOF, *JAVA_SOURCES]})
    (directory / 'saved-leave-prepared-inputs.json').write_text(json.dumps(prepared, ensure_ascii=False, indent=2) + '\n', 'utf-8')
    return prepared, java, options, classpath, ports


def unchanged(jar, directory, prepared):
    base.unchanged(jar, directory, prepared)
    if prepared['savedLeaveToolInputs'] != {name: base.sha(ROOT / name) for name in prepared['savedLeaveToolInputs']}:
        raise ValueError('Saved-record native tool or complete fixed source fixture changed')
    if base.sha(directory / 'resources-fixture/resource-manifest.json') != prepared['savedLeaveMarkerSHA256']:
        raise ValueError('Owned saved-record fixture identity changed')


def snapshot(path):
    return json.loads(next(line for line in reversed(path.read_text('utf-8').splitlines()) if line.startswith('{')))


def child_probe(java, options, classpath, directory, server, server_log, game, name, input_data, observe):
    flags = subprocess.CREATE_NO_WINDOW if os.name == 'nt' else 0
    path = directory / (name + '.log')
    probe_class = 'NativeChessRogueMerchantProbe' if observe else 'NativeChessRogueSavedLeaveProbe'
    with path.open('wb') as writer:
        child = subprocess.Popen([str(java), *options, '-cp', classpath,
            'org.hkrpg.server.protocol.' + probe_class, str(game)], cwd=directory,
            stdin=subprocess.PIPE, stdout=writer, stderr=subprocess.STDOUT, creationflags=flags)
        child.stdin.write((json.dumps(input_data) + '\n').encode('utf-8'))
        child.stdin.flush()
        if not observe:
            child.stdin.close()
        bridge = base.FixtureConsoleBridge(int(time.time()))
        deadline = time.monotonic() + 240
        try:
            while child.poll() is None:
                if observe:
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
    if child.returncode or observe and not bridge.complete:
        raise RuntimeError('Actual saved-record native probe failed: ' + str(path))
    result = snapshot(path)
    if not result['passed'] or result['uid'] != 10001 or result['mode'] != input_data['mode']:
        raise ValueError('Wrong owned saved-record snapshot')
    return result


def run(jar, output, preflight_only):
    runs = ROOT / 'build/smoke'
    runs.mkdir(parents=True, exist_ok=True)
    directory = Path(tempfile.mkdtemp(prefix='chess-rogue-native-saved-leave-', dir=runs))
    snapshots, inspections, credentials, stops = [], [], {}, 0
    artifact = base.sha(jar)
    try:
        prepared, java, options, classpath, (http, mongo, game) = prepare(jar, directory, preflight_only)
        unchanged(jar, directory, prepared)
        if preflight_only:
            result = dict(passed=True, prepared=True, nativeExecuted=False, gameServerStarted=False,
                scenario='saved-record-leave', runtimeDirectory=str(directory), **prepared)
        else:
            flags = subprocess.CREATE_NO_WINDOW if os.name == 'nt' else 0
            for index, mode in enumerate(PHASES):
                unchanged(jar, directory, prepared)
                server_log = directory / f'saved-leave-server-{index}.log'
                with server_log.open('wb') as writer:
                    server = subprocess.Popen([str(java), *options, '-Xmx1536m', '-cp', classpath,
                        'org.hkrpg.server.HkrpgServer', '--no-handbook'], cwd=directory,
                        stdin=subprocess.PIPE, stdout=writer, stderr=subprocess.STDOUT, creationflags=flags)
                    ready = False
                    try:
                        deadline = time.monotonic() + 100
                        while not ready:
                            if server.poll() is not None:
                                raise RuntimeError('Owned saved-record server startup failed: ' + str(server_log))
                            try:
                                ready = base.local_request(http, '/health/ready')['ready']
                            except (OSError, urllib.error.URLError):
                                pass
                            if time.monotonic() > deadline:
                                raise TimeoutError(server_log)
                            time.sleep(.2)
                        if not credentials:
                            login = base.local_request(http, '/hkrpg_cn/mdk/shield/api/login', dict(account='chess-saved-record-native', password='local-test'))
                            if login['retcode'] != 0:
                                raise ValueError('Owned saved-record account login failed')
                            account = login['data']['account']
                            combo = base.local_request(http, '/hkrpg_cn/combo/granter/login/v2/login',
                                dict(data=json.dumps(dict(uid=account['uid'], token=account['token']))))
                            if combo['retcode'] != 0:
                                raise ValueError('Owned saved-record combo login failed')
                            credentials.update(accountUid=account['uid'], comboToken=combo['data']['combo_token'])
                            merchant = child_probe(java, options, classpath, directory, server, server_log, game,
                                'original-merchant-sequence', dict(credentials, mode='sequence', actors=prepared['actors'], boards=prepared['boards']), True)
                            merchant.update(artifactSHA256=artifact, isolatedPolicySHA256=prepared['isolatedPolicySHA256'])
                            snapshots.append(merchant)
                            credentials.update(uid=merchant['uid'], gameInfoHash=merchant['gameInfoHash'], classicHash=merchant['classicHash'])
                            unchanged(jar, directory, prepared)
                        observed = json.loads((directory / 'fixture-observed-start.json').read_text('utf-8'))
                        ordinary = next(row for row in observed['ordinaryAvatars'] if row['avatarId'] == 8001)
                        result_snapshot = child_probe(java, options, classpath, directory, server, server_log, game,
                            mode, dict(credentials, mode=mode, boards=prepared['boards'], ordinaryAvatar=ordinary), False)
                        result_snapshot.update(artifactSHA256=artifact, isolatedPolicySHA256=prepared['isolatedPolicySHA256'])
                        snapshots.append(result_snapshot)
                        unchanged(jar, directory, prepared)
                    finally:
                        stops += base.stop_owned_server(server, directory, ready)
                control = directory / f'inspect-{mode}.json'
                expected = dict(result_snapshot, encounters=snapshots[0]['encounters'])
                if inspections:
                    expected['expectedChessDocument'] = inspections[0]['chessDocument']
                control.write_text(json.dumps(expected, ensure_ascii=False, indent=2), 'utf-8')
                inspected = subprocess.run([str(java), *options, '-cp', classpath,
                    'org.hkrpg.server.gameplay.ChessRogueSavedLeaveInspector', str(directory), str(control)],
                    cwd=ROOT, capture_output=True, text=True, encoding='utf-8', creationflags=flags, timeout=120)
                (directory / f'saved-leave-inspector-{index}.log').write_text(inspected.stdout + inspected.stderr, 'utf-8')
                if inspected.returncode:
                    raise RuntimeError('Read-only saved-record H2 inspection failed: ' + str(directory / f'saved-leave-inspector-{index}.log'))
                inspection = json.loads(next(line for line in reversed(inspected.stdout.splitlines()) if line.startswith('{')))
                if not inspection['passed'] or inspection['uid'] != credentials['uid']:
                    raise ValueError('Wrong owned same-UID H2 record')
                inspections.append(inspection)
                unchanged(jar, directory, prepared)
                print(json.dumps(dict(phase=mode, normalStops=stops, checks=len(result_snapshot['checks']))), flush=True)
            if stops != 3 or len(snapshots) != 4 or len(inspections) != 3:
                raise ValueError('Expected merchant plus three saved-record snapshots and three normal stops/H2 reads')
            result = dict(passed=True, prepared=True, nativeExecuted=True, scenario='saved-record-leave',
                artifactSHA256=artifact, runtimeDirectory=str(directory), normalStops=stops,
                sameUIDNormalH2ProcessRestarts=2, nativeRequests=sum(r['requests'] for r in snapshots),
                nativeChecks=sum(len(r['checks']) for r in snapshots), snapshots=snapshots, inspections=inspections,
                sourceEvidence=prepared, originalResourcesModified=False, operatorDataModified=False,
                seededOriginalEligibilityHistory=8013104, chessRunCoinsVictoryRewardOrEntrySeeded=False,
                savedRecordNetworkLeaveAndResumeVerified=True, completeQueryProfileNetworkVerified=True,
                clientGameplayVerified=False, clientMenuActExecuted=False, originalQuitFinishVerified=False,
                blessingShopVerified=False, miracle4FutureEffectVerified=False, crashAtomicityVerified=False,
                wholeChessRogueGameComplete=False, wholeGameComplete=False)
        unchanged(jar, directory, prepared)
        with output.open('x', encoding='utf-8') as report:
            report.write(json.dumps(result, ensure_ascii=False, indent=2) + '\n')
        print(json.dumps(dict(passed=result['passed'], nativeExecuted=result['nativeExecuted'],
            artifactSHA256=artifact, runtimeDirectory=str(directory)), ensure_ascii=False))
    except BaseException as error:
        with (directory / 'failed-saved-leave-run.json').open('x', encoding='utf-8') as report:
            json.dump(dict(passed=False, error=repr(error), traceback=''.join(traceback.format_exception(error)),
                artifactSHA256=artifact, runtimeDirectory=str(directory), normalStops=stops,
                completedSnapshots=snapshots, inspections=inspections), report, ensure_ascii=False, indent=2)
        raise


def main():
    if hasattr(sys.stdout, 'reconfigure'):
        sys.stdout.reconfigure(encoding='utf-8')
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--jar', type=Path, required=True, help='统一冻结的新profile/保存离开jar，F2只可预检')
    parser.add_argument('--output', type=Path, default=ROOT / 'build' / ('chess-saved-leave-native-' + str(time.time_ns()) + '.json'))
    parser.add_argument('--preflight-only', action='store_true', help='完整来源+javac+schema+storage codec，不启动游戏服务/KCP')
    args = parser.parse_args()
    jar, output = args.jar.resolve(), args.output.resolve()
    if not jar.is_file() or not jar.is_relative_to(ROOT / 'build') or not output.is_relative_to(ROOT / 'build'):
        raise ValueError('Only frozen local build jar and fresh build report accepted')
    base.reserve_output(jar, output)
    run(jar, output, args.preflight_only)


if __name__ == '__main__':
    main()
