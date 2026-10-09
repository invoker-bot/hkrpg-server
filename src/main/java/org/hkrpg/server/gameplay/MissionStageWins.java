package org.hkrpg.server.gameplay;

import emu.lunarcore.data.GameData;
import emu.lunarcore.data.config.MissionInfo;
import emu.lunarcore.game.battle.Battle;
import emu.lunarcore.game.battle.BattleStage;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.game.quest.QuestState;
import java.util.*;

/** Authored decimal stage-family prefixes, frozen against the actual accepted battle-start view. */
public final class MissionStageWins {
  private record Clause(int main, int prefix, int progress, int plane, int floor, String script) {}

  private record Target(
      MissionInfo.SubMissionInfo definition,
      QuestState main,
      QuestState sub,
      int progress,
      String mainKey,
      Clause clause) {}

  public static final class Context {
    private final Player owner;
    private final BattleStage stage;
    private final int stageId;
    private final int world, plane, floor, entry;
    private final Map<Integer, Target> targets;

    private Context(Player owner, BattleStage stage, Map<Integer, Target> targets) {
      this.owner = owner;
      this.stage = stage;
      this.stageId = stage == null ? 0 : stage.getId();
      this.world = owner.getWorldLevel();
      this.plane = owner.getPlaneId();
      this.floor = owner.getFloorId();
      this.entry = owner.getEntryId();
      this.targets = Map.copyOf(targets);
    }

    public boolean matches(Player player, Battle battle, MissionInfo.SubMissionInfo definition) {
      var target = definition == null ? null : targets.get(definition.getId());
      if (player != owner
          || battle.getPlayer() != player
          || battle.getStage() != stage
          || stage == null
          || stage.getId() != stageId
          || GameData.getStageExcelMap().get(stageId) != stage
          || target == null
          || player.getWorldLevel() != world
          || player.getPlaneId() != plane
          || player.getFloorId() != floor
          || player.getEntryId() != entry
          || !available(player)
          || target.definition() != definition
          || !target.clause().equals(clause(definition))
          || !condition(definition, stage.getId(), plane, floor)) return false;
      var g = player.getGameplay();
      return g.getMainMissions().get(definition.getMainMissionID()) == target.main()
          && MissionStateRecords.mainDoing(player, definition.getMainMissionID())
          && g.getSubMissions().get(definition.getId()) == target.sub()
          && MissionStateRecords.doing(
              target.sub(), definition.getId(), Math.max(1, definition.getProgress()))
          && target.sub().getProgress() == target.progress()
          && Objects.equals(
              target.mainKey(), DailyMissionRepeats.mainKey(player, definition.getMainMissionID()))
          && player.getMissionManager().canFinishFromAction(definition.getId());
    }
  }

  public static Context capture(Player player, Battle battle) {
    synchronized (player) {
      var targets = new HashMap<Integer, Target>();
      var stage = battle.getStage();
      var source = stage == null ? null : GameData.getStageExcelMap().get(stage.getId());
      if (battle.getPlayer() == player
          && source != null
          && source == stage
          && !battle.getWaves().isEmpty()
          && available(player)) {
        var g = player.getGameplay();
        for (var state : g.getSubMissions().values()) {
          var c = state == null ? null : GameplayDefinitions.subs.get(state.getQuestId());
          if (condition(c, stage.getId(), player.getPlaneId(), player.getFloorId())
              && MissionStateRecords.doing(state, c.getId(), Math.max(1, c.getProgress()))
              && player.getMissionManager().canFinishFromAction(c.getId()))
            targets.put(
                c.getId(),
                new Target(
                    c,
                    g.getMainMissions().get(c.getMainMissionID()),
                    state,
                    state.getProgress(),
                    DailyMissionRepeats.mainKey(player, c.getMainMissionID()),
                    clause(c)));
        }
      }
      return new Context(player, stage, targets);
    }
  }

  private static boolean available(Player player) {
    var run = player.getGameplay().getRogue();
    return player.getGameplay().getStoryLines().getCurrentId() == 0
        && !player.getGameplay().getStoryLines().isTransitionPending()
        && player.getGameplay().getCurrentMissionRaid() == 0
        && player.getGameplay().getTrialRun() == null
        && player.getInstance() == null
        && (run == null || !run.isInside());
  }

  private static Clause clause(MissionInfo.SubMissionInfo c) {
    return new Clause(
        c.getMainMissionID(),
        c.getParamInt1(),
        c.getProgress(),
        c.getLevelPlaneID(),
        c.getLevelFloorID(),
        MissionStageScripts.signature(c));
  }

  static boolean repeatable(MissionInfo.SubMissionInfo c) {
    return supported(c)
        && GameData.getStageExcelMap().values().stream()
            .anyMatch(stage -> prefix(c.getParamInt1(), stage.getId()));
  }

  private static boolean prefix(int authored, int stage) {
    return authored > 0
        && stage > 0
        && Integer.toString(stage).startsWith(Integer.toString(authored));
  }

  private static boolean condition(MissionInfo.SubMissionInfo c, int stage, int plane, int floor) {
    return supported(c)
        && prefix(c.getParamInt1(), stage)
        && (c.getLevelPlaneID() == 0 || c.getLevelPlaneID() == plane)
        && (c.getLevelFloorID() == 0 || c.getLevelFloorID() == floor);
  }

  private static boolean supported(MissionInfo.SubMissionInfo c) {
    return c != null
        && c.getId() > 0
        && "StageWin".equals(c.getFinishType())
        && "Equal".equals(c.getParamType())
        && c.getProgress() >= 0
        && c.getParamInt1() > 0
        && c.getParamInt2() == 0
        && c.getParamInt3() == 0
        && c.getParamStr1() != null
        && c.getParamStr1().isBlank()
        && c.getParamIntList() != null
        && c.getParamIntList().length == 0
        && c.getParamItemList() != null
        && c.getParamItemList().isEmpty()
        && c.getIgnoreVerseParamList() != null
        && c.getIgnoreVerseParamList().isEmpty()
        && c.getStoryLineID() == 0
        && c.getStoryLineIDList() != null
        && c.getStoryLineIDList().isEmpty()
        && c.getStoryTrialTask() == null
        && MissionStageScripts.signature(c) != null
        && c.getMazePlaneID() == 0
        && c.getMazeFloorID() == 0
        && !c.isBackTrack();
  }
}
