"""按客户端现用 AudioV 清单核对配音，包括启动器基础包未包含的章节包。

默认只读；补装只接受清单内、长度和 MD5 都匹配的文件，不能用基础包清单代替完整性。
不更新索引、客户端补丁或服务端，也不启动游戏。
"""
import argparse
import hashlib
import importlib.util
import json
import os
from pathlib import Path, PurePosixPath
import re
import shutil
import uuid

ROOT = Path(__file__).resolve().parents[1]
AUDIO = Path('StarRail_Data/Persistent/Audio/AudioPackage/Windows')
INDEX = 'AudioV_f4e0fbb3ae338c02662c48a07a95226c.bytes'
INDEX_SHA256 = 'd32a82ee300c9daf1a0ccd988bfc11536b11608ffd0ba5a129563a0a24bb18b3'
POINTER_SHA256 = 'a39cb126d5bcb722b827ea4882b24d9dcaa88fdb083cc5881a12e5a7804b4616'
LANGUAGES = {'cn': 'Chinese(PRC)', 'en': 'English', 'jp': 'Japanese', 'kr': 'Korean'}


def digest(path, algorithm='sha256'):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, algorithm).hexdigest()


def checked(root, relative):
    path = root / relative
    if path.is_symlink() or not path.resolve().is_relative_to(root.resolve()):
        raise ValueError('路径离开客户端目录: ' + str(relative))
    return path


def read_index(directory, language, name=INDEX, expected=INDEX_SHA256):
    if language not in LANGUAGES or not re.fullmatch(r'AudioV_[0-9a-f]{32}\.bytes', name):
        raise ValueError('未知语音或索引名称')
    index = checked(directory, name)
    if digest(index) != expected:
        raise ValueError('音频索引 SHA256 不匹配，拒绝沿用其他版本')
    if index.stat().st_size > 8 * 1024 * 1024:
        raise ValueError('音频索引过大')
    rows, seen = [], set()
    for line in index.read_text(encoding='utf-8-sig').splitlines():
        row = json.loads(line)
        path = row['Path']
        pure = PurePosixPath(path)
        if (pure.is_absolute() or '..' in pure.parts or str(pure) != path
                or '\\' in path or ':' in path):
            raise ValueError('不规范的音频路径')
        if path.casefold() in seen:
            raise ValueError('重复音频路径')
        seen.add(path.casefold())
        if (type(row['Size']) is not int or row['Size'] <= 0
                or not re.fullmatch('[0-9a-f]{32}', row['Md5'])):
            raise ValueError('无效的音频长度或 MD5')
        if pure.parts[0] == LANGUAGES[language]:
            if len(pure.parts) != 2 or pure.suffix != '.pck':
                raise ValueError('未核对的音频子目录')
            rows.append(row)
    if not rows:
        raise ValueError('现用索引缺少所选语音')
    return rows


def marker_path(path, row):
    return path.with_name(path.stem + '_' + row['Md5'] + '.hash')


def audit(directory, rows):
    issues = []
    for row in rows:
        path = checked(directory, row['Path'])
        marker = checked(directory, marker_path(Path(row['Path']), row))
        if not path.is_file():
            issues.append({'path': row['Path'], 'reason': 'missing'})
        elif path.stat().st_size != row['Size'] or digest(path, 'md5') != row['Md5']:
            issues.append({'path': row['Path'], 'reason': 'content-mismatch'})
        elif not marker.is_file():
            issues.append({'path': row['Path'], 'reason': 'missing-marker'})
    return {'requiredPackages': len(rows), 'complete': not issues, 'issues': issues}


def plan_additions(directory, rows, source):
    plan = []
    for row in rows:
        target = checked(directory, row['Path'])
        marker = checked(directory, marker_path(Path(row['Path']), row))
        if target.exists():
            if not target.is_file() or target.stat().st_size != row['Size'] or digest(target, 'md5') != row['Md5']:
                raise ValueError('现有音频不同，拒绝覆盖: ' + row['Path'])
        else:
            staged = checked(source, target.name)
            if (not staged.is_file() or staged.stat().st_size != row['Size']
                    or digest(staged, 'md5') != row['Md5']):
                raise ValueError('缺失音频尚无匹配来源: ' + row['Path'])
            plan.append((staged, target))
        if not marker.exists():
            plan.append((None, marker))
        elif not marker.is_file():
            raise ValueError('音频校验标记不是文件')
    return plan


def install_additions(plan):
    # 链接的是刚复制的临时文件，发布后删除临时名；不会链接下载缓存。
    # link 不会替换已经存在的目标，防止检查后的并发写入被覆盖。
    created = []
    try:
        for source, target in plan:
            temporary = target.with_name(target.name + '.' + uuid.uuid4().hex + '.partial')
            try:
                with temporary.open('xb') as dst:
                    if source is None:
                        dst.write(b' ')
                    else:
                        with source.open('rb') as src:
                            shutil.copyfileobj(src, dst, 1024 * 1024)
                if source is not None and digest(temporary) != digest(source):
                    raise ValueError('复制后的音频校验失败')
                os.link(temporary, target)
                created.append(target)
            finally:
                temporary.unlink(missing_ok=True)
        return created
    except Exception:
        for path in reversed(created):
            path.unlink()
        raise


def select_audio(directory, language):
    record = checked(directory, 'AudioLangRedord.txt')
    before = record.read_bytes()
    data = json.loads(before.decode('utf-8-sig'))
    if not isinstance(data, dict) or not isinstance(data.get('AudioLang'), str):
        raise ValueError('现有配音选择记录格式未知')
    data['AudioLang'] = LANGUAGES[language]
    after = json.dumps(data, ensure_ascii=False, separators=(',', ':')).encode('utf-8')
    return record, before, after


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--client-dir', type=Path, required=True)
    parser.add_argument('--language', choices=LANGUAGES, default='cn')
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--source-dir', type=Path)
    parser.add_argument('--apply', action='store_true')
    parser.add_argument('--select', action='store_true')
    args = parser.parse_args()
    client = args.client_dir.resolve()
    directory = checked(client, AUDIO)
    pointer = checked(directory, 'M_AudioV.bytes')
    if digest(pointer) != POINTER_SHA256:
        raise ValueError('当前音频索引指针不同，需重新核对版本')
    if args.output.resolve().is_relative_to(client):
        raise ValueError('审计输出须位于客户端外')
    rows = read_index(directory, args.language)
    before = audit(directory, rows)
    report = {'index': INDEX, 'indexSHA256': INDEX_SHA256, 'language': args.language,
              'before': before, 'clientStarted': False, 'clientAcceptance': 'pending'}
    if args.apply:
        if args.source_dir is None:
            raise ValueError('补装需要已校验的来源目录')
        spec = importlib.util.spec_from_file_location('launcher', ROOT / 'scripts/start-local-client.py')
        launcher = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(launcher)
        launcher.require_stopped()
        manifest, pinned, _ = launcher.load_manifest(ROOT / '.local/client/patch-manifest.json')
        if pinned.resolve() != client:
            raise ValueError('与原桌面入口的客户端目录不一致')
        for path, expected in manifest['clientFiles'].items():
            if digest(checked(client, path)) != expected:
                raise ValueError('现有客户端补丁校验失败: ' + path)
        plan = plan_additions(directory, rows, args.source_dir.resolve())
        selection = select_audio(directory, args.language) if args.select else None
        backup = args.output.with_suffix('.before.json')
        report['created'] = [str(target.relative_to(client)) for _, target in plan]
        if selection:
            report['selectionBeforeHex'] = selection[1].hex()
        args.output.parent.mkdir(parents=True, exist_ok=True)
        with backup.open('x', encoding='utf-8') as stream:
            json.dump(report, stream, ensure_ascii=False, indent=2)
        launcher.require_stopped()
        if digest(checked(directory, INDEX)) != INDEX_SHA256 or digest(pointer) != POINTER_SHA256:
            raise ValueError('音频索引在检查期间改变')
        install_additions(plan)
        try:
            if selection:
                launcher.require_stopped()
                path, original, updated = selection
                if path.read_bytes() != original:
                    raise ValueError('配音选择在核对后发生变化')
                temporary = path.with_name(path.name + '.' + uuid.uuid4().hex + '.partial')
                try:
                    with temporary.open('xb') as stream:
                        stream.write(updated)
                    temporary.replace(path)
                finally:
                    temporary.unlink(missing_ok=True)
                report['selection'] = json.loads(path.read_bytes())['AudioLang']
            report['after'] = audit(directory, rows)
            if not report['after']['complete']:
                raise ValueError('补装后的完整索引检查失败')
            for path, expected in manifest['clientFiles'].items():
                if digest(checked(client, path)) != expected:
                    raise ValueError('补装后客户端补丁校验失败')
            report['patchFilesVerified'] = len(manifest['clientFiles'])
        except Exception:
            # 本次新增文件保留便于诊断；备份含精确新增清单及原始选择字节。
            report['failed'] = True
            raise
        finally:
            args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    else:
        if args.select:
            raise ValueError('--select 必须与 --apply 一起使用')
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    print(json.dumps({'output': str(args.output), 'result': report.get('after', before)}, ensure_ascii=False))
    return 0 if report.get('after', before)['complete'] else 1


if __name__ == '__main__':
    raise SystemExit(main())
