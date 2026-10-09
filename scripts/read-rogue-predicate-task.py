"""只读核对两个实际条件的泛型父类、共享方法、反转次序和未实现Tick。"""
import argparse
from functools import lru_cache
import hashlib
import importlib.util
import json
import mmap
from pathlib import Path
import struct

import pefile

ROOT=Path(__file__).resolve().parents[1]
PROFILE=ROOT/'src/test/resources/fixtures/rogue-predicate-task-4.6.json'
PROFILE_SHA256='a0e31564c0fb7186b77221ffa0877452f72f513cf9a4113aa6ff9008afabd77e'

def sibling(name,filename):
 spec=importlib.util.spec_from_file_location(name,Path(__file__).with_name(filename))
 module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module);return module

branches=sibling('predicate_task_branches','read-rogue-predicate-branches.py')

@lru_cache(maxsize=1)
def load_profile():
 raw=PROFILE.read_bytes().replace(b'\r\n',b'\n')
 if hashlib.sha256(raw).hexdigest()!=PROFILE_SHA256:raise ValueError('泛型条件任务证据改变')
 p=json.loads(raw)
 if p['predicateBranchesProfileSHA256']!=branches.PROFILE_SHA256:raise ValueError('有限条件分支版本不符')
 return p

def verify_files(client):
 p=load_profile()
 for name,relative in [('GameAssembly.dll','GameAssembly.dll'),
  ('global-metadata.dat','StarRail_Data/il2cpp_data/Metadata/global-metadata.dat'),
  ('startup-metadata.dat','StarRail_Data/il2cpp_data/Metadata/startup-metadata.dat')]:
  with (client/relative).open('rb') as f:
   if hashlib.file_digest(f,'sha256').hexdigest()!=p['nativeFiles'][name]:raise ValueError('条件共享方法对应的客户端文件不符: '+name)

def read_rva(pe,address,size):
 section=pe.get_section_by_rva(address)
 if address<0 or size<0 or section is None or address-section.VirtualAddress+size>section.SizeOfRawData:
  raise ValueError('泛型记录没有实际文件范围')
 raw=pe.get_data(address,size)
 if len(raw)!=size:raise ValueError('泛型记录截断')
 return raw

def verify_generic_records(client,pe=None):
 p=load_profile()
 if pe is None:
  verify_files(client);pe=pefile.PE(str(client/'GameAssembly.dll'),fast_load=True)
 base=pe.OPTIONAL_HEADER.ImageBase;header=read_rva(pe,int(p['headerRva'],16),0x208)
 if header.hex()!=p['headerHex']:raise ValueError('泛型解析元数据头不符')
 word=lambda o:struct.unpack_from('<I',header,o)[0]
 types=(word(0x84)^0x68531d3f)+0x208
 startup=(client/'StarRail_Data/il2cpp_data/Metadata/startup-metadata.dat').read_bytes()
 table=word(0x164)^0x7f5c5934;signed=word(0xbc);signed=signed if signed<0x80000000 else signed-0x100000000
 count=((signed>>3)^0x79fc2ec)&0xffffffff
 if len(startup)!=p['startupMetadataLength'] or table!=int(p['genericClassTableOffsetInStartup'],16) or count!=p['genericClassCount'] or table+count*8>len(startup):
  raise ValueError('startup泛型类表偏移或数量不符，不能读取global表替代')
 registration=p['genericInstantiationRegistration']
 inst_array=struct.unpack('<Q',read_rva(pe,int(registration['pointerWordRva'],16),8))[0]-base
 if inst_array!=int(registration['arrayRva'],16):raise ValueError('泛型实例化注册表不符')
 code=p['bodyPointerRegistration'];reg=int(code['codeRegistrationRva'],16)
 code_array=struct.unpack('<Q',read_rva(pe,reg+0x30,8))[0]-base
 code_count=(struct.unpack('<I',read_rva(pe,reg+0x1c,4))[0]+0xeba017c5)&0xffffffff
 special_array=struct.unpack('<Q',read_rva(pe,reg+0x90,8))[0]-base
 if (code_array,code_count,special_array)!=(int(code['arrayRva'],16),code['count'],int(code['specialArrayRva'],16)):
  raise ValueError('共享主体函数数组不符，不能用invoker数组替代')
 function_table=(word(0xec)^0x67f701ba)+0x208;function_count=(word(0x1e4)^0x720fef70)//12
 specs=((word(0x38)+0xf778f1ab)&0xffffffff)+0x208
 if (function_table,function_count,specs)!=(int(p['functionTableOffsetInMetadata'],16),p['functionTableCount'],int(p['methodSpecTableOffsetInMetadata'],16)):
  raise ValueError('函数记录或MethodSpec表不符')
 with (client/'StarRail_Data/il2cpp_data/Metadata/global-metadata.dat').open('rb') as f,mmap.mmap(f.fileno(),0,access=mmap.ACCESS_READ) as data:
  if function_table+function_count*12>len(data):raise ValueError('完整函数表越界')
  for r in p['parentGenericRecords']:
   pos=types+r['index']*70
   if hex(pos)!=r['rowRvaInMetadata'] or data[pos+4:pos+8].hex()!=r['parentWord']:raise ValueError('具体条件的泛型父字段不符')
   native=(struct.unpack_from('<I',data,pos+4)[0]+0xb706aab9)&0xffffffff
   type_rva=0x4ffdd60+native*8;raw=read_rva(pe,type_rva,8);payload=struct.unpack_from('<I',raw)[0]
   if (native,hex(type_rva),raw.hex(),raw[6],payload)!=(r['parentNativeTypeIndex'],r['parentTypeRecordRva'],r['record'],21,r['payload']):raise ValueError('泛型父原生类型记录不符')
   pos=table+payload*8;raw=startup[pos:pos+8];definition,inst=struct.unpack('<ii',raw)
   if payload>=count or (hex(pos),raw.hex(),definition,inst)!=(r['startupRowOffset'],r['startupRowHex'],r['genericDefinition'],r['instIndex']):raise ValueError('startup泛型父记录不符')
  seen=set()
  for r in p['functionRecords']:
   index=r['functionTableIndex'];pos=function_table+index*12
   if index in seen or not 0<=index<function_count:raise ValueError('共享函数记录索引无效')
   seen.add(index);raw=data[pos:pos+12]
   spec=struct.unpack_from('<i',raw)[0];spec_pos=specs+spec*12;spec_raw=data[spec_pos:spec_pos+12]
   if (hex(pos),raw.hex(),spec,hex(spec_pos),spec_raw.hex())!=(r['tableRowInMetadata'],r['tableRowHex'],r['methodSpecIndex'],r['methodSpecRowInMetadata'],r['methodSpecHex']):raise ValueError('共享函数记录或MethodSpec不符')
   definition,at4,at8=struct.unpack('<iii',spec_raw)
   if definition!=r['definition']:raise ValueError('共享方法定义不符')
   for index,fact in zip([at4,at8],r['argumentsAt4At8'],strict=True):
    if fact is None:
     if index!=-1:raise ValueError('MethodSpec省略参数不符')
     continue
    address=inst_array+index*16;inst_raw=read_rva(pe,address,16);length,unused,argv=struct.unpack('<IIQ',inst_raw)
    argument=struct.unpack('<Q',read_rva(pe,argv-base,8))[0]-base;arg_raw=read_rva(pe,argument,8)
    actual={'instIndex':index,'instRva':hex(address),'instHex':inst_raw.hex(),'argvRva':hex(argv-base),
     'argumentRva':hex(argument),'argumentHex':arg_raw.hex(),'kind':arg_raw[6],'payload':struct.unpack_from('<I',arg_raw)[0]}
    if length!=1 or unused!=0 or actual!=fact:raise ValueError('共享方法具体配置参数不符')
   index=struct.unpack_from('<i',raw,4)[0];alternate=struct.unpack_from('<H',raw,8)[0]
   if alternate!=65535 or not 0<=index<code_count:raise ValueError('未匹配的共享函数指针路径')
   pointer=struct.unpack('<Q',read_rva(pe,code_array+index*8,8))[0]-base
   if (index,hex(pointer))!=(r['bodyPointerIndex'],r['bodyPointerRva']):raise ValueError('实际共享主体地址不符')
  methods=((word(0x14c)+0xf3a04294)&0xffffffff)+0x208
  analyzer=sibling('predicate_task_virtuals','analyze-client-46.py')
  for r in p['evaluationVirtualSlots']:
   pos=methods+r['definition']*26+12;encoded=struct.unpack_from('<H',data,pos)[0];slot=(encoded^analyzer.method_key(r['definition'])^0x9f7)&0xffff
   if (hex(pos),encoded,slot)!=(r['offsetInMetadata'],r['encodedWord'],r['decodedSlot']):raise ValueError('共享Evaluate与具体覆写槽不符')
  r=p['tickThrowMethodInfo'];pairs=((word(0x190)+0xed791073)&0xffffffff)+0x208
  pos=pairs+r['pairIndex']*8;first,second=struct.unpack_from('<II',data,pos)
  key=(((r['pairIndex']*0x87c3)^0x5fa3fad3)*0x334fb2ba+0x454d10d89e6a02a)&((1<<64)-1)
  key=(((key>>14)*0x102533d7+0x58972d9863a807)&((1<<64)-1))>>23
  destination=((first^0x6907ab9a)-key)&0xffffffff;source=(second-key+0xab3a6cb5)&0xffffffff
  payload=source&0x1fffffff;spec_pos=specs+payload*12;raw=data[spec_pos:spec_pos+12]
  if (hex(pos),data[pos:pos+8].hex(),destination,source>>29,payload,hex(spec_pos),raw.hex())!=(r['pairInMetadata'],r['pairHex'],r['destination'],6,r['payload'],r['methodSpecInMetadata'],r['methodSpecHex']):raise ValueError('Tick异常携带的泛型MethodInfo不符')
 return len(p['parentGenericRecords']),len(p['functionRecords'])

def verify_native(client):
 p=load_profile();verify_files(client);pe=pefile.PE(str(client/'GameAssembly.dll'),fast_load=True)
 directory=pe.OPTIONAL_HEADER.DATA_DIRECTORY[pefile.DIRECTORY_ENTRY['IMAGE_DIRECTORY_ENTRY_EXCEPTION']]
 bounds={s:(e,u) for s,e,u in struct.iter_unpack('<III',read_rva(pe,directory.VirtualAddress,directory.Size))}
 for m in p['sharedBodies']+p['runtimeProviders']:
  start=int(m['rva'],16);end=int(m['endRva'],16)
  if bounds.get(start)!=(end,int(m['unwindRva'],16)):raise ValueError('共享主体/解析辅助没有匹配完整PE范围')
  raw=read_rva(pe,start,end-start)
  if raw.hex()!=m['bytesHex'] or hashlib.sha256(raw).hexdigest()!=m['sha256']:raise ValueError('共享主体/解析辅助原生字节不符')
 for m in p['sharedLeaves']+[p['registrationFragment']]:
  start=int(m['rva'],16);raw=read_rva(pe,start,m['length'])
  if raw.hex()!=m['bytesHex'] or hashlib.sha256(raw).hexdigest()!=m['sha256'] or (m in p['sharedLeaves'] and start in bounds):raise ValueError('共享叶片段或注册片段边界/字节不符')
 config=p['genericBaseMetadata'];identities=config['methods']+[p['tickExceptionConstructor']]
 actual=branches.metadata(client,types=[config['name']],fields=True,field_offsets=False,
  method_indices=[m['index'] for m in identities],type_slots=[int(s['slot'],16) for s in p['tickExceptionClassSlots']])
 base=next(t for t in actual['methods'] if t['typeIndex']==config['typeIndex'])
 if {k:base[k] for k in config}!=config or actual['selectedMethods']!=identities or actual['typeInfoSlots']!=p['tickExceptionClassSlots']:raise ValueError('泛型基类定义、声明字段或Tick异常类/构造身份不符')
 parents,records=verify_generic_records(client,pe)
 return len(p['sharedBodies']),p['sharedBodyBytes'],len(p['runtimeProviders']),p['runtimeProviderBytes'],parents,records

def analyze(a):
 client=a.client_dir.resolve();design=sibling('predicate_task_output','read-client-design-data.py')
 output=design.outside_client(a.output,client)
 if ROOT/'build' not in output.parents:raise ValueError('只接受工程build下的共享条件任务证据')
 p=load_profile();bodies,size,providers,provider_size,parents,records=verify_native(client)
 if branches.source_conditions(client)!=p['sourceConditions']:raise ValueError('两个原始条件回调不符')
 result={'passed':True,'matchedCompleteSharedBodies':bodies,'matchedCompleteSharedBodyBytes':size,
  'matchedCompleteRuntimeProviders':providers,'matchedCompleteRuntimeProviderBytes':provider_size,
  'matchedSharedLeaves':len(p['sharedLeaves']),'matchedSharedLeafBytes':p['sharedLeafBytes'],
  'matchedRegistrationFragmentBytes':p['registrationFragment']['length'],'sourceGenericParents':parents,
  'matchedFunctionRecords':records,'sourceConditions':len(p['sourceConditions']),
  'startupMetadataSHA256':p['nativeFiles']['startup-metadata.dat'],
  **{k:v for k,v in p.items() if isinstance(v,bool)},'jsonInstalled':False,'clientExecutableStarted':False,'clientModified':False}
 output.parent.mkdir(parents=True,exist_ok=True);output.write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n','utf-8');return result

if __name__=='__main__':
 parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--client-dir',type=Path,required=True)
 parser.add_argument('--output',type=Path,default=ROOT/'build/rogue-predicate-task-evidence.json')
 print(json.dumps(analyze(parser.parse_args()),ensure_ascii=False))
