package org.hkrpg.server.gameplay;

import emu.lunarcore.data.GameData;
import emu.lunarcore.data.config.MissionInfo;
import emu.lunarcore.data.config.MissionTaskInfo;
import emu.lunarcore.game.battle.Battle;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.game.quest.QuestState;
import emu.lunarcore.proto.BattleStatisticsOuterClass.BattleStatistics;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Explicit scripted event and exact finite reported value; this does not replay battle inputs. */
public final class MissionBattleCustomValues {
  public record Target(
      int main,
      String key,
      int expected,
      int trigger,
      MissionTaskInfo script,
      QuestState original,
      QuestState originalTrigger) {}

  public record Context(
      int event,
      int stage,
      int worldLevel,
      int plane,
      int floor,
      int entry,
      int raid,
      GameplayState.MissionRaidRun originalRaid,
      Map<Integer, Target> targets) {
    public Context {
      targets = Map.copyOf(targets);
    }

    public boolean matches(Player player, Battle battle, MissionInfo.SubMissionInfo config) {
      var target = targets.get(config.getId());
      if (target == null
          || StageEventDifficultySettings.world(player, battle) != worldLevel
          || player.getPlaneId() != plane
          || player.getFloorId() != floor
          || player.getEntryId() != entry
          || !matchesScope(player)
          || battle.getStage().getId() != stage
          || !condition(config, event, plane, floor)
          || config.getMainMissionID() != target.main()
          || !config.getParamStr1().equals(target.key())
          || config.getParamInt1() != target.expected()) return false;
      var manager = player.getMissionManager();
      var state = manager.sub(config.getId());
      var trigger = manager.sub(target.trigger());
      var triggerConfig = GameplayDefinitions.subs.get(target.trigger());
      if (state != target.original()
          || !doing(state, config.getId())
          || trigger != target.originalTrigger()
          || trigger.getQuestId() != target.trigger()
          || triggerConfig == null
          || triggerConfig.getMainMissionID() != target.main()
          || !triggerCondition(triggerConfig, event, plane, floor)
          || triggerConfig.getTask() != target.script()
          || !target.script().declaresBattle(event)
          || !activeParent(player, target.main())
          || !manager.canFinishFromAction(config.getId())
          || !manager.canFinishFromAction(target.trigger())) return false;
      var values = values(battle.getStats());
      return values != null
          && values.containsKey(target.key())
          && (double) values.get(target.key()) == target.expected();
    }

    public boolean matchesStageWin(Player player, Battle battle, MissionInfo.SubMissionInfo c) {
      if (!matchesScope(player)
          || StageEventDifficultySettings.world(player, battle) != worldLevel
          || player.getPlaneId() != plane
          || player.getFloorId() != floor
          || player.getEntryId() != entry
          || battle.getStage().getId() != stage
          || !triggerCondition(c, event, plane, floor)) return false;
      var manager = player.getMissionManager();
      for (var target : targets.values()) {
        if (c.getId() == target.trigger()
            && c.getMainMissionID() == target.main()
            && manager.sub(c.getId()) == target.originalTrigger()
            && doing(target.originalTrigger(), c.getId())
            && c.getTask() == target.script()
            && c.getTask().declaresBattle(event)
            && activeParent(player, target.main())
            && manager.canFinishFromAction(c.getId())) return true;
      }
      return false;
    }

    private boolean matchesScope(Player player) {
      return available(player)
          && player.getGameplay().getCurrentMissionRaid() == raid
          && new MissionRaid(player).current() == originalRaid;
    }
  }

  public static Context capture(Player player, Battle battle, int eventId, int worldLevel) {
    var event = GameData.getPlaneEventExcelMap().get(eventId, worldLevel);
    if (eventId <= 0
        || event == null
        || event.getEventId() != eventId
        || event.getWorldLevel() != worldLevel
        || StageEventDifficultySettings.world(player, battle) != worldLevel
        || event.getStageId() != battle.getStage().getId()
        || player.getBattle() != battle
        || battle.getPlayer() != player
        || battle.isSettling()
        || !available(player)
        || battle.getWaves().isEmpty()) return null;
    var manager = player.getMissionManager();
    var targets = new HashMap<Integer, Target>();
    for (var state : player.getGameplay().getSubMissions().values()) {
      var c = GameplayDefinitions.subs.get(state.getQuestId());
      if (!condition(c, eventId, player.getPlaneId(), player.getFloorId())
          || !belongsToCurrentRaid(player, c.getMainMissionID())
          || !doing(state, c.getId())
          || !activeParent(player, c.getMainMissionID())
          || !manager.canFinishFromAction(c.getId())) continue;
      for (var trigger : player.getGameplay().getSubMissions().values()) {
        var t = GameplayDefinitions.subs.get(trigger.getQuestId());
        if (triggerCondition(t, eventId, player.getPlaneId(), player.getFloorId())
            && t.getMainMissionID() == c.getMainMissionID()
            && doing(trigger, t.getId())
            && t.getLevelPlaneID() == player.getPlaneId()
            && t.getLevelFloorID() == player.getFloorId()
            && manager.canFinishFromAction(t.getId())
            && t.getTask() != null
            && t.getTask().declaresBattle(eventId)) {
          targets.put(
              c.getId(),
              new Target(
                  c.getMainMissionID(),
                  c.getParamStr1(),
                  c.getParamInt1(),
                  t.getId(),
                  t.getTask(),
                  state,
                  trigger));
          break;
        }
      }
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
            player.getGameplay().getCurrentMissionRaid(),
            new MissionRaid(player).current(),
            targets);
  }

  private static boolean available(Player player) {
    var run = player.getGameplay().getRogue();
    return player.getGameplay().getStoryLines().getCurrentId() == 0
        && !player.getGameplay().getStoryLines().isTransitionPending()
        && validRaid(player)
        && player.getGameplay().getTrialRun() == null
        && player.getInstance() == null
        && (run == null || !run.isInside());
  }

  private static boolean validRaid(Player player) {
    if (player.getGameplay().getCurrentMissionRaid() == 0) return true;
    var raid = new MissionRaid(player);
    var run = raid.current();
    return run != null && run.getPendingTransfer() == 0 && raid.activeFor(run.getMainMission());
  }

  private static boolean belongsToCurrentRaid(Player player, int main) {
    var run = new MissionRaid(player).current();
    return player.getGameplay().getCurrentMissionRaid() == 0
        || run != null && run.getMainMission() == main;
  }

  private static boolean doing(QuestState state, int id) {
    return state != null
        && state.getQuestId() == id
        && state.getPhase() == QuestState.Phase.DOING
        && state.getProgress() == 0
        && state.getFinishTime() == 0;
  }

  private static boolean activeParent(Player player, int main) {
    var state = player.getGameplay().getMainMissions().get(main);
    return main > 0
        && state != null
        && state.getQuestId() == main
        && state.getPhase() == QuestState.Phase.DOING
        && state.getFinishTime() == 0;
  }

  private static boolean condition(MissionInfo.SubMissionInfo c, int event, int plane, int floor) {
    return c != null
        && c.getId() > 0
        && "BattleWinWithCustomValue".equals(c.getFinishType())
        && "Equal".equals(c.getParamType())
        && c.getParamInt2() == event
        && event > 0
        && (c.getParamInt1() == 0 || c.getParamInt1() == 1)
        && c.getParamInt3() == 0
        && validKey(c.getParamStr1())
        && c.getParamIntList().length == 0
        && c.getParamItemList().isEmpty()
        && c.getProgress() == 1
        && !c.isBackTrack()
        && c.getLevelPlaneID() > 0
        && c.getLevelPlaneID() == plane
        && c.getLevelFloorID() > 0
        && c.getLevelFloorID() == floor
        && c.getMazePlaneID() == 0
        && c.getMazeFloorID() == 0
        && c.getIgnoreVerseParamList().isEmpty()
        && c.getStoryLineIDList().isEmpty();
  }

  private static boolean triggerCondition(
      MissionInfo.SubMissionInfo c, int event, int plane, int floor) {
    return c != null
        && c.getId() > 0
        && "StageWin".equals(c.getFinishType())
        && "Equal".equals(c.getParamType())
        && c.getParamInt1() == event
        && event > 0
        && c.getParamInt2() == 0
        && c.getParamInt3() == 0
        && c.getParamStr1().isBlank()
        && c.getParamIntList().length == 0
        && c.getParamItemList().isEmpty()
        && c.getProgress() == 1
        && !c.isBackTrack()
        && c.getLevelPlaneID() == plane
        && plane > 0
        && c.getLevelFloorID() == floor
        && floor > 0
        && c.getMazePlaneID() == 0
        && c.getMazeFloorID() == 0
        && c.getIgnoreVerseParamList().isEmpty()
        && c.getStoryLineIDList().isEmpty();
  }

  public static boolean validKey(String key) {
    return key != null && !key.isEmpty() && key.getBytes(StandardCharsets.UTF_8).length <= 128;
  }

  private static Map<String, Float> values(BattleStatistics stats) {
    if (stats == null || stats.getCustomValues().length() > 128) return null;
    var values = new HashMap<String, Float>();
    for (var entry : stats.getCustomValues()) {
      if (!validKey(entry.getKey())
          || !Float.isFinite(entry.getValue())
          || values.putIfAbsent(entry.getKey(), entry.getValue()) != null) return null;
    }
    return values;
  }

  public static boolean validStatistics(BattleStatistics stats) {
    return values(stats) != null;
  }

  private MissionBattleCustomValues() {}
}
