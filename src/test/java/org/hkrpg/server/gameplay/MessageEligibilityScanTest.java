package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import emu.lunarcore.data.config.MissionInfo;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.game.quest.QuestState;
import java.util.*;
import org.junit.jupiter.api.*;

/** Count predicate work independently of machine speed while checking real message activation. */
class MessageEligibilityScanTest {
  static final int MAIN = 199912, FIRST = 1999120;
  final Map<Integer, GameplayDefinitions.MessageSection> sections = new HashMap<>(GameplayDefinitions.messageSections);
  final Map<Integer, GameplayDefinitions.MessageGroup> groups = new HashMap<>(GameplayDefinitions.messageGroups);
  final Map<Integer, GameplayDefinitions.MessageItem> items = new HashMap<>(GameplayDefinitions.messageItems);
  final Map<Integer, MissionInfo.SubMissionInfo> subs = new HashMap<>(GameplayDefinitions.subs);
  final Player player = new Player() { @Override public void save() {} };

  static class Goal extends MissionInfo.SubMissionInfo {
    int predicateReads;
    @Override public String getFinishType() { predicateReads++; return super.getFinishType(); }
  }

  @BeforeEach void prepare() {
    GameplayDefinitions.messageSections.clear(); GameplayDefinitions.messageGroups.clear();
    GameplayDefinitions.messageItems.clear(); GameplayDefinitions.subs.clear();
    for (int id = FIRST; id < FIRST + 12; id++) {
      GameplayDefinitions.messageSections.put(id, new GameplayDefinitions.MessageSection(id, false, 0, List.of(id * 10)));
      GameplayDefinitions.messageGroups.put(id, new GameplayDefinitions.MessageGroup(id, MAIN, 0, List.of(id)));
      GameplayDefinitions.messageItems.put(id * 10, new GameplayDefinitions.MessageItem(id * 10, id, "Player", "Text", List.of()));
    }
  }

  @AfterEach void restore() {
    GameplayDefinitions.messageSections.clear(); GameplayDefinitions.messageSections.putAll(sections);
    GameplayDefinitions.messageGroups.clear(); GameplayDefinitions.messageGroups.putAll(groups);
    GameplayDefinitions.messageItems.clear(); GameplayDefinitions.messageItems.putAll(items);
    GameplayDefinitions.subs.clear(); GameplayDefinitions.subs.putAll(subs);
  }

  Goal goal() {
    var goal = new Goal(); goal.setId(FIRST); goal.setMainMissionID(MAIN);
    goal.setFinishType("MessageSectionFinish"); goal.setParamType("Equal"); goal.setParamInt1(FIRST);
    GameplayDefinitions.subs.put(goal.getId(), goal);
    return goal;
  }

  @Test void inactiveMissionsDoNotEvaluateMessagePredicatesForEverySection() {
    var goal = goal();
    assertFalse(new MessageManager(player).activateEligible());
    assertTrue(player.getGameplay().getPhoneSections().isEmpty());
    assertEquals(0, goal.predicateReads, "Inactive goals cannot activate any section");
  }

  @Test void activePredicatesAreEvaluatedOnceAndEligibilityIsRebuiltOnTheNextCall() {
    var goal = goal();
    player.getGameplay().getMainMissions().put(MAIN, new QuestState(MAIN));
    player.getGameplay().getSubMissions().put(FIRST, new QuestState(FIRST));
    var manager = new MessageManager(player);
    assertTrue(manager.activateEligible());
    assertEquals(Set.of(FIRST), player.getGameplay().getPhoneSections().keySet());
    assertTrue(goal.predicateReads <= 3, "One activation pass must not recheck each goal for every section: " + goal.predicateReads);
    player.getGameplay().getPhoneSections().clear();
    player.getGameplay().getCancelledSubs().add(FIRST);
    goal.predicateReads = 0;
    assertFalse(manager.activateEligible());
    assertEquals(0, goal.predicateReads);
    player.getGameplay().getCancelledSubs().clear();
    assertTrue(manager.activateEligible());
    assertEquals(Set.of(FIRST), player.getGameplay().getPhoneSections().keySet());
  }
}
