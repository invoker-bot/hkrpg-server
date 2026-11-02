package emu.lunarcore.game.quest;

import emu.lunarcore.data.GameData;
import emu.lunarcore.data.excel.*;
import emu.lunarcore.game.inventory.GameItem;
import emu.lunarcore.game.player.BasePlayerManager;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.server.packet.Retcode;
import emu.lunarcore.server.packet.send.PacketGetQuestDataScRsp;
import java.util.*;
import org.hkrpg.server.gameplay.ParkourQuests;
import org.hkrpg.server.gameplay.MainMissionQuestCounts;
import org.hkrpg.server.gameplay.QuestCompletionRecords;

/** Configured quests only. Unsupported unlock/finish rules remain pending. */
public final class QuestManager extends BasePlayerManager {
  public record Claim(Retcode retcode, List<GameItem> items) {}
  private int claimBatchDepth;

  public QuestManager(Player player) {
    super(player);
  }

  /** The native handler emits one final snapshot after all independently saved claims. */
  public <T,E extends Exception>T withClaimBatch(
      org.hkrpg.server.gameplay.ActivityReplayScope.Action<T,E> action)throws E {
    synchronized(getPlayer()){
      claimBatchDepth++;
      try{return action.run();}finally{claimBatchDepth--;}
    }
  }

  public void refreshUnlocks() {
    refreshUnlocksInternal();
  }

  private boolean refreshUnlocksInternal() {
    synchronized (getPlayer()) {
      boolean changed = false;
      boolean collectionChanged = false;
      boolean pass;
      do {
        pass = false;
        for (var definition : GameData.getQuestExcelMap().values()) {
          // Daily-active (5) and cycle/battle-pass (3) use their own reset domains.
          if (definition.getQuestType() != 1 && definition.getQuestType() != 4) continue;
          if (getPlayer().getQuestStates().containsKey(definition.getId()) || !unlocked(definition))
            continue;
          var rule = GameData.getFinishWayExcelMap().get(definition.getFinishWayID());
          if (rule == null || rule.getProgress() <= 0) continue;
          var state = new QuestState(definition.getId());
          getPlayer().getQuestStates().put(definition.getId(), state);
          if (rule.isType("AutoFinish", 1))
            state.advance(rule.getProgress(), rule.getProgress(), now());
          pass = true;
          changed = true;
        }
        boolean observed = org.hkrpg.server.gameplay.RogueBlessingQuests.refresh(getPlayer());
        observed |= org.hkrpg.server.gameplay.RogueTalentQuests.refresh(getPlayer());
        observed |= org.hkrpg.server.gameplay.RogueMiracleQuests.refresh(getPlayer());
        observed |= org.hkrpg.server.gameplay.RogueEnhancementQuests.refresh(getPlayer());
        observed |= org.hkrpg.server.gameplay.RogueDestructionQuests.refresh(getPlayer());
        observed |= org.hkrpg.server.gameplay.RogueBattleKillQuests.refresh(getPlayer());
        observed |= org.hkrpg.server.gameplay.RogueChestKillQuests.refresh(getPlayer());
        observed |= org.hkrpg.server.gameplay.LocalLegendQuests.refresh(getPlayer());
        observed |= org.hkrpg.server.gameplay.DiceRollQuests.refresh(getPlayer());
        observed |= org.hkrpg.server.gameplay.BoxingClubResidentQuests.refresh(getPlayer());
        observed |= org.hkrpg.server.gameplay.BoxingClubActivityQuests.refresh(getPlayer());
        observed |= org.hkrpg.server.gameplay.MusicActivityQuests.refresh(getPlayer());
        observed |= org.hkrpg.server.gameplay.TimelineActivityQuests.refresh(getPlayer());
        observed |= org.hkrpg.server.gameplay.ActivityKillQuests.refresh(getPlayer());
        observed |= org.hkrpg.server.gameplay.ActivityFirstTalkQuests.refresh(getPlayer());
        observed |= org.hkrpg.server.gameplay.CavalryBeastQuests.refresh(getPlayer());
        observed |= org.hkrpg.server.gameplay.ActivityChestQuests.refresh(getPlayer());
        observed |= org.hkrpg.server.gameplay.ActivityKraterQuests.refresh(getPlayer());
        collectionChanged |= observed;
        changed |= observed;
        pass |= observed;
      } while (pass);
      changed |= new ParkourQuests(getPlayer()).refresh();
      if (changed) getPlayer().save();
      return collectionChanged;
    }
  }

  public void onRogueHandbookProgress() {
    synchronized (getPlayer()) {
      if (org.hkrpg.server.gameplay.RogueBlessingQuests.hasCollection(getPlayer())
          && refreshUnlocksInternal()) sync();
    }
  }

  public void onRogueTalentProgress() {
    synchronized (getPlayer()) {
      if (refreshUnlocksInternal()) sync();
    }
  }

  public void onLocalLegendProgress() {
    synchronized (getPlayer()) {
      if (refreshUnlocksInternal()) sync();
    }
  }

  public void onDiceRollProgress() {
    synchronized (getPlayer()) {
      if (org.hkrpg.server.gameplay.DiceRollQuests.hasRolls(getPlayer())
          && refreshUnlocksInternal()) sync();
    }
  }

  public void onBoxingClubProgress() {
    synchronized (getPlayer()) {
      if (refreshUnlocksInternal()) sync();
    }
  }

  public void onRogueMiracleProgress() {
    synchronized (getPlayer()) {
      if (org.hkrpg.server.gameplay.RogueMiracleQuests.hasCollection(getPlayer())
          && refreshUnlocksInternal()) sync();
    }
  }

  public void onRogueEnhancementProgress() {
    synchronized(getPlayer()) {
      if(org.hkrpg.server.gameplay.RogueEnhancementQuests.hasEnhancements(getPlayer())
          && refreshUnlocksInternal())sync();
    }
  }

  public void onRogueDestructionProgress() {
    synchronized(getPlayer()) {
      if(org.hkrpg.server.gameplay.RogueDestructionQuests.hasDestructions(getPlayer())
          &&refreshUnlocksInternal())sync();
    }
  }

  public void onRogueBattleKillProgress() {
    synchronized (getPlayer()) {
      if ((org.hkrpg.server.gameplay.RogueBattleKillQuests.hasKills(getPlayer())
          || org.hkrpg.server.gameplay.RogueChestKillQuests.hasKills(getPlayer()))
          && refreshUnlocksInternal()) sync();
    }
  }

  private boolean unlocked(QuestExcel definition) {
    String type = definition.getUnlockType();
    if ("AutoUnlock".equals(type) || "1".equals(type)) return true;
    if (!("FinishQuest".equals(type) || "2".equals(type))
        || definition.getUnlockParamList() == null
        || definition.getUnlockParamList().isEmpty()) return false;
    return definition.getUnlockParamList().stream()
        .allMatch(id -> id != null && QuestCompletionRecords.completed(getPlayer(), id));
  }

  public boolean onMissionProgress() {
    synchronized (getPlayer()) {
      if (GameData.getQuestExcelMap().values().stream()
          .noneMatch(definition ->
              (getPlayer().getQuestStates().containsKey(definition.getId()) || unlocked(definition))
                  && MainMissionQuestCounts.needsProgress(getPlayer(), definition)))
        return false;
      refreshUnlocks();
      boolean changed = false;
      for (var definition : GameData.getQuestExcelMap().values()) {
        int count = MainMissionQuestCounts.count(getPlayer(), definition);
        if (count < 0) continue;
        var state = getPlayer().getQuestStates().get(definition.getId());
        var rule = GameData.getFinishWayExcelMap().get(definition.getFinishWayID());
        changed |= state.advance(count - state.getProgress(), rule.getProgress(), now());
      }
      if (changed) {
        getPlayer().save();
        refreshUnlocks();
        sync();
      }
      return changed;
    }
  }

  public Retcode finishByClient(int questId) {
    synchronized (getPlayer()) {
      getPlayer().getDailyManager().ensureDay();
      var definition = GameData.getQuestExcelMap().get(questId);
      if (definition == null) return Retcode.REQ_PARA_INVALID;
      if (definition.getQuestType() == 5 || definition.getQuestType() == 3)
        return Retcode.QUEST_STATUS_ERROR;
      var state = getPlayer().getQuestStates().get(questId);
      if (state == null) return Retcode.QUEST_NOT_ACCEPT;
      var rule = GameData.getFinishWayExcelMap().get(definition.getFinishWayID());
      if (rule == null || !rule.isType("FinishQuestByClient", 88) || rule.getProgress() <= 0)
        return Retcode.QUEST_STATUS_ERROR;
      if (!state.advance(rule.getProgress(), rule.getProgress(), now()))
        return Retcode.QUEST_STATUS_ERROR;
      getPlayer().save();
      refreshUnlocks();
      getPlayer().getMissionManager().refresh();
      sync();
      return Retcode.SUCC;
    }
  }

  public void onStaminaConsumed(int amount) {
    if (amount > 0) advanceRules(amount, "ConsumeStamina", 101, ignored -> true);
  }

  public void onStageWin(int stageId) {
    advanceRules(
        1,
        "StageWin",
        3,
        rule ->
            rule.getParamIntList().isEmpty()
                ? rule.getParamInt1() == stageId
                : rule.getParamIntList().contains(stageId));
  }

  public void onCocoonCategoryVictory(emu.lunarcore.game.battle.Battle battle) {
    var result = org.hkrpg.server.gameplay.CocoonCategories.victory(getPlayer(), battle);
    if (result.isPresent())
      advanceRules(
          result.get().count(),
          "AnyCocoonFinish",
          26,
          rule ->
              org.hkrpg.server.gameplay.CocoonCategories.matches(rule, result.get().category()));
  }

  public void onParkourResult() {
    synchronized (getPlayer()) {
      if (new ParkourQuests(getPlayer()).refresh()) {
        getPlayer().save();
        sync();
      }
    }
  }

  private void advanceRules(
      int amount, String name, int type, java.util.function.Predicate<FinishWayExcel> matches) {
    synchronized (getPlayer()) {
      boolean changed = false;
      for (var state : getPlayer().getQuestStates().values()) {
        var definition = GameData.getQuestExcelMap().get(state.getQuestId());
        if (definition == null
            || type == 26 && definition.getQuestType() != 1 && definition.getQuestType() != 4)
          continue;
        var rule = GameData.getFinishWayExcelMap().get(definition.getFinishWayID());
        if (rule != null && rule.isType(name, type) && matches.test(rule))
          changed |= state.advance(amount, rule.getProgress(), now());
      }
      if (changed) {
        getPlayer().save();
        refreshUnlocks();
        sync();
      }
    }
  }

  public Claim claim(int questId) {
    return org.hkrpg.server.gameplay.MusicActivityQuests.owns(questId)
        ?org.hkrpg.server.gameplay.ActivityReplayScope.in(getPlayer().getGameplay(),50022,()->claimCurrent(questId))
        :claimCurrent(questId);
  }
  private Claim claimCurrent(int questId) {
    synchronized (getPlayer()) {
      var definition = GameData.getQuestExcelMap().get(questId);
      if (definition == null) return new Claim(Retcode.REQ_PARA_INVALID, List.of());
      if (definition.getQuestType() == 5) return getPlayer().getDailyManager().claimQuest(questId);
      if (definition.getQuestType() == 3) {
        getPlayer().getDailyManager().ensureDay();
        if(org.hkrpg.server.gameplay.MusicActivityQuests.belongs(definition))
          org.hkrpg.server.gameplay.MusicActivityQuests.refresh(getPlayer());
        else refreshUnlocks();
      }
      var cycle = new org.hkrpg.server.gameplay.DailyCycleQuests(getPlayer())
          .claimState(questId, getPlayer().getDailyManager().currentInstant());
      var state =
          definition.getQuestType() == 3
              ? cycle != null ? cycle : new ParkourQuests(getPlayer()).claimState(questId)
              : getPlayer().getQuestStates().get(questId);
      if (org.hkrpg.server.gameplay.RogueBlessingQuests.belongs(definition))
        state = org.hkrpg.server.gameplay.RogueBlessingQuests.state(getPlayer(), questId);
      if (org.hkrpg.server.gameplay.RogueTalentQuests.belongs(definition))
        state = org.hkrpg.server.gameplay.RogueTalentQuests.state(getPlayer(), questId);
      if (org.hkrpg.server.gameplay.RogueMiracleQuests.belongs(definition))
        state = org.hkrpg.server.gameplay.RogueMiracleQuests.state(getPlayer(), questId);
      if (org.hkrpg.server.gameplay.RogueEnhancementQuests.belongs(definition))
        state = org.hkrpg.server.gameplay.RogueEnhancementQuests.state(getPlayer(), questId);
      if (org.hkrpg.server.gameplay.RogueDestructionQuests.belongs(definition))
        state = org.hkrpg.server.gameplay.RogueDestructionQuests.state(getPlayer(), questId);
      if (org.hkrpg.server.gameplay.RogueBattleKillQuests.belongs(definition))
        state = org.hkrpg.server.gameplay.RogueBattleKillQuests.state(getPlayer(), questId);
      if (org.hkrpg.server.gameplay.RogueChestKillQuests.belongs(definition))
        state = org.hkrpg.server.gameplay.RogueChestKillQuests.state(getPlayer(), questId);
      if (org.hkrpg.server.gameplay.LocalLegendQuests.belongs(definition))
        state = org.hkrpg.server.gameplay.LocalLegendQuests.state(getPlayer(), questId);
      if (org.hkrpg.server.gameplay.DiceRollQuests.belongs(definition))
        state = org.hkrpg.server.gameplay.DiceRollQuests.state(getPlayer(), questId);
      if (org.hkrpg.server.gameplay.BoxingClubResidentQuests.belongs(definition))
        state = org.hkrpg.server.gameplay.BoxingClubResidentQuests.state(getPlayer(), questId);
      if (org.hkrpg.server.gameplay.BoxingClubActivityQuests.belongs(definition))
        state = org.hkrpg.server.gameplay.BoxingClubActivityQuests.state(getPlayer(), questId);
      if (org.hkrpg.server.gameplay.TimelineActivityQuests.belongs(definition))
        state = org.hkrpg.server.gameplay.TimelineActivityQuests.state(getPlayer(), questId);
      if (org.hkrpg.server.gameplay.ActivityKillQuests.belongs(definition))
        state = org.hkrpg.server.gameplay.ActivityKillQuests.state(getPlayer(), questId);
      if (org.hkrpg.server.gameplay.ActivityFirstTalkQuests.belongs(definition))
        state = org.hkrpg.server.gameplay.ActivityFirstTalkQuests.state(getPlayer(), questId);
      if (org.hkrpg.server.gameplay.CavalryBeastQuests.belongs(definition))
        state = org.hkrpg.server.gameplay.CavalryBeastQuests.state(getPlayer(), questId);
      if (org.hkrpg.server.gameplay.ActivityChestQuests.belongs(definition))
        state = org.hkrpg.server.gameplay.ActivityChestQuests.state(getPlayer(), questId);
      if (org.hkrpg.server.gameplay.ActivityKraterQuests.belongs(definition))
        state = org.hkrpg.server.gameplay.ActivityKraterQuests.state(getPlayer(), questId);
      // Exact ownership takes precedence over the generic OfferingLevel rejection guard.
      if (org.hkrpg.server.gameplay.MusicActivityQuests.belongs(definition))
        state = org.hkrpg.server.gameplay.MusicActivityQuests.state(getPlayer(), questId);
      if (state == null) return new Claim(Retcode.QUEST_NOT_ACCEPT, List.of());
      if (state.getPhase() == QuestState.Phase.CLOSED)
        return new Claim(Retcode.QUEST_REWARD_ALREADY_TAKEN, List.of());
      if (state.getPhase() == QuestState.Phase.CLAIMING)
        return new Claim(Retcode.SYSTEM_BUSY, List.of());
      if (state.getPhase() != QuestState.Phase.FINISHED)
        return new Claim(Retcode.QUEST_NOT_FINISH, List.of());
      var reward = GameData.getRewardExcelMap().get(definition.getRewardID());
      if (definition.getRewardID() != 0 && (reward == null || reward.getRewards() == null))
        return new Claim(Retcode.PLAYER_DATA_ERROR, List.of());
      state.reserveReward();
      getPlayer().save();
      // Persist reservation before touching inventory. Interrupted claims require reconciliation;
      // the inherited inventory stores separate documents without a cross-document transaction.
      var granted =
          reward == null
              ? new emu.lunarcore.game.inventory.Inventory.GrantResult(List.of(), true)
              : getPlayer().getInventory().grantItems(reward.getRewards(), true);
      if (!granted.complete()) {
        // Currency balances belong to this player document as well as the reservation.
        getPlayer().save();
        return new Claim(Retcode.PLAYER_DATA_ERROR, granted.items());
      }
      state.closeReward();
      getPlayer().save();
      sync();
      return new Claim(Retcode.SUCC, List.copyOf(granted.items()));
    }
  }

  public List<QuestState> states() {
    synchronized (getPlayer()) {
      getPlayer().getDailyManager().ensureDay();
      refreshUnlocks();
      var boxing = org.hkrpg.server.gameplay.BoxingClubResidentQuests.states(getPlayer());
      var states =
          new ArrayList<>(
              getPlayer().getQuestStates().values().stream()
                  .filter(
                      q -> {
                        var definition = GameData.getQuestExcelMap().get(q.getQuestId());
                        return definition == null || definition.getQuestType() != 3 && definition.getQuestType() != 5
                            && (!org.hkrpg.server.gameplay.RogueBlessingQuests.belongs(definition)
                                || org.hkrpg.server.gameplay.RogueBlessingQuests.state(getPlayer(), q.getQuestId()) == q)
                            && (!org.hkrpg.server.gameplay.RogueTalentQuests.belongs(definition)
                                || org.hkrpg.server.gameplay.RogueTalentQuests.state(getPlayer(), q.getQuestId()) == q)
                            && (!org.hkrpg.server.gameplay.RogueMiracleQuests.belongs(definition)
                                || org.hkrpg.server.gameplay.RogueMiracleQuests.state(getPlayer(), q.getQuestId()) == q)
                            && (!org.hkrpg.server.gameplay.RogueEnhancementQuests.belongs(definition)
                                || org.hkrpg.server.gameplay.RogueEnhancementQuests.state(getPlayer(), q.getQuestId()) == q)
                            && (!org.hkrpg.server.gameplay.RogueDestructionQuests.belongs(definition)
                                || org.hkrpg.server.gameplay.RogueDestructionQuests.state(getPlayer(), q.getQuestId()) == q)
                            && (!org.hkrpg.server.gameplay.RogueBattleKillQuests.belongs(definition)
                                || org.hkrpg.server.gameplay.RogueBattleKillQuests.state(getPlayer(), q.getQuestId()) == q)
                            && (!org.hkrpg.server.gameplay.RogueChestKillQuests.belongs(definition)
                                || org.hkrpg.server.gameplay.RogueChestKillQuests.state(getPlayer(), q.getQuestId()) == q)
                            && (!org.hkrpg.server.gameplay.LocalLegendQuests.belongs(definition)
                                || org.hkrpg.server.gameplay.LocalLegendQuests.state(getPlayer(), q.getQuestId()) == q)
                            && (!org.hkrpg.server.gameplay.DiceRollQuests.belongs(definition)
                                || org.hkrpg.server.gameplay.DiceRollQuests.state(getPlayer(), q.getQuestId()) == q)
                            && (!org.hkrpg.server.gameplay.BoxingClubResidentQuests.belongs(definition)
                                || boxing.get(q.getQuestId()) == q)
                            && (!org.hkrpg.server.gameplay.TimelineActivityQuests.belongs(definition)
                                || org.hkrpg.server.gameplay.TimelineActivityQuests.state(getPlayer(), q.getQuestId()) == q)
                            && (!org.hkrpg.server.gameplay.ActivityKillQuests.belongs(definition)
                                || org.hkrpg.server.gameplay.ActivityKillQuests.state(getPlayer(), q.getQuestId()) == q)
                            && (!org.hkrpg.server.gameplay.ActivityFirstTalkQuests.belongs(definition)
                                || org.hkrpg.server.gameplay.ActivityFirstTalkQuests.state(getPlayer(), q.getQuestId()) == q)
                            && (!org.hkrpg.server.gameplay.CavalryBeastQuests.belongs(definition)
                                || org.hkrpg.server.gameplay.CavalryBeastQuests.state(getPlayer(), q.getQuestId()) == q)
                            && (!org.hkrpg.server.gameplay.ActivityChestQuests.belongs(definition)
                                || org.hkrpg.server.gameplay.ActivityChestQuests.state(getPlayer(), q.getQuestId()) == q)
                            && (!org.hkrpg.server.gameplay.ActivityKraterQuests.belongs(definition)
                                || org.hkrpg.server.gameplay.ActivityKraterQuests.state(getPlayer(), q.getQuestId()) == q);
                      })
                  .toList());
      states.addAll(new ParkourQuests(getPlayer()).states());
      states.addAll(org.hkrpg.server.gameplay.BoxingClubActivityQuests.states(getPlayer()).values());
      states.addAll(org.hkrpg.server.gameplay.MusicActivityQuests.states(getPlayer()).values());
      states.addAll(org.hkrpg.server.gameplay.TimelineActivityQuests.states(getPlayer()).values());
      states.addAll(org.hkrpg.server.gameplay.ActivityKillQuests.states(getPlayer()).values());
      states.addAll(org.hkrpg.server.gameplay.ActivityFirstTalkQuests.states(getPlayer()));
      states.addAll(org.hkrpg.server.gameplay.CavalryBeastQuests.states(getPlayer()));
      states.addAll(org.hkrpg.server.gameplay.ActivityChestQuests.states(getPlayer()));
      states.addAll(org.hkrpg.server.gameplay.ActivityKraterQuests.states(getPlayer()));
      states.addAll(org.hkrpg.server.gameplay.RogueBlessingQuests.permanentStates(getPlayer()));
      states.addAll(org.hkrpg.server.gameplay.LocalLegendQuests.activityStates(getPlayer()));
      states.addAll(new org.hkrpg.server.gameplay.DailyCycleQuests(getPlayer())
          .states(getPlayer().getDailyManager().currentInstant()));
      states.addAll(getPlayer().getGameplay().getDailyQuests().values());
      return states.stream().sorted(Comparator.comparingInt(QuestState::getQuestId)).toList();
    }
  }

  public int achievementCount() {
    synchronized (getPlayer()) {
      return (int)
          GameData.getAchievementExcelMap().values().stream()
              .filter(
                  definition -> {
                    var state = getPlayer().getQuestStates().get(definition.getQuestID());
                    return state != null && state.getPhase() == QuestState.Phase.CLOSED;
                  })
              .count();
    }
  }

  public void onActivityKillProgress() {
    synchronized (getPlayer()) { sync(); }
  }

  public void onWorldFirstTalkProgress() {
    synchronized (getPlayer()) { sync(); }
  }
  public void onWorldChestProgress() {
    synchronized (getPlayer()) { sync(); }
  }
  public void onKraterOfferingProgress() {
    synchronized (getPlayer()) { sync(); }
  }

  public void onMusicProgress(){
    synchronized(getPlayer()){
      if(org.hkrpg.server.gameplay.MusicActivityQuests.refresh(getPlayer())){
        getPlayer().save();sync();
      }
    }
  }

  private void sync() {
    if(claimBatchDepth>0)return;
    if (getPlayer().getSession() != null)
      getPlayer().sendPacket(new PacketGetQuestDataScRsp(getPlayer()));
  }

  private static long now() {
    return System.currentTimeMillis() / 1000;
  }
}
