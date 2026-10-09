"""原始命途目录、完整静态PE证据与冻结wire字段；不证明官方UI或回响求值。"""
import hashlib
import json
from pathlib import Path
import re
import unittest

ROOT = Path(__file__).resolve().parents[2]

class RogueAeonSourcesTest(unittest.TestCase):
    def setUp(self):
        self.proof = json.loads((ROOT / 'src/test/resources/fixtures/rogue-aeon-catalogue-4.6.json').read_text('utf-8'))

    def test_all_original_paths_groups_and_unlock_graph_are_retained(self):
        p = self.proof
        self.assertEqual('724b139d8c9c32d12552eb95745a4fee72bfe48b', p['sourceCommit'])
        self.assertEqual(list(range(1,10)),[r['AeonID'] for r in p['aeons']])
        self.assertEqual([1,2,7,5,6,3,4,8,9],[r['AeonID'] for r in sorted(p['aeons'],key=lambda r:r['Sort'])])
        self.assertEqual({r[k] for r in p['aeons'] for k in ['BattleEventBuffGroup','BattleEventEnhanceBuffGroup']},
                         {r['GMLOGNJAIGI'] for r in p['groups']})
        self.assertEqual(18,len(p['groups']))
        self.assertTrue(all(r['HECJCAMDGNO'] for r in p['groups']))
        self.assertEqual({r.get('UnlockID',0) for r in p['aeons']} - {0},{r['RogueUnlockID'] for r in p['unlocks']})
        self.assertEqual({r['UnlockFinishWay'] for r in p['unlocks']},{r['ID'] for r in p['rules']})
        for file in p['files'].values():
            for key in ['resourceSHA256','originalBlobSHA256']:
                self.assertRegex(file[key],'^[0-9a-f]{64}$')

    def test_unknown_kill_conditions_are_not_replaced_by_fabricated_completion(self):
        rules = self.proof['rules']
        self.assertEqual([2,3,4,5],[r['ParamInt1'] for r in rules if r['FinishType'] == 'RogueKillMonster'])
        self.assertEqual([[8013106],[8016105]],[r['ParamIntList'] for r in rules if r['FinishType'] == 'FinishMission'])
        self.assertEqual([1] * 6,[r['Progress'] for r in rules])

    def test_complete_bounded_native_bytes_and_class_identities_are_preserved(self):
        methods = self.proof['nativeMethods']
        self.assertEqual(20,len(methods))
        self.assertEqual(20,len({r['index'] for r in methods}))
        self.assertEqual(7378,sum(len(bytes.fromhex(r['bytesHex'])) for r in methods))
        for method in methods:
            raw = bytes.fromhex(method['bytesHex'])
            self.assertEqual('PE Exception Directory',method['bounds'])
            self.assertEqual(int(method['endRva'],16) - int(method['rva'],16),len(raw))
            self.assertEqual(hashlib.sha256(raw).hexdigest(),method['sha256'])
        self.assertEqual({'AGEHGBKOBEF','EDOIEMCFFCA','HNKAOFDGLIE','GFGHGCIMKGA','CIFCAIEDOLK','JIBBEEAIDIC'},
                         {r['name'] for r in self.proof['nativeMessageIdentities']})

    def test_existing_frozen_protocol_uses_the_matched_wire_numbers(self):
        text = (ROOT / 'protocols/4.6/CmdRogueType.proto').read_text('utf-8')
        for message, field, number in [('StartRogueCsReq','AFEMBLFCCCH',5),('StartRogueCsReq','area_id',10),
                                       ('GFGHGCIMKGA','OPAEGCBNACJ',5),('CIFCAIEDOLK','JPPAGGPJOMO',8)]:
            body = re.search(r'message ' + message + r' \{(.*?)\n\}',text,re.S).group(1)
            self.assertRegex(body,r'\b' + field + r' = ' + str(number) + r';')
        self.assertEqual(1805,self.proof['requestCommand'])
        self.assertTrue(self.proof['sourceOffsetCommentsDoNotMatchInstalledLayout'])
        self.assertTrue(self.proof['selectedAeonResponseWireFieldsMatched'])

    def test_static_selection_and_persistence_do_not_claim_full_resonance_or_ui(self):
        self.assertTrue(self.proof['selectionEntryAndPersistenceImplemented'])
        for key in ['fullSelectionRuntimeImplemented','resonanceRuntimeImplemented',
                    'nativeOfficialSelectionEvaluationVerified','clientGameplayVerified']:
            self.assertIs(False,self.proof[key])

if __name__ == '__main__': unittest.main()
