"""原始自选遗器的真实材料、费用、主副词条及正常H2重启；成本列表与UI仍未验收。"""
from pathlib import Path
import json,hashlib,os,subprocess,socket,tempfile,time,urllib.request,urllib.error
ROOT=Path(__file__).resolve().parents[1]; source=ROOT/'resources/4.6'; artifact=ROOT/'build/libs/hkrpg-server.jar'
java=ROOT/'.tools/jdk-21.0.12.1+1/bin/java.exe'
flags=subprocess.CREATE_NO_WINDOW if os.name=='nt' else 0
directory=Path(tempfile.mkdtemp(prefix='selected-relic-',dir=ROOT/'build/smoke'))
names=['ExcelOutput/'+n+'.json' for n in ['ItemConfig','ItemComposeConfig','ItemConfigRelic','RelicConfig','RelicMainAffixConfig','RelicSubAffixConfig']]
hashes={name:hashlib.sha256((source/name).read_bytes()).hexdigest() for name in names}
fixture=source
def free(kind=socket.SOCK_STREAM):
 with socket.socket(type=kind) as s:s.bind(('127.0.0.1',0));return s.getsockname()[1]
http,db,game=free(),free(),free(socket.SOCK_DGRAM)
(directory/'config.json').write_text(json.dumps(dict(serverOptions=dict(startTrailblazerLevel=40,welcomeMail=dict(title='Selected relic stock fixture',sender='Isolated test',content='Local source-relic stock fixture',attachments=[dict(id=235,count=300),dict(id=236,count=3),dict(id=237,count=5),dict(id=2,count=5000)])),protocol=dict(wireVersion='4.6.0'),resourceDir=str(fixture),httpServer=dict(bindAddress='127.0.0.1',bindPort=http),gameServer=dict(bindAddress='127.0.0.1',bindPort=game),internalMongoServer=dict(address='127.0.0.1',port=db,filePath='./data/database.mv'))),'utf-8')
command=[str(java),'-Xmx1536m','-Djdk.net.unixdomain.tmpdir='+str(directory/'tcp-fallback'),'-jar',str(artifact),'--no-console','--no-handbook']
def request(route,body=None):
 req=urllib.request.Request('http://127.0.0.1:'+str(http)+route,data=None if body is None else json.dumps(body).encode(),headers={'Content-Type':'application/json'})
 with urllib.request.urlopen(req,timeout=5) as response:return json.load(response)
def probe(name,credentials):
 r=subprocess.run([str(java),command[2],'-cp',str(artifact),'org.hkrpg.server.protocol.LoopbackSelectedRelicProbe',str(game)],input=json.dumps(credentials),cwd=directory,capture_output=True,text=True,encoding='utf-8',errors='replace',timeout=35,creationflags=flags)
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
    login=request('/hkrpg_cn/mdk/shield/api/login',dict(account='cn-46-selected-relic',password='local-test'));assert login['retcode']==0
    account=login['data']['account'];combo=request('/hkrpg_cn/combo/granter/login/v2/login',dict(data=json.dumps(dict(uid=account['uid'],token=account['token']))));assert combo['retcode']==0
    credentials=dict(accountUid=account['uid'],comboToken=combo['data']['combo_token'])
    first=probe('relic-first.log',credentials);snapshots.append(first);credentials.update(resume=True,expectedScoin=first['scoin'])
   restored=probe(f'relic-resume-{iteration}.log',credentials);assert restored['savedRelicsAndMaterialsRestored'] and restored['relicRolls']==first['relicRolls'] and restored['remaining235']==0 and restored['remaining236']==1 and restored['remaining237']==0 and restored['scoin']==first['scoin'] and restored['nativeSavedMissingMaterialRejected'] and restored['uid']==first['uid'];snapshots.append(restored)
   time.sleep(2)
  finally:
   process.terminate()
   try:process.wait(timeout=15)
   except subprocess.TimeoutExpired:process.kill();process.wait(timeout=5)
assert hashes=={name:hashlib.sha256((source/name).read_bytes()).hexdigest() for name in names}
assert all(snapshots[0][k] for k in ['nativeActualMaterialStock','nativeInvalidMainNoDebit','nativeDuplicateSubsNoDebit','nativeUnverifiedCostsNoDebit','nativeTwoSubsAndActualDebit','nativeOneSubAndActualDebit','nativeRandomMainAndActualDebit','nativeMissingMaterialNoDebit'])
assert len(first['relicRolls'])==3 and first['remaining235']==0 and first['remaining236']==1 and first['remaining237']==0
print(json.dumps(dict(passed=True,checks=['original-stock-claimed','invalid-main-no-debit','duplicate-subs-no-debit','unverified-cost-list-no-debit','actual-two-sub-relic-and-debit','actual-one-sub-relic-and-debit','actual-random-main-and-debit','missing-material-no-debit','three-actual-owned-unique-relics','actual-three-thousand-coin-debit','reconnect-identical-affix-rolls','h2-restart-identical-affix-rolls','saved-missing-material-rejected'],directory=str(directory),snapshots=snapshots,sourceSha256=hashes,fixture=dict(initialStockViaIsolatedWelcomeMail=True,startTrailblazerLevel=40,initial235=300,initial236=3,initial237=5,extraCredits=5000,sourceRecipesAndRelicsUnmodified=True,officialStoryUnlockVerified=False),sourceResourcesModified=False,clientGameplayVerified=False,clientRequestCostListsVerified=False,officialRandomWeightsVerified=False,crashAtomicityVerified=False)))
