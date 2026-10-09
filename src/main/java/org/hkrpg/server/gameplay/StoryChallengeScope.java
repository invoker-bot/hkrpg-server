package org.hkrpg.server.gameplay;

import emu.lunarcore.data.GameData;
import emu.lunarcore.game.battle.Battle;
import emu.lunarcore.game.battle.BattleMonsterWave;
import emu.lunarcore.game.challenge.ChallengeInstance;
import emu.lunarcore.game.enums.PlaneType;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.game.player.lineup.PlayerLineup;
import emu.lunarcore.game.scene.Scene;
import emu.lunarcore.game.scene.entity.EntityMonster;
import emu.lunarcore.proto.ChallengeStatusOuterClass.ChallengeStatus;
import java.util.*;

/** Native story reports belong to the original encounter and half that created the battle. */
public final class StoryChallengeScope {
  public record Binding(boolean required, Context context) {
    public boolean matches(Player player, Battle battle) {
      return !required || context != null && context.matches(player, battle);
    }
  }

  public record Combatant(
      EntityMonster monster, int entity, int group, int instance, int npc, int event, int world) {
    boolean matches(Scene scene) {
      return monster.getScene() == scene
          && monster.getEntityId() == entity
          && scene.getEntities().get(entity) == monster
          && monster.getGroupId() == group
          && monster.getInstId() == instance
          && monster.getExcel() != null
          && monster.getExcel().getId() == npc
          && monster.getEventId() == event
          && monster.getWorldLevel() == world;
    }
  }

  public record Wave(BattleMonsterWave wave, int stage, int level, List<Integer> monsters) {
    public Wave {
      monsters = List.copyOf(monsters);
    }

    boolean matches() {
      return wave.getStage() == GameData.getStageExcelMap().get(stage)
          && wave.getCustomLevel() == level
          && monsters.equals(wave.getMonsters());
    }
  }

  public record Context(
      ChallengeInstance challenge,
      int challengeId,
      int half,
      int entry,
      int plane,
      int floor,
      int world,
      int score,
      Scene scene,
      PlayerLineup lineup,
      List<Integer> roster,
      List<Integer> choices,
      List<Combatant> combatants,
      List<Wave> waves,
      int stage,
      int rounds) {
    public Context {
      roster = List.copyOf(roster);
      choices = List.copyOf(choices);
      combatants = List.copyOf(combatants);
      waves = List.copyOf(waves);
    }

    public boolean matches(Player player, Battle battle) {
      return player.getBattle() == battle
          && !battle.isSettling()
          && player.getInstance() == challenge
          && challenge.getChallengeId() == challengeId
          && challenge.getExcel() == GameData.getChallengeExcelMap().get(challengeId)
          && StoryChallengeCatalog.supports(challenge.getExcel())
          && challenge.getStatus() == ChallengeStatus.CHALLENGE_DOING_VALUE
          && challenge.getCurrentStage() == half
          && challenge.getTotalScore() == score
          && player.getEntryId() == entry
          && player.getPlaneId() == plane
          && player.getFloorId() == floor
          && player.getWorldLevel() == world
          && player.getScene() == scene
          && scene.getPlaneType() == PlaneType.Challenge
          && scene.getPlaneId() == plane
          && scene.getFloorId() == floor
          && player.getGameplay().getStoryLines().getCurrentId() == 0
          && player.getCurrentLineup() == lineup
          && battle.getLineup() == lineup
          && lineup.getExtraLineupType() == challenge.getCurrentExtraLineup()
          && roster.equals(lineup.getAvatars())
          && choices.equals(challenge.getBuffs())
          && challenge.getRoundsLeft() == rounds
          && battle.getRoundsLimit() == rounds
          && buff(battle, challenge.getExcel().getMazeBuffID())
          && buff(battle, choices.get(half - 1))
          && counter(battle, score)
          && battle.getStage() == GameData.getStageExcelMap().get(stage)
          && battle.getNpcMonsters().size() == combatants.size()
          && combatants.stream().map(Combatant::monster).toList().equals(battle.getNpcMonsters())
          && combatants.stream().allMatch(c -> c.matches(scene))
          && battle.getWaves().size() == waves.size()
          && waves.stream().map(Wave::wave).toList().equals(battle.getWaves())
          && waves.stream().allMatch(Wave::matches);
    }
  }

  private static boolean buff(Battle battle, int id) {
    var buff = battle.getBuffs().get(id);
    return buff != null && buff.getId() == id && buff.getLevel() == 1 && buff.getWaveFlag() == -1;
  }

  private static boolean counter(Battle battle, int score) {
    var list = battle.getBattleTargets() == null ? null : battle.getBattleTargets().get(1);
    return list != null
        && list.getBattleTargetList().length() == 1
        && list.getBattleTargetList().get(0).getId() == 10003
        && list.getBattleTargetList().get(0).getProgress() == score;
  }

  public static Binding capture(Player player, Battle battle) {
    boolean required = isNativeStory(player);
    return new Binding(required, required ? context(player, battle) : null);
  }

  public static boolean isNativeStory(Player player) {
    return player.getInstance() instanceof ChallengeInstance challenge
        && challenge.isStory()
        && player.getSession() != null
        && player.getSession().getProtocol().experimental();
  }

  private static Context context(Player player, Battle battle) {
    var challenge = (ChallengeInstance) player.getInstance();
    var excel = challenge.getExcel();
    var scene = player.getScene();
    int half = challenge.getCurrentStage();
    if (!StoryChallengeCatalog.supports(excel)
        || half < 1
        || half > 2
        || challenge.getStatus() != ChallengeStatus.CHALLENGE_DOING_VALUE
        || scene == null
        || scene.getPlaneType() != PlaneType.Challenge
        || player.getGameplay().getStoryLines().getCurrentId() != 0
        || battle.getStage() == null
        || battle.getNpcMonsters().isEmpty()
        || battle.getWaves().isEmpty()
        || battle.getLineup() != player.getCurrentLineup()
        || battle.getLineup().getExtraLineupType() != challenge.getCurrentExtraLineup()
        || battle.getLineup().getAvatars().isEmpty()
        || challenge.getBuffs() == null
        || challenge.getBuffs().size() != 2
        || !StoryChallengeCatalog.accepts(
            excel, challenge.getBuffs().getInt(0), challenge.getBuffs().getInt(1))) return null;
    var entry = GameData.getMapEntranceExcelMap().get(excel.getMapEntranceID());
    if (entry == null
        || entry.getId() != player.getEntryId()
        || entry.getPlaneID() != player.getPlaneId()
        || entry.getFloorID() != player.getFloorId()
        || scene.getPlaneId() != player.getPlaneId()
        || scene.getFloorId() != player.getFloorId()) return null;
    var expected = half == 1 ? excel.getChallengeMonsters1() : excel.getChallengeMonsters2();
    int group = half == 1 ? excel.getMazeGroupID1() : excel.getMazeGroupID2();
    if (expected == null || expected.isEmpty() || group <= 0) return null;
    var combatants = new ArrayList<Combatant>();
    var entities = new HashSet<Integer>();
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
          || !entities.add(monster.getEntityId())
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
              monster.getEntityId(),
              group,
              monster.getInstId(),
              row.getNpcMonsterId(),
              row.getEventId(),
              monster.getWorldLevel()));
    }
    if (!stages.contains(battle.getStage().getId())) return null;
    var waves = new ArrayList<Wave>();
    for (var wave : battle.getWaves()) {
      if (!stages.contains(wave.getStage().getId())
          || wave.getStage() != GameData.getStageExcelMap().get(wave.getStage().getId())
          || wave.getMonsters().isEmpty()) return null;
      waves.add(new Wave(wave, wave.getStage().getId(), wave.getCustomLevel(), wave.getMonsters()));
    }
    return new Context(
        challenge,
        excel.getId(),
        half,
        player.getEntryId(),
        player.getPlaneId(),
        player.getFloorId(),
        player.getWorldLevel(),
        challenge.getTotalScore(),
        scene,
        battle.getLineup(),
        battle.getLineup().getAvatars(),
        challenge.getBuffs(),
        combatants,
        waves,
        battle.getStage().getId(),
        challenge.getRoundsLeft());
  }

  private StoryChallengeScope() {}
}
