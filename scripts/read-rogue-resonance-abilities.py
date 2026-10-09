"""只读恢复固定国服 4.6 的九条命途共鸣能力配置。

保留字段缺省、定点整数和后缀表达式字节；不求值、不执行、不安装 JSON。
字段证据来自完整 PE 方法、注册表、元数据布局和原始 Design 记录。
未知字段、类型、模式及剩余字节均须拒绝，完整读取不代表客户端玩法验收。
"""
import argparse
import hashlib
import importlib.util
import json
from pathlib import Path
import tempfile
from types import SimpleNamespace

ROOT = Path(__file__).resolve().parents[1]
PROFILE = ROOT / 'src/test/resources/fixtures/rogue-resonance-abilities-4.6.json'
PROFILE_SHA256 = 'aecd7c770a4c85adfbdb927a1603210f7077dda96e6ae07f7c79745352b4b13e'


def sibling(name, filename):
    spec = importlib.util.spec_from_file_location(name, Path(__file__).with_name(filename))
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


graphs = sibling('resonance_ability_graphs', 'decode-client-level-graphs.py')
characters = sibling('resonance_ability_character', 'read-rogue-resonance-character.py')
postfix = sibling('resonance_ability_postfix', 'read-rogue-resonance-postfix.py')


def load_profile():
    raw = PROFILE.read_bytes().replace(b'\r\n', b'\n')
    if hashlib.sha256(raw).hexdigest() != PROFILE_SHA256:
        raise ValueError('能力配置的固定原生/资源证据已改变，需要重新核对')
    return json.loads(raw)


class Reader(graphs.Reader):
    """有限格式读取器；只接受九条原始记录中已核对的字段。"""
    def __init__(self, raw, profile=None):
        super().__init__(raw, profile if profile is not None else load_profile())
        self.trace, self.field_stack = [], []
        self.dynamic_expressions = 0

    def variant(self, family):
        start, tag = self.pos, self.uvar()
        binding = self.profile['bindings'][family].get(str(tag))
        if binding is None:
            raise ValueError(f'未知 {family} 分支 {tag}，位置 {start}')
        result = self.obj(binding['name'])
        result['$variantId'] = tag
        return result

    def dictionary(self, value_type):
        size = self.integer(32, True)
        if not 0 <= size <= 512:
            raise ValueError('字典条目数超出已核对的边界')
        entries, keys = [], set()
        for _ in range(size):
            key = self.read({'kind': 14})
            if key in keys:
                raise ValueError('字典存在重复 key')
            keys.add(key)
            entries.append([key, self.read(value_type)])
        return {'entries': entries}

    def _read(self, description):
        kind, name = description['kind'], description.get('name')
        if kind == 21:
            context = '.'.join(self.field_stack[-1]) if self.field_stack else ''
            if context in self.profile['dynamicDictionaries']:
                return self.dictionary({'kind': 18, 'name': 'DynamicFloat'})
            if context in self.profile['modifierDictionaries']:
                return self.dictionary({'kind': 18, 'name': 'TurnBasedModifierConfig'})
            raise ValueError('尚未核对的泛型字典字段: ' + context)
        if name == 'DynamicFloat':
            mode = self.take(1)[0]
            if mode == 0:
                return {'mode': 0, 'fixedSignedRaw': self.integer(64, True)}
            if mode != 1:
                raise ValueError('尚未核对的 DynamicFloat 模式')
            expression = self.take(self.take(1)[0]).hex()
            constants = [self.integer(64, True) for _ in range(self.take(1)[0])]
            variables = [self.integer(32, True) for _ in range(self.take(1)[0])]
            postfix.decode(bytes.fromhex(expression), constants, variables)
            self.dynamic_expressions += 1
            return {'mode': 1, 'rawPostfixBytes': expression,
                    'fixedSignedConstants': constants, 'variableKeys': variables,
                    'expressionEvaluated': False}
        if name == 'ENEIHJJPNPI':
            # This shared constant-table routine has no object/enum payload.
            reader = characters.Reader(self.data[self.pos:], {'enumTypes': []})
            value = reader.dynamic_values()
            self.pos += reader.offset
            return value
        if name == 'FixPoint':
            return {'fixedSignedRaw': self.integer(64, True)}
        if name == 'JsonEnum':
            return {'EnumIndex': self.integer(32, True), 'Value': self.integer(32, True)}
        if name == 'AttackData':
            if self.integer(32, True) != 0:
                raise ValueError('尚未核对的 AttackData 工厂类型标记')
            return self.obj(name)
        if name == 'TaskConfig':
            start, tag = self.pos, self.uvar()
            binding = self.profile['bindings']['task'].get(str(tag))
            if binding is None:
                raise ValueError(f'未知动作类型 {tag}，位置 {start}')
            result = self.obj(binding['name'])
            result['taskId'] = tag
            return result
        families = {'PredicateConfig': 'predicate', 'TargetEvaluator': 'target',
                    'TargetSeqOperation': 'sequence'}
        if name in families:
            return self.variant(families[name])
        if kind == 17 and str(description.get('typeIndex')) in self.profile['enums']:
            enum = self.profile['enums'][str(description['typeIndex'])]
            value = self.read(enum['underlyingType'])
            if value not in enum['allowedRawValues']:
                raise ValueError('尚未匹配的枚举值: ' + enum['name'])
            return value
        if kind in (17, 18) and name in self.profile['schemas']:
            schema = self.profile['schemas'][name]
            if 'typeIndex' in description and schema['typeIndex'] != description['typeIndex']:
                raise ValueError('同名对象的原生类型身份不符')
            return self.obj(name)
        if kind in (17, 18) and name not in ('MVector3', 'TextID'):
            raise ValueError('尚未核对的对象类型: ' + str(name))
        return super()._read(description)

    def obj(self, name):
        schema = self.profile['schemas'].get(name)
        if schema is None:
            raise ValueError('尚未核对的对象类型: ' + name)
        start = self.pos
        mask = sum(self.uvar() << (64 * i) for i in range(schema['maskWords']))
        fields = schema['fields']
        if mask >> len(fields):
            raise ValueError('未知字段掩码: ' + name)
        result = {'$type': name, '$mask': hex(mask), '$offset': start}
        for ordinal, field in enumerate(fields):
            if not mask & (1 << ordinal):
                continue
            if field['name'] not in schema['allowedFields']:
                raise ValueError('未匹配的序列化字段: ' + name + '.' + field['name'])
            self.trace.append({'type': name, 'typeIndex': schema['typeIndex'],
                               'objectOffset': start, 'field': field['name'],
                               'valueOffset': self.pos})
            self.field_stack.append((name, field['name']))
            try:
                result[field['name']] = self.read(field['type'])
            finally:
                self.field_stack.pop()
        return result

    def ability(self):
        result = self.read({'kind': 18, 'name': self.profile['rootType']})
        if self.pos != len(self.data):
            raise ValueError('能力记录未完整读取')
        return result


def analyze(args):
    client = args.client_dir.resolve()
    design = sibling('resonance_ability_design', 'read-client-design-data.py')
    output = design.outside_client(args.output, client)
    if ROOT / 'build' not in output.parents:
        raise ValueError('只接受工程 build/ 下的只读证据输出')
    profile = load_profile()
    methods, native_bytes = characters.verify_native(client, profile)
    postfix_methods, postfix_bytes = postfix.verify_native(client)
    (ROOT / 'build').mkdir(exist_ok=True)
    with tempfile.TemporaryDirectory(prefix='resonance-ability-', dir=ROOT / 'build') as directory:
        work = Path(directory)
        source = design.extract(SimpleNamespace(client_dir=client, paths_file=None,
            paths=[r['sourcePath'] for r in profile['records']], extract_dir=work / 'raw',
            output=work / 'index.json'))
        if source['indexSHA256'] != profile['indexSHA256']:
            raise ValueError('资源索引与字段证据不匹配')
        records, expressions = [], 0
        for expected, actual in zip(profile['records'], source['records'], strict=True):
            if actual['status'] != 'read' or any(actual[k] != v for k, v in expected.items()):
                raise ValueError('原始能力记录身份/长度/哈希不符')
            reader = Reader(Path(actual['extractedFile']).read_bytes(), profile)
            value = reader.ability()
            expressions += reader.dynamic_expressions
            records.append({**expected, 'fullyConsumed': True, 'decoded': value,
                            'fieldOccurrences': len(reader.trace), 'trace': reader.trace})
    report = {'passed': True, 'recordCount': len(records),
              'recordBytes': sum(r['length'] for r in records),
              'matchedNativeMethods': methods, 'matchedNativeBytes': native_bytes,
              'postfixExpressionsPreserved': expressions,
              'postfixProgramsValidated': expressions,
              'postfixNativeMethods': postfix_methods, 'postfixNativeBytes': postfix_bytes,
              'classes': len(profile['schemas']), 'enumTypes': len(profile['enums']),
              'bindings': {k: len(v) for k, v in profile['bindings'].items()},
              'nativeFiles': profile['nativeFiles'], 'indexSHA256': source['indexSHA256'],
              'indexEntryCount': source['indexEntryCount'], 'records': records,
              'unknownFormatsRejected': True, 'defaultsNotMaterialized': True,
              'expressionEvaluationVerified': False, 'jsonInstalled': False,
              'clientExecutableStarted': False, 'clientModified': False,
              'allResonanceAbilitiesImplemented': False, 'clientGameplayVerified': False}
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(report, ensure_ascii=False, indent=2, allow_nan=False) + '\n', 'utf-8')
    return report


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--client-dir', type=Path, required=True)
    parser.add_argument('--output', type=Path, default=ROOT / 'build/rogue-resonance-ability-evidence.json')
    report = analyze(parser.parse_args())
    print(json.dumps({k: report[k] for k in ['passed', 'recordCount', 'recordBytes',
        'matchedNativeMethods', 'matchedNativeBytes', 'postfixExpressionsPreserved',
        'jsonInstalled', 'clientGameplayVerified']}, ensure_ascii=False))
