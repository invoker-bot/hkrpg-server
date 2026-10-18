"""独立客户端日志启动及旧调试 DLL 撤回；使用隔离文件和进程。"""
import hashlib
import ctypes
import importlib.util
import json
import http.server
import os
import shutil
import subprocess
import sys
import threading
import time
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
        self.data = {'schema': 2, 'clientRoot': str(self.client), 'clientFiles': self.files,
                     'debugConsole': {'mode': 'external', 'sha256': launcher.sha(self.backup), 'backupPath': 'version.dll'},
                     'service': {'readyUrl': 'http://127.0.0.1:18080/health/ready',
                                 'startCommand': 'docker compose up -d --wait'}}
        self.write_manifest()
        self.process = patch.object(launcher, 'client_running', return_value=False).start()
        self.ready = patch.object(launcher, 'require_ready').start()
        self.spawn = patch.object(launcher, 'launch_client', return_value=1234).start()
        self.capture = patch.object(launcher, 'capture_client_log', return_value={'exists': False}).start()
        self.log_spawn = patch.object(launcher, 'launch_console', return_value=8765).start()
        self.addCleanup(patch.stopall)

    def write_manifest(self):
        self.manifest.write_text(json.dumps(self.data), encoding='utf8')

    def test_missing_proxy_is_not_restored_from_stable_or_numeric_backup(self):
        suffix = self.client / 'version.dll.1234'
        suffix.write_bytes(self.backup.read_bytes())
        with patch.object(launcher, 'capture_client_log', return_value={'exists': False}, create=True), \
             patch.object(launcher, 'launch_console', return_value=8765, create=True):
            result = launcher.start(self.manifest)
        self.assertFalse((self.client / 'version.dll').exists(),
                         'Client startup must not reintroduce the in-process debugging DLL')
        self.assertEqual('external', result['console'])
        self.assertEqual(8765, result['consolePid'])
        self.assertEqual(b'verified console', suffix.read_bytes())

    def test_known_proxy_is_preserved_but_no_longer_loaded(self):
        target = self.client / 'version.dll'
        target.write_bytes(self.backup.read_bytes())
        with patch.object(launcher, 'capture_client_log', return_value={'exists': False}, create=True), \
             patch.object(launcher, 'launch_console', return_value=8765, create=True):
            result = launcher.start(self.manifest)
        self.assertFalse(target.exists(), 'The known debugging proxy must be retired before game startup')
        self.assertEqual(self.backup.read_bytes(), Path(result['retiredProxy']).read_bytes())
        self.assertEqual('retired', result['proxy'])

    def test_external_console_captures_before_launch_and_receives_client_pid(self):
        events = []
        self.capture.side_effect = lambda: events.append('snapshot') or {'exists': False}
        self.spawn.side_effect = lambda root: events.append('client') or 1234
        self.log_spawn.side_effect = lambda root, pid, snapshot: events.append('console') or 8765
        result = launcher.start(self.manifest)
        self.assertEqual(['snapshot', 'client', 'console'], events)
        self.assertEqual('external', result['console'])
        self.assertEqual(1234, result['pid'])
        self.log_spawn.assert_called_once_with(self.client.resolve(), 1234, {'exists': False})

    def test_repair_only_never_starts_client_or_console(self):
        result = launcher.start(self.manifest, repair_only=True)
        self.assertTrue(result['repairOnly'])
        self.assertEqual('absent', result['proxy'])
        self.assertFalse((self.client / 'version.dll').exists())
        self.spawn.assert_not_called()
        self.capture.assert_not_called()
        self.log_spawn.assert_not_called()

    def test_numeric_rename_remains_archived_across_launches(self):
        suffix = self.client / 'version.dll.4294967295'
        suffix.write_bytes(self.backup.read_bytes())
        self.backup.unlink()
        for _ in range(2):
            launcher.start(self.manifest)
            self.assertFalse((self.client / 'version.dll').exists())
            self.assertEqual(b'verified console', suffix.read_bytes())

    def test_dry_run_reports_repair_and_changes_nothing(self):
        before = {p.relative_to(self.root): p.read_bytes() for p in self.root.rglob('*') if p.is_file()}
        result = launcher.start(self.manifest, dry_run=True)
        self.assertEqual('external', result['console'])
        self.assertEqual('absent', result['proxy'])
        self.assertEqual(before, {p.relative_to(self.root): p.read_bytes() for p in self.root.rglob('*') if p.is_file()})
        self.spawn.assert_not_called()

    def test_foreign_dll_is_never_overwritten_or_launched(self):
        target = self.client / 'version.dll'
        target.write_bytes(b'not ours')
        with self.assertRaisesRegex(ValueError, 'version.dll'):
            launcher.start(self.manifest)
        self.assertEqual(b'not ours', target.read_bytes())
        self.spawn.assert_not_called()

    def test_existing_valid_proxy_is_retired_even_without_old_backup(self):
        (self.client / 'version.dll').write_bytes(self.backup.read_bytes())
        self.backup.unlink()
        result = launcher.start(self.manifest)
        self.assertEqual('retired', result['proxy'])
        self.assertEqual(b'verified console', Path(result['retiredProxy']).read_bytes())
        self.assertFalse((self.client / 'version.dll').exists())

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
        suffix = self.client / 'version.dll.123'
        suffix.write_bytes(b'foreign')
        launcher.start(self.manifest)
        self.assertFalse((self.client / 'version.dll').exists())
        self.assertEqual(b'foreign', suffix.read_bytes())
        self.assertEqual(b'corrupt', self.backup.read_bytes())

    def test_manifest_cannot_omit_core_or_escape_client_root(self):
        del self.data['clientFiles']['GameAssembly.dll']
        self.data['clientFiles']['../outside'] = hashlib.sha256(b'').hexdigest()
        self.write_manifest()
        with self.assertRaisesRegex(ValueError, '清单'):
            launcher.start(self.manifest)
        self.spawn.assert_not_called()

    def test_retirement_never_overwrites_concurrently_created_backup(self):
        target = self.client / 'version.dll'
        target.write_bytes(self.backup.read_bytes())
        real_rename = Path.rename
        def competing_rename(source, destination):
            Path(destination).write_bytes(b'created concurrently')
            return real_rename(source, destination)
        with patch.object(Path, 'rename', new=competing_rename):
            with self.assertRaises(FileExistsError):
                launcher.start(self.manifest)
        self.assertEqual(b'verified console', target.read_bytes())
        self.spawn.assert_not_called()

    def test_replaced_proxy_is_restored_instead_of_archived_as_ours(self):
        target = self.client / 'version.dll'
        target.write_bytes(self.backup.read_bytes())
        real_rename = Path.rename
        def competing_rename(source, destination):
            if source.resolve() == target.resolve():
                target.write_bytes(b'foreign replacement')
            return real_rename(source, destination)
        with patch.object(Path, 'rename', new=competing_rename):
            with self.assertRaisesRegex(ValueError, '发生变化'):
                launcher.start(self.manifest)
        self.assertEqual(b'foreign replacement', target.read_bytes())
        self.spawn.assert_not_called()

    def test_console_failure_preserves_successful_game_launch(self):
        self.log_spawn.side_effect = OSError('reader unavailable')
        result = launcher.start(self.manifest)
        self.assertEqual(1234, result['pid'])
        self.assertEqual('reader unavailable', result['consoleWarning'])
        self.assertNotIn('consolePid', result)
        self.spawn.assert_called_once()

    def test_snapshot_failure_still_starts_game_but_never_replays_unbounded_old_logs(self):
        self.capture.side_effect = PermissionError('log unavailable')
        result = launcher.start(self.manifest)
        self.assertEqual(1234, result['pid'])
        self.assertEqual('log unavailable', result['consoleWarning'])
        self.spawn.assert_called_once()
        self.log_spawn.assert_not_called()

    def test_legacy_manifest_is_rejected_without_reintroducing_proxy(self):
        self.data['schema'] = 1
        self.write_manifest()
        with self.assertRaisesRegex(ValueError, '版本 2'):
            launcher.start(self.manifest)
        self.assertFalse((self.client / 'version.dll').exists())
        self.spawn.assert_not_called()

    def test_dry_run_preserves_known_proxy_and_reports_retirement(self):
        target = self.client / 'version.dll'
        target.write_bytes(self.backup.read_bytes())
        result = launcher.start(self.manifest, dry_run=True)
        self.assertEqual('would-retire', result['proxy'])
        self.assertEqual(b'verified console', target.read_bytes())
        self.spawn.assert_not_called()
        self.capture.assert_not_called()

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


@unittest.skipUnless(os.name == 'nt', 'Windows separate console process')
class ExternalConsoleLaunchTest(unittest.TestCase):
    def setUp(self):
        from unittest.mock import Mock
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        self.helper = Mock()
        self.helper.get_process_created.return_value = 133456789012345678
        self.process = Mock()
        self.process.pid = 4321
        self.process.poll.return_value = None
        self.mock_helper = patch.object(launcher, 'console_helper', return_value=self.helper).start()
        self.environment = patch.dict(os.environ, {'LOCALAPPDATA': str(self.root)}).start()
        self.spawn = patch.object(launcher.subprocess, 'Popen').start()
        self.addCleanup(patch.stopall)
        self.captured = {}

    def publish_ready(self, arguments, **kwargs):
        self.captured.update(arguments=arguments, options=kwargs)
        ready = Path(arguments[arguments.index('--ready-file') + 1])
        ready.write_text(json.dumps({'ready': True, 'pid': 4321, 'clientPid': 1234}), encoding='utf-8')
        return self.process

    def test_separate_console_confirms_its_own_pid_without_inherited_capture_pipes(self):
        self.spawn.side_effect = self.publish_ready
        snapshot = {'exists': False}
        self.assertEqual(4321, launcher.launch_console(self.root, 1234, snapshot))
        options = self.captured['options']
        self.assertEqual(subprocess.CREATE_NEW_CONSOLE, options['creationflags'])
        self.assertEqual(subprocess.DEVNULL, options['stdin'])
        self.assertEqual(subprocess.DEVNULL, options['stdout'])
        self.assertEqual(subprocess.DEVNULL, options['stderr'])
        self.assertTrue(options['close_fds'])
        arguments = self.captured['arguments']
        self.assertEqual('133456789012345678', arguments[arguments.index('--process-created') + 1])
        self.assertEqual(snapshot, json.loads(arguments[arguments.index('--snapshot-json') + 1]))
        self.assertFalse(list((self.root / 'HKRPG').glob('console-ready-*.json')))
        self.process.terminate.assert_not_called()

    def test_wrong_reader_identity_is_rejected_without_terminating_game_or_reader(self):
        def wrong_identity(arguments, **kwargs):
            process = self.publish_ready(arguments, **kwargs)
            ready = Path(arguments[arguments.index('--ready-file') + 1])
            ready.write_text(json.dumps({'ready': True, 'pid': 4321, 'clientPid': 999}), encoding='utf-8')
            return process
        self.spawn.side_effect = wrong_identity
        with self.assertRaisesRegex(RuntimeError, '进程核对失败'):
            launcher.launch_console(self.root, 1234, {'exists': False})
        self.process.terminate.assert_not_called()
        self.process.kill.assert_not_called()

    def test_exited_reader_is_reported_without_touching_the_game(self):
        self.spawn.return_value = self.process
        self.process.poll.return_value = 1
        with self.assertRaisesRegex(RuntimeError, '提前退出'):
            launcher.launch_console(self.root, 1234, {'exists': False})
        self.process.terminate.assert_not_called()
        self.process.kill.assert_not_called()

    def test_reader_timeout_is_bounded_and_does_not_terminate_a_process(self):
        self.spawn.return_value = self.process
        with patch.object(launcher.time, 'monotonic', side_effect=[0, 9]):
            with self.assertRaisesRegex(RuntimeError, '超时'):
                launcher.launch_console(self.root, 1234, {'exists': False})
        self.process.terminate.assert_not_called()
        self.process.kill.assert_not_called()


@unittest.skipUnless(os.name == 'nt', 'Windows ShellExecute contract')
class ShellExecuteLaunchTest(unittest.TestCase):
    def setUp(self):
        from unittest.mock import Mock
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.client = Path(self.directory.name) / 'client with spaces'
        self.client.mkdir()
        self.shell = Mock()
        self.kernel = Mock()
        self.kernel.GetProcessId.return_value = 2468
        self.kernel.CloseHandle.return_value = True
        self.captured = {}
        def accept(pointer):
            info = pointer._obj
            self.captured.update(verb=info.lpVerb, file=info.lpFile,
                directory=info.lpDirectory, parameters=info.lpParameters,
                mask=info.fMask, show=info.nShow, size=info.cbSize)
            info.hProcess = 1357
            return True
        self.shell.ShellExecuteExW.side_effect = accept
        self.loader = patch.object(ctypes, 'WinDLL', side_effect=lambda name, **kwargs:
                                   {'shell32': self.shell, 'kernel32': self.kernel}[name]).start()
        # Reproduce the real CreateProcess failure without ever launching a game.
        self.create_process = patch.object(launcher.subprocess, 'Popen', side_effect=ctypes.WinError(740)).start()
        self.addCleanup(patch.stopall)

    def test_require_administrator_exe_uses_normal_shell_launch(self):
        self.assertEqual(2468, launcher.launch_client(self.client))
        self.assertEqual('open', self.captured['verb'])
        self.assertEqual(str(self.client / 'StarRail.exe'), self.captured['file'])
        self.assertEqual(str(self.client), self.captured['directory'])
        self.assertIsNone(self.captured['parameters'])
        self.assertEqual(1, self.captured['show'])
        self.assertEqual(0x140, self.captured['mask'])
        self.shell.ShellExecuteExW.assert_called_once()
        self.kernel.CloseHandle.assert_called_once_with(1357)
        self.create_process.assert_not_called()

    def test_uac_cancel_has_clear_message_and_never_retries(self):
        self.shell.ShellExecuteExW.side_effect = None
        self.shell.ShellExecuteExW.return_value = False
        with patch.object(ctypes, 'get_last_error', return_value=1223):
            with self.assertRaisesRegex(RuntimeError, 'Windows.*取消|取消.*Windows'):
                launcher.launch_client(self.client)
        self.shell.ShellExecuteExW.assert_called_once()
        self.kernel.CloseHandle.assert_not_called()
        self.create_process.assert_not_called()

    def test_other_shell_errors_preserve_windows_error_code(self):
        self.shell.ShellExecuteExW.side_effect = None
        self.shell.ShellExecuteExW.return_value = False
        with patch.object(ctypes, 'get_last_error', return_value=2):
            with self.assertRaises(OSError) as caught:
                launcher.launch_client(self.client)
        self.assertEqual(2, caught.exception.winerror)
        self.kernel.CloseHandle.assert_not_called()

    def test_process_handle_is_closed_when_process_id_lookup_fails(self):
        self.kernel.GetProcessId.return_value = 0
        with patch.object(ctypes, 'get_last_error', return_value=6):
            with self.assertRaises(OSError) as caught:
                launcher.launch_client(self.client)
        self.assertEqual(6, caught.exception.winerror)
        self.kernel.CloseHandle.assert_called_once_with(1357)


@unittest.skipUnless(os.name == 'nt' and shutil.which('gcc'), 'Windows native shell integration')
class NativeShellExecuteTest(unittest.TestCase):
    def test_real_shell_execute_closing_launch_handle_keeps_child_running(self):
        from ctypes import wintypes
        with tempfile.TemporaryDirectory() as directory:
            client = Path(directory) / 'shell fixture with spaces'
            client.mkdir()
            source = client / 'fixture.c'
            source.write_text(r"""#include <windows.h>
#include <stdio.h>
int WINAPI WinMain(HINSTANCE instance, HINSTANCE previous, LPSTR arguments, int show) {
    (void)instance; (void)previous; (void)arguments; (void)show;
    HANDLE output = CreateFileW(L"launched.json", GENERIC_WRITE, 0, NULL, CREATE_ALWAYS,
                                 FILE_ATTRIBUTE_NORMAL, NULL);
    if (output == INVALID_HANDLE_VALUE) return 10;
    char data[100]; DWORD written;
    int size = snprintf(data, sizeof(data), "{\"pid\":%lu,\"console\":%d}",
                        GetCurrentProcessId(), GetConsoleWindow() != NULL);
    if (!WriteFile(output, data, (DWORD)size, &written, NULL)) return 11;
    CloseHandle(output);
    for (int i = 0; i < 1000; i++) {
        if (GetFileAttributesW(L"release") != INVALID_FILE_ATTRIBUTES) return 0;
        Sleep(10);
    }
    return 12;
}
""", encoding='ascii')
            subprocess.run(['gcc', '-mwindows', '-O2', '-Wall', '-Wextra', '-Werror',
                            '-o', str(client / 'StarRail.exe'), str(source)],
                           check=True, capture_output=True, creationflags=subprocess.CREATE_NO_WINDOW)
            kernel = ctypes.WinDLL('kernel32', use_last_error=True)
            kernel.OpenProcess.argtypes = [wintypes.DWORD, wintypes.BOOL, wintypes.DWORD]
            kernel.OpenProcess.restype = wintypes.HANDLE
            kernel.WaitForSingleObject.argtypes = [wintypes.HANDLE, wintypes.DWORD]
            kernel.WaitForSingleObject.restype = wintypes.DWORD
            kernel.GetExitCodeProcess.argtypes = [wintypes.HANDLE, ctypes.POINTER(wintypes.DWORD)]
            kernel.GetExitCodeProcess.restype = wintypes.BOOL
            kernel.CloseHandle.argtypes = [wintypes.HANDLE]
            kernel.CloseHandle.restype = wintypes.BOOL
            process = None
            try:
                pid = launcher.launch_client(client)
                process = kernel.OpenProcess(0x100000 | 0x1000, False, pid)
                self.assertTrue(process, 'Shell returned the live child PID after closing its own handle')
                deadline = time.monotonic() + 5
                while True:
                    try:
                        proof = json.loads((client / 'launched.json').read_text('ascii'))
                        break
                    except (FileNotFoundError, PermissionError, ValueError):
                        self.assertLess(time.monotonic(), deadline, 'Native fixture did not write its marker')
                        time.sleep(.02)
                self.assertEqual({'pid': pid, 'console': 0}, proof)
                (client / 'release').touch()
                self.assertEqual(0, kernel.WaitForSingleObject(process, 5000))
                exit_code = wintypes.DWORD()
                self.assertTrue(kernel.GetExitCodeProcess(process, ctypes.byref(exit_code)))
                self.assertEqual(0, exit_code.value)
            finally:
                (client / 'release').touch()
                if process:
                    kernel.WaitForSingleObject(process, 5000)
                    kernel.CloseHandle(process)


if __name__ == '__main__':
    unittest.main()
