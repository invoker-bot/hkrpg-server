package org.hkrpg.server.protocol;

import static org.junit.jupiter.api.Assertions.*;

import com.google.protobuf.DynamicMessage;
import emu.lunarcore.Config;
import emu.lunarcore.LunarCore;
import emu.lunarcore.data.GameData;
import emu.lunarcore.data.ResourceLoader;
import emu.lunarcore.data.config.MissionInfo;
import emu.lunarcore.data.excel.ActivityPanelExcel;
import emu.lunarcore.data.excel.MainMissionExcel;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.game.quest.QuestState;
import emu.lunarcore.server.game.GameSession;
import emu.lunarcore.server.packet.Retcode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.hkrpg.server.gameplay.GameplayDefinitions;
import org.hkrpg.server.gameplay.MissionSnapshotRecords;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ActivityMissionSchedule46Test {
  private static final int ORDINARY = 4020800, RESIDENT = 8027111, LIMITED = 8042106;
  private static final int KEPT = 8012101, SUB = 804210601, PANEL = 50080, MODULE = 5008001;
  private static final Instant START = Instant.parse("2026-11-10T00:00:00Z");

  @TempDir Path root;
  private List<MainMissionExcel> oldMains;
  private final List<GameplayDefinitions.Window> oldWindows = List.copyOf(GameplayDefinitions.windows);
  private Session session;

  private static class Session extends Gameplay46HandlerTest.Session {
    final List<DynamicMessage> changes = new ArrayList<>();
    final Player actor = new Player() {
      public int getLevel() { return 21; }
      public void save() {}
      public boolean isOnline() { return true; }
      public GameSession getSession() { return Session.this; }
    };

    public Player getPlayer() { return actor; }

    public void sendClientPacket(String name, byte[] bytes) {
      super.sendClientPacket(name, bytes);
      if (name.equals("PlayerSyncScNotify"))
        changes.add((DynamicMessage) Gameplay46.value(response, "mission_sync"));
    }
  }

  @BeforeEach void setup() throws Exception {
    var excel = Files.createDirectories(root.resolve("ExcelOutput"));
    Files.writeString(excel.resolve("MainMissionSchedule.json"), """
        [{"MainMissionID":8042106,"ActivityModuleID":5008001},
         {"MainMissionID":8027111,"ActivityModuleID":5002402},
         {"MainMissionID":8012101,"ActivityModuleID":5000401,"IsNotDelete":true}]
        """);
    Files.writeString(excel.resolve("ActivityConfig.json"), """
        [{"ActivityID":50080,"ActivityPanelID":50080,"ActivityModuleIDList":[5008001,5008002],"ResidentModuleList":[]},
         {"ActivityID":50024,"ActivityPanelID":50024,"ActivityModuleIDList":[5002401],"ResidentModuleList":[5002402]},
         {"ActivityID":50004,"ActivityPanelID":50004,"ActivityModuleIDList":[5000401],"ResidentModuleList":[]}]
        """);
    var field = LunarCore.class.getDeclaredField("config");
    field.setAccessible(true);
    var config = new Config();
    config.dataDir = root.toString();
    var oldConfig = LunarCore.getConfig();
    try {
      field.set(null, config);
      GameplayDefinitions.load(root);
    } finally {
      field.set(null, oldConfig);
    }

    var gson = ResourceLoader.getGson();
    var mains = GameData.getMainMissionExcelMap();
    oldMains = List.copyOf(mains.values());
    mains.values().clear();
    for (int id : List.of(ORDINARY, RESIDENT, LIMITED, KEPT)) {
      mains.add(gson.fromJson("""
          {"MainMissionID":%d,"Type":"Branch","TakeParam":[{"Type":"PlayerLevel","Value":21}],
           "BeginParam":[{"Type":"Auto"}]}
          """.formatted(id), MainMissionExcel.class));
      GameData.getMissionInfos().put(id, gson.fromJson("{\"MainMissionID\":" + id + "}", MissionInfo.class));
    }
    panel(PANEL, "[PlayerLevel:21]");
    panel(50081, "");
    panel(50004, "");
    GameplayDefinitions.subs.put(SUB, gson.fromJson("""
        {"ID":804210601,"MainMissionID":8042106,"TakeType":"Auto","Progress":3,
         "FinishType":"Talk","ParamStr1":"FixtureTalk"}
        """, MissionInfo.SubMissionInfo.class));
    session = new Session();
    at(0);
  }

  @AfterEach void restore() {
    var mains = GameData.getMainMissionExcelMap();
    if (oldMains != null) {
      mains.values().clear();
      oldMains.forEach(mains::add);
    }
    for (int id : List.of(ORDINARY, RESIDENT, LIMITED, KEPT)) GameData.getMissionInfos().remove(id);
    for (int id : List.of(PANEL, 50081, 50004)) GameData.getActivityPanelExcelMap().keySet().remove(id);
    GameplayDefinitions.subs.remove(SUB);
    GameplayDefinitions.replaceActivityWindows(oldWindows);
  }

  private void panel(int id, String unlock) {
    GameData.getActivityPanelExcelMap().add(ResourceLoader.getGson().fromJson(
        "{\"PanelID\":" + id + ",\"UnlockConditions\":\"" + unlock + "\"}", ActivityPanelExcel.class));
  }

  private void at(long seconds) {
    session.actor.getMissionManager().setClock(Clock.fixed(START.plusSeconds(seconds), ZoneOffset.UTC));
  }

  private static GameplayDefinitions.Window window(int panel, int module) {
    return new GameplayDefinitions.Window(panel, module, START.getEpochSecond(), START.plusSeconds(60).getEpochSecond());
  }

  private void exactWindow() { GameplayDefinitions.replaceActivityWindows(List.of(window(PANEL, MODULE))); }

  private QuestState oldProgress() {
    session.actor.getGameplay().getMainMissions().put(LIMITED, new QuestState(LIMITED));
    var sub = new QuestState(SUB);
    assertTrue(sub.advance(1, 3, START.getEpochSecond()));
    session.actor.getGameplay().getSubMissions().put(SUB, sub);
    return sub;
  }

  private static List<DynamicMessage> rows(DynamicMessage message, String field) {
    return ((List<?>) Gameplay46.value(message, field)).stream().map(DynamicMessage.class::cast).toList();
  }

  private static boolean hasMain(DynamicMessage snapshot, int id) {
    return rows(snapshot, "main_mission_list").stream().anyMatch(row -> Gameplay46.number(row, "id") == id);
  }

  private static DynamicMessage sub(DynamicMessage snapshot) {
    return rows(snapshot, "mission_list").stream().filter(row -> Gameplay46.number(row, "id") == SUB)
        .findFirst().orElseThrow();
  }

  private static void assertSub(DynamicMessage snapshot, String status, int progress) {
    var row = sub(snapshot);
    assertEquals("MissionStatus_Mission" + status, Gameplay46.value(row, "status").toString());
    assertEquals(progress, Gameplay46.number(row, "progress"));
  }

  @Test void automaticAcceptanceRequiresTheActualModuleAndPanel() throws Exception {
    GameplayDefinitions.replaceActivityWindows(List.of(window(PANEL, 5008002), window(50081, MODULE)));
    session.request("GetMissionDataCsReq");
    assertTrue(hasMain(session.response, ORDINARY));
    assertTrue(hasMain(session.response, RESIDENT));
    assertFalse(hasMain(session.response, LIMITED));
    assertFalse(session.actor.getGameplay().getMainMissions().containsKey(LIMITED));
    exactWindow();
    panel(PANEL, "[PlayerLevel:22]");
    session.request("GetMissionDataCsReq");
    assertFalse(hasMain(session.response, LIMITED));
    panel(PANEL, "[PlayerLevel:21]");
    session.request("GetMissionDataCsReq");
    assertTrue(hasMain(session.response, LIMITED));
  }

  @ParameterizedTest @ValueSource(booleans = {false, true})
  void explicitAcceptanceRejectsClosedActivityIncludingAnOldRecord(boolean saved) {
    if (saved) oldProgress();
    assertEquals(Retcode.MISSION_NOT_DOING, session.actor.getMissionManager().accept(LIMITED));
    assertEquals(saved, session.actor.getGameplay().getMainMissions().containsKey(LIMITED));
  }

  @Test void oldProgressIsHiddenAndCannotAdvanceWhileClosedButResumesAtOpening() throws Exception {
    var stored = oldProgress();
    exactWindow();
    at(-1);
    session.request("GetMissionDataCsReq");
    assertFalse(hasMain(session.response, LIMITED));
    assertSub(session.response, "None", 0);
    assertNull(MissionSnapshotRecords.sceneSub(session.actor, SUB));
    assertEquals(Retcode.MISSION_NOT_DOING, session.actor.getMissionManager().finishTalk(SUB, "FixtureTalk"));
    assertEquals(1, stored.getProgress());
    at(0);
    session.request("GetMissionDataCsReq");
    assertTrue(hasMain(session.response, LIMITED));
    assertSub(session.response, "Doing", 1);
    assertSame(stored, MissionSnapshotRecords.sceneSub(session.actor, SUB));
  }

  @Test void anOpenConnectionWithdrawsAndRestoresTheObjectiveOnce() throws Exception {
    oldProgress();
    exactWindow();
    session.request("GetMissionDataCsReq");
    session.changes.clear();
    at(60);
    session.actor.getMissionManager().refresh();
    session.actor.getMissionManager().refresh();
    assertEquals(1, session.changes.size());
    assertSub(session.changes.getLast(), "None", 0);
    at(1);
    session.actor.getMissionManager().refresh();
    session.actor.getMissionManager().refresh();
    assertEquals(2, session.changes.size());
    assertSub(session.changes.getLast(), "Doing", 1);
  }

  @Test void completedHistoryAndExplicitlyRetainedOldMissionsStayVisible() throws Exception {
    session.actor.getGameplay().getMainMissions().put(KEPT, new QuestState(KEPT));
    var done = new QuestState(LIMITED);
    done.advance(1, 1, START.getEpochSecond());
    done.reserveReward();
    done.closeReward();
    session.actor.getGameplay().getMainMissions().put(LIMITED, done);
    session.request("GetMissionDataCsReq");
    assertTrue(hasMain(session.response, KEPT));
    assertTrue(((List<?>) Gameplay46.value(session.response, "finished_main_mission_id_list")).contains(LIMITED));
  }
}
