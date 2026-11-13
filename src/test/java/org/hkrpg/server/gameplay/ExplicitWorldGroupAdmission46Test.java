package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import emu.lunarcore.game.enums.PlaneType;
import emu.lunarcore.game.quest.QuestState;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Original bed interaction and source gates, with explicitly constructed player histories. */
class ExplicitWorldGroupAdmission46Test {
  final CheapRoomDreamTest46Test source = new CheapRoomDreamTest46Test();

  @BeforeEach void prepare() throws Exception { source.prepare(); }
  @AfterEach void restore() { source.restore(); }

  void accept() {
    source.player.getGameplay().getMainMissions().put(1010601, new QuestState(1010601));
    source.player.getGameplay().getSubMissions().put(101060104, new QuestState(101060104));
  }

  @ParameterizedTest
  @ValueSource(strings = {"unaccepted", "doing", "finished", "cancelled", "owner-finished", "owner-cancelled"})
  void explicitStoryLoadUsesTheSameAuthoredAdmissionAsSceneConstruction(String history) {
    accept();
    var gameplay = source.player.getGameplay();
    switch (history) {
      case "unaccepted" -> gameplay.getSubMissions().clear();
      case "finished" -> gameplay.getSubMissions().get(101060104).advance(1, 1, 100);
      case "cancelled" -> gameplay.getCancelledSubs().add(101060104);
      case "owner-finished" -> gameplay.getMainMissions().get(1010601).advance(1, 1, 100);
      case "owner-cancelled" -> gameplay.getCancelledMissions().add(1010601);
      case "doing" -> {}
      default -> fail(history);
    }
    var scene = source.scene();
    scene.loadGroup(9);
    scene.loadGroup(source.groups.get(9));
    assertEquals(history.equals("doing"), scene.getLoadedGroups().contains(9));
    assertEquals(history.equals("doing") ? 1 : 0,
        scene.getEntities().values().stream().filter(e -> e.getGroupId() == 9).count());
    assertTrue(scene.getLoadedGroups().contains(2), "The normal exit remains");
    assertEquals(0, source.player.saves);
  }

  @Test
  void aLateLoadCannotRecreateTheStoryPropAfterTheNormalUnload() {
    accept();
    var scene = source.scene();
    assertTrue(scene.getLoadedGroups().contains(9));
    int oldEntity = scene.getEntities().values().stream().filter(e -> e.getGroupId() == 9)
        .findFirst().orElseThrow().getEntityId();
    source.player.getGameplay().getSubMissions().get(101060104).advance(1, 1, 100);
    scene.refreshMissionGroups();
    assertFalse(scene.getLoadedGroups().contains(9));
    scene.loadGroup(9);
    scene.loadGroup(source.groups.get(9));
    assertFalse(scene.getLoadedGroups().contains(9));
    assertNull(scene.getEntityById(oldEntity));
    assertTrue(scene.getEntities().values().stream().noneMatch(e -> e.getGroupId() == 9));
    assertEquals(0, source.player.saves);
  }

  @Test
  void thisWorldGateDoesNotReplaceAnInstanceLoadersOwnGroupSelection() throws Exception {
    // Explicit synthetic instance scope; the source prop and mission group stay unchanged.
    DailyMissionAcceptance46Test.set(source.plane, "planeType", PlaneType.Raid);
    var scene = source.scene();
    scene.loadGroup(9);
    assertTrue(scene.getLoadedGroups().contains(9));
  }
}
