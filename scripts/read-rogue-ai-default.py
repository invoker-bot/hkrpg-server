"""只读核对默认AI共享对象、两级优先级目录、Normal枚举、静态布局与发布顺序。"""
import argparse
from functools import lru_cache
import hashlib
import importlib.util
import json
import struct
from pathlib import Path
from types import SimpleNamespace

ROOT=Path(__file__).resolve().parents[1]
PROFILE=ROOT/'src/test/resources/fixtures/rogue-ai-default-4.6.json'
PROFILE_SHA256='ea45cf91fdc5cad76b317001419933561e4f294a4affda159794c6abfe33c113'

def sibling(name,filename):
 spec=importlib.util.spec_from_file_location(name,Path(__file__).with_name(filename))
 module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module);return module

characters=sibling('ai_default_characters','read-rogue-resonance-character.py')

@lru_cache(maxsize=1)
def load_profile():
 raw=PROFILE.read_bytes().replace(b'\r\n',b'\n')
 if hashlib.sha256(raw).hexdigest()!=PROFILE_SHA256:raise ValueError('默认AI冻结证据改变')
 return json.loads(raw)

def metadata(client,types,methods,inheritance=False):
 analyzer=sibling('ai_default_metadata','analyze-client-46.py')
 return analyzer.extract(SimpleNamespace(client_dir=client,types=types,fields=True,field_offsets=True,
  inheritance=inheritance,type_contains=[],method_indices=methods,method_rvas=[],method_name_contains=[],
  method_slots=[],string_slots=[],type_slots=[],disassemble=0,method_names=[]))

def verify_native(client):
 p=load_profile();methods,size=characters.verify_native(client,p)
 expected=sorted([{k:m[k] for k in ['index','name','rva','typeIndex','typeName']} for m in p['nativeMethods']]+p['boundMethodIdentities'],key=lambda m:m['index'])
 actual=metadata(client,[t['name'] for t in p['matchedTypes']],[m['index'] for m in expected])
 if actual['selectedMethods']!=expected:raise ValueError('默认AI初始化与优先级查询方法身份不符')
 declared={t['typeIndex']:t for t in actual['methods']}
 inheritedNames=[t['name'] for t in p['matchedTypes'] if 'selectedInstanceFields' in t]
 inherited={t['typeIndex']:t for t in metadata(client,inheritedNames,[],True)['methods']}
 for fact in p['matchedTypes']:
  t=declared[fact['typeIndex']]
  if t['name']!=fact['name']:raise ValueError('默认AI字段所属类型不符')
  if 'fields' in fact and t['fields']!=fact['fields']:raise ValueError('默认AI完整字段声明不符')
  for key,rows in [('selectedFields',t['fields']),('selectedInstanceFields',inherited.get(fact['typeIndex'],{}).get('instanceFields',[]))]:
   if key not in fact:continue
   names={f['name'] for f in fact[key]}
   if [f for f in rows if f['name'] in names]!=fact[key]:raise ValueError('默认AI静态目录字段不符')
 components=sibling('ai_default_components','read-rogue-resonance-components.py')
 import pefile
 pe=pefile.PE(str(client/'GameAssembly.dll'),fast_load=True)
 startup=(client/'StarRail_Data/il2cpp_data/Metadata/startup-metadata.dat').read_bytes()
 if hashlib.sha256(startup).hexdigest()!=p['nativeFiles']['startup-metadata.dat']:raise ValueError('默认AI目录泛型启动元数据不符')
 header=components.read_rva(pe,0x48b19b0,0x208)
 table=struct.unpack_from('<I',header,0x164)[0]^0x7f5c5934
 array=struct.unpack('<Q',components.read_rva(pe,0x4931690,8))[0]-pe.OPTIONAL_HEADER.ImageBase
 if table!=int(p['genericClassTableOffsetInStartup'],16) or array!=int(p['genericInstantiationArrayRva'],16):raise ValueError('默认AI目录泛型注册不符')
 def check_type(t,depth=0):
  if depth>6:raise ValueError('默认AI目录泛型深度越界')
  raw=components.read_rva(pe,int(t['nativeRva'],16),8)
  if raw.hex()!=t['bytesHex'] or raw[6]!=t['kind'] or struct.unpack_from('<I',raw)[0]!=t['payload']:raise ValueError('默认AI目录类型记录不符')
  if t['kind']!=21:return
  pos=table+t['payload']*8
  if pos!=int(t['startupRowOffset'],16) or startup[pos:pos+8].hex()!=t['startupRowHex']:raise ValueError('默认AI目录泛型类记录不符')
  if struct.unpack_from('<ii',startup,pos)!=(t['genericDefinition'],t['instIndex']):raise ValueError('默认AI目录泛型定义或实例索引不符')
  irva=array+t['instIndex']*16;raw=components.read_rva(pe,irva,16)
  if irva!=int(t['instRva'],16) or raw.hex()!=t['instHex']:raise ValueError('默认AI目录泛型实例原件不符')
  count,reserved,argv=struct.unpack('<IIQ',raw)
  if count!=len(t['args']) or reserved!=0 or not 1<=count<=8:raise ValueError('默认AI目录泛型参数数量不符')
  pointers=struct.unpack('<'+'Q'*count,components.read_rva(pe,argv-pe.OPTIONAL_HEADER.ImageBase,count*8))
  for pointer,arg in zip(pointers,t['args']):
   if pointer-pe.OPTIONAL_HEADER.ImageBase!=int(arg['nativeRva'],16):raise ValueError('默认AI目录泛型参数地址不符')
   check_type(arg,depth+1)
 for f in p['genericFields']:
  check_type(f['record'])
 import capstone
 decoder=capstone.Cs(capstone.CS_ARCH_X86,capstone.CS_MODE_64);decoder.detail=True
 instructions={i.address-pe.OPTIONAL_HEADER.ImageBase:i for m in p['nativeMethods'] for i in decoder.disasm(bytes.fromhex(m['bytesHex']),pe.OPTIONAL_HEADER.ImageBase+int(m['rva'],16))}
 for row in p['ripBindings']:
  i=instructions[int(row['instructionRva'],16)];rip=next(o for o in i.operands if o.type==capstone.x86.X86_OP_MEM and o.mem.base==capstone.x86.X86_REG_RIP)
  if i.address+i.size+rip.mem.disp-pe.OPTIONAL_HEADER.ImageBase!=int(row['slot'],16):raise ValueError('默认AI初始化或目录字面量槽不符')
 for row in p['leafFragments']:
  raw=components.read_rva(pe,int(row['rva'],16),len(bytes.fromhex(row['bytesHex'])))
  if raw.hex()!=row['bytesHex'] or hashlib.sha256(raw).hexdigest()!=row['sha256'] or raw!=b'\xc3':raise ValueError('默认AI无写入构造叶片段不符')
 metadataRaw=(client/'StarRail_Data/il2cpp_data/Metadata/global-metadata.dat').read_bytes()
 indexes=((struct.unpack_from('<I',header,0x180)[0]+0xe4dbe763)&0xffffffff)+0x208;layouts=((struct.unpack_from('<I',header,0x9c)[0]+0xbc7ec9d7)&0xffffffff)+0x208;offsets=(struct.unpack_from('<I',header,0x148)[0]^0x329e1172)+0x208
 for row in p['staticLayouts']:
  pos=indexes+row['typeIndex']*4
  if pos!=int(row['layoutIndexOffset'],16) or metadataRaw[pos:pos+4].hex()!=row['layoutIndexBytesHex']:raise ValueError('默认AI静态布局索引不符')
  pos=layouts+struct.unpack_from('<I',metadataRaw,pos)[0]*12
  if pos!=int(row['layoutRowOffset'],16) or metadataRaw[pos:pos+12].hex()!=row['layoutRowHex'] or struct.unpack_from('<I',metadataRaw,pos+8)[0]!=row['offsetStart']:raise ValueError('默认AI静态布局记录不符')
  pos=offsets+(row['offsetStart']+row['ordinal'])*4
  if pos!=int(row['metadataOffset'],16) or metadataRaw[pos:pos+4].hex()!=row['metadataBytesHex'] or struct.unpack_from('<I',metadataRaw,pos)[0]!=int(row['rawOffset'],16):raise ValueError('默认AI静态引用原始标记不符')
  field=declared[row['typeIndex']]['fields'][row['ordinal']]
  if not field['isStatic'] or field['nativeType']['kind']!=18:raise ValueError('默认AI共享引用字段属性不符')
 analyzer=sibling('ai_default_slots','analyze-client-46.py')
 slots=analyzer.extract(SimpleNamespace(client_dir=client,types=[],fields=False,field_offsets=False,inheritance=False,type_contains=[],method_indices=[],method_rvas=[],method_name_contains=[],method_names=[],method_slots=[],string_slots=[int(x['slot'],16) for x in p['stringLiteralSlots']],type_slots=[int(x['slot'],16) for x in p['typeInfoSlots']],disassemble=0))
 if sorted(slots['stringLiteralSlots'],key=lambda r:r['slot'])!=sorted(p['stringLiteralSlots'],key=lambda r:r['slot']) or slots['typeInfoSlots']!=p['typeInfoSlots']:raise ValueError('默认AI目录键、默认键或对象分配类型槽不符')
 for f in p['genericFields']:
  field=next(x for x in declared[f['ownerTypeIndex']]['fields'] if x['name']==f['field']['name'])
  if field!=f['field'] or int(f['record']['nativeRva'],16)!=0x4ffdd60+field['nativeTypeIndex']*8:raise ValueError('默认AI两级目录字段与泛型记录关联不符')
 registrations=sibling('ai_default_registrations','read-rogue-modifier-registrations.py');registrations.verify_native(client)
 return methods,size

def analyze(a):
 client=a.client_dir.resolve();design=sibling('ai_default_output','read-client-design-data.py');output=design.outside_client(a.output,client)
 if ROOT/'build' not in output.parents:raise ValueError('默认AI证据仅输出到工程build')
 p=load_profile();methods,size=verify_native(client)
 result={'passed':True,'matchedCompleteNativeMethods':methods,'matchedCompleteNativeBytes':size,'matchedLeafFragments':len(p['leafFragments']),'matchedLeafFragmentBytes':p['leafBytes'],'matchedFieldTypes':len(p['matchedTypes']),'matchedBoundMethodIdentities':len(p['boundMethodIdentities']),'matchedStaticLayoutRecords':len(p['staticLayouts']),'matchedGenericFields':len(p['genericFields']),'matchedStringLiteralSlots':len(p['stringLiteralSlots']),'matchedTypeInfoSlots':len(p['typeInfoSlots']),**{k:v for k,v in p.items() if isinstance(v,bool)},'jsonInstalled':False,'clientExecutableStarted':False,'clientModified':False}
 output.parent.mkdir(parents=True,exist_ok=True);output.write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n','utf-8');return result
if __name__=='__main__':
 parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--client-dir',type=Path,required=True);parser.add_argument('--output',type=Path,default=ROOT/'build/rogue-ai-default-evidence.json');print(json.dumps(analyze(parser.parse_args()),ensure_ascii=False))
