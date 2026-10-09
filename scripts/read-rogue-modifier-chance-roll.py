"""只读核对原始命中决定顺序、召唤者字段与布尔结果；世界、随机来源及免疫表需绑定。"""
import argparse
from functools import lru_cache
import hashlib
import importlib.util
import json
from pathlib import Path
import struct
from types import SimpleNamespace

import pefile

ROOT=Path(__file__).resolve().parents[1]
PROFILE=ROOT/'src/test/resources/fixtures/rogue-modifier-chance-roll-4.6.json'
PROFILE_SHA256='f8c0c360ec569358f4af64f4443db16f34750d5457ca94fe183d47b583fa7829'

def sibling(name,filename):
 spec=importlib.util.spec_from_file_location(name,Path(__file__).with_name(filename))
 module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module);return module

resistance=sibling('modifier_chance_roll_resistance','read-rogue-modifier-resistance.py')
numbers=resistance.numbers
entity=resistance.entity

@lru_cache(maxsize=1)
def load_profile():
 raw=PROFILE.read_bytes().replace(b'\r\n',b'\n')
 if hashlib.sha256(raw).hexdigest()!=PROFILE_SHA256:raise ValueError('命中决定冻结证据改变')
 p=json.loads(raw)
 if p['modifierResistanceProfileSHA256']!=resistance.PROFILE_SHA256:
  raise ValueError('命中决定的概率与常量来源版本不符')
 return p

def verify_bindings(client):
 return numbers.caster.selection.entry.components.read_generic_slots(client,load_profile())

def verify_native(client):
 p=load_profile();numbers.caster.selection.predicate.verify_files(client)
 chars=numbers.caster.selection.entry.onadd.characters
 methods,size=chars.verify_native(client,p);resistance.verify_native(client)
 analyzer=sibling('modifier_chance_roll_metadata','analyze-client-46.py')
 expected=[{k:m[k] for k in ['index','name','rva','typeIndex','typeName']} for m in p['nativeMethods']]
 actual=analyzer.extract(SimpleNamespace(client_dir=client,types=[t['name'] for t in p['matchedTypes']],
  fields=True,field_offsets=True,inheritance=False,type_contains=[],method_indices=[m['index'] for m in expected],
  method_rvas=[],method_name_contains=[],method_slots=[],string_slots=[],type_slots=[],disassemble=0,method_names=[]))
 if actual['selectedMethods']!=expected:raise ValueError('命中决定、原始来源及回调方法身份不符')
 types={t['typeIndex']:t for t in actual['methods']}
 for fact in p['matchedTypes']:
  t=types[fact['typeIndex']]
  if t['name']!=fact['name']:raise ValueError('命中决定字段所属类型不符')
  if 'fields' in fact and t['fields']!=fact['fields']:raise ValueError('命中决定声明字段不符')
  if 'selectedFields' in fact:
   names={f['name'] for f in fact['selectedFields']}
   if [f for f in t['fields'] if f['name'] in names]!=fact['selectedFields']:raise ValueError('召唤者、逻辑随机来源、行为/状态枚举或标准常量字段不符')
 if p['zeroInitializer']!=entity.load_profile()['missingReadInitializer'] or entity.read_missing_raw(client)!=0:
  raise ValueError('命中决定Zero并非完整构造器的配对初始化来源')
 if p['oneInitializer']!=numbers.rounds.load_profile()['oneInitializer']:raise ValueError('命中决定One原始初始化不符')
 verify_bindings(client)
 return methods,size

def analyze(a):
 client=a.client_dir.resolve();design=sibling('modifier_chance_roll_output','read-client-design-data.py')
 output=design.outside_client(a.output,client)
 if ROOT/'build' not in output.parents:raise ValueError('只接受工程build下的命中决定证据')
 p=load_profile();methods,size=verify_native(client);source=numbers.source_commands(client)
 if source!=p['sourceModifierCommands'] or any('Chance' in r['command'] for r in source):
  raise ValueError('六条来源任务不再省略Chance字段')
 result={'passed':True,'matchedCompleteNativeMethods':methods,'matchedCompleteNativeBytes':size,
  'matchedFieldTypes':len(p['matchedTypes']),'matchedGenericComponentArguments':len(p['genericMethodSlots']),
  'sourceModifierCommands':len(source),'zeroInitializerBytes':16,
  **{k:v for k,v in p.items() if isinstance(v,bool)},'jsonInstalled':False,'clientExecutableStarted':False,'clientModified':False}
 output.parent.mkdir(parents=True,exist_ok=True);output.write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n','utf-8');return result

if __name__=='__main__':
 parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--client-dir',type=Path,required=True)
 parser.add_argument('--output',type=Path,default=ROOT/'build/rogue-modifier-chance-roll-evidence.json')
 print(json.dumps(analyze(parser.parse_args()),ensure_ascii=False))
