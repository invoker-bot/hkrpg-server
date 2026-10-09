package emu.lunarcore.data;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import emu.lunarcore.LunarCore;
import emu.lunarcore.data.excel.MazeBuffExcel;
import emu.lunarcore.data.resource.*;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Source rows are unchanged; only the temporary resource directory is written. */
class AvatarMazeBuffResourceTest {
  @TempDir Path root;
  final Gson gson = ResourceLoader.getGson();

  JsonObject source(int index) throws Exception {
    try (var in = getClass().getResourceAsStream("/fixtures/avatar-maze-buff-4.6.json")) {
      assertNotNull(in);
      return JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8))
          .getAsJsonArray()
          .get(index)
          .getAsJsonObject();
    }
  }

  void write(String file, String data) throws Exception {
    var path = root.resolve("ExcelOutput");
    Files.createDirectories(path);
    Files.writeString(path.resolve(file), data);
  }

  MultiKeyExcelMap<MazeBuffExcel> load() throws Exception {
    var map = new MultiKeyExcelMap<MazeBuffExcel>();
    var before = LunarCore.getConfig().resourceDir;
    try {
      LunarCore.getConfig().resourceDir = root.toString();
      ResourceLoader.loadResourceDefinition(
          MazeBuffExcel.class, MazeBuffExcel.class.getAnnotation(ResourceType.class), map);
    } finally {
      LunarCore.getConfig().resourceDir = before;
    }
    return map;
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 1, 2, 3})
  void registeredSplitTableLoadsTheActualCharacterBuffAlongsideBaseAndRogue(int index)
      throws Exception {
    var source = source(index);
    var row = source.getAsJsonObject("row");
    write("MazeBuff.json", "[{\"ID\":600000,\"Lv\":1}]");
    write("RogueMazeBuff.json", "[{\"ID\":612030,\"Lv\":1},{\"ID\":612030,\"Lv\":2}]");
    String file = Path.of(source.get("source").getAsString()).getFileName().toString();
    write(file, "[" + row + "]");
    var map = load();
    var buff = map.get(row.get("ID").getAsInt(), row.get("Lv").getAsInt());
    assertNotNull(buff, "Character maze buff split table was not registered");
    assertEquals(row.get("ID").getAsInt(), buff.getBuffId());
    assertEquals(row.get("InBattleBindingKey").getAsString(), buff.getInBattleBindingKey());
    assertNotNull(map.get(600000, 1));
    assertNotNull(map.get(612030, 1));
    assertNotNull(map.get(612030, 2));
    assertEquals(4, map.size());
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 1, 2, 3})
  void characterModifierTypeAndUseBindingSurviveResourceParsing(int index) throws Exception {
    var raw = source(index).getAsJsonObject("row");
    var parsed = gson.toJsonTree(gson.fromJson(raw, MazeBuffExcel.class)).getAsJsonObject();
    for (String field : List.of("ModifierName", "MazeBuffType", "UseType")) {
      if (!raw.has(field)) continue;
      assertEquals(raw.get(field), parsed.get(field), "Source binding field was dropped: " + field);
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"AvatarMazeBuff.json", "AvatarMazeBuffLD.json"})
  void presentInvalidAvatarTableCannotBeSilentlySkipped(String file) throws Exception {
    write("MazeBuff.json", "[{\"ID\":600000,\"Lv\":1}]");
    write(file, "{invalid");
    assertThrows(JsonParseException.class, this::load);
  }

  @Test
  void olderBundleWithoutAvatarSplitTablesStillLoadsItsRequiredBaseAndRogue() throws Exception {
    write("MazeBuff.json", "[{\"ID\":600000,\"Lv\":1}]");
    write("RogueMazeBuff.json", "[{\"ID\":612030,\"Lv\":1}]");
    var map = load();
    assertEquals(2, map.size());
    assertNotNull(map.get(600000, 1));
    assertNotNull(map.get(612030, 1));
    assertFalse(Files.exists(root.resolve("ExcelOutput/AvatarMazeBuff.json")));
    assertFalse(Files.exists(root.resolve("ExcelOutput/AvatarMazeBuffLD.json")));
  }

  @Test
  void avatarSplitTableDoesNotReplaceTheRequiredBaseTable() throws Exception {
    write("AvatarMazeBuff.json", "[" + source(0).getAsJsonObject("row") + "]");
    assertThrows(java.io.FileNotFoundException.class, this::load);
  }
}
