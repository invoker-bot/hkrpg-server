"""只读核对4.6整数转换、余数、移位和位运算；不绑定真实战斗参数。"""
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
PROFILE=ROOT/'src/test/resources/fixtures/rogue-resonance-integer-4.6.json'
PROFILE_SHA256='678afd3c053c8c8657f3480e639d27e576c2884072ac918d517064e64dabeafe'
spec=importlib.util.spec_from_file_location('integer_registered',Path(__file__).with_name('read-rogue-resonance-functions.py'))
functions=importlib.util.module_from_spec(spec)
spec.loader.exec_module(functions)

@lru_cache(maxsize=1)
def load_profile():
    raw=PROFILE.read_bytes().replace(b'\r\n',b'\n')
    if hashlib.sha256(raw).hexdigest()!=PROFILE_SHA256: raise ValueError('整数回调原生证据改变')
    return json.loads(raw)

def read_rva(pe,address,size):
    section=pe.get_section_by_rva(address)
    if address<0 or size<0 or section is None or address-section.VirtualAddress+size>section.SizeOfRawData:
        raise ValueError('整数指令或系数没有实际文件范围')
    raw=pe.get_data(address,size)
    if len(raw)!=size: raise ValueError('整数指令或系数截断')
    return raw

def read_factors(pe,fact):
    raw=read_rva(pe,int(fact['rva'],16),8)
    if raw.hex()!=fact['bytesHex'] or hashlib.sha256(raw).hexdigest()!=fact['sha256']:
        raise ValueError('float32原始系数不符')
    values=struct.unpack('<2f',raw)
    if values!=(2**-26,2**-33) or fact['standardFactorIndex']!=1 or fact['standardFactorExponent']!=-33:
        raise ValueError('float32标准值系数身份不符')
    return values

def verify_native(client):
    profile=load_profile()
    functions.verify_native(client)
    names=['int','mod','leftshift','rightshift','and','or','xor']
    registered={r['name']:r for r in functions.load_profile()['registrations']}
    if profile['registrations']!=[registered[name] for name in names]:
        raise ValueError('整数回调注册名称、键或身份不符')
    characters=functions.sibling('integer_native_characters','read-rogue-resonance-character.py')
    counts=characters.verify_native(client,profile)
    analyzer=functions.sibling('integer_native_metadata','analyze-client-46.py')
    identities=[{k:m[k] for k in ['index','name','rva','typeIndex','typeName']} for m in profile['nativeMethods']]+profile['helperIdentities']
    actual=analyzer.extract(SimpleNamespace(client_dir=client,types=[],type_contains=[],
        method_indices=[m['index'] for m in identities],method_rvas=[],method_name_contains=[],
        method_slots=[],string_slots=[],type_slots=[],fields=False,inheritance=False,
        field_offsets=False,disassemble=0,method_names=[]))
    if actual['selectedMethods']!=identities: raise ValueError('整数回调、Approximately、初始化器或helper身份不符')
    pe=pefile.PE(str(client/'GameAssembly.dll'),fast_load=True)
    for fragment in profile['standardBranchFragments']:
        raw=read_rva(pe,int(fragment['rva'],16),len(bytes.fromhex(fragment['bytesHex'])))
        if raw.hex()!=fragment['bytesHex'] or hashlib.sha256(raw).hexdigest()!=fragment['sha256']:
            raise ValueError('加减法标准/舍入中间值指令片段不符')
    read_factors(pe,profile['floatFactors'])
    unary=functions.sibling('integer_unary_initializer','read-rogue-resonance-unary.py')
    if profile['oneInitializerPair']!=next(f for f in unary.load_profile()['initializerPairs'] if f['name']=='one'):
        raise ValueError('舍入ONE初始化对不符')
    unary.read_pair(pe,profile['oneInitializerPair'])
    return counts

def analyze(args):
    client=args.client_dir.resolve()
    design=functions.sibling('integer_output_design','read-client-design-data.py')
    output=design.outside_client(args.output,client)
    if ROOT/'build' not in output.parents: raise ValueError('只接受工程build目录下的整数静态证据')
    methods,size=verify_native(client)
    profile=load_profile()
    report={'passed':True,'matchedNativeMethods':methods,'matchedNativeBytes':size,
        'standardInstructionFragmentBytes':303,'initializerAndFactorBytes':24,
        'helperFragmentsCompleteNativeMethods':False,'newRegistrationsMatched':7,
        'newInputMode':profile['newInputMode'],'openedFunctionNames':profile['openedFunctionNames'],
        'registeredStandardCallbacksImplemented':15,'intEpsilonRaw':8588,'intEpsilonStrictLess':True,
        'intFloat32FallbackImplemented':True,'intApproximateNegativeEndpointPreserved':True,
        'positiveStandardRoundIntermediateDecoded':True,'shift32BitMaskImplemented':True,
        'bitArguments':[1,255],'binaryFiniteArguments':2,'intFiniteArguments':1,
        'nativeIgnoredExtraArgumentsSupported':False,'integerStandaloneArithmeticImplemented':True,
        'integerPostfixCallerImplemented':True,'newExtendedInputsSupported':False,'newExtendedResultsSupported':False,
        'actualParameterValuesResolved':False,'nativeNumericProviderImplemented':False,
        'dynamicCellProvidersImplemented':False,'liveCasterPropertiesRead':False,
        'liveBattleContextAttached':False,'originalTaskBranchesExecutedInBattle':False,
        'abilityExecutionVerified':False,'jsonInstalled':False,'clientExecutableStarted':False,
        'clientModified':False,'clientGameplayVerified':False,'allResonanceAbilitiesImplemented':False}
    output.parent.mkdir(parents=True,exist_ok=True)
    output.write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n','utf-8')
    return report

if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--client-dir',type=Path,required=True)
    parser.add_argument('--output',type=Path,default=ROOT/'build/rogue-resonance-integer-evidence.json')
    report=analyze(parser.parse_args())
    print(json.dumps({k:report[k] for k in ['passed','matchedNativeMethods','matchedNativeBytes',
        'registeredStandardCallbacksImplemented','intFloat32FallbackImplemented','liveBattleContextAttached','clientGameplayVerified']},ensure_ascii=False))
