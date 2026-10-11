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
 * Source-bound client finish reports, real chest openings and persistent time-flame activity
 * credit.
 */
public final class NativeTimeRelayFlameProbe {
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

  private void goals(JsonArray goals, Set<Integer> claimed, int count, int flameCount)
      throws Exception {
    for (var raw : goals) {
      var goal = raw.getAsJsonObject();
      int id = goal.get("id").getAsInt(), target = goal.get("goal").getAsInt();
      int value = Math.min(id == 6029856 ? flameCount : count, target);
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
    var current = scene(control);
    var known = new TreeSet<Integer>();
    var claimed = new TreeSet<Integer>();
    for (var id : control.getAsJsonArray("known")) known.add(id.getAsInt());
    for (var id : control.getAsJsonArray("claimed")) claimed.add(id.getAsInt());
    var unlocked = new TreeSet<Integer>();
    for (var id : control.getAsJsonArray("unlocked")) unlocked.add(id.getAsInt());
    var goals = control.getAsJsonArray("goals");
    goals(
        goals,
        claimed,
        known.size(),
        (int) known.stream().filter(id -> id >= 20411654 && id <= 20411657).count());
    for (var raw : control.getAsJsonArray("allChests")) {
      var source = raw.getAsJsonObject();
      int id = source.get("id").getAsInt();
      var found =
          actor(
              current,
              source,
              known.contains(id)
                  ? 13
                  : source.get("prop").getAsInt() == 104046 && !unlocked.contains(id) ? 11 : 12);
      if (known.contains(id)) {
        require(
            number(interact(current, found, 112131), "retcode") != 0,
            "Repeated saved chest opened");
        rejected++;
      }
    }
    for (var raw : goals) {
      var goal = raw.getAsJsonObject();
      int id = goal.get("id").getAsInt();
      if (claimed.contains(id)
          || (id == 6029856
                  ? known.stream().filter(k -> k >= 20411654 && k <= 20411657).count()
                  : known.size())
              < goal.get("goal").getAsInt())
        require(number(claim(id), "retcode") != 0, "Premature or repeated activity reward paid");
    }
    var newlyUnlocked = new ArrayList<Integer>();
    for (var raw : control.getAsJsonArray("finishChests")) {
      var source = raw.getAsJsonObject();
      int id = source.get("id").getAsInt();
      var found = actor(current, source, 11);
      var beforeBag = bag();
      int notifications = openedNotify.size();
      require(
          number(interact(current, found, 112131), "retcode") != 0,
          "Locked puzzle opened before finish report");
      rejected++;
      reply = interact(current, found, 111120);
      require(
          number(reply, "retcode") == 0 && number(reply, "prop_state") == 12,
          "Source finish report did not unlock original chest");
      require(unlocked.add(id), "Unlock prewritten");
      newlyUnlocked.add(id);
      require(
          number(interact(current, found, 111120), "retcode") != 0,
          "Finish report replay accepted");
      rejected++;
      require(
          openedNotify.size() == notifications && bag().equals(beforeBag),
          "Unlock paid inventory or emitted an opening notification");
      goals(
          goals,
          claimed,
          known.size(),
          (int) known.stream().filter(k -> k >= 20411654 && k <= 20411657).count());
      current = scene(control);
      actor(current, source, 12);
    }
    var opened = new ArrayList<Integer>();
    for (var raw : control.getAsJsonArray("chests")) {
      var source = raw.getAsJsonObject();
      int id = source.get("id").getAsInt();
      var found = actor(current, source, 12);
      int notifications = openedNotify.size();
      reply = interact(current, found, 112131);
      require(
          number(reply, "retcode") == 0 && number(reply, "prop_state") == 13,
          "Original chest did not open");
      require(
          openedNotify.size() == notifications + 1 && openedNotify.getLast() == id,
          "Original chest identity notification differs");
      require(number(interact(current, found, 112131), "retcode") != 0, "Repeated chest opened");
      rejected++;
      require(openedNotify.size() == notifications + 1, "Repeated chest notification");
      require(known.add(id), "World opening prewritten");
      opened.add(id);
    }
    goals(
        goals,
        claimed,
        known.size(),
        (int) known.stream().filter(id -> id >= 20411654 && id <= 20411657).count());
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
    goals(
        goals,
        claimed,
        known.size(),
        (int) known.stream().filter(id -> id >= 20411654 && id <= 20411657).count());
    current = scene(control);
    for (var raw : control.getAsJsonArray("allChests")) {
      var source = raw.getAsJsonObject();
      actor(
          current,
          source,
          known.contains(source.get("id").getAsInt())
              ? 13
              : source.get("prop").getAsInt() == 104046
                      && !unlocked.contains(source.get("id").getAsInt())
                  ? 11
                  : 12);
    }
    var result = new LinkedHashMap<String, Object>();
    result.put("passed", true);
    result.put("uid", uid);
    result.put("nativeRequests", requests);
    result.put("openedOriginalChests", opened);
    result.put("known", known);
    result.put("unlocked", unlocked);
    result.put("newlyUnlocked", newlyUnlocked);
    result.put("actualBag", bag());
    result.put("claimed", claimed);
    result.put("chestIdentityNotifications", openedNotify);
    result.put("rejectedRepeatedOpenings", rejected);
    result.put("clientGameplayVerified", false);
    return result;
  }

  public static void main(String[] args) {
    var probe = new NativeTimeRelayFlameProbe();
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
