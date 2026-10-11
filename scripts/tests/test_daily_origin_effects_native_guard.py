"""Tool-only report fixtures; no server state, resource files, or live goals are manufactured."""
import copy
import importlib.util
from pathlib import Path
import unittest

OWN=Path(__file__).resolve().parents[1]
spec=importlib.util.spec_from_file_location('effects_smoke_guard',OWN/'smoke-daily-origin-effects.py')
smoke=importlib.util.module_from_spec(spec);spec.loader.exec_module(smoke)


def fixture():
    snapshots=[]
    for day in (1,2):
        actors=[dict(kind='npc',instance=400001,id=10209,entity=11),dict(kind='npc',instance=400002,id=10215,entity=12)]
        actors.extend(dict(kind='prop',instance=instance,id=100,entity=index)for index,instance in enumerate((300001,300002,300003),21))
        snapshots.append(dict(phase='initial',dayIndex=day,originalInitialGroupActorWireIdentities=actors,
            originalInitialNpcRemovalWireIdsBeforeAck=[11,12],originalInitialEffectsSceneViews=[{},{}]))
    receipts={str(day):dict(event=f'event-{day}',mainAcceptance=f'main-{day}',initialAcceptance=f'initial-{day}',
        source='whole-fixed-source')for day in (1,2)}
    one=dict(actualNativeActorAndAcceptanceEffectsVerified=True,originalInitialNpcEffectReceipts={'1':copy.deepcopy(receipts['1'])})
    two=dict(actualNativeActorAndAcceptanceEffectsVerified=True,originalInitialNpcEffectReceipts=copy.deepcopy(receipts))
    return dict(scenario='settled',branch='102',snapshots=snapshots,readOnlyPhoneH2Inspections=[one,two])


class EffectsToolGuardTest(unittest.TestCase):
    def test_complete_observed_fixture_is_consistent(self):
        self.assertTrue(smoke.verify_effect_case(fixture())['passed'])

    def test_missing_or_duplicate_wire_removal_is_rejected(self):
        for ids in ([11],[11,11],[11,99]):
            with self.subTest(ids=ids):
                value=fixture();value['snapshots'][0]['originalInitialNpcRemovalWireIdsBeforeAck']=ids
                with self.assertRaises(AssertionError):smoke.verify_effect_case(value)

    def test_original_actors_cannot_be_replaced_by_foreign_instances(self):
        value=fixture();value['snapshots'][0]['originalInitialGroupActorWireIdentities'][0]['instance']=400003
        with self.assertRaises(AssertionError):smoke.verify_effect_case(value)

    def test_a_report_receipt_cannot_change_after_a_read(self):
        value=fixture();value['readOnlyPhoneH2Inspections'][1]['originalInitialNpcEffectReceipts']['1']['source']='foreign'
        with self.assertRaises(AssertionError):smoke.verify_effect_case(value)

    def test_a_new_day_must_have_its_own_event_and_acceptances(self):
        for key in ('event','mainAcceptance','initialAcceptance'):
            with self.subTest(key=key):
                value=fixture();rows=value['readOnlyPhoneH2Inspections'][1]['originalInitialNpcEffectReceipts'];rows['2'][key]=rows['1'][key]
                with self.assertRaises(AssertionError):smoke.verify_effect_case(value)

    def test_native_reads_cannot_be_reported_verified_when_helper_failed(self):
        value=fixture();value['readOnlyPhoneH2Inspections'][0]['actualNativeActorAndAcceptanceEffectsVerified']=False
        with self.assertRaises(AssertionError):smoke.verify_effect_case(value)

    def test_a_single_period_cannot_be_counted_as_two(self):
        value=fixture();value['readOnlyPhoneH2Inspections'][1]['originalInitialNpcEffectReceipts'].pop('2')
        with self.assertRaises(AssertionError):smoke.verify_effect_case(value)


if __name__=='__main__':unittest.main()
