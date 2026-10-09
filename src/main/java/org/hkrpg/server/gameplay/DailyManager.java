package org.hkrpg.server.gameplay;

import emu.lunarcore.data.GameData;
import emu.lunarcore.data.excel.*;
import emu.lunarcore.game.battle.Battle;
import emu.lunarcore.game.enums.StageType;
import emu.lunarcore.game.inventory.ItemParamMap;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.game.quest.QuestManager;
import emu.lunarcore.game.quest.QuestState;
import emu.lunarcore.proto.DailyActivityInfoOuterClass.DailyActivityInfo;
import emu.lunarcore.server.packet.Retcode;
import emu.lunarcore.server.packet.send.PacketDailyActiveInfoNotify;
import java.time.*;
import java.util.*;
import java.util.function.Predicate;

public final class DailyManager {
  // Pinned 4.6 ItemConfig/RewardData: virtual activity points, not an inventory grant.
  private static final int ACTIVE_POINT_ITEM_ID = 23;
  private final Player player;
  private Clock clock = Clock.systemUTC();

  public DailyManager(Player player) {
    this.player = player;
  }

  public void setClock(Clock value) {
    clock = Objects.requireNonNull(value);
  }

  public Instant currentInstant() {
    return clock.instant();
  }

  public static long day(Instant now) {
    return now.atOffset(ZoneOffset.ofHours(8)).minusHours(4).toLocalDate().toEpochDay();
  }

  public long ensureDay() {
    synchronized (player) {
      var state = player.getGameplay();
      long today = day(clock.instant());
      if (today <= state.getDailyDay()) {
        if (today == state.getDailyDay()) {
          refreshTodayMissions();
          refreshCycleQuests(false);
        }
        return state.getDailyDay();
      }
      state.resetDaily(today);
      var choices = new TreeMap<Integer, List<Integer>>();
      for (var pool : GameplayDefinitions.dailyPool) {
        var config = GameData.getQuestExcelMap().get(pool.QuestID());
        var rule =
            config == null ? null : GameData.getFinishWayExcelMap().get(config.getFinishWayID());
        if (config == null
            || config.getQuestType() != 5
            || rule == null
            || rule.getProgress() <= 0
            || !supported(rule)
                && !new DailyCycleQuests(player).supportedDaily(config.getId(), clock.instant())
            || !unlocked(config)) continue;
        choices.computeIfAbsent(pool.Type(), k -> new ArrayList<>()).add(config.getId());
      }
      // Explicit server policy: one supported, unlocked objective per pool type; deterministic
      // daily rotation.
      for (var ids : choices.values()) {
        ids.sort(Integer::compareTo);
        int id = ids.get(Math.floorMod(today, ids.size()));
        var progress = new QuestState(id);
        state.getDailyQuests().put(id, progress);
        var config = GameData.getQuestExcelMap().get(id);
        var rule = GameData.getFinishWayExcelMap().get(config.getFinishWayID());
        if ("AutoFinish".equals(rule.getFinishType()))
          progress.advance(
              rule.getProgress(), rule.getProgress(), clock.instant().getEpochSecond());
      }
      player.save();
      event("Login", r -> true, 1);
      sync();
      return today;
    }
  }

  boolean unlocked(QuestExcel q) {
    return "AutoUnlock".equals(q.getUnlockType())
        || "1".equals(q.getUnlockType())
        || Set.of("FinishMission", "FinishMainMission").contains(q.getUnlockType())
            && !q.getUnlockParamList().isEmpty()
            && q.getUnlockParamList().stream().allMatch(player.getMissionManager()::finished);
  }

  private static boolean supported(FinishWayExcel r) {
    if ("FinishTodayDailyMissionCnt".equals(r.getFinishType()))
      return TodayDailyMissionCounts.supported(r);
    if (Set.of("ItemCompose", "AnyItemComposeCount").contains(r.getFinishType()))
      return composeRule(r);
    if ("UseItemWithType".equals(r.getFinishType())) return foodUseRule(r);
    if (Set.of("AnyAvatarLevelUp", "AnyEquipmentLevelUp", "AnyRelicLevelUp")
        .contains(r.getFinishType())) return upgradeRule(r);
    if ("RogueFinishCnt".equals(r.getFinishType())) return rogueFinishRule(r);
    if ("ChallengeFinishCnt".equals(r.getFinishType())) return memoryChallengeRule(r);
    if ("PropDestruct".equals(r.getFinishType())) return propDestructionRule(r);
    if ("AnyCocoonFinish".equals(r.getFinishType())) return CocoonCategories.supported(r);
    if ("FarmTypeCocoonFinish".equals(r.getFinishType())) return CocoonFarmTypes.supported(r);
    if ("ResolveRelic".equals(r.getFinishType())) return resolveRelicRule(r);
    if ("TakePictureCnt".equals(r.getFinishType())) return pictureRule(r);
    if ("ExpeditionSetOrTakeReward".equals(r.getFinishType())) return expeditionRule(r);
    if ("ExcellentEnterBattleAndWin".equals(r.getFinishType())) return DailyWeaknessEntries.supported(r);
    return Set.of(
                "AutoFinish",
                "Login",
                "ConsumeStamina",
                "UseMazeSkill",
                "StageWin",
                "CocoonFinish",
                "AnyFarmElementFinish",
                "RogueExploreRoomFinishCnt")
            .contains(r.getFinishType())
        || "UseItem".equals(r.getFinishType())
            && r.getParamInt1() == 11
            && r.getParamIntList().isEmpty();
  }

  public void onStaminaConsumed(int amount) {
    if (amount <= 0) return;
    event("ConsumeStamina", r -> true, amount);
    // Resource item 11 is Trailblaze Power: credit only actual server debits.
    event("UseItem", r -> r.getParamInt1() == 11 && r.getParamIntList().isEmpty(), amount);
  }

  public void onMazeSkill() {
    event("UseMazeSkill", r -> true, 1);
  }

  public void onMainMissionCompleted(int id) {
    synchronized (player) {
      var now = clock.instant();
      if (!TodayDailyMissionCounts.completed(player, id, day(now), now)) return;
      ensureDay();
      refreshTodayMissions();
    }
  }

  private void refreshTodayMissions() {
    var now = clock.instant();
    if (day(now) != player.getGameplay().getDailyDay()) return;
    int count = TodayDailyMissionCounts.count(player, player.getGameplay().getDailyDay(), now);
    if (count <= 0) return;
    boolean changed = false;
    for (var entry : player.getGameplay().getDailyQuests().entrySet()) {
      if (entry.getKey() == null) continue;
      int id = entry.getKey();
      var definition = GameData.getQuestExcelMap().get(id);
      if (definition == null || definition.getId() != id || definition.getQuestType() != 5) continue;
      var rule = GameData.getFinishWayExcelMap().get(definition.getFinishWayID());
      if (rule == null || rule.getId() != definition.getFinishWayID()
          || !TodayDailyMissionCounts.supported(rule)
          || !MissionStateRecords.doing(entry.getValue(), id, rule.getProgress())) continue;
      var state = entry.getValue();
      changed |= state.advance(count - state.getProgress(), rule.getProgress(), now.getEpochSecond());
    }
    if (changed) {
      player.save();
      sync();
      if (player.isOnline())
        player.sendPacket(new emu.lunarcore.server.packet.send.PacketGetQuestDataScRsp(player));
    }
  }

  private static boolean propDestructionRule(FinishWayExcel rule) {
    return "PropDestruct".equals(rule.getFinishType())
        && rule.getProgress() > 0
        && "Equal".equals(rule.getParamType())
        && rule.getParamInt1() == 0
        && rule.getParamInt2() == 0
        && rule.getParamInt3() == 0
        && "".equals(rule.getParamStr1())
        && rule.getParamIntList() != null
        && rule.getParamIntList().isEmpty()
        && rule.getParamItemList() != null
        && rule.getParamItemList().isEmpty();
  }

  /** Invoked only after the current scene accepted and removed an actual destructible prop. */
  public void onPropDestruct() {
    event("PropDestruct", DailyManager::propDestructionRule, 1);
  }

  private static boolean pictureRule(FinishWayExcel rule) {
    return "TakePictureCnt".equals(rule.getFinishType())
        && rule.getProgress() == 1
        && "NoPara".equals(rule.getParamType())
        && rule.getParamInt1() == 0
        && rule.getParamInt2() == 0
        && rule.getParamInt3() == 0
        && "".equals(rule.getParamStr1())
        && rule.getParamIntList() != null && rule.getParamIntList().isEmpty()
        && rule.getParamItemList() != null && rule.getParamItemList().isEmpty()
        && !rule.isIsBackTrack();
  }

  /** The authenticated client photo report has been accepted for this player's current scene. */
  public void onPictureTaken() {
    event("TakePictureCnt", DailyManager::pictureRule, 1);
  }

  private static boolean expeditionRule(FinishWayExcel rule) {
    return "ExpeditionSetOrTakeReward".equals(rule.getFinishType())
        && rule.getProgress() == 1 && "NoPara".equals(rule.getParamType())
        && rule.getParamInt1() == 0 && rule.getParamInt2() == 0 && rule.getParamInt3() == 0
        && "".equals(rule.getParamStr1())
        && rule.getParamIntList() != null && rule.getParamIntList().isEmpty()
        && rule.getParamItemList() != null && rule.getParamItemList().isEmpty()
        && !rule.isIsBackTrack();
  }

  /** An actual changed nonempty assignment set or successfully delivered hourly harvest. */
  public void onExpeditionSetOrTakeReward() {
    event("ExpeditionSetOrTakeReward", DailyManager::expeditionRule, 1);
  }

  public void onRogueRoom() {
    event("RogueExploreRoomFinishCnt", r -> true, 1);
  }

  private static boolean rogueFinishRule(FinishWayExcel rule) {
    return "RogueFinishCnt".equals(rule.getFinishType())
        && rule.getProgress() > 0
        && "NoPara".equals(rule.getParamType())
        && rule.getParamInt1() == 0
        && rule.getParamInt2() == 0
        && rule.getParamInt3() == 0
        && "".equals(rule.getParamStr1())
        && rule.getParamIntList() != null
        && rule.getParamIntList().isEmpty()
        && rule.getParamItemList() != null
        && rule.getParamItemList().isEmpty();
  }

  /** Called only for a fresh successful terminal run recorded by UniverseManager. */
  public void onRogueFinish() {
    event("RogueFinishCnt", DailyManager::rogueFinishRule, 1);
    synchronized (player) {
      refreshCycleQuests(true);
    }
  }

  private void refreshCycleQuests(boolean rogueFinished) {
    var cycles = new DailyCycleQuests(player);
    boolean changed = rogueFinished ? cycles.onRogueFinish(clock.instant()) : cycles.refresh(clock.instant());
    if (changed) {
      player.save();
      sync();
      if (player.isOnline()) player.sendPacket(new emu.lunarcore.server.packet.send.PacketGetQuestDataScRsp(player));
    }
  }

  private static boolean memoryChallengeRule(FinishWayExcel rule) {
    // Pinned quest 2100119 and its CHS title identify [1] as Memory. Other categories
    // are unverified; Java ChallengeType ordinals are not a resource category mapping.
    return "ChallengeFinishCnt".equals(rule.getFinishType())
        && rule.getProgress() == 1
        && "ListContain".equals(rule.getParamType())
        && rule.getParamInt1() == 0
        && rule.getParamInt2() == 0
        && rule.getParamInt3() == 0
        && "".equals(rule.getParamStr1())
        && List.of(1).equals(rule.getParamIntList())
        && rule.getParamItemList() != null
        && rule.getParamItemList().isEmpty()
        && !rule.isIsBackTrack();
  }

  /** Fresh whole-Memory victory after the actual instance has consumed the last encounter. */
  public void onChallengeVictory(Battle battle) {
    synchronized (player) {
      if (battle == null
          || battle.getMissionChallengeWin() == null
          || !battle.getMissionChallengeWin().completed(player, battle)) return;
      event("ChallengeFinishCnt", DailyManager::memoryChallengeRule, 1);
    }
  }

  private static boolean upgradeRule(FinishWayExcel rule) {
    return Set.of("AnyAvatarLevelUp", "AnyEquipmentLevelUp", "AnyRelicLevelUp")
            .contains(rule.getFinishType())
        && rule.getProgress() == 1
        && "NoPara".equals(rule.getParamType())
        && rule.getParamInt1() == 0
        && rule.getParamInt2() == 0
        && rule.getParamInt3() == 0
        && "".equals(rule.getParamStr1())
        && rule.getParamIntList() != null
        && rule.getParamIntList().isEmpty()
        && rule.getParamItemList() != null
        && rule.getParamItemList().isEmpty();
  }

  public void onAvatarLevelUp() {
    event("AnyAvatarLevelUp", DailyManager::upgradeRule, 1);
  }

  public void onEquipmentLevelUp() {
    event("AnyEquipmentLevelUp", DailyManager::upgradeRule, 1);
  }

  public void onRelicLevelUp() {
    event("AnyRelicLevelUp", DailyManager::upgradeRule, 1);
  }

  private static boolean resolveRelicRule(FinishWayExcel rule) {
    return "ResolveRelic".equals(rule.getFinishType())
        && rule.getProgress() == 1
        && "NoPara".equals(rule.getParamType())
        && rule.getParamInt1() == 0
        && rule.getParamInt2() == 0
        && rule.getParamInt3() == 0
        && "".equals(rule.getParamStr1())
        && rule.getParamIntList() != null && rule.getParamIntList().isEmpty()
        && rule.getParamItemList() != null && rule.getParamItemList().isEmpty()
        && !rule.isIsBackTrack();
  }

  /** Only after a real relic debit and complete actual decomposition return delivery. */
  public void onRelicResolved() {
    event("ResolveRelic", DailyManager::resolveRelicRule, 1);
  }

  private static boolean singleItemEvent(FinishWayExcel rule) {
    return rule.getProgress() == 1
        && rule.getParamInt2() == 0
        && rule.getParamInt3() == 0
        && "".equals(rule.getParamStr1())
        && rule.getParamIntList() != null
        && rule.getParamItemList() != null
        && rule.getParamItemList().isEmpty();
  }

  private static boolean composeRule(FinishWayExcel rule) {
    if (!singleItemEvent(rule) || rule.getParamInt1() != 0) return false;
    return "AnyItemComposeCount".equals(rule.getFinishType())
            && "NoPara".equals(rule.getParamType())
            && rule.getParamIntList().isEmpty()
        || "ItemCompose".equals(rule.getFinishType())
            && "ListContain".equals(rule.getParamType())
            && (rule.getParamIntList().equals(List.of(502))
                || rule.getParamIntList().equals(List.of(601)));
  }

  private static boolean foodUseRule(FinishWayExcel rule) {
    return "UseItemWithType".equals(rule.getFinishType())
        && singleItemEvent(rule)
        && "Equal".equals(rule.getParamType())
        && rule.getParamInt1() == 502
        && rule.getParamIntList().isEmpty();
  }

  /** Accepted output from an actual completed composition, after material debit and delivery. */
  public void onItemComposed(emu.lunarcore.game.enums.ItemSubType subtype, int count) {
    if (subtype == null || count <= 0) return;
    event("ItemCompose", r -> composeRule(r) && r.getParamIntList().contains(subtype.getVal()), 1);
    event("AnyItemComposeCount", DailyManager::composeRule, 1);
  }

  /** Accepted item-use receipt, after actual consumption and applying the supported effect. */
  public void onItemUsed(emu.lunarcore.game.enums.ItemSubType subtype, int count) {
    if (subtype != emu.lunarcore.game.enums.ItemSubType.Food || count <= 0) return;
    event("UseItemWithType", DailyManager::foodUseRule, 1);
  }

  public void onWeaknessEntryOutcome(Battle battle) {
    synchronized (player) {
      var entry = battle.getDailyWeaknessEntry();
      if (entry != null && entry.consume(player, battle))
        event("ExcellentEnterBattleAndWin", rule -> entry.allows(player, rule), 1);
    }
  }

  public void onStageWin(Battle battle) {
    event(
        "StageWin",
        r ->
            r.getParamInt1() == battle.getStage().getId()
                || r.getParamIntList().contains(battle.getStage().getId()),
        1);
    if (battle.getCocoonWave() > 0)
      event(
          "CocoonFinish",
          r -> {
            var config =
                GameData.getCocoonExcelMap().get(battle.getCocoonId(), battle.getWorldLevel());
            return config != null
                && (r.getParamIntList().isEmpty()
                    || r.getParamIntList().contains(battle.getCocoonId()));
          },
          battle.getCocoonWave());
    if (battle.getStage().getStageType() == StageType.FarmElement)
      event("AnyFarmElementFinish", r -> true, 1);
  }

  public void onCocoonCategoryVictory(Battle battle) {
    var result = CocoonCategories.victory(player, battle);
    if (result.isPresent())
      event(
          "AnyCocoonFinish",
          rule -> CocoonCategories.matches(rule, result.get().category()),
          result.get().count());
  }

  public void onFarmTypeCocoonVictory(Battle battle) {
    int category = CocoonFarmTypes.victory(player, battle);
    if (category > 0)
      event("FarmTypeCocoonFinish", rule -> CocoonFarmTypes.matches(rule, category), 1);
  }

  private void event(String type, Predicate<FinishWayExcel> test, int amount) {
    synchronized (player) {
      ensureDay();
      boolean changed = false;
      for (var state : player.getGameplay().getDailyQuests().values()) {
        var q = GameData.getQuestExcelMap().get(state.getQuestId());
        var r = q == null ? null : GameData.getFinishWayExcelMap().get(q.getFinishWayID());
        if (r != null && type.equals(r.getFinishType()) && test.test(r))
          changed |= state.advance(amount, r.getProgress(), clock.instant().getEpochSecond());
      }
      if (changed) {
        player.save();
        sync();
      }
    }
  }

  public QuestManager.Claim claimQuest(int id) {
    synchronized (player) {
      ensureDay();
      var state = player.getGameplay().getDailyQuests().get(id);
      if (state == null) return new QuestManager.Claim(Retcode.QUEST_NOT_ACCEPT, List.of());
      if (!new DailyCycleQuests(player).summaryClaimValid(id, state, clock.instant()))
        return new QuestManager.Claim(Retcode.QUEST_STATUS_ERROR, List.of());
      if (state.getPhase() == QuestState.Phase.CLOSED)
        return new QuestManager.Claim(Retcode.QUEST_REWARD_ALREADY_TAKEN, List.of());
      if (state.getPhase() == QuestState.Phase.CLAIMING)
        return new QuestManager.Claim(Retcode.SYSTEM_BUSY, List.of());
      if (state.getPhase() != QuestState.Phase.FINISHED)
        return new QuestManager.Claim(Retcode.QUEST_NOT_FINISH, List.of());
      var q = GameData.getQuestExcelMap().get(id);
      var reward = q == null ? null : GameData.getRewardExcelMap().get(q.getRewardID());
      if (reward == null || reward.getRewards() == null)
        return new QuestManager.Claim(Retcode.REWARD_CONFIG_NOT_EXIST, List.of());
      var inventory = new ItemParamMap();
      int points = 0;
      for (var item : reward.getRewards()) {
        if (item.getIntKey() == ACTIVE_POINT_ITEM_ID) points += item.getIntValue();
        else if (item.getIntValue() > 0 && GameData.getItemExcelMap().containsKey(item.getIntKey()))
          inventory.add(item.getIntKey(), item.getIntValue());
        else return new QuestManager.Claim(Retcode.PLAYER_DATA_ERROR, List.of());
      }
      if (points < 0 || points > 500)
        return new QuestManager.Claim(Retcode.PLAYER_DATA_ERROR, List.of());
      state.reserveReward();
      player.save();
      var grant = player.getInventory().grantItems(inventory, true);
      if (!grant.complete())
        return new QuestManager.Claim(Retcode.PLAYER_DATA_ERROR, grant.items());
      player.getGameplay().addPoints(points);
      state.closeReward();
      player.save();
      sync();
      return new QuestManager.Claim(Retcode.SUCC, List.copyOf(grant.items()));
    }
  }

  public List<DailyActivityInfo> levels() {
    synchronized (player) {
      ensureDay();
      final int world = player.getWorldLevel();
      final int selected =
          GameplayDefinitions.dailyLevels.stream().anyMatch(l -> l.WorldLevel() == world)
              ? world
              : 0;
      return GameplayDefinitions.dailyLevels.stream()
          .filter(l -> l.WorldLevel() == selected)
          .sorted(Comparator.comparingInt(GameplayDefinitions.DailyLevel::Level))
          .map(
              l ->
                  DailyActivityInfo.newInstance()
                      .setLevel(l.Level())
                      .setDailyActivePoint(l.DailyActivePoint())
                      .setWorldLevel(selected)
                      .setIsHasTaken(player.getRewardLedger().granted(key(l.Level()))))
          .toList();
    }
  }

  public RewardLedger.Claim claimLevel(int level) {
    synchronized (player) {
      ensureDay();
      var info = levels().stream().filter(l -> l.getLevel() == level).findFirst().orElse(null);
      if (info == null)
        return new RewardLedger.Claim(Retcode.DAILY_ACTIVE_LEVEL_INVALID, List.of());
      if (info.getIsHasTaken())
        return new RewardLedger.Claim(Retcode.DAILY_ACTIVE_LEVEL_REWARD_ALREADY_TAKEN, List.of());
      if (player.getGameplay().getDailyPoints() < info.getDailyActivePoint())
        return new RewardLedger.Claim(Retcode.DAILY_ACTIVE_LEVEL_AP_NOT_ENOUGH, List.of());
      var cfg =
          GameplayDefinitions.dailyLevels.stream()
              .filter(l -> l.WorldLevel() == info.getWorldLevel() && l.Level() == level)
              .findFirst()
              .orElseThrow();
      var result = player.getRewardLedger().claim(key(level), cfg.DailyActiveReward());
      sync();
      return result;
    }
  }

  public String key(int level) {
    return "daily:" + player.getGameplay().getDailyDay() + ":" + level;
  }

  private void sync() {
    if (player.isOnline()) player.sendPacket(new PacketDailyActiveInfoNotify(player));
  }
}
