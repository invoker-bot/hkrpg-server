package org.hkrpg.server.protocol;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import com.google.protobuf.*;
import emu.lunarcore.game.quest.QuestState;
import emu.lunarcore.server.packet.Retcode;
import java.lang.reflect.Field;
import java.util.*;
import org.hkrpg.server.gameplay.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class HeartDialFull46Test {
  final HeartDialMissing46Test missing = new HeartDialMissing46Test();
  final HeartDial46Test fixture = missing.fixture;
  static final int SCRIPT = 20004, MISSION = 4240301, FULL_CONDITION = 10001013;
  @TempDir java.nio.file.Path root;

  @BeforeEach
  void setup() throws Exception {
    missing.root = root;
    missing.setup();
    var row = fixture.gson.toJsonTree(HeartDialDefinitions.scripts.get(SCRIPT)).getAsJsonObject();
    row.add("StepList", JsonParser.parseString("[\"Missing\",\"Full\",\"Normal\"]"));
    row.addProperty("FullDialogueID", 2000406);
    HeartDialDefinitions.scripts.put(
        SCRIPT, fixture.gson.fromJson(row, HeartDialDefinitions.Script.class));
    HeartDialDefinitions.dialogues.put(
        2000406, new HeartDialDefinitions.Dialogue(2000406, List.of()));
    HeartDialDefinitions.scriptConditions
        .get(SCRIPT)
        .addProperty("FullConditionID", FULL_CONDITION);
    HeartDialDefinitions.conditions.put(
        FULL_CONDITION,
        JsonParser.parseString(
                "{\"ID\":10001013,\"FinishType\":\"FinishMainMission\",\"ParamUint1\":4240301}")
            .getAsJsonObject());
  }

  @AfterEach
  void cleanup() {
    missing.cleanup();
  }

  void completedMain() {
    var state = new QuestState(MISSION);
    state.advance(1, 1, 1);
    fixture.player.getGameplay().getMainMissions().put(MISSION, state);
  }

  int report(int dialogue) throws Exception {
    return Gameplay46.number(
        fixture.call(
            6358,
            "FinishEmotionDialoguePerformanceScRsp",
            fixture.wire(3, SCRIPT, 4, dialogue, 10, 999)),
        "retcode");
  }

  @Test
  void payingAnEmotionItemDoesNotSkipTheAuthoredFullMissionGate() throws Exception {
    var stock = missing.stock(3);
    assertEquals(0, missing.step());
    assertEquals(0, missing.submit(missing.payment(missing.ITEM, 1)));
    assertEquals(2, stock.getCount());
    assertEquals(1, missing.step());
    assertEquals(0, report(2000406));
    assertNotEquals(0, report(2000404));
    assertEquals(Retcode.REQ_PARA_INVALID, HeartDials.change(fixture.player, SCRIPT, 999, 3));
    assertNotEquals(0, missing.submit(missing.payment(missing.ITEM, 1)));
    assertEquals(2, stock.getCount());
  }

  @Test
  void theExactCompletedMainMissionReleasesNormalAndPreservesHistoricalFullDialogue()
      throws Exception {
    missing.stock(3);
    assertEquals(0, missing.submit(missing.payment(missing.ITEM, 1)));
    assertEquals(0, report(2000406));
    var wrong = new QuestState(MISSION + 1);
    wrong.advance(1, 1, 1);
    fixture.player.getGameplay().getMainMissions().put(MISSION + 1, wrong);
    fixture.player.getMissionManager().refresh();
    assertEquals(1, missing.step());
    completedMain();
    fixture.player.getMissionManager().refresh();
    assertEquals(4, missing.step());
    assertEquals(Retcode.SUCC, HeartDials.change(fixture.player, SCRIPT, 999, 3));
    assertEquals(0, report(2000403));
    assertTrue(fixture.player.getGameplay().getHeartDialReports().get(2000406).isApplied());
    assertTrue(HeartDials.validState(fixture.player));
  }

  @Test
  void unacceptedDoingCancelledAndMismatchedMissionRecordsCannotReleaseFull() throws Exception {
    missing.stock(3);
    assertEquals(0, missing.submit(missing.payment(missing.ITEM, 1)));
    assertEquals(1, missing.step());
    fixture.player.getGameplay().getMainMissions().put(MISSION, new QuestState(MISSION));
    fixture.player.getMissionManager().refresh();
    assertEquals(1, missing.step());
    completedMain();
    fixture.player.getGameplay().getCancelledMissions().add(MISSION);
    fixture.player.getMissionManager().refresh();
    assertEquals(1, missing.step());
    fixture.player.getGameplay().getCancelledMissions().remove(MISSION);
    var wrong = new QuestState(MISSION + 1);
    wrong.advance(1, 1, 1);
    fixture.player.getGameplay().getMainMissions().put(MISSION, wrong);
    fixture.player.getMissionManager().refresh();
    assertEquals(1, missing.step());
    assertEquals(0, report(2000406));
  }

  @Test
  void fullAndNormalStepNotificationsAreSavedOnceAndSurviveReload() throws Exception {
    missing.stock(3);
    fixture.packetNames.clear();
    fixture.packetStates.clear();
    assertEquals(0, missing.submit(missing.payment(missing.ITEM, 1)));
    var first = fixture.sent.get("HeartDialScriptChangeScNotify");
    var list = (List<?>) Gameplay46.value(first, "changed_script_info_list");
    assertEquals(
        1,
        ((Descriptors.EnumValueDescriptor)
                Gameplay46.value((DynamicMessage) list.getFirst(), "step"))
            .getNumber());
    long firstCount =
        fixture.packetNames.stream().filter("HeartDialScriptChangeScNotify"::equals).count();
    fixture.player.getMissionManager().refresh();
    fixture.player.getMissionManager().refresh();
    assertEquals(
        firstCount,
        fixture.packetNames.stream().filter("HeartDialScriptChangeScNotify"::equals).count());
    completedMain();
    fixture.player.getMissionManager().refresh();
    long secondCount =
        fixture.packetNames.stream().filter("HeartDialScriptChangeScNotify"::equals).count();
    assertEquals(firstCount + 1, secondCount);
    var normal = fixture.sent.get("HeartDialScriptChangeScNotify");
    assertEquals(
        4,
        ((Descriptors.EnumValueDescriptor)
                Gameplay46.value(
                    (DynamicMessage)
                        ((List<?>) Gameplay46.value(normal, "changed_script_info_list")).getFirst(),
                    "step"))
            .getNumber());
    Field state = emu.lunarcore.game.player.Player.class.getDeclaredField("gameplay");
    state.setAccessible(true);
    state.set(
        fixture.player,
        fixture.gson.fromJson(
            fixture.gson.toJson(fixture.player.getGameplay()), GameplayState.class));
    fixture.player.getMissionManager().refresh();
    assertEquals(
        secondCount,
        fixture.packetNames.stream().filter("HeartDialScriptChangeScNotify"::equals).count());
    assertEquals(4, missing.step());
  }

  @Test
  void anAlreadyFinishedGateDoesNotRepairMissingWithoutItsItem() throws Exception {
    var stock = missing.stock(3);
    completedMain();
    fixture.saved.clear();
    assertEquals(0, missing.step());
    assertTrue(fixture.saved.isEmpty());
    assertEquals(3, stock.getCount());
    assertEquals(0, missing.submit(missing.payment(missing.ITEM, 1)));
    assertEquals(4, missing.step());
    assertNotEquals(0, report(2000406));
    assertEquals(0, report(2000404));
    assertEquals(2, stock.getCount());
  }

  @Test
  void aFullSubMissionGateRequiresItsOwnCompletionAndANonCancelledParent() throws Exception {
    int sub = 424030101;
    HeartDialDefinitions.conditions
        .get(FULL_CONDITION)
        .addProperty("FinishType", "FinishSubMission");
    HeartDialDefinitions.conditions.get(FULL_CONDITION).addProperty("ParamUint1", sub);
    var config =
        fixture.gson.fromJson(
            "{\"ID\":424030101,\"MainMissionID\":4240301}",
            emu.lunarcore.data.config.MissionInfo.SubMissionInfo.class);
    GameplayDefinitions.subs.put(sub, config);
    try {
      missing.stock(3);
      assertEquals(0, missing.submit(missing.payment(missing.ITEM, 1)));
      assertEquals(1, missing.step());
      var state = new QuestState(sub);
      state.advance(1, 1, 1);
      fixture.player.getGameplay().getSubMissions().put(sub, state);
      fixture.player.getGameplay().getCancelledSubs().add(sub);
      assertEquals(1, missing.step());
      fixture.player.getGameplay().getCancelledSubs().remove(sub);
      fixture.player.getGameplay().getCancelledMissions().add(MISSION);
      assertEquals(1, missing.step());
      fixture.player.getGameplay().getCancelledMissions().remove(MISSION);
      fixture.player.getMissionManager().refresh();
      assertEquals(4, missing.step());
    } finally {
      GameplayDefinitions.subs.remove(sub);
    }
  }

  @Test
  void aSavedReleasedGateKeepsNormalHistoryIfItsOldMissionRecordIsLaterRemoved() throws Exception {
    missing.stock(3);
    assertEquals(0, missing.submit(missing.payment(missing.ITEM, 1)));
    assertEquals(0, report(2000406));
    completedMain();
    fixture.player.getMissionManager().refresh();
    assertEquals(Retcode.SUCC, HeartDials.change(fixture.player, SCRIPT, 999, 3));
    assertEquals(0, report(2000403));
    fixture.player.getGameplay().getMainMissions().remove(MISSION);
    fixture.player.getGameplay().getCancelledMissions().add(MISSION);
    var state = emu.lunarcore.game.player.Player.class.getDeclaredField("gameplay");
    state.setAccessible(true);
    state.set(
        fixture.player,
        fixture.gson.fromJson(
            fixture.gson.toJson(fixture.player.getGameplay()), GameplayState.class));
    assertEquals(4, missing.step());
    assertTrue(HeartDials.validState(fixture.player));
    assertEquals(1, fixture.player.getGameplay().getHeartDialReports().get(2000406).getStep());
    assertEquals(4, fixture.player.getGameplay().getHeartDialReports().get(2000403).getStep());
  }

  @Test
  void unprovedFullReportsAndUnknownFullConditionsAreRejected() throws Exception {
    assertNotEquals(0, report(2000406));
    fixture
        .player
        .getGameplay()
        .getHeartDialReports()
        .put(
            2000406,
            new GameplayState.HeartDialReport(
                SCRIPT, 2, fixture.PLANE, fixture.FLOOR, 47, 400005, Set.of(), 1));
    assertFalse(HeartDials.validState(fixture.player));
    fixture.player.getGameplay().getHeartDialReports().clear();
    var stock = missing.stock(3);
    HeartDialDefinitions.conditions.get(FULL_CONDITION).addProperty("ParamUint2", 7);
    assertNotEquals(0, missing.submit(missing.payment(missing.ITEM, 1)));
    assertTrue(fixture.player.getGameplay().getHeartDialPayments().isEmpty());
    assertEquals(3, stock.getCount());
  }

  @Test
  void concurrentMissionRefreshPublishesTheCompletedFullGateOnce() throws Exception {
    missing.stock(3);
    assertEquals(0, missing.submit(missing.payment(missing.ITEM, 1)));
    fixture.packetNames.clear();
    fixture.packetStates.clear();
    completedMain();
    var pool = java.util.concurrent.Executors.newFixedThreadPool(6);
    try {
      var jobs = new ArrayList<java.util.concurrent.Future<?>>();
      for (int i = 0; i < 6; i++)
        jobs.add(pool.submit(() -> fixture.player.getMissionManager().refresh()));
      for (var job : jobs) job.get(5, java.util.concurrent.TimeUnit.SECONDS);
      assertEquals(
          1, fixture.packetNames.stream().filter("HeartDialScriptChangeScNotify"::equals).count());
      assertEquals(4, missing.step());
      assertTrue(
          fixture.packetStates.getLast().getHeartDialPayments().get(SCRIPT).isNormalUnlocked());
      assertEquals(
          4, fixture.packetStates.getLast().getHeartDialPayments().get(SCRIPT).getNotifiedStep());
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void anAuthoredFullStepObjectiveAdvancesFromItsPaidStepBeforeTheMainGateCompletes()
      throws Exception {
    fixture.mission();
    var config =
        fixture.gson.fromJson(
            "{\"ID\":"
                + fixture.SUB
                + ",\"MainMissionID\":"
                + fixture.MAIN
                + ",\"TakeType\":\"Auto\",\"FinishType\":\"HeartDialScriptListStep\",\"ParamType\":\"ListContain\",\"ParamInt1\":1,\"ParamIntList\":[20004],\"Progress\":1}",
            emu.lunarcore.data.config.MissionInfo.SubMissionInfo.class);
    GameplayDefinitions.subs.put(fixture.SUB, config);
    missing.stock(3);
    fixture.player.getMissionManager().refresh();
    assertEquals(0, fixture.player.getMissionManager().sub(fixture.SUB).getProgress());
    assertEquals(0, missing.submit(missing.payment(missing.ITEM, 1)));
    assertEquals(1, missing.step());
    assertEquals(1, fixture.player.getMissionManager().sub(fixture.SUB).getProgress());
    assertEquals(0, fixture.player.getMissionManager().sub(fixture.SECOND).getProgress());
    assertFalse(fixture.player.getMissionManager().finished(MISSION));
  }

  @Test
  void aGateCompletedBeforeAuthenticationKeepsItsPublicationForTheActiveSession() throws Exception {
    missing.stock(3);
    assertEquals(0, missing.submit(missing.payment(missing.ITEM, 1)));
    fixture.packetNames.clear();
    fixture.packetStates.clear();
    fixture.session.setState(emu.lunarcore.server.packet.SessionState.WAITING_FOR_TOKEN);
    completedMain();
    fixture.player.getMissionManager().refresh();
    assertEquals(
        1, fixture.player.getGameplay().getHeartDialPayments().get(SCRIPT).getNotifiedStep());
    assertTrue(fixture.player.getGameplay().getHeartDialPayments().get(SCRIPT).isNormalUnlocked());
    assertTrue(fixture.packetNames.isEmpty());
    fixture.session.setState(emu.lunarcore.server.packet.SessionState.ACTIVE);
    fixture.player.getMissionManager().refresh();
    assertEquals(
        4, fixture.player.getGameplay().getHeartDialPayments().get(SCRIPT).getNotifiedStep());
    assertEquals(
        1, fixture.packetNames.stream().filter("HeartDialScriptChangeScNotify"::equals).count());
    fixture.player.getMissionManager().refresh();
    assertEquals(
        1, fixture.packetNames.stream().filter("HeartDialScriptChangeScNotify"::equals).count());
  }
}
