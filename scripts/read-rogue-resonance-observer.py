"""只读核对数值容器观察者、有限实例监听与固定调用列表；完整委托和世界仍待完成。"""
import argparse
from functools import lru_cache
import hashlib
import importlib.util
import json
import struct
from pathlib import Path
from types import SimpleNamespace

ROOT=Path(__file__).resolve().parents[1]
PROFILE=ROOT/'src/test/resources/fixtures/rogue-resonance-observer-4.6.json'
PROFILE_SHA256='9053d71977bdf0849978d8738f0de3514cd94cfea068ddd1962ad788e88b0a7e'

def sibling(name,filename):
 spec=importlib.util.spec_from_file_location(name,Path(__file__).with_name(filename))
 module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module);return module

characters=sibling('resonance_observer_characters','read-rogue-resonance-character.py')

@lru_cache(maxsize=1)
def load_profile():
 raw=PROFILE.read_bytes().replace(b'\r\n',b'\n')
 if hashlib.sha256(raw).hexdigest()!=PROFILE_SHA256:raise ValueError('修改器数值监听冻结证据改变')
 return json.loads(raw)

def metadata(client,types,methods,inheritance=False):
 analyzer=sibling('resonance_observer_metadata','analyze-client-46.py')
 return analyzer.extract(SimpleNamespace(client_dir=client,types=types,fields=True,field_offsets=True,
  inheritance=inheritance,type_contains=[],method_indices=methods,method_rvas=[],method_name_contains=[],
  method_slots=[],string_slots=[],type_slots=[],disassemble=0,method_names=[]))


def verify_generic_slots(client,pe,components,p):
 if not p['genericMethodSlots']:return
 import mmap
 header=components.read_rva(pe,0x48b19b0,0x208);word=lambda o:struct.unpack_from('<I',header,o)[0]
 pairs=((word(0x190)+0xed791073)&0xffffffff)+0x208
 literals=(word(0x1f0)^0x56c7d20d)+0x208
 specs=((word(0x38)+0xf778f1ab)&0xffffffff)+0x208
 registration=p['genericInstantiationRegistration']
 array=struct.unpack('<Q',components.read_rva(pe,int(registration['pointerWordRva'],16),8))[0]-pe.OPTIONAL_HEADER.ImageBase
 if array!=int(registration['arrayRva'],16):raise ValueError('实例监听泛型注册不符')
 wanted={f['destination']:f for f in p['genericMethodSlots']};found={}
 with (client/'StarRail_Data/il2cpp_data/Metadata/global-metadata.dat').open('rb') as stream,mmap.mmap(stream.fileno(),0,access=mmap.ACCESS_READ) as data:
  if not 0<=pairs<literals<=len(data):raise ValueError('实例监听使用表范围越界')
  for index in range((literals-pairs)//8):
   first,second=struct.unpack_from('<II',data,pairs+index*8)
   key=(((index*0x87c3)^0x5fa3fad3)*0x334fb2ba+0x454d10d89e6a02a)&((1<<64)-1)
   key=(((key>>14)*0x102533d7+0x58972d9863a807)&((1<<64)-1))>>23
   dest=((first^0x6907ab9a)-key)&0xffffffff;source=(second-key+0xab3a6cb5)&0xffffffff
   if dest not in wanted or source>>29!=6:continue
   fact=wanted[dest];payload=source&0x1fffffff
   if index!=fact['pairIndex'] or payload!=fact['payload']:raise ValueError('实例监听MethodSpec使用不符')
   spec=fact['methodSpec'];actual=struct.unpack_from('<iii',data,specs+payload*12)
   if actual!=(spec['methodDefinition'],spec['argumentIndexAt4'],spec['argumentIndexAt8']):raise ValueError('实例监听泛型参数索引不符')
   if dest in found:raise ValueError('实例监听槽存在重复匹配')
   found[dest]=True
 if set(found)!=set(wanted):raise ValueError('实例监听方法槽缺失')
 for fact in wanted.values():
  for record in fact['instRecords']:
   raw=components.read_rva(pe,array+record['index']*16,16)
   if raw.hex()!=record['raw']:raise ValueError('实例监听泛型实例化记录不符')
   count,unused,pointer=struct.unpack('<IIQ',raw)
   if count!=2 or unused!=0 or pointer!=int(record['argv'],16):raise ValueError('实例监听泛型参数数组不符')
   pointers=struct.unpack('<QQ',components.read_rva(pe,int(record['argvRva'],16),16))
   for pointer,arg in zip(pointers,record['arguments']):
    if pointer-pe.OPTIONAL_HEADER.ImageBase!=int(arg['rva'],16):raise ValueError('实例监听类型参数指针不符')
    raw=components.read_rva(pe,int(arg['rva'],16),8)
    if raw.hex()!=arg['bytesHex']:raise ValueError('实例监听类型参数不符')

def verify_native(client):
 p=load_profile();methods,size=characters.verify_native(client,p)
 expected=[{k:m[k] for k in ['index','name','rva','typeIndex','typeName']} for m in p['nativeMethods']+p['boundMethodIdentities']]
 actual=metadata(client,[t['name'] for t in p['matchedTypes']],[m['index'] for m in expected])
 if actual['selectedMethods']!=expected:raise ValueError('数值监听、字符串列表与字典方法身份不符')
 declared={t['typeIndex']:t for t in actual['methods']}
 inheritedNames=[t['name'] for t in p['matchedTypes'] if 'selectedInstanceFields' in t]
 inherited={t['typeIndex']:t for t in metadata(client,inheritedNames,[],True)['methods']}
 for fact in p['matchedTypes']:
  t=declared[fact['typeIndex']]
  if t['name']!=fact['name']:raise ValueError('修改器数值监听字段所属类型不符')
  if 'fields' in fact and t['fields']!=fact['fields']:raise ValueError('修改器数值监听枚举完整声明不符')
  for key,rows in [('selectedFields',t['fields']),('selectedInstanceFields',inherited.get(fact['typeIndex'],{}).get('instanceFields',[]))]:
   if key not in fact:continue
   names={f['name'] for f in fact[key]}
   if [f for f in rows if f['name'] in names]!=fact[key]:raise ValueError('数值回调及委托字段字段不符')
 import pefile
 components=sibling('resonance_observer_components','read-rogue-resonance-components.py')
 pe=pefile.PE(str(client/'GameAssembly.dll'),fast_load=True)
 directory=pe.OPTIONAL_HEADER.DATA_DIRECTORY[pefile.DIRECTORY_ENTRY['IMAGE_DIRECTORY_ENTRY_EXCEPTION']]
 bounds={a:(b,c) for a,b,c in struct.iter_unpack('<III',components.read_rva(pe,directory.VirtualAddress,directory.Size))}
 for fact in p['nativeRuntimeFunctions']:
  start=int(fact['rva'],16);end=int(fact['endRva'],16)
  if bounds.get(start)!=(end,int(fact['unwindRva'],16)):raise ValueError('实例监听帮助函数PE边界不符')
  raw=components.read_rva(pe,start,end-start)
  if raw.hex()!=fact['bytesHex'] or hashlib.sha256(raw).hexdigest()!=fact['sha256']:raise ValueError('实例监听帮助函数原件不符')
 verify_generic_slots(client,pe,components,p)
 return methods,size

def analyze(a):
 client=a.client_dir.resolve();design=sibling('resonance_observer_output','read-client-design-data.py')
 output=design.outside_client(a.output,client)
 if ROOT/'build' not in output.parents:raise ValueError('只接受工程build下的修改器数值监听证据')
 p=load_profile();methods,size=verify_native(client)
 result={'passed':True,'matchedCompleteNativeMethods':methods,'matchedCompleteNativeBytes':size,
  'matchedFieldTypes':len(p['matchedTypes']),'matchedNativeRuntimeFunctions':len(p['nativeRuntimeFunctions']),'matchedNativeRuntimeBytes':p['nativeRuntimeBytes'],'matchedBoundMethodIdentities':len(p['boundMethodIdentities']),**{k:v for k,v in p.items() if isinstance(v,bool)},
  'jsonInstalled':False,'clientExecutableStarted':False,'clientModified':False}
 output.parent.mkdir(parents=True,exist_ok=True);output.write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n','utf-8');return result

if __name__=='__main__':
 parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--client-dir',type=Path,required=True)
 parser.add_argument('--output',type=Path,default=ROOT/'build/rogue-resonance-observer-evidence.json')
 print(json.dumps(analyze(parser.parse_args()),ensure_ascii=False))
