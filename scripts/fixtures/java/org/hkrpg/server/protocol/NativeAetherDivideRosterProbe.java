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

/** Source-bound roster KCP only. Explicit fixture ownership and real inventory; no capture, battle or UI execution. */
public final class NativeAetherDivideRosterProbe {
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
    if (value instanceof List<?> rows) return rows.stream().map(NativeAetherDivideRosterProbe::view).toList();
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
    schema("SetAetherDivideLineUpCsReq",11);schema("SetAetherDivideLineUpScRsp",1,7);
    schema("SwitchAetherDivideLineUpSlotCsReq",4);schema("SwitchAetherDivideLineUpSlotScRsp",9,12);
    schema("OCCAABPFBLF",2,10);schema("GHBICGMEFPL",3,4,6,10,11,13);
    schema("DMGDLEHEDFB",6,8,13);schema("POPJNEHGMIH",2,5);
    namedSchema("SpBarInfo","cur_sp","max_sp");
    namedSchema("PlayerGetTokenCsReq","account_uid","token","platform");
    namedSchema("GetCurSceneInfoScRsp","scene");namedSchema("GetCurLineupDataScRsp","lineup");
    namedSchema("GetRogueInfoScRsp","rogue_game_info");
    check(protocol.clientCommand("GetAetherDivideInfoCsReq")==4848 && protocol.clientCommand("SetAetherDivideLineUpCsReq")==4840
        && protocol.clientCommand("SwitchAetherDivideLineUpSlotCsReq")==4818,"three-fixed-native-Aether-roster-commands");
    check(protocol.clientSchema().message("GetAetherDivideInfoCsReq").getFields().isEmpty(),"source-roster-query-is-empty");
    var request=protocol.clientSchema().message("SetAetherDivideLineUpCsReq").findFieldByNumber(11);
    check(request.getMessageType().getName().equals("OCCAABPFBLF"),"actual-OED-Writer-tag0x5a-matched-to-lineup11");
    var row=protocol.clientSchema().message("OCCAABPFBLF");
    check(row.findFieldByNumber(2).getType()==Descriptors.FieldDescriptor.Type.UINT32
        && row.findFieldByNumber(10).getType()==Descriptors.FieldDescriptor.Type.UINT32 && row.findFieldByNumber(10).isRepeated(),
        "native-row-slot-uint32-and-variable-repeated-uint32-members");
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
    notifications.add(event); System.out.println("AETHER_ROSTER_NOTIFICATION="+new Gson().toJson(event)); System.out.flush();
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
    return reply(label,name,protocol.clientCommand(name),message(name,fields).toByteArray(),name.replace("CsReq","ScRsp"),status);
  }
  private DynamicMessage call(String name) throws Exception { return call(name,name,Map.of(),0); }
  private DynamicMessage callNamed(String label,String name,Map<String,Object> fields,int status) throws Exception {
    return call(label,name,named(name,fields),status);
  }

  private void observation(BufferedReader control,int uid,String operation) throws Exception {
    System.out.println("AETHER_ROSTER_READY_UID="+uid+":"+operation);System.out.flush();
    require(("AETHER_ROSTER_OBSERVED:"+operation).equals(control.readLine()),"Missing exact console observation acknowledgement");
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
  private DynamicMessage query(String label,boolean owned,int current,Map<Integer,List<Integer>> expected) throws Exception {
    var info=call(label,"GetAetherDivideInfoCsReq",Map.of(),0);
    check(lineups(info).equals(expected),"actual-complete-lineups-match-saved-roster-"+label);
    check(number(info,10)==current && number(info,12)==0 && number(info,13)==0,"native-current-slot-and-no-fabricated-trainer-or-infinite-"+label);
    check(!info.hasField(descriptor(info,11)),"unknown-info-field11-is-not-invented-"+label);
    var spirits=new TreeMap<Integer,DynamicMessage>();
    for(var value:rows(info,15)) { var row=(DynamicMessage)value;require(spirits.put(number(row,10),row)==null,"Duplicate actual spirit"); }
    var cores=new TreeMap<Integer,DynamicMessage>();
    for(var value:rows(info,14)) { var row=(DynamicMessage)value;require(cores.put(number(row,6),row)==null,"Duplicate actual skill core"); }
    check(spirits.keySet().equals(owned?Set.of(6001,6006):Set.of()),"ownership-only-from-explicit-stored-roster-never-fragment-only6010-"+label);
    check(cores.keySet().equals(owned?Set.of(250201,250202):Set.of()),"cores-projected-from-real-current-material-inventory-"+label);
    if(owned) {
      for(int id:List.of(6001,6006)) {
        var row=spirits.get(id);var sp=nested(row,6);
        check(number(row,3)==1 && number(row,11)==0 && number(sp,"max_sp")==3000
            && number(sp,"cur_sp")== (id==6001?1000:500) && rows(row,13).isEmpty(),"source-promotion-exp-independent-SpBar-and-no-fake-skill-"+id+"-"+label);
        check(number(row,4)==(id==6001?7:2),"actual-material-fragment-count-not-legacy-roster-hint-"+id+"-"+label);
      }
      check(number(cores.get(250201),13)==3 && number(cores.get(250202),13)==4
          && number(cores.get(250201),8)==0 && number(cores.get(250202),8)==0,"real-250201-and250202-stock-not-cached-core-count-"+label);
    }
    return info;
  }
  private DynamicMessage set(String label,int slot,List<Integer> ids,int expected) throws Exception {
    var response=call(label,"SetAetherDivideLineUpCsReq",Map.of(11,message("OCCAABPFBLF",Map.of(2,slot,10,ids))),expected);
    if(expected==0) {
      var wanted=new ArrayList<>(ids);while(wanted.size()<4)wanted.add(0);
      check(response.hasField(descriptor(response,1)) && number(nested(response,1),2)==slot
          && field(nested(response,1),10).equals(wanted),"successful-Set-returns-full-replacement-four-explicit-positions-"+label);
    } else check(!response.hasField(descriptor(response,1)),"rejected-Set-publishes-no-fake-lineup-"+label);
    return response;
  }
  private void switchSlot(String label,int slot,int expected) throws Exception {
    var response=call(label,"SwitchAetherDivideLineUpSlotCsReq",Map.of(4,slot),expected);
    check(expected==0?number(response,12)==slot:!response.hasField(descriptor(response,12)),"actual-switch-slot-reply-is-bounded-"+label);
  }
  private Map<String,Object> execute(JsonObject input,BufferedReader control) throws Exception {
    String mode=input.get("mode").getAsString();require(Set.of("initial","restart").contains(mode),"Unowned roster phase");
    verifySchemas();var origins=productionOrigins();String expectedOrigin=input.get("jarSourceURI").getAsString();
    var expectedPath=java.nio.file.Path.of(java.net.URI.create(expectedOrigin)).toRealPath();
    for(String origin:origins.values())check(java.nio.file.Path.of(java.net.URI.create(origin)).toRealPath().equals(expectedPath),
        "production-CodeSource-is-the-same-new-frozen-jar-"+origin);
    int uid=number(callNamed("token","PlayerGetTokenCsReq",Map.of("account_uid",input.get("accountUid").getAsString(),
        "token",input.get("comboToken").getAsString(),"platform",1),0),"uid");
    call("PlayerLoginCsReq");call("PlayerLoginFinishCsReq");check(uid==10001,"same-isolated-account-UID");
    var sceneBefore=nested(call("GetCurSceneInfoCsReq"),"scene");
    var teamBefore=nested(call("GetCurLineupDataCsReq"),"lineup");
    var classicBefore=nested(call("GetRogueInfoCsReq"),"rogue_game_info");
    var empty=new TreeMap<Integer,List<Integer>>();for(int slot=1;slot<=6;slot++)empty.put(slot,List.of(0,0,0,0));
    var finalRows=new TreeMap<Integer,List<Integer>>();for(int slot=1;slot<=5;slot++)finalRows.put(slot,List.of(6001,0,6006,0));
    finalRows.put(6,List.of(6006,0,6001,0));int negativeRequests=0;
    if(mode.equals("initial")) {
      observation(control,uid,"before-empty");var first=query("empty-first",false,1,empty);var repeat=query("empty-repeat",false,1,empty);
      check(first.equals(repeat),"initial-empty-roster-repeat-is-identical");observation(control,uid,"after-empty");
      observation(control,uid,"seed");observation(control,uid,"before-owned");
      first=query("owned-first",true,1,empty);repeat=query("owned-repeat",true,1,empty);
      check(first.equals(repeat),"seeded-roster-readonly-repeat-identical");observation(control,uid,"after-owned");
      observation(control,uid,"before-rejects");
      for(int slot:List.of(0,7)) {set("bad-slot-"+slot,slot,List.of(6001),-1);switchSlot("bad-switch-"+slot,slot,-1);negativeRequests+=2;}
      for(var ids:List.of(List.of(6001,6001),List.of(6010),List.of(6001,0,6006,0,0))) {
        set("invalid-members-"+ids,2,ids,-1);negativeRequests++;
      }
      var emptySet=call("missing-lineup","SetAetherDivideLineUpCsReq",Map.of(),-1);
      check(!emptySet.hasField(descriptor(emptySet,1)),"empty-Set-rejected-without-lineup");negativeRequests++;
      call("missing-switch","SwitchAetherDivideLineUpSlotCsReq",Map.of(),-1);negativeRequests++;
      reply("unknown-field-query","GetAetherDivideInfoCsReq",4848,new byte[]{8,1},"GetAetherDivideInfoScRsp",-1);negativeRequests++;
      observation(control,uid,"after-rejects");observation(control,uid,"before-mutations");
      for(int slot=1;slot<=6;slot++) {set("all-slots-"+slot,slot,List.of(6001,0,6006,0),0);switchSlot("all-switches-"+slot,slot,0);}
      var shortIds=List.of(6006,0,6001);
      for(int count=0;count<=3;count++)set("short-"+count,6,shortIds.subList(0,count),0);
      observation(control,uid,"after-mutations");observation(control,uid,"before-repeat");
      set("same-Set-short-repeat1",6,shortIds,0);set("same-Set-short-repeat2",6,shortIds,0);switchSlot("same-switch-repeat",6,0);
      observation(control,uid,"after-repeat");observation(control,uid,"before-final-query");
      first=query("final-first",true,6,finalRows);repeat=query("final-repeat",true,6,finalRows);
      check(first.equals(repeat),"final-query-is-identical");observation(control,uid,"after-final-query");
    } else {
      observation(control,uid,"before-restored");
      var first=query("restored-first",true,6,finalRows);var repeat=query("restored-repeat",true,6,finalRows);
      check(first.equals(repeat),"same-UID-H2-restored-Query-identical");
      set("restored-same-Set-repeat1",6,List.of(6006,0,6001),0);set("restored-same-Set-repeat2",6,List.of(6006,0,6001),0);
      switchSlot("restored-same-switch",6,0);var after=query("restored-after-idempotent",true,6,finalRows);
      check(first.equals(after),"restored-idempotent-Set-Switch-preserve-complete-query");observation(control,uid,"after-restored");
    }
    check(sceneBefore.equals(nested(call("GetCurSceneInfoCsReq"),"scene")),"roster-requests-preserve-actual-ordinary-scene");
    check(teamBefore.equals(nested(call("GetCurLineupDataCsReq"),"lineup")),"roster-requests-preserve-actual-ordinary-lineup");
    check(classicBefore.equals(nested(call("GetRogueInfoCsReq"),"rogue_game_info")),"roster-requests-preserve-classic-universe");
    var out=new LinkedHashMap<String,Object>();out.put("passed",true);out.put("uid",uid);out.put("mode",mode);
    out.put("requests",requests);out.put("negativeRequests",negativeRequests);out.put("checks",checks);out.put("events",events);
    out.put("notifications",notifications);out.put("storedRosterSeededByIsolatedFixture",true);out.put("productionSpiritAcquisitionVerified",false);
    out.put("productionCodeSources",origins);
    out.put("battleVerified",false);out.put("challengeVerified",false);out.put("clientGameplayVerified",false);
    out.put("shortReplacementIsServerPolicy",true);return out;
  }
  public static void main(String[] args) {
    var probe=new NativeAetherDivideRosterProbe();
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
