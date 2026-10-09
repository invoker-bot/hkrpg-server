package org.hkrpg.server.gameplay;

import emu.lunarcore.data.GameData;
import emu.lunarcore.data.excel.FinishWayExcel;
import emu.lunarcore.data.excel.StageExcel;
import emu.lunarcore.game.battle.Battle;
import emu.lunarcore.game.battle.skills.MazeSkill;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.game.quest.QuestState;
import emu.lunarcore.game.scene.entity.EntityMonster;
import emu.lunarcore.game.scene.entity.GameEntity;
import emu.lunarcore.proto.BattleEndStatusOuterClass.BattleEndStatus;
import java.util.*;

/** Original daily goals, with a server criterion for a validated elemental basic attack. */
public final class DailyWeaknessEntries {
  private static final Map<Integer, Integer> GOALS = Map.of(2100123, 3, 2100142, 1);

  public static boolean supported(FinishWayExcel rule) {
    return rule != null
        && GOALS.getOrDefault(rule.getId(), 0) == rule.getProgress()
        && GOALS.containsKey(rule.getId())
        && "ExcellentEnterBattleAndWin".equals(rule.getFinishType())
        && "NoPara".equals(rule.getParamType())
        && rule.getParamInt1() == 0
        && rule.getParamInt2() == 0
        && rule.getParamInt3() == 0
        && "".equals(rule.getParamStr1())
        && rule.getParamIntList() != null
        && rule.getParamIntList().isEmpty()
        && rule.getParamItemList() != null
        && rule.getParamItemList().isEmpty()
        && !rule.isIsBackTrack();
  }

  public static Context capture(
      Player player, Battle battle, int casterId, MazeSkill skill, List<GameEntity> primaryHits) {
    var scene = player.getScene();
    var caster = scene == null ? null : scene.getAvatarByEntityId(casterId);
    var excel = caster == null ? null : caster.getExcel();
    if (battle.getPlayer() != player
        || excel == null
        || excel.getDamageType() == null
        || skill == null
        || skill != excel.getMazeAttack()
        // Resource parser uses 1 for basic attacks; the client request selector is 0.
        || skill.getIndex() != 1
        || !skill.isTriggerBattle(caster)
        || skill.hasAdventureModifier(caster, "ADV_StageAbility_Maze_IgnoreWeakness_MazeSkillMark")
        || primaryHits == null
        || primaryHits.isEmpty()) return null;
    int slot = battle.getLineup().getAvatars().indexOf(caster.getAvatarId());
    int buffId = excel.getDamageType().getEnterBattleBuff();
    var source = GameData.getMazeBuffExcelMap().get(buffId, 1);
    var buff = battle.getBuffs().get(buffId);
    String suffix = "StageAbility_MazeCommon_EnterBattle_" + excel.getDamageType().name();
    if (slot < 0
        || buff == null
        || buff.getOwnerIndex() != slot
        || source == null
        || source.getID() != buffId
        || source.getLv() != 1
        || source.getLvMax() != 1
        || !"CharacterAbility".equals(source.getInBattleBindingType())
        || !suffix.equals(source.getInBattleBindingKey())
        || source.getParamList() == null
        || !source.getParamList().isEmpty()) return null;
    boolean weakness = false;
    for (var hit : primaryHits) {
      if (!(hit instanceof EntityMonster monster) || !monster.isAlive()) continue;
      if (monster.getScene() != scene
          || scene.getEntities().get(monster.getEntityId()) != monster
          || !battle.getNpcMonsters().contains(monster)) return null;
      var stage = monster.getCustomStage();
      if (stage == null)
        stage = SceneEventBattles.resolve(monster.getEventId(), monster.getWorldLevel());
      if (!(stage instanceof StageExcel)
          || GameData.getStageExcelMap().get(stage.getId()) != stage
          || stage.getMonsterWaves() == null
          || stage.getMonsterWaves().isEmpty()
          || stage.getMonsterWaves().getFirst() == null
          || stage.getMonsterWaves().getFirst().isEmpty()) return null;
      for (int id : stage.getMonsterWaves().getFirst()) {
        var row = GameData.getMonsterExcelMap().get(id);
        if (row == null
            || row.getId() != id
            || row.getStanceWeakList() == null
            || row.getStanceWeakList().stream().anyMatch(Objects::isNull)
            || new HashSet<>(row.getStanceWeakList()).size() != row.getStanceWeakList().size())
          return null;
        weakness |= row.getStanceWeakList().contains(excel.getDamageType());
      }
    }
    if (!weakness) return null;
    long day = player.getDailyManager().ensureDay();
    var goals = new HashMap<Integer, QuestState>();
    for (int id : GOALS.keySet()) {
      var quest = GameData.getQuestExcelMap().get(id);
      var state = player.getGameplay().getDailyQuests().get(id);
      if (quest != null
          && quest.getId() == id
          && quest.getQuestType() == 5
          && supported(GameData.getFinishWayExcelMap().get(quest.getFinishWayID()))
          && quest.getFinishWayID() == id
          && MissionStateRecords.doing(state, id, GOALS.get(id))) goals.put(id, state);
    }
    return goals.isEmpty() ? null : new Context(battle, day, goals);
  }

  /** Transient proof belongs to this exact live battle and its entry-day objective instances. */
  public static final class Context {
    private final Battle battle;
    private final long day;
    private final Map<Integer, QuestState> goals;
    private boolean consumed;

    private Context(Battle battle, long day, Map<Integer, QuestState> goals) {
      this.battle = battle;
      this.day = day;
      this.goals = Map.copyOf(goals);
    }

    boolean consume(Player player, Battle current) {
      if (consumed
          || current != battle
          || battle.getPlayer() != player
          || player.getBattle() != battle
          || !battle.isSettling()) return false;
      consumed = true;
      return battle.getResult() == BattleEndStatus.BATTLE_END_WIN
          && day == DailyManager.day(player.getDailyManager().currentInstant())
          && day == player.getGameplay().getDailyDay();
    }

    boolean allows(Player player, FinishWayExcel rule) {
      return supported(rule)
          && goals.get(rule.getId()) != null
          && goals.get(rule.getId()) == player.getGameplay().getDailyQuests().get(rule.getId());
    }
  }

  private DailyWeaknessEntries() {}
}
