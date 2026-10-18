"""Isolated regressions for the external client log console; no real game launch."""
import importlib.util
from pathlib import Path
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location('client_log_console', ROOT / 'scripts/client-log-console.py')
console = importlib.util.module_from_spec(spec)
spec.loader.exec_module(console)


class PrelaunchSnapshotTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.path = Path(self.temp.name) / 'Player.log'

    def test_startup_written_before_reader_attach_is_not_skipped_or_replayed(self):
        self.path.write_bytes(b'old session\n')
        snapshot = console._capture_snapshot(self.path)
        with self.path.open('ab') as stream:
            stream.write(b'new startup\n')
        self.assertEqual(['new startup'], console.LogTail(self.path, snapshot).poll())

    def test_first_log_created_after_prelaunch_snapshot_is_read_from_zero(self):
        snapshot = console._capture_snapshot(self.path)
        self.path.write_bytes(b'new startup\n')
        self.assertEqual(['new startup'], console.LogTail(self.path, snapshot).poll())

    def test_same_file_rewritten_larger_before_attach_is_detected(self):
        self.path.write_bytes(b'old\n')
        snapshot = console._capture_snapshot(self.path)
        self.path.write_bytes(b'new startup longer than old log\n')
        self.assertEqual(['new startup longer than old log'], console.LogTail(self.path, snapshot).poll())
import ctypes
import io
import json
import os
import shutil
import subprocess
import sys
import time
from unittest.mock import patch


class TailSafetyTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.path = Path(self.temp.name) / 'Player.log'

    def append(self, data):
        with self.path.open('ab') as stream:
            stream.write(data)

    def test_half_utf8_line_is_buffered_until_newline(self):
        tail = console.LogTail(self.path, console._capture_snapshot(self.path))
        self.path.write_bytes('启动'.encode('utf8')[:2])
        self.assertEqual([], tail.poll())
        self.append('启动'.encode('utf8')[2:] + b' ready')
        self.assertEqual([], tail.poll())
        self.append(b'\n')
        self.assertEqual(['启动 ready'], tail.poll())

    def test_sensitive_field_split_across_reads_never_leaks(self):
        tail = console.LogTail(self.path, console._capture_snapshot(self.path))
        self.path.write_bytes(b'ordinary\naccess_to')
        self.assertEqual(['ordinary'], tail.poll())
        self.append(b'ken=private-value\nhttps://example.test/path?x=secret-value\n')
        self.assertEqual([], tail.poll())
        self.append(b'person@example.test\npassword = redacted\nvisible\n')
        self.assertEqual(['visible'], tail.poll())

    def test_old_incomplete_line_is_discarded_after_prelaunch_boundary(self):
        self.path.write_bytes(b'old token=')
        tail = console.LogTail(self.path, console._capture_snapshot(self.path))
        self.append(b'not-new-log\nnew startup\n')
        self.assertEqual(['new startup'], tail.poll())

    def test_oversized_line_is_dropped_and_later_lines_survive(self):
        tail = console.LogTail(self.path, console._capture_snapshot(self.path))
        self.path.write_bytes(b'A' * (console.MAX_LINE + 50))
        self.assertEqual([], tail.poll())
        self.append(b'private tail\nsafe\n')
        self.assertEqual(['safe'], tail.poll())

    def test_rotation_truncation_and_larger_rewrite_reset_without_partial_old_line(self):
        tail = console.LogTail(self.path, console._capture_snapshot(self.path))
        self.path.write_bytes(b'first\nunfinished')
        self.assertEqual(['first'], tail.poll())
        rotated = self.path.with_suffix('.new')
        rotated.write_bytes(b'second\n')
        os.replace(rotated, self.path)
        self.assertEqual(['second'], tail.poll())
        self.path.write_bytes(b'x\n')
        self.assertEqual(['x'], tail.poll())
        self.path.write_bytes(b'third larger replacement\n')
        self.assertEqual(['third larger replacement'], tail.poll())
        self.assertEqual([], tail.poll())

    def test_each_poll_is_bounded_and_does_not_lose_lines(self):
        tail = console.LogTail(self.path, console._capture_snapshot(self.path))
        count = console.MAX_POLL // 2 + 10
        self.path.write_bytes(b'x\n' * count)
        first = tail.poll()
        self.assertEqual(console.MAX_POLL // 2, len(first))
        self.assertEqual(10, len(tail.poll()))
        self.assertEqual([], tail.poll())

    def exit_tail(self):
        self.path.write_bytes(b'secret=redacted\n' * (console.MAX_POLL // 7) + b'fatal exit marker\n')
        tail = console.LogTail(self.path, {'exists':False})
        tail.poll()
        return tail

    def test_exit_drain_freezes_eof_even_if_later_bytes_are_appended(self):
        tail = self.exit_tail()
        drain = tail.drain_after_exit()
        first = next(drain)
        self.append(b'later session must not appear\n')
        lines = first + [line for chunk in drain for line in chunk]
        self.assertEqual(['fatal exit marker'], lines)

    def test_exit_drain_keeps_original_file_if_path_is_replaced(self):
        tail = self.exit_tail()
        drain = tail.drain_after_exit()
        first = next(drain)
        replacement = self.path.with_suffix('.replacement')
        replacement.write_bytes(b'later session must not appear\n')
        # Windows permits a shared-delete source rename; direct replace of an open
        # destination fails in MoveFileEx even though the reader shares deletion.
        os.replace(self.path, self.path.with_suffix('.previous'))
        os.replace(replacement, self.path)
        lines = first + [line for chunk in drain for line in chunk]
        self.assertEqual(['fatal exit marker'], lines)

    def test_exit_drain_stops_if_original_file_is_rewritten(self):
        tail = self.exit_tail()
        drain = tail.drain_after_exit()
        self.assertEqual([], next(drain))
        self.path.write_bytes(b'later session must not appear\n' * 20000)
        self.assertEqual([], [line for chunk in drain for line in chunk])

    def test_snapshot_rejects_any_log_path_override_or_unbounded_offset(self):
        for snapshot in ({'exists':False, 'path':'other.log'}, {'exists':'yes'}, None):
            with self.subTest(snapshot=snapshot), self.assertRaises(console.DebugUnavailable):
                console.LogTail(self.path, snapshot)
        self.path.write_bytes(b'x\n')
        snapshot = console._capture_snapshot(self.path)
        snapshot['size'] = 1 << 80
        with self.assertRaises(console.DebugUnavailable):
            console.LogTail(self.path, snapshot)


class KeepText(io.StringIO):
    def close(self):
        self.closed_by_reader = True


class FakeProcess:
    def __init__(self, *args):
        self.closed = False
        self.live = True
    def alive(self): return self.live
    def exit_code(self): return 0
    def close(self): self.closed = True


class ReaderLifecycleTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.log = self.root / 'Player.log'
        self.ready = self.root / ('console-ready-' + 'a' * 32 + '.json')
        self.process = FakeProcess()
        self.output = KeepText()

    def run_reader(self, **kw):
        options = dict(log_path=self.log, output_directory=self.root,
            process_factory=lambda *a:self.process, console_factory=lambda:self.output,
            pause=lambda _seconds:setattr(self.process, 'live', False))
        options.update(kw)
        return console.run_reader(1234, self.root, {'exists':False}, 9876, self.ready, **options)

    def test_ready_handshake_journal_and_normal_game_exit(self):
        self.log.write_bytes(b'loading\naccount=private\n')
        self.assertEqual(0, self.run_reader())
        self.assertEqual({'ready':True,'pid':os.getpid(),'clientPid':1234},json.loads(self.ready.read_text()))
        logs = list(self.root.glob('client-debug-*-1234-*.log'))
        self.assertEqual(1,len(logs))
        text = logs[0].read_text(encoding='utf8')
        self.assertIn('[Unity] loading',text)
        self.assertNotIn('private',text)
        self.assertIn('Client exited',text)
        self.assertTrue(self.process.closed)
        self.assertTrue(self.output.closed_by_reader)

    def test_exit_drains_beyond_two_polls_even_when_filtered_chunks_are_empty(self):
        self.log.write_bytes(b'secret=redacted\n' * (3 * console.MAX_POLL // 16 + 50)
                             + b'fatal exit marker\n')
        self.process.live = False
        self.assertEqual(0, self.run_reader())
        text = self.output.getvalue()
        self.assertIn('[Unity] fatal exit marker', text)
        self.assertNotIn('redacted', text)
        self.assertLess(text.index('fatal exit marker'), text.index('Client exited'))

    def test_ctrl_c_only_closes_reader_handle(self):
        def interrupt(_seconds): raise KeyboardInterrupt()
        self.assertEqual(0,self.run_reader(pause=interrupt))
        self.assertTrue(self.process.live)
        self.assertTrue(self.process.closed)
        self.assertIn('game was not stopped',self.output.getvalue())

    def test_failed_identity_or_read_permission_produces_failure_handshake(self):
        def denied(*args): raise console.DebugUnavailable('Cannot verify client PID identity.')
        self.assertEqual(1,self.run_reader(process_factory=denied))
        result=json.loads(self.ready.read_text())
        self.assertFalse(result['ready'])
        self.assertIn('game was not stopped',result['error'])
        self.assertTrue(self.process.live)
        self.assertFalse(list(self.root.glob('client-debug-*.log')))

    def test_log_permission_error_cannot_leak_exception_payload(self):
        with patch.object(console,'_open_log',side_effect=PermissionError('account=SECRET')):
            self.assertEqual(1,self.run_reader())
        text=self.ready.read_text()+self.output.getvalue()
        self.assertNotIn('SECRET',text)
        self.assertIn('PermissionError',text)
        self.assertTrue(self.process.closed)
        self.assertTrue(self.process.live)

    def test_ready_path_cannot_escape_directory_and_existing_file_is_untouched(self):
        for path in (self.root/'other.json',self.root.parent/('console-ready-'+'a'*32+'.json')):
            with self.subTest(path=path),self.assertRaises(console.DebugUnavailable):
                console._validate_ready_file(path,self.root)
        self.ready.write_text('keep existing')
        self.assertEqual(1,self.run_reader())
        self.assertEqual('keep existing',self.ready.read_text())
        self.assertTrue(self.process.live)

    def test_production_cli_exposes_no_arbitrary_log_path(self):
        with patch.object(sys,'stderr',io.StringIO()), self.assertRaises(SystemExit) as caught:
            console.main(['--pid','1','--client-root',str(self.root),'--snapshot-json','{}',
                          '--process-created','1','--ready-file',str(self.ready),
                          '--log-path','private-file'])
        self.assertEqual(2,caught.exception.code)


@unittest.skipUnless(os.name=='nt' and shutil.which('gcc'),'Windows native fixture')
class NativeProcessLifecycleTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.build=tempfile.TemporaryDirectory()
        folder=Path(cls.build.name)
        source=folder/'fixture.c'
        source.write_text(r'''#include <windows.h>
int WINAPI WinMain(HINSTANCE a,HINSTANCE b,LPSTR c,int d) {
    (void)a;(void)b;(void)c;(void)d;
    for(int i=0;i<2000;i++) {
        if(GetFileAttributesW(L"release")!=INVALID_FILE_ATTRIBUTES)return 0;
        Sleep(10);
    }
    return 12;
}
''',encoding='ascii')
        cls.exe=folder/'StarRail.exe'
        subprocess.run(['gcc','-mwindows','-O2','-Wall','-Wextra','-Werror','-o',str(cls.exe),str(source)],
                       check=True,capture_output=True,creationflags=subprocess.CREATE_NO_WINDOW)

    @classmethod
    def tearDownClass(cls): cls.build.cleanup()

    def setUp(self):
        self.temp=tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root=Path(self.temp.name)/'client with spaces'
        self.root.mkdir()
        shutil.copy2(self.exe,self.root/'StarRail.exe')
        self.game=subprocess.Popen([str(self.root/'StarRail.exe')],cwd=self.root,
                                   creationflags=subprocess.CREATE_NO_WINDOW)
        self.addCleanup(self.release)

    def release(self):
        (self.root/'release').touch()
        self.game.wait(timeout=5)

    def test_real_handle_requires_exact_path_and_creation_time_and_survives_exit(self):
        created=console.get_process_created(self.game.pid,self.root)
        with self.assertRaisesRegex(console.DebugUnavailable,'different executable'):
            console.ClientProcess(self.game.pid,self.root/'other',created)
        with self.assertRaisesRegex(console.DebugUnavailable,'identity changed'):
            console.ClientProcess(self.game.pid,self.root,created+1)
        with console.ClientProcess(self.game.pid,self.root,created) as process:
            self.assertTrue(process.alive())
            self.release()
            self.assertFalse(process.alive())
            self.assertEqual(0,process.exit_code())
        self.assertIsNone(process.handle)

    def spawn_reader(self):
        log=self.root/'Player.log'
        log.write_bytes(b'prior session\n')
        snapshot=console._capture_snapshot(log)
        with log.open('ab') as stream:stream.write(b'startup before attach\npassword=SECRET\n')
        ready=self.root/('console-ready-'+'b'*32+'.json')
        code='''import importlib.util,json,sys
from pathlib import Path
spec=importlib.util.spec_from_file_location('client_log_console',sys.argv[1])
m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m)
raise SystemExit(m.run_reader(int(sys.argv[2]),Path(sys.argv[3]),json.loads(sys.argv[4]),int(sys.argv[5]),Path(sys.argv[6]),log_path=Path(sys.argv[7]),output_directory=Path(sys.argv[3])))
'''
        startup=subprocess.STARTUPINFO()
        startup.dwFlags=subprocess.STARTF_USESHOWWINDOW
        startup.wShowWindow=0  # Exercise a real independent console without showing a test window.
        reader=subprocess.Popen([sys.executable,'-X','utf8','-c',code,str(ROOT/'scripts/client-log-console.py'),
            str(self.game.pid),str(self.root),json.dumps(snapshot),
            str(console.get_process_created(self.game.pid,self.root)),str(ready),str(log)],
            creationflags=subprocess.CREATE_NEW_CONSOLE,startupinfo=startup,close_fds=True,
            stdin=subprocess.DEVNULL,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
        self.addCleanup(lambda: reader.wait(timeout=5) if reader.poll() is not None else self.stop_reader(reader))
        deadline=time.monotonic()+6
        while True:
            try:
                result=json.loads(ready.read_text())
                self.assertTrue(result.get('ready'),result)
                self.assertEqual(reader.pid,result['pid'])
                self.assertEqual(self.game.pid,result['clientPid'])
                return reader,log
            except (FileNotFoundError,json.JSONDecodeError):
                self.assertLess(time.monotonic(),deadline,'Reader failed to handshake')
                self.assertIsNone(reader.poll(),'Reader exited before handshake')
                time.sleep(.02)

    @staticmethod
    def stop_reader(reader):
        reader.terminate()  # Only the test-owned log reader, never the game PID.
        reader.wait(timeout=5)

    def test_real_new_console_exits_when_fixture_exits_and_keeps_sanitized_journal(self):
        reader,log=self.spawn_reader()
        self.release()
        self.assertEqual(0,reader.wait(timeout=5))
        text=''.join(p.read_text(encoding='utf8') for p in self.root.glob('client-debug-*.log'))
        self.assertIn('startup before attach',text)
        self.assertNotIn('SECRET',text)
        self.assertIn('Client exited',text)
        self.assertEqual(b'prior session\nstartup before attach\npassword=SECRET\n',log.read_bytes())

    def test_closing_independent_reader_does_not_stop_fixture(self):
        reader,_log=self.spawn_reader()
        self.stop_reader(reader)
        self.assertIsNone(self.game.poll())
        self.release()
        self.assertEqual(0,self.game.returncode)


    def test_real_launcher_production_cli_ready_and_fixture_exit(self):
        launcher_spec=importlib.util.spec_from_file_location('test_external_launcher',ROOT/'scripts/start-local-client.py')
        launcher=importlib.util.module_from_spec(launcher_spec)
        launcher_spec.loader.exec_module(launcher)
        # The production path is read only. Its prelaunch baseline prevents old log replay.
        snapshot=launcher.capture_client_log()
        spawned=[]
        original_popen=subprocess.Popen
        def hidden_console(*args,**kwargs):
            self.assertEqual(subprocess.CREATE_NEW_CONSOLE,kwargs['creationflags'])
            self.assertTrue(kwargs['close_fds'])
            for stream in ('stdin','stdout','stderr'):
                self.assertEqual(subprocess.DEVNULL,kwargs[stream])
            startup=subprocess.STARTUPINFO()
            startup.dwFlags=subprocess.STARTF_USESHOWWINDOW
            startup.wShowWindow=0
            reader=original_popen(*args,**kwargs,startupinfo=startup)
            spawned.append(reader)
            self.addCleanup(lambda:reader.wait(timeout=5) if reader.poll() is not None else self.stop_reader(reader))
            return reader
        with patch.object(launcher.subprocess,'Popen',side_effect=hidden_console):
            console_pid=launcher.launch_console(self.root,self.game.pid,snapshot)
        self.assertEqual(1,len(spawned))
        reader=spawned[0]
        self.assertEqual(reader.pid,console_pid)  # launch_console validated both readiness PIDs.
        self.assertIsNone(reader.poll())
        self.release()
        self.assertEqual(0,reader.wait(timeout=5))
        self.assertEqual(0,self.game.poll())
        self.assertEqual(snapshot,launcher.capture_client_log())
        journals=list(console.journal_directory().glob('client-debug-*-'+str(self.game.pid)+'-*.log'))
        self.assertEqual(1,len(journals))
        text=journals[0].read_text(encoding='utf8')
        self.assertIn('independent reader PID '+str(console_pid),text)
        self.assertIn('Client exited; status 0',text)
        self.assertNotIn('[Unity]',text)  # This fixture writes no real Player.log bytes.


class ProcessRightsAndPrivacyTest(unittest.TestCase):
    def test_standalone_user_identifiers_are_not_copied_to_debug_output(self):
        for line in ('UID: 123456', 'uid = 123456', 'userId: 123456', '{"uid":12345678}'):
            with self.subTest(line=line):
                self.assertIsNone(console.safe_line(line))
        self.assertEqual('Unity mesh build complete',console.safe_line('Unity mesh build complete'))
        self.assertEqual('GUID lookup complete',console.safe_line('GUID lookup complete'))
        self.assertEqual('中文\t [31m [0m text',console.safe_line('中文\t\x1b[31m\x9b[0m\x9dtext'))

    @unittest.skipUnless(os.name == 'nt','Windows API contract')
    def test_process_opens_only_query_limited_and_synchronize_then_closes_handle(self):
        from unittest.mock import Mock
        with tempfile.TemporaryDirectory() as folder:
            kernel=Mock()
            kernel.OpenProcess.return_value=1357
            def image(handle,flags,buffer,size):
                buffer.value=str(Path(folder).resolve()/'StarRail.exe')
                return True
            def times(handle,created,*unused):
                created._obj.dwLowDateTime=8899
                created._obj.dwHighDateTime=0
                return True
            kernel.QueryFullProcessImageNameW.side_effect=image
            kernel.GetProcessTimes.side_effect=times
            with patch.object(ctypes,'WinDLL',return_value=kernel):
                self.assertEqual(8899,console.get_process_created(42,folder))
            kernel.OpenProcess.assert_called_once_with(0x101000,False,42)
            kernel.CloseHandle.assert_called_once_with(1357)
            kernel.TerminateProcess.assert_not_called()


if __name__ == '__main__':
    unittest.main()
