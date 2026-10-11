package org.hkrpg.server.protocol;

import com.google.gson.*;
import com.google.protobuf.*;
import emu.lunarcore.server.packet.PacketCodec;
import emu.lunarcore.server.packet.Retcode;
import io.netty.buffer.*;
import java.io.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import kcp.highway.*;

/** Native 915 against a loaded original NPC; actual riding controls and rendering are excluded. */
public final class NativeCavalryActivityProbe {
  private static final int QUEST = 6029860;
  private record Frame(int command, byte[] body) {}
  private final ProtocolBridge protocol = ProtocolBridge.forVersion("4.6.0");
  private final BlockingQueue<Frame> inbox = new LinkedBlockingQueue<>();
  private final AtomicReference<Throwable> failure = new AtomicReference<>();
  private final CompletableFuture<Ukcp> connected = new CompletableFuture<>();
  private final List<String> checks = new ArrayList<>();
  private final List<Map<String, Object>> reports = new ArrayList<>();
  private final List<Map<String, Object>> questObservations = new ArrayList<>();
  private final Map<String, Map<String, Integer>> schemas = new TreeMap<>();
  private Ukcp channel;
  private int requests;

  private static Object field(DynamicMessage value, String name) {
    return value.getField(Objects.requireNonNull(value.getDescriptorForType().findFieldByName(name), name));
  }

  private static int number(DynamicMessage value, String name) {
    var found = field(value, name);
    return found instanceof Descriptors.EnumValueDescriptor enumeration ? enumeration.getNumber() : (Integer) found;
  }

  private static void require(boolean valid, String why) {
    if (!valid) throw new IllegalStateException(why);
  }

  private void check(boolean valid, String why) {
    require(valid, why);
    checks.add(why);
  }

  private DynamicMessage.Builder builder(String name) {
    return DynamicMessage.newBuilder(Objects.requireNonNull(protocol.clientSchema().message(name), name));
  }

  private void schema(String name, String... fields) {
    var descriptor = builder(name).getDescriptorForType();
    var matched = new TreeMap<String, Integer>();
    for (String field : fields)
      matched.put(field, Objects.requireNonNull(descriptor.findFieldByName(field), name + "." + field).getNumber());
    schemas.put(name, matched);
  }

  private void preflight() {
    schema("PlayerGetTokenCsReq", "account_uid", "token", "platform");
    schema("PlayerLoginCsReq");
    schema("PlayerLoginFinishCsReq");
    schema("GetAvatarDataCsReq");
    schema("GetCurSceneInfoCsReq");
    schema("GetQuestDataCsReq");
    schema("FinishQuestCsReq", "quest_id", "prop_id", "group_id");
    schema("FinishQuestScRsp", "PLDKENJLKME");
    schema("TakeQuestRewardCsReq", "ODJIJFJCHHB");
    schema("TakeQuestRewardScRsp", "retcode", "reward", "succ_quest_id_list");
    schema("SceneInfo", "plane_id", "floor_id", "entry_id", "world_id", "interact_id", "entity_group_list");
    schema("SceneEntityGroupInfo", "group_id", "entity_list");
    schema("SceneEntityInfo", "group_id", "inst_id", "entity_id", "npc");
    schema("SceneNpcInfo", "npc_id");
    schema("Quest", "id", "progress", "status", "finish_time");
    var status = builder("Quest").getDescriptorForType().findFieldByName("status").getEnumType();
    var phases = new TreeMap<String, Integer>();
    for (String name : List.of("QuestStatus_QuestDoing", "QuestStatus_QuestFinish", "QuestStatus_QuestClose"))
      phases.put(name, Objects.requireNonNull(status.findValueByName(name), name).getNumber());
    schemas.put("QuestStatus", phases);
    check(phases.equals(Map.of("QuestStatus_QuestDoing", 1, "QuestStatus_QuestFinish", 2, "QuestStatus_QuestClose", 3)),
        "native-quest-status-source-names-and-values");
    schema("ItemList", "item_list");
    schema("Item", "item_id", "num");
    check(protocol.clientCommand("FinishQuestCsReq") == 915
        && schemas.get("FinishQuestCsReq").equals(Map.of("quest_id", 7, "prop_id", 8, "group_id", 11)),
        "native-915-source-fields-7-8-11");
  }

  private DynamicMessage call(String name, byte[] body) throws Exception {
    var bytes = Unpooled.wrappedBuffer(PacketCodec.encode(protocol.clientCommand(name), new byte[0], body));
    try { channel.write(bytes); requests++; } finally { bytes.release(); }
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(25);
    while (System.nanoTime() < deadline) {
      if (failure.get() != null) throw new IllegalStateException("Native cavalry connection failed", failure.get());
      var frame = inbox.poll(1, TimeUnit.SECONDS);
      if (frame == null || frame.command() != protocol.clientCommand(name.replace("CsReq", "ScRsp"))) continue;
      var reply = protocol.clientSchema().parse(name.replace("CsReq", "ScRsp"), frame.body());
      require(reply.getUnknownFields().asMap().isEmpty(), "Unknown native reply fields for " + name);
      if (!name.equals("FinishQuestCsReq") && !name.equals("TakeQuestRewardCsReq"))
        require(number(reply, "retcode") == 0, "Rejected native request " + name + ": " + reply);
      return reply;
    }
    throw new TimeoutException(name);
  }

  private DynamicMessage call(String name, Map<String, Object> fields) throws Exception {
    var request = builder(name);
    fields.forEach((key, value) -> request.setField(request.getDescriptorForType().findFieldByName(key), value));
    return call(name, request.build().toByteArray());
  }

  private DynamicMessage call(String name) throws Exception { return call(name, Map.of()); }

  private DynamicMessage quest(int progress, String status) throws Exception {
    var rows = ((List<?>) field(call("GetQuestDataCsReq"), "quest_list")).stream()
        .map(row -> (DynamicMessage) row).filter(row -> number(row, "id") == QUEST).toList();
    check(rows.size() == 1, "original-ride-goal-published-once-" + status);
    var state = rows.getFirst();
    var observed = new LinkedHashMap<String, Object>();
    observed.put("id", number(state, "id"));
    observed.put("progress", number(state, "progress"));
    observed.put("status", ((Descriptors.EnumValueDescriptor) field(state, "status")).getName());
    observed.put("statusValue", number(state, "status"));
    observed.put("finishTime", field(state, "finish_time"));
    observed.put("messageHex", HexFormat.of().formatHex(state.toByteArray()));
    questObservations.add(observed);
    System.out.println("RIDE_QUEST=" + new Gson().toJson(observed));
    System.out.flush();
    check(number(state, "progress") == progress
        && ((Descriptors.EnumValueDescriptor) field(state, "status")).getName().equals(status)
        && ((Long) field(state, "finish_time") > 0) == (progress == 1),
        "actual-quest-phase-and-time-" + status);
    return state;
  }

  private DynamicMessage scene(JsonObject control) throws Exception {
    var result = (DynamicMessage) field(call("GetCurSceneInfoCsReq"), "scene");
    check(number(result, "plane_id") == control.get("plane").getAsInt()
        && number(result, "floor_id") == control.get("floor").getAsInt()
        && number(result, "entry_id") == control.get("entry").getAsInt()
        && number(result, "world_id") == 501 && (Long) field(result, "interact_id") > 0,
        "actual-original-town-world-entry-and-runtime");
    return result;
  }

  private DynamicMessage actor(DynamicMessage scene, int group, int instance, int npc) {
    var found = new ArrayList<DynamicMessage>();
    for (var rawGroup : (List<?>) field(scene, "entity_group_list"))
      for (var raw : (List<?>) field((DynamicMessage) rawGroup, "entity_list")) {
        var entity = (DynamicMessage) raw;
        if (number(entity, "group_id") == group && number(entity, "inst_id") == instance) found.add(entity);
      }
    check(found.size() == 1, "unique-current-original-npc-" + group + "-" + instance);
    var entity = found.getFirst();
    check(entity.hasField(entity.getDescriptorForType().findFieldByName("npc"))
        && number((DynamicMessage) field(entity, "npc"), "npc_id") == npc && number(entity, "entity_id") > 0,
        "actual-scene-npc-identity-" + npc);
    return entity;
  }

  private static byte[] wire(long... fieldsAndValues) throws IOException {
    var bytes = new ByteArrayOutputStream();
    var stream = CodedOutputStream.newInstance(bytes);
    for (int i = 0; i < fieldsAndValues.length; i += 2)
      stream.writeUInt64((int) fieldsAndValues[i], fieldsAndValues[i + 1]);
    stream.flush();
    return bytes.toByteArray();
  }

  private void finish(String name, byte[] request, Integer expected) throws Exception {
    var reply = call("FinishQuestCsReq", request);
    int code = number(reply, "PLDKENJLKME");
    var report = new LinkedHashMap<String, Object>();
    report.put("name", name);
    report.put("requestHex", HexFormat.of().formatHex(request));
    report.put("retcode", code);
    reports.add(report);
    System.out.println("RIDE_REPORT=" + new Gson().toJson(report));
    System.out.flush();
    check(expected == null ? code != 0 : code == expected, "actual-915-response-" + name);
  }

  private DynamicMessage claim() throws Exception {
    var request = builder("TakeQuestRewardCsReq");
    request.addRepeatedField(request.getDescriptorForType().findFieldByName("ODJIJFJCHHB"), QUEST);
    return call("TakeQuestRewardCsReq", request.build().toByteArray());
  }

  private void reward(DynamicMessage reply) {
    var amounts = new TreeMap<Integer, Integer>();
    for (var raw : (List<?>) field((DynamicMessage) field(reply, "reward"), "item_list")) {
      var item = (DynamicMessage) raw;
      require(amounts.put(number(item, "item_id"), number(item, "num")) == null, "Duplicate reward item");
    }
    check(number(reply, "retcode") == 0 && field(reply, "succ_quest_id_list").equals(List.of(QUEST))
        && amounts.equals(Map.of(1, 20, 213, 4)), "actual-original-20-hcoin-and-4-item213-reward");
  }

  private Map<String, Object> execute(JsonObject control) throws Exception {
    preflight();
    int uid = number(call("PlayerGetTokenCsReq", Map.of("account_uid", control.get("accountUid").getAsString(),
        "token", control.get("comboToken").getAsString(), "platform", 1)), "uid");
    call("PlayerLoginCsReq");
    call("PlayerLoginFinishCsReq");
    System.out.println("READY_UID=" + uid);
    System.out.flush();
    String mode = control.get("mode").getAsString();
    if (mode.equals("bootstrap")) {
      boolean avatar = false;
      for (int attempt = 0; attempt < 120 && !avatar; attempt++) {
        avatar = ((List<?>) field(call("GetAvatarDataCsReq"), "avatar_list")).stream()
            .map(row -> (DynamicMessage) row).anyMatch(row -> number(row, "base_avatar_id") == 1001);
        if (!avatar) Thread.sleep(200);
      }
      check(avatar, "isolated-fixture-avatar-owned");
      check(((List<?>) field(call("GetQuestDataCsReq"), "quest_list")).stream()
          .map(row -> (DynamicMessage) row).noneMatch(row -> number(row, "id") == QUEST),
          "target-goal-not-accepted-before-source-prerequisites");
    } else {
      var scene = scene(control);
      var original = actor(scene, 176, 400001, 3185);
      actor(scene, 176, 400002, 3183);
      if (mode.equals("report")) {
        quest(0, "QuestStatus_QuestDoing");
        var invalid = new LinkedHashMap<String, byte[]>();
        invalid.put("missing-group", wire(7, QUEST, 8, 400001));
        invalid.put("missing-instance", wire(7, QUEST, 11, 176));
        invalid.put("duplicate-quest", wire(7, QUEST, 7, QUEST, 8, 400001, 11, 176));
        invalid.put("redirect-quest", wire(7, QUEST, 7, 19999999, 8, 400001, 11, 176));
        invalid.put("duplicate-instance", wire(7, QUEST, 8, 400001, 8, 400001, 11, 176));
        invalid.put("unknown-field", wire(7, QUEST, 8, 400001, 11, 176, 15, 1));
        invalid.put("overflow-instance", wire(7, QUEST, 8, 0x1_0000_0001L, 11, 176));
        for (var rejected : invalid.entrySet()) {
          finish(rejected.getKey(), rejected.getValue(), Retcode.REQ_PARA_INVALID.getVal());
          quest(0, "QuestStatus_QuestDoing");
        }
        for (var rejected : Map.of("untagged-original-npc", wire(7, QUEST, 8, 400002, 11, 176),
            "wrong-group", wire(7, QUEST, 8, 400001, 11, 19999999),
            "wrong-instance", wire(7, QUEST, 8, 19999999, 11, 176)).entrySet()) {
          finish(rejected.getKey(), rejected.getValue(), null);
          quest(0, "QuestStatus_QuestDoing");
        }
        finish("original-loaded-ride", wire(7, QUEST, 8, 400001, 11, 176), 0);
        quest(1, "QuestStatus_QuestFinish");
        check(number(original, "entity_id") == number(actor(scene(control), 176, 400001, 3185), "entity_id"),
            "ride-report-preserves-current-original-npc");
      } else require(mode.equals("resume"), "Unknown probe mode");
      quest(1, mode.equals("report") ? "QuestStatus_QuestFinish" : "QuestStatus_QuestClose");
      finish("duplicate-original-report-" + mode, wire(7, QUEST, 8, 400001, 11, 176), 0);
      quest(1, mode.equals("report") ? "QuestStatus_QuestFinish" : "QuestStatus_QuestClose");
      if (mode.equals("report")) reward(claim());
      quest(1, "QuestStatus_QuestClose");
      check(number(claim(), "retcode") == Retcode.QUEST_REWARD_ALREADY_TAKEN.getVal(),
          "actual-repeated-claim-blocked-" + mode);
    }
    var result = new LinkedHashMap<String, Object>(Map.of("passed", true, "uid", uid, "mode", mode, "requests", requests,
        "checks", checks, "reports", reports, "requestSchemas", schemas,
        "physicalRideVerified", false, "clientGameplayVerified", false));
    result.put("questObservations", questObservations);
    return result;
  }

  public static void main(String[] args) {
    var probe = new NativeCavalryActivityProbe();
    if (args.length == 1 && args[0].equals("--schema")) {
      probe.preflight();
      System.out.println(new Gson().toJson(Map.of("passed", true, "schemas", probe.schemas)));
      return;
    }
    var client = new KcpClient();
    Ukcp connection = null;
    int exit = 1;
    try {
      var input = JsonParser.parseReader(new InputStreamReader(System.in, StandardCharsets.UTF_8)).getAsJsonObject();
      var config = new ChannelConfig();
      config.nodelay(true, 40, 2, true);
      config.setMtu(1400);
      config.setSndwnd(256);
      config.setRcvwnd(256);
      config.setUseConvChannel(true);
      config.setConv(ThreadLocalRandom.current().nextInt(1, Integer.MAX_VALUE));
      config.setTimeoutMillis(25000);
      config.setAckNoDelay(true);
      client.init(config, new KcpListener() {
        public void onConnected(Ukcp value) { probe.connected.complete(value); }
        public void handleReceive(ByteBuf bytes, Ukcp value) {
          try {
            while (bytes.isReadable()) {
              var frame = PacketCodec.decodeNext(bytes);
              require(frame != null, "Truncated native frame");
              probe.inbox.add(new Frame(frame.cmdId(), frame.body()));
            }
          } catch (Throwable invalid) { probe.failure.set(invalid); }
        }
        public void handleException(Throwable invalid, Ukcp value) {
          probe.failure.set(invalid);
          probe.connected.completeExceptionally(invalid);
        }
        public void handleClose(Ukcp value) {
          probe.failure.compareAndSet(null, new IllegalStateException("Disconnected"));
        }
      });
      connection = client.connect(new InetSocketAddress("127.0.0.1", Integer.parseInt(args[0])), config);
      probe.channel = probe.connected.get(10, TimeUnit.SECONDS);
      System.out.println(new Gson().toJson(probe.execute(input)));
      System.out.flush();
      exit = 0;
    } catch (Exception invalid) { invalid.printStackTrace(System.err); }
    finally {
      if (connection != null) connection.close();
      client.stop();
    }
    System.exit(exit);
  }
}
