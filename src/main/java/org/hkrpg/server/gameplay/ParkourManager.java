package org.hkrpg.server.gameplay;

import emu.lunarcore.game.player.Player;
import emu.lunarcore.server.packet.Retcode;
import java.util.Optional;

/** Resource gates and saved client-reported races; movement is not replayed by the server. */
public final class ParkourManager {
  public record Result(
      Retcode code,
      int level,
      int time,
      int reason,
      boolean success,
      boolean firstPass,
      boolean newRecord) {}

  private final Player player;

  public ParkourManager(Player player) {
    this.player = player;
  }

  public Optional<GameplayDefinitions.Window> activeWindow() {
    int module = GameplayDefinitions.parkourConstants.getOrDefault("parkour_activity_module_id", 0);
    return player.getLoginActivityManager().activeWindows().stream()
        .filter(w -> module > 0 && w.panelId() == 50039 && w.moduleId() == module)
        .findFirst();
  }

  public boolean unlocked(int id) {
    var level = GameplayDefinitions.parkourLevels.get(id);
    if (level == null
        || level.ID() <= 0
        || level.UnlockParam() <= 0
        || level.TargetRank() <= 0
        || level.TargetRank() > 32
        || level.LapCount() <= 0
        || level.GameAssetPath() == null
        || level.GameAssetPath().isBlank()) return false;
    return switch (java.util.Objects.toString(level.UnlockType(), "")) {
      case "FinishSubMission" -> new MusicComposition(player).subFinished(level.UnlockParam());
      case "FinishLevel" -> {
        var previous = player.getGameplay().getParkour().getRecords().get(level.UnlockParam());
        yield GameplayDefinitions.parkourLevels.containsKey(level.UnlockParam())
            && previous != null
            && previous.isPassed();
      }
      default -> false;
    };
  }

  private boolean ballAllowed(GameplayDefinitions.ParkourLevel level, int id) {
    if (id <= 0 || !GameplayDefinitions.parkourBalls.containsKey(id)) return false;
    if (level.RailBallLimit() > 0) return id == level.RailBallLimit();
    for (var ball : GameplayDefinitions.parkourBalls.values()) {
      if (ball.UpgradeBallID() <= 0) continue;
      boolean upgraded = new MusicComposition(player).subFinished(ball.UpgradeSubMission());
      if (id == ball.UpgradeBallID() && !upgraded || id == ball.ID() && upgraded) return false;
    }
    return true;
  }

  public Retcode start(int id, int ball) {
    synchronized (player) {
      var level = GameplayDefinitions.parkourLevels.get(id);
      var window = activeWindow();
      int floor = GameplayDefinitions.parkourConstants.getOrDefault("parkour_gameplay_floor_id", 0);
      var state = player.getGameplay().getParkour();
      if (window.isEmpty()
          || floor <= 0
          || player.getFloorId() != floor
          || !unlocked(id)
          || !ballAllowed(level, ball)
          || player.isInBattle()
          || player.getGameplay().getRhythm().getActiveLevel() != 0
          || player.getGameplay().getRogue().isInside()) return Retcode.REQ_PARA_INVALID;
      if (state.getActiveLevel() != 0) return Retcode.SYSTEM_BUSY;
      var w = window.get();
      state.start(
          id,
          ball,
          level.TargetRank(),
          floor,
          w.beginTime(),
          w.endTime(),
          "FinishSubMission".equals(level.UnlockType()) ? level.UnlockParam() : 0);
      player.save();
      return Retcode.SUCC;
    }
  }

  public Result finish(int level, int ball, int elapsed, int total, int rank, int reason) {
    synchronized (player) {
      var state = player.getGameplay().getParkour();
      var error = new Result(Retcode.REQ_PARA_INVALID, level, elapsed, reason, false, false, false);
      // No transaction ID exists in this protocol: only the currently started level/ball can end.
      if (state.getActiveLevel() <= 0
          || level != state.getActiveLevel()
          || ball != state.getActiveBall()
          || state.getTargetRank() <= 0
          || elapsed < 0
          || total < elapsed
          || total > 86_400_000
          || rank < 0
          || rank > 32
          || reason < 1
          || reason > 2) return error;
      // Value 1 terminates without passing; no guessed score or reward is produced.
      if (reason == 1) {
        state.close();
        player.save();
        return new Result(Retcode.SUCC, level, elapsed, reason, false, false, false);
      }
      var window = activeWindow();
      if (elapsed <= 0
          || rank <= 0
          || player.isInBattle()
          || player.getFloorId() != state.getFloor()
          || !state.isSnapshotReady()
          || state.getUnlockSub() > 0
              && !new MusicComposition(player).subFinished(state.getUnlockSub())
          || window.isEmpty()
          || window.get().beginTime() != state.getWindowBegin()
          || window.get().endTime() != state.getWindowEnd()) return error;
      var record =
          state.getRecords().computeIfAbsent(level, k -> new GameplayState.ParkourRecord());
      boolean success = rank <= state.getTargetRank();
      boolean first = success && !record.isPassed();
      boolean best = record.getTime() == 0 || elapsed < record.getTime();
      record.record(elapsed, rank, success, ball);
      new ParkourQuests(player).record(window.get(), level, elapsed, rank, success, ball);
      state.close();
      player.save();
      player.getQuestManager().onParkourResult();
      if (success) player.getMissionManager().onParkourResult(level);
      return new Result(Retcode.SUCC, level, elapsed, reason, success, first, best);
    }
  }
}
