import hashlib
import importlib.util
import json
import shutil
import subprocess
import unittest
from pathlib import Path
import test_compose_prepare as fixtures

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location('compose_prepare_resources', ROOT / 'scripts/prepare-compose.py')
prepare = importlib.util.module_from_spec(spec)
spec.loader.exec_module(prepare)


class NativeResourceDeploymentTests(unittest.TestCase):
    def setUp(self):
        self.fixture = fixtures.PrepareComposeTests()
        self.fixture.setUp()
        self.addCleanup(self.fixture.doCleanups)
        self.resource = self.fixture.resources / 'ExcelOutput/Original Config.json'
        self.resource.write_bytes(b'{"original":true}\r\n')

    def test_manifest_binds_exact_relative_paths_and_original_bytes(self):
        target = self.fixture.base / 'resources.sha256'
        report = prepare.write_resource_manifest(self.fixture.resources, target)
        expected = hashlib.sha256(self.resource.read_bytes()).hexdigest()
        self.assertEqual(expected + '  ExcelOutput/Original Config.json\n', target.read_text())
        self.assertEqual(1, report['files'])
        self.assertEqual(self.resource.stat().st_size, report['bytes'])
        self.assertEqual(hashlib.sha256(target.read_bytes()).hexdigest(), report['sha256'])

    def test_manifest_never_replaces_an_existing_snapshot(self):
        target = self.fixture.base / 'resources.sha256'
        target.write_bytes(b'original snapshot')
        with self.assertRaises(FileExistsError):
            prepare.write_resource_manifest(self.fixture.resources, target)
        self.assertEqual(b'original snapshot', target.read_bytes())

    def test_prepare_records_resource_snapshot_without_mutating_original(self):
        before = fixtures.sha(self.resource)
        result = self.fixture.run_prepare()
        self.assertEqual(0, result.returncode, result.stderr)
        manifest = self.fixture.target / 'artifact/resources.sha256'
        self.assertTrue(manifest.is_file())
        report = json.loads((self.fixture.target / 'migration.json').read_text())
        self.assertEqual(fixtures.sha(manifest), report['resourceSnapshot']['sha256'])
        self.assertEqual(1, report['resourceSnapshot']['files'])
        self.assertEqual(before, fixtures.sha(self.resource))

    @unittest.skipUnless(shutil.which('docker'), 'Docker Compose CLI unavailable')
    def test_resolved_compose_uses_native_image_resources_and_read_only_root(self):
        result = self.fixture.run_prepare()
        self.assertEqual(0, result.returncode, result.stderr)
        resolved = subprocess.run(['docker', 'compose', '--env-file',
                                  str(self.fixture.target / 'compose.env'), '-f',
                                  str(ROOT / 'deploy/compose.local.yaml'), 'config', '--format', 'json'],
                                 capture_output=True, text=True, encoding='utf-8', timeout=30)
        self.assertEqual(0, resolved.returncode, resolved.stderr)
        server = json.loads(resolved.stdout)['services']['server']
        self.assertEqual(self.fixture.resources.resolve(),
                         Path(server['build']['additional_contexts']['hkrpg_resources']).resolve())
        self.assertFalse(any(mount['target'] == '/resources' or mount['target'].startswith('/resources/')
                             for mount in server['volumes']))
        self.assertTrue(server['read_only'])
        self.assertIn('/tmp', server['tmpfs'])
        work = next(mount for mount in server['volumes'] if mount['target'] == '/work')
        self.assertFalse(work.get('read_only', False))
        self.assertEqual((self.fixture.target / 'work').resolve(), Path(work['source']).resolve())


if __name__ == '__main__':
    unittest.main()
