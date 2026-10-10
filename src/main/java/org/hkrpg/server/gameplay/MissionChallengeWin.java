package org.hkrpg.server.gameplay;

import emu.lunarcore.data.GameData;
import emu.lunarcore.data.config.MissionInfo;
import emu.lunarcore.game.battle.Battle;
import emu.lunarcore.game.challenge.ChallengeInstance;
import emu.lunarcore.game.challenge.ChallengeType;
import emu.lunarcore.game.enums.PlaneType;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.game.quest.QuestState;
import emu.lunarcore.game.scene.entity.EntityMonster;
import emu.lunarcore.proto.BattleEndStatusOuterClass.BattleEndStatus;
import emu.lunarcore.proto.ChallengeStatusOuterClass.ChallengeStatus;
import java.util.*;

/** Memory challenge completion from validated encounters, independent of star rewards. */
public final class MissionChallengeWin {
  public record Target(MissionBattleAcceptance acceptance) {}

  public record Combatant(
      EntityMonster monster, int group, int instance, int npc, int event, int world) {
    boolean unchanged() {
      return monster.getGroupId() == group
          && monster.getInstId() == instance
          && monster.getExcel() != null
          && monster.getExcel().getId() == npc
          && monster.getEventId() == event
          && monster.getWorldLevel() == world;
    }
  }

  public record Context(
      ChallengeInstance challenge,
      int challengeId,
      int half,
      int halves,
      int entry,
      int plane,
      int floor,
      int world,
      int stage,
      List<Combatant> combatants,
      Map<Integer, Target> targets) {
    public Context {
      combatants = List.copyOf(combatants);
      targets = Map.copyOf(targets);
    }

    public boolean completed(Player player, Battle battle) {
      return player.getBattle() == battle
          && battle.isSettling()
          && battle.getResult() == BattleEndStatus.BATTLE_END_WIN
          && battle.getStats() != null
          && player.getInstance() == challenge
          && challenge.getStatus() == ChallengeStatus.CHALLENGE_FINISH_VALUE
          && challenge.getChallengeId() == challengeId
          && challenge.getCurrentStage() == half
          && half == halves
          && challenge.getExcel() != null
          && challenge.getExcel().getStageNum() == halves
          && challenge.getType() == ChallengeType.MEMORY
          && player.getEntryId() == entry
          && player.getPlaneId() == plane
          && player.getFloorId() == floor
          && player.getWorldLevel() == world
          && player.getGameplay().getStoryLines().getCurrentId() == 0
          && player.getCurrentLineup() == battle.getLineup()
          && battle.getStage().getId() == stage
          && battle.getNpcMonsters().size() == combatants.size()
          && combatants.stream()
              .allMatch(c -> battle.getNpcMonsters().contains(c.monster()) && c.unchanged());
    }

    public boolean matchesTarget(Player player, MissionInfo.SubMissionInfo config) {
      if (!condition(config, challengeId)) return false;
      var target = targets.get(config.getId());
      return target != null && target.acceptance().matches(player, config);
    }
  }

  public static Context capture(Player player, Battle battle) {
    if (!(player.getInstance() instanceof ChallengeInstance challenge)
        || challenge.getExcel() == null
        || challenge.getType() != ChallengeType.MEMORY
        || challenge.getStatus() != ChallengeStatus.CHALLENGE_DOING_VALUE
        || player.getBattle() != battle
        || battle.isSettling()
        || battle.getStage() == null
        || battle.getWaves().isEmpty()
        || battle.getNpcMonsters().isEmpty()
        || battle.getLineup() != player.getCurrentLineup()
        || battle.getLineup().getAvatars().isEmpty()
        || battle.getLineup().getExtraLineupType() != challenge.getCurrentExtraLineup()
        || player.getGameplay().getStoryLines().getCurrentId() != 0) return null;
    var excel = challenge.getExcel();
    var entry = GameData.getMapEntranceExcelMap().get(excel.getMapEntranceID());
    var scene = player.getScene();
    int half = challenge.getCurrentStage();
    if (excel.getId() <= 0
        || excel.getId() != challenge.getChallengeId()
        || excel.getStageNum() < 1
        || excel.getStageNum() > 2
        || half < 1
        || half > excel.getStageNum()
        || entry == null
        || player.getEntryId() != excel.getMapEntranceID()
        || scene == null
        || scene.getPlaneType() != PlaneType.Challenge
        || scene.getPlaneId() != entry.getPlaneID()
        || scene.getFloorId() != entry.getFloorID()
        || player.getPlaneId() != entry.getPlaneID()
        || player.getFloorId() != entry.getFloorID()) return null;
    var expected = half == 1 ? excel.getChallengeMonsters1() : excel.getChallengeMonsters2();
    int group = half == 1 ? excel.getMazeGroupID1() : excel.getMazeGroupID2();
    if (group <= 0 || expected == null || expected.isEmpty()) return null;
    var combatants = new ArrayList<Combatant>();
    var identities = new HashSet<Integer>();
    var stages = new HashSet<Integer>();
    for (var monster : battle.getNpcMonsters()) {
      var row = expected.get(monster.getInstId());
      var event =
          GameData.getPlaneEventExcelMap().get(monster.getEventId(), monster.getWorldLevel());
      if (row == null
          || row.getConfigId() != monster.getInstId()
          || row.getNpcMonsterId() <= 0
          || monster.getExcel() == null
          || row.getNpcMonsterId() != monster.getExcel().getId()
          || row.getEventId() <= 0
          || row.getEventId() != monster.getEventId()
          || monster.getGroupId() != group
          || monster.getScene() != scene
          || monster.getEntityId() <= 0
          || !identities.add(monster.getEntityId())
          || scene.getEntities().get(monster.getEntityId()) != monster
          || monster.getWorldLevel() != player.getWorldLevel()
          || event == null
          || event.getEventId() != row.getEventId()
          || event.getWorldLevel() != monster.getWorldLevel()
          || event.getStageId() <= 0
          || monster.getCustomStage() != null
              && monster.getCustomStage() != GameData.getStageExcelMap().get(event.getStageId()))
        return null;
      stages.add(event.getStageId());
      combatants.add(
          new Combatant(
              monster,
              group,
              monster.getInstId(),
              row.getNpcMonsterId(),
              row.getEventId(),
              monster.getWorldLevel()));
    }
    if (!stages.contains(battle.getStage().getId())
        || battle.getWaves().stream()
            .anyMatch(w -> !stages.contains(w.getStage().getId()) || w.getMonsters().isEmpty()))
      return null;
    var targets = new HashMap<Integer, Target>();
    for (var state : player.getGameplay().getSubMissions().values()) {
      var config = GameplayDefinitions.subs.get(state.getQuestId());
      if (state.getPhase() == QuestState.Phase.DOING
          && condition(config, excel.getId())
          && player.getMissionManager().canFinishFromAction(config.getId())) {
        var acceptance = MissionBattleAcceptance.capture(player, config);
        if (acceptance != null) targets.put(config.getId(), new Target(acceptance));
      }
    }
    return new Context(
        challenge,
        excel.getId(),
        half,
        excel.getStageNum(),
        player.getEntryId(),
        player.getPlaneId(),
        player.getFloorId(),
        player.getWorldLevel(),
        battle.getStage().getId(),
        combatants,
        targets);
  }

  static boolean condition(MissionInfo.SubMissionInfo config, int challenge) {
    return config != null
        && challenge > 0
        && config.getProgress() == 1
        && config.getParamInt2() == 0
        && config.getParamInt3() == 0
        && config.getParamStr1() != null
        && config.getParamStr1().isBlank()
        && config.getParamIntList() != null
        && config.getParamIntList().length == 0
        && config.getParamItemList() != null
        && config.getParamItemList().isEmpty()
        && config.getLevelPlaneID() > 0
        && config.getLevelFloorID() > 0
        && config.getMazePlaneID() == 0
        && config.getMazeFloorID() == 0
        && config.getIgnoreVerseParamList() != null
        && config.getIgnoreVerseParamList().isEmpty()
        && config.getStoryLineIDList() != null
        && config.getStoryLineIDList().isEmpty()
        && ("ChallengeFinishCnt".equals(config.getFinishType())
            ? "NoPara".equals(config.getParamType())
                && config.getParamInt1() == 0
                && !config.isBackTrack()
            : "ChallengeFinish".equals(config.getFinishType())
                && "Equal".equals(config.getParamType())
                && config.getParamInt1() == challenge);
  }

  private MissionChallengeWin() {}
}
