"""Bound exact original1407 Out to a typed Submit task and complete native1460 writer."""
import argparse
import base64
import hashlib
import importlib.util
import json
import mmap
from pathlib import Path
import struct
from types import SimpleNamespace
import zlib
import capstone
import pefile

HERE = Path(__file__).resolve().parent
ROOT = next(parent for parent in HERE.parents if (parent / 'scripts/analyze-client-46.py').is_file())
CLIENT = Path(r'C:\Users\InvokerBot\AppData\Local\hoyo\hkrpg\versions\current')
sha = lambda raw: hashlib.sha256(raw).hexdigest()

def module(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    value = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(value)
    return value

def verify_submit_parent(parent):
    if parent['nativeKind'] != 21 or parent['definitionName'] != 'ImmediateTaskBase`1':
        raise ValueError('Not the actual immediate generic task base')
    if parent['genericDefinition'] != 54788 or parent['args'] != [
            dict(kind=18, definition=20219, name='AdventureSubmitSkill')]:
        raise ValueError('Generic task does not bind the original AdventureSubmitSkill template')
    return True

def audit(client_dir, destination):
    global CLIENT
    CLIENT = client_dir.resolve()
    destination = destination.resolve()
    if not destination.is_relative_to(ROOT / 'build') or destination.is_relative_to(CLIENT):
        raise ValueError('Source evidence only writes a new owned build directory outside the client')
    destination.mkdir(parents=True, exist_ok=False)
    reader_path = ROOT / 'scripts/analyze-client-46.py'
    components_path = ROOT / 'scripts/read-rogue-resonance-components.py'
    ai_path = ROOT / 'scripts/read-rogue-ai-default.py'
    components = module('out_complete_generic_reader', components_path)
    ai = module('out_complete_generic_contract', ai_path)
    existing = ai.load_profile()
    source = reader_path.read_text('utf-8')
    old = "entry = {'typeIndex': index, 'name': type_name, 'methods': []}"
    new = old + "\n                entry['nativeParentTypeIndex'] = (word(data, row + 4) + 0xb706aab9) & MASK32"
    assert source.count(old) == 1
    scope = {'__name__': 'out_complete_typed_parent_reader', '__file__': str(reader_path)}
    exec(compile(source.replace(old, new), str(reader_path), 'exec'), scope)
    methods = [535758, 552535, 635593, 635592, 635460, 232578, 232553, 526387]
    native = scope['extract'](SimpleNamespace(client_dir=CLIENT,
        types=['ALGDMNMOPPK', 'ImmediateTaskBase`1', 'AdventureSubmitSkill', 'DGOAKJNHIFJ', 'INBDPGBJIFA'],
        fields=True, field_offsets=True, inheritance=False, type_contains=[], method_indices=methods,
        method_rvas=[], type_slots=[], method_slots=[], string_slots=[], method_names=[],
        method_name_contains=[], disassemble=0))
    layout = module('out_complete_pe_layout', ROOT / 'scripts/inspect-maze-cast-sequence-client.py')
    pe = pefile.PE(str(CLIENT / 'GameAssembly.dll'), fast_load=True)
    startup = (CLIENT / 'StarRail_Data/il2cpp_data/Metadata/startup-metadata.dat').read_bytes()
    assert sha(startup) == existing['nativeFiles']['startup-metadata.dat']
    header = components.read_rva(pe, 0x48b19b0, 0x208)
    table = struct.unpack_from('<I', header, 0x164)[0] ^ 0x7f5c5934
    array = struct.unpack('<Q', components.read_rva(pe, 0x4931690, 8))[0] - pe.OPTIONAL_HEADER.ImageBase
    assert table == int(existing['genericClassTableOffsetInStartup'], 16)
    assert array == int(existing['genericInstantiationArrayRva'], 16)
    owner = next(t for t in native['methods'] if t['name'] == 'ALGDMNMOPPK')
    parent_index = owner['nativeParentTypeIndex']
    raw = components.read_rva(pe, 0x4ffdd60 + parent_index * 8, 8)
    assert raw[6] == 21
    payload = struct.unpack_from('<I', raw)[0]
    position = table + payload * 8
    assert position + 8 <= len(startup)
    definition, inst = struct.unpack_from('<ii', startup, position)
    inst_rva = array + inst * 16
    inst_raw = components.read_rva(pe, inst_rva, 16)
    count, reserved, argv = struct.unpack('<IIQ', inst_raw)
    assert count == 1 and reserved == 0 and argv >= pe.OPTIONAL_HEADER.ImageBase
    pointer = struct.unpack('<Q', components.read_rva(pe, argv - pe.OPTIONAL_HEADER.ImageBase, 8))[0]
    arg_rva = pointer - pe.OPTIONAL_HEADER.ImageBase
    arg_raw = components.read_rva(pe, arg_rva, 8)
    arg_def = struct.unpack_from('<I', arg_raw)[0]
    declared = {t['typeIndex']: t['name'] for t in native['methods']}
    parent = dict(ownerTypeIndex=owner['typeIndex'], ownerTypeName=owner['name'],
        nativeParentTypeIndex=parent_index, nativeParentRaw=raw.hex(), nativeKind=raw[6], payload=payload,
        startupRowOffset=hex(position), startupRowHex=startup[position:position + 8].hex(),
        genericDefinition=definition, definitionName=declared[definition], instIndex=inst,
        instRVA=hex(inst_rva), instBytesHex=inst_raw.hex(), argumentRVA=hex(arg_rva), argumentBytesHex=arg_raw.hex(),
        args=[dict(kind=arg_raw[6], definition=arg_def, name=declared[arg_def])])
    verify_submit_parent(parent)
    with (CLIENT / 'GameAssembly.dll').open('rb') as stream, mmap.mmap(stream.fileno(), 0, access=mmap.ACCESS_READ) as dll:
        offset, start, length, image_base = layout.pe_layout(dll)
        boundaries = {begin: end for begin, end, _ in struct.iter_unpack('<III', dll[start:start + length])}
        decoder = capstone.Cs(capstone.CS_ARCH_X86, capstone.CS_MODE_64)
        for method in native['selectedMethods']:
            begin = int(method['rva'], 16)
            end = boundaries[begin]
            assert 0 < end - begin < 30000
            body = dll[offset(begin, end - begin):offset(begin, end - begin) + end - begin]
            instructions = list(decoder.disasm(body, image_base + begin))
            assert sum(row.size for row in instructions) == len(body)
            method.update(endRVA=hex(end), byteCount=len(body), bodySHA256=sha(body),
                instructions=[f'{row.address - image_base:x}: {row.mnemonic} {row.op_str}' for row in instructions])
    pinned = json.loads((ROOT / 'scripts/fixtures/maze-cast-sequence-native-4.6.json').read_text('utf-8'))
    manual = json.loads((ROOT / 'scripts/fixtures/maze-manual-ability-native-4.6.json').read_text('utf-8'))
    combined_pins = {m['index']: m for m in [*pinned['methods'], *manual['functions']]}
    functions = {m['index']: m for m in native['selectedMethods']}
    for index, method in functions.items():
        if index in combined_pins: assert method['bodySHA256'] == combined_pins[index]['bodySHA256']
    anchors = {
        535758: ['1555d9b8: xor r8d, r8d', '1555d9bb: xor r9d, r9d', '1555d9be: call 0x18c603fc0'],
        552535: ['c604066: call 0x18e84dab0', 'c604073: mov qword ptr [rsp + 0x80], r13',
                 'c60407b: mov qword ptr [rsp + 0x88], rsi', 'c604172: lea rdx, [rsp + 0x60]',
                 'c604177: call 0x18d9c4ce0'],
        635593: ['e84dee2: mov qword ptr [rsi + 0x18], rax'],
        635592: ['e84e1ab: mov dword ptr [rax + 0x20], 1', 'e84e1b7: mov dword ptr [rax + 0x24], 2',
                 'e84e1c5: mov qword ptr [rcx + 0x63f38], rax'],
        635460: ['d9c4d50: mov rax, qword ptr [r14 + 0x10]', 'd9c4d70: mov qword ptr [r12 + 0x10], rax',
                 'd9c4d75: mov eax, dword ptr [r14]', 'd9c4d78: mov dword ptr [r12 + 0x58], eax',
                 'd9c4f36: mov rdx, qword ptr [r14 + 0x18]', 'd9c4f3f: mov rcx, qword ptr [r12 + 0x18]',
                 'd9c5100: mov dx, 0x5b4'],
        232553: ['1e69189c: mov ecx, 0x5a', '1e6918b6: mov qword ptr [rcx + 0x53248], rax'],
        232578: ['1e690c2d: mov rcx, qword ptr [rdi + 0x18]', '1e690c41: mov r8, qword ptr [rax + 0x53248]',
                 '1e690c97: mov byte ptr [rcx + rax + 0x20], 0x68', '1e690c9c: mov edx, dword ptr [rdi + 0x58]',
                 '1e690cf0: mov byte ptr [rcx + rax + 0x20], 0x7a', '1e690cf5: mov rdx, qword ptr [rdi + 0x10]']}
    for index, instructions in anchors.items():
        assert all(row in functions[index]['instructions'] for row in instructions)
    fixture_path = ROOT / 'src/test/resources/fixtures/maze-castorice-manual-ability-4.6.json'
    fixture = json.loads(fixture_path.read_text('utf-8'))
    blob = fixture['sourceFiles']['Config/ConfigAdventureAbility/LocalPlayer/LocalPlayer_Castorice_00_Ability.json']
    ability_raw = zlib.decompress(base64.b64decode(blob['zlibBase64']))
    assert sha(ability_raw) == blob['blobSHA256']
    ability = json.loads(ability_raw)
    out = ability['AbilityList'][4]
    assert out['Name'] == 'LocalPlayer_Castorice_MazeSkill_Out' and out['ManualTriggerType'] == 'Free'
    assert out['OnStart'][2]['SuccessTaskList'] == [
        {'$type': 'RPG.GameCore.AdventureSubmitSkill'},
        {'$type': 'RPG.GameCore.RemoveMazeBuff', 'TargetType': {'$type': 'RPG.GameCore.TargetAlias', 'Alias': 'Caster'}, 'ID': 140704}]
    native.update(submitTaskParentBinding=parent, startupSHA256=sha(startup), exactOriginalOut=out,
        noRuntimeOrClientExecution=True, nativeTemplateMappingProven=True,
        wirePattern=dict(command=1460, skillIndexField4=1, tagsField11=[1, 2],
            castUseIdField13='original new Free Ability UseID', abilityNameField15=out['Name'],
            hitTargets='empty', assistTargets='empty'),
        nullArguments='DGO+0x20/+0x28 are separate target lists. They do not overwrite tag arrayDGO+0x18.',
        fixedSourceCommit=fixture['sourceCommit'], sourceFixtureSHA256=sha(fixture_path.read_bytes()),
        verificationDependencies={str(p.relative_to(ROOT)): sha(p.read_bytes()) for p in
            [reader_path, components_path, ai_path, ROOT / 'src/test/resources/fixtures/rogue-ai-default-4.6.json']},
        officialClientRequestCaptured=False, clientGameplayVerified=False,
        summonCreationOrPhysicsVerified=False, formalSourcesModified=False)
    with (destination / 'proof.json').open('x', encoding='utf-8') as stream: json.dump(native, stream, ensure_ascii=False, indent=2)
    print(json.dumps(dict(nativeFunctions=len(functions), submitGenericBindingPassed=True,
        defaultTags=[1, 2], finalFields=[11, 13, 15], clientExecuted=False)))

if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--client-dir', type=Path, default=CLIENT)
    parser.add_argument('--output-dir', type=Path, default=ROOT / 'build/maze-castorice-out-source-analysis')
    args = parser.parse_args()
    audit(args.client_dir, args.output_dir)
