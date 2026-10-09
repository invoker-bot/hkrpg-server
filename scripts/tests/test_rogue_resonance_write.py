"""两种原始写入任务、默认目标和魅惑门槛的有限来源回归。"""
from copy import deepcopy
import hashlib
import importlib.util
from pathlib import Path
import tempfile
from types import SimpleNamespace
import unittest

import capstone

ROOT=Path(__file__).resolve().parents[2]
CLIENT=Path(r'C:\Users\InvokerBot\AppData\Local\hoyo\hkrpg\versions\current')
spec=importlib.util.spec_from_file_location('resonance_write',ROOT/'scripts/read-rogue-resonance-write.py')
write=importlib.util.module_from_spec(spec)
spec.loader.exec_module(write)


class ResonanceWriteTest(unittest.TestCase):
    def test_bounded_native_tasks_preserve_entry_state_and_evaluation_write_order(self):
        profile=write.load_profile()
        self.assertEqual((12,21166,6),(len(profile['nativeMethods']),profile['nativeBytes'],profile['fragmentBytes']))
        decoder=capstone.Cs(capstone.CS_ARCH_X86,capstone.CS_MODE_64)
        instructions={}
        for method in profile['nativeMethods']:
            raw=bytes.fromhex(method['bytesHex'])
            self.assertEqual(method['sha256'],hashlib.sha256(raw).hexdigest())
            self.assertEqual('PE Exception Directory',method['bounds'])
            self.assertEqual(len(raw),int(method['endRva'],16)-int(method['rva'],16))
            instructions.update({i.address-0x180000000:i for i in decoder.disasm(raw,0x180000000+int(method['rva'],16))})
        for fragment in profile['nativeFragments']:
            raw=bytes.fromhex(fragment['bytesHex'])
            self.assertFalse(fragment['completeNativeMethod'])
            self.assertEqual(fragment['sha256'],hashlib.sha256(raw).hexdigest())
            instructions.update({i.address-0x180000000:i for i in decoder.disasm(raw,0x180000000+int(fragment['rva'],16))})
        for address,expected in [
                (0x15458a64,('call','0x19b174dc0')),
                (0x1545cdf6,('mov','dword ptr [rax + 0x10], 0x7777')),
                (0x15931780,('mov','dword ptr [r13 + 0x10], 0x9999')),
                (0x159317a6,('call','0x18e778630')),
                (0x15931824,('cmp','byte ptr [rax + 0x38], 0')),
                (0x1593189e,('cmp','byte ptr [rax + 0x2b4], 0')),
                (0x15931973,('call','0x18e777c90')),
                (0x159319d7,('call','0x18e7796f0')),
                (0x15931a5d,('call','0x18e4c33b0')),
                (0x15931a74,('call','0x19cd7ea20')),
                (0x15931a83,('jl','0x195931a00')),
                (0x11fd735c,('mov','dword ptr [rsi + 0x10], 0x9999')),
                (0x11fd7396,('call','0x18e4c35d0')),
                (0x11fd73e6,('call','0x18e7796f0')),
                (0x11fd7415,('mov','rcx, qword ptr [rax + 0x20]')),
                (0x11fd7459,('mov','edx, dword ptr [rax + 0x30]')),
                (0x11fd745f,('call','0x18e766260')),
                (0x11fd7484,('jmp','0x19cd7ea20')),
                (0x1da7296f,('call','0x19db22c30')),
                (0x1da72974,('mov','qword ptr [rsi + 0x18], rax')),
                (0x1da72978,('mov','byte ptr [rsi + 0x38], 1')),
                (0x1da7297c,('mov','byte ptr [rsi + 0x10], 1')),
                (0xe7786b9,('mov','r15, qword ptr [rsi + 0x20]')),
                (0x1da7b610,('mov','byte ptr [rcx + 0x10], 1')),
                (0x1d5fbef0,('ret',''))]:
            self.assertEqual(expected,(instructions[address].mnemonic,instructions[address].op_str),hex(address))

    def test_native_factory_slots_property_and_charm_fields_match_fixed_client(self):
        self.assertEqual((12,21166),write.verify_native(CLIENT))
        profile=write.load_profile()
        self.assertEqual('Caster',profile['stringLiteralSlots'][0]['value'])
        self.assertEqual([2791,2855],[b['configVariant'] for b in profile['runtimeRegistrations']])
        charm=next(f['field'] for f in profile['nativeFieldFacts'] if f['field']['name']=='bIsInCharmAction')
        prop=next(f['field'] for f in profile['nativeFieldFacts'] if f['field']['name']=='CurrentSP')
        self.assertEqual(0x2b4,charm['instanceOffset'])
        self.assertEqual(12,prop['constant']['value'])

    def fixture_record(self):
        profile=write.load_profile()
        ability={'Name':profile['sourceAbilityName'],'OnStart':[{'SuccessTaskList':[{}, {}, {}]},
            {'SuccessTaskList':[{}, {}, {}, {}]}]}
        for row in profile['sourceTasks']:
            ability['OnStart'][row['path'][3]]['SuccessTaskList'][row['path'][5]]=deepcopy(row['task'])
        return {**profile['sourceRecord'],'decoded':{'AbilityList':[{}, {}, ability]}}

    def test_six_original_source_tasks_have_explicit_keys_and_two_property_reads(self):
        rows=write.verify_source_tasks(self.fixture_record())
        self.assertEqual([547,571,606,719,743,805],[r['offset'] for r in rows])
        self.assertEqual([2855,2791,2791,2855,2791,2791],[r['taskId'] for r in rows])
        self.assertEqual([-978103959,-1803181471,-1803181471,-978103959,1555818884,1555818884],
            [r['dynamicKeyHash'] for r in rows])

    def test_changed_native_source_keys_values_paths_or_identity_are_closed(self):
        for index in range(6):
            for kind in ['key','mask','offset','variant','value','path']:
                record=self.fixture_record()
                row=write.load_profile()['sourceTasks'][index]
                node=record['decoded']['AbilityList'][2]['OnStart'][row['path'][3]]['SuccessTaskList']
                task=node[row['path'][5]]
                if kind=='key': task['DynamicKey']='changed'
                elif kind=='mask': task['$mask']='0x0'
                elif kind=='offset': task['$offset']+=1
                elif kind=='variant': task['taskId']=1221
                elif kind=='value': task['Value']=0
                else: node.pop()
                with self.subTest(index=index,kind=kind),self.assertRaises(ValueError):
                    write.verify_source_tasks(record)
        record=self.fixture_record();record['sha256']='0'*64
        with self.assertRaises(ValueError):write.verify_source_tasks(record)

    def test_source_default_literal_target_and_charm_skip_do_not_resolve_actual_numeric_parameters(self):
        profile=write.load_profile()
        self.assertEqual(('Caster',True),(profile['defaultExpressionTargetAlias'],profile['defaultSkipInCharmAction']))
        expressions=[r['task'] for r in profile['sourceTasks'] if r['task']['taskId']==2791]
        self.assertTrue(all(t['DynamicKey']['$mask']=='0x4' for t in expressions))
        self.assertTrue(all('TargetType' not in t and 'SkipInCharmAction' not in t for t in expressions))
        self.assertFalse(profile['actualParameterValuesResolved'])
        self.assertFalse(profile['liveCasterPropertiesRead'])

    def test_other_original_target_aliases_are_not_reinterpreted_as_caster(self):
        for alias in ['Owner','LevelEntity','Target']:
            record=self.fixture_record()
            task=record['decoded']['AbilityList'][2]['OnStart'][0]['SuccessTaskList'][0]
            task['ReadTargetType']['Alias']=alias
            with self.subTest(alias=alias),self.assertRaises(ValueError):
                write.verify_source_tasks(record)
        record=self.fixture_record()
        record['decoded']['AbilityList'][2]['OnStart'][0]['SuccessTaskList'][1]['TargetType']={'Alias':'Owner'}
        with self.assertRaises(ValueError):write.verify_source_tasks(record)

    def test_complete_original_records_and_factory_evidence_still_keep_battle_unverified(self):
        with tempfile.TemporaryDirectory(dir=ROOT/'build') as temporary:
            report=write.analyze(SimpleNamespace(client_dir=CLIENT,output=Path(temporary)/'report.json'))
        self.assertTrue(report['passed'])
        self.assertEqual(6,len(report['sourceTasks']))
        self.assertEqual([2791,2855],report['sourceTaskVariantsMatchedToRuntimeFactories'])
        self.assertEqual('Caster',report['expressionDefaultTargetAliasMatched'])
        self.assertTrue(report['explicitInputsWriteTasksImplemented'])
        self.assertTrue(report['numericValueEvaluatedBeforeTargetWrites'])
        for key in ['constructorsCompleteNativeMethods','nativeLifecycleStateAloneProvesEffectSuccess',
                'contextLookupSelectionImplemented','actualParameterValuesResolved','liveCasterPropertiesRead',
                'originalTaskBranchesExecutedInBattle','abilityExecutionVerified','jsonInstalled',
                'clientExecutableStarted','clientModified','clientGameplayVerified','allResonanceAbilitiesImplemented']:
            self.assertFalse(report[key],key)

    def test_client_operator_and_resource_output_paths_are_closed(self):
        for output in [CLIENT/'write.json',ROOT/'config.json',ROOT/'resources/4.6/write.json']:
            with self.subTest(output=str(output)),self.assertRaises(ValueError):
                write.analyze(SimpleNamespace(client_dir=CLIENT,output=output))


if __name__=='__main__':unittest.main()
