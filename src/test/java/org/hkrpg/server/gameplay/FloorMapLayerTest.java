package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.Gson;
import emu.lunarcore.data.config.FloorInfo;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class FloorMapLayerTest {
  @Test
  void originalSingleLayerFloorRetainsItsAuthoredMask() throws Exception {
    var floor = readFloor("P20001_F20001001.json");
    assertEquals(20001001, floor.getFloorID());
    assertArrayEquals(new int[] {8}, masks(floor));
  }

  @Test
  void originalTwoLayerFloorRetainsMaskOrderRatherThanTreatingMasksAsLayerIds()
      throws Exception {
    var floor = readFloor("P10000_F10000003.json");
    assertEquals(10000003, floor.getFloorID());
    assertArrayEquals(new int[] {8, 16}, masks(floor));
  }

  private static FloorInfo readFloor(String name) throws Exception {
    return new Gson().fromJson(
        Files.readString(Path.of("resources", "4.6", "Config", "LevelOutput", "RuntimeFloor", name)),
        FloorInfo.class);
  }

  private static int[] masks(FloorInfo floor) throws Exception {
    var field = assertDoesNotThrow(() -> FloorInfo.class.getDeclaredField("LayerToAreaMask"),
        "FloorInfo must retain the authored LayerToAreaMask for native map-layer validation");
    assertEquals(int[].class, field.getType());
    return (int[]) FloorInfo.class.getMethod("getLayerToAreaMask").invoke(floor);
  }
}
