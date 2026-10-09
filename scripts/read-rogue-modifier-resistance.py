"""只读核对行为抗性最大值、命中加成与两种抗性补数；不获取在线属性或执行抽样。"""
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
PROFILE=ROOT/'src/test/resources/fixtures/rogue-modifier-resistance-4.6.json'
PROFILE_SHA256='44491bc2bb3271a3f293e4848d59be7d4c9d46996b79a5db097b693ca9c4a975'

def sibling(name,filename):
 spec=importlib.util.spec_from_file_location(name,Path(__file__).with_name(filename))
 module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module);return module

chance=sibling('modifier_resistance_chance','read-rogue-modifier-chance.py')
numbers=chance.numbers
entity=chance.entity

@lru_cache(maxsize=1)
def load_profile():
 raw=PROFILE.read_bytes().replace(b'\r\n',b'\n')
 if hashlib.sha256(raw).hexdigest()!=PROFILE_SHA256:raise ValueError('效果抗性冻结证据改变')
 p=json.loads(raw)
 if p['modifierChanceProfileSHA256']!=chance.PROFILE_SHA256:
  raise ValueError('效果抗性的概率与常量来源版本不符')
 return p

def verify_bindings(client):
 p=load_profile();predicate=numbers.caster.selection.predicate
 pe=pefile.PE(str(client/'GameAssembly.dll'),fast_load=True);base=pe.OPTIONAL_HEADER.ImageBase
 read=lambda address,size:predicate.read_rva(pe,address,size)
 header=read(0x48b19b0,0x208);word=lambda offset:struct.unpack_from('<I',header,offset)[0]
 startup=(client/'StarRail_Data/il2cpp_data/Metadata/startup-metadata.dat').read_bytes()
 table=word(0x164)^0x7f5c5934;baseline=predicate.load_profile()
 if table!=int(baseline['genericClassTableOffsetInStartup'],16) or len(startup)!=baseline['startupMetadataLength']:
  raise ValueError('抗性绑定泛型startup范围不符')
 registration=p['genericInstantiationRegistration']
 array=struct.unpack('<Q',read(int(registration['pointerWordRva'],16),8))[0]-base
 if array!=int(registration['arrayRva'],16):raise ValueError('抗性绑定泛型参数注册表不符')
 for fact,definition,kinds,payloads in zip(p['mappingGenerics'],[1535,1535],[[17,18],[17,18]],[[10407,56339],[10407,56339]],strict=True):
  address=0x4ffdd60+fact['field']['nativeTypeIndex']*8;raw=read(address,8)
  if (hex(address),raw.hex(),raw[6])!=(fact['nativeTypeRecordRva'],fact['nativeTypeRecordHex'],21):
   raise ValueError('抗性绑定字段类型不符')
  pos=table+struct.unpack_from('<I',raw)[0]*8;record=startup[pos:pos+8]
  if len(record)!=8 or (hex(pos),record.hex(),struct.unpack('<ii',record))!=(fact['startupRowOffset'],fact['startupRowHex'],(definition,fact['instIndex'])):
   raise ValueError('抗性绑定抗性/命中字典的泛型定义不符')
  address=array+fact['instIndex']*16;raw=read(address,16);count,reserved,argv=struct.unpack('<IIQ',raw)
  if (hex(address),raw.hex(),count,reserved)!=(fact['instRva'],fact['instHex'],len(kinds),0):
   raise ValueError('抗性绑定泛型参数记录不符')
  if [a['kind'] for a in fact['args']]!=kinds or [a['definition'] for a in fact['args']]!=payloads:
   raise ValueError('抗性绑定参数并非行为枚举到当前属性值对象')
  for (pointer,),arg in zip(struct.iter_unpack('<Q',read(argv-base,count*8)),fact['args'],strict=True):
   raw=read(pointer-base,8)
   if (hex(pointer-base),raw.hex(),raw[6],struct.unpack_from('<I',raw)[0])!=(arg['nativeRva'],arg['bytesHex'],arg['kind'],arg['definition']):
    raise ValueError('抗性绑定参数实际类型不符')
 return 4

def verify_native(client):
 p=load_profile();numbers.caster.selection.predicate.verify_files(client)
 chars=numbers.caster.selection.entry.onadd.characters
 methods,size=chars.verify_native(client,p);numbers.rounds.verify_native(client)
 analyzer=sibling('modifier_resistance_metadata','analyze-client-46.py')
 expected=[{k:m[k] for k in ['index','name','rva','typeIndex','typeName']} for m in p['nativeMethods']]
 actual=analyzer.extract(SimpleNamespace(client_dir=client,types=[t['name'] for t in p['matchedTypes']],
  fields=True,field_offsets=True,inheritance=False,type_contains=[],method_indices=[m['index'] for m in expected]+[68470],
  method_rvas=[],method_name_contains=[],method_slots=[],string_slots=[],type_slots=[],disassemble=0,method_names=[]))
 if actual['selectedMethods']!=expected+[p['subtractionDefinition']]:raise ValueError('抗性和额外命中计算方法身份不符')
 types={t['typeIndex']:t for t in actual['methods']}
 for fact in p['matchedTypes']:
  t=types[fact['typeIndex']]
  if t['name']!=fact['name']:raise ValueError('抗性字段所属类型不符')
  if 'fields' in fact and t['fields']!=fact['fields']:raise ValueError('抗性声明字段不符')
  if 'selectedFields' in fact:
   names={f['name'] for f in fact['selectedFields']}
   if [f for f in t['fields'] if f['name'] in names]!=fact['selectedFields']:raise ValueError('抗性/命中字典、属性枚举或标准常量字段不符')
 if p['zeroInitializer']!=entity.load_profile()['missingReadInitializer'] or entity.read_missing_raw(client)!=0:
  raise ValueError('抗性Zero并非完整构造器的配对初始化来源')
 if p['oneInitializer']!=numbers.rounds.load_profile()['oneInitializer']:raise ValueError('抗性One原始初始化不符')
 pe=pefile.PE(str(client/'GameAssembly.dll'),fast_load=True);fragment=p['standardSubtractionFragment']
 raw=numbers.caster.selection.predicate.read_rva(pe,int(fragment['rva'],16),fragment['length'])
 if raw.hex()!=fragment['bytesHex'] or hashlib.sha256(raw).hexdigest()!=fragment['sha256']:
  raise ValueError('标准减法前缀与有限结果范围片段改变')
 if fragment['completeNativeMethod'] is not False:raise ValueError('减法片段不能计为完整方法')
 verify_bindings(client)
 return methods,size

def analyze(a):
 client=a.client_dir.resolve();design=sibling('modifier_resistance_output','read-client-design-data.py')
 output=design.outside_client(a.output,client)
 if ROOT/'build' not in output.parents:raise ValueError('只接受工程build下的抗性证据')
 p=load_profile();methods,size=verify_native(client);source=numbers.source_commands(client)
 if source!=p['sourceModifierCommands'] or any('Chance' in r['command'] for r in source):
  raise ValueError('六条来源任务不再省略Chance字段')
 result={'passed':True,'matchedCompleteNativeMethods':methods,'matchedCompleteNativeBytes':size,
  'matchedFieldTypes':len(p['matchedTypes']),'matchedMappingGenericArguments':4,
  'sourceModifierCommands':len(source),'zeroInitializerBytes':16,'matchedStandardSubtractionFragmentBytes':p['standardSubtractionFragment']['length'],'statusProbabilityProperty':132,'statusResistanceProperty':135,
  **{k:v for k,v in p.items() if isinstance(v,bool)},'jsonInstalled':False,'clientExecutableStarted':False,'clientModified':False}
 output.parent.mkdir(parents=True,exist_ok=True);output.write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n','utf-8');return result

if __name__=='__main__':
 parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--client-dir',type=Path,required=True)
 parser.add_argument('--output',type=Path,default=ROOT/'build/rogue-modifier-resistance-evidence.json')
 print(json.dumps(analyze(parser.parse_args()),ensure_ascii=False))
