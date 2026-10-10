package org.hkrpg.server.protocol;

import com.google.gson.*;
import com.google.protobuf.*;
import emu.lunarcore.server.packet.PacketCodec;
import emu.lunarcore.server.packet.Retcode;
import io.netty.buffer.*;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import kcp.highway.*;

/** External fixture probe: source orb interaction and automatically accepted original raid goal. */
public final class NativeRaidPropInteractionProbe {
  private record Frame(int command, byte[] body) {}

  private final ProtocolBridge protocol = ProtocolBridge.forVersion("4.6.0");
  private final BlockingQueue<Frame> inbox = new LinkedBlockingQueue<>();
  private final AtomicReference<Throwable> failure = new AtomicReference<>();
  private final CompletableFuture<Ukcp> connected = new CompletableFuture<>();
  private Ukcp channel;
  private int requests, reports, rejected, victories;

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

  private DynamicMessage prop(DynamicMessage scene, JsonObject goal) {
    DynamicMessage found = null;
    for (var raw : (List<?>) field(scene, "entity_group_list"))
      for (var item : (List<?>) field((DynamicMessage) raw, "entity_list")) {
        var entity = (DynamicMessage) item;
        if (number(entity, "group_id") != goal.get("group").getAsInt()
            || number(entity, "inst_id") != goal.get("instance").getAsInt()) continue;
        require(
            found == null && entity.hasField(entity.getDescriptorForType().findFieldByName("prop")),
            "Missing or duplicate original prop");
        require(
            number((DynamicMessage) field(entity, "prop"), "prop_id")
                == goal.get("prop").getAsInt(),
            "Source prop was replaced");
        found = entity;
      }
    require(found != null, "Original orb was not loaded");
    return found;
  }

  private static int state(DynamicMessage entity) {
    return number((DynamicMessage) field(entity, "prop"), "prop_state");
  }

  private void talk(int id) throws Exception {
    var request = builder("FinishTalkMissionCsReq");
    set(request, "sub_mission_id", id);
    set(request, "talk_str", "Mission_" + id);
    require(
        number(call("FinishTalkMissionCsReq", request), "retcode") == 0,
        "Original talk rejected " + id);
  }

  private void victory(int eventId) throws Exception {
    var event = builder("SceneEnterStageCsReq");
    set(event, "event_id", eventId);
    var entered = call("SceneEnterStageCsReq", event);
    require(number(entered, "retcode") == 0, "Original battle rejected " + eventId);
    var battle = (DynamicMessage) field(entered, "battle_info");
    require(
        !((List<?>) field(battle, "monster_wave_list")).isEmpty(), "Missing original battle waves");
    var result = builder("PVEBattleResultCsReq");
    set(result, "battle_id", field(battle, "battle_id"));
    set(result, "stage_id", field(battle, "stage_id"));
    var status = result.getDescriptorForType().findFieldByName("end_status");
    result.setField(status, status.getEnumType().findValueByName("BATTLE_END_WIN"));
    var stats = builder("BattleStatistics");
    set(stats, "round_cnt", 1);
    set(result, "stt", stats.build());
    require(
        number(call("PVEBattleResultCsReq", result), "retcode") == 0,
        "Actual battle WIN rejected " + eventId);
    victories++;
    require(
        number(call("PVEBattleResultCsReq", result), "retcode") != 0,
        "Duplicate battle WIN accepted");
  }

  private void originalRequiredGoals() throws Exception {
    var original = new JsonArray();
    for (int id :
        List.of(
            104040830, 104040834, 104040829, 104040801, 104040802, 104040806, 104040807, 104040832,
            104040833, 104040809, 104040831, 104040818, 104040823, 104040821, 104040822,
            104040824)) {
      var goal = new JsonObject();
      goal.addProperty("id", id);
      original.add(goal);
    }
    goals(original, true);
  }

  private DynamicMessage interaction(int entity, int config) throws Exception {
    var request = builder("InteractPropCsReq");
    set(request, "prop_entity_id", entity);
    set(request, "interact_id2", config);
    set(request, "interact_id", 1L << 40);
    return call("InteractPropCsReq", request);
  }

  private void goals(JsonArray goals, boolean finished) throws Exception {
    var request = builder("GetMissionStatusCsReq");
    for (var raw : goals)
      request.addRepeatedField(
          request.getDescriptorForType().findFieldByName("sub_mission_id_list"),
          raw.getAsJsonObject().get("id").getAsInt());
    var reply = call("GetMissionStatusCsReq", request);
    require(number(reply, "retcode") == 0, "Mission query rejected");
    var seen = new HashSet<Integer>();
    for (var raw : (List<?>) field(reply, "sub_mission_status_list")) {
      var row = (DynamicMessage) raw;
      int id = number(row, "id");
      require(seen.add(id), "Duplicate objective");
      require(
          number(row, "progress") == (finished ? 1 : 0)
              && number(row, "status") == (finished ? 2 : 1),
          "Incorrect original objective progress " + id);
    }
    require(seen.size() == goals.size(), "Missing original goals");
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
    String mode = control.get("mode").getAsString();
    require(
        Set.of(
                "enter",
                "resume",
                "pause",
                "return",
                "finish",
                "finished_resume",
                "leave",
                "home_resume")
            .contains(mode),
        "Unknown fixture mode");
    if (mode.equals("home_resume")) {
      require(
          number((DynamicMessage) field(call("GetCurSceneInfoCsReq"), "scene"), "entry_id")
              == control.get("homeEntry").getAsInt(),
          "Finished return did not restore home");
      originalRequiredGoals();
      boolean recorded = false;
      for (var raw : (List<?>) field(call("GetRaidInfoCsReq"), "finished_raid_info_list"))
        if (number((DynamicMessage) raw, "raid_id") == 40431) recorded = true;
      require(recorded, "Finished raid missing after normal restart at home");
      for (var raw : (List<?>) field(call("GetAvatarDataCsReq"), "avatar_list"))
        require(
            number((DynamicMessage) raw, "base_avatar_id") != 1409,
            "Trial became an owned formal avatar");
      return Map.of(
          "passed",
          true,
          "uid",
          uid,
          "mode",
          mode,
          "nativeRequests",
          requests,
          "actualPropInteractions",
          0,
          "invalidInteractionRejections",
          0,
          "actualBattleVictories",
          0,
          "clientGameplayVerified",
          false);
    }
    if (mode.equals("enter") || mode.equals("return")) {
      var request = builder("StartRaidCsReq");
      set(request, "raid_id", 40431);
      if (mode.equals("enter"))
        require(
            number(call("StartRaidCsReq", request), "retcode")
                == Retcode.RAID_AVATAR_NOT_MATCH.getVal(),
            "Missing role bypassed original team limit");
      var selected = request.getDescriptorForType().findFieldByName("NIMDEPFJJNI");
      var member = DynamicMessage.newBuilder(selected.getMessageType());
      set(member, "id", 1021409);
      var type = member.getDescriptorForType().findFieldByName("avatar_type");
      member.setField(type, type.getEnumType().findValueByName("AvatarType_AvatarTrialType"));
      request.addRepeatedField(selected, member.build());
      require(
          number(call("StartRaidCsReq", request), "retcode") == 0,
          "Original Default trial entry rejected");
      require(
          number(call("StartRaidCsReq", request), "retcode") == Retcode.RAID_DOING.getVal(),
          "Duplicate entry replaced run");
    }
    if (mode.equals("enter")) {
      var first = (DynamicMessage) field(call("GetCurSceneInfoCsReq"), "scene");
      require(number(first, "entry_id") == 40431001, "Wrong original starting floor");
      var preceding = new JsonArray();
      var stageGoal = new JsonObject();
      stageGoal.addProperty("id", 104040830);
      preceding.add(stageGoal);
      goals(preceding, false);
      victory(20431070);
      goals(preceding, true);
      var talk = builder("FinishTalkMissionCsReq");
      set(talk, "sub_mission_id", 104040834);
      set(talk, "talk_str", "Mission_104040834");
      require(
          number(call("FinishTalkMissionCsReq", talk), "retcode") == 0,
          "Original transfer talk rejected");
    }
    if (mode.equals("enter")) {
      talk(104040801);
      talk(104040802);
      talk(104040806);
    }
    var current = scene(control);
    var targets = control.getAsJsonArray("goals");
    goals(targets, !mode.equals("enter"));
    var original = targets.get(0).getAsJsonObject();
    var entity = prop(current, original);
    if (mode.equals("enter")) {
      require(state(entity) == 0, "Original orb state or completion was prewritten");
      require(
          number(interaction(number(entity, "entity_id"), 0), "retcode") != 0,
          "Missing source interaction config accepted");
      rejected++;
      require(
          number(interaction(number(entity, "entity_id") + 100000, 1010), "retcode") != 0,
          "Missing scene actor accepted");
      rejected++;
      goals(targets, false);
      var changed = interaction(number(entity, "entity_id"), 1010);
      require(
          number(changed, "retcode") == 0 && number(changed, "prop_state") == 1,
          "Source orb interaction rejected");
      reports++;
      require(
          number(interaction(number(entity, "entity_id"), 1010), "retcode") != 0,
          "Duplicate closed-to-open interaction accepted");
      rejected++;
    }
    goals(targets, true);
    current = scene(control);
    require(state(prop(current, original)) == 1, "Original orb transition did not restore");
    if (mode.equals("finish")) {
      for (int id :
          List.of(104040832, 104040833, 104040809, 104040831, 104040818, 104040823, 104040821))
        talk(id);
      victory(20431080);
      talk(104040824);
    }
    boolean finished = Set.of("finish", "finished_resume", "leave").contains(mode);
    if (finished) originalRequiredGoals();
    var party = (List<?>) field(call("GetAllLineupDataCsReq"), "lineup_list");
    boolean selectedTrial = false;
    for (var raw : party) {
      var lineup = (DynamicMessage) raw;
      for (var a : (List<?>) field(lineup, "avatar_list")) {
        var avatar = (DynamicMessage) a;
        if (number(avatar, "id") == 1021409) {
          require(
              ((com.google.protobuf.Descriptors.EnumValueDescriptor) field(avatar, "avatar_type"))
                  .getName()
                  .equals("AvatarType_AvatarTrialType"),
              "Trial type changed");
          selectedTrial = true;
        }
      }
    }
    require(selectedTrial, "Selected trial missing from native party");
    for (var raw : (List<?>) field(call("GetAvatarDataCsReq"), "avatar_list"))
      require(
          number((DynamicMessage) raw, "base_avatar_id") != 1409,
          "Trial granted an owned formal role");
    if (mode.equals("pause") || mode.equals("leave")) {
      var leave = builder("LeaveRaidCsReq");
      set(leave, "raid_id", 40431);
      require(number(call("LeaveRaidCsReq", leave), "retcode") == 0, "Pause rejected");
      require(
          number((DynamicMessage) field(call("GetCurSceneInfoCsReq"), "scene"), "entry_id")
              == control.get("homeEntry").getAsInt(),
          "Did not restore home");
    }
    var completed = (List<?>) field(call("GetRaidInfoCsReq"), "finished_raid_info_list");
    boolean recorded = false;
    for (var row : completed) if (number((DynamicMessage) row, "raid_id") == 40431) recorded = true;
    require(
        recorded == finished, "Original raid completion record does not match source finish goal");
    return Map.of(
        "passed",
        true,
        "uid",
        uid,
        "mode",
        mode,
        "nativeRequests",
        requests,
        "actualPropInteractions",
        reports,
        "invalidInteractionRejections",
        rejected,
        "actualBattleVictories",
        victories,
        "clientGameplayVerified",
        false);
  }

  public static void main(String[] args) {
    var probe = new NativeRaidPropInteractionProbe();
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
