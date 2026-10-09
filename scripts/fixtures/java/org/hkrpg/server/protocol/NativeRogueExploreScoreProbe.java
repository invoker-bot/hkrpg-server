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

/** Source room combat and once-only exploration credit; no official client interaction. */
public final class NativeRogueExploreScoreProbe {
  private record Frame(int command, byte[] body) {}

  private final ProtocolBridge protocol = ProtocolBridge.forVersion("4.6.0");
  private final BlockingQueue<Frame> inbox = new LinkedBlockingQueue<>();
  private final AtomicReference<Throwable> failure = new AtomicReference<>();
  private final CompletableFuture<Ukcp> connected = new CompletableFuture<>();
  private Ukcp channel;
  private int requests,
      battles,
      scoreNotifications,
      removals,
      staleResults,
      credits,
      droppedCredits;
  private JsonObject actors;
  private final List<Map<String, Integer>> encounters = new ArrayList<>();

  private static Object field(DynamicMessage m, String n) {
    return m.getField(m.getDescriptorForType().findFieldByName(n));
  }

  private static int number(DynamicMessage m, String n) {
    return (Integer) field(m, n);
  }

  private static void require(boolean ok, String why) {
    if (!ok) throw new IllegalStateException(why);
  }

  private void observe(Frame frame) throws Exception {
    if (frame.command() == protocol.clientCommand("PlayerSyncScNotify")) {
      var m = protocol.clientSchema().parse("PlayerSyncScNotify", frame.body());
      if (m.hasField(m.getDescriptorForType().findFieldByName("basic_info"))) {
        var info = (DynamicMessage) field(m, "basic_info");
        require(number(info, "hcoin") == 0, "Unexpected jade grant");
        credits = number(info, "scoin");
      }
    }
    if (frame.command() == protocol.clientCommand("CycleScoreRewardUpdateInfoScNotify")) {
      var row =
          (DynamicMessage)
              field(
                  protocol.clientSchema().parse("CycleScoreRewardUpdateInfoScNotify", frame.body()),
                  "NFEDANFBIBE");
      require(number(row, "PMJGAJGIFOA") == 260, "Unexpected credited exploration score");
      scoreNotifications++;
    }
    if (frame.command() == protocol.clientCommand("SceneGroupRefreshScNotify")) {
      var m = protocol.clientSchema().parse("SceneGroupRefreshScNotify", frame.body());
      for (var raw : (List<?>) field(m, "group_refresh_list")) {
        var g = (DynamicMessage) raw;
        for (var value : (List<?>) field(g, "refresh_entity")) {
          var e = (DynamicMessage) value;
          if (e.hasField(e.getDescriptorForType().findFieldByName("delete_entity"))) {
            require(
                number(m, "floor_id") == 80001001
                    && actors.has(Integer.toString(number(g, "group_id"))),
                "Wrong defeated source group");
            removals++;
          }
        }
      }
    }
  }

  private void mission(boolean done) throws Exception {
    var m =
        call(
            "GetMissionStatusCsReq",
            Map.of(
                "main_mission_id_list",
                List.of(4030006),
                "sub_mission_id_list",
                List.of(403000601)));
    require(
        ((List<?>) field(m, "finished_main_mission_id_list"))
            .equals(done ? List.of(4030006) : List.of()),
        "Wrong main mission completion");
    var rows = (List<?>) field(m, "sub_mission_status_list");
    require(rows.size() == 1, "Missing original exploration target");
    var sub = (DynamicMessage) rows.getFirst();
    require(
        number(sub, "id") == 403000601
            && number(sub, "progress") == (done ? 1 : 0)
            && ((Descriptors.EnumValueDescriptor) field(sub, "status")).getNumber()
                == (done ? 2 : 1),
        "Original target progress/phase differs");
  }

  private void score(int expected) throws Exception {
    var m = (DynamicMessage) field(call("CycleScoreRewardGetDataCsReq"), "NFEDANFBIBE");
    require(
        number(m, "PMJGAJGIFOA") == expected
            && (Long) field(m, "end_time") > (Long) field(m, "start_time")
            && ((List<?>) field(m, "OPLNKCOLCKJ")).isEmpty(),
        "Score or claim history changed");
  }

  private List<DynamicMessage> monsters(DynamicMessage scene) {
    require(
        number(scene, "entry_id") == 8000101
            && number(scene, "plane_id") == 80001
            && number(scene, "floor_id") == 80001001,
        "Wrong source room");
    var rows = new ArrayList<DynamicMessage>();
    for (var g : (List<?>) field(scene, "entity_group_list"))
      for (var raw : (List<?>) field((DynamicMessage) g, "entity_list")) {
        var e = (DynamicMessage) raw;
        if (e.hasField(e.getDescriptorForType().findFieldByName("npc_monster"))) {
          var expected = actors.getAsJsonObject(Integer.toString(number(e, "group_id")));
          var npc = (DynamicMessage) field(e, "npc_monster");
          require(
              expected != null
                  && number(e, "inst_id") == 200001
                  && number(npc, "monster_id") == expected.get("npc").getAsInt()
                  && number(npc, "event_id") == expected.get("stage").getAsInt(),
              "Original source actor changed: group="
                  + number(e, "group_id")
                  + " inst="
                  + number(e, "inst_id")
                  + " npc="
                  + number(npc, "monster_id")
                  + " event="
                  + number(npc, "event_id")
                  + " expected="
                  + expected);
          rows.add(e);
        }
      }
    return rows;
  }

  private void encounter(DynamicMessage scene, DynamicMessage entity) throws Exception {
    int leader = number(scene, "leader_entity_id"),
        group = number(entity, "group_id"),
        target = number(entity, "entity_id");
    require(leader > 0 && target > 0, "Missing actual combat actor");
    var expected = actors.getAsJsonObject(Integer.toString(group));
    int stage = expected.get("stage").getAsInt();
    var response =
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
                List.of(target)));
    var battle = (DynamicMessage) field(response, "battle_info");
    int id = number(battle, "battle_id");
    require(id > 0 && number(battle, "stage_id") == stage, "Wrong source battle stage");
    var waves = (List<?>) field(battle, "monster_wave_list");
    var sourceWaves = expected.getAsJsonArray("waves");
    require(waves.size() == sourceWaves.size(), "Original wave count changed");
    for (int i = 0; i < waves.size(); i++) {
      var wave = (DynamicMessage) waves.get(i);
      var actual = (List<?>) field(wave, "monster_list");
      var source = sourceWaves.get(i).getAsJsonArray();
      require(
          number(wave, "battle_stage_id") == stage && actual.size() == source.size(),
          "Wrong original wave");
      for (int j = 0; j < actual.size(); j++)
        require(
            number((DynamicMessage) actual.get(j), "monster_id") == source.get(j).getAsInt(),
            "Original combat roster changed");
    }
    var req = DynamicMessage.newBuilder(protocol.clientSchema().message("PVEBattleResultCsReq"));
    req.setField(req.getDescriptorForType().findFieldByName("battle_id"), id);
    req.setField(req.getDescriptorForType().findFieldByName("stage_id"), stage);
    var end = req.getDescriptorForType().findFieldByName("end_status");
    req.setField(end, end.getEnumType().findValueByName("BATTLE_END_WIN"));
    int before = removals;
    var result =
        protocol
            .clientSchema()
            .parse(
                "PVEBattleResultScRsp",
                raw(
                        protocol.clientCommand("PVEBattleResultCsReq"),
                        req.build().toByteArray(),
                        protocol.clientCommand("PVEBattleResultScRsp"))
                    .body());
    requests++;
    require(
        number(result, "retcode") == 0
            && number(result, "battle_id") == id
            && removals == before + 1,
        "Battle settlement or actual actor removal failed");
    for (var raw : (List<?>) field((DynamicMessage) field(result, "drop_data"), "item_list")) {
      var item = (DynamicMessage) raw;
      if (number(item, "item_id") == emu.lunarcore.GameConstants.MATERIAL_COIN_ID)
        droppedCredits = Math.addExact(droppedCredits, number(item, "num"));
    }
    require(credits == droppedCredits, "Wallet differs from actual battle drop response");
    var duplicate =
        protocol
            .clientSchema()
            .parse(
                "PVEBattleResultScRsp",
                raw(
                        protocol.clientCommand("PVEBattleResultCsReq"),
                        req.build().toByteArray(),
                        protocol.clientCommand("PVEBattleResultScRsp"))
                    .body());
    requests++;
    require(number(duplicate, "retcode") != 0, "Duplicate battle result accepted");
    staleResults++;
    battles++;
    encounters.add(Map.of("group", group, "stage", stage, "battle", id));
  }

  private Map<String, Object> execute(JsonObject credentials) throws Exception {
    String mode = credentials.get("mode").getAsString();
    require(Set.of("pending", "complete", "resume").contains(mode), "Unknown fixture mode");
    actors = credentials.getAsJsonObject("actors");
    int uid =
        number(
            call(
                "PlayerGetTokenCsReq",
                Map.of(
                    "account_uid",
                    credentials.get("accountUid").getAsString(),
                    "token",
                    credentials.get("comboToken").getAsString(),
                    "platform",
                    1)),
            "uid");
    var basic = (DynamicMessage) field(call("PlayerLoginCsReq"), "basic_info");
    credits = number(basic, "scoin");
    int expectedCredits =
        credentials.has("expectedCredits") ? credentials.get("expectedCredits").getAsInt() : 0;
    require(number(basic, "hcoin") == 0 && credits == expectedCredits, "Unexpected fixture wallet");
    call("PlayerLoginFinishCsReq");
    mission(mode.equals("resume"));
    score(mode.equals("resume") ? 260 : 0);
    if (mode.equals("complete")) {
      var scene =
          (DynamicMessage)
              field(
                  call(
                      "StartRogueCsReq",
                      Map.of(
                          "area_id",
                          110,
                          "NOEPBNMJFGL",
                          List.of(emu.lunarcore.GameConstants.TRAILBLAZER_AVATAR_ID))),
                  "scene");
      require(monsters(scene).size() == actors.size(), "Original room actor count changed");
      for (int i = 0; i < actors.size(); i++) {
        var targets = monsters(scene);
        require(targets.size() == actors.size() - i, "Wrong surviving source actors");
        encounter(scene, targets.getFirst());
        scene = (DynamicMessage) field(call("GetCurSceneInfoCsReq"), "scene");
        mission(false);
        score(0);
      }
      require(monsters(scene).isEmpty(), "Source room not cleared");
      call("LeaveRogueCsReq");
      mission(false);
      score(0);
      call("QuitRogueCsReq", Map.of("area_id", 110));
      mission(true);
      score(260);
      score(260);
      require(
          scoreNotifications == 1 && removals == actors.size() && staleResults == actors.size(),
          "Settlement notifications differ");
    }
    var r = new LinkedHashMap<String, Object>();
    r.put("uid", uid);
    r.put("passed", true);
    r.put("mode", mode);
    r.put("score", mode.equals("pending") ? 0 : 260);
    r.put("missionProgress", mode.equals("pending") ? 0 : 1);
    r.put("credits", credits);
    r.put("battleDroppedCredits", droppedCredits);
    r.put("requests", requests);
    r.put("battles", battles);
    r.put("scoreNotifications", scoreNotifications);
    r.put("removedSourceActors", removals);
    r.put("staleBattleResultsRejected", staleResults);
    r.put("encounters", encounters);
    r.put("clientGameplayVerified", false);
    r.put("combatInputReplayVerified", false);
    return r;
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
        throw new IllegalStateException(
            "Native exploration score connection failed", failure.get());
      var f = inbox.poll(1, TimeUnit.SECONDS);
      if (f == null) continue;
      observe(f);
      if (f.command() == response) return f;
    }
    throw new TimeoutException("Response " + response);
  }

  private DynamicMessage call(String name, Map<String, Object> fields) throws Exception {
    requests++;
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
    require(m.getUnknownFields().asMap().isEmpty(), "Unknown exploration score response fields");
    if (!name.equals("GetRogueBuffEnhanceInfoCsReq"))
      require(
          number(m, "retcode") == 0, "Unexpected " + response + " status=" + number(m, "retcode"));
    return m;
  }

  private DynamicMessage call(String name) throws Exception {
    return call(name, Map.of());
  }

  public static void main(String[] args) {
    var probe = new NativeRogueExploreScoreProbe();
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
                  require(frame != null, "Truncated exploration score frame");
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
                  null, new IllegalStateException("Exploration score probe disconnected"));
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
      // stop() already closes all channels; a second close races the KCP listener cleanup.
      client.stop();
    }
    System.exit(exit);
  }
}
