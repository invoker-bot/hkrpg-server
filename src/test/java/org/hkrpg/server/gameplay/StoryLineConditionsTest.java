package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.Gson;
import emu.lunarcore.data.config.MissionInfo;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.game.quest.QuestState;
import java.util.*;
import org.junit.jupiter.api.*;

class StoryLineConditionsTest {
  static final int MAIN = 199939, START = 19993901, END = 19993902, OTHER = 19993903;
  Map<Integer, MissionInfo.SubMissionInfo> oldDefinitions;
  Player player;

  @BeforeEach
  void prepare() {
    oldDefinitions = new HashMap<>(GameplayDefinitions.subs);
    GameplayDefinitions.subs.clear();
    player = new Player();
    player.getGameplay().getMainMissions().put(MAIN, new QuestState(MAIN));
    var gson = new Gson();
    for (int id : List.of(START, END, OTHER))
      GameplayDefinitions.subs.put(
          id,
          gson.fromJson(
              "{\"ID\":" + id + ",\"MainMissionID\":" + MAIN + "}",
              MissionInfo.SubMissionInfo.class));
  }

  @AfterEach
  void restore() {
    GameplayDefinitions.subs.clear();
    GameplayDefinitions.subs.putAll(oldDefinitions);
  }

  String between(int a, int b) {
    return "[BetweenSubMission:" + a + "," + b + "]";
  }

  QuestState take(int id) {
    var s = new QuestState(id);
    player.getGameplay().getSubMissions().put(id, s);
    return s;
  }

  @Test
  void anIntervalOpensOnlyAfterTheActualStartAndClosesOnActualEndCompletion() {
    String expr = between(START, END);
    assertFalse(StoryLineConditions.matches(player, expr));
    var start = take(START);
    assertTrue(StoryLineConditions.matches(player, expr));
    start.advance(1, 1, 1);
    assertTrue(StoryLineConditions.matches(player, expr));
    take(END).advance(1, 1, 2);
    assertFalse(StoryLineConditions.matches(player, expr));
    assertEquals(0, player.getGameplay().getRewards().size());
  }

  @Test
  void identicalEndpointsMeanTheActualMissionIsDoing() {
    String expr = between(START, START);
    assertFalse(StoryLineConditions.matches(player, expr));
    var state = take(START);
    assertTrue(StoryLineConditions.matches(player, expr));
    state.advance(1, 1, 1);
    assertFalse(StoryLineConditions.matches(player, expr));
    state.reserveReward();
    assertFalse(StoryLineConditions.matches(player, expr));
    state.closeReward();
    assertFalse(StoryLineConditions.matches(player, expr));
  }

  @Test
  void everyOrBranchIsParsedBeforeAnyBranchMayPass() {
    take(OTHER);
    assertTrue(
        StoryLineConditions.matches(player, between(START, END) + "|" + between(OTHER, OTHER)));
    assertFalse(StoryLineConditions.matches(player, between(OTHER, OTHER) + "|[Unknown:1,2]"));
    assertFalse(StoryLineConditions.matches(player, between(OTHER, OTHER) + "|"));
    assertFalse(StoryLineConditions.matches(player, "|" + between(OTHER, OTHER)));
  }

  @Test
  void cancellationOrMissingDefinitionCannotUnlockAStoryFloor() {
    take(START);
    String expr = between(START, END);
    assertTrue(StoryLineConditions.matches(player, expr));
    player.getGameplay().getCancelledSubs().add(START);
    assertFalse(StoryLineConditions.matches(player, expr));
    player.getGameplay().getCancelledSubs().clear();
    player.getGameplay().getCancelledSubs().add(END);
    assertFalse(StoryLineConditions.matches(player, expr));
    player.getGameplay().getCancelledSubs().clear();
    player.getGameplay().getCancelledMissions().add(MAIN);
    assertFalse(StoryLineConditions.matches(player, expr));
    player.getGameplay().getCancelledMissions().clear();
    GameplayDefinitions.subs.remove(END);
    assertFalse(StoryLineConditions.matches(player, expr));
  }

  @Test
  void orphanProgressAndMalformedIntegerAliasesDoNotBecomeStoryConditions() {
    take(START);
    assertTrue(StoryLineConditions.matches(player, between(START, END)));
    player.getGameplay().getMainMissions().clear();
    assertFalse(StoryLineConditions.matches(player, between(START, END)));
    for (String expr :
        Arrays.asList(
            null,
            "",
            " ",
            "[BetweenSubMission:0,1]",
            "[BetweenSubMission:-1,1]",
            "[BetweenSubMission:019993901,19993902]",
            "[BetweenSubMission:4294967297,1]",
            "[BetweenSubMission:19993901.0,19993902]",
            "[BetweenSubMission:19993901,19993902]junk",
            between(START, END) + "&" + between(START, END)))
      assertFalse(StoryLineConditions.matches(player, expr));
  }

  @Test
  void orphanCompletionAndMismatchedSavedKeysCannotOpenAnInterval() {
    take(START);
    GameplayDefinitions.subs.put(
        END,
        new Gson()
            .fromJson(
                "{\"ID\":" + END + ",\"MainMissionID\":199940}", MissionInfo.SubMissionInfo.class));
    take(END).advance(1, 1, 1);
    assertFalse(StoryLineConditions.matches(player, between(START, END)));
    player.getGameplay().getSubMissions().remove(END);
    assertTrue(StoryLineConditions.matches(player, between(START, END)));
    player.getGameplay().getMainMissions().put(MAIN, new QuestState(199940));
    assertFalse(StoryLineConditions.matches(player, between(START, END)));
  }

  @Test
  void checkingAConditionDoesNotCreateMissionStatesOrChangeRewardPhases() {
    var start = take(START);
    start.advance(1, 1, 1);
    start.reserveReward();
    var oldSubs = new HashMap<>(player.getGameplay().getSubMissions());
    assertTrue(StoryLineConditions.matches(player, between(START, END)));
    assertEquals(oldSubs, player.getGameplay().getSubMissions());
    assertFalse(player.getGameplay().getSubMissions().containsKey(END));
    assertEquals(QuestState.Phase.CLAIMING, start.getPhase());
  }
}
