package org.hkrpg.server.protocol;

import com.google.gson.Gson;
import com.google.gson.JsonParser;
import com.google.protobuf.DynamicMessage;
import emu.lunarcore.server.packet.PacketCodec;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import kcp.highway.*;

/** Isolated native dormant Elation reports, exact farming charges, and normal H2 restart. */
public final class NativeCocoonElationSettlementProbe {
  private final ProtocolBridge protocol = ProtocolBridge.forVersion("4.6.0");
  private final BlockingQueue<PacketCodec.Frame> inbox = new LinkedBlockingQueue<>();
  private final AtomicReference<Throwable> failure = new AtomicReference<>();
  private final CompletableFuture<Ukcp> connected = new CompletableFuture<>();
  private Ukcp channel;
  private int requests;
  private volatile DynamicMessage stamina;
  private int loginStamina;
  private final Map<Integer, Integer> expectedMaterials = new java.util.TreeMap<>();

  private static void require(boolean valid, String why) {
    if (!valid) throw new IllegalStateException(why);
  }

  private static Object field(DynamicMessage message, String name) {
    return message.getField(message.getDescriptorForType().findFieldByName(name));
  }

  private DynamicMessage.Builder builder(String name) {
    return DynamicMessage.newBuilder(protocol.clientSchema().message(name));
  }

  private DynamicMessage call(String name) throws Exception {
    return call(name, builder(name));
  }

  private DynamicMessage call(String name, DynamicMessage.Builder body) throws Exception {
    return call(name, body.build().toByteArray(), 0);
  }

  private DynamicMessage call(String name, byte[] body, int expectedCode) throws Exception {
    byte[] header = {8, (byte) ++requests};
    var request = Unpooled.wrappedBuffer(PacketCodec.encode(protocol.clientCommand(name), header,
        body));
    try { channel.write(request); } finally { request.release(); }
    String response = name.replace("CsReq", "ScRsp");
    long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    while (System.nanoTime() < end) {
      if (failure.get() != null) throw new IllegalStateException("Connection failed", failure.get());
      var frame = inbox.poll(100, TimeUnit.MILLISECONDS);
      if (frame == null || frame.cmdId() != protocol.clientCommand(response)) continue;
      require(Arrays.equals(header, frame.header()), "Response lost the pending request header");
      var message = protocol.clientSchema().parse(response, frame.body());
      require(message.getUnknownFields().asMap().isEmpty(), "Unknown response fields");
      require(expectedCode < 0 ? !field(message, "retcode").equals(0) : field(message, "retcode").equals(expectedCode), "Wrong result code: " + response + " / " + field(message, "retcode"));
      return message;
    }
    throw new TimeoutException(response);
  }

  private void balance(int expected) {
    // A rejected report emits no balance notification; retain the real login snapshot then.
    int actual = stamina == null ? loginStamina : (Integer) field(stamina, "stamina");
    require(actual == expected, "Wrong settlement balance: " + actual + " / " + expected);
  }

  private DynamicMessage scene() throws Exception {
    var reply = call("GetCurSceneInfoCsReq");
    return (DynamicMessage) field(reply, "scene");
  }

  private Map<Integer, Integer> materials() throws Exception {
    var reply = call("GetBagCsReq");
    var result = new java.util.TreeMap<Integer, Integer>();
    for (var raw : (java.util.List<?>) field(reply, "material_list")) {
      var row = (DynamicMessage) raw;
      result.merge((Integer) field(row, "tid"), (Integer) field(row, "num"), Integer::sum);
    }
    return result;
  }

  private void sameLocation(DynamicMessage before, DynamicMessage after) {
    for (String key : new String[] {"entry_id", "floor_id", "plane_id"})
      require(field(before, key).equals(field(after, key)), "Direct farming changed scene: " + key);
  }

  private DynamicMessage start(String name, int count) throws Exception {
    var body = builder(name);
    body.setField(body.getDescriptorForType().findFieldByName("cocoon_id"), 1029);
    body.setField(body.getDescriptorForType().findFieldByName("HECCOBFBJFI"), count);
    if (name.equals("StartCocoonStageCsReq"))
      body.setField(body.getDescriptorForType().findFieldByName("prop_entity_id"), 33);
    var reply = call(name, body);
    require(field(reply, "wave").equals(count), "Wrong requested challenge count");
    var info = (DynamicMessage) field(reply, "battle_info");
    require(((java.util.List<?>) field(info, "monster_wave_list")).size() == count, "Shortened battle");
    require(((java.util.List<?>) field(info, "battle_event")).isEmpty(), "Idle telemetry spawned an extra event");
    require(java.util.Set.of(1024810, 1024820, 1024830).contains(field(info, "stage_id")), "Wrong original 1029 stage");
    return info;
  }

  private DynamicMessage finish(DynamicMessage info, int eventId, int current, int maximum,
      boolean duplicate, int expected) throws Exception {
    var body = builder("PVEBattleResultCsReq");
    for (String key : new String[] {"battle_id", "stage_id"})
      body.setField(body.getDescriptorForType().findFieldByName(key), field(info, key));
    var end = body.getDescriptorForType().findFieldByName("end_status");
    body.setField(end, end.getEnumType().findValueByName("BATTLE_END_WIN"));
    var stats = builder("BattleStatistics");
    var event = builder("BattleEventBattleInfo");
    event.setField(event.getDescriptorForType().findFieldByName("battle_event_id"), eventId);
    var status = builder("BattleEventProperty");
    var sp = builder("SpBarInfo");
    sp.setField(sp.getDescriptorForType().findFieldByName("cur_sp"), current);
    sp.setField(sp.getDescriptorForType().findFieldByName("max_sp"), maximum);
    status.setField(status.getDescriptorForType().findFieldByName("sp_bar"), sp.build());
    event.setField(event.getDescriptorForType().findFieldByName("status"), status.build());
    var events = stats.getDescriptorForType().findFieldByName("BDCHEJKPOAF");
    stats.addRepeatedField(events, event.build());
    if (duplicate) stats.addRepeatedField(events, event.build());
    // Use the actual server-sent combatants. Keep their health/energy valid without creating actors.
    for (var raw : (java.util.List<?>) field(info, "battle_avatar_list")) {
      var actor = (DynamicMessage) raw;
      var row = builder("AvatarBattleInfo");
      row.setField(row.getDescriptorForType().findFieldByName("id"), field(actor, "id"));
      var vitals = builder("AvatarProperty");
      for (var value : Map.of("max_hp", 1000.0, "left_hp", 800.0, "max_sp", 100.0, "left_sp", 0.0).entrySet())
        vitals.setField(vitals.getDescriptorForType().findFieldByName(value.getKey()), value.getValue());
      row.setField(row.getDescriptorForType().findFieldByName("avatar_status"), vitals.build());
      stats.addRepeatedField(stats.getDescriptorForType().findFieldByName("battle_avatar_list"), row.build());
    }
    body.setField(body.getDescriptorForType().findFieldByName("stt"), stats.build());
    var reply = call("PVEBattleResultCsReq", body.build().toByteArray(), expected);
    if (expected == 0) {
      require(field(reply, "battle_id").equals(field(info, "battle_id")), "Lost battle reply identity");
      require(field(reply, "stage_id").equals(field(info, "stage_id")), "Lost stage reply identity");
      require(field(reply, "check_identical").equals(true), "Settlement did not complete");
      var drops = (DynamicMessage) field(reply, "drop_data");
      require(!((java.util.List<?>) field(drops, "item_list")).isEmpty(), "Actual farming rewards missing");
      for (var raw : (java.util.List<?>) field(drops, "item_list")) {
        var row = (DynamicMessage) raw;
        int id = (Integer) field(row, "item_id");
        if (id >= 100) expectedMaterials.merge(id, (Integer) field(row, "num"), Integer::sum);
      }
    }
    require(materials().equals(expectedMaterials), "Actual bag differs from exactly-once farming rewards");
    return reply;
  }

  private Map<String, Object> execute(com.google.gson.JsonObject control) throws Exception {
    var token = builder("PlayerGetTokenCsReq");
    token.setField(token.getDescriptorForType().findFieldByName("account_uid"), control.get("accountUid").getAsString());
    token.setField(token.getDescriptorForType().findFieldByName("token"), control.get("comboToken").getAsString());
    token.setField(token.getDescriptorForType().findFieldByName("platform"), 1);
    int uid = (Integer) field(call("PlayerGetTokenCsReq", token), "uid");
    var login = call("PlayerLoginCsReq");
    loginStamina = (Integer) field(login, "stamina");
    String mode = control.get("mode").getAsString();
    require(field(login, "stamina").equals(mode.equals("restart") ? 40 : 300), "Persisted settlement charge changed");
    call("PlayerLoginFinishCsReq");
    var before = scene();
    expectedMaterials.putAll(materials());
    int settled = 0;
    if (mode.equals("battles")) {
      var first = start("StartCocoonStageCsReq", 1);
      for (int[] row : new int[][] {{70002, 0, 100000}, {70001, 1, 100000}, {70001, 0, 99999}}) {
        finish(first, row[0], row[1], row[2], false, 1);
        balance(300);
      }
      finish(first, 70001, 0, 100000, true, 1);
      balance(300);
      var active = (DynamicMessage) field(call("GetCurBattleInfoCsReq"), "battle_info");
      require(field(active, "battle_id").equals(field(first, "battle_id")), "Invalid report consumed active battle");
      finish(first, 70001, 0, 100000, false, 0); balance(290);
      finish(first, 70001, 0, 100000, false, 1); balance(290);
      var batch = start("StartCocoonStageCsReq", 24);
      finish(batch, 70001, 0, 100000, false, 0); balance(50);
      finish(batch, 70001, 0, 100000, false, 1); balance(50);
      var quick = start("QuickStartCocoonStageCsReq", 1);
      finish(quick, 70001, 0, 100000, false, 0); balance(40);
      finish(quick, 70001, 0, 100000, false, 1); balance(40);
      settled = 26;
    } else if (mode.equals("restart")) {
      var active = (DynamicMessage) field(call("GetCurBattleInfoCsReq"), "battle_info");
      require(field(active, "battle_id").equals(0), "Settled battle resurrected after restart");
      require(new Gson().toJsonTree(expectedMaterials).equals(control.get("expectedMaterials")),
          "Actual awarded materials changed across normal H2 restart");
    } else if (!mode.equals("initialize")) throw new IllegalStateException("Unknown probe phase");
    sameLocation(before, scene());
    var heartbeat = builder("PlayerHeartBeatCsReq");
    heartbeat.setField(heartbeat.getDescriptorForType().findFieldByName("client_time_ms"), 1_793_500_000_000L);
    call("PlayerHeartBeatCsReq", heartbeat);
    return Map.of("passed", true, "uid", uid, "mode", mode, "nativeRequests", requests,
        "actualSettledChallenges", settled, "correlationHeadersVerified", true,
        "sceneEntryUnchanged", true, "materialTotals", expectedMaterials, "clientUIVerified", false);
  }

  public static void main(String[] args) {
    var probe = new NativeCocoonElationSettlementProbe();
    var client = new KcpClient();
    Ukcp connection = null;
    int exit = 1;
    try {
      var control = JsonParser.parseReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))
          .getAsJsonObject();
      var config = new ChannelConfig();
      config.nodelay(true, 40, 2, true);
      config.setMtu(1200); config.setSndwnd(256); config.setRcvwnd(256);
      config.setUseConvChannel(true);
      config.setConv(ThreadLocalRandom.current().nextInt(1, Integer.MAX_VALUE));
      config.setTimeoutMillis(25000); config.setAckNoDelay(true);
      client.init(config, new KcpListener() {
        public void onConnected(Ukcp channel) { probe.connected.complete(channel); }
        public void handleReceive(ByteBuf bytes, Ukcp channel) {
          try {
            while (bytes.isReadable()) {
              var frame = PacketCodec.decodeNext(bytes);
              require(frame != null, "Truncated frame");
              if (frame.cmdId() == probe.protocol.clientCommand("StaminaInfoScNotify"))
                probe.stamina = probe.protocol.clientSchema().parse("StaminaInfoScNotify", frame.body());
              probe.inbox.add(frame);
            }
          } catch (Throwable error) { probe.failure.set(error); }
        }
        public void handleException(Throwable error, Ukcp channel) {
          probe.failure.set(error); probe.connected.completeExceptionally(error);
        }
        public void handleClose(Ukcp channel) {
          probe.failure.compareAndSet(null, new IllegalStateException("Disconnected"));
        }
      });
      connection = client.connect(new InetSocketAddress("127.0.0.1", Integer.parseInt(args[0])), config);
      probe.channel = probe.connected.get(10, TimeUnit.SECONDS);
      System.out.write((new Gson().toJson(probe.execute(control)) + "\n").getBytes(StandardCharsets.UTF_8));
      System.out.flush(); exit = 0;
    } catch (Exception error) { error.printStackTrace(System.err); }
    finally { if (connection != null) connection.close(); client.stop(); }
    System.exit(exit);
  }
}
