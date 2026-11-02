"""隔离 H2/KCP 检查快捷清单分类、实际发放、重复拒绝、请求重放和同 UID 重启。"""
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
import urllib.parse
import urllib.request
import uuid

ROOT=Path(__file__).resolve().parents[1]
spec=importlib.util.spec_from_file_location('gm',ROOT/'scripts/smoke-gm-console.py')
gm=importlib.util.module_from_spec(spec);spec.loader.exec_module(gm)


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--browser-pause',action='store_true')
    parser.add_argument('--output',type=Path,default=ROOT/'build/gm-shortcuts-native.json')
    args=parser.parse_args()
    jar=ROOT/'build/libs/hkrpg-server.jar';digest=hashlib.sha256(jar.read_bytes()).hexdigest()
    java=next((ROOT/'.tools').glob('jdk-21*/bin/java.exe'))
    runs=ROOT/'build/smoke';runs.mkdir(exist_ok=True)
    directory=Path(tempfile.mkdtemp(prefix='gm-shortcuts-',dir=runs));helpers=directory/'helpers';helpers.mkdir()
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
    account_name='shortcut_'+uuid.uuid4().hex+'@example.invalid';logs=[];operations=[];native=[];snapshots=[];stops=0

    def request(path,body=None):
        req=urllib.request.Request(base+path,data=None if body is None else json.dumps(body).encode(),
            headers={'Content-Type':'application/json','Origin':base,'X-GM-CSRF':csrf})
        with opener.open(req,timeout=30) as response:return json.load(response)

    def snapshot():return json.loads((directory/'snapshot.json').read_text('utf-8'))

    def send(name):
        rid=str(uuid.uuid4());path=directory/'equipment-request.json';temp=path.with_suffix('.tmp')
        temp.write_text(json.dumps({'requestId':rid,'command':name,'wire':base64.b64encode(b'').decode(),'success':True}),encoding='utf-8');temp.replace(path)
        result=gm.wait_for(lambda:(lambda s:s if s.get('equipmentReply',{}).get('requestId')==rid else None)(snapshot()),40)
        path.unlink();reply=result['equipmentReply']['response'];assert reply.get('retcode',0)==0,reply
        native.append({'command':name,'response':reply});return reply

    def grant(item,amount=1,success=True):
        body={'requestId':str(uuid.uuid4()),'action':'shortcut-grant','uid':uid,'itemId':item['item']['id'],
              'amount':amount,'level':1 if item['item']['maxLevel'] else 0,'rank':1 if item['item']['category']=='equipment' else 0}
        result=request('/gm/api/operate',body)['data']
        assert result['state']==('completed' if success else 'failed'),result
        if success:operations.append(body)
        return result

    def integers(value):
        if isinstance(value,dict):return [n for v in value.values() for n in integers(v)]
        if isinstance(value,list):return [n for v in value for n in integers(v)]
        return [value] if isinstance(value,int) else []

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

    catalog=[];selected={};saved=None;clothing_search={};stardust_selected={};stardust_items=[]
    try:
        for cycle in range(2):
            log=(directory/f'server-{cycle}.log').open('w',encoding='utf-8');logs.append(log)
            server=subprocess.Popen([str(java),*jflags,'-Xmx1536m','-jar',str(jar),'--no-handbook'],cwd=directory,
                stdin=subprocess.PIPE,stdout=log,stderr=subprocess.STDOUT,creationflags=flags)
            gm.wait_for(lambda:server.poll() is None and request('/health/ready')['ready'])
            csrf=request('/gm/api/session',{})['data']['csrf']
            if cycle==0:
                page=request('/gm/api/item-shortcuts?limit=100')['data'];total=page['total']
                for offset in range(0,total,100):catalog.extend(request(f'/gm/api/item-shortcuts?limit=100&offset={offset}')['data']['items'])
                assert len(catalog)==total and len({i['item']['id'] for i in catalog})==total
                by_id={i['item']['id']:i for i in catalog}
                chosen={'avatar':1102,'equipment':23001,'skin':1131001,'icon':200001,'bubble':220001,
                        'theme':221002,'case':254001,'outfit':227003,'pet':251001,'pam':252001,'card':253001,'medal':250601,'material':170}
                for kind,item_id in chosen.items():
                    assert by_id[item_id]['type']==kind and by_id[item_id]['grantable'],by_id[item_id]
                    selected[kind]=by_id[item_id]
                assert not by_id[226005]['grantable']
                for kind in ['music','mission']:
                    selected[kind]=next(i for i in catalog if i['type']==kind and i['grantable'])
                assert request('/gm/api/item-shortcuts?type=theme&tag=hidden&q=221002')['data']['total']==1
                clothing={i['item']['id'] for i in catalog if i['type'] in {'skin','outfit','pam'}}
                for word in ['服饰','服装','衣服']:
                    found=request('/gm/api/item-shortcuts?'+urllib.parse.urlencode({'q':word,'limit':100}))['data']
                    assert {i['item']['id'] for i in found['items']}==clothing,found
                    assert found['total']==len(clothing)
                    clothing_search[word]=found['total']
                skins={i['item']['id'] for i in catalog if i['type'] in {'skin','pam'}}
                found=request('/gm/api/item-shortcuts?'+urllib.parse.urlencode({'q':'皮肤','limit':100}))['data']
                assert {i['item']['id'] for i in found['items']}==skins,found
                clothing_search['皮肤']=found['total']
                for kind in ['skin','outfit','pam']:
                    found=request('/gm/api/item-shortcuts?'+urllib.parse.urlencode({'q':'服饰','type':kind,'limit':100}))['data']
                    assert {i['item']['id'] for i in found['items']}=={i['item']['id'] for i in catalog if i['type']==kind}
                found=request('/gm/api/item-shortcuts?'+urllib.parse.urlencode({'q':'三月七','type':'skin'}))['data']
                assert found['total']==1 and found['items'][0]['item']['id']==1100101,found
                assert '对应角色：三月七 #1001' in found['items'][0]['sources']
                clothing_search['三月七']=found['total']
                stardust_goods=request('/gm/api/shops?shopId=108&limit=100')['data']['items']
                stardust_items=sorted({i['itemId'] for i in stardust_goods})
                assert len(stardust_items)==21
                found=request('/gm/api/item-shortcuts?'+urllib.parse.urlencode({'q':'星尘之礼','limit':100}))['data']
                assert found['total']==21 and sorted(i['item']['id'] for i in found['items'])==stardust_items,found
                for goods in stardust_goods:
                    row=by_id[goods['itemId']]
                    assert row['grantable'] and any('商店目录：星尘之礼 #108' in s and f"商品 #{goods['goodsId']}" in s for s in row['sources']),row
                for kind in {by_id[item_id]['type'] for item_id in stardust_items}:
                    found=request('/gm/api/item-shortcuts?'+urllib.parse.urlencode({'q':'星尘之礼','type':kind,'limit':100}))['data']
                    assert {i['item']['id'] for i in found['items']}=={item_id for item_id in stardust_items if by_id[item_id]['type']==kind}
                for kind,item_id in {'outfit':229001,'pet':251003,'pam':252002,'icon':202038,'card':253002,'material':110101}.items():
                    assert by_id[item_id]['type']==kind
                    stardust_selected[kind]=by_id[item_id]
                (directory/'catalog.json').write_text(json.dumps(catalog,ensure_ascii=False,indent=2),encoding='utf-8')
            account=request('/hkrpg_cn/mdk/shield/api/login',{'account':account_name,'password':'disposable-shortcut'})['data']['account']
            combo=request('/hkrpg_cn/combo/granter/login/v2/login',{'data':json.dumps({'uid':account['uid'],'token':account['token']})})
            credentials=directory/'credentials.json';credentials.write_text(json.dumps({'accountUid':account['uid'],'comboToken':combo['data']['combo_token']}),encoding='utf-8')
            (directory/'quit').unlink(missing_ok=True);(directory/'snapshot.json').unlink(missing_ok=True)
            log=(directory/f'probe-{cycle}.log').open('w',encoding='utf-8');logs.append(log)
            probe=subprocess.Popen([str(java),*jflags,'-cp',str(helpers)+os.pathsep+str(jar),'org.hkrpg.server.protocol.NativeGmPlayerProbe',
                str(game),str(credentials),str(directory)],cwd=directory,stdout=log,stderr=subprocess.STDOUT,creationflags=flags)
            initial=gm.wait_for(snapshot)
            if uid is None:uid=initial['uid']
            assert uid==initial['uid']
            if saved:
                assert initial['bag']==saved['bag'] and initial['avatars']==saved['avatars']
            if cycle==0:
                grant(selected['theme'],2,False)
                for kind,item in selected.items():grant(item,3 if kind=='material' else 1)
                for kind,item in stardust_selected.items():grant(item,10 if kind=='material' else 1)
                unsupported=next(i for i in catalog if not i['grantable']);grant(unsupported,success=False)
                unknown=request('/gm/api/operate',{'requestId':str(uuid.uuid4()),'action':'shortcut-grant','uid':uid,'itemId':1,'amount':1})['data']
                assert unknown['state']=='failed'
            for body in operations:assert request('/gm/api/operate',body)['data']['state']=='completed'
            for kind in ['theme','skin','bubble','case','outfit','pet','pam','card','medal','music']:grant(selected[kind],success=False)
            for kind,item in stardust_selected.items():
                if kind!='material':grant(item,success=False)
            phone=send('GetPhoneDataCsReq');assert {220001,221002,254001}.issubset(integers(phone)),phone
            board=send('GetPlayerBoardDataCsReq');assert {200001,253001,202038,253002}.issubset(integers(board)),board
            pam=send('GetPamSkinDataCsReq');assert {252001,252002}.issubset(integers(pam)),pam
            pets=send('GetPetDataCsReq');assert {1001,1003}.issubset(integers(pets)),pets
            gm.wait_for(lambda:1131001 in integers(snapshot()['avatars']) and 1102 in integers(snapshot()['avatars']))
            current=snapshot();stock={r['tid']:r['num'] for r in current['bag'].get('material_list',[])}
            assert stock.get(170)==3 and stock.get(227003)==1
            assert stock.get(229001)==1 and stock.get(110101)==10
            assert any(r.get('tid')==23001 for r in current['bag'].get('equipment_list',[])),current['bag']
            if cycle==0 and args.browser_pause:
                ready={'base':base,'runtimeDirectory':str(directory),'uid':uid,'jarSHA256':digest}
                (ROOT/'build/gm-shortcuts-browser-ready.json').write_text(json.dumps(ready,indent=2),encoding='utf-8')
                print(json.dumps({'browserReady':True,**ready}),flush=True)
                gm.wait_for(lambda:(directory/'continue').exists(),2400)
            saved=snapshot();snapshots.append(saved);stop()
        assert hashlib.sha256(jar.read_bytes()).hexdigest()==digest
        report={'passed':True,'jarSHA256':digest,'runtimeDirectory':str(directory),'catalogEntries':len(catalog),
                'grantableEntries':sum(i['grantable'] for i in catalog),'typesTested':list(selected),'normalStops':stops,
                'sameUidRestart':True,'uid':uid,'operations':len(operations),'nativeRequests':len(native),'productionModified':False,
                'clothingSearch':clothing_search,'stardustItems':stardust_items,'stardustTypesTested':list(stardust_selected),
                'clientAccepted':False,'native':native,'snapshots':snapshots}
        args.output.parent.mkdir(exist_ok=True,parents=True);args.output.write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf-8')
        print(json.dumps({k:v for k,v in report.items() if k not in ['native','snapshots']}))
    finally:
        stop(False)
        for log in logs:log.close()
        (directory/'credentials.json').unlink(missing_ok=True)


if __name__=='__main__':main()
