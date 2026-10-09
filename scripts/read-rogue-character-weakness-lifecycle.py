"""只读核对弱点创建、静态目录、查询和释放；实际世界与通知调度仍待完成。"""
import argparse
from functools import lru_cache
import hashlib
import importlib.util
import json
import struct
from pathlib import Path
from types import SimpleNamespace

ROOT=Path(__file__).resolve().parents[1]
PROFILE=ROOT/'src/test/resources/fixtures/rogue-character-weakness-lifecycle-4.6.json'
PROFILE_SHA256='138a5ea2e26943a0d5c1d5f2c5b8a1ff8afae32c030dd82cc8cf14d731145732'

def sibling(name,filename):
 spec=importlib.util.spec_from_file_location(name,Path(__file__).with_name(filename))
 module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module);return module

characters=sibling('character_weakness_lifecycle_characters','read-rogue-resonance-character.py')

@lru_cache(maxsize=1)
def load_profile():
 raw=PROFILE.read_bytes().replace(b'\r\n',b'\n')
 if hashlib.sha256(raw).hexdigest()!=PROFILE_SHA256:raise ValueError('弱点恢复冻结证据改变')
 return json.loads(raw)

def metadata(client,types,methods,inheritance=False):
 analyzer=sibling('character_weakness_lifecycle_metadata','analyze-client-46.py')
 return analyzer.extract(SimpleNamespace(client_dir=client,types=types,fields=True,field_offsets=True,
  inheritance=inheritance,type_contains=[],method_indices=methods,method_rvas=[],method_name_contains=[],
  method_slots=[],string_slots=[],type_slots=[],disassemble=0,method_names=[]))

def verify_native(client):
 p=load_profile();methods,size=characters.verify_native(client,p)
 expected=[{k:m[k] for k in ['index','name','rva','typeIndex','typeName']} for m in p['nativeMethods']]+p['boundMethodIdentities']
 actual=metadata(client,[t['name'] for t in p['matchedTypes']],[m['index'] for m in expected])
 if actual['selectedMethods']!=expected:raise ValueError('弱点恢复及实际刷新绑定的方法身份不符')
 declared={t['typeIndex']:t for t in actual['methods']}
 inheritedNames=[t['name'] for t in p['matchedTypes'] if 'selectedInstanceFields' in t]
 inherited={t['typeIndex']:t for t in metadata(client,inheritedNames,[],True)['methods']}
 for fact in p['matchedTypes']:
  t=declared[fact['typeIndex']]
  if t['name']!=fact['name']:raise ValueError('弱点恢复字段所属类型不符')
  if 'fields' in fact and t['fields']!=fact['fields']:raise ValueError('弱点恢复类型完整声明不符')
  for key,rows in [('selectedFields',t['fields']),('selectedInstanceFields',inherited.get(fact['typeIndex'],{}).get('instanceFields',[]))]:
   if key not in fact:continue
   names={f['name'] for f in fact[key]}
   if [f for f in rows if f['name'] in names]!=fact[key]:raise ValueError('弱点状态或特殊列表字段不符')
 components=sibling('character_weakness_lifecycle_components','read-rogue-resonance-components.py')
 import pefile
 pe=pefile.PE(str(client/'GameAssembly.dll'),fast_load=True)
 startup=(client/'StarRail_Data/il2cpp_data/Metadata/startup-metadata.dat').read_bytes()
 if hashlib.sha256(startup).hexdigest()!=p['nativeFiles']['startup-metadata.dat']:raise ValueError('弱点恢复泛型启动元数据不符')
 header=components.read_rva(pe,0x48b19b0,0x208)
 table=struct.unpack_from('<I',header,0x164)[0]^0x7f5c5934
 array=struct.unpack('<Q',components.read_rva(pe,0x4931690,8))[0]-pe.OPTIONAL_HEADER.ImageBase
 if table!=int(p['genericClassTableOffsetInStartup'],16) or array!=int(p['genericInstantiationArrayRva'],16):raise ValueError('弱点恢复泛型注册不符')
 def check_type(t,depth=0):
  if depth>6:raise ValueError('弱点恢复泛型深度越界')
  raw=components.read_rva(pe,int(t['nativeRva'],16),8)
  if raw.hex()!=t['bytesHex'] or raw[6]!=t['kind'] or struct.unpack_from('<I',raw)[0]!=t['payload']:raise ValueError('弱点恢复类型记录不符')
  if t['kind']!=21:return
  pos=table+t['payload']*8
  if pos!=int(t['startupRowOffset'],16) or startup[pos:pos+8].hex()!=t['startupRowHex']:raise ValueError('弱点恢复泛型类记录不符')
  if struct.unpack_from('<ii',startup,pos)!=(t['genericDefinition'],t['instIndex']):raise ValueError('弱点恢复泛型定义或实例索引不符')
  irva=array+t['instIndex']*16;raw=components.read_rva(pe,irva,16)
  if irva!=int(t['instRva'],16) or raw.hex()!=t['instHex']:raise ValueError('弱点恢复泛型实例原件不符')
  count,reserved,argv=struct.unpack('<IIQ',raw)
  if count!=len(t['args']) or reserved!=0 or not 1<=count<=8:raise ValueError('弱点恢复泛型参数数量不符')
  pointers=struct.unpack('<'+'Q'*count,components.read_rva(pe,argv-pe.OPTIONAL_HEADER.ImageBase,count*8))
  for pointer,arg in zip(pointers,t['args']):
   if pointer-pe.OPTIONAL_HEADER.ImageBase!=int(arg['nativeRva'],16):raise ValueError('弱点恢复泛型参数地址不符')
   check_type(arg,depth+1)
 for f in p['genericFields']:
  check_type(f['record'])
 metadataRaw=(client/'StarRail_Data/il2cpp_data/Metadata/global-metadata.dat').read_bytes()
 import capstone
 decoder=capstone.Cs(capstone.CS_ARCH_X86,capstone.CS_MODE_64);decoder.detail=True
 directory=pe.OPTIONAL_HEADER.DATA_DIRECTORY[pefile.DIRECTORY_ENTRY['IMAGE_DIRECTORY_ENTRY_EXCEPTION']]
 starts={s for s,_,_ in struct.iter_unpack('<III',components.read_rva(pe,directory.VirtualAddress,directory.Size))}
 for leaf in p['nativeLeafFragments']:
  start=int(leaf['rva'],16);raw=components.read_rva(pe,start,leaf['length']);instructions=list(decoder.disasm(raw,pe.OPTIONAL_HEADER.ImageBase+start))
  if start in starts or raw.hex()!=leaf['bytesHex'] or hashlib.sha256(raw).hexdigest()!=leaf['sha256'] or not instructions or instructions[-1].mnemonic!='ret' or sum(i.size for i in instructions)!=leaf['length'] or any(i.mnemonic=='call' or i.mnemonic.startswith('j') for i in instructions):raise ValueError('弱点直线叶方法片段不符')
  if [{'rva':hex(i.address-pe.OPTIONAL_HEADER.ImageBase),'mnemonic':i.mnemonic,'operand':i.op_str} for i in instructions]!=leaf['instructions']:raise ValueError('弱点叶方法片段指令不符')
 initializer=next(decoder.disasm(bytes.fromhex(p['nativeLeafFragments'][0]['bytesHex']),pe.OPTIONAL_HEADER.ImageBase+int(p['nativeLeafFragments'][0]['rva'],16)))
 staticSlot=initializer.address+initializer.size+initializer.operands[0].mem.disp-pe.OPTIONAL_HEADER.ImageBase
 if initializer.operands[1].imm!=p['initialCapacity'] or staticSlot!=int(p['initialCapacityStaticSlot'],16):raise ValueError('弱点静态容量初始化不符')
 ctor=next(m for m in p['nativeMethods'] if m['index']==534687);reads=[]
 for i in decoder.disasm(bytes.fromhex(ctor['bytesHex']),pe.OPTIONAL_HEADER.ImageBase+int(ctor['rva'],16)):
  if i.mnemonic=='mov' and i.op_str.startswith('edx, dword ptr [rip'):
   target=i.address+i.size+i.operands[1].mem.disp-pe.OPTIONAL_HEADER.ImageBase
   if target!=staticSlot:raise ValueError('弱点构造的容量读取不符')
   reads.append({'instructionRva':hex(i.address-pe.OPTIONAL_HEADER.ImageBase),'slot':hex(target)})
 if reads!=p['initialCapacityReads'] or len(reads)!=8:raise ValueError('弱点八次容量读取不符')
 c=p['catalogStaticData'];reg=struct.unpack('<14Q',components.read_rva(pe,0x49316f0,112));slotBase=reg[4]-pe.OPTIONAL_HEADER.ImageBase
 if slotBase!=int(c['fieldInfoSlotBase'],16) or slotBase+c['slotIndex']*8!=int(c['slot'],16):raise ValueError('弱点目录字段槽注册不符')
 pairs=((struct.unpack_from('<I',header,0x190)[0]+0xed791073)&0xffffffff)+0x208;pair=c['usage']['pairIndex'];offset=pairs+pair*8
 if offset!=int(c['usage']['pairOffset'],16) or metadataRaw[offset:offset+8].hex()!=c['usage']['pairHex']:raise ValueError('弱点目录使用记录不符')
 first,second=struct.unpack_from('<II',metadataRaw,offset);key=(((pair*0x87c3)^0x5fa3fad3)*0x334fb2ba+0x454d10d89e6a02a)&0xffffffffffffffff;key=(((key>>14)*0x102533d7+0x58972d9863a807)&0xffffffffffffffff)>>23
 destination=((first^0x6907ab9a)-key)&0xffffffff;source=(second-key+0xab3a6cb5)&0xffffffff
 if destination!=c['slotIndex'] or source>>29!=4 or source&0x1fffffff!=c['usage']['sourcePayload']:raise ValueError('弱点目录字段使用种类不符')
 payload=c['usage']['sourcePayload'];table=((struct.unpack_from('<I',header,0xa0)[0]+0x85eca8f2)&0xffffffff)+0x208;offset=table+payload*8
 if offset!=int(c['fieldReferenceRowOffset'],16) or metadataRaw[offset:offset+8].hex()!=c['fieldReferenceRowHex']:raise ValueError('弱点目录字段引用不符')
 value=(payload*0x28651e907697+0x32d18e4d3c97cdf)&0xffffffffffffffff;key=(((value>>20)*0x1ae14d8d)&0xffffffffffffffff)>>18;key=(key^0x746f8bfb)&0xffffffff
 nativeType=((struct.unpack_from('<I',metadataRaw,offset+4)[0]+0xe8d5913d)&0xffffffff)^key;ordinal=((struct.unpack_from('<I',metadataRaw,offset)[0]+0x94b14d70)&0xffffffff)^key
 if nativeType!=c['nativeTypeIndex'] or ordinal!=c['fieldOrdinal']:raise ValueError('弱点目录字段序号不符')
 raw=components.read_rva(pe,0x4ffdd60+nativeType*8,8)
 if raw.hex()!=c['nativeTypeRecordHex'] or raw[6] not in (17,18) or struct.unpack_from('<I',raw)[0]!=c['declaringTypeIndex']:raise ValueError('弱点目录字段所属类型不符')
 types=(struct.unpack_from('<I',header,0x84)[0]^0x68531d3f)+0x208;offset=types+c['declaringTypeIndex']*70
 if offset!=int(c['declaringTypeRowOffset'],16) or metadataRaw[offset:offset+70].hex()!=c['declaringTypeRowHex']:raise ValueError('弱点目录声明原始记录不符')
 fieldStart=(struct.unpack_from('<I',metadataRaw,offset+0x20)[0]+0x8b7ac79c)&0xffffffff;fieldCount=(struct.unpack_from('<H',metadataRaw,offset+0x32)[0]+0x444d)&65535
 if ordinal>=fieldCount or fieldStart+ordinal!=c['fieldIndex']:raise ValueError('弱点目录声明字段索引不符')
 defaults=(struct.unpack_from('<I',header,0x1fc)[0]^0x6238cdb0)+0x208;defaultSize=struct.unpack_from('<I',header,0x1dc)[0]^0x3e0c72f0
 hits=[pos for pos in range(defaults,defaults+(defaultSize//12)*12,12) if struct.unpack_from('<i',metadataRaw,pos+8)[0]==c['fieldIndex']]
 if len(hits)!=1 or hits[0]!=int(c['defaultRowOffset'],16) or metadataRaw[hits[0]:hits[0]+12].hex()!=c['defaultRowHex']:raise ValueError('弱点目录静态数据行不符')
 valueOffset=struct.unpack_from('<i',metadataRaw,hits[0]+4)[0];valueBase=((struct.unpack_from('<I',header,0x3c)[0]+0x978be7a5)&0xffffffff)+0x208;offset=valueBase+valueOffset
 if valueOffset<0 or offset!=int(c['defaultDataOffset'],16) or metadataRaw[offset:offset+28].hex()!=c['bytesHex'] or list(struct.unpack_from('<7i',metadataRaw,offset))!=c['values']:raise ValueError('弱点目录七项原始值不符')
 table=p['metadataUsageSwitchTable'];rva=int(table['rva'],16);raw=components.read_rva(pe,rva,32)
 if raw.hex()!=table['bytesHex'] or [hex(rva+i) for i in struct.unpack('<8i',raw)]!=table['targets'] or table['targets'][4]!='0x3d3339c':raise ValueError('弱点目录字段使用分派表不符')
 catalogInit=next(m for m in p['nativeMethods'] if m['index']==534732);instruction=next(i for i in decoder.disasm(bytes.fromhex(catalogInit['bytesHex']),pe.OPTIONAL_HEADER.ImageBase+int(catalogInit['rva'],16)) if i.address-pe.OPTIONAL_HEADER.ImageBase==0xb572ad4)
 if instruction.address+instruction.size+instruction.operands[1].mem.disp-pe.OPTIONAL_HEADER.ImageBase!=int(c['slot'],16):raise ValueError('弱点目录初始化的字段槽引用不符')
 helpers,helperSize=characters.verify_native(client,{'nativeFiles':p['nativeFiles'],'nativeMethods':p['runtimeMethods'],'nativeBytes':p['runtimeBytes']})
 return methods,size,helpers,helperSize

def analyze(a):
 client=a.client_dir.resolve();design=sibling('character_weakness_lifecycle_output','read-client-design-data.py')
 output=design.outside_client(a.output,client)
 if ROOT/'build' not in output.parents:raise ValueError('只接受工程build下的弱点恢复证据')
 p=load_profile();methods,size,helpers,helperSize=verify_native(client)
 result={'passed':True,'matchedCompleteNativeMethods':methods,'matchedCompleteNativeBytes':size,
  'matchedCompleteRuntimeHelpers':helpers,'matchedCompleteRuntimeHelperBytes':helperSize,'matchedNativeLeafFragments':len(p['nativeLeafFragments']),'matchedNativeLeafBytes':sum(x['length'] for x in p['nativeLeafFragments']),'matchedInitialCapacityReads':len(p['initialCapacityReads']),'matchedCatalogValues':len(p['catalogStaticData']['values']),'matchedFieldTypes':len(p['matchedTypes']),'matchedBoundMethodIdentities':len(p['boundMethodIdentities']),'matchedGenericFieldArguments':len(p['genericFields']),'matchedMetadataUsageSwitchTargets':len(p['metadataUsageSwitchTable']['targets']),**{k:v for k,v in p.items() if isinstance(v,bool)},
  'jsonInstalled':False,'clientExecutableStarted':False,'clientModified':False}
 output.parent.mkdir(parents=True,exist_ok=True);output.write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n','utf-8');return result

if __name__=='__main__':
 parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--client-dir',type=Path,required=True)
 parser.add_argument('--output',type=Path,default=ROOT/'build/rogue-character-weakness-lifecycle-evidence.json')
 print(json.dumps(analyze(parser.parse_args()),ensure_ascii=False))
