"""真实4.6折纸关卡、原生字段、奖励库存与H2正常重启；隔离放开活动和关卡前置，不修改运营资源。"""
import hashlib
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
    req = urllib.request.Request(base + route, data=None if body is None else json.dumps(body).encode(),
                                 headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=5) as response:
        return json.load(response)


def main():
    local_java = next((ROOT / ".tools").glob("jdk-21*/bin/java.exe"), None)
    java = str(local_java) if local_java else "java"
    artifact = ROOT / "build/libs/hkrpg-server.jar"
    runs = ROOT / "build/smoke"
    runs.mkdir(parents=True, exist_ok=True)
    directory = Path(tempfile.mkdtemp(prefix="match-three-", dir=runs))
    source_resources = ROOT / "resources/4.6"
    fixture = directory / "resources-fixture"
    excel = fixture / "ExcelOutput"
    excel.mkdir(parents=True)
    names = {"ActivityPanel.json", "MatchThreeLevel.json", "MatchThreeConstValueClient.json", "RewardData.json"}
    hashes = {n: hashlib.sha256((source_resources / "ExcelOutput" / n).read_bytes()).hexdigest() for n in names}
    original = {}
    for source in (source_resources / "ExcelOutput").glob("*.json"):
        target = excel / source.name
        if source.name not in names:
            os.link(source, target)
            continue
        data = json.loads(source.read_text(encoding="utf-8"))
        rows = list(data.values()) if isinstance(data, dict) else data
        if source.name == "ActivityPanel.json":
            row = next(r for r in rows if r["PanelID"] == 50014)
            original["panelUnlock"] = row["UnlockConditions"]
            row["UnlockConditions"] = ""
        elif source.name == "MatchThreeLevel.json":
            levels = [r for r in rows if r["LevelID"] == 1100 and r.get("Mode") in [1, 2]]
            assert len(levels) == 2 and all(r["PlayerBirdID"] == 300 for r in levels)
            original["levelUnlocks"] = {r["Mode"]: r["UnlockID"] for r in levels}
            for row in levels:
                row["UnlockID"] = 0
        elif source.name == "MatchThreeConstValueClient.json":
            row = next(r for r in rows if r["ConstValueName"] == "MatchThree_MainPageUnlock_SubMissionID")
            original["mainPageUnlock"] = row["Value"]["IntValue"]
            row["Value"]["IntValue"] = 0
        target.write_text(json.dumps(data, ensure_ascii=False), encoding="utf-8")
    if os.name == "nt":
        def literal(p):
            return "'" + str(p).replace("'", "''") + "'"
        subprocess.run(["powershell", "-NoProfile", "-NonInteractive", "-Command",
                        "New-Item -ItemType Junction -Path " + literal(fixture / "Config") + " -Value " + literal(source_resources / "Config") + " | Out-Null"],
                       check=True, capture_output=True, text=True, creationflags=subprocess.CREATE_NO_WINDOW)
    else:
        (fixture / "Config").symlink_to(source_resources / "Config", target_is_directory=True)
    (fixture / "resource-manifest.json").write_text(json.dumps({"clientVersion": "4.6.0", "testFixture": True, "openedPanel": 50014, "originalGates": original}), encoding="utf-8")
    (directory / "data").mkdir()
    (directory / "data/Gameplay46.json").write_text(json.dumps({"activities": [{"panelId": 50014, "moduleId": 5001400, "beginTime": 0, "endTime": int(time.time()) + 86400}]}), encoding="utf-8")
    http_port, mongo_port, game_port = free_port(), free_port(), free_port(socket.SOCK_DGRAM)
    (directory / "config.json").write_text(json.dumps({"protocol": {"wireVersion": "4.6.0"}, "resourceDir": str(fixture),
        "httpServer": {"bindAddress": "127.0.0.1", "bindPort": http_port}, "gameServer": {"bindAddress": "127.0.0.1", "bindPort": game_port},
        "internalMongoServer": {"address": "127.0.0.1", "port": mongo_port, "filePath": "./data/database.mv"}}), encoding="utf-8")
    command = [java, "-Xmx1536m", f"-Djdk.net.unixdomain.tmpdir={directory / 'tcp-fallback'}", "-jar", str(artifact), "--no-console", "--no-handbook"]
    base = f"http://127.0.0.1:{http_port}"

    def probe(name, credentials):
        run = subprocess.run([java, command[2], "-cp", str(artifact), "org.hkrpg.server.protocol.LoopbackMatchThreeProbe", str(game_port)],
            input=json.dumps(credentials), cwd=directory, capture_output=True, text=True, encoding="utf-8", errors="replace", timeout=30,
            creationflags=subprocess.CREATE_NO_WINDOW if os.name == "nt" else 0)
        (directory / name).write_text(run.stdout + run.stderr, encoding="utf-8")
        if run.returncode:
            raise RuntimeError(f"Classic probe failed; inspect {directory / name}")
        result = json.loads(next(line for line in run.stdout.splitlines() if line.startswith('{"uid":')))
        assert result["passed"]
        return result

    snapshots = []
    for iteration in range(2):
        if iteration == 1:
            p = excel / "RewardData.json"
            data = json.loads(p.read_text(encoding="utf-8"))
            rows = list(data.values()) if isinstance(data, dict) else data
            for row in [r for r in rows if r["RewardID"] in [230001, 230011]]:
                row["Count_2"] = 999
            p.write_text(json.dumps(data, ensure_ascii=False), encoding="utf-8")
        with (directory / f"server-{iteration}.log").open("w", encoding="utf-8") as log:
            process = subprocess.Popen(command, cwd=directory, stdin=subprocess.DEVNULL, stdout=log, stderr=subprocess.STDOUT,
                creationflags=subprocess.CREATE_NO_WINDOW if os.name == "nt" else 0)
            try:
                deadline = time.monotonic() + 90
                while True:
                    if process.poll() is not None:
                        raise RuntimeError(f"Classic server exited; inspect {directory}")
                    try:
                        if request(base, "/health/ready")["ready"]:
                            break
                    except (OSError, urllib.error.URLError):
                        pass
                    if time.monotonic() > deadline:
                        raise RuntimeError(f"Classic startup timeout; inspect {directory}")
                    time.sleep(.2)
                if iteration == 0:
                    login = request(base, "/hkrpg_cn/mdk/shield/api/login", {"account": "cn-46-classic", "password": "local-test"})
                    assert login["retcode"] == 0
                    account = login["data"]["account"]
                    combo = request(base, "/hkrpg_cn/combo/granter/login/v2/login", {"data": json.dumps({"uid": account["uid"], "token": account["token"]})})
                    assert combo["retcode"] == 0
                    credentials = {"accountUid": account["uid"], "comboToken": combo["data"]["combo_token"], "mode": "fresh"}
                    first = probe("classic-fresh.log", credentials)
                    snapshots.append(first)
                    credentials.update(mode="resume", baselineItems=first["baselineItems"])
                    snapshots.append(probe("classic-resume.log", credentials))
                else:
                    credentials["mode"] = "restart"
                    snapshots.append(probe("classic-restart.log", credentials))
                time.sleep(2)
            finally:
                if process.poll() is None:
                    process.terminate()
                    try:
                        process.wait(timeout=15)
                    except subprocess.TimeoutExpired:
                        process.kill()
                        process.wait(timeout=5)
    for r in snapshots:
        for key in ["uid", "items", "finishedModes", "birdUseCount", "birdMaximum", "bird301Position", "statistics"]:
            assert r[key] == first[key], f"Persisted classic state changed: {key}"
    assert all(hashlib.sha256((source_resources / "ExcelOutput" / n).read_bytes()).hexdigest() == sha for n, sha in hashes.items())
    print(json.dumps({"passed": True, "runtimeDirectory": str(directory), "checks": ["actual-native-query-roster-and-mode-separated-results", "actual-native-reward-items-and-saved-statistics", "idempotent-and-conflicting-result-retries", "earned-bird-placement-notified-and-occupied-nest-rejected", "state-and-reward-receipts-restored-on-reconnect", "state-and-reward-receipts-restored-after-h2-normal-restart-and-resource-change"],
        "snapshots": snapshots, "fixture": {"openedPanel": 50014, "actualLevel": 1100, "actualModes": [1, 2], "actualRewards": [230001, 230011], "originalGates": original, "sourceResourceHashes": hashes, "sourceResourcesModified": False, "officialStoryUnlockVerified": False}, "clientGameplayVerified": False}, ensure_ascii=False))


if __name__ == "__main__":
    main()
