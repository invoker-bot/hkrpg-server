package org.hkrpg.server.gameplay;

import emu.lunarcore.game.player.Player;
import emu.lunarcore.server.packet.Retcode;
import emu.lunarcore.util.JsonUtils;
import java.util.*;

/** Source effect 21; pool 10004 and exclusion of all replaced IDs are explicit server policy. */
public final class RogueMiracleDice {
  private static final int DICE = 21;
  private static final int POOL = 10004;

  public record Replacement(int oldId, int newId, GameplayState.RogueMiracle item) {}

  /** Only the committed projection may publish the temporary dice and replacement actions. */
  public static final class Change {
    private final GameplayState.RogueRun run;
    private final Map<Integer, GameplayState.RogueMiracle> finalItems;
    private final List<Replacement> replacements;
    private final int enhancements;

    private Change(GameplayState.RogueRun run, List<Replacement> replacements, int enhancements) {
      this.run = run;
      this.finalItems = Map.copyOf(run.getMiracles());
      this.replacements = List.copyOf(replacements);
      this.enhancements = enhancements;
    }

    public boolean valid(Player player) {
      return player.getGameplay().getRogue() == run && finalItems.equals(run.getMiracles());
    }

    public List<Replacement> replacements() {
      return replacements;
    }

    public int enhancements() { return enhancements; }
  }

  public static boolean definitionMatches() {
    var row = GameplayDefinitions.miracles.get(DICE);
    var effect = GameplayDefinitions.miracleEffects.get(DICE);
    return row != null
        && row.MiracleID() == DICE
        && row.MiracleDisplayID() == 18
        && row.MiracleEffectDisplayID() == DICE
        && effect != null
        && effect.MiracleEffectID() == DICE
        && effect.ParamList() != null
        && effect.ParamList().equals(List.of(new GameplayDefinitions.EffectValue(0)));
  }

  public static Plan prepare(Player player) {
    return prepare(player, null);
  }

  /** Preview effects and any frozen merchant payment together without mutating the live player. */
  public static Plan prepare(Player player, GameplayState.RogueFragmentCost payment) {
    synchronized (player) {
      var run = player.getGameplay().getRogue();
      var enhancementHistory = player.getGameplay().getRogueEnhancements();
      var pool = GameplayDefinitions.miracleGroups.get(POOL);
      if (!definitionMatches()
          || enhancementHistory == null || !enhancementHistory.valid()
          || player.getGameplay().getRogueHandbook() == null
          || !player.getGameplay().getRogueHandbook().valid()
          || run.getPhase() != GameplayState.RogueRun.Phase.EXPLORING
          || !run.isInside()
          || run.getRoster().isEmpty()
          || player.isInBattle()
          || run.getRunId() == null
          || run.getRunId().isBlank()
          || run.getOffers() == null
          || !run.getOffers().isEmpty()
          || run.getMiracles() == null
          || run.getMiracles().containsKey(DICE)
          || run.getMiracles().size() > 100
          || run.getCoins() < 0
          || run.getBlessings() == null
          || run.getMiracleFragmentGrants() == null
          || pool == null
          || pool.RogueMiracleGroupID() != POOL
          || pool.MiracleWeight() == null
          || pool.MiracleWeight().isEmpty()
          || pool.MiracleWeight().size() > 1000
          || pool.MiracleWeight().entrySet().stream()
              .anyMatch(
                  e ->
                      e.getKey() == null
                          || e.getKey() <= 0
                          || e.getValue() == null
                          || e.getValue() <= 0)
          || run.getMiracles().entrySet().stream()
              .anyMatch(e -> !RogueMiracles.validForReplacement(e.getKey(), e.getValue())))
        return null;
      try {
        String definitions = definitionSnapshot();
        String before = JsonUtils.encode(run);
        var projected =
            JsonUtils.decode(JsonUtils.encode(player.getGameplay()), GameplayState.class);
        if (projected == null) return null;
        var preview = new PreviewPlayer(player, projected);
        var next = projected.getRogue();
        var excluded = new HashSet<>(run.getMiracles().keySet());
        excluded.add(DICE);
        var replacements = new ArrayList<Replacement>();
        if (payment != null) {
          var paid = RogueMiracles.pay(next, payment);
          if (paid.retcode() != Retcode.SUCC) return null;
          if (!paid.brokenAcquisition().isBlank())
            replacements.add(new Replacement(70, 9, next.getMiracles().get(9)));
        }
        var oldIds = new ArrayList<>(next.getMiracles().keySet());
        Collections.sort(oldIds);
        oldIds.add(DICE);
        excluded.addAll(oldIds);
        next.getMiracles().clear();
        var random = new Random(Objects.hash(run.getRunId(), run.getSelectionId(), DICE));
        var remaining = new TreeMap<>(pool.MiracleWeight());
        remaining.keySet().removeAll(excluded);
        for (int old : oldIds) {
          remaining.keySet().removeIf(id -> !RogueMiracles.canAcquire(preview, id));
          if (remaining.isEmpty()) return null;
          long total = remaining.values().stream().mapToLong(Integer::longValue).sum();
          long ticket = random.nextLong(total);
          int chosen = 0;
          for (var entry : remaining.entrySet()) {
            ticket -= entry.getValue();
            if (ticket < 0) {
              chosen = entry.getKey();
              break;
            }
          }
          if (chosen == 0 || RogueMiracles.acquireDeferred(preview, chosen) == null) return null;
          replacements.add(new Replacement(old, chosen, next.getMiracles().get(chosen)));
          remaining.remove(chosen);
        }
        if (next.getMiracles().size() != oldIds.size()) return null;
        int diceHandbook = RogueHandbookCatalog.unlock(DICE);
        if (diceHandbook > 0 && !projected.getRogueHandbook().canRecord(diceHandbook)) return null;
        if (diceHandbook > 0) projected.getRogueHandbook().record(diceHandbook);
        var upgrades =
            next.getBlessings().entrySet().stream()
                .filter(e -> !Objects.equals(run.getBlessings().get(e.getKey()), e.getValue()))
                .map(Map.Entry::getKey)
                .sorted()
                .toList();
        if (!definitions.equals(definitionSnapshot())) return null;
        return new Plan(
            run,
            before,
            definitions,
            Set.copyOf(player.getGameplay().getRogueCollectedBuffs()),
            JsonUtils.encode(player.getGameplay().getRogueHandbook()),
            projected,
            replacements,
            upgrades,
            payment != null,
            enhancementHistory,
            enhancementHistory.total());
      } catch (RuntimeException malformed) {
        return null;
      }
    }
  }

  public static final class Plan {
    private final GameplayState.RogueRun run;
    private final String before;
    private final String definitions;
    private final Set<Integer> collected;
    private final String handbook;
    private final GameplayState projected;
    private final List<Replacement> replacements;
    private final List<Integer> upgrades;
    private final boolean paid;
    private final RogueEnhancementState enhancementHistory;
    private final int enhancementBefore;

    private Plan(
        GameplayState.RogueRun run,
        String before,
        String definitions,
        Set<Integer> collected,
        String handbook,
        GameplayState projected,
        List<Replacement> replacements,
        List<Integer> upgrades,
        boolean paid,
        RogueEnhancementState enhancementHistory,
        int enhancementBefore) {
      this.run = run;
      this.before = before;
      this.definitions = definitions;
      this.collected = collected;
      this.handbook = handbook;
      this.projected = projected;
      this.replacements = List.copyOf(replacements);
      this.upgrades = List.copyOf(upgrades);
      this.paid = paid;
      this.enhancementHistory = enhancementHistory;
      this.enhancementBefore = enhancementBefore;
    }

    public RogueMiracles.Acquisition commit(Player player) {
      synchronized (player) {
        if (player.getGameplay().getRogue() != run
            || player.getGameplay().getRogueEnhancements() != enhancementHistory
            || enhancementHistory.total() != enhancementBefore
            || !before.equals(JsonUtils.encode(run))
            || !definitions.equals(definitionSnapshot())
            || !handbook.equals(JsonUtils.encode(player.getGameplay().getRogueHandbook()))
            || !collected.equals(player.getGameplay().getRogueCollectedBuffs())) return null;
        var projectedHistory = projected.getRogueEnhancements();
        if (projectedHistory == null || !projectedHistory.valid()
            || projectedHistory.total() < enhancementBefore) return null;
        int enhancements = projectedHistory.total() - enhancementBefore;
        if (!enhancementHistory.canRecordEnhancements(enhancements)) return null;
        var next = projected.getRogue();
        run.getMiracles().clear();
        run.getMiracles().putAll(next.getMiracles());
        run.getBlessings().clear();
        run.getBlessings().putAll(next.getBlessings());
        player.getGameplay().getRogueCollectedBuffs().clear();
        player.getGameplay().getRogueCollectedBuffs().addAll(projected.getRogueCollectedBuffs());
        player.getGameplay().getRogueHandbook().merge(projected.getRogueHandbook());
        int handbookId = RogueHandbookCatalog.unlock(DICE);
        if (handbookId > 0) player.getGameplay().getRogueHandbook().record(handbookId);
        if (paid) {
          run.addCoins(next.getCoins() - run.getCoins());
          run.getMiracleFragmentGrants().clear();
          run.getMiracleFragmentGrants().putAll(next.getMiracleFragmentGrants());
        }
        if (!enhancementHistory.recordEnhancements(enhancements))
          throw new IllegalStateException("Prechecked dice enhancement count changed");
        return new RogueMiracles.Acquisition(
            List.of(), upgrades, DICE, new Change(run, replacements, enhancements));
      }
    }
  }

  private static String definitionSnapshot() {
    return JsonUtils.encode(
        Arrays.asList(
            GameplayDefinitions.miracles,
            GameplayDefinitions.miracleEffects,
            GameplayDefinitions.miracleGroups.get(POOL),
            RogueHandbookCatalog.snapshot()));
  }

  private static final class PreviewPlayer extends Player {
    private final Player source;
    private final GameplayState state;

    private PreviewPlayer(Player source, GameplayState state) {
      this.source = source;
      this.state = state;
    }

    @Override
    public GameplayState getGameplay() {
      return state;
    }

    @Override
    public int getLevel() {
      return source.getLevel();
    }

    @Override
    public int getWorldLevel() {
      return source.getWorldLevel();
    }

    @Override
    public MissionManager getMissionManager() {
      return source.getMissionManager();
    }
  }

  private RogueMiracleDice() {}
}
