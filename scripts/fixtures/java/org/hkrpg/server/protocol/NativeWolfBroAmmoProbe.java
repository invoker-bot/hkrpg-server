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

/** Real Level11 placements, native 6525/6514/6508 traffic and normally persisted ammo. */
public final class NativeWolfBroAmmoProbe {
  private record Frame(int command, byte[] body) {}
  private final ProtocolBridge protocol = ProtocolBridge.forVersion("4.6.0");
  private final BlockingQueue<Frame> inbox = new LinkedBlockingQueue<>();
  private final AtomicReference<Throwable> failure = new AtomicReference<>();
  private final CompletableFuture<Ukcp> connected = new CompletableFuture<>();
  private final List<String> checks = new ArrayList<>();
  private final List<Map<String, Object>> events = new ArrayList<>(), notifications = new ArrayList<>();
  private final Map<String, Map<String, Integer>> schemas = new LinkedHashMap<>();
  private final List<Integer> deleted = new ArrayList<>(), controlStates = new ArrayList<>();
  private Ukcp channel;
  private int requests;

  private static Object field(DynamicMessage value, String name) {
    return value.getField(Objects.requireNonNull(value.getDescriptorForType().findFieldByName(name), name));
  }
  private static int number(DynamicMessage value, String name) { return ((Number) field(value, name)).intValue(); }
  private static boolean has(DynamicMessage value, String name) { return value.hasField(Objects.requireNonNull(value.getDescriptorForType().findFieldByName(name), name)); }
  private static void require(boolean ok, String why) { if (!ok) throw new IllegalStateException(why); }
  private void check(boolean ok, String why) { require(ok, why); checks.add(why); }
  private static String hash(DynamicMessage value) throws Exception {
    return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(value.toByteArray()));
  }
  private void schema(String name, String... keys) {
    var descriptor = Objects.requireNonNull(protocol.clientSchema().message(name), name);
    var fields = new LinkedHashMap<String, Integer>();
    for (String key : keys) fields.put(key, Objects.requireNonNull(descriptor.findFieldByName(key), name + "." + key).getNumber());
    schemas.put(name, fields);
  }
  private void verifySchemas() {
    schema("PlayerGetTokenCsReq", "account_uid", "token", "platform");
    schema("EnterSceneCsReq", "entry_id");
    schema("StartWolfBroGameCsReq", "id", "motion", "IDANCEPOOEI");
    schema("StartWolfBroGameScRsp", "retcode", "game_data");
    schema("GetWolfBroGameDataCsReq", "id");
    schema("GetWolfBroGameDataScRsp", "retcode", "game_data");
    schema("WolfBroGameActivateBulletCsReq", "group_id", "DAKENPDMDDJ");
    schema("WolfBroGameActivateBulletScRsp", "retcode");
    schema("OKEFGCNNEKI", "assist_monster_entity_id_list", "GGDCDJDBCCN");
    schema("WolfBroGameUseBulletScRsp", "retcode", "game_data");
    schema("WolfBroGamePickupBulletCsReq", "GGDCDJDBCCN");
    schema("WolfBroGamePickupBulletScRsp", "retcode", "game_data");
    schema("WolfBroGameDataChangeScNotify", "game_data");
    schema("ArchiveWolfBroGameCsReq", "id", "motion");
    schema("ArchiveWolfBroGameScRsp", "retcode", "game_data");
    schema("RestoreWolfBroGameArchiveCsReq", "id");
    schema("RestoreWolfBroGameArchiveScRsp", "retcode", "game_data");
    schema("GGHOBPBFFOB", "id", "uuid", "CHCOPKAKBNO", "is_bullet_activated", "PAOEJKABFEP", "BBEMIGELGJC");
    schema("KDFPANILFOH", "CHCOPKAKBNO", "is_bullet_activated", "PAOEJKABFEP", "motion");
    schema("Vector", "x", "y", "z");
    schema("MotionInfo", "pos", "rot");
    schema("GroupStateChangeCsReq", "group_state_info");
    schema("GroupStateInfo", "group_id", "group_state");
    schema("SceneEntityInfo", "entity_id", "group_id", "inst_id", "npc_monster", "prop", "motion");
    schema("SceneNpcMonsterInfo", "monster_id", "event_id");
    schema("ScenePropInfo", "prop_id");
    schema("SceneGroupRefreshScNotify", "floor_id", "group_refresh_list");
    schema("GroupRefreshInfo", "group_id", "state", "refresh_entity");
    schema("SceneEntityRefreshInfo", "delete_entity");
    check(protocol.clientCommand("WolfBroGameActivateBulletCsReq") == 6525
        && protocol.clientCommand("WolfBroGameActivateBulletScRsp") == 6523
        && protocol.clientCommand("WolfBroGamePickupBulletCsReq") == 6508
        && protocol.clientCommand("WolfBroGamePickupBulletScRsp") == 6547
        && protocol.clientCommand("WolfBroGameUseBulletScRsp") == 6540
        && protocol.clientCommand("WolfBroGameDataChangeScNotify") == 6548, "pinned-ammo-command-identities");
    check(schemas.get("WolfBroGameActivateBulletCsReq").equals(Map.of("group_id", 12, "DAKENPDMDDJ", 7))
        && schemas.get("OKEFGCNNEKI").equals(Map.of("assist_monster_entity_id_list", 1, "GGDCDJDBCCN", 14))
        && schemas.get("WolfBroGamePickupBulletCsReq").get("GGDCDJDBCCN") == 6,
        "pinned-gun-instance-shot-list-and-pickup-vector-fields");
    check(schemas.get("GGHOBPBFFOB").get("CHCOPKAKBNO") == 13
        && schemas.get("GGHOBPBFFOB").get("is_bullet_activated") == 15
        && schemas.get("GGHOBPBFFOB").get("PAOEJKABFEP") == 6
        && schemas.get("KDFPANILFOH").get("CHCOPKAKBNO") == 8
        && schemas.get("KDFPANILFOH").get("is_bullet_activated") == 4
        && schemas.get("KDFPANILFOH").get("PAOEJKABFEP") == 1, "pinned-current-and-archive-ammo-fields");
    check(message("OKEFGCNNEKI", Map.of("assist_monster_entity_id_list", List.of())).getSerializedSize() == 0,
        "native-empty-shot-does-not-send-an-invented-vector-or-id");
  }
  private static Map<String, Integer> pointView(DynamicMessage point) {
    return Map.of("x", number(point, "x"), "y", number(point, "y"), "z", number(point, "z"));
  }
  private static Map<String, Object> motionView(DynamicMessage motion) {
    return Map.of("pos", pointView((DynamicMessage) field(motion, "pos")), "rot", pointView((DynamicMessage) field(motion, "rot")));
  }
  private static List<Map<String, Integer>> drops(DynamicMessage value) {
    return ((List<?>) field(value, "PAOEJKABFEP")).stream().map(DynamicMessage.class::cast).map(NativeWolfBroAmmoProbe::pointView).toList();
  }
  private static Map<String, Object> gameView(DynamicMessage data) {
    var archive = (DynamicMessage) field(data, "BBEMIGELGJC");
    return Map.of("id", number(data, "id"), "uuid", field(data, "uuid"), "ammo", number(data, "CHCOPKAKBNO"),
        "activated", field(data, "is_bullet_activated"), "bullets", drops(data),
        "archive", Map.of("ammo", number(archive, "CHCOPKAKBNO"), "activated", field(archive, "is_bullet_activated"),
            "bullets", drops(archive), "motion", motionView((DynamicMessage) field(archive, "motion"))));
  }
  private void observe(Frame frame) throws Exception {
    if (frame.command() == 6548) {
      var reply = protocol.clientSchema().parse("WolfBroGameDataChangeScNotify", frame.body());
      require(reply.getUnknownFields().asMap().isEmpty(), "Unknown ammo notification fields");
      var data = (DynamicMessage) field(reply, "game_data");
      require(number(data, "id") == 11 && data.getUnknownFields().asMap().isEmpty(), "Wrong Level11 ammo notification");
      notifications.add(gameView(data));
    } else if (frame.command() == protocol.clientCommand("SceneGroupRefreshScNotify")) {
      var reply = protocol.clientSchema().parse("SceneGroupRefreshScNotify", frame.body());
      require(reply.getUnknownFields().asMap().isEmpty(), "Unknown scene removal fields");
      if (number(reply, "floor_id") != 20321001) return;
      for (var raw : (List<?>) field(reply, "group_refresh_list")) {
        var group = (DynamicMessage) raw;
        if (number(group, "group_id") == 21) controlStates.add(number(group, "state"));
        for (var item : (List<?>) field(group, "refresh_entity")) {
          var entity = (DynamicMessage) item;
          if (has(entity, "delete_entity")) deleted.add(number(entity, "delete_entity"));
        }
      }
    }
  }
  private DynamicMessage query() throws Exception {
    var data = (DynamicMessage) field(call("GetWolfBroGameDataCsReq", Map.of("id", 11), 0), "game_data");
    require(number(data, "id") == 11 && data.getUnknownFields().asMap().isEmpty(), "Wrong native query data");
    return data;
  }
  private DynamicMessage scene() throws Exception {
    var scene = (DynamicMessage) field(call("GetCurSceneInfoCsReq", Map.of(), 0), "scene");
    require(number(scene, "plane_id") == 20321 && number(scene, "floor_id") == 20321001
        && number(scene, "entry_id") == 2032101, "Different original Level11 scene");
    return scene;
  }
  private DynamicMessage target(int group, int instance, String type) throws Exception {
    DynamicMessage found = null;
    for (var g : (List<?>) field(scene(), "entity_group_list"))
      for (var e : (List<?>) field((DynamicMessage) g, "entity_list")) {
        var entity = (DynamicMessage) e;
        if (number(entity, "group_id") == group && number(entity, "inst_id") == instance) {
          require(found == null && has(entity, type), "Duplicate or different original scene target");
          found = entity;
        }
      }
    return found;
  }
  private void control(int expected) throws Exception {
    var groups = ((List<?>) field(scene(), "entity_group_list")).stream().map(DynamicMessage.class::cast)
        .filter(group -> number(group, "group_id") == 21).toList();
    check(groups.size() == 1 && number(groups.getFirst(), "state") == expected, "actual-source-G21-control-state-" + expected);
  }
  private DynamicMessage leaderMotion() throws Exception {
    var scene = scene();
    int id = number(scene, "leader_entity_id");
    for (var group : (List<?>) field(scene, "entity_group_list"))
      for (var raw : (List<?>) field((DynamicMessage) group, "entity_list")) {
        var entity = (DynamicMessage) raw;
        if (number(entity, "entity_id") == id) return (DynamicMessage) field(entity, "motion");
      }
    throw new IllegalStateException("Actual scene leader missing");
  }
  private DynamicMessage point(JsonObject value) {
    return message("Vector", Map.of("x", value.get("x").getAsInt(), "y", value.get("y").getAsInt(), "z", value.get("z").getAsInt()));
  }
  private DynamicMessage motion(JsonObject value) { return message("MotionInfo", Map.of("pos", point(value.getAsJsonObject("pos")), "rot", point(value.getAsJsonObject("rot")))); }
  private DynamicMessage operation(String label, String name, Map<String, Object> values,
      int expected, int expectedNotifications, int ammo, boolean activated, int bullets) throws Exception {
    var before = query(); int count = notifications.size();
    var reply = name.equals("OKEFGCNNEKI") ? reply(name, values, 6514, "WolfBroGameUseBulletScRsp")
        : reply(name, values, protocol.clientCommand(name), name.replace("CsReq", "ScRsp"));
    var event = new LinkedHashMap<String, Object>();
    event.putAll(Map.of("label", label, "request", name, "command", name.equals("OKEFGCNNEKI") ? 6514 : protocol.clientCommand(name),
        "actualRetcode", number(reply, "retcode"), "expectedRetcode", expected,
        "before", gameView(before), "notificationDelta", notifications.size() - count));
    if (reply.getDescriptorForType().findFieldByName("game_data") != null && has(reply, "game_data"))
      event.put("nativeReplyData", gameView((DynamicMessage) field(reply, "game_data")));
    if (name.equals("OKEFGCNNEKI")) {
      event.put("entityIDs", values.get("assist_monster_entity_id_list"));
      event.put("shotVectorSent", values.containsKey("GGDCDJDBCCN"));
    }
    events.add(event); System.out.println("AMMO_REPLY=" + new Gson().toJson(event));
    var after = query();
    event.put("after", gameView(after));
    event.put("notificationDelta", notifications.size() - count);
    System.out.println("AMMO_OBSERVATION=" + new Gson().toJson(event));
    check(number(reply, "retcode") == expected, label + "-actual-response-code");
    check(number(after, "CHCOPKAKBNO") == ammo && field(after, "is_bullet_activated").equals(activated)
        && drops(after).size() == bullets, label + "-actual-count-activation-and-drop-list");
    check(notifications.size() == count + expectedNotifications, label + "-native-data-notification-count");
    if (expectedNotifications != 0) check(notifications.getLast().equals(gameView(after)), label + "-notification-matches-queried-state");
    if (expected == 0 && reply.getDescriptorForType().findFieldByName("game_data") != null)
      check(after.equals(field(reply, "game_data")), label + "-actual-response-data-matches-query");
    if (expected != 0) check(before.equals(after), label + "-rejection-keeps-actual-state");
    return after;
  }
  private DynamicMessage activate(String label, int instance, int status, int notices, int ammo) throws Exception {
    return operation(label, "WolfBroGameActivateBulletCsReq", Map.of("group_id", 20, "DAKENPDMDDJ", instance), status, notices, ammo, true, 0);
  }
  private DynamicMessage shoot(String label, List<Integer> ids, int status, int notices, int ammo, int drops) throws Exception {
    return operation(label, "OKEFGCNNEKI", Map.of("assist_monster_entity_id_list", ids), status, notices, ammo, true, drops);
  }
  private void checkpoint(DynamicMessage data, DynamicMessage expectedMotion, int ammo) {
    var archive = (DynamicMessage) field(data, "BBEMIGELGJC");
    check(number(archive, "CHCOPKAKBNO") == ammo && field(archive, "is_bullet_activated").equals(true)
        && drops(archive).isEmpty() && expectedMotion.equals(field(archive, "motion")), "actual-archive-own-ammo-and-source-motion");
  }
  private Map<String, Object> execute(JsonObject input, java.io.BufferedReader control) throws Exception {
    verifySchemas();
    String mode = input.get("mode").getAsString();
    require(Set.of("sequence", "resume", "restored").contains(mode), "Unknown ammo native phase");
    int uid = number(call("PlayerGetTokenCsReq", Map.of("account_uid", input.get("accountUid").getAsString(),
        "token", input.get("comboToken").getAsString(), "platform", 1), 0), "uid");
    var login = call("PlayerLoginCsReq", Map.of(), 0); call("PlayerLoginFinishCsReq", Map.of(), 0);
    int jades = number((DynamicMessage) field(login, "basic_info"), "hcoin");
    check(uid == 10001 && jades == 0, "one-isolated-player-without-reward-grant");
    if (mode.equals("sequence")) {
      call("EnterSceneCsReq", Map.of("entry_id", 2032101), Retcode.MISSION_NOT_FINISH.getVal());
      check(true, "original-2032101-chapter-lock-rejects-before-explicit-history-fixture");
      System.out.println("AMMO_FIXTURE_READY_UID=" + uid); System.out.flush();
      require("PREREQUISITES_APPLIED".equals(control.readLine()), "Original chapter history fixture was not acknowledged");
      call("EnterSceneCsReq", Map.of("entry_id", 2032101), 0);
    }
    var gun = target(20, 300009, "prop");
    check(gun != null && number((DynamicMessage) field(gun, "prop"), "prop_id") == 103068
        && point(input.getAsJsonObject("gunPosition")).equals(field((DynamicMessage) field(gun, "motion"), "pos")), "actual-source-loaded-G20-I300009-prop103068");
    var checkpoint = motion(input.getAsJsonObject("checkpointMotion"));
    DynamicMessage data;
    if (mode.equals("sequence")) {
      var npc = target(22, 200001, "npc_monster");
      require(npc != null, "Original source NPC is absent before any shot");
      int entity = number(npc, "entity_id");
      check(number((DynamicMessage) field(npc, "npc_monster"), "monster_id") == 3002041
          && number((DynamicMessage) field(npc, "npc_monster"), "event_id") == 10301299
          && checkpoint.equals(field(npc, "motion")), "actual-source-loaded-NPC3002041-and-authored-position");
      data = query();
      check(field(data, "uuid").equals("") && number(data, "CHCOPKAKBNO") == 0
          && field(data, "is_bullet_activated").equals(false) && drops(data).isEmpty(), "unplayed-query-has-no-run-gun-or-ammo");
      var group = message("GroupStateInfo", Map.of("group_id", 21, "group_state", 1));
      data = operation("start-zero", "StartWolfBroGameCsReq", Map.of("id", 11, "motion", field(gun, "motion"), "IDANCEPOOEI", group), 0, 1, 0, false, 0);
      String uuid = (String) field(data, "uuid");
      check(UUID.fromString(uuid).toString().equals(uuid), "actual-start-created-a-fresh-run-uuid");
      control(1);
      call("GroupStateChangeCsReq", Map.of("group_state_info", message("GroupStateInfo", Map.of("group_id", 21, "group_state", 2))), 0);
      control(2);
      operation("wrong-gun-instance", "WolfBroGameActivateBulletCsReq", Map.of("group_id", 20, "DAKENPDMDDJ", 0), 5, 0, 0, false, 0);
      activate("first-source-gun-fill", 300009, 0, 1, 5);
      activate("duplicate-gun-does-not-refill-five", 300009, 0, 0, 5);
      shoot("empty-native-shot-spends-one", List.of(), 0, 1, 4, 0);
      activate("source-gun-does-not-refill-after-empty-shot", 300009, 0, 0, 4);
      int removed = deleted.size();
      data = shoot("actual-source-NPC-shot", List.of(entity), 0, 1, 3, 1);
      check(drops(data).equals(List.of(pointView((DynamicMessage) field(checkpoint, "pos")))), "real-shot-drop-is-at-the-original-NPC-position");
      check(deleted.subList(removed, deleted.size()).equals(List.of(entity))
          && target(22, 200001, "npc_monster") == null, "real-shot-deletes-the-actual-entity-on-wire-and-in-scene");
      var pos = (DynamicMessage) field(checkpoint, "pos");
      var wrong = message("Vector", Map.of("x", number(pos, "x") + 1, "y", number(pos, "y"), "z", number(pos, "z")));
      operation("wrong-pickup-position", "WolfBroGamePickupBulletCsReq", Map.of("GGDCDJDBCCN", wrong), 5, 0, 3, true, 1);
      operation("one-real-drop-pickup", "WolfBroGamePickupBulletCsReq", Map.of("GGDCDJDBCCN", pos), 0, 1, 4, true, 0);
      operation("duplicate-pickup-does-not-add-ammo", "WolfBroGamePickupBulletCsReq", Map.of("GGDCDJDBCCN", pos), 5, 0, 4, true, 0);
      shoot("deleted-NPC-cannot-produce-second-drop", List.of(entity), 5, 0, 4, 0);
      data = operation("archive-ammo-four", "ArchiveWolfBroGameCsReq", Map.of("id", 11, "motion", checkpoint), 0, 1, 4, true, 0);
      checkpoint(data, checkpoint, 4);
      shoot("post-archive-empty-shot", List.of(), 0, 1, 3, 0);
      data = operation("restore-saved-ammo", "RestoreWolfBroGameArchiveCsReq", Map.of("id", 11), 0, 1, 4, true, 0);
      checkpoint(data, checkpoint, 4);
      check(field(data, "uuid").equals(uuid) && leaderMotion().equals(checkpoint), "restore-keeps-UUID-and-updates-actual-player-motion");
      operation("repeated-restore-is-read-only", "RestoreWolfBroGameArchiveCsReq", Map.of("id", 11), 0, 0, 4, true, 0);
      activate("restored-gun-still-does-not-refill", 300009, 0, 0, 4);
      check(notifications.size() == 8, "only-eight-committed-sequence-state-changes-notify");
    } else {
      data = query();
      check(hash(data).equals(input.get("gameDataHash").getAsString())
          && field(data, "uuid").equals(input.get("runUuid").getAsString()), "same-UUID-count-and-checkpoint-after-normal-H2-restart-" + mode);
      check(number(data, "CHCOPKAKBNO") == 4 && field(data, "is_bullet_activated").equals(true)
          && drops(data).isEmpty() && target(22, 200001, "npc_monster") == null, "persisted-ammo-four-and-defeated-source-NPC-remains-absent-" + mode);
      checkpoint(data, checkpoint, 4); control(2);
      check(leaderMotion().equals(checkpoint), "saved-player-motion-restored-before-any-new-operation-" + mode);
      activate("restart-gun-cannot-refill", 300009, 0, 0, 4);
      operation("restart-cannot-pick-the-consumed-drop", "WolfBroGamePickupBulletCsReq", Map.of("GGDCDJDBCCN", field(checkpoint, "pos")), 5, 0, 4, true, 0);
      if (mode.equals("resume")) {
        shoot("resume-empty-shot", List.of(), 0, 1, 3, 0);
        data = operation("resume-restore-actual-archive-ammo", "RestoreWolfBroGameArchiveCsReq", Map.of("id", 11), 0, 1, 4, true, 0);
        operation("resume-repeat-restore-read-only", "RestoreWolfBroGameArchiveCsReq", Map.of("id", 11), 0, 0, 4, true, 0);
        data = operation("resume-archive-same-source-checkpoint", "ArchiveWolfBroGameCsReq", Map.of("id", 11, "motion", checkpoint), 0, 1, 4, true, 0);
        check(hash(data).equals(input.get("gameDataHash").getAsString()) && notifications.size() == 3, "resumed-real-operations-preserve-identical-saved-ammo-and-three-notifications");
      } else check(notifications.isEmpty(), "second-restart-read-only-queries-and-rejections-do-not-notify");
    }
    data = query(); control(2);
    check(target(22, 200001, "npc_monster") == null, "committed-source-defeat-is-still-absent-" + mode);
    return Map.of("passed", true, "uid", uid, "mode", mode, "requests", requests, "checks", checks,
        "events", events, "notifications", notifications, "game", gameView(data), "gameDataHash", hash(data),
        "sourceIdentity", Map.of("level", 11, "plane", 20321, "floor", 20321001, "entry", 2032101,
            "gunGroup", 20, "gunInstance", 300009, "gunProp", 103068, "npcGroup", 22, "npcInstance", 200001, "npc", 3002041));
  }
  private DynamicMessage message(String name, Map<String, Object> fields) {
    var builder = DynamicMessage.newBuilder(Objects.requireNonNull(protocol.clientSchema().message(name), name));
    fields.forEach((key, value) -> builder.setField(Objects.requireNonNull(builder.getDescriptorForType().findFieldByName(key), name + "." + key), value));
    return builder.build();
  }
  private Frame raw(int command, byte[] body, int expected) throws Exception {
    var bytes = Unpooled.wrappedBuffer(PacketCodec.encode(command, new byte[0], body));
    try { channel.write(bytes); } finally { bytes.release(); }
    long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
    while (System.nanoTime() < until) {
      if (failure.get() != null) throw new IllegalStateException("Native ammo connection failed", failure.get());
      var frame = inbox.poll(1, TimeUnit.SECONDS);
      if (frame == null) continue;
      observe(frame);
      if (frame.command() == expected) return frame;
    }
    throw new TimeoutException("Native ammo reply " + expected);
  }
  private DynamicMessage reply(String name, Map<String, Object> fields, int command, String response) throws Exception {
    requests++;
    var frame = raw(command, message(name, fields).toByteArray(), protocol.clientCommand(response));
    var value = protocol.clientSchema().parse(response, frame.body());
    require(value.getUnknownFields().asMap().isEmpty(), "Unknown actual native reply fields " + response);
    return value;
  }
  private DynamicMessage call(String name, Map<String, Object> fields, int expected) throws Exception {
    var result = reply(name, fields, protocol.clientCommand(name), name.replace("CsReq", "ScRsp"));
    require(number(result, "retcode") == expected, "Actual " + name + " retcode=" + number(result, "retcode") + " expected=" + expected);
    return result;
  }
  public static void main(String[] args) {
    var probe = new NativeWolfBroAmmoProbe();
    if (args.length == 1 && args[0].equals("--verify-descriptors")) {
      probe.verifySchemas(); System.out.println(new Gson().toJson(Map.of("passed", true, "schemas", probe.schemas, "checks", probe.checks))); return;
    }
    var client = new KcpClient(); int code = 1;
    try {
      var control = new java.io.BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
      var input = JsonParser.parseString(control.readLine()).getAsJsonObject();
      var config = new ChannelConfig();
      config.nodelay(true, 40, 2, true); config.setMtu(1400); config.setSndwnd(256); config.setRcvwnd(256);
      config.setUseConvChannel(true); config.setConv(ThreadLocalRandom.current().nextInt(1, Integer.MAX_VALUE));
      config.setTimeoutMillis(25000); config.setAckNoDelay(true);
      client.init(config, new KcpListener() {
        public void onConnected(Ukcp value) { probe.connected.complete(value); }
        public void handleReceive(ByteBuf bytes, Ukcp value) {
          try { while (bytes.isReadable()) {
            var frame = PacketCodec.decodeNext(bytes); require(frame != null, "Truncated ammo frame");
            probe.inbox.add(new Frame(frame.cmdId(), frame.body()));
          } } catch (Throwable error) { probe.failure.set(error); }
        }
        public void handleException(Throwable error, Ukcp value) { probe.failure.set(error); probe.connected.completeExceptionally(error); }
        public void handleClose(Ukcp value) { probe.failure.compareAndSet(null, new IllegalStateException("Native ammo probe disconnected")); }
      });
      client.connect(new InetSocketAddress("127.0.0.1", Integer.parseInt(args[0])), config);
      probe.channel = probe.connected.get(10, TimeUnit.SECONDS);
      System.out.println(new Gson().toJson(probe.execute(input, control))); code = 0;
    } catch (Exception error) {
      System.err.println("FAILED_AMMO_OBSERVATIONS=" + new Gson().toJson(probe.events));
      error.printStackTrace(System.err);
    } finally { client.stop(); }
    System.exit(code);
  }
}
