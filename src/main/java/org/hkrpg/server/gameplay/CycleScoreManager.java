package org.hkrpg.server.gameplay;

import emu.lunarcore.data.GameData;
import emu.lunarcore.game.inventory.*;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.server.packet.Retcode;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;

/** Resource epochs and once-only server-earned score. No client request supplies score. */
public final class CycleScoreManager {
  public record Window(long start, long end) {
    public String key() {
      return start + ":" + end;
    }
  }

  public record Credit(long start, long end, int points) {}

  public record Snapshot(long start, long end, int score, List<Integer> taken) {}

  public record Claim(
      Retcode retcode, List<Integer> ranks, List<GameItem> items, List<Integer> exceeded) {}

  private final Player player;
  private Clock clock = Clock.systemUTC();

  public CycleScoreManager(Player player) {
    this.player = player;
  }

  public void setClock(Clock value) {
    clock = Objects.requireNonNull(value);
  }

  public Optional<Window> window() {
    try {
      var refresh = GameplayDefinitions.cycleConstants.get("CycleScore_Refresh");
      var date = GameplayDefinitions.cycleConstants.get("CycleScore_StartDate");
      int days = refresh.get("IntValue").getAsBigDecimal().intValueExact();
      if (days <= 0 || days > 365) return Optional.empty();
      long epoch =
          LocalDateTime.parse(
                  date.get("StringValue").getAsString(),
                  DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss")
                      .withResolverStyle(java.time.format.ResolverStyle.STRICT))
              .toEpochSecond(ZoneOffset.ofHours(8));
      long now = clock.instant().getEpochSecond();
      if (now < epoch) return Optional.empty();
      long length = Math.multiplyExact((long) days, 86400L);
      long start =
          Math.addExact(
              epoch,
              Math.multiplyExact(Math.floorDiv(Math.subtractExact(now, epoch), length), length));
      return Optional.of(new Window(start, Math.addExact(start, length)));
    } catch (RuntimeException e) {
      return Optional.empty();
    }
  }

  /**
   * Server policy: eligible classical runs contribute their resource room score, without a bonus.
   */
  Optional<Credit> freeze(GameplayState.RogueRun run, int points, boolean known) {
    var area = GameplayDefinitions.areas.get(run.getAreaId());
    if (!known || points < 0 || area == null) return Optional.empty();
    try {
      int gate =
          Integer.parseInt(
              GameplayDefinitions.rogueConstants.get("Rogue_Score_Reward_Unlock_Progress"));
      if (gate <= 0 || area.AreaProgress() < gate) return Optional.empty();
      return window().map(w -> new Credit(w.start(), w.end(), points));
    } catch (RuntimeException e) {
      return Optional.empty();
    }
  }

  public void settle(GameplayState.RogueResult result) {
    synchronized (player) {
      if (result == null
          || !ownsResult(result)
          || !result.isCycleReady()
          || result.isCycleSettled()
          || result.getExploreMissionTargets() != null
          || result.getOutcome() == null
          || !Set.of(GameplayState.RogueRun.Phase.FINISHED, GameplayState.RogueRun.Phase.FAILED,
              GameplayState.RogueRun.Phase.ABANDONED).contains(result.getOutcome())
          || !result.isEarningsReady()
          || result.getAreaId() <= 0
          || result.getPassedRooms() <= 0
          || result.getReachedRooms() < result.getPassedRooms()
          || result.getCyclePoints() != result.getRoomScore()
          || result.getCycleStart() < 0
          || result.getCycleStart() >= result.getCycleEnd()
          || result.getCyclePoints() <= 0) return;
      var w = new Window(result.getCycleStart(), result.getCycleEnd());
      var weeks = player.getGameplay().getCycleWeeks();
      var week = weeks.get(w.key());
      if (week == null) week = new GameplayState.CycleWeek(w.start(), w.end());
      if (week.getStart() != w.start() || week.getEnd() != w.end() || week.getScore() < 0) return;
      if (!week.credit(result.getCyclePoints())) return;
      result.observeExploreTargets(RogueExploreScoreMission.capture(player, result.getCyclePoints()));
      weeks.put(w.key(), week);
      result.settleCycle();
      // Score and terminal once-only marker are saved in one Player document.
      player.save();
      if (window().filter(w::equals).isPresent())
        org.hkrpg.server.protocol.CycleScore46.sync(player);
      player.getMissionManager().refresh();
    }
  }

  private boolean ownsResult(GameplayState.RogueResult result) {
    int references = 0;
    for (var entry : player.getGameplay().getRogueResults().entrySet()) {
      if (entry.getValue() != result) continue;
      if (entry.getKey() == null || entry.getKey().isBlank()) return false;
      references++;
    }
    return references == 1;
  }

  public void settleSaved() {
    synchronized (player) {
      for (var result : List.copyOf(player.getGameplay().getRogueResults().values()))
        settle(result);
    }
  }

  public Optional<Snapshot> snapshot() {
    synchronized (player) {
      return window()
          .map(
              w -> {
                var week = player.getGameplay().getCycleWeeks().get(w.key());
                var taken =
                    week == null
                        ? List.<Integer>of()
                        : week.getClaims().keySet().stream()
                            .filter(
                                rank ->
                                    week.getClaims().get(rank).isDiscarded()
                                        || player.getRewardLedger().granted(key(w, rank)))
                            .sorted()
                            .toList();
                return new Snapshot(w.start(), w.end(), week == null ? 0 : week.getScore(), taken);
              });
    }
  }

  public Claim claim(List<Integer> ranks) {
    synchronized (player) {
      synchronized (player.getInventory()) {
        return claimLocked(ranks);
      }
    }
  }

  private Claim claimLocked(List<Integer> ranks) {
    if (ranks == null
        || ranks.isEmpty()
        || ranks.size() > 100
        || new HashSet<>(ranks).size() != ranks.size()
        || ranks.stream().anyMatch(id -> id == null || id <= 0))
      return error(Retcode.REQ_PARA_INVALID);
    settleSaved();
    var current = window();
    if (current.isEmpty()) return error(Retcode.REWARD_CONFIG_NOT_EXIST);
    var w = current.get();
    var week = player.getGameplay().getCycleWeeks().get(w.key());
    var plans = new LinkedHashMap<Integer, GameplayState.CycleClaim>();
    // Validate the entire batch before reserving or granting any item.
    for (int rank : ranks) {
      var frozen = week == null ? null : week.getClaims().get(rank);
      if (frozen != null) {
        plans.put(rank, frozen);
        continue;
      }
      var row = GameplayDefinitions.cycleRewards.get(rank);
      if (row == null || row.ScoreRank() != rank || row.Score() <= 0 || row.Reward() <= 0)
        return error(Retcode.REQ_PARA_INVALID);
      if (week == null || week.getScore() < row.Score()) return error(Retcode.QUEST_NOT_FINISH);
      var reward = GameData.getRewardExcelMap().get(row.Reward());
      if (reward == null || reward.getRewards() == null || reward.getRewards().isEmpty())
        return error(Retcode.REWARD_CONFIG_NOT_EXIST);
      var items = new HashMap<Integer, Integer>();
      for (var item : reward.getRewards()) {
        if (item.getIntKey() <= 0
            || item.getIntValue() <= 0
            || GameData.getItemExcelMap().get(item.getIntKey()) == null)
          return error(Retcode.PLAYER_DATA_ERROR);
        try {
          items.merge(item.getIntKey(), item.getIntValue(), Math::addExact);
        } catch (ArithmeticException e) {
          return error(Retcode.PLAYER_DATA_ERROR);
        }
      }
      plans.put(rank, new GameplayState.CycleClaim(row.Reward(), items, discardable(items)));
    }
    boolean newPlans = false;
    for (var plan : plans.entrySet())
      newPlans |= week.getClaims().putIfAbsent(plan.getKey(), plan.getValue()) == null;
    if (newPlans) player.save();
    var successes = new ArrayList<Integer>();
    var items = new ArrayList<GameItem>();
    var exceeded = new ArrayList<Integer>();
    Retcode firstError = Retcode.SUCC;
    for (var plan : plans.entrySet()) {
      if (plan.getValue().isDiscarded()) {
        if (firstError == Retcode.SUCC) firstError = Retcode.QUEST_REWARD_ALREADY_TAKEN;
        continue;
      }
      if (!player.getGameplay().getRewards().containsKey(key(w, plan.getKey()))
          && plan.getValue().isDiscardable()
          && exceeds(plan.getValue())) {
        // The client offers explicit discard only for this single-item, over-limit reward.
        // Preserve the entire award; neither partial inventory nor a grant reservation is made.
        plan.getValue().markExceed();
        player.save();
        exceeded.add(plan.getKey());
        continue;
      }
      var rewards = new ItemParamMap();
      plan.getValue().getItems().forEach(rewards::add);
      var claim = player.getRewardLedger().claimItems(key(w, plan.getKey()), rewards);
      items.addAll(claim.items());
      if (claim.retcode() == Retcode.SUCC) successes.add(plan.getKey());
      else if (firstError == Retcode.SUCC) firstError = claim.retcode();
    }
    // A partial grant reports only real item deltas and leaves the affected rank unclaimed.
    return new Claim(
        successes.isEmpty() && exceeded.isEmpty() ? firstError : Retcode.SUCC,
        List.copyOf(successes),
        List.copyOf(items),
        List.copyOf(exceeded));
  }

  public Retcode discard(List<Integer> ranks) {
    synchronized (player) {
      synchronized (player.getInventory()) {
        return discardLocked(ranks);
      }
    }
  }

  private Retcode discardLocked(List<Integer> ranks) {
    if (ranks == null
        || ranks.isEmpty()
        || ranks.size() > 100
        || new HashSet<>(ranks).size() != ranks.size()
        || ranks.stream().anyMatch(id -> id == null || id <= 0)) return Retcode.REQ_PARA_INVALID;
    var current = window();
    if (current.isEmpty()) return Retcode.REWARD_CONFIG_NOT_EXIST;
    var w = current.get();
    var week = player.getGameplay().getCycleWeeks().get(w.key());
    if (week == null) return Retcode.QUEST_NOT_FINISH;
    for (int rank : ranks) {
      var plan = week.getClaims().get(rank);
      if (plan == null || !plan.isDiscardable() || !plan.isExceedPending() || !exceeds(plan))
        return Retcode.REQ_PARA_INVALID;
      if (plan.isDiscarded() || player.getGameplay().getRewards().containsKey(key(w, rank)))
        return Retcode.QUEST_REWARD_ALREADY_TAKEN;
    }
    ranks.forEach(rank -> week.getClaims().get(rank).discard());
    // This closes the frozen unreceived award; it never removes any already-owned item.
    player.save();
    return Retcode.SUCC;
  }

  private static boolean discardable(Map<Integer, Integer> items) {
    if (items.size() != 1 || !items.containsKey(110)) return false;
    try {
      var allowed =
          GameplayDefinitions.cycleConstants
              .get("CycleScore_DestroyItemList")
              .getAsJsonArray("ArrayValue");
      return allowed.size() == 1
          && allowed.get(0).getAsJsonObject().get("IntValue").getAsBigDecimal().intValueExact()
              == 110;
    } catch (RuntimeException e) {
      return false;
    }
  }

  private boolean exceeds(GameplayState.CycleClaim plan) {
    if (plan.getItems().size() != 1 || !plan.getItems().containsKey(110)) return false;
    var excel = GameData.getItemExcelMap().get(110);
    if (excel == null
        || excel.getItemMainType() != emu.lunarcore.game.enums.ItemMainType.Material
        || excel.getPileLimit() <= 0) return false;
    var item = player.getInventory().getMaterialByItemId(110);
    int stock = item == null ? 0 : item.getCount();
    return stock >= 0 && (long) stock + plan.getItems().get(110) > excel.getPileLimit();
  }

  private static String key(Window w, int rank) {
    return "cycle-score:" + w.key() + ":" + rank;
  }

  private static Claim error(Retcode code) {
    return new Claim(code, List.of(), List.of(), List.of());
  }
}
