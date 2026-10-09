package org.hkrpg.server.gameplay;

import emu.lunarcore.data.ResourceLoader;
import emu.lunarcore.data.config.MissionInfo;
import emu.lunarcore.game.battle.Battle;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.game.quest.QuestState;
import emu.lunarcore.proto.BattleEndReasonOuterClass.BattleEndReason;
import emu.lunarcore.proto.BattleEndStatusOuterClass.BattleEndStatus;
import java.util.*;

/** Accepted, non-backtracked single-step tutorial goals from a completed native story battle. */
public final class StoryChallengeSettlementGoals {
  public record Target(String definition, String script) {
    static Target of(MissionInfo.SubMissionInfo config) {
      return new Target(
          ResourceLoader.getGson().toJson(config),
          config.getTask() == null ? "" : config.getTask().getSourceDefinition());
    }
  }

  public static Map<Integer, Target> capture(Player player) {
    var targets = new HashMap<Integer, Target>();
    for (var state : player.getGameplay().getSubMissions().values()) {
      var config = GameplayDefinitions.subs.get(state.getQuestId());
      if (state.getPhase() == QuestState.Phase.DOING
          && condition(config)
          && player.getMissionManager().canFinishFromAction(config.getId()))
        targets.put(config.getId(), Target.of(config));
    }
    return Map.copyOf(targets);
  }

  public static Map<Integer, Target> completed(Player player, Battle battle) {
    var binding = battle.getStoryChallengeBinding();
    var context = binding == null ? null : binding.context();
    if (context == null
        || !binding.required()
        || player.getBattle() != battle
        || !battle.isSettling()
        || battle.getStats() == null
        || player.getInstance() != context.challenge()
        || !context.challenge().isWin()
        || context.half() != 2
        || context.challenge().getCurrentStage() != 2
        || context.challenge().getChallengeId() != context.challengeId()
        || player.getScene() != context.scene()
        || player.getEntryId() != context.entry()
        || player.getPlaneId() != context.plane()
        || player.getFloorId() != context.floor()
        || player.getWorldLevel() != context.world()
        || player.getCurrentLineup() != context.lineup()
        || !context.roster().equals(player.getCurrentLineup().getAvatars())
        || player.getGameplay().getStoryLines().getCurrentId() != 0
        || battle.getResult() != BattleEndStatus.BATTLE_END_WIN
            && (battle.getResult() != BattleEndStatus.BATTLE_END_LOSE
                || battle.getStats().getEndReason()
                    != BattleEndReason.BATTLE_END_REASON_TURN_LIMIT)) return Map.of();
    return battle.getStorySettlementTargets();
  }

  public static boolean matches(Map<Integer, Target> targets, MissionInfo.SubMissionInfo config) {
    return condition(config) && Objects.equals(targets.get(config.getId()), Target.of(config));
  }

  static boolean condition(MissionInfo.SubMissionInfo config) {
    return config != null
        && config.getId() > 0
        && config.getMainMissionID() > 0
        && "ChallengeStorySettleCnt".equals(config.getFinishType())
        && "NoPara".equals(config.getParamType())
        && config.getProgress() == 1
        && !config.isBackTrack()
        && config.getParamInt1() == 0
        && config.getParamInt2() == 0
        && config.getParamInt3() == 0
        && config.getParamStr1() != null
        && config.getParamStr1().isBlank()
        && config.getParamIntList() != null
        && config.getParamIntList().length == 0
        && config.getParamItemList() != null
        && config.getParamItemList().isEmpty()
        && config.getLevelPlaneID() == 0
        && config.getLevelFloorID() == 0
        && config.getMazePlaneID() == 0
        && config.getMazeFloorID() == 0
        && config.getStoryLineID() == 0
        && config.getStoryLineIDList() != null
        && config.getStoryLineIDList().isEmpty()
        && config.getIgnoreVerseParamList() != null
        && config.getIgnoreVerseParamList().isEmpty()
        && config.getFinishActionList() != null
        && config.getFinishActionList().length == 0;
  }

  private StoryChallengeSettlementGoals() {}
}
