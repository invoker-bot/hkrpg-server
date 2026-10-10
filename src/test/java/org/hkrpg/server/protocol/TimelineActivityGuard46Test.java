package org.hkrpg.server.protocol;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import emu.lunarcore.game.quest.QuestState;
import emu.lunarcore.server.packet.Retcode;
import java.nio.file.*;
import java.util.*;
import org.hkrpg.server.gameplay.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Pending observations cannot borrow another source, scene, window or acceptance. */
class TimelineActivityGuard46Test {
  @TempDir Path root;
  final TimelineActivity46Test fixture = new TimelineActivity46Test();

  @BeforeEach
  void load() throws Exception {
    fixture.root = root;
    fixture.load();
  }

  @AfterEach
  void restore() throws Exception {
    fixture.restore();
  }

  void activate(int id) {
    fixture.activate(id);
    fixture.accepted(id);
  }

  int progress(int id) {
    return fixture
        .fixture
        .actor
        .getGameplay()
        .getTimelineActivityQuests()
        .getQuests()
        .get(id)
        .getProgress();
  }

  @ParameterizedTest
  @ValueSource(ints = {6029846, 6029847, 6029848, 6029849})
  void changingTheActiveWindowDiscardsThePair(int id) throws Exception {
    activate(id);
    assertEquals(0, fixture.arm(id));
    GameplayDefinitions.windows.clear();
    assertEquals(0, fixture.report("L0_Idle"));
    assertEquals(0, progress(id));
    GameplayDefinitions.windows.add(
        new GameplayDefinitions.Window(30013, 3001301, 1, Long.MAX_VALUE));
    assertEquals(0, fixture.report("L1_Break"));
    assertEquals(0, fixture.accepted(id).getProgress());
  }

  @ParameterizedTest
  @ValueSource(ints = {6029846, 6029847, 6029848, 6029849})
  void originalSubMissionPrerequisiteMustRemainValid(int id) throws Exception {
    activate(id);
    assertEquals(0, fixture.arm(id));
    int sub =
        id == 6029846
            ? 104010102
            : id == 6029847 ? 104011107 : id == 6029848 ? 104010602 : 104010966;
    fixture.fixture.actor.getGameplay().getCancelledSubs().add(sub);
    assertEquals(0, fixture.report("L0_Idle"));
    assertEquals(0, progress(id));
    assertNull(fixture.state(id));
  }

  @ParameterizedTest
  @ValueSource(ints = {6029846, 6029847, 6029848, 6029849})
  void rebuildingTheSceneCannotConsumeAnOldMarker(int id) throws Exception {
    activate(id);
    assertEquals(0, fixture.arm(id));
    fixture.rebuild();
    assertEquals(0, fixture.report("L0_Idle"));
    assertEquals(0, fixture.accepted(id).getProgress());
    assertEquals(0, fixture.arm(id));
    assertEquals(0, fixture.report("L1_Break"));
    assertEquals(1, fixture.accepted(id).getProgress());
  }

  @ParameterizedTest
  @ValueSource(ints = {6029846, 6029847, 6029848, 6029849})
  void replacingTheCompleteGroupSourceAfterArmingDoesNotCount(int id) throws Exception {
    activate(id);
    assertEquals(0, fixture.arm(id));
    var altered = fixture.selected.getAsJsonObject("definition").deepCopy();
    altered.addProperty("GroupName", "Changed source");
    fixture.write(fixture.selected.get("path").getAsString(), altered);
    assertEquals(0, fixture.report("L0_Idle"));
    assertEquals(0, progress(id));
    assertNotEquals(0, fixture.arm(id));
  }

  @ParameterizedTest
  @ValueSource(ints = {6029846, 6029847, 6029848, 6029849})
  void aLegacyGlobalCompletedRecordCannotReplaceTheOwnedActivityRecord(int id) throws Exception {
    activate(id);
    var fake = new QuestState(id);
    fake.advance(3, 3, 1);
    fixture.fixture.actor.getQuestStates().put(id, fake);
    assertEquals(0, fixture.accepted(id).getProgress());
    assertEquals(
        1,
        fixture.fixture.actor.getQuestManager().states().stream()
            .filter(q -> q.getQuestId() == id)
            .count());
    assertEquals(
        Retcode.QUEST_NOT_FINISH, fixture.fixture.actor.getQuestManager().claim(id).retcode());
  }

  @ParameterizedTest
  @ValueSource(ints = {6029846, 6029847, 6029848, 6029849})
  void forgedSavedProgressWithoutThreeReceiptsIsUnavailable(int id) throws Exception {
    activate(id);
    fixture.accepted(id).advance(3, 3, 1);
    assertNull(fixture.state(id));
    assertNotEquals(0, fixture.arm(id));
    assertEquals(
        Retcode.QUEST_NOT_ACCEPT, fixture.fixture.actor.getQuestManager().claim(id).retcode());
  }

  @ParameterizedTest
  @ValueSource(ints = {6029846, 6029847, 6029848, 6029849})
  void originalSourceRowsCannotBeChangedToAutoFinishTheCounter(int id) throws Exception {
    activate(id);
    var rows =
        fixture
            .source
            .getAsJsonObject("records")
            .getAsJsonArray("ExcelOutput/FinishWay.json")
            .deepCopy();
    for (var row : rows)
      if (row.getAsJsonObject().get("ID").getAsInt() == id)
        row.getAsJsonObject().addProperty("Progress", 1);
    fixture.write("ExcelOutput/FinishWay.json", rows);
    assertNull(fixture.state(id));
    assertNotEquals(0, fixture.arm(id));
  }

  @ParameterizedTest
  @ValueSource(ints = {6029846, 6029847, 6029848, 6029849})
  void unknownDuplicateAndOverflowingWireFieldsCannotArmTheCounter(int id) throws Exception {
    activate(id);
    byte[] original = fixture.finishRequest(id, fixture.group.getId(), fixture.prop().getInstId());
    for (byte[] suffix :
        new byte[][] {{8, 1}, {56, 1}, {88, 1}, {64, -128, -128, -128, -128, 16}, {56, -128}}) {
      byte[] invalid = Arrays.copyOf(original, original.length + suffix.length);
      System.arraycopy(suffix, 0, invalid, original.length, suffix.length);
      assertNotEquals(0, fixture.finish(invalid));
    }
    assertEquals(0, fixture.report("L0_Idle"));
    assertEquals(0, fixture.accepted(id).getProgress());
  }
}
