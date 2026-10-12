"""Actual isolated packaged Dispatch console, known command failure and normal stop.

No production overlay, operator directory, game resource or game client is used.
An old console failure remains failed even while HTTP is still ready.
"""
import argparse, hashlib, json, os, socket, subprocess, tempfile, time, traceback
from pathlib import Path
import urllib.error, urllib.request

ROOT=next(p for p in Path(__file__).resolve().parents if (p/'scripts/build.ps1').is_file())
HERE=Path(__file__).resolve().parent
JAVA_SOURCE=HERE/'ConsoleContinuationFixtureCommand.java'
if not JAVA_SOURCE.is_file():
    JAVA_SOURCE=ROOT/'scripts/fixtures/java/emu/lunarcore/command/commands/ConsoleContinuationFixtureCommand.java'
sha=lambda p:hashlib.sha256(p.read_bytes()).hexdigest()

def free_port():
    with socket.socket() as sock:
        sock.bind(('127.0.0.1',0));return sock.getsockname()[1]

def request(port,route,body=None):
    raw=None if body is None else json.dumps(body).encode('utf-8')
    req=urllib.request.Request(f'http://127.0.0.1:{port}'+route,raw,{'Content-Type':'application/json'})
    with urllib.request.urlopen(req,timeout=3) as result:return json.load(result)

def wait_marker(process,log,marker,seconds=5):
    deadline=time.monotonic()+seconds
    while time.monotonic()<deadline:
        if marker in log.read_text('utf-8',errors='replace'):return
        if process.poll() is not None:raise AssertionError('Owned server exited before '+marker)
        time.sleep(.1)
    raise AssertionError('Actual console did not process marker '+marker)

def main():
    parser=argparse.ArgumentParser();parser.add_argument('--jar',type=Path,required=True)
    parser.add_argument('--output',type=Path,required=True);args=parser.parse_args()
    jar=args.jar.resolve();output=args.output.resolve()
    if output.exists():raise ValueError('Previous evidence may not be overwritten')
    if not jar.is_file() or not output.is_relative_to(ROOT/'build'):raise ValueError('Owned jar/build output required')
    runs=ROOT/'build/smoke';runs.mkdir(exist_ok=True)
    directory=Path(tempfile.mkdtemp(prefix='console-continuation-',dir=runs)).resolve()
    java=next((ROOT/'.tools').glob('jdk-21*/bin/java.exe'));classes=directory/'probe-classes';classes.mkdir()
    http,mongo=free_port(),free_port();nonce=str(time.time_ns())
    config=directory/'config.json';config.write_text(json.dumps(dict(
        httpServer=dict(bindAddress='127.0.0.1',bindPort=http),
        internalMongoServer=dict(address='127.0.0.1',port=mongo,filePath='./data/database.mv'))),'utf-8')
    control=directory/'console-native-control.json';control.write_text(json.dumps(dict(nonce=nonce)),'utf-8')
    fixed={str(p):sha(p) for p in [Path(__file__),JAVA_SOURCE,jar,config,control]}
    flags=subprocess.CREATE_NO_WINDOW if os.name=='nt' else 0
    cp=os.pathsep.join(map(str,[classes,jar]));normal_stops=0;forced_stops=0;snapshots=[]
    compiled=subprocess.run([str(java.with_name('javac.exe')),'--release','21','-encoding','UTF-8',
        '-cp',str(jar),'-d',str(classes),str(JAVA_SOURCE)],capture_output=True,creationflags=flags,timeout=60)
    (directory/'javac.log').write_bytes(compiled.stdout+compiled.stderr)
    if compiled.returncode:raise RuntimeError('Fixture preparation failed; no Native server started')
    first_uid=None
    try:
        for phase in range(2):
            log=directory/f'server-{phase}.log';row=dict(phase=phase,failuresObserved=0,commandsAfterFailure=0)
            snapshots.append(row)
            with log.open('xb') as writer:
                process=subprocess.Popen([str(java),'-Dfile.encoding=UTF-8',
                    '-Djdk.net.unixdomain.tmpdir='+str(directory/'tcp-fallback'),
                    '-Dio.netty.eventLoopThreads=2','-Dconsole.expectedProductionJar='+str(jar),
                    '-cp',cp,'org.hkrpg.server.HkrpgServer','--mode=dispatch','--no-handbook'],
                    cwd=directory,stdin=subprocess.PIPE,stdout=writer,stderr=subprocess.STDOUT,creationflags=flags)
                try:
                    deadline=time.monotonic()+50
                    while True:
                        if process.poll() is not None:raise AssertionError('Packaged Dispatch startup exited')
                        try:
                            if request(http,'/health/ready')['ready']:break
                        except (OSError,urllib.error.URLError):pass
                        if time.monotonic()>deadline:raise TimeoutError('Packaged Dispatch readiness')
                        time.sleep(.1)
                    login=request(http,'/hkrpg_global/mdk/shield/api/login',dict(account='console-local-probe',password='local-test'))
                    assert login['retcode']==0
                    uid=login['data']['account']['uid'];row['accountUid']=uid
                    if first_uid is None:first_uid=uid
                    else:assert uid==first_uid,'Same account was not restored after normal stop'
                    for count in [1,2]:
                        process.stdin.write(f'consolecontinuationfixture throw {nonce}\n'.encode());process.stdin.flush()
                        wait_marker(process,log,f'HKRPG_CONSOLE_FAILURE:{nonce}:{count}')
                        row['failuresObserved']+=1
                        row['httpReadyAfterFailure']=request(http,'/health/ready')['ready'];assert row['httpReadyAfterFailure']
                        row['serverAliveAfterFailure']=process.poll() is None;assert row['serverAliveAfterFailure']
                        process.stdin.write(f'consolecontinuationfixture observe {nonce}\n'.encode());process.stdin.flush()
                        wait_marker(process,log,f'HKRPG_CONSOLE_OBSERVED:{nonce}:{count}:{jar}')
                        row['commandsAfterFailure']+=1
                    process.stdin.write(b'stop\n');process.stdin.flush();process.wait(timeout=20)
                    assert process.returncode in (1000,232) and 'Stopping server' in log.read_text('utf-8',errors='replace')
                    normal_stops+=1;row['normalStop']=True
                finally:
                    if process.poll() is None:
                        process.terminate();forced_stops+=1
                        try:process.wait(timeout=15)
                        except subprocess.TimeoutExpired:process.kill();process.wait(timeout=5)
            assert fixed=={p:sha(Path(p)) for p in fixed},'Pinned Native input changed'
        result=dict(passed=True,nativeExecuted=True,artifactSHA256=sha(jar),runtimeDirectory=str(directory),
            normalStops=normal_stops,forcedStops=forced_stops,sameAccountRestart=True,gameServerStarted=False,
            productionOverrides=False,clientAccepted=False,snapshots=snapshots,inputs=fixed,
            actualKnownCommandFailureInjected=True,physicalTerminalSignalsTested=False)
        assert normal_stops==2 and forced_stops==0
    except BaseException as error:
        result=dict(passed=False,nativeExecuted=True,artifactSHA256=sha(jar),runtimeDirectory=str(directory),
            normalStops=normal_stops,forcedStops=forced_stops,snapshots=snapshots,
            error=repr(error),traceback=traceback.format_exc(),inputs=fixed,clientAccepted=False,productionOverrides=False)
        with (directory/'failed-console-native.json').open('x',encoding='utf-8') as f:json.dump(result,f,indent=2)
    with output.open('x',encoding='utf-8') as f:json.dump(result,f,indent=2)
    print(json.dumps({k:result[k] for k in ['passed','nativeExecuted','artifactSHA256','runtimeDirectory','normalStops','forcedStops']}))
    if not result['passed']:raise SystemExit(1)

if __name__=='__main__':main()
