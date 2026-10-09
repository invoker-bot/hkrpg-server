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

/** Original paid 4000301 formal/trial choice, actual payout and normal H2 restore; no client UI. */
public final class NativePaidMissionRaidProbe {
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

  private int credits;

  private void observe(Frame frame) {
    if (frame.command() == protocol.clientCommand("PlayerSyncScNotify"))
      try {
        var value = protocol.clientSchema().parse("PlayerSyncScNotify", frame.body());
        var basic = value.getDescriptorForType().findFieldByName("basic_info");
        if (value.hasField(basic))
          credits = number((DynamicMessage) value.getField(basic), "scoin");
      } catch (Exception ex) {
        throw new IllegalStateException("Invalid basic info notify", ex);
      }
  }

  private void start(boolean formal) throws Exception {
    var fields = new HashMap<String, Object>();
    fields.put("raid_id", 4000301);
    if (!formal) {
      var request = protocol.clientSchema().message("StartRaidCsReq");
      var list = request.findFieldByName("NIMDEPFJJNI");
      var member = DynamicMessage.newBuilder(list.getMessageType());
      var type = member.getDescriptorForType().findFieldByName("avatar_type");
      member.setField(member.getDescriptorForType().findFieldByName("id"), 1101006);
      member.setField(type, type.getEnumType().findValueByName("AvatarType_AvatarTrialType"));
      fields.put("NIMDEPFJJNI", List.of(member.build()));
    }
    call("StartRaidCsReq", fields);
  }

  private Map<String, Object> execute(JsonObject credentials) throws Exception {
    String mode = credentials.get("mode").getAsString();
    boolean formal = credentials.get("formal").getAsBoolean();
    require(
        Set.of(
                "pending",
                "entry",
                "source",
                "active",
                "pause",
                "return",
                "resume",
                "finished",
                "leaveFinished",
                "final")
            .contains(mode),
        "Unknown paid raid operation");
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
    credits = number(basic, "scoin");
    require(number(basic, "hcoin") == 0, "Unexpected jade reward");
    call("PlayerLoginFinishCsReq");
    if (Set.of("entry", "return").contains(mode)) start(formal);
    if (Set.of("pause", "leaveFinished").contains(mode))
      call("LeaveRaidCsReq", Map.of("raid_id", 4000301));
    boolean finished = Set.of("finished", "leaveFinished", "final").contains(mode);
    boolean inside =
        Set.of("entry", "source", "active", "return", "resume", "finished").contains(mode);
    boolean scripted = formal && Set.of("source", "active", "return", "resume").contains(mode);
    var expected =
        mode.equals("pending")
            ? List.of(8001)
            : !inside
                ? List.of(formal ? 1006 : 8001)
                : scripted ? List.of(1006, 1101006) : List.of(formal ? 1006 : 1101006);
    var lineup = (DynamicMessage) field(call("GetCurLineupDataCsReq"), "lineup");
    var ids = new ArrayList<Integer>();
    for (var raw : (List<?>) field(lineup, "avatar_list")) {
      var member = (DynamicMessage) raw;
      int id = number(member, "id");
      ids.add(id);
      boolean trial = id == 1101006;
      require(
          ((Descriptors.EnumValueDescriptor) field(member, "avatar_type"))
              .getName()
              .contains(trial ? "Trial" : "Formal"),
          "Actor type changed");
      require(
          number(member, "hp") == 10000
              && number((DynamicMessage) field(member, "sp_bar"), "cur_sp") == (trial ? 7000 : 0),
          "Paid raid vitals changed");
    }
    require(ids.equals(expected), "Paid raid roster differs: " + ids);
    require(
        number(lineup, "mp") == 5
            && number(lineup, "leader_slot") == 0
            && !(Boolean) field(lineup, "_is_virtual"),
        "Paid raid party state changed");
    var all = call("GetAllLineupDataCsReq");
    int found = 0;
    for (var raw : (List<?>) field(all, "lineup_list")) {
      var team = (DynamicMessage) raw;
      if (number(team, "index") == number(lineup, "index")) {
        require(team.equals(lineup), "All lineup differs");
        found++;
      }
    }
    require(found == 1, "Current lineup missing/duplicated");
    var details = call("GetLineupAvatarDataCsReq");
    var detailIds = new ArrayList<Integer>();
    for (var raw : (List<?>) field(details, "avatar_data_list"))
      detailIds.add(number((DynamicMessage) raw, "id"));
    require(detailIds.equals(ids), "Actor details differ");
    var scene = (DynamicMessage) field(call("GetCurSceneInfoCsReq"), "scene");
    var actors = new ArrayList<Integer>();
    var entities = new HashSet<Integer>();
    for (var rawGroup : (List<?>) field(scene, "entity_group_list"))
      for (var raw : (List<?>) field((DynamicMessage) rawGroup, "entity_list")) {
        var entity = (DynamicMessage) raw;
        var descriptor = entity.getDescriptorForType().findFieldByName("actor");
        if (descriptor != null && entity.hasField(descriptor)) {
          actors.add(number((DynamicMessage) field(entity, "actor"), "base_avatar_id"));
          require(entities.add(number(entity, "entity_id")), "Duplicate actor entity");
        }
      }
    Collections.sort(actors);
    var expectedActors = new ArrayList<Integer>();
    for (int id : expected) expectedActors.add(id == 1101006 ? 1006 : id);
    Collections.sort(expectedActors);
    require(actors.equals(expectedActors), "Actual scene actor count differs: " + actors);
    int entry = number(scene, "entry_id");
    if (!mode.equals("pending"))
      require(entry == (inside ? 4000301 : 2000101), "Paid raid location changed");
    if (inside)
      require(
          number(scene, "plane_id") == 40003 && number(scene, "floor_id") == 40003001,
          "Wrong original raid floor");
    var status =
        call(
            "GetMissionStatusCsReq",
            Map.of(
                "main_mission_id_list",
                List.of(2000702, 2000703),
                "sub_mission_id_list",
                List.of(200070301, 200070317, 200070318)));
    var mains = new TreeSet<Integer>();
    for (var raw : (List<?>) field(status, "finished_main_mission_id_list"))
      mains.add((Integer) raw);
    require(mains.contains(2000703) == finished, "Main completion differs");
    require(!mains.contains(2000702), "Parent fixture was completed");
    var subs = new TreeSet<Integer>();
    for (var raw : (List<?>) field(status, "sub_mission_status_list")) {
      var sub = (DynamicMessage) raw;
      if (((Descriptors.EnumValueDescriptor) field(sub, "status"))
          .getName()
          .equals("MissionStatus_MissionFinish")) subs.add(number(sub, "id"));
    }
    boolean created = !Set.of("pending", "entry").contains(mode);
    require(subs.contains(200070318) == created, "Accepted original create objective differs");
    require(subs.contains(200070317) == finished, "Original final objective differs");
    boolean raidFinished = false;
    for (var raw : (List<?>) field(call("GetRaidInfoCsReq"), "finished_raid_info_list"))
      if (number((DynamicMessage) raw, "raid_id") == 4000301) raidFinished = true;
    require(raidFinished == finished, "Premature or missing raid payout");
    var bag = call("GetBagCsReq");
    var materials = new TreeMap<Integer, Integer>();
    for (var raw : (List<?>) field(bag, "material_list")) {
      var item = (DynamicMessage) raw;
      int id = number(item, "tid");
      if (id == 213 || id == 223) materials.put(id, number(item, "num"));
    }
    require(
        materials.equals(finished ? Map.of(213, 3, 223, 3) : Map.of()),
        "Real materials were not granted exactly once: " + materials);
    require(lineup.equals(field(call("GetCurLineupDataCsReq"), "lineup")), "Queries mutated party");
    require(credits == (finished ? 30000 : 0), "Actual credits differ: " + credits);
    var result = new LinkedHashMap<String, Object>();
    result.put("passed", true);
    result.put("mode", mode);
    result.put("formal", formal);
    result.put("uid", uid);
    result.put("requests", requests);
    result.put("party", ids);
    result.put("sceneActors", actors);
    result.put("entry", entry);
    result.put("credits", credits);
    result.put("materials", materials);
    result.put("raidFinished", raidFinished);
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
    var probe = new NativePaidMissionRaidProbe();
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
