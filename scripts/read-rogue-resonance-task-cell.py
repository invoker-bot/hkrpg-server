"""只读核对4.6 NK任务单元的显式标准数投影，不加载真实任务图。"""
import argparse
from functools import lru_cache
import hashlib
import importlib.util
import json
from pathlib import Path
from types import SimpleNamespace

ROOT = Path(__file__).resolve().parents[1]
PROFILE = ROOT/'src/test/resources/fixtures/rogue-resonance-task-cell-4.6.json'
PROFILE_SHA256 = '749c6521003ac4a151308717ee1613b8d988bda5bd5f252357a1f126327bcd6a'
spec = importlib.util.spec_from_file_location('taskcell_adsorption',Path(__file__).with_name('read-rogue-resonance-adsorption.py'))
adsorption = importlib.util.module_from_spec(spec)
spec.loader.exec_module(adsorption)
functions = adsorption.functions


@lru_cache(maxsize=1)
def load_profile():
    raw = PROFILE.read_bytes().replace(b'\r\n',b'\n')
    if hashlib.sha256(raw).hexdigest() != PROFILE_SHA256: raise ValueError('任务单元冻结证据改变')
    return json.loads(raw)


def verify_native(client):
    p = load_profile()
    adsorption.verify_native(client)
    characters = functions.sibling('taskcell_native_characters','read-rogue-resonance-character.py')
    counts = characters.verify_native(client,p)
    analyzer = functions.sibling('taskcell_native_metadata','analyze-client-46.py')
    identities = [{k:m[k] for k in ['index','name','rva','typeIndex','typeName']} for m in p['nativeMethods']]
    actual = analyzer.extract(SimpleNamespace(client_dir=client,types=['NKDKGFGBPNL'],type_contains=[],
        method_indices=[m['index'] for m in identities],method_rvas=[],method_name_contains=[],
        method_slots=[],string_slots=[0x96fa600],type_slots=[0x9361588,0x9361fc0],
        fields=True,inheritance=False,field_offsets=True,disassemble=0,method_names=[]))
    if actual['selectedMethods'] != identities: raise ValueError('NK工厂、getter/setter、范围或初始化器身份不符')
    t = next(t for t in actual['methods'] if t['typeIndex']==10854)
    if {k:t[k] for k in ['typeIndex','name','fields']} != p['taskCellTypeFields']:
        raise ValueError('NK任务、范围、吸附、provider或缓存字段不符')
    slots = [{k:s[k] for k in ['slot','typeIndex','typeName']} for s in actual['typeInfoSlots']]
    if slots != [p['factoryTypeSlot'],p['compatibleProviderTypeSlot']]: raise ValueError('NK工厂或provider类型槽不符')
    if actual['stringLiteralSlots'] != [p['resultString']] or p['resultString']['value'] != '_ResultValue':
        raise ValueError('别名固定输出名不符')
    return counts


def analyze(args):
    client = args.client_dir.resolve()
    design = functions.sibling('taskcell_output_design','read-client-design-data.py')
    output = design.outside_client(args.output,client)
    if ROOT/'build' not in output.parents: raise ValueError('只接受工程build目录下的任务单元静态证据')
    methods,size = verify_native(client)
    p = load_profile()
    keys = ['setterModifiesValue','setterLogsAttemptOnly','missingProviderOrAliasReturnsCache',
        'explicitTaskCellReadProjectionImplemented','mixedFiniteLookupProjectionImplemented',
        'actualTaskConfigurationDecoded','nativeAliasTaskSequenceImplemented','aliasEvaluatorBindingImplemented',
        'actualPropertyCellsLoaded','actualParameterValuesResolved','liveCasterPropertiesRead',
        'liveBattleContextAttached','originalTaskBranchesExecutedInBattle','abilityExecutionVerified',
        'clientGameplayVerified','allResonanceAbilitiesImplemented','extendedCellsImplemented']
    r = {'passed':True,'matchedNativeMethods':methods,'matchedNativeBytes':size,
        'resultName':p['resultName'],'resultKey':p['resultKey'],'getterOrder':p['getterOrder'],
        **{k:p[k] for k in keys},'jsonInstalled':False,'clientExecutableStarted':False,'clientModified':False}
    output.parent.mkdir(parents=True,exist_ok=True)
    output.write_text(json.dumps(r,ensure_ascii=False,indent=2)+'\n','utf-8')
    return r


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--client-dir',type=Path,required=True)
    parser.add_argument('--output',type=Path,default=ROOT/'build/rogue-resonance-task-cell-evidence.json')
    print(json.dumps(analyze(parser.parse_args()),ensure_ascii=False))
