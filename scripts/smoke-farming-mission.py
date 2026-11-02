"""真实1001花萼和两步隔离任务经合成KCP、重连、H2重启；不是官方剧情或客户端验收。"""
import argparse
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
    parser = argparse.ArgumentParser()
    parser.add_argument('--categories', action='store_true', help='同时验证花萼/隧洞/历战分类及成就和日常')
    parser.add_argument('--native-count', action='store_true', help='使用本机4.6普通field10/快速field6挑战次数，并核对原生回显')
    args = parser.parse_args()
    java = next((ROOT / ".tools").glob("jdk-21*/bin/java.exe"), None)
    java = str(java) if java else "java"
    artifact = ROOT / "build/libs/hkrpg-server.jar"
    runs = ROOT / "build/smoke"
    runs.mkdir(parents=True, exist_ok=True)
    directory = Path(tempfile.mkdtemp(prefix="farming-mission-", dir=runs))
    source_resources = ROOT / "resources/4.6"
    fixture = directory / "resources-fixture"
    excel = fixture / "ExcelOutput"
    excel.mkdir(parents=True)
    original_main = source_resources / "ExcelOutput/MainMission.json"
    guarded = ['MainMission.json', 'CocoonConfig.json']
    if args.categories:
        guarded += ['QuestData.json', 'FinishWay.json', 'DailyActiveQuestPool.json']
    original_sha = {name: hashlib.sha256((source_resources / 'ExcelOutput' / name).read_bytes()).hexdigest()
                    for name in guarded}
    for source in (source_resources / "ExcelOutput").glob("*.json"):
        target = excel / source.name
        if source.name == "MainMission.json":
            rows = json.loads(source.read_text(encoding="utf-8"))
            assert isinstance(rows, list)
            assert not any(row["MainMissionID"] == 19996923 for row in rows)
            rows.append({"MainMissionID": 19996923, "TakeOperation": "And", "TakeParam": [{"Type": "Manual"}],
                         "BeginOperation": "And", "BeginParam": [{"Type": "Auto"}]})
            target.write_text(json.dumps(rows, ensure_ascii=False), encoding="utf-8")
        elif args.categories and source.name in ('QuestData.json', 'FinishWay.json', 'DailyActiveQuestPool.json'):
            rows = json.loads(source.read_text(encoding='utf-8'))
            for category in (1, 2, 3):
                quest_id = 19997050 + category
                if source.name == 'QuestData.json':
                    assert not any(r['QuestID'] == quest_id for r in rows)
                    rows.append({'QuestID': quest_id, 'QuestType': 5, 'UnlockType': 'AutoUnlock',
                                 'FinishWayID': quest_id, 'UnlockParamList': [], 'RewardID': 0})
                elif source.name == 'FinishWay.json':
                    assert not any(r['ID'] == quest_id for r in rows)
                    rows.append({'ID': quest_id, 'FinishType': 'AnyCocoonFinish', 'ParamType': 'ListContain',
                                 'ParamIntList': [category], 'ParamItemList': [], 'Progress': 20})
                else:
                    rows.append({'QuestID': quest_id, 'Type': quest_id})
            target.write_text(json.dumps(rows, ensure_ascii=False), encoding='utf-8')
        else:
            os.link(source, target)

    def connect(target, source):
        target.parent.mkdir(parents=True, exist_ok=True)
        if source.is_file():
            os.link(source, target)
        elif os.name == "nt":
            def literal(path): return "'" + str(path).replace("'", "''") + "'"
            subprocess.run(["powershell", "-NoProfile", "-NonInteractive", "-Command",
                            "New-Item -ItemType Junction -Path " + literal(target) +
                            " -Value " + literal(source) + " | Out-Null"],
                           check=True, capture_output=True, text=True, creationflags=subprocess.CREATE_NO_WINDOW)
        else:
            target.symlink_to(source, target_is_directory=True)

    for source in (source_resources / "Config").iterdir():
        if source.name != "Level": connect(fixture / "Config" / source.name, source)
    for source in (source_resources / "Config/Level").iterdir():
        if source.name != "Mission": connect(fixture / "Config/Level" / source.name, source)
    mission_root = fixture / "Config/Level/Mission/19996923"
    mission_root.mkdir(parents=True)
    (mission_root / "MissionInfo_19996923.json").write_text(json.dumps({"MainMissionID": 19996923,
        "FinishSubMissionList": [199969232], "SubMissionList": [
            {"ID": 199969231, "MainMissionID": 19996923, "TakeType": "Auto", "FinishType": "CocoonFinish",
             "ParamType": "ListContain", "ParamIntList": [1001], "Progress": 1},
            {"ID": 199969232, "MainMissionID": 19996923, "TakeType": "MultiSequence",
             "TakeParamIntList": [199969231], "FinishType": "CocoonFinish", "ParamType": "ListContain",
             "ParamIntList": [1001], "Progress": 1}]}), encoding="utf-8")
    if args.categories:
        path = mission_root / 'MissionInfo_19996923.json'
        data = json.loads(path.read_text(encoding='utf-8'))
        for category in (1, 2, 3):
            sub_id = 199969232 + category
            data['FinishSubMissionList'].append(sub_id)
            data['SubMissionList'].append({'ID': sub_id, 'MainMissionID': 19996923, 'TakeType': 'Auto',
                'FinishType': 'AnyCocoonFinish', 'ParamType': 'ListContain', 'ParamIntList': [category], 'Progress': 1})
        path.write_text(json.dumps(data), encoding='utf-8')
    (fixture / "resource-manifest.json").write_text(json.dumps({"clientVersion": "4.6.0", "testFixture": True,
        "onlyMissionFixture": 19996923, "realCocoonResource": 1001}), encoding="utf-8")
    http_port, mongo_port, game_port = free_port(), free_port(), free_port(socket.SOCK_DGRAM)
    (directory / "config.json").write_text(json.dumps({"protocol": {"wireVersion": "4.6.0"}, "resourceDir": str(fixture),
        "httpServer": {"bindAddress": "127.0.0.1", "bindPort": http_port},
        "gameServer": {"bindAddress": "127.0.0.1", "bindPort": game_port},
        "internalMongoServer": {"address": "127.0.0.1", "port": mongo_port, "filePath": "./data/database.mv"}}), encoding="utf-8")
    command = [java, "-Xmx1536m", f"-Djdk.net.unixdomain.tmpdir={directory / 'tcp-fallback'}",
               "-jar", str(artifact), "--no-console", "--no-handbook"]
    base = f"http://127.0.0.1:{http_port}"

    def probe(log_name, credentials):
        execution = subprocess.run([java, command[2], "-cp", str(artifact),
            "org.hkrpg.server.protocol.LoopbackFarmingMissionProbe", str(game_port)], input=json.dumps(credentials),
            cwd=directory, capture_output=True, text=True, encoding="utf-8", errors="replace", timeout=30,
            creationflags=subprocess.CREATE_NO_WINDOW if os.name == "nt" else 0)
        (directory / log_name).write_text(execution.stdout + execution.stderr, encoding="utf-8")
        if execution.returncode: raise RuntimeError(f"Farming mission failed; inspect {directory / log_name}")
        result = json.loads(next(line for line in execution.stdout.splitlines() if line.startswith('{"uid":')))
        assert result["passed"] and not result["clientGameplayVerified"]
        return result

    snapshots, credentials = [], None
    for iteration in range(2):
        with (directory / f"server-{iteration}.log").open("w", encoding="utf-8") as log:
            process = subprocess.Popen(command, cwd=directory, stdin=subprocess.DEVNULL, stdout=log, stderr=subprocess.STDOUT,
                                       creationflags=subprocess.CREATE_NO_WINDOW if os.name == "nt" else 0)
            try:
                deadline = time.monotonic() + 90
                while True:
                    if process.poll() is not None: raise RuntimeError(f"Farming server failed; inspect {directory}")
                    try:
                        if request(base, "/health/ready")["ready"]: break
                    except (OSError, urllib.error.URLError): pass
                    if time.monotonic() > deadline: raise RuntimeError(f"Farming server timeout; inspect {directory}")
                    time.sleep(.2)
                if iteration == 0:
                    login = request(base, "/hkrpg_cn/mdk/shield/api/login", {"account": "cn-46-farming", "password": "local-test"})
                    assert login["retcode"] == 0
                    account = login["data"]["account"]
                    combo = request(base, "/hkrpg_cn/combo/granter/login/v2/login", {"data": json.dumps({"uid": account["uid"], "token": account["token"]})})
                    assert combo["retcode"] == 0
                    credentials = {"accountUid": account["uid"], "comboToken": combo["data"]["combo_token"],
                                   'categories': args.categories, 'nativeCount': args.native_count}
                    first = probe("farming-first.log", credentials)
                    assert first["threeWaveBattleAndStamina"] and first["acceptedMissionFinishedAfterTwoBattles"]
                    assert first["newObjectiveWaitsForAnotherBattle"] and first["repeatedBattleResultRejected"]
                    assert first['nativeChallengeCount'] == args.native_count
                    if args.categories:
                        assert first['threeCategoriesStaySeparate'] and first['actualCategoryAchievementsAndDailies']
                    snapshots.append(first)
                    credentials.update(resume=True)
                restored = probe(f"farming-resume-{iteration}.log", credentials)
                assert restored["savedMissionRestored"] and restored["finishedMissionRetryRejected"]
                assert restored["uid"] == first["uid"]
                snapshots.append(restored)
                time.sleep(2)
            finally:
                if process.poll() is None:
                    process.terminate()
                    try: process.wait(timeout=15)
                    except subprocess.TimeoutExpired:
                        process.kill()
                        process.wait(timeout=5)
    for name, digest in original_sha.items():
        assert hashlib.sha256((source_resources / 'ExcelOutput' / name).read_bytes()).hexdigest() == digest
    checks = ["actual-cocoon-native-three-waves-and-cost",
        "wrong-identity-and-repeated-victory-rejected", "new-objective-waits-for-second-actual-battle",
        "completed-mission-restored-on-reconnect", "completed-mission-restored-after-h2-process-restart"]
    if args.categories:
        checks += ['normal-relic-weekly-native-battles-and-category-goals',
                   'real-4060201-4060301-4060401-achievement-counters',
                   'isolated-daily-category-counters-and-persistence']
    print(json.dumps({"passed": True, "runtimeDirectory": str(directory), "checks": checks,
        "artifactSha256": hashlib.sha256(artifact.read_bytes()).hexdigest(),
        "nativeChallengeCount": args.native_count,
        "snapshots": snapshots, "fixture": {"onlyMission": 19996923, "cocoon": 1001,
        'categories': args.categories, 'sourceSha256': original_sha,
        "sourceResourcesModified": False, "officialStoryMissionVerified": False}, "clientGameplayVerified": False}, ensure_ascii=False))


if __name__ == "__main__":
    main()
