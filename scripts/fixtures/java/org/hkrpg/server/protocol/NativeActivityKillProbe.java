package org.hkrpg.server.protocol;

import com.google.gson.*;
import com.google.protobuf.*;
import emu.lunarcore.server.packet.PacketCodec;
import io.netty.buffer.*;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import kcp.highway.*;

/**
 * Original scene encounter, native reported deaths, activity rewards and saved progress; combat
 * input is not replayed.
 */
public final class NativeActivityKillProbe {
  private record Frame(int command, byte[] body) {}

  private final ProtocolBridge protocol = ProtocolBridge.forVersion("4.6.0");
  private final BlockingQueue<Frame> inbox = new LinkedBlockingQueue<>();
  private final AtomicReference<Throwable> failure = new AtomicReference<>();
  private final CompletableFuture<Ukcp> connected = new CompletableFuture<>();
  private Ukcp channel;
  private int requests, reports, rejected;

  private static Object field(DynamicMessage m, String key) {
    return m.getField(m.getDescriptorForType().findFieldByName(key));
  }

  private static int number(DynamicMessage m, String key) {
    var v = field(m, key);
    return v instanceof com.google.protobuf.Descriptors.EnumValueDescriptor e
        ? e.getNumber()
        : (Integer) v;
  }

  private static void set(DynamicMessage.Builder b, String key, Object value) {
    b.setField(b.getDescriptorForType().findFieldByName(key), value);
  }

  private static void require(boolean valid, String why) {
    if (!valid) throw new IllegalStateException(why);
  }

  private DynamicMessage.Builder builder(String name) {
    return DynamicMessage.newBuilder(protocol.clientSchema().message(name));
  }

  private DynamicMessage call(String name, byte[] body) throws Exception {
    var bytes =
        Unpooled.wrappedBuffer(PacketCodec.encode(protocol.clientCommand(name), new byte[0], body));
    try {
      channel.write(bytes);
      requests++;
    } finally {
      bytes.release();
    }
    long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(25);
    while (System.nanoTime() < end) {
      if (failure.get() != null)
        throw new IllegalStateException("Connection failed", failure.get());
      var frame = inbox.poll(1, TimeUnit.SECONDS);
      if (frame == null) continue;
      if (frame.command() == protocol.clientCommand(name.replace("CsReq", "ScRsp"))) {
        var reply = protocol.clientSchema().parse(name.replace("CsReq", "ScRsp"), frame.body());
        require(reply.getUnknownFields().asMap().isEmpty(), "Unknown response fields");
        return reply;
      }
    }
    throw new TimeoutException(name);
  }

  private DynamicMessage call(String name, DynamicMessage.Builder request) throws Exception {
    return call(name, request.build().toByteArray());
  }

  private DynamicMessage call(String name) throws Exception {
    return call(name, builder(name));
  }

  private DynamicMessage scene(JsonObject control) throws Exception {
    var reply = call("GetCurSceneInfoCsReq");
    require(number(reply, "retcode") == 0, "Scene query rejected");
    var scene = (DynamicMessage) field(reply, "scene");
    require(
        number(scene, "entry_id") == control.get("entry").getAsInt()
            && number(scene, "plane_id") == control.get("plane").getAsInt()
            && number(scene, "floor_id") == control.get("floor").getAsInt(),
        "Wrong original scene identity");
    require((Long) field(scene, "interact_id") > 0, "Missing current map runtime ID");
    return scene;
  }

  private void quest(int id, int progress, int status) throws Exception {
    var reply = call("GetQuestDataCsReq");
    require(number(reply, "retcode") == 0, "Quest query rejected");
    var rows =
        ((List<?>) field(reply, "quest_list"))
            .stream().map(x -> (DynamicMessage) x).filter(q -> number(q, "id") == id).toList();
    require(rows.size() == 1, "Missing or duplicate original activity quest " + id);
    require(
        number(rows.getFirst(), "progress") == progress
            && number(rows.getFirst(), "status") == status,
        "Incorrect activity progress " + id + ": " + rows.getFirst());
  }

  private DynamicMessage claim(int id) throws Exception {
    var request = builder("TakeQuestRewardCsReq");
    request.addRepeatedField(request.getDescriptorForType().findFieldByName("ODJIJFJCHHB"), id);
    return call("TakeQuestRewardCsReq", request);
  }

  private void reward(DynamicMessage reply, JsonObject goal) {
    require(number(reply, "retcode") == 0, "Reward claim rejected");
    var actual = new TreeMap<Integer, Integer>();
    var reward = (DynamicMessage) field(reply, "reward");
    for (var raw : (List<?>) field(reward, "item_list")) {
      var item = (DynamicMessage) raw;
      require(
          actual.put(number(item, "item_id"), number(item, "num")) == null,
          "Duplicate reward item");
    }
    var expected = new TreeMap<Integer, Integer>();
    goal.getAsJsonObject("rewards")
        .entrySet()
        .forEach(e -> expected.put(Integer.parseInt(e.getKey()), e.getValue().getAsInt()));
    require(actual.equals(expected), "Original reward recipe differs: " + actual);
    require(
        ((List<?>) field(reply, "succ_quest_id_list")).equals(List.of(goal.get("id").getAsInt())),
        "Claimed a different quest");
  }

  private Map<String, Object> execute(JsonObject control) throws Exception {
    var token = builder("PlayerGetTokenCsReq");
    set(token, "account_uid", control.get("accountUid").getAsString());
    set(token, "token", control.get("comboToken").getAsString());
    set(token, "platform", 1);
    var reply = call("PlayerGetTokenCsReq", token);
    require(number(reply, "retcode") == 0, "Token rejected");
    int uid = number(reply, "uid");
    require(
        number(call("PlayerLoginCsReq"), "retcode") == 0
            && number(call("PlayerLoginFinishCsReq"), "retcode") == 0,
        "Login rejected");
    if (control.get("seed").getAsBoolean()) {
      System.out.println("READY_UID=" + uid);
      System.out.flush();
      boolean owned = false;
      for (int i = 0; i < 120 && !owned; i++) {
        for (var row : (List<?>) field(call("GetAvatarDataCsReq"), "avatar_list"))
          if (number((DynamicMessage) row, "base_avatar_id") == 1001) owned = true;
        if (!owned) Thread.sleep(200);
      }
      require(owned, "Explicit console roster fixture was not granted");
      return Map.of(
          "passed",
          true,
          "seed",
          true,
          "uid",
          uid,
          "nativeRequests",
          requests,
          "clientGameplayVerified",
          false);
    }
    var current = scene(control);
    var goals = control.getAsJsonArray("goals");
    for (var raw : goals) {
      var goal = raw.getAsJsonObject();
      int id = goal.get("id").getAsInt(), progress = goal.get("before").getAsInt();
      quest(
          id,
          progress,
          control.get("resume").getAsBoolean()
              ? 3
              : progress == goal.get("goal").getAsInt() ? 2 : 1);
      if (progress < goal.get("goal").getAsInt() || control.get("resume").getAsBoolean())
        require(number(claim(id), "retcode") != 0, "Premature or duplicate reward paid " + id);
    }
    var battles = new ArrayList<Map<String, Object>>();
    int deaths = 0;
    for (var raw : control.getAsJsonArray("encounters")) {
      var encounter = raw.getAsJsonObject();
      DynamicMessage entity = null;
      for (var group : (List<?>) field(current, "entity_group_list"))
        for (var item : (List<?>) field((DynamicMessage) group, "entity_list")) {
          var candidate = (DynamicMessage) item;
          if (number(candidate, "group_id") == encounter.get("group").getAsInt()
              && number(candidate, "inst_id") == encounter.get("instance").getAsInt()) {
            require(
                entity == null
                    && candidate.hasField(
                        candidate.getDescriptorForType().findFieldByName("npc_monster")),
                "Wrong or duplicate original NPC");
            require(
                number((DynamicMessage) field(candidate, "npc_monster"), "monster_id")
                    == encounter.get("npc").getAsInt(),
                "Original NPC replaced");
            entity = candidate;
          }
        }
      require(entity != null, "Missing original scene NPC " + encounter);
      int leader = number(current, "leader_entity_id");
      require(leader > 0, "Missing formal fixture caster");
      var cast = builder("SceneCastSkillCsReq");
      set(cast, "cast_entity_id", leader);
      set(cast, "attacked_by_entity_id", leader);
      set(cast, "skill_index", 0);
      cast.addRepeatedField(
          cast.getDescriptorForType().findFieldByName("hit_target_entity_id_list"),
          number(entity, "entity_id"));
      var started = call("SceneCastSkillCsReq", cast);
      require(
          started.hasField(started.getDescriptorForType().findFieldByName("battle_info")),
          "Original NPC cast did not enter battle");
      var battle = (DynamicMessage) field(started, "battle_info");
      int battleId = number(battle, "battle_id"), stage = encounter.get("stage").getAsInt();
      require(battleId > 0 && number(battle, "stage_id") == stage, "Wrong source battle stage");
      var expected = encounter.getAsJsonArray("waves");
      var waves = (List<?>) field(battle, "monster_wave_list");
      require(waves.size() == expected.size(), "Original wave count differs");
      var result = builder("PVEBattleResultCsReq");
      set(result, "battle_id", battleId);
      set(result, "stage_id", stage);
      var end = result.getDescriptorForType().findFieldByName("end_status");
      set(result, "end_status", end.getEnumType().findValueByName("BATTLE_END_WIN"));
      var stats = builder("BattleStatistics");
      int reported = 0;
      for (int w = 0; w < waves.size(); w++) {
        var wave = (DynamicMessage) waves.get(w);
        require(number(wave, "battle_stage_id") == stage, "Wrong wave stage");
        var monsters = (List<?>) field(wave, "monster_list");
        var original = expected.get(w).getAsJsonArray();
        require(monsters.size() == original.size(), "Original roster size differs");
        for (int m = 0; m < monsters.size(); m++) {
          int monster = number((DynamicMessage) monsters.get(m), "monster_id");
          require(monster == original.get(m).getAsInt(), "Original roster order differs");
          var observation = builder("MonsterBattleInfo");
          set(observation, "entity_id", 5001 + reported);
          set(observation, "monster_id", monster);
          set(observation, "stage_id", stage);
          var death = observation.getDescriptorForType().findFieldByName("death_source");
          set(observation, "death_source", death.getEnumType().findValueByNumber(reported % 2 + 1));
          stats.addRepeatedField(
              stats.getDescriptorForType().findFieldByName("monster_list"), observation.build());
          reported++;
        }
      }
      set(result, "stt", stats.build());
      var settled = call("PVEBattleResultCsReq", result);
      require(
          number(settled, "retcode") == 0
              && number(settled, "battle_id") == battleId
              && number(settled, "stage_id") == stage,
          "Original battle settlement rejected");
      var drops = new TreeMap<Integer, Integer>();
      for (var item : (List<?>) field((DynamicMessage) field(settled, "drop_data"), "item_list")) {
        var drop = (DynamicMessage) item;
        drops.merge(number(drop, "item_id"), number(drop, "num"), Math::addExact);
      }
      deaths += reported;
      require(
          number(call("PVEBattleResultCsReq", result), "retcode") != 0, "Repeated result accepted");
      rejected++;
      current = scene(control);
      for (var group : (List<?>) field(current, "entity_group_list"))
        for (var item : (List<?>) field((DynamicMessage) group, "entity_list")) {
          var remaining = (DynamicMessage) item;
          require(
              number(remaining, "group_id") != encounter.get("group").getAsInt()
                  || number(remaining, "inst_id") != encounter.get("instance").getAsInt(),
              "Defeated source NPC remained");
        }
      var after = encounter.getAsJsonObject("after");
      for (var rawGoal : goals) {
        var goal = rawGoal.getAsJsonObject();
        int id = goal.get("id").getAsInt(), progress = after.get(String.valueOf(id)).getAsInt();
        quest(id, progress, progress == goal.get("goal").getAsInt() ? 2 : 1);
      }
      battles.add(
          Map.of(
              "battle",
              battleId,
              "stage",
              stage,
              "reportedDeaths",
              reported,
              "rosterVerified",
              true,
              "drops",
              drops));
    }
    int claimed = 0;
    for (var raw : goals) {
      var goal = raw.getAsJsonObject();
      int id = goal.get("id").getAsInt(),
          progress = goal.get("after").getAsInt(),
          target = goal.get("goal").getAsInt();
      boolean resume = control.get("resume").getAsBoolean();
      quest(id, progress, resume ? 3 : progress == target ? 2 : 1);
      if (!resume && progress == target && control.get("claim").getAsBoolean()) {
        reward(claim(id), goal);
        claimed++;
        quest(id, progress, 3);
        require(number(claim(id), "retcode") != 0, "Duplicate reward paid again");
      }
    }
    return Map.of(
        "passed",
        true,
        "uid",
        uid,
        "seed",
        false,
        "nativeRequests",
        requests,
        "actualReportedDeaths",
        deaths,
        "staleResultRejections",
        rejected,
        "battles",
        battles,
        "claimedOriginalGoals",
        claimed,
        "clientGameplayVerified",
        false);
  }

  public static void main(String[] args) {
    var probe = new NativeActivityKillProbe();
    var client = new KcpClient();
    Ukcp connection = null;
    int exit = 1;
    try {
      var control =
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
                  require(frame != null, "Truncated frame");
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
              probe.failure.compareAndSet(null, new IllegalStateException("Disconnected"));
            }
          });
      connection =
          client.connect(new InetSocketAddress("127.0.0.1", Integer.parseInt(args[0])), config);
      probe.channel = probe.connected.get(10, TimeUnit.SECONDS);
      System.out.write(
          (new Gson().toJson(probe.execute(control)) + "\n").getBytes(StandardCharsets.UTF_8));
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
