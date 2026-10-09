package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import emu.lunarcore.data.GameData;
import emu.lunarcore.data.config.MissionTaskInfo;
import emu.lunarcore.game.quest.QuestState;
import emu.lunarcore.server.packet.Retcode;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Original file loading includes the source script, rather than dropping its task declaration. */
class DailyStageScript46Test {
  final SceneEventBattle46Test events = new SceneEventBattle46Test();
  final DailyStageWin46Test source = new DailyStageWin46Test();
  @TempDir Path temp;
  JsonObject script;
  static final int MAIN = 3011607, SUB = 301160701, EVENT = 20134222;

  @BeforeEach
  void load() throws Exception {
    events.load();
    source.temp = temp;
    source.setup();
    var path = temp.resolve("Config/Level/Mission/" + MAIN);
    Files.createDirectories(path);
    try (var input = getClass().getResourceAsStream("/fixtures/daily-stage-script-4.6.json")) {
      var files =
          JsonParser.parseString(
                  new String(Objects.requireNonNull(input).readAllBytes(), StandardCharsets.UTF_8))
              .getAsJsonArray();
      assertEquals(5, files.size());
      for (var raw : files) {
        var file = raw.getAsJsonObject();
        var name = Path.of(file.get("source").getAsString()).getFileName().toString();
        Files.writeString(path.resolve(name), file.get("row").toString());
        if (name.equals("Mission_" + SUB + ".json"))
          script = file.getAsJsonObject("row").deepCopy();
      }
    }
    GameplayDefinitions.load(temp);
    assertNotNull(
        GameplayDefinitions.subs.get(SUB).getTask(), "The actual source task must be loaded");
    assertTrue(GameplayDefinitions.subs.get(SUB).getTask().declaresBattle(EVENT));
    assertNotNull(
        GameplayDefinitions.subs.get(SUB + 1).getTask(),
        "The original final performance task must stay loaded");
    source.single(MAIN);
    GameplayDefinitions.dailyPool.add(new GameplayDefinitions.DailyPool(2100003, 1));
    var actor = new DailyStageWin46Test.Actor();
    assertTrue(actor.setNickname("StageScript"));
    source.assigned.repeated.fixture.actor = actor;
    source.clock(DailyMissionRepeat46Test.FIRST_DAY);
    for (int id : List.of(1010403, 1011501, 2010501, 1011403))
      actor.getGameplay().getMainMissions().put(id, DailyMissionAcceptance46Test.closed(id));
    actor.getDailyManager().ensureDay();
  }

  @AfterEach
  void restore() {
    source.cleanup();
    events.restore();
  }

  @Test
  void canonicalSourceDailyWithItsLoadedLiteralScriptIsActuallyAssigned() {
    source.actor().getMissionManager().refresh();
    var state = source.actor().getGameplay().getMainMissions().get(MAIN);
    assertNotNull(state);
    assertEquals(QuestState.Phase.DOING, state.getPhase());
    assertNotNull(source.actor().getMissionManager().sub(SUB));
  }

  @Test
  void actualEventVictoryAdvancesTheLoadedOriginalScriptGoalAndPaysItsOriginalTail()
      throws Exception {
    source.actor().getMissionManager().refresh();
    assertEquals(Retcode.SUCC, source.actor().getMissionManager().accept(MAIN));
    source.actor().floor = 20122001;
    var battle = Objects.requireNonNull(events.service.startEventBattle(source.actor(), EVENT));
    assertEquals(0, source.actor().getMissionManager().sub(SUB).getProgress());
    source.win(battle);
    assertEquals(1, source.actor().getMissionManager().sub(SUB).getProgress());
    assertEquals(0, source.actor().getScoin());
    source.finishTail(MAIN);
    assertEquals(5000, source.actor().getScoin());
    assertEquals(Retcode.SUCC, source.actor().getDailyManager().claimQuest(2100003).retcode());
    assertEquals(200, source.actor().getGameplay().getDailyPoints());
  }

  private void replace(JsonObject definition) {
    var task = new Gson().fromJson(definition, MissionTaskInfo.class);
    task.retainSourceDefinition(definition);
    GameplayDefinitions.subs.get(SUB).setTask(task);
  }

  private JsonArray tasks(JsonObject root, int sequence) {
    return root.getAsJsonArray("OnStartSequece")
        .get(sequence)
        .getAsJsonObject()
        .getAsJsonArray("TaskList");
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "unknown-root",
        "nonempty-init",
        "extra-sequence",
        "extra-action",
        "wrong-talk",
        "wrong-trigger",
        "wrong-wait",
        "immediate-false",
        "immediate-string",
        "dynamic-event",
        "wrong-event",
        "zero-group",
        "zero-area",
        "decimal-event",
        "wide-event",
        "string-event",
        "extra-literal",
        "extra-value",
        "extra-sequence-key",
        "wrong-type",
        "missing-init",
        "missing-start",
        "unknown-action-key",
        "wrong-battle-type",
        "missing-group"
      })
  void unsupportedSourceActionShapeCannotBeSilentlyDroppedAndAssigned(String mutation) {
    var value = script.deepCopy();
    var first = tasks(value, 0);
    var second = tasks(value, 1);
    var battle = second.get(1).getAsJsonObject();
    var event = battle.getAsJsonObject("EventID");
    switch (mutation) {
      case "unknown-root" -> value.addProperty("OnFinishSequence", true);
      case "nonempty-init" -> value.getAsJsonArray("OnInitSequece").add(new JsonObject());
      case "extra-sequence" -> value.getAsJsonArray("OnStartSequece").add(new JsonObject());
      case "extra-action" -> first.add(new JsonObject());
      case "wrong-talk" ->
          first
              .get(0)
              .getAsJsonObject()
              .getAsJsonObject("CustomString")
              .addProperty("Value", "Talk_" + (SUB + 1));
      case "wrong-trigger" ->
          first
              .get(1)
              .getAsJsonObject()
              .getAsJsonObject("CustomString")
              .addProperty("Value", "Battle_" + (SUB + 1));
      case "wrong-wait" ->
          second
              .get(0)
              .getAsJsonObject()
              .getAsJsonObject("CustomString")
              .addProperty("Value", "Battle_" + (SUB + 1));
      case "immediate-false" ->
          second.get(0).getAsJsonObject().addProperty("GoNextImmediately", false);
      case "immediate-string" ->
          second.get(0).getAsJsonObject().addProperty("GoNextImmediately", "true");
      case "dynamic-event" -> event.addProperty("IsDynamic", true);
      case "wrong-event" -> event.getAsJsonObject("FixedValue").addProperty("Value", EVENT - 1);
      case "zero-group" ->
          battle.getAsJsonObject("GroupID").getAsJsonObject("FixedValue").addProperty("Value", 0);
      case "zero-area" ->
          battle
              .getAsJsonObject("BattleAreaID")
              .getAsJsonObject("FixedValue")
              .addProperty("Value", 0);
      case "decimal-event" -> event.getAsJsonObject("FixedValue").addProperty("Value", EVENT + .5);
      case "wide-event" ->
          event.getAsJsonObject("FixedValue").addProperty("Value", (1L << 32) + EVENT);
      case "string-event" ->
          event.getAsJsonObject("FixedValue").addProperty("Value", Integer.toString(EVENT));
      case "extra-literal" -> event.addProperty("Binding", "dynamic");
      case "extra-value" -> event.getAsJsonObject("FixedValue").addProperty("Other", 1);
      case "extra-sequence-key" ->
          value.getAsJsonArray("OnStartSequece").get(0).getAsJsonObject().addProperty("Order", 1);
      case "wrong-type" -> value.addProperty("Type", "Adventure");
      case "missing-init" -> value.remove("OnInitSequece");
      case "missing-start" -> value.remove("OnStartSequece");
      case "unknown-action-key" -> first.get(0).getAsJsonObject().addProperty("ExecuteOnce", true);
      case "wrong-battle-type" -> battle.addProperty("$type", "RPG.GameCore.TriggerBattleByID");
      case "missing-group" -> battle.remove("GroupID");
      default -> fail(mutation);
    }
    replace(value);
    assertNull(MissionStageScripts.signature(GameplayDefinitions.subs.get(SUB)));
    source.actor().getMissionManager().refresh();
    assertNull(source.actor().getGameplay().getMainMissions().get(MAIN));
    assertEquals(0, source.actor().getScoin());
  }

  @ParameterizedTest
  @ValueSource(strings = {"GroupID", "BattleAreaID", "unknown-action"})
  void sourceDeclarationChangedAfterAcceptedBattleCannotAdvanceAFrozenGoal(String change) {
    source.actor().getMissionManager().refresh();
    source.actor().floor = 20122001;
    var battle = Objects.requireNonNull(events.service.startEventBattle(source.actor(), EVENT));
    var edited = script.deepCopy();
    if (change.equals("unknown-action")) tasks(edited, 0).add(new JsonObject());
    else
      tasks(edited, 1)
          .get(1)
          .getAsJsonObject()
          .getAsJsonObject(change)
          .getAsJsonObject("FixedValue")
          .addProperty("Value", 99);
    replace(edited);
    source.win(battle);
    assertEquals(0, source.actor().getMissionManager().sub(SUB).getProgress());
    assertEquals(0, source.actor().getScoin());
  }

  @Test
  void aTypedTaskWithoutRetainedOriginalFieldsStaysUnsupported() {
    GameplayDefinitions.subs.get(SUB).setTask(new Gson().fromJson(script, MissionTaskInfo.class));
    assertNull(MissionStageScripts.signature(GameplayDefinitions.subs.get(SUB)));
    source.actor().getMissionManager().refresh();
    assertNull(source.actor().getGameplay().getMainMissions().get(MAIN));
  }

  @Test
  void missingCanonicalEventCannotBeReplacedWithANeighborStage() {
    GameData.getPlaneEventExcelMap().values().removeIf(row -> row.getEventId() == EVENT);
    assertNotNull(GameData.getStageExcelMap().get(201342220));
    assertNull(MissionStageScripts.signature(GameplayDefinitions.subs.get(SUB)));
    source.actor().getMissionManager().refresh();
    assertNull(source.actor().getGameplay().getMainMissions().get(MAIN));
  }

  @Test
  void nonStageFinalPerformanceTaskIsPreservedWithoutPretendingToInterpretIt() {
    var tail = GameplayDefinitions.subs.get(SUB + 1).getTask();
    assertTrue(tail.getSourceDefinition().contains("RPG.GameCore.TriggerPerformance"));
    assertTrue(tail.getSourceDefinition().contains("RPG.GameCore.FinishPerformanceMission"));
    assertNull(MissionStageScripts.signature(GameplayDefinitions.subs.get(SUB + 1)));
    source.actor().getMissionManager().refresh();
    assertEquals(0, source.actor().getScoin());
    assertNotEquals(
        Retcode.SUCC,
        source.actor().getMissionManager().finishTalk(SUB + 1, "Mission_" + (SUB + 1)));
  }
}
