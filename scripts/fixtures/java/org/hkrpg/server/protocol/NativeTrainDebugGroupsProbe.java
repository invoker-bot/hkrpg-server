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

/** Authenticated native train entry/reentry; restricted to the runner's isolated account and ports. */
public final class NativeTrainDebugGroupsProbe {
  private record Frame(int command, byte[] body) {}
  private final ProtocolBridge protocol = ProtocolBridge.forVersion("4.6.0");
  private final BlockingQueue<Frame> inbox = new LinkedBlockingQueue<>();
  private final AtomicReference<Throwable> failure = new AtomicReference<>();
  private final CompletableFuture<Ukcp> connected = new CompletableFuture<>();
  private final List<Integer> checkedGroups = new ArrayList<>();
  private Ukcp channel;
  private volatile boolean closing;
  private boolean loggedIn;
  private int requests, checkedScenes;

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
      require(number(result, "retcode") == 0, "Rejected native request: " + name);
      return result;
    }
    throw new IllegalStateException("Timed out waiting for " + response);
  }
  private DynamicMessage call(String name) throws Exception { return call(name, builder(name)); }

  private void checkScene(DynamicMessage scene) {
    require(number(scene, "floor_id") == 10000003 && number(scene, "plane_id") == 10000,
        "Wrong train scene");
    require(number(scene, "leader_entity_id") != 0, "Missing actual party leader");
    var ordinary = new TreeSet<Integer>();
    var debug = Set.of(2, 25, 28, 44, 79);
    for (var raw : (List<?>) field(scene, "entity_group_list")) {
      var group = (DynamicMessage) raw;
      int id = number(group, "group_id");
      require(!debug.contains(id), "Debug group leaked into native train scene: " + id);
      ordinary.add(id);
      if (id == 26) require(((List<?>) field(group, "entity_list")).size() == 3,
          "Ordinary train teleports changed");
    }
    require(ordinary.containsAll(Set.of(26, 121)), "Ordinary train groups disappeared");
    boolean debugFlag = false;
    for (var raw : (List<?>) field(scene, "floor_saved_data")) {
      var entry = (DynamicMessage) raw;
      if (field(entry, "key").equals("Utility_IsDebug")) {
        require(number(entry, "value") == 0, "Debug flag unexpectedly enabled");
        debugFlag = true;
      }
    }
    require(debugFlag, "Missing authored debug default");
    checkedGroups.clear(); checkedGroups.addAll(ordinary);
    checkedScenes++;
  }

  private Map<String, Object> execute(JsonObject control) throws Exception {
    var token = builder("PlayerGetTokenCsReq");
    set(token, "account_uid", control.get("accountUid").getAsString());
    set(token, "token", control.get("comboToken").getAsString()); set(token, "platform", 1);
    int uid = number(call("PlayerGetTokenCsReq", token), "uid");
    call("PlayerLoginCsReq"); loggedIn = true;
    call("PlayerLoginFinishCsReq");
    var profile = child(child(call("TrainPartyGetDataCsReq"), "train_party_data"), "train_party_info");
    var rooms = (List<?>) field(profile, "area_list");
    require(rooms.size() == 1, "Missing original initial room reference");
    var room = (DynamicMessage) rooms.getFirst();
    require(room.getAllFields().size() == 1 && number(room, "area_id") == 12,
        "Initial room must contain only its original area ID at zero progress");
    if (!control.get("mode").getAsString().equals("exercise"))
      checkScene(child(call("GetCurSceneInfoCsReq"), "scene"));
    for (int entry : new int[] {100000301, 100000302}) {
      var request = builder("EnterSceneCsReq"); set(request, "entry_id", entry);
      call("EnterSceneCsReq", request);
      var scene = child(call("GetCurSceneInfoCsReq"), "scene");
      require(number(scene, "entry_id") == entry, "Train entry did not change");
      checkScene(scene);
    }
    var result = new LinkedHashMap<String, Object>();
    result.put("passed", true); result.put("playerUid", uid);
    result.put("checkedScenes", checkedScenes); result.put("ordinaryGroups", checkedGroups);
    result.put("debugGroupsAbsent", List.of(2, 25, 28, 44, 79));
    result.put("entry", 100000302); result.put("floor", 10000003);
    result.put("initialRoomId", 12); result.put("constructionProgress", 0);
    result.put("clientGameplayVerified", false);
    return result;
  }
  private void logout() throws InterruptedException {
    if (!loggedIn || closing) return;
    closing = true;
    send("PlayerLogoutCsReq", builder("PlayerLogoutCsReq"));
    Thread.sleep(250);
  }
  public static void main(String[] args) {
    var probe = new NativeTrainDebugGroupsProbe();
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
              probe.inbox.add(new Frame(frame.cmdId(), frame.body()));
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
      System.err.println("Train scene probe failed: " + error.getClass().getSimpleName());
      System.err.println("Contract: " + error.getMessage());
    } finally {
      try { probe.logout(); } catch (Exception ignored) { /* Runner verifies cleanup. */ }
      if (connection != null) connection.close();
      client.stop();
    }
    System.exit(exit);
  }
}
