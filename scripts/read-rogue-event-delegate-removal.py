"""只读核对普通事件委托与多播委托撤回的来源和执行边界。"""
import argparse
from functools import lru_cache
import hashlib
import importlib.util
import json
import struct
from pathlib import Path
from types import SimpleNamespace

ROOT=Path(__file__).resolve().parents[1]
PROFILE=ROOT/'src/test/resources/fixtures/rogue-event-delegate-removal-4.6.json'
PROFILE_SHA256='fb78e9b7c4015f9a756ad478ccf4a8539704252278e8a453396b644b2e898822'

def sibling(name,filename):
 spec=importlib.util.spec_from_file_location(name,Path(__file__).with_name(filename))
 module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module);return module

characters=sibling('event_delegate_removal_characters','read-rogue-resonance-character.py')

@lru_cache(maxsize=1)
def load_profile():
 raw=PROFILE.read_bytes().replace(b'\r\n',b'\n')
 if hashlib.sha256(raw).hexdigest()!=PROFILE_SHA256:raise ValueError('委托撤回冻结证据改变')
 return json.loads(raw)

def metadata(client,types,methods,inheritance=False):
 analyzer=sibling('event_delegate_removal_metadata','analyze-client-46.py')
 return analyzer.extract(SimpleNamespace(client_dir=client,types=types,fields=True,field_offsets=True,
  inheritance=inheritance,type_contains=[],method_indices=methods,method_rvas=[],method_name_contains=[],
  method_slots=[],string_slots=[],type_slots=[],disassemble=0,method_names=[]))

def verify_native(client):
 p=load_profile();methods,size=characters.verify_native(client,p)
 expected=sorted([{k:m[k] for k in ['index','name','rva','typeIndex','typeName']} for m in p['nativeMethods']]+p['boundMethodIdentities'],key=lambda m:m['index'])
 actual=metadata(client,[t['name'] for t in p['matchedTypes']],[m['index'] for m in expected])
 if actual['selectedMethods']!=expected:raise ValueError('回合结束、清理、释放和明确绑定方法身份不符')
 declared={t['typeIndex']:t for t in actual['methods']}
 inheritedNames=[t['name'] for t in p['matchedTypes'] if 'selectedInstanceFields' in t]
 inherited={t['typeIndex']:t for t in metadata(client,inheritedNames,[],True)['methods']}
 for fact in p['matchedTypes']:
  t=declared[fact['typeIndex']]
  if t['name']!=fact['name']:raise ValueError('委托撤回字段所属类型不符')
  if 'fields' in fact and t['fields']!=fact['fields']:raise ValueError('委托撤回枚举完整声明不符')
  for key,rows in [('selectedFields',t['fields']),('selectedInstanceFields',inherited.get(fact['typeIndex'],{}).get('instanceFields',[]))]:
   if key not in fact:continue
   names={f['name'] for f in fact[key]}
   if [f for f in rows if f['name'] in names]!=fact[key]:raise ValueError('委托目标、数据与当前多播列表字段不符')
 auxiliary,auxiliarySize=characters.verify_native(client,{'nativeFiles':p['nativeFiles'],'nativeMethods':p['auxiliaryMethods'],'nativeBytes':p['auxiliaryBytes']})
 base=sibling('event_delegate_removal_base','read-rogue-event-delegate-equality.py');base.verify_native(client)
 return methods,size,auxiliary,auxiliarySize

def analyze(a):
 client=a.client_dir.resolve();design=sibling('event_delegate_removal_output','read-client-design-data.py')
 output=design.outside_client(a.output,client)
 if ROOT/'build' not in output.parents:raise ValueError('只接受工程build下的委托撤回证据')
 p=load_profile();methods,size,auxiliary,auxiliarySize=verify_native(client)
 result={'passed':True,'matchedCompleteNativeMethods':methods,'matchedCompleteNativeBytes':size,
  'matchedCompleteAuxiliaryMethods':auxiliary,'matchedCompleteAuxiliaryBytes':auxiliarySize,'matchedFieldTypes':len(p['matchedTypes']),'matchedBoundMethodIdentities':len(p['boundMethodIdentities']),**{k:v for k,v in p.items() if isinstance(v,bool)},
  'jsonInstalled':False,'clientExecutableStarted':False,'clientModified':False}
 output.parent.mkdir(parents=True,exist_ok=True);output.write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n','utf-8');return result

if __name__=='__main__':
 parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--client-dir',type=Path,required=True)
 parser.add_argument('--output',type=Path,default=ROOT/'build/rogue-event-delegate-removal-evidence.json')
 print(json.dumps(analyze(parser.parse_args()),ensure_ascii=False))
