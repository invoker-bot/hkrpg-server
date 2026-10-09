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

/** Exact original scene events and their actual battle waves, over synthetic UDP/KCP. */
public final class NativeStageEventDifficultyProbe {
  private record Frame(int command, byte[] body) {}

  private final ProtocolBridge protocol = ProtocolBridge.forVersion("4.6.0");
  private final BlockingQueue<Frame> inbox = new LinkedBlockingQueue<>();
  private final AtomicReference<Throwable> failure = new AtomicReference<>();
  private final CompletableFuture<Ukcp> connected = new CompletableFuture<>();
  private final List<String> checks = new ArrayList<>();
  private Ukcp channel;
  private int requests;
  private final List<Integer> stages = new ArrayList<>();

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

  private DynamicMessage info() throws Exception {
    return (DynamicMessage) field(call("GetCurBattleInfoCsReq"), "battle_info");
  }

  private DynamicMessage difficulty(int value) {
    return difficulty(20003011, value);
  }

  private DynamicMessage difficulty(int event, int value) {
    var b = DynamicMessage.newBuilder(protocol.clientSchema().message("AMKBFMCOIHK"));
    b.setField(b.getDescriptorForType().findFieldByName("id"), event);
    for (var pair : Map.of("source_type", 2, "ANJMFPKJHOH", value).entrySet()) {
      var field = b.getDescriptorForType().findFieldByName(pair.getKey());
      b.setField(field, field.getEnumType().findValueByNumber(pair.getValue()));
    }
    return b.build();
  }

  private void preference(int value) throws Exception {
    var choices = (List<?>) field(call("DifficultyAdjustmentGetDataCsReq"), "content_package_list");
    require(
        choices.equals(value < 0 ? List.of() : List.of(difficulty(value))),
        "Preference did not restore");
  }

  private void battle(int selected) throws Exception {
    battle(20003011, selected, List.of(8015011));
  }

  private void battle(int event, int selected, List<Integer> expectedMonsters) throws Exception {
    battleWaves(event, selected, List.of(expectedMonsters));
  }

  private void battleWaves(int event, int selected, List<List<Integer>> expectedWaves)
      throws Exception {
    int stage = event * 10 + 1 - selected;
    var started =
        (DynamicMessage)
            field(call("SceneEnterStageCsReq", Map.of("event_id", event), 0), "battle_info");
    require(
        number(started, "stage_id") == stage && number(started, "world_level") == 1 - selected,
        "Wrong effective event stage/world");
    var waves = (List<?>) field(started, "monster_wave_list");
    require(waves.size() == expectedWaves.size(), "Unexpected original waves");
    for (int index = 0; index < waves.size(); index++) {
      var wave = (DynamicMessage) waves.get(index);
      var monsters = (List<?>) field(wave, "monster_list");
      require(
          number(wave, "battle_stage_id") == stage
              && monsters.stream()
                  .map(monster -> number((DynamicMessage) monster, "monster_id"))
                  .toList()
                  .equals(expectedWaves.get(index)),
          "Original monster changed");
    }
    require(!((List<?>) field(started, "battle_avatar_list")).isEmpty(), "Formal team absent");
    require(started.equals(info()), "Current battle changed");
    call("DifficultyAdjustmentUpdateDataCsReq", Map.of("data", difficulty(event, 1 - selected)), 5);
    require(started.equals(info()), "Rejected preference change altered battle");
    call("SceneEnterStageCsReq", Map.of("event_id", event), 5);
    require(started.equals(info()), "Repeated entry altered battle");
    var result = protocol.clientSchema().message("PVEBattleResultCsReq");
    var end = result.findFieldByName("end_status").getEnumType().findValueByName("BATTLE_END_QUIT");
    int id = number(started, "battle_id");
    call(
        "PVEBattleResultCsReq",
        Map.of("battle_id", id + 1, "stage_id", stage, "end_status", end),
        1);
    require(started.equals(info()), "Wrong result identity altered battle");
    var fields = Map.<String, Object>of("battle_id", id, "stage_id", stage, "end_status", end);
    call("PVEBattleResultCsReq", fields, 0);
    call("PVEBattleResultCsReq", fields, 1);
    var empty = call("GetCurBattleInfoCsReq", Map.of(), 1);
    require(
        !empty.hasField(empty.getDescriptorForType().findFieldByName("battle_info")),
        "Quit battle still active");
    stages.add(stage);
  }

  private Map<String, Object> execute(JsonObject input) throws Exception {
    String mode = input.get("mode").getAsString();
    require(
        Set.of(
                "pending",
                "unselected",
                "easyRestart",
                "defaultRestart",
                "singleMatrix",
                "singleRestart")
            .contains(mode),
        "Unknown mode");
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
    var basic = (DynamicMessage) field(call("PlayerLoginCsReq"), "basic_info");
    call("PlayerLoginFinishCsReq");
    require(
        number(basic, "world_level") == (mode.equals("pending") ? 0 : 1),
        "Explicit player world drifted");
    if (mode.equals("singleMatrix") || mode.equals("singleRestart")) {
      var events = input.getAsJsonArray("singleEvents");
      boolean all = input.has("allOriginalEvents") && input.get("allOriginalEvents").getAsBoolean();
      require(events.size() == (all ? 57 : 28), "Incomplete original event matrix");
      var expected = new TreeMap<Integer, DynamicMessage>();
      if (mode.equals("singleRestart"))
        for (var raw : events) expected.put(raw.getAsInt(), difficulty(raw.getAsInt(), 1));
      require(
          ((List<?>) field(call("DifficultyAdjustmentGetDataCsReq"), "content_package_list"))
              .equals(new ArrayList<>(expected.values())),
          "Initial independent preferences changed");
      for (var raw : events) {
        int event = raw.getAsInt();
        if (mode.equals("singleMatrix")) {
          originalBattle(input, event, 0);
          for (int selected : new int[] {0, 1}) {
            call(
                "DifficultyAdjustmentUpdateDataCsReq",
                Map.of("data", difficulty(event, selected)),
                0);
            expected.put(event, difficulty(event, selected));
            require(
                ((List<?>) field(call("DifficultyAdjustmentGetDataCsReq"), "content_package_list"))
                    .equals(new ArrayList<>(expected.values())),
                "Event preference leaked or lost");
            originalBattle(input, event, selected);
          }
        } else originalBattle(input, event, 1);
      }
    } else preference(mode.equals("easyRestart") ? 1 : mode.equals("defaultRestart") ? 0 : -1);
    if (mode.equals("unselected")) {
      battle(0);
      call("DifficultyAdjustmentUpdateDataCsReq", Map.of("data", difficulty(1)), 0);
      preference(1);
      battle(1);
    } else if (mode.equals("easyRestart")) {
      battle(1);
      call("DifficultyAdjustmentUpdateDataCsReq", Map.of("data", difficulty(0)), 0);
      preference(0);
      battle(0);
    } else if (mode.equals("defaultRestart")) battle(0);
    var out = new LinkedHashMap<String, Object>();
    out.put("passed", true);
    out.put("uid", uid);
    out.put("mode", mode);
    out.put("requests", requests);
    out.put("actualWorld", number(basic, "world_level"));
    out.put("actualEventBattles", stages.size());
    out.put("stages", stages);
    out.put("clientGameplayVerified", false);
    return out;
  }

  private void originalBattle(JsonObject input, int event, int selected) throws Exception {
    int id = event * 10 + 1 - selected;
    JsonObject original = null;
    for (var raw : input.getAsJsonArray("stages"))
      if (raw.getAsJsonObject().get("StageID").getAsInt() == id) original = raw.getAsJsonObject();
    require(original != null, "Missing original stage");
    var waves = original.getAsJsonArray("MonsterList");
    require(!waves.isEmpty(), "Empty original stage waves");
    var expected = new ArrayList<List<Integer>>();
    for (var raw : waves) {
      var wave = raw.getAsJsonObject();
      var ids = new ArrayList<Integer>();
      for (int slot = 0; slot < 5; slot++) {
        var value = wave.get("Monster" + slot);
        if (value != null && value.getAsInt() != 0) ids.add(value.getAsInt());
      }
      require(!ids.isEmpty(), "Empty original monster wave");
      expected.add(List.copyOf(ids));
    }
    battleWaves(event, selected, expected);
  }

  public static void main(String[] args) {
    var probe = new NativeStageEventDifficultyProbe();
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
