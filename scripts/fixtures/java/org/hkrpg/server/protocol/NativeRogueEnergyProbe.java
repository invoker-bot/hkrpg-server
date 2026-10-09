package org.hkrpg.server.protocol;

import com.google.gson.*;
import com.google.protobuf.*;
import emu.lunarcore.server.packet.PacketCodec;
import io.netty.buffer.*;
import java.io.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import kcp.highway.*;

/** Own KCP battle reports against original encounters; the initial resonance is a declared seed. */
public final class NativeRogueEnergyProbe {
  private record Frame(int command, byte[] body) {}

  private final ProtocolBridge protocol = ProtocolBridge.forVersion("4.6.0");
  private final BlockingQueue<Frame> inbox = new LinkedBlockingQueue<>();
  private final AtomicReference<Throwable> failure = new AtomicReference<>();
  private final CompletableFuture<Ukcp> connected = new CompletableFuture<>();
  private final List<String> checks = new ArrayList<>();
  private Ukcp channel;
  private int requests;
  private Map<String, Object> actualEncounter = Map.of();

  private static Object field(DynamicMessage m, String name) {
    return m.getField(Objects.requireNonNull(m.getDescriptorForType().findFieldByName(name), name));
  }

  private static int number(DynamicMessage m, String name) {
    return ((Number) field(m, name)).intValue();
  }

  private static void require(boolean value, String message) {
    if (!value) throw new IllegalStateException(message);
  }

  private void check(boolean value, String message) {
    require(value, message);
    checks.add(message);
  }

  private DynamicMessage raw(String name, byte[] payload) throws Exception {
    requests++;
    var bytes =
        Unpooled.wrappedBuffer(
            PacketCodec.encode(protocol.clientCommand(name), new byte[0], payload));
    try {
      channel.write(bytes);
    } finally {
      bytes.release();
    }
    String response = name.replace("CsReq", "ScRsp");
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
    while (System.nanoTime() < deadline) {
      if (failure.get() != null)
        throw new IllegalStateException("Energy connection failed", failure.get());
      var frame = inbox.poll(1, TimeUnit.SECONDS);
      if (frame == null || frame.command() != protocol.clientCommand(response)) continue;
      var result = protocol.clientSchema().parse(response, frame.body());
      require(result.getUnknownFields().asMap().isEmpty(), "Unknown response fields: " + response);
      return result;
    }
    throw new TimeoutException(response);
  }

  private DynamicMessage call(String name, Map<String, Object> values) throws Exception {
    var builder = DynamicMessage.newBuilder(protocol.clientSchema().message(name));
    values.forEach(
        (key, value) ->
            builder.setField(builder.getDescriptorForType().findFieldByName(key), value));
    return raw(name, builder.build().toByteArray());
  }

  private DynamicMessage call(String name) throws Exception {
    return call(name, Map.of());
  }

  private void success(DynamicMessage m, String label) {
    check(number(m, "retcode") == 0, label);
  }

  private List<DynamicMessage> actors(DynamicMessage scene) {
    require(
        number(scene, "entry_id") == 8000101
            && number(scene, "plane_id") == 80001
            && number(scene, "floor_id") == 80001001,
        "Unexpected original combat room");
    var result = new ArrayList<DynamicMessage>();
    for (var g : (List<?>) field(scene, "entity_group_list"))
      for (var value : (List<?>) field((DynamicMessage) g, "entity_list")) {
        var e = (DynamicMessage) value;
        if (e.hasField(e.getDescriptorForType().findFieldByName("npc_monster"))) result.add(e);
      }
    result.sort(
        Comparator.comparingInt((DynamicMessage m) -> number(m, "group_id"))
            .thenComparingInt(m -> number(m, "inst_id")));
    require(!result.isEmpty(), "No original monsters remain");
    return result;
  }

  private void energy(DynamicMessage battle, int expected) {
    var events = (List<?>) field(battle, "battle_event");
    check(events.size() == 1, "one-original-classic-battle-event");
    var event = (DynamicMessage) events.getFirst();
    var bar = (DynamicMessage) field((DynamicMessage) field(event, "status"), "sp_bar");
    check(
        number(event, "battle_event_id") == 41007
            && number(bar, "cur_sp") == expected
            && number(bar, "max_sp") == 10000,
        "actual-battle-reserved-energy-" + expected);
  }

  private DynamicMessage encounter(DynamicMessage scene, int expected) throws Exception {
    var actor = actors(scene).getFirst();
    var npc = (DynamicMessage) field(actor, "npc_monster");
    int leader = number(scene, "leader_entity_id");
    var result =
        call(
            "SceneCastSkillCsReq",
            Map.of(
                "cast_entity_id",
                leader,
                "attacked_by_entity_id",
                leader,
                "skill_index",
                0,
                "hit_target_entity_id_list",
                List.of(number(actor, "entity_id"))));
    success(result, "original-monster-cast-started-battle");
    var battle = (DynamicMessage) field(result, "battle_info");
    require(
        number(battle, "battle_id") > 0 && number(battle, "stage_id") == number(npc, "event_id"),
        "Source encounter stage was substituted");
    var waves = (List<?>) field(battle, "monster_wave_list");
    check(
        !waves.isEmpty()
            && waves.stream()
                .allMatch(w -> !((List<?>) field((DynamicMessage) w, "monster_list")).isEmpty()),
        "original-stage-and-nonempty-monster-roster");
    var roster = new ArrayList<List<Integer>>();
    var levels = new ArrayList<Integer>();
    for (var value : waves) {
      var wave = (DynamicMessage) value;
      roster.add(
          ((List<?>) field(wave, "monster_list"))
              .stream().map(m -> number((DynamicMessage) m, "monster_id")).toList());
      levels.add(number((DynamicMessage) field(wave, "monster_param"), "level"));
    }
    actualEncounter =
        Map.of(
            "stage",
            number(battle, "stage_id"),
            "waves",
            roster,
            "waveLevels",
            levels,
            "initialEnergy",
            expected,
            "group",
            number(actor, "group_id"),
            "instance",
            number(actor, "inst_id"),
            "npc",
            number(npc, "monster_id"));
    energy(battle, expected);
    return battle;
  }

  private static UnknownFieldSet.Field scalar(long value) {
    return UnknownFieldSet.Field.newBuilder().addVarint(value).build();
  }

  private static UnknownFieldSet.Field nested(byte[] value) {
    return UnknownFieldSet.Field.newBuilder()
        .addLengthDelimited(ByteString.copyFrom(value))
        .build();
  }

  private byte[] statistics(String variant, int current, int maximum) {
    if (variant.equals("absent")) return new byte[0];
    var bar = UnknownFieldSet.newBuilder().addField(1, scalar(current));
    if (variant.equals("duplicate-current"))
      bar.addField(
          1, UnknownFieldSet.Field.newBuilder().addVarint(current).addVarint(current + 1).build());
    bar.addField(2, scalar(maximum));
    var property = UnknownFieldSet.newBuilder();
    if (!variant.equals("missing-bar")) property.addField(2, nested(bar.build().toByteArray()));
    if (variant.equals("unknown-property")) property.addField(99, scalar(1));
    var event =
        UnknownFieldSet.newBuilder()
            .addField(1, scalar(variant.equals("foreign") ? 41001 : 41007))
            .addField(2, nested(property.build().toByteArray()))
            .build()
            .toByteArray();
    var events = UnknownFieldSet.Field.newBuilder().addLengthDelimited(ByteString.copyFrom(event));
    if (variant.equals("duplicate-event")) events.addLengthDelimited(ByteString.copyFrom(event));
    return UnknownFieldSet.newBuilder().addField(16, events.build()).build().toByteArray();
  }

  private byte[] request(DynamicMessage battle, String end, byte[] stats, boolean quit) {
    var type = protocol.clientSchema().message(quit ? "QuitBattleCsReq" : "PVEBattleResultCsReq");
    var result =
        UnknownFieldSet.newBuilder()
            .addField(type.findFieldByName("stt").getNumber(), nested(stats));
    if (!quit) {
      result.addField(
          type.findFieldByName("battle_id").getNumber(), scalar(number(battle, "battle_id")));
      result.addField(
          type.findFieldByName("stage_id").getNumber(), scalar(number(battle, "stage_id")));
      var status = type.findFieldByName("end_status");
      result.addField(
          status.getNumber(),
          scalar(status.getEnumType().findValueByName("BATTLE_END_" + end).getNumber()));
    }
    return result.build().toByteArray();
  }

  private void badReports(DynamicMessage battle, int expected) throws Exception {
    for (String variant :
        List.of(
            "foreign",
            "duplicate-event",
            "duplicate-current",
            "unknown-property",
            "overflow",
            "zero-max",
            "over-max")) {
      int current = variant.equals("overflow") ? -1 : variant.equals("over-max") ? 101 : 7;
      int maximum = variant.equals("zero-max") ? 0 : 100;
      var response =
          raw(
              "PVEBattleResultCsReq",
              request(battle, "WIN", statistics(variant, current, maximum), false));
      check(number(response, "retcode") != 0, "invalid-" + variant + "-rejected");
      var active = call("GetCurBattleInfoCsReq");
      success(active, "invalid-report-kept-active-battle");
      var actual = (DynamicMessage) field(active, "battle_info");
      check(
          number(actual, "battle_id") == number(battle, "battle_id"),
          "invalid-report-did-not-consume-settlement");
      energy(actual, expected);
    }
  }

  private Map<String, Object> execute(JsonObject input) throws Exception {
    String mode = input.get("mode").getAsString();
    require(
        Set.of("setup", "quit", "missing", "win", "lose", "recover").contains(mode),
        "Unknown mode");
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
    success(token, "actual-token-accepted");
    int uid = number(token, "uid");
    success(call("PlayerLoginCsReq"), "actual-player-login");
    success(call("PlayerLoginFinishCsReq"), "actual-player-login-finished");
    if (mode.equals("recover")) {
      success(call("GetRogueInfoCsReq"), "terminal-query-after-normal-h2-restart");
      check(
          number(call("EnterRogueCsReq", Map.of("area_id", 100)), "retcode") != 0,
          "failed-exploration-cannot-resume");
    } else {
      var entered =
          mode.equals("setup")
              ? call(
                  "StartRogueCsReq",
                  Map.of("area_id", 100, "AFEMBLFCCCH", 7, "NOEPBNMJFGL", List.of(8001)))
              : call("EnterRogueCsReq", Map.of("area_id", 100));
      success(entered, "actual-exploration-entry");
      var scene = (DynamicMessage) field(entered, "scene");
      actors(scene);
      if (!mode.equals("setup")) {
        int expected = mode.equals("quit") ? 2500 : mode.equals("lose") ? 3333 : 1750;
        var battle = encounter(scene, expected);
        boolean quit = mode.equals("quit");
        if (quit) badReports(battle, expected);
        String name = quit ? "QuitBattleCsReq" : "PVEBattleResultCsReq";
        String end = mode.equals("win") ? "WIN" : mode.equals("lose") ? "LOSE" : "QUIT";
        byte[] stats =
            statistics(
                mode.equals("missing") ? "missing-bar" : "normal",
                quit ? 7 : mode.equals("win") ? 1 : 0,
                quit ? 40 : mode.equals("win") ? 3 : 100);
        byte[] report = request(battle, end, stats, quit);
        var result = raw(name, report);
        success(result, "actual-" + mode + "-settlement-accepted");
        if (!quit)
          check(
              number(result, "battle_id") == number(battle, "battle_id")
                  && number(result, "stage_id") == number(battle, "stage_id"),
              "accepted-result-kept-source-battle-identity");
        check(number(raw(name, report), "retcode") != 0, "settlement-replay-rejected");
        check(
            number(call("GetCurBattleInfoCsReq"), "retcode") != 0,
            "accepted-battle-no-longer-active");
      }
      success(call("LeaveRogueCsReq"), "actual-exploration-leave");
    }
    return Map.of(
        "passed",
        true,
        "uid",
        uid,
        "mode",
        mode,
        "requests",
        requests,
        "checks",
        checks,
        "clientGameplayVerified",
        false,
        "completeResonance",
        false,
        "encounter",
        actualEncounter);
  }

  public static void main(String[] args) {
    var probe = new NativeRogueEnergyProbe();
    var client = new KcpClient();
    int exit = 1;
    try {
      var input =
          JsonParser.parseString(
                  new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))
                      .readLine())
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
            public void onConnected(Ukcp channel) {
              probe.connected.complete(channel);
            }

            public void handleReceive(ByteBuf data, Ukcp channel) {
              try {
                while (data.isReadable()) {
                  var frame = PacketCodec.decodeNext(data);
                  require(frame != null, "Truncated frame");
                  probe.inbox.add(new Frame(frame.cmdId(), frame.body()));
                }
              } catch (Throwable error) {
                probe.failure.set(error);
              }
            }

            public void handleException(Throwable error, Ukcp channel) {
              probe.failure.set(error);
              probe.connected.completeExceptionally(error);
            }

            public void handleClose(Ukcp channel) {
              probe.failure.compareAndSet(
                  null, new IllegalStateException("Energy connection closed"));
            }
          });
      client.connect(new InetSocketAddress("127.0.0.1", Integer.parseInt(args[0])), config);
      probe.channel = probe.connected.get(10, TimeUnit.SECONDS);
      System.out.println(new Gson().toJson(probe.execute(input)));
      exit = 0;
    } catch (Exception error) {
      error.printStackTrace(System.err);
    } finally {
      client.stop();
    }
    System.exit(exit);
  }
}
