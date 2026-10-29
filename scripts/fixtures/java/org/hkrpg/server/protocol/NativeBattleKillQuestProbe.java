package org.hkrpg.server.protocol;

import com.google.gson.*;
import com.google.protobuf.*;
import emu.lunarcore.server.packet.*;
import io.netty.buffer.*;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import kcp.highway.*;

/** External-only source encounters and accepted native death reports; no combat input replay. */
public final class NativeBattleKillQuestProbe {
  private record Frame(int command, byte[] body) {}

  private final ProtocolBridge protocol = ProtocolBridge.forVersion("4.6.0");
  private final BlockingQueue<Frame> inbox = new LinkedBlockingQueue<>();
  private final AtomicReference<Throwable> failure = new AtomicReference<>();
  private final CompletableFuture<Ukcp> connected = new CompletableFuture<>();
  private Ukcp channel;
  private int target,
      current,
      queries,
      progressNotifications,
      removalNotifications,
      jadeNotifications,
      observedJades,
      explorations,
      encounters,
      actualDeaths,
      staleResults,
      expectedEntity;
  private boolean awaitKills, awaitClaim;
  private boolean verifyStoryBlessing;
  private boolean selectedBeforeRestart;
  private String mode;
  private int pendingBatch, pendingBuff, blessingNotifications, blessingSelections;
  private int selectedBatch, selectedBuff;
  private Set<Integer> claimed = Set.of();
  private final Map<Integer, Integer> questProgress = new TreeMap<>(),
      questPhases = new TreeMap<>();
  private final List<Map<String, Integer>> operations = new ArrayList<>();
  private final List<String> checks = new ArrayList<>();

  private static Object field(DynamicMessage m, String name) {
    return m.getField(m.getDescriptorForType().findFieldByName(name));
  }

  private static int number(DynamicMessage m, String name) {
    return (Integer) field(m, name);
  }

  private static void require(boolean ok, String reason) {
    if (!ok) throw new IllegalStateException(reason);
  }

  private static int phase(DynamicMessage m) {
    return ((com.google.protobuf.Descriptors.EnumValueDescriptor) field(m, "status")).getNumber();
  }

  private void validateQuests(DynamicMessage m, int count, Set<Integer> closed) {
    require(number(m, "retcode") == 0, "Kill quest query rejected");
    var rows = new HashMap<Integer, DynamicMessage>();
    for (var raw : (List<?>) field(m, "quest_list")) {
      var row = (DynamicMessage) raw;
      int id = number(row, "id");
      if (id == 4090301 || id == 4090303)
        require(rows.put(id, row) == null, "Duplicate original kill goal");
    }
    require(rows.size() == 2, "Original kill goals missing");
    for (int id : List.of(4090301, 4090303)) {
      int goal = id == 4090301 ? 15 : 1000,
          status = closed.contains(id) ? 3 : count >= goal ? 2 : 1;
      var row = rows.get(id);
      require(
          number(row, "progress") == Math.min(goal, count)
              && phase(row) == status
              && ((Long) field(row, "finish_time") > 0) == (status != 1),
          "Kill progress/phase/time differs from accepted deaths");
      questProgress.put(id, number(row, "progress"));
      questPhases.put(id, phase(row));
    }
  }

  private void observe(Frame frame) throws Exception {
    if (verifyStoryBlessing && awaitKills
        && frame.command() == protocol.clientCommand("SyncRogueCommonPendingActionScNotify")) {
      var notify = protocol.clientSchema().parse("SyncRogueCommonPendingActionScNotify", frame.body());
      readBlessingAction((DynamicMessage) field(notify, "action"));
      blessingNotifications++;
    }
    if (awaitKills && frame.command() == protocol.clientCommand("GetQuestDataScRsp")) {
      validateQuests(
          protocol.clientSchema().parse("GetQuestDataScRsp", frame.body()), current, claimed);
      progressNotifications++;
    }
    if (awaitKills && frame.command() == protocol.clientCommand("SceneGroupRefreshScNotify")) {
      var m = protocol.clientSchema().parse("SceneGroupRefreshScNotify", frame.body());
      require(number(m, "floor_id") == 80001001, "Wrong defeated source floor");
      for (var raw : (List<?>) field(m, "group_refresh_list")) {
        var g = (DynamicMessage) raw;
        for (var e : (List<?>) field(g, "refresh_entity")) {
          var info = (DynamicMessage) e;
          if (info.hasField(info.getDescriptorForType().findFieldByName("delete_entity"))) {
            require(
                number(g, "group_id") == 3 && number(info, "delete_entity") == expectedEntity,
                "Wrong removed source actor");
            removalNotifications++;
          }
        }
      }
    }
    if (awaitClaim && frame.command() == protocol.clientCommand("PlayerSyncScNotify")) {
      var m = protocol.clientSchema().parse("PlayerSyncScNotify", frame.body());
      if (m.hasField(m.getDescriptorForType().findFieldByName("basic_info"))) {
        observedJades = number((DynamicMessage) field(m, "basic_info"), "hcoin");
        require(
            observedJades == 5 || target == 1000 && observedJades == 15,
            "Wrong actual kill goal jades");
        jadeNotifications++;
      }
    }
  }

  private void readBlessingAction(DynamicMessage action) {
    int batch = number(action, "queue_position");
    var detail = (DynamicMessage) field(action, "detail");
    var info = (DynamicMessage) field(detail, "PCHGEGFBKLF");
    var choices = (List<?>) field(info, "LEOFNINDJDB");
    require(batch > 0 && !choices.isEmpty(), "Missing saved first-story blessing offer");
    int buff = number((DynamicMessage) choices.getFirst(), "buff_id");
    require(buff > 0, "Invalid first-story blessing choice");
    pendingBatch = batch; pendingBuff = buff;
  }

  private void selectStoryBlessing() throws Exception {
    require(pendingBatch > 0 && pendingBuff > 0, "No first-story blessing to select");
    int batch = pendingBatch, buff = pendingBuff;
    var choice = DynamicMessage.newBuilder(protocol.clientSchema().message("BPLNKHGAILK"));
    choice.setField(choice.getDescriptorForType().findFieldByName("BAOFIGFFMLN"), buff);
    var request = DynamicMessage.newBuilder(protocol.clientSchema().message("OFEDNNJIJFL"));
    request.setField(request.getDescriptorForType().findFieldByName("MPLGEEGOAMC"), batch);
    request.setField(request.getDescriptorForType().findFieldByName("DLKIKBIDJDB"), choice.build());
    var reply = protocol.clientSchema().parse("HandleRogueCommonPendingActionScRsp",
        raw(5676, request.build().toByteArray(), protocol.clientCommand("HandleRogueCommonPendingActionScRsp")).body());
    require(number(reply, "retcode") == 0 && number(reply, "queue_position") == batch,
        "First-story blessing selection was rejected");
    var duplicate = protocol.clientSchema().parse("HandleRogueCommonPendingActionScRsp",
        raw(5676, request.build().toByteArray(), protocol.clientCommand("HandleRogueCommonPendingActionScRsp")).body());
    require(number(duplicate, "retcode") != 0, "Repeated first-story blessing selection succeeded");
    var scene = (DynamicMessage) field(call("GetCurSceneInfoCsReq"), "scene");
    requireTriggerOpen(scene);
    blessingSelections++;
    selectedBatch = batch; selectedBuff = buff;
    pendingBatch = 0; pendingBuff = 0;
  }

  private void requireTriggerOpen(DynamicMessage scene) {
    boolean opened = false;
    for (var raw : (List<?>) field(scene, "entity_group_list"))
      for (var row : (List<?>) field((DynamicMessage) raw, "entity_list")) {
        var entity = (DynamicMessage) row;
        if (number(entity, "group_id") == 3 && number(entity, "inst_id") == 300001
            && entity.hasField(entity.getDescriptorForType().findFieldByName("prop"))) {
          var prop = (DynamicMessage) field(entity, "prop");
          opened = number(prop, "prop_id") == 100 && number(prop, "prop_state") == 1;
        }
      }
    require(opened, "First-story trigger did not open after selection");
  }

  private int actor(DynamicMessage scene, boolean consumed) {
    require(
        number(scene, "entry_id") == 8000101
            && number(scene, "plane_id") == 80001
            && number(scene, "floor_id") == 80001001,
        "Wrong source combat room");
    int result = 0, others = 0;
    for (var raw : (List<?>) field(scene, "entity_group_list"))
      for (var value : (List<?>) field((DynamicMessage) raw, "entity_list")) {
        var e = (DynamicMessage) value;
        if (!e.hasField(e.getDescriptorForType().findFieldByName("npc_monster"))) continue;
        if (number(e, "group_id") == 3 && number(e, "inst_id") == 200001) {
          var npc = (DynamicMessage) field(e, "npc_monster");
          require(
              number(npc, "monster_id") == 8011020 && number(npc, "event_id") == 80010011,
              "Source encounter replaced");
          require(result == 0, "Duplicate source actor");
          result = number(e, "entity_id");
        } else others++;
      }
    require(
        (result == 0) == consumed && others > 0,
        "Partial-room source actor consumption/remaining monsters changed");
    return result;
  }

  private void encounter(DynamicMessage scene, int deaths) throws Exception {
    int entity = actor(scene, false), leader = number(scene, "leader_entity_id");
    require(leader > 0, "Missing caster");
    var cast =
        call(
            "SceneCastSkillCsReq",
            Map.of(
                "cast_entity_id",
                leader,
                "attacked_by_entity_id",
                leader,
                "skill_index",
                0,
                "hit_target_entity_id_list",
                List.of(entity)));
    require(
        cast.hasField(cast.getDescriptorForType().findFieldByName("battle_info")),
        "Source cast did not start battle");
    var battle = (DynamicMessage) field(cast, "battle_info");
    int id = number(battle, "battle_id");
    require(id > 0 && number(battle, "stage_id") == 80010011, "Wrong original battle stage");
    var waves = (List<?>) field(battle, "monster_wave_list");
    require(waves.size() == 1, "Original wave count changed");
    var wave = (DynamicMessage) waves.getFirst();
    var mobs = (List<?>) field(wave, "monster_list");
    require(
        number(wave, "battle_stage_id") == 80010011
            && mobs.size() == 2
            && number((DynamicMessage) mobs.get(0), "monster_id") == 8011020
            && number((DynamicMessage) mobs.get(1), "monster_id") == 8011010,
        "Original combat roster changed");
    var request =
        DynamicMessage.newBuilder(protocol.clientSchema().message("PVEBattleResultCsReq"));
    request.setField(request.getDescriptorForType().findFieldByName("battle_id"), id);
    request.setField(request.getDescriptorForType().findFieldByName("stage_id"), 80010011);
    var end = request.getDescriptorForType().findFieldByName("end_status");
    request.setField(end, end.getEnumType().findValueByName("BATTLE_END_WIN"));
    var stats = DynamicMessage.newBuilder(protocol.clientSchema().message("BattleStatistics"));
    for (int i = 0; i < deaths; i++) {
      var row = DynamicMessage.newBuilder(protocol.clientSchema().message("MonsterBattleInfo"));
      for (var e :
          Map.of(
                  "entity_id",
                  5001 + i,
                  "monster_id",
                  i == 0 ? 8011020 : 8011010,
                  "stage_id",
                  80010011)
              .entrySet())
        row.setField(row.getDescriptorForType().findFieldByName(e.getKey()), e.getValue());
      var death = row.getDescriptorForType().findFieldByName("death_source");
      row.setField(death, death.getEnumType().findValueByNumber(i + 1));
      stats.addRepeatedField(
          stats.getDescriptorForType().findFieldByName("monster_list"), row.build());
    }
    request.setField(request.getDescriptorForType().findFieldByName("stt"), stats.build());
    int before = progressNotifications, removed = removalNotifications;
    current += deaths;
    expectedEntity = entity;
    awaitKills = true;
    var reply =
        protocol
            .clientSchema()
            .parse(
                "PVEBattleResultScRsp",
                raw(
                        protocol.clientCommand("PVEBattleResultCsReq"),
                        request.build().toByteArray(),
                        protocol.clientCommand("PVEBattleResultScRsp"))
                    .body());
    awaitKills = false;
    require(
        number(reply, "retcode") == 0
            && number(reply, "battle_id") == id
            && number(reply, "stage_id") == 80010011,
        "Source settlement rejected");
    require(
        progressNotifications == before + 1 && removalNotifications == removed + 1,
        "Kill progress/removal not observed before settlement success");
    if (verifyStoryBlessing) {
      require(pendingBatch > 0 && pendingBuff > 0, "No first-story blessing notification after victory");
      if (!mode.equals("checkpoint") || selectedBeforeRestart) selectStoryBlessing();
    }
    actualDeaths += deaths;
    encounters++;
    operations.add(Map.of("battle", id, "stage", 80010011, "deaths", deaths, "count", current));
    var duplicate =
        protocol
            .clientSchema()
            .parse(
                "PVEBattleResultScRsp",
                raw(
                        protocol.clientCommand("PVEBattleResultCsReq"),
                        request.build().toByteArray(),
                        protocol.clientCommand("PVEBattleResultScRsp"))
                    .body());
    require(number(duplicate, "retcode") != 0, "Repeated result accepted");
    staleResults++;
    actor((DynamicMessage) field(call("GetCurSceneInfoCsReq"), "scene"), true);
  }

  private DynamicMessage start() throws Exception {
    explorations++;
    return (DynamicMessage)
        field(
            call(
                "StartRogueCsReq",
                Map.of(
                    "area_id",
                    100,
                    "NOEPBNMJFGL",
                    List.of(emu.lunarcore.GameConstants.TRAILBLAZER_AVATAR_ID))),
            "scene");
  }

  private Map<String, Object> execute(JsonObject credentials, java.io.BufferedReader unused)
      throws Exception {
    target = credentials.get("target").getAsInt();
    require(target == 15 || target == 1000, "Unsupported source kill goal");
    mode = credentials.get("mode").getAsString();
    verifyStoryBlessing = credentials.has("verifyStoryBlessing") && credentials.get("verifyStoryBlessing").getAsBoolean();
    selectedBeforeRestart = credentials.has("selectedBeforeRestart") && credentials.get("selectedBeforeRestart").getAsBoolean();
    var token =
        call(
            "PlayerGetTokenCsReq",
            Map.of(
                "account_uid",
                credentials.get("accountUid").getAsString(),
                "token",
                credentials.get("comboToken").getAsString(),
                "platform",
                1));
    int uid = number(token, "uid");
    observedJades = number((DynamicMessage) field(call("PlayerLoginCsReq"), "basic_info"), "hcoin");
    require(
        observedJades == (mode.equals("resume") ? (target == 15 ? 5 : 15) : 0),
        "Restored source goal wallet differs");
    call("PlayerLoginFinishCsReq");
    if (mode.equals("checkpoint")) {
      quests(0);
      encounter(start(), 2);
      quests(2);
      call("LeaveRogueCsReq");
      checks.add("source-encounter-and-two-reported-deaths-saved-with-room-still-partial");
    } else {
      var ids = target == 15 ? List.of(4090301) : List.of(4090301, 4090303);
      if (mode.equals("resume")) {
        current = target;
        claimed = Set.copyOf(ids);
        quests(target);
      } else {
        require(mode.equals("complete"), "Unknown source test mode");
        current = 2;
        quests(2);
        var resumed = call("EnterRogueCsReq", Map.of("area_id", 100));
        actor((DynamicMessage) field(resumed, "scene"), true);
        if (verifyStoryBlessing) {
          var game = (DynamicMessage) field(resumed, "rogue_game_info");
          var currentRun = (DynamicMessage) field(game, "CFDIKLBINHK");
          if (selectedBeforeRestart) {
            requireTriggerOpen((DynamicMessage) field(resumed, "scene"));
            var buffs = (DynamicMessage) field(currentRun, "HABJHGPFGEB");
            int expected = credentials.get("expectedBuff").getAsInt();
            require(((List<?>) field(buffs, "CHCMEKAFKAF")).stream()
                    .map(DynamicMessage.class::cast).anyMatch(b -> number(b, "buff_id") == expected),
                "Selected blessing did not survive H2 restart");
            checks.add("selected-first-story-blessing-and-open-trigger-restored");
          } else {
            readBlessingAction((DynamicMessage) field(currentRun, "FMIGOKIDKJL"));
            require(pendingBatch == credentials.get("expectedBatch").getAsInt()
                    && pendingBuff == credentials.get("expectedBuff").getAsInt(),
                "Pending blessing changed across H2 restart");
            selectStoryBlessing();
            checks.add("pending-first-story-blessing-restored-and-selected-once");
          }
        }
        checks.add(
            "normal-h2-restart-restores-defeated-original-actor-and-remaining-original-monsters");
        call("LeaveRogueCsReq");
        call("QuitRogueCsReq", Map.of("area_id", 100));
        while (current < target) {
          encounter(start(), Math.min(2, target - current));
          call("LeaveRogueCsReq");
          call("QuitRogueCsReq", Map.of("area_id", 100));
        }
        quests(target);
        awaitClaim = true;
        var reward = call("TakeQuestRewardCsReq", Map.of("ODJIJFJCHHB", ids));
        awaitClaim = false;
        require(
            field(reward, "succ_quest_id_list").equals(ids),
            "Wrong original kill reward identities");
        int sum = 0;
        for (var raw : (List<?>) field((DynamicMessage) field(reward, "reward"), "item_list")) {
          var r = (DynamicMessage) raw;
          require(number(r, "item_id") == 1, "Wrong kill reward item");
          sum += number(r, "num");
        }
        require(
            sum == (target == 15 ? 5 : 15)
                && observedJades == sum
                && jadeNotifications == ids.size(),
            "Original jades not paid/notified before success");
        claimed = Set.copyOf(ids);
      }
      var repeated =
          DynamicMessage.newBuilder(protocol.clientSchema().message("TakeQuestRewardCsReq"));
      repeated.setField(repeated.getDescriptorForType().findFieldByName("ODJIJFJCHHB"), ids);
      var m =
          protocol
              .clientSchema()
              .parse(
                  "TakeQuestRewardScRsp",
                  raw(
                          protocol.clientCommand("TakeQuestRewardCsReq"),
                          repeated.build().toByteArray(),
                          protocol.clientCommand("TakeQuestRewardScRsp"))
                      .body());
      require(
          number(m, "retcode") != 0
              && ((List<?>) field(m, "succ_quest_id_list")).isEmpty()
              && ((List<?>) field((DynamicMessage) field(m, "reward"), "item_list")).isEmpty(),
          "Original kill rewards paid twice");
      quests(target);
      checks.add("actual-kill-goals-wallet-and-repeat-claims-verified");
    }
    var result = new LinkedHashMap<String, Object>();
    result.put("uid", uid);
    result.put("passed", true);
    result.put("target", target);
    result.put("mode", mode);
    result.put("count", current);
    result.put("actualReportedDeaths", actualDeaths);
    result.put("actualEncounters", encounters);
    result.put("actualExplorations", explorations);
    result.put("questNotifications", progressNotifications);
    result.put("removalNotifications", removalNotifications);
    result.put("staleResultsRejected", staleResults);
    result.put("pendingBlessingBatch", pendingBatch);
    result.put("pendingBlessingBuff", pendingBuff);
    result.put("blessingNotifications", blessingNotifications);
    result.put("blessingSelections", blessingSelections);
    result.put("selectedBlessingBatch", selectedBatch);
    result.put("selectedBlessingBuff", selectedBuff);
    result.put("jadeNotifications", jadeNotifications);
    result.put("questQueries", queries);
    result.put("jades", observedJades);
    result.put("originalQuestProgress", questProgress);
    result.put("originalQuestPhases", questPhases);
    result.put("actualOperations", operations);
    result.put("checks", checks);
    result.put("clientGameplayVerified", false);
    result.put("combatInputReplayVerified", false);
    return result;
  }

  private Frame raw(int command, byte[] payload, int response) throws Exception {
    var bytes = Unpooled.wrappedBuffer(PacketCodec.encode(command, new byte[0], payload));
    try {
      channel.write(bytes);
    } finally {
      bytes.release();
    }
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
    while (System.nanoTime() < deadline) {
      if (failure.get() != null)
        throw new IllegalStateException("Native enhancement connection failed", failure.get());
      var f = inbox.poll(1, TimeUnit.SECONDS);
      if (f == null) continue;
      observe(f);
      if (f.command() == response) return f;
    }
    throw new TimeoutException("Response " + response);
  }

  private DynamicMessage call(String name, Map<String, Object> fields) throws Exception {
    var b = DynamicMessage.newBuilder(protocol.clientSchema().message(name));
    fields.forEach(
        (key, value) -> b.setField(b.getDescriptorForType().findFieldByName(key), value));
    String response = name.replace("CsReq", "ScRsp");
    var frame =
        raw(
            protocol.clientCommand(name),
            b.build().toByteArray(),
            protocol.clientCommand(response));
    var m = protocol.clientSchema().parse(response, frame.body());
    require(m.getUnknownFields().asMap().isEmpty(), "Unknown enhancement response fields");
    if (!name.equals("GetRogueBuffEnhanceInfoCsReq"))
      require(
          number(m, "retcode") == 0, "Unexpected " + response + " status=" + number(m, "retcode"));
    return m;
  }

  private DynamicMessage call(String name) throws Exception {
    return call(name, Map.of());
  }

  private void quests(int count) throws Exception {
    validateQuests(call("GetQuestDataCsReq"), count, claimed);
    queries++;
  }

  public static void main(String[] args) {
    var probe = new NativeBattleKillQuestProbe();
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
                  require(frame != null, "Truncated enhancement frame");
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
                  null, new IllegalStateException("Enhancement probe disconnected"));
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
      // stop() already closes all channels; a second close races the KCP listener cleanup.
      client.stop();
    }
    System.exit(exit);
  }
}
