"""原始配方书实际学习/扣除/合成、重连与正常H2重启；邮件初始库存明确隔离。"""
from pathlib import Path
import json,hashlib,os,subprocess,socket,tempfile,time,urllib.request,urllib.error
ROOT=Path(__file__).resolve().parents[1]; source=ROOT/'resources/4.6'; artifact=ROOT/'build/libs/hkrpg-server.jar'
java=ROOT/'.tools/jdk-21.0.12.1+1/bin/java.exe'
flags=subprocess.CREATE_NO_WINDOW if os.name=='nt' else 0
directory=Path(tempfile.mkdtemp(prefix='formula-learning-',dir=ROOT/'build/smoke'))
names=['ExcelOutput/ItemConfig.json','ExcelOutput/ItemComposeConfig.json','ExcelOutput/ItemUseData.json','ExcelOutput/ItemUseBuffData.json']
hashes={name:hashlib.sha256((source/name).read_bytes()).hexdigest() for name in names}
fixture=source
def free(kind=socket.SOCK_STREAM):
 with socket.socket(type=kind) as s:s.bind(('127.0.0.1',0));return s.getsockname()[1]
http,db,game=free(),free(),free(socket.SOCK_DGRAM)
(directory/'config.json').write_text(json.dumps(dict(serverOptions=dict(welcomeMail=dict(title='Formula stock fixture',sender='Isolated test',content='Local source-recipe stock fixture',attachments=[dict(id=404005,count=2),dict(id=408001,count=1),dict(id=180002,count=1)])),protocol=dict(wireVersion='4.6.0'),resourceDir=str(fixture),httpServer=dict(bindAddress='127.0.0.1',bindPort=http),gameServer=dict(bindAddress='127.0.0.1',bindPort=game),internalMongoServer=dict(address='127.0.0.1',port=db,filePath='./data/database.mv'))),'utf-8')
command=[str(java),'-Xmx1536m','-Djdk.net.unixdomain.tmpdir='+str(directory/'tcp-fallback'),'-jar',str(artifact),'--no-console','--no-handbook']
def request(route,body=None):
 req=urllib.request.Request('http://127.0.0.1:'+str(http)+route,data=None if body is None else json.dumps(body).encode(),headers={'Content-Type':'application/json'})
 with urllib.request.urlopen(req,timeout=5) as response:return json.load(response)
def probe(name,credentials):
 r=subprocess.run([str(java),command[2],'-cp',str(artifact),'org.hkrpg.server.protocol.LoopbackFormulaLearningProbe',str(game)],input=json.dumps(credentials),cwd=directory,capture_output=True,text=True,encoding='utf-8',errors='replace',timeout=35,creationflags=flags)
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
    login=request('/hkrpg_cn/mdk/shield/api/login',dict(account='cn-46-formula-learning',password='local-test'));assert login['retcode']==0
    account=login['data']['account'];combo=request('/hkrpg_cn/combo/granter/login/v2/login',dict(data=json.dumps(dict(uid=account['uid'],token=account['token']))));assert combo['retcode']==0
    credentials=dict(accountUid=account['uid'],comboToken=combo['data']['combo_token'])
    first=probe('formula-first.log',credentials);snapshots.append(first);credentials.update(resume=True)
   restored=probe(f'formula-resume-{iteration}.log',credentials);assert restored['savedFormulaAndInventoryRestored'] and restored['learnedFormulas']==[404005] and restored['remainingBooks']==1 and restored['remainingIngredients']==0 and restored['food']==1 and restored['nativeSavedDuplicateLearningRejected'] and restored['nativeSavedMissingIngredientsRejected'] and restored['uid']==snapshots[0]['uid'];snapshots.append(restored)
   time.sleep(2)
  finally:
   process.terminate()
   try:process.wait(timeout=15)
   except subprocess.TimeoutExpired:process.kill();process.wait(timeout=5)
assert hashes=={name:hashlib.sha256((source/name).read_bytes()).hexdigest() for name in names}
assert all(snapshots[0][k] for k in ['nativeActualBookAndMaterialStock','nativeLockedRecipeCannotDebit','nativeBulkBookUseRejected','nativeLearnedFormulaField13','nativeDuplicateLearningCannotDebit','nativeActualFormulaField15Confirmation','nativeActualRecipeOutputAndDebit'])
print(json.dumps(dict(passed=True,checks=['source-book-and-material-stock','locked-recipe-no-debit','bulk-book-use-rejected','actual-book-debit-and-learning','native-formula-field15-confirmation','native-bag-field13-learning-list','duplicate-learning-no-debit','actual-recipe-output-and-material-debit','reconnect-learned-formula-restored','h2-restart-learned-formula-restored','saved-duplicate-learning-rejected','saved-missing-materials-rejected'],directory=str(directory),snapshots=snapshots,sourceSha256=hashes,fixture=dict(initialStockViaIsolatedWelcomeMail=True,books=2,ingredient408001=1,ingredient180002=1,sourceRecipesAndItemsUnmodified=True,officialStoryUnlockVerified=False),sourceResourcesModified=False,clientGameplayVerified=False,crashAtomicityVerified=False)))
