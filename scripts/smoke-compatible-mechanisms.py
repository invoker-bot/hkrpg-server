"""全部 37 个兼容宝箱：原生 KCP 解锁/领取、正常停服、同 UID H2 恢复；不修改运营数据。"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import socket
import subprocess
import tempfile
import time
import urllib.request
from compatible_mechanism_probe_cases import phases

ROOT=Path(__file__).resolve().parents[1]
def sha(p):return hashlib.sha256(p.read_bytes()).hexdigest()
def port(kind=socket.SOCK_STREAM):
    with socket.socket(type=kind) as s:s.bind(('127.0.0.1',0));return s.getsockname()[1]

def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--jar',type=Path,default=ROOT/'build/libs/hkrpg-server.jar');p.add_argument('--output',type=Path,default=ROOT/'build/compatible-native.json');p.add_argument('--flow');a=p.parse_args()
    jar=a.jar.resolve();output=a.output.resolve();assert jar.is_file() and jar.is_relative_to(ROOT/'build') and output.is_relative_to(ROOT/'build')
    stages=phases();stages=[s for s in stages if not a.flow or s['flow']==a.flow];assert stages
    expected={step['id'] for s in stages for step in s['actions'] if step['op']=='claim'}
    if not a.flow:assert len(expected)==37
    source=ROOT/'resources/4.6';catalog=json.loads((ROOT/'src/main/resources/gameplay/compatible-mechanisms-4.6.json').read_text('utf-8'))
    hashes={p:sha(source/p) for p in set(catalog['files'])|{str(f.relative_to(source)).replace('\\','/') for f in (source/'ExcelOutput').glob('*.json')}}
    artifact=sha(jar);flags=subprocess.CREATE_NO_WINDOW if os.name=='nt' else 0
    java=next((ROOT/'.tools').glob('jdk-21*/bin/java.exe'));directory=Path(tempfile.mkdtemp(prefix='compatible-',dir=ROOT/'build/smoke'))
    resources=directory/'resources-fixture';resources.mkdir();(resources/'resource-manifest.json').write_text(json.dumps(dict(testFixture=True,worldChestFixture=True,mapChestFixture=True,compatibleFixture=True)),'utf-8')
    http,mongo,game=port(),port(),port(socket.SOCK_DGRAM)
    cfg=directory/'config.json';cfg.write_text(json.dumps(dict(protocol=dict(wireVersion='4.6.0'),resourceDir=str(source),httpServer=dict(bindAddress='127.0.0.1',bindPort=http),gameServer=dict(bindAddress='127.0.0.1',bindPort=game),internalMongoServer=dict(address='127.0.0.1',port=mongo,filePath='./data/database.mv'))),'utf-8');config=cfg.read_bytes()
    classes=directory/'probe-classes';classes.mkdir()
    sources=[ROOT/'scripts/fixtures/java/org/hkrpg/server/protocol/NativeWorldChestProbe.java',ROOT/'scripts/fixtures/java/org/hkrpg/server/gameplay/WorldChestFixtureInspector.java']
    r=subprocess.run([str(java.with_name('javac.exe')),'-encoding','UTF-8','-cp',str(jar),'-d',str(classes),*map(str,sources)],capture_output=True,creationflags=flags)
    (directory/'javac.log').write_bytes(r.stdout+r.stderr);assert r.returncode==0,directory/'javac.log'
    options=['-Dfile.encoding=UTF-8','-Dstdout.encoding=UTF-8','-Dstderr.encoding=UTF-8',f'-Djdk.net.unixdomain.tmpdir={directory/"tcp-fallback"}'];cp=str(classes)+os.pathsep+str(jar)
    credentials={};known=[];events={};combat={};mission_rewards=[];snapshots=[];inspections=[];stops=0
    stage=stages[0]['stage'];pre=stages[0]['prerequisite']
    def request(route,body=None):
        req=urllib.request.Request(f'http://127.0.0.1:{http}'+route,data=None if body is None else json.dumps(body).encode(),headers={'Content-Type':'application/json'})
        with urllib.request.urlopen(req,timeout=5) as response:return json.load(response)
    def inspect(mode):
        control=directory/f'inspector-{len(inspections)}.json'
        control.write_text(json.dumps(dict(uid=credentials['uid'],known=known,claimed=[],goals=[],mapChests=True,compatibleFixture=True,compatibleEvents=events,combatPayout=combat,missionRewards=mission_rewards,stage=stage,prerequisite=pre)),'utf-8')
        r=subprocess.run([str(java),*options,'-cp',cp,'org.hkrpg.server.gameplay.WorldChestFixtureInspector',str(directory),str(control),mode],cwd=ROOT,capture_output=True,text=True,encoding='utf-8',timeout=180,creationflags=flags)
        log=directory/f'inspector-{len(inspections)}.log';log.write_text(r.stdout+r.stderr,'utf-8');assert r.returncode==0,log
        result=json.loads(next(l for l in reversed(r.stdout.splitlines()) if l.startswith('{')));assert result['passed'];inspections.append(result)
    for index,phase in enumerate([None,*stages]):
        actions=[] if phase is None else phase['actions']
        control=dict(credentials,**stage,allChests=[],chests=[],goals=[],known=known,claimed=[],seed=index==0,claim=0)
        if phase:control['compatibleActions']=actions
        with (directory/f'server-{index}.log').open('wb') as log:
            process=subprocess.Popen([str(java),*options,'-Xmx1536m','-jar',str(jar),'--no-handbook'],cwd=directory,stdin=subprocess.PIPE,stdout=log,stderr=subprocess.STDOUT,creationflags=flags)
            try:
                deadline=time.monotonic()+120
                while True:
                    assert process.poll() is None,directory/f'server-{index}.log'
                    try:
                        if request('/health/ready')['ready']:break
                    except (OSError,ValueError):pass
                    if time.monotonic()>deadline:raise TimeoutError(directory)
                    time.sleep(.2)
                if not credentials:
                    login=request('/hkrpg_cn/mdk/shield/api/login',dict(account='compatible-native',password='local-test'));assert login['retcode']==0
                    account=login['data']['account'];combo=request('/hkrpg_cn/combo/granter/login/v2/login',dict(data=json.dumps(dict(uid=account['uid'],token=account['token']))));assert combo['retcode']==0
                    credentials.update(accountUid=account['uid'],comboToken=combo['data']['combo_token']);control.update(credentials)
                probe=directory/f'probe-{index}.log'
                with probe.open('wb') as out:
                    child=subprocess.Popen([str(java),*options,'-cp',cp,'org.hkrpg.server.protocol.NativeWorldChestProbe',str(game)],cwd=directory,stdin=subprocess.PIPE,stdout=out,stderr=subprocess.STDOUT,creationflags=flags)
                    child.stdin.write((json.dumps(control)+'\n').encode());child.stdin.close();deadline=time.monotonic()+240;granted=False
                    try:
                        while child.poll() is None:
                            if not index and not granted:
                                m=re.search(r'READY_UID=(\d+)',probe.read_text('utf-8'))
                                if m:process.stdin.write(f'give 1001 @{int(m.group(1))}\n'.encode());process.stdin.flush();granted=True
                            if time.monotonic()>deadline:raise TimeoutError(probe)
                            time.sleep(.2)
                    finally:
                        if child.poll() is None:child.terminate();child.wait(timeout=15)
                assert child.returncode==0,probe
                result=json.loads(next(l for l in reversed(probe.read_text('utf-8').splitlines()) if l.startswith('{')));assert result['passed'];credentials.setdefault('uid',result['uid']);assert credentials['uid']==result['uid'];snapshots.append(result)
                known=sorted(known+result.get('openedOriginalChests',[]))
                for item,count in result.get('combatPayout',{}).items():combat[item]=combat.get(item,0)+count
                if phase:
                    for step in actions:
                        events.setdefault(phase['flow'],[]);events[phase['flow']]=sorted(set(events[phase['flow']]+step.get('events',[])))
                        if step.get('completedMain'):
                            main=next(m for m in json.loads((source/'ExcelOutput/MainMission.json').read_text('utf-8')) if m['MainMissionID']==step['completedMain'])
                            if not main.get('RewardID'):continue
                            reward=next(r for r in json.loads((source/'ExcelOutput/RewardData.json').read_text('utf-8')) if r['RewardID']==main['RewardID'])
                            recipe={1:reward['Hcoin']} if reward.get('Hcoin') else {}
                            for k,item in reward.items():
                                if k.startswith('ItemID_') and item:recipe[item]=recipe.get(item,0)+reward['Count_'+k.split('_')[1]]
                            mission_rewards.append(dict(mission=step['completedMain'],rewards=recipe))
            finally:
                if process.poll() is None:
                    try:process.stdin.write(b'stop\n');process.stdin.flush();process.wait(timeout=45);assert process.returncode==(1000 if os.name=='nt' else 232);stops+=1
                    finally:
                        if process.poll() is None:process.terminate();process.wait(timeout=15)
                        process.stdin.close()
        inspect('seed' if index==0 else 'inspect')
        if index<len(stages):
            upcoming=stages[index]
            move=index>0 and (stage!=upcoming['stage'] or upcoming['prerequisite'] is not None)
            stage=upcoming['stage'];pre=upcoming['prerequisite']
            if move:inspect('move')
        print(json.dumps(dict(phase=index,flow=phase['flow'] if phase else 'seed',claimed=len(known),normalStops=stops,total=len(stages)+1)),flush=True)
    assert set(known)==expected and len(known)==len(expected)
    assert artifact==sha(jar) and config==cfg.read_bytes()
    assert all(sha(source/p)==digest for p,digest in hashes.items())
    output.parent.mkdir(parents=True,exist_ok=True);output.write_text(json.dumps(dict(passed=True,artifactSha256=artifact,runtimeDirectory=str(directory),openedOriginalChests=known,normalStops=stops,sameUidRestarts=stops-1,nativeRequests=sum(s['nativeRequests'] for s in snapshots),compatibleEvents=events,snapshots=snapshots,inspections=inspections,clientGameplayVerified=False),indent=2),'utf-8');print(output)

if __name__=='__main__':main()
