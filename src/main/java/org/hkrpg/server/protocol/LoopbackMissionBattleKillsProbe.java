package org.hkrpg.server.protocol;

import com.google.gson.*;
import com.google.protobuf.DynamicMessage;
import emu.lunarcore.server.packet.PacketCodec;
import io.netty.buffer.*;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import kcp.highway.*;

/** Actual 305001 event/800204001 monster with explicit isolated acceptance gates. */
public final class LoopbackMissionBattleKillsProbe {
  private static final int MAIN = 8000181, SUB = 800018102, EVENT = 305001, MONSTER = 800204001;
  private final ProtocolBridge protocol = ProtocolBridge.forVersion("4.6.0");
  private final CompletableFuture<Map<String, Object>> completed = new CompletableFuture<>();
  private final Map<String, Object> result = new LinkedHashMap<>();
  private boolean resume;
  private int step, battleId, stageId;

  private static Object field(DynamicMessage m, String name) {
    return m.getField(m.getDescriptorForType().findFieldByName(name));
  }

  private static void require(boolean ok, String reason) {
    if (!ok) throw new IllegalStateException(reason);
  }

  private DynamicMessage.Builder request(String name) {
    return DynamicMessage.newBuilder(protocol.clientSchema().message(name));
  }

  private static void set(DynamicMessage.Builder b, String name, Object value) {
    b.setField(b.getDescriptorForType().findFieldByName(name), value);
  }

  private void send(Ukcp c, String name, DynamicMessage.Builder b) {
    System.err.println("Battle kill request=" + name + ", step=" + step);
    var packet =
        Unpooled.wrappedBuffer(
            PacketCodec.encode(protocol.clientCommand(name), new byte[0], b.build().toByteArray()));
    try {
      c.write(packet);
    } finally {
      packet.release();
    }
  }

  private void accept(Ukcp c) {
    var b = request("AcceptMainMissionCsReq");
    set(b, "main_mission_id", MAIN);
    send(c, "AcceptMainMissionCsReq", b);
  }

  private void query(Ukcp c) {
    send(c, "GetMissionDataCsReq", request("GetMissionDataCsReq"));
  }

  private void start(Ukcp c) {
    var b = request("SceneEnterStageCsReq");
    set(b, "event_id", EVENT);
    send(c, "SceneEnterStageCsReq", b);
  }

  private void finish(Ukcp c, int death, boolean wrongId, boolean wrongStage, boolean duplicate) {
    var b = request("PVEBattleResultCsReq");
    set(b, "battle_id", wrongId ? battleId + 1 : battleId);
    set(b, "stage_id", stageId);
    var end = b.getDescriptorForType().findFieldByName("end_status");
    b.setField(end, end.getEnumType().findValueByName("BATTLE_END_WIN"));
    var stats = request("BattleStatistics");
    var row = request("MonsterBattleInfo");
    set(row, "entity_id", 5001);
    set(row, "monster_id", MONSTER);
    set(row, "stage_id", wrongStage ? stageId + 1 : stageId);
    var cause = row.getDescriptorForType().findFieldByName("death_source");
    row.setField(cause, cause.getEnumType().findValueByNumberCreatingIfUnknown(death));
    var list = stats.getDescriptorForType().findFieldByName("monster_list");
    stats.addRepeatedField(list, row.build());
    if (duplicate) stats.addRepeatedField(list, row.build());
    set(b, "stt", stats.build());
    send(c, "PVEBattleResultCsReq", b);
  }

  private void progress(DynamicMessage m, int expected) {
    require((Integer) field(m, "retcode") == 0, "Mission query failed");
    var matches =
        ((List<?>) field(m, "mission_list"))
            .stream()
                .map(v -> (DynamicMessage) v)
                .filter(v -> (Integer) field(v, "id") == SUB)
                .toList();
    require(
        matches.size() == 1 && (Integer) field(matches.getFirst(), "progress") == expected,
        "Kill objective progress changed at " + step);
    require(
        ((List<?>) field(m, "finished_main_mission_id_list")).contains(MAIN) == (expected == 1),
        "Parent outcome changed");
    result.put("objectiveProgress", expected);
  }

  private void done() {
    result.put("passed", true);
    result.put("clientGameplayVerified", false);
    completed.complete(result);
  }

  private void receive(ByteBuf bytes, Ukcp c) throws Exception {
    while (bytes.isReadable()) {
      var frame = PacketCodec.decodeNext(bytes);
      require(frame != null, "Truncated kill frame");
      String name =
          List.of(
                  "PlayerGetTokenScRsp",
                  "PlayerLoginScRsp",
                  "PlayerLoginFinishScRsp",
                  "EnterSceneScRsp",
                  "AcceptMainMissionScRsp",
                  "GetMissionDataScRsp",
                  "SceneEnterStageScRsp",
                  "PVEBattleResultScRsp",
                  "GetCurBattleInfoScRsp")
              .stream()
              .filter(n -> protocol.clientCommand(n) == frame.cmdId())
              .findFirst()
              .orElse(null);
      if (name == null) continue;
      System.err.println("Battle kill response=" + name + ", step=" + step);
      var m = protocol.clientSchema().parse(name, frame.body());
      require(m.getUnknownFields().asMap().isEmpty(), "Unknown response " + name);
      switch (name) {
        case "PlayerGetTokenScRsp" -> {
          require((Integer) field(m, "retcode") == 0, "Token rejected");
          result.put("uid", field(m, "uid"));
          send(c, "PlayerLoginCsReq", request("PlayerLoginCsReq"));
        }
        case "PlayerLoginScRsp" -> {
          require((Integer) field(m, "retcode") == 0, "Login rejected");
          send(c, "PlayerLoginFinishCsReq", request("PlayerLoginFinishCsReq"));
        }
        case "PlayerLoginFinishScRsp" -> {
          require((Integer) field(m, "retcode") == 0, "Login finish rejected");
          if (resume) {
            step = 20;
            query(c);
          } else {
            var b = request("EnterSceneCsReq");
            set(b, "entry_id", 1010101);
            send(c, "EnterSceneCsReq", b);
          }
        }
        case "EnterSceneScRsp" -> {
          require((Integer) field(m, "retcode") == 0, "Fixture entrance rejected");
          result.put("actualSceneEntered", true);
          accept(c);
        }
        case "AcceptMainMissionScRsp" -> {
          require((Integer) field(m, "main_mission_id") == MAIN, "Wrong mission echo");
          if (resume) {
            require(
                step == 21 && (Integer) field(m, "retcode") != 0,
                "Completed mission accepted again");
            result.put("finishedMissionRetryRejected", true);
            step = 22;
          } else {
            require(step == 0 && (Integer) field(m, "retcode") == 0, "Fixture accept rejected");
            step = 1;
          }
          query(c);
        }
        case "GetMissionDataScRsp" -> {
          switch (step) {
            case 1 -> {
              progress(m, 0);
              step = 2;
              start(c);
            }
            case 6 -> {
              progress(m, 0);
              result.put("escapeStayedPending", true);
              step = 7;
              start(c);
            }
            case 9 -> {
              progress(m, 0);
              result.put("wrongMonsterStageStayedPending", true);
              step = 10;
              start(c);
            }
            case 12 -> {
              progress(m, 0);
              result.put("duplicateMonsterStayedPending", true);
              step = 13;
              start(c);
            }
            case 16 -> {
              progress(m, 1);
              result.put("actualEventKillCompleted", true);
              result.put("repeatedBattleResultRejected", true);
              done();
            }
            case 20 -> {
              progress(m, 1);
              result.put("savedMissionRestored", true);
              step = 21;
              accept(c);
            }
            case 22 -> {
              progress(m, 1);
              done();
            }
          }
        }
        case "SceneEnterStageScRsp" -> {
          require((Integer) field(m, "retcode") == 0, "Stage entry rejected");
          var battle = (DynamicMessage) field(m, "battle_info");
          battleId = (Integer) field(battle, "battle_id");
          stageId = (Integer) field(battle, "stage_id");
          require(stageId == 3050010, "Wrong event world-level stage");
          var waves = (List<?>) field(battle, "monster_wave_list");
          require(waves.size() == 1, "Wrong wave count");
          var wave = (DynamicMessage) waves.getFirst();
          var roster = (List<?>) field(wave, "monster_list");
          require(
              roster.size() == 1
                  && (Integer) field((DynamicMessage) roster.getFirst(), "monster_id") == MONSTER,
              "Wrong actual monster roster");
          result.put("actualEventStageAndRoster", true);
          result.put("stageId", stageId);
          switch (step) {
            case 2 -> {
              step = 3;
              finish(c, 1, true, false, false);
            }
            case 7 -> {
              step = 8;
              finish(c, 1, false, true, false);
            }
            case 10 -> {
              step = 11;
              finish(c, 1, false, false, true);
            }
            case 13 -> {
              step = 14;
              finish(c, 1, false, false, false);
            }
          }
        }
        case "PVEBattleResultScRsp" -> {
          int code = (Integer) field(m, "retcode");
          switch (step) {
            case 3 -> {
              require(code != 0, "Wrong identity accepted");
              result.put("wrongBattleIdentityRejected", true);
              step = 4;
              send(c, "GetCurBattleInfoCsReq", request("GetCurBattleInfoCsReq"));
            }
            case 41 -> {
              require(code != 0, "Unknown death enum accepted");
              result.put("unknownDeathRejected", true);
              step = 42;
              send(c, "GetCurBattleInfoCsReq", request("GetCurBattleInfoCsReq"));
            }
            case 5 -> {
              require(code == 0, "Escape settlement rejected");
              step = 6;
              query(c);
            }
            case 8 -> {
              require(code == 0, "Wrong monster stage telemetry rejected");
              step = 9;
              query(c);
            }
            case 11 -> {
              require(code == 0, "Duplicate telemetry settlement rejected");
              step = 12;
              query(c);
            }
            case 14 -> {
              require(code == 0, "Actual kill settlement rejected");
              step = 15;
              finish(c, 1, false, false, false);
            }
            case 15 -> {
              require(code != 0, "Repeated settlement accepted");
              step = 16;
              query(c);
            }
          }
        }
        case "GetCurBattleInfoScRsp" -> {
          require((Integer) field(m, "retcode") == 0, "Rejected report consumed battle");
          require(
              (Integer) field((DynamicMessage) field(m, "battle_info"), "battle_id") == battleId,
              "Battle identity changed");
          if (step == 4) {
            step = 41;
            finish(c, 4, false, false, false);
          } else {
            require(step == 42, "Unexpected query");
            result.put("invalidReportPreservedBattle", true);
            step = 5;
            finish(c, 3, false, false, false);
          }
        }
      }
    }
  }

  public static void main(String[] args) {
    var client = new KcpClient();
    Ukcp connection = null;
    int exit = 1;
    try {
      var probe = new LoopbackMissionBattleKillsProbe();
      var credentials =
          JsonParser.parseReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))
              .getAsJsonObject();
      probe.resume = credentials.has("resume") && credentials.get("resume").getAsBoolean();
      var token = probe.request("PlayerGetTokenCsReq");
      set(token, "account_uid", credentials.get("accountUid").getAsString());
      set(token, "token", credentials.get("comboToken").getAsString());
      set(token, "platform", 1);
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
              probe.send(c, "PlayerGetTokenCsReq", token);
            }

            public void handleReceive(ByteBuf bytes, Ukcp c) {
              try {
                probe.receive(bytes, c);
              } catch (Exception e) {
                probe.completed.completeExceptionally(e);
              }
            }

            public void handleException(Throwable e, Ukcp c) {
              probe.completed.completeExceptionally(e);
            }

            public void handleClose(Ukcp c) {
              if (!probe.completed.isDone())
                probe.completed.completeExceptionally(
                    new IllegalStateException("Battle kill probe disconnected"));
            }
          });
      connection =
          client.connect(new InetSocketAddress("127.0.0.1", Integer.parseInt(args[0])), config);
      System.out.write(
          (new Gson().toJson(probe.completed.get(20, TimeUnit.SECONDS)) + "\n")
              .getBytes(StandardCharsets.UTF_8));
      System.out.flush();
      exit = 0;
    } catch (Exception e) {
      System.err.println(
          "Battle kill mission probe failed: "
              + e.getClass().getSimpleName()
              + ": "
              + e.getMessage());
    } finally {
      if (connection != null) connection.close();
      client.stop();
    }
    System.exit(exit);
  }
}
