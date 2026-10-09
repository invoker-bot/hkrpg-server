"""核对有限效果定义、构造缺省和事件回调选择；不创建在线效果实例。"""
import argparse
from functools import lru_cache
import hashlib
import importlib.util
import json
from pathlib import Path
import tempfile
from types import SimpleNamespace

ROOT=Path(__file__).resolve().parents[1]
PROFILE=ROOT/'src/test/resources/fixtures/rogue-modifier-definitions-4.6.json'
PROFILE_SHA256='4c72a3b8ef1b5dc4f95aa00618eb7273cdb5d94340df0fcd6e391229b0f51bbd'


def sibling(name,filename):
 spec=importlib.util.spec_from_file_location(name,Path(__file__).with_name(filename))
 module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module);return module


onadd=sibling('modifier_defs_onadd','read-rogue-resonance-onadd.py')
abilities=sibling('modifier_defs_abilities','read-rogue-resonance-abilities.py')


@lru_cache(maxsize=1)
def load_profile():
 raw=PROFILE.read_bytes().replace(b'\r\n',b'\n')
 if hashlib.sha256(raw).hexdigest()!=PROFILE_SHA256:raise ValueError('有限效果定义证据改变')
 p=json.loads(raw)
 if p['onAddProfileSHA256']!=onadd.PROFILE_SHA256:raise ValueError('原始OnAdd版本不符')
 return p


def verify_native(client):
 p=load_profile();counts=onadd.characters.verify_native(client,p)
 analyzer=sibling('modifier_defs_metadata','analyze-client-46.py')
 actual=analyzer.extract(SimpleNamespace(client_dir=client,types=[t['name'] for t in p['definitionTypes']],
  type_contains=[],method_indices=[m['index'] for m in p['nativeMethods']],method_rvas=[],method_name_contains=[],
  method_slots=[],string_slots=[],type_slots=[int(t['slot'],16) for t in p['typeInfoSlots']],
  fields=True,field_offsets=True,inheritance=True,disassemble=0,method_names=[]))
 expected=[{k:m[k] for k in ['index','name','rva','typeIndex','typeName']} for m in p['nativeMethods']]
 if actual['selectedMethods']!=expected or actual['typeInfoSlots']!=p['typeInfoSlots']:
  raise ValueError('效果构造/默认值/局部能力类型/回调方法身份不符')
 types={t['typeIndex']:t for t in actual['methods']}
 for fact in p['definitionTypes']:
  if {k:types[fact['typeIndex']][k] for k in fact}!=fact:raise ValueError('效果定义或能力的完整字段/枚举不符')
 return counts


def source_definitions(client):
 p=load_profile();commands=onadd.source_commands(client)
 parent=json.loads((ROOT/'build/event-params-source-parent-evidence.json').read_text('utf-8'))
 all_abilities=parent['decoded']['AbilityList']+[a['decoded'] for a in onadd.classic.load_profile()['additionalAbilities']]
 local=[]
 for c in commands:
  a=next(a for a in all_abilities if a['Name']==c['abilityName'])
  local.append({'eventId':c['eventId'],'abilityName':a['Name'],'Modifiers':a.get('Modifiers')})
 if local!=p['sourceLocalDefinitions']:raise ValueError('九条局部定义或省略值改变')
 with tempfile.TemporaryDirectory(prefix='modifier-definitions-',dir=ROOT/'build') as temporary:
  report=abilities.analyze(SimpleNamespace(client_dir=client,output=Path(temporary)/'abilities.json'))
  actual=next(r for r in report['records'] if r['sourcePath']==p['breedingSourceRecord']['sourcePath'])
  if {k:actual[k] for k in p['breedingSourceRecord']}!=p['breedingSourceRecord']:
   raise ValueError('完整繁育能力记录身份改变')
  global_defs=actual['decoded']['GlobalModifiers']
 if global_defs!=p['sourceGlobalDefinitions']:raise ValueError('繁育全局效果定义或完整回调程序改变')
 return local,global_defs


def task_count(tasks):
 if tasks is None:return 0
 return sum(1+task_count(t.get('SuccessTaskList'))+task_count(t.get('FailedTaskList')) for t in tasks)


def analyze(args):
 client=args.client_dir.resolve();design=sibling('modifier_defs_output','read-client-design-data.py')
 output=design.outside_client(args.output,client)
 if ROOT/'build' not in output.parents:raise ValueError('只接受工程build下的效果定义证据')
 p=load_profile();methods,size=verify_native(client);local,global_defs=source_definitions(client)
 callbacks=[c for _,d in global_defs['entries'] for c in d['_CallbackList']]
 flags={k:v for k,v in p.items() if isinstance(v,bool)}
 result={'passed':True,'matchedNativeMethods':methods,'matchedNativeBytes':size,'sourceClassicEvents':len(local),
  'sourceLocalDefinitions':sum(len(s['Modifiers']['entries']) for s in local if s['Modifiers'] is not None),
  'sourceGlobalDefinitions':len(global_defs['entries']),'sourceGlobalCallbacks':len(callbacks),
  'sourceCallbackTasksIncludingBranches':sum(task_count(c['CallbackConfig']) for c in callbacks),
  'sourceGlobalAbilityBytes':p['breedingSourceRecord']['length'],**flags,
  'jsonInstalled':False,'clientExecutableStarted':False,'clientModified':False}
 output.parent.mkdir(parents=True,exist_ok=True);output.write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n','utf-8');return result


if __name__=='__main__':
 parser=argparse.ArgumentParser(description=__doc__)
 parser.add_argument('--client-dir',type=Path,required=True)
 parser.add_argument('--output',type=Path,default=ROOT/'build/rogue-modifier-definitions-evidence.json')
 print(json.dumps(analyze(parser.parse_args()),ensure_ascii=False))
