package org.hkrpg.server.protocol;

import com.google.gson.*;
import com.google.protobuf.DynamicMessage;
import emu.lunarcore.server.packet.PacketCodec;
import io.netty.buffer.*;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import kcp.highway.*;

/** Original Normal story challenge 20014, two selected buffs and accepted cumulative reports. */
public final class NativeStoryChallengeRoundLimitProbe {
  private record Frame(int command, byte[] body) {}

  private final ProtocolBridge protocol = ProtocolBridge.forVersion("4.6.0");
  private final BlockingQueue<Frame> inbox = new LinkedBlockingQueue<>();
  private final AtomicReference<Throwable> failure = new AtomicReference<>();
  private final CompletableFuture<Ukcp> connected = new CompletableFuture<>();
  private Ukcp channel;
  private DynamicMessage settlement;
  private int requests;

  private static Object field(DynamicMessage m, String key) {
    return m.getField(m.getDescriptorForType().findFieldByName(key));
  }

  private static int number(DynamicMessage m, String key) {
    var value = field(m, key);
    return value instanceof com.google.protobuf.Descriptors.EnumValueDescriptor e
        ? e.getNumber()
        : (Integer) value;
  }

  private static void require(boolean valid, String why) {
    if (!valid) throw new IllegalStateException(why);
  }

  private static void set(DynamicMessage.Builder b, String key, Object value) {
    b.setField(b.getDescriptorForType().findFieldByName(key), value);
  }

  private DynamicMessage.Builder builder(String name) {
    return DynamicMessage.newBuilder(protocol.clientSchema().message(name));
  }

  private void observe(Frame frame) throws Exception {
    if (frame.command() == protocol.clientCommand("ChallengeSettleNotify"))
      settlement = protocol.clientSchema().parse("ChallengeSettleNotify", frame.body());
  }

  private DynamicMessage call(String name, DynamicMessage.Builder b) throws Exception {
    var response = name.replace("CsReq", "ScRsp");
    int command = name.equals("OFEDNNJIJFL") ? 5676 : protocol.clientCommand(name);
    if (name.equals("OFEDNNJIJFL")) response = "HandleRogueCommonPendingActionScRsp";
    var bytes =
        Unpooled.wrappedBuffer(PacketCodec.encode(command, new byte[0], b.build().toByteArray()));
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
      observe(frame);
      if (frame.command() == protocol.clientCommand(response)) {
        var m = protocol.clientSchema().parse(response, frame.body());
        require(m.getUnknownFields().asMap().isEmpty(), "Unknown response fields: " + response);
        return m;
      }
    }
    throw new TimeoutException(response);
  }

  private DynamicMessage call(String name) throws Exception {
    return call(name, builder(name));
  }

  private DynamicMessage current(int half, int score) throws Exception {
    var m = call("GetCurChallengeCsReq");
    require(number(m, "retcode") == 0, "Current challenge rejected");
    var cur = (DynamicMessage) field(m, "cur_challenge");
    require(number(cur, "challenge_id") == 20014, "Wrong source challenge");
    require(number(cur, "extra_lineup_type") == (half == 1 ? 1 : 3), "Wrong half lineup");
    require(number(cur, "score_id") == score, "Wrong cumulative first-half score");
    require(number(cur, "round_count") == 4, "Story round limit wrapped or changed");
    var buffs =
        (DynamicMessage) field((DynamicMessage) field(cur, "stage_info"), "cur_story_buffs");
    require(field(buffs, "buff_list").equals(List.of(3031301, 3031302)), "Lost half selections");
    return cur;
  }

  private DynamicMessage history() throws Exception {
    var response = call("GetChallengeCsReq");
    require(number(response, "retcode") == 0, "History rejected");
    for (var row : (List<?>) field(response, "challenge_list"))
      if (number((DynamicMessage) row, "challenge_id") == 20014) return (DynamicMessage) row;
    throw new IllegalStateException("Missing original story history");
  }

  private DynamicMessage scene() throws Exception {
    var m = call("GetCurSceneInfoCsReq");
    require(number(m, "retcode") == 0, "Scene query failed");
    return (DynamicMessage) field(m, "scene");
  }

  private DynamicMessage fight(int half) throws Exception {
    var scene = scene();
    require(number(scene, "entry_id") == 3000205, "Wrong original entry");
    int enemy = 0;
    for (var group : (List<?>) field(scene, "entity_group_list"))
      for (var raw : (List<?>) field((DynamicMessage) group, "entity_list")) {
        var entity = (DynamicMessage) raw;
        var fd = entity.getDescriptorForType().findFieldByName("npc_monster");
        if (entity.hasField(fd)) {
          require(enemy == 0, "Unexpected extra encounter");
          enemy = number(entity, "entity_id");
          require(
              number((DynamicMessage) field(entity, "npc_monster"), "monster_id")
                  == (half == 1 ? 1004020 : 1014010),
              "Wrong original NPC");
        }
      }
    require(enemy > 0, "Missing original encounter");
    var attack = builder("SceneCastSkillCsReq");
    set(attack, "cast_entity_id", field(scene, "leader_entity_id"));
    set(attack, "attacked_by_entity_id", field(scene, "leader_entity_id"));
    attack.addRepeatedField(
        attack.getDescriptorForType().findFieldByName("hit_target_entity_id_list"), enemy);
    var cast = call("SceneCastSkillCsReq", attack);
    require(number(cast, "retcode") == 0, "Encounter rejected");
    var b = (DynamicMessage) field(cast, "battle_info");
    require(number(b, "rounds_limit") == 4, "Original battle round limit must be four");
    require(number(b, "stage_id") == (half == 1 ? 30019041 : 30019042), "Wrong original stage");
    require(!((List<?>) field(b, "monster_wave_list")).isEmpty(), "Missing original waves");
    var ids = new HashSet<Integer>();
    for (var raw : (List<?>) field(b, "buff_list")) {
      var row = (DynamicMessage) raw;
      ids.add(number(row, "id"));
      if (number(row, "id") == 3031001 || number(row, "id") == (half == 1 ? 3031301 : 3031302))
        require(number(row, "wave_flag") == -1, "Story buff does not bind every wave");
    }
    require(
        ids.contains(3031001)
            && ids.contains(half == 1 ? 3031301 : 3031302)
            && !ids.contains(half == 1 ? 3031302 : 3031301),
        "Half buff incorrect");
    boolean counter = false;
    for (var raw : (List<?>) field(b, "battle_target_info")) {
      var entry = (DynamicMessage) raw;
      if (number(entry, "key") == 1)
        for (var target :
            (List<?>) field((DynamicMessage) field(entry, "value"), "battle_target_list")) {
          var row = (DynamicMessage) target;
          if (number(row, "id") == 10003 && number(row, "progress") == (half == 1 ? 0 : 12000))
            counter = true;
        }
    }
    require(counter, "Missing 4.6 story score counter");
    return b;
  }

  private void rejectDirectEntry(int half) throws Exception {
    var before = scene();
    var empty = call("GetCurBattleInfoCsReq");
    require(number(empty, "retcode") == 1, "Unexpected battle before direct-entry check");
    var request = builder("SceneEnterStageCsReq");
    set(request, "event_id", half == 1 ? 30019041 : 30019042);
    var reply = call("SceneEnterStageCsReq", request);
    require(
        number(reply, "retcode") != 0,
        "Unbound direct story event accepted: " + number(reply, "retcode"));
    require(empty.equals(call("GetCurBattleInfoCsReq")), "Rejected direct entry created a battle");
    require(before.equals(scene()), "Rejected direct entry changed the original scene");
    current(half, half == 1 ? 0 : 12000);
  }

  private DynamicMessage report(DynamicMessage battle, int score) throws Exception {
    var req = builder("PVEBattleResultCsReq");
    set(req, "battle_id", field(battle, "battle_id"));
    set(req, "stage_id", field(battle, "stage_id"));
    var end = req.getDescriptorForType().findFieldByName("end_status");
    req.setField(end, end.getEnumType().findValueByName("BATTLE_END_WIN"));
    var stats = builder("BattleStatistics");
    set(stats, "challenge_score", score);
    set(stats, "round_cnt", 1);
    set(req, "stt", stats.build());
    return call("PVEBattleResultCsReq", req);
  }

  private Map<String, Object> execute(JsonObject control) throws Exception {
    var token = builder("PlayerGetTokenCsReq");
    set(token, "account_uid", control.get("accountUid").getAsString());
    set(token, "token", control.get("comboToken").getAsString());
    set(token, "platform", 1);
    var reply = call("PlayerGetTokenCsReq", token);
    require(number(reply, "retcode") == 0, "Token rejected");
    int uid = number(reply, "uid");
    require(number(call("PlayerLoginCsReq"), "retcode") == 0, "Login rejected");
    require(number(call("PlayerLoginFinishCsReq"), "retcode") == 0, "Login finish rejected");
    boolean resume = control.get("resume").getAsBoolean();
    if (!resume) {
      System.out.println("READY_UID=" + uid);
      System.out.flush();
      boolean owned = false;
      for (int i = 0; i < 120 && !owned; i++) {
        var avatars = call("GetAvatarDataCsReq");
        for (var raw : (List<?>) field(avatars, "avatar_list"))
          if (number((DynamicMessage) raw, "base_avatar_id") == 1001) owned = true;
        if (!owned) Thread.sleep(200);
      }
      require(owned, "Console roster fixture did not grant formal avatar 1001");
      var req = builder("StartChallengeCsReq");
      set(req, "challenge_id", 20014);
      req.addRepeatedField(req.getDescriptorForType().findFieldByName("first_lineup"), 8001);
      req.addRepeatedField(req.getDescriptorForType().findFieldByName("second_lineup"), 1001);
      var buffs = builder("ChallengeStoryBuffInfo");
      set(buffs, "buff_one", 3031301);
      set(buffs, "buff_two", 3031302);
      var stage = builder("ChallengeBuffInfo");
      set(stage, "story_info", buffs.build());
      set(req, "stage_info", stage.build());
      require(number(call("StartChallengeCsReq", req), "retcode") == 0, "Story entry rejected");
      current(1, 0);
      rejectDirectEntry(1);
      var first = fight(1);
      require(number(report(first, 12000), "retcode") == 0, "First half rejected");
      require(number(report(first, 12000), "retcode") != 0, "Repeated first half accepted");
      current(2, 12000);
      rejectDirectEntry(2);
      var second = fight(2);
      var snapshot = call("GetCurBattleInfoCsReq");
      require(number(report(second, 11000), "retcode") != 0, "Backwards score accepted");
      require(snapshot.equals(call("GetCurBattleInfoCsReq")), "Rejected score consumed battle");
      require(number(report(second, 40000), "retcode") == 0, "Final half rejected");
      require(number(report(second, 40000), "retcode") != 0, "Repeated final half accepted");
      require(
          settlement != null
              && number(settlement, "challenge_id") == 20014
              && Boolean.TRUE.equals(field(settlement, "is_win"))
              && number(settlement, "challenge_score") == 12000
              && number(settlement, "score_two") == 28000
              && number(settlement, "star") == 1,
          "Wrong actual settlement");
      require(number(call("LeaveChallengeCsReq"), "retcode") == 0, "Story leave rejected");
      require(number(call("LeaveChallengeCsReq"), "retcode") != 0, "Repeated leave accepted");
    }
    var saved = history();
    require(
        number(saved, "star") == 1
            && number(saved, "score_id") == 12000
            && number(saved, "score_two") == 28000,
        "Stored half scores/mask changed");
    var result =
        new LinkedHashMap<String, Object>(
            Map.of(
                "passed",
                true,
                "uid",
                uid,
                "resume",
                resume,
                "nativeRequests",
                requests,
                "score",
                40000,
                "starMask",
                1,
                "sourceChallenge",
                20014,
                "actualHalfVictories",
                resume ? 0 : 2,
                "clientGameplayVerified",
                false,
                "combatInputReplayVerified",
                false));
    result.put("directEntriesRejected", resume ? 0 : 2);
    result.put("scoreOne", number(saved, "score_id"));
    result.put("scoreTwo", number(saved, "score_two"));
    return result;
  }

  public static void main(String[] args) {
    var probe = new NativeStoryChallengeRoundLimitProbe();
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
                  var f = PacketCodec.decodeNext(bytes);
                  require(f != null, "Truncated frame");
                  probe.inbox.add(new Frame(f.cmdId(), f.body()));
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
