"""只读核对4.6普通动态单元的数值读取与所属lookup，不实例化真实战斗对象。"""
import argparse
from functools import lru_cache
import hashlib
import importlib.util
import json
from pathlib import Path
from types import SimpleNamespace
import pefile

ROOT = Path(__file__).resolve().parents[1]
PROFILE = ROOT/'src/test/resources/fixtures/rogue-resonance-dynamic-cell-4.6.json'
PROFILE_SHA256 = '96ce8f5a240d9249a6910a058f0dc14cd843e0f1149204662931ed53b63cfc6b'
spec = importlib.util.spec_from_file_location('dynamic_condition', Path(__file__).with_name('read-rogue-resonance-condition.py'))
condition = importlib.util.module_from_spec(spec)
spec.loader.exec_module(condition)
functions = condition.functions


@lru_cache(maxsize=1)
def load_profile():
    raw = PROFILE.read_bytes().replace(b'\r\n', b'\n')
    if hashlib.sha256(raw).hexdigest() != PROFILE_SHA256:
        raise ValueError('动态单元的冻结证据改变')
    return json.loads(raw)


def verify_native(client):
    p = load_profile()
    condition.verify_native(client)
    characters = functions.sibling('dynamic_native_characters', 'read-rogue-resonance-character.py')
    counts = characters.verify_native(client, p)
    analyzer = functions.sibling('dynamic_native_metadata', 'analyze-client-46.py')
    expected = [{k:m[k] for k in ['index','name','rva','typeIndex','typeName']} for m in p['nativeMethods']]
    f = p['nativeFragments'][0]
    expected.append({'index':f['methodIndex'],'name':f['methodName'],'rva':f['rva'],
        'typeIndex':f['typeIndex'],'typeName':f['typeName']})
    actual = analyzer.extract(SimpleNamespace(client_dir=client,
        types=[t['name'] for t in p['typeFieldFacts']], type_contains=[],
        method_indices=[m['index'] for m in expected],method_rvas=[],method_name_contains=[],
        method_slots=[],string_slots=[],type_slots=[int(p['factoryTypeSlot']['slot'],16)],
        fields=True,inheritance=False,field_offsets=True,disassemble=0,method_names=[]))
    if actual['selectedMethods'] != expected or actual['typeInfoSlots'] != [p['factoryTypeSlot']]:
        raise ValueError('动态单元方法或创建类型不符')
    types = {t['typeIndex']:t for t in actual['methods']}
    for t in p['typeFieldFacts']:
        native = types[t['typeIndex']]
        if t['name'] != native['name'] or t['fields'] != [f for f in native['fields'] if not f['isStatic']]:
            raise ValueError('DynamicFloat、单元或lookup字段布局不符')
    pe = pefile.PE(str(client/'GameAssembly.dll'),fast_load=True)
    raw = condition.integer.read_rva(pe,int(f['rva'],16),f['length'])
    if raw.hex() != f['bytesHex'] or hashlib.sha256(raw).hexdigest() != f['sha256']:
        raise ValueError('IsDynamic片段不符')
    unary = functions.sibling('dynamic_zero_initializer','read-rogue-resonance-unary.py')
    zero = next(x for x in unary.load_profile()['initializerPairs'] if x['name']=='zero')
    if p['zeroInitializerPair'] != zero: raise ValueError('零初始化来源不符')
    unary.read_pair(pe,zero)
    defaults = functions.sibling('dynamic_default_limits','read-rogue-resonance-defaults.py').load_profile()
    for k in ['minimumInitializer','maximumInitializer']:
        if p[k] != defaults[k]: raise ValueError('缺省扩展上下限来源不符')
    return counts


def analyze(args):
    client = args.client_dir.resolve()
    design = functions.sibling('dynamic_output_design','read-client-design-data.py')
    output = design.outside_client(args.output,client)
    if ROOT/'build' not in output.parents: raise ValueError('只接受工程build目录下的动态单元静态证据')
    methods,size = verify_native(client)
    p = load_profile()
    opened = ['dynamicCellReadProjectionImplemented','localLookupNumericReadsImplemented','aiTagProviderProjectionImplemented']
    closed = ['nativeNumericProviderImplemented','aliasEvaluatorBindingImplemented','dynamicCellSetterImplemented',
        'adsorptionImplemented','propertyCellImplemented','actualParameterValuesResolved','liveCasterPropertiesRead',
        'liveBattleContextAttached','originalTaskBranchesExecutedInBattle','abilityExecutionVerified',
        'clientGameplayVerified','allResonanceAbilitiesImplemented','newExtendedInputsSupported']
    r = {'passed':True,'matchedNativeMethods':methods,'matchedNativeBytes':size,'nativeFragmentBytes':9,
        'helperFragmentsCompleteNativeMethods':False,'zeroInitializerBytes':16,
        'getValInputOrder':p['getValInputOrder'],'minimumFieldOffset':24,'maximumFieldOffset':16,
        **{k:p[k] for k in opened+closed},'jsonInstalled':False,'clientExecutableStarted':False,'clientModified':False}
    output.parent.mkdir(parents=True,exist_ok=True)
    output.write_text(json.dumps(r,ensure_ascii=False,indent=2)+'\n','utf-8')
    return r


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--client-dir',type=Path,required=True)
    parser.add_argument('--output',type=Path,default=ROOT/'build/rogue-resonance-dynamic-cell-evidence.json')
    print(json.dumps(analyze(parser.parse_args()),ensure_ascii=False))
