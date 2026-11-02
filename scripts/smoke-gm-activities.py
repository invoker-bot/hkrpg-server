"""隔离 HTTP/KCP/H2 验证活动排期、原始门槛、关闭/恢复、重放与正常重启。"""
import argparse
import hashlib
import http.cookiejar
import importlib.util
import json
import os
from pathlib import Path
import socket
import subprocess
import tempfile
import time
import urllib.request
import uuid

ROOT=Path(__file__).resolve().parents[1]
spec=importlib.util.spec_from_file_location('gm',ROOT/'scripts/smoke-gm-console.py')
gm=importlib.util.module_from_spec(spec);spec.loader.exec_module(gm)


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--browser-pause',action='store_true')
    parser.add_argument('--output',type=Path,default=ROOT/'build/gm-activities-native.json')
    args=parser.parse_args()
    jar=ROOT/'build/libs/hkrpg-server.jar';digest=hashlib.sha256(jar.read_bytes()).hexdigest()
    java=next((ROOT/'.tools').glob('jdk-21*/bin/java.exe'))
    runs=ROOT/'build/smoke';runs.mkdir(exist_ok=True)
    directory=Path(tempfile.mkdtemp(prefix='gm-activities-',dir=runs));helpers=directory/'helpers';helpers.mkdir()
    flags=subprocess.CREATE_NO_WINDOW if os.name=='nt' else 0
    subprocess.run([str(java.with_name('javac.exe')),'-encoding','UTF-8','-cp',str(jar),'-d',str(helpers),
                    str(ROOT/'scripts/fixtures/java/org/hkrpg/server/protocol/NativeGmPlayerProbe.java')],check=True,capture_output=True,creationflags=flags)
    http_port,mongo,game=gm.free_port(),gm.free_port(),gm.free_port(socket.SOCK_DGRAM)
    (directory/'config.json').write_text(json.dumps({'protocol':{'wireVersion':'4.6.0'},'resourceDir':str(ROOT/'resources/4.6'),
        'httpServer':{'bindAddress':'127.0.0.1','bindPort':http_port},'gameServer':{'bindAddress':'127.0.0.1','bindPort':game},
        'internalMongoServer':{'address':'127.0.0.1','port':mongo,'filePath':'./data/database.mv'}}),encoding='utf-8')
    base=f'http://127.0.0.1:{http_port}';csrf='';uid=None;server=probe=None
    opener=urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
    jflags=['-Dfile.encoding=UTF-8','-Dio.netty.eventLoopThreads=2','-Djdk.net.unixdomain.tmpdir='+str(directory/'tcp-fallback')]
    account_name='activity_'+uuid.uuid4().hex+'@example.invalid';logs=[];native=[];stops=0;catalog=[];snapshots=[]

    def request(path,body=None):
        req=urllib.request.Request(base+path,data=None if body is None else json.dumps(body).encode(),
            headers={'Content-Type':'application/json','Origin':base,'X-GM-CSRF':csrf})
        with opener.open(req,timeout=30) as response:return json.load(response)

    def snapshot():return json.loads((directory/'snapshot.json').read_text('utf-8'))

    def schedules():
        rid=str(uuid.uuid4());path=directory/'equipment-request.json';temp=path.with_suffix('.tmp')
        temp.write_text(json.dumps({'requestId':rid,'command':'GetActivityScheduleConfigCsReq','wire':'','success':True}),encoding='utf-8');temp.replace(path)
        result=gm.wait_for(lambda:(lambda s:s if s.get('equipmentReply',{}).get('requestId')==rid else None)(snapshot()),40)
        path.unlink();reply=result['equipmentReply']['response'];assert reply.get('retcode',0)==0,reply
        native.append(reply);return {(x['panel_id'],x['activity_id']):(x['begin_time'],x['end_time']) for x in reply.get('schedule_data',[])}

    def operate(action,activity=50045,success=True,**fields):
        body={'requestId':str(uuid.uuid4()),'action':'activity-'+action,'activityId':activity,**fields}
        result=request('/gm/api/operate',body)['data'];assert result['state']==('completed' if success else 'failed'),result
        return body,result

    def entry(activity):return next(x for x in request('/gm/api/activities?q='+str(activity))['data']['items'] if x['id']==activity)

    def stop(strict=True):
        nonlocal server,probe,stops
        codes=[]
        if probe:
            (directory/'quit').touch()
            try:probe.wait(timeout=40)
            except subprocess.TimeoutExpired:probe.terminate();probe.wait(timeout=15)
            codes.append(probe.returncode==0);probe=None
        if server:
            if server.poll() is None:
                server.stdin.write(b'stop\n');server.stdin.flush()
                try:server.wait(timeout=45)
                except subprocess.TimeoutExpired:server.terminate();server.wait(timeout=15)
            codes.append(server.returncode==(1000 if os.name=='nt' else 232));server.stdin.close();server=None
        if strict:assert all(codes),codes;stops+=1

    pairs={(50045,5004501),(50045,5004502)};saved=None;opened=None;active_times=None
    try:
        for cycle in range(2):
            log=(directory/f'server-{cycle}.log').open('w',encoding='utf-8');logs.append(log)
            server=subprocess.Popen([str(java),*jflags,'-Xmx1536m','-jar',str(jar),'--no-handbook'],cwd=directory,
                stdin=subprocess.PIPE,stdout=log,stderr=subprocess.STDOUT,creationflags=flags)
            gm.wait_for(lambda:server.poll() is None and request('/health/ready')['ready'])
            csrf=request('/gm/api/session',{})['data']['csrf']
            if cycle==0:
                assert request('/gm/api/status')['data']['onlinePlayers']==0
                total=request('/gm/api/activities?limit=100')['data']['total']
                for offset in range(0,total,100):catalog.extend(request(f'/gm/api/activities?limit=100&offset={offset}')['data']['items'])
                assert len(catalog)==total and len({x['id'] for x in catalog})==total
                assert entry(50045)['canManage'] and len(entry(50045)['modules'])==2
                assert request('/gm/api/activities?q=5004502&type=event')['data']['total']==1
                readonly=next(x for x in catalog if not x['canManage']);operate('open',readonly['id'],success=False)
                now=int(time.time());opened,_=operate('open',beginTime=now-1,endTime=now+3600)
                active_times=(now-1,now+3600)
                operate('open',50046,durationDays=7)
                operate('open',50118,beginTime=now+86400,endTime=now+2*86400)
                operate('open',30013,durationDays=7)
                assert entry(50118)['state']=='scheduled'
            account=request('/hkrpg_cn/mdk/shield/api/login',{'account':account_name,'password':'disposable-activity'})['data']['account']
            combo=request('/hkrpg_cn/combo/granter/login/v2/login',{'data':json.dumps({'uid':account['uid'],'token':account['token']})})
            credentials=directory/'credentials.json';credentials.write_text(json.dumps({'accountUid':account['uid'],'comboToken':combo['data']['combo_token']}),encoding='utf-8')
            (directory/'quit').unlink(missing_ok=True);(directory/'snapshot.json').unlink(missing_ok=True)
            log=(directory/f'probe-{cycle}.log').open('w',encoding='utf-8');logs.append(log)
            probe=subprocess.Popen([str(java),*jflags,'-cp',str(helpers)+os.pathsep+str(jar),'org.hkrpg.server.protocol.NativeGmPlayerProbe',
                str(game),str(credentials),str(directory)],cwd=directory,stdout=log,stderr=subprocess.STDOUT,creationflags=flags)
            initial=gm.wait_for(snapshot)
            if uid is None:uid=initial['uid']
            assert uid==initial['uid']
            wire=schedules();assert (50046,5004601) in wire and (50118,5011801) not in wire and (30013,3001301) not in wire,wire
            if cycle==0:
                assert pairs.issubset(wire) and all(wire[pair]==active_times for pair in pairs)
                operate('stop');assert pairs.isdisjoint(schedules())
                assert request('/gm/api/operate',opened)['data']['state']=='completed'
                assert pairs.isdisjoint(schedules()),'Old request replay reopened activity'
                operate('enable');assert all(schedules()[pair]==active_times for pair in pairs)
                if args.browser_pause:
                    ready={'base':base,'runtimeDirectory':str(directory),'uid':uid,'jarSHA256':digest}
                    (ROOT/'build/gm-activities-browser-ready.json').write_text(json.dumps(ready,indent=2),encoding='utf-8')
                    print(json.dumps({'browserReady':True,**ready}),flush=True)
                    gm.wait_for(lambda:(directory/'continue').exists(),2400)
                operate('stop');assert pairs.isdisjoint(schedules())
                assert request('/gm/api/operate',opened)['data']['state']=='completed'
            else:
                assert pairs.isdisjoint(wire) and entry(50045)['state']=='stopped'
                assert request('/gm/api/operate',opened)['data']['state']=='completed'
                assert pairs.isdisjoint(schedules())
                assert initial['bag']==saved['bag'] and initial['avatars']==saved['avatars']
            saved=snapshot();snapshots.append(saved);stop()
        assert hashlib.sha256(jar.read_bytes()).hexdigest()==digest
        report={'passed':True,'jarSHA256':digest,'runtimeDirectory':str(directory),'catalogEntries':len(catalog),
                'manageableEntries':sum(x['canManage'] for x in catalog),'normalStops':stops,'sameUidRestart':True,'uid':uid,
                'nativeRequests':len(native),'productionModified':False,'clientAccepted':False,'native':native,'snapshots':snapshots,
                'checks':['zero-player open','all source modules','custom begin/end wire values','future excluded','original unlock retained',
                          'read-only rejected','stop immediate','restore without extending','replay after stop and restart','disk and H2 restart retained']}
        args.output.parent.mkdir(exist_ok=True,parents=True);args.output.write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf-8')
        print(json.dumps({k:v for k,v in report.items() if k not in ['native','snapshots']}))
    finally:
        stop(False)
        for log in logs:log.close()
        (directory/'credentials.json').unlink(missing_ok=True)


if __name__=='__main__':main()
