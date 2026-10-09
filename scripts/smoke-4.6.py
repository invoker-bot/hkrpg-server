"""完整 4.6 实验服务、HTTP 与合成 KCP 登录验证；不启动或修改游戏客户端。"""
import base64
import json
import os
from pathlib import Path
import socket
import subprocess
import tempfile
import time
import urllib.error
import urllib.request

ROOT = Path(__file__).resolve().parents[1]


def free_port(kind=socket.SOCK_STREAM):
    with socket.socket(type=kind) as sock:
        sock.bind(("127.0.0.1", 0))
        return sock.getsockname()[1]


def request(base, route, body=None):
    data = None if body is None else json.dumps(body).encode()
    req = urllib.request.Request(base + route, data=data, headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=5) as response:
        return response.read()


def fields(data):
    index = 0

    def varint():
        nonlocal index
        value = shift = 0
        while index < len(data) and shift <= 63:
            byte = data[index]
            index += 1
            value |= (byte & 127) << shift
            if byte < 128: return value
            shift += 7
        raise ValueError("Invalid varint")

    result = {}
    while index < len(data):
        tag = varint()
        number, kind = tag >> 3, tag & 7
        if kind == 0: value = varint()
        elif kind in (1, 2, 5):
            size = varint() if kind == 2 else (8 if kind == 1 else 4)
            if size > len(data) - index: raise ValueError("Truncated field")
            value = data[index:index + size]
            index += size
        else: raise ValueError("Unsupported wire type")
        result.setdefault(number, []).append(value)
    return result


def main():
    java = next((ROOT / ".tools").glob("jdk-21*/bin/java.exe"), None)
    java = str(java) if java else "java"
    artifact = ROOT / "build/libs/hkrpg-server.jar"
    runs = ROOT / "build/smoke"
    runs.mkdir(parents=True, exist_ok=True)
    directory = Path(tempfile.mkdtemp(prefix="full-46-", dir=runs))
    # An isolated resource fixture opens only this panel's story gate. Operator resources stay intact.
    source_resources = ROOT / "resources/4.6"
    fixture_resources = directory / "resources-fixture"
    fixture_excel = fixture_resources / "ExcelOutput"
    fixture_excel.mkdir(parents=True)
    for source in (source_resources / "ExcelOutput").glob("*.json"):
        target = fixture_excel / source.name
        if source.name == "ActivityPanel.json":
            panel_data = json.loads(source.read_text(encoding="utf-8"))
            panels = list(panel_data.values()) if isinstance(panel_data, dict) else panel_data
            panel = next(row for row in panels if row["PanelID"] == 20003)
            original_trial_unlock = panel["UnlockConditions"]
            panel["UnlockConditions"] = ""
            target.write_text(json.dumps(panel_data, ensure_ascii=False), encoding="utf-8")
        else:
            os.link(source, target)
    if os.name == "nt":
        def ps_literal(path): return "'" + str(path).replace("'", "''") + "'"
        subprocess.run(["powershell", "-NoProfile", "-NonInteractive", "-Command",
            "New-Item -ItemType Junction -Path " + ps_literal(fixture_resources / "Config") +
            " -Value " + ps_literal(source_resources / "Config") + " | Out-Null"],
            check=True, capture_output=True, text=True)
    else:
        (fixture_resources / "Config").symlink_to(source_resources / "Config", target_is_directory=True)
    (fixture_resources / "resource-manifest.json").write_text(json.dumps({"clientVersion": "4.6.0",
        "testFixture": True, "modifiedPanelUnlock": 20003, "originalUnlockConditions": original_trial_unlock}), encoding="utf-8")
    (directory / "data").mkdir()
    (directory / "data/Gameplay46.json").write_text(json.dumps({"activities": [
        {"panelId": 20003, "moduleId": 2000401, "beginTime": 0, "endTime": int(time.time()) + 86400}]}), encoding="utf-8")
    http_port, mongo_port, game_port = free_port(), free_port(), free_port(socket.SOCK_DGRAM)
    config = {"protocol": {"wireVersion": "4.6.0"}, "resourceDir": str(fixture_resources),
        "httpServer": {"bindAddress": "127.0.0.1", "bindPort": http_port},
        "gameServer": {"bindAddress": "127.0.0.1", "bindPort": game_port},
        "internalMongoServer": {"address": "127.0.0.1", "port": mongo_port, "filePath": "./data/database.mv"}}
    (directory / "config.json").write_text(json.dumps(config), encoding="utf-8")
    command = [java, "-Xmx1536m", f"-Djdk.net.unixdomain.tmpdir={directory / 'tcp-fallback'}", "-jar", str(artifact), "--no-console", "--no-handbook"]

    def combat_probe(log_name, payload):
        session = subprocess.run([java, command[2], "-cp", str(artifact),
            "org.hkrpg.server.protocol.LoopbackRogueCombatProbe", str(game_port)],
            input=json.dumps(payload), cwd=directory, capture_output=True, text=True,
            encoding="utf-8", errors="replace", timeout=30)
        (directory / log_name).write_text(session.stdout + session.stderr, encoding="utf-8")
        if session.returncode:
            raise RuntimeError(f"Actual rogue combat failed; inspect {directory / log_name}")
        snapshot = json.loads(next(line for line in session.stdout.splitlines() if line.startswith('{"uid":')))
        assert snapshot["passed"] and not snapshot["clientGameplayVerified"]
        return snapshot
    def trial_probe(log_name, payload):
        session = subprocess.run([java, command[2], "-cp", str(artifact),
            "org.hkrpg.server.protocol.LoopbackTrialRecoveryProbe", str(game_port)],
            input=json.dumps(payload), cwd=directory, capture_output=True, text=True,
            encoding="utf-8", errors="replace", timeout=30)
        (directory / log_name).write_text(session.stdout + session.stderr, encoding="utf-8")
        if session.returncode:
            raise RuntimeError(f"Actual trial recovery failed; inspect {directory / log_name}")
        snapshot = json.loads(next(line for line in session.stdout.splitlines() if line.startswith('{"uid":')))
        assert snapshot["passed"] and snapshot["activeTrialSnapshot"] and not snapshot["clientGameplayVerified"]
        return snapshot
    check = subprocess.run(command + ["--check"], cwd=directory, capture_output=True, text=True, encoding="utf-8", errors="replace")
    (directory / "preflight.log").write_text(check.stdout + check.stderr, encoding="utf-8")
    if check.returncode: raise RuntimeError(f"4.6 resource preflight failed; inspect {directory / 'preflight.log'}")
    base = f"http://127.0.0.1:{http_port}"
    checks = ["resource-preflight"]
    with (directory / "server.log").open("w", encoding="utf-8") as log:
        process = subprocess.Popen(command, cwd=directory, stdin=subprocess.DEVNULL, stdout=log, stderr=subprocess.STDOUT,
            creationflags=subprocess.CREATE_NO_WINDOW if os.name == "nt" else 0)
        try:
            deadline = time.monotonic() + 90
            while True:
                if process.poll() is not None: raise RuntimeError(f"4.6 server exited; inspect {directory / 'server.log'}")
                try:
                    if json.loads(request(base, "/health/ready"))["ready"]: break
                except (OSError, urllib.error.URLError): pass
                if time.monotonic() > deadline: raise RuntimeError(f"4.6 startup timeout; inspect {directory}")
                time.sleep(0.2)
            health = json.loads(request(base, "/health/live"))
            assert health["clientVersion"] == "4.6.0" and health["experimentalProtocol"] and not health["clientAccepted"]
            checks.append("full-java-service-ready")
            probe = subprocess.run([java, command[2], "-cp", str(artifact), "org.hkrpg.server.protocol.LoopbackHeartbeatProbe", str(game_port)],
                cwd=directory, capture_output=True, text=True, encoding="utf-8", errors="replace", timeout=25)
            (directory / "kcp-probe.log").write_text(probe.stdout + probe.stderr, encoding="utf-8")
            if probe.returncode: raise RuntimeError(f"KCP heartbeat failed; inspect {directory / 'kcp-probe.log'}")
            checks.append("46-loopback-kcp-heartbeat")
            gateway = fields(base64.b64decode(request(base, "/query_gateway?version=CNPRODWin4.6.0")))
            assert gateway[13] == [game_port] and gateway[14] == [b"127.0.0.1"]
            assert gateway.get(497, [0]) == [0]
            regions = fields(base64.b64decode(request(base, "/query_dispatch?version=CNPRODWin4.6.0")))
            assert regions.get(4), "Game region was not registered"
            checks.append("46-gateway-and-dispatch-fields")
            login = json.loads(request(base, "/hkrpg_cn/mdk/shield/api/login", {"account": "cn-46-smoke", "password": "local-test"}))
            assert login["retcode"] == 0
            account = login["data"]["account"]
            verified = json.loads(request(base, "/hkrpg_cn/mdk/shield/api/verify", {"uid": account["uid"], "token": account["token"]}))
            assert verified["retcode"] == 0
            combo = json.loads(request(base, "/hkrpg_cn/combo/granter/login/v2/login", {"data": json.dumps({"uid": account["uid"], "token": account["token"]})}))
            assert combo["retcode"] == 0 and combo["data"]["combo_token"]
            checks.append("cn-sdk-token-chain")
            credentials = {"accountUid": account["uid"], "comboToken": combo["data"]["combo_token"]}
            snapshots = []
            nickname = "4.6持久化探针"
            for attempt in range(2):
                probe_input = dict(credentials)
                probe_input["universeMode"] = "start" if attempt == 0 else "resume"
                if attempt == 0:
                    probe_input["setNickname"] = nickname
                    probe_input["finishTalk"] = True
                session = subprocess.run([java, command[2], "-cp", str(artifact), "org.hkrpg.server.protocol.LoopbackSessionProbe", str(game_port)],
                    input=json.dumps(probe_input), cwd=directory, capture_output=True, text=True, encoding="utf-8", errors="replace", timeout=30)
                (directory / f"session-probe-{attempt}.log").write_text(session.stdout + session.stderr, encoding="utf-8")
                if session.returncode: raise RuntimeError(f"KCP login failed; inspect {directory / f'session-probe-{attempt}.log'} and server.log")
                snapshot = json.loads(next(line for line in session.stdout.splitlines() if line.startswith('{"uid":')))
                assert snapshot["passed"] and not snapshot["clientGameplayVerified"]
                assert snapshot["nativeUniverseChoiceRejected"] and snapshot["rogueCatalogAreas"] == 33
                assert snapshot["nativeUniverseUnassignedRoomRejected"]
                assert snapshot["nativeUniverseLifecycle"]
                assert snapshot["initialUniverseFragments"] == 50
                assert snapshot["nativeReviveCostSnapshot"] and snapshot["nativeReviveCostNotify"] and snapshot["nativeInvalidReviveRejected"]
                assert snapshot["nativeTalentQuery"] and snapshot["nativeInvalidTalentRejected"] and snapshot["resourceTalents"] == 42
                assert snapshot["nativeCycleQuery"] and snapshot["nativeUnearnedCycleRejected"]
                assert snapshot["nativeMapQueries"] > 0 and snapshot["nativeInvalidPropRejected"] and snapshot["nativeInvalidTeleportRejected"]
                snapshots.append(snapshot)
            assert snapshots[0]["uid"] == snapshots[1]["uid"], "Player UID changed on reconnect"
            assert snapshots[0]["avatars"] == snapshots[1]["avatars"], "Avatar snapshot changed on reconnect"
            assert snapshots[0]["universeRoom"] == snapshots[1]["universeRoom"], "Saved universe room changed on reconnect"
            assert snapshots[1]["nickname"] == nickname, "Nickname mutation was not preserved on reconnect"
            assert snapshots[0]["talkMutation"] and snapshots[1]["introTalkStatus"] == "MissionStatus_MissionFinish", "Talk objective mutation lost on reconnect"
            assert all(snapshot["battleWinAndRetry"] and snapshot["normalAttackAndInvalidSkill"] for snapshot in snapshots)
            checks.extend(["46-kcp-login-and-initial-snapshots", "46-kcp-player-reconnect"])
            checks.extend(["46-normal-attack-invalid-skill-stage-query-quit-win-retry", "46-talk-objective-persisted-on-reconnect"])
            assert snapshots[0]["nativeFirstTalkQuery"] and snapshots[0]["nativeInvalidFirstTalkRejected"]
            checks.append("46-native-first-talk-query-unknown-completion-rejected")
            checks.append("46-native-map-anchor-query-invalid-prop-and-teleport-rejected")
            assert all(row["nativeUnplayedParkourQuery"] and row["nativeClosedParkourStartRejected"] and row["nativeUnstartedParkourEndRejected"] for row in snapshots)
            checks.append("46-native-parkour-unplayed-query-closed-start-unstarted-end-rejected")
            assert all(row["nativeResourceParkourRanking"] for row in snapshots)
            checks.append("46-native-parkour-resource-ranking-levels-without-invented-players")
            assert all(row["nativeClosedParkourRaidRejected"] and row["nativeUnstartedParkourRaidExitRejected"] and row["nativeUnsavedParkourRaidQuery"] for row in snapshots)
            checks.append("46-native-parkour-raid-closed-entry-unstarted-exit-unsaved-directory")
            assert all(row["nativeAbsentConsumableRejected"] for row in snapshots)
            checks.append("46-native-absent-consumable-error-without-invented-use-or-rewards")
            assert all(row["nativeUnacceptedSelectionRejected"] for row in snapshots)
            checks.append("46-native-selected-item-mission-rejects-unaccepted-resource-objective")
            assert all(row["nativeUnstartedStoryQuery"] for row in snapshots)
            checks.append("46-native-story-query-without-invented-branches-or-trials")
            assert all(row["nativeTrialLifecycle"] and row["nativeTrialStaleLeaveRejected"] and row["nativeTrialActors"] > 0 for row in snapshots)
            assert all(row["nativeTrialActivitySchedule"] and row["nativeClosedTrialStartRejected"] for row in snapshots)
            checks.append("46-native-trial-resource-panel-module-schedule-unadvertised-stage-rejected")
            checks.append("46-native-trial-start-real-actors-stale-leave-rejected-return")
            assert all(row["nativeTrialCombatVictoryAndRetry"] and row["nativeTrialZeroInitialEnergy"] and row["nativeTrialFinishNotifications"] == 1 for row in snapshots)
            checks.append("46-native-trial-real-npc-battle-zero-energy-victory-once-retry-rejected")
            assert all(row["nativeTrialRewardOnceAndQuery"] for row in snapshots)
            assert snapshots[0]["nativeTrialRewardItems"] == {"2": 10000, "212": 2, "111011": 4}
            assert snapshots[1]["nativeTrialRewardRestored"]
            checks.append("46-native-trial-unplayed-rejected-confirmed-resource-reward-once-reconnect")
            checks.append("46-native-resource-talent-query-unknown-activation-rejected")
            checks.extend(["46-native-universe-catalog-query", "46-native-universe-invalid-choice-rejected",
                           "46-native-universe-unassigned-room-rejected"])
            checks.extend(["46-native-universe-start-real-room-lineup-leave", "46-native-universe-resume-after-reconnect"])
            for declared in ["OSBETAWin4.6.51", "OSBETAWin4.6.0", "CNDEVWin4.6.0"]:
                try: request(base, "/query_gateway?version=" + declared)
                except urllib.error.HTTPError as error: assert error.code == 400
                else: raise AssertionError("Non-release version unexpectedly accepted")
            checks.append("beta-version-rejected")
            backlog = json.loads(request(base, "/api/feature-backlog"))["entries"]
            expected_backlog = json.loads((ROOT / "src/main/resources/feature-backlog.json").read_text(encoding="utf-8"))["entries"]
            assert len(backlog) == len(expected_backlog) and len(backlog) > 400
            by_name = {entry["request"]: entry for entry in backlog}
            for name in ["PlayerGetTokenCsReq", "PlayerLoginCsReq", "PlayerLoginFinishCsReq", "GetAvatarDataCsReq",
                         "GetBagCsReq", "GetAllLineupDataCsReq", "GetCurSceneInfoCsReq", "ContentPackageGetDataCsReq", "SetNicknameCsReq",
                         "GetRogueInfoCsReq", "HandleRogueCommonPendingActionCsReq", "EnterRogueMapRoomCsReq"]:
                entry = by_name[name]
                assert entry["status"] == "partial" and entry["acceptance"]["serverBehavior"] and not entry["acceptance"]["client46"]
            features = json.loads(request(base, "/api/features"))["features"]
            assert next(feature for feature in features if feature["id"] == "content-package")["status"] == "partial"
            checks.append("packaged-feature-catalog-boundaries")
            time.sleep(2)  # Allow the embedded H2 delayed disk commit before process restart.
        finally:
            if process.poll() is None:
                process.terminate()
                try: process.wait(timeout=15)
                except subprocess.TimeoutExpired:
                    process.kill()
                    process.wait(timeout=5)
    with (directory / "server-restart.log").open("w", encoding="utf-8") as log:
        process = subprocess.Popen(command, cwd=directory, stdin=subprocess.DEVNULL, stdout=log, stderr=subprocess.STDOUT,
            creationflags=subprocess.CREATE_NO_WINDOW if os.name == "nt" else 0)
        try:
            deadline = time.monotonic() + 90
            while True:
                if process.poll() is not None: raise RuntimeError(f"4.6 restart failed; inspect {directory / 'server-restart.log'}")
                try:
                    if json.loads(request(base, "/health/ready"))["ready"]: break
                except (OSError, urllib.error.URLError): pass
                if time.monotonic() > deadline: raise RuntimeError(f"4.6 restart timeout; inspect {directory}")
                time.sleep(0.2)
            restart_credentials = dict(credentials, universeMode="resume", abandonUniverse=True)
            session = subprocess.run([java, command[2], "-cp", str(artifact), "org.hkrpg.server.protocol.LoopbackSessionProbe", str(game_port)],
                input=json.dumps(restart_credentials), cwd=directory, capture_output=True, text=True, encoding="utf-8", errors="replace", timeout=30)
            (directory / "session-probe-restart.log").write_text(session.stdout + session.stderr, encoding="utf-8")
            if session.returncode: raise RuntimeError(f"KCP restart login failed; inspect {directory / 'session-probe-restart.log'}")
            restored = json.loads(next(line for line in session.stdout.splitlines() if line.startswith('{"uid":')))
            for key in ("uid", "avatars", "avatarPaths", "lineups", "floor", "entry", "contentPackages"):
                assert restored[key] == snapshots[0][key], f"Player snapshot changed after restart: {key}"
            assert restored["nickname"] == nickname, "Nickname mutation was not preserved after process restart"
            assert restored["introTalkStatus"] == "MissionStatus_MissionFinish", "Talk objective mutation lost after restart"
            assert restored["battleWinAndRetry"] and restored["normalAttackAndInvalidSkill"]
            assert restored["nativeTrialRewardRestored"] and restored["nativeTrialRewardOnceAndQuery"]
            checks.append("46-native-trial-confirmed-reward-restored-after-process-restart")
            assert restored["nativeUniverseChoiceRejected"] and restored["rogueCatalogAreas"] == 33
            assert restored["nativeUniverseUnassignedRoomRejected"]
            assert restored["nativeUniverseLifecycle"] and restored["nativeUniverseAbandoned"]
            assert restored["initialUniverseFragments"] == 50
            checks.append("46-native-universe-resource-initial-fragments-resume-restart")
            assert restored["nativeReviveCostSnapshot"] and restored["nativeReviveCostNotify"] and restored["nativeInvalidReviveRejected"]
            checks.append("46-native-revive-saved-cost-query-notify-invalid-target-rejected")
            assert restored["nativeUniverseAbandonResult"]
            assert restored["nativeTalentQuery"] and restored["nativeInvalidTalentRejected"] and restored["resourceTalents"] == 42
            assert restored["nativeCycleQuery"] and restored["nativeUnearnedCycleRejected"]
            checks.append("46-native-cycle-seven-day-query-unearned-reward-rejected")
            assert restored["universeRoom"] == snapshots[0]["universeRoom"], "Saved universe room changed after restart"
            checks.append("46-native-universe-resume-leave-quit-after-process-restart")
            checks.append("46-native-universe-abandon-result-counts-roster-no-win-reward")
            checks.append("46-kcp-player-restored-after-process-restart")
            checks.append("46-talk-objective-restored-after-process-restart")
            combat = combat_probe("rogue-combat-probe.log", credentials)
            assert combat["actualRogueVictoryAndRetry"] and combat["fragmentCurrencyNotification"]
            assert 70 <= combat["fragments"] <= 90
            checks.append("46-native-resource-rogue-attack-win-retry-fragment-notify")
            assert combat["nativeEnhancementOutsideRestRoomRejected"]
            assert combat["nativeClassicEnhancementAndOtherModeRejected"]
            assert combat["nativeAvatarDownloadInvalidTargetRejected"]
            assert combat["nativeRogueLineupReplacement"]
            checks.extend(["46-native-enhancement-shop-outside-rest-room-rejected",
                           "46-native-classic-enhancement-field13-other-mode-field9-rejected",
                           "46-native-avatar-download-invalid-target-rejected",
                           "46-native-rogue-lineup-replacement"])
            combat_credentials = dict(credentials, resume=True, expectedFragments=combat["fragments"])
            combat_reconnected = combat_probe("rogue-combat-reconnect.log", combat_credentials)
            assert combat_reconnected["savedCombatWalletRestored"]
            for key in ("uid", "room", "site", "fragments"):
                assert combat_reconnected[key] == combat[key], f"Combat state changed on reconnect: {key}"
            checks.append("46-native-rogue-combat-fragments-restored-after-reconnect")
            trial_active = trial_probe("trial-active-start.log", credentials)
            trial_credentials = dict(credentials, resume=True, expectedHome=trial_active["home"], expectedHomeLineup=trial_active["homeLineup"])
            trial_reconnected = trial_probe("trial-active-reconnect.log", trial_credentials)
            for key in ("uid", "trialScene", "trialActors", "trialLineup"):
                assert trial_active[key] == trial_reconnected[key], f"Active trial changed on reconnect: {key}"
            checks.extend(["46-native-active-trial-frozen-party-hp-sp-snapshot", "46-native-active-trial-restored-after-reconnect"])
            time.sleep(2)  # The same deliberate delayed-H2-commit boundary as the initial checks.
        finally:
            if process.poll() is None:
                process.terminate()
                try: process.wait(timeout=15)
                except subprocess.TimeoutExpired:
                    process.kill()
                    process.wait(timeout=5)
    with (directory / "server-combat-restart.log").open("w", encoding="utf-8") as log:
        process = subprocess.Popen(command, cwd=directory, stdin=subprocess.DEVNULL, stdout=log, stderr=subprocess.STDOUT,
            creationflags=subprocess.CREATE_NO_WINDOW if os.name == "nt" else 0)
        try:
            deadline = time.monotonic() + 90
            while True:
                if process.poll() is not None:
                    raise RuntimeError(f"Combat restart failed; inspect {directory / 'server-combat-restart.log'}")
                try:
                    if json.loads(request(base, "/health/ready"))["ready"]: break
                except (OSError, urllib.error.URLError): pass
                if time.monotonic() > deadline: raise RuntimeError(f"Combat restart timeout; inspect {directory}")
                time.sleep(0.2)
            trial_restored = trial_probe("trial-active-after-restart.log", dict(trial_credentials, leave=True))
            for key in ("uid", "trialScene", "trialActors", "trialLineup"):
                assert trial_active[key] == trial_restored[key], f"Active trial changed after restart: {key}"
            assert trial_restored["originalHomeAndPartyRestored"]
            checks.append("46-native-active-trial-restored-after-process-restart-exact-home-party")
            combat_restored = combat_probe("rogue-combat-after-restart.log", combat_credentials)
            assert combat_restored["savedCombatWalletRestored"]
            for key in ("uid", "room", "site", "fragments"):
                assert combat_restored[key] == combat[key], f"Combat state changed after restart: {key}"
            checks.append("46-native-rogue-combat-fragments-restored-after-process-restart")
        finally:
            if process.poll() is None:
                process.terminate()
                try: process.wait(timeout=15)
                except subprocess.TimeoutExpired:
                    process.kill()
                    process.wait(timeout=5)
    summary = {"passed": True, "checks": checks, "playerSnapshot": restored,
        "rogueCombatSnapshot": combat, "rogueCombatRestoredAfterProcessRestart": combat_restored,
        "playerRestoredAfterProcessRestart": True, "clientGameplayVerified": False, "runtimeDirectory": str(directory),
        "trialWindowFixture": {"panelId": 20003, "moduleId": 2000401, "originalUnlockConditions": original_trial_unlock,
            "sourceResourcesModified": False, "storyGateExercisedByJavaRegressions": True}}
    (ROOT / "build/smoke-4.6-summary.json").write_text(json.dumps(summary, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(summary, indent=2))


if __name__ == "__main__":
    main()
