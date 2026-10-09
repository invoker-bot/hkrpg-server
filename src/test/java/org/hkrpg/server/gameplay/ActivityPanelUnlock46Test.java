package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import emu.lunarcore.data.GameData;
import emu.lunarcore.data.config.MissionInfo;
import emu.lunarcore.data.excel.*;
import emu.lunarcore.game.quest.QuestState;
import java.util.*;
import org.hkrpg.server.protocol.VersionedPacketHandler;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;

/** Original panel conditions; completed targets and operator windows are explicit fixtures. */
class ActivityPanelUnlock46Test {
  final Gson gson = new Gson();
  final GameplayProgressTest.TestPlayer player =
      new GameplayProgressTest.TestPlayer() {
        @Override
        public int getLevel() {
          return 1;
        }

        @Override
        public int getWorldLevel() {
          return 0;
        }
      };
  final Map<Integer, ActivityPanelExcel> oldPanels = new HashMap<>();
  Map<Integer, MissionInfo.SubMissionInfo> oldSubs;
  List<GameplayDefinitions.Window> oldWindows;
  QuestExcel oldQuest;
  FinishWayExcel oldRule;

  @BeforeEach
  void prepare() throws Exception {
    var original =
        JsonParser.parseString(
                new String(
                    Objects.requireNonNull(
                            getClass()
                                .getResourceAsStream("/fixtures/activity-panel-unlock-4.6.json"))
                        .readAllBytes(),
                    java.nio.charset.StandardCharsets.UTF_8))
            .getAsJsonObject()
            .getAsJsonObject("records");
    for (var raw : original.getAsJsonArray("ExcelOutput/ActivityPanel.json")) {
      var panel = gson.fromJson(raw, ActivityPanelExcel.class);
      oldPanels.put(panel.getId(), GameData.getActivityPanelExcelMap().get(panel.getId()));
      GameData.getActivityPanelExcelMap().add(panel);
    }
    oldSubs = new HashMap<>(GameplayDefinitions.subs);
    var partial =
        gson.fromJson(
            original
                .getAsJsonArray("Config/Level/Mission/8017101/MissionInfo_8017101.partial.json")
                .get(0),
            MissionInfo.class);
    for (var sub : partial.getSubMissionList()) GameplayDefinitions.subs.put(sub.getId(), sub);
    oldQuest = GameData.getQuestExcelMap().get(3000018);
    oldRule = GameData.getFinishWayExcelMap().get(3000018);
    GameData.getQuestExcelMap()
        .add(
            gson.fromJson(
                original.getAsJsonArray("ExcelOutput/QuestData.json").get(0), QuestExcel.class));
    GameData.getFinishWayExcelMap()
        .add(
            gson.fromJson(
                original.getAsJsonArray("ExcelOutput/FinishWay.json").get(0),
                FinishWayExcel.class));
    oldWindows = new ArrayList<>(GameplayDefinitions.windows);
    GameplayDefinitions.windows.clear();
    GameplayDefinitions.windows.add(
        new GameplayDefinitions.Window(10061, 199984, 0, Long.MAX_VALUE));
    GameplayDefinitions.windows.add(
        new GameplayDefinitions.Window(10025, 199985, 0, Long.MAX_VALUE));
  }

  @AfterEach
  void restore() {
    oldPanels.forEach(
        (id, row) -> {
          GameData.getActivityPanelExcelMap().keySet().remove(id);
          if (row != null) GameData.getActivityPanelExcelMap().add(row);
        });
    GameplayDefinitions.subs.clear();
    GameplayDefinitions.subs.putAll(oldSubs);
    GameplayDefinitions.windows.clear();
    GameplayDefinitions.windows.addAll(oldWindows);
    GameData.getQuestExcelMap().keySet().remove(3000018);
    GameData.getFinishWayExcelMap().keySet().remove(3000018);
    if (oldQuest != null) GameData.getQuestExcelMap().add(oldQuest);
    if (oldRule != null) GameData.getFinishWayExcelMap().add(oldRule);
  }

  void main(int id) {
    var state = new QuestState(id);
    state.advance(1, 1, 123);
    player.getGameplay().getMainMissions().put(id, state);
  }

  void sub() {
    player.getGameplay().getMainMissions().put(8017101, new QuestState(8017101));
    var state = new QuestState(801710102);
    state.advance(1, 1, 123);
    player.getGameplay().getSubMissions().put(801710102, state);
  }

  List<Integer> nativePanels() throws Exception {
    var session = new TrialActivityLifecycleTest.Session(player);
    assertTrue(
        VersionedPacketHandler.handle(
            session,
            session.getProtocol().clientCommand("GetActivityScheduleConfigCsReq"),
            new byte[0]));
    assertEquals("GetActivityScheduleConfigScRsp", session.responseName);
    assertTrue(session.response.getUnknownFields().asMap().isEmpty());
    var field = session.response.getDescriptorForType().findFieldByName("schedule_data");
    return ((List<?>) session.response.getField(field))
        .stream()
            .map(
                raw -> {
                  var row = (com.google.protobuf.DynamicMessage) raw;
                  return (Integer)
                      row.getField(row.getDescriptorForType().findFieldByName("panel_id"));
                })
            .toList();
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 1, 2, 3, 4, 5, 6, 7})
  void anyOriginalBossPanelBranchCanUnlockWithoutCompletingItsOtherBranches(int mask)
      throws Exception {
    if ((mask & 1) != 0) main(4020103);
    if ((mask & 2) != 0) main(4010106);
    if ((mask & 4) != 0) sub();
    assertEquals(mask != 0, ActivityAccess.unlocked(player, 10061));
    assertEquals(mask == 0 ? List.of() : List.of(10061), nativePanels());
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 1, 2, 3})
  void originalQuestClosePanelRequiresTheActualClosedPhase(int phase) throws Exception {
    var state = new QuestState(3000018);
    if (phase > 0) state.advance(1, 1, 123);
    if (phase > 1) state.reserveReward();
    if (phase > 2) state.closeReward();
    player.getQuestStates().put(3000018, state);
    assertEquals(phase == 3, ActivityAccess.unlocked(player, 10025));
    assertEquals(phase == 3 ? List.of(10025) : List.of(), nativePanels());
  }

  void condition(String expression) {
    var row = new JsonObject();
    row.addProperty("PanelID", 10061);
    if (expression == null) row.add("UnlockConditions", JsonNull.INSTANCE);
    else row.addProperty("UnlockConditions", expression);
    GameData.getActivityPanelExcelMap().add(gson.fromJson(row, ActivityPanelExcel.class));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "[PlayerLevel:1]|[Unknown:1]",
        "[Unknown:1]|[PlayerLevel:1]",
        "[PlayerLevel:1]|",
        "|[PlayerLevel:1]",
        "[PlayerLevel:1]||[PlayerLevel:1]",
        "[PlayerLevel:1],",
        ",[PlayerLevel:1]",
        "[PlayerLevel:1],,[PlayerLevel:1]",
        "[PlayerLevel:2147483648]",
        "[PlayerLevel:1.0]",
        "[FinishSubMission:-1]",
        "[QuestClose:0]",
        "[PlayerLevel:1]junk",
        "[PlayerLevel:1]|[FinishSubMission:0]"
      })
  void theWholeExpressionIsValidatedBeforeAnyTrueBranchCanExposeThePanel(String expression)
      throws Exception {
    condition(expression);
    assertFalse(assertDoesNotThrow(() -> ActivityAccess.unlocked(player, 10061)));
    assertEquals(List.of(), nativePanels());
  }

  @Test
  void explicitNullConditionsCannotTurnIntoAnUnconditionalPanel() throws Exception {
    condition(null);
    assertFalse(ActivityAccess.unlocked(player, 10061));
    assertEquals(List.of(), nativePanels());
  }

  @ParameterizedTest
  @ValueSource(strings = {"parent", "cancelled", "wrongProgress", "wrongIdentity"})
  void aDamagedSubMissionRecordCannotSatisfyTheOriginalThirdBranch(String damage) throws Exception {
    sub();
    if (damage.equals("parent")) player.getGameplay().getMainMissions().remove(8017101);
    if (damage.equals("cancelled")) player.getGameplay().getCancelledSubs().add(801710102);
    if (damage.equals("wrongIdentity"))
      player.getGameplay().getSubMissions().put(801710102, new QuestState(801710103));
    if (damage.equals("wrongProgress")) {
      var f = QuestState.class.getDeclaredField("progress");
      f.setAccessible(true);
      f.setInt(player.getGameplay().getSubMissions().get(801710102), 2);
    }
    assertFalse(ActivityAccess.unlocked(player, 10061));
  }

  @ParameterizedTest
  @ValueSource(strings = {"goal", "time", "identity", "missingRule"})
  void aClosedQuestStillNeedsItsOwnGoalIdentityAndCompletionTime(String damage) throws Exception {
    var state = new QuestState(3000018);
    state.advance(1, 1, 123);
    state.reserveReward();
    state.closeReward();
    player.getQuestStates().put(3000018, state);
    if (damage.equals("missingRule")) GameData.getFinishWayExcelMap().keySet().remove(3000018);
    else {
      var f =
          QuestState.class.getDeclaredField(
              damage.equals("goal")
                  ? "progress"
                  : damage.equals("time") ? "finishTime" : "questId");
      f.setAccessible(true);
      if (damage.equals("time")) f.setLong(state, 0);
      else f.setInt(state, damage.equals("goal") ? 2 : 3000019);
    }
    assertFalse(ActivityAccess.unlocked(player, 10025));
  }

  @ParameterizedTest
  @CsvSource({
    "[PlayerLevel:1]&[WorldLevel:0],true",
    "[PlayerLevel:2]&[WorldLevel:0],false",
    "[PlayerLevel:2]&[WorldLevel:0]|[PlayerLevel:1],true",
    "[PlayerLevel:1]|[PlayerLevel:2]&[WorldLevel:1],true"
  })
  void conjunctionIsEvaluatedBeforeAlternativeBranches(String expression, boolean expected) {
    condition(expression);
    assertEquals(expected, ActivityAccess.unlocked(player, 10061));
  }

  @Test
  void existingCommaConjunctionStillRequiresEveryCondition() {
    condition("[PlayerLevel:1],[WorldLevel:0]");
    assertTrue(ActivityAccess.unlocked(player, 10061));
    condition("[PlayerLevel:2],[WorldLevel:0]");
    assertFalse(ActivityAccess.unlocked(player, 10061));
  }

  static java.util.stream.Stream<Arguments> savedStates() {
    return java.util.stream.Stream.concat(
        java.util.stream.IntStream.range(0, 8).mapToObj(mask -> Arguments.of("branches", mask)),
        java.util.stream.IntStream.range(0, 4).mapToObj(phase -> Arguments.of("quest", phase)));
  }

  @ParameterizedTest
  @MethodSource("savedStates")
  void actualPlayerStorageRestoresEveryOriginalGateStateTwice(String mode, int value)
      throws Exception {
    if (mode.equals("branches")) {
      if ((value & 1) != 0) main(4020103);
      if ((value & 2) != 0) main(4010106);
      if ((value & 4) != 0) sub();
    } else {
      var state = new QuestState(3000018);
      if (value > 0) state.advance(1, 1, 123);
      if (value > 1) state.reserveReward();
      if (value > 2) state.closeReward();
      player.getQuestStates().put(3000018, state);
    }
    boolean expected = mode.equals("branches") ? value != 0 : value == 3;
    int panel = mode.equals("branches") ? 10061 : 10025;
    var server =
        new de.bwaldvogel.mongo.MongoServer(new de.bwaldvogel.mongo.backend.memory.MemoryBackend());
    var address = server.bind();
    try (var client =
        com.mongodb.client.MongoClients.create(
            "mongodb://127.0.0.1:" + address.getPort() + "/?directConnection=true")) {
      var store = dev.morphia.Morphia.createDatastore(client, "activity-panel-unlock-fixture");
      store.getMapper().mapPackage("org.hkrpg.server.gameplay");
      store
          .getMapper()
          .map(
              emu.lunarcore.game.player.Player.class,
              QuestState.class,
              emu.lunarcore.util.Position.class);
      var document = new emu.lunarcore.game.player.Player();
      MissionTrialProvisioning46Test.set(document, "uid", 199985);
      MissionTrialProvisioning46Test.set(document, "gameplay", player.getGameplay());
      document.getQuestStates().putAll(player.getQuestStates());
      store.save(document);
      String gameplay = gson.toJson(document.getGameplay()),
          quests = gson.toJson(document.getQuestStates());
      for (int attempt = 0; attempt < 2; attempt++) {
        var saved =
            Objects.requireNonNull(store.find(emu.lunarcore.game.player.Player.class).first());
        assertEquals(gameplay, gson.toJson(saved.getGameplay()));
        assertEquals(quests, gson.toJson(saved.getQuestStates()));
        assertEquals(expected, ActivityAccess.unlocked(saved, panel));
        assertEquals(gameplay, gson.toJson(saved.getGameplay()));
        assertEquals(quests, gson.toJson(saved.getQuestStates()));
      }
    } finally {
      server.shutdownNow();
    }
  }
}
