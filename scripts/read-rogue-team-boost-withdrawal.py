"""只读核对战技点附加登记与增益撤销；登记工厂和世界仍待完成。"""
import argparse
from functools import lru_cache
import hashlib
import importlib.util
import json
import struct
from pathlib import Path
from types import SimpleNamespace

ROOT=Path(__file__).resolve().parents[1]
PROFILE=ROOT/'src/test/resources/fixtures/rogue-team-boost-withdrawal-4.6.json'
PROFILE_SHA256='7ef40021b124bf424386d6e385e9d163336ade9fb115f61bcd05f6f57dacd490'

def sibling(name,filename):
 spec=importlib.util.spec_from_file_location(name,Path(__file__).with_name(filename))
 module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module);return module

characters=sibling('team_boost_withdrawal_characters','read-rogue-resonance-character.py')

@lru_cache(maxsize=1)
def load_profile():
 raw=PROFILE.read_bytes().replace(b'\r\n',b'\n')
 if hashlib.sha256(raw).hexdigest()!=PROFILE_SHA256:raise ValueError('战技点撤销冻结证据改变')
 return json.loads(raw)

def metadata(client,types,methods,inheritance=False):
 analyzer=sibling('team_boost_withdrawal_metadata','analyze-client-46.py')
 return analyzer.extract(SimpleNamespace(client_dir=client,types=types,fields=True,field_offsets=True,
  inheritance=inheritance,type_contains=[],method_indices=methods,method_rvas=[],method_name_contains=[],
  method_slots=[],string_slots=[],type_slots=[],disassemble=0,method_names=[]))

def verify_native(client):
 p=load_profile();methods,size=characters.verify_native(client,p)
 expected=[{k:m[k] for k in ['index','name','rva','typeIndex','typeName']} for m in p['nativeMethods']]+p['boundMethodIdentities']
 actual=metadata(client,[t['name'] for t in p['matchedTypes']],[m['index'] for m in expected])
 if actual['selectedMethods']!=expected:raise ValueError('战技点撤销、上下文求值和明确绑定方法身份不符')
 declared={t['typeIndex']:t for t in actual['methods']}
 inheritedNames=[t['name'] for t in p['matchedTypes'] if 'selectedInstanceFields' in t]
 inherited={t['typeIndex']:t for t in metadata(client,inheritedNames,[],True)['methods']}
 for fact in p['matchedTypes']:
  t=declared[fact['typeIndex']]
  if t['name']!=fact['name']:raise ValueError('战技点撤销字段所属类型不符')
  if 'fields' in fact and t['fields']!=fact['fields']:raise ValueError('战技点撤销枚举完整声明不符')
  for key,rows in [('selectedFields',t['fields']),('selectedInstanceFields',inherited.get(fact['typeIndex'],{}).get('instanceFields',[]))]:
   if key not in fact:continue
   names={f['name'] for f in fact[key]}
   if [f for f in rows if f['name'] in names]!=fact[key]:raise ValueError('战技点组件与条目字段不符')
 components=sibling('team_boost_withdrawal_components','read-rogue-resonance-components.py')
 import pefile
 pe=pefile.PE(str(client/'GameAssembly.dll'),fast_load=True)
 startup=(client/'StarRail_Data/il2cpp_data/Metadata/startup-metadata.dat').read_bytes()
 if hashlib.sha256(startup).hexdigest()!=p['nativeFiles']['startup-metadata.dat']:raise ValueError('战技点撤销泛型启动元数据不符')
 header=components.read_rva(pe,0x48b19b0,0x208)
 table=struct.unpack_from('<I',header,0x164)[0]^0x7f5c5934
 array=struct.unpack('<Q',components.read_rva(pe,0x4931690,8))[0]-pe.OPTIONAL_HEADER.ImageBase
 if table!=int(p['genericClassTableOffsetInStartup'],16) or array!=int(p['genericInstantiationArrayRva'],16):raise ValueError('战技点撤销泛型注册不符')
 def check_type(t,depth=0):
  if depth>6:raise ValueError('战技点撤销泛型深度越界')
  raw=components.read_rva(pe,int(t['nativeRva'],16),8)
  if raw.hex()!=t['bytesHex'] or raw[6]!=t['kind'] or struct.unpack_from('<I',raw)[0]!=t['payload']:raise ValueError('战技点撤销类型记录不符')
  if t['kind']!=21:return
  pos=table+t['payload']*8
  if pos!=int(t['startupRowOffset'],16) or startup[pos:pos+8].hex()!=t['startupRowHex']:raise ValueError('战技点撤销泛型类记录不符')
  if struct.unpack_from('<ii',startup,pos)!=(t['genericDefinition'],t['instIndex']):raise ValueError('战技点撤销泛型定义或实例索引不符')
  irva=array+t['instIndex']*16;raw=components.read_rva(pe,irva,16)
  if irva!=int(t['instRva'],16) or raw.hex()!=t['instHex']:raise ValueError('战技点撤销泛型实例原件不符')
  count,reserved,argv=struct.unpack('<IIQ',raw)
  if count!=len(t['args']) or reserved!=0 or not 1<=count<=8:raise ValueError('战技点撤销泛型参数数量不符')
  pointers=struct.unpack('<'+'Q'*count,components.read_rva(pe,argv-pe.OPTIONAL_HEADER.ImageBase,count*8))
  for pointer,arg in zip(pointers,t['args']):
   if pointer-pe.OPTIONAL_HEADER.ImageBase!=int(arg['nativeRva'],16):raise ValueError('战技点撤销泛型参数地址不符')
   check_type(arg,depth+1)
 for f in p['genericFields']:
  check_type(f['record'])
 import capstone
 decoder=capstone.Cs(capstone.CS_ARCH_X86,capstone.CS_MODE_64);decoder.detail=True
 literal=p['counterLiteral'];method=p['runtimeMethods'][0]
 instruction=next(i for i in decoder.disasm(bytes.fromhex(method['bytesHex']),pe.OPTIONAL_HEADER.ImageBase+int(method['rva'],16)) if i.mnemonic=='paddd')
 rva=instruction.address+instruction.size+instruction.operands[1].mem.disp-pe.OPTIONAL_HEADER.ImageBase
 raw=components.read_rva(pe,rva,16)
 if instruction.address-pe.OPTIONAL_HEADER.ImageBase!=int(literal['instructionRva'],16) or rva!=int(literal['rva'],16) or raw.hex()!=literal['bytesHex'] or list(struct.unpack('<4i',raw))!=literal['values'] or literal['values'][:2]!=[-1,1]:raise ValueError('战技点链表计数及版本变化原件不符')
 helpers,helperSize=characters.verify_native(client,{'nativeFiles':p['nativeFiles'],'nativeMethods':p['runtimeMethods'],'nativeBytes':p['runtimeBytes']})
 return methods,size,helpers,helperSize

def analyze(a):
 client=a.client_dir.resolve();design=sibling('team_boost_withdrawal_output','read-client-design-data.py')
 output=design.outside_client(a.output,client)
 if ROOT/'build' not in output.parents:raise ValueError('只接受工程build下的战技点撤销证据')
 p=load_profile();methods,size,helpers,helperSize=verify_native(client)
 result={'passed':True,'matchedCompleteNativeMethods':methods,'matchedCompleteNativeBytes':size,
  'matchedFieldTypes':len(p['matchedTypes']),'matchedBoundMethodIdentities':len(p['boundMethodIdentities']),'matchedGenericFieldArguments':len(p['genericFields']),'matchedCompleteRuntimeHelpers':helpers,'matchedCompleteRuntimeHelperBytes':helperSize,'matchedCounterLiteralValues':len(p['counterLiteral']['values']),**{k:v for k,v in p.items() if isinstance(v,bool)},
  'jsonInstalled':False,'clientExecutableStarted':False,'clientModified':False}
 output.parent.mkdir(parents=True,exist_ok=True);output.write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n','utf-8');return result

if __name__=='__main__':
 parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--client-dir',type=Path,required=True)
 parser.add_argument('--output',type=Path,default=ROOT/'build/rogue-team-boost-withdrawal-evidence.json')
 print(json.dumps(analyze(parser.parse_args()),ensure_ascii=False))
