package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.google.gson.*;
import emu.lunarcore.LunarCore;
import emu.lunarcore.data.*;
import emu.lunarcore.data.config.*;
import emu.lunarcore.data.excel.*;
import emu.lunarcore.game.enums.PropState;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.game.player.lineup.PlayerLineup;
import emu.lunarcore.game.scene.Scene;
import emu.lunarcore.game.scene.entity.EntityProp;
import emu.lunarcore.server.packet.BasePacket;
import emu.lunarcore.util.Position;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.hkrpg.server.GameDiagnostics;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class GreatMineAnchorActivationTest {
  static final int PLANE = 20121, FLOOR = 20121001, ENTRY = 201210102, GROUP = 2;
  final Gson gson = ResourceLoader.getGson();
  JsonObject raw;
  FloorInfo floor, oldFloor;
  MapEntranceExcel oldEntry;
  MazePlaneExcel oldPlane;
  PropExcel oldProp;
  Actor actor;

  static class Actor extends Player {
    Scene scene;
    Position position = new Position();
    int saves;
    boolean fail;
    final List<BasePacket> packets = new ArrayList<>();
    final PlayerLineup team = new PlayerLineup(this, 0) {
      @Override public void heal(int amount, boolean revive) {}
    };
    @Override public Scene getScene() { return scene; }
    @Override public Position getPos() { return position; }
    @Override public int getPlaneId() { return PLANE; }
    @Override public int getFloorId() { return FLOOR; }
    @Override public int getEntryId() { return ENTRY; }
    @Override public PlayerLineup getCurrentLineup() { return team; }
    @Override public void save() {
      if (fail) throw new IllegalStateException("private fixture write failure");
      saves++;
    }
    @Override public void sendPacket(BasePacket packet) {
      assertTrue(saves > 0, "State must be saved before activation is published");
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
        "/fixtures/great-mine-anchors-4.6.json")), StandardCharsets.UTF_8)) {
      fixture = JsonParser.parseReader(reader).getAsJsonObject();
    }
    raw = fixture.getAsJsonArray("sources").get(0).getAsJsonObject().getAsJsonObject("original").deepCopy();
    // Other props remain in the pinned fixture; this test scene only instantiates its four anchors.
    var anchors = new JsonArray();
    raw.getAsJsonArray("PropList").forEach(value -> {
      if (value.getAsJsonObject().get("PropID").getAsInt() == 101) anchors.add(value);
    });
    raw.add("PropList", anchors);
    var prop = gson.fromJson(fixture.get("prop"), PropExcel.class);
    prop.onLoad();
    GameData.getPropExcelMap().add(prop);
    GameData.getMazePlaneExcelMap().add(gson.fromJson(
        "{\"PlaneID\":20121,\"PlaneType\":\"Maze\"}", MazePlaneExcel.class));
    GameData.getMapEntranceExcelMap().add(gson.fromJson(
        "{\"ID\":201210102,\"PlaneID\":20121,\"FloorID\":20121001,\"StartGroupID\":2,\"StartAnchorID\":1}", MapEntranceExcel.class));
    load();
  }

  void load() throws Exception {
    floor = gson.fromJson("{\"FloorID\":20121001}", FloorInfo.class);
    var group = gson.fromJson(raw, GroupInfo.class);
    group.setId(GROUP);
    SceneAnchors.loadTriggers(group, raw);
    floor.getGroups().put(GROUP, group);
    floor.onLoad();
    GameData.getFloorInfos().put("P20121_F20121001", floor);
    actor = new Actor();
    reload();
  }

  void reload() throws Exception {
    actor.scene = new Scene(actor, GameData.getMazePlaneExcelMap().get(PLANE), FLOOR);
    actor.scene.setEntryId(ENTRY);
    var loaded = Scene.class.getDeclaredField("loaded");
    loaded.setAccessible(true);
    loaded.set(actor.scene, true);
  }

  JsonObject source(int id) {
    return raw.getAsJsonArray("PropList").asList().stream().map(JsonElement::getAsJsonObject)
        .filter(prop -> prop.get("ID").getAsInt() == id).findFirst().orElseThrow();
  }
  JsonObject trigger(int id) { return source(id).getAsJsonObject("CustomTriggerMap").getAsJsonObject("CustomTrigger_Spring"); }
  EntityProp anchor(int id) { return actor.scene.getEntitiesByGroup(EntityProp.class, GROUP).stream()
      .filter(prop -> prop.getInstId() == id).findFirst().orElseThrow(); }
  void approach(int id) { actor.position.set(anchor(id).getPos()); }
  boolean unlocked(int id) { return SceneAnchors.unlocked(actor,
      GameData.getMapEntranceExcelMap().get(ENTRY), anchor(id).getPropInfo()); }

  @AfterEach void restore() {
    if (oldFloor == null) GameData.getFloorInfos().remove("P20121_F20121001"); else GameData.getFloorInfos().put("P20121_F20121001", oldFloor);
    GameData.getMapEntranceExcelMap().keySet().remove(ENTRY); if (oldEntry != null) GameData.getMapEntranceExcelMap().add(oldEntry);
    GameData.getMazePlaneExcelMap().keySet().remove(PLANE); if (oldPlane != null) GameData.getMazePlaneExcelMap().add(oldPlane);
    GameData.getPropExcelMap().keySet().remove(101); if (oldProp != null) GameData.getPropExcelMap().add(oldProp);
  }

  @ParameterizedTest @ValueSource(ints = {300001, 300004})
  void approachingOriginalAnchorsWithOmittedOffsetEnablesTheActualPropAndMapOnce(int id) {
    assertFalse(trigger(id).has("Offset"));
    approach(id);
    actor.onMove();
    assertTrue(unlocked(id));
    assertEquals(PropState.CheckPointEnable, anchor(id).getState());
    assertEquals(8, anchor(id).toSceneEntityProto().getProp().getPropState());
    assertEquals(1, actor.saves);
    assertEquals(1, actor.packets.size());
    actor.onMove();
    assertEquals(1, actor.saves);
    assertEquals(1, actor.packets.size());
    assertFalse(unlocked(id == 300004 ? 300001 : 300004));
  }

  @Test void savedActivationRestoresWithoutChangingAuthoredPlacementOrUnlockingAnotherAnchor() throws Exception {
    var before = gson.toJson(floor.getGroups().get(GROUP));
    approach(300004);
    actor.onMove();
    assertTrue(unlocked(300004));
    var gameplay = Player.class.getDeclaredField("gameplay");
    gameplay.setAccessible(true);
    gameplay.set(actor, gson.fromJson(gson.toJson(actor.getGameplay()), GameplayState.class));
    reload();
    assertTrue(unlocked(300004));
    assertFalse(unlocked(300001));
    assertEquals(before, gson.toJson(floor.getGroups().get(GROUP)));
    assertEquals(PropState.CheckPointDisable, anchor(300004).getPropInfo().getState());
  }

  @ParameterizedTest @ValueSource(ints = {300001, 300004})
  void failedSavePublishesNothingAndMovementCanRetryTheSameAnchor(int id) {
    approach(id);
    actor.fail = true;
    actor.onMove();
    assertFalse(unlocked(id));
    assertTrue(actor.packets.isEmpty());
    assertTrue(actor.getGameplay().getScenePropStates().getRecords().isEmpty());
    actor.fail = false;
    actor.onMove();
    assertTrue(unlocked(id));
    assertEquals(1, actor.saves);
  }

  @ParameterizedTest @ValueSource(strings = {"far", "other-storey", "not-spawned"})
  void omittedOffsetDoesNotExpandTheOriginalSphereOrActivateAnAbsentProp(String variant) {
    approach(300004);
    if (variant.equals("far")) actor.position.setX(actor.position.getX() + 6100);
    if (variant.equals("other-storey")) actor.position.setY(actor.position.getY() + 10000);
    if (variant.equals("not-spawned")) actor.scene.getEntities().remove(anchor(300004).getEntityId());
    actor.onMove();
    assertEquals(0, actor.saves);
    assertTrue(actor.getGameplay().getScenePropStates().getRecords().isEmpty());
  }

  @ParameterizedTest @ValueSource(strings = {"null", "array", "text", "unknown", "incomplete", "bad-number", "infinity"})
  void explicitMalformedOffsetsRemainRejected(String variant) throws Exception {
    var offset = JsonParser.parseString("{\"X\":0,\"Y\":0,\"Z\":0}").getAsJsonObject();
    switch (variant) {
      case "null" -> trigger(300004).add("Offset", JsonNull.INSTANCE);
      case "array" -> trigger(300004).add("Offset", new JsonArray());
      case "text" -> trigger(300004).addProperty("Offset", "0");
      case "unknown" -> { offset.addProperty("Untrusted", 1); trigger(300004).add("Offset", offset); }
      case "incomplete" -> { offset.remove("X"); trigger(300004).add("Offset", offset); }
      case "bad-number" -> { offset.addProperty("X", "0"); trigger(300004).add("Offset", offset); }
      case "infinity" -> { offset.addProperty("Y", Double.POSITIVE_INFINITY); trigger(300004).add("Offset", offset); }
    }
    load();
    approach(300004);
    actor.onMove();
    assertFalse(unlocked(300004));
    assertEquals(0, actor.saves);
  }

  @Test void authoredNonzeroOffsetStillUsesItsOriginalLocalCentre() {
    var volume = assertInstanceOf(SceneAnchors.Sphere.class, anchor(300002).getPropInfo().getSpringActivation());
    assertEquals(-1, volume.x()); assertEquals(1, volume.y()); assertEquals(3.5, volume.z());
    assertEquals(7, volume.radius());
  }

  @Test void loadingTheOmittedOffsetKeepsTheExistingSourceFingerprintStable() {
    var group = gson.fromJson(raw, GroupInfo.class);
    var before = gson.toJson(group);
    SceneAnchors.loadTriggers(group, raw);
    assertEquals(before, gson.toJson(group));
    assertNotNull(group.getPropList().stream().filter(prop -> prop.getID() == 300004)
        .findFirst().orElseThrow().getSpringActivation());
  }

  @Test void explicitZeroOffsetAndTheOmittedOffsetUseTheSameSphere() throws Exception {
    var missing = anchor(300004).getPropInfo().getSpringActivation();
    trigger(300004).add("Offset", JsonParser.parseString("{\"X\":0,\"Y\":0,\"Z\":0}"));
    load();
    assertNotNull(missing);
    assertEquals(missing, anchor(300004).getPropInfo().getSpringActivation());
  }

  @Test void omittedBoxOffsetUsesZeroWithoutFallingBackToItsRadius() throws Exception {
    // Explicit constructed box variant; the original checkpoint remains a sphere in the fixture.
    var trigger = trigger(300004);
    trigger.addProperty("Shape", "Box");
    trigger.addProperty("DimX", 12); trigger.addProperty("DimY", 4); trigger.addProperty("DimZ", 8);
    load(); approach(300004);
    actor.position.set(actor.position.getX() + 5500, actor.position.getY() + 1500,
        actor.position.getZ() + 3500);
    var box = assertInstanceOf(SceneAnchors.Box.class, anchor(300004).getPropInfo().getSpringActivation());
    assertEquals(0, box.x()); assertEquals(0, box.y()); assertEquals(0, box.z());
    actor.onMove();
    assertTrue(unlocked(300004));
  }

  @Test void invalidTriggerLogsItsOriginalFloorAndPlacementOncePerWindow() throws Exception {
    trigger(300004).add("Offset", JsonNull.INSTANCE);
    GameDiagnostics.flush(true);
    var logger = (Logger) LunarCore.getLogger();
    var capture = new ListAppender<ILoggingEvent>(); capture.start(); logger.addAppender(capture);
    try {
      load(); approach(300004); actor.onMove(); actor.onMove();
      var messages = capture.list.stream().map(ILoggingEvent::getFormattedMessage)
          .filter(message -> message.contains("Anchor trigger skipped:")).toList();
      assertEquals(1, messages.size());
      assertTrue(messages.getFirst().contains("plane=20121 floor=20121001 group=2 instance=300004"));
      GameDiagnostics.flush(true);
      assertTrue(capture.list.stream().anyMatch(event -> event.getFormattedMessage().contains("repeated=1")));
    } finally { logger.detachAppender(capture); capture.stop(); GameDiagnostics.flush(true); }
  }

  @Test void resourceParsingDoesNotWarnAboutAnAnchorBeforeAPlayerUsesItsScene() throws Exception {
    trigger(300004).add("Offset", JsonNull.INSTANCE);
    GameDiagnostics.flush(true);
    var logger = (Logger) LunarCore.getLogger();
    var capture = new ListAppender<ILoggingEvent>(); capture.start(); logger.addAppender(capture);
    try {
      load();
      assertTrue(capture.list.stream().noneMatch(event -> event.getFormattedMessage().contains("Anchor trigger skipped:")));
    } finally { logger.detachAppender(capture); capture.stop(); GameDiagnostics.flush(true); }
  }

  @Test void saveFailuresAreLocatedAndRepeatedMovementDoesNotSpamTheSameError() throws Exception {
    // A constructed explicit zero offset isolates diagnostic behavior from the parsing regression.
    trigger(300004).add("Offset", JsonParser.parseString("{\"X\":0,\"Y\":0,\"Z\":0}"));
    load(); approach(300004); actor.fail = true;
    GameDiagnostics.flush(true);
    var logger = (Logger) LunarCore.getLogger();
    var capture = new ListAppender<ILoggingEvent>(); capture.start(); logger.addAppender(capture);
    try {
      actor.onMove(); actor.onMove();
      var failures = capture.list.stream().map(ILoggingEvent::getFormattedMessage)
          .filter(message -> message.contains("stage=anchor-activate")).toList();
      assertEquals(1, failures.size());
      assertTrue(failures.getFirst().contains("subject=2012103"));
      assertFalse(failures.getFirst().contains("private fixture"));
      GameDiagnostics.flush(true);
      assertTrue(capture.list.stream().anyMatch(event -> event.getFormattedMessage().contains("repeated=1")));
    } finally { logger.detachAppender(capture); capture.stop(); GameDiagnostics.flush(true); }
  }
}
