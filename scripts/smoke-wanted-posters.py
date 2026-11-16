"""隔离 H2 和 4.6 KCP 检查七张通缉令，绝不读取或修改正式玩家存档。"""
import argparse
import hashlib
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

def sha(path):return hashlib.sha256(path.read_bytes()).hexdigest()
def port(kind):
    with socket.socket(type=kind) as sock:
        sock.bind(('127.0.0.1',0));return sock.getsockname()[1]

def run(baseline, candidate, output):
    baseline,candidate,output=baseline.resolve(strict=True),candidate.resolve(strict=True),output.resolve()
    if any(not p.is_relative_to(ROOT/'build') for p in (baseline,candidate,output)) or output.exists():
        raise ValueError('只允许 build 内的冻结程序和新报告')
    smoke=ROOT/'build/smoke';smoke.mkdir(parents=True,exist_ok=True)
    directory=Path(tempfile.mkdtemp(prefix='wanted-posters-',dir=smoke))
    (directory/'fixture-marker').write_text('wanted-posters-v1','ascii')
    classes=directory/'classes';classes.mkdir()
    java=next((ROOT/'.tools').glob('jdk-21*/bin/java.exe'))
    probe=ROOT/'scripts/fixtures/java/org/hkrpg/server/protocol/NativeWantedPosterProbe.java'
    fixture=ROOT/'scripts/fixtures/java/org/hkrpg/server/gameplay/WantedPosterFixture.java'
    options=['-Dfile.encoding=UTF-8','-Dio.netty.eventLoopThreads=2',
             '-Djdk.net.unixdomain.tmpdir='+str(directory/'tcp-fallback')]
    flags=subprocess.CREATE_NO_WINDOW if os.name=='nt' else 0
    compiled=subprocess.run([str(java.with_name('javac.exe')),'-encoding','UTF-8','-proc:none',
        '-cp',str(candidate),'-d',str(classes),str(probe),str(fixture)],capture_output=True,creationflags=flags)
    (directory/'compile.log').write_bytes(compiled.stdout+compiled.stderr)
    if compiled.returncode:raise RuntimeError('隔离探针编译失败: '+str(directory/'compile.log'))
    resources=ROOT/'resources/4.6'
    paths=[resources/f'Config/LevelOutput/SharedRuntimeGroup/Groups_P10101_F10101001/LevelGroup_P10101_F10101001_G{i}.json' for i in range(147,154)]
    paths += [resources/f'Config/Level/Mission/4040109/Mission_{i}.json' for i in range(404010901,404010908)]
    paths += [resources/f'Config/Level/Mission/4040109/Act/Act{i}.json' for i in range(404010901,404010908)]
    paths += [resources/'Config/Level/Mission/4040109/MissionInfo_4040109.partial.json']
    frozen={str(p):sha(p) for p in [baseline,candidate,probe,fixture,Path(__file__).resolve(),*paths]}
    http,mongo,game=port(socket.SOCK_STREAM),port(socket.SOCK_STREAM),port(socket.SOCK_DGRAM)
    while len({http,mongo,game})!=3 or {http,mongo,game}&{18080,23311,27017,27079}:
        http,mongo,game=port(socket.SOCK_STREAM),port(socket.SOCK_STREAM),port(socket.SOCK_DGRAM)
    (directory/'config.json').write_text(json.dumps({'protocol':{'wireVersion':'4.6.0'},
        'resourceDir':str(resources),'httpServer':{'bindAddress':'127.0.0.1','bindPort':http},
        'gameServer':{'bindAddress':'127.0.0.1','bindPort':game},
        'internalMongoServer':{'address':'127.0.0.1','port':mongo,'filePath':'./data/database.mv'}}),'utf-8')
    opener=urllib.request.build_opener(urllib.request.ProxyHandler({}))
    def request(route,data=None):
        req=urllib.request.Request(f'http://127.0.0.1:{http}'+route,
            data=None if data is None else json.dumps(data).encode(),headers={'Content-Type':'application/json'})
        with opener.open(req,timeout=5) as response:return json.load(response)
    uid=None;stops=0;results={};user='wanted-posters-'+uuid.uuid4().hex+'@example.invalid'
    def execute(jar,mode):
        nonlocal uid,stops
        with (directory/(mode+'-server.log')).open('wb') as stream:
            process=subprocess.Popen([str(java),*options,'-Xmx1536m','-jar',str(jar),'--no-handbook'],
                cwd=directory,stdin=subprocess.PIPE,stdout=stream,stderr=subprocess.STDOUT,creationflags=flags)
            try:
                deadline=time.monotonic()+120
                while True:
                    if process.poll() is not None:raise RuntimeError('隔离服务提前退出: '+mode)
                    try:
                        if request('/health/ready')['ready']:break
                    except OSError:pass
                    if time.monotonic()>deadline:raise TimeoutError('隔离服务启动超时')
                    time.sleep(.2)
                auth=request('/hkrpg_cn/mdk/shield/api/login',{'account':user,'password':'isolated-wanted-posters'})
                if auth['retcode']!=0:raise RuntimeError('隔离账号登录失败')
                account=auth['data']['account']
                combo=request('/hkrpg_cn/combo/granter/login/v2/login',
                    {'data':json.dumps({'uid':account['uid'],'token':account['token']})})
                if combo['retcode']!=0:raise RuntimeError('隔离会话登录失败')
                credentials={'accountUid':account['uid'],'comboToken':combo['data']['combo_token'],'mode':mode}
                result=subprocess.run([str(java),*options,'-cp',str(classes)+os.pathsep+str(jar),
                    'org.hkrpg.server.protocol.NativeWantedPosterProbe',str(game)],
                    input=json.dumps(credentials)+'\n',text=True,encoding='utf-8',capture_output=True,
                    timeout=120,creationflags=flags,cwd=directory)
                (directory/(mode+'-probe.log')).write_text(result.stderr,'utf-8')
                if result.returncode:raise RuntimeError('隔离探针失败: '+str(directory/(mode+'-probe.log')))
                record=json.loads(result.stdout.strip().splitlines()[-1]);actual=record.pop('uid')
                if uid is None:uid=actual
                if uid!=actual:raise RuntimeError('隔离账号意外变化')
                record['jarSHA256']=sha(jar);results[mode]=record
            finally:
                if process.poll() is None:
                    try:
                        process.stdin.write(b'stop\n');process.stdin.flush()
                        if process.wait(timeout=60)!=1000:raise RuntimeError('隔离服务异常退出')
                        stops+=1
                    finally:
                        if process.poll() is None:process.terminate();process.wait(timeout=15)
                        process.stdin.close()
    execute(candidate,'warmup')
    result=subprocess.run([str(java),*options,'-cp',str(classes)+os.pathsep+str(candidate),
        'org.hkrpg.server.gameplay.WantedPosterFixture',str(directory),str(uid)],cwd=ROOT,
        capture_output=True,creationflags=flags,timeout=60)
    (directory/'seed.log').write_bytes(result.stdout+result.stderr)
    if result.returncode:raise RuntimeError('隔离存档初始化失败: '+str(directory/'seed.log'))
    execute(baseline,'baseline')
    execute(candidate,'exercise')
    execute(candidate,'recovered')
    execute(candidate,'final')
    if stops!=5 or any(sha(Path(name))!=digest for name,digest in frozen.items()):
        raise RuntimeError('隔离服务或冻结输入校验失败')
    report={'passed':True,'runtimeSHA256':sha(candidate),'baselineSHA256':sha(baseline),
        'requests':sum(r['requests'] for r in results.values()),'normalStops':stops,
        'isolatedRuntime':str(directory),'clientGameplayVerified':False,'results':results}
    output.write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n','utf-8')
    print(json.dumps({k:report[k] for k in ('passed','requests','normalStops','runtimeSHA256')}))

if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--baseline',type=Path,required=True)
    parser.add_argument('--jar',type=Path,required=True)
    parser.add_argument('--output',type=Path,required=True)
    args=parser.parse_args();run(args.baseline,args.jar,args.output)
