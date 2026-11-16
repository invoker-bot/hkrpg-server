package org.hkrpg.server.protocol;

import com.google.gson.*;
import com.google.protobuf.DynamicMessage;
import emu.lunarcore.server.packet.*;
import io.netty.buffer.*;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import kcp.highway.*;

/** Local investigation scene publication over the original 4.6 UDP protocol. */
public final class NativeWantedPosterProbe {
  private record Frame(int command, byte[] body) {}

  private final ProtocolBridge protocol = ProtocolBridge.forVersion("4.6.0");
  private final BlockingQueue<Frame> inbox = new LinkedBlockingQueue<>();
  private final AtomicReference<Throwable> failure = new AtomicReference<>();
  private final CompletableFuture<Ukcp> connected = new CompletableFuture<>();
  private final List<String> checks = new ArrayList<>();
  private Ukcp channel;
  private DynamicMessage sceneNotification;
  private int requests;
  private final List<DynamicMessage> syncs = new ArrayList<>();

  private static Object field(DynamicMessage m, String name) {
    return m.getField(m.getDescriptorForType().findFieldByName(name));
  }

  private static int number(DynamicMessage m, String name) {
    return (Integer) field(m, name);
  }

  private static int status(DynamicMessage m) {
    return ((com.google.protobuf.Descriptors.EnumValueDescriptor) field(m, "status")).getNumber();
  }

  private static void require(boolean value, String reason) {
    if (!value) throw new IllegalStateException(reason);
  }

  private void check(boolean value, String reason) {
    require(value, reason);
    checks.add(reason);
  }

  private DynamicMessage.Builder request(String name, Map<String, Object> fields) {
    var b = DynamicMessage.newBuilder(protocol.clientSchema().message(name));
    fields.forEach(
        (key, value) -> b.setField(b.getDescriptorForType().findFieldByName(key), value));
    return b;
  }

  private DynamicMessage call(String name, Map<String, Object> fields, int code) throws Exception {
    return rawCall(name, request(name, fields).build().toByteArray(), code);
  }

  private DynamicMessage rawCall(String name, byte[] body, int code) throws Exception {
    requests++;
    var bytes =
        Unpooled.wrappedBuffer(PacketCodec.encode(protocol.clientCommand(name), new byte[0], body));
    try {
      channel.write(bytes);
    } finally {
      bytes.release();
    }
    String response = name.replace("CsReq", "ScRsp");
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
    while (System.nanoTime() < deadline) {
      if (failure.get() != null)
        throw new IllegalStateException("Scene event KCP failed", failure.get());
      var frame = inbox.poll(1, TimeUnit.SECONDS);
      if (frame == null) continue;
      if (frame.command() == protocol.clientCommand("PlayerSyncScNotify"))
        syncs.add(protocol.clientSchema().parse("PlayerSyncScNotify", frame.body()));
      if (frame.command() == protocol.clientCommand("EnterSceneByServerScNotify")) {
        sceneNotification = protocol.clientSchema().parse("EnterSceneByServerScNotify", frame.body());
      }
      if (frame.command() != protocol.clientCommand(response)) continue;
      var message = protocol.clientSchema().parse(response, frame.body());
      require(
          message.getUnknownFields().asMap().isEmpty(),
          "Unknown scene event response fields: " + response);
      String retcode = response.equals("FinishQuestScRsp") ? "PLDKENJLKME" : "retcode";
      require(
          number(message, retcode) == code,
          "Rejected " + response + " code=" + number(message, retcode) + " expected=" + code);
      return message;
    }
    throw new TimeoutException(response);
  }

  private DynamicMessage call(String name) throws Exception {
    return call(name, Map.of(), 0);
  }

  private Map<String, Object> execute(JsonObject input) throws Exception {
    int uid =
        number(
            call(
                "PlayerGetTokenCsReq",
                Map.of(
                    "account_uid",
                    input.get("accountUid").getAsString(),
                    "token",
                    input.get("comboToken").getAsString(),
                    "platform",
                    1),
                0),
            "uid");
    call("PlayerLoginCsReq");
    call("PlayerLoginFinishCsReq");
    String mode = input.get("mode").getAsString();
    if (mode.equals("warmup")) return Map.of("uid", uid, "requests", requests);

    call("GetMissionDataCsReq");
    var scene = currentScene();
    check(number(scene,"floor_id")==10101001,"Original administrative district");
    boolean baseline=mode.equals("baseline");
    checkScene(scene,baseline,mode.equals("recovered")?1:mode.equals("final")?7:0);
    if(mode.equals("baseline")) {
      for(int id=404010901;id<=404010907;id++) assertStage(id,1);
    } else if(mode.equals("exercise")) {
      for(int id=404010901;id<=404010907;id++) assertStage(id,1);
      call("FinishTalkMissionCsReq",Map.of("sub_mission_id",0,"talk_str","Mission_404010901"),0);
      assertStage(404010901,2);
      check(syncContains(404010901,2),"First completion notification delivered");
      checkScene(currentScene(),false,1);
      call("FinishTalkMissionCsReq",Map.of("sub_mission_id",0,"talk_str","Mission_404010901"),2400);
      assertStage(404010902,1);
    } else if(mode.equals("recovered")) {
      assertStage(404010901,2);
      for(int id=404010902;id<=404010907;id++)assertStage(id,1);
      checkScene(scene,false,1);
      for(int id=404010902;id<=404010907;id++) {
        call("FinishTalkMissionCsReq",Map.of("sub_mission_id",0,"talk_str","Mission_"+id),0);
        assertStage(id,2);
        checkScene(currentScene(),false,id-404010900);
      }
      assertStage(404010911,2);
    } else if(mode.equals("final")) {
      for(int id=404010901;id<=404010907;id++)assertStage(id,2);
      assertStage(404010911,2);
      checkScene(scene,false,7);
    } else throw new IllegalArgumentException(mode);
    return Map.of("uid",uid,"requests",requests,"mode",mode,"checks",checks,
        "clientGameplayVerified",false);
  }

  private DynamicMessage currentScene() throws Exception {
    return (DynamicMessage) field(call("GetCurSceneInfoCsReq"),"scene");
  }
  private void checkScene(DynamicMessage scene,boolean baseline,int done) {
    var groups=(List<?>)field(scene,"entity_group_list");
    for(int group=147;group<=153;group++) {
      int expected=group;
      boolean present=groups.stream().map(DynamicMessage.class::cast)
          .anyMatch(g->number(g,"group_id")==expected);
      check(present==baseline,"Expected publication for group "+group+" baseline="+baseline);
    }
    if(baseline)return;
    var gates=(DynamicMessage)field(scene,"scene_mission_info");
    check(((List<?>)field(gates,"unfinished_main_mission_id_list")).contains(4040109),"Original parent gate");
    var rows=(List<?>)field(gates,"sub_mission_status_list");
    for(int i=0;i<7;i++) {
      int expected=404010901+i,phase=i<done?2:1;
      check(rows.stream().map(DynamicMessage.class::cast)
          .anyMatch(r->number(r,"id")==expected && status(r)==phase),"Scene mission "+expected+" phase "+phase);
    }
  }
  private boolean syncContains(int id,int phase) {
    for(var msg:syncs) {
      var f=msg.getDescriptorForType().findFieldByName("mission_sync");
      if(!msg.hasField(f))continue;
      var mission=(DynamicMessage)msg.getField(f);
      for(Object row:(List<?>)field(mission,"mission_list"))
        if(number((DynamicMessage)row,"id")==id && status((DynamicMessage)row)==phase)return true;
    }
    return false;
  }
  private void assertStage(int id,int expected) throws Exception {
    var reply=call("GetMissionStatusCsReq",Map.of("sub_mission_id_list",List.of(id)),0);
    var rows=(List<?>)field(reply,"sub_mission_status_list");
    check(rows.size()==1 && number((DynamicMessage)rows.getFirst(),"id")==id
        && status((DynamicMessage)rows.getFirst())==expected,"Mission "+id+" phase "+expected);
  }

  public static void main(String[] args) {
    var probe = new NativeWantedPosterProbe();
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
                  require(frame != null, "Truncated scene event frame");
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
                  null, new IllegalStateException("Scene event probe disconnected"));
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
      if (connection != null) connection.close();
      client.stop();
    }
    System.exit(exit);
  }
}
