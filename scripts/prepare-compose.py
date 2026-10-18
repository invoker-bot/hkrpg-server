#!/usr/bin/env python3
"""离线准备本机 Compose：只复制已停服实例到新目录，不启动 Docker，不覆盖任何已有目标。"""
import argparse
import hashlib
import json
import os
import re
import shutil
import sys
import tempfile
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
DEFAULT_RUNTIME_IMAGE = 'eclipse-temurin:21-jre@sha256:227118764cccd3c3294617061aaa93a08309c63a395eefa83e6b35032992ea99'


def sha(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def read_object(path, label):
    if not path.is_file():
        raise ValueError(f'Missing {label} file: {path}')
    try:
        value = json.loads(path.read_text(encoding='utf-8-sig'))
    except (ValueError, UnicodeError) as error:
        raise ValueError(f'Invalid {label} JSON') from error
    if not isinstance(value, dict):
        raise ValueError(f'{label} must be a JSON object')
    return value


def section(config, name):
    value = config.setdefault(name, {})
    if not isinstance(value, dict):
        raise ValueError(f'Configuration section {name} must be an object')
    return value


def local_path(value, operator, label):
    if not isinstance(value, str) or not value:
        raise ValueError(f'Invalid {label} path')
    path = Path(value)
    if not path.is_absolute():
        path = operator / path
    return path.resolve()


def linked(path):
    return path.is_symlink() or getattr(path, 'is_junction', lambda: False)()


def tree_files(directory):
    if linked(directory):
        raise ValueError(f'Linked operator directory is not supported: {directory}')
    result = []
    for base, directories, files in os.walk(directory, followlinks=False):
        for name in directories + files:
            item = Path(base) / name
            if linked(item):
                raise ValueError(f'Linked operator entry is not supported: {item}')
            if item.is_file():
                result.append(item)
    return result


def env_value(value):
    text = str(value).replace('\\', '/')
    if any(character in text for character in "\r\n'\x00"):
        raise ValueError('Compose environment paths cannot contain newlines or single quotes')
    return "'" + text + "'"



def write_resource_manifest(resource_dir, destination):
    """生成仅用于镜像构建的完整SHA清单；不改资源，也不替换已有清单。"""
    resources = Path(resource_dir).resolve(strict=True)
    destination = Path(destination).absolute()
    if os.path.lexists(destination):
        raise FileExistsError(f'Resource manifest already exists: {destination}')
    if destination.resolve().is_relative_to(resources):
        raise ValueError('Resource manifest must be outside original resources')
    files = sorted(tree_files(resources), key=lambda path: path.relative_to(resources).as_posix())
    if not files:
        raise ValueError('Resource snapshot cannot be empty')
    lines, total_bytes = [], 0
    for source in files:
        relative = source.relative_to(resources).as_posix()
        if any(character in relative for character in '\r\n\\'):
            raise ValueError('Resource filenames cannot contain newlines or backslashes')
        before = source.stat()
        digest = sha(source)
        after = source.stat()
        if (before.st_size, before.st_mtime_ns) != (after.st_size, after.st_mtime_ns):
            raise ValueError('Resource changed while hashing; snapshot refused')
        lines.append(f'{digest}  {relative}\n')
        total_bytes += after.st_size
    if set(tree_files(resources)) != set(files):
        raise ValueError('Resource file list changed while hashing; snapshot refused')
    with destination.open('x', encoding='utf-8', newline='\n') as stream:
        stream.writelines(lines)
    return {'files': len(files), 'bytes': total_bytes, 'sha256': sha(destination)}


def prepare(operator_dir, jar_path, runtime_dir, expected_jar_sha256=None, project_name='hkrpg-local', runtime_image=DEFAULT_RUNTIME_IMAGE):
    operator = Path(operator_dir).resolve(strict=True)
    jar = Path(jar_path).resolve(strict=True)
    raw_target = Path(runtime_dir).absolute()
    if os.path.lexists(raw_target):
        raise ValueError(f'Runtime target already exists: {raw_target}')
    target = raw_target.resolve()
    if not re.fullmatch(r'[a-z0-9][a-z0-9_-]*', project_name):
        raise ValueError('Invalid Compose project name')
    if not operator.is_dir():
        raise ValueError('Operator directory is not a directory')
    config_path, hotfix_path = operator / 'config.json', operator / 'hotfix.json'
    config = read_object(config_path, 'config')
    read_object(hotfix_path, 'hotfix')
    http, game = section(config, 'httpServer'), section(config, 'gameServer')
    if http.get('useSSL', False):
        raise ValueError('Existing TLS configuration requires an explicit migration; refusing downgrade')
    for name in ('accountDatabase', 'gameDatabase'):
        database = section(config, name)
        if database.get('useInternal', True) is not True:
            raise ValueError('External database migration is not supported by this local H2 preparer')
        database['useInternal'] = True
    internal = section(config, 'internalMongoServer')
    data = local_path(config.get('dataDir', './data'), operator, 'data')
    database_file = local_path(internal.get('filePath', './data/database.mv'), operator, 'database')
    resources = local_path(config.get('resourceDir', './resources'), operator, 'resource')
    if not data.is_dir() or not database_file.is_file():
        raise ValueError('Existing database file and data directory are required; refusing an empty store')
    if not database_file.is_relative_to(data):
        raise ValueError('Database file is outside data directory; explicit migration required')
    if not resources.is_dir() or not (resources / 'ExcelOutput').is_dir():
        raise ValueError('Resource directory must exist and contain ExcelOutput')
    for source in (operator, data, resources):
        if target == source or target.is_relative_to(source) or source.is_relative_to(target):
            raise ValueError('Runtime target and operator/resource directories must not overlap')
    artifact_sha = sha(jar)
    if expected_jar_sha256 and artifact_sha != expected_jar_sha256.lower():
        raise ValueError('Verified runtime JAR SHA256 does not match')
    try:
        with zipfile.ZipFile(jar) as archive:
            manifest = archive.read('META-INF/MANIFEST.MF').decode('utf-8')
            if ('Main-Class: org.hkrpg.server.HkrpgServer' not in manifest
                    or 'org/hkrpg/server/HkrpgServer.class' not in archive.namelist()):
                raise ValueError('Runtime JAR is not the HKRPG executable artifact')
    except (zipfile.BadZipFile, KeyError, UnicodeError) as error:
        raise ValueError('Invalid runtime JAR') from error
    credentials = {}
    login = section(config, 'loginOptions')
    if login.get('cnPassportPrivateKeyPath'):
        key = local_path(login['cnPassportPrivateKeyPath'], operator, 'passport key')
        if not key.is_file() or linked(key):
            raise ValueError('Configured passport private key is missing or linked')
        credentials['passport-private.der'] = key
        login['cnPassportPrivateKeyPath'] = '/run/hkrpg-secrets/passport-private.der'
    keystore = config.get('keystore', {})
    if not isinstance(keystore, dict):
        raise ValueError('Keystore configuration must be an object')
    if keystore.get('path'):
        key = local_path(keystore['path'], operator, 'keystore')
        if not key.is_file() or linked(key):
            raise ValueError('Configured keystore is missing or linked')
        credentials['keystore.p12'] = key
        keystore['path'] = '/run/hkrpg-secrets/keystore.p12'
    config['resourceDir'], config['dataDir'] = '/resources', '/work/data'
    relative_database = database_file.relative_to(data).as_posix()
    internal['address'], internal['port'] = '127.0.0.1', 27079
    internal['filePath'] = '/work/data/' + relative_database
    for network, port in ((http, 18080), (game, 23311)):
        network.update(bindAddress='0.0.0.0', bindPort=port,
                       publicAddress='127.0.0.1', publicPort=port)
    plugin_dir = operator / 'plugins'
    source_files = [config_path, hotfix_path, jar, *credentials.values(), *tree_files(data)]
    if plugin_dir.exists():
        source_files += tree_files(plugin_dir)
    source_hashes = {path: sha(path) for path in source_files}
    env = {'HKRPG_PROJECT_NAME': project_name, 'HKRPG_RUNTIME_DIR': target,
           'HKRPG_RESOURCES_DIR': resources, 'HKRPG_REPO_DIR': ROOT,
           'HKRPG_HTTP_PORT': 18080, 'HKRPG_GAME_PORT': 23311, 'RUNTIME_IMAGE': runtime_image}
    env_text = ''.join(f'{key}={env_value(value)}\n' for key, value in env.items())
    # All inputs are validated before creating the unique staging area. Source data is never written.
    target.parent.mkdir(parents=True, exist_ok=True)
    staging = Path(tempfile.mkdtemp(prefix='.hkrpg-compose-', dir=target.parent)).resolve()
    try:
        work, artifact = staging / 'work', staging / 'artifact'
        work.mkdir()
        artifact.mkdir()
        (work / 'logs').mkdir()
        shutil.copytree(data, work / 'data')
        if plugin_dir.exists():
            shutil.copytree(plugin_dir, work / 'plugins')
        else:
            (work / 'plugins').mkdir()
        shutil.copy2(hotfix_path, work / 'hotfix.json')
        (work / 'config.json').write_text(json.dumps(config, ensure_ascii=False, indent=2) + '\n',
                                         encoding='utf-8')
        secret_dir = staging / 'credentials'
        secret_dir.mkdir()
        for name, source in credentials.items():
            shutil.copy2(source, secret_dir / name)
        shutil.copy2(jar, artifact / 'server.jar')
        resource_snapshot = write_resource_manifest(resources, artifact / 'resources.sha256')
        for name, source in [('LICENSE', ROOT / 'LICENSE'), ('NOTICE.md', ROOT / 'NOTICE.md'),
                             ('healthcheck.sh', ROOT / 'deploy/healthcheck.sh')]:
            shutil.copy2(source, artifact / name)
        copied_files = [(source, work / 'data' / source.relative_to(data)) for source in tree_files(data)]
        if plugin_dir.exists():
            copied_files += [(source, work / 'plugins' / source.relative_to(plugin_dir))
                             for source in tree_files(plugin_dir)]
        copied_files += [(hotfix_path, work / 'hotfix.json'), (jar, artifact / 'server.jar')]
        copied_files += [(source, secret_dir / name) for name, source in credentials.items()]
        for source, copied in copied_files:
            if sha(copied) != source_hashes[source]:
                raise ValueError('Source changed during copy; stop the source server before migration')
        if any(sha(path) != digest for path, digest in source_hashes.items()):
            raise ValueError('Source changed during preparation; stop the source server before migration')
        report = {'runtimeSHA256': artifact_sha, 'configSHA256': sha(work / 'config.json'),
                  'sourceConfigSHA256': source_hashes[config_path],
                  'sourceHotfixSHA256': source_hashes[hotfix_path],
                  'sourceDatabaseSHA256': source_hashes[database_file],
                  'databaseRelativePath': 'data/' + relative_database,
                  'sourceFilesVerified': len(source_hashes), 'credentialsCopied': len(credentials),
                  'resourceSnapshot': resource_snapshot,
                  'sourceRunningStateVerifiedByTool': False,
                  'sourceMustBeStoppedBeforePreparation': True,
                  'operatorDirectory': str(operator), 'resourceDirectory': str(resources),
                  'runtimeDirectory': str(target), 'projectName': project_name,
                  'clientAcceptanceVerified': False}
        (staging / 'compose.env').write_text(env_text, encoding='utf-8')
        (staging / 'migration.json').write_text(json.dumps(report, indent=2) + '\n', encoding='utf-8')
        # Exclusive creation also refuses a target that appeared after validation.
        target.mkdir(exist_ok=False)
        for item in staging.iterdir():
            item.rename(target / item.name)
        return report
    finally:
        # Only remove the unique directory created by this invocation, within the checked parent.
        if staging.parent != target.parent or not staging.name.startswith('.hkrpg-compose-'):
            raise RuntimeError('Unexpected staging directory; cleanup refused')
        shutil.rmtree(staging)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--operator-dir', type=Path, required=True)
    parser.add_argument('--jar', type=Path, required=True)
    parser.add_argument('--runtime-dir', type=Path, default=ROOT / '.local/compose')
    parser.add_argument('--expected-jar-sha256')
    parser.add_argument('--project-name', default='hkrpg-local')
    parser.add_argument('--runtime-image', default=DEFAULT_RUNTIME_IMAGE)
    args = parser.parse_args()
    try:
        report = prepare(args.operator_dir, args.jar, args.runtime_dir,
                         args.expected_jar_sha256, args.project_name, args.runtime_image)
    except (ValueError, OSError) as error:
        print(f'Compose preparation failed: {error}', file=sys.stderr)
        return 1
    print(json.dumps({'prepared': True, 'runtimeDirectory': report['runtimeDirectory'],
                      'runtimeSHA256': report['runtimeSHA256'],
                      'sourceFilesVerified': report['sourceFilesVerified'],
                      'sourceRunningStateVerifiedByTool': False}))
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
