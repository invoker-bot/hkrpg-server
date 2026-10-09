"""原始小时委托、实际日常/背包、同UID重连与H2重启；前置与两小时经过由隔离夹具设置。"""
from pathlib import Path
import hashlib,json,os,socket,subprocess,tempfile,time,urllib.error,urllib.request

ROOT=Path(__file__).resolve().parents[1]
def free_port(kind=socket.SOCK_STREAM):
    with socket.socket(type=kind) as sock:sock.bind(('127.0.0.1',0));return sock.getsockname()[1]
def main():
    source=ROOT/'resources/4.6';artifact=ROOT/'build/libs/hkrpg-server.jar'
    java=next((ROOT/'.tools').glob('jdk-21*/bin/java.exe'),None);assert java and artifact.is_file()
    classes=ROOT/'build/classes/java/test';assert (classes/'org/hkrpg/server/gameplay/ExpeditionFixtureEditor.class').is_file()
    digest=hashlib.sha256(artifact.read_bytes()).hexdigest();runs=ROOT/'build/smoke';runs.mkdir(exist_ok=True)
    directory=Path(tempfile.mkdtemp(prefix='expedition-',dir=runs));assert directory.resolve().is_relative_to(runs.resolve())
    fixture=directory/'resources-fixture';excel=fixture/'ExcelOutput';excel.mkdir(parents=True)
    guarded=['resource-manifest.json']+['ExcelOutput/'+n+'.json' for n in ['DailyActiveQuestPool','QuestData','FinishWay','RewardData','ItemConfig','ExpeditionHarvestData','ExpeditionTeam','ConstValueCommon']]
    hashes={name:hashlib.sha256((source/name).read_bytes()).hexdigest() for name in guarded}
    for origin in (source/'ExcelOutput').iterdir():
        target=excel/origin.name
        if origin.name=='DailyActiveQuestPool.json':
            rows=[r for r in json.loads(origin.read_text('utf-8')) if r['QuestID'] in {2100002,2100133}]
            assert rows==[{'QuestID':2100002,'Type':3},{'QuestID':2100133,'Type':7}]
            target.write_text(json.dumps(rows),'utf-8')
        else:os.link(origin,target)
    flags=subprocess.CREATE_NO_WINDOW if os.name=='nt' else 0
    config=fixture/'Config'
    if os.name=='nt':
        plan=directory/'junction-plan.json';plan.write_text(json.dumps({'target':str(config),'source':str(source/'Config')}),'utf-8')
        helper=directory/'prepare-fixture.ps1';helper.write_text("param([string]$Plan)\n$ErrorActionPreference='Stop'\n$binding=Get-Content -LiteralPath $Plan -Raw | ConvertFrom-Json\nNew-Item -ItemType Junction -Path $binding.target -Value $binding.source | Out-Null\n",'utf-8')
        subprocess.run(['powershell','-NoProfile','-NonInteractive','-File',str(helper),'-Plan',str(plan)],check=True,capture_output=True,creationflags=flags)
    else:config.symlink_to(source/'Config',target_is_directory=True)
    (fixture/'resource-manifest.json').write_text(json.dumps({'clientVersion':'4.6.0','testFixture':True,'expeditionFixture':True,'dailyPoolIsolated':True,'originalHarvestAndRewardRowsUnmodified':True}),'utf-8')
    http_port,mongo_port,game_port=free_port(),free_port(),free_port(socket.SOCK_DGRAM)
    (directory/'config.json').write_text(json.dumps({'protocol':{'wireVersion':'4.6.0'},'resourceDir':str(fixture),
        'httpServer':{'bindAddress':'127.0.0.1','bindPort':http_port},'gameServer':{'bindAddress':'127.0.0.1','bindPort':game_port},
        'internalMongoServer':{'address':'127.0.0.1','port':mongo_port,'filePath':'./data/database.mv'}}),'utf-8')
    java_option='-Djdk.net.unixdomain.tmpdir='+str(directory/'tcp-fallback')
    command=[str(java),'-Xmx1536m',java_option,'-jar',str(artifact),'--no-handbook']
    base=f'http://127.0.0.1:{http_port}';credentials=None;snapshots=[];edits=[]
    def request(route,body=None):
        req=urllib.request.Request(base+route,data=None if body is None else json.dumps(body).encode(),headers={'Content-Type':'application/json'})
        with urllib.request.urlopen(req,timeout=5) as response:return json.load(response)
    def probe(mode):
        assert hashlib.sha256(artifact.read_bytes()).hexdigest()==digest
        data=dict(credentials,mode=mode)
        result=subprocess.run([str(java),java_option,'-cp',str(artifact),'org.hkrpg.server.protocol.LoopbackExpeditionProbe',str(game_port)],
            input=json.dumps(data),text=True,encoding='utf-8',capture_output=True,timeout=45,creationflags=flags)
        (directory/f'probe-{len(snapshots)}-{mode}.log').write_text(result.stdout+result.stderr,'utf-8');assert result.returncode==0,directory
        value=json.loads(next(r for r in reversed(result.stdout.splitlines()) if r.startswith('{')));assert value['passed'];snapshots.append(value);return value
    def editor(mode):
        result=subprocess.run([str(java),java_option,'-cp',os.pathsep.join([str(classes),str(artifact)]),'org.hkrpg.server.gameplay.ExpeditionFixtureEditor',str(directory),str(snapshots[0]['uid']),mode],cwd=ROOT,text=True,encoding='utf-8',capture_output=True,timeout=45,creationflags=flags)
        (directory/f'editor-{len(edits)}-{mode}.log').write_text(result.stdout+result.stderr,'utf-8');assert result.returncode==0,directory
        value=json.loads(next(r for r in reversed(result.stdout.splitlines()) if r.startswith('{')));assert value['passed'];edits.append(value);return value
    for iteration,mode in enumerate(['bootstrap','dispatch','harvest','resume']):
        with (directory/f'server-{iteration}.log').open('w',encoding='utf-8') as log:
            process=subprocess.Popen(command,cwd=directory,stdin=subprocess.PIPE,stdout=log,stderr=subprocess.STDOUT,creationflags=flags)
            try:
                deadline=time.monotonic()+90
                while True:
                    assert process.poll() is None,directory
                    try:
                        if request('/health/ready')['ready']:break
                    except (OSError,urllib.error.URLError):pass
                    if time.monotonic()>deadline:raise RuntimeError(f'Expedition readiness timeout: {directory}')
                    time.sleep(.2)
                if iteration==0:
                    login=request('/hkrpg_cn/mdk/shield/api/login',{'account':'cn-46-expedition','password':'local-test'});assert login['retcode']==0;account=login['data']['account']
                    combo=request('/hkrpg_cn/combo/granter/login/v2/login',{'data':json.dumps({'uid':account['uid'],'token':account['token']})});assert combo['retcode']==0
                    credentials={'accountUid':account['uid'],'comboToken':combo['data']['combo_token']}
                value=probe(mode)
                if mode=='harvest':credentials['expectedTime']=value['rewardTime'];probe('resume')
                time.sleep(2)
            finally:
                if process.poll() is None:
                    try:
                        process.stdin.write(b'stop\n');process.stdin.flush()
                        process.wait(timeout=30)
                        assert process.returncode==(1000 if os.name=='nt' else 232),directory
                    except BaseException:
                        if process.poll() is None:process.terminate();process.wait(timeout=15)
                        raise
                    finally:process.stdin.close()
        if iteration==0:editor('seed')
        elif iteration==1:
            before_age=editor('inspect')
            assert before_age['restoredMainMissionGate'] and before_age['gameplay']['expeditions']['assignments'],directory
            aged=editor('age');credentials['expectedTime']=aged['gameplay']['expeditions']['rewardTime']
            verified=editor('inspect')
            assert verified['restoredMainMissionGate'] and verified['gameplay']['expeditions']==aged['gameplay']['expeditions'],directory
    final=editor('inspect')
    assert snapshots[0]['nativeMissionGatePreserved'] and snapshots[0]['queryDoesNotCompleteDaily']
    assert all(snapshots[1][key] for key in ['nativeMalformedSetRejected','nativeActualDispatch','nativeBothOriginalDispatchDailiesFinished','dispatchDoesNotAutomaticallyGrantPoints','firstOriginalHundredPoints','secondOriginalHundredPoints','duplicateDailyClaimRejected','nativeRepeatedSetNoResetOrRewards'])
    assert snapshots[2]['nativeTwoHourOriginalHarvest'] and snapshots[2]['nativeDuplicateHarvestRejected']
    for value in snapshots:
        assert value['uid']==snapshots[0]['uid'] and value['teamCount']==2
    for value in snapshots[2:]:
        assert value['actualHarvestInventoryVerified'] and value['savedDailyClaimsRestored'] and value['dailyPoints']==200
        assert value['materialStocks']=={'111001':4,'111011':4} and value['active']==[1001,1002]
        assert value['quest2100002Status']==value['quest2100133Status']==3
    saved=final['gameplay']['expeditions'];assert saved['lastAttempt']['completed'] and saved['sequence']==1 and saved['lastAttempt']['hours']==2
    assert saved['lastAttempt']['rewards']=={'111001':4,'111011':4}
    assert hashes=={name:hashlib.sha256((source/name).read_bytes()).hexdigest() for name in guarded}
    assert hashlib.sha256(artifact.read_bytes()).hexdigest()==digest
    checks=['source-mission-gate','query-no-daily-credit','malformed-set-rejected','actual-two-assignments','both-original-daily-objectives','no-immediate-auto-points','first-original-100','second-original-100','duplicate-daily-claim-rejected','same-set-no-clock-reset','actual-two-hour-original-payout','duplicate-harvest-rejected','actual-stored-materials','same-uid-reconnect','normal-h2-restart','saved-hourly-anchor','completed-receipt-restored']
    print(json.dumps({'passed':True,'runtimeDirectory':str(directory),'snapshots':snapshots,'checks':checks,'rootJarSHA256':digest,'sourceSHA256':hashes,
        'sourceResourcesModified':False,'fixture':{'dailyPoolIsolated':True,'finishedMissionSeeded':1010403,'elapsedHoursSeeded':2,'fixtureEditorIsTestClassOnly':True,'originalHarvestAndRewardRowsUnmodified':True},
        'finalSavedAttempt':saved['lastAttempt'],'normalStopCommandUsed':True,'offlineFixtureCheckedBeforeAndAfterAge':True,'clientGameplayVerified':False,'officialMissionChainVerified':False,'naturalTwoHourWaitVerified':False,'unlockNotify2526Verified':False,'crashAtomicityVerified':False},ensure_ascii=False))
if __name__=='__main__':main()
