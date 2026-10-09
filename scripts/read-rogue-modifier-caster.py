"""只读核对普通修改器施加者模式、重复上下文读取及内置中转能力名称；不创建活对象。"""
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
PROFILE=ROOT/'src/test/resources/fixtures/rogue-modifier-caster-4.6.json'
PROFILE_SHA256='01d8cd6415fdb87676d5ca71494cb6666abecc5012dfad49c6d670e00ba851a0'

def sibling(name,filename):
 spec=importlib.util.spec_from_file_location(name,Path(__file__).with_name(filename))
 module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module);return module

selection=sibling('modifier_caster_selection','read-rogue-modifier-selection.py')

@lru_cache(maxsize=1)
def load_profile():
 raw=PROFILE.read_bytes().replace(b'\r\n',b'\n')
 if hashlib.sha256(raw).hexdigest()!=PROFILE_SHA256:raise ValueError('施加者与中转能力证据改变')
 p=json.loads(raw)
 if p['modifierSelectionProfileSHA256']!=selection.PROFILE_SHA256:raise ValueError('修改器资格证据版本不符')
 return p

def verify_native(client):
 p=load_profile();selection.predicate.verify_files(client)
 counts=selection.entry.onadd.characters.verify_native(client,p)
 analyzer=sibling('modifier_caster_metadata','analyze-client-46.py')
 expected=[{k:m[k] for k in ['index','name','rva','typeIndex','typeName']} for m in p['nativeMethods']]
 virtual=[{k:v[k] for k in ['index','name','rva','typeIndex','typeName']} for v in p['virtualMethodSlots']]
 actual=analyzer.extract(SimpleNamespace(client_dir=client,types=[t['name'] for t in p['matchedTypes']],type_contains=[],
  fields=True,field_offsets=True,inheritance=True,method_indices=[m['index'] for m in expected+virtual],method_rvas=[],
  method_name_contains=[],method_slots=[],string_slots=[int(s['slot'],16) for s in p['transitNameSlots']],
  type_slots=[int(s['slot'],16) for s in p['abilityClassSlots']+p['transitConfigClassSlots']],disassemble=0,method_names=[]))
 if actual['selectedMethods']!=expected+virtual:raise ValueError('施加者选择、默认查找/初始化或上下文方法身份不符')
 if actual['typeInfoSlots']!=p['abilityClassSlots']+p['transitConfigClassSlots'] or actual['stringLiteralSlots']!=p['transitNameSlots']:
  raise ValueError('实际能力类、内置配置类或中转名称槽不符')
 types={t['typeIndex']:t for t in actual['methods']}
 for fact in p['matchedTypes']:
  t=types.get(fact['typeIndex'])
  if t is None or t['name']!=fact['name']:raise ValueError('施加者相关字段的所属类型不符')
  if 'fields' in fact:
   if t['fields']!=fact['fields']:raise ValueError('施加者枚举常量或声明字段不符')
   continue
  if 'baseTypeChain' in fact and t['baseTypeChain']!=fact['baseTypeChain']:raise ValueError('能力实例字段的继承链不符')
  inherited='matchedFields' in fact
  for f in fact['matchedFields'] if inherited else fact['declaredFields']:
   if f not in t['instanceFields' if inherited else 'fields']:raise ValueError('能力/上下文字段偏移或类型不符')
 pe=pefile.PE(str(client/'GameAssembly.dll'),fast_load=True)
 read=lambda a,n:selection.predicate.read_rva(pe,a,n)
 directory=pe.OPTIONAL_HEADER.DATA_DIRECTORY[pefile.DIRECTORY_ENTRY['IMAGE_DIRECTORY_ENTRY_EXCEPTION']]
 bounds={s:(e,u) for s,e,u in struct.iter_unpack('<III',read(directory.VirtualAddress,directory.Size))}
 for m in p['runtimeProviders']:
  start=int(m['rva'],16);end=int(m['endRva'],16);raw=read(start,end-start)
  if bounds.get(start)!=(end,int(m['unwindRva'],16)) or raw.hex()!=m['bytesHex'] or hashlib.sha256(raw).hexdigest()!=m['sha256']:
   raise ValueError('重复上下文读取辅助的完整PE范围或字节不符')
 header=read(0x48b19b0,0x208);methods=((analyzer.word(header,0x14c)+0xf3a04294)&0xffffffff)+0x208
 data=(client/'StarRail_Data/il2cpp_data/Metadata/global-metadata.dat').read_bytes()
 for r in p['virtualMethodSlots']:
  encoded=struct.unpack_from('<H',data,methods+r['index']*26+12)[0]
  slot=(encoded^analyzer.method_key(r['index'])^0x9f7)&0xffff
  if (encoded,slot)!=(r['encodedWord'],r['decodedSlot']):raise ValueError('OwnerAbility/OwnerEntity的实际虚槽不符')
 selection.entry.components.read_generic_slots(client,p)
 return *counts,len(p['runtimeProviders']),sum(len(bytes.fromhex(m['bytesHex'])) for m in p['runtimeProviders'])

def analyze(a):
 client=a.client_dir.resolve();design=sibling('modifier_caster_output','read-client-design-data.py')
 output=design.outside_client(a.output,client)
 if ROOT/'build' not in output.parents:raise ValueError('只接受工程build下的施加者只读证据')
 p=load_profile();methods,size,providers,provider_size=verify_native(client)
 sources=selection.entry.source_commands(client)
 if sources!=p['sourceModifierCommands']:raise ValueError('六条来源效果任务不符')
 result={'passed':True,'matchedCompleteNativeMethods':methods,'matchedCompleteNativeBytes':size,
  'matchedCompleteRuntimeProviders':providers,'matchedCompleteRuntimeProviderBytes':provider_size,
  'matchedFieldTypes':len(p['matchedTypes']),'matchedVirtualGetterSlots':len(p['virtualMethodSlots']),
  'sourceModifierCommands':len(sources),'transitConfig':p['builtinTransitConfig'],
  **{k:v for k,v in p.items() if isinstance(v,bool)},'jsonInstalled':False,'clientExecutableStarted':False,'clientModified':False}
 output.parent.mkdir(parents=True,exist_ok=True);output.write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n','utf-8');return result

if __name__=='__main__':
 parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--client-dir',type=Path,required=True)
 parser.add_argument('--output',type=Path,default=ROOT/'build/rogue-modifier-caster-evidence.json')
 print(json.dumps(analyze(parser.parse_args()),ensure_ascii=False))
