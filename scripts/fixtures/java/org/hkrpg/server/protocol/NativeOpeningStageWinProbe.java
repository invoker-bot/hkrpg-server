package org.hkrpg.server.protocol;

import com.google.gson.*;
import com.google.protobuf.*;
import emu.lunarcore.server.packet.PacketCodec;
import io.netty.buffer.*;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import kcp.highway.*;

/** Fresh opening party, tutorial progress and first victory over KCP; no seeded player state. */
public final class NativeOpeningStageWinProbe {
  private record Frame(int command, byte[] body) {}

  private final ProtocolBridge protocol = ProtocolBridge.forVersion("4.6.0");
  private final BlockingQueue<Frame> inbox = new LinkedBlockingQueue<>();
  private final AtomicReference<Throwable> failure = new AtomicReference<>();
  private final CompletableFuture<Ukcp> connected = new CompletableFuture<>();
  private Ukcp channel;
  private int requests;

  private static Object field(DynamicMessage message, String name) {
    return message.getField(
        Objects.requireNonNull(message.getDescriptorForType().findFieldByName(name), name));
  }

  private static void require(boolean condition, String message) {
    if (!condition) throw new IllegalStateException(message);
  }

  private DynamicMessage call(String name, Map<String, Object> fields, boolean success)
      throws Exception {
    var request = DynamicMessage.newBuilder(protocol.clientSchema().message(name));
    fields.forEach(
        (key, value) ->
            request.setField(request.getDescriptorForType().findFieldByName(key), value));
    var packet =
        Unpooled.wrappedBuffer(
            PacketCodec.encode(
                protocol.clientCommand(name), new byte[0], request.build().toByteArray()));
    try {
      channel.write(packet);
    } finally {
      packet.release();
    }
    requests++;
    var response = name.replace("CsReq", "ScRsp");
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
    while (System.nanoTime() < deadline) {
      if (failure.get() != null) throw new IllegalStateException("KCP failure", failure.get());
      var frame = inbox.poll(1, TimeUnit.SECONDS);
      if (frame == null || frame.command() != protocol.clientCommand(response)) continue;
      var result = protocol.clientSchema().parse(response, frame.body());
      require(result.getUnknownFields().asMap().isEmpty(), "Unknown response fields: " + response);
      require(
          ((Integer) field(result, "retcode") == 0) == success,
          "Unexpected status: " + response + " code=" + field(result, "retcode"));
      return result;
    }
    throw new IllegalStateException("Response timeout: " + response);
  }

  private DynamicMessage call(String name) throws Exception {
    return call(name, Map.of(), true);
  }

  private void progress(boolean finished) throws Exception {
    var data = call("GetMissionDataCsReq");
    var missions = new HashMap<Integer, DynamicMessage>();
    for (var row : (List<?>) field(data, "mission_list")) {
      var mission = (DynamicMessage) row;
      missions.put((Integer) field(mission, "id"), mission);
    }
    var battle = missions.get(100010103);
    require(
        battle != null && (Integer) field(battle, "progress") == (finished ? 1 : 0),
        "Opening battle progress");
    require(
        ((Descriptors.EnumValueDescriptor) field(battle, "status")).getNumber()
            == (finished ? 2 : 1),
        "Opening battle status");
    require(missions.containsKey(100010105) == finished, "Next opening objective");
  }

  private void openingParty() throws Exception {
    var team = (DynamicMessage) field(call("GetCurLineupDataCsReq"), "lineup");
    var avatars = (List<?>) field(team, "avatar_list");
    require(avatars.size() == 1, "Opening must contain only Kafka");
    var kafka = (DynamicMessage) avatars.getFirst();
    require((Integer) field(kafka, "id") == 1001005, "Opening Kafka special-avatar ID");
    require(
        ((Descriptors.EnumValueDescriptor) field(kafka, "avatar_type")).getNumber() == 1,
        "Opening actor must remain a trial");
    var owned =
        (List<?>)
            field(call("GetAvatarDataCsReq", Map.of("is_get_all", true), true), "avatar_list");
    require(
        owned.size() == 1
            && (Integer) field((DynamicMessage) owned.getFirst(), "base_avatar_id") == 8001,
        "Opening trials must not be granted as formal avatars");
  }

  private void tutorialProgress(boolean finished) throws Exception {
    var tutorials = (List<?>) field(call("GetTutorialCsReq"), "tutorial_list");
    var guides = (List<?>) field(call("GetTutorialGuideCsReq"), "tutorial_guide_list");
    if (!finished) {
      require(
          tutorials.isEmpty() && guides.isEmpty(), "Fresh tutorials were fabricated as completed");
    } else {
      require(
          tutorials.size() == 1 && guides.isEmpty(),
          "Tutorial progress must survive reconnect/restart");
      var item = (DynamicMessage) tutorials.getFirst();
      require(
          (Integer) field(item, "id") == 1001
              && ((Descriptors.EnumValueDescriptor) field(item, "status")).getNumber() == 2,
          "Only the acknowledged opening tutorial should be finished");
    }
  }

  private void openingScene() throws Exception {
    var scene = (DynamicMessage) field(call("GetCurSceneInfoCsReq"), "scene");
    require(
        (Integer) field(scene, "entry_id") == 2000101
            && (Integer) field(scene, "floor_id") == 20001001,
        "Actual initial scene");
    boolean kafkaPresent = false;
    boolean doorEffects = false;
    boolean openingProps = false;
    for (var raw : (List<?>) field(scene, "entity_group_list")) {
      var group = (DynamicMessage) raw;
      int id = (Integer) field(group, "group_id");
      require(id != 93 && id != 6, "Opening scene contains locked world group " + id);
      doorEffects |= id == 52;
      openingProps |= id == 88;
      for (var value : (List<?>) field(group, "entity_list")) {
        var entity = (DynamicMessage) value;
        var actorField = entity.getDescriptorForType().findFieldByName("actor");
        if (!entity.hasField(actorField)) continue;
        var actor = (DynamicMessage) entity.getField(actorField);
        require(
            (Integer) field(actor, "base_avatar_id") == 7005,
            "Opening scene must use the Kafka actor");
        kafkaPresent = true;
      }
    }
    require(kafkaPresent, "Opening scene Kafka actor is absent");
    require(doorEffects && openingProps, "Opening scene lost its current story props");
  }

  private Map<String, Object> execute(JsonObject control) throws Exception {
    int uid =
        (Integer)
            field(
                call(
                    "PlayerGetTokenCsReq",
                    Map.of(
                        "account_uid",
                        control.get("accountUid").getAsString(),
                        "token",
                        control.get("comboToken").getAsString(),
                        "platform",
                        1),
                    true),
                "uid");
    call("PlayerLoginCsReq");
    call("PlayerLoginFinishCsReq");
    openingParty();
    openingScene();
    boolean first = control.get("phase").getAsString().equals("first");
    if (first) {
      tutorialProgress(false);
      var missions = (List<?>) field(call("GetMissionDataCsReq"), "mission_list");
      var intro =
          missions.stream()
              .map(row -> (DynamicMessage) row)
              .filter(row -> (Integer) field(row, "id") == 100010102)
              .findFirst()
              .orElseThrow();
      require(
          (Integer) field(intro, "progress") == 0
              && ((Descriptors.EnumValueDescriptor) field(intro, "status")).getNumber() == 1,
          "Opening intro must wait for the client performance");
      for (int id : new int[] {100010102, 100010100, 100010104})
        call(
            "FinishTalkMissionCsReq",
            Map.of("sub_mission_id", id, "talk_str", "Mission_" + id),
            true);
      progress(false);
      var entered = call("SceneEnterStageCsReq", Map.of("event_id", 20001001), true);
      var battle = (DynamicMessage) field(entered, "battle_info");
      require((Integer) field(battle, "stage_id") == 200010010, "Original world-zero stage");
      var combatants = (List<?>) field(battle, "battle_avatar_list");
      require(combatants.size() == 1, "Opening battle must use Kafka only");
      var kafka = (DynamicMessage) combatants.getFirst();
      require(
          (Integer) field(kafka, "id") == 1001005 && (Integer) field(kafka, "level") == 10,
          "Opening battle must use the original level-10 Kafka trial");
      require(
          !((List<?>) field(battle, "monster_wave_list")).isEmpty(), "Original monsters are empty");
      var end =
          protocol.clientSchema().message("PVEBattleResultCsReq").findFieldByName("end_status");
      var report =
          Map.<String, Object>of(
              "battle_id",
              field(battle, "battle_id"),
              "stage_id",
              field(battle, "stage_id"),
              "end_status",
              end.getEnumType().findValueByName("BATTLE_END_WIN"));
      call("PVEBattleResultCsReq", report, true);
      progress(true);
      call("PVEBattleResultCsReq", report, false);
      call("UnlockTutorialCsReq", Map.of("HPIMDINCCNN", 1001), true);
      call("FinishTutorialCsReq", Map.of("tutorial_id", 1001), true);
    }
    progress(true);
    tutorialProgress(true);
    return Map.of(
        "passed",
        true,
        "uid",
        uid,
        "phase",
        control.get("phase").getAsString(),
        "requests",
        requests,
        "openingBattleFinished",
        true,
        "nextObjectiveAccepted",
        true,
        "openingKafkaVerified",
        true,
        "tutorialProgressVerified",
        true,
        "openingSceneGatesVerified",
        true,
        "clientGameplayVerified",
        false);
  }

  public static void main(String[] args) {
    var probe = new NativeOpeningStageWinProbe();
    var client = new KcpClient();
    int exit = 1;
    try {
      var control =
          JsonParser.parseReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))
              .getAsJsonObject();
      var config = new ChannelConfig();
      config.nodelay(true, 40, 2, true);
      config.setMtu(1200);
      config.setSndwnd(256);
      config.setRcvwnd(256);
      config.setUseConvChannel(true);
      config.setConv(ThreadLocalRandom.current().nextInt(1, Integer.MAX_VALUE));
      config.setTimeoutMillis(40000);
      config.setAckNoDelay(true);
      client.init(
          config,
          new KcpListener() {
            public void onConnected(Ukcp connection) {
              probe.connected.complete(connection);
            }

            public void handleReceive(ByteBuf bytes, Ukcp connection) {
              try {
                while (bytes.isReadable()) {
                  var frame = PacketCodec.decodeNext(bytes);
                  require(frame != null, "Truncated opening frame");
                  probe.inbox.add(new Frame(frame.cmdId(), frame.body()));
                }
              } catch (Throwable error) {
                probe.failure.set(error);
              }
            }

            public void handleException(Throwable error, Ukcp connection) {
              probe.failure.set(error);
              probe.connected.completeExceptionally(error);
            }

            public void handleClose(Ukcp connection) {
              probe.failure.compareAndSet(
                  null, new IllegalStateException("Opening probe disconnected"));
            }
          });
      client.connect(new InetSocketAddress("127.0.0.1", Integer.parseInt(args[0])), config);
      probe.channel = probe.connected.get(15, TimeUnit.SECONDS);
      System.out.println(new Gson().toJson(probe.execute(control)));
      exit = 0;
    } catch (Exception error) {
      error.printStackTrace(System.err);
    } finally {
      client.stop();
    }
    System.exit(exit);
  }
}
