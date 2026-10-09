"""只读核对离场和不可选中登记列表、可见性刷新及引用容器；事件和组件函数体仍保持明确绑定。"""
import argparse
from functools import lru_cache
import hashlib
import importlib.util
import json
import struct
from pathlib import Path
from types import SimpleNamespace

ROOT=Path(__file__).resolve().parents[1]
PROFILE=ROOT/'src/test/resources/fixtures/rogue-owned-parameter-lists-4.6.json'
PROFILE_SHA256='73a54238257fbcbe3678f836235f2e7c291a5c68a8a95b628212bae0fd7e84f1'

def sibling(name,filename):
 spec=importlib.util.spec_from_file_location(name,Path(__file__).with_name(filename))
 module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module);return module

characters=sibling('owned_parameter_lists_characters','read-rogue-resonance-character.py')

@lru_cache(maxsize=1)
def load_profile():
 raw=PROFILE.read_bytes().replace(b'\r\n',b'\n')
 if hashlib.sha256(raw).hexdigest()!=PROFILE_SHA256:raise ValueError('拥有对象撤销冻结证据改变')
 return json.loads(raw)

def metadata(client,types,methods,inheritance=False):
 analyzer=sibling('owned_parameter_lists_metadata','analyze-client-46.py')
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
 components=sibling('owned_effects_parameters','read-rogue-resonance-components.py')
 import pefile
 pe=pefile.PE(str(client/'GameAssembly.dll'),fast_load=True)
 header=components.read_rva(pe,0x48b19b0,0x208)
 metadataRaw=(client/'StarRail_Data/il2cpp_data/Metadata/global-metadata.dat').read_bytes()
 methodTable=((struct.unpack_from('<I',header,0x14c)[0]+0xf3a04294)&0xffffffff)+0x208
 parameterTable=((struct.unpack_from('<I',header,0x30)[0]+0xdcf5fdbe)&0xffffffff)+0x208
 for row in p['parameterRecords']:
  index=row['methodIndex'];offset=methodTable+index*26
  if offset!=int(row['methodRowOffset'],16) or metadataRaw[offset:offset+26].hex()!=row['methodRowHex']:raise ValueError('拥有参数撤销的方法原始行不符')
  key=(((index*0x31e1)^0x33914937)*0x2c03f17d)&0xffffffffffffffff
  key=(((key>>23)*0x540cc9f4)&0xffffffffffffffff)>>21
  key=(key+0x71bc7861)&0xffffffff
  count=metadataRaw[offset+0x18]^(key&255)^0xa8
  start=struct.unpack_from('<I',metadataRaw,offset+4)[0]^key^0x9889b8
  parameter=row['parameter'];pi=parameter['index']
  if not 0<=parameter['ordinal']<count or pi!=start+parameter['ordinal']:raise ValueError('拥有参数撤销的参数位置不符')
  pp=parameterTable+pi*8
  if pp!=int(row['parameterRowOffset'],16) or metadataRaw[pp:pp+8].hex()!=row['parameterRowHex']:raise ValueError('拥有参数撤销的参数原始行不符')
  pkey=((((((pi*0x72e1d74b12b+0x1911d05aff5)&0xffffffffffffffff)>>11)*0x58b870a2)+0x83cf7b44)&0xffffffff)
  nativeType=((struct.unpack_from('<I',metadataRaw,pp)[0]^0x67e90dc5)-pkey)&0xffffffff
  record=row['record'];rva=0x4ffdd60+nativeType*8
  if nativeType!=parameter['nativeTypeIndex'] or rva!=int(record['nativeRva'],16):raise ValueError('拥有参数撤销的类型索引不符')
  raw=components.read_rva(pe,rva,8)
  if raw.hex()!=record['bytesHex'] or raw[6]!=record['kind'] or struct.unpack_from('<I',raw)[0]!=record['payload']:raise ValueError('拥有参数撤销的引用或布尔类型原件不符')
 components=sibling('owned_parameter_lists_components','read-rogue-resonance-components.py')
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
 helpers,helperSize=characters.verify_native(client,{'nativeFiles':p['nativeFiles'],'nativeMethods':p['runtimeMethods'],'nativeBytes':p['runtimeBytes']})
 return methods,size,helpers,helperSize

def analyze(a):
 client=a.client_dir.resolve();design=sibling('owned_parameter_lists_output','read-client-design-data.py')
 output=design.outside_client(a.output,client)
 if ROOT/'build' not in output.parents:raise ValueError('只接受工程build下的拥有对象撤销证据')
 p=load_profile();methods,size,helpers,helperSize=verify_native(client)
 result={'passed':True,'matchedCompleteNativeMethods':methods,'matchedCompleteNativeBytes':size,
  'matchedCompleteRuntimeHelpers':helpers,'matchedCompleteRuntimeHelperBytes':helperSize,'matchedGenericFieldArguments':len(p['genericFields']),'matchedNativeParameterRecords':len(p['parameterRecords']),'matchedFieldTypes':len(p['matchedTypes']),'matchedBoundMethodIdentities':len(p['boundMethodIdentities']),**{k:v for k,v in p.items() if isinstance(v,bool)},
  'jsonInstalled':False,'clientExecutableStarted':False,'clientModified':False}
 output.parent.mkdir(parents=True,exist_ok=True);output.write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n','utf-8');return result

if __name__=='__main__':
 parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--client-dir',type=Path,required=True)
 parser.add_argument('--output',type=Path,default=ROOT/'build/rogue-owned-parameter-lists-evidence.json')
 print(json.dumps(analyze(parser.parse_args()),ensure_ascii=False))
