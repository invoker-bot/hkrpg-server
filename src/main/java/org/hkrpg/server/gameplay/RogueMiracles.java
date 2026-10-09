package org.hkrpg.server.gameplay;

import emu.lunarcore.game.player.Player;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

/** Effects specified by pinned 4.6 RogueMiracleEffect, applied only to the current run. */
public final class RogueMiracles {
  private static final Map<Integer, Integer> FIREWAX_PATHS =
      Map.of(22, 120, 23, 126, 24, 124, 25, 125, 26, 121, 27, 122, 28, 123);
  private static final Set<Integer> SUPPORTED =
      Set.of(
          1, 2, 3, 4, 5, 6, 7, 8, 10, 12, 13, 14, 15, 17, 19, 20, 22, 23, 24, 25, 26, 27, 28, 60,
          61, 65, 45, 46, 47, 48, 49, 50, 51, 52, 53, 54, 55, 56, 58, 59, 62, 66, 67, 68, 69, 70,
          71, 18, 64, 21, 63);

  public static boolean supported(int id) {
    return acquisitionParameters(id) != null;
  }

  private static boolean oppositeOwned(GameplayState.RogueRun run, int id) {
    return RogueCodeMiracles.oppositeOwned(run, id)
        || id == 10 && run.getMiracles().containsKey(71)
        || id == 71 && run.getMiracles().containsKey(10)
        || id == 70 && run.getMiracles().containsKey(9)
        || id == 18 && run.getMiracles().containsKey(64)
        || id == 64 && run.getMiracles().containsKey(18);
  }

  public static boolean canAcquire(Player player, int id) {
    var run = player.getGameplay().getRogue();
    if (id == 21) return supported(id) && RogueMiracleDice.prepare(player) != null;
    return supported(id)
        && (id != 63 || RogueGalacticLottery.configurationValid())
        && RogueHandbookCatalog.canRecord(player, id)
        && !run.getMiracles().containsKey(id)
        && !oppositeOwned(run, id)
        && (id != 20 || RogueAutomaticEnhancement.select(player, acquisitionParameters(id).getFirst().intValue()) != null)
        && (!RogueCodeMiracles.isCode(id)
            || run.getPhase() == GameplayState.RogueRun.Phase.EXPLORING)
        && (!Set.of(18, 64, 67, 63).contains(id) || run.isInside())
        && (!Set.of(
                    8, 10, 14, 19, 46, 48, 50, 52, 54, 56, 58, 59, 60, 61, 62, 65, 66, 67, 68, 69,
                    70, 71, 18, 64, 63)
                .contains(id)
            || run.getPhase() == GameplayState.RogueRun.Phase.EXPLORING)
        && (!(id == 5 || FIREWAX_PATHS.containsKey(id))
            || (run.getPhase() == GameplayState.RogueRun.Phase.EXPLORING
                && !immediateBlessingPool(player, FIREWAX_PATHS.getOrDefault(id, 0)).isEmpty()));
  }

  private static List<Integer> immediateBlessingPool(Player player, int path) {
    var run = player.getGameplay().getRogue();
    return GameplayDefinitions.blessings.stream()
        .filter(Objects::nonNull)
        .filter(
            b ->
                b.MazeBuffID() > 0
                    && b.MazeBuffLevel() == 1
                    && b.RogueVersion() == 1
                    && b.AeonID() == 0
                    && b.BattleEventBuffType() == null
                    && (path == 0 || b.RogueBuffType() == path)
                    && Set.of("Common", "Rare", "Legendary")
                        .contains(Objects.toString(b.RogueBuffCategory(), ""))
                    && !run.getBlessings().containsKey(b.MazeBuffID())
                    && (b.UnlockIDList() == null
                        || b.UnlockIDList().stream()
                            .allMatch(id -> id != null && RogueUnlocks.open(player, id)))
                    && emu.lunarcore.data.GameData.getMazeBuffExcelMap().get(b.MazeBuffID(), 1)
                        != null)
        .map(GameplayDefinitions.Blessing::MazeBuffID)
        .distinct()
        .sorted()
        .toList();
  }

  private static List<Double> acquisitionParameters(int id) {
    // The source keeps the old owned ID 10 but explicitly points to effect 71.
    int effectId = id == 10 ? 71 : id == 18 ? 64 : id;
    var effect = GameplayDefinitions.miracleEffects.get(effectId);
    if (!SUPPORTED.contains(id)
        || !GameplayDefinitions.miracles.containsKey(id)
        || effect == null
        || effect.ParamList() == null
        || effect.ParamList().isEmpty()
        || effect.ParamList().size() > 16
        || effect.ParamList().stream().anyMatch(v -> v == null || !Double.isFinite(v.Value())))
      return null;
    if ((Set.of(
                    1, 2, 3, 5, 7, 8, 10, 12, 14, 19, 45, 46, 47, 48, 49, 50, 51, 52, 53, 54, 55,
                    56, 58, 59, 60, 61, 62, 65, 66, 67, 68, 69, 70, 71, 18, 64)
                .contains(id)
            || FIREWAX_PATHS.containsKey(id))
        && (GameplayDefinitions.miracles.get(id).MiracleID() != id
            || GameplayDefinitions.miracles.get(id).MiracleEffectDisplayID() != effectId
            || effect.MiracleEffectID() != effectId)) return null;
    var parameters =
        effect.ParamList().stream().map(GameplayDefinitions.EffectValue::Value).toList();
    if (id == 70 && GameplayDefinitions.miracles.get(id).MiracleDisplayID() != 51) return null;
    if (id == 63 && (GameplayDefinitions.miracles.get(id).MiracleID() != 63
        || GameplayDefinitions.miracles.get(id).MiracleDisplayID() != 44
        || GameplayDefinitions.miracles.get(id).MiracleEffectDisplayID() != 63
        || effect.MiracleEffectID() != 63)) return null;
    if (id == 21 && !RogueMiracleDice.definitionMatches()) return null;
    if ((id == 18 || id == 64) && GameplayDefinitions.miracles.get(id).MiracleDisplayID() != 45)
      return null;
    if (id == 65 && GameplayDefinitions.miracles.get(id).MiracleDisplayID() != 46) return null;
    if (id == 69 && GameplayDefinitions.miracles.get(id).MiracleDisplayID() != 50) return null;
    if (id == 60 && GameplayDefinitions.miracles.get(id).MiracleDisplayID() != 41) return null;
    if (id == 61 && GameplayDefinitions.miracles.get(id).MiracleDisplayID() != 42) return null;
    if (id == 59 && GameplayDefinitions.miracles.get(id).MiracleDisplayID() != 40) return null;
    if (id == 58 && GameplayDefinitions.miracles.get(id).MiracleDisplayID() != 39) return null;
    if (id == 62 && GameplayDefinitions.miracles.get(id).MiracleDisplayID() != 43) return null;
    if (id == 66 && GameplayDefinitions.miracles.get(id).MiracleDisplayID() != 47) return null;
    if (id == 67 && GameplayDefinitions.miracles.get(id).MiracleDisplayID() != 48) return null;
    if (id == 8 && GameplayDefinitions.miracles.get(id).MiracleDisplayID() != 8) return null;
    if (id == 14 && GameplayDefinitions.miracles.get(id).MiracleDisplayID() != 12) return null;
    if (id == 19 && GameplayDefinitions.miracles.get(id).MiracleDisplayID() != 16) return null;
    if (id == 68 && GameplayDefinitions.miracles.get(id).MiracleDisplayID() != 49) return null;
    if ((id == 10 || id == 71) && GameplayDefinitions.miracles.get(id).MiracleDisplayID() != 52)
      return null;
    if (Set.of(46, 48, 50, 52, 54, 56).contains(id)
        && GameplayDefinitions.miracles.get(id).MiracleDisplayID() != id - 20) return null;
    if (RogueCodeMiracles.isBrokenCode(id)
        && (GameplayDefinitions.miracles.get(id).MiracleDisplayID() != id - 19
            || acquisitionParameters(id + 1) == null)) return null;
    if (RogueBattleMiracles.hasBinding(id) && !RogueBattleMiracles.definitionMatches(id))
      return null;
    return validParameters(id, parameters) ? parameters : null;
  }

  static boolean validParameters(int id, List<Double> parameters) {
    if (!SUPPORTED.contains(id)
        || parameters.isEmpty()
        || parameters.size() > 16
        || parameters.stream().anyMatch(v -> v == null || !Double.isFinite(v))) return false;
    double value = parameters.getFirst();
    if (id == 21) return parameters.equals(List.of(0.0));
    if (id == 63) return parameters.equals(List.of(.99));
    if (FIREWAX_PATHS.containsKey(id)) return parameters.equals(List.of(0.0));
    if (id == 18 || id == 64) return parameters.equals(List.of(0.0));
    if (id == 1) return parameters.equals(List.of(1.0, 1.0, 2.0));
    if (id == 5) return parameters.equals(List.of(1.0, 2.0));
    if (id == 70) return parameters.equals(List.of(.25));
    if (id == 61 || id == 62 || id == 65 || id == 69) return parameters.equals(List.of(1.0));
    if (id == 66) return parameters.equals(List.of(1.0, 5.0));
    if (id == 67) return parameters.equals(List.of(.05));
    if (id == 60) return parameters.equals(List.of(5.0));
    if (id == 8) return parameters.equals(List.of(.3, 5.0));
    if (id == 14) return parameters.equals(List.of(100.0, .16, 5.0));
    if (id == 19) return parameters.equals(List.of(1.0, 3.0, 5.0));
    if (id == 59) return parameters.equals(List.of(.5, .2));
    if (id == 58) return parameters.equals(List.of(.03));
    if (id == 45) return parameters.equals(List.of(0.0));
    if (id == 47) return parameters.equals(List.of(.3));
    if (id == 49) return parameters.equals(List.of(.5, .35, .5));
    if (id == 51 || id == 53) return parameters.equals(List.of(.35));
    if (id == 55) return parameters.equals(List.of(1.0));
    if (id == 46) return parameters.equals(List.of(40.0));
    if (id == 48) return parameters.equals(List.of(.3));
    if (id == 50) return parameters.equals(List.of(.5, .25));
    if (id == 52 || id == 54) return parameters.equals(List.of(.25));
    if (id == 56) return parameters.equals(List.of(1.0));
    if (id == 68) return parameters.equals(List.of(1.0, 1.0, 3.0));
    if (id == 10 || id == 71) return parameters.equals(List.of(2.0));
    if (Set.of(2, 3, 7, 12).contains(id) && parameters.size() != 1) return false;
    if (id == 2) return value > 0 && value <= 20 && value == Math.rint(value);
    if (id == 3 || id == 7) return value == 1;
    return Set.of(6, 12, 13, 15).contains(id)
        ? value > 0 && value <= 1
        : value > 0 && value <= 100 && value == Math.rint(value);
  }

  private static double value(GameplayState.RogueRun run, int id) {
    return run.getMiracles().get(id).getEffectParameters().getFirst();
  }

  static boolean active(GameplayState.RogueRun run, int id) {
    var item = run.getMiracles().get(id);
    return item != null
        && !item.broken()
        && validParameters(id, item.getEffectParameters())
        && item.getUseCount() >= 0
        && !((id == 10 || id == 71 || id == 70 || id == 18 || id == 64) && oppositeOwned(run, id))
        && (id != 70 || !item.getAcquisitionId().isBlank() && item.getExtraFragmentsSpent() >= 0)
        && (!Set.of(18, 64, 60, 61, 67, 63).contains(id) || !item.getAcquisitionId().isBlank())
        && (id != 63 || item.getLottery() != null && item.getLottery().valid(item))
        && (!RogueBattleMiracles.hasBinding(id) || !item.getAcquisitionId().isBlank())
        && (id != 58 || item.destructionCount() >= 0)
        && (!RogueCodeMiracles.isBrokenCode(id)
            || !RogueCodeMiracles.oppositeOwned(run, id)
                && validParameters(id + 1, item.getCodeRepairParameters()))
        && (id == 1
                || id == 4
                || id == 7
                || id == 60
                || id == 61
                || id == 63
                || RogueCodeMiracles.isBrokenCode(id)
            ? item.getTotalCount() == lifetime(id, item.getEffectParameters())
            : item.getTotalCount() == 0 && item.getUseCount() == 0);
  }

  private static int lifetime(int id, List<Double> parameters) {
    return id == 63 ? 1 : RogueCodeMiracles.isBrokenCode(id)
        ? 3
        : id == 1
            ? parameters.get(2).intValue()
            : id == 4 || id == 7 || id == 60 || id == 61 ? parameters.getFirst().intValue() : 0;
  }

  public static boolean acquire(Player p, int id) {
    synchronized (p) {
      var acquired = acquireDeferred(p, id);
      if (acquired == null) return false;
      boolean resonancePending = RogueResonance.queue(p.getGameplay().getRogue());
      p.save();
      acquired.syncEffects(p);
      if (resonancePending) org.hkrpg.server.protocol.Universe46.syncPending(p);
      return true;
    }
  }

  /** Allows the caller to save effects with a transaction before notifying the client. */
  public record Acquisition(List<Integer> repairs, List<Integer> upgrades, int miracleId,
      RogueMiracleDice.Change diceChange) {
    public Acquisition(List<Integer> repairs, List<Integer> upgrades, int miracleId) {
      this(repairs, upgrades, miracleId, null);
    }
    public Acquisition(List<Integer> repairs, List<Integer> upgrades) {
      this(repairs, upgrades, 0);
    }

    public void syncEffects(Player p) {
      if (diceChange != null) org.hkrpg.server.protocol.Universe46.syncMiracleDice(p, diceChange);
      repairs.forEach(id -> org.hkrpg.server.protocol.Universe46.syncMiracle(p, id, false, 0));
      upgrades.forEach(id -> org.hkrpg.server.protocol.Universe46.syncBlessing(p, id, 0));
      if (!upgrades.isEmpty()) p.getQuestManager().onRogueHandbookProgress();
      if (miracleId == 20 && !upgrades.isEmpty()
          || diceChange != null && diceChange.enhancements() > 0)
        p.getQuestManager().onRogueEnhancementProgress();
      if (miracleId > 0) p.getQuestManager().onRogueMiracleProgress();
      if (diceChange != null || miracleId == 70 && active(p.getGameplay().getRogue(), 70))
        org.hkrpg.server.protocol.RogueRevival46.sync(p);
    }
  }

  static Acquisition acquireDeferred(Player p, int id) {
    if (id == 21) {
      var plan = RogueMiracleDice.prepare(p);
      return plan == null ? null : plan.commit(p);
    }
    var run = p.getGameplay().getRogue();
    var parameters = acquisitionParameters(id);
    if (parameters == null
        || id == 63 && !RogueGalacticLottery.configurationValid()
        || !RogueHandbookCatalog.canRecord(p, id)
        || run.getMiracles().containsKey(id)
        || oppositeOwned(run, id)
        || RogueCodeMiracles.isCode(id) && run.getPhase() != GameplayState.RogueRun.Phase.EXPLORING
        || Set.of(18, 64, 67, 63).contains(id) && !run.isInside()
        || Set.of(
                    8, 10, 14, 19, 46, 48, 50, 52, 54, 56, 58, 59, 60, 61, 62, 65, 66, 67, 68, 69,
                    70, 71, 18, 64, 63)
                .contains(id)
            && run.getPhase() != GameplayState.RogueRun.Phase.EXPLORING) return null;
    var granted = List.<Integer>of();
    if (id == 5 || FIREWAX_PATHS.containsKey(id)) {
      if (run.getPhase() != GameplayState.RogueRun.Phase.EXPLORING) return null;
      var candidates = new ArrayList<>(immediateBlessingPool(p, FIREWAX_PATHS.getOrDefault(id, 0)));
      if (candidates.isEmpty()) return null;
      var random = new Random(Objects.hash(run.getRunId(), run.getSelectionId(), id));
      int count = id == 5 ? 1 + random.nextInt(2) : 1;
      Collections.shuffle(candidates, random);
      granted = List.copyOf(candidates.subList(0, Math.min(count, candidates.size())));
    }
    var repairs = new ArrayList<Integer>();
    var upgrades = new ArrayList<Integer>();
    var enhancementBatch = id == 20 ? RogueAutomaticEnhancement.select(p, parameters.getFirst().intValue()) : List.<Integer>of();
    if (enhancementBatch == null) return null;
    var item =
        RogueCodeMiracles.isBrokenCode(id)
            ? new GameplayState.RogueMiracle(3, parameters, acquisitionParameters(id + 1))
            : new GameplayState.RogueMiracle(lifetime(id, parameters), parameters);
    if (id == 70) {
      var broken = brokenClockParameters();
      if (!broken.isEmpty()) item.freezeClockBreakParameters(broken);
    }
    if (id == 63) item.freezeLottery();
    run.getMiracles().put(id, item);
    int handbook = RogueHandbookCatalog.unlock(id);
    if (handbook > 0 && p.getGameplay().getRogueHandbook() != null)
      p.getGameplay().getRogueHandbook().record(handbook);
    if (id == 5 || FIREWAX_PATHS.containsKey(id)) {
      for (int buff : granted) {
        run.getBlessings().put(buff, 1);
        p.getGameplay().getRogueCollectedBuffs().add(buff);
        upgrades.add(buff);
      }
    } else if (id == 17) {
      var broken =
          run.getMiracles().entrySet().stream()
              .filter(e -> e.getValue().broken())
              .map(Map.Entry::getKey)
              .sorted()
              .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
      Collections.shuffle(
          broken, new Random(Objects.hash(run.getRunId(), run.getSelectionId(), id)));
      for (int repair : broken.stream().limit(parameters.getFirst().intValue()).toList()) {
        run.getMiracles().get(repair).repair();
        repairs.add(repair);
      }
    } else if (id == 20) {
      for (int buff : enhancementBatch) {
        run.enhance(buff);
        upgrades.add(buff);
      }
      if (!p.getGameplay().getRogueEnhancements().recordEnhancements(enhancementBatch.size()))
        throw new IllegalStateException("Prechecked automatic enhancement count changed");
    }
    return new Acquisition(List.copyOf(repairs), List.copyOf(upgrades), id);
  }

  static boolean validForReplacement(Integer id, GameplayState.RogueMiracle item) {
    if (id == null || item == null || item.getAcquisitionId().isBlank()
        || item.getUseCount() < 0 || item.getTotalCount() < 0
        || item.getUseCount() > item.getTotalCount()) return false;
    var parameters = item.getEffectParameters();
    if (id == 9) return parameters.equals(List.of(499.0, 999.0))
        && item.getUseCount() == 0 && item.getTotalCount() == 0;
    return id != 21 && validParameters(id, parameters)
        && (id != 63 || item.getLottery() != null && item.getLottery().valid(item))
        && item.getTotalCount() == lifetime(id, parameters)
        && (!RogueCodeMiracles.isBrokenCode(id)
            || validParameters(id + 1, item.getCodeRepairParameters()))
        && (id != 70 || item.getExtraFragmentsSpent() >= 0
            && (item.getClockBreakParameters().isEmpty()
                || item.getClockBreakParameters().equals(List.of(499.0, 999.0))));
  }

  public static int enhancementCost(GameplayState.RogueRun run, int base) {
    return enhancementPlan(run, base).getCost();
  }

  static String laurelAcquisition(GameplayState.RogueRun run) {
    return active(run, 61) ? run.getMiracles().get(61).getAcquisitionId() : "";
  }

  static boolean useLaurel(GameplayState.RogueRun run, String acquisition) {
    if (acquisition == null
        || acquisition.isBlank()
        || !acquisition.equals(laurelAcquisition(run))
        || run.getVitals() == null
        || run.getVitals().isEmpty()
        || !run.getVitals().keySet().containsAll(run.getRoster())
        || run.getVitals().values().stream()
            .anyMatch(v -> v == null || v.getHp() < 0 || v.getHp() > 10000 || v.getSp() < 0))
      return false;
    if (!run.getMiracles().get(61).consume()) return false;
    run.getVitals().values().forEach(v -> v.setHp(10000));
    return true;
  }

  public static GameplayState.RogueFragmentCost enhancementPlan(
      GameplayState.RogueRun run, int base) {
    if (base < 0) throw new IllegalArgumentException("Negative enhancement cost");
    int discounted =
        active(run, 13)
            ? BigDecimal.valueOf(base)
                .multiply(BigDecimal.ONE.subtract(BigDecimal.valueOf(value(run, 13))))
                .intValue()
            : base;
    // Project stacking/rounding rule: discount first, then floor the clock's extra quarter.
    return fragmentPlan(run, discounted);
  }

  /** General spending applies only the clock; the enhancement discount is a separate effect. */
  public static GameplayState.RogueFragmentCost fragmentPlan(GameplayState.RogueRun run, int base) {
    if (base < 0) throw new IllegalArgumentException("Negative fragment cost");
    int extra =
        active(run, 70)
            ? BigDecimal.valueOf(base)
                .multiply(BigDecimal.valueOf(value(run, 70)))
                .setScale(0, RoundingMode.DOWN)
                .intValueExact()
            : 0;
    return new GameplayState.RogueFragmentCost(
        Math.addExact(base, extra),
        extra,
        extra == 0 ? "" : run.getMiracles().get(70).getAcquisitionId());
  }

  /** Preserve all advertised costs while counting extras only for the current acquisition. */
  public static GameplayState.RogueFragmentCost combinePayments(
      GameplayState.RogueRun run, List<GameplayState.RogueFragmentCost> plans) {
    if (plans == null || plans.isEmpty()) return null;
    var clock = run.getMiracles().get(70);
    String acquisition = clock == null ? "" : clock.getAcquisitionId();
    int cost = 0, extra = 0;
    try {
      for (var plan : plans) {
        if (plan == null || !plan.valid()) return null;
        cost = Math.addExact(cost, plan.getCost());
        if (!acquisition.isBlank() && acquisition.equals(plan.getClockAcquisition()))
          extra = Math.addExact(extra, plan.getExtra());
      }
      return new GameplayState.RogueFragmentCost(cost, extra, extra == 0 ? "" : acquisition);
    } catch (ArithmeticException invalid) {
      return null;
    }
  }

  /** Freeze the confirmed victory reward at preparation, using the acquired coupon's ratio. */
  static int combatFragments(GameplayState.RogueRun run, int base) {
    if (base < 0) throw new IllegalArgumentException("Negative encounter fragments");
    return active(run, 12)
        ? BigDecimal.valueOf(base)
            .multiply(BigDecimal.ONE.add(BigDecimal.valueOf(value(run, 12))))
            .setScale(0, RoundingMode.DOWN)
            .intValueExact()
        : base;
  }

  /** Extra free rolls precede the run's paid plan, and the caller freezes the resulting menu. */
  static List<Integer> blessingRerollCosts(GameplayState.RogueRun run, boolean victory) {
    return blessingRerollPlans(run, victory).stream()
        .map(GameplayState.RogueFragmentCost::getCost)
        .toList();
  }

  static List<GameplayState.RogueFragmentCost> blessingRerollPlans(
      GameplayState.RogueRun run, boolean victory) {
    var costs = new ArrayList<GameplayState.RogueFragmentCost>();
    if (victory && active(run, 2))
      costs.addAll(
          Collections.nCopies((int) value(run, 2), new GameplayState.RogueFragmentCost(0)));
    for (int cost : run.getRerollCosts()) costs.add(enhancementPlan(run, cost));
    return List.copyOf(costs);
  }

  /** Only an actual paid frozen quote contributes to the matching acquisition's counter. */
  public static emu.lunarcore.server.packet.Retcode spend(
      GameplayState.RogueRun run, GameplayState.RogueFragmentCost plan) {
    return pay(run, plan).retcode();
  }

  private static List<Double> brokenClockParameters() {
    var row = GameplayDefinitions.miracles.get(9);
    var effect = GameplayDefinitions.miracleEffects.get(9);
    if (row == null || row.MiracleID() != 9 || row.MiracleDisplayID() != 55
        || row.MiracleEffectDisplayID() != 9 || effect == null || effect.MiracleEffectID() != 9
        || effect.ParamList() == null || effect.ParamList().stream().anyMatch(Objects::isNull))
      return List.of();
    var parameters = effect.ParamList().stream().map(GameplayDefinitions.EffectValue::Value).toList();
    return parameters.equals(List.of(499.0, 999.0)) ? parameters : List.of();
  }

  /** A payment changes the domain first; its caller saves before publishing this replacement. */
  public record FragmentPayment(emu.lunarcore.server.packet.Retcode retcode,
      GameplayState.RogueRun run, String brokenAcquisition) {
    public void syncEffects(Player player) {
      var item = run.getMiracles().get(9);
      if (retcode == emu.lunarcore.server.packet.Retcode.SUCC && !brokenAcquisition.isBlank()
          && player.getGameplay().getRogue() == run && !run.getMiracles().containsKey(70)
          && item != null && brokenAcquisition.equals(item.getAcquisitionId())) {
        org.hkrpg.server.protocol.Universe46.syncMiracleReplacement(player, 70, 9);
        org.hkrpg.server.protocol.RogueRevival46.sync(player);
      }
    }
  }

  /** Non-mutating preflight for acquisitions that must also be committed with the debit. */
  public static emu.lunarcore.server.packet.Retcode paymentStatus(
      GameplayState.RogueRun run, GameplayState.RogueFragmentCost plan) {
    if (run.getPhase() != GameplayState.RogueRun.Phase.EXPLORING)
      return emu.lunarcore.server.packet.Retcode.ROGUE_STATUS_NOT_MATCH;
    if (plan == null || !plan.valid())
      return emu.lunarcore.server.packet.Retcode.PLAYER_DATA_ERROR;
    var owned = run.getMiracles().get(70);
    if (owned != null
        && plan.getExtra() > 0
        && owned.getAcquisitionId().equals(plan.getClockAcquisition())
        && !active(run, 70))
      return emu.lunarcore.server.packet.Retcode.PLAYER_DATA_ERROR;
    var clock = active(run, 70) ? owned : null;
    boolean count = clock != null && clock.getAcquisitionId().equals(plan.getClockAcquisition());
    boolean replace = false;
    if (count) {
      try {
        long next = Math.addExact(clock.getExtraFragmentsSpent(), plan.getExtra());
        var parameters = clock.getClockBreakParameters();
        if (parameters == null || !parameters.isEmpty() && !parameters.equals(List.of(499.0, 999.0)))
          return emu.lunarcore.server.packet.Retcode.PLAYER_DATA_ERROR;
        // Project rule: the pinned 499 threshold is exclusive. The official comparison is pending.
        replace = !parameters.isEmpty() && plan.getExtra() > 0 && next > parameters.getFirst();
        if (replace && (run.getMiracles().containsKey(9) || run.getMiracleFragmentGrants() == null
            || run.getMiracleFragmentGrants().containsKey("clock:70:" + clock.getAcquisitionId())))
          return emu.lunarcore.server.packet.Retcode.PLAYER_DATA_ERROR;
        if (replace && run.getCoins() >= plan.getCost())
          Math.addExact(run.getCoins() - plan.getCost(), parameters.get(1).intValue());
      } catch (ArithmeticException overflow) {
        return emu.lunarcore.server.packet.Retcode.PLAYER_DATA_ERROR;
      }
    }
    return run.getCoins() < plan.getCost()
        ? emu.lunarcore.server.packet.Retcode.ITEM_NOT_ENOUGH
        : emu.lunarcore.server.packet.Retcode.SUCC;
  }

  public static FragmentPayment pay(GameplayState.RogueRun run, GameplayState.RogueFragmentCost plan) {
    var status = paymentStatus(run, plan);
    if (status != emu.lunarcore.server.packet.Retcode.SUCC)
      return new FragmentPayment(status, run, "");
    var clock = active(run, 70) ? run.getMiracles().get(70) : null;
    boolean count = clock != null && clock.getAcquisitionId().equals(plan.getClockAcquisition());
    boolean replace = count && !clock.getClockBreakParameters().isEmpty() && plan.getExtra() > 0
        && clock.getExtraFragmentsSpent() + plan.getExtra() > clock.getClockBreakParameters().getFirst();
    if (!run.spend(plan.getCost())) throw new IllegalStateException("Prechecked fragment debit changed");
    if (count) clock.recordExtraFragments(plan.getExtra());
    String acquisition = "";
    if (replace) {
      var parameters = clock.getClockBreakParameters();
      var broken = new GameplayState.RogueMiracle(0, parameters);
      var grant = new GameplayState.RogueCombatGrant(parameters.get(1).intValue());
      run.getMiracles().remove(70);
      run.getMiracles().put(9, broken);
      run.getMiracleFragmentGrants().put("clock:70:" + clock.getAcquisitionId(), grant);
      if (!grant.credit(run)) throw new IllegalStateException("Prechecked clock reward changed");
      acquisition = broken.getAcquisitionId();
    }
    return new FragmentPayment(emu.lunarcore.server.packet.Retcode.SUCC, run, acquisition);
  }

  /** Compound Eye enhances only Common candidates in a victory menu. */
  static int blessingOfferLevel(
      GameplayState.RogueRun run, GameplayDefinitions.Blessing blessing, boolean victory) {
    return victory && active(run, 3) && "Common".equals(blessing.RogueBuffCategory()) ? 2 : 1;
  }

  static boolean legendaryVictory(GameplayState.RogueRun run, boolean victory) {
    return victory && active(run, 7);
  }

  /** Two-to-one path weighting is a project rule; the pinned description gives no multiplier. */
  static int blessingWeight(
      GameplayState.RogueRun run, GameplayDefinitions.Blessing blessing, boolean victory) {
    return victory
            && FIREWAX_PATHS.entrySet().stream()
                .anyMatch(e -> e.getValue() == blessing.RogueBuffType() && active(run, e.getKey()))
        ? 2
        : 1;
  }

  /** Trigger once when a nonempty victory menu is saved, rather than again on its refresh. */
  static Set<Integer> consumeVictoryMenu(
      GameplayState.RogueRun run, boolean victory, boolean doubleChoice) {
    var consumed = new TreeSet<Integer>();
    if (legendaryVictory(run, victory) && run.getMiracles().get(7).consume()) consumed.add(7);
    if (doubleChoice && victory && active(run, 1) && run.getMiracles().get(1).consume())
      consumed.add(1);
    return Set.copyOf(consumed);
  }

  static boolean doubleVictoryChoice(GameplayState.RogueRun run, boolean victory, int available) {
    return victory && available >= 2 && active(run, 1);
  }

  /** Divination Clock reduces the normal three options only when preparing a victory menu. */
  static int blessingOptionLimit(GameplayState.RogueRun run, boolean victory) {
    return victory && active(run, 65) ? 2 : 3;
  }

  static int beaconEnhancements(GameplayState.RogueRun run, boolean victory) {
    return victory && active(run, 69) ? 1 : 0;
  }

  static Set<Integer> beaconCandidates(Map<Integer, Integer> levels) {
    var eligible = new TreeSet<Integer>();
    levels.forEach(
        (id, level) -> {
          if (level == 1
              && emu.lunarcore.data.GameData.getMazeBuffExcelMap().get(id, 2) != null
              && GameplayDefinitions.blessings.stream()
                  .anyMatch(
                      b -> b.MazeBuffID() == id && b.MazeBuffLevel() == 2 && b.RogueVersion() == 1))
            eligible.add(id);
        });
    return Set.copyOf(eligible);
  }

  /** One of the displayed level-one candidates, never an unseen candidate or level three. */
  static Map<Integer, Integer> beaconLevels(
      Map<Integer, Integer> base, Set<Integer> eligible, List<Integer> displayed, Random random) {
    var levels = new TreeMap<>(base);
    var choices =
        displayed.stream()
            .filter(
                id ->
                    base.getOrDefault(id, 0) == 1
                        && eligible.contains(id)
                        && emu.lunarcore.data.GameData.getMazeBuffExcelMap().get(id, 2) != null)
            .sorted()
            .toList();
    if (!choices.isEmpty()) levels.put(choices.get(random.nextInt(choices.size())), 2);
    return levels;
  }

  /** Freeze the matching acquisition at battle preparation, including whether fragments stop. */
  static String debtAcquisition(GameplayState.RogueRun run) {
    return active(run, 60) ? run.getMiracles().get(60).getAcquisitionId() : "";
  }

  record DebtVictory(boolean changed, boolean credited) {}

  static DebtVictory debtVictory(GameplayState.RogueRun run, String acquisition, String battle) {
    if (acquisition == null
        || acquisition.isBlank()
        || !active(run, 60)
        || !acquisition.equals(run.getMiracles().get(60).getAcquisitionId()))
      return new DebtVictory(false, false);
    var item = run.getMiracles().get(60);
    if (!item.consume()) return new DebtVictory(false, false);
    boolean credited = false;
    if (item.broken() && run.getCoins() > 0) {
      String key = "debt:60:" + acquisition + ":" + battle;
      // The additional current balance is frozen, even if overflow delays its payment.
      var grant =
          run.getMiracleFragmentGrants()
              .computeIfAbsent(key, ignored -> new GameplayState.RogueCombatGrant(run.getCoins()));
      credited = grant.credit(run);
    }
    return new DebtVictory(true, credited);
  }

  public static void onRoomEntered(Player p) {
    var run = p.getGameplay().getRogue();
    if (active(run, 15)) run.addCoins((int) Math.floor(run.getCoins() * value(run, 15)));
    // Project order: interest first, then floor the loss using the post-interest balance.
    if (active(run, 67) && run.getCoins() > 0) {
      int loss =
          BigDecimal.valueOf(run.getCoins())
              .multiply(BigDecimal.valueOf(value(run, 67)))
              .setScale(0, RoundingMode.DOWN)
              .intValueExact();
      run.spend(loss);
    }
  }

  public static void onBattleWon(Player p) {
    var run = p.getGameplay().getRogue();
    boolean revived = false;
    if (active(run, 4)) {
      for (int id : run.getRoster()) {
        var vital = run.getVitals().get(id);
        if (vital != null && vital.getHp() == 0) {
          vital.setHp(10000);
          revived = true;
        }
      }
      if (revived) {
        run.getMiracles().get(4).consume();
      }
    }
    if (active(run, 6)) {
      for (int id : run.getRoster()) {
        var vital = run.getVitals().get(id);
        if (vital != null && vital.getHp() > 0)
          vital.setHp(Math.min(10000, vital.getHp() + (int) Math.floor(10000 * value(run, 6))));
      }
    }
    p.save();
    if (revived) org.hkrpg.server.protocol.Universe46.syncMiracle(p, 4, false, 0);
    if (p.isOnline())
      p.sendPacket(
          new emu.lunarcore.server.packet.send.PacketSyncLineupNotify(
              p.getUniverseManager().lineup()));
  }

  private RogueMiracles() {}
}
