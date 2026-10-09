package emu.lunarcore.data;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import emu.lunarcore.LunarCore;
import emu.lunarcore.data.excel.*;
import emu.lunarcore.data.resource.*;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class EquipmentResourceRegistryTest {
  @TempDir Path root;

  @SuppressWarnings("unchecked")
  ExcelMap<EquipmentExcel> registered() throws Exception {
    var lookup = ResourceLoader.class.getDeclaredMethod("getMapForExcel", Class.class, Class.class);
    lookup.setAccessible(true);
    return (ExcelMap<EquipmentExcel>) lookup.invoke(null, GameData.class, EquipmentExcel.class);
  }

  @Test
  void discoveredLightConeDefinitionUsesTheExistingRuntimeRegistry() throws Exception {
    assertSame(
        GameData.getEquipExcelMap(), registered(), "EquipmentConfig must reach equipExcelMap");
  }

  @ParameterizedTest
  @ValueSource(ints = {20000, 21000, 23000})
  void actualLoaderStoresOriginalEquipmentAndTheIdenticalItemBackReference(int id)
      throws Exception {
    JsonObject item = null, equipment = null;
    try (var in = getClass().getResourceAsStream("/fixtures/item-sale-4.6.json")) {
      assertNotNull(in);
      for (var value :
          JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8))
              .getAsJsonArray()) {
        var r = value.getAsJsonObject();
        var row = r.getAsJsonObject("row");
        if (r.get("kind").getAsString().equals("item") && row.get("ID").getAsInt() == id)
          item = row;
        if (r.get("kind").getAsString().equals("equipment")
            && row.get("EquipmentID").getAsInt() == id) equipment = row;
      }
    }
    assertNotNull(item);
    assertNotNull(equipment);
    var oldItem = GameData.getItemExcelMap().get(id);
    var oldEquip = GameData.getEquipExcelMap().get(id);
    var previous = LunarCore.getConfig().resourceDir;
    try {
      var actualItem = ResourceLoader.getGson().fromJson(item, ItemExcel.class);
      GameData.getItemExcelMap().add(actualItem);
      GameData.getEquipExcelMap().keySet().remove(id);
      Files.createDirectories(root.resolve("ExcelOutput"));
      Files.writeString(root.resolve("ExcelOutput/EquipmentConfig.json"), "[" + equipment + "]");
      LunarCore.getConfig().resourceDir = root.toString();
      assertEquals(
          1,
          ResourceLoader.loadResourceDefinition(
              EquipmentExcel.class,
              EquipmentExcel.class.getAnnotation(ResourceType.class),
              registered()));
      var actual = GameData.getEquipExcelMap().get(id);
      assertNotNull(actual, "Parsed original light cone is missing from the real runtime registry");
      assertSame(actual, actualItem.getEquipmentExcel());
      assertEquals(id, actual.getId());
    } finally {
      LunarCore.getConfig().resourceDir = previous;
      GameData.getItemExcelMap().keySet().remove(id);
      if (oldItem != null) GameData.getItemExcelMap().add(oldItem);
      GameData.getEquipExcelMap().keySet().remove(id);
      if (oldEquip != null) GameData.getEquipExcelMap().add(oldEquip);
    }
  }
}
