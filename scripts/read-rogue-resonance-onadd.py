"""只读核对九条共鸣 OnAdd 任务、使能默认及效果工厂；在线效果仍待接入。"""
import argparse
from functools import lru_cache
import hashlib
import importlib.util
import json
from pathlib import Path
from types import SimpleNamespace
import pefile

ROOT=Path(__file__).resolve().parents[1]
PROFILE=ROOT/'src/test/resources/fixtures/rogue-resonance-onadd-4.6.json'
PROFILE_SHA256='9b5a0a693fe39ffc997e8cf7f0ceacfc2226dde3c9690cebe6ea1a1e9401c383'


def sibling(name,filename):
 spec=importlib.util.spec_from_file_location(name,Path(__file__).with_name(filename))
 module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module);return module


classic=sibling('onadd_classic','read-rogue-resonance-classic-events.py')
characters=classic.parent.camera.abilities.characters


@lru_cache(maxsize=1)
def load_profile():
 raw=PROFILE.read_bytes().replace(b'\r\n',b'\n')
 if hashlib.sha256(raw).hexdigest()!=PROFILE_SHA256:raise ValueError('共鸣OnAdd冻结证据改变')
 p=json.loads(raw)
 if p['classicProfileSHA256']!=classic.PROFILE_SHA256:raise ValueError('九条共鸣目录版本不符')
 return p


def verify_native(client):
 p=load_profile();counts=characters.verify_native(client,p)
 analyzer=sibling('onadd_metadata','analyze-client-46.py');leaf=p['taskEnabledLeaf']
 actual=analyzer.extract(SimpleNamespace(client_dir=client,types=[],type_contains=[],
  method_indices=[m['index'] for m in p['nativeMethods']]+[leaf['index']],method_rvas=[],method_name_contains=[],
  method_slots=[int(p['factoryMethodSlot']['slot'],16)],string_slots=[],
  type_slots=[int(t['slot'],16) for t in p['modifierTypeSlots']+p['linkInterfaceSlots']],
  fields=True,field_offsets=True,inheritance=False,disassemble=0,method_names=[]))
 expected=[{k:m[k] for k in ['index','name','rva','typeIndex','typeName']} for m in p['nativeMethods']]+[
  {k:leaf[k] for k in ['index','name','rva','typeIndex','typeName']}]
 if actual['selectedMethods']!=expected or actual['methodInfoSlots']!=[p['factoryMethodSlot']]:
  raise ValueError('效果运行时工厂或完整方法身份不符')
 if sorted(actual['typeInfoSlots'],key=lambda t:t['slot'])!=sorted(p['modifierTypeSlots']+p['linkInterfaceSlots'],key=lambda t:t['slot']):
  raise ValueError('配置/运行时分配/连接接口类型槽不符')
 types={t['typeIndex']:t for t in actual['methods']}
 if any({k:types[t['typeIndex']][k] for k in t}!=t for t in p['modifierTypes']):
  raise ValueError('效果配置或运行时声明字段不符')
 pe=pefile.PE(str(client/'GameAssembly.dll'),fast_load=True)
 rva=int(leaf['rva'],16);raw=pe.get_data(rva,leaf['bytes'])
 if raw.hex()!=leaf['bytesHex'] or hashlib.sha256(raw).hexdigest()!=leaf['sha256']:
  raise ValueError('TaskEnabled叶构造器字节不符')
 # This leaf is not included in the complete PE-method count.
 directory=pe.OPTIONAL_HEADER.DATA_DIRECTORY[pefile.DIRECTORY_ENTRY['IMAGE_DIRECTORY_ENTRY_EXCEPTION']]
 import struct
 if any(start==rva for start,end,unwind in struct.iter_unpack('<III',pe.get_data(directory.VirtualAddress,directory.Size))):
  raise ValueError('叶构造器范围证据与冻结分类不符')
 return counts


def source_commands(client):
 p=load_profile();definitions=classic.source_definitions(client)
 report=json.loads((ROOT/'build/event-params-source-parent-evidence.json').read_text('utf-8'))
 # source_definitions freshly verifies the full seven-event parent and each complete selected
 # additional ability, including every OnAdd field, before these decoded values are used.
 abilities=report['decoded']['AbilityList']+[a['decoded'] for a in classic.load_profile()['additionalAbilities']]
 result=[]
 for d in definitions:
  a=next(a for a in abilities if a['Name']==d['abilityName'])
  result.append({'eventId':d['eventId'],'abilityName':a['Name'],'commands':a['OnAdd']})
 if result!=p['sourceCommands']:raise ValueError('原始OnAdd顺序、使能或效果字段不符')
 return result


def analyze(args):
 client=args.client_dir.resolve();design=sibling('onadd_output','read-client-design-data.py')
 output=design.outside_client(args.output,client)
 if ROOT/'build' not in output.parents:raise ValueError('只接受工程build下的OnAdd只读证据')
 p=load_profile();methods,size=verify_native(client);sources=source_commands(client)
 flags=[k for k,v in p.items() if isinstance(v,bool)]
 result={'passed':True,'matchedNativeMethods':methods,'matchedNativeBytes':size,
  'matchedTaskEnabledLeafBytes':5,'sourceEvents':len(sources),
  'sourceCommands':sum(len(s['commands']) for s in sources),
  'sourceModifierCommands':sum(t['taskId']==50 for s in sources for t in s['commands']),
  'sourceEnabledFieldsAbsent':all('TaskEnabled' not in t for s in sources for t in s['commands']),
  **{k:p[k] for k in flags},'jsonInstalled':False,'clientExecutableStarted':False,'clientModified':False}
 output.write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n','utf-8');return result


if __name__=='__main__':
 parser=argparse.ArgumentParser(description=__doc__)
 parser.add_argument('--client-dir',type=Path,required=True)
 parser.add_argument('--output',type=Path,default=ROOT/'build/rogue-resonance-onadd-evidence.json')
 print(json.dumps(analyze(parser.parse_args()),ensure_ascii=False))
