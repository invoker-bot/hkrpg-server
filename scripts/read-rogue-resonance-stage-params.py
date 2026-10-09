"""只读核对4.6 StageParamEntry的#ADF编号与原始标准定点词构造。"""
import argparse
from functools import lru_cache
import hashlib
import importlib.util
import json
from pathlib import Path
from types import SimpleNamespace

ROOT = Path(__file__).resolve().parents[1]
PROFILE = ROOT/'src/test/resources/fixtures/rogue-resonance-stage-params-4.6.json'
PROFILE_SHA256 = '2379b22d4d991707aa2be2b82fb60cd2e8e0d00a860c8102185a4fdb9c5c0796'
spec = importlib.util.spec_from_file_location('stageparams_aliascontext',Path(__file__).with_name('read-rogue-resonance-alias-context.py'))
aliascontext = importlib.util.module_from_spec(spec)
spec.loader.exec_module(aliascontext)
functions = aliascontext.functions


@lru_cache(maxsize=1)
def load_profile():
    raw = PROFILE.read_bytes().replace(b'\r\n',b'\n')
    if hashlib.sha256(raw).hexdigest() != PROFILE_SHA256:
        raise ValueError('StageParam构造的冻结证据改变')
    return json.loads(raw)


def verify_native(client):
    p = load_profile()
    characters = functions.sibling('stageparams_native_bytes','read-rogue-resonance-character.py')
    counts = characters.verify_native(client,p)
    analyzer = functions.sibling('stageparams_native_metadata','analyze-client-46.py')
    actual = analyzer.extract(SimpleNamespace(client_dir=client,types=['StageParamEntry'],type_contains=[],
        method_indices=[m['index'] for m in p['nativeMethods']],method_rvas=[],method_name_contains=[],
        method_slots=[],string_slots=[0x9713190],type_slots=[0x937d160],fields=True,
        inheritance=False,field_offsets=True,disassemble=0,method_names=[]))
    expected = [{k:m[k] for k in ['index','name','rva','typeIndex','typeName']} for m in p['nativeMethods']]
    if actual['selectedMethods'] != expected: raise ValueError('StageParam数组/list构造方法身份不符')
    t = next(t for t in actual['methods'] if t['typeIndex']==15143)
    if {k:t[k] for k in ['typeIndex','name','fields']} != p['entryTypeFields']:
        raise ValueError('StageParamEntry名字或定点词字段不符')
    if actual['typeInfoSlots'] != [p['entryTypeSlot']]: raise ValueError('StageParamEntry工厂类型槽不符')
    if actual['stringLiteralSlots'] != [p['formatLiteral']]: raise ValueError('#ADF原始格式字符串不符')
    return counts


def analyze(args):
    client = args.client_dir.resolve()
    design = functions.sibling('stageparams_output_design','read-client-design-data.py')
    output = design.outside_client(args.output,client)
    if ROOT/'build' not in output.parents: raise ValueError('只接受工程build目录下的StageParam静态证据')
    methods,size = verify_native(client)
    p = load_profile()
    fields = ['keyFieldOffset','rawFieldOffset','nameFormat','firstSourceNameIndex',
        'explicitStandardStageParameterBuilderImplemented','sourcePositionNamesPreserved',
        'arrayAssignmentPreservesEarlierSlots','listAppendsPreserveExistingEntries','convertsThroughFloat',
        'unprefixedExpressionAliasMappingImplemented','actualParameterValuesResolved','liveBattleContextAttached',
        'liveCasterPropertiesRead','originalTaskBranchesExecutedInBattle','abilityExecutionVerified',
        'clientGameplayVerified','allResonanceAbilitiesImplemented','extendedCellsImplemented']
    r = {'passed':True,'matchedNativeMethods':methods,'matchedNativeBytes':size,
        **{k:p[k] for k in fields},'jsonInstalled':False,'clientExecutableStarted':False,'clientModified':False}
    output.parent.mkdir(parents=True,exist_ok=True)
    output.write_text(json.dumps(r,ensure_ascii=False,indent=2)+'\n','utf-8')
    return r


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--client-dir',type=Path,required=True)
    parser.add_argument('--output',type=Path,default=ROOT/'build/rogue-resonance-stage-params-evidence.json')
    print(json.dumps(analyze(parser.parse_args()),ensure_ascii=False))
