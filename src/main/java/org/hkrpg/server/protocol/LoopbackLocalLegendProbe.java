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

/** Dedicated Local Legend entries and constructed result reports over synthetic UDP/KCP. */
public final class LoopbackLocalLegendProbe {
  private record Frame(int command, byte[] body) {}

  private final ProtocolBridge protocol = ProtocolBridge.forVersion("4.6.0");
  private final BlockingQueue<Frame> inbox = new LinkedBlockingQueue<>();
  private final AtomicReference<Throwable> failure = new AtomicReference<>();
  private final CompletableFuture<Ukcp> connected = new CompletableFuture<>();
  private final List<String> checks = new ArrayList<>();
  private JsonObject stagePlans;
  private JsonObject targetPlans;
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
      if (frame.command() == protocol.clientCommand("LocalLegendDataChangeNotify")) {
        var message = protocol.clientSchema().parse("LocalLegendDataChangeNotify", frame.body());
        var row = (DynamicMessage) field(message, "level_data");
        notifiedMasks.put(
            number(row, "group_id") + ":" + number(row, "DBKHHCGGKNN"), number(row, "FFGFINDLMCF"));
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

  private final Map<String, Integer> notifiedMasks = new HashMap<>();

  private DynamicMessage.Builder entry(int group, int difficulty, int id, int avatarType) {
    var b =
        request("EnterLocalLegendLevelCsReq", Map.of("group_id", group, "DBKHHCGGKNN", difficulty));
    var list = b.getDescriptorForType().findFieldByName("avatar_list");
    var type = list.getMessageType();
    b.addRepeatedField(
        list,
        DynamicMessage.newBuilder(type)
            .setField(type.findFieldByName("avatar_id"), id)
            .setField(
                type.findFieldByName("avatar_type"),
                type.findFieldByName("avatar_type")
                    .getEnumType()
                    .findValueByName(
                        avatarType == 1
                            ? "AvatarType_AvatarFormalType"
                            : "AvatarType_AvatarTrialType"))
            .build());
    return b;
  }

  private Map<String, Integer> masks() throws Exception {
    var message = call("GetLocalLegendDataCsReq");
    var result = new TreeMap<String, Integer>();
    for (var raw : (List<?>) field(message, "level_data_list")) {
      var row = (DynamicMessage) raw;
      String key = number(row, "group_id") + ":" + number(row, "DBKHHCGGKNN");
      require(result.put(key, number(row, "FFGFINDLMCF")) == null, "Repeated Local Legend row");
    }
    require(result.size() == 15, "Original Local Legend level count changed");
    return result;
  }

  private DynamicMessage info() throws Exception {
    return (DynamicMessage) field(call("GetCurBattleInfoCsReq"), "battle_info");
  }

  private byte[] result(DynamicMessage battle, String outcome, int target, int progress) {
    int sourceAchievement =
        switch (number(battle, "stage_id")) {
          case 4260041 -> 30212;
          case 426005 -> 30214;
          default -> 30213;
        };
    return result(battle, outcome, target, progress, sourceAchievement);
  }

  private byte[] result(
      DynamicMessage battle, String outcome, int target, int progress, int achievementId) {
    var b =
        request(
            "PVEBattleResultCsReq",
            Map.of(
                "battle_id", number(battle, "battle_id"), "stage_id", number(battle, "stage_id")));
    var status = b.getDescriptorForType().findFieldByName("end_status");
    b.setField(status, status.getEnumType().findValueByName(outcome));
    if (target > 0) {
      var statistics = b.getDescriptorForType().findFieldByName("stt");
      var stat = DynamicMessage.newBuilder(statistics.getMessageType());
      var map = stat.getDescriptorForType().findFieldByName("battle_target_info");
      var category = DynamicMessage.newBuilder(map.getMessageType());
      var value = category.getDescriptorForType().findFieldByName("value");
      var list = DynamicMessage.newBuilder(value.getMessageType());
      var goals = list.getDescriptorForType().findFieldByName("battle_target_list");
      var goal = DynamicMessage.newBuilder(goals.getMessageType());
      goal.setField(goal.getDescriptorForType().findFieldByName("id"), target)
          .setField(goal.getDescriptorForType().findFieldByName("progress"), progress);
      list.addRepeatedField(goals, goal.build());
      category
          .setField(category.getDescriptorForType().findFieldByName("key"), 5)
          .setField(value, list.build());
      stat.addRepeatedField(map, category.build());
      if (achievementId > 0) {
        var achievementCategory = DynamicMessage.newBuilder(map.getMessageType());
        var achievementList = DynamicMessage.newBuilder(value.getMessageType());
        var achievement = DynamicMessage.newBuilder(goals.getMessageType());
        achievement
            .setField(achievement.getDescriptorForType().findFieldByName("id"), achievementId)
            .setField(achievement.getDescriptorForType().findFieldByName("progress"), 1);
        achievementList.addRepeatedField(goals, achievement.build());
        achievementCategory
            .setField(achievementCategory.getDescriptorForType().findFieldByName("key"), 6)
            .setField(value, achievementList.build());
        stat.addRepeatedField(map, achievementCategory.build());
      }
      b.setField(statistics, stat.build());
    }
    return b.build().toByteArray();
  }

  private void claim(int quest, boolean paid) throws Exception {
    var b = request("TakeQuestRewardCsReq", Map.of());
    var ids = b.getDescriptorForType().findFieldByName("ODJIJFJCHHB");
    b.addRepeatedField(ids, quest);
    var reply = rawCall("TakeQuestRewardCsReq", b.build().toByteArray(), paid ? 0 : 1100);
    require(
        field(reply, "succ_quest_id_list").equals(paid ? List.of(quest) : List.of()),
        "Quest success list changed");
    if (paid) {
      var rewards = new TreeMap<Integer, Integer>();
      var list = (DynamicMessage) field(reply, "reward");
      for (var raw : (List<?>) field(list, "item_list")) {
        var row = (DynamicMessage) raw;
        rewards.merge(number(row, "item_id"), number(row, "num"), Integer::sum);
      }
      require(
          rewards.equals(
              switch (quest) {
                case 6072113 -> Map.of(1, 40, 2, 40000, 213, 4);
                case 6072114 -> Map.of(1, 30, 110222, 6);
                case 6072147 -> Map.of(1, 30, 2, 10000, 213, 2);
                case 6072148 -> Map.of(1, 20, 2, 10000, 232, 5);
                default -> throw new IllegalArgumentException("Undeclared fixture reward");
              }),
          "Original reward changed");
    }
    check(true, "original-quest-" + quest + "-" + (paid ? "paid" : "repeat-rejected"));
  }

  private void unfinished(int quest) throws Exception {
    var request = request("TakeQuestRewardCsReq", Map.of());
    request.addRepeatedField(request.getDescriptorForType().findFieldByName("ODJIJFJCHHB"), quest);
    var reply =
        rawCall(
            "TakeQuestRewardCsReq",
            request.build().toByteArray(),
            Retcode.QUEST_NOT_FINISH.getVal());
    check(
        field(reply, "succ_quest_id_list").equals(List.of()),
        "unreported-achievement-reward-rejected-" + quest);
  }

  private Map<Integer, Integer> stock() throws Exception {
    var result = new TreeMap<Integer, Integer>();
    var bag = call("GetBagCsReq");
    for (var raw : (List<?>) field(bag, "material_list")) {
      var row = (DynamicMessage) raw;
      int id = number(row, "tid");
      if (id == 213 || id == 232 || id == 110222)
        result.merge(id, number(row, "num"), Integer::sum);
    }
    return result;
  }

  private DynamicMessage battle(int difficulty, int avatar, int avatarType, int expectedStage)
      throws Exception {
    var reply =
        rawCall(
            "EnterLocalLegendLevelCsReq",
            entry(1, difficulty, avatar, avatarType).build().toByteArray(),
            0);
    var battle = (DynamicMessage) field(reply, "battle_info");
    check(
        number(battle, "stage_id") == expectedStage && number(battle, "battle_id") > 0,
        "source-world-one-stage-" + expectedStage);
    var actual = new ArrayList<List<Integer>>();
    for (var raw : (List<?>) field(battle, "monster_wave_list")) {
      var wave = (DynamicMessage) raw;
      require(number(wave, "battle_stage_id") == expectedStage, "Wrong wave stage");
      var monsters = new ArrayList<Integer>();
      for (var monster : (List<?>) field(wave, "monster_list"))
        monsters.add(number((DynamicMessage) monster, "monster_id"));
      actual.add(monsters);
    }
    var expected = new ArrayList<List<Integer>>();
    for (var row : stagePlans.getAsJsonArray(Integer.toString(expectedStage))) {
      var ids = new ArrayList<Integer>();
      for (var id : row.getAsJsonArray()) ids.add(id.getAsInt());
      expected.add(ids);
    }
    check(
        !actual.isEmpty() && actual.equals(expected),
        "exact-original-monster-waves-" + expectedStage);
    var categories = (List<?>) field(battle, "battle_target_info");
    require(categories.size() == 1, "Missing or extra initial target categories");
    var category = (DynamicMessage) categories.getFirst();
    require(number(category, "key") == 5, "Wrong native initial target category");
    var targetList = (DynamicMessage) field(category, "value");
    var targets = (List<?>) field(targetList, "battle_target_list");
    var expectedTargets = targetPlans.getAsJsonArray(Integer.toString(difficulty));
    require(targets.size() == expectedTargets.size(), "Incomplete initial target list");
    for (int i = 0; i < targets.size(); i++) {
      var target = (DynamicMessage) targets.get(i);
      var goal = expectedTargets.get(i).getAsJsonObject();
      require(
          number(target, "id") == goal.get("id").getAsInt()
              && number(target, "progress") == 0
              && Integer.toUnsignedLong(number(target, "total_progress"))
                  == goal.get("threshold").getAsLong(),
          "Initial target identity, order, progress or original threshold differs");
    }
    check(true, "original-zero-progress-initial-targets-" + expectedStage + "-" + avatar);
    check(battle.equals(info()), "frozen-active-battle-" + expectedStage);
    var actors = (List<?>) field(battle, "battle_avatar_list");
    require(actors.size() == 1, "Wrong activity party count");
    var actor = (DynamicMessage) actors.getFirst();
    check(
        number(actor, "id") == avatar
            && ((com.google.protobuf.Descriptors.EnumValueDescriptor) field(actor, "avatar_type"))
                .getName()
                .equals(
                    avatarType == 1 ? "AvatarType_AvatarFormalType" : "AvatarType_AvatarTrialType"),
        "actual-formal-or-trial-actor-" + avatar);
    var row = (DynamicMessage) field(actor, "sp_bar");
    check(
        number(actor, "hp") == 10000 && number(row, "cur_sp") == 5000,
        "bounded-full-health-half-energy-entry-" + avatar);
    return battle;
  }

  private void win(DynamicMessage battle, int target, int mask, int difficulty) throws Exception {
    var beforeStock = stock();
    var beforeQuests = call("GetQuestDataCsReq");
    var beforeMasks = masks();
    var invalid = result(battle, "BATTLE_END_WIN", target == 5001804 ? 5001801 : 5001804, 1);
    rawCall("PVEBattleResultCsReq", invalid, 1);
    check(battle.equals(info()), "foreign-target-preserves-active-battle-" + difficulty);
    check(
        stock().equals(beforeStock)
            && masks().equals(beforeMasks)
            && call("GetQuestDataCsReq").equals(beforeQuests),
        "foreign-target-preserves-stored-rewards-and-quests-" + difficulty);
    rawCall(
        "PVEBattleResultCsReq",
        result(battle, "BATTLE_END_WIN", target, 1, difficulty == 2 ? 30214 : 30213),
        1);
    check(battle.equals(info()), "foreign-achievement-preserves-active-battle-" + difficulty);
    check(
        stock().equals(beforeStock)
            && masks().equals(beforeMasks)
            && call("GetQuestDataCsReq").equals(beforeQuests),
        "foreign-achievement-preserves-history-rewards-and-quests-" + difficulty);
    byte[] report =
        switch (difficulty) {
          case 1 -> result(battle, "BATTLE_END_WIN", 0, 0, 0);
          case 2 -> result(battle, "BATTLE_END_WIN", target, 1, 0);
          default -> result(battle, "BATTLE_END_WIN", target, 1);
        };
    rawCall("PVEBattleResultCsReq", report, 0);
    check(
        true,
        switch (difficulty) {
          case 1 -> "source-winner-main-target-without-statistics-win-1";
          case 2 -> "source-winner-leaf-without-achievement-statistics-win-2";
          default -> "native-client-achievement-category-six-compatible-win-" + difficulty;
        });
    check(masks().get("1:" + difficulty) == mask, "actual-bound-target-mask-" + difficulty);
    check(
        Objects.equals(notifiedMasks.get("1:" + difficulty), mask),
        "saved-target-notification-" + difficulty);
    rawCall("PVEBattleResultCsReq", report, 1);
    check(true, "duplicate-result-rejected-" + difficulty);
  }

  private Map<String, Object> execute(JsonObject input) throws Exception {
    stagePlans = input.getAsJsonObject("stagePlans");
    targetPlans = input.getAsJsonObject("targetPlans");
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
    var login = call("PlayerLoginCsReq");
    call("PlayerLoginFinishCsReq");
    String mode = input.get("mode").getAsString();
    var initial = masks();
    var ordinary = call("GetAllLineupDataCsReq");
    var basic = (DynamicMessage) field(login, "basic_info");
    var before = stock();
    if (mode.equals("bootstrap")) {
      check(
          initial.values().stream().allMatch(v -> v == 0),
          "empty-query-does-not-complete-any-goal");
      rawCall("EnterLocalLegendLevelCsReq", entry(1, 1, 8001, 1).build().toByteArray(), 5);
      check(true, "actual-story-and-level-gate-rejected");
      check(
          masks().equals(initial) && stock().equals(before),
          "locked-entry-preserves-history-and-items");
    } else if (mode.equals("battle")) {
      require(initial.values().stream().allMatch(v -> v == 0), "Not a fresh activity fixture");
      rawCall(
          "EnterLocalLegendLevelCsReq",
          entry(1, 1, 8001, 3).build().toByteArray(),
          Retcode.REQ_PARA_INVALID.getVal());
      rawCall(
          "EnterLocalLegendLevelCsReq",
          entry(1, 1, 3231402, 1).build().toByteArray(),
          Retcode.REQ_PARA_INVALID.getVal());
      check(
          masks().equals(initial) && stock().equals(before),
          "native-formal-and-trial-types-cannot-borrow-each-other");
      rawCall("EnterLocalLegendLevelCsReq", entry(1, 2, 8001, 1).build().toByteArray(), 5);
      check(true, "source-hard-unlock-gate-preserved");
      var easy = battle(1, 8001, 1, 4260041);
      rawCall(
          "EnterLocalLegendLevelCsReq",
          entry(1, 1, 8001, 1).build().toByteArray(),
          Retcode.SYSTEM_BUSY.getVal());
      check(easy.equals(info()), "busy-entry-preserves-original-battle");
      win(easy, 5001804, 1, 1);
      unfinished(6072147);
      unfinished(6072148);
      unfinished(6072150);
      claim(6072113, true);
      claim(6072113, false);
      var hard = battle(2, 8001, 1, 4260011);
      win(hard, 5001801, 1, 2);
      claim(6072147, true);
      claim(6072147, false);
      claim(6072114, true);
      claim(6072114, false);
      var fearless = battle(3, 8001, 1, 426005);
      win(fearless, 5001827, 5, 3);
      claim(6072148, true);
      claim(6072148, false);
      var trial = battle(1, 3231402, 3, 4260041);
      rawCall("PVEBattleResultCsReq", result(trial, "BATTLE_END_QUIT", 0, 0), 0);
      check(true, "declared-trial-exit-does-not-create-new-progress");
      var actual = stock();
      check(
          actual.getOrDefault(213, 0) == before.getOrDefault(213, 0) + 6
              && actual.getOrDefault(232, 0) == before.getOrDefault(232, 0) + 5
              && actual.getOrDefault(110222, 0) == before.getOrDefault(110222, 0) + 6,
          "actual-original-materials-paid-once");
      check(
          call("GetAllLineupDataCsReq").equals(ordinary),
          "formal-lineups-and-vitals-remain-unchanged");
    } else if (mode.equals("read")) {
      check(
          initial.get("1:1") == 1 && initial.get("1:2") == 1 && initial.get("1:3") == 5,
          "independent-target-masks-restored");
      check(
          initial.entrySet().stream()
              .filter(e -> !e.getKey().startsWith("1:"))
              .allMatch(e -> e.getValue() == 0),
          "other-groups-not-manufactured");
      claim(6072113, false);
      claim(6072114, false);
      claim(6072147, false);
      claim(6072148, false);
      unfinished(6072150);
      check(
          before.getOrDefault(213, 0) == 6
              && before.getOrDefault(232, 0) == 5
              && before.getOrDefault(110222, 0) == 6,
          "actual-material-stocks-restored");
      require(
          number(basic, "hcoin") == input.get("expectedHcoin").getAsInt(),
          "Actual saved star-jade balance changed");
      check(true, "actual-wallet-restored");
    } else throw new IllegalArgumentException("Unknown Local Legend probe mode");
    return Map.of(
        "passed",
        true,
        "uid",
        uid,
        "mode",
        mode,
        "checks",
        checks,
        "masks",
        masks(),
        "stocks",
        stock(),
        "initialHcoin",
        number(basic, "hcoin"),
        "constructedResultReports",
        true,
        "clientGameplayVerified",
        false);
  }

  public static void main(String[] args) {
    var probe = new LoopbackLocalLegendProbe();
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
