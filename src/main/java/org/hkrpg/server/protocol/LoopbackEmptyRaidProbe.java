package org.hkrpg.server.protocol;

import com.google.gson.*;
import com.google.protobuf.Descriptors.EnumValueDescriptor;
import com.google.protobuf.DynamicMessage;
import emu.lunarcore.server.packet.*;
import io.netty.buffer.*;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import kcp.highway.*;

/** Original 40403 entry and first talk with a fixture parent, never a completed official story. */
public final class LoopbackEmptyRaidProbe {
  private record Frame(int command, byte[] body) {}

  private final ProtocolBridge protocol = ProtocolBridge.forVersion("4.6.0");
  private final BlockingQueue<Frame> inbox = new LinkedBlockingQueue<>();
  private final AtomicReference<Throwable> failure = new AtomicReference<>();
  private final CompletableFuture<Ukcp> connected = new CompletableFuture<>();
  private final List<String> checks = new ArrayList<>();
  private Ukcp channel;

  private static Object field(DynamicMessage m, String name) {
    return m.getField(m.getDescriptorForType().findFieldByName(name));
  }

  private static int number(DynamicMessage m, String name) {
    return (Integer) field(m, name);
  }

  private static void require(boolean value, String reason) {
    if (!value) throw new IllegalStateException(reason);
  }

  private void check(boolean value, String reason) {
    require(value, reason);
    checks.add(reason);
  }

  private DynamicMessage.Builder request(String name, Map<String, Object> fields) {
    var b = DynamicMessage.newBuilder(protocol.clientSchema().message(name));
    fields.forEach(
        (key, value) -> b.setField(b.getDescriptorForType().findFieldByName(key), value));
    return b;
  }

  private DynamicMessage call(String name, Map<String, Object> fields, int code) throws Exception {
    var packet =
        Unpooled.wrappedBuffer(
            PacketCodec.encode(
                protocol.clientCommand(name),
                new byte[0],
                request(name, fields).build().toByteArray()));
    try {
      channel.write(packet);
    } finally {
      packet.release();
    }
    String response = name.replace("CsReq", "ScRsp");
    int command = protocol.clientCommand(response);
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
    while (System.nanoTime() < deadline) {
      if (failure.get() != null)
        throw new IllegalStateException("KCP connection failed", failure.get());
      var frame = inbox.poll(1, TimeUnit.SECONDS);
      if (frame == null || frame.command() != command) continue;
      var m = protocol.clientSchema().parse(response, frame.body());
      require(m.getUnknownFields().asMap().isEmpty(), "Unknown response fields: " + response);
      require(
          number(m, "retcode") == code,
          "Response rejected: " + response + " code=" + number(m, "retcode"));
      return m;
    }
    throw new TimeoutException(response);
  }

  private DynamicMessage call(String name) throws Exception {
    return call(name, Map.of(), 0);
  }

  private DynamicMessage scene() throws Exception {
    return (DynamicMessage) field(call("GetCurSceneInfoCsReq"), "scene");
  }

  private static Map<String, Object> place(DynamicMessage scene) {
    Map<String, Object> motion = null;
    for (var group : (List<?>) field(scene, "entity_group_list"))
      for (var item : (List<?>) field((DynamicMessage) group, "entity_list")) {
        var entity = (DynamicMessage) item;
        var actor = entity.getDescriptorForType().findFieldByName("actor");
        if (!entity.hasField(actor)) continue;
        var m = (DynamicMessage) field(entity, "motion");
        var row = new LinkedHashMap<String, Object>();
        for (var name : List.of("pos", "rot")) {
          var point = (DynamicMessage) field(m, name);
          row.put(name, List.of(number(point, "x"), number(point, "y"), number(point, "z")));
        }
        motion = row;
        break;
      }
    require(motion != null && number(scene, "leader_entity_id") > 0, "Missing actual scene actor");
    return Map.of(
        "entry",
        number(scene, "entry_id"),
        "plane",
        number(scene, "plane_id"),
        "floor",
        number(scene, "floor_id"),
        "motion",
        motion);
  }

  private static List<Integer> formals(DynamicMessage team) {
    require(
        ((EnumValueDescriptor) field(team, "extra_lineup_type")).getNumber() == 0,
        "Raid replaced ordinary team");
    var ids = new ArrayList<Integer>();
    for (var value : (List<?>) field(team, "avatar_list")) {
      var avatar = (DynamicMessage) value;
      require(
          ((EnumValueDescriptor) field(avatar, "avatar_type"))
              .getName()
              .equals("AvatarType_AvatarFormalType"),
          "Undeclared trial published");
      ids.add(number(avatar, "id"));
    }
    require(!ids.isEmpty() && ids.size() <= 4, "Missing formal team");
    return List.copyOf(ids);
  }

  private void raid(DynamicMessage response, List<Integer> roster) {
    var wrapper = (DynamicMessage) field(response, "scene");
    check(
        number(wrapper, "raid_id") == 40403 && number(wrapper, "world_level") == 0,
        "original-raid-wrapper");
    var scene = (DynamicMessage) field(wrapper, "MOPHKIGKDIK");
    check(
        number(scene, "entry_id") == 40403001
            && number(scene, "floor_id") == 40403001
            && number(scene, "plane_id") == 40403,
        "original-first-floor-entry");
    place(scene);
    check(
        formals((DynamicMessage) field(wrapper, "lineup")).equals(roster),
        "owned-formal-roster-without-trials");
  }

  private Map<String, Object> execute(JsonObject credentials) throws Exception {
    var token =
        call(
            "PlayerGetTokenCsReq",
            Map.of(
                "account_uid",
                credentials.get("accountUid").getAsString(),
                "token",
                credentials.get("comboToken").getAsString(),
                "platform",
                1),
            0);
    int uid = number(token, "uid");
    call("PlayerLoginCsReq");
    call("PlayerLoginFinishCsReq");
    boolean resume = credentials.has("resume") && credentials.get("resume").getAsBoolean();
    var initial = scene();
    var team = (DynamicMessage) field(call("GetCurLineupDataCsReq"), "lineup");
    var roster = formals(team);
    JsonObject home;
    if (resume) {
      home = credentials.getAsJsonObject("home");
      check(number(initial, "entry_id") == 40403001, "saved-original-raid-scene-restored");
      check(
          new Gson().toJsonTree(roster).equals(credentials.get("roster")),
          "saved-formal-roster-restored");
    } else {
      home = new Gson().toJsonTree(place(initial)).getAsJsonObject();
      call("AcceptMainMissionCsReq", Map.of("main_mission_id", 1040113), 0);
      raid(call("StartRaidCsReq", Map.of("raid_id", 40403), 0), roster);
      call(
          "FinishTalkMissionCsReq",
          Map.of("sub_mission_id", 104011501, "talk_str", "Mission_104011501"),
          0);
      check(true, "original-first-talk-accepted");
    }
    call(
        "FinishTalkMissionCsReq",
        Map.of("sub_mission_id", 104011501, "talk_str", "Mission_104011501"),
        Retcode.MISSION_NOT_DOING.getVal());
    check(true, "completed-first-talk-not-replayed");
    var missions = call("GetMissionDataCsReq");
    var rows =
        ((List<?>) field(missions, "mission_list"))
            .stream()
                .map(DynamicMessage.class::cast)
                .filter(m -> number(m, "id") == 104011501)
                .toList();
    check(
        rows.size() == 1
            && number(rows.getFirst(), "progress") == 1
            && ((EnumValueDescriptor) field(rows.getFirst(), "status")).getNumber() == 2,
        "actual-first-talk-history");
    check(
        !((List<?>) field(missions, "finished_main_mission_id_list")).contains(1040115),
        "full-official-mission-remains-unfinished");
    call("LeaveRaidCsReq", Map.of("raid_id", 40403, "is_save", false), 0);
    check(new Gson().toJsonTree(place(scene())).equals(home), "exact-original-home-restored");
    check(
        formals((DynamicMessage) field(call("GetCurLineupDataCsReq"), "lineup")).equals(roster),
        "ordinary-roster-preserved-after-leave");
    raid(call("StartRaidCsReq", Map.of("raid_id", 40403), 0), roster);
    var result = new LinkedHashMap<String, Object>();
    result.put("passed", true);
    result.put("uid", uid);
    result.put("checks", checks);
    result.put("home", home);
    result.put("roster", roster);
    result.put("entry", 40403001);
    result.put("firstTalk", 104011501);
    result.put("resume", resume);
    result.put("originalMissionGraphVerified", false);
    result.put("clientGameplayVerified", false);
    return result;
  }

  public static void main(String[] args) {
    var probe = new LoopbackEmptyRaidProbe();
    var client = new KcpClient();
    Ukcp connection = null;
    int exit = 1;
    try {
      var credentials =
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
                  require(frame != null, "Truncated raid frame");
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
                  null, new IllegalStateException("Raid probe disconnected"));
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
      if (connection != null) connection.close();
      client.stop();
    }
    System.exit(exit);
  }
}
