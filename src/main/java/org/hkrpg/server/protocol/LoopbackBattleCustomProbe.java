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

/**
 * Source event and finite custom-value branches; reports are synthetic and no inputs are replayed.
 */
public final class LoopbackBattleCustomProbe {
  private final ProtocolBridge protocol = ProtocolBridge.forVersion("4.6.0");
  private final CompletableFuture<Map<String, Object>> completed = new CompletableFuture<>();
  private final Map<String, Object> result = new LinkedHashMap<>();
  private boolean resume, formalRaid, trialRaid, mixedRaid, finishRaid, completeSaved;
  private int step, battleId, stageId, chosen;

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
    System.err.println("Custom battle request=" + name + ", step=" + step);
    var packet =
        Unpooled.wrappedBuffer(
            PacketCodec.encode(protocol.clientCommand(name), new byte[0], b.build().toByteArray()));
    try {
      c.write(packet);
    } finally {
      packet.release();
    }
  }

  private void query(Ukcp c) {
    send(c, "GetMissionDataCsReq", request("GetMissionDataCsReq"));
  }

  private void current(Ukcp c) {
    send(c, "GetCurBattleInfoCsReq", request("GetCurBattleInfoCsReq"));
  }

  private void startRaid(Ukcp c, int next) {
    step = next;
    var b = request("StartRaidCsReq");
    set(b, "raid_id", 40235);
    if (trialRaid && next != 30) {
      var choices = b.getDescriptorForType().findFieldByName("NIMDEPFJJNI");
      if (mixedRaid) {
        var formal = DynamicMessage.newBuilder(choices.getMessageType());
        set(formal, "id", 8001);
        var type = formal.getDescriptorForType().findFieldByName("avatar_type");
        formal.setField(type, type.getEnumType().findValueByName("AvatarType_AvatarFormalType"));
        b.addRepeatedField(choices, formal.build());
      }
      var avatar = DynamicMessage.newBuilder(choices.getMessageType());
      set(avatar, "id", 1011209);
      var type = avatar.getDescriptorForType().findFieldByName("avatar_type");
      avatar.setField(type, type.getEnumType().findValueByName("AvatarType_AvatarTrialType"));
      b.addRepeatedField(choices, avatar.build());
    }
    send(c, "StartRaidCsReq", b);
  }

  private void precedingTalk(Ukcp c) {
    var b = request("FinishTalkMissionCsReq");
    set(b, "sub_mission_id", 202200501);
    set(b, "talk_str", "Mission_202200501");
    send(c, "FinishTalkMissionCsReq", b);
  }

  private void tailTalk(Ukcp c, int id, int next) {
    step = next;
    var b = request("FinishTalkMissionCsReq");
    set(b, "sub_mission_id", id);
    set(b, "talk_str", "Mission_" + id);
    send(c, "FinishTalkMissionCsReq", b);
  }

  private void completedProgress(DynamicMessage m, Ukcp c) {
    require(
        (Integer) field(m, "retcode") == 0
            && ((List<?>) field(m, "finished_main_mission_id_list")).contains(2022005),
        "Source raid main did not actually complete");
    var goals = new HashMap<Integer, Integer>();
    for (var raw : (List<?>) field(m, "mission_list")) {
      var row = (DynamicMessage) raw;
      goals.put((Integer) field(row, "id"), (Integer) field(row, "progress"));
    }
    for (int id : new int[] {202200501, 202200502, 202200503, 202200504, 202200505})
      require(Objects.equals(goals.get(id), 1), "Missing actual source tail " + id);
    require(
        Objects.equals(goals.get(202200506), chosen)
            && Objects.equals(goals.get(202200507), 1 - chosen),
        "Completion changed selected branch");
    for (int id :
        new int[] {202200501, 202200502, 202200503, 202200504, 202200505, 202200506, 202200507})
      result.put(Integer.toString(id), goals.get(id));
    result.put("sourceMainAndTailCompleted", true);
    result.put("parentStillDoing", false);
    step = 46;
    send(c, "GetRaidInfoCsReq", request("GetRaidInfoCsReq"));
  }

  private void formalParty(DynamicMessage lineup) {
    var rows = (List<?>) field(lineup, "avatar_list");
    require(rows.size() == (mixedRaid ? 2 : 1), "Selected raid roster missing");
    if (mixedRaid) {
      var formal = (DynamicMessage) rows.getFirst();
      require(
          (Integer) field(formal, "id") == 8001
              && ((com.google.protobuf.Descriptors.EnumValueDescriptor)
                      field(formal, "avatar_type"))
                  .getName()
                  .equals("AvatarType_AvatarFormalType"),
          "Owned formal raid member changed");
      result.put("mixedFormalMember", 8001);
      result.put("selectedRoster", List.of(8001, 1011209));
    }
    var avatar = (DynamicMessage) rows.getLast();
    require(
        (Integer) field(avatar, "id") == (trialRaid ? 1011209 : 1209)
            && ((com.google.protobuf.Descriptors.EnumValueDescriptor) field(avatar, "avatar_type"))
                .getName()
                .equals(trialRaid ? "AvatarType_AvatarTrialType" : "AvatarType_AvatarFormalType"),
        "Source selected actor identity changed");
    require(
        ((List<?>) field(lineup, "story_line_avatar_id_list")).isEmpty(), "Unexpected story trial");
    if (trialRaid) {
      require(
          ((com.google.protobuf.Descriptors.EnumValueDescriptor) field(lineup, "extra_lineup_type"))
                  .getNumber()
              == 13,
          "Trial raid party lost its extra lineup type");
      result.put("trialRequiredActor", 1209);
      result.put("selectedTrial", 1011209);
    } else result.put("formalRequiredRole", 1209);
  }

  private void ordinaryTrialReturn(DynamicMessage lineup) {
    var rows = (List<?>) field(lineup, "avatar_list");
    require(rows.size() == 1, "Ordinary return roster changed");
    var avatar = (DynamicMessage) rows.getFirst();
    require(
        (Integer) field(avatar, "id") == 8001
            && ((com.google.protobuf.Descriptors.EnumValueDescriptor) field(avatar, "avatar_type"))
                .getName()
                .equals("AvatarType_AvatarFormalType"),
        "Raid trial leaked into ordinary roster");
    result.put("ordinaryRosterRestored", true);
    result.put("trialRequiredActor", 1209);
    result.put("selectedTrial", 1011209);
    if (mixedRaid) {
      result.put("mixedFormalMember", 8001);
      result.put("selectedRoster", List.of(8001, 1011209));
    }
  }

  private void formalStorage(Ukcp c, int next) {
    step = next;
    send(c, "GetAvatarDataCsReq", request("GetAvatarDataCsReq"));
  }

  private void raidScene(DynamicMessage scene) {
    require(
        (Integer) field(scene, "plane_id") == 40235
            && (Integer) field(scene, "floor_id") == 40235001
            && (Integer) field(scene, "entry_id") == 40235001,
        "Authored raid location changed");
    result.put("sourceRaidScene", true);
  }

  private void finish(Ukcp c, int variant) {
    var b = request("PVEBattleResultCsReq");
    set(b, "battle_id", variant == 1 ? battleId + 1 : battleId);
    set(b, "stage_id", stageId);
    var end = b.getDescriptorForType().findFieldByName("end_status");
    b.setField(end, end.getEnumType().findValueByName("BATTLE_END_WIN"));
    var stats = request("BattleStatistics");
    var values = stats.getDescriptorForType().findFieldByName("custom_values");
    var row = DynamicMessage.newBuilder(values.getMessageType());
    set(row, "key", "_PlayerWin");
    set(row, "value", variant == 3 ? Float.NaN : (float) chosen);
    stats.addRepeatedField(values, row.build());
    if (variant == 2) {
      set(row, "value", (float) (1 - chosen));
      stats.addRepeatedField(values, row.build());
    }
    set(b, "stt", stats.build());
    send(c, "PVEBattleResultCsReq", b);
  }

  private void progress(DynamicMessage m, boolean finished) {
    require((Integer) field(m, "retcode") == 0, "Mission query rejected");
    var rows = new HashMap<Integer, DynamicMessage>();
    for (var value : (List<?>) field(m, "mission_list")) {
      var row = (DynamicMessage) value;
      require(rows.put((Integer) field(row, "id"), row) == null, "Duplicate objective");
    }
    for (int id : new int[] {202200502, 202200506, 202200507}) {
      require(rows.containsKey(id), "Source objective missing " + id);
      int expected = !finished ? 0 : id == 202200502 ? 1 : id == 202200506 ? chosen : 1 - chosen;
      require((Integer) field(rows.get(id), "progress") == expected, "Wrong source branch " + id);
      result.put(Integer.toString(id), expected);
    }
    if (finished) require(rows.containsKey(202200503), "Selected branch did not unlock next talk");
    require(
        !((List<?>) field(m, "finished_main_mission_id_list")).contains(2022005),
        "Parent story falsely completed");
    result.put("parentStillDoing", true);
  }

  private void done() {
    result.put("chosenValue", chosen);
    result.put("battleId", battleId);
    result.put("stageId", stageId);
    result.put("mode", resume ? "resume" : "fresh");
    result.put("passed", true);
    result.put("clientGameplayVerified", false);
    completed.complete(result);
  }

  private void receive(ByteBuf bytes, Ukcp c) throws Exception {
    while (bytes.isReadable()) {
      var frame = PacketCodec.decodeNext(bytes);
      require(frame != null, "Truncated custom battle frame");
      if (formalRaid && frame.cmdId() == 719) {
        require(step == 33, "Unexpected formal lineup response");
        var input = com.google.protobuf.CodedInputStream.newInstance(frame.body());
        int code = 0;
        while (!input.isAtEnd()) {
          int tag = input.readTag();
          if (tag == 72) code = input.readUInt32();
          else if (tag == 56) require(input.readUInt32() == 0, "Wrong formal lineup index");
          else throw new IllegalStateException("Unknown formal lineup response field");
        }
        require(code == 0, "Owned formal lineup rejected");
        startRaid(c, 34);
        continue;
      }
      String name =
          List.of(
                  "PlayerGetTokenScRsp",
                  "PlayerLoginScRsp",
                  "PlayerLoginFinishScRsp",
                  "EnterSceneScRsp",
                  "AcceptMainMissionScRsp",
                  "FinishTalkMissionScRsp",
                  "GetMissionDataScRsp",
                  "SceneEnterStageScRsp",
                  "PVEBattleResultScRsp",
                  "GetCurBattleInfoScRsp",
                  "StartRaidScRsp",
                  "MusicRhythmStartLevelScRsp",
                  "MusicRhythmFinishLevelScRsp",
                  "GetCurLineupDataScRsp",
                  "GetAvatarDataScRsp",
                  "GetCurSceneInfoScRsp",
                  "LeaveRaidScRsp",
                  "GetRaidInfoScRsp")
              .stream()
              .filter(n -> protocol.clientCommand(n) == frame.cmdId())
              .findFirst()
              .orElse(null);
      if (name == null) continue;
      System.err.println("Custom battle response=" + name + ", step=" + step);
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
            step = completeSaved ? 48 : 20;
            query(c);
          } else {
            var b = request("EnterSceneCsReq");
            set(b, "entry_id", 1010101);
            send(c, "EnterSceneCsReq", b);
          }
        }
        case "EnterSceneScRsp" -> {
          require((Integer) field(m, "retcode") == 0, "Isolated entrance rejected");
          var b = request("AcceptMainMissionCsReq");
          if (formalRaid) step = 29;
          set(b, "main_mission_id", formalRaid ? 2022004 : 2022005);
          send(c, "AcceptMainMissionCsReq", b);
        }
        case "AcceptMainMissionScRsp" -> {
          if (formalRaid) {
            if (step == 29) {
              require((Integer) field(m, "retcode") == 0, "Isolated parent seed rejected");
              step = 0;
              var b = request("AcceptMainMissionCsReq");
              set(b, "main_mission_id", 2022005);
              send(c, "AcceptMainMissionCsReq", b);
              break;
            }
            require(
                (Integer) field(m, "retcode") != 0, "IsInRaid gate bypassed by normal acceptance");
            result.put("normalAcceptanceRejected", true);
            startRaid(c, 30);
            break;
          }
          require((Integer) field(m, "retcode") == 0, "Isolated main acceptance rejected");
          precedingTalk(c);
        }
        case "StartRaidScRsp" -> {
          if (step == 30) {
            require(
                (Integer) field(m, "retcode")
                    == emu.lunarcore.server.packet.Retcode.RAID_AVATAR_NOT_MATCH.getVal(),
                "Missing source actor did not reject entry");
            result.put("missingRoleRejected", true);
            if (trialRaid) {
              startRaid(c, 34);
              break;
            }
            step = 31;
            var b = request("MusicRhythmStartLevelCsReq");
            set(b, "level_id", 101);
            send(c, "MusicRhythmStartLevelCsReq", b);
          } else {
            require(
                step == 34 && (Integer) field(m, "retcode") == 0,
                "Formal source raid entry rejected");
            var payload = (DynamicMessage) field(m, "scene");
            require((Integer) field(payload, "raid_id") == 40235, "Wrong raid identity");
            raidScene((DynamicMessage) field(payload, "MOPHKIGKDIK"));
            formalParty((DynamicMessage) field(payload, "lineup"));
            if (trialRaid) formalStorage(c, 35);
            else precedingTalk(c);
          }
        }
        case "MusicRhythmStartLevelScRsp" -> {
          require(
              formalRaid && step == 31 && (Integer) field(m, "retcode") == 0,
              "Fixture role grant start rejected");
          step = 32;
          var b = request("MusicRhythmFinishLevelCsReq");
          set(b, "role_star", 1);
          send(c, "MusicRhythmFinishLevelCsReq", b);
        }
        case "MusicRhythmFinishLevelScRsp" -> {
          require(
              formalRaid && step == 32 && (Integer) field(m, "retcode") == 0,
              "Fixture formal role grant rejected");
          step = 33;
          var b = request("ReplaceLineupCsReq");
          var slots = b.getDescriptorForType().findFieldByName("lineup_slot_list");
          var slot = DynamicMessage.newBuilder(slots.getMessageType());
          set(slot, "id", 1209);
          var type = slot.getDescriptorForType().findFieldByName("avatar_type");
          slot.setField(type, type.getEnumType().findValueByName("AvatarType_AvatarFormalType"));
          b.addRepeatedField(slots, slot.build());
          send(c, "ReplaceLineupCsReq", b);
        }
        case "GetCurLineupDataScRsp" -> {
          if (step == 49) {
            require((Integer) field(m, "retcode") == 0, "Completed formal roster query rejected");
            if (trialRaid) {
              ordinaryTrialReturn((DynamicMessage) field(m, "lineup"));
              formalStorage(c, 50);
              break;
            }
            formalParty((DynamicMessage) field(m, "lineup"));
            step = 51;
            current(c);
            break;
          }
          require(
              formalRaid && step == 40 && (Integer) field(m, "retcode") == 0,
              "Saved formal roster query rejected");
          formalParty((DynamicMessage) field(m, "lineup"));
          step = 41;
          send(c, "GetCurSceneInfoCsReq", request("GetCurSceneInfoCsReq"));
        }
        case "GetCurSceneInfoScRsp" -> {
          if (step == 47) {
            require(
                (Integer) field(m, "retcode") == 0
                    && (Integer) field((DynamicMessage) field(m, "scene"), "entry_id") == 2023101,
                "Completed raid did not return to actual source entrance");
            result.put("actualFinishedReturnRestored", true);
            step = 49;
            send(c, "GetCurLineupDataCsReq", request("GetCurLineupDataCsReq"));
            break;
          }
          require(
              formalRaid && step == 41 && (Integer) field(m, "retcode") == 0,
              "Saved raid scene query rejected");
          raidScene((DynamicMessage) field(m, "scene"));
          result.put(trialRaid ? "raidAndTrialTeamRestored" : "raidAndFormalTeamRestored", true);
          if (finishRaid) tailTalk(c, 202200503, 42);
          else if (trialRaid) formalStorage(c, 52);
          else done();
        }
        case "GetAvatarDataScRsp" -> {
          require(trialRaid && (Integer) field(m, "retcode") == 0, "Formal storage query rejected");
          var ids =
              ((List<?>) field(m, "avatar_list"))
                  .stream()
                      .map(row -> (Integer) field((DynamicMessage) row, "base_avatar_id"))
                      .toList();
          require(ids.equals(List.of(8001)), "Raid trial was granted as a formal avatar");
          result.put("formalStorageUnchanged", true);
          if (step == 35) precedingTalk(c);
          else if (step == 50) {
            step = 51;
            current(c);
          } else if (step == 52) done();
          else throw new IllegalStateException("Unexpected formal storage step " + step);
        }
        case "LeaveRaidScRsp" -> {
          require(
              step == 45 && (Integer) field(m, "retcode") == 0,
              "Source completed raid could not leave");
          step = 48;
          query(c);
        }
        case "GetRaidInfoScRsp" -> {
          require(step == 46 && (Integer) field(m, "retcode") == 0, "Finished raid query rejected");
          boolean found = false;
          for (var raw : (List<?>) field(m, "finished_raid_info_list")) {
            var row = (DynamicMessage) raw;
            if ((Integer) field(row, "raid_id") == 40235) {
              require(
                  !found && (Integer) field(row, "world_level") == 0,
                  "Duplicated or wrong completed raid");
              found = true;
            }
          }
          require(found, "Actual source completion omitted from history");
          result.put("actualSourceRaidCompletionRecorded", true);
          step = 47;
          send(c, "GetCurSceneInfoCsReq", request("GetCurSceneInfoCsReq"));
        }
        case "FinishTalkMissionScRsp" -> {
          require((Integer) field(m, "retcode") == 0, "Source preceding talk rejected");
          if (step == 42) {
            tailTalk(c, 202200504, 43);
            break;
          }
          if (step == 43) {
            tailTalk(c, 202200505, 44);
            break;
          }
          if (step == 44) {
            step = 45;
            var b = request("LeaveRaidCsReq");
            set(b, "raid_id", 40235);
            send(c, "LeaveRaidCsReq", b);
            break;
          }
          step = 1;
          query(c);
        }
        case "GetMissionDataScRsp" -> {
          if (step == 48) {
            completedProgress(m, c);
          } else if (step == 1) {
            progress(m, false);
            step = 2;
            var b = request("SceneEnterStageCsReq");
            set(b, "event_id", 20241054);
            send(c, "SceneEnterStageCsReq", b);
          } else if (step == 12) {
            progress(m, true);
            step = 13;
            current(c);
          } else if (step == 20) {
            progress(m, true);
            result.put("savedBranchesRestored", true);
            step = 21;
            finish(c, 0);
          } else throw new IllegalStateException("Unexpected mission step " + step);
        }
        case "SceneEnterStageScRsp" -> {
          require(step == 2 && (Integer) field(m, "retcode") == 0, "Source event entry rejected");
          var battle = (DynamicMessage) field(m, "battle_info");
          battleId = (Integer) field(battle, "battle_id");
          stageId = (Integer) field(battle, "stage_id");
          require(stageId == 202410540, "Wrong source event stage");
          if (trialRaid) {
            var avatars = (List<?>) field(battle, "battle_avatar_list");
            require(avatars.size() == (mixedRaid ? 2 : 1), "Selected trial battle roster missing");
            if (mixedRaid) {
              var formal = (DynamicMessage) avatars.getFirst();
              require(
                  (Integer) field(formal, "id") == 8001
                      && ((com.google.protobuf.Descriptors.EnumValueDescriptor)
                              field(formal, "avatar_type"))
                          .getName()
                          .equals("AvatarType_AvatarFormalType"),
                  "Owned formal battle member changed");
              result.put("sourceMixedBattleRoster", true);
            }
            var avatar = (DynamicMessage) avatars.getLast();
            require(
                (Integer) field(avatar, "id") == 1011209
                    && (Integer) field(avatar, "level") == 40
                    && ((com.google.protobuf.Descriptors.EnumValueDescriptor)
                            field(avatar, "avatar_type"))
                        .getName()
                        .equals("AvatarType_AvatarTrialType"),
                "Trial battle actor identity changed");
            var gear = (List<?>) field(avatar, "equipment_list");
            require(
                gear.size() == 1
                    && (Integer) field((DynamicMessage) gear.getFirst(), "id") == 23012
                    && (Integer) field((DynamicMessage) gear.getFirst(), "level") == 40,
                "Source trial equipment changed");
            require(
                !((List<?>) field(avatar, "skilltree_list")).isEmpty(),
                "Source trial skills missing");
            result.put("sourceTrialBattleActorEquipmentAndSkills", true);
          }
          var waves = (List<?>) field(battle, "monster_wave_list");
          require(waves.size() == 1, "Wrong source wave count");
          var monsters = (List<?>) field((DynamicMessage) waves.getFirst(), "monster_list");
          require(
              monsters.size() == 1
                  && (Integer) field((DynamicMessage) monsters.getFirst(), "monster_id") == 2002010,
              "Wrong source monster");
          result.put("sourceStageAndRoster", true);
          step = 3;
          finish(c, 1);
        }
        case "PVEBattleResultScRsp" -> {
          int code = (Integer) field(m, "retcode");
          if (step == 3 || step == 6 || step == 8) {
            require(code != 0, "Invalid report accepted at " + step);
            result.put(
                step == 3
                    ? "wrongIdentityRejected"
                    : step == 6 ? "duplicateKeyRejected" : "nonFiniteRejected",
                true);
            step = step == 3 ? 5 : step == 6 ? 7 : 9;
            current(c);
          } else if (step == 10) {
            require(code == 0, "Valid source report rejected");
            step = 11;
            finish(c, 0);
          } else if (step == 11) {
            require(code != 0, "Repeated result accepted");
            result.put("repeatedResultRejected", true);
            step = 12;
            query(c);
          } else if (step == 21) {
            require(code != 0, "Previous battle replayed after login");
            result.put("savedBattleRetryRejected", true);
            step = 22;
            current(c);
          } else throw new IllegalStateException("Unexpected settlement step " + step);
        }
        case "GetCurBattleInfoScRsp" -> {
          if (step == 51) {
            require((Integer) field(m, "retcode") != 0, "Completed raid retained a battle");
            result.put("noActiveBattle", true);
            done();
          } else if (step == 13 || step == 22) {
            require((Integer) field(m, "retcode") != 0, "Settled battle remained active");
            result.put("noActiveBattle", true);
            if (formalRaid) {
              step = 40;
              send(c, "GetCurLineupDataCsReq", request("GetCurLineupDataCsReq"));
            } else done();
          } else {
            require((Integer) field(m, "retcode") == 0, "Bad report destroyed actual battle");
            var b = (DynamicMessage) field(m, "battle_info");
            require(
                (Integer) field(b, "battle_id") == battleId
                    && (Integer) field(b, "stage_id") == stageId,
                "Battle identity changed");
            result.put("invalidReportsPreservedBattle", true);
            if (step == 5) {
              step = 6;
              finish(c, 2);
            } else if (step == 7) {
              step = 8;
              finish(c, 3);
            } else if (step == 9) {
              step = 10;
              finish(c, 0);
            } else throw new IllegalStateException("Unexpected active battle step " + step);
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
      var probe = new LoopbackBattleCustomProbe();
      var credentials =
          JsonParser.parseReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))
              .getAsJsonObject();
      probe.resume = credentials.has("resume") && credentials.get("resume").getAsBoolean();
      probe.formalRaid =
          credentials.has("formalRaid") && credentials.get("formalRaid").getAsBoolean();
      probe.trialRaid = credentials.has("trialRaid") && credentials.get("trialRaid").getAsBoolean();
      probe.mixedRaid = credentials.has("mixedRaid") && credentials.get("mixedRaid").getAsBoolean();
      require(!probe.mixedRaid || probe.trialRaid, "Mixed raid requires declared trial mode");
      require(!probe.trialRaid || probe.formalRaid, "Trial raid needs source raid mode");
      probe.finishRaid =
          credentials.has("finishRaid") && credentials.get("finishRaid").getAsBoolean();
      probe.completeSaved =
          credentials.has("completeSaved") && credentials.get("completeSaved").getAsBoolean();
      probe.chosen = credentials.get("chosenValue").getAsInt();
      require(probe.chosen == 0 || probe.chosen == 1, "Unsupported report fixture");
      if (probe.resume) {
        probe.battleId = credentials.get("battleId").getAsInt();
        probe.stageId = credentials.get("stageId").getAsInt();
      }
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
                    new IllegalStateException("Custom battle probe disconnected"));
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
          "Custom battle probe failed: " + e.getClass().getSimpleName() + ": " + e.getMessage());
    } finally {
      if (connection != null) connection.close();
      client.stop();
    }
    System.exit(exit);
  }
}
