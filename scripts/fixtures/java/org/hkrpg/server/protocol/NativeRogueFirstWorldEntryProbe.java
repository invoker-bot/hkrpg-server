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

/** Actual default first-world room bindings and reconnect; no official client UI. */
public final class NativeRogueFirstWorldEntryProbe {
  private record Frame(int command, byte[] body) {}

  private final ProtocolBridge protocol = ProtocolBridge.forVersion("4.6.0");
  private final BlockingQueue<Frame> inbox = new LinkedBlockingQueue<>();
  private final AtomicReference<Throwable> failure = new AtomicReference<>();
  private final CompletableFuture<Ukcp> connected = new CompletableFuture<>();
  private Ukcp channel;
  private int requests;
  private boolean legacyOrigin;
  private JsonObject spawn;
  private Map<String, Integer> leaderPosition = Map.of();

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

  private Map<Integer, Integer> assignments(DynamicMessage game, boolean active) {
    var currentField = game.getDescriptorForType().findFieldByName("CFDIKLBINHK");
    require(game.hasField(currentField) == active, "Unexpected current run");
    if (!active) return Map.of();
    var current = (DynamicMessage) field(game, "CFDIKLBINHK");
    var map = (DynamicMessage) field(current, "COOOMDKGODF");
    require(
        number(map, "area_id") == 110
            && number(map, "map_id") == 101
            && number(map, "ANHDBMOPGDC") == 1
            && number(map, "HPKMGKPPELC") == 304,
        "Wrong original world or first room");
    var actual = new TreeMap<Integer, Integer>();
    for (var raw : (List<?>) field(map, "LLNICHDDIAN")) {
      var room = (DynamicMessage) raw;
      int site = number(room, "MAJKFIMPOOF");
      require(actual.put(site, number(room, "room_id")) == null, "Duplicate published site");
      var status = (Descriptors.EnumValueDescriptor) field(room, "CAGDNHCOCKM");
      require(
          status.getName().equals(site == 1 ? "RogueRoomStatus_Play" : "RogueRoomStatus_Lock"),
          "Uncleared world published an unlocked destination");
    }
    require(
        actual.equals(Map.of(1, 304, 2, 302, 3, 303, 4, 304, 5, 305, 6, 306, 7, 307)),
        "Default map pool assignments differ");
    return actual;
  }

  private Map<Integer, Map<String, Integer>> actors(DynamicMessage scene, JsonObject source) {
    require(
        number(scene, "entry_id") == 8000103
            && number(scene, "plane_id") == 80001
            && number(scene, "floor_id") == 80001003,
        "Wrong original room entrance");
    var actual = new TreeMap<Integer, Map<String, Integer>>();
    boolean foundLeader = false;
    int leader = number(scene, "leader_entity_id");
    for (var rawGroup : (List<?>) field(scene, "entity_group_list"))
      for (var raw : (List<?>) field((DynamicMessage) rawGroup, "entity_list")) {
        var entity = (DynamicMessage) raw;
        if (number(entity, "entity_id") == leader) {
          var motion = (DynamicMessage) field(entity, "motion");
          var pos = (DynamicMessage) field(motion, "pos");
          var rot = (DynamicMessage) field(motion, "rot");
          for (String axis : List.of("x", "y", "z"))
            require(Math.abs(number(pos, axis) - (legacyOrigin ? 0 : spawn.get(axis).getAsInt())) <= 2,
                "Native leader is outside the authored room entrance: " + axis + "=" + number(pos, axis));
          require(Math.abs(number(rot, "y") - (legacyOrigin ? 0 : spawn.get("rotY").getAsInt())) <= 2,
              "Native leader rotation differs from room entrance");
          leaderPosition = Map.of("x", number(pos, "x"), "y", number(pos, "y"), "z", number(pos, "z"));
          foundLeader = true;
        }
        if (!entity.hasField(entity.getDescriptorForType().findFieldByName("npc_monster")))
          continue;
        int group = number(entity, "group_id"), inst = number(entity, "inst_id");
        var expected = source.getAsJsonObject(Integer.toString(group));
        require(
            expected != null && inst == expected.get("inst").getAsInt(), "Wrong authored actor");
        var npc = (DynamicMessage) field(entity, "npc_monster");
        int id = number(npc, "monster_id"), stage = number(npc, "event_id");
        boolean found = false;
        for (var row : expected.getAsJsonArray("choices")) {
          var choice = row.getAsJsonObject();
          if (choice.get("npc").getAsInt() == id && choice.get("stage").getAsInt() == stage)
            found = true;
        }
        require(found, "Actor differs from source weighted content");
        require(
            actual.put(group, Map.of("inst", inst, "npc", id, "stage", stage)) == null,
            "Duplicate authored group");
      }
    require(
        actual.size() == 3 && actual.keySet().equals(Set.of(18, 19, 20)),
        "Missing source room actors");
    require(foundLeader, "Missing native leader position");
    return actual;
  }

  private Map<String, Object> execute(JsonObject credentials) throws Exception {
    String mode = credentials.get("mode").getAsString();
    legacyOrigin = credentials.has("legacyOrigin") && credentials.get("legacyOrigin").getAsBoolean();
    spawn = credentials.getAsJsonObject("spawn");
    require(Set.of("pending", "start", "resume").contains(mode), "Unknown probe mode");
    int uid =
        number(
            call(
                "PlayerGetTokenCsReq",
                Map.of(
                    "account_uid",
                    credentials.get("accountUid").getAsString(),
                    "token",
                    credentials.get("comboToken").getAsString(),
                    "platform",
                    1)),
            "uid");
    var basic = (DynamicMessage) field(call("PlayerLoginCsReq"), "basic_info");
    require(
        number(basic, "hcoin") == 0 && number(basic, "scoin") == 0,
        "Entry unexpectedly granted currency");
    call("PlayerLoginFinishCsReq");
    var initial =
        assignments(
            (DynamicMessage) field(call("GetRogueInfoCsReq"), "rogue_game_info"),
            mode.equals("resume"));
    Map<Integer, Map<String, Integer>> bindings = Map.of();
    if (!mode.equals("pending")) {
      DynamicMessage scene;
      if (mode.equals("start")) {
        var response =
            call(
                "StartRogueCsReq",
                Map.of(
                    "area_id",
                    110,
                    "NOEPBNMJFGL",
                    List.of(emu.lunarcore.GameConstants.TRAILBLAZER_AVATAR_ID)));
        initial = assignments((DynamicMessage) field(response, "rogue_game_info"), true);
        scene = (DynamicMessage) field(response, "scene");
      } else scene = (DynamicMessage) field(call("GetCurSceneInfoCsReq"), "scene");
      bindings = actors(scene, credentials.getAsJsonObject("actors"));
      require(
          bindings.equals(
              actors(
                  (DynamicMessage) field(call("GetCurSceneInfoCsReq"), "scene"),
                  credentials.getAsJsonObject("actors"))),
          "A repeated query rerolled authored content");
      require(
          initial.equals(
              assignments(
                  (DynamicMessage) field(call("GetRogueInfoCsReq"), "rogue_game_info"), true)),
          "A repeated query rerolled rooms");
      // The original next site stays locked until actual room clearance.
      var req =
          DynamicMessage.newBuilder(protocol.clientSchema().message("EnterRogueMapRoomCsReq"));
      req.setField(req.getDescriptorForType().findFieldByName("MAJKFIMPOOF"), 2);
      req.setField(req.getDescriptorForType().findFieldByName("room_id"), 302);
      requests++;
      var rejected =
          protocol
              .clientSchema()
              .parse(
                  "EnterRogueMapRoomScRsp",
                  raw(
                          protocol.clientCommand("EnterRogueMapRoomCsReq"),
                          req.build().toByteArray(),
                          protocol.clientCommand("EnterRogueMapRoomScRsp"))
                      .body());
      require(number(rejected, "retcode") != 0, "Uncleared room could skip to the elite");
      require(
          bindings.equals(
              actors(
                  (DynamicMessage) field(call("GetCurSceneInfoCsReq"), "scene"),
                  credentials.getAsJsonObject("actors"))),
          "Rejected navigation changed room content");
    }
    var result = new LinkedHashMap<String, Object>();
    result.put("uid", uid);
    result.put("passed", true);
    result.put("mode", mode);
    result.put("assignments", initial);
    result.put("actors", bindings);
    result.put("leaderPosition", leaderPosition);
    result.put("legacyOriginExpected", legacyOrigin);
    result.put("requests", requests);
    result.put("credits", 0);
    result.put("clientGameplayVerified", false);
    result.put("officialRoomGenerationVerified", false);
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
    require(m.getUnknownFields().asMap().isEmpty(), "Unknown first world entry response fields");
    if (!name.equals("GetRogueBuffEnhanceInfoCsReq"))
      require(
          number(m, "retcode") == 0, "Unexpected " + response + " status=" + number(m, "retcode"));
    return m;
  }

  private DynamicMessage call(String name) throws Exception {
    return call(name, Map.of());
  }

  public static void main(String[] args) {
    var probe = new NativeRogueFirstWorldEntryProbe();
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
