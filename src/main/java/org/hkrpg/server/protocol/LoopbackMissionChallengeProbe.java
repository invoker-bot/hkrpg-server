package org.hkrpg.server.protocol;

import com.google.gson.*;
import com.google.protobuf.CodedOutputStream;
import com.google.protobuf.DynamicMessage;
import emu.lunarcore.server.packet.PacketCodec;
import io.netty.buffer.*;
import java.io.ByteArrayOutputStream;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import kcp.highway.*;

/** Actual memory challenges 1/15; isolated mission acceptance gates only. */
public final class LoopbackMissionChallengeProbe {
  private final ProtocolBridge protocol = ProtocolBridge.forVersion("4.6.0");
  private final CompletableFuture<Map<String, Object>> completed = new CompletableFuture<>();
  private final Map<String, Object> result = new LinkedHashMap<>();
  private boolean resume;
  private boolean verifyDaily;
  private int step, battleId, stageId, challenge = 1, half = 1;
  private boolean settled;
  private DynamicMessage currentScene;

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
    sendBytes(c, name, b.build().toByteArray());
  }

  private void sendBytes(Ukcp c, String name, byte[] body) {
    System.err.println("Memory request=" + name + ", step=" + step + ", half=" + half);
    var packet =
        Unpooled.wrappedBuffer(PacketCodec.encode(protocol.clientCommand(name), new byte[0], body));
    try {
      c.write(packet);
    } finally {
      packet.release();
    }
  }

  private void accept(Ukcp c, int id) {
    var b = request("AcceptMainMissionCsReq");
    set(b, "main_mission_id", id);
    send(c, "AcceptMainMissionCsReq", b);
  }

  private void query(Ukcp c) {
    send(c, "GetMissionDataCsReq", request("GetMissionDataCsReq"));
  }

  private void begin(Ukcp c) {
    var b = request("StartChallengeCsReq");
    set(b, "challenge_id", challenge);
    if (challenge == 1)
      b.addRepeatedField(b.getDescriptorForType().findFieldByName("first_lineup"), 8001);
    else {
      var avatar = request("AvatarIdentifier");
      set(avatar, "id", 8001);
      var type = avatar.getDescriptorForType().findFieldByName("avatar_type");
      avatar.setField(type, type.getEnumType().findValueByName("AvatarType_AvatarFormalType"));
      b.addRepeatedField(
          b.getDescriptorForType().findFieldByName("avatar_lineup_first"), avatar.build());
      b.addRepeatedField(
          b.getDescriptorForType().findFieldByName("avatar_lineup_second"), avatar.build());
    }
    send(c, "StartChallengeCsReq", b);
  }

  private void attack(Ukcp c) {
    int enemy = 0;
    for (var raw : (List<?>) field(currentScene, "entity_group_list"))
      for (var row : (List<?>) field((DynamicMessage) raw, "entity_list")) {
        var entity = (DynamicMessage) row;
        if (entity.hasField(entity.getDescriptorForType().findFieldByName("npc_monster"))) {
          enemy = (Integer) field(entity, "entity_id");
          break;
        }
      }
    require(enemy > 0, "No actual challenge NPC in half " + half);
    var b = request("SceneCastSkillCsReq");
    set(b, "cast_entity_id", field(currentScene, "leader_entity_id"));
    set(b, "attacked_by_entity_id", field(currentScene, "leader_entity_id"));
    b.addRepeatedField(
        b.getDescriptorForType().findFieldByName("hit_target_entity_id_list"), enemy);
    send(c, "SceneCastSkillCsReq", b);
  }

  private void finish(Ukcp c, boolean wrong) {
    var b = request("PVEBattleResultCsReq");
    set(b, "battle_id", wrong ? battleId + 1 : battleId);
    set(b, "stage_id", stageId);
    var end = b.getDescriptorForType().findFieldByName("end_status");
    b.setField(end, end.getEnumType().findValueByName("BATTLE_END_WIN"));
    var statistics = request("BattleStatistics");
    set(statistics, "round_cnt", 8);
    set(b, "stt", statistics.build());
    send(c, "PVEBattleResultCsReq", b);
  }

  private void progress(DynamicMessage m, int count, int specified) {
    require((Integer) field(m, "retcode") == 0, "Mission query failed");
    for (var row : new int[][] {{401010507, count}, {401010601, specified}}) {
      var matches =
          ((List<?>) field(m, "mission_list"))
              .stream()
                  .map(v -> (DynamicMessage) v)
                  .filter(v -> (Integer) field(v, "id") == row[0])
                  .toList();
      require(
          matches.size() == 1 && (Integer) field(matches.getFirst(), "progress") == row[1],
          "Wrong challenge objective " + row[0] + " at step " + step);
    }
    result.put("countProgress", count);
    result.put("specifiedProgress", specified);
  }

  private void overflowRounds(Ukcp c) throws Exception {
    var b = request("PVEBattleResultCsReq");
    set(b, "battle_id", battleId);
    set(b, "stage_id", stageId);
    var end = b.getDescriptorForType().findFieldByName("end_status");
    b.setField(end, end.getEnumType().findValueByName("BATTLE_END_WIN"));
    var statistics = new ByteArrayOutputStream();
    var writer = CodedOutputStream.newInstance(statistics);
    writer.writeUInt64(9, (1L << 32) + 8);
    writer.flush();
    var body = new ByteArrayOutputStream();
    body.write(b.build().toByteArray());
    writer = CodedOutputStream.newInstance(body);
    writer.writeByteArray(8, statistics.toByteArray());
    writer.flush();
    sendBytes(c, "PVEBattleResultCsReq", body.toByteArray());
  }

  private void done() {
    result.put("passed", true);
    result.put("clientGameplayVerified", false);
    completed.complete(result);
  }

  private void history(Ukcp c) {
    send(c, "GetChallengeCsReq", request("GetChallengeCsReq"));
  }

  private void dailyQuery(Ukcp c, String name, int next) {
    step = next;
    send(c, name, request(name));
  }

  private void dailyClaim(Ukcp c, int next) {
    var b = request("TakeQuestRewardCsReq");
    b.addRepeatedField(b.getDescriptorForType().findFieldByName("ODJIJFJCHHB"), 2100119);
    step = next;
    send(c, "TakeQuestRewardCsReq", b);
  }

  private void dailyPoints(DynamicMessage m, int expected) {
    require((Integer) field(m, "retcode") == 0, "Daily point query rejected");
    require((Integer) field(m, "daily_active_point") == expected, "Wrong daily point balance");
    require(
        ((List<?>) field(m, "daily_active_quest_id_list")).equals(List.of(2100119)),
        "Original Memory daily not selected");
    result.put("dailyPoints", expected);
  }

  private void dailyState(DynamicMessage m, int progress, int status) {
    require((Integer) field(m, "retcode") == 0, "Daily quest query rejected");
    var rows =
        ((List<?>) field(m, "quest_list"))
            .stream()
                .map(r -> (DynamicMessage) r)
                .filter(r -> (Integer) field(r, "id") == 2100119)
                .toList();
    require(rows.size() == 1, "Missing or duplicate Memory daily");
    var quest = rows.getFirst();
    require(
        (Integer) field(quest, "progress") == progress
            && ((com.google.protobuf.Descriptors.EnumValueDescriptor) field(quest, "status"))
                    .getNumber()
                == status,
        "Wrong Memory daily progress/status at step " + step);
    result.put("dailyProgress", progress);
    result.put("dailyQuestStatus", status);
  }

  private void checkHistory(DynamicMessage message) {
    require((Integer) field(message, "retcode") == 0, "History query rejected");
    for (int id : List.of(1, 15)) {
      var rows =
          ((List<?>) field(message, "challenge_list"))
              .stream()
                  .map(row -> (DynamicMessage) row)
                  .filter(row -> (Integer) field(row, "challenge_id") == id)
                  .toList();
      require(
          rows.size() == 1 && (Integer) field(rows.getFirst(), "star") == 5,
          "Wrong saved star mask for " + id);
    }
    result.put("nativeHistoryStars", true);
    result.put("firstWinStars", 5);
    result.put("specifiedWinStars", 5);
  }

  private void receive(ByteBuf bytes, Ukcp c) throws Exception {
    while (bytes.isReadable()) {
      var frame = PacketCodec.decodeNext(bytes);
      require(frame != null, "Truncated frame");
      String name =
          List.of(
                  "PlayerGetTokenScRsp",
                  "PlayerLoginScRsp",
                  "PlayerLoginFinishScRsp",
                  "AcceptMainMissionScRsp",
                  "GetMissionDataScRsp",
                  "GetChallengeScRsp",
                  "GetCurChallengeScRsp",
                  "StartChallengeScRsp",
                  "SceneCastSkillScRsp",
                  "PVEBattleResultScRsp",
                  "GetCurBattleInfoScRsp",
                  "GetSceneMapInfoScRsp",
                  "EnterSceneByServerScNotify",
                  "LeaveChallengeScRsp",
                  "ChallengeSettleNotify",
                  "GetDailyActiveInfoScRsp",
                  "GetQuestDataScRsp",
                  "TakeQuestRewardScRsp")
              .stream()
              .filter(n -> protocol.clientCommand(n) == frame.cmdId())
              .findFirst()
              .orElse(null);
      if (name == null) continue;
      System.err.println("Memory response=" + name + ", step=" + step + ", half=" + half);
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
          if (verifyDaily) {
            dailyQuery(c, "GetDailyActiveInfoCsReq", 80);
          } else if (resume) {
            step = 20;
            query(c);
          } else accept(c, 4010105);
        }
        case "AcceptMainMissionScRsp" -> {
          require((Integer) field(m, "retcode") == 0, "Accept rejected");
          if (step == 0) {
            step = 1;
            accept(c, 4010106);
          } else {
            require(step == 1, "Unexpected accept");
            step = 2;
            query(c);
          }
        }
        case "GetMissionDataScRsp" -> {
          if (step == 2) {
            progress(m, 0, 0);
            step = 3;
            begin(c);
          } else if (step == 7) {
            progress(m, 1, 0);
            result.put("actualCountCompletedOnce", true);
            if (verifyDaily) dailyQuery(c, "GetDailyActiveInfoCsReq", 82);
            else {
              step = 8;
              send(c, "LeaveChallengeCsReq", request("LeaveChallengeCsReq"));
            }
          } else if (step == 12) {
            progress(m, 1, 0);
            result.put("firstHalfStayedPending", true);
            step = 13;
            send(c, "GetCurChallengeCsReq", request("GetCurChallengeCsReq"));
          } else if (step == 16) {
            progress(m, 1, 1);
            require(settled, "No actual final settlement notification");
            result.put("actualSpecifiedTwoHalfCompleted", true);
            step = 17;
            send(c, "LeaveChallengeCsReq", request("LeaveChallengeCsReq"));
          } else if (step == 20) {
            progress(m, 1, 1);
            result.put("savedObjectivesRestored", true);
            step = 21;
            history(c);
          }
        }
        case "StartChallengeScRsp" -> {
          require((Integer) field(m, "retcode") == 0, "Start rejected");
          currentScene = (DynamicMessage) field(m, "scene");
          require((Integer) field(currentScene, "entry_id") == 3000101, "Wrong challenge entrance");
          var cur = (DynamicMessage) field(m, "cur_challenge");
          require((Integer) field(cur, "challenge_id") == challenge, "Wrong current challenge");
          result.put(
              challenge == 1 ? "packedLineupStarted" : "formalIdentifierLineupsStarted", true);
          half = 1;
          step = challenge == 1 ? 4 : 10;
          send(c, "GetCurChallengeCsReq", request("GetCurChallengeCsReq"));
        }
        case "GetCurChallengeScRsp" -> {
          require((Integer) field(m, "retcode") == 0, "Current challenge query rejected");
          var cur = (DynamicMessage) field(m, "cur_challenge");
          require(
              (Integer) field(cur, "challenge_id") == challenge,
              "Current challenge identity changed");
          require(
              (Integer) field(cur, "round_count") == (half == 1 ? 0 : 8), "Wrong consumed rounds");
          require(
              ((com.google.protobuf.Descriptors.EnumValueDescriptor)
                          field(cur, "extra_lineup_type"))
                      .getNumber()
                  == (half == 1 ? 1 : 3),
              "Wrong half lineup");
          require(step == 4 || step == 10 || step == 13, "Unexpected current challenge query");
          result.put(half == 1 ? "nativeCurrentChallengeQuery" : "nativeSecondHalfQuery", true);
          attack(c);
        }
        case "GetChallengeScRsp" -> {
          require(step == 19 || step == 21, "Unexpected challenge history");
          checkHistory(m);
          if (resume) result.put("savedStarsRestored", true);
          if (verifyDaily) dailyQuery(c, "GetDailyActiveInfoCsReq", 88);
          else done();
        }
        case "GetDailyActiveInfoScRsp" -> {
          if (!verifyDaily) break;
          if (step == 80) {
            dailyPoints(m, resume ? 200 : 0);
            dailyQuery(c, "GetQuestDataCsReq", 81);
          } else if (step == 82) {
            dailyPoints(m, 0);
            dailyQuery(c, "GetQuestDataCsReq", 83);
          } else if (step == 85) {
            dailyPoints(m, 200);
            result.put("nativeOriginalMemoryDailyReward", true);
            dailyClaim(c, 86);
          } else if (step == 88) {
            dailyPoints(m, 200);
            dailyQuery(c, "GetQuestDataCsReq", 89);
          } else throw new IllegalStateException("Unexpected daily point query");
        }
        case "GetQuestDataScRsp" -> {
          if (!verifyDaily) break;
          if (step == 81) {
            dailyState(m, resume ? 1 : 0, resume ? 3 : 1);
            if (resume) {
              result.put("savedDailyRewardRestored", true);
              dailyClaim(c, 90);
            } else {
              result.put("nativeOriginalMemoryDailySelected", true);
              step = 0;
              accept(c, 4010105);
            }
          } else if (step == 83) {
            dailyState(m, 1, 2);
            result.put("nativeWholeMemoryWinFinishedDaily", true);
            dailyClaim(c, 84);
          } else if (step == 87) {
            dailyState(m, 1, 3);
            step = 8;
            send(c, "LeaveChallengeCsReq", request("LeaveChallengeCsReq"));
          } else if (step == 89) {
            dailyState(m, 1, 3);
            result.put("nativeDailyCreditStayedSingle", true);
            done();
          } else {
            // The existing claim handler also sends a fresh quest snapshot after its reply.
            dailyState(m, 1, 3);
          }
        }
        case "TakeQuestRewardScRsp" -> {
          require(verifyDaily && Set.of(84, 86, 90).contains(step), "Unexpected daily claim");
          boolean success = step == 84;
          require(
              (Integer) field(m, "retcode")
                  == (success
                      ? 0
                      : emu.lunarcore.server.packet.Retcode.QUEST_REWARD_ALREADY_TAKEN.getVal()),
              "Wrong daily claim result");
          require(
              ((List<?>) field(m, "quest_id_list")).equals(List.of(2100119))
                  && ((List<?>) field(m, "succ_quest_id_list"))
                      .equals(success ? List.of(2100119) : List.of()),
              "Wrong daily claim IDs");
          require(
              ((List<?>) field((DynamicMessage) field(m, "reward"), "item_list")).isEmpty(),
              "Activity points became ordinary inventory items");
          if (success) {
            result.put("nativeActivityPointsRemainVirtual", true);
            dailyQuery(c, "GetDailyActiveInfoCsReq", 85);
          } else if (step == 86) {
            result.put("nativeMemoryDailyDuplicateRejected", true);
            dailyQuery(c, "GetQuestDataCsReq", 87);
          } else {
            result.put("nativeSavedMemoryDailyClaimRejected", true);
            step = 20;
            query(c);
          }
        }
        case "SceneCastSkillScRsp" -> {
          require((Integer) field(m, "retcode") == 0, "Cast rejected");
          var b = (DynamicMessage) field(m, "battle_info");
          battleId = (Integer) field(b, "battle_id");
          stageId = (Integer) field(b, "stage_id");
          require(
              stageId == (challenge == 1 ? 30001011 : half == 1 ? 30001151 : 30001152),
              "Wrong resource stage");
          require(!((List<?>) field(b, "monster_wave_list")).isEmpty(), "Missing real waves");
          if (challenge == 1) {
            step = 5;
            finish(c, true);
          } else {
            step = half == 1 ? 11 : 14;
            finish(c, false);
          }
        }
        case "PVEBattleResultScRsp" -> {
          int code = (Integer) field(m, "retcode");
          if (step == 5) {
            require(code != 0, "Wrong battle accepted");
            result.put("wrongIdentityRejected", true);
            step = 51;
            send(c, "GetCurBattleInfoCsReq", request("GetCurBattleInfoCsReq"));
          } else if (step == 52) {
            require(code != 0, "Wrapped rounds accepted as eight");
            result.put("overflowRoundsRejected", true);
            step = 53;
            send(c, "GetCurBattleInfoCsReq", request("GetCurBattleInfoCsReq"));
          } else if (step == 6) {
            require(code == 0, "Valid count WIN rejected");
            step = 61;
            finish(c, false);
          } else if (step == 61) {
            require(code != 0, "Repeated count accepted");
            result.put("duplicateReportRejected", true);
            step = 7;
            query(c);
          } else if (step == 11) {
            require(code == 0, "First half WIN rejected");
            step = 12;
            query(c);
          } else if (step == 14) {
            require(code == 0, "Final half WIN rejected");
            step = 15;
            finish(c, false);
          } else if (step == 15) {
            require(code != 0, "Repeated final accepted");
            step = 16;
            query(c);
          }
        }
        case "GetCurBattleInfoScRsp" -> {
          require(
              (step == 51 || step == 53) && (Integer) field(m, "retcode") == 0,
              "Rejected report consumed battle");
          require(
              (Integer) field((DynamicMessage) field(m, "battle_info"), "battle_id") == battleId,
              "Current identity changed");
          if (step == 51) {
            result.put("rejectedReportPreservedBattle", true);
            step = 52;
            overflowRounds(c);
          } else {
            result.put("overflowRoundsPreservedBattle", true);
            step = 6;
            finish(c, false);
          }
        }
        case "EnterSceneByServerScNotify" -> {
          if (challenge == 15 && step == 11) {
            currentScene = (DynamicMessage) field(m, "scene");
            half = 2;
          }
        }
        case "ChallengeSettleNotify" -> {
          require(
              (Integer) field(m, "challenge_id") == challenge && (Boolean) field(m, "is_win"),
              "Wrong memory outcome");
          require((Integer) field(m, "star") == 5, "Final battle was not deducted before stars");
          require(
              (Integer) field((DynamicMessage) field(m, "cur_challenge"), "round_count")
                  == (challenge == 1 ? 8 : 16),
              "Final consumed rounds missing");
          result.put(
              challenge == 1 ? "lastBattleRoundsBeforeStars" : "bothHalfRoundsBeforeStars", true);
          if (challenge == 15) settled = true;
          result.put("nativeSettleNotification", true);
        }
        case "LeaveChallengeScRsp" -> {
          if (step == 18) {
            require((Integer) field(m, "retcode") == 2806, "Repeated leave claimed success");
            result.put("repeatedLeaveRejected", true);
            step = 19;
            history(c);
            break;
          }
          require((Integer) field(m, "retcode") == 0, "Leave rejected");
          if (step == 8) {
            challenge = 15;
            step = 9;
            begin(c);
          } else if (step == 17) {
            result.put("actualLeaveCompleted", true);
            step = 18;
            send(c, "LeaveChallengeCsReq", request("LeaveChallengeCsReq"));
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
      var probe = new LoopbackMissionChallengeProbe();
      var credentials =
          JsonParser.parseReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))
              .getAsJsonObject();
      probe.resume = credentials.has("resume") && credentials.get("resume").getAsBoolean();
      probe.verifyDaily =
          credentials.has("verifyDaily") && credentials.get("verifyDaily").getAsBoolean();
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
                    new IllegalStateException("Memory challenge probe disconnected"));
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
          "Memory challenge mission probe failed: "
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
