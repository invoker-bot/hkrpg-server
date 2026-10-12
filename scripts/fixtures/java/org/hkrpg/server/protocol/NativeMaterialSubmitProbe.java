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

/** Original seven material submissions over real local KCP, inventory and rewards. */
public final class NativeMaterialSubmitProbe {
  private record Frame(int command, byte[] body) {}
  private final ProtocolBridge protocol = ProtocolBridge.forVersion("4.6.0");
  private final BlockingQueue<Frame> inbox = new LinkedBlockingQueue<>();
  private final AtomicReference<Throwable> failure = new AtomicReference<>();
  private final CompletableFuture<Ukcp> connected = new CompletableFuture<>();
  private final List<Map<String,Object>> events = new ArrayList<>(), notifications = new ArrayList<>();
  private final List<String> checks = new ArrayList<>();
  private final Map<String,Map<String,Integer>> schemas = new LinkedHashMap<>();
  private Ukcp channel;
  private int requests;

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
    if (value instanceof List<?> rows) return rows.stream().map(NativeMaterialSubmitProbe::view).toList();
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
        "org.hkrpg.server.protocol.MaterialSubmit46","org.hkrpg.server.gameplay.GameplayState","org.hkrpg.server.gameplay.MaterialSubmitManager",
        "org.hkrpg.server.gameplay.MaterialSubmitState","org.hkrpg.server.gameplay.MaterialSubmitDefinitions","emu.lunarcore.game.player.Player","emu.lunarcore.LunarCore")) {
      var type=Class.forName(name,false,getClass().getClassLoader());
      result.put(name,type.getProtectionDomain().getCodeSource().getLocation().toURI().toString());
    }
    return result;
  }
  private void verifySchemas() {
    schema("GetMaterialSubmitActivityDataCsReq");schema("GetMaterialSubmitActivityDataScRsp",2,8);
    schema("SubmitMaterialSubmitActivityMaterialCsReq",8);schema("SubmitMaterialSubmitActivityMaterialScRsp",6,8);
    schema("TakeMaterialSubmitActivityRewardCsReq",7);schema("TakeMaterialSubmitActivityRewardScRsp",3,7,15);
    schema("KPMAKCHCDMH",8,9,13,15);namedSchema("ItemList","item_list");
    namedSchema("Item","item_id","num");
    namedSchema("PlayerGetTokenCsReq","account_uid","token","platform");
    namedSchema("GetCurSceneInfoScRsp","scene");namedSchema("GetCurLineupDataScRsp","lineup");
    check(protocol.clientCommand("GetMaterialSubmitActivityDataCsReq")==2621
        && protocol.clientCommand("GetMaterialSubmitActivityDataScRsp")==2612
        && protocol.clientCommand("SubmitMaterialSubmitActivityMaterialCsReq")==2611
        && protocol.clientCommand("SubmitMaterialSubmitActivityMaterialScRsp")==2617
        && protocol.clientCommand("TakeMaterialSubmitActivityRewardCsReq")==2638
        && protocol.clientCommand("TakeMaterialSubmitActivityRewardScRsp")==2678,"six-source-material-native-commands");
    check(protocol.clientSchema().message("GetMaterialSubmitActivityDataCsReq").getFields().isEmpty(),"native-query-actual-empty-request");
    for(int id:List.of(8,13))check(protocol.clientSchema().message("KPMAKCHCDMH").findFieldByNumber(id).getType()==Descriptors.FieldDescriptor.Type.BOOL,"native-stored-bool-"+id);
    check(protocol.clientSchema().message("KPMAKCHCDMH").findFieldByNumber(15).getType()==Descriptors.FieldDescriptor.Type.UINT32,"native-ID15-uint32");
    check(protocol.clientSchema().message("TakeMaterialSubmitActivityRewardScRsp").findFieldByNumber(15).getMessageType().getName().equals("ItemList"),"native-nonnull-reward-cache-ItemList15");
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
    notifications.add(event); System.out.println("MATERIAL_SUBMIT_NOTIFICATION="+new Gson().toJson(event)); System.out.flush();
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
    events.add(event); System.out.println("MATERIAL_REPLY_FRAME="+new Gson().toJson(event)); System.out.flush();
    var value=protocol.clientSchema().parse(response,frame.body());
    event.put("observedReply",view(value)); event.put("actualRetcode",number(value,"retcode"));
    event.put("unknownFields",value.getUnknownFields().asMap().keySet());
    System.out.println("MATERIAL_REPLY="+new Gson().toJson(event)); System.out.flush();
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
    System.out.println("MATERIAL_SUBMIT_READY_UID="+uid+":"+operation);System.out.flush();
    require(("MATERIAL_SUBMIT_OBSERVED:"+operation).equals(control.readLine()),"Missing exact console observation acknowledgement");
  }
  private void state(DynamicMessage row,int id,boolean submitted,boolean taken,String label) {
    check(number(row,15)==id && Boolean.valueOf(submitted).equals(field(row,8)) && Boolean.valueOf(taken).equals(field(row,13)),"native-state-ID15-submitted8-taken13-"+label);
    check(number(row,9)==0 && !row.hasField(descriptor(row,9)),"unknown-state9-is-not-invented-"+label);
  }
  private DynamicMessage query(String label,int count) throws Exception {
    var response=call(label,"GetMaterialSubmitActivityDataCsReq",Map.of(),0);
    var rows=(List<?>)field(response,8);check(rows.size()==count,"only-actual-saved-material-records-"+label);
    for(int index=0;index<count;index++)state((DynamicMessage)rows.get(index),301+index,true,true,label+"-"+index);
    return response;
  }
  private void submit(String label,int id,boolean taken) throws Exception {
    var response=call(label,"SubmitMaterialSubmitActivityMaterialCsReq",Map.of(8,id),0);
    check(response.hasField(descriptor(response,8)),"success-contains-nonnull-native-state-"+label);state(nested(response,8),id,true,taken,label);
  }
  private void take(String label,int id,Map<Integer,Integer> rewards) throws Exception {
    var response=call(label,"TakeMaterialSubmitActivityRewardCsReq",Map.of(7,id),0);
    check(number(response,3)==id && response.hasField(descriptor(response,15)),"success-contains-ID3-and-nonnull-ItemList15-"+label);
    var rows=(List<?>)field(nested(response,15),"item_list");var actual=new TreeMap<Integer,Integer>();
    for(var value:rows) {var item=(DynamicMessage)value;check(actual.put(number(item,"item_id"),number(item,"num"))==null,"no-duplicate-reward-item-"+label);}
    check(actual.equals(rewards),"complete-original-reward-ItemList-"+label);
  }
  private void rejected(String label,String request,byte[] body) throws Exception {
    String responseName=request.replace("CsReq","ScRsp");
    var response=reply(label,request,protocol.clientCommand(request),body,responseName,-1);
    int successField=request.startsWith("Submit")?8:request.startsWith("Take")?15:8;
    check(response.getAllFields().keySet().stream().noneMatch(f->f.getNumber()==successField),"rejection-publishes-no-fake-state-or-items-"+label);
    if(request.startsWith("Take"))check(number(response,3)==0,"rejection-does-not-invent-taken-ID-"+label);
  }
  private static byte[] unsignedBody(int field,long value) throws Exception {
    var out=new ByteArrayOutputStream();var wire=CodedOutputStream.newInstance(out);wire.writeUInt64(field,value);wire.flush();return out.toByteArray();
  }
  private Map<String,Object> execute(JsonObject input,BufferedReader control) throws Exception {
    String mode=input.get("mode").getAsString();require(Set.of("initial","restart").contains(mode),"Unowned material phase");
    verifySchemas();var origins=productionOrigins();var path=java.nio.file.Path.of(java.net.URI.create(input.get("jarSourceURI").getAsString())).toRealPath();
    for(String origin:origins.values())check(java.nio.file.Path.of(java.net.URI.create(origin)).toRealPath().equals(path),"production-CodeSource-single-frozen-jar-"+origin);
    int uid=number(callNamed("token","PlayerGetTokenCsReq",Map.of("account_uid",input.get("accountUid").getAsString(),"token",input.get("comboToken").getAsString(),"platform",1),0),"uid");
    call("PlayerLoginCsReq");call("PlayerLoginFinishCsReq");check(uid==10001,"same-isolated-account-UID");
    var scene=nested(call("GetCurSceneInfoCsReq"),"scene");var team=nested(call("GetCurLineupDataCsReq"),"lineup");
    var rewards=new TreeMap<Integer,Map<Integer,Integer>>();
    input.getAsJsonObject("rewards").entrySet().forEach(row->{var value=new TreeMap<Integer,Integer>();row.getValue().getAsJsonObject().entrySet().forEach(e->value.put(Integer.parseInt(e.getKey()),e.getValue().getAsInt()));rewards.put(Integer.parseInt(row.getKey()),value);});
    int negative=0;
    if(mode.equals("initial")) {
      observation(control,uid,"before-empty");var first=query("empty-first",0);var repeat=query("empty-repeat",0);check(first.equals(repeat),"empty-query-repeat-identical");observation(control,uid,"after-empty");
      observation(control,uid,"before-no-window");rejected("no-operator-window","SubmitMaterialSubmitActivityMaterialCsReq",unsignedBody(8,301));negative++;observation(control,uid,"after-no-window");
      observation(control,uid,"seed");observation(control,uid,"before-owned");query("stock-does-not-create-submission",0);observation(control,uid,"after-owned");
      observation(control,uid,"before-rejects");
      rejected("previous-not-submitted","SubmitMaterialSubmitActivityMaterialCsReq",unsignedBody(8,302));negative++;
      rejected("not-submitted-no-reward","TakeMaterialSubmitActivityRewardCsReq",unsignedBody(7,301));negative++;
      for(long id:new long[]{1,0,0x10000012dL,0xffffffffL}) {rejected("invalid-ID-"+id,"SubmitMaterialSubmitActivityMaterialCsReq",unsignedBody(8,id));negative++;}
      rejected("duplicate-ID","SubmitMaterialSubmitActivityMaterialCsReq",new byte[]{64,(byte)0xad,2,64,(byte)0xad,2});negative++;
      rejected("unknown-field","SubmitMaterialSubmitActivityMaterialCsReq",new byte[]{64,(byte)0xad,2,8,1});negative++;
      rejected("truncated-wire","SubmitMaterialSubmitActivityMaterialCsReq",new byte[]{64,(byte)0x80});negative++;
      rejected("nonempty-query","GetMaterialSubmitActivityDataCsReq",new byte[]{8,1});negative++;
      observation(control,uid,"after-rejects");observation(control,uid,"before-first");
      submit("first-submit",301,false);take("first-source-reward",301,rewards.get(301));query("first-completed-state",1);observation(control,uid,"after-first");
      observation(control,uid,"before-repeat");submit("same-submit-no-second-debit",301,true);rejected("same-take-no-second-payment","TakeMaterialSubmitActivityRewardCsReq",unsignedBody(7,301));negative++;query("repeat-state",1);observation(control,uid,"after-repeat");
      observation(control,uid,"before-final-query");first=query("initial-final1",1);repeat=query("initial-final2",1);check(first.equals(repeat),"initial-final-repeat-identical");observation(control,uid,"after-final-query");
    } else {
      observation(control,uid,"windows");observation(control,uid,"before-restored");var first=query("restored1",1);var repeat=query("restored2",1);check(first.equals(repeat),"same-UID-real-H2-restored-query-identical");observation(control,uid,"after-restored");
      observation(control,uid,"before-rest-repeats");submit("restored-same-submit",301,true);rejected("restored-same-take","TakeMaterialSubmitActivityRewardCsReq",unsignedBody(7,301));negative++;observation(control,uid,"after-rest-repeats");
      observation(control,uid,"before-complete");for(int id=302;id<=307;id++){submit("submit-"+id,id,false);take("take-"+id,id,rewards.get(id));query("complete-through-"+id,id-300);}observation(control,uid,"after-complete");
      observation(control,uid,"before-complete-repeat");for(int id=301;id<=307;id++){submit("complete-same-submit-"+id,id,true);rejected("complete-same-take-"+id,"TakeMaterialSubmitActivityRewardCsReq",unsignedBody(7,id));negative++;}observation(control,uid,"after-complete-repeat");
      observation(control,uid,"before-complete-query");first=query("complete-final1",7);repeat=query("complete-final2",7);check(first.equals(repeat),"complete-final-readonly-repeat-identical");observation(control,uid,"after-complete-query");
    }
    check(scene.equals(nested(call("GetCurSceneInfoCsReq"),"scene")),"material-flow-preserves-ordinary-scene");check(team.equals(nested(call("GetCurLineupDataCsReq"),"lineup")),"material-flow-preserves-ordinary-team");
    var out=new LinkedHashMap<String,Object>();out.put("passed",true);out.put("uid",uid);out.put("mode",mode);out.put("requests",requests);out.put("negativeRequests",negative);out.put("checks",checks);out.put("events",events);out.put("notifications",notifications);out.put("productionCodeSources",origins);out.put("clientGameplayVerified",false);out.put("crashAtomicityVerified",false);return out;
  }
  public static void main(String[] args) {
    var probe=new NativeMaterialSubmitProbe();
    if (args.length==1 && args[0].equals("--verify-descriptors")) {
      try {
        probe.verifySchemas();var origins=probe.productionOrigins();String expected=System.getProperty("material.expectedProductionJar");
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
      System.err.println("FAILED_MATERIAL_OBSERVATIONS="+new Gson().toJson(probe.events)); error.printStackTrace(System.err);
    } finally { client.stop(); }
    System.exit(status);
  }
}
