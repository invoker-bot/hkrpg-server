package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import emu.lunarcore.*;
import emu.lunarcore.data.*;
import emu.lunarcore.data.excel.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class TrialActivityWindowTest {
  @TempDir Path root;
  final TrialActivityLifecycleTest fixture = new TrialActivityLifecycleTest();
  List<GameplayDefinitions.Window> oldWindows;
  ActivityPanelExcel oldPanel;
  java.lang.reflect.Field configField;
  Object oldConfig;
  static final int PANEL = 199951, MODULE = 19995101;

  @BeforeEach
  void prepare() throws Exception {
    fixture.install();
    fixture.roster(TrialActivityLifecycleTest.SPECIAL);
    oldWindows = new ArrayList<>(GameplayDefinitions.windows);
    GameplayDefinitions.windows.clear();
    oldPanel = GameData.getActivityPanelExcelMap().get(PANEL);
    panel("");
    configField = LunarCore.class.getDeclaredField("config");
    configField.setAccessible(true);
    oldConfig = configField.get(null);
    var config = new Config();
    config.resourceDir = root.toString();
    configField.set(null, config);
    catalog(root, PANEL, MODULE, TrialActivityLifecycleTest.STAGE);
  }

  static void catalog(Path root, int panel, int module, int stage) throws Exception {
    var dir = root.resolve("ExcelOutput");
    Files.createDirectories(dir);
    Files.writeString(
        dir.resolve("ActivityAvatarDemo.json"),
        "[{\"ActivityID\":" + panel + ",\"TypeParam\":[" + stage + "]}]");
    Files.writeString(
        dir.resolve("ActivityModuleDemo.json"),
        "[{\"AvatarDemoStageID\":" + stage + ",\"ActivityModuleID\":" + module + ",\"Sort\":1}]");
    Files.writeString(
        dir.resolve("ActivityConfig.json"),
        "[{\"ActivityID\":"
            + panel
            + ",\"ActivityPanelID\":"
            + panel
            + ",\"ActivityModuleIDList\":["
            + module
            + "]}]");
  }

  void panel(String conditions) {
    GameData.getActivityPanelExcelMap()
        .add(
            fixture.gson.fromJson(
                "{\"PanelID\":"
                    + PANEL
                    + ",\"UIPrefab\":\"UI/Quest/Widget/RoleTrialPanel.prefab\",\"UnlockConditions\":\""
                    + conditions
                    + "\"}",
                ActivityPanelExcel.class));
  }

  @AfterEach
  void restore() throws Exception {
    configField.set(null, oldConfig);
    GameplayDefinitions.windows.clear();
    GameplayDefinitions.windows.addAll(oldWindows);
    GameData.getActivityPanelExcelMap().keySet().remove(PANEL);
    if (oldPanel != null) GameData.getActivityPanelExcelMap().add(oldPanel);
    fixture.restore();
  }

  void window(int module, long begin, long end) {
    GameplayDefinitions.windows.add(new GameplayDefinitions.Window(PANEL, module, begin, end));
  }

  int start(TrialActivityLifecycleTest.Director player) throws Exception {
    var session = new TrialActivityLifecycleTest.Session(player);
    fixture.handle(
        session,
        "StartTrialActivityCsReq",
        fixture.request(session, "StartTrialActivityCsReq", TrialActivityLifecycleTest.STAGE));
    return fixture.code(session);
  }

  TrialActivityLifecycleTest.Director playerWith(emu.lunarcore.game.gacha.GachaService service) throws Exception {
    var field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
    field.setAccessible(true);
    var unsafe = (sun.misc.Unsafe) field.get(null);
    var game = (emu.lunarcore.server.game.GameServer) unsafe.allocateInstance(emu.lunarcore.server.game.GameServer.class);
    var serviceField = emu.lunarcore.server.game.GameServer.class.getDeclaredField("gachaService");
    serviceField.setAccessible(true); serviceField.set(game, service);
    return new TrialActivityLifecycleTest.Director() {
      @Override public emu.lunarcore.server.game.GameServer getServer() { return game; }
    };
  }

  @Test
  void normalBannerAllowsOnlyResidentNormalTrialStages() throws Exception {
    try (var rarity = new org.hkrpg.server.gacha.GachaRarityResources()) {
      int normalStage = TrialActivityLifecycleTest.STAGE + 1, module = MODULE + 1;
      GameData.getAvatarDemoExcelMap().add(fixture.gson.fromJson(
          "{\"StageID\":" + normalStage + ",\"AvatarID\":1003}", AvatarDemoExcel.class));
      try {
        var dir = root.resolve("ExcelOutput");
        Files.writeString(dir.resolve("ActivityConfig.json"), "[{\"ActivityID\":" + PANEL + ",\"ActivityPanelID\":" + PANEL
            + ",\"ActivityModuleIDList\":[" + MODULE + "]},{\"ActivityID\":10000,\"ResidentModuleList\":[" + module + "],\"ActivityModuleIDList\":[]}]");
        Files.writeString(dir.resolve("ActivityModuleDemo.json"), "[{\"AvatarDemoStageID\":" + normalStage
            + ",\"ActivityModuleID\":" + module + ",\"AvatarDemoType\":\"Normal\"}]");
        var service = new emu.lunarcore.game.gacha.GachaService(null) { @Override public void watch() {} };
        service.replaceBanners(List.of(new emu.lunarcore.game.gacha.GachaBanner().createBanner(1001,
            emu.lunarcore.game.gacha.GachaType.Normal, 0, 0, new int[0], new int[0])));
        var player = playerWith(service);
        assertTrue(TrialActivityCatalog.available(player, normalStage, Instant.now()));
        assertFalse(TrialActivityCatalog.available(player, TrialActivityLifecycleTest.STAGE, Instant.now()));
        service.replaceBanners(List.of());
        assertFalse(TrialActivityCatalog.available(player, normalStage, Instant.now()));
      } finally { GameData.getAvatarDemoExcelMap().keySet().remove(normalStage); }
    }
  }

  @Test
  void sharedActivityCanContainAnotherFiveStarAndSeparateModules() throws Exception {
    try (var rarity = new org.hkrpg.server.gacha.GachaRarityResources()) {
      int first = TrialActivityLifecycleTest.STAGE, second = first + 1, third = first + 2;
      var avatarField = AvatarDemoExcel.class.getDeclaredField("AvatarID"); avatarField.setAccessible(true);
      avatarField.setInt(GameData.getAvatarDemoExcelMap().get(first), 1102);
      try {
        GameData.getAvatarDemoExcelMap().add(fixture.gson.fromJson("{\"StageID\":" + second + ",\"AvatarID\":1003}", AvatarDemoExcel.class));
        GameData.getAvatarDemoExcelMap().add(fixture.gson.fromJson("{\"StageID\":" + third + ",\"AvatarID\":1002}", AvatarDemoExcel.class));
        var dir = root.resolve("ExcelOutput");
        Files.writeString(dir.resolve("ActivityAvatarDemo.json"), "[{\"ActivityID\":" + PANEL + ",\"TypeParam\":[" + first + "," + second + "," + third + "]}]");
        Files.writeString(dir.resolve("ActivityConfig.json"), "[{\"ActivityID\":" + PANEL + ",\"ActivityPanelID\":" + PANEL
            + ",\"ActivityModuleIDList\":[" + MODULE + "," + (MODULE+1) + "," + (MODULE+2) + "]}]");
        Files.writeString(dir.resolve("ActivityModuleDemo.json"), "[{\"AvatarDemoStageID\":" + first + ",\"ActivityModuleID\":" + MODULE
            + "},{\"AvatarDemoStageID\":" + second + ",\"ActivityModuleID\":" + (MODULE+1)
            + "},{\"AvatarDemoStageID\":" + third + ",\"ActivityModuleID\":" + (MODULE+2) + "}]");
        var service = new emu.lunarcore.game.gacha.GachaService(null) { @Override public void watch() {} };
        long now = Instant.now().getEpochSecond();
        service.replaceBanners(List.of(new emu.lunarcore.game.gacha.GachaBanner().createBanner(2001,
            emu.lunarcore.game.gacha.GachaType.AvatarUp, (int)(now-5), (int)(now+300), new int[]{1102}, new int[]{1002})));
        var player = playerWith(service);
        assertEquals(Set.of(MODULE, MODULE+2), ActivityAccess.active(player, Instant.ofEpochSecond(now))
            .stream().map(GameplayDefinitions.Window::moduleId).collect(java.util.stream.Collectors.toSet()));
        assertTrue(TrialActivityCatalog.available(player, first, Instant.ofEpochSecond(now)));
        assertTrue(TrialActivityCatalog.available(player, third, Instant.ofEpochSecond(now)));
        assertFalse(TrialActivityCatalog.available(player, second, Instant.ofEpochSecond(now)), "An inactive five-star banner cannot grant its trial");
      } finally {
        GameData.getAvatarDemoExcelMap().keySet().remove(second);
        GameData.getAvatarDemoExcelMap().keySet().remove(third);
      }
    }
  }

  @Test
  void anActiveMatchingBannerOpensOnlyItsActualTrialActivity() throws Exception {
    try (var rarity = new org.hkrpg.server.gacha.GachaRarityResources()) {
      var avatarField = AvatarDemoExcel.class.getDeclaredField("AvatarID");
      avatarField.setAccessible(true);
      avatarField.setInt(GameData.getAvatarDemoExcelMap().get(TrialActivityLifecycleTest.STAGE), 1102);
      var service = new emu.lunarcore.game.gacha.GachaService(null) { @Override public void watch() {} };
      long now = Instant.now().getEpochSecond();
      service.replaceBanners(List.of(new emu.lunarcore.game.gacha.GachaBanner().createBanner(
          2001, emu.lunarcore.game.gacha.GachaType.AvatarUp, (int)(now - 5), (int)(now + 300),
          new int[]{1102}, new int[0])));
      var player = playerWith(service);
      assertEquals(1, ActivityAccess.active(player, Instant.ofEpochSecond(now)).size());
      assertTrue(TrialActivityCatalog.available(player, TrialActivityLifecycleTest.STAGE, Instant.ofEpochSecond(now)));
      window(MODULE, 0, now);
      assertFalse(TrialActivityCatalog.available(player, TrialActivityLifecycleTest.STAGE, Instant.ofEpochSecond(now)),
          "An explicitly expired operator window cannot be bypassed by a derived one");
      GameplayDefinitions.windows.clear();
      panel("[PlayerLevel:80]");
      assertTrue(ActivityAccess.active(player, Instant.ofEpochSecond(now)).isEmpty());
      assertFalse(TrialActivityCatalog.available(player, TrialActivityLifecycleTest.STAGE, Instant.ofEpochSecond(now)));
      panel("");
      service.replaceBanners(List.of(new emu.lunarcore.game.gacha.GachaBanner().createBanner(
          2001, emu.lunarcore.game.gacha.GachaType.AvatarUp, (int)(now - 5), (int)(now + 300),
          new int[]{1102}, new int[]{1002})));
      assertTrue(ActivityAccess.active(player, Instant.ofEpochSecond(now)).isEmpty(), "A partial roster must not open a historical trial");
      service.replaceBanners(List.of(new emu.lunarcore.game.gacha.GachaBanner().createBanner(
          2001, emu.lunarcore.game.gacha.GachaType.AvatarUp, (int)(now - 10), (int) now,
          new int[]{1102}, new int[0])));
      assertTrue(ActivityAccess.active(player, Instant.ofEpochSecond(now)).isEmpty(), "Expired banners must not open trials");
      service.replaceBanners(List.of());
      assertTrue(ActivityAccess.active(player, Instant.ofEpochSecond(now)).isEmpty());
      assertFalse(TrialActivityCatalog.available(player, TrialActivityLifecycleTest.STAGE, Instant.ofEpochSecond(now)));
    }
  }

  @Test
  void closedTrialPanelCannotInstallActorsOrEnterItsResourceStage() throws Exception {
    var player = new TrialActivityLifecycleTest.Director();
    assertNotEquals(0, start(player));
    assertNull(player.getInstance());
    assertNull(player.getGameplay().getTrialRun());
    assertTrue(player.getAvatars().getTempAvatars().isEmpty());
    assertEquals(0, player.entryAttempts);
  }

  @Test
  void activeWindowCannotAdvertiseOrStartAnUnrelatedTrialModule() throws Exception {
    window(MODULE + 1, 0, Long.MAX_VALUE);
    var player = new TrialActivityLifecycleTest.Director();
    assertTrue(ActivityAccess.active(player, Instant.now()).isEmpty());
    assertNotEquals(0, start(player));
    assertEquals(0, player.entryAttempts);
  }

  @Test
  void lockedOrExpiredWindowsCannotBypassTheTrialPanelGate() throws Exception {
    window(MODULE, 0, Instant.now().getEpochSecond());
    assertNotEquals(0, start(new TrialActivityLifecycleTest.Director()));
    GameplayDefinitions.windows.clear();
    window(MODULE, 0, Long.MAX_VALUE);
    panel("[PlayerLevel:80]");
    assertNotEquals(0, start(new TrialActivityLifecycleTest.Director()));
  }

  @Test
  void relatedTrialActivityIsResolvedThroughItsActualPanelInsteadOfMatchingNumbers()
      throws Exception {
    var dir = root.resolve("ExcelOutput");
    Files.writeString(
        dir.resolve("ActivityAvatarDemo.json"),
        "[{\"ActivityID\":"
            + (PANEL + 1)
            + ",\"TypeParam\":["
            + TrialActivityLifecycleTest.STAGE
            + "]}]");
    Files.writeString(
        dir.resolve("ActivityConfig.json"),
        "[{\"ActivityID\":"
            + (PANEL + 1)
            + ",\"ActivityPanelID\":"
            + PANEL
            + ",\"ActivityModuleIDList\":["
            + MODULE
            + "]}]");
    window(MODULE, 0, Long.MAX_VALUE);
    var player = new TrialActivityLifecycleTest.Director();
    assertEquals(1, ActivityAccess.active(player, Instant.now()).size());
    assertEquals(0, start(player));
  }

  @Test
  void legacyStageModuleRowsCannotHideAStageDeclaredByTheCurrentActivity() throws Exception {
    Files.writeString(
        root.resolve("ExcelOutput/ActivityModuleDemo.json"),
        "[{\"AvatarDemoStageID\":"
            + (TrialActivityLifecycleTest.STAGE + 1)
            + ",\"ActivityModuleID\":"
            + MODULE
            + ",\"Sort\":1}]");
    window(MODULE, 0, Long.MAX_VALUE);
    var player = new TrialActivityLifecycleTest.Director();
    assertEquals(1, ActivityAccess.active(player, Instant.now()).size());
    assertEquals(0, start(player));
  }

  @Test
  void aHistoricalActivityWithoutAPanelCannotCloseASeparateValidTrialWindow() throws Exception {
    var dir = root.resolve("ExcelOutput");
    Files.writeString(
        dir.resolve("ActivityAvatarDemo.json"),
        "[{\"ActivityID\":"
            + PANEL
            + ",\"TypeParam\":["
            + TrialActivityLifecycleTest.STAGE
            + "]},"
            + "{\"ActivityID\":"
            + (PANEL + 1)
            + ",\"TypeParam\":["
            + TrialActivityLifecycleTest.STAGE
            + "]}]");
    Files.writeString(
        dir.resolve("ActivityConfig.json"),
        "[{\"ActivityID\":"
            + PANEL
            + ",\"ActivityPanelID\":"
            + PANEL
            + ",\"ActivityModuleIDList\":["
            + MODULE
            + "]},"
            + "{\"ActivityID\":"
            + (PANEL + 1)
            + ",\"ResidentModuleList\":[],\"ActivityModuleIDList\":["
            + (MODULE + 1)
            + "]}]");
    window(MODULE, 0, Long.MAX_VALUE);
    var player = new TrialActivityLifecycleTest.Director();
    assertEquals(1, ActivityAccess.active(player, Instant.now()).size());
    assertEquals(0, start(player));
  }

  @Test
  void aValidOpenPanelStartsItsResourceStageAndCanBeLeftAfterTheWindowCloses() throws Exception {
    window(MODULE, 0, Long.MAX_VALUE);
    var player = new TrialActivityLifecycleTest.Director();
    assertEquals(1, ActivityAccess.active(player, Instant.now()).size());
    assertEquals(0, start(player));
    GameplayDefinitions.windows.clear();
    var session = new TrialActivityLifecycleTest.Session(player);
    fixture.handle(
        session,
        "LeaveTrialActivityCsReq",
        fixture.request(session, "LeaveTrialActivityCsReq", TrialActivityLifecycleTest.STAGE));
    assertEquals(0, fixture.code(session));
    assertNull(player.getInstance());
  }
}
