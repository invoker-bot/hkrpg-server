"""有限损坏资格拒绝/合法原资格结算和firstStart；独占KCP+正常stop+只读H2，预检不启游戏服。"""
import argparse
import base64
import importlib.util
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile
import time
import traceback
import urllib.error
import zipfile

HERE=Path(__file__).resolve().parent
ROOT=next(path for path in HERE.parents if (path/'src/main/resources/protocol/4.6').is_dir())
INITIAL_PATH=ROOT/'scripts/smoke-chess-rogue-initial-query.py'
spec=importlib.util.spec_from_file_location('qualification_initial_source_base',INITIAL_PATH)
initial=importlib.util.module_from_spec(spec); spec.loader.exec_module(initial)
base=initial.base
PROOF=ROOT/'src/test/resources/fixtures/chess-rogue-qualification-integrity-4.6.json'
PROOF_SHA='efadaca6362d229be85fe58d84e5ad63b7ae57c6371f8094695611c6e83ec999'
def source_path(local, relative):
    candidate = HERE / local
    return candidate if candidate.is_file() else ROOT / relative


JAVA_SOURCES=[source_path('fixtures/java/org/hkrpg/server/protocol/NativeChessRogueQualificationProbe.java',
    'scripts/fixtures/java/org/hkrpg/server/protocol/NativeChessRogueQualificationProbe.java'),
    source_path('fixtures/java/emu/lunarcore/command/commands/ChessRogueQualificationFixtureCommand.java',
    'scripts/fixtures/java/emu/lunarcore/command/commands/ChessRogueQualificationFixtureCommand.java'),
    source_path('fixtures/java/org/hkrpg/server/gameplay/ChessRogueQualificationInspector.java',
    'scripts/fixtures/java/org/hkrpg/server/gameplay/ChessRogueQualificationInspector.java')]
OPERATIONS=['seed-null','before-null','after-null','seed-progress','before-progress','after-progress',
    'qualify','before-valid','after-valid','before-start','after-start']


def qualification_integrity_source():
    if base.sha(PROOF)!=PROOF_SHA: raise ValueError('Complete original qualification integrity source drifted')
    proof=json.loads(PROOF.read_bytes())
    if proof['sourceCommit']!=base.COMMIT or proof['clientGameplayVerified'] or len(proof['completeOriginalRows'])!=4:
        raise ValueError('Fixed source commit and all four original qualification rows required')
    for row in proof['completeOriginalRows']:
        original=subprocess.run(['git','-C',str(ROOT/'references/TurnBasedGameData'),'show',base.COMMIT+':'+row['path']],
            capture_output=True,check=True).stdout
        disk=base.inside(ROOT/'resources/4.6',row['path']).read_bytes()
        if base.digest_bytes(original)!=row['sourceFileSHA256'] or disk.replace(b'\r\n',b'\n')!=original.replace(b'\r\n',b'\n'):
            raise ValueError('Whole original source table differs: '+row['path'])
        if json.loads(disk).count(row['row'])!=1: raise ValueError('Complete original row missing or duplicated')
    whole=proof['wholeNativeUnlockedConsumer']; raw=base64.b64decode(whole['rawBase64'],validate=True)
    if whole['method']['index']!=614962 or len(raw)!=137 or len(raw)!=whole['byteCount'] or base.digest_bytes(raw)!=whole['sha256']:
        raise ValueError('Complete native unlocked consumer drifted')
    return proof


def runtime_capabilities(jar,preflight_only):
    with zipfile.ZipFile(jar) as archive:
        names=['org/hkrpg/server/gameplay/'+name+'.class' for name in ['ChessRogueManager','ChessRogueProfile']]
        ready=all(name in archive.namelist() and b'MissionStateRecords' in archive.read(name)
                  and b'mainCompleted' in archive.read(name) for name in names)
    if not ready and not preflight_only:
        raise ValueError('Qualification Native requires the newly frozen repaired jar; old258b is preflight only')
    return ready


class QualificationConsoleBridge:
    """Only exact ordered console acknowledgements allow explicit fixture operations."""
    def __init__(self,nonce):
        if nonce<=0: raise ValueError('Positive owned nonce required')
        self.nonce=nonce; self.operations=list(OPERATIONS); self.seen=set(); self.pending=None; self.acknowledged=[]

    def next_command(self,probe_text):
        for uid,operation in re.findall(r'CHESS_QUALIFICATION_READY_UID=(\d+):([a-z-]+)',probe_text):
            if int(uid)!=10001 or operation not in self.operations: raise ValueError('Wrong owned UID/operation')
            if operation in self.seen: continue
            if self.pending is not None or operation!=self.operations[len(self.acknowledged)]:
                raise ValueError('Out-of-order or overlapping qualification operation')
            self.seen.add(operation); self.pending=operation
            return f'chessqualfixture 10001 {operation} {self.nonce}\n'
        return None

    def next_acknowledgement(self,server_text):
        if self.pending is None: return None
        for uid,operation,nonce in re.findall(r'HKRPG_CHESS_QUALIFICATION_ACK:(\d+):([a-z-]+):(\d+)',server_text):
            if int(nonce)!=self.nonce: continue
            if int(uid)!=10001 or operation not in self.operations: raise ValueError('Wrong owned console UID/operation')
            if operation in self.acknowledged: continue
            if operation!=self.pending: raise ValueError('Out-of-order console acknowledgement')
            self.acknowledged.append(operation); self.pending=None
            return f'CHESS_QUALIFICATION_OBSERVED:{operation}\n'
        return None

    @property
    def complete(self): return self.acknowledged==self.operations


def prepare(jar,directory,preflight_only):
    source=qualification_integrity_source(); initial_source=initial.initial_source_proof()
    reward_source=initial.qualification_source_proof(ROOT/'resources/4.6')
    ready=runtime_capabilities(jar,preflight_only)
    prepared,java,options,classpath,ports=base.prepare(jar,directory,preflight_only)
    flags=subprocess.CREATE_NO_WINDOW if os.name=='nt' else 0
    result=subprocess.run([str(java.with_name('javac.exe')),'-encoding','UTF-8','-cp',classpath,
        '-d',str(directory/'probe-classes'),*map(str,JAVA_SOURCES)],capture_output=True,creationflags=flags,timeout=90)
    (directory/'qualification-javac.log').write_bytes(result.stdout+result.stderr)
    if result.returncode: raise RuntimeError('Qualification helpers javac failed, no Native executed')
    for label,kind,argument in [('descriptors','org.hkrpg.server.protocol.NativeChessRogueQualificationProbe','--verify-descriptors'),
            ('storage-codecs','org.hkrpg.server.gameplay.ChessRogueQualificationInspector','--verify-storage-codecs')]:
        checked=subprocess.run([str(java),*options,'-cp',classpath,kind,argument],capture_output=True,text=True,encoding='utf-8',
            creationflags=flags,timeout=60)
        path=directory/('qualification-'+label+'.log'); path.write_text(checked.stdout+checked.stderr,'utf-8')
        if checked.returncode: raise RuntimeError('Pure Qualification preflight failed: '+str(path))
        observed=initial.snapshot(path)
        if not observed['passed'] or observed.get('nativeExecuted',False): raise ValueError('Preflight is not actual gameplay')
        prepared['qualification'+label.title().replace('-','')]=observed
    marker_path=directory/'resources-fixture/resource-manifest.json'; marker=json.loads(marker_path.read_bytes())
    marker.update(qualificationIntegrityFixture=True,qualificationCommands=[5547,5426],qualificationSourceProofSHA256=PROOF_SHA)
    marker_path.write_text(json.dumps(marker,ensure_ascii=False,indent=2)+'\n','utf-8')
    nonce=int(time.time()); control=directory/'qualification-native-control.json'
    with control.open('x',encoding='utf-8') as writer: json.dump(dict(uid=10001,nonce=nonce),writer)
    reward_path=directory/'qualification-source-proof.json'; reward_path.write_bytes(initial.QUALIFICATION_PROOF.read_bytes())
    proof_path=directory/'qualification-integrity-source-proof.json'; proof_path.write_bytes(PROOF.read_bytes())
    tool_inputs=[Path(__file__),INITIAL_PATH,initial.BASE_PATH,PROOF,initial.PROOF,initial.QUALIFICATION_PROOF,*JAVA_SOURCES]
    prepared.update(frozenJarHasQualificationRepair=ready,qualificationSourceEvidence=source,
        completeInitialQuerySourceEvidence=initial_source,originalRewardSourceEvidence=reward_source,
        qualificationToolInputs={str(p.relative_to(ROOT)):base.sha(p) for p in tool_inputs},
        qualificationOwnedFiles={p.relative_to(directory).as_posix():base.sha(p) for p in [marker_path,control,reward_path,proof_path]},
        qualificationNonce=nonce)
    with (directory/'qualification-prepared-inputs.json').open('x',encoding='utf-8') as writer:
        writer.write(json.dumps(prepared,ensure_ascii=False,indent=2)+'\n')
    return prepared,java,options,classpath,ports


def unchanged(jar,directory,prepared):
    base.unchanged(jar,directory,prepared)
    if prepared['qualificationToolInputs']!={name:base.sha(ROOT/name) for name in prepared['qualificationToolInputs']}:
        raise ValueError('Qualification tool or fixed source input drifted')
    if prepared['qualificationOwnedFiles']!={name:base.sha(base.inside(directory,name)) for name in prepared['qualificationOwnedFiles']}:
        raise ValueError('Owned isolated qualification identity/source/control drifted')


def child_probe(jar,java,options,classpath,directory,server,server_log,game,credentials,prepared):
    bridge=QualificationConsoleBridge(prepared['qualificationNonce']); path=directory/'qualification-native.log'
    flags=subprocess.CREATE_NO_WINDOW if os.name=='nt' else 0
    with path.open('xb') as writer:
        child=subprocess.Popen([str(java),*options,'-cp',classpath,'org.hkrpg.server.protocol.NativeChessRogueQualificationProbe',str(game)],
            cwd=directory,stdin=subprocess.PIPE,stdout=writer,stderr=subprocess.STDOUT,creationflags=flags)
        child.stdin.write((json.dumps(credentials)+'\n').encode('utf-8')); child.stdin.flush()
        deadline=time.monotonic()+240
        try:
            while child.poll() is None:
                command=bridge.next_command(path.read_text('utf-8'))
                if command:
                    unchanged(jar,directory,prepared); server.stdin.write(command.encode('utf-8')); server.stdin.flush()
                ack=bridge.next_acknowledgement(server_log.read_text('utf-8'))
                if ack:
                    unchanged(jar,directory,prepared); child.stdin.write(ack.encode('utf-8')); child.stdin.flush()
                    if bridge.complete: child.stdin.close()
                if time.monotonic()>deadline: raise TimeoutError(path)
                time.sleep(.2)
        finally: base.stop_owned_probe(child,directory)
    if child.returncode or not bridge.complete: raise RuntimeError('Actual finite Qualification Native failed: '+str(path))
    observed=initial.snapshot(path)
    if not observed['passed'] or observed['uid']!=10001 or observed['negativeRequests']!=8 or observed['merchantRequests']!=0:
        raise ValueError('Incomplete/incorrect finite qualification network proof')
    return observed


def run(jar,output,preflight_only):
    runs=ROOT/'build/smoke'; runs.mkdir(parents=True,exist_ok=True)
    directory=Path(tempfile.mkdtemp(prefix='chess-rogue-native-qualification-',dir=runs)); artifact=base.sha(jar)
    observed=None; inspection=None; stops=0
    try:
        prepared,java,options,classpath,(http,mongo,game)=prepare(jar,directory,preflight_only)
        unchanged(jar,directory,prepared)
        if preflight_only:
            result=dict(passed=True,nativeExecuted=False,gameServerStarted=False,scenario='qualification-record-integrity',
                artifactSHA256=artifact,runtimeDirectory=str(directory),sourceEvidence=prepared)
        else:
            flags=subprocess.CREATE_NO_WINDOW if os.name=='nt' else 0; server_log=directory/'qualification-server.log'
            with server_log.open('xb') as writer:
                server=subprocess.Popen([str(java),*options,'-Xmx1536m','-cp',classpath,'org.hkrpg.server.HkrpgServer','--no-handbook'],
                    cwd=directory,stdin=subprocess.PIPE,stdout=writer,stderr=subprocess.STDOUT,creationflags=flags)
                ready=False
                try:
                    deadline=time.monotonic()+100
                    while not ready:
                        if server.poll() is not None: raise RuntimeError('Owned Qualification server startup failed: '+str(server_log))
                        try: ready=base.local_request(http,'/health/ready')['ready']
                        except (OSError,urllib.error.URLError): pass
                        if time.monotonic()>deadline: raise TimeoutError(server_log)
                        time.sleep(.2)
                    login=base.local_request(http,'/hkrpg_cn/mdk/shield/api/login',dict(account='chess-qualification-native',password='local-test'))
                    if login['retcode']!=0: raise ValueError('Owned Qualification account login failed')
                    account=login['data']['account']; combo=base.local_request(http,'/hkrpg_cn/combo/granter/login/v2/login',
                        dict(data=json.dumps(dict(uid=account['uid'],token=account['token']))))
                    if combo['retcode']!=0: raise ValueError('Owned Qualification combo login failed')
                    observed=child_probe(jar,java,options,classpath,directory,server,server_log,game,
                        dict(accountUid=account['uid'],comboToken=combo['data']['combo_token']),prepared)
                    unchanged(jar,directory,prepared)
                finally: stops+=base.stop_owned_server(server,directory,ready)
            if stops!=1: raise ValueError('Exactly one normal successful stop required')
            for phase in ['null','progress','valid']:
                a=json.loads((directory/('qualification-integrity-before-'+phase+'.json')).read_bytes())
                b=json.loads((directory/('qualification-integrity-after-'+phase+'.json')).read_bytes())
                if a!=b: raise ValueError('Actual Qualification Query/rejection changed complete state: '+phase)
            inspect_input=directory/'qualification-inspect-control.json'
            with inspect_input.open('x',encoding='utf-8') as writer: json.dump(dict(uid=10001),writer)
            checked=subprocess.run([str(java),*options,'-cp',classpath,'org.hkrpg.server.gameplay.ChessRogueQualificationInspector',
                str(directory),str(inspect_input)],capture_output=True,text=True,encoding='utf-8',creationflags=flags,timeout=60)
            inspect_log=directory/'qualification-inspect.log'; inspect_log.write_text(checked.stdout+checked.stderr,'utf-8')
            if checked.returncode: raise RuntimeError('Physical read-only Qualification H2 inspection failed: '+str(inspect_log))
            inspection=initial.snapshot(inspect_log)
            if not inspection['passed'] or not inspection['physicallyReadOnly'] or inspection['actualPlayerDocumentReads']!=2:
                raise ValueError('Two independent actual read-only H2 Player restorations required')
            settled=json.loads((directory/'qualification-integrity-legal-qualification.json').read_bytes())
            if settled['productionQualificationReward']!=18013104 or settled['creditDelta']!=5000:
                raise ValueError('Legal original qualification refresh was not separately attributed')
            result=dict(passed=True,nativeExecuted=True,gameServerStarted=True,scenario='qualification-record-integrity',
                artifactSHA256=artifact,runtimeDirectory=str(directory),sourceEvidence=prepared,snapshot=observed,
                nativeRequests=observed['requests'],nativeChecks=len(observed['checks']),normalStops=stops,
                readOnlyH2Inspections=1,actualIndependentPlayerRestorations=2,sameUIDProcessRestarts=0,
                inspection=inspection,qualificationProductionSettlement=settled,
                corruptFixtureSetupFiles=['qualification-integrity-setup-null.json','qualification-integrity-setup-progress.json'],
                queryOrRejectedStartDocumentChanges=0,queryOrRejectedStartWalletIncrement=0,
                sourceQualificationRefreshCredits=5000,firstReward109011Paid=False,merchantRequests=0,
                querySaveCallsInstrumented=False,originalResourcesModified=False,operatorDataModified=False,
                qualificationStoryVerified=False,clientGameplayVerified=False)
        unchanged(jar,directory,prepared)
        with output.open('x',encoding='utf-8') as writer: writer.write(json.dumps(result,ensure_ascii=False,indent=2)+'\n')
        print(json.dumps(dict(passed=True,nativeExecuted=result['nativeExecuted'],artifactSHA256=artifact,runtimeDirectory=str(directory)),ensure_ascii=False))
    except BaseException as error:
        with (directory/'failed-qualification-run.json').open('x',encoding='utf-8') as writer:
            json.dump(dict(passed=False,error=repr(error),traceback=''.join(traceback.format_exception(error)),
                artifactSHA256=artifact,runtimeDirectory=str(directory),normalStops=stops,snapshot=observed,inspection=inspection),
                writer,ensure_ascii=False,indent=2)
        raise


def main():
    if hasattr(sys.stdout,'reconfigure'): sys.stdout.reconfigure(encoding='utf-8')
    parser=argparse.ArgumentParser(description=__doc__); parser.add_argument('--jar',type=Path,required=True)
    parser.add_argument('--output',type=Path,required=True); parser.add_argument('--preflight-only',action='store_true')
    args=parser.parse_args(); jar=args.jar.resolve(); output=args.output.resolve()
    if not jar.is_file() or not jar.is_relative_to(ROOT/'build') or not output.is_relative_to(ROOT/'build'):
        raise ValueError('Only frozen local build jar and fresh build report accepted')
    base.reserve_output(jar,output); run(jar,output,args.preflight_only)


if __name__=='__main__': main()
