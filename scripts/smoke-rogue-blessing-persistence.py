"""经典战后原菜单 KCP/H2：三场实际源战斗、5676原价重置与选择；保存故障仅单元测试。"""
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
spec=importlib.util.spec_from_file_location('rogueblessing_mature_local_transport',BASE_PATH)
base=importlib.util.module_from_spec(spec);spec.loader.exec_module(base)
def source_path(local,relative):
    candidate=HERE/local
    return candidate if candidate.is_file() else ROOT/relative


PROOF=source_path('rogue-blessing-persistence-4.6.json','src/test/resources/fixtures/rogue-blessing-persistence-4.6.json')
PROOF_SHA='1c7be9a11f87aa1a2de109e244b2d70ef06bf046daf6e3d2f2370ecebc7104b3'

JAVA_SOURCES=[source_path('NativeRogueBlessingPersistenceProbe.java','scripts/fixtures/java/org/hkrpg/server/protocol/NativeRogueBlessingPersistenceProbe.java'),
    source_path('RogueBlessingPersistenceFixtureSupport.java','scripts/fixtures/java/org/hkrpg/server/gameplay/RogueBlessingPersistenceFixtureSupport.java'),
    source_path('RogueBlessingPersistenceFixtureCommand.java','scripts/fixtures/java/emu/lunarcore/command/commands/RogueBlessingPersistenceFixtureCommand.java'),
    source_path('RogueBlessingPersistenceInspector.java','scripts/fixtures/java/org/hkrpg/server/gameplay/RogueBlessingPersistenceInspector.java')]
INITIAL_OPERATIONS=['seed','before-query','after-query','before-reroll','after-reroll','before-rejects','after-rejects','before-pending','after-pending']
OPERATIONS=dict(initial=INITIAL_OPERATIONS,restart=['checkpoint-restored','before-restored-query','after-restored-query','before-select','after-select','before-repeat','after-repeat','before-final-query','after-final-query'])


def last_json(path):
    return json.loads(next(line for line in reversed(path.read_text('utf-8').splitlines()) if line.startswith('{')))


def validate_login_checkpoint(before,after):
    if before['livePlayer']!=after['livePlayer']:
        raise ValueError('Fixture login checkpoint changed actual live Player')
    expected=dict(before['storedPlayerDocument'])
    expected['lastActiveTime']=before['livePlayer']['lastActiveTime']
    if expected!=after['storedPlayerDocument']:
        raise ValueError('Fixture checkpoint persisted an unknown non-login field')
    for key in before.keys()|after.keys():
        if key!='storedPlayerDocument' and before.get(key)!=after.get(key):
            raise ValueError('Fixture checkpoint changed complete observation: '+key)
    delta={key:dict(before=before['storedPlayerDocument'].get(key),after=after['storedPlayerDocument'].get(key))
        for key in before['storedPlayerDocument'].keys()|after['storedPlayerDocument'].keys()
        if before['storedPlayerDocument'].get(key)!=after['storedPlayerDocument'].get(key)}
    if set(delta)-{'lastActiveTime'}:raise ValueError('No broad ignored fields are allowed')
    return delta


def source_proof():
    if base.sha(PROOF)!=PROOF_SHA:raise ValueError('Frozen source fixture drifted')
    proof=json.loads(PROOF.read_bytes())
    if proof['sourceCommit']!=base.COMMIT or len(proof['tables'])!=13 or len(proof['combatSources'])!=10:
        raise ValueError('All complete fixed tables/combat sources required')
    for table,record in proof['tables'].items():
        path=record['path'];disk=(ROOT/'resources/4.6'/path).read_bytes()
        raw=subprocess.run(['git','-C',str(ROOT/'references/TurnBasedGameData'),'show',base.COMMIT+':'+path],capture_output=True,check=True).stdout
        if base.digest_bytes(raw)!=record['sourceBlobSHA256'] or base.digest_bytes(disk)!=record['operatorSHA256'] or json.loads(raw)!=record['rows'] or json.loads(disk)!=record['rows']:
            raise ValueError('Whole original table changed: '+path)
    for path,record in proof['combatSources'].items():
        disk=(ROOT/'resources/4.6'/path).read_bytes()
        raw=subprocess.run(['git','-C',str(ROOT/'references/TurnBasedGameData'),'show',base.COMMIT+':'+path],capture_output=True,check=True).stdout
        if base.digest_bytes(raw)!=record['sourceBlobSHA256'] or base.digest_bytes(disk)!=record['operatorSHA256'] or json.loads(raw)!=json.loads(disk):
            raise ValueError('Whole original combat source changed: '+path)
    return proof

def first_world_actors():
    folder=ROOT/'resources/4.6';cache={};audit={}
    def original(path):
        if path in cache:return cache[path]
        disk=(folder/path).read_bytes();raw=subprocess.run(['git','-C',str(ROOT/'references/TurnBasedGameData'),'show',base.COMMIT+':'+path],capture_output=True,check=True).stdout
        parsed=json.loads(raw)
        if parsed!=json.loads(disk):raise ValueError('Whole first-world source changed: '+path)
        cache[path]=parsed;audit[path]=dict(originalSHA256=base.digest_bytes(raw),operatorSHA256=base.digest_bytes(disk),wholeObjectEquivalent=True);return parsed
    room=next(row for row in original('ExcelOutput/RogueRoom.json') if row['RogueRoomID']==304)
    entrance=next(row for row in original('ExcelOutput/MapEntrance.json') if row['ID']==room['MapEntrance'])
    groups=original('ExcelOutput/RogueMonsterGroup.json');monsters=original('ExcelOutput/RogueMonster.json');stages=original('ExcelOutput/StageConfig.json')
    output={}
    for group,content in room['GroupWithContent'].items():
        path=f"Config/LevelOutput/SharedRuntimeGroup/Groups_P80001_F{entrance['FloorID']}/LevelGroup_P80001_F{entrance['FloorID']}_G{group}.json"
        layout=original(path)
        if len(layout['MonsterList'])!=1:raise ValueError('Original unique first-world placement required')
        weights=next(row['RogueMonsterListAndWeight'] for row in groups if row['RogueMonsterGroupID']==content);choices=[]
        for monster,weight in weights.items():
            if weight<=0:raise ValueError('Original positive source weight required')
            row=next(row for row in monsters if row['RogueMonsterID']==int(monster));stage=next(stage for stage in stages if stage['StageID']==row['EventID'])
            choices.append(dict(monster=int(monster),npc=row['NpcMonsterID'],stage=row['EventID'],waves=[list(wave.values()) for wave in stage['MonsterList']]))
        output[group]=dict(content=content,inst=layout['MonsterList'][0]['ID'],weights=weights,choices=choices)
    if len(output)!=3:raise ValueError('Three original first-world encounter groups required')
    unlocks={row['RogueUnlockID']:row for row in original('ExcelOutput/RogueUnlockConfig.json')};rules={row['ID']:row for row in original('ExcelOutput/FinishWayRogue.json')}
    expected={12001:('RogueFinishUnlock',[11,11007],2),11:('FinishMission',[4030001,4030002],2),11007:('FinishMission',[1010403],1)}
    for identifier,(kind,ids,progress) in expected.items():
        rule=rules[unlocks[identifier]['UnlockFinishWay']]
        if rule['FinishType']!=kind or rule['ParamIntList']!=ids or rule['Progress']!=progress:raise ValueError('Original recursive first-world gate changed')
    mains=original('ExcelOutput/MainMission.json')
    audit['ExcelOutput/MainMission.json']['qualificationMainRows']={str(identifier):next(row for row in mains if row['MainMissionID']==identifier) for identifier in [4030001,4030002,1010403]}
    return output,audit

def runtime_capabilities(jar,preflight_only,production):
    if production is not None:raise ValueError('No production overlay allowed in this native lane')
    with zipfile.ZipFile(jar) as archive:
        ready=b'restoreMiracleShop' in archive.read('org/hkrpg/server/gameplay/UniverseManager.class')
    if not ready and not preflight_only:raise ValueError('Actual native requires new frozen rollback runtime jar')
    return ready


class ConsoleBridge:
    def __init__(self,mode,nonce):
        if mode not in OPERATIONS or nonce<=0:raise ValueError('Unknown owned phase/nonce')
        self.operations=OPERATIONS[mode];self.nonce=nonce;self.seen=set();self.pending=None;self.acknowledged=[]

    def next_command(self,text):
        for uid,operation in re.findall(r'ROGUE_BLESSING_READY_UID=(\d+):([a-z0-9-]+)',text):
            if int(uid)!=10001 or operation not in self.operations:raise ValueError('Wrong owned UID/operation')
            if operation in self.seen:continue
            if self.pending is not None or operation!=self.operations[len(self.acknowledged)]:raise ValueError('Out-of-order overlapping operation')
            self.seen.add(operation);self.pending=operation;return f'rogueblessingfixture 10001 {operation} {self.nonce}\n'
        return None

    def next_acknowledgement(self,text):
        if self.pending is None:return None
        for uid,operation,nonce in re.findall(r'HKRPG_ROGUE_BLESSING_ACK:(\d+):([a-z0-9-]+):(\d+)',text):
            if int(nonce)!=self.nonce:continue
            if int(uid)!=10001 or operation not in self.operations:raise ValueError('Wrong console UID/operation')
            if operation in self.acknowledged:continue
            if operation!=self.pending:raise ValueError('Out-of-order console acknowledgement')
            self.acknowledged.append(operation);self.pending=None;return f'ROGUE_BLESSING_OBSERVED:{operation}\n'
        return None

    def next_failure(self,text):
        for uid,operation,nonce in re.findall(r'HKRPG_ROGUE_BLESSING_FAILURE:(\d+):([a-z0-9-]+):(\d+)',text):
            if int(nonce)!=self.nonce:continue
            if int(uid)!=10001 or operation not in self.operations or operation!=self.pending:
                raise ValueError('Unowned or out-of-order fixture failure marker')
            return operation
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
    actors,actors_audit=first_world_actors();(directory/'actors.json').write_text(json.dumps(actors),'utf-8');(directory/'actors-source-audit.json').write_text(json.dumps(actors_audit),'utf-8')
    marker=fixture/'resource-manifest.json';marker.write_text(json.dumps(dict(testFixture=True,rogueBlessingPersistenceFixture=True,sourceCommit=base.COMMIT,
        frozenJarSHA256=artifact,sourceProofSHA256=PROOF_SHA,clientVersion='4.6.0',qualificationOrTalentPurchaseVerified=False,originalResourcesModified=False)),'utf-8')
    control=directory/'rogue-blessing-native-control.json';control.write_text(json.dumps(dict(uid=10001,nonce=nonce)),'utf-8')
    source=directory/'rogue-blessing-source-proof.json';source.write_bytes(PROOF.read_bytes())
    classes=directory/'probe-classes';classes.mkdir();parts=[classes,*([production.resolve()] if production is not None else []),jar]
    classpath=os.pathsep.join(map(str,parts));options=['-Dfile.encoding=UTF-8','-Dstdout.encoding=UTF-8','-Dstderr.encoding=UTF-8',
        '-Djdk.net.unixdomain.tmpdir='+str(directory/'tcp-fallback'),'-Dio.netty.eventLoopThreads=2']
    if production is None:options.append('-Drogueblessing.expectedProductionJar='+str(jar))
    compiled=subprocess.run([str(java.with_name('javac.exe')),'--release','21','-encoding','UTF-8','-cp',classpath,'-d',str(classes),
        *map(str,JAVA_SOURCES)],capture_output=True,creationflags=flags,timeout=90)
    (directory/'rogueblessing-javac.log').write_bytes(compiled.stdout+compiled.stderr)
    if compiled.returncode:raise RuntimeError('Classic blessing helper javac failed; no Native executed')
    preflight={}
    for label,kind,argument in [('descriptors','org.hkrpg.server.protocol.NativeRogueBlessingPersistenceProbe','--verify-descriptors'),
            ('storage-codecs','org.hkrpg.server.gameplay.RogueBlessingPersistenceInspector','--verify-storage-codecs')]:
        checked=subprocess.run([str(java),*options,'-cp',classpath,kind,argument],capture_output=True,text=True,encoding='utf-8',creationflags=flags,timeout=60)
        path=directory/('rogueblessing-'+label+'.log');path.write_text(checked.stdout+checked.stderr,'utf-8')
        if checked.returncode:raise RuntimeError('Pure Classic blessing preflight failed: '+str(path))
        observed=last_json(path)
        if not observed['passed'] or observed.get('nativeExecuted',False):raise ValueError('Pure preflight is not actual gameplay')
        preflight[label]=observed
    inputs=[Path(__file__),BASE_PATH,ROOT/'scripts/smoke_resource_fixture.py',PROOF,*JAVA_SOURCES]
    source_paths=sorted(set((ROOT/'resources/4.6/ExcelOutput').glob('*.json'))|{ROOT/'resources/4.6'/name for name in set(actors_audit)|set(proof['combatSources'])})
    prepared=dict(artifactSHA256=artifact,isolatedConfigSHA256=base.sha(config),sourceProofSHA256=PROOF_SHA,
        frozenJarHasBlessingRollback=ready,preflightProductionOverlay=str(production) if production is not None else None,
        overlaySHA256={} if production is None else {path.relative_to(production).as_posix():base.sha(path) for path in production.rglob('*.class')},
        toolInputs={path.relative_to(ROOT).as_posix():base.sha(path) for path in inputs},
        ownedFiles={path.relative_to(directory).as_posix():base.sha(path) for path in [marker,control,source,directory/'actors.json',directory/'actors-source-audit.json']},
        helperClasses={path.relative_to(classes).as_posix():base.sha(path) for path in classes.rglob('*.class')},
        sourceResourceHashes={path.relative_to(ROOT/'resources/4.6').as_posix():base.sha(path) for path in source_paths},
        preflight=preflight,nonce=nonce,wholeOriginalTables=13,wholeCombatSources=10,firstWorldSources=actors_audit,actors=actors,
        sourceCommit=base.COMMIT,clientGameplayVerified=False,nativeSaveFailureInjected=False)
    (directory/'rogueblessing-prepared-inputs.json').write_text(json.dumps(prepared,ensure_ascii=False,indent=2)+'\n','utf-8')
    return prepared,java,options,classpath,(http,mongo,game)


def unchanged(jar,directory,prepared):
    if base.sha(jar)!=prepared['artifactSHA256'] or base.sha(directory/'config.json')!=prepared['isolatedConfigSHA256']:
        raise ValueError('Frozen jar/isolated config drifted')
    for key,root in [('toolInputs',ROOT),('ownedFiles',directory),('helperClasses',directory/'probe-classes'),('sourceResourceHashes',ROOT/'resources/4.6')]:
        if prepared[key]!={name:base.sha(base.inside(root,name)) for name in prepared[key]}:raise ValueError('Audited Classic blessing input changed: '+key)
    if prepared['preflightProductionOverlay']:
        production=Path(prepared['preflightProductionOverlay'])
        if prepared['overlaySHA256']!={name:base.sha(base.inside(production,name)) for name in prepared['overlaySHA256']}:raise ValueError('Preflight-only root production bytecode drifted')


def child_probe(jar,java,options,classpath,directory,server,server_log,game,credentials,prepared,mode):
    bridge=ConsoleBridge(mode,prepared['nonce']);path=directory/('rogue-blessing-native-'+mode+'.log');flags=subprocess.CREATE_NO_WINDOW if os.name=='nt' else 0
    with path.open('xb') as writer:
        child=subprocess.Popen([str(java),*options,'-cp',classpath,'org.hkrpg.server.protocol.NativeRogueBlessingPersistenceProbe',str(game)],
            cwd=directory,stdin=subprocess.PIPE,stdout=writer,stderr=subprocess.STDOUT,creationflags=flags)
        child.stdin.write((json.dumps(dict(credentials,mode=mode,jarSourceURI=jar.as_uri()))+'\n').encode('utf-8'));child.stdin.flush();deadline=time.monotonic()+360
        try:
            while child.poll() is None:
                command=bridge.next_command(path.read_text('utf-8'))
                if command:unchanged(jar,directory,prepared);server.stdin.write(command.encode('utf-8'));server.stdin.flush()
                server_text=server_log.read_text('utf-8')
                failed_operation=bridge.next_failure(server_text)
                if failed_operation:raise RuntimeError('Owned fixture command failed with complete preserved evidence: '+failed_operation)
                acknowledgement=bridge.next_acknowledgement(server_text)
                if acknowledgement:
                    unchanged(jar,directory,prepared);child.stdin.write(acknowledgement.encode('utf-8'));child.stdin.flush()
                    if bridge.complete:child.stdin.close()
                if time.monotonic()>deadline:raise TimeoutError(path)
                time.sleep(.2)
        finally:base.stop_owned_probe(child,directory)
    if child.returncode or not bridge.complete:raise RuntimeError('Actual finite Classic blessing Native failed: '+str(path))
    observed=last_json(path)
    if not observed['passed'] or observed['uid']!=10001 or observed['mode']!=mode \
            or observed['negativeRequests']!=(5 if mode=='initial' else 1):raise ValueError('Wrong/incomplete owned Classic blessing snapshot')
    return observed


def run(jar,output,preflight_only,production):
    runs=ROOT/'build/smoke';runs.mkdir(parents=True,exist_ok=True)
    directory=Path(tempfile.mkdtemp(prefix='rogue-blessing-',dir=runs));artifact=base.sha(jar);stops=0;snapshots=[];inspections=[];credentials={}
    try:
        prepared,java,options,classpath,(http,mongo,game)=prepare(jar,directory,preflight_only,production);unchanged(jar,directory,prepared)
        if preflight_only:result=dict(passed=True,nativeExecuted=False,gameServerStarted=False,artifactSHA256=artifact,runtimeDirectory=str(directory),sourceEvidence=prepared)
        else:
            if prepared['preflightProductionOverlay']:raise ValueError('Actual server may not consume a preflight overlay')
            flags=subprocess.CREATE_NO_WINDOW if os.name=='nt' else 0
            for index,mode in enumerate(OPERATIONS):
                server_log=directory/('rogueblessing-server-'+mode+'.log')
                with server_log.open('xb') as writer:
                    server=subprocess.Popen([str(java),*options,'-Xmx1536m','-cp',classpath,'org.hkrpg.server.HkrpgServer','--no-handbook'],
                        cwd=directory,stdin=subprocess.PIPE,stdout=writer,stderr=subprocess.STDOUT,creationflags=flags);ready=False
                    try:
                        deadline=time.monotonic()+100
                        while not ready:
                            if server.poll() is not None:raise RuntimeError('Owned Classic blessing server startup failed: '+str(server_log))
                            try:ready=base.local_request(http,'/health/ready')['ready']
                            except(OSError,urllib.error.URLError):pass
                            if time.monotonic()>deadline:raise TimeoutError(server_log)
                            time.sleep(.2)
                        if not credentials:
                            login=base.local_request(http,'/hkrpg_cn/mdk/shield/api/login',dict(account='rogue-blessing-native',password='local-test'))
                            if login['retcode']!=0:raise ValueError('Owned Classic blessing account login failed')
                            account=login['data']['account'];combo=base.local_request(http,'/hkrpg_cn/combo/granter/login/v2/login',
                                dict(data=json.dumps(dict(uid=account['uid'],token=account['token']))))
                            if combo['retcode']!=0:raise ValueError('Owned Classic blessing combo login failed')
                            credentials.update(accountUid=account['uid'],comboToken=combo['data']['combo_token'],actors=prepared['actors'])
                        observed=child_probe(jar,java,options,classpath,directory,server,server_log,game,credentials,prepared,mode)
                        observed.update(artifactSHA256=artifact,isolatedConfigSHA256=prepared['isolatedConfigSHA256']);snapshots.append(observed);credentials.update(batch=observed["batch"],offers=observed["offers"],coins=observed["coins"]);unchanged(jar,directory,prepared)
                    finally:stops+=base.stop_owned_server(server,directory,ready)
                for phase in (['query','rejects','pending'] if mode=='initial' else ['restored-query','repeat','final-query']):
                    before=json.loads((directory/('rogue-blessing-before-'+phase+'.json')).read_bytes())
                    after=json.loads((directory/('rogue-blessing-after-'+phase+'.json')).read_bytes())
                    if before!=after:raise ValueError('Query/reject/idempotent operation changed complete actual state: '+phase)
                control=directory/('rogueblessing-inspect-'+mode+'.json');control.write_text(json.dumps(dict(uid=10001,mode=mode)),'utf-8')
                checked=subprocess.run([str(java),*options,'-cp',classpath,'org.hkrpg.server.gameplay.RogueBlessingPersistenceInspector',str(directory),str(control)],
                    cwd=ROOT,capture_output=True,text=True,encoding='utf-8',creationflags=flags,timeout=120)
                path=directory/('rogueblessing-inspector-'+mode+'.log');path.write_text(checked.stdout+checked.stderr,'utf-8')
                if checked.returncode:raise RuntimeError('Physical readonly actual H2 inspection failed: '+str(path))
                inspected=last_json(path)
                if not inspected['passed'] or inspected['mode']!=mode or not inspected['physicallyReadOnly'] or inspected['actualPlayerDocumentReads']!=2:
                    raise ValueError('Two actual independent restored Players required in each physical readonly H2 inspection')
                inspections.append(inspected);unchanged(jar,directory,prepared)
                print(json.dumps(dict(phase=mode,normalStops=stops,requests=observed['requests'],checks=len(observed['checks']))),flush=True)
            if stops!=2 or len(snapshots)!=2 or len(inspections)!=2:raise ValueError('Two complete processes/normal stops/H2 checks required')
            setup=json.loads((directory/'rogue-blessing-fixture-setup.json').read_bytes())
            a,b=inspections
            for key in ['credits','jades','storedAvatarDocuments','storedTeamDocuments','storedExtraTeamDocuments','storedItemDocuments']:
                if a[key]!=b[key]:raise ValueError('Reroll/choice/restart changed actual ordinary document/wallet: '+key)
            checkpoint_before=json.loads((directory/'rogue-blessing-checkpoint-restored-before.json').read_bytes())
            checkpoint_after=json.loads((directory/'rogue-blessing-checkpoint-restored-after.json').read_bytes())
            checkpoint_result=json.loads((directory/'rogue-blessing-checkpoint-restored-result.json').read_bytes())
            checkpoint_delta=validate_login_checkpoint(checkpoint_before,checkpoint_after)
            if not checkpoint_result['passed'] or checkpoint_result['realPlayerSaveCalls']!=1 or checkpoint_result['completeStoredPlayerDelta']!=checkpoint_delta:
                raise ValueError('Separate exact genuine login fixture checkpoint required')
            result=dict(passed=True,nativeExecuted=True,gameServerStarted=True,scenario='actual-first-world-three-WINs-ordinary-menu-reroll-choice',
                artifactSHA256=artifact,runtimeDirectory=str(directory),normalStops=stops,sameUIDProcessRestarts=1,readOnlyH2Inspections=2,
                actualIndependentPlayerRestorations=4,nativeRequests=sum(row['requests'] for row in snapshots),nativeChecks=sum(len(row['checks']) for row in snapshots),
                snapshots=snapshots,inspections=inspections,sourceEvidence=prepared,explicitFixtureSetup=setup,
                sourceStoryCompletionVerified=False,talentPurchaseVerified=False,offersOrChoicesSeeded=False,
                actualOriginalBattleSourceVerified=True,officialBattleCalculationOrInputVerified=False,
                queryOrRejectedDocumentChanges=0,nativeSaveFailureInjected=False,saveFailureRollbackCoveredByUnitTests=True,
                fixtureLoginCheckpoint=dict(realPlayerSaveCalls=1,before=checkpoint_before,after=checkpoint_after,completeStoredPlayerDelta=checkpoint_delta,
                    countedAsBlessingBehavior=False,countedAsReadOnlyBarrier=False),
                originalResourcesModified=False,operatorDataModified=False,clientGameplayVerified=False)

        unchanged(jar,directory,prepared)
        with output.open('x',encoding='utf-8') as writer:writer.write(json.dumps(result,ensure_ascii=False,indent=2)+'\n')
        print(json.dumps(dict(passed=True,nativeExecuted=result['nativeExecuted'],artifactSHA256=artifact,runtimeDirectory=str(directory)),ensure_ascii=False))
    except BaseException as error:
        with (directory/'failed-rogueblessing-run.json').open('x',encoding='utf-8') as writer:json.dump(dict(passed=False,error=repr(error),
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
