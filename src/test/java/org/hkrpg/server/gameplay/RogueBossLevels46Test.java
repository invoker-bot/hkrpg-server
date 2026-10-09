package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import emu.lunarcore.game.battle.Battle;
import emu.lunarcore.game.scene.entity.EntityMonster;
import emu.lunarcore.server.packet.Retcode;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Flat boss-wave level from the matched original world preview, not inferred normal-room scaling.
 */
class RogueBossLevels46Test {
  @TempDir Path directory;
  final RogueWorldBossEncounters46Test original = new RogueWorldBossEncounters46Test();

  @BeforeEach
  void setup() throws Exception {
    original.directory = directory;
    original.setup();
  }

  @AfterEach
  void restore() throws Exception {
    original.restore();
  }

  @ParameterizedTest
  @org.junit.jupiter.params.provider.MethodSource(
      "org.hkrpg.server.gameplay.RogueWorldBossEncounters46Test#classicAreas")
  void actualBossActorAndAllBattleWavesUseTheOriginalWorldLevel(int area) {
    var player = original.explorer();
    assertEquals(Retcode.SUCC, player.getUniverseManager().start(area));
    var source =
        original.fixture.getAsJsonArray("areas").asList().stream()
            .map(JsonElement::getAsJsonObject)
            .filter(r -> r.get("RogueAreaID").getAsInt() == area)
            .findFirst()
            .orElseThrow();
    int level =
        source
            .getAsJsonObject("DisplayMonsterMap")
            .entrySet()
            .iterator()
            .next()
            .getValue()
            .getAsInt();
    var actor =
        player.getScene().getEntities().values().stream()
            .filter(EntityMonster.class::isInstance)
            .map(EntityMonster.class::cast)
            .filter(e -> e.getGroupId() == 39)
            .findFirst()
            .orElseThrow();
    assertEquals(level, actor.getCustomLevel());
    var battle = new Battle(player, player.getCurrentLineup(), List.of(actor));
    assertFalse(battle.getWaves().isEmpty());
    for (var wave : battle.getWaves()) {
      assertEquals(level, wave.getCustomLevel());
      assertEquals(level, wave.toProto().getWaveParam().getLevel());
    }
  }

  private EntityMonster actor(RogueWorldBossEncounters46Test.Explorer player) {
    return player.getScene().getEntities().values().stream()
        .filter(EntityMonster.class::isInstance)
        .map(EntityMonster.class::cast)
        .findFirst()
        .orElseThrow();
  }

  private void changeSource(String damage) {
    var source = GameplayDefinitions.areas.get(140);
    Map<Integer, Integer> levels =
        switch (damage) {
          case "missing" -> null;
          case "empty" -> Map.of();
          case "zero" -> Map.of(1014011, 0);
          case "negative" -> Map.of(1014011, -1);
          case "excessive" -> Map.of(1014011, 101);
          case "foreign" -> Map.of(2024011, 42);
          case "mixed" -> Map.of(1014011, 42, 2024011, 43);
          case "badId" -> Map.of(0, 42);
          case "changed" -> Map.of(1014011, 43);
          default -> throw new IllegalArgumentException(damage);
        };
    GameplayDefinitions.areas.put(
        140,
        new GameplayDefinitions.Area(
            source.RogueAreaID(),
            source.AreaProgress(),
            source.Difficulty(),
            source.FirstReward(),
            source.UnlockID(),
            source.ScoreMap(),
            levels));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {"missing", "empty", "zero", "negative", "excessive", "foreign", "mixed", "badId"})
  void anInvalidOriginalPreviewCannotPublishANewRun(String damage) {
    changeSource(damage);
    var player = original.explorer();
    String before = new Gson().toJson(player.getGameplay());
    assertEquals(Retcode.ROGUE_AREA_INVALID, player.getUniverseManager().start(140));
    assertEquals(before, new Gson().toJson(player.getGameplay()));
    assertEquals(0, player.saves);
    assertEquals(11, player.getEntryId());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "missing",
        "empty",
        "zero",
        "negative",
        "excessive",
        "foreign",
        "mixed",
        "badId",
        "changed"
      })
  void aChangedPreviewCannotSilentlyRescaleAStoredRun(String damage) throws Exception {
    var player = original.explorer();
    assertEquals(Retcode.SUCC, player.getUniverseManager().start(140));
    var saved = new Gson().fromJson(new Gson().toJson(player.getGameplay()), GameplayState.class);
    changeSource(damage);
    var restored = original.explorer();
    RogueMonsterSelectionTest.set(restored, "gameplay", saved);
    String before = new Gson().toJson(saved);
    restored.enterScene(8122201, 0, false);
    assertTrue(
        restored.getScene().getEntities().values().stream()
            .noneMatch(EntityMonster.class::isInstance));
    assertEquals(before, new Gson().toJson(saved));
    assertEquals(0, restored.saves);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "zero",
        "negative",
        "excessive",
        "wrong",
        "levelsMissing",
        "levelsEmpty",
        "levelsForeign",
        "flag"
      })
  void aCorruptCapturedLevelCannotSpawnOrReroll(String damage) throws Exception {
    var player = original.explorer();
    assertEquals(Retcode.SUCC, player.getUniverseManager().start(140));
    var run = player.getGameplay().getRogue();
    var plan = run.getBossEncounter();
    switch (damage) {
      case "zero" -> RogueMonsterSelectionTest.set(plan, "level", 0);
      case "negative" -> RogueMonsterSelectionTest.set(plan, "level", -1);
      case "excessive" -> RogueMonsterSelectionTest.set(plan, "level", 101);
      case "wrong" -> RogueMonsterSelectionTest.set(plan, "level", 43);
      case "levelsMissing" -> RogueMonsterSelectionTest.set(plan, "levels", null);
      case "levelsEmpty" -> RogueMonsterSelectionTest.set(plan, "levels", Map.of());
      case "levelsForeign" -> RogueMonsterSelectionTest.set(plan, "levels", Map.of(2024011, 42));
      case "flag" -> RogueMonsterSelectionTest.set(plan, "levelPlanned", false);
    }
    String before = new Gson().toJson(run);
    int saves = player.saves;
    player.enterScene(8122201, 0, false);
    assertTrue(
        player.getScene().getEntities().values().stream()
            .noneMatch(EntityMonster.class::isInstance));
    assertEquals(before, new Gson().toJson(run));
    assertEquals(saves, player.saves);
  }

  @Test
  void originalPreviewMutationCannotModifyTheCapturedMap() {
    var player = original.explorer();
    assertEquals(Retcode.SUCC, player.getUniverseManager().start(140));
    var source = GameplayDefinitions.areas.get(140);
    source.DisplayMonsterMap().put(1014011, 99);
    assertEquals(
        Map.of(1014011, 42), player.getGameplay().getRogue().getBossEncounter().getLevels());
  }

  @Test
  void aJsonRestoredHistoricalPlanRetainsItsPreviousNoOverride() throws Exception {
    var player = original.explorer();
    assertEquals(Retcode.SUCC, player.getUniverseManager().start(140));
    var json = JsonParser.parseString(new Gson().toJson(player.getGameplay())).getAsJsonObject();
    var plan = json.getAsJsonObject("rogue").getAsJsonObject("bossEncounter");
    for (String key : List.of("level", "levels", "levelPlanned")) plan.remove(key);
    var saved = new Gson().fromJson(json, GameplayState.class);
    changeSource("changed");
    var restored = original.explorer();
    RogueMonsterSelectionTest.set(restored, "gameplay", saved);
    restored.enterScene(8122201, 0, false);
    assertEquals(0, actor(restored).getCustomLevel());
    var battle = new Battle(restored, restored.getCurrentLineup(), List.of(actor(restored)));
    assertTrue(battle.getWaves().stream().allMatch(w -> w.getCustomLevel() == 0));
    assertEquals(0, restored.saves);
  }

  @ParameterizedTest
  @ValueSource(strings = {"actorBefore", "actorAfter", "waveAfter", "replacedWave", "removedWave"})
  void anAlteredCombatLevelCannotClearTheRoomOrCreateSettlement(String damage) {
    var player = original.explorer();
    assertEquals(Retcode.SUCC, player.getUniverseManager().start(140));
    var actor = actor(player);
    if (damage.equals("actorBefore")) actor.setCustomLevel(99);
    var battle = new Battle(player, player.getCurrentLineup(), List.of(actor));
    switch (damage) {
      case "actorAfter" -> actor.setCustomLevel(99);
      case "waveAfter" -> battle.getWaves().getFirst().setCustomLevel(99);
      case "replacedWave" ->
          battle
              .getWaves()
              .set(0, new emu.lunarcore.game.battle.BattleMonsterWave(battle.getStage()));
      case "removedWave" -> battle.getWaves().clear();
    }
    var run = player.getGameplay().getRogue();
    String before = new Gson().toJson(player.getGameplay());
    int saves = player.saves;
    battle.setResult(emu.lunarcore.proto.BattleEndStatusOuterClass.BattleEndStatus.BATTLE_END_WIN);
    player.getScene().getEntities().remove(actor.getEntityId());
    actor.setEntityId(0);
    player.getUniverseManager().onBattleFinish(battle);
    assertEquals(before, new Gson().toJson(player.getGameplay()));
    assertTrue(run.getCleared().isEmpty());
    assertTrue(run.getProcessedBattles().isEmpty());
    assertEquals(saves, player.saves);
  }

  @Test
  void twoActualMorphiaReloadsPreserveTheCapturedLevel() throws Exception {
    var player = original.explorer();
    assertEquals(Retcode.SUCC, player.getUniverseManager().start(140));
    var server =
        new de.bwaldvogel.mongo.MongoServer(new de.bwaldvogel.mongo.backend.memory.MemoryBackend());
    var address = server.bind();
    try (var client =
        com.mongodb.client.MongoClients.create(
            "mongodb://127.0.0.1:" + address.getPort() + "/?directConnection=true")) {
      var store = dev.morphia.Morphia.createDatastore(client, "world-boss-level-fixture");
      store.getMapper().mapPackage("org.hkrpg.server.gameplay");
      store
          .getMapper()
          .map(
              emu.lunarcore.game.player.Player.class,
              emu.lunarcore.game.quest.QuestState.class,
              emu.lunarcore.util.Position.class);
      var document = new emu.lunarcore.game.player.Player();
      RogueMonsterSelectionTest.set(document, "uid", 199987);
      RogueMonsterSelectionTest.set(document, "gameplay", player.getGameplay());
      store.save(document);
      String captured = new Gson().toJson(player.getGameplay());
      for (int i = 0; i < 2; i++) {
        var restored = store.find(emu.lunarcore.game.player.Player.class).first();
        assertNotNull(restored);
        assertEquals(captured, new Gson().toJson(restored.getGameplay()));
        var actual = original.explorer();
        RogueMonsterSelectionTest.set(actual, "gameplay", restored.getGameplay());
        actual.enterScene(8122201, 0, false);
        assertEquals(42, actor(actual).getCustomLevel());
        assertEquals(0, actual.saves);
        store.save(restored);
      }
    } finally {
      server.shutdownNow();
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"none", "actorAfter", "waveAfter"})
  void aChangedLevelCannotPublishEarlierBattleDeathReceipts(String damage) {
    verifyDeathBoundary(damage, false);
  }

  @ParameterizedTest
  @ValueSource(strings = {"none", "actorAfter", "waveAfter"})
  void aDirectFrozenDeathContextAlsoRejectsChangedLevels(String damage) {
    verifyDeathBoundary(damage, true);
  }

  void verifyDeathBoundary(String damage, boolean direct) {
    var player = original.explorer();
    assertEquals(Retcode.SUCC, player.getUniverseManager().start(140));
    var actor = actor(player);
    var battle = new Battle(player, player.getCurrentLineup(), List.of(actor));
    player.setBattle(battle);
    var context = RogueBattleKills.capture(player, battle);
    assertNotNull(context);
    battle.setResult(emu.lunarcore.proto.BattleEndStatusOuterClass.BattleEndStatus.BATTLE_END_WIN);
    battle.setStats(emu.lunarcore.proto.BattleStatisticsOuterClass.BattleStatistics.newInstance());
    battle.setReportedMonsters(List.of(new MissionBattleKills.Monster(5001, 1014011, 80149011, 1)));
    assertTrue(battle.beginSettlement());
    if (damage.equals("actorAfter")) actor.setCustomLevel(99);
    if (damage.equals("waveAfter")) battle.getWaves().getFirst().setCustomLevel(99);
    int saves = player.saves;
    if (direct) context.commit(battle);
    else player.getUniverseManager().onBattleKillOutcome(battle);
    int expected = damage.equals("none") ? 1 : 0;
    assertEquals(expected, player.getGameplay().getRogueBattleKills().total());
    assertEquals(expected, player.getGameplay().getRogueBattleDeaths().total());
    assertEquals(expected, player.getGameplay().getRogue().getDefeatedRogueMonsters().size());
    assertEquals(expected, player.getGameplay().getRogue().getRecordedBattleKills().size());
    if (expected == 0) assertEquals(saves, player.saves);
  }
}
