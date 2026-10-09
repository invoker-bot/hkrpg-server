"""只读解析已核对的国服 4.6 IL2CPP 元数据和方法地址，不加载或执行 DLL。

常量由本机 GameAssembly.dll 的初始化、类型/方法读取函数独立反汇编得到。
仅接受指定 SHA256，其他客户端必须重新核对，不能沿用字段结论。
依赖：Python pefile、capstone；输出仅是静态证据，不代表客户端验收。
"""
import argparse
import hashlib
import json
import mmap
import struct
from pathlib import Path

import capstone
import pefile

MASK32 = (1 << 32) - 1
MASK64 = (1 << 64) - 1
HASHES = {
    'GameAssembly.dll': '8cc7380066343b6152069a6d1c90e230322aa78b780b1e738c13185b3692f1d3',
    'global-metadata.dat': '09ce2093b78779d4bbe9dc866bb472438efd96cc6546b7cbc34edf4006736130',
}


def word(data, offset):
    return struct.unpack_from('<I', data, offset)[0]


def method_key(index):
    value = (((index * 0x31e1) ^ 0x33914937) * 0x2c03f17d) & MASK64
    value = (((value >> 23) * 0x540cc9f4) & MASK64) >> 21
    return (value + 0x71bc7861) & MASK32


def extract(args):
    root = args.client_dir.resolve()
    paths = [root / 'GameAssembly.dll', root / 'StarRail_Data/il2cpp_data/Metadata/global-metadata.dat']
    verified = {}
    for path in paths:
        with path.open('rb') as stream:
            digest = hashlib.file_digest(stream, 'sha256').hexdigest()
        if digest != HASHES[path.name]:
            raise ValueError(f'{path.name}: SHA256 不匹配，拒绝沿用其他客户端的解码常量')
        verified[path.name] = digest
    pe = pefile.PE(str(paths[0]), fast_load=True)
    image_base = pe.OPTIONAL_HEADER.ImageBase
    decoder = capstone.Cs(capstone.CS_ARCH_X86, capstone.CS_MODE_64)
    with paths[0].open('rb') as binary, paths[1].open('rb') as metadata:
        with mmap.mmap(binary.fileno(), 0, access=mmap.ACCESS_READ) as dll, \
                mmap.mmap(metadata.fileno(), 0, access=mmap.ACCESS_READ) as data:
            def read_rva(rva, count):
                # Reject virtual zero-fill addresses and truncated reads.
                section = pe.get_section_by_rva(rva)
                if section is None or rva - section.VirtualAddress + count > section.SizeOfRawData:
                    raise ValueError(f'RVA 无对应文件字节: {rva:x}')
                offset = pe.get_offset_from_rva(rva)
                return dll[offset:offset + count]

            header = read_rva(0x48b19b0, 0x208)
            names = ((word(header, 0x1b4) + 0x8d43a4ee) & MASK32) + 0x208
            types = (word(header, 0x84) ^ 0x68531d3f) + 0x208
            methods = ((word(header, 0x14c) + 0xf3a04294) & MASK32) + 0x208
            count = (word(header, 0x1a8) ^ 0x729b1a9e) // 70

            # SetupFields 3d220d0, nongeneric branch 3d22484..3d22604:
            # type definition -> 12-byte layout record -> declared-field ordinal.
            # The global field definition index is NOT an index into the offset table.
            field_offsets_requested = getattr(args, 'field_offsets', False)
            if field_offsets_requested:
                layout_indexes = ((word(header, 0x180) + 0xe4dbe763) & MASK32) + 0x208
                layout_records = ((word(header, 0x9c) + 0xbc7ec9d7) & MASK32) + 0x208
                layout_offsets = (word(header, 0x148) ^ 0x329e1172) + 0x208
                if layout_indexes + count * 4 > len(data):
                    raise ValueError('类型布局索引表超出元数据范围')

            def name(index):
                if index == MASK32:
                    return ''
                extended = bool(index & 0x80000000)
                offset = index & (0x7fffff if extended else 0x1ffffff)
                length = (index >> (23 if extended else 25)) & (255 if extended else 63)
                seed = offset * 0x907c49622d94d21a + 0x75b679daf67c3f24
                decoded = bytearray()
                for chunk in range((length + 7) // 8):
                    encrypted = struct.unpack_from('<Q', data, names + offset + 8 * chunk)[0]
                    decoded.extend(struct.pack('<Q', encrypted ^
                        ((seed + chunk * 0x3e693cd23a41fdef) & MASK64)))
                return decoded[:length].decode('utf-8', errors='strict')

            # SetupFields (3d220d0) and MonoField.GetRawConstantValue (3cd70d0)
            # independently identify these tables. Field order does not imply enum values.
            constants = {}
            if args.fields:
                field_table = ((word(header, 0x20) + 0xb2fce189) & MASK32) + 0x208
                default_table = (word(header, 0x1fc) ^ 0x6238cdb0) + 0x208
                default_bytes = word(header, 0x1dc) ^ 0x3e0c72f0
                default_data = ((word(header, 0x3c) + 0x978be7a5) & MASK32) + 0x208
                if not 12 <= default_bytes < 4000000 or default_table + default_bytes > len(data):
                    raise ValueError('字段常量表超出已核对的元数据范围')
                # The native reader uses floor(size / 12); trailing bytes are not a row.
                for pos in range(default_table, default_table + (default_bytes // 12) * 12, 12):
                    native_type, value_offset, field_index = struct.unpack_from('<iii', data, pos)
                    if value_offset < 0:
                        continue
                    if field_index in constants:
                        raise ValueError('同一字段出现多个元数据常量')
                    constants[field_index] = (native_type, value_offset)

            def native_type_info(index, depth=0):
                if depth > 8:
                    raise ValueError('数组元素类型递归超过只读核对边界')
                if not 0 <= index < 1000000:
                    raise ValueError('字段类型索引超出已核对的类型表范围')
                record = read_rva(0x4ffdd60 + index * 8, 8)
                kind = record[6]
                result = {'kind': kind, 'attributes': struct.unpack_from('<H', record, 4)[0]}
                if kind in (0x11, 0x12):
                    definition = word(record, 0)
                    if definition >= count:
                        raise ValueError('字段类型未指向可核对的定义')
                    result.update(typeIndex=definition,
                                  name=name((word(data, types + definition * 70 + 0x28)
                                             + 0xe9fd68f8) & MASK32))
                elif kind == 29:
                    element = word(record, 0)
                    result.update(elementTypeIndex=element,
                                  elementType=native_type_info(element, depth + 1))
                return result

            def field_info(row):
                raw_start = word(data, row + 0x20)
                begin = (raw_start + 0x8b7ac79c) & MASK32
                total = (struct.unpack_from('<H', data, row + 0x32)[0] + 0x444d) & 65535
                if total > 10000 or total and field_table + (begin + total) * 8 > len(data):
                    raise ValueError('无法核对字段定义范围')
                result = []
                offset_start = None
                if field_offsets_requested and total:
                    definition = (row - types) // 70
                    record = word(data, layout_indexes + definition * 4)
                    record_pos = layout_records + record * 12
                    if record_pos + 12 > len(data):
                        raise ValueError('类型布局记录超出元数据范围')
                    offset_start = word(data, record_pos + 8)
                    if layout_offsets + (offset_start + total) * 4 > len(data):
                        raise ValueError('声明字段偏移范围超出元数据')
                for ordinal in range(total):
                    field_index = begin + ordinal
                    pos = field_table + field_index * 8
                    key = (0xad416bb9 - (raw_start + ordinal) * 0x2c5dcb00) & MASK32
                    index = (word(data, pos + 4) + key) & MASK32
                    entry = {'fieldIndex': field_index,
                             'name': name((word(data, pos) + key + 0x2aafc785) & MASK32),
                             'nativeTypeIndex': index, 'nativeType': native_type_info(index)}
                    entry['isStatic'] = bool(entry['nativeType']['attributes'] & 0x10)
                    if field_offsets_requested and not entry['isStatic']:
                        offset = word(data, layout_offsets + (offset_start + ordinal) * 4)
                        if offset & 0xff000000:
                            # Native dynamic/indirect layouts require runtime resolution.
                            # Do not strip flags and claim a directly readable object offset.
                            entry['unsupportedInstanceOffset'] = hex(offset)
                        else:
                            entry['instanceOffset'] = offset
                    if field_index in constants:
                        constant_type, value_offset = constants[field_index]
                        kind = native_type_info(constant_type)['kind']
                        constant = {'nativeTypeIndex': constant_type, 'kind': kind,
                                    'dataOffset': value_offset}
                        if kind in (8, 9):
                            if default_data + value_offset + 4 > len(data):
                                raise ValueError('Int32/UInt32 常量超出元数据范围')
                            constant['value'] = struct.unpack_from('<i' if kind == 8 else '<I', data,
                                                                 default_data + value_offset)[0]
                        else:
                            constant['unsupportedValueEncoding'] = True
                        entry['constant'] = constant
                    result.append(entry)
                return result

            # Static class slots are populated by metadata usage kind 1 (RVA 3d330f0).
            # Resolve their type definitions before interpreting an obfuscated response layout.
            # Register (3adc820) installs this collection. Its switch at 3d336c4
            # selects separate CLASS / MethodInfo / string arrays, never one shared base.
            registration = struct.unpack('<14Q', read_rva(0x49316f0, 112))
            slot_base = registration[0x48 // 8] - image_base
            method_slot_base = registration[8 // 8] - image_base
            string_slot_base = registration[0x10 // 8] - image_base
            if (slot_base, method_slot_base, string_slot_base) != (
                    0x9359280, 0x944d430, 0x96f35f0):
                raise ValueError('元数据使用表注册地址交叉核对失败')
            wanted = {}
            for slot in args.type_slots:
                if slot < slot_base or (slot - slot_base) % 8:
                    raise ValueError(f'类型槽地址未对齐或超出已核对的表: {slot:x}')
                wanted[(slot - slot_base) // 8] = slot
            resolved_slots = {}
            def slot_destinations(slots, base, label):
                result = {}
                for slot in slots:
                    if slot < base or (slot - base) % 8:
                        raise ValueError(f'{label}槽地址未对齐或超出已核对的表: {slot:x}')
                    result[(slot - base) // 8] = slot
                return result

            wanted_methods = slot_destinations(args.method_slots, method_slot_base, '方法')
            wanted_strings = slot_destinations(args.string_slots, string_slot_base, '字符串')
            resolved_methods = {}
            resolved_strings = {}
            pairs = ((word(header, 0x190) + 0xed791073) & MASK32) + 0x208
            literals = (word(header, 0x1f0) ^ 0x56c7d20d) + 0x208
            if wanted or wanted_methods or wanted_strings:
                for pair in range((literals - pairs) // 8):
                    first, second = struct.unpack_from('<II', data, pairs + pair * 8)
                    key = (((pair * 0x87c3) ^ 0x5fa3fad3) * 0x334fb2ba + 0x454d10d89e6a02a) & MASK64
                    key = (((key >> 14) * 0x102533d7 + 0x58972d9863a807) & MASK64) >> 23
                    destination = ((first ^ 0x6907ab9a) - key) & MASK32
                    source = (second - key + 0xab3a6cb5) & MASK32
                    usage_kind = source >> 29
                    payload = source & 0x1fffffff
                    if destination in wanted_methods and usage_kind in (3, 6):
                        if usage_kind == 6:
                            raise ValueError('方法槽为泛型 MethodSpec，尚未核对该变体')
                        previous = resolved_methods.get(destination)
                        if previous and previous['methodIndex'] != payload:
                            raise ValueError('同一方法槽具有冲突的元数据初始化')
                        resolved_methods[destination] = {'slot': hex(wanted_methods[destination]),
                            'pairIndex': pair, 'usageKind': usage_kind, 'methodIndex': payload}
                    if destination in wanted_strings and usage_kind == 5:
                        if literals + payload * 4 + 8 > len(data):
                            raise ValueError('字符串字面量索引超出元数据范围')
                        seed = (payload * 0x32c1cf25bb14) & MASK64
                        k1 = ((((seed + 0x1f0fe259cf0538) & MASK64) >> 14)
                              * 0x24085c9a & MASK64) >> 8
                        intermediate = (word(data, literals + payload * 4) - k1) & MASK32
                        offset = (intermediate + 0x3bd9429b) & MASK32
                        k2 = ((((seed + 0x1f42a428f4c04c) & MASK64) >> 14)
                              * 0x24085c9a & MASK64) >> 8
                        length = (word(data, literals + payload * 4 + 4)
                                  - k2 - intermediate) & MASK32
                        string_data = ((word(header, 8) + 0xf7114e59) & MASK32) + 0x208
                        if length > 65536 or string_data + offset + length > len(data):
                            raise ValueError('字符串字面量长度或数据超出已核对的范围')
                        raw = data[string_data + offset:string_data + offset + length]
                        cipher_seed = ((((payload * 0xde8c09c836133dbd) & MASK64)
                                        ^ 0x18d025c96ee74e86) + 0x2c6833cc6f0a9c48) & MASK64
                        decoded = bytearray()
                        for chunk in range((length + 7) // 8):
                            encrypted = int.from_bytes(raw[chunk * 8:chunk * 8 + 8].ljust(8, b'\0'), 'little')
                            decoded.extend(struct.pack('<Q', encrypted ^
                                ((cipher_seed + chunk * 0x464c46540f730312) & MASK64)))
                        value = decoded[:length].decode('utf-8', errors='strict')
                        previous = resolved_strings.get(destination)
                        if previous and previous['value'] != value:
                            raise ValueError('同一字符串槽具有冲突的元数据初始化')
                        resolved_strings[destination] = {'slot': hex(wanted_strings[destination]),
                            'pairIndex': pair, 'usageKind': usage_kind, 'literalIndex': payload,
                            'value': value}
                    if destination not in wanted or source >> 29 != 1:
                        continue
                    type_record = read_rva(0x4ffdd60 + (source & 0x1fffffff) * 8, 8)
                    definition = word(type_record, 0)
                    if type_record[6] != 0x12 or definition >= count:
                        raise ValueError(f'类型槽未指向 CLASS 定义: {wanted[destination]:x}')
                    type_name = name((word(data, types + definition * 70 + 0x28) + 0xe9fd68f8) & MASK32)
                    if destination in resolved_slots and resolved_slots[destination]['typeIndex'] != definition:
                        raise ValueError('同一类型槽具有冲突的元数据初始化')
                    resolved_slots[destination] = {'slot': hex(wanted[destination]),
                        'pairIndex': pair, 'typeIndex': definition, 'typeName': type_name}
                if set(wanted) != set(resolved_slots):
                    raise ValueError('无法解析全部指定类型槽')
                if set(wanted_methods) != set(resolved_methods):
                    raise ValueError('无法解析全部指定方法槽')
                if set(wanted_strings) != set(resolved_strings):
                    raise ValueError('无法解析全部指定字符串槽')
            requested_types = set(args.types) | {slot['typeName'] for slot in resolved_slots.values()}

            # The address array is read by SetupMethods at RVA 3d240c1.
            pointers = struct.unpack('<Q', read_rva(0x43f9fa0 + 0x40, 8))[0] - image_base
            checks = {219393: 0x1e955a90, 635044: 0xd9a70e0, 615965: 0xdee9860}
            for index, expected in checks.items():
                actual = struct.unpack('<Q', read_rva(pointers + index * 8, 8))[0] - image_base
                if actual != expected:
                    raise ValueError(f'方法表交叉核对失败: {index}')

            def method_info(index):
                pos = methods + index * 26
                if index < 0 or pos + 26 > len(data):
                    raise ValueError(f'方法索引超出元数据: {index}')
                declaring = word(data, pos + 0x10) ^ method_key(index) ^ 0x2a5fabe8
                if declaring >= count:
                    raise ValueError(f'无法核对方法所属类型: {index}')
                row = types + declaring * 70
                type_name = name((word(data, row + 0x28) + 0xe9fd68f8) & MASK32)
                method_name = name(word(data, pos) ^ method_key(index) ^ 0xe714bc1)
                rva = struct.unpack('<Q', read_rva(pointers + index * 8, 8))[0] - image_base
                result = {'index': index, 'name': method_name, 'rva': hex(rva),
                          'typeIndex': declaring, 'typeName': type_name}
                if args.disassemble and rva > 0 and (
                        not args.method_names or method_name in args.method_names):
                    result['instructions'] = [
                        f'{ins.address - image_base:x}: {ins.mnemonic} {ins.op_str}'
                        for ins in decoder.disasm(read_rva(rva, args.disassemble), image_base + rva)]
                return result

            selected = [method_info(index) for index in args.method_indices]
            for slot in resolved_methods.values():
                slot['method'] = method_info(slot.pop('methodIndex'))
            method_fragments = getattr(args, 'method_name_contains', [])
            if args.method_rvas or method_fragments:
                # The last declared method start is a verified lower bound, not a guessed count.
                starts = [word(data, types + index * 70 + 8) ^ 0x1a7af5fe
                          for index in range(count)]
                limit = max(start for start in starts if start != MASK32)
                requested = set(args.method_rvas)
                found = set()
                for index, (address,) in enumerate(struct.iter_unpack('<Q', read_rva(pointers, limit * 8))):
                    rva = address - image_base
                    if rva in requested:
                        selected.append(method_info(index))
                        found.add(rva)
                    if method_fragments:
                        method_pos = methods + index * 26
                        method_name = name(word(data, method_pos) ^ method_key(index) ^ 0xe714bc1)
                        if any(fragment in method_name for fragment in method_fragments):
                            if len(selected) >= 64:
                                raise ValueError('方法名匹配超过64项，请使用更具体的片段')
                            if not any(row['index'] == index for row in selected):
                                selected.append(method_info(index))
                if found != requested:
                    raise ValueError('指定 RVA 未在已核对的方法范围内定位，不能推定方法含义')
            matches = []
            for index in range(count):
                row = types + index * 70
                type_name = name((word(data, row + 0x28) + 0xe9fd68f8) & MASK32)
                if type_name not in requested_types and not any(
                        fragment in type_name for fragment in args.type_contains):
                    continue
                begin = word(data, row + 8) ^ 0x1a7af5fe
                entry = {'typeIndex': index, 'name': type_name, 'methods': []}
                if args.fields:
                    entry['fields'] = field_info(row)
                if args.inheritance:
                    chain = []
                    inherited = []
                    current = index
                    visited = set()
                    while True:
                        if current in visited or len(visited) >= 32:
                            raise ValueError('父类型链循环或超过只读核对边界')
                        visited.add(current)
                        parent_row = types + current * 70
                        owner = name((word(data, parent_row + 0x28) + 0xe9fd68f8) & MASK32)
                        own = [dict(field, declaringType=owner)
                               for field in field_info(parent_row) if not field['isStatic']]
                        inherited[0:0] = own
                        parent_index = (word(data, parent_row + 4) + 0xb706aab9) & MASK32
                        chain.append({'typeIndex': current, 'name': owner,
                                      'parentNativeTypeIndex': parent_index})
                        if parent_index == MASK32:
                            break
                        parent = native_type_info(parent_index)
                        chain[-1]['parentType'] = parent
                        if parent['kind'] == 28:  # Built-in System.Object has no parent fields.
                            break
                        if parent['kind'] != 18:
                            raise ValueError(f'父类型不是已核对的 CLASS，不能推定继承字段: {owner}')
                        current = parent['typeIndex']
                    entry['baseTypeChain'] = chain
                    entry['instanceFields'] = inherited
                if begin != MASK32:
                    successor = index + 1
                    while successor < count:
                        end = word(data, types + successor * 70 + 8) ^ 0x1a7af5fe
                        if end != MASK32:
                            break
                        successor += 1
                    if successor == count or not 0 <= end - begin <= 10000:
                        raise ValueError(f'无法核对方法范围: {type_name}')
                    for method in range(begin, end):
                        result = method_info(method)
                        if result['typeIndex'] != index:
                            raise ValueError(f'方法范围跨越所属类型: {type_name} / {method}')
                        entry['methods'].append(result)
                matches.append(entry)
    return {'clientExecutableStarted': False, 'clientModified': False, 'clientAccepted': False,
            'files': verified, 'typeCount': count, 'methods': matches, 'selectedMethods': selected,
            'typeInfoSlots': sorted(resolved_slots.values(), key=lambda slot: slot['slot']),
            'methodInfoSlots': sorted(resolved_methods.values(), key=lambda slot: slot['slot']),
            'stringLiteralSlots': sorted(resolved_strings.values(), key=lambda slot: slot['slot']),
            'unmatchedTypes': sorted(set(args.types) - {item['name'] for item in matches})}


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--client-dir', type=Path, required=True)
    parser.add_argument('--types', nargs='+', default=[])
    parser.add_argument('--fields', action='store_true',
                        help='只读核对字段、数组元素及Int32/UInt32常量；不把字段顺序当作数值')
    parser.add_argument('--field-offsets', action='store_true',
                        help='读取已核对的本机非泛型实例字段布局；间接/动态布局明确标为不支持')
    parser.add_argument('--inheritance', action='store_true',
                        help='沿已核对的父类型链读取实例字段；不推定二进制字段掩码或默认值')
    parser.add_argument('--type-contains', nargs='+', default=[],
                        help='按元数据类型名片段查找，仅用于定位，不能推定字段含义')
    parser.add_argument('--method-indices', nargs='+', type=lambda text: int(text, 0), default=[],
                        help='已从本机调用定位的方法索引，支持十进制或 0x 前缀')
    parser.add_argument('--method-rvas', nargs='+', type=lambda text: int(text, 0), default=[],
                        help='从本机静态调用点读取的目标 RVA，先核对方法所属类型再输出')
    parser.add_argument('--type-slots', nargs='+', type=lambda text: int(text, 0), default=[],
                        help='静态类型槽 RVA，必须来自调用点读取的地址')
    parser.add_argument('--method-slots', nargs='+', type=lambda text: int(text, 0), default=[],
                        help='静态 MethodInfo 槽 RVA；只接受已核对的非泛型方法使用类型')
    parser.add_argument('--string-slots', nargs='+', type=lambda text: int(text, 0), default=[],
                        help='静态字符串槽 RVA；按本机注册表和字面量解码函数只读解析')
    parser.add_argument('--method-names', nargs='*', default=[])
    parser.add_argument('--method-name-contains', nargs='+', default=[],
                        help='按方法名片段定位编译器回调，最多64项；名称本身不证明协议语义')
    parser.add_argument('--disassemble', type=int, default=0)
    parser.add_argument('--output', type=Path, default=Path('build/client-46-methods.json'))
    args = parser.parse_args()
    if args.inheritance or args.field_offsets:
        args.fields = True
    if not 0 <= args.disassemble <= 10000:
        parser.error('--disassemble 必须在 0..10000')
    if any(len(fragment) < 3 for fragment in args.type_contains):
        parser.error('--type-contains 的片段至少包含三个字符')
    if any(len(fragment) < 8 for fragment in args.method_name_contains):
        parser.error('--method-name-contains 的片段至少包含八个字符')
    if not args.types and not args.type_contains and not args.method_indices and not args.method_rvas and not args.type_slots and not args.method_slots and not args.string_slots and not args.method_name_contains:
        parser.error('至少指定 --types、--method-indices 或 --type-slots')
    report = extract(args)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    print(json.dumps({'output': str(args.output), 'typeCount': report['typeCount'],
        'matchedTypes': len(report['methods']), 'selectedMethods': len(report['selectedMethods']),
        'unmatchedTypes': report['unmatchedTypes'],
        'clientAccepted': False}, ensure_ascii=True))
