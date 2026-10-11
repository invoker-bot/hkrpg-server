"""只读核对 4.6 金色短矛完整脚本、地图覆盖后的四次交互和道具类型，不启动客户端。"""
import argparse
import importlib.util
import json
from pathlib import Path
from types import SimpleNamespace

ROOT = Path(__file__).resolve().parents[1]
GROUP = 'Config/Level/GroupGraph/F20411001/Group_F20411001_G{}.json'
SHIELD = 'Config/Level/Props/Chap04/InitLevelGraph_Prop_Chap04_ShieldCoin_01.json'
SPEAR = 'Config/Level/Props/Chap04/InitLevelGraph_Prop_Chap04_GoldenSpear_01.json'
OVERRIDE = 'Config/Level/Props/Chap04/InitLevelGraph_Prop_Chap04_GoldenSpear_04.json'
EXPECTED = {
    GROUP.format(273): (170, '1a78ce6c9afef7cebdafb527589945b5c4123ac699c09612613eb2f781a53ebf'),
    GROUP.format(274): (50, '8354264be3c929dc2f0924b277923b6d8e6031695bb2dbc10bfd64f1cff2aaf2'),
    SHIELD: (309, '586787ef83f8cee11a5deefde52ea2f197d3d4549cb8baadc7658a92ec4cab07'),
    SPEAR: (2693, '1294707d03111fc9a0ac4ba127407421bc78c4893f8aa0fcf756c31d484885ec'),
    OVERRIDE: (1946, 'cb5fbd5a91dd3d1e0ab9c2310c291e0f7fdbd6a8aa4d5adb2f6a6d9d88f45d36'),
}
BASE_CONFIGS = [1010, 101101, 1010102, 1020103, 1030104, 1040105, 1050002]
CONFIGS = [1010, 101101, 1010102, 1020002]


def module(name, file):
    spec = importlib.util.spec_from_file_location(name, ROOT / 'scripts' / file)
    value = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(value)
    return value


def walk(value):
    if isinstance(value, dict):
        yield value
        for child in value.values():
            yield from walk(child)
    elif isinstance(value, list):
        for child in value:
            yield from walk(child)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--client-dir', type=Path, required=True)
    parser.add_argument('--output-dir', type=Path, default=ROOT / 'build/golden-spear-client')
    args = parser.parse_args()
    output = args.output_dir.resolve()
    if not output.is_relative_to(ROOT / 'build'):
        raise ValueError('输出只能写入本项目 build 目录')
    profile = json.loads((ROOT / 'scripts/fixtures/golden-spear-reader-4.6.json').read_text('utf-8'))
    if profile['runtimeInstallSupported'] or profile['clientAccepted'] or profile['scriptsExecuted']:
        raise ValueError('只读剖面的边界不成立')
    design = module('golden_design', 'read-client-design-data.py')
    records = design.extract(SimpleNamespace(client_dir=args.client_dir, paths=list(EXPECTED),
        paths_file=None, extract_dir=output / 'records', output=output / 'records.json'))
    if records['files'] != profile['clientFiles'] or records['indexSHA256'] != profile['indexSHA256']:
        raise ValueError('客户端或设计索引不匹配')
    graphs = module('golden_graphs', 'recover-client-mission-graphs.py')
    expressions = module('golden_expr', 'read-rogue-resonance-abilities.py')

    class Reader(graphs.graphs.Reader):
        def _read(self, description):
            name = description.get('name')
            if name == 'DynamicFloat' and self.data[self.pos] != 0:
                start = self.pos
                delegated = expressions.Reader(self.data[self.pos:], self.profile)
                value = delegated.read(description)
                self.pos += delegated.pos
                value.update({'$type': name, '$offset': start})
                return value
            table = {'DynamicJsonConfig': 'dynamicJsonConfigs',
                     'PropCurveMoveStartMode': 'curveStartModes'}.get(name)
            if table:
                start = self.pos
                tag = self.uvar()
                selected = self.profile[table].get(str(tag))
                if not selected:
                    raise ValueError('未核对的分支: ' + name + '/' + str(tag))
                value = self.obj(selected)
                value.update({'$variantId': tag, '$offset': start})
                return value
            return super()._read(description)

    decoded = {}
    for record in records['records']:
        path = record['sourcePath']
        raw = Path(record['extractedFile']).read_bytes()
        if record['status'] != 'read' or (len(raw), record['sha256']) != EXPECTED[path]:
            raise ValueError('原始脚本记录不匹配: ' + path)
        reader = Reader(raw, profile)
        graph = reader.read({'kind': 18, 'name': 'LevelGraphConfig'})
        if reader.pos != len(raw):
            raise ValueError('脚本未完整读取: ' + path)
        decoded[path] = graph
        (output / (Path(path).stem + '.json')).write_text(
            json.dumps(graph, ensure_ascii=False, indent=2) + '\n', 'utf-8')
    native = module('golden_native', 'analyze-client-46.py')
    enums = native.extract(SimpleNamespace(client_dir=args.client_dir, types=['PropType', 'ChestType'],
        fields=True, inheritance=False, field_offsets=False, type_contains=[], method_indices=[],
        method_rvas=[], type_slots=[], method_slots=[], string_slots=[], method_names=[],
        method_name_contains=[], disassemble=0))
    for index, expected in {10462: {'PROP_DYNAMIC_POSITION': 31},
                            10464: {'CHEST_PUZZLE_GOLDEN_SPEAR': 226}}.items():
        matches = [t for t in enums['methods'] if t['typeIndex'] == index]
        if len(matches) != 1:
            raise ValueError('原始枚举类型未匹配')
        values = {f['name']: f.get('constant', {}).get('value') for f in matches[0]['fields']}
        if any(values.get(k) != v for k, v in expected.items()):
            raise ValueError('原始道具或宝箱枚举编号未匹配')
    (output / 'native-enums.json').write_text(json.dumps(enums, indent=2) + '\n', 'utf-8')
    for path, expected in ((SPEAR, BASE_CONFIGS), (OVERRIDE, CONFIGS)):
        requests = [v for v in walk(decoded[path]) if v.get('$type') == 'PropReqInteract']
        actual = []
        for request in requests:
            value = request['InteractID']
            raw = value.get('fixedRawSigned', 0)
            if value.get('$mode') != 0 or raw % (1 << 33):
                raise ValueError('交互编号不是已核对的定值')
            actual.append(raw // (1 << 33))
        if actual != expected:
            raise ValueError('基础或地图覆盖交互次序不匹配')
    source = json.loads((ROOT / 'src/test/resources/fixtures/golden-spear-4.6.json').read_text('utf-8'))
    for group in source['groups'].values():
        spears = [p for p in group['PropList'] if p['PropID'] == 104023]
        if len(spears) != 1 or spears[0]['InitLevelGraph'] != OVERRIDE:
            raise ValueError('源地图使用的覆盖脚本未匹配')
    for group in (273, 274):
        graph = decoded[GROUP.format(group)]
        tasks = [v.get('$type') for v in walk(graph)]
        if graph.get('Type') != 2 or 'LoopWaitBeHit' not in tasks or 'LoopWaitEntityServerEvent' not in tasks:
            raise ValueError('地图组命中监听器未匹配')
    events = decoded[SHIELD].get('EntityEventList', [])
    if not any(v.get('Name') == 'Srv_OnDestruction' and v.get('IsClient') is False for v in events):
        raise ValueError('护盾服务器破坏事件未匹配')
    report = dict(passed=True, fullyConsumedGraphs=5, fullyConsumedBytes=sum(v[0] for v in EXPECTED.values()),
        sourceRequests=actual, baseRequests=BASE_CONFIGS, sourceMapUsesOverride=True, nativePropType=31, nativeChestType=226,
        clientLocalCurveMovesReplayed=False, serverListenerSemanticsFullyRecovered=False,
        clientExecutableStarted=False, clientModified=False, clientGameplayVerified=False)
    (output / 'verification.json').write_text(json.dumps(report, indent=2) + '\n', 'utf-8')
    print(json.dumps(report))


if __name__ == '__main__':
    main()
