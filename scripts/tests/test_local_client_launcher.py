"""启动前恢复被改名的调试 DLL；所有文件和进程均为隔离测试替身。"""
import hashlib
import importlib.util
import json
import http.server
import os
import shutil
import subprocess
import sys
import threading
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location('local_client_launcher', ROOT / 'scripts/start-local-client.py')
launcher = importlib.util.module_from_spec(spec)
spec.loader.exec_module(launcher)


class LocalClientLauncherTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        self.client = self.root / '客户端 with spaces'
        self.client.mkdir()
        self.files = {}
        for relative in launcher.REQUIRED_FILES:
            target = self.client / relative
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(relative.encode('utf8'))
            self.files[relative] = launcher.sha(target)
        self.backup = self.root / 'version.dll'
        self.backup.write_bytes(b'verified console')
        self.manifest = self.root / 'patch-manifest.json'
        self.data = {'schema': 1, 'clientRoot': str(self.client), 'clientFiles': self.files,
                     'debugConsole': {'sha256': launcher.sha(self.backup), 'backupPath': 'version.dll'},
                     'service': {'readyUrl': 'http://127.0.0.1:18080/health/ready',
                                 'startCommand': 'docker compose up -d --wait'}}
        self.write_manifest()
        self.process = patch.object(launcher, 'client_running', return_value=False).start()
        self.ready = patch.object(launcher, 'require_ready').start()
        self.spawn = patch.object(launcher, 'launch_client', return_value=1234).start()
        self.addCleanup(patch.stopall)

    def write_manifest(self):
        self.manifest.write_text(json.dumps(self.data), encoding='utf8')

    def test_restore_from_stable_backup_before_detached_launch(self):
        result = launcher.start(self.manifest)
        self.assertEqual(self.backup.read_bytes(), (self.client / 'version.dll').read_bytes())
        self.assertEqual('restored', result['console'])
        self.spawn.assert_called_once_with(self.client.resolve())
        self.assertEqual(1234, result['pid'])

    def test_repair_only_restores_console_without_starting_game(self):
        result = launcher.start(self.manifest, repair_only=True)
        self.assertTrue(result['repairOnly'])
        self.assertEqual('restored', result['console'])
        self.assertEqual(self.backup.read_bytes(), (self.client / 'version.dll').read_bytes())
        self.spawn.assert_not_called()

    def test_second_launch_repairs_numeric_rename_without_touching_suffix(self):
        launcher.start(self.manifest)
        suffix = self.client / 'version.dll.4294967295'
        (self.client / 'version.dll').rename(suffix)
        self.backup.unlink()
        launcher.start(self.manifest)
        self.assertEqual(b'verified console', suffix.read_bytes())
        self.assertEqual(suffix.read_bytes(), (self.client / 'version.dll').read_bytes())

    def test_dry_run_reports_repair_and_changes_nothing(self):
        before = {p.relative_to(self.root): p.read_bytes() for p in self.root.rglob('*') if p.is_file()}
        result = launcher.start(self.manifest, dry_run=True)
        self.assertEqual('would-restore', result['console'])
        self.assertEqual(before, {p.relative_to(self.root): p.read_bytes() for p in self.root.rglob('*') if p.is_file()})
        self.spawn.assert_not_called()

    def test_foreign_dll_is_never_overwritten_or_launched(self):
        target = self.client / 'version.dll'
        target.write_bytes(b'not ours')
        with self.assertRaisesRegex(ValueError, 'version.dll'):
            launcher.start(self.manifest)
        self.assertEqual(b'not ours', target.read_bytes())
        self.spawn.assert_not_called()

    def test_existing_valid_dll_does_not_require_backup(self):
        (self.client / 'version.dll').write_bytes(self.backup.read_bytes())
        self.backup.unlink()
        self.assertEqual('verified', launcher.start(self.manifest)['console'])

    def test_changed_sdk_is_rejected_before_repair(self):
        path = self.client / 'StarRail_Data/Plugins/x86_64/AccountPlatNative.dll'
        path.write_bytes(b'updated client')
        with self.assertRaisesRegex(ValueError, 'AccountPlatNative'):
            launcher.start(self.manifest)
        self.assertEqual(b'updated client', path.read_bytes())
        self.assertFalse((self.client / 'version.dll').exists())
        self.spawn.assert_not_called()

    def test_running_game_is_never_modified(self):
        self.process.return_value = True
        with self.assertRaisesRegex(RuntimeError, '运行'):
            launcher.start(self.manifest)
        self.assertFalse((self.client / 'version.dll').exists())
        self.spawn.assert_not_called()

    def test_game_started_during_checks_prevents_publish(self):
        self.process.side_effect = [False, True]
        with self.assertRaisesRegex(RuntimeError, '运行'):
            launcher.start(self.manifest)
        self.assertFalse((self.client / 'version.dll').exists())
        self.assertFalse(list(self.client.glob('.hkrpg-version-*')))
        self.spawn.assert_not_called()

    def test_offline_service_does_not_repair_or_launch(self):
        self.ready.side_effect = RuntimeError('服务未就绪')
        with self.assertRaisesRegex(RuntimeError, '服务'):
            launcher.start(self.manifest)
        self.assertFalse((self.client / 'version.dll').exists())
        self.spawn.assert_not_called()

    def test_unknown_suffix_and_corrupt_backup_do_not_become_executable(self):
        self.backup.write_bytes(b'corrupt')
        (self.client / 'version.dll.123').write_bytes(b'foreign')
        with self.assertRaisesRegex(ValueError, '恢复来源'):
            launcher.start(self.manifest)
        self.assertFalse((self.client / 'version.dll').exists())
        self.spawn.assert_not_called()

    def test_manifest_cannot_omit_core_or_escape_client_root(self):
        del self.data['clientFiles']['GameAssembly.dll']
        self.data['clientFiles']['../outside'] = hashlib.sha256(b'').hexdigest()
        self.write_manifest()
        with self.assertRaisesRegex(ValueError, '清单'):
            launcher.start(self.manifest)
        self.spawn.assert_not_called()

    def test_atomic_publish_never_replaces_file_created_concurrently(self):
        real_link = launcher.os.link
        def competing_link(source, target):
            Path(target).write_bytes(b'created concurrently')
            return real_link(source, target)
        with patch.object(launcher.os, 'link', side_effect=competing_link):
            with self.assertRaises(FileExistsError):
                launcher.start(self.manifest)
        self.assertEqual(b'created concurrently', (self.client / 'version.dll').read_bytes())
        self.assertFalse(list(self.client.glob('.hkrpg-version-*')))
        self.spawn.assert_not_called()

    def test_ready_response_must_be_explicit_true(self):
        for body in (b'{"ready": false}', b'{"ready": "true"}', b'{}', b'[]', b'not-json'):
            with self.subTest(body=body), self.assertRaises(ValueError):
                launcher.validate_ready(body)
        launcher.validate_ready(b'{"ready": true}')

    @unittest.skipUnless(os.name == 'nt', 'Windows shortcut integration')
    def test_shortcut_installation_backs_up_original_and_is_idempotent(self):
        self.exercise_shortcut_installation(False)

    @unittest.skipUnless(os.name == 'nt', 'Windows default manifest integration')
    def test_shortcut_uses_default_manifest_when_no_manifest_argument_is_supplied(self):
        self.exercise_shortcut_installation(True)

    def exercise_shortcut_installation(self, use_default_manifest):
        # The wrapper is copied beside the real Python helper, with a fake client
        # containing only text fixtures. InstallShortcut always invokes --dry-run.
        scripts = self.root / 'scripts'
        scripts.mkdir()
        for name in ('start-local-client.ps1', 'start-local-client.py'):
            shutil.copy2(ROOT / 'scripts' / name, scripts / name)
        shortcut = self.root / 'StarRail.exe.lnk'
        quote = lambda value: "'" + str(value).replace("'", "''") + "'"
        creator = "$s=(New-Object -ComObject WScript.Shell).CreateShortcut(" + quote(shortcut) + ");" + \
            "$s.TargetPath=" + quote(self.client / 'StarRail.exe') + ";$s.Save()"
        subprocess.run(['powershell.exe', '-NoProfile', '-NonInteractive', '-Command', creator],
                       check=True, capture_output=True, creationflags=subprocess.CREATE_NO_WINDOW)
        original = shortcut.read_bytes()
        if use_default_manifest:
            local = self.root / '.local/client'
            local.mkdir(parents=True)
            shutil.copy2(self.backup, local / 'version.dll')
            self.manifest = local / 'patch-manifest.json'
        class Handler(http.server.BaseHTTPRequestHandler):
            def do_GET(self):
                self.send_response(200)
                self.end_headers()
                self.wfile.write(b'{"ready":true}')
            def log_message(self, *args):
                pass
        server = http.server.ThreadingHTTPServer(('127.0.0.1', 0), Handler)
        worker = threading.Thread(target=server.serve_forever, daemon=True)
        worker.start()
        try:
            self.data['service']['readyUrl'] = f'http://127.0.0.1:{server.server_port}/health/ready'
            self.write_manifest()
            command = ['powershell.exe', '-NoProfile', '-NonInteractive', '-ExecutionPolicy', 'Bypass',
                       '-File', str(scripts / 'start-local-client.ps1'),
                       '-PythonExecutable', sys.executable, '-InstallShortcut', '-ShortcutPath', str(shortcut)]
            if not use_default_manifest:
                command += ['-Manifest', str(self.manifest)]
            preview = subprocess.run(command + ['-DryRun'], capture_output=True,
                                     creationflags=subprocess.CREATE_NO_WINDOW)
            self.assertEqual(0, preview.returncode, preview.stderr)
            self.assertEqual(original, shortcut.read_bytes())
            self.assertFalse((self.manifest.parent / 'original-StarRail.exe.lnk').exists())
            for attempt in range(2):
                result = subprocess.run(command, capture_output=True,
                                        creationflags=subprocess.CREATE_NO_WINDOW)
                self.assertEqual(0, result.returncode, result.stderr)
                self.assertEqual(original, (self.manifest.parent / 'original-StarRail.exe.lnk').read_bytes())
            self.assertFalse((self.client / 'version.dll').exists(), 'Shortcut installation must not patch or launch')
        finally:
            server.shutdown()
            server.server_close()
            worker.join(timeout=5)


if __name__ == '__main__':
    unittest.main()
