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

/** Original 40231001 stage gates, transfers, replacement and normal H2 restore; no client UI. */
public final class NativeMissionRaidSequenceProbe {
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
        Set.of(
                "pending",
                "first",
                "second",
                "active",
                "pause",
                "return",
                "resume",
                "finished",
                "leaveFinished",
                "final")
            .contains(mode),
        "Unknown sequence operation");
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
        number(basic, "hcoin") == 0 && number(basic, "scoin") == 0, "Unexpected sequence reward");
    call("PlayerLoginFinishCsReq");
    if (mode.equals("first") || mode.equals("return"))
      call("StartRaidCsReq", Map.of("raid_id", 40231001));
    if (mode.equals("pause") || mode.equals("leaveFinished"))
      call("LeaveRaidCsReq", Map.of("raid_id", 40231001));
    boolean dynamic = Set.of("second", "active", "return", "resume").contains(mode);
    boolean complete = Set.of("finished", "leaveFinished", "final").contains(mode);
    boolean inside = dynamic || mode.equals("first") || mode.equals("finished");
    var expected = dynamic ? List.of(1021213) : inside ? List.of(1021002) : List.of(8001);
    var lineup = (DynamicMessage) field(call("GetCurLineupDataCsReq"), "lineup");
    var ids = new ArrayList<Integer>();
    for (var raw : (List<?>) field(lineup, "avatar_list")) {
      var member = (DynamicMessage) raw;
      ids.add(number(member, "id"));
      var kind = ((Descriptors.EnumValueDescriptor) field(member, "avatar_type")).getName();
      require(kind.contains(inside ? "Trial" : "Formal"), "Sequence actor type changed");
      require(
          number(member, "hp") == 10000
              && number((DynamicMessage) field(member, "sp_bar"), "cur_sp") == (inside ? 7000 : 0),
          "Sequence vitals changed");
    }
    require(ids.equals(expected), "Original replacement/retirement failed: " + ids);
    require(
        number(lineup, "mp") == 5
            && number(lineup, "leader_slot") == 0
            && !(Boolean) field(lineup, "_is_virtual"),
        "Sequence lineup changed");
    var all = call("GetAllLineupDataCsReq");
    int found = 0;
    for (var raw : (List<?>) field(all, "lineup_list")) {
      var item = (DynamicMessage) raw;
      if (number(item, "index") == number(lineup, "index")) {
        require(item.equals(lineup), "All lineup differs");
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
    var actors = new TreeSet<Integer>();
    for (var rawGroup : (List<?>) field(scene, "entity_group_list"))
      for (var raw : (List<?>) field((DynamicMessage) rawGroup, "entity_list")) {
        var entity = (DynamicMessage) raw;
        var descriptor = entity.getDescriptorForType().findFieldByName("actor");
        if (descriptor != null && entity.hasField(descriptor))
          actors.add(number((DynamicMessage) field(entity, "actor"), "base_avatar_id"));
      }
    require(
        actors.equals(
            dynamic
                ? Set.of(credentials.get("trialActor").getAsInt())
                : inside ? Set.of(1002) : Set.of(8001)),
        "Scene actors differ: " + actors);
    int entry = number(scene, "entry_id");
    if (mode.equals("first"))
      require(
          entry == 40231004
              && number(scene, "plane_id") == 40231
              && number(scene, "floor_id") == 40231004,
          "First actual transfer missing");
    else if (inside)
      require(
          entry == 40231003
              && number(scene, "plane_id") == 40231
              && number(scene, "floor_id") == 40231003,
          "Second actual transfer missing");
    else if (mode.equals("pause")) require(entry == 2000101, "Pause home changed");
    else if (complete) require(entry == 2023202, "Finished return changed");
    var status =
        call(
            "GetMissionStatusCsReq",
            Map.of(
                "main_mission_id_list",
                List.of(1021301, 1021401, 1021501),
                "sub_mission_id_list",
                List.of(102130113, 102140141, 102140142, 102140143)));
    var finishedMains = new TreeSet<Integer>();
    for (var raw : (List<?>) field(status, "finished_main_mission_id_list"))
      finishedMains.add((Integer) raw);
    require(!finishedMains.contains(1021501), "After mission was automatically granted");
    require(
        finishedMains.contains(1021301) == (dynamic || mode.equals("pause") || complete),
        "First main completion differs");
    require(finishedMains.contains(1021401) == complete, "Second main completion differs");
    var finishedSubs = new TreeSet<Integer>();
    for (var raw : (List<?>) field(status, "sub_mission_status_list")) {
      var item = (DynamicMessage) raw;
      int id = number(item, "id");
      if (((Descriptors.EnumValueDescriptor) field(item, "status"))
          .getName()
          .equals("MissionStatus_MissionFinish")) {
        require(number(item, "progress") == 1, "Invalid source completion progress");
        finishedSubs.add(id);
      }
    }
    require(!finishedSubs.contains(102140143), "Unaccepted optional role was created");
    if (!mode.equals("pending"))
      require(finishedSubs.contains(102130113), "First transfer objective lost");
    if (dynamic || mode.equals("pause") || complete)
      require(
          finishedSubs.containsAll(Set.of(102140141, 102140142)),
          "Second transfer/replacement objectives lost");
    var raidInfo = call("GetRaidInfoCsReq");
    boolean raidFinished = false;
    for (var raw : (List<?>) field(raidInfo, "finished_raid_info_list"))
      if (number((DynamicMessage) raw, "raid_id") == 40231001) raidFinished = true;
    require(raidFinished == complete, "First stage incorrectly completed entire raid");
    require(lineup.equals(field(call("GetCurLineupDataCsReq"), "lineup")), "Queries mutated party");
    var result = new LinkedHashMap<String, Object>();
    result.put("passed", true);
    result.put("mode", mode);
    result.put("uid", uid);
    result.put("raid", 40231001);
    result.put("requests", requests);
    result.put("party", ids);
    result.put("sceneActors", actors);
    result.put("entry", entry);
    result.put("finishedMains", finishedMains);
    result.put("finishedSubs", finishedSubs);
    result.put("raidFinished", raidFinished);
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
    var probe = new NativeMissionRaidSequenceProbe();
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
