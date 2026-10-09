package org.hkrpg.server.gameplay;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import emu.lunarcore.game.battle.BattleService;
import emu.lunarcore.proto.BattleEndStatusOuterClass.BattleEndStatus;
import emu.lunarcore.proto.BattleStatisticsOuterClass.BattleStatistics;
import emu.lunarcore.server.packet.Retcode;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/** Original entry/source fixtures; qualifying previous targets are explicitly seeded. */
class LocalLegendWinnerTargetsTest {
  @TempDir Path root;
  final LocalLegendEntry46Test fixture = new LocalLegendEntry46Test();

  @BeforeEach
  void setup() throws Exception {
    fixture.root = root;
    fixture.load();
  }

  @AfterEach
  void cleanup() throws Exception {
    fixture.restore();
  }

  LocalLegendBattle enter(int group, int difficulty) {
    GameplayDefinitions.windows.clear();
    GameplayDefinitions.windows.add(
        new GameplayDefinitions.Window(
            50070, LocalLegendCatalog.group(group).module(), 1, Long.MAX_VALUE));
    for (int prior = 1; prior < difficulty; prior++)
      fixture
          .session
          .actor
          .getGameplay()
          .getLocalLegendProgress()
          .merge(LocalLegendCatalog.level(group, prior), 1, 100);
    fixture.session.actor.getQuestManager().refreshUnlocks();
    var result =
        LocalLegendBattles.start(
            fixture.session.actor,
            group,
            difficulty,
            List.of(new LocalLegendParty.Member(8001, 1)),
            Instant.now());
    assertEquals(Retcode.SUCC, result.retcode());
    return result.battle();
  }

  void finish(LocalLegendBattle battle, BattleEndStatus status, BattleStatistics stats) {
    assertSame(
        battle,
        new BattleService(null)
            .finishBattle(
                fixture.session.actor, battle.getId(), battle.getStage().getId(), status, stats));
    assertFalse(fixture.session.actor.isInBattle());
  }

  @ParameterizedTest
  @CsvSource({"1,1", "1,3", "2,1", "2,3", "3,1", "3,3", "4,1", "4,3", "5,1", "5,3"})
  void everyOriginalEasyAndFearlessWinnerExecutesItsMainLeafWithoutTargetStatistics(
      int group, int difficulty) {
    var battle = enter(group, difficulty);
    finish(battle, BattleEndStatus.BATTLE_END_WIN, BattleStatistics.newInstance());
    var progress = fixture.session.actor.getGameplay().getLocalLegendProgress();
    assertEquals(1, progress.mask(battle.level()), "Original winner target is missing");
    assertTrue(progress.hasTarget(battle.level().goals().getFirst().id()));
    for (int i = 1; i < battle.level().goals().size(); i++)
      assertFalse(progress.hasTarget(battle.level().goals().get(i).id()));
    assertEquals(1200, fixture.formal.getCurrentHp(fixture.session.actor.ordinary));
    assertEquals(3000, fixture.formal.getCurrentSp(fixture.session.actor.ordinary));
    assertEquals(0, fixture.formal.saves);
    assertEquals(0, fixture.session.actor.grants);
  }

  @ParameterizedTest
  @CsvSource({"1,1", "1,3", "2,1", "2,3", "3,1", "3,3", "4,1", "4,3", "5,1", "5,3"})
  void aZeroTargetReportCannotUndoTheOriginalWinnerLeaf(int group, int difficulty) {
    var battle = enter(group, difficulty);
    finish(
        battle,
        BattleEndStatus.BATTLE_END_WIN,
        LocalLegendCatalogTest.report(5, battle.level().goals().getFirst().id(), 0, 0));
    assertEquals(
        1, fixture.session.actor.getGameplay().getLocalLegendProgress().mask(battle.level()));
  }

  @ParameterizedTest
  @ValueSource(ints = {1, 2, 3, 4, 5})
  void hardWinnerDoesNotInventTheSeparateDynamicMainTargetCondition(int group) {
    var battle = enter(group, 2);
    finish(battle, BattleEndStatus.BATTLE_END_WIN, BattleStatistics.newInstance());
    assertEquals(
        0, fixture.session.actor.getGameplay().getLocalLegendProgress().mask(battle.level()));
  }

  @ParameterizedTest
  @CsvSource({
    "1,1,BATTLE_END_LOSE", "1,1,BATTLE_END_QUIT", "1,3,BATTLE_END_LOSE", "1,3,BATTLE_END_QUIT",
    "2,1,BATTLE_END_LOSE", "2,1,BATTLE_END_QUIT", "2,3,BATTLE_END_LOSE", "2,3,BATTLE_END_QUIT",
    "3,1,BATTLE_END_LOSE", "3,1,BATTLE_END_QUIT", "3,3,BATTLE_END_LOSE", "3,3,BATTLE_END_QUIT",
    "4,1,BATTLE_END_LOSE", "4,1,BATTLE_END_QUIT", "4,3,BATTLE_END_LOSE", "4,3,BATTLE_END_QUIT",
    "5,1,BATTLE_END_LOSE", "5,1,BATTLE_END_QUIT", "5,3,BATTLE_END_LOSE", "5,3,BATTLE_END_QUIT"
  })
  void theSourceWinnerLeafDoesNotRunOnLoseOrQuit(int group, int difficulty, String status) {
    var battle = enter(group, difficulty);
    finish(battle, BattleEndStatus.valueOf(status), BattleStatistics.newInstance());
    assertEquals(
        0, fixture.session.actor.getGameplay().getLocalLegendProgress().mask(battle.level()));
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 1, 2, 3, 4, 5})
  void sourceWinnerTargetIdentityThresholdAbilityAndScopeCannotBeRebound(int variant)
      throws Exception {
    var catalog = fixture.fixture.fixture.catalog;
    String table = "BattleTargetConfig";
    var rows = catalog.rows(table).deepCopy();
    JsonObject target =
        rows.asList().stream()
            .map(JsonElement::getAsJsonObject)
            .filter(r -> r.get("ID").getAsInt() == 5001804)
            .findFirst()
            .orElseThrow();
    switch (variant) {
      case 0 -> target.addProperty("TargetParam", 2);
      case 1 -> target.addProperty("AbilityName", "BattleTarget_LocalLegend_S2_4");
      case 2 -> target.addProperty("AbilityName", "UnverifiedWinnerAlias");
      case 3 -> {
        table = "LocalLegendStageConfig";
        rows = catalog.rows(table).deepCopy();
        var source =
            rows.asList().stream()
                .map(JsonElement::getAsJsonObject)
                .filter(
                    r ->
                        r.get("GroupID").getAsInt() == 1
                            && r.get("DifficultyLevel").getAsString().equals("Easy"))
                .findFirst()
                .orElseThrow();
        source.getAsJsonArray("BattleTargetList").set(0, new JsonPrimitive(5001809));
      }
      case 4 -> {
        var other =
            rows.asList().stream()
                .map(JsonElement::getAsJsonObject)
                .filter(r -> r.get("ID").getAsInt() == 5001802)
                .findFirst()
                .orElseThrow();
        other.addProperty("AbilityName", "BattleTarget_LocalLegend_S1_4");
      }
      case 5 -> {
        table = "LocalLegendStageConfig";
        rows = catalog.rows(table).deepCopy();
        var source =
            rows.asList().stream()
                .map(JsonElement::getAsJsonObject)
                .filter(
                    r ->
                        r.get("GroupID").getAsInt() == 1
                            && r.get("DifficultyLevel").getAsString().equals("Easy"))
                .findFirst()
                .orElseThrow();
        source.getAsJsonArray("BattleTargetList").set(0, new JsonPrimitive(5001801));
      }
    }
    catalog.write(table, rows);
    assertThrows(IllegalStateException.class, () -> LocalLegendCatalog.load(root));
    assertTrue(LocalLegendCatalog.levels().isEmpty());
  }

  @Test
  void theWholeOriginalAbilitiesBindTheDynamicTargetAndHaveOnlyTheSourceWinnerPredicate()
      throws Exception {
    try (var in =
        getClass().getResourceAsStream("/fixtures/local-legend-winner-target-abilities-4.6.json")) {
      var data =
          JsonParser.parseString(
                  new String(Objects.requireNonNull(in).readAllBytes(), StandardCharsets.UTF_8))
              .getAsJsonObject();
      assertEquals(
          "724b139d8c9c32d12552eb95745a4fee72bfe48b", data.get("sourceCommit").getAsString());
      assertEquals(
          "1d03573599903693f0d4099e73fb4dcaabd5460021dd2e0327100203868288d3",
          data.get("sourceSHA256").getAsString());
      assertEquals(15, data.getAsJsonArray("abilities").size());
      for (var value : data.getAsJsonArray("abilities")) {
        var ability = value.getAsJsonObject();
        var name = ability.get("Name").getAsString();
        var modifier = ability.getAsJsonObject("Modifiers").getAsJsonObject("Modifier_" + name);
        var callbacks = modifier.getAsJsonArray("_CallbackList");
        var create =
            callbacks.asList().stream()
                .map(JsonElement::getAsJsonObject)
                .filter(c -> c.get("Event").getAsString().equals("OnCreate"))
                .findFirst()
                .orElseThrow()
                .getAsJsonArray("CallbackConfig");
        assertEquals(1, create.size());
        assertEquals(
            "RPG.GameCore.SetDynamicValueByBattleTargetID",
            create.get(0).getAsJsonObject().get("$type").getAsString());
        assertEquals(name, create.get(0).getAsJsonObject().get("AbilityName").getAsString());
        assertEquals(
            "_BattleTargetKey",
            create.get(0).getAsJsonObject().get("BattleTargetIDKey").getAsString());
        var leave =
            callbacks.asList().stream()
                .map(JsonElement::getAsJsonObject)
                .filter(c -> c.get("Event").getAsString().equals("OnLeaveBattle"))
                .findFirst()
                .orElseThrow()
                .getAsJsonArray("CallbackConfig");
        assertEquals(1, leave.size());
        var gate = leave.get(0).getAsJsonObject();
        assertEquals(
            JsonParser.parseString("{\"$type\":\"RPG.GameCore.ByLocalWin\"}"),
            gate.get("Predicate"));
        var leaves = gate.getAsJsonArray("SuccessTaskList");
        long targetLeaves =
            leaves.asList().stream()
                .map(JsonElement::getAsJsonObject)
                .filter(
                    l ->
                        l.get("$type").getAsString().equals("RPG.GameCore.SetBattleTargetProgress"))
                .peek(
                    l -> {
                      assertEquals(
                          JsonParser.parseString(
                              "{\"IsDynamic\":true,\"PostfixExpr\":{\"OpCodes\":\"AQAR\",\"FixedValues\":[],\"DynamicHashes\":[416319683]}}"),
                          l.get("BattleTargetID"));
                      assertEquals(
                          JsonParser.parseString(
                              "{\"IsDynamic\":false,\"FixedValue\":{\"Value\":1}}"),
                          l.get("ModifyValue"));
                    })
                .count();
        assertEquals(name.endsWith("_1") ? 0 : 1, targetLeaves);
      }
    }
  }
}
