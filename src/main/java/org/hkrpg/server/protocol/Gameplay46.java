package org.hkrpg.server.protocol;

import com.google.protobuf.DynamicMessage;
import emu.lunarcore.game.inventory.GameItem;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.game.quest.QuestState;
import emu.lunarcore.proto.ItemListOuterClass.ItemList;
import emu.lunarcore.server.game.GameSession;
import emu.lunarcore.server.packet.Retcode;
import emu.lunarcore.server.packet.SessionState;
import java.io.IOException;
import java.util.*;
import org.hkrpg.server.gameplay.*;

/** Native 4.6 named gameplay fields; unverified opaque action fields never become gameplay. */
public final class Gameplay46 {
  // CN4.6 sends the deduplicated frame-end mission list in one request (d99e6b0),
  // including observed batches beyond 100. Bound work above the pinned 2,184-row catalog.
  private static final int MAX_CUSTOM_VALUE_QUERY_IDS = 4096;
  private static final Set<String> REQUESTS =
      Set.of(
          "GetMissionDataCsReq",
          "GetNpcStatusCsReq",
          "GetNpcMessageGroupCsReq",
          "GetMissionMessageCsReq",
          "FinishItemIdCsReq",
          "FinishSectionIdCsReq",
          "FinishPerformSectionIdCsReq",
          "FinishMultiPerformSectionIdCsReq",
          "AcceptMainMissionCsReq",
          "GetMainMissionCustomValueCsReq",
          "FinishTalkMissionCsReq",
          "StarPerformanceRelayCsReq",
          "FinishCosumeItemMissionCsReq",
          "TakeApRewardCsReq",
          "TakeAllApRewardCsReq",
          "GetLoginActivityCsReq",
          "TakeLoginActivityRewardCsReq",
          "GetActivityScheduleConfigCsReq",
          "MusicRhythmDataCsReq",
          "MusicRhythmStartLevelCsReq",
          "MusicRhythmFinishLevelCsReq",
          "MusicRhythmSaveSongConfigDataCsReq",
          "SceneCastSkillCsReq",
          "SceneCastSkillCostMpCsReq",
          "GetCurBattleInfoCsReq",
          "QuitBattleCsReq");

  public static boolean handle(GameSession session, int command, byte[] bytes) throws IOException {
    if (!session.getProtocol().experimental()) return false;
    String name =
        REQUESTS.stream()
            .filter(n -> session.getProtocol().clientCommand(n) == command)
            .findFirst()
            .orElse(null);
    if (name == null) return false;
    if (session.getState() != SessionState.ACTIVE) return true;
    if (name.equals("QuitBattleCsReq") && !BattleResult46.identityValid(session.getProtocol().clientSchema().message(name),bytes,0)) {
      send(session,"QuitBattleScRsp",Map.of("retcode",1));
      return true;
    }
    if ((name.equals("SceneCastSkillCsReq") || name.equals("SceneCastSkillCostMpCsReq"))
        && SceneActionWire46.rejectMalformed(session, name, bytes)) return true;
    var req = session.getProtocol().clientSchema().parse(name, bytes);
    if (!req.getUnknownFields().asMap().isEmpty() && !name.equals("FinishCosumeItemMissionCsReq"))
      throw new ProtocolException("Unknown gameplay request fields: " + name);
    var player = session.getPlayer();
    synchronized (player) {
      switch (name) {
        case "StarPerformanceRelayCsReq" -> {
          var code = Parkour46.wireValid(req.getDescriptorForType(), bytes, 0)
                  && only(req, "performance_id", "performance_type")
              ? DailyRecoverPerformances.start(player, number(req, "performance_id"), number(req, "performance_type"))
              : Retcode.REQ_PARA_INVALID;
          send(session, "StarPerformanceRelayScRsp", Map.of("retcode", code.getVal()));
        }
        case "GetNpcStatusCsReq",
                "GetNpcMessageGroupCsReq",
                "GetMissionMessageCsReq",
                "FinishItemIdCsReq",
                "FinishSectionIdCsReq",
                "FinishPerformSectionIdCsReq",
                "FinishMultiPerformSectionIdCsReq" ->
            Message46.handle(session, name, req);
        case "FinishCosumeItemMissionCsReq" -> {
          int id = number(req, "sub_mission_id");
          boolean valid =
              Parkour46.wireValid(req.getDescriptorForType(), bytes, 0)
                  && only(req, "sub_mission_id", "item_list");
          Map<Integer, Integer> reported = null;
          var itemField = req.getDescriptorForType().findFieldByName("item_list");
          if (req.hasField(itemField)) {
            reported = new HashMap<>();
            var list = (DynamicMessage) req.getField(itemField);
            valid &= list.getUnknownFields().asMap().isEmpty() && only(list, "item_list");
            var rows = (List<?>) value(list, "item_list");
            if (rows.size() > 100) valid = false;
            for (Object entry : rows) {
              var item = (DynamicMessage) entry;
              int itemId = number(item, "item_id"), count = number(item, "num");
              if (!item.getUnknownFields().asMap().isEmpty()
                  || !only(item, "item_id", "num")
                  || itemId <= 0
                  || count <= 0
                  || reported.putIfAbsent(itemId, count) != null) valid = false;
            }
          }
          var result =
              valid
                  ? player.getMissionManager().submitItems(id, reported)
                  : Retcode.REQ_PARA_INVALID;
          send(
              session,
              "FinishCosumeItemMissionScRsp",
              Map.of("retcode", result.getVal(), "sub_mission_id", id));
        }
        case "MusicRhythmDataCsReq",
                "MusicRhythmStartLevelCsReq",
                "MusicRhythmFinishLevelCsReq",
                "MusicRhythmSaveSongConfigDataCsReq" ->
            Music46.handle(session, name, req);
        case "SceneCastSkillCsReq", "SceneCastSkillCostMpCsReq",
                "GetCurBattleInfoCsReq", "QuitBattleCsReq" ->
            Battle46.handle(session, name, req);
        case "GetMissionDataCsReq" -> {
          session.getMissionNotifications().initialize(session);
          if (MissionSnapshotRecords.valid(player)) player.getMissionManager().refresh();
          var snapshot = missionSnapshot(session, false);
          session.sendClientPacket("GetMissionDataScRsp", snapshot.toByteArray());
          session.getMissionNotifications().snapshotSent(session, snapshot);
        }
        case "AcceptMainMissionCsReq" -> {
          int id = number(req, "main_mission_id");
          var result =
              only(req, "main_mission_id")
                  ? player.getMissionManager().accept(id)
                  : Retcode.REQ_PARA_INVALID;
          send(
              session,
              "AcceptMainMissionScRsp",
              Map.of("retcode", result.getVal(), "main_mission_id", id));
        }
        case "GetMainMissionCustomValueCsReq" -> {
          var response = builder(session, "GetMainMissionCustomValueScRsp");
          @SuppressWarnings("unchecked")
          var ids = (List<Integer>) value(req, "main_mission_id_list");
          if (ids.size() > MAX_CUSTOM_VALUE_QUERY_IDS)
            set(response, "retcode", Retcode.REQ_PARA_INVALID.getVal());
          else if (ids.stream().anyMatch(id ->
              player.getGameplay().getMainMissions().containsKey(id)
                  && !MissionSnapshotRecords.main(player, id)))
            set(response, "retcode", Retcode.PLAYER_DATA_ERROR.getVal());
          else
            for (int id : new LinkedHashSet<>(ids)) {
              var main = builder(session, "MainMission");
              set(main, "id", id);
              var state = player.getGameplay().getMainMissions().get(id);
              setMissionStatus(
                  main,
                  state == null
                      ? "MissionStatus_MissionNone"
                      : state.getPhase() == QuestState.Phase.DOING
                          ? "MissionStatus_MissionDoing"
                          : "MissionStatus_MissionFinish");
              appendCustom(session, main, id);
              add(response, "main_mission_list", main.build());
            }
          session.sendClientPacket(
              "GetMainMissionCustomValueScRsp", response.build().toByteArray());
        }
        case "FinishTalkMissionCsReq" -> {
          int id = number(req, "sub_mission_id");
          String talk = (String) value(req, "talk_str");
          var values = new HashMap<Integer, Integer>();
          boolean valid = only(req, "sub_mission_id", "talk_str", "custom_value_list");
          @SuppressWarnings("unchecked")
          var custom = (List<DynamicMessage>) value(req, "custom_value_list");
          if (custom.size() > 32) valid = false;
          for (var v : custom) {
            valid &= v.getUnknownFields().asMap().isEmpty() && only(v, "index", "custom_value");
            if (values.putIfAbsent(number(v, "index"), number(v, "custom_value")) != null)
              valid = false;
          }
          var result =
              valid
                  ? player.getMissionManager().finishTalk(id, talk, values)
                  : Retcode.MISSION_CUSTOM_VALUE_NOT_VALID;
          var response = builder(session, "FinishTalkMissionScRsp");
          set(response, "retcode", result.getVal());
          set(response, "sub_mission_id", id);
          set(response, "talk_str", talk);
          if (result == Retcode.SUCC) custom.forEach(v -> add(response, "custom_value_list", v));
          session.sendClientPacket("FinishTalkMissionScRsp", response.build().toByteArray());
        }
        case "TakeApRewardCsReq" -> {
          int level = number(req, "level");
          var result = player.getDailyManager().claimLevel(level);
          send(
              session,
              "TakeApRewardScRsp",
              Map.of(
                  "retcode",
                  result.retcode().getVal(),
                  "level",
                  level,
                  "reward",
                  items(session, result.items())));
        }
        case "TakeAllApRewardCsReq" -> {
          var response = builder(session, "TakeAllApRewardScRsp");
          var items = new ArrayList<GameItem>();
          int count = 0;
          for (var level : player.getDailyManager().levels()) {
            var result = player.getDailyManager().claimLevel(level.getLevel());
            if (result.retcode() == Retcode.SUCC) {
              add(response, "take_reward_level_list", level.getLevel());
              items.addAll(result.items());
              count++;
            }
          }
          set(
              response,
              "retcode",
              (count > 0 ? Retcode.SUCC : Retcode.DAILY_ACTIVE_LEVEL_AP_NOT_ENOUGH).getVal());
          set(response, "reward", items(session, items));
          session.sendClientPacket("TakeAllApRewardScRsp", response.build().toByteArray());
        }
        case "GetLoginActivityCsReq" -> {
          var response = builder(session, "GetLoginActivityScRsp");
          for (int id : player.getLoginActivityManager().activeIds()) {
            var activity = builder(session, "LoginActivityData");
            set(activity, "id", id);
            set(
                activity,
                "panel_id",
                player.getLoginActivityManager().window(id).orElseThrow().panelId());
            set(activity, "login_days", player.getLoginActivityManager().days(id));
            add(response, "login_activity_list", activity.build());
          }
          session.sendClientPacket("GetLoginActivityScRsp", response.build().toByteArray());
        }
        case "TakeLoginActivityRewardCsReq" -> {
          int id = number(req, "id"), day = number(req, "take_days");
          var result = player.getLoginActivityManager().claim(id, day);
          send(
              session,
              "TakeLoginActivityRewardScRsp",
              Map.of(
                  "retcode",
                  result.retcode().getVal(),
                  "id",
                  id,
                  "take_days",
                  day,
                  "panel_id",
                  player
                      .getLoginActivityManager()
                      .window(id)
                      .map(GameplayDefinitions.Window::panelId)
                      .orElse(0),
                  "reward",
                  items(session, result.items())));
        }
        case "GetActivityScheduleConfigCsReq" -> {
          var response = builder(session, "GetActivityScheduleConfigScRsp");
          for (var w : player.getLoginActivityManager().activeWindows()) {
            var row = builder(session, "ActivityScheduleData");
            set(row, "activity_id", w.moduleId());
            set(row, "panel_id", w.panelId());
            set(row, "begin_time", w.beginTime());
            set(row, "end_time", w.endTime());
            add(response, "schedule_data", row.build());
          }
          session.sendClientPacket(
              "GetActivityScheduleConfigScRsp", response.build().toByteArray());
        }
        default -> throw new ProtocolException("Unimplemented gameplay command: " + name);
      }
    }
    return true;
  }

  public static DynamicMessage missionSnapshot(GameSession session, boolean sync) {
    synchronized (session.getPlayer()) {
      return checkedMissionSnapshot(session, sync);
    }
  }

  private static DynamicMessage checkedMissionSnapshot(GameSession session, boolean sync) {
    var response = builder(session, sync ? "MissionSync" : "GetMissionDataScRsp");
    var player = session.getPlayer();
    if (!MissionSnapshotRecords.valid(player)) {
      if (sync) return null;
      set(response, "retcode", Retcode.PLAYER_DATA_ERROR.getVal());
      return response.build();
    }
    for (var state :
        player.getGameplay().getSubMissions().values().stream()
            .sorted(Comparator.comparingInt(QuestState::getQuestId))
            .toList()) {
      if (!sync && state.getPhase() != QuestState.Phase.DOING) {
        // CN4.6 GetMissionData field 15 populates _FinishedSubMissionIDs silently.
        // Sending historical FINISH rows in mission_list instead runs _UpdateSubMission
        // and queues the 0x928 completion callback (including function-unlock hints).
        add(response, "OLKALABEDOA", state.getQuestId());
        continue;
      }
      var mission = builder(session, "Mission");
      set(mission, "id", state.getQuestId());
      set(mission, "progress", state.getProgress());
      var status =
          mission
              .getDescriptorForType()
              .findFieldByName("status")
              .getEnumType()
              .findValueByName(
                  state.getPhase() == QuestState.Phase.DOING
                      ? "MissionStatus_MissionDoing"
                      : "MissionStatus_MissionFinish");
      set(mission, "status", status);
      add(response, "mission_list", mission.build());
    }
    for (var state :
        player.getGameplay().getMainMissions().values().stream()
            .sorted(Comparator.comparingInt(QuestState::getQuestId))
            .toList()) {
      if (state.getPhase() != QuestState.Phase.DOING)
        add(response, "finished_main_mission_id_list", state.getQuestId());
      if (!sync && state.getPhase() == QuestState.Phase.DOING) {
        var main = builder(session, "MainMission");
        set(main, "id", state.getQuestId());
        var field = main.getDescriptorForType().findFieldByName("status");
        set(
            main,
            "status",
            field
                .getEnumType()
                .findValueByName(
                    state.getPhase() == QuestState.Phase.DOING
                        ? "MissionStatus_MissionDoing"
                        : "MissionStatus_MissionFinish"));
        appendCustom(session, main, state.getQuestId());
        add(response, "main_mission_list", main.build());
      }
    }
    return response.build();
  }

  public static void syncMissions(Player player) {
    if (!player.isOnline() || !player.getSession().getProtocol().experimental()) return;
    var session = player.getSession();
    session.getMissionNotifications().send(session);
  }

  private static void setMissionStatus(DynamicMessage.Builder main, String status) {
    set(
        main,
        "status",
        main.getDescriptorForType()
            .findFieldByName("status")
            .getEnumType()
            .findValueByName(status));
  }

  private static void appendCustom(GameSession s, DynamicMessage.Builder main, int id) {
    var info = emu.lunarcore.data.GameData.getMissionInfos().get(id);
    if (info == null || info.getMissionCustomValueList() == null) return;
    for (var c : info.getMissionCustomValueList()) {
      var val =
          s.getPlayer().getGameplay().getStoryValues().get("mission:" + id + ":" + c.getName());
      if (val == null || val < 0) continue;
      var row = builder(s, "MissionCustomValue");
      set(row, "index", c.getIndex());
      set(row, "custom_value", val);
      add(main, "custom_value_list", row.build());
    }
  }

  static DynamicMessage.Builder builder(GameSession s, String name) {
    return DynamicMessage.newBuilder(s.getProtocol().clientSchema().message(name));
  }

  static void set(DynamicMessage.Builder b, String name, Object value) {
    var f = b.getDescriptorForType().findFieldByName(name);
    if (f == null) throw new ProtocolException("Missing gameplay field " + name);
    b.setField(f, value);
  }

  static void add(DynamicMessage.Builder b, String name, Object value) {
    var f = b.getDescriptorForType().findFieldByName(name);
    if (f == null) throw new ProtocolException("Missing gameplay list " + name);
    b.addRepeatedField(f, value);
  }

  static Object value(DynamicMessage m, String name) {
    return m.getField(m.getDescriptorForType().findFieldByName(name));
  }

  static int number(DynamicMessage m, String name) {
    return (Integer) value(m, name);
  }

  static boolean only(DynamicMessage m, String... allowed) {
    var set = Set.of(allowed);
    return m.getAllFields().keySet().stream().allMatch(f -> set.contains(f.getName()));
  }

  static void send(GameSession s, String name, Map<String, Object> fields) {
    var b = builder(s, name);
    fields.forEach((f, v) -> set(b, f, v));
    s.sendClientPacket(name, b.build().toByteArray());
  }

  static DynamicMessage items(GameSession s, List<GameItem> items) {
    var list = ItemList.newInstance();
    items.forEach(i -> list.addItemList(i.toProto()));
    try {
      return s.getProtocol()
          .clientSchema()
          .parse("ItemList", s.getProtocol().translate("ItemList", list.toByteArray(), true));
    } catch (IOException e) {
      throw new ProtocolException("Malformed reward list");
    }
  }

  private Gameplay46() {}
}
