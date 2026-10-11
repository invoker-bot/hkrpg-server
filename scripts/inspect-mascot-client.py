"""只读核对三个4.6吉祥物组、追逐/抓取脚本及原始宝箱分类；不执行脚本。"""
import argparse
import importlib.util
import json
from pathlib import Path
from types import SimpleNamespace

ROOT = Path(__file__).resolve().parents[1]
GROUPS = [f'Config/Level/GroupGraph/F{floor}/Group_F{floor}_G{group}.json'
          for floor,group in [(20411001,251),(20411001,253),(20412001,206)]]
MONSTER = 'Config/Level/Props/Chap04/InitLevelGraph_Prop_Chap04_MascotMonster_01.json'
COIN = 'Config/Level/Props/Chap04/InitLevelGraph_Prop_Chap04_MascotCoin_01.json'
EXPECTED = {path:(4370,'231f59484b581855aa3776880342cfe3dab1df3eaf70954872acc862c03f63d6') for path in GROUPS}
EXPECTED.update({MONSTER:(4176,'04552deeb9f73f0434a9c6790adc69d27938815effc7736af74da65ddc1d5cba'),
                 COIN:(266,'1026591d160e4d77ff66e0785dfaed1e690c34e37d8f27f89939b6be98ef6406')})

def module(name,file):
    spec=importlib.util.spec_from_file_location(name,ROOT/'scripts'/file)
    value=importlib.util.module_from_spec(spec);spec.loader.exec_module(value);return value

def walk(value):
    if isinstance(value,dict):
        yield value
        for child in value.values():yield from walk(child)
    elif isinstance(value,list):
        for child in value:yield from walk(child)

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--client-dir',type=Path,required=True)
    parser.add_argument('--output-dir',type=Path,default=ROOT/'build/mascot-client')
    args=parser.parse_args();output=args.output_dir.resolve()
    if not output.is_relative_to(ROOT/'build'):raise ValueError('输出仅限本项目build目录')
    profile=json.loads((ROOT/'scripts/fixtures/mascot-reader-4.6.json').read_text('utf-8'))
    if any(profile[k] for k in ('clientAccepted','runtimeInstallSupported','scriptsExecuted')):raise ValueError('只读剖面边界无效')
    records=module('mascot_design','read-client-design-data.py').extract(SimpleNamespace(
        client_dir=args.client_dir,paths=list(EXPECTED),paths_file=None,extract_dir=output/'records',output=output/'records.json'))
    if records['files']!=profile['clientFiles'] or records['indexSHA256']!=profile['indexSHA256']:raise ValueError('客户端指纹不匹配')
    graphs=module('mascot_graphs','recover-client-mission-graphs.py')
    expressions=module('mascot_expressions','read-rogue-resonance-abilities.py')
    class Reader(graphs.graphs.Reader):
        def _read(self,description):
            name=description.get('name')
            if name=='DynamicFloat' and self.data[self.pos]!=0:
                start=self.pos;reader=expressions.Reader(self.data[start:],self.profile)
                value=reader.read(description);self.pos+=reader.pos
                value.update({'$type':name,'$offset':start});return value
            table={'DynamicJsonConfig':'dynamicJsonConfigs','PropCurveMoveStartMode':'curveStartModes'}.get(name)
            if table:
                start=self.pos;tag=self.uvar();selected=self.profile[table].get(str(tag))
                if not selected:raise ValueError(f'未核对的{table}分支{tag}')
                value=self.obj(selected);value.update({'$variantId':tag,'$offset':start});return value
            return super()._read(description)
    requests={}
    for record in records['records']:
        path=record['sourcePath'];raw=Path(record['extractedFile']).read_bytes()
        if record['status']!='read' or (len(raw),record['sha256'])!=EXPECTED[path]:raise ValueError('脚本字节未匹配:'+path)
        reader=Reader(raw,profile);graph=reader.read({'kind':18,'name':'LevelGraphConfig'})
        if reader.pos!=len(raw):raise ValueError('脚本未完整读取:'+path)
        (output/(Path(path).stem+'.json')).write_text(json.dumps(graph,ensure_ascii=False,indent=2)+'\n','utf-8')
        if path in GROUPS:
            values=[v['TargetState'] for v in walk(graph) if v.get('$type')=='RequestGroupStateChange']
            if values!=[4,1,2,2,4] or graph.get('Type')!=2:raise ValueError('原始请求顺序未匹配')
            requests[path]=values
    native=module('mascot_native','analyze-client-46.py').extract(SimpleNamespace(
        client_dir=args.client_dir,types=['ChestType','QteCatchGhostNotify'],fields=True,inheritance=False,
        field_offsets=False,type_contains=[],method_indices=[],method_rvas=[],type_slots=[],method_slots=[],
        string_slots=[],method_names=[],method_name_contains=[],disassemble=0))
    enums={t['typeIndex']:{f['name']:f.get('constant',{}).get('value') for f in t['fields']} for t in native['methods']}
    if enums.get(10464,{}).get('CHEST_PUZZLE_MASCOT')!=228:raise ValueError('宝箱分类未匹配')
    expected={'OpenTreasureBox':0,'EscapeStart':1,'EscapeFinish':2,'FailFinish':3,'SuccessFinish':4,'AllFinish':5}
    if any(enums.get(24598,{}).get(k)!=v for k,v in expected.items()):raise ValueError('QTE本地通知枚举未匹配')
    (output/'native-enums.json').write_text(json.dumps(native,indent=2)+'\n','utf-8')
    report=dict(passed=True,fullyConsumedGraphs=5,fullyConsumedBytes=sum(v[0] for v in EXPECTED.values()),
        sourceRequests=requests,nativeChestType=228,qteNotifyIsLocal=True,clientLocalCurveMovesReplayed=False,
        serverListenerSemanticsFullyRecovered=False,clientExecutableStarted=False,clientModified=False,clientGameplayVerified=False)
    (output/'verification.json').write_text(json.dumps(report,indent=2)+'\n','utf-8');print(json.dumps(report))

if __name__=='__main__':main()
