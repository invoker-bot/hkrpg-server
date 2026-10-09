"""只读核对两个原始瞄准条件与普通条件分支生命周期；不读取在线实体。"""
import argparse
from functools import lru_cache
import hashlib
import importlib.util
import json
from pathlib import Path
import struct
from types import SimpleNamespace

import capstone
import pefile

ROOT=Path(__file__).resolve().parents[1]
PROFILE=ROOT/'src/test/resources/fixtures/rogue-predicate-branches-4.6.json'
PROFILE_SHA256='aea3c3b0a50331ed759491996ca67859a749e1750943a7a4b4e72928568a50dd'

def sibling(name,filename):
 spec=importlib.util.spec_from_file_location(name,Path(__file__).with_name(filename))
 module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module);return module

definitions=sibling('predicate_branch_definitions','read-rogue-modifier-definitions.py')

@lru_cache(maxsize=1)
def load_profile():
 raw=PROFILE.read_bytes().replace(b'\r\n',b'\n')
 if hashlib.sha256(raw).hexdigest()!=PROFILE_SHA256:raise ValueError('条件分支原生证据改变')
 p=json.loads(raw)
 if p['definitionProfileSHA256']!=definitions.PROFILE_SHA256:raise ValueError('原始回调版本不符')
 return p

def args(client,**extra):
 return SimpleNamespace(client_dir=client,types=[],type_contains=[],method_indices=[],method_rvas=[],
  method_name_contains=[],method_slots=[],string_slots=[],type_slots=[],fields=False,
  field_offsets=False,inheritance=False,disassemble=0,method_names=[],**extra)

def metadata(client,**extra):
 analyzer=sibling('predicate_branch_metadata','analyze-client-46.py')
 a=args(client)
 for k,v in extra.items():setattr(a,k,v)
 return analyzer.extract(a)

def verify_native(client):
 p=load_profile();counts=definitions.onadd.characters.verify_native(client,p)
 methods=p['nativeMethods']+p['nativeLeaves']
 actual=metadata(client,types=[t['name'] for t in p['runtimeTypes']],
  method_indices=[m['index'] for m in methods],fields=True,field_offsets=True,
  type_slots=[int(s['slot'],16) for s in p['typeInfoSlots']],
  method_slots=[int(s['slot'],16) for s in p['methodInfoSlots']])
 expected=[{k:m[k] for k in ['index','name','rva','typeIndex','typeName']} for m in methods]
 if actual['selectedMethods']!=expected or actual['typeInfoSlots']!=p['typeInfoSlots'] or actual['methodInfoSlots']!=p['methodInfoSlots']:
  raise ValueError('条件/分支方法、类槽或原始工厂身份不符')
 runtime={t['typeIndex']:t for t in actual['methods']}
 for t in p['runtimeTypes']:
  if {k:runtime[t['typeIndex']][k] for k in t}!=t:raise ValueError('条件/分支声明字段不符')
 actual=metadata(client,types=[t['name'] for t in p['configTypes']]+[p['entityType']['name']],
  fields=True,field_offsets=True,inheritance=True)
 types={t['typeIndex']:t for t in actual['methods']}
 for t in p['configTypes']:
  if {k:types[t['typeIndex']][k] for k in t}!=t:raise ValueError('原始条件配置完整字段/继承关系不符')
 if p['teamField'] not in types[p['entityType']['typeIndex']]['instanceFields']:
  raise ValueError('实体队伍字段身份不符')
 pe=pefile.PE(str(client/'GameAssembly.dll'),fast_load=True)
 directory=pe.OPTIONAL_HEADER.DATA_DIRECTORY[pefile.DIRECTORY_ENTRY['IMAGE_DIRECTORY_ENTRY_EXCEPTION']]
 starts={s for s,e,u in struct.iter_unpack('<III',pe.get_data(directory.VirtualAddress,directory.Size))}
 decoder=capstone.Cs(capstone.CS_ARCH_X86,capstone.CS_MODE_64)
 for leaf in p['nativeLeaves']:
  rva=int(leaf['rva'],16);size=leaf['bytes'];section=pe.get_section_by_rva(rva)
  if rva in starts or section is None or rva-section.VirtualAddress+size>section.SizeOfRawData:
   raise ValueError('构造叶片段边界不符，不计入完整PE方法')
  raw=pe.get_data(rva,size)
  rows=[{'rva':hex(i.address-pe.OPTIONAL_HEADER.ImageBase),'mnemonic':i.mnemonic,'operand':i.op_str}
   for i in decoder.disasm(raw,rva+pe.OPTIONAL_HEADER.ImageBase)]
  if raw.hex()!=leaf['bytesHex'] or hashlib.sha256(raw).hexdigest()!=leaf['sha256'] or rows!=leaf['instructions'] or rows[-1]['mnemonic']!='ret':
   raise ValueError('Enabled或存活掩码构造叶片段不符')
 return counts

@lru_cache(maxsize=1)
def source_conditions(client):
 _,global_defs=definitions.source_definitions(client);result=[]
 for name,d in global_defs['entries']:
  for c in d['_CallbackList']:
   for task in c['CallbackConfig']:
    if task['$type']=='PredicateTaskList':result.append({'modifier':name,'event':c['Event'],'task':task})
 if result!=load_profile()['sourceConditions']:raise ValueError('两个完整原始条件分支或省略值改变')
 return result

def analyze(a):
 client=a.client_dir.resolve();design=sibling('predicate_branch_output','read-client-design-data.py')
 output=design.outside_client(a.output,client)
 if ROOT/'build' not in output.parents:raise ValueError('只接受工程build下的条件分支证据')
 p=load_profile();methods,size=verify_native(client);conditions=source_conditions(client)
 result={'passed':True,'matchedNativeMethods':methods,'matchedNativeBytes':size,
  'matchedConstructorLeaves':len(p['nativeLeaves']),'matchedConstructorLeafBytes':sum(m['bytes'] for m in p['nativeLeaves']),
  'sourceConditions':len(conditions),'compareDefaultAliveMask':p['compareDefaultAliveMask'],
  'teamSelectionAliveMask':p['teamSelectionAliveMask'],**{k:v for k,v in p.items() if isinstance(v,bool)},
  'jsonInstalled':False,'clientExecutableStarted':False,'clientModified':False}
 output.parent.mkdir(parents=True,exist_ok=True);output.write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n','utf-8');return result

if __name__=='__main__':
 parser=argparse.ArgumentParser(description=__doc__)
 parser.add_argument('--client-dir',type=Path,required=True)
 parser.add_argument('--output',type=Path,default=ROOT/'build/rogue-predicate-branches-evidence.json')
 print(json.dumps(analyze(parser.parse_args()),ensure_ascii=False))
