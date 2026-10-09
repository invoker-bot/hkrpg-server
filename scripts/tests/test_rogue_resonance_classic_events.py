"""九条经典参数目录的原始布局、选中能力、绑定和完整执行范围边界。"""
import importlib.util
from pathlib import Path
from types import SimpleNamespace
import unittest

ROOT = Path(__file__).resolve().parents[2]
CLIENT = Path(r'C:\Users\InvokerBot\AppData\Local\hoyo\hkrpg\versions\current')
spec = importlib.util.spec_from_file_location('classic_events', ROOT/'scripts/read-rogue-resonance-classic-events.py')
classic = importlib.util.module_from_spec(spec)
spec.loader.exec_module(classic)


class ClassicEventsTest(unittest.TestCase):
    def test_previous_factory_executor_and_additional_config_layout_readers_match_client(self):
        self.assertEqual((2, 1567), classic.params.verify_native(CLIENT))
        self.assertEqual((5, 1855), classic.parent.verify_native(CLIENT))
        self.assertEqual(classic.parent.camera.load_profile()['nativeBytes'], classic.parent.camera.verify_native(CLIENT)[1])

    def test_all_nine_source_event_ids_bindings_and_declarations_are_frozen(self):
        p = classic.load_profile()
        definitions = p['classicEventDefinitions']
        self.assertEqual(list(range(41001, 41010)), [d['eventId'] for d in definitions])
        self.assertEqual(155, sum(len(d['bindings']) for d in definitions))
        self.assertEqual(154, sum(len(d['sourceDeclarations']) for d in definitions))
        self.assertTrue(all(d['eventIdRaw']==d['eventId'] << 33 and d['taskId']==1122
            and d['team']==3 and d['totalDamageTeam']==1 for d in definitions))

    def test_fresh_original_paths_layouts_and_all_nine_definitions_match(self):
        definitions = classic.source_definitions(CLIENT)
        self.assertEqual(classic.load_profile()['classicEventDefinitions'], definitions)
        self.assertEqual(['StageAbility_612720', 'StageAbility_612820'], [d['abilityName'] for d in definitions[-2:]])

    def test_additional_layouts_select_complete_961_and_387_byte_abilities(self):
        a, b = classic.load_profile()['additionalAbilities']
        self.assertEqual((11167, 12128, 36), (a['offset'], a['endOffset'], a['layoutCount']))
        self.assertEqual((2, 389, 36), (b['offset'], b['endOffset'], b['layoutCount']))
        self.assertEqual('Config/ConfigAbility/Level/Level_RogueBuff_Ability_DLC1.json', a['sourceRecord']['sourcePath'])
        self.assertEqual('Config/ConfigAbility/Level/Level_RogueBuff_Ability_Erudition.json', b['sourceRecord']['sourcePath'])
        self.assertEqual(40044, sum(r['length'] for r in classic.load_profile()['records']))

    def test_propagation_fixed_egg_limit_does_not_read_an_invented_source_variable(self):
        d = classic.load_profile()['classicEventDefinitions'][-2]
        self.assertEqual(19, len(d['bindings']))
        self.assertEqual(['Egg_MaxLayer_612720', {'mode':0, 'fixedSignedRaw':0}], d['bindings'][-1])
        self.assertEqual(18, len(d['sourceDeclarations']))
        self.assertEqual([{'key':-1202971289, 'fixedSignedRaw':1 << 33, 'rawReadType':0}],
            [v for v in d['sourceDeclarations'] if v['fixedSignedRaw']])

    def test_erudition_unqueried_second_third_values_remain_original_zero_declarations(self):
        a = classic.load_profile()['additionalAbilities'][-1]['decoded']
        query = a['OnAdd'][0]
        self.assertEqual(2847, query['taskId'])
        entries = query['QueryList']['entries']
        self.assertEqual(['ADF_612821_1', 'ADF_612822_1', 'ADF_612823_1'], [e[0] for e in entries])
        declarations = {v['key']:v['fixedSignedRaw'] for v in a['DynamicValues']['floats']}
        self.assertEqual((0, 0), (declarations[1419155902], declarations[-146928039]))
        self.assertTrue(all('Level' not in e[1] for e in entries))

    def test_extra_modifier_task_is_preserved_as_source_and_remains_unexecuted(self):
        a = classic.load_profile()['additionalAbilities'][0]['decoded']
        self.assertEqual([2847, 1122, 50], [t['taskId'] for t in a['OnAdd']])
        modifier = a['OnAdd'][-1]
        self.assertEqual('Caster', modifier['TargetType']['Alias'])
        self.assertEqual('MAvatar_RogueBattleevent127_AimTarget', modifier['ModifierName']['Value'])
        self.assertFalse(classic.load_profile()['abilityExecutionVerified'])

    def test_every_variable_binding_preserves_original_program_and_exact_source_key(self):
        count = 0
        for d in classic.load_profile()['classicEventDefinitions']:
            names = [pair[0] for pair in d['bindings']]
            self.assertEqual(len(names), len(set(names)))
            keys = {v['key'] for v in d['sourceDeclarations']}
            for _, value in d['bindings']:
                if value['mode']==0:
                    self.assertEqual(0, value['fixedSignedRaw'])
                    continue
                self.assertEqual('010011', value['rawPostfixBytes'])
                self.assertEqual([], value['fixedSignedConstants'])
                self.assertEqual(1, len(value['variableKeys']))
                self.assertIn(value['variableKeys'][0], keys)
                count += 1
        self.assertEqual(154, count)

    def test_full_evidence_distinguishes_selected_abilities_from_entire_additional_parents(self):
        r = classic.analyze(SimpleNamespace(client_dir=CLIENT, output=ROOT/'build/rogue-resonance-classic-events-test-evidence.json'))
        self.assertEqual((9, 155, 154, 1348), tuple(r[k] for k in ['sourceEventCount', 'sourceParameterBindings', 'sourceDeclarations', 'additionalSelectedAbilityBytes']))
        self.assertTrue(r['additionalSelectedAbilitiesFullyConsumed'])
        self.assertTrue(r['additionalLayoutsFullyConsumed'])
        self.assertFalse(r['additionalParentsFullyConsumed'])
        for key in ['implicitPrefixRemovalImplemented', 'nativeQueryTaskExecuted', 'queryLevelFallbackImplemented',
            'heldBlessingsApplied', 'battleEventCreated', 'callbackExecutionImplemented', 'liveBattleContextAttached',
            'abilityExecutionVerified', 'clientGameplayVerified', 'completeResonanceRuntimeImplemented',
            'jsonInstalled', 'clientExecutableStarted', 'clientModified']:
            self.assertFalse(r[key], key)

    def test_output_guard_rejects_client_and_nonbuild_paths_before_reading(self):
        for target in [CLIENT/'classic.json', ROOT/'classic.json']:
            with self.assertRaises(ValueError):
                classic.analyze(SimpleNamespace(client_dir=CLIENT, output=target))


if __name__ == '__main__':
    unittest.main()
