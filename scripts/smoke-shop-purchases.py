"""独立 H2/KCP 商店购买、背包/外观查询、限购与同 UID 重启验证；不连接正式端口。"""
import argparse, base64, hashlib, http.cookiejar, importlib.util, json, os, socket, subprocess, tempfile, urllib.request, uuid
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
spec=importlib.util.spec_from_file_location('gm',ROOT/'scripts/smoke-gm-console.py')
gm=importlib.util.module_from_spec(spec); spec.loader.exec_module(gm)
spec=importlib.util.spec_from_file_location('fashion_data',ROOT/'scripts/client_skin_shop_data.py')
fashion_data=importlib.util.module_from_spec(spec); spec.loader.exec_module(fashion_data)

def varint(v):
 b=bytearray()
 while v>127:b.append((v&127)|128);v>>=7
 b.append(v);return bytes(b)
def wire(values):return b''.join(varint(k*8)+varint(v) for k,v in values)

def main():
 parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--output',type=Path,default=ROOT/'build/shop-purchases-native.json');parser.add_argument('--jar',type=Path,default=ROOT/'build/libs/hkrpg-server.jar');parser.add_argument('--fashion-only',action='store_true',help='只验证新增饰品和帕姆服装');args=parser.parse_args()
 jar=args.jar.resolve();digest=hashlib.sha256(jar.read_bytes()).hexdigest()
 java=next((ROOT/'.tools').glob('jdk-21*/bin/java.exe'))
 runs=ROOT/'build/smoke';runs.mkdir(parents=True, exist_ok=True)
 directory=Path(tempfile.mkdtemp(prefix='shop-purchases-',dir=runs));helpers=directory/'helpers';helpers.mkdir()
 flags=subprocess.CREATE_NO_WINDOW if os.name=='nt' else 0
 subprocess.run([str(java.with_name('javac.exe')),'-encoding','UTF-8','-cp',str(jar),'-d',str(helpers),str(ROOT/'scripts/fixtures/java/org/hkrpg/server/protocol/NativeGmPlayerProbe.java')],check=True,capture_output=True,creationflags=flags)
 ports=[gm.free_port(),gm.free_port(),gm.free_port(socket.SOCK_DGRAM)]
 (directory/'config.json').write_text(json.dumps({'protocol':{'wireVersion':'4.6.0'},'resourceDir':str(ROOT/'resources/4.6'),'httpServer':{'bindAddress':'127.0.0.1','bindPort':ports[0]},'gameServer':{'bindAddress':'127.0.0.1','bindPort':ports[2]},'internalMongoServer':{'address':'127.0.0.1','port':ports[1],'filePath':'./data/database.mv'}}),encoding='utf-8')
 base=f'http://127.0.0.1:{ports[0]}';csrf='';uid=None
 opener=urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
 account_name='shop_'+uuid.uuid4().hex+'@example.invalid';logs=[];results=[];server=probe=None
 jflags=['-Dfile.encoding=UTF-8','-Dio.netty.eventLoopThreads=2','-Djdk.net.unixdomain.tmpdir='+str(directory/'tcp-fallback')]
 def request(path,body=None):
  req=urllib.request.Request(base+path,data=None if body is None else json.dumps(body).encode(),headers={'Content-Type':'application/json','Origin':base,'X-GM-CSRF':csrf})
  with opener.open(req,timeout=30) as r:return json.load(r)
 def snapshot():return json.loads((directory/'snapshot.json').read_text('utf-8'))
 def stock(s,id):return next((r['num'] for r in s['bag'].get('material_list',[]) if r['tid']==id),0)
 def send(name,payload=b'',success=True):
  rid=str(uuid.uuid4());target=directory/'equipment-request.json';tmp=target.with_suffix('.tmp')
  tmp.write_text(json.dumps({'requestId':rid,'command':name,'wire':base64.b64encode(payload).decode(),'success':success}),encoding='utf-8');tmp.replace(target)
  s=gm.wait_for(lambda:(lambda x:x if x.get('equipmentReply',{}).get('requestId')==rid else None)(snapshot()),40);target.unlink()
  r=s['equipmentReply']['response'];assert (r.get('retcode',r.get('PLDKENJLKME',0))==0)==success,(name,r)
  results.append({'command':name,'success':success,'response':r});return r,s
 def grant(id,n):
  data=request('/gm/api/operate',{'requestId':str(uuid.uuid4()),'uid':uid,'action':'grant','itemId':id,'amount':n,'level':1 if id in (1415,1505,1409) else 0,'rank':0})['data'];assert data['state']=='completed',data
 def purchase(row,count=1,choices=(),success=True):
  before=snapshot();body=wire([(1,row['ShopID']),(3,count),(9,row['GoodsID']),(13,row.get('ItemID',0))])
  if choices:
   packed=b''.join(varint(v) for v in choices);body+=varint(34)+varint(len(packed))+packed
  r,s=send('BuyGoodsCsReq',body,success)
  if success:
   for currency,price in zip(row['CurrencyList'],row['CurrencyCostList']):
    key={1:'hcoin',2:'scoin',3:'mcoin'}.get(currency)
    old=before['basic'].get(key,0) if key else stock(before,currency)
    new=s['basic'].get(key,0) if key else stock(s,currency)
    assert new==old-count*price,(currency,old,new,price)
   assert r['return_item_list']['item_list']
  else:assert s['basic']==before['basic'] and s['bag']==before['bag']
  return r,s
 def stop(strict=True):
  nonlocal server,probe
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
  if strict:assert all(codes),codes
 goods={r['GoodsID']:r for r in json.loads((ROOT/'resources/4.6/ExcelOutput/ShopGoodsConfig.json').read_text('utf-8'))}
 for gid,skin,currency,price in [(107013,1141501,3,1680),(107015,1150501,1,2680),(107016,1140901,3,1350)]:
  goods[gid]={'GoodsID':gid,'ShopID':107,'ItemID':skin,'CurrencyList':[currency],'CurrencyCostList':[price]}
 for gid,item,shop,currency,price,_ in fashion_data.FASHION_ENTRIES:
  goods[gid]={'GoodsID':gid,'ShopID':shop,'ItemID':item,'CurrencyList':[currency],'CurrencyCostList':[price]}
 outfits={227001:1001,227002:1002,227003:1003,227005:1005,
          227007:1007,227008:1008,227015:4001,227017:1017}
 skin_ids=[];saved=None
 try:
  for cycle in range(2):
   log=(directory/f'server-{cycle}.log').open('w',encoding='utf-8');logs.append(log)
   server=subprocess.Popen([str(java),*jflags,'-Xmx1536m','-jar',str(jar),'--no-handbook'],cwd=directory,stdin=subprocess.PIPE,stdout=log,stderr=subprocess.STDOUT,creationflags=flags)
   gm.wait_for(lambda:server.poll() is None and request('/health/ready')['ready'])
   csrf=request('/gm/api/session',{})['data']['csrf']
   account=request('/hkrpg_cn/mdk/shield/api/login',{'account':account_name,'password':'disposable-shop-smoke'})['data']['account']
   combo=request('/hkrpg_cn/combo/granter/login/v2/login',{'data':json.dumps({'uid':account['uid'],'token':account['token']})})
   credentials=directory/'credentials.json';credentials.write_text(json.dumps({'accountUid':account['uid'],'comboToken':combo['data']['combo_token']}))
   (directory/'quit').unlink(missing_ok=True);(directory/'snapshot.json').unlink(missing_ok=True)
   log=(directory/f'probe-{cycle}.log').open('w',encoding='utf-8');logs.append(log)
   probe=subprocess.Popen([str(java),*jflags,'-cp',str(helpers)+os.pathsep+str(jar),'org.hkrpg.server.protocol.NativeGmPlayerProbe',str(ports[2]),str(credentials),str(directory)],cwd=directory,stdout=log,stderr=subprocess.STDOUT,creationflags=flags)
   initial=gm.wait_for(snapshot)
   if uid is None:uid=initial['uid']
   assert uid==initial['uid']
   if cycle==0:
    for id,n in [(3,30000),(1,20000),(2,10000),(263,10000),(284,1000),(252,300)]:grant(id,n)
    for id in [1415,1505,1409]:grant(id,1)
    gm.wait_for(lambda:snapshot()['basic'].get('mcoin',0)==30000 and stock(snapshot(),252)==300,30)
    for gid,item,shop,_,_,_ in fashion_data.FASHION_ENTRIES:
     row=request(f'/gm/api/shops?shopId={shop}&q={gid}')['data']['items']
     assert len(row)==1 and row[0]['itemId']==item and row[0]['listed'],row
     _,owned=purchase(goods[gid]);assert stock(owned,item)==1,(gid,item)
     purchase(goods[gid],success=False)
     if item in outfits:
      nested=wire([(1,outfits[item])]);send('SetPlayerOutfitCsReq',b'\x0a'+varint(len(nested))+nested)
    nested=wire([(1,1017),(1,4001)])
    send('SetPlayerOutfitCsReq',b'\x0a'+varint(len(nested))+nested)
    send('SelectPamSkinCsReq',wire([(1,252001)]))
    if not args.fashion_only:
     for gid,skin in [(107013,1141501),(107015,1150501),(107016,1140901)]:
      row=request(f'/gm/api/shops?shopId=107&q={gid}')['data']['items']
      assert len(row)==1 and row[0]['itemId']==skin and row[0]['listed'],row
     missing,_=send('DressAvatarSkinCsReq',wire([(3,1141501),(11,1415)]),success=False)
     assert missing['PLDKENJLKME']==1222,missing
     target=[{'shopId':107,'goodsId':107013}]
     for action in ['shop-unlist','shop-list']:
      result=request('/gm/api/operate',{'requestId':str(uuid.uuid4()),'action':action,'shopGoods':target})['data']
      assert result['state']=='completed',result
      if action=='shop-unlist':purchase(goods[107013],success=False)
     for gid,skin,avatar in [(107013,1141501,1415),(107015,1150501,1505),(107016,1140901,1409)]:
      purchase(goods[gid]);purchase(goods[gid],success=False)
      reply,current=send('DressAvatarSkinCsReq',wire([(3,skin),(11,avatar)]))
      paths=current['avatars'].get('avatar_path_data_info_list',[])
      assert any(p.get('avatar_id')==avatar and p.get('dressed_skin_id')==skin for p in paths),(avatar,skin,paths)
      _,current=send('TakeOffAvatarSkinCsReq',wire([(1,avatar)]))
      paths=current['avatars'].get('avatar_path_data_info_list',[])
      assert any(p.get('avatar_id')==avatar and p.get('dressed_skin_id',0)==0 for p in paths),(avatar,paths)
      send('DressAvatarSkinCsReq',wire([(3,skin),(11,avatar)]))
     purchase(goods[107012],success=False);purchase(goods[107014],success=False)
     wrong,_=send('DressAvatarSkinCsReq',wire([(3,1141501),(11,1409)]),success=False)
     assert wrong['PLDKENJLKME']==1225,wrong
     for id in [107002,107003,107004,107006,107011]:
      purchase(goods[id]);skin_ids.append(goods[id]['ItemID']);purchase(goods[id],success=False)
     for id in [108001,108002,108005,109001,109002,109003,109005]:purchase(goods[id])
     # 隔离账号直接发送原生购买请求；任务上架条件不是本次网络夹具的验收对象。
     book_reply,book_state=purchase(goods[301003])
     chapters=set(range(190901,190908))
     assert {row['item_id'] for row in book_reply['return_item_list']['item_list']}==chapters,book_reply
     assert stock(book_state,300200)==0 and all(stock(book_state,i)==1 for i in chapters)
     purchase(goods[301003],success=False)
     # 模拟旧服务已保存的礼包，验证实际持有时可使用且不再支付；重复使用失败。
     grant(300200,1)
     before_use=gm.wait_for(lambda:(lambda s:s if stock(s,300200)==1 else None)(snapshot()),30)
     used,book_state=send('UseItemCsReq',wire([(9,1),(12,300200)]))
     assert {row['item_id'] for row in used['return_data']['item_list']}==chapters,used
     assert used['use_item_count']==1 and used['use_item_id']==300200,used
     assert book_state['basic']==before_use['basic'] and stock(book_state,300200)==0
     assert all(stock(book_state,i)==2 for i in chapters)
     _,after_retry=send('UseItemCsReq',wire([(9,1),(12,300200)]),success=False)
     assert after_retry['basic']==book_state['basic'] and after_retry['bag']==book_state['bag']
     purchase(goods[900041],choices=[1202001],success=False)
     purchase(goods[900041],choices=[1202001,1202011,1202012,1202013])
     purchase(goods[999001],count=2);purchase(goods[999001],count=3)
     send('SetPersonalCardCsReq',wire([(1,253004)]));send('SelectPamSkinCsReq',wire([(1,252002)]))
   else:
    assert initial['basic']==saved['basic'],'Wallet changed after restart'
    assert initial['bag']==saved['bag'],'Inventory changed after restart'
    if not args.fashion_only:assert stock(initial,300200)==0 and all(stock(initial,i)==2 for i in range(190901,190908))
    assert initial['avatars']==saved['avatars'],'Skin/avatar data changed after restart'
    assert all(stock(initial,item)==1 for _,item,_,_,_,_ in fashion_data.FASHION_ENTRIES)
    if not args.fashion_only:purchase(goods[107002],success=False)
   if not args.fashion_only:
    board,_=send('GetPlayerBoardDataCsReq');assert board['current_personal_card_id']==253004
    assert 253004 in board['unlocked_personal_card_list'] and 253002 in board['unlocked_personal_card_list']
    pets,_=send('GetPetDataCsReq');assert 1003 in pets['unlocked_pet_id']
   pam,_=send('GetPamSkinDataCsReq')
   assert pam['cur_skin']==(252001 if args.fashion_only else 252002)
   assert 252001 in pam['unlock_skin_list']
   shops,saved=send('GetShopListCsReq',wire([(12,1)]))
   rows={g['goods_id']:g for sh in shops['shop_list'] for g in sh['goods_list']}
   assert all(rows[gid]['buy_times']==1 for gid,_,_,_,_,_ in fashion_data.FASHION_ENTRIES)
   assert set(saved['avatars']['DHKFCDAGHDM']['EAKOLIJOEPA'])=={1017,4001}
   if not args.fashion_only:
    assert rows[107002]['buy_times']==1 and rows[999001]['buy_times']==5
    assert all(rows[gid]['buy_times']==1 for gid in [107013,107015,107016])
    assert {1141501,1150501,1140901}.issubset(set(saved['avatars']['skin_list']))
   stop()
  assert hashlib.sha256(jar.read_bytes()).hexdigest()==digest
  args.output.parent.mkdir(exist_ok=True,parents=True)
  args.output.write_text(json.dumps({'passed':True,'jarSHA256':digest,'runtimeDirectory':str(directory),'uid':uid,'normalStops':2,'sameUidAfterRestart':True,'productionModified':False,'clientAccepted':False,'results':results,'snapshot':saved},ensure_ascii=False,indent=2),encoding='utf-8')
  print(json.dumps({'passed':True,'nativeRequests':len(results),'normalStops':2,'runtimeDirectory':str(directory)}))
 finally:
  stop(False)
  for f in logs:f.close()
  (directory/'credentials.json').unlink(missing_ok=True)
if __name__=='__main__':main()
