package org.hkrpg.server.protocol;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import emu.lunarcore.data.GameData;
import emu.lunarcore.data.excel.ActivityPanelExcel;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.game.quest.QuestState;
import emu.lunarcore.server.packet.Retcode;
import java.nio.file.Path;
import org.hkrpg.server.gameplay.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Native original completions; reward phases and the dependent panel are isolated fixtures. */
class TimelineCompletionHistory46Test {
  @TempDir Path root;
  final TimelineActivity46Test fixture = new TimelineActivity46Test();
  static final int DEPENDENT_PANEL = 799999;
  ActivityPanelExcel previousPanel;

  @BeforeEach
  void load() throws Exception {
    fixture.root = root;
    fixture.load();
    previousPanel = GameData.getActivityPanelExcelMap().get(DEPENDENT_PANEL);
  }

  @AfterEach
  void restore() throws Exception {
    GameData.getActivityPanelExcelMap().keySet().remove(DEPENDENT_PANEL);
    if (previousPanel != null) GameData.getActivityPanelExcelMap().add(previousPanel);
    fixture.restore();
  }

  QuestState complete(int id) throws Exception {
    fixture.activate(id);
    fixture.accepted(id);
    for (int i = 0; i < 3; i++) {
      assertEquals(0, fixture.arm(id));
      assertEquals(0, fixture.report(i % 2 == 0 ? "L0_Idle" : "L1_Break"));
    }
    var state = fixture.accepted(id);
    assertEquals(QuestState.Phase.FINISHED, state.getPhase());
    return state;
  }

  void dependent(int id) {
    var row = new JsonObject();
    row.addProperty("PanelID", DEPENDENT_PANEL);
    row.addProperty("UnlockConditions", "[QuestClose:" + id + "]");
    GameData.getActivityPanelExcelMap().add(fixture.gson.fromJson(row, ActivityPanelExcel.class));
  }

  void reload() throws Exception {
    var field = Player.class.getDeclaredField("gameplay");
    field.setAccessible(true);
    field.set(
        fixture.fixture.actor,
        fixture.gson.fromJson(
            fixture.gson.toJson(fixture.fixture.actor.getGameplay()), GameplayState.class));
  }

  @ParameterizedTest
  @ValueSource(ints = {6029846, 6029847, 6029848, 6029849})
  void originalCompletionRemainsEvidenceAfterOperatorWindowEnds(int id) throws Exception {
    complete(id);
    assertTrue(QuestCompletionRecords.completed(fixture.fixture.actor, id));
    GameplayDefinitions.windows.clear();
    assertNull(TimelineActivityQuests.state(fixture.fixture.actor, id));
    assertTrue(
        QuestCompletionRecords.completed(fixture.fixture.actor, id),
        "Expired presentation window erased an original completed observation");
    assertEquals(
        Retcode.QUEST_NOT_ACCEPT, fixture.fixture.actor.getQuestManager().claim(id).retcode());
  }

  @ParameterizedTest
  @ValueSource(ints = {6029846, 6029847, 6029848, 6029849})
  void closedOwnedActivityDoesNotNeedALegacyDuplicate(int id) throws Exception {
    var state = complete(id);
    state.reserveReward();
    state.closeReward();
    dependent(id);
    assertFalse(fixture.fixture.actor.getQuestStates().containsKey(id));
    assertTrue(
        ActivityAccess.unlocked(fixture.fixture.actor, DEPENDENT_PANEL),
        "QuestClose ignored the validated activity-owned record");
  }

  @ParameterizedTest
  @ValueSource(ints = {6029846, 6029847, 6029848, 6029849})
  void restoredClosedHistoryUnlocksAfterExpiryWithoutReopeningClaims(int id) throws Exception {
    var state = complete(id);
    state.reserveReward();
    state.closeReward();
    dependent(id);
    GameplayDefinitions.windows.clear();
    reload();
    assertTrue(QuestCompletionRecords.completed(fixture.fixture.actor, id));
    assertTrue(ActivityAccess.unlocked(fixture.fixture.actor, DEPENDENT_PANEL));
    assertEquals(
        Retcode.QUEST_NOT_ACCEPT, fixture.fixture.actor.getQuestManager().claim(id).retcode());
  }

  @ParameterizedTest
  @ValueSource(ints = {6029846, 6029847, 6029848, 6029849})
  void unclaimedAndReservedRewardsDoNotSatisfyQuestClose(int id) throws Exception {
    var state = complete(id);
    dependent(id);
    assertTrue(QuestCompletionRecords.completed(fixture.fixture.actor, id));
    assertFalse(ActivityAccess.unlocked(fixture.fixture.actor, DEPENDENT_PANEL));
    state.reserveReward();
    assertTrue(QuestCompletionRecords.completed(fixture.fixture.actor, id));
    assertFalse(ActivityAccess.unlocked(fixture.fixture.actor, DEPENDENT_PANEL));
  }

  @ParameterizedTest
  @ValueSource(ints = {6029846, 6029847, 6029848, 6029849})
  void borrowedClosedLegacyRecordCannotReplaceAnActivityProof(int id) throws Exception {
    fixture.activate(id);
    fixture.accepted(id);
    var fake = new QuestState(id);
    fake.advance(3, 3, 1);
    fake.reserveReward();
    fake.closeReward();
    fixture.fixture.actor.getQuestStates().put(id, fake);
    dependent(id);
    assertFalse(QuestCompletionRecords.completed(fixture.fixture.actor, id));
    assertFalse(ActivityAccess.unlocked(fixture.fixture.actor, DEPENDENT_PANEL));
  }

  @ParameterizedTest
  @ValueSource(ints = {6029846, 6029847, 6029848, 6029849})
  void corruptReceiptStillFailsAfterTheWindowExpires(int id) throws Exception {
    var state = complete(id);
    state.reserveReward();
    state.closeReward();
    dependent(id);
    var saved = fixture.gson.toJsonTree(fixture.fixture.actor.getGameplay()).getAsJsonObject();
    saved
        .getAsJsonObject("timelineActivityQuests")
        .getAsJsonObject("receipts")
        .getAsJsonArray(Integer.toString(id))
        .get(0)
        .getAsJsonObject()
        .addProperty("acceptance", "wrong");
    var field = Player.class.getDeclaredField("gameplay");
    field.setAccessible(true);
    field.set(fixture.fixture.actor, fixture.gson.fromJson(saved, GameplayState.class));
    GameplayDefinitions.windows.clear();
    assertFalse(QuestCompletionRecords.completed(fixture.fixture.actor, id));
    assertFalse(ActivityAccess.unlocked(fixture.fixture.actor, DEPENDENT_PANEL));
  }

  @ParameterizedTest
  @ValueSource(ints = {6029846, 6029847, 6029848, 6029849})
  void invalidSourcePrerequisiteCannotBorrowHistoricalCompletion(int id) throws Exception {
    var state = complete(id);
    state.reserveReward();
    state.closeReward();
    dependent(id);
    int sub =
        id == 6029846
            ? 104010102
            : id == 6029847 ? 104011107 : id == 6029848 ? 104010602 : 104010966;
    fixture.fixture.actor.getGameplay().getCancelledSubs().add(sub);
    GameplayDefinitions.windows.clear();
    assertFalse(QuestCompletionRecords.completed(fixture.fixture.actor, id));
    assertFalse(ActivityAccess.unlocked(fixture.fixture.actor, DEPENDENT_PANEL));
  }
}
