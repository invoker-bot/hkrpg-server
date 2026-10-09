"""只读核对效果整数参数的有限标准计算；不安装参数、不执行在线修改器。"""
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
PROFILE=ROOT/'src/test/resources/fixtures/rogue-modifier-numbers-4.6.json'
PROFILE_SHA256='a387385b0aef43e828263d0d13918913734524b2d9db80799eca7646a06230f7'

def sibling(name,filename):
 spec=importlib.util.spec_from_file_location(name,Path(__file__).with_name(filename))
 module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module);return module

caster=sibling('modifier_numbers_caster','read-rogue-modifier-caster.py')
rounds=sibling('modifier_numbers_round','read-rogue-resonance-round.py')

@lru_cache(maxsize=1)
def load_profile():
 raw=PROFILE.read_bytes().replace(b'\r\n',b'\n')
 if hashlib.sha256(raw).hexdigest()!=PROFILE_SHA256:raise ValueError('效果整数参数冻结证据改变')
 p=json.loads(raw)
 if p['modifierCasterProfileSHA256']!=caster.PROFILE_SHA256 or p['roundProfileSHA256']!=rounds.PROFILE_SHA256:
  raise ValueError('效果整数参数的入口或舍入版本不符')
 return p

def verify_native(client):
 p=load_profile();caster.selection.predicate.verify_files(client)
 chars=caster.selection.entry.onadd.characters
 methods,size=chars.verify_native(client,p)
 provider=p['layoutProvider']
 providers,provider_size=chars.verify_native(client,{'nativeFiles':p['nativeFiles'],
  'nativeMethods':[provider],'nativeBytes':len(bytes.fromhex(provider['bytesHex']))})
 rounds.verify_native(client)
 analyzer=sibling('modifier_numbers_metadata','analyze-client-46.py')
 expected=[{k:m[k] for k in ['index','name','rva','typeIndex','typeName']} for m in p['nativeMethods']]
 actual=analyzer.extract(SimpleNamespace(client_dir=client,types=[t['name'] for t in p['matchedTypes']],
  fields=True,field_offsets=True,inheritance=False,type_contains=[],method_indices=[m['index'] for m in expected],
  method_rvas=[],method_name_contains=[],method_slots=[],string_slots=[],type_slots=[],disassemble=0,method_names=[]))
 if actual['selectedMethods']!=expected:raise ValueError('参数覆盖、普通舍入、入口或构造方法身份不符')
 types={t['typeIndex']:t for t in actual['methods']}
 for fact in p['matchedTypes']:
  t=types[fact['typeIndex']]
  if (t['typeIndex'],t['name'])!=(fact['typeIndex'],fact['name']):raise ValueError('参数类型身份不符')
  if 'fields' in fact and t['fields']!=fact['fields']:raise ValueError('参数覆盖或内嵌三元组字段不符')
  if 'selectedFields' in fact:
   names={f['name'] for f in fact['selectedFields']}
   if [f for f in t['fields'] if f['name'] in names]!=fact['selectedFields']:raise ValueError('标准数值的One/Zero字段不符')
 verify_zero_layout(client,p,types[9939])
 return methods,size,providers,provider_size

def verify_zero_layout(client,p,fixpoint):
 zero=p['zeroInitializer'];one=p['oneInitializer']
 if one!=rounds.load_profile()['oneInitializer'] or zero['raw']!=0 or zero['staticOffsetDelta']!=0x70:
  raise ValueError('整数覆盖的One/Zero初始化证据不符')
 pe=pefile.PE(str(client/'GameAssembly.dll'),fast_load=True)
 metadata=(client/'StarRail_Data/il2cpp_data/Metadata/global-metadata.dat').read_bytes()
 header=pe.get_data(0x48b19b0,0x208);word=lambda o:struct.unpack_from('<I',header,o)[0]
 indexes=((word(0x180)+0xe4dbe763)&0xffffffff)+0x208
 records=((word(0x9c)+0xbc7ec9d7)&0xffffffff)+0x208
 offsets=(word(0x148)^0x329e1172)+0x208
 record=struct.unpack_from('<I',metadata,indexes+9939*4)[0]
 start=struct.unpack_from('<I',metadata,records+record*12+8)[0]
 for fact in zero['fields']:
  pos=offsets+(start+fact['ordinal'])*4
  if (hex(pos),struct.unpack_from('<I',metadata,pos)[0],fixpoint['fields'][fact['ordinal']]['name'])!=(
   fact['metadataOffset'],fact['word'],fact['name']):raise ValueError('One/Zero字段身份或声明布局行不符')
  if fact['word']&0xff000000!=zero['sharedLayoutFlag']:raise ValueError('One/Zero不属于相同静态布局格式')
 if zero['fields'][1]['word']-zero['fields'][0]['word']!=zero['staticOffsetDelta']:
  raise ValueError('One/Zero静态位置差不符')
 rva=int(zero['slotRva'],16)
 if rva-int(one['slotRva'],16)!=zero['staticOffsetDelta']:raise ValueError('Zero读取位置不符')
 section=pe.get_section_by_rva(rva)
 if section is None or (section.Name.hex(),hex(section.VirtualAddress),section.Misc_VirtualSize,section.SizeOfRawData)!=(
  zero['sectionName'],zero['sectionRva'],zero['virtualSize'],zero['rawSize']):raise ValueError('Zero的PE零初始化范围不符')
 if not section.SizeOfRawData<=rva-section.VirtualAddress<=section.Misc_VirtualSize-8:
  raise ValueError('Zero不处于已匹配的未映射文件尾零初始化范围')

def source_commands(client):
 p=load_profile();sources=caster.selection.entry.source_commands(client)
 if sources!=p['sourceModifierCommands']:raise ValueError('效果整数参数的六条来源任务不符')
 for source in sources:
  if any(name in source['command'] for name in p['originalNumericFields']):
   raise ValueError('来源任务不再省略四种整数参数')
 return sources

def analyze(a):
 client=a.client_dir.resolve();design=sibling('modifier_numbers_output','read-client-design-data.py')
 output=design.outside_client(a.output,client)
 if ROOT/'build' not in output.parents:raise ValueError('只接受工程build下的整数参数证据')
 p=load_profile();methods,size,providers,provider_size=verify_native(client);sources=source_commands(client)
 result={'passed':True,'matchedCompleteNativeMethods':methods,'matchedCompleteNativeBytes':size,
  'matchedCompleteLayoutProviders':providers,'matchedCompleteLayoutProviderBytes':provider_size,
  'matchedFieldTypes':len(p['matchedTypes']),'sourceModifierCommands':len(sources),
  'originalOmittedNumericFields':len(sources)*len(p['originalNumericFields']),
  **{k:v for k,v in p.items() if isinstance(v,bool)},'jsonInstalled':False,'clientExecutableStarted':False,'clientModified':False}
 output.parent.mkdir(parents=True,exist_ok=True);output.write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n','utf-8');return result

if __name__=='__main__':
 parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--client-dir',type=Path,required=True)
 parser.add_argument('--output',type=Path,default=ROOT/'build/rogue-modifier-numbers-evidence.json')
 print(json.dumps(analyze(parser.parse_args()),ensure_ascii=False))
