"""只读解析固定国服 4.6 的九条回响角色配置；未知字段/模式停止。

报告保留缺省字段、非有限浮点位和定点整数，不生成可安装 JSON。
不执行 DLL、不启动游戏、不写入客户端、运营资源、配置或数据库。
"""
import argparse
import hashlib
import importlib.util
import json
import math
import mmap
from pathlib import Path
import struct
import tempfile
from types import SimpleNamespace

import pefile

ROOT = Path(__file__).resolve().parents[1]
PROFILE = ROOT / 'src/test/resources/fixtures/rogue-resonance-character-4.6.json'


def load_profile():
    return json.loads(PROFILE.read_text('utf-8'))


class Reader:
    """仅支持已匹配原生函数、且九条记录实际使用的字段和格式。"""
    def __init__(self, raw, profile=None):
        if not 1 <= len(raw) <= 1024 * 1024:
            raise ValueError('记录大小超出边界')
        self.raw, self.offset = raw, 0
        self.profile = profile or load_profile()
        self.nodes, self.depth, self.trace = 0, 0, []
        self.enums = {t['name']: {f['constant']['value']: f['name'] for f in t['fields']
                                 if 'constant' in f and 'value' in f['constant']}
                      for t in self.profile['enumTypes']}

    def take(self, size):
        if not 0 <= size <= len(self.raw) - self.offset:
            raise ValueError('记录截断: ' + str(self.offset))
        result = self.raw[self.offset:self.offset + size]
        self.offset += size
        return result

    def unsigned(self, bits=64):
        value = 0
        for index in range(10):
            byte = self.take(1)[0]
            if index == 9 and byte > 1:
                raise ValueError('无符号 varint 溢出')
            value |= (byte & 127) << (7 * index)
            if byte < 128:
                if index and byte == 0:
                    raise ValueError('拒绝非规范 varint')
                if value >= 1 << bits:
                    raise ValueError('整数超出字段位数')
                return value
        raise ValueError('无符号 varint 未终止')

    def signed(self, bits=64):
        value = self.unsigned(bits)
        return (value >> 1) ^ -(value & 1)

    def string(self):
        length = self.unsigned(32)
        if length > 16384:
            raise ValueError('字符串过长')
        return self.take(length).decode('utf-8', errors='strict')

    def object(self, name):
        if name not in self.profile['schemas']:
            raise ValueError('尚未匹配的对象类型: ' + name)
        self.depth += 1
        if self.depth > 32:
            raise ValueError('对象递归过深')
        try:
            schema = self.profile['schemas'][name]
            fields = schema['fields']
            start = self.offset
            mask = sum(self.unsigned() << shift for shift in range(0, len(fields), 64))
            if mask >> len(fields):
                raise ValueError('未知对象 mask 位: ' + name)
            result = {'$presentMask': hex(mask)}
            for index, field in enumerate(fields):
                if mask & (1 << index):
                    if field['name'] not in schema['allowedFields']:
                        raise ValueError('未匹配的序列化字段: ' + name + '.' + field['name'])
                    self.trace.append({'type': name, 'objectOffset': start,
                                       'field': field['name'], 'valueOffset': self.offset})
                    result[field['name']] = self.read(field['nativeType'])
            return result
        finally:
            self.depth -= 1

    def dynamic_values(self):
        packed = self.unsigned(32)
        if packed >> 16:
            raise ValueError('未匹配的 DynamicValues 整数表')
        count = packed & 65535
        if count > 512:
            raise ValueError('DynamicValues 记录过多')
        floats, keys = [], set()
        for _ in range(count):
            key = self.signed(32)
            if key in keys:
                raise ValueError('重复的 DynamicValues key')
            keys.add(key)
            if self.take(1)[0] != 0:
                raise ValueError('未匹配的 DynamicValues 表达式')
            fixed = self.signed()
            if self.take(1)[0] != 0:
                raise ValueError('未匹配的 DynamicValues 范围')
            if self.take(1)[0] != 0:
                raise ValueError('未匹配的 DynamicValues ReadInfo 模式')
            floats.append({'key': key, 'fixedSignedRaw': fixed, 'rawReadType': 0})
        return {'floats': floats}

    def read(self, native_type):
        self.nodes += 1
        if self.nodes > 4096:
            raise ValueError('解析节点过多')
        kind, name = native_type['kind'], native_type.get('name')
        if kind == 2:
            value = self.take(1)[0]
            if value not in (0, 1):
                raise ValueError('非法布尔值')
            return bool(value)
        if kind in (6, 8, 10):
            return self.signed({6: 16, 8: 32, 10: 64}[kind])
        if kind in (7, 9, 11):
            return self.unsigned({7: 16, 9: 32, 11: 64}[kind])
        if kind == 14:
            return self.string()
        if kind == 12:
            raw = self.take(4)
            value = struct.unpack('<f', raw)[0]
            return {'floatBits': raw.hex(), **({'value': value} if math.isfinite(value)
                      else {'nonFinite': 'NaN' if math.isnan(value)
                            else 'Infinity' if value > 0 else '-Infinity'})}
        if kind == 29:
            size = self.signed(32)
            if not -1 <= size <= 512:
                raise ValueError('数组长度超出边界')
            return None if size == -1 else [self.read(native_type['elementType']) for _ in range(size)]
        if name == 'MVector3':
            return {axis: self.read({'kind': 12}) for axis in 'xyz'}
        if name == 'FixPoint':
            return {'fixedSignedRaw': self.signed()}
        if name == 'DynamicFloat':
            if self.take(1)[0] != 0:
                raise ValueError('未匹配的 DynamicFloat 模式')
            return {'fixedSignedRaw': self.signed()}
        if name == 'ENEIHJJPNPI':
            return self.dynamic_values()
        if name == 'ComplexSkillAIFactorGroupWeight':
            if self.unsigned(32) != 0:
                raise ValueError('未匹配的 AI weight 类型标记')
            return self.object(name)
        if name in self.profile['schemas']:
            return self.object(name)
        if name in self.enums:
            raw = self.signed(32)
            if raw not in self.enums[name]:
                raise ValueError('未知枚举常量: ' + name)
            return {'enumType': name, 'raw': raw, 'name': self.enums[name][raw]}
        raise ValueError('尚未匹配的字段类型: ' + str(native_type))

    def character(self):
        if self.unsigned(32) != self.profile['rootFactoryTag']:
            raise ValueError('未匹配的 CharacterConfig 类型标记')
        result = self.object('BattleEventConfig')
        if self.offset != len(self.raw):
            raise ValueError('记录未完整消费')
        return result


def verify_native(client, profile):
    for name, relative in [('GameAssembly.dll', 'GameAssembly.dll'),
                           ('global-metadata.dat', 'StarRail_Data/il2cpp_data/Metadata/global-metadata.dat')]:
        with (client / relative).open('rb') as stream:
            if hashlib.file_digest(stream, 'sha256').hexdigest() != profile['nativeFiles'][name]:
                raise ValueError(name + ': 文件哈希改变，拒绝旧字段布局')
    pe = pefile.PE(str(client / 'GameAssembly.dll'), fast_load=True)
    directory = pe.OPTIONAL_HEADER.DATA_DIRECTORY[pefile.DIRECTORY_ENTRY['IMAGE_DIRECTORY_ENTRY_EXCEPTION']]
    if directory.Size % 12:
        raise ValueError('非法 PE 方法范围表')
    bounds = {begin: (end, unwind) for begin, end, unwind in struct.iter_unpack(
        '<III', pe.get_data(directory.VirtualAddress, directory.Size)) if begin}
    with (client / 'GameAssembly.dll').open('rb') as stream, mmap.mmap(
            stream.fileno(), 0, access=mmap.ACCESS_READ) as raw:
        for method in profile['nativeMethods']:
            begin, end = int(method['rva'], 16), int(method['endRva'], 16)
            if bounds.get(begin) != (end, int(method['unwindRva'], 16)):
                raise ValueError('完整原生方法 PE 范围不符')
            section = pe.get_section_by_rva(begin)
            if not section or begin - section.VirtualAddress + end - begin > section.SizeOfRawData:
                raise ValueError('完整原生方法没有文件范围')
            offset = pe.get_offset_from_rva(begin)
            actual = raw[offset:offset + end - begin]
            if actual != bytes.fromhex(method['bytesHex']) or hashlib.sha256(actual).hexdigest() != method['sha256']:
                raise ValueError('完整原生方法字节不符')
    return len(profile['nativeMethods']), profile['nativeBytes']


def analyze(args):
    client = args.client_dir.resolve()
    spec = importlib.util.spec_from_file_location('resonance_design', Path(__file__).with_name('read-client-design-data.py'))
    design = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(design)
    output = design.outside_client(args.output, client)
    # Refuse any operator resource/config/database target: evidence belongs under build/.
    if ROOT / 'build' not in output.parents:
        raise ValueError('只接受工程 build/ 下的证据报告输出')
    profile = load_profile()
    methods, method_bytes = verify_native(client, profile)
    (ROOT / 'build').mkdir(exist_ok=True)
    with tempfile.TemporaryDirectory(prefix='resonance-character-', dir=ROOT / 'build') as temporary:
        work = Path(temporary)
        extraction = design.extract(SimpleNamespace(client_dir=client, paths_file=None,
            paths=[r['sourcePath'] for r in profile['records']], extract_dir=work / 'raw', output=work / 'index.json'))
        results = []
        for expected, actual in zip(profile['records'], extraction['records'], strict=True):
            if actual['status'] != 'read' or any(actual[k] != v for k, v in expected.items()):
                raise ValueError('原始记录身份/哈希不符')
            reader = Reader(Path(actual['extractedFile']).read_bytes(), profile)
            decoded = reader.character()
            results.append({**expected, 'fullyConsumed': True, 'decoded': decoded, 'trace': reader.trace})
    report = {'passed': True, 'recordCount': len(results), 'recordBytes': sum(r['length'] for r in results),
              'matchedNativeMethods': methods, 'matchedNativeBytes': method_bytes,
              'indexSHA256': extraction['indexSHA256'], 'indexEntryCount': extraction['indexEntryCount'],
              'nativeFiles': profile['nativeFiles'], 'records': results,
              'unknownFormatsRejected': True, 'defaultsNotMaterialized': True,
              'jsonInstalled': False, 'allResonanceAbilitiesImplemented': False,
              'clientExecutableStarted': False, 'clientModified': False, 'clientGameplayVerified': False}
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(report, ensure_ascii=False, indent=2, allow_nan=False) + '\n', 'utf-8')
    return report


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--client-dir', type=Path, required=True)
    parser.add_argument('--output', type=Path, default=ROOT / 'build/rogue-resonance-character-evidence.json')
    report = analyze(parser.parse_args())
    print(json.dumps({k: report[k] for k in ['passed', 'recordCount', 'recordBytes', 'matchedNativeMethods',
        'matchedNativeBytes', 'jsonInstalled', 'clientGameplayVerified']}, ensure_ascii=False))
