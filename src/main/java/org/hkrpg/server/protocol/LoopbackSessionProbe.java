package org.hkrpg.server.protocol;

import com.google.gson.*;
import com.google.protobuf.DynamicMessage;
import emu.lunarcore.server.packet.PacketCodec;
import emu.lunarcore.server.packet.Retcode;
import io.netty.buffer.*;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import kcp.highway.*;

/**
 * Synthetic 4.6 login and real player snapshots over local UDP/KCP. Credentials arrive on stdin.
 */
public final class LoopbackSessionProbe {
  private final ProtocolBridge protocol = ProtocolBridge.forVersion("4.6.0");
  private final CompletableFuture<Map<String, Object>> completed = new CompletableFuture<>();
  private final Map<Integer, String> expected = new HashMap<>();
  private final Set<String> received = new LinkedHashSet<>();
  private final Map<String, Object> result = new LinkedHashMap<>();
  private int uid;
  private String setNickname;
  private int battleId;
  private int battleSeed;
  private int battleStage;
  private boolean battleVerified;
  private int leaderEntity;
  private int castPhase;
  private int battleRound;
  private boolean finishTalk;
  private boolean pendingActionVerified;
  private boolean roomEntryVerified;
  private String universeMode;
  private boolean abandonUniverse;
  private int universeStep;
  private boolean universeVerified;
  private boolean invalidPropVerified;
  private boolean invalidTeleportVerified;
  private boolean invalidFirstTalkVerified;
  private boolean invalidTalentVerified;
  private boolean unearnedCycleVerified;
  private int trialStep;
  private boolean trialVerified;
  private int trialBattleId, trialBattleStage, trialFinishNotifications;
  private static final int TRIAL_STAGE = 310010;

  private void querySnapshots(Ukcp connection) {
    send(connection, "PlayerLoginFinishCsReq");
    var introQuery =
        DynamicMessage.newBuilder(
            protocol.clientSchema().message("GetFirstTalkByPerformanceNpcCsReq"));
    introQuery.addRepeatedField(
        introQuery.getDescriptorForType().findFieldByName("performance_id_list"), 504036001);
    send(connection, "GetFirstTalkByPerformanceNpcCsReq", introQuery.build());
    var invalidIntro =
        DynamicMessage.newBuilder(
            protocol.clientSchema().message("FinishFirstTalkByPerformanceNpcCsReq"));
    invalidIntro.setField(
        invalidIntro.getDescriptorForType().findFieldByName("performance_id"), Integer.MAX_VALUE);
    send(connection, "FinishFirstTalkByPerformanceNpcCsReq", invalidIntro.build());
    var request = protocol.clientSchema().message("GetAvatarDataCsReq");
    send(
        connection,
        "GetAvatarDataCsReq",
        DynamicMessage.newBuilder(request)
            .setField(request.findFieldByName("is_get_all"), true)
            .build());
    for (String query :
        List.of(
            "GetBagCsReq",
            "GetStoryLineInfoCsReq",
            "GetAllLineupDataCsReq",
            "GetCurSceneInfoCsReq",
            "ContentPackageGetDataCsReq",
            "GetQuestDataCsReq",
            "GetMissionDataCsReq",
            "GetMainMissionCustomValueCsReq",
            "GetDailyActiveInfoCsReq",
            "GetLoginActivityCsReq",
            "GetActivityScheduleConfigCsReq",
            "MusicRhythmDataCsReq",
            "ParkourGetDataCsReq",
            "ParkourGetRankingInfoCsReq",
            "GetRaidInfoCsReq",
            "GetAllSaveRaidCsReq",
            "GetRogueInfoCsReq",
            "GetRogueTalentInfoCsReq",
            "CycleScoreRewardGetDataCsReq")) send(connection, query);
    var parkourStart =
        DynamicMessage.newBuilder(protocol.clientSchema().message("ParkourStartLevelCsReq"));
    parkourStart.setField(parkourStart.getDescriptorForType().findFieldByName("level_id"), 1);
    parkourStart.setField(parkourStart.getDescriptorForType().findFieldByName("CEKKJHBNEOD"), 1);
    send(connection, "ParkourStartLevelCsReq", parkourStart.build());
    var parkourEnd =
        DynamicMessage.newBuilder(protocol.clientSchema().message("ParkourEndLevelCsReq"));
    parkourEnd.setField(parkourEnd.getDescriptorForType().findFieldByName("level_id"), 1);
    parkourEnd.setField(parkourEnd.getDescriptorForType().findFieldByName("CEKKJHBNEOD"), 1);
    parkourEnd.setField(parkourEnd.getDescriptorForType().findFieldByName("time"), 80000);
    parkourEnd.setField(parkourEnd.getDescriptorForType().findFieldByName("total_time"), 85000);
    parkourEnd.setField(parkourEnd.getDescriptorForType().findFieldByName("rank"), 1);
    var reason = parkourEnd.getDescriptorForType().findFieldByName("end_reason");
    parkourEnd.setField(reason, reason.getEnumType().findValueByNumber(2));
    send(connection, "ParkourEndLevelCsReq", parkourEnd.build());
    for (String name : List.of("StartRaidCsReq", "LeaveRaidCsReq", "GetSaveRaidCsReq")) {
      var raid = DynamicMessage.newBuilder(protocol.clientSchema().message(name));
      raid.setField(raid.getDescriptorForType().findFieldByName("raid_id"), 44306001);
      send(connection, name, raid.build());
    }
    var absentItem = DynamicMessage.newBuilder(protocol.clientSchema().message("UseItemCsReq"));
    absentItem.setField(absentItem.getDescriptorForType().findFieldByName("use_item_id"), 401006);
    absentItem.setField(absentItem.getDescriptorForType().findFieldByName("use_item_count"), 1);
    send(connection, "UseItemCsReq", absentItem.build());
    var selection =
        DynamicMessage.newBuilder(protocol.clientSchema().message("FinishCosumeItemMissionCsReq"));
    selection.setField(
        selection.getDescriptorForType().findFieldByName("sub_mission_id"), 434014905);
    var credit = DynamicMessage.newBuilder(protocol.clientSchema().message("Item"));
    credit.setField(credit.getDescriptorForType().findFieldByName("item_id"), 2);
    credit.setField(credit.getDescriptorForType().findFieldByName("num"), 1);
    var selectedItems = DynamicMessage.newBuilder(protocol.clientSchema().message("ItemList"));
    selectedItems.addRepeatedField(
        selectedItems.getDescriptorForType().findFieldByName("item_list"), credit.build());
    selection.setField(
        selection.getDescriptorForType().findFieldByName("item_list"), selectedItems.build());
    send(connection, "FinishCosumeItemMissionCsReq", selection.build());
    var unearnedCycle =
        DynamicMessage.newBuilder(
            protocol.clientSchema().message("CycleScoreRewardTakeRewardCsReq"));
    unearnedCycle.addRepeatedField(
        unearnedCycle.getDescriptorForType().findFieldByName("LLJHJADNDKB"), 1);
    send(connection, "CycleScoreRewardTakeRewardCsReq", unearnedCycle.build());
    var invalidTalent =
        DynamicMessage.newBuilder(protocol.clientSchema().message("EnableRogueTalentCsReq"));
    invalidTalent.setField(
        invalidTalent.getDescriptorForType().findFieldByName("CKHCLKOALKE"), Integer.MAX_VALUE);
    send(connection, "EnableRogueTalentCsReq", invalidTalent.build());
    // Native 4.6 obfuscated command; no pending action exists on this fresh account.
    var choice = DynamicMessage.newBuilder(protocol.clientSchema().message("OFEDNNJIJFL"));
    choice.setField(choice.getDescriptorForType().findFieldByName("MPLGEEGOAMC"), 7654321);
    var packet =
        Unpooled.wrappedBuffer(
            PacketCodec.encode(
                Universe46.PENDING_ACTION_COMMAND, new byte[0], choice.build().toByteArray()));
    try {
      connection.write(packet);
    } finally {
      packet.release();
    }
    var room = DynamicMessage.newBuilder(protocol.clientSchema().message("EnterRogueMapRoomCsReq"));
    room.setField(room.getDescriptorForType().findFieldByName("MAJKFIMPOOF"), 1);
    room.setField(room.getDescriptorForType().findFieldByName("room_id"), 100);
    send(connection, "EnterRogueMapRoomCsReq", room.build());
  }

  private static Object field(DynamicMessage message, String name) {
    return message.getField(message.getDescriptorForType().findFieldByName(name));
  }

  private static void require(boolean condition, String reason) {
    if (!condition) throw new IllegalStateException(reason);
  }

  private void send(Ukcp connection, String name, DynamicMessage message) {
    var bytes =
        Unpooled.wrappedBuffer(
            PacketCodec.encode(protocol.clientCommand(name), new byte[0], message.toByteArray()));
    try {
      connection.write(bytes);
    } finally {
      bytes.release();
    }
  }

  private void send(Ukcp connection, String name) {
    send(
        connection, name, DynamicMessage.newBuilder(protocol.clientSchema().message(name)).build());
  }

  private void receive(ByteBuf bytes, Ukcp connection) throws Exception {
    while (bytes.isReadable()) {
      var frame = PacketCodec.decodeNext(bytes);
      require(frame != null, "Truncated response frame");
      if (frame.cmdId() == protocol.clientCommand("CycleScoreRewardTakeRewardScRsp")) {
        var response =
            protocol.clientSchema().parse("CycleScoreRewardTakeRewardScRsp", frame.body());
        require(
            (Integer) field(response, "retcode")
                == emu.lunarcore.server.packet.Retcode.QUEST_NOT_FINISH.getVal(),
            "Unearned cycle reward was accepted");
        var info = (DynamicMessage) field(response, "NFEDANFBIBE");
        require(
            (Integer) field(info, "PMJGAJGIFOA") == 0
                && ((List<?>) field(info, "OPLNKCOLCKJ")).isEmpty(),
            "Unearned cycle score or rank");
        require(
            ((List<?>) field(response, "NGKOFMBBGNL")).isEmpty()
                && ((List<?>) field((DynamicMessage) field(response, "ENGHMIAMPLG"), "item_list"))
                    .isEmpty(),
            "Rejected cycle claim manufactured rewards");
        unearnedCycleVerified = true;
        result.put("nativeUnearnedCycleRejected", true);
        finishIfReady(connection);
        continue;
      }
      if (frame.cmdId() == protocol.clientCommand("FinishFirstTalkByPerformanceNpcScRsp")) {
        var response =
            protocol.clientSchema().parse("FinishFirstTalkByPerformanceNpcScRsp", frame.body());
        require(
            (Integer) field(response, "retcode")
                == emu.lunarcore.server.packet.Retcode.MISSION_NOT_EXIST.getVal(),
            "Unknown intro performance was accepted");
        require(
            (Integer) field(response, "performance_id") == Integer.MAX_VALUE,
            "Invalid intro echo lost");
        invalidFirstTalkVerified = true;
        result.put("nativeInvalidFirstTalkRejected", true);
        finishIfReady(connection);
        continue;
      }
      if (frame.cmdId() == protocol.clientCommand("InteractPropScRsp")) {
        var response = protocol.clientSchema().parse("InteractPropScRsp", frame.body());
        require(
            (Integer) field(response, "retcode")
                == emu.lunarcore.server.packet.Retcode.SCENE_ENTITY_NOT_EXIST.getVal(),
            "Unknown prop interaction succeeded");
        require(
            (Integer) field(response, "prop_entity_id") == Integer.MAX_VALUE,
            "Invalid interaction lost its entity echo");
        invalidPropVerified = true;
        result.put("nativeInvalidPropRejected", true);
        finishIfReady(connection);
        continue;
      }
      if (frame.cmdId() == protocol.clientCommand("EnterSceneScRsp")) {
        var response = protocol.clientSchema().parse("EnterSceneScRsp", frame.body());
        require((Integer) field(response, "retcode") != 0, "Unknown teleport succeeded");
        require(
            !response.hasField(response.getDescriptorForType().findFieldByName("scene_identifier")),
            "Rejected teleport returned a successful scene");
        invalidTeleportVerified = true;
        result.put("nativeInvalidTeleportRejected", true);
        finishIfReady(connection);
        continue;
      }
      if (frame.cmdId() == protocol.clientCommand("EnterRogueMapRoomScRsp")) {
        var response = protocol.clientSchema().parse("EnterRogueMapRoomScRsp", frame.body());
        require(
            (Integer) field(response, "retcode")
                == emu.lunarcore.server.packet.Retcode.ROGUE_AREA_INVALID.getVal(),
            "An unassigned universe room was accepted");
        require(
            !response.hasField(response.getDescriptorForType().findFieldByName("scene"))
                && !response.hasField(response.getDescriptorForType().findFieldByName("lineup")),
            "Rejected universe entry manufactured a scene/lineup");
        roomEntryVerified = true;
        result.put("nativeUniverseUnassignedRoomRejected", true);
        finishIfReady(connection);
        continue;
      }
      if (frame.cmdId() == protocol.clientCommand("HandleRogueCommonPendingActionScRsp")) {
        var response =
            protocol.clientSchema().parse("HandleRogueCommonPendingActionScRsp", frame.body());
        require(
            (Integer) field(response, "retcode")
                == emu.lunarcore.server.packet.Retcode.REQ_PARA_INVALID.getVal(),
            "No-offer universe choice was accepted");
        require(
            (Integer) field(response, "BFNEPPCNOOI") == 7654321
                && (Integer) field(response, "queue_position") == 0,
            "Wrong failed universe queue echo");
        pendingActionVerified = true;
        result.put("nativeUniverseChoiceRejected", true);
        finishIfReady(connection);
        continue;
      }
      if (frame.cmdId() == protocol.clientCommand("EnableRogueTalentScRsp")) {
        var response = protocol.clientSchema().parse("EnableRogueTalentScRsp", frame.body());
        require((Integer) field(response, "retcode") != 0, "Unknown talent succeeded");
        verifyTalentSnapshot(response);
        invalidTalentVerified = true;
        result.put("nativeInvalidTalentRejected", true);
        finishIfReady(connection);
        continue;
      }
      if (receiveTrial(frame.cmdId(), frame.body(), connection)) {
        finishIfReady(connection);
        continue;
      }
      if (receiveUniverse(frame.cmdId(), frame.body(), connection)) {
        finishIfReady(connection);
        continue;
      }
      if (receiveBattle(frame.cmdId(), frame.body(), connection)) {
        finishIfReady(connection);
        continue;
      }
      String name = expected.get(frame.cmdId());
      if (name == null)
        continue; // Unsolicited notifications are covered separately, not counted as query success.
      var message = protocol.clientSchema().parse(name, frame.body());
      require(message.getUnknownFields().asMap().isEmpty(), "Unknown response fields: " + name);
      if (name.equals("FinishCosumeItemMissionScRsp")) {
        require(
            (Integer) field(message, "retcode") == Retcode.MISSION_NOT_DOING.getVal(),
            "Unaccepted selected-item mission returned success or the wrong error");
        require(
            (Integer) field(message, "sub_mission_id") == 434014905,
            "Selected-item mission response has the wrong identity");
        require(received.add(name), "Repeated selected-item mission response");
        result.put("nativeUnacceptedSelectionRejected", true);
        finishIfReady(connection);
        continue;
      }
      if (name.equals("UseItemScRsp")) {
        require(
            (Integer) field(message, "retcode") == Retcode.ITEM_NOT_EXIST.getVal(),
            "Absent consumable returned success or the wrong failure");
        require(
            (Integer) field(message, "use_item_id") == 401006
                && (Integer) field(message, "use_item_count") == 0,
            "Absent consumable invented a used quantity");
        require(
            ((List<?>) field((DynamicMessage) field(message, "return_data"), "item_list"))
                .isEmpty(),
            "Absent consumable invented a reward");
        require(received.add(name), "Repeated consumable response");
        result.put("nativeAbsentConsumableRejected", true);
        finishIfReady(connection);
        continue;
      }
      if (name.equals("StartRaidScRsp") || name.equals("LeaveRaidScRsp")) {
        require(
            (Integer) field(message, "retcode") != 0, "Closed or unstarted parkour Raid accepted");
        require(received.add(name), "Repeated Raid response");
        if (name.equals("StartRaidScRsp"))
          require(
              !message.hasField(message.getDescriptorForType().findFieldByName("scene")),
              "Rejected Raid invented a scene");
        result.put(
            name.equals("StartRaidScRsp")
                ? "nativeClosedParkourRaidRejected"
                : "nativeUnstartedParkourRaidExitRejected",
            true);
        finishIfReady(connection);
        continue;
      }
      if (name.equals("ParkourStartLevelScRsp") || name.equals("ParkourEndLevelScRsp")) {
        require((Integer) field(message, "retcode") != 0, "Closed or unstarted parkour accepted");
        require(received.add(name), "Repeated parkour response");
        if (name.equals("ParkourEndLevelScRsp"))
          require(
              !(Boolean) field(message, "ADJLGNBJAOC") && !(Boolean) field(message, "AJGOLAKNGPA"),
              "Rejected race manufactured completion");
        result.put(
            name.equals("ParkourStartLevelScRsp")
                ? "nativeClosedParkourStartRejected"
                : "nativeUnstartedParkourEndRejected",
            true);
        finishIfReady(connection);
        continue;
      }
      if (message.getDescriptorForType().findFieldByName("retcode") != null)
        require((Integer) field(message, "retcode") == 0, "Nonzero retcode: " + name);
      require(received.add(name), "Repeated response: " + name);
      switch (name) {
        case "GetStoryLineInfoScRsp" -> {
          require(
              (Integer) field(message, "cur_story_line_id") == 0
                  && ((List<?>) field(message, "unfinished_story_line_id_list")).isEmpty()
                  && ((List<?>) field(message, "trial_avatar_id_list")).isEmpty(),
              "Story query invented a started branch or a trial party");
          result.put("nativeUnstartedStoryQuery", true);
        }
        case "GetSaveRaidScRsp" -> {
          require(
              (Integer) field(message, "raid_id") == 44306001
                  && !(Boolean) field(message, "is_save"),
              "Unsaved parkour Raid invented a saved run");
          result.put("nativeUnsavedParkourRaidQuery", true);
        }
        case "GetAllSaveRaidScRsp" ->
            require(
                ((List<?>) field(message, "raid_data_list")).isEmpty(), "Invented saved Raid rows");
        case "GetRaidInfoScRsp" ->
            require(
                ((List<?>) field(message, "finished_raid_info_list")).isEmpty(),
                "Invented finished Raid rows");
        case "GetFirstTalkByPerformanceNpcScRsp" -> {
          var rows = (List<?>) field(message, "npc_meet_status_list");
          require(rows.size() == 1, "Missing intro status row");
          var row = (DynamicMessage) rows.getFirst();
          require(
              (Integer) field(row, "performance_id") == 504036001
                  && !(Boolean) field(row, "is_meet"),
              "Query manufactured intro completion");
          result.put("nativeFirstTalkQuery", true);
        }
        case "PlayerGetTokenScRsp" -> {
          uid = (Integer) field(message, "uid");
          require(uid > 0, "No player UID");
          result.put("uid", uid);
          send(connection, "PlayerLoginCsReq");
        }
        case "PlayerLoginScRsp" -> {
          var basic = (DynamicMessage) field(message, "basic_info");
          require((Integer) field(basic, "level") > 0, "No player level");
          result.put("nickname", field(basic, "nickname"));
          if (setNickname == null) querySnapshots(connection);
          else {
            var request = protocol.clientSchema().message("SetNicknameCsReq");
            send(
                connection,
                "SetNicknameCsReq",
                DynamicMessage.newBuilder(request)
                    .setField(request.findFieldByName("nickname"), setNickname)
                    .build());
          }
        }
        case "SetNicknameScRsp" -> querySnapshots(connection);
        case "GetAvatarDataScRsp" -> {
          var avatars = (List<?>) field(message, "avatar_list");
          var paths = (List<?>) field(message, "avatar_path_data_info_list");
          require(!avatars.isEmpty() && !paths.isEmpty(), "Missing avatar/path snapshot");
          result.put("avatars", avatars.size());
          result.put("avatarPaths", paths.size());
        }
        case "GetBagScRsp" -> {
          result.put("materials", ((List<?>) field(message, "material_list")).size());
          result.put("equipment", ((List<?>) field(message, "equipment_list")).size());
          result.put("relics", ((List<?>) field(message, "relic_list")).size());
        }
        case "GetAllLineupDataScRsp" -> {
          var lineups = (List<?>) field(message, "lineup_list");
          require(
              lineups.stream()
                  .anyMatch(
                      value -> !((List<?>) field((DynamicMessage) value, "avatar_list")).isEmpty()),
              "No lineup avatar");
          result.put("lineups", lineups.size());
        }
        case "GetCurSceneInfoScRsp" -> {
          var scene = (DynamicMessage) field(message, "scene");
          require((Integer) field(scene, "leader_entity_id") > 0, "No scene leader entity");
          int floor = (Integer) field(scene, "floor_id");
          var identifier = (DynamicMessage) field(scene, "scene_identifier");
          require(
              floor > 0 && floor == (Integer) field(identifier, "floor_id"),
              "Scene identifier mismatch");
          require(!((List<?>) field(scene, "entity_group_list")).isEmpty(), "Missing scene groups");
          result.put("floor", floor);
          result.put("plane", field(scene, "plane_id"));
          result.put("entry", field(scene, "entry_id"));
          result.put("sceneGroups", ((List<?>) field(scene, "entity_group_list")).size());
          leaderEntity = (Integer) field(scene, "leader_entity_id");
          var anchors =
              DynamicMessage.newBuilder(protocol.clientSchema().message("GetUnlockTeleportCsReq"));
          anchors.addRepeatedField(
              anchors.getDescriptorForType().findFieldByName("entry_id_list"),
              field(scene, "entry_id"));
          send(connection, "GetUnlockTeleportCsReq", anchors.build());
          var map =
              DynamicMessage.newBuilder(protocol.clientSchema().message("GetSceneMapInfoCsReq"));
          var mapIdentifier =
              DynamicMessage.newBuilder(protocol.clientSchema().message("SceneIdentifier"));
          mapIdentifier.setField(
              mapIdentifier.getDescriptorForType().findFieldByName("floor_id"), floor);
          map.addRepeatedField(
              map.getDescriptorForType().findFieldByName("scene_identifiers"),
              mapIdentifier.build());
          send(connection, "GetSceneMapInfoCsReq", map.build());
          var interact =
              DynamicMessage.newBuilder(protocol.clientSchema().message("InteractPropCsReq"));
          interact.setField(
              interact.getDescriptorForType().findFieldByName("prop_entity_id"), Integer.MAX_VALUE);
          interact.setField(interact.getDescriptorForType().findFieldByName("interact_id2"), 1);
          interact.setField(
              interact.getDescriptorForType().findFieldByName("interact_id"), 1L << 40);
          send(connection, "InteractPropCsReq", interact.build());
          var teleport =
              DynamicMessage.newBuilder(protocol.clientSchema().message("EnterSceneCsReq"));
          teleport.setField(
              teleport.getDescriptorForType().findFieldByName("entry_id"),
              field(scene, "entry_id"));
          teleport.setField(
              teleport.getDescriptorForType().findFieldByName("teleport_id"), Integer.MAX_VALUE);
          send(connection, "EnterSceneCsReq", teleport.build());
          sendCast(connection, 2);
        }
        case "GetUnlockTeleportScRsp" ->
            result.put(
                "nativeUnlockedAnchors",
                ((List<?>) field(message, "unlocked_teleport_list")).size());
        case "GetSceneMapInfoScRsp" -> {
          var maps = (List<?>) field(message, "scene_map_info");
          require(!maps.isEmpty(), "Missing native scene map");
          require(
              maps.stream()
                  .allMatch(
                      row -> field((DynamicMessage) row, "floor_id").equals(result.get("floor"))),
              "Native map returned another floor");
          require(
              maps.stream()
                  .allMatch(
                      row ->
                          ((List<?>) field((DynamicMessage) row, "lighten_section_list"))
                              .isEmpty()),
              "Native map manufactured explored sections");
          result.put("nativeMapQueries", maps.size());
        }
        case "ContentPackageGetDataScRsp" -> {
          var data = (DynamicMessage) field(message, "data");
          var packages = (List<?>) field(data, "content_package_list");
          require(!packages.isEmpty(), "Missing content catalog");
          require(
              packages.stream()
                  .allMatch(
                      value ->
                          field((DynamicMessage) value, "status")
                              .toString()
                              .equals("ContentPackageStatus_Init")),
              "Unexpected canned completion");
          result.put("contentPackages", packages.size());
        }
        case "GetQuestDataScRsp" -> result.put("questSnapshot", true);
        case "GetMainMissionCustomValueScRsp" -> result.put("missionCustomValueQuery", true);
        case "ParkourGetDataScRsp" -> {
          require(
              ((List<?>) field(message, "CGIPFMLDPNA")).isEmpty(),
              "Unplayed parkour levels marked complete");
          result.put("nativeUnplayedParkourQuery", true);
        }
        case "ParkourGetRankingInfoScRsp" -> {
          require(
              message.hasField(message.getDescriptorForType().findFieldByNumber(12)),
              "Parkour aggregate object missing");
          var aggregate = (DynamicMessage) field(message, "LGFNFABAAGA");
          require(
              ((List<?>) field(aggregate, "BEDHEKAGAPE")).isEmpty(), "Invented aggregate players");
          var boards = (List<?>) field(message, "JNMFKJHCOEG");
          var levels = new java.util.TreeSet<Integer>();
          for (var value : boards) {
            var board = (DynamicMessage) value;
            levels.add((Integer) field(board, "level_id"));
            require(((List<?>) field(board, "BEDHEKAGAPE")).isEmpty(), "Invented ranking players");
          }
          require(
              levels.equals(Set.of(10, 11, 12)) && boards.size() == 3,
              "Wrong resource ranking levels");
          result.put("nativeResourceParkourRanking", true);
        }
        case "MusicRhythmDataScRsp" -> {
          require(
              ((List<?>) field(message, "level_data_list")).isEmpty(),
              "Unconfigured rhythm activity was opened");
          require((Integer) field(message, "cur_level_id") == 0, "Unexpected started rhythm level");
          result.put("closedMusicActivityQuery", true);
        }
        case "GetRogueInfoScRsp" -> {
          var game = (DynamicMessage) field(message, "rogue_game_info");
          boolean current =
              game.hasField(game.getDescriptorForType().findFieldByName("CFDIKLBINHK"));
          require(current == "resume".equals(universeMode), "Universe saved-run presence changed");
          if (current) universeSnapshot(game);
          var basic = (DynamicMessage) field(game, "LFLCDNGALME");
          var currency = (DynamicMessage) field(basic, "NIKDPODBIEF");
          require((Integer) field(currency, "GEBGGMAGDJB") == 0, "Unearned talent points");
          var areas = (DynamicMessage) field(basic, "ACLKMFCDMED");
          var rows = (List<?>) field(areas, "OJGOAOPLMLB");
          require(!rows.isEmpty(), "Missing active resource season catalog");
          require(
              rows.stream()
                  .noneMatch(
                      row ->
                          field((DynamicMessage) row, "area_status")
                              .toString()
                              .equals("RogueAreaStatus_FirstPass")),
              "A query manufactured first clears");
          result.put("rogueCatalogAreas", rows.size());
        }
        case "GetRogueTalentInfoScRsp" -> {
          verifyTalentSnapshot(message);
          result.put("nativeTalentQuery", true);
        }
        case "CycleScoreRewardGetDataScRsp" -> {
          require((Integer) field(message, "retcode") == 0, "Resource cycle query failed");
          var info = (DynamicMessage) field(message, "NFEDANFBIBE");
          long start = (Long) field(info, "start_time"), end = (Long) field(info, "end_time");
          require(start > 0 && end - start == 7 * 86400L, "Invalid resource cycle window");
          require(
              (Integer) field(info, "PMJGAJGIFOA") == 0
                  && ((List<?>) field(info, "OPLNKCOLCKJ")).isEmpty(),
              "Query manufactured cycle earnings");
          result.put("nativeCycleQuery", true);
          result.put("cycleStart", start);
          result.put("cycleEnd", end);
        }
        case "GetMissionDataScRsp" -> {
          var missions = (List<?>) field(message, "mission_list");
          require(!missions.isEmpty(), "No accepted mission objectives");
          result.put("missionObjectives", missions.size());
          result.put(
              "finishedMainMissions",
              ((List<?>) field(message, "finished_main_mission_id_list")).size());
          var objective =
              missions.stream()
                  .map(v -> (DynamicMessage) v)
                  .filter(v -> (Integer) field(v, "id") == 100010102)
                  .findFirst()
                  .orElseThrow();
          result.put("introTalkStatus", field(objective, "status").toString());
          if (finishTalk) {
            var type = protocol.clientSchema().message("FinishTalkMissionCsReq");
            send(
                connection,
                "FinishTalkMissionCsReq",
                DynamicMessage.newBuilder(type)
                    .setField(type.findFieldByName("sub_mission_id"), 100010102)
                    .setField(type.findFieldByName("talk_str"), "Mission_100010102")
                    .build());
          }
        }
        case "FinishTalkMissionScRsp" -> {
          result.put("introTalkStatus", "MissionStatus_MissionFinish");
          result.put("talkMutation", true);
        }
        case "GetDailyActiveInfoScRsp" -> {
          require((Integer) field(message, "daily_active_point") == 0, "Unearned daily points");
          var levels = (List<?>) field(message, "daily_active_level_list");
          require(!levels.isEmpty(), "Missing daily reward levels");
          require(
              levels.stream().noneMatch(v -> (Boolean) field((DynamicMessage) v, "is_has_taken")),
              "Unearned daily reward already taken");
          result.put("dailyLevels", levels.size());
        }
        case "GetLoginActivityScRsp" ->
            result.put("loginActivities", ((List<?>) field(message, "login_activity_list")).size());
        case "GetActivityScheduleConfigScRsp" -> {
          var schedules = (List<?>) field(message, "schedule_data");
          result.put("activityWindows", schedules.size());
          boolean trialScheduled =
              schedules.stream()
                  .map(DynamicMessage.class::cast)
                  .anyMatch(
                      row ->
                          (Integer) field(row, "panel_id") == 20003
                              && (Integer) field(row, "activity_id") == 2000401);
          require(trialScheduled, "Actual trial panel/module schedule was not advertised");
          result.put("nativeTrialActivitySchedule", true);
        }
        default -> {}
      }
      finishIfReady(connection);
    }
  }

  private void finishIfReady(Ukcp connection) {
    if (received.size() == expected.size()
        && battleVerified
        && pendingActionVerified
        && roomEntryVerified
        && invalidPropVerified
        && invalidFirstTalkVerified
        && invalidTalentVerified
        && unearnedCycleVerified
        && invalidTeleportVerified) {
      if (!trialVerified) {
        if (trialStep == 0) {
          trialStep = -1;
          send(connection, "GetTrialActivityDataCsReq");
        }
        return;
      }
      if (universeMode != null && !universeVerified) {
        if (universeStep == 0) {
          universeStep = 1;
          String name = universeMode.equals("start") ? "StartRogueCsReq" : "EnterRogueCsReq";
          var request = DynamicMessage.newBuilder(protocol.clientSchema().message(name));
          request.setField(request.getDescriptorForType().findFieldByName("area_id"), 100);
          if (universeMode.equals("start"))
            request.addRepeatedField(
                request.getDescriptorForType().findFieldByName("NOEPBNMJFGL"),
                emu.lunarcore.GameConstants.TRAILBLAZER_AVATAR_ID);
          send(connection, name, request.build());
        }
        return;
      }
      result.put("passed", true);
      result.put("wireVersion", "4.6.0");
      result.put("checks", received);
      result.put("clientGameplayVerified", false);
      completed.complete(result);
    }
  }

  private void sendTrial(Ukcp connection, String name, int stage) {
    var type = protocol.clientSchema().message(name);
    send(
        connection,
        name,
        DynamicMessage.newBuilder(type).setField(type.findFieldByName("stage_id"), stage).build());
  }

  private boolean receiveTrial(int command, byte[] bytes, Ukcp connection) throws Exception {
    if ((trialStep == -1 || trialStep == 13 || trialStep == 16)
        && command == protocol.clientCommand("GetTrialActivityDataScRsp")) {
      var response = protocol.clientSchema().parse("GetTrialActivityDataScRsp", bytes);
      require(
          (Integer) field(response, "retcode") == 0
              && (Integer) field(response, "activity_stage_id") == 0,
          "Trial completion query failed or fabricated an active stage");
      var rows = (List<?>) field(response, "trial_activity_info_list");
      boolean alreadyTaken = "resume".equals(universeMode);
      if (trialStep == -1 && !alreadyTaken) {
        require(rows.isEmpty(), "Query manufactured completed trials");
      } else {
        require(rows.size() == 1, "Saved trial completion was lost or duplicated");
        var info = (DynamicMessage) rows.getFirst();
        require(
            (Integer) field(info, "stage_id") == TRIAL_STAGE
                && (Boolean) field(info, "taken_reward") == (trialStep == 16 || alreadyTaken),
            "Trial completion/taken state changed across query or reconnect");
      }
      if (trialStep == -1) {
        if (alreadyTaken) result.put("nativeTrialRewardRestored", true);
        trialStep = -2;
        sendTrial(connection, "TakeTrialActivityRewardCsReq", TRIAL_STAGE);
      } else if (trialStep == 13) {
        trialStep = 14;
        sendTrial(connection, "TakeTrialActivityRewardCsReq", TRIAL_STAGE);
      } else {
        result.put("nativeTrialRewardOnceAndQuery", true);
        trialVerified = true;
        trialStep = 17;
      }
      return true;
    }
    if ((trialStep == -2 || trialStep == 14 || trialStep == 15)
        && command == protocol.clientCommand("TakeTrialActivityRewardScRsp")) {
      var response = protocol.clientSchema().parse("TakeTrialActivityRewardScRsp", bytes);
      int code = (Integer) field(response, "retcode");
      require(
          (Integer) field(response, "stage_id") == TRIAL_STAGE,
          "Trial reward lost its stage identity");
      var rewards = (List<?>) field((DynamicMessage) field(response, "reward"), "item_list");
      boolean alreadyTaken = "resume".equals(universeMode);
      if (trialStep == -2) {
        require(
            code
                    == (alreadyTaken
                            ? Retcode.QUEST_REWARD_ALREADY_TAKEN
                            : Retcode.QUEST_NOT_FINISH)
                        .getVal()
                && rewards.isEmpty(),
            "Unplayed or previously taken trial granted a reward");
        trialStep = -3;
        sendTrial(connection, "StartTrialActivityCsReq", 311020);
      } else if (trialStep == 14) {
        require(
            code == (alreadyTaken ? Retcode.QUEST_REWARD_ALREADY_TAKEN.getVal() : 0),
            "Confirmed trial reward failed");
        if (alreadyTaken) require(rewards.isEmpty(), "Saved trial reward was granted again");
        else {
          var actual = new TreeMap<Integer, Integer>();
          for (var row : rewards) {
            var item = (DynamicMessage) row;
            actual.merge(
                (Integer) field(item, "item_id"), (Integer) field(item, "num"), Math::addExact);
          }
          require(
              actual.equals(Map.of(2, 10000, 212, 2, 111011, 4)),
              "Actual trial reward differs from its frozen resource quantities: " + actual);
          result.put("nativeTrialRewardItems", actual);
        }
        trialStep = 15;
        sendTrial(connection, "TakeTrialActivityRewardCsReq", TRIAL_STAGE);
      } else {
        require(
            code == Retcode.QUEST_REWARD_ALREADY_TAKEN.getVal() && rewards.isEmpty(),
            "Repeated trial reward succeeded");
        trialStep = 16;
        send(connection, "GetTrialActivityDataCsReq");
      }
      return true;
    }
    if (command == protocol.clientCommand("CurTrialActivityScNotify")) {
      var response = protocol.clientSchema().parse("CurTrialActivityScNotify", bytes);
      require(
          trialStep >= 10
              && trialStep <= 11
              && (Integer) field(response, "activity_stage_id") == TRIAL_STAGE
              && ((com.google.protobuf.Descriptors.EnumValueDescriptor) field(response, "status"))
                      .getNumber()
                  == 1
              && ++trialFinishNotifications == 1,
          "Trial finish was fabricated, duplicated or lost its identity");
      return true;
    }
    if (trialStep == -3 && command == protocol.clientCommand("StartTrialActivityScRsp")) {
      var response = protocol.clientSchema().parse("StartTrialActivityScRsp", bytes);
      require(
          (Integer) field(response, "retcode") != 0
              && (Integer) field(response, "stage_id") == 311020,
          "Unadvertised but valid resource trial was started");
      result.put("nativeClosedTrialStartRejected", true);
      trialStep = 1;
      sendTrial(connection, "StartTrialActivityCsReq", TRIAL_STAGE);
      return true;
    }
    if ((trialStep == 1 || trialStep == 6)
        && command == protocol.clientCommand("StartTrialActivityScRsp")) {
      var response = protocol.clientSchema().parse("StartTrialActivityScRsp", bytes);
      require(
          (Integer) field(response, "retcode") == 0
              && (Integer) field(response, "stage_id") == TRIAL_STAGE,
          "Native trial start failed or lost its stage identity");
      trialStep = trialStep == 1 ? 2 : 7;
      send(connection, "GetCurSceneInfoCsReq");
      return true;
    }
    if ((trialStep == 2 || trialStep == 5 || trialStep == 7 || trialStep == 12)
        && command == protocol.clientCommand("GetCurSceneInfoScRsp")) {
      var response = protocol.clientSchema().parse("GetCurSceneInfoScRsp", bytes);
      require((Integer) field(response, "retcode") == 0, "Trial scene query failed");
      var scene = (DynamicMessage) field(response, "scene");
      int trialActors = 0;
      for (var row : (List<?>) field(scene, "entity_group_list")) {
        for (var entry : (List<?>) field((DynamicMessage) row, "entity_list")) {
          var entity = (DynamicMessage) entry;
          var actorField = entity.getDescriptorForType().findFieldByName("actor");
          if (!entity.hasField(actorField)) continue;
          var actor = (DynamicMessage) entity.getField(actorField);
          if (((com.google.protobuf.Descriptors.EnumValueDescriptor) field(actor, "avatar_type"))
                  .getNumber()
              == 1) trialActors++;
        }
      }
      if (trialStep == 2) {
        require(
            trialActors > 0 && !field(scene, "entry_id").equals(result.get("entry")),
            "Trial start did not create a scene with temporary actors");
        result.put("nativeTrialActors", trialActors);
        trialStep = 3;
        sendTrial(connection, "LeaveTrialActivityCsReq", TRIAL_STAGE + 1);
      } else if (trialStep == 7) {
        require(trialActors > 0, "Second trial entry lost its temporary party");
        int enemy = 0;
        for (var row : (List<?>) field(scene, "entity_group_list")) {
          for (var entry : (List<?>) field((DynamicMessage) row, "entity_list")) {
            var entity = (DynamicMessage) entry;
            if (entity.hasField(entity.getDescriptorForType().findFieldByName("npc_monster"))) {
              enemy = (Integer) field(entity, "entity_id");
              break;
            }
          }
          if (enemy > 0) break;
        }
        require(enemy > 0, "Trial scene has no actual configured encounter");
        var type = protocol.clientSchema().message("SceneCastSkillCsReq");
        var attack =
            DynamicMessage.newBuilder(type)
                .setField(type.findFieldByName("cast_entity_id"), field(scene, "leader_entity_id"))
                .setField(
                    type.findFieldByName("attacked_by_entity_id"),
                    field(scene, "leader_entity_id"));
        attack.addRepeatedField(type.findFieldByName("hit_target_entity_id_list"), enemy);
        trialStep = 8;
        send(connection, "SceneCastSkillCsReq", attack.build());
      } else {
        require(
            trialActors == 0
                && field(scene, "entry_id").equals(result.get("entry"))
                && field(scene, "plane_id").equals(result.get("plane"))
                && field(scene, "floor_id").equals(result.get("floor")),
            "Trial leave did not restore the ordinary scene and party");
        if (trialStep == 5) {
          result.put("nativeTrialLifecycle", true);
          result.put("nativeTrialStaleLeaveRejected", true);
          trialStep = 6;
          sendTrial(connection, "StartTrialActivityCsReq", TRIAL_STAGE);
        } else {
          require(trialFinishNotifications == 1, "Actual trial victory did not notify completion");
          trialStep = 13;
          result.put("nativeTrialCombatVictoryAndRetry", true);
          result.put("nativeTrialZeroInitialEnergy", true);
          result.put("nativeTrialFinishNotifications", trialFinishNotifications);
          send(connection, "GetTrialActivityDataCsReq");
        }
      }
      return true;
    }
    if ((trialStep == 3 || trialStep == 4)
        && command == protocol.clientCommand("LeaveTrialActivityScRsp")) {
      var response = protocol.clientSchema().parse("LeaveTrialActivityScRsp", bytes);
      if (trialStep == 3) {
        require(
            (Integer) field(response, "retcode") == Retcode.REQ_PARA_INVALID.getVal()
                && (Integer) field(response, "stage_id") == TRIAL_STAGE + 1,
            "Stale trial leave changed the current trial");
        trialStep = 4;
        sendTrial(connection, "LeaveTrialActivityCsReq", TRIAL_STAGE);
      } else {
        require(
            (Integer) field(response, "retcode") == 0
                && (Integer) field(response, "stage_id") == TRIAL_STAGE,
            "Native trial leave failed or lost its stage identity");
        trialStep = 5;
        send(connection, "GetCurSceneInfoCsReq");
      }
      return true;
    }
    if (trialStep == 8 && command == protocol.clientCommand("SceneCastSkillScRsp")) {
      var response = protocol.clientSchema().parse("SceneCastSkillScRsp", bytes);
      require((Integer) field(response, "retcode") == 0, "Actual trial attack failed");
      var battle = (DynamicMessage) field(response, "battle_info");
      trialBattleId = (Integer) field(battle, "battle_id");
      trialBattleStage = (Integer) field(battle, "stage_id");
      var actors = (List<?>) field(battle, "battle_avatar_list");
      require(
          trialBattleId > 0
              && !actors.isEmpty()
              && !((List<?>) field(battle, "monster_wave_list")).isEmpty(),
          "Trial battle lost its actual party or waves");
      for (var row : actors) {
        var actor = (DynamicMessage) row;
        require(
            ((com.google.protobuf.Descriptors.EnumValueDescriptor) field(actor, "avatar_type"))
                        .getNumber()
                    == 1
                && (Integer) field((DynamicMessage) field(actor, "sp_bar"), "cur_sp") == 0,
            "Trial BattleAvatar ignored its zero SPList resource or temporary type");
      }
      trialStep = 9;
      finishTrialBattle(connection, true);
      return true;
    }
    if (trialStep >= 9
        && trialStep <= 11
        && command == protocol.clientCommand("PVEBattleResultScRsp")) {
      var response = protocol.clientSchema().parse("PVEBattleResultScRsp", bytes);
      int code = (Integer) field(response, "retcode");
      if (trialStep == 9) {
        require(
            code != 0 && trialFinishNotifications == 0,
            "Wrong trial battle identity completed the trial");
        trialStep = 10;
        finishTrialBattle(connection, false);
      } else if (trialStep == 10) {
        require(
            code == 0 && (Integer) field(response, "battle_id") == trialBattleId,
            "Actual trial victory failed");
        trialStep = 11;
        finishTrialBattle(connection, false);
      } else {
        require(
            code != 0 && trialFinishNotifications == 1,
            "Repeated trial victory succeeded or notified twice");
        trialStep = 12;
        send(connection, "GetCurSceneInfoCsReq");
      }
      return true;
    }
    return false;
  }

  private void finishTrialBattle(Ukcp connection, boolean wrongId) {
    var type = protocol.clientSchema().message("PVEBattleResultCsReq");
    var end = type.findFieldByName("end_status");
    send(
        connection,
        "PVEBattleResultCsReq",
        DynamicMessage.newBuilder(type)
            .setField(
                type.findFieldByName("battle_id"), wrongId ? trialBattleId + 1 : trialBattleId)
            .setField(type.findFieldByName("stage_id"), trialBattleStage)
            .setField(end, end.getEnumType().findValueByName("BATTLE_END_WIN"))
            .build());
  }

  private void verifyTalentSnapshot(DynamicMessage message) {
    var info = (DynamicMessage) field(message, "JCOBJAMPOAL");
    var rows = (List<?>) field(info, "BLHJIPBHHFO");
    require(rows.size() == 42, "Missing resource talent nodes");
    var ids = new HashSet<Integer>();
    for (var value : rows) {
      var row = (DynamicMessage) value;
      int id = (Integer) field(row, "CKHCLKOALKE");
      require(id > 0 && ids.add(id), "Invalid duplicate talent");
      require(
          !field(row, "status").toString().equals("RogueTalentStatus_Enable"),
          "Unearned talent activated");
      for (var entry : (List<?>) field(row, "LDHIJAHNEGK")) {
        var condition = (DynamicMessage) entry;
        require(
            (Integer) field(condition, "progress") == 0 && !(Boolean) field(condition, "finish"),
            "Unearned talent prerequisite");
      }
    }
    result.put("resourceTalents", rows.size());
  }

  private void universeSnapshot(DynamicMessage game) {
    var current = (DynamicMessage) field(game, "CFDIKLBINHK");
    var wallet = (DynamicMessage) field(current, "DHEKLJDBNNP");
    require((Integer) field(wallet, "rogue_money") == 50, "Initial 4.6 fragments changed");
    result.put("initialUniverseFragments", 50);
    require(
        field(current, "status").toString().equals("RogueStatus_Doing"), "Exploration status lost");
    var map = (DynamicMessage) field(current, "COOOMDKGODF");
    require((Integer) field(map, "area_id") == 100, "Saved universe area changed");
    int site = (Integer) field(map, "ANHDBMOPGDC"), room = (Integer) field(map, "HPKMGKPPELC");
    require(site > 0 && room > 0, "Missing assigned universe room");
    if (result.containsKey("universeRoom"))
      require(result.get("universeRoom").equals(room), "Resume rerolled the room");
    result.put("universeSite", site);
    result.put("universeRoom", room);
    var avatars = (DynamicMessage) field(current, "EIOHFJMFOKF");
    verifyRevivalPrice((DynamicMessage) field(avatars, "MFEODNJNGDP"));
    result.put("nativeReviveCostSnapshot", true);
    require(
        ((List<?>) field(avatars, "NOEPBNMJFGL"))
            .contains(emu.lunarcore.GameConstants.TRAILBLAZER_AVATAR_ID),
        "Downloaded avatar lost");
  }

  private boolean receiveUniverse(int command, byte[] bytes, Ukcp connection) throws Exception {
    if (universeMode == null) return false;
    if (command == protocol.clientCommand("SyncRogueReviveInfoScNotify")) {
      var notify = protocol.clientSchema().parse("SyncRogueReviveInfoScNotify", bytes);
      verifyRevivalPrice((DynamicMessage) field(notify, "MFEODNJNGDP"));
      result.put("nativeReviveCostNotify", true);
      return true;
    }
    if (command == protocol.clientCommand("ReviveRogueAvatarScRsp")) {
      require(universeStep == 4, "Unexpected revival response");
      var rsp = protocol.clientSchema().parse("ReviveRogueAvatarScRsp", bytes);
      require(
          (Integer) field(rsp, "PLDKENJLKME") != 0,
          "Revival accepted without a console/dead target");
      verifyRevivalPrice((DynamicMessage) field(rsp, "PHNAANFIFGB"));
      result.put("nativeInvalidReviveRejected", true);
      universeStep = 2;
      send(connection, "LeaveRogueCsReq");
      return true;
    }
    if (command == protocol.clientCommand("SyncRogueFinishScNotify")) {
      require(abandonUniverse && universeStep == 3, "Unexpected universe finish result");
      var packet = protocol.clientSchema().parse("SyncRogueFinishScNotify", bytes);
      var finish = (DynamicMessage) field(packet, "NLEOMLNOKCC");
      require(
          (Integer) field(finish, "area_id") == 100 && !(Boolean) field(finish, "is_win"),
          "Wrong abandoned universe result");
      require(
          (Integer) field(finish, "GMIIMAHLIJF") == 0
              && (Integer) field(finish, "HEOMALBNJLL") == 1,
          "Wrong abandoned room counts");
      var record = (DynamicMessage) field(finish, "record_info");
      var avatars = (List<?>) field(record, "avatar_list");
      require(
          avatars.size() == 1
              && (Integer) field((DynamicMessage) avatars.getFirst(), "id")
                  == emu.lunarcore.GameConstants.TRAILBLAZER_AVATAR_ID,
          "Lost frozen rogue roster");
      require(
          ((List<?>) field((DynamicMessage) field(finish, "LHPNJLBPACA"), "item_list")).isEmpty(),
          "Abandon produced win rewards");
      result.put("nativeUniverseAbandonResult", true);
      return true;
    }
    String name =
        List.of("StartRogueScRsp", "EnterRogueScRsp", "LeaveRogueScRsp", "QuitRogueScRsp").stream()
            .filter(n -> protocol.clientCommand(n) == command)
            .findFirst()
            .orElse(null);
    if (name == null) return false;
    var message = protocol.clientSchema().parse(name, bytes);
    require(
        (Integer) field(message, "retcode") == 0,
        "Universe lifecycle failed: " + name + " " + field(message, "retcode"));
    if (universeStep == 1) {
      require(
          name.equals(universeMode.equals("start") ? "StartRogueScRsp" : "EnterRogueScRsp"),
          "Unexpected universe entry response");
      universeSnapshot((DynamicMessage) field(message, "rogue_game_info"));
      var scene = (DynamicMessage) field(message, "scene");
      require(
          (Integer) field(scene, "entry_id") > 0
              && !field(scene, "entry_id").equals(result.get("entry")),
          "Universe did not enter a real room");
      require(
          !((List<?>) field(scene, "entity_group_list")).isEmpty(),
          "Universe room has no loaded groups");
      var team = (DynamicMessage) field(message, "lineup");
      require(
          field(team, "extra_lineup_type").toString().equals("ExtraLineupType_LineupRogue")
              && !((List<?>) field(team, "avatar_list")).isEmpty(),
          "Universe has no dedicated lineup");
      universeStep = 4;
      var revive =
          DynamicMessage.newBuilder(protocol.clientSchema().message("ReviveRogueAvatarCsReq"));
      revive.addRepeatedField(
          revive.getDescriptorForType().findFieldByName("NOEPBNMJFGL"),
          emu.lunarcore.GameConstants.TRAILBLAZER_AVATAR_ID);
      send(connection, "ReviveRogueAvatarCsReq", revive.build());
    } else if (universeStep == 2) {
      require(name.equals("LeaveRogueScRsp"), "Unexpected universe leave response");
      var scene = (DynamicMessage) field(message, "scene");
      require(
          field(scene, "entry_id").equals(result.get("entry"))
              && field(scene, "floor_id").equals(result.get("floor")),
          "Universe return location lost");
      require(
          field((DynamicMessage) field(message, "lineup"), "extra_lineup_type")
              .toString()
              .equals("ExtraLineupType_LineupNone"),
          "Normal lineup not restored");
      if (abandonUniverse) {
        universeStep = 3;
        var request = DynamicMessage.newBuilder(protocol.clientSchema().message("QuitRogueCsReq"));
        request.setField(request.getDescriptorForType().findFieldByName("area_id"), 100);
        send(connection, "QuitRogueCsReq", request.build());
      } else {
        universeVerified = true;
        result.put("nativeUniverseLifecycle", true);
      }
    } else if (universeStep == 3) {
      require(name.equals("QuitRogueScRsp"), "Unexpected universe quit response");
      require(
          Boolean.TRUE.equals(result.get("nativeUniverseAbandonResult")),
          "Quit response arrived without a terminal result");
      var game = (DynamicMessage) field(message, "rogue_game_info");
      require(
          !game.hasField(game.getDescriptorForType().findFieldByName("CFDIKLBINHK")),
          "Abandoned run still active");
      universeVerified = true;
      result.put("nativeUniverseLifecycle", true);
      result.put("nativeUniverseAbandoned", true);
    } else throw new IllegalStateException("Unsolicited universe lifecycle response");
    return true;
  }

  private void verifyRevivalPrice(DynamicMessage info) {
    var costs = (DynamicMessage) field(info, "LNIEFKBMIPD");
    var list = (List<?>) field(costs, "item_list");
    require(list.size() == 1, "Missing classic resource revive cost");
    var pile = (DynamicMessage) field((DynamicMessage) list.getFirst(), "pile_item");
    require(
        (Integer) field(pile, "item_id") == 31 && (Integer) field(pile, "item_num") == 80,
        "Wrong saved revive currency or unit price");
  }

  private boolean receiveBattle(int command, byte[] bytes, Ukcp connection) throws Exception {
    if (command == protocol.clientCommand("SceneCastSkillScRsp")) {
      var response = protocol.clientSchema().parse("SceneCastSkillScRsp", bytes);
      require((Integer) field(response, "cast_entity_id") == leaderEntity, "Wrong cast echo");
      if (castPhase++ == 0) {
        require((Integer) field(response, "retcode") != 0, "Invalid skill index accepted");
        sendCast(connection, 0);
      } else {
        require((Integer) field(response, "retcode") == 0, "Normal scene attack failed");
        result.put("normalAttackAndInvalidSkill", true);
        var stage = protocol.clientSchema().message("SceneEnterStageCsReq");
        send(
            connection,
            "SceneEnterStageCsReq",
            DynamicMessage.newBuilder(stage)
                .setField(stage.findFieldByName("event_id"), 20001001)
                .build());
      }
      return true;
    }
    if (command == protocol.clientCommand("SceneEnterStageScRsp")) {
      var response = protocol.clientSchema().parse("SceneEnterStageScRsp", bytes);
      require((Integer) field(response, "retcode") == 0, "Stage entry failed");
      var battle = (DynamicMessage) field(response, "battle_info");
      battleId = (Integer) field(battle, "battle_id");
      battleStage = (Integer) field(battle, "stage_id");
      battleSeed = (Integer) field(battle, "logic_random_seed");
      require(battleId > 0, "Missing battle identity");
      require(!((List<?>) field(battle, "monster_wave_list")).isEmpty(), "No monster waves");
      send(connection, "GetCurBattleInfoCsReq");
      return true;
    }
    if (command == protocol.clientCommand("GetCurBattleInfoScRsp")) {
      var response = protocol.clientSchema().parse("GetCurBattleInfoScRsp", bytes);
      require((Integer) field(response, "retcode") == 0, "Current battle query failed");
      var battle = (DynamicMessage) field(response, "battle_info");
      require(
          (Integer) field(battle, "battle_id") == battleId
              && (Integer) field(battle, "logic_random_seed") == battleSeed,
          "Unstable battle snapshot");
      var type = protocol.clientSchema().message("PVEBattleResultCsReq");
      var end = type.findFieldByName("end_status");
      send(
          connection,
          "PVEBattleResultCsReq",
          DynamicMessage.newBuilder(type)
              .setField(
                  type.findFieldByName("battle_id"), battleRound == 0 ? battleId + 1 : battleId)
              .setField(type.findFieldByName("stage_id"), battleStage)
              .setField(end, end.getEnumType().findValueByName("BATTLE_END_WIN"))
              .build());
      return true;
    }
    if (command == protocol.clientCommand("PVEBattleResultScRsp")) {
      var response = protocol.clientSchema().parse("PVEBattleResultScRsp", bytes);
      if (battleRound == 0) {
        require((Integer) field(response, "retcode") != 0, "Wrong battle identity was accepted");
        send(connection, "QuitBattleCsReq");
      } else if (battleRound == 1) {
        require((Integer) field(response, "retcode") == 0, "Valid battle win failed");
        require((Integer) field(response, "battle_id") == battleId, "Wrong settlement identity");
        battleRound = 2;
        var type = protocol.clientSchema().message("PVEBattleResultCsReq");
        var end = type.findFieldByName("end_status");
        send(
            connection,
            "PVEBattleResultCsReq",
            DynamicMessage.newBuilder(type)
                .setField(type.findFieldByName("battle_id"), battleId)
                .setField(type.findFieldByName("stage_id"), battleStage)
                .setField(end, end.getEnumType().findValueByName("BATTLE_END_WIN"))
                .build());
      } else {
        require((Integer) field(response, "retcode") != 0, "Duplicate battle win accepted");
        result.put("battleWinAndRetry", true);
        battleVerified = true;
      }
      return true;
    }
    if (command == protocol.clientCommand("QuitBattleScRsp")) {
      var response = protocol.clientSchema().parse("QuitBattleScRsp", bytes);
      require((Integer) field(response, "retcode") == 0, "Battle quit failed");
      result.put("battleIdentityAndQuit", true);
      battleRound = 1;
      var stage = protocol.clientSchema().message("SceneEnterStageCsReq");
      send(
          connection,
          "SceneEnterStageCsReq",
          DynamicMessage.newBuilder(stage)
              .setField(stage.findFieldByName("event_id"), 20001001)
              .build());
      return true;
    }
    return false;
  }

  private void sendCast(Ukcp connection, int index) {
    var type = protocol.clientSchema().message("SceneCastSkillCsReq");
    send(
        connection,
        "SceneCastSkillCsReq",
        DynamicMessage.newBuilder(type)
            .setField(type.findFieldByName("cast_entity_id"), leaderEntity)
            .setField(type.findFieldByName("attacked_by_entity_id"), leaderEntity)
            .setField(type.findFieldByName("skill_index"), index)
            .build());
  }

  public static void main(String[] args) {
    int exit = 1;
    var client = new KcpClient();
    Ukcp connection = null;
    try {
      var probe = new LoopbackSessionProbe();
      var credentials =
          JsonParser.parseReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))
              .getAsJsonObject();
      if (credentials.has("universeMode")) {
        probe.universeMode = credentials.get("universeMode").getAsString();
        require(
            Set.of("start", "resume").contains(probe.universeMode), "Unknown universe probe mode");
      }
      probe.abandonUniverse =
          credentials.has("abandonUniverse") && credentials.get("abandonUniverse").getAsBoolean();
      if (credentials.has("finishTalk") && credentials.get("finishTalk").getAsBoolean()) {
        probe.finishTalk = true;
        probe.expected.put(
            probe.protocol.clientCommand("FinishTalkMissionScRsp"), "FinishTalkMissionScRsp");
      }
      if (credentials.has("setNickname")) {
        probe.setNickname = credentials.get("setNickname").getAsString();
        probe.expected.put(probe.protocol.clientCommand("SetNicknameScRsp"), "SetNicknameScRsp");
      }
      var type = probe.protocol.clientSchema().message("PlayerGetTokenCsReq");
      var token =
          DynamicMessage.newBuilder(type)
              .setField(
                  type.findFieldByName("account_uid"), credentials.get("accountUid").getAsString())
              .setField(type.findFieldByName("token"), credentials.get("comboToken").getAsString())
              .setField(type.findFieldByName("platform"), 1)
              .build();
      for (String response :
          List.of(
              "PlayerGetTokenScRsp",
              "PlayerLoginScRsp",
              "PlayerLoginFinishScRsp",
              "GetAvatarDataScRsp",
              "GetBagScRsp",
              "GetAllLineupDataScRsp",
              "GetStoryLineInfoScRsp",
              "GetCurSceneInfoScRsp",
              "GetUnlockTeleportScRsp",
              "GetSceneMapInfoScRsp",
              "ContentPackageGetDataScRsp",
              "GetQuestDataScRsp",
              "GetMissionDataScRsp",
              "GetMainMissionCustomValueScRsp",
              "GetDailyActiveInfoScRsp",
              "GetLoginActivityScRsp",
              "GetActivityScheduleConfigScRsp",
              "MusicRhythmDataScRsp",
              "ParkourGetDataScRsp",
              "ParkourGetRankingInfoScRsp",
              "ParkourStartLevelScRsp",
              "ParkourEndLevelScRsp",
              "StartRaidScRsp",
              "LeaveRaidScRsp",
              "GetSaveRaidScRsp",
              "GetAllSaveRaidScRsp",
              "GetRaidInfoScRsp",
              "UseItemScRsp",
              "FinishCosumeItemMissionScRsp",
              "GetRogueInfoScRsp",
              "GetRogueTalentInfoScRsp",
              "CycleScoreRewardGetDataScRsp",
              "GetFirstTalkByPerformanceNpcScRsp"))
        probe.expected.put(probe.protocol.clientCommand(response), response);
      var config = new ChannelConfig();
      config.nodelay(true, 40, 2, true);
      config.setMtu(1400);
      config.setSndwnd(256);
      config.setRcvwnd(256);
      config.setUseConvChannel(true);
      config.setConv(ThreadLocalRandom.current().nextInt(1, Integer.MAX_VALUE));
      config.setTimeoutMillis(25_000);
      config.setAckNoDelay(true);
      client.init(
          config,
          new KcpListener() {
            @Override
            public void onConnected(Ukcp channel) {
              probe.send(channel, "PlayerGetTokenCsReq", token);
            }

            @Override
            public void handleReceive(ByteBuf bytes, Ukcp channel) {
              try {
                probe.receive(bytes, channel);
              } catch (Exception ex) {
                probe.completed.completeExceptionally(ex);
              }
            }

            @Override
            public void handleException(Throwable error, Ukcp channel) {
              probe.completed.completeExceptionally(error);
            }

            @Override
            public void handleClose(Ukcp channel) {
              if (!probe.completed.isDone())
                probe.completed.completeExceptionally(
                    new IllegalStateException(
                        "Closed before snapshot completion: " + probe.received));
            }
          });
      connection =
          client.connect(new InetSocketAddress("127.0.0.1", Integer.parseInt(args[0])), config);
      var result = probe.completed.get(20, TimeUnit.SECONDS);
      // A redirected Windows PrintStream can use the console code page despite UTF-8 file.encoding.
      System.out.write((new Gson().toJson(result) + "\n").getBytes(StandardCharsets.UTF_8));
      System.out.flush();
      exit = 0;
    } catch (Exception ex) {
      System.err.println(
          "Loopback session failed: " + ex.getClass().getSimpleName() + ": " + ex.getMessage());
    } finally {
      if (connection != null) connection.close();
      client.stop();
    }
    System.exit(exit);
  }

  private LoopbackSessionProbe() {}
}
