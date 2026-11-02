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
      var windows=activeWindows();
      for (int id : GameplayDefinitions.activities.keySet().stream().sorted().toList()) {
        var config = GameplayDefinitions.activities.get(id);
        var window=windows.stream().filter(w->w.moduleId()==config.ActivityModuleID()).findFirst().orElse(null);
        if(window==null)continue;
        changed |=
            days(window,true)
                .computeIfAbsent(id, k -> new GameplayState.LoginDays())
                .record(day, config.RewardList().size());
      }
      if (changed) player.save();
    }
  }

  public int days(int id) {
    var window=window(id).orElse(null);
    var row = (window==null?player.getGameplay().getActivityDays():days(window,false)).get(id);
    return row == null ? 0 : row.getDays();
  }

  public RewardLedger.Claim claim(int id, int day) {
    synchronized (player) {
      var config = GameplayDefinitions.activities.get(id);
      var window=window(id).orElse(null);
      if (window==null || day < 1 || config == null || day > config.RewardList().size())
        return new RewardLedger.Claim(Retcode.REQ_PARA_INVALID, List.of());
      var progress=days(window,false).get(id);
      if (day > (progress==null?0:progress.getDays()))
        return new RewardLedger.Claim(Retcode.LOGIN_ACTIVITY_DAYS_LACK, List.of());
      String key=key(id,day,window);
      if (player.getRewardLedger().granted(key))
        return new RewardLedger.Claim(Retcode.LOGIN_ACTIVITY_HAS_TAKEN, List.of());
      return player.getRewardLedger().claim(key, config.RewardList().get(day - 1));
    }
  }

  public List<Integer> claimedDays(int id){
    synchronized(player){
      var config=GameplayDefinitions.activities.get(id);var window=window(id).orElse(null);
      if(config==null||window==null)return List.of();
      return java.util.stream.IntStream.rangeClosed(1,config.RewardList().size())
          .filter(day->player.getRewardLedger().granted(key(id,day,window))).boxed().toList();
    }
  }

  public static String key(int id, int day) {
    return "login-activity:" + id + ":" + day;
  }

  private Map<Integer,GameplayState.LoginDays> days(GameplayDefinitions.Window window,boolean create){
    return window.replayId()==null?player.getGameplay().getActivityDays():player.getGameplay().getActivityReplays().login(window,create);
  }
  private static String key(int id,int day,GameplayDefinitions.Window window){
    return window.replayId()==null?key(id,day):"activity-replay:"+window.replayId()+":"+key(id,day);
  }
}
