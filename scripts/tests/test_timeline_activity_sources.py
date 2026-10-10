"""四个原始非周期时间回溯计数任务：完整来源、维度归属及本机静态协议证据。"""
import hashlib
import json
from pathlib import Path
import re
import subprocess
import unittest

ROOT = Path(__file__).resolve().parents[2]
PIN = '724b139d8c9c32d12552eb95745a4fee72bfe48b'
IDS = set(range(6029846, 6029850))
KEYS = {'QuestData':'QuestID','FinishWay':'ID','CycleQuest':'CycleID','QuestTimeLimitConfig':'QuestID',
        'ActivityQuestTimeLimitGroup':'QuestTimeLimitGroupID','ActivityConfig':'ActivityID','ActivityPanel':'PanelID',
        'FuncUnlockData':'UnlockID','RewardData':'RewardID','ItemConfig':'ID','MazePlane':'PlaneID','MazeProp':'ID',
        'MapEntrance':'ID','MainMission':'MainMissionID'}


def fingerprint(value):
    return hashlib.sha256(json.dumps(value, sort_keys=True, ensure_ascii=False, separators=(',', ':')).encode()).hexdigest()


class TimelineActivitySources(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.fixture = json.loads((ROOT / 'src/test/resources/fixtures/timeline-activity-4.6.json').read_text('utf-8'))
        paths = list(cls.fixture['sourceFiles'])
        result = subprocess.run(['git', '-C', str(ROOT / 'references/TurnBasedGameData'), 'cat-file', '--batch'],
                                input=''.join(PIN + ':' + p + '\n' for p in paths).encode(), capture_output=True, check=True).stdout
        cls.raw = {}
        cursor = 0
        for path in paths:
            end = result.index(b'\n', cursor)
            _, kind, size = result[cursor:end].split()
            assert kind == b'blob'
            cursor = end + 1
            cls.raw[path] = result[cursor:cursor + int(size)]
            cursor += int(size) + 1
        assert cursor == len(result)
        cls.data = {p: json.loads(v) for p, v in cls.raw.items()}
        cls.tables = {Path(p).stem: rows for p, rows in cls.fixture['records'].items()}
        cls.code = (ROOT / 'src/main/java/org/hkrpg/server/gameplay/TimelineActivitySources.java').read_text('utf-8')

    def test_all_175_source_blobs_and_local_resources_match_the_recorded_commit(self):
        self.assertEqual(PIN, self.fixture['sourceCommit'])
        self.assertEqual(175, len(self.raw))
        for path, entry in self.fixture['sourceFiles'].items():
            self.assertEqual(entry['gitBlobSHA256'], hashlib.sha256(self.raw[path]).hexdigest())
            local = (ROOT / 'resources/4.6' / path).read_bytes()
            self.assertEqual(entry['checkoutSHA256'], hashlib.sha256(local).hexdigest())
            self.assertEqual(self.data[path], json.loads(local))

    def test_all_56_whole_rows_and_runtime_pins_preserve_original_fields(self):
        self.assertEqual(14, len(self.tables))
        self.assertEqual(56, sum(map(len, self.tables.values())))
        pins = {}
        for path, records in self.fixture['records'].items():
            table = Path(path).stem
            for row in records:
                self.assertIn(row, self.data[path])
                pins[table + ':' + str(row[KEYS[table]])] = fingerprint(row)
        actual = dict(re.findall(r'Map\.entry\(\s*"([^"\n]+:[^"\n]+)"\s*,\s*"([0-9a-f]{64})"', self.code))
        self.assertEqual(pins, actual)

    def test_four_nonperiodic_quests_keep_real_three_count_goals_and_panel_ownership(self):
        quests = {r['QuestID']: r for r in self.tables['QuestData']}
        cycles = {r['CycleID']: r for r in self.tables['CycleQuest']}
        finishes = {r['ID']: r for r in self.tables['FinishWay']}
        self.assertEqual(IDS, set(quests))
        self.assertEqual(IDS, set(cycles))
        for id in IDS:
            self.assertEqual(3, quests[id]['QuestType'])
            self.assertEqual(id, quests[id]['FinishWayID'])
            self.assertEqual(('TimeLineSetStateCnt', 'Equal', 3, id),
                             tuple(finishes[id][k] for k in ['FinishType','ParamType','Progress','ParamInt1']))
            self.assertEqual([id], cycles[id]['QuestList'])
            self.assertTrue(cycles[id]['IsNonPeriodic'])
            self.assertEqual(3001301, cycles[id]['ActivityModuleID'])
            self.assertEqual(list(range(1, 8)), cycles[id]['WeekDayList'])
        group = self.tables['ActivityQuestTimeLimitGroup'][0]
        self.assertEqual((22,30013,3001301,'MazeExplore'), tuple(group[k] for k in ['QuestTimeLimitGroupID','ActivityID','ActivityModuleID','UIPanelType']))
        self.assertEqual(IDS, set(group['QuestList']))
        config = self.tables['ActivityConfig'][0]
        self.assertIn(3001301, config['ActivityModuleIDList'])
        self.assertNotIn(3001301, config['ResidentModuleList'])

    def test_four_whole_prerequisite_files_and_unlock_rows_are_preserved(self):
        self.assertEqual(4, len(self.fixture['headers']))
        for path, header in self.fixture['headers'].items():
            self.assertEqual(self.data[path], header)
        prerequisites = self.fixture['prerequisites']
        self.assertEqual(4, len(prerequisites))
        limits = {r['QuestID']:r for r in self.tables['QuestTimeLimitConfig']}
        unlocks = {r['UnlockID']:r for r in self.tables['FuncUnlockData']}
        expected = {6029846:104010102,6029847:104011107,6029848:104010602,6029849:104010966}
        for id, sub in expected.items():
            self.assertEqual([{'Type':'FinishSubMission','Param':str(sub)}], unlocks[limits[id]['UnlockData']]['Conditions'])
            row = next(p for p in prerequisites if p['sub'] == sub)
            self.assertEqual(self.data[row['path']], row['definition'])
            self.assertIn(row['condition'], row['definition']['SubMissionList'])
            self.assertIn('"' + fingerprint(row['definition']) + '"', self.code)

    def test_all_149_whole_groups_have_unique_original_dimension_resource_ownership(self):
        groups = self.fixture['catalogGroups']
        self.assertEqual(149, len(groups))
        seen = set()
        pins = re.findall(r'new Group\(\s*(\d+)\s*,\s*(\d+)\s*,\s*(\d+)\s*,\s*"([^"]+)"\s*,\s*"([a-f0-9]{64})"\s*,\s*List\.of\(([^)]*)\)', self.code)
        self.assertEqual(149, len(pins))
        actual = {path: (int(plane),int(floor),int(id),pin,[int(x) for x in dimensions.split(',') if x.strip()]) for plane,floor,id,path,pin,dimensions in pins}
        referenced_props = 0
        for group in groups:
            self.assertEqual(self.data[group['path']], group['definition'])
            floor_path = f"Config/LevelOutput/RuntimeFloor/P{group['plane']}_F{group['floor']}.json"
            floor = self.fixture['floors'][floor_path]
            self.assertEqual(self.data[floor_path], floor)
            indices = {i for i,r in enumerate(floor['GroupInstanceList']) if r['ID']==group['group'] and r['GroupPath']==group['path']}
            self.assertTrue(indices)
            dims = sorted({d.get('ID',0) for d in floor['DimensionList'] if indices.intersection(d.get('GroupIndexList',[]))})
            self.assertEqual((group['plane'],group['floor'],group['group'],fingerprint(group['definition']),dims), actual[group['path']])
            for dimension in dims:
                identity = group['plane'],group['floor'],dimension,group['group']
                self.assertNotIn(identity, seen)
                seen.add(identity)
            for prop in group['definition'].get('PropList',[]):
                if not prop.get('IsDelete',False) and not prop.get('IsClientOnly',False) and prop.get('LoadOnInitial',True) and any(t.get('$type')=='RPG.GameCore.PropTagTimeRewindEntityComponent' for t in prop.get('TagOverrideComponents',{}).get('TagComponents',[])):
                    referenced_props += 1
        self.assertEqual(221, referenced_props)
        # Unreferenced old variants are retained as source evidence, never auto-enabled.
        self.assertEqual([], actual[next(p for p in actual if p.endswith('_G88_D1001.json'))][-1])

    def test_original_rewards_keep_120_hcoin_and_exact_currency_and_material_counts(self):
        rewards = self.tables['RewardData']
        totals = {1: sum(r.get('Hcoin',0) for r in rewards)}
        for row in rewards:
            for key,id in row.items():
                if key.startswith('ItemID_') and id:
                    totals[id] = totals.get(id,0) + row['Count_' + key.split('_')[1]]
        self.assertEqual({1:120,2:60000,110202:6,213:4}, totals)

    def test_original_components_with_missing_initial_states_are_not_rewritten(self):
        count = 0
        for group in self.fixture['catalogGroups']:
            for prop in group['definition'].get('PropList',[]):
                for tag in prop.get('TagOverrideComponents',{}).get('TagComponents',[]):
                    if tag.get('$type') == 'RPG.GameCore.PropTagTimeRewindEntityComponent' and 'InitState' not in tag:
                        count += 1
                        self.assertEqual({'Tag':'Prop.Component.TimeRewindEntity','TagHash':532483231}, tag['Tag'])
        self.assertEqual(28, count)

    def test_native_command_field_order_and_control_evidence_are_static_only(self):
        evidence = self.fixture['clientEvidence']
        self.assertEqual(915, evidence['nativeSendCommand'])
        self.assertEqual({'quest_id':7,'prop_id':8,'group_id':11}, evidence['requestFields'])
        self.assertEqual((531989,560635,216045), tuple(evidence[k] for k in ['nativeSaveCurrentState','nativeTryFinishQuest','nativeQuestWriteTo']))
        self.assertEqual((29,9), tuple(evidence[k] for k in ['nativeIsInControlSlot','nativeIsActiveTargetSlot']))
        self.assertTrue(evidence['finishRequestPrecedesTimelineRequest'])
        self.assertFalse(evidence['nativePerTupleCooldownEnabled'])
        for key in ['clientExecutableStarted','clientModified','clientAccepted']:
            self.assertFalse(evidence[key])
        self.assertFalse(self.fixture['clientGameplayVerified'])


if __name__ == '__main__':
    unittest.main()
