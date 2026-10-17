import ctypes
import importlib.util
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import time
import unittest

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location('client_console_build', ROOT / 'scripts/build-client-console.py')
builder = importlib.util.module_from_spec(spec)
spec.loader.exec_module(builder)


@unittest.skipUnless(os.name == 'nt' and shutil.which('gcc'), 'Windows x64 GCC integration')
class ClientConsoleTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.directory = Path(tempfile.mkdtemp(prefix='client-console-', dir=ROOT / 'build'))
        cls.production = builder.build(cls.directory / 'production')
        cls.testing = builder.build(cls.directory / 'testing', test=True)
        cls.host = cls.directory / 'host.exe'
        subprocess.run(['gcc', '-mwindows', '-O2', '-Wall', '-Wextra', '-Werror',
            '-o', str(cls.host), str(ROOT / 'scripts/tests/fixtures/client-console-host.c'), '-lversion'], check=True)

    def wait_for(self, condition, process=None):
        deadline = time.monotonic() + 12
        while not condition():
            if process is not None and process.poll() is not None:
                self.fail(f'GUI test host exited with {process.returncode}')
            if time.monotonic() > deadline:
                self.fail('Native console condition timed out')
            time.sleep(.05)

    def test_all_system_exports_still_resolve_to_windows(self):
        original = ctypes.WinDLL(self.production['systemVersionDLL'])
        proxy = ctypes.WinDLL(self.production['library'])
        for name in self.production['systemExports']:
            with self.subTest(export=name):
                self.assertEqual(ctypes.cast(getattr(original, name), ctypes.c_void_p).value,
                                 ctypes.cast(getattr(proxy, name), ctypes.c_void_p).value)
        self.assertEqual(0, proxy.HKRPGDebugConsoleStatus(), 'A foreign process must not open a console')
        self.assertNotIn(b'HKRPG_CONSOLE_TEST_LOG', Path(self.production['library']).read_bytes())

    def test_production_dll_opens_a_console_in_the_gui_client_process(self):
        run = self.directory / 'production-host'
        run.mkdir()
        shutil.copy2(self.host, run / 'StarRail.exe')
        shutil.copy2(self.production['library'], run / 'version.dll')
        process = subprocess.Popen([str(run / 'StarRail.exe')], cwd=run,
            env=os.environ | {'HKRPG_CONSOLE_HOST_DIR': str(run)})
        try:
            self.wait_for(lambda: (run / 'proof.json').is_file(), process)
            proof = json.loads((run / 'proof.json').read_text())
            self.assertTrue(proof['ownsConsole'])
            self.assertEqual(process.pid, proof['pid'])
            self.assertNotEqual(0, proof['consoleWindow'])
        finally:
            (run / 'stop').touch()
            try:
                process.wait(timeout=5)
            except subprocess.TimeoutExpired:
                process.terminate()
                process.wait(timeout=5)
        self.assertEqual(0, process.returncode)

    def test_game_style_small_stack_thread_notifications_do_not_overflow(self):
        run = self.directory / 'small-stack-host'
        run.mkdir()
        shutil.copy2(self.host, run / 'StarRail.exe')
        shutil.copy2(self.production['library'], run / 'version.dll')
        process = subprocess.Popen([str(run / 'StarRail.exe')], cwd=run,
            env=os.environ | {'HKRPG_CONSOLE_HOST_DIR': str(run), 'HKRPG_CONSOLE_HOST_SMALL_STACK': '1'})
        # Once proof exists, the host can exit normally without another controller step.
        (run / 'stop').touch()
        try:
            exit_code = process.wait(timeout=12)
        except subprocess.TimeoutExpired:
            process.terminate()
            process.wait(timeout=5)
            self.fail('Small-stack GUI host failed to exit')
        self.assertEqual(0, exit_code, f'Native small-stack host exit: 0x{exit_code:08x}')
        proof = json.loads((run / 'proof.json').read_text())
        self.assertTrue(proof['ownsConsole'])

    def test_client_owns_console_and_real_time_logs_survive_rewrites(self):
        run = self.directory / '中文路径 with spaces'
        run.mkdir()
        shutil.copy2(self.host, run / 'StarRail.exe')
        shutil.copy2(self.testing['library'], run / 'version.dll')
        log = run / 'Player.log'
        environment = os.environ | {'HKRPG_CONSOLE_HOST_DIR': str(run), 'HKRPG_CONSOLE_TEST_LOG': str(log)}
        process = subprocess.Popen([str(run / 'StarRail.exe')], cwd=run, env=environment)
        journal = None
        try:
            self.wait_for(lambda: (run / 'proof.json').is_file(), process)
            proof = json.loads((run / 'proof.json').read_text())
            self.assertEqual(process.pid, proof['pid'])
            self.assertTrue(proof['ownsConsole'])
            self.assertNotEqual(0, proof['consoleWindow'])
            folder = Path(os.environ['LOCALAPPDATA']) / 'HKRPG'
            self.wait_for(lambda: bool(list(folder.glob(f'client-debug-*-{process.pid}.log'))), process)
            journal = next(folder.glob(f'client-debug-*-{process.pid}.log'))
            contents = lambda: journal.read_text(encoding='utf8')
            log.write_text('FIRST 中文\n', encoding='utf8')
            self.wait_for(lambda: 'FIRST 中文' in contents(), process)
            with log.open('a', encoding='utf8') as stream:
                stream.write('to'); stream.flush()
                time.sleep(.35)
                stream.write('ken=must-never-leak\nSECOND\n')
            self.wait_for(lambda: 'SECOND' in contents(), process)
            self.assertNotIn('must-never-leak', contents())
            log.write_text('x' * 200 + '\nROTATED-LARGER\n', encoding='utf8')
            self.wait_for(lambda: 'ROTATED-LARGER' in contents(), process)
            with log.open('a', encoding='utf8') as stream:
                stream.write('x' * 9000 + ' secret\nAFTER-LONG-LINE\n')
            self.wait_for(lambda: 'AFTER-LONG-LINE' in contents(), process)
            self.assertNotIn('secret', contents())
            self.assertIsNone(process.poll())
        finally:
            (run / 'stop').touch()
            try:
                process.wait(timeout=5)
            except subprocess.TimeoutExpired:
                process.terminate()
                process.wait(timeout=5)
            if journal:
                shutil.copy2(journal, run / 'captured-client-debug.log')
        self.assertEqual(0, process.returncode)


if __name__ == '__main__':
    unittest.main()
