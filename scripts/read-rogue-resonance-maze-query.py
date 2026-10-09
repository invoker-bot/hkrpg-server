"""只读核对4.6任务2847的缺省等级、索引和九条原始查询；不启动客户端。"""
import argparse
from functools import lru_cache
import hashlib
import importlib.util
import json
from pathlib import Path
from types import SimpleNamespace

ROOT = Path(__file__).resolve().parents[1]
PROFILE = ROOT/'src/test/resources/fixtures/rogue-resonance-maze-query-4.6.json'
PROFILE_SHA256 = '8fb8344a248dee77c3924ff5288b11e2028dad697b45ffb225ac7cfb7fb57685'


def sibling(name, filename):
    spec = importlib.util.spec_from_file_location(name, Path(__file__).with_name(filename))
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


classic = sibling('maze_query_classic', 'read-rogue-resonance-classic-events.py')
maze = sibling('maze_query_table', 'read-rogue-maze-buff-table.py')


@lru_cache(maxsize=1)
def load_profile():
    raw = PROFILE.read_bytes().replace(b'\r\n', b'\n')
    if hashlib.sha256(raw).hexdigest() != PROFILE_SHA256:
        raise ValueError('祝福参数查询冻结证据改变')
    value = json.loads(raw)
    if (value['classicProfileSHA256'] != classic.PROFILE_SHA256
            or value['mazeTableProfileSHA256'] != maze.PROFILE_SHA256):
        raise ValueError('查询能力与原始祝福表证据不符')
    return value


def verify_native(client):
    p = load_profile()
    counts = maze.characters.verify_native(client, p)
    analyzer = sibling('maze_query_metadata', 'analyze-client-46.py')
    actual = analyzer.extract(SimpleNamespace(client_dir=client,
        types=[t['name'] for t in p['queryTypes']], type_contains=[],
        method_indices=[m['index'] for m in p['nativeMethods']], method_rvas=[],
        method_name_contains=[], method_slots=[], string_slots=[],
        type_slots=[int(p[k]['slot'],16) for k in ['runtimeTypeSlot','configTypeSlot']],
        fields=True, inheritance=False, field_offsets=True, disassemble=0, method_names=[]))
    expected = [{k:m[k] for k in ['index','name','rva','typeIndex','typeName']}
        for m in sorted(p['nativeMethods'],key=lambda m:m['index'])]
    if sorted(actual['selectedMethods'],key=lambda m:m['index']) != expected:
        raise ValueError('查询工厂、执行方法或完整配置读取器身份不符')
    names = {t['name'] for t in p['queryTypes']}
    if [{k:t[k] for k in ['typeIndex','name','fields']} for t in actual['methods'] if t['name'] in names] != p['queryTypes']:
        raise ValueError('MazeBuffParamKey/查询任务字段身份或布局不符')
    if actual['typeInfoSlots'] != sorted([p['runtimeTypeSlot'],p['configTypeSlot']],key=lambda t:t['slot']):
        raise ValueError('查询任务配置与运行类型槽不符')
    return counts


def source_queries(client):
    p = load_profile()
    # This checks the original full parent plus both selected additional abilities first.
    classic.source_definitions(client)
    parent = json.loads((ROOT/'build/event-params-source-parent-evidence.json').read_text('utf-8'))
    abilities = parent['decoded']['AbilityList'] + [a['decoded'] for a in classic.load_profile()['additionalAbilities']]
    queries = []
    for expected in p['classicQueries']:
        ability = next(a for a in abilities if a['Name']==expected['abilityName'])
        tasks = [t for t in ability['OnAdd'] if t['taskId']==2847]
        if len(tasks)!=1:
            raise ValueError('原始经典祝福查询任务不唯一')
        task = tasks[0]
        row = {'eventId':expected['eventId'],'abilityName':ability['Name'],'sourceTask':task,
            'scope':task.get('ContextScope',0),
            'queries':[{'name':name,'id':value.get('MazeBuffID',0),'level':value.get('Level',1),
                'index':value.get('Index',0),'levelPresent':'Level' in value}
                for name,value in task['QueryList']['entries']]}
        if row != expected:
            raise ValueError('原始经典查询顺序、等级字段、索引或名称改变')
        queries.append(row)
    return queries


def resolved_source_values(queries):
    rows = {}
    for record in maze.load_profile()['records']:
        for row in maze.Reader(bytes.fromhex(record['rawHex'])).table():
            identity = row['ID'],row.get('Lv',0)
            if identity in rows:
                raise ValueError('显式三表来源存在相同查询身份，不能猜测覆盖顺序')
            rows[identity] = row
    results = []
    for definition in queries:
        values = []
        for q in definition['queries']:
            row = rows.get((q['id'],q['level']))
            if row is None or not 1<=q['index']<=len(row.get('ParamList') or []):
                raise ValueError('经典查询未匹配实际原始参数')
            values.append({'name':q['name'],'raw':row['ParamList'][q['index']-1]})
        results.append({'eventId':definition['eventId'],'values':values})
    return results


def analyze(args):
    client = args.client_dir.resolve()
    design = sibling('maze_query_output', 'read-client-design-data.py')
    output = design.outside_client(args.output,client)
    if ROOT/'build' not in output.parents:
        raise ValueError('只接受工程build目录下的查询静态证据')
    p = load_profile()
    methods,size = verify_native(client)
    queries = source_queries(client)
    # Re-extract all three original tables before using their exact frozen raw words.
    maze.analyze(SimpleNamespace(client_dir=client,output=ROOT/'build/maze-query-original-tables-evidence.json'))
    resolved = resolved_source_values(queries)
    flags = ['explicitQueryExecutionImplemented','queryDefaultLevelMatched','queryIndexConventionMatched',
        'sourceFailureContinuesMatched','nativeQueryTaskExecuted','queryLevelFallbackImplemented',
        'heldBlessingsApplied','liveBattleContextAttached','clientGameplayVerified',
        'abilityExecutionVerified','completeResonanceRuntimeImplemented']
    result = {'passed':True,'matchedNativeMethods':methods,'matchedNativeBytes':size,
        'sourceEventCount':len(queries),'sourceQueryCount':sum(len(r['queries']) for r in queries),
        'allOriginalQueriesResolved':True,'resolvedValues':resolved,
        **{k:p[k] for k in flags},'battleEventCreated':False,'callbackExecutionImplemented':False,
        'hotfixPatchDecoded':False,'jsonInstalled':False,'clientExecutableStarted':False,'clientModified':False}
    output.write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n','utf-8')
    return result


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--client-dir',type=Path,required=True)
    parser.add_argument('--output',type=Path,default=ROOT/'build/rogue-resonance-maze-query-evidence.json')
    result = analyze(parser.parse_args())
    print(json.dumps({k:v for k,v in result.items() if k!='resolvedValues'},ensure_ascii=False))
