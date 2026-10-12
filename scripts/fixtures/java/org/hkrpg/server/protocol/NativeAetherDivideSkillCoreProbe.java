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
public final class NativeAetherDivideSkillCoreProbe {
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
    if (value instanceof List<?> rows) return rows.stream().map(NativeAetherDivideSkillCoreProbe::view).toList();
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
        "org.hkrpg.server.protocol.AetherDivide46","org.hkrpg.server.gameplay.GameplayState","org.hkrpg.server.gameplay.AetherDivideManager",
        "org.hkrpg.server.gameplay.AetherDivideState","org.hkrpg.server.gameplay.AetherDivideDefinitions","emu.lunarcore.game.player.Player","emu.lunarcore.LunarCore")) {
      var type=Class.forName(name,false,getClass().getClassLoader());
      result.put(name,type.getProtectionDomain().getCodeSource().getLocation().toURI().toString());
    }
    return result;
  }
  private void verifySchemas() {
    schema("GetAetherDivideInfoCsReq");schema("GetAetherDivideInfoScRsp",4,6,10,12,13,14,15);
    schema("EquipAetherDividePassiveSkillCsReq",3,12,14);schema("EquipSkillCoreScRsp",1,9,15);
    schema("TakeOffSkillCoreCsReq",1,7);schema("TakeOffSkillCoreScRsp",4,8,14);
    schema("OCCAABPFBLF",2,10);schema("GHBICGMEFPL",3,4,6,10,11,13);
    schema("DMGDLEHEDFB",6,8,13);schema("POPJNEHGMIH",2,5);
    namedSchema("SpBarInfo","cur_sp","max_sp");
    namedSchema("PlayerGetTokenCsReq","account_uid","token","platform");
    namedSchema("GetCurSceneInfoScRsp","scene");namedSchema("GetCurLineupDataScRsp","lineup");
    namedSchema("GetRogueInfoScRsp","rogue_game_info");
    for(var item:Map.of("GetAetherDivideInfoCsReq",4848,"EquipAetherDividePassiveSkillCsReq",4847,
        "EquipSkillCoreScRsp",4825,"TakeOffSkillCoreCsReq",4823,"TakeOffSkillCoreScRsp",4802).entrySet())
      check(protocol.clientCommand(item.getKey())==item.getValue(),"fixed-source-core-command-"+item.getKey());
    for(String name:List.of("EquipAetherDividePassiveSkillCsReq","TakeOffSkillCoreCsReq"))
      for(var field:protocol.clientSchema().message(name).getFields())
        check(field.getType()==Descriptors.FieldDescriptor.Type.UINT32 && !field.isRepeated(),"source-uint32-request-field-"+name+"-"+field.getNumber());
    for(String name:List.of("EquipSkillCoreScRsp","TakeOffSkillCoreScRsp")) {
      var descriptor=protocol.clientSchema().message(name);
      check(descriptor.findFieldByNumber(name.startsWith("Equip")?9:4).getMessageType().getName().equals("DMGDLEHEDFB"),"actual-core-type-"+name);
      check(descriptor.findFieldByNumber(name.startsWith("Equip")?15:8).getMessageType().getName().equals("GHBICGMEFPL"),"actual-spirit-type-"+name);
    }
    check(protocol.clientSchema().message("GetAetherDivideInfoCsReq").getFields().isEmpty(),"source-query-is-empty");
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
    notifications.add(event); System.out.println("AETHER_CORE_NOTIFICATION="+new Gson().toJson(event)); System.out.flush();
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
    events.add(event); System.out.println("AETHER_REPLY_FRAME="+new Gson().toJson(event)); System.out.flush();
    var value=protocol.clientSchema().parse(response,frame.body());
    event.put("observedReply",view(value)); event.put("actualRetcode",number(value,"retcode"));
    event.put("unknownFields",value.getUnknownFields().asMap().keySet());
    System.out.println("AETHER_REPLY="+new Gson().toJson(event)); System.out.flush();
    require(value.getUnknownFields().asMap().isEmpty(),"Unknown actual "+response+" fields");
    require(expected<0 ? number(value,"retcode")!=0 : number(value,"retcode")==expected,
        label+" actual retcode="+number(value,"retcode")+" expected="+expected);
    return value;
  }
  private DynamicMessage call(String label,String name,Map<Integer,Object> fields,int status) throws Exception {
    return reply(label,name,protocol.clientCommand(name),message(name,fields).toByteArray(),(name.equals("EquipAetherDividePassiveSkillCsReq")?"EquipSkillCoreScRsp":name.replace("CsReq","ScRsp")),status);
  }
  private DynamicMessage call(String name) throws Exception { return call(name,name,Map.of(),0); }
  private DynamicMessage callNamed(String label,String name,Map<String,Object> fields,int status) throws Exception {
    return call(label,name,named(name,fields),status);
  }

  private void observation(BufferedReader control,int uid,String operation) throws Exception {
    System.out.println("AETHER_CORE_READY_UID="+uid+":"+operation);System.out.flush();
    require(("AETHER_CORE_OBSERVED:"+operation).equals(control.readLine()),"Missing exact console observation acknowledgement");
  }
  private List<?> rows(DynamicMessage value,int id) { return (List<?>)field(value,id); }
  private Map<Integer,List<Integer>> lineups(DynamicMessage info) {
    var result=new TreeMap<Integer,List<Integer>>();
    for(var value:rows(info,4)) {
      var row=(DynamicMessage)value;int slot=number(row,2);
      @SuppressWarnings("unchecked") var ids=(List<Integer>)field(row,10);
      check(slot>=1 && slot<=6 && ids.size()==4 && result.put(slot,List.copyOf(ids))==null,"six-source-slots-with-four-explicit-positions-"+slot);
    }
    check(result.keySet().equals(Set.of(1,2,3,4,5,6)),"complete-six-slots-1-through-6");return result;
  }
  private void passive(DynamicMessage row,int expectedSlot,int expectedItem,String label) {
    var values=rows(row,13);
    check(values.size()==(expectedItem==0?0:1),"complete-passive-size-"+label);
    if(expectedItem!=0) {
      var value=(DynamicMessage)values.getFirst();
      check(number(value,2)==expectedItem && number(value,5)==expectedSlot,"actual-one-based-passive-binding-"+label);
    }
  }
  private void spirit(DynamicMessage row,int id,int bound,String label) {
    check(number(row,10)==id && number(row,3)==(id==6001?2:3) && number(row,11)==0,"source-id-promotion-exp-"+label);
    var sp=nested(row,6);
    check(row.hasField(descriptor(row,6)) && number(sp,"max_sp")== (id==6001?3000:4000)
        && number(sp,"cur_sp")== (id==6001?1000:500),"complete-independent-source-SP-"+label);
    check(number(row,4)==(id==6001?7:2),"real-fragments-not-legacy-hints-"+label);
    passive(row,id==6001?1:2,bound==id?250202:0,label);
  }
  private DynamicMessage query(String label,boolean owned,int bound) throws Exception {
    var info=call(label,"GetAetherDivideInfoCsReq",Map.of(),0);
    var expected=new TreeMap<Integer,List<Integer>>();for(int slot=1;slot<=6;slot++)expected.put(slot,List.of(0,0,0,0));
    check(lineups(info).equals(expected) && number(info,10)==1,"core-requests-preserve-six-four-zero-lineups-"+label);
    check(number(info,12)==0 && number(info,13)==0 && !info.hasField(descriptor(info,11)),"no-invented-trainer-infinite-or-unknown-field-"+label);
    var spirits=new TreeMap<Integer,DynamicMessage>();
    for(var value:rows(info,15)) {var row=(DynamicMessage)value;require(spirits.put(number(row,10),row)==null,"Duplicate spirit");}
    var cores=new TreeMap<Integer,DynamicMessage>();
    for(var value:rows(info,14)) {var row=(DynamicMessage)value;require(cores.put(number(row,6),row)==null,"Duplicate core");}
    check(spirits.keySet().equals(owned?Set.of(6001,6005):Set.of()),"ownership-only-from-explicit-stored-roster-not-fragment6010-"+label);
    check(cores.keySet().equals(owned?Set.of(250201,250202,250204):Set.of()),"core-list-from-current-real-inventory-"+label);
    if(owned) {
      for(int id:List.of(6001,6005))spirit(spirits.get(id),id,bound,id+"-"+label);
      for(var entry:Map.of(250201,3,250202,4,250204,2).entrySet()) {
        var row=cores.get(entry.getKey());
        check(number(row,13)==entry.getValue() && number(row,8)==(entry.getKey()==250202?bound:0),"real-core-count-and-saved-owner-"+entry.getKey()+"-"+label);
      }
    }
    return info;
  }
  private void coreReply(DynamicMessage value,int avatar,boolean equipped,String label) {
    boolean equip=value.getDescriptorForType().getName().equals("EquipSkillCoreScRsp");int coreField=equip?9:4,spiritField=equip?15:8;
    check(value.hasField(descriptor(value,coreField)) && value.hasField(descriptor(value,spiritField)),"success-complete-core-and-spirit-"+label);
    var core=nested(value,coreField);var avatarData=nested(value,spiritField);
    check(number(core,6)==250202 && number(core,13)==4 && number(core,8)==(equipped?avatar:0),"success-real-count-and-full-core-owner-"+label);
    spirit(avatarData,avatar,equipped?avatar:0,label);
  }
  private DynamicMessage equip(String label,int avatar,int slot,int item,int status) throws Exception {
    var value=call(label,"EquipAetherDividePassiveSkillCsReq",Map.of(3,avatar,12,slot,14,item),status);
    if(status!=0)check(!value.hasField(descriptor(value,9)) && !value.hasField(descriptor(value,15)),"failed-equip-has-no-fake-success-structures-"+label);
    else coreReply(value,avatar,true,label);
    return value;
  }
  private DynamicMessage off(String label,int avatar,int slot,int status) throws Exception {
    var value=call(label,"TakeOffSkillCoreCsReq",Map.of(7,avatar,1,slot),status);
    if(status!=0)check(!value.hasField(descriptor(value,4)) && !value.hasField(descriptor(value,8)),"failed-takeoff-has-no-fake-success-structures-"+label);
    else coreReply(value,avatar,false,label);
    return value;
  }
  private Map<String,Object> execute(JsonObject input,BufferedReader control) throws Exception {
    String mode=input.get("mode").getAsString();require(Set.of("initial","restart").contains(mode),"Unowned core phase");
    verifySchemas();var origins=productionOrigins();var expectedPath=java.nio.file.Path.of(java.net.URI.create(input.get("jarSourceURI").getAsString())).toRealPath();
    for(String origin:origins.values())check(java.nio.file.Path.of(java.net.URI.create(origin)).toRealPath().equals(expectedPath),"production-CodeSource-is-one-frozen-jar-"+origin);
    int uid=number(callNamed("token","PlayerGetTokenCsReq",Map.of("account_uid",input.get("accountUid").getAsString(),
        "token",input.get("comboToken").getAsString(),"platform",1),0),"uid");
    call("PlayerLoginCsReq");call("PlayerLoginFinishCsReq");check(uid==10001,"same-isolated-account-UID");
    var sceneBefore=nested(call("GetCurSceneInfoCsReq"),"scene");
    var teamBefore=nested(call("GetCurLineupDataCsReq"),"lineup");
    var classicBefore=nested(call("GetRogueInfoCsReq"),"rogue_game_info");
    int negatives=0;
    if(mode.equals("initial")) {
      observation(control,uid,"before-empty");var first=query("empty-first",false,0);var repeat=query("empty-repeat",false,0);
      check(first.equals(repeat),"initial-query-exact-repeat");observation(control,uid,"after-empty");
      observation(control,uid,"seed");call("GetCurSceneInfoCsReq");
      observation(control,uid,"before-ready");first=query("seeded-first",true,0);repeat=query("seeded-repeat",true,0);
      check(first.equals(repeat),"seeded-query-exact-repeat");observation(control,uid,"after-ready");
      observation(control,uid,"before-rejects");
      for(var row:List.of(new int[]{6010,1,250202},new int[]{6001,0,250202},new int[]{6001,3,250202},
          new int[]{6001,2,250205},new int[]{6001,1,250201},new int[]{6001,1,250208},new int[]{6001,1,999999})) {
        equip("source-or-stock-reject-"+Arrays.toString(row),row[0],row[1],row[2],-1);negatives++;
      }
      off("empty-slot-off",6001,1,-1);negatives++;
      var missing=call("missing-equip-fields","EquipAetherDividePassiveSkillCsReq",Map.of(),-1);negatives++;
      check(!missing.hasField(descriptor(missing,9)) && !missing.hasField(descriptor(missing,15)),"missing-equip-no-success-shape");
      var valid=message("EquipAetherDividePassiveSkillCsReq",Map.of(3,6001,12,1,14,250202)).toByteArray();
      var malformed=Arrays.copyOf(valid,valid.length+2);malformed[valid.length]=8;malformed[valid.length+1]=1;
      var unknown=reply("unknown-equip-field","EquipAetherDividePassiveSkillCsReq",4847,malformed,"EquipSkillCoreScRsp",-1);negatives++;
      check(!unknown.hasField(descriptor(unknown,9)) && !unknown.hasField(descriptor(unknown,15)),"unknown-equip-no-success-shape");
      observation(control,uid,"after-rejects");observation(control,uid,"before-equip");
      equip("actual-empty-slot-equip",6001,1,250202,0);observation(control,uid,"after-equip");
      observation(control,uid,"before-repeat");
      equip("same-binding-repeat1",6001,1,250202,0);equip("same-binding-repeat2",6001,1,250202,0);
      equip("occupied-replacement-rejected",6001,1,250204,-1);equip("direct-cross-owner-rejected",6005,2,250202,-1);negatives+=2;
      observation(control,uid,"after-repeat");observation(control,uid,"before-query");
      first=query("equipped-first",true,6001);repeat=query("equipped-repeat",true,6001);check(first.equals(repeat),"equipped-query-exact-repeat");
      observation(control,uid,"after-query");
    } else {
      observation(control,uid,"checkpoint-restored");
      observation(control,uid,"before-restored");var first=query("restored-first",true,6001);var repeat=query("restored-repeat",true,6001);
      check(first.equals(repeat),"same-UID-H2-binding-restored-exactly");equip("restored-idempotent-equip",6001,1,250202,0);
      observation(control,uid,"after-restored");observation(control,uid,"before-off");
      off("actual-takeoff",6001,1,0);observation(control,uid,"after-off");
      observation(control,uid,"before-empty-off");off("duplicate-empty-takeoff",6001,1,-1);negatives++;
      observation(control,uid,"after-empty-off");observation(control,uid,"before-transfer");
      equip("explicit-off-then-new-compatible-empty-slot",6005,2,250202,0);observation(control,uid,"after-transfer");
      observation(control,uid,"before-final-query");first=query("final-first",true,6005);repeat=query("final-repeat",true,6005);
      check(first.equals(repeat),"final-query-exact-repeat");observation(control,uid,"after-final-query");
    }
    check(sceneBefore.equals(nested(call("GetCurSceneInfoCsReq"),"scene")),"actual-core-requests-preserve-ordinary-scene");
    check(teamBefore.equals(nested(call("GetCurLineupDataCsReq"),"lineup")),"actual-core-requests-preserve-ordinary-lineup");
    check(classicBefore.equals(nested(call("GetRogueInfoCsReq"),"rogue_game_info")),"actual-core-requests-preserve-classic-universe");
    var out=new LinkedHashMap<String,Object>();out.put("passed",true);out.put("uid",uid);out.put("mode",mode);
    out.put("requests",requests);out.put("negativeRequests",negatives);out.put("checks",checks);out.put("events",events);
    out.put("notifications",notifications);out.put("storedRosterSeededByIsolatedFixture",true);out.put("productionSpiritAcquisitionVerified",false);
    out.put("productionCodeSources",origins);out.put("finiteEmptySlotPolicy",true);
    out.put("battleVerified",false);out.put("challengeVerified",false);out.put("clientGameplayVerified",false);return out;
  }
  public static void main(String[] args) {
    var probe=new NativeAetherDivideSkillCoreProbe();
    if (args.length==1 && args[0].equals("--verify-descriptors")) {
      try {
        probe.verifySchemas();var origins=probe.productionOrigins();String expected=System.getProperty("aether.expectedProductionJar");
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
      System.err.println("FAILED_AETHER_OBSERVATIONS="+new Gson().toJson(probe.events)); error.printStackTrace(System.err);
    } finally { client.stop(); }
    System.exit(status);
  }
}
