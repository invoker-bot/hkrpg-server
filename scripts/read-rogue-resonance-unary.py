"""只读核对4.6 ceil/abs/clamp01标准数分支及调用注册，不绑定真实战斗参数。"""
import argparse
from functools import lru_cache
import hashlib
import importlib.util
import json
from pathlib import Path
import struct
from types import SimpleNamespace

import pefile

ROOT = Path(__file__).resolve().parents[1]
PROFILE = ROOT / 'src/test/resources/fixtures/rogue-resonance-unary-4.6.json'
PROFILE_SHA256 = 'e9c66dd4b1307ad7ce9d1c973f00deee4f1d859560886f5c46bffdba586b20d0'
spec = importlib.util.spec_from_file_location('unary_registered', Path(__file__).with_name('read-rogue-resonance-functions.py'))
functions = importlib.util.module_from_spec(spec)
spec.loader.exec_module(functions)


@lru_cache(maxsize=1)
def load_profile():
    raw = PROFILE.read_bytes().replace(b'\r\n', b'\n')
    if hashlib.sha256(raw).hexdigest() != PROFILE_SHA256:
        raise ValueError('标准数一元回调原生证据改变')
    return json.loads(raw)


def read_rva(pe, address, size):
    section = pe.get_section_by_rva(address)
    if address < 0 or size < 0 or section is None or address-section.VirtualAddress+size > section.SizeOfRawData:
        raise ValueError('一元回调常量或指令没有实际文件范围')
    raw = pe.get_data(address, size)
    if len(raw) != size: raise ValueError('一元回调常量或指令截断')
    return raw


def read_pair(pe, fact):
    if fact['wordOffset'] != 8:
        raise ValueError('不是已匹配的初始化对第二个字')
    raw = read_rva(pe, int(fact['rva'],16), 16)
    if raw.hex() != fact['bytesHex'] or hashlib.sha256(raw).hexdigest() != fact['sha256']:
        raise ValueError('一元回调常量初始化对不符')
    value = struct.unpack_from('<q',raw,8)[0]
    if value != fact['constantRaw']:
        raise ValueError('一元回调常量原字不符')
    return value


def verify_native(client):
    profile = load_profile()
    functions.verify_native(client)
    registered = {r['name']: r for r in functions.load_profile()['registrations']}
    if profile['registrations'] != [registered[name] for name in ['ceil','clamp01','abs']]:
        raise ValueError('一元回调注册名、散列键或方法身份不符')
    characters = functions.sibling('unary_native_characters','read-rogue-resonance-character.py')
    counts = characters.verify_native(client,profile)
    analyzer = functions.sibling('unary_native_metadata','analyze-client-46.py')
    identities = [{k:m[k] for k in ['index','name','rva','typeIndex','typeName']} for m in profile['nativeMethods']] + profile['helperIdentities']
    actual = analyzer.extract(SimpleNamespace(client_dir=client,types=[],type_contains=[],
        method_indices=[m['index'] for m in identities],method_rvas=[],method_name_contains=[],
        method_slots=[],string_slots=[],type_slots=[],fields=False,inheritance=False,
        field_offsets=False,disassemble=0,method_names=[]))
    if actual['selectedMethods'] != identities:
        raise ValueError('一元回调、Clamp01/Add helper或初始化器身份不符')
    pe=pefile.PE(str(client/'GameAssembly.dll'),fast_load=True)
    for fragment in profile['standardBranchFragments']:
        raw=read_rva(pe,int(fragment['rva'],16),len(bytes.fromhex(fragment['bytesHex'])))
        if raw.hex()!=fragment['bytesHex'] or hashlib.sha256(raw).hexdigest()!=fragment['sha256']:
            raise ValueError('标准Clamp01或加法指令片段不符')
    for fact in profile['initializerPairs']: read_pair(pe,fact)
    return counts


def analyze(args):
    client=args.client_dir.resolve()
    design=functions.sibling('unary_output_design','read-client-design-data.py')
    output=design.outside_client(args.output,client)
    if ROOT/'build' not in output.parents:
        raise ValueError('只接受工程build目录下的一元回调静态证据')
    methods,size=verify_native(client)
    profile=load_profile()
    report={'passed':True,'matchedNativeMethods':methods,'matchedNativeBytes':size,
        'standardInstructionFragmentBytes':profile['standardBranchFragmentBytes'],
        'helperFragmentsCompleteNativeMethods':False,'initializerBytes':32,
        'initializerPairsMatched':2,'newRegistrationsMatched':3,
        'newInputMode':profile['newInputMode'],'supportedUnaryNames':profile['supportedUnaryNames'],
        'unaryStandaloneArithmeticImplemented':True,'unaryPostfixCallerImplemented':True,
        'absMinimumRawPreserved':True,'ceilExtendedResultSupported':False,'newExtendedInputsSupported':False,
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
    parser.add_argument('--output',type=Path,default=ROOT/'build/rogue-resonance-unary-evidence.json')
    report=analyze(parser.parse_args())
    print(json.dumps({k:report[k] for k in ['passed','matchedNativeMethods','matchedNativeBytes',
        'supportedUnaryNames','absMinimumRawPreserved','liveBattleContextAttached','clientGameplayVerified']},ensure_ascii=False))
