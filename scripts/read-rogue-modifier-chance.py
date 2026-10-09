"""只读核对概率覆盖、外部修正、名称/行为筛选；不执行概率抽样或创建实例。"""
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
PROFILE=ROOT/'src/test/resources/fixtures/rogue-modifier-chance-4.6.json'
PROFILE_SHA256='27591b2f3672388f6f5fcf9358385e968c1962927fc775ae1cd56330292b40a3'

def sibling(name,filename):
 spec=importlib.util.spec_from_file_location(name,Path(__file__).with_name(filename))
 module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module);return module

numbers=sibling('modifier_chance_numbers','read-rogue-modifier-numbers.py')
entity=sibling('modifier_chance_zero','read-rogue-resonance-entity-lookup.py')

@lru_cache(maxsize=1)
def load_profile():
 raw=PROFILE.read_bytes().replace(b'\r\n',b'\n')
 if hashlib.sha256(raw).hexdigest()!=PROFILE_SHA256:raise ValueError('效果概率冻结证据改变')
 p=json.loads(raw)
 if p['modifierNumbersProfileSHA256']!=numbers.PROFILE_SHA256 or p['entityLookupProfileSHA256']!=entity.PROFILE_SHA256:
  raise ValueError('效果概率整数计算或Zero来源版本不符')
 return p

def verify_bindings(client):
 p=load_profile();predicate=numbers.caster.selection.predicate
 pe=pefile.PE(str(client/'GameAssembly.dll'),fast_load=True);base=pe.OPTIONAL_HEADER.ImageBase
 read=lambda address,size:predicate.read_rva(pe,address,size)
 header=read(0x48b19b0,0x208);word=lambda offset:struct.unpack_from('<I',header,offset)[0]
 startup=(client/'StarRail_Data/il2cpp_data/Metadata/startup-metadata.dat').read_bytes()
 table=word(0x164)^0x7f5c5934;baseline=predicate.load_profile()
 if table!=int(baseline['genericClassTableOffsetInStartup'],16) or len(startup)!=baseline['startupMetadataLength']:
  raise ValueError('概率绑定泛型startup范围不符')
 registration=p['genericInstantiationRegistration']
 array=struct.unpack('<Q',read(int(registration['pointerWordRva'],16),8))[0]-base
 if array!=int(registration['arrayRva'],16):raise ValueError('概率绑定泛型参数注册表不符')
 for fact,definition,kinds,payloads in zip(p['mappingGenerics'],[1535,3696],[[14,18],[17]],[[330,57756],[10407]],strict=True):
  address=0x4ffdd60+fact['field']['nativeTypeIndex']*8;raw=read(address,8)
  if (hex(address),raw.hex(),raw[6])!=(fact['nativeTypeRecordRva'],fact['nativeTypeRecordHex'],21):
   raise ValueError('概率绑定字段类型不符')
  pos=table+struct.unpack_from('<I',raw)[0]*8;record=startup[pos:pos+8]
  if len(record)!=8 or (hex(pos),record.hex(),struct.unpack('<ii',record))!=(fact['startupRowOffset'],fact['startupRowHex'],(definition,fact['instIndex'])):
   raise ValueError('概率绑定字典或静音集合的泛型定义不符')
  address=array+fact['instIndex']*16;raw=read(address,16);count,reserved,argv=struct.unpack('<IIQ',raw)
  if (hex(address),raw.hex(),count,reserved)!=(fact['instRva'],fact['instHex'],len(kinds),0):
   raise ValueError('概率绑定泛型参数记录不符')
  if [a['kind'] for a in fact['args']]!=kinds or [a['definition'] for a in fact['args']]!=payloads:
   raise ValueError('概率绑定参数并非字符串/修正对象/行为枚举')
  for (pointer,),arg in zip(struct.iter_unpack('<Q',read(argv-base,count*8)),fact['args'],strict=True):
   raw=read(pointer-base,8)
   if (hex(pointer-base),raw.hex(),raw[6],struct.unpack_from('<I',raw)[0])!=(arg['nativeRva'],arg['bytesHex'],arg['kind'],arg['definition']):
    raise ValueError('概率绑定参数实际类型不符')
 slot=p['acquireListSlot'];index=slot['pairIndex']
 pairs=((word(0x190)+0xed791073)&0xffffffff)+0x208
 literals=(word(0x1f0)^0x56c7d20d)+0x208
 specs=((word(0x38)+0xf778f1ab)&0xffffffff)+0x208
 data=(client/'StarRail_Data/il2cpp_data/Metadata/global-metadata.dat').read_bytes()
 if not 0<=pairs+index*8<=literals-8<=len(data)-8:raise ValueError('概率列表方法使用记录越界')
 first,second=struct.unpack_from('<II',data,pairs+index*8)
 key=(((index*0x87c3)^0x5fa3fad3)*0x334fb2ba+0x454d10d89e6a02a)&((1<<64)-1)
 key=(((key>>14)*0x102533d7+0x58972d9863a807)&((1<<64)-1))>>23
 destination=((first^0x6907ab9a)-key)&0xffffffff;source=(second-key+0xab3a6cb5)&0xffffffff
 if (destination,source>>29,source&0x1fffffff)!=(slot['destination'],6,slot['payload']) or int(slot['slot'],16)!=0x944d430+destination*8:
  raise ValueError('概率列表方法槽或独立槽基址不符')
 address=specs+slot['payload']*12
 if not 0<=address<=len(data)-12:raise ValueError('概率列表MethodSpec截断')
 definition,at4,at8=struct.unpack_from('<iii',data,address)
 if {'methodDefinition':definition,'argumentIndexAt4':at4,'argumentIndexAt8':at8}!=slot['methodSpec'] or definition!=41441 or at8!=-1:
  raise ValueError('不是已匹配的AcquireList方法')
 inst=slot['arguments'][0];raw=read(array+at4*16,16);count,reserved,argv=struct.unpack('<IIQ',raw)
 if (at4,hex(array+at4*16),raw.hex(),count,reserved)!=(inst['index'],inst['rva'],inst['raw'],1,0):
  raise ValueError('概率列表单参数记录不符')
 pointer=struct.unpack('<Q',read(argv-base,8))[0];raw=read(pointer-base,8);arg=inst['arguments'][0]
 if (hex(pointer-base),raw.hex(),raw[6],struct.unpack_from('<I',raw)[0])!=(arg['rva'],arg['raw'],18,57756):
  raise ValueError('概率列表元素不是已匹配的修正对象')
 return 3,1

def verify_native(client):
 p=load_profile();numbers.caster.selection.predicate.verify_files(client)
 chars=numbers.caster.selection.entry.onadd.characters
 methods,size=chars.verify_native(client,p);numbers.rounds.verify_native(client)
 analyzer=sibling('modifier_chance_metadata','analyze-client-46.py')
 expected=[{k:m[k] for k in ['index','name','rva','typeIndex','typeName']} for m in p['nativeMethods']]
 actual=analyzer.extract(SimpleNamespace(client_dir=client,types=[t['name'] for t in p['matchedTypes']],
  fields=True,field_offsets=True,inheritance=False,type_contains=[],method_indices=[m['index'] for m in expected]+[41441],
  method_rvas=[],method_name_contains=[],method_slots=[],string_slots=[],type_slots=[],disassemble=0,method_names=[]))
 if actual['selectedMethods']!=expected+[p['acquireListDefinition']]:raise ValueError('概率计算、聚合、筛选或列表方法定义身份不符')
 types={t['typeIndex']:t for t in actual['methods']}
 for fact in p['matchedTypes']:
  t=types[fact['typeIndex']]
  if t['name']!=fact['name']:raise ValueError('概率字段所属类型不符')
  if 'fields' in fact and t['fields']!=fact['fields']:raise ValueError('概率三元组或修正对象声明字段不符')
  if 'selectedFields' in fact:
   names={f['name'] for f in fact['selectedFields']}
   if [f for f in t['fields'] if f['name'] in names]!=fact['selectedFields']:raise ValueError('概率字典、静音集合或标准常量字段不符')
 if p['zeroInitializer']!=entity.load_profile()['missingReadInitializer'] or entity.read_missing_raw(client)!=0:
  raise ValueError('概率Zero并非完整构造器的配对初始化来源')
 if p['oneInitializer']!=numbers.rounds.load_profile()['oneInitializer']:raise ValueError('概率One原始初始化不符')
 verify_bindings(client)
 return methods,size

def analyze(a):
 client=a.client_dir.resolve();design=sibling('modifier_chance_output','read-client-design-data.py')
 output=design.outside_client(a.output,client)
 if ROOT/'build' not in output.parents:raise ValueError('只接受工程build下的概率证据')
 p=load_profile();methods,size=verify_native(client);source=numbers.source_commands(client)
 if source!=p['sourceModifierCommands'] or any('Chance' in r['command'] for r in source):
  raise ValueError('六条来源任务不再省略Chance字段')
 result={'passed':True,'matchedCompleteNativeMethods':methods,'matchedCompleteNativeBytes':size,
  'matchedFieldTypes':len(p['matchedTypes']),'matchedMappingGenericArguments':3,'matchedAcquireListGenericArguments':1,
  'sourceModifierCommands':len(source),'sourceChanceOmissions':len(source),'zeroInitializerBytes':16,
  **{k:v for k,v in p.items() if isinstance(v,bool)},'jsonInstalled':False,'clientExecutableStarted':False,'clientModified':False}
 output.parent.mkdir(parents=True,exist_ok=True);output.write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n','utf-8');return result

if __name__=='__main__':
 parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--client-dir',type=Path,required=True)
 parser.add_argument('--output',type=Path,default=ROOT/'build/rogue-modifier-chance-evidence.json')
 print(json.dumps(analyze(parser.parse_args()),ensure_ascii=False))
