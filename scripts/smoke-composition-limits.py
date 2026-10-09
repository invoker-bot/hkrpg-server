"""原始周/月配方实际限额、扣除、产出、通知、重连与正常H2重启；初始库存和等级明确隔离。"""
from pathlib import Path
import json,hashlib,os,subprocess,socket,tempfile,time,urllib.request,urllib.error
ROOT=Path(__file__).resolve().parents[1]; source=ROOT/'resources/4.6'; artifact=ROOT/'build/libs/hkrpg-server.jar'
java=ROOT/'.tools/jdk-21.0.12.1+1/bin/java.exe'
flags=subprocess.CREATE_NO_WINDOW if os.name=='nt' else 0
directory=Path(tempfile.mkdtemp(prefix='composition-limits-',dir=ROOT/'build/smoke'))
names=['ExcelOutput/ItemConfig.json','ExcelOutput/ItemComposeConfig.json']
hashes={name:hashlib.sha256((source/name).read_bytes()).hexdigest() for name in names}
fixture=source
def free(kind=socket.SOCK_STREAM):
 with socket.socket(type=kind) as s:s.bind(('127.0.0.1',0));return s.getsockname()[1]
http,db,game=free(),free(),free(socket.SOCK_DGRAM)
(directory/'config.json').write_text(json.dumps(dict(serverOptions=dict(startTrailblazerLevel=40,welcomeMail=dict(title='Composition quota stock fixture',sender='Isolated test',content='Local source-recipe stock fixture',attachments=[dict(id=235,count=2400),dict(id=110402,count=42)])),protocol=dict(wireVersion='4.6.0'),resourceDir=str(fixture),httpServer=dict(bindAddress='127.0.0.1',bindPort=http),gameServer=dict(bindAddress='127.0.0.1',bindPort=game),internalMongoServer=dict(address='127.0.0.1',port=db,filePath='./data/database.mv'))),'utf-8')
command=[str(java),'-Xmx1536m','-Djdk.net.unixdomain.tmpdir='+str(directory/'tcp-fallback'),'-jar',str(artifact),'--no-console','--no-handbook']
def request(route,body=None):
 req=urllib.request.Request('http://127.0.0.1:'+str(http)+route,data=None if body is None else json.dumps(body).encode(),headers={'Content-Type':'application/json'})
 with urllib.request.urlopen(req,timeout=5) as response:return json.load(response)
def probe(name,credentials):
 r=subprocess.run([str(java),command[2],'-cp',str(artifact),'org.hkrpg.server.protocol.LoopbackCompositionLimitProbe',str(game)],input=json.dumps(credentials),cwd=directory,capture_output=True,text=True,encoding='utf-8',errors='replace',timeout=35,creationflags=flags)
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
    login=request('/hkrpg_cn/mdk/shield/api/login',dict(account='cn-46-composition-limits',password='local-test'));assert login['retcode']==0
    account=login['data']['account'];combo=request('/hkrpg_cn/combo/granter/login/v2/login',dict(data=json.dumps(dict(uid=account['uid'],token=account['token']))));assert combo['retcode']==0
    credentials=dict(accountUid=account['uid'],comboToken=combo['data']['combo_token'])
    first=probe('quota-first.log',credentials);snapshots.append(first);credentials.update(resume=True)
   restored=probe(f'quota-resume-{iteration}.log',credentials);assert restored['savedQuotaAndInventoryRestored'] and restored['nativeSavedQuotaSnapshotRestored'] and restored['nativeUsedMonthly']==2 and restored['nativeUsedWeekly']==20 and restored['monthlyInput']==800 and restored['monthlyOutput']==2 and restored['weeklyInput']==2 and restored['weeklyOutput']==20 and restored['nativeSavedMonthlyExcessRejected'] and restored['nativeSavedWeeklyExcessRejected'] and restored['uid']==snapshots[0]['uid'];snapshots.append(restored)
   time.sleep(2)
  finally:
   process.terminate()
   try:process.wait(timeout=15)
   except subprocess.TimeoutExpired:process.kill();process.wait(timeout=5)
assert hashes=={name:hashlib.sha256((source/name).read_bytes()).hexdigest() for name in names}
assert all(snapshots[0][k] for k in ['nativeActualSourceStock','nativeThirtyInitialQuotaIds','nativeMonthlyFirstSourceDebit','nativeMonthlySecondSourceDebit','nativeMonthlyExcessRejectedWithoutDebit','nativeWeeklyFirstSourceDebit','nativeWeeklyBatchSourceDebit','nativeWeeklyExcessRejectedWithoutDebit','nativeUnknownConversionRejectedWithoutDebit'])
print(json.dumps(dict(passed=True,checks=['source-actual-stock','native-thirty-source-quota-ids','monthly-first-source-debit','monthly-second-source-debit','monthly-excess-no-debit','weekly-first-source-debit','weekly-batch-source-debit','weekly-excess-no-debit','unknown-conversion-no-debit','native-used-count-updates','reconnect-spent-quotas-restored','h2-restart-spent-quotas-restored','saved-monthly-excess-rejected','saved-weekly-excess-rejected'],directory=str(directory),snapshots=snapshots,sourceSha256=hashes,fixture=dict(initialStockViaIsolatedWelcomeMail=True,material235=2400,material110402=42,isolatedStartTrailblazerLevel=40,worldLevel=3,sourceRecipesAndItemsUnmodified=True,officialStoryWorldUnlockVerified=False),sourceResourcesModified=False,clientGameplayVerified=False,networkPeriodRolloverVerified=False,crashAtomicityVerified=False)))
