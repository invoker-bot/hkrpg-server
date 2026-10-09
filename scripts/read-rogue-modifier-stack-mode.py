"""只读核对叠加模式寿命/计数分支、独立设置器和替换回调顺序；真实事件与组件仍需绑定。"""
import argparse
from functools import lru_cache
import hashlib
import importlib.util
import json
import struct
from pathlib import Path
from types import SimpleNamespace

ROOT=Path(__file__).resolve().parents[1]
PROFILE=ROOT/'src/test/resources/fixtures/rogue-modifier-stack-mode-4.6.json'
PROFILE_SHA256='c31e621aed67f93cdaa66a6685dc079789d085fabe6bf5892eefaf1a7eb51621'

def sibling(name,filename):
 spec=importlib.util.spec_from_file_location(name,Path(__file__).with_name(filename))
 module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module);return module

characters=sibling('modifier_stack_mode_characters','read-rogue-resonance-character.py')

@lru_cache(maxsize=1)
def load_profile():
 raw=PROFILE.read_bytes().replace(b'\r\n',b'\n')
 if hashlib.sha256(raw).hexdigest()!=PROFILE_SHA256:raise ValueError('叠加模式冻结证据改变')
 return json.loads(raw)

def metadata(client,types,methods,inheritance=False):
 analyzer=sibling('modifier_stack_mode_metadata','analyze-client-46.py')
 return analyzer.extract(SimpleNamespace(client_dir=client,types=types,fields=True,field_offsets=True,
  inheritance=inheritance,type_contains=[],method_indices=methods,method_rvas=[],method_name_contains=[],
  method_slots=[],string_slots=[],type_slots=[],disassemble=0,method_names=[]))

def verify_native(client):
 p=load_profile();methods,size=characters.verify_native(client,p)
 expected=[{k:m[k] for k in ['index','name','rva','typeIndex','typeName']} for m in p['nativeMethods']]
 actual=metadata(client,[t['name'] for t in p['matchedTypes']],[m['index'] for m in expected])
 if actual['selectedMethods']!=expected:raise ValueError('叠加分支、值设置器或回调方法身份不符')
 declared={t['typeIndex']:t for t in actual['methods']}
 inheritedNames=[t['name'] for t in p['matchedTypes'] if 'selectedInstanceFields' in t]
 inherited={t['typeIndex']:t for t in metadata(client,inheritedNames,[],True)['methods']}
 for fact in p['matchedTypes']:
  t=declared[fact['typeIndex']]
  if t['name']!=fact['name']:raise ValueError('叠加模式字段所属类型不符')
  if 'fields' in fact and t['fields']!=fact['fields']:raise ValueError('叠加模式枚举完整声明不符')
  for key,rows in [('selectedFields',t['fields']),('selectedInstanceFields',inherited.get(fact['typeIndex'],{}).get('instanceFields',[]))]:
   if key not in fact:continue
   names={f['name'] for f in fact[key]}
   if [f for f in rows if f['name'] in names]!=fact[key]:raise ValueError('计数、层数、寿命或定义字段不符')
 table=p['modeJumpTable'];m=next(m for m in p['nativeMethods'] if m['index']==table['methodIndex'])
 rva=int(table['rva'],16);off=rva-int(m['rva'],16);raw=bytes.fromhex(m['bytesHex'])[off:off+44]
 if raw.hex()!=table['bytesHex'] or [hex(rva+i) for i in struct.unpack('<11i',raw)]!=table['targets']:raise ValueError('叠加模式跳转表不符')
 enum=next(t for t in p['matchedTypes'] if t['typeIndex']==15648)
 if [f['constant']['value'] for f in enum['fields'] if 'constant' in f]!=list(range(14)):raise ValueError('叠加模式枚举值不符')
 return methods,size

def analyze(a):
 client=a.client_dir.resolve();design=sibling('modifier_stack_mode_output','read-client-design-data.py')
 output=design.outside_client(a.output,client)
 if ROOT/'build' not in output.parents:raise ValueError('只接受工程build下的叠加模式证据')
 p=load_profile();methods,size=verify_native(client)
 result={'passed':True,'matchedCompleteNativeMethods':methods,'matchedCompleteNativeBytes':size,
  'matchedFieldTypes':len(p['matchedTypes']),'matchedStackModes':14,'matchedJumpTargets':11,**{k:v for k,v in p.items() if isinstance(v,bool)},
  'jsonInstalled':False,'clientExecutableStarted':False,'clientModified':False}
 output.parent.mkdir(parents=True,exist_ok=True);output.write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n','utf-8');return result

if __name__=='__main__':
 parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--client-dir',type=Path,required=True)
 parser.add_argument('--output',type=Path,default=ROOT/'build/rogue-modifier-stack-mode-evidence.json')
 print(json.dumps(analyze(parser.parse_args()),ensure_ascii=False))
