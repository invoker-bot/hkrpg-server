package org.hkrpg.server.gameplay;

import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import emu.lunarcore.data.GameData;
import emu.lunarcore.data.config.AnchorInfo;
import emu.lunarcore.data.config.MissionInfo;
import emu.lunarcore.game.player.Player;
import java.util.Arrays;
import java.util.List;

/** Shared finish keys for the two letter readings in To: The Faint Star. */
public final class RockyTruthPerformance {
  private static final int MAIN = 2000111, FOLLOWUP = 2000119, RETURN_TO_ROCKY = 200011009;
  private static final int PLANE = 10001, FLOOR = 10001001;
  private static final int ROCKY_GROUP = 18, ROCKY_INSTANCE = 400001, ROCKY = 10106;
  private static final int NEARBY_ENTRANCE = 1000102, NEARBY_GROUP = 11, NEARBY_ANCHOR = 3;
  private static final long NEARBY_DISTANCE_SQUARED = 12_000L * 12_000;

  private static final Branch RED =
      new Branch(200011010, 200011012, 200011029, 140072, "Mission_200011012",
          "519dc50b50c44f72f0e244315e1ba2b2cdc5c5787a9c994f90b75581ae2d52ba");
  private static final Branch BLUE =
      new Branch(200011011, 200011013, 200011030, 140073, "Mission_200011013",
          "7d103e5a2a1c7f3fc152c65abb6a3d6c3c4a380aa434d9dad799fa5c9a052f55");

  record Branch(int delivery, int talk, int observer, int letter, String key, String scriptHash) {
    private boolean original() {
      var primary = target(talk, MAIN);
      var observation = target(observer, FOLLOWUP);
      var payment = target(delivery, MAIN);
      if (!matchesTalk(primary, key) || !matchesTalk(observation, key)
          || !"AnySequence".equals(primary.getTakeType())
          || !Arrays.equals(primary.getTakeParamIntList(), new Integer[] {delivery})
          || !"Auto".equals(observation.getTakeType())
          || !"".equals(observation.getMissionJsonPath())
          || observation.getTask() != null
          || observation.getFinishActionList() == null || observation.getFinishActionList().length != 0
          || observation.getSubRewardID() != 0
          || observation.getCustomValueReward() == null || observation.getCustomValueReward().length != 0
          || payment == null
          || !"ConsumeMissionItem".equals(payment.getFinishType())
          || !"Equal".equals(payment.getParamType())
          || !List.of(new MissionInfo.MissionItemCost(letter, 1)).equals(payment.getParamItemList())
          || !("Config/Level/Mission/2000110/Mission_" + talk + ".json").equals(primary.getMissionJsonPath())
          || primary.getTask() == null) return false;
      var script = primary.getTask().getSourceDefinition();
      if (script == null || script.length() > 1024) return false;
      try {
        return scriptHash.equals(TimelineActivityRules.fingerprint(JsonParser.parseString(script)));
      } catch (JsonParseException invalid) {
        return false;
      }
    }

    boolean delivered(Player player) {
      return MissionStateRecords.subCompleted(player, delivery);
    }

    private boolean pending(Player player) {
      return MissionStateRecords.doing(player.getMissionManager().sub(talk), talk, 1)
          && delivered(player) && original();
    }
  }

  private static MissionInfo.SubMissionInfo target(int id, int owner) {
    var main = GameData.getMissionInfos().get(owner);
    var sub = GameplayDefinitions.subs.get(id);
    return sub != null && main != null && main.getMainMissionID() == owner
            && main.getSubMissionById(id) == sub && sub.getMainMissionID() == owner
            && sub.getId() == id && sub.getProgress() == 1
            && sub.getLevelPlaneID() == PLANE && sub.getLevelFloorID() == FLOOR
        ? sub
        : null;
  }

  private static boolean matchesTalk(MissionInfo.SubMissionInfo sub, String key) {
    return sub != null && "Talk".equals(sub.getFinishType()) && "Equal".equals(sub.getParamType())
        && key.equals(sub.getParamStr1());
  }

  static Branch report(int id, String key) {
    var branch = RED.key().equals(key) ? RED : BLUE.key().equals(key) ? BLUE : null;
    return branch != null && (id == 0 || id == branch.talk() || id == branch.observer())
            && branch.original()
        ? branch
        : null;
  }

  static boolean followupSatisfied(Player player, MissionInfo.SubMissionInfo target) {
    var branch = target.getId() == RED.observer() ? RED : target.getId() == BLUE.observer() ? BLUE : null;
    // The hidden quest observes the same completed performance, including after a reconnect.
    return branch != null && MissionStateRecords.subCompleted(player, branch.talk())
        && branch.delivered(player) && branch.original();
  }

  static boolean atStation(Player player) {
    var scene = player.getScene();
    return player.getPlaneId() == PLANE && player.getFloorId() == FLOOR
        && (scene == null || ClientNpcGroups.supports(scene)
            && scene.getPlaneId() == PLANE && scene.getFloorId() == FLOOR);
  }

  /** Stage an interrupted reading near the existing actor before the login scene is sent. */
  public static AnchorInfo resumeAnchor(Player player) {
    synchronized (player) {
      var session = player.getSession();
      if (session == null || !session.getProtocol().experimental() || !atStation(player)
          || !DailyRecoverPerformances.ordinaryScene(player)
          || !MissionStateRecords.mainDoing(player, MAIN)
          || !MissionStateRecords.doing(player.getMissionManager().sub(RETURN_TO_ROCKY), RETURN_TO_ROCKY, 1)
          || !MissionStateRecords.subAccepted(player, RETURN_TO_ROCKY)
          || RED.pending(player) == BLUE.pending(player)) return null;

      var floor = GameData.getFloorInfo(PLANE, FLOOR);
      if (floor == null) return null;
      var group = floor.getGroups(0).get(ROCKY_GROUP);
      if (group == null || !group.isClientOwnedNpcSource() || group.getNPCList() == null
          || !MissionGroupConditions.matches(player, group.getLoadCondition())
          || MissionGroupConditions.evaluate(player, group.getUnloadCondition())
              != MissionGroupConditions.Result.NO_MATCH) return null;
      var placements = group.getNPCList().stream().filter(npc -> npc.getID() == ROCKY_INSTANCE).toList();
      if (placements.size() != 1) return null;
      var npc = placements.getFirst();
      if (npc.getNPCID() != ROCKY || npc.isIsDelete() || !npc.isLoadOnInitial()
          || !GameData.getNpcExcelMap().containsKey(ROCKY)
          || npc.getPos().getFastDist(player.getPos()) <= NEARBY_DISTANCE_SQUARED) return null;

      var entrance = GameData.getMapEntranceExcelMap().get(NEARBY_ENTRANCE);
      var nearby = floor.getGroups(0).get(NEARBY_GROUP);
      if (entrance == null || entrance.getPlaneID() != PLANE || entrance.getFloorID() != FLOOR
          || entrance.getStartGroupID() != NEARBY_GROUP || entrance.getStartAnchorID() != NEARBY_ANCHOR
          || nearby == null || nearby.getAnchorList() == null) return null;
      var anchor = floor.getAnchorInfo(nearby, NEARBY_ANCHOR);
      return anchor != null && !anchor.isIsDelete()
              && anchor.getPos().getFastDist(npc.getPos()) <= NEARBY_DISTANCE_SQUARED
          ? anchor
          : null;
    }
  }

  private RockyTruthPerformance() {}
}
