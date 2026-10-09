package emu.lunarcore.data.config;

import static org.junit.jupiter.api.Assertions.*;

import emu.lunarcore.data.ResourceLoader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Empty ValueSource is authored in the recovered 4.6 runtime groups. */
class PropSharedValue46Test {
  @ParameterizedTest
  @ValueSource(strings = {"{}", "{\"Values\":null}"})
  void emptySharedValueSourceHasNoKeyInsteadOfCrashing(String values) {
    var prop =
        ResourceLoader.getGson()
            .fromJson(
                "{\"ID\":300001,\"PropID\":104446,\"ValueSource\":" + values + "}", PropInfo.class);
    assertNull(prop.getSharedValueByKey("PuzzlePrefab"));
    assertNotNull(prop.getValueSource(), "Do not rewrite the source object to hide missing values");
    assertNull(prop.getValueSource().getValues(), "Keep authored missing/null values missing");
  }

  @Test
  void sourceWithoutValueSourceAndExplicitEmptyListRemainSafe() {
    var absent = ResourceLoader.getGson().fromJson("{\"ID\":300001}", PropInfo.class);
    var empty =
        ResourceLoader.getGson()
            .fromJson("{\"ID\":300001,\"ValueSource\":{\"Values\":[]}}", PropInfo.class);
    assertNull(absent.getSharedValueByKey("PuzzlePrefab"));
    assertNull(empty.getSharedValueByKey("PuzzlePrefab"));
    assertNotNull(empty.getValueSource().getValues());
    assertTrue(empty.getValueSource().getValues().isEmpty());
  }

  @Test
  void matchingAuthoredValueAndMissingKeysRetainTheirExistingMeaning() {
    var prop =
        ResourceLoader.getGson()
            .fromJson(
                "{\"ValueSource\":{\"Values\":[{\"$type\":\"RPG.GameCore.SharedString\","
                    + "\"Key\":\"PuzzlePrefab\",\"Value\":\"Props/Outputs/Chap03/Chap03_Prop_WolfBro/"
                    + "Chap03_Prop_PuzzleWolfBro1_10301001.prefab\"},{\"Key\":\"NoValue\"}]}}",
                PropInfo.class);
    assertEquals(
        "Props/Outputs/Chap03/Chap03_Prop_WolfBro/Chap03_Prop_PuzzleWolfBro1_10301001.prefab",
        prop.getSharedValueByKey("PuzzlePrefab"));
    assertNull(prop.getSharedValueByKey("NoValue"));
    assertNull(prop.getSharedValueByKey("OtherPrefab"));
  }
}
