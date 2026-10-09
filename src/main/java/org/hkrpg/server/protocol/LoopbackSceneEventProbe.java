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
public final class LoopbackSceneEventProbe {
  private record Frame(int command, byte[] body) {}

  private final ProtocolBridge protocol = ProtocolBridge.forVersion("4.6.0");
  private final BlockingQueue<Frame> inbox = new LinkedBlockingQueue<>();
  private final AtomicReference<Throwable> failure = new AtomicReference<>();
  private final CompletableFuture<Ukcp> connected = new CompletableFuture<>();
  private final List<String> checks = new ArrayList<>();
  private Ukcp channel;

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

  private int ret(DynamicMessage value) {
    return number(value, "retcode");
  }

  private DynamicMessage info() throws Exception {
    return (DynamicMessage) field(call("GetCurBattleInfoCsReq"), "battle_info");
  }

  private byte[] malformed(long... values) throws Exception {
    var out = new java.io.ByteArrayOutputStream();
    var wire = com.google.protobuf.CodedOutputStream.newInstance(out);
    for (int i = 0; i < values.length; i += 2) {
      wire.writeUInt32NoTag((int) values[i]);
      wire.writeUInt64NoTag(values[i + 1]);
    }
    wire.flush();
    return out.toByteArray();
  }

  private byte[] join(byte[]... rows) throws Exception {
    var out = new java.io.ByteArrayOutputStream();
    for (var row : rows) out.write(row);
    return out.toByteArray();
  }

  private byte[] nested(int field, byte[]... rows) throws Exception {
    var out = new java.io.ByteArrayOutputStream();
    var wire = com.google.protobuf.CodedOutputStream.newInstance(out);
    for (var row : rows) wire.writeByteArray(field, row);
    wire.flush();
    return out.toByteArray();
  }

  private byte[] targetEntry(long key, byte[]... targets) throws Exception {
    return join(malformed(8, key), nested(2, nested(1, targets)));
  }

  private List<byte[]> invalidTargetStatistics() throws Exception {
    var bad = new ArrayList<byte[]>();
    var target = malformed(8, 5001805, 16, 1, 24, 1);
    var entry = targetEntry(5, target);
    bad.add(nested(28, entry, entry));
    for (byte[] value :
        List.of(
            targetEntry(5, target, malformed(8, 5001805, 16, 0, 24, 1)),
            join(entry, malformed(8, 6)),
            join(entry, nested(2, new byte[0])),
            join(entry, malformed(72, 1)),
            join(malformed(8, 5), malformed(16, 1)),
            join(malformed(8, 5), nested(2, malformed(8, 1))),
            targetEntry(5, nested(1, new byte[0])),
            targetEntry(5, join(target, malformed(72, 1))),
            join(malformed(8, 5), nested(2, nested(9, target))))) bad.add(nested(28, value));
    bad.add(nested(28, new byte[0], malformed(8, 0)));
    for (int field = 1; field <= 3; field++)
      bad.add(nested(28, targetEntry(5, join(target, malformed(field * 8, 1)))));
    for (long[] variant :
        new long[][] {
          {1, 1L << 32}, {2, 1L << 32}, {3, 1L << 32},
          {1, Long.MAX_VALUE}, {2, -1}, {3, -1}
        }) {
      long id = 5001805, progress = 1, total = 1;
      if (variant[0] == 1) id = variant[1];
      else if (variant[0] == 2) progress = variant[1];
      else total = variant[1];
      bad.add(nested(28, targetEntry(5, malformed(8, id, 16, progress, 24, total))));
    }
    for (long key : new long[] {1L << 32, Long.MAX_VALUE, -1})
      bad.add(nested(28, targetEntry(key, target)));
    for (byte[] value :
        List.of(
            new byte[] {8, (byte) 128},
            new byte[] {18, 3, 10},
            new byte[] {8, 5, 18, 4, 10, 2, 8, (byte) 128})) bad.add(nested(28, value));
    var targets = new ArrayList<byte[]>();
    for (int i = 1; i <= 1025; i++) targets.add(malformed(8, i));
    bad.add(nested(28, targetEntry(5, targets.toArray(byte[][]::new))));
    var groups = new ArrayList<byte[]>();
    for (int i = 0; i < 33; i++) groups.add(targetEntry(i, target));
    bad.add(nested(28, groups.toArray(byte[][]::new)));
    require(bad.size() == 28, "Wrong target report rejection plan");
    return bad;
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
    String phase = input.get("phase").getAsString();
    boolean rejectLocalLegend =
        input.has("rejectLocalLegend") && input.get("rejectLocalLegend").getAsBoolean();
    boolean checkTargetReports =
        input.has("checkTargetReports") && input.get("checkTargetReports").getAsBoolean();
    int targetRejections = 0;
    var noBattle = call("GetCurBattleInfoCsReq", Map.of(), 1);
    check(
        !noBattle.hasField(noBattle.getDescriptorForType().findFieldByName("battle_info")),
        "no-battle-after-login-" + phase);
    var beforeBag = call("GetBagCsReq");
    var beforeQuests = call("GetQuestDataCsReq");
    for (byte[] body :
        List.of(
            new byte[0],
            malformed(88, 0),
            malformed(88, 20134220),
            malformed(88, 201342210),
            malformed(88, (1L << 32) + 20134221),
            malformed(88, 20134221, 88, 20134221),
            malformed(88, 20134221, 80, 0, 80, 0),
            malformed(88, 20134221, 128, 0),
            new byte[] {(byte) 128},
            new byte[] {88, (byte) 128},
            new byte[] {90, 0},
            malformed(88, 20134221, 80, 1))) {
      rawCall("SceneEnterStageCsReq", body, 5);
      check(true, "invalid-native-event-rejected-" + checks.size() + "-" + phase);
    }
    if (rejectLocalLegend) {
      for (int event : new int[] {426004, 426001, 426005}) {
        var rejected = call("SceneEnterStageCsReq", Map.of("event_id", event), 5);
        check(
            !rejected.hasField(rejected.getDescriptorForType().findFieldByName("battle_info")),
            "local-legend-generic-entry-rejected-" + event + "-" + phase);
        var empty = call("GetCurBattleInfoCsReq", Map.of(), 1);
        check(
            !empty.hasField(empty.getDescriptorForType().findFieldByName("battle_info")),
            "local-legend-rejection-does-not-create-battle-" + event + "-" + phase);
      }
    }
    check(
        beforeBag.equals(call("GetBagCsReq")), "rejected-events-do-not-change-inventory-" + phase);
    check(
        beforeQuests.equals(call("GetQuestDataCsReq")),
        "rejected-events-do-not-complete-quests-" + phase);
    var views = new ArrayList<Map<String, Object>>();
    if (phase.equals("battle")) {
      int previous = 0;
      for (var raw : input.getAsJsonArray("stagePlans")) {
        var plan = raw.getAsJsonObject();
        int event = plan.get("event").getAsInt(), stage = plan.get("stage").getAsInt();
        var response = call("SceneEnterStageCsReq", Map.of("event_id", event), 0);
        var battle = (DynamicMessage) field(response, "battle_info");
        int id = number(battle, "battle_id");
        check(
            id > 0 && id > previous && number(battle, "stage_id") == stage,
            "original-event-selects-world-zero-stage-" + event);
        previous = id;
        var nativeWaves = (List<?>) field(battle, "monster_wave_list");
        var actual = new ArrayList<List<Integer>>();
        for (var wave : nativeWaves) {
          var w = (DynamicMessage) wave;
          require(number(w, "battle_stage_id") == stage, "Wrong native wave stage");
          var monsters = new ArrayList<Integer>();
          for (var m : (List<?>) field(w, "monster_list"))
            monsters.add(number((DynamicMessage) m, "monster_id"));
          actual.add(monsters);
        }
        var expected = new ArrayList<List<Integer>>();
        for (var row : plan.getAsJsonArray("waves")) {
          var ids = new ArrayList<Integer>();
          for (var m : row.getAsJsonArray()) ids.add(m.getAsInt());
          expected.add(ids);
        }
        check(
            actual.equals(expected) && !actual.isEmpty(),
            "actual-original-monster-waves-preserved-" + event);
        check(
            !((List<?>) field(battle, "battle_avatar_list")).isEmpty(),
            "actual-formal-player-lineup-present-" + event);
        check(battle.equals(info()), "current-battle-retains-native-view-" + event);
        if (rejectLocalLegend) {
          for (int activityEvent : new int[] {426004, 426001, 426005}) {
            call("SceneEnterStageCsReq", Map.of("event_id", activityEvent), 5);
            check(
                battle.equals(info()),
                "local-legend-rejection-preserves-active-battle-" + activityEvent + "-" + event);
          }
        }
        call(
            "SceneEnterStageCsReq", Map.of("event_id", event == 20134221 ? 20134222 : 20134221), 5);
        check(battle.equals(info()), "repeated-entry-preserves-original-battle-" + event);
        var status =
            protocol
                .clientSchema()
                .message("PVEBattleResultCsReq")
                .findFieldByName("end_status")
                .getEnumType()
                .findValueByName("BATTLE_END_WIN");
        if (checkTargetReports) {
          var bag = call("GetBagCsReq");
          var quests = call("GetQuestDataCsReq");
          var identity =
              request(
                      "PVEBattleResultCsReq",
                      Map.of("battle_id", id, "stage_id", stage, "end_status", status))
                  .build()
                  .toByteArray();
          int statisticsField =
              protocol
                  .clientSchema()
                  .message("PVEBattleResultCsReq")
                  .findFieldByName("stt")
                  .getNumber();
          for (var statistics : invalidTargetStatistics()) {
            rawCall("PVEBattleResultCsReq", join(identity, nested(statisticsField, statistics)), 1);
            check(
                battle.equals(info()),
                "invalid-target-report-preserves-active-battle-"
                    + targetRejections++
                    + "-"
                    + event);
          }
          check(
              bag.equals(call("GetBagCsReq")),
              "invalid-target-reports-preserve-inventory-" + event);
          check(
              quests.equals(call("GetQuestDataCsReq")),
              "invalid-target-reports-preserve-quests-" + event);
        }
        call(
            "PVEBattleResultCsReq",
            Map.of("battle_id", id + 1, "stage_id", stage, "end_status", status),
            1);
        check(battle.equals(info()), "wrong-result-identity-preserves-battle-" + event);
        call(
            "PVEBattleResultCsReq",
            Map.of("battle_id", id, "stage_id", stage, "end_status", status),
            0);
        check(true, "actual-resource-battle-win-accepted-" + event);
        call(
            "PVEBattleResultCsReq",
            Map.of("battle_id", id, "stage_id", stage, "end_status", status),
            1);
        check(true, "duplicate-battle-win-rejected-" + event);
        var empty = call("GetCurBattleInfoCsReq", Map.of(), 1);
        check(
            !empty.hasField(empty.getDescriptorForType().findFieldByName("battle_info")),
            "settled-battle-cleared-" + event);
        views.add(Map.of("event", event, "stage", stage, "battleId", id, "waves", actual));
      }
    }
    var finalInfo = call("GetCurBattleInfoCsReq", Map.of(), 1);
    check(
        !finalInfo.hasField(finalInfo.getDescriptorForType().findFieldByName("battle_info")),
        "read-after-settlement-has-no-active-battle-" + phase);
    return Map.of(
        "passed",
        true,
        "uid",
        uid,
        "phase",
        phase,
        "checks",
        checks,
        "actualResourceBattles",
        views,
        "invalidTargetReportsRejected",
        targetRejections,
        "clientGameplayVerified",
        false);
  }

  public static void main(String[] args) {
    var probe = new LoopbackSceneEventProbe();
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
