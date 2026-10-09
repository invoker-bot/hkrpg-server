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

/** Actual merchant acquisition and source-prop rolls; synthetic KCP, no official client UI. */
public final class NativeRogueLotteryProbe {
  private record Frame(int command, byte[] body) {}

  private final ProtocolBridge protocol = ProtocolBridge.forVersion("4.6.0");
  private final BlockingQueue<Frame> inbox = new LinkedBlockingQueue<>();
  private final AtomicReference<Throwable> failure = new AtomicReference<>();
  private final CompletableFuture<Ukcp> connected = new CompletableFuture<>();
  private Ukcp channel;
  private int requests, expectedRemoval, removals, awards, breaks, lineupUpdates;
  private final List<String> checks = new ArrayList<>();

  private static Object field(DynamicMessage m, String n) {
    return m.getField(Objects.requireNonNull(m.getDescriptorForType().findFieldByName(n), n));
  }

  private static int number(DynamicMessage m, String n) {
    return ((Number) field(m, n)).intValue();
  }

  private static void require(boolean ok, String reason) {
    if (!ok) throw new IllegalStateException(reason);
  }

  private void check(boolean ok, String reason) {
    require(ok, reason);
    checks.add(reason);
  }

  private void observe(Frame f) throws Exception {
    if (f.command() == protocol.clientCommand("SceneGroupRefreshScNotify")) {
      var m = protocol.clientSchema().parse("SceneGroupRefreshScNotify", f.body());
      for (var g : (List<?>) field(m, "group_refresh_list"))
        for (var r : (List<?>) field((DynamicMessage) g, "refresh_entity"))
          if (number((DynamicMessage) r, "delete_entity") == expectedRemoval && expectedRemoval > 0)
            removals++;
    } else if (f.command() == protocol.clientCommand("SyncRogueCommonActionResultScNotify")) {
      var m = protocol.clientSchema().parse("SyncRogueCommonActionResultScNotify", f.body());
      for (var r : (List<?>) field(m, "CLGGKLNPLCK")) {
        var detail = (DynamicMessage) field((DynamicMessage) r, "detail");
        for (String name : List.of("GPJJMPCMAAA", "KDJBDMLDMHG"))
          if (detail.hasField(detail.getDescriptorForType().findFieldByName(name))) {
            var item = (DynamicMessage) field((DynamicMessage) field(detail, name), "miracle");
            int id = number(item, "LJCEOGPOGBE"), uses = number(item, "DGGCFDKJDPO");
            if (name.equals("GPJJMPCMAAA") && id != 63) {
              require(
                  Set.of(2, 12).contains(id) && uses == 0,
                  "Wrong actual lottery award notification");
              awards++;
            }
            if (name.equals("KDJBDMLDMHG") && id == 63) {
              require(
                  uses == 1 && number(item, "total_count") == 1,
                  "Wrong broken lottery notification");
              breaks++;
            }
          }
      }
    } else if (f.command() == protocol.clientCommand("SyncLineupNotify")) {
      var m = protocol.clientSchema().parse("SyncLineupNotify", f.body());
      var lineup = (DynamicMessage) field(m, "lineup");
      for (var r : (List<?>) field(lineup, "avatar_list"))
        if (number((DynamicMessage) r, "hp") == 100) lineupUpdates++;
    }
  }

  private DynamicMessage info() throws Exception {
    var m = call("GetRogueInfoCsReq");
    require(number(m, "retcode") == 0, "Rogue query failed");
    return (DynamicMessage) field(m, "rogue_game_info");
  }

  private Map<Integer, List<Integer>> owned(DynamicMessage game) {
    var current = (DynamicMessage) field(game, "CFDIKLBINHK");
    var items =
        (DynamicMessage) field((DynamicMessage) field(current, "DNPCCFFFBHN"), "DNPCCFFFBHN");
    var out = new TreeMap<Integer, List<Integer>>();
    for (var r : (List<?>) field(items, "MAJGMMCOCIG")) {
      var row = (DynamicMessage) r;
      require(
          out.put(
                  number(row, "LJCEOGPOGBE"),
                  List.of(number(row, "DGGCFDKJDPO"), number(row, "total_count")))
              == null,
          "Duplicate owned curio");
    }
    return out;
  }

  private int hp() throws Exception {
    var response = call("GetCurLineupDataCsReq");
    require(number(response, "retcode") == 0, "Lineup query failed");
    var lineup = (DynamicMessage) field(response, "lineup");
    var rows = (List<?>) field(lineup, "avatar_list");
    require(rows.size() == 1, "Wrong lottery fixture roster");
    return number((DynamicMessage) rows.getFirst(), "hp");
  }

  private DynamicMessage shop(int entity, boolean buy) throws Exception {
    var bytes = new java.io.ByteArrayOutputStream();
    var out = CodedOutputStream.newInstance(bytes);
    out.writeUInt32(12, entity);
    if (buy) out.writeUInt32(6, 63);
    out.flush();
    requests++;
    String reply = buy ? "BuyRogueShopMiracleScRsp" : "GetRogueShopMiracleInfoScRsp";
    return protocol
        .clientSchema()
        .parse(
            reply,
            raw(buy ? 5656 : 5675, bytes.toByteArray(), protocol.clientCommand(reply)).body());
  }

  private DynamicMessage scene() throws Exception {
    var m = call("GetCurSceneInfoCsReq");
    require(number(m, "retcode") == 0, "Scene query failed");
    return (DynamicMessage) field(m, "scene");
  }

  private Map<String, Integer> props(DynamicMessage scene, JsonObject source) {
    var result = new TreeMap<String, Integer>();
    require(
        number(scene, "entry_id") == 8020101 && number(scene, "floor_id") == 80201001,
        "Wrong authored fixture scene");
    for (var g : (List<?>) field(scene, "entity_group_list"))
      for (var r : (List<?>) field((DynamicMessage) g, "entity_list")) {
        var e = (DynamicMessage) r;
        String key = number(e, "group_id") + ":" + number(e, "inst_id");
        if (!source.has(key)) continue;
        require(
            e.hasField(e.getDescriptorForType().findFieldByName("prop"))
                && number((DynamicMessage) field(e, "prop"), "prop_id")
                    == source.get(key).getAsInt(),
            "Source prop identity differs");
        require(result.put(key, number(e, "entity_id")) == null, "Duplicate authored prop");
      }
    return result;
  }

  private Map<String, Object> execute(JsonObject input) throws Exception {
    String mode = input.get("mode").getAsString();
    require(Set.of("buy", "roll", "resume").contains(mode), "Invalid mode");
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
    if (mode.equals("buy"))
      require(
          number(
                  call("StartRogueCsReq", Map.of("area_id", 100, "NOEPBNMJFGL", List.of(8001))),
                  "retcode")
              == 0,
          "Start failed");
    var scene = scene();
    var present = props(scene, input.getAsJsonObject("props"));
    if (mode.equals("buy")) {
      check(
          present.size() == input.getAsJsonObject("props").size(),
          "all-original-selected-props-loaded");
      int npc = 0;
      for (var g : (List<?>) field(scene, "entity_group_list"))
        for (var r : (List<?>) field((DynamicMessage) g, "entity_list")) {
          var e = (DynamicMessage) r;
          if (number(e, "group_id") == 35
              && number(e, "inst_id") == 400001
              && e.hasField(e.getDescriptorForType().findFieldByName("npc"))) {
            require(
                number((DynamicMessage) field(e, "npc"), "npc_id") == 1013,
                "Wrong original merchant");
            npc = number(e, "entity_id");
          }
        }
      require(npc > 0, "No actual merchant");
      var quote = shop(npc, false);
      require(number(quote, "retcode") == 0, "Merchant query rejected");
      DynamicMessage selected = null;
      for (var r : (List<?>) field((DynamicMessage) field(quote, "DNPCCFFFBHN"), "MAJGMMCOCIG"))
        if (number((DynamicMessage) r, "LJCEOGPOGBE") == 63) selected = (DynamicMessage) r;
      require(selected != null, "Original lottery missing from bounded fixture stock");
      var bought = shop(npc, true);
      check(number(bought, "retcode") == 0, "native-original-lottery-purchase");
      check(number(shop(npc, true), "retcode") != 0, "repeat-merchant-purchase-rejected");
      check(owned(info()).equals(Map.of(63, List.of(0, 1))), "acquired-lottery-native-lifetime");
      check(hp() == 10000, "actual-starting-hp");
    } else {
      var chosen = input.getAsJsonArray("chosen");
      require(chosen.size() == 3, "Three actual branch props required");
      if (mode.equals("roll"))
        for (var rawChoice : chosen) {
          var choice = rawChoice.getAsJsonObject();
          String key = choice.get("key").getAsString(),
              outcome = choice.get("outcome").getAsString();
          require(present.containsKey(key), "Chosen actual prop not present");
          int entity = present.get(key);
          int before = hp();
          int oldAwards = awards,
              oldBreaks = breaks,
              oldLineups = lineupUpdates,
              oldRemovals = removals;
          expectedRemoval = entity;
          var cast =
              Map.<String, Object>of(
                  "cast_entity_id",
                  number(scene, "leader_entity_id"),
                  "attacked_by_entity_id",
                  number(scene, "leader_entity_id"),
                  "hit_target_entity_id_list",
                  List.of(entity));
          var reply = call("SceneCastSkillCsReq", cast);
          check(
              number(reply, "retcode") == 0
                  && !reply.hasField(reply.getDescriptorForType().findFieldByName("battle_info")),
              "actual-prop-attack-" + outcome);
          check(removals == oldRemovals + 1, "one-real-removal-" + outcome);
          var after = owned(info());
          check(
              outcome.equals("BREAK") ? hp() == (before + 99) / 100 : hp() == before,
              "actual-hp-" + outcome);
          if (outcome.equals("GAIN"))
            check(
                awards == oldAwards + 1 && after.size() == 2,
                "real-award-and-acquisition-notification");
          if (outcome.equals("BREAK"))
            check(
                breaks == oldBreaks + 1
                    && lineupUpdates == oldLineups + 1
                    && after.get(63).equals(List.of(1, 1)),
                "native-break-and-lineup-notification");
          String frozen = after.toString();
          int savedHp = hp();
          check(
              number(call("SceneCastSkillCsReq", cast), "retcode") != 0,
              "repeat-physical-attack-rejected-" + outcome);
          check(
              owned(info()).toString().equals(frozen)
                  && hp() == savedHp
                  && removals == oldRemovals + 1,
              "retry-keeps-real-outcome-" + outcome);
        }
      var after = owned(info());
      check(
          after.size() == 2 && after.get(63).equals(List.of(1, 1)) && hp() == 100,
          "broken-lottery-and-earned-curio-restored");
      var reloaded = props(scene(), input.getAsJsonObject("props"));
      for (var rawChoice : chosen)
        check(
            !reloaded.containsKey(rawChoice.getAsJsonObject().get("key").getAsString()),
            "saved-physical-prop-stays-removed");
    }
    var result = new LinkedHashMap<String, Object>();
    result.put("passed", true);
    result.put("uid", uid);
    result.put("mode", mode);
    result.put("owned", owned(info()));
    result.put("hp", hp());
    result.put("requests", requests);
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
        throw new IllegalStateException("Native lottery connection failed", failure.get());
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
    require(m.getUnknownFields().asMap().isEmpty(), "Unknown lottery response fields");
    return m;
  }

  private DynamicMessage call(String name) throws Exception {
    return call(name, Map.of());
  }

  public static void main(String[] args) {
    var probe = new NativeRogueLotteryProbe();
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
                  require(frame != null, "Truncated lottery frame");
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
                  null, new IllegalStateException("Lottery probe disconnected"));
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
