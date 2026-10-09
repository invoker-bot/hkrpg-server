package org.hkrpg.server.gameplay;

import emu.lunarcore.game.player.Player;
import emu.lunarcore.server.packet.Retcode;
import java.time.*;
import java.util.*;

/** Only explicit operator windows and supported panel unlock conditions are accepted. */
public final class LoginActivityManager {
  private final Player player;
  private Clock clock = Clock.systemUTC();

  public LoginActivityManager(Player player) {
    this.player = player;
  }

  public void setClock(Clock value) {
    clock = Objects.requireNonNull(value);
  }

  public Optional<GameplayDefinitions.Window> window(int id) {
    var config = GameplayDefinitions.activities.get(id);
    if (config == null) return Optional.empty();
    long now = clock.instant().getEpochSecond();
    return GameplayDefinitions.windows.stream()
        .filter(
            w ->
                w.moduleId() == config.ActivityModuleID()
                    && w.beginTime() <= now
                    && now < w.endTime())
        .filter(w -> ActivityAccess.unlocked(player, w.panelId()))
        .findFirst();
  }

  public List<Integer> activeIds() {
    return GameplayDefinitions.activities.keySet().stream()
        .filter(id -> window(id).isPresent())
        .sorted()
        .toList();
  }

  public List<GameplayDefinitions.Window> activeWindows() {
    return ActivityAccess.active(player, clock.instant());
  }

  public void recordLogin() {
    synchronized (player) {
      boolean changed = false;
      long day = DailyManager.day(clock.instant());
      for (int id : activeIds()) {
        var config = GameplayDefinitions.activities.get(id);
        changed |=
            player
                .getGameplay()
                .getActivityDays()
                .computeIfAbsent(id, k -> new GameplayState.LoginDays())
                .record(day, config.RewardList().size());
      }
      if (changed) player.save();
    }
  }

  public int days(int id) {
    var row = player.getGameplay().getActivityDays().get(id);
    return row == null ? 0 : row.getDays();
  }

  public RewardLedger.Claim claim(int id, int day) {
    synchronized (player) {
      var config = GameplayDefinitions.activities.get(id);
      if (window(id).isEmpty() || day < 1 || config == null || day > config.RewardList().size())
        return new RewardLedger.Claim(Retcode.REQ_PARA_INVALID, List.of());
      if (day > days(id))
        return new RewardLedger.Claim(Retcode.LOGIN_ACTIVITY_DAYS_LACK, List.of());
      if (player.getRewardLedger().granted(key(id, day)))
        return new RewardLedger.Claim(Retcode.LOGIN_ACTIVITY_HAS_TAKEN, List.of());
      return player.getRewardLedger().claim(key(id, day), config.RewardList().get(day - 1));
    }
  }

  public static String key(int id, int day) {
    return "login-activity:" + id + ":" + day;
  }
}
