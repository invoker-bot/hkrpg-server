package org.hkrpg.server.gameplay;

import emu.lunarcore.data.GameData;
import emu.lunarcore.data.config.MissionInfo;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.server.packet.Retcode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import org.hkrpg.server.GameDiagnostics;

/** Authored world encounters. Only the guarded requests can resolve a missing reference event. */
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
  private static final Objective GRAFFITI = new Objective(
      20003224, 20003225, 2000701, 200070107, 200070106, 20001, 20001001, "Companion",
      "6a4a19e1fc23cc80c6be37741246d6efee3727acf1f64a81b870cc04963b5b20");
  private static final Objective CONTROL_ROOM = new Objective(
      20003226, 20003226, 2000701, 200070119, 200070118, 20002, 20002001, "Companion",
      "b7508db2c7e4da1d6836c9a5ac41056602f17763d2bbe4daa9b856e4d457f16b");
  private static final Map<Integer, Objective> BY_REQUEST = Map.of(
      STATION_BOSS.request(), STATION_BOSS,
      FOLLOW_PEPPY.request(), FOLLOW_PEPPY,
      RESCUE_ARLAN.request(), RESCUE_ARLAN,
      RESCUE_ARLAN.event(), RESCUE_ARLAN,
      GRAFFITI.request(), GRAFFITI,
      GRAFFITI.event(), GRAFFITI,
      CONTROL_ROOM.event(), CONTROL_ROOM);
  private static final Map<Integer, Objective> GUARDED_WORLD_WINS = Map.of(
      FOLLOW_PEPPY.sub(), FOLLOW_PEPPY, RESCUE_ARLAN.sub(), RESCUE_ARLAN,
      GRAFFITI.sub(), GRAFFITI, CONTROL_ROOM.sub(), CONTROL_ROOM);

  record Objective(
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

    boolean inOriginalWorld(Player player) {
      var scene = player.getScene();
      var gameplay = player.getGameplay();
      int world = player.getWorldLevel();
      return world >= 0 && world <= 6 && StoryDoors.world(scene) && scene.getPlayer() == player
          && scene.getPlaneId() == plane && scene.getFloorId() == floor
          && player.getPlaneId() == plane && player.getFloorId() == floor
          && player.getInstance() == null && gameplay.getCurrentMissionRaid() == 0
          && gameplay.getTrialRun() == null && !gameplay.getRogue().isInside()
          && !gameplay.getParkourVenue().inside() && gameplay.getStoryLines().getCurrentId() == 0;
    }

    boolean prerequisiteCompleted(Player player) {
      return MissionStateRecords.subCompleted(player, prerequisite);
    }

    boolean matchesEvent(int enteredEvent, int world) {
      return enteredEvent == event
          || (enteredEvent == request && BY_REQUEST.get(request) == this
              && GameData.getPlaneEventExcelMap().get(request, world) == null);
    }

    void report(Player player, String command, int subject, Retcode result, String reason, int stage) {
      GameDiagnostics.outcome(player.getSession(), command, subject, result, reason,
          "objective=" + sub + " event=" + event + " stage=" + stage + " world=" + player.getWorldLevel());
    }
  }

  public static int canonicalEvent(Player player, int requested) {
    var objective = BY_REQUEST.get(requested);
    if (objective == null) return requested;
    int world = player.getWorldLevel();
    // A future real event row takes precedence; never replace an existing battle by its neighbour.
    if (requested != objective.event()
        && GameData.getPlaneEventExcelMap().get(requested, world) != null) return requested;
    var state = player.getGameplay().getSubMissions().get(objective.sub());
    if (player.isInBattle() || !objective.inOriginalWorld(player)
        || !MissionStateRecords.doing(state, objective.sub(), 1)
        || !objective.prerequisiteCompleted(player)
        || !player.getMissionManager().canFinishFromAction(objective.sub()))
      return reject(player, requested, objective, "story-battle-inactive");
    if (!objective.matches(GameplayDefinitions.subs.get(objective.sub())))
      return reject(player, requested, objective, "story-battle-source");
    if (SceneEventBattles.resolveClientEvent(objective.event(), world) == null)
      return reject(player, requested, objective, "story-battle-stage");
    return objective.event();
  }

  private static int reject(Player player, int requested, Objective objective, String reason) {
    objective.report(player, "SceneEnterStageCsReq", requested, Retcode.REQ_PARA_INVALID, reason, 0);
    return 0;
  }

  static Objective worldWin(int sub) {
    var guarded = GUARDED_WORLD_WINS.get(sub);
    return guarded != null ? guarded : WorldStageWinSources.OBJECTIVES.get(sub);
  }

  private static String digest(String text) {
    if (text == null) return null;
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
  }

  private StoryBattleEvents() {}
}
