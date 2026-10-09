package emu.lunarcore.data;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import emu.lunarcore.data.excel.StageExcel;
import emu.lunarcore.game.enums.StageType;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class StageType46Test {
  @ParameterizedTest
  @ValueSource(ints = {0, 1, 2, 3, 4, 5})
  void actualFourSixStageRetainsItsSourceCategoryInsteadOfBecomingUnknown(int index)
      throws Exception {
    try (var in = getClass().getResourceAsStream("/fixtures/stage-types-4.6.json")) {
      assertNotNull(in);
      var record =
          JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8))
              .getAsJsonArray()
              .get(index)
              .getAsJsonObject();
      var row = record.getAsJsonObject("row");
      var stage = ResourceLoader.getGson().fromJson(row, StageExcel.class);
      stage.onLoad();
      assertEquals(row.get("StageType").getAsString(), stage.getStageType().name());
      assertEquals(record.get("nativeStageTypeValue").getAsInt(), stage.getStageType().getVal());
      assertFalse(stage.getMonsterWaves().isEmpty());
      assertEquals(row.get("StageID").getAsInt(), stage.getId());
    }
  }

  @Test
  void unknownFutureCategoryKeepsTheExistingUnknownFallback() {
    var stage =
        ResourceLoader.getGson()
            .fromJson(
                "{\"StageID\":1,\"StageType\":\"FutureUnverifiedCategory\",\"MonsterList\":[]}",
                StageExcel.class);
    stage.onLoad();
    assertEquals(StageType.Unknown, stage.getStageType());
  }

  @Test
  void existingUniverseCategoryAndValueRemainUnchanged() {
    var stage =
        ResourceLoader.getGson()
            .fromJson(
                "{\"StageID\":1,\"StageType\":\"VerseSimulation\",\"MonsterList\":[]}",
                StageExcel.class);
    stage.onLoad();
    assertEquals(StageType.VerseSimulation, stage.getStageType());
    assertEquals(8, stage.getStageType().getVal());
  }
}
