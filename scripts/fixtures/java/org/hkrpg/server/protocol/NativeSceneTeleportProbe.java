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

/** Isolated authenticated 4.6 teleport contract; never operates a game client. */
public final class NativeSceneTeleportProbe {
  private record Frame(int command, byte[] body) {}
  private record Scene(long runtime, int entry, int floor, int leader, long version, int layer,
                       List<Integer> position, List<Integer> rotation) {}
  private static final class ContractViolation extends IllegalStateException {
    ContractViolation(String message) { super(message); }
  }
  private final ProtocolBridge protocol = ProtocolBridge.forVersion("4.6.0");
  private final BlockingQueue<Frame> inbox = new LinkedBlockingQueue<>();
  private final AtomicReference<Throwable> failure = new AtomicReference<>();
  private final AtomicInteger corrections = new AtomicInteger();
  private final CompletableFuture<Ukcp> connected = new CompletableFuture<>();
  private final List<String> checks = new ArrayList<>();
  private Ukcp channel;
  private volatile boolean closing;
  private boolean loggedIn;
  private int requests;

  private static void require(boolean value, String message) {
    if (!value) throw new ContractViolation(message);
  }
  private static FieldDescriptor descriptor(DynamicMessage message, String name) {
    var field = message.getDescriptorForType().findFieldByName(name);
    require(field != null, "Missing frozen schema field: " + name);
    return field;
  }
  private static Object field(DynamicMessage message, String name) {
    return message.getField(descriptor(message, name));
  }
  private static int number(DynamicMessage message, String name) {
    return (Integer) field(message, name);
  }
  private static long unsigned(DynamicMessage message, String name) {
    return Integer.toUnsignedLong(number(message, name));
  }
  private static DynamicMessage child(DynamicMessage message, String name) {
    require(message.hasField(descriptor(message, name)), "Missing container: " + name);
    return (DynamicMessage) field(message, name);
  }
  private static void set(DynamicMessage.Builder message, String name, Object value) {
    var descriptor = message.getDescriptorForType().findFieldByName(name);
    require(descriptor != null, "Missing frozen builder field: " + name);
    message.setField(descriptor, value);
  }
  private DynamicMessage.Builder builder(String name) {
    return DynamicMessage.newBuilder(protocol.clientSchema().message(name));
  }
  private void send(String name, DynamicMessage.Builder message) {
    int command = protocol.clientCommand(name);
    var bytes = Unpooled.wrappedBuffer(PacketCodec.encode(command, new byte[] {8, 1},
        message.build().toByteArray()));
    try { channel.write(bytes); requests++; } finally { bytes.release(); }
  }
  private DynamicMessage call(String name, DynamicMessage.Builder message) throws Exception {
    String response = name.replace("CsReq", "ScRsp");
    send(name, message);
    int command = protocol.clientCommand(response);
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
    while (System.nanoTime() < deadline) {
      require(failure.get() == null, "KCP connection failed");
      var frame = inbox.poll(250, TimeUnit.MILLISECONDS);
      if (frame == null || frame.command != command) continue;
      var value = protocol.clientSchema().parse(response, frame.body);
      require(value.getUnknownFields().asMap().isEmpty(), "Unknown response fields: " + response);
      return value;
    }
    throw new ContractViolation("Timed out waiting for " + response);
  }
  private DynamicMessage call(String name) throws Exception { return call(name, builder(name)); }
  private static List<Integer> vector(DynamicMessage message) {
    return List.of(number(message, "x"), number(message, "y"), number(message, "z"));
  }
  private DynamicMessage vector(List<Integer> value) {
    var result = builder("Vector");
    set(result, "x", value.get(0)); set(result, "y", value.get(1)); set(result, "z", value.get(2));
    return result.build();
  }
  private static void findLeader(DynamicMessage value, int leader, List<DynamicMessage> found) {
    if (value.getDescriptorForType().getName().equals("SceneEntityInfo")
        && number(value, "entity_id") == leader) found.add(value);
    for (var item : value.getAllFields().entrySet()) {
      if (item.getKey().getJavaType() != FieldDescriptor.JavaType.MESSAGE) continue;
      if (item.getKey().isRepeated()) {
        for (Object nested : (List<?>) item.getValue()) findLeader((DynamicMessage) nested, leader, found);
      } else findLeader((DynamicMessage) item.getValue(), leader, found);
    }
  }
  private Scene scene() throws Exception {
    var reply = call("GetCurSceneInfoCsReq");
    require(number(reply, "retcode") == 0, "Scene query rejected");
    var scene = child(reply, "scene");
    int leader = number(scene, "leader_entity_id");
    long runtime = (Long) field(scene, "interact_id");
    require(leader != 0 && runtime != 0 && number(scene, "entry_id") > 0,
        "Scene must publish actual runtime, entry and leader identity");
    var found = new ArrayList<DynamicMessage>();
    findLeader(scene, leader, found);
    require(!found.isEmpty(), "Leader entity missing from actual scene snapshot");
    var entity = found.getFirst();
    for (var duplicate : found) require(entity.equals(duplicate), "Conflicting leader snapshots");
    var motion = child(entity, "motion");
    return new Scene(runtime, number(scene, "entry_id"), number(scene, "floor_id"), leader,
        unsigned(scene, "client_pos_version"),
        number(child(entity, "actor"), "map_layer"), vector(child(motion, "pos")),
        vector(child(motion, "rot")));
  }
  private DynamicMessage.Builder teleport(long runtime, int entry, int entity,
      List<Integer> position, List<Integer> rotation, int layer, boolean unknownFlag) {
    var motion = builder("MotionInfo");
    set(motion, "pos", vector(position)); set(motion, "rot", vector(rotation));
    var entityMotion = builder("EntityMotion");
    set(entityMotion, "entity_id", entity); set(entityMotion, "motion", motion.build());
    set(entityMotion, "map_layer", layer);
    entityMotion.setField(entityMotion.getDescriptorForType().findFieldByNumber(7), unknownFlag);
    var request = builder("SceneEntityTeleportCsReq");
    set(request, "entity_motion", entityMotion.build()); set(request, "entry_id", entry);
    set(request, "interact_id", runtime);
    return request;
  }
  private Scene accepted(Scene before, List<Integer> position, List<Integer> rotation, int layer)
      throws Exception {
    var reply = call("SceneEntityTeleportCsReq", teleport(before.runtime, before.entry,
        before.leader, position, rotation, layer, false));
    require(number(reply, "retcode") == 0, "Valid current-scene teleport rejected");
    require(unsigned(reply, "client_pos_version") == before.version + 1,
        "Accepted teleport did not publish the next authoritative revision");
    var motion = child(child(reply, "entity_motion"), "motion");
    require(vector(child(motion, "pos")).equals(position)
        && vector(child(motion, "rot")).equals(rotation), "Teleport reply changed full-axis motion");
    var after = scene();
    require(after.runtime == before.runtime && after.entry == before.entry && after.floor == before.floor
        && after.leader == before.leader,
        "Current-scene teleport changed scene identity");
    require(after.position.equals(position) && after.rotation.equals(rotation),
        "Scene did not commit all position and rotation axes");
    require(after.version == before.version + 1 && after.layer == (layer == 0 ? before.layer : layer),
        "Scene revision or map-layer differs from accepted teleport");
    require(corrections.get() == 0, "Teleport invented a 1442 forced movement notification");
    return after;
  }
  private void rejected(Scene before, String label, long runtime, int entry, int entity,
      int layer, boolean unknownFlag) throws Exception {
    var reply = call("SceneEntityTeleportCsReq", teleport(runtime, entry, entity,
        List.of(-111, -222, -333), List.of(-444, -555, -666), layer, unknownFlag));
    require(number(reply, "retcode") != 0, "Invalid teleport accepted: " + label);
    require(before.equals(scene()), "Rejected teleport mutated scene state: " + label);
    require(corrections.get() == 0, "Rejected teleport emitted 1442: " + label);
    checks.add(label);
  }
  private void acknowledge(Scene expected, long version, String label) throws Exception {
    var notification = builder("SceneUpdatePositionVersionNotify");
    notification.setField(notification.getDescriptorForType().findFieldByNumber(5), (int) version);
    send("SceneUpdatePositionVersionNotify", notification);
    require(expected.equals(scene()), "Position acknowledgement changed authority: " + label);
    checks.add(label);
  }
  private static List<Integer> list(JsonArray array) {
    var result = new ArrayList<Integer>();
    for (var value : array) result.add(value.getAsInt());
    require(result.size() == 3, "Expected synthetic vector must have three axes");
    return List.copyOf(result);
  }
  private Map<String, Object> execute(JsonObject control) throws Exception {
    var token = builder("PlayerGetTokenCsReq");
    set(token, "account_uid", control.get("accountUid").getAsString());
    set(token, "token", control.get("comboToken").getAsString()); set(token, "platform", 1);
    var auth = call("PlayerGetTokenCsReq", token);
    require(number(auth, "retcode") == 0, "Synthetic token rejected");
    int uid = number(auth, "uid");
    require(uid > 0 && number(call("PlayerLoginCsReq"), "retcode") == 0, "Synthetic login rejected");
    loggedIn = true;
    require(number(call("PlayerLoginFinishCsReq"), "retcode") == 0, "Login finish rejected");
    Scene current = scene();
    require(current.version == 0, "New scene instance must initialize local revision to zero");
    var floorResource = control.getAsJsonObject("floorResource");
    require(current.floor == floorResource.get("floorId").getAsInt(),
        "Actual snapshot floor differs from the frozen runtime-floor resource");
    boolean authoredLayer = false;
    for (var layer : floorResource.getAsJsonArray("validMapLayers"))
      authoredLayer |= current.layer == layer.getAsInt();
    require(authoredLayer, "Actual actor layer is not authored by LayerToAreaMask");
    checks.add("actual-floor-and-layer-match-frozen-runtime-resource");
    if (control.has("expected")) {
      var expected = control.getAsJsonObject("expected");
      require(uid == expected.get("playerUid").getAsInt(), "Synthetic player changed after reconnect");
      require(current.entry == expected.get("entry").getAsInt()
          && current.floor == expected.get("floor").getAsInt()
          && current.position.equals(list(expected.getAsJsonArray("position")))
          && current.rotation.equals(list(expected.getAsJsonArray("rotation")))
          && current.layer == expected.get("mapLayer").getAsInt(),
          "Persisted full-axis position, rotation, entry or layer changed after reconnect/restart");
      checks.add("saved-position-restored-with-new-scene-revision");
    }
    if (control.get("mode").getAsString().equals("exercise")) {
      current = accepted(current, List.of(-123, 456, -789), List.of(-234, 345, -456), 0);
      checks.add("full-axis-signed-motion-and-zero-layer-preservation");
      acknowledge(current, current.version, "matching-version-ack");
      acknowledge(current, current.version - 1, "stale-version-ack");
      acknowledge(current, current.version + 1, "future-version-ack");
      rejected(current, "wrong-runtime-rejected", current.runtime ^ 1L, current.entry,
          current.leader, 0, false);
      rejected(current, "wrong-entry-rejected", current.runtime, current.entry + 1,
          current.leader, 0, false);
      rejected(current, "foreign-entity-rejected", current.runtime, current.entry,
          current.leader == -1 ? -2 : -1, 0, false);
      rejected(current, "unknown-boolean-rejected", current.runtime, current.entry,
          current.leader, 0, true);
      rejected(current, "invalid-map-layer-rejected", current.runtime, current.entry,
          current.leader, -1, false);
      if (current.layer > 0) {
        current = accepted(current, List.of(-321, 654, -987), List.of(123, -456, 789), current.layer);
        checks.add("actual-nonzero-map-layer-accepted");
        acknowledge(current, current.version, "second-accepted-revision-ack");
      }
    } else require(control.get("mode").getAsString().equals("verify"), "Unknown probe mode");
    var heartbeat = builder("PlayerHeartBeatCsReq");
    set(heartbeat, "client_time_ms", 1234567890123L);
    require((Long) field(call("PlayerHeartBeatCsReq", heartbeat), "client_time_ms") == 1234567890123L,
        "Heartbeat did not survive teleport/ack/rejections");
    Thread.sleep(200);
    require(failure.get() == null && corrections.get() == 0, "Unexpected disconnect or 1442 notification");
    checks.add("heartbeat-and-no-forced-movement");
    var result = new LinkedHashMap<String, Object>();
    result.put("passed", true); result.put("playerUid", uid); result.put("entry", current.entry);
    result.put("floor", current.floor);
    result.put("position", current.position); result.put("rotation", current.rotation);
    result.put("mapLayer", current.layer); result.put("version", current.version);
    result.put("checks", List.copyOf(checks)); result.put("nativeRequests", requests);
    result.put("forcedMovementNotifications", corrections.get());
    result.put("clientGameplayVerified", false);
    return result;
  }
  private void logout() throws InterruptedException {
    if (!loggedIn || closing) return;
    closing = true;
    send("PlayerLogoutCsReq", builder("PlayerLogoutCsReq"));
    Thread.sleep(250); // Let the queued KCP send reach the server before disposing our transport.
  }
  public static void main(String[] args) {
    var probe = new NativeSceneTeleportProbe();
    var client = new KcpClient();
    Ukcp connection = null;
    int exit = 1;
    try {
      var control = JsonParser.parseReader(new InputStreamReader(System.in, StandardCharsets.UTF_8)).getAsJsonObject();
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
              require(frame != null, "Truncated response frame");
              if (frame.cmdId() == 1442) probe.corrections.incrementAndGet();
              probe.inbox.add(new Frame(frame.cmdId(), frame.body()));
            }
          } catch (Throwable error) { probe.failure.compareAndSet(null, error); }
        }
        public void handleException(Throwable error, Ukcp value) {
          if (!probe.closing) probe.failure.compareAndSet(null, error);
          probe.connected.completeExceptionally(error);
        }
        public void handleClose(Ukcp value) {
          if (!probe.closing) probe.failure.compareAndSet(null, new ContractViolation("Unexpected disconnect"));
        }
      });
      connection = client.connect(new InetSocketAddress("127.0.0.1", Integer.parseInt(args[0])), config);
      probe.channel = probe.connected.get(10, TimeUnit.SECONDS);
      var result = probe.execute(control);
      probe.logout();
      result.put("normalLogoutRequested", true);
      result.put("nativeRequests", probe.requests);
      System.out.println(new Gson().toJson(result));
      exit = 0;
    } catch (Exception error) {
      Throwable detail = error;
      while (detail.getCause() != null) detail = detail.getCause();
      System.err.println("Scene teleport probe failed: " + detail.getClass().getSimpleName());
      if (detail instanceof ContractViolation) System.err.println("Contract: " + detail.getMessage());
    } finally {
      try { probe.logout(); } catch (Exception ignored) { /* The runner retains server cleanup evidence. */ }
      if (connection != null) connection.close();
      client.stop();
    }
    System.exit(exit);
  }
}
