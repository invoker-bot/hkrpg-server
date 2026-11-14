"""隔离GM/KCP/H2：原版与两轮音律复刻的27关、编曲、16档报酬、20目标及独立保存。"""
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
 parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--browser-pause',action='store_true');parser.add_argument('--jar',type=Path,default=ROOT/'build/libs/hkrpg-server.jar');args=parser.parse_args()
 types=schema();finished=types['Quest'].DESCRIPTOR.fields_by_name['status'].enum_type.values_by_number[2].name;closed=types['Quest'].DESCRIPTOR.fields_by_name['status'].enum_type.values_by_number[3].name;profile=json.loads((ROOT/'src/main/resources/gameplay/music-replay-4.6.json').read_text('utf8'))
 records=profile['records'];levels=sorted(records['MusicRhythmLevel'],key=lambda x:(x['Difficulty'],x['ID']))
 goal_ids=sorted(r['QuestID'] for r in records['QuestData']);jar=args.jar.resolve();assert jar.is_file() and jar.is_relative_to(ROOT/'build');digest=hashlib.sha256(jar.read_bytes()).hexdigest()
 java=next((ROOT/'.tools').glob('jdk-21*/bin/java.exe'));flags=subprocess.CREATE_NO_WINDOW if os.name=='nt' else 0
 runs=ROOT/'build/smoke';runs.mkdir(exist_ok=True);directory=Path(tempfile.mkdtemp(prefix='gm-music-replay-',dir=runs));helpers=directory/'helpers';helpers.mkdir()
 sources=[ROOT/'scripts/fixtures/java'/p for p in ['org/hkrpg/server/protocol/NativeGmPlayerProbe.java',
  'emu/lunarcore/command/commands/ActivityReplayFixtureCommand.java','org/hkrpg/server/gameplay/ActivityReplayMusicInspector.java']]
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
 account_name='music_replay_'+uuid.uuid4().hex+'@example.invalid'
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
  value=subprocess.run([str(java),*jflags,'-cp',str(helpers)+os.pathsep+str(jar),'org.hkrpg.server.gameplay.ActivityReplayMusicInspector'],
    cwd=directory,input=json.dumps({'directory':str(directory),'uid':uid}).encode(),capture_output=True,creationflags=flags,timeout=35)
  if value.returncode:raise RuntimeError(value.stderr.decode('utf8','replace')+value.stdout.decode('utf8','replace'))
  row=json.loads(next(s for s in value.stdout.decode('utf8').splitlines() if s.startswith('{')));row['label']=label;reads.append(row);return row
 def stop_probe():
  nonlocal probe
  if probe:
   (directory/'quit').touch();probe.wait(timeout=45);assert probe.returncode==0;probe=None
   gm.wait_for(lambda:request('/status/server')['status']['playerCount']==0,15)
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
  (directory/'music-replay-fixture.json').write_text(json.dumps({'testFixture':True,'uid':uid}),'utf8');return initial
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
 def goals():
  response=send('GetQuestDataCsReq');return {r['id']:r for r in response.get('quest_list',[]) if r['id'] in goal_ids}
 def music():return send('MusicRhythmDataCsReq')
 def offering():return send('GetOfferingInfoCsReq',{'offering_id_list':[5]})['offering_info_list'][0]
 def play(rows):
  commands=[]
  for row in rows:commands.extend([step('MusicRhythmStartLevelCsReq',{'level_id':row['ID']}),step('MusicRhythmFinishLevelCsReq',{'role_star':3,'full_combo':True})])
  send_steps(commands)
 def complete(label,before,rows=None):
  play(levels if rows is None else rows)
  data=music();assert len(data['level_data_list'])==27 and all(r['unlock_level']==3 for r in data['level_data_list']),data
  song=records['MusicRhythmSong'][0]
  send('MusicRhythmSaveSongConfigDataCsReq',{'BENAFNHLCEO':{'music_group_phase':song['ID'],'music_group_id':song['PresetIDList'][0]}})
  offering();send('SubmitOfferingItemCsReq',{'offering_id':5});assert offering()['offering_level']==16
  send('TakeOfferingRewardCsReq',{'offering_id':5,'take_reward_level_list':list(range(1,17))})
  state=goals();assert len(state)==20 and all(r['status']==finished for r in state.values()),state
  print(json.dumps({'claimingGoals':label,'runtimeDirectory':str(directory)}),flush=True)
  reward=send('TakeQuestRewardCsReq',{'ODJIJFJCHHB':goal_ids});assert sorted(reward['succ_quest_id_list'])==goal_ids,reward
  state=goals();assert len(state)==20 and all(r['status']==closed for r in state.values()),state
  send('TakeQuestRewardCsReq',{'ODJIJFJCHHB':goal_ids},False);send('TakeOfferingRewardCsReq',{'offering_id':5,'take_reward_level_list':[1]},False)
  after=inspect(label)
  assert after['wallet']['hcoin']-before['wallet']['hcoin']==1380,(before,after)
  assert after['wallet']['scoin']-before['wallet']['scoin']==960000,(before,after)
  assert after['wallet']['exp']-before['wallet']['exp']==1600,(before,after)
  assert after['materials']['121003']-before['materials']['121003']==4
  assert after['materials']['236']-before['materials']['236']==1
  assert after['lightCones']-before['lightCones']==1
  cycles.append(label);print(json.dumps({'cycleCompleted':label,'nativeRequests':len(native)}),flush=True);return after
 report=None
 try:
  start_server();event=request('/gm/api/activities/history?q=50022')['data']['items'][0];assert event['canReplay'] and event['family']=='music',event
  operate('activity-open',activityId=50022,durationDays=30);login();operate('command',uid=uid,command='activityreplayfixture music');stop_probe();login()
  scene=send('GetCurSceneInfoCsReq')['scene'];assert scene['floor_id']==20101001
  assert any(g.get('group_id',0)==102 and any(e.get('npc',{}).get('npc_id')==3025 for e in g.get('entity_list',[])) for g in scene['entity_group_list'])
  music();legacy_before=inspect('legacy-before');legacy=complete('legacy-complete',legacy_before)
  first=operate('activity-rerun',activityKey=event['key'],durationDays=30)
  send('MusicRhythmStartLevelCsReq',{'level_id':101},False);data=music();assert all(r.get('unlock_level',0)==0 for r in data['level_data_list']) and data.get('cur_song_id',0)==0
  assert offering().get('offering_level',0)==0;assert goals()[6029231].get('progress',0)==0
  first_before=inspect('first-before');send('MusicRhythmStartLevelCsReq',{'level_id':101});pending=inspect('first-active-before-restart')
  assert pending['musicRuns'][first['requestId']]['state']['activeLevel']==101;stop()
  start_server();login();data=music();assert data['cur_level_id']==101
  send('MusicRhythmFinishLevelCsReq',{'role_star':3,'full_combo':True})
  first_done=complete('first-complete',first_before,[r for r in levels if r['ID']!=101])
  now=int(time.time());future=operate('activity-rerun',activityKey=event['key'],beginTime=now+3600,endTime=now+86400)
  music();offering();held=snapshot()['bag'];send('MusicRhythmStartLevelCsReq',{'level_id':101},False);send('SubmitOfferingItemCsReq',{'offering_id':5},False);assert snapshot()['bag']==held
  second=operate('activity-rerun',activityKey=event['key'],durationDays=30)
  send('MusicRhythmFinishLevelCsReq',{'role_star':3},False);music();assert offering().get('offering_level',0)==0
  second_before=inspect('second-before');second_done=complete('second-complete',second_before)
  assert second_done['legacyRhythm']==legacy['legacyRhythm'] and second_done['legacyOfferings']==legacy['legacyOfferings']
  assert all(second_done['legacyRewards'].get(k)==v for k,v in legacy['legacyRewards'].items())
  assert second_done['musicRuns'][first['requestId']]==first_done['musicRuns'][first['requestId']]
  assert request('/gm/api/operate',first)['data']['state']=='completed'
  current=json.loads((directory/'data/Gameplay46.json').read_text('utf8'));assert next(r for r in current['activities'] if r['panelId']==50022)['replayId']==second['requestId']
  operate('activity-stop',activityId=50022,activityKey=event['key']);send('SubmitOfferingItemCsReq',{'offering_id':5},False)
  operate('activity-enable',activityId=50022,activityKey=event['key']);music();offering()
  if args.browser_pause:
   (ROOT/'build/gm-music-replay-browser-ready.json').write_text(json.dumps({'base':base,'runtimeDirectory':str(directory),'uid':uid,'key':event['key'],'jarSHA256':digest},indent=2),'utf8');print(json.dumps({'browserReady':True,'base':base,'runtimeDirectory':str(directory)}),flush=True)
   gm.wait_for(lambda:(directory/'continue').exists(),2400)
  stopped=inspect('before-final-restart');stop();start_server();login();music();assert offering()['offering_level']==16
  restored=goals();assert len(restored)==20 and all(q['status']==closed for q in restored.values())
  send('TakeQuestRewardCsReq',{'ODJIJFJCHHB':goal_ids},False)
  final=inspect('after-final-restart');assert final['musicRuns']==stopped['musicRuns'] and final['legacyRhythm']==legacy['legacyRhythm'];assert final['materials']['270003']==18
  stop();assert all('Exception in thread "hkrpg-shutdown"' not in p.read_text('utf8',errors='replace') for p in directory.glob('server-*.log'));assert hashlib.sha256(jar.read_bytes()).hexdigest()==digest
  report={'passed':True,'jarSHA256':digest,'runtimeDirectory':str(directory),'nativeRequests':len(native),'normalStops':normal_stops,
   'completedCycles':cycles,'levelsCompleted':81,'activityRewardsClaimed':60,'offeringRewardLevelsClaimed':48,'sameUidRestarts':2,
   'sourceResourcesModified':False,'productionModified':False,'clientAccepted':False,'syntheticPrerequisites':'level 21 and original story prerequisites only; original NPC scene entered; no play or rewards seeded',
   'native':native,'playerReads':reads}
  (ROOT/'build/gm-music-replay-native.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),'utf8');print(json.dumps({k:v for k,v in report.items() if k not in ('native','playerReads')}))
 finally:
  stop(False)
  for log in logs:log.close()
  (directory/'credentials.json').unlink(missing_ok=True)


if __name__=='__main__':main()
