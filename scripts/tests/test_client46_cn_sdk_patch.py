"""固定SDK数据补丁、原件保护及本地地址约束；不启动或登录游戏。"""
import hashlib
import importlib.util
import tempfile
import unittest
from pathlib import Path
from urllib.parse import urlsplit

from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric import rsa

ROOT = Path(__file__).resolve().parents[2]
SDK = Path('C:/Users/InvokerBot/AppData/Local/hoyo/hkrpg/versions/current/StarRail_Data/Plugins/x86_64/AccountPlatNative.dll')
spec = importlib.util.spec_from_file_location('cn_sdk_patch', ROOT / 'scripts/patch-client-cn-sdk.py')
patcher = importlib.util.module_from_spec(spec)
spec.loader.exec_module(patcher)


class PatchOriginTest(unittest.TestCase):
    def test_only_explicit_loopback_http_origins_are_accepted(self):
        self.assertEqual('http://127.0.0.1:18080', patcher.server_origin('http://127.0.0.1:18080/'))
        for value in ['https://127.0.0.1:18080', 'http://example.com:18080', 'http://127.0.0.1',
                      'http://user@127.0.0.1:18080', 'http://127.0.0.1:18080/path',
                      'http://127.0.0.1:18080?a=1', 'http://127.0.0.1:99999']:
            with self.subTest(value=value), self.assertRaises(ValueError):
                patcher.server_origin(value)


@unittest.skipUnless(SDK.is_file(), '没有本机固定国服SDK')
class FixedSdkPatchTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.raw = SDK.read_bytes()
        cls.public = rsa.generate_private_key(public_exponent=65537, key_size=1024).public_key().public_bytes(
            serialization.Encoding.PEM, serialization.PublicFormat.SubjectPublicKeyInfo)

    def test_all_120_account_urls_and_only_the_login_public_key_slot_change(self):
        patched, changes = patcher.patch_bytes(self.raw, 'http://127.0.0.1:18080', self.public)
        self.assertEqual(120, len(changes))
        self.assertEqual(len(self.raw), len(patched))
        restored = bytearray(patched)
        for row in changes:
            start, end = row['offset'], row['offset'] + row['length']
            actual = patched[start:end]
            self.assertEqual(row['afterSHA256'], patcher.checksum(actual))
            url = urlsplit(actual[:-1].decode('ascii'))
            self.assertEqual(('http', '127.0.0.1', 18080, row['path'], '', ''),
                             (url.scheme, url.hostname, url.port, url.path, url.query, url.fragment))
            restored[start:end] = self.raw[start:end]
        start, end = patcher.KEY_OFFSET, patcher.KEY_OFFSET + patcher.KEY_LENGTH
        self.assertEqual(patcher.public_literal(self.public), patched[start:end])
        restored[start:end] = self.raw[start:end]
        self.assertEqual(self.raw, restored)

    def test_fixed_length_native_strings_do_not_include_nul_padding(self):
        patched, changes = patcher.patch_bytes(self.raw, 'http://127.0.0.1:18080', self.public)
        # SDK RVA 106477 passes the original URL byte length to its string
        # constructor at 137f20. The terminator is outside that byte span.
        # NUL padding would become part of the C++ string and its JSON value.
        for row in changes:
            with self.subTest(host=row['originalHost'], path=row['path']):
                span = patched[row['offset']:row['offset'] + row['length'] - 1]
                self.assertNotIn(b'\0', span)
                self.assertEqual(0, patched[row['offset'] + row['length'] - 1])

    def test_changed_sdk_and_wrong_key_size_are_rejected(self):
        bad = bytearray(self.raw)
        bad[0] ^= 1
        with self.assertRaisesRegex(ValueError, 'SHA256'):
            patcher.patch_bytes(bad, 'http://127.0.0.1:18080', self.public)
        public = rsa.generate_private_key(public_exponent=65537, key_size=2048).public_key().public_bytes(
            serialization.Encoding.PEM, serialization.PublicFormat.SubjectPublicKeyInfo)
        with self.assertRaisesRegex(ValueError, '1024'):
            patcher.patch_bytes(self.raw, 'http://127.0.0.1:18080', public)

    def test_install_is_repeatable_and_preserves_unknown_modifications_and_original_client(self):
        original = hashlib.sha256(SDK.read_bytes()).hexdigest()
        with tempfile.TemporaryDirectory(dir=ROOT / 'build', prefix='sdk-patch-test-') as temp:
            directory = Path(temp)
            client = directory / 'client'
            target = client / patcher.SDK_PATH
            target.parent.mkdir(parents=True)
            target.write_bytes(self.raw)
            public = directory / 'public.pem'
            public.write_bytes(self.public)
            first = patcher.install(client, 'http://127.0.0.1:18080', public, directory / 'backup', directory / 'first.json')
            self.assertTrue(first['changed'])
            second = patcher.install(client, 'http://127.0.0.1:18080', public, directory / 'backup', directory / 'second.json')
            self.assertFalse(second['changed'])
            self.assertEqual(self.raw, Path(first['backup']).read_bytes())
            predecessor = bytearray(target.read_bytes())
            for row in first['urlReplacements']:
                value = ('http://127.0.0.1:18080' + row['path']).encode('ascii')
                predecessor[row['offset']:row['offset'] + row['length']] = value + bytes(row['length'] - len(value))
            target.write_bytes(predecessor)
            upgrade = patcher.install(client, 'http://127.0.0.1:18080', public, directory / 'backup', directory / 'upgrade.json')
            self.assertTrue(upgrade['upgradedFromNulPadding'])
            self.assertEqual(first['patchedSHA256'], patcher.checksum(target.read_bytes()))
            target.write_bytes(b'operator modification')
            with self.assertRaises(ValueError):
                patcher.install(client, 'http://127.0.0.1:18080', public, directory / 'backup', directory / 'third.json')
            self.assertEqual(b'operator modification', target.read_bytes())
            self.assertFalse((directory / 'third.json').exists())
        self.assertEqual(original, hashlib.sha256(SDK.read_bytes()).hexdigest())

    def test_official_install_is_never_a_patch_target(self):
        client = SDK.parents[3]
        with tempfile.TemporaryDirectory(dir=ROOT / 'build', prefix='sdk-protection-test-') as temp:
            directory = Path(temp)
            public = directory / 'public.pem'
            public.write_bytes(self.public)
            with self.assertRaisesRegex(ValueError, '独立副本'):
                patcher.install(client, 'http://127.0.0.1:18080', public, directory / 'backup', directory / 'proof.json')
            self.assertFalse((directory / 'backup').exists())


if __name__ == '__main__':
    unittest.main()
