"""构建/安装由 StarRail.exe 创建的调试控制台；保留原桌面入口及现有游戏文件。"""
import argparse
import ctypes
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / '.tools/client-analysis-libs'))
CLIENT_FILES = {
    'StarRail.exe': '1a300c978b82a060716b6081ffb78249f0f90b1002d616c0f22a5589c9e3c02e',
    'UnityPlayer.dll': '1765b4dd185240a0c3c1d14ebd7a9786d8bcd75af6f47bac27fd5b2cad7b2112',
    'GameAssembly.dll': '8cc7380066343b6152069a6d1c90e230322aa78b780b1e738c13185b3692f1d3',
}


def sha(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def build(directory, *, test=False):
    if os.name != 'nt' or ctypes.sizeof(ctypes.c_void_p) != 8:
        raise RuntimeError('只支持已核对的 Windows x64 客户端')
    import pefile
    compiler = shutil.which('gcc')
    if not compiler or subprocess.check_output([compiler, '-dumpmachine'], text=True).strip() != 'x86_64-w64-mingw32':
        raise RuntimeError('需要本机 x86_64-w64-mingw32 GCC')
    buffer = ctypes.create_unicode_buffer(32768)
    if not ctypes.windll.kernel32.GetSystemDirectoryW(buffer, len(buffer)):
        raise ctypes.WinError()
    system = Path(buffer.value) / 'version.dll'
    pe = pefile.PE(str(system))
    exports = {entry.name.decode('ascii'): entry.ordinal for entry in pe.DIRECTORY_ENTRY_EXPORT.symbols if entry.name}
    directory.mkdir(parents=True, exist_ok=True)
    forwarders = directory / 'version.def'
    module_path = system.with_suffix('').as_posix()
    forwarders.write_text('LIBRARY version\nEXPORTS\n' + ''.join(
        f'{name}="{module_path}.{name}" @{ordinal}\n' for name, ordinal in exports.items()), encoding='ascii')
    library = directory / 'version.dll'
    arguments = [compiler, '-shared', '-O2', '-Wall', '-Wextra', '-Werror', '-Wl,--no-insert-timestamp']
    if test:
        arguments += ['-DHKRPG_CONSOLE_TEST']
    arguments += ['-o', str(library), str(ROOT / 'client-debug/console.c'), str(forwarders),
                  '-lshell32', '-lole32', '-luuid']
    subprocess.run(arguments, check=True)
    report = {'library': str(library.resolve()), 'librarySHA256': sha(library),
        'sourceSHA256': sha(ROOT / 'client-debug/console.c'), 'systemVersionDLL': str(system),
        'systemVersionSHA256': sha(system), 'systemExports': exports, 'testBuild': test,
        'clientVerified': False}
    (directory / 'build.json').write_text(json.dumps(report, indent=2) + '\n', encoding='utf8')
    return report


def install(report, client):
    import pefile
    if report['testBuild']:
        raise ValueError('拒绝安装测试日志路径可覆盖的 DLL')
    client = client.resolve()
    for relative, expected in CLIENT_FILES.items():
        if sha(client / relative) != expected:
            raise ValueError('客户端文件与已核对版本不符: ' + relative)
    # Check the real dependency that makes the normal desktop executable load this DLL.
    pe = pefile.PE(str(client / 'UnityPlayer.dll'), fast_load=True)
    pe.parse_data_directories(directories=[1])
    if not any(entry.dll.lower() == b'version.dll' for entry in pe.DIRECTORY_ENTRY_IMPORT):
        raise ValueError('UnityPlayer 未导入已核对的 VERSION.dll')
    running = subprocess.check_output(['tasklist.exe', '/NH', '/FO', 'CSV', '/FI',
        'IMAGENAME eq StarRail.exe'], creationflags=subprocess.CREATE_NO_WINDOW)
    if b'starrail.exe' in running.lower():
        raise RuntimeError('客户端正在运行，必须退出后再安装')
    source, target = Path(report['library']), client / 'version.dll'
    if sha(source) != report['librarySHA256']:
        raise ValueError('待安装 DLL 在构建后发生变化')
    if target.exists():
        if sha(target) != report['librarySHA256']:
            raise FileExistsError('已有其他 version.dll，拒绝覆盖')
    else:
        with source.open('rb') as incoming, target.open('xb') as outgoing:
            shutil.copyfileobj(incoming, outgoing)
    if sha(target) != report['librarySHA256']:
        raise RuntimeError('客户端控制台安装校验失败')
    if any(sha(client / relative) != expected for relative, expected in CLIENT_FILES.items()):
        raise RuntimeError('安装后既有游戏文件校验失败')
    installed = report | {'clientRoot': str(client), 'installedDLL': str(target),
        'existingClientFilesUnchanged': True, 'desktopShortcutChanged': False, 'clientVerified': False}
    (source.parent / 'installation.json').write_text(json.dumps(installed, indent=2) + '\n', encoding='utf8')
    return installed


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output-dir', type=Path, default=ROOT / 'build/client-debug')
    parser.add_argument('--install', type=Path)
    args = parser.parse_args()
    result = build(args.output_dir.resolve())
    if args.install:
        result = install(result, args.install)
    print(json.dumps(result, ensure_ascii=True))
