package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import emu.lunarcore.data.GameData;
import emu.lunarcore.data.config.MissionInfo;
import emu.lunarcore.data.config.MissionTaskInfo;
import emu.lunarcore.data.excel.MainMissionExcel;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.game.quest.QuestState;
import emu.lunarcore.server.packet.Retcode;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Original rows and completely decoded client graphs; acceptance and storage are fixtures. */
class MissionTalkScriptGates46Test {
  static final int MAIN = 4340111, TALK = 434011114, STUDIO = 105450340;
  final Gson gson = new Gson();
  final Map<Integer, MissionInfo> previousMains = new HashMap<>();
  final Map<Integer, MissionInfo.SubMissionInfo> previousSubs = new HashMap<>();
  final Map<Integer, MainMissionExcel> previousExcel = new HashMap<>();
  final Map<Integer, JsonObject> sourceSubs = new HashMap<>();
  final Map<String, JsonObject> sourceGraphs = new HashMap<>();
  Actor actor;

  static class Actor extends Player {
    int saves, plane = 20321, floor = 20321001;

    @Override
    public void save() {
      saves++;
    }

    @Override
    public int getPlaneId() {
      return plane;
    }

    @Override
    public int getFloorId() {
      return floor;
    }
  }

  @BeforeEach
  void setup() throws Exception {
    previousMains.putAll(GameData.getMissionInfos());
    previousSubs.putAll(GameplayDefinitions.subs);
    for (var row : GameData.getMainMissionExcelMap().values()) previousExcel.put(row.getId(), row);
    GameData.getMissionInfos().clear();
    GameplayDefinitions.subs.clear();
    GameData.getMainMissionExcelMap().values().clear();
    try (var reader =
        new InputStreamReader(
            getClass().getResourceAsStream("/fixtures/recovered-mission-talk-gates-4.6.json"),
            StandardCharsets.UTF_8)) {
      var fixture = JsonParser.parseReader(reader).getAsJsonObject();
      for (var value : fixture.getAsJsonArray("fixedSourceRows")) {
        var entry = value.getAsJsonObject();
        var row = entry.getAsJsonObject("row");
        if (entry.get("kind").getAsString().equals("main")) {
          var config = gson.fromJson(row, MissionInfo.class);
          GameData.getMissionInfos().put(config.getMainMissionID(), config);
        } else if (entry.get("kind").getAsString().equals("sub")) {
          sourceSubs.put(row.get("ID").getAsInt(), row);
          var config = gson.fromJson(row, MissionInfo.SubMissionInfo.class);
          GameplayDefinitions.subs.put(config.getId(), config);
        }
      }
      for (var value : fixture.getAsJsonArray("graphs")) {
        var entry = value.getAsJsonObject();
        sourceGraphs.put(entry.get("sourcePath").getAsString(), entry.getAsJsonObject("resource"));
      }
    }
    script(TALK, graph(TALK));
    script(STUDIO, graph(STUDIO));
    actor = new Actor();
    actor.getGameplay().getMainMissions().put(MAIN, new QuestState(MAIN));
    completed(434011107);
    actor.getMissionManager().refresh();
    assertNotNull(actor.getMissionManager().sub(TALK));
  }

  JsonObject graph(int id) {
    return sourceGraphs.get(GameplayDefinitions.subs.get(id).getMissionJsonPath()).deepCopy();
  }

  void script(int id, JsonElement raw) {
    var task = gson.fromJson(raw, MissionTaskInfo.class);
    task.retainSourceDefinition(raw);
    GameplayDefinitions.subs.get(id).setTask(task);
  }

  void completed(int id) {
    var state = new QuestState(id);
    assertTrue(state.advance(1, 1, 100));
    actor.getGameplay().getSubMissions().put(id, state);
  }

  void rejected(int id) {
    var before = gson.toJson(actor.getGameplay());
    int saves = actor.saves;
    assertEquals(
        Retcode.MISSION_FINISH_WAY_NOT_MATCH,
        actor.getMissionManager().finishTalk(id, "Mission_" + id));
    assertEquals(before, gson.toJson(actor.getGameplay()));
    assertEquals(saves, actor.saves);
  }

  @AfterEach
  void cleanup() {
    GameData.getMissionInfos().clear();
    GameData.getMissionInfos().putAll(previousMains);
    GameplayDefinitions.subs.clear();
    GameplayDefinitions.subs.putAll(previousSubs);
    GameData.getMainMissionExcelMap().values().clear();
    previousExcel.values().forEach(GameData.getMainMissionExcelMap()::add);
  }

  @Test
  void originalMessageBranchesCannotFinishBeforeAnyWatchedTask() {
    rejected(TALK);
  }

  @Test
  void studioFloorSavedValueCannotStandInForUnboundFloorCustomFloat() {
    actor.plane = 20551;
    actor.floor = 20551001;
    actor.getGameplay().getMainMissions().put(1054503, new QuestState(1054503));
    completed(105450323);
    actor.getMissionManager().refresh();
    assertNotNull(actor.getMissionManager().sub(STUDIO));
    actor.getGameplay().getStoryValues().put("floor:20551001:FSV_StudioState", 1);
    actor.getGameplay().getStoryValues().put("floor:20551001:FCV_StudioState_Entity", 1);
    rejected(STUDIO);
  }

  @Test
  void aMatchingFloorNumberCannotAuthorizeTheWrongPlane() {
    completed(434011111);
    actor.plane = 20311;
    rejected(TALK);
  }

  @ParameterizedTest
  @ValueSource(ints = {434011111, 434011112, 434011113})
  void eachOriginalFinishedBranchAuthorizesExactlyOneReport(int watched) {
    completed(watched);
    assertEquals(Retcode.SUCC, actor.getMissionManager().finishTalk(TALK, "Mission_" + TALK));
    var state = actor.getMissionManager().sub(TALK);
    assertEquals(QuestState.Phase.FINISHED, state.getPhase());
    assertEquals(1, state.getProgress());
    assertTrue(state.getFinishTime() > 0);
    var before = gson.toJson(actor.getGameplay());
    int saves = actor.saves;
    assertEquals(
        Retcode.MISSION_NOT_DOING, actor.getMissionManager().finishTalk(TALK, "Mission_" + TALK));
    assertEquals(before, gson.toJson(actor.getGameplay()));
    assertEquals(saves, actor.saves);
    assertEquals(
        QuestState.Phase.FINISHED, actor.getGameplay().getMainMissions().get(MAIN).getPhase());
  }

  @ParameterizedTest
  @ValueSource(strings = {"CLAIMING", "CLOSED"})
  void completedMessageHistoryRemainsUsableAfterItsRewardPhase(String phase) {
    completed(434011112);
    var state = actor.getMissionManager().sub(434011112);
    assertTrue(state.reserveReward());
    if (phase.equals("CLOSED")) state.closeReward();
    assertEquals(Retcode.SUCC, actor.getMissionManager().finishTalk(TALK, "Mission_" + TALK));
  }

  @Test
  void zeroIdResolutionUsesTheSameScriptGate() {
    assertEquals(
        Retcode.MISSION_FINISH_WAY_NOT_MATCH,
        actor.getMissionManager().finishTalk(0, "Mission_" + TALK));
    completed(434011113);
    assertEquals(Retcode.SUCC, actor.getMissionManager().finishTalk(0, "Mission_" + TALK));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "doing",
        "wrongId",
        "zeroTime",
        "negativeTime",
        "zeroProgress",
        "excessProgress",
        "cancelled",
        "missing"
      })
  void malformedWatchedHistoryCannotStandInForFinishedState(String defect) {
    completed(434011111);
    var raw = gson.toJsonTree(actor.getMissionManager().sub(434011111)).getAsJsonObject();
    switch (defect) {
      case "doing" -> raw.addProperty("phase", "DOING");
      case "wrongId" -> raw.addProperty("questId", 434011112);
      case "zeroTime" -> raw.addProperty("finishTime", 0);
      case "negativeTime" -> raw.addProperty("finishTime", -1);
      case "zeroProgress" -> raw.addProperty("progress", 0);
      case "excessProgress" -> raw.addProperty("progress", 2);
      case "cancelled" -> actor.getGameplay().getCancelledSubs().add(434011111);
      case "missing" -> actor.getGameplay().getSubMissions().remove(434011111);
      default -> fail(defect);
    }
    if (!defect.equals("missing"))
      actor.getGameplay().getSubMissions().put(434011111, gson.fromJson(raw, QuestState.class));
    rejected(TALK);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "parent",
        "section",
        "goal",
        "finish",
        "condition",
        "actions",
        "nullList",
        "missingOther",
        "prerequisite",
        "take"
      })
  void changedTaskSourcesCannotBorrowTheOriginalBranch(String defect) {
    completed(434011111);
    var config = GameplayDefinitions.subs.get(434011111);
    switch (defect) {
      case "parent" -> config.setMainMissionID(1054503);
      case "section" -> config.setParamInt1(1306200);
      case "goal" -> config.setProgress(2);
      case "finish" -> config.setFinishType("Talk");
      case "condition" -> config.setParamType("NoPara");
      case "actions" ->
          config.setFinishActionList(
              gson.fromJson(
                  "[{\"FinishActionType\":\"UnknownEffect\"}]", MissionInfo.FinishAction[].class));
      case "nullList" -> config.setStoryLineIDList(null);
      case "missingOther" -> GameplayDefinitions.subs.remove(434011113);
      case "prerequisite" -> GameplayDefinitions.subs.get(434011107).setMainMissionID(1054503);
      case "take" ->
          GameplayDefinitions.subs.get(TALK).setTakeParamIntList(new Integer[] {434011111});
      default -> fail(defect);
    }
    rejected(TALK);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "missing",
        "empty",
        "invalid",
        "unknownAction",
        "inverted",
        "wrongState",
        "extraInit",
        "wrongTarget"
      })
  void changedOrIncompleteGraphsCannotAuthorizeAReport(String defect) {
    completed(434011111);
    var raw = graph(TALK);
    var tasks =
        raw.getAsJsonArray("OnStartSequece").get(0).getAsJsonObject().getAsJsonArray("TaskList");
    switch (defect) {
      case "missing" -> GameplayDefinitions.subs.get(TALK).setTask(null);
      case "empty" -> GameplayDefinitions.subs.get(TALK).getTask().retainSourceDefinition(null);
      case "invalid" ->
          GameplayDefinitions.subs
              .get(TALK)
              .getTask()
              .retainSourceDefinition(new JsonPrimitive("invalid"));
      case "unknownAction" ->
          tasks.add(JsonParser.parseString("{\"$type\":\"RPG.GameCore.Unknown\"}"));
      case "inverted" -> tasks.get(1).getAsJsonObject().addProperty("Inverse", true);
      case "wrongState" -> tasks.get(1).getAsJsonObject().addProperty("SubMissionState", "Started");
      case "extraInit" ->
          raw.getAsJsonArray("OnInitSequece").add(JsonParser.parseString("{\"TaskList\":[]}"));
      case "wrongTarget" -> tasks.get(2).getAsJsonObject().addProperty("SubmissionID", 434011115);
      default -> fail(defect);
    }
    if (!Set.of("missing", "empty", "invalid").contains(defect)) script(TALK, raw);
    rejected(TALK);
  }

  @Test
  void savedMessageCompletionAuthorizesAfterGameplayStateRestoration() throws Exception {
    completed(434011113);
    var restored = gson.fromJson(gson.toJson(actor.getGameplay()), GameplayState.class);
    var replacement = new Actor();
    var field = Player.class.getDeclaredField("gameplay");
    field.setAccessible(true);
    field.set(replacement, restored);
    actor = replacement;
    assertEquals(Retcode.SUCC, actor.getMissionManager().finishTalk(TALK, "Mission_" + TALK));
  }

  @Test
  void missingOriginalAcceptancePredecessorCannotBorrowALaterCompletion() {
    completed(434011111);
    actor.getGameplay().getSubMissions().remove(434011107);
    rejected(TALK);
  }
}
