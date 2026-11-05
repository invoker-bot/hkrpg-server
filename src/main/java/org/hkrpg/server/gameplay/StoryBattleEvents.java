package org.hkrpg.server.gameplay;

import emu.lunarcore.data.GameData;
import emu.lunarcore.data.config.MissionInfo;
import emu.lunarcore.game.player.Player;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/** Source-bound battle entry for original space station story objectives. */
public final class StoryBattleEvents {
  // Hashes cover the complete Mission graphs as retained by the resource loader.
  private static final Objective STATION_BOSS = new Objective(
      20003012, 20003011, 1000400, 100040110, 100040109, 20003, 20003001, "Main",
      "63f3a07ab496ccf4a08a9ea627175dff54b3122fc9f5f5a397edfe13b77d80ae");
  private static final Objective FOLLOW_PEPPY = new Objective(
      20003214, 20003214, 2000211, 200021114, 200021113, 20001, 20001001, "Companion",
      "09e9f02b0d5e1b650352bf00b35f05eba4fa24b682b39577acf25e724dc1b4cc");
  // The installed client uses 20003215. The reference JSON uses the 20003216 alias.
  private static final Objective RESCUE_ARLAN = new Objective(
      20003216, 20003215, 2000211, 200021123, 200021122, 20003, 20003001, "Companion",
      "763fbdedd2b37fcd5406872a4094be5f6e699b40134030dac51adab0597f1fa6");
  private static final Map<Integer, Objective> BY_REQUEST = Map.of(
      STATION_BOSS.request(), STATION_BOSS,
      FOLLOW_PEPPY.request(), FOLLOW_PEPPY,
      RESCUE_ARLAN.request(), RESCUE_ARLAN,
      RESCUE_ARLAN.event(), RESCUE_ARLAN);
  private static final Map<Integer, Objective> COMPANION_OBJECTIVES =
      Map.of(FOLLOW_PEPPY.sub(), FOLLOW_PEPPY, RESCUE_ARLAN.sub(), RESCUE_ARLAN);

  private record Objective(
      int request, int event, int main, int sub, int prerequisite, int plane, int floor,
      String missionType, String scriptHash) {
    boolean matches(MissionInfo.SubMissionInfo definition) {
      var owner = GameData.getMainMissionExcelMap().get(main);
      var graph = GameData.getMissionInfos().get(main);
      return definition != null && definition.getId() == sub && definition.getMainMissionID() == main
          && owner != null && owner.getId() == main && !owner.isInRaid()
          && missionType.equals(owner.getType())
          && graph != null && graph.getMainMissionID() == main
          && graph.getSubMissionById(sub) == definition
          && definition.getLevelPlaneID() == plane && definition.getLevelFloorID() == floor
          && "StageWin".equals(definition.getFinishType()) && "Equal".equals(definition.getParamType())
          && definition.getParamInt1() == event && definition.getParamInt2() == 0
          && definition.getParamInt3() == 0 && definition.getProgress() == 1
          && "AnySequence".equals(definition.getTakeType())
          && Arrays.equals(definition.getTakeParamIntList(), new Integer[]{prerequisite})
          && definition.getTask() != null && definition.getTask().declaresBattle(request)
          && scriptHash.equals(digest(definition.getTask().getSourceDefinition()));
    }
  }

  public static int canonicalEvent(Player player, int requested) {
    var objective = BY_REQUEST.get(requested);
    if (objective == null) return requested;
    int world = player.getWorldLevel();
    // A future real event row takes precedence; never replace an existing battle by its neighbour.
    if (requested != objective.event()
        && GameData.getPlaneEventExcelMap().get(requested, world) != null) return requested;
    var scene = player.getScene();
    var g = player.getGameplay();
    if (world < 0 || world > 6 || player.isInBattle() || !StoryDoors.world(scene)
        || scene.getPlayer() != player
        || scene.getPlaneId() != objective.plane() || scene.getFloorId() != objective.floor()
        || player.getPlaneId() != objective.plane() || player.getFloorId() != objective.floor()
        || player.getInstance() != null || g.getCurrentMissionRaid() != 0 || g.getTrialRun() != null
        || g.getRogue().isInside() || g.getParkourVenue().inside() || g.getStoryLines().getCurrentId() != 0
        || !MissionStateRecords.doing(g.getSubMissions().get(objective.sub()), objective.sub(), 1)
        || !MissionStateRecords.subCompleted(player, objective.prerequisite())
        || !player.getMissionManager().canFinishFromAction(objective.sub())
        || !objective.matches(GameplayDefinitions.subs.get(objective.sub()))
        || SceneEventBattles.resolveClientEvent(objective.event(), world) == null) return 0;
    return objective.event();
  }

  /** Returns the reference graph's event when the companion objective is verified. */
  static int companionRequest(MissionInfo.SubMissionInfo definition) {
    var objective = COMPANION_OBJECTIVES.get(definition.getId());
    return objective != null && objective.matches(definition) ? objective.request() : 0;
  }

  private static String digest(String text) {
    if (text == null) return null;
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
  }

  private StoryBattleEvents() {}
}
