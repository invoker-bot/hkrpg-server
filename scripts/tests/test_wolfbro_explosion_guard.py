"""只验证新工具的完整原件预检；不启动服务器，也不算原生验收。"""
import copy
import importlib.util
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

SCRIPT = Path(__file__).resolve().parents[1] / 'smoke-wolfbro-ammo.py'
spec = importlib.util.spec_from_file_location('wolf_explosion_smoke_guard', SCRIPT)
smoke = importlib.util.module_from_spec(spec)
spec.loader.exec_module(smoke)


class ExplosionPreflightTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.source = self.root / 'source'
        self.proof = smoke.source_json.loads((smoke.ROOT / 'src/test/resources/fixtures/wolfbro-explosion-4.6.json').read_bytes())
        self.abilities = [record for record in self.proof['sourceFiles'] if 'ConfigAdventureAbility' in record['path']]
        for record in self.abilities:
            path = self.source / record['path']; path.parent.mkdir(parents=True, exist_ok=True)
            # Same complete original object, with an explicitly controlled formatting fixture.
            path.write_text(smoke.source_json.canonical(record['data'])+'\n', encoding='utf-8', newline='\n')
            record['sourceBlobSHA256'] = smoke.sha(path)
        row = self.proof['sourceRows'][0]
        path = self.source / row['sourcePath']; path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(smoke.source_json.canonical([row['data']])+'\n', encoding='utf-8', newline='\n')
        self.prepared = dict(sourceResourceHashes={row['sourcePath']:smoke.sha(path)},
                             copiedRuntimeOriginalFiles=[row['sourcePath']])
        self.fixture = self.root / 'src/test/resources/fixtures/wolfbro-explosion-4.6.json'
        self.fixture.parent.mkdir(parents=True)
        self.write_proof()
        self.addCleanup(patch.stopall)
        patch.object(smoke,'ROOT',self.root).start()

    def write_proof(self):
        self.fixture.write_text(smoke.source_json.canonical(self.proof)+'\n', encoding='utf-8')

    def test_four_complete_abilities_and_the_original_row_are_added_to_physical_copies(self):
        result=smoke.explosion_inputs(self.source,self.prepared)
        self.assertEqual(4,result['completeAbilityFiles'])
        self.assertEqual(1,result['completeExplosivePropRows'])
        self.assertFalse(result['serverPhysicsImplemented'])
        self.assertEqual(5,len(self.prepared['sourceResourceHashes']))
        self.assertEqual(5,len(self.prepared['copiedRuntimeOriginalFiles']))

    def test_failed_late_source_check_does_not_partly_change_the_prepared_lease(self):
        before=copy.deepcopy(self.prepared)
        path=self.source/self.abilities[-1]['path']
        path.write_text(path.read_text('utf-8')+'\n',encoding='utf-8')
        with self.assertRaises(ValueError):smoke.explosion_inputs(self.source,self.prepared)
        self.assertEqual(before,self.prepared)

    def test_an_unreviewed_ability_name_cannot_replace_a_runtime_required_original(self):
        record=self.abilities[0]
        old=self.source/record['path']; record['path']=record['path'].replace('_Ability.json','_Other_Ability.json')
        other=self.source/record['path']; other.write_bytes(old.read_bytes()); self.write_proof()
        with self.assertRaises(ValueError):smoke.explosion_inputs(self.source,self.prepared)

    def test_changed_full_prop_row_rejects_before_adding_any_copy(self):
        before=copy.deepcopy(self.prepared)
        row=self.proof['sourceRows'][0]; path=self.source/row['sourcePath']
        data=copy.deepcopy(row['data']); data['LodPriority']=99
        path.write_text(smoke.source_json.canonical([data]),encoding='utf-8')
        with self.assertRaises(ValueError):smoke.explosion_inputs(self.source,self.prepared)
        self.assertEqual(before,self.prepared)

    def test_missing_source_ability_is_not_an_empty_complete_feature(self):
        self.proof['sourceFiles']=[record for record in self.proof['sourceFiles'] if record is not self.abilities[-1]]
        self.write_proof()
        with self.assertRaises(ValueError):smoke.explosion_inputs(self.source,self.prepared)


if __name__=='__main__':unittest.main()
