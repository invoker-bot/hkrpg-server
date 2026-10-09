package org.hkrpg.server.protocol;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import emu.lunarcore.data.config.*;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.game.quest.QuestState;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import org.hkrpg.server.gameplay.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;

class ClockParkMissionProgress46Test {
  @TempDir Path root;
  final ClockParkEnding46Test ending = new ClockParkEnding46Test();
  final Gson gson = new Gson();
  JsonObject source;

  @BeforeEach
  void prepare() throws Exception {
    ending.root = root;
    ending.prepare();
    source =
        JsonParser.parseString(
                new String(
                    getClass()
                        .getResourceAsStream("/fixtures/clock-park-mission-progress-4.6.json")
                        .readAllBytes(),
                    java.nio.charset.StandardCharsets.UTF_8))
            .getAsJsonObject();
    place(20321, 20321001);
  }

  @AfterEach
  void restore() throws Exception {
    ending.restore();
  }

  Player player() {
    return ending.fixture().opening.player;
  }

  void place(int plane, int floor) throws Exception {
    for (var e : Map.of("planeId", plane, "floorId", floor).entrySet()) {
      var field = Player.class.getDeclaredField(e.getKey());
      field.setAccessible(true);
      field.setInt(player(), e.getValue());
    }
  }

  MissionInfo.SubMissionInfo target(int id) {
    var row =
        source.getAsJsonArray("rows").asList().stream()
            .map(JsonElement::getAsJsonObject)
            .filter(v -> v.get("ID").getAsInt() == id)
            .findFirst()
            .orElseThrow();
    var sub = gson.fromJson(row, MissionInfo.SubMissionInfo.class);
    if (id == 802210401) {
      var task = gson.fromJson(source.get("script"), MissionTaskInfo.class);
      task.retainSourceDefinition(source.get("script"));
      sub.setTask(task);
    }
    return sub;
  }

  boolean condition(MissionInfo.SubMissionInfo sub) throws Exception {
    var method =
        MissionManager.class.getDeclaredMethod(
            "currentCondition", MissionInfo.SubMissionInfo.class);
    method.setAccessible(true);
    return (boolean) method.invoke(player().getMissionManager(), sub);
  }

  void complete() throws Exception {
    ending.endingStory();
    ending.completeStory();
    assertEquals(0, Gameplay46.number(ending.completion.response, "retcode"));
    assertEquals(1, ending.delivered);
    assertNull(ending.run());
  }

  void accept(MissionInfo.SubMissionInfo sub) {
    // Isolate the accepted target from unrelated authored Auto branches in this parent fixture.
    // Keep all five opening prerequisites required by the actual ClockPark source check.
    var openingGates = Set.of(802210101, 802210801, 802212004, 802212404, 802210311);
    GameplayDefinitions.subs
        .values()
        .removeIf(
            c ->
                c.getMainMissionID() == sub.getMainMissionID()
                    && !openingGates.contains(c.getId()));
    GameplayDefinitions.subs.put(sub.getId(), sub);
    player()
        .getGameplay()
        .getMainMissions()
        .put(sub.getMainMissionID(), new QuestState(sub.getMainMissionID()));
    player().getGameplay().getSubMissions().put(sub.getId(), new QuestState(sub.getId()));
  }

  @Test
  void actualCompletedEndingSatisfiesFirstThresholdAndSurvivesSavedStateReload() throws Exception {
    var target = target(802210103);
    assertFalse(condition(target));
    ending.endingStory();
    assertFalse(condition(target), "Ending story is still waiting");
    ending.completeStory();
    assertEquals(0, Gameplay46.number(ending.completion.response, "retcode"));
    assertTrue(condition(target));
    var raw = gson.toJson(player().getGameplay());
    ending.fixture().opening.replaceGameplay(gson.fromJson(raw, GameplayState.class));
    assertTrue(condition(target));
    assertEquals(raw, gson.toJson(player().getGameplay()));
  }

  @ParameterizedTest
  @ValueSource(ints = {802210103, 802210201, 802210205, 802210207, 802210401})
  void oneActualEndingCannotSatisfyAHigherAuthoredThreshold(int id) throws Exception {
    complete();
    assertEquals(id == 802210103, condition(target(id)));
  }

  @Test
  void actualEndingNotificationAlsoCompletesAcceptedStoryActionsOnce() throws Exception {
    var sub = target(802210103);
    accept(sub);
    complete();
    var progress = player().getMissionManager().sub(sub.getId());
    assertEquals(QuestState.Phase.FINISHED, progress.getPhase());
    assertEquals(1, progress.getProgress());
    assertTrue(progress.getFinishTime() > 0);
    assertEquals(4, player().getGameplay().getStoryValues().get("floor:20321001:Crowd"));
    assertEquals(1, player().getGameplay().getStoryValues().get("floor:20321001:FSV_Post01"));
    long time = progress.getFinishTime();
    player().getGameplay().getStoryValues().put("floor:20321001:Crowd", 99);
    player().getMissionManager().refresh();
    assertEquals(time, progress.getFinishTime());
    assertEquals(99, player().getGameplay().getStoryValues().get("floor:20321001:Crowd"));
    assertEquals(1, ending.delivered);
  }

  @Test
  void refreshRecoversAMissedProgressEventFromTheSavedEnding() throws Exception {
    complete();
    var sub = target(802210103);
    accept(sub);
    ending
        .fixture()
        .opening
        .replaceGameplay(gson.fromJson(gson.toJson(player().getGameplay()), GameplayState.class));
    player().getMissionManager().refresh();
    assertEquals(
        QuestState.Phase.FINISHED, player().getMissionManager().sub(sub.getId()).getPhase());
    assertEquals(4, player().getGameplay().getStoryValues().get("floor:20321001:Crowd"));
  }

  @ParameterizedTest
  @CsvSource({"20322,20321001", "20321,20321002"})
  void wrongOriginalPlaceKeepsTheStoryPendingWithoutFloorWrites(int plane, int floor)
      throws Exception {
    complete();
    var sub = target(802210103);
    accept(sub);
    String values = gson.toJson(player().getGameplay().getStoryValues());
    place(plane, floor);
    player().getMissionManager().refresh();
    assertEquals(QuestState.Phase.DOING, player().getMissionManager().sub(sub.getId()).getPhase());
    assertEquals(values, gson.toJson(player().getGameplay().getStoryValues()));
  }

  @ParameterizedTest
  @ValueSource(strings = {"main", "sub"})
  void cancelledStoryCannotConsumeEarnedActivityProgress(String kind) throws Exception {
    complete();
    var sub = target(802210103);
    accept(sub);
    String actions = gson.toJson(player().getGameplay().getStoryActions());
    if (kind.equals("main"))
      player().getGameplay().getCancelledMissions().add(sub.getMainMissionID());
    else player().getGameplay().getCancelledSubs().add(sub.getId());
    player().getMissionManager().refresh();
    assertEquals(QuestState.Phase.DOING, player().getMissionManager().sub(sub.getId()).getPhase());
    assertEquals(actions, gson.toJson(player().getGameplay().getStoryActions()));
  }

  @ParameterizedTest
  @ValueSource(strings = {"receipt", "item", "sequence", "source", "chapter"})
  void corruptEndingEvidenceCannotBecomeStoryCompletion(String kind) throws Exception {
    complete();
    if (kind.equals("receipt")) player().getGameplay().getRewards().clear();
    else if (kind.equals("item")) ending.owned = null;
    else {
      var raw = gson.toJsonTree(player().getGameplay()).getAsJsonObject();
      var state = raw.getAsJsonObject("clockPark");
      if (kind.equals("sequence")) state.addProperty("sequence", 0);
      else {
        var proof =
            state.getAsJsonObject("reachedProofs").getAsJsonObject("1").getAsJsonObject("103");
        if (kind.equals("source")) proof.addProperty("cardSource", "changed source");
        else proof.addProperty("chapter", 102);
      }
      ending.fixture().opening.replaceGameplay(gson.fromJson(raw, GameplayState.class));
    }
    assertFalse(condition(target(802210103)));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "id",
        "main",
        "threshold",
        "compare",
        "backtrack",
        "goal",
        "extra",
        "place",
        "actions"
      })
  void changedSourceTargetDoesNotBorrowTheOriginalProgressContract(String kind) throws Exception {
    complete();
    var sub = target(802210103);
    switch (kind) {
      case "id" -> sub.setId(802210104);
      case "main" -> sub.setMainMissionID(8022102);
      case "threshold" -> sub.setParamInt1(1);
      case "compare" -> sub.setParamType("Equal");
      case "backtrack" -> sub.setBackTrack(false);
      case "goal" -> sub.setProgress(2);
      case "extra" -> sub.setParamInt2(1);
      case "place" -> sub.setLevelPlaneID(0);
      case "actions" -> sub.setFinishActionList(new MissionInfo.FinishAction[0]);
      default -> throw new AssertionError(kind);
    }
    assertFalse(condition(sub));
  }

  @Test
  void pendingRewardReservationDoesNotAdvanceStoryProgress() throws Exception {
    ending.endingStory();
    ending.rejectDelivery = true;
    ending.completeStory();
    assertEquals("FINISHING", ending.run().getPhase());
    assertFalse(condition(target(802210103)));
    assertFalse(player().getGameplay().getStoryValues().containsKey("floor:20321001:Crowd"));
  }

  @ParameterizedTest
  @ValueSource(strings = {"malformed", "missing", "changed"})
  void incompleteOrChangedRawScreenScriptIsRejectedWithoutThrowing(String kind) throws Exception {
    var sub = target(802210401);
    var field = MissionTaskInfo.class.getDeclaredField("sourceDefinition");
    field.setAccessible(true);
    String text =
        switch (kind) {
          case "malformed" -> "{";
          case "missing" -> "";
          case "changed" ->
              gson.toJson(source.get("script")).replace("ScreenContent", "DifferentContent");
          default -> throw new AssertionError(kind);
        };
    field.set(sub.getTask(), text);
    assertFalse(condition(sub));
  }
}
