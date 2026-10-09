package org.hkrpg.server.protocol;

import com.google.gson.*;
import com.google.protobuf.Descriptors.EnumValueDescriptor;
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

/** Original 4040204/4040208 dialogue completions advance the original 4040290 list thresholds. */
public final class LoopbackFinishMainListProbe {
  private record Frame(int command, byte[] body) {}

  private final ProtocolBridge protocol = ProtocolBridge.forVersion("4.6.0");
  private final BlockingQueue<Frame> inbox = new LinkedBlockingQueue<>();
  private final AtomicReference<Throwable> failure = new AtomicReference<>();
  private final CompletableFuture<Ukcp> connected = new CompletableFuture<>();
  private final List<String> checks = new ArrayList<>();
  private Ukcp channel;
  private boolean verifyQuestMainCompletion;
  private int awaitedQuest;
  private boolean questCompletionNotify;

  private static Object field(DynamicMessage m, String name) {
    return m.getField(m.getDescriptorForType().findFieldByName(name));
  }

  private static int number(DynamicMessage m, String name) {
    return (Integer) field(m, name);
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
    var packet =
        Unpooled.wrappedBuffer(
            PacketCodec.encode(
                protocol.clientCommand(name),
                new byte[0],
                request(name, fields).build().toByteArray()));
    try {
      channel.write(packet);
    } finally {
      packet.release();
    }
    String response = name.replace("CsReq", "ScRsp");
    int command = protocol.clientCommand(response);
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
    while (System.nanoTime() < deadline) {
      if (failure.get() != null)
        throw new IllegalStateException("KCP connection failed", failure.get());
      var frame = inbox.poll(1, TimeUnit.SECONDS);
      if (frame == null) continue;
      if (awaitedQuest != 0 && frame.command() == protocol.clientCommand("GetQuestDataScRsp")) {
        var data = protocol.clientSchema().parse("GetQuestDataScRsp", frame.body());
        require(data.getUnknownFields().asMap().isEmpty(), "Unknown quest notification fields");
        for (var raw : (List<?>) field(data, "quest_list")) {
          var row = (DynamicMessage) raw;
          if (number(row, "id") == awaitedQuest
              && number(row, "progress") == 1
              && ((EnumValueDescriptor) field(row, "status")).getNumber() == 2)
            questCompletionNotify = true;
        }
      }
      if (frame.command() != command) continue;
      var m = protocol.clientSchema().parse(response, frame.body());
      require(m.getUnknownFields().asMap().isEmpty(), "Unknown response fields: " + response);
      require(
          number(m, "retcode") == code,
          "Response rejected: " + response + " code=" + number(m, "retcode"));
      return m;
    }
    throw new TimeoutException(response);
  }

  private DynamicMessage call(String name) throws Exception {
    return call(name, Map.of(), 0);
  }

  private void talk(int id, String text, int code, String label) throws Exception {
    call("FinishTalkMissionCsReq", Map.of("sub_mission_id", id, "talk_str", text), code);
    check(true, label);
  }

  private Map<Integer, Integer> materials() throws Exception {
    var stocks = new TreeMap<Integer, Integer>();
    for (var raw : (List<?>) field(call("GetBagCsReq"), "material_list")) {
      var row = (DynamicMessage) raw;
      int id = number(row, "tid");
      require(stocks.put(id, number(row, "num")) == null, "Duplicate actual material " + id);
    }
    return stocks;
  }

  private void progress(int count, String label) throws Exception {
    var data = call("GetMissionDataCsReq");
    var rows = new HashMap<Integer, DynamicMessage>();
    for (var raw : (List<?>) field(data, "mission_list")) {
      var row = (DynamicMessage) raw;
      int id = number(row, "id");
      if (id >= 404029001 && id <= 404029006)
        require(rows.put(id, row) == null, "Duplicate original threshold " + id);
    }
    require(rows.size() == 6, "Missing original threshold objectives");
    for (int goal = 1; goal <= 6; goal++) {
      var row = rows.get(404029000 + goal);
      require(
          number(row, "progress") == Math.min(count, goal),
          "Wrong original threshold count " + goal);
      require(
          ((EnumValueDescriptor) field(row, "status")).getNumber() == (count >= goal ? 2 : 1),
          "Wrong original threshold phase " + goal);
    }
    var completed = (List<?>) field(data, "finished_main_mission_id_list");
    require(!completed.contains(4040290), "Incomplete original message chain claimed completed");
    require(completed.contains(4040204) == (count >= 1), "Wrong first source main history");
    require(completed.contains(4040208) == (count >= 2), "Wrong second source main history");
    checks.add(label);
    if (verifyQuestMainCompletion) quests(count, label);
  }

  private void quests(int count, String label) throws Exception {
    var data = call("GetQuestDataCsReq");
    var rows = new HashMap<Integer, DynamicMessage>();
    for (var raw : (List<?>) field(data, "quest_list")) {
      var row = (DynamicMessage) raw;
      int id = number(row, "id");
      if (id == 4052344 || id == 4052348)
        require(rows.put(id, row) == null, "Duplicate original main-completion quest");
    }
    for (int threshold = 1; threshold <= 2; threshold++) {
      int id = threshold == 1 ? 4052344 : 4052348;
      var row = rows.get(id);
      check(
          row != null
              && number(row, "progress") == (count >= threshold ? 1 : 0)
              && ((EnumValueDescriptor) field(row, "status")).getNumber()
                  == (count >= threshold ? 2 : 1)
              && ((Long) field(row, "finish_time") > 0) == (count >= threshold),
          label + "-ordinary-quest-" + id);
    }
  }

  private void entry(int id, int plane, int floor) throws Exception {
    call("EnterSceneCsReq", Map.of("entry_id", id), 0);
    var scene = (DynamicMessage) field(call("GetCurSceneInfoCsReq"), "scene");
    check(
        number(scene, "entry_id") == id
            && number(scene, "plane_id") == plane
            && number(scene, "floor_id") == floor
            && number(scene, "leader_entity_id") > 0,
        "fixture-entry-at-original-floor-and-real-actor-" + id);
  }

  private Map<String, Object> execute(JsonObject credentials) throws Exception {
    verifyQuestMainCompletion =
        credentials.has("verifyQuestMainCompletion")
            && credentials.get("verifyQuestMainCompletion").getAsBoolean();
    var token =
        call(
            "PlayerGetTokenCsReq",
            Map.of(
                "account_uid",
                credentials.get("accountUid").getAsString(),
                "token",
                credentials.get("comboToken").getAsString(),
                "platform",
                1),
            0);
    int uid = number(token, "uid");
    var login = call("PlayerLoginCsReq");
    call("PlayerLoginFinishCsReq");
    String phase = credentials.get("phase").getAsString();
    int wallet = number((DynamicMessage) field(login, "basic_info"), "scoin");
    check(wallet == (phase.equals("final") ? 5000 : 0), "original-main-wallet-restored");
    int count = phase.equals("first") ? 0 : phase.equals("final") ? 2 : 1;
    var start = materials();
    int original =
        credentials.has("initialMaterial")
            ? credentials.get("initialMaterial").getAsInt()
            : start.getOrDefault(120002, 0);
    if (phase.equals("first")) {
      call("AcceptMainMissionCsReq", Map.of("main_mission_id", 4040290), 0);
      check(true, "isolated-parent-acceptance");
    } else {
      progress(count, "saved-main-completions-and-six-thresholds");
      check(
          start.getOrDefault(120002, 0) == original + count * 10,
          "actual-original-material-restored");
    }
    if (phase.equals("first") || phase.equals("second")) {
      int main = phase.equals("first") ? 4040204 : 4040208;
      int first = phase.equals("first") ? 404020401 : 404020801;
      int last = phase.equals("first") ? 404020403 : 404020802;
      entry(
          phase.equals("first") ? 1020101 : 1020201,
          phase.equals("first") ? 10201 : 10202,
          phase.equals("first") ? 10201001 : 10202001);
      call("AcceptMainMissionCsReq", Map.of("main_mission_id", main), 0);
      check(true, "original-source-main-accepted-" + main);
      progress(count, "acceptance-does-not-count-as-completion");
      talk(
          last,
          "Mission_" + last,
          Retcode.MISSION_NOT_DOING.getVal(),
          "last-dialogue-cannot-skip-original-prerequisite");
      talk(
          first,
          "wrong-original-dialogue",
          Retcode.MISSION_FINISH_WAY_NOT_MATCH.getVal(),
          "wrong-original-talk-rejected");
      progress(count, "rejected-reports-do-not-count");
      talk(first, "Mission_" + first, 0, "first-original-dialogue-completed");
      progress(count, "partial-source-main-does-not-count");
      if (verifyQuestMainCompletion) {
        awaitedQuest = main == 4040204 ? 4052344 : 4052348;
        questCompletionNotify = false;
      }
      talk(last, "Mission_" + last, 0, "last-original-dialogue-completes-source-main");
      if (verifyQuestMainCompletion) {
        check(questCompletionNotify, "main-completion-quest-notify-before-final-talk-response");
        awaitedQuest = 0;
      }
      count++;
      progress(count, "actual-completed-main-advances-original-thresholds");
      check(
          materials().getOrDefault(120002, 0) == original + count * 10,
          "actual-original-main-material-payout");
    }
    int last = count == 1 ? 404020403 : 404020802;
    var paid = materials();
    talk(
        last,
        "Mission_" + last,
        Retcode.MISSION_NOT_DOING.getVal(),
        "completed-original-dialogue-not-replayed");
    progress(count, "duplicate-report-keeps-exact-thresholds");
    check(materials().equals(paid), "duplicate-report-does-not-repeat-actual-inventory-payout");
    var result = new LinkedHashMap<String, Object>();
    result.put("passed", true);
    result.put("uid", uid);
    result.put("phase", phase);
    result.put("loginWallet", wallet);
    result.put("count", count);
    result.put("checks", checks);
    result.put("initialMaterial", original);
    result.put("actualMaterials", paid);
    result.put("questMainCompletionVerified", verifyQuestMainCompletion);
    result.put("immediateQuestCompletionNotifyVerified", questCompletionNotify);
    result.put("sourceMainGraphsUnmodified", true);
    result.put("wholeParentMessageChainVerified", false);
    result.put("clientGameplayVerified", false);
    return result;
  }

  public static void main(String[] args) {
    var probe = new LoopbackFinishMainListProbe();
    var client = new KcpClient();
    Ukcp connection = null;
    int exit = 1;
    try {
      var credentials =
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
                  require(frame != null, "Truncated mission frame");
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
                  null, new IllegalStateException("Mission probe disconnected"));
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
