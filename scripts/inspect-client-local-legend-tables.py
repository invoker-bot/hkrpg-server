"""只读核对固定本机 4.6 传奇挑战的完整关卡、分组、奖励和战斗目标表，不执行或安装玩法。"""
import argparse
import hashlib
import importlib.util
import json
import tempfile
from pathlib import Path
from types import SimpleNamespace

MAX_RECORD, MAX_ROWS, MAX_ARRAY = 16384, 64, 64
TABLES = {
    'LocalLegendStageConfig': {
        'entryKey': '367e3912aa7875c8', 'length': 2949, 'rows': 15,
        'sha256': '67f7279448cfaca33d7bb9a648cf7d714c7a251b3d88cd040ea360d6a6a36cfc',
        'semanticSHA256': 'f7ecb5764056d151053c118219ed46d5acc7e4ffa17929b939cdc67c89d5939a'},
    'ActivityLocalLegendGroup': {
        'entryKey': '7820c7364640609f', 'length': 719, 'rows': 5,
        'sha256': '3ef4fa137e58d3b7909dfa26f04ca582323e8bbcfa75483ebd8c5a79550b2938',
        'semanticSHA256': '383905a4d74b0ec324aff631b7d9114f3dfbe21fffa0334b3d56ee02349e38fb'},
    'ActivityLocalLegendReward': {
        'entryKey': 'c1d7c4b8835aff9e', 'length': 72, 'rows': 10,
        'sha256': '162359a1869585438e60aea5ff1a57d5b77c9e972521b30d4ab7431b593363c4',
        'semanticSHA256': '886f9a23e9ab87a5d4821dfd33169917b2416ff2b9592ecf2e1235bba9d4ab7c'},
    'BattleTargetConfig': {
        'entryKey': '36867b21343ea0d2', 'length': 10275, 'rows': 143,
        'sha256': '6fa1b1a44cc9536540d234d3c740b36502f60df0b6e949e499fbabd23c62d246',
        'semanticSHA256': 'b840c4b154c14198fd82cda3cad6f8a57122ce72a2d32c05a747a95fe1d5a300'},
}
# FromBinary 81215/1d33cb10, 81229/1d33c4f0, 81243/1d33c960: bit order,
# not metadata declaration order. These fixed records have a literal 00 prefix.
# Its broader format semantics remain unclaimed; other tables need their own reader.
FIELDS = {
    'LocalLegendStageConfig': [('GroupID', 'u'), ('DifficultyLevel', 'difficulty'),
        ('EventID', 'u'), ('UnlockQuest', 'u'), ('BattleTargetList', 'au'),
        ('FearlessStageMechanism', 'text'), ('BattleAreaID', 'u'), ('TrialAvatarList', 'au'),
        ('ManikinPrefabPath', 's'), ('ManikinConfigPath', 's')],
    'ActivityLocalLegendGroup': [('GroupID', 'u'), ('GroupTitle', 'text'),
        ('ActivityModuleID', 'u'), ('GroupPicPath', 's'), ('TutorialGuideID', 'u'),
        ('StageMechanismTitle', 'text'), ('StageMechanism', 'text'),
        ('ChallengeStrategy', 'text'), ('TeamBuildTip', 'text')],
    'ActivityLocalLegendReward': [('ID', 'u'), ('TaskType', 'reward'), ('Sort', 'i')],
    # BattleTargetConfigRow.FromBinary 87469/1d43bb30.
    'BattleTargetConfig': [('ID', 'u'), ('Type', 'target'), ('AbilityName', 's'),
        ('ParamType', 'param'), ('TargetParam', 'u'), ('HintStep', 'au'),
        ('IsShowProgress', 'u'), ('IsFixableHeight', 'b'), ('TargetName', 'text'),
        ('TargetNameSimple', 'text'), ('MultiTarget', 'au'), ('MultiTargetIconType', 'ai'),
        ('ShowInScoreCounter', 'b'), ('IconType', 'icon'), ('IconNum', 'u'),
        ('SkipWhenSuccessOnEnterBattle', 'b')],
}
# Native explicit Int32 constants; these are not inferred enum ordinals.
ENUMS = {'difficulty': {1: 'Easy', 2: 'Hard', 3: 'Fearless'},
         'reward': {1: 'EasyHard', 2: 'Fearless'},
         'target': {0: 'Unknown', 1: 'ChallengeTarget', 2: 'PassTarget'},
         'param': {0: 'Unknown', 1: 'NoPara', 2: 'Equal', 3: 'Greater', 4: 'GreaterEqual',
                   5: 'Less', 6: 'LessEqual', 7: 'NotEqual'},
         'icon': {0: 'Star', 1: 'Round', 2: 'PeakBossStar', 3: 'PeakDifficultyBossStar',
                  4: 'TierceSpecialStar'}}


def module(name, filename):
    spec = importlib.util.spec_from_file_location(name, Path(__file__).with_name(filename))
    result = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(result)
    return result


graphs = module('legend_primitives', 'decode-client-level-graphs.py')
design = module('legend_design', 'read-client-design-data.py')


class Reader(graphs.Reader):
    def __init__(self, data):
        if not 0 < len(data) <= MAX_RECORD:
            raise ValueError('传奇挑战及目标记录超出 16 KiB 读取边界')
        super().__init__(data, {})

    def item(self, kind):
        if kind in ENUMS:
            value = self.integer(32, True)
            if value not in ENUMS[kind]:
                raise ValueError('未核对的传奇挑战枚举值')
            return ENUMS[kind][value]
        if kind in ('u', 'i', 's', 'b'):
            return self.read({'kind': {'u': 9, 'i': 8, 's': 14, 'b': 2}[kind]})
        if kind == 'text':
            self.integer(32, True)  # Runtime TextID cache is not an authored field.
            return {'Hash': self.uvar()}
        if kind in ('au', 'ai'):
            count = self.integer(32, True)
            if count == -1:
                return None
            if not 0 <= count <= MAX_ARRAY:
                raise ValueError('传奇挑战数组数量超出读取边界')
            return [self.item('u' if kind == 'au' else 'icon') for _ in range(count)]
        raise ValueError('未核对的传奇挑战字段类型')


def decode_table(name, data):
    if name not in FIELDS:
        raise ValueError('未核对的传奇挑战表名')
    reader = Reader(data)
    if reader.take(1) != b'\x00':
        raise ValueError('传奇挑战固定记录前缀不符')
    count = reader.integer(32, True)
    if not 0 <= count <= (256 if name == 'BattleTargetConfig' else MAX_ROWS):
        raise ValueError('传奇挑战记录数量超出读取边界')
    fields = FIELDS[name]
    rows, identities = [], set()
    for _ in range(count):
        mask = reader.uvar()
        if mask >> len(fields):
            raise ValueError('传奇挑战记录有未知字段掩码')
        row = {key: reader.item(kind) for bit, (key, kind) in enumerate(fields)
               if mask & (1 << bit)}
        if name == 'LocalLegendStageConfig':
            identity = (row.get('GroupID', 0), row.get('DifficultyLevel'))
            valid = identity[0] > 0 and identity[1] in ENUMS['difficulty'].values()
        else:
            identity = row.get('GroupID' if name == 'ActivityLocalLegendGroup' else 'ID', 0)
            valid = identity > 0
        if not valid or identity in identities:
            raise ValueError('传奇挑战记录身份缺失或重复')
        identities.add(identity)
        rows.append(row)
    if reader.pos != len(data):
        raise ValueError('传奇挑战表有未读取的剩余字节')
    return rows


def canonical(rows):
    return json.dumps(rows, sort_keys=True, separators=(',', ':'), ensure_ascii=False).encode('utf-8')


def source_path(name):
    return ('ExcelOutputGameCore/' if name == 'BattleTargetConfig' else 'ExcelOutput/') + name + '.json'


def inspect(args):
    client = args.client_dir.resolve()
    output = design.outside_client(args.output, client)
    tables = []
    with tempfile.TemporaryDirectory(prefix='hkrpg-local-legend-') as directory:
        temp = Path(directory)
        paths = [source_path(name) for name in TABLES]
        source = design.extract(SimpleNamespace(client_dir=client, paths=paths, paths_file=None,
                                               extract_dir=temp / 'raw', output=temp / 'index.json'))
        if len(source['records']) != len(TABLES):
            raise ValueError('传奇挑战索引记录数量不符')
        for (name, expected), record in zip(TABLES.items(), source['records']):
            if (record['status'] != 'read' or record.get('sourcePath') != source_path(name)
                    or any(record.get(k) != expected[k] for k in ['entryKey', 'length', 'sha256'])):
                raise ValueError('传奇挑战记录键、长度或哈希与固定证据不符')
            raw = Path(record['extractedFile']).read_bytes()
            if len(raw) != expected['length'] or hashlib.sha256(raw).hexdigest() != expected['sha256']:
                raise ValueError('传奇挑战表在读取后发生变化')
            rows = decode_table(name, raw)
            digest = hashlib.sha256(canonical(rows)).hexdigest()
            if len(rows) != expected['rows'] or digest != expected['semanticSHA256']:
                raise ValueError('完整传奇挑战表与固定原始字段不符')
            tables.append({'name': name, 'record': {k: v for k, v in record.items() if k != 'extractedFile'},
                           'tableFullyConsumed': True, 'rowCount': len(rows),
                           'tableSemanticSHA256': digest, 'rows': rows})
    result = {'clientExecutableStarted': False, 'clientModified': False, 'clientAccepted': False,
              'jsonInstalled': False, 'scriptsExecuted': False, 'completeGameplay': False,
              'clientFiles': source['files'], 'indexSHA256': source['indexSHA256'],
              'verifiedChunks': source['verifiedChunks'], 'tables': tables}
    content = (json.dumps(result, ensure_ascii=False, indent=2) + '\n').encode('utf-8')
    if output.exists():
        if not output.is_file() or output.read_bytes() != content:
            raise FileExistsError(f'拒绝覆盖已有不同内容的报告: {output}')
    else:
        output.parent.mkdir(parents=True, exist_ok=True)
        with output.open('xb') as stream:
            stream.write(content)
    return result


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--client-dir', type=Path, required=True)
    parser.add_argument('--output', type=Path, default=Path('build/client46-local-legend-tables.json'))
    result = inspect(parser.parse_args())
    print(json.dumps({'tables': [(t['name'], t['rowCount']) for t in result['tables']],
                      'clientAccepted': False, 'jsonInstalled': False}))
