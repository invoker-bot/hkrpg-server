"""从只读审计和固定客户端导出记录生成本地显示组目录，不修改运营资源或客户端。

只接收无需服务端实体交互的普通提示物和任务人物；完整原件解码、归属、
任务条件与放置编号必须一致。复杂机关、战斗、状态组和未知字段保留原路径。
"""
import argparse
from collections import Counter
import hashlib
import importlib.util
import json
from pathlib import Path
import re
import subprocess

ROOT=Path(__file__).resolve().parents[1]
INDEX='bcac71ed6783dfd447de127bdcab7c40ae4e21d488c39cba1af6ef6aa801b608'
VISUAL_PROPS={100,104,106,111,117,120,121,122,124,138,141,142,143,144}


def module(name,file):
    spec=importlib.util.spec_from_file_location(name,ROOT/'scripts'/file)
    value=importlib.util.module_from_spec(spec);spec.loader.exec_module(value)
    return value


def load(path):
    return json.loads(path.read_text(encoding='utf-8-sig'))


class Number(str):
    """Keep the original JSON number spelling used by Gson's fingerprint."""


def canonical(value):
    if isinstance(value,Number):return str(value)
    if isinstance(value,dict):return '{'+','.join(json.dumps(k,ensure_ascii=False)+':'+canonical(value[k]) for k in sorted(value))+'}'
    if isinstance(value,list):return '['+','.join(canonical(v) for v in value)+']'
    return json.dumps(value,ensure_ascii=False,separators=(',',':'))


def fingerprint(text):
    parsed=json.loads(text,parse_int=Number,parse_float=Number)
    return hashlib.sha256(canonical(parsed).encode()).hexdigest()


def graph_reason(resources,path,cache,seen=None):
    if path in cache:return cache[path]
    seen=set() if seen is None else seen
    if path in seen:return None
    seen.add(path)
    file=resources/path
    if not file.is_file():return 'missing-server-graph'
    text=file.read_text(encoding='utf-8-sig')
    if 'RPG.GameCore.PropReqInteract' in text:return 'server-prop-request'
    for child in set(re.findall(r'"(Config/Level/[^"\r\n]+\.json)"',text)):
        reason=graph_reason(resources,child,cache,seen)
        if reason:return reason
    cache[path]=None
    return None


def placements(value,kind,key):
    result=[]
    for row in value.get(kind,[]):
        item={k:row.get(k,default) for k,default in [(key,0),('ID',0),('LoadOnInitial',True),
            ('IsDelete',False),('IsClientOnly',False),('IsOverrideInitLevelGraph',False),
            ('InitLevelGraph',None),('ServerInteractVerificationIDList',[])]}
        item['serverTrigger']=row.get('Trigger',{}).get('Server',False)
        result.append(item)
    return result


def binding_differences(source,native):
    result=[]
    for key,default in [('Category','Normal'),('LoadSide','Client'),('LoadOnInitial',False),
            ('OwnerMainMissionID',0),('AreaAnchorName',None),('LevelGraph',None),
            ('LoadCondition',None),('UnloadCondition',None)]:
        if source.get(key,default)!=native.get(key,default):result.append(key)
    for kind,key in [('PropList','PropID'),('NPCList','NPCID'),('MonsterList','NPCMonsterID')]:
        if placements(source,kind,key)!=placements(native,kind,key):result.append(kind)
    return result


def select(resources,audit):
    refs,_=module('visual_floor_refs','audit-scene-interactions.py').floor_references(resources)
    groups=list(audit['clientPropGroups'])
    for directory in ['SharedRuntimeGroup','RuntimeGroup']:
        for path in sorted((resources/f'Config/LevelOutput/{directory}').glob('*/*.json')):
            group=load(path);source=path.relative_to(resources).as_posix()
            if not group.get('PropList') and group.get('Category')=='Mission' and group.get('OwnerMainMissionID',0)>0 and group.get('LoadSide','Client')=='Client':
                groups.append(dict(groupPath=source,floorRefs=refs.get(source,[])))
    result=subprocess.run(['rg','-l','RPG.GameCore.PropReqInteract',str(resources/'Config/Level/Mission')],capture_output=True,text=True)
    if result.returncode not in (0,1):raise ValueError('无法核对任务脚本的实体交互')
    unsafe={int(Path(p).relative_to(resources/'Config/Level/Mission').parts[0]) for p in result.stdout.splitlines()
        if Path(p).relative_to(resources/'Config/Level/Mission').parts[0].isdigit()}
    defaults={}
    for prop in load(resources/'ExcelOutput/MazeProp.json'):
        if prop['ID'] in VISUAL_PROPS:
            path=resources/prop['JsonPath'];defaults[prop['ID']]=load(path).get('DefaultLevelGraphPath') if path.is_file() else None
    selected=[];decisions=[];cache={}
    for item in groups:
        source=item['groupPath'];group=load(resources/source);refs=[r for r in item['floorRefs'] if r['activeDefault']]
        reason=None
        if len(refs)!=1:reason='shared-or-unreferenced'
        elif refs[0]['floor']==10001001 and refs[0]['group']==16:reason='bernard-server-trigger'
        elif group.get('MonsterList'):reason='server-combat-actors'
        elif 'Book' in group.get('GroupName',''):reason='server-book-pickup'
        elif any(group.get(k) for k in ('GroupPropertyMap','GroupStateList','SavedValueCondition','SystemUnlockCondition','RandomInit')):reason='stateful-server-context'
        elif group.get('OwnerMainMissionID',0) in unsafe:reason='owner-prop-requests'
        elif any(p.get('PropID') not in VISUAL_PROPS and not p.get('IsClientOnly') for p in group.get('PropList',[])):reason='server-object-family'
        elif any(any(p.get(k) for k in ['RaidID','MappingInfoID','CocoonID','ChestID','EventID','ItemID','RewardID']) for p in group.get('PropList',[])):reason='server-gameplay-binding'
        elif any(p.get('ServerInteractVerificationIDList') or p.get('Trigger',{}).get('Server') for p in group.get('PropList',[])):reason='server-prop-verification'
        if reason is None:
            graphs={group.get('LevelGraph')}|{p.get('InitLevelGraph') if p.get('IsOverrideInitLevelGraph') else defaults.get(p.get('PropID')) for p in group.get('PropList',[])}
            for graph in graphs:
                if graph:
                    reason=graph_reason(resources,graph,cache)
                    if reason:break
        if reason is None:
            ref=refs[0];area=f"LevelArea_P{ref['floor']//1000}_F{ref['floor']}_G{ref['group']}"
            if group.get('AreaAnchorName')!=area:reason='nonlocal-area-binding'
            else:selected.append(dict(path=source,area=area,floor=ref['floor'],group=ref['group'],owner=group.get('OwnerMainMissionID',0),
                dormantMission=group.get('Category')=='Mission' and not group.get('LoadCondition') and not group.get('LoadOnInitial',False)))
        decisions.append(dict(path=source,disposition=reason or 'candidate-client-visual'))
    return selected,decisions


def generate(resources,audit,native_dir):
    decoder=module('visual_native_groups','recover-client-runtime-groups.py');profile=decoder.load_profile()
    selected,decisions=select(resources,audit);verified=[];proof=[]
    for row in selected:
        try:
            source=resources/row['path'];text=source.read_text(encoding='utf-8-sig')
            raw=(native_dir/row['path']).with_suffix('.bytes').read_bytes()
            native=decoder.resource_json(decoder.decode_group(raw,profile),profile,root=True)
            differences=binding_differences(json.loads(text),native)
            if differences:raise ValueError('原件绑定不同: '+','.join(differences))
            row['fingerprint']=fingerprint(text);row['nativeSHA256']=hashlib.sha256(raw).hexdigest()
            verified.append(row);proof.append(dict(path=row['path'],verified=True))
        except (OSError,ValueError,KeyError,AssertionError) as exc:
            proof.append(dict(path=row['path'],verified=False,error=type(exc).__name__+': '+str(exc)[:150]))
    catalog=dict(schema=1,indexSHA256=INDEX,nativeBindingsSHA256=hashlib.sha256(
        json.dumps(verified,sort_keys=True,separators=(',',':')).encode()).hexdigest(),
        groups=[[r['floor'],r['group'],r['owner'],r['dormantMission'],r['fingerprint']] for r in verified])
    report=dict(verified=len(verified),dormantMissions=sum(r['dormantMission'] for r in verified),
        decisions=decisions,nativeProof=proof,readOnly=True,clientModified=False)
    return catalog,report


def encode(catalog):
    head=json.dumps({k:v for k,v in catalog.items() if k!='groups'},ensure_ascii=False,indent=2).rstrip().removesuffix('}').rstrip()
    return head+',\n  "groups": [\n'+',\n'.join('    '+json.dumps(r,separators=(',',':')) for r in catalog['groups'])+'\n  ]\n}\n'


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--resources',type=Path,default=ROOT/'resources/4.6')
    parser.add_argument('--audit',type=Path,required=True)
    parser.add_argument('--native-dir',type=Path,required=True)
    parser.add_argument('--output',type=Path,default=ROOT/'build/client-visual-groups-4.6.json')
    args=parser.parse_args();output=args.output.resolve()
    if any(output==p.resolve() or p.resolve() in output.parents for p in (args.resources,args.native_dir)):
        raise ValueError('不能向资源或只读原件目录输出')
    catalog,report=generate(args.resources.resolve(),load(args.audit),args.native_dir.resolve())
    output.parent.mkdir(parents=True,exist_ok=True);output.write_text(encode(catalog),encoding='utf-8')
    output.with_suffix('.proof.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    print(json.dumps(dict(groups=report['verified'],dormantMissions=report['dormantMissions'],
        nativeRejected=sum(not r['verified'] for r in report['nativeProof']),decisions=dict(Counter(r['disposition'] for r in report['decisions'])))))
