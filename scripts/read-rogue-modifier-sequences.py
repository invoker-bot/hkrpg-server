"""只读核对修改器等待序列、计数、结束刷新与共享Java子任务；原生虚表和实例仍需绑定。"""
import argparse
from functools import lru_cache
import hashlib
import importlib.util
import json
import struct
from pathlib import Path
from types import SimpleNamespace

ROOT=Path(__file__).resolve().parents[1]
PROFILE=ROOT/'src/test/resources/fixtures/rogue-modifier-sequences-4.6.json'
PROFILE_SHA256='72cf795ca8d01a926dc76a357fdab98ceac36ae4429658cbbd22dbeb3b648202'

def sibling(name,filename):
 spec=importlib.util.spec_from_file_location(name,Path(__file__).with_name(filename))
 module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module);return module

characters=sibling('modifier_sequences_characters','read-rogue-resonance-character.py')

@lru_cache(maxsize=1)
def load_profile():
 raw=PROFILE.read_bytes().replace(b'\r\n',b'\n')
 if hashlib.sha256(raw).hexdigest()!=PROFILE_SHA256:raise ValueError('修改器序列冻结证据改变')
 return json.loads(raw)

def metadata(client,types,methods,inheritance=False):
 analyzer=sibling('modifier_sequences_metadata','analyze-client-46.py')
 return analyzer.extract(SimpleNamespace(client_dir=client,types=types,fields=True,field_offsets=True,
  inheritance=inheritance,type_contains=[],method_indices=methods,method_rvas=[],method_name_contains=[],
  method_slots=[],string_slots=[],type_slots=[],disassemble=0,method_names=[]))

def verify_native(client):
 p=load_profile();methods,size=characters.verify_native(client,p)
 expected=[{k:m[k] for k in ['index','name','rva','typeIndex','typeName']} for m in p['nativeMethods']]
 actual=metadata(client,[t['name'] for t in p['matchedTypes']],[m['index'] for m in expected])
 if actual['selectedMethods']!=expected:raise ValueError('等待/结束刷新和子任务方法身份不符')
 declared={t['typeIndex']:t for t in actual['methods']}
 inheritedNames=[t['name'] for t in p['matchedTypes'] if 'selectedInstanceFields' in t]
 inherited={t['typeIndex']:t for t in metadata(client,inheritedNames,[],True)['methods']}
 for fact in p['matchedTypes']:
  t=declared[fact['typeIndex']]
  if t['name']!=fact['name']:raise ValueError('修改器序列字段所属类型不符')
  if 'fields' in fact and t['fields']!=fact['fields']:raise ValueError('修改器序列枚举完整声明不符')
  for key,rows in [('selectedFields',t['fields']),('selectedInstanceFields',inherited.get(fact['typeIndex'],{}).get('instanceFields',[]))]:
   if key not in fact:continue
   names={f['name'] for f in fact[key]}
   if [f for f in rows if f['name'] in names]!=fact[key]:raise ValueError('序列状态、执行计数或延迟能力字段不符')
 slotFacts=p['modifierVirtualSlots']
 expectedSlots=[{k:f[k] for k in ['index','name','rva','typeIndex','typeName']} for f in slotFacts]
 if metadata(client,[],[f['index'] for f in slotFacts])['selectedMethods']!=expectedSlots:raise ValueError('修改器虚调用方法身份不符')
 analyzer=sibling('modifier_sequences_slot_metadata','analyze-client-46.py')
 import mmap,pefile
 pe=pefile.PE(str(client/'GameAssembly.dll'),fast_load=True);header=pe.get_data(0x48b19b0,0x208)
 start=((analyzer.word(header,0x14c)+0xf3a04294)&0xffffffff)+0x208
 with (client/'StarRail_Data/il2cpp_data/Metadata/global-metadata.dat').open('rb') as stream,mmap.mmap(stream.fileno(),0,access=mmap.ACCESS_READ) as data:
  for fact in slotFacts:
   encoded=struct.unpack_from('<H',data,start+fact['index']*26+12)[0]
   slot=(encoded^analyzer.method_key(fact['index'])^0x9f7)&0xffff
   if (encoded,slot,hex(0xc0+slot*8))!=(fact['encodedWord'],fact['decodedSlot'],fact['nativeFunctionSlotOffset']):raise ValueError('修改器虚方法槽不符')
 return methods,size

def analyze(a):
 client=a.client_dir.resolve();design=sibling('modifier_sequences_output','read-client-design-data.py')
 output=design.outside_client(a.output,client)
 if ROOT/'build' not in output.parents:raise ValueError('只接受工程build下的修改器序列证据')
 p=load_profile();methods,size=verify_native(client)
 result={'passed':True,'matchedCompleteNativeMethods':methods,'matchedCompleteNativeBytes':size,
  'matchedFieldTypes':len(p['matchedTypes']),'matchedModifierVirtualSlots':len(p['modifierVirtualSlots']),**{k:v for k,v in p.items() if isinstance(v,bool)},
  'jsonInstalled':False,'clientExecutableStarted':False,'clientModified':False}
 output.parent.mkdir(parents=True,exist_ok=True);output.write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n','utf-8');return result

if __name__=='__main__':
 parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--client-dir',type=Path,required=True)
 parser.add_argument('--output',type=Path,default=ROOT/'build/rogue-modifier-sequences-evidence.json')
 print(json.dumps(analyze(parser.parse_args()),ensure_ascii=False))
