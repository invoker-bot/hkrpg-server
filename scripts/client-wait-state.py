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
    """只输出限定的界面状态、等待编号及脱敏输入锁；不输出地址或任意字符串。"""
    def read(address, size):
        if not 0x10000 <= address < 0x7fffffffffff or not 0 < size <= 4096:
            raise NotReady()
        value = memory.read(address, size)
        if len(value) != size:
            raise NotReady()
        return value

    def q(address): return struct.unpack('<Q', read(address, 8))[0]
    def i(address): return struct.unpack('<i', read(address, 4))[0]
    def f(address): return struct.unpack('<f', read(address, 4))[0]
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
        if offset == 0x20 and count:
            entries = q(dictionary + 0x18)
            if not entries or not count <= q(entries + 0x18) <= MAX_ENTRIES * 4:
                raise NotReady()
            fixed = []
            for idx in range(count):
                row = entries + 0x20 + idx * 24
                if i(row) < 0:
                    continue
                identity, entry = i(row + 8), q(row + 16)
                kind, duration, delay = i(entry + 0x2c), f(entry + 0x34), f(entry + 0x28)
                silent, callback, label = read(entry + 0x38, 1)[0], q(entry + 0x10), q(entry + 0x18)
                if (identity < 0 or kind != 1 or silent not in (0, 1)
                        or not math.isfinite(duration) or duration < 0
                        or not math.isfinite(delay) or delay < 0):
                    raise NotReady()
                record = {'id': identity, 'kind': kind, 'durationSeconds': duration,
                          'delaySeconds': delay, 'silent': bool(silent), 'callbackPresent': bool(callback)}
                if label:
                    length = i(label + 0x10)
                    if not 0 <= length <= 160:
                        raise NotReady()
                    if length:
                        record['originSHA256'] = hashlib.sha256(read(label + 0x14, length * 2)).hexdigest()
                fixed.append(record)
            if len(fixed) != count - free or len({row['id'] for row in fixed}) != len(fixed):
                raise NotReady()
            result['fixedBlocks'] = sorted(fixed, key=lambda row: row['id'])
        if (version != i(dictionary + 0x24) or dictionary != q(block + offset)
                or count != i(dictionary + 0x20) or free != i(dictionary + 0x2c)):
            raise NotReady()
    if ui != q(static + 0x1398) or block != q(ui + 0xd0):
        raise NotReady()
    if result.get('fixedBlocks'):
        # 仅对固定输入锁补采焦点和已核对的 UIFullScreenBlock 目标编号。
        # 未列入白名单的窗口名称只标记为 other，避免写入任意游戏文本。
        # DialogueManager.TryLockInput 保存自身的固定锁编号于 +0xa8，
        # TryUnlockInput 只释放此编号；0 表示它没有持有编号。
        dialogue = q(static + 0x14f0)
        if dialogue:
            identity = i(dialogue + 0xa8)
            if identity < 0:
                raise NotReady()
            result['dialogueLock'] = {
                'blockId': identity if identity else None,
                'matchesFixedBlock': bool(identity) and any(
                    row['id'] == identity for row in result['fixedBlocks']),
            }
            if identity != i(dialogue + 0xa8):
                raise NotReady()
        if dialogue != q(static + 0x14f0):
            raise NotReady()
        # 应用失焦时 InControlActionsManager 会持有另一把独立的固定锁。
        # 同时记录调试界面持有的编号，避免把正常切窗产生的锁误认成对话锁。
        controls = q(static + 0x13d0)
        if controls:
            action_id, debug_id = i(controls + 0x7c), i(controls + 0x68)
            if action_id < 0 or debug_id < 0:
                raise NotReady()
            fixed_ids = {row['id'] for row in result['fixedBlocks']}
            result['inputLocks'] = {
                'actionBlockId': action_id if action_id else None,
                'actionMatchesFixedBlock': bool(action_id) and action_id in fixed_ids,
                'debugBlockId': debug_id if debug_id else None,
                'debugMatchesFixedBlock': bool(debug_id) and debug_id in fixed_ids,
            }
            if action_id != i(controls + 0x7c) or debug_id != i(controls + 0x68):
                raise NotReady()
        if controls != q(static + 0x13d0):
            raise NotReady()
        focus = q(ui + 0xb0)
        if focus:
            label = q(focus + 0x30)
            if label:
                length = i(label + 0x10)
                if not 0 <= length <= 160:
                    raise NotReady()
                try:
                    name = read(label + 0x14, length * 2).decode('utf-16le') if length else ''
                except UnicodeDecodeError:
                    raise NotReady() from None
                allowed = {'TalkDialog', 'PassiveSubmitMissionItemPage',
                           'NewPassiveSubmitMissionItemPage', 'MazeMainPage'}
                result['focusedController'] = name if name in allowed else 'other'
        manager = q(static + 0x1468)
        module = q(manager + 0x500) if manager else 0
        targets = q(module + 0x20) if module else 0
        if targets:
            version = i(targets + 0x24)
            count, free = i(targets + 0x20), i(targets + 0x2c)
            if not 0 <= free <= count <= MAX_ENTRIES:
                raise NotReady()
            entries = q(targets + 0x18) if count else 0
            if count and (not entries or not count <= q(entries + 0x18) <= MAX_ENTRIES * 4):
                raise NotReady()
            seen, target = 0, 0
            for idx in range(count):
                row = entries + 0x20 + idx * 24
                if i(row) < 0:
                    continue
                seen += 1
                if i(row + 8) == 12:  # GamePlayLockTarget.UIFullScreenBlock
                    if target:
                        raise NotReady()
                    target = q(row + 16)
            if seen != count - free or version != i(targets + 0x24):
                raise NotReady()
            if target:
                locked, identity, present = read(target + 0x24, 1)[0], i(target + 0x28), read(target + 0x2c, 1)[0]
                if locked not in (0, 1) or present not in (0, 1) or (present and identity < 0):
                    raise NotReady()
                result['uiLockTarget'] = {'isLocked': bool(locked), 'blockId': identity if present else None,
                                          'idPresent': bool(present),
                                          'matchesFixedBlock': bool(present) and any(
                                              row['id'] == identity for row in result['fixedBlocks'])}
                if (locked != read(target + 0x24, 1)[0] or identity != i(target + 0x28)
                        or present != read(target + 0x2c, 1)[0]):
                    raise NotReady()
            if (manager != q(static + 0x1468) or module != q(manager + 0x500)
                    or targets != q(module + 0x20)):
                raise NotReady()
        if ui != q(static + 0x1398) or block != q(ui + 0xd0) or focus != q(ui + 0xb0):
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
