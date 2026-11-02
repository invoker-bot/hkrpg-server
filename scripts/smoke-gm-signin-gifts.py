"""隔离GM/KCP/H2：七期签到赠礼的原版和两次复刻、75项实际到账、独立周期及重启。"""
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
import time
import urllib.request
import uuid
from google.protobuf import descriptor_pb2,descriptor_pool,message_factory

ROOT=Path(__file__).resolve().parents[1]
spec=importlib.util.spec_from_file_location('gm',ROOT/'scripts/smoke-gm-console.py')
gm=importlib.util.module_from_spec(spec);spec.loader.exec_module(gm)


def schema():
 files=descriptor_pb2.FileDescriptorSet();files.ParseFromString((ROOT/'src/main/resources/protocol/4.6/descriptor.pb').read_bytes())
 pool=descriptor_pool.DescriptorPool();pending=list(files.file)
 while pending:
  old=len(pending)
  for f in pending[:]:
   try:pool.AddSerializedFile(f.SerializeToString());pending.remove(f)
   except TypeError:pass
  if len(pending)==old:raise ValueError('Unresolved protocol dependency')
 result={m.name:message_factory.GetMessageClass(pool.FindMessageTypeByName((f.package+'.' if f.package else '')+m.name)) for f in files.file for m in f.message_type}
 aliases=json.loads((ROOT/'src/main/resources/protocol/4.6/aliases.json').read_text('utf8'))
 result.update({name:result[original] for name,original in aliases.items()})
 return result


def main():
 parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--browser-pause',action='store_true');args=parser.parse_args()
 types=schema();profile=json.loads((ROOT/'src/main/resources/gameplay/signin-gift-replay-4.6.json').read_text('utf8'))
 records=profile['records'];login_rows=sorted(records['ActivityLoginConfig'],key=lambda r:r['ID']);ids=[r['ID'] for r in login_rows]
 rewards={r['RewardID']:r for r in records['RewardData']};jar=ROOT/'build/libs/hkrpg-server.jar';digest=hashlib.sha256(jar.read_bytes()).hexdigest()
 java=next((ROOT/'.tools').glob('jdk-21*/bin/java.exe'));flags=subprocess.CREATE_NO_WINDOW if os.name=='nt' else 0
 runs=ROOT/'build/smoke';runs.mkdir(exist_ok=True);directory=Path(tempfile.mkdtemp(prefix='gm-signin-gifts-',dir=runs));helpers=directory/'helpers';helpers.mkdir()
 sources=[ROOT/'scripts/fixtures/java'/p for p in ['org/hkrpg/server/protocol/NativeGmPlayerProbe.java',
  'emu/lunarcore/command/commands/ActivityReplayFixtureCommand.java','org/hkrpg/server/gameplay/ActivityReplaySigninInspector.java']]
 compiled=subprocess.run([str(java.with_name('javac.exe')),'-encoding','UTF-8','-cp',str(jar),'-d',str(helpers),*map(str,sources)],capture_output=True,creationflags=flags)
 if compiled.returncode:raise RuntimeError(compiled.stderr.decode('utf8','replace'))
 (directory/'activity-replay-owned-fixture').touch()
 port,mongo,game=gm.free_port(),gm.free_port(),gm.free_port(socket.SOCK_DGRAM)
 assert not {port,mongo,game}&{18080,23311,27017,27079}
 (directory/'config.json').write_text(json.dumps({'protocol':{'wireVersion':'4.6.0'},'resourceDir':str(ROOT/'resources/4.6'),
  'httpServer':{'bindAddress':'127.0.0.1','bindPort':port},'gameServer':{'bindAddress':'127.0.0.1','bindPort':game},
  'internalMongoServer':{'address':'127.0.0.1','port':mongo,'filePath':'./data/database.mv'}}),'utf8')
 base=f'http://127.0.0.1:{port}';csrf='';uid=None;server=probe=None;logs=[];normal_stops=0;packet_id=2000;native=[];reads=[];cycles=[]
 opener=urllib.request.build_opener(urllib.request.ProxyHandler({}),urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
 account_name='signin_replay_'+uuid.uuid4().hex+'@example.invalid'
 jflags=['-Dfile.encoding=UTF-8','-Dio.netty.eventLoopThreads=2','-Djdk.net.unixdomain.tmpdir='+str(directory/'tcp-fallback')]
 def request(path,body=None):
  req=urllib.request.Request(base+path,data=None if body is None else json.dumps(body).encode(),headers={'Content-Type':'application/json','Origin':base,'X-GM-CSRF':csrf})
  with opener.open(req,timeout=30) as r:return json.load(r)
 def snapshot():return json.loads((directory/'snapshot.json').read_text('utf8'))
 def operate(action,**fields):
  body={'requestId':str(uuid.uuid4()),'action':action,**fields};value=request('/gm/api/operate',body)['data'];assert value['state'] in ('completed','returned'),value;return body
 def step(name,fields=None,success=True):
  nonlocal packet_id
  packet_id+=1;body=types[name](**(fields or {})).SerializeToString()
  return {'command':name,'wire':base64.b64encode(body).decode(),'success':success,'packetId':packet_id}
 def send_steps(steps):
  rid=str(uuid.uuid4());path=directory/'equipment-request.json';temp=path.with_suffix('.tmp')
  temp.write_text(json.dumps({'requestId':rid,'commands':steps}),'utf8');temp.replace(path)
  deadline=time.monotonic()+120
  while True:
   if probe.poll() is not None:raise RuntimeError('Native probe exited; inspect '+str(directory))
   try:s=snapshot()
   except (OSError,json.JSONDecodeError):s={}
   if s.get('equipmentReply',{}).get('requestId')==rid:break
   if time.monotonic()>=deadline:raise TimeoutError('Native reply timed out: '+str(directory))
   time.sleep(.1)
  path.unlink();responses=s['equipmentReply']['responses'];assert len(responses)==len(steps)
  for command,response in zip(steps,responses):
   body=response['response'];assert (body.get('retcode',0)==0)==command['success'],response
   native.append({'command':command['command'],'success':command['success'],'response':body})
  return [r['response'] for r in responses]
 def send(name,fields=None,success=True):return send_steps([step(name,fields,success)])[0]
 def inspect(label):
  value=subprocess.run([str(java),*jflags,'-cp',str(helpers)+os.pathsep+str(jar),'org.hkrpg.server.gameplay.ActivityReplaySigninInspector'],
    cwd=directory,input=json.dumps({'directory':str(directory),'uid':uid}).encode(),capture_output=True,creationflags=flags,timeout=35)
  if value.returncode:raise RuntimeError(value.stderr.decode('utf8','replace')+value.stdout.decode('utf8','replace'))
  row=json.loads(next(s for s in value.stdout.decode('utf8').splitlines() if s.startswith('{')));row['label']=label;reads.append(row);return row
 def stop_probe():
  nonlocal probe
  if probe:
   (directory/'quit').touch();probe.wait(timeout=45);assert probe.returncode==0;probe=None
 def login():
  nonlocal probe,uid
  account=request('/hkrpg_cn/mdk/shield/api/login',{'account':account_name,'password':'isolated-music'})['data']['account']
  combo=request('/hkrpg_cn/combo/granter/login/v2/login',{'data':json.dumps({'uid':account['uid'],'token':account['token']})})
  credentials=directory/'credentials.json';credentials.write_text(json.dumps({'accountUid':account['uid'],'comboToken':combo['data']['combo_token']}),'utf8')
  for name in ['quit','snapshot.json']:(directory/name).unlink(missing_ok=True)
  log=(directory/f'probe-{len(logs)}.log').open('w',encoding='utf8');logs.append(log)
  probe=subprocess.Popen([str(java),*jflags,'-cp',str(helpers)+os.pathsep+str(jar),'org.hkrpg.server.protocol.NativeGmPlayerProbe',str(game),str(credentials),str(directory)],cwd=directory,stdout=log,stderr=subprocess.STDOUT,creationflags=flags)
  initial=gm.wait_for(snapshot)
  if uid is None:uid=initial['uid']
  assert initial['uid']==uid
  (directory/'signin-gift-replay-fixture.json').write_text(json.dumps({'testFixture':True,'uid':uid,'epoch':int(time.time())}),'utf8');return initial
 def start_server():
  nonlocal server,csrf
  log=(directory/f'server-{len(logs)}.log').open('w',encoding='utf8');logs.append(log)
  server=subprocess.Popen([str(java),*jflags,'-Xmx1536m','-cp',str(helpers)+os.pathsep+str(jar),'org.hkrpg.server.HkrpgServer','--no-handbook'],cwd=directory,stdin=subprocess.PIPE,stdout=log,stderr=subprocess.STDOUT,creationflags=flags)
  gm.wait_for(lambda:server.poll() is None and request('/health/ready')['ready'],180);csrf=request('/gm/api/session',{})['data']['csrf']
 def stop(strict=True):
  nonlocal server,probe,normal_stops
  try:stop_probe()
  except Exception:
   if probe:probe.terminate();probe.wait(timeout=15);probe=None
   if strict:raise
  if server:
   if server.poll() is None:
    server.stdin.write(b'stop\n');server.stdin.flush()
    try:server.wait(timeout=45)
    except subprocess.TimeoutExpired:server.terminate();server.wait(timeout=15)
   if strict:assert server.returncode==(1000 if os.name=='nt' else 232);normal_stops+=1
   server.stdin.close();server=None
 def signin():
  data=send('GetLoginActivityCsReq');return {r['id']:r for r in data.get('login_activity_list',[]) if r['id'] in ids}
 def claim(row,day,success=True):return step('TakeLoginActivityRewardCsReq',{'id':row['ID'],'take_days':day},success)
 def register_day(day):operate('command',uid=uid,command='activityreplayfixture signin-day '+str(day))
 expected={1:0}
 for row in login_rows:
  for reward_id in row['RewardList']:
   reward=rewards[reward_id];expected[1]+=reward.get('Hcoin',0)
   for slot in range(1,7):
    item=reward.get('ItemID_'+str(slot),0)
    if item:expected[item]=expected.get(item,0)+reward['Count_'+str(slot)]
 def amount(row,item):return row['hcoin'] if item==1 else row['materials'][str(item)]
 def complete(label,before):
  (directory/'signin-gift-replay-fixture.json').write_text(json.dumps({'testFixture':True,'uid':uid,'epoch':int(time.time())}),'utf8')
  for day in range(1,8):
   register_day(day-1);data=signin();assert set(data)==set(ids),data
   for row in login_rows:assert data[row['ID']]['login_days']==min(day,len(row['RewardList'])),(row,data)
   eligible=[r for r in login_rows if day<=len(r['RewardList'])]
   send_steps([claim(r,day) for r in eligible]);send_steps([claim(r,day,False) for r in eligible])
   if day<7:send_steps([claim(r,day+1,False) for r in eligible if day<len(r['RewardList'])])
  data=signin()
  for row in login_rows:assert data[row['ID']]['MOIAHCMHNEO']==list(range(1,len(row['RewardList'])+1)),data
  after=inspect(label)
  for item,delta in expected.items():assert amount(after,item)-amount(before,item)==delta,(item,delta,before,after)
  cycles.append(label);print(json.dumps({'cycleCompleted':label,'nativeRequests':len(native)}),flush=True);return after
 try:
  start_server();events=[request('/gm/api/activities/history?q='+str(id))['data']['items'] for id in ids]
  events=[next(e for e in rows if e['activityId']==id) for id,rows in zip(ids,events)]
  assert all(e['canReplay'] and e['family']=='login' for e in events),events
  for e in events:operate('activity-open',activityId=e['activityId'],durationDays=30)
  login();operate('command',uid=uid,command='activityreplayfixture');stop_probe();login()
  before=inspect('original-before');original=complete('original-complete',before)
  first=[operate('activity-rerun',activityKey=e['key'],durationDays=30) for e in events]
  (directory/'signin-gift-replay-fixture.json').write_text(json.dumps({'testFixture':True,'uid':uid,'epoch':int(time.time())}),'utf8');register_day(0)
  send_steps([claim(r,1,False) for r in login_rows]);first_before=inspect('first-before');first_done=complete('first-complete',first_before)
  future=operate('activity-rerun',activityKey=events[0]['key'],beginTime=int(time.time())+3600,endTime=int(time.time())+86400)
  assert ids[0] not in signin();send_steps([claim(login_rows[0],1,False)])
  second=[operate('activity-rerun',activityKey=e['key'],durationDays=30) for e in events]
  (directory/'signin-gift-replay-fixture.json').write_text(json.dumps({'testFixture':True,'uid':uid,'epoch':int(time.time())}),'utf8');register_day(0)
  send_steps([claim(r,1,False) for r in login_rows]);second_before=inspect('second-before');second_done=complete('second-complete',second_before)
  assert second_done['legacyDays']==original['legacyDays'] and second_done['legacyRewards']==original['legacyRewards']
  for operation in first:assert second_done['loginRuns'][operation['requestId']]==first_done['loginRuns'][operation['requestId']]
  for operation in first:assert request('/gm/api/operate',operation)['data']['state']=='completed'
  live=json.loads((directory/'data/Gameplay46.json').read_text('utf8'))
  for operation,e in zip(second,events):assert next(w for w in live['activities'] if w['panelId']==e['panelId'])['replayId']==operation['requestId']
  if args.browser_pause:
   ready={'base':base,'runtimeDirectory':str(directory),'uid':uid,'jarSHA256':digest,'keys':[e['key'] for e in events]}
   (ROOT/'build/gm-signin-gifts-browser-ready.json').write_text(json.dumps(ready,indent=2),'utf8');print(json.dumps({'browserReady':True,**ready}),flush=True)
   gm.wait_for(lambda:(directory/'continue').exists(),2400)
  stop();start_server();login();data=signin()
  assert all(data[r['ID']]['MOIAHCMHNEO']==list(range(1,len(r['RewardList'])+1)) for r in login_rows)
  send_steps([claim(r,1,False) for r in login_rows]);restored=inspect('after-same-uid-restart')
  for key in ('hcoin','materials','legacyDays','legacyRewards','loginRuns'):assert restored[key]==second_done[key],key
  stop();assert hashlib.sha256(jar.read_bytes()).hexdigest()==digest
  report={'passed':True,'jarSHA256':digest,'runtimeDirectory':str(directory),'nativeRequests':len(native),'editions':len(events),
   'rewardClaims':75,'completeRounds':cycles,'replayInstancesCompleted':14,'controlledCalendarDays':21,
   'sameUidRestarts':1,'normalStops':normal_stops,'expectedPerRound':expected,'sourceResourcesModified':False,
   'productionModified':False,'clientAccepted':False,'calendarBoundary':'seven configured login dates per round are driven by an owned test-only clock and the real registration method; no wall-clock week or full game client was tested',
   'native':native,'playerReads':reads}
  (ROOT/'build/gm-signin-gifts-native.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),'utf8');print(json.dumps({k:v for k,v in report.items() if k not in ('native','playerReads')}))
 finally:
  stop(False)
  for log in logs:log.close()
  (directory/'credentials.json').unlink(missing_ok=True)


if __name__=='__main__':main()
