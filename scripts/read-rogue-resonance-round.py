"""只读核对固定4.6的round回调、RoundToInt与ONE初始化；不执行能力任务。"""
import argparse
from functools import lru_cache
import hashlib
import importlib.util
import json
from pathlib import Path
import struct
from types import SimpleNamespace

import pefile

ROOT = Path(__file__).resolve().parents[1]
PROFILE = ROOT / 'src/test/resources/fixtures/rogue-resonance-round-4.6.json'
PROFILE_SHA256 = 'ec8c785b73621315ee3b1896926ca09e3ef489a954b16adfaa9dcfd9acf3ad24'
spec = importlib.util.spec_from_file_location('round_source_functions', Path(__file__).with_name('read-rogue-resonance-functions.py'))
functions = importlib.util.module_from_spec(spec)
spec.loader.exec_module(functions)


@lru_cache(maxsize=1)
def load_profile():
    raw = PROFILE.read_bytes().replace(b'\r\n', b'\n')
    if hashlib.sha256(raw).hexdigest() != PROFILE_SHA256:
        raise ValueError('round回调的固定原生证据已改变')
    return json.loads(raw)


def round_to_int(raw):
    if type(raw) is not int or not -(1 << 63) <= raw < 1 << 63 or raw & 1:
        raise ValueError('round仅开放有符号64位标准定点数')
    integral, fraction = divmod(raw, 1 << 33)
    bound = load_profile()['standardIntegerMagnitudeExclusive']
    if abs(integral) >= bound:
        raise ValueError('round输入超过有限标准范围')
    if fraction > 1 << 32 or fraction == 1 << 32 and integral & 1:
        integral += 1
    if abs(integral) >= bound:
        raise ValueError('round结果需要未开放的扩展编码')
    return integral


def round_raw(raw):
    return round_to_int(raw) * (1 << 33)


def verify_native(client):
    profile = load_profile()
    functions.verify_native(client)
    characters = functions.sibling('round_native_characters', 'read-rogue-resonance-character.py')
    counts = characters.verify_native(client, profile)
    analyzer = functions.sibling('round_native_metadata', 'analyze-client-46.py')
    identities = analyzer.extract(SimpleNamespace(client_dir=client, types=[], type_contains=[],
        method_indices=[74381, 68536, 68417], method_rvas=[], method_name_contains=[],
        method_slots=[], string_slots=[], type_slots=[], fields=False, inheritance=False,
        field_offsets=False, disassemble=0, method_names=[]))
    expected = [{k: m[k] for k in ['index', 'name', 'rva', 'typeIndex', 'typeName']} for m in profile['nativeMethods']]
    if identities['selectedMethods'] != expected:
        raise ValueError('round和RoundToInt的完整原生方法身份不符')
    one = profile['oneInitializer']
    paired = functions.load_profile()['floorConstants']['minimumInitializer']
    if (one['pairedMinimumInitializer'] != paired or int(one['slotRva'], 16) != int(paired['slotRva'], 16) + 8
            or int(one['sourceSlotRva'], 16) != int(paired['sourceSlotRva'], 16) + 8
            or one['sourceBytesHex'] != paired['sourceBytesHex'][16:] or one['raw'] != 1 << 33):
        raise ValueError('round增量ONE没有匹配完整cctor的成对初始化')
    pe = pefile.PE(str(client / 'GameAssembly.dll'), fast_load=True)
    with (client / 'GameAssembly.dll').open('rb') as stream:
        def read(address, length):
            section = pe.get_section_by_rva(address)
            if section is None or address - section.VirtualAddress + length > section.SizeOfRawData:
                raise ValueError('round证据没有对应的原生文件范围')
            stream.seek(pe.get_offset_from_rva(address))
            raw = stream.read(length)
            if len(raw) != length:
                raise ValueError('round原生证据截断')
            return raw
        raw = read(int(one['sourceSlotRva'], 16), one['sourceLength'])
        if raw.hex() != one['sourceBytesHex'] or struct.unpack('<q', raw)[0] != one['raw']:
            raise ValueError('round增量ONE的原生初始化数据不符')
        for fragment in profile['standardAdditionFragments']:
            raw = read(int(fragment['rva'], 16), fragment['length'])
            if raw.hex() != fragment['bytesHex'] or hashlib.sha256(raw).hexdigest() != fragment['sha256']:
                raise ValueError('round标准加法分支指令片段不符')
    return counts


def analyze(args):
    client = args.client_dir.resolve()
    design = functions.sibling('round_output_design', 'read-client-design-data.py')
    output = design.outside_client(args.output, client)
    if ROOT / 'build' not in output.parents:
        raise ValueError('只接受工程build目录下的round静态证据')
    methods, size = verify_native(client)
    profile = load_profile()
    report = {'passed': True, 'matchedNativeMethods': methods, 'matchedNativeBytes': size,
        'functionRegistration': profile['registration'], 'oneInitializer': profile['oneInitializer'],
        'standardAdditionInstructionBytes': sum(f['length'] for f in profile['standardAdditionFragments']),
        'standardAdditionCompleteNativeMethod': False, 'standardArithmeticOnly': True,
        'roundStandaloneCallbackImplemented': True, 'roundToIntStandaloneImplemented': True,
        'originalAbilityExpressionsRead': False, 'originalVariableContextResolved': False,
        'roundActionBindingVerified': False, 'abilityExecutionVerified': False,
        'jsonInstalled': False, 'clientExecutableStarted': False, 'clientModified': False,
        'clientGameplayVerified': False, 'allResonanceAbilitiesImplemented': False}
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n', 'utf-8')
    return report


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--client-dir', type=Path, required=True)
    parser.add_argument('--output', type=Path, default=ROOT / 'build/rogue-resonance-round-evidence.json')
    report = analyze(parser.parse_args())
    print(json.dumps({k: report[k] for k in ['passed', 'matchedNativeMethods', 'matchedNativeBytes',
        'standardAdditionInstructionBytes', 'roundStandaloneCallbackImplemented',
        'roundToIntStandaloneImplemented', 'roundActionBindingVerified', 'clientGameplayVerified']}))
