"""只读核对基类销毁与两个事件监听撤回，保留原生委托边界。"""
import argparse
from functools import lru_cache
import hashlib
import importlib.util
import json
import struct
from pathlib import Path
from types import SimpleNamespace

ROOT=Path(__file__).resolve().parents[1]
PROFILE=ROOT/'src/test/resources/fixtures/rogue-base-modifier-destroy-4.6.json'
PROFILE_SHA256='2fed11ef445d5205f764b7f4f995ea6539c9a59ae2a755bf082ab64de595ffe2'

def sibling(name,filename):
 spec=importlib.util.spec_from_file_location(name,Path(__file__).with_name(filename))
 module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module);return module

characters=sibling('base_modifier_destroy_characters','read-rogue-resonance-character.py')

@lru_cache(maxsize=1)
def load_profile():
 raw=PROFILE.read_bytes().replace(b'\r\n',b'\n')
 if hashlib.sha256(raw).hexdigest()!=PROFILE_SHA256:raise ValueError('基类销毁冻结证据改变')
 return json.loads(raw)

def metadata(client,types,methods,inheritance=False):
 analyzer=sibling('base_modifier_destroy_metadata','analyze-client-46.py')
 return analyzer.extract(SimpleNamespace(client_dir=client,types=types,fields=True,field_offsets=True,
  inheritance=inheritance,type_contains=[],method_indices=methods,method_rvas=[],method_name_contains=[],
  method_slots=[],string_slots=[],type_slots=[],disassemble=0,method_names=[]))

def metadata_slots(client,p):
 analyzer=sibling('base_modifier_destroy_slots','analyze-client-46.py')
 return analyzer.extract(SimpleNamespace(client_dir=client,types=[],fields=False,field_offsets=False,
  inheritance=False,type_contains=[],method_indices=[],method_rvas=[],method_name_contains=[],
  method_slots=[int(row['slot'],16) for row in p['callbackMethodSlots']],string_slots=[],type_slots=[int(row['slot'],16) for row in p['callbackTypeSlots']],disassemble=0,method_names=[]))

def verify_native(client):
 p=load_profile();methods,size=characters.verify_native(client,p)
 expected=sorted([{k:m[k] for k in ['index','name','rva','typeIndex','typeName']} for m in p['nativeMethods']]+p['boundMethodIdentities'],key=lambda m:m['index'])
 actual=metadata(client,[t['name'] for t in p['matchedTypes']],[m['index'] for m in expected])
 if actual['selectedMethods']!=expected:raise ValueError('基类销毁、上下文求值和明确绑定方法身份不符')
 declared={t['typeIndex']:t for t in actual['methods']}
 inheritedNames=[t['name'] for t in p['matchedTypes'] if 'selectedInstanceFields' in t]
 inherited={t['typeIndex']:t for t in metadata(client,inheritedNames,[],True)['methods']}
 for fact in p['matchedTypes']:
  t=declared[fact['typeIndex']]
  if t['name']!=fact['name']:raise ValueError('基类销毁字段所属类型不符')
  if 'fields' in fact and t['fields']!=fact['fields']:raise ValueError('基类销毁枚举完整声明不符')
  for key,rows in [('selectedFields',t['fields']),('selectedInstanceFields',inherited.get(fact['typeIndex'],{}).get('instanceFields',[]))]:
   if key not in fact:continue
   names={f['name'] for f in fact[key]}
   if [f for f in rows if f['name'] in names]!=fact[key]:raise ValueError('基类监听撤回字段不符')
 components=sibling('base_modifier_destroy_parameters','read-rogue-resonance-components.py')
 import pefile
 pe=pefile.PE(str(client/'GameAssembly.dll'),fast_load=True)
 header=components.read_rva(pe,0x48b19b0,0x208)
 metadataRaw=(client/'StarRail_Data/il2cpp_data/Metadata/global-metadata.dat').read_bytes()
 methodTable=((struct.unpack_from('<I',header,0x14c)[0]+0xf3a04294)&0xffffffff)+0x208
 parameterTable=((struct.unpack_from('<I',header,0x30)[0]+0xdcf5fdbe)&0xffffffff)+0x208
 for row in p['parameterRecords']:
  index=row['methodIndex'];offset=methodTable+index*26
  if offset!=int(row['methodRowOffset'],16) or metadataRaw[offset:offset+26].hex()!=row['methodRowHex']:raise ValueError('基类销毁参数的方法原始行不符')
  key=(((index*0x31e1)^0x33914937)*0x2c03f17d)&0xffffffffffffffff
  key=(((key>>23)*0x540cc9f4)&0xffffffffffffffff)>>21
  key=(key+0x71bc7861)&0xffffffff
  count=metadataRaw[offset+0x18]^(key&255)^0xa8
  start=struct.unpack_from('<I',metadataRaw,offset+4)[0]^key^0x9889b8
  parameter=row['parameter'];pi=parameter['index']
  if not 0<=parameter['ordinal']<count or pi!=start+parameter['ordinal']:raise ValueError('基类销毁参数的参数位置不符')
  pp=parameterTable+pi*8
  if pp!=int(row['parameterRowOffset'],16) or metadataRaw[pp:pp+8].hex()!=row['parameterRowHex']:raise ValueError('基类销毁参数的参数原始行不符')
  pkey=((((((pi*0x72e1d74b12b+0x1911d05aff5)&0xffffffffffffffff)>>11)*0x58b870a2)+0x83cf7b44)&0xffffffff)
  nativeType=((struct.unpack_from('<I',metadataRaw,pp)[0]^0x67e90dc5)-pkey)&0xffffffff
  record=row['record'];rva=0x4ffdd60+nativeType*8
  if nativeType!=parameter['nativeTypeIndex'] or rva!=int(record['nativeRva'],16):raise ValueError('基类销毁参数的类型索引不符')
  raw=components.read_rva(pe,rva,8)
  if raw.hex()!=record['bytesHex'] or raw[6]!=record['kind'] or struct.unpack_from('<I',raw)[0]!=record['payload']:raise ValueError('基类销毁参数的引用或布尔类型原件不符')
 slots=metadata_slots(client,p)
 if slots['methodInfoSlots']!=p['callbackMethodSlots'] or slots['typeInfoSlots']!=p['callbackTypeSlots']:raise ValueError('基类销毁委托类型与两个回调方法槽不符')
 import capstone
 decoder=capstone.Cs(capstone.CS_ARCH_X86,capstone.CS_MODE_64);decoder.detail=True
 instructions={i.address-pe.OPTIONAL_HEADER.ImageBase:i for m in p['nativeMethods'] for i in decoder.disasm(bytes.fromhex(m['bytesHex']),pe.OPTIONAL_HEADER.ImageBase+int(m['rva'],16))}
 for row in p['ripBindings']:
  i=instructions[int(row['instructionRva'],16)];rip=next(o for o in i.operands if o.type==capstone.x86.X86_OP_MEM and o.mem.base==capstone.x86.X86_REG_RIP)
  if i.address+i.size+rip.mem.disp-pe.OPTIONAL_HEADER.ImageBase!=int(row['target'],16):raise ValueError('基类销毁分配类型/方法/桥接地址不符')
 auxiliary,auxiliarySize=characters.verify_native(client,{'nativeFiles':p['nativeFiles'],'nativeMethods':p['auxiliaryMethods'],'nativeBytes':p['auxiliaryBytes']})
 ordinary=sibling('base_modifier_destroy_ordinary','read-rogue-modifier-destroy.py');ordinary.verify_native(client)
 return methods,size,auxiliary,auxiliarySize

def analyze(a):
 client=a.client_dir.resolve();design=sibling('base_modifier_destroy_output','read-client-design-data.py')
 output=design.outside_client(a.output,client)
 if ROOT/'build' not in output.parents:raise ValueError('只接受工程build下的基类销毁证据')
 p=load_profile();methods,size,auxiliary,auxiliarySize=verify_native(client)
 result={'passed':True,'matchedCompleteNativeMethods':methods,'matchedCompleteNativeBytes':size,
  'matchedCompleteAuxiliaryMethods':auxiliary,'matchedCompleteAuxiliaryBytes':auxiliarySize,'matchedCallbackMethodSlots':len(p['callbackMethodSlots']),'matchedCallbackTypeSlots':len(p['callbackTypeSlots']),'matchedRipBindings':len(p['ripBindings']),'matchedNativeParameterRecords':len(p['parameterRecords']),'matchedFieldTypes':len(p['matchedTypes']),'matchedBoundMethodIdentities':len(p['boundMethodIdentities']),**{k:v for k,v in p.items() if isinstance(v,bool)},
  'jsonInstalled':False,'clientExecutableStarted':False,'clientModified':False}
 output.parent.mkdir(parents=True,exist_ok=True);output.write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n','utf-8');return result

if __name__=='__main__':
 parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--client-dir',type=Path,required=True)
 parser.add_argument('--output',type=Path,default=ROOT/'build/rogue-base-modifier-destroy-evidence.json')
 print(json.dumps(analyze(parser.parse_args()),ensure_ascii=False))
