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

/** Full original daily 3011607 files, event battle, final talk and one-time rewards over KCP. */
public final class LoopbackDailyStageEventProbe {
  private record Frame(int command, byte[] body) {}

  private final ProtocolBridge protocol = ProtocolBridge.forVersion("4.6.0");
  private final BlockingQueue<Frame> inbox = new LinkedBlockingQueue<>();
  private final AtomicReference<Throwable> failure = new AtomicReference<>();
  private final CompletableFuture<Ukcp> connected = new CompletableFuture<>();
  private final List<String> checks = new ArrayList<>();
  private Ukcp channel;
  private boolean mainRewardNotify;
  private int observedWallet = -1;
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
      if (frame.command() == protocol.clientCommand("MissionRewardScNotify")) {
        var notify = protocol.clientSchema().parse("MissionRewardScNotify", frame.body());
        require(notify.getUnknownFields().asMap().isEmpty(), "Unknown main reward fields");
        if (number(notify, "main_mission_id") != 3011607) continue;
        var reward = (DynamicMessage) field(notify, "reward");
        var items = (List<?>) field(reward, "item_list");
        require(items.size() == 1, "Wrong daily main reward item count");
        var item = (DynamicMessage) items.getFirst();
        require(
            number(item, "item_id") == 2 && number(item, "num") == 5000,
            "Wrong original daily credit notification");
        mainRewardNotify = true;
      }
      if (frame.command() == protocol.clientCommand("PlayerSyncScNotify")) {
        var notify = protocol.clientSchema().parse("PlayerSyncScNotify", frame.body());
        var basic = notify.getDescriptorForType().findFieldByName("basic_info");
        if (basic != null && notify.hasField(basic))
          observedWallet = number((DynamicMessage) notify.getField(basic), "scoin");
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

  private void entry() throws Exception {
    call("EnterSceneCsReq", Map.of("entry_id", 2012201), 0);
    var scene = (DynamicMessage) field(call("GetCurSceneInfoCsReq"), "scene");
    check(
        number(scene, "entry_id") == 2012201
            && number(scene, "plane_id") == 20122
            && number(scene, "floor_id") == 20122001
            && number(scene, "leader_entity_id") > 0,
        "real-source-daily-stage-floor");
  }

  private void progress(int step, String label) throws Exception {
    var data = call("GetMissionDataCsReq");
    var mains = new ArrayList<DynamicMessage>();
    for (var raw : (List<?>) field(data, "main_mission_list")) {
      var row = (DynamicMessage) raw;
      if (number(row, "id") == 3011607) mains.add(row);
    }
    require(mains.size() == 1, "Source assignment absent or duplicated");
    require(
        ((EnumValueDescriptor) field(mains.getFirst(), "status")).getNumber()
            == (step == 2 ? 2 : 1),
        "Wrong source main phase");
    require(
        ((List<?>) field(data, "finished_main_mission_id_list")).contains(3011607) == (step == 2),
        "Wrong source main history");
    var subs = new HashMap<Integer, DynamicMessage>();
    for (var raw : (List<?>) field(data, "mission_list")) {
      var row = (DynamicMessage) raw;
      int id = number(row, "id");
      if (id == 301160701 || id == 301160702)
        require(subs.put(id, row) == null, "Duplicated source sub");
    }
    require(subs.containsKey(301160701), "Original StageWin goal missing");
    var stage = subs.get(301160701);
    require(
        number(stage, "progress") == (step > 0 ? 1 : 0)
            && ((EnumValueDescriptor) field(stage, "status")).getNumber() == (step > 0 ? 2 : 1),
        "Wrong accepted source StageWin progress");
    if (step > 0) {
      require(subs.size() == 2, "Original final talk not installed after victory");
      var tail = subs.get(301160702);
      require(
          number(tail, "progress") == (step == 2 ? 1 : 0)
              && ((EnumValueDescriptor) field(tail, "status")).getNumber() == (step == 2 ? 2 : 1),
          "Wrong source final talk phase");
    }
    check(true, label);
  }

  private void battle(JsonObject input) throws Exception {
    var response = call("SceneEnterStageCsReq", Map.of("event_id", 20134222), 0);
    var battle = (DynamicMessage) field(response, "battle_info");
    int id = number(battle, "battle_id"), stage = number(battle, "stage_id");
    check(
        id > 0 && stage == 201342220,
        "literal-source-script-event-selects-original-world-zero-stage");
    var actual = new ArrayList<List<Integer>>();
    for (var raw : (List<?>) field(battle, "monster_wave_list")) {
      var wave = (DynamicMessage) raw;
      require(number(wave, "battle_stage_id") == stage, "Wrong source wave stage");
      var monsters = new ArrayList<Integer>();
      for (var monster : (List<?>) field(wave, "monster_list"))
        monsters.add(number((DynamicMessage) monster, "monster_id"));
      actual.add(monsters);
    }
    var expected = new ArrayList<List<Integer>>();
    for (var raw : input.getAsJsonArray("waves")) {
      var monsters = new ArrayList<Integer>();
      for (var monster : raw.getAsJsonArray()) monsters.add(monster.getAsInt());
      expected.add(monsters);
    }
    check(
        actual.equals(expected) && !actual.isEmpty(),
        "unmodified-source-stage-monster-waves-present");
    check(
        !((List<?>) field(battle, "battle_avatar_list")).isEmpty(),
        "actual-formal-player-lineup-present");
    check(
        battle.equals(field(call("GetCurBattleInfoCsReq"), "battle_info")),
        "accepted-native-battle-view-retained");
    progress(0, "starting-source-event-does-not-complete-stage-objective");
    call("SceneEnterStageCsReq", Map.of("event_id", 20134222), 5);
    check(
        battle.equals(field(call("GetCurBattleInfoCsReq"), "battle_info")),
        "duplicate-event-preserves-accepted-battle");
    var win =
        protocol
            .clientSchema()
            .message("PVEBattleResultCsReq")
            .findFieldByName("end_status")
            .getEnumType()
            .findValueByName("BATTLE_END_WIN");
    call(
        "PVEBattleResultCsReq",
        Map.of("battle_id", id + 1, "stage_id", stage, "end_status", win),
        1);
    progress(0, "wrong-battle-result-does-not-complete-source-stage");
    call("PVEBattleResultCsReq", Map.of("battle_id", id, "stage_id", stage, "end_status", win), 0);
    check(true, "actual-source-event-battle-win-accepted");
    progress(1, "confirmed-win-completes-source-stage-and-unlocks-original-tail");
    quest(1, "stage-victory-alone-does-not-finish-daily-activity");
    points(0, "stage-victory-does-not-auto-claim-activity");
    call("PVEBattleResultCsReq", Map.of("battle_id", id, "stage_id", stage, "end_status", win), 1);
    progress(1, "duplicate-source-battle-result-does-not-change-goal");
    var empty = call("GetCurBattleInfoCsReq", Map.of(), 1);
    check(
        !empty.hasField(empty.getDescriptorForType().findFieldByName("battle_info")),
        "settled-source-battle-cleared");
  }

  private void quest(int status, String label) throws Exception {
    var data = call("GetQuestDataCsReq");
    var matches = new ArrayList<DynamicMessage>();
    for (var raw : (List<?>) field(data, "quest_list")) {
      var row = (DynamicMessage) raw;
      if (number(row, "id") == 2100003) matches.add(row);
    }
    require(matches.size() == 1, "Missing or duplicate original daily active target");
    var row = matches.getFirst();
    check(
        ((EnumValueDescriptor) field(row, "status")).getNumber() == status
            && number(row, "progress") == (status == 1 ? 0 : 1)
            && ((Long) field(row, "finish_time") > 0) == (status != 1),
        label);
  }

  private void points(int expected, String label) throws Exception {
    var data = call("GetDailyActiveInfoCsReq");
    check(number(data, "daily_active_point") == expected, label);
  }

  private void claim(int code, String label) throws Exception {
    var data = call("TakeQuestRewardCsReq", Map.of("ODJIJFJCHHB", List.of(2100003)), code);
    check(
        ((List<?>) field(data, "succ_quest_id_list"))
            .equals(code == 0 ? List.of(2100003) : List.of()),
        label);
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
    var login = call("PlayerLoginCsReq");
    call("PlayerLoginFinishCsReq");
    String phase = input.get("phase").getAsString();
    boolean first = phase.equals("first"), claimed = phase.equals("claimed");
    require(
        Set.of("first", "unclaimed", "claim", "claimed").contains(phase),
        "Unknown source daily stage phase");
    int wallet = number((DynamicMessage) field(login, "basic_info"), "scoin");
    check(wallet == (first ? 0 : 5000), "source-main-wallet-restored-" + phase);
    progress(
        first ? 0 : 2, "full-source-script-assignment-restored-without-client-accept-" + phase);
    quest(first ? 1 : claimed ? 3 : 2, "original-source-activity-restored-" + phase);
    points(claimed ? 200 : 0, "original-source-daily-points-restored-" + phase);
    if (first) {
      entry();
      talk(
          301160702,
          "Mission_301160702",
          Retcode.MISSION_NOT_DOING.getVal(),
          "original-tail-cannot-skip-source-stage");
      talk(
          301160701,
          "Talk_301160701",
          Retcode.MISSION_FINISH_WAY_NOT_MATCH.getVal(),
          "client-string-does-not-report-stage-win");
      progress(0, "rejected-talks-do-not-complete-source-stage");
      claim(Retcode.QUEST_NOT_FINISH.getVal(), "unfinished-source-activity-cannot-be-claimed");
      battle(input);
      talk(
          301160702,
          "wrong-final-source-talk",
          Retcode.MISSION_FINISH_WAY_NOT_MATCH.getVal(),
          "wrong-original-tail-string-rejected");
      awaitedQuest = 2100003;
      questCompletionNotify = false;
      mainRewardNotify = false;
      talk(301160702, "Mission_301160702", 0, "original-final-talk-completes-source-daily-main");
      check(questCompletionNotify, "original-activity-notification-before-tail-response");
      check(
          mainRewardNotify && observedWallet == 5000,
          "original-5000-credit-notification-before-tail-response");
      awaitedQuest = 0;
      progress(2, "both-source-goals-and-main-complete");
      quest(2, "original-tail-finishes-daily-activity");
      points(0, "source-completion-does-not-auto-claim-200-points");
      talk(
          301160702,
          "Mission_301160702",
          Retcode.MISSION_NOT_DOING.getVal(),
          "original-tail-replay-rejected");
    } else if (phase.equals("claim")) {
      claim(0, "actual-source-activity-200-points-claimed");
      points(200, "source-activity-pays-exact-original-200-points");
      quest(3, "claimed-source-activity-closed");
      claim(
          Retcode.QUEST_REWARD_ALREADY_TAKEN.getVal(), "duplicate-source-activity-claim-rejected");
      points(200, "duplicate-source-activity-keeps-200-points");
    } else if (claimed) {
      claim(
          Retcode.QUEST_REWARD_ALREADY_TAKEN.getVal(), "restored-source-activity-cannot-pay-again");
      points(200, "restored-source-activity-keeps-200-points");
    }
    if (!first) {
      talk(
          301160702,
          "Mission_301160702",
          Retcode.MISSION_NOT_DOING.getVal(),
          "saved-source-tail-cannot-pay-again-" + phase);
      call(
          "AcceptMainMissionCsReq",
          Map.of("main_mission_id", 3011607),
          Retcode.MISSION_ALREADY_DONE.getVal());
      check(true, "saved-source-daily-cannot-restart-today-" + phase);
    }
    var output = new LinkedHashMap<String, Object>();
    output.put("passed", true);
    output.put("uid", uid);
    output.put("phase", phase);
    output.put("checks", checks);
    output.put("loginWallet", wallet);
    output.put("immediateQuestCompletionNotifyVerified", questCompletionNotify);
    output.put("mainRewardNotifyBeforeResponseVerified", mainRewardNotify);
    output.put("fullOriginalMissionFolderLoaded", true);
    output.put("clientGameplayVerified", false);
    return output;
  }

  public static void main(String[] args) {
    var probe = new LoopbackDailyStageEventProbe();
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
