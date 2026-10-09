"""只读核对有限任务序列调度，原始任务工厂和全局插件未接入。"""
import argparse
from functools import lru_cache
import hashlib
import importlib.util
import json
from pathlib import Path
from types import SimpleNamespace

ROOT=Path(__file__).resolve().parents[1]
PROFILE=ROOT/'src/test/resources/fixtures/rogue-task-sequence-4.6.json'
PROFILE_SHA256='140d2f3df95a52c2c67fed0a3343089fdd8e64c905db99b1b8a7f40e0173778a'


def sibling(name,filename):
    spec=importlib.util.spec_from_file_location(name,Path(__file__).with_name(filename))
    module=importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


characters=sibling('task_sequence_native','read-rogue-resonance-character.py')


@lru_cache(maxsize=1)
def load_profile():
    raw=PROFILE.read_bytes().replace(b'\r\n',b'\n')
    if hashlib.sha256(raw).hexdigest()!=PROFILE_SHA256:
        raise ValueError('任务序列冻结证据改变')
    return json.loads(raw)


def verify_native(client):
    p=load_profile()
    counts=characters.verify_native(client,p)
    analyzer=sibling('task_sequence_metadata','analyze-client-46.py')
    helper=p['diagnosticHelper']
    names=[p['stateType']['name'],p['sequenceType']['name'],p['performanceField']['name']]
    actual=analyzer.extract(SimpleNamespace(client_dir=client,types=names,type_contains=[],
        method_indices=[m['index'] for m in p['nativeMethods']]+[helper['index']],method_rvas=[],method_name_contains=[],
        method_slots=[],string_slots=[],type_slots=[int(t['slot'],16) for t in p['interfaceTypeSlots']],
        fields=True,field_offsets=True,inheritance=True,disassemble=0,method_names=[]))
    expected=[{k:m[k] for k in ['index','name','rva','typeIndex','typeName']} for m in p['nativeMethods']]+[helper]
    if actual['selectedMethods']!=expected or actual['typeInfoSlots']!=p['interfaceTypeSlots']:
        raise ValueError('序列方法、即时/失败通知接口或LogError身份不符')
    types={t['typeIndex']:t for t in actual['methods']}
    state=p['stateType'];sequence=p['sequenceType'];performance=p['performanceField']
    if {k:types[state['typeIndex']][k] for k in state}!=state:
        raise ValueError('回合任务状态枚举不符，不能混用另一同名TaskState')
    if {k:types[sequence['typeIndex']][k] for k in sequence}!=sequence:
        raise ValueError('序列类型、继承链或实例字段不符')
    if performance['field'] not in types[performance['typeIndex']]['fields']:
        raise ValueError('演出即时完成标志字段不符')
    return counts


def analyze(args):
    client=args.client_dir.resolve()
    design=sibling('task_sequence_output','read-client-design-data.py')
    output=design.outside_client(args.output,client)
    if ROOT/'build' not in output.parents:
        raise ValueError('只接受工程build下的任务序列只读证据')
    p=load_profile()
    methods,size=verify_native(client)
    flags=['explicitTaskSequenceImplemented','ordinaryNoPluginStepMatched','performanceImmediateInterfaceMatched',
        'disposeChildReleaseOrderMatched','nativeDisposedArrayReplacementMatched','originalTaskConfigsDispatched',
        'globalPluginHooksImplemented','globalAliasCacheImplemented','nativeSequenceExecuted','liveBattleContextAttached',
        'abilityExecutionVerified','clientGameplayVerified','completeResonanceRuntimeImplemented']
    result={'passed':True,'matchedNativeMethods':methods,'matchedNativeBytes':size,'taskStateTypeIndex':54773,
        'taskStates':[f['constant']['value'] for f in p['stateType']['fields'] if 'constant' in f],
        'performanceFlagOffset':154,'failureNotificationTypeIndex':54780,'immediateTypeIndex':54779,
        'waitingDiagnosticMethodMatched':True,**{k:p[k] for k in flags},
        'jsonInstalled':False,'clientExecutableStarted':False,'clientModified':False}
    output.write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n','utf-8')
    return result


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--client-dir',type=Path,required=True)
    parser.add_argument('--output',type=Path,default=ROOT/'build/rogue-task-sequence-evidence.json')
    print(json.dumps(analyze(parser.parse_args()),ensure_ascii=False))
