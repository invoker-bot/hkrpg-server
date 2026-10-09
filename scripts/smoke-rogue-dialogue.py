"""原始事件房间305与NPC/选项经实际KCP/重连/H2重启；首房/钱包为隔离夹具，效果仍不可用。"""
from pathlib import Path
import json,hashlib,os,subprocess,socket,tempfile,time,urllib.request,urllib.error
ROOT=Path(__file__).resolve().parents[1]; source=ROOT/'resources/4.6'; artifact=ROOT/'build/libs/hkrpg-server.jar'
java=ROOT/'.tools/jdk-21.0.12.1+1/bin/java.exe'
flags=subprocess.CREATE_NO_WINDOW if os.name=='nt' else 0
directory=Path(tempfile.mkdtemp(prefix='rogue-dialogue-',dir=ROOT/'build/smoke'))
fixture=directory/'resources-fixture';excel=fixture/'ExcelOutput';excel.mkdir(parents=True)
names=['ExcelOutput/RogueNPC.json','ExcelOutput/RogueRoom.json','ExcelOutput/MapEntrance.json','ExcelOutput/NPCData.json','ExcelOutput/ConstValueRogue.json',
'Config/LevelOutput/RuntimeFloor/P80001_F80001003.json','Config/LevelOutput/SharedRuntimeGroup/Groups_P80001_F80001003/LevelGroup_P80001_F80001003_G21.json',
'Config/Level/Rogue/RogueNPC/RogueNPC40398.json','Config/Level/Rogue/RogueDialogue/Event40398/Act40398.json','Config/Level/Rogue/RogueDialogue/Event40398/Opt40398.json']
hashes={name:hashlib.sha256((source/name).read_bytes()).hexdigest() for name in names}
original_coins=None
for p in (source/'ExcelOutput').glob('*.json'):
 target=excel/p.name
 if p.name=='ConstValueRogue.json':
  data=json.loads(p.read_text('utf-8-sig'));row=next(r for r in data if r['ConstRogueName']=='Start_Rogue_Coin_Base');original_coins=row['ConstValue'];row['ConstValue']='300';target.write_text(json.dumps(data),'utf-8')
 else:os.link(p,target)
def literal(p):return "'"+str(p).replace("'","''")+"'"
if os.name=='nt':subprocess.run(['powershell','-NoProfile','-NonInteractive','-Command','New-Item -ItemType Junction -Path '+literal(fixture/'Config')+' -Value '+literal(source/'Config')+' | Out-Null'],check=True,capture_output=True,creationflags=flags)
else:(fixture/'Config').symlink_to(source/'Config',target_is_directory=True)
(fixture/'resource-manifest.json').write_text(json.dumps(dict(clientVersion='4.6.0',testFixture=True,originalValue=original_coins)),'utf-8')
(directory/'data').mkdir();pool=json.loads((ROOT/'src/main/resources/defaults/RogueMapGen.json').read_text('utf-8-sig'));original_pool=pool['1'];pool['1']=[305];(directory/'data/RogueMapGen.json').write_text(json.dumps(pool),'utf-8')
def free(kind=socket.SOCK_STREAM):
 with socket.socket(type=kind) as s:s.bind(('127.0.0.1',0));return s.getsockname()[1]
http,db,game=free(),free(),free(socket.SOCK_DGRAM)
(directory/'config.json').write_text(json.dumps(dict(protocol=dict(wireVersion='4.6.0'),resourceDir=str(fixture),httpServer=dict(bindAddress='127.0.0.1',bindPort=http),gameServer=dict(bindAddress='127.0.0.1',bindPort=game),internalMongoServer=dict(address='127.0.0.1',port=db,filePath='./data/database.mv'))),'utf-8')
command=[str(java),'-Xmx1536m','-Djdk.net.unixdomain.tmpdir='+str(directory/'tcp-fallback'),'-jar',str(artifact),'--no-console','--no-handbook']
def request(route,body=None):
 req=urllib.request.Request('http://127.0.0.1:'+str(http)+route,data=None if body is None else json.dumps(body).encode(),headers={'Content-Type':'application/json'})
 with urllib.request.urlopen(req,timeout=5) as response:return json.load(response)
def probe(name,credentials):
 r=subprocess.run([str(java),command[2],'-cp',str(artifact),'org.hkrpg.server.protocol.LoopbackRogueDialogueProbe',str(game)],input=json.dumps(credentials),cwd=directory,capture_output=True,text=True,encoding='utf-8',errors='replace',timeout=35,creationflags=flags)
 (directory/name).write_text(r.stdout+r.stderr,'utf-8');assert r.returncode==0,str(directory/name)
 data=json.loads(next(line for line in r.stdout.splitlines() if line.startswith('{"uid":')));assert data['passed'];return data
snapshots=[];credentials=None
for iteration in range(2):
 with (directory/f'server-{iteration}.log').open('w',encoding='utf-8') as log:
  process=subprocess.Popen(command,cwd=directory,stdin=subprocess.DEVNULL,stdout=log,stderr=subprocess.STDOUT,creationflags=flags)
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
    login=request('/hkrpg_cn/mdk/shield/api/login',dict(account='cn-46-authored-event',password='local-test'));assert login['retcode']==0
    account=login['data']['account'];combo=request('/hkrpg_cn/combo/granter/login/v2/login',dict(data=json.dumps(dict(uid=account['uid'],token=account['token']))));assert combo['retcode']==0
    credentials=dict(accountUid=account['uid'],comboToken=combo['data']['combo_token'])
    first=probe('event-first.log',credentials);snapshots.append(first);credentials.update(resume=True,expectedUnique=first['unique'])
   restored=probe(f'event-resume-{iteration}.log',credentials);assert restored['savedEventLeaseRestored'] and restored['unique']==snapshots[0]['unique'] and restored['uid']==snapshots[0]['uid'];snapshots.append(restored)
   time.sleep(2)
  finally:
   process.terminate()
   try:process.wait(timeout=15)
   except subprocess.TimeoutExpired:process.kill();process.wait(timeout=5)
assert hashes=={name:hashlib.sha256((source/name).read_bytes()).hexdigest() for name in names}
print(json.dumps(dict(passed=True,checks=['native-source-event-npc','native-original-options','native-repeat-query-stable','native-unsupported-effect-rejected','native-foreign-option-rejected','event-wallet-unchanged','event-lease-reconnect','event-lease-h2-restart'],directory=str(directory),snapshots=snapshots,sourceSha256=hashes,fixture=dict(firstRoomOnly=True,originalFirstRoomPool=original_pool,room=305,sourceRoomAndGroupUnmodified=True,initialWallet=300,originalInitialWallet=original_coins),sourceResourcesModified=False,completeEventEffects=False,clientGameplayVerified=False)))
