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
 * Original world presentation reports, one activity reward and saved history; client rendering is
 * not replayed.
 */
public final class NativeActivityFirstTalkProbe {
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

  private void quest(int id, int progress, int status) throws Exception {
    var reply = call("GetQuestDataCsReq");
    require(number(reply, "retcode") == 0, "Quest query rejected");
    var rows =
        ((List<?>) field(reply, "quest_list"))
            .stream().map(x -> (DynamicMessage) x).filter(q -> number(q, "id") == id).toList();
    require(rows.size() == 1, "Missing or duplicate original activity quest " + id);
    require(
        number(rows.getFirst(), "progress") == progress
            && number(rows.getFirst(), "status") == status,
        "Incorrect activity progress " + id + ": " + rows.getFirst());
  }

  private DynamicMessage claim(int id) throws Exception {
    var request = builder("TakeQuestRewardCsReq");
    request.addRepeatedField(request.getDescriptorForType().findFieldByName("ODJIJFJCHHB"), id);
    return call("TakeQuestRewardCsReq", request);
  }

  private void reward(DynamicMessage reply, JsonObject goal) {
    require(number(reply, "retcode") == 0, "Reward claim rejected");
    var actual = new TreeMap<Integer, Integer>();
    var reward = (DynamicMessage) field(reply, "reward");
    for (var raw : (List<?>) field(reward, "item_list")) {
      var item = (DynamicMessage) raw;
      require(
          actual.put(number(item, "item_id"), number(item, "num")) == null,
          "Duplicate reward item");
    }
    var expected = new TreeMap<Integer, Integer>();
    goal.getAsJsonObject("rewards")
        .entrySet()
        .forEach(e -> expected.put(Integer.parseInt(e.getKey()), e.getValue().getAsInt()));
    require(actual.equals(expected), "Original reward recipe differs: " + actual);
    require(
        ((List<?>) field(reply, "succ_quest_id_list")).equals(List.of(goal.get("id").getAsInt())),
        "Claimed a different quest");
  }

  private void query(Set<Integer> expected) throws Exception {
    var request = builder("GetFirstTalkByPerformanceNpcCsReq");
    for (int id = 304002001; id <= 304002026; id++)
      request.addRepeatedField(
          request.getDescriptorForType().findFieldByName("performance_id_list"), id);
    var reply = call("GetFirstTalkByPerformanceNpcCsReq", request);
    require(number(reply, "retcode") == 0, "Presentation query rejected");
    var seen = new HashSet<Integer>();
    var unique = new HashSet<Integer>();
    for (var raw : (List<?>) field(reply, "npc_meet_status_list")) {
      var row = (DynamicMessage) raw;
      int id = number(row, "performance_id");
      require(
          id >= 304002001 && id <= 304002026 && unique.add(id),
          "Unexpected presentation query row");
      if ((Boolean) field(row, "is_meet")) seen.add(id);
    }
    require(
        unique.size() == 26 && seen.equals(expected),
        "World seen history differs: " + seen + " expected " + expected);
  }

  private DynamicMessage finish(int id) throws Exception {
    var request = builder("FinishFirstTalkByPerformanceNpcCsReq");
    set(request, "performance_id", id);
    var reply = call("FinishFirstTalkByPerformanceNpcCsReq", request);
    require(number(reply, "performance_id") == id, "Wrong presentation response identity");
    return reply;
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
    var current = scene(control);
    var known = new TreeSet<Integer>();
    for (var id : control.getAsJsonArray("known")) known.add(id.getAsInt());
    query(known);
    quest(6029859, known.isEmpty() ? 0 : 1, known.isEmpty() ? 1 : 3);
    require(number(claim(6029859), "retcode") != 0, "Premature or repeated reward paid");
    require(number(finish(304002026), "retcode") != 0, "Camera-only entry accepted");
    rejected++;
    if (control.has("wrongMap")) {
      require(
          number(finish(control.get("wrongMap").getAsInt()), "retcode") != 0,
          "Unloaded world actor accepted");
      rejected++;
    }
    var reports = new ArrayList<Integer>();
    for (var raw : control.getAsJsonArray("performances")) {
      var location = raw.getAsJsonObject();
      int id = location.get("id").getAsInt();
      DynamicMessage found = null;
      for (var group : (List<?>) field(current, "entity_group_list"))
        for (var item : (List<?>) field((DynamicMessage) group, "entity_list")) {
          var entity = (DynamicMessage) item;
          if (number(entity, "group_id") == location.get("group").getAsInt()
              && number(entity, "inst_id") == location.get("instance").getAsInt()) {
            require(
                found == null
                    && entity.hasField(entity.getDescriptorForType().findFieldByName("prop")),
                "Missing or duplicate original prop");
            require(
                number((DynamicMessage) field(entity, "prop"), "prop_id")
                    == location.get("prop").getAsInt(),
                "Different original prop");
            found = entity;
          }
        }
      require(
          found != null && number(found, "entity_id") > 0,
          "Original source actor not loaded " + id);
      require(number(finish(id), "retcode") == 0, "Original world report rejected " + id);
      require(number(finish(id), "retcode") == 0, "Repeated report rejected " + id);
      require(known.add(id), "Observation was already prewritten");
      reports.add(id);
    }
    query(known);
    quest(6029859, 1, control.get("claim").getAsBoolean() ? 2 : 3);
    if (control.get("claim").getAsBoolean()) {
      reward(claim(6029859), control.getAsJsonObject("goal"));
      quest(6029859, 1, 3);
      require(number(claim(6029859), "retcode") != 0, "Repeated original reward paid");
    }
    var result = new LinkedHashMap<String, Object>();
    result.put("passed", true);
    result.put("uid", uid);
    result.put("nativeRequests", requests);
    result.put("originalWorldReports", reports);
    result.put("duplicateReportChecks", reports.size());
    result.put("rejectedReports", rejected);
    result.put("claimedOriginalGoal", control.get("claim").getAsBoolean());
    result.put("seen", known);
    result.put("clientGameplayVerified", false);
    return result;
  }

  public static void main(String[] args) {
    var probe = new NativeActivityFirstTalkProbe();
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
