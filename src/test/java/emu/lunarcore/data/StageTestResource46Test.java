package emu.lunarcore.data;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import emu.lunarcore.LunarCore;
import emu.lunarcore.data.excel.PlaneEventExcel;
import emu.lunarcore.data.excel.StageExcel;
import emu.lunarcore.data.resource.*;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class StageTestResource46Test {
  @TempDir Path root;

  JsonObject source(int stageId) throws Exception {
    try (var in = getClass().getResourceAsStream("/fixtures/stage-test-events-4.6.json")) {
      assertNotNull(in);
      var rows =
          JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8))
              .getAsJsonArray();
      for (var raw : rows) {
        var record = raw.getAsJsonObject();
        if (record.getAsJsonObject("row").get("StageID").getAsInt() == stageId) return record;
      }
      throw new AssertionError("Missing original stage fixture " + stageId);
    }
  }

  void write(String file, String data) throws Exception {
    var path = root.resolve("ExcelOutput");
    Files.createDirectories(path);
    Files.writeString(path.resolve(file), data);
  }

  <T> void load(Class<T> type, ResourceMap<T> map) throws Exception {
    var before = LunarCore.getConfig().resourceDir;
    try {
      LunarCore.getConfig().resourceDir = root.toString();
      ResourceLoader.loadResourceDefinition(type, type.getAnnotation(ResourceType.class), map);
    } finally {
      LunarCore.getConfig().resourceDir = before;
    }
  }

  ExcelMap<StageExcel> stages() throws Exception {
    var map = new ExcelMap<StageExcel>();
    load(StageExcel.class, map);
    return map;
  }

  void main() throws Exception {
    write("StageConfig.json", "[" + source(201342210).getAsJsonObject("row") + "]");
  }

  @ParameterizedTest
  @ValueSource(
      ints = {
        100, 166, 167, 168, 169, 170, 171, 172, 173, 174, 175, 176, 177, 178, 179, 180, 181, 182,
        183, 184, 185, 186, 187, 188, 189, 190
      })
  void originalEventFindsItsRegisteredTestStageAndAllAuthoredMonsterSlots(int id) throws Exception {
    main();
    var fixture = source(id);
    var original = fixture.getAsJsonObject("row");
    write("StageTestConfig.json", "[" + original + "]");
    write("PlaneEvent.json", "[" + fixture.getAsJsonObject("event") + "]");
    var events = new MultiKeyExcelMap<PlaneEventExcel>();
    load(PlaneEventExcel.class, events);
    var event = events.get(fixture.getAsJsonObject("event").get("EventID").getAsInt(), 0);
    assertNotNull(event);
    var map = stages();
    var stage = map.get(event.getStageId());
    assertNotNull(
        stage, "The original event points to a stage omitted by the resource registration");
    assertEquals(id, stage.getId());
    assertEquals(original.get("Level").getAsInt(), stage.getLevel());
    assertEquals(original.get("StageType").getAsString(), stage.getStageType().name());
    var expected = new ArrayList<List<Integer>>();
    for (var wave : original.getAsJsonArray("MonsterList")) {
      var monsters = new ArrayList<Integer>();
      for (int slot = 0; slot < 5; slot++) {
        var value = wave.getAsJsonObject().get("Monster" + slot);
        if (value != null && value.getAsInt() != 0) monsters.add(value.getAsInt());
      }
      if (!monsters.isEmpty()) expected.add(monsters);
    }
    assertFalse(expected.isEmpty());
    assertEquals(expected, stage.getMonsterWaves());
    assertNotNull(
        map.get(201342210), "Loading the split table must retain the required base stage");
    assertEquals(2, map.size());
  }

  @Test
  void mapShapedSplitResourcesAlsoSupplyTheirAuthoredWave() throws Exception {
    main();
    write("StageTestConfig.json", "{\"177\":" + source(177).getAsJsonObject("row") + "}");
    var stage = stages().get(177);
    assertNotNull(stage);
    assertEquals(List.of(List.of(1003010)), stage.getMonsterWaves());
  }

  @Test
  void olderBundleWithoutTheOptionalSplitStillLoadsItsRequiredStage() throws Exception {
    main();
    var map = stages();
    assertEquals(1, map.size());
    assertNotNull(map.get(201342210));
    assertFalse(Files.exists(root.resolve("ExcelOutput/StageTestConfig.json")));
    var method = org.hkrpg.server.HkrpgServer.class.getDeclaredMethod("requiredTables");
    method.setAccessible(true);
    var required = (List<?>) method.invoke(null);
    assertTrue(required.contains("StageConfig.json"));
    assertFalse(required.contains("StageTestConfig.json"));
  }

  @Test
  void theOptionalTableCannotReplaceTheRequiredBase() throws Exception {
    write("StageTestConfig.json", "[" + source(177).getAsJsonObject("row") + "]");
    assertThrows(java.io.FileNotFoundException.class, this::stages);
  }

  @Test
  void malformedPresentSplitResourcesMustBeReported() throws Exception {
    main();
    write("StageTestConfig.json", "{invalid");
    assertThrows(JsonParseException.class, this::stages);
  }
}
