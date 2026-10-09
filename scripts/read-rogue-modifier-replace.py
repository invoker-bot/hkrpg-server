"""只读核对修改器替换普通顺序、临时数据释放、延迟能力与参数注入；真实事件与组件仍需绑定。"""
import argparse
from functools import lru_cache
import hashlib
import importlib.util
import json
import struct
import pefile
from pathlib import Path
from types import SimpleNamespace

ROOT=Path(__file__).resolve().parents[1]
PROFILE=ROOT/'src/test/resources/fixtures/rogue-modifier-replace-4.6.json'
PROFILE_SHA256='ba47fdc742f96f375f334813e5fe286ea303b9d913c7168bd6241f72ed225006'

def sibling(name,filename):
 spec=importlib.util.spec_from_file_location(name,Path(__file__).with_name(filename))
 module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module);return module

characters=sibling('modifier_replace_characters','read-rogue-resonance-character.py')

@lru_cache(maxsize=1)
def load_profile():
 raw=PROFILE.read_bytes().replace(b'\r\n',b'\n')
 if hashlib.sha256(raw).hexdigest()!=PROFILE_SHA256:raise ValueError('替换冻结证据改变')
 return json.loads(raw)

def metadata(client,types,methods,inheritance=False):
 analyzer=sibling('modifier_replace_metadata','analyze-client-46.py')
 return analyzer.extract(SimpleNamespace(client_dir=client,types=types,fields=True,field_offsets=True,
  inheritance=inheritance,type_contains=[],method_indices=methods,method_rvas=[],method_name_contains=[],
  method_slots=[],string_slots=[],type_slots=[],disassemble=0,method_names=[]))

def verify_generic_fields(client):
 p=load_profile();path=client/'StarRail_Data/il2cpp_data/Metadata/startup-metadata.dat'
 with path.open('rb') as stream:
  if hashlib.file_digest(stream,'sha256').hexdigest()!=p['nativeFiles']['startup-metadata.dat']:raise ValueError('替换泛型startup版本不符')
 startup=path.read_bytes();pe=pefile.PE(str(client/'GameAssembly.dll'),fast_load=True);base=pe.OPTIONAL_HEADER.ImageBase
 header=pe.get_data(0x48b19b0,0x208);table=struct.unpack_from('<I',header,0x164)[0]^0x7f5c5934
 array=struct.unpack('<Q',pe.get_data(0x4931690,8))[0]-base
 if hex(table)!=p['genericClassTableOffsetInStartup'] or hex(array)!=p['genericInstantiationArrayRva']:raise ValueError('替换泛型注册表不符')
 def check(record):
  raw=pe.get_data(int(record['nativeRva'],16),8)
  if raw.hex()!=record['bytesHex'] or raw[6]!=record['kind'] or struct.unpack_from('<I',raw)[0]!=record['payload']:raise ValueError('替换泛型类型原件不符')
  if record['kind']==21:
   pos=table+record['payload']*8;row=startup[pos:pos+8]
   definition,inst=struct.unpack('<ii',row);irva=array+inst*16;iraw=pe.get_data(irva,16)
   if (hex(pos),row.hex(),definition,inst,hex(irva),iraw.hex())!=(record['startupRowOffset'],record['startupRowHex'],record['genericDefinition'],record['instIndex'],record['instRva'],record['instHex']):raise ValueError('替换泛型声明或实例不符')
   count,unused,argv=struct.unpack('<IIQ',iraw)
   if count!=len(record['args']) or unused!=0:raise ValueError('替换泛型参数个数不符')
   for (pointer,),arg in zip(struct.iter_unpack('<Q',pe.get_data(argv-base,count*8)),record['args'],strict=True):
    if hex(pointer-base)!=arg['nativeRva']:raise ValueError('替换泛型实际参数指针不符')
    check(arg)
 for fact in p['genericFields']:
  if int(fact['record']['nativeRva'],16)!=0x4ffdd60+fact['field']['nativeTypeIndex']*8:raise ValueError('替换字段泛型记录位置不符')
  check(fact['record'])
 if [(f['record']['genericName'],[(a['kind'],a.get('payload')) for a in f['record']['args']]) for f in p['genericFields'][:2]]!=[('List`1',[(17,54435)]),('List`1',[(17,54434)])]:raise ValueError('注入列表不是匹配的字符串/固定数值元素')
 if any(f['record']['genericName']!='Nullable`1' or [a['kind'] for a in f['record']['args']]!=[8] for f in p['genericFields'][2:]):raise ValueError('最大层数覆盖或强制层数不是可空Int32')
 return 4

def verify_native(client):
 p=load_profile();methods,size=characters.verify_native(client,p)
 expected=[{k:m[k] for k in ['index','name','rva','typeIndex','typeName']} for m in p['nativeMethods']]
 actual=metadata(client,[t['name'] for t in p['matchedTypes']],[m['index'] for m in expected])
 if actual['selectedMethods']!=expected:raise ValueError('替换体、注入设置器方法身份不符')
 declared={t['typeIndex']:t for t in actual['methods']}
 inheritedNames=[t['name'] for t in p['matchedTypes'] if 'selectedInstanceFields' in t]
 inherited={t['typeIndex']:t for t in metadata(client,inheritedNames,[],True)['methods']}
 for fact in p['matchedTypes']:
  t=declared[fact['typeIndex']]
  if t['name']!=fact['name']:raise ValueError('替换参数字段所属类型不符')
  if 'fields' in fact and t['fields']!=fact['fields']:raise ValueError('替换类型枚举完整声明不符')
  for key,rows in [('selectedFields',t['fields']),('selectedInstanceFields',inherited.get(fact['typeIndex'],{}).get('instanceFields',[]))]:
   if key not in fact:continue
   names={f['name'] for f in fact[key]}
   if [f for f in rows if f['name'] in names]!=fact[key]:raise ValueError('执行计数、延迟能力、注入列表或参数不符')
 verify_generic_fields(client)
 return methods,size

def analyze(a):
 client=a.client_dir.resolve();design=sibling('modifier_replace_output','read-client-design-data.py')
 output=design.outside_client(a.output,client)
 if ROOT/'build' not in output.parents:raise ValueError('只接受工程build下的替换证据')
 p=load_profile();methods,size=verify_native(client)
 result={'passed':True,'matchedCompleteNativeMethods':methods,'matchedCompleteNativeBytes':size,
  'matchedFieldTypes':len(p['matchedTypes']),'matchedGenericArguments':4,**{k:v for k,v in p.items() if isinstance(v,bool)},
  'jsonInstalled':False,'clientExecutableStarted':False,'clientModified':False}
 output.parent.mkdir(parents=True,exist_ok=True);output.write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n','utf-8');return result

if __name__=='__main__':
 parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--client-dir',type=Path,required=True)
 parser.add_argument('--output',type=Path,default=ROOT/'build/rogue-modifier-replace-evidence.json')
 print(json.dumps(analyze(parser.parse_args()),ensure_ascii=False))
