package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import emu.lunarcore.data.GameData;
import emu.lunarcore.data.excel.MainMissionExcel;
import emu.lunarcore.game.quest.QuestState;
import emu.lunarcore.server.packet.Retcode;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Completion flags alone cannot unlock prerequisites or release an existing reward plan. */
class MissionCompletionRecordsTest {
  final SubMissionRewardTest fixture = new SubMissionRewardTest();
  static final int MAIN = SubMissionRewardTest.MAIN,
      SUB = SubMissionRewardTest.SUB,
      GATE = 19997009;
  SubMissionRewardTest.Recipient actor;

  @BeforeEach
  void setup() {
    fixture.fixture();
    actor = new SubMissionRewardTest.Recipient();
    assertEquals(Retcode.SUCC, actor.getMissionManager().accept(MAIN));
  }

  @AfterEach
  void cleanup() {
    fixture.clean();
    GameData.getMainMissionExcelMap().keySet().remove(GATE);
    GameData.getMissionInfos().remove(GATE);
  }

  QuestState complete(int id) {
    var state = new QuestState(id);
    assertTrue(state.advance(1, 1, 1));
    return state;
  }

  QuestState damage(int id, String mode) {
    var row = fixture.gson.toJsonTree(complete(id)).getAsJsonObject();
    switch (mode) {
      case "null-phase" -> row.add("phase", JsonNull.INSTANCE);
      case "zero-time" -> row.addProperty("finishTime", 0);
      case "negative-time" -> row.addProperty("finishTime", -1);
      case "zero-progress" -> row.addProperty("progress", 0);
      case "negative-progress" -> row.addProperty("progress", -1);
      case "too-much-progress" -> row.addProperty("progress", 2);
      case "wrong-id" -> row.addProperty("questId", id + 1000000);
    }
    return fixture.gson.fromJson(row, QuestState.class);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "null-phase",
        "zero-time",
        "negative-time",
        "zero-progress",
        "negative-progress",
        "too-much-progress",
        "wrong-id"
      })
  void badMainCompletionDoesNotUnlockTheNextMainMission(String mode) {
    actor.getGameplay().getMainMissions().put(MAIN, damage(MAIN, mode));
    new GameplayProgressTest().mission(GATE, "Manual", Integer.toString(GATE + 1), "[]");
    var row =
        fixture.gson.toJsonTree(GameData.getMainMissionExcelMap().get(GATE)).getAsJsonObject();
    row.add(
        "TakeParam",
        JsonParser.parseString("[{\"Type\":\"MultiSequence\",\"Value\":" + MAIN + "}]"));
    GameData.getMainMissionExcelMap().add(fixture.gson.fromJson(row, MainMissionExcel.class));
    assertEquals(Retcode.MISSION_NOT_DOING, actor.getMissionManager().accept(GATE));
    assertFalse(actor.getGameplay().getMainMissions().containsKey(GATE));
    assertFalse(actor.getMissionManager().finished(MAIN));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "null-phase",
        "zero-time",
        "negative-time",
        "zero-progress",
        "negative-progress",
        "too-much-progress",
        "wrong-id"
      })
  void badSubCompletionDoesNotUnlockItsNextChild(String mode) {
    addNextChild();
    actor.getGameplay().getSubMissions().put(SUB, damage(SUB, mode));
    assertDoesNotThrow(() -> actor.getMissionManager().refresh());
    assertNull(actor.getMissionManager().sub(SUB + 2));
    assertEquals(0, actor.attempts);
  }

  void addNextChild() {
    var main = GameData.getMissionInfos().get(MAIN);
    var value = fixture.gson.toJsonTree(main).getAsJsonObject();
    value
        .getAsJsonArray("SubMissionList")
        .add(
            JsonParser.parseString(
                "{\"ID\":"
                    + (SUB + 2)
                    + ",\"MainMissionID\":"
                    + MAIN
                    + ",\"TakeType\":\"AnySequence\",\"TakeParamIntList\":["
                    + SUB
                    + "],\"FinishType\":\"UnsupportedCondition\",\"Progress\":1}"));
    var updated = fixture.gson.fromJson(value, emu.lunarcore.data.config.MissionInfo.class);
    GameData.getMissionInfos().put(MAIN, updated);
    for (var sub : updated.getSubMissionList()) GameplayDefinitions.subs.put(sub.getId(), sub);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "null-phase",
        "zero-time",
        "negative-time",
        "zero-progress",
        "negative-progress",
        "too-much-progress",
        "wrong-id"
      })
  void badMainCompletionCannotReleaseItsResourceReward(String mode) {
    var row =
        fixture.gson.toJsonTree(GameData.getMainMissionExcelMap().get(MAIN)).getAsJsonObject();
    row.addProperty("RewardID", SubMissionRewardTest.REWARD);
    GameData.getMainMissionExcelMap().add(fixture.gson.fromJson(row, MainMissionExcel.class));
    actor.getGameplay().getMainMissions().put(MAIN, damage(MAIN, mode));
    assertDoesNotThrow(() -> actor.getMissionManager().refresh());
    assertEquals(0, actor.attempts);
    assertFalse(actor.getRewardLedger().granted("mission:" + MAIN));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "null-phase",
        "zero-time",
        "negative-time",
        "zero-progress",
        "negative-progress",
        "too-much-progress",
        "wrong-id"
      })
  void aFrozenSubRewardPlanDoesNotReplaceMissingCompletionProof(String mode) {
    new MissionRewards(actor).freezeSub(GameplayDefinitions.subs.get(SUB));
    actor.getGameplay().getSubMissions().put(SUB, damage(SUB, mode));
    assertDoesNotThrow(() -> actor.getMissionManager().refresh());
    assertEquals(0, actor.attempts);
    assertFalse(actor.getRewardLedger().granted("submission:" + SUB));
    assertTrue(actor.getGameplay().getMissionRewardPlans().containsKey("submission:" + SUB));
  }

  @ParameterizedTest
  @ValueSource(strings = {"wrong-id", "positive-time", "negative-progress", "completed-progress"})
  void aDamagedDoingParentCannotAuthorizeAnActualTalk(String mode) {
    var row = fixture.gson.toJsonTree(new QuestState(MAIN)).getAsJsonObject();
    if (mode.equals("wrong-id")) row.addProperty("questId", MAIN + 1000000);
    else if (mode.equals("positive-time")) row.addProperty("finishTime", 1);
    else if (mode.equals("completed-progress")) row.addProperty("progress", 1);
    else row.addProperty("progress", -1);
    actor.getGameplay().getMainMissions().put(MAIN, fixture.gson.fromJson(row, QuestState.class));
    assertEquals(Retcode.MISSION_NOT_DOING, actor.getMissionManager().finishTalk(SUB, "earned"));
    assertEquals(0, actor.attempts);
    assertEquals(0, actor.getMissionManager().sub(SUB).getProgress());
  }

  @ParameterizedTest
  @ValueSource(strings = {"FINISHED", "CLAIMING", "CLOSED"})
  void explicitValidMainCompletionAndRewardStagesKeepTheirHistory(String phase) {
    var state = complete(MAIN);
    if (!phase.equals("FINISHED")) assertTrue(state.reserveReward());
    if (phase.equals("CLOSED")) state.closeReward();
    actor.getGameplay().getMainMissions().put(MAIN, state);
    assertTrue(actor.getMissionManager().finished(MAIN));
    actor.getGameplay().getCancelledMissions().add(MAIN);
    assertFalse(actor.getMissionManager().finished(MAIN));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "null-phase",
        "zero-time",
        "negative-time",
        "zero-progress",
        "negative-progress",
        "too-much-progress",
        "wrong-id"
      })
  void aFrozenMainRewardPlanDoesNotReplaceMissingCompletionProof(String mode) {
    var row =
        fixture.gson.toJsonTree(GameData.getMainMissionExcelMap().get(MAIN)).getAsJsonObject();
    row.addProperty("RewardID", SubMissionRewardTest.REWARD);
    var config = fixture.gson.fromJson(row, MainMissionExcel.class);
    GameData.getMainMissionExcelMap().add(config);
    new MissionRewards(actor).freezeMain(config);
    actor.getGameplay().getMainMissions().put(MAIN, damage(MAIN, mode));
    assertDoesNotThrow(() -> actor.getMissionManager().refresh());
    assertEquals(0, actor.attempts);
    assertFalse(actor.getRewardLedger().granted("mission:" + MAIN));
    assertTrue(actor.getGameplay().getMissionRewardPlans().containsKey("mission:" + MAIN));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "null-phase",
        "zero-time",
        "negative-time",
        "zero-progress",
        "negative-progress",
        "too-much-progress",
        "wrong-id"
      })
  void damagedStartCannotOpenAStoryInterval(String mode) {
    actor.getGameplay().getSubMissions().put(SUB, damage(SUB, mode));
    assertFalse(
        StoryLineConditions.matches(actor, "[BetweenSubMission:" + SUB + "," + (SUB + 1) + "]"));
    assertEquals(0, actor.attempts);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "null-phase",
        "zero-time",
        "negative-time",
        "zero-progress",
        "negative-progress",
        "too-much-progress",
        "wrong-id"
      })
  void damagedEndCannotBecomeStoryCompletion(String mode) {
    actor.getGameplay().getSubMissions().put(SUB + 1, damage(SUB + 1, mode));
    assertFalse(StoryLineConditions.finished(actor, SUB + 1));
    assertFalse(
        StoryLineConditions.matches(actor, "[BetweenSubMission:" + SUB + "," + (SUB + 1) + "]"));
    assertEquals(0, actor.attempts);
  }

  emu.lunarcore.data.config.GroupInfo group(String type, String phase, int id) {
    return fixture.gson.fromJson(
        "{\"Category\":\"Mission\",\"LoadCondition\":{\"Conditions\":[{\"Type\":\""
            + type
            + "\",\"Phase\":\""
            + phase
            + "\",\"ID\":"
            + id
            + "}]}}",
        emu.lunarcore.data.config.GroupInfo.class);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "null-phase",
        "zero-time",
        "negative-time",
        "zero-progress",
        "negative-progress",
        "too-much-progress",
        "wrong-id"
      })
  void damagedMainCannotLoadAFinishGatedMapGroup(String mode) {
    actor.getGameplay().getMainMissions().put(MAIN, damage(MAIN, mode));
    assertFalse(actor.getMissionManager().shouldLoadGroup(group("MainMission", "Finish", MAIN)));
    assertFalse(actor.getMissionManager().shouldLoadGroup(group("MainMission", "Accept", MAIN)));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "null-phase",
        "zero-time",
        "negative-time",
        "zero-progress",
        "negative-progress",
        "too-much-progress",
        "wrong-id"
      })
  void damagedSubCannotLoadAFinishGatedMapGroup(String mode) {
    actor.getGameplay().getSubMissions().put(SUB, damage(SUB, mode));
    assertFalse(actor.getMissionManager().shouldLoadGroup(group("SubMission", "Finish", SUB)));
    assertFalse(actor.getMissionManager().shouldLoadGroup(group("SubMission", "Accept", SUB)));
  }

  @ParameterizedTest
  @ValueSource(strings = {"wrong-id", "positive-time", "negative-progress", "completed-progress"})
  void damagedDoingChildCannotBeRepairedByAnActualTalk(String mode) {
    var row = fixture.gson.toJsonTree(new QuestState(SUB)).getAsJsonObject();
    if (mode.equals("wrong-id")) row.addProperty("questId", SUB + 1000000);
    else if (mode.equals("positive-time")) row.addProperty("finishTime", 1);
    else if (mode.equals("completed-progress")) row.addProperty("progress", 1);
    else row.addProperty("progress", -1);
    actor.getGameplay().getSubMissions().put(SUB, fixture.gson.fromJson(row, QuestState.class));
    assertEquals(Retcode.MISSION_NOT_DOING, actor.getMissionManager().finishTalk(SUB, "earned"));
    assertEquals(0, actor.attempts);
    assertFalse(actor.getGameplay().getMissionRewardPlans().containsKey("submission:" + SUB));
  }

  @ParameterizedTest
  @ValueSource(strings = {"FINISHED", "CLAIMING", "CLOSED"})
  void validChildHistoryUnlocksTheNextChildAndKeepsOriginalPhase(String phase) {
    addNextChild();
    var state = complete(SUB);
    if (!phase.equals("FINISHED")) state.reserveReward();
    if (phase.equals("CLOSED")) state.closeReward();
    actor.getGameplay().getSubMissions().put(SUB, state);
    actor.getMissionManager().refresh();
    assertNotNull(actor.getMissionManager().sub(SUB + 2));
    assertTrue(StoryLineConditions.finished(actor, SUB));
    assertEquals(QuestState.Phase.valueOf(phase), state.getPhase());
    assertEquals(0, actor.attempts);
  }

  @Test
  void resourceGoalAboveOneIsCompletedAndRewardedByTheActualTalk() {
    var row = fixture.gson.toJsonTree(GameplayDefinitions.subs.get(SUB)).getAsJsonObject();
    row.addProperty("Progress", 2);
    GameplayDefinitions.subs.put(
        SUB,
        fixture.gson.fromJson(row, emu.lunarcore.data.config.MissionInfo.SubMissionInfo.class));
    assertEquals(Retcode.SUCC, actor.getMissionManager().finishTalk(SUB, "earned"));
    assertEquals(2, actor.getMissionManager().sub(SUB).getProgress());
    assertEquals(QuestState.Phase.CLOSED, actor.getMissionManager().sub(SUB).getPhase());
    assertTrue(StoryLineConditions.finished(actor, SUB));
    assertEquals(7, actor.delivered);
    actor.getMissionManager().refresh();
    assertEquals(1, actor.attempts);
  }

  @Test
  void concurrentTalkRequestsSettleOneCompletionAndOneFrozenPayout() throws Exception {
    var workers = java.util.concurrent.Executors.newFixedThreadPool(6);
    try {
      var calls = new ArrayList<java.util.concurrent.Callable<Retcode>>();
      for (int i = 0; i < 24; i++)
        calls.add(() -> actor.getMissionManager().finishTalk(SUB, "earned"));
      int completed = 0;
      for (var result : workers.invokeAll(calls)) {
        var code = result.get();
        assertTrue(code == Retcode.SUCC || code == Retcode.MISSION_NOT_DOING);
        if (code == Retcode.SUCC) completed++;
      }
      assertEquals(1, completed);
      assertEquals(1, actor.attempts);
      assertEquals(7, actor.delivered);
      assertEquals(QuestState.Phase.CLOSED, actor.getMissionManager().sub(SUB).getPhase());
    } finally {
      workers.shutdownNow();
    }
  }

  @Test
  void actualMorphiaReloadKeepsInvalidCompletionPendingAndValidPaidHistoryClosed()
      throws Exception {
    var server =
        new de.bwaldvogel.mongo.MongoServer(new de.bwaldvogel.mongo.backend.memory.MemoryBackend());
    var address = server.bind();
    try (var client =
        com.mongodb.client.MongoClients.create(
            "mongodb://" + address.getHostString() + ":" + address.getPort())) {
      var store = dev.morphia.Morphia.createDatastore(client, "completion_records");
      store.getMapper().map(emu.lunarcore.game.player.Player.class, QuestState.class);
      new MissionRewards(actor).freezeSub(GameplayDefinitions.subs.get(SUB));
      actor.getGameplay().getSubMissions().put(SUB, damage(SUB, "zero-time"));
      reload(store);
      actor.getMissionManager().refresh();
      assertEquals(0, actor.attempts);
      assertFalse(StoryLineConditions.finished(actor, SUB));
      assertFalse(actor.getRewardLedger().granted("submission:" + SUB));
      actor.getGameplay().getSubMissions().put(SUB, complete(SUB));
      actor.getMissionManager().refresh();
      assertEquals(1, actor.attempts);
      assertEquals(7, actor.delivered);
      reload(store);
      actor.getMissionManager().refresh();
      assertEquals(0, actor.attempts);
      assertTrue(StoryLineConditions.finished(actor, SUB));
      assertEquals(QuestState.Phase.CLOSED, actor.getMissionManager().sub(SUB).getPhase());
      assertTrue(actor.getRewardLedger().granted("submission:" + SUB));
    } finally {
      server.shutdownNow();
    }
  }

  void reload(dev.morphia.Datastore store) throws Exception {
    var base = new emu.lunarcore.game.player.Player();
    var uid = emu.lunarcore.game.player.Player.class.getDeclaredField("uid");
    uid.setAccessible(true);
    uid.setInt(base, 123456791);
    var field = emu.lunarcore.game.player.Player.class.getDeclaredField("gameplay");
    field.setAccessible(true);
    field.set(base, actor.getGameplay());
    store.save(base);
    var restored =
        store
            .find(emu.lunarcore.game.player.Player.class)
            .filter(dev.morphia.query.filters.Filters.eq("_id", 123456791))
            .first();
    assertNotNull(restored);
    actor = new SubMissionRewardTest.Recipient();
    field.set(actor, restored.getGameplay());
  }

  @Test
  void anotherSavedMapKeyCannotReleaseTheActualMainReward() {
    var row =
        fixture.gson.toJsonTree(GameData.getMainMissionExcelMap().get(MAIN)).getAsJsonObject();
    row.addProperty("RewardID", SubMissionRewardTest.REWARD);
    GameData.getMainMissionExcelMap().add(fixture.gson.fromJson(row, MainMissionExcel.class));
    actor.getGameplay().getMainMissions().put(GATE, complete(MAIN));
    assertDoesNotThrow(() -> actor.getMissionManager().refresh());
    assertEquals(0, actor.attempts);
    assertFalse(actor.getRewardLedger().granted("mission:" + MAIN));
    assertEquals(
        QuestState.Phase.DOING, actor.getGameplay().getMainMissions().get(MAIN).getPhase());
  }
}
