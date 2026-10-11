"""只读复核4.6施放回复逐目标结果、线序列化与原生消费者，不启动客户端。"""
import argparse
import hashlib
import importlib.util
import json
from pathlib import Path
from types import SimpleNamespace

ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--client-dir', type=Path, required=True)
    parser.add_argument('--output-dir', type=Path, default=ROOT / 'build/maze-attack-results-client')
    args = parser.parse_args()
    output = args.output_dir.resolve()
    if not output.is_relative_to(ROOT / 'build'):
        raise ValueError('输出仅限本项目build目录')
    profile = json.loads((ROOT / 'scripts/fixtures/maze-attack-results-native-4.6.json').read_text('utf-8'))
    descriptor = ROOT / 'src/main/resources/protocol/4.6/descriptor.pb'
    if hashlib.sha256(descriptor.read_bytes()).hexdigest() != profile['descriptorSHA256']:
        raise ValueError('冻结4.6描述符发生变化，必须重新核对命名字段映射')
    spec = importlib.util.spec_from_file_location('maze_attack_native', ROOT / 'scripts/analyze-client-46.py')
    native = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(native)
    report = native.extract(SimpleNamespace(
        client_dir=args.client_dir, types=list(profile['types']), fields=True, field_offsets=True,
        inheritance=False, type_contains=[], method_indices=[v['index'] for v in profile['methods']],
        method_rvas=[], type_slots=[int(v['slot'], 0) for v in profile['typeSlots']],
        method_slots=[], string_slots=[], method_names=[], method_name_contains=[], disassemble=1500))
    if report['files'] != profile['clientFiles'] or report['unmatchedTypes']:
        raise ValueError('本机客户端指纹或类型未匹配')
    types = {value['name']: value for value in report['methods']}
    checks = 0
    for name, expected in profile['types'].items():
        value = types[name]
        if value['typeIndex'] != expected['typeIndex']:
            raise ValueError('类型索引变化: ' + name)
        fields = {field['name']: field for field in value['fields']}
        for field_name, constant in expected.get('constants', {}).items():
            if fields[field_name].get('constant', {}).get('value') != constant:
                raise ValueError('字段或枚举常量变化: ' + name + '.' + field_name)
            checks += 1
        for field_name, layout in expected.get('fields', {}).items():
            field = fields[field_name]
            if field.get('isStatic') or field.get('instanceOffset') != layout['offset']:
                raise ValueError('实例布局变化: ' + name + '.' + field_name)
            if field['nativeType']['kind'] != layout['kind']:
                raise ValueError('实例类型变化: ' + name + '.' + field_name)
            if 'typeIndex' in layout and field['nativeType'].get('typeIndex') != layout['typeIndex']:
                raise ValueError('实例关联类型变化: ' + name + '.' + field_name)
            checks += 1
    slots = {value['slot']: value['typeIndex'] for value in report['typeInfoSlots']}
    if slots != {value['slot']: value['typeIndex'] for value in profile['typeSlots']}:
        raise ValueError('回复消费者的静态类型槽变化')
    methods = {value['index']: value for value in report['selectedMethods']}
    for expected in profile['methods']:
        value = methods[expected['index']]
        if any(value[key] != expected[key] for key in ('name', 'rva', 'typeIndex')):
            raise ValueError('原生方法身份变化: ' + str(expected['index']))
        for instruction in expected['instructions']:
            if instruction not in value['instructions']:
                raise ValueError('序列化/消费者证据变化: ' + instruction)
            checks += 1
    output.mkdir(parents=True, exist_ok=True)
    (output / 'native.json').write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n', 'utf-8')
    result = dict(passed=True, checks=checks, types=len(types), consumers=3,
                  clientExecutableStarted=False, clientModified=False, clientGameplayVerified=False,
                  descriptorModified=False, directDieSimulateImplemented=False)
    (output / 'verification.json').write_text(json.dumps(result, indent=2) + '\n', 'utf-8')
    print(json.dumps(result))


if __name__ == '__main__':
    main()
