"""只读核对固定 4.6 客户端的火炬完成事件和交互编号，不安装资源或启动客户端。"""
import argparse
import hashlib
import importlib.util
import json
from pathlib import Path
from types import SimpleNamespace

ROOT = Path(__file__).resolve().parents[1]
GRAPH = 'Config/Level/Props/Chap04/InitLevelGraph_Prop_Chap04_TrfGoldenGoatMaskProp_01.json'
EXPECTED = {
    GRAPH: '83200ef6a3910ddfe5db1da957e0e396fa98064867e5ce965957feba14368a8a',
    'Config/Gameplays/LittleGame/TimeRelayFlame/Level/Config_TrfLevel_08.json':
        'ac2a9dd082d50a94ffd16e1c6e5bf239d2608b469bff21abb69164b2fc80c136',
    'Config/Gameplays/LittleGame/TimeRelayFlame/Level/Config_TrfLevel_09.json':
        '4e36b2fb2935f076ce9b3af36c46782b02ae7aface806fe33d46409e30995051',
    'Config/Gameplays/LittleGame/TimeRelayFlame/Level/Config_TrfLevel_10.json':
        'd28c0032f8f48a2c32987fd8728030da292c78930fecbcf592bc910f7f927853',
    'Config/Gameplays/LittleGame/TimeRelayFlame/Level/Config_TrfLevel_11.json':
        '21267d50b193fd676e9d4fc1b3213d4440b374f45ac57c9647e600895cb1f689',
}


def module(name, filename):
    spec = importlib.util.spec_from_file_location(name, ROOT / 'scripts' / filename)
    result = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(result)
    return result


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
    parser.add_argument('--output-dir', type=Path, default=ROOT / 'build/time-relay-flame-client')
    args = parser.parse_args()
    output = args.output_dir.resolve()
    if not output.is_relative_to(ROOT / 'build'):
        raise ValueError('核对输出只能写入本项目 build 目录')
    profile = json.loads((ROOT / 'scripts/fixtures/time-relay-flame-reader-4.6.json').read_text('utf-8'))
    if profile['runtimeInstallSupported'] or any(v.get('unverifiedValues') for v in profile['enums'].values()):
        raise ValueError('无效的只读读取剖面')
    design = module('time_flame_design', 'read-client-design-data.py')
    graphs = module('time_flame_graphs', 'recover-client-mission-graphs.py')
    records = design.extract(SimpleNamespace(
        client_dir=args.client_dir, paths=list(EXPECTED), paths_file=None,
        extract_dir=output / 'records', output=output / 'records.json'))
    if records['files'] != profile['clientFiles'] or records['indexSHA256'] != profile['indexSHA256']:
        raise ValueError('客户端或设计索引不匹配')
    for record in records['records']:
        if record['status'] != 'read' or record['sha256'] != EXPECTED[record['sourcePath']]:
            raise ValueError('原始记录不匹配: ' + record['sourcePath'])
    native = module('time_flame_native', 'analyze-client-46.py')
    enum_report = native.extract(SimpleNamespace(
        client_dir=args.client_dir, types=['ChestType', 'AuthoritySide'], fields=True,
        inheritance=False, field_offsets=False, type_contains=[], method_indices=[],
        method_rvas=[], type_slots=[], method_slots=[], string_slots=[], method_names=[],
        method_name_contains=[], disassemble=0))
    expected_enums = {
        10464: {'CHEST_PUZZLE_TIME_RELAY_FLAME': 224,
                'CHEST_PUZZLE_GOLDEN_SPEAR': 226, 'CHEST_PUZZLE_MASCOT': 228},
        24532: {'Client': 0, 'Server': 1},
    }
    for index, expected in expected_enums.items():
        types = [t for t in enum_report['methods'] if t['typeIndex'] == index]
        if len(types) != 1:
            raise ValueError('客户端枚举类型未匹配')
        values = {f['name']: f.get('constant', {}).get('value') for f in types[0]['fields']}
        if any(values.get(key) != value for key, value in expected.items()):
            raise ValueError('客户端谜题分类或权限枚举未匹配')
    (output / 'native-enums.json').write_text(json.dumps(enum_report, indent=2) + '\n', 'utf-8')
    raw = Path(next(r['extractedFile'] for r in records['records'] if r['sourcePath'] == GRAPH)).read_bytes()

    class Reader(graphs.graphs.Reader):
        def _read(self, description):
            table = {'TargetEvaluator': 'targets', 'PredicateConfig': 'predicates'}.get(description.get('name'))
            if table:
                start = self.pos
                tag = self.uvar()
                name = self.profile.get('zeroPayloadVariants', {}).get(table, {}).get(str(tag))
                if name:
                    return {'$type': name, '$variantId': tag, '$noReaderPayload': True, '$offset': start}
                self.pos = start
            return super()._read(description)

    reader = Reader(raw, profile)
    graph = reader.read({'kind': 18, 'name': 'LevelGraphConfig'})
    if len(raw) != 4130 or reader.pos != len(raw):
        raise ValueError('火炬道具脚本未完整读取')
    listeners = [v for v in walk(graph) if v.get('$type') == 'TimeRelayFlameEventListener']
    if len(listeners) != 1 or not any(
        v.get('$type') == 'TriggerEntityEvent'
        and v.get('EventName', {}).get('Value') == 'Clt_TriggerChestUnlock'
        for v in walk(listeners[0].get('Finish'))):
        raise ValueError('缺少原始完成到解锁事件链')
    hooks = []
    for event, config, before, after in [
            ('Clt_TriggerChestUnlock', 111120, 11, 12), ('Clt_TriggerChestUse', 112131, 12, 13)]:
        waits = [v for v in walk(graph) if v.get('$type') == 'WaitEntityEvent'
                 and v.get('EventName', {}).get('Value') == event]
        if len(waits) != 1:
            raise ValueError('缺少或重复交互事件: ' + event)
        requests = [v for v in walk(waits[0]) if v.get('$type') == 'PropReqInteract']
        transitions = [v for v in walk(waits[0]) if v.get('$type') == 'AdvClientChangePropState']
        if len(requests) != 1 or requests[0]['InteractID'].get('$mode') != 0 \
                or requests[0]['InteractID']['fixedRawSigned'] != config * 8589934592 \
                or len(transitions) != 1 or transitions[0]['FromState'] != before \
                or transitions[0]['ToState'] != after:
            raise ValueError('原始完成上报或状态转换不匹配')
        hooks.append(dict(event=event, interactID=config, fromState=before, toState=after,
                          waitOffset=waits[0]['$offset'], requestOffset=requests[0]['$offset']))
    report = dict(passed=True, files=records['files'], indexSHA256=records['indexSHA256'],
        graphSHA256=hashlib.sha256(raw).hexdigest(), graphBytes=len(raw), graphFullyConsumed=True,
        readerProfileSHA256=hashlib.sha256((ROOT / 'scripts/fixtures/time-relay-flame-reader-4.6.json').read_bytes()).hexdigest(),
        hooks=hooks, verifiedNativeEnums=expected_enums,
        puzzleLevelsReadWithoutDecoding=4, rawRecords=records['records'],
        clientLocalFinishReport=True, puzzleMovesReplayed=False, clientExecutableStarted=False,
        clientModified=False, jsonInstalled=False, clientGameplayVerified=False)
    (output / 'verification.json').write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n', 'utf-8')
    print(json.dumps(dict(passed=True, graphBytes=len(raw), report=str(output / 'verification.json'))))


if __name__ == '__main__':
    main()
