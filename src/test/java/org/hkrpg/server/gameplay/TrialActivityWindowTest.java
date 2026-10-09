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
