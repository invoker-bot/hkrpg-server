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

/** Project elemental entry criterion through original first-world actors and native requests. */
public final class NativeDailyWeaknessEntryProbe {
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
  private final List<Integer> attackedStages = new ArrayList<>();

  private void check(boolean ok, String why) {
    require(ok, why);
    checks.add(why);
  }

  private void goals(int victories, boolean claimed, int points) throws Exception {
    var data = call("GetQuestDataCsReq");
    var found = new HashMap<Integer, DynamicMessage>();
    for (var raw : (List<?>) field(data, "quest_list")) {
      var row = (DynamicMessage) raw;
      int id = number(row, "id");
      if (Set.of(2100123, 2100142).contains(id))
        require(found.put(id, row) == null, "Duplicate daily goal");
    }
    require(found.size() == 2, "Both original daily goals must be selected");
    for (int id : found.keySet()) {
      var row = found.get(id);
      int goal = id == 2100123 ? 3 : 1;
      int progress = Math.min(victories, goal);
      int status = claimed ? 3 : progress == goal ? 2 : 1;
      require(number(row, "progress") == progress, "Wrong weakness goal " + id);
      require(
          ((Descriptors.EnumValueDescriptor) field(row, "status")).getNumber() == status,
          "Wrong daily goal phase " + id);
      require(
          ((Long) field(row, "finish_time") > 0) == (progress == goal), "Wrong finish timestamp");
    }
    require(
        number(call("GetDailyActiveInfoCsReq"), "daily_active_point") == points,
        "Incorrect daily points");
    check(true, "actual-daily-progress-" + victories + "-claimed-" + claimed + "-points-" + points);
  }

  private void victory(JsonObject input, int previous) throws Exception {
    var scene =
        (DynamicMessage)
            field(
                call("StartRogueCsReq", Map.of("area_id", 110, "NOEPBNMJFGL", List.of(8001))),
                "scene");
    require(
        number(scene, "entry_id") == 8000103 && number(scene, "floor_id") == 80001003,
        "Wrong original first-world room");
    DynamicMessage target = null;
    for (var rawGroup : (List<?>) field(scene, "entity_group_list"))
      for (var raw : (List<?>) field((DynamicMessage) rawGroup, "entity_list")) {
        var entity = (DynamicMessage) raw;
        if (number(entity, "group_id") == 18
            && entity.hasField(entity.getDescriptorForType().findFieldByName("npc_monster"))) {
          require(target == null, "Duplicate original group 18 monster");
          target = entity;
        }
      }
    require(target != null, "Missing actual primary monster");
    var npc = (DynamicMessage) field(target, "npc_monster");
    int stage = number(npc, "event_id");
    var expected = input.getAsJsonObject("primaryStages").getAsJsonObject(Integer.toString(stage));
    require(
        expected != null && expected.get("npc").getAsInt() == number(npc, "monster_id"),
        "Primary is not an original weighted group 18 choice");
    var attack =
        call(
            "SceneCastSkillCsReq",
            Map.of(
                "cast_entity_id",
                field(scene, "leader_entity_id"),
                "attacked_by_entity_id",
                field(scene, "leader_entity_id"),
                "hit_target_entity_id_list",
                List.of(number(target, "entity_id"))));
    var battle = (DynamicMessage) field(attack, "battle_info");
    int id = number(battle, "battle_id");
    require(id > 0 && number(battle, "stage_id") == stage, "Wrong actual attack battle");
    var waves = new ArrayList<List<Integer>>();
    for (var raw : (List<?>) field(battle, "monster_wave_list")) {
      var wave = (DynamicMessage) raw;
      var monsters = new ArrayList<Integer>();
      require(number(wave, "battle_stage_id") == stage, "Different primary wave stage");
      for (var item : (List<?>) field(wave, "monster_list"))
        monsters.add(number((DynamicMessage) item, "monster_id"));
      waves.add(monsters);
    }
    var original = new ArrayList<List<Integer>>();
    for (var raw : expected.getAsJsonArray("waves")) {
      var wave = new ArrayList<Integer>();
      for (var m : raw.getAsJsonArray()) wave.add(m.getAsInt());
      original.add(wave);
    }
    require(waves.equals(original), "Original primary stage waves changed");
    require(
        waves.getFirst().stream()
            .anyMatch(
                m ->
                    input.getAsJsonArray("physicalWeak").asList().stream()
                        .anyMatch(x -> x.getAsInt() == m)),
        "No physical weakness in actual primary first wave");
    var avatars = (List<?>) field(battle, "battle_avatar_list");
    require(
        avatars.size() == 1 && number((DynamicMessage) avatars.getFirst(), "id") == 8001,
        "Different actual formal actor");
    require(
        ((List<?>) field(battle, "buff_list"))
            .stream().anyMatch(raw -> number((DynamicMessage) raw, "id") == 1000111),
        "Actual elemental entry buff missing");
    check(true, "original-primary-waves-physical-actor-and-elemental-entry-buff-" + stage);
    goals(previous, false, 0);
    var win =
        protocol
            .clientSchema()
            .message("PVEBattleResultCsReq")
            .findFieldByName("end_status")
            .getEnumType()
            .findValueByName("BATTLE_END_WIN");
    callCode(
        "PVEBattleResultCsReq",
        Map.of("battle_id", id + 1, "stage_id", stage, "end_status", win),
        1);
    goals(previous, false, 0);
    call("PVEBattleResultCsReq", Map.of("battle_id", id, "stage_id", stage, "end_status", win));
    goals(previous + 1, false, 0);
    callCode(
        "PVEBattleResultCsReq", Map.of("battle_id", id, "stage_id", stage, "end_status", win), 1);
    goals(previous + 1, false, 0);
    call("LeaveRogueCsReq");
    call("QuitRogueCsReq", Map.of("area_id", 110));
    attackedStages.add(stage);
  }

  private void claim(int id, int code) throws Exception {
    var response = callCode("TakeQuestRewardCsReq", Map.of("ODJIJFJCHHB", List.of(id)), code);
    require(
        ((List<?>) field(response, "succ_quest_id_list"))
            .equals(code == 0 ? List.of(id) : List.of()),
        "Wrong claim receipt");
    check(true, "native-daily-claim-" + id + "-retcode-" + code);
  }

  private Map<String, Object> execute(JsonObject input) throws Exception {
    String mode = input.get("mode").getAsString();
    int uid =
        number(
            call(
                "PlayerGetTokenCsReq",
                Map.of(
                    "account_uid",
                    input.get("accountUid").getAsString(),
                    "token",
                    input.get("comboToken").getAsString(),
                    "platform",
                    1)),
            "uid");
    call("PlayerLoginCsReq");
    call("PlayerLoginFinishCsReq");
    switch (mode) {
      case "initial" -> goals(0, false, 0);
      case "win1" -> {
        goals(0, false, 0);
        victory(input, 0);
      }
      case "one" -> goals(1, false, 0);
      case "win2" -> {
        goals(1, false, 0);
        victory(input, 1);
      }
      case "win3" -> {
        goals(2, false, 0);
        victory(input, 2);
      }
      case "ready" -> goals(3, false, 0);
      case "claim" -> {
        goals(3, false, 0);
        claim(2100123, 0);
        require(
            number(call("GetDailyActiveInfoCsReq"), "daily_active_point") == 100,
            "First reward must be 100");
        claim(2100142, 0);
        goals(3, true, 200);
        claim(2100123, 1100);
        claim(2100142, 1100);
        goals(3, true, 200);
      }
      case "claimed" -> {
        goals(3, true, 200);
        claim(2100123, 1100);
        claim(2100142, 1100);
        goals(3, true, 200);
      }
      default -> throw new IllegalArgumentException(mode);
    }
    var result = new LinkedHashMap<String, Object>();
    result.put("uid", uid);
    result.put("passed", true);
    result.put("mode", mode);
    result.put("requests", requests);
    result.put("checks", checks);
    result.put("attackedStages", attackedStages);
    result.put("clientGameplayVerified", false);
    result.put("officialExcellentPredicateVerified", false);
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
        throw new IllegalStateException(
            "Native first world entry connection failed", failure.get());
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
        (key, value) -> b.setField(b.getDescriptorForType().findFieldByName(key), value));
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
    var probe = new NativeDailyWeaknessEntryProbe();
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
                  require(frame != null, "Truncated first world entry frame");
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
                  null, new IllegalStateException("First world entry probe disconnected"));
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
