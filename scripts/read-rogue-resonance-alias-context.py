"""只读核对4.6 lookup的临时别名上下文绑定与恢复；实际任务图仍未解码。"""
import argparse
from functools import lru_cache
import hashlib
import importlib.util
import json
from pathlib import Path
from types import SimpleNamespace

ROOT = Path(__file__).resolve().parents[1]
PROFILE = ROOT/'src/test/resources/fixtures/rogue-resonance-alias-context-4.6.json'
PROFILE_SHA256 = '95a147af91ffe3b52ef014a1ad9c27501a42a42cb5c8fdb3ae3192520e96e007'
spec = importlib.util.spec_from_file_location('aliascontext_taskcell',Path(__file__).with_name('read-rogue-resonance-task-cell.py'))
taskcell = importlib.util.module_from_spec(spec)
spec.loader.exec_module(taskcell)
functions = taskcell.functions


@lru_cache(maxsize=1)
def load_profile():
    raw = PROFILE.read_bytes().replace(b'\r\n',b'\n')
    if hashlib.sha256(raw).hexdigest() != PROFILE_SHA256:
        raise ValueError('临时别名上下文绑定的冻结证据改变')
    return json.loads(raw)


def verify_native(client):
    p = load_profile()
    taskcell.verify_native(client)
    characters = functions.sibling('aliascontext_native_bytes','read-rogue-resonance-character.py')
    counts = characters.verify_native(client,p)
    analyzer = functions.sibling('aliascontext_native_metadata','analyze-client-46.py')
    actual = analyzer.extract(SimpleNamespace(client_dir=client,types=['ENEIHJJPNPI'],type_contains=[],
        method_indices=[m['index'] for m in p['nativeMethods']],method_rvas=[],method_name_contains=[],
        method_slots=[],string_slots=[],type_slots=[],fields=True,inheritance=False,field_offsets=True,
        disassemble=0,method_names=[]))
    expected = [{k:m[k] for k in ['index','name','rva','typeIndex','typeName']} for m in p['nativeMethods']]
    if actual['selectedMethods'] != expected: raise ValueError('逐键读取、NK getter或别名求值入口身份不符')
    t = next(t for t in actual['methods'] if t['typeIndex']==10850)
    if {k:t[k] for k in ['typeIndex','name','fields']} != p['lookupTypeFields']:
        raise ValueError('lookup临时别名接口字段不符')
    return counts


def analyze(args):
    client = args.client_dir.resolve()
    design = functions.sibling('aliascontext_output_design','read-client-design-data.py')
    output = design.outside_client(args.output,client)
    if ROOT/'build' not in output.parents: raise ValueError('只接受工程build目录下的上下文绑定静态证据')
    methods,size = verify_native(client)
    p = load_profile()
    fields = ['bindingFieldOffset','readVirtualSlot','bindingOrder','explicitContextAliasBindingImplemented',
        'restoresPreviousBindingOnReturn','restoresPreviousBindingOnFailure','sameLookupConcurrentContextsSerialized',
        'actualTaskConfigurationDecoded','nativeAliasTaskSequenceImplemented','liveBattleContextAttached',
        'actualParameterValuesResolved','liveCasterPropertiesRead','originalTaskBranchesExecutedInBattle',
        'abilityExecutionVerified','clientGameplayVerified','allResonanceAbilitiesImplemented','extendedCellsImplemented']
    r = {'passed':True,'matchedNativeMethods':methods,'matchedNativeBytes':size,
        **{k:p[k] for k in fields},'jsonInstalled':False,'clientExecutableStarted':False,'clientModified':False}
    output.parent.mkdir(parents=True,exist_ok=True)
    output.write_text(json.dumps(r,ensure_ascii=False,indent=2)+'\n','utf-8')
    return r


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--client-dir',type=Path,required=True)
    parser.add_argument('--output',type=Path,default=ROOT/'build/rogue-resonance-alias-context-evidence.json')
    print(json.dumps(analyze(parser.parse_args()),ensure_ascii=False))
