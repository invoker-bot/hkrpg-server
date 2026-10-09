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

/** Constructed accepted battle status, then actual dead/living casts; no official client replay. */
public final class NativeLivingCasterProbe {
  private record Frame(int command, byte[] body) {}

  private final ProtocolBridge protocol = ProtocolBridge.forVersion("4.6.0");
  private final BlockingQueue<Frame> inbox = new LinkedBlockingQueue<>();
  private final AtomicReference<Throwable> failure = new AtomicReference<>();
  private final CompletableFuture<Ukcp> connected = new CompletableFuture<>();
  private Ukcp channel;
  private int requests, mpNotifications;
  private final List<String> checks = new ArrayList<>();

  private static Object field(DynamicMessage m, String n) {
    return m.getField(Objects.requireNonNull(m.getDescriptorForType().findFieldByName(n), n));
  }

  private static int number(DynamicMessage m, String n) {
    return ((Number) field(m, n)).intValue();
  }

  private static void require(boolean ok, String why) {
    if (!ok) throw new IllegalStateException(why);
  }

  private void check(boolean ok, String why) {
    require(ok, why);
    checks.add(why);
  }

  private void observe(Frame f) {
    if (f.command() == protocol.clientCommand("SceneCastSkillMpUpdateScNotify")) mpNotifications++;
  }

  private DynamicMessage message(String name, Map<String, Object> values) {
    var b = DynamicMessage.newBuilder(protocol.clientSchema().message(name));
    values.forEach((k, v) -> b.setField(b.getDescriptorForType().findFieldByName(k), v));
    return b.build();
  }

  private DynamicMessage lineup() throws Exception {
    var m = call("GetCurLineupDataCsReq");
    require(number(m, "retcode") == 0, "Lineup query failed");
    return (DynamicMessage) field(m, "lineup");
  }

  private int hp(DynamicMessage lineup) {
    DynamicMessage found = null;
    for (var r : (List<?>) field(lineup, "avatar_list"))
      if (number((DynamicMessage) r, "id") == 8001) {
        require(found == null, "Duplicate formal actor");
        found = (DynamicMessage) r;
      }
    require(found != null, "Original formal actor missing");
    return number(found, "hp");
  }

  private int caster() throws Exception {
    var m = call("GetCurSceneInfoCsReq");
    require(number(m, "retcode") == 0, "Scene query failed");
    var scene = (DynamicMessage) field(m, "scene");
    int result = 0;
    for (var g : (List<?>) field(scene, "entity_group_list"))
      for (var r : (List<?>) field((DynamicMessage) g, "entity_list")) {
        var e = (DynamicMessage) r;
        if (e.hasField(e.getDescriptorForType().findFieldByName("actor"))
            && number((DynamicMessage) field(e, "actor"), "base_avatar_id") == 8001) {
          require(result == 0, "Duplicate formal scene actor");
          result = number(e, "entity_id");
        }
      }
    require(result > 0, "Missing actual formal scene actor");
    return result;
  }

  private void settle(JsonObject input, int hp) throws Exception {
    var entry = call("SceneEnterStageCsReq", Map.of("event_id", 305001));
    require(number(entry, "retcode") == 0, "Original event entry failed");
    var battle = (DynamicMessage) field(entry, "battle_info");
    int id = number(battle, "battle_id"), stage = number(battle, "stage_id");
    require(stage == input.get("stage").getAsInt(), "Original event stage differs");
    var actual = new ArrayList<List<Integer>>();
    for (var raw : (List<?>) field(battle, "monster_wave_list")) {
      var wave = new ArrayList<Integer>();
      for (var r : (List<?>) field((DynamicMessage) raw, "monster_list"))
        wave.add(number((DynamicMessage) r, "monster_id"));
      actual.add(wave);
    }
    var expected = new ArrayList<List<Integer>>();
    for (var raw : input.getAsJsonArray("waves")) {
      var wave = new ArrayList<Integer>();
      for (var r : raw.getAsJsonArray()) wave.add(r.getAsInt());
      expected.add(wave);
    }
    check(actual.equals(expected), "original-plane-event-and-complete-stage-waves");
    var property =
        message(
            "AvatarProperty",
            Map.of("max_hp", 100d, "left_hp", (double) hp / 100, "max_sp", 120d, "left_sp", 120d));
    var actor = message("AvatarBattleInfo", Map.of("id", 8001, "avatar_status", property));
    var stats = message("BattleStatistics", Map.of("battle_avatar_list", List.of(actor)));
    var end =
        protocol
            .clientSchema()
            .message("PVEBattleResultCsReq")
            .findFieldByName("end_status")
            .getEnumType()
            .findValueByName("BATTLE_END_WIN");
    var values =
        new HashMap<String, Object>(
            Map.of("battle_id", id + 1, "stage_id", stage, "end_status", end, "stt", stats));
    check(
        number(call("PVEBattleResultCsReq", values), "retcode") != 0,
        "wrong-battle-status-report-rejected");
    values.put("battle_id", id);
    check(
        number(call("PVEBattleResultCsReq", values), "retcode") == 0,
        "constructed-status-accepted-" + hp);
    check(
        number(call("PVEBattleResultCsReq", values), "retcode") != 0,
        "repeated-status-report-rejected");
    check(hp(lineup()) == hp, "actual-avatar-status-after-settlement-" + hp);
  }

  private void casts(boolean living, boolean technique) throws Exception {
    int caster = caster();
    var before = lineup();
    int mp = number(before, "mp"), notifications = mpNotifications;
    for (int index = 0; index <= (technique ? 1 : 0); index++) {
      var reply =
          call(
              "SceneCastSkillCsReq",
              Map.of(
                  "cast_entity_id", caster, "attacked_by_entity_id", caster, "skill_index", index));
      check(
          (number(reply, "retcode") == 0) == living
              && !reply.hasField(reply.getDescriptorForType().findFieldByName("battle_info")),
          "actual-" + (living ? "living" : "dead") + "-cast-" + index);
    }
    var after = lineup();
    check(hp(after) == (living ? 10000 : 0), "cast-keeps-actual-life");
    check(
        number(after, "mp") == mp - (living && technique ? 1 : 0),
        "cast-points-before-side-effects");
    check(
        mpNotifications == notifications + (living && technique ? 1 : 0),
        "native-point-notification-count");
  }

  private Map<String, Object> execute(JsonObject input) throws Exception {
    String mode = input.get("mode").getAsString();
    require(Set.of("dead", "restore", "living").contains(mode), "Invalid mode");
    var token =
        call(
            "PlayerGetTokenCsReq",
            Map.of(
                "account_uid",
                input.get("accountUid").getAsString(),
                "token",
                input.get("comboToken").getAsString(),
                "platform",
                1));
    require(number(token, "retcode") == 0, "Token rejected");
    int uid = number(token, "uid");
    require(number(call("PlayerLoginCsReq"), "retcode") == 0, "Login rejected");
    require(number(call("PlayerLoginFinishCsReq"), "retcode") == 0, "Login finish rejected");
    if (mode.equals("dead")) {
      settle(input, 0);
      casts(false, true);
    } else if (mode.equals("restore")) {
      check(hp(lineup()) == 2000, "existing-login-leader-revival-policy");
      settle(input, 0);
      casts(false, true);
      settle(input, 10000);
      casts(true, true);
    } else {
      check(hp(lineup()) == 10000, "actual-living-avatar-restored-after-normal-stop");
      casts(true, false);
    }
    var result = new LinkedHashMap<String, Object>();
    result.put("passed", true);
    result.put("uid", uid);
    result.put("mode", mode);
    result.put("hp", hp(lineup()));
    result.put("mp", number(lineup(), "mp"));
    result.put("requests", requests);
    result.put("checks", checks);
    result.put("constructedResultReports", true);
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
        throw new IllegalStateException("Native living caster connection failed", failure.get());
      var f = inbox.poll(1, TimeUnit.SECONDS);
      if (f == null) continue;
      observe(f);
      if (f.command() == response) return f;
    }
    throw new TimeoutException("Response " + response);
  }

  private DynamicMessage call(String name, Map<String, Object> fields) throws Exception {
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
    require(m.getUnknownFields().asMap().isEmpty(), "Unknown living caster response fields");
    return m;
  }

  private DynamicMessage call(String name) throws Exception {
    return call(name, Map.of());
  }

  public static void main(String[] args) {
    var probe = new NativeLivingCasterProbe();
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
                  require(frame != null, "Truncated living caster frame");
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
                  null, new IllegalStateException("Living caster probe disconnected"));
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
