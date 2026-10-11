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

/** Real phase requests and admitted battle snapshots; source skills and controlled scene targets. */
public final class NativeMazeCallbackPhasesProbe {
  private record Frame(int command, byte[] body) {}

  private final ProtocolBridge protocol = ProtocolBridge.forVersion("4.6.0");
  private final BlockingQueue<Frame> inbox = new LinkedBlockingQueue<>();
  private final AtomicReference<Throwable> failure = new AtomicReference<>();
  private final CompletableFuture<Ukcp> connected = new CompletableFuture<>();
  private Ukcp channel;
  private int requests;

  private static Object field(DynamicMessage m, String n) {
    return m.getField(Objects.requireNonNull(m.getDescriptorForType().findFieldByName(n), n));
  }

  private static int number(DynamicMessage m, String n) {
    return (Integer) field(m, n);
  }

  private static void require(boolean ok, String why) {
    if (!ok) throw new IllegalStateException(why);
  }

  private void observe(Frame frame) {}

  private final List<String> checks = new ArrayList<>();
  private final List<Map<String, Object>> attackEvents = new ArrayList<>();
  private final Map<String, Map<String, Integer>> requestSchemas = new LinkedHashMap<>();
  private final List<Map<String, Object>> battles = new ArrayList<>();

  private void check(boolean ok, String why) {
    require(ok, why);
    checks.add(why);
  }


  private DynamicMessage scene() throws Exception {
    var value = (DynamicMessage) field(call("GetCurSceneInfoCsReq"), "scene");
    require(number(value, "entry_id") == 2000101, "Different isolated callback fixture entry");
    return value;
  }

  private DynamicMessage target(DynamicMessage scene, int inst) {
    DynamicMessage found = null;
    for (var group : (List<?>) field(scene, "entity_group_list"))
      for (var raw : (List<?>) field((DynamicMessage) group, "entity_list")) {
        var entity = (DynamicMessage) raw;
        if (number(entity, "group_id") == 91 && number(entity, "inst_id") == inst) {
          require(found == null, "Duplicate controlled scene target");
          require(entity.hasField(entity.getDescriptorForType().findFieldByName("npc_monster")), "Target is not an NPC");
          found = entity;
        }
      }
    require(found != null, "Missing actual controlled scene target " + inst);
    return found;
  }

  private DynamicMessage attack(int inst, int index, int[] tags, int code) throws Exception {
    var scene = scene();
    var values = new HashMap<String, Object>();
    values.put("cast_entity_id", field(scene, "leader_entity_id"));
    values.put("attacked_by_entity_id", field(scene, "leader_entity_id"));
    values.put("skill_index", index);
    values.put("hit_target_entity_id_list", List.of(number(target(scene, inst), "entity_id")));
    var tagType = protocol.clientSchema().message("SceneCastSkillCsReq").findFieldByName("skill_extra_tags").getEnumType();
    var events = new ArrayList<Descriptors.EnumValueDescriptor>();
    for (int tag : tags) events.add(tagType.findValueByNumberCreatingIfUnknown(tag));
    values.put("skill_extra_tags", events);
    var reply = callCode("SceneCastSkillCsReq", values, code);
    if (code == 0) {
      var rows = (List<?>) field(reply, "monster_battle_info");
      require(rows.size() == 1, "One actual targeted NPC result required");
      var row = (DynamicMessage) rows.getFirst();
      check(number(row, "target_monster_entity_id") == number(target(scene, inst), "entity_id")
          && ((Descriptors.EnumValueDescriptor) field(row, "monster_battle_type")).getName().equals("MONSTER_BATTLE_TYPE_TRIGGER_BATTLE"),
          "actual-npc-trigger-result-" + inst);
    } else {
      check(!reply.hasField(reply.getDescriptorForType().findFieldByName("battle_info")), "failure-has-no-battle-" + inst);
      callCode("GetCurBattleInfoCsReq", Map.of(), 1);
      check(true, "failure-does-not-register-an-encounter-" + inst);
    }
    attackEvents.add(Map.of("instance", inst, "skillIndex", index,
        "requestedTags", Arrays.stream(tags).boxed().toList(), "retcode", number(reply, "retcode"),
        "hasBattle", reply.hasField(reply.getDescriptorForType().findFieldByName("battle_info"))));
    return reply;
  }

  private static Map<String, Float> dynamicValues(DynamicMessage buff) {
    var values = new TreeMap<String, Float>();
    for (var raw : (List<?>) field(buff, "dynamic_values")) {
      var row = (DynamicMessage) raw;
      require(values.put((String) field(row, "key"), (Float) field(row, "value")) == null,
          "Duplicate battle buff dynamic key");
    }
    return values;
  }

  private static Map<String, Object> buffRow(DynamicMessage buff) {
    return Map.of("id", number(buff, "id"), "level", number(buff, "level"),
        "ownerIndex", number(buff, "owner_index"), "waveFlag", number(buff, "wave_flag"),
        "targetIndices", field(buff, "target_index_list"), "dynamicValues", dynamicValues(buff));
  }

  private void fireflyBuffSources(String name, List<DynamicMessage> buffs, int slot, boolean technique) {
    var fire = buffs.stream().filter(buff -> number(buff, "id") == 1000112).toList();
    var entry = fire.stream().filter(buff -> dynamicValues(buff).containsKey("SkillIndex")).toList();
    var snapshot = fire.stream().filter(buff -> dynamicValues(buff).isEmpty()).toList();
    // SkillAbilityInfo stores normal/technique as 1/2; the request selects them with 0/1.
    check(fire.size() == (technique ? 2 : 1) && entry.size() == 1
            && dynamicValues(entry.getFirst()).equals(Map.of("SkillIndex", technique ? 2.0f : 1.0f)),
        name + "-one-element-entry-row-with-loaded-one-based-skill-index");
    check(snapshot.size() == (technique ? 1 : 0),
        name + "-onbattle-caster-snapshot-only-after-admitted-technique");
    for (var buff : fire)
      check(number(buff, "level") == 1 && number(buff, "owner_index") == slot
              && number(buff, "wave_flag") == -1 && field(buff, "target_index_list").equals(List.of(slot)),
          name + "-fire-row-belongs-to-actual-caster-slot-" + slot);
  }

  private void accept(JsonObject input, String name, DynamicMessage reply, int actor,
      Set<Integer> present, Set<Integer> absent, boolean npcBuffs) throws Exception {
    var battle = (DynamicMessage) field(reply, "battle_info");
    int id = number(battle, "battle_id"), stage = number(battle, "stage_id");
    require(id > 0 && stage == input.get("stage").getAsInt(), "Different actual source stage");
    var current = (DynamicMessage) field(call("GetCurBattleInfoCsReq"), "battle_info");
    check(number(current, "battle_id") == id && number(current, "stage_id") == stage, name + "-battle-is-current");
    var waves = new ArrayList<List<Integer>>();
    for (var raw : (List<?>) field(battle, "monster_wave_list")) {
      var row = (DynamicMessage) raw;
      require(number(row, "battle_stage_id") == stage, "Different NPC wave stage");
      var monsters = new ArrayList<Integer>();
      for (var item : (List<?>) field(row, "monster_list")) monsters.add(number((DynamicMessage) item, "monster_id"));
      waves.add(monsters);
    }
    var expected = new ArrayList<List<Integer>>();
    for (var raw : input.getAsJsonArray("waves")) {
      var values = new ArrayList<Integer>();
      for (var monster : raw.getAsJsonArray()) values.add(monster.getAsInt());
      expected.add(values);
    }
    check(waves.equals(expected), name + "-original-stage-waves");
    int casterSlot = -1;
    var avatarRows = new ArrayList<Map<String, Integer>>();
    for (var value : (List<?>) field(battle, "battle_avatar_list")) {
      var avatar = (DynamicMessage) value;
      avatarRows.add(Map.of("id", number(avatar, "id"), "index", number(avatar, "index")));
      if (number(avatar, "id") == actor) casterSlot = number(avatar, "index");
    }
    check(casterSlot >= 0, name + "-actual-source-actor-" + actor);
    var buffs = new ArrayList<DynamicMessage>();
    var buffRows = new ArrayList<Map<String, Object>>();
    var buffIDs = new TreeSet<Integer>();
    for (var value : (List<?>) field(battle, "buff_list")) {
      var buff = (DynamicMessage) value;
      buffs.add(buff);
      buffRows.add(buffRow(buff));
      buffIDs.add(number(buff, "id"));
    }
    var observation = new LinkedHashMap<String, Object>();
    observation.put("name", name);
    observation.put("battleID", id);
    observation.put("stage", stage);
    observation.put("waves", waves);
    observation.put("actor", actor);
    observation.put("avatarRows", avatarRows);
    observation.put("expectedPresentBuffIDs", new TreeSet<>(present));
    observation.put("expectedAbsentBuffIDs", new TreeSet<>(absent));
    observation.put("buffIDs", new ArrayList<>(buffIDs));
    observation.put("buffRows", buffRows);
    // Preserve duplicate IDs and attribution before any effect assertion can abort the probe.
    System.out.println("BATTLE_OBSERVATION=" + new Gson().toJson(observation));
    System.out.flush();
    battles.add(observation);
    check(buffIDs.containsAll(present) && Collections.disjoint(buffIDs, absent), name + "-only-selected-callback-effects");
    if (actor == 1310) fireflyBuffSources(name, buffs, casterSlot, npcBuffs);
    if (npcBuffs) {
      int mask = (int) ((1L << waves.size()) - 1);
      for (int buff : List.of(131001, 131002)) {
        var rows = buffs.stream().filter(value -> number(value, "id") == buff).toList();
        check(rows.size() == 1 && number(rows.getFirst(), "wave_flag") == mask
                && number(rows.getFirst(), "owner_index") == casterSlot,
            name + "-npc-buff-binds-actual-waves-" + buff);
      }
    }
    var end = protocol.clientSchema().message("PVEBattleResultCsReq").findFieldByName("end_status")
        .getEnumType().findValueByName("BATTLE_END_WIN");
    var win = Map.<String, Object>of("battle_id", id, "stage_id", stage, "end_status", end);
    call("PVEBattleResultCsReq", win);
    callCode("PVEBattleResultCsReq", win, 1);
    callCode("GetCurBattleInfoCsReq", Map.of(), 1);
    check(true, name + "-win-settled-once");
    observation.put("winSettledOnce", true);
  }

  private void leader(int slot) throws Exception {
    call("ChangeLineupLeaderCsReq", Map.of("slot", slot));
  }

  private void requestSchema(String name, String... fields) {
    var descriptor = Objects.requireNonNull(protocol.clientSchema().message(name), name);
    var matched = new LinkedHashMap<String, Integer>();
    for (String field : fields) {
      var value = Objects.requireNonNull(descriptor.findFieldByName(field), name + "." + field);
      matched.put(field, value.getNumber());
    }
    requestSchemas.put(name, matched);
  }

  private void verifyRequestSchemas() {
    requestSchema("PlayerGetTokenCsReq", "account_uid", "token", "platform");
    requestSchema("PlayerLoginCsReq");
    requestSchema("PlayerLoginFinishCsReq");
    requestSchema("GetAvatarDataCsReq");
    requestSchema("GetCurSceneInfoCsReq");
    requestSchema("GetCurBattleInfoCsReq");
    requestSchema("GetCurLineupDataCsReq");
    requestSchema("SetAvatarPathCsReq", "avatar_id");
    requestSchema("ReplaceLineupCsReq", "index", "lineup_slot_list", "leader_slot");
    requestSchema("LineupSlotData", "id", "slot", "avatar_type");
    requestSchema("ChangeLineupLeaderCsReq", "slot");
    requestSchema("SceneCastSkillCsReq", "cast_entity_id", "attacked_by_entity_id", "skill_index",
        "skill_extra_tags", "hit_target_entity_id_list");
    requestSchema("PVEBattleResultCsReq", "battle_id", "stage_id", "end_status");
    requestSchema("BattleAvatar", "id", "index");
    requestSchema("BattleBuff", "id", "level", "owner_index", "wave_flag", "target_index_list", "dynamic_values");
  }

  private void formalRoster() throws Exception {
    var original = (DynamicMessage) field(call("GetCurLineupDataCsReq"), "lineup");
    int index = number(original, "index");
    var request = DynamicMessage.newBuilder(protocol.clientSchema().message("ReplaceLineupCsReq"));
    request.setField(request.getDescriptorForType().findFieldByName("index"), index);
    var slots = request.getDescriptorForType().findFieldByName("lineup_slot_list");
    var roster = List.of(8001, 1310, 1009);
    for (int slot = 0; slot < roster.size(); slot++) {
      var row = DynamicMessage.newBuilder(slots.getMessageType());
      row.setField(row.getDescriptorForType().findFieldByName("slot"), slot);
      row.setField(row.getDescriptorForType().findFieldByName("id"), roster.get(slot));
      var type = row.getDescriptorForType().findFieldByName("avatar_type");
      row.setField(type, type.getEnumType().findValueByName("AvatarType_AvatarFormalType"));
      request.addRepeatedField(slots, row.build());
    }
    requests++;
    var frame = raw(protocol.clientCommand("ReplaceLineupCsReq"), request.build().toByteArray(),
        protocol.clientCommand("JoinLineupScRsp"));
    // Existing native formal replacement reply is 719 fields 7/9 (CN), not the named join row.
    var reply = UnknownFieldSet.parseFrom(frame.body());
    require(Set.of(7, 9).containsAll(reply.asMap().keySet()), "Unknown native replacement reply");
    require(!reply.hasField(9) || reply.getField(9).getVarintList().equals(List.of(0L)),
        "Native formal roster was rejected");
    require(!reply.hasField(7) || reply.getField(7).getVarintList().equals(List.of((long) index)),
        "Different native formal roster index");
    var lineup = (DynamicMessage) field(call("GetCurLineupDataCsReq"), "lineup");
    var actual = new ArrayList<Integer>();
    for (var raw : (List<?>) field(lineup, "avatar_list")) actual.add(number((DynamicMessage) raw, "id"));
    check(actual.equals(roster) && number(lineup, "index") == index,
        "native-formal-replacement-publishes-original-path-firefly-and-asta");
  }

  private Map<String, Object> execute(JsonObject input) throws Exception {
    verifyRequestSchemas();
    int uid = number(call("PlayerGetTokenCsReq", Map.of("account_uid", input.get("accountUid").getAsString(),
        "token", input.get("comboToken").getAsString(), "platform", 1)), "uid");
    call("PlayerLoginCsReq");
    call("PlayerLoginFinishCsReq");
    System.out.println("READY_UID=" + uid);
    System.out.flush();
    var owned = new HashSet<Integer>();
    for (int attempt = 0; attempt < 120 && !owned.containsAll(Set.of(1009, 1310)); attempt++) {
      for (var raw : (List<?>) field(call("GetAvatarDataCsReq"), "avatar_list"))
        owned.add(number((DynamicMessage) raw, "base_avatar_id"));
      if (!owned.containsAll(Set.of(1009, 1310))) Thread.sleep(200);
    }
    require(owned.containsAll(Set.of(1009, 1310)), "Isolated console actor fixtures not ready");
    var path = protocol.clientSchema().message("SetAvatarPathCsReq").findFieldByName("avatar_id").getEnumType().findValueByNumber(8007);
    var selected = call("SetAvatarPathCsReq", Map.of("avatar_id", path));
    check(((Descriptors.EnumValueDescriptor) field(selected, "avatar_id")).getNumber() == 8007, "native-selects-original-playerboy30-path");
    accept(input, "original-8007-hit", attack(200101, 0, new int[] {1}, 0), 8007, Set.of(), Set.of(), false);
    accept(input, "original-8007-finish", attack(200102, 0, new int[] {2}, 0), 8007, Set.of(), Set.of(), false);
    formalRoster();
    leader(2);
    accept(input, "controlled-asta-direct", attack(200103, 0, new int[] {}, 0), 1009,
        Set.of(), Set.of(199998101, 199998102), false);
    accept(input, "controlled-asta-hit", attack(200104, 0, new int[] {1}, 0), 1009,
        Set.of(199998101), Set.of(199998102), false);
    accept(input, "controlled-asta-finish", attack(200105, 0, new int[] {2}, 0), 1009,
        Set.of(199998102), Set.of(199998101), false);
    attack(200106, 0, new int[] {1, 1}, 5);
    accept(input, "asta-direct-after-duplicate-tag", attack(200106, 0, new int[] {}, 0), 1009,
        Set.of(199998102), Set.of(199998101), false);
    attack(200107, 0, new int[] {1, 2}, 5);
    accept(input, "asta-direct-after-contradictory-tags", attack(200107, 0, new int[] {}, 0), 1009,
        Set.of(199998102), Set.of(199998101), false);
    attack(200108, 0, new int[] {99}, 5);
    accept(input, "asta-direct-after-unknown-tag", attack(200108, 0, new int[] {}, 0), 1009,
        Set.of(199998102), Set.of(199998101), false);
    leader(1);
    attack(200112, 0, new int[] {2}, 5);
    accept(input, "firefly-normal-after-projectile-claim", attack(200112, 0, new int[] {}, 0), 1310,
        Set.of(1000112), Set.of(131001, 131002), false);
    attack(200109, 1, new int[] {}, 1);
    accept(input, "original-firefly-normal-after-failed-technique", attack(200110, 0, new int[] {}, 0), 1310,
        Set.of(1000112), Set.of(131001, 131002), false);
    accept(input, "original-firefly-admitted-technique", attack(200111, 1, new int[] {}, 0), 1310,
        Set.of(131001, 131002, 1000112), Set.of(), true);
    return new LinkedHashMap<>(Map.of("uid", uid, "passed", true, "requests", requests,
        "checks", checks, "battles", battles, "events", attackEvents, "clientGameplayVerified", false,
        "multiPacketTechniqueMPVerified", false, "activeBattleRecoveryVerified", false,
        "requestSchemas", requestSchemas));
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
            "Native maze callback connection failed", failure.get());
      var f = inbox.poll(1, TimeUnit.SECONDS);
      if (f == null) continue;
      observe(f);
      if (f.command() == response) return f;
    }
    throw new TimeoutException("Response " + response);
  }

  private DynamicMessage callCode(String name, Map<String, Object> fields, int code)
      throws Exception {
    requests++;
    var b = DynamicMessage.newBuilder(protocol.clientSchema().message(name));
    fields.forEach(
        (key, value) -> b.setField(Objects.requireNonNull(
            b.getDescriptorForType().findFieldByName(key), name + "." + key), value));
    String response = name.replace("CsReq", "ScRsp");
    var frame =
        raw(
            protocol.clientCommand(name),
            b.build().toByteArray(),
            protocol.clientCommand(response));
    var m = protocol.clientSchema().parse(response, frame.body());
    require(m.getUnknownFields().asMap().isEmpty(), "Unknown native response fields");
    require(
        number(m, "retcode") == code,
        "Unexpected " + response + " status=" + number(m, "retcode") + " expected=" + code);
    return m;
  }

  private DynamicMessage call(String name, Map<String, Object> fields) throws Exception {
    return callCode(name, fields, 0);
  }

  private DynamicMessage call(String name) throws Exception {
    return call(name, Map.of());
  }

  public static void main(String[] args) {
    var probe = new NativeMazeCallbackPhasesProbe();
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
                  require(frame != null, "Truncated maze callback frame");
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
                  null, new IllegalStateException("Maze callback probe disconnected"));
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
