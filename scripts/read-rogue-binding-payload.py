"""只读核对绑定载荷Clear、无写入构造叶片段、值结构与共享String.Empty字段。"""
import argparse
from functools import lru_cache
import hashlib
import importlib.util
import json
import struct
from pathlib import Path
from types import SimpleNamespace

ROOT=Path(__file__).resolve().parents[1]
PROFILE=ROOT/'src/test/resources/fixtures/rogue-binding-payload-4.6.json'
PROFILE_SHA256='35b0c6e8f1675ff2b316f2160aeec0aebf98d52c045d6e3fa97628dba4024d21'

def sibling(name,filename):
 spec=importlib.util.spec_from_file_location(name,Path(__file__).with_name(filename))
 module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module);return module

characters=sibling('binding_payload_characters','read-rogue-resonance-character.py')

@lru_cache(maxsize=1)
def load_profile():
 raw=PROFILE.read_bytes().replace(b'\r\n',b'\n')
 if hashlib.sha256(raw).hexdigest()!=PROFILE_SHA256:raise ValueError('拥有对象撤销冻结证据改变')
 return json.loads(raw)

def metadata(client,types,methods,inheritance=False):
 analyzer=sibling('binding_payload_metadata','analyze-client-46.py')
 return analyzer.extract(SimpleNamespace(client_dir=client,types=types,fields=True,field_offsets=True,
  inheritance=inheritance,type_contains=[],method_indices=methods,method_rvas=[],method_name_contains=[],
  method_slots=[],string_slots=[],type_slots=[],disassemble=0,method_names=[]))

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
 components=sibling('binding_payload_components','read-rogue-resonance-components.py')
 import pefile,capstone
 pe=pefile.PE(str(client/'GameAssembly.dll'),fast_load=True);header=components.read_rva(pe,0x48b19b0,0x208)
 metadataRaw=(client/'StarRail_Data/il2cpp_data/Metadata/global-metadata.dat').read_bytes()
 for row in p['leafFragments']:
  raw=components.read_rva(pe,int(row['rva'],16),len(bytes.fromhex(row['bytesHex'])))
  if raw.hex()!=row['bytesHex'] or hashlib.sha256(raw).hexdigest()!=row['sha256'] or raw!=b'\xc3':raise ValueError('绑定载荷构造器叶返回片段不符')
 layout=p['emptyStaticLayout'];indexes=((struct.unpack_from('<I',header,0x180)[0]+0xe4dbe763)&0xffffffff)+0x208;layouts=((struct.unpack_from('<I',header,0x9c)[0]+0xbc7ec9d7)&0xffffffff)+0x208;offsets=(struct.unpack_from('<I',header,0x148)[0]^0x329e1172)+0x208
 pos=indexes+layout['typeIndex']*4
 if layout['typeIndex']!=330 or pos!=int(layout['layoutIndexOffset'],16) or metadataRaw[pos:pos+4].hex()!=layout['layoutIndexBytesHex']:raise ValueError('String.Empty布局索引不符')
 pos=layouts+struct.unpack_from('<I',metadataRaw,pos)[0]*12
 if pos!=int(layout['layoutRowOffset'],16) or metadataRaw[pos:pos+12].hex()!=layout['layoutRowHex'] or struct.unpack_from('<I',metadataRaw,pos+8)[0]!=layout['offsetStart']:raise ValueError('String.Empty布局行不符')
 for row in layout['fields']:
  pos=offsets+(layout['offsetStart']+row['ordinal'])*4
  if pos!=int(row['metadataOffset'],16) or metadataRaw[pos:pos+4].hex()!=row['metadataBytesHex'] or struct.unpack_from('<I',metadataRaw,pos)[0]!=int(row['rawOffset'],16) or row['rawOffset']!='0x4000110':raise ValueError('String.Empty原始静态标记不符')
 decoder=capstone.Cs(capstone.CS_ARCH_X86,capstone.CS_MODE_64);decoder.detail=True
 ins={i.address-pe.OPTIONAL_HEADER.ImageBase:i for m in p['nativeMethods'] for i in decoder.disasm(bytes.fromhex(m['bytesHex']),pe.OPTIONAL_HEADER.ImageBase+int(m['rva'],16))}
 i=ins[0xb8a6471];rip=next(o for o in i.operands if o.type==capstone.x86.X86_OP_MEM and o.mem.base==capstone.x86.X86_REG_RIP)
 if i.address+i.size+rip.mem.disp-pe.OPTIONAL_HEADER.ImageBase!=0x93434a8:raise ValueError('绑定载荷Empty共享引用字段基址槽不符')
 i=ins[0xb8a6478]
 if i.mnemonic!='mov' or i.operands[1].type!=capstone.x86.X86_OP_MEM or i.operands[1].mem.disp!=0x110:raise ValueError('绑定载荷Empty字段读取偏移不符')
 helpers,helperSize=characters.verify_native(client,{'nativeFiles':p['nativeFiles'],'nativeMethods':p['runtimeMethods'],'nativeBytes':p['runtimeBytes']})
 registrations=sibling('binding_payload_registrations','read-rogue-modifier-registrations.py');registrations.verify_native(client)
 return methods,size,helpers,helperSize

def analyze(a):
 client=a.client_dir.resolve();design=sibling('binding_payload_output','read-client-design-data.py');output=design.outside_client(a.output,client)
 if ROOT/'build' not in output.parents:raise ValueError('绑定载荷证据仅输出到工程build')
 p=load_profile();methods,size,helpers,helperSize=verify_native(client)
 result={'passed':True,'matchedCompleteNativeMethods':methods,'matchedCompleteNativeBytes':size,'matchedCompleteRuntimeHelpers':helpers,'matchedCompleteRuntimeHelperBytes':helperSize,'matchedLeafFragments':len(p['leafFragments']),'matchedLeafFragmentBytes':p['leafBytes'],'matchedFieldTypes':len(p['matchedTypes']),'matchedBoundMethodIdentities':len(p['boundMethodIdentities']),'matchedEmptyStaticLayoutRecords':len(p['emptyStaticLayout']['fields']),**{k:v for k,v in p.items() if isinstance(v,bool)},'jsonInstalled':False,'clientExecutableStarted':False,'clientModified':False}
 output.parent.mkdir(parents=True,exist_ok=True);output.write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n','utf-8');return result
if __name__=='__main__':
 parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--client-dir',type=Path,required=True);parser.add_argument('--output',type=Path,default=ROOT/'build/rogue-binding-payload-evidence.json');print(json.dumps(analyze(parser.parse_args()),ensure_ascii=False))
