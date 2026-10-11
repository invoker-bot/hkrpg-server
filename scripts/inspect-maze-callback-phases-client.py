"""只读复核4.6回调容器、投射物声明、原生事件请求发送和字段11序列化。"""
import argparse
import hashlib
import importlib.util
import json
from pathlib import Path
from types import SimpleNamespace

ROOT = next(parent for parent in Path(__file__).resolve().parents
            if (parent / 'scripts/analyze-client-46.py').is_file())


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--client-dir', type=Path, required=True)
    parser.add_argument('--resources', type=Path, default=ROOT / 'resources/4.6')
    parser.add_argument('--output-dir', type=Path, default=ROOT / 'build/maze-callback-phases-client')
    args = parser.parse_args()
    output = args.output_dir.resolve()
    if not output.is_relative_to(ROOT / 'build'):
        raise ValueError('输出仅限本项目build目录')
    profile = json.loads((Path(__file__).resolve().parent /
                          'fixtures/maze-callback-phases-native-4.6.json').read_text('utf-8'))
    descriptor = ROOT / 'src/main/resources/protocol/4.6/descriptor.pb'
    if hashlib.sha256(descriptor.read_bytes()).hexdigest() != profile['descriptorSHA256']:
        raise ValueError('冻结4.6描述符变化，需重新核对字段映射')
    spec = importlib.util.spec_from_file_location('maze_phases_native', ROOT / 'scripts/analyze-client-46.py')
    native = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(native)
    report = native.extract(SimpleNamespace(
        client_dir=args.client_dir, types=list(profile['types']), fields=True, field_offsets=True,
        inheritance=False, type_contains=[], method_indices=[value['index'] for value in profile['methods']],
        method_rvas=[], type_slots=[int(value['slot'], 0) for value in profile['typeSlots']],
        method_slots=[], string_slots=[], method_names=[], method_name_contains=[], disassemble=4000))
    if report['files'] != profile['clientFiles'] or report['unmatchedTypes']:
        raise ValueError('客户端指纹或类型未匹配')
    checks = 0
    types = {value['name']: value for value in report['methods']}
    for name, expected in profile['types'].items():
        actual = types[name]
        if actual['typeIndex'] != expected['typeIndex']:
            raise ValueError('类型身份变化: ' + name)
        fields = {field['name']: field for field in actual['fields']}
        for name_field, constant in expected['constants'].items():
            if fields[name_field].get('constant', {}).get('value') != constant:
                raise ValueError('原生枚举/字段号变化: ' + name + '.' + name_field)
            checks += 1
        for name_field, layout in expected['fields'].items():
            field = fields[name_field]
            if field.get('isStatic') or field.get('instanceOffset') != layout['offset']:
                raise ValueError('独立回调或请求实例布局变化: ' + name + '.' + name_field)
            if field['nativeType']['kind'] != layout['kind']:
                raise ValueError('原生字段类型变化: ' + name + '.' + name_field)
            if 'typeIndex' in layout and field['nativeType'].get('typeIndex') != layout['typeIndex']:
                raise ValueError('投射物声明关联类型变化: ' + name + '.' + name_field)
            checks += 1
    if {value['slot']: value['typeIndex'] for value in report['typeInfoSlots']} != {
        value['slot']: value['typeIndex'] for value in profile['typeSlots']
    }:
        raise ValueError('原生施放请求类型槽变化')
    checks += 1
    methods = {value['index']: value for value in report['selectedMethods']}
    for expected in profile['methods']:
        actual = methods[expected['index']]
        if any(actual[key] != expected[key] for key in ('name', 'rva', 'typeIndex')):
            raise ValueError('原生发送/序列化方法身份变化: ' + str(expected['index']))
        for instruction in expected['instructions']:
            if instruction not in actual['instructions']:
                raise ValueError('发送/序列化/回调分离证据变化: ' + instruction)
            checks += 1
    source_checks = 0
    source = json.loads((ROOT / 'src/test/resources/fixtures/maze-callback-phases-4.6.json').read_text('utf-8'))
    if source['sourceCommit'] != profile['sourceCommit']:
        raise ValueError('来源提交未匹配')
    for record in source['records']:
        for name in ('avatar', 'ability'):
            path = args.resources / record[name + 'File']
            if hashlib.sha256(path.read_bytes()).hexdigest() != record[name + 'FileSHA256']:
                raise ValueError('原始角色/技能来源文件变化: ' + str(path))
            content = json.loads(path.read_text('utf-8'))
            if name == 'ability':
                content = content['AbilityList']
            if record[name] not in content:
                raise ValueError('原始角色/技能记录变化: ' + name)
            source_checks += 2
    output.mkdir(parents=True, exist_ok=True)
    (output / 'native.json').write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n', 'utf-8')
    result = dict(passed=True, nativeChecks=checks, sourceChecks=source_checks,
                  types=len(types), methods=len(methods), sourceCommit=source['sourceCommit'],
                  clientExecutableStarted=False, clientModified=False, clientGameplayVerified=False,
                  nativeEventSchedulingVerified=False, multiPacketTechniqueMPVerified=False,
                  descriptorModified=False)
    (output / 'verification.json').write_text(json.dumps(result, indent=2) + '\n', 'utf-8')
    print(json.dumps(result))


if __name__ == '__main__':
    main()
