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

/** Isolated 4.6 KCP dialogue, task and inventory verification. */
public final class MissionTalkCustomNativeProbe {
  private record Frame(int command, byte[] body) {}
  private final ProtocolBridge protocol = ProtocolBridge.forVersion("4.6.0");
  private final BlockingQueue<Frame> inbox = new LinkedBlockingQueue<>();
  private final AtomicReference<Throwable> failure = new AtomicReference<>();
  private final CompletableFuture<Ukcp> connected = new CompletableFuture<>();
  private final List<String> checks = new ArrayList<>();
  private final List<DynamicMessage> missionUpdates = new ArrayList<>();
  private Ukcp channel;

  private static Object field(DynamicMessage m, String name) {
    return m.getField(m.getDescriptorForType().findFieldByName(name));
  }
  private static int number(DynamicMessage m, String name) { return (Integer) field(m, name); }
  private static void require(boolean ok, String message) {
    if (!ok) throw new IllegalStateException(message);
  }
  private void check(boolean ok, String message) { require(ok, message); checks.add(message); }
  private DynamicMessage.Builder request(String name) {
    return DynamicMessage.newBuilder(protocol.clientSchema().message(name));
  }
  private DynamicMessage call(String name, Map<String, Object> fields, int expected) throws Exception {
    var builder = request(name);
    fields.forEach((key, value) -> builder.setField(builder.getDescriptorForType().findFieldByName(key), value));
    return rawCall(name, builder.build().toByteArray(), expected);
  }
  private DynamicMessage call(String name) throws Exception { return call(name, Map.of(), 0); }
  private DynamicMessage rawCall(String name, byte[] body, int expected) throws Exception {
    var packet = Unpooled.wrappedBuffer(PacketCodec.encode(protocol.clientCommand(name), new byte[0], body));
    try { channel.write(packet); } finally { packet.release(); }
    String response = name.replace("CsReq", "ScRsp");
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
    while (System.nanoTime() < deadline) {
      if (failure.get() != null) throw new IllegalStateException("KCP failed", failure.get());
      var frame = inbox.poll(1, TimeUnit.SECONDS);
      if (frame == null) continue;
      if (frame.command() == protocol.clientCommand("PlayerSyncScNotify")) {
        var sync = protocol.clientSchema().parse("PlayerSyncScNotify", frame.body());
        var missionField = sync.getDescriptorForType().findFieldByName("mission_sync");
        if (sync.hasField(missionField)) missionUpdates.add((DynamicMessage) sync.getField(missionField));
      }
      if (frame.command() != protocol.clientCommand(response)) continue;
      var message = protocol.clientSchema().parse(response, frame.body());
      require(message.getUnknownFields().asMap().isEmpty(), "Unexpected response fields");
      require(number(message, "retcode") == expected, response + " retcode=" + number(message, "retcode"));
      return message;
    }
    throw new TimeoutException(response);
  }
  private void talk(int choice, boolean include, int expected) throws Exception {
    var b = request("FinishTalkMissionCsReq");
    b.setField(b.getDescriptorForType().findFieldByName("sub_mission_id"), 802711102);
    b.setField(b.getDescriptorForType().findFieldByName("talk_str"), "Mission_802711102");
    if (include) {
      var v = request("MissionCustomValue");
      v.setField(v.getDescriptorForType().findFieldByName("custom_value"), choice);
      b.addRepeatedField(b.getDescriptorForType().findFieldByName("custom_value_list"), v.build());
    }
    var reply = rawCall("FinishTalkMissionCsReq", b.build().toByteArray(), expected);
    if (expected == 0) {
      var values = (List<?>) field(reply, "custom_value_list");
      check(values.size() == 1 && number((DynamicMessage) values.getFirst(), "custom_value") == choice,
          "original-choice-returned");
    }
    check(true, "talk-retcode-" + expected);
  }
  private void progress(boolean complete) throws Exception {
    var all = call("GetMissionDataCsReq");
    var rows = new HashMap<Integer, DynamicMessage>();
    for (var item : (List<?>) field(all, "mission_list")) {
      var row = (DynamicMessage) item;
      int id = number(row, "id");
      if (id == 802711102 || id == 802711103) require(rows.put(id, row) == null, "Duplicate objective");
    }
    var first = rows.get(802711102);
    var finishedSubs = (List<?>) field(all, "OLKALABEDOA");
    check(complete ? first == null && finishedSubs.contains(802711102)
        : first != null && number(first, "progress") == 0 && !finishedSubs.contains(802711102),
        "current-objective-progress-" + complete);
    var next = rows.get(802711103);
    check(complete ? next != null && number(next, "progress") == 0 : next == null,
        "next-objective-available-" + complete);
    check(!((List<?>) field(all, "finished_main_mission_id_list")).contains(8027111), "parent-still-doing");
  }
  private void bag(boolean earned) throws Exception {
    var stock = new HashMap<Integer, Integer>();
    for (var item : (List<?>) field(call("GetBagCsReq"), "material_list")) {
      var row = (DynamicMessage) item;
      int id = number(row, "tid");
      if (id == 408636 || id == 408637) stock.put(id, number(row, "num"));
    }
    check(stock.getOrDefault(408636, 0) == (earned ? 1 : 0)
        && stock.getOrDefault(408637, 0) == 0, "selected-reward-once-" + earned);
  }
  private void choiceSnapshot() throws Exception {
    var request = request("GetMainMissionCustomValueCsReq");
    request.addRepeatedField(request.getDescriptorForType().findFieldByName("main_mission_id_list"), 8027111);
    var reply = rawCall("GetMainMissionCustomValueCsReq", request.build().toByteArray(), 0);
    var mains = (List<?>) field(reply, "main_mission_list");
    check(mains.size() == 1 && number((DynamicMessage) mains.getFirst(), "id") == 8027111,
        "choice-query-main");
    var values = (List<?>) field((DynamicMessage) mains.getFirst(), "custom_value_list");
    check(values.size() == 1 && number((DynamicMessage) values.getFirst(), "index") == 0
        && number((DynamicMessage) values.getFirst(), "custom_value") == 1, "choice-query-value");
  }
  private void completionNotification() {
    var found = new HashMap<Integer, DynamicMessage>();
    for (var update : missionUpdates)
      for (var raw : (List<?>) field(update, "mission_list")) {
        var row = (DynamicMessage) raw;
        int id = number(row, "id");
        if (id == 802711102 || id == 802711103) found.put(id, row);
      }
    var first = found.get(802711102);
    var next = found.get(802711103);
    check(first != null && number(first, "progress") == 1
        && ((com.google.protobuf.Descriptors.EnumValueDescriptor) field(first, "status"))
            .getName().equals("MissionStatus_MissionFinish"), "completed-objective-pushed");
    check(next != null && number(next, "progress") == 0
        && ((com.google.protobuf.Descriptors.EnumValueDescriptor) field(next, "status"))
            .getName().equals("MissionStatus_MissionDoing"), "next-objective-pushed");
  }
  private Map<String, Object> execute(JsonObject input) throws Exception {
    int uid = number(call("PlayerGetTokenCsReq", Map.of("account_uid", input.get("accountUid").getAsString(),
        "token", input.get("comboToken").getAsString(), "platform", 1), 0), "uid");
    call("PlayerLoginCsReq");
    call("PlayerLoginFinishCsReq");
    String mode = input.get("mode").getAsString();
    if (mode.equals("bootstrap")) call("EnterSceneCsReq", Map.of("entry_id", 100000302), 0);
    var scene = (DynamicMessage) field(call("GetCurSceneInfoCsReq"), "scene");
    check(number(scene, "plane_id") == 10000 && number(scene, "floor_id") == 10000003,
        "original-party-carriage-floor");
    if (mode.equals("complete")) {
      progress(false); bag(false);
      talk(0, false, Retcode.MISSION_CUSTOM_VALUE_NOT_VALID.getVal());
      talk(3, true, Retcode.MISSION_CUSTOM_VALUE_NOT_VALID.getVal());
      progress(false); bag(false);
      missionUpdates.clear();
      talk(1, true, 0);
      completionNotification(); choiceSnapshot();
      progress(true); bag(true);
      talk(1, true, Retcode.MISSION_NOT_DOING.getVal());
      bag(true);
    } else if (mode.equals("resume")) {
      progress(true); bag(true); choiceSnapshot();
      talk(1, true, Retcode.MISSION_NOT_DOING.getVal());
      progress(true); bag(true);
    } else require(mode.equals("bootstrap"), "Unknown fixture mode");
    return Map.of("passed", true, "uid", uid, "mode", mode, "checks", checks,
        "clientGameplayVerified", false);
  }
  public static void main(String[] args) {
    var probe = new MissionTalkCustomNativeProbe();
    var client = new KcpClient();
    Ukcp connection = null;
    int exit = 1;
    try {
      var input = new java.io.BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
      var credentials = JsonParser.parseString(input.readLine()).getAsJsonObject();
      var config = new ChannelConfig();
      config.nodelay(true, 40, 2, true); config.setMtu(1400); config.setSndwnd(256); config.setRcvwnd(256);
      config.setUseConvChannel(true); config.setConv(ThreadLocalRandom.current().nextInt(1, Integer.MAX_VALUE));
      config.setTimeoutMillis(25000); config.setAckNoDelay(true);
      client.init(config, new KcpListener() {
        public void onConnected(Ukcp c) { probe.connected.complete(c); }
        public void handleReceive(ByteBuf bytes, Ukcp c) {
          try {
            while (bytes.isReadable()) {
              var frame = PacketCodec.decodeNext(bytes);
              require(frame != null, "Truncated KCP frame");
              probe.inbox.add(new Frame(frame.cmdId(), frame.body()));
            }
          } catch (Throwable e) { probe.failure.set(e); }
        }
        public void handleException(Throwable e, Ukcp c) { probe.failure.set(e); probe.connected.completeExceptionally(e); }
        public void handleClose(Ukcp c) { probe.failure.compareAndSet(null, new IllegalStateException("KCP closed")); }
      });
      connection = client.connect(new InetSocketAddress("127.0.0.1", Integer.parseInt(args[0])), config);
      probe.channel = probe.connected.get(10, TimeUnit.SECONDS);
      System.out.write((new Gson().toJson(probe.execute(credentials)) + "\n").getBytes(StandardCharsets.UTF_8));
      System.out.flush(); exit = 0;
    } catch (Exception e) { e.printStackTrace(System.err); }
    finally { if (connection != null) connection.close(); client.stop(); }
    System.exit(exit);
  }
  private MissionTalkCustomNativeProbe() {}
}
