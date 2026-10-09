package org.hkrpg.server.protocol;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import emu.lunarcore.data.GameData;
import emu.lunarcore.data.excel.*;
import emu.lunarcore.game.quest.QuestState;
import emu.lunarcore.server.packet.Retcode;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.hkrpg.server.gameplay.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Original daily rules and recovery props. The original room is isolated; no game UI acceptance.
 */
class DailyPropDestruct46Test {
  final UniversePropRecovery46Test helper = new UniversePropRecovery46Test();
  List<GameplayDefinitions.DailyPool> beforePool;
  Map<Integer, QuestExcel> beforeQuests = new HashMap<>();
  Map<Integer, FinishWayExcel> beforeRules = new HashMap<>();
  Map<Integer, RewardExcel> beforeRewards = new HashMap<>();
  PropExcel beforeConsole;
  JsonArray fixture;

  @BeforeEach
  void setup() throws Exception {
    helper.setup();
    try (var in = getClass().getResourceAsStream("/fixtures/daily-prop-points-4.6.json")) {
      assertNotNull(in);
      fixture =
          JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8))
              .getAsJsonArray();
    }
    beforePool = new ArrayList<>(GameplayDefinitions.dailyPool);
    GameplayDefinitions.dailyPool.clear();
    for (var value : fixture) {
      var record = value.getAsJsonObject();
      var raw = record.getAsJsonObject("row");
      switch (record.get("kind").getAsString()) {
        case "prop" -> {
          beforeConsole = GameData.getPropExcelMap().get(1002);
          var compatible = raw.deepCopy();
          compatible.addProperty(
              "PropName", raw.getAsJsonObject("PropName").get("Hash").getAsLong());
          var prop = helper.gson.fromJson(compatible, PropExcel.class);
          prop.onLoad();
          GameData.getPropExcelMap().add(prop);
        }
        case "pool" ->
            GameplayDefinitions.dailyPool.add(
                helper.gson.fromJson(raw, GameplayDefinitions.DailyPool.class));
        case "quest" -> {
          int id = raw.get("QuestID").getAsInt();
          beforeQuests.put(id, GameData.getQuestExcelMap().get(id));
          GameData.getQuestExcelMap().add(helper.gson.fromJson(raw, QuestExcel.class));
        }
        case "rule" -> {
          int id = raw.get("ID").getAsInt();
          beforeRules.put(id, GameData.getFinishWayExcelMap().get(id));
          GameData.getFinishWayExcelMap().add(helper.gson.fromJson(raw, FinishWayExcel.class));
        }
        case "reward" -> {
          int id = raw.get("RewardID").getAsInt();
          beforeRewards.put(id, GameData.getRewardExcelMap().get(id));
          var row = helper.gson.fromJson(raw, RewardExcel.class);
          row.onLoad();
          GameData.getRewardExcelMap().add(row);
        }
      }
    }
    helper.reloadScene();
    helper
        .player
        .getDailyManager()
        .setClock(Clock.fixed(Instant.parse("2026-10-05T12:00:00Z"), ZoneOffset.UTC));
    helper.player.getDailyManager().ensureDay();
  }

  @AfterEach
  void restore() {
    GameplayDefinitions.dailyPool.clear();
    GameplayDefinitions.dailyPool.addAll(beforePool);
    for (var old : beforeQuests.entrySet()) {
      GameData.getQuestExcelMap().keySet().remove(old.getKey());
      if (old.getValue() != null) GameData.getQuestExcelMap().add(old.getValue());
    }
    for (var old : beforeRules.entrySet()) {
      GameData.getFinishWayExcelMap().keySet().remove(old.getKey());
      if (old.getValue() != null) GameData.getFinishWayExcelMap().add(old.getValue());
    }
    for (var old : beforeRewards.entrySet()) {
      GameData.getRewardExcelMap().keySet().remove(old.getKey());
      if (old.getValue() != null) GameData.getRewardExcelMap().add(old.getValue());
    }
    GameData.getPropExcelMap().keySet().remove(1002);
    if (beforeConsole != null) GameData.getPropExcelMap().add(beforeConsole);
    helper.cleanup();
  }

  QuestState state(int id) {
    var state = helper.player.getGameplay().getDailyQuests().get(id);
    assertNotNull(state, "Original supported daily objective was not selected");
    return state;
  }

  @ParameterizedTest
  @ValueSource(ints = {2100125, 2100144})
  void threeActualOriginalPropsFinishAndCreditOriginalDailyReward(int id) {
    assertEquals(QuestState.Phase.DOING, state(id).getPhase());
    for (int instance : List.of(300012, 300013, 300015))
      assertTrue(helper.player.scene.destroyProp(helper.prop(instance)));
    assertEquals(3, state(id).getProgress());
    assertEquals(QuestState.Phase.FINISHED, state(id).getPhase());
    assertEquals(Retcode.SUCC, helper.player.getDailyManager().claimQuest(id).retcode());
    assertEquals(200, helper.player.getGameplay().getDailyPoints());
    assertEquals(
        Retcode.QUEST_REWARD_ALREADY_TAKEN,
        helper.player.getDailyManager().claimQuest(id).retcode());
    assertEquals(200, helper.player.getGameplay().getDailyPoints());
  }

  @Test
  void stalePropCannotIncrementDailyProgressAgain() {
    state(2100125);
    var prop = helper.prop(300012);
    assertTrue(helper.player.scene.destroyProp(prop));
    assertEquals(1, state(2100125).getProgress());
    assertFalse(helper.player.scene.destroyProp(prop));
    assertEquals(1, state(2100125).getProgress());
  }

  @Test
  void anOldSceneCannotCreditTheCurrentDailyObjective() {
    state(2100125);
    var scene = helper.player.scene;
    var prop = helper.prop(300012);
    helper.reloadScene();
    assertFalse(scene.destroyProp(prop));
    assertEquals(0, state(2100125).getProgress());
  }

  @Test
  void aRevivalConsoleIsNotADestructibleEvent() {
    state(2100125);
    assertFalse(helper.player.scene.destroyProp(helper.prop(300019)));
    assertEquals(0, state(2100125).getProgress());
  }

  @Test
  void concurrentAttemptsAtTheSamePropCreditOneDestruction() throws Exception {
    state(2100125);
    var prop = helper.prop(300012);
    var executor = Executors.newFixedThreadPool(2);
    try {
      var results =
          executor.invokeAll(
              List.of(
                  () -> helper.player.scene.destroyProp(prop),
                  () -> helper.player.scene.destroyProp(prop)));
      var counts = new ArrayList<Object>();
      for (var result : results) counts.add(result.get());
      assertEquals(1, counts.stream().filter(Boolean.TRUE::equals).count());
      assertEquals(1, state(2100125).getProgress());
    } finally {
      executor.shutdownNow();
    }
  }

  @Test
  void theFirstDestructionAfterFourAmUsesTheNewDailyState() {
    var old = state(2100125);
    var manager = helper.player.getDailyManager();
    manager.setClock(Clock.fixed(Instant.parse("2026-10-05T19:59:59Z"), ZoneOffset.UTC));
    assertTrue(helper.player.scene.destroyProp(helper.prop(300012)));
    assertEquals(1, old.getProgress());
    long day = helper.player.getGameplay().getDailyDay();
    manager.setClock(Clock.fixed(Instant.parse("2026-10-05T20:00:00Z"), ZoneOffset.UTC));
    assertTrue(helper.player.scene.destroyProp(helper.prop(300013)));
    assertEquals(day + 1, helper.player.getGameplay().getDailyDay());
    assertNotSame(old, state(2100125));
    assertEquals(1, state(2100125).getProgress());
    assertEquals(1, old.getProgress());
  }

  @ParameterizedTest
  @ValueSource(strings = {"comparison", "number", "text", "list", "items", "type"})
  void unresolvedHotChangedRulesDoNotBorrowTheOriginalDestructionRule(String mode) {
    state(2100125);
    JsonObject raw = null;
    for (var value : fixture) {
      var row = value.getAsJsonObject();
      if ("rule".equals(row.get("kind").getAsString())
          && row.getAsJsonObject("row").get("ID").getAsInt() == 2100125)
        raw = row.getAsJsonObject("row").deepCopy();
    }
    assertNotNull(raw);
    switch (mode) {
      case "comparison" -> raw.addProperty("ParamType", "NoPara");
      case "number" -> raw.addProperty("ParamInt1", 1);
      case "text" -> raw.addProperty("ParamStr1", "Unknown");
      case "list" -> raw.add("ParamIntList", JsonParser.parseString("[1]"));
      case "items" -> raw.add("ParamItemList", JsonParser.parseString("[{}]"));
      case "type" -> raw.addProperty("FinishType", "Custom.PropDestruct");
    }
    GameData.getFinishWayExcelMap().add(helper.gson.fromJson(raw, FinishWayExcel.class));
    assertTrue(helper.player.scene.destroyProp(helper.prop(300012)));
    assertEquals(0, state(2100125).getProgress());
    assertEquals(1, state(2100144).getProgress());
  }
}
