"""NK任务单元 getter、缓存回退和忽略写入的来源回归。"""
import hashlib
import importlib.util
from pathlib import Path
from types import SimpleNamespace
import unittest
import capstone

ROOT = Path(__file__).resolve().parents[2]
CLIENT = Path(r'C:\Users\InvokerBot\AppData\Local\hoyo\hkrpg\versions\current')
spec = importlib.util.spec_from_file_location('resonance_task_cell',ROOT/'scripts/read-rogue-resonance-task-cell.py')
taskcell = importlib.util.module_from_spec(spec)
spec.loader.exec_module(taskcell)


class ResonanceTaskCellTest(unittest.TestCase):
    def instructions(self):
        c = capstone.Cs(capstone.CS_ARCH_X86,capstone.CS_MODE_64)
        result = {}
        for m in taskcell.load_profile()['nativeMethods']:
            raw = bytes.fromhex(m['bytesHex'])
            self.assertEqual(m['sha256'],hashlib.sha256(raw).hexdigest())
            self.assertEqual(len(raw),int(m['endRva'],16)-int(m['rva'],16))
            result.update({i.address-0x180000000:i for i in c.disasm(raw,0x180000000+int(m['rva'],16))})
        return result

    def check(self,rows):
        ins = self.instructions()
        for rva,expected in rows: self.assertEqual(expected,(ins[rva].mnemonic,ins[rva].op_str),hex(rva))

    def target(self,rva): return hex(0x180000000+rva)

    def test_six_complete_methods_and_field_type_bindings_match_client(self):
        self.assertEqual((6,1439),taskcell.verify_native(CLIENT))
        self.assertEqual([74761,74811,74816,74818,74820,74821],[m['index'] for m in taskcell.load_profile()['nativeMethods']])

    def test_factory_assigns_task_limits_adsorption_and_lookup_provider(self):
        self.check([(0x1cd7e896,('mov','qword ptr [rsi + 0x40], rax')),
            (0x1cd7e89e,('mov','qword ptr [rsi + 0x10], rax')),
            (0x1cd7e8a6,('mov','qword ptr [rsi + 0x48], rax')),
            (0x1cd7e8e3,('mov','qword ptr [rsi + 0x30], rax')),
            (0x1cd7e8e7,('mov','qword ptr [rsi + 0x38], rbx'))])

    def test_missing_provider_or_alias_branches_to_cache_without_bounds(self):
        self.check([(0x1cd81b18,('mov','rax, qword ptr [rsi + 0x38]')),
            (0x1cd81b1f,('je',self.target(0x1cd81c0e))),
            (0x1cd81b57,('mov','rdi, qword ptr [rax + 0x20]')),
            (0x1cd81b5e,('je',self.target(0x1cd81c0e))),
            (0x1cd81c0e,('mov','rax, qword ptr [rsi + 0x58]'))])

    def test_task_result_is_adsorbed_before_minimum_maximum_and_clamp(self):
        self.check([(0x1cd81b64,('mov','rbx, qword ptr [rsi + 0x40]')),
            (0x1cd81bac,('call',self.target(0xb223af0))),
            (0x1cd81bb6,('mov','rdx, qword ptr [rsi + 0x30]')),
            (0x1cd81bbf,('call',self.target(0x1d1103d0))),
            (0x1cd81bcc,('call',self.target(0x1cd81860))),
            (0x1cd81bd7,('call',self.target(0x1cd819b0))),
            (0x1cd81be5,('call',self.target(0x1dcd1a00)))])

    def test_only_final_clamped_result_is_committed_to_cache(self):
        self.check([(0x1cd81bea,('mov','qword ptr [rsi + 0x58], rax'))])
        writes = [i for a,i in self.instructions().items() if 0x1cd81b00<=a<0x1cd81c68
            and i.mnemonic=='mov' and i.op_str.startswith('qword ptr [rsi + 0x58],')]
        self.assertEqual(1,len(writes))

    def test_minimum_and_maximum_use_distinct_scalar_fields(self):
        self.check([(0x1cd81868,('mov','rax, qword ptr [rcx + 0x10]')),
            (0x1cd819b8,('mov','rax, qword ptr [rcx + 0x48]')),
            (0x1cd818e9,('call',self.target(0x1d23e480))),
            (0x1cd81a39,('call',self.target(0x1d23e480)))])

    def test_setter_discards_cell_pointer_for_key_and_only_logs_attempt(self):
        self.check([(0x1cd81c81,('mov','esi, dword ptr [rsi + 0x50]')),
            (0x1cd81c84,('call',self.target(0x1c207d30))),
            (0x1cd81ca6,('call',self.target(0x1c0c1b50))),
            (0x1cd81cb3,('jmp',self.target(0x1dd6b050)))])
        self.assertTrue(taskcell.load_profile()['setterLogsAttemptOnly'])
        self.assertFalse(taskcell.load_profile()['setterModifiesValue'])

    def test_result_literal_hash_matches_cctor_algorithm_and_getter_static_field(self):
        p = taskcell.load_profile()
        first = second = 0x1505
        for i,c in enumerate(p['resultName']):
            if i&1: second = (second*33)^ord(c)
            else: first = (first*33)^ord(c)
        raw = (first+second*0x5d588b65)&0xffffffff
        self.assertEqual(-1228589475,raw-(1<<32) if raw>=1<<31 else raw)
        self.assertEqual('_ResultValue',p['resultString']['value'])
        self.check([(0x1cd81e43,('imul','ecx, esi, 0x5d588b65')),
            (0x1cd81e49,('add','ecx, eax'))])

    def test_static_output_keeps_actual_tasks_and_live_battle_unimplemented(self):
        r = taskcell.analyze(SimpleNamespace(client_dir=CLIENT,output=ROOT/'build/task-cell-source-test.json'))
        for k in ['explicitTaskCellReadProjectionImplemented','mixedFiniteLookupProjectionImplemented',
            'missingProviderOrAliasReturnsCache','setterLogsAttemptOnly']:
            self.assertTrue(r[k],k)
        for k in ['setterModifiesValue','actualTaskConfigurationDecoded','nativeAliasTaskSequenceImplemented',
            'aliasEvaluatorBindingImplemented','actualPropertyCellsLoaded','actualParameterValuesResolved',
            'liveCasterPropertiesRead','liveBattleContextAttached','originalTaskBranchesExecutedInBattle',
            'abilityExecutionVerified','clientGameplayVerified','allResonanceAbilitiesImplemented',
            'extendedCellsImplemented','jsonInstalled','clientExecutableStarted','clientModified']:
            self.assertFalse(r[k],k)

    def test_client_output_is_rejected_before_source_inspection(self):
        with self.assertRaises(ValueError):
            taskcell.analyze(SimpleNamespace(client_dir=CLIENT,output=CLIENT/'task-cell-evidence.json'))


if __name__ == '__main__': unittest.main()
