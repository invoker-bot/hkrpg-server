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

/** Source-bound1407 Free direct [1,2] through real KCP; every reply is recorded before assertions. */
public final class NativeMazeCastoriceAbilityProbe {
  private static final String BASE = "LocalPlayer_Castorice_MazeSkill";
  private static final String INPUT = "LocalPlayer_Castorice_MazeSkill_Input";
  private static final String INPUT_DEATH_OUTCOME = "TRIGGER_BATTLE";
  private static final int TRANSPORT_IDLE_TIMEOUT_MS = 25000;
  @FunctionalInterface private interface CheckedAction { void run() throws Exception; }
  @FunctionalInterface private interface CheckedSleep { void sleep(long milliseconds) throws Exception; }
  private static void awaitOriginalBuffExpiry(long duration, CheckedAction heartbeat, CheckedSleep sleeper) throws Exception {
    long remaining = duration;
    while (remaining > 0) {
      long chunk = Math.min(remaining, TRANSPORT_IDLE_TIMEOUT_MS / 4);
      sleeper.sleep(chunk);
      remaining -= chunk;
      heartbeat.run(); // An actual observed read-only lineup query in the live probe.
    }
  }
  private static void verifyExpiryWait() throws Exception {
    long[] clock = {0, 0, 0, 0};
    awaitOriginalBuffExpiry(21000, () -> { clock[1] = clock[0]; clock[3]++; }, milliseconds -> {
      clock[0] += milliseconds; clock[2] = Math.max(clock[2], clock[0] - clock[1]);
    });
    require(clock[0] == 21000, "Original parameter20-second buff expiry wait was shortened");
    require(clock[2] < TRANSPORT_IDLE_TIMEOUT_MS && clock[3] >= 2,
        "Source expiry wait exceeded the actual declared KCP idle timeout without read-only heartbeat: " + clock[2]);
    System.out.println(new Gson().toJson(Map.of("passed", true, "waitMilliseconds", clock[0],
        "maximumRequestIdleMilliseconds", clock[2], "declaredKcpIdleTimeoutMilliseconds", TRANSPORT_IDLE_TIMEOUT_MS,
        "readOnlyHeartbeats", clock[3], "nativeExecuted", false)));
  }
  private record Frame(int command, byte[] body) {}
  private final ProtocolBridge protocol = ProtocolBridge.forVersion("4.6.0");
  private final BlockingQueue<Frame> inbox = new LinkedBlockingQueue<>();
  private final AtomicReference<Throwable> failure = new AtomicReference<>();
  private final CompletableFuture<Ukcp> connected = new CompletableFuture<>();
  private final List<String> checks = new ArrayList<>();
  private final List<Map<String, Object>> replies = new ArrayList<>(), events = new ArrayList<>(), notifications = new ArrayList<>();
  private final List<Map<String, Object>> removals = new ArrayList<>();
  private final Map<String, Map<String, Integer>> schemas = new LinkedHashMap<>();
  private Ukcp channel;
  private java.io.BufferedReader control;
  private int requests, mpUpdates, sceneEntry = 2000101;

  private static Object field(DynamicMessage message, String name) {
    return message.getField(Objects.requireNonNull(message.getDescriptorForType().findFieldByName(name), name));
  }
  private static int number(DynamicMessage message, String name) { return ((Number) field(message, name)).intValue(); }
  private static void require(boolean ok, String why) { if (!ok) throw new IllegalStateException(why); }
  private void check(boolean ok, String why) { require(ok, why); checks.add(why); }
  private void schema(String name, String... names) {
    var type = Objects.requireNonNull(protocol.clientSchema().message(name), name);
    var fields = new LinkedHashMap<String, Integer>();
    for (var key : names) fields.put(key, Objects.requireNonNull(type.findFieldByName(key), name + "." + key).getNumber());
    schemas.put(name, fields);
  }
  private void schemaOf(String key, Descriptors.Descriptor type, String... names) {
    var fields = new LinkedHashMap<String, Integer>();
    for (var name : names) fields.put(name, Objects.requireNonNull(type.findFieldByName(name), key + "." + name).getNumber());
    schemas.put(key, fields);
  }
  private void verifySchemas() {
    schema("SceneCastSkillCsReq", "cast_entity_id", "attacked_by_entity_id", "skill_index", "maze_ability_str", "skill_extra_tags", "hit_target_entity_id_list");
    schema("SceneCastSkillScRsp", "cast_entity_id", "retcode", "battle_info", "monster_battle_info");
    schema("SceneCastSkillMpUpdateScNotify", "cast_entity_id", "mp");
    schema("SyncEntityBuffChangeListScNotify", "entity_buff_change_list");
    schema("EntityBuffChangeInfo", "entity_id", "remove_buff_id");
    schema("PVEBattleResultCsReq", "battle_id", "stage_id", "end_status");
    schema("LineupSlotData", "id", "slot", "avatar_type");
    schema("PlayerGetTokenCsReq", "account_uid", "token", "platform");
    schema("ReplaceLineupCsReq", "index", "lineup_slot_list");
    schema("ChangeLineupLeaderCsReq", "slot");
    schema("EnterSceneCsReq", "entry_id");
    schema("SceneInfo", "entry_id", "plane_id", "floor_id", "entity_group_list");
    var groupType = protocol.clientSchema().message("SceneInfo").findFieldByName("entity_group_list").getMessageType();
    schemaOf("SceneInfo/entity_group_list", groupType, "entity_list");
    var entityType = groupType.findFieldByName("entity_list").getMessageType();
    schemaOf("SceneInfo/entity_group_list/entity_list", entityType, "entity_id", "group_id", "inst_id", "actor", "npc_monster", "prop");
    schemaOf("SceneInfo/actor", entityType.findFieldByName("actor").getMessageType(), "base_avatar_id");
    var lineupType = protocol.clientSchema().message("GetCurLineupDataScRsp").findFieldByName("lineup").getMessageType();
    schemaOf("GetCurLineupDataScRsp/lineup", lineupType, "index", "mp", "avatar_list");
    schemaOf("lineup/avatar_list", lineupType.findFieldByName("avatar_list").getMessageType(), "id");
    schemaOf("SceneCastSkillScRsp/monster_battle_info", protocol.clientSchema().message("SceneCastSkillScRsp")
        .findFieldByName("monster_battle_info").getMessageType(), "target_monster_entity_id", "monster_battle_type");
    var request = protocol.clientSchema().message("SceneCastSkillCsReq");
    check(protocol.clientCommand("SceneCastSkillCsReq") == 1460, "verified-native-cast-command-id-with-no-base-payment-request");
    check(schemas.get("SceneCastSkillCsReq").get("cast_entity_id") == 13
        && request.findFieldByName("cast_entity_id").getType() == Descriptors.FieldDescriptor.Type.UINT32,
        "verified-uint-useid-field13-without-caster-alias");
    check(schemas.get("SceneCastSkillCsReq").get("maze_ability_str") == 15
        && schemas.get("SceneCastSkillCsReq").get("skill_index") == 4
        && schemas.get("SceneCastSkillCsReq").get("skill_extra_tags") == 11,
        "verified-original-manual-name-field15-slot4-and-event11");
    check(request.findFieldByNumber(8) != null && !request.findFieldByNumber(8).getName().equals("cost_mp"), "unknown-field8-is-not-used-as-a-cost");
    var avatar = protocol.clientSchema().message("LineupSlotData").findFieldByName("avatar_type").getEnumType();
    check(avatar.findValueByName("AvatarType_AvatarFormalType").getNumber() == 3, "native-formal-avatar-enum3");
    var end = protocol.clientSchema().message("PVEBattleResultCsReq").findFieldByName("end_status").getEnumType();
    check(end.findValueByName("BATTLE_END_WIN") != null, "native-win-enum-exists");
    var battle = protocol.clientSchema().message("SceneCastSkillScRsp").findFieldByName("battle_info").getMessageType();
    for (var key : List.of("battle_id", "stage_id", "monster_wave_list", "buff_list"))
      check(battle.findFieldByName(key) != null, "actual-battle-field-" + key);
    var buff = battle.findFieldByName("buff_list").getMessageType();
    check(buff.findFieldByName("id") != null, "actual-source-battle-buff-id-field");
    var outcomeType = protocol.clientSchema().message("SceneCastSkillScRsp").findFieldByName("monster_battle_info")
        .getMessageType().findFieldByName("monster_battle_type").getEnumType();
    var death = outcomeType.findValueByName("MONSTER_BATTLE_TYPE_" + INPUT_DEATH_OUTCOME);
    check(death != null && death.getNumber() == 1, "native-trigger-battle-enum1");
  }
  private void observe(Frame frame) throws Exception {
    if (frame.command() == protocol.clientCommand("SyncEntityBuffChangeListScNotify")) {
      var data = protocol.clientSchema().parse("SyncEntityBuffChangeListScNotify", frame.body());
      var observed = new LinkedHashMap<String, Object>(Map.of("command", frame.command(),
          "rawBase64", Base64.getEncoder().encodeToString(frame.body())));
      var rows = new ArrayList<Map<String, Object>>();
      for (var raw : (List<?>) field(data, "entity_buff_change_list")) {
        var row = (DynamicMessage) raw;
        var remove = row.getDescriptorForType().findFieldByName("remove_buff_id");
        if (row.hasField(remove)) {
          var event = Map.<String, Object>of("entity", number(row, "entity_id"), "buff", number(row, "remove_buff_id"));
          removals.add(event); rows.add(event);
        }
      }
      observed.put("removals", rows);
      System.out.println("OBSERVED_BUFF_REMOVAL=" + new Gson().toJson(observed));
      require(data.getUnknownFields().asMap().isEmpty(), "Unknown source-buff removal native fields");
      return;
    }
    if (frame.command() != protocol.clientCommand("SceneCastSkillMpUpdateScNotify")) return;
    var data = protocol.clientSchema().parse("SceneCastSkillMpUpdateScNotify", frame.body());
    var event = new LinkedHashMap<String, Object>(Map.of("useID", number(data, "cast_entity_id"), "mp", number(data, "mp")));
    event.put("rawBase64", Base64.getEncoder().encodeToString(frame.body()));
    notifications.add(event);
    System.out.println("OBSERVED_MP=" + new Gson().toJson(event));
    require(data.getUnknownFields().asMap().isEmpty(), "Unknown MP notification fields");
    mpUpdates++;
  }
  private DynamicMessage lineup() throws Exception { return (DynamicMessage) field(call("GetCurLineupDataCsReq"), "lineup"); }
  private int mp() throws Exception { return number(lineup(), "mp"); }
  private DynamicMessage scene() throws Exception {
    var result = (DynamicMessage) field(call("GetCurSceneInfoCsReq"), "scene");
    require(number(result, "entry_id") == sceneEntry, "Different controlled scene entry");
    return result;
  }
  private int actor(int avatar) throws Exception {
    int found = 0;
    for (var group : (List<?>) field(scene(), "entity_group_list"))
      for (var raw : (List<?>) field((DynamicMessage) group, "entity_list")) {
        var entity = (DynamicMessage) raw;
        var type = entity.getDescriptorForType().findFieldByName("actor");
        if (entity.hasField(type) && number((DynamicMessage) field(entity, "actor"), "base_avatar_id") == avatar) {
          require(found == 0, "Duplicate actual scene caster");
          found = number(entity, "entity_id");
        }
      }
    require(found > 0, "Missing actual scene caster " + avatar);
    return found;
  }
  private int target(int instance, String kind) throws Exception {
    int found = 0;
    for (var group : (List<?>) field(scene(), "entity_group_list"))
      for (var raw : (List<?>) field((DynamicMessage) group, "entity_list")) {
        var entity = (DynamicMessage) raw;
        if (number(entity, "group_id") == 91 && number(entity, "inst_id") == instance) {
          require(found == 0 && entity.hasField(entity.getDescriptorForType().findFieldByName(kind)), "Different controlled target type");
          found = number(entity, "entity_id");
        }
      }
    require(found > 0, "Missing controlled instance " + instance);
    return found;
  }
  private DynamicMessage cast(int use, int caster, String name, int index, int[] tags, int[] instances,
      String kind, int status, int delta, int notices, String outcome, boolean battle) throws Exception {
    int before = mp(), updates = mpUpdates, removed = removals.size();
    var targets = new ArrayList<Integer>();
    for (int instance : instances) targets.add(target(instance, kind));
    var values = new HashMap<String, Object>(Map.of("cast_entity_id", use, "attacked_by_entity_id", caster, "skill_index", index));
    if (!name.isEmpty()) values.put("maze_ability_str", name);
    if (!targets.isEmpty()) values.put("hit_target_entity_id_list", targets);
    var enumType = protocol.clientSchema().message("SceneCastSkillCsReq").findFieldByName("skill_extra_tags").getEnumType();
    var encoded = new ArrayList<Descriptors.EnumValueDescriptor>();
    for (int tag : tags) encoded.add(enumType.findValueByNumberCreatingIfUnknown(tag));
    values.put("skill_extra_tags", encoded);
    var response = callReply("SceneCastSkillCsReq", values);
    int after = mp();
    boolean hasBattle = response.hasField(response.getDescriptorForType().findFieldByName("battle_info"));
    var rows = new ArrayList<Map<String, Object>>();
    for (var raw : (List<?>) field(response, "monster_battle_info")) {
      var row = (DynamicMessage) raw;
      rows.add(Map.of("entity", number(row, "target_monster_entity_id"), "outcome",
          ((Descriptors.EnumValueDescriptor) field(row, "monster_battle_type")).getName()));
    }
    var event = new LinkedHashMap<String, Object>();
    event.putAll(Map.of("request", 1460, "useID", use, "casterEntity", caster, "skillIndex", index,
        "abilityName", name, "tags", Arrays.stream(tags).boxed().toList(), "targetInstances", Arrays.stream(instances).boxed().toList(),
        "targetEntities", targets, "status", number(response, "retcode")));
    event.put("mpBefore", before); event.put("mpAfter", after); event.put("mpNotificationDelta", mpUpdates - updates);
    event.put("hasBattle", hasBattle); event.put("targetResults", rows); event.put("expectedStatus", status);
    event.put("sourceBuffRemovals", new ArrayList<>(removals.subList(removed, removals.size())));
    events.add(event); System.out.println("MANUAL_OBSERVATION=" + new Gson().toJson(event));
    check(number(response, "retcode") == status, "manual-action-status-" + use + "-" + name + "-" + Arrays.toString(tags));
    check(number(response, "cast_entity_id") == use && use != caster, "manual-action-echo-is-new-useid-" + use);
    check(after == before + delta && mpUpdates == updates + notices, "actual-cost-and-notification-per-attack-" + use + "-" + Arrays.toString(tags));
    if (notices > 0) check(numberFrom(notifications.getLast(), "useID") == use && numberFrom(notifications.getLast(), "mp") == after,
        "unpaid-base-compatibility-notification-owns-its-useid-" + use);
    check(hasBattle == battle, "actual-battle-admission-" + use);
    long radiusRemoved = removals.subList(removed, removals.size()).stream()
        .filter(row -> numberFrom(row, "entity") == caster && numberFrom(row, "buff") == 140704).count();
    check(radiusRemoved == (name.equals(INPUT) && status == 0 ? 1 : 0),
        "actual-radius140704-removal-once-only-after-accepted-input-" + use);
    if (status == 0 && kind.equals("npc_monster")) {
      check(rows.size() == targets.size(), "one-actual-result-per-manual-target-" + use);
      for (int i = 0; i < rows.size(); i++) check(rows.get(i).get("entity").equals(targets.get(i))
          && rows.get(i).get("outcome").equals("MONSTER_BATTLE_TYPE_" + outcome), "actual-manual-target-outcome-" + use + "-" + i);
    }
    if (battle) {
      var data = (DynamicMessage) field(response, "battle_info");
      var buffs = new ArrayList<Integer>();
      for (var raw : (List<?>) field(data, "buff_list")) buffs.add(number((DynamicMessage) raw, "id"));
      event.put("observedBattleBuffIDs", buffs);
      System.out.println("OBSERVED_MANUAL_BATTLE=" + new Gson().toJson(event));
      check(buffs.contains(140701), "actual-original-onbattle140701-frozen-at-entry-" + use);
      check(!buffs.contains(140704), "actual-consumed-caster-radius140704-is-excluded-from-battle-snapshot-" + use);
    } else callCode("GetCurBattleInfoCsReq", Map.of(), 1);
    return response;
  }
  private static int numberFrom(Map<String, Object> map, String name) { return ((Number) map.get(name)).intValue(); }
  private DynamicMessage input(int use, int status, boolean battle, int... targets) throws Exception {
    return cast(use, actor(1407), INPUT, 1, new int[] {1, 2}, targets, "npc_monster", status,
        0, 0, "TRIGGER_BATTLE", battle);
  }
  private void release(int use) throws Exception {
    cast(use, actor(1407), BASE, 1, new int[] {0}, new int[] {}, "npc_monster", 0, -1, 1, "NO_BATTLE", false);
  }
  private void recover(int instance, int use) throws Exception {
    cast(use, actor(8001), "", 0, new int[] {}, new int[] {instance}, "prop", 0, 2, 0, "NO_BATTLE", false);
  }
  private void sourceGate(String action) throws Exception {
    require(action.equals("hide") || action.equals("restore"), "Unknown owned source control");
    System.out.println("SOURCE_CONTROL=" + requests + ":" + action); System.out.flush();
    require(("SOURCE_ACK=" + action).equals(control.readLine()), "Owned original LocalPlayer source control was not acknowledged");
    events.add(Map.of("ownedSourceControl", action, "afterNativeRequests", requests,
        "source", "Config/ConfigCharacter/LocalPlayer/LocalPlayer_Castorice_00_Config.json"));
  }
  private void settle(JsonObject input, DynamicMessage reply) throws Exception {
    var battle = (DynamicMessage) field(reply, "battle_info");
    int id = number(battle, "battle_id"), stage = number(battle, "stage_id");
    check(id > 0 && stage == input.get("stage").getAsInt(), "original-actual-admitted-stage-" + id);
    var waves = new ArrayList<List<Integer>>();
    for (var raw : (List<?>) field(battle, "monster_wave_list")) {
      var wave = (DynamicMessage) raw;
      require(number(wave, "battle_stage_id") == stage, "Different battle wave source stage");
      var monsters = new ArrayList<Integer>();
      for (var row : (List<?>) field(wave, "monster_list")) monsters.add(number((DynamicMessage) row, "monster_id"));
      waves.add(monsters);
    }
    var expected = new ArrayList<List<Integer>>();
    for (var raw : input.getAsJsonArray("waves")) {
      var wave = new ArrayList<Integer>();
      for (var row : raw.getAsJsonArray()) wave.add(row.getAsInt());
      expected.add(wave);
    }
    var complete = new ArrayList<List<Integer>>();
    for (int i = 0; i < ((List<?>) field(reply, "monster_battle_info")).size(); i++) complete.addAll(expected);
    check(waves.equals(complete), "complete-original-stage-waves-per-actual-npc-" + id);
    var current = (DynamicMessage) field(call("GetCurBattleInfoCsReq"), "battle_info");
    check(number(current, "battle_id") == id, "actual-source-battle-is-current-" + id);
    var end = protocol.clientSchema().message("PVEBattleResultCsReq").findFieldByName("end_status").getEnumType().findValueByName("BATTLE_END_WIN");
    var win = Map.<String, Object>of("battle_id", id, "stage_id", stage, "end_status", end);
    call("PVEBattleResultCsReq", win); callCode("PVEBattleResultCsReq", win, 1); callCode("GetCurBattleInfoCsReq", Map.of(), 1);
    check(true, "actual-win-and-repeat-report-rejection-" + id);
  }
  private void roster() throws Exception {
    int index = number(lineup(), "index");
    var type = protocol.clientSchema().message("LineupSlotData");
    var slots = new ArrayList<DynamicMessage>();
    var ids = List.of(8001, 1407);
    for (int i = 0; i < ids.size(); i++) {
      var row = DynamicMessage.newBuilder(type);
      row.setField(type.findFieldByName("id"), ids.get(i)); row.setField(type.findFieldByName("slot"), i);
      row.setField(type.findFieldByName("avatar_type"), type.findFieldByName("avatar_type").getEnumType().findValueByName("AvatarType_AvatarFormalType"));
      slots.add(row.build());
    }
    requests++;
    var frame = raw(protocol.clientCommand("ReplaceLineupCsReq"), message("ReplaceLineupCsReq", Map.of("index", index, "lineup_slot_list", slots)).toByteArray(), protocol.clientCommand("JoinLineupScRsp"));
    var reply = UnknownFieldSet.parseFrom(frame.body());
    var event = Map.<String, Object>of("request", "ReplaceLineupCsReq", "observedResponse", "JoinLineupScRsp", "rawBase64", Base64.getEncoder().encodeToString(frame.body()), "unknownNativeFields", reply.asMap().keySet());
    replies.add(event); System.out.println("OBSERVED_REPLY=" + new Gson().toJson(event));
    require(Set.of(7, 9).containsAll(reply.asMap().keySet()) && (!reply.hasField(9) || reply.getField(9).getVarintList().equals(List.of(0L))), "Native formal replacement rejected");
    var actual = new ArrayList<Integer>();
    for (var row : (List<?>) field(lineup(), "avatar_list")) actual.add(number((DynamicMessage) row, "id"));
    check(actual.equals(ids), "actual-owned-formal-playerboy-and-castorice-roster");
    call("ChangeLineupLeaderCsReq", Map.of("slot", 1));
  }
  private Map<String, Object> execute(JsonObject input) throws Exception {
    verifySchemas();
    String mode = input.get("mode").getAsString();
    require(Set.of("sequence", "new-session", "restored").contains(mode), "Unknown Castorice mode");
    int uid = number(call("PlayerGetTokenCsReq", Map.of("account_uid", input.get("accountUid").getAsString(), "token", input.get("comboToken").getAsString(), "platform", 1)), "uid");
    call("PlayerLoginCsReq"); call("PlayerLoginFinishCsReq");
    if (mode.equals("sequence")) {
      System.out.println("READY_UID=" + uid); System.out.flush();
      boolean owned = false;
      for (int attempt = 0; attempt < 120 && !owned; attempt++) {
        for (var row : (List<?>) field(call("GetAvatarDataCsReq"), "avatar_list")) owned |= number((DynamicMessage) row, "base_avatar_id") == 1407;
        if (!owned) Thread.sleep(200);
      }
      require(owned, "Declared isolated formal1407 grant is missing");
      roster(); check(mp() == 5, "initial-five-native-technique-points");
      input(97101, 5, false, 200101);
      input(0, 5, false); input(-1, 5, false);
      release(97100);
      input(97100, 5, false);
      for (int[] tags : List.of(new int[] {1}, new int[] {2}, new int[] {0}, new int[] {99},
          new int[] {2, 1}, new int[] {1, 1}))
        cast(97101, actor(1407), INPUT, 1, tags, new int[] {}, "npc_monster", 5, 0, 0, "NO_BATTLE", false);
      cast(97101, actor(1407), INPUT, 0, new int[] {1, 2}, new int[] {}, "npc_monster", 5, 0, 0, "NO_BATTLE", false);
      cast(97101, actor(1407), INPUT, 1, new int[] {2}, new int[] {}, "npc_monster", 5, 0, 0, "NO_BATTLE", false);
      cast(97101, actor(1407), "LocalPlayer_Castorice_MazeSkill_Out", 1, new int[] {1, 2}, new int[] {}, "npc_monster", 5, 0, 0, "NO_BATTLE", false);
      cast(97101, actor(8001), INPUT, 1, new int[] {1, 2}, new int[] {}, "npc_monster", 5, 0, 0, "NO_BATTLE", false);
      input(97101, 1, false, 200109);
      settle(input, input(97101, 0, true, 200101, 200102));
      input(97101, 5, false); input(97102, 5, false, 200103);
      check(mp() == 4 && mpUpdates == 1, "failed-stage-admission-spends-no-mp-and-same-input-useid-retries-on-real-stage");
      release(97110);
      input(97101, 5, false, 200103);
      sourceGate("hide"); input(97111, 5, false, 200103); sourceGate("restore");
      settle(input, input(97111, 0, true, 200103));
      input(97112, 5, false);
      check(mp() == 3 && mpUpdates == 2, "one-new-source-grants-one-new-input-and-old-child-cannot-borrow-it");
      release(97120);
      System.out.println("WAIT_ORIGINAL_BUFF_EXPIRY_SECONDS=20"); System.out.flush();
      long waitStarted = System.nanoTime();
      awaitOriginalBuffExpiry(21000, () -> call("GetCurLineupDataCsReq"), Thread::sleep);
      long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - waitStarted);
      var waited = Map.<String, Object>of("sourceBuff", 140704, "sourceLifetimeSeconds", 20,
          "observedExpiryWaitMilliseconds", elapsed, "transportIdleTimeoutMilliseconds", TRANSPORT_IDLE_TIMEOUT_MS,
          "keepAliveRequest", "GetCurLineupDataCsReq");
      events.add(waited); System.out.println("OBSERVED_EXPIRY_WAIT=" + new Gson().toJson(waited));
      check(elapsed >= 21000, "actual-source-expiry-wait-was-not-shortened-by-read-only-heartbeats");
      input(97121, 5, false, 200104);
      release(97130); input(97131, 0, false); input(97132, 5, false);
      release(97140);
      check(mp() == 0, "zero-native-mp-reached-by-five-real-unprepaid-base1460-releases");
      settle(input, input(97141, 0, true, 200104));
      input(97142, 5, false);
      check(mp() == 0 && mpUpdates == 5, "free-direct-input-at-zero-mp-has-no-extra-fee-or-notification");
      recover(300001, 97200); release(97150);
      int oldCaster = actor(1407);
      call("EnterSceneCsReq", Map.of("entry_id", 100000301)); sceneEntry = 100000301;
      var scene = scene();
      check(number(scene, "plane_id") == 10000 && number(scene, "floor_id") == 10000003, "actual-original-ungated-train-scene-transition");
      cast(97151, oldCaster, INPUT, 1, new int[] {1, 2}, new int[] {}, "npc_monster", 5, 0, 0, "NO_BATTLE", false);
      input(97151, 5, false); input(97141, 5, false);
      check(mp() == 1 && mpUpdates == 6, "six-real-base-releases-and-one-box-only-no1408");
    } else {
      sceneEntry = 100000301;
      check(mp() == (mode.equals("new-session") ? 1 : 0), "actual-saved-mp-after-normal-h2-restart-" + mode);
      input(97141, 5, false); input(97151, 5, false); input(97161, 5, false);
      if (mode.equals("new-session")) {
        release(97160); input(97161, 0, false); input(97162, 5, false);
        check(mp() == 0 && mpUpdates == 1, "new-session-needs-true-base-and-one-input-cleans-its-original-source-at-zero");
      } else {
        input(97171, 5, false);
        cast(97160, actor(1407), BASE, 1, new int[] {0}, new int[] {}, "npc_monster", 2213, 0, 0, "NO_BATTLE", false);
        check(mp() == 0 && mpUpdates == 0, "second-h2-restore-has-no-persisted-buff-origin-or-payment");
      }
    }
    int finalMp = mp();
    require(requests == replies.size(), "Every actual request, including final balance query, is counted");
    var result = new LinkedHashMap<>(Map.of("passed", true, "uid", uid, "mode", mode, "requests", requests,
        "checks", checks, "events", events, "observedReplies", replies, "notifications", notifications, "mp", finalMp, "requestSchemas", schemas));
    result.put("observedBuffRemovals", removals);
    return result;
  }
  private DynamicMessage message(String name, Map<String, Object> fields) {
    var builder = DynamicMessage.newBuilder(Objects.requireNonNull(protocol.clientSchema().message(name), name));
    fields.forEach((key, value) -> builder.setField(Objects.requireNonNull(builder.getDescriptorForType().findFieldByName(key), name + "." + key), value));
    return builder.build();
  }
  private Frame raw(int command, byte[] body, int response) throws Exception {
    var packet = Unpooled.wrappedBuffer(PacketCodec.encode(command, new byte[0], body));
    try { channel.write(packet); } finally { packet.release(); }
    long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
    while (System.nanoTime() < until) {
      if (failure.get() != null) throw new IllegalStateException("Native manual connection failed", failure.get());
      var frame = inbox.poll(1, TimeUnit.SECONDS);
      if (frame == null) continue;
      observe(frame); if (frame.command() == response) return frame;
    }
    throw new TimeoutException("Native manual response " + response);
  }
  private DynamicMessage callReply(String name, Map<String, Object> fields) throws Exception {
    requests++;
    String reply = name.replace("CsReq", "ScRsp");
    var frame = raw(protocol.clientCommand(name), message(name, fields).toByteArray(), protocol.clientCommand(reply));
    var result = protocol.clientSchema().parse(reply, frame.body());
    var event = new LinkedHashMap<String, Object>(Map.of("sequence", requests, "request", name, "observedResponse", reply,
        "rawBase64", Base64.getEncoder().encodeToString(frame.body())));
    if (result.getDescriptorForType().findFieldByName("retcode") != null) event.put("retcode", number(result, "retcode"));
    replies.add(event); System.out.println("OBSERVED_REPLY=" + new Gson().toJson(event));
    require(result.getUnknownFields().asMap().isEmpty(), "Unknown native reply fields " + reply);
    return result;
  }
  private DynamicMessage callCode(String name, Map<String, Object> fields, int status) throws Exception {
    var result = callReply(name, fields);
    require(number(result, "retcode") == status, "Unexpected " + name.replace("CsReq", "ScRsp") + " status=" + number(result, "retcode") + " expected=" + status);
    return result;
  }
  private DynamicMessage call(String name, Map<String, Object> fields) throws Exception { return callCode(name, fields, 0); }
  private DynamicMessage call(String name) throws Exception { return call(name, Map.of()); }
  public static void main(String[] args) {
    var probe = new NativeMazeCastoriceAbilityProbe();
    if (args.length == 1 && args[0].equals("--verify-expiry-wait")) {
      try { verifyExpiryWait(); } catch (Exception error) { throw new IllegalStateException(error); }
      return;
    }
    if (args.length == 1 && args[0].equals("--verify-descriptors")) {
      probe.verifySchemas();
      System.out.println(new Gson().toJson(Map.of("passed", true, "requestSchemas", probe.schemas, "checks", probe.checks,
          "nativeExecuted", false, "serverStarted", false)));
      return;
    }
    var client = new KcpClient(); int status = 1;
    try {
      probe.control = new java.io.BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
      var input = JsonParser.parseString(probe.control.readLine()).getAsJsonObject();
      var config = new ChannelConfig();
      config.nodelay(true, 40, 2, true); config.setMtu(1400); config.setSndwnd(256); config.setRcvwnd(256);
      config.setUseConvChannel(true); config.setConv(ThreadLocalRandom.current().nextInt(1, Integer.MAX_VALUE));
      config.setTimeoutMillis(TRANSPORT_IDLE_TIMEOUT_MS); config.setAckNoDelay(true);
      client.init(config, new KcpListener() {
        public void onConnected(Ukcp connection) { probe.connected.complete(connection); }
        public void handleReceive(ByteBuf bytes, Ukcp connection) {
          try {
            while (bytes.isReadable()) {
              var frame = PacketCodec.decodeNext(bytes); require(frame != null, "Truncated native manual frame");
              probe.inbox.add(new Frame(frame.cmdId(), frame.body()));
            }
          } catch (Throwable error) { probe.failure.set(error); }
        }
        public void handleException(Throwable error, Ukcp connection) { probe.failure.set(error); probe.connected.completeExceptionally(error); }
        public void handleClose(Ukcp connection) { probe.failure.compareAndSet(null, new IllegalStateException("Native manual probe disconnected")); }
      });
      client.connect(new InetSocketAddress("127.0.0.1", Integer.parseInt(args[0])), config);
      probe.channel = probe.connected.get(10, TimeUnit.SECONDS);
      System.out.println(new Gson().toJson(probe.execute(input))); status = 0;
    } catch (Exception error) {
      System.err.println("FAILED_MANUAL_OBSERVATIONS=" + new Gson().toJson(probe.events)); error.printStackTrace(System.err);
    } finally { client.stop(); }
    System.exit(status);
  }
}
