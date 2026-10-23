package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import emu.lunarcore.data.*;
import emu.lunarcore.data.config.*;
import emu.lunarcore.data.excel.*;
import emu.lunarcore.game.enums.*;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.game.player.lineup.PlayerLineup;
import emu.lunarcore.game.scene.Scene;
import emu.lunarcore.game.scene.entity.EntityProp;
import emu.lunarcore.server.packet.*;
import emu.lunarcore.util.Position;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Original containment-zone placement; saves/lineup are explicit unit-test substitutes. */
class SpaceAnchorActivationTest {
  static final int PLANE = 20002, FLOOR = 20002001, ENTRY = 2000201, MAPPING = 2000204;
  final Gson gson = ResourceLoader.getGson();
  JsonObject raw;
  FloorInfo floor, oldFloor;
  MazePlaneExcel oldPlane;
  MapEntranceExcel oldEntry;
  PropExcel oldProp;
  Actor player;

  static class Actor extends Player {
    Scene current;
    Position position = new Position(-66242, -3195, -69229);
    PlayerLineup team = new PlayerLineup(this, 0) {
      public void heal(int amount, boolean revive) {}
    };
    int saves;
    boolean fail;
    List<BasePacket> packets = new ArrayList<>();
    public Scene getScene() { return current; }
    public int getPlaneId() { return PLANE; }
    public int getFloorId() { return FLOOR; }
    public int getEntryId() { return ENTRY; }
    public Position getPos() { return position; }
    public Position getRot() { return new Position(); }
    public PlayerLineup getCurrentLineup() { return team; }
    public void save() { if (fail) throw new IllegalStateException("injected save failure"); saves++; }
    public void sendPacket(BasePacket packet) {
      assertTrue(saves > 0, "Activation must be durable before its notification");
      packets.add(packet);
    }
  }

  @BeforeEach void setup() throws Exception {
    oldFloor = GameData.getFloorInfo(PLANE, FLOOR);
    oldPlane = GameData.getMazePlaneExcelMap().get(PLANE);
    oldEntry = GameData.getMapEntranceExcelMap().get(ENTRY);
    oldProp = GameData.getPropExcelMap().get(101);
    JsonObject fixture;
    try (var reader = new InputStreamReader(Objects.requireNonNull(getClass().getResourceAsStream(
        "/fixtures/space-anchors-4.6.json")), StandardCharsets.UTF_8)) {
      fixture = JsonParser.parseReader(reader).getAsJsonObject();
    }
    raw = fixture.getAsJsonArray("sources").get(0).getAsJsonObject().getAsJsonObject("original").deepCopy();
    var prop = gson.fromJson(fixture.get("prop"), PropExcel.class);
    prop.onLoad();
    GameData.getPropExcelMap().add(prop);
    GameData.getMazePlaneExcelMap().add(gson.fromJson(
        "{\"PlaneID\":20002,\"PlaneType\":\"Maze\"}", MazePlaneExcel.class));
    GameData.getMapEntranceExcelMap().add(gson.fromJson(
        "{\"ID\":2000201,\"PlaneID\":20002,\"FloorID\":20002001,\"StartGroupID\":1,\"StartAnchorID\":4}", MapEntranceExcel.class));
    load();
  }

  void load() throws Exception {
    floor = gson.fromJson("{\"FloorID\":20002001}", FloorInfo.class);
    var group = gson.fromJson(raw, GroupInfo.class);
    group.setId(1);
    SceneAnchors.loadTriggers(group, raw);
    floor.getGroups().put(1, group);
    floor.onLoad();
    GameData.getFloorInfos().put("P20002_F20002001", floor);
    player = new Actor();
    reload();
  }

  void reload() throws Exception {
    player.current = new Scene(player, GameData.getMazePlaneExcelMap().get(PLANE), FLOOR);
    player.current.setEntryId(ENTRY);
    var loaded = Scene.class.getDeclaredField("loaded");
    loaded.setAccessible(true);
    loaded.set(player.current, true);
  }

  PropInfo definition() { return floor.getCachedTeleports().get(MAPPING); }
  EntityProp anchor() { return player.current.getEntitiesByGroup(EntityProp.class, 1).stream()
      .filter(p -> p.getInstId() == 300007).findFirst().orElseThrow(); }
  boolean unlocked() { return SceneAnchors.unlocked(player, GameData.getMapEntranceExcelMap().get(ENTRY), definition()); }

  @AfterEach void cleanup() {
    if (oldFloor == null) GameData.getFloorInfos().remove("P20002_F20002001");
    else GameData.getFloorInfos().put("P20002_F20002001", oldFloor);
    if (oldPlane == null) GameData.getMazePlaneExcelMap().keySet().remove(PLANE); else GameData.getMazePlaneExcelMap().add(oldPlane);
    if (oldEntry == null) GameData.getMapEntranceExcelMap().keySet().remove(ENTRY); else GameData.getMapEntranceExcelMap().add(oldEntry);
    if (oldProp == null) GameData.getPropExcelMap().keySet().remove(101); else GameData.getPropExcelMap().add(oldProp);
  }

  @Test void walkingToTheReportedAnchorActivatesOnceAndRestoresWithoutChangingTheResource() throws Exception {
    assertFalse(unlocked());
    player.onMove();
    assertEquals(PropState.CheckPointEnable, anchor().getState());
    assertTrue(unlocked());
    assertEquals(1, player.saves);
    assertEquals(1, player.packets.size());
    assertEquals(CmdId.SceneGroupRefreshScNotify, player.packets.getFirst().getCmdId());
    player.onMove();
    assertEquals(1, player.saves);
    assertEquals(1, player.packets.size());
    assertEquals(PropState.CheckPointDisable, definition().getState());
    assertFalse(SceneAnchors.unlocked(player, GameData.getMapEntranceExcelMap().get(ENTRY), floor.getCachedTeleports().get(2000202)));
    var field = Player.class.getDeclaredField("gameplay"); field.setAccessible(true);
    field.set(player, gson.fromJson(gson.toJson(player.getGameplay()), GameplayState.class));
    reload();
    assertEquals(PropState.CheckPointEnable, anchor().getState());
    assertTrue(unlocked());
  }

  @Test void mapUnlockQueriesUseTheSamePersistedStateAsSceneRestoration() throws Exception {
    var row = new ScenePropState.Snapshot(PLANE, FLOOR, 0, 0, 1, 300007, 101, 8,
        PropTimelines.source(definition()), "");
    player.getGameplay().scenePropStates(player.getGameplay().getScenePropStates().with(List.of(row)));
    reload();
    assertEquals(PropState.CheckPointEnable, anchor().getState());
    assertTrue(unlocked(), "Map queries must agree with the actual restored anchor");
  }

  @Test void failedPersistenceLeavesAnchorLockedAndTheNextMovementCanRetry() {
    player.fail = true;
    assertDoesNotThrow(player::onMove);
    assertFalse(unlocked());
    assertEquals(PropState.CheckPointDisable, anchor().getState());
    assertTrue(player.packets.isEmpty());
    assertTrue(player.getGameplay().getScenePropStates().getRecords().isEmpty());
    player.fail = false;
    player.onMove();
    assertTrue(unlocked());
    assertEquals(1, player.saves);
  }

  JsonObject rawAnchor() {
    return raw.getAsJsonArray("PropList").asList().stream().map(JsonElement::getAsJsonObject)
        .filter(p -> p.get("ID").getAsInt() == 300007).findFirst().orElseThrow();
  }

  @Test void authoredSixMetreTriggerWorksOutsideTheOldFiveMetreHealingRadius() {
    player.position.set(-62350, -2195, -67300); // 5.5m from the original trigger centre.
    player.onMove();
    assertTrue(unlocked());
  }

  @ParameterizedTest @ValueSource(strings = {"far", "different-storey", "hidden", "removed", "unloaded"})
  void movementDoesNotUnlockUnreachableOrInactiveAnchors(String reason) throws Exception {
    switch (reason) {
      case "far" -> player.position.set(-59850, -2195, -67300);
      case "different-storey" -> player.position.set(-67850, 5000, -67300);
      case "hidden" -> {
        rawAnchor().addProperty("State", "Hidden");
        load();
      }
      case "removed" -> player.current.getEntities().remove(anchor().getEntityId());
      case "unloaded" -> {
        var field = Scene.class.getDeclaredField("loaded"); field.setAccessible(true);
        field.set(player.current, false);
      }
    }
    player.onMove();
    assertFalse(unlocked());
    assertEquals(0, player.saves);
    assertTrue(player.packets.isEmpty());
  }

  @ParameterizedTest @ValueSource(strings = {
      "missing", "client", "box", "polygon", "negative", "text-radius", "unknown", "tilted"})
  void unsupportedOrMissingAuthoredTriggerNeverBecomesAGlobalUnlock(String reason) throws Exception {
    var trigger = rawAnchor().getAsJsonObject("CustomTriggerMap").getAsJsonObject("CustomTrigger_Spring");
    switch (reason) {
      case "missing" -> rawAnchor().remove("CustomTriggerMap");
      case "client" -> trigger.addProperty("Server", false);
      case "box" -> trigger.addProperty("Shape", "Box");
      case "polygon" -> trigger.getAsJsonArray("PointList").add(new JsonObject());
      case "negative" -> trigger.addProperty("Radius", -6);
      case "text-radius" -> trigger.addProperty("Radius", "6");
      case "unknown" -> trigger.addProperty("UnknownGate", 1);
      case "tilted" -> rawAnchor().addProperty("RotX", 90);
    }
    load();
    player.onMove();
    assertFalse(unlocked());
    assertEquals(0, player.saves);
  }

  @Test void triggerBindingDoesNotChangeExistingPropSourceFingerprints() {
    var clean = gson.fromJson(raw, GroupInfo.class);
    String before = gson.toJson(clean);
    SceneAnchors.loadTriggers(clean, raw);
    assertEquals(before, gson.toJson(clean));
    assertNotNull(clean.getPropList().stream().filter(p -> p.getID() == 300007).findFirst().orElseThrow().getSpringActivation());
  }

  @Test void mismatchedSavedSourceDoesNotAuthorizeTeleport() throws Exception {
    player.getGameplay().scenePropStates(player.getGameplay().getScenePropStates().with(List.of(
        new ScenePropState.Snapshot(PLANE, FLOOR, 0, 0, 1, 300007, 101, 8, "0".repeat(64), ""))));
    reload();
    assertEquals(PropState.CheckPointDisable, anchor().getState());
    assertFalse(unlocked());
  }

  @Test void storyActivationAndProximityShareOneRestorableState() throws Exception {
    var entry = GameData.getMapEntranceExcelMap().get(ENTRY);
    player.getGameplay().scenePropStates(player.getGameplay().getScenePropStates().with(List.of(
        new ScenePropState.Snapshot(PLANE, FLOOR, 0, 0, 1, 300007, 101, 7, PropTimelines.source(definition()), ""))));
    player.saves = 1; // StoryActions owns save ordering for this separate API.
    assertTrue(SceneAnchors.activate(player, ENTRY, 1, 300007));
    reload();
    assertEquals(PropState.CheckPointEnable, anchor().getState());
    assertTrue(SceneAnchors.unlocked(player, entry, definition()));
  }
}

