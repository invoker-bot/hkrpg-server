"""有限 Aether roster KCP/H2：显式隔离持有、真实库存、六槽四位；预检不启动游戏服。"""
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
sys.path.insert(0,str(ROOT/'scripts'))
BASE_PATH=ROOT/'scripts/smoke-chess-rogue-merchant.py'
spec=importlib.util.spec_from_file_location('aether_mature_local_transport',BASE_PATH)
base=importlib.util.module_from_spec(spec);spec.loader.exec_module(base)
PROOF=ROOT/'src/test/resources/fixtures/aether-divide-roster-4.6.json'
PROOF_SHA='39230a643489ee248812836830154a5433a093e1b3aa315de9deaf068588333d'
def source_path(local,relative):
    candidate=HERE/local
    return candidate if candidate.is_file() else ROOT/relative


JAVA_SOURCES=[source_path('NativeAetherDivideRosterProbe.java','scripts/fixtures/java/org/hkrpg/server/protocol/NativeAetherDivideRosterProbe.java'),
    source_path('AetherDivideRosterFixtureSupport.java','scripts/fixtures/java/org/hkrpg/server/gameplay/AetherDivideRosterFixtureSupport.java'),
    source_path('AetherDivideRosterFixtureCommand.java','scripts/fixtures/java/emu/lunarcore/command/commands/AetherDivideRosterFixtureCommand.java'),
    source_path('AetherDivideRosterInspector.java','scripts/fixtures/java/org/hkrpg/server/gameplay/AetherDivideRosterInspector.java')]
INITIAL_OPERATIONS=['before-empty','after-empty','seed','before-owned','after-owned','before-rejects','after-rejects',
    'before-mutations','after-mutations','before-repeat','after-repeat','before-final-query','after-final-query']
OPERATIONS=dict(initial=INITIAL_OPERATIONS,restart=['before-restored','after-restored'])
EXPECTED_TABLE_COUNTS=dict(AetherDivideSpirit=19,AetherDivideSpiritPromotion=114,AetherDividePassiveSkill=32,
    AetherDivideTrainerLevel=5,AetherDivideConstCommon=5,AetherDivideChallengeList=42)


def last_json(path):
    return json.loads(next(line for line in reversed(path.read_text('utf-8').splitlines()) if line.startswith('{')))


def validate_proof(proof):
    if proof['sourceCommit']!=base.COMMIT or proof['clientExecutableStarted'] or proof['clientModified'] or proof['clientAccepted']:
        raise ValueError('Original source and client boundaries required')
    expected={'ExcelOutput/'+name+'.json':count for name,count in EXPECTED_TABLE_COUNTS.items()}
    if set(proof['files'])!=set(expected):raise ValueError('Exactly six complete source tables required')
    for path,count in expected.items():
        row=proof['files'][path];raw=base64.b64decode(row['rawBase64'],validate=True)
        original=base64.b64decode(row['originalRawBase64'],validate=True)
        if len(raw)!=row['size'] or base.digest_bytes(raw)!=row['sha256'] or len(original)!=row['originalSize'] \
                or base.digest_bytes(original)!=row['originalSHA256'] or len(row['completeRows'])!=count \
                or json.loads(raw)!=row['completeRows'] or json.loads(original)!=row['completeRows']:
            raise ValueError('Whole original source serialization changed: '+path)
    if len(proof['wholeNativeFunctions'])!=27 or sum(row['bytes'] for row in proof['wholeNativeFunctions'])!=8101:
        raise ValueError('All 27 whole functions required')
    for row in proof['wholeNativeFunctions']:
        raw=base64.b64decode(row['rawBase64'],validate=True)
        if len(raw)!=row['bytes'] or base.digest_bytes(raw)!=row['bodySHA256']:raise ValueError('Whole native function bytes changed')
    scope=proof['scope']
    if not all(scope[name] for name in ['storedRoster','lineupMutations','shortRequestArrays','completeLineupReplacementIsServerPolicy',
            'fragmentStockFromCurrentInventory','skillCoreCountFromCurrentInventory']) or any(scope[name] for name in
            ['automaticSpiritGrants','fragmentIsSpiritOwnership','acquisitionImplemented','battleImplemented','unknownInfoField11Used','officialShortArrayReplacementSemanticsVerified']):
        raise ValueError('Stored roster, inventory projection and finite server policy boundaries changed')
    return proof


def source_proof():
    if base.sha(PROOF)!=PROOF_SHA:raise ValueError('Frozen complete formal Aether source fixture drifted')
    proof=validate_proof(json.loads(PROOF.read_bytes()))
    if proof['descriptorSHA256']!=base.sha(ROOT/'src/main/resources/protocol/4.6/descriptor.pb'):
        raise ValueError('Matched descriptor changed')
    for name,row in proof['files'].items():
        disk=base.inside(ROOT/'resources/4.6',name).read_bytes()
        git=subprocess.run(['git','-C',str(ROOT/'references/TurnBasedGameData'),'show',base.COMMIT+':'+name],capture_output=True,check=True).stdout
        if base.digest_bytes(disk)!=row['sha256'] or base.digest_bytes(git)!=row['originalSHA256'] \
                or json.loads(disk)!=row['completeRows'] or json.loads(git)!=row['completeRows']:
            raise ValueError('Whole installed/original source object changed: '+name)
    return proof


def runtime_capabilities(jar,preflight_only,production):
    if production is not None and not preflight_only:raise ValueError('Production overlay is schema-only; actual Native requires one new frozen jar')
    with zipfile.ZipFile(jar) as archive:
        needed=['org/hkrpg/server/'+name+'.class' for name in ['gameplay/AetherDivideManager','gameplay/AetherDivideState',
            'gameplay/AetherDivideDefinitions','protocol/AetherDivide46']]
        ready=all(name in archive.namelist() for name in needed)
        if ready:ready=b'CoreStock' in archive.read(needed[0]) and b'AetherDivide46' in archive.read('org/hkrpg/server/protocol/VersionedPacketHandler.class')
    if not ready and not preflight_only:raise ValueError('Actual Native requires the newly frozen Aether roster+stock jar')
    if not ready and production is None:raise ValueError('Old258b only supports explicit schema-only owned production overlay')
    if production is not None:
        production=production.resolve()
        if not production.is_relative_to(ROOT/'build/aether-divide-roster-draft') or production.name!='owned-main' \
                or not (production/'org/hkrpg/server/gameplay/AetherDivideManager$Stock.class').is_file():
            raise ValueError('Only audited root owned Aether production bytecode accepted for preflight')
    return ready


class ConsoleBridge:
    def __init__(self,mode,nonce):
        if mode not in OPERATIONS or nonce<=0:raise ValueError('Unknown owned phase/nonce')
        self.operations=OPERATIONS[mode];self.nonce=nonce;self.seen=set();self.pending=None;self.acknowledged=[]

    def next_command(self,text):
        for uid,operation in re.findall(r'AETHER_ROSTER_READY_UID=(\d+):([a-z0-9-]+)',text):
            if int(uid)!=10001 or operation not in self.operations:raise ValueError('Wrong owned UID/operation')
            if operation in self.seen:continue
            if self.pending is not None or operation!=self.operations[len(self.acknowledged)]:raise ValueError('Out-of-order overlapping operation')
            self.seen.add(operation);self.pending=operation;return f'aetherrosterfixture 10001 {operation} {self.nonce}\n'
        return None

    def next_acknowledgement(self,text):
        if self.pending is None:return None
        for uid,operation,nonce in re.findall(r'HKRPG_AETHER_ROSTER_ACK:(\d+):([a-z0-9-]+):(\d+)',text):
            if int(nonce)!=self.nonce:continue
            if int(uid)!=10001 or operation not in self.operations:raise ValueError('Wrong console UID/operation')
            if operation in self.acknowledged:continue
            if operation!=self.pending:raise ValueError('Out-of-order console acknowledgement')
            self.acknowledged.append(operation);self.pending=None;return f'AETHER_ROSTER_OBSERVED:{operation}\n'
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
    config=directory/'config.json';config.write_text(json.dumps(dict(protocol=dict(wireVersion='4.6.0'),resourceDir=str(fixture),
        httpServer=dict(bindAddress='127.0.0.1',bindPort=http),gameServer=dict(bindAddress='127.0.0.1',bindPort=game),
        internalMongoServer=dict(address='127.0.0.1',port=mongo,filePath='./data/database.mv'))),'utf-8')
    marker=fixture/'resource-manifest.json';marker.write_text(json.dumps(dict(testFixture=True,aetherRosterFixture=True,sourceCommit=base.COMMIT,
        frozenJarSHA256=artifact,sourceProofSHA256=PROOF_SHA,clientVersion='4.6.0',productionSpiritAcquisitionVerified=False,originalResourcesModified=False)),'utf-8')
    control=directory/'aether-native-control.json';control.write_text(json.dumps(dict(uid=10001,nonce=nonce)),'utf-8')
    source=directory/'aether-source-proof.json';source.write_bytes(PROOF.read_bytes())
    classes=directory/'probe-classes';classes.mkdir();parts=[classes,*([production.resolve()] if production is not None else []),jar]
    classpath=os.pathsep.join(map(str,parts));options=['-Dfile.encoding=UTF-8','-Dstdout.encoding=UTF-8','-Dstderr.encoding=UTF-8',
        '-Djdk.net.unixdomain.tmpdir='+str(directory/'tcp-fallback'),'-Dio.netty.eventLoopThreads=2']
    if production is None:options.append('-Daether.expectedProductionJar='+str(jar))
    compiled=subprocess.run([str(java.with_name('javac.exe')),'--release','21','-encoding','UTF-8','-cp',classpath,'-d',str(classes),
        *map(str,JAVA_SOURCES)],capture_output=True,creationflags=flags,timeout=90)
    (directory/'aether-javac.log').write_bytes(compiled.stdout+compiled.stderr)
    if compiled.returncode:raise RuntimeError('Aether helper javac failed; no Native executed')
    preflight={}
    for label,kind,argument in [('descriptors','org.hkrpg.server.protocol.NativeAetherDivideRosterProbe','--verify-descriptors'),
            ('storage-codecs','org.hkrpg.server.gameplay.AetherDivideRosterInspector','--verify-storage-codecs')]:
        checked=subprocess.run([str(java),*options,'-cp',classpath,kind,argument],capture_output=True,text=True,encoding='utf-8',creationflags=flags,timeout=60)
        path=directory/('aether-'+label+'.log');path.write_text(checked.stdout+checked.stderr,'utf-8')
        if checked.returncode:raise RuntimeError('Pure Aether preflight failed: '+str(path))
        observed=last_json(path)
        if not observed['passed'] or observed.get('nativeExecuted',False):raise ValueError('Pure preflight is not actual gameplay')
        preflight[label]=observed
    inputs=[Path(__file__),BASE_PATH,ROOT/'scripts/smoke_resource_fixture.py',PROOF,*JAVA_SOURCES]
    source_paths=list((ROOT/'resources/4.6/ExcelOutput').glob('*.json'))
    prepared=dict(artifactSHA256=artifact,isolatedConfigSHA256=base.sha(config),sourceProofSHA256=PROOF_SHA,
        frozenJarHasAetherRosterStock=ready,preflightProductionOverlay=str(production) if production is not None else None,
        overlaySHA256={} if production is None else {path.relative_to(production).as_posix():base.sha(path) for path in production.rglob('*.class')},
        toolInputs={path.relative_to(ROOT).as_posix():base.sha(path) for path in inputs},
        ownedFiles={path.relative_to(directory).as_posix():base.sha(path) for path in [marker,control,source]},
        helperClasses={path.relative_to(classes).as_posix():base.sha(path) for path in classes.rglob('*.class')},
        sourceResourceHashes={path.relative_to(ROOT/'resources/4.6').as_posix():base.sha(path) for path in source_paths},
        preflight=preflight,nonce=nonce,wholeOriginalTables=6,wholeNativeFunctions=27,wholeNativeBytes=8101,
        sourceCommit=base.COMMIT,clientGameplayVerified=False,shortReplacementIsServerPolicy=True)
    (directory/'aether-prepared-inputs.json').write_text(json.dumps(prepared,ensure_ascii=False,indent=2)+'\n','utf-8')
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
    bridge=ConsoleBridge(mode,prepared['nonce']);path=directory/('aether-native-'+mode+'.log');flags=subprocess.CREATE_NO_WINDOW if os.name=='nt' else 0
    with path.open('xb') as writer:
        child=subprocess.Popen([str(java),*options,'-cp',classpath,'org.hkrpg.server.protocol.NativeAetherDivideRosterProbe',str(game)],
            cwd=directory,stdin=subprocess.PIPE,stdout=writer,stderr=subprocess.STDOUT,creationflags=flags)
        child.stdin.write((json.dumps(dict(credentials,mode=mode,jarSourceURI=jar.as_uri()))+'\n').encode('utf-8'));child.stdin.flush();deadline=time.monotonic()+360
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
            or observed['negativeRequests']!=(10 if mode=='initial' else 0):raise ValueError('Wrong/incomplete owned Aether snapshot')
    return observed


def run(jar,output,preflight_only,production):
    runs=ROOT/'build/smoke';runs.mkdir(parents=True,exist_ok=True)
    directory=Path(tempfile.mkdtemp(prefix='aether-divide-roster-',dir=runs));artifact=base.sha(jar);stops=0;snapshots=[];inspections=[];credentials={}
    try:
        prepared,java,options,classpath,(http,mongo,game)=prepare(jar,directory,preflight_only,production);unchanged(jar,directory,prepared)
        if preflight_only:result=dict(passed=True,nativeExecuted=False,gameServerStarted=False,artifactSHA256=artifact,runtimeDirectory=str(directory),sourceEvidence=prepared)
        else:
            if prepared['preflightProductionOverlay']:raise ValueError('Actual server may not consume a preflight overlay')
            flags=subprocess.CREATE_NO_WINDOW if os.name=='nt' else 0
            for index,mode in enumerate(OPERATIONS):
                server_log=directory/('aether-server-'+mode+'.log')
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
                            login=base.local_request(http,'/hkrpg_cn/mdk/shield/api/login',dict(account='aether-roster-native',password='local-test'))
                            if login['retcode']!=0:raise ValueError('Owned Aether account login failed')
                            account=login['data']['account'];combo=base.local_request(http,'/hkrpg_cn/combo/granter/login/v2/login',
                                dict(data=json.dumps(dict(uid=account['uid'],token=account['token']))))
                            if combo['retcode']!=0:raise ValueError('Owned Aether combo login failed')
                            credentials.update(accountUid=account['uid'],comboToken=combo['data']['combo_token'])
                        observed=child_probe(jar,java,options,classpath,directory,server,server_log,game,credentials,prepared,mode)
                        observed.update(artifactSHA256=artifact,isolatedConfigSHA256=prepared['isolatedConfigSHA256']);snapshots.append(observed);unchanged(jar,directory,prepared)
                    finally:stops+=base.stop_owned_server(server,directory,ready)
                for phase in (['empty','owned','rejects','repeat','final-query'] if mode=='initial' else ['restored']):
                    before=json.loads((directory/('aether-roster-before-'+phase+'.json')).read_bytes())
                    after=json.loads((directory/('aether-roster-after-'+phase+'.json')).read_bytes())
                    if before!=after:raise ValueError('Query/reject/idempotent operation changed complete actual state: '+phase)
                control=directory/('aether-inspect-'+mode+'.json');control.write_text(json.dumps(dict(uid=10001,mode=mode)),'utf-8')
                checked=subprocess.run([str(java),*options,'-cp',classpath,'org.hkrpg.server.gameplay.AetherDivideRosterInspector',str(directory),str(control)],
                    cwd=ROOT,capture_output=True,text=True,encoding='utf-8',creationflags=flags,timeout=120)
                path=directory/('aether-inspector-'+mode+'.log');path.write_text(checked.stdout+checked.stderr,'utf-8')
                if checked.returncode:raise RuntimeError('Physical readonly actual H2 inspection failed: '+str(path))
                inspected=last_json(path)
                if not inspected['passed'] or inspected['mode']!=mode or not inspected['physicallyReadOnly'] or inspected['actualPlayerDocumentReads']!=2:
                    raise ValueError('Two actual independent restored Players required in each physical readonly H2 inspection')
                inspections.append(inspected);unchanged(jar,directory,prepared)
                print(json.dumps(dict(phase=mode,normalStops=stops,requests=observed['requests'],checks=len(observed['checks']))),flush=True)
            if stops!=2 or len(snapshots)!=2 or len(inspections)!=2:raise ValueError('Two complete processes/normal stops/H2 checks required')
            setup=json.loads((directory/'aether-roster-fixture-setup.json').read_bytes())
            a,b=inspections
            if a['gameplay']['aetherDivide']!=b['gameplay']['aetherDivide'] \
                    or any(a[key]!=b[key] for key in ['expectedMaterials','credits','jades','ordinaryAvatarDocuments','ordinaryTeamDocuments','ordinaryExtraTeamDocuments','actualItemDocuments']):
                raise ValueError('SameUID login/restart changed persisted roster/actual materials/ordinary wallet/team')
            result=dict(passed=True,nativeExecuted=True,gameServerStarted=True,scenario='stored-aether-roster-real-stock-six-lineups',
                artifactSHA256=artifact,runtimeDirectory=str(directory),normalStops=stops,sameUIDProcessRestarts=1,readOnlyH2Inspections=2,
                actualIndependentPlayerRestorations=4,nativeRequests=sum(row['requests'] for row in snapshots),nativeChecks=sum(len(row['checks']) for row in snapshots),
                snapshots=snapshots,inspections=inspections,sourceEvidence=prepared,explicitFixtureSetup=setup,
                queryOrRejectedOrIdempotentDocumentChanges=0,queryOrRejectedOrIdempotentWalletIncrement=0,
                rosterAcquisition='explicit isolated stored fixture, not production capture',fragmentOnly6010IsOwned=False,
                shortReplacementIsServerPolicy=True,officialShortReplacementVerified=False,originalResourcesModified=False,operatorDataModified=False,
                productionSpiritAcquisitionVerified=False,battleVerified=False,challengeVerified=False,clientGameplayVerified=False,crashAtomicityVerified=False)
        unchanged(jar,directory,prepared)
        with output.open('x',encoding='utf-8') as writer:writer.write(json.dumps(result,ensure_ascii=False,indent=2)+'\n')
        print(json.dumps(dict(passed=True,nativeExecuted=result['nativeExecuted'],artifactSHA256=artifact,runtimeDirectory=str(directory)),ensure_ascii=False))
    except BaseException as error:
        with (directory/'failed-aether-run.json').open('x',encoding='utf-8') as writer:json.dump(dict(passed=False,error=repr(error),
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
