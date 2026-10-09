package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import emu.lunarcore.data.GameData;
import emu.lunarcore.data.config.*;
import emu.lunarcore.game.scene.Scene;
import emu.lunarcore.game.scene.entity.EntityProp;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/** Original curio definitions in an explicitly assembled authoritative scene fixture. */
class RogueGalacticLottery46Test {
  final RogueDestructionCurioTest base = new RogueDestructionCurioTest();
  GameplayDefinitions.Miracle oldMiracle;
  GameplayDefinitions.MiracleEffect oldEffect;
  GameplayDefinitions.MiracleGroup oldPool;
  JsonObject source;
  GameplayDefinitions.Miracle oldRepair;
  GameplayDefinitions.MiracleEffect oldRepairEffect;

  @BeforeEach
  void setup() throws Exception {
    base.setup();
    oldMiracle = GameplayDefinitions.miracles.get(63);
    oldEffect = GameplayDefinitions.miracleEffects.get(63);
    oldPool = GameplayDefinitions.miracleGroups.get(10004);
    oldRepair = GameplayDefinitions.miracles.get(17);
    oldRepairEffect = GameplayDefinitions.miracleEffects.get(17);
    try (var input = getClass().getResourceAsStream("/fixtures/rogue-galactic-lottery-4.6.json")) {
      assertNotNull(input);
      source =
          JsonParser.parseString(new String(input.readAllBytes(), StandardCharsets.UTF_8))
              .getAsJsonObject();
      for (var raw : source.getAsJsonArray("records")) {
        var record = raw.getAsJsonObject();
        var row = record.getAsJsonObject("row");
        switch (record.get("kind").getAsString()) {
          case "miracle" ->
              GameplayDefinitions.miracles.put(
                  row.get("MiracleID").getAsInt(),
                  base.base.fixture.gson.fromJson(row, GameplayDefinitions.Miracle.class));
          case "effect" ->
              GameplayDefinitions.miracleEffects.put(
                  row.get("MiracleEffectID").getAsInt(),
                  base.base.fixture.gson.fromJson(row, GameplayDefinitions.MiracleEffect.class));
          case "pool" ->
              GameplayDefinitions.miracleGroups.put(
                  10004,
                  base.base.fixture.gson.fromJson(row, GameplayDefinitions.MiracleGroup.class));
          case "display" -> {}
          default -> fail("Unknown source record");
        }
      }
    }
    var floor = new FloorInfo();
    var field = Scene.class.getDeclaredField("floorInfo");
    field.setAccessible(true);
    field.set(base.base.fixture.p.getScene(), floor);
  }

  @AfterEach
  void cleanup() {
    GameplayDefinitions.miracles.remove(63);
    if (oldMiracle != null) GameplayDefinitions.miracles.put(63, oldMiracle);
    GameplayDefinitions.miracleEffects.remove(63);
    if (oldEffect != null) GameplayDefinitions.miracleEffects.put(63, oldEffect);
    GameplayDefinitions.miracleGroups.remove(10004);
    if (oldPool != null) GameplayDefinitions.miracleGroups.put(10004, oldPool);
    GameplayDefinitions.miracles.remove(17);
    GameplayDefinitions.miracleEffects.remove(17);
    if (oldRepair != null) GameplayDefinitions.miracles.put(17, oldRepair);
    if (oldRepairEffect != null) GameplayDefinitions.miracleEffects.put(17, oldRepairEffect);
    base.cleanup();
  }

  GameplayState.RogueRun run() {
    return base.base.run();
  }

  void owned() {
    assertTrue(RogueMiracles.acquire(base.base.fixture.p, 63));
  }

  // Controlled unit acquisition identity selects each project-policy branch. Native probes acquire
  // their own UUID through the real merchant and never write an outcome or a receipt.
  void controlled(RogueLotteryState.Outcome expected, int instance) {
    String key = "1:19996001:199962:199962001:8:" + instance;
    String acquisition;
    do {
      acquisition = UUID.randomUUID().toString();
    } while (RogueGalacticLottery.outcome(acquisition, key) != expected);
    var raw =
        base.base
            .fixture
            .gson
            .toJsonTree(new GameplayState.RogueMiracle(1, List.of(.99)))
            .getAsJsonObject();
    raw.addProperty("acquisitionId", acquisition);
    var item = base.base.fixture.gson.fromJson(raw, GameplayState.RogueMiracle.class);
    item.freezeLottery();
    run().getMiracles().put(63, item);
    assertTrue(RogueMiracles.active(run(), 63));
  }

  RogueLotteryState.Roll roll() {
    return run().getMiracles().get(63).getLottery().getRolls().values().iterator().next();
  }

  EntityProp prop(int instance) {
    var scene = base.base.fixture.p.getScene();
    var group =
        base.base.fixture.gson.fromJson(
            "{\"ID\":8,\"PropList\":[{\"ID\":"
                + instance
                + ",\"PropID\":"
                + RogueDestructionCurioTest.PROP
                + ",\"LoadOnInitial\":true,\"Pos\":{},\"Rot\":{}}]}",
            GroupInfo.class);
    group.setId(8);
    var info = group.getPropList().getFirst();
    info.setGroup(group);
    scene.getFloorInfo().getGroups().put(8, group);
    var prop =
        new EntityProp(
            scene, GameData.getPropExcelMap().get(RogueDestructionCurioTest.PROP), group, info);
    prop.setEntityId(220000 + instance);
    scene.getEntities().put(prop.getEntityId(), prop);
    return prop;
  }

  @Test
  void originalGalacticLotteryCanBeAcquiredOnceWithFrozenNinetyNinePercentLoss() {
    assertTrue(RogueMiracles.supported(63));
    assertTrue(RogueMiracles.acquire(base.base.fixture.p, 63));
    var item = run().getMiracles().get(63);
    assertEquals(List.of(.99), item.getEffectParameters());
    assertEquals(1, item.getTotalCount());
    assertFalse(item.getAcquisitionId().isBlank());
    assertFalse(RogueMiracles.acquire(base.base.fixture.p, 63));
  }

  @Test
  void acceptedAuthoritativeDestructionRecordsExactlyOneLotteryIdentity() {
    owned();
    var prop = prop(31);
    assertTrue(base.base.fixture.p.getScene().destroyProp(prop));
    assertEquals(1, run().getMiracles().get(63).destructionCount());
    assertFalse(base.base.fixture.p.getScene().destroyProp(prop));
    assertEquals(1, run().getMiracles().get(63).destructionCount());
  }

  @Test
  void replayOfSavedPhysicalPropIdentityCannotRollLotteryAgain() {
    owned();
    assertTrue(base.base.fixture.p.getScene().destroyProp(prop(32)));
    var before = base.base.fixture.gson.toJson(run());
    assertFalse(base.base.fixture.p.getScene().destroyProp(prop(32)));
    assertEquals(before, base.base.fixture.gson.toJson(run()));
    assertEquals(1, run().getMiracles().get(63).destructionCount());
  }

  @Test
  void winningDrawAcquiresTheRealCurioAndKeepsItsActualAcquisitionReceipt() {
    controlled(RogueLotteryState.Outcome.GAIN, 33);
    int hp = run().getVitals().get(199961).getHp();
    assertTrue(base.base.fixture.p.getScene().destroyProp(prop(33)));
    var receipt = roll();
    assertEquals(RogueLotteryState.Outcome.GAIN, receipt.getOutcome());
    assertTrue(Set.of(8, 17, 58, 71).contains(receipt.getAwardedId()));
    var award = run().getMiracles().get(receipt.getAwardedId());
    assertNotNull(award);
    assertEquals(award.getAcquisitionId(), receipt.getAwardedAcquisition());
    assertTrue(RogueMiracles.active(run(), 63));
    assertEquals(hp, run().getVitals().get(199961).getHp());
    String after = base.base.fixture.gson.toJson(run());
    assertFalse(base.base.fixture.p.getScene().destroyProp(prop(33)));
    assertEquals(after, base.base.fixture.gson.toJson(run()));
  }

  @ParameterizedTest
  @CsvSource({"10000,100", "5000,50", "201,3", "1,1", "0,0"})
  void brokenDrawLosesNinetyNinePercentOfCurrentHpAndBreaksExactlyOnce(int before, int after) {
    controlled(RogueLotteryState.Outcome.BREAK, 34);
    run().getVitals().get(199961).setHp(before);
    assertTrue(base.base.fixture.p.getScene().destroyProp(prop(34)));
    assertEquals(after, run().getVitals().get(199961).getHp());
    assertEquals(Map.of(199961, before), roll().getBeforeHp());
    assertEquals(Map.of(199961, after), roll().getAfterHp());
    assertTrue(run().getMiracles().get(63).broken());
    assertTrue(run().getMiracles().get(63).getLottery().valid(run().getMiracles().get(63)));
    assertFalse(RogueMiracles.active(run(), 63));
    assertTrue(base.base.fixture.p.getScene().destroyProp(prop(35)));
    assertEquals(after, run().getVitals().get(199961).getHp());
    assertEquals(1, run().getMiracles().get(63).destructionCount());
  }

  @Test
  void ordinaryDrawChangesNeitherHpNorOwnedCurios() {
    controlled(RogueLotteryState.Outcome.NONE, 36);
    int hp = run().getVitals().get(199961).getHp();
    assertTrue(base.base.fixture.p.getScene().destroyProp(prop(36)));
    assertEquals(RogueLotteryState.Outcome.NONE, roll().getOutcome());
    assertEquals(Set.of(63), run().getMiracles().keySet());
    assertEquals(hp, run().getVitals().get(199961).getHp());
  }

  @Test
  void realRepairCurioReactivatesLotteryWithoutErasingOldPhysicalReceipts() {
    controlled(RogueLotteryState.Outcome.BREAK, 37);
    String acquisition = run().getMiracles().get(63).getAcquisitionId();
    assertTrue(base.base.fixture.p.getScene().destroyProp(prop(37)));
    assertTrue(RogueMiracles.acquire(base.base.fixture.p, 17));
    assertTrue(RogueMiracles.active(run(), 63));
    assertEquals(acquisition, run().getMiracles().get(63).getAcquisitionId());
    assertFalse(base.base.fixture.p.getScene().destroyProp(prop(37)));
    assertTrue(base.base.fixture.p.getScene().destroyProp(prop(38)));
    assertEquals(2, run().getMiracles().get(63).destructionCount());
  }

  @Test
  void actualPropCounterSurvivesAwardPreviewMerge() {
    controlled(RogueLotteryState.Outcome.GAIN, 39);
    assertTrue(RogueMiracles.acquire(base.base.fixture.p, 58));
    String acquisition = run().getMiracles().get(58).getAcquisitionId();
    assertTrue(base.base.fixture.p.getScene().destroyProp(prop(39)));
    assertEquals(acquisition, run().getMiracles().get(58).getAcquisitionId());
    assertEquals(1, run().getMiracles().get(58).destructionCount());
    assertEquals(1, run().getMiracles().get(63).destructionCount());
  }

  @Test
  void sourceBoundRecoveryIsAppliedBeforeTheNinetyNinePercentLoss() {
    controlled(RogueLotteryState.Outcome.BREAK, 50);
    var excel =
        base.base.fixture.gson.fromJson(
            "{\"ID\":"
                + RogueDestructionCurioTest.PROP
                + ",\"PropType\":\"PROP_DESTRUCT\",\"JsonPath\":\"Fixture_HPRecover\"}",
            emu.lunarcore.data.excel.PropExcel.class);
    excel.onLoad();
    GameData.getPropExcelMap().add(excel);
    run().getVitals().get(199961).setHp(2500);
    assertTrue(base.base.fixture.p.getScene().destroyProp(prop(50)));
    assertEquals(Map.of(199961, 5000), roll().getBeforeHp());
    assertEquals(Map.of(199961, 50), roll().getAfterHp());
    assertEquals(50, run().getVitals().get(199961).getHp());
    assertEquals(Set.of("1:19996001:199962:199962001:8:50"), run().getDestroyedRecoveryProps());
  }

  @Test
  void concurrentRequestsDestroyOnePropAndGenerateOneAward() throws Exception {
    controlled(RogueLotteryState.Outcome.GAIN, 40);
    var p = prop(40);
    var executor = Executors.newFixedThreadPool(6);
    try {
      var tasks = new ArrayList<Callable<Boolean>>();
      for (int i = 0; i < 24; i++) tasks.add(() -> base.base.fixture.p.getScene().destroyProp(p));
      int accepted = 0;
      for (var result : executor.invokeAll(tasks)) if (result.get()) accepted++;
      assertEquals(1, accepted);
      assertEquals(1, run().getMiracles().get(63).destructionCount());
      assertEquals(2, run().getMiracles().size());
    } finally {
      executor.shutdownNow();
    }
  }

  @Test
  void missingPoolRejectsBeforeRemovingThePhysicalProp() {
    controlled(RogueLotteryState.Outcome.GAIN, 41);
    GameplayDefinitions.miracleGroups.remove(10004);
    var p = prop(41);
    String before = base.base.fixture.gson.toJson(run());
    assertFalse(base.base.fixture.p.getScene().destroyProp(p));
    assertSame(p, base.base.fixture.p.getScene().getEntities().get(p.getEntityId()));
    assertEquals(before, base.base.fixture.gson.toJson(run()));
  }

  @ParameterizedTest
  @ValueSource(ints = {0, -1})
  void corruptPoolWeightsRejectAcquisitionAndDestruction(int weight) {
    controlled(RogueLotteryState.Outcome.GAIN, 44);
    GameplayDefinitions.miracleGroups.put(
        10004, new GameplayDefinitions.MiracleGroup(10004, Map.of(8, weight)));
    assertFalse(base.base.fixture.p.getScene().destroyProp(prop(44)));
    run().getMiracles().remove(63);
    assertFalse(RogueMiracles.canAcquire(base.base.fixture.p, 63));
    assertFalse(RogueMiracles.acquire(base.base.fixture.p, 63));
  }

  @Test
  void exhaustedEligiblePoolRecordsTheActualGainBranchWithoutInventingAnAward() {
    controlled(RogueLotteryState.Outcome.GAIN, 45);
    // Original weights are preserved; all three entries are already owned or explicitly excluded.
    GameplayDefinitions.miracleGroups.put(
        10004, new GameplayDefinitions.MiracleGroup(10004, Map.of(21, 100, 63, 100, 8, 100)));
    assertTrue(RogueMiracles.acquire(base.base.fixture.p, 8));
    assertTrue(base.base.fixture.p.getScene().destroyProp(prop(45)));
    assertEquals("EXHAUSTED", roll().getOutcome().name());
    assertEquals(Set.of(8, 63), run().getMiracles().keySet());
    assertEquals(0, roll().getAwardedId());
  }

  @Test
  void preparedDrawCannotDamageAReplacementVitalsObject() {
    controlled(RogueLotteryState.Outcome.BREAK, 46);
    var p = prop(46);
    var prepared = RogueGalacticLottery.prepare(base.base.fixture.p, p);
    assertNotNull(prepared);
    base.base.fixture.p.getScene().removeEntity(p);
    run().getVitals().put(199961, new GameplayState.RogueVitals(5000, 0));
    prepared.complete();
    assertEquals(5000, run().getVitals().get(199961).getHp());
    assertEquals(0, run().getMiracles().get(63).destructionCount());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "rule",
        "acquisition",
        "rolls",
        "lastBroken",
        "totalCount",
        "useCount",
        "outcome",
        "missing",
        "legacy"
      })
  void corruptSavedLotteryCannotBecomeAnActiveAbility(String field) {
    controlled(RogueLotteryState.Outcome.NONE, 47);
    assertTrue(base.base.fixture.p.getScene().destroyProp(prop(47)));
    var raw = base.base.fixture.gson.toJsonTree(run().getMiracles().get(63)).getAsJsonObject();
    var lottery = raw.getAsJsonObject("lottery");
    switch (field) {
      case "rule" -> lottery.addProperty("rule", "unknown-policy");
      case "acquisition" -> lottery.addProperty("acquisition", UUID.randomUUID().toString());
      case "rolls" -> lottery.add("rolls", JsonNull.INSTANCE);
      case "lastBroken" -> lottery.addProperty("lastBroken", true);
      case "totalCount" -> raw.addProperty("totalCount", 2);
      case "useCount" -> raw.addProperty("useCount", 1);
      case "outcome" ->
          lottery
              .getAsJsonObject("rolls")
              .entrySet()
              .iterator()
              .next()
              .getValue()
              .getAsJsonObject()
              .addProperty("outcome", "GAIN");
      case "missing" -> lottery.getAsJsonObject("rolls").entrySet().clear();
      case "legacy" -> raw.remove("lottery");
      default -> fail();
    }
    var restored = base.base.fixture.gson.fromJson(raw, GameplayState.RogueMiracle.class);
    run().getMiracles().put(63, restored);
    assertFalse(RogueMiracles.active(run(), 63));
    assertFalse(RogueMiracles.validForReplacement(63, restored));
    assertNull(RogueGalacticLottery.prepare(base.base.fixture.p, prop(48)));
  }

  @Test
  void wellShapedSavedReceiptWithWrongProjectFateIsRejected() {
    controlled(RogueLotteryState.Outcome.BREAK, 49);
    assertTrue(base.base.fixture.p.getScene().destroyProp(prop(49)));
    var raw = base.base.fixture.gson.toJsonTree(run().getMiracles().get(63)).getAsJsonObject();
    var lottery = raw.getAsJsonObject("lottery");
    lottery.addProperty("lastBroken", false);
    raw.addProperty("useCount", 0);
    lottery
        .getAsJsonObject("rolls")
        .entrySet()
        .iterator()
        .next()
        .setValue(base.base.fixture.gson.toJsonTree(RogueLotteryState.Roll.none()));
    var restored = base.base.fixture.gson.fromJson(raw, GameplayState.RogueMiracle.class);
    run().getMiracles().put(63, restored);
    assertFalse(RogueMiracles.active(run(), 63));
    assertFalse(RogueMiracles.validForReplacement(63, restored));
  }

  @Test
  void completedReceiptCannotBeUsedWithReorderedOrReplacedRoster() {
    controlled(RogueLotteryState.Outcome.BREAK, 42);
    var p = prop(42);
    var prepared = RogueGalacticLottery.prepare(base.base.fixture.p, p);
    assertNotNull(prepared);
    base.base.fixture.p.getScene().removeEntity(p);
    // Missing original actor must fail even if an unrelated valid actor could be substituted.
    run().getRoster().clear();
    prepared.complete();
    assertEquals(0, run().getMiracles().get(63).destructionCount());
  }

  @ParameterizedTest
  @ValueSource(strings = {"NONE", "GAIN", "BREAK", "repaired"})
  void actualMorphiaTwoReadsPreserveOutcomesAcquisitionsHpAndReplayGuard(String phase)
      throws Exception {
    controlled(
        phase.equals("repaired")
            ? RogueLotteryState.Outcome.BREAK
            : RogueLotteryState.Outcome.valueOf(phase),
        43);
    assertTrue(base.base.fixture.p.getScene().destroyProp(prop(43)));
    if (phase.equals("repaired")) assertTrue(RogueMiracles.acquire(base.base.fixture.p, 17));
    var mongo =
        new de.bwaldvogel.mongo.MongoServer(new de.bwaldvogel.mongo.backend.memory.MemoryBackend());
    var address = mongo.bind();
    try (var client =
        com.mongodb.client.MongoClients.create("mongodb://127.0.0.1:" + address.getPort())) {
      var store = dev.morphia.Morphia.createDatastore(client, "galactic-lottery");
      store.getMapper().mapPackage("org.hkrpg.server.gameplay");
      store
          .getMapper()
          .map(emu.lunarcore.game.player.Player.class, emu.lunarcore.game.quest.QuestState.class);
      var document = new emu.lunarcore.game.player.Player();
      var uid = emu.lunarcore.game.player.Player.class.getDeclaredField("uid");
      uid.setAccessible(true);
      uid.setInt(document, 123456782);
      var gameplay = emu.lunarcore.game.player.Player.class.getDeclaredField("gameplay");
      gameplay.setAccessible(true);
      String before = base.base.fixture.gson.toJson(base.base.fixture.p.getGameplay());
      for (int i = 0; i < 2; i++) {
        gameplay.set(document, base.base.fixture.p.getGameplay());
        store.save(document);
        gameplay.set(
            base.base.fixture.p,
            store.find(emu.lunarcore.game.player.Player.class).first().getGameplay());
        assertEquals(before, base.base.fixture.gson.toJson(base.base.fixture.p.getGameplay()));
        assertTrue(run().getMiracles().get(63).getLottery().valid(run().getMiracles().get(63)));
        assertFalse(base.base.fixture.p.getScene().destroyProp(prop(43)));
        assertEquals(before, base.base.fixture.gson.toJson(base.base.fixture.p.getGameplay()));
      }
    } finally {
      mongo.shutdownNow();
    }
  }
}
