"""只读核对4.6两份完整战斗事件表及当前波次StageID创建检查，不创建实体。"""
import argparse
from functools import lru_cache
import hashlib
import importlib.util
import json
from pathlib import Path
import tempfile
from types import SimpleNamespace

ROOT = Path(__file__).resolve().parents[1]
PROFILE = ROOT/'src/test/resources/fixtures/rogue-battle-event-tables-4.6.json'
PROFILE_SHA256 = '2b0987ec82d7405bc25aed170eb6a4e536433c6acfb33b6099f46ce1f4702a05'
CONFIG = [('BattleEventID','u'),('Team','TeamType'),('EventSubType','BattleEventEntitySubType'),
    ('BEActionBarType','u'),('BattleEventButtonType','u'),('BattleEventName','s'),('HeadIcon','s'),
    ('AbilityList','strings'),('OverrideProperty','properties'),('Speed','fixed'),('HardLevel','b'),
    ('EliteGroup','b'),('ActionBarDescrptionText','text'),('DescrptionText','s'),('ParamList','fixeds'),('AssetPackName','s')]
DATA = [('BattleEventID','u'),('Config','s'),('Prefab','s'),('LevelAreaPrefab','s'),('BEActionBarPrefab','s'),
    ('BasePoint','s'),('SkillIDList','uints'),('IsSPReserved','b')]


def sibling(name,filename):
    spec = importlib.util.spec_from_file_location(name,Path(__file__).with_name(filename))
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


graphs = sibling('event_table_primitives','decode-client-level-graphs.py')
characters = sibling('event_table_native','read-rogue-resonance-character.py')


@lru_cache(maxsize=1)
def load_profile():
    raw = PROFILE.read_bytes().replace(b'\r\n',b'\n')
    if hashlib.sha256(raw).hexdigest()!=PROFILE_SHA256:
        raise ValueError('战斗事件完整表冻结证据改变')
    return json.loads(raw)


class Reader(graphs.Reader):
    def __init__(self,raw):
        if not 0<len(raw)<=8*1024*1024:
            raise ValueError('有限事件表记录大小不符')
        self.data,self.pos = raw,0
        self.profile = {}
        self.nodes,self.depth = 0,0
        self.enums = {t['name']:{f['constant']['value']:f['name'] for f in t['fields'] if 'constant' in f}
            for t in load_profile()['types'] if t['name'] in ['TeamType','BattleEventEntitySubType','AbilityProperty']}

    def array(self,kind):
        size = self.integer(32,True)
        if not -1<=size<=512:
            raise ValueError('事件数组超出有限读取边界')
        return None if size==-1 else [self.item(kind) for _ in range(size)]

    def fixed(self):
        value = self.integer(64,True)
        if value&1:
            raise ValueError('扩展事件定点数未接入此有限标准表')
        return value

    def item(self,kind):
        if kind in ['u','s','b']:
            return self.read({'kind':{'u':9,'s':14,'b':2}[kind]})
        if kind=='text':
            return {'cacheIndex':self.integer(32,True),'Hash':self.uvar()}
        if kind=='fixed':
            return self.fixed()
        if kind in ['strings','fixeds','properties','uints']:
            return self.array({'strings':'s','fixeds':'fixed','properties':'property','uints':'u'}[kind])
        if kind=='property':
            start,mask = self.pos,self.uvar()
            if mask>>2:
                raise ValueError('事件覆盖属性存在未知字段掩码')
            return {'offset':start,'mask':mask,
                **({'PropertyType':self.item('AbilityProperty')} if mask&1 else {}),
                **({'Value':self.fixed()} if mask&2 else {})}
        value = self.integer(32,True)
        if value not in self.enums[kind]:
            raise ValueError('事件枚举未经核对')
        return {'raw':value,'name':self.enums[kind][value]}

    def table(self,fields):
        count = self.integer(32,True)
        if not 0<=count<=200000:
            raise ValueError('事件行数超出有限读取边界')
        rows,ids = [],set()
        for _ in range(count):
            start,mask = self.pos,self.uvar()
            if mask>>len(fields):
                raise ValueError('事件表存在未知字段掩码')
            row = {name:self.item(kind) for i,(name,kind) in enumerate(fields) if mask&(1<<i)}
            identity = row.get('BattleEventID',0)
            if identity<=0 or identity in ids:
                raise ValueError('事件表缺少身份或重复')
            ids.add(identity)
            rows.append({'offset':start,'endOffset':self.pos,'mask':mask,**row})
        if self.pos!=len(self.data):
            raise ValueError('事件表存在未读取的字节')
        return rows


def verify_native(client):
    p = load_profile()
    counts = characters.verify_native(client,p)
    analyzer = sibling('event_table_metadata','analyze-client-46.py')
    actual = analyzer.extract(SimpleNamespace(client_dir=client,
        types=[t['name'] for t in p['types']]+[t['name'] for t in p['stageContext']],type_contains=[],
        method_indices=[m['index'] for m in p['nativeMethods']],method_rvas=[],method_name_contains=[],
        method_slots=[],string_slots=[],type_slots=[],fields=True,inheritance=False,
        field_offsets=True,disassemble=0,method_names=[]))
    expected = [{k:m[k] for k in ['index','name','rva','typeIndex','typeName']}
        for m in sorted(p['nativeMethods'],key=lambda m:m['index'])]
    if sorted(actual['selectedMethods'],key=lambda m:m['index'])!=expected:
        raise ValueError('事件表、覆盖属性或创建检查方法身份不符')
    types = {t['name']:t for t in actual['methods']}
    if [{k:types[t['name']][k] for k in ['typeIndex','name','fields']} for t in p['types']]!=p['types']:
        raise ValueError('事件字段、类型或枚举常量不符')
    for expected in p['stageContext']:
        t = types[expected['name']]
        field = next(f for f in t['fields'] if f.get('instanceOffset')==expected['field']['instanceOffset'])
        if t['typeIndex']!=expected['typeIndex'] or field!=expected['field']:
            raise ValueError('当前波次关卡上下文字段身份不符')
    return counts


def analyze(args):
    client = args.client_dir.resolve()
    design = sibling('event_table_design','read-client-design-data.py')
    output = design.outside_client(args.output,client)
    if ROOT/'build' not in output.parents:
        raise ValueError('只接受工程build目录下的事件表只读证据')
    p = load_profile()
    methods,size = verify_native(client)
    sources = []
    with tempfile.TemporaryDirectory(prefix='event-table-',dir=ROOT/'build') as directory:
        work = Path(directory)
        report = design.extract(SimpleNamespace(client_dir=client,paths_file=None,
            paths=[r['record']['sourcePath'] for r in p['records']],extract_dir=work/'raw',output=work/'index.json'))
        if report['indexSHA256']!=p['indexSHA256']:
            raise ValueError('事件表设计数据索引不符')
        for expected,actual in zip(p['records'],report['records'],strict=True):
            if any(actual.get(k)!=v for k,v in expected['record'].items()):
                raise ValueError('事件表原件路径、范围或哈希不符')
            raw = Path(actual['extractedFile']).read_bytes()
            if raw!=bytes.fromhex(expected['rawHex']):
                raise ValueError('事件完整原始字节不符')
            config = 'BattleEventConfig.' in actual['sourcePath']
            rows = Reader(raw).table(CONFIG if config else DATA)
            selected = [r for r in rows if 41001<=r['BattleEventID']<=41009]
            if (len(rows)!=expected['rows'] or selected!=expected['selected']
                    or sum(len(r.get('OverrideProperty') or []) for r in rows)!=expected['rowPropertyCount']
                    or sum(len(r.get('ParamList') or []) for r in rows)!=expected['paramCount']):
                raise ValueError('事件表行数、经典定义或原始参数数量不符')
            sources.append({'sourcePath':actual['sourcePath'],'bytes':len(raw),'rows':len(rows),
                'fullyConsumed':True,'classicRows':len(selected)})
    flags = ['allOriginalEventTablesConsumed','standardBattleEventTableReaderImplemented',
        'explicitEventPreparationImplemented','battleEventCreated','liveBattleContextAttached',
        'callbackExecutionImplemented','abilityExecutionVerified','clientGameplayVerified','completeResonanceRuntimeImplemented']
    result = {'passed':True,'matchedNativeMethods':methods,'matchedNativeBytes':size,
        'sourceTables':sources,'sourceTableBytes':sum(r['bytes'] for r in sources),
        'sourceRows':sum(r['rows'] for r in sources),'classicRows':sum(r['classicRows'] for r in sources),
        'sourcePropertyValues':820,'sourceParameters':488,'currentWaveStageFieldMatched':True,
        **{k:p[k] for k in flags},'jsonInstalled':False,'clientExecutableStarted':False,'clientModified':False}
    output.write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n','utf-8')
    return result


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--client-dir',type=Path,required=True)
    parser.add_argument('--output',type=Path,default=ROOT/'build/rogue-battle-event-tables-evidence.json')
    print(json.dumps(analyze(parser.parse_args()),ensure_ascii=False))
