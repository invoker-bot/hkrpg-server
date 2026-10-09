"""只读核对4.6三份完整MazeBuffRow表，保留原始定点字；不执行祝福查询任务。"""
import argparse
from functools import lru_cache
import hashlib
import importlib.util
import json
from pathlib import Path
import tempfile
from types import SimpleNamespace

ROOT = Path(__file__).resolve().parents[1]
PROFILE = ROOT/'src/test/resources/fixtures/rogue-maze-buff-table-4.6.json'
PROFILE_SHA256 = '6a0e500acf87bfdf75d208399094a020d00cc377a7d1d0ba8574b6227fffb544'
FIELDS = [('ID','u'), ('BuffSeries','u'), ('BuffRarity','u'), ('Lv','u'), ('LvMax','u'),
    ('ModifierName','s'), ('InBattleBindingType','MazeBuffInBattleBindingType'), ('InBattleBindingKey','s'),
    ('ParamList','params'), ('BuffDescParamByAvatarSkillID','u'), ('BuffIcon','s'), ('BuffName','text'),
    ('BuffDesc','text'), ('BuffSimpleDesc','text'), ('BuffDescBattle','text'), ('BuffEffect','s'),
    ('MazeBuffType','MazeBuffType'), ('UseType','MazeBuffUseType'), ('MazeBuffIconType','EnumStatusType'),
    ('MazeBuffPool','u'), ('DisplayType','MazeBuffDisplayType'), ('IsDisplayEnvInLevel','b')]


def sibling(name, filename):
    spec = importlib.util.spec_from_file_location(name, Path(__file__).with_name(filename))
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


graphs = sibling('mazebuff_graph_primitives', 'decode-client-level-graphs.py')
characters = sibling('mazebuff_complete_native', 'read-rogue-resonance-character.py')


@lru_cache(maxsize=1)
def load_profile():
    raw = PROFILE.read_bytes().replace(b'\r\n', b'\n')
    if hashlib.sha256(raw).hexdigest() != PROFILE_SHA256:
        raise ValueError('原始MazeBuff表冻结证据改变')
    return json.loads(raw)


class Reader(graphs.Reader):
    def __init__(self, data, profile=None):
        if not 0 < len(data) <= 8 * 1024 * 1024:
            raise ValueError('祝福表记录超出读取边界')
        self.data, self.pos, self.profile = data, 0, profile if profile is not None else load_profile()
        self.depth, self.nodes = 0, 0

    def item(self, kind):
        if kind in ('u', 's', 'b'):
            return self.read({'kind':{'u':9, 's':14, 'b':2}[kind]})
        if kind == 'text':
            return {'cacheIndex':self.integer(32, True), 'Hash':self.uvar()}
        if kind == 'params':
            size = self.integer(32, True)
            if not -1 <= size <= 255:
                raise ValueError('有限祝福参数数组超出读取边界')
            if size == -1:
                return None
            values = [self.integer(64, True) for _ in range(size)]
            if any(v & 1 for v in values):
                raise ValueError('扩展定点参数未接入此有限标准表读取器')
            return values
        value = self.integer(32, True)
        name = self.profile['enums'][kind].get(str(value))
        if name is None:
            raise ValueError('祝福表枚举值未经核对')
        return {'raw':value, 'name':name}

    def table(self):
        count = self.integer(32, True)
        if not 0 <= count <= 200000:
            raise ValueError('祝福表行数超出读取边界')
        rows, identities = [], set()
        for _ in range(count):
            offset, mask = self.pos, self.uvar()
            if mask >> len(FIELDS):
                raise ValueError('祝福表有未知字段掩码')
            row = {name:self.item(kind) for bit, (name, kind) in enumerate(FIELDS) if mask & (1 << bit)}
            key = row.get('ID', 0), row.get('Lv', 0)
            if key[0] <= 0 or key in identities:
                raise ValueError('祝福表缺少身份或同一ID/等级重复')
            identities.add(key)
            rows.append({'offset':offset, 'endOffset':self.pos, 'mask':mask, **row})
        if self.pos != len(self.data):
            raise ValueError('祝福表存在未读取的字节')
        return rows


def verify_native(client):
    p = load_profile()
    counts = characters.verify_native(client, p)
    analyzer = sibling('mazebuff_native_metadata', 'analyze-client-46.py')
    actual = analyzer.extract(SimpleNamespace(client_dir=client,
        types=['MazeBuffRow', *p['enums']], type_contains=[],
        method_indices=[m['index'] for m in p['nativeMethods']], method_rvas=[], method_name_contains=[],
        method_slots=[int(p['fixedReaderMethodSlot']['slot'], 16)], string_slots=[], type_slots=[],
        fields=True, inheritance=False, field_offsets=True, disassemble=0, method_names=[]))
    expected = [{k:m[k] for k in ['index', 'name', 'rva', 'typeIndex', 'typeName']} for m in p['nativeMethods']]
    if actual['selectedMethods'] != expected or actual['methodInfoSlots'] != [p['fixedReaderMethodSlot']]:
        raise ValueError('祝福表完整读取器、定点读取器或委托槽身份不符')
    fields = [{k:t[k] for k in ['typeIndex', 'name', 'fields']} for t in actual['methods']]
    if sorted(fields, key=lambda t:t['typeIndex']) != sorted([p['rowTypeFields'], *p['enumFields']], key=lambda t:t['typeIndex']):
        raise ValueError('祝福表字段、实例布局或枚举常量不符')
    return counts


def analyze(args):
    client = args.client_dir.resolve()
    design = sibling('mazebuff_design_records', 'read-client-design-data.py')
    output = design.outside_client(args.output, client)
    if ROOT/'build' not in output.parents:
        raise ValueError('只接受工程build目录下的只读祝福表证据')
    p = load_profile()
    methods, size = verify_native(client)
    sources = []
    with tempfile.TemporaryDirectory(prefix='maze-buff-native-', dir=ROOT/'build') as directory:
        work = Path(directory)
        report = design.extract(SimpleNamespace(client_dir=client, paths_file=None,
            paths=[r['record']['sourcePath'] for r in p['records']], extract_dir=work/'raw', output=work/'index.json'))
        if report['indexSHA256'] != p['indexSHA256']:
            raise ValueError('祝福表资源索引不符')
        for expected, actual in zip(p['records'], report['records'], strict=True):
            if any(actual.get(k) != v for k, v in expected['record'].items()):
                raise ValueError('祝福表原件身份、范围或哈希不符')
            raw = Path(actual['extractedFile']).read_bytes()
            if raw != bytes.fromhex(expected['rawHex']):
                raise ValueError('祝福表完整原件字节不符')
            rows = Reader(raw, p).table()
            selected = [row for row in rows if any(row['ID'] in range(base, base+8) for base in range(612020, 612621, 100))]
            words = sum(len(r.get('ParamList') or []) for r in rows)
            if len(rows) != expected['rows'] or words != expected['rawParameterCount'] or selected != expected['selected']:
                raise ValueError('祝福表行数、参数数量或原始经典共鸣行不符')
            sources.append({'sourcePath':actual['sourcePath'], 'bytes':len(raw), 'rows':len(rows), 'rawParameters':words,
                'fullyConsumed':True, 'selectedClassicRows':len(selected)})
    fields = ['rawSourceParametersMatched', 'exactStandardNativeTableReaderImplemented',
        'runtimeResourcesInstalled', 'nativeQueryTaskExecuted', 'queryLevelFallbackImplemented',
        'queryIndexConventionMatched', 'heldBlessingsApplied', 'liveBattleContextAttached',
        'abilityExecutionVerified', 'clientGameplayVerified', 'completeResonanceRuntimeImplemented']
    result = {'passed':True, 'matchedNativeMethods':methods, 'matchedNativeBytes':size,
        'sourceTables':sources, 'sourceTableBytes':sum(r['bytes'] for r in sources),
        'sourceRows':sum(r['rows'] for r in sources), 'sourceRawParameters':sum(r['rawParameters'] for r in sources),
        **{k:p[k] for k in fields}, 'convertsThroughFloat':False, 'clientExecutableStarted':False, 'clientModified':False}
    output.write_text(json.dumps(result, ensure_ascii=False, indent=2)+'\n', 'utf-8')
    return result


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--client-dir', type=Path, required=True)
    parser.add_argument('--output', type=Path, default=ROOT/'build/rogue-maze-buff-table-evidence.json')
    print(json.dumps(analyze(parser.parse_args()), ensure_ascii=False))
