"""只读核对4.6的2791/2855写入任务和六处原始动作；真实属性与参数仍未接入。"""
import argparse
from functools import lru_cache
import hashlib
import importlib.util
import json
from pathlib import Path
import tempfile
from types import SimpleNamespace

import pefile

ROOT=Path(__file__).resolve().parents[1]
PROFILE=ROOT/'src/test/resources/fixtures/rogue-resonance-write-4.6.json'
PROFILE_SHA256='7c2b2fd3762c86bc0ec6d1aeff7ccfae60ce967f0af77660d1ad6324ba3f06e3'
spec=importlib.util.spec_from_file_location('write_variable',Path(__file__).with_name('read-rogue-resonance-variables.py'))
variables=importlib.util.module_from_spec(spec)
spec.loader.exec_module(variables)
functions=variables.rounding.functions


@lru_cache(maxsize=1)
def load_profile():
    raw=PROFILE.read_bytes().replace(b'\r\n',b'\n')
    if hashlib.sha256(raw).hexdigest()!=PROFILE_SHA256:
        raise ValueError('写入动作的固定原生证据改变')
    return json.loads(raw)


def verify_native(client):
    profile=load_profile()
    variables.verify_native(client)
    characters=functions.sibling('write_native_characters','read-rogue-resonance-character.py')
    counts=characters.verify_native(client,profile)
    analyzer=functions.sibling('write_native_metadata','analyze-client-46.py')
    actual=analyzer.extract(SimpleNamespace(client_dir=client,
        types=['DynamicString','AbilityProperty','TurnBasedAbilityComponent'],type_contains=[],
        method_indices=[m['index'] for m in profile['nativeMethods']]+[127749,106200],
        method_rvas=[],method_name_contains=[],method_slots=[int(m['slot'],16) for m in profile['methodInfoSlots']],
        type_slots=[int(t['slot'],16) for t in profile['typeInfoSlots']],
        string_slots=[int(s['slot'],16) for s in profile['stringLiteralSlots']],
        fields=True,inheritance=False,field_offsets=True,disassemble=0,method_names=[]))
    expected=[{k:m[k] for k in ['index','name','rva','typeIndex','typeName']} for m in profile['nativeMethods']]
    expected += [{'index':127749,'name':'.ctor','rva':'0x1da7b610','typeIndex':23082,'typeName':'SetDynamicValueByProperty'},
        {'index':106200,'name':'.ctor','rva':'0x1d5fbef0','typeIndex':16479,'typeName':'DynamicString'}]
    if (actual['selectedMethods']!=expected or actual['typeInfoSlots']!=profile['typeInfoSlots']
            or actual['methodInfoSlots']!=profile['methodInfoSlots']
            or actual['stringLiteralSlots']!=profile['stringLiteralSlots']):
        raise ValueError('写入任务的运行时工厂、执行器或默认目标身份不符')
    for fact in profile['nativeFieldFacts']:
        owner=next(t for t in actual['methods'] if t['name']==fact['typeName'])
        if next(f for f in owner['fields'] if f['name']==fact['field']['name'])!=fact['field']:
            raise ValueError('写入属性、魅惑状态或动态字符串字段不符')
    pe=pefile.PE(str(client/'GameAssembly.dll'),fast_load=True)
    with (client/'GameAssembly.dll').open('rb') as stream:
        for fragment in profile['nativeFragments']:
            start,size=int(fragment['rva'],16),fragment['length']
            section=pe.get_section_by_rva(start)
            if section is None or start-section.VirtualAddress+size>section.SizeOfRawData:
                raise ValueError('写入构造片段没有原生文件范围')
            stream.seek(pe.get_offset_from_rva(start))
            raw=stream.read(size)
            if raw.hex()!=fragment['bytesHex'] or hashlib.sha256(raw).hexdigest()!=fragment['sha256']:
                raise ValueError('写入任务启用或动态字符串构造片段不符')
    return counts


def verify_source_tasks(record):
    profile=load_profile()
    if any(record.get(k)!=v for k,v in profile['sourceRecord'].items()):
        raise ValueError('六处原始写入任务的记录身份不符')
    config=functions.sibling('write_source_abilities','read-rogue-resonance-abilities.py').load_profile()
    result=[]
    for row in profile['sourceTasks']:
        try:
            task=record['decoded']
            for step in row['path']: task=task[step]
            name=record['decoded']['AbilityList'][2]['Name']
        except (KeyError,IndexError,TypeError) as error:
            raise ValueError('原始写入任务路径不符') from error
        if task!=row['task'] or name!=profile['sourceAbilityName']:
            raise ValueError('原始写入任务内容或能力名称不符')
        if config['bindings']['task'][str(task['taskId'])]!=profile['configBindings'][str(task['taskId'])]:
            raise ValueError('写入任务与原始配置注册不符')
        key=task['DynamicKey']['Value'] if task['taskId']==2791 else task['DynamicKey']
        result.append({'path':row['path'],'taskId':task['taskId'],'offset':task['$offset'],
            'dynamicKey':key,'dynamicKeyHash':functions.native_name_key(key)})
    return result


def analyze(args):
    client=args.client_dir.resolve()
    design=functions.sibling('write_output_design','read-client-design-data.py')
    output=design.outside_client(args.output,client)
    if ROOT/'build' not in output.parents:
        raise ValueError('只接受工程build目录下的写入静态证据')
    methods,size=verify_native(client)
    profile=load_profile()
    abilities=functions.sibling('write_source_abilities','read-rogue-resonance-abilities.py')
    with tempfile.TemporaryDirectory(prefix='resonance-write-',dir=ROOT/'build') as temporary:
        base=abilities.analyze(SimpleNamespace(client_dir=client,output=Path(temporary)/'abilities.json'))
        record=next(r for r in base['records'] if r['sourcePath']==profile['sourceRecord']['sourcePath'])
        source_tasks=verify_source_tasks(record)
    report={'passed':True,'matchedNativeMethods':methods,'matchedNativeBytes':size,
        'constructorInstructionBytes':profile['fragmentBytes'],'constructorsCompleteNativeMethods':False,
        'sourceRecord':profile['sourceRecord'],'sourceTasks':source_tasks,
        'sourceTaskVariantsMatchedToRuntimeFactories':[2791,2855],
        'expressionDefaultTargetAliasMatched':'Caster','expressionDefaultSkipInCharmActionMatched':True,
        'sourceCurrentSPPropertyMatched':12,'explicitInputsWriteTasksImplemented':True,
        'numericValueEvaluatedBeforeTargetWrites':True,'nativeLifecycleStateAloneProvesEffectSuccess':False,
        'contextLookupSelectionImplemented':False,'actualParameterValuesResolved':False,
        'liveCasterPropertiesRead':False,'originalTaskBranchesExecutedInBattle':False,
        'abilityExecutionVerified':False,'jsonInstalled':False,'clientExecutableStarted':False,
        'clientModified':False,'clientGameplayVerified':False,'allResonanceAbilitiesImplemented':False}
    output.parent.mkdir(parents=True,exist_ok=True)
    output.write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n','utf-8')
    return report


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--client-dir',type=Path,required=True)
    parser.add_argument('--output',type=Path,default=ROOT/'build/rogue-resonance-write-evidence.json')
    report=analyze(parser.parse_args())
    print(json.dumps({k:report[k] for k in ['passed','matchedNativeMethods','matchedNativeBytes',
        'sourceTaskVariantsMatchedToRuntimeFactories','actualParameterValuesResolved','clientGameplayVerified']},ensure_ascii=False))
