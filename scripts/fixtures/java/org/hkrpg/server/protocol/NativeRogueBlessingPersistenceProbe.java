package org.hkrpg.server.protocol;

import com.google.gson.*;
import com.google.protobuf.*;
import emu.lunarcore.server.packet.*;
import io.netty.buffer.*;
import java.io.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import kcp.highway.*;

/** Source-bound finite empty-slot Equip/TakeOff KCP only. Explicit fixture ownership and real inventory; no capture, battle or UI execution. */
public final class NativeRogueBlessingPersistenceProbe {
  private static final String FORMAL_AVATAR_ENUM = "AvatarType_AvatarFormalType";
  private record Frame(int command, byte[] body) {}
  private final ProtocolBridge protocol = ProtocolBridge.forVersion("4.6.0");
  private final BlockingQueue<Frame> inbox = new LinkedBlockingQueue<>();
  private final AtomicReference<Throwable> failure = new AtomicReference<>();
  private final CompletableFuture<Ukcp> connected = new CompletableFuture<>();
  private final List<Map<String,Object>> events = new ArrayList<>(), notifications = new ArrayList<>();
  private final List<DynamicMessage> lineupNotifications = new ArrayList<>();
  private final List<DynamicMessage> miracleActionNotifications = new ArrayList<>();
  private final List<Map<String,Object>> miracleActionEvents = new ArrayList<>();
  private final List<String> checks = new ArrayList<>();
  private final List<Map<String,Integer>> encounters = new ArrayList<>();
  private final Map<String,Map<String,Integer>> schemas = new LinkedHashMap<>();
  private Ukcp channel;
  private JsonArray sourceBoards;
  private int requests, castUseId = 190000;

  private static void require(boolean ok,String why) { if (!ok) throw new IllegalStateException(why); }
  private void check(boolean ok,String why) { require(ok,why); checks.add(why); }
  private static Descriptors.FieldDescriptor descriptor(DynamicMessage value,int id) {
    return Objects.requireNonNull(value.getDescriptorForType().findFieldByNumber(id), value.getDescriptorForType().getName()+"."+id);
  }
  private static Object field(DynamicMessage value,int id) { return value.getField(descriptor(value,id)); }
  private static Object field(DynamicMessage value,String name) {
    return value.getField(Objects.requireNonNull(value.getDescriptorForType().findFieldByName(name),name));
  }
  private static int number(DynamicMessage value,String name) { return ((Number)field(value,name)).intValue(); }
  private static int number(DynamicMessage value,int id) {
    var raw=field(value,id);
    return raw instanceof Descriptors.EnumValueDescriptor ordinal ? ordinal.getNumber() : ((Number)raw).intValue();
  }
  private static DynamicMessage nested(DynamicMessage value,int id) { return (DynamicMessage)field(value,id); }
  private static DynamicMessage nested(DynamicMessage value,String key) { return (DynamicMessage)field(value,key); }
  private static boolean has(DynamicMessage value,String key) {
    return value.hasField(Objects.requireNonNull(value.getDescriptorForType().findFieldByName(key),key));
  }
  private static String sha(DynamicMessage value) throws Exception {
    return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(value.toByteArray()));
  }
  private static Object view(Object value) {
    if (value instanceof DynamicMessage message) {
      var out=new LinkedHashMap<String,Object>();
      message.getAllFields().forEach((key,raw)->out.put(key.getNumber()+":"+key.getName(),
          key.getName().toLowerCase(Locale.ROOT).contains("token") ? "[redacted]" : view(raw)));
      return out;
    }
    if (value instanceof List<?> rows) return rows.stream().map(NativeRogueBlessingPersistenceProbe::view).toList();
    if (value instanceof Descriptors.EnumValueDescriptor ordinal) return Map.of("name",ordinal.getName(),"number",ordinal.getNumber());
    if (value instanceof ByteString bytes) return Base64.getEncoder().encodeToString(bytes.toByteArray());
    return value;
  }
  private void schema(String name,int... ids) {
    var message=Objects.requireNonNull(protocol.clientSchema().message(name),name);
    var fields=new LinkedHashMap<String,Integer>();
    for (int id:ids) fields.put(Objects.requireNonNull(message.findFieldByNumber(id),name+"."+id).getName(),id);
    schemas.put(name,fields);
  }
  private void namedSchema(String name,String... names) {
    var message=Objects.requireNonNull(protocol.clientSchema().message(name),name);
    var fields=new LinkedHashMap<String,Integer>();
    for (String key:names) fields.put(key,Objects.requireNonNull(message.findFieldByName(key),name+"."+key).getNumber());
    schemas.put(name,fields);
  }

  private Map<String,String> productionOrigins() throws Exception {
    var result=new TreeMap<String,String>();
    for(String name:List.of("org.hkrpg.server.protocol.ProtocolBridge","org.hkrpg.server.protocol.VersionedPacketHandler",
        "org.hkrpg.server.protocol.Universe46","org.hkrpg.server.gameplay.GameplayState","org.hkrpg.server.gameplay.UniverseManager",
        "org.hkrpg.server.gameplay.RogueMiracles","org.hkrpg.server.gameplay.RogueResonance","emu.lunarcore.game.player.Player","emu.lunarcore.LunarCore")) {
      var type=Class.forName(name,false,getClass().getClassLoader());
      result.put(name,type.getProtectionDomain().getCodeSource().getLocation().toURI().toString());
    }
    return result;
  }

  private void verifySchemas() {
    schema("OFEDNNJIJFL",5,51,1474);schema("BPLNKHGAILK",3);schema("DEMENEAAGAA");
    schema("HandleRogueCommonPendingActionScRsp",3,9,10,13);
    namedSchema("StartRogueCsReq","area_id","NOEPBNMJFGL","AFEMBLFCCCH");
    namedSchema("GetRogueInfoScRsp","rogue_game_info");namedSchema("SceneInfo","leader_entity_id","entity_group_list","floor_id","entry_id");
    namedSchema("SceneCastSkillCsReq","cast_entity_id","attacked_by_entity_id","skill_index","hit_target_entity_id_list");
    namedSchema("PVEBattleResultCsReq","battle_id","stage_id","end_status");
    namedSchema("PlayerGetTokenCsReq","account_uid","token","platform");
    check(protocol.clientSchema().message("DEMENEAAGAA").getFields().isEmpty(),"original-empty-reroll-structure");
    check(protocol.clientSchema().message("OFEDNNJIJFL").findFieldByNumber(1474).getMessageType().getName().equals("BPLNKHGAILK"),"source-selection-oneof1474");
    check(protocol.clientSchema().message("OFEDNNJIJFL").findFieldByNumber(51).getMessageType().getName().equals("DEMENEAAGAA"),"source-reroll-oneof51");
  }
  private List<?> rows(DynamicMessage value,String name){return (List<?>)field(value,name);}
  private DynamicMessage current() throws Exception {
    var game=nested(call("GetRogueInfoCsReq"),"rogue_game_info");check(has(game,"CFDIKLBINHK"),"actual-classic-run-present");return nested(game,"CFDIKLBINHK");
  }
  private int coins(DynamicMessage current){return number(nested(current,"DHEKLJDBNNP"),"rogue_money");}
  private List<Integer> offered(DynamicMessage current) {
    require(has(current,"FMIGOKIDKJL"),"Actual ordinary pending menu required");
    var menu=nested(nested(nested(current,"FMIGOKIDKJL"),"detail"),"PCHGEGFBKLF");
    return rows(menu,"LEOFNINDJDB").stream().map(value->{var row=(DynamicMessage)value;check(number(row,"HAGDKKBAAKK")==1,"actual-base-level-menu-candidate");return number(row,"buff_id");}).toList();
  }
  private int batch(DynamicMessage current){return number(nested(current,"FMIGOKIDKJL"),"queue_position");}
  private Set<Integer> acquired(DynamicMessage current){var out=new TreeSet<Integer>();for(var value:rows(nested(current,"HABJHGPFGEB"),"CHCMEKAFKAF"))require(out.add(number((DynamicMessage)value,"buff_id")),"Duplicate blessing");return out;}
  private void route(DynamicMessage current,boolean pending) {
    var map=nested(current,"COOOMDKGODF");check(number(map,"area_id")==110&&number(map,"map_id")==101&&number(map,"ANHDBMOPGDC")==1&&number(map,"HPKMGKPPELC")==304,"actual-original-first-world-room304");
    check(has(current,"FMIGOKIDKJL")==pending,"actual-menu-presence-controls-room-exit");
  }
  private DynamicMessage action(String label,int batch,int buff,boolean roll,int status) throws Exception {
    var req=DynamicMessage.newBuilder(protocol.clientSchema().message("OFEDNNJIJFL"));
    req.setField(req.getDescriptorForType().findFieldByNumber(5),batch);
    var fd=req.getDescriptorForType().findFieldByNumber(roll?51:1474);var option=DynamicMessage.newBuilder(fd.getMessageType());
    if(!roll)option.setField(option.getDescriptorForType().findFieldByNumber(3),buff);req.setField(fd,option.build());
    var response=reply(label,"OFEDNNJIJFL",5676,req.build().toByteArray(),"HandleRogueCommonPendingActionScRsp",status);
    check(number(response,status==0?10:9)==batch,"matched-native-action-batch-acknowledgement-"+label);return response;
  }
  private List<DynamicMessage> monsters(DynamicMessage scene){var out=new ArrayList<DynamicMessage>();for(var group:rows(scene,"entity_group_list"))for(var raw:rows((DynamicMessage)group,"entity_list")){var value=(DynamicMessage)raw;if(has(value,"npc_monster"))out.add(value);}return out;}
  private DynamicMessage scene() throws Exception{return nested(call("GetCurSceneInfoCsReq"),"scene");}
  private void fight(JsonObject actors) throws Exception {
    for(int encounter=0;encounter<3;encounter++) {
      var scene=scene();var targets=monsters(scene);check(targets.size()==3-encounter,"actual-room-three-original-actors-"+encounter);
      var target=targets.getFirst();var actor=Objects.requireNonNull(actors.getAsJsonObject(Integer.toString(number(target,"group_id"))));
      var npc=nested(target,"npc_monster");JsonObject expected=null;
      for(var value:actor.getAsJsonArray("choices")){var row=value.getAsJsonObject();if(number(npc,"monster_id")==row.get("npc").getAsInt()&&number(npc,"event_id")==row.get("stage").getAsInt())expected=row;}
      check(expected!=null&&number(target,"inst_id")==actor.get("inst").getAsInt(),"source-weighted-NPC-stage-instance-"+encounter);
      int use=++castUseId,leader=number(scene,"leader_entity_id"),entity=number(target,"entity_id");
      var attack=callNamed("real-normal-use-"+use,"SceneCastSkillCsReq",Map.of("cast_entity_id",use,"attacked_by_entity_id",leader,"skill_index",0,"hit_target_entity_id_list",List.of(entity)),0);
      check(number(attack,"cast_entity_id")==use,"actual-Ability-UseID-not-scene-entity");
      var battle=nested(attack,"battle_info");int id=number(battle,"battle_id"),stage=number(battle,"stage_id");check(id>0&&stage==expected.get("stage").getAsInt(),"actual-source-battle-identity");
      var waves=rows(battle,"monster_wave_list");var original=expected.getAsJsonArray("waves");check(waves.size()==original.size(),"whole-original-wave-count");
      for(int i=0;i<waves.size();i++){var wave=(DynamicMessage)waves.get(i);var ids=rows(wave,"monster_list");var originals=original.get(i).getAsJsonArray();check(number(wave,"battle_stage_id")==stage&&ids.size()==originals.size(),"whole-source-wave-stage-and-size");for(int j=0;j<ids.size();j++)check(number((DynamicMessage)ids.get(j),"monster_id")==originals.get(j).getAsInt(),"source-wave-monster");}
      var end=protocol.clientSchema().message("PVEBattleResultCsReq").findFieldByName("end_status");var result=Map.<String,Object>of("battle_id",id,"stage_id",stage,"end_status",end.getEnumType().findValueByName("BATTLE_END_WIN"));
      callNamed("actual-WIN-"+id,"PVEBattleResultCsReq",result,0);callNamed("duplicate-WIN-rejected-"+id,"PVEBattleResultCsReq",result,-1);
      check(monsters(scene()).stream().noneMatch(value->number(value,"entity_id")==entity),"actual-defeated-actor-removed");
      encounters.add(Map.of("group",number(target,"group_id"),"stage",stage,"battle",id));
    }
    check(monsters(scene()).isEmpty(),"original-room-cleared-after-three-real-WINs");
  }
  private void readOnly(BufferedReader control,int uid,String phase) throws Exception {
    observation(control,uid,"before-"+phase);current();scene();observation(control,uid,"after-"+phase);
  }
  private Map<String,Object> execute(JsonObject input,BufferedReader control) throws Exception {
    String mode=input.get("mode").getAsString();require(Set.of("initial","restart").contains(mode),"Unknown owned blessing phase");verifySchemas();
    var origins=productionOrigins();var expectedPath=java.nio.file.Path.of(java.net.URI.create(input.get("jarSourceURI").getAsString())).toRealPath();
    for(String origin:origins.values())check(java.nio.file.Path.of(java.net.URI.create(origin)).toRealPath().equals(expectedPath),"one-actual-frozen-production-CodeSource");
    int uid=number(callNamed("owned-token","PlayerGetTokenCsReq",Map.of("account_uid",input.get("accountUid").getAsString(),"token",input.get("comboToken").getAsString(),"platform",1),0),"uid");check(uid==10001,"one-owned-actual-UID");
    call("PlayerLoginCsReq");call("PlayerLoginFinishCsReq");scene();
    if(mode.equals("initial")) {
      observation(control,uid,"seed");
      callNamed("real-first-world-Start","StartRogueCsReq",Map.of("area_id",110,"NOEPBNMJFGL",List.of(emu.lunarcore.GameConstants.TRAILBLAZER_AVATAR_ID)),0);route(current(),false);
      fight(input.getAsJsonObject("actors"));var initial=current();route(initial,true);check(offered(initial).size()==3,"real-saved-three-blessing-victory-menu");
      readOnly(control,uid,"query");int batch=batch(initial),coins=coins(initial);var first=offered(initial);
      observation(control,uid,"before-reroll");var response=action("real-original-price-reroll",batch,0,true,0);var rolled=current();
      check(coins(rolled)==coins-30&&batch(rolled)==batch&&Collections.disjoint(first,offered(rolled)),"actual-reroll-single-original-30-debit-same-batch-new-menu");
      check(has(response,"JFHKDEIDMOG"),"actual-reroll-response-menu-callback");observation(control,uid,"after-reroll");
      observation(control,uid,"before-rejects");action("same-paid-roll-rejected",batch,0,true,-1);action("old-excluded-offer-rejected",batch,first.getFirst(),false,-1);current();scene();observation(control,uid,"after-rejects");
    } else {
      // The fixture save is attributed separately; it only flushes genuine pending login fields.
      observation(control,uid,"checkpoint-restored");
      var restored=current();route(restored,true);check(batch(restored)==input.get("batch").getAsInt()&&offered(restored).equals(new Gson().fromJson(input.get("offers"),new com.google.gson.reflect.TypeToken<List<Integer>>(){}.getType()))&&coins(restored)==input.get("coins").getAsInt(),"same-UID-exact-persisted-menu-price-and-currency");
      readOnly(control,uid,"restored-query");int batch=batch(restored),buff=offered(restored).getFirst();
      observation(control,uid,"before-select");int notificationStart=notifications.size();action("actual-saved-menu-blessing-acquire",batch,buff,false,0);var selected=current();
      check(acquired(selected).contains(buff)&&!has(selected,"FMIGOKIDKJL")&&coins(selected)==coins(restored),"actual-blessing-obtained-menu-cleared-no-extra-debit");
      check(notifications.subList(notificationStart,notifications.size()).stream().anyMatch(row->((Number)row.get("command")).intValue()==protocol.clientCommand("SyncRogueCommonActionResultScNotify")),"actual-acquisition-native-notification-before-reply");observation(control,uid,"after-select");
      observation(control,uid,"before-repeat");action("same-blessing-replay-rejected",batch,buff,false,-1);current();scene();observation(control,uid,"after-repeat");
    }
    String finalPhase=mode.equals("initial")?"pending":"final-query";
    observation(control,uid,"before-"+finalPhase);var last=current();scene();observation(control,uid,"after-"+finalPhase);
    var out=new LinkedHashMap<String,Object>();out.put("passed",true);out.put("uid",uid);out.put("mode",mode);out.put("requests",requests);out.put("checks",checks);out.put("events",events);out.put("notifications",notifications);out.put("encounters",encounters);out.put("productionCodeSources",origins);out.put("coins",coins(last));
    out.put("batch",has(last,"FMIGOKIDKJL")?batch(last):input.get("batch"));out.put("offers",has(last,"FMIGOKIDKJL")?offered(last):List.of());out.put("negativeRequests",events.stream().filter(row->((Number)row.get("expectedRetcode")).intValue()<0).count());out.put("nativeSaveFailureInjected",false);out.put("clientGameplayVerified",false);return out;
  }
  private DynamicMessage message(String name,Map<Integer,Object> fields) {
    var builder=DynamicMessage.newBuilder(Objects.requireNonNull(protocol.clientSchema().message(name),name));
    fields.forEach((id,raw)->builder.setField(Objects.requireNonNull(builder.getDescriptorForType().findFieldByNumber(id),name+"."+id),raw));
    return builder.build();
  }
  private Map<Integer,Object> named(String name,Map<String,Object> fields) {
    var descriptor=Objects.requireNonNull(protocol.clientSchema().message(name),name);
    var out=new LinkedHashMap<Integer,Object>();
    fields.forEach((key,value)->out.put(Objects.requireNonNull(descriptor.findFieldByName(key),name+"."+key).getNumber(),value));
    return out;
  }
  private void observe(Frame frame) {
    var event=new LinkedHashMap<String,Object>();
    event.put("command",frame.command()); event.put("observedBeforeReplyRequestOrdinal",requests);
    event.put("observedBodyBase64",Base64.getEncoder().encodeToString(frame.body()));
    notifications.add(event); System.out.println("ROGUE_BLESSING_NOTIFICATION="+new Gson().toJson(event)); System.out.flush();
  }
  private Frame raw(int command,byte[] body,int expected) throws Exception {
    var bytes=Unpooled.wrappedBuffer(PacketCodec.encode(command,new byte[0],body));
    try { channel.write(bytes); } finally { bytes.release(); }
    long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(20);
    while (System.nanoTime()<until) {
      if (failure.get()!=null) throw new IllegalStateException("Native Aether connection failed",failure.get());
      var frame=inbox.poll(1,TimeUnit.SECONDS); if (frame==null) continue;
      if (frame.command()==expected) return frame;
      observe(frame);
    }
    throw new TimeoutException("Actual native Aether reply "+expected);
  }
  private DynamicMessage reply(String label,String name,int command,byte[] bytes,String response,int expected) throws Exception {
    requests++;
    var frame=raw(command,bytes,protocol.clientCommand(response));
    // Persist the actual frame before parsing or asserting. Authentication bodies stay redacted.
    var event=new LinkedHashMap<String,Object>();
    event.put("label",label); event.put("requestCommand",command); event.put("request",name);
    event.put("response",response); event.put("responseCommand",frame.command()); event.put("expectedRetcode",expected);
    if (!name.startsWith("PlayerGetToken")) event.put("observedBodyBase64",Base64.getEncoder().encodeToString(frame.body()));
    events.add(event); System.out.println("ROGUE_BLESSING_REPLY_FRAME="+new Gson().toJson(event)); System.out.flush();
    var value=protocol.clientSchema().parse(response,frame.body());
    event.put("observedReply",view(value)); event.put("actualRetcode",number(value,"retcode"));
    event.put("unknownFields",value.getUnknownFields().asMap().keySet());
    System.out.println("ROGUE_BLESSING_REPLY="+new Gson().toJson(event)); System.out.flush();
    require(value.getUnknownFields().asMap().isEmpty(),"Unknown actual "+response+" fields");
    require(expected<0 ? number(value,"retcode")!=0 : number(value,"retcode")==expected,
        label+" actual retcode="+number(value,"retcode")+" expected="+expected);
    return value;
  }
  private DynamicMessage call(String label,String name,Map<Integer,Object> fields,int status) throws Exception {
    return reply(label,name,protocol.clientCommand(name),message(name,fields).toByteArray(),name.replace("CsReq","ScRsp"),status);
  }
  private DynamicMessage call(String name) throws Exception { return call(name,name,Map.of(),0); }
  private DynamicMessage callNamed(String label,String name,Map<String,Object> fields,int status) throws Exception {
    return call(label,name,named(name,fields),status);
  }

  private void observation(BufferedReader control,int uid,String operation) throws Exception {
    System.out.println("ROGUE_BLESSING_READY_UID="+uid+":"+operation);System.out.flush();
    require(("ROGUE_BLESSING_OBSERVED:"+operation).equals(control.readLine()),"Missing exact console observation acknowledgement");
  }
  public static void main(String[] args) {
    var probe=new NativeRogueBlessingPersistenceProbe();
    if (args.length==1 && args[0].equals("--verify-descriptors")) {
      try {
        probe.verifySchemas();var origins=probe.productionOrigins();String expected=System.getProperty("rogueblessing.expectedProductionJar");
        if(expected!=null)for(String origin:origins.values())require(java.nio.file.Path.of(java.net.URI.create(origin)).toRealPath()
            .equals(java.nio.file.Path.of(expected).toRealPath()),"Preflight production CodeSource differs from frozen jar");
        System.out.println(new Gson().toJson(Map.of("passed",true,"nativeExecuted",false,"schemas",probe.schemas,"checks",probe.checks,"productionCodeSources",origins)));return;
      } catch(Exception failure) {throw new IllegalStateException("Pure source/schema preflight failed",failure);}
    }
    var client=new KcpClient(); int status=1;
    try {
      var control=new BufferedReader(new InputStreamReader(System.in,StandardCharsets.UTF_8));
      var input=JsonParser.parseString(control.readLine()).getAsJsonObject();
      var config=new ChannelConfig(); config.nodelay(true,40,2,true); config.setMtu(1400);
      config.setSndwnd(256); config.setRcvwnd(256); config.setUseConvChannel(true);
      config.setConv(ThreadLocalRandom.current().nextInt(1,Integer.MAX_VALUE)); config.setTimeoutMillis(25000); config.setAckNoDelay(true);
      client.init(config,new KcpListener() {
        public void onConnected(Ukcp value) { probe.connected.complete(value); }
        public void handleReceive(ByteBuf bytes,Ukcp value) {
          try { while (bytes.isReadable()) {
            var frame=PacketCodec.decodeNext(bytes); require(frame!=null,"Truncated Aether frame");
            probe.inbox.add(new Frame(frame.cmdId(),frame.body()));
          } } catch (Throwable error) { probe.failure.set(error); }
        }
        public void handleException(Throwable error,Ukcp value) { probe.failure.set(error); probe.connected.completeExceptionally(error); }
        public void handleClose(Ukcp value) { probe.failure.compareAndSet(null,new IllegalStateException("Owned Aether probe disconnected")); }
      });
      client.connect(new InetSocketAddress("127.0.0.1",Integer.parseInt(args[0])),config);
      probe.channel=probe.connected.get(10,TimeUnit.SECONDS); System.out.println(new Gson().toJson(probe.execute(input,control))); status=0;
    } catch (Exception error) {
      System.err.println("FAILED_ROGUE_BLESSING_OBSERVATIONS="+new Gson().toJson(probe.events)); error.printStackTrace(System.err);
    } finally { client.stop(); }
    System.exit(status);
  }
}
