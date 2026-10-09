"""只读核对4.6余数、近似比较、三元选择和逻辑非，不接入真实任务调度。"""
import argparse
from functools import lru_cache
import hashlib
import importlib.util
import json
from pathlib import Path
import struct
from types import SimpleNamespace
import pefile

ROOT=Path(__file__).resolve().parents[1]
PROFILE=ROOT/'src/test/resources/fixtures/rogue-resonance-condition-4.6.json'
PROFILE_SHA256='8c3c65f69821d52cb0c470accde618f6aeea8497c757e221e0068a03f4e359aa'
spec=importlib.util.spec_from_file_location('condition_integer',Path(__file__).with_name('read-rogue-resonance-integer.py'))
integer=importlib.util.module_from_spec(spec)
spec.loader.exec_module(integer)
functions=integer.functions

@lru_cache(maxsize=1)
def load_profile():
    raw=PROFILE.read_bytes().replace(b'\r\n',b'\n')
    if hashlib.sha256(raw).hexdigest()!=PROFILE_SHA256: raise ValueError('条件操作原生证据改变')
    return json.loads(raw)

def verify_native(client):
    p=load_profile()
    integer.verify_native(client)
    characters=functions.sibling('condition_native_characters','read-rogue-resonance-character.py')
    counts=characters.verify_native(client,p)
    analyzer=functions.sibling('condition_native_metadata','analyze-client-46.py')
    identities=[{k:m[k] for k in ['index','name','rva','typeIndex','typeName']} for m in p['nativeMethods']]+p['helperIdentities']
    actual=analyzer.extract(SimpleNamespace(client_dir=client,types=[],type_contains=[],
        method_indices=[m['index'] for m in identities],method_rvas=[],method_name_contains=[],
        method_slots=[],string_slots=[],type_slots=[],fields=False,inheritance=False,
        field_offsets=False,disassemble=0,method_names=[]))
    if actual['selectedMethods']!=identities: raise ValueError('VM、近似比较或接近零helper身份不符')
    postfix=functions.sibling('condition_postfix_profile','read-rogue-resonance-postfix.py')
    if p['opCodeConstants']!=postfix.load_profile()['opCodeConstants']: raise ValueError('18个Byte操作码常量不符')
    pe=pefile.PE(str(client/'GameAssembly.dll'),fast_load=True)
    for f in p['nativeFragments']:
        raw=integer.read_rva(pe,int(f['rva'],16),f['length'])
        if raw.hex()!=f['bytesHex'] or hashlib.sha256(raw).hexdigest()!=f['sha256']:
            raise ValueError('Sub或IsAlmostZero指令片段不符')
    table=int(p['vmSwitchTableRva'],16)
    targets=[hex(table+n) for n in struct.unpack('<18i',integer.read_rva(pe,table,72))]
    if targets!=p['vmSwitchTargets']: raise ValueError('18项相对跳表不符')
    if p['approximation']!=integer.load_profile()['approximation']:
        raise ValueError('近似阈值不是已核对的初始化来源')
    unary=functions.sibling('condition_unary_initializer','read-rogue-resonance-unary.py')
    unary.read_pair(pe,p['oneInitializerPair'])
    return counts

def analyze(args):
    client=args.client_dir.resolve()
    design=functions.sibling('condition_output_design','read-client-design-data.py')
    output=design.outside_client(args.output,client)
    if ROOT/'build' not in output.parents: raise ValueError('只接受工程build目录下的条件静态证据')
    methods,size=verify_native(client)
    p=load_profile()
    r={'passed':True,'matchedNativeMethods':methods,'matchedNativeBytes':size,'nativeFragmentBytes':620,
        'helperFragmentsCompleteNativeMethods':False,'initializerBytes':16,'opcodeConstantsMatched':18,
        'acceptedNumericOpCodes':p['acceptedNumericOpCodes'],'newOpCodes':p['newOpCodes'],
        'epsilonRaw':8588,'booleanTrueRaw':1<<33,'booleanFalseRaw':0,
        'ternaryInputOrder':p['ternaryInputOrder'],'ternaryBothBranchesEager':True,
        'truthUsesStrictAlmostZero':True,'conditionPostfixImplemented':True,
        'originalConditionProgramsObserved':0,'newExtendedInputsSupported':False,
        'actualParameterValuesResolved':False,'nativeNumericProviderImplemented':False,
        'dynamicCellProvidersImplemented':False,'liveCasterPropertiesRead':False,
        'liveBattleContextAttached':False,'originalTaskBranchesExecutedInBattle':False,
        'abilityExecutionVerified':False,'jsonInstalled':False,'clientExecutableStarted':False,
        'clientModified':False,'clientGameplayVerified':False,'allResonanceAbilitiesImplemented':False}
    output.parent.mkdir(parents=True,exist_ok=True)
    output.write_text(json.dumps(r,ensure_ascii=False,indent=2)+'\n','utf-8')
    return r

if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--client-dir',type=Path,required=True)
    parser.add_argument('--output',type=Path,default=ROOT/'build/rogue-resonance-condition-evidence.json')
    report=analyze(parser.parse_args())
    print(json.dumps({k:report[k] for k in ['passed','matchedNativeMethods','matchedNativeBytes',
        'opcodeConstantsMatched','conditionPostfixImplemented','liveBattleContextAttached','clientGameplayVerified']},ensure_ascii=False))
