"""独立的本地客户端启动检查；仅恢复清单确认的调试 DLL。"""
import argparse
import csv
import ctypes
from ctypes import wintypes
import hashlib
import io
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile
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
    if data.get('schema') != 1 or set(data.get('clientFiles', {})) != REQUIRED_FILES:
        raise ValueError('客户端清单必须完整包含已核对的 7 个文件')
    root = Path(data['clientRoot'])
    if not root.is_absolute() or not root.is_dir():
        raise ValueError('客户端清单目录无效')
    digests = list(data['clientFiles'].values()) + [data['debugConsole']['sha256']]
    if any(not isinstance(value, str) or not re.fullmatch('[0-9a-f]{64}', value) for value in digests):
        raise ValueError('客户端清单 SHA256 无效')
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


def launch_client(root):
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
    info.lpVerb = 'open'
    info.lpFile = str(root / 'StarRail.exe')
    info.lpDirectory = str(root)
    info.nShow = 1  # SW_SHOWNORMAL; the game owns its debug console.
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


def restore_console(root, backup, expected, *, dry_run=False):
    target = checked_path(root, 'version.dll')
    if target.exists():
        if sha(target) != expected:
            raise ValueError('已有其他 version.dll，拒绝覆盖或启动')
        return 'verified'
    candidates = [backup]
    candidates.extend(p for p in sorted(root.glob('version.dll.*'))
                      if re.fullmatch(r'version\.dll\.[0-9]{1,10}', p.name)
                      and int(p.suffix[1:]) <= 0xffffffff)
    source = next((p for p in candidates
                   if p.is_file() and not p.is_symlink() and sha(p) == expected), None)
    if source is None:
        raise ValueError('没有 SHA256 匹配的 version.dll 恢复来源')
    if dry_run:
        return 'would-restore'
    # Publish a complete file atomically, with no replacement if another writer wins.
    # NTFS hard links work without administrator rights; unlinking the temporary name
    # leaves one ordinary file at version.dll.
    temporary = None
    try:
        with tempfile.NamedTemporaryFile(prefix='.hkrpg-version-', suffix='.tmp',
                                         dir=root, delete=False) as outgoing:
            temporary = Path(outgoing.name)
            with source.open('rb') as incoming:
                while chunk := incoming.read(1024 * 1024):
                    outgoing.write(chunk)
            outgoing.flush()
            os.fsync(outgoing.fileno())
        if sha(temporary) != expected:
            raise ValueError('version.dll 恢复来源在读取期间发生变化')
        require_stopped()
        os.link(temporary, target)
    finally:
        if temporary is not None:
            temporary.unlink(missing_ok=True)
    return 'restored'


def start(manifest, *, dry_run=False, repair_only=False):
    data, root, backup = load_manifest(Path(manifest).resolve())
    require_stopped()
    require_ready(data['service'])
    for relative, expected in data['clientFiles'].items():
        if sha(checked_path(root, relative)) != expected:
            raise ValueError('客户端文件已变化，拒绝覆盖或启动: ' + relative)
    state = restore_console(root, backup, data['debugConsole']['sha256'], dry_run=dry_run)
    result = {'dryRun': dry_run, 'repairOnly': repair_only, 'console': state,
              'clientRoot': str(root), 'clientFilesVerified': len(REQUIRED_FILES)}
    if not dry_run and not repair_only:
        require_stopped()
        result['pid'] = launch_client(root)
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--manifest', type=Path, default=ROOT / '.local/client/patch-manifest.json')
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument('--dry-run', action='store_true')
    mode.add_argument('--repair-only', action='store_true')
    args = parser.parse_args()
    try:
        print(json.dumps(start(args.manifest, dry_run=args.dry_run, repair_only=args.repair_only), ensure_ascii=True))
        return 0
    except Exception as error:
        print(json.dumps({'error': str(error)}, ensure_ascii=True))
        return 1


if __name__ == '__main__':
    raise SystemExit(main())
