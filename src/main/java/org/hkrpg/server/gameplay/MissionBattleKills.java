package org.hkrpg.server.gameplay;

import emu.lunarcore.data.GameData;
import emu.lunarcore.data.config.MissionInfo;
import emu.lunarcore.game.battle.Battle;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.game.quest.QuestState;
import java.util.*;

/** Frozen scripted battle identity; client statistics remain reports, not input replay. */
public final class MissionBattleKills {
  public record Monster(int entityId, int monsterId, int stageId, int deathSource) {}

  public record Context(
      int eventId,
      int stageId,
      int worldLevel,
      int planeId,
      int floorId,
      int entryId,
      Map<Integer, Integer> roster,
      Map<Integer, Integer> targets) {
    public Context {
      roster = Map.copyOf(roster);
      targets = Map.copyOf(targets);
    }

    public boolean matches(Player player, Battle battle, MissionInfo.SubMissionInfo config) {
      if (StageEventDifficultySettings.world(player, battle) != worldLevel
          || player.getPlaneId() != planeId
          || player.getFloorId() != floorId
          || player.getEntryId() != entryId
          || player.getGameplay().getStoryLines().getCurrentId() != 0
          || battle.getStage().getId() != stageId
          || !condition(config, eventId, planeId, floorId)
          || !Objects.equals(targets.get(config.getId()), config.getParamIntList()[0]))
        return false;
      var seen = new HashSet<Integer>();
      var counts = new HashMap<Integer, Integer>();
      boolean killed = false;
      for (var monster : battle.getReportedMonsters()) {
        if (monster.entityId() <= 0 || !seen.add(monster.entityId())) return false;
        if (monster.stageId() != stageId || !roster.containsKey(monster.monsterId())) continue;
        if (counts.merge(monster.monsterId(), 1, Integer::sum) > roster.get(monster.monsterId()))
          return false;
        if (monster.monsterId() == config.getParamIntList()[0]
            && (monster.deathSource() == 1 || monster.deathSource() == 2)) killed = true;
      }
      return killed;
    }
  }

  public static Context capture(Player player, Battle battle, int eventId, int worldLevel) {
    var event = GameData.getPlaneEventExcelMap().get(eventId, worldLevel);
    if (event == null
        || event.getEventId() != eventId
        || event.getWorldLevel() != worldLevel
        || event.getStageId() != battle.getStage().getId()
        || player.getBattle() != battle
        || battle.isSettling()
        || player.getGameplay().getStoryLines().getCurrentId() != 0) return null;
    var roster = new HashMap<Integer, Integer>();
    for (var wave : battle.getWaves())
      if (wave.getStage().getId() == battle.getStage().getId())
        for (int monster : wave.getMonsters()) roster.merge(monster, 1, Integer::sum);
    if (roster.isEmpty()) return null;
    var targets = new HashMap<Integer, Integer>();
    for (var state : player.getGameplay().getSubMissions().values()) {
      var config = GameplayDefinitions.subs.get(state.getQuestId());
      if (state.getPhase() == QuestState.Phase.DOING
          && condition(config, eventId, player.getPlaneId(), player.getFloorId())
          && roster.containsKey(config.getParamIntList()[0])
          && player.getMissionManager().canFinishFromAction(config.getId())
          && config.getTask() != null
          && config.getTask().declaresBattle(eventId))
        targets.put(config.getId(), config.getParamIntList()[0]);
    }
    return targets.isEmpty()
        ? null
        : new Context(
            eventId,
            battle.getStage().getId(),
            worldLevel,
            player.getPlaneId(),
            player.getFloorId(),
            player.getEntryId(),
            roster,
            targets);
  }

  private static boolean condition(
      MissionInfo.SubMissionInfo config, int event, int plane, int floor) {
    return config != null
        && "KillMonsterInBattle".equals(config.getFinishType())
        && "IntContainListContain".equals(config.getParamType())
        && config.getProgress() == 1
        && config.getParamInt1() == event
        && config.getParamInt2() == 0
        && config.getParamInt3() == 0
        && config.getParamStr1() != null
        && config.getParamStr1().isBlank()
        && config.getParamIntList() != null
        && config.getParamIntList().length == 1
        && config.getParamIntList()[0] != null
        && config.getParamIntList()[0] > 0
        && config.getParamItemList() != null
        && config.getParamItemList().isEmpty()
        && config.getLevelPlaneID() > 0
        && config.getLevelPlaneID() == plane
        && config.getLevelFloorID() > 0
        && config.getLevelFloorID() == floor
        && config.getMazePlaneID() == 0
        && config.getMazeFloorID() == 0
        && config.getIgnoreVerseParamList() != null
        && config.getIgnoreVerseParamList().isEmpty()
        && config.getStoryLineIDList() != null
        && config.getStoryLineIDList().isEmpty();
  }

  private MissionBattleKills() {}
}
