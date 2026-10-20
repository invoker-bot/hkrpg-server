package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import emu.lunarcore.game.enums.PropState;
import emu.lunarcore.game.quest.QuestState;
import emu.lunarcore.game.scene.entity.EntityProp;
import emu.lunarcore.server.packet.Retcode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Original Base Zone chests, with only prerequisite player history supplied by the fixture. */
class BaseZoneChest46Test {
  @TempDir Path root;
  final WorldChestActivity46Test fixture = new WorldChestActivity46Test();

  static class StationPlayer extends WorldChestActivity46Test.Actor {
    public int getWorldId() {
      return plane == 20001 ? 101 : 501;
    }
  }

  @BeforeEach
  void load() throws Exception {
    var extra =
        JsonParser.parseReader(
                new java.io.InputStreamReader(
                    getClass().getResourceAsStream("/fixtures/base-zone-chests-4.6.json"),
                    StandardCharsets.UTF_8))
            .getAsJsonObject();
    for (String section : new String[] {"records", "groups", "floors", "locations"})
      for (var row : extra.getAsJsonObject(section).entrySet()) {
        var target = fixture.source.getAsJsonObject(section);
        if (section.equals("records") && target.has(row.getKey()))
          target.getAsJsonArray(row.getKey()).addAll(row.getValue().getAsJsonArray());
        else target.add(row.getKey(), row.getValue());
      }
    fixture.root = root;
    fixture.load();
    fixture.player = new StationPlayer();
    fixture.player.setLevel(1);
    finish(1000401);
    enter(20001, 20001001, 2000101);
  }

  @AfterEach
  void restore() throws Exception {
    fixture.restore();
  }

  void finish(int id) {
    var state = new QuestState(id);
    state.advance(1, 1, 1);
    fixture.player.getGameplay().getMainMissions().put(id, state);
  }

  void enter(int plane, int floor, int entry) {
    fixture.player.plane = plane;
    fixture.player.floor = floor;
    fixture.player.entry = entry;
    fixture.scene();
  }

  EntityProp chest(int id) {
    return fixture.chest(id);
  }

  Retcode open(int id) {
    return WorldChests.open(fixture.player, 112131, chest(id).getEntityId()).retcode();
  }

  @Test
  void originalResourcePinsAndLoadedChestCatalogMatch() {
    for (var pin : BaseZoneChestSources.ROWS.entrySet()) {
      var key = pin.getKey().split(":");
      var rows =
          fixture
              .source
              .getAsJsonObject("records")
              .getAsJsonArray("ExcelOutput/" + key[0] + ".json");
      String field = WorldChestSources.KEYS.get(key[0]);
      var row =
          rows.asList().stream()
              .map(JsonElement::getAsJsonObject)
              .filter(r -> r.get(field).getAsInt() == Integer.parseInt(key[1]))
              .findFirst()
              .orElseThrow();
      assertEquals(pin.getValue(), TimelineActivityRules.fingerprint(row), pin.getKey());
    }
    for (var pin : BaseZoneChestSources.FILES.entrySet())
      assertEquals(
          pin.getValue(),
          TimelineActivityRules.fingerprint(
              fixture.source.getAsJsonObject("groups").get(pin.getKey())),
          pin.getKey());
    var rules = WorldChestRules.load();
    assertNotNull(rules, "Paired loaded catalog");
    assertTrue(rules.locations.keySet().containsAll(BaseZoneChestSources.LOCATIONS.keySet()));
    assertNotNull(rules.loot(20001604));
    assertNotNull(rules.original(20001604, 0));
    assertTrue(WorldChests.applies(chest(20001604)));
  }

  @ParameterizedTest
  @ValueSource(ints = {20001601, 20001602, 20001603, 20001604, 20001605, 20001606})
  void originalBaseZoneChestPaysOnceAndRemainsUsedAfterReload(int id) {
    var player = fixture.player;
    assertEquals(
        Retcode.SUCC, SceneInteractions.validateNative(player, 112131, chest(id).getEntityId()));
    assertEquals(Retcode.SUCC, open(id));
    assertEquals(1, player.grants);
    var record = player.getGameplay().getWorldChests().getRecords().get(id);
    assertEquals(Map.of(1, 5, 2, 500, 22, 5, 120000, 15, 212, 3, 232, 1), record.getLoot());
    assertEquals(WorldChestState.Phase.OPEN, record.getPhase());
    assertTrue(record.getAcceptances().isEmpty());
    assertEquals(PropState.ChestUsed, chest(id).getState());
    assertNotEquals(Retcode.SUCC, open(id));
    player
        .getGameplay()
        .worldChests(
            fixture.gson.fromJson(
                fixture.gson.toJson(player.getGameplay().getWorldChests()), WorldChestState.class));
    fixture.scene();
    assertEquals(PropState.ChestUsed, chest(id).getState());
    assertNotEquals(Retcode.SUCC, open(id));
    assertEquals(1, player.grants);
  }

  @Test
  void openingPlayerCannotLoadOrClaimTheseLaterExplorationChests() {
    fixture.player.getGameplay().getMainMissions().remove(1000401);
    fixture.scene();
    assertFalse(fixture.player.scene.getLoadedGroups().contains(6));
    fixture.player.scene.loadGroup(6);
    assertTrue(fixture.player.scene.getEntitiesByGroup(EntityProp.class, 6).isEmpty());
    assertEquals(0, fixture.player.grants);
  }

  @Test
  void staleLoadedActorCannotBypassRevokedMissionGate() {
    int entity = chest(20001604).getEntityId();
    fixture.player.getGameplay().getMainMissions().remove(1000401);
    assertNotEquals(Retcode.SUCC, WorldChests.open(fixture.player, 112131, entity).retcode());
    assertTrue(fixture.player.getGameplay().getWorldChests().getRecords().isEmpty());
    assertEquals(0, fixture.player.grants);
  }

  @Test
  void baseZoneLootDoesNotCountTowardAnotherWorldsActivity() {
    fixture.player.setLevel(21);
    finish(1000510);
    fixture.player.getQuestManager().refreshUnlocks();
    assertNotNull(fixture.state(6029850));
    assertEquals(Retcode.SUCC, open(20001604));
    for (int goal : new int[] {6029850, 6029851, 6029852}) assertEquals(0, fixture.progress(goal));
    enter(20411, 20411001, 2041101);
    assertEquals(Retcode.SUCC, open(20411601));
    for (int goal : new int[] {6029850, 6029851, 6029852}) assertEquals(1, fixture.progress(goal));
    assertEquals(2, fixture.player.grants);
  }

  @Test
  void failedReservationCanRetryWithoutConsumingTheChest() {
    fixture.player.failSave = true;
    assertEquals(Retcode.SYSTEM_BUSY, open(20001604));
    assertEquals(0, fixture.player.grants);
    assertTrue(fixture.player.getGameplay().getWorldChests().getRecords().isEmpty());
    assertEquals(PropState.ChestClosed, chest(20001604).getState());
    fixture.player.failSave = false;
    assertEquals(Retcode.SUCC, open(20001604));
    assertEquals(1, fixture.player.grants);
  }

  @Test
  void interruptedGrantCannotPublishOrRepeatLootFromAnotherChest() {
    fixture.player.failGrant = true;
    assertEquals(Retcode.PLAYER_DATA_ERROR, open(20001604));
    assertEquals(PropState.ChestClosed, chest(20001604).getState());
    fixture.player.failGrant = false;
    assertEquals(Retcode.SYSTEM_BUSY, open(20001605));
    assertEquals(1, fixture.player.grants);
  }

  @Test
  void failedFinalSaveCannotPublishOrRepeatLoot() {
    fixture.player.failOnSave = fixture.player.saves + 2;
    assertEquals(Retcode.SYSTEM_BUSY, open(20001604));
    assertEquals(PropState.ChestClosed, chest(20001604).getState());
    assertEquals(Retcode.SYSTEM_BUSY, open(20001604));
    assertEquals(1, fixture.player.grants);
  }
}
