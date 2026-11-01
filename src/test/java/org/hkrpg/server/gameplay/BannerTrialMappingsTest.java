package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import emu.lunarcore.Config;
import emu.lunarcore.LunarCore;
import emu.lunarcore.data.GameData;
import emu.lunarcore.data.excel.*;
import emu.lunarcore.game.gacha.*;
import emu.lunarcore.game.quest.QuestState;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.stream.Stream;
import org.hkrpg.server.gacha.GachaRarityResources;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/** Fixed source membership, including every previously unavailable banner, without game fixtures. */
class BannerTrialMappingsTest {
  static final Gson JSON = new Gson();
  static final Instant NOW = Instant.ofEpochSecond(1_800_000_000L);
  static final JsonObject SOURCE;
  static {
    try (var input = BannerTrialMappingsTest.class.getResourceAsStream(
        "/fixtures/banner-trial-mappings-4.6.json")) {
      SOURCE = JsonParser.parseReader(new InputStreamReader(
          Objects.requireNonNull(input), StandardCharsets.UTF_8)).getAsJsonObject();
    } catch (java.io.IOException error) {
      throw new ExceptionInInitializerError(error);
    }
  }

  @TempDir Path root;
  Object oldConfig;
  java.lang.reflect.Field configField;
  GachaRarityResources rarity;
  List<ItemExcel> oldItems;
  List<AvatarExcel> oldAvatars;
  List<AvatarDemoExcel> oldDemos;
  List<ActivityPanelExcel> oldPanels;
  List<GameplayDefinitions.Window> oldWindows;
  GachaService service;
  TrialActivityLifecycleTest.Director player;

  static Stream<JsonObject> banners() {
    return SOURCE.getAsJsonArray("banners").asList().stream().map(JsonElement::getAsJsonObject);
  }

  Set<Integer> numbers(JsonObject row, String key) {
    var result = new HashSet<Integer>();
    for (var value : row.getAsJsonArray(key)) result.add(value.getAsInt());
    return result;
  }

  void write(String name, JsonArray rows) throws Exception {
    Files.writeString(root.resolve("ExcelOutput/" + name + ".json"), JSON.toJson(rows));
  }

  @BeforeEach
  void install() throws Exception {
    oldItems = new ArrayList<>(GameData.getItemExcelMap().values());
    oldAvatars = new ArrayList<>(GameData.getAvatarExcelMap().values());
    oldDemos = new ArrayList<>(GameData.getAvatarDemoExcelMap().values());
    oldPanels = new ArrayList<>(GameData.getActivityPanelExcelMap().values());
    oldWindows = new ArrayList<>(GameplayDefinitions.windows);
    GameplayDefinitions.windows.clear();
    rarity = new GachaRarityResources();
    var tables = SOURCE.getAsJsonObject("tables");
    Files.createDirectories(root.resolve("ExcelOutput"));
    for (String name : List.of("ActivityAvatarDemo", "ActivityConfig", "ActivityModuleDemo"))
      write(name, tables.getAsJsonArray(name));
    for (var row : tables.getAsJsonArray("ItemConfigAvatar"))
      GameData.getItemExcelMap().add(JSON.fromJson(row, ItemExcel.class));
    for (var row : tables.getAsJsonArray("AvatarConfig"))
      GameData.getAvatarExcelMap().add(JSON.fromJson(row, AvatarExcel.class));
    GameData.getAvatarDemoExcelMap().values().clear();
    for (var row : tables.getAsJsonArray("AvatarDemoConfig"))
      GameData.getAvatarDemoExcelMap().add(JSON.fromJson(row, AvatarDemoExcel.class));
    GameData.getActivityPanelExcelMap().values().clear();
    for (var row : tables.getAsJsonArray("ActivityPanel"))
      GameData.getActivityPanelExcelMap().add(JSON.fromJson(row, ActivityPanelExcel.class));
    configField = LunarCore.class.getDeclaredField("config");
    configField.setAccessible(true);
    oldConfig = configField.get(null);
    var config = new Config();
    config.resourceDir = root.toString();
    configField.set(null, config);
    service = new GachaService(null) { @Override public void watch() {} };
    player = new TrialActivityWindowTest().playerWith(service);
    var gate = new QuestState(1000510);
    assertTrue(gate.advance(1, 1, 123));
    player.getGameplay().getMainMissions().put(1000510, gate);
  }

  @AfterEach
  void restore() throws Exception {
    configField.set(null, oldConfig);
    rarity.close();
    GameData.getItemExcelMap().values().clear();
    oldItems.forEach(GameData.getItemExcelMap()::add);
    GameData.getAvatarExcelMap().values().clear();
    oldAvatars.forEach(GameData.getAvatarExcelMap()::add);
    GameData.getAvatarDemoExcelMap().values().clear();
    oldDemos.forEach(GameData.getAvatarDemoExcelMap()::add);
    GameData.getActivityPanelExcelMap().values().clear();
    oldPanels.forEach(GameData.getActivityPanelExcelMap()::add);
    GameplayDefinitions.windows.clear();
    GameplayDefinitions.windows.addAll(oldWindows);
  }

  GachaBanner activate(JsonObject source) {
    var row = source.deepCopy();
    row.addProperty("beginTime", NOW.getEpochSecond() - 10);
    row.addProperty("endTime", NOW.getEpochSecond() + 300);
    var banner = JSON.fromJson(row, GachaBanner.class);
    service.replaceBanners(List.of(banner));
    assertTrue(service.getPool(banner).canPull(), "The fixture must have a real valid UP pool");
    return banner;
  }

  @ParameterizedTest(name = "resource banner {index}: {0}")
  @MethodSource("banners")
  void everyBannerOpensItsSourceTrialStagesWithoutOpeningAnInactiveFiveStar(JsonObject row) {
    activate(row);
    var expected = numbers(row, "stages");
    assertFalse(expected.isEmpty());
    for (int stage : expected)
      assertTrue(TrialActivityCatalog.available(player, stage, NOW),
          "Banner " + row.get("id") + " cannot enter its actual stage " + stage);
    var activities = row.getAsJsonArray("activities").asList().stream()
        .map(JsonElement::getAsJsonObject).toList();
    var windows = ActivityAccess.active(player, NOW);
    assertFalse(windows.isEmpty(), "A mapped trial needs a native activity window");
    for (var window : windows)
      assertTrue(activities.stream().anyMatch(a -> a.get("panelId").getAsInt() == window.panelId()
          && numbers(a, "moduleIds").contains(window.moduleId())),
          "Advertised windows must come from the original activity declarations");
    var stages = SOURCE.getAsJsonObject("tables").getAsJsonArray("ActivityAvatarDemo");
    for (var candidate : activities)
      for (var activity : stages)
        if (activity.getAsJsonObject().get("ActivityID").getAsInt()
            == candidate.get("activityId").getAsInt())
          for (int stage : numbers(activity.getAsJsonObject(), "TypeParam"))
            if (!expected.contains(stage))
              assertFalse(TrialActivityCatalog.available(player, stage, NOW),
                  "A shared activity cannot enable the inactive five-star's trial");
    assertTrue(player.getGameplay().getTrialCompletions().isEmpty());
    assertTrue(player.getGameplay().getRewards().isEmpty());
  }

  @Test
  void allTwentyEightPreviouslyMissingBannersRemainClosedWhenLockedOrExpired() {
    var gaps = banners().filter(b -> b.get("wasGap").getAsBoolean()).toList();
    assertEquals(28, gaps.size());
    for (var row : gaps) {
      activate(row);
      player.getGameplay().getMainMissions().remove(1000510);
      assertTrue(ActivityAccess.active(player, NOW).isEmpty());
      for (int stage : numbers(row, "stages"))
        assertFalse(TrialActivityCatalog.available(player, stage, NOW));
      var gate = new QuestState(1000510);
      gate.advance(1, 1, 123);
      player.getGameplay().getMainMissions().put(1000510, gate);
      assertTrue(ActivityAccess.active(player, NOW.plusSeconds(300)).isEmpty());
      for (int stage : numbers(row, "stages"))
        assertFalse(TrialActivityCatalog.available(player, stage, NOW.plusSeconds(300)));
    }
  }

  @Test
  void anExplicitlyClosedGroupModuleCannotBeBypassedByOtherDerivedGroupModules() {
    var row = banners().filter(b -> b.get("id").getAsInt() == 2093).findFirst().orElseThrow();
    activate(row);
    GameplayDefinitions.windows.add(new GameplayDefinitions.Window(20070, 2007000, 0, NOW.getEpochSecond()));
    assertTrue(ActivityAccess.active(player, NOW).isEmpty());
    for (int stage : numbers(row, "stages"))
      assertFalse(TrialActivityCatalog.available(player, stage, NOW));
  }

  @Test
  void aMissingSourceStageCannotBeReplacedByAnotherTrialWithTheSameActor() {
    var row = banners().filter(b -> b.get("id").getAsInt() == 2093).findFirst().orElseThrow();
    activate(row);
    GameData.getAvatarDemoExcelMap().keySet().remove(314070);
    for (int stage : numbers(row, "stages"))
      assertFalse(TrialActivityCatalog.available(player, stage, NOW));
    assertTrue(ActivityAccess.active(player, NOW).isEmpty());
  }

  @Test
  void aPartialFourStarRosterCannotOpenAnotherActivitiesCompleteTrialSet() {
    var row = banners().filter(b -> b.get("id").getAsInt() == 2093).findFirst().orElseThrow().deepCopy();
    row.getAsJsonArray("rateUpItems4").remove(0);
    activate(row);
    assertTrue(ActivityAccess.active(player, NOW).isEmpty());
    for (int stage : numbers(row, "stages"))
      assertFalse(TrialActivityCatalog.available(player, stage, NOW));
  }

  @Test
  void conflictingStageVariantsInDifferentMatchingPanelsCannotBeGuessed() throws Exception {
    var row = banners().filter(b -> b.get("id").getAsInt() == 2093).findFirst().orElseThrow();
    activate(row);
    var tables = SOURCE.getAsJsonObject("tables");
    var activities = tables.getAsJsonArray("ActivityAvatarDemo").deepCopy();
    var original = activities.asList().stream().map(JsonElement::getAsJsonObject)
        .filter(a -> a.get("ActivityID").getAsInt() == 20070).findFirst().orElseThrow();
    var alternate = original.deepCopy();
    alternate.addProperty("ActivityID", 1999951);
    var stages = alternate.getAsJsonArray("TypeParam");
    for (int i = 0; i < stages.size(); i++)
      if (stages.get(i).getAsInt() == 314070) stages.set(i, new JsonPrimitive(999997));
    activities.add(alternate);
    write("ActivityAvatarDemo", activities);
    var configurations = tables.getAsJsonArray("ActivityConfig").deepCopy();
    configurations.add(JsonParser.parseString("{\"ActivityID\":1999951,\"ActivityPanelID\":1999951,"
        + "\"ActivityModuleIDList\":[199995100]}"));
    write("ActivityConfig", configurations);
    GameData.getActivityPanelExcelMap().add(JSON.fromJson("{\"PanelID\":1999951,"
        + "\"UIPrefab\":\"UI/Quest/Widget/RoleTrialPanel.prefab\",\"UnlockConditions\":\"\"}", ActivityPanelExcel.class));
    GameData.getAvatarDemoExcelMap().add(JSON.fromJson("{\"StageID\":999997,\"AvatarID\":1407}", AvatarDemoExcel.class));
    assertTrue(ActivityAccess.active(player, NOW).isEmpty());
    assertFalse(TrialActivityCatalog.available(player, 999997, NOW));
    for (int stage : numbers(row, "stages"))
      assertFalse(TrialActivityCatalog.available(player, stage, NOW));
  }

  @Test
  void disabledBannersCannotAdvertiseOrEnterTheirGroupedTrials() {
    for (var row : banners().filter(b -> b.get("wasGap").getAsBoolean()).toList()) {
      var disabled = row.deepCopy();
      disabled.addProperty("disabled", true);
      activate(disabled);
      assertTrue(ActivityAccess.active(player, NOW).isEmpty());
      for (int stage : numbers(row, "stages"))
        assertFalse(TrialActivityCatalog.available(player, stage, NOW));
    }
  }

  @Test
  void conflictingLegacyModuleAssignmentsCannotTurnIntoAGroupFallback() throws Exception {
    var row = banners().filter(b -> b.get("id").getAsInt() == 2093).findFirst().orElseThrow();
    activate(row);
    var modules = SOURCE.getAsJsonObject("tables").getAsJsonArray("ActivityModuleDemo").deepCopy();
    modules.add(JsonParser.parseString("{\"AvatarDemoStageID\":314070,\"ActivityModuleID\":2007000}"));
    write("ActivityModuleDemo", modules);
    assertTrue(ActivityAccess.active(player, NOW).isEmpty());
    for (int stage : numbers(row, "stages"))
      assertFalse(TrialActivityCatalog.available(player, stage, NOW));
  }
}
