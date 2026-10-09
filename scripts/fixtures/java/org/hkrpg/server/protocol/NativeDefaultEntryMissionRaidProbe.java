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

/** Original Default 43306, two stages, source virtual actor and normal H2 restore. */
public final class NativeDefaultEntryMissionRaidProbe {
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
    boolean adjustedWorld =
        credentials.has("worldOneDifficulty")
            || credentials.has("easyWorldOne") && credentials.get("easyWorldOne").getAsBoolean();
    int selectedDifficulty =
        credentials.has("worldOneDifficulty")
            ? credentials.get("worldOneDifficulty").getAsInt()
            : 1;
    require(
        !adjustedWorld || selectedDifficulty == 0 || selectedDifficulty == 1,
        "Unsupported fixture difficulty");
    boolean unselected =
        credentials.has("unselectedWorldOne")
            && credentials.get("unselectedWorldOne").getAsBoolean();
    require(
        !unselected || adjustedWorld && selectedDifficulty == 0,
        "Unselected fixture must use world-one Default");
    int actualWorld =
        credentials.has("raidDifficultyActualWorld")
            ? credentials.get("raidDifficultyActualWorld").getAsInt()
            : 1;
    require(!adjustedWorld || actualWorld >= 1 && actualWorld <= 6, "Unknown fixture world tier");
    int difficultyBattles = 0;
    require(
        Set.of(
                "pending",
                "first",
                "active",
                "resume",
                "second",
                "virtual",
                "pause",
                "return",
                "virtualResume",
                "finished",
                "leaveFinished",
                "final")
            .contains(mode),
        "Unknown Default operation");
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
        number(basic, "hcoin") == 0 && number(basic, "scoin") == 0, "Unexpected Default reward");
    call("PlayerLoginFinishCsReq");
    if (adjustedWorld) {
      require(
          number(basic, "world_level") == (mode.equals("pending") ? 0 : actualWorld),
          "Formal world was reinterpreted");
      if (mode.equals("first") && !unselected) {
        var selection = difficulty(selectedDifficulty);
        for (int repeat = 0; repeat < 2; repeat++) {
          var updated = call("DifficultyAdjustmentUpdateDataCsReq", Map.of("data", selection));
          require(
              field(updated, "data").equals(selection)
                  && field(updated, "content_package_list").equals(List.of(selection)),
              "Selected difficulty response changed");
        }
      }
    }
    if (mode.equals("first") || mode.equals("return"))
      call("StartRaidCsReq", Map.of("raid_id", 43306));
    if (mode.equals("pause") || mode.equals("leaveFinished"))
      call("LeaveRaidCsReq", Map.of("raid_id", 43306));
    if (adjustedWorld) {
      if (mode.equals("pause"))
        call(
            "DifficultyAdjustmentUpdateDataCsReq",
            Map.of("data", difficulty(1 - selectedDifficulty)));
      var choices = field(call("DifficultyAdjustmentGetDataCsReq"), "content_package_list");
      boolean switched =
          Set.of("pause", "return", "virtualResume", "finished", "leaveFinished", "final")
              .contains(mode);
      int selected =
          Set.of("pause", "return", "virtualResume", "finished", "leaveFinished", "final")
                  .contains(mode)
              ? 1 - selectedDifficulty
              : selectedDifficulty;
      require(
          choices.equals(
              mode.equals("pending") || unselected && !switched
                  ? List.of()
                  : List.of(difficulty(selected))),
          "Saved difficulty preference did not restore");
      if (mode.equals("first"))
        call(
            "DifficultyAdjustmentUpdateDataCsReq",
            Map.of("data", difficulty(1 - selectedDifficulty)),
            Retcode.REQ_PARA_INVALID.getVal());
      if (Set.of("first", "resume").contains(mode)) {
        var started =
            (DynamicMessage)
                field(call("SceneEnterStageCsReq", Map.of("event_id", 20321050)), "battle_info");
        require(
            number(started, "stage_id") == 203210500 + actualWorld - selectedDifficulty
                && number(started, "world_level") == actualWorld - selectedDifficulty,
            "Event battle differs from the frozen selected difficulty");
        var resultType = protocol.clientSchema().message("PVEBattleResultCsReq");
        var end = resultType.findFieldByName("end_status");
        var quit =
            call(
                "PVEBattleResultCsReq",
                Map.of(
                    "battle_id",
                    number(started, "battle_id"),
                    "stage_id",
                    203210500 + actualWorld - selectedDifficulty,
                    "end_status",
                    end.getEnumType().findValueByName("BATTLE_END_QUIT")));
        require(
            number(quit, "battle_id") == number(started, "battle_id"),
            "Actual selected battle quit rejected");
        difficultyBattles++;
      }
    }
    boolean inside = !Set.of("pending", "pause", "leaveFinished", "final").contains(mode);
    boolean stageTwo =
        Set.of("second", "virtual", "return", "virtualResume", "finished").contains(mode);
    boolean virtual = Set.of("virtual", "return", "virtualResume").contains(mode);
    boolean complete = Set.of("finished", "leaveFinished", "final").contains(mode);
    var expected = virtual ? List.of(1021304) : List.of(8001);
    var scene = (DynamicMessage) field(call("GetCurSceneInfoCsReq"), "scene");
    var actors = new TreeSet<Integer>();
    for (var rawGroup : (List<?>) field(scene, "entity_group_list")) {
      for (var raw : (List<?>) field((DynamicMessage) rawGroup, "entity_list")) {
        var entity = (DynamicMessage) raw;
        var descriptor = entity.getDescriptorForType().findFieldByName("actor");
        if (descriptor != null && entity.hasField(descriptor))
          actors.add(number((DynamicMessage) field(entity, "actor"), "base_avatar_id"));
      }
    }
    require(
        actors.equals(virtual ? Set.of(1304) : Set.of(8001)),
        "Default scene actors changed: " + actors);
    int entry = number(scene, "entry_id");
    require(
        !inside || entry == (stageTwo ? 43306002 : 43306001),
        "Original Default stage entry changed");
    if (!inside && !mode.equals("pending"))
      require(
          entry == (complete ? 2031101 : 2000101),
          "Original Default pause/finished return changed");
    var lineup = (DynamicMessage) field(call("GetCurLineupDataCsReq"), "lineup");
    var ids = new ArrayList<Integer>();
    for (var raw : (List<?>) field(lineup, "avatar_list")) {
      var member = (DynamicMessage) raw;
      ids.add(number(member, "id"));
      var kind = ((Descriptors.EnumValueDescriptor) field(member, "avatar_type")).getName();
      require(kind.contains(virtual ? "Trial" : "Formal"), "Default actor type changed");
      require(
          number(member, "hp") == 10000
              && number((DynamicMessage) field(member, "sp_bar"), "cur_sp") == (virtual ? 7000 : 0),
          "Default vitals changed");
    }
    require(ids.equals(expected), "Default party differs: " + ids);
    require(
        number(lineup, "mp") == (mode.equals("pending") ? 5 : 2)
            && (Boolean) field(lineup, "_is_virtual") == virtual,
        "Default/formal points or virtual flag changed");
    var all = call("GetAllLineupDataCsReq");
    int found = 0;
    for (var raw : (List<?>) field(all, "lineup_list")) {
      var item = (DynamicMessage) raw;
      if (number(item, "index") == number(lineup, "index")) {
        require(item.equals(lineup), "All Default lineup differs");
        found++;
      }
    }
    require(found == 1, "Current Default lineup missing/duplicated");
    var details = call("GetLineupAvatarDataCsReq");
    var detailIds = new ArrayList<Integer>();
    for (var raw : (List<?>) field(details, "avatar_data_list"))
      detailIds.add(number((DynamicMessage) raw, "id"));
    require(detailIds.equals(ids), "Default actor details differ");
    var status =
        call(
            "GetMissionStatusCsReq",
            Map.of(
                "main_mission_id_list",
                List.of(1032201, 1031901),
                "sub_mission_id_list",
                List.of(
                    103220101, 103220102, 103220103, 103220104, 103220105, 103190102, 103190112,
                    103190106)));
    var finishedMains = new TreeSet<Integer>();
    for (var raw : (List<?>) field(status, "finished_main_mission_id_list"))
      finishedMains.add((Integer) raw);
    require(
        finishedMains.contains(1032201) == (stageTwo || mode.equals("pause") || complete),
        "First Default stage completion changed");
    require(finishedMains.contains(1031901) == complete, "Default final stage completion changed");
    var finishedSubs = new TreeSet<Integer>();
    for (var raw : (List<?>) field(status, "sub_mission_status_list")) {
      var item = (DynamicMessage) raw;
      if (((Descriptors.EnumValueDescriptor) field(item, "status"))
          .getName()
          .equals("MissionStatus_MissionFinish")) {
        require(number(item, "progress") == 1, "Invalid Default progress");
        finishedSubs.add(number(item, "id"));
      }
    }
    require(
        finishedSubs.contains(103190112) == (virtual || mode.equals("pause") || complete),
        "Original virtual source dependency differs");
    var raidInfo = call("GetRaidInfoCsReq");
    boolean raidFinished = false;
    for (var raw : (List<?>) field(raidInfo, "finished_raid_info_list"))
      if (number((DynamicMessage) raw, "raid_id") == 43306) raidFinished = true;
    require(raidFinished == complete, "Default stage incorrectly finished entire raid");
    require(
        lineup.equals(field(call("GetCurLineupDataCsReq"), "lineup")),
        "Queries changed Default points");
    var result = new LinkedHashMap<String, Object>();
    result.put("passed", true);
    result.put("mode", mode);
    result.put("uid", uid);
    result.put("raid", 43306);
    result.put("requests", requests);
    result.put("party", ids);
    result.put("sceneActors", actors);
    result.put("entry", entry);
    result.put("mp", number(lineup, "mp"));
    result.put("virtual", virtual);
    result.put("finishedMains", finishedMains);
    result.put("finishedSubs", finishedSubs);
    result.put("raidFinished", raidFinished);
    result.put("credits", number(basic, "scoin"));
    result.put("actualWorld", number(basic, "world_level"));
    result.put("difficultyBattles", difficultyBattles);
    result.put("easyBattles", selectedDifficulty == 1 ? difficultyBattles : 0);
    result.put("selectedDifficulty", adjustedWorld ? selectedDifficulty : -1);
    result.put(
        "effectiveBattleWorld", difficultyBattles > 0 ? actualWorld - selectedDifficulty : -1);
    result.put(
        "battleStage", difficultyBattles > 0 ? 203210500 + actualWorld - selectedDifficulty : 0);
    result.put("initialDifficultySelectionOmitted", unselected);
    result.put("nativeDifficultyWorldOneFixture", adjustedWorld && actualWorld == 1);
    result.put("fixtureActualWorld", adjustedWorld ? actualWorld : 0);
    result.put(
        "nativeEasyWorldFixture", adjustedWorld && actualWorld == 1 && selectedDifficulty == 1);
    result.put("clientGameplayVerified", false);
    return result;
  }

  private DynamicMessage difficulty(int value) {
    var b = DynamicMessage.newBuilder(protocol.clientSchema().message("AMKBFMCOIHK"));
    b.setField(b.getDescriptorForType().findFieldByName("id"), 43306);
    var source = b.getDescriptorForType().findFieldByName("source_type");
    var difficulty = b.getDescriptorForType().findFieldByName("ANJMFPKJHOH");
    b.setField(source, source.getEnumType().findValueByNumber(1));
    b.setField(difficulty, difficulty.getEnumType().findValueByNumber(value));
    return b.build();
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
    var probe = new NativeDefaultEntryMissionRaidProbe();
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
