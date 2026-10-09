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

/** Actual source 100010110 actor publication, reconnect and normal H2 restore; no client UI. */
public final class NativeMissionTrialProbe {
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
    boolean active = Set.of("active", "resume").contains(mode);
    boolean removed = Set.of("removed", "removedResume").contains(mode);
    require(
        Set.of("pending", "active", "resume", "closed", "removed", "removedResume").contains(mode),
        "Invalid mode");
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
        "Trial task granted unrelated currency");
    call("PlayerLoginFinishCsReq");
    var lineup = (DynamicMessage) field(call("GetCurLineupDataCsReq"), "lineup");
    var ids = new ArrayList<Integer>();
    int trialHP = 0, trialSP = 0;
    for (var raw : (List<?>) field(lineup, "avatar_list")) {
      var avatar = (DynamicMessage) raw;
      int id = number(avatar, "id");
      ids.add(id);
      var kind = (Descriptors.EnumValueDescriptor) field(avatar, "avatar_type");
      if (id == 1001006) {
        require(kind.getName().contains("Trial"), "Source trial was published as a formal role");
        trialHP = number(avatar, "hp");
        trialSP = number((DynamicMessage) field(avatar, "sp_bar"), "cur_sp");
        require(trialHP == 10000 && trialSP == 7000, "Frozen source trial vitals differ");
      } else
        require(id == 8001 && kind.getName().contains("Formal"), "Unexpected formal party member");
    }
    require(
        ids.equals(active ? List.of(8001, 1001006) : List.of(8001)),
        "Actual mission party differs");
    require(
        !(Boolean) field(lineup, "_is_virtual") && number(lineup, "mp") == 5,
        "Ordinary source task changed virtual flag or MP");
    require(
        lineup.equals(field(call("GetCurLineupDataCsReq"), "lineup")),
        "Query recreated or reset the trial");
    var all = call("GetAllLineupDataCsReq");
    require(
        number(all, "cur_index") == number(lineup, "index"),
        "All-party query selected another team");
    boolean found = false;
    for (var raw : (List<?>) field(all, "lineup_list")) {
      var party = (DynamicMessage) raw;
      if (number(party, "index") == number(lineup, "index")) {
        require(!found && party.equals(lineup), "Current and all-party query disagree");
        found = true;
      }
    }
    require(found, "All-party query omitted the mission party");
    var data = call("GetLineupAvatarDataCsReq");
    var detailIDs = new ArrayList<Integer>();
    for (var raw : (List<?>) field(data, "avatar_data_list"))
      detailIDs.add(number((DynamicMessage) raw, "id"));
    require(detailIDs.equals(ids), "Actor detail query omitted the mission trial");
    var scene = (DynamicMessage) field(call("GetCurSceneInfoCsReq"), "scene");
    var actorIDs = new TreeSet<Integer>();
    int replacementNpcs = 0;
    for (var rawGroup : (List<?>) field(scene, "entity_group_list"))
      for (var raw : (List<?>) field((DynamicMessage) rawGroup, "entity_list")) {
        var entity = (DynamicMessage) raw;
        var f = entity.getDescriptorForType().findFieldByName("actor");
        if (f != null && entity.hasField(f))
          actorIDs.add(number((DynamicMessage) field(entity, "actor"), "base_avatar_id"));
        var npcField = entity.getDescriptorForType().findFieldByName("npc");
        if (npcField != null
            && entity.hasField(npcField)
            && number(entity, "group_id") == 27
            && number(entity, "inst_id") == 400003) {
          require(
              number((DynamicMessage) field(entity, "npc"), "npc_id") == 1006,
              "Replacement NPC identity differs");
          replacementNpcs++;
        }
      }
    require(
        actorIDs.equals(active ? Set.of(8001, 7006) : Set.of(8001)),
        "Scene and mission party use different actors");
    require(
        replacementNpcs == (removed ? 1 : 0),
        "Script-only NPC was missing, duplicated or published early");
    if (active || mode.equals("closed") || removed) {
      require(
          number(scene, "plane_id") == 20001
              && number(scene, "floor_id") == 20001001
              && number(scene, "entry_id") == 2000101,
          "Original mission floor changed");
      var status = call("GetMissionStatusCsReq", Map.of("sub_mission_id_list", List.of(100010110)));
      var subs = (List<?>) field(status, "sub_mission_status_list");
      require(subs.size() == 1, "Missing actual mission status");
      var sub = (DynamicMessage) subs.get(0);
      require(
          number(sub, "id") == 100010110
              && number(sub, "progress") == 1
              && ((Descriptors.EnumValueDescriptor) field(sub, "status"))
                  .getName()
                  .equals("MissionStatus_MissionFinish"),
          "Actual trial publication did not complete its condition");
    }
    if (removed) {
      var status =
          call(
              "GetMissionStatusCsReq",
              Map.of("sub_mission_id_list", List.of(100010123, 100010117)));
      var statuses = new HashMap<Integer, DynamicMessage>();
      for (var raw : (List<?>) field(status, "sub_mission_status_list")) {
        var row = (DynamicMessage) raw;
        require(statuses.put(number(row, "id"), row) == null, "Duplicate removal status");
      }
      require(
          statuses.keySet().equals(Set.of(100010123, 100010117)),
          "Missing removal or successor status");
      require(
          ((Descriptors.EnumValueDescriptor) field(statuses.get(100010123), "status"))
              .getName()
              .equals("MissionStatus_MissionFinish"),
          "Removal did not finish its actual goal");
      require(
          ((Descriptors.EnumValueDescriptor) field(statuses.get(100010117), "status"))
              .getName()
              .equals("MissionStatus_MissionDoing"),
          "Original successor was not accepted");
    }
    require(
        lineup.equals(field(call("GetCurLineupDataCsReq"), "lineup")),
        "Mission query changed the actor party");
    return Map.of(
        "passed",
        true,
        "uid",
        uid,
        "mode",
        mode,
        "party",
        ids,
        "sceneActors",
        actorIDs,
        "requests",
        requests,
        "trialHP",
        trialHP,
        "trialSP",
        trialSP,
        "credits",
        0,
        "clientGameplayVerified",
        false);
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
          number(m, "retcode") == 0, "Unexpected " + response + " status=" + number(m, "retcode"));
    return m;
  }

  private DynamicMessage call(String name) throws Exception {
    return call(name, Map.of());
  }

  public static void main(String[] args) {
    var probe = new NativeMissionTrialProbe();
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
