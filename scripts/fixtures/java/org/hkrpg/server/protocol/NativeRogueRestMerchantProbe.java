package org.hkrpg.server.protocol;

import com.google.gson.*;
import com.google.protobuf.*;
import emu.lunarcore.server.packet.*;
import io.netty.buffer.*;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import kcp.highway.*;

/** Original seven sites and explicit G22 server merchant over KCP; client menu entry remains unverified. */
public final class NativeRogueRestMerchantProbe {
  private record Frame(int command, byte[] body) {}
  private static final Map<Integer, Integer> ROOMS = Map.of(1,304,2,302,3,303,4,304,5,305,6,306,7,307);
  private final ProtocolBridge protocol = ProtocolBridge.forVersion("4.6.0");
  private final BlockingQueue<Frame> inbox = new LinkedBlockingQueue<>();
  private final AtomicReference<Throwable> failure = new AtomicReference<>();
  private final CompletableFuture<Ukcp> connected = new CompletableFuture<>();
  private final List<Map<String, Integer>> encounters = new ArrayList<>();
  private Ukcp channel;
  private int requests, finishNotifications, actionResults, rejected, credits;
  private JsonObject sources;

  private static Object field(DynamicMessage m, String name) {
    return m.getField(Objects.requireNonNull(m.getDescriptorForType().findFieldByName(name), name));
  }
  private static int number(DynamicMessage m, String name) { return (Integer)field(m,name); }
  private static DynamicMessage nested(DynamicMessage m, String name) { return (DynamicMessage)field(m,name); }
  private static boolean has(DynamicMessage m, String name) { return m.hasField(m.getDescriptorForType().findFieldByName(name)); }
  private static String enumeration(DynamicMessage m, String name) { return ((Descriptors.EnumValueDescriptor)field(m,name)).getName(); }
  private static void require(boolean ok, String why) { if (!ok) throw new IllegalStateException(why); }

  private void observe(Frame frame) throws Exception {
    if (frame.command()==protocol.clientCommand("SyncRogueFinishScNotify")) {
      var data=nested(protocol.clientSchema().parse("SyncRogueFinishScNotify",frame.body()),"NLEOMLNOKCC");
      require(number(data,"area_id")==110 && (Boolean)field(data,"is_win")
          && number(data,"GMIIMAHLIJF")==7 && number(data,"HEOMALBNJLL")==7,
          "Finish notification did not reflect all seven cleared sites");
      finishNotifications++;
    }
    if (frame.command()==protocol.clientCommand("SyncRogueCommonActionResultScNotify")) actionResults++;
    if (frame.command()==protocol.clientCommand("PlayerSyncScNotify")) {
      var notify=protocol.clientSchema().parse("PlayerSyncScNotify",frame.body());
      if (has(notify,"basic_info")) credits=number(nested(notify,"basic_info"),"scoin");
    }
  }

  private DynamicMessage current() throws Exception {
    var game=nested(call("GetRogueInfoCsReq"),"rogue_game_info");
    require(has(game,"CFDIKLBINHK"),"Saved run disappeared");
    return nested(game,"CFDIKLBINHK");
  }
  private DynamicMessage scene() throws Exception { return nested(call("GetCurSceneInfoCsReq"),"scene"); }
  private List<DynamicMessage> entities(DynamicMessage scene,String type) {
    var result=new ArrayList<DynamicMessage>();
    for (var group:(List<?>)field(scene,"entity_group_list"))
      for (var entity:(List<?>)field((DynamicMessage)group,"entity_list")) {
        var row=(DynamicMessage)entity;
        if (has(row,type)) result.add(row);
      }
    return result;
  }
  private Set<Integer> blessings(DynamicMessage current) {
    var result=new TreeSet<Integer>();
    for (var row:(List<?>)field(nested(current,"HABJHGPFGEB"),"CHCMEKAFKAF"))
      require(result.add(number((DynamicMessage)row,"buff_id")),"Duplicate acquired blessing");
    return result;
  }
  private int coins(DynamicMessage current) { return number(nested(current,"DHEKLJDBNNP"),"rogue_money"); }
  private void map(DynamicMessage current,int site,int cleared,boolean pending) {
    var map=nested(current,"COOOMDKGODF");
    require(number(map,"area_id")==110 && number(map,"map_id")==101
        && number(map,"ANHDBMOPGDC")==site && number(map,"HPKMGKPPELC")==ROOMS.get(site),"Wrong default route position");
    var assignments=new TreeMap<Integer,Integer>();
    for (var raw:(List<?>)field(map,"LLNICHDDIAN")) {
      var room=(DynamicMessage)raw;
      int id=number(room,"MAJKFIMPOOF");
      require(assignments.put(id,number(room,"room_id"))==null,"Duplicate site");
      String expected=id<=cleared ? "RogueRoomStatus_Finish" : id==site ? "RogueRoomStatus_Play"
          : id==site+1 && cleared==site && !pending ? "RogueRoomStatus_Unlock" : "RogueRoomStatus_Lock";
      require(enumeration(room,"CAGDNHCOCKM").equals(expected),"Wrong clearance or exit status for site "+id);
    }
    require(assignments.equals(ROOMS),"Production default room assignments changed");
    require(has(current,"FMIGOKIDKJL")==pending,"Pending action differs from exit status");
  }
  private void enter(int site) throws Exception {
    call("EnterRogueMapRoomCsReq",Map.of("MAJKFIMPOOF",site,"room_id",ROOMS.get(site)));
  }
  private void locked(int site) throws Exception {
    reject("EnterRogueMapRoomCsReq",Map.of("MAJKFIMPOOF",site,"room_id",ROOMS.get(site)));
  }

  private void fightAll(int site) throws Exception {
    int count=0;
    while (true) {
      var scene=scene();
      var monsters=entities(scene,"npc_monster");
      if (monsters.isEmpty()) break;
      require(++count<=3,"Room combat did not remove its actors");
      var entity=monsters.getFirst();
      int room=ROOMS.get(site), group=number(entity,"group_id");
      var source=sources.getAsJsonObject(Integer.toString(room)).getAsJsonObject(Integer.toString(group));
      require(source!=null && number(entity,"inst_id")==source.get("inst").getAsInt(),"Non-source monster placement");
      var npc=nested(entity,"npc_monster");
      JsonObject expected=null;
      for (var raw:source.getAsJsonArray("choices")) {
        var choice=raw.getAsJsonObject();
        if (choice.get("npc").getAsInt()==number(npc,"monster_id") && choice.get("stage").getAsInt()==number(npc,"event_id")) expected=choice;
      }
      require(expected!=null,"Monster is not from the original weighted content");
      int leader=number(scene,"leader_entity_id"), target=number(entity,"entity_id");
      var battle=nested(call("SceneCastSkillCsReq",Map.of("cast_entity_id",leader,"attacked_by_entity_id",leader,
          "skill_index",0,"hit_target_entity_id_list",List.of(target))),"battle_info");
      int battleId=number(battle,"battle_id"), stage=number(battle,"stage_id");
      require(battleId>0 && stage==expected.get("stage").getAsInt(),"Wrong original battle stage");
      var waves=(List<?>)field(battle,"monster_wave_list");
      var original=expected.getAsJsonArray("waves");
      require(waves.size()==original.size(),"Original wave count changed");
      for (int i=0;i<waves.size();i++) {
        var wave=(DynamicMessage)waves.get(i);
        var enemies=(List<?>)field(wave,"monster_list");
        var ids=original.get(i).getAsJsonArray();
        require(number(wave,"battle_stage_id")==stage && enemies.size()==ids.size(),"Wrong source wave");
        for(int j=0;j<ids.size();j++) require(number((DynamicMessage)enemies.get(j),"monster_id")==ids.get(j).getAsInt(),"Wrong source wave enemy");
      }
      var req=DynamicMessage.newBuilder(protocol.clientSchema().message("PVEBattleResultCsReq"));
      req.setField(req.getDescriptorForType().findFieldByName("battle_id"),battleId);
      req.setField(req.getDescriptorForType().findFieldByName("stage_id"),stage);
      var end=req.getDescriptorForType().findFieldByName("end_status");
      req.setField(end,end.getEnumType().findValueByName("BATTLE_END_WIN"));
      require(number(exchange("PVEBattleResultCsReq",req.build().toByteArray()),"retcode")==0,"Native battle settlement failed");
      require(number(exchange("PVEBattleResultCsReq",req.build().toByteArray()),"retcode")!=0,"Repeated battle settlement succeeded");
      rejected++;
      require(entities(scene(),"npc_monster").stream().noneMatch(e->number(e,"entity_id")==target),"Defeated scene actor survived");
      encounters.add(Map.of("site",site,"room",room,"group",group,"stage",stage,"battle",battleId));
    }
    require(count==(site==1 || site==4 ? 3:1),"Missing actual route combats");
    consumeMenus();
  }
  private DynamicMessage choiceRequest(int batch,int buff,boolean reroll) {
    var req=DynamicMessage.newBuilder(protocol.clientSchema().message("OFEDNNJIJFL"));
    req.setField(req.getDescriptorForType().findFieldByName("MPLGEEGOAMC"),batch);
    var fd=req.getDescriptorForType().findFieldByName(reroll?"KBNLGEMNDEN":"DLKIKBIDJDB");
    var choice=DynamicMessage.newBuilder(fd.getMessageType());
    if (!reroll) choice.setField(choice.getDescriptorForType().findFieldByName("BAOFIGFFMLN"),buff);
    req.setField(fd,choice.build());
    return req.build();
  }
  private DynamicMessage acquire(int batch,int buff,boolean success) throws Exception {
    var request=choiceRequest(batch,buff,false);
    var response=protocol.clientSchema().parse("HandleRogueCommonPendingActionScRsp",
        raw(5676,request.toByteArray(),protocol.clientCommand("HandleRogueCommonPendingActionScRsp")).body());
    requests++;
    require((number(response,"retcode")==0)==success,"Unexpected pending action result");
    if(success) require(number(response,"queue_position")==batch,"Missing removed-action acknowledgement");
    else rejected++;
    return response;
  }
  private int[] pending(DynamicMessage current) {
    require(has(current,"FMIGOKIDKJL"),"Missing event reward menu");
    var action=nested(current,"FMIGOKIDKJL");
    var menu=nested(nested(action,"detail"),"PCHGEGFBKLF");
    var rows=(List<?>)field(menu,"LEOFNINDJDB");
    require(!rows.isEmpty(),"Empty blessing menu");
    var first=(DynamicMessage)rows.getFirst();
    require(number(first,"HAGDKKBAAKK")==1,"Event is not a base-level blessing");
    return new int[]{number(action,"queue_position"),number(first,"buff_id"),rows.size()};
  }
  private void consumeMenus() throws Exception {
    for(int attempt=0;attempt<4;attempt++) {
      var current=current();
      if (!has(current,"FMIGOKIDKJL")) return;
      var menu=pending(current);
      acquire(menu[0],menu[1],true);
    }
    throw new IllegalStateException("Menu confirmation did not terminate");
  }
  private int event() throws Exception {
    var rows=(List<?>)field(call("GetRogueCommonDialogueDataCsReq"),"dialogue_list");
    require(rows.size()==1,"Original event actor missing");
    var row=(DynamicMessage)rows.getFirst();
    require(number(nested(nested(row,"owner"),"MFAEGBJBJBE"),"OEANPIHCHHF")==40398,"Wrong source event");
    return number(row,"AIAFEKGBFIF");
  }
  private DynamicMessage choose(int unique,int option,boolean success) throws Exception {
    var response=exchange("SelectRogueCommonDialogueOptionCsReq",build("SelectRogueCommonDialogueOptionCsReq",
        Map.of("AIAFEKGBFIF",unique,"HAFOKMHCGFM",option)));
    require((number(response,"retcode")==0)==success,"Unexpected authored option result");
    if(!success) rejected++;
    return response;
  }
  private void verifyPendingEvent(int unique,int batch,int buff,int money) throws Exception {
    require(event()==unique,"Dialogue lease identity changed across reconnect");
    var current=current();
    map(current,5,4,true);
    require(coins(current)==money && !blessings(current).contains(buff),"Pending reward was already acquired");
    require(Arrays.equals(pending(current),new int[]{batch,buff,1}),"Frozen event draw changed");
    locked(6);
    choose(unique,14102,false);
    var response=protocol.clientSchema().parse("HandleRogueCommonPendingActionScRsp",
        raw(5676,choiceRequest(batch,buff,true).toByteArray(),protocol.clientCommand("HandleRogueCommonPendingActionScRsp")).body());
    requests++;
    require(number(response,"retcode")!=0,"Event draw could be rerolled");
    rejected++;
  }
  private Set<Integer> miracles(DynamicMessage current) {
    var result=new TreeSet<Integer>();
    for (var raw:(List<?>)field(nested(nested(current,"DNPCCFFFBHN"),"DNPCCFFFBHN"),"MAJGMMCOCIG"))
      require(result.add(number((DynamicMessage)raw,"LJCEOGPOGBE")),"Duplicate current curio");
    return result;
  }
  private Set<Integer> handbook() throws Exception {
    var result=new TreeSet<Integer>();
    for (var raw:(List<?>)field(nested(call("GetRogueHandbookDataCsReq"),"HAMKPLCBEBG"),"MAJGMMCOCIG"))
      require(result.add(number((DynamicMessage)raw,"NPNMLPNBHDP")),"Duplicate handbook curio");
    return result;
  }
  private int merchant() throws Exception {
    var scene=scene(); require(number(scene,"entry_id")==8000103,"G22 merchant is outside original entrance");
    var matches=entities(scene,"npc").stream().filter(row->number(row,"group_id")==22 && number(row,"inst_id")==400001
        && number(nested(row,"npc"),"npc_id")==1013).toList();
    require(matches.size()==1,"Original G22 source actor is missing or duplicated");
    return number(matches.getFirst(),"entity_id");
  }
  private DynamicMessage shop(int entity,int id,boolean success) throws Exception {
    var buffer=new java.io.ByteArrayOutputStream(); var out=CodedOutputStream.newInstance(buffer);
    out.writeUInt32(12,entity); if(id>0) out.writeUInt32(6,id); out.flush();
    String response=id>0?"BuyRogueShopMiracleScRsp":"GetRogueShopMiracleInfoScRsp";
    requests++;
    var message=protocol.clientSchema().parse(response,raw(id>0?5656:5675,buffer.toByteArray(),protocol.clientCommand(response)).body());
    require(message.getUnknownFields().asMap().isEmpty(),"Unknown native shop response fields");
    require((number(message,"retcode")==0)==success,"Unexpected source merchant status: entity="+entity+", miracle="+id+", retcode="+number(message,"retcode"));
    if(!success) rejected++;
    return message;
  }
  private Map<Integer,Integer> quotes(DynamicMessage response,int selected,boolean purchased) {
    var result=new TreeMap<Integer,Integer>();
    for(var raw:(List<?>)field(nested(response,"DNPCCFFFBHN"),"MAJGMMCOCIG")) {
      var row=(DynamicMessage)raw; int id=number(row,"LJCEOGPOGBE");
      var cost=nested(row,"cost_data");
      var items=(List<?>)field(cost,"item_list"); require(items.size()==1,"Unexpected merchant currencies");
      var pile=nested((DynamicMessage)items.getFirst(),"pile_item");
      require(number(pile,"item_id")==31 && number(pile,"item_num")==200,"Configured server quote changed");
      require(result.put(id,number(pile,"item_num"))==null,"Duplicate merchant offer");
      boolean bought=purchased && id==selected;
      require((Boolean)field(row,"BELPGDAGFIH")==bought,"Merchant ownership differs from the acquired curio");
      boolean unavailable=(Boolean)field(row,"GCIHGKCJOGB");
      require(purchased ? !bought || unavailable : !unavailable,"Merchant availability differs from the receipt phase");
    }
    require(!result.isEmpty() && result.size()<=3,"Unsupported or empty merchant stock");
    return result;
  }
  private Map<String,Object> execute(JsonObject credentials) throws Exception {
    String mode=credentials.get("mode").getAsString();
    require(Set.of("start","stock","purchase","bought","finish","finished").contains(mode),"Unknown merchant mode");
    sources=credentials.getAsJsonObject("actors");
    int uid=number(call("PlayerGetTokenCsReq",Map.of("account_uid",credentials.get("accountUid").getAsString(),
        "token",credentials.get("comboToken").getAsString(),"platform",1)),"uid");
    credits=number(nested(call("PlayerLoginCsReq"),"basic_info"),"scoin"); call("PlayerLoginFinishCsReq");
    int unique=mode.equals("start")?0:credentials.get("unique").getAsInt();
    int batch=mode.equals("start")?0:credentials.get("batch").getAsInt();
    int buff=mode.equals("start")?0:credentials.get("buff").getAsInt();
    int selected=mode.equals("start")?0:credentials.get("selected").getAsInt();
    int before=mode.equals("start")?0:credentials.get("beforePurchaseCoins").getAsInt();
    var expectedStock=new TreeMap<Integer,Integer>();
    if(!mode.equals("start")) credentials.getAsJsonObject("stock").entrySet().forEach(e->expectedStock.put(Integer.parseInt(e.getKey()),e.getValue().getAsInt()));
    var unlocks=credentials.getAsJsonObject("handbookUnlocks");
    boolean bought=Set.of("purchase","bought","finish","finished").contains(mode);
    int entity=0;
    if(mode.equals("start")) {
      call("StartRogueCsReq",Map.of("area_id",110,"NOEPBNMJFGL",List.of(emu.lunarcore.GameConstants.TRAILBLAZER_AVATAR_ID)));
      map(current(),1,0,false); locked(2);
      fightAll(1); map(current(),1,1,false); enter(2); fightAll(2); map(current(),2,2,false);
      enter(3); map(current(),3,3,false);
      var third=entities(scene(),"npc").stream().filter(row->number(row,"group_id")==34 && number(row,"inst_id")==400001).toList();
      require(third.size()<=1,"Duplicated tutorial merchant");
      if(!third.isEmpty()) shop(number(third.getFirst(),"entity_id"),0,false);
      enter(4); fightAll(4); map(current(),4,4,false); enter(5); map(current(),5,4,false);
      unique=event(); choose(unique,14102,true);
      var pending=pending(current()); batch=pending[0]; buff=pending[1]; acquire(batch,buff,true);
      require(blessings(current()).contains(buff),"Original 40398 blessing was not acquired");
      enter(6); map(current(),6,6,false); choose(unique,14101,false);
      require(miracles(current()).isEmpty() && handbook().isEmpty(),"Event invented a curio before the server merchant");
      entity=merchant(); shop(entity,4,false); shop(entity+10000,0,false);
      expectedStock.putAll(quotes(shop(entity,0,true),0,false));
      selected=expectedStock.keySet().stream().filter(id->id!=21)
          .min(Comparator.comparingInt(id->unlocks.get(Integer.toString(id)).getAsInt()>0?0:1)).orElseThrow();
      before=coins(current()); require(before>=200,"Original route has insufficient fragments for configured purchase");
      call("LeaveRogueCsReq"); shop(entity,0,false); shop(entity,selected,false);
      call("EnterRogueCsReq",Map.of("area_id",110)); entity=merchant();
      require(expectedStock.equals(quotes(shop(entity,0,true),0,false)),"Paused stock changed");
    } else if(!mode.equals("finished")) {
      var current=current(); map(current,6,6,false); require(blessings(current).contains(buff),"Original event reward disappeared");
      entity=merchant();
      boolean alreadyPurchased=!mode.equals("stock") && !mode.equals("purchase");
      require(expectedStock.equals(quotes(shop(entity,0,true),selected,alreadyPurchased)),"Frozen stock changed across KCP or H2 restart");
      require(miracles(current).equals(alreadyPurchased?Set.of(selected):Set.of()),"Current curio ownership differs from receipt phase");
      require(coins(current)==before-(alreadyPurchased?200:0),"Merchant wallet does not match persisted purchase");
      if(mode.equals("purchase")) {
        shop(entity+10000,selected,false);
        var response=shop(entity,selected,true);
        require(expectedStock.equals(quotes(response,selected,true)),"Purchase response changed stock or cost");
        require(coins(current())==before-200 && miracles(current()).equals(Set.of(selected)),"Actual purchase did not debit and acquire once");
      }
      if(bought) {
        shop(entity,selected,false);
        require(coins(current())==before-200,"Duplicate purchase changed fragments");
        final int chosen=selected;
        int other=expectedStock.keySet().stream().filter(id->id!=chosen).findFirst().orElse(0);
        if(other>0 && before-200<200) shop(entity,other,false);
        int handbook=unlocks.get(Integer.toString(selected)).getAsInt();
        require(handbook().equals(handbook>0?Set.of(handbook):Set.of()),"Curio handbook differs from explicit source unlock");
      }
      if(mode.equals("finish")) {
        enter(7); fightAll(7); map(current(),7,7,false);
        require(finishNotifications==1,"Missing or duplicated seven-site finish presentation");
        call("LeaveRogueCsReq"); shop(entity,selected,false);
      }
    } else {
      var current=current(); map(current,7,7,false);
      require(enumeration(current,"status").equals("RogueStatus_Finish") && (Boolean)field(current,"ILOFGCAFHAH")
          && blessings(current).contains(buff) && miracles(current).equals(Set.of(selected)),"Completed original route lost its purchased curio");
      require(number(scene(),"entry_id")!=8000103,"Final external scene did not restore");
      acquire(batch,buff,false); choose(unique,14102,false); shop(1,selected,false);
    }
    var state=current();
    var result=new LinkedHashMap<String,Object>();
    result.put("uid",uid); result.put("passed",true); result.put("mode",mode);
    result.put("unique",unique); result.put("batch",batch); result.put("buff",buff); result.put("selected",selected);
    result.put("stock",expectedStock); result.put("beforePurchaseCoins",before); result.put("currentCoins",coins(state));
    result.put("blessings",blessings(state)); result.put("miracles",miracles(state)); result.put("handbook",handbook()); result.put("credits",credits);
    result.put("encounters",encounters); result.put("merchantEntity",entity); result.put("finishNotifications",finishNotifications);
    result.put("rejectedDuplicateOrBlockedActions",rejected); result.put("requests",requests);
    result.put("eventRewardComplete",true); result.put("originalG22Merchant",true); result.put("merchantPurchased",bought);
    result.put("clientMenuEntryVerified",false); result.put("allSevenRoomsPlayed",mode.equals("finish") || mode.equals("finished"));
    result.put("clientGameplayVerified",false); return result;
  }
  private byte[] build(String name,Map<String,Object> fields) {
    var builder=DynamicMessage.newBuilder(protocol.clientSchema().message(name));
    fields.forEach((key,value)->builder.setField(Objects.requireNonNull(builder.getDescriptorForType().findFieldByName(key),key),value));
    return builder.build().toByteArray();
  }
  private DynamicMessage exchange(String name,byte[] body) throws Exception {
    requests++;
    String response=name.replace("CsReq","ScRsp");
    var m=protocol.clientSchema().parse(response,raw(protocol.clientCommand(name),body,protocol.clientCommand(response)).body());
    require(m.getUnknownFields().asMap().isEmpty(),"Unknown native closure response fields");
    return m;
  }
  private DynamicMessage call(String name,Map<String,Object> fields) throws Exception {
    var response=exchange(name,build(name,fields));
    require(number(response,"retcode")==0,"Unexpected "+name+" status "+number(response,"retcode"));
    return response;
  }
  private DynamicMessage call(String name) throws Exception { return call(name,Map.of()); }
  private void reject(String name,Map<String,Object> fields) throws Exception {
    require(number(exchange(name,build(name,fields)),"retcode")!=0,"Blocked route action succeeded"); rejected++;
  }
  private Frame raw(int command,byte[] payload,int response) throws Exception {
    var bytes=Unpooled.wrappedBuffer(PacketCodec.encode(command,new byte[0],payload));
    try { channel.write(bytes); } finally { bytes.release(); }
    long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(20);
    while(System.nanoTime()<deadline) {
      if(failure.get()!=null) throw new IllegalStateException("Native seven-site connection failed",failure.get());
      var frame=inbox.poll(1,TimeUnit.SECONDS);
      if(frame==null) continue;
      observe(frame); if(frame.command()==response) return frame;
    }
    throw new TimeoutException("Response "+response);
  }
  public static void main(String[] args) {
    var probe=new NativeRogueRestMerchantProbe();
    var client=new KcpClient(); int exit=1;
    try {
      var reader=new java.io.BufferedReader(new InputStreamReader(System.in,StandardCharsets.UTF_8));
      var credentials=JsonParser.parseString(reader.readLine()).getAsJsonObject();
      var config=new ChannelConfig(); config.nodelay(true,40,2,true); config.setMtu(1400);
      config.setSndwnd(256); config.setRcvwnd(256); config.setUseConvChannel(true);
      config.setConv(ThreadLocalRandom.current().nextInt(1,Integer.MAX_VALUE)); config.setTimeoutMillis(25000); config.setAckNoDelay(true);
      client.init(config,new KcpListener() {
        public void onConnected(Ukcp channel) { probe.connected.complete(channel); }
        public void handleReceive(ByteBuf bytes,Ukcp channel) {
          try {
            while(bytes.isReadable()) {
              var frame=PacketCodec.decodeNext(bytes); require(frame!=null,"Truncated native closure frame");
              probe.inbox.add(new Frame(frame.cmdId(),frame.body()));
            }
          } catch(Throwable error) { probe.failure.set(error); }
        }
        public void handleException(Throwable error,Ukcp channel) { probe.failure.set(error); probe.connected.completeExceptionally(error); }
        public void handleClose(Ukcp channel) { probe.failure.compareAndSet(null,new IllegalStateException("Native closure disconnected")); }
      });
      client.connect(new InetSocketAddress("127.0.0.1",Integer.parseInt(args[0])),config);
      probe.channel=probe.connected.get(10,TimeUnit.SECONDS);
      System.out.write((new Gson().toJson(probe.execute(credentials))+"\n").getBytes(StandardCharsets.UTF_8));
      System.out.flush(); exit=0;
    } catch(Exception error) { error.printStackTrace(System.err); }
    finally { client.stop(); }
    System.exit(exit);
  }
}
