package org.hkrpg.server.gameplay;

import emu.lunarcore.data.GameData;
import emu.lunarcore.data.config.MissionInfo;
import emu.lunarcore.game.battle.Battle;
import emu.lunarcore.game.battle.BattleStage;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.game.quest.QuestState;
import emu.lunarcore.game.scene.Scene;
import emu.lunarcore.server.packet.Retcode;
import java.util.*;

/** Story event mappings and legacy stage-family prefixes, frozen at accepted battle start. */
public final class MissionStageWins {
  private record Clause(
      int main, int stageOrEvent, int progress, int plane, int floor,
      boolean eventMapped, StoryBattleEvents.Objective worldBattle, String script) {}

  private record Target(
      MissionInfo.SubMissionInfo definition,
      QuestState main,
      QuestState sub,
      int progress,
      String mainKey,
      Clause clause,
      MissionBattleAcceptance raidAcceptance) {}

  public static final class Context {
    private final Player owner;
    private final BattleStage stage;
    private final Scene scene;
    private final int stageId;
    private final int world, plane, floor, entry;
    private final Map<Integer, Target> targets;
    private final MissionRaidBattleScope raid;

    private Context(
        Player owner,
        BattleStage stage,
        Map<Integer, Target> targets,
        MissionRaidBattleScope raid) {
      this.owner = owner;
      this.stage = stage;
      this.scene = owner.getScene();
      this.stageId = stage == null ? 0 : stage.getId();
      this.world = owner.getWorldLevel();
      this.plane = owner.getPlaneId();
      this.floor = owner.getFloorId();
      this.entry = owner.getEntryId();
      this.targets = Map.copyOf(targets);
      this.raid = raid;
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
          || (raid == null
              ? player.getGameplay().getCurrentMissionRaid() != 0
              : !raid.matches(player))
          || target.definition() != definition
          || !target.clause().equals(clause(definition))
          || !condition(target.clause(), stage.getId(), world, plane, floor)) return false;
      var worldBattle = target.clause().worldBattle();
      if (worldBattle != null && !worldBattle.matchesEvent(battle.getEventId(), world)) return false;
      if (worldBattle != null && (player.getScene() != scene || !worldBattle.inOriginalWorld(player)
          || !worldBattle.prerequisiteCompleted(player))) {
        worldBattle.report(player, "MissionStageWin", definition.getId(), Retcode.MISSION_NOT_DOING,
            "story-battle-context-changed", stageId);
        return false;
      }
      var g = player.getGameplay();
      boolean matched = g.getMainMissions().get(definition.getMainMissionID()) == target.main()
          && MissionStateRecords.mainDoing(player, definition.getMainMissionID())
          && g.getSubMissions().get(definition.getId()) == target.sub()
          && MissionStateRecords.doing(
              target.sub(), definition.getId(), Math.max(1, definition.getProgress()))
          && target.sub().getProgress() == target.progress()
          && Objects.equals(
              target.mainKey(), DailyMissionRepeats.mainKey(player, definition.getMainMissionID()))
          && (raid == null
              || target.raidAcceptance() != null
                  && target.raidAcceptance().matches(player, definition))
          && player.getMissionManager().canFinishFromAction(definition.getId());
      if (matched && worldBattle != null)
        worldBattle.report(player, "MissionStageWin", definition.getId(), Retcode.SUCC,
            "story-battle-matched", stageId);
      return matched;
    }
  }

  public static Context capture(Player player, Battle battle) {
    synchronized (player) {
      var targets = new HashMap<Integer, Target>();
      var stage = battle.getStage();
      var source = stage == null ? null : GameData.getStageExcelMap().get(stage.getId());
      var raid = MissionRaidBattleScope.capture(player);
      if (battle.getPlayer() == player
          && source != null
          && source == stage
          && !battle.getWaves().isEmpty()
          && available(player)
          && (player.getGameplay().getCurrentMissionRaid() == 0 || raid != null)) {
        var g = player.getGameplay();
        for (var state : g.getSubMissions().values()) {
          var c = state == null ? null : GameplayDefinitions.subs.get(state.getQuestId());
          if (c == null || !MissionStateRecords.doing(state, c.getId(), Math.max(1, c.getProgress())))
            continue;
          var clause = captureClause(player, c, stage);
          if (condition(
                  clause,
                  stage.getId(),
                  player.getWorldLevel(),
                  player.getPlaneId(),
                  player.getFloorId())
              && (raid == null || raid.owns(c.getMainMissionID()))
              && player.getMissionManager().canFinishFromAction(c.getId()))
            targets.put(
                c.getId(),
                new Target(
                    c,
                    g.getMainMissions().get(c.getMainMissionID()),
                    state,
                    state.getProgress(),
                    DailyMissionRepeats.mainKey(player, c.getMainMissionID()),
                    clause,
                    raid == null ? null : MissionBattleAcceptance.capture(player, c)));
        }
      }
      return new Context(player, stage, targets, raid);
    }
  }

  private static Clause captureClause(Player player, MissionInfo.SubMissionInfo c, BattleStage stage) {
    var authored = StoryBattleEvents.worldWin(c.getId());
    var clause = clause(c);
    if (authored == null || SceneEventBattles.resolve(authored.event(), player.getWorldLevel()) != stage)
      return clause;
    if (clause == null) {
      authored.report(player, "MissionStageWin", c.getId(), Retcode.MISSION_FINISH_WAY_NOT_MATCH,
          "story-battle-source", stage.getId());
      return null;
    }
    if (!authored.inOriginalWorld(player) || !authored.prerequisiteCompleted(player)) return null;
    return clause;
  }

  private static boolean available(Player player) {
    var run = player.getGameplay().getRogue();
    return player.getGameplay().getStoryLines().getCurrentId() == 0
        && !player.getGameplay().getStoryLines().isTransitionPending()
        && player.getGameplay().getTrialRun() == null
        && player.getInstance() == null
        && (run == null || !run.isInside());
  }

  private static Clause clause(MissionInfo.SubMissionInfo c) {
    if (!supported(c)) return null;
    var worldBattle = StoryBattleEvents.worldWin(c.getId());
    if (worldBattle != null && !worldBattle.matches(c)) return null;
    boolean eventMapped = mainline(c) || worldBattle != null;
    String script = script(c, eventMapped);
    if (script == null) return null;
    return new Clause(
        c.getMainMissionID(),
        c.getParamInt1(),
        c.getProgress(),
        c.getLevelPlaneID(),
        c.getLevelFloorID(),
        eventMapped,
        worldBattle,
        script);
  }

  private static boolean mainline(MissionInfo.SubMissionInfo c) {
    var main = GameData.getMainMissionExcelMap().get(c.getMainMissionID());
    return main != null && "Main".equals(main.getType()) && !main.isInRaid();
  }

  private static String script(MissionInfo.SubMissionInfo c, boolean eventMapped) {
    if (!eventMapped || c.getTask() == null) return MissionStageScripts.signature(c);
    // These presentation scripts run on the client. Their action shape must not gate
    // the server's StageWin condition (the opening script even declares a legacy event).
    // Retain the complete source in the battle snapshot so a reload cannot change it.
    var source = c.getTask().getSourceDefinition();
    return "Mission".equals(c.getTask().getType())
            && source != null
            && !source.isBlank()
            && source.length() <= 1048576
        ? source
        : null;
  }

  static boolean repeatable(MissionInfo.SubMissionInfo c) {
    return clause(c) != null
        && MissionStageScripts.signature(c) != null
        && GameData.getStageExcelMap().values().stream()
            .anyMatch(stage -> prefix(c.getParamInt1(), stage.getId()));
  }

  private static boolean prefix(int authored, int stage) {
    return authored > 0
        && stage > 0
        && Integer.toString(stage).startsWith(Integer.toString(authored));
  }

  private static boolean condition(Clause clause, int stage, int world, int plane, int floor) {
    if (clause == null || (clause.plane() != 0 && clause.plane() != plane)
        || (clause.floor() != 0 && clause.floor() != floor)) return false;
    if (!clause.eventMapped()) return prefix(clause.stageOrEvent(), stage);
    var mapped = SceneEventBattles.resolve(clause.stageOrEvent(), world);
    return mapped != null && mapped.getId() == stage;
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
        && c.getMazePlaneID() == 0
        && c.getMazeFloorID() == 0
        && !c.isBackTrack();
  }
}
