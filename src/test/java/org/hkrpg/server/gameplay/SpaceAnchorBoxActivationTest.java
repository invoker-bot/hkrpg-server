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

/** The original base-zone checkpoint and a player at the reported position. */
class SpaceAnchorBoxActivationTest {
  static final int PLANE = 20001, FLOOR = 20001001, ENTRY = 2000101, GROUP = 18, INSTANCE = 300002, MAPPING = 2000102;
  final Gson gson = ResourceLoader.getGson();
  JsonObject raw;
  FloorInfo floor, oldFloor;
  MazePlaneExcel oldPlane;
  MapEntranceExcel oldEntry;
  PropExcel oldProp;
  Actor player;

  static class Actor extends Player {
    Scene current;
    Position position = new Position(21576, 146, 224180);
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
      assertTrue(saves > 0, "save must precede notification");
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
        "/fixtures/base-zone-anchor-box-4.6.json")), StandardCharsets.UTF_8)) {
      fixture = JsonParser.parseReader(reader).getAsJsonObject();
    }
    raw = fixture.getAsJsonObject("original").deepCopy();
    var prop = gson.fromJson(fixture.get("prop"), PropExcel.class);
    prop.onLoad();
    GameData.getPropExcelMap().add(prop);
    GameData.getMazePlaneExcelMap().add(gson.fromJson(
        "{\"PlaneID\":20001,\"PlaneType\":\"Maze\"}", MazePlaneExcel.class));
    GameData.getMapEntranceExcelMap().add(gson.fromJson(
        "{\"ID\":2000101,\"PlaneID\":20001,\"FloorID\":20001001,\"StartGroupID\":18,\"StartAnchorID\":2}", MapEntranceExcel.class));
    load();
  }

  void load() throws Exception {
    floor = gson.fromJson("{\"FloorID\":20001001}", FloorInfo.class);
    var group = gson.fromJson(raw, GroupInfo.class);
    group.setId(GROUP);
    SceneAnchors.loadTriggers(group, raw);
    floor.getGroups().put(GROUP, group);
    floor.onLoad();
    GameData.getFloorInfos().put("P20001_F20001001", floor);
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
  EntityProp anchor() { return player.current.getEntitiesByGroup(EntityProp.class, GROUP).stream()
      .filter(p -> p.getInstId() == INSTANCE).findFirst().orElseThrow(); }
  boolean unlocked() { return SceneAnchors.unlocked(player, GameData.getMapEntranceExcelMap().get(ENTRY), definition()); }
  JsonObject rawAnchor() {
    return raw.getAsJsonArray("PropList").asList().stream().map(JsonElement::getAsJsonObject)
        .filter(p -> p.get("ID").getAsInt() == INSTANCE).findFirst().orElseThrow();
  }

  @AfterEach void cleanup() {
    if (oldFloor == null) GameData.getFloorInfos().remove("P20001_F20001001");
    else GameData.getFloorInfos().put("P20001_F20001001", oldFloor);
    if (oldPlane == null) GameData.getMazePlaneExcelMap().keySet().remove(PLANE); else GameData.getMazePlaneExcelMap().add(oldPlane);
    if (oldEntry == null) GameData.getMapEntranceExcelMap().keySet().remove(ENTRY); else GameData.getMapEntranceExcelMap().add(oldEntry);
    if (oldProp == null) GameData.getPropExcelMap().keySet().remove(101); else GameData.getPropExcelMap().add(oldProp);
  }

  @Test void reportedPositionActivatesOnceAndRestores() throws Exception {
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
    var field = Player.class.getDeclaredField("gameplay"); field.setAccessible(true);
    field.set(player, gson.fromJson(gson.toJson(player.getGameplay()), GameplayState.class));
    reload();
    assertEquals(PropState.CheckPointEnable, anchor().getState());
    assertTrue(unlocked());
  }

  @Test void saveFailureRollsBackAndMovementCanRetry() {
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

  @Test void dimensionsAreFullLocalSizesAndOffsetRotatesWithTheProp() throws Exception {
    rawAnchor().addProperty("RotY", 90);
    load();
    // Rotated centre: (24.33, 0.146952, 221.52), with local Z half extent 5 m.
    player.position.set(29350, 146, 221520);
    player.onMove();
    assertFalse(unlocked());
    player.position.set(29320, 146, 221520);
    player.onMove();
    assertTrue(unlocked());
  }

  @ParameterizedTest @ValueSource(strings = {"outside-x", "outside-y", "outside-z", "unloaded"})
  void movementOutsideTheAuthoredVolumeDoesNotActivate(String reason) throws Exception {
    switch (reason) {
      case "outside-x" -> player.position.set(29350, 146, 226520);
      case "outside-y" -> player.position.set(23330, 3160, 226520);
      case "outside-z" -> player.position.set(23330, 146, 231540);
      case "unloaded" -> {
        var field = Scene.class.getDeclaredField("loaded"); field.setAccessible(true);
        field.set(player.current, false);
      }
    }
    player.onMove();
    assertFalse(unlocked());
    assertEquals(0, player.saves);
  }

  @ParameterizedTest @ValueSource(strings = {"missing-dimension", "zero-dimension", "negative-dimension", "text-dimension", "unknown", "polygon", "client", "tilted"})
  void malformedOrUnsupportedBoxesCannotUnlock(String reason) throws Exception {
    var trigger = rawAnchor().getAsJsonObject("CustomTriggerMap").getAsJsonObject("CustomTrigger_Spring");
    switch (reason) {
      case "missing-dimension" -> trigger.remove("DimX");
      case "zero-dimension" -> trigger.addProperty("DimY", 0);
      case "negative-dimension" -> trigger.addProperty("DimZ", -1);
      case "text-dimension" -> trigger.addProperty("DimX", "12");
      case "unknown" -> trigger.addProperty("UnknownGate", 1);
      case "polygon" -> trigger.getAsJsonArray("PointList").add(new JsonObject());
      case "client" -> trigger.addProperty("Server", false);
      case "tilted" -> rawAnchor().addProperty("RotX", 10);
    }
    load();
    player.onMove();
    assertFalse(unlocked());
    assertEquals(0, player.saves);
  }

  @Test void runtimeBoxDoesNotChangeExistingSourceFingerprints() {
    var group = gson.fromJson(raw, GroupInfo.class);
    String before = gson.toJson(group);
    SceneAnchors.loadTriggers(group, raw);
    assertEquals(before, gson.toJson(group));
    assertNotNull(group.getPropList().stream().filter(p -> p.getID() == INSTANCE)
        .findFirst().orElseThrow().getSpringActivation());
  }
}
