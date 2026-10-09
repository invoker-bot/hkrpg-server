"""只读核对4.6动态单元setter的无吸附分支与lookup通知顺序。"""
import argparse
from functools import lru_cache
import hashlib
import importlib.util
import json
from pathlib import Path
from types import SimpleNamespace

ROOT = Path(__file__).resolve().parents[1]
PROFILE = ROOT/'src/test/resources/fixtures/rogue-resonance-dynamic-setter-4.6.json'
PROFILE_SHA256 = 'f15b4dd0b9b77af52666112078212b1f54161e4eecf99d183e6f102eb87c028b'
spec = importlib.util.spec_from_file_location('setter_dynamic',Path(__file__).with_name('read-rogue-resonance-dynamic-cell.py'))
dynamic = importlib.util.module_from_spec(spec)
spec.loader.exec_module(dynamic)
functions = dynamic.functions


@lru_cache(maxsize=1)
def load_profile():
    raw = PROFILE.read_bytes().replace(b'\r\n',b'\n')
    if hashlib.sha256(raw).hexdigest() != PROFILE_SHA256: raise ValueError('无吸附动态setter冻结证据改变')
    return json.loads(raw)


def verify_native(client):
    p = load_profile()
    dynamic.verify_native(client)
    characters = functions.sibling('setter_native_characters','read-rogue-resonance-character.py')
    counts = characters.verify_native(client,p)
    analyzer = functions.sibling('setter_native_metadata','analyze-client-46.py')
    expected = [{k:m[k] for k in ['index','name','rva','typeIndex','typeName']} for m in p['nativeMethods']]
    actual = analyzer.extract(SimpleNamespace(client_dir=client,types=['PropertyAdsorptionConfig'],type_contains=[],
        method_indices=[m['index'] for m in expected],method_rvas=[],method_name_contains=[],
        method_slots=[],string_slots=[],type_slots=[],fields=True,inheritance=False,field_offsets=True,
        disassemble=0,method_names=[]))
    t = actual['methods'][0]
    if actual['selectedMethods'] != expected or {k:t[k] for k in ['typeIndex','name','fields']} != p['adsorptionTypeFields']:
        raise ValueError('动态setter、lookup或吸附参数字段不符')
    old = functions.sibling('setter_fixed_variables','read-rogue-resonance-variables.py').load_profile()
    if p['nativeMethods'][2] != next(m for m in old['nativeMethods'] if m['index']==74764):
        raise ValueError('通知顺序不是此前核对的lookup setter')
    return counts


def analyze(args):
    client = args.client_dir.resolve()
    design = functions.sibling('setter_output_design','read-client-design-data.py')
    output = design.outside_client(args.output,client)
    if ROOT/'build' not in output.parents: raise ValueError('只接受工程build目录下的动态setter静态证据')
    methods,size = verify_native(client)
    p = load_profile()
    opened = ['nullAdsorptionDynamicSetterImplemented','dynamicLookupSetterImplemented',
        'setterReplacesPostfixWithFixedValue','observerUsesSecondGetter','cellTemplatesCopiedPerLookup']
    closed = ['nonNullAdsorptionImplemented','rangeSettersImplemented','extendedCellsImplemented',
        'propertyCellImplemented','aliasEvaluatorBindingImplemented','actualParameterValuesResolved',
        'liveCasterPropertiesRead','liveBattleContextAttached','originalTaskBranchesExecutedInBattle',
        'abilityExecutionVerified','clientGameplayVerified','allResonanceAbilitiesImplemented']
    r = {'passed':True,'matchedNativeMethods':methods,'matchedNativeBytes':size,
        'acceptedAdsorptionConfig':'null only','lookupWriteOrder':p['lookupWriteOrder'],
        **{k:p[k] for k in opened+closed},'jsonInstalled':False,'clientExecutableStarted':False,'clientModified':False}
    output.parent.mkdir(parents=True,exist_ok=True)
    output.write_text(json.dumps(r,ensure_ascii=False,indent=2)+'\n','utf-8')
    return r


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--client-dir',type=Path,required=True)
    parser.add_argument('--output',type=Path,default=ROOT/'build/rogue-resonance-dynamic-setter-evidence.json')
    print(json.dumps(analyze(parser.parse_args()),ensure_ascii=False))
