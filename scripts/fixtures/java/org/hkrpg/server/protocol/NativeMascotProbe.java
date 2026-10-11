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
 * Original catch reports, declared battle and talk outcomes, conditional reward groups and restart.
 */
public final class NativeMascotProbe {
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

  private Map<Integer, Integer> bag() throws Exception {
    var reply = call("GetBagCsReq");
    require(number(reply, "retcode") == 0, "Actual bag query rejected");
    var result = new TreeMap<Integer, Integer>();
    for (var raw : (List<?>) field(reply, "material_list")) {
      var item = (DynamicMessage) raw;
      require(
          result.put(number(item, "tid"), number(item, "num")) == null,
          "Duplicate actual bag material");
    }
    return result;
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

  private DynamicMessage actor(DynamicMessage scene, JsonObject source, int state) {
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
                  && number(prop, "prop_state") == state,
              "Incorrect source chest state " + source.get("id"));
          found = entity;
        }
      }
    require(found != null && number(found, "entity_id") > 0, "Original chest not loaded");
    return found;
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

  private int count(int id, Set<Integer> known) {
    if (id >= 6029853 && id <= 6029855)
      return (int)
          known.stream().filter(k -> Set.of(20411614, 20411625, 20412620).contains(k)).count();
    return id == 6029856
        ? (int) known.stream().filter(k -> k >= 20411654 && k <= 20411657).count()
        : id == 6029858
            ? (int) known.stream().filter(k -> k == 20411666 || k == 20411667).count()
            : known.size();
  }

  private void goals(JsonArray goals, Set<Integer> claimed, Set<Integer> known) throws Exception {
    for (var raw : goals) {
      var goal = raw.getAsJsonObject();
      int id = goal.get("id").getAsInt(), target = goal.get("goal").getAsInt();
      int value = Math.min(count(id, known), target);
      quest(id, value, claimed.contains(id) ? 3 : value == target ? 2 : 1);
    }
  }

  private JsonObject placement(JsonObject source, int instance, int prop) {
    var copy = source.deepCopy();
    copy.addProperty("instance", instance);
    copy.addProperty("prop", prop);
    return copy;
  }

  private void absent(DynamicMessage scene, JsonObject source, int instance) {
    for (var group : (List<?>) field(scene, "entity_group_list"))
      for (var raw : (List<?>) field((DynamicMessage) group, "entity_list")) {
        var e = (DynamicMessage) raw;
        require(
            number(e, "group_id") != source.get("group").getAsInt()
                || number(e, "inst_id") != instance,
            "Destroyed shield respawned");
      }
  }

  private DynamicMessage travel(JsonObject source) throws Exception {
    var req = builder("EnterSceneCsReq");
    set(req, "entry_id", source.get("entry").getAsInt());
    require(number(call("EnterSceneCsReq", req), "retcode") == 0, "Native map travel rejected");
    return scene(source);
  }

  private DynamicMessage groupChange(JsonObject source, int state) throws Exception {
    var row = builder("GroupStateInfo");
    set(row, "group_id", source.get("group").getAsInt());
    set(row, "entry_id", source.get("entry").getAsInt());
    set(row, "group_state", state);
    var req = builder("GroupStateChangeCsReq");
    set(req, "group_state_info", row.build());
    return call("GroupStateChangeCsReq", req);
  }

  private void groupState(DynamicMessage scene, JsonObject source, int expected) {
    var rows =
        ((List<?>) field(scene, "entity_group_list"))
            .stream()
                .map(v -> (DynamicMessage) v)
                .filter(g -> number(g, "group_id") == source.get("group").getAsInt())
                .toList();
    require(
        rows.size() == 1 && number(rows.getFirst(), "state") == expected,
        "Different saved mascot stage " + source + " expected " + expected + " actual " + rows);
  }

  private DynamicMessage talk(JsonObject source) throws Exception {
    var req = builder("FinishTalkMissionCsReq");
    int sub = source.get("talk").getAsInt();
    set(req, "sub_mission_id", sub);
    set(req, "talk_str", "Mission_" + sub);
    return call("FinishTalkMissionCsReq", req);
  }

  private void battle(JsonObject source) throws Exception {
    var entry = builder("SceneEnterStageCsReq");
    set(entry, "event_id", source.get("event").getAsInt());
    var start = call("SceneEnterStageCsReq", entry);
    require(number(start, "retcode") == 0, "Original event battle rejected");
    var info = (DynamicMessage) field(start, "battle_info");
    int id = number(info, "battle_id"), stage = number(info, "stage_id");
    require(id > 0 && stage == source.get("stage").getAsInt(), "Different original battle stage");
    var req = builder("PVEBattleResultCsReq");
    set(req, "battle_id", id);
    set(req, "stage_id", stage);
    var end = req.getDescriptorForType().findFieldByName("end_status");
    req.setField(end, end.getEnumType().findValueByName("BATTLE_END_WIN"));
    set(req, "stt", builder("BattleStatistics").build());
    var response = call("PVEBattleResultCsReq", req);
    require(
        number(response, "retcode") == 0 && number(response, "battle_id") == id,
        "Original result rejected");
    require(number(call("PVEBattleResultCsReq", req), "retcode") != 0, "Repeated battle paid");
    rejected++;
  }

  private Map<String, Object> execute(JsonObject control) throws Exception {
    var token = builder("PlayerGetTokenCsReq");
    set(token, "account_uid", control.get("accountUid").getAsString());
    set(token, "token", control.get("comboToken").getAsString());
    set(token, "platform", 1);
    var login = call("PlayerGetTokenCsReq", token);
    require(number(login, "retcode") == 0, "Token rejected");
    int uid = number(login, "uid");
    require(
        number(call("PlayerLoginCsReq"), "retcode") == 0
            && number(call("PlayerLoginFinishCsReq"), "retcode") == 0,
        "Login rejected");
    var known = new TreeSet<Integer>();
    var claimed = new TreeSet<Integer>();
    for (var raw : control.getAsJsonArray("known")) known.add(raw.getAsInt());
    for (var raw : control.getAsJsonArray("claimed")) claimed.add(raw.getAsInt());
    var stages = control.getAsJsonObject("mascotProgress").deepCopy();
    var goals = control.getAsJsonArray("goals");
    goals(goals, claimed, known);
    var accepted = new ArrayList<String>();
    var opened = new ArrayList<Integer>();
    int wins = 0;
    for (var raw : control.getAsJsonArray("actions")) {
      var source = raw.getAsJsonObject();
      int chest = source.get("id").getAsInt();
      var treasure = source.getAsJsonObject("treasure");
      var current = travel(source);
      int stage = stages.has("" + chest) ? stages.get("" + chest).getAsInt() : 0;
      groupState(current, source, stage);
      if (stage < 4) {
        absent(current, treasure, treasure.get("instance").getAsInt());
        require(number(groupChange(source, 4), "retcode") != 0, "Premature reveal accepted");
        rejected++;
      }
      if (source.get("catch").getAsBoolean()) {
        require(stage == 0, "Catch prewritten");
        var before = bag();
        int notes = openedNotify.size();
        for (int next = 1; next <= 2; next++) {
          require(
              number(groupChange(source, next), "retcode") == 0,
              "Original catch request rejected " + next);
          require(
              number(groupChange(source, next), "retcode") != 0, "Original catch replay accepted");
          rejected++;
          stage = next;
          stages.addProperty("" + chest, stage);
          accepted.add(chest + ":" + stage);
          current = scene(source);
          groupState(current, source, stage);
          absent(current, treasure, treasure.get("instance").getAsInt());
        }
        require(before.equals(bag()) && notes == openedNotify.size(), "Catch paid loot");
      }
      if (source.get("return").getAsBoolean()) {
        require(stage == 2, "Return has no saved catch");
        if (source.get("event").getAsInt() > 0) {
          require(number(talk(source), "retcode") != 0, "Talk skipped declared battle");
          rejected++;
          battle(source);
          wins++;
          current = scene(source);
          groupState(current, source, 2);
          absent(current, treasure, treasure.get("instance").getAsInt());
        }
        require(number(talk(source), "retcode") == 0, "Original return talk rejected");
        require(number(talk(source), "retcode") != 0, "Repeated talk accepted");
        rejected++;
        current = scene(source);
        groupState(current, source, 3);
        absent(current, treasure, treasure.get("instance").getAsInt());
        var before = bag();
        int notes = openedNotify.size();
        require(
            number(groupChange(source, 4), "retcode") == 0,
            "Original reward appearance request rejected");
        require(number(groupChange(source, 4), "retcode") != 0, "Repeated reveal accepted");
        rejected++;
        stage = 4;
        stages.addProperty("" + chest, 4);
        accepted.add(chest + ":3");
        accepted.add(chest + ":4");
        current = scene(source);
        groupState(current, source, 4);
        actor(current, treasure, 12);
        require(
            before.equals(bag()) && notes == openedNotify.size(), "Reward appearance paid loot");
      }
      if (source.get("open").getAsBoolean()) {
        require(stage == 4, "Reward has no returned mascot");
        var a = actor(current, treasure, 12);
        int notes = openedNotify.size();
        var result = interact(current, a, 112131);
        require(
            number(result, "retcode") == 0 && number(result, "prop_state") == 13,
            "Original mascot chest did not open");
        require(
            openedNotify.size() == notes + 1 && openedNotify.getLast() == chest,
            "Different chest notification");
        require(number(interact(current, a, 112131), "retcode") != 0, "Repeated mascot chest paid");
        rejected++;
        require(known.add(chest), "Opening prewritten");
        opened.add(chest);
      }
      if (source.get("inspect").getAsBoolean() && stage == 4) {
        var a = actor(current, treasure, known.contains(chest) ? 13 : 12);
        if (known.contains(chest)) {
          require(number(interact(current, a, 112131), "retcode") != 0, "Restored chest paid");
          rejected++;
        }
      }
      goals(goals, claimed, known);
    }
    for (var raw : control.getAsJsonArray("claims")) {
      int id = raw.getAsInt();
      var goal =
          goals.asList().stream()
              .map(JsonElement::getAsJsonObject)
              .filter(g -> g.get("id").getAsInt() == id)
              .findFirst()
              .orElseThrow();
      reward(claim(id), goal);
      require(claimed.add(id), "Claim prewritten");
      require(number(claim(id), "retcode") != 0, "Repeated goal paid");
      rejected++;
    }
    goals(goals, claimed, known);
    var result = new LinkedHashMap<String, Object>();
    result.put("passed", true);
    result.put("uid", uid);
    result.put("nativeRequests", requests);
    result.put("known", known);
    result.put("claimed", claimed);
    result.put("mascotProgress", stages);
    result.put("acceptedSteps", accepted);
    result.put("newlyOpened", opened);
    result.put("declaredBattleWins", wins);
    result.put("rejectedRequests", rejected);
    result.put("chestIdentityNotifications", openedNotify);
    result.put("actualBag", bag());
    result.put("clientGameplayVerified", false);
    return result;
  }

  public static void main(String[] args) {
    var probe = new NativeMascotProbe();
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
