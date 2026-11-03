"""本地客户端启动检查与独立日志窗口；不向游戏进程加载调试 DLL。"""
import argparse
import csv
import ctypes
from ctypes import wintypes
import hashlib
import io
import importlib.util
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import time
import uuid
import urllib.request
from urllib.parse import urlsplit

ROOT = Path(__file__).resolve().parents[1]
REQUIRED_FILES = frozenset({
    'StarRail.exe', 'UnityPlayer.dll', 'GameAssembly.dll',
    'StarRail_Data/Plugins/x86_64/AccountPlatNative.dll',
    'StarRail_Data/StreamingAssets/MiHoYoSDKRes/PC/mihoyo_sdk_res',
    'StarRail_Data/StreamingAssets/ClientConfig.bytes',
    'StarRail_Data/il2cpp_data/Metadata/global-metadata.dat',
})


def sha(path):
    with Path(path).open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def client_running():
    if os.name != 'nt':
        raise RuntimeError('仅支持 Windows 客户端')
    output = subprocess.check_output(['tasklist.exe', '/NH', '/FO', 'CSV', '/FI',
        'IMAGENAME eq StarRail.exe'], creationflags=subprocess.CREATE_NO_WINDOW)
    return any(row and row[0].lower() == 'starrail.exe'
               for row in csv.reader(io.StringIO(output.decode('utf8', errors='replace'))))


def require_stopped():
    if client_running():
        raise RuntimeError('客户端正在运行，请先退出游戏后再从原桌面图标启动。')


def validate_ready(body):
    if len(body) > 65536:
        raise ValueError('服务状态响应过长')
    data = json.loads(body)
    if not isinstance(data, dict) or data.get('ready') is not True:
        raise ValueError('服务尚未 ready')


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *args, **kwargs):
        return None


def require_ready(service):
    try:
        opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), NoRedirect())
        with opener.open(service['readyUrl'], timeout=5) as response:
            if response.status != 200:
                raise ValueError('服务状态不是 200')
            validate_ready(response.read(65537))
    except Exception as error:
        raise RuntimeError('本地服务器未就绪。请先启动 Docker Desktop，然后运行：\n'
                           + service['startCommand']) from error


def checked_path(root, relative):
    target = root / relative
    if target.is_symlink() or not target.resolve().is_relative_to(root.resolve()):
        raise ValueError('拒绝客户端目录外的链接: ' + relative)
    return target


def load_manifest(path):
    data = json.loads(path.read_text(encoding='utf-8-sig'))
    if data.get('schema') != 2 or set(data.get('clientFiles', {})) != REQUIRED_FILES:
        raise ValueError('客户端清单必须为版本 2，并完整包含已核对的 7 个文件')
    root = Path(data['clientRoot'])
    if not root.is_absolute() or not root.is_dir():
        raise ValueError('客户端清单目录无效')
    if data.get('debugConsole', {}).get('mode') != 'external':
        raise ValueError('仅支持独立客户端日志窗口')
    digests = list(data['clientFiles'].values()) + [data['debugConsole']['sha256']]
    if any(not isinstance(value, str) or not re.fullmatch('[0-9a-f]{64}', value) for value in digests):
        raise ValueError('客户端清单 SHA256 无效')
    lua_files = data.get('clientLuaFiles', {})
    lua_prefix = 'StarRail_Data/Persistent/Lua/Windows/'
    if not isinstance(lua_files, dict) or len(lua_files) not in (0, 4):
        raise ValueError('地图兼容资源清单不完整')
    if lua_files and (lua_prefix + 'M_LuaV.bytes' not in lua_files
                     or sum(name.startswith(lua_prefix + 'LuaV_') for name in lua_files) != 1):
        raise ValueError('地图兼容资源缺少入口或索引')
    for relative, expected in lua_files.items():
        if (not relative.startswith(lua_prefix)
                or not re.fullmatch(r'(M_LuaV|LuaV_[a-f0-9]{32}|[a-f0-9]{32})\.bytes', relative[len(lua_prefix):])
                or not isinstance(expected, str) or not re.fullmatch('[0-9a-f]{64}', expected)):
            raise ValueError('地图兼容资源路径或 SHA256 无效')
    service = data['service']
    url = urlsplit(service['readyUrl'])
    if (url.scheme != 'http' or url.hostname != '127.0.0.1' or url.username or url.password
            or url.path != '/health/ready' or url.query or url.fragment):
        raise ValueError('清单服务地址必须是本机 /health/ready')
    if not isinstance(service['startCommand'], str) or not service['startCommand']:
        raise ValueError('清单缺少服务启动提示')
    backup = (path.parent / data['debugConsole']['backupPath']).resolve()
    if not backup.is_relative_to(path.parent.resolve()):
        raise ValueError('清单备份必须保存在清单目录内')
    return data, root.resolve(), backup


def _shell_launch(file, directory, *, parameters=None, verb='open', show=1):
    if os.name != 'nt':
        raise RuntimeError('仅支持 Windows 客户端')

    class ShellExecuteInfo(ctypes.Structure):
        _fields_ = [('cbSize', wintypes.DWORD), ('fMask', wintypes.ULONG),
            ('hwnd', wintypes.HWND), ('lpVerb', wintypes.LPCWSTR),
            ('lpFile', wintypes.LPCWSTR), ('lpParameters', wintypes.LPCWSTR),
            ('lpDirectory', wintypes.LPCWSTR), ('nShow', ctypes.c_int),
            ('hInstApp', wintypes.HINSTANCE), ('lpIDList', ctypes.c_void_p),
            ('lpClass', wintypes.LPCWSTR), ('hkeyClass', wintypes.HKEY),
            ('dwHotKey', wintypes.DWORD), ('hIconOrMonitor', wintypes.HANDLE),
            ('hProcess', wintypes.HANDLE)]

    shell = ctypes.WinDLL('shell32', use_last_error=True)
    kernel = ctypes.WinDLL('kernel32', use_last_error=True)
    shell.ShellExecuteExW.argtypes = [ctypes.POINTER(ShellExecuteInfo)]
    shell.ShellExecuteExW.restype = wintypes.BOOL
    kernel.GetProcessId.argtypes = [wintypes.HANDLE]
    kernel.GetProcessId.restype = wintypes.DWORD
    kernel.CloseHandle.argtypes = [wintypes.HANDLE]
    kernel.CloseHandle.restype = wintypes.BOOL
    info = ShellExecuteInfo()
    info.cbSize = ctypes.sizeof(info)
    # Normal Shell open honors the EXE's requireAdministrator manifest and UAC.
    # Keep a handle for its PID; finish dispatch before this short-lived launcher exits.
    info.fMask = 0x40 | 0x100  # SEE_MASK_NOCLOSEPROCESS | SEE_MASK_NOASYNC
    info.lpVerb = verb
    info.lpFile = str(file)
    info.lpParameters = parameters
    info.lpDirectory = str(directory)
    info.nShow = show
    if not shell.ShellExecuteExW(ctypes.byref(info)):
        error = ctypes.get_last_error()
        if error == 1223:  # ERROR_CANCELLED
            raise RuntimeError('已取消 Windows 权限确认，游戏未启动；请再次点击原桌面图标并按需确认。')
        raise ctypes.WinError(error)
    if not info.hProcess:
        raise RuntimeError('Windows 未返回客户端进程句柄，请确认游戏是否已启动。')
    try:
        pid = kernel.GetProcessId(info.hProcess)
        if not pid:
            raise ctypes.WinError(ctypes.get_last_error())
        return pid
    finally:
        kernel.CloseHandle(info.hProcess)


def launch_client(root):
    return _shell_launch(root / 'StarRail.exe', root)


def is_administrator():
    if os.name != 'nt':
        raise RuntimeError('仅支持 Windows 客户端')
    shell = ctypes.WinDLL('shell32', use_last_error=True)
    shell.IsUserAnAdmin.argtypes = []
    shell.IsUserAnAdmin.restype = wintypes.BOOL
    return bool(shell.IsUserAnAdmin())


def relaunch_as_admin(manifest):
    # Move the game's existing UAC boundary to its launcher so the independent
    # diagnostic reader inherits the same integrity level. Never bypass UAC.
    powershell = Path(os.environ['SystemRoot']) / 'System32/WindowsPowerShell/v1.0/powershell.exe'
    arguments = ['-NoLogo', '-NoProfile', '-ExecutionPolicy', 'Bypass', '-WindowStyle', 'Hidden',
                 '-File', str(ROOT / 'scripts/start-local-client.ps1'),
                 '-Manifest', str(Path(manifest).resolve()), '-PythonExecutable', sys.executable,
                 '-ElevationAttempted']
    return _shell_launch(powershell, ROOT, parameters=subprocess.list2cmdline(arguments),
                         verb='runas', show=0)


def retire_console_proxy(root, expected, *, dry_run=False):
    target = checked_path(root, 'version.dll')
    if not target.exists():
        return {'proxy': 'absent'}
    if sha(target) != expected:
        raise ValueError('已有其他 version.dll，拒绝移动或启动')
    if dry_run:
        return {'proxy': 'would-retire'}
    # A same-directory rename preserves the exact original bytes on this volume.
    # Windows rename refuses to replace a destination created concurrently.
    retired = checked_path(root, 'version.dll.hkrpg-retired-' + uuid.uuid4().hex)
    require_stopped()
    target.rename(retired)
    if sha(retired) != expected:
        # If another writer replaced the source after validation, put it back;
        # never replace a newly appeared file at the original name.
        retired.rename(target)
        raise ValueError('version.dll 在检查后发生变化，已恢复原位置')
    return {'proxy': 'retired', 'retiredProxy': str(retired)}


def console_helper():
    path = Path(__file__).with_name('client-log-console.py')
    spec = importlib.util.spec_from_file_location('hkrpg_client_log_console', path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def capture_client_log():
    return console_helper().capture_log_snapshot()


def launch_console(root, pid, snapshot):
    helper = console_helper()
    created = helper.get_process_created(pid, root)
    directory = Path(os.environ['LOCALAPPDATA']) / 'HKRPG'
    directory.mkdir(parents=True, exist_ok=True)
    ready = directory / ('console-ready-' + uuid.uuid4().hex + '.json')
    arguments = [sys.executable, '-X', 'utf8', str(Path(__file__).with_name('client-log-console.py')),
                 '--pid', str(pid), '--client-root', str(root), '--process-created', str(created),
                 '--snapshot-json', json.dumps(snapshot, separators=(',', ':')), '--ready-file', str(ready)]
    process = subprocess.Popen(arguments, creationflags=subprocess.CREATE_NEW_CONSOLE,
                               stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL,
                               stderr=subprocess.DEVNULL, close_fds=True)
    try:
        deadline = time.monotonic() + 8
        while time.monotonic() < deadline:
            if ready.exists():
                try:
                    result = json.loads(ready.read_text(encoding='utf-8'))
                except (ValueError, PermissionError):
                    time.sleep(.05)
                    continue
                if result.get('ready') is not True:
                    raise RuntimeError('客户端日志窗口未就绪：' + str(result.get('error', '未知错误')))
                if result.get('pid') != process.pid or result.get('clientPid') != pid:
                    raise RuntimeError('客户端日志窗口进程核对失败')
                return process.pid
            if process.poll() is not None:
                raise RuntimeError('客户端日志窗口提前退出，游戏进程未停止')
            time.sleep(.05)
        raise RuntimeError('客户端日志窗口启动超时，游戏进程未停止')
    finally:
        ready.unlink(missing_ok=True)


def start(manifest, *, dry_run=False, repair_only=False):
    data, root, _backup = load_manifest(Path(manifest).resolve())
    require_stopped()
    require_ready(data['service'])
    for relative, expected in data['clientFiles'].items():
        if sha(checked_path(root, relative)) != expected:
            raise ValueError('客户端文件已变化，拒绝覆盖或启动: ' + relative)
    for relative, expected in data.get('clientLuaFiles', {}).items():
        if sha(checked_path(root, relative)) != expected:
            raise ValueError('地图兼容资源已变化，请重新核对: ' + relative)
    state = retire_console_proxy(root, data['debugConsole']['sha256'], dry_run=dry_run)
    result = {'dryRun': dry_run, 'repairOnly': repair_only, 'console': 'external',
              'clientRoot': str(root), 'clientFilesVerified': len(REQUIRED_FILES), **state}
    if data.get('clientLuaFiles'):
        result['clientLuaFilesVerified'] = len(data['clientLuaFiles'])
    if not dry_run and not repair_only:
        snapshot = None
        try:
            snapshot = capture_client_log()
        except Exception as error:
            result['consoleWarning'] = str(error)
        require_stopped()
        result['pid'] = launch_client(root)
        if snapshot is not None:
            try:
                result['consolePid'] = launch_console(root, result['pid'], snapshot)
            except Exception as error:
                # Logging is optional. Never report game launch failure or
                # terminate it on a reader failure; skip readers without a baseline.
                result['consoleWarning'] = str(error)
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--manifest', type=Path, default=ROOT / '.local/client/patch-manifest.json')
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument('--dry-run', action='store_true')
    mode.add_argument('--repair-only', action='store_true')
    parser.add_argument('--elevated-start', action='store_true', help=argparse.SUPPRESS)
    args = parser.parse_args()
    try:
        if not args.dry_run and not args.repair_only and not is_administrator():
            if args.elevated_start:
                raise RuntimeError('启动器未取得管理员权限，已停止重试；游戏未启动。')
            start(args.manifest, dry_run=True)  # Validate files and server before the UAC prompt.
            result = {'elevatedLauncherPid': relaunch_as_admin(args.manifest),
                      'clientStarted': False, 'delegated': True}
        else:
            result = start(args.manifest, dry_run=args.dry_run, repair_only=args.repair_only)
        print(json.dumps(result, ensure_ascii=True))
        return 0
    except Exception as error:
        print(json.dumps({'error': str(error)}, ensure_ascii=True))
        return 1


if __name__ == '__main__':
    raise SystemExit(main())
