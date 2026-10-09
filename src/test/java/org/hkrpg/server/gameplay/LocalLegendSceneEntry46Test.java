package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import emu.lunarcore.data.GameData;
import emu.lunarcore.data.ResourceLoader;
import emu.lunarcore.data.excel.PlaneEventExcel;
import emu.lunarcore.data.excel.StageExcel;
import emu.lunarcore.game.enums.StageType;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Three original activity difficulties, a server shell and the existing empty test party. */
class LocalLegendSceneEntry46Test {
  final SceneEventBattle46Test fixture = new SceneEventBattle46Test();

  @BeforeEach
  void load() throws Exception {
    fixture.load();
    try (var stream = getClass().getResourceAsStream("/fixtures/local-legend-entry-4.6.json")) {
      var rows =
          JsonParser.parseString(
                  new String(Objects.requireNonNull(stream).readAllBytes(), StandardCharsets.UTF_8))
              .getAsJsonArray();
      assertEquals(6, rows.size());
      for (var value : rows) {
        var row = value.getAsJsonObject();
        if (row.get("kind").getAsString().equals("event")) {
          GameData.getPlaneEventExcelMap()
              .add(ResourceLoader.getGson().fromJson(row.get("row"), PlaneEventExcel.class));
        } else {
          var stage = ResourceLoader.getGson().fromJson(row.get("row"), StageExcel.class);
          stage.onLoad();
          assertEquals(StageType.LocalLegend, stage.getStageType());
          GameData.getStageExcelMap().add(stage);
        }
      }
    }
  }

  @AfterEach
  void restore() {
    fixture.restore();
  }

  @ParameterizedTest
  @CsvSource({"426004,4260040", "426001,4260010", "426005,426005"})
  void nativeGenericEventCannotBypassTheDedicatedActivityEntry(int event, int stage)
      throws Exception {
    assertEquals(stage, SceneEventBattles.resolve(event, 0).getId());
    var session = fixture.new Session();
    assertTrue(session.owner.getLevel() < 21);
    fixture.rejected(session, fixture.request(event));
  }

  @ParameterizedTest
  @CsvSource({"426004,4260040", "426001,4260010", "426005,426005"})
  void genericEventServiceCannotCreateAnActivityBattleWithoutItsContext(int event, int stage) {
    var actor = new SceneEventBattle46Test.Actor();
    assertNull(
        fixture.service.startEventBattle(actor, event), "Generic entry bypassed activity gates");
    assertNull(actor.getBattle());
    assertEquals(0, actor.saves);
    assertEquals(0, actor.grants);
    assertEquals(1, actor.getNextBattleId(), "Rejected activity entry allocated a battle identity");
  }

  @ParameterizedTest
  @CsvSource({"426004,4260040", "426001,4260010", "426005,426005"})
  void rawOriginalLookupAndInternalScriptedEntryRemainAvailable(int event, int stage) {
    assertEquals(stage, SceneEventBattles.resolve(event, 0).getId());
    var actor = new SceneEventBattle46Test.Actor();
    var battle = fixture.service.startBattle(actor, event, 0);
    assertNotNull(battle);
    assertEquals(stage, battle.getStage().getId());
    assertEquals(StageType.LocalLegend, battle.getStage().getStageType());
    assertSame(battle, actor.getBattle());
  }
}
