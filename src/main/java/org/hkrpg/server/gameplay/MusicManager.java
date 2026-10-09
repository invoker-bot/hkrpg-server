package org.hkrpg.server.gameplay;

import emu.lunarcore.data.GameData;
import emu.lunarcore.game.inventory.ItemParamMap;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.server.packet.Retcode;
import java.util.*;

/**
 * Client rhythm performance, server gates and rewards. Input replay is not supplied by this
 * protocol.
 */
public final class MusicManager {
  private final Player player;

  public MusicManager(Player p) {
    player = p;
  }

  public boolean open() {
    return player.getLoginActivityManager().activeWindows().stream()
        .anyMatch(w -> w.panelId() == 50022);
  }

  public boolean unlocked(int id) {
    var level = GameplayDefinitions.musicLevels.get(id);
    if (!open() || level == null) return false;
    var group = GameplayDefinitions.musicGroups.get(level.Group());
    if (group == null) return false;
    if (group.UnlockSubMissionID() != 0
        && !new MusicComposition(player).subFinished(group.UnlockSubMissionID())) return false;
    return level.Difficulty() >= 1
        && level.Difficulty() <= 3
        && (level.Difficulty() < 3 || maxDifficultyUnlocked());
  }

  /** The client builds this set from all phases, groups and levels, including unplayed levels. */
  static List<GameplayDefinitions.MusicLevel> resourceLevels() {
    if (GameplayDefinitions.musicGroups.isEmpty() || GameplayDefinitions.musicPhases.isEmpty())
      return List.of();
    for (var group : GameplayDefinitions.musicGroups.values())
      if (!GameplayDefinitions.musicPhases.containsKey(group.Phase())
          || GameplayDefinitions.musicLevels.values().stream()
              .noneMatch(l -> l.Group() == group.ID())) return List.of();
    for (var level : GameplayDefinitions.musicLevels.values())
      if (!GameplayDefinitions.musicGroups.containsKey(level.Group())
          || level.Difficulty() < 1
          || level.Difficulty() > 3) return List.of();
    return List.copyOf(GameplayDefinitions.musicLevels.values());
  }

  public boolean completedAtMost(int difficulty, int rating) {
    if (difficulty < 1 || difficulty > 3 || rating < 1 || rating > 3) return false;
    var levels = resourceLevels().stream().filter(l -> l.Difficulty() <= difficulty).toList();
    return !levels.isEmpty()
        && levels.stream()
            .allMatch(
                l -> player.getGameplay().getRhythm().getStars().getOrDefault(l.ID(), 0) >= rating);
  }

  public boolean maxDifficultyUnlocked() {
    if (!open()) return false;
    int mission =
        GameplayDefinitions.musicConstants.getOrDefault(
            "Activity_MusicRhythm_Max_Difficulty_Levels_Unlock_Mission", 0);
    int rating =
        GameplayDefinitions.musicConstants.getOrDefault(
            "Activity_MusicRhythm_Max_Difficulty_Levels_Unlock_Star", 0);
    int difficulty =
        GameplayDefinitions.musicConstants.getOrDefault(
            "Activity_MusicRhythm_Level_Max_Difficulty", 0);
    // Require the resource unlock mission to have been reached, without bypassing cancelled story.
    return mission > 0
        && player.getGameplay().getMainMissions().containsKey(mission)
        && !player.getGameplay().getCancelledMissions().contains(mission)
        && difficulty == 3
        && completedAtMost(difficulty - 1, rating);
  }

  public Retcode start(int id) {
    synchronized (player) {
      if (!GameplayDefinitions.musicLevels.containsKey(id)) return Retcode.MUSIC_NOT_EXIST;
      if (!unlocked(id)
          || player.isInBattle()
          || player.getInstance() != null
          || player.getGameplay().getParkourVenue().inside()
          || player.getGameplay().getCurrentMissionRaid() != 0
          || player.getGameplay().getClockPark().getActive() != null
          || player.getGameplay().getRogue().isInside()
          || player.getGameplay().getStoryLines().getCurrentId() != 0
          || player.getGameplay().getStoryLines().isTransitionPending()
          || player.getGameplay().getParkour().getActiveLevel() != 0
          || player.getGameplay().getRhythm().getActiveLevel() != 0) return Retcode.MUSIC_LOCKED;
      var config = GameplayDefinitions.musicLevels.get(id);
      if (config.StarRewardIDList() == null || config.StarRewardIDList().size() != 3)
        return Retcode.REQ_PARA_INVALID;
      var rewards = new ArrayList<Map<Integer, Integer>>();
      for (var rewardId : config.StarRewardIDList()) {
        if (rewardId == null || rewardId < 0) return Retcode.REQ_PARA_INVALID;
        var row = GameData.getRewardExcelMap().get(rewardId);
        if (rewardId != 0 && (row == null || row.getRewards() == null))
          return Retcode.REWARD_CONFIG_NOT_EXIST;
        var plan = new HashMap<Integer, Integer>();
        if (row != null)
          for (var item : row.getRewards()) {
            if (item.getIntKey() <= 0
                || item.getIntValue() <= 0
                || GameData.getItemExcelMap().get(item.getIntKey()) == null)
              return Retcode.PLAYER_DATA_ERROR;
            plan.put(item.getIntKey(), item.getIntValue());
          }
        rewards.add(plan);
      }
      player.getGameplay().getRhythm().start(id, rewards);
      player.save();
      return Retcode.SUCC;
    }
  }

  public Retcode finish(int reportedStars, boolean combo) {
    synchronized (player) {
      var run = player.getGameplay().getRhythm();
      int id = run.getActiveLevel();
      if (id == 0 || !unlocked(id)) return Retcode.MUSIC_LOCKED;
      if (reportedStars < 0 || reportedStars > 3 || (combo && reportedStars == 0))
        return Retcode.REQ_PARA_INVALID;
      if (!run.isRewardsFrozen()
          || !run.getStarRewards().keySet().equals(Set.of(1, 2, 3))
          || run.getStarRewards().values().stream()
              .anyMatch(
                  plan ->
                      plan == null
                          || plan.entrySet().stream()
                              .anyMatch(
                                  e ->
                                      e.getKey() == null
                                          || e.getKey() <= 0
                                          || e.getValue() == null
                                          || e.getValue() <= 0))) return Retcode.PLAYER_DATA_ERROR;
      if (!run.prepareFinish(reportedStars, combo)) return Retcode.REQ_PARA_INVALID;
      boolean maximumBefore = maxDifficultyUnlocked();
      // Preserve the exact report and transaction if any grant is interrupted or incomplete.
      player.save();
      for (int star = 1; star <= reportedStars; star++) {
        String key = "music:" + id + ":" + star;
        if (player.getRewardLedger().granted(key)) continue;
        var items = new ItemParamMap();
        run.getStarRewards().get(star).forEach((item, count) -> items.put(item, count));
        var claim = player.getRewardLedger().claimItems(key, items);
        if (claim.retcode() != Retcode.SUCC) return claim.retcode();
      }
      run.finish(reportedStars, combo);
      player.save();
      if (reportedStars > 0) player.getMissionManager().onMusicResult(id, reportedStars);
      if (!maximumBefore && maxDifficultyUnlocked())
        org.hkrpg.server.protocol.Music46.syncMaximum(player);
      return Retcode.SUCC;
    }
  }
}
