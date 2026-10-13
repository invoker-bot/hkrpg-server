import hashlib
import importlib.util
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import subprocess
import sys

ROOT=Path(__file__).resolve().parents[2]
spec=importlib.util.spec_from_file_location('recovery_tool',ROOT/'scripts/smoke-daily-recover-delivery.py')
tool=importlib.util.module_from_spec(spec);spec.loader.exec_module(tool)


class RecoverDeliveryFixtureGuard(unittest.TestCase):
    def test_completed_probe_cleanup_uses_the_actual_support_signature(self):
        with tempfile.TemporaryDirectory(prefix='recovery-cleanup-guard-')as directory:
            process=subprocess.Popen([sys.executable,'-c','print(123)'],stdout=subprocess.PIPE,stderr=subprocess.STDOUT,text=True)
            raw=process.communicate(timeout=10)[0];log=Path(directory)/'probe.log';failure=None
            try:tool.finish_probe(process,None,log,[raw])
            except Exception as error:failure=error
            self.assertIsNone(failure,'A completed actual subprocess must not lose its wire output to a cleanup signature error')
            self.assertEqual(raw,log.read_text())

    def test_cleanup_failure_cannot_discard_the_captured_wire_output(self):
        with tempfile.TemporaryDirectory(prefix='recovery-retain-guard-')as directory:
            log=Path(directory)/'probe.log';raw='{"passed":true,"requests":17}\n'
            with patch.object(tool.shared,'cleanup',side_effect=RuntimeError('cleanup failed')):
                with self.assertRaisesRegex(RuntimeError,'cleanup failed'):tool.finish_probe(None,None,log,[raw])
            self.assertTrue(log.exists(),'Wire evidence must be saved before an independent cleanup failure')
            self.assertEqual(raw,log.read_text())

    def test_number_one_cannot_match_boolean_true_in_complete_originals(self):
        self.assertNotEqual(tool.shared.complete_json(b'{"ID":1}'),tool.shared.complete_json(b'{"ID":true}'))

    def test_precise_integer_and_decimal_originals_do_not_pass_through_float(self):
        self.assertEqual(tool.shared.complete_json(b'{"ID":9007199254740993,"value":1e-9}'),
            tool.shared.complete_json(b'{"ID":9007199254740993,"value":0.000000001}'))
        self.assertNotEqual(tool.shared.complete_json(b'{"ID":9007199254740993}'),tool.shared.complete_json(b'{"ID":9007199254740992}'))

    def test_detaching_filtered_catalog_preserves_actual_operator_hardlink_bytes(self):
        with tempfile.TemporaryDirectory(prefix='recovery-link-guard-')as directory:
            root=Path(directory);source=root/'source';source.mkdir();fixture=root/'fixture'
            files={'ExcelOutput/DailyMissionData.json':json.dumps([{'ID':3011014},{'ID':3011605}]).encode(),
                'ExcelOutput/DailyActiveQuestPool.json':json.dumps([{'QuestID':2100003},{'QuestID':2100004}]).encode(),
                'Config/Level/Mission/3011014/MissionInfo_3011014.json':b'{}','Other/unchanged.json':b'{}'}
            optional={'Config/Level/NPCDialogue/OriginalUninstalled.json'}
            for name,raw in files.items():path=source/name;path.parent.mkdir(parents=True,exist_ok=True);path.write_bytes(raw)
            before={name:hashlib.sha256(raw).hexdigest()for name,raw in files.items()}
            view=tool.materialize(source,fixture,files,optional)
            self.assertEqual(0,view['directoryJunctionCount']);self.assertFalse(view['ordinaryOperatorFilesWritten'])
            self.assertEqual([{'ID':3011014}],json.loads((fixture/'ExcelOutput/DailyMissionData.json').read_text()))
            self.assertFalse(os.path.samefile(source/'ExcelOutput/DailyMissionData.json',fixture/'ExcelOutput/DailyMissionData.json'))
            self.assertTrue(all(hashlib.sha256((source/name).read_bytes()).hexdigest()==digest for name,digest in before.items()))
            self.assertFalse((fixture/next(iter(optional))).exists())

    def test_complete_fixture_retains_all_three_choices_and_eleven_source_only_graphs(self):
        fixture=json.loads(tool.FIXTURE.read_text('utf-8'))
        self.assertEqual(68,len(fixture['sourceFiles']));self.assertEqual(195,len(fixture['records']))
        optional=[name for name,row in fixture['sourceFiles'].items()if row['installed']is False]
        self.assertEqual(11,len(optional));self.assertTrue(all(name.startswith('Config/Level/NPCDialogue/')for name in optional))
        records=[row['row']for row in fixture['records']if row['source']=='ExcelOutput/ItemConfig.json']
        self.assertTrue({140146,140147,140148}.issubset({row['ID']for row in records}))


if __name__=='__main__':unittest.main(verbosity=2)
