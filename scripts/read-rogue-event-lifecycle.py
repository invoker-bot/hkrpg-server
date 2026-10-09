"""只读核对事件创建任务普通生命周期和参数栈；不运行原始回调任务。"""
import argparse
from functools import lru_cache
import hashlib
import importlib.util
import json
from pathlib import Path
from types import SimpleNamespace
import pefile

ROOT = Path(__file__).resolve().parents[1]
PROFILE = ROOT/'src/test/resources/fixtures/rogue-event-lifecycle-4.6.json'
PROFILE_SHA256 = '654e100e4ee858173d8ce13c0ac19c6592611cfe576ecf980691bf6b7b2eb435'


def sibling(name,filename):
    spec=importlib.util.spec_from_file_location(name,ROOT/'scripts'/filename)
    module=importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


characters=sibling('event_lifecycle_native','read-rogue-resonance-character.py')
classic=sibling('event_lifecycle_classic','read-rogue-resonance-classic-events.py')


@lru_cache(maxsize=1)
def load_profile():
    raw=PROFILE.read_bytes().replace(b'\r\n',b'\n')
    if hashlib.sha256(raw).hexdigest()!=PROFILE_SHA256:
        raise ValueError('事件生命周期冻结证据改变')
    return json.loads(raw)


def verify_native(client):
    p=load_profile()
    counts=characters.verify_native(client,p)
    analyzer=sibling('event_lifecycle_metadata','analyze-client-46.py')
    names=[t['name'] for t in p['types']]+[p['worldRegister']['name']]
    actual=analyzer.extract(SimpleNamespace(client_dir=client,types=names,type_contains=[],
        method_indices=[m['index'] for m in p['nativeMethods']],method_rvas=[],method_name_contains=[],
        method_slots=[int(p['beforeCallbackMethodSlot']['slot'],16)],string_slots=[],type_slots=[],
        fields=True,field_offsets=True,inheritance=False,disassemble=0,method_names=[]))
    expected=[{k:m[k] for k in ['index','name','rva','typeIndex','typeName']} for m in p['nativeMethods']]
    if actual['selectedMethods']!=expected or actual['methodInfoSlots']!=[p['beforeCallbackMethodSlot']]:
        raise ValueError('事件生命周期方法身份或前回调委托不符')
    types={t['name']:t for t in actual['methods']}
    if [{k:types[t['name']][k] for k in ['typeIndex','name','fields']} for t in p['types']]!=p['types']:
        raise ValueError('事件创建配置、运行类或参数栈类型不符')
    world=p['worldRegister']
    if types[world['name']]['typeIndex']!=world['typeIndex'] or world['field'] not in types[world['name']]['fields']:
        raise ValueError('世界参数寄存器字段身份不符')
    pe=pefile.PE(str(client/'GameAssembly.dll'),fast_load=True)
    for record in p['unwindRecords']:
        raw=bytes.fromhex(record['unwindBytesHex'])
        if pe.get_data(int(record['unwindRva'],16),len(raw))!=raw:
            raise ValueError('事件生命周期完整方法的展开信息不符')
        if (raw[0]&7,raw[0]>>3,raw[2])!=(record['unwindVersion'],record['unwindFlags'],record['unwindCodeSlots']):
            raise ValueError('事件生命周期展开标志不符')
    return counts


def source_callbacks(client):
    classic.source_definitions(client)
    parent=json.loads((ROOT/'build/event-params-source-parent-evidence.json').read_text('utf-8'))
    abilities=parent['decoded']['AbilityList']+[a['decoded'] for a in classic.load_profile()['additionalAbilities']]
    rows=[]
    for definition in classic.load_profile()['classicEventDefinitions']:
        ability=next(a for a in abilities if a['Name']==definition['abilityName'])
        task=next(t for t in ability['OnAdd'] if t['taskId']==1122)
        rows.append({'eventId':definition['eventId'],'abilityName':definition['abilityName'],
            'before':task.get('OnBeforeCreateCallback'),'after':task.get('OnBattleEventCreate'),
            'dynamicPreload':task.get('IsDynamicPreload',False),'allowDuplicate':task.get('AllowDuplicate',False)})
    if rows!=load_profile()['classicCallbacks']:
        raise ValueError('原始九事件的回调存在性或标志不符')
    return rows


def analyze(args):
    client=args.client_dir.resolve()
    design=sibling('event_lifecycle_output','read-client-design-data.py')
    output=design.outside_client(args.output,client)
    if ROOT/'build' not in output.parents:
        raise ValueError('只接受工程build下的事件生命周期只读证据')
    p=load_profile()
    methods,size=verify_native(client)
    callbacks=source_callbacks(client)
    fields=['explicitCreateTaskLifecycleImplemented','explicitCallbackFrameStackImplemented',
        'ordinaryCallbackExceptionCleanupMatched','liveEntityFactoryImplemented','originalCallbackTasksExecuted',
        'battleEventCreated','liveBattleContextAttached','abilityExecutionVerified','clientGameplayVerified',
        'completeResonanceRuntimeImplemented']
    result={'passed':True,'matchedNativeMethods':methods,'matchedNativeBytes':size,
        'sourceEventCount':len(callbacks),'sourceBeforeCallbacks':sum(len(r['before'] or []) for r in callbacks),
        'sourceAfterCallbacks':sum(len(r['after'] or []) for r in callbacks),
        'callbackHelpersHaveExceptionHandler':False,'callbackHelpersHaveTerminationHandler':False,
        **{k:p[k] for k in fields},'jsonInstalled':False,'clientExecutableStarted':False,'clientModified':False}
    output.write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n','utf-8')
    return result


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--client-dir',type=Path,required=True)
    parser.add_argument('--output',type=Path,default=ROOT/'build/rogue-event-lifecycle-evidence.json')
    print(json.dumps(analyze(parser.parse_args()),ensure_ascii=False))
