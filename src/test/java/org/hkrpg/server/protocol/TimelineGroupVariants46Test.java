package org.hkrpg.server.protocol;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import emu.lunarcore.data.*;
import emu.lunarcore.data.config.*;
import emu.lunarcore.data.excel.*;
import emu.lunarcore.game.scene.Scene;
import emu.lunarcore.game.scene.entity.EntityProp;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

/**
 * Actual asynchronous resource loader; two original G88 resources share an ID across dimensions.
 */
class TimelineGroupVariants46Test {
  @TempDir Path root;
  final TimelineActivity46Test fixture = new TimelineActivity46Test();
  final List<MapEntranceExcel> entries = new ArrayList<>();
  final Map<String, FloorInfo> floors = new HashMap<>();
  JsonObject base, variant;

  @BeforeEach
  void load() throws Exception {
    fixture.root = root;
    fixture.load();
    entries.addAll(GameData.getMapEntranceExcelMap().values());
    floors.putAll(GameData.getFloorInfos());
    GameData.getFloorInfos().clear();
    GameData.getMapEntranceExcelMap().values().clear();
    var raw =
        fixture
            .source
            .getAsJsonObject("records")
            .getAsJsonArray("ExcelOutput/MapEntrance.json")
            .asList()
            .stream()
            .map(JsonElement::getAsJsonObject)
            .filter(r -> r.get("ID").getAsInt() == 1040101)
            .findFirst()
            .orElseThrow();
    GameData.getMapEntranceExcelMap().add(fixture.gson.fromJson(raw, MapEntranceExcel.class));
    var groups =
        fixture.source.getAsJsonArray("catalogGroups").asList().stream()
            .map(JsonElement::getAsJsonObject)
            .filter(r -> r.get("plane").getAsInt() == 10401 && r.get("group").getAsInt() == 88)
            .toList();
    base =
        groups.stream()
            .filter(r -> r.get("path").getAsString().endsWith("_G88.json"))
            .findFirst()
            .orElseThrow();
    variant =
        groups.stream()
            .filter(r -> r.get("path").getAsString().endsWith("_G88_D1001.json"))
            .findFirst()
            .orElseThrow();
    var floor = new JsonObject();
    floor.addProperty("FloorID", 10401001);
    var refs = new JsonArray();
    for (var row : List.of(base, variant)) {
      var reference = new JsonObject();
      reference.addProperty("ID", 88);
      reference.addProperty("GroupPath", row.get("path").getAsString());
      refs.add(reference);
    }
    floor.add("GroupInstanceList", refs);
    var dims = new JsonArray();
    for (int i = 0; i < 2; i++) {
      var d = new JsonObject();
      d.addProperty("ID", i == 0 ? 0 : 1001);
      var indices = new JsonArray();
      indices.add(i);
      d.add("GroupIndexList", indices);
      dims.add(d);
    }
    floor.add("DimensionList", dims);
    fixture.write("Config/LevelOutput/RuntimeFloor/P10401_F10401001.json", floor);
    var method = ResourceLoader.class.getDeclaredMethod("loadFloorInfos");
    method.setAccessible(true);
    method.invoke(null);
  }

  @AfterEach
  void restore() throws Exception {
    GameData.getFloorInfos().clear();
    GameData.getFloorInfos().putAll(floors);
    GameData.getMapEntranceExcelMap().values().clear();
    entries.forEach(GameData.getMapEntranceExcelMap()::add);
    fixture.restore();
  }

  JsonObject original(JsonObject row) {
    return row.getAsJsonObject("definition").getAsJsonArray("PropList").asList().stream()
        .map(JsonElement::getAsJsonObject)
        .filter(r -> r.get("ID").getAsInt() == 300002)
        .findFirst()
        .orElseThrow();
  }

  JsonObject typed(PropInfo value) {
    return fixture.gson.toJsonTree(value).getAsJsonObject();
  }

  void assertOriginal(JsonObject row, PropInfo actual) {
    var expected = fixture.gson.fromJson(original(row), PropInfo.class);
    if (expected.getAnchorID() <= 0 && expected.getInitLevelGraph() != null)
      expected.setValueSource(null);
    assertEquals(typed(expected), typed(actual));
  }

  @Test
  void eachOriginalResourceIndexSurvivesTheAsynchronousGroupLoad() {
    var floor = GameData.getFloorInfo(10401, 10401001);
    assertOriginal(
        base,
        floor.getGroupInfoByIndex(0).getPropList().stream()
            .filter(p -> p.getID() == 300002)
            .findFirst()
            .orElseThrow());
    assertOriginal(
        variant,
        floor.getGroupInfoByIndex(1).getPropList().stream()
            .filter(p -> p.getID() == 300002)
            .findFirst()
            .orElseThrow());
    assertNotSame(floor.getGroupInfoByIndex(0), floor.getGroupInfoByIndex(1));
  }

  @Test
  void twoLiveSceneDimensionsUseTheirOwnOriginalPropDefinition() {
    for (int dim : new int[] {0, 1001}) {
      var actor = new PropTimeline46Test.Actor();
      actor.current = new Scene(actor, GameData.getMazePlaneExcelMap().get(10401), 10401001, dim);
      actor.current.setEntryId(1040101);
      actor.current.loadGroup(88);
      var prop =
          actor.current.getEntities().values().stream()
              .filter(
                  e -> e instanceof EntityProp && e.getGroupId() == 88 && e.getInstId() == 300002)
              .map(e -> (EntityProp) e)
              .findFirst()
              .orElseThrow();
      assertOriginal(dim == 0 ? base : variant, prop.getPropInfo());
    }
  }
}
