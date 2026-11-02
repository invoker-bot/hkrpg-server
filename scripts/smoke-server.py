"""Exercise the packaged Dispatch service and account persistence without a game client."""
import argparse
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

def free_port():
    with socket.socket() as sock:
        sock.bind(("127.0.0.1", 0))
        return sock.getsockname()[1]

def request(base, route, body=None):
    data = None if body is None else json.dumps(body).encode()
    req = urllib.request.Request(base + route, data=data, headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=5) as response:
        return json.load(response)

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--java")
    parser.add_argument("--jar", type=Path, default=ROOT / "build/libs/hkrpg-server.jar")
    args = parser.parse_args()
    local_java = next((ROOT / ".tools").glob("jdk-21*/bin/java.exe"), None)
    java = args.java or (str(local_java) if local_java else "java")
    artifact = args.jar.resolve()
    if not artifact.is_file(): raise RuntimeError("Build the server first")
    runs = ROOT / "build/smoke"
    runs.mkdir(parents=True, exist_ok=True)
    directory = Path(tempfile.mkdtemp(prefix="dispatch-", dir=runs))
    assert directory.resolve().is_relative_to(runs.resolve())
    port = free_port()
    mongo_port = free_port()
    (directory / "config.json").write_text(json.dumps({
        "httpServer": {"bindAddress": "127.0.0.1", "bindPort": port},
        "internalMongoServer": {"address": "127.0.0.1", "port": mongo_port, "filePath": "./data/database.mv"}
    }), encoding="utf-8")
    base = f"http://127.0.0.1:{port}"
    checks = []
    first_uid = None
    for iteration in range(2):
        with (directory / f"server-{iteration}.log").open("w", encoding="utf-8") as log:
            process = subprocess.Popen([java, f"-Djdk.net.unixdomain.tmpdir={directory / 'tcp-fallback'}", "-jar", str(artifact), "--mode=dispatch", "--no-console"], cwd=directory, stdin=subprocess.DEVNULL, stdout=log, stderr=subprocess.STDOUT, creationflags=subprocess.CREATE_NO_WINDOW if os.name == "nt" else 0)
            try:
                deadline = time.monotonic() + 45
                while True:
                    if process.poll() is not None: raise RuntimeError(f"Server exited early; inspect {directory}")
                    try:
                        if request(base, "/health/ready")["ready"]: break
                    except (OSError, urllib.error.URLError): pass
                    if time.monotonic() > deadline: raise RuntimeError(f"Startup timed out; inspect {directory}")
                    time.sleep(0.2)
                assert request(base, "/health/live")["status"] == "UP"
                assert request(base, "/status/server")["status"]["playerCount"] == 0
                assert request(base, "/api/features")["protocolBaseline"] == "4.2.0"
                login = request(base, "/hkrpg_global/mdk/shield/api/login", {"account": "smoke-account", "password": "local-test"})
                assert login["retcode"] == 0
                account = login["data"]["account"]
                if first_uid is None: first_uid = account["uid"]
                else: assert account["uid"] == first_uid, "Account changed after restart"
                verified = request(base, "/hkrpg_global/mdk/shield/api/verify", {"uid": account["uid"], "token": account["token"]})
                assert verified["retcode"] == 0
                invalid = request(base, "/hkrpg_global/mdk/shield/api/verify", {"uid": account["uid"], "token": "wrong"})
                assert invalid["retcode"] != 0
                combo = request(base, "/hkrpg_global/combo/granter/login/v2/login", {"data": json.dumps({"uid": account["uid"], "token": account["token"]})})
                assert combo["retcode"] == 0 and combo["data"]["combo_token"]
                for route in ["/hkrpg_global/mdk/shield/api/login", "/hkrpg_global/mdk/shield/api/verify", "/hkrpg_global/combo/granter/login/v2/login"]:
                    assert request(base, route, {})["retcode"] == -202
                checks.append(f"dispatch-pass-{iteration + 1}")
                # H2 has a delayed disk commit; give the store time to flush before a process-level restart.
                time.sleep(2)
            finally:
                if process.poll() is None:
                    process.terminate()
                    try: process.wait(timeout=15)
                    except subprocess.TimeoutExpired:
                        process.kill()
                        process.wait(timeout=5)
    summary = {"passed": True, "checks": checks, "accountRestoredAfterProcessRestart": True, "clientGameplayVerified": False, "runtimeDirectory": str(directory)}
    (ROOT / "build/smoke-summary.json").write_text(json.dumps(summary, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(summary, indent=2))

if __name__ == "__main__":
    main()
