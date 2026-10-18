"""Bounded, external read-only diagnostics for one pinned 4.6 client process."""
import ctypes
from ctypes import wintypes as W
from datetime import datetime, timezone
import hashlib
import json
import math
from pathlib import Path
import struct
import threading
import time

PINNED_FILES = {
    'GameAssembly.dll': '8cc7380066343b6152069a6d1c90e230322aa78b780b1e738c13185b3692f1d3',
    'StarRail_Data/il2cpp_data/Metadata/global-metadata.dat':
        '09ce2093b78779d4bbe9dc866bb472438efd96cc6546b7cbc34edf4006736130',
}
MAX_SECONDS = 600
MAX_ENTRIES = 512


class NotReady(RuntimeError):
    """A loading or changing object cannot be interpreted safely yet."""


def verify_client(root):
    for relative, expected in PINNED_FILES.items():
        with (Path(root) / relative).open('rb') as stream:
            if hashlib.file_digest(stream, 'sha256').hexdigest() != expected:
                return False
    return True


class WindowsMemory:
    """Borrow a validated read-only handle. No memory writes or game calls."""
    def __init__(self, process, root):
        self.process = process
        self.root = Path(root).resolve()
        self.kernel = ctypes.WinDLL('kernel32', use_last_error=True)
        self.psapi = ctypes.WinDLL('psapi', use_last_error=True)
        self.kernel.ReadProcessMemory.argtypes = [W.HANDLE, ctypes.c_void_p, ctypes.c_void_p,
                                                  ctypes.c_size_t, ctypes.POINTER(ctypes.c_size_t)]
        self.kernel.ReadProcessMemory.restype = W.BOOL
        self.psapi.EnumProcessModulesEx.argtypes = [W.HANDLE, ctypes.c_void_p, W.DWORD,
                                                    ctypes.POINTER(W.DWORD), W.DWORD]
        self.psapi.EnumProcessModulesEx.restype = W.BOOL
        self.psapi.GetModuleFileNameExW.argtypes = [W.HANDLE, W.HMODULE, W.LPWSTR, W.DWORD]
        self.psapi.GetModuleFileNameExW.restype = W.DWORD

    def image_base(self):
        # Recheck the module on each sample; never reuse a pointer after unload.
        modules = (W.HMODULE * 1024)()
        needed = W.DWORD()
        if not self.psapi.EnumProcessModulesEx(self.process.handle, modules,
                ctypes.sizeof(modules), ctypes.byref(needed), 3):
            raise NotReady()
        if needed.value > ctypes.sizeof(modules):
            raise NotReady()
        for module in modules[:needed.value // ctypes.sizeof(W.HMODULE)]:
            name = ctypes.create_unicode_buffer(32768)
            if self.psapi.GetModuleFileNameExW(self.process.handle, module, name, len(name)):
                path = Path(name.value)
                if path.name.lower() == 'gameassembly.dll':
                    if path.resolve() != self.root / 'GameAssembly.dll':
                        raise NotReady()
                    return module
        raise NotReady()

    def read(self, address, size):
        if not 0x10000 <= address < 0x7fffffffffff or not 0 < size <= 4096:
            raise NotReady()
        target = ctypes.create_string_buffer(size)
        actual = ctypes.c_size_t()
        if not self.kernel.ReadProcessMemory(self.process.handle, address, target, size,
                ctypes.byref(actual)) or actual.value != size:
            raise NotReady()
        return target.raw


def snapshot(memory):
    """Only UI flags, dictionary counts, response IDs and wait limits leave memory."""
    def read(address, size):
        if not 0x10000 <= address < 0x7fffffffffff or not 0 < size <= 4096:
            raise NotReady()
        value = memory.read(address, size)
        if len(value) != size:
            raise NotReady()
        return value

    def q(address): return struct.unpack('<Q', read(address, 8))[0]
    def i(address): return struct.unpack('<i', read(address, 4))[0]
    base = memory.image_base()
    static = q(base + 0x93434a8)
    ui = q(static + 0x1398)
    if not ui:
        return {'uiReady': False}
    block = q(ui + 0xd0)
    result = {'uiReady': True, 'loading': bool(read(ui + 0x124, 1)[0]),
              'storyMode': bool(read(ui + 0x12c, 1)[0]),
              'sceneUnloaded': bool(read(ui + 0x12f, 1)[0]),
              'waits': [], 'dictionaryCounts': {}}
    if not block:
        return result
    result['blockFlags'] = list(read(block + 0x3c, 2))
    for offset in (0x10, 0x20):
        dictionary = q(block + offset)
        if not dictionary:
            continue
        version = i(dictionary + 0x24)
        count, free = i(dictionary + 0x20), i(dictionary + 0x2c)
        if not 0 <= free <= count <= MAX_ENTRIES:
            raise NotReady()
        result['dictionaryCounts'][hex(offset)] = count - free
        if offset == 0x10 and count:
            entries = q(dictionary + 0x18)
            if not entries or not count <= q(entries + 0x18) <= MAX_ENTRIES * 4:
                raise NotReady()
            for idx in range(count):
                row = entries + 0x20 + idx * 24
                if i(row) < 0:  # Dictionary's free slots are not live objects.
                    continue
                entry = q(row + 16)
                kind = i(entry + 0x38)
                response = struct.unpack('<H', read(entry + 0x3e, 2))[0]
                timeout = struct.unpack('<f', read(entry + 0x40, 4))[0]
                if kind != 2 or response == 0 or not math.isfinite(timeout) or timeout < 0:
                    raise NotReady()
                result['waits'].append({'responseId': response, 'timeoutSeconds': timeout})
            if len(result['waits']) != count - free:
                raise NotReady()
        if (version != i(dictionary + 0x24) or dictionary != q(block + offset)
                or count != i(dictionary + 0x20) or free != i(dictionary + 0x2c)):
            raise NotReady()
    if ui != q(static + 0x1398) or block != q(ui + 0xd0):
        raise NotReady()
    result['waits'].sort(key=lambda row: row['responseId'])
    return result


def capture(pid, root, created, destination, process_factory, stop, *,
            verify=verify_client, memory_factory=WindowsMemory,
            clock=time.monotonic, max_seconds=MAX_SECONDS):
    """Separate journal: a paused/selected console cannot block these samples."""
    started = clock()
    with Path(destination).open('x', encoding='utf-8', buffering=1) as journal:
        def emit(state):
            row = {'time': datetime.now(timezone.utc).isoformat(timespec='milliseconds'),
                   'elapsedSeconds': round(clock() - started, 3), 'clientPid': pid,
                   'readOnly': True, **state}
            journal.write(json.dumps(row, allow_nan=False, separators=(',', ':')) + '\n')
            journal.flush()
        emit({'status': 'starting', 'limitSeconds': max_seconds})
        try:
            if not verify(root):
                emit({'status': 'unsupported-client'})
                return
            # A distinct handle validates EXE path AND creation time again, preventing PID reuse.
            with process_factory(pid, root, created, memory_read=True) as process:
                memory = memory_factory(process, root)
                previous = None
                while not stop.is_set() and clock() - started < max_seconds:
                    if not process.alive():
                        emit({'status': 'client-exited', 'exitCode': process.exit_code()})
                        return
                    try:
                        state = {'status': 'sample', **snapshot(memory)}
                    except NotReady:
                        state = {'status': 'objects-not-ready'}
                    # Keep wait duration visible, but deduplicate idle/loading-not-ready states.
                    if state != previous or state.get('waits'):
                        emit(state)
                        previous = state
                    stop.wait(1)
                emit({'status': 'reader-stopped' if stop.is_set() else 'capture-expired'})
        except Exception as error:
            # Never write exception text containing paths, addresses or arbitrary memory.
            emit({'status': 'unavailable', 'errorType': type(error).__name__})


class Capture:
    def __init__(self, *args):
        self.signal = threading.Event()
        self.thread = threading.Thread(target=self._run, args=args, name='client-wait-log', daemon=True)
        self.thread.start()

    def _run(self, *args):
        try:
            capture(*args, self.signal)
        except OSError:
            pass  # An unwritable diagnostic file must not stop the ordinary log reader.

    def stop(self):
        self.signal.set()
        self.thread.join(timeout=2)
