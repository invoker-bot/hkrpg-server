"""七项原表材料提交KCP/H2：真实扣料、一次领奖、重启与只读存档检查。"""
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
import zlib

HERE=Path(__file__).resolve().parent
ROOT=next(path for path in HERE.parents if (path/'src/main/resources/protocol/4.6').is_dir())
sys.path.insert(0,str(ROOT/'scripts'))
BASE_PATH=ROOT/'scripts/smoke-chess-rogue-merchant.py'
spec=importlib.util.spec_from_file_location('material_mature_local_transport',BASE_PATH)
base=importlib.util.module_from_spec(spec);spec.loader.exec_module(base)
PROOF=ROOT/'src/test/resources/fixtures/material-submit-activity-4.6.json'
PROOF_SHA='455304ad8bd49e783b91337bd1824d1b6bf843a368f97e1fa40a40577f290d64'
def source_path(local,relative):
    candidate=HERE/local
    return candidate if candidate.is_file() else ROOT/relative


JAVA_SOURCES=[source_path('NativeMaterialSubmitProbe.java','scripts/fixtures/java/org/hkrpg/server/protocol/NativeMaterialSubmitProbe.java'),
    source_path('MaterialSubmitFixtureSupport.java','scripts/fixtures/java/org/hkrpg/server/gameplay/MaterialSubmitFixtureSupport.java'),
    source_path('MaterialSubmitFixtureCommand.java','scripts/fixtures/java/emu/lunarcore/command/commands/MaterialSubmitFixtureCommand.java'),
    source_path('MaterialSubmitInspector.java','scripts/fixtures/java/org/hkrpg/server/gameplay/MaterialSubmitInspector.java')]
INITIAL_OPERATIONS=['before-empty','after-empty','before-no-window','after-no-window','seed','before-owned','after-owned','before-rejects','after-rejects','before-first','after-first','before-repeat','after-repeat','before-final-query','after-final-query']
OPERATIONS=dict(initial=INITIAL_OPERATIONS,restart=['windows','before-restored','after-restored','before-rest-repeats','after-rest-repeats','before-complete','after-complete','before-complete-repeat','after-complete-repeat','before-complete-query','after-complete-query'])
EXPECTED_TABLE_COUNTS=dict(MaterialSubmitter=28,MaterialSubmitterGroup=1,ActivityConfig=525,ActivityPanel=270,RewardData=9515,ItemConfig=2941)


def last_json(path):
    return json.loads(next(line for line in reversed(path.read_text('utf-8').splitlines()) if line.startswith('{')))


def validate_proof(proof):
    if proof['sourceCommit']!=base.COMMIT or proof['clientAccepted'] or not proof['resourceOriginalAndInstalledByteEqual']:
        raise ValueError('Fixed complete original material source boundaries required')
    expected={'ExcelOutput/'+name+'.json':count for name,count in EXPECTED_TABLE_COUNTS.items()}
    if set(proof['files'])!=set(expected):raise ValueError('Exactly six complete original material files required')
    for path,count in expected.items():
        row=proof['files'][path]
        if row['compression']!='zlib':raise ValueError('Only complete original zlib byte encoding accepted')
        raw=zlib.decompress(base64.b64decode(row['rawBase64'],validate=True))
        if len(raw)!=row['bytes'] or base.digest_bytes(raw)!=row['sha256'] or len(json.loads(raw))!=count or row['completeRowCount']!=count:
            raise ValueError('Whole original material bytes/count differ: '+path)
    return proof


def source_proof():
    if base.sha(PROOF)!=PROOF_SHA:raise ValueError('Formal material source fixture drifted')
    proof=validate_proof(json.loads(PROOF.read_bytes()))
    for name,row in proof['files'].items():
        raw=zlib.decompress(base64.b64decode(row['rawBase64'],validate=True));disk=base.inside(ROOT/'resources/4.6',name).read_bytes()
        original=subprocess.run(['git','-C',str(ROOT/'references/TurnBasedGameData'),'show',base.COMMIT+':'+name],capture_output=True,check=True).stdout
        if disk!=raw or (ROOT/'references/TurnBasedGameData'/name).read_bytes()!=raw or json.loads(original)!=json.loads(raw):raise ValueError('Whole installed/checkout bytes or complete original Git JSON changed: '+name)
        proof.setdefault('canonicalGitFiles',{})[name]=dict(sha256=base.digest_bytes(original),bytes=len(original),compression='zlib',rawBase64=base64.b64encode(zlib.compress(original,9)).decode(),completeJSONEqualToCheckout=True,physicalBytesEqualToCheckout=original==raw)
    return proof


def runtime_capabilities(jar,preflight_only,production):
    if production is not None and not preflight_only:raise ValueError('Production overlay is schema-only; actual Native requires one frozen jar')
    with zipfile.ZipFile(jar) as archive:
        needed=['org/hkrpg/server/'+name+'.class' for name in ['gameplay/MaterialSubmitManager','gameplay/MaterialSubmitState','gameplay/MaterialSubmitDefinitions','protocol/MaterialSubmit46']]
        ready=all(name in archive.namelist() for name in needed)
        if ready:ready=b'MaterialSubmit46' in archive.read('org/hkrpg/server/protocol/VersionedPacketHandler.class')
    if not ready and not preflight_only:raise ValueError('Actual Native requires newly frozen material submission jar')
    if not ready and production is None:raise ValueError('Immutable old jar requires audited owned production only for pure preflight')
    if production is not None:
        production=production.resolve()
        if not production.is_relative_to(ROOT/'build/material-submit-activity-next-source') or production.name!='production' or not (production/'org/hkrpg/server/gameplay/MaterialSubmitManager.class').is_file():raise ValueError('Only audited owned material bytecode allowed for preflight')
    return ready


def original_rewards():
    proof=validate_proof(json.loads(PROOF.read_bytes()))
    tables={name:json.loads(zlib.decompress(base64.b64decode(row['rawBase64'],validate=True))) for name,row in proof['files'].items()}
    rewards={row['RewardID']:row for row in tables['ExcelOutput/RewardData.json']};out={}
    for row in tables['ExcelOutput/MaterialSubmitter.json']:
        if not 301<=row['ID']<=307:continue
        r=rewards[row['RewardID']];items={1:r['Hcoin']}
        for n in range(1,6):
            if 'ItemID_'+str(n) in r:items[r['ItemID_'+str(n)]]=r['Count_'+str(n)]
        out[row['ID']]=items
    if set(out)!=set(range(301,308)):raise ValueError('Exactly seven source rewards required')
    return out


class ConsoleBridge:
    def __init__(self,mode,nonce):
        if mode not in OPERATIONS or nonce<=0:raise ValueError('Unknown owned phase/nonce')
        self.operations=OPERATIONS[mode];self.nonce=nonce;self.seen=set();self.pending=None;self.acknowledged=[]

    def next_command(self,text):
        for uid,operation in re.findall(r'MATERIAL_SUBMIT_READY_UID=(\d+):([a-z0-9-]+)',text):
            if int(uid)!=10001 or operation not in self.operations:raise ValueError('Wrong owned UID/operation')
            if operation in self.seen:continue
            if self.pending is not None or operation!=self.operations[len(self.acknowledged)]:raise ValueError('Out-of-order overlapping operation')
            self.seen.add(operation);self.pending=operation;return f'materialsubmitfixture 10001 {operation} {self.nonce}\n'
        return None

    def next_acknowledgement(self,text):
        if self.pending is None:return None
        for uid,operation,nonce in re.findall(r'HKRPG_MATERIAL_SUBMIT_ACK:(\d+):([a-z0-9-]+):(\d+)',text):
            if int(nonce)!=self.nonce:continue
            if int(uid)!=10001 or operation not in self.operations:raise ValueError('Wrong console UID/operation')
            if operation in self.acknowledged:continue
            if operation!=self.pending:raise ValueError('Out-of-order console acknowledgement')
            self.acknowledged.append(operation);self.pending=None;return f'MATERIAL_SUBMIT_OBSERVED:{operation}\n'
        return None

    @property
    def complete(self):return self.acknowledged==self.operations


def prepare(jar,directory,preflight_only,production):
    proof=source_proof();ready=runtime_capabilities(jar,preflight_only,production)
    java=next((ROOT/'.tools').glob('jdk-21*/bin/java.exe'),None)
    if java is None:raise ValueError('Owned Java21 required')
    flags=subprocess.CREATE_NO_WINDOW if os.name=='nt' else 0
    fixture=directory/'resources-fixture';junctions=base.mirror_resources(ROOT/'resources/4.6',fixture,{})
    if junctions:
        plan=directory/'junctions.json';plan.write_text(json.dumps(junctions),'utf-8')
        helper=directory/'prepare.ps1';helper.write_text("param([string]$Plan)\n$ErrorActionPreference='Stop'\nforeach ($binding in (Get-Content -LiteralPath $Plan -Raw | ConvertFrom-Json)) { New-Item -ItemType Junction -Path $binding.target -Value $binding.source | Out-Null }\n",'utf-8')
        subprocess.run(['powershell','-NoProfile','-NonInteractive','-File',str(helper),'-Plan',str(plan)],capture_output=True,check=True,creationflags=flags)
    artifact=base.sha(jar);nonce=int(time.time());http,mongo,game=base.transport_ports();(directory/'data').mkdir()
    config=directory/'config.json';config.write_text(json.dumps(dict(protocol=dict(wireVersion='4.6.0'),resourceDir=str(fixture),serverOptions=dict(startTrailblazerLevel=21),
        httpServer=dict(bindAddress='127.0.0.1',bindPort=http),gameServer=dict(bindAddress='127.0.0.1',bindPort=game),
        internalMongoServer=dict(address='127.0.0.1',port=mongo,filePath='./data/database.mv'))),'utf-8')
    marker=fixture/'resource-manifest.json';marker.write_text(json.dumps(dict(testFixture=True,materialSubmitFixture=True,sourceCommit=base.COMMIT,
        frozenJarSHA256=artifact,sourceProofSHA256=PROOF_SHA,clientVersion='4.6.0',productionMaterialAcquisitionVerified=False,originalResourcesModified=False)),'utf-8')
    control=directory/'material-native-control.json';control.write_text(json.dumps(dict(uid=10001,nonce=nonce)),'utf-8')
    source=directory/'material-source-proof.json';source.write_bytes(PROOF.read_bytes())
    classes=directory/'probe-classes';classes.mkdir();parts=[classes,*([production.resolve()] if production is not None else []),jar]
    classpath=os.pathsep.join(map(str,parts));options=['-Dfile.encoding=UTF-8','-Dstdout.encoding=UTF-8','-Dstderr.encoding=UTF-8',
        '-Djdk.net.unixdomain.tmpdir='+str(directory/'tcp-fallback'),'-Dio.netty.eventLoopThreads=2']
    if production is None:options.append('-Dmaterial.expectedProductionJar='+str(jar))
    compiled=subprocess.run([str(java.with_name('javac.exe')),'--release','21','-encoding','UTF-8','-cp',classpath,'-d',str(classes),
        *map(str,JAVA_SOURCES)],capture_output=True,creationflags=flags,timeout=90)
    (directory/'material-javac.log').write_bytes(compiled.stdout+compiled.stderr)
    if compiled.returncode:raise RuntimeError('Aether helper javac failed; no Native executed')
    preflight={}
    for label,kind,argument in [('descriptors','org.hkrpg.server.protocol.NativeMaterialSubmitProbe','--verify-descriptors'),
            ('storage-codecs','org.hkrpg.server.gameplay.MaterialSubmitInspector','--verify-storage-codecs')]:
        checked=subprocess.run([str(java),*options,'-cp',classpath,kind,argument],capture_output=True,text=True,encoding='utf-8',creationflags=flags,timeout=60)
        path=directory/('material-'+label+'.log');path.write_text(checked.stdout+checked.stderr,'utf-8')
        if checked.returncode:raise RuntimeError('Pure Aether preflight failed: '+str(path))
        observed=last_json(path)
        if not observed['passed'] or observed.get('nativeExecuted',False):raise ValueError('Pure preflight is not actual gameplay')
        preflight[label]=observed
    inputs=[Path(__file__),BASE_PATH,ROOT/'scripts/smoke_resource_fixture.py',PROOF,*JAVA_SOURCES]
    source_paths=list((ROOT/'resources/4.6/ExcelOutput').glob('*.json'))
    prepared=dict(artifactSHA256=artifact,isolatedConfigSHA256=base.sha(config),sourceProofSHA256=PROOF_SHA,
        frozenJarHasMaterialSubmit=ready,preflightProductionOverlay=str(production) if production is not None else None,
        overlaySHA256={} if production is None else {path.relative_to(production).as_posix():base.sha(path) for path in production.rglob('*.class')},
        toolInputs={path.relative_to(ROOT).as_posix():base.sha(path) for path in inputs},
        ownedFiles={path.relative_to(directory).as_posix():base.sha(path) for path in [marker,control,source]},
        helperClasses={path.relative_to(classes).as_posix():base.sha(path) for path in classes.rglob('*.class')},
        sourceResourceHashes={path.relative_to(ROOT/'resources/4.6').as_posix():base.sha(path) for path in source_paths},
        preflight=preflight,nonce=nonce,wholeOriginalTables=6,wholeNativeFunctions=58,wholeNativeBytes=12661,
        sourceCommit=base.COMMIT,clientGameplayVerified=False,submissionIdempotenceIsServerPolicy=True)
    (directory/'material-prepared-inputs.json').write_text(json.dumps(prepared,ensure_ascii=False,indent=2)+'\n','utf-8')
    return prepared,java,options,classpath,(http,mongo,game)


def unchanged(jar,directory,prepared):
    if base.sha(jar)!=prepared['artifactSHA256'] or base.sha(directory/'config.json')!=prepared['isolatedConfigSHA256']:
        raise ValueError('Frozen jar/isolated config drifted')
    for key,root in [('toolInputs',ROOT),('ownedFiles',directory),('helperClasses',directory/'probe-classes'),('sourceResourceHashes',ROOT/'resources/4.6')]:
        if prepared[key]!={name:base.sha(base.inside(root,name)) for name in prepared[key]}:raise ValueError('Audited Aether input changed: '+key)
    if prepared['preflightProductionOverlay']:
        production=Path(prepared['preflightProductionOverlay'])
        if prepared['overlaySHA256']!={name:base.sha(base.inside(production,name)) for name in prepared['overlaySHA256']}:raise ValueError('Preflight-only root production bytecode drifted')


def child_probe(jar,java,options,classpath,directory,server,server_log,game,credentials,prepared,mode):
    bridge=ConsoleBridge(mode,prepared['nonce']);path=directory/('material-native-'+mode+'.log');flags=subprocess.CREATE_NO_WINDOW if os.name=='nt' else 0
    with path.open('xb') as writer:
        child=subprocess.Popen([str(java),*options,'-cp',classpath,'org.hkrpg.server.protocol.NativeMaterialSubmitProbe',str(game)],
            cwd=directory,stdin=subprocess.PIPE,stdout=writer,stderr=subprocess.STDOUT,creationflags=flags)
        child.stdin.write((json.dumps(dict(credentials,mode=mode,jarSourceURI=jar.as_uri(),rewards=original_rewards()))+'\n').encode('utf-8'));child.stdin.flush();deadline=time.monotonic()+360
        try:
            while child.poll() is None:
                command=bridge.next_command(path.read_text('utf-8'))
                if command:unchanged(jar,directory,prepared);server.stdin.write(command.encode('utf-8'));server.stdin.flush()
                acknowledgement=bridge.next_acknowledgement(server_log.read_text('utf-8'))
                if acknowledgement:
                    unchanged(jar,directory,prepared);child.stdin.write(acknowledgement.encode('utf-8'));child.stdin.flush()
                    if bridge.complete:child.stdin.close()
                if time.monotonic()>deadline:raise TimeoutError(path)
                time.sleep(.2)
        finally:base.stop_owned_probe(child,directory)
    if child.returncode or not bridge.complete:raise RuntimeError('Actual finite Aether Native failed: '+str(path))
    observed=last_json(path)
    if not observed['passed'] or observed['uid']!=10001 or observed['mode']!=mode \
            or observed['negativeRequests']!=(12 if mode=='initial' else 8):raise ValueError('Wrong/incomplete owned Aether snapshot')
    return observed


def run(jar,output,preflight_only,production):
    runs=ROOT/'build/smoke';runs.mkdir(parents=True,exist_ok=True)
    directory=Path(tempfile.mkdtemp(prefix='material-submit-',dir=runs));artifact=base.sha(jar);stops=0;snapshots=[];inspections=[];credentials={}
    try:
        prepared,java,options,classpath,(http,mongo,game)=prepare(jar,directory,preflight_only,production);unchanged(jar,directory,prepared)
        if preflight_only:result=dict(passed=True,nativeExecuted=False,gameServerStarted=False,artifactSHA256=artifact,runtimeDirectory=str(directory),sourceEvidence=prepared)
        else:
            if prepared['preflightProductionOverlay']:raise ValueError('Actual server may not consume a preflight overlay')
            flags=subprocess.CREATE_NO_WINDOW if os.name=='nt' else 0
            for index,mode in enumerate(OPERATIONS):
                server_log=directory/('material-server-'+mode+'.log')
                with server_log.open('xb') as writer:
                    server=subprocess.Popen([str(java),*options,'-Xmx1536m','-cp',classpath,'org.hkrpg.server.HkrpgServer','--no-handbook'],
                        cwd=directory,stdin=subprocess.PIPE,stdout=writer,stderr=subprocess.STDOUT,creationflags=flags);ready=False
                    try:
                        deadline=time.monotonic()+100
                        while not ready:
                            if server.poll() is not None:raise RuntimeError('Owned Aether server startup failed: '+str(server_log))
                            try:ready=base.local_request(http,'/health/ready')['ready']
                            except(OSError,urllib.error.URLError):pass
                            if time.monotonic()>deadline:raise TimeoutError(server_log)
                            time.sleep(.2)
                        if not credentials:
                            login=base.local_request(http,'/hkrpg_cn/mdk/shield/api/login',dict(account='material-native',password='local-test'))
                            if login['retcode']!=0:raise ValueError('Owned Aether account login failed')
                            account=login['data']['account'];combo=base.local_request(http,'/hkrpg_cn/combo/granter/login/v2/login',
                                dict(data=json.dumps(dict(uid=account['uid'],token=account['token']))))
                            if combo['retcode']!=0:raise ValueError('Owned Aether combo login failed')
                            credentials.update(accountUid=account['uid'],comboToken=combo['data']['combo_token'])
                        observed=child_probe(jar,java,options,classpath,directory,server,server_log,game,credentials,prepared,mode)
                        observed.update(artifactSHA256=artifact,isolatedConfigSHA256=prepared['isolatedConfigSHA256']);snapshots.append(observed);unchanged(jar,directory,prepared)
                    finally:stops+=base.stop_owned_server(server,directory,ready)
                phases=['empty','no-window','owned','rejects','repeat','final-query'] if mode=='initial' else ['restored','rest-repeats','complete-repeat','complete-query']
                for phase in phases:
                    before=json.loads((directory/('material-before-'+phase+'.json')).read_bytes());after=json.loads((directory/('material-after-'+phase+'.json')).read_bytes())
                    if before!=after:raise ValueError('Readonly/rejected/idempotent actual material state changed: '+phase)
                control=directory/('material-inspect-'+mode+'.json');control.write_text(json.dumps(dict(uid=10001,mode=mode)),'utf-8')
                checked=subprocess.run([str(java),*options,'-cp',classpath,'org.hkrpg.server.gameplay.MaterialSubmitInspector',str(directory),str(control)],
                    cwd=ROOT,capture_output=True,text=True,encoding='utf-8',creationflags=flags,timeout=120)
                path=directory/('material-inspector-'+mode+'.log');path.write_text(checked.stdout+checked.stderr,'utf-8')
                if checked.returncode:raise RuntimeError('Physical readonly actual H2 inspection failed: '+str(path))
                inspected=last_json(path)
                if not inspected['passed'] or inspected['mode']!=mode or not inspected['physicallyReadOnly'] or inspected['actualPlayerDocumentReads']!=2:
                    raise ValueError('Two actual independent restored Players required in each physical readonly H2 inspection')
                inspections.append(inspected);unchanged(jar,directory,prepared)
                print(json.dumps(dict(phase=mode,normalStops=stops,requests=observed['requests'],checks=len(observed['checks']))),flush=True)
            if stops!=2 or len(snapshots)!=2 or len(inspections)!=2:raise ValueError('Two complete processes/normal stops/H2 checks required')
            setup=json.loads((directory/'material-fixture-setup.json').read_bytes());first,final=inspections
            for key in ['ordinaryAvatarDocuments','ordinaryTeamDocuments','ordinaryExtraTeamDocuments']:
                if first[key]!=final[key]:raise ValueError('Material flow or sameUID restart changed unrelated ordinary state')
            source_reward_totals={}
            for rewards in original_rewards().values():
                for item,amount in rewards.items():source_reward_totals[str(item)]=source_reward_totals.get(str(item),0)+amount
            if source_reward_totals['1']!=500 or source_reward_totals['2']!=280000 or source_reward_totals['213']!=21:raise ValueError('Original seven reward totals changed')
            if final['jades']-setup['after']['jades']!=500 or final['credits']-setup['after']['credits']!=280000:raise ValueError('Actual source wallets not paid exactly once')
            if final['expectedMaterials']['180005']!=0:raise ValueError('Real depleted shared material not observed')
            restored=json.loads((directory/'material-before-restored.json').read_bytes())
            if restored['gameplay']['materialSubmission']!=first['gameplay']['materialSubmission'] or restored['actualMaterials']!=first['expectedMaterials'] or any(restored[key]!=first[key] for key in ['credits','jades']):raise ValueError('SameUID restart did not restore exact saved material state, stock and wallet')
            result=dict(passed=True,nativeExecuted=True,gameServerStarted=True,scenario='source-seven-material-debits-one-time-rewards',artifactSHA256=artifact,runtimeDirectory=str(directory),normalStops=stops,sameUIDProcessRestarts=1,readOnlyH2Inspections=2,actualIndependentPlayerRestorations=4,nativeRequests=sum(row['requests'] for row in snapshots),nativeChecks=sum(len(row['checks']) for row in snapshots),snapshots=snapshots,inspections=inspections,sourceEvidence=prepared,explicitFixtureSetup=setup,originalSevenRewardTotals=source_reward_totals,queryOrRejectedOrIdempotentDocumentChanges=0,queryOrRejectedOrIdempotentWalletIncrement=0,productionMaterialAcquisitionVerified=False,originalResourcesModified=False,operatorDataModified=False,nativeSaveFailureInjected=False,wholeStoryOrActivityVerified=False,clientGameplayVerified=False,crashAtomicityVerified=False)
        unchanged(jar,directory,prepared)
        with output.open('x',encoding='utf-8') as writer:writer.write(json.dumps(result,ensure_ascii=False,indent=2)+'\n')
        print(json.dumps(dict(passed=True,nativeExecuted=result['nativeExecuted'],artifactSHA256=artifact,runtimeDirectory=str(directory)),ensure_ascii=False))
    except BaseException as error:
        with (directory/'failed-material-run.json').open('x',encoding='utf-8') as writer:json.dump(dict(passed=False,error=repr(error),
            traceback=''.join(traceback.format_exception(error)),artifactSHA256=artifact,runtimeDirectory=str(directory),normalStops=stops,
            snapshots=snapshots,inspections=inspections),writer,ensure_ascii=False,indent=2)
        raise


def main():
    if hasattr(sys.stdout,'reconfigure'):sys.stdout.reconfigure(encoding='utf-8')
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--jar',type=Path,required=True);parser.add_argument('--output',type=Path,required=True)
    parser.add_argument('--preflight-only',action='store_true');parser.add_argument('--preflight-production',type=Path)
    args=parser.parse_args();jar=args.jar.resolve();output=args.output.resolve()
    if not jar.is_file() or not jar.is_relative_to(ROOT/'build') or not output.is_relative_to(ROOT/'build'):raise ValueError('Only frozen local build jar and fresh build report accepted')
    runtime_capabilities(jar,args.preflight_only,args.preflight_production)
    base.reserve_output(jar,output);run(jar,output,args.preflight_only,args.preflight_production)


if __name__=='__main__':main()
