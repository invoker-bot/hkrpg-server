"""隔离 GM/KCP/H2：历史目录、独立签到周期、两个原始材料活动和同 UID 重启。"""
import argparse
import base64
import hashlib
import http.cookiejar
import importlib.util
import json
import os
from pathlib import Path
import socket
import subprocess
import tempfile
import urllib.request
import uuid
from collections import Counter

ROOT=Path(__file__).resolve().parents[1]
spec=importlib.util.spec_from_file_location('gm',ROOT/'scripts/smoke-gm-console.py')
gm=importlib.util.module_from_spec(spec);spec.loader.exec_module(gm)


def uint(field,value):
    result=bytearray()
    for integer in (field*8,value):
        while integer>127:result.append((integer&127)|128);integer>>=7
        result.append(integer)
    return bytes(result)


def main():
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--browser-pause',action='store_true');args=parser.parse_args()
    jar=ROOT/'build/libs/hkrpg-server.jar';digest=hashlib.sha256(jar.read_bytes()).hexdigest();java=next((ROOT/'.tools').glob('jdk-21*/bin/java.exe'))
    runs=ROOT/'build/smoke';runs.mkdir(exist_ok=True);directory=Path(tempfile.mkdtemp(prefix='gm-activity-history-',dir=runs));helpers=directory/'helpers';helpers.mkdir()
    flags=subprocess.CREATE_NO_WINDOW if os.name=='nt' else 0
    subprocess.run([str(java.with_name('javac.exe')),'-encoding','UTF-8','-cp',str(jar),'-d',str(helpers),str(ROOT/'scripts/fixtures/java/org/hkrpg/server/protocol/NativeGmPlayerProbe.java'),
                    str(ROOT/'scripts/fixtures/java/emu/lunarcore/command/commands/ActivityReplayFixtureCommand.java')],check=True,capture_output=True,creationflags=flags)
    (directory/'activity-replay-owned-fixture').touch()
    port,mongo,game=gm.free_port(),gm.free_port(),gm.free_port(socket.SOCK_DGRAM)
    (directory/'config.json').write_text(json.dumps({'protocol':{'wireVersion':'4.6.0'},'resourceDir':str(ROOT/'resources/4.6'),
        'httpServer':{'bindAddress':'127.0.0.1','bindPort':port},'gameServer':{'bindAddress':'127.0.0.1','bindPort':game},
        'internalMongoServer':{'address':'127.0.0.1','port':mongo,'filePath':'./data/database.mv'}}),encoding='utf8')
    base=f'http://127.0.0.1:{port}';csrf='';uid=None;server=probe=None;logs=[];normal_stops=0;native=[];snapshots=[]
    opener=urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
    account_name='history_'+uuid.uuid4().hex+'@example.invalid'
    jflags=['-Dfile.encoding=UTF-8','-Dio.netty.eventLoopThreads=2','-Djdk.net.unixdomain.tmpdir='+str(directory/'tcp-fallback')]
    def request(path,body=None):
        req=urllib.request.Request(base+path,data=None if body is None else json.dumps(body).encode(),headers={'Content-Type':'application/json','Origin':base,'X-GM-CSRF':csrf})
        with opener.open(req,timeout=30) as response:return json.load(response)
    def snapshot():return json.loads((directory/'snapshot.json').read_text('utf8'))
    def send(name,wire=b'',success=True):
        rid=str(uuid.uuid4());path=directory/'equipment-request.json';temp=path.with_suffix('.tmp')
        temp.write_text(json.dumps({'requestId':rid,'command':name,'wire':base64.b64encode(wire).decode(),'success':success}),encoding='utf8');temp.replace(path)
        value=gm.wait_for(lambda:(lambda s:s if s.get('equipmentReply',{}).get('requestId')==rid else None)(snapshot()),40)
        path.unlink();reply=value['equipmentReply']['response'];assert (reply.get('retcode',0)==0)==success,reply
        native.append({'command':name,'success':success,'response':reply});return reply
    def operate(action,**fields):
        body={'requestId':str(uuid.uuid4()),'action':action,**fields};result=request('/gm/api/operate',body)['data']
        assert result['state'] in ('completed','returned'),result
        return body
    def stop_probe():
        nonlocal probe
        if probe:
            (directory/'quit').touch();probe.wait(timeout=40);assert probe.returncode==0;probe=None
    def login():
        nonlocal probe,uid
        account=request('/hkrpg_cn/mdk/shield/api/login',{'account':account_name,'password':'isolated-history'})['data']['account']
        combo=request('/hkrpg_cn/combo/granter/login/v2/login',{'data':json.dumps({'uid':account['uid'],'token':account['token']})})
        credentials=directory/'credentials.json';credentials.write_text(json.dumps({'accountUid':account['uid'],'comboToken':combo['data']['combo_token']}),encoding='utf8')
        for name in ['quit','snapshot.json']:(directory/name).unlink(missing_ok=True)
        log=(directory/f'probe-{len(logs)}.log').open('w',encoding='utf8');logs.append(log)
        probe=subprocess.Popen([str(java),*jflags,'-cp',str(helpers)+os.pathsep+str(jar),'org.hkrpg.server.protocol.NativeGmPlayerProbe',str(game),str(credentials),str(directory)],cwd=directory,stdout=log,stderr=subprocess.STDOUT,creationflags=flags)
        initial=gm.wait_for(snapshot)
        if uid is None:uid=initial['uid']
        assert uid==initial['uid'];return initial
    def stop(strict=True):
        nonlocal server,normal_stops,probe
        try:stop_probe()
        except Exception:
            if probe:probe.terminate();probe.wait(timeout=15);probe=None
            if strict:raise
        if server:
            if server.poll() is None:
                server.stdin.write(b'stop\n');server.stdin.flush()
                try:server.wait(timeout=45)
                except subprocess.TimeoutExpired:server.terminate();server.wait(timeout=15)
            if strict:assert server.returncode==(1000 if os.name=='nt' else 232),server.returncode;normal_stops+=1
            server.stdin.close();server=None
    sources=json.loads((ROOT/'resources/4.6/ExcelOutput/MaterialSubmitter.json').read_text('utf8'))
    rewards={r['RewardID']:r for r in json.loads((ROOT/'resources/4.6/ExcelOutput/RewardData.json').read_text('utf8'))}
    wallet_checks=0
    def amount(state,item):
        if item in (1,2):return state['basic'].get({1:'hcoin',2:'scoin'}[item],0)
        return next((r['num'] for r in state['bag'].get('material_list',[]) if r['tid']==item),0)
    def complete_material(first=301):
        nonlocal wallet_checks
        response=send('GetMaterialSubmitActivityDataCsReq')
        rows=[r for r in response.get('JJNFLKKAOFI',[]) if first<=r['LNOCOOCMNCI']<first+7]
        assert not rows,response
        before=snapshot();expected=Counter()
        for row in sources:
            if not first<=row['ID']<first+7:continue
            for cost in row['MaterialList']:expected[cost['ItemID']]-=cost['ItemNum']
            reward=rewards[row['RewardID']];expected[1]+=reward.get('Hcoin',0)
            for slot in range(1,6):
                if 'ItemID_'+str(slot) in reward:expected[reward['ItemID_'+str(slot)]]+=reward['Count_'+str(slot)]
        for item in range(first,first+7):
            send('SubmitMaterialSubmitActivityMaterialCsReq',uint(8,item));send('TakeMaterialSubmitActivityRewardCsReq',uint(7,item))
        send('TakeMaterialSubmitActivityRewardCsReq',uint(7,first),False)
        response=send('GetMaterialSubmitActivityDataCsReq')
        rows=[r for r in response['JJNFLKKAOFI'] if first<=r['LNOCOOCMNCI']<first+7]
        assert len(rows)==7 and all(r['is_taken_reward'] for r in rows),response
        after=snapshot()
        for item,delta in expected.items():assert amount(after,item)-amount(before,item)==delta,(item,delta,before,after)
        wallet_checks+=len(expected)
    first=second=None;history=[];saved=None
    try:
        for cycle in range(2):
            log=(directory/f'server-{cycle}.log').open('w',encoding='utf8');logs.append(log)
            server=subprocess.Popen([str(java),*jflags,'-Xmx1536m','-cp',str(helpers)+os.pathsep+str(jar),'org.hkrpg.server.HkrpgServer','--no-handbook'],cwd=directory,stdin=subprocess.PIPE,stdout=log,stderr=subprocess.STDOUT,creationflags=flags)
            gm.wait_for(lambda:server.poll() is None and request('/health/ready')['ready']);csrf=request('/gm/api/session',{})['data']['csrf']
            if cycle==0:
                total=request('/gm/api/activities/history?limit=100')['data']['total']
                for offset in range(0,total,100):history.extend(request(f'/gm/api/activities/history?limit=100&offset={offset}')['data']['items'])
                assert len(history)==total and len({e['version'] for e in history})==31
                gift=next(e for e in history if e['version']=='1.1' and e['activityId']==10018)
                material=next(e for e in history if e['activityId']==30014)
                belobog=next(e for e in history if e['activityId']==30011)
                first=operate('activity-rerun',activityKey=gift['key'],durationDays=30)
            initial=login()
            if cycle==0:
                operate('command',uid=uid,command='activityreplayfixture');stop_probe();login()
                response=send('GetLoginActivityCsReq');row=next(x for x in response['login_activity_list'] if x['panel_id']==10018);assert row['login_days']==1,row;login_id=row['id']
                send('TakeLoginActivityRewardCsReq',uint(8,login_id)+uint(6,1));send('TakeLoginActivityRewardCsReq',uint(8,login_id)+uint(6,1),False)
                response=send('GetLoginActivityCsReq');assert next(x for x in response['login_activity_list'] if x['panel_id']==10018)['MOIAHCMHNEO']==[1]
                for item in sorted({v['ItemID'] for r in sources if 201<=r['ID']<=307 for v in r['MaterialList']}):operate('grant',uid=uid,itemId=item,amount=100)
                operate('activity-open',activityId=30014,durationDays=30);complete_material()
                for edition in range(2):
                    body=operate('activity-rerun',activityKey=material['key'],durationDays=30)
                    send('SubmitMaterialSubmitActivityMaterialCsReq',uint(8,301),False)
                    complete_material()
                for edition in range(2):
                    operate('activity-rerun',activityKey=belobog['key'],durationDays=30)
                    send('SubmitMaterialSubmitActivityMaterialCsReq',uint(8,201),False)
                    complete_material(201)
                second=operate('activity-rerun',activityKey=gift['key'],durationDays=30)
                send('TakeLoginActivityRewardCsReq',uint(8,login_id)+uint(6,1),False)
                assert request('/gm/api/operate',first)['data']['state']=='completed'
                file=json.loads((directory/'data/Gameplay46.json').read_text('utf8'))
                assert next(r for r in file['activities'] if r['panelId']==10018)['replayId']==second['requestId']
                if args.browser_pause:
                    ready={'base':base,'runtimeDirectory':str(directory),'uid':uid,'jarSHA256':digest,'giftKey':gift['key'],'materialKey':material['key']}
                    (ROOT/'build/gm-history-browser-ready.json').write_text(json.dumps(ready,indent=2),encoding='utf8');print(json.dumps({'browserReady':True,**ready}),flush=True)
                    gm.wait_for(lambda:(directory/'continue').exists(),2400)
                saved=snapshot();snapshots.append(saved)
            else:
                assert initial['bag']==saved['bag'] and initial['avatars']==saved['avatars']
                response=send('GetLoginActivityCsReq');row=next(x for x in response['login_activity_list'] if x['panel_id']==10018);assert row['login_days']==1,row
                send('TakeLoginActivityRewardCsReq',uint(8,row['id'])+uint(6,1));send('TakeLoginActivityRewardCsReq',uint(8,row['id'])+uint(6,1),False)
                response=send('GetMaterialSubmitActivityDataCsReq');assert len(response['JJNFLKKAOFI'])==14 and all(r['is_taken_reward'] for r in response['JJNFLKKAOFI'])
                send('TakeMaterialSubmitActivityRewardCsReq',uint(7,301),False);send('TakeMaterialSubmitActivityRewardCsReq',uint(7,201),False);snapshots.append(snapshot())
            stop()
        assert hashlib.sha256(jar.read_bytes()).hexdigest()==digest
        report={'passed':True,'jarSHA256':digest,'runtimeDirectory':str(directory),'events':len(history),'versions':31,
                'normalStops':normal_stops,'sameUidRestart':True,'nativeRequests':len(native),'materialCyclesCompleted':5,'materialOrdersCompleted':35,'materialWalletChecks':wallet_checks,
                'isolatedPrerequisites':['level 21','main mission 1000510 marked finished; story progression is not under test'],
                'separateLoginCyclesClaimed':2,'productionModified':False,'clientAccepted':False,'native':native,'snapshots':snapshots}
        (ROOT/'build/gm-activity-history-native.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf8')
        print(json.dumps({k:v for k,v in report.items() if k not in ('native','snapshots')}))
    finally:
        stop(False)
        for log in logs:log.close()
        (directory/'credentials.json').unlink(missing_ok=True)


if __name__=='__main__':main()
