"""原始日常破坏三物件、100/200活跃度一次领取、重连与正常H2重启；首房与钱包隔离。"""
from pathlib import Path
import argparse,json,hashlib,os,subprocess,socket,tempfile,time,urllib.request,urllib.error
ROOT=Path(__file__).resolve().parents[1]; source=ROOT/'resources/4.6'
parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--jar',type=Path,default=ROOT/'build/libs/hkrpg-server.jar');args=parser.parse_args();artifact=args.jar.resolve()
assert artifact.is_file(), 'Build the server first'
java=ROOT/'.tools/jdk-21.0.12.1+1/bin/java.exe'
flags=subprocess.CREATE_NO_WINDOW if os.name=='nt' else 0
directory=Path(tempfile.mkdtemp(prefix='daily-prop-',dir=ROOT/'build/smoke'))
fixture=directory/'resources-fixture';excel=fixture/'ExcelOutput';excel.mkdir(parents=True)
names=['ExcelOutput/RogueRoom.json','ExcelOutput/MapEntrance.json','ExcelOutput/MazeProp.json','ExcelOutput/ConstValueRogue.json',
'ExcelOutput/MainMission.json','ExcelOutput/DailyActiveQuestPool.json','ExcelOutput/QuestData.json','ExcelOutput/FinishWay.json','ExcelOutput/RewardData.json','ExcelOutput/ItemConfig.json','Config/LevelOutput/RuntimeFloor/P80001_F80001003.json','Config/LevelOutput/SharedRuntimeGroup/Groups_P80001_F80001003/LevelGroup_P80001_F80001003_G22.json']
hashes={name:hashlib.sha256((source/name).read_bytes()).hexdigest() for name in names}
original_coins=None
for p in (source/'ExcelOutput').glob('*.json'):
 target=excel/p.name
 if p.name=='ConstValueRogue.json':
  data=json.loads(p.read_text('utf-8-sig'));row=next(r for r in data if r['ConstRogueName']=='Start_Rogue_Coin_Base');original_coins=row['ConstValue'];row['ConstValue']='300';target.write_text(json.dumps(data),'utf-8')
 elif p.name=='MainMission.json':
  row=next(r for r in json.loads(p.read_text('utf-8-sig')) if r['MainMissionID']==4030006)
  target.write_text(json.dumps([row]),'utf-8') # Retain one original locked mission; do not enter an automatic opening instance.
 else:os.link(p,target)
def literal(p):return "'"+str(p).replace("'","''")+"'"
if os.name=='nt':subprocess.run(['powershell','-NoProfile','-NonInteractive','-Command','New-Item -ItemType Junction -Path '+literal(fixture/'Config')+' -Value '+literal(source/'Config')+' | Out-Null'],check=True,capture_output=True,creationflags=flags)
else:(fixture/'Config').symlink_to(source/'Config',target_is_directory=True)
(fixture/'resource-manifest.json').write_text(json.dumps(dict(clientVersion='4.6.0',testFixture=True,originalValue=original_coins)),'utf-8')
(directory/'data').mkdir();pool=json.loads((ROOT/'src/main/resources/defaults/RogueMapGen.json').read_text('utf-8-sig'));original_pool=pool['1'];pool['1']=[306];(directory/'data/RogueMapGen.json').write_text(json.dumps(pool),'utf-8')
scoped=json.loads((ROOT/'src/main/resources/defaults/RogueMapGenByMap.json').read_text('utf-8-sig'));scoped['maps']['1']={'1':[306]};(directory/'data/RogueMapGenByMap.json').write_text(json.dumps(scoped),'utf-8')
def free(kind=socket.SOCK_STREAM):
 with socket.socket(type=kind) as s:s.bind(('127.0.0.1',0));return s.getsockname()[1]
http,db,game=free(),free(),free(socket.SOCK_DGRAM)
(directory/'config.json').write_text(json.dumps(dict(protocol=dict(wireVersion='4.6.0'),resourceDir=str(fixture),httpServer=dict(bindAddress='127.0.0.1',bindPort=http),gameServer=dict(bindAddress='127.0.0.1',bindPort=game),internalMongoServer=dict(address='127.0.0.1',port=db,filePath='./data/database.mv'))),'utf-8')
command=[str(java),'-Xmx1536m','-Djdk.net.unixdomain.tmpdir='+str(directory/'tcp-fallback'),'-jar',str(artifact),'--no-handbook']
def request(route,body=None):
 req=urllib.request.Request('http://127.0.0.1:'+str(http)+route,data=None if body is None else json.dumps(body).encode(),headers={'Content-Type':'application/json'})
 with urllib.request.urlopen(req,timeout=5) as response:return json.load(response)
def probe(name,credentials):
 r=subprocess.run([str(java),command[2],'-cp',str(artifact),'org.hkrpg.server.protocol.LoopbackDailyPropProbe',str(game)],input=json.dumps(credentials),cwd=directory,capture_output=True,text=True,encoding='utf-8',errors='replace',timeout=35,creationflags=flags)
 (directory/name).write_text(r.stdout+r.stderr,'utf-8');assert r.returncode==0,str(directory/name)
 data=json.loads(next(line for line in r.stdout.splitlines() if line.startswith('{"uid":')));assert data['passed'];return data
snapshots=[];credentials=None;normal_stops=[]
for iteration in range(2):
 with (directory/f'server-{iteration}.log').open('w',encoding='utf-8') as log:
  process=subprocess.Popen(command,cwd=directory,stdin=subprocess.PIPE,stdout=log,stderr=subprocess.STDOUT,text=True,encoding='utf-8',creationflags=flags)
  try:
   deadline=time.monotonic()+90
   while True:
    if process.poll() is not None:raise RuntimeError('Server failed '+str(directory))
    try:
     if request('/health/ready')['ready']:break
    except (OSError,urllib.error.URLError):pass
    if time.monotonic()>deadline:raise RuntimeError('Server readiness timeout '+str(directory))
    time.sleep(.2)
   if iteration==0:
    login=request('/hkrpg_cn/mdk/shield/api/login',dict(account='cn-46-daily-props',password='local-test'));assert login['retcode']==0
    account=login['data']['account'];combo=request('/hkrpg_cn/combo/granter/login/v2/login',dict(data=json.dumps(dict(uid=account['uid'],token=account['token']))));assert combo['retcode']==0
    credentials=dict(accountUid=account['uid'],comboToken=combo['data']['combo_token'])
    first=probe('daily-first.log',credentials);snapshots.append(first);credentials.update(resume=True)
   restored=probe(f'daily-resume-{iteration}.log',credentials);assert restored['savedDailyAndPropsRestored'] and restored['dailyPoints']==300 and restored['propProgress']==3 and restored['propQuestStatus']==3 and restored['autoQuestStatus']==3 and restored['nativeSavedDuplicateClaimsRejected'] and restored['nativeConsumedBoxesAbsent'] and restored['uid']==snapshots[0]['uid'];snapshots.append(restored)
   time.sleep(2)
  finally:
   if process.poll() is None:
    try:
     process.stdin.write('stop\n');process.stdin.flush();process.wait(timeout=20)
    except (OSError,subprocess.TimeoutExpired):process.kill();process.wait(timeout=5);raise
    assert process.returncode==(1000 if os.name=='nt' else 1000 % 256), 'Daily probe server did not stop through its stop command'
    normal_stops.append(process.returncode)
assert hashes=={name:hashlib.sha256((source/name).read_bytes()).hexdigest() for name in names}
assert snapshots[0]['nativeOriginalAutoReward'] and snapshots[0]['nativeOriginalPropReward'] and snapshots[0]['nativeAutoDuplicateRejected'] and snapshots[0]['nativePropDuplicateRejected'] and snapshots[0]['nativeThreeDestructionsFinishedDaily'] and snapshots[0]['nativeStaleTargetsCannotCredit'] and snapshots[0]['dailyPoints']==300 and snapshots[0]['propProgress']==3 and snapshots[0]['propQuestStatus']==3
assert all(snapshots[0][key] for key in ['nativeAutoPointPushBeforeQuery','nativePropPointPushBeforeQuery','nativePropProgressPushBeforeQuery'])
print(json.dumps(dict(passed=True,checks=['original-daily-pool-selected','original-auto-daily-100-points','original-three-props-finish-daily','original-prop-daily-200-points','native-auto-point-push-before-query','native-prop-point-push-before-query','native-prop-progress-push-before-query','native-duplicate-rewards-rejected','native-stale-props-no-daily-credit','reconnect-daily-and-props-restored','h2-restart-daily-and-props-restored'],jarSHA256=hashlib.sha256(artifact.read_bytes()).hexdigest(),normalStops=normal_stops,directory=str(directory),snapshots=snapshots,sourceSha256=hashes,fixture=dict(firstRoomOnly=True,originalFirstRoomPool=original_pool,room=306,sourceRoomAndGroupUnmodified=True,initialWallet=300,originalInitialWallet=original_coins,automaticMainMissionsDisabled=True,dailyPoolUnmodified=True,dailyQuestAndRewardUnmodified=True),sourceResourcesModified=False,clientGameplayVerified=False,crashAtomicityVerified=False)))
