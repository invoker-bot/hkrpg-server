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

/** Native default seven-site route and normal H2 restart; no official client UI. */
public final class NativeRogueFirstWorldClosureProbe {
  private record Frame(int command, byte[] header, byte[] body) {}
  private static final Map<Integer, Integer> ROOMS = Map.of(1,304,2,302,3,303,4,304,5,305,6,306,7,307);
  private final ProtocolBridge protocol = ProtocolBridge.forVersion("4.6.0");
  private final BlockingQueue<Frame> inbox = new LinkedBlockingQueue<>();
  private final AtomicReference<Throwable> failure = new AtomicReference<>();
  private final CompletableFuture<Ukcp> connected = new CompletableFuture<>();
  private final List<Map<String, Integer>> encounters = new ArrayList<>();
  private Ukcp channel;
  private int requests, finishNotifications, actionResults, rejected, credits;
  private final Map<Integer, DynamicMessage> dialogueCache = new LinkedHashMap<>();
  private int dialogueNotifications;
  private int finishAcknowledgements, finishRejections;
  private int finishPacketIdentity = Integer.MIN_VALUE, finishCorrelatedReplies;
  private int pendingReplies, pendingCorrelatedReplies;
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
    if (frame.command()==protocol.clientCommand("SyncRogueCommonDialogueDataScNotify")) {
      var notify=protocol.clientSchema().parse("SyncRogueCommonDialogueDataScNotify",frame.body());
      require(notify.getUnknownFields().asMap().isEmpty(),"Unknown dialogue notification fields");
      dialogueCache.clear();
      for(var raw:(List<?>)field(notify,"dialogue_list")) {
        var row=(DynamicMessage)raw;
        require(number(row,"AIAFEKGBFIF")>0
            && dialogueCache.put(number(row,"AIAFEKGBFIF"),row)==null,"Duplicate event cache identity");
      }
      dialogueNotifications++;
    }
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
    // Check before GetRogueInfo or any explicit dialogue query could hide the missing push.
    if(site==5) event();
    else require(dialogueCache.isEmpty(),"Old event options survived a room transition");
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
    var rows=new ArrayList<>(dialogueCache.values());
    require(rows.size()==1,"Original event actor missing");
    var row=rows.getFirst();
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
  private void finish(int unique,boolean success) throws Exception {
    requests++;
    var response=protocol.clientSchema().parse("LPDPAJGFBMM",
        raw(5646,build("BIHACLHMAKO",Map.of("AIAFEKGBFIF",unique)),5631).body());
    require(response.getUnknownFields().asMap().isEmpty(),"Unknown event-finish acknowledgement fields");
    require((number(response,"retcode")==0)==success,"Unexpected event-finish acknowledgement");
    if(success) finishAcknowledgements++;
    else { finishRejections++; rejected++; }
  }
  private void verifyPendingEvent(int unique,int batch,int buff,int money) throws Exception {
    require(event()==unique,"Dialogue lease identity changed across reconnect");
    finish(unique,true);
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
  private void verifyAcquiredEvent(int unique,int batch,int buff,int money) throws Exception {
    require(event()==unique,"Acquired event identity changed across restart");
    var current=current(); map(current,5,5,false);
    require(coins(current)==money && blessings(current).contains(buff),"Saved event reward was lost or repeated");
    acquire(batch,buff,false);
    choose(unique,14102,false);
  }
  private Map<String,Object> execute(JsonObject credentials) throws Exception {
    String mode=credentials.get("mode").getAsString();
    require(Set.of("start","pending","acquired","resume","finished").contains(mode),"Unknown closure mode");
    sources=credentials.getAsJsonObject("actors");
    int uid=number(call("PlayerGetTokenCsReq",Map.of("account_uid",credentials.get("accountUid").getAsString(),
        "token",credentials.get("comboToken").getAsString(),"platform",1)),"uid");
    credits=number(nested(call("PlayerLoginCsReq"),"basic_info"),"scoin");
    call("PlayerLoginFinishCsReq");
    call("GetRogueInfoCsReq");
    int unique=mode.equals("start")?0:credentials.get("unique").getAsInt();
    int batch=mode.equals("start")?0:credentials.get("batch").getAsInt();
    int buff=mode.equals("start")?0:credentials.get("buff").getAsInt();
    int money=mode.equals("start")?0:credentials.get("coins").getAsInt();
    if(mode.equals("start")) {
      call("StartRogueCsReq",Map.of("area_id",110,"NOEPBNMJFGL",List.of(emu.lunarcore.GameConstants.TRAILBLAZER_AVATAR_ID)));
      map(current(),1,0,false);
      locked(2);
      fightAll(1); map(current(),1,1,false);
      enter(2); fightAll(2); map(current(),2,2,false);
      enter(3); map(current(),3,3,false);
      enter(4); fightAll(4); map(current(),4,4,false);
      enter(5); map(current(),5,4,false);
      unique=event();
      finish(unique,false);
      var row=dialogueCache.values().iterator().next();
      var options=(List<?>)field(row,"KFNMIEMBJGD");
      require(options.size()==3,"Original three options changed");
      for(var raw:options) require((Boolean)field((DynamicMessage)raw,"is_valid"),"Default recipe did not make the original choices usable");
      money=coins(current());
      choose(unique,14102,true);
      var pending=pending(current()); batch=pending[0]; buff=pending[1];
      verifyPendingEvent(unique,batch,buff,money);
      call("LeaveRogueCsReq");
      require(dialogueCache.isEmpty(),"Paused event options remained in the client cache");
      finish(unique,false);
      acquire(batch,buff,false);
      call("EnterRogueCsReq",Map.of("area_id",110));
      require(event()==unique,"Resume did not push the saved event identity");
      verifyPendingEvent(unique,batch,buff,money);
    } else if(mode.equals("pending") || mode.equals("acquired")) {
      verifyPendingEvent(unique,batch,buff,money);
      if(mode.equals("acquired")) {
        acquire(batch,buff,true);
        require(blessings(current()).contains(buff),"Confirmed event blessing not acquired");
        map(current(),5,5,false);
        verifyAcquiredEvent(unique,batch,buff,money);
      }
    } else if(mode.equals("resume")) {
        verifyAcquiredEvent(unique,batch,buff,money);
        finish(unique,true);
        finish(unique,true);
        verifyAcquiredEvent(unique,batch,buff,money);
        enter(6); map(current(),6,6,false);
        finish(unique,false);
        choose(unique,14101,false);
        enter(7); fightAll(7);
        var current=current(); map(current,7,7,false);
        require(enumeration(current,"status").equals("RogueStatus_Finish") && (Boolean)field(current,"ILOFGCAFHAH"),"Boss victory did not finish the run");
        require(finishNotifications==1,"Missing or repeated complete-run presentation");
        call("GetRogueInfoCsReq");
        call("LeaveRogueCsReq");
    } else {
      var current=current(); map(current,7,7,false);
      require(enumeration(current,"status").equals("RogueStatus_Finish") && (Boolean)field(current,"ILOFGCAFHAH")
          && blessings(current).contains(buff),"Completed run did not restore");
      acquire(batch,buff,false); choose(unique,14102,false);
      finish(unique,false);
      require(dialogueCache.isEmpty(),"Old event NPC leaked outside the run");
      require(number(scene(),"entry_id")!=8000103,"Terminal leave location did not restore");
    }
    var state=current();
    var result=new LinkedHashMap<String,Object>();
    result.put("uid",uid); result.put("passed",true); result.put("mode",mode);
    result.put("unique",unique); result.put("batch",batch); result.put("buff",buff); result.put("coins",money);
    result.put("currentCoins",coins(state)); result.put("blessings",blessings(state)); result.put("credits",credits);
    result.put("encounters",encounters); result.put("finishNotifications",finishNotifications); result.put("actionResults",actionResults);
    result.put("rejectedDuplicateOrBlockedActions",rejected); result.put("requests",requests);
    result.put("allSevenRoomsPlayed",mode.equals("resume") || mode.equals("finished"));
    result.put("dialogueNotifications",dialogueNotifications);
    result.put("dialogueQueryRequests",0);
    result.put("passiveDialogueCacheVerified",true);
    result.put("finishAcknowledgements",finishAcknowledgements);
    result.put("finishRejections",finishRejections);
    result.put("finishCorrelatedReplies",finishCorrelatedReplies);
    result.put("pendingReplies",pendingReplies);
    result.put("pendingCorrelatedReplies",pendingCorrelatedReplies);
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
    byte[] header=new byte[0];
    if(command==5646 || command==5676) {
      var output=new java.io.ByteArrayOutputStream();
      var wire=CodedOutputStream.newInstance(output);
      wire.writeUInt32(1,++finishPacketIdentity); wire.flush(); header=output.toByteArray();
    }
    var bytes=Unpooled.wrappedBuffer(PacketCodec.encode(command,header,payload));
    try { channel.write(bytes); } finally { bytes.release(); }
    long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(20);
    while(System.nanoTime()<deadline) {
      if(failure.get()!=null) throw new IllegalStateException("Native seven-site connection failed",failure.get());
      var frame=inbox.poll(1,TimeUnit.SECONDS);
      if(frame==null) continue;
      observe(frame);
      if(frame.command()==response) {
        if(command==5646) {
          require(Arrays.equals(header,frame.header()),"End acknowledgement lost the click packet identity");
          finishCorrelatedReplies++;
        }
        if(command==5676) {
          pendingReplies++;
          require(Arrays.equals(header,frame.header()),"Pending reward reply lost the click packet identity");
          pendingCorrelatedReplies++;
        }
        return frame;
      }
    }
    throw new TimeoutException("Response "+response);
  }
  public static void main(String[] args) {
    var probe=new NativeRogueFirstWorldClosureProbe();
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
              probe.inbox.add(new Frame(frame.cmdId(),frame.header(),frame.body()));
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
