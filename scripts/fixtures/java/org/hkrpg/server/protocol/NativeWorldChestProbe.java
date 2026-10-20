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

/** Original scene chests, source activity goals, basic server rewards and persistent state. */
public final class NativeWorldChestProbe {
  private record Frame(int command, byte[] body) {}

  private final ProtocolBridge protocol = ProtocolBridge.forVersion("4.6.0");
  private final BlockingQueue<Frame> inbox = new LinkedBlockingQueue<>();
  private final AtomicReference<Throwable> failure = new AtomicReference<>();
  private final CompletableFuture<Ukcp> connected = new CompletableFuture<>();
  private Ukcp channel;
  private int requests, reports, rejected;
  private final List<Integer> openedNotify = new ArrayList<>();

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
      if (frame.command() == protocol.clientCommand("OpenChestScNotify")) {
        var notify = protocol.clientSchema().parse("OpenChestScNotify", frame.body());
        require(notify.getUnknownFields().asMap().isEmpty(), "Unknown chest notification fields");
        openedNotify.add(number(notify, "chest_id"));
        continue;
      }
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

  private DynamicMessage actor(DynamicMessage scene, JsonObject source, boolean used) {
    DynamicMessage found = null;
    for (var group : (List<?>) field(scene, "entity_group_list"))
      for (var raw : (List<?>) field((DynamicMessage) group, "entity_list")) {
        var entity = (DynamicMessage) raw;
        if (number(entity, "group_id") == source.get("group").getAsInt()
            && number(entity, "inst_id") == source.get("instance").getAsInt()) {
          require(
              found == null
                  && entity.hasField(entity.getDescriptorForType().findFieldByName("prop")),
              "Wrong or duplicate original chest actor");
          var prop = (DynamicMessage) field(entity, "prop");
          require(
              number(prop, "prop_id") == source.get("prop").getAsInt()
                  && number(prop, "prop_state") == (used ? 13 : 12),
              "Incorrect source chest state " + source.get("id"));
          found = entity;
        }
      }
    require(found != null && number(found, "entity_id") > 0, "Original chest not loaded");
    return found;
  }

  private DynamicMessage interact(DynamicMessage scene, DynamicMessage actor) throws Exception {
    return interact(scene, actor, 112131);
  }

  private DynamicMessage interact(DynamicMessage scene, DynamicMessage actor, int config)
      throws Exception {
    var request = builder("InteractPropCsReq");
    set(request, "interact_id2", config);
    set(request, "prop_entity_id", number(actor, "entity_id"));
    set(request, "interact_id", field(scene, "interact_id"));
    var reply = call("InteractPropCsReq", request);
    require(number(reply, "prop_entity_id") == number(actor, "entity_id"), "Wrong response actor");
    return reply;
  }

  private DynamicMessage entity(DynamicMessage scene, int group, int instance) {
    var matches = new ArrayList<DynamicMessage>();
    for (var raw : (List<?>) field(scene, "entity_group_list"))
      for (var row : (List<?>) field((DynamicMessage) raw, "entity_list")) {
        var e = (DynamicMessage) row;
        if (number(e, "group_id") == group && number(e, "inst_id") == instance) matches.add(e);
      }
    require(matches.size() <= 1, "Duplicate source actor");
    return matches.isEmpty() ? null : matches.getFirst();
  }

  private DynamicMessage chestState(DynamicMessage scene, JsonObject spec, int state) {
    var e = entity(scene, spec.get("group").getAsInt(), spec.get("instance").getAsInt());
    if (state == 13
        && spec.has("unloadsAfterClaim")
        && spec.get("unloadsAfterClaim").getAsBoolean()) {
      require(e == null, "Completed mission retained its Accept-only treasure group");
      return null;
    }
    require(e != null, "Original mechanism chest missing");
    var prop = (DynamicMessage) field(e, "prop");
    require(
        number(prop, "prop_id") == spec.get("prop").getAsInt()
            && number(prop, "prop_state") == state,
        "Wrong mechanism chest state: " + prop);
    return e;
  }

  private Map<String, Object> mechanism(JsonObject control, int uid) throws Exception {
    var spec = control.getAsJsonObject("mechanism");
    String op = spec.get("op").getAsString();
    int id = spec.get("id").getAsInt(), group = spec.get("group").getAsInt();
    int actorGroup = spec.has("actorGroup") ? spec.get("actorGroup").getAsInt() : group;
    var current = scene(control);
    var chest = chestState(current, spec, spec.get("before").getAsInt());
    states(current, actorGroup, spec.getAsJsonObject("beforeStates"));
    int notifications = openedNotify.size();
    if (chest != null && !op.equals("claim") && spec.get("before").getAsInt() != 12) {
      require(number(interact(current, chest), "retcode") != 0, "Premature or repeated loot claim");
      rejected++;
    }
    if (op.equals("guard")) {
      int instance = spec.get("guard").getAsInt();
      var monster = entity(current, group, instance);
      require(monster != null, "Original guard missing");
      var npc = (DynamicMessage) field(monster, "npc_monster");
      require(
          number(npc, "monster_id") == spec.get("npc").getAsInt()
              && number(npc, "event_id") == spec.get("event").getAsInt(),
          "Wrong original guard");
      var cast = builder("SceneCastSkillCsReq");
      int caster = number(current, "leader_entity_id");
      require(caster > 0, "Missing live caster");
      set(cast, "cast_entity_id", caster);
      set(cast, "attacked_by_entity_id", caster);
      set(cast, "skill_index", 0);
      set(cast, "hit_target_entity_id_list", List.of(number(monster, "entity_id")));
      var attack = call("SceneCastSkillCsReq", cast);
      require(
          number(attack, "retcode") == 0
              && attack.hasField(attack.getDescriptorForType().findFieldByName("battle_info")),
          "Original guard did not enter battle: " + attack);
      var battle = (DynamicMessage) field(attack, "battle_info");
      require(
          number(battle, "stage_id") == spec.get("event").getAsInt() * 10,
          "Guard stage identity changed");
      require(!((List<?>) field(battle, "monster_wave_list")).isEmpty(), "Missing guard wave");
      var win = builder("PVEBattleResultCsReq");
      set(win, "battle_id", number(battle, "battle_id"));
      set(win, "stage_id", number(battle, "stage_id"));
      set(
          win,
          "end_status",
          win.getDescriptorForType()
              .findFieldByName("end_status")
              .getEnumType()
              .findValueByName("BATTLE_END_WIN"));
      require(number(call("PVEBattleResultCsReq", win), "retcode") == 0, "Guard victory rejected");
      require(
          number(call("PVEBattleResultCsReq", win), "retcode") != 0, "Duplicate victory accepted");
    } else if (op.equals("step")) {
      int instance =
          spec.has("target") ? spec.get("target").getAsInt() : spec.get("controller").getAsInt();
      var target = entity(current, actorGroup, instance);
      require(target != null, "Staged actor missing");
      for (var config : spec.getAsJsonArray("configs")) {
        var response = interact(current, target, config.getAsInt());
        require(number(response, "retcode") == 0, "Authored stage rejected: " + response);
        require(
            number(interact(current, target, config.getAsInt()), "retcode") != 0,
            "Repeated stage accepted");
      }
    } else if (op.equals("destroy")) {
      var target = entity(current, group, spec.get("shield").getAsInt());
      require(target != null, "Bound shield missing");
      var cast = builder("SceneCastSkillCsReq");
      int caster = number(current, "leader_entity_id");
      require(caster > 0, "Missing live caster");
      set(cast, "cast_entity_id", caster);
      set(cast, "attacked_by_entity_id", caster);
      set(cast, "skill_index", 0);
      set(cast, "hit_target_entity_id_list", List.of(number(target, "entity_id")));
      var response = call("SceneCastSkillCsReq", cast);
      require(number(response, "retcode") == 0, "Bound shield attack rejected: " + response);
    } else if (op.equals("finish")) {
      var target =
          spec.get("controller").getAsInt() == 0
              ? chest
              : entity(current, actorGroup, spec.get("controller").getAsInt());
      require(target != null, "Original puzzle controller missing");
      var result = interact(current, target, spec.get("finish").getAsInt());
      require(number(result, "retcode") == 0, "Source puzzle finish rejected: " + result);
      require(
          number(interact(current, target, spec.get("finish").getAsInt()), "retcode") != 0,
          "Repeated completion accepted");
    } else if (op.equals("talk")) {
      var request = builder("FinishTalkMissionCsReq");
      int talk = spec.get("talk").getAsInt();
      set(request, "sub_mission_id", talk);
      set(request, "talk_str", "Mission_" + talk);
      require(number(call("FinishTalkMissionCsReq", request), "retcode") == 0, "Dialogue rejected");
      require(
          number(call("FinishTalkMissionCsReq", request), "retcode") != 0,
          "Repeated dialogue accepted");
    } else if (op.equals("property")) {
      var request = builder("UpdateGroupPropertyCsReq");
      set(request, "floor_id", spec.get("floor").getAsInt());
      set(request, "group_id", actorGroup);
      set(request, "property_name", spec.get("property").getAsString());
      set(request, "OLBEPIBLDBD", 1);
      var response = call("UpdateGroupPropertyCsReq", request);
      require(
          number(response, "retcode") == 0
              && number(response, "old_value") == 0
              && number(response, "new_value") == 1,
          "Property completion rejected: " + response);
      response = call("UpdateGroupPropertyCsReq", request);
      require(
          number(response, "retcode") == 0
              && number(response, "old_value") == 1
              && number(response, "new_value") == 1,
          "Property replay lost receipt: " + response);
    } else if (op.equals("claim")) {
      var result = interact(current, chest);
      require(
          number(result, "retcode") == 0 && number(result, "prop_state") == 13,
          "Unlocked original chest did not pay");
      require(
          openedNotify.size() == notifications + 1 && openedNotify.getLast() == id,
          "Wrong source chest notification");
      require(number(interact(current, chest), "retcode") != 0, "Duplicate reward delivered");
      rejected++;
    } else require(op.equals("verify"), "Unknown mechanism test action");
    current = scene(control);
    chestState(current, spec, spec.get("after").getAsInt());
    states(current, actorGroup, spec.getAsJsonObject("states"));
    if (spec.has("absentAfter"))
      for (var instance : spec.getAsJsonArray("absentAfter"))
        require(
            entity(current, actorGroup, instance.getAsInt()) == null,
            "Completed objective did not unload its authored controller group");
    if (spec.has("shield") && Set.of(12, 13).contains(spec.get("after").getAsInt()))
      require(
          entity(current, group, spec.get("shield").getAsInt()) == null,
          "Shield survived destruction");
    for (var gone : spec.getAsJsonArray("defeated"))
      require(entity(current, group, gone.getAsInt()) == null, "Defeated guard respawned");
    for (var live : spec.getAsJsonArray("remaining"))
      require(entity(current, group, live.getAsInt()) != null, "Undefeated guard disappeared");
    if (!op.equals("claim"))
      require(openedNotify.size() == notifications, "Unlock generated a loot notification");
    var known = new TreeSet<Integer>();
    control.getAsJsonArray("known").forEach(v -> known.add(v.getAsInt()));
    if (op.equals("claim")) require(known.add(id), "Prewritten mechanism reward");
    var result = new LinkedHashMap<String, Object>();
    result.put("passed", true);
    result.put("uid", uid);
    result.put("nativeRequests", requests);
    result.put("openedOriginalChests", op.equals("claim") ? List.of(id) : List.of());
    result.put("known", known);
    result.put("claimed", List.of());
    result.put("chestIdentityNotifications", openedNotify);
    result.put("rejectedRepeatedOpenings", rejected);
    result.put("mechanism", spec);
    result.put("clientGameplayVerified", false);
    return result;
  }

  private void states(DynamicMessage scene, int group, JsonObject expected) {
    if (expected == null) return;
    for (var item : expected.entrySet()) {
      var actor = entity(scene, group, Integer.parseInt(item.getKey()));
      require(
          actor != null
              && number((DynamicMessage) field(actor, "prop"), "prop_state")
                  == item.getValue().getAsInt(),
          "Wrong restored actor state " + group + "/" + item.getKey());
    }
  }

  private void goals(JsonArray goals, Set<Integer> claimed, int count) throws Exception {
    for (var raw : goals) {
      var goal = raw.getAsJsonObject();
      int id = goal.get("id").getAsInt(), target = goal.get("goal").getAsInt();
      int value = Math.min(count, target);
      quest(id, value, claimed.contains(id) ? 3 : value == target ? 2 : 1);
    }
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
    if (control.has("mechanism")) return mechanism(control, uid);
    var current = scene(control);
    var known = new TreeSet<Integer>();
    var claimed = new TreeSet<Integer>();
    for (var id : control.getAsJsonArray("known")) known.add(id.getAsInt());
    for (var id : control.getAsJsonArray("claimed")) claimed.add(id.getAsInt());
    var goals = control.getAsJsonArray("goals");
    goals(goals, claimed, known.size());
    for (var raw : control.getAsJsonArray("allChests")) {
      var source = raw.getAsJsonObject();
      int id = source.get("id").getAsInt();
      var found = actor(current, source, known.contains(id));
      if (known.contains(id)) {
        require(number(interact(current, found), "retcode") != 0, "Repeated saved chest opened");
        rejected++;
      }
    }
    for (var raw : goals) {
      var goal = raw.getAsJsonObject();
      int id = goal.get("id").getAsInt();
      if (claimed.contains(id) || known.size() < goal.get("goal").getAsInt())
        require(number(claim(id), "retcode") != 0, "Premature or repeated activity reward paid");
    }
    var opened = new ArrayList<Integer>();
    for (var raw : control.getAsJsonArray("chests")) {
      var source = raw.getAsJsonObject();
      int id = source.get("id").getAsInt();
      var found = actor(current, source, false);
      int notifications = openedNotify.size();
      reply = interact(current, found);
      require(
          number(reply, "retcode") == 0 && number(reply, "prop_state") == 13,
          "Original chest did not open");
      require(
          openedNotify.size() == notifications + 1 && openedNotify.getLast() == id,
          "Original chest identity notification differs");
      require(number(interact(current, found), "retcode") != 0, "Repeated chest opened");
      rejected++;
      require(openedNotify.size() == notifications + 1, "Repeated chest notification");
      require(known.add(id), "World opening prewritten");
      opened.add(id);
    }
    goals(goals, claimed, known.size());
    int targetClaim = control.get("claim").getAsInt();
    if (targetClaim > 0) {
      var goal =
          goals.asList().stream()
              .map(JsonElement::getAsJsonObject)
              .filter(g -> g.get("id").getAsInt() == targetClaim)
              .findFirst()
              .orElseThrow();
      reward(claim(targetClaim), goal);
      require(claimed.add(targetClaim), "Claim prewritten");
      require(number(claim(targetClaim), "retcode") != 0, "Repeated source reward paid");
    }
    goals(goals, claimed, known.size());
    current = scene(control);
    for (var raw : control.getAsJsonArray("allChests")) {
      var source = raw.getAsJsonObject();
      actor(current, source, known.contains(source.get("id").getAsInt()));
    }
    var result = new LinkedHashMap<String, Object>();
    result.put("passed", true);
    result.put("uid", uid);
    result.put("nativeRequests", requests);
    result.put("openedOriginalChests", opened);
    result.put("known", known);
    result.put("claimed", claimed);
    result.put("chestIdentityNotifications", openedNotify);
    result.put("rejectedRepeatedOpenings", rejected);
    result.put("clientGameplayVerified", false);
    return result;
  }

  public static void main(String[] args) {
    var probe = new NativeWorldChestProbe();
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
