"""只读核对4.6事件参数的逐项源上下文求值，以及七条原始经典共鸣转交配置。"""
import argparse
from functools import lru_cache
import hashlib
import importlib.util
import json
from pathlib import Path
from types import SimpleNamespace

ROOT = Path(__file__).resolve().parents[1]
PROFILE = ROOT/'src/test/resources/fixtures/rogue-resonance-event-params-4.6.json'
PROFILE_SHA256 = 'f463f67e60a8cfa39f5e2ccd9cdeea7d8c51a54554903910c86a590fe3841c39'


def sibling(name, filename):
    spec = importlib.util.spec_from_file_location(name, Path(__file__).with_name(filename))
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


parent = sibling('eventparams_parent', 'read-rogue-resonance-shared-parent.py')
variables = sibling('eventparams_registry', 'read-rogue-resonance-variables.py')


@lru_cache(maxsize=1)
def load_profile():
    raw = PROFILE.read_bytes().replace(b'\r\n', b'\n')
    if hashlib.sha256(raw).hexdigest() != PROFILE_SHA256:
        raise ValueError('事件参数转交的冻结证据改变')
    return json.loads(raw)


def verify_native(client):
    p = load_profile()
    variables.verify_native(client)
    characters = parent.camera.abilities.characters
    counts = characters.verify_native(client, p)
    analyzer = sibling('eventparams_native_metadata', 'analyze-client-46.py')
    actual = analyzer.extract(SimpleNamespace(client_dir=client, types=['CreateBattleEvent'], type_contains=[],
        method_indices=[m['index'] for m in p['nativeMethods']], method_rvas=[], method_name_contains=[],
        method_slots=[int(p['factoryMethodSlot']['slot'], 16)], string_slots=[],
        type_slots=[int(p['runtimeTypeSlot']['slot'], 16), int(p['registryBinding']['configTypeSlotRva'], 16)],
        fields=True, inheritance=False, field_offsets=True, disassemble=0, method_names=[]))
    expected = [{k:m[k] for k in ['index', 'name', 'rva', 'typeIndex', 'typeName']} for m in p['nativeMethods']]
    if actual['selectedMethods'] != expected:
        raise ValueError('事件参数工厂/运行方法身份不符')
    t = next(t for t in actual['methods'] if t['name']=='CreateBattleEvent')
    if {k:t[k] for k in ['typeIndex', 'name', 'fields']} != p['configTypeFields']:
        raise ValueError('事件参数配置字段身份不符')
    if actual['methodInfoSlots'] != [p['factoryMethodSlot']]:
        raise ValueError('事件参数运行时工厂方法槽不符')
    runtime = next(t for t in actual['typeInfoSlots'] if t['slot']==p['runtimeTypeSlot']['slot'])
    config = next(t for t in actual['typeInfoSlots'] if t['slot']==p['registryBinding']['configTypeSlotRva'])
    if runtime != p['runtimeTypeSlot'] or (config['typeIndex'], config['typeName']) != (22975, 'CreateBattleEvent'):
        raise ValueError('事件参数工厂的配置/分配类型槽不符')
    return counts


def source_bindings(client):
    p = load_profile()
    report = parent.analyze(SimpleNamespace(client_dir=client, output=ROOT/'build/event-params-source-parent-evidence.json'))
    if (p['sourceRecord'] not in report['sourceRecords'] or not report['parentFullyConsumed']
            or report['abilityCount'] != 76):
        raise ValueError('事件参数父能力表原件与冻结范围不符')
    sources = []
    for expected in p['classicEventBindings']:
        a = next(a for a in report['decoded']['AbilityList'] if a['Name']==expected['abilityName'])
        tasks = [t for t in a['OnAdd'] if t['taskId']==1122]
        if len(tasks)!=1:
            raise ValueError('原始事件创建任务不唯一')
        t = tasks[0]
        source = {'abilityName':a['Name'], 'abilityOffset':a['$offset'], 'taskOffset':t['$offset'],
            'taskId':t['taskId'], 'eventIdRaw':t['EventID']['fixedSignedRaw'],
            'eventId':t['EventID']['fixedSignedRaw']>>33, 'team':t['Team'], 'totalDamageTeam':t['TotalDamageTeam'],
            'bindings':t['DynamicValues']['entries'], 'sourceDeclarations':a['DynamicValues']['floats']}
        if source != expected:
            raise ValueError('原始事件参数名称、顺序、变量键或声明不符')
        sources.append(source)
    return sources


def analyze(args):
    client = args.client_dir.resolve()
    design = sibling('eventparams_output_guard', 'read-client-design-data.py')
    output = design.outside_client(args.output, client)
    if ROOT/'build' not in output.parents:
        raise ValueError('只接受工程build目录下的事件参数静态证据')
    methods, size = verify_native(client)
    sources = source_bindings(client)
    p = load_profile()
    fields = ['sourceNameHashContainsPrefix', 'explicitEventParameterTransferImplemented',
        'originalClassicTransferConfigsMatched', 'originalPrefixedParameterReadMatched', 'evaluationUsesSourceContext',
        'implicitPrefixRemovalImplemented', 'actualParameterValuesResolved', 'battleEventCreated',
        'callbackExecutionImplemented', 'liveBattleContextAttached', 'liveCasterPropertiesRead',
        'originalTaskBranchesExecutedInBattle', 'abilityExecutionVerified', 'clientGameplayVerified',
        'allResonanceAbilitiesImplemented', 'extendedCellsImplemented']
    r = {'passed':True, 'matchedNativeMethods':methods, 'matchedNativeBytes':size,
        'sourceEventCount':len(sources), 'sourceParameterBindings':sum(len(s['bindings']) for s in sources),
        'sourceAbilityRecordBytes':p['sourceRecord']['length'], 'sourceEventIds':[s['eventId'] for s in sources],
        **{k:p[k] for k in fields}, 'jsonInstalled':False, 'clientExecutableStarted':False, 'clientModified':False}
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(r, ensure_ascii=False, indent=2)+'\n', 'utf-8')
    return r


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--client-dir', type=Path, required=True)
    parser.add_argument('--output', type=Path, default=ROOT/'build/rogue-resonance-event-params-evidence.json')
    print(json.dumps(analyze(parser.parse_args()), ensure_ascii=False))
