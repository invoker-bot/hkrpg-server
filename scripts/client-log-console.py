"""Independent Windows client-log console. Never loads code into the game."""
import argparse
from collections import OrderedDict
import ctypes
from ctypes import wintypes
from datetime import datetime, timezone
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import sys
import time
import uuid

PROCESS_ACCESS = 0x100000 | 0x1000  # SYNCHRONIZE | PROCESS_QUERY_LIMITED_INFORMATION
MAX_LINE = 8191
MAX_POLL = 128 * 1024
SENSITIVE_KEYS = ('token', 'authkey', 'password', 'email', 'account', 'combo', 'secret',
                  'authorization', 'cookie', 'passport', 'ticket', 'session_key',
                  'userid', 'user_id', 'username', 'user_name', 'deviceid', 'device_id', 'uid=')
SNAPSHOT_KEYS = {'exists', 'device', 'fileIndex', 'size', 'checkpointOffset',
                 'checkpointSHA256', 'endsWithNewline'}
ENTITY_DUPLICATE = re.compile(
    r'^Name ((?:PROP_|NPC_)?\d+-\d+) is used\. old entity \d+\. new entity \d+[.!]?$')
DIAGNOSTIC_HEAD = re.compile(
    r'^(?:Curl error \d+:|Request failed without retry chance\s*$|wm_initialize:failed:'
    r'|\[(?:warn(?:ing)?|error|exception|fatal)\]'
    r'|[\w. +]{0,80}(?:Exception|Error):|PerformanceInstance \w+ - \d+ .*error:'
    r'|\[Task\] .*\b(?:not found|failed|missing)\b)', re.IGNORECASE)
STACK_FRAME = re.compile(r'^[\w.$+<>`/]+:[^\s(]+\(')


class DebugUnavailable(RuntimeError):
    """Controlled user-facing error; never include raw log data."""


def _windows_error(action):
    return DebugUnavailable(f'{action} (Windows error {ctypes.get_last_error()}).')


def _known_folder(guid):
    if os.name != 'nt':
        raise DebugUnavailable('The client debug console requires Windows.')
    raw = (ctypes.c_ubyte * 16).from_buffer_copy(uuid.UUID(guid).bytes_le)
    shell = ctypes.WinDLL('shell32', use_last_error=True)
    ole = ctypes.WinDLL('ole32', use_last_error=True)
    shell.SHGetKnownFolderPath.argtypes = [ctypes.c_void_p, wintypes.DWORD,
                                         wintypes.HANDLE, ctypes.POINTER(ctypes.c_void_p)]
    shell.SHGetKnownFolderPath.restype = ctypes.c_long
    ole.CoTaskMemFree.argtypes = [ctypes.c_void_p]
    ole.CoTaskMemFree.restype = None
    value = ctypes.c_void_p()
    if shell.SHGetKnownFolderPath(ctypes.byref(raw), 0, None, ctypes.byref(value)) != 0:
        raise DebugUnavailable('Windows could not locate the client log directory.')
    try:
        return Path(ctypes.wstring_at(value))
    finally:
        ole.CoTaskMemFree(value)


def player_log_path():
    low = _known_folder('a520a1a4-1780-4ff6-bd18-167343c5af16')
    return low / 'miHoYo' / '\u5d29\u574f\uff1a\u661f\u7a79\u94c1\u9053' / 'Player.log'


def journal_directory():
    return _known_folder('f1b32785-6fba-4fcf-9d55-7b8e7f157091') / 'HKRPG'


def _open_log(path):
    if os.name != 'nt':
        return Path(path).open('rb')
    import msvcrt
    kernel = ctypes.WinDLL('kernel32', use_last_error=True)
    kernel.CreateFileW.argtypes = [wintypes.LPCWSTR, wintypes.DWORD, wintypes.DWORD,
                                  ctypes.c_void_p, wintypes.DWORD, wintypes.DWORD, wintypes.HANDLE]
    kernel.CreateFileW.restype = wintypes.HANDLE
    kernel.CloseHandle.argtypes = [wintypes.HANDLE]
    kernel.CloseHandle.restype = wintypes.BOOL
    handle = kernel.CreateFileW(str(path), 0x80000000, 7, None, 3, 0x80, None)
    if handle == ctypes.c_void_p(-1).value:
        code = ctypes.get_last_error()
        if code in (2, 3):
            raise FileNotFoundError(str(path))
        raise _windows_error('Cannot read Player.log')
    try:
        fd = msvcrt.open_osfhandle(handle, os.O_RDONLY | os.O_BINARY)
    except Exception:
        kernel.CloseHandle(handle)
        raise
    return os.fdopen(fd, 'rb')  # The Python stream now owns the Windows handle.


def _capture_snapshot(path):
    try:
        with _open_log(path) as stream:
            stat = os.fstat(stream.fileno())
            offset = max(0, stat.st_size - 64)
            stream.seek(offset)
            checkpoint = stream.read(stat.st_size - offset)
            if len(checkpoint) != stat.st_size - offset:
                raise DebugUnavailable('Player.log changed during the prelaunch snapshot; retry startup.')
            return {'exists': True, 'device': stat.st_dev, 'fileIndex': stat.st_ino,
                    'size': stat.st_size, 'checkpointOffset': offset,
                    'checkpointSHA256': hashlib.sha256(checkpoint).hexdigest(),
                    'endsWithNewline': not checkpoint or checkpoint.endswith(b'\n')}
    except FileNotFoundError:
        return {'exists': False}


def capture_log_snapshot():
    """Call before ShellExecute. No log path or content is returned."""
    return _capture_snapshot(player_log_path())


def _validate_snapshot(snapshot):
    if not isinstance(snapshot, dict) or type(snapshot.get('exists')) is not bool:
        raise DebugUnavailable('Invalid prelaunch log snapshot.')
    if snapshot['exists'] is False:
        if set(snapshot) != {'exists'}:
            raise DebugUnavailable('Invalid prelaunch log snapshot.')
        return dict(snapshot)
    if set(snapshot) != SNAPSHOT_KEYS:
        raise DebugUnavailable('Invalid prelaunch log snapshot.')
    for key in ('device', 'fileIndex', 'size', 'checkpointOffset'):
        if type(snapshot[key]) is not int or not 0 <= snapshot[key] <= (1 << 128) - 1:
            raise DebugUnavailable('Invalid prelaunch log snapshot.')
    if snapshot['size'] > (1 << 63) - 1 or snapshot['checkpointOffset'] != max(0, snapshot['size'] - 64):
        raise DebugUnavailable('Invalid prelaunch log snapshot.')
    if (type(snapshot['endsWithNewline']) is not bool
            or not isinstance(snapshot['checkpointSHA256'], str)
            or not re.fullmatch('[0-9a-f]{64}', snapshot['checkpointSHA256'])):
        raise DebugUnavailable('Invalid prelaunch log snapshot.')
    return dict(snapshot)


def safe_line(line):
    lower = line.lower()
    if any(key in lower for key in SENSITIVE_KEYS) or re.search(r'\buid\b', lower):
        return None
    if re.search(r'[\w.+-]+@[\w.-]+\.[a-z]{2,}', lower):
        return None
    if ('http://' in lower or 'https://' in lower) and '?' in line:
        return None
    if '\x00' in line:
        return None
    # Logs are untrusted text, not terminal control sequences.
    return ''.join(ch if ch == '\t' or ord(ch) >= 32 and not 127 <= ord(ch) <= 159 else ' ' for ch in line)


class UnityDiagnostics:
    """Coalesce known warning/error blocks after redaction, keeping one bounded stack per signature."""

    WINDOW = 60.0
    STACK_IDLE = 0.75
    MAX_EVENTS = 256
    MAX_STACK_LINES = 48
    MAX_STACK_BYTES = 32 * 1024

    def __init__(self, emit):
        self.emit = emit
        self.recent = OrderedDict()
        self.event_id = 0
        self.lines = []
        self.signature = None
        self.last_line = 0.0
        self.size = 0
        self.omitted = 0

    @staticmethod
    def _head(line):
        duplicate = ENTITY_DUPLICATE.fullmatch(line)
        if duplicate:
            return 'Name ' + duplicate[1] + ' is used.'
        return line if DIAGNOSTIC_HEAD.match(line) else None

    @staticmethod
    def _stack(line):
        return line.startswith(('at ', '(Filename:', 'BakedLua/', 'stack traceback:', '#0 ')) \
            or bool(STACK_FRAME.match(line))

    def _repeat_counts(self, events):
        counts = [(number, repeats) for _, number, repeats in events if repeats]
        for offset in range(0, len(counts), 16):
            rows = counts[offset:offset + 16]
            refs = ' '.join(f'#{number}+{repeats}' for number, repeats in rows)
            self.emit(f'[Unity repeats] repeated={sum(n for _, n in rows)} events={refs}')

    def _finish(self, now):
        if not self.lines:
            return
        key = self.signature.digest()
        if key in self.recent:
            start, number, repeats = self.recent[key]
            self.recent[key] = (start, number, repeats + 1)
        else:
            if len(self.recent) == self.MAX_EVENTS:
                self._repeat_counts([self.recent.popitem(last=False)[1]])
            self.event_id += 1
            self.recent[key] = (now, self.event_id, 0)
            self.emit(f'[Unity] [event #{self.event_id}] {self.lines[0]}')
            for line in self.lines[1:]:
                self.emit('[Unity] ' + line)
            if self.omitted:
                self.emit(f'[Unity] [event #{self.event_id}] stack_lines_omitted={self.omitted}')
        self.lines.clear()
        self.signature = None

    def _expire(self, now):
        expired = []
        while self.recent and now - next(iter(self.recent.values()))[0] >= self.WINDOW:
            expired.append(self.recent.popitem(last=False)[1])
        self._repeat_counts(expired)

    def feed(self, lines, now):
        self._expire(now)
        if self.lines and now - self.last_line >= self.STACK_IDLE:
            self._finish(now)
        for line in lines:
            stripped = line.strip()
            if not stripped:
                continue
            head = self._head(stripped)
            if self.lines and head is None and self._stack(stripped):
                raw = stripped.encode('utf-8')
                self.signature.update(b'\n' + raw)
                self.last_line = now
                if len(self.lines) < self.MAX_STACK_LINES and self.size + len(raw) <= self.MAX_STACK_BYTES:
                    self.lines.append(line)
                    self.size += len(raw)
                else:
                    self.omitted += 1
                if stripped.startswith('(Filename:'):
                    self._finish(now)
                continue
            self._finish(now)
            if head is None:
                self.emit('[Unity] ' + line)
                continue
            self.lines.append(line)
            self.signature = hashlib.sha256(head.encode('utf-8'))
            self.last_line = now
            self.size = len(line.encode('utf-8'))
            self.omitted = 0

    def close(self, now):
        self._expire(now)
        self._finish(now)
        self._repeat_counts(self.recent.values())
        self.recent.clear()


class LogTail:
    def __init__(self, path, snapshot):
        self.path = Path(path)
        self.snapshot = _validate_snapshot(snapshot)
        self.pending = bytearray()
        self.discard = self.snapshot['exists'] and not self.snapshot['endsWithNewline']

    def _consume(self, data):
        lines = []
        for ch in data:
            if ch == 10:
                if self.pending and not self.discard:
                    line = safe_line(self.pending.decode('utf-8', errors='replace'))
                    if line:
                        lines.append(line)
                self.pending.clear()
                self.discard = False
            elif ch != 13 and not self.discard:
                if len(self.pending) >= MAX_LINE:
                    self.discard = True
                    self.pending.clear()
                else:
                    self.pending.append(ch)
        return lines

    def poll(self):
        try:
            stream = _open_log(self.path)
        except FileNotFoundError:
            return []
        with stream:
            stat = os.fstat(stream.fileno())
            old = self.snapshot
            changed = not old['exists']
            if old['exists']:
                changed = ((stat.st_dev, stat.st_ino) != (old['device'], old['fileIndex'])
                           or stat.st_size < old['size'])
                if not changed:
                    stream.seek(old['checkpointOffset'])
                    checkpoint = stream.read(old['size'] - old['checkpointOffset'])
                    changed = hashlib.sha256(checkpoint).hexdigest() != old['checkpointSHA256']
            if changed:
                position = 0
                self.pending.clear()
                self.discard = False
            else:
                position = old['size']
            stream.seek(position)
            data = stream.read(MAX_POLL)
            position += len(data)
            lines = self._consume(data)
            offset = max(0, position - 64)
            stream.seek(offset)
            checkpoint = stream.read(position - offset)
            if len(checkpoint) != position - offset:
                raise DebugUnavailable('Player.log changed while being read; restart the debug console.')
            self.snapshot = {'exists': True, 'device': stat.st_dev, 'fileIndex': stat.st_ino,
                             'size': position, 'checkpointOffset': offset,
                             'checkpointSHA256': hashlib.sha256(checkpoint).hexdigest(),
                             'endsWithNewline': not checkpoint or checkpoint.endswith(b'\n')}
            return lines


    def drain_after_exit(self):
        """Drain the observed file only to its frozen EOF, never follow another session."""
        try:
            stream = _open_log(self.path)
        except FileNotFoundError:
            return
        with stream:
            stat = os.fstat(stream.fileno())
            old = self.snapshot
            if (not old['exists'] or (stat.st_dev, stat.st_ino) != (old['device'], old['fileIndex'])
                    or stat.st_size < old['size']):
                return
            position, limit = old['size'], stat.st_size
            def checkpoint_at(end):
                start = max(0, end - 64)
                stream.seek(start)
                value = stream.read(end - start)
                return hashlib.sha256(value).hexdigest() if len(value) == end - start else None
            checkpoint = old['checkpointSHA256']
            if checkpoint_at(position) != checkpoint:
                return
            end_checkpoint = checkpoint_at(limit)
            while position < limit:
                # Keep the original shared-read handle across yields: replacing Player.log
                # cannot switch this reader to the next game. Check same-file rewrites too.
                if (os.fstat(stream.fileno()).st_size < limit
                        or checkpoint_at(limit) != end_checkpoint
                        or checkpoint_at(position) != checkpoint):
                    return
                amount = min(MAX_POLL, limit - position)
                stream.seek(position)
                data = stream.read(amount)
                if len(data) != amount or checkpoint_at(limit) != end_checkpoint:
                    return
                position += len(data)
                checkpoint = checkpoint_at(position)
                yield self._consume(data)  # An empty filtered chunk is not EOF.


class ClientProcess:
    def __init__(self, pid, client_root, expected_created=None, *, memory_read=False):
        if os.name != 'nt':
            raise DebugUnavailable('The client debug console requires Windows.')
        if type(pid) is not int or not 0 < pid <= 0xffffffff:
            raise DebugUnavailable('Invalid client PID.')
        self.kernel = ctypes.WinDLL('kernel32', use_last_error=True)
        k = self.kernel
        k.OpenProcess.argtypes = [wintypes.DWORD, wintypes.BOOL, wintypes.DWORD]
        k.OpenProcess.restype = wintypes.HANDLE
        k.QueryFullProcessImageNameW.argtypes = [wintypes.HANDLE, wintypes.DWORD,
                                               wintypes.LPWSTR, ctypes.POINTER(wintypes.DWORD)]
        k.QueryFullProcessImageNameW.restype = wintypes.BOOL
        k.GetProcessTimes.argtypes = [wintypes.HANDLE] + [ctypes.POINTER(wintypes.FILETIME)] * 4
        k.GetProcessTimes.restype = wintypes.BOOL
        k.WaitForSingleObject.argtypes = [wintypes.HANDLE, wintypes.DWORD]
        k.WaitForSingleObject.restype = wintypes.DWORD
        k.GetExitCodeProcess.argtypes = [wintypes.HANDLE, ctypes.POINTER(wintypes.DWORD)]
        k.GetExitCodeProcess.restype = wintypes.BOOL
        k.CloseHandle.argtypes = [wintypes.HANDLE]
        k.CloseHandle.restype = wintypes.BOOL
        access = PROCESS_ACCESS | (0x410 if memory_read else 0)  # VM_READ and QUERY_INFORMATION only.
        self.handle = k.OpenProcess(access, False, pid)
        if not self.handle:
            raise _windows_error('Cannot inspect the client process; game remains running')
        try:
            image = ctypes.create_unicode_buffer(32768)
            size = wintypes.DWORD(len(image))
            if not k.QueryFullProcessImageNameW(self.handle, 0, image, ctypes.byref(size)):
                raise _windows_error('Cannot verify the client process path')
            expected = Path(client_root).resolve() / 'StarRail.exe'
            if os.path.normcase(str(Path(image.value).resolve())) != os.path.normcase(str(expected)):
                raise DebugUnavailable('PID belongs to a different executable; debug console unavailable.')
            stamps = [wintypes.FILETIME() for _ in range(4)]
            if not k.GetProcessTimes(self.handle, *(ctypes.byref(stamp) for stamp in stamps)):
                raise _windows_error('Cannot verify the client process creation time')
            self.created = stamps[0].dwLowDateTime | (stamps[0].dwHighDateTime << 32)
            if expected_created is not None and self.created != expected_created:
                raise DebugUnavailable('Client PID identity changed; debug console unavailable.')
        except BaseException:
            self.close()
            raise

    def alive(self):
        result = self.kernel.WaitForSingleObject(self.handle, 0)
        if result == 0:
            return False
        if result == 258:
            return True
        raise _windows_error('Cannot observe client process lifetime')

    def exit_code(self):
        code = wintypes.DWORD()
        if not self.kernel.GetExitCodeProcess(self.handle, ctypes.byref(code)):
            raise _windows_error('Cannot read client exit status')
        return code.value

    def close(self):
        if self.handle:
            self.kernel.CloseHandle(self.handle)
            self.handle = None

    def __enter__(self):
        return self

    def __exit__(self, *unused):
        self.close()


def get_process_created(pid, client_root):
    with ClientProcess(pid, client_root) as process:
        return process.created


def open_console():
    if os.name != 'nt':
        raise DebugUnavailable('The client debug console requires Windows.')
    kernel = ctypes.WinDLL('kernel32', use_last_error=True)
    kernel.SetConsoleTitleW.argtypes = [wintypes.LPCWSTR]
    kernel.SetConsoleTitleW.restype = wintypes.BOOL
    kernel.SetConsoleOutputCP.argtypes = [wintypes.UINT]
    kernel.SetConsoleOutputCP.restype = wintypes.BOOL
    if not kernel.SetConsoleTitleW('HKRPG Client Debug') or not kernel.SetConsoleOutputCP(65001):
        raise _windows_error('Cannot initialize the independent debug console')
    # The launcher redirects inherited streams to DEVNULL. Open only our new console.
    return open('CONOUT$', 'w', encoding='utf-8', errors='replace', buffering=1)


def _validate_ready_file(path, directory):
    path = Path(path)
    directory = Path(directory).resolve()
    if (not path.is_absolute() or path.parent.resolve() != directory or path.is_symlink()
            or not re.fullmatch(r'console-ready-[0-9a-f]{32}\.json', path.name)):
        raise DebugUnavailable('Invalid debug readiness destination.')
    return path


def _ready(path, payload):
    with Path(path).open('x', encoding='utf-8') as stream:
        json.dump(payload, stream, ensure_ascii=True)
        stream.flush()
        os.fsync(stream.fileno())


def start_wait_capture(pid, client_root, created, journal):
    path = Path(__file__).with_name('client-wait-state.py')
    spec = importlib.util.spec_from_file_location('hkrpg_client_wait_state', path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    destination = Path(journal).with_name(Path(journal).stem.replace('client-debug-', 'client-waits-') + '.jsonl')
    return module.Capture(pid, client_root, created, destination, ClientProcess)


def run_reader(pid, client_root, snapshot, process_created, ready_file, *, log_path=None,
               output_directory=None, process_factory=ClientProcess,
               console_factory=open_console, pause=time.sleep):
    """Overrides are test injection only; the production CLI exposes none of them."""
    output = journal = process = capture = None
    ready = None
    ready_written = False
    def emit(line):
        text = datetime.now(timezone.utc).isoformat(timespec='milliseconds') + ' ' + line + '\n'
        if output:
            output.write(text)
            output.flush()
        if journal:
            journal.write(text)
            journal.flush()
    diagnostics = UnityDiagnostics(emit)
    try:
        directory = Path(output_directory) if output_directory is not None else journal_directory()
        ready = _validate_ready_file(ready_file, directory)
        directory.mkdir(parents=True, exist_ok=True)
        output = console_factory()
        process = process_factory(pid, client_root, process_created)
        tail = LogTail(log_path if log_path is not None else player_log_path(), snapshot)
        first_lines = tail.poll()  # Verify read access before announcing ready.
        name = 'client-debug-' + datetime.now(timezone.utc).strftime('%Y%m%d-%H%M%S')
        name += '-' + str(pid) + '-' + uuid.uuid4().hex[:12] + '.log'
        journal = (directory / name).open('x', encoding='utf-8')
        emit(f'HKRPG CLIENT DEBUG - client PID {pid}; independent reader PID {os.getpid()}')
        emit('Source: client Player.log. Repeats refer to event numbers. Sensitive and oversized lines are omitted.')
        emit('Closing this window or pressing Ctrl+C stops only this reader.')
        _ready(ready, {'ready': True, 'pid': os.getpid(), 'clientPid': pid})
        ready_written = True
        try:
            capture = start_wait_capture(pid, client_root, process_created, directory / name)
            emit('Wait-state capture: separate client-waits journal; read-only, up to 10 minutes.')
        except Exception:
            emit('Wait-state capture unavailable; ordinary client logging continues.')
        diagnostics.feed(first_lines, time.monotonic())
        while True:
            if not process.alive():
                for chunk in tail.drain_after_exit():
                    diagnostics.feed(chunk, time.monotonic())
                diagnostics.close(time.monotonic())
                emit(f'Client exited; status {process.exit_code()}. Debug reader finished.')
                return 0
            diagnostics.feed(tail.poll(), time.monotonic())
            pause(.25)
    except KeyboardInterrupt:
        diagnostics.close(time.monotonic())
        emit('Debug reader stopped; the game was not stopped.')
        return 0
    except Exception as error:
        detail = str(error) if isinstance(error, DebugUnavailable) else type(error).__name__
        message = 'Client debug unavailable; game was not stopped. ' + detail
        if ready is not None and not ready_written:
            try:
                _ready(ready, {'ready': False, 'error': message})
            except OSError:
                pass
        try:
            diagnostics.close(time.monotonic())
            emit(message)
        except (OSError, ValueError):
            pass
        return 1
    finally:
        if capture is not None:
            capture.stop()
        if process is not None:
            process.close()
        if journal is not None:
            journal.close()
        if output is not None:
            output.close()


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--pid', type=int, required=True)
    parser.add_argument('--client-root', type=Path, required=True)
    parser.add_argument('--snapshot-json', required=True)
    parser.add_argument('--process-created', type=int, required=True)
    parser.add_argument('--ready-file', type=Path, required=True)
    args = parser.parse_args(argv)
    # Validation errors are handled inside run_reader so the launcher gets an error handshake.
    try:
        snapshot = json.loads(args.snapshot_json) if len(args.snapshot_json) <= 4096 else None
    except ValueError:
        snapshot = None
    return run_reader(args.pid, args.client_root, snapshot, args.process_created, args.ready_file)


if __name__ == '__main__':
    raise SystemExit(main())
