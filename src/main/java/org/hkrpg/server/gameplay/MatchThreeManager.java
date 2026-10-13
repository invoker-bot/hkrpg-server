package org.hkrpg.server.gameplay;

import emu.lunarcore.data.GameData;
import emu.lunarcore.game.inventory.ItemParamMap;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.server.packet.Retcode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Classic origami bird progress. Client-local board replay is not provided by this protocol. */
public final class MatchThreeManager {
  private final Player player;

  public MatchThreeManager(Player player) {
    this.player = player;
  }

  public boolean open() {
    return !MatchThreeCatalog.roster().isEmpty()
        && player.getLoginActivityManager().activeWindows().stream()
            .anyMatch(w -> w.panelId() == 50014);
  }

  public boolean birdUnlocked(int id) {
    var row = MatchThreeCatalog.birds.get(id);
    return row != null
        && MatchThreeCatalog.roster().contains(id)
        && row.UnlockLevel() >= 0
        && (row.UnlockLevel() == 0
            || player
                .getGameplay()
                .getMatchThree()
                .getCompleted()
                .containsKey("1:" + row.UnlockLevel()));
  }

  public Retcode position(int id, int position) {
    synchronized (player) {
      int nests = MatchThreeCatalog.constants.getOrDefault("MatchThree_BirdNestNumber", 0);
      int gate =
          MatchThreeCatalog.constants.getOrDefault("MatchThree_MainPageUnlock_SubMissionID", -1);
      if (nests <= 0 || nests > 64 || position < 0 || position > nests)
        return Retcode.REQ_PARA_INVALID;
      if (!open()
          || !birdUnlocked(id)
          || player.isInBattle()
          || player.getInstance() != null
          || player.getGameplay().getClockPark().getActive() != null
          || player.getGameplay().getStoryLines().getCurrentId() != 0
          || player.getGameplay().getStoryLines().isTransitionPending()
          || player.getGameplay().getRhythm().getActiveLevel() != 0
          || player.getGameplay().getParkour().getActiveLevel() != 0
          || player.getGameplay().getParkourVenue().inside()
          || player.getGameplay().getCurrentMissionRaid() != 0
          || player.getGameplay().getRogue().isInside()
          || gate < 0
          || (gate != 0 && !new MusicComposition(player).subFinished(gate)))
        return Retcode.REQ_PARA_INVALID;
      var records = player.getGameplay().getMatchThree().getBirds();
      // One bird per occupied nest is a server rule; zero removes a placement.
      if (position != 0
          && records.entrySet().stream()
              .anyMatch(e -> e.getKey() != id && e.getValue().getPosition() == position))
        return Retcode.REQ_PARA_INVALID;
      var previous = records.get(id);
      var record = records.computeIfAbsent(id, k -> new GameplayState.MatchThreeBird());
      if (record.getPosition() != position) {
        int previousPosition = record.getPosition();
        record.position(position);
        try {
          player.save();
        } catch (RuntimeException failure) {
          if (previous == null) records.remove(id);
          else previous.position(previousPosition);
          throw failure;
        }
      }
      player.getMissionManager().onMatchThreeBirdPosition(id, position);
      return Retcode.SUCC;
    }
  }

  public Retcode finish(
      int mode, int id, int bird, int power, String uuid, Map<Integer, Integer> statistics) {
    synchronized (player) {
      // The opaque client UUID correlates retries, not authoritative board validation.
      if (uuid == null
          || uuid.isEmpty()
          || uuid.length() > 128
          || uuid.chars().anyMatch(c -> c < 33 || c > 126)
          || mode < 0
          || mode > 2
          || id <= 0
          || bird <= 0
          || power < 0
          || statistics == null
          || statistics.entrySet().stream()
              .anyMatch(
                  e ->
                      !Set.of(5, 10).contains(e.getKey())
                          || e.getValue() == null
                          || e.getValue() < 0)) return Retcode.REQ_PARA_INVALID;
      var state = player.getGameplay().getMatchThree();
      String reportKey;
      try {
        reportKey =
            HexFormat.of()
                .formatHex(
                    MessageDigest.getInstance("SHA-256")
                        .digest(uuid.getBytes(StandardCharsets.US_ASCII)));
      } catch (java.security.NoSuchAlgorithmException ex) {
        throw new IllegalStateException(ex);
      }
      String levelKey = mode + ":" + id;
      var report = state.getReports().get(reportKey);
      if (report != null) {
        if (!report.matches(uuid, id, mode, bird, power, statistics))
          return Retcode.REQ_PARA_INVALID;
        if (report.isApplied()) {
          if (!validPlan(report)) return Retcode.PLAYER_DATA_ERROR;
          finishMissionEvents(report);
          return Retcode.SUCC;
        }
      } else {
        if (state.getReports().values().stream().anyMatch(r -> !r.isApplied()))
          return Retcode.SYSTEM_BUSY;
        var level = MatchThreeCatalog.levels.get(levelKey);
        if (!open()
            || player.isInBattle()
            || player.getInstance() != null
            || player.getGameplay().getClockPark().getActive() != null
            || player.getGameplay().getStoryLines().getCurrentId() != 0
            || player.getGameplay().getStoryLines().isTransitionPending()
            || player.getGameplay().getRhythm().getActiveLevel() != 0
            || player.getGameplay().getParkour().getActiveLevel() != 0
            || player.getGameplay().getParkourVenue().inside()
            || player.getGameplay().getCurrentMissionRaid() != 0
            || player.getGameplay().getRogue().isInside()
            || level == null
            || level.Mode() != mode
            || level.LevelID() != id
            || level.PlayerBirdID() != bird
            || !birdUnlocked(bird)
            || level.UnlockID() < 0
            || (level.UnlockID() != 0
                && !new MusicComposition(player).subFinished(level.UnlockID())))
          return Retcode.REQ_PARA_INVALID;
        var record = state.getBirds().get(bird);
        int uses = record == null ? 0 : record.getCount(),
            maximum = record == null ? 0 : record.getMaximum();
        if (uses < 0 || uses == Integer.MAX_VALUE || maximum < 0) return Retcode.PLAYER_DATA_ERROR;
        var totals = new HashMap<>(state.getStatistics());
        var rewards = new HashMap<Integer, Integer>();
        try {
          for (var entry : statistics.entrySet()) {
            int previous = totals.getOrDefault(entry.getKey(), 0);
            if (previous < 0) return Retcode.PLAYER_DATA_ERROR;
            totals.put(entry.getKey(), Math.addExact(previous, entry.getValue()));
          }
          if (!state.getCompleted().containsKey(levelKey)) {
            if (level.RewardID() < 0) return Retcode.REWARD_CONFIG_NOT_EXIST;
            var row = GameData.getRewardExcelMap().get(level.RewardID());
            if (level.RewardID() != 0 && (row == null || row.getRewards() == null))
              return Retcode.REWARD_CONFIG_NOT_EXIST;
            if (row != null)
              for (var item : row.getRewards()) {
                if (item.getIntKey() <= 0
                    || item.getIntValue() <= 0
                    || GameData.getItemExcelMap().get(item.getIntKey()) == null)
                  return Retcode.PLAYER_DATA_ERROR;
                rewards.merge(item.getIntKey(), item.getIntValue(), Math::addExact);
              }
          }
        } catch (ArithmeticException ex) {
          return Retcode.PLAYER_DATA_ERROR;
        }
        report =
            new GameplayState.MatchThreeReport(
                uuid,
                id,
                mode,
                bird,
                power,
                uses + 1,
                Math.max(maximum, power),
                statistics,
                totals,
                rewards,
                player.getMissionManager().matchThreeTargets(id, mode));
        state.getReports().put(reportKey, report);
        player.save();
      }
      if (!validPlan(report)) return Retcode.PLAYER_DATA_ERROR;
      String receipt = "match-three:" + levelKey;
      if (!state.getCompleted().containsKey(levelKey)
          && !player.getRewardLedger().granted(receipt)) {
        var items = new ItemParamMap();
        report.getRewards().forEach(items::put);
        var claim = player.getRewardLedger().claimItems(receipt, items);
        if (claim.retcode() != Retcode.SUCC) return claim.retcode();
      }
      state
          .getBirds()
          .computeIfAbsent(bird, k -> new GameplayState.MatchThreeBird())
          .result(report.getCount(), report.getMaximum());
      state.getStatistics().clear();
      state.getStatistics().putAll(report.getTotals());
      state.getCompleted().putIfAbsent(levelKey, new GameplayState.MatchThreeLevel(id, mode));
      report.apply();
      player.save();
      finishMissionEvents(report);
      return Retcode.SUCC;
    }
  }

  private void finishMissionEvents(GameplayState.MatchThreeReport report) {
    if (report.isMissionEventsDone()) return;
    player
        .getMissionManager()
        .onMatchThreeResult(report.getId(), report.getMode(), report.getMissionTargets());
    if (player.getMissionManager().matchThreeTargetsDone(report.getMissionTargets())) {
      report.finishMissionEvents();
      player.save();
    }
  }

  private static boolean validPlan(GameplayState.MatchThreeReport report) {
    return report.getCount() > 0
        && report.getMaximum() >= report.getPower()
        && report.getReported() != null
        && report.getTotals() != null
        && report.getRewards() != null
        && report.getMissionTargets() != null
        && report.getMissionTargets().stream().allMatch(id -> id != null && id > 0)
        && report.getTotals().entrySet().stream()
            .allMatch(
                e ->
                    e.getKey() != null
                        && Set.of(5, 10).contains(e.getKey())
                        && e.getValue() != null
                        && e.getValue() >= 0)
        && report.getReported().entrySet().stream()
            .allMatch(e -> report.getTotals().getOrDefault(e.getKey(), -1) >= e.getValue())
        && report.getRewards().entrySet().stream()
            .allMatch(
                e ->
                    e.getKey() != null
                        && e.getKey() > 0
                        && e.getValue() != null
                        && e.getValue() > 0);
  }
}
