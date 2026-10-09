"""只读核对六条原始效果任务的默认写入、魅惑跳过及有限入口；完整效果待接入。"""
import argparse
from functools import lru_cache
import hashlib
import importlib.util
import json
from pathlib import Path
from types import SimpleNamespace

ROOT=Path(__file__).resolve().parents[1]
PROFILE=ROOT/'src/test/resources/fixtures/rogue-modifier-entry-4.6.json'
PROFILE_SHA256='a2b89c701f6c452541f1c304e54d9da0c2ffbd515288b5583d927058dbb0abaa'


def sibling(name,filename):
 spec=importlib.util.spec_from_file_location(name,Path(__file__).with_name(filename))
 module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module);return module


onadd=sibling('modifier_entry_onadd','read-rogue-resonance-onadd.py')
components=sibling('modifier_entry_components','read-rogue-resonance-components.py')


@lru_cache(maxsize=1)
def load_profile():
 raw=PROFILE.read_bytes().replace(b'\r\n',b'\n')
 if hashlib.sha256(raw).hexdigest()!=PROFILE_SHA256:raise ValueError('有限效果入口证据改变')
 p=json.loads(raw)
 if p['onAddProfileSHA256']!=onadd.PROFILE_SHA256:raise ValueError('原始OnAdd证据版本不符')
 return p


def verify_native(client):
 p=load_profile();counts=onadd.characters.verify_native(client,p)
 analyzer=sibling('modifier_entry_metadata','analyze-client-46.py')
 actual=analyzer.extract(SimpleNamespace(client_dir=client,
  types=[p[k]['name'] for k in ['configType','dynamicType','aliveMaskType']],type_contains=[],
  method_indices=[m['index'] for m in p['nativeMethods']],method_rvas=[],method_name_contains=[],
  method_slots=[],string_slots=[],type_slots=[],fields=True,field_offsets=True,inheritance=False,
  disassemble=0,method_names=[]))
 expected=[{k:m[k] for k in ['index','name','rva','typeIndex','typeName']} for m in p['nativeMethods']]
 if actual['selectedMethods']!=expected:raise ValueError('完整效果入口或来源/目标方法身份不符')
 types={t['typeIndex']:t for t in actual['methods']}
 for key in ['configType','dynamicType','aliveMaskType']:
  fact=p[key];native=types[fact['typeIndex']]
  matched={k:native[k] for k in fact}
  if len(native['fields'])!=len(fact['fields']):raise ValueError('声明字段数量不符')
  matched['fields']=[{k:actual[k] for k in frozen} for frozen,actual in zip(fact['fields'],native['fields'],strict=True)]
  if matched!=fact:raise ValueError('效果配置/定点数/存活掩码声明字段不符')
 fact=p['sourceCharmComponent']
 actual=analyzer.extract(SimpleNamespace(client_dir=client,types=[fact['name']],type_contains=[],
  method_indices=[],method_rvas=[],method_name_contains=[],method_slots=[],string_slots=[],type_slots=[],
  fields=True,field_offsets=True,inheritance=True,disassemble=0,method_names=[]))
 native=next(t for t in actual['methods'] if t['typeIndex']==fact['typeIndex'])
 if native['baseTypeChain']!=fact['baseTypeChain'] or fact['field'] not in native['instanceFields']:
  raise ValueError('来源魅惑字段的类型或实际偏移不符')
 components.read_generic_slots(client,p)
 return counts


def source_commands(client):
 sources=onadd.source_commands(client)
 modifiers=[{'eventId':s['eventId'],'command':t} for s in sources for t in s['commands'] if t['taskId']==50]
 if modifiers!=load_profile()['sourceModifierCommands']:raise ValueError('六条原始效果字段不符')
 return modifiers


def analyze(args):
 client=args.client_dir.resolve();design=sibling('modifier_entry_output','read-client-design-data.py')
 output=design.outside_client(args.output,client)
 if ROOT/'build' not in output.parents:raise ValueError('只接受工程build下的效果入口只读证据')
 p=load_profile();methods,size=verify_native(client);source=source_commands(client)
 flags={k:v for k,v in p.items() if isinstance(v,bool)}
 result={'passed':True,'matchedNativeMethods':methods,'matchedNativeBytes':size,
  'sourceModifierCommands':len(source),'sourceCharmTypeIndex':p['sourceCharmComponent']['typeIndex'],
  'sourceCharmFieldOffset':p['sourceCharmComponent']['field']['instanceOffset'],
  'genericGetComponentSlots':len(p['genericMethodSlots']),**flags,
  'jsonInstalled':False,'clientExecutableStarted':False,'clientModified':False}
 output.parent.mkdir(parents=True,exist_ok=True)
 output.write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n','utf-8');return result


if __name__=='__main__':
 parser=argparse.ArgumentParser(description=__doc__)
 parser.add_argument('--client-dir',type=Path,required=True)
 parser.add_argument('--output',type=Path,default=ROOT/'build/rogue-modifier-entry-evidence.json')
 print(json.dumps(analyze(parser.parse_args()),ensure_ascii=False))
