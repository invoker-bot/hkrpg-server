package org.hkrpg.server.protocol;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import emu.lunarcore.game.quest.QuestState;
import emu.lunarcore.server.packet.Retcode;
import org.hkrpg.server.gameplay.MissionSceneEntrances;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Full original hotel script; preceding dialogue is a fixture, not a played performance. */
class LiteralSceneTransfer46Test {
  final StorySceneTransfer46Test f = new StorySceneTransfer46Test();
  static final long START = 1_800_000_000_000L;

  @BeforeEach void prepare() throws Exception { f.prepare(); }
  @AfterEach void restore() { f.cleanup(); }

  JsonObject script() {
    return JsonParser.parseString(f.sub.getTask().getSourceDefinition()).getAsJsonObject();
  }

  @Test void hotelInitializationTransferUsesTheOriginalRoomEntrance() throws Exception {
    f.setup(101020202);
    assertTrue(MissionSceneEntrances.allows(f.f.actor,
        emu.lunarcore.data.GameData.getMapEntranceExcelMap().get(f.entry), 0));
    f.f.enter(f.entry, 0, Retcode.SUCC);
    f.arrived();
  }

  @Test void hotelRecoveryKeepsTheNextInteractionPending() throws Exception {
    f.setup(101020202); f.online();
    var source = f.f.actor.getScene();
    f.f.actor.onTick(START, 1000);
    f.f.actor.onTick(START + 14_000, 1000);
    assertSame(source, f.f.actor.getScene());
    f.f.actor.onTick(START + 16_000, 1000);
    f.arrived();
    var next = f.f.actor.getGameplay().getSubMissions().get(101020203);
    assertNotNull(next);
    assertEquals(QuestState.Phase.DOING, next.getPhase());
  }

  @Test void failedHotelSaveLeavesTheOriginalSceneAndCanRetry() throws Exception {
    f.setup(101020202); f.online();
    var source = f.f.actor.getScene();
    var before = f.f.gson.toJson(f.f.actor.getGameplay());
    f.f.actor.onTick(START, 1000);
    f.f.actor.failSave = true;
    assertThrows(IllegalStateException.class, () -> f.f.actor.onTick(START + 16_000, 1000));
    assertSame(source, f.f.actor.getScene());
    assertEquals(before, f.f.gson.toJson(f.f.actor.getGameplay()));
    f.f.actor.failSave = false;
    f.f.actor.onTick(START + 17_000, 1000);
    f.f.actor.onTick(START + 33_000, 1000);
    f.arrived();
  }

  @ParameterizedTest @ValueSource(ints = {100040113, 101000205, 101020202, 101060105})
  void repeatedClientTransferRetainsTheStoryAnchorWithoutSavingOrPushingAgain(int id) throws Exception {
    f.setup(id); f.online();
    f.f.enter(f.entry, 0, Retcode.SUCC);
    f.arrived();
    var scene = f.f.actor.getScene();
    var position = new emu.lunarcore.util.Position(f.f.actor.getPos());
    int saves = f.f.actor.saves;
    f.f.enter(f.entry, 0, Retcode.SUCC);
    assertSame(scene, f.f.actor.getScene());
    assertEquals(position, f.f.actor.getPos());
    assertEquals(saves, f.f.actor.saves);
  }

  @Test void replacingTheMainAcceptanceStartsANewGracePeriod() throws Exception {
    f.setup(100040113); f.online();
    var scene = f.f.actor.getScene();
    f.f.actor.onTick(START, 1000);
    f.f.actor.getGameplay().getMainMissions().put(f.sub.getMainMissionID(),
        new QuestState(f.sub.getMainMissionID()));
    f.f.actor.onTick(START + 16_000, 1000);
    assertSame(scene, f.f.actor.getScene());
    f.f.actor.onTick(START + 32_000, 1000);
    f.arrived();
  }

  @Test void replacingTheOriginalScriptStartsANewGracePeriod() throws Exception {
    f.setup(100040113); f.online();
    var scene = f.f.actor.getScene();
    f.f.actor.onTick(START, 1000);
    var changed = script();
    changed.getAsJsonArray("OnStartSequece").get(0).getAsJsonObject().remove("Order");
    f.sub.getTask().retainSourceDefinition(changed);
    f.f.actor.onTick(START + 16_000, 1000);
    assertSame(scene, f.f.actor.getScene());
    f.f.actor.onTick(START + 32_000, 1000);
    f.arrived();
  }

  @ParameterizedTest @ValueSource(strings = {"loop", "second-phase", "extra-task", "dynamic", "wrong-floor"})
  void initializationRecoveryRejectsUnprovedOrAdditionalWork(String change) throws Exception {
    f.setup(101020202); f.online();
    var changed = script();
    var sequence = changed.getAsJsonArray("OnInitSequece").get(0).getAsJsonObject();
    var task = sequence.getAsJsonArray("TaskList").get(0).getAsJsonObject();
    switch (change) {
      case "loop" -> sequence.addProperty("IsLoop", true);
      case "second-phase" -> changed.getAsJsonArray("OnStartSequece").add(sequence.deepCopy());
      case "extra-task" -> sequence.getAsJsonArray("TaskList").add(task.deepCopy());
      case "dynamic" -> task.addProperty("EntranceID", Integer.toString(f.entry));
      case "wrong-floor" -> task.addProperty("EntranceID", 1010101);
      default -> fail(change);
    }
    f.sub.getTask().retainSourceDefinition(changed);
    var scene = f.f.actor.getScene();
    f.f.actor.onTick(START, 1000);
    f.f.actor.onTick(START + 30_000, 1000);
    assertSame(scene, f.f.actor.getScene());
    assertEquals(QuestState.Phase.DOING, f.f.actor.getMissionManager().sub(f.sub.getId()).getPhase());
  }

  @Test void hotelRecoveryCannotSkipThePreviousDialogue() throws Exception {
    f.setup(101020202); f.online();
    f.f.actor.getGameplay().getSubMissions().put(101020201, new QuestState(101020201));
    var scene = f.f.actor.getScene();
    f.f.actor.onTick(START, 1000); f.f.actor.onTick(START + 30_000, 1000);
    assertSame(scene, f.f.actor.getScene());
    OpeningSceneTransfer46Test.field(f.f.actor, "loggedIn", false);
    assertFalse(MissionSceneEntrances.resumeOnLogin(f.f.actor, MissionSceneEntrances.captureLogin(f.f.actor)));
  }
}
