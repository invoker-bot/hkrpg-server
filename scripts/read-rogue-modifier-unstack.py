"""只读核对普通UnStack整合入口、嵌入战技点脏值与明确的属性/模块绑定。"""
import argparse
from functools import lru_cache
import hashlib
import importlib.util
import json
import struct
from pathlib import Path
from types import SimpleNamespace

ROOT=Path(__file__).resolve().parents[1]
PROFILE=ROOT/'src/test/resources/fixtures/rogue-modifier-unstack-4.6.json'
PROFILE_SHA256='e8a49b0e7a394316aebfb6e3da5e93030459999267b4ca7086e55cbacf661d1e'

def sibling(name,filename):
 spec=importlib.util.spec_from_file_location(name,Path(__file__).with_name(filename))
 module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module);return module

characters=sibling('modifier_unstack_characters','read-rogue-resonance-character.py')

@lru_cache(maxsize=1)
def load_profile():
 raw=PROFILE.read_bytes().replace(b'\r\n',b'\n')
 if hashlib.sha256(raw).hexdigest()!=PROFILE_SHA256:raise ValueError('整合撤回冻结证据改变')
 return json.loads(raw)

def metadata(client,types,methods,inheritance=False):
 analyzer=sibling('modifier_unstack_metadata','analyze-client-46.py')
 return analyzer.extract(SimpleNamespace(client_dir=client,types=types,fields=True,field_offsets=True,
  inheritance=inheritance,type_contains=[],method_indices=methods,method_rvas=[],method_name_contains=[],
  method_slots=[],string_slots=[],type_slots=[],disassemble=0,method_names=[]))

def verify_native(client):
 p=load_profile();methods,size=characters.verify_native(client,p)
 expected=sorted([{k:m[k] for k in ['index','name','rva','typeIndex','typeName']} for m in p['nativeMethods']]+p['boundMethodIdentities'],key=lambda m:m['index'])
 actual=metadata(client,[t['name'] for t in p['matchedTypes']],[m['index'] for m in expected])
 if actual['selectedMethods']!=expected:raise ValueError('整合撤回初始化与优先级查询方法身份不符')
 declared={t['typeIndex']:t for t in actual['methods']}
 inheritedNames=[t['name'] for t in p['matchedTypes'] if 'selectedInstanceFields' in t]
 inherited={t['typeIndex']:t for t in metadata(client,inheritedNames,[],True)['methods']}
 for fact in p['matchedTypes']:
  t=declared[fact['typeIndex']]
  if t['name']!=fact['name']:raise ValueError('整合撤回字段所属类型不符')
  if 'fields' in fact and t['fields']!=fact['fields']:raise ValueError('整合撤回完整字段声明不符')
  for key,rows in [('selectedFields',t['fields']),('selectedInstanceFields',inherited.get(fact['typeIndex'],{}).get('instanceFields',[]))]:
   if key not in fact:continue
   names={f['name'] for f in fact[key]}
   if [f for f in rows if f['name'] in names]!=fact[key]:raise ValueError('整合撤回静态目录字段不符')
 auxiliary,auxiliarySize=characters.verify_native(client,{'nativeFiles':p['nativeFiles'],'nativeMethods':p['auxiliaryMethods'],'nativeBytes':p['auxiliaryBytes']})
 for name,filename in [('modifier_unstack_stacked','read-rogue-modifier-stacked-effects.py'),('modifier_unstack_registrations','read-rogue-modifier-registrations.py'),('modifier_unstack_owned','read-rogue-modifier-owned-effects.py')]:
  sibling(name,filename).verify_native(client)
 return methods,size,auxiliary,auxiliarySize

def analyze(a):
 client=a.client_dir.resolve();design=sibling('modifier_unstack_output','read-client-design-data.py');output=design.outside_client(a.output,client)
 if ROOT/'build' not in output.parents:raise ValueError('整合撤回证据仅输出到工程build')
 p=load_profile();methods,size,auxiliary,auxiliarySize=verify_native(client)
 result={'passed':True,'matchedCompleteNativeMethods':methods,'matchedCompleteNativeBytes':size,'matchedCompleteAuxiliaryMethods':auxiliary,'matchedCompleteAuxiliaryBytes':auxiliarySize,'matchedFieldTypes':len(p['matchedTypes']),'matchedBoundMethodIdentities':len(p['boundMethodIdentities']),**{k:v for k,v in p.items() if isinstance(v,bool)},'jsonInstalled':False,'clientExecutableStarted':False,'clientModified':False}
 output.parent.mkdir(parents=True,exist_ok=True);output.write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n','utf-8');return result
if __name__=='__main__':
 parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--client-dir',type=Path,required=True);parser.add_argument('--output',type=Path,default=ROOT/'build/rogue-modifier-unstack-evidence.json');print(json.dumps(analyze(parser.parse_args()),ensure_ascii=False))
