package emu.lunarcore.data;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import emu.lunarcore.LunarCore;
import emu.lunarcore.data.resource.*;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Unchanged source skill rows are loaded only into private temporary resource directories. */
class AvatarSkillParameterResourceTest {
  @TempDir Path root;

  Class<?> definition() throws Exception {
    var discover = ResourceLoader.class.getDeclaredMethod("getResourceDefClasses");
    discover.setAccessible(true);
    var classes = (List<?>) discover.invoke(null);
    var type =
        classes.stream()
            .map(c -> (Class<?>) c)
            .filter(
                c ->
                    Arrays.asList(c.getAnnotation(ResourceType.class).name())
                        .contains("AvatarSkillConfig.json"))
            .findFirst()
            .orElse(null);
    assertNotNull(type, "The actual resource loader has no AvatarSkillConfig parameter definition");
    return type;
  }

  JsonObject source(int id, int level) throws Exception {
    try (var in = getClass().getResourceAsStream("/fixtures/avatar-skill-parameters-4.6.json")) {
      assertNotNull(in);
      for (var element :
          JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8))
              .getAsJsonArray()) {
        var row = element.getAsJsonObject().getAsJsonObject("row");
        if (row.has("SkillID")
            && row.get("SkillID").getAsInt() == id
            && row.get("Level").getAsInt() == level) return row;
      }
    }
    fail("Missing original skill row");
    return null;
  }

  void write(String file, String data) throws Exception {
    Files.createDirectories(root.resolve("ExcelOutput"));
    Files.writeString(root.resolve("ExcelOutput").resolve(file), data);
  }

  @SuppressWarnings({"unchecked", "rawtypes"})
  MultiKeyExcelMap<?> load(Class<?> type) throws Exception {
    var map = new MultiKeyExcelMap();
    var before = LunarCore.getConfig().resourceDir;
    try {
      LunarCore.getConfig().resourceDir = root.toString();
      ResourceLoader.loadResourceDefinition(type, type.getAnnotation(ResourceType.class), map);
    } finally {
      LunarCore.getConfig().resourceDir = before;
    }
    return map;
  }

  OptionalDouble value(Object row, int index) throws Exception {
    return (OptionalDouble) row.getClass().getMethod("getParameter", int.class).invoke(row, index);
  }

  @ParameterizedTest
  @ValueSource(ints = {800107, 800207, 120207})
  void originalMazeRowsRetainTheirUnitsAndTriggerKey(int id) throws Exception {
    var type = definition();
    var row = source(id, 1);
    write("AvatarSkillConfig.json", "[" + row + "]");
    var map = load(type);
    var actual = map.get(id, 1);
    assertNotNull(actual);
    assertEquals(1, map.size());
    assertEquals(
        row.getAsJsonArray("ParamList").get(0).getAsJsonObject().get("Value").getAsDouble(),
        value(actual, 0).orElseThrow());
    assertEquals("SkillMaze", type.getMethod("getSkillTriggerKey").invoke(actual));
    assertEquals("Maze", type.getMethod("getAttackType").invoke(actual));
    assertTrue(value(actual, 1).isEmpty());
    assertTrue(value(actual, -1).isEmpty());
  }

  @Test
  void originalLevelsOfOneSkillCannotOverwriteEachOther() throws Exception {
    var type = definition();
    write("AvatarSkillConfig.json", "[" + source(800101, 1) + "," + source(800101, 2) + "]");
    var map = load(type);
    assertEquals(2, map.size());
    assertEquals(0.5, value(map.get(800101, 1), 0).orElseThrow());
    assertEquals(0.6, value(map.get(800101, 2), 0).orElseThrow());
    assertNull(map.get(800101, 3));
  }

  @Test
  void discoveredDefinitionHasTheActualReflectionRegistryMap() throws Exception {
    var type = definition();
    var lookup = ResourceLoader.class.getDeclaredMethod("getMapForExcel", Class.class, Class.class);
    lookup.setAccessible(true);
    assertInstanceOf(MultiKeyExcelMap.class, lookup.invoke(null, GameData.class, type));
  }

  @Test
  void anOlderBundleWithoutTheOptionalSplitStillLoadsTheRequiredSkillTable() throws Exception {
    var type = definition();
    write("AvatarSkillConfig.json", "[" + source(800107, 1) + "]");
    assertEquals(1, load(type).size());
    assertFalse(Files.exists(root.resolve("ExcelOutput/AvatarSkillConfigLD.json")));
  }

  @Test
  void anOptionalSplitCannotReplaceTheRequiredSkillTable() throws Exception {
    var type = definition();
    write("AvatarSkillConfigLD.json", "[" + source(800207, 1) + "]");
    assertThrows(java.io.FileNotFoundException.class, () -> load(type));
  }

  @ParameterizedTest
  @ValueSource(strings = {"AvatarSkillConfig.json", "AvatarSkillConfigLD.json"})
  void aPresentMalformedSkillTableIsReported(String file) throws Exception {
    var type = definition();
    write("AvatarSkillConfig.json", "[" + source(800107, 1) + "]");
    write(file, "{invalid");
    assertThrows(JsonParseException.class, () -> load(type));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "[]",
        "[null]",
        "[{}]",
        "[{\"Value\":null}]",
        "[{\"Value\":\"0.15\"}]",
        "[{\"Value\":true}]",
        "[{\"Value\":1e400}]"
      })
  void missingOrUnusableParametersDoNotBecomeZeroOrARecoveryDefault(String params)
      throws Exception {
    var type = definition();
    var row = source(800107, 1).deepCopy();
    row.add("ParamList", JsonParser.parseString(params));
    write("AvatarSkillConfig.json", "[" + row + "]");
    assertTrue(value(load(type).get(800107, 1), 0).isEmpty());
  }
}
