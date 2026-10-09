"""只读核对固定数值注入、编码比较和边界；扩展单元、吸附信息和世界仍待完成。"""
import argparse
from functools import lru_cache
import hashlib
import importlib.util
import json
import struct
from pathlib import Path
from types import SimpleNamespace

ROOT=Path(__file__).resolve().parents[1]
PROFILE=ROOT/'src/test/resources/fixtures/rogue-modifier-injection-4.6.json'
PROFILE_SHA256='6ca1a75a61a43d0c5e00ca3fa5832fd801bbe2bb1d6118991ed8384501f246c8'

def sibling(name,filename):
 spec=importlib.util.spec_from_file_location(name,Path(__file__).with_name(filename))
 module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module);return module

characters=sibling('modifier_injection_characters','read-rogue-resonance-character.py')

@lru_cache(maxsize=1)
def load_profile():
 raw=PROFILE.read_bytes().replace(b'\r\n',b'\n')
 if hashlib.sha256(raw).hexdigest()!=PROFILE_SHA256:raise ValueError('修改器数值注入冻结证据改变')
 return json.loads(raw)

def metadata(client,types,methods,inheritance=False):
 analyzer=sibling('modifier_injection_metadata','analyze-client-46.py')
 return analyzer.extract(SimpleNamespace(client_dir=client,types=types,fields=True,field_offsets=True,
  inheritance=inheritance,type_contains=[],method_indices=methods,method_rvas=[],method_name_contains=[],
  method_slots=[],string_slots=[],type_slots=[],disassemble=0,method_names=[]))

def verify_native(client):
 p=load_profile();methods,size=characters.verify_native(client,p)
 expected=[{k:m[k] for k in ['index','name','rva','typeIndex','typeName']} for m in p['nativeMethods']+p['nativeFragments']]
 actual=metadata(client,[t['name'] for t in p['matchedTypes']],[m['index'] for m in expected])
 if actual['selectedMethods']!=expected:raise ValueError('固定数值注入、静态初值及比较/裁剪方法身份不符')
 declared={t['typeIndex']:t for t in actual['methods']}
 inheritedNames=[t['name'] for t in p['matchedTypes'] if 'selectedInstanceFields' in t]
 inherited={t['typeIndex']:t for t in metadata(client,inheritedNames,[],True)['methods']}
 for fact in p['matchedTypes']:
  t=declared[fact['typeIndex']]
  if t['name']!=fact['name']:raise ValueError('修改器数值注入字段所属类型不符')
  if 'fields' in fact and t['fields']!=fact['fields']:raise ValueError('修改器数值注入枚举完整声明不符')
  for key,rows in [('selectedFields',t['fields']),('selectedInstanceFields',inherited.get(fact['typeIndex'],{}).get('instanceFields',[]))]:
   if key not in fact:continue
   names={f['name'] for f in fact[key]}
   if [f for f in rows if f['name'] in names]!=fact[key]:raise ValueError('固定数值参数、原始值或编码常量字段不符')
 import pefile
 components=sibling('modifier_injection_components','read-rogue-resonance-components.py')
 pe=pefile.PE(str(client/'GameAssembly.dll'),fast_load=True)
 for fact in p['nativeFragments']:
  raw=components.read_rva(pe,int(fact['rva'],16),fact['fragmentLength'])
  if raw.hex()!=fact['bytesHex'] or hashlib.sha256(raw).hexdigest()!=fact['sha256']:raise ValueError('数值比较或裁剪片段不符')
 minimum=p['minimumInitializer'];raw=components.read_rva(pe,int(minimum['sourceSlotRva'],16),minimum['sourceLength'])
 if raw.hex()!=minimum['sourceBytesHex'] or struct.unpack_from('<q',raw)[0]!=minimum['raw']:raise ValueError('数值注入默认下限原件不符')
 if p['maximumInitializer']['raw']!=6710886399932891137 or minimum['raw']!=-6710886399932891135:raise ValueError('数值注入默认范围不符')
 return methods,size

def analyze(a):
 client=a.client_dir.resolve();design=sibling('modifier_injection_output','read-client-design-data.py')
 output=design.outside_client(a.output,client)
 if ROOT/'build' not in output.parents:raise ValueError('只接受工程build下的修改器数值注入证据')
 p=load_profile();methods,size=verify_native(client)
 result={'passed':True,'matchedCompleteNativeMethods':methods,'matchedCompleteNativeBytes':size,
  'matchedFieldTypes':len(p['matchedTypes']),'matchedNativeMethodFragments':len(p['nativeFragments']),'matchedNativeFragmentBytes':p['nativeFragmentBytes'],**{k:v for k,v in p.items() if isinstance(v,bool)},
  'jsonInstalled':False,'clientExecutableStarted':False,'clientModified':False}
 output.parent.mkdir(parents=True,exist_ok=True);output.write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n','utf-8');return result

if __name__=='__main__':
 parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--client-dir',type=Path,required=True)
 parser.add_argument('--output',type=Path,default=ROOT/'build/rogue-modifier-injection-evidence.json')
 print(json.dumps(analyze(parser.parse_args()),ensure_ascii=False))
