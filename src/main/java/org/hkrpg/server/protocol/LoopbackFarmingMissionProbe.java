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

/** Actual cocoon resource over KCP with an explicitly isolated two-step mission fixture. */
public final class LoopbackFarmingMissionProbe {
  private static final int MAIN = 19996923, FIRST = 199969231, SECOND = 199969232;
  private final ProtocolBridge protocol = ProtocolBridge.forVersion("4.6.0");
  private final CompletableFuture<Map<String, Object>> completed = new CompletableFuture<>();
  private final Map<String, Object> result = new LinkedHashMap<>();
  private boolean resume, categories;
  private int step, battleId, stageId, initialStamina, currentStamina;

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
    System.err.println("Farming probe request=" + name + ", step=" + step);
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

  private void start(Ukcp c, int waves) {
    start(c, 1001, waves);
  }

  private void start(Ukcp c, int cocoon, int waves) {
    String name = waves == 1 ? "QuickStartCocoonStageCsReq" : "StartCocoonStageCsReq";
    var b = request(name);
    set(b, "cocoon_id", cocoon);
    set(b, "wave", waves);
    send(c, name, b);
  }

  private void finish(Ukcp c, boolean wrong) {
    var b = request("PVEBattleResultCsReq");
    set(b, "battle_id", wrong ? battleId + 1 : battleId);
    set(b, "stage_id", stageId);
    var end = b.getDescriptorForType().findFieldByName("end_status");
    b.setField(end, end.getEnumType().findValueByName("BATTLE_END_WIN"));
    send(c, "PVEBattleResultCsReq", b);
  }

  private void progress(DynamicMessage m, int first, int second, boolean done) {
    require((Integer) field(m, "retcode") == 0, "Mission query failed");
    var rows = new HashMap<Integer, DynamicMessage>();
    for (Object raw : (List<?>) field(m, "mission_list")) {
      var row = (DynamicMessage) raw;
      rows.put((Integer) field(row, "id"), row);
    }
    require(
        rows.containsKey(FIRST) && (Integer) field(rows.get(FIRST), "progress") == first,
        "First mission progress changed");
    require(
        second < 0
            ? !rows.containsKey(SECOND)
            : rows.containsKey(SECOND) && (Integer) field(rows.get(SECOND), "progress") == second,
        "Second mission progress changed");
    require(
        ((List<?>) field(m, "finished_main_mission_id_list")).contains(MAIN)
            == (done && (!categories || step == 19 || step == 20 || step == 22)),
        "Main mission outcome changed");
    if (categories) {
      for (int category = 1; category <= 3; category++) {
        int expected =
            switch (category) {
              case 1 -> first;
              case 2 -> step == 19 || step == 20 || step == 22 ? 1 : 0;
              default -> step == 15 || step == 19 || step == 20 || step == 22 ? 1 : 0;
            };
        var row = rows.get(199969232 + category);
        require(
            row != null && (Integer) field(row, "progress") == expected,
            "Story category " + category + " changed at step " + step);
      }
    }
  }

  private void queryQuests(Ukcp c, int next) {
    step = next;
    send(c, "GetQuestDataCsReq", request("GetQuestDataCsReq"));
  }

  private void questProgress(DynamicMessage m, int normal, int weekly, int relic) {
    require((Integer) field(m, "retcode") == 0, "Category quest query rejected");
    var rows = new HashMap<Integer, Integer>();
    for (Object raw : (List<?>) field(m, "quest_list")) {
      var row = (DynamicMessage) raw;
      require(
          rows.put((Integer) field(row, "id"), (Integer) field(row, "progress")) == null,
          "Duplicate category quest record");
    }
    int[] ids = {4060201, 4060401, 4060301, 19997051, 19997052, 19997053};
    int[] values = {normal, weekly, relic, normal, weekly, relic};
    for (int i = 0; i < ids.length; i++)
      require(
          Objects.equals(rows.get(ids[i]), values[i]),
          "Category quest " + ids[i] + " expected " + values[i] + " got " + rows.get(ids[i]));
    result.put("categoryQuestProgress", Map.of("normal", normal, "weekly", weekly, "relic", relic));
  }

  private void done() {
    result.put("stamina", currentStamina);
    result.put("passed", true);
    result.put("clientGameplayVerified", false);
    completed.complete(result);
  }

  private void receive(ByteBuf bytes, Ukcp c) throws Exception {
    while (bytes.isReadable()) {
      var frame = PacketCodec.decodeNext(bytes);
      require(frame != null, "Truncated farming frame");
      String name =
          List.of(
                  "PlayerGetTokenScRsp",
                  "PlayerLoginScRsp",
                  "PlayerLoginFinishScRsp",
                  "AcceptMainMissionScRsp",
                  "GetMissionDataScRsp",
                  "StartCocoonStageScRsp",
                  "QuickStartCocoonStageScRsp",
                  "PVEBattleResultScRsp",
                  "GetQuestDataScRsp",
                  "GetDailyActiveInfoScRsp",
                  "StaminaInfoScNotify")
              .stream()
              .filter(n -> protocol.clientCommand(n) == frame.cmdId())
              .findFirst()
              .orElse(null);
      if (name == null) continue;
      System.err.println("Farming probe response=" + name + ", step=" + step);
      var m = protocol.clientSchema().parse(name, frame.body());
      require(m.getUnknownFields().asMap().isEmpty(), "Unknown farming fields: " + name);
      switch (name) {
        case "PlayerGetTokenScRsp" -> {
          require((Integer) field(m, "retcode") == 0, "Token rejected");
          result.put("uid", field(m, "uid"));
          send(c, "PlayerLoginCsReq", request("PlayerLoginCsReq"));
        }
        case "PlayerLoginScRsp" -> {
          require((Integer) field(m, "retcode") == 0, "Login rejected");
          initialStamina = currentStamina = (Integer) field(m, "stamina");
          send(c, "PlayerLoginFinishCsReq", request("PlayerLoginFinishCsReq"));
        }
        case "PlayerLoginFinishScRsp" -> {
          require((Integer) field(m, "retcode") == 0, "Login finish rejected");
          if (resume) {
            step = 20;
            query(c);
          } else accept(c);
        }
        case "AcceptMainMissionScRsp" -> {
          require((Integer) field(m, "main_mission_id") == MAIN, "Wrong accepted mission echo");
          if (resume) {
            require(
                step == 21 && (Integer) field(m, "retcode") != 0,
                "Completed mission was accepted again");
            step = 22;
            query(c);
          } else {
            require(
                step == 0 && (Integer) field(m, "retcode") == 0,
                "Fixture mission acceptance failed");
            step = 1;
            if (categories) {
              step = 40;
              send(c, "GetDailyActiveInfoCsReq", request("GetDailyActiveInfoCsReq"));
            } else query(c);
          }
        }
        case "GetMissionDataScRsp" -> {
          switch (step) {
            case 1 -> {
              progress(m, 0, -1, false);
              step = 2;
              start(c, 3);
            }
            case 4 -> {
              progress(m, 0, -1, false);
              result.put("wrongBattleIdentityRejected", true);
              step = 5;
              finish(c, false);
            }
            case 7 -> {
              progress(m, 1, 0, false);
              require(currentStamina == initialStamina - 30, "Three-wave stamina cost changed");
              result.put("threeWaveBattleAndStamina", true);
              result.put("newObjectiveWaitsForAnotherBattle", true);
              if (categories) queryQuests(c, 30);
              else {
                step = 8;
                start(c, 1);
              }
            }
            case 11 -> {
              progress(m, 1, 1, true);
              require(
                  currentStamina == initialStamina - 40,
                  "Two battles did not debit actual stamina once");
              result.put("acceptedMissionFinishedAfterTwoBattles", true);
              result.put("repeatedBattleResultRejected", true);
              if (categories) queryQuests(c, 31);
              else done();
            }
            case 15 -> {
              progress(m, 1, 1, false);
              require(currentStamina == initialStamina - 80, "Relic stamina cost changed");
              queryQuests(c, 32);
            }
            case 19 -> {
              progress(m, 1, 1, true);
              require(currentStamina == initialStamina - 110, "Weekly stamina cost changed");
              result.put("threeCategoriesStaySeparate", true);
              queryQuests(c, 33);
            }
            case 20 -> {
              progress(m, 1, 1, true);
              step = 21;
              accept(c);
            }
            case 22 -> {
              progress(m, 1, 1, true);
              result.put("savedMissionRestored", true);
              result.put("finishedMissionRetryRejected", true);
              if (categories) queryQuests(c, 34);
              else done();
            }
            default -> throw new IllegalStateException("Unexpected mission step " + step);
          }
        }
        case "StartCocoonStageScRsp", "QuickStartCocoonStageScRsp" -> {
          require(
              (step == 2 || step == 8 || step == 12 || step == 16)
                  && (Integer) field(m, "retcode") == 0,
              "Actual cocoon start failed");
          int expected = step == 2 ? 3 : 1;
          require((Integer) field(m, "wave") == expected, "Requested native waves were ignored");
          var battle = (DynamicMessage) field(m, "battle_info");
          battleId = (Integer) field(battle, "battle_id");
          stageId = (Integer) field(battle, "stage_id");
          require(
              battleId > 0
                  && ((List<?>) field(battle, "monster_wave_list")).size()
                      == (step == 12 ? 2 : expected),
              "Actual cocoon battle waves changed");
          if (step == 2) {
            step = 3;
            finish(c, true);
          } else {
            step = step == 8 ? 9 : step + 1;
            finish(c, false);
          }
        }
        case "PVEBattleResultScRsp" -> {
          int code = (Integer) field(m, "retcode");
          switch (step) {
            case 3 -> {
              require(code != 0, "Wrong battle identity accepted");
              step = 4;
              query(c);
            }
            case 5 -> {
              require(code == 0, "First actual cocoon victory rejected");
              step = 6;
              finish(c, false);
            }
            case 6 -> {
              require(code != 0, "First repeated victory accepted");
              step = 7;
              query(c);
            }
            case 9 -> {
              require(code == 0, "Second actual cocoon victory rejected");
              step = 10;
              finish(c, false);
            }
            case 10 -> {
              require(code != 0, "Second repeated victory accepted");
              step = 11;
              query(c);
            }
            case 13, 17 -> {
              require(code == 0, "Category victory rejected");
              step++;
              finish(c, false);
            }
            case 14, 18 -> {
              require(code != 0, "Repeated category victory accepted");
              step++;
              query(c);
            }
            default -> throw new IllegalStateException("Unexpected battle step " + step);
          }
        }
        case "StaminaInfoScNotify" -> currentStamina = (Integer) field(m, "stamina");
        case "GetDailyActiveInfoScRsp" -> {
          if (step != 40) break;
          require((Integer) field(m, "retcode") == 0, "Daily category query rejected");
          var ids = (List<?>) field(m, "daily_active_quest_id_list");
          require(
              ids.containsAll(List.of(19997051, 19997052, 19997053)),
              "Daily categories not selected");
          queryQuests(c, 41);
        }
        case "GetQuestDataScRsp" -> {
          // The same response may also be an unsolicited progress sync during battle settlement.
          switch (step) {
            case 41 -> {
              questProgress(m, 0, 0, 0);
              step = 1;
              query(c);
            }
            case 30 -> {
              questProgress(m, 3, 0, 0);
              step = 8;
              start(c, 1);
            }
            case 31 -> {
              questProgress(m, 4, 0, 0);
              step = 12;
              start(c, 1201, 1);
            }
            case 32 -> {
              questProgress(m, 4, 0, 1);
              step = 16;
              start(c, 1301, 1);
            }
            case 33, 34 -> {
              questProgress(m, 4, 1, 1);
              result.put("actualCategoryAchievementsAndDailies", true);
              done();
            }
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
      var probe = new LoopbackFarmingMissionProbe();
      var credentials =
          JsonParser.parseReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))
              .getAsJsonObject();
      probe.resume = credentials.has("resume") && credentials.get("resume").getAsBoolean();
      probe.categories =
          credentials.has("categories") && credentials.get("categories").getAsBoolean();
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
                    new IllegalStateException("Farming probe disconnected"));
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
          "Farming mission probe failed: " + e.getClass().getSimpleName() + ": " + e.getMessage());
    } finally {
      if (connection != null) connection.close();
      client.stop();
    }
    System.exit(exit);
  }
}
