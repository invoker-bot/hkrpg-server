package emu.lunarcore.data;

import static org.junit.jupiter.api.Assertions.*;

import emu.lunarcore.LunarCore;
import emu.lunarcore.data.excel.MazeBuffExcel;
import emu.lunarcore.data.resource.*;
import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RogueMazeBuffResourceTest {
  @TempDir Path root;

  private MultiKeyExcelMap<MazeBuffExcel> loadRegisteredFiles() throws Exception {
    var type = MazeBuffExcel.class.getAnnotation(ResourceType.class);
    var map = new MultiKeyExcelMap<MazeBuffExcel>();
    String previous = LunarCore.getConfig().resourceDir;
    try {
      LunarCore.getConfig().resourceDir = root.toString();
      ResourceLoader.loadResourceDefinition(MazeBuffExcel.class, type, map);
    } finally {
      LunarCore.getConfig().resourceDir = previous;
    }
    return map;
  }

  private void write(String file, String data) throws Exception {
    var excel = root.resolve("ExcelOutput");
    Files.createDirectories(excel);
    Files.writeString(excel.resolve(file), data);
  }

  @Test
  void splitUniverseTableSuppliesBothSelectableAndEnhancedLevelsAlongsideBaseBuffs()
      throws Exception {
    write("MazeBuff.json", "[{\"ID\":600000,\"Lv\":1}]");
    write("RogueMazeBuff.json", "[{\"ID\":612030,\"Lv\":1},{\"ID\":612030,\"Lv\":2}]");
    var map = loadRegisteredFiles();
    assertNotNull(
        map.get(612030, 1), "Universe blessing is absent from the registered resource sources");
    assertNotNull(map.get(612030, 2), "The same blessing must remain constructible when enhanced");
    assertNotNull(map.get(600000, 1));
    assertNull(map.get(612030, 3));
    assertEquals(3, map.size());
  }

  @Test
  void splitNestedResourceKeepsDifferentLevelsAndDoesNotReplaceTheBaseTable() throws Exception {
    write("MazeBuff.json", "{\"600000\":{\"ID\":600000,\"Lv\":1}}");
    write(
        "RogueMazeBuff.json",
        "{\"612030\":{\"1\":{\"ID\":612030,\"Lv\":1},\"2\":{\"ID\":612030,\"Lv\":2}}}");
    var map = loadRegisteredFiles();
    assertNotNull(map.get(612030, 1), "Nested split resources must reach the runtime cache");
    assertNotNull(map.get(612030, 2));
    assertNotNull(map.get(600000, 1));
    assertEquals(3, map.size());
  }

  @Test
  void olderBundleWithoutSplitTableStillLoadsTheRequiredBaseTable() throws Exception {
    write("MazeBuff.json", "[{\"ID\":600000,\"Lv\":1}]");
    var map = loadRegisteredFiles();
    assertNotNull(map.get(600000, 1));
    assertEquals(1, map.size());
    assertFalse(Files.exists(root.resolve("ExcelOutput/RogueMazeBuff.json")));
    var method = org.hkrpg.server.HkrpgServer.class.getDeclaredMethod("requiredTables");
    method.setAccessible(true);
    var required = (java.util.List<?>) method.invoke(null);
    assertTrue(required.contains("MazeBuff.json"));
    assertFalse(required.contains("RogueMazeBuff.json"));
  }

  @Test
  void missingRequiredBaseTableCannotBeReplacedByTheOptionalUniverseTable() throws Exception {
    write("RogueMazeBuff.json", "[{\"ID\":612030,\"Lv\":1}]");
    assertThrows(java.io.FileNotFoundException.class, this::loadRegisteredFiles);
  }

  @Test
  void invalidPresentSplitTableIsReportedInsteadOfBeingSilentlyIgnored() throws Exception {
    write("MazeBuff.json", "[{\"ID\":600000,\"Lv\":1}]");
    write("RogueMazeBuff.json", "{invalid");
    assertThrows(com.google.gson.JsonParseException.class, this::loadRegisteredFiles);
  }
}
