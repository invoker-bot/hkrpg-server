"""只读核对普通引用对象池的完整来源函数、辅助体、接口、容量属性与泛型；世界和原生工厂仍待绑定。"""
import argparse
from functools import lru_cache
import hashlib
import importlib.util
import json
import struct
from pathlib import Path
from types import SimpleNamespace

ROOT=Path(__file__).resolve().parents[1]
PROFILE=ROOT/'src/test/resources/fixtures/rogue-reference-pool-4.6.json'
PROFILE_SHA256='3889d560142fc8dd9311902a3320ca1fff2c12e06fc4cadeef48758ee02c71b4'

def sibling(name,filename):
 spec=importlib.util.spec_from_file_location(name,Path(__file__).with_name(filename))
 module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module);return module

characters=sibling('reference_pool_characters','read-rogue-resonance-character.py')

@lru_cache(maxsize=1)
def load_profile():
 raw=PROFILE.read_bytes().replace(b'\r\n',b'\n')
 if hashlib.sha256(raw).hexdigest()!=PROFILE_SHA256:raise ValueError('拥有对象撤销冻结证据改变')
 return json.loads(raw)

def metadata(client,types,methods,inheritance=False):
 analyzer=sibling('reference_pool_metadata','analyze-client-46.py')
 return analyzer.extract(SimpleNamespace(client_dir=client,types=types,fields=True,field_offsets=True,
  inheritance=inheritance,type_contains=[],method_indices=methods,method_rvas=[],method_name_contains=[],
  method_slots=[],string_slots=[],type_slots=[],disassemble=0,method_names=[]))

def verify_native(client):
 p=load_profile();methods,size=characters.verify_native(client,p)
 expected=sorted([{k:m[k] for k in ['index','name','rva','typeIndex','typeName']} for m in p['nativeMethods']+p['auxiliaryMethods']]+p['boundMethodIdentities'],key=lambda m:m['index'])
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
 components=sibling('reference_pool_components','read-rogue-resonance-components.py')
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
 auxiliary,auxiliarySize=characters.verify_native(client,{'nativeFiles':p['nativeFiles'],'nativeMethods':p['auxiliaryMethods'],'nativeBytes':p['auxiliaryBytes']})
 helpers,helperSize=characters.verify_native(client,{'nativeFiles':p['nativeFiles'],'nativeMethods':p['runtimeMethods'],'nativeBytes':p['runtimeBytes']})
 for row in p['leafFragments']:
  raw=components.read_rva(pe,int(row['rva'],16),len(bytes.fromhex(row['bytesHex'])))
  if raw.hex()!=row['bytesHex'] or hashlib.sha256(raw).hexdigest()!=row['sha256']:raise ValueError('对象池叶片段字节不符')
 import capstone
 decoder=capstone.Cs(capstone.CS_ARCH_X86,capstone.CS_MODE_64);decoder.detail=True
 instructions={i.address-pe.OPTIONAL_HEADER.ImageBase:i for m in p['nativeMethods']+p['runtimeMethods']+p['auxiliaryMethods']+p['leafFragments'] for i in decoder.disasm(bytes.fromhex(m['bytesHex']),pe.OPTIONAL_HEADER.ImageBase+int(m['rva'],16))}
 for row in p['ripBindings']:
  i=instructions[int(row['instructionRva'],16)]
  rip=next(o for o in i.operands if o.type==capstone.x86.X86_OP_MEM and o.mem.base==capstone.x86.X86_REG_RIP)
  if i.address+i.size+rip.mem.disp-pe.OPTIONAL_HEADER.ImageBase!=int(row['slot'],16):raise ValueError('对象池接口、泛型或检查开关读取槽不符')
 analyzer=sibling('reference_pool_slot_metadata','analyze-client-46.py')
 slots=analyzer.extract(SimpleNamespace(client_dir=client,types=[],fields=False,field_offsets=False,inheritance=False,type_contains=[],method_indices=[],method_rvas=[],method_name_contains=[],method_slots=[],string_slots=[],type_slots=[int(x['slot'],16) for x in p['poolTypeUsageSlots']],disassemble=0,method_names=[]))
 if slots['typeInfoSlots']!=p['poolTypeUsageSlots']:raise ValueError('对象池接口类型槽归属不符')
 metadataRaw=(client/'StarRail_Data/il2cpp_data/Metadata/global-metadata.dat').read_bytes()
 layout=p['staticLayout'];indexes=((struct.unpack_from('<I',header,0x180)[0]+0xe4dbe763)&0xffffffff)+0x208;layouts=((struct.unpack_from('<I',header,0x9c)[0]+0xbc7ec9d7)&0xffffffff)+0x208;offsets=(struct.unpack_from('<I',header,0x148)[0]^0x329e1172)+0x208
 pos=indexes+layout['typeIndex']*4
 if pos!=int(layout['layoutIndexOffset'],16) or metadataRaw[pos:pos+4].hex()!=layout['layoutIndexBytesHex']:raise ValueError('对象池静态布局索引不符')
 pos=layouts+struct.unpack_from('<I',metadataRaw,pos)[0]*12
 if pos!=int(layout['layoutRowOffset'],16) or metadataRaw[pos:pos+12].hex()!=layout['layoutRowHex'] or struct.unpack_from('<I',metadataRaw,pos+8)[0]!=layout['offsetStart']:raise ValueError('对象池静态布局行不符')
 for row in layout['fields']:
  pos=offsets+(layout['offsetStart']+row['ordinal'])*4
  if pos!=int(row['metadataOffset'],16) or metadataRaw[pos:pos+4].hex()!=row['metadataBytesHex'] or struct.unpack_from('<I',metadataRaw,pos)[0]!=int(row['rawOffset'],16):raise ValueError('对象池静态字段原始标记不符')
 pairs=((struct.unpack_from('<I',header,0x190)[0]+0xed791073)&0xffffffff)+0x208;specs=((struct.unpack_from('<I',header,0x38)[0]+0xf778f1ab)&0xffffffff)+0x208
 for row in p['poolMethodSpecs']:
  pos=pairs+row['pairIndex']*8
  if pos!=int(row['usagePairOffset'],16) or metadataRaw[pos:pos+8].hex()!=row['usagePairHex']:raise ValueError('对象池泛型用法原件不符')
  first,second=struct.unpack_from('<II',metadataRaw,pos);idx=row['pairIndex']
  key=(((idx*0x87c3)^0x5fa3fad3)*0x334fb2ba+0x454d10d89e6a02a)&0xffffffffffffffff;key=(((key>>14)*0x102533d7+0x58972d9863a807)&0xffffffffffffffff)>>23
  destination=((first^0x6907ab9a)-key)&0xffffffff;source=(second-key+0xab3a6cb5)&0xffffffff
  if destination!=row['destination'] or source>>29!=6 or source&0x1fffffff!=row['payload'] or 0x944d430+destination*8!=int(row['slot'],16):raise ValueError('对象池泛型用法解码不符')
  pos=specs+row['payload']*12;spec=row['methodSpec']
  if pos!=int(row['methodSpecRowOffset'],16) or metadataRaw[pos:pos+12].hex()!=row['methodSpecRowHex'] or struct.unpack_from('<iii',metadataRaw,pos)!=(spec['methodDefinition'],spec['classInst'],spec['methodInst']):raise ValueError('对象池MethodSpec原件或角色不符')
  for inst in row['instRecords']:
   index=spec['classInst' if inst['role']=='class' else 'methodInst'];rva=array+index*16;raw=components.read_rva(pe,rva,16)
   if index!=inst['index'] or rva!=int(inst['rva'],16) or raw.hex()!=inst['raw']:raise ValueError('对象池泛型实例原件不符')
   count,reserved,argv=struct.unpack('<IIQ',raw)
   if count!=len(inst['arguments']) or reserved!=0 or not 1<=count<=8:raise ValueError('对象池泛型参数数量不符')
   pointers=struct.unpack('<'+'Q'*count,components.read_rva(pe,argv-pe.OPTIONAL_HEADER.ImageBase,count*8))
   for pointer,arg in zip(pointers,inst['arguments']):
    if pointer-pe.OPTIONAL_HEADER.ImageBase!=int(arg['nativeRva'],16):raise ValueError('对象池泛型参数指针不符')
    check_type(arg)
 departed=sibling('reference_pool_departed','read-rogue-departed-transition.py');departed.verify_native(client)
 return methods,size,helpers,helperSize,auxiliary,auxiliarySize

def analyze(a):
 client=a.client_dir.resolve();design=sibling('reference_pool_output','read-client-design-data.py');output=design.outside_client(a.output,client)
 if ROOT/'build' not in output.parents:raise ValueError('对象池证据仅输出到工程build')
 p=load_profile();methods,size,helpers,helperSize,auxiliary,auxiliarySize=verify_native(client)
 result={'passed':True,'matchedCompleteNativeMethods':methods,'matchedCompleteNativeBytes':size,'matchedCompleteRuntimeHelpers':helpers,'matchedCompleteRuntimeHelperBytes':helperSize,'matchedCompleteAuxiliaryMethods':auxiliary,'matchedCompleteAuxiliaryBytes':auxiliarySize,'matchedLeafFragments':len(p['leafFragments']),'matchedLeafFragmentBytes':p['leafBytes'],'matchedGenericFieldArguments':len(p['genericFields']),'matchedFieldTypes':len(p['matchedTypes']),'matchedBoundMethodIdentities':len(p['boundMethodIdentities']),'matchedTypeUsageSlots':len(p['poolTypeUsageSlots']),'matchedPoolMethodSpecs':len(p['poolMethodSpecs']),'matchedDepartedPayloadMethodSpecs':2,'matchedStaticLayoutRecords':len(p['staticLayout']['fields']),**{k:v for k,v in p.items() if isinstance(v,bool)},'jsonInstalled':False,'clientExecutableStarted':False,'clientModified':False}
 output.parent.mkdir(parents=True,exist_ok=True);output.write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n','utf-8');return result

if __name__=='__main__':
 parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--client-dir',type=Path,required=True);parser.add_argument('--output',type=Path,default=ROOT/'build/rogue-reference-pool-evidence.json');print(json.dumps(analyze(parser.parse_args()),ensure_ascii=False))
