"""固定国服 SDK 的本地商店入口、原有文件保护及安全安装。"""
import hashlib
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from unittest import mock
from urllib.parse import urlsplit

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location('sdk_shop', ROOT / 'scripts/client_sdk_shop.py')
shop = importlib.util.module_from_spec(spec)
spec.loader.exec_module(shop)
CLIENT = Path.home() / 'AppData/Local/hoyo/hkrpg/versions/current'
BACKUP = ROOT / '.local/client-patches/sdk-shop-20261115-backup/sdk.bundle'


@unittest.skipUnless((CLIENT / shop.sdk.BUNDLE_PATH).is_file(), '固定国服客户端不可用')
class SdkShopPatchTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        active = (CLIENT / shop.sdk.BUNDLE_PATH).read_bytes()
        cls.raw = active if shop.sha(active) == shop.SOURCE_SHA else BACKUP.read_bytes()
        assert shop.sha(cls.raw) == shop.SOURCE_SHA

    def test_patch_changes_only_checked_sdk_shop_urls(self):
        result, proof = shop.patch(self.raw)
        before, after = shop.sdk.read_bundle_config(self.raw), shop.sdk.read_bundle_config(result)
        expected = json.loads(json.dumps(before['config']))
        related = {key for key, value in expected[0]['url'].items() if value and
                   any(word in key.lower() for word in ('pay', 'price', 'shop', 'goods', 'order', 'currency', 'recharge'))}
        self.assertEqual(set(shop.SHOP_FIELDS) | {'jpPayAgeLimit', 'jpPayAgeLimitPC'}, related)
        for name in shop.SHOP_FIELDS:
            old = urlsplit(expected[0]['url'][name])
            expected[0]['url'][name] = shop.ORIGIN + old.path + (('?' + old.query) if old.query else '')
            self.assertEqual('127.0.0.1:18080', urlsplit(after['config'][0]['url'][name]).netloc)
        self.assertEqual(expected, after['config'])
        self.assertEqual(before['nodes'], after['nodes'])
        self.assertEqual([44], proof['changedBlocks'])
        self.assertEqual(len(shop.SHOP_FIELDS), len(proof['changes']))
        for number, block in enumerate(before['blocks']):
            if number != 44:
                self.assertEqual(block, after['blocks'][number])

    def test_unknown_sdk_is_rejected_without_guessing(self):
        changed = bytearray(self.raw)
        changed[-1] ^= 1
        with self.assertRaises(ValueError):
            shop.patch(bytes(changed))

    def test_install_preserves_manifest_and_refuses_a_running_game(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            client = root / 'client'
            files = {}
            for relative in shop.launcher.REQUIRED_FILES:
                target = client / relative
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_bytes(self.raw if relative == shop.sdk.BUNDLE_PATH.as_posix() else b'fixture')
                files[relative] = hashlib.sha256(target.read_bytes()).hexdigest()
            manifest = root / 'patch-manifest.json'
            original = {'schema': 2, 'clientRoot': str(client), 'clientFiles': files,
                        'debugConsole': {'mode': 'external', 'sha256': '0' * 64, 'backupPath': 'backup'},
                        'service': {'readyUrl': 'http://127.0.0.1:18080/health/ready', 'startCommand': 'test'}}
            manifest.write_text(json.dumps(original), encoding='utf-8')
            prepared, backup = root / 'prepared', root / 'backup-sdk'
            with mock.patch.object(shop.launcher, 'require_stopped'):
                shop.prepare(manifest, prepared)
            with mock.patch.object(shop.launcher, 'require_stopped', side_effect=RuntimeError('running')):
                with self.assertRaises(RuntimeError):
                    shop.install(manifest, prepared, backup)
            self.assertFalse(backup.exists())
            self.assertEqual(self.raw, (client / shop.sdk.BUNDLE_PATH).read_bytes())
            with mock.patch.object(shop.launcher, 'require_stopped'):
                proof = shop.install(manifest, prepared, backup)
            self.assertTrue(proof['clientModified'])
            changed = json.loads(manifest.read_text(encoding='utf-8'))
            expected = json.loads(json.dumps(original))
            expected['clientFiles'][shop.sdk.BUNDLE_PATH.as_posix()] = proof['resultSHA256']
            self.assertEqual(expected, changed)
            self.assertEqual(self.raw, (backup / 'sdk.bundle').read_bytes())
            with mock.patch.object(shop.launcher, 'require_stopped'):
                repeated = shop.install(manifest, prepared, root / 'unused-backup')
            self.assertFalse(repeated['clientModified'])
            self.assertTrue(repeated['alreadyInstalled'])
            self.assertFalse((root / 'unused-backup').exists())


if __name__ == '__main__':
    unittest.main()
