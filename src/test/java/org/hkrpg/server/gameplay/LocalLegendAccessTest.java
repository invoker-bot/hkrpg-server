package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import emu.lunarcore.game.quest.QuestState;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;

/** Original gates with explicit accepted-main-mission and operator-window fixtures. */
class LocalLegendAccessTest {
  @TempDir Path root;
  final LocalLegendTargetQuestsTest fixture = new LocalLegendTargetQuestsTest();
  final List<GameplayDefinitions.Window> originalWindows = new ArrayList<>();
  JsonArray access;

  @BeforeEach
  void load() throws Exception {
    originalWindows.addAll(GameplayDefinitions.windows);
    GameplayDefinitions.windows.clear();
    fixture.root = root;
    fixture.load();
    access =
        JsonParser.parseString(
                new String(
                    Objects.requireNonNull(
                            getClass()
                                .getResourceAsStream("/fixtures/local-legend-access-4.6.json"))
                        .readAllBytes(),
                    java.nio.charset.StandardCharsets.UTF_8))
            .getAsJsonArray();
    for (var value : access) {
      var row = value.getAsJsonObject();
      write(row.get("kind").getAsString(), row.getAsJsonObject("row"));
    }
    LocalLegendAccess.load(root);
    fixture.actor.setLevel(21);
    var main = new QuestState(1040416);
    main.advance(1, 1, 100);
    fixture.actor.getGameplay().getMainMissions().put(1040416, main);
  }

  void write(String table, JsonObject row) throws Exception {
    var values = new JsonArray();
    values.add(row);
    Files.writeString(root.resolve("ExcelOutput/" + table + ".json"), values.toString());
  }

  JsonObject source(String table) {
    return access.asList().stream()
        .map(JsonElement::getAsJsonObject)
        .filter(r -> r.get("kind").getAsString().equals(table))
        .findFirst()
        .orElseThrow()
        .getAsJsonObject("row")
        .deepCopy();
  }

  void window(int group) {
    GameplayDefinitions.windows.add(
        new GameplayDefinitions.Window(
            50070, LocalLegendCatalog.group(group).module(), 1000, 2000));
  }

  boolean available(int group, int difficulty, long time) {
    return LocalLegendAccess.available(
        fixture.actor, LocalLegendCatalog.level(group, difficulty), Instant.ofEpochSecond(time));
  }

  @AfterEach
  void restore() throws Exception {
    GameplayDefinitions.windows.clear();
    GameplayDefinitions.windows.addAll(originalWindows);
    fixture.restore();
    LocalLegendAccess.load(root.resolve("absent"));
  }

  @ParameterizedTest
  @CsvSource({"1,5007003", "2,5007001", "3,5007002", "4,5007004", "5,5007005"})
  void onlyTheOriginalGroupModuleWindowOpensTheEasyLevel(int group, int module) {
    assertEquals(module, LocalLegendCatalog.group(group).module());
    assertFalse(available(group, 1, 1500));
    window(group);
    assertTrue(available(group, 1, 1500));
    for (int other = 1; other <= 5; other++)
      if (other != group) assertFalse(available(other, 1, 1500));
    assertEquals(0, fixture.actor.saves);
    assertTrue(fixture.actor.getQuestStates().isEmpty());
  }

  @Test
  void originalMainPrerequisiteIsRequiredButGuideTaskIsNotSubstitutedForIt() {
    window(1);
    fixture.actor.getGameplay().getMainMissions().clear();
    var guide = new QuestState(8038203);
    guide.advance(1, 1, 100);
    fixture.actor.getGameplay().getMainMissions().put(8038203, guide);
    assertFalse(available(1, 1, 1500));
    var main = new QuestState(1040416);
    fixture.actor.getGameplay().getMainMissions().put(1040416, main);
    assertFalse(available(1, 1, 1500));
    main.advance(1, 1, 100);
    assertTrue(available(1, 1, 1500));
    fixture.actor.getGameplay().getMainMissions().remove(8038203);
    assertTrue(available(1, 1, 1500));
    fixture.actor.getGameplay().getCancelledMissions().add(1040416);
    assertFalse(available(1, 1, 1500));
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 1, 20, 21, 22, 70})
  void originalLevelTwentyOneGateIsCheckedBeforeEntry(int level) {
    window(1);
    fixture.actor.setLevel(level);
    assertEquals(level >= 21, available(1, 1, 1500));
  }

  @ParameterizedTest
  @ValueSource(longs = {999, 1000, 1999, 2000, 2001})
  void actualOperatorWindowUsesInclusiveBeginningAndExclusiveEnd(long time) {
    window(1);
    assertEquals(time >= 1000 && time < 2000, available(1, 1, time));
  }

  @Test
  void duplicateWindowsAndWrongPanelCannotOpenTheGroup() {
    GameplayDefinitions.windows.add(new GameplayDefinitions.Window(50071, 5007003, 1000, 2000));
    assertFalse(available(1, 1, 1500));
    window(1);
    assertTrue(available(1, 1, 1500));
    window(1);
    assertFalse(available(1, 1, 1500));
  }

  @Test
  void difficultyGateUsesActualMainTargetQuestAndDoesNotRequireEverySideTarget() {
    window(1);
    assertFalse(available(1, 2, 1500));
    assertFalse(available(1, 3, 1500));
    var fake = new QuestState(6072101);
    fake.advance(1, 1, 100);
    fixture.actor.getQuestStates().put(6072101, fake);
    assertFalse(available(1, 2, 1500));
    fixture
        .actor
        .getGameplay()
        .getLocalLegendProgress()
        .merge(LocalLegendCatalog.level(1, 1), 1, 100);
    fixture.actor.getQuestManager().refreshUnlocks();
    assertTrue(available(1, 2, 1500));
    assertFalse(available(1, 3, 1500));
    fixture
        .actor
        .getGameplay()
        .getLocalLegendProgress()
        .merge(LocalLegendCatalog.level(1, 2), 2, 101);
    fixture.actor.getQuestManager().refreshUnlocks();
    assertFalse(available(1, 3, 1500));
    fixture
        .actor
        .getGameplay()
        .getLocalLegendProgress()
        .merge(LocalLegendCatalog.level(1, 2), 1, 102);
    fixture.actor.getQuestManager().refreshUnlocks();
    assertTrue(available(1, 3, 1500));
    assertEquals(
        3,
        fixture.actor.getGameplay().getLocalLegendProgress().mask(LocalLegendCatalog.level(1, 2)));
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11})
  void missingAndUnsupportedAccessResourcesClosePreviouslyLoadedAccess(int variant)
      throws Exception {
    window(1);
    assertTrue(available(1, 1, 1500));
    var config = source("ActivityConfig");
    var panel = source("ActivityPanel");
    var conditions = source("ActivityPanelCondition");
    switch (variant) {
      case 0 -> config.addProperty("ActivityPanelID", 50071);
      case 1 ->
          config.add(
              "ActivityModuleIDList", JsonParser.parseString("[5007001,5007002,5007004,5007005]"));
      case 2 -> config.add("ActivityModuleIDList", JsonParser.parseString("[5007001,5007001]"));
      case 3 -> panel.addProperty("UnlockConditions", "[Unknown:21]");
      case 4 -> panel.add("UnlockConditions", JsonNull.INSTANCE);
      case 5 -> conditions.add("PreConditions", new JsonArray());
      case 6 ->
          conditions.add(
              "PreConditions",
              JsonParser.parseString("[{\"Type\":\"Unknown\",\"Param\":\"1040416\"}]"));
      case 7 ->
          conditions.add(
              "PreConditions",
              JsonParser.parseString("[{\"Type\":\"FinishMainMission\",\"Param\":1040416}]"));
      case 8 ->
          conditions.add(
              "PreConditions",
              JsonParser.parseString(
                  "[{\"Type\":\"FinishMainMission\",\"Param\":\"1040416\"},{\"Type\":\"FinishMainMission\",\"Param\":\"1040416\"}]"));
      case 9 -> config.addProperty("ActivityID", "50070");
      case 10 -> config.addProperty("ActivityID", 50071);
      case 11 -> {
        Files.delete(root.resolve("ExcelOutput/ActivityConfig.json"));
        assertThrows(IllegalStateException.class, () -> LocalLegendAccess.load(root));
        assertFalse(available(1, 1, 1500));
        return;
      }
    }
    write("ActivityConfig", config);
    write("ActivityPanel", panel);
    write("ActivityPanelCondition", conditions);
    assertThrows(IllegalStateException.class, () -> LocalLegendAccess.load(root));
    assertFalse(available(1, 1, 1500));
    assertEquals(0, fixture.actor.saves);
  }
}
