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

/** Original Raid 40301/40414 script parties, pause/reenter and normal H2 restore; no client UI. */
public final class NativeRaidScriptedTrialProbe {
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

  private Map<String, Object> execute(JsonObject credentials) throws Exception {
    String mode = credentials.get("mode").getAsString();
    require(
        Set.of("pending", "first", "active", "pause", "return", "resume", "closed").contains(mode),
        "Unknown raid operation");
    int raid = credentials.get("raid").getAsInt(), sub = credentials.get("sub").getAsInt();
    int trial = credentials.get("trial").getAsInt(), actor = credentials.get("actor").getAsInt();
    int entry = credentials.get("entry").getAsInt(), floor = credentials.get("floor").getAsInt();
    int plane = credentials.has("plane") ? credentials.get("plane").getAsInt() : raid;
    boolean trialOnly = credentials.has("trialOnly") && credentials.get("trialOnly").getAsBoolean();
    List<Integer> base =
        credentials.has("baseRoster")
            ? credentials.getAsJsonArray("baseRoster").asList().stream()
                .map(JsonElement::getAsInt)
                .toList()
            : List.of(8001);
    Set<Integer> baseActorIds =
        credentials.has("baseActorIds")
            ? new HashSet<>(
                credentials.getAsJsonArray("baseActorIds").asList().stream()
                    .map(JsonElement::getAsInt)
                    .toList())
            : Set.of(8001);
    boolean virtual = credentials.get("virtual").getAsBoolean();
    boolean active = Set.of("first", "active", "return", "resume").contains(mode);
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
        "Unexpected raid currency reward");
    call("PlayerLoginFinishCsReq");
    if (mode.equals("first") || mode.equals("return"))
      call("StartRaidCsReq", Map.of("raid_id", raid));
    if (mode.equals("pause")) call("LeaveRaidCsReq", Map.of("raid_id", raid));
    var lineup = (DynamicMessage) field(call("GetCurLineupDataCsReq"), "lineup");
    var ids = new ArrayList<Integer>();
    int hp = 0, sp = 0;
    for (var raw : (List<?>) field(lineup, "avatar_list")) {
      var member = (DynamicMessage) raw;
      int id = number(member, "id");
      ids.add(id);
      String kind = ((Descriptors.EnumValueDescriptor) field(member, "avatar_type")).getName();
      if (id == trial) {
        require(
            active && kind.contains("Trial"), "Dynamic raid role was exposed as a formal avatar");
        hp = number(member, "hp");
        sp = number((DynamicMessage) field(member, "sp_bar"), "cur_sp");
        require(hp == 10000 && sp == 7000, "Paused/reloaded raid changed source trial vitals");
      } else if (active && trialOnly) {
        require(
            base.contains(id) && kind.contains("Trial"),
            "Declared trial party was replaced or made formal");
        require(
            number(member, "hp") == 10000
                && number((DynamicMessage) field(member, "sp_bar"), "cur_sp") == 7000,
            "Declared trial member vitals changed");
      } else require(id == 8001 && kind.contains("Formal"), "Unexpected raid party member");
    }
    var appended = new ArrayList<>(base);
    appended.add(trial);
    List<Integer> expected = active ? (virtual ? List.of(trial) : appended) : List.of(8001);
    require(ids.equals(expected), "Original script did not publish the actual raid party: " + ids);
    require(
        (Boolean) field(lineup, "_is_virtual") == (active && virtual) && number(lineup, "mp") == 5,
        "Virtual flag or MP changed");
    require(number(lineup, "leader_slot") == 0, "Declared raid captain changed");
    if (active && trialOnly) {
      for (int slot : List.of(1, 2)) {
        var denied =
            call(
                "ChangeLineupLeaderCsReq",
                Map.of("slot", slot),
                Retcode.REQ_PARA_INVALID.getVal());
        require(number(denied, "slot") == 0, "Rejected captain change acknowledged another slot");
      }
    }
    require(
        lineup.equals(field(call("GetCurLineupDataCsReq"), "lineup")),
        "Query reset the frozen raid party");
    var all = call("GetAllLineupDataCsReq");
    boolean found = false;
    require(
        number(all, "cur_index") == number(lineup, "index"),
        "All-party query changed current index");
    for (var raw : (List<?>) field(all, "lineup_list")) {
      var party = (DynamicMessage) raw;
      if (number(party, "index") == number(lineup, "index")) {
        require(!found && party.equals(lineup), "All-party and current-party queries disagree");
        found = true;
      }
    }
    require(found, "Current raid party omitted");
    var detail = call("GetLineupAvatarDataCsReq");
    var detailIds = new ArrayList<Integer>();
    for (var raw : (List<?>) field(detail, "avatar_data_list"))
      detailIds.add(number((DynamicMessage) raw, "id"));
    require(detailIds.equals(ids), "Raid trial missing from actual actor details");
    var scene = (DynamicMessage) field(call("GetCurSceneInfoCsReq"), "scene");
    var actors = new TreeSet<Integer>();
    for (var group : (List<?>) field(scene, "entity_group_list"))
      for (var raw : (List<?>) field((DynamicMessage) group, "entity_list")) {
        var entity = (DynamicMessage) raw;
        var f = entity.getDescriptorForType().findFieldByName("actor");
        if (f != null && entity.hasField(f))
          actors.add(number((DynamicMessage) field(entity, "actor"), "base_avatar_id"));
      }
    var appendedActors = new HashSet<>(baseActorIds);
    appendedActors.add(actor);
    require(
        actors.equals(active ? (virtual ? Set.of(actor) : appendedActors) : Set.of(8001)),
        "Scene and raid party differ");
    if (active)
      require(
          number(scene, "plane_id") == plane
              && number(scene, "floor_id") == floor
              && number(scene, "entry_id") == entry,
          "Original raid location changed");
    else if (!mode.equals("pending"))
      require(
          number(scene, "plane_id") == 20001
              && number(scene, "floor_id") == 20001001
              && number(scene, "entry_id") == 2000101,
          "Paused raid did not return to the saved actual home");
    if (!mode.equals("pending")) {
      var status = call("GetMissionStatusCsReq", Map.of("sub_mission_id_list", List.of(sub)));
      boolean complete = false;
      for (var raw : (List<?>) field(status, "sub_mission_status_list")) {
        var mission = (DynamicMessage) raw;
        if (number(mission, "id") == sub)
          complete =
              number(mission, "progress") == 1
                  && ((Descriptors.EnumValueDescriptor) field(mission, "status"))
                      .getName()
                      .equals("MissionStatus_MissionFinish");
      }
      require(complete, "Original raid trial objective is incomplete or reset");
    }
    require(
        lineup.equals(field(call("GetCurLineupDataCsReq"), "lineup")),
        "Scene/status query changed the actual party");
    var result = new LinkedHashMap<String, Object>();
    result.put("passed", true);
    result.put("mode", mode);
    result.put("uid", uid);
    result.put("raid", raid);
    result.put("requests", requests);
    result.put("party", ids);
    result.put("sceneActors", actors);
    result.put("trialHP", hp);
    result.put("trialSP", sp);
    result.put("virtual", active && virtual);
    result.put("credits", number(basic, "scoin"));
    result.put("captainChangesRejected", active && trialOnly ? 2 : 0);
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
        throw new IllegalStateException("Native mission trial connection failed", failure.get());
      var f = inbox.poll(1, TimeUnit.SECONDS);
      if (f == null) continue;
      observe(f);
      if (f.command() == response) return f;
    }
    throw new TimeoutException("Response " + response);
  }

  private DynamicMessage call(String name, Map<String, Object> fields) throws Exception {
    return call(name, fields, 0);
  }

  private DynamicMessage call(String name, Map<String, Object> fields, int expectedRetcode)
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
    require(m.getUnknownFields().asMap().isEmpty(), "Unknown mission trial response fields");
    if (!name.equals("GetRogueBuffEnhanceInfoCsReq"))
      require(
          number(m, "retcode") == expectedRetcode,
          "Unexpected " + response + " status=" + number(m, "retcode"));
    return m;
  }

  private DynamicMessage call(String name) throws Exception {
    return call(name, Map.of());
  }

  public static void main(String[] args) {
    var probe = new NativeRaidScriptedTrialProbe();
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
                  require(frame != null, "Truncated mission trial frame");
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
                  null, new IllegalStateException("Mission trial probe disconnected"));
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
