package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import emu.lunarcore.data.GameData;
import emu.lunarcore.data.excel.AvatarSkillParameterExcel;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Seven further bindings; original conditional leaves are explicitly isolated. */
class MazeBuffRemainingParameterTest {
  final MazeBuffParameterTest helper = new MazeBuffParameterTest();
  JsonArray fixture;
  List<JsonObject> tasks;

  @BeforeEach
  void setup() throws Exception {
    helper.setup();
    try (var in = getClass().getResourceAsStream("/fixtures/maze-buff-more-parameters-4.6.json")) {
      assertNotNull(in);
      fixture =
          JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8))
              .getAsJsonArray();
    }
    tasks = new ArrayList<>();
    for (var value : fixture) {
      var record = value.getAsJsonObject();
      helper.fixture.add(record);
      if ("skill".equals(record.get("kind").getAsString()))
        GameData.getAvatarSkillParameterExcelMap()
            .add(helper.gson.fromJson(record.get("row"), AvatarSkillParameterExcel.class));
      if ("task".equals(record.get("kind").getAsString())) tasks.add(record);
    }
    assertEquals(11, tasks.size());
  }

  @AfterEach
  void restore() {
    helper.restore();
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10})
  void unchangedAdditionalSourceLeafUsesItsOwnDuration(int index) {
    var record = tasks.get(index);
    assertFalse(record.get("completeAbilityChainVerified").getAsBoolean());
    int id = record.get("avatarId").getAsInt();
    var caster = helper.caster(id);
    var task = record.getAsJsonObject("task");
    var skill = helper.skill(caster, task);
    double seconds =
        helper
            .record(id, "skill")
            .getAsJsonObject("row")
            .getAsJsonArray("ParamList")
            .get(record.get("parameterIndex").getAsInt())
            .getAsJsonObject()
            .get("Value")
            .getAsDouble();
    long before = System.currentTimeMillis();
    helper.cast(skill, caster);
    helper.expiry(caster, task.get("ID").getAsInt(), before, seconds);
  }

  @ParameterizedTest
  @ValueSource(ints = {1407, 1208, 1510, 1223, 1102, 1207, 1221})
  void eachAdditionalBindingReadsTheCurrentSkillRow(int id) {
    var caster = helper.caster(id);
    var task = helper.task(id);
    var skill = helper.skill(caster, task);
    int index = helper.record(id, "task").get("parameterIndex").getAsInt();
    helper.replace(
        id,
        row ->
            row.getAsJsonArray("ParamList")
                .set(index, JsonParser.parseString("{\"Value\":9.125}")));
    long before = System.currentTimeMillis();
    helper.cast(skill, caster);
    helper.expiry(caster, task.get("ID").getAsInt(), before, 9.125);
  }

  @ParameterizedTest
  @ValueSource(ints = {1407, 1208, 1510, 1223, 1102, 1207, 1221})
  void anUnrelatedCasterCannotBorrowTheseBindings(int id) {
    var caster = helper.caster(id);
    var task = helper.task(id);
    var skill = helper.skill(caster, task);
    var other = helper.caster(1309);
    helper.cast(skill, other);
    assertFalse(other.getBuffs().containsKey(task.get("ID").getAsInt()));
    assertTrue(caster.getBuffs().isEmpty());
  }

  @ParameterizedTest
  @ValueSource(ints = {1407, 1208, 1510, 1223, 1102, 1207, 1221})
  void disappearedRowsDoNotProduceAGuessedEffect(int id) {
    var caster = helper.caster(id);
    var task = helper.task(id);
    var skill = helper.skill(caster, task);
    GameData.getAvatarSkillParameterExcelMap().values().clear();
    helper.cast(skill, caster);
    assertFalse(caster.getBuffs().containsKey(task.get("ID").getAsInt()));
  }
}
