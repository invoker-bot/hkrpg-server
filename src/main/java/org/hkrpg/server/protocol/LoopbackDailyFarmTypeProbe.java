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

/** Original golden/crimson daily, actual resource waves and debit; no client UI acceptance. */
public final class LoopbackDailyFarmTypeProbe {
  private final ProtocolBridge protocol = ProtocolBridge.forVersion("4.6.0");
  private final CompletableFuture<Map<String, Object>> completed = new CompletableFuture<>();
  private final Map<String, Object> result = new LinkedHashMap<>();
  private boolean resume;
  private int quest, target, other, step, battleId, stageId, initialStamina, currentStamina;

  private static Object field(DynamicMessage m, String name) {
    return m.getField(m.getDescriptorForType().findFieldByName(name));
  }

  private static int number(DynamicMessage m, String name) {
    return (Integer) field(m, name);
  }

  private static void require(boolean valid, String reason) {
    if (!valid) throw new IllegalStateException(reason);
  }

  private DynamicMessage.Builder request(String name) {
    return DynamicMessage.newBuilder(protocol.clientSchema().message(name));
  }

  private static void set(DynamicMessage.Builder b, String name, Object value) {
    b.setField(b.getDescriptorForType().findFieldByName(name), value);
  }

  private void send(Ukcp c, String name, DynamicMessage.Builder b) {
    System.err.println("Farm daily request=" + name + ", step=" + step);
    var packet =
        Unpooled.wrappedBuffer(
            PacketCodec.encode(protocol.clientCommand(name), new byte[0], b.build().toByteArray()));
    try {
      c.write(packet);
    } finally {
      packet.release();
    }
  }

  private void query(Ukcp c, String name, int next) {
    step = next;
    send(c, name, request(name));
  }

  private void start(Ukcp c, boolean matching) {
    String name = matching ? "StartCocoonStageCsReq" : "QuickStartCocoonStageCsReq";
    var b = request(name);
    set(b, "cocoon_id", matching ? target : other);
    set(b, "wave", matching ? 2 : 1);
    step = matching ? 6 : 3;
    send(c, name, b);
  }

  private void finish(Ukcp c, boolean wrong) {
    var b = request("PVEBattleResultCsReq");
    set(b, "battle_id", battleId + (wrong ? 1 : 0));
    set(b, "stage_id", stageId);
    var end = b.getDescriptorForType().findFieldByName("end_status");
    b.setField(end, end.getEnumType().findValueByName("BATTLE_END_WIN"));
    send(c, "PVEBattleResultCsReq", b);
  }

  private void claim(Ukcp c, int next) {
    var b = request("TakeQuestRewardCsReq");
    b.addRepeatedField(b.getDescriptorForType().findFieldByName("ODJIJFJCHHB"), quest);
    step = next;
    send(c, "TakeQuestRewardCsReq", b);
  }

  private void points(DynamicMessage m, int expected) {
    require(
        number(m, "retcode") == 0 && number(m, "daily_active_point") == expected,
        "Wrong daily points");
    require(
        ((List<?>) field(m, "daily_active_quest_id_list")).equals(List.of(quest)),
        "Wrong isolated original daily selection");
    result.put("dailyPoints", expected);
  }

  private void state(DynamicMessage m, int progress, int status) {
    require(number(m, "retcode") == 0, "Quest query rejected");
    var rows =
        ((List<?>) field(m, "quest_list"))
            .stream().map(r -> (DynamicMessage) r).filter(r -> number(r, "id") == quest).toList();
    require(rows.size() == 1, "Missing original farm daily");
    var q = rows.getFirst();
    require(
        number(q, "progress") == progress
            && ((com.google.protobuf.Descriptors.EnumValueDescriptor) field(q, "status"))
                    .getNumber()
                == status,
        "Wrong farm daily progress/status at " + step);
    result.put("dailyProgress", progress);
    result.put("dailyQuestStatus", status);
  }

  private void done() {
    require(currentStamina == initialStamina - 30, "Wrong actual source stamina debit");
    result.put("nativeActualThirtyStaminaDebit", true);
    result.put("initialStamina", initialStamina);
    result.put("remainingStamina", currentStamina);
    result.put("passed", true);
    result.put("clientGameplayVerified", false);
    result.put("quest", quest);
    result.put("cocoon", target);
    completed.complete(result);
  }

  private void receive(ByteBuf bytes, Ukcp c) throws Exception {
    while (bytes.isReadable()) {
      var frame = PacketCodec.decodeNext(bytes);
      require(frame != null, "Truncated response");
      String name =
          List.of(
                  "PlayerGetTokenScRsp",
                  "PlayerLoginScRsp",
                  "PlayerLoginFinishScRsp",
                  "StaminaInfoScNotify",
                  "GetDailyActiveInfoScRsp",
                  "GetQuestDataScRsp",
                  "TakeQuestRewardScRsp",
                  "QuickStartCocoonStageScRsp",
                  "StartCocoonStageScRsp",
                  "PVEBattleResultScRsp")
              .stream()
              .filter(n -> protocol.clientCommand(n) == frame.cmdId())
              .findFirst()
              .orElse(null);
      if (name == null) continue;
      var m = protocol.clientSchema().parse(name, frame.body());
      require(m.getUnknownFields().asMap().isEmpty(), "Unknown native response fields");
      System.err.println("Farm daily response=" + name + ", step=" + step);
      switch (name) {
        case "PlayerGetTokenScRsp" -> {
          require(number(m, "retcode") == 0, "Token rejected");
          result.put("uid", field(m, "uid"));
          send(c, "PlayerLoginCsReq", request("PlayerLoginCsReq"));
        }
        case "PlayerLoginScRsp" -> {
          require(number(m, "retcode") == 0, "Login rejected");
          currentStamina = number(m, "stamina");
          if (resume) {
            require(
                currentStamina == initialStamina - 30, "Saved actual stamina debit not restored");
            result.put("savedActualStaminaRestored", true);
          } else initialStamina = currentStamina;
          send(c, "PlayerLoginFinishCsReq", request("PlayerLoginFinishCsReq"));
        }
        case "PlayerLoginFinishScRsp" -> {
          require(number(m, "retcode") == 0, "Login finish rejected");
          query(c, "GetDailyActiveInfoCsReq", 1);
        }
        case "StaminaInfoScNotify" -> currentStamina = number(m, "stamina");
        case "GetDailyActiveInfoScRsp" -> {
          if (step == 1) {
            points(m, resume ? 200 : 0);
            query(c, "GetQuestDataCsReq", 2);
          } else if (step == 11) {
            points(m, 200);
            result.put("nativeOriginalTwoHundredPoints", true);
            claim(c, 12);
          } else {
            require(step == 14, "Unexpected daily point response");
            points(m, 200);
            done();
          }
        }
        case "GetQuestDataScRsp" -> {
          if (step == 2) {
            state(m, resume ? 1 : 0, resume ? 3 : 1);
            if (resume) {
              result.put("savedFarmDailyRestored", true);
              claim(c, 20);
            } else {
              result.put("nativeOriginalFarmDailySelected", true);
              start(c, false);
            }
          } else if (step == 5) {
            state(m, 0, 1);
            result.put("nativeOtherColorStayedPending", true);
            start(c, true);
          } else if (step == 9) {
            state(m, 1, 2);
            result.put("nativeActualFarmVictoryCompletedDaily", true);
            claim(c, 10);
          } else if (step == 13) {
            state(m, 1, 3);
            query(c, "GetDailyActiveInfoCsReq", 14);
          } else if (step <= 7) {
            state(m, 0, 1);
          } else if (step == 8 || step == 81) {
            // Drops can publish a snapshot before the farming callback; the later explicit
            // query must prove the completed state after the accepted battle response.
            var rows =
                ((List<?>) field(m, "quest_list"))
                    .stream()
                        .map(r -> (DynamicMessage) r)
                        .filter(r -> number(r, "id") == quest)
                        .toList();
            require(rows.size() == 1, "Missing original farm daily snapshot");
            int progress = number(rows.getFirst(), "progress");
            require(progress == 0 || progress == 1, "Invalid farm daily snapshot progress");
            state(m, progress, progress == 0 ? 1 : 2);
          } else state(m, 1, 3); // Existing claim handler sends an additional quest snapshot.
        }
        case "QuickStartCocoonStageScRsp", "StartCocoonStageScRsp" -> {
          boolean matching = step == 6;
          require(
              number(m, "retcode") == 0
                  && number(m, "cocoon_id") == (matching ? target : other)
                  && number(m, "wave") == (matching ? 2 : 1),
              "Wrong actual farming start");
          var battle = (DynamicMessage) field(m, "battle_info");
          battleId = number(battle, "battle_id");
          stageId = number(battle, "stage_id");
          var stages =
              Set.of(
                  matching
                      ? (target == 1001 ? 1022010 : 1022310)
                      : (other == 1001 ? 1022010 : 1022310),
                  matching
                      ? (target == 1001 ? 1022020 : 1022320)
                      : (other == 1001 ? 1022020 : 1022320),
                  matching
                      ? (target == 1001 ? 1022030 : 1022330)
                      : (other == 1001 ? 1022030 : 1022330));
          var waves = (List<?>) field(battle, "monster_wave_list");
          require(
              stages.contains(stageId)
                  && waves.size() == (matching ? 2 : 1)
                  && waves.stream()
                      .allMatch(
                          w -> !((List<?>) field((DynamicMessage) w, "monster_list")).isEmpty()),
              "Original nonempty resource waves missing");
          result.put(
              matching ? "nativeActualTwoWaveTargetStarted" : "nativeActualOtherColorStarted",
              true);
          step = matching ? 7 : 4;
          finish(c, matching);
        }
        case "PVEBattleResultScRsp" -> {
          if (step == 4) {
            require(number(m, "retcode") == 0, "Other color WIN rejected");
            query(c, "GetQuestDataCsReq", 5);
          } else if (step == 7) {
            require(number(m, "retcode") != 0, "Wrong battle identity accepted");
            result.put("nativeWrongIdentityRejected", true);
            step = 8;
            finish(c, false);
          } else if (step == 8) {
            require(number(m, "retcode") == 0, "Target WIN rejected after bad identity");
            step = 81;
            finish(c, false);
          } else {
            require(step == 81 && number(m, "retcode") != 0, "Repeated settlement accepted");
            result.put("nativeRepeatedReportRejected", true);
            query(c, "GetQuestDataCsReq", 9);
          }
        }
        case "TakeQuestRewardScRsp" -> {
          boolean accepted = step == 10;
          require(
              number(m, "retcode")
                  == (accepted
                      ? 0
                      : emu.lunarcore.server.packet.Retcode.QUEST_REWARD_ALREADY_TAKEN.getVal()),
              "Wrong original daily claim result");
          require(
              ((List<?>) field(m, "quest_id_list")).equals(List.of(quest))
                  && ((List<?>) field(m, "succ_quest_id_list"))
                      .equals(accepted ? List.of(quest) : List.of())
                  && ((List<?>) field((DynamicMessage) field(m, "reward"), "item_list")).isEmpty(),
              "Wrong native virtual point award");
          if (accepted) {
            result.put("nativeVirtualPointsWithoutInventoryItem", true);
            query(c, "GetDailyActiveInfoCsReq", 11);
          } else if (step == 12) {
            result.put("nativeDuplicateRewardRejected", true);
            query(c, "GetQuestDataCsReq", 13);
          } else {
            require(step == 20, "Unexpected claim response");
            result.put("nativeSavedDuplicateRewardRejected", true);
            query(c, "GetDailyActiveInfoCsReq", 14);
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
      var probe = new LoopbackDailyFarmTypeProbe();
      var credentials =
          JsonParser.parseReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))
              .getAsJsonObject();
      probe.quest = credentials.get("quest").getAsInt();
      require(probe.quest == 2100157 || probe.quest == 2100158, "Unknown original daily");
      probe.target = probe.quest == 2100157 ? 1001 : 1004;
      probe.other = probe.quest == 2100157 ? 1004 : 1001;
      probe.resume = credentials.has("resume") && credentials.get("resume").getAsBoolean();
      if (probe.resume) probe.initialStamina = credentials.get("initialStamina").getAsInt();
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
                    new IllegalStateException("Farm daily probe disconnected"));
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
          "Farm daily probe failed: " + e.getClass().getSimpleName() + ": " + e.getMessage());
    } finally {
      if (connection != null) connection.close();
      client.stop();
    }
    System.exit(exit);
  }
}
