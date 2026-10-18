import hashlib
import json
import subprocess
import sys
import tempfile
import unittest
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SCRIPT = ROOT / 'scripts/prepare-compose.py'


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


class PrepareComposeTests(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory(prefix='hkrpg-compose-test-')
        self.addCleanup(temporary.cleanup)
        self.base = Path(temporary.name)
        self.operator = self.base / 'operator'
        self.data = self.operator / 'data'
        self.data.mkdir(parents=True)
        (self.data / 'database.mv').write_bytes(b'existing-account-and-player-save\x00\xff')
        (self.data / 'nested').mkdir()
        (self.data / 'nested/custom.json').write_bytes(b'{"operator":true}\r\n')
        (self.operator / 'plugins').mkdir()
        (self.operator / 'plugins/example.jar').write_bytes(b'operator-plugin')
        self.resources = self.base / 'resource files'
        (self.resources / 'ExcelOutput').mkdir(parents=True)
        (self.resources / 'ExcelOutput/Original Config.json').write_bytes(b'[]')
        self.key = self.base / 'passport-private.der'
        self.key.write_bytes(b'synthetic-private-material-never-print')
        self.config = {'protocol': {'wireVersion': '4.6.0'},
                       'resourceDir': str(self.resources), 'dataDir': './data',
                       'httpServer': {'bindAddress': '127.0.0.1', 'bindPort': 18080},
                       'gameServer': {'bindPort': 23311, 'name': 'operator name'},
                       'internalMongoServer': {'port': 27079, 'filePath': './data/database.mv'},
                       'loginOptions': {'cnPassportPrivateKeyPath': str(self.key)},
                       'serverOptions': {'autoCreateAccount': True},
                       'operatorExtension': {'retain': [1, 2, 3]}}
        self.write_config()
        (self.operator / 'hotfix.json').write_bytes(b'{"assetBundleUrl":"operator-custom"}\r\n')
        self.jar = self.base / 'verified.jar'
        with zipfile.ZipFile(self.jar, 'w') as archive:
            archive.writestr('META-INF/MANIFEST.MF',
                             'Main-Class: org.hkrpg.server.HkrpgServer\n')
            archive.writestr('org/hkrpg/server/HkrpgServer.class', b'test-fixture')
        self.target = self.base / 'compose'

    def write_config(self):
        (self.operator / 'config.json').write_text(json.dumps(self.config), encoding='utf-8')

    def run_prepare(self, *extra):
        return subprocess.run([sys.executable, str(SCRIPT), '--operator-dir', str(self.operator),
                               '--jar', str(self.jar), '--runtime-dir', str(self.target), *extra],
                              capture_output=True, text=True, encoding='utf-8')

    def rejected(self, phrase):
        result = self.run_prepare()
        self.assertNotEqual(0, result.returncode)
        self.assertIn(phrase, result.stderr.lower())
        self.assertFalse(self.target.exists())
        self.assertNotIn(self.key.read_text(), result.stdout + result.stderr)

    def test_existing_account_data_settings_and_artifact_survive_migration(self):
        before = {p: sha(p) for p in self.operator.rglob('*') if p.is_file()}
        before.update({self.key: sha(self.key), self.jar: sha(self.jar)})
        result = self.run_prepare('--expected-jar-sha256', sha(self.jar))
        self.assertEqual(0, result.returncode, result.stderr)
        cfg = json.loads((self.target / 'work/config.json').read_text())
        self.assertEqual('/resources', cfg['resourceDir'])
        self.assertEqual('/work/data', cfg['dataDir'])
        self.assertEqual('/work/data/database.mv', cfg['internalMongoServer']['filePath'])
        self.assertEqual('/run/hkrpg-secrets/passport-private.der',
                         cfg['loginOptions']['cnPassportPrivateKeyPath'])
        for section, port in [('httpServer', 18080), ('gameServer', 23311)]:
            self.assertEqual('0.0.0.0', cfg[section]['bindAddress'])
            self.assertEqual('127.0.0.1', cfg[section]['publicAddress'])
            self.assertEqual(port, cfg[section]['bindPort'])
            self.assertEqual(port, cfg[section]['publicPort'])
        self.assertTrue(cfg['accountDatabase']['useInternal'])
        self.assertTrue(cfg['gameDatabase']['useInternal'])
        self.assertTrue(cfg['serverOptions']['autoCreateAccount'])
        self.assertEqual('operator name', cfg['gameServer']['name'])
        self.assertEqual(self.config['operatorExtension'], cfg['operatorExtension'])
        for relative in ['data/database.mv', 'data/nested/custom.json',
                         'hotfix.json', 'plugins/example.jar']:
            self.assertEqual((self.operator / relative).read_bytes(),
                             (self.target / 'work' / relative).read_bytes())
        self.assertEqual(self.key.read_bytes(),
                         (self.target / 'credentials/passport-private.der').read_bytes())
        self.assertEqual(sha(self.jar), sha(self.target / 'artifact/server.jar'))
        self.assertEqual(before, {p: sha(p) for p in before})
        manifest = json.loads((self.target / 'migration.json').read_text())
        self.assertEqual(sha(self.jar), manifest['runtimeSHA256'])
        self.assertEqual(sha(self.data / 'database.mv'), manifest['sourceDatabaseSHA256'])
        self.assertEqual('data/database.mv', manifest['databaseRelativePath'])
        self.assertFalse(manifest['sourceRunningStateVerifiedByTool'])
        self.assertNotIn(self.key.read_text(), result.stdout + result.stderr + json.dumps(manifest))
        self.assertIn("HKRPG_RUNTIME_DIR='", (self.target / 'compose.env').read_text())
        for name in ['LICENSE', 'NOTICE.md', 'healthcheck.sh']:
            self.assertTrue((self.target / 'artifact' / name).is_file())

    def test_existing_target_even_empty_is_never_reused(self):
        self.target.mkdir()
        result = self.run_prepare()
        self.assertNotEqual(0, result.returncode)
        self.assertIn('already exists', result.stderr.lower())
        self.assertEqual([], list(self.target.iterdir()))

    def test_missing_database_cannot_silently_create_an_empty_account_store(self):
        (self.data / 'database.mv').unlink()
        self.rejected('database')

    def test_missing_resources_is_rejected_before_copying(self):
        self.config['resourceDir'] = str(self.base / 'absent')
        self.write_config()
        self.rejected('resource')

    def test_missing_configured_key_is_rejected_before_copying(self):
        self.config['loginOptions']['cnPassportPrivateKeyPath'] = str(self.base / 'missing.der')
        self.write_config()
        self.rejected('passport')

    def test_external_database_is_not_replaced_by_empty_internal_data(self):
        self.config['accountDatabase'] = {'useInternal': False, 'uri': 'mongodb://example.invalid'}
        self.write_config()
        self.rejected('external database')

    def test_database_outside_data_requires_explicit_migration(self):
        (self.operator / 'outside.mv').write_bytes(b'other-save')
        self.config['internalMongoServer']['filePath'] = './outside.mv'
        self.write_config()
        self.rejected('outside data')

    def test_destination_overlapping_source_is_rejected(self):
        self.target = self.operator / 'nested-compose'
        self.rejected('overlap')

    def test_wrong_artifact_digest_is_rejected(self):
        result = self.run_prepare('--expected-jar-sha256', '0' * 64)
        self.assertNotEqual(0, result.returncode)
        self.assertIn('sha256', result.stderr.lower())
        self.assertFalse(self.target.exists())

    def test_tls_is_not_silently_downgraded(self):
        self.config['httpServer']['useSSL'] = True
        self.write_config()
        self.rejected('tls')

    def test_missing_hotfix_is_not_replaced_with_defaults(self):
        (self.operator / 'hotfix.json').unlink()
        self.rejected('hotfix')


if __name__ == '__main__':
    unittest.main()
