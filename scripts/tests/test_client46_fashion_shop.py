"""固定 4.6 商品升级的隔离检查；不启动或修改正式客户端。"""
import hashlib
import importlib.util
import json
from pathlib import Path
import shutil
import tempfile
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
CLIENT = Path(r'C:\Users\InvokerBot\AppData\Local\hoyo\hkrpg\versions\current')
spec = importlib.util.spec_from_file_location('fashion_test', ROOT / 'scripts/client_fashion_shop_data.py')
fashion = importlib.util.module_from_spec(spec)
spec.loader.exec_module(fashion)
data = fashion.data
SOURCE = CLIENT / data.CLIENT_DIR
BACKUP = ROOT / '.local/client-patches/fashion-shop-20261115-backup' / fashion.INDEX_NAME


def baseline_index():
    for path in (SOURCE / fashion.INDEX_NAME, BACKUP):
        if path.is_file() and data.sha(path.read_bytes()) == fashion.INDEX_SHA:
            return path
    return None


@unittest.skipUnless(baseline_index() is not None and (SOURCE / (fashion.CHUNK_MD5 + '.bytes')).is_file(),
                     '缺少已核对的 4.6 客户端资料')
class FashionDataTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.client = self.root / 'isolated client'
        self.directory = self.client / data.CLIENT_DIR
        self.directory.mkdir(parents=True)
        current = baseline_index().read_bytes()
        self.assertEqual(fashion.INDEX_SHA, data.sha(current))
        (self.directory / fashion.INDEX_NAME).write_bytes(current)
        shutil.copyfile(SOURCE / (fashion.CHUNK_MD5 + '.bytes'), self.directory / (fashion.CHUNK_MD5 + '.bytes'))
        self.launch = fashion.launcher()
        files = {}
        for name in self.launch.REQUIRED_FILES:
            path = self.client / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(name.encode('utf-8'))
            files[name] = data.sha(path.read_bytes())
        lua_prefix = data.lua.LUA
        lua_names = ['M_LuaV.bytes', 'LuaV_' + 'a' * 32 + '.bytes', 'b' * 32 + '.bytes', 'c' * 32 + '.bytes']
        lua_files = {}
        for name in lua_names:
            relative = lua_prefix + name
            path = self.client / relative
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(name.encode('ascii'))
            lua_files[relative] = data.sha(path.read_bytes())
        self.manifest = self.root / 'manifest.json'
        self.manifest.write_text(json.dumps({'schema': 2, 'clientRoot': str(self.client), 'clientFiles': files,
            'clientLuaFiles': lua_files, 'debugConsole': {'mode': 'external', 'sha256': '0' * 64,
            'backupPath': 'console.dll'}, 'service': {'readyUrl': 'http://127.0.0.1:18080/health/ready',
            'startCommand': 'fixture'}}), encoding='utf-8')
        self.prepared = self.root / 'prepared'

    def plan(self):
        return fashion.make_plan(self.client, self.manifest, self.launch)

    def test_original_903_rows_and_existing_skin_offers_are_unchanged(self):
        plan = self.plan()
        old_chunks = data.read_index(plan['oldIndex'], index_sha=fashion.INDEX_SHA)
        new_chunks = data.read_index(plan['index'], index_sha=data.sha(plan['index']))
        old_chunk, old_entry = next((c, e) for c in old_chunks for e in c['entries'] if e['key'] == data.RECORD_KEY)
        new_chunk, new_entry = next((c, e) for c in new_chunks for e in c['entries'] if e['key'] == data.RECORD_KEY)
        old_bytes = (self.directory / (fashion.CHUNK_MD5 + '.bytes')).read_bytes()
        old = old_bytes[old_entry['offset']:old_entry['offset'] + old_entry['length']]
        new = plan['chunk'][new_entry['offset']:new_entry['offset'] + new_entry['length']]
        before, after = data.decode_table(old), data.decode_table(new)
        self.assertEqual(fashion.RECORD_SHA, data.sha(old))
        self.assertEqual((903, 912), (len(before), len(after)))
        self.assertEqual(before, after[:903])
        self.assertEqual({r[0] for r in data.FASHION_ENTRIES}, {row['GoodsID'] for row, _ in after[903:]})
        self.assertEqual(old_chunk['base'], new_chunk['base'])
        self.assertEqual(hashlib.md5(plan['chunk']).hexdigest(), new_chunk['digest'])
        self.assertEqual(len(old_chunks), len(new_chunks))
        self.assertEqual(1, sum(a['digest'] != b['digest'] for a, b in zip(old_chunks, new_chunks)))
        for old_entry, new_entry in zip(old_chunk['entries'], new_chunk['entries']):
            self.assertEqual(old_entry['key'], new_entry['key'])
            if old_entry['key'] != data.RECORD_KEY:
                a = old_bytes[old_entry['offset']:old_entry['offset'] + old_entry['length']]
                b = plan['chunk'][new_entry['offset']:new_entry['offset'] + new_entry['length']]
                self.assertEqual(a, b)

    def test_prepare_install_backup_and_idempotence_on_disposable_client(self):
        old_index = (self.directory / fashion.INDEX_NAME).read_bytes()
        with patch.object(fashion, 'launcher', return_value=self.launch), \
             patch.object(self.launch, 'client_running', return_value=False):
            report = fashion.prepare(self.client, self.prepared, self.manifest)
            self.assertFalse(report['clientModified'])
            result = fashion.install(self.prepared, self.root / 'backup', self.manifest)
            self.assertTrue(result['clientModified'])
            self.assertEqual(old_index, (self.root / 'backup' / fashion.INDEX_NAME).read_bytes())
            self.assertEqual(report['newIndexSHA256'], data.sha((self.directory / fashion.INDEX_NAME).read_bytes()))
            self.assertTrue(fashion.install(self.prepared, self.root / 'again', self.manifest)['alreadyInstalled'])
            self.assertFalse((self.root / 'again').exists())

    def test_running_game_or_changed_lua_never_changes_the_client_copy(self):
        old = (self.directory / fashion.INDEX_NAME).read_bytes()
        with patch.object(fashion, 'launcher', return_value=self.launch), \
             patch.object(self.launch, 'client_running', return_value=False):
            fashion.prepare(self.client, self.prepared, self.manifest)
            with patch.object(self.launch, 'client_running', return_value=True):
                with self.assertRaisesRegex(RuntimeError, '运行'):
                    fashion.install(self.prepared, self.root / 'backup', self.manifest)
            manifest = json.loads(self.manifest.read_text('utf-8'))
            first = next(iter(manifest['clientLuaFiles']))
            (self.client / first).write_bytes(b'changed')
            with self.assertRaisesRegex(ValueError, 'Lua 资源变化'):
                fashion.install(self.prepared, self.root / 'backup', self.manifest)
        self.assertEqual(old, (self.directory / fashion.INDEX_NAME).read_bytes())
        self.assertFalse((self.root / 'backup').exists())

    def test_forged_prepared_data_and_conflicting_new_id_are_rejected(self):
        old = (self.directory / fashion.INDEX_NAME).read_bytes()
        with patch.object(fashion, 'launcher', return_value=self.launch), \
             patch.object(self.launch, 'client_running', return_value=False):
            report = fashion.prepare(self.client, self.prepared, self.manifest)
            chunk = self.prepared / report['chunkName']
            chunk.write_bytes(chunk.read_bytes() + b'changed')
            report['chunkSHA256'] = data.sha(chunk.read_bytes())
            (self.prepared / 'prepared.json').write_text(json.dumps(report), encoding='utf-8')
            with self.assertRaises(ValueError):
                fashion.install(self.prepared, self.root / 'backup', self.manifest)
        self.assertEqual(old, (self.directory / fashion.INDEX_NAME).read_bytes())
        self.assertFalse((self.root / 'backup').exists())

        plan = self.plan()
        chunks = data.read_index(plan['oldIndex'], index_sha=fashion.INDEX_SHA)
        entry = next(e for c in chunks for e in c['entries'] if e['key'] == data.RECORD_KEY)
        payload = (self.directory / (fashion.CHUNK_MD5 + '.bytes')).read_bytes()
        raw = payload[entry['offset']:entry['offset'] + entry['length']]
        patched = data.append_fashion_rows(raw)
        with self.assertRaisesRegex(ValueError, '编号冲突'):
            data.append_fashion_rows(patched)


if __name__ == '__main__':
    unittest.main()
