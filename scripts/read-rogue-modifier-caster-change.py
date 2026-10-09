"""只读核对施加者缓存、数值监听迁移与身份列表；世界和动态属性处理体仍待完成。"""
import argparse
from functools import lru_cache
import hashlib
import importlib.util
import json
import struct
from pathlib import Path
from types import SimpleNamespace

ROOT=Path(__file__).resolve().parents[1]
PROFILE=ROOT/'src/test/resources/fixtures/rogue-modifier-caster-change-4.6.json'
PROFILE_SHA256='aff7e37072559b6924a163e5b2e27e9a7da6459daa98c4857e667a4d2b70bb94'

def sibling(name,filename):
 spec=importlib.util.spec_from_file_location(name,Path(__file__).with_name(filename))
 module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module);return module

characters=sibling('modifier_caster_change_characters','read-rogue-resonance-character.py')

@lru_cache(maxsize=1)
def load_profile():
 raw=PROFILE.read_bytes().replace(b'\r\n',b'\n')
 if hashlib.sha256(raw).hexdigest()!=PROFILE_SHA256:raise ValueError('施加者变化冻结证据改变')
 return json.loads(raw)

def metadata(client,types,methods,inheritance=False):
 analyzer=sibling('modifier_caster_change_metadata','analyze-client-46.py')
 return analyzer.extract(SimpleNamespace(client_dir=client,types=types,fields=True,field_offsets=True,
  inheritance=inheritance,type_contains=[],method_indices=methods,method_rvas=[],method_name_contains=[],
  method_slots=[],string_slots=[],type_slots=[],disassemble=0,method_names=[]))

def verify_native(client):
 p=load_profile();methods,size=characters.verify_native(client,p)
 expected=[{k:m[k] for k in ['index','name','rva','typeIndex','typeName']} for m in p['nativeMethods']]+p['boundMethodIdentities']
 actual=metadata(client,[t['name'] for t in p['matchedTypes']],[m['index'] for m in expected])
 if actual['selectedMethods']!=expected:raise ValueError('施加者变化、回调、身份登记和明确绑定方法身份不符')
 declared={t['typeIndex']:t for t in actual['methods']}
 inheritedNames=[t['name'] for t in p['matchedTypes'] if 'selectedInstanceFields' in t]
 inherited={t['typeIndex']:t for t in metadata(client,inheritedNames,[],True)['methods']}
 for fact in p['matchedTypes']:
  t=declared[fact['typeIndex']]
  if t['name']!=fact['name']:raise ValueError('施加者变化字段所属类型不符')
  if 'fields' in fact and t['fields']!=fact['fields']:raise ValueError('施加者变化枚举完整声明不符')
  for key,rows in [('selectedFields',t['fields']),('selectedInstanceFields',inherited.get(fact['typeIndex'],{}).get('instanceFields',[]))]:
   if key not in fact:continue
   names={f['name'] for f in fact[key]}
   if [f for f in rows if f['name'] in names]!=fact[key]:raise ValueError('施加者缓存、组件数值回调、所属列表或范围字段不符')
 components=sibling('modifier_caster_change_components','read-rogue-resonance-components.py')
 components.read_generic_slots(client,p)
 analyzer=sibling('modifier_caster_change_slots','analyze-client-46.py')
 actualSlot=analyzer.extract(SimpleNamespace(client_dir=client,types=[],type_contains=[],method_indices=[],method_rvas=[],
  method_name_contains=[],method_slots=[int(p['listenerMethodSlot']['slot'],16)],string_slots=[],type_slots=[],
  fields=False,field_offsets=False,inheritance=False,disassemble=0,method_names=[]))
 if actualSlot['methodInfoSlots']!=[p['listenerMethodSlot']]:raise ValueError('施加者监听方法槽身份不符')
 return methods,size

def analyze(a):
 client=a.client_dir.resolve();design=sibling('modifier_caster_change_output','read-client-design-data.py')
 output=design.outside_client(a.output,client)
 if ROOT/'build' not in output.parents:raise ValueError('只接受工程build下的施加者变化证据')
 p=load_profile();methods,size=verify_native(client)
 result={'passed':True,'matchedCompleteNativeMethods':methods,'matchedCompleteNativeBytes':size,
  'matchedFieldTypes':len(p['matchedTypes']),'matchedBoundMethodIdentities':len(p['boundMethodIdentities']),'matchedGenericComponents':len(p['genericMethodSlots']),'matchedListenerMethodSlots':1,**{k:v for k,v in p.items() if isinstance(v,bool)},
  'jsonInstalled':False,'clientExecutableStarted':False,'clientModified':False}
 output.parent.mkdir(parents=True,exist_ok=True);output.write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n','utf-8');return result

if __name__=='__main__':
 parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--client-dir',type=Path,required=True)
 parser.add_argument('--output',type=Path,default=ROOT/'build/rogue-modifier-caster-change-evidence.json')
 print(json.dumps(analyze(parser.parse_args()),ensure_ascii=False))
