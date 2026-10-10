package org.hkrpg.server.protocol;

import com.google.gson.*;
import com.google.protobuf.*;
import emu.lunarcore.server.packet.PacketCodec;
import io.netty.buffer.*;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import kcp.highway.*;

/**
 * External fixture probe: actual native state reports, saved scene snapshots and explicit goals.
 */
public final class NativePropTimelineProbe {
  private record Frame(int command, byte[] body) {}

  private final ProtocolBridge protocol = ProtocolBridge.forVersion("4.6.0");
  private final BlockingQueue<Frame> inbox = new LinkedBlockingQueue<>();
  private final AtomicReference<Throwable> failure = new AtomicReference<>();
  private final CompletableFuture<Ukcp> connected = new CompletableFuture<>();
  private Ukcp channel;
  private int requests, reports, rejected;

  private static Object field(DynamicMessage m, String key) {
    return m.getField(m.getDescriptorForType().findFieldByName(key));
  }

  private static int number(DynamicMessage m, String key) {
    var v = field(m, key);
    return v instanceof com.google.protobuf.Descriptors.EnumValueDescriptor e
        ? e.getNumber()
        : (Integer) v;
  }

  private static void set(DynamicMessage.Builder b, String key, Object value) {
    b.setField(b.getDescriptorForType().findFieldByName(key), value);
  }

  private static void require(boolean valid, String why) {
    if (!valid) throw new IllegalStateException(why);
  }

  private DynamicMessage.Builder builder(String name) {
    return DynamicMessage.newBuilder(protocol.clientSchema().message(name));
  }

  private DynamicMessage call(String name, byte[] body) throws Exception {
    var bytes =
        Unpooled.wrappedBuffer(PacketCodec.encode(protocol.clientCommand(name), new byte[0], body));
    try {
      channel.write(bytes);
      requests++;
    } finally {
      bytes.release();
    }
    long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(25);
    while (System.nanoTime() < end) {
      if (failure.get() != null)
        throw new IllegalStateException("Connection failed", failure.get());
      var frame = inbox.poll(1, TimeUnit.SECONDS);
      if (frame == null) continue;
      if (frame.command() == protocol.clientCommand(name.replace("CsReq", "ScRsp"))) {
        var reply = protocol.clientSchema().parse(name.replace("CsReq", "ScRsp"), frame.body());
        require(reply.getUnknownFields().asMap().isEmpty(), "Unknown response fields");
        return reply;
      }
    }
    throw new TimeoutException(name);
  }

  private DynamicMessage call(String name, DynamicMessage.Builder request) throws Exception {
    return call(name, request.build().toByteArray());
  }

  private DynamicMessage call(String name) throws Exception {
    return call(name, builder(name));
  }

  private DynamicMessage scene(JsonObject control) throws Exception {
    var reply = call("GetCurSceneInfoCsReq");
    require(number(reply, "retcode") == 0, "Scene query rejected");
    var scene = (DynamicMessage) field(reply, "scene");
    require(
        number(scene, "entry_id") == control.get("entry").getAsInt()
            && number(scene, "plane_id") == control.get("plane").getAsInt()
            && number(scene, "floor_id") == control.get("floor").getAsInt(),
        "Wrong original scene identity");
    require((Long) field(scene, "interact_id") > 0, "Missing current map runtime ID");
    return scene;
  }

  private DynamicMessage prop(DynamicMessage scene, JsonObject goal) {
    DynamicMessage found = null;
    for (var raw : (List<?>) field(scene, "entity_group_list"))
      for (var item : (List<?>) field((DynamicMessage) raw, "entity_list")) {
        var entity = (DynamicMessage) item;
        if (number(entity, "group_id") != goal.get("group").getAsInt()
            || number(entity, "inst_id") != goal.get("instance").getAsInt()) continue;
        require(
            found == null && entity.hasField(entity.getDescriptorForType().findFieldByName("prop")),
            "Missing or duplicate original prop");
        require(
            number((DynamicMessage) field(entity, "prop"), "prop_id")
                == goal.get("prop").getAsInt(),
            "Source prop was replaced");
        found = entity;
      }
    require(found != null, "Original time-rewind prop was not loaded");
    return found;
  }

  private static String state(DynamicMessage entity) {
    var prop = (DynamicMessage) field(entity, "prop");
    var extra = (DynamicMessage) field(prop, "extra_info");
    if (!extra.hasField(extra.getDescriptorForType().findFieldByName("timeline_info"))) return null;
    var info = (DynamicMessage) field(extra, "timeline_info");
    require(!Boolean.TRUE.equals(field(info, "timeline_bool_value")), "Unexpected boolean route");
    return ((ByteString) field(info, "timeline_byte_value")).toString(StandardCharsets.US_ASCII);
  }

  private byte[] action(DynamicMessage scene, DynamicMessage entity, String state, long context) {
    var info = builder("PropTimelineInfo");
    set(info, "timeline_byte_value", ByteString.copyFrom(state, StandardCharsets.US_ASCII));
    var request = builder("ChangePropTimelineInfoCsReq");
    set(request, "prop_entity_id", number(entity, "entity_id"));
    set(request, "interact_id", context);
    set(request, "uuid", -1L);
    set(request, "timeline_info", info.build());
    return request.build().toByteArray();
  }

  private void goals(JsonArray goals, boolean finished) throws Exception {
    var request = builder("GetMissionStatusCsReq");
    for (var raw : goals)
      request.addRepeatedField(
          request.getDescriptorForType().findFieldByName("sub_mission_id_list"),
          raw.getAsJsonObject().get("id").getAsInt());
    var reply = call("GetMissionStatusCsReq", request);
    require(number(reply, "retcode") == 0, "Mission query rejected");
    var seen = new HashSet<Integer>();
    for (var raw : (List<?>) field(reply, "sub_mission_status_list")) {
      var row = (DynamicMessage) raw;
      int id = number(row, "id");
      require(seen.add(id), "Duplicate objective");
      require(
          number(row, "progress") == (finished ? 1 : 0)
              && number(row, "status") == (finished ? 2 : 1),
          "Incorrect original objective progress " + id);
    }
    require(seen.size() == goals.size(), "Missing original goals");
  }

  private Map<String, Object> execute(JsonObject control) throws Exception {
    var token = builder("PlayerGetTokenCsReq");
    set(token, "account_uid", control.get("accountUid").getAsString());
    set(token, "token", control.get("comboToken").getAsString());
    set(token, "platform", 1);
    var reply = call("PlayerGetTokenCsReq", token);
    require(number(reply, "retcode") == 0, "Token rejected");
    int uid = number(reply, "uid");
    require(
        number(call("PlayerLoginCsReq"), "retcode") == 0
            && number(call("PlayerLoginFinishCsReq"), "retcode") == 0,
        "Login rejected");
    if (control.get("seed").getAsBoolean()) {
      System.out.println("READY_UID=" + uid);
      System.out.flush();
      boolean owned = false;
      for (int i = 0; i < 120 && !owned; i++) {
        for (var row : (List<?>) field(call("GetAvatarDataCsReq"), "avatar_list"))
          if (number((DynamicMessage) row, "base_avatar_id") == 1001) owned = true;
        if (!owned) Thread.sleep(200);
      }
      require(owned, "Explicit console roster fixture was not granted");
      return Map.of(
          "passed",
          true,
          "seed",
          true,
          "uid",
          uid,
          "nativeRequests",
          requests,
          "clientGameplayVerified",
          false);
    }
    var targets = control.getAsJsonArray("goals");
    boolean resume = control.get("resume").getAsBoolean();
    var current = scene(control);
    long context = (Long) field(current, "interact_id");
    goals(targets, resume);
    for (var raw : targets) {
      var goal = raw.getAsJsonObject();
      var entity = prop(current, goal);
      String expected = goal.get("state").getAsString();
      if (resume) require(expected.equals(state(entity)), "Saved native timeline not restored");
      else {
        require(
            state(entity) == null, "Initial animation was incorrectly published as observed proof");
        var rejectedReply =
            call("ChangePropTimelineInfoCsReq", action(current, entity, expected, context + 1));
        require(number(rejectedReply, "retcode") != 0, "Old context accepted");
        rejected++;
        var accepted =
            call("ChangePropTimelineInfoCsReq", action(current, entity, expected, context));
        require(
            number(accepted, "retcode") == 0
                && number(accepted, "prop_entity_id") == number(entity, "entity_id"),
            "Actual state report rejected");
        reports++;
        require(
            number(
                    call("ChangePropTimelineInfoCsReq", action(current, entity, expected, context)),
                    "retcode")
                == 0,
            "Duplicate snapshot rejected");
      }
    }
    goals(targets, true);
    current = scene(control);
    for (var raw : targets) {
      var goal = raw.getAsJsonObject();
      require(
          goal.get("state").getAsString().equals(state(prop(current, goal))),
          "Native saved timeline echo differs");
    }
    return Map.of(
        "passed",
        true,
        "uid",
        uid,
        "seed",
        false,
        "resume",
        resume,
        "nativeRequests",
        requests,
        "actualStateReports",
        reports,
        "oldContextRejections",
        rejected,
        "goals",
        targets.size(),
        "clientGameplayVerified",
        false);
  }

  public static void main(String[] args) {
    var probe = new NativePropTimelineProbe();
    var client = new KcpClient();
    Ukcp connection = null;
    int exit = 1;
    try {
      var control =
          JsonParser.parseReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))
              .getAsJsonObject();
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
                  require(frame != null, "Truncated frame");
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
              probe.failure.compareAndSet(null, new IllegalStateException("Disconnected"));
            }
          });
      connection =
          client.connect(new InetSocketAddress("127.0.0.1", Integer.parseInt(args[0])), config);
      probe.channel = probe.connected.get(10, TimeUnit.SECONDS);
      System.out.write(
          (new Gson().toJson(probe.execute(control)) + "\n").getBytes(StandardCharsets.UTF_8));
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
