package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import emu.lunarcore.data.*;
import emu.lunarcore.data.config.*;
import emu.lunarcore.data.excel.*;
import emu.lunarcore.game.quest.QuestState;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/** All completions are earned through boxing settlement; entry prerequisites remain fixtures. */
class BoxingClubMissionGoals46Test {
  @TempDir Path root;
  final BoxingClubActivityQuests46Test fixture = new BoxingClubActivityQuests46Test();
  final List<MainMissionExcel> oldMains = new ArrayList<>();
  final Map<Integer, MissionInfo> oldHeaders = new HashMap<>();
  final Map<Integer, MissionInfo.SubMissionInfo> oldSubs = new HashMap<>();
  JsonObject source;

  @BeforeEach
  void load() throws Exception {
    fixture.root = root;
    fixture.load();
    for (var entry : Map.of("planeId", 10102, "floorId", 10102004).entrySet()) {
      var field = emu.lunarcore.game.player.Player.class.getDeclaredField(entry.getKey());
      field.setAccessible(true);
      field.setInt(fixture.actor(), entry.getValue());
    }
    oldMains.addAll(GameData.getMainMissionExcelMap().values());
    oldHeaders.putAll(GameData.getMissionInfos());
    oldSubs.putAll(GameplayDefinitions.subs);
    GameData.getMainMissionExcelMap().values().clear();
    GameData.getMissionInfos().clear();
    GameplayDefinitions.subs.clear();
    source = fixture.first().fixture("boxing-club-mission-goals-4.6.json");
    for (var raw : source.getAsJsonArray("headers")) {
      int main = raw.getAsJsonObject().get("MainMissionID").getAsInt();
      var file = root.resolve("Config/Level/Mission/" + main + "/MissionInfo_" + main + ".json");
      Files.createDirectories(file.getParent());
      Files.writeString(file, raw.toString());
    }
    for (var raw : source.getAsJsonArray("rows")) {
      int main = raw.getAsJsonObject().get("MainMissionID").getAsInt();
      var file =
          root.resolve("Config/Level/Mission/" + main + "/MissionInfo_" + main + ".partial.json");
      Files.createDirectories(file.getParent());
      var rows = new JsonArray();
      rows.add(raw);
      var data = new JsonObject();
      data.add("SubMissionList", rows);
      Files.writeString(file, data.toString());
    }
  }

  @AfterEach
  void restore() throws Exception {
    fixture.restore();
    GameData.getMainMissionExcelMap().values().clear();
    oldMains.forEach(GameData.getMainMissionExcelMap()::add);
    GameData.getMissionInfos().clear();
    GameData.getMissionInfos().putAll(oldHeaders);
    GameplayDefinitions.subs.clear();
    GameplayDefinitions.subs.putAll(oldSubs);
  }

  MissionInfo.SubMissionInfo activate(int id) {
    var gson = ResourceLoader.getGson();
    var raw =
        java.util.stream.StreamSupport.stream(source.getAsJsonArray("rows").spliterator(), false)
            .map(JsonElement::getAsJsonObject)
            .filter(v -> v.get("ID").getAsInt() == id)
            .findFirst()
            .orElseThrow();
    var sub = gson.fromJson(raw, MissionInfo.SubMissionInfo.class);
    if (source.getAsJsonObject("scripts").has(String.valueOf(id))) {
      var json = source.getAsJsonObject("scripts").get(String.valueOf(id));
      var task = gson.fromJson(json, MissionTaskInfo.class);
      task.retainSourceDefinition(json);
      sub.setTask(task);
    }
    GameplayDefinitions.subs.put(id, sub);
    int main = sub.getMainMissionID();
    for (var json : source.getAsJsonArray("mainRows"))
      if (json.getAsJsonObject().get("MainMissionID").getAsInt() == main)
        GameData.getMainMissionExcelMap().add(gson.fromJson(json, MainMissionExcel.class));
    for (var json : source.getAsJsonArray("headers"))
      if (json.getAsJsonObject().get("MainMissionID").getAsInt() == main)
        GameData.getMissionInfos().put(main, gson.fromJson(json, MissionInfo.class));
    fixture.actor().getGameplay().getMainMissions().put(main, new QuestState(main));
    fixture.actor().getGameplay().getSubMissions().put(id, new QuestState(id));
    return sub;
  }

  @ParameterizedTest
  @ValueSource(ints = {201073101, 201073201, 201073301, 201073401, 201073501, 801520101, 801520301})
  void actualCompleteRunAdvancesTheOriginalStoryGoal(int id) throws Exception {
    int challenge = id < 800000000 ? (id - 201073001) / 100 : 12;
    for (int c = challenge <= 5 ? 1 : 6; c < challenge; c++) fixture.earn(c, c <= 5 ? 4 : 3);
    var sub = activate(id);
    fixture.earn(challenge, challenge <= 5 ? 4 : 3);
    fixture.actor().getMissionManager().refresh();
    var state = fixture.actor().getMissionManager().sub(id);
    assertEquals(
        QuestState.Phase.FINISHED, state.getPhase(), "Earned boxing story goal remains DOING");
    assertEquals(1, state.getProgress());
    assertTrue(state.getFinishTime() > 0);
    if (id == 801520101)
      assertEquals(
          1, fixture.actor().getGameplay().getStoryValues().get("floor:10102004:BoxClub2Hide"));
  }

  int challenge(int id) {
    return id < 800000000 ? (id - 201073001) / 100 : 12;
  }

  void prerequisites(int c) throws Exception {
    for (int n = c <= 5 ? 1 : 6; n < c; n++) fixture.earn(n, n <= 5 ? 4 : 3);
  }

  void begin(int c) throws Exception {
    GameplayDefinitions.windows.removeIf(w -> w.moduleId() != 5000001 && w.moduleId() != 4000308);
    int module =
        c <= 5
            ? BoxingClubCatalog.load(root).challenges().get(c).module()
            : fixture.fixture.fixture.challenge(c).module();
    GameplayDefinitions.windows.add(
        new GameplayDefinitions.Window(c <= 5 ? 50000 : 40003, module, 1, Long.MAX_VALUE));
    if (c <= 5) fixture.first().match(c, 1001);
    else fixture.fixture.fixture.prepare(c);
  }

  emu.lunarcore.game.battle.Battle start(int c, int index) throws Exception {
    if (c <= 5) return fixture.first().start();
    if (index > 0)
      fixture.fixture.fixture.choose(
          c, fixture.fixture.fixture.options(c, index).getFirst(), false);
    return fixture.fixture.fixture.start(c);
  }

  void win(int c, emu.lunarcore.game.battle.Battle battle, int rounds) {
    if (c <= 5) fixture.first().win((BoxingClubBattle) battle, rounds);
    else
      assertSame(
          battle,
          fixture.fixture.fixture.finish(
              (BoxingClubResonanceBattle) battle,
              emu.lunarcore.proto.BattleEndStatusOuterClass.BattleEndStatus.BATTLE_END_WIN,
              rounds));
  }

  emu.lunarcore.game.battle.Battle last(int c) throws Exception {
    begin(c);
    for (int i = 0; i < (c <= 5 ? 3 : 2); i++) win(c, start(c, i), 1);
    return start(c, c <= 5 ? 3 : 2);
  }

  void pending(int id) {
    fixture.actor().getMissionManager().refresh();
    var state = fixture.actor().getMissionManager().sub(id);
    assertEquals(QuestState.Phase.DOING, state.getPhase());
    assertEquals(0, state.getProgress());
    assertEquals(0, state.getFinishTime());
  }

  @ParameterizedTest
  @ValueSource(ints = {201073101, 201073201, 201073301, 201073401, 201073501})
  void originalBacktrackGoalsObserveEarlierActualCompletions(int id) throws Exception {
    int c = challenge(id);
    prerequisites(c);
    fixture.earn(c, 4);
    activate(id);
    fixture.actor().getMissionManager().refresh();
    assertEquals(QuestState.Phase.FINISHED, fixture.actor().getMissionManager().sub(id).getPhase());
  }

  @ParameterizedTest
  @ValueSource(ints = {801520101, 801520301})
  void nonbacktrackGoalsNeedANewAcceptedCompletionEvenWhenItsScoreIsWorse(int id) throws Exception {
    prerequisites(12);
    fixture.earn(12, 3);
    activate(id);
    pending(id);
    fixture.earn(12, 6);
    assertEquals(
        3, fixture.actor().getGameplay().getBoxingClubResonance().getCompleted().get(12).rounds());
    assertEquals(QuestState.Phase.FINISHED, fixture.actor().getMissionManager().sub(id).getPhase());
  }

  @ParameterizedTest
  @ValueSource(ints = {201073101, 201073201, 201073301, 201073401, 201073501, 801520101, 801520301})
  void partialRunsCannotCompleteOriginalStoryGoals(int id) throws Exception {
    int c = challenge(id);
    prerequisites(c);
    activate(id);
    begin(c);
    for (int i = 0; i < (c <= 5 ? 3 : 2); i++) win(c, start(c, i), 1);
    pending(id);
  }

  @ParameterizedTest
  @ValueSource(ints = {201073101, 201073201, 201073301, 201073401, 201073501, 801520101, 801520301})
  void anActualOtherChallengeCannotSupplyThisGoal(int id) throws Exception {
    activate(id);
    int wrong = challenge(id) == 1 ? 6 : 1;
    fixture.earn(wrong, wrong <= 5 ? 4 : 3);
    pending(id);
  }

  @ParameterizedTest
  @ValueSource(ints = {801520101, 801520301})
  void acceptingAfterTheFinalBattleStartsCannotConsumeThatResult(int id) throws Exception {
    prerequisites(12);
    var battle = last(12);
    activate(id);
    win(12, battle, 1);
    pending(id);
  }

  @ParameterizedTest
  @CsvSource({"801520101,main", "801520101,sub", "801520301,main", "801520301,sub"})
  void aReplacementAcceptanceCannotUseAnEarlierBattleBinding(int id, String kind) throws Exception {
    prerequisites(12);
    var sub = activate(id);
    var battle = last(12);
    if (kind.equals("main"))
      fixture
          .actor()
          .getGameplay()
          .getMainMissions()
          .put(sub.getMainMissionID(), new QuestState(sub.getMainMissionID()));
    else fixture.actor().getGameplay().getSubMissions().put(id, new QuestState(id));
    win(12, battle, 1);
    pending(id);
  }

  @ParameterizedTest
  @CsvSource({
    "201073101,main",
    "201073101,sub",
    "201073201,main",
    "201073201,sub",
    "201073301,main",
    "201073301,sub",
    "201073401,main",
    "201073401,sub",
    "201073501,main",
    "201073501,sub",
    "801520101,main",
    "801520101,sub",
    "801520301,main",
    "801520301,sub"
  })
  void canceledAcceptancesCannotConsumeACompleteRun(int id, String kind) throws Exception {
    int c = challenge(id);
    prerequisites(c);
    var sub = activate(id);
    var battle = last(c);
    if (kind.equals("main"))
      fixture.actor().getGameplay().getCancelledMissions().add(sub.getMainMissionID());
    else fixture.actor().getGameplay().getCancelledSubs().add(id);
    win(c, battle, 1);
    pending(id);
  }

  @ParameterizedTest
  @ValueSource(ints = {201073101, 201073201, 201073301, 201073401, 201073501, 801520101, 801520301})
  void aCompleteRunInAnotherCurrentFloorDoesNotAdvanceThisGoal(int id) throws Exception {
    int c = challenge(id);
    prerequisites(c);
    activate(id);
    var f = emu.lunarcore.game.player.Player.class.getDeclaredField("floorId");
    f.setAccessible(true);
    f.setInt(fixture.actor(), 20001001);
    fixture.earn(c, c <= 5 ? 4 : 3);
    pending(id);
  }

  @ParameterizedTest
  @CsvSource({
    "201073101,extra",
    "201073101,missing",
    "201073101,duplicate",
    "201073201,extra",
    "201073201,missing",
    "201073201,duplicate",
    "201073301,extra",
    "201073301,missing",
    "201073301,duplicate",
    "201073401,extra",
    "201073401,missing",
    "201073401,duplicate",
    "201073501,extra",
    "201073501,missing",
    "201073501,duplicate",
    "801520101,extra",
    "801520101,missing",
    "801520101,duplicate",
    "801520301,extra",
    "801520301,missing",
    "801520301,duplicate"
  })
  void changedCompleteRawDefinitionsCannotSupplyAStoryGoal(int id, String change) throws Exception {
    int c = challenge(id);
    prerequisites(c);
    var sub = activate(id);
    var file =
        root.resolve(
            "Config/Level/Mission/"
                + sub.getMainMissionID()
                + "/MissionInfo_"
                + sub.getMainMissionID()
                + ".partial.json");
    var raw = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
    var rows = raw.getAsJsonArray("SubMissionList");
    if (change.equals("extra"))
      rows.get(0).getAsJsonObject().addProperty("UnknownOriginalGoalOverride", true);
    else if (change.equals("duplicate")) rows.add(rows.get(0).deepCopy());
    else rows.remove(0);
    Files.writeString(file, raw.toString());
    fixture.earn(c, c <= 5 ? 4 : 3);
    pending(id);
  }

  @ParameterizedTest
  @ValueSource(ints = {201073101, 201073201, 201073301, 201073401})
  void anAlteredOriginalPerformanceScriptCannotSupplyTheBoundGoal(int id) throws Exception {
    int c = challenge(id);
    prerequisites(c);
    var sub = activate(id);
    var raw = JsonParser.parseString(sub.getTask().getSourceDefinition()).getAsJsonObject();
    raw.addProperty("UnrecognizedPerformance", true);
    sub.getTask().retainSourceDefinition(raw);
    fixture.earn(c, 4);
    pending(id);
  }

  @ParameterizedTest
  @ValueSource(ints = {201073101, 201073201, 201073301, 201073401, 201073501, 801520101, 801520301})
  void aTalkReportCannotReplaceTheActualBoxingCompletion(int id) {
    activate(id);
    assertNotEquals(
        emu.lunarcore.server.packet.Retcode.SUCC,
        fixture.actor().getMissionManager().finishTalk(id, "Mission_" + id));
    pending(id);
  }

  @ParameterizedTest
  @ValueSource(ints = {801520101, 801520301})
  void aSavedActualCompletionRecoversWhenTheFollowingMissionWriteIsMissing(int id)
      throws Exception {
    prerequisites(12);
    activate(id);
    var battle = last(12);
    var gson = ResourceLoader.getGson();
    String pending = gson.toJson(fixture.actor().getGameplay());
    win(12, battle, 1);
    var accepted = fixture.actor().getGameplay().getBoxingClubResonance();
    assertNotNull(accepted.getCompleted().get(12));
    // The completion is actual. This models the older persisted mission document, not a victory.
    var restored = gson.fromJson(pending, GameplayState.class);
    restored.boxingClubResonance(accepted);
    var field = emu.lunarcore.game.player.Player.class.getDeclaredField("gameplay");
    field.setAccessible(true);
    field.set(fixture.actor(), restored);
    fixture.actor().getMissionManager().refresh();
    assertEquals(
        QuestState.Phase.FINISHED,
        fixture.actor().getMissionManager().sub(id).getPhase(),
        "Saved complete run lost its originally accepted story target");
    if (id == 801520101)
      assertEquals(
          1, fixture.actor().getGameplay().getStoryValues().get("floor:10102004:BoxClub2Hide"));
  }

  @ParameterizedTest
  @ValueSource(ints = {201073101, 201073201, 201073301, 201073401})
  void originalMainStartDeclarationsAcceptTheFourGoalsWithOmittedTakeType(int id) {
    activate(id);
    fixture.actor().getGameplay().getSubMissions().remove(id);
    fixture.actor().getMissionManager().refresh();
    assertNotNull(
        fixture.actor().getMissionManager().sub(id), "Original boxing start goal never accepted");
    pending(id);
  }

  @ParameterizedTest
  @CsvSource({
    "201073101,extra",
    "201073101,wrongRoot",
    "201073201,extra",
    "201073201,wrongRoot",
    "201073301,extra",
    "201073301,wrongRoot",
    "201073401,extra",
    "201073401,wrongRoot"
  })
  void changedFullMainDeclarationsCannotAutoAcceptAnOmittedTakeType(int id, String damage)
      throws Exception {
    var sub = activate(id);
    fixture.actor().getGameplay().getSubMissions().remove(id);
    var file =
        root.resolve(
            "Config/Level/Mission/"
                + sub.getMainMissionID()
                + "/MissionInfo_"
                + sub.getMainMissionID()
                + ".json");
    var header = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
    if (damage.equals("extra")) header.addProperty("UnknownStartOverride", true);
    else header.getAsJsonArray("StartSubMissionList").set(0, new JsonPrimitive(id + 1));
    Files.writeString(file, header.toString());
    fixture.actor().getMissionManager().refresh();
    assertNull(fixture.actor().getMissionManager().sub(id));
  }

  @ParameterizedTest
  @ValueSource(ints = {801520101, 801520301})
  void aFailedFinalAdmissionSaveRestoresBothAcceptanceKeysAndTheActiveRun(int id) throws Exception {
    prerequisites(12);
    var sub = activate(id);
    begin(12);
    for (int i = 0; i < 2; i++) win(12, start(12, i), 1);
    fixture.fixture.fixture.choose(12, fixture.fixture.fixture.options(12, 2).getFirst(), false);
    var state = fixture.actor().getGameplay().getBoxingClubResonance();
    var main = fixture.actor().getGameplay().getMainMissions().get(sub.getMainMissionID());
    var goal = fixture.actor().getMissionManager().sub(id);
    fixture.actor().failSave = true;
    try {
      assertThrows(
          IllegalStateException.class,
          () -> BoxingClubResonanceManager.start(fixture.actor(), 12, java.time.Instant.now()));
    } finally {
      fixture.actor().failSave = false;
    }
    assertSame(state, fixture.actor().getGameplay().getBoxingClubResonance());
    assertFalse(state.getActive().missionTargetsReady());
    assertNull(main.getObservationKey());
    assertNull(goal.getObservationKey());
    assertNull(fixture.actor().getBattle());
    assertEquals(2, state.getActive().index());
    win(12, start(12, 2), 1);
    assertEquals(QuestState.Phase.FINISHED, fixture.actor().getMissionManager().sub(id).getPhase());
  }

  @ParameterizedTest
  @CsvSource({
    "801520101,source",
    "801520101,uid",
    "801520101,plane",
    "801520101,floor",
    "801520101,sequence",
    "801520101,zeroTurn",
    "801520101,reusedBattle",
    "801520101,stage",
    "801520101,binding",
    "801520101,extraTarget",
    "801520101,missingReady",
    "801520301,source",
    "801520301,uid",
    "801520301,plane",
    "801520301,floor",
    "801520301,sequence",
    "801520301,zeroTurn",
    "801520301,reusedBattle",
    "801520301,stage",
    "801520301,binding",
    "801520301,extraTarget",
    "801520301,missingReady"
  })
  void damagedStoredStoryProofsCannotRecoverAnOriginallyAcceptedGoal(int id, String damage)
      throws Exception {
    prerequisites(12);
    activate(id);
    var battle = last(12);
    var gson = ResourceLoader.getGson();
    var pending = gson.toJson(fixture.actor().getGameplay());
    win(12, battle, 1);
    var raw =
        gson.toJsonTree(fixture.actor().getGameplay().getBoxingClubResonance()).getAsJsonObject();
    var proof = raw.getAsJsonObject("missionCompletions").getAsJsonObject(String.valueOf(id));
    switch (damage) {
      case "source" -> proof.addProperty("source", "unknown-source");
      case "uid" -> proof.addProperty("uid", fixture.actor().getUid() + 1);
      case "plane", "floor" -> proof.addProperty(damage, proof.get(damage).getAsInt() + 1);
      case "sequence" -> proof.addProperty("sequence", raw.get("sequence").getAsLong() + 1);
      case "zeroTurn" ->
          proof.getAsJsonArray("wins").get(2).getAsJsonObject().addProperty("rounds", 0);
      case "reusedBattle" ->
          proof
              .getAsJsonArray("wins")
              .get(2)
              .getAsJsonObject()
              .add("battle", proof.getAsJsonArray("wins").get(0).getAsJsonObject().get("battle"));
      case "stage" -> proof.getAsJsonArray("wins").get(2).getAsJsonObject().addProperty("stage", 1);
      case "binding" ->
          proof
              .getAsJsonObject("missionTargets")
              .addProperty(
                  String.valueOf(id),
                  java.util.UUID.randomUUID() + ":" + java.util.UUID.randomUUID());
      case "extraTarget" ->
          proof
              .getAsJsonObject("missionTargets")
              .addProperty(
                  "801520999",
                  proof.getAsJsonObject("missionTargets").get(String.valueOf(id)).getAsString());
      case "missingReady" -> proof.addProperty("missionTargetsReady", false);
      default -> fail("Unknown damage fixture");
    }
    var restored = gson.fromJson(pending, GameplayState.class);
    restored.boxingClubResonance(gson.fromJson(raw, BoxingClubResonanceState.class));
    var field = emu.lunarcore.game.player.Player.class.getDeclaredField("gameplay");
    field.setAccessible(true);
    field.set(fixture.actor(), restored);
    pending(id);
  }

  @Test
  void aClonedUnrecognizedZeroLimitGoalCannotBecomeAUniversalBoxingShortcut() throws Exception {
    prerequisites(12);
    var source = activate(801520101);
    var clone =
        ResourceLoader.getGson()
            .fromJson(ResourceLoader.getGson().toJson(source), MissionInfo.SubMissionInfo.class);
    clone.setId(801520199);
    GameplayDefinitions.subs.put(clone.getId(), clone);
    fixture
        .actor()
        .getGameplay()
        .getSubMissions()
        .put(clone.getId(), new QuestState(clone.getId()));
    fixture.earn(12, 3);
    pending(clone.getId());
  }
}
