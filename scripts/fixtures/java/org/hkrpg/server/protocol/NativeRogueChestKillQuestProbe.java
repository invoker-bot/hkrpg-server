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
public final class NativeRogueChestKillQuestProbe {
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
  private Set<Integer> claimed = Set.of();
  private final Map<Integer, Integer> questProgress = new TreeMap<>(),
      questPhases = new TreeMap<>();
  private final List<Map<String, Integer>> operations = new ArrayList<>();
  private final List<String> checks = new ArrayList<>();

  private int selectedStage, selectedNpc, trotterDeaths;
  private static final Set<Integer> TROTTERS =
      Set.of(8002051, 8002052, 8002053, 8002060, 8002070, 8002080, 8002061, 8002071, 8002082);
  private final Set<Integer> observedVariants = new TreeSet<>();

  private record Model(int npc, List<Integer> monsters) {}

  private static final Map<Integer, Model> MODELS =
      Map.of(
          80030081, new Model(8001030, List.of(8001030, 8011010, 8012020, 8011010)),
          80030082, new Model(8012010, List.of(8011020, 8012010, 8012010, 8011020)),
          80031011, new Model(8002060, List.of(8011010, 8012020, 8011010, 8002060)),
          80031012, new Model(8002080, List.of(8012010, 8002080, 8012010)),
          80031013, new Model(8002070, List.of(8011020, 8012010, 8002070, 8011020)));

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
      if (id == 4092320 || id == 4092321)
        require(rows.put(id, row) == null, "Duplicate original kill goal");
    }
    require(rows.size() == 2, "Original kill goals missing");
    for (int id : List.of(4092320, 4092321)) {
      int goal = id == 4092320 ? 1 : 66, status = closed.contains(id) ? 3 : count >= goal ? 2 : 1;
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
    if (awaitKills && frame.command() == protocol.clientCommand("GetQuestDataScRsp")) {
      validateQuests(
          protocol.clientSchema().parse("GetQuestDataScRsp", frame.body()), current, claimed);
      progressNotifications++;
    }
    if (awaitKills && frame.command() == protocol.clientCommand("SceneGroupRefreshScNotify")) {
      var m = protocol.clientSchema().parse("SceneGroupRefreshScNotify", frame.body());
      require(number(m, "floor_id") == 81002001, "Wrong defeated source floor");
      for (var raw : (List<?>) field(m, "group_refresh_list")) {
        var g = (DynamicMessage) raw;
        for (var e : (List<?>) field(g, "refresh_entity")) {
          var info = (DynamicMessage) e;
          if (info.hasField(info.getDescriptorForType().findFieldByName("delete_entity"))) {
            require(
                number(g, "group_id") == 58 && number(info, "delete_entity") == expectedEntity,
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
            observedJades == 5 || target == 66 && observedJades == 15,
            "Wrong actual kill goal jades");
        jadeNotifications++;
      }
    }
  }

  private int actor(DynamicMessage scene, boolean consumed) {
    require(
        number(scene, "entry_id") == 8100201
            && number(scene, "plane_id") == 81002
            && number(scene, "floor_id") == 81002001,
        "Wrong original weighted room");
    int result = 0, others = 0;
    for (var raw : (List<?>) field(scene, "entity_group_list"))
      for (var value : (List<?>) field((DynamicMessage) raw, "entity_list")) {
        var e = (DynamicMessage) value;
        if (!e.hasField(e.getDescriptorForType().findFieldByName("npc_monster"))) continue;
        if (number(e, "group_id") == 58 && number(e, "inst_id") == 200002) {
          var npc = (DynamicMessage) field(e, "npc_monster");
          int stage = number(npc, "event_id"), id = number(npc, "monster_id");
          var model = MODELS.get(stage);
          require(
              model != null && model.npc() == id, "Encounter outside original weighted group 3008");
          require(result == 0, "Duplicate weighted placement");
          result = number(e, "entity_id");
          selectedStage = stage;
          selectedNpc = id;
        } else others++;
      }
    require(
        (result == 0) == consumed && others > 0,
        "Weighted consumed/remaining original monsters changed");
    return result;
  }

  private void encounter(DynamicMessage scene) throws Exception {
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
    require(
        id > 0 && number(battle, "stage_id") == selectedStage, "Selected original stage changed");
    var model = MODELS.get(selectedStage);
    int deaths = model.monsters().size();
    var waves = (List<?>) field(battle, "monster_wave_list");
    require(waves.size() == 1, "Original wave count changed");
    var wave = (DynamicMessage) waves.getFirst();
    var mobs = (List<?>) field(wave, "monster_list");
    require(
        number(wave, "battle_stage_id") == selectedStage && mobs.size() == deaths,
        "Original selected wave changed");
    for (int i = 0; i < deaths; i++)
      require(
          number((DynamicMessage) mobs.get(i), "monster_id") == model.monsters().get(i),
          "Selected original roster order changed");
    var request =
        DynamicMessage.newBuilder(protocol.clientSchema().message("PVEBattleResultCsReq"));
    request.setField(request.getDescriptorForType().findFieldByName("battle_id"), id);
    request.setField(request.getDescriptorForType().findFieldByName("stage_id"), selectedStage);
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
                  model.monsters().get(i),
                  "stage_id",
                  selectedStage)
              .entrySet())
        row.setField(row.getDescriptorForType().findFieldByName(e.getKey()), e.getValue());
      var death = row.getDescriptorForType().findFieldByName("death_source");
      row.setField(death, death.getEnumType().findValueByNumber(1));
      stats.addRepeatedField(
          stats.getDescriptorForType().findFieldByName("monster_list"), row.build());
    }
    request.setField(request.getDescriptorForType().findFieldByName("stt"), stats.build());
    int before = progressNotifications, removed = removalNotifications;
    int trotterCount = (int) model.monsters().stream().filter(TROTTERS::contains).count();
    current += trotterCount;
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
            && number(reply, "stage_id") == selectedStage,
        "Source settlement rejected");
    require(
        progressNotifications >= before
            && progressNotifications <= before + 1
            && (trotterCount == 0 || progressNotifications == before + 1)
            && removalNotifications == removed + 1,
        "Kill progress/removal not observed before settlement success");
    actualDeaths += deaths;
    trotterDeaths += trotterCount;
    encounters++;
    observedVariants.add(selectedStage);
    operations.add(
        Map.of(
            "battle",
            id,
            "stage",
            selectedStage,
            "npc",
            selectedNpc,
            "deaths",
            deaths,
            "trotterDeaths",
            trotterCount,
            "count",
            current));
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
    require(target == 1 || target == 66, "Unsupported source kill goal");
    String mode = credentials.get("mode").getAsString();
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
        observedJades == (mode.equals("resume") ? (target == 1 ? 5 : 15) : 0),
        "Restored source goal wallet differs");
    call("PlayerLoginFinishCsReq");
    if (mode.equals("checkpoint")) {
      quests(0);
      while (current < 1) {
        encounter(start());
        if (current < 1) {
          call("LeaveRogueCsReq");
          call("QuitRogueCsReq", Map.of("area_id", 100));
        }
      }
      quests(1);
      call("LeaveRogueCsReq");
      checks.add("one-original-reported-trotter-death-saved-with-partial-weighted-room");
    } else {
      var ids = target == 1 ? List.of(4092320) : List.of(4092320, 4092321);
      if (mode.equals("resume")) {
        current = target;
        claimed = Set.copyOf(ids);
        quests(target);
      } else {
        require(mode.equals("complete"), "Unknown source test mode");
        current = 1;
        quests(1);
        actor(
            (DynamicMessage) field(call("EnterRogueCsReq", Map.of("area_id", 100)), "scene"), true);
        checks.add(
            "normal-h2-restart-restores-defeated-original-actor-and-remaining-original-monsters");
        call("LeaveRogueCsReq");
        call("QuitRogueCsReq", Map.of("area_id", 100));
        while (current < target) {
          encounter(start());
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
            sum == (target == 1 ? 5 : 15)
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
      checks.add("actual-trotter-goals-wallet-and-repeat-claims-verified");
    }
    var result = new LinkedHashMap<String, Object>();
    result.put("uid", uid);
    result.put("passed", true);
    result.put("selectedNpc", selectedNpc);
    result.put("selectedStage", selectedStage);
    result.put("observedVariants", observedVariants);
    result.put("target", target);
    result.put("mode", mode);
    result.put("actualTrotterDeaths", trotterDeaths);
    result.put("count", current);
    result.put("actualReportedDeaths", actualDeaths);
    result.put("actualEncounters", encounters);
    result.put("actualExplorations", explorations);
    result.put("questNotifications", progressNotifications);
    result.put("removalNotifications", removalNotifications);
    result.put("staleResultsRejected", staleResults);
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
    var probe = new NativeRogueChestKillQuestProbe();
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
