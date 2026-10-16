"""固定SDK数据补丁、原件保护及本地地址约束；不启动或登录游戏。"""
import hashlib
import importlib.util
import json
import os
import tempfile
import unittest
from pathlib import Path
from urllib.parse import urlsplit
from unittest.mock import patch

from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric import rsa

ROOT = Path(__file__).resolve().parents[2]
SDK = Path('C:/Users/InvokerBot/AppData/Local/hoyo/hkrpg/versions/current/StarRail_Data/Plugins/x86_64/AccountPlatNative.dll')
spec = importlib.util.spec_from_file_location('cn_sdk_patch', ROOT / 'scripts/patch-client-cn-sdk.py')
patcher = importlib.util.module_from_spec(spec)
spec.loader.exec_module(patcher)


def original_bytes(relative_path, backup_name, expected):
    # After an explicitly authorized installed-client patch, use an operator's
    # original backup as the fixture. Its pinned digest is still mandatory.
    configured = os.environ.get('HKRPG_CLIENT_ORIGINALS')
    source = Path(configured) / backup_name if configured else SDK.parents[3] / relative_path
    raw = source.read_bytes()
    if patcher.checksum(raw) != expected:
        raise ValueError('固定原件摘要不符；已安装补丁时请将HKRPG_CLIENT_ORIGINALS指向原件备份目录')
    return raw


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
        cls.raw = original_bytes(patcher.SDK_PATH, 'AccountPlatNative.original.dll', patcher.SDK_SHA256)
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

    def test_install_redirects_the_managed_sdk_login_config_too(self):
        raw_bundle = original_bytes(patcher.BUNDLE_PATH, 'mihoyo_sdk_res.original.bundle', patcher.BUNDLE_SHA256)
        with tempfile.TemporaryDirectory(dir=ROOT / 'build', prefix='sdk-bundle-test-') as temp:
            directory = Path(temp)
            client = directory / 'client'
            native = client / patcher.SDK_PATH
            native.parent.mkdir(parents=True)
            native.write_bytes(self.raw)
            bundle = client / patcher.BUNDLE_PATH
            bundle.parent.mkdir(parents=True)
            bundle.write_bytes(raw_bundle)
            public = directory / 'public.pem'
            public.write_bytes(self.public)
            patcher.install(client, 'http://127.0.0.1:18080', public, directory / 'backup', directory / 'proof.json')
            data = patcher.read_bundle_config(bundle.read_bytes())
            env = next(row for row in data['config'] if row['env'] == 0)
            self.assertEqual('127.0.0.1', urlsplit(env['url']['loginUrl']).hostname)
            first = bundle.read_bytes()
            repeated = patcher.install(client, 'http://127.0.0.1:18080', public, directory / 'backup', directory / 'repeat.json')
            self.assertFalse(repeated['changed'])
            self.assertEqual(first, bundle.read_bytes())

    def test_bundle_patch_preserves_every_other_expanded_byte_and_environment(self):
        raw = original_bytes(patcher.BUNDLE_PATH, 'mihoyo_sdk_res.original.bundle', patcher.BUNDLE_SHA256)
        original = patcher.read_bundle_config(raw)
        patched, report = patcher.patch_bundle_bytes(raw, 'http://127.0.0.1:18080')
        current = patcher.read_bundle_config(patched)
        self.assertEqual(original['nodes'], current['nodes'])
        self.assertEqual(original['config'][1:], current['config'][1:])
        expected = json.loads(json.dumps(original['config']))
        restored = bytearray(current['data'])
        self.assertEqual(set(patcher.CONFIG_FIELDS), {r['field'] for r in report['changes']})
        for row in report['changes']:
            begin, end = row['offset'], row['offset'] + row['length']
            restored[begin:end] = original['data'][begin:end]
            expected[0]['url'][row['field']] = 'http://127.0.0.1:18080' + row['path']
        self.assertEqual(original['data'], restored)
        self.assertEqual(expected, current['config'])
        self.assertEqual([44], report['changedBlocks'])
        for index in range(len(current['blocks'])):
            if index not in report['changedBlocks']:
                self.assertEqual(original['blocks'][index], current['blocks'][index])

    def test_unknown_bundle_is_rejected_before_any_native_file_or_backup_changes(self):
        with tempfile.TemporaryDirectory(dir=ROOT / 'build', prefix='sdk-bundle-guard-') as temp:
            directory = Path(temp)
            client = directory / 'client'
            native, bundle = client / patcher.SDK_PATH, client / patcher.BUNDLE_PATH
            native.parent.mkdir(parents=True)
            bundle.parent.mkdir(parents=True)
            native.write_bytes(self.raw)
            bundle.write_bytes(b'operator resource modification')
            public = directory / 'public.pem'
            public.write_bytes(self.public)
            with self.assertRaisesRegex(ValueError, 'SHA256'):
                patcher.install(client, 'http://127.0.0.1:18080', public, directory / 'backup', directory / 'proof.json')
            self.assertEqual(self.raw, native.read_bytes())
            self.assertEqual(b'operator resource modification', bundle.read_bytes())
            self.assertFalse((directory / 'backup').exists())
            self.assertFalse((directory / 'proof.json').exists())

    def test_second_file_replace_failure_rolls_back_the_native_patch(self):
        raw_bundle = original_bytes(patcher.BUNDLE_PATH, 'mihoyo_sdk_res.original.bundle', patcher.BUNDLE_SHA256)
        with tempfile.TemporaryDirectory(dir=ROOT / 'build', prefix='sdk-rollback-test-') as temp:
            directory = Path(temp)
            client = directory / 'client'
            native, bundle = client / patcher.SDK_PATH, client / patcher.BUNDLE_PATH
            native.parent.mkdir(parents=True)
            bundle.parent.mkdir(parents=True)
            native.write_bytes(self.raw)
            bundle.write_bytes(raw_bundle)
            public = directory / 'public.pem'
            public.write_bytes(self.public)
            replace = patcher.os.replace
            def fail_bundle(source, destination):
                if Path(destination) == bundle:
                    raise PermissionError('resource is in use')
                return replace(source, destination)
            with patch.object(patcher.os, 'replace', side_effect=fail_bundle):
                with self.assertRaises(PermissionError):
                    patcher.install(client, 'http://127.0.0.1:18080', public, directory / 'backup', directory / 'proof.json')
            self.assertEqual(self.raw, native.read_bytes())
            self.assertEqual(raw_bundle, bundle.read_bytes())
            self.assertFalse((directory / 'proof.json').exists())

    def test_installed_client_requires_explicit_scope_and_patches_all_three_files(self):
        raw_bundle = original_bytes(patcher.BUNDLE_PATH, 'mihoyo_sdk_res.original.bundle', patcher.BUNDLE_SHA256)
        raw_dispatch = original_bytes(patcher.DISPATCH_PATH, 'ClientConfig.original.bytes', patcher.DISPATCH_SHA256)
        with tempfile.TemporaryDirectory(prefix='sdk-installed-scope-') as installed_dir, \
                tempfile.TemporaryDirectory(dir=ROOT / 'build', prefix='sdk-installed-proof-') as proof_dir:
            client, proof = Path(installed_dir) / 'client', Path(proof_dir)
            for relative, raw in ((patcher.SDK_PATH, self.raw), (patcher.BUNDLE_PATH, raw_bundle),
                                  (patcher.DISPATCH_PATH, raw_dispatch)):
                file = client / relative
                file.parent.mkdir(parents=True, exist_ok=True)
                file.write_bytes(raw)
            public = proof / 'public.pem'
            public.write_bytes(self.public)
            with patch.object(patcher, 'INSTALLED_CLIENT', client):
                with self.assertRaises(ValueError):
                    patcher.install(client, 'http://127.0.0.1:18080', public, proof / 'backup', proof / 'rejected.json')
                self.assertFalse((proof / 'backup').exists())
                report = patcher.install(client, 'http://127.0.0.1:18080', public, proof / 'backup', proof / 'ok.json', allow_installed=True)
                self.assertTrue(report['installedClientTarget'])
                self.assertTrue(report['officialClientModified'])
                self.assertIsNotNone(report['managedConfig'])
                self.assertEqual(patcher.patch_dispatch_bytes(raw_dispatch, 'http://127.0.0.1:18080'),
                                 (client / patcher.DISPATCH_PATH).read_bytes())
                self.assertEqual(raw_dispatch, (proof / 'backup/ClientConfig.original.bytes').read_bytes())
                again = patcher.install(client, 'http://127.0.0.1:18080', public, proof / 'backup', proof / 'repeat.json', allow_installed=True)
                self.assertFalse(again['changed'])

    def test_dispatch_unknown_modification_is_rejected_before_any_install(self):
        with tempfile.TemporaryDirectory(dir=ROOT / 'build', prefix='sdk-dispatch-guard-') as temp:
            directory = Path(temp)
            client = directory / 'client'
            native, dispatch = client / patcher.SDK_PATH, client / patcher.DISPATCH_PATH
            native.parent.mkdir(parents=True)
            dispatch.parent.mkdir(parents=True, exist_ok=True)
            native.write_bytes(self.raw)
            dispatch.write_bytes(b'operator dispatch modification')
            public = directory / 'public.pem'
            public.write_bytes(self.public)
            with self.assertRaisesRegex(ValueError, 'SHA256'):
                patcher.install(client, 'http://127.0.0.1:18080', public, directory / 'backup', directory / 'proof.json')
            self.assertEqual(self.raw, native.read_bytes())
            self.assertEqual(b'operator dispatch modification', dispatch.read_bytes())
            self.assertFalse((directory / 'backup').exists())

    def test_third_file_failure_restores_both_native_and_resource_files(self):
        original_bundle = original_bytes(patcher.BUNDLE_PATH, 'mihoyo_sdk_res.original.bundle', patcher.BUNDLE_SHA256)
        original_dispatch = original_bytes(patcher.DISPATCH_PATH, 'ClientConfig.original.bytes', patcher.DISPATCH_SHA256)
        with tempfile.TemporaryDirectory(dir=ROOT / 'build', prefix='sdk-three-file-rollback-') as temp:
            directory = Path(temp)
            client = directory / 'client'
            originals = {patcher.SDK_PATH: self.raw, patcher.BUNDLE_PATH: original_bundle,
                         patcher.DISPATCH_PATH: original_dispatch}
            for relative, raw in originals.items():
                path = client / relative
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_bytes(raw)
            public = directory / 'public.pem'
            public.write_bytes(self.public)
            replace = patcher.os.replace
            def fail_dispatch(source, destination):
                if Path(destination) == client / patcher.DISPATCH_PATH:
                    raise PermissionError('dispatch file is in use')
                return replace(source, destination)
            with patch.object(patcher.os, 'replace', side_effect=fail_dispatch):
                with self.assertRaises(PermissionError):
                    patcher.install(client, 'http://127.0.0.1:18080', public, directory / 'backup', directory / 'proof.json')
            for relative, raw in originals.items():
                self.assertEqual(raw, (client / relative).read_bytes())
            self.assertFalse((directory / 'proof.json').exists())

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
