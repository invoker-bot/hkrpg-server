"""原生摆放推动一个原始已接取目标；真实解锁、正常关服与同UID H2重启，前置与场景是隔离夹具。"""
import argparse,hashlib,json,os,shutil,socket,subprocess,tempfile,time,urllib.error,urllib.request
from pathlib import Path
import sys
ROOT=Path(__file__).resolve().parents[1]
sys.path.insert(0,str(ROOT/'scripts'))
from smoke_resource_fixture import mirror_resources

def sha(p):return hashlib.sha256(p.read_bytes()).hexdigest()
def port(kind=socket.SOCK_STREAM):
 with socket.socket(type=kind)as s:s.bind(('127.0.0.1',0));return s.getsockname()[1]
def main():
 parser=argparse.ArgumentParser(description=__doc__)
 parser.add_argument('--output',type=Path,default=ROOT/'build/bird-position-native.json')
 args=parser.parse_args();output=args.output.resolve();assert output.is_relative_to(ROOT/'build')
 source=ROOT/'resources/4.6';jar=ROOT/'build/libs/hkrpg-server.jar';artifact=sha(jar)
 java=next((ROOT/'.tools').glob('jdk-21*/bin/java.exe'));flags=subprocess.CREATE_NO_WINDOW if os.name=='nt'else 0
 guarded=sorted({'resource-manifest.json',*[p.relative_to(source).as_posix()for p in (source/'ExcelOutput').glob('*.json')],*[p.relative_to(source).as_posix()for p in (source/'Config/Level/Mission/8023102').glob('*')if p.is_file()]})
 hashes={n:sha(source/n)for n in guarded};replacements={};original={}
 for n in ['ActivityPanel','MatchThreeLevel','MatchThreeConstValueClient']:
  rows=json.loads((source/f'ExcelOutput/{n}.json').read_text('utf-8'))
  if n=='ActivityPanel':
   row=next(r for r in rows if r['PanelID']==50014);original['panelUnlock']=row['UnlockConditions'];row['UnlockConditions']=''
  elif n=='MatchThreeLevel':
   levels=[r for r in rows if r['LevelID']==1100 and r.get('Mode')in [1,2]]
   assert len(levels)==2 and all(r['PlayerBirdID']==300 for r in levels)
   original['levelUnlocks']={r['Mode']:r['UnlockID']for r in levels}
   for row in levels:row['UnlockID']=0
  else:
   row=next(r for r in rows if r['ConstValueName']=='MatchThree_MainPageUnlock_SubMissionID');original['mainPageUnlock']=row['Value']['IntValue'];row['Value']['IntValue']=0
  replacements[f'ExcelOutput/{n}.json']=rows
 runs=ROOT/'build/smoke';runs.mkdir(parents=True,exist_ok=True)
 directory=Path(tempfile.mkdtemp(prefix='match-three-bird-position-',dir=runs));fixture=directory/'resources-fixture'
 bindings=mirror_resources(source,fixture,replacements)
 if bindings:
  plan=directory/'junction-plan.json';plan.write_text(json.dumps(bindings),'utf-8')
  helper=directory/'prepare-fixture.ps1';helper.write_text("param([string]$Plan)\n$ErrorActionPreference='Stop'\nforeach($binding in (Get-Content -LiteralPath $Plan -Raw | ConvertFrom-Json)){New-Item -ItemType Junction -Path $binding.target -Value $binding.source | Out-Null}\n",'utf-8')
  subprocess.run(['powershell','-NoProfile','-NonInteractive','-File',str(helper),'-Plan',str(plan)],check=True,capture_output=True,creationflags=flags)
 shutil.copytree(source/'Config/Level/Mission/8023102',fixture/'Config/Level/Mission/8023102')
 (fixture/'resource-manifest.json').write_text(json.dumps(dict(clientVersion='4.6.0',testFixture=True,matchThreeBirdPlacementFixture=True,originalGates=original)),'utf-8')
 (directory/'data').mkdir();(directory/'data/Gameplay46.json').write_text(json.dumps({'activities':[{'panelId':50014,'moduleId':5001400,'beginTime':0,'endTime':9223372036854775807}]}),'utf-8')
 http,mongo,game=port(),port(),port(socket.SOCK_DGRAM)
 cfg=directory/'config.json';cfg.write_text(json.dumps({'protocol':{'wireVersion':'4.6.0'},'resourceDir':str(fixture),'httpServer':{'bindAddress':'127.0.0.1','bindPort':http},'gameServer':{'bindAddress':'127.0.0.1','bindPort':game},'internalMongoServer':{'address':'127.0.0.1','port':mongo,'filePath':'./data/database.mv'}}),'utf-8');cfg_bytes=cfg.read_bytes()
 classes=directory/'probe-classes';classes.mkdir()
 fixture_java=ROOT/'scripts/fixtures/java'
 if not(fixture_java/'org/hkrpg/server/protocol/NativeMatchThreeBirdPositionProbe.java').is_file():fixture_java=ROOT/'build/bird-position-next-fixtures/java'
 sources=[fixture_java/f'org/hkrpg/server/{package}/{name}.java'for package,name in [('protocol','NativeMatchThreeBirdPositionProbe'),('gameplay','MatchThreeBirdPositionFixtureInspector')]]
 compile=subprocess.run([str(java.with_name('javac.exe')),'-encoding','UTF-8','-cp',str(jar),'-d',str(classes),*[str(p)for p in sources]],capture_output=True,creationflags=flags)
 (directory/'javac.log').write_bytes(compile.stdout+compile.stderr);assert compile.returncode==0,directory/'javac.log'
 java_flags=['-Dfile.encoding=UTF-8','-Dstdout.encoding=UTF-8','-Dstderr.encoding=UTF-8',f'-Djdk.net.unixdomain.tmpdir={directory/"tcp-fallback"}']
 def request(route,body=None):
  req=urllib.request.Request(f'http://127.0.0.1:{http}'+route,data=None if body is None else json.dumps(body).encode(),headers={'Content-Type':'application/json'})
  with urllib.request.urlopen(req,timeout=5)as response:return json.load(response)
 credentials={};snapshots=[];inspections=[];stops=0
 for index in range(3):
  with(directory/f'server-{index}.log').open('wb')as log:
   process=subprocess.Popen([str(java),*java_flags,'-Xmx1536m','-jar',str(jar),'--no-handbook'],cwd=directory,stdin=subprocess.PIPE,stdout=log,stderr=subprocess.STDOUT,creationflags=flags)
   try:
    deadline=time.monotonic()+100
    while True:
     assert process.poll()is None,directory/f'server-{index}.log'
     try:
      if request('/health/ready')['ready']:break
     except(OSError,urllib.error.URLError):pass
     if time.monotonic()>deadline:raise RuntimeError('Server startup timed out: '+str(directory))
     time.sleep(.2)
    if not credentials:
     login=request('/hkrpg_cn/mdk/shield/api/login',{'account':'match-three-bird-story','password':'local-test'});assert login['retcode']==0;account=login['data']['account']
     combo=request('/hkrpg_cn/combo/granter/login/v2/login',{'data':json.dumps({'uid':account['uid'],'token':account['token']})});assert combo['retcode']==0
     credentials.update(accountUid=account['uid'],comboToken=combo['data']['combo_token'])
    assert sha(jar)==artifact
    control=dict(credentials,mode='fresh'if index==0 else'place',beforePosition=6 if index==1 else 1)
    klass='org.hkrpg.server.protocol.LoopbackMatchThreeProbe'if index==0 else'org.hkrpg.server.protocol.NativeMatchThreeBirdPositionProbe'
    child=subprocess.run([str(java),*java_flags,'-cp',str(jar)+os.pathsep+str(classes),klass,str(game)],cwd=directory,input=json.dumps(control)+'\n',capture_output=True,text=True,encoding='utf-8',timeout=100,creationflags=flags)
    probe_log=directory/f'probe-{index}.log';probe_log.write_text(child.stdout+child.stderr,'utf-8');assert child.returncode==0,probe_log
    row=json.loads(next(line for line in reversed(child.stdout.splitlines())if line.startswith('{"')));assert row['passed']
    credentials.setdefault('uid',row['uid']);assert row['uid']==credentials['uid']
    if index==0:credentials['baselineItems']=row['baselineItems']
    assert row['bird301Position']==(6 if index==0 else 1);snapshots.append(row)
   finally:
    if process.poll()is None:
     try:
      process.stdin.write(b'stop\n');process.stdin.flush();process.wait(timeout=45)
      assert process.returncode==(1000 if os.name=='nt'else 232),directory;stops+=1
     except BaseException:
      if process.poll()is None:process.terminate();process.wait(timeout=15)
      raise
     finally:process.stdin.close()
  input=directory/'credentials.json';input.write_text(json.dumps(dict(credentials,**row)),'utf-8')
  child=subprocess.run([str(java),*java_flags,'-cp',str(classes)+os.pathsep+str(jar),'org.hkrpg.server.gameplay.MatchThreeBirdPositionFixtureInspector',str(directory),str(input),'prepare'if index==0 else'inspect'],cwd=ROOT,capture_output=True,text=True,encoding='utf-8',timeout=100,creationflags=flags)
  inspector_log=directory/f'inspector-{index}.log';inspector_log.write_text(child.stdout+child.stderr,'utf-8');assert child.returncode==0,inspector_log
  inspected=json.loads(next(line for line in reversed(child.stdout.splitlines())if line.startswith('{"')));assert inspected['passed']and inspected['changedPlayers']==(1 if index==0 else 0)and inspected['changedLineups']==0
  assert inspected['goalFinished']==(index>0);inspections.append(inspected)
  assert cfg.read_bytes()==cfg_bytes
  print(json.dumps(dict(phase=index,uid=row['uid'],bird301Position=row['bird301Position'],goalFinished=inspected['goalFinished'],normalStops=stops)),flush=True)
 assert stops==3 and all(sha(source/n)==digest for n,digest in hashes.items())and sha(jar)==artifact
 for key in ['items','finishedModes','birdUseCount','birdMaximum','statistics']:
  assert all(r[key]==snapshots[0][key]for r in snapshots)
 result=dict(passed=True,artifactSha256=artifact,runtimeDirectory=str(directory),snapshots=snapshots,inspections=inspections,normalStops=stops,sameUIDNormalH2ProcessRestarts=2,sourceResourceHashes=hashes,sourceResourcesModified=False,actualNativeGoal=802310206,actualNativeBird=301,actualNativePosition=1,actualNativeFloorAction=2,targetCompletionPrewritten=False,birdUnlockCompletionPrewritten=False,acceptanceAndSceneAreFixtures=True,originalGates=original,clientGameplayVerified=False,completeGameplayVerified=False)
 output.write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n','utf-8');print(json.dumps(dict(passed=True,report=str(output),artifactSha256=artifact,normalStops=stops,sourceResourcesUnchanged=len(hashes))),flush=True)
if __name__=='__main__':main()
