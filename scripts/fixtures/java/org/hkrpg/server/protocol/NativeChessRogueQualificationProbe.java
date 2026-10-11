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

/** Finite original qualification rejection and firstStart KCP. No merchant, battle or UI execution. */
public final class NativeChessRogueQualificationProbe {
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
    if (value instanceof List<?> rows) return rows.stream().map(NativeChessRogueQualificationProbe::view).toList();
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
  private void verifySchemas() {
    schema("ChessRogueStartCsReq",13,9,7); schema("ChessRogueStartScRsp",2,7,13); schema("ChessRogueQueryCsReq"); schema("ChessRogueQueryScRsp",2,6,8,10,12);
    schema("HIMJKAMHLMJ",9,10,11,12); schema("MOOKMKEKMKC",3,9,10); schema("DDAFEPBGOHK",8,9);
    namedSchema("PlayerGetTokenCsReq","account_uid","token","platform");
    namedSchema("GetCurSceneInfoScRsp","scene"); namedSchema("SceneInfo","plane_id","floor_id","entry_id");
    namedSchema("GetCurLineupDataScRsp","lineup"); namedSchema("LineupInfo","avatar_list","leader_slot","mp");
    namedSchema("GetRogueInfoScRsp","rogue_game_info");
    check(protocol.clientCommand("ChessRogueStartCsReq")==5426 && protocol.clientCommand("ChessRogueQueryCsReq")==5547,"fixed-native-initial-Query-command5547");
    check(protocol.clientSchema().message("ChessRogueQueryCsReq").getFields().isEmpty(),"original-initial-Query-is-empty-request");
    var response=protocol.clientSchema().message("ChessRogueQueryScRsp");
    check(response.findFieldByNumber(8).getMessageType().getName().equals("DJEKEGMEHLK")
        && response.findFieldByNumber(10).getMessageType().getName().equals("HIMJKAMHLMJ"),"whole-native-optional-DJE8-and-mandatory-HIM10-types");
    var profile=protocol.clientSchema().message("HIMJKAMHLMJ");
    check(profile.findFieldByNumber(9).getMessageType().getName().equals("MOOKMKEKMKC")
        && profile.findFieldByNumber(12).getMessageType().getName().equals("DDAFEPBGOHK"),"whole-native-required-MOOK9-and-DDAF12-types");
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
    notifications.add(event); System.out.println("CHESS_QUALIFICATION_NOTIFICATION="+new Gson().toJson(event)); System.out.flush();
  }
  private Frame raw(int command,byte[] body,int expected) throws Exception {
    var bytes=Unpooled.wrappedBuffer(PacketCodec.encode(command,new byte[0],body));
    try { channel.write(bytes); } finally { bytes.release(); }
    long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(20);
    while (System.nanoTime()<until) {
      if (failure.get()!=null) throw new IllegalStateException("Native Chess connection failed",failure.get());
      var frame=inbox.poll(1,TimeUnit.SECONDS); if (frame==null) continue;
      if (frame.command()==expected) return frame;
      observe(frame);
    }
    throw new TimeoutException("Actual native Chess reply "+expected);
  }
  private DynamicMessage reply(String label,String name,int command,byte[] bytes,String response,int expected) throws Exception {
    requests++;
    var frame=raw(command,bytes,protocol.clientCommand(response));
    // Persist the actual frame before parsing or asserting. Authentication bodies stay redacted.
    var event=new LinkedHashMap<String,Object>();
    event.put("label",label); event.put("requestCommand",command); event.put("request",name);
    event.put("response",response); event.put("responseCommand",frame.command()); event.put("expectedRetcode",expected);
    if (!name.startsWith("PlayerGetToken")) event.put("observedBodyBase64",Base64.getEncoder().encodeToString(frame.body()));
    events.add(event); System.out.println("CHESS_REPLY_FRAME="+new Gson().toJson(event)); System.out.flush();
    var value=protocol.clientSchema().parse(response,frame.body());
    event.put("observedReply",view(value)); event.put("actualRetcode",number(value,"retcode"));
    event.put("unknownFields",value.getUnknownFields().asMap().keySet());
    System.out.println("CHESS_REPLY="+new Gson().toJson(event)); System.out.flush();
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
  private static final Set<String> MODES=Set.of("unqualified","unqualified-restart","qualified","qualified-restart");
  private void observation(BufferedReader control,int uid,String operation) throws Exception {
    System.out.println("CHESS_QUALIFICATION_READY_UID="+uid+":"+operation); System.out.flush();
    require(("CHESS_QUALIFICATION_OBSERVED:"+operation).equals(control.readLine()),"Missing exact console-only source/observation acknowledgement");
  }
  private DynamicMessage profile(String label,boolean qualified) throws Exception {
    var response=call(label,"ChessRogueQueryCsReq",Map.of(),0);
    check(!response.hasField(descriptor(response,8)),"initial-Query-omits-optional-DJE8-no-run-or-board-"+label);
    check(!response.hasField(descriptor(response,2)) && !response.hasField(descriptor(response,6)),"initial-Query-does-not-publish-finish-or-Quit-payload-"+label);
    check(response.hasField(descriptor(response,10)),"initial-Query-includes-mandatory-HIM10-"+label);
    var value=nested(response,10);
    check(field(value,11).equals(qualified?List.of(101):List.of()),"original8013104-only-controls-source101-unlocked-"+label);
    check(((List<?>)field(value,10)).isEmpty(),"qualification-does-not-claim-original-firstReward109011-"+label);
    check(value.hasField(descriptor(value,9)) && value.hasField(descriptor(value,12)),"nonnull-complete-MOOK9-and-DDAF12-before-native-consumers-"+label);
    check(nested(value,9).getAllFields().isEmpty() && nested(value,12).getAllFields().isEmpty(),"no-Nous-dice-talents-or-virtual281013-acquisitions-"+label);
    var fields=value.getAllFields().keySet().stream().map(Descriptors.FieldDescriptor::getNumber).collect(java.util.stream.Collectors.toSet());
    check(fields.equals(qualified?Set.of(9,11,12):Set.of(9,12)),"complete-bounded-source201-profile-projection-"+label);
    return response;
  }
  private Map<String,Object> execute(JsonObject input,BufferedReader control) throws Exception {
    int uid=number(callNamed("token","PlayerGetTokenCsReq",Map.of("account_uid",input.get("accountUid").getAsString(),
        "token",input.get("comboToken").getAsString(),"platform",1),0),"uid");
    call("PlayerLoginCsReq"); call("PlayerLoginFinishCsReq"); check(uid==10001,"same-owned-original-account-and-uid");
    var sceneBefore=nested(call("GetCurSceneInfoCsReq"),"scene");
    var lineupBefore=nested(call("GetCurLineupDataCsReq"),"lineup");
    var classicBefore=nested(call("GetRogueInfoCsReq"),"rogue_game_info");
    check(number(sceneBefore,"plane_id")==20001 && number(sceneBefore,"floor_id")==20001001
        && number(sceneBefore,"entry_id")==2000101,"ordinary-source-before-qualification-records");
    var roster=new ArrayList<Integer>();
    for(var raw:(List<?>)field(lineupBefore,"avatar_list")) {
      var row=(DynamicMessage)raw;
      check(((Descriptors.EnumValueDescriptor)field(row,"avatar_type")).getName().equals(FORMAL_AVATAR_ENUM),
          "actual-ordinary-roster-is-owned-formal");
      roster.add(number(row,"id"));
    }
    check(!roster.isEmpty() && roster.size()<=4,"use-actual-owned-ordinary-roster-without-grant");
    int negativeRequests=0;
    for(String mode:List.of("null","progress")) {
      observation(control,uid,"seed-"+mode); observation(control,uid,"before-"+mode);
      int beforeNotice=notifications.size();
      var first=profile("first-"+mode,false); var repeat=profile("repeat-"+mode,false);
      check(first.equals(repeat),"damaged-record-repeat-Query-identical-"+mode);
      for(int i=0;i<2;i++) {
        var rejected=call("damaged-original-qualification-Start-rejected-"+mode+"-"+i,
            "ChessRogueStartCsReq",Map.of(13,101,9,6,7,roster),-1);
        check(!rejected.hasField(descriptor(rejected,7)) && !rejected.hasField(descriptor(rejected,13)),
            "rejected-Start-does-not-publish-board-or-scene-"+mode+"-"+i);
      }
      negativeRequests+=4;
      check(notifications.size()==beforeNotice,"damaged-Query-Start-no-state-notifications-"+mode);
      observation(control,uid,"after-"+mode);
    }
    observation(control,uid,"qualify");
    // Drain ordered notifications from the explicit qualification fixture before Query sampling.
    var qualifiedScene=nested(call("GetCurSceneInfoCsReq"),"scene");
    check(sha(sceneBefore).equals(sha(qualifiedScene)),"qualification-notification-barrier-keeps-ordinary-scene");
    observation(control,uid,"before-valid");
    int beforeValidNotices=notifications.size();
    var valid=profile("first-valid",true); var validRepeat=profile("repeat-valid",true);
    check(valid.equals(validRepeat),"intact-completed-record-repeat-Query-identical");
    check(notifications.size()==beforeValidNotices,"legal-Query-no-state-notifications");
    observation(control,uid,"after-valid");
    check(sha(sceneBefore).equals(sha(nested(call("GetCurSceneInfoCsReq"),"scene"))),"all-qualification-queries-rejections-preserve-ordinary-scene");
    check(sha(lineupBefore).equals(sha(nested(call("GetCurLineupDataCsReq"),"lineup"))),"all-qualification-queries-rejections-preserve-ordinary-team");
    check(sha(classicBefore).equals(sha(nested(call("GetRogueInfoCsReq"),"rogue_game_info"))),"all-qualification-queries-rejections-preserve-classic-universe");
    observation(control,uid,"before-start");
    call("legal-completed-original-qualification-first-Start", "ChessRogueStartCsReq",Map.of(13,101,9,6,7,roster),0);
    var entered=nested(call("GetCurSceneInfoCsReq"),"scene");
    check(number(entered,"plane_id")==80201 && number(entered,"floor_id")==80201001
        && number(entered,"entry_id")==8020101,"legal-completed-record-actually-enters-original-guide-source-room");
    observation(control,uid,"after-start");
    var out=new LinkedHashMap<String,Object>(); out.put("passed",true); out.put("uid",uid);
    out.put("requests",requests); out.put("negativeRequests",negativeRequests); out.put("checks",checks);
    out.put("events",events); out.put("notifications",notifications); out.put("roster",roster);
    out.put("negativeRecordCases",List.of("null-phase","FINISHED-progress0")); out.put("legalRecordPhase","CLOSED");
    out.put("legalFirstStartSourceCell",201); out.put("querySaveCallsInstrumented",false);
    out.put("qualificationStoryVerified",false); out.put("clientGameplayVerified",false); out.put("merchantRequests",0);
    return out;
  }
  public static void main(String[] args) {
    var probe=new NativeChessRogueQualificationProbe();
    if (args.length==1 && args[0].equals("--verify-descriptors")) {
      probe.verifySchemas(); System.out.println(new Gson().toJson(Map.of("passed",true,"schemas",probe.schemas,"checks",probe.checks))); return;
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
            var frame=PacketCodec.decodeNext(bytes); require(frame!=null,"Truncated Chess frame");
            probe.inbox.add(new Frame(frame.cmdId(),frame.body()));
          } } catch (Throwable error) { probe.failure.set(error); }
        }
        public void handleException(Throwable error,Ukcp value) { probe.failure.set(error); probe.connected.completeExceptionally(error); }
        public void handleClose(Ukcp value) { probe.failure.compareAndSet(null,new IllegalStateException("Owned Chess probe disconnected")); }
      });
      client.connect(new InetSocketAddress("127.0.0.1",Integer.parseInt(args[0])),config);
      probe.channel=probe.connected.get(10,TimeUnit.SECONDS); System.out.println(new Gson().toJson(probe.execute(input,control))); status=0;
    } catch (Exception error) {
      System.err.println("FAILED_CHESS_OBSERVATIONS="+new Gson().toJson(probe.events)); error.printStackTrace(System.err);
    } finally { client.stop(); }
    System.exit(status);
  }
}
