package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import emu.lunarcore.proto.BattleStatisticsOuterClass.BattleStatistics;
import emu.lunarcore.proto.BattleStatisticsOuterClass.BattleStatistics.BattleTargetInfoEntry;
import emu.lunarcore.proto.BattleTargetListOuterClass.BattleTargetList;
import emu.lunarcore.proto.BattleTargetOuterClass.BattleTarget;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class LocalLegendCatalogTest {
  @TempDir Path root;
  JsonObject fixture;

  @BeforeEach
  void load() throws Exception {
    try (var in = getClass().getResourceAsStream("/fixtures/local-legend-catalog-4.6.json")) {
      fixture =
          JsonParser.parseString(
                  new String(Objects.requireNonNull(in).readAllBytes(), StandardCharsets.UTF_8))
              .getAsJsonObject();
    }
    assertEquals(
        "724b139d8c9c32d12552eb95745a4fee72bfe48b", fixture.get("sourceCommit").getAsString());
    Files.createDirectories(root.resolve("ExcelOutput"));
    for (var e : fixture.getAsJsonObject("tables").entrySet())
      write(e.getKey(), e.getValue().getAsJsonObject().getAsJsonArray("rows"));
    LocalLegendCatalog.load(root);
  }

  @AfterEach
  void restore() throws Exception {
    LocalLegendCatalog.load(root.resolve("absent"));
  }

  JsonArray rows(String name) {
    return fixture.getAsJsonObject("tables").getAsJsonObject(name).getAsJsonArray("rows");
  }

  void write(String name, JsonArray rows) throws Exception {
    Files.writeString(root.resolve("ExcelOutput/" + name + ".json"), rows.toString());
  }

  static BattleStatistics report(int key, int... values) {
    var targets = BattleTargetList.newInstance();
    for (int i = 0; i < values.length; i += 3)
      targets.addBattleTargetList(
          BattleTarget.newInstance()
              .setId(values[i])
              .setProgress(values[i + 1])
              .setTotalProgress(values[i + 2]));
    return BattleStatistics.newInstance()
        .addBattleTargetInfo(BattleTargetInfoEntry.newInstance().setKey(key).setValue(targets));
  }

  @Test
  void originalFifteenLevelsKeepTargetOrderModulesTrialsAndRewardTasks() {
    assertEquals(15, LocalLegendCatalog.levels().size());
    assertEquals(10, LocalLegendCatalog.rewards().size());
    assertEquals(5007003, LocalLegendCatalog.group(1).module());
    assertEquals(5007001, LocalLegendCatalog.group(2).module());
    var level = LocalLegendCatalog.level(1, 3);
    assertEquals(426005, level.event());
    assertEquals(6072102, level.unlockQuest());
    assertEquals(
        List.of(5001805, 5001826, 5001827),
        level.goals().stream().map(LocalLegendCatalog.Goal::id).toList());
    assertEquals(
        35,
        LocalLegendCatalog.levels().stream()
            .flatMap(l -> l.goals().stream())
            .map(LocalLegendCatalog.Goal::id)
            .distinct()
            .count());
    assertTrue(level.trials().isEmpty());
    assertEquals(6, LocalLegendCatalog.level(1, 1).trials().size());
    assertEquals(
        Set.of("EasyHard", "Fearless"),
        new HashSet<>(
            LocalLegendCatalog.rewards().stream()
                .map(LocalLegendCatalog.Reward::taskType)
                .toList()));
    assertThrows(UnsupportedOperationException.class, () -> level.goals().clear());
    assertThrows(
        UnsupportedOperationException.class, () -> LocalLegendCatalog.level(1, 1).trials().clear());
  }

  @Test
  void thresholdsComeFromOriginalTargetsRatherThanReportedTotals() {
    var level = LocalLegendCatalog.level(3, 2);
    int threshold = (int) level.goals().get(2).threshold();
    assertEquals(10000, threshold);
    assertEquals(0, level.reportedMask(report(5, 5001813, threshold - 1, 1)).orElseThrow());
    assertEquals(4, level.reportedMask(report(5, 5001813, threshold, 0)).orElseThrow());
    assertEquals(2, level.reportedMask(report(5, 5001812, 18000, 1)).orElseThrow());
    assertEquals(4, level.reportedMask(report(5, 5001813, -1, 0)).orElseThrow());
    assertFalse(level.complete(4));
    assertTrue(level.complete(7));
    assertFalse(level.validMask(8));
  }

  @Test
  void missingReportsAndOtherNativeCategoriesNeverManufactureProgress() {
    var level = LocalLegendCatalog.level(1, 3);
    assertEquals(0, level.reportedMask(BattleStatistics.newInstance()).orElseThrow());
    assertEquals(0, level.reportedMask(report(1, 5001805, 1, 1)).orElseThrow());
    assertTrue(level.reportedMask(null).isEmpty());
  }

  @Test
  void nativeClientAchievementsAreAllowedWithoutBecomingLocalLegendTargetProgress() {
    var level = LocalLegendCatalog.level(1, 3);
    assertEquals(0, level.reportedMask(report(6, 5001805, 1, 1)).orElseThrow());
    assertEquals(0, level.reportedMask(report(6, 30213, 1, 1)).orElseThrow());
    var statistics = report(6, 30213, 1, 1);
    statistics.addBattleTargetInfo(report(5, 5001805, 1, 1).getBattleTargetInfo().get(0));
    assertEquals(1, level.reportedMask(statistics).orElseThrow());
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 7, -1})
  void unknownCategoriesCannotSupplyLocalLegendCompletion(int category) {
    assertTrue(
        LocalLegendCatalog.level(1, 3).reportedMask(report(category, 5001805, 1, 1)).isEmpty());
  }

  @Test
  void duplicateCategoriesDuplicateTargetsAndOtherLevelsGoalsAreRejected() {
    var level = LocalLegendCatalog.level(1, 3);
    var duplicates = report(5, 5001805, 1, 1);
    duplicates.addBattleTargetInfo(report(5, 5001826, 10000, 10000).getBattleTargetInfo().get(0));
    assertTrue(level.reportedMask(duplicates).isEmpty());
    assertTrue(level.reportedMask(report(5, 5001805, 1, 1, 5001805, 0, 1)).isEmpty());
    assertTrue(level.reportedMask(report(5, 5001804, 1, 1)).isEmpty());
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 1, 2, 3, 4, 5, 6, 7, 8, 9})
  void invalidOrAmbiguousOriginalResourcesLeaveNoStaleCatalog(int variant) throws Exception {
    String table = "LocalLegendStageConfig";
    var changed = rows(table).deepCopy();
    var row = changed.get(0).getAsJsonObject();
    switch (variant) {
      case 0 -> changed.add(row.deepCopy());
      case 1 -> row.addProperty("GroupID", 1.5);
      case 2 -> row.addProperty("DifficultyLevel", "Unknown");
      case 3 -> row.addProperty("EventID", 0);
      case 4 -> row.addProperty("BattleAreaID", 2147483648L);
      case 5 -> row.getAsJsonArray("BattleTargetList").add(5001804);
      case 6 -> row.getAsJsonArray("TrialAvatarList").add(3231402);
      case 7 -> row.getAsJsonArray("BattleTargetList").set(0, new JsonPrimitive(12345));
      case 8 -> {
        table = "BattleTargetConfig";
        changed = rows(table).deepCopy();
        for (var t : changed)
          if (t.getAsJsonObject().get("ID").getAsInt() == 5001804)
            t.getAsJsonObject().addProperty("ParamType", "Less");
      }
      case 9 -> {
        table = "ActivityLocalLegendGroup";
        changed = rows(table).deepCopy();
        changed.get(0).getAsJsonObject().addProperty("ActivityModuleID", 5007003);
      }
    }
    write(table, changed);
    assertThrows(IllegalStateException.class, () -> LocalLegendCatalog.load(root));
    assertTrue(LocalLegendCatalog.levels().isEmpty());
    assertNull(LocalLegendCatalog.group(1));
  }

  @Test
  void oldResourcePacksCanOmitAllThreeNewActivityTablesButPartialPacksAreRejected()
      throws Exception {
    for (String name :
        List.of("LocalLegendStageConfig", "ActivityLocalLegendGroup", "ActivityLocalLegendReward"))
      Files.delete(root.resolve("ExcelOutput/" + name + ".json"));
    LocalLegendCatalog.load(root);
    assertTrue(LocalLegendCatalog.levels().isEmpty());
    write("LocalLegendStageConfig", rows("LocalLegendStageConfig"));
    assertThrows(IllegalStateException.class, () -> LocalLegendCatalog.load(root));
  }
}
