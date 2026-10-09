package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import emu.lunarcore.data.GameData;
import emu.lunarcore.data.config.MissionTaskInfo;
import emu.lunarcore.data.excel.*;
import emu.lunarcore.game.battle.Battle;
import emu.lunarcore.proto.BattleEndStatusOuterClass.BattleEndStatus;
import emu.lunarcore.proto.BattleStatisticsOuterClass.BattleStatistics;
import emu.lunarcore.server.packet.Retcode;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class MissionBattleCustomValueTest {
  static final int MAIN = 2022005, TARGET = 202200506, ALTERNATE = 202200507;
  static final int TRIGGER = 202200502, EVENT = 20241054, STAGE = 202410540;
  final Gson gson = new Gson();
  final GameplayProgressTest missions = new GameplayProgressTest();
  final MissionFarmingBattleTest.Service service = new MissionFarmingBattleTest.Service();
  final Map<Integer, StageExcel> oldStages = new HashMap<>();
  List<PlaneEventExcel> oldEvents;
  JsonObject source;

  static class Fighter extends MissionFarmingBattleTest.Farmer {
    int plane = 40235, floor = 40235001, entry = 4023501;
    GameplayState saved;

    public int getPlaneId() {
      return plane;
    }

    public int getFloorId() {
      return floor;
    }

    public int getEntryId() {
      return entry;
    }

    public void save() {
      saved = new Gson().fromJson(new Gson().toJson(getGameplay()), GameplayState.class);
    }
  }

  @BeforeEach
  void resources() throws Exception {
    try (var input = getClass().getResourceAsStream("/gameplay/battle-custom-values-4.6.json")) {
      source =
          JsonParser.parseString(
                  new String(Objects.requireNonNull(input).readAllBytes(), StandardCharsets.UTF_8))
              .getAsJsonObject();
    }
    oldEvents =
        GameData.getPlaneEventExcelMap().values().stream()
            .filter(e -> e.getEventId() == EVENT)
            .toList();
    GameData.getPlaneEventExcelMap().values().removeIf(e -> e.getEventId() == EVENT);
    for (var row : source.getAsJsonArray("events"))
      GameData.getPlaneEventExcelMap().add(gson.fromJson(row, PlaneEventExcel.class));
    for (var row : source.getAsJsonArray("stages")) {
      var stage = emu.lunarcore.data.ResourceLoader.getGson().fromJson(row, StageExcel.class);
      oldStages.put(stage.getId(), GameData.getStageExcelMap().get(stage.getId()));
      stage.onLoad();
      GameData.getStageExcelMap().add(stage);
    }
    mission();
  }

  void mission() {
    missions.mission(MAIN, "Manual", "202200505", source.getAsJsonArray("subs").toString());
    GameplayDefinitions.subs
        .get(TRIGGER)
        .setTask(gson.fromJson(source.get("battleScript"), MissionTaskInfo.class));
  }

  @AfterEach
  void cleanup() {
    missions.reset();
    GameData.getMainMissionExcelMap().keySet().remove(MAIN);
    GameData.getMissionInfos().remove(MAIN);
    for (var row : oldStages.entrySet()) {
      if (row.getValue() == null) GameData.getStageExcelMap().keySet().remove(row.getKey());
      else GameData.getStageExcelMap().add(row.getValue());
    }
    GameData.getPlaneEventExcelMap().values().removeIf(e -> e.getEventId() == EVENT);
    if (oldEvents != null) oldEvents.forEach(GameData.getPlaneEventExcelMap()::add);
  }

  Fighter accepted() {
    var p = new Fighter();
    assertEquals(Retcode.SUCC, p.getMissionManager().accept(MAIN));
    assertEquals(Retcode.SUCC, p.getMissionManager().finishTalk(202200501, "Mission_202200501"));
    assertNotNull(p.getMissionManager().sub(TARGET));
    assertEquals(0, progress(p));
    return p;
  }

  Battle start(Fighter p) {
    return Objects.requireNonNull(service.startBattle(p, EVENT, p.getWorldLevel()));
  }

  int progress(Fighter p) {
    return p.getMissionManager().sub(TARGET).getProgress();
  }

  BattleStatistics stats(float value) {
    var stats = BattleStatistics.newInstance();
    stats.getMutableCustomValues().next().setKey("_PlayerWin").setValue(value);
    return stats;
  }

  Battle finish(Fighter p, Battle b, BattleStatistics stats) {
    return service.finishBattle(
        p, b.getId(), b.getStage().getId(), BattleEndStatus.BATTLE_END_WIN, stats);
  }

  @Test
  void actualSourceEventAndExactReportedValueCompleteOnlyTheDeclaredObjectiveAndPersist() {
    var p = accepted();
    var b = start(p);
    assertEquals(STAGE, b.getStage().getId());
    assertNotNull(finish(p, b, stats(1)));
    assertEquals(1, progress(p));
    assertEquals(1, p.saved.getSubMissions().get(TARGET).getProgress());
    assertEquals(0, p.getMissionManager().sub(ALTERNATE).getProgress());
    assertNull(finish(p, b, stats(1)));
    assertEquals(1, progress(p));
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 1, 2, 3, 4, 5, 6})
  void everyAuthoredWorldLevelUsesTheActualEventMapping(int world) {
    var p = accepted();
    p.setWorldLevel(world);
    var b = start(p);
    assertEquals(STAGE + world, b.getStage().getId());
    assertNotNull(b.getMissionBattleCustomValues());
    assertNotNull(finish(p, b, stats(1)));
    assertEquals(1, progress(p));
  }

  @Test
  void missingOtherCaseAndFractionalValuesDoNotBecomeAnIntegerVictory() {
    for (int variant = 0; variant < 5; variant++) {
      mission();
      var p = accepted();
      var b = start(p);
      var report =
          switch (variant) {
            case 0 -> BattleStatistics.newInstance();
            case 1 -> stats(0);
            case 2 -> stats(1.5f);
            case 3 -> stats(Math.nextUp(1f));
            default -> {
              var other = BattleStatistics.newInstance();
              other.getMutableCustomValues().next().setKey("_playerwin").setValue(1);
              yield other;
            }
          };
      assertNotNull(finish(p, b, report));
      assertEquals(0, progress(p), "variant=" + variant);
    }
  }

  @Test
  void nonFiniteEmptyDuplicateAndOversizedCoreMapsPreserveTheBattleForACorrectRetry() {
    for (int variant = 0; variant < 7; variant++) {
      mission();
      var p = accepted();
      var b = start(p);
      var bad = stats(1);
      if (variant <= 2)
        bad.getMutableCustomValues()
            .get(0)
            .setValue(
                new float[] {Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY}[variant]);
      if (variant == 3) bad.getMutableCustomValues().next().setKey("_PlayerWin").setValue(0);
      if (variant == 4) bad.getMutableCustomValues().get(0).setKey("");
      if (variant == 5) bad.getMutableCustomValues().get(0).setKey("界".repeat(43));
      if (variant == 6)
        for (int i = 0; i < 128; i++)
          bad.getMutableCustomValues().next().setKey("other" + i).setValue(0);
      assertNull(finish(p, b, bad), "variant=" + variant);
      assertSame(b, p.getBattle());
      assertFalse(b.isSettling());
      assertEquals(0, progress(p));
      assertNotNull(finish(p, b, stats(1)));
      assertEquals(1, progress(p));
    }
  }

  @Test
  void cancelledMovedReplacedOrCorruptTargetsCannotConsumeAnEarlierBattle() {
    for (int variant = 0; variant < 12; variant++) {
      mission();
      var p = accepted();
      var b = start(p);
      switch (variant) {
        case 0 -> p.floor++;
        case 1 -> p.entry++;
        case 2 -> p.setWorldLevel(1);
        case 3 -> p.getGameplay().getCancelledSubs().add(TARGET);
        case 4 -> p.getGameplay().getCancelledMissions().add(MAIN);
        case 5 -> p.getGameplay().getCancelledSubs().add(TRIGGER);
        case 6 ->
            p.getGameplay()
                .getSubMissions()
                .put(TARGET, new emu.lunarcore.game.quest.QuestState(TARGET));
        case 7 ->
            p.getGameplay()
                .getMainMissions()
                .put(MAIN, new emu.lunarcore.game.quest.QuestState(MAIN + 1));
        case 8 ->
            p.getGameplay()
                .getSubMissions()
                .put(TARGET, new emu.lunarcore.game.quest.QuestState(TARGET + 1));
        case 9 -> GameplayDefinitions.subs.get(TRIGGER).setTask(null);
        case 10 -> p.getGameplay().getSubMissions().remove(TRIGGER);
        case 11 ->
            p.getGameplay()
                .getSubMissions()
                .put(TRIGGER, new emu.lunarcore.game.quest.QuestState(TRIGGER));
      }
      assertNotNull(finish(p, b, stats(1)), "variant=" + variant);
      assertEquals(0, progress(p), "variant=" + variant);
    }
  }

  @Test
  void changedConditionsAndUnknownParameterCombinationsRemainPending() {
    for (int variant = 0; variant < 8; variant++) {
      mission();
      var p = accepted();
      var b = start(p);
      var c = GameplayDefinitions.subs.get(TARGET);
      switch (variant) {
        case 0 -> c.setParamType("GreaterEqual");
        case 1 -> c.setParamInt2(EVENT + 1);
        case 2 -> c.setParamInt1(0);
        case 3 -> c.setParamInt3(1);
        case 4 -> c.setParamStr1("other");
        case 5 -> c.setParamIntList(new Integer[] {EVENT});
        case 6 -> c.setProgress(2);
        case 7 -> c.setLevelFloorID(p.floor + 1);
      }
      assertNotNull(finish(p, b, stats(1)));
      assertEquals(0, progress(p), "variant=" + variant);
    }
  }

  @Test
  void absentOrDynamicPeerScriptDoesNotAuthorizeTheReport() {
    for (int variant = 0; variant < 3; variant++) {
      mission();
      var p = accepted();
      var t = GameplayDefinitions.subs.get(TRIGGER);
      if (variant == 0) t.setTask(null);
      else
        t.setTask(
            gson.fromJson(
                source
                    .get("battleScript")
                    .toString()
                    .replace(
                        variant == 1 ? "\"IsDynamic\":false" : "20241054",
                        variant == 1 ? "\"IsDynamic\":true" : "20241055"),
                MissionTaskInfo.class));
      var b = start(p);
      assertNull(b.getMissionBattleCustomValues());
      assertNotNull(finish(p, b, stats(1)));
      assertEquals(0, progress(p));
    }
  }

  @Test
  void targetAcceptedAfterStartWaitsForANewBattle() {
    var p = accepted();
    p.getGameplay().getSubMissions().remove(TARGET);
    p.getGameplay().getSubMissions().remove(ALTERNATE);
    var first = start(p);
    assertNull(first.getMissionBattleCustomValues());
    p.getMissionManager().refresh();
    assertNotNull(finish(p, first, stats(1)));
    assertEquals(0, progress(p));
    assertNotNull(finish(p, start(p), stats(1)));
    assertEquals(1, progress(p));
  }

  @Test
  void sameNumericStagePrefixWithoutTheDeclaredEventIsNotAuthorized() {
    var p = accepted();
    var b = Objects.requireNonNull(service.startBattle(p, STAGE, p.getWorldLevel()));
    assertNull(b.getMissionBattleCustomValues());
    assertNotNull(finish(p, b, stats(1)));
    assertEquals(0, progress(p));
  }

  @Test
  void lossesQuitsAndUnsettledCallbacksDoNotBecomeWinsFromTheCustomFlag() {
    for (var status : List.of(BattleEndStatus.BATTLE_END_LOSE, BattleEndStatus.BATTLE_END_QUIT)) {
      mission();
      var p = accepted();
      var b = start(p);
      b.setStats(stats(1));
      b.setResult(BattleEndStatus.BATTLE_END_WIN);
      p.getMissionManager().onBattleVictory(b);
      assertEquals(0, progress(p));
      assertNotNull(service.finishBattle(p, b.getId(), b.getStage().getId(), status, stats(1)));
      assertEquals(0, progress(p));
    }
  }

  @Test
  void unknownFinishActionDoesNotPublishCompletedMissionOrReplayTheBattle() {
    var p = accepted();
    var c = GameplayDefinitions.subs.get(TARGET);
    c.setFinishActionList(
        gson.fromJson(
            "[{\"FinishActionType\":\"UnknownSourceAction\"}]",
            emu.lunarcore.data.config.MissionInfo.FinishAction[].class));
    var b = start(p);
    assertNotNull(finish(p, b, stats(1)));
    assertEquals(0, progress(p));
    assertNull(finish(p, b, stats(1)));
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 1})
  void actualEventVictoryCompletesTheEventGoalAndOnlyTheSelectedSourceBranch(int reported) {
    var p = accepted();
    var b = start(p);
    assertNotNull(finish(p, b, stats(reported)));
    assertEquals(1, p.getMissionManager().sub(TRIGGER).getProgress());
    assertEquals(reported, progress(p));
    assertEquals(1 - reported, p.getMissionManager().sub(ALTERNATE).getProgress());
    assertNotNull(p.getMissionManager().sub(202200503));
  }
}
