"""只读核对3011014完整原图、14份入口和三名NPC初遇；不执行客户端演出。"""
import argparse,base64,hashlib,importlib.util,json,struct,subprocess,zlib
from pathlib import Path
from types import SimpleNamespace

ROOT=Path(__file__).resolve().parents[1]
COMMIT='724b139d8c9c32d12552eb95745a4fee72bfe48b'

def module(name,path):
    spec=importlib.util.spec_from_file_location(name,path);result=importlib.util.module_from_spec(spec);spec.loader.exec_module(result);return result

def objects(value):
    if isinstance(value,dict):
        yield value
        for child in value.values():yield from objects(child)
    elif isinstance(value,list):
        for child in value:yield from objects(child)

def native_proof(client):
    native=module('recover_graph_native',ROOT/'scripts/analyze-client-46.py')
    result=native.extract(SimpleNamespace(client_dir=client,types=['FinishPerformanceMission','DGMIJNGMGOP','MMJJKEOONKE'],
        fields=True,field_offsets=True,inheritance=False,type_contains=[],method_indices=[514968,499629,634886,201086],
        method_rvas=[],type_slots=[0x9378a18,0x9378c28,0x93e3e30],method_slots=[],string_slots=[],method_names=[],method_name_contains=[],disassemble=0))
    assert not result['unmatchedTypes']
    assert {s['slot']:s['typeName']for s in result['typeInfoSlots']}=={
        '0x9378a18':'FinishPerformanceMission','0x9378c28':'DGMIJNGMGOP','0x93e3e30':'MMJJKEOONKE'}
    import pefile
    pe=pefile.PE(str(client/'GameAssembly.dll'),fast_load=True)
    try:
        d=pe.OPTIONAL_HEADER.DATA_DIRECTORY[3];bounds={b:e for b,e,_ in struct.iter_unpack('<III',pe.get_data(d.VirtualAddress,d.Size))}
        functions=[]
        for method in result['selectedMethods']:
            begin=int(method['rva'],16);end=bounds[begin];raw=pe.get_data(begin,end-begin)
            assert len(raw)==end-begin
            functions.append(method|dict(endRVA=hex(end),sha256=hashlib.sha256(raw).hexdigest()))
        # Exact native instructions: runtime config at +0x30, Key at +0x18, call verified sender.
        assert pe.get_data(0xe84a769,4)==bytes.fromhex('488b4630')
        assert pe.get_data(0xe84a795,4)==bytes.fromhex('488b5018')
        call=pe.get_data(0xe84a799,5);assert call[0]==0xe8 and 0xe84a79e+struct.unpack_from('<i',call,1)[0]==0xd99de70
        assert pe.get_data(0xd99e089,4)==bytes.fromhex('66bab504') # command1205
        return dict(clientFiles=result['files'],types=result['methods'],typeSlots=result['typeInfoSlots'],completeMethods=functions,
                    finishPerformanceMissionCommand=1205,unknownSyncRequestUsedForCompletion=False,clientAccepted=False)
    finally:pe.close()

def verify(resources,repository,client):
    support=module('recover_graph_wire_support',ROOT/'scripts/smoke-daily-recover-delivery.py').shared
    files={};entrances=set();intros=None
    for name in ['daily-recover-delivery-3011014-4.6.json','daily-recover-performance-entrances-4.6.json','daily-recover-introductions-4.6.json']:
        fixture=json.loads((ROOT/'src/test/resources/fixtures'/name).read_text('utf-8'));assert fixture['sourceCommit']==COMMIT
        for path,meta in fixture['sourceFiles'].items():
            raw=base64.b64decode(meta['sourceBlobBase64'],validate=True)if 'sourceBlobBase64'in meta else zlib.decompress(base64.b64decode(meta['sourceBlobDeflateBase64'],validate=True))
            assert hashlib.sha256(raw).hexdigest()==meta['sourceBlobSHA256'],path
            if path in files:assert files[path]==raw
            files[path]=raw
        if name.startswith('daily-recover-delivery-'):entrances.update(fixture['sourceOnlyPaths'])
        elif name.startswith('daily-recover-performance-'):entrances.update(fixture['sourceFiles'])
        else:intros=fixture
    assert len(entrances)==14 and len(files)==77
    before={};graphs={}
    for path,raw in files.items():
        target=(resources/path).resolve();assert target.is_relative_to(resources) and target.is_file(),path
        actual=target.read_bytes();before[path]=hashlib.sha256(actual).hexdigest()
        assert support.complete_json(actual)==support.complete_json(raw),'Installed complete original differs: '+path
        original=subprocess.run(['git','-C',str(repository),'show',COMMIT+':'+path],check=True,capture_output=True).stdout
        assert raw==original,'Fixed Git blob differs: '+path
        graphs[path]=json.loads(raw)
    table=graphs['ExcelOutput/PerformanceE.json'];assert all(sum(r==wanted for r in table)==1 for wanted in intros['performances'])
    acts=[]
    for id in [301101401,301101402,*range(301101406,301101412)]:
        path=f'Config/Level/Mission/3011014/Act/Act{id}.json';nodes=list(objects(graphs[path]));types=[n.get('$type')for n in nodes]
        assert all('RPG.GameCore.'+task in types for task in ['EndPerformance','WaitPerformanceEnd','FinishLevelGraph']),id
        transitions=[n for n in nodes if n.get('$type')in {'RPG.GameCore.CreateNPC','RPG.GameCore.PerformanceTransition','RPG.GameCore.CaptureNPCToCharacter','RPG.GameCore.CharacterTriggerFreeStyle','RPG.GameCore.SwitchCharacterAnchor'}]
        acts.append(dict(id=id,path=path,presentationTasks=transitions,taskTypes=sorted({t for t in types if t})))
    after={p:hashlib.sha256((resources/p).read_bytes()).hexdigest()for p in before};assert before==after
    return dict(passed=True,fixedCommit=COMMIT,completeOriginalFiles=77,recoveredEntrances=sorted(entrances),completeActs=acts,
        npcIntroPerformances=intros['performances'],sourceFilesSHA256=before,sourceFilesUnchanged=True,
        native=native_proof(client)if client else None,clientActExecuted=False,clientUIVerified=False)

def main():
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--resources',type=Path,default=ROOT/'resources/4.6');parser.add_argument('--source-repo',type=Path,default=ROOT/'references/TurnBasedGameData');parser.add_argument('--client-dir',type=Path);parser.add_argument('--output',type=Path,required=True);args=parser.parse_args()
    output=args.output.resolve();assert output.is_relative_to(ROOT/'build') and not output.exists();output.parent.mkdir(parents=True,exist_ok=True)
    # Reserve evidence before doing work; never replace a previous report.
    with output.open('x',encoding='utf-8')as stream:
        result=verify(args.resources.resolve(strict=True),args.source_repo.resolve(strict=True),args.client_dir.resolve(strict=True)if args.client_dir else None)
        json.dump(result,stream,ensure_ascii=False,indent=2)
    print(json.dumps(dict(passed=result['passed'],completeOriginalFiles=result['completeOriginalFiles'],entrances=len(result['recoveredEntrances']),acts=len(result['completeActs']),clientUIVerified=False)))

if __name__=='__main__':main()
