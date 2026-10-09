"""只读核对4.6 min/max/clamp标准字分支及原生多参数回调。"""
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
PROFILE=ROOT/'src/test/resources/fixtures/rogue-resonance-multi-4.6.json'
PROFILE_SHA256='95028fbdd28e90bf82a9310bc64d6eba3af2ecc2b4d4eb84625eb051b4ed7e60'
spec=importlib.util.spec_from_file_location('multi_registered',Path(__file__).with_name('read-rogue-resonance-functions.py'))
functions=importlib.util.module_from_spec(spec)
spec.loader.exec_module(functions)


@lru_cache(maxsize=1)
def load_profile():
    raw=PROFILE.read_bytes().replace(b'\r\n',b'\n')
    if hashlib.sha256(raw).hexdigest()!=PROFILE_SHA256:
        raise ValueError('多参数回调原生证据改变')
    return json.loads(raw)


def read_rva(pe,address,size):
    section=pe.get_section_by_rva(address)
    if address<0 or size<0 or section is None or address-section.VirtualAddress+size>section.SizeOfRawData:
        raise ValueError('多参数指令或跳表没有实际文件范围')
    raw=pe.get_data(address,size)
    if len(raw)!=size: raise ValueError('多参数指令或跳表截断')
    return raw


def verify_native(client):
    profile=load_profile()
    functions.verify_native(client)
    registered={r['name']:r for r in functions.load_profile()['registrations']}
    if profile['registrations']!=[registered[name] for name in ['min','max','clamp']]:
        raise ValueError('多参数回调注册名称、键或身份不符')
    characters=functions.sibling('multi_native_characters','read-rogue-resonance-character.py')
    counts=characters.verify_native(client,profile)
    analyzer=functions.sibling('multi_native_metadata','analyze-client-46.py')
    identities=[{k:m[k] for k in ['index','name','rva','typeIndex','typeName']} for m in profile['nativeMethods']]+profile['helperIdentities']
    actual=analyzer.extract(SimpleNamespace(client_dir=client,types=[],type_contains=[],
        method_indices=[m['index'] for m in identities],method_rvas=[],method_name_contains=[],
        method_slots=[],string_slots=[],type_slots=[],fields=False,inheritance=False,
        field_offsets=False,disassemble=0,method_names=[]))
    if actual['selectedMethods']!=identities: raise ValueError('多参数VM、回调或Clamp helper身份不符')
    pe=pefile.PE(str(client/'GameAssembly.dll'),fast_load=True)
    for fragment in profile['standardBranchFragments']:
        raw=read_rva(pe,int(fragment['rva'],16),fragment['length'])
        if raw.hex()!=fragment['bytesHex'] or hashlib.sha256(raw).hexdigest()!=fragment['sha256']:
            raise ValueError('标准Clamp指令片段不符')
    table=int(profile['vmSwitchTableRva'],16)
    if table+struct.unpack('<i',read_rva(pe,table+16*4,4))[0]!=int(profile['vmCallTargetRva'],16):
        raise ValueError('VM Call操作码跳表不符')
    return counts


def analyze(args):
    client=args.client_dir.resolve()
    design=functions.sibling('multi_output_design','read-client-design-data.py')
    output=design.outside_client(args.output,client)
    if ROOT/'build' not in output.parents: raise ValueError('只接受工程build目录下的多参数静态证据')
    methods,size=verify_native(client)
    profile=load_profile()
    report={'passed':True,'matchedNativeMethods':methods,'matchedNativeBytes':size,
        'standardInstructionFragmentBytes':52,'helperFragmentsCompleteNativeMethods':False,
        'newRegistrationsMatched':3,'newInputMode':profile['newInputMode'],
        'openedFunctionNames':profile['openedFunctionNames'],'minMaxArguments':[1,255],
        'clampFiniteArguments':3,'nativeClampExtraArgumentsSupported':False,
        'multiStandaloneArithmeticImplemented':True,'multiPostfixCallerImplemented':True,
        'originalArgumentOrderPreserved':True,'newExtendedInputsSupported':False,
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
    parser.add_argument('--output',type=Path,default=ROOT/'build/rogue-resonance-multi-evidence.json')
    report=analyze(parser.parse_args())
    print(json.dumps({k:report[k] for k in ['passed','matchedNativeMethods','matchedNativeBytes',
        'openedFunctionNames','originalArgumentOrderPreserved','liveBattleContextAttached','clientGameplayVerified']},ensure_ascii=False))
