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

/** Actual 1408/1460 UseIDs and per-request controlled callbacks over isolated UDP/KCP. */
public final class NativeMazeCastSequenceProbe {
  private record Frame(int command, byte[] body) {}
  private final ProtocolBridge protocol = ProtocolBridge.forVersion("4.6.0");
  private final BlockingQueue<Frame> inbox = new LinkedBlockingQueue<>();
  private final AtomicReference<Throwable> failure = new AtomicReference<>();
  private final CompletableFuture<Ukcp> connected = new CompletableFuture<>();
  private final List<String> checks = new ArrayList<>();
  private final List<Map<String, Object>> events = new ArrayList<>();
  private final List<Map<String, Object>> notifications = new ArrayList<>();
  private final Map<String, Map<String, Integer>> schemas = new LinkedHashMap<>();
  private final Map<Integer, Integer> removals = new HashMap<>();
  private Ukcp channel;
  private int requests, mpUpdates;

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
  private void verifySchemas() {
    schema("PlayerGetTokenCsReq", "account_uid", "token", "platform");
    schema("ReplaceLineupCsReq", "index", "lineup_slot_list", "leader_slot");
    schema("LineupSlotData", "id", "slot", "avatar_type");
    schema("ChangeLineupLeaderCsReq", "slot");
    schema("SceneCastSkillCostMpCsReq", "cast_entity_id", "attacked_by_entity_id", "skill_index");
    schema("SceneCastSkillCostMpScRsp", "cast_entity_id", "retcode");
    schema("SceneCastSkillCsReq", "cast_entity_id", "attacked_by_entity_id", "skill_index", "skill_extra_tags", "hit_target_entity_id_list");
    schema("SceneCastSkillScRsp", "cast_entity_id", "retcode", "battle_info", "monster_battle_info");
    schema("SceneCastSkillMpUpdateScNotify", "cast_entity_id", "mp");
    schema("SyncEntityBuffChangeListScNotify", "entity_buff_change_list");
    schema("EntityBuffChangeInfo", "entity_id", "remove_buff_id");
    schema("PVEBattleResultCsReq", "battle_id", "stage_id", "end_status");
    check(schemas.get("SceneCastSkillCsReq").get("cast_entity_id") == 13
        && schemas.get("SceneCastSkillCostMpCsReq").get("cast_entity_id") == 13,
        "same-actual-uint-useid-field13-on-both-requests");
  }
  private void observe(Frame frame) throws Exception {
    if (frame.command() == protocol.clientCommand("SceneCastSkillMpUpdateScNotify")) {
      var data = protocol.clientSchema().parse("SceneCastSkillMpUpdateScNotify", frame.body());
      require(data.getUnknownFields().asMap().isEmpty(), "Unknown MP notification fields");
      mpUpdates++;
      notifications.add(Map.of("useID", number(data, "cast_entity_id"), "mp", number(data, "mp")));
    } else if (frame.command() == protocol.clientCommand("SyncEntityBuffChangeListScNotify")) {
      var data = protocol.clientSchema().parse("SyncEntityBuffChangeListScNotify", frame.body());
      require(data.getUnknownFields().asMap().isEmpty(), "Unknown callback notification fields");
      for (var raw : (List<?>) field(data, "entity_buff_change_list")) {
        var row = (DynamicMessage) raw;
        var id = row.getDescriptorForType().findFieldByName("remove_buff_id");
        if (row.hasField(id)) removals.merge(number(row, "remove_buff_id"), 1, Integer::sum);
      }
    }
  }
  private DynamicMessage lineup() throws Exception { return (DynamicMessage) field(call("GetCurLineupDataCsReq"), "lineup"); }
  private int mp() throws Exception { return number(lineup(), "mp"); }
  private DynamicMessage scene() throws Exception {
    var result = (DynamicMessage) field(call("GetCurSceneInfoCsReq"), "scene");
    require(number(result, "entry_id") == 2000101, "Different controlled scene entry");
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
  private void pay(int use, int caster, int status, int delta) throws Exception {
    int before = mp(), updates = mpUpdates;
    var response = callReply("SceneCastSkillCostMpCsReq",
        Map.of("cast_entity_id", use, "attacked_by_entity_id", caster, "skill_index", 1));
    int after = mp();
    var event = new LinkedHashMap<String, Object>(Map.of("request", "1408", "useID", use,
        "casterEntity", caster, "status", number(response, "retcode"), "mpBefore", before,
        "mpAfter", after, "mpNotificationDelta", mpUpdates - updates));
    events.add(event);
    event.put("expectedStatus", status);
    System.out.println("CAST_OBSERVATION=" + new Gson().toJson(event));
    check(number(response, "retcode") == status, "payment-actual-status-" + use + "-expected-" + status);
    check(number(response, "cast_entity_id") == use && use != caster, "payment-echoes-ability-useid-" + use);
    check(after == before - delta && mpUpdates == updates + (delta > 0 ? 1 : 0), "payment-balance-and-notify-once-" + use);
    if (delta > 0) check(notifications.getLast().equals(Map.of("useID", use, "mp", after)), "payment-notify-matches-useid-and-balance-" + use);
  }
  private DynamicMessage action(int use, int caster, int index, int[] tags, int inst, String kind,
      int status, int mpDelta, int release, int hit, int finish, boolean battle) throws Exception {
    int before = mp(), updates = mpUpdates;
    int r = removals.getOrDefault(199998111, 0), h = removals.getOrDefault(199998112, 0), e = removals.getOrDefault(199998113, 0);
    int entity = inst == 0 ? 0 : target(inst, kind);
    var values = new HashMap<String, Object>(Map.of("cast_entity_id", use, "attacked_by_entity_id", caster, "skill_index", index));
    if (entity != 0) values.put("hit_target_entity_id_list", List.of(entity));
    var tagType = protocol.clientSchema().message("SceneCastSkillCsReq").findFieldByName("skill_extra_tags").getEnumType();
    var encoded = new ArrayList<Descriptors.EnumValueDescriptor>();
    for (int tag : tags) encoded.add(tagType.findValueByNumberCreatingIfUnknown(tag));
    values.put("skill_extra_tags", encoded);
    var response = callReply("SceneCastSkillCsReq", values);
    int after = mp();
    boolean hasBattle = response.hasField(response.getDescriptorForType().findFieldByName("battle_info"));
    var event = new LinkedHashMap<String, Object>();
    event.putAll(Map.of("request", "1460", "useID", use, "casterEntity", caster, "skillIndex", index,
        "tags", Arrays.stream(tags).boxed().toList(), "targetInstance", inst, "targetEntity", entity,
        "status", number(response, "retcode"), "mpBefore", before, "mpAfter", after));
    event.put("mpNotificationDelta", mpUpdates - updates);
    event.put("callbackDeltas", List.of(removals.getOrDefault(199998111, 0) - r,
        removals.getOrDefault(199998112, 0) - h, removals.getOrDefault(199998113, 0) - e));
    event.put("hasBattle", hasBattle);
    events.add(event);
    event.put("expectedStatus", status);
    System.out.println("CAST_OBSERVATION=" + new Gson().toJson(event));
    check(number(response, "retcode") == status, "action-actual-status-" + use + "-expected-" + status);
    check(number(response, "cast_entity_id") == use && use != caster, "action-echoes-original-ability-useid-" + use);
    check(after == before + mpDelta && mpUpdates == updates, "action-balance-and-no-additional-point-notification-" + use);
    check(event.get("callbackDeltas").equals(List.of(release, hit, finish)), "per-request-release-hit-end-deltas-" + use + "-" + Arrays.toString(tags));
    check(hasBattle == battle, "actual-battle-admission-" + use + "-" + inst);
    if (status == 0 && entity != 0 && kind.equals("npc_monster")) {
      var rows = (List<?>) field(response, "monster_battle_info");
      check(rows.size() == 1 && number((DynamicMessage) rows.getFirst(), "target_monster_entity_id") == entity,
          "actual-result-belongs-to-this-request-target-" + inst);
      String outcome = ((Descriptors.EnumValueDescriptor) field((DynamicMessage) rows.getFirst(), "monster_battle_type")).getName();
      check(outcome.equals("MONSTER_BATTLE_TYPE_" + (battle ? "TRIGGER_BATTLE" : "NO_BATTLE")), "actual-target-outcome-" + inst);
    }
    if (!battle) callCode("GetCurBattleInfoCsReq", Map.of(), 1);
    return response;
  }
  private void settle(JsonObject input, DynamicMessage reply) throws Exception {
    var battle = (DynamicMessage) field(reply, "battle_info");
    int id = number(battle, "battle_id"), stage = number(battle, "stage_id");
    check(id > 0 && stage == input.get("stage").getAsInt(), "actual-admitted-source-stage-" + id);
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
    check(waves.equals(expected), "original-stage-wave-roster-" + id);
    var current = (DynamicMessage) field(call("GetCurBattleInfoCsReq"), "battle_info");
    check(number(current, "battle_id") == id, "admitted-battle-is-current-" + id);
    var end = protocol.clientSchema().message("PVEBattleResultCsReq").findFieldByName("end_status").getEnumType().findValueByName("BATTLE_END_WIN");
    var win = Map.<String, Object>of("battle_id", id, "stage_id", stage, "end_status", end);
    call("PVEBattleResultCsReq", win);
    callCode("PVEBattleResultCsReq", win, 1);
    callCode("GetCurBattleInfoCsReq", Map.of(), 1);
    check(true, "actual-win-and-replay-rejection-" + id);
  }
  private void roster() throws Exception {
    int index = number(lineup(), "index");
    var type = protocol.clientSchema().message("LineupSlotData");
    var slots = new ArrayList<DynamicMessage>();
    var ids = List.of(8001, 1009, 1510);
    for (int i = 0; i < ids.size(); i++) {
      var row = DynamicMessage.newBuilder(type);
      row.setField(type.findFieldByName("id"), ids.get(i));
      row.setField(type.findFieldByName("slot"), i);
      row.setField(type.findFieldByName("avatar_type"), type.findFieldByName("avatar_type").getEnumType().findValueByName("AvatarType_AvatarFormalType"));
      slots.add(row.build());
    }
    var request = message("ReplaceLineupCsReq", Map.of("index", index, "lineup_slot_list", slots));
    requests++;
    var reply = UnknownFieldSet.parseFrom(raw(protocol.clientCommand("ReplaceLineupCsReq"), request.toByteArray(), protocol.clientCommand("JoinLineupScRsp")).body());
    require(Set.of(7, 9).containsAll(reply.asMap().keySet()) && (!reply.hasField(9) || reply.getField(9).getVarintList().equals(List.of(0L))), "Native formal replacement rejected");
    var actual = new ArrayList<Integer>();
    for (var row : (List<?>) field(lineup(), "avatar_list")) actual.add(number((DynamicMessage) row, "id"));
    check(actual.equals(ids), "actual-formal-roster-has-original-asta-and-himekonova");
  }
  private Map<String, Object> execute(JsonObject input) throws Exception {
    verifySchemas();
    String mode = input.get("mode").getAsString();
    require(Set.of("sequence", "new-session", "restored").contains(mode), "Unknown cast sequence mode");
    int uid = number(call("PlayerGetTokenCsReq", Map.of("account_uid", input.get("accountUid").getAsString(), "token", input.get("comboToken").getAsString(), "platform", 1)), "uid");
    call("PlayerLoginCsReq"); call("PlayerLoginFinishCsReq");
    if (mode.equals("sequence")) {
      System.out.println("READY_UID=" + uid); System.out.flush();
      var owned = new HashSet<Integer>();
      for (int attempt = 0; attempt < 120 && !owned.containsAll(Set.of(1009, 1510)); attempt++) {
        for (var row : (List<?>) field(call("GetAvatarDataCsReq"), "avatar_list")) owned.add(number((DynamicMessage) row, "base_avatar_id"));
        if (!owned.containsAll(Set.of(1009, 1510))) Thread.sleep(200);
      }
      require(owned.containsAll(Set.of(1009, 1510)), "Declared isolated caster grants are missing");
      roster();
      int ordinary = actor(8001), caster = actor(1009);
      settle(input, action(90001, ordinary, 0, new int[] {1, 2}, 200101, "npc_monster", 0, 0, 0, 0, 0, true));
      call("ChangeLineupLeaderCsReq", Map.of("slot", 1));
      caster = actor(1009);
      check(mp() == 5, "initial-original-five-technique-points");
      pay(90002, actor(1510), 0, 2);
      action(90201, actor(8001), 0, new int[] {}, 300002, "prop", 0, 2, 0, 0, 0, false);
      check(mp() == 5, "original-himekonova-cost-two-and-actual-source-box-restores-two");
      pay(90101, caster, 0, 1);
      pay(90101, actor(1510), 5, 0);
      action(90101, caster, 0, new int[] {}, 0, "npc_monster", 5, 0, 0, 0, 0, false);
      action(90101, caster, 1, new int[] {0}, 0, "npc_monster", 0, 0, 1, 0, 0, false);
      action(90101, caster, 1, new int[] {1}, 200102, "npc_monster", 0, 0, 0, 1, 0, false);
      action(90101, caster, 1, new int[] {1}, 200103, "npc_monster", 0, 0, 0, 1, 0, false);
      pay(90101, caster, 0, 0);
      settle(input, action(90101, caster, 1, new int[] {2}, 200104, "npc_monster", 0, 0, 0, 0, 1, true));
      pay(90102, caster, 0, 1);
      action(90102, caster, 1, new int[] {2}, 200109, "npc_monster", 1, 0, 1, 0, 1, false);
      settle(input, action(90102, caster, 1, new int[] {2}, 200105, "npc_monster", 0, 0, 0, 0, 1, true));
      pay(90103, caster, 0, 1);
      action(90103, caster, 1, new int[] {0}, 0, "npc_monster", 0, 0, 1, 0, 0, false);
      pay(90104, caster, 0, 1);
      action(90104, caster, 1, new int[] {0}, 0, "npc_monster", 0, 0, 1, 0, 0, false);
      action(90103, caster, 1, new int[] {1}, 200106, "npc_monster", 0, 0, 0, 1, 0, false);
      settle(input, action(90103, caster, 1, new int[] {2}, 200107, "npc_monster", 0, 0, 0, 0, 1, true));
      for (int[] tags : List.of(new int[] {99}, new int[] {1, 1}, new int[] {2, 1}))
        action(90105, caster, 1, tags, 0, "npc_monster", 5, 0, 0, 0, 0, false);
      pay(90105, caster, 0, 1);
      action(90105, caster, 1, new int[] {1}, 200108, "npc_monster", 0, 0, 1, 1, 0, false);
      settle(input, action(90105, caster, 1, new int[] {2}, 200110, "npc_monster", 0, 0, 0, 0, 1, true));
      check(mp() == 0, "prepaid-hit-and-finish-are-valid-at-zero-mp");
      pay(90106, caster, 2213, 0);
      action(90200, caster, 0, new int[] {}, 300001, "prop", 0, 2, 0, 0, 0, false);
      pay(90106, caster, 0, 1);
      settle(input, action(90106, caster, 1, new int[] {1, 2}, 200111, "npc_monster", 0, 0, 1, 1, 1, true));
      check(mp() == 1 && mpUpdates == 7, "six-asta-one-himekonova-payments-only-seven-native-point-notifications");
    } else {
      int caster = actor(1009);
      check(mp() == (mode.equals("new-session") ? 1 : 0), "actual-saved-mp-after-normal-h2-restart-" + mode);
      if (mode.equals("new-session")) {
        pay(90106, caster, 0, 1);
        action(90106, caster, 1, new int[] {0}, 0, "npc_monster", 0, 0, 1, 0, 0, false);
        action(90106, caster, 1, new int[] {1}, 200112, "npc_monster", 0, 0, 0, 1, 0, false);
        check(mp() == 0 && mpUpdates == 1, "new-session-cannot-borrow-old-id-payment-and-still-runs-at-zero");
      } else {
        pay(90106, caster, 2213, 0);
        check(mp() == 0 && mpUpdates == 0, "second-h2-restore-has-no-prewritten-payment-or-refund");
      }
    }
    return new LinkedHashMap<>(Map.of("passed", true, "uid", uid, "mode", mode, "requests", requests,
        "checks", checks, "events", events, "notifications", notifications, "mp", mp(), "requestSchemas", schemas));
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
      if (failure.get() != null) throw new IllegalStateException("Native cast sequence connection failed", failure.get());
      var frame = inbox.poll(1, TimeUnit.SECONDS);
      if (frame == null) continue;
      observe(frame);
      if (frame.command() == response) return frame;
    }
    throw new TimeoutException("Native cast response " + response);
  }
  private DynamicMessage callReply(String name, Map<String, Object> fields) throws Exception {
    requests++;
    String reply = name.replace("CsReq", "ScRsp");
    var frame = raw(protocol.clientCommand(name), message(name, fields).toByteArray(), protocol.clientCommand(reply));
    var result = protocol.clientSchema().parse(reply, frame.body());
    require(result.getUnknownFields().asMap().isEmpty(), "Unknown native reply fields " + reply);
    return result;
  }
  private DynamicMessage callCode(String name, Map<String, Object> fields, int status) throws Exception {
    var result = callReply(name, fields);
    require(number(result, "retcode") == status, "Unexpected " + name.replace("CsReq", "ScRsp")
        + " status=" + number(result, "retcode") + " expected=" + status);
    return result;
  }
  private DynamicMessage call(String name, Map<String, Object> fields) throws Exception { return callCode(name, fields, 0); }
  private DynamicMessage call(String name) throws Exception { return call(name, Map.of()); }
  public static void main(String[] args) {
    var probe = new NativeMazeCastSequenceProbe();
    if (args.length == 1 && args[0].equals("--verify-descriptors")) {
      probe.verifySchemas();
      System.out.println(new Gson().toJson(Map.of("passed", true, "requestSchemas", probe.schemas)));
      return;
    }
    var client = new KcpClient();
    int status = 1;
    try {
      var control = new java.io.BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
      var input = JsonParser.parseString(control.readLine()).getAsJsonObject();
      var config = new ChannelConfig();
      config.nodelay(true, 40, 2, true); config.setMtu(1400); config.setSndwnd(256); config.setRcvwnd(256);
      config.setUseConvChannel(true); config.setConv(ThreadLocalRandom.current().nextInt(1, Integer.MAX_VALUE));
      config.setTimeoutMillis(25000); config.setAckNoDelay(true);
      client.init(config, new KcpListener() {
        public void onConnected(Ukcp connection) { probe.connected.complete(connection); }
        public void handleReceive(ByteBuf bytes, Ukcp connection) {
          try {
            while (bytes.isReadable()) {
              var frame = PacketCodec.decodeNext(bytes);
              require(frame != null, "Truncated native cast frame");
              probe.inbox.add(new Frame(frame.cmdId(), frame.body()));
            }
          } catch (Throwable error) { probe.failure.set(error); }
        }
        public void handleException(Throwable error, Ukcp connection) { probe.failure.set(error); probe.connected.completeExceptionally(error); }
        public void handleClose(Ukcp connection) { probe.failure.compareAndSet(null, new IllegalStateException("Native cast probe disconnected")); }
      });
      client.connect(new InetSocketAddress("127.0.0.1", Integer.parseInt(args[0])), config);
      probe.channel = probe.connected.get(10, TimeUnit.SECONDS);
      System.out.println(new Gson().toJson(probe.execute(input)));
      status = 0;
    } catch (Exception error) {
      System.err.println("FAILED_CAST_OBSERVATIONS=" + new Gson().toJson(probe.events));
      error.printStackTrace(System.err);
    } finally { client.stop(); }
    System.exit(status);
  }
}
