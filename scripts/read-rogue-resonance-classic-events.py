"""只读核对九条经典共鸣参数定义；额外两条按完整布局选中能力，不执行任务。"""
import argparse
from functools import lru_cache
import hashlib
import importlib.util
import json
from pathlib import Path
import tempfile
from types import SimpleNamespace

ROOT = Path(__file__).resolve().parents[1]
PROFILE = ROOT/'src/test/resources/fixtures/rogue-resonance-classic-events-4.6.json'
PROFILE_SHA256 = '2bb1b6e576dfb2b147c877702a9ac2fb8aa02b4b38e649bd69660d6c4102f110'


def sibling(name, filename):
    spec = importlib.util.spec_from_file_location(name, Path(__file__).with_name(filename))
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


params = sibling('classic_eventparams', 'read-rogue-resonance-event-params.py')
parent = params.parent


@lru_cache(maxsize=1)
def load_profile():
    raw = PROFILE.read_bytes().replace(b'\r\n', b'\n')
    if hashlib.sha256(raw).hexdigest() != PROFILE_SHA256:
        raise ValueError('九条经典共鸣定义冻结证据改变')
    value = json.loads(raw)
    if value['previousSevenEventProfileSHA256'] != params.PROFILE_SHA256:
        raise ValueError('九条共鸣与七条原始转交证据不符')
    return value


def source_definitions(client):
    p = load_profile()
    definitions = params.source_bindings(client)
    design = sibling('classic_event_design', 'read-client-design-data.py')
    with tempfile.TemporaryDirectory(prefix='classic-resonance-', dir=ROOT/'build') as directory:
        work = Path(directory)
        report = design.extract(SimpleNamespace(client_dir=client, paths_file=None,
            paths=[r['sourcePath'] for r in p['records']], extract_dir=work/'raw', output=work/'index.json'))
        if report['indexSHA256'] != p['indexSHA256']:
            raise ValueError('九条共鸣的客户端资源索引不符')
        sources = {}
        for expected, actual in zip(p['records'], report['records'], strict=True):
            if any(actual.get(k) != v for k, v in expected.items()):
                raise ValueError('额外共鸣源记录路径、范围或原始哈希不符')
            sources[actual['sourcePath']] = Path(actual['extractedFile']).read_bytes()
        for expected in p['additionalAbilities']:
            raw = sources[expected['sourceRecord']['sourcePath']]
            layouts = parent.camera.LayoutReader(sources[expected['layoutRecord']['sourcePath']]).layout()
            offsets = [l['Offset'] for l in layouts]
            if (len(layouts) != expected['layoutCount'] or len(set(offsets)) != len(offsets)
                    or len({l['UniqueName'] for l in layouts}) != len(layouts)
                    or any(not l['UniqueName'] or not 0 <= l['Offset'] < len(raw) for l in layouts)):
                raise ValueError('额外共鸣完整布局名称/范围重复或不符')
            selected = next(l for l in layouts if l['UniqueName']==expected['decoded']['Name'])
            start = selected['Offset']
            end = min((o for o in offsets if o > start), default=len(raw))
            if selected != expected['layout'] or selected['TypeId'] != 1 or (start, end) != (expected['offset'], expected['endOffset']):
                raise ValueError('额外共鸣能力名称、类型或相邻布局边界不符')
            value_raw = raw[start:end]
            if hashlib.sha256(value_raw).hexdigest() != expected['sliceSHA256']:
                raise ValueError('额外共鸣完整选中能力字节不符')
            reader = parent.Reader(value_raw)
            value = reader.read({'kind':18, 'name':'TurnBasedAbilityConfig'})
            if reader.pos != len(value_raw) or value != expected['decoded']:
                raise ValueError('额外共鸣能力未完整消费或原始字段改变')
            tasks = [t for t in value['OnAdd'] if t['taskId']==1122]
            if len(tasks) != 1:
                raise ValueError('额外共鸣事件任务不唯一')
            t = tasks[0]
            event_raw = t['EventID']['fixedSignedRaw']
            if event_raw != (event_raw >> 33) << 33:
                raise ValueError('额外共鸣事件编号不是原始精确标准整数')
            definitions.append({'abilityName':value['Name'], 'abilityOffset':start, 'taskOffset':start+t['$offset'],
                'taskId':1122, 'eventIdRaw':event_raw, 'eventId':event_raw>>33,
                'team':t['Team'], 'totalDamageTeam':t['TotalDamageTeam'],
                'bindings':t['DynamicValues']['entries'], 'sourceDeclarations':value['DynamicValues']['floats']})
    definitions.sort(key=lambda d:d['eventId'])
    if definitions != p['classicEventDefinitions']:
        raise ValueError('九条共鸣完整参数定义、顺序、固定字或声明不符')
    return definitions


def analyze(args):
    client = args.client_dir.resolve()
    design = sibling('classic_event_output', 'read-client-design-data.py')
    output = design.outside_client(args.output, client)
    if ROOT/'build' not in output.parents:
        raise ValueError('只接受工程build目录下的经典共鸣证据')
    p = load_profile()
    methods, size = params.verify_native(client)
    parent.verify_native(client)
    parent.camera.verify_native(client)
    sources = source_definitions(client)
    flags = ['allClassicParameterDefinitionsMatched', 'explicitClassicEventCatalogImplemented',
        'implicitPrefixRemovalImplemented', 'nativeQueryTaskExecuted', 'queryLevelFallbackImplemented',
        'heldBlessingsApplied', 'battleEventCreated', 'callbackExecutionImplemented',
        'liveBattleContextAttached', 'abilityExecutionVerified', 'clientGameplayVerified', 'completeResonanceRuntimeImplemented']
    r = {'passed':True, 'matchedNativeMethods':methods, 'matchedNativeBytes':size,
        'sourceEventCount':len(sources), 'sourceParameterBindings':sum(len(s['bindings']) for s in sources),
        'sourceDeclarations':sum(len(s['sourceDeclarations']) for s in sources),
        'sourceEventIds':[s['eventId'] for s in sources],
        'additionalSourceRecordBytes':sum(r['length'] for r in p['records']),
        'additionalSelectedAbilityBytes':p['sourceSelectedAbilityBytes'],
        'additionalSelectedAbilitiesFullyConsumed':True, 'additionalParentsFullyConsumed':False,
        'additionalLayoutsFullyConsumed':True, 'additionalLayoutCounts':[a['layoutCount'] for a in p['additionalAbilities']],
        **{k:p[k] for k in flags}, 'jsonInstalled':False, 'clientExecutableStarted':False, 'clientModified':False}
    output.write_text(json.dumps(r, ensure_ascii=False, indent=2)+'\n', 'utf-8')
    return r


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--client-dir', type=Path, required=True)
    parser.add_argument('--output', type=Path, default=ROOT/'build/rogue-resonance-classic-events-evidence.json')
    print(json.dumps(analyze(parser.parse_args()), ensure_ascii=False))
