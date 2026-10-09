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

/** Original daily-cycle graph and actual resource terminal encounters, over synthetic UDP/KCP. */
public final class LoopbackDailyCycleProbe {
  private record Frame(int command, byte[] body) {}

  private final ProtocolBridge protocol = ProtocolBridge.forVersion("4.6.0");
  private final BlockingQueue<Frame> inbox = new LinkedBlockingQueue<>();
  private final AtomicReference<Throwable> failure = new AtomicReference<>();
  private final CompletableFuture<Ukcp> connected = new CompletableFuture<>();
  private final List<String> checks = new ArrayList<>();
  private Ukcp channel;
  private boolean childNotify, parentNotify, winNotify;
  private boolean awaitCompletion;
  private int battles;
  private int observedPoints;
  private final Map<Integer, Map<String, Object>> questView = new TreeMap<>();

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
    var bytes =
        Unpooled.wrappedBuffer(
            PacketCodec.encode(
                protocol.clientCommand(name),
                new byte[0],
                request(name, fields).build().toByteArray()));
    try {
      channel.write(bytes);
    } finally {
      bytes.release();
    }
    String response = name.replace("CsReq", "ScRsp");
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
    while (System.nanoTime() < deadline) {
      if (failure.get() != null) throw new IllegalStateException("Cycle KCP failed", failure.get());
      var frame = inbox.poll(1, TimeUnit.SECONDS);
      if (frame == null) continue;
      if (awaitCompletion && frame.command() == protocol.clientCommand("GetQuestDataScRsp")) {
        var data = protocol.clientSchema().parse("GetQuestDataScRsp", frame.body());
        for (var raw : (List<?>) field(data, "quest_list")) {
          var row = (DynamicMessage) raw;
          if (number(row, "progress") == 1 && status(row) == 2) {
            if (number(row, "id") == 2100161) childNotify = true;
            if (number(row, "id") == 2100156) parentNotify = true;
          }
        }
      }
      if (frame.command() == protocol.clientCommand("SyncRogueExploreWinScNotify")) {
        var win = protocol.clientSchema().parse("SyncRogueExploreWinScNotify", frame.body());
        require(win.getUnknownFields().asMap().isEmpty(), "Unknown native win fields");
        winNotify = Boolean.TRUE.equals(field(win, "ILOFGCAFHAH"));
      }
      if (frame.command() != protocol.clientCommand(response)) continue;
      var message = protocol.clientSchema().parse(response, frame.body());
      require(
          message.getUnknownFields().asMap().isEmpty(),
          "Unknown cycle response fields: " + response);
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

  private void quests(int progress, int phase, String label) throws Exception {
    var data = call("GetQuestDataCsReq");
    var rows = new HashMap<Integer, DynamicMessage>();
    for (var raw : (List<?>) field(data, "quest_list")) {
      var row = (DynamicMessage) raw;
      require(row.getUnknownFields().asMap().isEmpty(), "Unknown cycle quest fields");
      require(rows.put(number(row, "id"), row) == null, "Duplicate quest id");
    }
    for (int id : List.of(2100156, 2100161)) {
      var row = rows.get(id);
      require(row != null, "Missing original cycle graph id " + id);
      require(
          number(row, "progress") == progress && status(row) == phase,
          "Wrong cycle progress/phase id " + id + ": " + row);
      require(
          progress == 0
              ? ((Long) field(row, "finish_time")) == 0
              : ((Long) field(row, "finish_time")) > 0,
          "Wrong cycle finish time");
      questView.put(
          id,
          Map.of(
              "progress",
              number(row, "progress"),
              "status",
              status(row),
              "finishTime",
              field(row, "finish_time")));
    }
    require(
        !rows.containsKey(2100162)
            && !rows.containsKey(2100163)
            && !rows.containsKey(2100159)
            && !rows.containsKey(2100164)
            && !rows.containsKey(2100165),
        "Unsupported cycle branches given progress");
    check(true, label);
  }

  private void points(int expected, String label) throws Exception {
    observedPoints = number(call("GetDailyActiveInfoCsReq"), "daily_active_point");
    check(observedPoints == expected, label);
  }

  private void claim(int id, int code, String label) throws Exception {
    var data = call("TakeQuestRewardCsReq", Map.of("ODJIJFJCHHB", List.of(id)), code);
    check(
        ((List<?>) field(data, "succ_quest_id_list")).equals(code == 0 ? List.of(id) : List.of()),
        label);
    if (id == 2100161 && code == 0) {
      var reward = (DynamicMessage) field(data, "reward");
      check(
          ((List<?>) field(reward, "item_list")).isEmpty(),
          "original-cycle-reward-100-has-no-items");
    }
    if (id == 2100156 && code == 0)
      check(
          ((List<?>) field((DynamicMessage) field(data, "reward"), "item_list")).isEmpty(),
          "source-virtual-daily-reward-has-no-inventory-items");
  }

  private void finish(int battle, int stage, boolean invalid) throws Exception {
    var end = protocol.clientSchema().message("PVEBattleResultCsReq").findFieldByName("end_status");
    call(
        "PVEBattleResultCsReq",
        Map.of(
            "battle_id",
            invalid ? battle + 1 : battle,
            "stage_id",
            stage,
            "end_status",
            end.getEnumType().findValueByName("BATTLE_END_WIN")),
        invalid ? 1 : 0);
  }

  private void completeUniverse() throws Exception {
    var started =
        call(
            "StartRogueCsReq",
            Map.of(
                "area_id",
                100,
                "NOEPBNMJFGL",
                List.of(emu.lunarcore.GameConstants.TRAILBLAZER_AVATAR_ID)),
            0);
    var scene = (DynamicMessage) field(started, "scene");
    check(number(scene, "entry_id") == 8112201, "actual-unmodified-source-122111-room-entered");
    var enemies = new ArrayList<Integer>();
    var groups = new HashSet<Integer>();
    for (var raw : (List<?>) field(scene, "entity_group_list"))
      for (var value : (List<?>) field((DynamicMessage) raw, "entity_list")) {
        var entity = (DynamicMessage) value;
        if (entity.hasField(entity.getDescriptorForType().findFieldByName("npc_monster"))) {
          enemies.add(number(entity, "entity_id"));
          groups.add(number(entity, "group_id"));
        }
      }
    check(
        enemies.size() == 3
            && new HashSet<>(enemies).size() == 3
            && groups.equals(Set.of(70, 71, 72)),
        "three-actual-original-room-monster-groups-loaded");
    for (int i = 0; i < enemies.size(); i++) {
      var attack =
          call(
              "SceneCastSkillCsReq",
              Map.of(
                  "cast_entity_id",
                  number(scene, "leader_entity_id"),
                  "attacked_by_entity_id",
                  number(scene, "leader_entity_id"),
                  "hit_target_entity_id_list",
                  List.of(enemies.get(i))),
              0);
      var battle = (DynamicMessage) field(attack, "battle_info");
      int id = number(battle, "battle_id"), stage = number(battle, "stage_id");
      check(
          id > 0 && !((List<?>) field(battle, "monster_wave_list")).isEmpty(),
          "actual-resource-battle-" + i + "-started");
      var current = (DynamicMessage) field(call("GetCurBattleInfoCsReq"), "battle_info");
      check(
          number(current, "battle_id") == id && number(current, "stage_id") == stage,
          "actual-battle-query-" + i + "-same-identity");
      if (i == 0) {
        finish(id, stage, true);
        check(true, "wrong-battle-id-rejected");
        quests(0, 1, "wrong-report-keeps-both-original-objectives-pending");
      }
      awaitCompletion = true;
      finish(id, stage, false);
      battles++;
      if (i == 2)
        check(
            childNotify && parentNotify && winNotify,
            "both-quest-completions-and-win-notified-before-final-response");
      var end =
          protocol.clientSchema().message("PVEBattleResultCsReq").findFieldByName("end_status");
      call(
          "PVEBattleResultCsReq",
          Map.of(
              "battle_id",
              id,
              "stage_id",
              stage,
              "end_status",
              end.getEnumType().findValueByName("BATTLE_END_WIN")),
          1);
      check(true, "actual-victory-" + i + "-duplicate-rejected");
      quests(
          i == 2 ? 1 : 0,
          i == 2 ? 2 : 1,
          i == 2
              ? "terminal-source-win-completes-both-original-quests"
              : "nonterminal-victory-" + i + "-keeps-cycle-pending");
    }
    check(
        childNotify && parentNotify && winNotify,
        "native-child-parent-and-universe-win-notifications-before-settlement-response");
    var current =
        (DynamicMessage)
            field(
                (DynamicMessage) field(call("GetRogueInfoCsReq"), "rogue_game_info"),
                "CFDIKLBINHK");
    check(
        field(current, "status").toString().equals("RogueStatus_Finish"),
        "actual-source-map-1-terminal-status-finished");
    call("LeaveRogueCsReq");
    points(0, "unclaimed-original-daily-has-no-activity-payout");
  }

  private Map<String, Object> execute(JsonObject input, java.io.BufferedReader control)
      throws Exception {
    var token =
        call(
            "PlayerGetTokenCsReq",
            Map.of(
                "account_uid",
                input.get("accountUid").getAsString(),
                "token",
                input.get("comboToken").getAsString(),
                "platform",
                1),
            0);
    int uid = number(token, "uid");
    var login = call("PlayerLoginCsReq");
    check(
        number((DynamicMessage) field(login, "basic_info"), "level") == 10,
        "isolated-start-level-meets-original-daily-minimum");
    call("PlayerLoginFinishCsReq");
    System.out.println("HKRPG_CYCLE_CLOCK_FIXTURE_READY:" + uid);
    System.out.flush();
    require("resume".equals(control.readLine()), "Cycle controlled clock rendezvous missing");
    String phase = input.get("phase").getAsString();
    if (phase.equals("pending") || phase.equals("complete") || phase.equals("nextday")) {
      quests(0, 1, phase + "-original-daily-and-cycle-start-pending");
      points(0, phase + "-activity-points-start-zero");
      call("FinishQuestCsReq", Map.of("quest_id", 2100161), Retcode.QUEST_STATUS_ERROR.getVal());
      call("FinishQuestCsReq", Map.of("quest_id", 2100156), Retcode.QUEST_STATUS_ERROR.getVal());
      check(true, phase + "-client-finish-reports-rejected");
      claim(2100161, Retcode.QUEST_NOT_FINISH.getVal(), phase + "-unfinished-cycle-claim-rejected");
      if (phase.equals("complete")) completeUniverse();
      if (phase.equals("nextday")) {
        for (int i = 0; i < 12; i++) call("GetRogueInfoCsReq");
        quests(0, 1, "prior-terminal-run-queries-do-not-complete-new-day");
      }
    } else if (phase.equals("unclaimed") || phase.equals("claim")) {
      quests(1, 2, "reconnect-and-H2-restore-original-completed-unclaimed-graph");
      points(0, "restored-unclaimed-daily-still-zero-points");
      if (phase.equals("claim")) {
        claim(2100161, 0, "original-empty-cycle-reward-closed-once");
        claim(2100156, 0, "original-linked-daily-reward-delivered-once");
        quests(1, 3, "both-original-quest-claims-closed");
        points(500, "original-five-hundred-activity-points");
      }
    } else if (phase.equals("claimed")) {
      quests(1, 3, "saved-closed-daily-and-cycle-after-reconnect-or-H2");
      points(500, "saved-exact-five-hundred-activity-points");
    } else throw new IllegalArgumentException("Unknown cycle phase " + phase);
    if (phase.equals("claim") || phase.equals("claimed")) {
      claim(
          2100161, Retcode.QUEST_REWARD_ALREADY_TAKEN.getVal(), "duplicate-cycle-reward-rejected");
      claim(
          2100156,
          Retcode.QUEST_REWARD_ALREADY_TAKEN.getVal(),
          "duplicate-linked-daily-reward-rejected");
      points(500, "duplicate-claims-do-not-add-activity-points");
    }
    check(
        ((List<?>) field(call("GetBagCsReq"), "material_list"))
            .stream().map(row -> (DynamicMessage) row).noneMatch(row -> number(row, "tid") == 23),
        "virtual-activity-points-absent-from-material-inventory");
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
        battles,
        "completionNotifyBeforeResponse",
        childNotify && parentNotify && winNotify,
        "clientGameplayVerified",
        false,
        "dailyPoints",
        observedPoints,
        "questView",
        questView);
  }

  public static void main(String[] args) {
    var probe = new LoopbackDailyCycleProbe();
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
                  require(frame != null, "Truncated cycle frame");
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
                  null, new IllegalStateException("Cycle probe disconnected"));
            }
          });
      connection =
          client.connect(new InetSocketAddress("127.0.0.1", Integer.parseInt(args[0])), config);
      probe.channel = probe.connected.get(10, TimeUnit.SECONDS);
      System.out.write(
          (new Gson().toJson(probe.execute(credentials, control)) + "\n")
              .getBytes(StandardCharsets.UTF_8));
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
