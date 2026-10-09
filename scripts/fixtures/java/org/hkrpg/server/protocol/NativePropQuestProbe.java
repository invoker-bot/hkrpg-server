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

/** External-only actual source prop destruction and normal H2 recovery; no game UI acceptance. */
public final class NativePropQuestProbe {
  private record Frame(int command, byte[] body) {}

  private final ProtocolBridge protocol = ProtocolBridge.forVersion("4.6.0");
  private final BlockingQueue<Frame> inbox = new LinkedBlockingQueue<>();
  private final AtomicReference<Throwable> failure = new AtomicReference<>();
  private final CompletableFuture<Ukcp> connected = new CompletableFuture<>();
  private Ukcp channel;
  private int target,
      current,
      queries,
      progressNotifications,
      removalNotifications,
      jadeNotifications,
      actualDestructions,
      explorations,
      leader,
      expectedEntity,
      staleRequests;
  private int observedJades;
  private boolean awaitDestruction, awaitClaim;
  private Set<Integer> claimed = Set.of();
  private final Map<Integer, Integer> questProgress = new TreeMap<>(),
      questPhases = new TreeMap<>();
  private final List<Map<String, Integer>> operations = new ArrayList<>();
  private final List<String> checks = new ArrayList<>();

  private static Object field(DynamicMessage m, String name) {
    return m.getField(m.getDescriptorForType().findFieldByName(name));
  }

  private static int number(DynamicMessage m, String name) {
    return (Integer) field(m, name);
  }

  private static void require(boolean ok, String reason) {
    if (!ok) throw new IllegalStateException(reason);
  }

  private static int phase(DynamicMessage m) {
    return ((com.google.protobuf.Descriptors.EnumValueDescriptor) field(m, "status")).getNumber();
  }

  private void validateQuests(DynamicMessage m, int count, Set<Integer> closed) {
    require(number(m, "retcode") == 0, "Prop quest query rejected");
    var rows = new HashMap<Integer, DynamicMessage>();
    for (var raw : (List<?>) field(m, "quest_list")) {
      var row = (DynamicMessage) raw;
      int id = number(row, "id");
      if (id == 4092312 || id == 4092313)
        require(rows.put(id, row) == null, "Duplicate original prop goal");
    }
    require(rows.size() == 2, "Original prop goals missing");
    for (int id : List.of(4092312, 4092313)) {
      int goal = id == 4092312 ? 20 : 400, status = closed.contains(id) ? 3 : count >= goal ? 2 : 1;
      var row = rows.get(id);
      require(
          number(row, "progress") == Math.min(goal, count)
              && phase(row) == status
              && ((Long) field(row, "finish_time") > 0) == (status != 1),
          "Prop progress/phase/time differs from actual casts");
      questProgress.put(id, number(row, "progress"));
      questPhases.put(id, phase(row));
    }
  }

  private void observe(Frame frame) throws Exception {
    if (awaitDestruction && frame.command() == protocol.clientCommand("GetQuestDataScRsp")) {
      validateQuests(
          protocol.clientSchema().parse("GetQuestDataScRsp", frame.body()), current, claimed);
      progressNotifications++;
    }
    if (awaitDestruction
        && frame.command() == protocol.clientCommand("SceneGroupRefreshScNotify")) {
      var m = protocol.clientSchema().parse("SceneGroupRefreshScNotify", frame.body());
      require(number(m, "floor_id") == 80001003, "Removed source prop in wrong floor");
      var groups = (List<?>) field(m, "group_refresh_list");
      require(groups.size() == 1, "Unexpected removal group count");
      var group = (DynamicMessage) groups.getFirst();
      require(number(group, "group_id") == 21, "Wrong removed group");
      var entities = (List<?>) field(group, "refresh_entity");
      require(entities.size() == 1, "Unexpected removal list");
      require(
          number((DynamicMessage) entities.getFirst(), "delete_entity") == expectedEntity,
          "Removal lost original entity identity");
      removalNotifications++;
    }
    if (awaitClaim && frame.command() == protocol.clientCommand("PlayerSyncScNotify")) {
      var m = protocol.clientSchema().parse("PlayerSyncScNotify", frame.body());
      if (m.hasField(m.getDescriptorForType().findFieldByName("basic_info"))) {
        observedJades = number((DynamicMessage) field(m, "basic_info"), "hcoin");
        require(
            observedJades == 5 || target == 400 && observedJades == 15,
            "Prop goal reward amount changed");
        jadeNotifications++;
      }
    }
  }

  private Map<Integer, Integer> scene(DynamicMessage scene, Set<Integer> expected) {
    require(
        number(scene, "entry_id") == 8000103
            && number(scene, "plane_id") == 80001
            && number(scene, "floor_id") == 80001003,
        "Wrong original room entrance/floor");
    leader = number(scene, "leader_entity_id");
    require(leader > 0, "Missing actual caster");
    var found = new TreeMap<Integer, Integer>();
    for (var raw : (List<?>) field(scene, "entity_group_list"))
      for (var value : (List<?>) field((DynamicMessage) raw, "entity_list")) {
        var e = (DynamicMessage) value;
        if (!e.hasField(e.getDescriptorForType().findFieldByName("prop"))
            || number(e, "group_id") != 21) continue;
        int inst = number(e, "inst_id");
        if (inst != 300007 && inst != 300008) continue;
        require(
            number((DynamicMessage) field(e, "prop"), "prop_id") == 100027,
            "Ordinary source prop was replaced");
        require(
            found.put(inst, number(e, "entity_id")) == null, "Duplicate original prop instance");
      }
    require(
        found.keySet().equals(expected),
        "Consumed/remaining source prop state differs: " + found.keySet());
    return found;
  }

  private DynamicMessage cast(int entity, boolean success) throws Exception {
    var b = DynamicMessage.newBuilder(protocol.clientSchema().message("SceneCastSkillCsReq"));
    for (var e :
        Map.of(
                "cast_entity_id",
                leader,
                "attacked_by_entity_id",
                leader,
                "skill_index",
                0,
                "hit_target_entity_id_list",
                List.of(entity))
            .entrySet())
      b.setField(b.getDescriptorForType().findFieldByName(e.getKey()), e.getValue());
    var f =
        raw(
            protocol.clientCommand("SceneCastSkillCsReq"),
            b.build().toByteArray(),
            protocol.clientCommand("SceneCastSkillScRsp"));
    var m = protocol.clientSchema().parse("SceneCastSkillScRsp", f.body());
    require(
        number(m, "retcode") == (success ? 0 : Retcode.REQ_PARA_INVALID.getVal()),
        "Actual/stale cast outcome changed");
    require(
        !m.hasField(m.getDescriptorForType().findFieldByName("battle_info")),
        "Prop cast created a battle");
    return m;
  }

  private void destroy(int instance, int entity) throws Exception {
    int before = progressNotifications, del = removalNotifications;
    current++;
    expectedEntity = entity;
    awaitDestruction = true;
    cast(entity, true);
    awaitDestruction = false;
    require(
        progressNotifications == before + 1 && removalNotifications == del + 1,
        "Progress/removal not published before cast success");
    actualDestructions++;
    operations.add(Map.of("instance", instance, "entity", entity, "count", current));
    cast(entity, false);
    staleRequests++;
    require(
        progressNotifications == before + 1 && removalNotifications == del + 1,
        "Stale cast added another notification");
  }

  private DynamicMessage start() throws Exception {
    var m =
        call(
            "StartRogueCsReq",
            Map.of(
                "area_id",
                100,
                "NOEPBNMJFGL",
                List.of(emu.lunarcore.GameConstants.TRAILBLAZER_AVATAR_ID)));
    explorations++;
    return (DynamicMessage) field(m, "scene");
  }

  private Map<String, Object> execute(JsonObject credentials, java.io.BufferedReader unused)
      throws Exception {
    target = credentials.get("target").getAsInt();
    require(target == 20 || target == 400, "Unsupported prop goal");
    String mode = credentials.get("mode").getAsString();
    require(Set.of("checkpoint", "complete", "resume").contains(mode), "Unknown checkpoint mode");
    var token =
        call(
            "PlayerGetTokenCsReq",
            Map.of(
                "account_uid",
                credentials.get("accountUid").getAsString(),
                "token",
                credentials.get("comboToken").getAsString(),
                "platform",
                1));
    int uid = number(token, "uid");
    var login = call("PlayerLoginCsReq");
    observedJades = number((DynamicMessage) field(login, "basic_info"), "hcoin");
    require(
        observedJades == (mode.equals("resume") ? (target == 20 ? 5 : 15) : 0),
        "Fresh/restored wallet differs");
    call("PlayerLoginFinishCsReq");
    if (mode.equals("checkpoint")) {
      quests(0);
      var boxes = scene(start(), Set.of(300007, 300008));
      destroy(300007, boxes.get(300007));
      scene((DynamicMessage) field(call("GetCurSceneInfoCsReq"), "scene"), Set.of(300008));
      quests(1);
      call("LeaveRogueCsReq");
      checks.add("one-actual-destruction-saved-with-the-other-original-prop-still-present");
    } else {
      var ids = target == 20 ? List.of(4092312) : List.of(4092312, 4092313);
      if (mode.equals("resume")) {
        current = target;
        claimed = Set.copyOf(ids);
        quests(target);
      } else {
        current = 1;
        quests(1);
        var boxes =
            scene(
                (DynamicMessage) field(call("EnterRogueCsReq", Map.of("area_id", 100)), "scene"),
                Set.of(300008));
        checks.add("normal-h2-restart-restores-consumed-and-remaining-original-props");
        destroy(300008, boxes.get(300008));
        scene((DynamicMessage) field(call("GetCurSceneInfoCsReq"), "scene"), Set.of());
        call("LeaveRogueCsReq");
        call("QuitRogueCsReq", Map.of("area_id", 100));
        while (current < target) {
          boxes = scene(start(), Set.of(300007, 300008));
          for (int id : List.of(300007, 300008)) destroy(id, boxes.get(id));
          scene((DynamicMessage) field(call("GetCurSceneInfoCsReq"), "scene"), Set.of());
          call("LeaveRogueCsReq");
          call("QuitRogueCsReq", Map.of("area_id", 100));
        }
        quests(target);
        awaitClaim = true;
        var reward = call("TakeQuestRewardCsReq", Map.of("ODJIJFJCHHB", ids));
        awaitClaim = false;
        require(
            field(reward, "succ_quest_id_list").equals(ids), "Actual source reward IDs changed");
        int sum = 0;
        for (var raw : (List<?>) field((DynamicMessage) field(reward, "reward"), "item_list")) {
          var item = (DynamicMessage) raw;
          require(number(item, "item_id") == 1, "Wrong source reward item");
          sum += number(item, "num");
        }
        require(
            sum == (target == 20 ? 5 : 15)
                && observedJades == sum
                && jadeNotifications == ids.size(),
            "Original actual jades not notified before success");
        claimed = Set.copyOf(ids);
      }
      var duplicate =
          DynamicMessage.newBuilder(protocol.clientSchema().message("TakeQuestRewardCsReq"));
      duplicate.setField(duplicate.getDescriptorForType().findFieldByName("ODJIJFJCHHB"), ids);
      var repeated =
          protocol
              .clientSchema()
              .parse(
                  "TakeQuestRewardScRsp",
                  raw(
                          protocol.clientCommand("TakeQuestRewardCsReq"),
                          duplicate.build().toByteArray(),
                          protocol.clientCommand("TakeQuestRewardScRsp"))
                      .body());
      require(
          number(repeated, "retcode") != 0
              && ((List<?>) field(repeated, "succ_quest_id_list")).isEmpty()
              && ((List<?>) field((DynamicMessage) field(repeated, "reward"), "item_list"))
                  .isEmpty(),
          "Repeated prop goal paid twice");
      quests(target);
      checks.add("actual-count-original-goals-wallet-and-repeat-claims-verified");
    }
    var result = new LinkedHashMap<String, Object>();
    result.put("uid", uid);
    result.put("passed", true);
    result.put("target", target);
    result.put("mode", mode);
    result.put("count", current);
    result.put("actualDestructions", actualDestructions);
    result.put("actualExplorations", explorations);
    result.put("questNotifications", progressNotifications);
    result.put("removalNotifications", removalNotifications);
    result.put("staleRequestsRejected", staleRequests);
    result.put("jadeNotifications", jadeNotifications);
    result.put("questQueries", queries);
    result.put("jades", observedJades);
    result.put("originalQuestProgress", questProgress);
    result.put("originalQuestPhases", questPhases);
    result.put("actualOperations", operations);
    result.put("checks", checks);
    result.put("clientGameplayVerified", false);
    return result;
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
        throw new IllegalStateException("Native enhancement connection failed", failure.get());
      var f = inbox.poll(1, TimeUnit.SECONDS);
      if (f == null) continue;
      observe(f);
      if (f.command() == response) return f;
    }
    throw new TimeoutException("Response " + response);
  }

  private DynamicMessage call(String name, Map<String, Object> fields) throws Exception {
    var b = DynamicMessage.newBuilder(protocol.clientSchema().message(name));
    fields.forEach(
        (key, value) -> b.setField(b.getDescriptorForType().findFieldByName(key), value));
    String response = name.replace("CsReq", "ScRsp");
    var frame =
        raw(
            protocol.clientCommand(name),
            b.build().toByteArray(),
            protocol.clientCommand(response));
    var m = protocol.clientSchema().parse(response, frame.body());
    require(m.getUnknownFields().asMap().isEmpty(), "Unknown enhancement response fields");
    if (!name.equals("GetRogueBuffEnhanceInfoCsReq"))
      require(
          number(m, "retcode") == 0, "Unexpected " + response + " status=" + number(m, "retcode"));
    return m;
  }

  private DynamicMessage call(String name) throws Exception {
    return call(name, Map.of());
  }

  private void quests(int count) throws Exception {
    validateQuests(call("GetQuestDataCsReq"), count, claimed);
    queries++;
  }

  public static void main(String[] args) {
    var probe = new NativePropQuestProbe();
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
                  require(frame != null, "Truncated prop frame");
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
                  null, new IllegalStateException("Prop probe disconnected"));
            }
          });
      connection =
          client.connect(new InetSocketAddress("127.0.0.1", Integer.parseInt(args[0])), config);
      probe.channel = probe.connected.get(10, TimeUnit.SECONDS);
      System.out.write(
          (new Gson().toJson(probe.execute(credentials, control)) + "\n")
              .getBytes(StandardCharsets.UTF_8));
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
