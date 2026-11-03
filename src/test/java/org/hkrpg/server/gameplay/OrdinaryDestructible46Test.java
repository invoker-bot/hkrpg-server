package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import emu.lunarcore.data.GameData;
import emu.lunarcore.game.inventory.*;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.game.scene.entity.EntityProp;
import emu.lunarcore.server.packet.*;
import emu.lunarcore.util.Position;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/** Authored ordinary props; explicit compatibility rewards and isolated actual storage. */
class OrdinaryDestructible46Test {
  @TempDir Path root;
  final WorldChestActivity46Test fixture = new WorldChestActivity46Test();
  Actor actor;

  static class Actor extends WorldChestActivity46Test.Actor {
    final List<BasePacket> packets = new ArrayList<>();
    final Inventory actual =
        new Inventory(this) {
          @Override
          public GrantResult grantItems(ItemParamMap items, boolean hint) {
            grants++;
            return failGrant ? new GrantResult(List.of(), false) : super.grantItems(items, hint);
          }
        };

    @Override
    public Inventory getInventory() {
      return actual;
    }

    @Override
    public void sendPacket(BasePacket packet) {
      packets.add(packet);
    }

    @Override
    public void save() {
      super.save();
      var stored = new Player();
      try {
        DailyMissionAcceptance46Test.set(stored, "uid", getUid());
        DailyMissionAcceptance46Test.set(stored, "gameplay", getGameplay());
        DailyMissionAcceptance46Test.set(stored, "scoin", getScoin());
      } catch (Exception e) {
        throw new IllegalStateException(e);
      }
      BookPickup46Test.database.save(stored);
    }
  }

  @BeforeAll
  static void storage() throws Exception {
    BookPickup46Test.isolatedStorage();
  }

  @AfterAll
  static void closeStorage() throws Exception {
    BookPickup46Test.closeStorage();
  }

  @BeforeEach
  void setup() throws Exception {
    OrdinaryDestructibles.clock =
        Clock.fixed(Instant.parse("2026-10-22T03:00:00Z"), ZoneOffset.UTC);
    for (String file : List.of("ordinary-destructibles-4.6.json", "jarilo-destructibles-4.6.json")) {
      try (var input = getClass().getResourceAsStream("/fixtures/" + file)) {
        var source =
            JsonParser.parseString(new String(input.readAllBytes(), StandardCharsets.UTF_8))
                .getAsJsonObject();
        for (String section : List.of("records", "groups", "floors", "locations", "headers"))
          for (var row : source.getAsJsonObject(section).entrySet()) {
            var target = fixture.source.getAsJsonObject(section);
            if (section.equals("records") && target.has(row.getKey()))
              target.getAsJsonArray(row.getKey()).addAll(row.getValue().getAsJsonArray());
            else target.add(row.getKey(), row.getValue());
          }
      }
    }
    fixture.root = root;
    fixture.load();
    fresh();
  }

  void fresh() throws Exception {
    actor = new Actor();
    DailyMissionAcceptance46Test.set(actor, "uid", 123456806);
    DailyMissionAcceptance46Test.set(actor, "name", "box-fixture");
    DailyMissionAcceptance46Test.set(actor, "pos", new Position());
    actor.plane = 20001;
    actor.floor = 20001001;
    actor.entry = 2000101;
    fixture.player = actor;
    fixture.scene();
  }

  @AfterEach
  void cleanup() throws Exception {
    try {
      fixture.restore();
    } finally {
      OrdinaryDestructibles.clock = Clock.systemUTC();
      BookPickup46Test.database.getDatabase().drop();
    }
  }

  void reload() throws Exception {
    int plane = actor.plane, floor = actor.floor, entry = actor.entry;
    var stored = BookPickup46Test.database.getObjectByField(Player.class, "_id", actor.getUid());
    assertNotNull(stored);
    fresh();
    actor.plane = plane;
    actor.floor = floor;
    actor.entry = entry;
    DailyMissionAcceptance46Test.set(actor, "gameplay", stored.getGameplay());
    DailyMissionAcceptance46Test.set(actor, "scoin", stored.getScoin());
    for (var item : BookPickup46Test.database.getObjects(GameItem.class).toList())
      if (item.getOwnerUid() == actor.getUid()) assertTrue(actor.getInventory().loadItem(item));
    fixture.scene();
  }

  EntityProp box(int instance) {
    return box(36, instance);
  }

  EntityProp box(int group, int instance) {
    return actor.scene.getEntitiesByGroup(EntityProp.class, group).stream()
        .filter(p -> p.getInstId() == instance)
        .findFirst()
        .orElse(null);
  }

  static java.util.stream.Stream<Arguments> stationPlacements() throws Exception {
    try (var input = OrdinaryDestructible46Test.class.getResourceAsStream(
        "/fixtures/ordinary-destructibles-4.6.json")) {
      var rows = JsonParser.parseString(new String(input.readAllBytes(), StandardCharsets.UTF_8))
          .getAsJsonObject().getAsJsonArray("placements");
      assertEquals(53, rows.size(), "Frozen ordinary-space-station placement coverage changed");
      return rows.asList().stream().map(raw -> {
        var row = raw.getAsJsonObject();
        return Arguments.of(row.get("plane").getAsInt(), row.get("floor").getAsInt(),
            row.get("entry").getAsInt(), row.get("group").getAsInt(),
            row.get("instance").getAsInt(), row.get("prop").getAsInt(),
            row.get("event").getAsInt());
      });
    }
  }

  @ParameterizedTest(name = "station {0} group {3} instance {4}")
  @MethodSource("stationPlacements")
  void everyOriginalStationBoxAndCylinderAwardsAndSurvivesReload(
      int plane, int floor, int entry, int group, int instance, int propId, int eventId)
      throws Exception {
    assertOriginalDropAndReload(plane, floor, entry, group, instance, propId, eventId);
  }

  static java.util.stream.Stream<Arguments> jariloPlacements() throws Exception {
    try (var input = OrdinaryDestructible46Test.class.getResourceAsStream(
        "/fixtures/jarilo-destructibles-4.6.json")) {
      var rows = JsonParser.parseString(new String(input.readAllBytes(), StandardCharsets.UTF_8))
          .getAsJsonObject().getAsJsonArray("placements");
      assertEquals(89, rows.size(), "Frozen ordinary Jarilo-VI placement coverage changed");
      return rows.asList().stream().map(raw -> {
        var row = raw.getAsJsonObject();
        return Arguments.of(row.get("plane").getAsInt(), row.get("floor").getAsInt(),
            row.get("entry").getAsInt(), row.get("group").getAsInt(),
            row.get("instance").getAsInt(), row.get("prop").getAsInt(),
            row.get("event").getAsInt());
      });
    }
  }

  @ParameterizedTest(name = "Jarilo {0} group {3} instance {4}")
  @MethodSource("jariloPlacements")
  void everyOriginalJariloBarrelAwardsAndSurvivesReload(
      int plane, int floor, int entry, int group, int instance, int propId, int eventId)
      throws Exception {
    assertOriginalDropAndReload(plane, floor, entry, group, instance, propId, eventId);
  }

  void assertOriginalDropAndReload(
      int plane, int floor, int entry, int group, int instance, int propId, int eventId)
      throws Exception {
    actor.plane = plane;
    actor.floor = floor;
    actor.entry = entry;
    fixture.scene();
    var prop = box(group, instance);
    assertNotNull(prop, "Original ordinary prop was not loaded");
    assertEquals(propId, prop.getPropId());
    assertEquals(eventId, prop.getPropInfo().getEventID());
    actor.getPos().set(prop.getPos());
    assertTrue(actor.scene.destroyProp(prop));
    assertEquals(100, actor.getScoin(), "Destroyed an ordinary prop without its credit drop");
    assertEquals(1, actor.getInventory().getMaterialByItemId(180005).getCount());
    assertEquals(1, actor.packets.stream()
        .filter(p -> p.getCmdId() == CmdId.ScenePlaneEventScNotify).count());
    assertFalse(actor.scene.destroyProp(prop));
    reload();
    assertNull(box(group, instance), "Paid prop respawned on the same day");
    assertEquals(100, actor.getScoin());
    assertEquals(1, actor.getInventory().getMaterialByItemId(180005).getCount());
    assertTrue(actor.getGameplay().getDestructibleRewards().getReceipts()
        .get(plane + ":" + floor + ":" + group + ":" + instance).isGranted());
    assertEquals(0, actor.grants);
  }

  void backwaterPass() {
    actor.plane = 20111;
    actor.floor = 20111001;
    actor.entry = 201110102;
    fixture.scene();
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 1, 2, 3, 4, 5, 6})
  void backwaterPassStoryEntranceAndEveryWorldLevelUseTheRegionalPool(int world)
      throws Exception {
    DailyMissionAcceptance46Test.set(actor, "worldLevel", world);
    backwaterPass();
    assertTrue(actor.scene.destroyProp(box(6, 300001)));
    assertEquals(100, actor.getScoin());
    assertEquals(1, actor.getInventory().getMaterialByItemId(180005).getCount());
    reload();
    assertNull(box(6, 300001));
  }

  @ParameterizedTest
  @ValueSource(strings = {"pool", "station-pool", "stage", "reward", "unknown-event", "source"})
  void malformedJariloRewardEventsLeaveTheBarrelAndInventoryUntouched(String fault)
      throws Exception {
    backwaterPass();
    var prop = box(6, 300001);
    var event = GameData.getPlaneEventExcelMap().get(20111401, 0);
    switch (fault) {
      case "pool" -> DailyMissionAcceptance46Test.set(event, "DropList",
          JsonParser.parseString("[101002]").getAsJsonArray());
      case "station-pool" -> DailyMissionAcceptance46Test.set(event, "DropList",
          JsonParser.parseString("[2000102,101001]").getAsJsonArray());
      case "stage" -> DailyMissionAcceptance46Test.set(event, "StageID", 1);
      case "reward" -> DailyMissionAcceptance46Test.set(event, "Reward", 101002);
      case "unknown-event" -> DailyMissionAcceptance46Test.set(prop.getPropInfo(), "EventID", 99999999);
      case "source" -> {
        var copy = fixture.gson.fromJson(fixture.gson.toJson(prop.getPropInfo()),
            emu.lunarcore.data.config.PropInfo.class);
        DailyMissionAcceptance46Test.set(copy, "EventID", 20101401);
        prop.setPropInfo(copy);
      }
    }
    assertFalse(actor.scene.destroyProp(prop));
    assertSame(prop, box(6, 300001));
    assertEquals(0, actor.getScoin());
    assertEquals(0, actor.grants);
    assertEquals(0, actor.saves);
    assertTrue(actor.getGameplay().getDestructibleRewards().getReceipts().isEmpty());
  }

  @Test
  void jariloRecoveryPropsDoNotBecomeOrdinaryMaterialDrops() {
    backwaterPass();
    var recovery = box(6, 300004);
    assertNotNull(recovery);
    assertEquals(101016, recovery.getPropId());
    assertFalse(OrdinaryDestructibles.applies(recovery));
    assertTrue(actor.scene.destroyProp(recovery));
    assertEquals(0, actor.getScoin());
    assertEquals(0, actor.grants);
    assertTrue(actor.getGameplay().getDestructibleRewards().getReceipts().isEmpty());
  }

  @Test
  void jariloMissionBarrelKeepsItsOriginalPrerequisite() {
    actor.plane = 20131;
    actor.floor = 20131001;
    actor.entry = 2013101;
    fixture.scene();
    assertNotNull(box(4, 300001));
    assertFalse(actor.scene.getLoadedGroups().contains(82));
    assertNull(box(82, 300001));
    assertEquals(0, actor.grants);
  }

  @Test
  void jariloFullInventoryAndFailedSaveKeepTheBarrelForRetry() {
    backwaterPass();
    assertTrue(actor.getInventory().addItem(180005, 99999));
    var barrel = box(6, 300001);
    assertFalse(actor.scene.destroyProp(barrel));
    assertSame(barrel, box(6, 300001));
    actor.getInventory().getMaterialByItemId(180005).setCount(99998);
    actor.failSave = true;
    assertFalse(actor.scene.destroyProp(barrel));
    assertSame(barrel, box(6, 300001));
    assertEquals(0, actor.getScoin());
    actor.failSave = false;
    assertTrue(actor.scene.destroyProp(barrel));
    assertEquals(100, actor.getScoin());
    assertEquals(99999, actor.getInventory().getMaterialByItemId(180005).getCount());
  }

  @ParameterizedTest
  @ValueSource(ints = {300003, 300008})
  void destroyedWhiteBoxActuallyDeliversCreditsMaterialAndRewardHint(int instance) {
    var box = box(instance);
    assertNotNull(box);
    assertEquals(100026, box.getPropId());
    actor.getPos().set(box.getPos());
    int before = actor.getScoin();
    assertTrue(
        actor.scene.destroyProp(box),
        () ->
            "world="
                + actor.scene.getWorldId()
                + " saves="
                + actor.saves
                + " grants="
                + actor.grants
                + " prop="
                + fixture.gson.toJson(box.getPropInfo()));
    assertEquals(before + 100, actor.getScoin(), "The box disappeared without delivering credits");
    assertEquals(1, actor.getInventory().getMaterialByItemId(180005).getCount());
    assertTrue(actor.packets.stream().anyMatch(p -> p.getCmdId() == CmdId.ScenePlaneEventScNotify));
    assertFalse(actor.scene.destroyProp(box));
    assertEquals(before + 100, actor.getScoin());
  }

  @Test
  void independentBoxesPersistAfterTwoPlayerAndInventoryReloads() throws Exception {
    assertTrue(actor.scene.destroyProp(box(300003)));
    assertTrue(actor.scene.destroyProp(box(300008)));
    for (int i = 0; i < 2; i++) {
      reload();
      assertNull(box(300003));
      assertNull(box(300008));
      assertNotNull(box(300006));
      assertEquals(200, actor.getScoin());
      assertEquals(2, actor.getInventory().getMaterialByItemId(180005).getCount());
      assertEquals(2, actor.getGameplay().getDestructibleRewards().getReceipts().size());
      assertEquals(0, actor.grants);
    }
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 1, 2, 3, 4, 5, 6})
  void sourceWorldLevelsUseCompatibilityPolicyWithoutMistakingPoolForReward(int world)
      throws Exception {
    DailyMissionAcceptance46Test.set(actor, "worldLevel", world);
    assertEquals(10000, GameData.getRewardExcelMap().get(101001).getRewards().get(2));
    assertTrue(actor.scene.destroyProp(box(300003)));
    assertEquals(100, actor.getScoin());
    assertEquals(
        Set.of(180005),
        actor.getInventory().getItems().values().stream()
            .map(GameItem::getItemId)
            .collect(java.util.stream.Collectors.toSet()));
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 1, 2, 3, 4, 5, 6})
  void allStationEventRowsAtEveryWorldLevelShareTheVerifiedPoolPolicy(int world)
      throws Exception {
    DailyMissionAcceptance46Test.set(actor, "worldLevel", world);
    for (int plane : new int[] {20002, 20003, 20004}) {
      actor.plane = plane;
      actor.floor = plane * 1000 + 1;
      actor.entry = plane * 100 + 1;
      fixture.scene();
      var prop = actor.scene.getEntities().values().stream()
          .filter(e -> e instanceof EntityProp p && p.getPropId() == 100026)
          .map(e -> (EntityProp) e).findFirst().orElseThrow();
      assertTrue(actor.scene.destroyProp(prop));
    }
    assertEquals(300, actor.getScoin());
    assertEquals(3, actor.getInventory().getMaterialByItemId(180005).getCount());
    assertEquals(3, actor.getGameplay().getDestructibleRewards().getReceipts().size());
    reload();
    assertEquals(300, actor.getScoin());
    assertEquals(3, actor.getInventory().getMaterialByItemId(180005).getCount());
  }

  @ParameterizedTest
  @ValueSource(strings = {"pool", "stage", "reward", "unknown-event"})
  void incompatibleStationEventsCannotSilentlyDestroyTheProp(String fault) throws Exception {
    actor.plane = 20002;
    actor.floor = 20002001;
    actor.entry = 2000201;
    fixture.scene();
    var prop = box(12, 300004);
    var event = GameData.getPlaneEventExcelMap().get(20002401, 0);
    switch (fault) {
      case "pool" -> DailyMissionAcceptance46Test.set(event, "DropList",
          JsonParser.parseString("[3213004]").getAsJsonArray());
      case "stage" -> DailyMissionAcceptance46Test.set(event, "StageID", 1);
      case "reward" -> DailyMissionAcceptance46Test.set(event, "Reward", 101001);
      case "unknown-event" -> DailyMissionAcceptance46Test.set(prop.getPropInfo(), "EventID", 99999999);
    }
    assertFalse(actor.scene.destroyProp(prop));
    assertSame(prop, box(12, 300004));
    assertEquals(0, actor.grants);
    assertEquals(0, actor.saves);
    assertTrue(actor.getGameplay().getDestructibleRewards().getReceipts().isEmpty());
  }

  @Test
  void refreshedSceneOnlyRespawnsPaidBoxesAfterTheNextFourAmBoundary() throws Exception {
    OrdinaryDestructibles.clock =
        Clock.fixed(Instant.parse("2026-10-22T19:59:59Z"), ZoneOffset.UTC);
    assertTrue(actor.scene.destroyProp(box(300003)));
    reload();
    assertNull(box(300003));
    OrdinaryDestructibles.clock =
        Clock.fixed(Instant.parse("2026-10-22T20:00:00Z"), ZoneOffset.UTC);
    fixture.scene();
    assertNotNull(box(300003));
    assertTrue(actor.scene.destroyProp(box(300003)));
    reload();
    assertNull(box(300003));
    assertEquals(200, actor.getScoin());
    OrdinaryDestructibles.clock =
        Clock.fixed(Instant.parse("2026-10-21T20:00:00Z"), ZoneOffset.UTC);
    fixture.scene();
    assertNull(box(300003));
  }

  @Test
  void fullMaterialStackLeavesBoxAndCurrencyUntouchedThenCanRetry() {
    assertTrue(actor.getInventory().addItem(180005, 99999));
    var box = box(300003);
    assertFalse(actor.scene.destroyProp(box));
    assertNotNull(box(300003));
    assertEquals(0, actor.saves);
    assertEquals(0, actor.getScoin());
    actor.getInventory().getMaterialByItemId(180005).setCount(99998);
    assertTrue(actor.scene.destroyProp(box));
    assertEquals(100, actor.getScoin());
    assertEquals(99999, actor.getInventory().getMaterialByItemId(180005).getCount());
  }

  @Test
  void reservationSaveFailureCanRetryWithoutLoss() {
    actor.failSave = true;
    assertFalse(actor.scene.destroyProp(box(300003)));
    assertEquals(0, actor.grants);
    assertTrue(actor.getGameplay().getDestructibleRewards().getReceipts().isEmpty());
    actor.failSave = false;
    assertTrue(actor.scene.destroyProp(box(300003)));
    assertEquals(100, actor.getScoin());
  }

  @ParameterizedTest
  @ValueSource(strings = {"grant", "complete"})
  void unfinishedDeliveryBlocksReplayEvenAfterReloadAndNextDay(String fault) throws Exception {
    actor.failGrant = fault.equals("grant");
    actor.failOnSave = fault.equals("complete") ? 2 : -1;
    assertFalse(actor.scene.destroyProp(box(300003)));
    assertNotNull(box(300003));
    assertFalse(
        actor
            .getGameplay()
            .getDestructibleRewards()
            .getReceipts()
            .values()
            .iterator()
            .next()
            .isGranted());
    reload();
    OrdinaryDestructibles.clock =
        Clock.fixed(Instant.parse("2026-10-25T03:00:00Z"), ZoneOffset.UTC);
    fixture.scene();
    assertFalse(actor.scene.destroyProp(box(300003)));
    assertFalse(actor.scene.destroyProp(box(300008)));
    assertEquals(0, actor.grants);
  }

  @ParameterizedTest
  @ValueSource(strings = {"source", "pool", "stage", "unloaded", "item", "credits", "state"})
  void invalidSourceOrCapacityCannotDestroyOrAward(String fault) throws Exception {
    var box = box(300003);
    switch (fault) {
      case "source" -> {
        var changed =
            fixture.gson.fromJson(
                fixture.gson.toJson(box.getPropInfo()), emu.lunarcore.data.config.PropInfo.class);
        DailyMissionAcceptance46Test.set(changed, "EventID", 20001402);
        box.setPropInfo(changed);
      }
      case "pool" ->
          DailyMissionAcceptance46Test.set(
              GameData.getPlaneEventExcelMap().get(20001401, 0),
              "DropList",
              JsonParser.parseString("[101001]").getAsJsonArray());
      case "stage" ->
          DailyMissionAcceptance46Test.set(
              GameData.getPlaneEventExcelMap().get(20001401, 0), "StageID", 1);
      case "unloaded" -> actor.scene.getLoadedGroups().remove(36);
      case "item" -> GameData.getItemExcelMap().keySet().remove(180005);
      case "credits" -> DailyMissionAcceptance46Test.set(actor, "scoin", 999999999);
      case "state" ->
          DailyMissionAcceptance46Test.set(actor.getGameplay(), "destructibleRewards", null);
    }
    assertFalse(actor.scene.destroyProp(box));
    assertEquals(0, actor.grants);
    assertEquals(0, actor.saves);
    assertNotNull(box(300003));
  }

  @Test
  void concurrentHitsAwardOnceAndAdministrativeRemovalAwardsNothing() throws Exception {
    var box = box(300003);
    try (var pool = Executors.newFixedThreadPool(2)) {
      var futures =
          pool.invokeAll(
              List.<Callable<Boolean>>of(
                  () -> actor.scene.destroyProp(box), () -> actor.scene.destroyProp(box)));
      assertEquals(
          1,
          futures.stream()
              .filter(
                  f -> {
                    try {
                      return f.get();
                    } catch (Exception e) {
                      throw new AssertionError(e);
                    }
                  })
              .count());
    }
    actor.scene.removeEntity(box(300008));
    assertEquals(100, actor.getScoin());
    assertEquals(1, actor.getInventory().getMaterialByItemId(180005).getCount());
    assertEquals(1, actor.grants);
  }
}
