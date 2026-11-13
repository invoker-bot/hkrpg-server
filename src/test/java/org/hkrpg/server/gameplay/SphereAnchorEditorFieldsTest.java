package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
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
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SphereAnchorEditorFieldsTest {
  final Gson gson = ResourceLoader.getGson();
  JsonObject fixture, spec, raw;
  final Map<String, FloorInfo> oldFloors = new HashMap<>();
  final Map<Integer, MazePlaneExcel> oldPlanes = new HashMap<>();
  final Map<Integer, MapEntranceExcel> oldEntries = new HashMap<>();
  PropExcel oldProp;
  Actor actor;

  static class Actor extends Player {
    final int plane, floor, entry;
    Scene scene;
    Position position = new Position();
    int saves;
    boolean fail;
    final List<BasePacket> packets = new ArrayList<>();
    final PlayerLineup team = new PlayerLineup(this, 0) {
      @Override public void heal(int amount, boolean revive) {}
    };
    Actor(int plane, int floor, int entry) { this.plane=plane; this.floor=floor; this.entry=entry; }
    @Override public Scene getScene() { return scene; }
    @Override public Position getPos() { return position; }
    @Override public int getPlaneId() { return plane; }
    @Override public int getFloorId() { return floor; }
    @Override public int getEntryId() { return entry; }
    @Override public PlayerLineup getCurrentLineup() { return team; }
    @Override public void save() { if(fail) throw new IllegalStateException("Rejected fixture save"); saves++; }
    @Override public void sendPacket(BasePacket packet) {
      assertTrue(saves > 0, "Activation must be saved before publication"); packets.add(packet);
    }
  }

  @BeforeEach void setup() throws Exception {
    try (var in = new InputStreamReader(Objects.requireNonNull(getClass().getResourceAsStream(
        "/fixtures/sphere-anchor-editor-fields-4.6.json")), StandardCharsets.UTF_8)) {
      fixture = JsonParser.parseReader(in).getAsJsonObject();
    }
    for(var row:fixture.getAsJsonArray("cases")) {
      var c=row.getAsJsonObject(); int plane=c.get("plane").getAsInt(),floor=c.get("floor").getAsInt(),entry=c.get("entry").getAsInt();
      oldFloors.put("P"+plane+"_F"+floor,GameData.getFloorInfo(plane,floor));
      oldPlanes.put(plane,GameData.getMazePlaneExcelMap().get(plane));
      oldEntries.put(entry,GameData.getMapEntranceExcelMap().get(entry));
    }
    oldProp=GameData.getPropExcelMap().get(101);
    var prop=gson.fromJson(fixture.get("prop"),PropExcel.class);prop.onLoad();GameData.getPropExcelMap().add(prop);
    select(20212);
  }

  void select(int plane) throws Exception {
    spec=fixture.getAsJsonArray("cases").asList().stream().map(JsonElement::getAsJsonObject)
        .filter(row->row.get("plane").getAsInt()==plane).findFirst().orElseThrow();
    raw=spec.getAsJsonObject("original").deepCopy();
    var selected=new JsonArray();
    for(var row:raw.getAsJsonArray("PropList"))
      if(row.getAsJsonObject().get("ID").getAsInt()==id()) selected.add(row);
    raw.add("PropList",selected);
    raw.add("NPCList",new JsonArray());raw.add("MonsterList",new JsonArray());
    load();
  }

  int id() { return spec.get("instance").getAsInt(); }
  int group() { return spec.get("group").getAsInt(); }
  JsonObject trigger() { return raw.getAsJsonArray("PropList").get(0).getAsJsonObject()
      .getAsJsonObject("CustomTriggerMap").getAsJsonObject("CustomTrigger_Spring"); }
  void load() throws Exception {
    int plane=spec.get("plane").getAsInt(),floor=spec.get("floor").getAsInt(),entry=spec.get("entry").getAsInt();
    GameData.getMazePlaneExcelMap().add(gson.fromJson("{\"PlaneID\":"+plane+",\"PlaneType\":\""+spec.get("planeType").getAsString()+"\"}",MazePlaneExcel.class));
    GameData.getMapEntranceExcelMap().add(gson.fromJson("{\"ID\":"+entry+",\"PlaneID\":"+plane+",\"FloorID\":"+floor+"}",MapEntranceExcel.class));
    var definition=gson.fromJson("{\"FloorID\":"+floor+"}",FloorInfo.class);
    var group=gson.fromJson(raw,GroupInfo.class);group.setId(group());SceneAnchors.loadTriggers(group,raw);
    definition.getGroups().put(group(),group);definition.onLoad();GameData.getFloorInfos().put("P"+plane+"_F"+floor,definition);
    actor=new Actor(plane,floor,entry);reload();
  }
  void reload() throws Exception {
    actor.scene=new Scene(actor,GameData.getMazePlaneExcelMap().get(actor.plane),actor.floor);actor.scene.setEntryId(actor.entry);
    var loaded=Scene.class.getDeclaredField("loaded");loaded.setAccessible(true);loaded.set(actor.scene,true);
  }
  EntityProp anchor() { return actor.scene.getEntitiesByGroup(EntityProp.class,group()).stream()
      .filter(entity->entity.getInstId()==id()).findFirst().orElseThrow(); }
  boolean unlocked() { return SceneAnchors.unlocked(actor,GameData.getMapEntranceExcelMap().get(actor.entry),anchor().getPropInfo()); }
  void atSourceCentre() {
    var p=raw.getAsJsonArray("PropList").get(0).getAsJsonObject();var t=trigger();
    var offset=t.has("Offset")?t.getAsJsonObject("Offset"):new JsonObject();
    assertFalse(p.has("RotY"),"The pinned examples have unrotated offsets");
    actor.position.set((int)Math.round((p.get("PosX").getAsDouble()+(offset.has("X")?offset.get("X").getAsDouble():0))*1000),
        (int)Math.round((p.get("PosY").getAsDouble()+(offset.has("Y")?offset.get("Y").getAsDouble():0))*1000),
        (int)Math.round((p.get("PosZ").getAsDouble()+(offset.has("Z")?offset.get("Z").getAsDouble():0))*1000));
  }
  void source20212InsideSphereOutsideBox() { actor.position.set(25010,-28866,-53120); }

  @AfterEach void restore() {
    oldFloors.forEach((key,value)->{GameData.getFloorInfos().remove(key);if(value!=null)GameData.getFloorInfos().put(key,value);});
    oldPlanes.forEach((key,value)->{GameData.getMazePlaneExcelMap().keySet().remove(key);if(value!=null)GameData.getMazePlaneExcelMap().add(value);});
    oldEntries.forEach((key,value)->{GameData.getMapEntranceExcelMap().keySet().remove(key);if(value!=null)GameData.getMapEntranceExcelMap().add(value);});
    GameData.getPropExcelMap().keySet().remove(101);if(oldProp!=null)GameData.getPropExcelMap().add(oldProp);
  }

  @ParameterizedTest @ValueSource(ints={20212,20411,10521})
  void originalSpheresRetainingBoxEditorDimensionsActivateOnce(int plane) throws Exception {
    select(plane);assertFalse(trigger().has("Shape"));
    assertInstanceOf(SceneAnchors.Sphere.class,anchor().getPropInfo().getSpringActivation());
    atSourceCentre();actor.onMove();
    assertTrue(unlocked());assertEquals(8,anchor().toSceneEntityProto().getProp().getPropState());
    assertEquals(1,actor.saves);assertEquals(1,actor.packets.size());
    actor.onMove();assertEquals(1,actor.saves);assertEquals(1,actor.packets.size());
  }

  @Test void anExplicitSphereTagAlsoIgnoresTheInactiveBoxSizes() throws Exception {
    trigger().addProperty("Shape","Sphere");load();source20212InsideSphereOutsideBox();actor.onMove();assertTrue(unlocked());
  }

  @ParameterizedTest @ValueSource(ints={0,-10})
  void finiteInactiveBoxSizesNeverSelectOrResizeTheSphere(int size) throws Exception {
    for(String key:List.of("DimX","DimY","DimZ"))trigger().addProperty(key,size);
    load();source20212InsideSphereOutsideBox();actor.onMove();assertTrue(unlocked());
    assertEquals(6,assertInstanceOf(SceneAnchors.Sphere.class,anchor().getPropInfo().getSpringActivation()).radius());
  }

  @Test void beingInsideTheUnusedBoxButOutsideTheSphereDoesNotUnlock() throws Exception {
    select(20411);atSourceCentre();actor.position.setZ(actor.position.getZ()+20000);actor.onMove();
    assertFalse(unlocked());assertEquals(0,actor.saves);
  }

  @ParameterizedTest @ValueSource(strings={"missing","zero","negative","text","infinity"})
  void retainedEditorDimensionsNeverReplaceAnInvalidActiveRadius(String variant) throws Exception {
    switch(variant) {
      case "missing"->trigger().remove("Radius");
      case "zero"->trigger().addProperty("Radius",0);
      case "negative"->trigger().addProperty("Radius",-1);
      case "text"->trigger().addProperty("Radius","6");
      case "infinity"->trigger().addProperty("Radius",Double.POSITIVE_INFINITY);
    }
    load();source20212InsideSphereOutsideBox();actor.onMove();assertFalse(unlocked());assertEquals(0,actor.saves);
  }

  @Test void malformedKnownMetadataRemainsRejected() throws Exception {
    trigger().addProperty("DimX","bad-size");load();atSourceCentre();actor.onMove();assertFalse(unlocked());assertEquals(0,actor.saves);
  }

  @Test void unknownFieldsStillCannotAuthorizeActivation() throws Exception {
    trigger().addProperty("UnknownGate",true);load();atSourceCentre();actor.onMove();assertFalse(unlocked());assertEquals(0,actor.saves);
  }

  @Test void failedPersistenceCanRetryWithoutPublishingUnsavedActivation() {
    source20212InsideSphereOutsideBox();actor.fail=true;actor.onMove();assertFalse(unlocked());assertTrue(actor.packets.isEmpty());
    actor.fail=false;actor.onMove();assertTrue(unlocked());assertEquals(1,actor.saves);
  }

  @Test void activationRestoresWithoutChangingTheAuthoredSource() throws Exception {
    var info=anchor().getPropInfo();String hash=PropTimelines.source(info);source20212InsideSphereOutsideBox();actor.onMove();assertTrue(unlocked());
    var gameplay=Player.class.getDeclaredField("gameplay");gameplay.setAccessible(true);gameplay.set(actor,gson.fromJson(gson.toJson(actor.getGameplay()),GameplayState.class));reload();
    assertTrue(unlocked());assertEquals(hash,PropTimelines.source(anchor().getPropInfo()));assertEquals(PropState.CheckPointDisable,info.getState());
  }

  @Test void explicitBoxesContinueToUseTheirDimensions() throws Exception {
    trigger().addProperty("Shape","Box");load();assertInstanceOf(SceneAnchors.Box.class,anchor().getPropInfo().getSpringActivation());
    source20212InsideSphereOutsideBox();actor.onMove();assertFalse(unlocked());assertEquals(0,actor.saves);
  }
}
