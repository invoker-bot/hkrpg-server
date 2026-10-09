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

/**
 * Original 43201 five stages, entry trial reuse, replacement and normal H2 restore; no client UI.
 */
public final class NativeFiveStageMissionRaidProbe {
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

  private void start() throws Exception {
    var request = protocol.clientSchema().message("StartRaidCsReq");
    var list = request.findFieldByName("NIMDEPFJJNI");
    var choices = new ArrayList<DynamicMessage>();
    for (int id : List.of(8001, 1101208)) {
      var member = DynamicMessage.newBuilder(list.getMessageType());
      var type = member.getDescriptorForType().findFieldByName("avatar_type");
      member.setField(member.getDescriptorForType().findFieldByName("id"), id);
      member.setField(
          type,
          type.getEnumType()
              .findValueByName(
                  id == 8001 ? "AvatarType_AvatarFormalType" : "AvatarType_AvatarTrialType"));
      choices.add(member.build());
    }
    call("StartRaidCsReq", Map.of("raid_id", 43201, "NIMDEPFJJNI", choices));
  }

  private Map<String, Object> execute(JsonObject c) throws Exception {
    String mode = c.get("mode").getAsString();
    String phase = c.has("phase") ? c.get("phase").getAsString() : mode;
    require(
        Set.of(
                "pending",
                "first",
                "second",
                "third",
                "alias",
                "replacement",
                "active",
                "pause",
                "return",
                "fourth",
                "fifth",
                "finished",
                "leaveFinished",
                "final")
            .contains(mode),
        "Unknown five-stage operation");
    int uid =
        number(
            call(
                "PlayerGetTokenCsReq",
                Map.of(
                    "account_uid",
                    c.get("accountUid").getAsString(),
                    "token",
                    c.get("comboToken").getAsString(),
                    "platform",
                    1)),
            "uid");
    var basic = (DynamicMessage) field(call("PlayerLoginCsReq"), "basic_info");
    require(
        number(basic, "scoin") == 0 && number(basic, "hcoin") == 0,
        "Display reward was granted as gameplay reward");
    call("PlayerLoginFinishCsReq");
    if (mode.equals("first") || mode.equals("return")) start();
    if (mode.equals("pause") || mode.equals("leaveFinished"))
      call("LeaveRaidCsReq", Map.of("raid_id", 43201));
    boolean done = Set.of("finished", "leaveFinished", "final").contains(mode);
    boolean outside = Set.of("pending", "pause", "leaveFinished", "final").contains(mode);
    boolean alias = phase.equals("alias"), replacement = phase.equals("replacement");
    var expected =
        outside
            ? List.of(8001)
            : alias ? List.of(1101208) : replacement ? List.of(1101001) : List.of(8001, 1101208);
    var team = (DynamicMessage) field(call("GetCurLineupDataCsReq"), "lineup");
    var ids = new ArrayList<Integer>();
    for (var raw : (List<?>) field(team, "avatar_list")) {
      var member = (DynamicMessage) raw;
      int id = number(member, "id");
      ids.add(id);
      boolean trial = id != 8001;
      require(
          ((Descriptors.EnumValueDescriptor) field(member, "avatar_type"))
              .getName()
              .contains(trial ? "Trial" : "Formal"),
          "Actor kind changed");
      require(
          number(member, "hp") == 10000
              && number((DynamicMessage) field(member, "sp_bar"), "cur_sp") == (trial ? 7000 : 0),
          "Actor vitals changed");
    }
    require(ids.equals(expected), "Five-stage roster differs: " + ids);
    require(
        number(team, "mp") == 5
            && number(team, "leader_slot") == 0
            && !(Boolean) field(team, "_is_virtual"),
        "Five-stage lineup changed");
    var all = call("GetAllLineupDataCsReq");
    int found = 0;
    for (var raw : (List<?>) field(all, "lineup_list")) {
      var v = (DynamicMessage) raw;
      if (number(v, "index") == number(team, "index")) {
        require(v.equals(team), "Current/all lineup differ");
        found++;
      }
    }
    require(found == 1, "Duplicate/missing current team");
    var details = call("GetLineupAvatarDataCsReq");
    var detailed = new ArrayList<Integer>();
    for (var raw : (List<?>) field(details, "avatar_data_list"))
      detailed.add(number((DynamicMessage) raw, "id"));
    require(detailed.equals(ids), "Role details differ");
    var scene = (DynamicMessage) field(call("GetCurSceneInfoCsReq"), "scene");
    var actors = new TreeSet<Integer>();
    var entities = new HashSet<Integer>();
    for (var group : (List<?>) field(scene, "entity_group_list"))
      for (var raw : (List<?>) field((DynamicMessage) group, "entity_list")) {
        var entity = (DynamicMessage) raw;
        var actor = entity.getDescriptorForType().findFieldByName("actor");
        if (actor != null && entity.hasField(actor)) {
          actors.add(number((DynamicMessage) entity.getField(actor), "base_avatar_id"));
          require(entities.add(number(entity, "entity_id")), "Duplicate scene actor");
        }
      }
    var expectedActors = new TreeSet<Integer>();
    for (int id : ids) expectedActors.add(id == 1101208 ? 1208 : id == 1101001 ? 1001 : id);
    require(
        actors.equals(expectedActors) && entities.size() == ids.size(),
        "Scene roster differs: " + actors);
    int stage =
        switch (phase) {
          case "second" -> 1;
          case "third", "alias", "replacement" -> 2;
          case "fourth" -> 3;
          case "fifth", "finished", "leaveFinished", "final" -> 4;
          default -> 0;
        };
    int entry = number(scene, "entry_id");
    if (!mode.equals("pending"))
      require(
          entry == (outside ? 2000101 : 43201001 + stage), "Wrong actual stage entry: " + entry);
    if (!outside)
      require(
          number(scene, "plane_id") == 43201 && number(scene, "floor_id") == 43201001 + stage,
          "Wrong raid floor");
    var status =
        call(
            "GetMissionStatusCsReq",
            Map.of(
                "main_mission_id_list",
                List.of(2020501, 2020502, 2020503, 2020504, 2020505, 2020506),
                "sub_mission_id_list",
                List.of(
                    202050211, 202050310, 202050409, 202050505, 202050602, 202050410, 202050412)));
    var mains = new TreeSet<Integer>();
    for (var raw : (List<?>) field(status, "finished_main_mission_id_list"))
      mains.add((Integer) raw);
    require(!mains.contains(2020501), "Parent fixture was completed");
    for (int i = 0; i < 5; i++)
      require(
          mains.contains(2020502 + i) == (done || i < stage), "Five-stage finish order differs");
    var subs = new TreeSet<Integer>();
    for (var raw : (List<?>) field(status, "sub_mission_status_list")) {
      var v = (DynamicMessage) raw;
      if (((Descriptors.EnumValueDescriptor) field(v, "status"))
          .getName()
          .equals("MissionStatus_MissionFinish")) subs.add(number(v, "id"));
    }
    require(
        subs.contains(202050410) == (alias || replacement || stage > 2 || done),
        "Original alias objective changed");
    require(
        subs.contains(202050412) == (replacement || stage > 2 || done),
        "Original replacement objective changed");
    boolean raidDone = false;
    for (var raw : (List<?>) field(call("GetRaidInfoCsReq"), "finished_raid_info_list"))
      if (number((DynamicMessage) raw, "raid_id") == 43201) raidDone = true;
    require(raidDone == done, "Premature entire raid completion");
    require(
        team.equals(field(call("GetCurLineupDataCsReq"), "lineup")),
        "Queries mutated frozen source team");
    var result = new LinkedHashMap<String, Object>();
    result.put("passed", true);
    result.put("mode", mode);
    result.put("phase", phase);
    result.put("uid", uid);
    result.put("requests", requests);
    result.put("party", ids);
    result.put("sceneActors", actors);
    result.put("entry", entry);
    result.put("stage", stage);
    result.put("finishedMains", mains);
    result.put("finishedSubs", subs);
    result.put("raidFinished", raidDone);
    result.put("credits", number(basic, "scoin"));
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
    var probe = new NativeFiveStageMissionRaidProbe();
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
