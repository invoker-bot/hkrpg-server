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

/** Source-bound teaching handoff, ordinary-run continuation and real enhancement requests; no UI. */
public final class NativeRogueTeachingFlowProbe {
  private record Frame(int command, byte[] body) {}

  private final ProtocolBridge protocol = ProtocolBridge.forVersion("4.6.0");
  private final BlockingQueue<Frame> inbox = new LinkedBlockingQueue<>();
  private final AtomicReference<Throwable> failure = new AtomicReference<>();
  private final CompletableFuture<Ukcp> connected = new CompletableFuture<>();
  private Ukcp channel;
  private int requests;

  private static Object field(DynamicMessage m, String n) {
    return m.getField(Objects.requireNonNull(m.getDescriptorForType().findFieldByName(n), n));
  }

  private static int number(DynamicMessage m, String n) {
    return (Integer) field(m, n);
  }

  private static void require(boolean ok, String why) {
    if (!ok) throw new IllegalStateException(why);
  }

  private void observe(Frame frame) {}

  private Map<Integer,String> goals() throws Exception {
    var reply=call("GetMissionStatusCsReq",Map.of("sub_mission_id_list",List.of(
        401011601,401011602,401011603,401011604,403000300,403000301,403000303,403000304)));
    var result=new TreeMap<Integer,String>();
    for(var raw:(List<?>)field(reply,"sub_mission_status_list")) {
      var row=(DynamicMessage)raw;
      result.put(number(row,"id"),((Descriptors.EnumValueDescriptor)field(row,"status")).getName());
    }
    return result;
  }

  private void finished(Map<Integer,String> goals,int id) {
    require("MissionStatus_MissionFinish".equals(goals.get(id)),"Original goal not finished: "+id+"="+goals);
  }

  private void doing(Map<Integer,String> goals,int id) {
    require("MissionStatus_MissionDoing".equals(goals.get(id)),"Original goal not doing: "+id+"="+goals);
  }

  private void herta(DynamicMessage scene) {
    require(number(scene,"entry_id")==8000102,"Not in the original rest room");
    boolean found=false;
    for(var group:(List<?>)field(scene,"entity_group_list"))
      for(var raw:(List<?>)field((DynamicMessage)group,"entity_list")) {
        var row=(DynamicMessage)raw;
        var npc=row.getDescriptorForType().findFieldByName("npc");
        if(number(row,"group_id")==34 && number(row,"inst_id")==400001 && row.hasField(npc))
          found=number((DynamicMessage)field(row,"npc"),"npc_id")==1013;
      }
    require(found,"Original G34 Herta is missing");
  }

  private List<?> quotes() throws Exception {
    var response=call("GetRogueBuffEnhanceInfoCsReq");
    require(number(response,"PLDKENJLKME")==0,"Original Herta enhancement context rejected");
    return (List<?>)field((DynamicMessage)field(response,"retcode"),"EMAGFIBLNCJ");
  }

  private void enhancement(boolean repeat) throws Exception {
    var bytes=new java.io.ByteArrayOutputStream();
    var out=com.google.protobuf.CodedOutputStream.newInstance(bytes);
    out.writeUInt32(13,600000);out.flush();requests++;
    var reply=raw(1885,bytes.toByteArray(),1807);
    var in=com.google.protobuf.CodedInputStream.newInstance(reply.body());
    int code=0;boolean buff=false;
    for(int tag;(tag=in.readTag())!=0;) {
      if(tag==48)code=in.readUInt32();
      else if(tag==26) {
        var value=protocol.clientSchema().parse("KJAMNOGFAMJ",in.readByteArray());
        buff=number(value,"buff_id")==600000 && number(value,"HAGDKKBAAKK")==2;
      } else require(in.skipField(tag),"Malformed enhancement response");
    }
    require(repeat ? code!=0 && !buff : code==0 && buff,"Enhancement or repeat did not use native contract");
  }

  private Map<String,Object> execute(JsonObject credentials) throws Exception {
    String mode=credentials.get("mode").getAsString();
    int uid=number(call("PlayerGetTokenCsReq",Map.of("account_uid",credentials.get("accountUid").getAsString(),
        "token",credentials.get("comboToken").getAsString(),"platform",1)),"uid");
    call("PlayerLoginCsReq");call("PlayerLoginFinishCsReq");
    if(mode.equals("create"))return Map.of("uid",uid,"passed",true,"mode",mode,"requests",requests,"clientGameplayVerified",false);
    var before=goals();
    for(int id:List.of(401011601,401011602,401011603))finished(before,id);
    DynamicMessage scene=(DynamicMessage)field(call("GetCurSceneInfoCsReq"),"scene");
    if(mode.equals("fresh")) {
      doing(before,403000300);
      call("FinishTalkMissionCsReq",Map.of("sub_mission_id",403000300,"talk_str","Mission_403000300"));
      var started=call("StartRogueCsReq",Map.of("area_id",110,"NOEPBNMJFGL",List.of(emu.lunarcore.GameConstants.TRAILBLAZER_AVATAR_ID)));
      var game=(DynamicMessage)field(started,"rogue_game_info");
      var map=(DynamicMessage)field((DynamicMessage)field(game,"CFDIKLBINHK"),"COOOMDKGODF");
      require(number(map,"map_id")==3 && number(map,"HPKMGKPPELC")==301,"First teaching used an ordinary map");
      scene=(DynamicMessage)field(started,"scene");
      require(number(scene,"entry_id")==8000102,"Teaching did not enter authored first room");
      finished(goals(),403000301);
    } else {
      herta(scene);
      if(mode.equals("exercise")) {
        doing(before,403000300);
        require(!"MissionStatus_MissionDoing".equals(before.get(403000304)),"Unplayed introduction was skipped");
        var left=call("LeaveRogueCsReq");
        var office=(DynamicMessage)field(left,"scene");
        require(number(office,"entry_id")==8011201,"Pause did not return to the original office");
        call("FinishTalkMissionCsReq",Map.of("sub_mission_id",403000300,"talk_str","Mission_403000300"));
        var resumed=call("EnterRogueCsReq",Map.of("area_id",110));
        herta((DynamicMessage)field(resumed,"scene"));
        var ready=goals();finished(ready,403000300);finished(ready,403000301);finished(ready,403000303);doing(ready,403000304);
        require(quotes().size()==1,"Original held blessing did not receive a quote");
        enhancement(false);enhancement(true);
        call("FinishTalkMissionCsReq",Map.of("sub_mission_id",403000304,"talk_str","Mission_403000304"));
      }
      var after=goals();finished(after,403000300);finished(after,403000303);finished(after,403000304);
      require(quotes().isEmpty(),"Enhanced blessing was offered or charged twice");
    }
    return Map.of("uid",uid,"passed",true,"mode",mode,"goals",goals(),"requests",requests,
        "clientGameplayVerified",false,"allRoomsPlayed",false);
  }

  private Frame raw(int command, byte[] payload, int response) throws Exception {
    var bytes = Unpooled.wrappedBuffer(PacketCodec.encode(command, new byte[0], payload));
    try {
      channel.write(bytes);
    } finally {
      bytes.release();
    }
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
    while (System.nanoTime() < deadline) {
      if (failure.get() != null)
        throw new IllegalStateException(
            "Native first world entry connection failed", failure.get());
      var f = inbox.poll(1, TimeUnit.SECONDS);
      if (f == null) continue;
      observe(f);
      if (f.command() == response) return f;
    }
    throw new TimeoutException("Response " + response);
  }

  private DynamicMessage call(String name, Map<String, Object> fields) throws Exception {
    requests++;
    var b = DynamicMessage.newBuilder(protocol.clientSchema().message(name));
    fields.forEach(
        (key, value) -> b.setField(b.getDescriptorForType().findFieldByName(key), value));
    String response = name.replace("CsReq", "ScRsp");
    var frame =
        raw(
            protocol.clientCommand(name),
            b.build().toByteArray(),
            protocol.clientCommand(response));
    var m = protocol.clientSchema().parse(response, frame.body());
    require(m.getUnknownFields().asMap().isEmpty(), "Unknown first world entry response fields");
    if (!name.equals("GetRogueBuffEnhanceInfoCsReq"))
      require(
          number(m, "retcode") == 0, "Unexpected " + response + " status=" + number(m, "retcode"));
    return m;
  }

  private DynamicMessage call(String name) throws Exception {
    return call(name, Map.of());
  }

  public static void main(String[] args) {
    var probe = new NativeRogueTeachingFlowProbe();
    var client = new KcpClient();
    Ukcp connection = null;
    int exit = 1;
    try {
      var control =
          new java.io.BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
      var credentials = JsonParser.parseString(control.readLine()).getAsJsonObject();
      var config = new ChannelConfig();
      config.nodelay(true, 40, 2, true);
      config.setMtu(1400);
      config.setSndwnd(256);
      config.setRcvwnd(256);
      config.setUseConvChannel(true);
      config.setConv(ThreadLocalRandom.current().nextInt(1, Integer.MAX_VALUE));
      config.setTimeoutMillis(25000);
      config.setAckNoDelay(true);
      client.init(
          config,
          new KcpListener() {
            public void onConnected(Ukcp c) {
              probe.connected.complete(c);
            }

            public void handleReceive(ByteBuf bytes, Ukcp c) {
              try {
                while (bytes.isReadable()) {
                  var frame = PacketCodec.decodeNext(bytes);
                  require(frame != null, "Truncated first world entry frame");
                  probe.inbox.add(new Frame(frame.cmdId(), frame.body()));
                }
              } catch (Throwable error) {
                probe.failure.set(error);
              }
            }

            public void handleException(Throwable error, Ukcp c) {
              probe.failure.set(error);
              probe.connected.completeExceptionally(error);
            }

            public void handleClose(Ukcp c) {
              probe.failure.compareAndSet(
                  null, new IllegalStateException("First world entry probe disconnected"));
            }
          });
      connection =
          client.connect(new InetSocketAddress("127.0.0.1", Integer.parseInt(args[0])), config);
      probe.channel = probe.connected.get(10, TimeUnit.SECONDS);
      System.out.write(
          (new Gson().toJson(probe.execute(credentials)) + "\n").getBytes(StandardCharsets.UTF_8));
      System.out.flush();
      exit = 0;
    } catch (Exception error) {
      error.printStackTrace(System.err);
    } finally {
      // stop() already closes all channels; a second close races the KCP listener cleanup.
      client.stop();
    }
    System.exit(exit);
  }
}
