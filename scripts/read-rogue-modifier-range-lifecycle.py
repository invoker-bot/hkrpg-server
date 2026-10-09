"""只读核对范围根监听退订和销毁退出；完整修改器销毁及原生世界仍待完成。"""
import argparse
from functools import lru_cache
import hashlib
import importlib.util
import json
import struct
from pathlib import Path
from types import SimpleNamespace

ROOT=Path(__file__).resolve().parents[1]
PROFILE=ROOT/'src/test/resources/fixtures/rogue-modifier-range-lifecycle-4.6.json'
PROFILE_SHA256='d640f57af73107a839bd9bb72661cb9e9b9d5bebcf8b15cd3d59ff78170faf94'

def sibling(name,filename):
 spec=importlib.util.spec_from_file_location(name,Path(__file__).with_name(filename))
 module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module);return module

characters=sibling('modifier_range_lifecycle_characters','read-rogue-resonance-character.py')

@lru_cache(maxsize=1)
def load_profile():
 raw=PROFILE.read_bytes().replace(b'\r\n',b'\n')
 if hashlib.sha256(raw).hexdigest()!=PROFILE_SHA256:raise ValueError('范围收尾冻结证据改变')
 return json.loads(raw)

def metadata(client,types,methods,inheritance=False):
 analyzer=sibling('modifier_range_lifecycle_metadata','analyze-client-46.py')
 return analyzer.extract(SimpleNamespace(client_dir=client,types=types,fields=True,field_offsets=True,
  inheritance=inheritance,type_contains=[],method_indices=methods,method_rvas=[],method_name_contains=[],
  method_slots=[],string_slots=[],type_slots=[],disassemble=0,method_names=[]))

def verify_native(client):
 p=load_profile();methods,size=characters.verify_native(client,p)
 expected=[{k:m[k] for k in ['index','name','rva','typeIndex','typeName']} for m in p['nativeMethods']]+p['boundMethodIdentities']
 actual=metadata(client,[t['name'] for t in p['matchedTypes']],[m['index'] for m in expected])
 if actual['selectedMethods']!=expected:raise ValueError('动态范围、上下文求值和明确绑定方法身份不符')
 declared={t['typeIndex']:t for t in actual['methods']}
 inheritedNames=[t['name'] for t in p['matchedTypes'] if 'selectedInstanceFields' in t]
 inherited={t['typeIndex']:t for t in metadata(client,inheritedNames,[],True)['methods']}
 for fact in p['matchedTypes']:
  t=declared[fact['typeIndex']]
  if t['name']!=fact['name']:raise ValueError('动态范围字段所属类型不符')
  if 'fields' in fact and t['fields']!=fact['fields']:raise ValueError('动态范围枚举完整声明不符')
  for key,rows in [('selectedFields',t['fields']),('selectedInstanceFields',inherited.get(fact['typeIndex'],{}).get('instanceFields',[]))]:
   if key not in fact:continue
   names={f['name'] for f in fact[key]}
   if [f for f in rows if f['name'] in names]!=fact[key]:raise ValueError('范围回调、数值上下文、临时帧或监听字典字段不符')
 components=sibling('modifier_range_lifecycle_components','read-rogue-resonance-components.py')
 import pefile
 pe=pefile.PE(str(client/'GameAssembly.dll'),fast_load=True)
 startup=(client/'StarRail_Data/il2cpp_data/Metadata/startup-metadata.dat').read_bytes()
 if hashlib.sha256(startup).hexdigest()!=p['nativeFiles']['startup-metadata.dat']:raise ValueError('范围监听泛型启动元数据不符')
 header=components.read_rva(pe,0x48b19b0,0x208)
 table=struct.unpack_from('<I',header,0x164)[0]^0x7f5c5934
 array=struct.unpack('<Q',components.read_rva(pe,0x4931690,8))[0]-pe.OPTIONAL_HEADER.ImageBase
 if table!=int(p['genericClassTableOffsetInStartup'],16) or array!=int(p['genericInstantiationArrayRva'],16):raise ValueError('范围字典泛型注册不符')
 def check_type(t,depth=0):
  if depth>6:raise ValueError('范围字典泛型深度越界')
  raw=components.read_rva(pe,int(t['nativeRva'],16),8)
  if raw.hex()!=t['bytesHex'] or raw[6]!=t['kind'] or struct.unpack_from('<I',raw)[0]!=t['payload']:raise ValueError('范围字典类型记录不符')
  if t['kind']!=21:return
  pos=table+t['payload']*8
  if pos!=int(t['startupRowOffset'],16) or startup[pos:pos+8].hex()!=t['startupRowHex']:raise ValueError('范围字典泛型类记录不符')
  if struct.unpack_from('<ii',startup,pos)!=(t['genericDefinition'],t['instIndex']):raise ValueError('范围字典泛型定义或实例索引不符')
  irva=array+t['instIndex']*16;raw=components.read_rva(pe,irva,16)
  if irva!=int(t['instRva'],16) or raw.hex()!=t['instHex']:raise ValueError('范围字典泛型实例原件不符')
  count,reserved,argv=struct.unpack('<IIQ',raw)
  if count!=len(t['args']) or reserved!=0 or not 1<=count<=8:raise ValueError('范围字典泛型参数数量不符')
  pointers=struct.unpack('<'+'Q'*count,components.read_rva(pe,argv-pe.OPTIONAL_HEADER.ImageBase,count*8))
  for pointer,arg in zip(pointers,t['args']):
   if pointer-pe.OPTIONAL_HEADER.ImageBase!=int(arg['nativeRva'],16):raise ValueError('范围字典泛型参数地址不符')
   check_type(arg,depth+1)
 for f in p['genericFields']:
  check_type(f['record'])
 directory=pe.OPTIONAL_HEADER.DATA_DIRECTORY[pefile.DIRECTORY_ENTRY['IMAGE_DIRECTORY_ENTRY_EXCEPTION']]
 bounds={a:(b,c) for a,b,c in struct.iter_unpack('<III',components.read_rva(pe,directory.VirtualAddress,directory.Size))}
 for fact in p['nativeRuntimeFunctions']:
  start=int(fact['rva'],16);end=int(fact['endRva'],16)
  if bounds.get(start)!=(end,int(fact['unwindRva'],16)):raise ValueError('范围退出枚举帮助函数PE边界不符')
  raw=components.read_rva(pe,start,end-start)
  if raw.hex()!=fact['bytesHex'] or hashlib.sha256(raw).hexdigest()!=fact['sha256']:raise ValueError('范围退出枚举帮助函数原件不符')
 components.read_generic_slots(client,p)
 analyzer=sibling('modifier_range_lifecycle_slots','analyze-client-46.py')
 actual=analyzer.extract(SimpleNamespace(client_dir=client,types=[],type_contains=[],method_indices=[],method_rvas=[],method_name_contains=[],method_slots=[int(f['slot'],16) for f in p['listenerMethodSlots']],string_slots=[],type_slots=[],fields=False,field_offsets=False,inheritance=False,disassemble=0,method_names=[]))
 if actual['methodInfoSlots']!=p['listenerMethodSlots']:raise ValueError('范围监听退订的三项方法槽身份不符')
 return methods,size

def analyze(a):
 client=a.client_dir.resolve();design=sibling('modifier_range_lifecycle_output','read-client-design-data.py')
 output=design.outside_client(a.output,client)
 if ROOT/'build' not in output.parents:raise ValueError('只接受工程build下的动态范围证据')
 p=load_profile();methods,size=verify_native(client)
 result={'passed':True,'matchedCompleteNativeMethods':methods,'matchedCompleteNativeBytes':size,
  'matchedFieldTypes':len(p['matchedTypes']),'matchedBoundMethodIdentities':len(p['boundMethodIdentities']),'matchedGenericFieldArguments':len(p['genericFields']),'matchedNativeRuntimeFunctions':len(p['nativeRuntimeFunctions']),'matchedNativeRuntimeBytes':p['nativeRuntimeBytes'],'matchedGenericComponents':len(p['genericMethodSlots']),'matchedListenerMethodSlots':len(p['listenerMethodSlots']),**{k:v for k,v in p.items() if isinstance(v,bool)},
  'jsonInstalled':False,'clientExecutableStarted':False,'clientModified':False}
 output.parent.mkdir(parents=True,exist_ok=True);output.write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n','utf-8');return result

if __name__=='__main__':
 parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--client-dir',type=Path,required=True)
 parser.add_argument('--output',type=Path,default=ROOT/'build/rogue-modifier-range-lifecycle-evidence.json')
 print(json.dumps(analyze(parser.parse_args()),ensure_ascii=False))
