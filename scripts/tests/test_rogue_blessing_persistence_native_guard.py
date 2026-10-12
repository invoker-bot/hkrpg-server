"""Offline guards for real-command ownership and complete observation/checkpoint boundaries."""
import importlib.util,json,sys,unittest
from pathlib import Path
HERE=Path(__file__).resolve().parent
ROOT=next(path for path in HERE.parents if (path/'src/main/resources/protocol/4.6').is_dir())
WRAPPER=HERE/'smoke-rogue-blessing-persistence.py'
if not WRAPPER.is_file():WRAPPER=ROOT/'scripts/smoke-rogue-blessing-persistence.py'
spec=importlib.util.spec_from_file_location('owned_blessing_native',WRAPPER)
tool=importlib.util.module_from_spec(spec);spec.loader.exec_module(tool)

def source_path(name):
    candidate=HERE/name
    if candidate.is_file():return candidate
    if name=='smoke-rogue-blessing-persistence.py':return WRAPPER
    return next(path for path in tool.JAVA_SOURCES if path.name==name)

class Guard(unittest.TestCase):
    def test_fixed_real_fields_are_not_fake_commands(self):
        source=source_path('NativeRogueBlessingPersistenceProbe.java').read_text('utf-8')
        self.assertIn('reply(label,"OFEDNNJIJFL",5676',source)
        self.assertIn('findFieldByNumber(roll?51:1474)',source)
        self.assertIn('source-selection-oneof1474',source)
        self.assertIn('Ability-UseID-not-scene-entity',source)
    def test_bridge_command_label_matches_actual_fixture_command(self):
        bridge=tool.ConsoleBridge('initial',12)
        self.assertEqual('rogueblessingfixture 10001 seed 12\n',bridge.next_command('ROGUE_BLESSING_READY_UID=10001:seed'))
        self.assertEqual('ROGUE_BLESSING_OBSERVED:seed\n',bridge.next_acknowledgement('HKRPG_ROGUE_BLESSING_ACK:10001:seed:12'))
    def test_unowned_uid_cannot_seed(self):
        with self.assertRaises(ValueError):tool.ConsoleBridge('initial',12).next_command('ROGUE_BLESSING_READY_UID=10002:seed')
    def test_operations_cannot_skip_the_explicit_fixture_step(self):
        with self.assertRaises(ValueError):tool.ConsoleBridge('initial',12).next_command('ROGUE_BLESSING_READY_UID=10001:before-reroll')
    def test_overlapping_commands_are_rejected(self):
        bridge=tool.ConsoleBridge('initial',12);bridge.next_command('ROGUE_BLESSING_READY_UID=10001:seed')
        with self.assertRaises(ValueError):bridge.next_command('ROGUE_BLESSING_READY_UID=10001:before-query')
    def test_stale_ack_does_not_advance_nonce(self):
        bridge=tool.ConsoleBridge('initial',12);bridge.next_command('ROGUE_BLESSING_READY_UID=10001:seed')
        self.assertIsNone(bridge.next_acknowledgement('HKRPG_ROGUE_BLESSING_ACK:10001:seed:11'));self.assertFalse(bridge.complete)
    def test_failure_marker_is_immediate_and_owned(self):
        bridge=tool.ConsoleBridge('initial',12);bridge.next_command('ROGUE_BLESSING_READY_UID=10001:seed')
        self.assertEqual('seed',bridge.next_failure('HKRPG_ROGUE_BLESSING_FAILURE:10001:seed:12'))
    def test_bad_failure_marker_is_rejected(self):
        bridge=tool.ConsoleBridge('initial',12);bridge.next_command('ROGUE_BLESSING_READY_UID=10001:seed')
        with self.assertRaises(ValueError):bridge.next_failure('HKRPG_ROGUE_BLESSING_FAILURE:10002:seed:12')
    def test_both_phases_have_distinct_complete_observations(self):
        combined=[value for rows in tool.OPERATIONS.values() for value in rows]
        self.assertEqual(len(combined),len(set(combined)))
        command=source_path('RogueBlessingPersistenceFixtureCommand.java').read_text('utf-8')
        for value in combined:self.assertIn('"'+value+'"',command)
    def test_checkpoint_accepts_only_actual_pending_login_delta(self):
        before=dict(livePlayer=dict(lastActiveTime=20,gameplay={'rogue':{'offers':[1]}}),storedPlayerDocument=dict(lastActiveTime=10,gameplay={'rogue':{'offers':[1]}}),gameplay={'rogue':{'offers':[1]}})
        after=json.loads(json.dumps(before));after['storedPlayerDocument']['lastActiveTime']=20
        self.assertEqual({'lastActiveTime':{'before':10,'after':20}},tool.validate_login_checkpoint(before,after))
    def test_checkpoint_cannot_hide_other_player_changes(self):
        before=dict(livePlayer=dict(lastActiveTime=20),storedPlayerDocument=dict(lastActiveTime=10,coins=1));after=json.loads(json.dumps(before));after['storedPlayerDocument'].update(lastActiveTime=20,coins=2)
        with self.assertRaises(ValueError):tool.validate_login_checkpoint(before,after)
    def test_complete_snapshot_is_written_before_asserting(self):
        source=source_path('RogueBlessingPersistenceFixtureSupport.java').read_text('utf-8')
        self.assertLess(source.index('write(cwd.resolve("rogue-blessing-"+operation+".json"),observed)'),source.index('if(!before) {'))
    def test_no_offers_choices_or_save_fault_are_seeded(self):
        support=source_path('RogueBlessingPersistenceFixtureSupport.java').read_text('utf-8')
        self.assertNotIn('run.offer(',support);self.assertNotIn('run.reroll(',support);self.assertNotIn('setRogue(',support)
        self.assertIn('List.of(4030001,4030002,1010403)',support);self.assertIn('getRogueTalents().add(11)',support)
        self.assertNotIn('failNext',support)
    def test_no_runtime_overlay_or_mutable_main_is_allowed(self):
        with self.assertRaises(ValueError):tool.runtime_capabilities(Path('unused'),True,Path('unused'))
        source=source_path('smoke-rogue-blessing-persistence.py').read_text('utf-8')
        self.assertNotIn('build/classes/java/main',source);self.assertIn('preflightProductionOverlay',source)
    def test_readonly_inspector_never_saves(self):
        source=source_path('RogueBlessingPersistenceInspector.java').read_text('utf-8')
        self.assertIn('.readOnly().open()',source);self.assertIn('first!=second',source);self.assertNotIn('.save(',source)
    def test_source_file_count_is_dynamic_and_only_one_actual_jar_is_used(self):
        source=source_path('smoke-rogue-blessing-persistence.py').read_text('utf-8')
        self.assertIn("glob('*.json')",source);self.assertIn('isolatedConfigSHA256',source)
        probe=source_path('NativeRogueBlessingPersistenceProbe.java').read_text('utf-8')
        self.assertIn('one-actual-frozen-production-CodeSource',probe)
    def test_untouched_old_tools_are_only_read_for_transport(self):
        self.assertIn("BASE_PATH=ROOT/'scripts/smoke-chess-rogue-merchant.py'",source_path('smoke-rogue-blessing-persistence.py').read_text('utf-8'))
    def test_production_uri_spelling_uses_exact_canonical_paths(self):
        source=source_path('NativeRogueBlessingPersistenceProbe.java').read_text('utf-8')
        self.assertIn('Path.of(java.net.URI.create(origin)).toRealPath().equals(expectedPath)',source)
        self.assertNotIn('origin.equals(input.get("jarSourceURI")',source)
    def test_seed_observation_survives_incorrect_qualification(self):
        source=source_path('RogueBlessingPersistenceFixtureSupport.java').read_text('utf-8')
        self.assertLess(source.index('write(cwd.resolve("rogue-blessing-fixture-setup-observed.json"),report)'),source.index('require(RogueUnlocks.open(player,12001)'))
    def test_original_first_world_gate_is_recursively_bound(self):
        source=source_path('smoke-rogue-blessing-persistence.py').read_text('utf-8')
        self.assertIn("12001:('RogueFinishUnlock',[11,11007],2)",source)
        self.assertIn("11:('FinishMission',[4030001,4030002],2)",source)
        self.assertIn("11007:('FinishMission',[1010403],1)",source)

if __name__=='__main__':unittest.main()
