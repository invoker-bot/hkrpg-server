"""隔离 KCP/H2 验证银鬃铁卫两场战斗，前置剧情状态为夹具，正式服务只读。"""
from pathlib import Path
import hashlib
import json
import os
import socket
import subprocess
import shutil
import tempfile
import time
import urllib.request
import uuid

ROOT = Path(__file__).resolve().parents[1]
import argparse
parser=argparse.ArgumentParser(description=__doc__)
parser.add_argument('--jar',type=Path,required=True)
parser.add_argument('--resources',type=Path,default=ROOT/'resources/4.6')
parser.add_argument('--output',type=Path,default=ROOT/'build/silvermane-loop-20261102/native.json')
parser.add_argument('--city-transfer',action='store_true',help='After synthetic victories, report the performance and wait for the original city transfer')
parser.add_argument('--belobog-round-trip',action='store_true',help='Also report the authored city talks and verify both Qlipoth Fort transfers in the same session')
args=parser.parse_args()
if args.belobog_round_trip: args.city_transfer=True
args.output=args.output.resolve();args.output.parent.mkdir(parents=True,exist_ok=True)
OUT = args.output.parent
FIXTURES=ROOT/'scripts/fixtures/java/org/hkrpg/server/protocol'
jar = args.jar.resolve()
local_jdk = next((ROOT / '.tools').glob('jdk-21*'), None)
java = str(local_jdk / 'bin/java.exe') if local_jdk and os.name == 'nt' else shutil.which('java')
javac = str(local_jdk / 'bin/javac.exe') if local_jdk and os.name == 'nt' else shutil.which('javac')
assert java and javac, 'Java 21 and javac are required'
sha = lambda path: hashlib.sha256(path.read_bytes()).hexdigest()
artifact = sha(jar)
runs = ROOT / 'build/smoke'
runs.mkdir(parents=True, exist_ok=True)
directory = Path(tempfile.mkdtemp(prefix='silvermane-', dir=runs))
(directory/'silvermane-fixture-marker').write_text('silvermane-isolated-20261102',encoding='utf-8')
classes = directory/'helpers'
classes.mkdir()
flags = subprocess.CREATE_NO_WINDOW if os.name == 'nt' else 0
compiled = subprocess.run([javac,'-encoding','UTF-8','-cp',str(jar),'-d',str(classes),
    str(FIXTURES/'NativeSilvermaneBattleProbe.java'),str(FIXTURES/'SilvermaneBattleFixture.java')],capture_output=True,creationflags=flags)
(directory/'javac.log').write_bytes(compiled.stdout+compiled.stderr)
assert compiled.returncode==0,directory/'javac.log'

def port(kind=socket.SOCK_STREAM):
    with socket.socket(type=kind) as s:
        s.bind(('127.0.0.1',0))
        return s.getsockname()[1]

http,mongo,game=port(),port(),port(socket.SOCK_DGRAM)
assert http!=18080 and mongo!=27079 and game!=23311
resource=args.resources.resolve()
assert resource.is_dir()
fixture=json.loads((ROOT/'src/test/resources/fixtures/silvermane-battle-result-4.6.json').read_text(encoding='utf-8'))
hashes={row['path']:sha(resource/row['path']) for row in fixture['sources']}
assert hashes=={row['path']:row['sha256'] for row in fixture['sources']}
if args.city_transfer:
    transfers=json.loads((ROOT/'src/test/resources/fixtures/story-transfers-4.6.json').read_bytes())
    for name in transfers['snowToCityProvenance']['paths']+['ExcelOutput/MapEntrance.json','ExcelOutput/MazePlane.json']:
        hashes[name]=sha(resource/name)
        assert hashes[name]==transfers['sourceSHA256'][name]
    if args.belobog_round_trip:
        for name in transfers['subsequentScenesProvenance']['paths']:
            hashes[name]=sha(resource/name)
            assert hashes[name]==transfers['sourceSHA256'][name]
config=dict(protocol=dict(wireVersion='4.6.0'),resourceDir=str(resource),
    httpServer=dict(bindAddress='127.0.0.1',bindPort=http),gameServer=dict(bindAddress='127.0.0.1',bindPort=game),
    internalMongoServer=dict(address='127.0.0.1',port=mongo,filePath='./data/database.mv'))
(directory/'config.json').write_text(json.dumps(config),encoding='utf-8')
config_hash=sha(directory/'config.json')
options=['-XX:ActiveProcessorCount=4','-Dio.netty.eventLoopThreads=2','-Dfile.encoding=UTF-8',
         f'-Djdk.net.unixdomain.tmpdir={directory/"tcp-fallback"}']
classpath=str(classes)+os.pathsep+str(jar)
process=None
logs=[]
stops=0
def request(route,body=None):
    req=urllib.request.Request(f'http://127.0.0.1:{http}'+route,
        data=None if body is None else json.dumps(body).encode(),headers={'Content-Type':'application/json'})
    with urllib.request.urlopen(req,timeout=4) as response:return json.load(response)

def start(index):
    global process
    log=(directory/f'server-{index}.log').open('w',encoding='utf-8');logs.append(log)
    process=subprocess.Popen([java,*options,'-Xmx1536m','-jar',str(jar),'--mode=both','--no-handbook'],
        cwd=directory,stdin=subprocess.PIPE,stdout=log,stderr=subprocess.STDOUT,creationflags=flags)
    deadline=time.monotonic()+100
    while True:
        assert process.poll() is None,directory/f'server-{index}.log'
        try:
            if request('/health/ready')['ready']:return
        except OSError:pass
        assert time.monotonic()<deadline,'Server startup timeout'
        time.sleep(.3)

def stop():
    global process,stops
    if process is None:return
    assert process.poll() is None,'Server exited unexpectedly'
    process.stdin.write(b'stop\n');process.stdin.flush();process.wait(timeout=45)
    assert process.returncode==(1000 if os.name=='nt' else 232),'Abnormal server stop'
    process.stdin.close();process=None;stops+=1

def probe(mode,credentials):
    run=subprocess.run([java,*options,'-cp',classpath,'NativeSilvermaneBattleProbe',str(game)],
        input=json.dumps(dict(credentials,mode=mode,cityTransfer=args.city_transfer,belobogRoundTrip=args.belobog_round_trip)).encode(),capture_output=True,timeout=125,creationflags=flags)
    (directory/f'probe-{mode}.log').write_bytes(run.stdout+run.stderr)
    assert run.returncode==0,directory/f'probe-{mode}.log'
    return json.loads(next(line.removeprefix('SILVERMANE_PROBE=') for line in run.stdout.decode('utf-8').splitlines() if line.startswith('SILVERMANE_PROBE=')))

def inspect(mode,uid,index):
    assert process is None,'Database must be offline'
    run=subprocess.run([java,*options,'-cp',classpath,'SilvermaneBattleFixture',str(directory),mode,str(uid)]+([('round-trip' if args.belobog_round_trip else 'city'),str(resource)] if args.city_transfer and mode=='inspect' else []),
        cwd=ROOT,capture_output=True,timeout=60,creationflags=flags)
    (directory/f'inspect-{index}.log').write_bytes(run.stdout+run.stderr)
    assert run.returncode==0,directory/f'inspect-{index}.log'
    return json.loads(next(line.removeprefix('SILVERMANE_INSPECT=') for line in run.stdout.decode('utf-8').splitlines() if line.startswith('SILVERMANE_INSPECT=')))

report=dict(passed=False,artifactSHA256=artifact,runtimeDirectory=str(directory),clientGameplayVerified=False,
    seededParentAndPrecedingObjectives=True,productionDataModified=False,cityTransfer=args.city_transfer,belobogRoundTrip=args.belobog_round_trip)
try:
    start(0)
    account=request('/hkrpg_cn/mdk/shield/api/login',dict(account='silvermane-'+uuid.uuid4().hex,password='owned-silvermane-fixture'))['data']['account']
    combo=request('/hkrpg_cn/combo/granter/login/v2/login',dict(data=json.dumps(dict(uid=account['uid'],token=account['token']))))
    credentials=dict(accountUid=account['uid'],comboToken=combo['data']['combo_token'])
    bootstrap=probe('bootstrap',credentials);uid=bootstrap['uid'];stop()
    seed=inspect('seed',uid,0)
    start(1);investigated=probe('battle',credentials);stop();persisted=inspect('inspect',uid,1)
    start(2);restored=probe('restored',credentials);stop();persisted_after_restart=inspect('inspect',uid,2)
    assert all(x['uid']==uid for x in (seed,investigated,persisted,restored,persisted_after_restart))
    assert persisted['credits']==persisted_after_restart['credits'],'Restart changed credits'
    assert all(sha(resource/name)==digest for name,digest in hashes.items())
    assert sha(jar)==artifact and sha(directory/'config.json')==config_hash
    report.update(passed=True,uid=uid,requests=sum(x['requests'] for x in (bootstrap,investigated,restored)),
        normalStops=stops,sourceFilesUnchanged=len(hashes),bootstrap=bootstrap,seed=seed,
        investigated=investigated,persisted=persisted,restored=restored,persistedAfterRestart=persisted_after_restart)
    print(json.dumps({k:v for k,v in report.items() if k not in ('bootstrap','seed','investigated','persisted','restored','persistedAfterRestart')}))
finally:
    try:
        if process is not None and process.poll() is None:stop()
    finally:
        for log in logs:log.close()
        args.output.write_text(json.dumps(report,indent=2)+'\n',encoding='utf-8')
