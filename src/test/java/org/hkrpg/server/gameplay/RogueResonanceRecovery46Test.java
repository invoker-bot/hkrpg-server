package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import emu.lunarcore.server.packet.Retcode;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Recovery from saved earned blessings; source qualification is separate from client UI. */
class RogueResonanceRecovery46Test {
  static final int ROOM = 19997301;
  @TempDir Path root;
  final RogueResonance46Test source = new RogueResonance46Test();
  final Map<Integer, GameplayDefinitions.Miracle> oldMiracles = new HashMap<>();
  final Map<Integer, GameplayDefinitions.MiracleEffect> oldEffects = new HashMap<>();

  static class Explorer extends UniverseLifecycleTest.Explorer {
    final List<String> saved = new ArrayList<>();
    boolean stopAfterSave;

    @Override
    public void save() {
      saved.add(new Gson().toJson(getGameplay().getRogue()));
      if (stopAfterSave) throw new SavedInterruption();
    }
  }

  static class SavedInterruption extends RuntimeException {}

  @BeforeEach
  void setup() throws Exception {
    source.root = root;
    source.setup();
    oldMiracles.putAll(GameplayDefinitions.miracles);
    oldEffects.putAll(GameplayDefinitions.miracleEffects);
    GameplayDefinitions.rooms.put(ROOM, new GameplayDefinitions.Room(ROOM, 1, 12, 8));
  }

  @AfterEach
  void cleanup() throws Exception {
    GameplayDefinitions.miracles.clear();
    GameplayDefinitions.miracles.putAll(oldMiracles);
    GameplayDefinitions.miracleEffects.clear();
    GameplayDefinitions.miracleEffects.putAll(oldEffects);
    source.cleanup();
  }

  Explorer paused(int threshold) {
    var player = new Explorer();
    var run = new GameplayState.RogueRun(100, 1, Map.of(1, ROOM), null, 1);
    run.setupTeam(player, List.of(199961));
    run.enter(1, ROOM);
    run.setInside(false);
    player.getGameplay().setRogue(run);
    var ids = source.normals(1);
    ids.stream().limit(threshold).forEach(id -> run.getBlessings().put(id, 1));
    if (threshold > 3) {
      run.getBlessings().put(source.basic(1), 1);
      run.activateResonance(source.basic(1));
      run.saveResonanceSp(2500);
      source.enhancements(1).stream()
          .limit(threshold == 6 ? 0 : threshold == 10 ? 1 : 2)
          .forEach(id -> run.getBlessings().put(id, 1));
    }
    assertFalse(run.pending());
    return player;
  }

  List<Integer> expected(GameplayState.RogueRun run) {
    return run.getBlessings().containsKey(source.basic(1))
        ? source.enhancements(1).stream().filter(id -> !run.getBlessings().containsKey(id)).toList()
        : List.of(source.basic(1));
  }

  @ParameterizedTest
  @ValueSource(ints = {3, 6, 10, 14})
  void successfulResumeRecoversEarnedMenuInTheSamePlayerSave(int threshold) {
    var player = paused(threshold);
    var run = player.getGameplay().getRogue();
    String uuid = run.getRunId();
    var pending = expected(run);
    assertEquals(Retcode.SUCC, player.getUniverseManager().resume(100, false));
    assertEquals(pending, run.getOffers(), "Resume must restore an earned but unsaved menu");
    assertEquals(uuid, run.getRunId());
    assertTrue(run.isInside());
    assertEquals(12, player.entry);
    assertEquals(1, player.saved.size());
    var stored = source.gson.fromJson(player.saved.getFirst(), GameplayState.RogueRun.class);
    assertEquals(pending, stored.getOffers());
    assertEquals(run.getSelectionId(), stored.getSelectionId());
    assertEquals(threshold == 3 ? -1 : 2500, stored.getResonanceSp());
    assertEquals(1, stored.offerChoiceTotal());
    assertEquals(1, stored.offerChoiceRemaining());
    assertTrue(stored.getOfferRerollCosts().isEmpty());
  }

  @Test
  void repeatedResumeKeepsTheSavedBatchAndDoesNotGrantResonanceItself() {
    var player = paused(3);
    var run = player.getGameplay().getRogue();
    assertEquals(Retcode.SUCC, player.getUniverseManager().resume(100, false));
    assertEquals(expected(run), run.getOffers());
    int batch = run.getSelectionId();
    assertEquals(Retcode.SUCC, player.getUniverseManager().resume(100, false));
    assertEquals(batch, run.getSelectionId());
    assertFalse(run.getBlessings().containsKey(source.basic(1)));
    assertEquals(-1, run.getResonanceSp());
  }

  @ParameterizedTest
  @ValueSource(strings = {"double-blessing", "miracle"})
  void resumeDoesNotReplaceAnyExistingPendingMenu(String kind) {
    var player = paused(3);
    var run = player.getGameplay().getRogue();
    if (kind.equals("miracle")) run.offerMiracles(List.of(22, 26));
    else source.normalMenu(run, source.normals(1).subList(3, 5), 2);
    String before = source.gson.toJson(run);
    int batch = run.getSelectionId();
    assertEquals(Retcode.SUCC, player.getUniverseManager().resume(100, false));
    var previous = source.gson.fromJson(before, GameplayState.RogueRun.class);
    assertEquals(previous.getOffers(), run.getOffers());
    assertEquals(previous.getMiracleOffers(), run.getMiracleOffers());
    assertEquals(previous.offerChoiceRemaining(), run.offerChoiceRemaining());
    assertEquals(batch, run.getSelectionId());
  }

  @ParameterizedTest
  @ValueSource(strings = {"scene-failure", "wrong-area", "missing-avatar", "terminal"})
  void rejectedResumeDoesNotQueueOrSaveAMenu(String kind) {
    var player = paused(3);
    var run = player.getGameplay().getRogue();
    if (kind.equals("scene-failure")) player.rejectScene = true;
    if (kind.equals("missing-avatar")) run.getRoster().add(199962);
    if (kind.equals("terminal")) run.finish(GameplayState.RogueRun.Phase.ABANDONED);
    String before = source.gson.toJson(run);
    assertNotEquals(
        Retcode.SUCC,
        player.getUniverseManager().resume(kind.equals("wrong-area") ? 101 : 100, false));
    assertEquals(before, source.gson.toJson(run));
    assertTrue(player.saved.isEmpty());
    assertFalse(run.pending());
    assertEquals(11, player.entry);
  }

  @ParameterizedTest
  @ValueSource(strings = {"missing", "unknown-policy", "foreign-aeon", "insufficient"})
  void resumeDoesNotManufactureAnEarnedMenuFromUnsupportedSavedState(String kind) {
    var player = paused(kind.equals("insufficient") ? 2 : 3);
    var raw = source.gson.toJsonTree(player.getGameplay().getRogue()).getAsJsonObject();
    if (kind.equals("missing")) raw.remove("resonance");
    if (kind.equals("unknown-policy"))
      raw.getAsJsonObject("resonance").addProperty("policy", "unknown");
    if (kind.equals("foreign-aeon")) raw.addProperty("selectedAeon", 2);
    var run = source.gson.fromJson(raw, GameplayState.RogueRun.class);
    player.getGameplay().setRogue(run);
    assertEquals(Retcode.SUCC, player.getUniverseManager().resume(100, false));
    assertFalse(run.pending());
    assertEquals(-1, run.getResonanceSp());
  }

  @ParameterizedTest
  @ValueSource(ints = {-1, Integer.MAX_VALUE})
  void corruptBatchCannotThrowDuringResumeOrProduceAnUnusableMenu(int sequence) throws Exception {
    var player = paused(3);
    var raw = source.gson.toJsonTree(player.getGameplay().getRogue()).getAsJsonObject();
    raw.addProperty("selectionId", sequence);
    var run = source.gson.fromJson(raw, GameplayState.RogueRun.class);
    player.getGameplay().setRogue(run);
    // setRogue advances a new run's high-water mark; stored corruption is tested after that step.
    var field = GameplayState.RogueRun.class.getDeclaredField("selectionId");
    field.setAccessible(true);
    field.setInt(run, sequence);
    assertDoesNotThrow(
        () -> assertEquals(Retcode.SUCC, player.getUniverseManager().resume(100, false)));
    assertFalse(run.pending(), "Invalid saved batch must not create a new reward menu");
    assertEquals(sequence, run.getSelectionId());
  }

  @Test
  void invalidOwnedEnhancementLevelDoesNotEarnAnotherMenu() {
    var player = paused(10);
    var run = player.getGameplay().getRogue();
    run.getBlessings().put(source.enhancements(1).getFirst(), 2);
    assertEquals(Retcode.SUCC, player.getUniverseManager().resume(100, false));
    assertFalse(run.pending(), "A malformed enhancement must not count as a valid earned reward");
  }

  void qualifyFirewax(Explorer player) throws Exception {
    try (var in = getClass().getResourceAsStream("/fixtures/rogue-miracle-quests-4.6.json")) {
      var rows =
          JsonParser.parseString(
                  new String(Objects.requireNonNull(in).readAllBytes(), StandardCharsets.UTF_8))
              .getAsJsonArray();
      for (var value : rows) {
        var entry = value.getAsJsonObject();
        var row = entry.getAsJsonObject("row");
        if (entry.get("kind").getAsString().equals("miracle")
            && row.get("MiracleID").getAsInt() == 22)
          GameplayDefinitions.miracles.put(
              22, source.gson.fromJson(row, GameplayDefinitions.Miracle.class));
        if (entry.get("kind").getAsString().equals("effect")
            && row.get("MiracleEffectID").getAsInt() == 22)
          GameplayDefinitions.miracleEffects.put(
              22, source.gson.fromJson(row, GameplayDefinitions.MiracleEffect.class));
      }
    }
    int target = source.normals(1).get(2);
    // One eligible original ordinary blessing; eligibility is a test qualification.
    GameplayDefinitions.blessings.removeIf(
        b ->
            b.RogueBuffType() == 120
                && b.AeonID() == 0
                && b.MazeBuffID() != target
                && !player.getGameplay().getRogue().getBlessings().containsKey(b.MazeBuffID()));
    GameplayDefinitions.blessings.replaceAll(
        b ->
            b.MazeBuffID() != target
                ? b
                : new GameplayDefinitions.Blessing(
                    b.MazeBuffID(),
                    b.MazeBuffLevel(),
                    b.RogueBuffType(),
                    b.RogueBuffCategory(),
                    b.RogueVersion(),
                    List.of(),
                    b.IsShow(),
                    b.RogueBuffTag(),
                    b.AeonID(),
                    b.BattleEventBuffType()));
  }

  @ParameterizedTest
  @ValueSource(strings = {"miracle-menu", "direct-acquisition"})
  void firewaxGrantAndEarnedResonanceArePresentBeforeTheFirstSaveReturns(String kind)
      throws Exception {
    var player = paused(2);
    var run = player.getGameplay().getRogue();
    run.setInside(true);
    player.entry = 12;
    qualifyFirewax(player);
    if (kind.equals("miracle-menu")) run.offerMiracles(List.of(22));
    player.stopAfterSave = true;
    assertThrows(
        SavedInterruption.class,
        () -> {
          if (kind.equals("miracle-menu"))
            player.getUniverseManager().selectMiracle(run.getSelectionId(), 22);
          else assertTrue(RogueMiracles.acquire(player, 22));
        });
    assertEquals(1, player.saved.size());
    var saved = source.gson.fromJson(player.saved.getFirst(), GameplayState.RogueRun.class);
    assertTrue(saved.getMiracles().containsKey(22));
    assertTrue(saved.getMiracleOffers().isEmpty());
    assertEquals(
        3, saved.getBlessings().keySet().stream().filter(source.normals(1)::contains).count());
    assertEquals(
        List.of(source.basic(1)),
        saved.getOffers(),
        "The first saved state must already contain the earned menu");
    assertFalse(saved.getBlessings().containsKey(source.basic(1)));
  }
}
