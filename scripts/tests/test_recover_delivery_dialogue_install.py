import importlib.util,json,tempfile,unittest
from pathlib import Path

SCRIPTS=Path(__file__).resolve().parents[1]
spec=importlib.util.spec_from_file_location('recover_dialogue_install',SCRIPTS/'install-recover-delivery-dialogues.py')
module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module)

class InstallTests(unittest.TestCase):
    def root(self,directory):
        root=Path(directory)/'resources';(root/'ExcelOutput').mkdir(parents=True)
        (root/'ExcelOutput/PerformanceE.json').write_text('[]')
        return root
    def test_add_only_and_matching_retry_preserve_all_existing_bytes(self):
        with tempfile.TemporaryDirectory()as directory:
            root=self.root(directory);result=module.install(root);self.assertEqual(14,len(result['created']))
            files={p:p.read_bytes()for p in root.rglob('*')if p.is_file()}
            retry=module.install(root);self.assertEqual([],retry['created']);self.assertEqual(14,len(retry['presentMatching']))
            self.assertEqual(files,{p:p.read_bytes()for p in root.rglob('*')if p.is_file()})
    def test_conflict_is_detected_before_any_missing_file_is_installed(self):
        with tempfile.TemporaryDirectory()as directory:
            root=self.root(directory)
            fixture=json.loads(module.FIXTURE.read_text('utf-8'));conflict=root/fixture['sourceOnlyPaths'][-1]
            conflict.parent.mkdir(parents=True);conflict.write_bytes(b'{"operator":true}')
            before={p:p.read_bytes()for p in root.rglob('*')if p.is_file()}
            with self.assertRaisesRegex(ValueError,'冲突'):module.install(root)
            self.assertEqual(before,{p:p.read_bytes()for p in root.rglob('*')if p.is_file()})

if __name__=='__main__':unittest.main()
