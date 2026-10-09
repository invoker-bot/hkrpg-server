"""只读核对固定本机 4.6 的三份完整战斗关卡表，不安装或执行关卡。"""
import argparse
import hashlib
import importlib.util
import json
import tempfile
from pathlib import Path
from types import SimpleNamespace

MAX_ROWS = 200000
MAX_RECORD = 8 * 1024 * 1024
TABLES = {
    'StageConfig': {'entryKey': '4efae292630058fd', 'length': 4361268, 'rows': 29515,
                    'sha256': '3f15418faae1f6f63683805820c74368b51d85520db88a1da8dc02f837f743e6',
                    'semanticSHA256': 'f890f7b9886f40e4bdef2e6dba36027455bb3192e148cce6a83f1df714f71ad5'},
    'StageTestConfig': {'entryKey': '02e3900fff97c0cf', 'length': 100383, 'rows': 829,
                        'sha256': '970430268aa182b34f105ddad84fe20751fed25cc47c1a7869a43abcf5069550',
                        'semanticSHA256': 'df9bb14121b90ecb36c61e84cd428af95da1bdb88c1ade20f7551c577126b259'},
    'StageConfigLD': {'entryKey': '1480f80c46f4c751', 'length': 1, 'rows': 0,
                      'sha256': '6e340b9cffb37a989ca544e6bb780a2c78901d3fb33738768511a30617afa01d',
                      'semanticSHA256': '4f53cda18c2baa0c0354bb5f9a3ecbe5ed12ab4d8e11ba873c2f11161202b945'},
}
# StageRow.FromBinary 101757 / 1dade570; processed caches are not serialized fields.
FIELDS = [('StageID', 'u'), ('StageType', 'enum'), ('StageName', 'text'),
          ('HardLevelGroup', 'u'), ('Level', 'u'), ('EliteGroup', 'u'),
          ('LevelGraphPath', 's'), ('StageAbilityConfig', 'as'), ('BattleScoringGroup', 'u'),
          ('SubLevelGraphs', 'sub'), ('StageConfigData', 'kv'), ('MonsterList', 'waves'),
          ('LevelLoseCondition', 'as'), ('LevelWinCondition', 'as'),
          ('ForbidAutoBattle', 'b'), ('ForbidViewMode', 'b'), ('Release', 'b'),
          ('ForbidExitBattle', 'b'), ('MonsterWarningRatio', 'f'),
          ('ResetBattleSpeed', 'b'), ('TrialAvatarList', 'au')]
# Explicit Int32 constants from the fixed client's StageType (type 10419).
STAGE_TYPES = {0: 'Unknown', 1: 'Mainline', 2: 'Maze', 3: 'Adventure', 4: 'Cocoon',
               5: 'FarmElement', 6: 'Client', 7: 'FarmRelic', 8: 'VerseSimulation',
               9: 'Trial', 10: 'PunkLord', 11: 'FightActivity', 12: 'TrialAdventure',
               13: 'BoxingClub', 14: 'TrialInBattle', 15: 'RogueChallengeActivity',
               16: 'TreasureDungeon', 17: 'AetherDivide', 18: 'FantasticStory',
               19: 'BattleCollege', 20: 'Heliobus', 21: 'RogueEndlessActivity',
               22: 'StrongChallengeActivity', 23: 'Challenge', 24: 'TelevisionActivity',
               25: 'EvolveBuildActivity', 26: 'FeverTimeActivity', 27: 'ClockParkActivity',
               28: 'RogueRelic', 29: 'SwordTraining', 30: 'StarFightActivity',
               31: 'SummonActivity', 32: 'FightFest', 33: 'GridFightActivity',
               34: 'FateActivity', 35: 'LocalLegend', 36: 'ElationActivity',
               37: 'FateRin', 38: 'ExpeditionBattle', 39: 'FinalityActivity'}
# Native child readers: 88853 / 1d2c6ef0, 101745 / 1d063260, 101739 / 1dae34c0.
CHILD_FIELDS = {
    'kvitem': ['BFLIFKBEOPJ', 'MNDFOPKBHKP'],
    'subitem': ['BFLIFKBEOPJ', 'KACPIAGHKMN', 'EOAHMJFOLHD', 'MEDEFCIKNLA',
                'APNFMFEMPNI', 'LGNKFIIEICM', 'BIEJJCIFBGA'],
    'wave': ['Monster0', 'Monster1', 'Monster2', 'Monster3', 'Monster4'],
}


def module(name, filename):
    spec = importlib.util.spec_from_file_location(name, Path(__file__).with_name(filename))
    result = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(result)
    return result


graphs = module('stage_primitives', 'decode-client-level-graphs.py')
design = module('stage_design', 'read-client-design-data.py')


class Reader(graphs.Reader):
    def __init__(self, data):
        # Only these pinned table records require a larger bound than a level graph.
        if not 0 < len(data) <= MAX_RECORD:
            raise ValueError('关卡表记录超出 8 MiB 读取边界')
        self.data, self.pos, self.profile = data, 0, {}
        self.depth, self.nodes = 0, 0

    def item(self, kind):
        primitive = {'u': 9, 's': 14, 'b': 2, 'f': 12}
        if kind in primitive:
            return self.read({'kind': primitive[kind]})
        if kind == 'enum':
            value = self.integer(32, True)
            if value not in STAGE_TYPES:
                raise ValueError('关卡类型不是已核对的显式枚举值')
            return STAGE_TYPES[value]
        if kind == 'text':
            self.integer(32, True)  # Runtime TextID cache index is not an authored JSON field.
            return {'Hash': self.uvar()}
        if kind in CHILD_FIELDS:
            names = CHILD_FIELDS[kind]
            mask = self.uvar()
            if mask >> len(names):
                raise ValueError('关卡子记录有未知字段掩码')
            return {name: self.item('u' if kind == 'wave' else 's')
                    for bit, name in enumerate(names) if mask & (1 << bit)}
        children = {'as': 's', 'au': 'u', 'kv': 'kvitem', 'sub': 'subitem', 'waves': 'wave'}
        if kind not in children:
            raise ValueError('未核对的关卡字段类型')
        size = self.integer(32, True)
        if size == -1:
            return None
        if not 0 <= size <= graphs.MAX_ARRAY:
            raise ValueError('关卡字段数组超出读取边界')
        return [self.item(children[kind]) for _ in range(size)]


def decode_table(data):
    reader = Reader(data)
    count = reader.integer(32, True)
    if not 0 <= count <= MAX_ROWS:
        raise ValueError('关卡行数超出读取边界')
    rows, identities = [], set()
    for _ in range(count):
        reader.nodes = 0
        mask = reader.uvar()
        if mask >> len(FIELDS):
            raise ValueError('关卡行有未知字段掩码')
        row = {name: reader.item(kind) for bit, (name, kind) in enumerate(FIELDS)
               if mask & (1 << bit)}
        identity = row.get('StageID', 0)
        if identity <= 0 or identity in identities:
            raise ValueError('关卡缺少正整数身份或身份重复')
        identities.add(identity)
        rows.append(row)
    if reader.pos != len(data):
        raise ValueError('关卡表有未读取的剩余字节')
    return rows


def canonical(rows):
    return json.dumps(rows, sort_keys=True, separators=(',', ':'), ensure_ascii=False).encode('utf-8')


def validate_stages(stages):
    if (not 1 <= len(stages) <= 256 or len(set(stages)) != len(stages)
            or any(type(stage) is not int or not 1 <= stage <= 0xffffffff for stage in stages)):
        raise ValueError('仅接受 1..256 个唯一、正整数 UInt32 关卡编号')


def inspect(args):
    validate_stages(args.stages)
    client = args.client_dir.resolve()
    output = design.outside_client(args.output, client)
    tables, selected, found = [], [], set()
    with tempfile.TemporaryDirectory(prefix='hkrpg-stage-tables-') as directory:
        temp = Path(directory)
        paths = ['ExcelOutputGameCore/' + name + '.json' for name in TABLES]
        source = design.extract(SimpleNamespace(client_dir=client, paths=paths, paths_file=None,
                                               extract_dir=temp / 'raw', output=temp / 'index.json'))
        for (name, expected), record in zip(TABLES.items(), source['records']):
            if (record['status'] != 'read'
                    or any(record.get(k) != expected[k] for k in ['entryKey', 'length', 'sha256'])):
                raise ValueError('关卡表的记录键、长度或哈希与证据不匹配')
            raw = Path(record['extractedFile']).read_bytes()
            if len(raw) != expected['length'] or hashlib.sha256(raw).hexdigest() != expected['sha256']:
                raise ValueError('关卡表在读取后发生变化')
            rows = decode_table(raw)
            digest = hashlib.sha256(canonical(rows)).hexdigest()
            if len(rows) != expected['rows'] or digest != expected['semanticSHA256']:
                raise ValueError('完整关卡表与已核对的原始形状不符')
            tables.append({'name': name, 'record': {k: v for k, v in record.items() if k != 'extractedFile'},
                           'tableFullyConsumed': True, 'rowCount': len(rows),
                           'tableSemanticSHA256': digest})
            for row in rows:
                if row['StageID'] in args.stages:
                    if row['StageID'] in found:
                        raise ValueError('指定关卡同时出现在多个表中，拒绝合并覆盖')
                    found.add(row['StageID'])
                    selected.append({'table': name, 'row': row})
    report = {'clientExecutableStarted': False, 'clientModified': False, 'clientAccepted': False,
              'jsonInstalled': False, 'scriptsExecuted': False, 'completeGameplay': False,
              'clientFiles': source['files'], 'indexSHA256': source['indexSHA256'],
              'verifiedChunks': source['verifiedChunks'], 'tables': tables,
              'requestedStageIDs': args.stages,
              'missingStageIDs': [s for s in args.stages if s not in found], 'stages': selected}
    content = (json.dumps(report, ensure_ascii=False, indent=2) + '\n').encode('utf-8')
    if output.exists():
        if not output.is_file() or output.read_bytes() != content:
            raise FileExistsError(f'拒绝覆盖已有不同内容的报告: {output}')
    else:
        output.parent.mkdir(parents=True, exist_ok=True)
        with output.open('xb') as stream:
            stream.write(content)
    return report


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--client-dir', type=Path, required=True)
    parser.add_argument('--stages', nargs='+', type=int, default=[100, 166, 177, 190, 201342210])
    parser.add_argument('--output', type=Path, default=Path('build/client46-stage-tables.json'))
    args = parser.parse_args()
    result = inspect(args)
    print(json.dumps({'tables': [(t['name'], t['rowCount']) for t in result['tables']],
                      'missingStageIDs': result['missingStageIDs'], 'clientAccepted': False,
                      'jsonInstalled': False}))
