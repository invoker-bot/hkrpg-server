package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonParser;
import emu.lunarcore.data.GameData;
import emu.lunarcore.data.excel.MazeBuffExcel;
import emu.lunarcore.util.JsonUtils;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

public class RogueDiceCurioTest {
  final Map<Integer, GameplayDefinitions.Miracle> previousMiracles = new HashMap<>();
  final Map<Integer, GameplayDefinitions.MiracleEffect> previousEffects = new HashMap<>();
  final Map<Integer, GameplayDefinitions.MiracleGroup> previousGroups = new HashMap<>();
  final List<GameplayDefinitions.Blessing> previousBlessings = new ArrayList<>();
  final Map<Long, MazeBuffExcel> previousMaze = new HashMap<>();
  UniverseLifecycleTest.Explorer player;
  GameplayState.RogueRun run;

  @BeforeEach
  public void setup() {
    previousMiracles.putAll(GameplayDefinitions.miracles);
    previousEffects.putAll(GameplayDefinitions.miracleEffects);
    previousGroups.putAll(GameplayDefinitions.miracleGroups);
    previousBlessings.addAll(GameplayDefinitions.blessings);
    GameplayDefinitions.miracles.clear();
    GameplayDefinitions.miracleEffects.clear();
    GameplayDefinitions.miracleGroups.clear();
    GameplayDefinitions.blessings.clear();
    try (var input =
        new InputStreamReader(
            getClass().getResourceAsStream("/fixtures/rogue-dice-curio-4.6.json"),
            StandardCharsets.UTF_8)) {
      for (var entry : JsonParser.parseReader(input).getAsJsonArray()) {
        var spec = entry.getAsJsonObject();
        var row = spec.get("row");
        switch (spec.get("kind").getAsString()) {
          case "miracle" -> {
            var value = JsonUtils.getGsonFactory().fromJson(row, GameplayDefinitions.Miracle.class);
            GameplayDefinitions.miracles.put(value.MiracleID(), value);
          }
          case "effect" -> {
            var value =
                JsonUtils.getGsonFactory().fromJson(row, GameplayDefinitions.MiracleEffect.class);
            GameplayDefinitions.miracleEffects.put(value.MiracleEffectID(), value);
          }
          case "group" -> {
            var value =
                JsonUtils.getGsonFactory().fromJson(row, GameplayDefinitions.MiracleGroup.class);
            GameplayDefinitions.miracleGroups.put(value.RogueMiracleGroupID(), value);
          }
          case "blessing" ->
              GameplayDefinitions.blessings.add(
                  JsonUtils.getGsonFactory().fromJson(row, GameplayDefinitions.Blessing.class));
          case "maze-buff" -> {
            var value = JsonUtils.getGsonFactory().fromJson(row, MazeBuffExcel.class);
            long key = ((long) value.getID() << 32) + value.getLv();
            previousMaze.put(key, GameData.getMazeBuffExcelMap().get(value.getID(), value.getLv()));
            GameData.getMazeBuffExcelMap().add(value);
          }
          default -> {}
        }
      }
    } catch (Exception e) {
      throw new AssertionError(e);
    }
    player = new UniverseLifecycleTest.Explorer();
    run = new GameplayState.RogueRun(100, 1, Map.of());
    run.setupTeam(player, List.of(199961));
    player.getGameplay().setRogue(run);
  }

  @AfterEach
  public void cleanup() {
    GameplayDefinitions.miracles.clear();
    GameplayDefinitions.miracles.putAll(previousMiracles);
    GameplayDefinitions.miracleEffects.clear();
    GameplayDefinitions.miracleEffects.putAll(previousEffects);
    GameplayDefinitions.miracleGroups.clear();
    GameplayDefinitions.miracleGroups.putAll(previousGroups);
    GameplayDefinitions.blessings.clear();
    GameplayDefinitions.blessings.addAll(previousBlessings);
    GameData.getMazeBuffExcelMap()
        .values()
        .removeIf(v -> previousMaze.containsKey(((long) v.getID() << 32) + v.getLv()));
    previousMaze.values().stream()
        .filter(Objects::nonNull)
        .forEach(GameData.getMazeBuffExcelMap()::add);
  }

  @Test
  void exactSourceDiceIsSupported() {
    assertTrue(RogueMiracles.supported(21));
  }

  @Test
  void emptyHoldingsStillReplaceTheNewDiceWithOneActualCurio() {
    assertTrue(RogueMiracles.acquire(player, 21));
    assertEquals(1, run.getMiracles().size());
    assertFalse(run.getMiracles().containsKey(21));
    assertTrue(run.getMiracles().keySet().stream().allMatch(RogueMiracles::supported));
    assertEquals(100, run.getCoins());
  }

  @Test
  void allHeldCuriosAndTheDiceAreReplacedTogether() {
    assertTrue(RogueMiracles.acquire(player, 4));
    run.getMiracles().get(4).consume();
    assertTrue(RogueMiracles.acquire(player, 6));
    var old = new HashSet<>(run.getMiracles().keySet());
    assertTrue(RogueMiracles.acquire(player, 21));
    assertEquals(3, run.getMiracles().size());
    assertTrue(Collections.disjoint(old, run.getMiracles().keySet()));
    assertFalse(run.getMiracles().containsKey(21));
    assertEquals(100, run.getCoins());
    assertEquals(
        0,
        run.getMiracles().values().stream()
            .mapToInt(GameplayState.RogueMiracle::getUseCount)
            .sum());
  }

  @Test
  void newCuriosFreezeTheirActualSourceEffectsAndAcquisitions() {
    assertTrue(RogueMiracles.acquire(player, 21));
    var entry = run.getMiracles().entrySet().iterator().next();
    assertFalse(entry.getValue().getAcquisitionId().isBlank());
    assertEquals(
        GameplayDefinitions.miracleEffects.get(entry.getKey()).ParamList().stream()
            .map(GameplayDefinitions.EffectValue::Value)
            .toList(),
        entry.getValue().getEffectParameters());
    var reloaded = JsonUtils.decode(JsonUtils.encode(player.getGameplay()), GameplayState.class);
    assertNotNull(reloaded);
    assertEquals(run.getMiracles().keySet(), reloaded.getRogue().getMiracles().keySet());
    assertEquals(
        entry.getValue().getAcquisitionId(),
        reloaded.getRogue().getMiracles().get(entry.getKey()).getAcquisitionId());
  }

  @Test
  void pendingSelectionCannotBeReplayedAfterReplacement() {
    run.offerMiracles(List.of(21));
    int batch = run.getSelectionId();
    assertEquals(
        emu.lunarcore.server.packet.Retcode.SUCC,
        player.getUniverseManager().selectMiracle(batch, 21));
    var saved = JsonUtils.encode(run);
    assertEquals(
        emu.lunarcore.server.packet.Retcode.REQ_PARA_INVALID,
        player.getUniverseManager().selectMiracle(batch, 21));
    assertEquals(saved, JsonUtils.encode(run));
  }

  @Test
  void previewDoesNotMutateWalletCuriosBlessingsOrHandbook() {
    assertTrue(RogueMiracles.acquire(player, 4));
    String before = JsonUtils.encode(player.getGameplay());
    assertNotNull(RogueMiracleDice.prepare(player));
    assertTrue(RogueMiracles.canAcquire(player, 21));
    assertEquals(before, JsonUtils.encode(player.getGameplay()));
  }

  @Test
  void aCommittedPlanCannotBeCommittedAgain() {
    var plan = RogueMiracleDice.prepare(player);
    assertNotNull(plan);
    assertNotNull(plan.commit(player));
    String after = JsonUtils.encode(player.getGameplay());
    assertNull(plan.commit(player));
    assertEquals(after, JsonUtils.encode(player.getGameplay()));
  }

  @Test
  void changedWalletRejectsTheStalePlan() {
    var plan = RogueMiracleDice.prepare(player);
    assertNotNull(plan);
    run.addCoins(1);
    String before = JsonUtils.encode(player.getGameplay());
    assertNull(plan.commit(player));
    assertEquals(before, JsonUtils.encode(player.getGameplay()));
  }

  @Test
  void changedHandbookRejectsTheStalePlan() {
    var plan = RogueMiracleDice.prepare(player);
    assertNotNull(plan);
    player.getGameplay().getRogueCollectedBuffs().add(199999);
    assertNull(plan.commit(player));
    assertTrue(run.getMiracles().isEmpty());
  }

  @ParameterizedTest
  @ValueSource(ints = {1, 2, 3, 4})
  void changedSourceBeforeCommitRejectsWithoutApplyingTheProjection(int change) {
    var plan = RogueMiracleDice.prepare(player);
    assertNotNull(plan);
    switch (change) {
      case 1 -> GameplayDefinitions.miracles.remove(21);
      case 2 -> GameplayDefinitions.miracleEffects.clear();
      case 3 ->
          GameplayDefinitions.miracleGroups.put(
              10004, new GameplayDefinitions.MiracleGroup(10004, Map.of(4, 1)));
      case 4 ->
          GameplayDefinitions.miracleEffects.put(
              21,
              new GameplayDefinitions.MiracleEffect(
                  21, List.of(new GameplayDefinitions.EffectValue(1))));
      default -> throw new AssertionError();
    }
    String before = JsonUtils.encode(player.getGameplay());
    assertNull(plan.commit(player));
    assertEquals(before, JsonUtils.encode(player.getGameplay()));
  }

  @ParameterizedTest
  @ValueSource(ints = {1, 2, 3, 4, 5, 6, 7, 8})
  void invalidSourceOrPoolNeverMutatesTheLiveRun(int change) {
    switch (change) {
      case 1 -> GameplayDefinitions.miracles.put(21, new GameplayDefinitions.Miracle(21, 17, 21));
      case 2 ->
          GameplayDefinitions.miracleEffects.put(
              21, new GameplayDefinitions.MiracleEffect(21, List.of()));
      case 3 ->
          GameplayDefinitions.miracleEffects.put(
              21,
              new GameplayDefinitions.MiracleEffect(
                  21, List.of(new GameplayDefinitions.EffectValue(Double.NaN))));
      case 4 -> GameplayDefinitions.miracleGroups.remove(10004);
      case 5 ->
          GameplayDefinitions.miracleGroups.put(
              10004, new GameplayDefinitions.MiracleGroup(10003, Map.of(4, 1)));
      case 6 ->
          GameplayDefinitions.miracleGroups.put(
              10004, new GameplayDefinitions.MiracleGroup(10004, Map.of(4, 0)));
      case 7 ->
          GameplayDefinitions.miracleGroups.put(
              10004, new GameplayDefinitions.MiracleGroup(10004, Map.of(4, -1)));
      case 8 ->
          GameplayDefinitions.miracleGroups.put(
              10004, new GameplayDefinitions.MiracleGroup(10004, Map.of(9999, 1)));
      default -> throw new AssertionError();
    }
    String before = JsonUtils.encode(player.getGameplay());
    assertFalse(RogueMiracles.acquire(player, 21));
    assertEquals(before, JsonUtils.encode(player.getGameplay()));
  }

  @ParameterizedTest
  @ValueSource(ints = {1, 2, 3, 4})
  void malformedHoldingsCannotBeSilentlyDropped(int change) {
    switch (change) {
      case 1 -> run.getMiracles().put(4, null);
      case 2 -> run.getMiracles().put(9999, new GameplayState.RogueMiracle(0, List.of(1.0)));
      case 3 -> run.getMiracles().put(4, new GameplayState.RogueMiracle(0, List.of(1.0)));
      case 4 -> run.getMiracles().put(4, new GameplayState.RogueMiracle(1));
      default -> throw new AssertionError();
    }
    String before = JsonUtils.encode(player.getGameplay());
    assertFalse(RogueMiracles.acquire(player, 21));
    assertEquals(before, JsonUtils.encode(player.getGameplay()));
  }

  @Test
  void tooFewEligibleReplacementCuriosKeepsEveryOldCurioAndTheOffer() {
    assertTrue(RogueMiracles.acquire(player, 4));
    GameplayDefinitions.miracleGroups.put(
        10004, new GameplayDefinitions.MiracleGroup(10004, Map.of(6, 1)));
    run.offerMiracles(List.of(21));
    String before = JsonUtils.encode(player.getGameplay());
    assertFalse(RogueMiracles.acquire(player, 21));
    assertEquals(before, JsonUtils.encode(player.getGameplay()));
  }

  @Test
  void replacementIsUnavailableWhilePausedOrAwaitingABlessing() {
    run.setInside(false);
    assertFalse(RogueMiracles.acquire(player, 21));
    run.setInside(true);
    run.offer(List.of(100005));
    assertFalse(RogueMiracles.acquire(player, 21));
    assertEquals(List.of(100005), run.getOffers());
  }

  @Test
  void affordableFrozenPaymentAndReplacementCommitTogetherOnce() {
    var plan = RogueMiracleDice.prepare(player, new GameplayState.RogueFragmentCost(25));
    assertNotNull(plan);
    assertEquals(100, run.getCoins());
    assertTrue(run.getMiracles().isEmpty());
    assertNotNull(plan.commit(player));
    assertEquals(75, run.getCoins());
    assertEquals(1, run.getMiracles().size());
    assertNull(plan.commit(player));
    assertEquals(75, run.getCoins());
  }

  @Test
  void unaffordablePaymentOrInsufficientPoolCannotConsumeFragments() {
    assertNull(RogueMiracleDice.prepare(player, new GameplayState.RogueFragmentCost(101)));
    assertTrue(RogueMiracles.acquire(player, 4));
    GameplayDefinitions.miracleGroups.put(
        10004, new GameplayDefinitions.MiracleGroup(10004, Map.of(6, 1)));
    String before = JsonUtils.encode(player.getGameplay());
    assertNull(RogueMiracleDice.prepare(player, new GameplayState.RogueFragmentCost(25)));
    assertEquals(before, JsonUtils.encode(player.getGameplay()));
  }

  @Test
  void frozenClockPaymentBreaksAndCreditsBeforeAllCuriosAreReplaced() {
    assertTrue(RogueMiracles.acquire(player, 70));
    run.addCoins(200);
    run.getMiracles().get(70).recordExtraFragments(450);
    var cost = RogueMiracles.fragmentPlan(run, 200);
    assertEquals(250, cost.getCost());
    var plan = RogueMiracleDice.prepare(player, cost);
    assertNotNull(plan);
    assertEquals(300, run.getCoins());
    assertEquals(Set.of(70), run.getMiracles().keySet());
    var acquired = plan.commit(player);
    assertNotNull(acquired);
    assertEquals(1049, run.getCoins());
    assertEquals(2, run.getMiracles().size());
    assertTrue(Collections.disjoint(Set.of(9, 21, 70), run.getMiracles().keySet()));
    assertEquals(1, run.getMiracleFragmentGrants().size());
    assertEquals(70, acquired.diceChange().replacements().getFirst().oldId());
    assertEquals(9, acquired.diceChange().replacements().getFirst().newId());
    assertNull(plan.commit(player));
    assertEquals(1049, run.getCoins());
    var reloaded = JsonUtils.decode(JsonUtils.encode(player.getGameplay()), GameplayState.class);
    assertNotNull(reloaded);
    assertEquals(1049, reloaded.getRogue().getCoins());
    assertEquals(run.getMiracles().keySet(), reloaded.getRogue().getMiracles().keySet());
    assertEquals(1, reloaded.getRogue().getMiracleFragmentGrants().size());
  }

  @Test
  void aReplacementBoxGrantsTheOriginalBlessingOnlyAfterCommit() {
    GameplayDefinitions.miracleGroups.put(
        10004, new GameplayDefinitions.MiracleGroup(10004, Map.of(5, 1)));
    var plan = RogueMiracleDice.prepare(player);
    assertNotNull(plan);
    assertTrue(run.getBlessings().isEmpty());
    assertTrue(player.getGameplay().getRogueCollectedBuffs().isEmpty());
    var acquired = plan.commit(player);
    assertNotNull(acquired);
    assertEquals(Set.of(5), run.getMiracles().keySet());
    assertEquals(Map.of(600000, 1), run.getBlessings());
    assertEquals(Set.of(600000), player.getGameplay().getRogueCollectedBuffs());
    assertEquals(List.of(600000), acquired.upgrades());
    assertNull(plan.commit(player));
    assertEquals(Map.of(600000, 1), run.getBlessings());
  }

  @Test
  void aReplacementSealUpgradesTheOriginalBlessingAndKeepsEarlierRewards() {
    GameplayDefinitions.miracleGroups.put(
        10004, new GameplayDefinitions.MiracleGroup(10004, Map.of(20, 1)));
    run.getBlessings().put(600000, 1);
    player.getGameplay().getRogueCollectedBuffs().add(600000);
    var plan = RogueMiracleDice.prepare(player);
    assertNotNull(plan);
    assertEquals(Map.of(600000, 1), run.getBlessings());
    var acquired = plan.commit(player);
    assertNotNull(acquired);
    assertEquals(Set.of(20), run.getMiracles().keySet());
    assertEquals(Map.of(600000, 2), run.getBlessings());
    assertEquals(Set.of(600000), player.getGameplay().getRogueCollectedBuffs());
    assertEquals(List.of(600000), acquired.upgrades());
    assertEquals(100, run.getCoins());
    var restored = JsonUtils.decode(JsonUtils.encode(player.getGameplay()), GameplayState.class);
    assertNotNull(restored);
    assertEquals(Map.of(600000, 2), restored.getRogue().getBlessings());
  }
}
