package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import emu.lunarcore.data.GameData;
import emu.lunarcore.game.enums.PropState;
import emu.lunarcore.game.scene.entity.EntityProp;
import emu.lunarcore.server.packet.Retcode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Complete original groups and floors; player placement and prerequisite history are fixtures. */
class MapChest46Test {
  @TempDir Path root;
  final WorldChestActivity46Test fixture = new WorldChestActivity46Test();

  static class MapPlayer extends WorldChestActivity46Test.Actor {
    @Override
    public int getWorldId() {
      return GameData.getMazePlaneExcelMap().get(plane).getWorldID();
    }
  }

  void merge(String name) {
    var extra =
        JsonParser.parseReader(
                new java.io.InputStreamReader(
                    Objects.requireNonNull(getClass().getResourceAsStream("/fixtures/" + name)),
                    StandardCharsets.UTF_8))
            .getAsJsonObject();
    for (String section : List.of("records", "groups", "floors", "locations")) {
      if (!extra.has(section)) continue;
      for (var entry : extra.getAsJsonObject(section).entrySet()) {
        var target = fixture.source.getAsJsonObject(section);
        if (!section.equals("records") || !target.has(entry.getKey())) {
          target.add(entry.getKey(), entry.getValue());
          continue;
        }
        String table = Path.of(entry.getKey()).getFileName().toString().replace(".json", "");
        String id = WorldChestSources.KEYS.getOrDefault(table, "ID");
        var rows = new LinkedHashMap<Integer, JsonElement>();
        for (var row : target.getAsJsonArray(entry.getKey()))
          rows.put(row.getAsJsonObject().get(id).getAsInt(), row);
        for (var row : entry.getValue().getAsJsonArray())
          rows.put(row.getAsJsonObject().get(id).getAsInt(), row);
        var merged = new JsonArray();
        rows.values().forEach(merged::add);
        target.add(entry.getKey(), merged);
      }
    }
  }

  @BeforeEach
  void load() throws Exception {
    merge("base-zone-chests-4.6.json");
    merge("map-chests-4.6.json");
    fixture.root = root;
    fixture.load();
    fixture.player = new MapPlayer();
    fixture.player.setLevel(1);
    enter(20001607);
  }

  @AfterEach
  void restore() throws Exception {
    fixture.restore();
  }

  void enter(int id) {
    var row = fixture.source.getAsJsonObject("locations").getAsJsonObject(String.valueOf(id));
    fixture.player.plane = row.get("plane").getAsInt();
    fixture.player.floor = row.get("floor").getAsInt();
    fixture.player.entry = row.get("entry").getAsInt();
    fixture.scene();
  }

  EntityProp chest(int id) {
    return fixture.chest(id);
  }

  Retcode open(int id) {
    return WorldChests.open(fixture.player, 112131, chest(id).getEntityId()).retcode();
  }

  @ParameterizedTest
  @ValueSource(ints = {20001607, 10101601, 10201601, 10301601, 10401601, 10501601, 10551601})
  void originalChestAcrossSevenWorldsOpensOnceAndRestores(int id) {
    enter(id);
    assertEquals(PropState.ChestClosed, chest(id).getState());
    assertEquals(
        Retcode.SUCC,
        SceneInteractions.validateNative(fixture.player, 112131, chest(id).getEntityId()));
    assertEquals(Retcode.SUCC, open(id));
    assertEquals(1, fixture.player.grants);
    var record = fixture.player.getGameplay().getWorldChests().getRecords().get(id);
    assertNotNull(record);
    assertEquals(WorldChestState.Phase.OPEN, record.getPhase());
    assertEquals(5, record.getLoot().get(1));
    int material =
        switch (fixture.player.getWorldId()) {
          case 101 -> 120000;
          case 201 -> 120001;
          case 301 -> 120002;
          case 401 -> 120003;
          case 501 -> 120004;
          case 601 -> 120005;
          case 602 -> 120006;
          default -> throw new AssertionError("Unexpected source world");
        };
    assertEquals(15, record.getLoot().get(material));
    fixture
        .player
        .getGameplay()
        .worldChests(
            fixture.gson.fromJson(
                fixture.gson.toJson(fixture.player.getGameplay().getWorldChests()),
                WorldChestState.class));
    fixture.scene();
    assertEquals(PropState.ChestUsed, chest(id).getState());
    assertNotEquals(Retcode.SUCC, open(id));
    assertEquals(1, fixture.player.grants);
  }

  @Test
  void openingChestDoesNotRequireTheLaterExplorationMission() {
    assertFalse(fixture.player.getGameplay().getMainMissions().containsKey(1000401));
    assertTrue(fixture.player.scene.getLoadedGroups().contains(36));
    assertFalse(fixture.player.scene.getLoadedGroups().contains(6));
    assertEquals(Retcode.SUCC, open(20001607));
    assertFalse(fixture.player.getGameplay().getMainMissions().containsKey(1000401));
  }

  @Test
  void lockedChallengeChestStillRequiresItsWorldUnlock() {
    assertEquals(PropState.ChestLocked, chest(20001632).getState());
    assertNotEquals(Retcode.SUCC, open(20001632));
    assertEquals(0, fixture.player.grants);
    assertTrue(fixture.player.getGameplay().getWorldChests().getRecords().isEmpty());
  }

  @Test
  void reservationFailureLeavesChestRetryable() {
    fixture.player.failSave = true;
    assertEquals(Retcode.SYSTEM_BUSY, open(20001607));
    assertEquals(0, fixture.player.grants);
    assertTrue(fixture.player.getGameplay().getWorldChests().getRecords().isEmpty());
    fixture.player.failSave = false;
    assertEquals(Retcode.SUCC, open(20001607));
  }

  @Test
  void interruptedDeliveryCannotBeRetriedOnAnotherMap() {
    fixture.player.failGrant = true;
    assertEquals(Retcode.PLAYER_DATA_ERROR, open(20001607));
    fixture.player.failGrant = false;
    enter(10101601);
    assertEquals(Retcode.SYSTEM_BUSY, open(10101601));
    assertEquals(1, fixture.player.grants);
  }

  @Test
  void finalSaveFailureCannotPublishOrRepeatTheReward() {
    fixture.player.failOnSave = fixture.player.saves + 2;
    assertEquals(Retcode.SYSTEM_BUSY, open(20001607));
    assertEquals(PropState.ChestClosed, chest(20001607).getState());
    fixture.player.failOnSave = -1;
    assertEquals(Retcode.SYSTEM_BUSY, open(20001607));
    enter(10101601);
    assertEquals(Retcode.SYSTEM_BUSY, open(10101601));
    assertEquals(1, fixture.player.grants);
  }

  @Test
  void changedGroupWithSameSizeAndTimestampCannotUseCachedSource() throws Exception {
    assertEquals(
        Retcode.SUCC,
        SceneInteractions.validateNative(fixture.player, 112131, chest(20001607).getEntityId()));
    var file = root.resolve(MapChestSources.LOCATIONS.get(20001607).path());
    var timestamp = Files.getLastModifiedTime(file);
    var original = Files.readString(file);
    var changed = original.replace("300002", "300003");
    assertNotEquals(original, changed);
    assertEquals(original.length(), changed.length());
    Files.writeString(file, changed);
    Files.setLastModifiedTime(file, timestamp);
    assertNotEquals(Retcode.SUCC, open(20001607));
    assertEquals(0, fixture.player.grants);
    Files.writeString(file, original);
    assertEquals(Retcode.SUCC, open(20001607));
  }

  @Test
  void missingWorldCurrencyCannotConsumeTheChest() {
    enter(10551601);
    var item = GameData.getItemExcelMap().get(120006);
    GameData.getItemExcelMap().values().remove(item);
    assertNotEquals(Retcode.SUCC, open(10551601));
    assertEquals(0, fixture.player.grants);
    assertTrue(fixture.player.getGameplay().getWorldChests().getRecords().isEmpty());
    GameData.getItemExcelMap().add(item);
    assertEquals(Retcode.SUCC, open(10551601));
  }

  @Test
  void aChestEntityFromThePreviousMapCannotBeClaimed() {
    var original = chest(20001607);
    enter(10101601);
    assertNotEquals(
        Retcode.SUCC, WorldChests.open(fixture.player, 112131, original.getEntityId()).retcode());
    assertEquals(0, fixture.player.grants);
    assertEquals(Retcode.SUCC, open(10101601));
  }

  @Test
  void hiddenAndLockedChestsCannotBeForcedOpenByTheInteractionRequest() {
    for (var state : List.of(PropState.Hidden, PropState.ChestLocked)) {
      chest(20001607).setState(state, false);
      assertNotEquals(Retcode.SUCC, open(20001607));
    }
    assertEquals(0, fixture.player.grants);
    chest(20001607).setState(PropState.ChestClosed, false);
    assertEquals(Retcode.SUCC, open(20001607));
  }

  @Test
  void newWorldFiveChestCreditsOnlyItsAcceptedActivityOnce() {
    fixture.player.setLevel(21);
    var quest = new emu.lunarcore.game.quest.QuestState(1000510);
    quest.advance(1, 1, 1);
    quest.reserveReward();
    quest.closeReward();
    fixture.player.getGameplay().getMainMissions().put(1000510, quest);
    fixture.player.getQuestManager().refreshUnlocks();
    assertNotNull(fixture.state(6029850));
    assertEquals(Retcode.SUCC, open(20001607));
    for (int id : new int[] {6029850, 6029851, 6029852}) assertEquals(0, fixture.progress(id));
    enter(10401601);
    assertEquals(Retcode.SUCC, open(10401601));
    assertNotEquals(Retcode.SUCC, open(10401601));
    for (int id : new int[] {6029850, 6029851, 6029852}) assertEquals(1, fixture.progress(id));
    assertEquals(2, fixture.player.grants);
  }
}
