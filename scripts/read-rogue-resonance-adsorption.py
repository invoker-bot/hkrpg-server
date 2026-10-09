"""只读核对4.6普通动态单元吸附的标准数分支，不安装资源或执行DLL。"""
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
PROFILE = ROOT/'src/test/resources/fixtures/rogue-resonance-adsorption-4.6.json'
PROFILE_SHA256 = 'ad60c721e6820df669273c475921ead196ff6952df16ffa1cd1af2c8300b50e1'
spec = importlib.util.spec_from_file_location('adsorption_setter',Path(__file__).with_name('read-rogue-resonance-dynamic-setter.py'))
setter = importlib.util.module_from_spec(spec)
spec.loader.exec_module(setter)
functions = setter.functions


@lru_cache(maxsize=1)
def load_profile():
    raw = PROFILE.read_bytes().replace(b'\r\n',b'\n')
    if hashlib.sha256(raw).hexdigest() != PROFILE_SHA256: raise ValueError('标准吸附冻结证据改变')
    return json.loads(raw)


def verify_native(client):
    p = load_profile()
    setter.verify_native(client)
    characters = functions.sibling('adsorption_native_characters','read-rogue-resonance-character.py')
    counts = characters.verify_native(client,p)
    analyzer = functions.sibling('adsorption_native_metadata','analyze-client-46.py')
    identities = [{k:m[k] for k in ['index','name','rva','typeIndex','typeName']} for m in p['nativeMethods']]
    identities.insert(0,{'index':3059,'name':'Pow','rva':'0x1c24d130','typeIndex':295,'typeName':'Math'})
    actual = analyzer.extract(SimpleNamespace(client_dir=client,types=[],type_contains=[],
        method_indices=[m['index'] for m in identities],method_rvas=[],method_name_contains=[],
        method_slots=[],string_slots=[],type_slots=[],fields=False,inheritance=False,field_offsets=False,
        disassemble=0,method_names=[]))
    if actual['selectedMethods'] != identities: raise ValueError('吸附、乘除或Math.Pow身份不符')
    old = setter.load_profile()
    if p['adsorptionTypeFields'] != old['adsorptionTypeFields'] or p['nativeMethods'][2] != old['nativeMethods'][1]:
        raise ValueError('吸附helper或配置字段不是此前核对的来源')
    unary = functions.sibling('adsorption_unary_constants','read-rogue-resonance-unary.py')
    pe = pefile.PE(str(client/'GameAssembly.dll'),fast_load=True)
    for f in p['nativeFragments']+[p['powerBaseConstant']]:
        raw = unary.read_rva(pe,int(f['rva'],16),f['length'])
        if raw.hex() != f['bytesHex'] or hashlib.sha256(raw).hexdigest() != f['sha256']:
            raise ValueError('Pow片段或指数底数不符')
    if struct.unpack('<d',bytes.fromhex(p['powerBaseConstant']['bytesHex'])) != (10.0,):
        raise ValueError('吸附指数底数不是10')
    if p['initializerPairs'] != unary.load_profile()['initializerPairs']: raise ValueError('ZERO/ONE来源不符')
    for pair in p['initializerPairs']: unary.read_pair(pe,pair)
    return counts


def analyze(args):
    client = args.client_dir.resolve()
    design = functions.sibling('adsorption_output_design','read-client-design-data.py')
    output = design.outside_client(args.output,client)
    if ROOT/'build' not in output.parents: raise ValueError('只接受工程build目录下的吸附静态证据')
    methods,size = verify_native(client)
    p = load_profile()
    keys = ['nonNullStandardAdsorptionImplemented','nullAdsorptionStillNoOp','getterDoesNotAdsorb',
        'extendedInputsOrIntermediateResultsSupported','positiveDigitsAboveNineSupported',
        'propertyCellImplemented','aliasEvaluatorBindingImplemented','actualParameterValuesResolved',
        'liveCasterPropertiesRead','liveBattleContextAttached','originalTaskBranchesExecutedInBattle',
        'abilityExecutionVerified','clientGameplayVerified','allResonanceAbilitiesImplemented']
    r = {'passed':True,'matchedNativeMethods':methods,'matchedNativeBytes':size,'nativeFragmentBytes':5,
        'helperFragmentsCompleteNativeMethods':False,'initializerBytes':32,'powerBaseConstantBytes':8,
        'supportedFractionalDigits':p['supportedFractionalDigits'],
        'maximumPositiveFractionalDigit':9,'sourceWriteOrder':p['sourceWriteOrder'],
        **{k:p[k] for k in keys},'jsonInstalled':False,'clientExecutableStarted':False,'clientModified':False}
    output.parent.mkdir(parents=True,exist_ok=True)
    output.write_text(json.dumps(r,ensure_ascii=False,indent=2)+'\n','utf-8')
    return r


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--client-dir',type=Path,required=True)
    parser.add_argument('--output',type=Path,default=ROOT/'build/rogue-resonance-adsorption-evidence.json')
    print(json.dumps(analyze(parser.parse_args()),ensure_ascii=False))
