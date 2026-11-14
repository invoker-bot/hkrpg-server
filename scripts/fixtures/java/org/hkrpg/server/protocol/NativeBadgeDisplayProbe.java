package org.hkrpg.server.protocol;

import com.google.gson.*;
import com.google.protobuf.Descriptors.FieldDescriptor;
import com.google.protobuf.DynamicMessage;
import emu.lunarcore.server.packet.PacketCodec;
import io.netty.buffer.*;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import kcp.highway.*;

/** Native cabinet snapshot/replacement; restricted to the runner's isolated account and ports. */
public final class NativeBadgeDisplayProbe {
  private record Frame(int command, byte[] header, byte[] body) {}
  private final ProtocolBridge protocol = ProtocolBridge.forVersion("4.6.0");
  private final BlockingQueue<Frame> inbox = new LinkedBlockingQueue<>();
  private final AtomicReference<Throwable> failure = new AtomicReference<>();
  private final CompletableFuture<Ukcp> connected = new CompletableFuture<>();
  private Ukcp channel;
  private volatile boolean closing;
  private boolean loggedIn;
  private int requests;

  private static void require(boolean value, String message) {
    if (!value) throw new IllegalStateException(message);
  }
  private static FieldDescriptor descriptor(DynamicMessage message, String name) {
    var field = message.getDescriptorForType().findFieldByName(name);
    require(field != null, "Missing native field: " + name);
    return field;
  }
  private static Object field(DynamicMessage message, String name) {
    return message.getField(descriptor(message, name));
  }
  private static int number(DynamicMessage message, String name) {
    return (Integer) field(message, name);
  }
  private static DynamicMessage child(DynamicMessage message, String name) {
    require(message.hasField(descriptor(message, name)), "Missing native container: " + name);
    return (DynamicMessage) field(message, name);
  }
  private static void set(DynamicMessage.Builder message, String name, Object value) {
    message.setField(descriptor(message.buildPartial(), name), value);
  }
  private DynamicMessage.Builder builder(String name) {
    return DynamicMessage.newBuilder(protocol.clientSchema().message(name));
  }
  private void send(String name, DynamicMessage.Builder message) {
    var bytes = Unpooled.wrappedBuffer(PacketCodec.encode(protocol.clientCommand(name),
        new byte[] {8, 1}, message.build().toByteArray()));
    try { channel.write(bytes); requests++; } finally { bytes.release(); }
  }
  private DynamicMessage call(String name, DynamicMessage.Builder message) throws Exception {
    return callExpected(name, message, 0);
  }
  private DynamicMessage callExpected(String name, DynamicMessage.Builder message, int expected) throws Exception {
    String response = name.replace("CsReq", "ScRsp");
    send(name, message);
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
    while (System.nanoTime() < deadline) {
      require(failure.get() == null, "KCP transport failed");
      var frame = inbox.poll(250, TimeUnit.MILLISECONDS);
      if (frame == null) continue;
      if (frame.command == protocol.clientCommand("EnterSceneByServerScNotify")) {
        var scene = child(protocol.clientSchema().parse("EnterSceneByServerScNotify", frame.body), "scene");
        if (number(scene, "floor_id") == 10000003) checkScene(scene);
      }
      if (frame.command != protocol.clientCommand(response)) continue;
      var result = protocol.clientSchema().parse(response, frame.body);
      require(result.getUnknownFields().asMap().isEmpty(), "Unknown response fields");
      require(number(result, "retcode") == expected, "Unexpected native retcode for " + name + ": " + number(result, "retcode") + "; expected " + expected);
      return result;
    }
    throw new IllegalStateException("Timed out waiting for " + response);
  }
  private DynamicMessage call(String name) throws Exception { return call(name, builder(name)); }

  private static final long PERMANENT = 0xffffffffL;
  private void checkScene(DynamicMessage scene) {
    require(number(scene,"floor_id")==10000003 && number(scene,"plane_id")==10000,"Wrong train scene");
  }
  private DynamicMessage profile() throws Exception {
    return child(child(call("TrainPartyGetDataCsReq"),"train_party_data"),"train_party_info");
  }
  private DynamicMessage badgeInfo() throws Exception {
    var bytes=Unpooled.wrappedBuffer(PacketCodec.encode(7172,new byte[]{8,37},new byte[0]));
    try { channel.write(bytes); requests++; } finally { bytes.release(); }
    long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(15);
    while(System.nanoTime()<deadline) {
      var frame=inbox.poll(250,TimeUnit.MILLISECONDS);
      if(frame==null || frame.command!=7173) continue;
      require(Arrays.equals(frame.header,new byte[]{8,37}),"Badge reply lost the request ID");
      var response=protocol.clientSchema().parse("GetBadgeInfoScRsp",frame.body);
      require(number(response,"retcode")==0,"Badge query failed");
      return child(response,"AIANIJCIBGE");
    }
    throw new IllegalStateException("Timed out waiting for badge reply");
  }
  private Map<Integer,Long> badges(DynamicMessage info) {
    var result=new TreeMap<Integer,Long>();
    for(var raw:(List<?>)field(info,"CPMCCCODGAB")) {
      var row=(DynamicMessage)raw;
      require(result.put(number(row,"EIKLMFPMJJD"),(Long)field(row,"expire_time"))==null,"Duplicate badge");
    }
    return result;
  }
  private void awaitBadgeNotify(int expected) throws Exception {
    long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(15);
    while(System.nanoTime()<deadline) {
      var frame=inbox.poll(250,TimeUnit.MILLISECONDS);
      if(frame==null || frame.command!=7180) continue;
      var notify=protocol.clientSchema().parse("BadgeInfoScNotify",frame.body);
      if(badges(child(notify,"AIANIJCIBGE")).size()==expected) return;
    }
    throw new IllegalStateException("Expected badge update was not delivered");
  }
  private Map<Integer,Integer> positions(DynamicMessage info) {
    var display=child(info,"CMCNNJKJNPK"); var result=new TreeMap<Integer,Integer>();
    for(var raw:(List<?>)field(display,"POLMJFIKNHN")) {
      var row=(DynamicMessage)raw;
      require((Long)field(row,"expire_time")==PERMANENT,"Wrong display expiry");
      require(result.put(number(row,"dice_slot_id"),number(row,"EIKLMFPMJJD"))==null,"Duplicate slot");
    }
    return result;
  }
  private void setClosed(boolean closed,int expected) throws Exception {
    var req=builder("TrainPartySetBadgeAutoFillCsReq");set(req,"PELJACANAFH",closed);
    var rsp=callExpected("TrainPartySetBadgeAutoFillCsReq",req,expected);
    if(expected==0) require(field(rsp,"PELJACANAFH").equals(closed),"Wrong closed preference");
  }
  private void save(Map<Integer,Integer> positions) throws Exception {
    var data=builder("OIIPJFDNPAH");
    for(var entry:positions.entrySet()) {
      var row=builder("POFNNGOCEOK");set(row,"dice_slot_id",entry.getKey());
      set(row,"EIKLMFPMJJD",entry.getValue());set(row,"expire_time",PERMANENT);
      data.addRepeatedField(descriptor(data.buildPartial(),"POLMJFIKNHN"),row.build());
    }
    var req=builder("TrainPartyBuildSelfDisplayAreaDiyCsReq");set(req,"ACLKMFCDMED",data.build());
    call("TrainPartyBuildSelfDisplayAreaDiyCsReq",req);
  }
  private static void grantFixtureBadge(int http,int uid,int item) throws Exception {
    var directory=java.nio.file.Path.of("").toRealPath();
    require(directory.getFileName().toString().startsWith("player-room-badge-")
        && directory.getParent().getFileName().toString().equals("smoke")
        && directory.getParent().getParent().getFileName().toString().equals("build"),"Not a disposable fixture");
    var config=JsonParser.parseString(java.nio.file.Files.readString(directory.resolve("config.json"))).getAsJsonObject();
    var httpConfig=config.getAsJsonObject("httpServer"); var game=config.getAsJsonObject("gameServer");
    require(http!=18080 && httpConfig.get("bindAddress").getAsString().equals("127.0.0.1")
        && httpConfig.get("bindPort").getAsInt()==http && game.get("bindPort").getAsInt()!=23311
        && game.get("bindAddress").getAsString().equals("127.0.0.1")
        && Set.of(230900,230901).contains(item),"Refusing an operator server");
    String base="http://127.0.0.1:"+http;
    var cookies=new java.net.CookieManager(null,java.net.CookiePolicy.ACCEPT_ALL);
    var client=java.net.http.HttpClient.newBuilder().cookieHandler(cookies).build();
    var open=java.net.http.HttpRequest.newBuilder(java.net.URI.create(base+"/gm/api/session"))
        .header("Origin",base).header("Content-Type","application/json")
        .POST(java.net.http.HttpRequest.BodyPublishers.ofString("{}")).build();
    var session=client.send(open,java.net.http.HttpResponse.BodyHandlers.ofString());
    require(session.statusCode()==200,"Synthetic console session failed");
    var sessionJson=JsonParser.parseString(session.body()).getAsJsonObject();
    require(sessionJson.get("ok").getAsBoolean(),"Synthetic console session rejected");
    String csrf=sessionJson.getAsJsonObject("data").get("csrf").getAsString();
    var body=new JsonObject();body.addProperty("requestId",UUID.randomUUID().toString());
    body.addProperty("action","grant");body.addProperty("uid",uid);body.addProperty("itemId",item);
    body.addProperty("amount",1);body.addProperty("level",0);body.addProperty("rank",0);
    var request=java.net.http.HttpRequest.newBuilder(java.net.URI.create(base+"/gm/api/operate"))
        .header("Origin",base).header("Content-Type","application/json").header("X-GM-CSRF",csrf)
        .POST(java.net.http.HttpRequest.BodyPublishers.ofString(body.toString())).build();
    var result=client.send(request,java.net.http.HttpResponse.BodyHandlers.ofString());
    require(result.statusCode()==200,"Synthetic item grant failed");
    var json=JsonParser.parseString(result.body()).getAsJsonObject();
    require(json.get("ok").getAsBoolean() && json.getAsJsonObject("data").get("state").getAsString().equals("completed"),
        "Synthetic item grant not completed");
  }
  private Map<String,Object> execute(JsonObject control) throws Exception {
    var token=builder("PlayerGetTokenCsReq");set(token,"account_uid",control.get("accountUid").getAsString());
    set(token,"token",control.get("comboToken").getAsString());set(token,"platform",1);
    int uid=number(call("PlayerGetTokenCsReq",token),"uid");
    call("PlayerLoginCsReq");loggedIn=true;call("PlayerLoginFinishCsReq");
    var enter=builder("EnterSceneCsReq");set(enter,"entry_id",100000302);
    call("EnterSceneCsReq",enter);checkScene(child(call("GetCurSceneInfoCsReq"),"scene"));
    String mode=control.get("mode").getAsString();int http=control.get("httpPort").getAsInt();
    if(mode.equals("locked")) {
      require(badges(badgeInfo()).isEmpty(),"A new player received a badge");setClosed(false,1215);
    } else if(mode.equals("place")) {
      require(badges(badgeInfo()).isEmpty(),"Fixture unexpectedly owned a badge");
      setClosed(false,0);grantFixtureBadge(http,uid,230900);awaitBadgeNotify(1);
      require(badges(badgeInfo()).equals(Map.of(230900,PERMANENT)),"First badge not actually owned");
      require(positions(profile()).equals(Map.of(1600106,230900)),"First badge was not auto-filled");
      save(Map.of(1600110,230900));setClosed(true,0);
      grantFixtureBadge(http,uid,230901);awaitBadgeNotify(2);
      require(positions(profile()).equals(Map.of(1600110,230900)),"Closed auto-fill changed manual position");
      setClosed(false,0);
      require(positions(profile()).equals(Map.of(1600106,230901,1600110,230900)),"Re-enabled fill did not preserve positions");
    } else {
      require(badges(badgeInfo()).equals(Map.of(230900,PERMANENT,230901,PERMANENT)),"Earned badges did not persist");
      var expected=mode.equals("verify")?Map.of(1600106,230901,1600110,230900):Map.of(1600110,230900);
      require(positions(profile()).equals(expected),"Saved placement changed after login");
      if(mode.equals("verify")) {
        save(Map.of(1600110,230900));
        require(positions(profile()).equals(Map.of(1600110,230900)),"Manual removal was refilled immediately");
      } else if(mode.equals("restart")) {
        setClosed(true,0);setClosed(false,0);
        require(positions(profile()).equals(Map.of(1600106,230901,1600110,230900)),"Re-enabled fill did not recover");
      }
    }
    var result=new LinkedHashMap<String,Object>(); result.put("passed",true);result.put("playerUid",uid);
    result.put("mode",mode);result.put("badgeReplyRequestIdVerified",true);result.put("clientGameplayVerified",false);
    return result;
  }
  private void logout() throws InterruptedException {
    if (!loggedIn || closing) return;
    closing = true;
    send("PlayerLogoutCsReq", builder("PlayerLogoutCsReq"));
    Thread.sleep(250);
  }
  public static void main(String[] args) {
    var probe = new NativeBadgeDisplayProbe();
    var client = new KcpClient();
    Ukcp connection = null;
    int exit = 1;
    try {
      var control = JsonParser.parseReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))
          .getAsJsonObject();
      var config = new ChannelConfig();
      config.nodelay(true, 40, 2, true); config.setMtu(1200);
      config.setSndwnd(256); config.setRcvwnd(256); config.setUseConvChannel(true);
      config.setConv(ThreadLocalRandom.current().nextInt(1, Integer.MAX_VALUE));
      config.setTimeoutMillis(15000); config.setAckNoDelay(true);
      client.init(config, new KcpListener() {
        public void onConnected(Ukcp value) { probe.connected.complete(value); }
        public void handleReceive(ByteBuf bytes, Ukcp value) {
          try {
            while (bytes.isReadable()) {
              var frame = PacketCodec.decodeNext(bytes);
              require(frame != null, "Truncated packet");
              probe.inbox.add(new Frame(frame.cmdId(), frame.header(), frame.body()));
            }
          } catch (Throwable error) { probe.failure.compareAndSet(null, error); }
        }
        public void handleException(Throwable error, Ukcp value) {
          if (!probe.closing) probe.failure.compareAndSet(null, error);
          probe.connected.completeExceptionally(error);
        }
        public void handleClose(Ukcp value) {
          if (!probe.closing)
            probe.failure.compareAndSet(null, new IllegalStateException("Unexpected disconnect"));
        }
      });
      connection = client.connect(new InetSocketAddress("127.0.0.1", Integer.parseInt(args[0])), config);
      probe.channel = probe.connected.get(10, TimeUnit.SECONDS);
      var result = probe.execute(control);
      probe.logout();
      result.put("normalLogoutRequested", true); result.put("nativeRequests", probe.requests);
      System.out.println(new Gson().toJson(result));
      exit = 0;
    } catch (Exception error) {
      System.err.println("Badge display probe failed: " + error.getClass().getSimpleName());
      System.err.println("Contract: " + error.getMessage());
    } finally {
      try { probe.logout(); } catch (Exception ignored) { /* Runner verifies cleanup. */ }
      if (connection != null) connection.close();
      client.stop();
    }
    System.exit(exit);
  }
}
