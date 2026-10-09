"""真实音律关卡/奖励/库存的合成KCP与H2重启；只在隔离资源副本放开活动及组1前置。"""
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
    directory = Path(tempfile.mkdtemp(prefix="music-rewards-", dir=runs))
    source_resources = ROOT / "resources/4.6"
    fixture = directory / "resources-fixture"
    excel = fixture / "ExcelOutput"
    excel.mkdir(parents=True)
    modified = {"ActivityPanel.json", "MusicRhythmGroup.json", "RewardData.json"}
    source_hashes = {n: hashlib.sha256((source_resources / "ExcelOutput" / n).read_bytes()).hexdigest()
                     for n in modified}
    original_unlock = None
    for source in (source_resources / "ExcelOutput").glob("*.json"):
        target = excel / source.name
        if source.name not in modified:
            os.link(source, target)
            continue
        data = json.loads(source.read_text(encoding="utf-8"))
        rows = list(data.values()) if isinstance(data, dict) else data
        if source.name == "ActivityPanel.json":
            panel = next(x for x in rows if x["PanelID"] == 50022)
            original_unlock = panel["UnlockConditions"]
            panel["UnlockConditions"] = ""
        elif source.name == "MusicRhythmGroup.json":
            group = next(x for x in rows if x["ID"] == 1)
            assert group["UnlockSubMissionID"] == 802611006
            group["UnlockSubMissionID"] = 0
        target.write_text(json.dumps(data, ensure_ascii=False), encoding="utf-8")
    if os.name == "nt":
        def literal(path):
            return "'" + str(path).replace("'", "''") + "'"
        subprocess.run(["powershell", "-NoProfile", "-NonInteractive", "-Command",
                        "New-Item -ItemType Junction -Path " + literal(fixture / "Config") +
                        " -Value " + literal(source_resources / "Config") + " | Out-Null"],
                       check=True, capture_output=True, text=True, creationflags=subprocess.CREATE_NO_WINDOW)
    else:
        (fixture / "Config").symlink_to(source_resources / "Config", target_is_directory=True)
    (fixture / "resource-manifest.json").write_text(json.dumps({"clientVersion": "4.6.0", "testFixture": True,
        "actualLevels": [101, 102], "openedPanel": 50022, "originalPanelUnlock": original_unlock,
        "openedGroup": 1, "originalGroupSubMission": 802611006}), encoding="utf-8")
    (directory / "data").mkdir()
    (directory / "data/Gameplay46.json").write_text(json.dumps({"activities": [
        {"panelId": 50022, "moduleId": 0, "beginTime": 0, "endTime": int(time.time()) + 86400}]}),
        encoding="utf-8")
    http_port, mongo_port, game_port = free_port(), free_port(), free_port(socket.SOCK_DGRAM)
    (directory / "config.json").write_text(json.dumps({"protocol": {"wireVersion": "4.6.0"}, "resourceDir": str(fixture),
        "httpServer": {"bindAddress": "127.0.0.1", "bindPort": http_port},
        "gameServer": {"bindAddress": "127.0.0.1", "bindPort": game_port},
        "internalMongoServer": {"address": "127.0.0.1", "port": mongo_port, "filePath": "./data/database.mv"}}),
        encoding="utf-8")
    command = [java, "-Xmx1536m", f"-Djdk.net.unixdomain.tmpdir={directory / 'tcp-fallback'}",
               "-jar", str(artifact), "--no-console", "--no-handbook"]
    base = f"http://127.0.0.1:{http_port}"

    def probe(name, credentials):
        execution = subprocess.run([java, command[2], "-cp", str(artifact),
            "org.hkrpg.server.protocol.LoopbackMusicRewardProbe", str(game_port)], input=json.dumps(credentials),
            cwd=directory, capture_output=True, text=True, encoding="utf-8", errors="replace", timeout=30,
            creationflags=subprocess.CREATE_NO_WINDOW if os.name == "nt" else 0)
        (directory / name).write_text(execution.stdout + execution.stderr, encoding="utf-8")
        if execution.returncode:
            raise RuntimeError(f"Music reward probe failed; inspect {directory / name}")
        result = json.loads(next(line for line in execution.stdout.splitlines() if line.startswith('{"uid":')))
        assert result["passed"] and not result["clientGameplayVerified"]
        return result

    snapshots, credentials = [], None
    for iteration in range(2):
        if iteration:
            p = excel / "RewardData.json"
            data = json.loads(p.read_text(encoding="utf-8"))
            rows = list(data.values()) if isinstance(data, dict) else data
            changed = [x for x in rows if x["RewardID"] in [261004, 261005, 261006]]
            assert len(changed) == 3 and all(x["ItemID_1"] == 270003 and x["Count_1"] == 1 for x in changed)
            for row in changed:
                row["Count_1"] = 999
            p.write_text(json.dumps(data, ensure_ascii=False), encoding="utf-8")
        with (directory / f"server-{iteration}.log").open("w", encoding="utf-8") as log:
            process = subprocess.Popen(command, cwd=directory, stdin=subprocess.DEVNULL, stdout=log,
                stderr=subprocess.STDOUT, creationflags=subprocess.CREATE_NO_WINDOW if os.name == "nt" else 0)
            try:
                deadline = time.monotonic() + 90
                while True:
                    if process.poll() is not None:
                        raise RuntimeError(f"Music server exited; inspect {directory}")
                    try:
                        if request(base, "/health/ready")["ready"]:
                            break
                    except (OSError, urllib.error.URLError):
                        pass
                    if time.monotonic() > deadline:
                        raise RuntimeError(f"Music server timeout; inspect {directory}")
                    time.sleep(.2)
                if iteration == 0:
                    login = request(base, "/hkrpg_cn/mdk/shield/api/login", {"account": "cn-46-music", "password": "local-test"})
                    assert login["retcode"] == 0
                    account = login["data"]["account"]
                    combo = request(base, "/hkrpg_cn/combo/granter/login/v2/login", {"data": json.dumps({"uid": account["uid"], "token": account["token"]})})
                    assert combo["retcode"] == 0
                    credentials = {"accountUid": account["uid"], "comboToken": combo["data"]["combo_token"], "mode": "fresh"}
                    first = probe("music-first.log", credentials)
                    assert first["materialCount"] == 1 and first["activeLevel"] == 101
                    snapshots.append(first)
                    credentials["mode"] = "resume"
                    resumed = probe("music-resume.log", credentials)
                    assert resumed["uid"] == first["uid"] and resumed["materialCount"] == 2 and resumed["activeLevel"] == 102
                    snapshots.append(resumed)
                else:
                    credentials["mode"] = "restart"
                    restored = probe("music-restart.log", credentials)
                    assert restored["uid"] == first["uid"] and restored["materialCount"] == 5 and restored["activeLevel"] == 0
                    snapshots.append(restored)
                time.sleep(2)
            finally:
                if process.poll() is None:
                    process.terminate()
                    try:
                        process.wait(timeout=15)
                    except subprocess.TimeoutExpired:
                        process.kill()
                        process.wait(timeout=5)
    assert all(hashlib.sha256((source_resources / "ExcelOutput" / n).read_bytes()).hexdigest() == sha
               for n, sha in source_hashes.items())
    print(json.dumps({"passed": True, "runtimeDirectory": str(directory), "checks": [
        "actual-resource-level-rating-and-first-material", "duplicate-settlement-rejected-without-extra-material",
        "unfinished-run-restored-on-reconnect-with-only-new-star-reward", "unfinished-run-and-frozen-reward-quantities-restored-after-h2-restart",
        "best-rating-and-full-combo-persisted", "replayed-completed-level-does-not-regrant-rewards"],
        "snapshots": snapshots, "fixture": {"actualLevels": [101, 102], "actualRewardMaterial": 270003,
        "sourceResourcesModified": False, "openedPanel": 50022, "openedGroup": 1,
        "restartRewardQuantity": 999, "expectedFrozenQuantity": 1, "officialStoryUnlockVerified": False},
        "clientGameplayVerified": False}, ensure_ascii=False))


if __name__ == "__main__":
    main()
