"""只读核对带种子基础Random、普通定点投影与计数顺序；不启动客户端或安装世界状态。"""
import argparse
from functools import lru_cache
import hashlib
import importlib.util
import json
from pathlib import Path
import struct
from types import SimpleNamespace

import capstone
import pefile

ROOT=Path(__file__).resolve().parents[1]
PROFILE=ROOT/'src/test/resources/fixtures/rogue-logic-random-4.6.json'
PROFILE_SHA256='4ef5766b048b4599b3f02aeada6de7a7aa41ed11a410d6d6f43b4e5c9d3c0569'

def sibling(name,filename):
 spec=importlib.util.spec_from_file_location(name,Path(__file__).with_name(filename))
 module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module);return module

characters=sibling('logic_random_characters','read-rogue-resonance-character.py')

@lru_cache(maxsize=1)
def load_profile():
 raw=PROFILE.read_bytes().replace(b'\r\n',b'\n')
 if hashlib.sha256(raw).hexdigest()!=PROFILE_SHA256:raise ValueError('逻辑随机冻结证据改变')
 return json.loads(raw)

class SeededProjection:
 """独立有符号32位投影，用于核对冻结的宿主对照，不代表执行游戏代码。"""
 @staticmethod
 def signed(v):return (v+0x80000000)%0x100000000-0x80000000
 def __init__(self,seed):
  if not -0x80000000<=seed<=0x7fffffff:raise ValueError('种子超出Int32')
  self.values=[0]*56;self.next=0;self.partner=21
  magnitude=0x7fffffff if seed==-0x80000000 else abs(seed)
  mj=self.signed(161803398-magnitude);mk=1;self.values[55]=mj
  for index in range(1,55):
   slot=(21*index)%55;self.values[slot]=mk;mk=self.signed(mj-mk)
   if mk<0:mk=self.signed(mk+0x7fffffff)
   mj=self.values[slot]
  for _ in range(4):
   for index in range(1,56):
    v=self.signed(self.values[index]-self.values[1+(index+30)%55])
    self.values[index]=self.signed(v+0x7fffffff) if v<0 else v
 def sample(self):
  a=self.next+1;b=self.partner+1
  if a>=56:a=1
  if b>=56:b=1
  value=self.signed(self.values[a]-self.values[b])
  if value==0x7fffffff:value-=1
  if value<0:value=self.signed(value+0x7fffffff)
  self.values[a]=value;self.next=a;self.partner=b
  inverse=struct.unpack('<d',bytes.fromhex('000020000000003e'))[0]
  return value*inverse

def verify_vectors():
 host=load_profile()['hostComparison']
 if not host['hostComparisonOnly'] or host['executedClient'] is not False:raise ValueError('宿主对照不能声明客户端执行')
 records=host['records'];seeds=[r['seed'] for r in records]
 if seeds!=[0,1,-1,42,-42,123456789,161803398,0x7fffffff,-0x80000000]:raise ValueError('冻结种子边界改变')
 total=0
 for record in records:
  if len(record['values'])!=128:raise ValueError('冻结序列长度改变')
  random=SeededProjection(record['seed'])
  for row in record['values']:
   sample=random.sample();bits=f'{struct.unpack("<Q",struct.pack("<d",sample))[0]:016x}'
   if (bits,int(sample*8589934592.0)&~1)!=(row['doubleBits'],row['standardRaw']):raise ValueError('带种子算法与宿主冻结序列不符')
   total+=1
 return len(records),total

def verify_native(client):
 p=load_profile();methods,size=characters.verify_native(client,p)
 analyzer=sibling('logic_random_metadata','analyze-client-46.py')
 expected=[{k:m[k] for k in ['index','name','rva','typeIndex','typeName']} for m in p['nativeMethods']]
 actual=analyzer.extract(SimpleNamespace(client_dir=client,types=[t['name'] for t in p['matchedTypes']],
  fields=True,field_offsets=True,inheritance=False,type_contains=[],method_indices=[m['index'] for m in expected],
  method_rvas=[],method_name_contains=[],method_slots=[],string_slots=[],type_slots=[],disassemble=0,method_names=[]))
 if actual['selectedMethods']!=expected:raise ValueError('随机构造、基础抽样或计数通知方法身份不符')
 types={t['typeIndex']:t for t in actual['methods']}
 for t in p['matchedTypes']:
  if {k:types[t['typeIndex']][k] for k in t}!=t:raise ValueError('基础Random或包装状态字段不符')
 pe=pefile.PE(str(client/'GameAssembly.dll'),fast_load=True);base=pe.OPTIONAL_HEADER.ImageBase
 decoder=capstone.Cs(capstone.CS_ARCH_X86,capstone.CS_MODE_64);decoder.detail=True
 for fact in p['constants']:
  m=next(m for m in p['nativeMethods'] if m['index']==fact['methodIndex'])
  i=next(i for i in decoder.disasm(bytes.fromhex(m['bytesHex']),base+int(m['rva'],16)) if i.address-base==int(fact['instructionRva'],16))
  o=next(o for o in i.operands if o.type==capstone.CS_OP_MEM and o.mem.base==capstone.x86_const.X86_REG_RIP)
  rva=i.address+i.size+o.mem.disp-base;raw=pe.get_data(rva,len(bytes.fromhex(fact['bytesHex'])))
  if (hex(rva),raw.hex(),hashlib.sha256(raw).hexdigest())!=(fact['rva'],fact['bytesHex'],fact['sha256']):raise ValueError('随机SIMD边界、倒数或定点尺度来源不符')
  if 'double' in fact and struct.unpack('<d',raw)[0]!=fact['double']:raise ValueError('随机浮点常量不符')
  if 'int32' in fact and list(struct.unpack('<iiii',raw))!=fact['int32']:raise ValueError('随机索引向量不符')
 return methods,size

def analyze(a):
 client=a.client_dir.resolve();design=sibling('logic_random_output','read-client-design-data.py')
 output=design.outside_client(a.output,client)
 if ROOT/'build' not in output.parents:raise ValueError('只接受工程build下的逻辑随机证据')
 p=load_profile();methods,size=verify_native(client);seeds,samples=verify_vectors()
 result={'passed':True,'matchedCompleteNativeMethods':methods,'matchedCompleteNativeBytes':size,
  'matchedFieldTypes':len(p['matchedTypes']),'matchedConstants':len(p['constants']),
  'matchedConstantBytes':sum(len(bytes.fromhex(f['bytesHex'])) for f in p['constants']),
  'comparedSeeds':seeds,'comparedSamples':samples,'hostComparisonIsClientExecution':False,
  **{k:v for k,v in p.items() if isinstance(v,bool)},'jsonInstalled':False,'clientExecutableStarted':False,'clientModified':False}
 output.parent.mkdir(parents=True,exist_ok=True);output.write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n','utf-8');return result

if __name__=='__main__':
 parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--client-dir',type=Path,required=True)
 parser.add_argument('--output',type=Path,default=ROOT/'build/rogue-logic-random-evidence.json')
 print(json.dumps(analyze(parser.parse_args()),ensure_ascii=False))
