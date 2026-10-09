"""只读核对事件载荷初始化、清空及None/零值原件；池和世界仍待完成。"""
import argparse
from functools import lru_cache
import hashlib
import importlib.util
import json
import struct
from pathlib import Path
from types import SimpleNamespace

ROOT=Path(__file__).resolve().parents[1]
PROFILE=ROOT/'src/test/resources/fixtures/rogue-event-payload-4.6.json'
PROFILE_SHA256='cee8f1439e36a825221924bec48703072b490969e671318b0a6213d88b3cbd87'

def sibling(name,filename):
 spec=importlib.util.spec_from_file_location(name,Path(__file__).with_name(filename))
 module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module);return module

characters=sibling('event_payload_characters','read-rogue-resonance-character.py')

@lru_cache(maxsize=1)
def load_profile():
 raw=PROFILE.read_bytes().replace(b'\r\n',b'\n')
 if hashlib.sha256(raw).hexdigest()!=PROFILE_SHA256:raise ValueError('拥有对象撤销冻结证据改变')
 return json.loads(raw)

def metadata(client,types,methods,inheritance=False):
 analyzer=sibling('event_payload_metadata','analyze-client-46.py')
 return analyzer.extract(SimpleNamespace(client_dir=client,types=types,fields=True,field_offsets=True,
  inheritance=inheritance,type_contains=[],method_indices=methods,method_rvas=[],method_name_contains=[],
  method_slots=[],string_slots=[],type_slots=[],disassemble=0,method_names=[]))

def metadata_slot(client,slot):
 analyzer=sibling('event_payload_slot_metadata','analyze-client-46.py')
 result=analyzer.extract(SimpleNamespace(client_dir=client,types=[],fields=False,field_offsets=False,inheritance=False,type_contains=[],method_indices=[],method_rvas=[],method_name_contains=[],method_slots=[],string_slots=[],type_slots=[int(slot,16)],disassemble=0,method_names=[]))
 if len(result['typeInfoSlots'])!=1:raise ValueError('事件None类型槽数量不符')
 return result['typeInfoSlots'][0]

def verify_native(client):
 p=load_profile();methods,size=characters.verify_native(client,p)
 expected=sorted([{k:m[k] for k in ['index','name','rva','typeIndex','typeName']} for m in p['nativeMethods']]+p['boundMethodIdentities'],key=lambda m:m['index'])
 actual=metadata(client,[t['name'] for t in p['matchedTypes']],[m['index'] for m in expected])
 if actual['selectedMethods']!=expected:raise ValueError('拥有对象撤销、上下文求值和明确绑定方法身份不符')
 declared={t['typeIndex']:t for t in actual['methods']}
 inheritedNames=[t['name'] for t in p['matchedTypes'] if 'selectedInstanceFields' in t]
 inherited={t['typeIndex']:t for t in metadata(client,inheritedNames,[],True)['methods']}
 for fact in p['matchedTypes']:
  t=declared[fact['typeIndex']]
  if t['name']!=fact['name']:raise ValueError('拥有对象撤销字段所属类型不符')
  if 'fields' in fact and t['fields']!=fact['fields']:raise ValueError('拥有对象撤销枚举完整声明不符')
  for key,rows in [('selectedFields',t['fields']),('selectedInstanceFields',inherited.get(fact['typeIndex'],{}).get('instanceFields',[]))]:
   if key not in fact:continue
   names={f['name'] for f in fact[key]}
   if [f for f in rows if f['name'] in names]!=fact[key]:raise ValueError('拥有参数与实体世界战斗模式链字段不符')
 components=sibling('owned_effects_parameters','read-rogue-resonance-components.py')
 import pefile
 pe=pefile.PE(str(client/'GameAssembly.dll'),fast_load=True)
 header=components.read_rva(pe,0x48b19b0,0x208)
 metadataRaw=(client/'StarRail_Data/il2cpp_data/Metadata/global-metadata.dat').read_bytes()
 methodTable=((struct.unpack_from('<I',header,0x14c)[0]+0xf3a04294)&0xffffffff)+0x208
 parameterTable=((struct.unpack_from('<I',header,0x30)[0]+0xdcf5fdbe)&0xffffffff)+0x208
 components=sibling('event_payload_components','read-rogue-resonance-components.py')
 import pefile
 pe=pefile.PE(str(client/'GameAssembly.dll'),fast_load=True)
 startup=(client/'StarRail_Data/il2cpp_data/Metadata/startup-metadata.dat').read_bytes()
 if hashlib.sha256(startup).hexdigest()!=p['nativeFiles']['startup-metadata.dat']:raise ValueError('拥有参数列表泛型启动元数据不符')
 header=components.read_rva(pe,0x48b19b0,0x208)
 table=struct.unpack_from('<I',header,0x164)[0]^0x7f5c5934
 array=struct.unpack('<Q',components.read_rva(pe,0x4931690,8))[0]-pe.OPTIONAL_HEADER.ImageBase
 if table!=int(p['genericClassTableOffsetInStartup'],16) or array!=int(p['genericInstantiationArrayRva'],16):raise ValueError('拥有参数列表泛型注册不符')
 def check_type(t,depth=0):
  if depth>6:raise ValueError('拥有参数列表泛型深度越界')
  raw=components.read_rva(pe,int(t['nativeRva'],16),8)
  if raw.hex()!=t['bytesHex'] or raw[6]!=t['kind'] or struct.unpack_from('<I',raw)[0]!=t['payload']:raise ValueError('拥有参数列表类型记录不符')
  if t['kind']!=21:return
  pos=table+t['payload']*8
  if pos!=int(t['startupRowOffset'],16) or startup[pos:pos+8].hex()!=t['startupRowHex']:raise ValueError('拥有参数列表泛型类记录不符')
  if struct.unpack_from('<ii',startup,pos)!=(t['genericDefinition'],t['instIndex']):raise ValueError('拥有参数列表泛型定义或实例索引不符')
  irva=array+t['instIndex']*16;raw=components.read_rva(pe,irva,16)
  if irva!=int(t['instRva'],16) or raw.hex()!=t['instHex']:raise ValueError('拥有参数列表泛型实例原件不符')
  count,reserved,argv=struct.unpack('<IIQ',raw)
  if count!=len(t['args']) or reserved!=0 or not 1<=count<=8:raise ValueError('拥有参数列表泛型参数数量不符')
  pointers=struct.unpack('<'+'Q'*count,components.read_rva(pe,argv-pe.OPTIONAL_HEADER.ImageBase,count*8))
  for pointer,arg in zip(pointers,t['args']):
   if pointer-pe.OPTIONAL_HEADER.ImageBase!=int(arg['nativeRva'],16):raise ValueError('拥有参数列表泛型参数地址不符')
   check_type(arg,depth+1)
 for f in p['genericFields']:
  check_type(f['record'])
 metadataRaw=(client/'StarRail_Data/il2cpp_data/Metadata/global-metadata.dat').read_bytes()
 slot=p['noneTypeSlot'];resolved=metadata_slot(client,slot['slot'])
 if resolved!=slot:raise ValueError('事件None所属类型用法不符')
 layout=p['noneStaticLayout']
 indexes=((struct.unpack_from('<I',header,0x180)[0]+0xe4dbe763)&0xffffffff)+0x208
 layouts=((struct.unpack_from('<I',header,0x9c)[0]+0xbc7ec9d7)&0xffffffff)+0x208
 offsets=(struct.unpack_from('<I',header,0x148)[0]^0x329e1172)+0x208
 pos=indexes+layout['typeIndex']*4
 if pos!=int(layout['layoutIndexOffset'],16) or metadataRaw[pos:pos+4].hex()!=layout['layoutIndexBytesHex']:raise ValueError('事件None布局索引原件不符')
 row=layouts+struct.unpack_from('<I',metadataRaw,pos)[0]*12
 if row!=int(layout['layoutRowOffset'],16) or metadataRaw[row:row+12].hex()!=layout['layoutRowHex']:raise ValueError('事件None布局行原件不符')
 start=struct.unpack_from('<I',metadataRaw,row+8)[0]
 if start!=layout['offsetStart']:raise ValueError('事件None偏移起点不符')
 for fact in layout['fields']:
  pos=offsets+(start+fact['ordinal'])*4;raw=metadataRaw[pos:pos+4]
  if pos!=int(fact['metadataOffset'],16) or raw.hex()!=fact['metadataBytesHex'] or struct.unpack('<I',raw)[0]!=int(fact['rawOffset'],16) or fact['rawOffset']!='0x400d4b0':raise ValueError('事件None原始布局标记不符')
 import capstone
 decoder=capstone.Cs(capstone.CS_ARCH_X86,capstone.CS_MODE_64);decoder.detail=True
 instructions={i.address-pe.OPTIONAL_HEADER.ImageBase:i for m in p['nativeMethods'] for i in decoder.disasm(bytes.fromhex(m['bytesHex']),pe.OPTIONAL_HEADER.ImageBase+int(m['rva'],16))}
 for address in [0xb82f19e,0xb82f39b]:
  i=instructions[address];operand=next(o for o in i.operands if o.type==capstone.x86.X86_OP_MEM and o.mem.base==capstone.x86.X86_REG_RIP)
  if i.address+i.size+operand.mem.disp-pe.OPTIONAL_HEADER.ImageBase!=int(slot['slot'],16):raise ValueError('事件None类初始化槽不符')
 for address in [0xb82f1b9,0xb82f3b2]:
  i=instructions[address]
  if i.mnemonic!='mov' or i.operands[1].type!=capstone.x86.X86_OP_MEM or i.operands[1].mem.disp!=0xd4b0:raise ValueError('事件None编译字段读取位移不符')
 zero=p['zeroInitializer'];raw=components.read_rva(pe,int(zero['sourceRva'],16),16)
 if raw.hex()!=zero['sourceBytesHex'] or struct.unpack_from('<q',raw,zero['sourceOffset'])[0]!=0 or zero['raw']!=0:raise ValueError('事件数值槽的零值初始化原件不符')
 for address in [0xb82f1f8,0xb82f21d,0xb82f23f]:
  i=instructions[address];operand=next(o for o in i.operands if o.type==capstone.x86.X86_OP_MEM and o.mem.base==capstone.x86.X86_REG_RIP)
  if i.address+i.size+operand.mem.disp-pe.OPTIONAL_HEADER.ImageBase!=int(zero['slotRva'],16):raise ValueError('事件数值槽的零值读取槽不符')
 helpers,helperSize=characters.verify_native(client,{'nativeFiles':p['nativeFiles'],'nativeMethods':p['runtimeMethods'],'nativeBytes':p['runtimeBytes']})
 return methods,size,helpers,helperSize

def analyze(a):
 client=a.client_dir.resolve();design=sibling('event_payload_output','read-client-design-data.py')
 output=design.outside_client(a.output,client)
 if ROOT/'build' not in output.parents:raise ValueError('只接受工程build下的拥有对象撤销证据')
 p=load_profile();methods,size,helpers,helperSize=verify_native(client)
 result={'passed':True,'matchedCompleteNativeMethods':methods,'matchedCompleteNativeBytes':size,
  'matchedCompleteRuntimeHelpers':helpers,'matchedCompleteRuntimeHelperBytes':helperSize,'matchedGenericFieldArguments':len(p['genericFields']),'matchedNoneStaticLayoutRecords':len(p['noneStaticLayout']['fields']),'matchedNoneTypeUsageSlots':1,'matchedZeroInitializerLiteralBytes':16,'matchedFieldTypes':len(p['matchedTypes']),'matchedBoundMethodIdentities':len(p['boundMethodIdentities']),**{k:v for k,v in p.items() if isinstance(v,bool)},
  'jsonInstalled':False,'clientExecutableStarted':False,'clientModified':False}
 output.parent.mkdir(parents=True,exist_ok=True);output.write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n','utf-8');return result

if __name__=='__main__':
 parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--client-dir',type=Path,required=True)
 parser.add_argument('--output',type=Path,default=ROOT/'build/rogue-event-payload-evidence.json')
 print(json.dumps(analyze(parser.parse_args()),ensure_ascii=False))
