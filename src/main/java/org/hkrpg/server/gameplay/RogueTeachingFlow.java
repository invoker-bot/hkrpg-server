package org.hkrpg.server.gameplay;

import com.google.gson.*;
import emu.lunarcore.data.*;
import emu.lunarcore.data.config.*;
import emu.lunarcore.data.excel.MainMissionExcel;
import emu.lunarcore.game.enums.PlaneType;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.game.quest.QuestState;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Source-bound automatic handoff and the first-world teaching entrance, not a general graph VM. */
public final class RogueTeachingFlow {
  private static final Gson GSON = ResourceLoader.getGson();
  private static final JsonObject SOURCE = source();
  private static final String VALUE = "mission:4010116:OldValue_0";
  private static final Set<Integer> AUTOMATIC = Set.of(401011601, 401011603, 401011604);
  private static final Map<Integer,Integer> ROOMS = Map.of(1,301,2,302,3,303,4,304,5,305,6,306,7,307);
  private static final Map<Integer,Integer> LEGACY = Map.of(1,304,2,302,3,303,4,304,5,305,6,306,7,307);

  private static JsonObject source() {
    try (var input = RogueTeachingFlow.class.getResourceAsStream("/gameplay/rogue-teaching-flow-4.6.json")) {
      return JsonParser.parseString(new String(Objects.requireNonNull(input).readAllBytes(),
          StandardCharsets.UTF_8)).getAsJsonObject();
    } catch (Exception failed) { throw new ExceptionInInitializerError(failed); }
  }

  private static boolean main(int id) {
    var raw = SOURCE.getAsJsonObject("mains").get(Integer.toString(id));
    var actual = GameData.getMainMissionExcelMap().get(id);
    return raw != null && actual != null
        && GSON.toJsonTree(actual).equals(GSON.toJsonTree(GSON.fromJson(raw,MainMissionExcel.class)));
  }

  private static JsonObject header(MissionInfo value) {
    var result = GSON.toJsonTree(value).getAsJsonObject();
    for (var row : result.getAsJsonArray("SubMissionList")) row.getAsJsonObject().remove("task");
    return result;
  }

  private static boolean mission(int id) {
    var raw = SOURCE.getAsJsonObject("missions").get(Integer.toString(id));
    var actual = GameData.getMissionInfos().get(id);
    return main(id) && raw != null && actual != null
        && header(actual).equals(header(GSON.fromJson(raw,MissionInfo.class)));
  }

  private static boolean goal(int id, boolean script) {
    var actual = GameplayDefinitions.subs.get(id);
    var header = actual == null ? null : GameData.getMissionInfos().get(actual.getMainMissionID());
    if (header == null || !mission(actual.getMainMissionID()) || header.getSubMissionById(id) != actual)
      return false;
    if (!script) return true;
    var expected = SOURCE.getAsJsonObject("scripts").get(Integer.toString(id));
    var task = actual.getTask();
    if (expected == null || task == null || task.getSourceDefinition().isBlank()) return false;
    try {
      return expected.equals(JsonParser.parseString(task.getSourceDefinition()))
          && GSON.toJsonTree(task).equals(GSON.toJsonTree(GSON.fromJson(expected,MissionTaskInfo.class)));
    } catch (RuntimeException invalid) { return false; }
  }

  static boolean automatic(int id) { return AUTOMATIC.contains(id); }

  /** Only the three original no-reward, no-finish-action automatic goals are run here. */
  static boolean advanceAutomatic(Player player, MissionInfo.SubMissionInfo c, long now) {
    if (!automatic(c.getId()) || !MissionStateRecords.mainDoing(player,4010116)
        || !main(1010403) || !MissionStateRecords.mainCompleted(player,1010403)
        || !goal(c.getId(),true) || !goal(401011601,true) || !goal(401011602,true)
        || !goal(401011603,true) || !goal(401011604,true)
        || c.getSubRewardID() != 0 || c.getCustomValueReward().length != 0
        || c.getFinishActionList().length != 0) return false;
    var old = player.getGameplay().getSubMissions().get(c.getId());
    if (!MissionStateRecords.doing(old,c.getId(),1)) return false;
    Integer value;
    if (c.getId() == 401011603) {
      if (!main(4030002) || !goal(403000222,false)
          || player.getGameplay().getCancelledMissions().contains(4030002)
          || player.getGameplay().getCancelledSubs().contains(403000222)) return false;
      var observed = player.getGameplay().getSubMissions().get(403000222);
      boolean finished = MissionStateRecords.subCompleted(player,403000222);
      if (observed != null && !finished && !MissionStateRecords.doing(observed,403000222,1))
        return false;
      value = finished ? 1 : 2;
    } else {
      if (!goal(401011603,true) || !MissionStateRecords.subCompleted(player,401011603)) return false;
      value = c.getId() == 401011601 ? 1 : 2;
    }
    var values = player.getGameplay().getStoryValues();
    var previous = values.get(VALUE);
    if (previous != null && !previous.equals(value)
        || c.getId() != 401011603 && previous == null) return false;
    var next = GSON.fromJson(GSON.toJsonTree(old),QuestState.class);
    next.advance(1,1,now);
    player.getGameplay().getSubMissions().put(c.getId(),next);
    if (c.getId() == 401011603) values.put(VALUE,value);
    try { player.save(); }
    catch (RuntimeException failed) {
      player.getGameplay().getSubMissions().put(c.getId(),old);
      if (previous == null) values.remove(VALUE); else values.put(VALUE,previous);
      throw failed;
    }
    return true;
  }

  public static boolean inProgress(Player player) {
    return MissionStateRecords.mainDoing(player,4030003) && mission(4030003)
        && main(4010116) && MissionStateRecords.mainCompleted(player,4010116)
        && main(4030002) && MissionStateRecords.mainCompleted(player,4030002)
        && main(1010403) && MissionStateRecords.mainCompleted(player,1010403);
  }

  public static boolean introCompleted(Player player) {
    return inProgress(player) && goal(403000300,true)
        && MissionStateRecords.subCompleted(player,403000300);
  }

  static boolean office(Player player) {
    var scene = player.getScene();
    return scene != null && scene.getPlayer() == player && scene.getPlaneType() == PlaneType.Town
        && scene.getPlaneId() == 80112 && scene.getFloorId() == 80112001
        && scene.getEntryId() == 8011201 && scene.getDimensionId() == 0 && scene.getStoryLineId() == 0
        && player.getPlaneId() == 80112 && player.getFloorId() == 80112001 && player.getEntryId() == 8011201;
  }

  static boolean plan(Map<Integer,Integer> rooms) { return ROOMS.equals(rooms); }

  private static boolean map(int id) {
    var actual = GameplayDefinitions.maps.get(id);
    var expected = new ArrayList<GameplayDefinitions.Site>();
    for (var raw : SOURCE.getAsJsonArray("maps")) {
      var site = GSON.fromJson(raw,GameplayDefinitions.Site.class);
      if (site.RogueMapID() == id) expected.add(site);
    }
    if (actual == null || actual.size() != expected.size()) return false;
    return new HashSet<>(actual).equals(new HashSet<>(expected));
  }

  static boolean rooms() {
    for (var raw : SOURCE.getAsJsonArray("rooms")) {
      var row = GSON.fromJson(raw,GameplayDefinitions.Room.class);
      if (!row.equals(GameplayDefinitions.rooms.get(row.RogueRoomID()))) return false;
    }
    return true;
  }

  static Optional<RogueMapPools.Assignment> assignment() {
    return map(3) && rooms() ? Optional.of(new RogueMapPools.Assignment(1,ROOMS)) : Optional.empty();
  }

  /** A legacy ordinary run is observed in place; its route, victories and inventory are untouched. */
  static boolean restSatisfied(Player player, MissionInfo.SubMissionInfo c) {
    if (c.getId() != 403000303 || !introCompleted(player) || !goal(c.getId(),true)
        || !goal(403000304,true) || !MissionStateRecords.subCompleted(player,403000301)
        || !rooms()) return false;
    var run = player.getGameplay().getRogue();
    var scene = player.getScene();
    var entry = GameData.getMapEntranceExcelMap().get(8000102);
    return run.getAreaId() == 110 && run.getPhase() == GameplayState.RogueRun.Phase.EXPLORING
        && run.isInside() && !run.getRoster().isEmpty() && !run.pending() && !player.isInBattle()
        && run.getRunId() != null && !run.getRunId().isBlank()
        && (run.getMapId() == 3 && plan(run.getRooms()) && map(3)
            || run.getMapId() == 101 && LEGACY.equals(run.getRooms()) && map(101))
        && run.getSiteId() == 3 && run.getRoomId() == 303 && run.getCleared().containsAll(Set.of(1,2))
        && entry != null && entry.getPlaneID() == 80001 && entry.getFloorID() == 80001002
        && player.getEntryId() == 8000102 && player.getPlaneId() == 80001 && player.getFloorId() == 80001002
        && scene != null && scene.getPlayer() == player && scene.getPlaneType() == PlaneType.Rogue
        && scene.getEntryId() == 8000102 && scene.getPlaneId() == 80001 && scene.getFloorId() == 80001002
        && scene.getDimensionId() == 0 && scene.getStoryLineId() == 0 && scene.getFloorInfo() != null
        && scene.getFloorInfo().isGroupInDimension(34,0) && scene.getResourceGroups().containsKey(34);
  }

  private RogueTeachingFlow() {}
}
