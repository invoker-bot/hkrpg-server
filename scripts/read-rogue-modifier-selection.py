"""只读核对原始修改器的名称选择、存活与敌方战斗事件资格；实例与其他限制待绑定。"""
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
PROFILE=ROOT/'src/test/resources/fixtures/rogue-modifier-selection-4.6.json'
PROFILE_SHA256='05972faf59f0ce11dd1b3b8553c5cfa0b1400c05663df1e8e4c54cccc57ed569'

def sibling(name,filename):
 spec=importlib.util.spec_from_file_location(name,Path(__file__).with_name(filename))
 module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module);return module

entry=sibling('modifier_selection_entry','read-rogue-modifier-entry.py')
predicate=sibling('modifier_selection_generic','read-rogue-predicate-task.py')

@lru_cache(maxsize=1)
def load_profile():
 raw=PROFILE.read_bytes().replace(b'\r\n',b'\n')
 if hashlib.sha256(raw).hexdigest()!=PROFILE_SHA256:raise ValueError('修改器名称与资格证据改变')
 p=json.loads(raw)
 if p['modifierEntryProfileSHA256']!=entry.PROFILE_SHA256 or p['predicateTaskProfileSHA256']!=predicate.PROFILE_SHA256:
  raise ValueError('修改器入口或泛型原件版本不符')
 return p

def verify_override_mapping(client,pe=None):
 p=load_profile();predicate.verify_files(client)
 if pe is None:pe=pefile.PE(str(client/'GameAssembly.dll'),fast_load=True)
 read=lambda a,n:predicate.read_rva(pe,a,n)
 base=pe.OPTIONAL_HEADER.ImageBase;header=read(0x48b19b0,0x208)
 word=lambda o:struct.unpack_from('<I',header,o)[0]
 startup=(client/'StarRail_Data/il2cpp_data/Metadata/startup-metadata.dat').read_bytes()
 table=word(0x164)^0x7f5c5934
 expected=predicate.load_profile()
 if table!=int(expected['genericClassTableOffsetInStartup'],16) or len(startup)!=expected['startupMetadataLength']:
  raise ValueError('名称覆盖表的startup泛型范围不符')
 r=p['overrideMappingGeneric'];address=0x4ffdd60+r['field']['nativeTypeIndex']*8;raw=read(address,8)
 if hex(address)!=r['nativeTypeRecordRva'] or raw.hex()!=r['nativeTypeRecordHex'] or raw[6]!=21:
  raise ValueError('名称覆盖字段的泛型类型记录不符')
 pos=table+struct.unpack_from('<I',raw)[0]*8;record=startup[pos:pos+8]
 if len(record)!=8:raise ValueError('名称覆盖泛型记录截断')
 definition,inst=struct.unpack('<ii',record)
 if (hex(pos),record.hex(),definition,inst)!=(r['startupRowOffset'],r['startupRowHex'],1535,r['instIndex']) or r['genericDefinition']!=1535:
  raise ValueError('名称覆盖字典定义或参数索引不符')
 registration=p['genericInstantiationRegistration']
 array=struct.unpack('<Q',read(int(registration['pointerWordRva'],16),8))[0]-base
 if array!=int(registration['arrayRva'],16):raise ValueError('覆盖字典参数注册表不符')
 address=array+inst*16;raw=read(address,16);count,unused,argv=struct.unpack('<IIQ',raw)
 if (hex(address),raw.hex(),count,unused)!=(r['instRva'],r['instHex'],2,0):raise ValueError('覆盖字典双参数记录不符')
 for (pointer,),fact in zip(struct.iter_unpack('<Q',read(argv-base,16)),r['args'],strict=True):
  address=pointer-base;raw=read(address,8)
  if (hex(address),raw.hex(),raw[6],struct.unpack_from('<I',raw)[0])!=(fact['nativeRva'],fact['bytesHex'],fact['kind'],fact['definition']):
   raise ValueError('覆盖字典键或值的实际类型不符')
 if [f['kind'] for f in r['args']]!=[14,18] or r['args'][1]['definition']!=57755:raise ValueError('不是已核对的字符串到覆盖配置字典')
 return 2

def verify_native(client):
 p=load_profile();predicate.verify_files(client)
 counts=entry.onadd.characters.verify_native(client,{**p,'nativeBytes':p['nativeMethodBytes']})
 analyzer=sibling('modifier_selection_metadata','analyze-client-46.py')
 expected=[{k:m[k] for k in ['index','name','rva','typeIndex','typeName']} for m in p['nativeMethods']]
 actual=analyzer.extract(SimpleNamespace(client_dir=client,types=[t['name'] for t in p['matchedTypes']],type_contains=[],
  fields=True,field_offsets=True,inheritance=True,method_indices=[m['index'] for m in expected],
  method_rvas=[],method_name_contains=[],method_slots=[],string_slots=[],type_slots=[],disassemble=0,method_names=[]))
 if actual['selectedMethods']!=expected:raise ValueError('资格、覆盖、别名或额外限制方法身份不符')
 types={t['typeIndex']:t for t in actual['methods']}
 for fact in p['matchedTypes']:
  t=types.get(fact['typeIndex'])
  if t is None or t['name']!=fact['name']:raise ValueError('名称与资格字段的所属类型不符')
  if 'baseTypeChain' in fact and t['baseTypeChain']!=fact['baseTypeChain']:raise ValueError('名称与资格字段的继承链不符')
  inherited='matchedFields' in fact
  for f in fact['matchedFields'] if inherited else fact['declaredFields']:
   if f not in t['instanceFields' if inherited else 'fields']:raise ValueError('名称与资格字段的实际偏移或声明类型不符')
 entry.components.read_generic_slots(client,p)
 verify_override_mapping(client)
 return counts

def analyze(a):
 client=a.client_dir.resolve();design=sibling('modifier_selection_output','read-client-design-data.py')
 output=design.outside_client(a.output,client)
 if ROOT/'build' not in output.parents:raise ValueError('只接受工程build下的修改器资格证据')
 p=load_profile();methods,size=verify_native(client)
 source=entry.source_commands(client)
 if source!=p['sourceModifierCommands']:raise ValueError('六条来源效果任务不符')
 result={'passed':True,'matchedCompleteNativeMethods':methods,'matchedCompleteNativeBytes':size,
  'matchedFieldTypes':len(p['matchedTypes']),'matchedOverrideGenericArguments':2,'matchedBattleEventComponentSlots':len(p['genericMethodSlots']),
  'sourceModifierCommands':len(source),'requiredBehaviorFlag':84,'restrictedSourceTeam':2,'restrictedTargetType':12,
  **{k:v for k,v in p.items() if isinstance(v,bool)},'jsonInstalled':False,'clientExecutableStarted':False,'clientModified':False}
 output.parent.mkdir(parents=True,exist_ok=True);output.write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n','utf-8');return result

if __name__=='__main__':
 parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--client-dir',type=Path,required=True)
 parser.add_argument('--output',type=Path,default=ROOT/'build/rogue-modifier-selection-evidence.json')
 print(json.dumps(analyze(parser.parse_args()),ensure_ascii=False))
