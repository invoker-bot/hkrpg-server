package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import emu.lunarcore.data.config.MissionInfo;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.game.quest.QuestState;
import emu.lunarcore.server.packet.Retcode;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class StoryActionArithmeticTest {
  private static final int MAIN = 19996690, ROOT = 199966901, CHILD = 199966902;
  private static final String KEY = "mission:" + MAIN + ":count";
  private final Gson gson = new Gson();
  private final GameplayProgressTest fixture = new GameplayProgressTest();

  static class SavedPlayer extends GameplayProgressTest.TestPlayer {
    int saves;
    GameplayState document;

    public void save() {
      saves++;
      document = new Gson().fromJson(new Gson().toJson(getGameplay()), GameplayState.class);
    }
  }

  @AfterEach
  void cleanup() {
    fixture.reset();
  }

  private JsonObject variable(String type, String name, int value) {
    var action = new JsonObject();
    action.addProperty("FinishActionType", type);
    var strings = new JsonArray();
    strings.add(name);
    strings.add(Integer.toString(value));
    action.add("FinishActionParaString", strings);
    return action;
  }

  private JsonObject finishChild(int... ids) {
    var action = new JsonObject();
    action.addProperty("FinishActionType", "delSubMission");
    var numbers = new JsonArray();
    for (int id : ids) numbers.add(id);
    action.add("FinishActionPara", numbers);
    return action;
  }

  private JsonObject definition(int id, JsonObject... actions) {
    var sub = new JsonObject();
    sub.addProperty("ID", id);
    sub.addProperty("MainMissionID", MAIN);
    sub.addProperty("TakeType", "Auto");
    sub.addProperty("FinishType", id == ROOT ? "Talk" : "Unknown");
    sub.addProperty("ParamStr1", "arithmetic");
    var list = new JsonArray();
    for (var action : actions) list.add(action);
    sub.add("FinishActionList", list);
    return sub;
  }

  private MissionInfo.SubMissionInfo action(JsonObject... actions) {
    return gson.fromJson(definition(ROOT, actions), MissionInfo.SubMissionInfo.class);
  }

  private SavedPlayer graph(JsonObject root, JsonObject child) {
    var subs = new JsonArray();
    subs.add(root);
    subs.add(child);
    fixture.mission(MAIN, "Manual", Integer.toString(ROOT), subs.toString());
    var player = new SavedPlayer();
    assertEquals(Retcode.SUCC, player.getMissionManager().accept(MAIN));
    player.saves = 0;
    return player;
  }

  private void rejected(SavedPlayer p, MissionInfo.SubMissionInfo sub) {
    var before = new HashMap<>(p.getGameplay().getStoryValues());
    var actions = new StoryActions(p);
    assertEquals(Retcode.MISSION_FINISH_WAY_NOT_MATCH, actions.validate(sub));
    assertEquals(before, p.getGameplay().getStoryValues());
    assertEquals(
        Retcode.MISSION_FINISH_WAY_NOT_MATCH, assertDoesNotThrow(() -> actions.execute(sub)));
    assertEquals(before, p.getGameplay().getStoryValues());
    assertTrue(p.getGameplay().getStoryActions().isEmpty());
    assertEquals(0, p.saves);
  }

  @ParameterizedTest
  @CsvSource({"2147483647,1", "-2147483648,-1", "2147483646,2", "-2147483647,-2"})
  void overflowingSingleAdditionRejectsBeforeReservation(int current, int delta) {
    var p = new SavedPlayer();
    p.getGameplay().getStoryValues().put(KEY, current);
    rejected(p, action(variable("AddCustomValue", "count", delta)));
  }

  @ParameterizedTest
  @ValueSource(ints = {-1, 1})
  void wholeSequencePreflightUsesTheEarlierSetWithoutApplyingIt(int direction) {
    var p = new SavedPlayer();
    p.getGameplay().getStoryValues().put(KEY, 17);
    int limit = direction == 1 ? Integer.MAX_VALUE : Integer.MIN_VALUE;
    rejected(
        p,
        action(
            variable("SetCustomValue", "early", 1),
            variable("SetCustomValue", "count", limit),
            variable("AddCustomValue", "count", direction)));
  }

  @Test
  void nestedChildAdditionIncludesTheParentEarlierAddition() {
    var p =
        graph(
            definition(ROOT, variable("AddCustomValue", "count", 1), finishChild(CHILD)),
            definition(CHILD, variable("AddCustomValue", "count", 1)));
    p.getGameplay().getStoryValues().put(KEY, Integer.MAX_VALUE - 1);
    rejected(p, GameplayDefinitions.subs.get(ROOT));
    assertEquals(QuestState.Phase.DOING, p.getMissionManager().sub(CHILD).getPhase());
  }

  @Test
  void parentLaterAdditionIncludesTheChildSet() {
    var p =
        graph(
            definition(ROOT, finishChild(CHILD), variable("AddCustomValue", "count", 1)),
            definition(CHILD, variable("SetCustomValue", "count", Integer.MAX_VALUE)));
    rejected(p, GameplayDefinitions.subs.get(ROOT));
    assertEquals(QuestState.Phase.DOING, p.getMissionManager().sub(CHILD).getPhase());
  }

  @ParameterizedTest
  @CsvSource({"2147483646,1,2147483647", "-2147483647,-1,-2147483648"})
  void exactSignedBoundariesRemainValid(int current, int delta, int expected) {
    var p = new SavedPlayer();
    p.getGameplay().getStoryValues().put(KEY, current);
    var sub = action(variable("AddCustomValue", "count", delta));
    assertEquals(Retcode.SUCC, new StoryActions(p).validate(sub));
    assertEquals(Retcode.SUCC, new StoryActions(p).execute(sub));
    assertEquals(expected, p.getGameplay().getStoryValues().get(KEY));
    assertFalse(p.getGameplay().getStoryActions().get(ROOT).isPending());
  }

  @Test
  void precedingSetCanMakeAnAdditionValid() {
    var p = new SavedPlayer();
    p.getGameplay().getStoryValues().put(KEY, Integer.MAX_VALUE);
    assertEquals(
        Retcode.SUCC,
        new StoryActions(p)
            .execute(
                action(
                    variable("SetCustomValue", "count", -2),
                    variable("AddCustomValue", "count", 3))));
    assertEquals(1, p.getGameplay().getStoryValues().get(KEY));
  }

  @Test
  void restoredCompletedPrefixIsSkippedAndFinalActionDoesNotReplay() throws Exception {
    var p = new SavedPlayer();
    p.getGameplay().getStoryValues().put(KEY, Integer.MAX_VALUE);
    var cursor = new GameplayState.ActionCursor();
    cursor.reserve();
    cursor.complete();
    p.getGameplay().getStoryActions().put(ROOT, cursor);
    var sub =
        action(variable("AddCustomValue", "count", 1), variable("AddCustomValue", "count", -1));
    assertEquals(Retcode.SUCC, new StoryActions(p).execute(sub));
    assertEquals(Integer.MAX_VALUE - 1, p.getGameplay().getStoryValues().get(KEY));
    var restored = new SavedPlayer();
    var field = Player.class.getDeclaredField("gameplay");
    field.setAccessible(true);
    field.set(restored, p.document);
    assertEquals(Retcode.SUCC, new StoryActions(restored).execute(sub));
    assertEquals(Integer.MAX_VALUE - 1, restored.getGameplay().getStoryValues().get(KEY));
    assertEquals(2, restored.getGameplay().getStoryActions().get(ROOT).getNext());
    assertEquals(0, restored.saves);
  }

  @Test
  void repeatedChildReferenceCountsItsActionsOnce() {
    var p =
        graph(
            definition(ROOT, finishChild(CHILD, CHILD)),
            definition(CHILD, variable("AddCustomValue", "count", 1)));
    p.getGameplay().getStoryValues().put(KEY, Integer.MAX_VALUE - 1);
    assertEquals(Retcode.SUCC, p.getMissionManager().finishTalk(ROOT, "arithmetic"));
    assertEquals(Integer.MAX_VALUE, p.getGameplay().getStoryValues().get(KEY));
    assertTrue(p.getMissionManager().finished(MAIN));
  }

  @Test
  void alreadyFinishedChildCannotRejectOrReplayItsOldAddition() {
    var p =
        graph(
            definition(ROOT, finishChild(CHILD)),
            definition(CHILD, variable("AddCustomValue", "count", 1)));
    assertEquals(Retcode.SUCC, p.getMissionManager().finishFromAction(CHILD));
    p.getGameplay().getStoryValues().put(KEY, Integer.MAX_VALUE);
    assertEquals(Retcode.SUCC, p.getMissionManager().finishTalk(ROOT, "arithmetic"));
    assertEquals(Integer.MAX_VALUE, p.getGameplay().getStoryValues().get(KEY));
    assertTrue(p.getMissionManager().finished(MAIN));
  }
}
